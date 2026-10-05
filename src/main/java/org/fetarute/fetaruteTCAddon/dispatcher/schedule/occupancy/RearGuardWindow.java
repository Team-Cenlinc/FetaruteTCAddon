package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.function.IntUnaryOperator;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;

/**
 * 尾部保护的范围：从车头最近到达（或越过）的节点往回，先覆盖整列车，再在车尾之后多保留几条边。
 *
 * <p>运行时建尾部保护（{@link OccupancyRequestBuilder}）与编表算闭塞时间共用这一份规则：编表要知道前车什么时候把身后的线路放出来，
 * 不能另写一套——两边一旦不一致，表上的跟车间隔就会和实际差出一截。
 */
public final class RearGuardWindow {

  private RearGuardWindow() {}

  /**
   * 从车头往回保留几条边。
   *
   * <p>车身从车头所在节点往回逐边累计，直到覆盖车长；车尾落在哪条边上，那条边就算车身。之后再多保留 {@code rearGuardEdges} 条。 车长未知（{@link
   * Long#MAX_VALUE}）时保留全部可用的边；可用的边不够时同样全部保留，而不是缩短阈值。
   *
   * @param availableEdges 车头往回可用的边数
   * @param lengthBackward 第 {@code i} 条边（0 是紧挨车头的那条）的长度（格）；负数按 0 算
   * @param trainLengthBlocks 保守车长（格）
   * @param rearGuardEdges 车尾之后多保留的边数
   * @return 保留的边数，不超过 {@code availableEdges}
   */
  public static int retainedEdges(
      int availableEdges,
      IntUnaryOperator lengthBackward,
      long trainLengthBlocks,
      int rearGuardEdges) {
    if (availableEdges <= 0 || (rearGuardEdges <= 0 && trainLengthBlocks <= 0L)) {
      return 0;
    }
    long bodyDistance = 0L;
    int bodyEdges = 0;
    while (bodyEdges < availableEdges && bodyDistance < trainLengthBlocks) {
      bodyDistance = saturatingAdd(bodyDistance, lengthBackward.applyAsInt(bodyEdges));
      bodyEdges++;
    }
    return Math.min(availableEdges, bodyEdges + Math.max(0, rearGuardEdges));
  }

  /**
   * 车头经过这种中间图节点（不在交路定义里的）时，运行时会把它记作"最后经过的图节点"，尾部保护改从它往回量：路径点与道岔。 车站、车库等其它节点不记——不在交路里的车站牌子运行时直接跳过。
   *
   * @param type 节点类型
   */
  public static boolean tracksIntermediateNode(NodeType type) {
    return type == NodeType.WAYPOINT || type == NodeType.SWITCHER;
  }

  private static long saturatingAdd(long left, int right) {
    if (right <= 0) {
      return left;
    }
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }
}
