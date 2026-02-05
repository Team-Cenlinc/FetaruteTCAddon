package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphCorridorInfo;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphCorridorSupport;
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
 * 同向跟驰最小空闲边数由 {@code minClearEdges} 与 lookahead 取最大值控制。 尾部保护通过 {@code rearGuardEdges} 保留当前节点向后 N
 * 段边，避免长编组尾部被追尾。
 *
 * <p>同时会记录冲突区 entryOrder（首次进入冲突的边序号），用于冲突区放行与死锁解除。
 */
public final class OccupancyRequestBuilder {

  private final RailGraph graph;
  private final int switcherZoneEdges;
  private final int rearGuardEdges;
  private final int effectiveLookaheadEdges;
  private final RailGraphPathFinder pathFinder = new RailGraphPathFinder();
  private static final String SWITCHER_CONFLICT_PREFIX = "switcher:";
  private final java.util.function.Consumer<String> debugLogger;

  public OccupancyRequestBuilder(
      RailGraph graph,
      int lookaheadEdges,
      int minClearEdges,
      int rearGuardEdges,
      int switcherZoneEdges) {
    this(graph, lookaheadEdges, minClearEdges, rearGuardEdges, switcherZoneEdges, msg -> {});
  }

  public OccupancyRequestBuilder(
      RailGraph graph,
      int lookaheadEdges,
      int minClearEdges,
      int rearGuardEdges,
      int switcherZoneEdges,
      java.util.function.Consumer<String> debugLogger) {
    this.graph = Objects.requireNonNull(graph, "graph");
    this.debugLogger = debugLogger != null ? debugLogger : msg -> {};
    if (lookaheadEdges <= 0) {
      throw new IllegalArgumentException("lookaheadEdges 必须大于 0");
    }
    if (minClearEdges < 0) {
      throw new IllegalArgumentException("minClearEdges 必须为非负数");
    }
    if (rearGuardEdges < 0) {
      throw new IllegalArgumentException("rearGuardEdges 必须为非负数");
    }
    if (switcherZoneEdges < 0) {
      throw new IllegalArgumentException("switcherZoneEdges 必须为非负数");
    }
    this.switcherZoneEdges = switcherZoneEdges;
    this.rearGuardEdges = rearGuardEdges;
    this.effectiveLookaheadEdges = Math.max(lookaheadEdges, minClearEdges);
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
    // 默认优先级 0 (普通)
    return buildFromNodes(
        state.trainName(), Optional.of(route.id()), nodes, currentIndex, requestTime, 0);
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
      Instant now,
      int priority) {
    return buildContextFromNodes(trainName, routeId, nodes, currentIndex, now, priority)
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
      Instant now,
      int priority) {
    Objects.requireNonNull(trainName, "trainName");
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(nodes, "nodes");
    Instant requestTime = now != null ? now : Instant.now();
    if (nodes.isEmpty()) {
      debugLogger.accept("构建请求失败: nodes 列表为空");
      return Optional.empty();
    }
    if (currentIndex < 0 || currentIndex >= nodes.size() - 1) {
      debugLogger.accept(
          "构建请求失败: currentIndex 超出范围 index=" + currentIndex + " size=" + nodes.size());
      return Optional.empty();
    }
    int maxIndex = Math.min(nodes.size() - 1, currentIndex + effectiveLookaheadEdges);
    List<NodeId> pathNodes = new ArrayList<>();
    for (int i = currentIndex; i <= maxIndex; i++) {
      pathNodes.add(nodes.get(i));
    }
    List<NodeId> expandedNodes = expandPathNodes(pathNodes);
    if (expandedNodes.isEmpty()) {
      debugLogger.accept("构建请求失败: expandPathNodes 返回空 (路径不连通?) nodes=" + pathNodes);
      return Optional.empty();
    }
    List<RailEdge> edges = resolveEdges(expandedNodes);
    if (edges.isEmpty()) {
      debugLogger.accept("构建请求失败: resolveEdges 返回空 (边未找到?) nodes=" + expandedNodes);
      return Optional.empty();
    }
    List<NodeId> rearNodes = resolveRearGuardNodes(nodes, currentIndex);
    List<NodeId> rearExpanded = expandRearGuardNodes(rearNodes);
    List<RailEdge> rearEdges = resolveRearGuardEdges(rearExpanded);
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
    appendRearGuardResources(resources, rearExpanded, rearEdges);
    Map<String, CorridorDirection> corridorDirections = resolveCorridorDirections(expandedNodes);
    Map<String, Integer> conflictEntryOrders = resolveConflictEntryOrders(edges);
    OccupancyRequest request =
        new OccupancyRequest(
            trainName,
            routeId,
            requestTime,
            List.copyOf(resources),
            corridorDirections,
            conflictEntryOrders,
            priority);
    return Optional.of(new OccupancyRequestContext(request, expandedNodes, edges));
  }

