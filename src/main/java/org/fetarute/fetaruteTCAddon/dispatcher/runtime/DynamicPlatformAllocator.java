package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher.DynamicSpec;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;

/**
 * 动态站台分配器。
 *
 * <p>在列车进入 {@link #ALLOCATION_EDGE_THRESHOLD} edges 的 DYNAMIC 授权窗口时，从候选轨道范围中选择一个可用站台，并把分配结果返回给
 * Dispatcher。
 *
 * <p>本类不直接写入 TrainCarts destination，也不申请占用资源；destination materialize 与发车授权由 {@link
 * RuntimeDispatchService} 编排，控车落地由 {@link RuntimeTrainController} 执行。
 *
 * <p>触发条件：
 *
 * <ul>
 *   <li>当前 RouteStop 为 DYNAMIC 类型
 *   <li>列车距离该站点 ≤ {@link #ALLOCATION_EDGE_THRESHOLD} 个 edges；若它就是下一 RouteStop，则允许越过该预选阈值
 *   <li>尚未为该站点分配过站台（避免重复分配）
 * </ul>
 *
 * <p>分配策略：
 *
 * <ul>
 *   <li>遍历 [fromTrack, toTrack] 范围内的所有候选站台
 *   <li>优先选择未被占用的站台
 *   <li>若所有站台都被占用，不生成 materialized destination，由运行时保持在入口等待
 * </ul>
 *
 * <h2>物理先后</h2>
 *
 * <p>站台按列车<b>实际能到达的先后</b>给出：若另一列正在等待同站容量的列车停在本车通往某候选站台的进站路中间，该站台对本车不可用——
 * 新订不给，已缓存的预订也在复核时撤回。否则后车订走最后一个空台后，前车没有台开不走、后车被前车挡着也到不了台，形成调度看不见的互卡（预订不是 claim，前车停因里没有 blocker）。
 *
 * <p>两车的位置都取物理位置（{@link RouteProgressRegistry#lastPassedGraphNode}），不取交路路径点：同一对路径点之间的车路径点相同，
 * 分不出先后。<b>任一方位置未知时本规则不生效</b>——拿路径点顶替会把身后的车误判成挡路者，两车可能互相拒绝；两边都是精确位置时，
 * 最短路长度对称，两车不可能互为对方进站路上的障碍。本规则只会撤回或拒绝预订，从不发放预订，也不触碰任何占用或授权。
 */
public final class DynamicPlatformAllocator {

  /**
   * 触发分配的 edge 阈值。
   *
   * <p>该窗口必须覆盖运行时可能写入的最大 hard-authority 窗口；否则 6-8 edges 内的声明占位节点可能先进入可写授权链。
   */
  public static final int ALLOCATION_EDGE_THRESHOLD = 8;

  /** 候选轨道遍历的安全上限，防止 spec 范围过大导致卡服。 */
  private static final int MAX_TRACK_CANDIDATES = 20;

  private final RouteDefinitionCache routeDefinitions;
  private final OccupancyManager occupancyManager;
  private final Consumer<String> debugLogger;

  /** 容量等待登记；为 null 时物理先后规则不生效。 */
  private final DynamicCapacityWaitRegistry capacityWaits;

  /** 列车物理位置（最后经过的图节点）；为 null 或返回空时视为位置未知。 */
  private final Function<String, Optional<NodeId>> physicalPosition;

  /** 物理先后裁定的最近一次输出签名（按请求列车），只在变化时输出，体量受在场车数限制。 */
  private final Map<String, String> orderWithheldReported = new ConcurrentHashMap<>();

  /** 已分配记录：trainName -> (routeId:stopSequence) -> 带定义证据的分配。 */
  private final Map<String, Map<String, CachedAllocation>> allocations = new ConcurrentHashMap<>();

  /** 列车改名时保护跨两个 owner key 的原子迁移，避免把并发容器本身作为 monitor。 */
  private final Object allocationMigrationLock = new Object();

