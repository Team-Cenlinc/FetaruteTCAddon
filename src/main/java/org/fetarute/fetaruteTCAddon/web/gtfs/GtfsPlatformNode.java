package org.fetarute.fetaruteTCAddon.web.gtfs;

import java.util.Objects;

/**
 * 调度图里的一条车站股道节点（{@link org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType#STATION}）。
 *
 * <p>站咽喉、区间点、车库与道岔都不发布：GTFS 的停靠点只到股道与车站两级。
 *
 * @param nodeId 调度图节点 ID（如 {@code SURC:S:HHU:3}）
 * @param x 世界 x（米）
 * @param z 世界 z（米）
 */
public record GtfsPlatformNode(String nodeId, double x, double z) {

  public GtfsPlatformNode {
    nodeId = Objects.requireNonNull(nodeId, "nodeId").trim();
    if (nodeId.isEmpty()) {
      throw new IllegalArgumentException("nodeId 不能为空");
    }
  }
}