  /**
   * Depot 出车专用：在起步段遇到道岔时，额外把该道岔周边的多分支 edge 也纳入请求，用于 spawn 前的“多方向 lookover”。
   *
   * <p>实现策略：仅追加 EDGE 资源（不追加走廊冲突 key），避免因缺少方向信息导致过度对向锁闭。
   */
  public OccupancyRequest applyDepotLookover(OccupancyRequestContext context) {
    Objects.requireNonNull(context, "context");
    OccupancyRequest base = context.request();
    if (switcherZoneEdges <= 0) {
      return base;
    }

    List<NodeId> pathNodes = context.pathNodes();
    if (pathNodes.size() < 2) {
      return base;
    }

    int maxIndex = Math.min(pathNodes.size() - 1, switcherZoneEdges);
    Set<NodeId> switchers = new LinkedHashSet<>();
    for (int i = 0; i <= maxIndex; i++) {
      NodeId nodeId = pathNodes.get(i);
      if (nodeId == null) {
        continue;
      }
      graph
          .findNode(nodeId)
          .filter(node -> node.type() == NodeType.SWITCHER)
          .ifPresent(node -> switchers.add(node.id()));
    }
    if (switchers.isEmpty()) {
      return base;
    }

    Set<OccupancyResource> merged = new LinkedHashSet<>(base.resourceList());
    Map<String, CorridorDirection> directions = new LinkedHashMap<>(base.corridorDirections());
    boolean updated = false;
    for (NodeId switcher : switchers) {
      for (RailEdge edge : collectEdgesWithin(switcher, switcherZoneEdges)) {
        OccupancyResource edgeResource = OccupancyResource.forEdge(edge.id());
        if (merged.contains(edgeResource)) {
          continue;
        }
        if (tryAddDirectionalLookoverConflict(
            merged, directions, context.pathNodes(), switcher, edge)) {
          updated = true;
          continue;
        }
        updated |= merged.add(edgeResource);
      }
    }

    if (!updated) {
      return base;
    }
    return new OccupancyRequest(
        base.trainName(),
        base.routeId(),
        base.now(),
        List.copyOf(merged),
        Map.copyOf(directions),
        base.conflictEntryOrders(),
        base.priority());
  }

  private List<NodeId> resolveRearGuardNodes(List<NodeId> nodes, int currentIndex) {
    if (rearGuardEdges <= 0) {
      return List.of();
    }
    if (nodes == null || nodes.size() < 2) {
      return List.of();
    }
    if (currentIndex <= 0 || currentIndex >= nodes.size()) {
      return List.of();
    }
    int startIndex = Math.max(0, currentIndex - rearGuardEdges);
    if (startIndex >= currentIndex) {
      return List.of();
    }
    List<NodeId> rear = new ArrayList<>();
    for (int i = startIndex; i <= currentIndex; i++) {
      rear.add(nodes.get(i));
    }
    return List.copyOf(rear);
  }

  private List<NodeId> expandRearGuardNodes(List<NodeId> nodes) {
    if (nodes == null || nodes.size() < 2) {
      return List.of();
    }
    List<NodeId> expanded = expandPathNodes(nodes);
    if (expanded.isEmpty()) {
      debugLogger.accept("rear-guard 解析失败: expandPathNodes 返回空 nodes=" + nodes);
    }
    return expanded;
  }

  private List<RailEdge> resolveRearGuardEdges(List<NodeId> expandedNodes) {
    if (expandedNodes == null || expandedNodes.size() < 2) {
      return List.of();
    }
    List<RailEdge> edges = resolveEdges(expandedNodes);
    if (edges.isEmpty()) {
      debugLogger.accept("rear-guard 解析失败: resolveEdges 返回空 nodes=" + expandedNodes);
    }
    return edges;
  }

