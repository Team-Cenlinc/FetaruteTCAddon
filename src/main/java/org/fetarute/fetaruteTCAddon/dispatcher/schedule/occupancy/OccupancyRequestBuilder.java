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
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainRuntimeState;

/**
 * 运行时占用请求构建器：把“列车状态 + 线路定义 + 图”转换成 OccupancyRequest。
 *
 * <p>默认行为只做 lookahead 的边占用。
 */
public final class OccupancyRequestBuilder {

  private final RailGraph graph;
  private final int lookaheadEdges;

  public OccupancyRequestBuilder(RailGraph graph, int lookaheadEdges) {
    this.graph = Objects.requireNonNull(graph, "graph");
    if (lookaheadEdges <= 0) {
      throw new IllegalArgumentException("lookaheadEdges 必须大于 0");
    }
    this.lookaheadEdges = lookaheadEdges;
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
   * <p>该方法用于未来从其他来源（非 RouteDefinition）构建 lookahead 请求。
   *
   * <p>currentIndex 指向“已抵达节点”的索引，资源从 currentIndex -> currentIndex+1 开始。
   */
  public Optional<OccupancyRequest> buildFromNodes(
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
    List<RailEdge> edges = resolveEdges(pathNodes);
    if (edges.isEmpty()) {
      return Optional.empty();
    }
    Set<OccupancyResource> resources = new LinkedHashSet<>();
    for (NodeId node : pathNodes) {
      if (node == null) {
        continue;
      }
      resources.add(OccupancyResource.forNode(node));
    }
    for (RailEdge edge : edges) {
      resources.addAll(OccupancyResourceResolver.resourcesForEdge(graph, edge));
    }
    return Optional.of(
        new OccupancyRequest(trainName, routeId, requestTime, List.copyOf(resources)));
  }

  private List<RailEdge> resolveEdges(List<NodeId> pathNodes) {
    List<RailEdge> edges = new ArrayList<>();
    for (int i = 0; i < pathNodes.size() - 1; i++) {
      NodeId from = pathNodes.get(i);
      NodeId to = pathNodes.get(i + 1);
      Optional<RailEdge> edgeOpt = findEdge(from, to);
      if (edgeOpt.isEmpty()) {
        return List.of();
      }
      edges.add(edgeOpt.get());
    }
    return edges;
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
    return Optional.empty();
  }
}
