package org.fetarute.fetaruteTCAddon.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.fetarute.fetaruteTCAddon.web.gtfs.GtfsFeed;
import org.fetarute.fetaruteTCAddon.web.gtfs.GtfsFeedService;
import org.fetarute.fetaruteTCAddon.web.gtfs.GtfsTestFixtures;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeService;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GTFS 与 GTFS-Realtime 接口")
class WebApiGtfsTest {

  private final AtomicLong millis = new AtomicLong(GtfsTestFixtures.NOW.toEpochMilli());
  private final Clock clock = Clock.fixed(GtfsTestFixtures.NOW, GtfsTestFixtures.ZONE);
  private final GtfsFeed feed = GtfsTestFixtures.feed();
  private final GtfsFeedService gtfs =
      new GtfsFeedService(() -> Optional.of(GtfsTestFixtures.snapshot()), clock, message -> {});
  private final GtfsRealtimeService realtime =
      new GtfsRealtimeService(
          gtfs,
          now -> Optional.of(GtfsRealtimeSnapshot.empty(now)),
          clock,
          () -> key -> key,
          message -> {});
  private final WebApiRouter router =
      new WebApiRouter(
          mock(WebApiData.class),
          new WebResponseCache(millis::get),
          clock,
          "1.14.0",
          () -> Optional.of("sv"),
          gtfs,
          realtime);

  private static WebRequest get(String path, Map<String, String> query) {
    return new WebRequest("GET", path, query);
  }

  private WebApiRouter router(
      Optional<String> serverId, GtfsFeedService feedService, GtfsRealtimeService realtimeService) {
    return new WebApiRouter(
        mock(WebApiData.class),
        new WebResponseCache(millis::get),
        clock,
        "1.14.0",
        () -> serverId,
        feedService,
        realtimeService);
  }

  @Test
  @DisplayName("静态包：application/zip、下载文件名、缓存 300 秒；有效期内复用同一份回应")
  void servesTheStaticZip() {
    WebResponse response = router.route(get("/v1/gtfs.zip", Map.of()));

    assertEquals(200, response.status());
    assertEquals("application/zip", response.contentType());
    assertEquals("attachment; filename=\"gtfs-sv.zip\"", response.contentDisposition());
    assertEquals(WebApiRouter.GTFS_MAX_AGE, response.maxAgeSeconds());
    assertEquals('P', (char) response.body()[0]);
    assertEquals('K', (char) response.body()[1]);
    assertTrue(!response.etag().isEmpty());
    assertSame(response, router.route(get("/v1/gtfs.zip", Map.of())));
  }

  @Test
  @DisplayName("实时源：默认 protobuf，?format=json 出调试 JSON，未知格式 400")
  void servesRealtimeInBothFormats() {
    WebResponse proto = router.route(get("/v1/gtfs-rt/trip-updates", Map.of()));
    assertEquals(200, proto.status());
    assertEquals(GtfsRealtimeService.PROTOBUF_CONTENT_TYPE, proto.contentType());
    assertEquals(WebApiRouter.TRIP_UPDATES_MAX_AGE, proto.maxAgeSeconds());
    assertTrue(proto.body().length > 0);

    WebResponse json = router.route(get("/v1/gtfs-rt/vehicle-positions", Map.of("format", "JSON")));
    assertEquals(200, json.status());
    assertEquals(WebResponse.JSON, json.contentType());
    JsonObject body =
        JsonParser.parseString(new String(json.body(), StandardCharsets.UTF_8)).getAsJsonObject();
    assertEquals(feed.version(), body.getAsJsonObject("header").get("feed_version").getAsString());

    assertEquals(400, router.route(get("/v1/gtfs-rt/alerts", Map.of("format", "yaml"))).status());
  }

  @Test
  @DisplayName("meta 报静态包版本与新增的接口")
  void metaReportsTheFeedVersion() {
    JsonObject body =
        JsonParser.parseString(
                new String(router.route(get("/v1/meta", Map.of())).body(), StandardCharsets.UTF_8))
            .getAsJsonObject();

    assertEquals(feed.version(), body.getAsJsonObject("gtfs").get("feedVersion").getAsString());
    assertNotNull(body.getAsJsonObject("gtfs").get("generatedAt"));
    assertTrue(
        body.getAsJsonArray("endpoints").asList().stream()
            .anyMatch(value -> value.getAsString().equals("/v1/gtfs.zip")));
    assertTrue(
        body.getAsJsonArray("endpoints").asList().stream()
            .anyMatch(value -> value.getAsString().startsWith("/v1/gtfs-rt/trip-updates")));
  }

  @Test
  @DisplayName("数据源没起来时回 503，而不是一个空包")
  void reportsNotReadyWhenTheSourceIsEmpty() {
    GtfsFeedService empty = new GtfsFeedService(() -> Optional.empty(), clock, message -> {});
    WebApiRouter unavailable =
        new WebApiRouter(
            mock(WebApiData.class),
            new WebResponseCache(millis::get),
            clock,
            "1.14.0",
            () -> Optional.empty(),
            empty,
            new GtfsRealtimeService(
                empty, now -> Optional.empty(), clock, () -> key -> key, message -> {}));

    assertEquals(503, unavailable.route(get("/v1/gtfs.zip", Map.of())).status());
    assertEquals(503, unavailable.route(get("/v1/gtfs-rt/trip-updates", Map.of())).status());
  }

  @Test
  @DisplayName("数据源抛异常时回 503 而不是 500，且告警一分钟内只记一次")
  void reportsServerErrorAsUnavailable() {
    List<String> warnings = new ArrayList<>();
    GtfsFeedService broken =
        new GtfsFeedService(
            () -> {
              throw new IllegalStateException("boom");
            },
            clock,
            warnings::add);
    WebApiRouter router =
        router(
            Optional.of("sv"),
            broken,
            new GtfsRealtimeService(
                broken, now -> Optional.empty(), clock, () -> key -> key, message -> {}));

    assertEquals(503, router.route(get("/v1/gtfs.zip", Map.of())).status());
    assertEquals(503, router.route(get("/v1/gtfs-rt/alerts", Map.of())).status());
    assertEquals(1, warnings.size());
  }

  @Test
  @DisplayName("下载文件名带上服务器 ID；含点号的 ID 不再被吞成 server，引号/换行不进响应头")
  void sanitizesTheDownloadFileName() {
    WebApiRouter dotted =
        router(
            Optional.of("survival.1"),
            gtfs,
            new GtfsRealtimeService(
                gtfs, now -> Optional.empty(), clock, () -> key -> key, message -> {}));
    assertEquals(
        "attachment; filename=\"gtfs-survival.1.zip\"",
        dotted.route(get("/v1/gtfs.zip", Map.of())).contentDisposition());

    WebApiRouter unsafe =
        router(
            Optional.of("bad\r\nX"),
            gtfs,
            new GtfsRealtimeService(
                gtfs, now -> Optional.empty(), clock, () -> key -> key, message -> {}));
    assertEquals(
        "attachment; filename=\"gtfs-server.zip\"",
        unsafe.route(get("/v1/gtfs.zip", Map.of())).contentDisposition());
  }
}
