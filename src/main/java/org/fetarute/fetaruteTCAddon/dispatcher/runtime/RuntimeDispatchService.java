package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailTravelTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailTravelTimeModels;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.train.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.train.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestBuilder;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;

/**
 * 运行时调度控制：推进点触发下发目的地 + 信号等级变化时控车。
 *
 * <p>推进点逻辑用于“进入节点时下发下一跳 destination”；信号监测用于“运行中根据信号变化调整限速/制动”。
 *
 * <p>假设列车已写入 {@code FTA_OPERATOR_CODE/FTA_LINE_CODE/FTA_ROUTE_CODE}（或 {@code FTA_ROUTE_ID}）与 {@code
 * FTA_ROUTE_INDEX} tag，且 RouteDefinitionCache 已完成预热。
 */
public final class RuntimeDispatchService {

  private static final double TICKS_PER_SECOND = 20.0;

  /** 事件反射式占用的“长租约”窗口：用于避免依赖短续租带来的抖动。 */
  private static final Duration OCCUPANCY_HOLD_LEASE = Duration.ofDays(3650);

  private final OccupancyManager occupancyManager;
  private final RailGraphService railGraphService;
  private final RouteDefinitionCache routeDefinitions;
  private final RouteProgressRegistry progressRegistry;
  private final SignNodeRegistry signNodeRegistry;
  private final ConfigManager configManager;
  private final TrainConfigResolver trainConfigResolver;
  private final Consumer<String> debugLogger;

  public RuntimeDispatchService(
      OccupancyManager occupancyManager,
      RailGraphService railGraphService,
      RouteDefinitionCache routeDefinitions,
      RouteProgressRegistry progressRegistry,
      SignNodeRegistry signNodeRegistry,
      ConfigManager configManager,
      TrainConfigResolver trainConfigResolver,
      Consumer<String> debugLogger) {
    this.occupancyManager = Objects.requireNonNull(occupancyManager, "occupancyManager");
    this.railGraphService = Objects.requireNonNull(railGraphService, "railGraphService");
    this.routeDefinitions = Objects.requireNonNull(routeDefinitions, "routeDefinitions");
    this.progressRegistry = Objects.requireNonNull(progressRegistry, "progressRegistry");
    this.signNodeRegistry = Objects.requireNonNull(signNodeRegistry, "signNodeRegistry");
    this.configManager = Objects.requireNonNull(configManager, "configManager");
    this.trainConfigResolver = Objects.requireNonNull(trainConfigResolver, "trainConfigResolver");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
  }

