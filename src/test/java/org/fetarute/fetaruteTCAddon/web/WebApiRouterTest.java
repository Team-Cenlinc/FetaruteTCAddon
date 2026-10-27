package org.fetarute.fetaruteTCAddon.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.fetarute.fetaruteTCAddon.api.eta.EtaApi;
import org.fetarute.fetaruteTCAddon.api.line.LineApi;
import org.fetarute.fetaruteTCAddon.api.operator.OperatorApi;
import org.fetarute.fetaruteTCAddon.api.station.StationApi;
import org.fetarute.fetaruteTCAddon.api.train.TrainApi;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatus;
import org.fetarute.fetaruteTCAddon.web.gtfs.GtfsFeedService;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("只读 HTTP 接口的路由")
class WebApiRouterTest {

  private static final UUID OPERATOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID LINE = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID STATION = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final Instant NOW = Instant.parse("2026-10-21T20:00:00Z");

  /** 记下各项被取了几次。 */
  private static final class FakeData implements WebApiData {
    int lineCalls;
    int trainCalls;
    Optional<PidsLineStatus> status = Optional.empty();
    final List<String> boardCalls = new ArrayList<>();

    @Override
    public Collection<OperatorApi.OperatorInfo> operators() {
      return List.of(
          new OperatorApi.OperatorInfo(
              OPERATOR,
              "SURC",
              UUID.randomUUID(),
              "示例铁路",
              Optional.empty(),
              Optional.of("#336699"),
              0,
              Optional.empty()));
    }

    @Override
    public Collection<LineApi.LineInfo> lines() {
      lineCalls++;
      return List.of(
          new LineApi.LineInfo(
              LINE,
              "MT",
              OPERATOR,
              "地铁线",
              Optional.of("Metro"),
              LineApi.ServiceType.METRO,
              Optional.of("#ff0000"),
              LineApi.LineStatus.ACTIVE,
              Optional.empty()));
    }

    @Override
    public Collection<StationApi.StationInfo> stations() {
      return List.of(
          new StationApi.StationInfo(
              STATION,
              "HHU",
              OPERATOR,
              Optional.of(LINE),
              "河湾",
              Optional.empty(),
              Optional.of("Towny"),
              Optional.of(new StationApi.Position(447.5, 74.0, 996.5)),
              Optional.of("SURC:S:HHU:1")));
    }

    @Override
    public Collection<StationApi.StationGroupInfo> stationGroups() {
      return List.of();
    }

    @Override
    public List<StationApi.ServingLine> linesServing(UUID stationId) {
      return List.of(
          new StationApi.ServingLine(
              LINE,
              "SURC",
              "MT",
              "地铁线",
              Optional.empty(),
              stationId,
              "HHU",
              Optional.empty(),
              OptionalInt.empty()));
    }

    @Override
    public Optional<StationCodes> station(String operatorCode, String stationCode) {
      return "SURC".equalsIgnoreCase(operatorCode) && "HHU".equalsIgnoreCase(stationCode)
          ? Optional.of(new StationCodes("SURC", "HHU"))
          : Optional.empty();
    }

    @Override
    public Optional<String> lineCode(String operatorCode, String lineCode) {
      return "SURC".equals(operatorCode) && "MT".equalsIgnoreCase(lineCode)
          ? Optional.of("MT")
          : Optional.empty();
    }

    @Override
    public Optional<PidsLineStatus> lineStatus(String operatorCode, String lineCode) {
      return "SURC".equals(operatorCode) && "MT".equals(lineCode) ? status : Optional.empty();
    }

    @Override
    public Collection<TrainApi.TrainSnapshot> trains() {
      trainCalls++;
      return List.of();
    }

    @Override
    public EtaApi.BoardResult board(
        String operator, String stationCode, String lineCode, Duration horizon) {
      boardCalls.add(operator + "|" + stationCode + "|" + lineCode + "|" + horizon.toSeconds());
      return new EtaApi.BoardResult(List.of());
    }
  }

  private final AtomicLong millis = new AtomicLong(NOW.toEpochMilli());
  private final FakeData data = new FakeData();
  private final AtomicReference<Optional<String>> serverId =
      new AtomicReference<>(Optional.empty());
  private static final ZoneId ZONE = ZoneId.of("America/Chicago");

  private final GtfsFeedService gtfs =
      new GtfsFeedService(() -> Optional.empty(), Clock.fixed(NOW, ZONE), message -> {});

  private final GtfsRealtimeService realtime =
      new GtfsRealtimeService(
          gtfs, now -> Optional.empty(), Clock.fixed(NOW, ZONE), () -> key -> "", message -> {});

  private final WebApiRouter router =
      new WebApiRouter(
          data,
          new WebResponseCache(millis::get),
          Clock.fixed(NOW, ZONE),
          "1.14.0",
          serverId::get,
          gtfs,
          realtime);

  private static WebRequest get(String path, Map<String, String> query) {
    return new WebRequest("GET", path, query);
  }

