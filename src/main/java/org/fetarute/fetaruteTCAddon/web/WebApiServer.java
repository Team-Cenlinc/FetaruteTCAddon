package org.fetarute.fetaruteTCAddon.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.fetarute.fetaruteTCAddon.api.FetaruteApi;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.display.pids.view.PidsLineStatus;

/**
 * 只读 HTTP 接口的服务：JDK 自带的 {@link HttpServer}，请求在插件自己的两条后台线程上处理，不碰服务器主线程。按配置启停，配置变了时重启。
 *
 * <p>设了令牌时只回应带令牌的请求（{@code Authorization: Bearer <令牌>} 或 {@code X-Fetarute-Token}），其余回
 * 401：外部的缓存中转（如 Cloudflare Worker）带令牌来拉，公众的请求由它的缓存接住。回应带 {@code Cache-Control}、{@code ETag}，认
 * {@code If-None-Match}（回 304），并允许跨域读取。
 */
public final class WebApiServer {

  /** 处理请求的后台线程数：只有缓存中转一个客户端，两条足够。 */
  private static final int THREADS = 2;

  /** 停下时最多等正在算的请求多久（毫秒）。 */
  private static final long STOP_WAIT_MILLIS = 1000L;

  private final Consumer<String> info;
  private final Consumer<String> warn;
  private final Supplier<Optional<StationDirectory.Snapshot>> directory;
  private final BiFunction<String, String, Optional<PidsLineStatus>> lineStatus;
  private HttpServer server;
  private ExecutorService executor;
  private ConfigManager.WebApiSettings running;

  /**
   * @param info 普通日志
   * @param warn 警告日志
   * @param directory 车站目录快照（运营商、线路、车站从这里取）
   * @param lineStatus 线路运行状况（运营商代码、线路代码；站台屏没启用时为空）
   */
  public WebApiServer(
      Consumer<String> info,
      Consumer<String> warn,
      Supplier<Optional<StationDirectory.Snapshot>> directory,
      BiFunction<String, String, Optional<PidsLineStatus>> lineStatus) {
    this.info = Objects.requireNonNull(info, "info");
    this.warn = Objects.requireNonNull(warn, "warn");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.lineStatus = Objects.requireNonNull(lineStatus, "lineStatus");
  }

