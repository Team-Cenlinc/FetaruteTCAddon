package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitScheduler;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** 区间生成要用到的区块：按车身方向估计，没加载的先在后台加载、挂票，这一次不生成。 */
class EntrySpawnChunksTest {

  private final FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
  private final World world = mock(World.class);
  private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
  private final Map<Long, CompletableFuture<Chunk>> loads = new HashMap<>();
  private MockedStatic<Bukkit> bukkit;
  private TrainCartsDepotSpawner spawner;

  @BeforeEach
  void setUp() {
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
    bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
    when(world.getUID()).thenReturn(UUID.randomUUID());
    when(world.getChunkAtAsync(anyInt(), anyInt()))
        .thenAnswer(
            invocation -> {
              CompletableFuture<Chunk> future = new CompletableFuture<>();
              loads.put(
                  TrainCartsDepotSpawner.chunkKey(
                      invocation.getArgument(0), invocation.getArgument(1)),
                  future);
              return future;
            });
    spawner = new TrainCartsDepotSpawner(plugin, mock(SignNodeRegistry.class), message -> {});
  }

  @AfterEach
  void tearDown() {
    bukkit.close();
  }

  /** 车头在区间点、朝 +X 开：车身往 -X 铺；每节车周围两圈。 */
  @Test
  void theBodyLiesBehindTheEntryAlongTheTrack() {
    Set<Long> chunks = TrainCartsDepotSpawner.entryBodyChunks(100, 8, 1.0, 0.0, 36.0);

    // 区间点在区块 (6, 0)，车尾在 x = 64.5 一带（区块 4）：X 从 4-2 到 6+2，Z 从 -2 到 2。
    assertEquals(7 * 5, chunks.size());
    assertTrue(chunks.contains(TrainCartsDepotSpawner.chunkKey(2, -2)));
    assertTrue(chunks.contains(TrainCartsDepotSpawner.chunkKey(8, 2)));
    assertFalse(chunks.contains(TrainCartsDepotSpawner.chunkKey(9, 0)), "车头前方不铺车身");
  }

  /** 方向不明：按区间点周围一个车长估计。 */
  @Test
  void withoutADirectionTheWholeSurroundingIsTaken() {
    Set<Long> chunks = TrainCartsDepotSpawner.entryBodyChunks(0, 0, 0.0, 0.0, 36.0);

    assertEquals(11 * 11, chunks.size());
  }

  /** 都加载了：照常生成，不发请求。 */
  @Test
  void loadedChunksNeedNothing() {
    when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);

    assertFalse(
        spawner.preloadEntryChunks(
            world, TrainCartsDepotSpawner.entryBodyChunks(100, 8, 1.0, 0.0, 36.0)));

    verify(world, never()).getChunkAtAsync(anyInt(), anyInt());
  }

  /** 有没加载的：在后台加载（同一区块不重复请求）、这一次不生成；加载好后挂票，过一阵摘掉。主线程上不同步读区块。 */
  @Test
  void missingChunksLoadInTheBackgroundAndAreHeld() {
    when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
    Set<Long> chunks = Set.of(TrainCartsDepotSpawner.chunkKey(3, 4));

    assertTrue(spawner.preloadEntryChunks(world, chunks));
    assertTrue(spawner.preloadEntryChunks(world, chunks), "还在加载");
    verify(world, times(1)).getChunkAtAsync(3, 4);
    verify(world, never()).getChunkAt(anyInt(), anyInt());
    verify(world, never()).addPluginChunkTicket(anyInt(), anyInt(), eq(plugin));

    when(world.isChunkLoaded(3, 4)).thenReturn(true);
    when(world.addPluginChunkTicket(3, 4, plugin)).thenReturn(true);
    loads.get(TrainCartsDepotSpawner.chunkKey(3, 4)).complete(mock(Chunk.class));

    verify(world).addPluginChunkTicket(3, 4, plugin);
    verify(scheduler)
        .runTaskLater(eq(plugin), org.mockito.ArgumentMatchers.any(Runnable.class), anyLong());
    assertFalse(spawner.preloadEntryChunks(world, chunks), "加载好了，可以生成");
  }
}
