package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import com.bergerkiller.bukkit.common.utils.ChunkUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SwitcherSignDefinitionParser;

/**
 * 核对库里的道岔节点是否已经作废（牌子改成了写条件或 tag 的道岔），按需分批加载区块：找一点、判一点、放一点。
 *
 * <p>判定要读完整的牌子柱：TrainCarts 查牌子会碰到轨道 x、z 各 ±1 覆盖的区块，遇到没加载的会在主线程同步加载。所以判定前先把这些区块异步加载并挂上本插件的区块票，
 * 判完立即撤票，交还服务器按正常规则卸载。只撤本次新挂上的票，不调用任何卸载接口：列车、玩家以及本插件别处挂的票都不受影响。
 *
 * <p>道岔按区块排序，相邻道岔共用已加载的区块；同一时间为核对挂票或在途加载的区块不超过窗口（队首道岔需要的区块例外，否则窗口小于它需要的区块数时会卡住），
 * 新发起的加载总数不超过上限。超出上限、加载失败或判定出错的节点保留并记为未核对。只在服务器主线程使用。
 */
public final class SwitcherNodeAudit {

  /** 区块的加载与区块票，生产环境见 {@link #bukkitChunks}。 */
  public interface ChunkAccess {
    boolean isLoaded(int chunkX, int chunkZ);

    /** 异步加载；完成时区块已在内存里（随后由 {@link #hold} 保住）。 */
    CompletableFuture<?> load(int chunkX, int chunkZ);

    /**
     * 为核对挂一张区块票。
     *
     * @return 是否新挂上了票；区块未加载、或本插件别处已经挂着票时为 {@code false}（那张票不归核对撤）
     */
    boolean hold(int chunkX, int chunkZ);

    /** 撤掉 {@link #hold} 挂上的票。 */
    void release(int chunkX, int chunkZ);
  }

  /** 判定一段道岔轨道上的节点是否作废；调用时它需要的区块都已加载。 */
  @FunctionalInterface
  public interface Judge {
    boolean isStale(RailBlockPos rail);
  }

  /**
   * 核对结果。
   *
   * @param stale 牌子已改成条件或 tag 道岔、应当移除的节点
   * @param unverified 区块超出上限、加载失败或判定出错而保留的节点
   * @param audited 参与核对的道岔节点数
   * @param chunksLoaded 为核对新发起加载的区块数
   */
  public record Result(
      List<RailNodeRecord> stale, List<RailNodeRecord> unverified, int audited, int chunksLoaded) {
    public Result {
      stale = List.copyOf(stale);
      unverified = List.copyOf(unverified);
    }
  }

  private record Item(RailNodeRecord node, RailBlockPos rail, long[] chunks) {}

  private final List<Item> items;
  private final ChunkAccess chunks;
  private final Judge judge;
  private final int window;
  private int budget;
  private int loadsStarted;

  /** 下一个待判定的道岔。 */
  private int next;

  /** 已为其排好区块的道岔之后的第一个。 */
  private int ahead;

  private final Set<Long> held = new HashSet<>();
  private final Map<Long, CompletableFuture<?>> inFlight = new HashMap<>();
  private final Set<Long> failed = new HashSet<>();

  /** [next, ahead) 内各道岔对区块的引用数：降到零才撤票。 */
  private final Map<Long, Integer> needed = new HashMap<>();

  private final List<RailNodeRecord> stale = new ArrayList<>();
  private final List<RailNodeRecord> unverified = new ArrayList<>();
  private boolean closed;

  /**
   * @param nodes 库里的节点；只核对其中的道岔节点
   * @param window 同一时间为核对挂票或在途加载的区块上限
   * @param maxChunks 新发起加载的区块总数上限
   */
  public SwitcherNodeAudit(
      List<RailNodeRecord> nodes, ChunkAccess chunks, Judge judge, int window, int maxChunks) {
    this.chunks = Objects.requireNonNull(chunks, "chunks");
    this.judge = Objects.requireNonNull(judge, "judge");
    if (window <= 0 || maxChunks < 0) {
      throw new IllegalArgumentException("window 必须为正数，maxChunks 不能为负数");
    }
    this.window = window;
    this.budget = maxChunks;
    List<Item> switchers = new ArrayList<>();
    for (RailNodeRecord node : nodes) {
      if (node.nodeType() != NodeType.SWITCHER) {
        continue;
      }
      RailBlockPos rail =
          SwitcherSignDefinitionParser.tryParseRailPos(node.nodeId())
              .orElse(new RailBlockPos(node.x(), node.y(), node.z()));
      switchers.add(new Item(node, rail, chunksAround(rail)));
    }
    switchers.sort(
        Comparator.<Item>comparingInt(item -> item.rail().x() >> 4)
            .thenComparingInt(item -> item.rail().z() >> 4)
            .thenComparingInt(item -> item.rail().x())
            .thenComparingInt(item -> item.rail().y())
            .thenComparingInt(item -> item.rail().z()));
    this.items = List.copyOf(switchers);
  }

