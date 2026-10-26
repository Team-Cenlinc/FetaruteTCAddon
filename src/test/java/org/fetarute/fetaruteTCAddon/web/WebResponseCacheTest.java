package org.fetarute.fetaruteTCAddon.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("接口回应的短时缓存")
class WebResponseCacheTest {

  @Test
  @DisplayName("有效期内只算一次；过期重算；同样的正文 ETag 不变，不同的正文 ETag 不同")
  void cachesWithinMaxAge() {
    AtomicLong millis = new AtomicLong(1_000L);
    AtomicInteger computed = new AtomicInteger();
    WebResponseCache cache = new WebResponseCache(millis::get);

    WebResponse first =
        cache.get(
            "k",
            5,
            () -> WebResponseCache.Rendered.json("{\"n\":" + computed.incrementAndGet() + "}"));
    cache.get(
        "k", 5, () -> WebResponseCache.Rendered.json("{\"n\":" + computed.incrementAndGet() + "}"));
    assertEquals(1, computed.get());
    assertEquals(5, first.maxAgeSeconds());

    millis.addAndGet(5_000L);
    WebResponse second =
        cache.get(
            "k",
            5,
            () -> WebResponseCache.Rendered.json("{\"n\":" + computed.incrementAndGet() + "}"));
    assertEquals(2, computed.get());
    assertNotEquals(first.etag(), second.etag());
    assertEquals(
        WebResponseCache.etag("{}".getBytes(StandardCharsets.UTF_8)),
        WebResponseCache.etag("{}".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  @DisplayName("ETag 只按内容算：生成时刻变了、内容没变时重算出同一个 ETag，缓存方复验能拿到 304")
  void etagIgnoresTheGenerationTime() {
    AtomicLong millis = new AtomicLong(1_000L);
    WebResponseCache cache = new WebResponseCache(millis::get);

    WebResponse first =
        cache.get(
            "k",
            5,
            () ->
                new WebResponseCache.Rendered(
                    "{\"generatedAt\":\"t1\",\"n\":1}".getBytes(StandardCharsets.UTF_8),
                    WebResponse.JSON,
                    "{\"n\":1}",
                    ""));
    millis.addAndGet(5_000L);
    WebResponse second =
        cache.get(
            "k",
            5,
            () ->
                new WebResponseCache.Rendered(
                    "{\"generatedAt\":\"t2\",\"n\":1}".getBytes(StandardCharsets.UTF_8),
                    WebResponse.JSON,
                    "{\"n\":1}",
                    ""));

    assertNotEquals(
        new String(first.body(), StandardCharsets.UTF_8),
        new String(second.body(), StandardCharsets.UTF_8));
    assertEquals(first.etag(), second.etag());
  }
}
