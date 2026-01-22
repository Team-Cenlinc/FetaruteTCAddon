package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;

/**
 * 路径进度模型：从“线路定义 + 当前索引 + 目标”推导剩余边列表。
 *
 * <p>核心原则：edge-first。
 *
 * <ul>
 *   <li>route 的 waypoints 是“有序节点列表”（用于确定方向）。
 *   <li>若相邻 waypoint 在图中不直连，使用最短路补全（与占用请求构建逻辑一致）。
 * </ul>
 */
public final class PathProgressModel {

  private final RailGraphPathFinder pathFinder = new RailGraphPathFinder();

  public record PathProgress(List<NodeId> remainingNodes, List<RailEdge> remainingEdges) {
    public PathProgress {
      remainingNodes = remainingNodes == null ? List.of() : List.copyOf(remainingNodes);
      remainingEdges = remainingEdges == null ? List.of() : List.copyOf(remainingEdges);
    }

    public int remainingEdgeCount() {
      return remainingEdges.size();
    }
  }

  /** 计算到目标节点的剩余路径（从 currentIndex->currentIndex+1 开始）。 */
  public Optional<PathProgress> remainingToNode(
      RailGraph graph, RouteDefinition route, int currentIndex, NodeId target) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(route, "route");
    Objects.requireNonNull(target, "target");

    List<NodeId> waypoints = route.waypoints();
    if (waypoints.isEmpty() || currentIndex < 0 || currentIndex >= waypoints.size() - 1) {
      return Optional.empty();
    }

    // 找到目标在 waypoint 中的位置（若不存在则无法定位）。
    int targetIndex = -1;
    for (int i = currentIndex + 1; i < waypoints.size(); i++) {
      if (target.equals(waypoints.get(i))) {
        targetIndex = i;
        break;
      }
    }
    if (targetIndex < 0) {
      return Optional.empty();
    }

    List<NodeId> segmentWaypoints = new ArrayList<>();
    for (int i = currentIndex; i <= targetIndex; i++) {
      segmentWaypoints.add(waypoints.get(i));
    }

    List<NodeId> expandedNodes = expand(graph, segmentWaypoints);
    if (expandedNodes.isEmpty() || expandedNodes.size() < 2) {
      return Optional.empty();
    }

    List<RailEdge> edges = resolveEdges(graph, expandedNodes);
    if (edges.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new PathProgress(expandedNodes, edges));
  }

  private List<NodeId> expand(RailGraph graph, List<NodeId> nodes) {
    List<NodeId> expanded = new ArrayList<>();
    expanded.add(nodes.get(0));
    for (int i = 0; i < nodes.size() - 1; i++) {
      NodeId from = nodes.get(i);
      NodeId to = nodes.get(i + 1);
      if (findEdge(graph, from, to).isPresent()) {
        expanded.add(to);
        continue;
      }
      Optional<RailGraphPath> pathOpt =
          pathFinder.shortestPath(graph, from, to, RailGraphPathFinder.Options.shortestDistance());
      if (pathOpt.isEmpty()) {
        return List.of();
      }
      List<NodeId> segment = pathOpt.get().nodes();
      for (int j = 1; j < segment.size(); j++) {
        expanded.add(segment.get(j));
      }
    }
    return List.copyOf(expanded);
  }

  private List<RailEdge> resolveEdges(RailGraph graph, List<NodeId> nodes) {
    List<RailEdge> edges = new ArrayList<>();
    for (int i = 0; i < nodes.size() - 1; i++) {
      Optional<RailEdge> edge = findEdge(graph, nodes.get(i), nodes.get(i + 1));
      if (edge.isEmpty()) {
        return List.of();
      }
      edges.add(edge.get());
    }
    return List.copyOf(edges);
  }

  private Optional<RailEdge> findEdge(RailGraph graph, NodeId from, NodeId to) {
    for (RailEdge edge : graph.edgesFrom(from)) {
      if (edge.from().equals(from) && edge.to().equals(to)) {
        return Optional.of(edge);
      }
      if (edge.from().equals(to) && edge.to().equals(from)) {
        return Optional.of(edge);
      }
    }
    return Optional.empty();
  }
}