  /**
   * 推进点触发：申请占用 → 下发目的地 → 发车/限速。
   *
   * <p>当前节点由牌子解析得到，下一跳从 RouteDefinition 中推导。
   */
  public void handleProgressTrigger(SignActionEvent event, SignNodeDefinition definition) {
    if (event == null || definition == null || !event.hasGroup()) {
      return;
    }
    com.bergerkiller.bukkit.tc.controller.MinecartGroup group = event.getGroup();
    RuntimeTrainHandle train = new TrainCartsRuntimeTrainHandle(group);
    TrainProperties properties = train.properties();
    String trainName = properties != null ? properties.getTrainName() : "unknown";
    Optional<UUID> routeUuidOpt = readRouteUuid(properties);
    Optional<RouteDefinition> routeOpt = resolveRouteDefinition(properties);
    if (routeOpt.isEmpty()) {
      debugLogger.accept(
          "调度推进失败: 未找到线路定义 train=" + trainName + " " + describeRouteTags(properties, routeUuidOpt));
      return;
    }
    RouteDefinition route = routeOpt.get();
    OptionalInt tagIndex =
        TrainTagHelper.readIntTag(properties, RouteProgressRegistry.TAG_ROUTE_INDEX)
            .map(OptionalInt::of)
            .orElse(OptionalInt.empty());
    int currentIndex = RouteIndexResolver.resolveCurrentIndex(route, tagIndex, definition.nodeId());
    if (currentIndex < 0) {
      debugLogger.accept(
          "调度推进跳过: 当前节点不在线路定义内 train="
              + trainName
              + " node="
              + definition.nodeId().value()
              + " route="
              + route.id().value());
      return;
    }
    NodeId currentNode = definition.nodeId();
    Optional<NodeId> nextTarget =
        currentIndex + 1 < route.waypoints().size()
            ? Optional.of(route.waypoints().get(currentIndex + 1))
            : Optional.empty();
    if (nextTarget.isEmpty()) {
      debugLogger.accept(
          "调度推进结束: 已到终点 train="
              + trainName
              + " node="
              + definition.nodeId().value()
              + " route="
              + route.id().value());
      return;
    }

    Instant now = Instant.now();
    Optional<RailGraph> graphOpt = resolveGraph(event);
    if (graphOpt.isEmpty()) {
      debugLogger.accept(
          "调度推进失败: 未找到调度图 train="
              + trainName
              + " node="
              + definition.nodeId().value()
              + " route="
              + route.id().value());
      return;
    }
    RailGraph graph = graphOpt.get();
    RailTravelTimeModel travelTimeModel =
        RailTravelTimeModels.constantSpeed(
            configManager.current().graphSettings().defaultSpeedBlocksPerSecond());
    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(
            graph, travelTimeModel, configManager.current().runtimeSettings().lookaheadEdges());
    Optional<OccupancyRequest> requestOpt =
        builder.buildFromNodes(
            trainName, Optional.ofNullable(route.id()), route.waypoints(), currentIndex, now);
    if (requestOpt.isEmpty()) {
      debugLogger.accept(
          "调度推进失败: 构建占用请求失败 train="
              + trainName
              + " node="
              + definition.nodeId().value()
              + " route="
              + route.id().value()
              + " reason="
              + diagnoseBuildFailure(
                  graph,
                  route,
                  currentIndex,
                  configManager.current().runtimeSettings().lookaheadEdges(),
                  travelTimeModel));
      return;
    }
    OccupancyRequest request = withHoldLease(requestOpt.get());
    releaseResourcesNotInRequest(trainName, request.resourceList(), now);
    OccupancyDecision decision = occupancyManager.canEnter(request);
    if (!decision.allowed()) {
      debugLogger.accept(
          "调度推进阻塞: train="
              + trainName
              + " node="
              + definition.nodeId().value()
              + " signal="
              + decision.signal()
              + " earliest="
              + decision.earliestTime()
              + " blockers="
              + summarizeBlockers(decision));
      progressRegistry.updateSignal(trainName, decision.signal(), now);
      applySignalToTrain(
          train, properties, decision.signal(), route, currentNode, nextTarget.get(), graph, false);
      return;
    }
    occupancyManager.acquire(request);
    progressRegistry.advance(
        trainName, routeUuidOpt.orElse(null), route, currentIndex, properties, now);
    progressRegistry.updateSignal(trainName, SignalAspect.PROCEED, now);

    String destinationName = resolveDestinationName(nextTarget.get());
    if (destinationName == null || destinationName.isBlank()) {
      return;
    }
    properties.clearDestinationRoute();
    properties.setDestination(destinationName);
    applySignalToTrain(
        train, properties, SignalAspect.PROCEED, route, currentNode, nextTarget.get(), graph, true);
  }

  /** 周期性信号检查：信号等级变化时调整速度/刹车。 */
  public void handleSignalTick(com.bergerkiller.bukkit.tc.controller.MinecartGroup group) {
    handleSignalTick(new TrainCartsRuntimeTrainHandle(group), false);
  }

  /**
   * 强制刷新信号控制（用于停站结束后恢复发车）。
   *
   * <p>会重新评估占用与信号，并根据结果重下发速度/发车指令。
   */
  public void refreshSignal(com.bergerkiller.bukkit.tc.controller.MinecartGroup group) {
    handleSignalTick(new TrainCartsRuntimeTrainHandle(group), true);
  }

