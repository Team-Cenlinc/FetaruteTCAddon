package org.fetarute.fetaruteTCAddon.dispatcher.graph.network;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.EdgeOverrideRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.portal.PortalLink;

/**
 * 跨世界路网的缓存：输入（各世界的图、运维覆盖、传送门连接）变了，或建网时生效的定时封锁到期，才重建。
 *
 * <p>路网把各世界的运维封锁按建网时刻冻结在里面。定时封锁只会随时间解除、不会随时间生效，所以封锁到期之前路网不会过时；
 * 没有定时封锁时，输入不变就一直沿用同一张路网（视图不变，最短路记忆也不作废）。
 *
 * <p>设了后台执行器时，能用旧网的重建放到后台：后台建好后原子换上，期间旧网照常服务。旧网能用的条件是它<b>不比现状宽</b>——
 * 各世界的图是同一批实例、现在生效的每条封锁旧网都封着、旧网里的每条传送门连接边都还在。这样旧网上规划出的路在现状下一定走得通，
 * 晚一会儿换网只会让列车多等一会儿（到期的封锁晚解除、新加的连接晚可用）。不满足时（换了图、加了封锁、删了连接）在调用方线程上同步重建，
 * 与没有缓存时一样；没有旧网（冷启动）或没设执行器时也同步建。
 *
 * <p>判定旧网能用之后，同一组版本号下不再重复判定：时间推移只会让封锁变少，结论不会翻转。
 */
public final class RailNetworkCache {

  /**
   * 一次建网的输入，在调用方线程上采集；之后只读。
   *
   * @param snapshotVersion 采集前读到的图快照版本（含覆盖变化）
   * @param linkRevision 采集前读到的传送门连接版本
   * @param graphs 各世界的调度图（未叠加覆盖）
   * @param overrides 各世界的运维覆盖快照
   * @param links 传送门连接
   */
  public record Inputs(
      long snapshotVersion,
      long linkRevision,
      Map<UUID, RailGraph> graphs,
      Map<UUID, Map<EdgeId, RailEdgeOverrideRecord>> overrides,
      List<PortalLink> links) {
    public Inputs {
      graphs = Map.copyOf(Objects.requireNonNull(graphs, "graphs"));
      Map<UUID, Map<EdgeId, RailEdgeOverrideRecord>> copied = new HashMap<>();
      Objects.requireNonNull(overrides, "overrides")
          .forEach((world, byEdge) -> copied.put(world, Map.copyOf(byEdge)));
      overrides = Map.copyOf(copied);
      links = List.copyOf(Objects.requireNonNull(links, "links"));
    }
  }

  /**
   * 建好的路网与它的来历。
   *
   * @param baseGraphs 建网时各世界的图实例（未叠加覆盖）
   * @param blockedAtBuild 建网时刻各世界因覆盖而封锁的边
   * @param nextExpiry 建网时生效的定时封锁里最早到期的时刻；没有时为 {@code null}
   */
  private record Built(
      long snapshotVersion,
      long linkRevision,
      Map<UUID, RailGraph> baseGraphs,
      Map<UUID, Set<EdgeId>> blockedAtBuild,
      Instant nextExpiry,
      RailNetwork network) {

    boolean sameVersions(long version, long revision) {
      return snapshotVersion == version && linkRevision == revision;
    }

    boolean expiredAt(Instant now) {
      return nextExpiry != null && !now.isBefore(nextExpiry);
    }

    /** 这张路网不比 {@code inputs} 在 {@code now} 时刻描述的现状宽：上面规划的路在现状下都走得通。 */
    boolean noWiderThan(Inputs inputs, Instant now) {
      if (!baseGraphs.keySet().equals(inputs.graphs().keySet())) {
        return false;
      }
      for (Map.Entry<UUID, RailGraph> entry : inputs.graphs().entrySet()) {
        if (baseGraphs.get(entry.getKey()) != entry.getValue()) {
          return false;
        }
        Set<EdgeId> blocked = blockedAtBuild.getOrDefault(entry.getKey(), Set.of());
        Map<EdgeId, RailEdgeOverrideRecord> overrides =
            inputs.overrides().getOrDefault(entry.getKey(), Map.of());
        for (Map.Entry<EdgeId, RailEdgeOverrideRecord> override : overrides.entrySet()) {
          if (override.getValue().isBlockedEffective(now) && !blocked.contains(override.getKey())) {
            return false;
          }
        }
      }
      return network.portalEdgesBackedBy(inputs.links());
    }
  }

