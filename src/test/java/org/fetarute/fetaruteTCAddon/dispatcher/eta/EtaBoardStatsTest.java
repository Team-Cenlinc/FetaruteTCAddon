package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class EtaBoardStatsTest {

  private static final Instant T0 = Instant.parse("2026-09-30T12:00:00Z");

  @Test
  void emptyStatsReportZeroWithoutDividingByZero() {
    EtaBoardStats.Snapshot snapshot = new EtaBoardStats(T0).snapshot();

    assertEquals(0L, snapshot.calls());
    assertEquals(0.0, snapshot.cacheHitRate());
    assertEquals(0.0, snapshot.averageComputeMillis());
    assertEquals(0.0, snapshot.averageStorageReads());
    assertEquals(T0, snapshot.since());
  }

  @Test
  void aggregatesComputesAndCacheHits() {
    EtaBoardStats stats = new EtaBoardStats(T0);

    stats.recordCompute(2_000_000L, 4);
    stats.recordCompute(6_000_000L, 10);
    stats.recordCacheHit();
    stats.recordCacheHit();

    EtaBoardStats.Snapshot snapshot = stats.snapshot();
    assertEquals(4L, snapshot.calls());
    assertEquals(2L, snapshot.computes());
    assertEquals(0.5, snapshot.cacheHitRate());
    assertEquals(4.0, snapshot.averageComputeMillis());
    assertEquals(6.0, snapshot.maxComputeMillis());
    assertEquals(14L, snapshot.totalStorageReads());
    assertEquals(7.0, snapshot.averageStorageReads());
    assertEquals(10L, snapshot.maxStorageReads());
  }

  @Test
  // 负值只可能来自计时异常（如时钟回拨），按 0 计，不能把总量减小。
  void negativeInputsCountAsZero() {
    EtaBoardStats stats = new EtaBoardStats(T0);

    stats.recordCompute(-5L, -3L);

    EtaBoardStats.Snapshot snapshot = stats.snapshot();
    assertEquals(1L, snapshot.computes());
    assertEquals(0L, snapshot.totalComputeNanos());
    assertEquals(0L, snapshot.totalStorageReads());
  }

  @Test
  // 存储读取计数是累计值，只用来取差；清零统计不能让它倒退，否则清零瞬间正在进行的重算会算出负数。
  void resetClearsStatsButKeepsTheStorageReadCounter() {
    EtaBoardStats stats = new EtaBoardStats(T0);
    stats.countStorageRead();
    stats.countStorageRead();
    stats.recordCompute(1_000_000L, 2);
    Instant later = T0.plusSeconds(60);

    stats.reset(later);

    EtaBoardStats.Snapshot snapshot = stats.snapshot();
    assertEquals(0L, snapshot.calls());
    assertEquals(0L, snapshot.maxComputeNanos());
    assertEquals(0L, snapshot.maxStorageReads());
    assertEquals(later, snapshot.since());
    assertEquals(2L, stats.storageReadsSoFar());
  }
}
