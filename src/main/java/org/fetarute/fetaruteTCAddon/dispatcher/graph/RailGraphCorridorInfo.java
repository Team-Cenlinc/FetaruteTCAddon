package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 单线走廊信息：提供有序端点与路径节点。
 *
 * @param key 走廊稳定标识（基于端点排序生成）
 * @param left 走廊左端点（与 key 的排序规则一致）
 * @param right 走廊右端点（与 key 的排序规则一致）
 * @param nodes 走廊内按路径顺序排列的节点列表（含端点）
 * @param cycle 是否为闭环走廊（闭环不具备方向）
 */
public record RailGraphCorridorInfo(
    String key, NodeId left, NodeId right, List<NodeId> nodes, boolean cycle) {

  public RailGraphCorridorInfo {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(nodes, "nodes");
    nodes = List.copyOf(nodes);
  }

  /**
   * @return 是否具备方向性（非闭环且端点完整）。
   */
  public boolean directional() {
    return !cycle && left != null && right != null && nodes.size() >= 2;
  }
}
