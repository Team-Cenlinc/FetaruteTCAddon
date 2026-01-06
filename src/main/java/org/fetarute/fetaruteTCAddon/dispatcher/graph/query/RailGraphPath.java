package org.fetarute.fetaruteTCAddon.dispatcher.graph.query;

import java.util.List;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 一条在 {@code RailGraph} 上规划出的路径：包含节点序列与区间序列。
 *
 * <p>约束（构造时校验）：
 *
 * <ul>
 *   <li>{@code nodes} 不能为空，且首尾必须分别等于 {@code from}/{@code to}
 *   <li>{@code edges.size() == nodes.size() - 1}
 *   <li>{@code totalLengthBlocks} 为非负数（通常为 {@code edges.lengthBlocks} 的累计）
 * </ul>
 *
 * @param from 起点节点
 * @param to 终点节点
 * @param nodes 节点序列（含起点与终点）
 * @param edges 区间序列（与 nodes 相邻成对）
 * @param totalLengthBlocks 路径长度（blocks）
 */
public record RailGraphPath(
    NodeId from, NodeId to, List<NodeId> nodes, List<RailEdge> edges, long totalLengthBlocks) {

  public RailGraphPath {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
    edges = edges == null ? List.of() : List.copyOf(edges);
    if (nodes.isEmpty()) {
      throw new IllegalArgumentException("nodes 不能为空");
    }
    if (!nodes.get(0).equals(from) || !nodes.get(nodes.size() - 1).equals(to)) {
      throw new IllegalArgumentException("nodes 端点与 from/to 不一致");
    }
    if (nodes.size() != edges.size() + 1) {
      throw new IllegalArgumentException("nodes 与 edges 数量不匹配");
    }
    if (totalLengthBlocks < 0) {
      throw new IllegalArgumentException("totalLengthBlocks 不能为负");
    }
  }
}