  private void appendRearGuardResources(
      Set<OccupancyResource> resources, List<NodeId> nodes, List<RailEdge> edges) {
    if (resources == null) {
      return;
    }
    if (nodes != null) {
      for (NodeId node : nodes) {
        if (node == null) {
          continue;
        }
        resources.add(OccupancyResource.forNode(node));
      }
    }
    if (edges != null) {
      for (RailEdge edge : edges) {
        if (edge == null) {
          continue;
        }
        resources.add(OccupancyResource.forEdge(edge.id()));
      }
    }
  }

  private boolean tryAddDirectionalLookoverConflict(
      Set<OccupancyResource> resources,
      Map<String, CorridorDirection> directions,
      List<NodeId> pathNodes,
      NodeId switcher,
      RailEdge edge) {
    if (resources == null || directions == null || switcher == null || edge == null) {
      return false;
    }
    if (!(graph instanceof RailGraphCorridorSupport support)) {
      return false;
    }
    NodeId from;
    NodeId to;
    if (switcher.equals(edge.from())) {
      from = edge.from();
      to = edge.to();
    } else if (switcher.equals(edge.to())) {
      from = edge.to();
      to = edge.from();
    } else {
      return false;
    }
    Optional<String> conflictKeyOpt = support.conflictKeyForEdge(edge.id());
    if (conflictKeyOpt.isEmpty()) {
      return false;
    }
    Optional<RailGraphCorridorInfo> infoOpt = support.corridorInfoForEdge(edge.id());
    if (infoOpt.isEmpty() || !infoOpt.get().directional()) {
      return false;
    }
    String key = conflictKeyOpt.get();
    Optional<CorridorDirection> directionOpt =
        resolveCorridorDirection(infoOpt.get(), pathNodes, from, to);
    if (directionOpt.isEmpty()) {
      return false;
    }
    if (!directions.containsKey(key)) {
      directions.put(key, directionOpt.get());
    }
    resources.add(OccupancyResource.forConflict(key));
    return true;
  }

