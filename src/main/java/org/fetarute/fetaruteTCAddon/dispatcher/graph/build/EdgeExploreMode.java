package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

/**
 * 边探索模式配置。
 *
 * <p>当前支持两种模式：
 *
 * <ul>
 *   <li>{@link #BFS_MULTI_SOURCE} - 基于 BFS 的多源探索（原有逻辑，适合小图）
 *   <li>{@link #NODE_TO_NODE} - 使用 TrainCarts TrackWalkingPoint 的节点到节点探索（适合大图）
 * </ul>
 */
public record EdgeExploreMode(Mode mode, int maxDistanceBlocks) {

  public enum Mode {
    /** 多源 BFS 探索所有可达轨道，再用 Dijkstra 计算边长 */
    BFS_MULTI_SOURCE,
    /** 从每个节点出发，沿轨道走到下一个节点就停 */
    NODE_TO_NODE
  }

  private static final int DEFAULT_MAX_DISTANCE = 512;

  /** 使用 BFS 多源探索（原有逻辑） */
  public static EdgeExploreMode bfsMultiSource() {
    return new EdgeExploreMode(Mode.BFS_MULTI_SOURCE, DEFAULT_MAX_DISTANCE);
  }

  /** 使用节点到节点探索（新逻辑） */
  public static EdgeExploreMode nodeToNode() {
    return new EdgeExploreMode(Mode.NODE_TO_NODE, DEFAULT_MAX_DISTANCE);
  }

  public static EdgeExploreMode nodeToNode(int maxDistanceBlocks) {
    return new EdgeExploreMode(Mode.NODE_TO_NODE, maxDistanceBlocks);
  }

  public boolean isNodeToNode() {
    return mode == Mode.NODE_TO_NODE;
  }
}
