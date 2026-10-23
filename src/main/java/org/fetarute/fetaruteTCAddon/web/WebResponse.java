package org.fetarute.fetaruteTCAddon.web;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 回给客户端的内容。
 *
 * @param status HTTP 状态码
 * @param body 正文（JSON，UTF-8）
 * @param maxAgeSeconds 允许缓存多少秒（{@code Cache-Control: public, max-age}）；0 为不缓存
 * @param etag 正文的 ETag（带引号）；不缓存的回应为空串
 */
record WebResponse(int status, byte[] body, int maxAgeSeconds, String etag) {

  WebResponse {
    Objects.requireNonNull(body, "body");
    maxAgeSeconds = Math.max(0, maxAgeSeconds);
    etag = etag == null ? "" : etag;
  }

  /** 出错时的回应：{@code {"error":"<原因>"}}，不缓存。 */
  static WebResponse error(int status, String reason) {
    return new WebResponse(
        status, ("{\"error\":\"" + reason + "\"}").getBytes(StandardCharsets.UTF_8), 0, "");
  }
}