  private Set<RailEdge> collectEdgesWithin(NodeId start, int maxEdges) {
    if (start == null || maxEdges <= 0) {
      return Set.of();
    }

    record NodeDepth(NodeId node, int depth) {}

    Set<RailEdge> collected = new LinkedHashSet<>();
    Set<EdgeId> visitedEdges = new HashSet<>();
    Map<NodeId, Integer> bestDepth = new HashMap<>();
    Deque<NodeDepth> queue = new ArrayDeque<>();
    queue.addLast(new NodeDepth(start, 0));
    bestDepth.put(start, 0);

    while (!queue.isEmpty()) {
      NodeDepth current = queue.removeFirst();
      if (current.depth() >= maxEdges) {
        continue;
      }
      for (RailEdge edge : graph.edgesFrom(current.node())) {
        if (edge == null) {
          continue;
        }
        EdgeId edgeId = EdgeId.undirected(edge.from(), edge.to());
        if (!visitedEdges.add(edgeId)) {
          continue;
        }
        collected.add(edge);
        NodeId next = current.node().equals(edge.from()) ? edge.to() : edge.from();
        if (next == null) {
          continue;
        }
        int nextDepth = current.depth() + 1;
        Integer known = bestDepth.get(next);
        if (known != null && known <= nextDepth) {
          continue;
        }
        bestDepth.put(next, nextDepth);
        queue.addLast(new NodeDepth(next, nextDepth));
      }
    }

    return Set.copyOf(collected);
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
        debugLogger.accept("resolveEdges 失败: 边不可达 from=" + from.value() + " to=" + to.value());
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
        debugLogger.accept("expandPathNodes 失败: 最短路未找到 from=" + from.value() + " to=" + to.value());
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

  private Map<String, CorridorDirection> resolveCorridorDirections(List<NodeId> pathNodes) {
    if (!(graph instanceof RailGraphCorridorSupport support)) {
      return Map.of();
    }
    if (pathNodes == null || pathNodes.size() < 2) {
      return Map.of();
    }
    Map<String, CorridorDirection> directions = new LinkedHashMap<>();
    for (int i = 0; i < pathNodes.size() - 1; i++) {
      NodeId from = pathNodes.get(i);
      NodeId to = pathNodes.get(i + 1);
      if (from == null || to == null) {
        continue;
      }
      EdgeId edgeId = EdgeId.undirected(from, to);
      support
          .corridorInfoForEdge(edgeId)
          .filter(RailGraphCorridorInfo::directional)
          .ifPresent(
              info -> {
                if (directions.containsKey(info.key())) {
                  return;
                }
                resolveCorridorDirection(info, pathNodes, from, to)
                    .ifPresent(direction -> directions.put(info.key(), direction));
              });
    }
    return Map.copyOf(directions);
  }

  private Map<String, Integer> resolveConflictEntryOrders(List<RailEdge> edges) {
    if (edges == null || edges.isEmpty()) {
      return Map.of();
    }
    Map<String, Integer> orders = new LinkedHashMap<>();
    for (int i = 0; i < edges.size(); i++) {
      RailEdge edge = edges.get(i);
      if (edge == null) {
        continue;
      }
      for (OccupancyResource resource : OccupancyResourceResolver.resourcesForEdge(graph, edge)) {
        if (resource == null || resource.kind() != ResourceKind.CONFLICT) {
          continue;
        }
        orders.putIfAbsent(resource.key(), i);
      }
    }
    return Map.copyOf(orders);
  }

  private Optional<CorridorDirection> resolveCorridorDirection(
      RailGraphCorridorInfo info, List<NodeId> pathNodes, NodeId from, NodeId to) {
    CorridorDirection byCorridor = resolveDirectionByCorridorNodes(info, from, to);
    if (byCorridor != CorridorDirection.UNKNOWN) {
      return Optional.of(byCorridor);
    }
    int leftIndex = indexOfNode(pathNodes, info.left());
    int rightIndex = indexOfNode(pathNodes, info.right());
    if (leftIndex >= 0 && rightIndex >= 0 && leftIndex != rightIndex) {
      return Optional.of(
          leftIndex < rightIndex ? CorridorDirection.A_TO_B : CorridorDirection.B_TO_A);
    }
    CorridorDirection byDistance = resolveDirectionByDistance(from, to, info.left(), info.right());
    return byDistance == CorridorDirection.UNKNOWN ? Optional.empty() : Optional.of(byDistance);
  }

  private CorridorDirection resolveDirectionByCorridorNodes(
      RailGraphCorridorInfo info, NodeId from, NodeId to) {
    if (info == null || from == null || to == null) {
      return CorridorDirection.UNKNOWN;
    }
    List<NodeId> corridorNodes = info.nodes();
    int fromIndex = indexOfNode(corridorNodes, from);
    int toIndex = indexOfNode(corridorNodes, to);
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) {
      return CorridorDirection.UNKNOWN;
    }
    return fromIndex < toIndex ? CorridorDirection.A_TO_B : CorridorDirection.B_TO_A;
  }

  private CorridorDirection resolveDirectionByDistance(
      NodeId from, NodeId to, NodeId left, NodeId right) {
    OptionalLong fromLeft = shortestDistance(from, left);
    OptionalLong toLeft = shortestDistance(to, left);
    OptionalLong fromRight = shortestDistance(from, right);
    OptionalLong toRight = shortestDistance(to, right);
    if (fromLeft.isEmpty() || toLeft.isEmpty() || fromRight.isEmpty() || toRight.isEmpty()) {
      return CorridorDirection.UNKNOWN;
    }
    boolean towardLeft = toLeft.getAsLong() < fromLeft.getAsLong();
    boolean towardRight = toRight.getAsLong() < fromRight.getAsLong();
    if (towardRight && !towardLeft) {
      return CorridorDirection.A_TO_B;
    }
    if (towardLeft && !towardRight) {
      return CorridorDirection.B_TO_A;
    }
    return CorridorDirection.UNKNOWN;
  }

  private OptionalLong shortestDistance(NodeId from, NodeId to) {
    if (from == null || to == null) {
      return OptionalLong.empty();
    }
    return pathFinder
        .shortestPath(graph, from, to, RailGraphPathFinder.Options.shortestDistance())
        .map(path -> OptionalLong.of(path.totalLengthBlocks()))
        .orElse(OptionalLong.empty());
  }

  private int indexOfNode(List<NodeId> nodes, NodeId target) {
    if (nodes == null || target == null) {
      return -1;
    }
    for (int i = 0; i < nodes.size(); i++) {
      if (target.equals(nodes.get(i))) {
        return i;
      }
    }
    return -1;
  }
}
