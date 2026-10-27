package org.fetarute.fetaruteTCAddon.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 接口回应的短时缓存：同一个键在有效期内只算一次，其余请求直接回缓存的正文与 ETag。同一个键同时来几个请求时只有一个去算，其余等它算完（不会一起扑到数据上）。
 *
 * <p>只缓存 200 的回应；算的时候出错不缓存。可在任意线程调用。
 */
final class WebResponseCache {

  /** 键超过这么多条时顺手清掉过期的。 */
  private static final int PRUNE_THRESHOLD = 512;

  private final LongSupplier millis;
  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  private record Entry(WebResponse response, long expiresAtMillis) {}

  /**
   * 一次算出的回应。
   *
   * @param body 正文
   * @param contentType 正文的 {@code Content-Type}
   * @param identity 算 ETag 用的内容：去掉生成时刻等每次都变、不代表内容变化的字段，内容没变时 ETag 不变，缓存方复验能拿到 304
   * @param contentDisposition {@code Content-Disposition} 头的值；不需要时为空串
   */
  record Rendered(byte[] body, String contentType, String identity, String contentDisposition) {

    Rendered {
      Objects.requireNonNull(body, "body");
      Objects.requireNonNull(identity, "identity");
      contentType = contentType == null || contentType.isBlank() ? WebResponse.JSON : contentType;
      contentDisposition = contentDisposition == null ? "" : contentDisposition;
    }

    /** JSON 正文：整个正文都算进 ETag。 */
    static Rendered json(String body) {
      return json(body, body);
    }

    /**
     * JSON 正文，按另一份内容算 ETag。
     *
     * @param body 正文
     * @param identity 算 ETag 用的内容（去掉生成时刻这类每次都会变的字段）
     */
    static Rendered json(String body, String identity) {
      return new Rendered(body.getBytes(StandardCharsets.UTF_8), WebResponse.JSON, identity, "");
    }

    /** 二进制正文（zip、protobuf）。ETag 按 {@code identity} 算；两者都由同一份模型确定性生成，内容没变时正文逐字节一致。 */
    static Rendered binary(
        byte[] body, String contentType, String identity, String contentDisposition) {
      return new Rendered(body, contentType, identity, contentDisposition);
    }
  }

  /**
   * @param millis 当前时刻（毫秒）
   */
  WebResponseCache(LongSupplier millis) {
    this.millis = Objects.requireNonNull(millis, "millis");
  }

  /**
   * 取缓存的回应；过期或没有时算一次（{@code render} 返回正文与算 ETag 的内容）并缓存 {@code maxAgeSeconds} 秒。
   *
   * @param key 缓存键（路径加规范化的参数）
   * @param maxAgeSeconds 有效期，也写进回应的 {@code Cache-Control}
   * @param render 算正文
   */
  WebResponse get(String key, int maxAgeSeconds, Supplier<Rendered> render) {
    long now = millis.getAsLong();
    Entry cached = entries.get(key);
    if (cached != null && now < cached.expiresAtMillis()) {
      return cached.response();
    }
    Entry fresh =
        entries.compute(
            key,
            (k, previous) -> {
              long at = millis.getAsLong();
              if (previous != null && at < previous.expiresAtMillis()) {
                return previous;
              }
              Rendered rendered = render.get();
              return new Entry(
                  new WebResponse(
                      200,
                      rendered.body(),
                      rendered.contentType(),
                      maxAgeSeconds,
                      etag(rendered.identity().getBytes(StandardCharsets.UTF_8)),
                      rendered.contentDisposition()),
                  at + maxAgeSeconds * 1000L);
            });
    if (entries.size() > PRUNE_THRESHOLD) {
      long at = millis.getAsLong();
      entries.values().removeIf(entry -> at >= entry.expiresAtMillis());
    }
    return fresh.response();
  }

  /** 正文的 ETag：SHA-256 前 16 个十六进制字符，带引号。 */
  static String etag(byte[] body) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
      return "\"" + HexFormat.of().formatHex(digest, 0, 8) + "\"";
    } catch (NoSuchAlgorithmException ex) {
      return "\"" + Integer.toHexString(java.util.Arrays.hashCode(body)) + "\"";
    }
  }
}
