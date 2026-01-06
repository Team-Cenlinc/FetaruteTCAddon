package org.fetarute.fetaruteTCAddon.dispatcher.graph.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.PriorityQueue;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 在 {@link RailGraph} 上进行最短路查询的工具类。
 *
 * <p>当前实现基于 Dijkstra：对无向稀疏图在诊断场景下足够稳定，同时允许通过 {@link Options#costModel()} 替换代价模型，
 * 为后续“按时间最短/按距离最短/按权重”扩展预留接口。
 *
 * <p>注意：本类不负责解释代价值的单位；单位由 {@link RailEdgeCostModel} 的实现定义（blocks/meters/ms 等）。
 */
public final class RailGraphPathFinder {

  /** 最短路查询选项。 */
  public record Options(RailEdgeCostModel costModel, boolean allowBlockedEdges) {
    public Options {
      Objects.requireNonNull(costModel, "costModel");
    }

    /** 距离最短（以 blocks 计），并默认跳过被封锁的边。 */
    public static Options shortestDistance() {
      return new Options(RailEdgeCostModels.lengthBlocks(), false);
    }
  }

  /**
   * 计算从 {@code from} 到 {@code to} 的最短路径。
   *
   * <p>默认行为不穿越被封锁的边；若需要把封锁边也纳入计算，可在 {@link Options#allowBlockedEdges()} 中显式开启。
   *
   * @return empty 表示不可达或输入节点不存在
   */
  public Optional<RailGraphPath> shortestPath(
      RailGraph graph, NodeId from, NodeId to, Options options) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Objects.requireNonNull(options, "options");

    if (graph.findNode(from).isEmpty() || graph.findNode(to).isEmpty()) {
      return Optional.empty();
    }

    if (from.equals(to)) {
      return Optional.of(new RailGraphPath(from, to, List.of(from), List.of(), 0L));
    }

    Map<NodeId, Double> dist = new HashMap<>();
    Map<NodeId, NodeId> prev = new HashMap<>();
    Map<NodeId, RailEdge> prevEdge = new HashMap<>();
    PriorityQueue<Entry> queue = new PriorityQueue<>(Comparator.comparingDouble(Entry::distance));

    dist.put(from, 0.0);
    queue.add(new Entry(from, 0.0));

    while (!queue.isEmpty()) {
      Entry currentEntry = queue.poll();
      if (currentEntry == null) {
        continue;
      }
      NodeId current = currentEntry.nodeId();
      Double currentBest = dist.get(current);
      if (currentBest == null || Math.abs(currentBest - currentEntry.distance()) > 1e-9) {
        continue;
      }
      if (current.equals(to)) {
        break;
      }

      for (RailEdge edge : graph.edgesFrom(current)) {
        if (edge == null) {
          continue;
        }
        if (!options.allowBlockedEdges() && graph.isBlocked(edge.id())) {
          continue;
        }
        NodeId neighbor = current.equals(edge.from()) ? edge.to() : edge.from();
        if (neighbor == null) {
          continue;
        }
        OptionalDouble costOpt = options.costModel().cost(graph, edge, current, neighbor);
        if (costOpt.isEmpty()) {
          continue;
        }
        double cost = costOpt.getAsDouble();
        if (!Double.isFinite(cost) || cost <= 0.0) {
          continue;
        }
        double nextDistance = currentBest + cost;
        if (!Double.isFinite(nextDistance) || nextDistance < 0.0) {
          continue;
        }

        Double bestKnown = dist.get(neighbor);
        if (bestKnown == null || nextDistance + 1e-9 < bestKnown) {
          dist.put(neighbor, nextDistance);
          prev.put(neighbor, current);
          prevEdge.put(neighbor, edge);
          queue.add(new Entry(neighbor, nextDistance));
        }
      }
    }

    if (!dist.containsKey(to)) {
      return Optional.empty();
    }

    List<NodeId> nodesReversed = new ArrayList<>();
    List<RailEdge> edgesReversed = new ArrayList<>();
    NodeId current = to;
    nodesReversed.add(current);
    while (!current.equals(from)) {
      RailEdge edge = prevEdge.get(current);
      NodeId parent = prev.get(current);
      if (edge == null || parent == null) {
        return Optional.empty();
      }
      edgesReversed.add(edge);
      current = parent;
      nodesReversed.add(current);
    }

    List<NodeId> nodes = new ArrayList<>(nodesReversed.size());
    for (int i = nodesReversed.size() - 1; i >= 0; i--) {
      nodes.add(nodesReversed.get(i));
    }

    List<RailEdge> edges = new ArrayList<>(edgesReversed.size());
    for (int i = edgesReversed.size() - 1; i >= 0; i--) {
      edges.add(edgesReversed.get(i));
    }

    long totalLengthBlocks = 0L;
    for (RailEdge edge : edges) {
      if (edge == null) {
        continue;
      }
      int length = edge.lengthBlocks();
      if (length > 0) {
        totalLengthBlocks += length;
      }
    }
    return Optional.of(new RailGraphPath(from, to, nodes, edges, totalLengthBlocks));
  }

  private record Entry(NodeId nodeId, double distance) {
    private Entry {
      Objects.requireNonNull(nodeId, "nodeId");
      if (!Double.isFinite(distance) || distance < 0.0) {
        throw new IllegalArgumentException("distance 不能为负");
      }
    }
  }
}
