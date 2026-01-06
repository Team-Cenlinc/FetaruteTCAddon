package org.fetarute.fetaruteTCAddon.dispatcher.graph.query;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 行程时间估算模型：用于在“已得到路径”的前提下估算 ETA。
 *
 * <p>设计目的：命令层先用默认速度给出稳定的 ETA；未来可切换为“按边限速 / 按列车类型 / 按动态拥堵”的更精细估算。
 */
public interface RailTravelTimeModel {

  /**
   * 返回走过某条边的行程时间。
   *
   * @return empty 表示无法估算（例如缺少速度信息）
   */
  Optional<Duration> edgeTravelTime(RailGraph graph, RailEdge edge, NodeId from, NodeId to);

  /**
   * 估算整条路径的行程时间。
   *
   * <p>默认实现按边逐段累计；任一段无法估算则整体返回 empty。
   */
  default Optional<Duration> pathTravelTime(
      RailGraph graph, List<NodeId> nodes, List<RailEdge> edges) {
    Objects.requireNonNull(nodes, "nodes");
    Objects.requireNonNull(edges, "edges");
    if (edges.isEmpty()) {
      return Optional.of(Duration.ZERO);
    }
    if (nodes.size() != edges.size() + 1) {
      throw new IllegalArgumentException("nodes 与 edges 数量不匹配");
    }

    Duration total = Duration.ZERO;
    for (int i = 0; i < edges.size(); i++) {
      RailEdge edge = edges.get(i);
      NodeId from = nodes.get(i);
      NodeId to = nodes.get(i + 1);
      Optional<Duration> dt = edgeTravelTime(graph, edge, from, to);
      if (dt.isEmpty()) {
        return Optional.empty();
      }
      total = total.plus(dt.get());
    }
    return Optional.of(total);
  }
}
