package org.fetarute.fetaruteTCAddon.display.pids.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** 一块屏的取帧：缓存里有的当拍用上，后台取的下一个 tick 用上；同一时间只取一帧，期间又变了只取最新的。 */
class PidsFrameQueueTest {

  private static final int LENGTH = 4;

  /** 交出去的取帧，按内容标识记下，测试里手动完成。 */
  private final Map<Object, CompletableFuture<byte[]>> started = new LinkedHashMap<>();

  private final List<Object> order = new ArrayList<>();

  private final Function<PidsContent, CompletableFuture<byte[]>> later =
      content -> {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        started.put(content.key(), future);
        order.add(content.key());
        return future;
      };

  @Test
  void aFrameAlreadyAtHandIsShownAtOnce() {
    PidsFrameQueue queue = new PidsFrameQueue(LENGTH);
    queue.offer(content("A"), content -> CompletableFuture.completedFuture(frame(1)));

    Optional<PidsFrameQueue.Ready> ready = queue.poll();

    assertTrue(ready.isPresent());
    assertEquals("A", ready.get().key());
    assertEquals(Optional.of("A"), queue.shownKey());
  }

  @Test
  void aFrameRenderedLaterIsShownOnceItIsDone() {
    PidsFrameQueue queue = new PidsFrameQueue(LENGTH);
    queue.offer(content("A"), later);

    assertTrue(queue.poll().isEmpty(), "还没画完");
    assertTrue(queue.shownKey().isEmpty(), "画完之前正显示的不变");
    started.get("A").complete(frame(2));
    PidsFrameQueue.Ready ready = queue.poll().orElseThrow();

    assertArrayEquals(frame(2), ready.frame());
    assertEquals(Optional.of("A"), queue.shownKey());
  }

  @Test
  void onlyOneFrameIsRenderedAtATimeAndTheLatestWins() {
    PidsFrameQueue queue = new PidsFrameQueue(LENGTH);
    queue.offer(content("A"), later);
    queue.offer(content("A"), later);
    queue.offer(content("B"), later);
    queue.offer(content("C"), later);

    assertEquals(List.of("A"), order, "A 还没画完时不另起");
    started.get("A").complete(frame(1));
    assertEquals("A", queue.poll().orElseThrow().key());
    assertEquals(List.of("A", "C"), order, "A 画完后只接着画最新的 C，B 不画");
    started.get("C").complete(frame(3));
    assertEquals("C", queue.poll().orElseThrow().key());
  }

  @Test
  void theShownContentIsNotRenderedAgain() {
    PidsFrameQueue queue = new PidsFrameQueue(LENGTH);
    queue.offer(content("A"), later);
    started.get("A").complete(frame(1));
    queue.poll();

    queue.offer(content("A"), later);

    assertEquals(List.of("A"), order);
    assertTrue(queue.poll().isEmpty());
  }

  @Test
  void aFailedOrMisSizedFrameLeavesTheScreenAsItWas() {
    PidsFrameQueue queue = new PidsFrameQueue(LENGTH);
    queue.offer(content("A"), content -> CompletableFuture.completedFuture(frame(1)));
    queue.poll();

    queue.offer(content("B"), later);
    started.get("B").completeExceptionally(new IllegalStateException("boom"));
    assertTrue(queue.poll().isEmpty());
    assertEquals(Optional.of("A"), queue.shownKey());

    queue.offer(content("C"), later);
    started.get("C").complete(new byte[0]);
    assertTrue(queue.poll().isEmpty(), "尺寸不符不显示");
    assertEquals(Optional.of("A"), queue.shownKey());

    queue.offer(content("B"), later);
    assertEquals(List.of("B", "C", "B"), order, "出错的内容下次检查再取");
  }

  @Test
  void resetDropsTheFrameBeingRendered() {
    PidsFrameQueue queue = new PidsFrameQueue(LENGTH);
    queue.offer(content("A"), later);
    queue.reset();
    started.get("A").complete(frame(1));

    assertTrue(queue.poll().isEmpty());
    assertTrue(queue.shownKey().isEmpty());
    queue.offer(content("A"), later);
    assertEquals(List.of("A", "A"), order, "重置后重新取");
  }

  private static PidsContent content(String key) {
    return new PidsContent(
        key,
        () -> {
          throw new AssertionError("队列不自己渲染");
        });
  }

  private static byte[] frame(int value) {
    byte[] out = new byte[LENGTH];
    java.util.Arrays.fill(out, (byte) value);
    return out;
  }
}
