package org.fetarute.fetaruteTCAddon.coupler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 刚在站台上挂上的列车：挂上后这条股道的车站牌子还会再触发，AutoStation 据此决定怎么停站。
 *
 * <p>TrainCarts 联挂时清掉两列车的牌子跟踪，下一拍合并的列车把身下的牌子都重新“进入”一遍；回程支线车先到时我们还会对合并的列车重新触发一次车站牌子。 只开一个停站：
 *
 * <ul>
 *   <li>开进来的是主干（回程支线车先到）：主干还没在这一站停过，第一次触发停站（开门、停站、发车照常；先到的车已经按挂上后的整列停在停车位置标处时原地停、不再挪车）， 之后的都是重复。
 *   <li>开进来的是回程支线车（主干先到）：主干的停站还没放行（发车许可锁还在，关门后等联挂扣留的那一段也算）时它在继续，挂上后的触发都是重复，
 *       车门开着时停站会话给挂上来的那几节同步开关门；停站已经结束时第一次触发重新停站。
 * </ul>
 *
 * 只认挂上后几拍之内、同一条股道的触发。本类不依赖服务器对象。
 */
public final class LinkedStops {

  /** 挂上后多少 tick 之内的触发算挂上带来的。 */
  static final long WINDOW_TICKS = 100L;

  /** 记录超过这么多条时顺手清掉窗口外的。 */
  private static final int PRUNE_THRESHOLD = 64;

  /** AutoStation 对挂上后这一次触发怎么办。 */
  public enum Arrival {
    /** 不是刚挂上的：照常停站。 */
    NONE,
    /** 挂上后第一次停站：车头已经停在整列节数的停车位置标处时原地停，否则照常对位。 */
    FIRST_STOP,
    /** 重复：这一站已经在停（或挂上后已经停过），跳过。 */
    DUPLICATE
  }

  private static final class Linked {
    final NodeId platform;
    final long tick;
    final boolean trunkStanding;
    boolean pending = true;

    Linked(NodeId platform, long tick, boolean trunkStanding) {
      this.platform = platform;
      this.tick = tick;
      this.trunkStanding = trunkStanding;
    }
  }

  private final Map<String, Linked> linked = new ConcurrentHashMap<>();

  /**
   * 登记一次挂上。
   *
   * @param key 合并后列车的车名键
   * @param platform 挂上的股道
   * @param trunkStanding 主干先到、停在站台上（开进来的是回程支线车）
   * @param tick 当前 tick
   */
  void record(String key, NodeId platform, boolean trunkStanding, long tick) {
    if (key == null || key.isEmpty() || platform == null) {
      return;
    }
    linked.put(key, new Linked(platform, tick, trunkStanding));
    if (linked.size() > PRUNE_THRESHOLD) {
      linked.values().removeIf(entry -> tick - entry.tick > WINDOW_TICKS);
    }
  }

  /**
   * 车站牌子触发了：是不是挂上带来的。停站只给第一次，之后的都是重复。
   *
   * @param key 车名键
   * @param node 触发的车站节点
   * @param tick 当前 tick
   * @param stopRunning 这列车此刻有没有还没放行的停站（主干先到时才问）
   */
  Arrival take(String key, NodeId node, long tick, BooleanSupplier stopRunning) {
    Linked entry = key == null ? null : linked.get(key);
    if (entry == null) {
      return Arrival.NONE;
    }
    if (tick < entry.tick || tick - entry.tick > WINDOW_TICKS) {
      linked.remove(key, entry);
      return Arrival.NONE;
    }
    if (node == null || !entry.platform.value().equalsIgnoreCase(node.value())) {
      return Arrival.NONE;
    }
    if (!entry.pending) {
      return Arrival.DUPLICATE;
    }
    entry.pending = false;
    return entry.trunkStanding && stopRunning.getAsBoolean()
        ? Arrival.DUPLICATE
        : Arrival.FIRST_STOP;
  }

  /** 全部清掉（停服、重载）。 */
  void clear() {
    linked.clear();
  }
}
