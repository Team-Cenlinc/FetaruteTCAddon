package org.fetarute.fetaruteTCAddon.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fetarute.fetaruteTCAddon.api.operator.OperatorApi;
import org.fetarute.fetaruteTCAddon.web.gtfs.GtfsFeedService;
import org.fetarute.fetaruteTCAddon.web.gtfs.GtfsFeedTables;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeService;

/**
 * 只读 HTTP 接口的路由：按路径分发到各接口，回应经 {@link WebResponseCache} 短时缓存。只认 GET 与 HEAD。纯逻辑，不碰网络与服务器对象，可在任意线程调用。
 *
 * <ul>
 *   <li>{@code /v1/meta}：版本、本服的服务器 ID、服务器时刻与时区、GTFS 版本、接口清单
 *   <li>{@code /v1/operators}、{@code /v1/lines}、{@code /v1/stations}：运营商、线路、车站（含停靠线路与车站组）
 *   <li>{@code /v1/trains}：在线列车
 *   <li>{@code /v1/stations/{运营商}/{站码}/board?line=&horizon=}：站牌（{@code horizon} 秒，默认
 *       600，60–7200，向上取整到 5 分钟一档）；车站、线路按车站目录认，不存在时 404
 *   <li>{@code /v1/gtfs.zip}：静态 GTFS 包（缓存 300 秒）
 *   <li>{@code /v1/gtfs-rt/trip-updates|vehicle-positions|alerts}：GTFS-Realtime（protobuf；加 {@code
 *       ?format=json} 出调试 JSON）
 * </ul>
 */
final class WebApiRouter {

  static final int META_MAX_AGE = 10;
  static final int CATALOG_MAX_AGE = 60;
  static final int TRAINS_MAX_AGE = 5;
  static final int BOARD_MAX_AGE = 10;

  /** 静态包的有效期；与 {@link GtfsFeedTables#MAX_AGE_SECONDS} 一致（重新发布时刻表后最多这么久换成新包）。 */
  static final int GTFS_MAX_AGE = GtfsFeedTables.MAX_AGE_SECONDS;

  static final int TRIP_UPDATES_MAX_AGE = 10;
  static final int VEHICLE_POSITIONS_MAX_AGE = 5;
  static final int ALERTS_MAX_AGE = 30;

  static final int DEFAULT_HORIZON_SECONDS = 600;
  static final int MIN_HORIZON_SECONDS = 60;
  static final int MAX_HORIZON_SECONDS = 7200;

  /** 站牌时间窗按这么多秒一档：时间窗是缓存键的一部分，档数少才接得住缓存。 */
  static final int HORIZON_STEP_SECONDS = 300;

  static final List<String> ENDPOINTS =
      List.of(
          "/v1/meta",
          "/v1/operators",
          "/v1/lines",
          "/v1/stations",
          "/v1/trains",
          "/v1/stations/{operator}/{station}/board?line=&horizon=",
          "/v1/gtfs.zip",
          "/v1/gtfs-rt/trip-updates?format=json",
          "/v1/gtfs-rt/vehicle-positions?format=json",
          "/v1/gtfs-rt/alerts?format=json");

  /** 代码里只认字母、数字与 {@code -_.}（运营商、站码、线路代码）。 */
  private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

  private static final Pattern BOARD = Pattern.compile("/v1/stations/([^/]+)/([^/]+)/board");

  /** 下载文件名里只放这些字符，避免服务器 ID 把引号或换行带进响应头。 */
  private static final Pattern FILENAME_SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private final WebApiData data;
  private final WebResponseCache cache;
  private final Clock clock;
  private final String apiVersion;
  private final Supplier<Optional<String>> serverId;
  private final GtfsFeedService gtfs;
  private final GtfsRealtimeService realtime;
  private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

  /**
   * @param serverId 本服的服务器 ID（配置里的 {@code server-id}，重载后可能变）；没配时为空
   * @param gtfs 静态 GTFS 服务
   * @param realtime GTFS-Realtime 服务
   */
  WebApiRouter(
      WebApiData data,
      WebResponseCache cache,
      Clock clock,
      String apiVersion,
      Supplier<Optional<String>> serverId,
      GtfsFeedService gtfs,
      GtfsRealtimeService realtime) {
    this.data = Objects.requireNonNull(data, "data");
    this.cache = Objects.requireNonNull(cache, "cache");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.apiVersion = Objects.requireNonNull(apiVersion, "apiVersion");
    this.serverId = Objects.requireNonNull(serverId, "serverId");
    this.gtfs = Objects.requireNonNull(gtfs, "gtfs");
    this.realtime = Objects.requireNonNull(realtime, "realtime");
  }

