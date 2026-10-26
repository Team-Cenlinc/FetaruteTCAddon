package org.fetarute.fetaruteTCAddon.display.pids.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.GraphWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 调色板帧：共享内容缓存里有的当场给出；没有的在渲染线程上画；渲染线程停了就在调用线程上画。 */
class PidsFrameSourceTest {

  private static final String THREAD = "pids-render-test";

  private final List<String> renderedOn = Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger started = new AtomicInteger();
  private final PidsFrameSource source =
      new PidsFrameSource(
          new PidsFrameCache(1024 * 1024),
          key -> !(key instanceof Moving),
          () -> {
            started.incrementAndGet();
            return GraphWorker.start(THREAD, message -> {});
          },
          message -> {});

  @AfterEach
  void stop() {
    source.stop();
  }

  @Test
  void aSharedFrameAlreadyCachedIsHandedOverAtOnce() throws Exception {
    PidsContent board = content("board");
    byte[] first = source.frameLater(board, 4, 2).get(5, TimeUnit.SECONDS);

    var again = source.frameLater(board, 4, 2);

    assertTrue(again.isDone(), "缓存里有的不排队");
    assertSame(first, again.get());
    assertEquals(List.of(THREAD), renderedOn, "只画了一次，在渲染线程上");
  }

  @Test
  void framesThatAreNotSharedAreRenderedOnTheRenderThreadEveryTime() throws Exception {
    PidsContent yard = new PidsContent(new Moving("yard"), image());

    source.frameLater(yard, 4, 2).get(5, TimeUnit.SECONDS);
    source.frameLater(yard, 4, 2).get(5, TimeUnit.SECONDS);

    assertEquals(List.of(THREAD, THREAD), renderedOn);
    assertEquals(1, started.get(), "只起一个渲染线程");
    assertEquals(0, source.cached(), "站场图、控制台不进共享缓存");
  }

  @Test
  void afterStopFramesAreDrawnOnTheCallingThread() {
    source.stop();

    var frame = source.frameLater(content("board"), 4, 2);

    assertTrue(frame.isDone());
    assertEquals(List.of(Thread.currentThread().getName()), renderedOn);
    assertEquals(0, started.get(), "停了以后不再起线程");
  }

  /** 渲染线程停了以后当场画；画出错时结果以异常结束，不往主线程上抛。 */
  @Test
  void aFailingInlineRenderEndsExceptionallyInsteadOfThrowing() {
    source.stop();
    PidsContent broken =
        new PidsContent(
            "broken",
            () -> {
              throw new IllegalStateException("boom");
            });

    var frame = source.frameLater(broken, 4, 2);

    assertTrue(frame.isCompletedExceptionally());
  }

  @Test
  void aFrameOfTheWrongSizeIsEmpty() throws Exception {
    assertEquals(0, source.frameLater(content("board"), 8, 8).get(5, TimeUnit.SECONDS).length);
  }

  private PidsContent content(String key) {
    return new PidsContent(key, image());
  }

  private java.util.function.Supplier<BufferedImage> image() {
    return () -> {
      renderedOn.add(Thread.currentThread().getName());
      return new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
    };
  }

  private record Moving(String name) {}
}
