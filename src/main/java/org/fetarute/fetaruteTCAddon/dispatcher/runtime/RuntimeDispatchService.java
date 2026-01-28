package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.bukkit.plugin.java.JavaPlugin;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.EdgeOverrideRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry.LayoverCandidate;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.ControlDiagnostics;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.ControlDiagnosticsCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignalLookahead;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestBuilder;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignTextParser;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 运行时调度控制：推进点触发下发目的地 + 信号等级变化时控车。
 *
 * <p>推进点逻辑用于“进入节点时下发下一跳 destination”；信号监测用于“运行中根据信号变化调整限速/制动”。
 *
 * <p>假设列车已写入 {@code FTA_OPERATOR_CODE/FTA_LINE_CODE/FTA_ROUTE_CODE}（或 {@code FTA_ROUTE_ID}）与 {@code
 * FTA_ROUTE_INDEX} tag，且 RouteDefinitionCache 已完成预热。
 */
public final class RuntimeDispatchService {

  private static final java.util.logging.Logger HEALTH_LOGGER =
      java.util.logging.Logger.getLogger("FetaruteTCAddon");

  private static final double SPEED_TICKS_PER_SECOND = 20.0;

  /** Waypoint 作为 STOP/TERM 时的默认停站时长（秒）。 */
  private static final int DEFAULT_WAYPOINT_DWELL_SECONDS = 20;

  /** Waypoint 停站减速：按制动距离的比例估算“软停车距离”。 */
  private static final double WAYPOINT_STOP_BRAKE_FACTOR = 0.5;

  /** Waypoint 停站等待超时（ticks）。 */
  private static final int WAYPOINT_STOP_WAIT_TIMEOUT_TICKS = 400;

  /** Waypoint 判定停稳的连续 tick 数。 */
  private static final int WAYPOINT_STOP_STABLE_TICKS = 1;

  /** 推进点去重窗口（毫秒），用于压制同一节点的重复触发。 */
  private static final long PROGRESS_TRIGGER_DEDUP_MS = 800L;

  private final OccupancyManager occupancyManager;
  private final RailGraphService railGraphService;
  private final RouteDefinitionCache routeDefinitions;
  private final RouteProgressRegistry progressRegistry;
  private final SignNodeRegistry signNodeRegistry;
  private final LayoverRegistry layoverRegistry;

  private final DwellRegistry dwellRegistry;

  private final ConfigManager configManager;
  private final StorageManager storageManager;
  private final TrainConfigResolver trainConfigResolver;
  private final TrainLaunchManager trainLaunchManager = new TrainLaunchManager();
  private final Consumer<String> debugLogger;
  private Consumer<LayoverRegistry.LayoverCandidate> layoverListener = candidate -> {};
  private final RailGraphPathFinder pathFinder = new RailGraphPathFinder();
  private final java.util.Map<String, StallState> stallStates = new java.util.HashMap<>();
  private final java.util.Set<String> missingSignalWarned = new HashSet<>();
  private final java.util.concurrent.ConcurrentMap<String, String> waypointStopSessions =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final AtomicLong waypointStopCounter = new AtomicLong();
  private final java.util.concurrent.ConcurrentMap<String, String> stopWaypointLogState =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.ConcurrentMap<String, ProgressTriggerState>
      progressTriggerState = new java.util.concurrent.ConcurrentHashMap<>();
  private volatile EtaService etaService;

  private final java.util.concurrent.atomic.LongAdder orphanCleanupRuns =
      new java.util.concurrent.atomic.LongAdder();
  private final java.util.concurrent.atomic.LongAdder orphanProgressRemoved =
      new java.util.concurrent.atomic.LongAdder();
  private final java.util.concurrent.atomic.LongAdder orphanTrainsReleased =
      new java.util.concurrent.atomic.LongAdder();
  private final java.util.concurrent.atomic.LongAdder orphanLayoverRemoved =
      new java.util.concurrent.atomic.LongAdder();
  private final java.util.concurrent.ConcurrentMap<String, Long> healLastWarnAtMs =
      new java.util.concurrent.ConcurrentHashMap<>();
  private volatile CleanupResult lastCleanupResult =
      new CleanupResult(java.time.Instant.EPOCH, 0, 0, 0);
  private final java.util.concurrent.ConcurrentMap<String, Integer> operatorPriorityCache =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final ControlDiagnosticsCache diagnosticsCache = new ControlDiagnosticsCache();
  private static final Pattern ACTION_PREFIX_PATTERN =
      Pattern.compile("^(CHANGE|DYNAMIC|ACTION|CRET|DSTY)\\b", Pattern.CASE_INSENSITIVE);

  public RuntimeDispatchService(
      OccupancyManager occupancyManager,
      RailGraphService railGraphService,
      RouteDefinitionCache routeDefinitions,
      RouteProgressRegistry progressRegistry,
      SignNodeRegistry signNodeRegistry,
      LayoverRegistry layoverRegistry,
      DwellRegistry dwellRegistry,
      ConfigManager configManager,
      StorageManager storageManager,
      TrainConfigResolver trainConfigResolver,
      Consumer<String> debugLogger) {
    this.occupancyManager = Objects.requireNonNull(occupancyManager, "occupancyManager");
    this.railGraphService = Objects.requireNonNull(railGraphService, "railGraphService");
    this.routeDefinitions = Objects.requireNonNull(routeDefinitions, "routeDefinitions");
    this.progressRegistry = Objects.requireNonNull(progressRegistry, "progressRegistry");
    this.signNodeRegistry = Objects.requireNonNull(signNodeRegistry, "signNodeRegistry");
    this.layoverRegistry = Objects.requireNonNull(layoverRegistry, "layoverRegistry");
    this.dwellRegistry = dwellRegistry;
    this.configManager = Objects.requireNonNull(configManager, "configManager");
    this.storageManager = storageManager;
    this.trainConfigResolver = Objects.requireNonNull(trainConfigResolver, "trainConfigResolver");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
  }

  /** 注册 Layover 事件监听器（在列车进入 Layover 时触发）。 */
  public void setLayoverListener(Consumer<LayoverRegistry.LayoverCandidate> listener) {
    this.layoverListener = listener != null ? listener : candidate -> {};
  }

  /** 设置 EtaService（可选），用于在推进点时使 ETA 缓存失效。 */
  public void setEtaService(EtaService etaService) {
    this.etaService = etaService;
  }

  /**
   * 获取指定列车的控车诊断数据。
   *
   * @param trainName 列车名
   * @return 诊断数据（如果缓存命中且未过期）
   */
  public Optional<ControlDiagnostics> getDiagnostics(String trainName) {
    return diagnosticsCache.get(trainName, Instant.now());
  }

  /**
   * 获取所有缓存的诊断数据快照（用于调试列表）。
   *
   * @return 未过期的诊断数据映射
   */
  public java.util.Map<String, ControlDiagnostics> getDiagnosticsSnapshot() {
    return diagnosticsCache.snapshot(Instant.now());
  }

