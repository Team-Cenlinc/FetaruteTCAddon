package org.fetarute.fetaruteTCAddon.web.gtfs;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 静态 GTFS 的缓存与生成入口。
 *
 * <p>按 {@link GtfsFeedTables#MAX_AGE_SECONDS} 检查一次：有效期内直接复用；到期时先取一份新快照算内容指纹 （{@link
 * GtfsSnapshotHash}），指纹没变就只续期，不重新生成 CSV/zip；变了才完整构建。这样“内容没变”的 5 分钟检查只需约 1 ms，而完整构建约 0.15–1 秒。
 *
 * <p>命中缓存不取锁；只有缓存过期或过期后需要重建时才在 {@code buildLock} 上单飞，重建期间其它请求等这一次结果，不会一起扑到快照与打包上。
 *
 * <p>快照或打包抛异常、或车站目录还没加载时，继续返回上一份旧包（last-good）；没有旧包才返回空，路由据此回 {@code 503}。生成与 hash 都在 web
 * 线程完成，不碰主线程。
 */
public final class GtfsFeedService {

  /**
   * 一次生成的 feed 与 zip。
   *
   * @param hash 生成时输入快照的内容指纹；到期时用它判断要不要重建
   * @param serverId 生成时的服务器 ID：重载后改了 server-id 时旧缓存立即作废
   */
  private record Cached(
      GtfsFeed feed,
      byte[] zip,
      long expiresAtMillis,
      Optional<String> serverId,
      long hash,
      long hardExpiresAtMillis) {

    Cached extended(long newExpiresAtMillis) {
      return new Cached(feed, zip, newExpiresAtMillis, serverId, hash, hardExpiresAtMillis);
    }
  }

  /**
   * 给 {@code /v1/meta} 的摘要。
   *
   * @param version 静态包版本（与实时源 {@code FeedHeader.feed_version} 相同）
   * @param generatedAt 生成时刻
   */
  public record Summary(String version, Instant generatedAt) {}

  /**
   * 一次取出的静态包与 zip：版本与字节来自同一次生成，不会拿到半新半旧的组合。
   *
   * @param version 静态包版本
   * @param zip zip 字节
   */
  public record Packaged(String version, byte[] zip) {

    public Packaged {
      Objects.requireNonNull(version, "version");
      zip = Objects.requireNonNull(zip, "zip").clone();
    }

    /** 返回副本：调用方拿到的数组不会反向改到缓存。 */
    @Override
    public byte[] zip() {
      return zip.clone();
    }
  }

  /** 生成失败告警的冷却：坏数据不会按缓存周期反复刷屏。 */
  private static final long FAILURE_COOLDOWN_MILLIS = 60_000L;

  /**
   * 指纹没变时最多续期到这么久，之后强制重建一次。
   *
   * <p>兜底：如果将来有输入忘了加进 {@link GtfsSnapshotHash}，最多 30 分钟也会纠正一次；实服规模下一次构建 0.15–1 秒，代价可以接受。
   */
  private static final long HARD_MAX_AGE_MILLIS = 30L * 60_000L;

  private final GtfsSource source;
  private final Clock clock;
  private final Consumer<String> warn;
  private final Supplier<Optional<String>> identity;
  private final boolean checkIdentity;
  private final Object buildLock = new Object();
  private volatile Cached cached;
  private String warnedVersion = "";
  private long lastFailureAtMillis = -FAILURE_COOLDOWN_MILLIS;

  /**
   * @param source 数据源
   * @param clock 时钟（决定日历日期、生成时刻与缓存有效期）
   * @param warn 告警日志（导出时跳过的坏数据，每个版本只记一次；生成失败一分钟内只记一次）
   * @param identity 当前服务器 ID（{@code server-id}）；变了就作废旧缓存，包内 ID 与前缀要一起换
   */
  public GtfsFeedService(
      GtfsSource source, Clock clock, Consumer<String> warn, Supplier<Optional<String>> identity) {
    this(source, clock, warn, identity, true);
  }

  /** 兼容调用：不检查服务器 ID（测试与只生成一次的调用方）。 */
  public GtfsFeedService(GtfsSource source, Clock clock, Consumer<String> warn) {
    this(source, clock, warn, Optional::empty, false);
  }