  /**
   * 清理“已不存在列车”的占用记录（事件反射式占用的兜底）。
   *
   * <p>该方法不会主动加载区块或扫描轨道，仅根据当前在线列车名集合做一致性修复。
   */
  public void cleanupOrphanOccupancyClaims(java.util.Set<String> activeTrainNames, Instant now) {
    if (occupancyManager == null || activeTrainNames == null || now == null) {
      return;
    }
    java.util.Set<String> activeLower = new java.util.HashSet<>();
    for (String name : activeTrainNames) {
      if (name == null || name.isBlank()) {
        continue;
      }
      activeLower.add(name.trim().toLowerCase(java.util.Locale.ROOT));
    }
    java.util.Set<String> released = new java.util.HashSet<>();
    for (OccupancyClaim claim : occupancyManager.snapshotClaims()) {
      if (claim == null || claim.trainName() == null || claim.trainName().isBlank()) {
        continue;
      }
      String trainName = claim.trainName();
      String key = trainName.trim().toLowerCase(java.util.Locale.ROOT);
      if (activeLower.contains(key)) {
        continue;
      }
      if (!released.add(key)) {
        continue;
      }
      occupancyManager.updateReleaseAtByTrain(trainName, now);
    }
  }

  /**
   * 信号 tick 入口：重算占用并根据许可变化调整控车。
   *
   * <p>forceApply 用于强制刷新（例如停站结束），即便信号等级未变化也会重新下发速度/发车动作。
   */
  void handleSignalTick(RuntimeTrainHandle train, boolean forceApply) {
    if (train == null || !train.isValid()) {
      return;
    }
    TrainProperties properties = train.properties();
    if (properties == null) {
      return;
    }
    String trainName = properties.getTrainName();
    Optional<RouteDefinition> routeOpt = resolveRouteDefinition(properties);
    if (routeOpt.isEmpty()) {
      return;
    }
    RouteDefinition route = routeOpt.get();
    RouteProgressRegistry.RouteProgressEntry progressEntry =
        progressRegistry
            .get(trainName)
            .orElseGet(() -> progressRegistry.initFromTags(trainName, properties, route));
    int currentIndex = progressEntry.currentIndex();
    if (currentIndex < 0 || currentIndex >= route.waypoints().size() - 1) {
      return;
    }
    Optional<RailGraph> graphOpt =
        railGraphService.getSnapshot(train.worldId()).map(snapshot -> snapshot.graph());
    if (graphOpt.isEmpty()) {
      return;
    }
    RailGraph graph = graphOpt.get();
    RailTravelTimeModel travelTimeModel =
        RailTravelTimeModels.constantSpeed(
            configManager.current().graphSettings().defaultSpeedBlocksPerSecond());
    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(
            graph, travelTimeModel, configManager.current().runtimeSettings().lookaheadEdges());
    Optional<OccupancyRequest> requestOpt =
        builder.buildFromNodes(
            trainName,
            Optional.ofNullable(route.id()),
            route.waypoints(),
            currentIndex,
            Instant.now());
    if (requestOpt.isEmpty()) {
      return;
    }
    Instant now = Instant.now();
    OccupancyRequest request = withHoldLease(requestOpt.get());
    releaseResourcesNotInRequest(trainName, request.resourceList(), now);
    OccupancyDecision decision = occupancyManager.canEnter(request);
    if (decision.allowed()) {
      occupancyManager.acquire(request);
    }
    SignalAspect nextAspect = decision.signal();
    SignalAspect lastAspect = progressEntry.lastSignal();
    boolean allowLaunch = forceApply || lastAspect != nextAspect;
    if (allowLaunch) {
      progressRegistry.updateSignal(trainName, nextAspect, Instant.now());
    }
    Optional<NodeId> nextNode =
        currentIndex + 1 < route.waypoints().size()
            ? Optional.of(route.waypoints().get(currentIndex + 1))
            : Optional.empty();
    Optional<NodeId> currentNode =
        currentIndex < route.waypoints().size()
            ? Optional.of(route.waypoints().get(currentIndex))
            : Optional.empty();
    if (currentNode.isEmpty() || nextNode.isEmpty()) {
      return;
    }
    applySignalToTrain(
        train,
        properties,
        nextAspect,
        route,
        currentNode.get(),
        nextNode.get(),
        graph,
        allowLaunch);
  }