  /**
   * 检查列车是否允许从当前站点发车（出站门控）。
   *
   * <p>如果允许，会申请占用并返回 true；如果阻塞，返回 false。
   */
  public boolean checkDeparture(
      com.bergerkiller.bukkit.tc.controller.MinecartGroup group, SignNodeDefinition definition) {
    if (group == null || definition == null) {
      return true;
    }
    RuntimeTrainHandle train = new TrainCartsRuntimeHandle(group);
    TrainProperties properties = train.properties();
    String trainName = properties != null ? properties.getTrainName() : "unknown";
    Optional<RouteDefinition> routeOpt = resolveRouteDefinition(properties);
    if (routeOpt.isEmpty()) {
      return true;
    }
    RouteDefinition route = routeOpt.get();
    OptionalInt tagIndex =
        TrainTagHelper.readIntTag(properties, RouteProgressRegistry.TAG_ROUTE_INDEX)
            .map(OptionalInt::of)
            .orElse(OptionalInt.empty());
    int currentIndex = RouteIndexResolver.resolveCurrentIndex(route, tagIndex, definition.nodeId());
    if (currentIndex < 0) {
      return true;
    }
    Optional<RouteStop> stopOpt = routeDefinitions.findStop(route.id(), currentIndex);
    if (stopOpt.isPresent()) {
      RouteStop stop = stopOpt.get();
      if (stop.passType() == org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType.TERMINATE
          && route.lifecycleMode()
              == org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLifecycleMode.REUSE_AT_TERM) {
        handleLayoverRegistrationIfNeeded(trainName, route, definition.nodeId(), properties);
        return false;
      }
    }
    if (currentIndex >= route.waypoints().size() - 1) {
      // 已到终点/无下一跳时不做发车门控。
      return true;
    }

    Instant now = Instant.now();
    Optional<RailGraph> graphOpt = resolveGraph(train.worldId(), now);
    if (graphOpt.isEmpty()) {
      return true;
    }
    RailGraph graph = graphOpt.get();
    ConfigManager.RuntimeSettings runtimeSettings = configManager.current().runtimeSettings();
    int lookaheadEdges = runtimeSettings.lookaheadEdges();
    int minClearEdges = runtimeSettings.minClearEdges();
    int priority = resolvePriority(properties, route);

    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(
            graph, lookaheadEdges, minClearEdges, runtimeSettings.switcherZoneEdges(), debugLogger);

    Optional<OccupancyRequestContext> contextOpt =
        builder.buildContextFromNodes(
            trainName,
            Optional.ofNullable(route.id()),
            route.waypoints(),
            currentIndex,
            now,
            priority);

    if (contextOpt.isEmpty()) {
      debugLogger.accept("发车门控失败: 构建占用请求失败 train=" + trainName);
      return false;
    }

    OccupancyRequest request = contextOpt.get().request();
    releaseResourcesNotInRequest(trainName, request.resourceList());
    if (occupancyManager.shouldYield(request)) {
      debugLogger.accept("发车门控让行: train=" + trainName + " priority=" + priority);
      return false;
    }
    OccupancyDecision decision = occupancyManager.acquire(request);
    if (!decision.allowed()) {
      debugLogger.accept("发车门控阻塞: train=" + trainName + " aspect=" + decision.signal());
      return false;
    }
    return true;
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
    RuntimeTrainHandle train = new TrainCartsRuntimeHandle(group);
    TrainProperties properties = train.properties();
    String trainName = properties != null ? properties.getTrainName() : "unknown";
    handleRenameIfNeeded(properties, trainName);
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
      if (matchesDstyTarget(route, definition.nodeId())) {
        handleDestroy(train, properties, trainName, "DSTY");
        return;
      }
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
    Instant now = Instant.now();
    if (!shouldHandleProgressTrigger(trainName, currentNode, currentIndex, now)) {
      return;
    }
    Optional<RouteStop> stopOpt = routeDefinitions.findStop(route.id(), currentIndex);
    boolean stopAtWaypoint = false;
    int waypointDwellSeconds = 0;
    if (stopOpt.isPresent()) {
      RouteStop stop = stopOpt.get();
      if (shouldDestroyAt(stop, currentNode)
          || shouldDestroyAtFallback(stop, currentIndex, route, definition)) {
        handleDestroy(train, properties, trainName, "DSTY");
        return;
      }
      if (stop.passType() == org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType.TERMINATE
          && route.lifecycleMode()
              == org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLifecycleMode.REUSE_AT_TERM) {
        if (definition.nodeType() == NodeType.WAYPOINT) {
          centerTrainAtWaypoint(event, trainName, definition.nodeId());
        }
        progressRegistry.advance(
            trainName, routeUuidOpt.orElse(null), route, currentIndex, properties, now);
        invalidateTrainEta(trainName);
        if (occupancyManager != null) {
          occupancyManager.releaseByTrain(trainName);
        }
        // TERM 标记：清除 destination 防止继续寻路
        // 不强制停车，让 handleSignalTick 的 speed curve 自然减速
        properties.clearDestinationRoute();
        properties.setDestination("");
        handleLayoverRegistrationIfNeeded(trainName, route, currentNode, properties);
        debugLogger.accept(
            "调度终到: 进入 Layover train="
                + trainName
                + " node="
                + currentNode.value()
                + " idx="
                + currentIndex
                + " route="
                + route.id().value());
        return;
      }
      if (shouldStopAtWaypoint(definition, stop)) {
        waypointDwellSeconds = resolveWaypointDwellSeconds(stop);
        stopAtWaypoint = waypointDwellSeconds > 0;
      }
    }
    Optional<NodeId> nextTarget =
        currentIndex + 1 < route.waypoints().size()
            ? Optional.of(route.waypoints().get(currentIndex + 1))
            : Optional.empty();
    if (nextTarget.isEmpty()) {
      progressRegistry.advance(
          trainName, routeUuidOpt.orElse(null), route, currentIndex, properties, now);
      invalidateTrainEta(trainName);
      // 无下一目标时清除 destination 防止继续寻路
      properties.clearDestinationRoute();
      properties.setDestination("");
      debugLogger.accept(
          "调度推进结束: 已到终点 train="
              + trainName
              + " node="
              + definition.nodeId().value()
              + " route="
              + route.id().value());
      return;
    }

    Optional<RailGraph> graphOpt = resolveGraph(event);
    RailGraph graph = graphOpt.orElse(null);
    if (stopAtWaypoint) {
      if (event.getAction() == SignActionType.MEMBER_ENTER) {
        return;
      }
      if (occupancyManager != null) {
        occupancyManager.releaseByTrain(trainName);
      }
      progressRegistry.advance(
          trainName, routeUuidOpt.orElse(null), route, currentIndex, properties, now);
      invalidateTrainEta(trainName);
      updateSignalOrWarn(trainName, SignalAspect.STOP, now);
      String destinationName = resolveDestinationName(nextTarget.get());
      if (destinationName != null && !destinationName.isBlank() && properties != null) {
        properties.clearDestinationRoute();
        properties.setDestination(destinationName);
      }
      OptionalLong stopDistanceOpt = resolveSoftStopDistance(train, properties);
      applyControl(
          train,
          properties,
          SignalAspect.STOP,
          route,
          currentNode,
          nextTarget.get(),
          graph,
          false,
          stopDistanceOpt);
      scheduleWaypointStopSequence(event, definition, trainName, waypointDwellSeconds);
      return;
    }
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
    ConfigManager.RuntimeSettings runtimeSettings = configManager.current().runtimeSettings();
    int lookaheadEdges = runtimeSettings.lookaheadEdges();
    int minClearEdges = runtimeSettings.minClearEdges();
    int priority = resolvePriority(properties, route);
    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(
            graph, lookaheadEdges, minClearEdges, runtimeSettings.switcherZoneEdges());
    Optional<OccupancyRequestContext> contextOpt =
        builder.buildContextFromNodes(
            trainName,
            Optional.ofNullable(route.id()),
            route.waypoints(),
            currentIndex,
            now,
            priority);
    if (contextOpt.isEmpty()) {
      debugLogger.accept(
          "调度推进失败: 构建占用请求失败 train="
              + trainName
              + " node="
              + definition.nodeId().value()
              + " route="
              + route.id().value()
              + " reason="
              + diagnoseBuildFailure(
                  graph, route, currentIndex, Math.max(lookaheadEdges, minClearEdges)));
      return;
    }
    OccupancyRequestContext context = contextOpt.get();
    OccupancyRequest request = context.request();
    releaseResourcesNotInRequest(trainName, request.resourceList());
    OccupancyDecision decision = occupancyManager.canEnter(request);
    // 诊断：输出请求资源与判定结果
    debugLogger.accept(
        "调度推进判定: train="
            + trainName
            + " idx="
            + currentIndex
            + " node="
            + definition.nodeId().value()
            + " resources="
            + request.resourceList().size()
            + " allowed="
            + decision.allowed()
            + " blockers="
            + decision.blockers().size()
            + " signal="
            + decision.signal());
    if (!decision.allowed()) {
      var lookahead =
          SignalLookahead.compute(decision, context, SignalAspect.STOP, this::isApproachingNode);
      OptionalLong lookaheadDistance = lookahead.minConstraintDistance();
      SignalAspect aspect = deriveBlockedAspect(decision, context);
      debugLogger.accept(
          "调度推进阻塞: train="
              + trainName
              + " node="
              + definition.nodeId().value()
              + " signal="
              + aspect
              + " earliest="
              + decision.earliestTime()
              + " blockers="
              + summarizeBlockers(decision));
      updateSignalOrWarn(trainName, aspect, now);
      applyControl(
          train,
          properties,
          aspect,
          route,
          currentNode,
          nextTarget.get(),
          graph,
          false,
          lookaheadDistance,
          lookahead,
          java.util.OptionalDouble.empty());
      return;
    }
    occupancyManager.acquire(request);
    progressRegistry.advance(
        trainName, routeUuidOpt.orElse(null), route, currentIndex, properties, now);
    invalidateTrainEta(trainName);
    updateSignalOrWarn(trainName, SignalAspect.PROCEED, now);

    String destinationName = resolveDestinationName(nextTarget.get());
    if (destinationName == null || destinationName.isBlank()) {
      return;
    }
    properties.clearDestinationRoute();
    properties.setDestination(destinationName);
    applyControl(
        train,
        properties,
        SignalAspect.PROCEED,
        route,
        currentNode,
        nextTarget.get(),
        graph,
        true,
        OptionalLong.empty());
  }

  /** 周期性信号检查：信号等级变化时调整速度/刹车。 */
  public void handleSignalTick(com.bergerkiller.bukkit.tc.controller.MinecartGroup group) {
    handleSignalTick(new TrainCartsRuntimeHandle(group), false);
  }

  /**
   * 强制刷新信号控制（用于停站结束后恢复发车）。
   *
   * <p>会重新评估占用与信号，并根据结果重下发速度/发车指令。
   */
  public void refreshSignal(com.bergerkiller.bukkit.tc.controller.MinecartGroup group) {
    handleSignalTick(new TrainCartsRuntimeHandle(group), true);
  }

  /**
   * 清理“已不存在列车”的占用记录与进度缓存（事件反射式占用的兜底）。
   *
   * <p>该方法不会主动加载区块或扫描轨道，仅根据当前在线列车名集合做一致性修复。
   */
  /** 清理“已不存在列车”的占用记录与进度缓存。 */
  public void cleanupOrphanOccupancyClaims(java.util.Set<String> activeTrainNames) {
    cleanupOrphanOccupancyClaimsWithReport(activeTrainNames);
  }

  /**
   * 清理“已不存在列车”的占用记录与进度缓存，并返回本次自愈的统计结果。
   *
   * <p>该方法不会主动加载区块或扫描轨道，仅根据当前在线列车名集合做一致性修复。
   */
  public CleanupResult cleanupOrphanOccupancyClaimsWithReport(
      java.util.Set<String> activeTrainNames) {
    if (occupancyManager == null || activeTrainNames == null) {
      return lastCleanupResult;
    }

    orphanCleanupRuns.increment();

    java.util.Set<String> activeLower = new java.util.HashSet<>();
    for (String name : activeTrainNames) {
      if (name == null || name.isBlank()) {
        continue;
      }
      activeLower.add(name.trim().toLowerCase(java.util.Locale.ROOT));
    }

    int removedProgress = 0;
    for (String name : progressRegistry.snapshot().keySet()) {
      if (name == null || name.isBlank()) {
        continue;
      }
      if (!activeLower.contains(name.trim().toLowerCase(java.util.Locale.ROOT))) {
        progressRegistry.remove(name);
        removedProgress++;
      }
    }

    java.util.Set<String> released = new java.util.HashSet<>();
    int releasedTrains = 0;
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
      occupancyManager.releaseByTrain(trainName);
      releasedTrains++;
    }

    int removedLayovers = 0;
    for (LayoverCandidate candidate : layoverRegistry.snapshot()) {
      if (candidate == null || candidate.trainName() == null || candidate.trainName().isBlank()) {
        continue;
      }
      String key = candidate.trainName().trim().toLowerCase(java.util.Locale.ROOT);
      if (activeLower.contains(key)) {
        continue;
      }
      layoverRegistry.unregister(candidate.trainName());
      removedLayovers++;
    }

    if (removedProgress > 0) {
      orphanProgressRemoved.add(removedProgress);
    }
    if (releasedTrains > 0) {
      orphanTrainsReleased.add(releasedTrains);
    }
    if (removedLayovers > 0) {
      orphanLayoverRemoved.add(removedLayovers);
    }

    CleanupResult result =
        new CleanupResult(
            java.time.Instant.now(), removedProgress, releasedTrains, removedLayovers);
    lastCleanupResult = result;

    if (removedProgress > 0 || releasedTrains > 0 || removedLayovers > 0) {
      warnHealThrottled(
          "orphan-cleanup",
          "调度自愈: cleaned progress="
              + removedProgress
              + " releasedTrains="
              + releasedTrains
              + " removedLayovers="
              + removedLayovers);
    }

