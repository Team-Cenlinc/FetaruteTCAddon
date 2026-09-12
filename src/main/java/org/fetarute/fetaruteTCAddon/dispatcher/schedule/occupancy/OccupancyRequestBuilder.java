package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainRuntimeState;

/**
 * 运行时占用请求构建器：把“列车状态 + 线路定义 + 图”转换成 OccupancyRequest。
 *
 * <p>默认会占用 lookahead 边与对应节点资源，并附加走廊/道岔冲突资源；道岔冲突可按 {@code switcherZoneEdges} 限制为“前 N 段边内的道岔”。
 * 同向跟驰最小空闲边数由 {@code minClearEdges} 与 lookahead 取最大值控制。尾部保护同时满足 {@code rearGuardEdges}
 * 的边数下限与列车长度导出的最小方块距离，避免长编组车头过点后提前释放仍被列尾占用的平交/道岔资源。
 *
 * <p>同时会记录冲突区 entryOrder（首次进入冲突的边序号），用于冲突区放行与死锁解除。
 *
 * <p>这个构建器只负责“把图上的可见状态翻译成请求”，不直接改写占用管理器状态。也就是说，真正的放行/阻塞/队列公平性仍由 {@link SimpleOccupancyManager}
 * 统一裁决，构建器只保证请求携带足够且稳定的上下文。
 */
public final class OccupancyRequestBuilder {

  private final RailGraph graph;
  private final int switcherZoneEdges;
  private final int rearGuardEdges;
  private final int effectiveLookaheadEdges;
  private final long minLookaheadDistanceBlocks;
  private final int maxLookaheadEdges;
  private final long minRearGuardDistanceBlocks;
  private final long minConflictExitDistanceBlocks;
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
    this(
        graph,
        lookaheadEdges,
        minClearEdges,
        rearGuardEdges,
        switcherZoneEdges,
        minLookaheadDistanceBlocks,
        maxLookaheadEdges,
        0L,
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
      long minRearGuardDistanceBlocks,
      java.util.function.Consumer<String> debugLogger) {
    this(
        graph,
        lookaheadEdges,
        minClearEdges,
        rearGuardEdges,
        switcherZoneEdges,
        minLookaheadDistanceBlocks,
        maxLookaheadEdges,
        minRearGuardDistanceBlocks,
        0L,
        debugLogger);
  }

  private OccupancyRequestBuilder(
      RailGraph graph,
      int lookaheadEdges,
      int minClearEdges,
      int rearGuardEdges,
      int switcherZoneEdges,
      long minLookaheadDistanceBlocks,
      int maxLookaheadEdges,
      long minRearGuardDistanceBlocks,
      long minConflictExitDistanceBlocks,
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
    if (minRearGuardDistanceBlocks < 0L) {
      throw new IllegalArgumentException("minRearGuardDistanceBlocks 必须为非负数");
    }
    if (minConflictExitDistanceBlocks < 0L) {
      throw new IllegalArgumentException("minConflictExitDistanceBlocks 必须为非负数");
    }
    this.switcherZoneEdges = switcherZoneEdges;
    this.rearGuardEdges = rearGuardEdges;
    this.effectiveLookaheadEdges = Math.max(lookaheadEdges, minClearEdges);
    this.minLookaheadDistanceBlocks = minLookaheadDistanceBlocks;
    this.minRearGuardDistanceBlocks = minRearGuardDistanceBlocks;
    this.minConflictExitDistanceBlocks = minConflictExitDistanceBlocks;
    this.maxLookaheadEdges =
        maxLookaheadEdges <= 0
            ? this.effectiveLookaheadEdges
            : Math.max(this.effectiveLookaheadEdges, maxLookaheadEdges);
  }

  /**
   * 返回要求物理联锁出口具备最小净空距离的新构建器。
   *
   * <p>该距离从最后一条携带同一 {@code interlocking:*} 资源的边之后开始累计，用于证明列车进入冲突区前，前方泊位至少能容纳整列车与停车余量。它与后向列尾保护、常规
   * lookahead 距离相互独立；完整路径无法证明足够净空时，请求会 fail-closed。
   *
   * @param distanceBlocks 冲突区外所需的最小净空方块数
   * @return 保留当前全部设置、仅替换出口净空距离的新构建器
   */
  public OccupancyRequestBuilder withMinimumConflictExitDistanceBlocks(long distanceBlocks) {
    if (distanceBlocks < 0L) {
      throw new IllegalArgumentException("distanceBlocks 必须为非负数");
    }
    if (distanceBlocks == minConflictExitDistanceBlocks) {
      return this;
    }
    return new OccupancyRequestBuilder(
        graph,
        effectiveLookaheadEdges,
        0,
        rearGuardEdges,
        switcherZoneEdges,
        minLookaheadDistanceBlocks,
        maxLookaheadEdges,
        minRearGuardDistanceBlocks,
        distanceBlocks,
        debugLogger);
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
    return buildContextFromNodesWithDirectionContext(
        trainName, routeId, nodes, nodes, currentIndex, now, priority, purpose);
  }