  /** 站台分配决策的去重键集合；受拓扑限制，不随车数增长。 */
  private final java.util.Set<String> allocationDecisionReported =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "OccupancyManager 是运行时共享服务句柄；动态站台分配必须读取同一占用状态，不能复制。")
  public DynamicPlatformAllocator(
      RouteDefinitionCache routeDefinitions,
      OccupancyManager occupancyManager,
      Consumer<String> debugLogger) {
    this(routeDefinitions, occupancyManager, debugLogger, null, null);
  }

  /**
   * 带物理先后规则的构造器。
   *
   * @param capacityWaits 运行时的容量等待登记
   * @param physicalPosition 按列车名查询物理位置（最后经过的图节点）
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "OccupancyManager 与容量等待登记都是运行时共享状态；选台必须读取同一份，不能复制。")
  DynamicPlatformAllocator(
      RouteDefinitionCache routeDefinitions,
      OccupancyManager occupancyManager,
      Consumer<String> debugLogger,
      DynamicCapacityWaitRegistry capacityWaits,
      Function<String, Optional<NodeId>> physicalPosition) {
    this.routeDefinitions = Objects.requireNonNull(routeDefinitions, "routeDefinitions");
    this.occupancyManager = occupancyManager;
    this.debugLogger = debugLogger != null ? debugLogger : s -> {};
    this.capacityWaits = capacityWaits;
    this.physicalPosition = physicalPosition;
  }

  /**
   * 检查并尝试为列车分配动态站台。
   *
   * <p>该入口保留旧版 {@link Optional} 合约：没有待解析的 DYNAMIC stop 与 DYNAMIC stop 当前被阻塞都会返回 {@link
   * Optional#empty()}。调用方不能据此判定可以回退 route 声明节点；本包运行时编排需要区分两者时应使用三态解析结果。
   *
   * @param trainName 列车名称
   * @param route 当前 RouteDefinition
   * @param currentIndex 当前 waypoint 索引
   * @param graph 调度图（用于计算 edge 距离）
   * @param currentNode 列车当前所在节点
   * @return 已选中的分配结果；未触发或被阻塞时返回 empty
   */
  public Optional<AllocationResult> tryAllocate(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      RailGraph graph,
      NodeId currentNode) {
    return resolveAllocation(trainName, route, currentIndex, graph, currentNode, Optional.empty())
        .selected();
  }

  /**
   * 检查并尝试为列车分配动态站台（含方向优选）。
   *
   * <p>该入口保留旧版 {@link Optional} 合约：没有待解析的 DYNAMIC stop 与 DYNAMIC stop 当前被阻塞都会返回 {@link
   * Optional#empty()}。调用方不能据此判定可以回退 route 声明节点；本包运行时编排需要区分两者时应使用三态解析结果。
   *
   * @param trainName 列车名称
   * @param route 当前 RouteDefinition
   * @param currentIndex 当前 waypoint 索引
   * @param graph 调度图（用于计算 edge 距离）
   * @param currentNode 列车当前所在节点
   * @param trainDirection 列车实际运行方向（优先使用；缺失时从 waypoints 推算）
   * @return 已选中的分配结果；未触发或被阻塞时返回 empty
   */
  public Optional<AllocationResult> tryAllocate(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      RailGraph graph,
      NodeId currentNode,
      Optional<BlockFace> trainDirection) {
    return resolveAllocation(trainName, route, currentIndex, graph, currentNode, trainDirection)
        .selected();
  }

  /**
   * 解析当前授权窗口内的 DYNAMIC 站台。
   *
   * <p>与兼容入口 {@link #tryAllocate(String, RouteDefinition, int, RailGraph, NodeId, Optional)}
   * 不同，本方法保留“当前没有 DYNAMIC stop”和“确有 DYNAMIC stop 但没有容量”的差异。运行时必须对后者 fail-closed，禁止退回 route
   * 声明节点继续申请咽喉进路。
   *
   * @param trainName 列车名称
   * @param route 当前线路
   * @param currentIndex 当前 waypoint 索引
   * @param graph 调度图
   * @param currentNode 当前图节点
   * @param trainDirection 实际运行方向
   * @return DYNAMIC 三态解析结果
   */
  DynamicResolution<AllocationResult> resolveAllocation(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      RailGraph graph,
      NodeId currentNode,
      Optional<BlockFace> trainDirection) {
    if (trainName == null || trainName.isBlank() || route == null) {
      return DynamicResolution.notApplicable("invalid-allocation-context");
    }

    List<NodeId> waypoints = route.waypoints();
    pruneStaleRouteAllocations(trainName, route);
    if (waypoints.isEmpty() || currentIndex < 0 || currentIndex >= waypoints.size()) {
      return DynamicResolution.notApplicable("route-index-out-of-range");
    }
    if (graph == null || currentNode == null || occupancyManager == null) {
      String reason =
          graph == null
              ? "graph-snapshot-missing"
              : currentNode == null ? "current-node-missing" : "occupancy-manager-unavailable";
      return hasDynamicStopInAllocationWindow(route, currentIndex)
          ? DynamicResolution.blocked(reason)
          : DynamicResolution.notApplicable(reason);
    }
    Optional<BlockFace> effectiveTrainDirection =
        trainDirection == null ? Optional.empty() : trainDirection;

    // 查找下一个 DYNAMIC stop
    for (int lookAhead = 1; lookAhead <= ALLOCATION_EDGE_THRESHOLD + 2; lookAhead++) {
      int targetIndex = currentIndex + lookAhead;
      if (targetIndex >= waypoints.size()) {
        break;
      }

      Optional<RouteStop> stopOpt = routeDefinitions.findStop(route.id(), targetIndex);
      if (stopOpt.isEmpty()) {
        continue;
      }

      RouteStop stop = stopOpt.get();
      Optional<DynamicSpec> specOpt = DynamicStopMatcher.parseDynamicSpec(stop);
      if (specOpt.isEmpty()) {
        if (DynamicStopMatcher.isDynamicStop(stop)) {
          debugLogger.accept(
              "DYNAMIC 分配阻塞: 声明格式无效 (train="
                  + trainName
                  + ", route="
                  + route.id().value()
                  + ", stopIndex="
                  + targetIndex
                  + ")");
          return DynamicResolution.blocked("invalid-dynamic-spec", OptionalInt.of(targetIndex));
        }
        continue;
      }

      // 检查是否已分配
      String allocationKey = route.id().value() + ":" + stop.sequence();
      Map<String, CachedAllocation> trainAllocations =
          allocations.computeIfAbsent(
              TrainNameNormalizer.normalizeKey(trainName), k -> new ConcurrentHashMap<>());
      CachedAllocation cached = trainAllocations.get(allocationKey);
      if (cached != null) {
        DynamicSpec currentSpec = specOpt.get();
        if (isCachedAllocationValid(
            cached,
            route.id(),
            targetIndex,
            stop.sequence(),
            currentSpec,
            graph,
            currentNode,
            trainName)) {
          return DynamicResolution.selected(
              new AllocationResult(
                  trainName, route.id(), targetIndex, currentSpec, cached.allocatedNode()));
        }
        trainAllocations.remove(allocationKey, cached);
        debugLogger.accept(
            "DYNAMIC 缓存分配失效: 定义、图、可达性、占用或预订证据已变化 (train="
                + trainName
                + ", allocated="
                + cached.allocatedNode().value()
                + ")");
      }

      // 计算到目标的 edge 距离。紧邻 DYNAMIC 是当前 route leg 的唯一目标；若它本身
      // 比预选窗口长，继续等待永远不会让 routeIndex 前进到更近的位置，必须在这里
      // 先完成候选选择。选择仍只建立预订，实际 NODE/EDGE 授权仍由后续信号链独立裁定。
      int edgeDistance = calculateEdgeDistance(graph, waypoints, currentIndex, targetIndex);
      boolean immediateDynamicTarget = targetIndex == currentIndex + 1;
      if (!immediateDynamicTarget && edgeDistance > ALLOCATION_EDGE_THRESHOLD) {
        continue;
      }
      if (immediateDynamicTarget && edgeDistance > ALLOCATION_EDGE_THRESHOLD) {
        debugLogger.accept(
            "DYNAMIC 紧邻目标越过预选距离: train="
                + trainName
                + ", route="
                + route.id().value()
                + ", stopIndex="
                + targetIndex
                + ", edgeDistance="
                + edgeDistance
                + ", previewThreshold="
                + ALLOCATION_EDGE_THRESHOLD);
      }

      // 执行分配
      DynamicSpec spec = specOpt.get();
      Optional<NodeId> allocated =
          allocatePlatform(
              trainName,
              spec,
              graph,
              waypoints,
              currentIndex,
              currentNode,
              effectiveTrainDirection);
      if (allocated.isEmpty()) {
        debugLogger.accept(
            "DYNAMIC 分配失败: 无可用站台 (train=" + trainName + ", spec=" + formatSpec(spec) + ")");
        return DynamicResolution.blocked("no-available-platform", OptionalInt.of(targetIndex));
      }

      NodeId allocatedNode = allocated.get();
      trainAllocations.put(
          allocationKey,
          new CachedAllocation(route.id(), targetIndex, stop.sequence(), spec, allocatedNode));

      debugLogger.accept(
          "DYNAMIC 分配成功: train="
              + trainName
              + ", spec="
              + formatSpec(spec)
              + ", allocated="
              + allocatedNode.value()
              + ", edgeDistance="
              + edgeDistance);

      return DynamicResolution.selected(
          new AllocationResult(trainName, route.id(), targetIndex, spec, allocatedNode));
    }

    return DynamicResolution.notApplicable("no-dynamic-stop-in-allocation-window");
  }

  /**
   * 按当前 route 定义清理失效的 DYNAMIC 预订。
   *
   * <p>同一个 RouteId 在 reload 后可能删短、移除 DYNAMIC、修正 spec，或重排 stop sequence。缓存 key 不能单独证明定义仍相同，因此按
   * routeId + stopIndex 重新读取当前 stop；任一证据变化都立即释放旧预订。
   */
  private void pruneStaleRouteAllocations(String trainName, RouteDefinition route) {
    if (trainName == null || route == null) {
      return;
    }
    String trainKey = TrainNameNormalizer.normalizeKey(trainName);
    Map<String, CachedAllocation> trainAllocations = allocations.get(trainKey);
    if (trainAllocations == null || trainAllocations.isEmpty()) {
      return;
    }
    for (Map.Entry<String, CachedAllocation> entry : new ArrayList<>(trainAllocations.entrySet())) {
      CachedAllocation cached = entry.getValue();
      if (cached == null || !route.id().equals(cached.routeId())) {
        continue;
      }
      boolean current =
          cached.stopIndex() >= 0
              && cached.stopIndex() < route.waypoints().size()
              && routeDefinitions
                  .findStop(route.id(), cached.stopIndex())
                  .filter(stop -> stop.sequence() == cached.stopSequence())
                  .flatMap(DynamicStopMatcher::parseDynamicSpec)
                  .filter(cached.spec()::equals)
                  .filter(spec -> DynamicStopMatcher.matches(cached.allocatedNode(), spec))
                  .isPresent();
      if (!current && trainAllocations.remove(entry.getKey(), cached)) {
        debugLogger.accept(
            "DYNAMIC 缓存分配清理: route 定义已变化 train="
                + trainName
                + ", route="
                + route.id().value()
                + ", stopIndex="
                + cached.stopIndex()
                + ", allocated="
                + cached.allocatedNode().value());
      }
    }
    if (trainAllocations.isEmpty()) {
      allocations.remove(trainKey, trainAllocations);
    }
  }

  /**
   * 判断当前分配窗口内是否声明了 DYNAMIC stop。
   *
   * <p>图快照缺失时解析器仍需区分“没有 DYNAMIC”与“存在 DYNAMIC 但无法安全选台”；这里只读取 route stop 定义，不尝试选择候选或产生副作用。
   */
  boolean hasDynamicStopInAllocationWindow(RouteDefinition route, int currentIndex) {
    if (route == null
        || route.waypoints().isEmpty()
        || currentIndex < 0
        || currentIndex >= route.waypoints().size()) {
      return false;
    }
    for (int lookAhead = 1; lookAhead <= ALLOCATION_EDGE_THRESHOLD + 2; lookAhead++) {
      int targetIndex = currentIndex + lookAhead;
      if (targetIndex >= route.waypoints().size()) {
        return false;
      }
      if (routeDefinitions
          .findStop(route.id(), targetIndex)
          .map(DynamicStopMatcher::isDynamicStop)
          .orElse(false)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 获取已分配的站台（用于 RouteDefinition 节点覆盖）。
   *
   * @param trainName 列车名称
   * @param routeId 路线 ID
   * @param stopSequence 停靠点序号
   * @return 已分配的 NodeId
   */
  public Optional<NodeId> getAllocation(String trainName, RouteId routeId, int stopSequence) {
    if (trainName == null || routeId == null) {
      return Optional.empty();
    }
    Map<String, CachedAllocation> trainAllocations =
        allocations.get(TrainNameNormalizer.normalizeKey(trainName));
    if (trainAllocations == null) {
      return Optional.empty();
    }
    String key = routeId.value() + ":" + stopSequence;
    return Optional.ofNullable(trainAllocations.get(key)).map(CachedAllocation::allocatedNode);
  }

  /**
   * 释放已经越过的 DYNAMIC stop 预订。
   *
   * <p>只删除同一 route 且 stopIndex 小于当前抵达索引的缓存；当前站台在列车尚未离开时继续保留，未来 stop 的预选也不会被误删。
   */
  void releaseCompletedAllocations(String trainName, RouteId routeId, int currentIndex) {
    if (trainName == null || routeId == null || currentIndex < 0) {
      return;
    }
    String trainKey = TrainNameNormalizer.normalizeKey(trainName);
    Map<String, CachedAllocation> trainAllocations = allocations.get(trainKey);
    if (trainAllocations == null) {
      return;
    }
    trainAllocations
        .entrySet()
        .removeIf(
            entry ->
                entry.getValue() != null
                    && routeId.equals(entry.getValue().routeId())
                    && entry.getValue().stopIndex() < currentIndex);
    if (trainAllocations.isEmpty()) {
      allocations.remove(trainKey, trainAllocations);
    }
  }

  /** 判断逻辑列车是否仍持有 DYNAMIC 站台预订。 */
  boolean hasAllocations(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return false;
    }
    Map<String, CachedAllocation> trainAllocations =
        allocations.get(TrainNameNormalizer.normalizeKey(trainName));
    return trainAllocations != null && !trainAllocations.isEmpty();
  }

  /**
   * 原子迁移列车名对应的 DYNAMIC 站台预订。
   *
   * <p>目标 owner 已有预订时拒绝迁移，调用方可以与其它运行时注册表一起回滚。没有旧预订视为成功。
   */
  boolean migrateAllocations(String previousTrainName, String currentTrainName) {
    if (previousTrainName == null
        || previousTrainName.isBlank()
        || currentTrainName == null
        || currentTrainName.isBlank()) {
      return false;
    }
    String previousKey = TrainNameNormalizer.normalizeKey(previousTrainName);
    String currentKey = TrainNameNormalizer.normalizeKey(currentTrainName);
    if (previousKey.equals(currentKey)) {
      return true;
    }
    orderWithheldReported.remove(previousKey);
    synchronized (allocationMigrationLock) {
      Map<String, CachedAllocation> previous = allocations.get(previousKey);
      if (previous == null || previous.isEmpty()) {
        return true;
      }
      if (allocations.containsKey(currentKey) || !allocations.remove(previousKey, previous)) {
        return false;
      }
      Map<String, CachedAllocation> conflict = allocations.putIfAbsent(currentKey, previous);
      if (conflict == null) {
        return true;
      }
      allocations.putIfAbsent(previousKey, previous);
      return false;
    }
  }

  /**
   * 清除列车的所有分配记录（列车销毁/完成运行时调用）。
   *
   * @param trainName 列车名称
   */
  public void clearAllocations(String trainName) {
    if (trainName != null) {
      allocations.remove(TrainNameNormalizer.normalizeKey(trainName));
      orderWithheldReported.remove(TrainNameNormalizer.normalizeKey(trainName));
    }
  }

  /**
   * 直接从 DynamicSpec 分配站台（用于 DSTY DYNAMIC depot 等场景）。
   *
   * <p>不依赖 RouteDefinition waypoints，直接根据 spec 和 currentNode 进行分配。
   *
   * @param trainName 列车名称
   * @param spec DYNAMIC 规范
   * @param graph 调度图
   * @param currentNode 当前节点
   * @return 分配的 NodeId，或 empty
   */
  public Optional<NodeId> allocateDirect(
      String trainName, DynamicSpec spec, RailGraph graph, NodeId currentNode) {
    if (trainName == null || spec == null || graph == null || currentNode == null) {
      return Optional.empty();
    }
    // 直接调用分配逻辑，无 waypoints 和方向信息
    return allocatePlatform(
        trainName,
        spec,
        graph,
        java.util.Collections.emptyList(),
        -1,
        currentNode,
        Optional.empty());
  }

  /**
   * 从 DYNAMIC 范围内选择可用站台。
   *
   * <p>当存在多个可用候选时，使用“方向优选”避免在 X 字渡线/多道岔区域走出 360° 折回：
   *
   * <ol>
   *   <li>运行方向：优先使用列车实际 {@code BlockFace}；fallback 为 {@code prevNode -> currentNode} 的 2D 向量
   *   <li>候选方向：{@code currentNode -> guideNode} 的方向向量（guideNode 为路径上首个非 SWITCHER 节点）
   *   <li>用点积作为相似度，选择得分最高者
   * </ol>
   */
  private Optional<NodeId> allocatePlatform(
      String trainName,
      DynamicSpec spec,
      RailGraph graph,
      List<NodeId> routeWaypoints,
      int currentIndex,
      NodeId currentNode,
      Optional<BlockFace> trainDirection) {
    if (spec == null || graph == null || routeWaypoints == null || currentNode == null) {
      return Optional.empty();
    }

    // 计算列车运行方向：优先使用列车实际 BlockFace；fallback 为 prev -> current
    Vector travelDir = trainDirection.map(this::blockFaceToVector2d).orElse(null);
    if (travelDir == null && currentIndex > 0 && currentIndex - 1 < routeWaypoints.size()) {
      NodeId prevNode = routeWaypoints.get(currentIndex - 1);
      travelDir =
          computeDirection2d(getNodePosition(graph, prevNode), getNodePosition(graph, currentNode));
    }

    RailGraphPathFinder pathFinder = new RailGraphPathFinder();
    List<ApproachCandidate> candidates = new ArrayList<>();

    for (int track : candidateTracks(spec, graph)) {
      NodeId candidate =
          NodeId.of(
              spec.operatorCode() + ":" + spec.nodeType() + ":" + spec.nodeName() + ":" + track);
      if (graph.findNode(candidate).isEmpty()) {
        continue;
      }

      Optional<RailGraphPath> pathOpt =
          pathFinder.shortestPath(
              graph, currentNode, candidate, RailGraphPathFinder.Options.shortestDistance());
      if (pathOpt.isEmpty()) {
        continue;
      }

      boolean physicallyFree = !isExternallyOccupied(candidate, trainName);
      boolean free =
          physicallyFree
              && !isReservedByOtherTrain(candidate, trainName)
              && waiterAheadOnApproach(trainName, candidate, graph).isEmpty();
      candidates.add(new ApproachCandidate(candidate, free, pathOpt.get().nodes()));
    }

    if (candidates.isEmpty()) {
      return Optional.empty();
    }

    List<ApproachCandidate> freeCandidates =
        candidates.stream().filter(ApproachCandidate::free).toList();
    if (freeCandidates.isEmpty()) {
      debugLogger.accept(
          "DYNAMIC 分配阻塞: 候选站台均被占用 train=" + trainName + ", spec=" + formatSpec(spec));
      return Optional.empty();
    }
    ApproachCandidate chosen =
        selectBestCandidateByDirection(trainName, currentNode, travelDir, freeCandidates, graph);
    traceAllocationDecision(spec, candidates, freeCandidates, chosen);
    return Optional.ofNullable(chosen != null ? chosen.nodeId : null);
  }

  /**
   * 解析 DYNAMIC 规范实际要枚举的股道号。
   *
   * <p>声明了范围就按范围；<b>未声明范围时枚举该站在图上实际存在的全部股道</b>。
   *
   * <p>此前这里直接用 {@code spec.fromTrack()..spec.toTrack()}，而 {@link
   * DynamicStopMatcher#parseDynamicSpec} 对未声明范围的规范返回 {@code from=1, to=1,
   * unbounded=true}——解析器把"未声明"标了出来，并在注释里写明 "由调用方按图上实际存在的股道枚举"，但本分配器**从未读过 {@code unbounded()}**
   * （改动前全文 0 处引用）。于是 {@code DYNAMIC:SURC:S:PPK} 退化成只看 1 号股道， 与写死 {@code S:PPK:1} 毫无区别。
   *
   * <p>第十六轮实服证据：分配器自报 {@code DYNAMIC 分配阻塞: 候选站台均被占用 spec=SURC:S:PPK:1}——PPK 明明有 1/2 两个站台，
   * 候选集合里却只有一个。
   *
   * <p>枚举口径与 {@code RuntimeDispatchService.dynamicCandidateTracks} 保持一致： 按前缀匹配图上节点、只取纯数字股道段。{@link
   * #MAX_TRACK_CANDIDATES} 仍作安全上限。
   */
  private List<Integer> candidateTracks(DynamicSpec spec, RailGraph graph) {
    if (!spec.unbounded()) {
      List<Integer> declared = new ArrayList<>();
      int maxTrack = Math.min(spec.toTrack(), spec.fromTrack() + MAX_TRACK_CANDIDATES - 1);
      for (int track = spec.fromTrack(); track <= maxTrack; track++) {
        declared.add(track);
      }
      return declared;
    }
    String prefix =
        spec.operatorCode().trim()
            + ":"
            + spec.nodeType().trim()
            + ":"
            + spec.nodeName().trim()
            + ":";
    java.util.TreeSet<Integer> discovered = new java.util.TreeSet<>();
    if (graph != null) {
      for (org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode node : graph.nodes()) {
        if (node == null || node.id() == null || node.id().value() == null) {
          continue;
        }
        String value = node.id().value();
        if (!value.regionMatches(true, 0, prefix, 0, prefix.length())) {
          continue;
        }
        String trackPart = value.substring(prefix.length());
        if (trackPart.isEmpty() || trackPart.indexOf(':') >= 0) {
          continue;
        }
        try {
          int track = Integer.parseInt(trackPart);
          if (track >= 1) {
            discovered.add(track);
          }
        } catch (NumberFormatException ignored) {
          // 非数字股道段不是候选。
        }
        if (discovered.size() >= MAX_TRACK_CANDIDATES) {
          break;
        }
      }
    }
    // 图上一个都发现不了时退回声明值，行为与改动前一致。
    return discovered.isEmpty() ? List.of(spec.fromTrack()) : new ArrayList<>(discovered);
  }

  /**
   * 判断站台是否被其他逻辑列车占用。
   *
   * <p>同一列车在进路授权或折返交接后可能已经持有目标节点 claim；该 claim 是已选目标的安全依据，不应反过来阻塞本车。若占用管理器只报告“已占用”却无法提供
   * claim，则按外部占用 fail-closed。
   */
  private boolean isExternallyOccupied(NodeId candidate, String trainName) {
    if (candidate == null || occupancyManager == null) {
      return false;
    }
    OccupancyResource resource = OccupancyResource.forNode(candidate);
    boolean selfClaimSeen = false;
    List<OccupancyClaim> claims = occupancyManager.snapshotClaims();
    for (OccupancyClaim claim : claims == null ? List.<OccupancyClaim>of() : claims) {
      if (claim == null || !resource.equals(claim.resource())) {
        continue;
      }
      if (!TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
        return true;
      }
      selfClaimSeen = true;
    }
    if (!occupancyManager.isNodeOccupied(candidate)) {
      return false;
    }
    if (selfClaimSeen) {
      return false;
    }
    return occupancyManager
        .getClaim(resource)
        .map(claim -> !TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName))
        .orElse(true);
  }

  private boolean isReservedByOtherTrain(NodeId candidate, String trainName) {
    if (candidate == null) {
      return false;
    }
    for (Map.Entry<String, Map<String, CachedAllocation>> entry : allocations.entrySet()) {
      if (entry == null
          || entry.getKey() == null
          || TrainNameNormalizer.sameLogicalTrain(entry.getKey(), trainName)) {
        continue;
      }
      Map<String, CachedAllocation> trainAllocations = entry.getValue();
      if (trainAllocations != null
          && trainAllocations.values().stream()
              .filter(Objects::nonNull)
              .map(CachedAllocation::allocatedNode)
              .anyMatch(candidate::equals)) {
        return true;
      }
    }
    return false;
  }

  private boolean isCachedAllocationValid(
      CachedAllocation cached,
      RouteId routeId,
      int targetIndex,
      int stopSequence,
      DynamicSpec currentSpec,
      RailGraph graph,
      NodeId currentNode,
      String trainName) {
    if (cached == null
        || !cached.routeId().equals(routeId)
        || cached.stopIndex() != targetIndex
        || cached.stopSequence() != stopSequence
        || !cached.spec().equals(currentSpec)
        || !DynamicStopMatcher.matches(cached.allocatedNode(), currentSpec)
        || graph.findNode(cached.allocatedNode()).isEmpty()
        || new RailGraphPathFinder()
            .shortestPath(
                graph,
                currentNode,
                cached.allocatedNode(),
                RailGraphPathFinder.Options.shortestDistance())
            .isEmpty()) {
      return false;
    }
    return !isExternallyOccupied(cached.allocatedNode(), trainName)
        && !isReservedByOtherTrain(cached.allocatedNode(), trainName)
        && waiterAheadOnApproach(trainName, cached.allocatedNode(), graph).isEmpty();
  }

  /**
   * 物理先后规则：找出一列停在本车通往 {@code candidate} 的进站路中间、且正在等待同一站台容量的其它列车。
   *
   * <p>规则与取位口径见类注释"物理先后"一节。进站路径从本车物理位置起算，只看中间节点：起点是本车自己，终点是站台本身（站台被占由 {@link #isExternallyOccupied}
   * 负责）。
   *
   * @return 挡在前面的等待列车名；没有、或任一方位置未知时为空
   */
  private Optional<String> waiterAheadOnApproach(
      String trainName, NodeId candidate, RailGraph graph) {
    if (capacityWaits == null || physicalPosition == null || candidate == null || graph == null) {
      return Optional.empty();
    }
    OccupancyResource resource = OccupancyResource.forNode(candidate);
    if (!capacityWaits.hasOtherWaiterFor(trainName, resource)) {
      return Optional.empty();
    }
    Optional<NodeId> position = physicalPosition.apply(trainName);
    if (position == null || position.isEmpty()) {
      return Optional.empty();
    }
    Optional<RailGraphPath> approach =
        new RailGraphPathFinder()
            .shortestPath(
                graph, position.get(), candidate, RailGraphPathFinder.Options.shortestDistance());
    if (approach.isEmpty() || approach.get().nodes().size() < 3) {
      return Optional.empty();
    }
    List<NodeId> nodes = approach.get().nodes();
    Optional<String> waiter =
        capacityWaits.waiterWithin(trainName, resource, nodes.subList(1, nodes.size() - 1));
    waiter.ifPresent(ahead -> reportOrderWithheld(trainName, candidate, ahead, position.get()));
    return waiter;
  }

  /**
   * 物理先后裁定拒绝或撤回预订时留痕。
   *
   * <p>它是规则生效的唯一证据，诊断门把它列为必留（{@code RuntimeDispatchDiagnosticGate}）；按 (候选, 挡路者, 起点) 变化去重，同一裁定在后车逐
   * tick 重评估时不重复输出。
   */
  private void reportOrderWithheld(
      String trainName, NodeId candidate, String waitingAhead, NodeId from) {
    String signature = candidate.value() + "|" + waitingAhead + "|" + from.value();
    String previous =
        orderWithheldReported.put(TrainNameNormalizer.normalizeKey(trainName), signature);
    if (signature.equals(previous)) {
      return;
    }
    debugLogger.accept(
        "DYNAMIC_PLATFORM_ORDER_WITHHELD train="
            + trainName
            + " candidate="
            + candidate.value()
            + " waitingAhead="
            + waitingAhead
            + " from="
            + from.value()
            + " reason=waiting-train-ahead-on-approach");
  }

  private record ApproachCandidate(NodeId nodeId, boolean free, List<NodeId> pathNodes) {
    private ApproachCandidate {
      Objects.requireNonNull(nodeId, "nodeId");
      Objects.requireNonNull(pathNodes, "pathNodes");
      pathNodes = List.copyOf(pathNodes);
    }
  }

  private record CachedAllocation(
      RouteId routeId, int stopIndex, int stopSequence, DynamicSpec spec, NodeId allocatedNode) {
    private CachedAllocation {
      Objects.requireNonNull(routeId, "routeId");
      Objects.requireNonNull(spec, "spec");
      Objects.requireNonNull(allocatedNode, "allocatedNode");
    }
  }

  /**
   * 记录一次站台分配的**候选与结果**。
   *
   * <p><b>为什么必须有这条</b>：第二十二轮实服里，MT 的折返终点 PPK 有两个站台与十字渡线， 但 {@code S:PPK:1} 与 {@code S:PPK:2} 的
   * claim 提及比接近 <b>4:1</b>，而 RVS/PTK/SPB 三站都均衡。 问题是我无法判定原因：是 2 号台很少空闲（那就是容量真的满了）， 还是它空着而分配器仍然偏爱 1
   * 号（那就是白白损失一半终端容量）。 <b>两者要采取的下一步完全相反。</b>
   *
   * <p>原有的 {@code DYNAMIC(approach) 方向优选} 只在候选 &gt; 1 时才打，而实服日志里它
   * <b>一条都没有</b>——这同时兼容于“从来只有一个候选”与“被诊断预算砍掉了”。 所以这条无条件记录，并把**空闲候选数**一并写出来。
   *
   * <p>体量：按 (站点范围, 候选数, 空闲数, 选中股道) 去重，受拓扑限制，不随车数增长。
   */
  private void traceAllocationDecision(
      DynamicSpec spec,
      List<ApproachCandidate> candidates,
      List<ApproachCandidate> freeCandidates,
      ApproachCandidate chosen) {
    if (spec == null || candidates == null || freeCandidates == null) {
      return;
    }
    String chosenNode = chosen == null ? "-" : chosen.nodeId.value();
    String key =
        formatSpec(spec) + "|" + candidates.size() + "|" + freeCandidates.size() + "|" + chosenNode;
    if (!allocationDecisionReported.add(key)) {
      return;
    }
    debugLogger.accept(
        "DYNAMIC_PLATFORM_DECISION spec="
            + formatSpec(spec)
            + " candidates="
            + candidates.size()
            + " free="
            + freeCandidates.size()
            + " chosen="
            + chosenNode
            + " freeNodes="
            + freeCandidates.stream().map(c -> c.nodeId.value()).toList());
  }

  private ApproachCandidate selectBestCandidateByDirection(
      String trainName,
      NodeId fromNode,
      Vector travelDir,
      List<ApproachCandidate> candidates,
      RailGraph graph) {
    if (candidates == null || candidates.isEmpty()) {
      return null;
    }
    // 无法计算方向时，保持 deterministic：按 track 顺序取第一个。
    if (travelDir == null) {
      return candidates.get(0);
    }
    Vector fromPos = getNodePosition(graph, fromNode);
    if (fromPos == null) {
      return candidates.get(0);
    }

    ApproachCandidate best = null;
    double bestScore = Double.NEGATIVE_INFINITY;
    for (ApproachCandidate cand : candidates) {
      NodeId guideNode = findPathGuideNode(cand.pathNodes, graph, fromNode);
      if (guideNode == null) {
        guideNode = cand.nodeId;
      }
      Vector guideDir = computeDirection2d(fromPos, getNodePosition(graph, guideNode));
      if (guideDir == null) {
        continue;
      }
      double score = travelDir.getX() * guideDir.getX() + travelDir.getZ() * guideDir.getZ();
      if (score > bestScore) {
        bestScore = score;
        best = cand;
      }
    }

    if (best != null && candidates.size() > 1) {
      debugLogger.accept(
          "DYNAMIC(approach) 方向优选: train="
              + trainName
              + ", from="
              + fromNode.value()
              + ", chosen="
              + best.nodeId.value()
              + ", candidates="
              + candidates.size());
    }

    return best != null ? best : candidates.get(0);
  }

  private NodeId findPathGuideNode(List<NodeId> pathNodes, RailGraph graph, NodeId fromNode) {
    if (pathNodes == null || pathNodes.size() < 2 || graph == null) {
      return null;
    }

    int fromIndex = -1;
    for (int i = 0; i < pathNodes.size(); i++) {
      if (fromNode.equals(pathNodes.get(i))) {
        fromIndex = i;
        break;
      }
    }
    int start = fromIndex >= 0 ? fromIndex + 1 : 1;
    for (int i = start; i < pathNodes.size(); i++) {
      NodeId node = pathNodes.get(i);
      if (node == null) {
        continue;
      }
      Optional<RailNode> railNodeOpt = graph.findNode(node);
      if (railNodeOpt.isEmpty()) {
        continue;
      }
      if (railNodeOpt.get().type() != NodeType.SWITCHER) {
        return node;
      }
    }
    return null;
  }

  private Vector getNodePosition(RailGraph graph, NodeId nodeId) {
    return graph.findNode(nodeId).map(RailNode::worldPosition).orElse(null);
  }

  private Vector computeDirection2d(Vector from, Vector to) {
    if (from == null || to == null) {
      return null;
    }
    double dx = to.getX() - from.getX();
    double dz = to.getZ() - from.getZ();
    double mag = Math.sqrt(dx * dx + dz * dz);
    if (mag < 1.0e-6) {
      return null;
    }
    return new Vector(dx / mag, 0.0, dz / mag);
  }

  /** 将 BlockFace 转换为归一化 2D 方向向量（X/Z 平面）。 */
  private Vector blockFaceToVector2d(BlockFace face) {
    if (face == null) {
      return null;
    }
    return switch (face) {
      case NORTH -> new Vector(0.0, 0.0, -1.0);
      case SOUTH -> new Vector(0.0, 0.0, 1.0);
      case EAST -> new Vector(1.0, 0.0, 0.0);
      case WEST -> new Vector(-1.0, 0.0, 0.0);
      default -> null;
    };
  }

  /** 计算从 currentIndex 到 targetIndex 的 edge 数量（沿 waypoints 路径）。 */
  private int calculateEdgeDistance(
      RailGraph graph, List<NodeId> waypoints, int currentIndex, int targetIndex) {
    if (currentIndex >= targetIndex) {
      return 0;
    }

    int edgeCount = 0;
    RailGraphPathFinder pathFinder = new RailGraphPathFinder();

    for (int i = currentIndex; i < targetIndex && i + 1 < waypoints.size(); i++) {
      NodeId from = waypoints.get(i);
      NodeId to = waypoints.get(i + 1);

      // 直接相邻的 waypoint 计为 1 edge
      // 若中间有路径则计算实际 edge 数
      var pathOpt =
          pathFinder.shortestPath(graph, from, to, RailGraphPathFinder.Options.shortestDistance());
      if (pathOpt.isPresent()) {
        edgeCount += Math.max(1, pathOpt.get().nodes().size() - 1);
      } else {
        edgeCount += 1; // fallback
      }
    }

    return edgeCount;
  }

  private static String formatSpec(DynamicSpec spec) {
    if (spec == null) {
      return "-";
    }
    String range =
        spec.fromTrack() == spec.toTrack()
            ? String.valueOf(spec.fromTrack())
            : "[" + spec.fromTrack() + ":" + spec.toTrack() + "]";
    return spec.operatorCode() + ":" + spec.nodeType() + ":" + spec.nodeName() + ":" + range;
  }

  /**
   * 分配结果。
   *
   * @param trainName 列车名称
   * @param routeId 路线 ID
   * @param stopIndex 停靠点索引
   * @param spec DYNAMIC 规范
   * @param allocatedNode 分配的具体站台 NodeId
   */
  public record AllocationResult(
      String trainName, RouteId routeId, int stopIndex, DynamicSpec spec, NodeId allocatedNode) {
    public AllocationResult {
      Objects.requireNonNull(trainName, "trainName");
      Objects.requireNonNull(routeId, "routeId");
      Objects.requireNonNull(spec, "spec");
      Objects.requireNonNull(allocatedNode, "allocatedNode");
    }
  }
}
