package org.fetarute.fetaruteTCAddon.dispatcher.graph.explore;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 从节点附近的“锚点轨道方块”出发，探索轨道连通性并生成区间（Edge）长度。
 *
 * <p>约束：
 *
 * <ul>
 *   <li>探索在轨道方块图上进行：每步移动视为经过 1 个方块
 *   <li>当搜索触达“其他节点的锚点方块”时，认为找到了区间终点，并停止继续向外扩展（避免跨越节点）
 * </ul>
 */
public final class RailGraphExplorer {

  private RailGraphExplorer() {}

  /**
   * 根据每个节点的锚点轨道方块，探索节点之间的区间长度（以方块数计）。
   *
   * @param anchorsByNode 节点 → 锚点轨道方块集合
   * @param access 轨道访问器
   * @param maxDistanceBlocks 单次 BFS 最大探索距离，避免无限环路或误扫整图
   * @return EdgeId → 最短区间长度（方块数）
   */
  public static Map<EdgeId, Integer> exploreEdgeLengths(
      Map<NodeId, Set<RailBlockPos>> anchorsByNode, RailBlockAccess access, int maxDistanceBlocks) {
    Objects.requireNonNull(anchorsByNode, "anchorsByNode");
    Objects.requireNonNull(access, "access");
    if (maxDistanceBlocks <= 0) {
      throw new IllegalArgumentException("maxDistanceBlocks 必须为正数");
    }

    RailGraphMultiSourceExplorerSession session =
        new RailGraphMultiSourceExplorerSession(anchorsByNode, access, maxDistanceBlocks);
    while (!session.isDone()) {
      session.step(10_000);
    }
    return session.edgeLengths();
  }
}
