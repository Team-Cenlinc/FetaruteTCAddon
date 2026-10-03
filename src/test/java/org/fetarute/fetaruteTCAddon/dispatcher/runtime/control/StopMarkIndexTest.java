package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("停车位置标索引的缓存")
class StopMarkIndexTest {

  private final AtomicLong clock = new AtomicLong(0L);
  private final AtomicInteger scans = new AtomicInteger();
  private boolean complete = true;
  private final StopMarkIndex index =
      new StopMarkIndex(
          rail -> {
            scans.incrementAndGet();
            return new StopMarks.Scan(List.of(), complete);
          },
          clock::get);

  private Block rail() {
    World world = mock(World.class);
    when(world.getUID()).thenReturn(new UUID(1L, 2L));
    Block block = mock(Block.class);
    when(block.getWorld()).thenReturn(world);
    when(block.getX()).thenReturn(10);
    when(block.getY()).thenReturn(64);
    when(block.getZ()).thenReturn(20);
    return block;
  }

  @Test
  @DisplayName("完整的结果缓存 30 秒；建拆牌子时清空")
  void completeScansAreCached() {
    Block rail = rail();
    index.around(rail);
    clock.addAndGet(StopMarkIndex.CACHE_MILLIS - 1);
    index.around(rail);
    assertEquals(1, scans.get());
    index.invalidate();
    index.around(rail);
    assertEquals(2, scans.get());
    clock.addAndGet(StopMarkIndex.CACHE_MILLIS);
    index.around(rail);
    assertEquals(3, scans.get());
  }

  @Test
  @DisplayName("沿途区块没加载完的结果只存一会儿")
  void incompleteScansExpireQuickly() {
    complete = false;
    Block rail = rail();
    index.around(rail);
    index.around(rail);
    assertEquals(1, scans.get(), "同一拍不重扫");
    clock.addAndGet(StopMarkIndex.INCOMPLETE_CACHE_MILLIS);
    index.around(rail);
    assertEquals(2, scans.get());
  }
}
