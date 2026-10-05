package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.StopMarkSign;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("停车位置标索引：后台分片、缓存与作废")
class StopMarkIndexTest {

  /** 一条 x 从 -200 到 200 的直线轨道，x = 150 处有一块 4 节车的标志。 */
  private static final class Line implements RailBlockAccess {
    @Override
    public boolean isRail(RailBlockPos pos) {
      return Math.abs(pos.x()) <= 200;
    }

    @Override
    public Set<RailBlockPos> neighbors(RailBlockPos pos) {
      return Set.of(
              new RailBlockPos(pos.x() - 1, pos.y(), pos.z()),
              new RailBlockPos(pos.x() + 1, pos.y(), pos.z()))
          .stream()
          .filter(this::isRail)
          .collect(java.util.stream.Collectors.toSet());
    }
  }

  private final AtomicLong millis = new AtomicLong(0L);
  private final AtomicLong nanos = new AtomicLong(0L);
  private final AtomicInteger inspected = new AtomicInteger();
  private final StopMarkIndex index =
      new StopMarkIndex(
          rail ->
              new StopMarks.ScanJob(
                  new Line(),
                  new RailBlockPos(0, 64, 0),
                  StopMarks.SEARCH_BLOCKS,
                  pos -> {
                    inspected.incrementAndGet();
                    // 每查一段轨道“花掉” 0.1 毫秒。
                    nanos.addAndGet(100_000L);
                    return pos.x() == 150
                        ? new StopMarks.RailSigns(
                            false,
                            List.of(
                                new StopMarks.Mark(
                                    pos,
                                    new Vector(150.5, 64.0, 0.5),
                                    StopMarkSign.parse("carriage:4", "").orElseThrow())))
                        : StopMarks.RailSigns.NONE;
                  },
                  pos -> true),
          millis::get,
          nanos::get);
  private final Block rail = rail();

  private static Block rail() {
    World world = mock(World.class);
    when(world.getUID()).thenReturn(new UUID(1L, 2L));
    Block block = mock(Block.class);
    when(block.getWorld()).thenReturn(world);
    when(block.getX()).thenReturn(0);
    when(block.getY()).thenReturn(64);
    when(block.getZ()).thenReturn(0);
    return block;
  }

  @Test
  @DisplayName("后台每 tick 只花一点时间，长站台分几个 tick 走完")
  void backgroundScanIsSliced() {
    index.prefetch(rail);
    index.tick();
    int afterOneTick = inspected.get();
    assertTrue(afterOneTick < 401, "一个 tick 走不完 401 段");
    assertTrue(afterOneTick >= StopMarkIndex.RAILS_PER_SLICE);
    int ticks = 1;
    while (index.pendingCount() > 0 && ticks < 1000) {
      index.tick();
      ticks++;
    }
    assertTrue(ticks > 1);
    assertEquals(401, inspected.get());
    assertEquals(1, index.around(rail).size(), "走完后直接用缓存");
    assertEquals(401, inspected.get());
  }

  @Test
  @DisplayName("驾驶侧只看缓存不阻塞；站台要结果时把还没走完的当场走完")
  void cachedNeverBlocksButAroundFinishes() {
    assertTrue(index.cached(rail).isEmpty());
    assertEquals(0, inspected.get(), "只排队，不走");
    index.tick();
    assertTrue(index.cached(rail).isEmpty(), "还没走完");
    assertEquals(1, index.around(rail).size());
    assertEquals(401, inspected.get(), "接着排队中的那次走完，不从头再来");
    assertEquals(1, index.cached(rail).orElseThrow().size());
  }

  @Test
  @DisplayName("作废后已知车站在后台重走；走的过程中被作废的结果不写进缓存")
  void invalidateRescansKnownStations() {
    index.around(rail);
    index.invalidate();
    assertTrue(index.cached(rail).isEmpty());
    index.tick();
    assertTrue(index.pendingCount() > 0 || index.cached(rail).isPresent());
    index.invalidate();
    while (index.pendingCount() > 0) {
      index.tick();
    }
    index.tick();
    while (index.pendingCount() > 0) {
      index.tick();
    }
    assertEquals(1, index.cached(rail).orElseThrow().size());
  }

  @Test
  @DisplayName("用了一段时间的结果先照用，同时在后台提前重走")
  void refreshesBeforeExpiry() {
    index.around(rail);
    millis.addAndGet(StopMarkIndex.REFRESH_MILLIS);
    int before = inspected.get();
    assertEquals(1, index.around(rail).size());
    assertEquals(before, inspected.get(), "这一刻不阻塞重走");
    assertEquals(1, index.pendingCount());
  }

  @Test
  @DisplayName("没有标志的股道隔得更久才重走（建、拆牌子另会作废缓存）")
  void emptyTracksRefreshLessOften() {
    StopMarkIndex empty =
        new StopMarkIndex(
            ignored ->
                new StopMarks.ScanJob(
                    new Line(),
                    new RailBlockPos(0, 64, 0),
                    StopMarks.SEARCH_BLOCKS,
                    pos -> StopMarks.RailSigns.NONE,
                    pos -> true),
            millis::get,
            nanos::get);
    assertTrue(empty.around(rail).isEmpty());
    millis.addAndGet(StopMarkIndex.CACHE_MILLIS);
    assertTrue(empty.cached(rail).orElseThrow().isEmpty());
    assertEquals(0, empty.pendingCount(), "有标志的股道这时早该重走了，空的还不用");
    millis.addAndGet(StopMarkIndex.EMPTY_REFRESH_MILLIS - StopMarkIndex.CACHE_MILLIS);
    empty.cached(rail);
    assertEquals(1, empty.pendingCount());
  }
}
