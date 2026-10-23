package org.fetarute.fetaruteTCAddon.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("只读 HTTP 接口的服务")
class WebApiServerTest {

  private final List<String> warnings = new ArrayList<>();
  private final WebApiServer server =
      new WebApiServer(
          message -> {},
          warnings::add,
          java.util.Optional::empty,
          (operator, line) -> java.util.Optional.empty());

  @AfterEach
  void tearDown() {
    server.stop();
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  @Test
  @DisplayName("令牌：没设时都放行；设了时认 Bearer 与 X-Fetarute-Token，错的、缺的都不认")
  void tokenCheck() {
    Headers none = new Headers();
    assertTrue(WebApiServer.authorized(none, new byte[0]));
    byte[] token = "s3cret".getBytes(StandardCharsets.UTF_8);
    assertFalse(WebApiServer.authorized(none, token));

    Headers bearer = new Headers();
    bearer.add("Authorization", "Bearer s3cret");
    assertTrue(WebApiServer.authorized(bearer, token));
    Headers custom = new Headers();
    custom.add("X-Fetarute-Token", "s3cret");
    assertTrue(WebApiServer.authorized(custom, token));
    Headers wrong = new Headers();
    wrong.add("Authorization", "Bearer s3cre");
    assertFalse(WebApiServer.authorized(wrong, token));
  }

  @Test
  @DisplayName("If-None-Match：弱比较、逗号分隔的多个、星号都认；没有 ETag 或对不上时不认")
  void ifNoneMatchUsesWeakComparison() {
    String etag = "\"0123456789abcdef\"";
    assertTrue(WebApiServer.notModified(etag, etag));
    assertTrue(WebApiServer.notModified("W/" + etag, etag), "中转压缩时把强 ETag 改成弱的");
    assertTrue(WebApiServer.notModified("\"other\", W/" + etag, etag));
    assertTrue(WebApiServer.notModified("*", etag));
    assertFalse(WebApiServer.notModified("\"other\"", etag));
    assertFalse(WebApiServer.notModified(null, etag));
    assertFalse(WebApiServer.notModified(etag, ""));
  }

  @Test
  @DisplayName("查询串：解码、同名取第一个、坏编码跳过")
  void queryParsing() {
    Map<String, String> query = WebApiServer.query("line=MT&horizon=600&line=XX&bad=%zz&flag");
    assertEquals("MT", query.get("line"));
    assertEquals("600", query.get("horizon"));
    assertFalse(query.containsKey("bad"));
    assertEquals("", query.get("flag"));
    assertTrue(WebApiServer.query(null).isEmpty());
  }

  @Test
  @DisplayName("真起一个本机服务：令牌、缓存头、ETag 回 304、HEAD 不回正文；停用后不再监听")
  void servesOverHttp() throws Exception {
    int port = freePort();
    server.apply(new ConfigManager.WebApiSettings(true, "127.0.0.1", port, "s3cret"));
    HttpClient client = HttpClient.newHttpClient();
    URI meta = URI.create("http://127.0.0.1:" + port + "/v1/meta");

    HttpResponse<String> denied =
        client.send(HttpRequest.newBuilder(meta).build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(401, denied.statusCode());
    assertEquals("no-store", denied.headers().firstValue("Cache-Control").orElse(""));

    HttpResponse<String> ok =
        client.send(
            HttpRequest.newBuilder(meta).header("Authorization", "Bearer s3cret").build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, ok.statusCode());
    assertTrue(ok.body().contains("\"apiVersion\""));
    assertEquals(
        "public, max-age=" + WebApiRouter.META_MAX_AGE,
        ok.headers().firstValue("Cache-Control").orElse(""));
    assertEquals("*", ok.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
    String etag = ok.headers().firstValue("ETag").orElse("");
    assertFalse(etag.isEmpty());

    HttpResponse<String> notModified =
        client.send(
            HttpRequest.newBuilder(meta)
                .header("X-Fetarute-Token", "s3cret")
                .header("If-None-Match", etag)
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(304, notModified.statusCode());

    HttpResponse<String> head =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/lines"))
                .header("X-Fetarute-Token", "s3cret")
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, head.statusCode());
    assertEquals("", head.body());

    HttpResponse<String> lines =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/lines"))
                .header("X-Fetarute-Token", "s3cret")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertNotNull(lines.body());
    assertTrue(lines.body().contains("\"lines\":[]"), "公开 API 没初始化时为空列表: " + lines.body());

    server.apply(new ConfigManager.WebApiSettings(false, "127.0.0.1", port, "s3cret"));
    try (ServerSocket reuse = new ServerSocket(port)) {
      assertEquals(port, reuse.getLocalPort(), "停用后端口已释放");
    }
    assertTrue(warnings.isEmpty(), () -> "不该有警告: " + warnings);
  }
}
