package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 同一辆车在同一站台几拍之内又被车站牌子触发：后来的那次是重复，只开一个停站。
 *
 * <p>联挂把两列并成一个编组时，TrainCarts 会对新编组补发一次进站，挂上后又会对合并的列车重新触发一次这块牌子。两次各开一个停站会话：门各开关一轮、
 * 后开的会话覆盖发车门控，联挂“这一站不再多停”的记录被前一次用掉，后一次又多停一回联挂时间。正常停站至少几十秒，同一站台几拍之内不会有第二次真的进站。
 */
final class StopTriggerDedup {

  /** 视为同一次进站的窗口（tick）。 */
  static final long WINDOW_TICKS = 40L;

  /** 记录超过这么多条时顺手清掉窗口外的。 */
  private static final int PRUNE_THRESHOLD = 256;

  private final Map<String, Start> starts = new ConcurrentHashMap<>();

  private record Start(NodeId node, long tick) {}

  /**
   * 登记一次进站触发。
   *
   * @param trainName 列车名
   * @param node 站台节点
   * @param tick 当前 tick
   * @return 是新的进站时为 true；同一辆车在同一站台窗口之内已经触发过时为 false
   */
  boolean claim(String trainName, NodeId node, long tick) {
    if (trainName == null || trainName.isBlank() || node == null) {
      return true;
    }
    String key = trainName.trim().toLowerCase(Locale.ROOT);
    Start previous = starts.get(key);
    if (previous != null
        && previous.node().equals(node)
        && tick >= previous.tick()
        && tick - previous.tick() <= WINDOW_TICKS) {
      return false;
    }
    starts.put(key, new Start(node, tick));
    if (starts.size() > PRUNE_THRESHOLD) {
      starts.values().removeIf(start -> tick - start.tick() > WINDOW_TICKS);
    }
    return true;
  }
}