  WebResponse route(WebRequest request) {
    if (!"GET".equals(request.method()) && !request.head()) {
      return WebResponse.error(405, "method-not-allowed");
    }
    String path = trimSlash(request.path());
    switch (path) {
      case "/v1/meta":
        // server-id 也是缓存键的一部分：重载改了它，下一次请求就换，既不用重启接口，也不在主线程上等缓存过期。
        Optional<String> id = serverId.get();
        return cache.get(
            path + '\u0000' + id.orElse(""),
            META_MAX_AGE,
            () ->
                json(
                    WebApiViews.meta(
                        apiVersion, id, now(), clock.getZone(), ENDPOINTS, gtfs.summary())));
      case "/v1/operators":
        return cache.get(
            path, CATALOG_MAX_AGE, () -> json(WebApiViews.operators(now(), data.operators())));
      case "/v1/lines":
        return cache.get(
            path,
            CATALOG_MAX_AGE,
            () -> json(WebApiViews.lines(now(), data.lines(), operatorCodes(), data::lineStatus)));
      case "/v1/stations":
        return cache.get(
            path,
            CATALOG_MAX_AGE,
            () ->
                json(
                    WebApiViews.stations(
                        now(),
                        data.stations(),
                        operatorCodes(),
                        data::linesServing,
                        data.stationGroups())));
      case "/v1/trains":
        return cache.get(
            path, TRAINS_MAX_AGE, () -> json(WebApiViews.trains(now(), data.trains())));
      case "/v1/gtfs.zip":
        return gtfsZip();
      case "/v1/gtfs-rt/trip-updates":
        return realtime(GtfsRealtimeService.Feed.TRIP_UPDATES, TRIP_UPDATES_MAX_AGE, request);
      case "/v1/gtfs-rt/vehicle-positions":
        return realtime(
            GtfsRealtimeService.Feed.VEHICLE_POSITIONS, VEHICLE_POSITIONS_MAX_AGE, request);
      case "/v1/gtfs-rt/alerts":
        return realtime(GtfsRealtimeService.Feed.ALERTS, ALERTS_MAX_AGE, request);
      default:
        break;
    }
    Matcher board = BOARD.matcher(path);
    if (board.matches()) {
      return board(board.group(1), board.group(2), request.query());
    }
    return WebResponse.error(404, "not-found");
  }

  private WebResponse board(String operator, String station, Map<String, String> query) {
    String line = query.getOrDefault("line", "").trim();
    if (!CODE.matcher(operator).matches()
        || !CODE.matcher(station).matches()
        || (!line.isEmpty() && !CODE.matcher(line).matches())) {
      return WebResponse.error(400, "bad-code");
    }
    int horizon = horizonSeconds(query.get("horizon"));
    if (horizon < 0) {
      return WebResponse.error(400, "bad-horizon");
    }
    // 先按车站目录认站与线路：不存在的不算（随便编的站码不会每个都算一遍站牌），存在的换成目录里的规范写法，缓存键与回显都用它
    Optional<WebApiData.StationCodes> found = data.station(operator, station);
    if (found.isEmpty()) {
      return WebResponse.error(404, "station-not-found");
    }
    Optional<String> lineCode = Optional.empty();
    if (!line.isEmpty()) {
      lineCode = data.lineCode(found.get().operatorCode(), line);
      if (lineCode.isEmpty()) {
        return WebResponse.error(404, "line-not-found");
      }
    }
    String operatorCode = found.get().operatorCode();
    String stationCode = found.get().stationCode();
    String filter = lineCode.orElse(null);
    String key =
        "board|" + operatorCode + "|" + stationCode + "|" + lineCode.orElse("") + "|" + horizon;
    return cache.get(
        key,
        BOARD_MAX_AGE,
        () ->
            json(
                WebApiViews.board(
                    now(),
                    operatorCode,
                    stationCode,
                    data.board(operatorCode, stationCode, filter, Duration.ofSeconds(horizon)))));
  }

  /**
   * 静态 GTFS 包：zip 的 ETag 按 {@code feed_version}（内容摘要）算。
   *
   * <p>缓存键带上服务器 ID：重载后改了 {@code server-id} 时，文件名与包内 ID 会一起换成新前缀，不会被旧缓存挡 300 秒。数据还没起来时在 缓存供应商里抛
   * {@link WebUnavailableException}，映射成 503 而不是 500；只有缓存过期时才会去看数据源，命中缓存不重新生成。
   */
  private WebResponse gtfsZip() {
    String key = "/v1/gtfs.zip|" + serverId.get().orElse("");
    String disposition = "attachment; filename=\"" + gtfsFileName() + "\"";
    try {
      return cache.get(
          key,
          GTFS_MAX_AGE,
          () -> {
            Optional<GtfsFeedService.Packaged> packaged = gtfs.packaged();
            if (packaged.isEmpty()) {
              throw new WebUnavailableException("GTFS 静态包还没准备好");
            }
            GtfsFeedService.Packaged value = packaged.get();
            return WebResponseCache.Rendered.binary(
                value.zip(), "application/zip", value.version(), disposition);
          });
    } catch (WebUnavailableException ex) {
      return WebResponse.error(503, "gtfs-not-ready");
    }
  }

