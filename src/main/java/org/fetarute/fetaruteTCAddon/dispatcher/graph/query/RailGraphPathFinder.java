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
 *
 * <h2>等长平局规则（跨进程确定）</h2>
 *
 * <p>多条最短路代价相等（差值不超过 {@code 1e-9}）时，<b>从终点倒推，每一步在全部最短前驱中取 {@link NodeId} 自然序最小者</b>；同一前驱经多条区间到达时取
 * {@link org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId}
 * 自然序最小者。等价表述：在全部最短路中，取"从终点倒读的节点序列"字典序最小的那一条。
 *
 * <p>结果只取决于图的内容，与 {@link RailGraph#edgesFrom} 的遍历顺序无关——此前用严格 {@code <} 松弛、优先队列只比距离，等长时"先遍历到的邻居赢"，而
 * {@code Set.copyOf} 的遍历顺序每个 JVM 随机一次，于是会让环两股道之类的等长备选每次重启换一条。
 *
 * <p>这条规则只为可复现，不表达运营偏好（例如"优先 1 道"）：它在简单会让环上恰好选中较小的股道号，但一旦股道两侧还有别的节点，选中的是"靠终点一侧节点 id
 * 较小"的那一条。需要按语义选股道的地方必须显式钉住途经节点（如 DYNAMIC 选台结果），不能依赖平局。
 */
public final class RailGraphPathFinder {

  /** 判定两条路径代价"相等"的容差；与松弛、出队判定共用，保证三处口径一致。 */
  static final double COST_EPSILON = 1e-9;

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
    // 距离相同再比节点：出队顺序只取决于图内容，不取决于入队先后。
    PriorityQueue<Entry> queue =
        new PriorityQueue<>(
            Comparator.comparingDouble(Entry::distance).thenComparing(Entry::nodeId));

    dist.put(from, 0.0);
    queue.add(new Entry(from, 0.0));

    while (!queue.isEmpty()) {
      Entry currentEntry = queue.poll();
      if (currentEntry == null) {
        continue;
      }
      NodeId current = currentEntry.nodeId();
      Double currentBest = dist.get(current);
      if (currentBest == null || Math.abs(currentBest - currentEntry.distance()) > COST_EPSILON) {
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
        if (bestKnown == null || nextDistance + COST_EPSILON < bestKnown) {
          dist.put(neighbor, nextDistance);
          prev.put(neighbor, current);
          prevEdge.put(neighbor, edge);
          queue.add(new Entry(neighbor, nextDistance));
        } else if (nextDistance <= bestKnown + COST_EPSILON
            && prefersPredecessor(current, edge, prev.get(neighbor), prevEdge.get(neighbor))) {
          // 等长平局：只换前驱，不改距离、不重复入队。代价恒为正，所以 neighbor 的全部最短前驱
          // 都严格比它先出队，它出队（或作为终点结束搜索）时前驱已是最终的最小者。
          prev.put(neighbor, current);
          prevEdge.put(neighbor, edge);
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

  /** 等长平局时，候选前驱是否优于已记录的前驱：先比节点，同节点再比区间。 */
  private static boolean prefersPredecessor(
      NodeId candidate, RailEdge candidateEdge, NodeId recorded, RailEdge recordedEdge) {
    if (recorded == null || recordedEdge == null) {
      return false;
    }
    int byNode = candidate.compareTo(recorded);
    if (byNode != 0) {
      return byNode < 0;
    }
    return candidateEdge.id().compareTo(recordedEdge.id()) < 0;
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
