package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
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
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphSectionSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SingleLineSectionInfo;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
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
 *
 * <p>这个构建器只负责“把图上的可见状态翻译成请求”，不直接改写占用管理器状态。也就是说，真正的放行/阻塞/队列公平性仍由 {@link SimpleOccupancyManager}
 * 统一裁决，构建器只保证请求携带足够且稳定的上下文。
 */
public final class OccupancyRequestBuilder {

  private static final int DEPOT_LOOKOVER_MIN_EDGES = 6;
  private static final int DEPOT_LOOKOVER_EDGE_MULTIPLIER = 3;
  private static final int DEPOT_LOOKOVER_MAX_EDGES = 24;

  private final RailGraph graph;
  private final int switcherZoneEdges;
  private final int rearGuardEdges;
  private final int effectiveLookaheadEdges;
  private final long minLookaheadDistanceBlocks;
  private final int maxLookaheadEdges;
  private final RailGraphPathFinder pathFinder = new RailGraphPathFinder();
  private final SemanticCorridorDirectionResolver semanticDirectionResolver;
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
    this(
        graph,
        lookaheadEdges,
        minClearEdges,
        rearGuardEdges,
        switcherZoneEdges,
        0L,
        0,
        debugLogger);
  }

  public OccupancyRequestBuilder(
      RailGraph graph,
      int lookaheadEdges,
      int minClearEdges,
      int rearGuardEdges,
      int switcherZoneEdges,
      long minLookaheadDistanceBlocks,
      int maxLookaheadEdges,
      java.util.function.Consumer<String> debugLogger) {
    this.graph = Objects.requireNonNull(graph, "graph");
    this.semanticDirectionResolver = new SemanticCorridorDirectionResolver(this.graph);
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
    if (minLookaheadDistanceBlocks < 0L) {
      throw new IllegalArgumentException("minLookaheadDistanceBlocks 必须为非负数");
    }
    this.switcherZoneEdges = switcherZoneEdges;
    this.rearGuardEdges = rearGuardEdges;
    this.effectiveLookaheadEdges = Math.max(lookaheadEdges, minClearEdges);
    this.minLookaheadDistanceBlocks = minLookaheadDistanceBlocks;
    this.maxLookaheadEdges =
        maxLookaheadEdges <= 0
            ? this.effectiveLookaheadEdges
            : Math.max(this.effectiveLookaheadEdges, maxLookaheadEdges);
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
        state.trainName(),
        Optional.of(route.id()),
        nodes,
        currentIndex,
        requestTime,
        0,
        AuthorizationPurpose.RUNTIME_MOVE);
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
    return buildFromNodes(
        trainName, routeId, nodes, currentIndex, now, priority, AuthorizationPurpose.RUNTIME_MOVE);
  }

  /**
   * 从给定节点列表构建指定来源的占用请求。
   *
   * <p>调用方应按实际授权来源传入 purpose；无法区分时只能传 {@link AuthorizationPurpose#RUNTIME_MOVE}。
   */
  public Optional<OccupancyRequest> buildFromNodes(
      String trainName,
      Optional<RouteId> routeId,
      List<NodeId> nodes,
      int currentIndex,
      Instant now,
      int priority,
      AuthorizationPurpose purpose) {
    return buildContextFromNodes(trainName, routeId, nodes, currentIndex, now, priority, purpose)
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
    return buildContextFromNodes(
        trainName, routeId, nodes, currentIndex, now, priority, AuthorizationPurpose.RUNTIME_MOVE);
  }

  /**
   * 构建指定来源的占用请求并返回路径上下文。
   *
   * <p>{@link AuthorizationPurpose#CONFLICT_CLEARING} 不应由普通入口直接传入；它必须由运行时在证明 inside/exit 后再附加
   * release hint。
   */
  public Optional<OccupancyRequestContext> buildContextFromNodes(
      String trainName,
      Optional<RouteId> routeId,
      List<NodeId> nodes,
      int currentIndex,
      Instant now,
      int priority,
      AuthorizationPurpose purpose) {
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
    // 先展开当前节点到 Route 末尾的完整路径，再按实际边数截断
    List<NodeId> pathNodes = new ArrayList<>();
    for (int i = currentIndex; i < nodes.size(); i++) {
      pathNodes.add(nodes.get(i));
    }
    List<NodeId> fullExpanded = expandPathNodes(pathNodes);
    if (fullExpanded.isEmpty()) {
      debugLogger.accept("构建请求失败: expandPathNodes 返回空 (路径不连通?) nodes=" + pathNodes);
      return Optional.empty();
    }
    fullExpanded =
        splitAtRepeatedOppositeTraversal(
            trainName, routeId.map(RouteId::value).orElse("-"), purpose, fullExpanded);
    List<RailEdge> fullEdges = resolveEdges(fullExpanded);
    if (fullEdges.isEmpty()) {
      debugLogger.accept("构建请求失败: full resolveEdges 返回空 (边未找到?) nodes=" + fullExpanded);
      return Optional.empty();
    }
    // 按实际图边截断：至少覆盖 edge 下限和距离下限，同时受 maxLookaheadEdges 硬上限约束。
    List<NodeId> expandedNodes = truncateLookahead(fullExpanded, fullEdges);
    List<RailEdge> edges = resolveEdges(expandedNodes);
    if (edges.isEmpty()) {
      debugLogger.accept("构建请求失败: resolveEdges 返回空 (边未找到?) nodes=" + expandedNodes);
      return Optional.empty();
    }
    List<NodeId> rearNodes = resolveRearGuardNodes(nodes, currentIndex);
    List<NodeId> rearExpanded = expandRearGuardNodes(rearNodes);
    List<RailEdge> rearEdges = resolveRearGuardEdges(rearExpanded);
    Set<OccupancyResource> resources = new LinkedHashSet<>();
    Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>();
    for (NodeId node : expandedNodes) {
      if (node == null) {
        continue;
      }
      addResource(
          resources, intents, OccupancyResource.forNode(node), ResourceIntent.MOVEMENT_REQUIRED);
    }
    for (RailEdge edge : edges) {
      addResources(
          resources,
          intents,
          OccupancyResourceResolver.resourcesForEdge(graph, edge),
          ResourceIntent.MOVEMENT_REQUIRED);
    }
    applySwitcherZoneConflicts(resources, intents, expandedNodes);
    appendRearGuardResources(resources, intents, rearExpanded, rearEdges);
    CorridorDirectionResolution windowDirections =
        resolveCorridorDirectionResolution(expandedNodes);
    CorridorDirectionResolution planDirections = resolveCorridorDirectionResolution(fullExpanded);
    Map<String, CorridorDirection> corridorDirections =
        requestCorridorDirections(resources, windowDirections, planDirections);
    Map<String, CorridorDirection> planCorridorDirections = planDirections.directions();
    Map<String, Integer> conflictEntryOrders = resolveConflictEntryOrders(edges);
    DirectedTraversalContext directedContext =
        buildDirectedContext(
            trainName,
            routeId,
            currentIndex,
            Optional.ofNullable(nodes.get(currentIndex)),
            fullExpanded,
            fullEdges,
            planCorridorDirections,
            resources,
            purpose.name());
    OccupancyRequest request =
        new OccupancyRequest(
            trainName,
            routeId,
            requestTime,
            List.copyOf(resources),
            corridorDirections,
            conflictEntryOrders,
            priority,
            purpose,
            Map.of(),
            intents,
            Optional.of(directedContext));
    return Optional.of(
        new OccupancyRequestContext(request, expandedNodes, edges, Optional.of(directedContext)));
  }

  /**
   * 构建“尾部保护”占用请求：仅保留当前节点与其后方 N 段边资源。
   *
   * <p>用于停站期间，避免后车过早释放导致互卡；不会额外占用前方 lookahead 资源。
   */
  public OccupancyRequest buildRearGuardRequestFromNodes(
      String trainName,
      Optional<RouteId> routeId,
      List<NodeId> nodes,
      int currentIndex,
      Instant now,
      int priority) {
    return buildRearGuardRequestFromNodes(
        trainName, routeId, nodes, currentIndex, now, priority, AuthorizationPurpose.RUNTIME_MOVE);
  }

  /** 构建指定来源的尾部保护请求；未提供规范计划时只保留物理资源，不自行建立 single 方向。 */
  public OccupancyRequest buildRearGuardRequestFromNodes(
      String trainName,
      Optional<RouteId> routeId,
      List<NodeId> nodes,
      int currentIndex,
      Instant now,
      int priority,
      AuthorizationPurpose purpose) {
    return buildRearGuardRequestFromNodes(
        trainName, routeId, nodes, currentIndex, now, priority, purpose, Optional.empty());
  }

  /**
   * 从本周期规范行车计划派生尾部保护请求。
   *
   * <p>资源窗口仍只覆盖当前节点后方的 configured rear-guard edges；single 方向与有向上下文完全继承 {@code movementPlan}。
   *
   * @param trainName 列车名
   * @param routeId 线路 route id
   * @param nodes 当前有效 route 节点
   * @param currentIndex 当前 route index
   * @param now 请求时间
   * @param priority 队列优先级
   * @param purpose 授权来源
   * @param movementPlan 本周期规范行车计划
   * @return 与规范计划方向一致的尾部保护请求
   */
  public OccupancyRequest buildRearGuardRequestFromPlan(
      String trainName,
      Optional<RouteId> routeId,
      List<NodeId> nodes,
      int currentIndex,
      Instant now,
      int priority,
      AuthorizationPurpose purpose,
      MovementPlanSnapshot movementPlan) {
    return buildRearGuardRequestFromNodes(
        trainName,
        routeId,
        nodes,
        currentIndex,
        now,
        priority,
        purpose,
        Optional.of(Objects.requireNonNull(movementPlan, "movementPlan")));
  }

  private OccupancyRequest buildRearGuardRequestFromNodes(
      String trainName,
      Optional<RouteId> routeId,
      List<NodeId> nodes,
      int currentIndex,
      Instant now,
      int priority,
      AuthorizationPurpose purpose,
      Optional<MovementPlanSnapshot> movementPlan) {
    Objects.requireNonNull(trainName, "trainName");
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(nodes, "nodes");
    Instant requestTime = now != null ? now : Instant.now();
    if (nodes.isEmpty()) {
      throw new IllegalArgumentException("nodes 列表为空");
    }
    if (currentIndex < 0 || currentIndex >= nodes.size()) {
      throw new IllegalArgumentException(
          "currentIndex 超出范围 index=" + currentIndex + " size=" + nodes.size());
    }
    Set<OccupancyResource> resources = new LinkedHashSet<>();
    Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>();
    NodeId currentNode = nodes.get(currentIndex);
    if (currentNode != null) {
      addResource(
          resources, intents, OccupancyResource.forNode(currentNode), ResourceIntent.HOLD_ONLY);
    }
    List<NodeId> rearNodes = resolveRearGuardNodes(nodes, currentIndex);
    List<NodeId> rearExpanded = expandRearGuardNodes(rearNodes);
    List<RailEdge> rearEdges = resolveRearGuardEdges(rearExpanded);
    appendRearGuardResources(resources, intents, rearExpanded, rearEdges);
    Map<String, CorridorDirection> corridorDirections =
        resolveProtectiveCorridorDirections(resources, movementPlan);
    Map<String, Integer> conflictEntryOrders = resolveConflictEntryOrders(rearEdges);
    DirectedTraversalContext directedContext =
        buildProtectiveDirectedContext(
            trainName,
            routeId,
            currentIndex,
            currentNode,
            rearExpanded,
            rearEdges,
            corridorDirections,
            resources,
            purpose.name(),
            movementPlan);
    return new OccupancyRequest(
        trainName,
        routeId,
        requestTime,
        List.copyOf(resources),
        corridorDirections,
        conflictEntryOrders,
        priority,
        purpose,
        Map.of(),
        intents,
        Optional.of(directedContext));
  }

  /**
   * 构建“当前位置保持”请求。
   *
   * <p>该请求用于硬 STOP/blocked 等 hold-only 场景：即使不再向前放行，也必须继续持有列车当前所在边派生出的 {@code
   * CONFLICT:single}，否则对向列车会在窗口滑动后看不到走廊内占用。该兼容入口没有规范计划，因此不会从局部路径建立 single 方向。
   */
  public OccupancyRequest buildHoldPositionRequest(
      String trainName,
      Optional<RouteId> routeId,
      NodeId currentNode,
      Optional<NodeId> targetNode,
      List<NodeId> routeNodes,
      int currentIndex,
      Instant now,
      int priority,
      AuthorizationPurpose purpose) {
    return buildHoldPositionRequest(
        trainName,
        routeId,
        currentNode,
        targetNode,
        routeNodes,
        currentIndex,
        now,
        priority,
        purpose,
        Optional.empty());
  }

  /**
   * 从本周期规范行车计划派生当前位置等待请求。
   *
   * <p>请求只保留当前位置、当前边与尾部保护资源；single 方向、完整展开路径与道岔签名由 {@code movementPlan} 提供。
   *
   * @param trainName 列车名
   * @param routeId 线路 route id
   * @param currentNode 当前图节点
   * @param targetNode 下一目标节点
   * @param routeNodes 当前有效 route 节点
   * @param currentIndex 当前 route index
   * @param now 请求时间
   * @param priority 队列优先级
   * @param purpose 授权来源
   * @param movementPlan 本周期规范行车计划
   * @return 与规范计划方向一致的等待请求
   */
  public OccupancyRequest buildHoldPositionRequestFromPlan(
      String trainName,
      Optional<RouteId> routeId,
      NodeId currentNode,
      Optional<NodeId> targetNode,
      List<NodeId> routeNodes,
      int currentIndex,
      Instant now,
      int priority,
      AuthorizationPurpose purpose,
      MovementPlanSnapshot movementPlan) {
    return buildHoldPositionRequest(
        trainName,
        routeId,
        currentNode,
        targetNode,
        routeNodes,
        currentIndex,
        now,
        priority,
        purpose,
        Optional.of(Objects.requireNonNull(movementPlan, "movementPlan")));
  }

  private OccupancyRequest buildHoldPositionRequest(
      String trainName,
      Optional<RouteId> routeId,
      NodeId currentNode,
      Optional<NodeId> targetNode,
      List<NodeId> routeNodes,
      int currentIndex,
      Instant now,
      int priority,
      AuthorizationPurpose purpose,
      Optional<MovementPlanSnapshot> movementPlan) {
    Objects.requireNonNull(trainName, "trainName");
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(currentNode, "currentNode");
    Instant requestTime = now != null ? now : Instant.now();
    Set<OccupancyResource> resources = new LinkedHashSet<>();
    Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>();
    addResource(
        resources, intents, OccupancyResource.forNode(currentNode), ResourceIntent.HOLD_ONLY);

    List<NodeId> directionNodes = List.of();
    List<RailEdge> directionEdges = List.of();
    if (targetNode != null && targetNode.isPresent()) {
      Optional<CurrentStep> stepOpt = resolveCurrentStep(currentNode, targetNode.get());
      if (stepOpt.isPresent()) {
        CurrentStep step = stepOpt.get();
        directionNodes = step.nodes();
        directionEdges = List.of(step.edge());
        addResources(
            resources,
            intents,
            OccupancyResourceResolver.resourcesForEdge(graph, step.edge()),
            ResourceIntent.HOLD_ONLY);
      }
    }

    if (routeNodes != null && currentIndex >= 0 && currentIndex < routeNodes.size()) {
      List<NodeId> rearNodes = resolveRearGuardNodes(routeNodes, currentIndex);
      List<NodeId> rearExpanded = expandRearGuardNodes(rearNodes);
      List<RailEdge> rearEdges = resolveRearGuardEdges(rearExpanded);
      appendRearGuardResources(resources, intents, rearExpanded, rearEdges);
    }

    Map<String, CorridorDirection> corridorDirections =
        resolveProtectiveCorridorDirections(resources, movementPlan);
    Map<String, Integer> conflictEntryOrders = resolveConflictEntryOrders(directionEdges);
    DirectedTraversalContext directedContext =
        buildProtectiveDirectedContext(
            trainName,
            routeId,
            currentIndex,
            currentNode,
            directionNodes,
            directionEdges,
            corridorDirections,
            resources,
            purpose.name(),
            movementPlan);
    return new OccupancyRequest(
        trainName,
        routeId,
        requestTime,
        List.copyOf(resources),
        corridorDirections,
        conflictEntryOrders,
        priority,
        purpose,
        Map.of(),
        intents,
        Optional.of(directedContext));
  }

  /**
   * 构建列车当前位置保护请求。
   *
   * <p>当前位置保护用于运行中持续占住“车头所在节点 + 朝目标方向的下一段图边”。当 {@code targetNode}
   * 不是相邻图节点时，会先走一次最短路，只取当前节点后的第一段边。这样长单线内的中间节点仍能保留正确的物理资源；由于该兼容入口没有规范计划，single 方向保持未知，调用方应优先使用
   * {@link #buildCurrentPositionRequestFromPlan}。
   *
   * @param trainName 列车名
   * @param routeId 线路 route id
   * @param currentNode 当前图节点
   * @param targetNode 目标图节点（通常是下一个 route waypoint）
   * @param now 请求时间
   * @param priority 队列优先级
   * @return 当前位置保护请求
   */
  public OccupancyRequest buildCurrentPositionRequest(
      String trainName,
      Optional<RouteId> routeId,
      NodeId currentNode,
      Optional<NodeId> targetNode,
      Instant now,
      int priority) {
    return buildCurrentPositionRequest(
        trainName,
        routeId,
        currentNode,
        targetNode,
        now,
        priority,
        AuthorizationPurpose.RUNTIME_MOVE);
  }

  /** 构建指定来源的当前位置保护请求。 */
  public OccupancyRequest buildCurrentPositionRequest(
      String trainName,
      Optional<RouteId> routeId,
      NodeId currentNode,
      Optional<NodeId> targetNode,
      Instant now,
      int priority,
      AuthorizationPurpose purpose) {
    return buildCurrentPositionRequest(
        trainName, routeId, currentNode, targetNode, now, priority, purpose, Optional.empty());
  }

  /**
   * 从本周期规范行车计划派生当前位置保护请求。
   *
   * <p>当前位置窗口只保留当前节点与第一条实际图边，但与规范计划重叠的 single conflict 必须继承其方向，不能因短路径缺少远端语义锚点而重新解释 traversal。
   *
   * @param trainName 列车名
   * @param routeId 线路 route id
   * @param currentNode 当前图节点
   * @param targetNode 下一目标节点
   * @param now 请求时间
   * @param priority 队列优先级
   * @param purpose 授权来源
   * @param movementPlan 本周期已确认的规范行车计划
   * @return 与规范计划方向一致的当前位置保护请求
   */
  public OccupancyRequest buildCurrentPositionRequestFromPlan(
      String trainName,
      Optional<RouteId> routeId,
      NodeId currentNode,
      Optional<NodeId> targetNode,
      Instant now,
      int priority,
      AuthorizationPurpose purpose,
      MovementPlanSnapshot movementPlan) {
    return buildCurrentPositionRequest(
        trainName,
        routeId,
        currentNode,
        targetNode,
        now,
        priority,
        purpose,
        Optional.of(Objects.requireNonNull(movementPlan, "movementPlan")));
  }

  private OccupancyRequest buildCurrentPositionRequest(
      String trainName,
      Optional<RouteId> routeId,
      NodeId currentNode,
      Optional<NodeId> targetNode,
      Instant now,
      int priority,
      AuthorizationPurpose purpose,
      Optional<MovementPlanSnapshot> movementPlan) {
    Objects.requireNonNull(trainName, "trainName");
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(currentNode, "currentNode");
    Instant requestTime = now != null ? now : Instant.now();
    Set<OccupancyResource> resources = new LinkedHashSet<>();
    Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>();
    addResource(
        resources,
        intents,
        OccupancyResource.forNode(currentNode),
        ResourceIntent.PROTECTIVE_RETAIN);

    List<NodeId> pathNodes = List.of(currentNode);
    List<RailEdge> edges = List.of();
    Optional<NodeId> target = targetNode != null ? targetNode : Optional.empty();
    if (target.isPresent() && !currentNode.equals(target.get())) {
      Optional<CurrentStep> stepOpt = resolveCurrentStep(currentNode, target.get());
      if (stepOpt.isPresent()) {
        CurrentStep step = stepOpt.get();
        addResources(
            resources,
            intents,
            OccupancyResourceResolver.resourcesForEdge(graph, step.edge()),
            ResourceIntent.PROTECTIVE_RETAIN);
        pathNodes = step.nodes();
        edges = List.of(step.edge());
      }
    }

    Map<String, CorridorDirection> corridorDirections =
        resolveProtectiveCorridorDirections(resources, movementPlan);
    Map<String, Integer> conflictEntryOrders = resolveConflictEntryOrders(edges);
    DirectedTraversalContext directedContext =
        buildProtectiveDirectedContext(
            trainName,
            routeId,
            -1,
            currentNode,
            pathNodes,
            edges,
            corridorDirections,
            resources,
            purpose.name(),
            movementPlan);
    return new OccupancyRequest(
        trainName,
        routeId,
        requestTime,
        List.copyOf(resources),
        corridorDirections,
        conflictEntryOrders,
        priority,
        purpose,
        Map.of(),
        intents,
        Optional.of(directedContext));
  }

  private DirectedTraversalContext buildProtectiveDirectedContext(
      String trainName,
      Optional<RouteId> routeId,
      int localCurrentIndex,
      NodeId currentNode,
      List<NodeId> localPathNodes,
      List<RailEdge> localEdges,
      Map<String, CorridorDirection> localDirections,
      Set<OccupancyResource> resources,
      String source,
      Optional<MovementPlanSnapshot> movementPlan) {
    if (movementPlan.isEmpty()) {
      return buildDirectedContext(
          trainName,
          routeId,
          localCurrentIndex,
          Optional.ofNullable(currentNode),
          localPathNodes,
          localEdges,
          localDirections,
          resources,
          source);
    }
    MovementPlanSnapshot plan = movementPlan.get();
    return new DirectedTraversalContext(
        trainName,
        plan.routeId(),
        plan.routeIndex(),
        Optional.ofNullable(currentNode),
        plan.lastPassedGraphNode(),
        plan.effectiveFromNode(),
        plan.effectiveToNode(),
        plan.expandedPathNodes(),
        plan.directedEdges(),
        plan.singleConflictDirections(),
        plan.switcherPathSignatures(),
        source,
        plan.occupancyVersion(),
        plan.progressVersion(),
        plan.requestId(),
        Optional.empty());
  }

  /**
   * 为保护资源筛出规范计划已经证明的 single 方向。
   *
   * <p>保护窗口自己的短路径只决定需要保留哪些物理资源，不能成为新的方向证据；未提供计划或计划方向未知时返回空映射。
   */
  private Map<String, CorridorDirection> resolveProtectiveCorridorDirections(
      Set<OccupancyResource> resources, Optional<MovementPlanSnapshot> movementPlan) {
    Set<String> requiredSingleConflicts = new LinkedHashSet<>();
    for (OccupancyResource resource : resources) {
      if (resource != null
          && resource.kind() == ResourceKind.CONFLICT
          && resource.key().startsWith("single:")) {
        requiredSingleConflicts.add(resource.key());
      }
    }
    if (requiredSingleConflicts.isEmpty()) {
      return Map.of();
    }

    Map<String, CorridorDirection> inherited = new LinkedHashMap<>();
    movementPlan.ifPresent(
        plan -> {
          for (String conflictKey : requiredSingleConflicts) {
            CorridorDirection direction = plan.singleConflictDirections().get(conflictKey);
            if (direction != null && direction != CorridorDirection.UNKNOWN) {
              inherited.put(conflictKey, direction);
            }
          }
        });
    return Map.copyOf(inherited);
  }

  /**
   * Depot 出车专用占用扩展。
   *
   * <p>默认以路径首节点作为 depot 起点（兼容旧行为）。
   *
   * @param context 占用上下文
   * @return 追加 lookover 后的请求；若无新增资源则返回原请求
   */
  public OccupancyRequest applyDepotLookover(OccupancyRequestContext context) {
    return applyDepotLookover(context, Optional.empty());
  }

  /**
   * Depot 出车专用占用扩展（支持显式指定 depot 起点）。
   *
   * <p>两层防护：
   *
   * <ol>
   *   <li>depot 周边 edge 预占用：显式指定 depot 时，按“加大后的 depot lookover 深度”扩展；
   *   <li>路径前端 switcher 分支：优先追加“方向冲突”资源，方向不可判定时回退 EDGE 资源。
   * </ol>
   *
   * <p>显式 depot 起点用于处理“route 首节点并非 depot”的出库场景，避免回库车已入道岔区时仍被放行 spawn。
   *
   * @param context 占用上下文
   * @param depotNodeOverride 显式 depot 节点（可空）
   * @return 追加 lookover 后的请求；若无新增资源则返回原请求
   */
  public OccupancyRequest applyDepotLookover(
      OccupancyRequestContext context, Optional<NodeId> depotNodeOverride) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(depotNodeOverride, "depotNodeOverride");
    OccupancyRequest base = context.request();
    List<NodeId> pathNodes = context.pathNodes();

    Set<OccupancyResource> merged = new LinkedHashSet<>(base.resourceList());
    Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>(base.resourceIntents());
    Map<String, CorridorDirection> directions = new LinkedHashMap<>(base.corridorDirections());
    Map<String, Integer> entryOrders = new LinkedHashMap<>(base.conflictEntryOrders());
    boolean updated = false;

    Optional<NodeId> depotAnchor = resolveDepotAnchor(pathNodes, depotNodeOverride);
    if (depotAnchor.isPresent()) {
      NodeId depotNode = depotAnchor.get();
      int depotLookoverEdges = resolveDepotLookoverEdges(depotNodeOverride.isPresent());
      for (LookoverEdge lookover : collectDirectedEdgesWithin(depotNode, depotLookoverEdges)) {
        updated |=
            addEdgeDerivedLookoverResources(
                merged, intents, directions, entryOrders, pathNodes, lookover);
      }
    }

    if (switcherZoneEdges > 0 && pathNodes.size() >= 2) {
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

      for (NodeId switcher : switchers) {
        for (LookoverEdge lookover : collectDirectedEdgesWithin(switcher, switcherZoneEdges)) {
          OccupancyResource edgeResource = OccupancyResource.forEdge(lookover.edge().id());
          if (merged.contains(edgeResource)) {
            continue;
          }
          if (tryAddDirectionalLookoverConflict(
              merged, intents, directions, entryOrders, pathNodes, lookover)) {
            updated = true;
            continue;
          }
          updated |= addResource(merged, intents, edgeResource, ResourceIntent.MOVEMENT_REQUIRED);
        }
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
        Map.copyOf(entryOrders),
        base.priority(),
        base.purpose(),
        base.conflictReleaseHints(),
        intents,
        base.directedContext());
  }

  private Optional<NodeId> resolveDepotAnchor(
      List<NodeId> pathNodes, Optional<NodeId> depotNodeOverride) {
    if (depotNodeOverride.isPresent()) {
      return depotNodeOverride;
    }
    if (pathNodes == null || pathNodes.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(pathNodes.get(0));
  }

  /**
   * 计算 Depot 出车 lookover 深度。
   *
   * <p>显式 depot 起点时，使用比常规 lookahead 更深的窗口覆盖车库道岔区，减少“回库车已进道岔但出库车仍被放行”的风险。
   */
  private int resolveDepotLookoverEdges(boolean explicitDepotAnchor) {
    if (!explicitDepotAnchor) {
      return 1;
    }
    int baseDepth =
        Math.max(effectiveLookaheadEdges, switcherZoneEdges * DEPOT_LOOKOVER_EDGE_MULTIPLIER);
    int expandedDepth = Math.max(DEPOT_LOOKOVER_MIN_EDGES, baseDepth);
    return Math.max(1, Math.min(DEPOT_LOOKOVER_MAX_EDGES, expandedDepth));
  }

  /** 返回 Depot lookover 深度，仅用于出车门控诊断输出。 */
  public int depotLookoverDepthForDiagnostics(boolean explicitDepotAnchor) {
    return resolveDepotLookoverEdges(explicitDepotAnchor);
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
      return List.of();
    }
    // 按实际边数截断：从末尾往前保留 rearGuardEdges 条边
    return truncateRearGuardToEdgeCount(expanded, rearGuardEdges);
  }

  /**
   * 从末尾往前截断尾部保护节点列表。
   *
   * <p>保留最后 {@code maxEdges} 条边对应的节点（即 maxEdges+1 个节点）。
   */
  private List<NodeId> truncateRearGuardToEdgeCount(List<NodeId> nodes, int maxEdges) {
    if (nodes == null || nodes.isEmpty()) {
      return List.of();
    }
    if (maxEdges <= 0) {
      return List.of(nodes.get(nodes.size() - 1));
    }
    // 边数 = 节点数 - 1，从末尾保留 maxEdges+1 个节点
    int keepNodes = Math.min(nodes.size(), maxEdges + 1);
    int startIndex = nodes.size() - keepNodes;
    return nodes.subList(startIndex, nodes.size());
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
      Set<OccupancyResource> resources,
      Map<OccupancyResource, ResourceIntent> intents,
      List<NodeId> nodes,
      List<RailEdge> edges) {
    if (resources == null) {
      return;
    }
    if (nodes != null) {
      for (NodeId node : nodes) {
        if (node == null) {
          continue;
        }
        addResource(
            resources, intents, OccupancyResource.forNode(node), ResourceIntent.PROTECTIVE_RETAIN);
      }
    }
    if (edges != null) {
      for (RailEdge edge : edges) {
        if (edge == null) {
          continue;
        }
        addResources(
            resources,
            intents,
            OccupancyResourceResolver.resourcesForEdge(graph, edge),
            ResourceIntent.PROTECTIVE_RETAIN);
      }
    }
  }

  private boolean addResource(
      Set<OccupancyResource> resources,
      Map<OccupancyResource, ResourceIntent> intents,
      OccupancyResource resource,
      ResourceIntent intent) {
    if (resources == null || resource == null) {
      return false;
    }
    boolean updated = resources.add(resource);
    if (intents != null) {
      intents.merge(
          resource, intent == null ? ResourceIntent.MOVEMENT_REQUIRED : intent, this::mergeIntent);
    }
    return updated;
  }

  private boolean addResources(
      Set<OccupancyResource> resources,
      Map<OccupancyResource, ResourceIntent> intents,
      List<OccupancyResource> additions,
      ResourceIntent intent) {
    boolean updated = false;
    if (additions == null) {
      return false;
    }
    for (OccupancyResource resource : additions) {
      updated |= addResource(resources, intents, resource, intent);
    }
    return updated;
  }

  private ResourceIntent mergeIntent(ResourceIntent existing, ResourceIntent incoming) {
    if (existing == ResourceIntent.MOVEMENT_REQUIRED
        || incoming == ResourceIntent.MOVEMENT_REQUIRED) {
      return ResourceIntent.MOVEMENT_REQUIRED;
    }
    if (existing == ResourceIntent.HOLD_ONLY || incoming == ResourceIntent.HOLD_ONLY) {
      return ResourceIntent.HOLD_ONLY;
    }
    if (existing == ResourceIntent.PROTECTIVE_RETAIN
        || incoming == ResourceIntent.PROTECTIVE_RETAIN) {
      return ResourceIntent.PROTECTIVE_RETAIN;
    }
    if (existing == ResourceIntent.QUEUE_POSITION || incoming == ResourceIntent.QUEUE_POSITION) {
      return ResourceIntent.QUEUE_POSITION;
    }
    return ResourceIntent.LOOKAHEAD_PREVIEW;
  }

  /**
   * 追加 Depot 周边边资源，并同步补齐冲突方向与队列入口序号。
   *
   * <p>Depot 出库门控必须把单线走廊的方向上下文带给占用层；否则 gate queue 只能看到“有 conflict
   * 资源”，却不知道出库车从哪一端进入，长单线同向/对向判定会退化为过宽或过窄的阻塞。
   */
  private boolean addEdgeDerivedLookoverResources(
      Set<OccupancyResource> resources,
      Map<OccupancyResource, ResourceIntent> intents,
      Map<String, CorridorDirection> directions,
      Map<String, Integer> entryOrders,
      List<NodeId> pathNodes,
      LookoverEdge lookover) {
    if (resources == null || directions == null || entryOrders == null || lookover == null) {
      return false;
    }
    boolean updated = false;
    List<OccupancyResource> edgeResources =
        OccupancyResourceResolver.resourcesForEdge(graph, lookover.edge());
    for (OccupancyResource resource : edgeResources) {
      if (resource == null) {
        continue;
      }
      if (resource.kind() == ResourceKind.CONFLICT) {
        updated |= putConflictEntryOrder(entryOrders, resource.key(), lookover.entryOrder());
      }
      updated |= addResource(resources, intents, resource, ResourceIntent.MOVEMENT_REQUIRED);
    }
    updated |= putCorridorDirection(directions, pathNodes, lookover);
    return updated;
  }

  private boolean tryAddDirectionalLookoverConflict(
      Set<OccupancyResource> resources,
      Map<OccupancyResource, ResourceIntent> intents,
      Map<String, CorridorDirection> directions,
      Map<String, Integer> entryOrders,
      List<NodeId> pathNodes,
      LookoverEdge lookover) {
    if (resources == null || directions == null || entryOrders == null || lookover == null) {
      return false;
    }
    boolean updated = false;
    if (graph instanceof RailGraphCorridorSupport support) {
      Optional<String> conflictKeyOpt = support.conflictKeyForEdge(lookover.edge().id());
      if (conflictKeyOpt.isPresent()) {
        String key = conflictKeyOpt.get();
        addResource(
            resources,
            intents,
            OccupancyResource.forConflict(key),
            ResourceIntent.MOVEMENT_REQUIRED);
        putConflictEntryOrder(entryOrders, key, lookover.entryOrder());
        Optional<RailGraphCorridorInfo> infoOpt = support.corridorInfoForEdge(lookover.edge().id());
        if (infoOpt.isPresent() && infoOpt.get().directional() && !directions.containsKey(key)) {
          resolveCorridorDirection(infoOpt.get(), pathNodes, lookover.from(), lookover.to())
              .direction()
              .ifPresent(direction -> directions.put(key, direction));
        }
        updated = true;
      }
    }
    if (graph instanceof RailGraphSectionSupport sectionSupport) {
      Optional<SingleLineSectionInfo> sectionOpt =
          sectionSupport.sectionInfoForEdge(lookover.edge().id());
      if (sectionOpt.isPresent()) {
        SingleLineSectionInfo section = sectionOpt.get();
        addResource(
            resources,
            intents,
            OccupancyResource.forConflict(section.key()),
            ResourceIntent.MOVEMENT_REQUIRED);
        putConflictEntryOrder(entryOrders, section.key(), lookover.entryOrder());
        if (section.directional() && !directions.containsKey(section.key())) {
          resolveSectionDirection(section, pathNodes, lookover.from(), lookover.to())
              .direction()
              .ifPresent(direction -> directions.put(section.key(), direction));
        }
        updated = true;
      }
    }
    return updated;
  }

  private boolean putCorridorDirection(
      Map<String, CorridorDirection> directions, List<NodeId> pathNodes, LookoverEdge lookover) {
    if (directions == null || lookover == null) {
      return false;
    }
    boolean updated = false;
    if (graph instanceof RailGraphCorridorSupport support) {
      Optional<RailGraphCorridorInfo> infoOpt = support.corridorInfoForEdge(lookover.edge().id());
      if (infoOpt.isPresent() && infoOpt.get().directional()) {
        RailGraphCorridorInfo info = infoOpt.get();
        if (!directions.containsKey(info.key())) {
          DirectionResolution direction =
              resolveCorridorDirection(info, pathNodes, lookover.from(), lookover.to());
          if (direction.direction().isPresent()) {
            directions.put(info.key(), direction.direction().get());
            updated = true;
          }
        }
      }
    }
    if (graph instanceof RailGraphSectionSupport sectionSupport) {
      Optional<SingleLineSectionInfo> sectionOpt =
          sectionSupport.sectionInfoForEdge(lookover.edge().id());
      if (sectionOpt.isPresent() && sectionOpt.get().directional()) {
        SingleLineSectionInfo section = sectionOpt.get();
        if (!directions.containsKey(section.key())) {
          DirectionResolution direction =
              resolveSectionDirection(section, pathNodes, lookover.from(), lookover.to());
          if (direction.direction().isPresent()) {
            directions.put(section.key(), direction.direction().get());
            updated = true;
          }
        }
      }
    }
    return updated;
  }

  private boolean putConflictEntryOrder(
      Map<String, Integer> entryOrders, String key, int entryOrder) {
    if (entryOrders == null || key == null || key.isBlank()) {
      return false;
    }
    Integer previous = entryOrders.putIfAbsent(key, Math.max(0, entryOrder));
    return previous == null;
  }

  /**
   * 从指定节点向外收集 lookover 边，并保留 BFS 进入方向。
   *
   * <p>图本身是无向的，但单线走廊的同向/对向判断依赖“列车从哪一端进入”。因此 lookover 不能只返回边，还必须携带从起点向外展开时的 from/to 方向与入口序号。
   */
  private List<LookoverEdge> collectDirectedEdgesWithin(NodeId start, int maxEdges) {
    if (start == null || maxEdges <= 0) {
      return List.of();
    }

    record NodeDepth(NodeId node, int depth) {}

    List<LookoverEdge> collected = new ArrayList<>();
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
        NodeId next = current.node().equals(edge.from()) ? edge.to() : edge.from();
        if (next == null) {
          continue;
        }
        int nextDepth = current.depth() + 1;
        collected.add(new LookoverEdge(edge, current.node(), next, nextDepth - 1));
        Integer known = bestDepth.get(next);
        if (known != null && known <= nextDepth) {
          continue;
        }
        bestDepth.put(next, nextDepth);
        queue.addLast(new NodeDepth(next, nextDepth));
      }
    }

    return List.copyOf(collected);
  }

  private DirectedTraversalContext buildDirectedContext(
      String trainName,
      Optional<RouteId> routeId,
      int currentIndex,
      Optional<NodeId> currentNode,
      List<NodeId> pathNodes,
      List<RailEdge> edges,
      Map<String, CorridorDirection> corridorDirections,
      Set<OccupancyResource> resources,
      String source) {
    List<NodeId> effectivePath = pathNodes == null ? List.of() : List.copyOf(pathNodes);
    List<DirectedTraversalContext.DirectedEdge> directedEdges = new ArrayList<>();
    List<RailEdge> safeEdges = edges == null ? List.of() : edges;
    int directedCount = Math.min(safeEdges.size(), Math.max(0, effectivePath.size() - 1));
    for (int i = 0; i < directedCount; i++) {
      RailEdge edge = safeEdges.get(i);
      if (edge == null || effectivePath.get(i) == null || effectivePath.get(i + 1) == null) {
        continue;
      }
      directedEdges.add(
          new DirectedTraversalContext.DirectedEdge(
              EdgeId.undirected(edge.from(), edge.to()),
              effectivePath.get(i),
              effectivePath.get(i + 1)));
    }
    Map<String, CorridorDirection> singleDirections = new LinkedHashMap<>();
    if (corridorDirections != null) {
      for (Map.Entry<String, CorridorDirection> entry : corridorDirections.entrySet()) {
        if (entry.getKey() != null && entry.getKey().startsWith("single:")) {
          singleDirections.put(entry.getKey(), entry.getValue());
        }
      }
    }
    Map<String, DirectedTraversalContext.SwitcherPathSignature> switcherSignatures =
        new LinkedHashMap<>();
    if (resources != null) {
      for (OccupancyResource resource : resources) {
        if (resource == null
            || resource.kind() != ResourceKind.CONFLICT
            || !resource.key().startsWith(SWITCHER_CONFLICT_PREFIX)) {
          continue;
        }
        switcherSignatures.put(
            resource.key(),
            new DirectedTraversalContext.SwitcherPathSignature(resource.key(), effectivePath));
      }
    }
    return new DirectedTraversalContext(
        trainName,
        routeId,
        currentIndex,
        currentNode,
        Optional.empty(),
        effectivePath.isEmpty() ? Optional.empty() : Optional.ofNullable(effectivePath.get(0)),
        effectivePath.size() < 2 ? Optional.empty() : Optional.ofNullable(effectivePath.get(1)),
        effectivePath,
        directedEdges,
        singleDirections,
        switcherSignatures,
        source,
        -1L,
        -1L,
        null,
        Optional.empty());
  }

  private void applySwitcherZoneConflicts(
      Set<OccupancyResource> resources,
      Map<OccupancyResource, ResourceIntent> intents,
      List<NodeId> pathNodes) {
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
    if (intents != null) {
      intents
          .keySet()
          .removeIf(
              resource ->
                  resource.kind() == ResourceKind.CONFLICT
                      && resource.key().startsWith(SWITCHER_CONFLICT_PREFIX)
                      && !allowed.contains(resource.key()));
    }
    // 补回前 N 段边内的道岔冲突资源。
    for (String key : allowed) {
      addResource(
          resources, intents, OccupancyResource.forConflict(key), ResourceIntent.MOVEMENT_REQUIRED);
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

  private List<NodeId> splitAtRepeatedOppositeTraversal(
      String trainName,
      String route,
      AuthorizationPurpose purpose,
      List<NodeId> expandedPathNodes) {
    if (expandedPathNodes == null || expandedPathNodes.size() < 3) {
      return expandedPathNodes == null ? List.of() : expandedPathNodes;
    }
    Map<EdgeId, DirectedTraversalContext.DirectedEdge> seen = new LinkedHashMap<>();
    for (int i = 0; i < expandedPathNodes.size() - 1; i++) {
      NodeId from = expandedPathNodes.get(i);
      NodeId to = expandedPathNodes.get(i + 1);
      if (from == null || to == null) {
        continue;
      }
      EdgeId edgeId = EdgeId.undirected(from, to);
      DirectedTraversalContext.DirectedEdge current =
          new DirectedTraversalContext.DirectedEdge(edgeId, from, to);
      DirectedTraversalContext.DirectedEdge previous = seen.get(edgeId);
      if (previous == null) {
        seen.put(edgeId, current);
        continue;
      }
      boolean opposite =
          previous.fromNode().equals(current.toNode())
              && previous.toNode().equals(current.fromNode());
      if (!opposite) {
        continue;
      }
      NodeId boundary = from;
      List<NodeId> segment = List.copyOf(expandedPathNodes.subList(0, i + 1));
      debugLogger.accept(
          "SMART_ROUTE_REPEATED_EDGE_DIRECTION_CHANGE route="
              + route
              + " edge="
              + edgeId
              + " firstDirection="
              + previous.fromNode().value()
              + "->"
              + previous.toNode().value()
              + " secondDirection="
              + current.fromNode().value()
              + "->"
              + current.toNode().value()
              + " purpose="
              + purpose);
      debugLogger.accept(
          "SMART_ROUTE_TURNBACK_BOUNDARY_DETECTED route=" + route + " station=" + boundary.value());
      debugLogger.accept(
          "SMART_ROUTE_SEGMENT_SPLIT_AT_TURNBACK train="
              + trainName
              + " station="
              + boundary.value()
              + " segmentAEnd="
              + boundary.value()
              + " segmentBStart="
              + current.toNode().value());
      debugLogger.accept(
          "SMART_JBS_SEGMENT_AUTHORITY train="
              + trainName
              + " segment=A"
              + " from="
              + segment.get(0).value()
              + " to="
              + boundary.value()
              + " resourceCount="
              + Math.max(0, segment.size() - 1));
      return segment;
    }
    return expandedPathNodes;
  }

  /**
   * 按实际边数截断展开后的节点列表。
   *
   * <p>保留前 {@code maxEdges} 条边对应的节点（即 maxEdges+1 个节点）。
   *
   * @param nodes 展开后的完整节点列表
   * @param maxEdges 最大边数
   * @return 截断后的节点列表
   */
  private List<NodeId> truncateToEdgeCount(List<NodeId> nodes, int maxEdges) {
    if (nodes == null || nodes.isEmpty()) {
      return List.of();
    }
    if (maxEdges <= 0) {
      return List.of(nodes.get(0));
    }
    // 边数 = 节点数 - 1，所以保留 maxEdges+1 个节点
    int keepNodes = Math.min(nodes.size(), maxEdges + 1);
    return nodes.subList(0, keepNodes);
  }

  /**
   * 截断前向 lookahead。
   *
   * <p>普通运行窗口既不能只看边数，也不能只看距离：短边密集区必须继续扩展到最小距离，长边区仍至少保留配置的边数下限；同时用 {@code maxLookaheadEdges}
   * 作为硬上限，避免资源集无界膨胀。
   */
  private List<NodeId> truncateLookahead(List<NodeId> nodes, List<RailEdge> edges) {
    if (nodes == null || nodes.isEmpty()) {
      return List.of();
    }
    if (edges == null || edges.isEmpty()) {
      return List.of(nodes.get(0));
    }
    if (minLookaheadDistanceBlocks <= 0L) {
      return truncateToEdgeCount(nodes, effectiveLookaheadEdges);
    }
    int edgeLimit = Math.min(edges.size(), maxLookaheadEdges);
    int includedEdges = 0;
    long coveredDistance = 0L;
    while (includedEdges < edgeLimit
        && (includedEdges < effectiveLookaheadEdges
            || coveredDistance < minLookaheadDistanceBlocks)) {
      RailEdge edge = edges.get(includedEdges);
      if (edge != null) {
        coveredDistance += Math.max(0L, edge.lengthBlocks());
      }
      includedEdges++;
    }
    int keepNodes = Math.min(nodes.size(), includedEdges + 1);
    return nodes.subList(0, keepNodes);
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

  private Optional<CurrentStep> resolveCurrentStep(NodeId currentNode, NodeId targetNode) {
    Optional<RailEdge> direct = findEdge(currentNode, targetNode);
    if (direct.isPresent()) {
      return Optional.of(new CurrentStep(List.of(currentNode, targetNode), direct.get()));
    }
    Optional<RailGraphPath> pathOpt =
        pathFinder.shortestPath(
            graph, currentNode, targetNode, RailGraphPathFinder.Options.shortestDistance());
    if (pathOpt.isEmpty() || pathOpt.get().nodes().size() < 2) {
      return Optional.empty();
    }
    List<NodeId> path = pathOpt.get().nodes();
    NodeId nextNode = path.get(1);
    Optional<RailEdge> edge = findEdge(currentNode, nextNode);
    return edge.map(railEdge -> new CurrentStep(List.of(currentNode, nextNode), railEdge));
  }

  /**
   * 解析路径中每个单线冲突资源的方向，并记录必须拒绝 legacy fallback 的资源。
   *
   * <p>语义方向解析器在 hub gap 等场景会明确要求 fail-closed；此时不能让后续字典序、距离或端点索引 fallback 重新给出看似确定但实际不安全的方向。
   */
  private CorridorDirectionResolution resolveCorridorDirectionResolution(List<NodeId> pathNodes) {
    if (!(graph instanceof RailGraphCorridorSupport support)) {
      return CorridorDirectionResolution.empty();
    }
    if (pathNodes == null || pathNodes.size() < 2) {
      return CorridorDirectionResolution.empty();
    }
    Map<String, CorridorDirection> directions = new LinkedHashMap<>();
    Set<String> blockedDirectionKeys = new HashSet<>();
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
                if (blockedDirectionKeys.contains(info.key())) {
                  return;
                }
                DirectionResolution resolution =
                    resolveCorridorDirection(info, pathNodes, from, to);
                if (resolution.blocksDirection()) {
                  directions.remove(info.key());
                  blockedDirectionKeys.add(info.key());
                  debugLogger.accept(
                      "方向判定失败: key="
                          + info.key()
                          + " from="
                          + from.value()
                          + " to="
                          + to.value()
                          + " corridorNodes="
                          + info.nodes().stream()
                              .map(NodeId::value)
                              .collect(java.util.stream.Collectors.joining(",")));
                  return;
                }
                if (directions.containsKey(info.key())) {
                  return;
                }
                if (resolution.direction().isPresent()) {
                  directions.put(info.key(), resolution.direction().get());
                  return;
                }
                debugLogger.accept(
                    "方向判定失败: key="
                        + info.key()
                        + " from="
                        + from.value()
                        + " to="
                        + to.value()
                        + " corridorNodes="
                        + info.nodes().stream()
                            .map(NodeId::value)
                            .collect(java.util.stream.Collectors.joining(",")));
              });
      if (graph instanceof RailGraphSectionSupport sectionSupport) {
        sectionSupport
            .sectionInfoForEdge(edgeId)
            .filter(SingleLineSectionInfo::directional)
            .ifPresent(
                info -> {
                  if (blockedDirectionKeys.contains(info.key())) {
                    return;
                  }
                  DirectionResolution resolution =
                      resolveSectionDirection(info, pathNodes, from, to);
                  if (resolution.blocksDirection()) {
                    directions.remove(info.key());
                    blockedDirectionKeys.add(info.key());
                    debugLogger.accept(
                        "section 方向判定失败: key="
                            + info.key()
                            + " from="
                            + from.value()
                            + " to="
                            + to.value()
                            + " sectionNodes="
                            + info.nodes().stream()
                                .map(NodeId::value)
                                .collect(java.util.stream.Collectors.joining(",")));
                    return;
                  }
                  if (directions.containsKey(info.key())) {
                    return;
                  }
                  if (resolution.direction().isPresent()) {
                    directions.put(info.key(), resolution.direction().get());
                    return;
                  }
                  debugLogger.accept(
                      "section 方向判定失败: key="
                          + info.key()
                          + " from="
                          + from.value()
                          + " to="
                          + to.value()
                          + " sectionNodes="
                          + info.nodes().stream()
                              .map(NodeId::value)
                              .collect(java.util.stream.Collectors.joining(",")));
                });
      }
    }
    return new CorridorDirectionResolution(directions, blockedDirectionKeys);
  }

  private Map<String, CorridorDirection> requestCorridorDirections(
      Collection<OccupancyResource> resources,
      CorridorDirectionResolution windowDirections,
      CorridorDirectionResolution planDirections) {
    if (resources == null || resources.isEmpty()) {
      return Map.of();
    }
    CorridorDirectionResolution window =
        windowDirections == null ? CorridorDirectionResolution.empty() : windowDirections;
    CorridorDirectionResolution plan =
        planDirections == null ? CorridorDirectionResolution.empty() : planDirections;
    Map<String, CorridorDirection> merged = new LinkedHashMap<>();
    for (OccupancyResource resource : resources) {
      if (resource == null || resource.kind() != ResourceKind.CONFLICT) {
        continue;
      }
      String key = resource.key();
      if (key == null || plan.blockedKeys().contains(key)) {
        continue;
      }
      CorridorDirection direction = plan.directions().get(key);
      if (direction == null && !window.blockedKeys().contains(key)) {
        direction = window.directions().get(key);
      }
      if (direction != null) {
        merged.putIfAbsent(key, direction);
      }
    }
    return Map.copyOf(merged);
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

  private DirectionResolution resolveCorridorDirection(
      RailGraphCorridorInfo info, List<NodeId> pathNodes, NodeId from, NodeId to) {
    if (info == null) {
      return DirectionResolution.unresolved();
    }
    return resolveDirectionalConflict(
        info.left(), info.right(), info.nodes(), List.of(), pathNodes, from, to);
  }

  private DirectionResolution resolveSectionDirection(
      SingleLineSectionInfo info, List<NodeId> pathNodes, NodeId from, NodeId to) {
    if (info == null) {
      return DirectionResolution.unresolved();
    }
    return resolveDirectionalConflict(
        info.left(), info.right(), info.nodes(), info.boundaries(), pathNodes, from, to);
  }

  private DirectionResolution resolveDirectionalConflict(
      NodeId left,
      NodeId right,
      List<NodeId> orderedNodes,
      List<NodeId> boundaryNodes,
      List<NodeId> pathNodes,
      NodeId from,
      NodeId to) {
    SemanticCorridorDirectionResolver.Result semantic =
        semanticDirectionResolver.resolve(
            semanticResourceNodes(left, right, orderedNodes, boundaryNodes), pathNodes, from, to);
    if (semantic.resolved()) {
      return DirectionResolution.resolved(semantic.direction());
    }
    CorridorDirection byCorridor = resolveDirectionByCorridorNodes(orderedNodes, from, to);
    if (semantic.blocksLegacyFallback()) {
      if (byCorridor != CorridorDirection.UNKNOWN
          && canUseOrderedCorridorFallbackAfterSemanticBlock(semantic, from, to)) {
        return DirectionResolution.resolved(byCorridor);
      }
      return DirectionResolution.blocked();
    }
    if (byCorridor != CorridorDirection.UNKNOWN) {
      return DirectionResolution.resolved(byCorridor);
    }
    int leftIndex = indexOfNode(pathNodes, left);
    int rightIndex = indexOfNode(pathNodes, right);
    if (leftIndex >= 0 && rightIndex >= 0 && leftIndex != rightIndex) {
      return DirectionResolution.resolved(
          leftIndex < rightIndex ? CorridorDirection.A_TO_B : CorridorDirection.B_TO_A);
    }
    CorridorDirection byDistance = resolveDirectionByDistance(from, to, left, right);
    return byDistance == CorridorDirection.UNKNOWN
        ? DirectionResolution.unresolved()
        : DirectionResolution.resolved(byDistance);
  }

  /**
   * 判断语义轴阻断后是否还能使用冲突索引的有序路径兜底。
   *
   * <p>普通 hub gap 的 {@code AMBIGUOUS} 必须继续 fail-closed；但 Station/Depot throat 边是线路模型里显式的终端过渡节点。
   * 当待判定边已经在同一个 ordered corridor/section 中可直接排序时，使用该顺序比保留 UNKNOWN 更能保持同一列车前向授权与运行中请求的方向一致。
   */
  private boolean canUseOrderedCorridorFallbackAfterSemanticBlock(
      SemanticCorridorDirectionResolver.Result semantic, NodeId from, NodeId to) {
    return semantic.status() == SemanticCorridorDirectionResolver.Status.AMBIGUOUS
        && touchesTerminalThroat(from, to);
  }

  private boolean touchesTerminalThroat(NodeId from, NodeId to) {
    return isTerminalThroat(from) || isTerminalThroat(to);
  }

  private boolean isTerminalThroat(NodeId node) {
    if (node == null) {
      return false;
    }
    return graph
        .findNode(node)
        .flatMap(railNode -> railNode.waypointMetadata())
        .map(WaypointMetadata::kind)
        .filter(kind -> kind == WaypointKind.STATION_THROAT || kind == WaypointKind.DEPOT_THROAT)
        .isPresent();
  }

  /**
   * 单次方向解析结果。
   *
   * <p>{@code unresolved} 表示当前证据不足但可继续 fallback；{@code blocked} 表示语义解析已发现本地分叉 gap 等不安全场景，调用方必须保留
   * UNKNOWN。唯一例外是待判定边明确触碰 Station/Depot throat，且同一 ordered corridor/section 已能直接证明顺序。
   */
  private record DirectionResolution(
      Optional<CorridorDirection> direction, boolean blocksDirection) {
    private DirectionResolution {
      direction = direction == null ? Optional.empty() : direction;
    }

    static DirectionResolution resolved(CorridorDirection direction) {
      return new DirectionResolution(Optional.of(direction), false);
    }

    static DirectionResolution unresolved() {
      return new DirectionResolution(Optional.empty(), false);
    }

    static DirectionResolution blocked() {
      return new DirectionResolution(Optional.empty(), true);
    }
  }

  /**
   * 一条路径上的方向解析快照。
   *
   * <p>{@code directions} 保存已证明方向；{@code blockedKeys} 保存语义解析明确拒绝 fallback、且不满足 terminal throat
   * 有序路径例外的资源 key。
   */
  private record CorridorDirectionResolution(
      Map<String, CorridorDirection> directions, Set<String> blockedKeys) {
    private CorridorDirectionResolution {
      directions = directions == null ? Map.of() : Map.copyOf(directions);
      blockedKeys = blockedKeys == null ? Set.of() : Set.copyOf(blockedKeys);
    }

    static CorridorDirectionResolution empty() {
      return new CorridorDirectionResolution(Map.of(), Set.of());
    }
  }

  private List<NodeId> semanticResourceNodes(
      NodeId left, NodeId right, List<NodeId> orderedNodes, List<NodeId> boundaryNodes) {
    LinkedHashSet<NodeId> nodes = new LinkedHashSet<>();
    if (left != null) {
      nodes.add(left);
    }
    if (right != null) {
      nodes.add(right);
    }
    if (orderedNodes != null) {
      for (NodeId node : orderedNodes) {
        if (node != null) {
          nodes.add(node);
        }
      }
    }
    if (boundaryNodes != null) {
      for (NodeId node : boundaryNodes) {
        if (node != null) {
          nodes.add(node);
        }
      }
    }
    return List.copyOf(nodes);
  }

  private CorridorDirection resolveDirectionByCorridorNodes(
      List<NodeId> corridorNodes, NodeId from, NodeId to) {
    if (corridorNodes == null || from == null || to == null) {
      return CorridorDirection.UNKNOWN;
    }
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

  private record LookoverEdge(RailEdge edge, NodeId from, NodeId to, int entryOrder) {}

  private record CurrentStep(List<NodeId> nodes, RailEdge edge) {}
}