  /** 生产环境：BKCommonLib 异步加载区块，本插件的区块票保住它。 */
  public static ChunkAccess bukkitChunks(World world, Plugin plugin) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(plugin, "plugin");
    return new ChunkAccess() {
      @Override
      public boolean isLoaded(int chunkX, int chunkZ) {
        return world.isChunkLoaded(chunkX, chunkZ);
      }

      @Override
      public CompletableFuture<?> load(int chunkX, int chunkZ) {
        return ChunkUtil.getChunkAsync(world, chunkX, chunkZ);
      }

      @Override
      public boolean hold(int chunkX, int chunkZ) {
        return world.isChunkLoaded(chunkX, chunkZ)
            && world.addPluginChunkTicket(chunkX, chunkZ, plugin);
      }

      @Override
      public void release(int chunkX, int chunkZ) {
        world.removePluginChunkTicket(chunkX, chunkZ, plugin);
      }
    };
  }

  /** 区块坐标打包成一个键。 */
  static long chunkKey(int chunkX, int chunkZ) {
    return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
  }

  private static int chunkX(long key) {
    return (int) (key >> 32);
  }

  private static int chunkZ(long key) {
    return (int) key;
  }

  /** 轨道 x、z 各 ±1 覆盖的区块：TrainCarts 读牌子柱时可能碰到的范围。 */
  private static long[] chunksAround(RailBlockPos rail) {
    Set<Long> keys = new LinkedHashSet<>();
    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        keys.add(chunkKey((rail.x() + dx) >> 4, (rail.z() + dz) >> 4));
      }
    }
    return keys.stream().mapToLong(Long::longValue).toArray();
  }

  /**
   * 推进一步：收下完成的加载、在窗口内发起新的加载、判定区块已齐的道岔并撤掉不再需要的票。
   *
   * @param deadlineNanos 判定在这个时刻之后停下，留到下一 tick
   * @return 是否已经核对完
   */
  public boolean step(long deadlineNanos) {
    if (closed) {
      return true;
    }
    boolean progressed = true;
    while (progressed && next < items.size()) {
      collectLoads();
      requestChunks();
      progressed = judgeReady(deadlineNanos);
      if (System.nanoTime() >= deadlineNanos) {
        break;
      }
    }
    if (next >= items.size()) {
      releaseAll();
      return true;
    }
    return false;
  }

  public boolean isDone() {
    return closed || next >= items.size();
  }

  public Result result() {
    return new Result(stale, unverified, items.size(), loadsStarted);
  }

  /** 放弃核对：撤掉已挂的票；之后完成的加载不再挂票。 */
  public void close() {
    closed = true;
    releaseAll();
    inFlight.clear();
  }

  private void collectLoads() {
    Iterator<Map.Entry<Long, CompletableFuture<?>>> it = inFlight.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<Long, CompletableFuture<?>> entry = it.next();
      if (!entry.getValue().isDone()) {
        continue;
      }
      it.remove();
      long key = entry.getKey();
      if (entry.getValue().isCompletedExceptionally()) {
        failed.add(key);
      } else if (chunks.hold(chunkX(key), chunkZ(key))) {
        held.add(key);
      } else if (!chunks.isLoaded(chunkX(key), chunkZ(key))) {
        failed.add(key);
      }
    }
  }

  private void requestChunks() {
    while (ahead < items.size()) {
      Item item = items.get(ahead);
      for (long key : item.chunks()) {
        if (held.contains(key) || inFlight.containsKey(key) || failed.contains(key)) {
          continue;
        }
        if (held.size() + inFlight.size() >= window && ahead > next) {
          return;
        }
        int x = chunkX(key);
        int z = chunkZ(key);
        if (chunks.isLoaded(x, z)) {
          if (chunks.hold(x, z)) {
            held.add(key);
          }
        } else if (budget > 0) {
          budget--;
          loadsStarted++;
          inFlight.put(key, chunks.load(x, z));
        }
      }
      for (long key : item.chunks()) {
        needed.merge(key, 1, Integer::sum);
      }
      ahead++;
    }
  }

  /** 判定队首起区块已齐的道岔；遇到还在加载的就停。 */
  private boolean judgeReady(long deadlineNanos) {
    boolean progressed = false;
    while (next < ahead && System.nanoTime() < deadlineNanos) {
      Item item = items.get(next);
      boolean loading = false;
      boolean ready = true;
      for (long key : item.chunks()) {
        if (inFlight.containsKey(key)) {
          loading = true;
        } else if (!chunks.isLoaded(chunkX(key), chunkZ(key))) {
          ready = false;
        }
      }
      if (loading) {
        break;
      }
      if (ready) {
        judge(item);
      } else {
        unverified.add(item.node());
      }
      next++;
      progressed = true;
      for (long key : item.chunks()) {
        if (needed.merge(key, -1, Integer::sum) <= 0) {
          needed.remove(key);
          if (held.remove(key)) {
            chunks.release(chunkX(key), chunkZ(key));
          }
        }
      }
    }
    return progressed;
  }

  private void judge(Item item) {
    try {
      if (judge.isStale(item.rail())) {
        stale.add(item.node());
      }
    } catch (RuntimeException | LinkageError ex) {
      unverified.add(item.node());
    }
  }

  private void releaseAll() {
    for (long key : held) {
      chunks.release(chunkX(key), chunkZ(key));
    }
    held.clear();
  }
}
