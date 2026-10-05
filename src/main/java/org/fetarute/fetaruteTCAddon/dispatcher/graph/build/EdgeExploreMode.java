package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

/**
 * 边探索参数：从每个节点出发，沿轨道走到下一个节点就停。
 *
 * @param maxDistanceBlocks 单方向最多走多远
 */
public record EdgeExploreMode(int maxDistanceBlocks) {

  /**
   * 节点到节点探索的单方向上限（blocks）。
   *
   * <p>超过上限仍未遇到节点的方向按尽头线处理（见 {@link
   * UnterminatedDirection}），因此上限必须远大于正常区间长度：取小了，很长但确实接回线网的区间会被误当成尽头线从图中消失， TrainCarts
   * 仍可能按物理最短路把列车引上这段图外轨道。只有无节点的延伸线会走到上限，放宽它不影响普通区间的探索量。
   */
  public static final int NODE_TO_NODE_MAX_DISTANCE = 4096;

  public static EdgeExploreMode nodeToNode() {
    return new EdgeExploreMode(NODE_TO_NODE_MAX_DISTANCE);
  }
}
