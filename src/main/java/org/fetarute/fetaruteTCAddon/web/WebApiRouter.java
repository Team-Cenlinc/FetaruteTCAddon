package org.fetarute.fetaruteTCAddon.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fetarute.fetaruteTCAddon.api.operator.OperatorApi;

/**
 * 只读 HTTP 接口的路由：按路径分发到各接口，回应经 {@link WebResponseCache} 短时缓存。只认 GET 与 HEAD。纯逻辑，不碰网络与服务器对象，可在任意线程调用。
 *
 * <ul>
 *   <li>{@code /v1/meta}：版本、服务器时刻与时区、接口清单
 *   <li>{@code /v1/operators}、{@code /v1/lines}、{@code /v1/stations}：运营商、线路、车站（含停靠线路与车站组）
 *   <li>{@code /v1/trains}：在线列车
 *   <li>{@code /v1/stations/{运营商}/{站码}/board?line=&horizon=}：站牌（{@code horizon} 秒，默认
 *       600，60–7200，向上取整到 5 分钟一档）；车站、线路按车站目录认，不存在时 404
 * </ul>
 */
final class WebApiRouter {

  static final int META_MAX_AGE = 10;
  static final int CATALOG_MAX_AGE = 60;
  static final int TRAINS_MAX_AGE = 5;
  static final int BOARD_MAX_AGE = 10;

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
          "/v1/stations/{operator}/{station}/board?line=&horizon=");

  /** 代码里只认字母、数字与 {@code -_.}（运营商、站码、线路代码）。 */
  private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

  private static final Pattern BOARD = Pattern.compile("/v1/stations/([^/]+)/([^/]+)/board");

  private final WebApiData data;
  private final WebResponseCache cache;
  private final Clock clock;
  private final String apiVersion;
  private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

  WebApiRouter(WebApiData data, WebResponseCache cache, Clock clock, String apiVersion) {
    this.data = Objects.requireNonNull(data, "data");
    this.cache = Objects.requireNonNull(cache, "cache");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.apiVersion = Objects.requireNonNull(apiVersion, "apiVersion");
  }

  WebResponse route(WebRequest request) {
    if (!"GET".equals(request.method()) && !request.head()) {
      return WebResponse.error(405, "method-not-allowed");
    }
    String path = trimSlash(request.path());
    switch (path) {
      case "/v1/meta":
        return cache.get(
            path,
            META_MAX_AGE,
            () -> json(WebApiViews.meta(apiVersion, now(), clock.getZone(), ENDPOINTS)));
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

  /** 正文与算 ETag 用的那部分：生成时刻每次都变、不代表内容变了，不算进 ETag。 */
  private WebResponseCache.Rendered json(Map<String, Object> view) {
    Map<String, Object> identity = new java.util.LinkedHashMap<>(view);
    identity.remove(WebApiViews.GENERATED_AT);
    return new WebResponseCache.Rendered(gson.toJson(view), gson.toJson(identity));
  }

  private static String trimSlash(String path) {
    String out = path;
    while (out.length() > 1 && out.endsWith("/")) {
      out = out.substring(0, out.length() - 1);
    }
    return out;
  }
}
