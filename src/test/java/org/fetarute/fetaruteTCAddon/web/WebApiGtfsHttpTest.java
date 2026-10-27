package org.fetarute.fetaruteTCAddon.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.interlink.ServerIdentity;
import org.fetarute.fetaruteTCAddon.web.gtfs.GtfsTestFixtures;
import org.fetarute.fetaruteTCAddon.web.gtfs.realtime.GtfsRealtimeSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 真起一个本机 HTTP 服务，按客户端实际拿包的方式验证 GTFS 两个端点的响应头与正文。 */
@DisplayName("GTFS 接口走真实 HTTP")
class WebApiGtfsHttpTest {

  private final WebApiServer server =
      new WebApiServer(
          message -> {},
          message -> {},
          Optional::empty,
          (operator, line) -> Optional.empty(),
          () -> Optional.of(GtfsTestFixtures.snapshot()),
          now -> Optional.of(GtfsRealtimeSnapshot.empty(now)),
          () -> null);

  @BeforeEach
  void configureServerIdentity() {
    // 下载文件名与 feed ID 都用 config.yml 的 server-id；生产环境由插件在启用时写入，这里模拟同一份配置
    ServerIdentity.configure("sv");
  }

  @AfterEach
  void tearDown() {
    server.stop();
    ServerIdentity.configure("");
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  @Test
  @DisplayName("GET /v1/gtfs.zip：application/zip、下载文件名、PK 头；带 ETag 复验回 304")
  void servesTheZipOverHttp() throws Exception {
    int port = freePort();
    server.apply(new ConfigManager.WebApiSettings(true, "127.0.0.1", port, ""));
    HttpClient client = HttpClient.newHttpClient();
    URI url = URI.create("http://127.0.0.1:" + port + "/v1/gtfs.zip");

    HttpResponse<byte[]> zip =
        client.send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, zip.statusCode());
    assertEquals("application/zip", zip.headers().firstValue("Content-Type").orElse(""));
    assertEquals(
        "attachment; filename=\"gtfs-sv.zip\"",
        zip.headers().firstValue("Content-Disposition").orElse(""));
    assertEquals(
        "public, max-age=" + WebApiRouter.GTFS_MAX_AGE,
        zip.headers().firstValue("Cache-Control").orElse(""));
    assertTrue(zip.body().length > 100);
    assertEquals('P', (char) zip.body()[0]);
    assertEquals('K', (char) zip.body()[1]);
    String etag = zip.headers().firstValue("ETag").orElse("");
    assertTrue(!etag.isEmpty());

    HttpResponse<byte[]> notModified =
        client.send(
            HttpRequest.newBuilder(url).header("If-None-Match", etag).build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(304, notModified.statusCode());
    assertEquals(0, notModified.body().length);
  }

  @Test
  @DisplayName("GET /v1/gtfs-rt/*：默认 protobuf，?format=json 能看到报文头里的 feed_version")
  void servesRealtimeOverHttp() throws Exception {
    int port = freePort();
    server.apply(new ConfigManager.WebApiSettings(true, "127.0.0.1", port, ""));
    HttpClient client = HttpClient.newHttpClient();
    URI base = URI.create("http://127.0.0.1:" + port + "/v1/gtfs-rt/");

    HttpResponse<byte[]> proto =
        client.send(
            HttpRequest.newBuilder(base.resolve("trip-updates")).build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, proto.statusCode());
    assertEquals("application/x-protobuf", proto.headers().firstValue("Content-Type").orElse(""));
    assertTrue(proto.body().length > 0);

    HttpResponse<String> json =
        client.send(
            HttpRequest.newBuilder(base.resolve("vehicle-positions?format=json")).build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, json.statusCode());
    assertTrue(json.body().contains("\"feed_version\""));
    assertTrue(json.body().contains(GtfsTestFixtures.feed().version()));
    assertEquals(
        "application/json; charset=utf-8", json.headers().firstValue("Content-Type").orElse(""));
  }
}