  private GtfsFeedService(
      GtfsSource source,
      Clock clock,
      Consumer<String> warn,
      Supplier<Optional<String>> identity,
      boolean checkIdentity) {
    this.source = Objects.requireNonNull(source, "source");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.warn = warn == null ? message -> {} : warn;
    this.identity = identity == null ? Optional::empty : identity;
    this.checkIdentity = checkIdentity;
  }

  /** 当前的静态包；数据源未就绪且没有旧包时为空。 */
  public Optional<GtfsFeed> feed() {
    return current().map(Cached::feed);
  }

  /** 当前的静态包版本与 zip；数据源未就绪且没有旧包时为空。 */
  public Optional<Packaged> packaged() {
    return current().map(item -> new Packaged(item.feed().version(), item.zip()));
  }

  /** 当前 feed 的版本与生成时刻；数据源未就绪且没有旧包时为空。 */
  public Optional<Summary> summary() {
    return current().map(item -> new Summary(item.feed().version(), item.feed().generatedAt()));
  }

  /** 有效期内的缓存；没有、过期或服务器 ID 变了都算不可用。 */
  private Optional<Cached> usable(Cached value) {
    return value != null
            && clock.millis() < value.expiresAtMillis()
            && (!checkIdentity || Objects.equals(value.serverId(), currentIdentity()))
        ? Optional.of(value)
        : Optional.empty();
  }

  /** 取当前缓存：命中直接返回；过期时先算输入指纹，没变只续期，变了才重建。 */
  private Optional<Cached> current() {
    Cached value = cached;
    Optional<Cached> usable = usable(value);
    if (usable.isPresent()) {
      return usable;
    }
    return refresh();
  }

  /** 单飞刷新：到期、服务器 ID 变化或首次请求都走这里。 */
  private Optional<Cached> refresh() {
    synchronized (buildLock) {
      Cached current = cached;
      Optional<Cached> usable = usable(current);
      if (usable.isPresent()) {
        return usable;
      }
      long now = clock.millis();
      Optional<GtfsSnapshot> snapshot;
      try {
        snapshot = source.snapshot();
      } catch (RuntimeException ex) {
        warnFailure(ex);
        return Optional.ofNullable(current);
      }
      if (snapshot.isEmpty() || Thread.currentThread().isInterrupted()) {
        // 数据源暂时不可用（或正在停插件）：有旧包就继续用，没有才回 503
        return Optional.ofNullable(current);
      }
      try {
        long hash = GtfsSnapshotHash.of(snapshot.get());
        Optional<String> snapshotServerId = snapshot.get().serverId();
        if (current != null
            && current.hash() == hash
            && (!checkIdentity || Objects.equals(current.serverId(), snapshotServerId))
            && now < current.hardExpiresAtMillis()) {
          // 内容没变：只延长有效期，不重新生成 CSV/zip
          Cached extended = current.extended(now + GtfsFeedTables.MAX_AGE_SECONDS * 1000L);
          cached = extended;
          return Optional.of(extended);
        }
        GtfsFeed feed = GtfsFeedBuilder.build(snapshot.get());
        Cached value =
            new Cached(
                feed,
                GtfsFeedTables.zip(feed),
                now + GtfsFeedTables.MAX_AGE_SECONDS * 1000L,
                snapshotServerId,
                hash,
                now + HARD_MAX_AGE_MILLIS);
        cached = value;
        logWarnings(feed);
        return Optional.of(value);
      } catch (RuntimeException ex) {
        warnFailure(ex);
        return Optional.ofNullable(current);
      }
    }
  }

  private Optional<String> currentIdentity() {
    try {
      Optional<String> value = identity.get();
      return value == null ? Optional.empty() : value;
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
  }

  private void logWarnings(GtfsFeed feed) {
    if (feed.version().equals(warnedVersion) || feed.warnings().isEmpty()) {
      return;
    }
    warnedVersion = feed.version();
    for (String message : feed.warnings()) {
      warn.accept("GTFS 导出跳过：" + message);
    }
  }

  private void warnFailure(RuntimeException ex) {
    long now = clock.millis();
    if (now - lastFailureAtMillis < FAILURE_COOLDOWN_MILLIS) {
      return;
    }
    lastFailureAtMillis = now;
    warn.accept("GTFS 生成失败：" + ex.getClass().getSimpleName());
  }
}