  /**
   * 将信号许可映射为速度/制动控制。
   *
   * <p>PROCEED 发车并恢复巡航速度；CAUTION 限速；STOP 停车并清空限速。
   */
  private void applySignalToTrain(
      RuntimeTrainHandle train,
      TrainProperties properties,
      SignalAspect aspect,
      RouteDefinition route,
      NodeId currentNode,
      NodeId nextNode,
      RailGraph graph,
      boolean allowLaunch) {
    if (properties == null) {
      return;
    }
    TrainConfig config = trainConfigResolver.resolve(properties, configManager.current());
    double targetBps =
        resolveTargetSpeed(train != null ? train.worldId() : null, aspect, route, nextNode);
    double edgeLimit =
        resolveEdgeSpeedLimit(train, graph, currentNode, nextNode, configManager.current());
    if (edgeLimit > 0.0) {
      targetBps = Math.min(targetBps, edgeLimit);
    }
    double targetBpt = toBlocksPerTick(targetBps);
    double accelBpt2 = toBlocksPerTickSquared(config.accelBps2());
    double decelBpt2 = toBlocksPerTickSquared(config.decelBps2());
    if (accelBpt2 > 0.0 && decelBpt2 > 0.0) {
      properties.setWaitAcceleration(accelBpt2, decelBpt2);
    }
    if (aspect == SignalAspect.STOP) {
      properties.setSpeedLimit(0.0);
      if (train != null) {
        train.stop();
      }
      return;
    }
    properties.setSpeedLimit(targetBpt);
    if (aspect == SignalAspect.PROCEED && allowLaunch && train != null && !train.isMoving()) {
      train.launch(targetBpt, accelBpt2);
    }
  }

  /**
   * 将占用请求改写为“长租约”，避免依赖短续约产生抖动。
   *
   * <p>真正释放仍由推进点/信号 tick 触发的事件反射逻辑完成。
   */
  private static OccupancyRequest withHoldLease(OccupancyRequest request) {
    if (request == null) {
      return null;
    }
    return new OccupancyRequest(
        request.trainName(),
        request.routeId(),
        request.now(),
        OCCUPANCY_HOLD_LEASE,
        request.resourceList());
  }

  /**
   * 释放“超出当前占用窗口”的资源。
   *
   * <p>用于事件反射式占用：列车推进后即时释放窗口外资源，不再等待 time-based 过期。
   */
  private void releaseResourcesNotInRequest(
      String trainName, List<OccupancyResource> keepResources, Instant now) {
    if (trainName == null || trainName.isBlank() || occupancyManager == null || now == null) {
      return;
    }
    java.util.Set<OccupancyResource> keep =
        keepResources == null ? java.util.Set.of() : java.util.Set.copyOf(keepResources);
    for (OccupancyClaim claim : occupancyManager.snapshotClaims()) {
      if (claim == null || claim.resource() == null) {
        continue;
      }
      if (!claim.trainName().equalsIgnoreCase(trainName)) {
        continue;
      }
      if (keep.contains(claim.resource())) {
        continue;
      }
      occupancyManager.updateReleaseAt(claim.resource(), now, Optional.of(trainName));
    }
  }