  /**
   * 换图前在后台先拼好的一张路网（{@link #prebuild}），切换时由 {@link #installPrebuilt} 换上。
   *
   * <p>只读；可以丢弃不用。
   */
  public static final class Prebuilt {

    private final Built built;

    private Prebuilt(Built built) {
      this.built = built;
    }

    /** 拼好的路网。 */
    public RailNetwork network() {
      return built.network();
    }
  }

  /** 已判定能继续用的旧网，及判定时的版本号。 */
  private record Verified(Built built, long snapshotVersion, long linkRevision) {
    boolean covers(Built current, long version, long revision) {
      return built == current && snapshotVersion == version && linkRevision == revision;
    }
  }

  private final AtomicReference<Built> current = new AtomicReference<>();
  private final AtomicBoolean rebuilding = new AtomicBoolean();
  private final Runnable onInstalled;
  private final Consumer<String> debugLogger;
  private volatile Executor executor;
  private volatile Clock clock = Clock.systemUTC();
  private volatile Verified verified;

  /**
   * @param onInstalled 换上新路网之后回调（同步重建在调用方线程，后台重建在后台线程）；必须线程安全、不阻塞
   * @param debugLogger 诊断日志
   */
  public RailNetworkCache(Runnable onInstalled, Consumer<String> debugLogger) {
    this.onInstalled = onInstalled != null ? onInstalled : () -> {};
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
  }

  /**
   * 后台重建用的执行器；{@code null} 时一律在调用方线程上同步重建。
   *
   * <p>换执行器不影响已经提交的重建。插件停用前应先置空，再关执行器。
   */
  public void setExecutor(Executor executor) {
    this.executor = executor;
  }