  /** 下载文件名：有服务器 ID 时带上它，几台服务器的包放在一起也分得清；不认得的字符不进响应头。 */
  private String gtfsFileName() {
    return serverId
        .get()
        .map(id -> "gtfs-" + (FILENAME_SAFE.matcher(id).matches() ? id : "server") + ".zip")
        .orElse("gtfs.zip");
  }

  /**
   * 一种 GTFS-Realtime feed。
   *
   * <p>{@code format} 只认 {@code json} 与 {@code proto}/{@code protobuf}（空为 protobuf）；JSON 与 protobuf
   * 各自缓存，缓存键带上格式。
   */
  private WebResponse realtime(GtfsRealtimeService.Feed type, int maxAge, WebRequest request) {
    Optional<Boolean> parsed = jsonFormat(request.query().get("format"));
    if (parsed.isEmpty()) {
      return WebResponse.error(400, "bad-format");
    }
    boolean json = parsed.get();
    String key =
        "gtfs-rt|"
            + type.name()
            + "|"
            + (json ? "json" : "proto")
            + "|"
            + serverId.get().orElse("");
    try {
      return cache.get(
          key,
          maxAge,
          () -> {
            Optional<GtfsRealtimeService.Rendered> rendered = realtime.render(type, json);
            if (rendered.isEmpty()) {
              throw new WebUnavailableException("GTFS-Realtime 还没准备好");
            }
            GtfsRealtimeService.Rendered value = rendered.get();
            return WebResponseCache.Rendered.binary(
                value.body(), value.contentType(), value.identity(), "");
          });
    } catch (WebUnavailableException ex) {
      return WebResponse.error(503, "gtfs-not-ready");
    }
  }

  /**
   * {@code format} 参数：空、{@code proto}、{@code protobuf} 为 protobuf，{@code json} 为调试 JSON；其余为空（400）。
   *
   * @param raw 原始值
   */
  static Optional<Boolean> jsonFormat(String raw) {
    String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    return switch (value) {
      case "", "proto", "protobuf", "binary" -> Optional.of(Boolean.FALSE);
      case "json" -> Optional.of(Boolean.TRUE);
      default -> Optional.empty();
    };
  }

  /** {@code horizon} 参数（秒）：没有时取默认，向上取整到 {@link #HORIZON_STEP_SECONDS} 一档；不是整数或超出范围时为 -1。 */
  static int horizonSeconds(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_HORIZON_SECONDS;
    }
    try {
      int value = Integer.parseInt(raw.trim());
      return value >= MIN_HORIZON_SECONDS && value <= MAX_HORIZON_SECONDS
          ? (value + HORIZON_STEP_SECONDS - 1) / HORIZON_STEP_SECONDS * HORIZON_STEP_SECONDS
          : -1;
    } catch (NumberFormatException ex) {
      return -1;
    }
  }

  private Map<UUID, String> operatorCodes() {
    Map<UUID, String> out = new HashMap<>();
    for (OperatorApi.OperatorInfo operator : data.operators()) {
      out.put(operator.id(), operator.code());
    }
    return out;
  }

  private Instant now() {
    return clock.instant();
  }

  /** 正文与算 ETag 用的那部分：生成时刻每次都变、不代表内容变了，不算进 ETag（含 meta 里 gtfs 的生成时刻）。 */
  private WebResponseCache.Rendered json(Map<String, Object> view) {
    Map<String, Object> identity = new LinkedHashMap<>(view);
    identity.remove(WebApiViews.GENERATED_AT);
    if (identity.get("gtfs") instanceof Map<?, ?> gtfsView) {
      Map<String, Object> copy = new LinkedHashMap<>();
      gtfsView.forEach((key, value) -> copy.put(String.valueOf(key), value));
      copy.remove(WebApiViews.GENERATED_AT);
      identity.put("gtfs", copy);
    }
    return WebResponseCache.Rendered.json(gson.toJson(view), gson.toJson(identity));
  }

  private static String trimSlash(String path) {
    String out = path;
    while (out.length() > 1 && out.endsWith("/")) {
      out = out.substring(0, out.length() - 1);
    }
    return out;
  }
}