  /**
   * 根据许可等级与进站限速计算目标速度（blocks/s）。
   *
   * <p>PROCEED 基准速度取调度图默认速度；警示信号使用连通分量的 caution 速度上限（无覆盖时回退为配置默认值）。
   */
  private double resolveTargetSpeed(
      UUID worldId, SignalAspect aspect, RouteDefinition route, NodeId nextNode) {
    double defaultSpeed = configManager.current().graphSettings().defaultSpeedBlocksPerSecond();
    double base =
        switch (aspect) {
          case PROCEED -> defaultSpeed;
          case PROCEED_WITH_CAUTION, CAUTION -> resolveCautionSpeed(worldId, nextNode);
          case STOP -> 0.0;
        };
    double approachLimit = configManager.current().runtimeSettings().approachSpeedBps();
    if (aspect == SignalAspect.PROCEED && approachLimit > 0.0 && isStationNode(nextNode)) {
      return Math.min(base, approachLimit);
    }
    return base;
  }

  private double resolveCautionSpeed(UUID worldId, NodeId nodeId) {
    double fallback = configManager.current().runtimeSettings().cautionSpeedBps();
    if (worldId == null || nodeId == null || railGraphService == null) {
      return fallback;
    }
    Optional<String> componentKey = railGraphService.componentKey(worldId, nodeId);
    if (componentKey.isEmpty()) {
      return fallback;
    }
    return railGraphService
        .componentCautionSpeedBlocksPerSecond(worldId, componentKey.get())
        .orElse(fallback);
  }

  private boolean isStationNode(NodeId nodeId) {
    if (nodeId == null) {
      return false;
    }
    Optional<SignNodeDefinition> def =
        signNodeRegistry.findByNodeId(nodeId, null).map(SignNodeRegistry.SignNodeInfo::definition);
    if (def.isEmpty()) {
      return false;
    }
    if (def.get().nodeType() == NodeType.STATION) {
      return true;
    }
    return def.get()
        .waypointMetadata()
        .map(meta -> meta.kind() == WaypointKind.STATION)
        .orElse(false);
  }

  private String resolveDestinationName(NodeId nodeId) {
    if (nodeId == null) {
      return null;
    }
    return signNodeRegistry
        .findByNodeId(nodeId, null)
        .map(info -> info.definition().trainCartsDestination().orElse(nodeId.value()))
        .orElse(nodeId.value());
  }

