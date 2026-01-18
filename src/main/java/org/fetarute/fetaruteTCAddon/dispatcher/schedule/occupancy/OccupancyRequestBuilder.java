package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainRuntimeState;

/**
 * 运行时占用请求构建器：把“列车状态 + 线路定义 + 图”转换成 OccupancyRequest。
 *
 * <p>默认会占用 lookahead 边与对应节点资源，并附加走廊/道岔冲突资源；道岔冲突可按 {@code switcherZoneEdges} 限制为“前 N 段边内的道岔”。
 */
public final class OccupancyRequestBuilder {

  private final RailGraph graph;
  private final int lookaheadEdges;
  private final int switcherZoneEdges;
  private final RailGraphPathFinder pathFinder = new RailGraphPathFinder();
  private static final String SWITCHER_CONFLICT_PREFIX = "switcher:";

  public OccupancyRequestBuilder(RailGraph graph, int lookaheadEdges, int switcherZoneEdges) {
    this.graph = Objects.requireNonNull(graph, "graph");
    if (lookaheadEdges <= 0) {
      throw new IllegalArgumentException("lookaheadEdges 必须大于 0");
    }
    if (switcherZoneEdges < 0) {
      throw new IllegalArgumentException("switcherZoneEdges 必须为非负数");
    }
    this.lookaheadEdges = lookaheadEdges;
    this.switcherZoneEdges = switcherZoneEdges;
  }