  /** 判断封锁是否到期用的时钟（测试注入）。 */
  public void setClock(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * 按 {@code inputs} 拼一张路网但不换上，可在任意线程调用：换图前在后台先拼好，切换时再用 {@link #installPrebuilt} 换上。
   *
   * @param inputs 按切换后的样子采集的输入（本世界换成新图），版本号是采集前读到的
   */
  public Prebuilt prebuild(Inputs inputs) {
    return new Prebuilt(build(Objects.requireNonNull(inputs, "inputs"), clock.instant()));
  }

  /**
   * 换上切换前拼好的路网，记为 {@code version} 的路网。
   *
   * <p>只在它按 {@code previousVersion} 采集的输入拼成、此后只多了这一次切换（版本恰好到 {@code version}）、传送门连接版本也没变时换上；
   * 否则什么都不做，路网照旧由 {@link #get} 按需重建。换上的这张会让正在后台按旧网重建的那张作废（换不上去）。不触发换新回调：切换方自己会发图变化通知。
   *
   * @param previousVersion 本次切换前的图快照版本
   * @param version 本次切换后的图快照版本
   * @param linkRevision 此刻的传送门连接版本
   * @return 是否换上
   */
  public boolean installPrebuilt(
      Prebuilt prebuilt, long previousVersion, long version, long linkRevision) {
    Objects.requireNonNull(prebuilt, "prebuilt");
    Built built = prebuilt.built;
    if (version != previousVersion + 1
        || built.snapshotVersion() != previousVersion
        || built.linkRevision() != linkRevision) {
      return false;
    }
    current.set(
        new Built(
            version,
            built.linkRevision(),
            built.baseGraphs(),
            built.blockedAtBuild(),
            built.nextExpiry(),
            built.network()));
    verified = null;
    return true;
  }

  /** 丢掉缓存的路网；下一次取时同步重建。正在后台建的那张建好后也不会换上。 */
  public void reset() {
    current.set(null);
    verified = null;
  }

  /**
   * 取当前可用的路网。
   *
   * @param snapshotVersion 调用方此刻读到的图快照版本
   * @param linkRevision 调用方此刻读到的传送门连接版本
   * @param inputs 需要重建或判定时才调用，采集与两个版本号对应（或更新）的输入
   */
  public RailNetwork get(long snapshotVersion, long linkRevision, Supplier<Inputs> inputs) {
    Objects.requireNonNull(inputs, "inputs");
    Instant now = clock.instant();
    Built cached = current.get();
    if (cached != null && cached.sameVersions(snapshotVersion, linkRevision)) {
      if (!cached.expiredAt(now)) {
        return cached.network();
      }
    }
    Executor background = executor;
    if (cached == null || background == null) {
      return buildNow(inputs.get(), now).network();
    }
    if (!cached.sameVersions(snapshotVersion, linkRevision)) {
      Verified check = verified;
      if (check == null || !check.covers(cached, snapshotVersion, linkRevision)) {
        Inputs collected = inputs.get();
        if (!cached.noWiderThan(collected, now)) {
          return buildNow(collected, now).network();
        }
        verified = new Verified(cached, snapshotVersion, linkRevision);
        submit(cached, collected, background);
        return cached.network();
      }
    }
    // 只是定时封锁到期，或已判定旧网能用：旧网照用，没在建就交给后台。
    if (!rebuilding.get()) {
      submit(cached, inputs.get(), background);
    }
    return cached.network();
  }

  private Built buildNow(Inputs inputs, Instant now) {
    Built built = build(inputs, now);
    current.set(built);
    verified = null;
    notifyInstalled();
    return built;
  }

  private void submit(Built base, Inputs inputs, Executor background) {
    if (!rebuilding.compareAndSet(false, true)) {
      return;
    }
    try {
      background.execute(() -> rebuildInBackground(base, inputs));
    } catch (RejectedExecutionException ex) {
      // 执行器已关（插件停用中）：继续用旧网，它不比现状宽。
      rebuilding.set(false);
    }
  }

  private void rebuildInBackground(Built base, Inputs inputs) {
    boolean installed = false;
    try {
      Built built = build(inputs, clock.instant());
      // 期间被同步重建或 reset 换掉的，以那一份为准：同步重建用的输入一定更新。
      installed = current.compareAndSet(base, built);
    } catch (RuntimeException ex) {
      debugLogger.accept(
          "跨世界路网后台重建失败，继续用旧网: " + ex.getClass().getSimpleName() + " " + ex.getMessage());
    } finally {
      rebuilding.set(false);
    }
    if (installed) {
      notifyInstalled();
    }
  }

  private void notifyInstalled() {
    try {
      onInstalled.run();
    } catch (RuntimeException ex) {
      debugLogger.accept("跨世界路网换新回调失败: " + ex);
    }
  }

  /** 按 {@code at} 时刻的覆盖拼路网，并记下建网时的图实例、封锁与最早到期时刻。 */
  private static Built build(Inputs inputs, Instant at) {
    Map<UUID, RailGraph> graphs = new HashMap<>();
    Map<UUID, Set<EdgeId>> blocked = new HashMap<>();
    Instant nextExpiry = null;
    for (Map.Entry<UUID, RailGraph> entry : inputs.graphs().entrySet()) {
      UUID worldId = entry.getKey();
      // 各世界叠加自己的运维封锁：从本世界规划跨到别的世界时，也要绕开那边封锁的边。
      Map<EdgeId, RailEdgeOverrideRecord> overrides =
          inputs.overrides().getOrDefault(worldId, Map.of());
      graphs.put(
          worldId,
          overrides.isEmpty()
              ? entry.getValue()
              : new EdgeOverrideRailGraph(entry.getValue(), overrides, at));
      Set<EdgeId> blockedHere = new HashSet<>();
      for (Map.Entry<EdgeId, RailEdgeOverrideRecord> override : overrides.entrySet()) {
        RailEdgeOverrideRecord record = override.getValue();
        if (!record.isBlockedEffective(at)) {
          continue;
        }
        blockedHere.add(override.getKey());
        // 手动封锁不随时间解除；只有单靠定时封着的边会到期。
        if (!record.blockedManual() && record.blockedUntil().isPresent()) {
          Instant until = record.blockedUntil().get();
          if (nextExpiry == null || until.isBefore(nextExpiry)) {
            nextExpiry = until;
          }
        }
      }
      blocked.put(worldId, Set.copyOf(blockedHere));
    }
    return new Built(
        inputs.snapshotVersion(),
        inputs.linkRevision(),
        inputs.graphs(),
        Map.copyOf(blocked),
        nextExpiry,
        RailNetwork.build(graphs, inputs.links()));
  }
}