  private double resolveEdgeSpeedLimit(
      RuntimeTrainHandle train,
      RailGraph graph,
      NodeId from,
      NodeId to,
      ConfigManager.ConfigView config) {
    if (train == null || graph == null || from == null || to == null || config == null) {
      return -1.0;
    }
    Optional<RailEdge> edgeOpt = findEdge(graph, from, to);
    if (edgeOpt.isEmpty()) {
      return -1.0;
    }
    return railGraphService.effectiveSpeedLimitBlocksPerSecond(
        train.worldId(),
        edgeOpt.get(),
        Instant.now(),
        config.graphSettings().defaultSpeedBlocksPerSecond());
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

  private Optional<RailGraph> resolveGraph(SignActionEvent event) {
    if (event == null || event.getWorld() == null) {
      return Optional.empty();
    }
    return railGraphService
        .getSnapshot(event.getWorld().getUID())
        .map(snapshot -> snapshot.graph());
  }

  private Optional<RouteDefinition> resolveRouteDefinition(TrainProperties properties) {
    if (properties == null) {
      return Optional.empty();
    }
    Optional<String> operatorCode =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_OPERATOR_CODE);
    Optional<String> lineCode =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_LINE_CODE);
    Optional<String> routeCode =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_ROUTE_CODE);
    if (operatorCode.isPresent() && lineCode.isPresent() && routeCode.isPresent()) {
      Optional<RouteDefinition> def =
          routeDefinitions.findByCodes(operatorCode.get(), lineCode.get(), routeCode.get());
      if (def.isPresent()) {
        return def;
      }
    }
    Optional<UUID> routeUuidOpt = readRouteUuid(properties);
    if (routeUuidOpt.isEmpty()) {
      return Optional.empty();
    }
    return routeDefinitions.findById(routeUuidOpt.get());
  }

  private Optional<UUID> readRouteUuid(TrainProperties properties) {
    return TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_ROUTE_ID)
        .flatMap(RuntimeDispatchService::parseUuid);
  }

  private String describeRouteTags(TrainProperties properties, Optional<UUID> routeUuidOpt) {
    String operatorCode =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_OPERATOR_CODE)
            .orElse("-");
    String lineCode =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_LINE_CODE).orElse("-");
    String routeCode =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_ROUTE_CODE).orElse("-");
    String routeId = routeUuidOpt.map(UUID::toString).orElse("-");
    return "op="
        + operatorCode
        + " line="
        + lineCode
        + " route="
        + routeCode
        + " routeId="
        + routeId;
  }

  private String summarizeBlockers(OccupancyDecision decision) {
    if (decision == null || decision.blockers().isEmpty()) {
      return "none";
    }
    StringBuilder builder = new StringBuilder();
    int count = 0;
    for (OccupancyClaim claim : decision.blockers()) {
      if (claim == null) {
        continue;
      }
      if (count > 0) {
        builder.append(", ");
      }
      builder
          .append(claim.resource().kind())
          .append(":")
          .append(claim.resource().key())
          .append("@")
          .append(claim.trainName());
      count++;
      if (count >= 3) {
        break;
      }
    }
    if (decision.blockers().size() > count) {
      builder.append(" +").append(decision.blockers().size() - count);
    }
    return builder.toString();
  }

  private String diagnoseBuildFailure(
      RailGraph graph,
      RouteDefinition route,
      int currentIndex,
      int lookaheadEdges,
      RailTravelTimeModel travelTimeModel) {
    if (graph == null || route == null) {
      return "missing_graph_or_route";
    }
    List<NodeId> nodes = route.waypoints();
    if (nodes == null || nodes.isEmpty()) {
      return "empty_route";
    }
    if (currentIndex < 0 || currentIndex >= nodes.size() - 1) {
      return "index_out_of_range";
    }
    int safeLookahead = Math.max(1, lookaheadEdges);
    int maxIndex = Math.min(nodes.size() - 1, currentIndex + safeLookahead);
    List<NodeId> pathNodes = new ArrayList<>();
    for (int i = currentIndex; i <= maxIndex; i++) {
      pathNodes.add(nodes.get(i));
    }
    List<RailEdge> edges = new ArrayList<>();
    for (int i = 0; i < pathNodes.size() - 1; i++) {
      NodeId from = pathNodes.get(i);
      NodeId to = pathNodes.get(i + 1);
      Optional<RailEdge> edgeOpt = findEdge(graph, from, to);
      if (edgeOpt.isEmpty()) {
        return "edge_missing:" + from.value() + "->" + to.value();
      }
      edges.add(edgeOpt.get());
    }
    if (edges.isEmpty()) {
      return "edges_empty";
    }
    Optional<Duration> travelTime = travelTimeModel.pathTravelTime(graph, pathNodes, edges);
    if (travelTime.isEmpty()) {
      return "travel_time_empty";
    }
    return "unknown";
  }

  private static Optional<UUID> parseUuid(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(raw.trim()));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  private static double toBlocksPerTick(double blocksPerSecond) {
    if (!Double.isFinite(blocksPerSecond) || blocksPerSecond <= 0.0) {
      return 0.0;
    }
    return blocksPerSecond / TICKS_PER_SECOND;
  }

  private static double toBlocksPerTickSquared(double blocksPerSecondSquared) {
    if (!Double.isFinite(blocksPerSecondSquared) || blocksPerSecondSquared <= 0.0) {
      return 0.0;
    }
    return blocksPerSecondSquared / (TICKS_PER_SECOND * TICKS_PER_SECOND);
  }
}