  /**
   * 从运行时状态与线路定义构建占用请求。
   *
   * <p>默认按当前索引起步，向前 lookahead N 段边生成资源清单。
   *
   * @return 缺少必要数据时返回 empty
   */
  public Optional<OccupancyRequest> build(
      TrainRuntimeState state, RouteDefinition route, Instant now) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(route, "route");
    Instant requestTime = now != null ? now : Instant.now();
    List<NodeId> nodes = route.waypoints();
    int currentIndex = state.routeProgress().currentIndex();
    return buildFromNodes(
        state.trainName(), Optional.of(route.id()), nodes, currentIndex, requestTime);
  }

  /**
   * 从给定的节点列表与 currentIndex 构建占用请求。
   *
   * <p>该方法用于未来从其他来源（非 RouteDefinition）构建 lookahead 请求；会同时生成节点与冲突资源。
   *
   * <p>currentIndex 指向“已抵达节点”的索引，资源从 currentIndex -> currentIndex+1 开始。
   */
  public Optional<OccupancyRequest> buildFromNodes(
      String trainName,
      Optional<RouteId> routeId,
      List<NodeId> nodes,
      int currentIndex,
      Instant now) {
    return buildContextFromNodes(trainName, routeId, nodes, currentIndex, now)
        .map(OccupancyRequestContext::request);
  }

  /**
   * 构建占用请求并返回路径上下文。
   *
   * <p>用于运行时做 lookahead 距离估算与诊断输出。
   */
  public Optional<OccupancyRequestContext> buildContextFromNodes(
      String trainName,
      Optional<RouteId> routeId,
      List<NodeId> nodes,
      int currentIndex,
      Instant now) {
    Objects.requireNonNull(trainName, "trainName");
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(nodes, "nodes");
    Instant requestTime = now != null ? now : Instant.now();
    if (nodes.isEmpty()) {
      return Optional.empty();
    }
    if (currentIndex < 0 || currentIndex >= nodes.size() - 1) {
      return Optional.empty();
    }
    int maxIndex = Math.min(nodes.size() - 1, currentIndex + lookaheadEdges);
    List<NodeId> pathNodes = new ArrayList<>();
    for (int i = currentIndex; i <= maxIndex; i++) {
      pathNodes.add(nodes.get(i));
    }
    List<NodeId> expandedNodes = expandPathNodes(pathNodes);
    if (expandedNodes.isEmpty()) {
      return Optional.empty();
    }
    List<RailEdge> edges = resolveEdges(expandedNodes);
    if (edges.isEmpty()) {
      return Optional.empty();
    }
    Set<OccupancyResource> resources = new LinkedHashSet<>();
    for (NodeId node : expandedNodes) {
      if (node == null) {
        continue;
      }
      resources.add(OccupancyResource.forNode(node));
    }
    for (RailEdge edge : edges) {
      resources.addAll(OccupancyResourceResolver.resourcesForEdge(graph, edge));
    }
    applySwitcherZoneConflicts(resources, expandedNodes);
    OccupancyRequest request =
        new OccupancyRequest(trainName, routeId, requestTime, List.copyOf(resources));
    return Optional.of(new OccupancyRequestContext(request, expandedNodes, edges));
  }

  private void applySwitcherZoneConflicts(
      Set<OccupancyResource> resources, List<NodeId> pathNodes) {
    if (resources == null || pathNodes == null || switcherZoneEdges < 0) {
      return;
    }
    Set<String> allowed = new LinkedHashSet<>();
    if (!pathNodes.isEmpty()) {
      int maxIndex = Math.min(pathNodes.size() - 1, switcherZoneEdges);
      for (int i = 0; i <= maxIndex; i++) {
        NodeId nodeId = pathNodes.get(i);
        if (nodeId == null) {
          continue;
        }
        // 仅保留“前 N 段边内”的道岔冲突资源，避免过度锁闭。
        graph
            .findNode(nodeId)
            .filter(node -> node.type() == NodeType.SWITCHER)
            .ifPresent(node -> allowed.add(OccupancyResourceResolver.switcherConflictId(node)));
      }
    }
    // 移除超出道岔联合锁闭范围的冲突资源。
    resources.removeIf(
        resource ->
            resource.kind() == ResourceKind.CONFLICT
                && resource.key().startsWith(SWITCHER_CONFLICT_PREFIX)
                && !allowed.contains(resource.key()));
    // 补回前 N 段边内的道岔冲突资源。
    for (String key : allowed) {
      resources.add(OccupancyResource.forConflict(key));
    }
  }

  private List<RailEdge> resolveEdges(List<NodeId> pathNodes) {
    List<RailEdge> edges = new ArrayList<>();
    for (int i = 0; i < pathNodes.size() - 1; i++) {
      NodeId from = pathNodes.get(i);
      NodeId to = pathNodes.get(i + 1);
      Optional<RailEdge> edgeOpt = findEdge(from, to);
      if (edgeOpt.isEmpty()) {
        // 任一相邻节点不可达时，直接失败并返回空列表。
        return List.of();
      }
      edges.add(edgeOpt.get());
    }
    return edges;
  }

  private List<NodeId> expandPathNodes(List<NodeId> nodes) {
    if (nodes == null || nodes.size() < 2) {
      return List.of();
    }
    List<NodeId> expanded = new ArrayList<>();
    expanded.add(nodes.get(0));
    for (int i = 0; i < nodes.size() - 1; i++) {
      NodeId from = nodes.get(i);
      NodeId to = nodes.get(i + 1);
      if (from == null || to == null) {
        return List.of();
      }
      Optional<RailEdge> directEdge = findEdge(from, to);
      if (directEdge.isPresent()) {
        expanded.add(to);
        continue;
      }
      Optional<RailGraphPath> pathOpt =
          pathFinder.shortestPath(graph, from, to, RailGraphPathFinder.Options.shortestDistance());
      if (pathOpt.isEmpty()) {
        return List.of();
      }
      List<NodeId> segment = pathOpt.get().nodes();
      if (segment.size() < 2) {
        return List.of();
      }
      for (int j = 1; j < segment.size(); j++) {
        expanded.add(segment.get(j));
      }
    }
    return List.copyOf(expanded);
  }

  private Optional<RailEdge> findEdge(NodeId from, NodeId to) {
    for (RailEdge edge : graph.edgesFrom(from)) {
      if (edge.from().equals(from) && edge.to().equals(to)) {
        return Optional.of(edge);
      }
      if (edge.from().equals(to) && edge.to().equals(from)) {
        return Optional.of(edge);
      }
    }
    // 无向图：只要两端点相连即可视为可达。
    return Optional.empty();
  }
}