  /**
   * 使用彼此独立的物理窗口与方向证据构建占用请求。
   *
   * <p>{@code nodes} 是实时位置修正后的 movement path，唯一决定本请求会申请哪些 NODE/EDGE/CONFLICT 资源；{@code
   * directionContextNodes} 只为单线资源提供当前 route leg 的稳定语义轴，不会被加入硬授权。典型场景是列车已经位于两个 route waypoint
   * 之间：物理窗口必须从 lastPassedGraphNode 起算，但方向仍应继承原 route waypoint 到下一站的语义，避免同一进路因上游锚点刚刚落到车后而从 A_TO_B
   * 漂移成 B_TO_A。
   *
   * <p>方向上下文不可展开或不连通时安全回退到实时 movement path；资源集合始终不受回退影响。
   *
   * @param trainName 列车名
   * @param routeId route 标识
   * @param nodes 实时物理请求节点
   * @param directionContextNodes 仅用于方向解析的 canonical route 节点
   * @param currentIndex 当前 route index
   * @param now 请求时间
   * @param priority 调度优先级
   * @param purpose 授权来源
   * @return 请求与物理路径上下文；实时路径不可构建时返回 empty
   */
  public Optional<OccupancyRequestContext> buildContextFromNodesWithDirectionContext(
      String trainName,
      Optional<RouteId> routeId,
      List<NodeId> nodes,
      List<NodeId> directionContextNodes,
      int currentIndex,
      Instant now,
      int priority,
      AuthorizationPurpose purpose) {
    Objects.requireNonNull(trainName, "trainName");
    Objects.requireNonNull(routeId, "routeId");
    Objects.requireNonNull(nodes, "nodes");
    Objects.requireNonNull(directionContextNodes, "directionContextNodes");
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
    List<NodeId> directionExpanded =
        expandDirectionContextPath(
            trainName, routeId, directionContextNodes, currentIndex, purpose, fullExpanded);
    Optional<ExpandedPathPlan> canonicalRearRetainPathPlan =
        resolveCanonicalRearRetainPathPlan(
            trainName,
            routeId,
            nodes,
            directionContextNodes,
            currentIndex,
            directionExpanded,
            purpose);
    List<RailEdge> fullEdges = resolveEdges(fullExpanded);
    if (fullEdges.isEmpty()) {
      debugLogger.accept("构建请求失败: full resolveEdges 返回空 (边未找到?) nodes=" + fullExpanded);
      return Optional.empty();
    }
    // 按实际图边截断：至少覆盖 edge 下限和距离下限，同时受 maxLookaheadEdges 硬上限约束。
    List<NodeId> expandedNodes = truncateLookahead(fullExpanded, fullEdges);
    ConflictExitAuthorityWindow conflictExitWindow =
        resolveConflictExitAuthorityWindow(fullEdges, expandedNodes);
    if (conflictExitWindow.applicable() && !conflictExitWindow.resolved()) {
      debugLogger.accept(
          "构建请求失败: 物理联锁缺少可见清出边 train="
              + trainName
              + " conflicts="
              + conflictExitWindow.conflictKeys()
              + " nodes="
              + fullExpanded);
      return Optional.empty();
    }
    if (conflictExitWindow.resolved()
        && conflictExitWindow.requiredNodeCount() > expandedNodes.size()) {
      expandedNodes = List.copyOf(fullExpanded.subList(0, conflictExitWindow.requiredNodeCount()));
      debugLogger.accept(
          "冲突区出口提升为硬授权: train="
              + trainName
              + " conflicts="
              + conflictExitWindow.conflictKeys()
              + " nodes="
              + conflictExitWindow.requiredNodeCount());
    }
    AtomicAuthorityWindow atomicWindow =
        resolveAtomicInterlockingWindow(fullExpanded, expandedNodes, purpose);
    if (atomicWindow.applicable() && !atomicWindow.resolved()) {
      debugLogger.accept(
          "构建请求失败: 联锁路径缺少可见清出点 train="
              + trainName
              + " entry="
              + atomicWindow.entry().map(NodeId::value).orElse("-")
              + " nodes="
              + fullExpanded);
      return Optional.empty();
    }
    if (atomicWindow.resolved()) {
      int requiredNodeCount = atomicWindow.exitIndex() + 1;
      if (requiredNodeCount > expandedNodes.size()) {
        expandedNodes = List.copyOf(fullExpanded.subList(0, requiredNodeCount));
        debugLogger.accept(
            "联锁进路提升为原子授权: train="
                + trainName
                + " entry="
                + atomicWindow.entry().map(NodeId::value).orElse("-")
                + " exit="
                + atomicWindow.exit().map(NodeId::value).orElse("-")
                + " edges="
                + atomicWindow.exitIndex());
      }
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
    applySwitcherZoneConflicts(resources, intents, expandedNodes, atomicWindow.resolved());
    appendRearGuardResources(resources, intents, rearExpanded, rearEdges);
    CorridorDirectionResolution windowDirections =
        resolveCorridorDirectionResolution(expandedNodes);
    CorridorDirectionResolution planDirections =
        resolveCorridorDirectionResolution(directionExpanded);
    Map<String, CorridorDirection> corridorDirections =
        requestCorridorDirections(resources, windowDirections, planDirections);
    Set<String> unresolvedDirectionKeys =
        unresolvedDirectionKeys(resources, windowDirections, planDirections, corridorDirections);
    reportFinalDirectionFailures(
        trainName,
        routeId,
        purpose,
        resources,
        corridorDirections,
        windowDirections,
        planDirections);
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
                purpose.name())
            .withCanonicalRearRetainPathPlan(canonicalRearRetainPathPlan);
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
            Optional.of(directedContext),
            unresolvedDirectionKeys);
    return Optional.of(
        new OccupancyRequestContext(
            request,
            expandedNodes,
            edges,
            Optional.of(directedContext),
            minLookaheadDistanceBlocks));
  }

  /**
   * 展开仅用于方向解析的 canonical route 后缀。
   *
   * <p>该路径绝不参与资源收集、联锁窗口或 rear guard；解析失败时返回实时路径，保持既有 fail-closed 行为。
   */
  private List<NodeId> expandDirectionContextPath(
      String trainName,
      Optional<RouteId> routeId,
      List<NodeId> directionContextNodes,
      int currentIndex,
      AuthorizationPurpose purpose,
      List<NodeId> liveExpanded) {
    if (directionContextNodes == null
        || currentIndex < 0
        || currentIndex >= directionContextNodes.size() - 1) {
      return liveExpanded;
    }
    List<NodeId> suffix = new ArrayList<>();
    for (int index = currentIndex; index < directionContextNodes.size(); index++) {
      suffix.add(directionContextNodes.get(index));
    }
    List<NodeId> expanded = expandPathNodes(suffix);
    if (expanded.isEmpty()) {
      debugLogger.accept(
          "方向上下文回退实时路径: train="
              + trainName
              + " route="
              + routeId.map(RouteId::value).orElse("-")
              + " reason=expand-failed nodes="
              + suffix);
      return liveExpanded;
    }
    List<NodeId> split =
        splitAtRepeatedOppositeTraversal(
            trainName, routeId.map(RouteId::value).orElse("-"), purpose, expanded);
    if (split.size() < 2 || resolveEdges(split).isEmpty()) {
      debugLogger.accept(
          "方向上下文回退实时路径: train="
              + trainName
              + " route="
              + routeId.map(RouteId::value).orElse("-")
              + " reason=edges-missing nodes="
              + split);
      return liveExpanded;
    }
    return split;
  }

  /**
   * 构建严格终止于当前有效节点的最近已走行规范路径。
   *
   * <p>当列车仍位于同一 route leg 的中间节点时，路径取 canonical current waypoint 到真实 current/last-passed
   * 的前缀；当列车刚推进到新的 route index 时，路径取上一 waypoint 到当前 waypoint 的完整上一 leg。两者都来自本次 builder
   * 使用的同一图快照，并要求当前锚点唯一、边链完整且无回环。
   *
   * <p>该路径不参与本次资源集合、走廊方向或联锁授权，只允许上层把仍然 live、同 route、自持有的 {@link ClaimRole#PROTECTIVE_RETAIN}
   * NODE/EDGE 识别为前进后可重评估的尾部资源。任何展开失败、重复锚点或路径损坏都返回 empty。
   */
  private Optional<ExpandedPathPlan> resolveCanonicalRearRetainPathPlan(
      String trainName,
      Optional<RouteId> routeId,
      List<NodeId> movementNodes,
      List<NodeId> directionContextNodes,
      int currentIndex,
      List<NodeId> directionExpanded,
      AuthorizationPurpose purpose) {
    if (movementNodes == null
        || directionContextNodes == null
        || currentIndex < 0
        || currentIndex >= movementNodes.size()
        || currentIndex >= directionContextNodes.size()) {
      return Optional.empty();
    }
    NodeId currentNode = movementNodes.get(currentIndex);
    NodeId canonicalCurrentNode = directionContextNodes.get(currentIndex);
    if (currentNode == null || canonicalCurrentNode == null) {
      return Optional.empty();
    }

    List<NodeId> rearPath;
    if (!currentNode.equals(canonicalCurrentNode)) {
      if (directionExpanded == null
          || directionExpanded.size() < 2
          || !directionExpanded.get(0).equals(canonicalCurrentNode)) {
        return Optional.empty();
      }
      int anchorIndex = directionExpanded.indexOf(currentNode);
      if (anchorIndex <= 0 || anchorIndex != directionExpanded.lastIndexOf(currentNode)) {
        return Optional.empty();
      }
      rearPath = List.copyOf(directionExpanded.subList(0, anchorIndex + 1));
    } else {
      if (currentIndex <= 0) {
        return Optional.empty();
      }
      NodeId previousNode = directionContextNodes.get(currentIndex - 1);
      if (previousNode == null || previousNode.equals(currentNode)) {
        return Optional.empty();
      }
      List<NodeId> expanded = expandPathNodes(List.of(previousNode, currentNode));
      if (expanded.isEmpty()) {
        return Optional.empty();
      }
      rearPath =
          splitAtRepeatedOppositeTraversal(
              trainName,
              routeId.map(RouteId::value).orElse("-"),
              purpose == null ? AuthorizationPurpose.RUNTIME_MOVE : purpose,
              expanded);
    }
    return toCanonicalRearRetainPathPlan(rearPath, currentNode);
  }

  private Optional<ExpandedPathPlan> toCanonicalRearRetainPathPlan(
      List<NodeId> pathNodes, NodeId currentNode) {
    if (pathNodes == null
        || pathNodes.size() < 2
        || currentNode == null
        || !pathNodes.get(pathNodes.size() - 1).equals(currentNode)
        || pathNodes.indexOf(currentNode) != pathNodes.lastIndexOf(currentNode)
        || new HashSet<>(pathNodes).size() != pathNodes.size()) {
      return Optional.empty();
    }
    List<RailEdge> pathEdges = resolveEdges(pathNodes);
    if (pathEdges.size() != pathNodes.size() - 1) {
      return Optional.empty();
    }
    List<DirectedTraversalContext.DirectedEdge> directedEdges = new ArrayList<>(pathEdges.size());
    for (int index = 0; index < pathEdges.size(); index++) {
      NodeId fromNode = pathNodes.get(index);
      NodeId toNode = pathNodes.get(index + 1);
      RailEdge edge = pathEdges.get(index);
      if (edge == null || !edge.id().equals(EdgeId.undirected(fromNode, toNode))) {
        return Optional.empty();
      }
      directedEdges.add(new DirectedTraversalContext.DirectedEdge(edge.id(), fromNode, toNode));
    }
    return Optional.of(new ExpandedPathPlan(pathNodes, directedEdges, Map.of(), Map.of()));
  }

  /**
   * 识别从当前图节点进入、并必须一次持有到首个清出点的站场联锁窗口。
   *
   * <p>这不是新的图资源，也不依赖环秩或距离阈值：当前 waypoint/station/depot 是可保持 STOP 的入口边界；从入口到首个 SWITCHER/显式 throat
   * 之间的节点是联锁接近段；进入联锁节点后，首个普通 waypoint、destination 或下一安全停车点是清出点。请求必须把所选有向路径上的全部 NODE/EDGE/CONFLICT
   * 作为同一次 hard authority 提交，消除“双方各占一半再互等”的竞态。
   *
   * <p>只有首个联锁节点已经落入普通 hard lookahead 时才提升窗口；更远的道岔只保留在完整 Movement Plan 中，不能提前扩大本 tick
   * 的硬授权。开始提升后仍扫描到下一处 STATION/DEPOT：若已经进入联锁却没有可见清出点，则 fail-closed。
   */
  private AtomicAuthorityWindow resolveAtomicInterlockingWindow(
      List<NodeId> fullPath, List<NodeId> hardWindow, AuthorizationPurpose purpose) {
    if (purpose == AuthorizationPurpose.UNLOCK_RESERVATION
        || fullPath == null
        || fullPath.size() < 2
        || hardWindow == null
        || hardWindow.stream().noneMatch(this::isInterlockingNode)) {
      return AtomicAuthorityWindow.notApplicable();
    }
    boolean enteredInterlocking = isInterlockingNode(fullPath.get(0));
    for (int index = 1; index < fullPath.size(); index++) {
      NodeId node = fullPath.get(index);
      if (isInterlockingSafeStopNode(node)) {
        return enteredInterlocking
            ? AtomicAuthorityWindow.resolved(fullPath.get(0), node, index)
            : AtomicAuthorityWindow.notApplicable();
      }
      if (isInterlockingNode(node)) {
        enteredInterlocking = true;
        continue;
      }
      if (enteredInterlocking) {
        return isInterlockingClearanceNode(node)
            ? AtomicAuthorityWindow.resolved(fullPath.get(0), node, index)
            : AtomicAuthorityWindow.unresolved(fullPath.get(0));
      }
    }
    return enteredInterlocking
        ? AtomicAuthorityWindow.unresolved(fullPath.get(0))
        : AtomicAuthorityWindow.notApplicable();
  }

  /**
   * 把当前 hard window 已经触及的冲突区扩展到可原子取得的清出点。
   *
   * <p>本窗口只处理无方向的精确 {@code interlocking:*} 物理联锁资源；方向性 {@code single:*} 继续由局部硬窗口、方向锁、跟驰间隔和 leader
   * token 协作，不能扩张为整段单线独占。
   *
   * <p>清出点从冲突区最后一条边之后开始，至少覆盖首条无该冲突的边，并按 {@link #minConflictExitDistanceBlocks}
   * 继续累计可用泊位距离，使请求同时持有冲突内全部 EDGE/NODE/CONFLICT
   * 与足以容纳列车的冲突外资源。若清出窗口进入另一个冲突区，则继续扩展新冲突，直到找到不再引入冲突的稳定窗口。这样 entry lookahead 只负责描述 canonical
   * 路径，真正准入仍由同一个 {@link OccupancyRequest} 的 fresh acquire 原子完成。单个 switcher 的路径锁继续由后续
   * throat/switcher 原子窗口负责，避免在这里把相邻边重复解释为第二套出口规则。
   *
   * <p>完整路径没有可见清出边时 builder 直接返回空请求并 fail-closed；不能把原始短窗口继续交给不理解物理联锁的调用方。若 route
   * 终止在联锁区内，应补充可证明车体完全清出的图边界，而不是把 destination 当作出清证据。
   */
  private ConflictExitAuthorityWindow resolveConflictExitAuthorityWindow(
      List<RailEdge> fullEdges, List<NodeId> hardWindow) {
    if (fullEdges == null || fullEdges.isEmpty() || hardWindow == null || hardWindow.size() < 2) {
      return ConflictExitAuthorityWindow.notApplicable();
    }
    int hardEdgeCount = Math.min(fullEdges.size(), hardWindow.size() - 1);
    Set<String> conflictKeys = conflictKeysForEdges(fullEdges, hardEdgeCount);
    if (conflictKeys.isEmpty()) {
      return ConflictExitAuthorityWindow.notApplicable();
    }

    int requiredEdgeCount = hardEdgeCount;
    Set<String> resolvedKeys = new LinkedHashSet<>();
    boolean expanded;
    do {
      expanded = false;
      List<String> candidates =
          conflictKeys.stream().filter(key -> !resolvedKeys.contains(key)).toList();
      for (String conflictKey : candidates) {
        int exitEdgeCount = conflictExitEdgeCount(fullEdges, conflictKey);
        if (exitEdgeCount < 0) {
          return ConflictExitAuthorityWindow.unresolved(conflictKeys);
        }
        resolvedKeys.add(conflictKey);
        if (exitEdgeCount > requiredEdgeCount) {
          requiredEdgeCount = exitEdgeCount;
          expanded = true;
        }
      }
      Set<String> newlyVisible = conflictKeysForEdges(fullEdges, requiredEdgeCount);
      if (conflictKeys.addAll(newlyVisible)) {
        expanded = true;
      }
    } while (expanded || resolvedKeys.size() < conflictKeys.size());

    return ConflictExitAuthorityWindow.resolved(requiredEdgeCount + 1, conflictKeys);
  }

  private Set<String> conflictKeysForEdges(List<RailEdge> edges, int edgeCount) {
    if (edges == null || edges.isEmpty() || edgeCount <= 0) {
      return new LinkedHashSet<>();
    }
    Set<String> keys = new LinkedHashSet<>();
    int limit = Math.min(edges.size(), edgeCount);
    for (int index = 0; index < limit; index++) {
      List<OccupancyResource> edgeResources =
          OccupancyResourceResolver.resourcesForEdge(graph, edges.get(index));
      for (OccupancyResource resource : edgeResources) {
        if (resource != null
            && resource.kind() == ResourceKind.CONFLICT
            && isExactPhysicalInterlockingKey(resource.key())) {
          keys.add(resource.key());
        }
      }
    }
    return keys;
  }

  private static boolean isExactPhysicalInterlockingKey(String key) {
    return key != null
        && key.startsWith("interlocking:")
        && !key.startsWith("interlocking:incomplete:");
  }

  private int conflictExitEdgeCount(List<RailEdge> edges, String conflictKey) {
    boolean entered = false;
    long clearanceDistanceBlocks = 0L;
    for (int index = 0; index < edges.size(); index++) {
      RailEdge edge = edges.get(index);
      boolean edgeInConflict =
          OccupancyResourceResolver.resourcesForEdge(graph, edge).stream()
              .anyMatch(
                  resource ->
                      resource != null
                          && resource.kind() == ResourceKind.CONFLICT
                          && resource.key().equals(conflictKey));
      if (edgeInConflict) {
        entered = true;
        clearanceDistanceBlocks = 0L;
      } else if (entered) {
        long edgeLengthBlocks = Math.max(0L, edge.lengthBlocks());
        clearanceDistanceBlocks = saturatingAdd(clearanceDistanceBlocks, edgeLengthBlocks);
        if (clearanceDistanceBlocks >= minConflictExitDistanceBlocks) {
          return index + 1;
        }
      }
    }
    return -1;
  }

  private boolean isInterlockingNode(NodeId nodeId) {
    if (nodeId == null) {
      return false;
    }
    return graph
        .findNode(nodeId)
        .map(
            node -> {
              if (node.type() == NodeType.SWITCHER) {
                return true;
              }
              return node.waypointMetadata()
                  .map(WaypointMetadata::kind)
                  .filter(
                      kind ->
                          kind == WaypointKind.STATION_THROAT
                              || kind == WaypointKind.DEPOT_THROAT
                              || kind == WaypointKind.SWITCHER)
                  .isPresent();
            })
        .orElse(false);
  }

  private boolean isInterlockingSafeStopNode(NodeId nodeId) {
    if (nodeId == null) {
      return false;
    }
    return graph
        .findNode(nodeId)
        .map(node -> node.type() == NodeType.STATION || node.type() == NodeType.DEPOT)
        .orElse(false);
  }

  private boolean isInterlockingClearanceNode(NodeId nodeId) {
    if (nodeId == null) {
      return false;
    }
    return graph
        .findNode(nodeId)
        .map(node -> node.type() == NodeType.WAYPOINT || node.type() == NodeType.DESTINATION)
        .orElse(false);
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
        Optional.empty(),
        plan.canonicalRearRetainPathPlan());
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

  private List<NodeId> resolveRearGuardNodes(List<NodeId> nodes, int currentIndex) {
    if (rearGuardEdges <= 0 && minRearGuardDistanceBlocks <= 0L) {
      return List.of();
    }
    if (nodes == null || nodes.size() < 2) {
      return List.of();
    }
    if (currentIndex <= 0 || currentIndex >= nodes.size()) {
      return List.of();
    }
    int startIndex =
        minRearGuardDistanceBlocks > 0L ? 0 : Math.max(0, currentIndex - rearGuardEdges);
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
    return truncateRearGuardByEdgesAndDistance(expanded);
  }

  /**
   * 从车头当前位置向后截断尾部保护节点列表。
   *
   * <p>先计算配置 {@code rearGuardEdges}
   * 对应的安全余量距离，再叠加列车长度下限；从末尾逐边累计，直到同时满足配置边数和总距离。若已知后向路径不足，则保留全部可证明路径而不是缩短阈值。
   */
  private List<NodeId> truncateRearGuardByEdgesAndDistance(List<NodeId> nodes) {
    if (nodes == null || nodes.isEmpty()) {
      return List.of();
    }
    if (nodes.size() < 2 || (rearGuardEdges <= 0 && minRearGuardDistanceBlocks <= 0L)) {
      return List.of(nodes.get(nodes.size() - 1));
    }
    long configuredMargin = 0L;
    int availableEdges = nodes.size() - 1;
    int configuredEdges = Math.min(rearGuardEdges, availableEdges);
    for (int offset = 0; offset < configuredEdges; offset++) {
      int edgeIndex = availableEdges - 1 - offset;
      configuredMargin =
          saturatingAdd(
              configuredMargin,
              findEdge(nodes.get(edgeIndex), nodes.get(edgeIndex + 1))
                  .map(RailEdge::lengthBlocks)
                  .map(length -> Math.max(0, length))
                  .orElse(0));
    }
    long requiredDistance = saturatingAdd(configuredMargin, minRearGuardDistanceBlocks);
    long coveredDistance = 0L;
    int includedEdges = 0;
    while (includedEdges < availableEdges
        && (includedEdges < rearGuardEdges || coveredDistance < requiredDistance)) {
      int edgeIndex = availableEdges - 1 - includedEdges;
      coveredDistance =
          saturatingAdd(
              coveredDistance,
              findEdge(nodes.get(edgeIndex), nodes.get(edgeIndex + 1))
                  .map(RailEdge::lengthBlocks)
                  .map(length -> Math.max(0, length))
                  .orElse(0));
      includedEdges++;
    }
    int startIndex = nodes.size() - includedEdges - 1;
    return nodes.subList(startIndex, nodes.size());
  }

  private static long saturatingAdd(long left, long right) {
    if (right <= 0L) {
      return left;
    }
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
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

  /**
   * 将所选进路内的道岔映射为共享联锁资源。
   *
   * <p>普通前瞻只保留配置窗口内的道岔，避免过度锁闭；原子联锁窗口已经被首个可证明清出点限定，因此必须保留窗口内全部道岔。否则 NODE/EDGE
   * 虽然覆盖到出口，交叉或分支进路却可能因缺少共享 mutex 而同时获权。
   */
  private void applySwitcherZoneConflicts(
      Set<OccupancyResource> resources,
      Map<OccupancyResource, ResourceIntent> intents,
      List<NodeId> pathNodes,
      boolean atomicInterlockingWindow) {
    if (resources == null || pathNodes == null || switcherZoneEdges < 0) {
      return;
    }
    Set<String> allowed = new LinkedHashSet<>();
    if (!pathNodes.isEmpty()) {
      int maxIndex =
          atomicInterlockingWindow
              ? pathNodes.size() - 1
              : Math.min(pathNodes.size() - 1, switcherZoneEdges);
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
          "SMART_TURNBACK_SEGMENT_AUTHORITY train="
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
    SemanticCorridorDirectionResolver.PathAxisIndex pathAxisIndex =
        semanticDirectionResolver.indexPath(pathNodes);
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
                    resolveCorridorDirection(info, pathNodes, pathAxisIndex, from, to);
                if (resolution.blocksDirection()) {
                  directions.remove(info.key());
                  blockedDirectionKeys.add(info.key());
                  return;
                }
                if (directions.containsKey(info.key())) {
                  return;
                }
                if (resolution.direction().isPresent()) {
                  directions.put(info.key(), resolution.direction().get());
                }
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
                      resolveSectionDirection(info, pathNodes, pathAxisIndex, from, to);
                  if (resolution.blocksDirection()) {
                    directions.remove(info.key());
                    blockedDirectionKeys.add(info.key());
                    return;
                  }
                  if (directions.containsKey(info.key())) {
                    return;
                  }
                  if (resolution.direction().isPresent()) {
                    directions.put(info.key(), resolution.direction().get());
                  }
                });
      }
    }
    return new CorridorDirectionResolution(directions, blockedDirectionKeys);
  }

  /**
   * 收集本次请求中“方向已被明确判定为不可确定”的单线冲突 key。
   *
   * <p>{@code blockedKeys} 原本只用于抑制方向与输出诊断，从未随请求下发；下游因此只能看到 corridorDirections 里的缺键，
   * 无法区分“本次计划不含该冲突”和“证据矛盾必须 fail-closed”，于是继续沿回退链取用已持有 claim 的旧方向——
   * 换向后旧方向复活、对向屏障失效的通路正在于此。这里把该集合限定到实际请求资源后随请求下发。
   */
  private Set<String> unresolvedDirectionKeys(
      Collection<OccupancyResource> resources,
      CorridorDirectionResolution windowDirections,
      CorridorDirectionResolution planDirections,
      Map<String, CorridorDirection> finalDirections) {
    if (resources == null || resources.isEmpty()) {
      return Set.of();
    }
    CorridorDirectionResolution window =
        windowDirections == null ? CorridorDirectionResolution.empty() : windowDirections;
    CorridorDirectionResolution plan =
        planDirections == null ? CorridorDirectionResolution.empty() : planDirections;
    Set<String> unresolved = new LinkedHashSet<>();
    for (OccupancyResource resource : resources) {
      if (resource == null || resource.kind() != ResourceKind.CONFLICT) {
        continue;
      }
      String key = resource.key();
      if (key == null || finalDirections.containsKey(key)) {
        continue;
      }
      if (plan.blockedKeys().contains(key) || window.blockedKeys().contains(key)) {
        unresolved.add(key);
      }
    }
    return Set.copyOf(unresolved);
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

  /**
   * 在物理窗口与 canonical plan 合并完成后，仅报告实际请求资源中仍无法确定方向的单线资源。
   *
   * <p>窗口解析失败可能被完整计划恢复，非桥 micro corridor 也可能根本不属于本次授权。把诊断延迟到最终资源集合确定之后，可避免把中间态误报成调度故障，同时保留真正
   * UNKNOWN 的 fail-closed 证据。
   */
  private void reportFinalDirectionFailures(
      String trainName,
      Optional<RouteId> routeId,
      AuthorizationPurpose purpose,
      Collection<OccupancyResource> resources,
      Map<String, CorridorDirection> finalDirections,
      CorridorDirectionResolution windowDirections,
      CorridorDirectionResolution planDirections) {
    for (OccupancyResource resource : resources) {
      if (resource == null
          || resource.kind() != ResourceKind.CONFLICT
          || !resource.key().startsWith("single:")
          || finalDirections.containsKey(resource.key())) {
        continue;
      }
      debugLogger.accept(
          "方向判定失败: stage=FINAL train="
              + trainName
              + " route="
              + routeId.map(RouteId::value).orElse("-")
              + " purpose="
              + purpose.name()
              + " key="
              + resource.key()
              + " window="
              + directionState(windowDirections, resource.key())
              + " plan="
              + directionState(planDirections, resource.key())
              + " finalSource=NONE");
    }
  }

  private String directionState(CorridorDirectionResolution resolution, String key) {
    if (resolution.blockedKeys().contains(key)) {
      return "BLOCKED";
    }
    CorridorDirection direction = resolution.directions().get(key);
    return direction == null ? "UNRESOLVED" : direction.name();
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
      RailGraphCorridorInfo info,
      List<NodeId> pathNodes,
      SemanticCorridorDirectionResolver.PathAxisIndex pathAxisIndex,
      NodeId from,
      NodeId to) {
    if (info == null) {
      return DirectionResolution.unresolved();
    }
    return resolveDirectionalConflict(
        info.left(), info.right(), info.nodes(), List.of(), pathNodes, pathAxisIndex, from, to);
  }

  private DirectionResolution resolveSectionDirection(
      SingleLineSectionInfo info,
      List<NodeId> pathNodes,
      SemanticCorridorDirectionResolver.PathAxisIndex pathAxisIndex,
      NodeId from,
      NodeId to) {
    if (info == null) {
      return DirectionResolution.unresolved();
    }
    return resolveDirectionalConflict(
        info.left(),
        info.right(),
        info.nodes(),
        info.boundaries(),
        pathNodes,
        pathAxisIndex,
        from,
        to);
  }

  private DirectionResolution resolveDirectionalConflict(
      NodeId left,
      NodeId right,
      List<NodeId> orderedNodes,
      List<NodeId> boundaryNodes,
      List<NodeId> pathNodes,
      SemanticCorridorDirectionResolver.PathAxisIndex pathAxisIndex,
      NodeId from,
      NodeId to) {
    SemanticCorridorDirectionResolver.Result semantic =
        semanticDirectionResolver.resolve(
            semanticResourceNodes(left, right, orderedNodes, boundaryNodes),
            pathAxisIndex,
            from,
            to);
    if (semantic.resolved()) {
      return DirectionResolution.resolved(semantic.direction());
    }
    CorridorDirection byCorridor = resolveDirectionByCorridorNodes(orderedNodes, from, to);
    if (semantic.blocksLegacyFallback()) {
      if (byCorridor != CorridorDirection.UNKNOWN
          && canUseOrderedCorridorFallbackAfterSemanticBlock(
              semantic, left, right, orderedNodes, from, to)) {
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
   * <p>普通 hub gap 的 {@code AMBIGUOUS} 必须继续 fail-closed。两个窄边界例外可以采用同一 corridor/section 的有序轴：显式
   * Station/Depot throat；以及 canonical corridor 端点与其首个 INTERVAL 锚点之间的边。后一种覆盖“汇入 switcher →
   * 区间首节点”，但不会把 corridor 中部的 hub gap 或“站台 → hub”放宽。
   */
  private boolean canUseOrderedCorridorFallbackAfterSemanticBlock(
      SemanticCorridorDirectionResolver.Result semantic,
      NodeId left,
      NodeId right,
      List<NodeId> orderedNodes,
      NodeId from,
      NodeId to) {
    return semantic.status() == SemanticCorridorDirectionResolver.Status.AMBIGUOUS
        && (touchesTerminalThroat(from, to)
            || isCanonicalBoundaryIntervalEdge(left, orderedNodes, from, to)
            || isCanonicalBoundaryIntervalEdge(right, orderedNodes, from, to));
  }

  private boolean isCanonicalBoundaryIntervalEdge(
      NodeId boundary, List<NodeId> orderedNodes, NodeId from, NodeId to) {
    if (boundary == null || orderedNodes == null || from == null || to == null) {
      return false;
    }
    NodeId neighbor;
    if (boundary.equals(from)) {
      neighbor = to;
    } else if (boundary.equals(to)) {
      neighbor = from;
    } else {
      return false;
    }
    int boundaryIndex = indexOfNode(orderedNodes, boundary);
    int neighborIndex = indexOfNode(orderedNodes, neighbor);
    if (boundaryIndex < 0 || neighborIndex < 0 || Math.abs(boundaryIndex - neighborIndex) != 1) {
      return false;
    }
    return graph
        .findNode(neighbor)
        .flatMap(RailNode::waypointMetadata)
        .map(WaypointMetadata::kind)
        .filter(kind -> kind == WaypointKind.INTERVAL)
        .isPresent();
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

  /** 从入口安全点到清出点的有向原子授权窗口。 */
  private record AtomicAuthorityWindow(
      boolean applicable,
      boolean resolved,
      Optional<NodeId> entry,
      Optional<NodeId> exit,
      int exitIndex) {

    private AtomicAuthorityWindow {
      entry = entry == null ? Optional.empty() : entry;
      exit = exit == null ? Optional.empty() : exit;
      exitIndex = Math.max(-1, exitIndex);
    }

    private static AtomicAuthorityWindow notApplicable() {
      return new AtomicAuthorityWindow(false, false, Optional.empty(), Optional.empty(), -1);
    }

    private static AtomicAuthorityWindow unresolved(NodeId entry) {
      return new AtomicAuthorityWindow(
          true, false, Optional.ofNullable(entry), Optional.empty(), -1);
    }

    private static AtomicAuthorityWindow resolved(NodeId entry, NodeId exit, int exitIndex) {
      return new AtomicAuthorityWindow(
          true, true, Optional.ofNullable(entry), Optional.ofNullable(exit), exitIndex);
    }
  }

  /** 当前 hard window 触及冲突区后的清出授权窗口。 */
  private record ConflictExitAuthorityWindow(
      boolean applicable, boolean resolved, int requiredNodeCount, Set<String> conflictKeys) {

    private ConflictExitAuthorityWindow {
      requiredNodeCount = Math.max(0, requiredNodeCount);
      conflictKeys = conflictKeys == null ? Set.of() : Set.copyOf(conflictKeys);
    }

    private static ConflictExitAuthorityWindow notApplicable() {
      return new ConflictExitAuthorityWindow(false, false, 0, Set.of());
    }

    private static ConflictExitAuthorityWindow unresolved(Set<String> conflictKeys) {
      return new ConflictExitAuthorityWindow(true, false, 0, conflictKeys);
    }

    private static ConflictExitAuthorityWindow resolved(
        int requiredNodeCount, Set<String> conflictKeys) {
      return new ConflictExitAuthorityWindow(true, true, requiredNodeCount, conflictKeys);
    }
  }

  private record CurrentStep(List<NodeId> nodes, RailEdge edge) {}
}