    return result;
  }

  /** 返回上一次自愈统计结果。 */
  public CleanupResult lastCleanupResult() {
    return lastCleanupResult;
  }

  /** 返回自愈运行次数。 */
  public long orphanCleanupRuns() {
    return orphanCleanupRuns.sum();
  }

  /** 返回累计移除的进度条目数量。 */
  public long orphanProgressRemoved() {
    return orphanProgressRemoved.sum();
  }

  /** 返回累计释放的列车占用数量。 */
  public long orphanTrainsReleased() {
    return orphanTrainsReleased.sum();
  }

  /** 返回累计移除的 Layover 条目数量。 */
  public long orphanLayoverRemoved() {
    return orphanLayoverRemoved.sum();
  }

  /** 返回当前进度缓存条目数。 */
  public int progressEntryCount() {
    return progressRegistry.snapshot().size();
  }

  /** 返回 Layover 候选列车数量。 */
  public int layoverCandidateCount() {
    return layoverRegistry.snapshot().size();
  }

  private void warnHealThrottled(String key, String message) {
    long now = System.currentTimeMillis();
    long intervalMs = 60_000L;
    healLastWarnAtMs.compute(
        key,
        (k, prev) -> {
          if (prev == null || now - prev > intervalMs) {
            HEALTH_LOGGER.warning(message);
            return now;
          }
          return prev;
        });
  }

  /** 调度自愈统计结果。 */
  public record CleanupResult(
      java.time.Instant at, int removedProgress, int releasedTrains, int removedLayovers) {}

  /**
   * 启动/重载后扫描现存列车，重建占用快照并修复孤儿占用。
   *
   * <p>会对每列车执行一次信号评估，用 tags 初始化进度，确保占用与信号状态同步。
   */
  public void rebuildOccupancySnapshot(java.util.Collection<? extends RuntimeTrainHandle> trains) {
    if (trains == null || trains.isEmpty()) {
      return;
    }
    java.util.Set<String> activeTrainNames = new java.util.HashSet<>();
    for (RuntimeTrainHandle train : trains) {
      if (train == null || !train.isValid()) {
        continue;
      }
      TrainProperties properties = train.properties();
      if (properties != null && properties.getTrainName() != null) {
        activeTrainNames.add(properties.getTrainName());
      }
      handleSignalTick(train, false);
    }
    cleanupOrphanOccupancyClaims(activeTrainNames);
  }

  /**
   * 列车卸载/移除时释放占用并清理进度缓存。
   *
   * <p>用于事件反射式占用的主动清理，避免列车消失后资源长期占用。
   */
  public void handleTrainRemoved(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    layoverRegistry.unregister(trainName);
    if (occupancyManager != null) {
      occupancyManager.releaseByTrain(trainName);
    }
    progressRegistry.remove(trainName);
    stallStates.remove(trainName.toLowerCase(java.util.Locale.ROOT));
    missingSignalWarned.remove(trainName.toLowerCase(java.util.Locale.ROOT));
    stopWaypointLogState.remove(trainName.toLowerCase(java.util.Locale.ROOT));
    progressTriggerState.remove(trainName.toLowerCase(java.util.Locale.ROOT));
  }

  /**
   * 信号 tick 入口：重算占用并根据许可变化调整控车。
   *
   * <p>forceApply 用于强制刷新（例如停站结束），即便信号等级未变化也会重新下发速度/发车动作。
   */
  /**
   * 信号周期性 tick：推进列车运行状态，处理终点停车、DSTY 销毁与 Layover 注册。
   *
   * <p>主要职责：
   *
   * <ul>
   *   <li>优先判定当前节点是否为 DSTY（销毁目标），如是则立即销毁列车。
   *   <li>若已到达终点（currentIndex >= last），无论 forceApply 均强制 setSpeedLimit(0)+stop()，避免滑行穿站。
   *   <li>终点为 REUSE_AT_TERM 时补注册 Layover，确保待命池可用。
   *   <li>其余分支推进常规信号/占用/速度控制。
   * </ul>
   *
   * <p>注意：此处终点停车不再依赖 forceApply，防止未触发信号刷新时列车穿站。
   *
   * @param train 控制的列车句柄
   * @param forceApply 是否强制刷新信号/状态（如停站结束/信号变化）
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
    Instant now = Instant.now();
    handleRenameIfNeeded(properties, trainName);
    if (layoverRegistry.get(trainName).isPresent()) {
      if (occupancyManager != null) {
        occupancyManager.releaseByTrain(trainName);
      }
      updateSignalOrWarn(trainName, SignalAspect.STOP, now);
      TrainConfig config = trainConfigResolver.resolve(properties, configManager.current());
      trainLaunchManager.applyControl(
          train,
          properties,
          SignalAspect.STOP,
          0.0,
          config,
          false,
          OptionalLong.empty(),
          java.util.Optional.empty(),
          configManager.current().runtimeSettings());
      if (train.isMoving()) {
        train.stop();
      }
      return;
    }
    if (TrainTagHelper.readIntTag(properties, RouteProgressRegistry.TAG_ROUTE_INDEX).isEmpty()) {
      if (progressRegistry.get(trainName).isPresent()) {
        if (occupancyManager != null) {
          occupancyManager.releaseByTrain(trainName);
        }
        progressRegistry.remove(trainName);
      }
      stallStates.remove(trainName.toLowerCase(java.util.Locale.ROOT));
      missingSignalWarned.remove(trainName.toLowerCase(java.util.Locale.ROOT));
      return;
    }
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
    if (currentIndex < 0) {
      return;
    }

    NodeId currentNode =
        currentIndex < route.waypoints().size()
            ? route.waypoints().get(currentIndex)
            : route.waypoints().get(Math.max(0, route.waypoints().size() - 1));

    // DSTY 销毁必须优先于“终点停车”，否则当线路最后一个节点就是 DSTY 目标时会卡死不销毁。
    // 若当前节点为 DSTY（销毁目标），立即销毁列车并返回，避免后续逻辑干扰。
    // 详见：终点停车与 DSTY 互斥处理说明。
    Optional<RouteStop> stopOpt = routeDefinitions.findStop(route.id(), currentIndex);
    if (stopOpt.isPresent() && shouldDestroyAt(stopOpt.get(), currentNode)) {
      handleDestroy(train, properties, trainName, "DSTY");
      return;
    }
    if (dwellRegistry != null) {
      Optional<Integer> remaining = dwellRegistry.remainingSeconds(trainName);
      if (remaining.isPresent()
          && stopOpt.isPresent()
          && stopOpt.get().passType() != RouteStopPassType.PASS) {
        Optional<NodeId> nextNode =
            currentIndex + 1 < route.waypoints().size()
                ? Optional.of(route.waypoints().get(currentIndex + 1))
                : Optional.empty();
        if (nextNode.isPresent()) {
          if (occupancyManager != null) {
            occupancyManager.releaseByTrain(trainName);
          }
          updateSignalOrWarn(trainName, SignalAspect.STOP, now);
          OptionalLong stopDistanceOpt = resolveSoftStopDistance(train, properties);
          applyControl(
              train,
              properties,
              SignalAspect.STOP,
              route,
              currentNode,
              nextNode.get(),
              null,
              false,
              stopDistanceOpt);
        } else {
          train.stop();
        }
        return;
      }
    }

    if (currentIndex >= route.waypoints().size() - 1) {
      // 终点：持续下发 STOP 控制，确保减速停车
      // 若已停止，清理 destination 并注册 Layover
      updateSignalOrWarn(trainName, SignalAspect.STOP, now);
      TrainConfig config = trainConfigResolver.resolve(properties, configManager.current());
      trainLaunchManager.applyControl(
          train,
          properties,
          SignalAspect.STOP,
          0.0,
          config,
          false,
          OptionalLong.empty(),
          java.util.Optional.empty(),
          configManager.current().runtimeSettings());
      double currentSpeed = train.currentSpeedBlocksPerTick();
      boolean isStopped = Math.abs(currentSpeed) < 0.001;
      if (isStopped) {
        if (train.isMoving()) {
          train.stop(); // 确保完全停止
        }
        properties.clearDestinationRoute();
        properties.setDestination("");
        if (route.lifecycleMode()
            == org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLifecycleMode.REUSE_AT_TERM) {
          int lastIndex = Math.max(0, route.waypoints().size() - 1);
          NodeId terminalNode = route.waypoints().get(lastIndex);
          if (layoverRegistry.get(trainName).isEmpty()) {
            handleLayoverRegistrationIfNeeded(trainName, route, terminalNode, properties);
          }
        }
      }
      // 未停止时：由 applyControl 的 speed curve 逐渐减速
      return;
    }
    Optional<RailGraph> graphOpt = resolveGraph(train.worldId(), now);
    if (graphOpt.isEmpty()) {
      return;
    }
    RailGraph graph = graphOpt.get();
    ConfigManager.RuntimeSettings runtimeSettings = configManager.current().runtimeSettings();
    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(
            graph,
            runtimeSettings.lookaheadEdges(),
            runtimeSettings.minClearEdges(),
            runtimeSettings.switcherZoneEdges());
    Optional<OccupancyRequestContext> contextOpt =
        builder.buildContextFromNodes(
            trainName, Optional.ofNullable(route.id()), route.waypoints(), currentIndex, now, 0);
    if (contextOpt.isEmpty()) {
      return;
    }
    OccupancyRequestContext context = contextOpt.get();
    OccupancyRequest request = context.request();
    releaseResourcesNotInRequest(trainName, request.resourceList());
    OccupancyDecision decision = occupancyManager.canEnter(request);
    SignalAspect nextAspect =
        decision.allowed() ? decision.signal() : deriveBlockedAspect(decision, context);
    SignalAspect lastAspect = progressEntry.lastSignal();
    Optional<NodeId> nextNode =
        currentIndex + 1 < route.waypoints().size()
            ? Optional.of(route.waypoints().get(currentIndex + 1))
            : Optional.empty();
    Optional<NodeId> currentNodeOpt =
        currentIndex < route.waypoints().size()
            ? Optional.of(route.waypoints().get(currentIndex))
            : Optional.empty();
    boolean stopAtNextWaypoint = false;
    if (nextNode.isPresent()) {
      Optional<RouteStop> nextStopOpt = routeDefinitions.findStop(route.id(), currentIndex + 1);
      if (nextStopOpt.isPresent()) {
        stopAtNextWaypoint = shouldStopAtWaypoint(nextNode.get(), nextStopOpt.get());
      }
    }
    // 注意：下一节点即便需要“停站”（STOP/TERM waypoint），也不能在此处强制将信号置为 STOP。
    // 否则当列车当前处于静止（例如 AutoStation 停站结束准备出站）时，会被 STOP 信号卡死无法发车。
    // 停车应通过 speed curve + distanceOpt 在接近目标时自然收敛到 0，并在进入目标节点后由推进点逻辑接管 dwell/TERM。
    // 诊断：仅在信号变化时输出（减少刷屏）
    if (lastAspect != nextAspect) {
      debugLogger.accept(
          "信号Tick变化: train="
              + trainName
              + " idx="
              + currentIndex
              + " "
              + lastAspect
              + " -> "
              + nextAspect
              + " allowed="
              + decision.allowed()
              + " blockers="
              + decision.blockers().size());
    }
    if (decision.allowed()) {
      occupancyManager.acquire(request);
    }
    boolean allowLaunch = forceApply || lastAspect != nextAspect;
    if (allowLaunch) {
      updateSignalOrWarn(trainName, nextAspect, now);
    }
    if (currentNodeOpt.isEmpty() || nextNode.isEmpty()) {
      // 若已到终点且无下一目标，检查是否需要注册 Layover
      if (nextNode.isEmpty() && forceApply && currentNodeOpt.isPresent()) {
        handleLayoverRegistrationIfNeeded(trainName, route, currentNodeOpt.get(), properties);
      }
      return;
    }
    OptionalLong distanceOpt = OptionalLong.empty();
    OptionalLong constraintDistanceOpt = OptionalLong.empty();
    boolean needsDistance =
        runtimeSettings.speedCurveEnabled() || runtimeSettings.failoverUnreachableStop();
    if (needsDistance) {
      OptionalLong shortestDistanceOpt =
          resolveShortestDistance(graph, currentNodeOpt.get(), nextNode.get());
      if (runtimeSettings.failoverUnreachableStop()
          && shortestDistanceOpt.isEmpty()
          && !stopAtNextWaypoint) {
        updateSignalOrWarn(trainName, SignalAspect.STOP, now);
        applyControl(
            train,
            properties,
            SignalAspect.STOP,
            route,
            currentNodeOpt.get(),
            nextNode.get(),
            graph,
            false,
            OptionalLong.empty());
        debugLogger.accept(
            "调度 failover: 目标不可达 train="
                + trainName
                + " from="
                + currentNodeOpt.get().value()
                + " to="
                + nextNode.get().value());
        return;
      }
      if (runtimeSettings.speedCurveEnabled() && shortestDistanceOpt.isPresent()) {
        if (!stopAtNextWaypoint) {
          distanceOpt = shortestDistanceOpt;
        }
      }
    }
    // 信号前瞻：计算到前方所有限制点的距离，取最近的作为减速依据
    SignalLookahead.LookaheadResult lookahead = null;
    if (runtimeSettings.speedCurveEnabled()) {
      lookahead = SignalLookahead.compute(decision, context, nextAspect, this::isApproachingNode);
      constraintDistanceOpt = lookahead.minConstraintDistance();
    }
    if (runtimeSettings.speedCurveEnabled()) {
      if (stopAtNextWaypoint && nextAspect != SignalAspect.STOP) {
        // STOP/TERM waypoint 只允许“前方 blocker”触发减速，不使用到下一节点距离，避免提前刹停在牌子前。
        distanceOpt = constraintDistanceOpt;
      } else if (constraintDistanceOpt.isPresent()) {
        if (distanceOpt.isPresent()) {
          distanceOpt =
              OptionalLong.of(Math.min(distanceOpt.getAsLong(), constraintDistanceOpt.getAsLong()));
        } else {
          distanceOpt = constraintDistanceOpt;
        }
      }
    }
    java.util.OptionalDouble targetOverrideBps = java.util.OptionalDouble.empty();
    if (stopAtNextWaypoint && nextAspect != SignalAspect.STOP) {
      double approachSpeed = runtimeSettings.approachSpeedBps();
      if (Double.isFinite(approachSpeed) && approachSpeed > 0.0) {
        targetOverrideBps = java.util.OptionalDouble.of(approachSpeed);
      }
    }
    if (stopAtNextWaypoint
        && shouldLogStopWaypoint(trainName, currentIndex, nextNode.orElse(null), nextAspect)) {
      debugLogger.accept(
          "STOP/TERM waypoint 进站: train="
              + trainName
              + " idx="
              + currentIndex
              + " next="
              + nextNode.get().value()
              + " aspect="
              + nextAspect
              + " allowLaunch="
              + allowLaunch
              + " speedCurve="
              + runtimeSettings.speedCurveEnabled()
              + " failoverUnreachableStop="
              + runtimeSettings.failoverUnreachableStop()
              + " approachSpeedBps="
              + (targetOverrideBps.isPresent() ? targetOverrideBps.getAsDouble() : "-")
              + " distanceOpt="
              + formatOptionalLong(distanceOpt)
              + " constraintDistance="
              + formatOptionalLong(constraintDistanceOpt));
    }
    StallDecision stallDecision = updateStallState(trainName, train, currentIndex, nextAspect);
    if (stallDecision.forceLaunch()) {
      allowLaunch = true;
    }
    applyControl(
        train,
        properties,
        nextAspect,
        route,
        currentNodeOpt.get(),
        nextNode.get(),
        graph,
        allowLaunch,
        distanceOpt,
        lookahead,
        targetOverrideBps);
    if (stallDecision.triggerFailover()) {
      triggerStallFailover(
          train,
          properties,
          trainName,
          route,
          currentNodeOpt.get(),
          nextNode.get(),
          graph,
          distanceOpt);
    }
  }

  /**
   * 终点 Layover 注册：在 REUSE_AT_TERM 模式下，将列车注册到 Layover 待命池。
   *
   * <p>仅在列车到达终点且未被 DSTY 销毁时调用，避免重复注册。
   *
   * @param trainName 列车名
   * @param route 当前线路定义
   * @param terminalNode 终点节点
   * @param properties 列车属性
   */
  /**
   * 终点 Layover 注册：在 REUSE_AT_TERM 模式下，将列车注册到 Layover 待命池。
   *
   * <p>仅在列车到达终点且未被 DSTY 销毁时调用。使用 {@link TerminalKeyResolver#toTerminalKey(NodeId)} 生成标准化的
   * terminalKey，确保后续匹配一致性。
   *
   * <h4>terminalKey 用途</h4>
   *
   * <ul>
   *   <li>作为 Layover 候选列车的分组依据
   *   <li>与新线路首站进行匹配，支持同站不同站台复用
   * </ul>
   *
   * @param trainName 列车名
   * @param route 当前线路定义
   * @param location 终点节点（列车当前位置）
   * @param properties 列车属性
   * @see TerminalKeyResolver
   * @see LayoverRegistry#register(String, String, NodeId, Instant, java.util.Map)
   */
  private void handleLayoverRegistrationIfNeeded(
      String trainName, RouteDefinition route, NodeId location, TrainProperties properties) {
    if (route.lifecycleMode()
        != org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLifecycleMode.REUSE_AT_TERM) {
      return;
    }
    if (layoverRegistry.get(trainName).isPresent()) {
      return;
    }
    // 使用 TerminalKeyResolver 生成标准化 terminalKey，确保匹配一致性
    String terminalKey = TerminalKeyResolver.toTerminalKey(location);

    java.util.Map<String, String> tags = new java.util.HashMap<>();
    if (properties.hasTags()) {
      for (String tag : properties.getTags()) {
        int idx = tag.indexOf('=');
        if (idx > 0) {
          tags.put(tag.substring(0, idx).trim(), tag.substring(idx + 1).trim());
        } else {
          tags.put(tag.trim(), "");
        }
      }
    }
    layoverRegistry.register(trainName, terminalKey, location, Instant.now(), tags);
    debugLogger.accept(
        "Layover 注册: train="
            + trainName
            + " terminalKey="
            + terminalKey
            + " station="
            + TerminalKeyResolver.extractStationName(location));
    layoverRegistry.get(trainName).ifPresent(layoverListener);
  }

  /**
   * 将待命列车投入下一趟运营（复用出车）。
   *
   * <p>验证流程：
   *
   * <ol>
   *   <li>检查列车存在且有效
   *   <li>检查列车位置与线路首站匹配（支持同站不同站台，使用 {@link TerminalKeyResolver}）
   *   <li>构建占用请求并获取许可
   *   <li>成功后写入 Route tags、推进进度、设置 destination
   * </ol>
   *
   * <h4>首站匹配规则</h4>
   *
   * <p>传统检查要求 {@code startIndex == 0}，即列车必须精确位于线路首站节点。 新规则使用 {@link
   * TerminalKeyResolver#matches(String, String)}，允许：
   *
   * <ul>
   *   <li>精确匹配：列车位置与首站 NodeId 相同
   *   <li>站点匹配：列车位于首站的不同站台（同 Station/Depot）
   * </ul>
   *
   * @param candidate 待命列车候选对象
   * @param ticket 分配的任务票据
   * @return 是否成功发车（若占用失败或列车无效则返回 false）
   */
  public boolean dispatchLayover(LayoverCandidate candidate, ServiceTicket ticket) {
    if (candidate == null || ticket == null) {
      return false;
    }
    String trainName = candidate.trainName();
    TrainProperties properties = TrainPropertiesStore.get(trainName);
    if (properties == null || properties.getHolder() == null) {
      debugLogger.accept("Layover 发车失败: 列车未找到 " + trainName);
      layoverRegistry.unregister(trainName);
      return false;
    }
    RuntimeTrainHandle trainHandle = new TrainCartsRuntimeHandle(properties.getHolder());
    if (!trainHandle.isValid()) {
      debugLogger.accept("Layover 发车失败: 列车无效 " + trainName);
      layoverRegistry.unregister(trainName);
      return false;
    }

    Optional<RouteDefinition> routeOpt = routeDefinitions.findById(ticket.routeId());
    if (routeOpt.isEmpty()) {
      debugLogger.accept("Layover 发车失败: Route 未找到 " + ticket.routeId());
      return false;
    }
    RouteDefinition route = routeOpt.get();
    NodeId startNode = candidate.locationNodeId();

    // 使用 TerminalKeyResolver 进行首站匹配，支持同站不同站台复用
    NodeId routeFirstNode = route.waypoints().get(0);
    String startTerminalKey = TerminalKeyResolver.toTerminalKey(startNode);
    String routeFirstTerminalKey = TerminalKeyResolver.toTerminalKey(routeFirstNode);

    if (!TerminalKeyResolver.matches(startTerminalKey, routeFirstTerminalKey)) {
      debugLogger.accept(
          "Layover 发车失败: 位置与首站不匹配 train="
              + trainName
              + " location="
              + startNode.value()
              + " routeFirst="
              + routeFirstNode.value());
      return false;
    }

    // 使用精确匹配确定 startIndex，若不匹配则回退为 0（同站不同站台场景）
    int startIndex = RouteIndexResolver.resolveCurrentIndex(route, OptionalInt.empty(), startNode);
    if (startIndex < 0) {
      // 同站不同站台：从索引 0 开始
      startIndex = 0;
      debugLogger.accept(
          "Layover 发车: 同站不同站台复用 train="
              + trainName
              + " location="
              + startNode.value()
              + " routeFirst="
              + routeFirstNode.value());
    }

    if (startIndex + 1 >= route.waypoints().size()) {
      debugLogger.accept("Layover 发车失败: 线路无下一站 train=" + trainName);
      return false;
    }

    Instant now = Instant.now();
    Optional<RailGraph> graphOpt = resolveGraph(trainHandle.worldId(), now);
    if (graphOpt.isEmpty()) {
      debugLogger.accept("Layover 发车失败: 图快照缺失 train=" + trainName);
      return false;
    }
    RailGraph graph = graphOpt.get();
    ConfigManager.RuntimeSettings runtime = configManager.current().runtimeSettings();
    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(
            graph,
            runtime.lookaheadEdges(),
            runtime.minClearEdges(),
            runtime.switcherZoneEdges(),
            debugLogger);
    Optional<OccupancyRequestContext> ctxOpt =
        builder.buildContextFromNodes(
            trainName,
            Optional.ofNullable(route.id()),
            route.waypoints(),
            startIndex,
            now,
            ticket.priority());
    if (ctxOpt.isEmpty()) {
      debugLogger.accept("Layover 发车失败: 无法构建占用请求 train=" + trainName);
      return false;
    }
    OccupancyRequest request = ctxOpt.get().request();
    OccupancyDecision decision = occupancyManager.canEnter(request);
    if (!decision.allowed()) {
      debugLogger.accept("Layover 发车受阻: train=" + trainName + " signal=" + decision.signal());
      return false;
    }

    releaseResourcesNotInRequest(trainName, request.resourceList());
    occupancyManager.acquire(request);
    layoverRegistry.unregister(trainName);

    TrainTagHelper.writeTag(
        properties, RouteProgressRegistry.TAG_ROUTE_ID, ticket.routeId().toString());
    route
        .metadata()
        .ifPresent(
            meta -> {
              TrainTagHelper.writeTag(
                  properties, RouteProgressRegistry.TAG_OPERATOR_CODE, meta.operator());
              TrainTagHelper.writeTag(
                  properties, RouteProgressRegistry.TAG_LINE_CODE, meta.lineId());
              TrainTagHelper.writeTag(
                  properties, RouteProgressRegistry.TAG_ROUTE_CODE, meta.serviceId());
            });
    if (route.metadata().isEmpty()) {
      TrainTagHelper.removeTagKey(properties, RouteProgressRegistry.TAG_OPERATOR_CODE);
      TrainTagHelper.removeTagKey(properties, RouteProgressRegistry.TAG_LINE_CODE);
      TrainTagHelper.removeTagKey(properties, RouteProgressRegistry.TAG_ROUTE_CODE);
    }
    TrainTagHelper.writeTag(properties, "FTA_TICKET_ID", ticket.ticketId());
    progressRegistry.advance(trainName, ticket.routeId(), route, startIndex, properties, now);
    invalidateTrainEta(trainName);

    NodeId nextNode = route.waypoints().get(startIndex + 1);
    String destinationName = resolveDestinationName(nextNode);
    if (destinationName == null || destinationName.isBlank()) {
      destinationName = nextNode.value();
    }
    properties.clearDestinationRoute();
    trainHandle.setDestination(destinationName);

    // 直接触发发车，不依赖 refreshSignal 的复杂逻辑
    // refreshSignal 可能因为图路径问题导致 failover 而不发车
    TrainConfig config = trainConfigResolver.resolve(properties, configManager.current());
    double targetBps = configManager.current().graphSettings().defaultSpeedBlocksPerSecond();
    double targetBpt = targetBps / 20.0; // blocks/s -> blocks/tick
    double accelBpt2 = config.accelBps2() / 400.0; // blocks/s^2 -> blocks/tick^2
    properties.setSpeedLimit(targetBpt);
    java.util.Optional<org.bukkit.block.BlockFace> fallbackDirection =
        resolveLaunchDirectionByGraph(graph, route.waypoints().get(startIndex), nextNode);
    trainHandle.launchWithFallback(fallbackDirection, targetBpt, accelBpt2);
    updateSignalOrWarn(trainName, SignalAspect.PROCEED, now);

    debugLogger.accept("Layover 发车成功: train=" + trainName + " route=" + route.id().value());
    return true;
  }

  /**
   * 当 canEnter 阻塞时，基于 blocker 在 lookahead 路径中的位置细分信号等级。
   *
   * <p>语义：
   *
   * <ul>
   *   <li>CAUTION：下一个区间（第 1 段边或下一节点）会遇到 stop，应更早准备停车。
   *   <li>PROCEED_WITH_CAUTION：前两个区间内存在 stop，用于提前减速提示。
   *   <li>STOP：无法定位 blocker 位置（例如仅 CONFLICT 阻塞）时的保守回退。
   * </ul>
   */
  private SignalAspect deriveBlockedAspect(
      OccupancyDecision decision, OccupancyRequestContext context) {
    if (decision == null || context == null || decision.blockers().isEmpty()) {
      return SignalAspect.STOP;
    }
    List<NodeId> nodes = context.pathNodes();
    List<RailEdge> edges = context.edges();
    int bestPosition = Integer.MAX_VALUE;
    for (OccupancyClaim claim : decision.blockers()) {
      if (claim == null || claim.resource() == null) {
        continue;
      }
      OccupancyResource resource = claim.resource();
      if (resource.kind() == ResourceKind.EDGE && edges != null) {
        for (int i = 0; i < edges.size(); i++) {
          RailEdge edge = edges.get(i);
          if (edge == null) {
            continue;
          }
          if (OccupancyResource.forEdge(edge.id()).key().equals(resource.key())) {
            bestPosition = Math.min(bestPosition, i + 1);
            break;
          }
        }
        continue;
      }
      if (resource.kind() == ResourceKind.NODE && nodes != null) {
        for (int i = 0; i < nodes.size(); i++) {
          NodeId node = nodes.get(i);
          if (node == null) {
            continue;
          }
          if (node.value().equals(resource.key()) && i > 0) {
            bestPosition = Math.min(bestPosition, i);
            break;
          }
        }
      }
    }
    if (bestPosition == Integer.MAX_VALUE) {
      return SignalAspect.STOP;
    }
    return bestPosition <= 1 ? SignalAspect.CAUTION : SignalAspect.PROCEED_WITH_CAUTION;
  }

  /** 更新信号并在 entry 缺失时给出一次性告警，避免静默漂移。 */
  private void updateSignalOrWarn(String trainName, SignalAspect aspect, Instant now) {
    if (trainName == null || trainName.isBlank() || aspect == null) {
      return;
    }
    boolean updated = progressRegistry.updateSignal(trainName, aspect, now);
    String key = trainName.toLowerCase(Locale.ROOT);
    if (updated) {
      missingSignalWarned.remove(key);
      return;
    }
    if (missingSignalWarned.add(key)) {
      debugLogger.accept("信号更新失败: entry 缺失 train=" + trainName + " aspect=" + aspect.name());
    }
  }

  /**
   * 将信号许可映射为速度/制动控制。
   *
   * <p>PROCEED 发车并恢复巡航速度；CAUTION 限速；STOP 停车并清空限速。
   */
  private void applyControl(
      RuntimeTrainHandle train,
      TrainProperties properties,
      SignalAspect aspect,
      RouteDefinition route,
      NodeId currentNode,
      NodeId nextNode,
      RailGraph graph,
      boolean allowLaunch,
      OptionalLong distanceOpt) {
    applyControl(
        train,
        properties,
        aspect,
        route,
        currentNode,
        nextNode,
        graph,
        allowLaunch,
        distanceOpt,
        null,
        java.util.OptionalDouble.empty());
  }

  /**
   * 将信号许可映射为速度/制动控制（含前瞻数据）。
   *
   * <p>PROCEED 发车并恢复巡航速度；CAUTION 限速；STOP 停车并清空限速。
   *
   * @param lookahead 前瞻结果（可选），用于记录诊断数据
   */
  private void applyControl(
      RuntimeTrainHandle train,
      TrainProperties properties,
      SignalAspect aspect,
      RouteDefinition route,
      NodeId currentNode,
      NodeId nextNode,
      RailGraph graph,
      boolean allowLaunch,
      OptionalLong distanceOpt,
      SignalLookahead.LookaheadResult lookahead,
      java.util.OptionalDouble targetOverrideBps) {
    if (properties == null || aspect == null || route == null) {
      return;
    }
    TrainConfig config = trainConfigResolver.resolve(properties, configManager.current());
    // 先获取边限速作为 PROCEED 基准（而非固定 defaultSpeed）
    double edgeLimit =
        resolveEdgeSpeedLimit(train, graph, currentNode, nextNode, configManager.current());
    double targetBps =
        resolveTargetSpeed(
            train != null ? train.worldId() : null, aspect, route, nextNode, edgeLimit);
    if (targetOverrideBps != null && targetOverrideBps.isPresent()) {
      double override = targetOverrideBps.getAsDouble();
      if (Double.isFinite(override) && override > 0.0) {
        targetBps = Math.min(targetBps, override);
      }
    }
    // 发车方向由 TrainCarts 依据 destination 自动推导（见 TrainCartsRuntimeHandle#launch），此处不写入额外 tag。
    java.util.Optional<org.bukkit.block.BlockFace> launchFallbackDirection =
        resolveLaunchDirectionByGraph(graph, currentNode, nextNode);
    trainLaunchManager.applyControl(
        train,
        properties,
        aspect,
        targetBps,
        config,
        allowLaunch,
        distanceOpt,
        launchFallbackDirection,
        configManager.current().runtimeSettings());

    // 记录诊断数据
    recordDiagnostics(
        train,
        properties,
        aspect,
        route,
        currentNode,
        nextNode,
        targetBps,
        edgeLimit,
        allowLaunch,
        lookahead);
  }

  /** 记录控车诊断数据到缓存。 */
  private void recordDiagnostics(
      RuntimeTrainHandle train,
      TrainProperties properties,
      SignalAspect aspect,
      RouteDefinition route,
      NodeId currentNode,
      NodeId nextNode,
      double targetBps,
      double edgeLimitBps,
      boolean allowLaunch,
      SignalLookahead.LookaheadResult lookahead) {
    if (properties == null) {
      return;
    }
    String trainName = properties.getTrainName();
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    Instant now = Instant.now();
    double currentSpeedBps =
        train != null ? train.currentSpeedBlocksPerTick() * SPEED_TICKS_PER_SECOND : 0.0;

    var builder =
        new ControlDiagnostics.Builder()
            .trainName(trainName)
            .routeId(route != null ? route.id() : null)
            .currentNode(currentNode)
            .nextNode(nextNode)
            .currentSpeedBps(currentSpeedBps)
            .targetSpeedBps(targetBps)
            .edgeLimitBps(edgeLimitBps)
            .currentSignal(aspect)
            .allowLaunch(allowLaunch)
            .sampledAt(now);

    if (lookahead != null) {
      builder.lookahead(lookahead);
    } else {
      builder.effectiveSignal(aspect);
    }

    diagnosticsCache.put(builder.build(), now);
  }

  /**
   * 由调度图推导“发车方向”兜底。
   *
   * <p>仅在 TrainCarts 无法从 destination 推导方向时使用，避免依赖 PathNode 创建。
   */
  private java.util.Optional<org.bukkit.block.BlockFace> resolveLaunchDirectionByGraph(
      RailGraph graph, NodeId currentNode, NodeId nextNode) {
    if (graph == null || currentNode == null || nextNode == null) {
      return java.util.Optional.empty();
    }
    java.util.Optional<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> fromOpt =
        graph.findNode(currentNode);
    java.util.Optional<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> toOpt =
        graph.findNode(nextNode);
    if (fromOpt.isEmpty() || toOpt.isEmpty()) {
      return java.util.Optional.empty();
    }
    org.bukkit.util.Vector from = fromOpt.get().worldPosition();
    org.bukkit.util.Vector to = toOpt.get().worldPosition();
    if (from == null || to == null) {
      return java.util.Optional.empty();
    }
    double dx = to.getX() - from.getX();
    double dz = to.getZ() - from.getZ();
    if (!Double.isFinite(dx) || !Double.isFinite(dz)) {
      return java.util.Optional.empty();
    }
    if (Math.abs(dx) < 1.0e-6 && Math.abs(dz) < 1.0e-6) {
      return java.util.Optional.empty();
    }
    if (Math.abs(dx) >= Math.abs(dz)) {
      return java.util.Optional.of(
          dx >= 0.0 ? org.bukkit.block.BlockFace.EAST : org.bukkit.block.BlockFace.WEST);
    }
    return java.util.Optional.of(
        dz >= 0.0 ? org.bukkit.block.BlockFace.SOUTH : org.bukkit.block.BlockFace.NORTH);
  }

  private OptionalLong resolveShortestDistance(RailGraph graph, NodeId from, NodeId to) {
    if (graph == null || from == null || to == null) {
      return OptionalLong.empty();
    }
    return pathFinder
        .shortestPath(graph, from, to, RailGraphPathFinder.Options.shortestDistance())
        .map(path -> OptionalLong.of(path.totalLengthBlocks()))
        .orElse(OptionalLong.empty());
  }

  // 说明：历史上曾通过 tag/反向来修正发车方向；现在统一交由 TrainCartsRuntimeHandle 在 launch 时按 destination 推导。

  /**
   * Waypoint 停站时强制居中列车（对齐牌子中心），以模拟 AutoStation 的停车体验。
   *
   * <p>仅在 waypoint STOP/TERM 停站触发时调用；异常时静默降级为普通停车。
   */
  private void centerTrainAtWaypoint(SignActionEvent event, String trainName, NodeId nodeId) {
    if (event == null || !event.hasGroup()) {
      return;
    }
    try {
      var group = event.getGroup();
      com.bergerkiller.bukkit.tc.Station station = new com.bergerkiller.bukkit.tc.Station(event);
      boolean hadAction = group.getActions().hasAction();
      group.getActions().launchReset();
      double centerDistance = resolveCenterDistance(event, group);
      String centerText = formatLocation(event.getCenterLocation());
      String cartText = formatLocation(resolveCenterCartLocation(group));
      String signText = formatLocation(event.getBlock().getLocation());
      boolean realSign = event.getTrackedSign() != null && event.getTrackedSign().isRealSign();
      station.centerTrain();
      boolean hasAction = group.getActions().hasAction();
      debugLogger.accept(
          "Waypoint 居中: train="
              + trainName
              + " node="
              + (nodeId != null ? nodeId.value() : "-")
              + " dist="
              + (Double.isFinite(centerDistance)
                  ? String.format(java.util.Locale.ROOT, "%.3f", centerDistance)
                  : "-")
              + " action="
              + (hadAction ? "Y" : "N")
              + "->"
              + (hasAction ? "Y" : "N")
              + " center="
              + centerText
              + " cart="
              + cartText
              + " sign="
              + signText
              + " real="
              + realSign);
    } catch (Throwable ex) {
      debugLogger.accept(
          "Waypoint 居中失败: train="
              + trainName
              + " node="
              + (nodeId != null ? nodeId.value() : "-")
              + " error="
              + ex.getClass().getSimpleName());
    }
  }

  private org.bukkit.Location resolveCenterCartLocation(
      com.bergerkiller.bukkit.tc.controller.MinecartGroup group) {
    if (group == null) {
      return null;
    }
    com.bergerkiller.bukkit.tc.controller.MinecartMember<?> cart = group.middle();
    if (cart == null || cart.getRailTracker() == null) {
      return null;
    }
    com.bergerkiller.bukkit.tc.controller.components.RailState state =
        cart.getRailTracker().getState();
    if (state == null) {
      return null;
    }
    return state.positionLocation();
  }

  /**
   * Waypoint 停站等待流程：只在 GROUP_ENTER 触发，并等待列车停稳后再居中/进入 dwell/进入 WaitState。
   *
   * <p>语义对齐 AutoStation 的停稳判定，避免 MEMBER_ENTER 过早触发导致居中不稳定。
   */
  private void scheduleWaypointStopSequence(
      SignActionEvent event, SignNodeDefinition definition, String trainName, int dwellSeconds) {
    if (event == null || definition == null || trainName == null || trainName.isBlank()) {
      return;
    }
    if (!event.hasGroup() || event.getGroup() == null) {
      return;
    }
    com.bergerkiller.bukkit.tc.controller.MinecartGroup group = event.getGroup();
    if (!group.isValid()) {
      return;
    }
    JavaPlugin plugin = resolveSchedulerPlugin();
    if (plugin == null || !plugin.isEnabled()) {
      handleWaypointStop(event, definition, trainName, dwellSeconds, true);
      return;
    }
    String key = trainName.toLowerCase(java.util.Locale.ROOT);
    String sessionId = Long.toString(waypointStopCounter.incrementAndGet());
    if (waypointStopSessions.putIfAbsent(key, sessionId) != null) {
      return;
    }
    new org.bukkit.scheduler.BukkitRunnable() {
      private int waitedTicks = 0;
      private int stoppedTicks = 0;

      @Override
      public void run() {
        if (!group.isValid()) {
          clearWaypointStopSession(key, sessionId);
          cancel();
          return;
        }
        if (!sessionId.equals(waypointStopSessions.get(key))) {
          cancel();
          return;
        }
        if (!group.isMoving()) {
          stoppedTicks++;
          if (stoppedTicks >= WAYPOINT_STOP_STABLE_TICKS) {
            clearWaypointStopSession(key, sessionId);
            cancel();
            handleWaypointStop(event, definition, trainName, dwellSeconds, false);
            return;
          }
        } else {
          stoppedTicks = 0;
        }
        waitedTicks++;
        if (waitedTicks >= WAYPOINT_STOP_WAIT_TIMEOUT_TICKS) {
          clearWaypointStopSession(key, sessionId);
          cancel();
          handleWaypointStop(event, definition, trainName, dwellSeconds, true);
        }
      }
    }.runTaskTimer(plugin, 1L, 1L);
  }

  /**
   * Waypoint 停站完成处理：执行居中、启动 dwell，并用 WaitState 真正 hold 住列车。
   *
   * @param timedOut 是否由超时兜底触发
   */
  private void handleWaypointStop(
      SignActionEvent event,
      SignNodeDefinition definition,
      String trainName,
      int dwellSeconds,
      boolean timedOut) {
    if (event == null || definition == null || trainName == null || trainName.isBlank()) {
      return;
    }
    com.bergerkiller.bukkit.tc.controller.MinecartGroup group = event.getGroup();
    if (group == null || !group.isValid()) {
      return;
    }
    centerTrainAtWaypoint(event, trainName, definition.nodeId());
    if (dwellSeconds > 0 && !group.isMoving() && dwellRegistry != null) {
      if (dwellRegistry.remainingSeconds(trainName).isEmpty()) {
        dwellRegistry.start(trainName, dwellSeconds);
      }
    }
    group.getActions().addActionWaitState();
    if (timedOut) {
      debugLogger.accept(
          "Waypoint 停站超时: train=" + trainName + " node=" + definition.nodeId().value());
    }
  }

  private void clearWaypointStopSession(String key, String sessionId) {
    if (key == null || sessionId == null) {
      return;
    }
    waypointStopSessions.remove(key, sessionId);
  }

  private JavaPlugin resolveSchedulerPlugin() {
    try {
      return JavaPlugin.getProvidingPlugin(RuntimeDispatchService.class);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private double resolveCenterDistance(
      SignActionEvent event, com.bergerkiller.bukkit.tc.controller.MinecartGroup group) {
    if (event == null || group == null) {
      return Double.NaN;
    }
    org.bukkit.Location center = event.getCenterLocation();
    org.bukkit.Location cart = resolveCenterCartLocation(group);
    if (center == null || cart == null) {
      return Double.NaN;
    }
    return center.distance(cart);
  }

  private String formatLocation(org.bukkit.Location location) {
    if (location == null || location.getWorld() == null) {
      return "-";
    }
    return location.getWorld().getName()
        + "("
        + location.getBlockX()
        + ","
        + location.getBlockY()
        + ","
        + location.getBlockZ()
        + ")";
  }

  /**
   * 解析 waypoint 停站的“软刹车距离”。
   *
   * <p>基于当前速度与制动能力估算制动距离，并乘以固定比例，确保速度以指数方式逐步降低， 避免 waypoint 触发后出现“立即清零”的急刹体验。
   */
  private OptionalLong resolveSoftStopDistance(
      RuntimeTrainHandle train, TrainProperties properties) {
    if (train == null || properties == null) {
      return OptionalLong.empty();
    }
    ConfigManager.RuntimeSettings settings = configManager.current().runtimeSettings();
    if (!settings.speedCurveEnabled()) {
      return OptionalLong.empty();
    }
    TrainConfig config = trainConfigResolver.resolve(properties, configManager.current());
    double currentBps = train.currentSpeedBlocksPerTick() * SPEED_TICKS_PER_SECOND;
    if (!Double.isFinite(currentBps) || currentBps <= 0.0) {
      return OptionalLong.empty();
    }
    double decel = config.decelBps2();
    if (!Double.isFinite(decel) || decel <= 0.0) {
      return OptionalLong.empty();
    }
    double brakingDistance = (currentBps * currentBps) / (2.0 * decel);
    if (!Double.isFinite(brakingDistance) || brakingDistance <= 0.0) {
      return OptionalLong.empty();
    }
    double effective = brakingDistance * WAYPOINT_STOP_BRAKE_FACTOR;
    if (!Double.isFinite(effective) || effective <= 0.0) {
      return OptionalLong.empty();
    }
    long blocks = (long) Math.ceil(effective);
    return OptionalLong.of(Math.max(1L, blocks));
  }

  private static String formatOptionalLong(OptionalLong value) {
    return value != null && value.isPresent() ? String.valueOf(value.getAsLong()) : "-";
  }

  private boolean shouldLogStopWaypoint(
      String trainName, int currentIndex, NodeId nextNode, SignalAspect aspect) {
    if (trainName == null || trainName.isBlank()) {
      return true;
    }
    String trainKey = trainName.trim().toLowerCase(java.util.Locale.ROOT);
    String nextText = nextNode != null ? nextNode.value() : "-";
    String aspectText = aspect != null ? aspect.name() : "-";
    String stateKey = currentIndex + ":" + nextText + ":" + aspectText;
    String prev = stopWaypointLogState.put(trainKey, stateKey);
    return prev == null || !prev.equals(stateKey);
  }

  private boolean shouldHandleProgressTrigger(
      String trainName, NodeId nodeId, int currentIndex, Instant now) {
    if (trainName == null || trainName.isBlank() || nodeId == null || now == null) {
      return true;
    }
    String trainKey = trainName.trim().toLowerCase(java.util.Locale.ROOT);
    String stateKey = nodeId.value() + "#" + currentIndex;
    long nowMs = now.toEpochMilli();
    ProgressTriggerState prev = progressTriggerState.get(trainKey);
    if (prev != null
        && prev.stateKey().equalsIgnoreCase(stateKey)
        && nowMs - prev.atMillis() < PROGRESS_TRIGGER_DEDUP_MS) {
      return false;
    }
    progressTriggerState.put(trainKey, new ProgressTriggerState(stateKey, nowMs));
    return true;
  }

  private void triggerStallFailover(
      RuntimeTrainHandle train,
      TrainProperties properties,
      String trainName,
      RouteDefinition route,
      NodeId currentNode,
      NodeId nextNode,
      RailGraph graph,
      OptionalLong distanceOpt) {
    if (train == null || properties == null || trainName == null || trainName.isBlank()) {
      return;
    }
    String destination = resolveDestinationName(nextNode);
    if (destination == null || destination.isBlank()) {
      return;
    }
    properties.clearDestinationRoute();
    properties.setDestination(destination);
    applyControl(
        train,
        properties,
        SignalAspect.PROCEED,
        route,
        currentNode,
        nextNode,
        graph,
        true,
        distanceOpt);
    debugLogger.accept(
        "调度 failover: 低速重下发 destination train=" + trainName + " dest=" + destination);
  }

  /**
   * 列车销毁处理：在 DSTY 节点或命令触发下销毁列车。
   *
   * <p>优先于终点停车逻辑，避免 DSTY 节点被终点停车拦截。
   *
   * @param train 控制的列车句柄
   * @param properties 列车属性
   * @param trainName 列车名
   * @param reason 销毁原因（如 DSTY/命令）
   */
  private void handleDestroy(
      RuntimeTrainHandle train, TrainProperties properties, String trainName, String reason) {
    if (properties == null || trainName == null || trainName.isBlank()) {
      return;
    }
    layoverRegistry.unregister(trainName);
    if (train != null) {
      train.destroy();
    }
    if (occupancyManager != null) {
      occupancyManager.releaseByTrain(trainName);
    }
    progressRegistry.remove(trainName);
    stallStates.remove(trainName.toLowerCase(java.util.Locale.ROOT));
    stopWaypointLogState.remove(trainName.toLowerCase(java.util.Locale.ROOT));
    TrainTagHelper.removeTagKey(properties, RouteProgressRegistry.TAG_ROUTE_INDEX);
    TrainTagHelper.removeTagKey(properties, RouteProgressRegistry.TAG_ROUTE_UPDATED_AT);
    debugLogger.accept("调度销毁: reason=" + reason + " train=" + trainName);
  }

  private StallDecision updateStallState(
      String trainName, RuntimeTrainHandle train, int currentIndex, SignalAspect aspect) {
    if (train == null || trainName == null || trainName.isBlank()) {
      return StallDecision.none();
    }
    ConfigManager.RuntimeSettings settings = configManager.current().runtimeSettings();
    if (settings.failoverStallSpeedBps() <= 0.0 || settings.failoverStallTicks() <= 0) {
      return StallDecision.none();
    }
    if (aspect == SignalAspect.STOP) {
      stallStates.remove(trainName.toLowerCase(java.util.Locale.ROOT));
      return StallDecision.none();
    }
    double currentBps = train.currentSpeedBlocksPerTick() * SPEED_TICKS_PER_SECOND;
    String key = trainName.toLowerCase(java.util.Locale.ROOT);
    StallState state = stallStates.computeIfAbsent(key, k -> new StallState());
    if (state.lastIndex != currentIndex) {
      state.lastIndex = currentIndex;
      state.ticks = 0;
      return StallDecision.none();
    }
    if (currentBps > settings.failoverStallSpeedBps()) {
      state.ticks = 0;
      return StallDecision.none();
    }
    state.ticks++;
    if (state.ticks < settings.failoverStallTicks()) {
      return StallDecision.none();
    }
    state.ticks = 0;
    return new StallDecision(true, true);
  }

  /**
   * 判定当前节点是否应执行 DSTY 销毁。
   *
   * <p>DSTY 在 DSL 中带目标 NodeId（如 {@code DSTY <NodeId>}），且历史版本可能把 DSTY 写在其他 stop 的 notes
   * 上。为避免在错误节点提前销毁，只有当“目标 NodeId == 当前节点”时才触发。
   */
  /**
   * 判断当前节点是否为 DSTY（销毁目标）。
   *
   * @param stop 当前 RouteStop
   * @param currentNode 当前节点
   * @return 是否应销毁
   */
  private boolean shouldDestroyAt(RouteStop stop, NodeId currentNode) {
    if (stop == null || currentNode == null) {
      return false;
    }
    Optional<String> targetOpt = findDirectiveTarget(stop, "DSTY");
    if (targetOpt.isEmpty()) {
      return false;
    }
    String target = targetOpt.get();
    if (target.isBlank()) {
      return false;
    }
    return currentNode.value().equalsIgnoreCase(target);
  }

  /**
   * DSTY 兜底判定：当 stop 未携带指令 notes 时，允许“最后一站 + depot + PASS”触发销毁。
   *
   * <p>用于兼容历史数据中 DSTY notes 缺失的情况，避免 RETURN 线路无法回收。
   */
  private boolean shouldDestroyAtFallback(
      RouteStop stop, int currentIndex, RouteDefinition route, SignNodeDefinition definition) {
    if (stop == null || route == null || definition == null) {
      return false;
    }
    if (definition.nodeType() != NodeType.DEPOT) {
      return false;
    }
    if (stop.notes().isPresent() && !stop.notes().get().isBlank()) {
      return false;
    }
    if (stop.passType() != RouteStopPassType.PASS) {
      return false;
    }
    int lastIndex = Math.max(0, route.waypoints().size() - 1);
    return currentIndex == lastIndex;
  }

  private boolean matchesDstyTarget(RouteDefinition route, NodeId currentNode) {
    if (route == null || currentNode == null) {
      return false;
    }
    List<RouteStop> stops = routeDefinitions.listStops(route.id());
    if (stops.isEmpty()) {
      return false;
    }
    for (RouteStop stop : stops) {
      Optional<String> targetOpt = findDirectiveTarget(stop, "DSTY");
      if (targetOpt.isEmpty()) {
        continue;
      }
      String target = targetOpt.get();
      if (!target.isBlank() && currentNode.value().equalsIgnoreCase(target)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 判断当前 waypoint 是否需要“停站”语义。
   *
   * <p>仅 waypoint 节点且 passType 不是 PASS 时触发；站台停站仍由 AutoStation 负责。
   */
  private boolean shouldStopAtWaypoint(SignNodeDefinition definition, RouteStop stop) {
    if (definition == null || stop == null) {
      return false;
    }
    if (definition.nodeType() != NodeType.WAYPOINT) {
      return false;
    }
    return stop.passType() != RouteStopPassType.PASS;
  }

  private boolean shouldStopAtWaypoint(NodeId nodeId, RouteStop stop) {
    if (nodeId == null || stop == null) {
      return false;
    }
    if (stop.passType() == RouteStopPassType.PASS) {
      return false;
    }
    if (isStationNode(nodeId) || isDepotNode(nodeId)) {
      return false;
    }
    Optional<WaypointKind> kindOpt =
        SignTextParser.parseWaypointLike(nodeId.value(), NodeType.WAYPOINT)
            .flatMap(SignNodeDefinition::waypointMetadata)
            .map(meta -> meta.kind());
    if (kindOpt.isPresent()) {
      WaypointKind kind = kindOpt.get();
      if (kind == WaypointKind.STATION || kind == WaypointKind.DEPOT) {
        return false;
      }
    }
    return true;
  }

  /** 解析 waypoint 停站时长（秒），缺失时回退默认值。 */
  private int resolveWaypointDwellSeconds(RouteStop stop) {
    if (stop == null) {
      return 0;
    }
    return stop.dwellSeconds().orElse(DEFAULT_WAYPOINT_DWELL_SECONDS);
  }

  private Optional<String> findDirectiveTarget(RouteStop stop, String prefix) {
    if (stop == null || prefix == null || prefix.isBlank() || stop.notes().isEmpty()) {
      return Optional.empty();
    }
    String raw = stop.notes().orElse("");
    if (raw.isBlank()) {
      return Optional.empty();
    }
    String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
    for (String line : normalized.split("\n", -1)) {
      if (line == null) {
        continue;
      }
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      if (!ACTION_PREFIX_PATTERN.matcher(trimmed).find()) {
        continue;
      }
      String normalizedAction = normalizeActionLine(trimmed);
      String actualPrefix = firstSegment(normalizedAction).trim();
      if (!actualPrefix.equalsIgnoreCase(prefix)) {
        continue;
      }
      String rest = normalizedAction.substring(actualPrefix.length()).trim();
      if (rest.isBlank()) {
        continue;
      }
      // DSTY/CRET 语法使用空格分隔；这里取整段 remainder 作为目标 NodeId（不支持带空格的 NodeId）。
      int ws = rest.indexOf(' ');
      if (ws >= 0) {
        rest = rest.substring(0, ws).trim();
      }
      if (!rest.isBlank()) {
        return Optional.of(rest);
      }
    }
    return Optional.empty();
  }

  private static String firstSegment(String line) {
    if (line == null || line.isEmpty()) {
      return "";
    }
    int idx = 0;
    while (idx < line.length()) {
      char ch = line.charAt(idx);
      if (Character.isWhitespace(ch) || ch == ':') {
        break;
      }
      idx++;
    }
    return line.substring(0, idx);
  }

  private static String normalizeActionLine(String line) {
    String trimmed = line.trim();
    java.util.regex.Matcher matcher = ACTION_PREFIX_PATTERN.matcher(trimmed);
    if (!matcher.find()) {
      return trimmed;
    }
    String prefix = matcher.group(1).toUpperCase(java.util.Locale.ROOT);
    String rest = trimmed.substring(prefix.length()).trim();
    if (rest.isEmpty()) {
      return prefix;
    }
    if (rest.startsWith(":")) {
      rest = rest.substring(1).trim();
    }
    if ("CRET".equals(prefix) || "DSTY".equals(prefix)) {
      return rest.isEmpty() ? prefix : prefix + " " + rest;
    }
    return prefix + ":" + rest;
  }

  private static final class StallState {
    private int lastIndex = -1;
    private int ticks = 0;
  }

  private record ProgressTriggerState(String stateKey, long atMillis) {}

  private record StallDecision(boolean forceLaunch, boolean triggerFailover) {
    private static StallDecision none() {
      return new StallDecision(false, false);
    }
  }

  /** 处理列车改名：迁移进度缓存并释放旧名占用，随后写回当前名称 tag。 */
  /**
   * 列车重命名处理：如有必要，自动同步列车名与属性。
   *
   * @param properties 列车属性
   * @param trainName 当前列车名
   */
  void handleRenameIfNeeded(TrainProperties properties, String trainName) {
    if (properties == null || trainName == null || trainName.isBlank()) {
      return;
    }
    Optional<String> previousOpt =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_TRAIN_NAME);
    if (previousOpt.isPresent()) {
      String previous = previousOpt.get();
      if (!previous.equalsIgnoreCase(trainName)) {
        progressRegistry.rename(previous, trainName);
        if (occupancyManager != null) {
          occupancyManager.releaseByTrain(previous);
        }
      }
    }
    TrainTagHelper.writeTag(properties, RouteProgressRegistry.TAG_TRAIN_NAME, trainName);
  }

  /**
   * 释放“超出当前占用窗口”的资源。
   *
   * <p>用于事件反射式占用：列车推进后即时释放窗口外资源，不再等待“基于时间”的过期。
   */
  private void releaseResourcesNotInRequest(
      String trainName, List<OccupancyResource> keepResources) {
    if (trainName == null || trainName.isBlank() || occupancyManager == null) {
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
      occupancyManager.releaseResource(claim.resource(), Optional.of(trainName));
    }
  }

  /**
   * 根据许可等级与进站限速计算目标速度（blocks/s）。
   *
   * <p>PROCEED 基准速度取边限速（若有效），否则回退到调度图默认速度；警示信号使用连通分量的 caution 速度上限（无覆盖时回退为配置默认值）。
   */
  private double resolveTargetSpeed(
      UUID worldId, SignalAspect aspect, RouteDefinition route, NodeId nextNode, double edgeLimit) {
    double defaultSpeed = configManager.current().graphSettings().defaultSpeedBlocksPerSecond();
    // PROCEED 优先使用边限速，无效时回退 defaultSpeed
    double proceedBase = edgeLimit > 0.0 ? edgeLimit : defaultSpeed;
    double base =
        switch (aspect) {
          case PROCEED -> proceedBase;
          case PROCEED_WITH_CAUTION, CAUTION -> resolveCautionSpeed(worldId, nextNode);
          case STOP -> 0.0;
        };
    double approachLimit = configManager.current().runtimeSettings().approachSpeedBps();
    if (aspect == SignalAspect.PROCEED && approachLimit > 0.0 && isStationNode(nextNode)) {
      return Math.min(base, approachLimit);
    }
    double depotLimit = configManager.current().runtimeSettings().approachDepotSpeedBps();
    if (aspect == SignalAspect.PROCEED && depotLimit > 0.0 && isDepotNode(nextNode)) {
      return Math.min(base, depotLimit);
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

  private boolean isDepotNode(NodeId nodeId) {
    if (nodeId == null) {
      return false;
    }
    Optional<SignNodeDefinition> def =
        signNodeRegistry.findByNodeId(nodeId, null).map(SignNodeRegistry.SignNodeInfo::definition);
    if (def.isEmpty()) {
      return false;
    }
    if (def.get().nodeType() == NodeType.DEPOT) {
      return true;
    }
    return def.get()
        .waypointMetadata()
        .map(meta -> meta.kind() == WaypointKind.DEPOT)
        .orElse(false);
  }

  /**
   * 判断节点是否需要 approaching 限速（Station 或 Depot）。
   *
   * <p>用于信号前瞻计算：提前感知需要减速的目标节点。
   */
  private boolean isApproachingNode(NodeId nodeId) {
    return isStationNode(nodeId) || isDepotNode(nodeId);
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

  private int resolvePriority(TrainProperties properties, RouteDefinition route) {
    Optional<Integer> tagPriority = TrainTagHelper.readIntTag(properties, "FTA_PRIORITY");
    if (tagPriority.isPresent()) {
      return tagPriority.get();
    }
    Optional<String> operatorOpt = resolveOperatorCode(properties, route);
    if (operatorOpt.isEmpty()) {
      return 0;
    }
    String operatorCode = operatorOpt.get();
    String key = operatorCode.trim().toLowerCase(Locale.ROOT);
    Integer cached = operatorPriorityCache.get(key);
    if (cached != null) {
      return cached;
    }
    Optional<Integer> loaded = loadOperatorPriority(operatorCode);
    if (loaded.isPresent()) {
      operatorPriorityCache.putIfAbsent(key, loaded.get());
      return loaded.get();
    }
    return 0;
  }

  private Optional<String> resolveOperatorCode(TrainProperties properties, RouteDefinition route) {
    Optional<String> tagOpt =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_OPERATOR_CODE);
    if (tagOpt.isPresent()) {
      return tagOpt;
    }
    if (route == null || route.id() == null || route.id().value() == null) {
      return Optional.empty();
    }
    String raw = route.id().value();
    String[] parts = raw.split(":");
    if (parts.length < 3) {
      return Optional.empty();
    }
    String op = parts[0].trim();
    return op.isEmpty() ? Optional.empty() : Optional.of(op);
  }

  private Optional<Integer> loadOperatorPriority(String operatorCode) {
    if (operatorCode == null || operatorCode.isBlank()) {
      return Optional.empty();
    }
    StorageManager storage = storageManager;
    if (storage == null || !storage.isReady()) {
      return Optional.empty();
    }
    Optional<StorageProvider> providerOpt = storage.provider();
    if (providerOpt.isEmpty()) {
      return Optional.empty();
    }
    StorageProvider provider = providerOpt.get();
    for (Company company : provider.companies().listAll()) {
      if (company == null) {
        continue;
      }
      Optional<Operator> operatorOpt =
          provider.operators().findByCompanyAndCode(company.id(), operatorCode);
      if (operatorOpt.isPresent()) {
        return Optional.of(operatorOpt.get().priority());
      }
    }
    return Optional.empty();
  }

  /**
   * 解析从 from 到 to 的边限速。
   *
   * <p>优先查找直接相邻边；若不存在则尝试最短路径，取路径上所有边限速的最小值。
   */
  private double resolveEdgeSpeedLimit(
      RuntimeTrainHandle train,
      RailGraph graph,
      NodeId from,
      NodeId to,
      ConfigManager.ConfigView config) {
    if (train == null || graph == null || from == null || to == null || config == null) {
      return -1.0;
    }
    double defaultSpeed = config.graphSettings().defaultSpeedBlocksPerSecond();
    UUID worldId = train.worldId();
    Instant now = Instant.now();

    // 1. 尝试直接相邻边
    Optional<RailEdge> directEdgeOpt = findEdge(graph, from, to);
    if (directEdgeOpt.isPresent()) {
      return railGraphService.effectiveSpeedLimitBlocksPerSecond(
          worldId, directEdgeOpt.get(), now, defaultSpeed);
    }

    // 2. 回退：通过最短路径查找，取路径上所有边的最小限速
    Optional<RailGraphPath> pathOpt =
        pathFinder.shortestPath(graph, from, to, RailGraphPathFinder.Options.shortestDistance());
    if (pathOpt.isEmpty() || pathOpt.get().edges().isEmpty()) {
      return -1.0;
    }
    double minSpeed = Double.MAX_VALUE;
    for (RailEdge edge : pathOpt.get().edges()) {
      double edgeSpeed =
          railGraphService.effectiveSpeedLimitBlocksPerSecond(worldId, edge, now, defaultSpeed);
      if (edgeSpeed > 0.0 && edgeSpeed < minSpeed) {
        minSpeed = edgeSpeed;
      }
    }
    return minSpeed == Double.MAX_VALUE ? defaultSpeed : minSpeed;
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
    return resolveGraph(event.getWorld().getUID(), Instant.now());
  }

  private Optional<RailGraph> resolveGraph(UUID worldId, Instant now) {
    if (worldId == null) {
      return Optional.empty();
    }
    Instant snapshotTime = now != null ? now : Instant.now();
    return railGraphService
        .getSnapshot(worldId)
        .map(
            snapshot -> {
              RailGraph graph = snapshot.graph();
              java.util.Map<
                      org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId, RailEdgeOverrideRecord>
                  overrides = railGraphService.edgeOverrides(worldId);
              if (overrides.isEmpty()) {
                return graph;
              }
              return new EdgeOverrideRailGraph(graph, overrides, snapshotTime);
            });
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
      RailGraph graph, RouteDefinition route, int currentIndex, int lookaheadEdges) {
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

  /** 在推进进度后使 ETA 缓存失效，确保下次查询会重新计算。 */
  private void invalidateTrainEta(String trainName) {
    EtaService svc = this.etaService;
    if (svc != null && trainName != null && !trainName.isBlank()) {
      svc.invalidateTrainEta(trainName);
    }
  }
}