  /** 按配置启停：关掉时停下；开着且地址、端口、令牌都没变时什么也不做；变了时重启。启动、重载时在主线程调用。 */
  public synchronized void apply(ConfigManager.WebApiSettings settings) {
    if (settings == null || !settings.enabled()) {
      stop();
      return;
    }
    if (server != null && settings.equals(running)) {
      return;
    }
    stop();
    WebApiRouter router =
        new WebApiRouter(
            WebApiData.of(FetaruteApi::get, directory, lineStatus),
            new WebResponseCache(System::currentTimeMillis),
            Clock.systemDefaultZone(),
            FetaruteApi.API_VERSION);
    HttpServer created;
    try {
      created = HttpServer.create(new InetSocketAddress(settings.host(), settings.port()), 32);
    } catch (IOException | RuntimeException ex) {
      warn.accept(
          "只读 HTTP 接口启动失败（" + settings.host() + ":" + settings.port() + "）：" + ex.getMessage());
      return;
    }
    AtomicInteger threads = new AtomicInteger();
    ExecutorService pool =
        Executors.newFixedThreadPool(
            THREADS,
            task -> {
              Thread thread = new Thread(task, "FetaruteTCAddon-web-" + threads.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
    byte[] token = settings.token().getBytes(StandardCharsets.UTF_8);
    created.setExecutor(pool);
    created.createContext("/", exchange -> handle(exchange, router, token));
    created.start();
    server = created;
    executor = pool;
    running = settings;
    info.accept("只读 HTTP 接口已启动: " + settings.host() + ":" + settings.port());
    if (!loopback(settings.host())) {
      // JDK 自带的 HTTP 服务读请求头不限时：直接暴露在公网时，几个慢速连接就能占满处理线程（接口卡住，服务器主线程不受影响）
      warn.accept(
          "只读 HTTP 接口监听在非本机地址 "
              + settings.host()
              + "：直接暴露在公网时慢速连接可以占满处理线程，建议只听 127.0.0.1、经 Cloudflare Tunnel 等反向代理转发"
              + (settings.token().isEmpty() ? "；且没有设置 web-api.token，任何人都能直接拉取" : ""));
    }
  }

  /** 停下（插件停用、重载时调用）：不再接请求，正在算的请求最多等 {@link #STOP_WAIT_MILLIS} 毫秒，插件随后才拆掉各服务。 */
  public synchronized void stop() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
    if (executor != null) {
      executor.shutdownNow();
      try {
        executor.awaitTermination(STOP_WAIT_MILLIS, TimeUnit.MILLISECONDS);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
      executor = null;
    }
    running = null;
  }

  private void handle(HttpExchange exchange, WebApiRouter router, byte[] token) {
    try (exchange) {
      Headers headers = exchange.getResponseHeaders();
      headers.set("Content-Type", "application/json; charset=utf-8");
      headers.set("Access-Control-Allow-Origin", "*");
      headers.set("X-Content-Type-Options", "nosniff");
      String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
      WebResponse response;
      if (!authorized(exchange.getRequestHeaders(), token)) {
        response = WebResponse.error(401, "unauthorized");
      } else {
        URI uri = exchange.getRequestURI();
        response = router.route(new WebRequest(method, uri.getPath(), query(uri.getRawQuery())));
      }
      headers.set(
          "Cache-Control",
          response.maxAgeSeconds() > 0
              ? "public, max-age=" + response.maxAgeSeconds()
              : "no-store");
      if (!response.etag().isEmpty()) {
        headers.set("ETag", response.etag());
      }
      boolean notModified =
          response.status() == 200
              && notModified(
                  exchange.getRequestHeaders().getFirst("If-None-Match"), response.etag());
      if (notModified) {
        exchange.sendResponseHeaders(304, -1);
        return;
      }
      if ("HEAD".equals(method)) {
        exchange.sendResponseHeaders(response.status(), -1);
        return;
      }
      exchange.sendResponseHeaders(response.status(), response.body().length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(response.body());
      }
    } catch (IOException ex) {
      // 客户端中途断开：不用处理
    } catch (RuntimeException ex) {
      warn.accept("只读 HTTP 接口处理请求出错: " + ex);
      try {
        byte[] body = WebResponse.error(500, "internal").body();
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().remove("ETag");
        exchange.sendResponseHeaders(500, body.length);
        exchange.getResponseBody().write(body);
      } catch (IOException | RuntimeException ignored) {
        // 已经开始回应：只能断开
      }
    }
  }

  /**
   * {@code If-None-Match} 是否认得这个 ETag：按弱比较（忽略 {@code W/} 前缀，中转压缩时会把强 ETag 改成弱的），认逗号分隔的多个与 {@code
   * *}。
   *
   * @param header 请求头；没有时为 {@code null}
   * @param etag 回应的 ETag；为空时不认
   */
  static boolean notModified(String header, String etag) {
    if (header == null || etag == null || etag.isEmpty()) {
      return false;
    }
    String ours = weak(etag);
    for (String candidate : header.split(",")) {
      String trimmed = candidate.trim();
      if (trimmed.equals("*") || weak(trimmed).equals(ours)) {
        return true;
      }
    }
    return false;
  }

  private static String weak(String etag) {
    return etag.startsWith("W/") ? etag.substring(2) : etag;
  }

  /** 设了令牌时请求须带同一个令牌（逐字节等长比较）；没设时都放行。 */
  static boolean authorized(Headers headers, byte[] token) {
    if (token.length == 0) {
      return true;
    }
    String provided = Optional.ofNullable(headers.getFirst("X-Fetarute-Token")).orElse("");
    String authorization = headers.getFirst("Authorization");
    if (provided.isEmpty()
        && authorization != null
        && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      provided = authorization.substring(7).trim();
    }
    return MessageDigest.isEqual(token, provided.getBytes(StandardCharsets.UTF_8));
  }

  /** 查询串拆成参数（同名取第一个）；解不开的参数跳过。 */
  static Map<String, String> query(String raw) {
    Map<String, String> out = new HashMap<>();
    if (raw == null || raw.isEmpty()) {
      return out;
    }
    for (String part : raw.split("&")) {
      if (part.isEmpty()) {
        continue;
      }
      int eq = part.indexOf('=');
      try {
        String key =
            URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
        String value =
            eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
        out.putIfAbsent(key, value);
      } catch (IllegalArgumentException ex) {
        // 编码坏了的参数不要
      }
    }
    return out;
  }

  private static boolean loopback(String host) {
    try {
      return InetAddress.getByName(host).isLoopbackAddress();
    } catch (IOException ex) {
      return false;
    }
  }
}
