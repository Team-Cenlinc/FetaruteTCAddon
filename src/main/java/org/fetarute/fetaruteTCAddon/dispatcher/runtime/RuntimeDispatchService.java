package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.utils.LauncherConfig;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestBuilder;
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
    MinecartGroup group = event.getGroup();
    TrainProperties properties = group.getProperties();
    String trainName = properties != null ? properties.getTrainName() : "unknown";
    Optional<UUID> routeUuidOpt = readRouteUuid(properties);
    Optional<RouteDefinition> routeOpt = resolveRouteDefinition(properties);
    if (routeOpt.isEmpty()) {
      debugLogger.accept(
          "调度推进失败: 未找到线路定义 train=" + trainName + " " + describeRouteTags(properties, routeUuidOpt));
      return;
    }
    RouteDefinition route = routeOpt.get();
    int currentIndex = indexOfNode(route, definition.nodeId());
    if (currentIndex < 0) {
      return;
    }
    NodeId currentNode = definition.nodeId();
    Optional<NodeId> nextTarget =
        currentIndex + 1 < route.waypoints().size()
            ? Optional.of(route.waypoints().get(currentIndex + 1))
            : Optional.empty();
    if (nextTarget.isEmpty()) {
      return;
    }

    Instant now = Instant.now();
    Optional<RailGraph> graphOpt = resolveGraph(event);
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
            trainName, Optional.ofNullable(route.id()), route.waypoints(), currentIndex, now);
    if (requestOpt.isEmpty()) {
      return;
    }
    OccupancyDecision decision = occupancyManager.canEnter(requestOpt.get());
    if (!decision.allowed()) {
      progressRegistry.updateSignal(trainName, decision.signal(), now);
      applySignalToTrain(
          group, properties, decision.signal(), route, currentNode, nextTarget.get(), graph);
      return;
    }
    occupancyManager.acquire(requestOpt.get());
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
        group, properties, SignalAspect.PROCEED, route, currentNode, nextTarget.get(), graph);
  }

  /** 周期性信号检查：信号等级变化时调整速度/刹车。 */
  public void handleSignalTick(MinecartGroup group) {
    if (group == null || !group.isValid()) {
      return;
    }
    TrainProperties properties = group.getProperties();
    if (properties == null) {
      return;
    }
    String trainName = properties.getTrainName();
    Optional<RouteDefinition> routeOpt = resolveRouteDefinition(properties);
    if (routeOpt.isEmpty()) {
      return;
    }
    RouteDefinition route = routeOpt.get();
    int currentIndex =
        TrainTagHelper.readIntTag(properties, RouteProgressRegistry.TAG_ROUTE_INDEX).orElse(0);
    if (currentIndex < 0 || currentIndex >= route.waypoints().size() - 1) {
      return;
    }
    Optional<RailGraph> graphOpt =
        railGraphService.getSnapshot(group.getWorld()).map(snapshot -> snapshot.graph());
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
    OccupancyDecision decision = occupancyManager.canEnter(requestOpt.get());
    SignalAspect nextAspect = decision.signal();
    SignalAspect lastAspect =
        progressRegistry
            .get(trainName)
            .map(RouteProgressRegistry.RouteProgressEntry::lastSignal)
            .orElse(null);
    if (lastAspect == nextAspect) {
      return;
    }
    progressRegistry.updateSignal(trainName, nextAspect, Instant.now());
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
        group, properties, nextAspect, route, currentNode.get(), nextNode.get(), graph);
  }

  /**
   * 将信号许可映射为速度/制动控制。
   *
   * <p>PROCEED 发车并恢复巡航速度；CAUTION 限速；STOP 停车并清空限速。
   */
  private void applySignalToTrain(
      MinecartGroup group,
      TrainProperties properties,
      SignalAspect aspect,
      RouteDefinition route,
      NodeId currentNode,
      NodeId nextNode,
      RailGraph graph) {
    if (properties == null) {
      return;
    }
    TrainConfig config = trainConfigResolver.resolve(properties, configManager.current());
    double targetBps = resolveTargetSpeed(aspect, config, route, nextNode);
    double edgeLimit =
        resolveEdgeSpeedLimit(group, graph, currentNode, nextNode, configManager.current());
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
      group.stop(false);
      return;
    }
    properties.setSpeedLimit(targetBpt);
    if (aspect == SignalAspect.PROCEED) {
      MinecartMember<?> head = group.head();
      if (head != null) {
        head.getActions().clear();
        LauncherConfig launchConfig = LauncherConfig.createDefault();
        if (accelBpt2 > 0.0) {
          launchConfig.setAcceleration(accelBpt2);
        }
        head.getActions().addActionLaunch(launchConfig, targetBpt);
      }
    }
  }

  /** 根据许可等级与进站限速计算目标速度（blocks/s）。 */
  private double resolveTargetSpeed(
      SignalAspect aspect, TrainConfig config, RouteDefinition route, NodeId nextNode) {
    double base =
        switch (aspect) {
          case PROCEED -> config.cruiseSpeedBps();
          case PROCEED_WITH_CAUTION, CAUTION -> config.cautionSpeedBps();
          case STOP -> 0.0;
        };
    double approachLimit = configManager.current().runtimeSettings().approachSpeedBps();
    if (aspect == SignalAspect.PROCEED && approachLimit > 0.0 && isStationNode(nextNode)) {
      return Math.min(base, approachLimit);
    }
    return base;
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
      MinecartGroup group,
      RailGraph graph,
      NodeId from,
      NodeId to,
      ConfigManager.ConfigView config) {
    if (group == null || graph == null || from == null || to == null || config == null) {
      return -1.0;
    }
    Optional<RailEdge> edgeOpt = findEdge(graph, from, to);
    if (edgeOpt.isEmpty()) {
      return -1.0;
    }
    return railGraphService.effectiveSpeedLimitBlocksPerSecond(
        group.getWorld().getUID(),
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
    return railGraphService.getSnapshot(event.getWorld()).map(snapshot -> snapshot.graph());
  }

  private int indexOfNode(RouteDefinition route, NodeId nodeId) {
    if (route == null || nodeId == null) {
      return -1;
    }
    for (int i = 0; i < route.waypoints().size(); i++) {
      if (route.waypoints().get(i).equals(nodeId)) {
        return i;
      }
    }
    return -1;
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
