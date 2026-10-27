package org.fetarute.fetaruteTCAddon.web;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 回给客户端的内容。
 *
 * <p>正文一律是字节数组：JSON 接口写 UTF-8 文本，GTFS 静态包写 zip，GTFS-Realtime 写 protobuf，三者共用同一套缓存、ETag 与 304
 * 复验。{@code Content-Type} 随正文一起决定，不再由服务端写死。
 *
 * @param status HTTP 状态码
 * @param body 正文
 * @param contentType 正文的 {@code Content-Type}
 * @param maxAgeSeconds 允许缓存多少秒（{@code Cache-Control: public, max-age}）；0 为不缓存
 * @param etag 正文的 ETag（带引号）；不缓存的回应为空串
 * @param contentDisposition {@code Content-Disposition} 头的值；不需要时为空串
 */
record WebResponse(
    int status,
    byte[] body,
    String contentType,
    int maxAgeSeconds,
    String etag,
    String contentDisposition) {

  /** 只读 JSON 接口的 {@code Content-Type}。 */
  static final String JSON = "application/json; charset=utf-8";

  WebResponse {
    Objects.requireNonNull(body, "body");
    contentType = contentType == null || contentType.isBlank() ? JSON : contentType;
    maxAgeSeconds = Math.max(0, maxAgeSeconds);
    etag = etag == null ? "" : etag;
    contentDisposition = contentDisposition == null ? "" : contentDisposition;
  }

  /** 出错时的回应：{@code {"error":"<原因>"}}，不缓存。 */
  static WebResponse error(int status, String reason) {
    return new WebResponse(
        status,
        ("{\"error\":\"" + reason + "\"}").getBytes(StandardCharsets.UTF_8),
        JSON,
        0,
        "",
        "");
  }
}
