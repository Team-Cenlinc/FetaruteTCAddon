package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** 编表候选的并行池：按原顺序交回、异常留在原位、嵌套不卡死、线程数换算。 */
class BuildPoolTest {

  /** 剩余不到四分之一才算内存紧：按传入的已占量判，不再含年轻代里没收的垃圾（那部分由调用方换成老年代占用）。 */
  @Test
  void lowMemoryMeansLessThanAQuarterLeft() {
    assertTrue(BuildPool.lowMemory(1000, 800));
    assertFalse(BuildPool.lowMemory(1000, 750));
    assertFalse(BuildPool.lowMemory(1000, 300));
  }

  /**
   * 长期占用取老年代上次专门回收后的占用与最近一次任何 GC 后老年代的占用之大者：只有年轻代 GC 时前者不更新（G1 只在混合/完全回收后才更新）， 晋升上来的数据要靠后者看见；
   * 都还没有时取老年代当前占用。
   */
  @Test
  void retainedHeapTakesTheLaterOfTheTwoAfterGcReadings() {
    assertEquals(700, BuildPool.retained(200, 700, 900));
    assertEquals(500, BuildPool.retained(500, 300, 900));
    assertEquals(400, BuildPool.retained(0, 400, 900));
    assertEquals(900, BuildPool.retained(0, 0, 900), "都没收过时取当前占用");
  }

  /** 各收集器的老年代池名都认得出，年轻代与幸存区不算。 */
  @Test
  void oldGenerationPoolsAreRecognisedByName() {
    for (String name :
        List.of("G1 Old Gen", "PS Old Gen", "CMS Old Gen", "Tenured Gen", "ZGC Old Generation")) {
      assertTrue(BuildPool.isOldGeneration(name), name);
    }
    for (String name :
        List.of("G1 Eden Space", "G1 Survivor Space", "PS Eden Space", "ZGC Young Generation")) {
      assertFalse(BuildPool.isOldGeneration(name), name);
    }
  }

  /** 结果按候选的顺序交回，与谁先编完无关。 */
  @Test
  void resultsComeBackInOrder() {
    List<Integer> items = IntStream.range(0, 20).boxed().toList();
    try (BuildPool pool = BuildPool.open(4)) {
      List<Integer> out =
          pool
              .evaluate(
                  items,
                  item -> {
                    if (item % 3 == 0) {
                      Thread.onSpinWait();
                    }
                    return item * item;
                  })
              .stream()
              .map(BuildPool.Evaluated::get)
              .toList();
      assertEquals(items.stream().map(item -> item * item).toList(), out);
    }
  }

  /** 错峰候选里又有相位候选：池内线程等子任务时帮着编，两层、三层嵌套都编得完。 */
  @Test
  void nestedBatchesDoNotDeadlock() {
    try (BuildPool pool = BuildPool.open(2)) {
      List<Integer> outer = IntStream.range(0, 6).boxed().toList();
      List<Integer> sums =
          pool
              .evaluate(
                  outer,
                  item ->
                      pool
                          .evaluate(
                              List.of(1, 2, 3),
                              inner ->
                                  pool.evaluate(List.of(inner, inner), x -> x * item).stream()
                                      .mapToInt(BuildPool.Evaluated::get)
                                      .sum())
                          .stream()
                          .mapToInt(BuildPool.Evaluated::get)
                          .sum())
              .stream()
              .map(BuildPool.Evaluated::get)
              .toList();
      assertEquals(outer.stream().map(item -> 12 * item).toList(), sums);
    }
  }

  /** 异常留在抛出它的候选上，取到它才抛；别的候选照常有结果。 */
  @Test
  void aFailureStaysWithItsCandidate() {
    IllegalStateException boom = new IllegalStateException("第 2 个");
    try (BuildPool pool = BuildPool.open(3)) {
      List<BuildPool.Evaluated<Integer>> out =
          pool.evaluate(
              List.of(0, 1, 2, 3),
              item -> {
                if (item == 2) {
                  throw boom;
                }
                return item;
              });
      assertEquals(4, out.size());
      assertEquals(1, out.get(1).get());
      assertEquals(3, out.get(3).get());
      assertSame(boom, assertThrows(IllegalStateException.class, () -> out.get(2).get()));
    }
  }

  /** 串行时出错就不再编后面的：与从前逐个编、出错即停一样。 */
  @Test
  void serialStopsAtTheFirstFailure() {
    AtomicInteger calls = new AtomicInteger();
    List<BuildPool.Evaluated<Integer>> out =
        BuildPool.serial()
            .evaluate(
                List.of(0, 1, 2, 3),
                item -> {
                  calls.incrementAndGet();
                  if (item == 1) {
                    throw new IllegalStateException();
                  }
                  return item;
                });
    assertEquals(2, out.size());
    assertEquals(2, calls.get());
    assertThrows(IllegalStateException.class, () -> out.get(1).get());
  }

  /** 一个线程不建池：就是串行。 */
  @Test
  void oneThreadIsSerial() {
    assertSame(BuildPool.serial(), BuildPool.open(1));
    assertSame(BuildPool.serial(), BuildPool.open(0));
    assertEquals(1, BuildPool.serial().threads());
  }

  /** 线程数换算：不配置取核数一半（至少 1、至多 4），0 取全部核，显式写的数不超过可用核数（结果随所在机器的核数变）。 */
  @Test
  void configuredThreadsResolve() {
    assertEquals(1, BuildPool.defaultThreads(1));
    assertEquals(1, BuildPool.defaultThreads(2));
    assertEquals(3, BuildPool.defaultThreads(6));
    assertEquals(4, BuildPool.defaultThreads(16));
    int cores = Runtime.getRuntime().availableProcessors();
    assertEquals(BuildPool.defaultThreads(cores), BuildPool.resolveThreads(-1));
    assertEquals(cores, BuildPool.resolveThreads(0));
    assertEquals(1, BuildPool.resolveThreads(1));
    assertEquals(Math.min(6, cores), BuildPool.resolveThreads(6));
    assertTrue(BuildPool.resolveThreads(-1) <= BuildPool.DEFAULT_MAX_THREADS);
  }
}
