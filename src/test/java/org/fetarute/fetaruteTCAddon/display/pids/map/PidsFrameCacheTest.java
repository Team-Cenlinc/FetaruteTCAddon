package org.fetarute.fetaruteTCAddon.display.pids.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** 调色板帧按内容标识与尺寸共享：同站台多块屏、轮播翻回主页都不再重画。 */
class PidsFrameCacheTest {

  private record View(String station, int minutes) {}

  @Test
  void sameContentIsRenderedOnce() {
    PidsFrameCache cache = new PidsFrameCache(1_000_000L);
    AtomicInteger renders = new AtomicInteger();

    Supplier<byte[]> render =
        () -> {
          renders.incrementAndGet();
          return new byte[8];
        };

    byte[] first = cache.frame(new View("HHU", 2), 4, 2, render);
    byte[] second = cache.frame(new View("HHU", 2), 4, 2, render);

    assertSame(first, second, "内容标识 equals 即共享，另一块屏或翻回主页直接复用");
    assertEquals(1, renders.get());
    assertNotSame(first, cache.frame(new View("HHU", 3), 4, 2, () -> new byte[8]));
    assertNotSame(first, cache.frame(new View("HHU", 2), 8, 1, () -> new byte[8]), "尺寸不同不共享");
  }

  @Test
  void leastRecentlyUsedFramesAreDroppedOverTheByteBudget() {
    PidsFrameCache cache = new PidsFrameCache(20L);
    byte[] a = cache.frame("a", 10, 1, () -> new byte[10]);
    cache.frame("b", 10, 1, () -> new byte[10]);
    cache.frame("a", 10, 1, () -> new byte[10]);

    cache.frame("c", 10, 1, () -> new byte[10]);

    assertEquals(2, cache.size());
    assertSame(a, cache.frame("a", 10, 1, () -> new byte[10]), "最近用过的留下");
  }
}
