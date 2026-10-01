package org.fetarute.fetaruteTCAddon.dispatcher.eta.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 列车销毁、换交路后旧键再也不会被读到：写入时定期清掉过期条目，缓存不会一直长。 */
class EtaCacheTest {

  private static final Instant T0 = Instant.parse("2026-10-01T12:00:00Z");

  @Test
  void expiredEntriesArePurgedWhileWriting() {
    EtaCache<String, Integer> cache = new EtaCache<>(Duration.ofSeconds(1));
    for (int i = 0; i < EtaCache.PURGE_EVERY - 1; i++) {
      cache.put("old-" + i, i, T0);
    }
    assertEquals(EtaCache.PURGE_EVERY - 1, cache.size());

    cache.put("fresh", 1, T0.plusSeconds(10));

    assertEquals(1, cache.size(), "第 PURGE_EVERY 次写入时清掉全部过期条目");
    assertTrue(cache.getIfFresh("fresh", T0.plusSeconds(10)).isPresent());
  }
}