  private static JsonObject json(WebResponse response) {
    return JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8))
        .getAsJsonObject();
  }

  @Test
  @DisplayName("meta 报版本、服务器时区与接口清单")
  void metaReportsVersionAndZone() {
    WebResponse response = router.route(get("/v1/meta", Map.of()));

    assertEquals(200, response.status());
    JsonObject body = json(response);
    assertEquals("1.14.0", body.get("apiVersion").getAsString());
    assertEquals("America/Chicago", body.get("timezone").getAsString());
    assertTrue(body.getAsJsonArray("endpoints").size() >= 5);
    assertEquals(WebApiRouter.META_MAX_AGE, response.maxAgeSeconds());
    assertFalse(response.etag().isEmpty());
    assertFalse(body.has("serverId"), "没配 server-id 时不输出");
  }

  @Test
  @DisplayName("meta 报本服的 server-id，聚合方据此核对来源")
  void metaReportsTheServerId() {
    serverId.set(Optional.of("survival"));

    JsonObject body = json(router.route(get("/v1/meta", Map.of())));

    assertEquals("survival", body.get("serverId").getAsString());
  }

  @Test
  @DisplayName("server-id 改了，下一次 meta 就换，不等缓存过期")
  void metaFollowsTheServerIdWithoutWaitingForTheCache() {
    serverId.set(Optional.of("survival"));
    assertEquals(
        "survival", json(router.route(get("/v1/meta", Map.of()))).get("serverId").getAsString());

    serverId.set(Optional.of("creative"));
    assertEquals(
        "creative", json(router.route(get("/v1/meta", Map.of()))).get("serverId").getAsString());

    serverId.set(Optional.empty());
    assertFalse(json(router.route(get("/v1/meta", Map.of()))).has("serverId"));
  }

  @Test
  @DisplayName("线路带运营商代码；有效期内再取不再读数据，过期后重读")
  void linesAreCachedForTheirMaxAge() {
    WebResponse first = router.route(get("/v1/lines/", Map.of()));
    JsonObject line = json(first).getAsJsonArray("lines").get(0).getAsJsonObject();
    assertEquals("SURC", line.get("operatorCode").getAsString());
    assertEquals("ACTIVE", line.get("status").getAsString());
    assertEquals("Metro", line.get("secondaryName").getAsString());

    assertSame(first, router.route(get("/v1/lines", Map.of())), "末尾的斜杠不影响");
    assertEquals(1, data.lineCalls);

    millis.addAndGet(WebApiRouter.CATALOG_MAX_AGE * 1000L);
    router.route(get("/v1/lines", Map.of()));
    assertEquals(2, data.lineCalls);
  }

  @Test
  @DisplayName("线路带实时运行状况与这种状况的细节；没有时不写")
  void linesCarryTheirLiveStatus() {
    JsonObject quiet =
        json(router.route(get("/v1/lines", Map.of())))
            .getAsJsonArray("lines")
            .get(0)
            .getAsJsonObject();
    assertFalse(quiet.has("liveStatus"), "站台屏没启用：不写");

    data.status =
        Optional.of(
            new PidsLineStatus(
                PidsLineStatus.Condition.MINOR_DELAYS, new PidsLineStatus.Detail.Late(6)));
    millis.addAndGet(WebApiRouter.CATALOG_MAX_AGE * 1000L);
    JsonObject late =
        json(router.route(get("/v1/lines", Map.of())))
            .getAsJsonArray("lines")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("liveStatus");
    assertEquals("MINOR_DELAYS", late.get("condition").getAsString());
    assertEquals(6, late.get("maxDelayMinutes").getAsInt());

    JsonObject closed =
        com.google.gson.JsonParser.parseString(
                new com.google.gson.Gson()
                    .toJson(
                        WebApiViews.liveStatus(
                            new PidsLineStatus(
                                PidsLineStatus.Condition.PART_SUSPENDED,
                                new PidsLineStatus.Detail.Closed(
                                    new org.fetarute
                                        .fetaruteTCAddon
                                        .display
                                        .pids
                                        .view
                                        .PidsDirectory
                                        .Section("SURC:HHU", "SURC:LWN"))))))
            .getAsJsonObject();
    assertEquals("SURC:HHU", closed.get("closedFrom").getAsString());
    assertEquals("SURC:LWN", closed.get("closedTo").getAsString());
    assertEquals(
        1,
        WebApiViews.liveStatus(PidsLineStatus.of(PidsLineStatus.Condition.GOOD)).size(),
        "运行正常只写状况");
  }

  @Test
  @DisplayName("车站带位置与停靠线路")
  void stationsCarryPositionAndLines() {
    JsonObject station =
        json(router.route(get("/v1/stations", Map.of())))
            .getAsJsonArray("stations")
            .get(0)
            .getAsJsonObject();

    assertEquals("HHU", station.get("code").getAsString());
    assertEquals(447.5, station.getAsJsonObject("position").get("x").getAsDouble(), 1e-9);
    assertEquals(
        "MT",
        station.getAsJsonArray("lines").get(0).getAsJsonObject().get("lineCode").getAsString());
    assertFalse(
        station.getAsJsonArray("lines").get(0).getAsJsonObject().has("viaStationId"),
        "本站自己的线路不写经由");
  }

  @Test
  @DisplayName("站牌：参数校验；不带 horizon 取默认；同样的参数共用缓存")
  void boardValidatesParametersAndCaches() {
    assertEquals(
        200, router.route(get("/v1/stations/SURC/HHU/board", Map.of("line", "MT"))).status());
    router.route(get("/v1/stations/SURC/HHU/board", Map.of("line", "MT")));
    WebResponse lower =
        router.route(get("/v1/stations/surc/hhu/board", Map.of("line", "mt", "horizon", "590")));
    assertEquals(
        List.of("SURC|HHU|MT|600"), data.boardCalls, "同样的参数只算一次；代码不分大小写、时间窗取整到 5 分钟一档后相同也算同样");
    assertEquals("HHU", json(lower).get("stationCode").getAsString(), "回显车站目录里的写法");

    router.route(get("/v1/stations/SURC/HHU/board", Map.of("horizon", "1800")));
    assertEquals("SURC|HHU|null|1800", data.boardCalls.get(1), "不带线路时全部线路");

    assertEquals(
        400, router.route(get("/v1/stations/SURC/HHU/board", Map.of("horizon", "5"))).status());
    assertEquals(
        400,
        router.route(get("/v1/stations/SURC/HH%20U/board", Map.of())).status(),
        "代码只认字母数字与 -_.");
    assertEquals(
        400, router.route(get("/v1/stations/SURC/HHU/board", Map.of("line", "a b"))).status());

    WebResponse unknownStation = router.route(get("/v1/stations/SURC/XYZ/board", Map.of()));
    assertEquals(404, unknownStation.status(), "车站目录里没有的站不算");
    assertEquals(0, unknownStation.maxAgeSeconds(), "不缓存");
    assertEquals(
        404, router.route(get("/v1/stations/SURC/HHU/board", Map.of("line", "NOPE"))).status());
    assertEquals(2, data.boardCalls.size(), "不存在的站与线路都没去算站牌");
  }

  @Test
  @DisplayName("只认 GET、HEAD；未知路径 404")
  void methodsAndUnknownPaths() {
    assertEquals(405, router.route(new WebRequest("POST", "/v1/lines", Map.of())).status());
    assertEquals(200, router.route(new WebRequest("HEAD", "/v1/lines", Map.of())).status());
    assertEquals(404, router.route(get("/v1/nope", Map.of())).status());
    assertEquals(404, router.route(get("/", Map.of())).status());
    WebResponse error = router.route(get("/v1/nope", Map.of()));
    assertEquals(0, error.maxAgeSeconds(), "出错不缓存");
    assertNotNull(json(error).get("error"));
  }

  @Test
  @DisplayName("horizon：默认 600，取整到 5 分钟一档，60–7200 以外与非整数为 -1")
  void horizonParsing() {
    assertEquals(600, WebApiRouter.horizonSeconds(null));
    assertEquals(600, WebApiRouter.horizonSeconds(" "));
    assertEquals(300, WebApiRouter.horizonSeconds("60"), "向上取整到 5 分钟一档");
    assertEquals(900, WebApiRouter.horizonSeconds("601"));
    assertEquals(7200, WebApiRouter.horizonSeconds("7200"));
    assertEquals(-1, WebApiRouter.horizonSeconds("7201"));
    assertEquals(-1, WebApiRouter.horizonSeconds("abc"));
  }

  @Test
  @DisplayName("站牌行：预测时刻与晚点、车厢")
  void boardRowsAreMapped() {
    EtaApi.BoardRow row =
        new EtaApi.BoardRow(
            "MT",
            "SURC:MT:MT-9F_Join",
            "南渡",
            Optional.of("SURC:NDU"),
            "",
            Optional.empty(),
            "",
            Optional.empty(),
            "1",
            "2 分",
            List.of(),
            NOW.plusSeconds(120).toEpochMilli(),
            EtaApi.BoardPhase.ARRIVING,
            3,
            false,
            false,
            false,
            Optional.of("SURC-MT-LP-1178"),
            OptionalLong.of(45),
            false,
            List.of(),
            true,
            List.of(new EtaApi.CarLoad(40, 12)),
            Optional.empty(),
            Optional.empty());
    JsonObject out =
        JsonParser.parseString(
                new com.google.gson.Gson()
                    .toJson(
                        WebApiViews.board(
                            NOW, "SURC", "HHU", new EtaApi.BoardResult(List.of(row)))))
            .getAsJsonObject();
    JsonObject departure = out.getAsJsonArray("departures").get(0).getAsJsonObject();

    assertEquals("2026-10-21T20:02:00Z", departure.get("eta").getAsString());
    assertEquals(45, departure.get("delaySeconds").getAsLong());
    assertEquals("ARRIVING", departure.get("phase").getAsString());
    assertEquals(
        12, departure.getAsJsonArray("cars").get(0).getAsJsonObject().get("occupied").getAsInt());
    assertFalse(departure.has("platformPending"));
  }
}
