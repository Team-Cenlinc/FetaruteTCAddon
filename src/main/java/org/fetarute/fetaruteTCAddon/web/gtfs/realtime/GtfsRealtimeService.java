package org.fetarute.fetaruteTCAddon.web.gtfs.realtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.fetarute.fetaruteTCAddon.web.gtfs.GtfsFeed;
import org.fetarute.fetaruteTCAddon.web.gtfs.GtfsFeedService;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeFeed.FeedMessage;

/**
 * 三种 GTFS-Realtime feed 的生成入口：静态包版本、运行时快照、protobuf/JSON 编码都从这里出。
 *
 * <p>ETag 按「静态包版本 + 实体内容」算，不含报文头的时间戳：报文头每次生成都会变，但内容没变时中转应该拿到 304。三种 feed 各自的有效期由路由层决定 （TripUpdates
 * 10 秒、VehiclePositions 5 秒、Alerts 30 秒）。
 *
 * <p>静态包还没起来时返回空，路由层回 503；运行时服务没起来时按空快照发一版合法报文（没有实体），而不是让接口失败。
 */
public final class GtfsRealtimeService {

  /** 三种实时 feed。 */
  public enum Feed {
    /** 车次进度、晚点与越站。 */
    TRIP_UPDATES,
    /** 车辆位置与载客。 */
    VEHICLE_POSITIONS,
    /** 服务告警。 */
    ALERTS
  }

  /** protobuf 的 {@code Content-Type}。 */
  public static final String PROTOBUF_CONTENT_TYPE = "application/x-protobuf";

  /**
   * 一次生成的正文。
   *
   * @param body 正文（protobuf 或 JSON）
   * @param contentType 正文类型
   * @param identity 算 ETag 用的内容（静态包版本 + 报文头生成时刻 + 实体）
   */
  public record Rendered(byte[] body, String contentType, String identity) {

    public Rendered {
      body = Objects.requireNonNull(body, "body").clone();
      Objects.requireNonNull(contentType, "contentType");
      Objects.requireNonNull(identity, "identity");
    }

    /** 返回副本：正文交给 HTTP 层之后不再变。 */
    @Override
    public byte[] body() {
      return body.clone();
    }
  }

  private final GtfsFeedService feeds;
  private final GtfsRealtimeSource source;
  private final Clock clock;

  /** 同一条坏记录告警在这一时限内不重复；否则每 5 秒生成一次车辆位置会把日志刷爆。 */
  private static final long WARN_COOLDOWN_MILLIS = 60_000L;

  /** 告警去重表的上限：超过后顺手清掉已经过冷却的键。 */
  private static final int MAX_WARN_KEYS = 128;

  private final Supplier<Function<String, String>> texts;
  private final Consumer<String> warn;
  private final Map<String, Long> warnedAt = new ConcurrentHashMap<>();

  /**
   * @param feeds 静态包服务（提供版本号与 trip/stop 索引）
   * @param source 运行时快照
   * @param clock 时钟
   * @param texts 语言文件读取（线路运行状况文案复用站台屏）；可为 null
   * @param warn 告警日志；可为 null
   */
  public GtfsRealtimeService(
      GtfsFeedService feeds,
      GtfsRealtimeSource source,
      Clock clock,
      Supplier<Function<String, String>> texts,
      Consumer<String> warn) {
    this.feeds = Objects.requireNonNull(feeds, "feeds");
    this.source = Objects.requireNonNull(source, "source");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.texts = texts == null ? () -> null : texts;
    this.warn = warn == null ? message -> {} : warn;
  }

  /**
   * 生成一版实时报文。
   *
   * @param feed 哪种 feed
   * @param json 是否出 JSON 调试格式
   * @return 正文；静态包还没起来时为空
   */
  public Optional<Rendered> render(Feed feed, boolean json) {
    Optional<GtfsFeed> staticFeed = feeds.feed();
    if (staticFeed.isEmpty()) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    try {
      GtfsRealtimeSnapshot snapshot =
          source.snapshot(now).orElseGet(() -> GtfsRealtimeSnapshot.empty(now));
      GtfsRealtimeBuilder builder =
          new GtfsRealtimeBuilder(
              staticFeed.get(), snapshot, now, texts.get(), this::warnThrottled);
      FeedMessage message =
          switch (feed) {
            case TRIP_UPDATES -> builder.tripUpdates();
            case VEHICLE_POSITIONS -> builder.vehiclePositions();
            case ALERTS -> builder.alerts();
          };
      String identity =
          staticFeed.get().version()
              + "|"
              + message.header().timestamp()
              + "|"
              + digest(GtfsRealtimeProtobuf.entitiesPayload(message.entities()));
      if (json) {
        return Optional.of(
            new Rendered(
                GtfsRealtimeJson.write(message).getBytes(StandardCharsets.UTF_8),
                "application/json; charset=utf-8",
                identity));
      }
      return Optional.of(
          new Rendered(GtfsRealtimeProtobuf.encode(message), PROTOBUF_CONTENT_TYPE, identity));
    } catch (RuntimeException ex) {
      warnThrottled("GTFS-Realtime 生成失败：" + ex.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  /** 同一条告警一分钟内只记一次；坏记录不会再让日志按缓存周期反复刷屏。 */
  private void warnThrottled(String message) {
    long now = clock.millis();
    Long previous = warnedAt.get(message);
    if (previous != null && now - previous < WARN_COOLDOWN_MILLIS) {
      return;
    }
    warnedAt.put(message, now);
    if (warnedAt.size() > MAX_WARN_KEYS) {
      warnedAt.entrySet().removeIf(entry -> now - entry.getValue() >= WARN_COOLDOWN_MILLIS);
    }
    warn.accept(message);
  }

  private static String digest(byte[] entities) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(entities);
      return HexFormat.of().formatHex(hash, 0, 8);
    } catch (NoSuchAlgorithmException ex) {
      return Integer.toHexString(java.util.Arrays.hashCode(entities));
    }
  }
}
