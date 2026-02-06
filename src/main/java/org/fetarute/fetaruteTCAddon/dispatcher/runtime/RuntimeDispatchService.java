package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.bukkit.plugin.java.JavaPlugin;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
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
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
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
  private final java.util.concurrent.ConcurrentMap<String, WaypointStopState> waypointStopStates =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.ConcurrentMap<String, String> stopWaypointLogState =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.ConcurrentMap<String, ProgressTriggerState>
      progressTriggerState = new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.atomic.AtomicLong waypointStopCounter =
      new java.util.concurrent.atomic.AtomicLong();
  private volatile EtaService etaService;

  /** 路线列车位置追踪器：用于快速查询前方列车，支持跟车信号计算。 */
  private final RouteTrainTracker routeTrainTracker = new RouteTrainTracker();

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

  /**
   * 运行时有效节点覆盖（按列车名 + route index）。
   *
   * <p>用于支持动态站台/同站不同站台容错：当列车实际到达的 NodeId 与线路定义不一致时，将“该索引的真实 NodeId”写入覆盖表， 后续信号 tick /
   * 占用请求构建将优先使用覆盖值。
   */
  private final java.util.concurrent.ConcurrentMap<
          String, java.util.concurrent.ConcurrentMap<Integer, NodeId>>
      effectiveNodeOverrides = new java.util.concurrent.ConcurrentHashMap<>();

  private final ControlDiagnosticsCache diagnosticsCache = new ControlDiagnosticsCache();
  private static final Pattern ACTION_PREFIX_PATTERN =
      Pattern.compile("^(CHANGE|DYNAMIC|ACTION|CRET|DSTY)\\b", Pattern.CASE_INSENSITIVE);

  /** 节点历史缓存：记录列车最近经过的节点（用于回退检测）。 */
  private final java.util.concurrent.ConcurrentMap<String, NodeHistory> nodeHistoryCache =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** 节点历史容量上限。 */
  private static final int NODE_HISTORY_CAPACITY = 10;

  /** 回退检测冷却时间（毫秒），避免重复触发 relaunch。 */
  private static final long RELAUNCH_COOLDOWN_MS = 5000L;

  /** 动态站台分配器。 */
  private final DynamicPlatformAllocator dynamicAllocator;

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
    this.dynamicAllocator =
        new DynamicPlatformAllocator(routeDefinitions, occupancyManager, this.debugLogger);
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
    int currentIndex =
        RouteIndexResolver.resolveCurrentIndexWithDynamic(
            route, routeDefinitions, tagIndex, definition.nodeId());
    if (currentIndex < 0) {
      return true;
    }
    recordEffectiveNode(trainName, route, currentIndex, definition.nodeId());
    pruneEffectiveNodeOverrides(trainName, currentIndex);
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
    int rearGuardEdges = runtimeSettings.rearGuardEdges();
    int priority = resolvePriority(properties, route);

    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(
            graph,
            lookaheadEdges,
            minClearEdges,
            rearGuardEdges,
            runtimeSettings.switcherZoneEdges(),
            debugLogger);
    List<NodeId> effectiveNodes = resolveEffectiveWaypoints(trainName, route);

    Optional<OccupancyRequestContext> contextOpt =
        builder.buildContextFromNodes(
            trainName,
            Optional.ofNullable(route.id()),
            effectiveNodes,
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
    logDeadlockReleaseIfNeeded(trainName, decision, "departure");
    if (!decision.allowed()) {
      debugLogger.accept("发车门控阻塞: train=" + trainName + " aspect=" + decision.signal());
      return false;
    }
    return true;
  }

  /**
   * AutoStation 停车时推进 routeIndex 并设置下一站 destination。
   *
   * <p>此方法应在列车于 AutoStation 停稳后（进入 dwell 前）调用，用于：
   *
   * <ul>
   *   <li>推进 routeIndex 到当前站点
   *   <li>解析 Dynamic 站台选择（如果下一站是 DYNAMIC）
   *   <li>设置下一站 destination
   *   <li>处理 CHANGE/DSTY 等特殊动作
   * </ul>
   *
   * @param group TrainCarts 列车组
   * @param definition 当前站点的节点定义
   */
  public void handleStationArrival(
      com.bergerkiller.bukkit.tc.controller.MinecartGroup group, SignNodeDefinition definition) {
    if (group == null || definition == null) {
      return;
    }
    RuntimeTrainHandle train = new TrainCartsRuntimeHandle(group);
    TrainProperties properties = train.properties();
    String trainName = properties != null ? properties.getTrainName() : "unknown";

    // 非 FTA 管控列车：静默跳过
    if (!isFtaManagedTrain(properties)) {
      return;
    }

    handleRenameIfNeeded(properties, trainName);
    Optional<UUID> routeUuidOpt = readRouteUuid(properties);
    Optional<RouteDefinition> routeOpt = resolveRouteDefinition(properties);
    if (routeOpt.isEmpty()) {
      debugLogger.accept(
          "Station 推进失败: 未找到线路定义 train="
              + trainName
              + " "
              + describeRouteTags(properties, routeUuidOpt));
      return;
    }
    RouteDefinition route = routeOpt.get();
    OptionalInt tagIndex =
        TrainTagHelper.readIntTag(properties, RouteProgressRegistry.TAG_ROUTE_INDEX)
            .map(OptionalInt::of)
            .orElse(OptionalInt.empty());
    int currentIndex =
        RouteIndexResolver.resolveCurrentIndexWithDynamic(
            route, routeDefinitions, tagIndex, definition.nodeId());
    if (currentIndex < 0) {
      if (matchesDstyTarget(route, definition.nodeId())) {
        handleDestroy(train, properties, trainName, "DSTY");
        return;
      }
      debugLogger.accept(
          "Station 推进跳过: 当前节点不在线路定义内 train="
              + trainName
              + " node="
              + definition.nodeId().value()
              + " route="
              + route.id().value());
      return;
    }
    NodeId currentNode = definition.nodeId();
    recordEffectiveNode(trainName, route, currentIndex, currentNode);
    pruneEffectiveNodeOverrides(trainName, currentIndex);
    Instant now = Instant.now();
    // 处理 DSTY 销毁
    Optional<RouteStop> stopOpt = routeDefinitions.findStop(route.id(), currentIndex);
    if (stopOpt.isPresent()) {
      RouteStop stop = stopOpt.get();
      if (shouldDestroyAt(stop, currentNode)) {
        handleDestroy(train, properties, trainName, "DSTY");
        return;
      }
      // 处理 CHANGE 移交指令
      handleChangeAction(trainName, properties, stop);
    }
    // 推进 routeIndex
    progressRegistry.advance(
        trainName, routeUuidOpt.orElse(null), route, currentIndex, properties, now);
    invalidateTrainEta(trainName);
    debugLogger.accept(
        "Station 推进: train="
            + trainName
            + " idx="
            + currentIndex
            + " node="
            + currentNode.value()
            + " route="
            + route.id().value());
    // 计算下一站并设置 destination
    int nextIndex = currentIndex + 1;
    if (nextIndex >= route.waypoints().size()) {
      // 检查是否有 DSTY DYNAMIC depot 需要前往
      Optional<RailGraph> graphOpt = resolveGraph(train.worldId(), now);
      if (graphOpt.isPresent()) {
        boolean allocated =
            tryAllocateDstyDynamicDepot(
                trainName, train, properties, route, currentNode, graphOpt.get());
        if (allocated) {
          debugLogger.accept(
              "Station 推进: 继续前往 DSTY DYNAMIC depot train="
                  + trainName
                  + " node="
                  + currentNode.value()
                  + " route="
                  + route.id().value());
          return;
        }
      }
      // 已到终点，清除 destination
      properties.clearDestinationRoute();
      properties.setDestination("");
      debugLogger.accept(
          "Station 推进: 已到终点 train="
              + trainName
              + " node="
              + currentNode.value()
              + " route="
              + route.id().value());
      return;
    }
    NodeId nextNode = resolveEffectiveNode(trainName, route, nextIndex);
    // 尝试 Dynamic 站台选择
    Optional<RailGraph> graphOpt = resolveGraph(train.worldId(), now);
    if (graphOpt.isPresent()) {
      RailGraph graph = graphOpt.get();
      resolveDynamicStationTargetIfNeeded(trainName, route, nextIndex, currentNode, graph)
          .ifPresent(
              selected -> {
                recordEffectiveNode(trainName, route, nextIndex, selected);
              });
      nextNode = resolveEffectiveNode(trainName, route, nextIndex);
    }
    // 设置下一站 destination
    String destinationName = resolveDestinationName(nextNode);
    if (destinationName != null && !destinationName.isBlank()) {
      properties.clearDestinationRoute();
      properties.setDestination(destinationName);
      debugLogger.accept(
          "Station 设置 destination: train="
              + trainName
              + " dest="
              + destinationName
              + " nextIdx="
              + nextIndex);
    }
  }

  /**
   * 更新列车经过的最后一个图节点（用于 arriving 判定优化）。
   *
   * <p>当列车经过中间 waypoint（不在 route 定义中）时调用，仅更新 lastPassedGraphNode，不推进 routeIndex。
   */
  public void updateLastPassedGraphNode(SignActionEvent event, SignNodeDefinition definition) {
    if (event == null || definition == null || !event.hasGroup()) {
      return;
    }
    com.bergerkiller.bukkit.tc.controller.MinecartGroup group = event.getGroup();
    TrainProperties properties = group.getProperties();
    if (properties == null) {
      return;
    }
    String trainName = properties.getTrainName();
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    NodeId nodeId = definition.nodeId();
    if (nodeId == null) {
      return;
    }
    Instant now = Instant.now();
    progressRegistry.updateLastPassedGraphNode(trainName, nodeId, now);
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

    // 非 FTA 管控列车：静默跳过，不输出日志
    if (!isFtaManagedTrain(properties)) {
      return;
    }

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
    int currentIndex =
        RouteIndexResolver.resolveCurrentIndexWithDynamic(
            route, routeDefinitions, tagIndex, definition.nodeId());
    if (currentIndex < 0) {
      if (matchesDstyTarget(route, definition.nodeId())) {
        handleDestroy(train, properties, trainName, "DSTY");
        return;
      }
      if (definition.nodeType() == NodeType.WAYPOINT) {
        updateLastPassedGraphNode(event, definition);
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

    // 回退检测：检查是否走回头路（异常反弹）
    if (detectAndHandleRegression(event, train, properties, trainName, route, currentNode)) {
      return; // 已触发 relaunch，跳过后续处理
    }

    // 记录节点历史（用于后续回退检测）
    recordNodeHistory(trainName, currentNode);

    recordEffectiveNode(trainName, route, currentIndex, currentNode);
    pruneEffectiveNodeOverrides(trainName, currentIndex);
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
      // 处理 CHANGE 移交指令：仅更新 operator/line 标识，不改变当前 route
      handleChangeAction(trainName, properties, stop);
      if (stop.passType() == org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType.TERMINATE
          && route.lifecycleMode()
              == org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLifecycleMode.REUSE_AT_TERM) {
        int dwellSeconds = resolveWaypointDwellSeconds(stop);
        progressRegistry.advance(
            trainName, routeUuidOpt.orElse(null), route, currentIndex, properties, now);
        invalidateTrainEta(trainName);
        // TERM 到达：只保留当前节点占用，释放窗口外资源，防止后车追尾
        if (occupancyManager != null) {
          RailGraph graph = resolveGraph(event).orElse(null);
          retainStopOccupancy(trainName, route, currentIndex, currentNode, graph, now);
        }
        updateSignalOrWarn(trainName, SignalAspect.STOP, now);
        if (definition.nodeType() == NodeType.WAYPOINT) {
          // Waypoint STOP/TERM 采用“先软刹停稳再居中”，避免触发即硬停。
          scheduleWaypointCenterAfterStop(event, definition.nodeId(), trainName, dwellSeconds);
        }
        // TERM 标记：清除 destination 防止继续寻路
        properties.clearDestinationRoute();
        properties.setDestination("");
        // 传入 dwellSeconds，readyAt = now + dwell
        handleLayoverRegistrationIfNeeded(trainName, route, currentNode, properties, dwellSeconds);
        debugLogger.accept(
            "调度终到: 进入 Layover train="
                + trainName
                + " node="
                + currentNode.value()
                + " idx="
                + currentIndex
                + " route="
                + route.id().value()
                + " readyIn="
                + dwellSeconds
                + "s");
        return;
      }
      if (shouldStopAtWaypoint(definition, stop)) {
        waypointDwellSeconds = resolveWaypointDwellSeconds(stop);
        stopAtWaypoint = waypointDwellSeconds > 0;
      }
    }
    int nextIndex = currentIndex + 1;
    if (nextIndex >= route.waypoints().size()) {
      progressRegistry.advance(
          trainName, routeUuidOpt.orElse(null), route, currentIndex, properties, now);
      invalidateTrainEta(trainName);
      // 检查是否有 DSTY DYNAMIC depot 需要前往
      Optional<RailGraph> graphOpt = resolveGraph(event);
      if (graphOpt.isPresent()) {
        boolean allocated =
            tryAllocateDstyDynamicDepot(
                trainName, train, properties, route, definition.nodeId(), graphOpt.get());
        if (allocated) {
          debugLogger.accept(
              "调度推进: 继续前往 DSTY DYNAMIC depot train="
                  + trainName
                  + " node="
                  + definition.nodeId().value()
                  + " route="
                  + route.id().value());
          return;
        }
      }
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
    NodeId nextNode = resolveEffectiveNode(trainName, route, nextIndex);

    Optional<RailGraph> graphOpt = resolveGraph(event);
    RailGraph graph = graphOpt.orElse(null);
    if (stopAtWaypoint) {
      if (graph != null) {
        resolveDynamicStationTargetIfNeeded(trainName, route, nextIndex, currentNode, graph)
            .ifPresent(
                selected -> {
                  recordEffectiveNode(trainName, route, nextIndex, selected);
                });
        nextNode = resolveEffectiveNode(trainName, route, nextIndex);
      }
      if (event.getAction() == SignActionType.MEMBER_ENTER) {
        return;
      }
      // STOP waypoint 到达：只保留当前节点占用，释放窗口外资源，防止后车追尾
      if (occupancyManager != null) {
        retainStopOccupancy(trainName, route, currentIndex, currentNode, graph, now);
      }
      progressRegistry.advance(
          trainName, routeUuidOpt.orElse(null), route, currentIndex, properties, now);
      invalidateTrainEta(trainName);
      updateSignalOrWarn(trainName, SignalAspect.STOP, now);
      String destinationName = resolveDestinationName(nextNode);
      if (destinationName != null && !destinationName.isBlank() && properties != null) {
        properties.clearDestinationRoute();
        properties.setDestination(destinationName);
      }
      // Waypoint STOP 采用“先软刹停稳再居中”，避免触发即硬停。
      scheduleWaypointCenterAfterStop(event, definition.nodeId(), trainName, waypointDwellSeconds);
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
    int rearGuardEdges = runtimeSettings.rearGuardEdges();
    int priority = resolvePriority(properties, route);
    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(
            graph,
            lookaheadEdges,
            minClearEdges,
            rearGuardEdges,
            runtimeSettings.switcherZoneEdges());
    List<NodeId> effectiveNodes = resolveEffectiveWaypoints(trainName, route);
    Optional<DynamicSelection> dynamicSelectionOpt =
        selectDynamicStationTargetForProgress(
            trainName, route, currentIndex, nextIndex, currentNode, graph, builder, now, priority);
    OccupancyRequestContext context;
    OccupancyDecision decision;
    if (dynamicSelectionOpt.isPresent()) {
      DynamicSelection selection = dynamicSelectionOpt.get();
      recordEffectiveNode(trainName, route, nextIndex, selection.targetNode());
      nextNode = selection.targetNode();
      context = selection.context();
      decision = selection.decision();
    } else {
      Optional<OccupancyRequestContext> contextOpt =
          builder.buildContextFromNodes(
              trainName,
              Optional.ofNullable(route.id()),
              effectiveNodes,
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
      context = contextOpt.get();
      OccupancyRequest request = context.request();
      decision = occupancyManager.canEnter(request);
    }
    OccupancyRequest request = context.request();
    releaseResourcesNotInRequest(trainName, request.resourceList());
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
    logDeadlockReleaseIfNeeded(trainName, decision, "progress");
    if (!decision.allowed()) {
      UUID worldId = train.worldId();
      SignalLookahead.EdgeSpeedResolver edgeSpeedResolver = createEdgeSpeedResolver(worldId);
      var lookahead =
          SignalLookahead.computeWithEdgeSpeed(
              decision, context, SignalAspect.STOP, this::isApproachingNode, edgeSpeedResolver);
      OptionalLong lookaheadDistance = lookahead.minConstraintDistance();
      SignalAspect aspect = deriveBlockedAspect(decision, context);
      OptionalLong stopDistance =
          aspect == SignalAspect.STOP
              ? resolveMergedStopDistance(lookaheadDistance, train, properties)
              : lookaheadDistance;
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
      // 阻塞时：已到达当前节点，仍需推进进度并更新 destination，避免后续道岔回弹
      progressRegistry.advance(
          trainName, routeUuidOpt.orElse(null), route, currentIndex, properties, now);
      invalidateTrainEta(trainName);
      String destinationName = resolveDestinationName(nextNode);
      if (destinationName != null && !destinationName.isBlank()) {
        properties.clearDestinationRoute();
        properties.setDestination(destinationName);
      }
      // 仅保留当前节点占用，避免后车追尾
      if (occupancyManager != null && currentNode != null) {
        OccupancyResource keepResource = OccupancyResource.forNode(currentNode);
        releaseResourcesNotInRequest(trainName, List.of(keepResource));
        OccupancyRequest locationRequest =
            new OccupancyRequest(
                trainName,
                Optional.of(route.id()),
                now,
                List.of(keepResource),
                java.util.Map.of(),
                0);
        occupancyManager.acquire(locationRequest);
      }
      updateSignalOrWarn(trainName, aspect, now);
      applyControl(
          train,
          properties,
          aspect,
          route,
          currentNode,
          nextNode,
          graph,
          false,
          stopDistance,
          lookahead,
          java.util.OptionalDouble.empty());
      return;
    }
    occupancyManager.acquire(request);
    progressRegistry.advance(
        trainName, routeUuidOpt.orElse(null), route, currentIndex, properties, now);
    invalidateTrainEta(trainName);
    updateSignalOrWarn(trainName, SignalAspect.PROCEED, now);

    String destinationName = resolveDestinationName(nextNode);
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
        nextNode,
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

    // 清理不活跃列车的动态站台分配
    for (String name : released) {
      dynamicAllocator.clearAllocations(name);
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

  /**
   * 返回进度快照（按列车名）。
   *
   * <p>用于运行时统计（如线路车数限制/Depot 负载均衡）。
   */
  public Map<String, RouteProgressRegistry.RouteProgressEntry> snapshotProgressEntries() {
    return progressRegistry.snapshot();
  }

  /**
   * 返回列车的“有效起点节点”快照。
   *
   * <p>会基于线路定义与动态覆盖表解析 index=0 的有效节点；若找不到定义则跳过。
   */
  public Map<String, NodeId> snapshotEffectiveStartNodes() {
    Map<String, RouteProgressRegistry.RouteProgressEntry> progress = progressRegistry.snapshot();
    if (progress.isEmpty()) {
      return Map.of();
    }
    Map<String, NodeId> out = new HashMap<>();
    for (RouteProgressRegistry.RouteProgressEntry entry : progress.values()) {
      if (entry == null || entry.trainName() == null || entry.trainName().isBlank()) {
        continue;
      }
      UUID routeId = entry.routeUuid();
      if (routeId == null) {
        continue;
      }
      Optional<RouteDefinition> routeOpt = routeDefinitions.findById(routeId);
      if (routeOpt.isEmpty()) {
        continue;
      }
      RouteDefinition route = routeOpt.get();
      if (route.waypoints().isEmpty()) {
        continue;
      }
      NodeId start = resolveEffectiveNode(entry.trainName(), route, 0);
      if (start != null) {
        out.put(entry.trainName(), start);
      }
    }
    return Map.copyOf(out);
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
    waypointStopStates.remove(trainName.toLowerCase(java.util.Locale.ROOT));
    missingSignalWarned.remove(trainName.toLowerCase(java.util.Locale.ROOT));
    stopWaypointLogState.remove(trainName.toLowerCase(java.util.Locale.ROOT));
    progressTriggerState.remove(trainName.toLowerCase(java.util.Locale.ROOT));
    effectiveNodeOverrides.remove(normalizeTrainKey(trainName));
  }

  /**
   * 列车运行时状态快照（用于健康检查）。
   *
   * @param trainName 列车名
   * @param progressIndex 当前进度索引
   * @param signalAspect 当前信号
   * @param speedBlocksPerTick 当前速度（blocks/tick）
   */
  public record TrainRuntimeState(
      String trainName, int progressIndex, SignalAspect signalAspect, double speedBlocksPerTick) {}

  /**
   * 获取列车运行时状态快照。
   *
   * @param trainName 列车名
   * @return 状态快照，若列车不存在或无进度则为空
   */
  public Optional<TrainRuntimeState> getTrainState(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return Optional.empty();
    }
    Optional<RouteProgressRegistry.RouteProgressEntry> entryOpt = progressRegistry.get(trainName);
    if (entryOpt.isEmpty()) {
      return Optional.empty();
    }
    RouteProgressRegistry.RouteProgressEntry entry = entryOpt.get();
    SignalAspect signal = entry.lastSignal();
    int idx = entry.currentIndex();

    // 获取列车当前速度
    double speedBpt = 0.0;
    TrainProperties properties = TrainPropertiesStore.get(trainName);
    if (properties != null) {
      com.bergerkiller.bukkit.tc.controller.MinecartGroup group = properties.getHolder();
      if (group != null && group.isValid()) {
        RuntimeTrainHandle handle = new TrainCartsRuntimeHandle(group);
        speedBpt = handle.currentSpeedBlocksPerTick();
      }
    }

    return Optional.of(new TrainRuntimeState(trainName, idx, signal, speedBpt));
  }

  /**
   * 通过列车名刷新信号。
   *
   * @param trainName 列车名
   */
  public void refreshSignalByName(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    TrainProperties properties = TrainPropertiesStore.get(trainName);
    if (properties == null) {
      return;
    }
    com.bergerkiller.bukkit.tc.controller.MinecartGroup group = properties.getHolder();
    if (group == null || !group.isValid()) {
      return;
    }
    refreshSignal(group);
  }

  /**
   * 强制重发列车（用于健康检查修复）。
   *
   * @param trainName 列车名
   * @return true 表示成功触发重发
   */
  public boolean forceRelaunchByName(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return false;
    }
    TrainProperties properties = TrainPropertiesStore.get(trainName);
    if (properties == null) {
      return false;
    }
    com.bergerkiller.bukkit.tc.controller.MinecartGroup group = properties.getHolder();
    if (group == null || !group.isValid()) {
      return false;
    }

    Optional<RouteProgressRegistry.RouteProgressEntry> entryOpt = progressRegistry.get(trainName);
    if (entryOpt.isEmpty()) {
      return false;
    }
    RouteProgressRegistry.RouteProgressEntry entry = entryOpt.get();
    Optional<RouteDefinition> routeOpt = routeDefinitions.findById(entry.routeUuid());
    if (routeOpt.isEmpty()) {
      return false;
    }
    RouteDefinition route = routeOpt.get();
    int currentIndex = entry.currentIndex();
    if (currentIndex < 0 || currentIndex >= route.waypoints().size()) {
      return false;
    }
    NodeId currentNode = route.waypoints().get(currentIndex);

    // 获取图
    Optional<RailGraph> graphOpt = resolveGraphByGroup(group);
    if (graphOpt.isEmpty()) {
      return false;
    }
    RailGraph graph = graphOpt.get();

    // 计算下一节点和方向
    int nextIndex = currentIndex + 1;
    if (nextIndex >= route.waypoints().size()) {
      return false; // 已到终点
    }
    NodeId nextNode = route.waypoints().get(nextIndex);
    Optional<org.bukkit.block.BlockFace> direction =
        resolveLaunchDirectionByGraph(graph, currentNode, nextNode);
    if (direction.isEmpty()) {
      return false;
    }

    // 设置 destination
    String destinationName = resolveDestinationName(nextNode);
    if (destinationName == null || destinationName.isBlank()) {
      destinationName = nextNode.value();
    }
    properties.clearDestinationRoute();
    properties.setDestination(destinationName);

    // 执行强制重发
    TrainConfig config = trainConfigResolver.resolve(properties, configManager.current());
    double targetBps = configManager.current().graphSettings().defaultSpeedBlocksPerSecond();
    double targetBpt = targetBps / 20.0;
    double accelBpt2 = config.accelBps2() / 400.0;
    properties.setSpeedLimit(targetBpt);

    RuntimeTrainHandle handle = new TrainCartsRuntimeHandle(group);
    handle.forceRelaunch(direction.get(), targetBpt, accelBpt2);

    debugLogger.accept(
        "HealthMonitor forceRelaunch: train="
            + trainName
            + " from="
            + currentNode.value()
            + " to="
            + nextNode.value()
            + " dir="
            + direction.get().name());
    return true;
  }

  private Optional<RailGraph> resolveGraphByGroup(
      com.bergerkiller.bukkit.tc.controller.MinecartGroup group) {
    if (group == null || group.getWorld() == null) {
      return Optional.empty();
    }
    UUID worldId = group.getWorld().getUID();
    return railGraphService.getSnapshot(worldId).map(s -> s.graph());
  }

  /**
   * 事件驱动的信号下发入口：由 SignalEventBus 触发，立即将新信号应用到列车。
   *
   * <p>此方法将事件信号与现有的 handleSignalTick 逻辑桥接，实现即时响应。
   *
   * @param trainName 列车名
   * @param signal 新的信号等级
   */
  public void applySignalFromEvent(String trainName, SignalAspect signal) {
    if (trainName == null || trainName.isBlank() || signal == null) {
      return;
    }
    TrainProperties properties = TrainPropertiesStore.get(trainName);
    if (properties == null || !isFtaManagedTrain(properties)) {
      return;
    }
    com.bergerkiller.bukkit.tc.controller.MinecartGroup group = properties.getHolder();
    if (group == null || !group.isValid()) {
      return;
    }
    RuntimeTrainHandle train = new TrainCartsRuntimeHandle(group);
    debugLogger.accept("事件信号下发: train=" + trainName + " signal=" + signal);
    // 使用 forceApply=true 确保立即生效
    handleSignalTick(train, true);
  }

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
   * <h4>STOP/TERM waypoint handoff（避免卡死/提前刹停）</h4>
   *
   * <p>当“下一站”为 STOP/TERM waypoint 时：
   *
   * <ul>
   *   <li>不在信号 tick 中强制将信号置为 STOP（否则列车静止时会被 STOP 卡死无法发车）。
   *   <li>仅将目标速度上限压到 {@code runtime.approach-speed-bps}（approaching
   *       速度），交由推进点（waypoint/autostation）接管真正停站。
   *   <li>仅当存在前方 blocker（红灯/占用阻塞）时，才使用“到 blocker 的距离”触发进一步减速/停车；不使用到下一节点的距离，避免提前刹停在牌子前。
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
    // 非 FTA 管控列车：静默跳过
    if (!isFtaManagedTrain(properties)) {
      return;
    }
    String trainName = properties.getTrainName();
    Instant now = Instant.now();
    handleRenameIfNeeded(properties, trainName);
    if (layoverRegistry.get(trainName).isPresent()) {
      // Layover 状态：保留当前位置节点的占用，防止后车"反向占用"导致死锁
      // 只释放前方 lookahead 资源，不释放当前节点
      LayoverCandidate candidate = layoverRegistry.get(trainName).orElse(null);
      if (occupancyManager != null && candidate != null) {
        NodeId locationNode = candidate.locationNodeId();
        OccupancyResource keepResource = OccupancyResource.forNode(locationNode);
        // 只保留当前位置节点占用，释放其他所有资源
        releaseResourcesNotInRequest(trainName, List.of(keepResource));
        // 确保当前位置占用存在（可能在之前被错误释放）
        OccupancyRequest locationRequest =
            new OccupancyRequest(
                trainName, Optional.empty(), now, List.of(keepResource), java.util.Map.of(), 0);
        occupancyManager.acquire(locationRequest);
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

    int boundedIndex =
        currentIndex < route.waypoints().size()
            ? currentIndex
            : Math.max(0, route.waypoints().size() - 1);

    // 首站位置初始化：从 FTA_DEPOT_ID tag 读取实际 spawn 位置（DYNAMIC depot 支持）
    // 注：只在首次处理时执行，避免每 tick 重复打印
    if (boundedIndex == 0 && readEffectiveNode(trainName, 0).isEmpty()) {
      Optional<String> depotIdOpt = TrainTagHelper.readTagValue(properties, "FTA_DEPOT_ID");
      if (depotIdOpt.isPresent() && !depotIdOpt.get().isBlank()) {
        NodeId actualStartNode = NodeId.of(depotIdOpt.get());
        NodeId declared = route.waypoints().get(0);
        // 只有当实际起点与声明不同时才打印日志
        if (!actualStartNode.equals(declared)) {
          debugLogger.accept("首站位置初始化: train=" + trainName + " depotId=" + actualStartNode.value());
        }
        // 无论是否与声明相同，都强制写入覆盖表以防止重复检查
        forceRecordEffectiveNode(trainName, 0, actualStartNode);
      }
    }

    NodeId currentNode = resolveEffectiveNode(trainName, route, boundedIndex);

    // 动态站台分配：检查前方是否有 DYNAMIC 站点，提前分配具体站台
    tryDynamicPlatformAllocation(train, properties, trainName, route, currentIndex, currentNode);

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
                ? Optional.of(resolveEffectiveNode(trainName, route, currentIndex + 1))
                : Optional.empty();
        if (nextNode.isPresent()) {
          if (occupancyManager != null) {
            if (currentNode != null) {
              RailGraph graph = resolveGraph(train.worldId(), now).orElse(null);
              retainStopOccupancy(trainName, route, currentIndex, currentNode, graph, now);
            } else {
              occupancyManager.releaseByTrain(trainName);
            }
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

    Optional<WaypointStopState> waypointStopState =
        resolveWaypointStopState(trainName, currentNode);
    if (waypointStopState.isPresent()) {
      Optional<NodeId> nextNode =
          currentIndex + 1 < route.waypoints().size()
              ? Optional.of(resolveEffectiveNode(trainName, route, currentIndex + 1))
              : Optional.empty();
      updateSignalOrWarn(trainName, SignalAspect.STOP, now);
      if (nextNode.isPresent()) {
        if (occupancyManager != null && currentNode != null) {
          RailGraph graph = resolveGraph(train.worldId(), now).orElse(null);
          retainStopOccupancy(trainName, route, currentIndex, currentNode, graph, now);
        }
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
          NodeId terminalNode = resolveEffectiveNode(trainName, route, lastIndex);
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
            runtimeSettings.rearGuardEdges(),
            runtimeSettings.switcherZoneEdges());
    NodeId currentNodeForSignal =
        resolveEffectiveCurrentNodeForSignal(trainName, route, currentIndex, graph);
    List<NodeId> effectiveNodes =
        applyCurrentNodeOverride(
            resolveEffectiveWaypoints(trainName, route), currentIndex, currentNodeForSignal);
    Optional<OccupancyRequestContext> contextOpt =
        builder.buildContextFromNodes(
            trainName, Optional.ofNullable(route.id()), effectiveNodes, currentIndex, now, 0);
    if (contextOpt.isEmpty()) {
      return;
    }
    OccupancyRequestContext context = contextOpt.get();
    OccupancyRequest request = context.request();
    releaseResourcesNotInRequest(trainName, request.resourceList());
    OccupancyDecision decision = occupancyManager.canEnter(request);
    logDeadlockReleaseIfNeeded(trainName, decision, "signal");
    // 冲突区放行锁生效时允许短暂跳过队头/前车扫描，避免信号乒乓
    boolean deadlockRelease = decision.allowed() && !decision.blockers().isEmpty();
    SignalAspect baseAspect =
        decision.allowed() ? decision.signal() : deriveBlockedAspect(decision, context);
    // 前方列车信号调整：扫描更远范围（4 edges）检测其他列车并降级信号
    SignalAspect nextAspect =
        deadlockRelease
            ? baseAspect
            : adjustSignalForForwardTrains(
                trainName, route, effectiveNodes, currentIndex, graph, baseAspect);
    SignalAspect lastAspect = progressEntry.lastSignal();
    Optional<NodeId> nextNode =
        currentIndex + 1 < route.waypoints().size()
            ? Optional.of(resolveEffectiveNode(trainName, route, currentIndex + 1))
            : Optional.empty();
    Optional<NodeId> currentNodeOpt =
        currentIndex < route.waypoints().size()
            ? Optional.ofNullable(currentNodeForSignal)
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
    OptionalLong blockerDistanceOpt = OptionalLong.empty();
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
    if (runtimeSettings.speedCurveEnabled() && !deadlockRelease) {
      UUID worldIdForLookahead = train.worldId();
      SignalLookahead.EdgeSpeedResolver edgeSpeedResolver =
          createEdgeSpeedResolver(worldIdForLookahead);
      lookahead =
          SignalLookahead.computeWithEdgeSpeed(
              decision, context, nextAspect, this::isApproachingNode, edgeSpeedResolver);
      blockerDistanceOpt = lookahead.distanceToBlocker();
      constraintDistanceOpt = lookahead.minConstraintDistance();
    }
    if (runtimeSettings.speedCurveEnabled()) {
      if (stopAtNextWaypoint) {
        // STOP/TERM waypoint：不使用到下一节点距离，避免提前刹停在牌子前。
        // 只允许“前方 blocker”触发进一步减速（例如占用阻塞/红灯），否则保持 approaching 速度交由 AutoStation 接管。
        distanceOpt = blockerDistanceOpt;
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
              + " blockerDistance="
              + formatOptionalLong(blockerDistanceOpt)
              + " constraintDistance="
              + formatOptionalLong(constraintDistanceOpt));
    }
    StallDecision stallDecision = updateStallState(trainName, train, currentIndex, nextAspect);
    if (stallDecision.forceLaunch()) {
      allowLaunch = true;
    }
    OptionalLong effectiveDistanceOpt =
        nextAspect == SignalAspect.STOP
            ? resolveMergedStopDistance(distanceOpt, train, properties)
            : distanceOpt;
    applyControl(
        train,
        properties,
        nextAspect,
        route,
        currentNodeOpt.get(),
        nextNode.get(),
        graph,
        allowLaunch,
        effectiveDistanceOpt,
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
  /**
   * 终点 Layover 注册（无停站时长）：readyAt 立即就绪。
   *
   * @see #handleLayoverRegistrationIfNeeded(String, RouteDefinition, NodeId, TrainProperties, int)
   */
  private void handleLayoverRegistrationIfNeeded(
      String trainName, RouteDefinition route, NodeId location, TrainProperties properties) {
    handleLayoverRegistrationIfNeeded(trainName, route, location, properties, 0);
  }

  /**
   * 终点 Layover 注册（含停站时长）。
   *
   * @param dwellSeconds 停站时长（秒），readyAt = now + dwell
   */
  private void handleLayoverRegistrationIfNeeded(
      String trainName,
      RouteDefinition route,
      NodeId location,
      TrainProperties properties,
      int dwellSeconds) {
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
    // readyAt = 当前时间 + 停站时长
    Instant readyAt = dwellSeconds > 0 ? Instant.now().plusSeconds(dwellSeconds) : Instant.now();
    layoverRegistry.register(trainName, terminalKey, location, readyAt, tags);
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
    // 新任务开始前清理旧的“有效节点覆盖”，避免跨线路遗留导致寻路/占用异常。
    effectiveNodeOverrides.remove(normalizeTrainKey(trainName));

    Optional<RouteDefinition> routeOpt = routeDefinitions.findById(ticket.routeId());
    if (routeOpt.isEmpty()) {
      debugLogger.accept("Layover 发车失败: Route 未找到 " + ticket.routeId());
      return false;
    }
    RouteDefinition route = routeOpt.get();
    NodeId startNode = candidate.locationNodeId();
    List<RouteStop> stops = routeDefinitions.listStops(route.id());

    // 首站匹配：支持 TerminalKey 匹配和 DYNAMIC 匹配
    NodeId routeFirstNode = route.waypoints().get(0);
    String startTerminalKey = TerminalKeyResolver.toTerminalKey(startNode);
    String routeFirstTerminalKey = TerminalKeyResolver.toTerminalKey(routeFirstNode);

    boolean firstStopMatches = TerminalKeyResolver.matches(startTerminalKey, routeFirstTerminalKey);
    // 若普通匹配失败，尝试 DYNAMIC 匹配（首站可能是 DYNAMIC stop）
    if (!firstStopMatches && !stops.isEmpty()) {
      RouteStop firstStop = stops.get(0);
      if (firstStop != null && DynamicStopMatcher.matchesStop(startNode, firstStop)) {
        firstStopMatches = true;
        debugLogger.accept(
            "Layover 发车: DYNAMIC 首站匹配 train=" + trainName + " location=" + startNode.value());
      }
    }

    if (!firstStopMatches) {
      debugLogger.accept(
          "Layover 发车失败: 位置与首站不匹配 train="
              + trainName
              + " location="
              + startNode.value()
              + " routeFirst="
              + routeFirstNode.value());
      return false;
    }

    // 使用 DYNAMIC 匹配确定 startIndex
    int startIndex =
        RouteIndexResolver.resolveCurrentIndexWithDynamic(
            route, stops, OptionalInt.empty(), startNode);
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
    recordEffectiveNode(trainName, route, startIndex, startNode);
    pruneEffectiveNodeOverrides(trainName, startIndex);

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
            runtime.rearGuardEdges(),
            runtime.switcherZoneEdges(),
            debugLogger);
    List<NodeId> effectiveNodes = resolveEffectiveWaypoints(trainName, route);
    int nextIndex = startIndex + 1;
    Optional<DynamicSelection> dynamicSelectionOpt =
        selectDynamicStationTargetForProgress(
            trainName,
            route,
            startIndex,
            nextIndex,
            startNode,
            graph,
            builder,
            now,
            ticket.priority());
    OccupancyRequestContext ctx;
    OccupancyDecision decision;
    if (dynamicSelectionOpt.isPresent()) {
      DynamicSelection selection = dynamicSelectionOpt.get();
      recordEffectiveNode(trainName, route, nextIndex, selection.targetNode());
      ctx = selection.context();
      decision = selection.decision();
    } else {
      Optional<OccupancyRequestContext> ctxOpt =
          builder.buildContextFromNodes(
              trainName,
              Optional.ofNullable(route.id()),
              effectiveNodes,
              startIndex,
              now,
              ticket.priority());
      if (ctxOpt.isEmpty()) {
        debugLogger.accept("Layover 发车失败: 无法构建占用请求 train=" + trainName);
        return false;
      }
      ctx = ctxOpt.get();
      decision = occupancyManager.canEnter(ctx.request());
    }
    OccupancyRequest request = ctx.request();
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
    // 更新终点站 tags（从 End of Operation 解析）
    Optional<DestinationDisplayInfo> destInfoOpt = resolveEndOfOperationInfo(route);
    destInfoOpt.ifPresent(
        dest -> {
          TrainTagHelper.writeTag(properties, "FTA_DEST_NAME", dest.name());
          TrainTagHelper.writeTag(properties, "FTA_DEST_CODE", dest.code());
        });
    // 重新生成 trainName（使用新的 destination）
    String newTrainName =
        regenerateTrainName(route, destInfoOpt.map(DestinationDisplayInfo::name).orElse(null));
    if (newTrainName != null && !newTrainName.equals(trainName)) {
      // 迁移注册：先取消旧 trainName 的注册，再用新 trainName 重新注册
      layoverRegistry.unregister(trainName); // 已在前面 unregister 过，这里是确保
      occupancyManager.releaseByTrain(trainName);
      progressRegistry.remove(trainName);
      properties.setTrainName(newTrainName);
      trainName = newTrainName;
      debugLogger.accept("Layover 复用: trainName 更新为 " + newTrainName);
    }
    TrainTagHelper.writeTag(properties, "FTA_TICKET_ID", ticket.ticketId());
    progressRegistry.advance(trainName, ticket.routeId(), route, startIndex, properties, now);
    invalidateTrainEta(trainName);

    NodeId nextNode = resolveEffectiveNode(trainName, route, startIndex + 1);
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
        resolveLaunchDirectionByGraph(
            graph, resolveEffectiveNode(trainName, route, startIndex), nextNode);
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
   *   <li>STOP：下一段边或下一节点遇到阻塞。
   *   <li>CAUTION：前两段边内存在阻塞。
   *   <li>PROCEED_WITH_CAUTION：前三段边内存在阻塞。
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
    if (bestPosition <= 1) {
      return SignalAspect.STOP;
    }
    if (bestPosition <= 2) {
      return SignalAspect.CAUTION;
    }
    return SignalAspect.PROCEED_WITH_CAUTION;
  }

  /**
   * 记录冲突区放行日志：allowed=true 且 blockers 非空时触发。
   *
   * <p>用于排查“互相占用节点导致的冲突区死锁”是否被放行。
   */
  private void logDeadlockReleaseIfNeeded(
      String trainName, OccupancyDecision decision, String scope) {
    if (decision == null || !decision.allowed() || decision.blockers().isEmpty()) {
      return;
    }
    debugLogger.accept(
        "冲突区放行: train="
            + trainName
            + " scope="
            + scope
            + " blockers="
            + summarizeBlockers(decision));
  }

  /**
   * 前方列车信号调整：扫描更远的前方路径，检测其他列车占用并调整信号。
   *
   * <p>信号规则（按 edge 数量）：
   *
   * <ul>
   *   <li>1 edge 内有车 → STOP
   *   <li>2 edges 内有车 → CAUTION
   *   <li>3 edges 内有车 → PROCEED_WITH_CAUTION
   *   <li>超过扫描范围或无车 → 保持原信号
   * </ul>
   *
   * @param trainName 当前列车名（用于排除自身占用）
   * @param route 线路定义
   * @param currentIndex 当前站点索引
   * @param graph 调度图
   * @param baseAspect 基础信号（由 canEnter 决定）
   * @return 调整后的信号
   */
  private SignalAspect adjustSignalForForwardTrains(
      String trainName,
      RouteDefinition route,
      List<NodeId> effectiveNodes,
      int currentIndex,
      RailGraph graph,
      SignalAspect baseAspect) {
    if (trainName == null
        || trainName.isBlank()
        || route == null
        || graph == null
        || occupancyManager == null) {
      return baseAspect;
    }
    // 扫描前方最多 3 段边（覆盖 STOP/CAUTION/PWC 范围）
    int scanEdges = 3;
    List<NodeId> waypoints = effectiveNodes;
    if (waypoints == null || waypoints.isEmpty()) {
      return baseAspect;
    }
    int maxIndex = Math.min(waypoints.size() - 1, currentIndex + scanEdges);
    if (maxIndex <= currentIndex) {
      return baseAspect;
    }

    // 沿路径扫描每个节点和边，检测其他列车占用
    int forwardEdgeCount = 0;
    for (int i = currentIndex; i < maxIndex; i++) {
      NodeId fromNode = resolveEffectiveNode(trainName, route, i);
      NodeId toNode = resolveEffectiveNode(trainName, route, i + 1);
      if (fromNode == null || toNode == null) {
        continue;
      }
      forwardEdgeCount++;

      // 检查目标节点是否被其他列车占用
      Optional<OccupancyClaim> nodeClaim =
          occupancyManager.getClaim(OccupancyResource.forNode(toNode));
      if (nodeClaim.isPresent()
          && !shouldIgnoreForwardScanClaim(trainName, route, currentIndex, nodeClaim.get())) {
        return edgeCountToSignal(forwardEdgeCount);
      }

      // 检查边是否被其他列车占用（通过 edgesFrom 查找）
      RailEdge edge = findEdgeBetween(graph, fromNode, toNode);
      if (edge != null) {
        Optional<OccupancyClaim> edgeClaim =
            occupancyManager.getClaim(OccupancyResource.forEdge(edge.id()));
        if (edgeClaim.isPresent()
            && !shouldIgnoreForwardScanClaim(trainName, route, currentIndex, edgeClaim.get())) {
          return edgeCountToSignal(forwardEdgeCount);
        }
      }
    }

    return baseAspect;
  }

  /**
   * 判定前方扫描是否应忽略某个占用记录。
   *
   * <p>忽略条件：
   *
   * <ul>
   *   <li>同一列车的占用（自占用）
   *   <li>同线路且进度索引落后当前列车（后车 lookahead）
   * </ul>
   */
  private boolean shouldIgnoreForwardScanClaim(
      String currentTrainName, RouteDefinition route, int currentIndex, OccupancyClaim claim) {
    if (claim == null || route == null) {
      return false;
    }
    if (currentTrainName != null && claim.trainName().equalsIgnoreCase(currentTrainName)) {
      return true;
    }
    Optional<RouteId> claimRouteIdOpt = claim.routeId();
    if (claimRouteIdOpt.isEmpty()) {
      return false;
    }
    RouteId claimRouteId = claimRouteIdOpt.get();
    if (!claimRouteId.equals(route.id())) {
      return false;
    }
    Optional<RouteProgressRegistry.RouteProgressEntry> entryOpt =
        progressRegistry.get(claim.trainName());
    if (entryOpt.isEmpty()) {
      return false;
    }
    RouteProgressRegistry.RouteProgressEntry entry = entryOpt.get();
    if (!route.id().equals(entry.routeId())) {
      return false;
    }
    return entry.currentIndex() < currentIndex;
  }

  /**
   * 查找两节点之间的边（如果存在，无向边匹配）。
   *
   * @return 边对象，若不存在则返回 null
   */
  private RailEdge findEdgeBetween(RailGraph graph, NodeId from, NodeId to) {
    for (RailEdge edge : graph.edgesFrom(from)) {
      // 无向边匹配
      boolean connects =
          (edge.from().equals(from) && edge.to().equals(to))
              || (edge.from().equals(to) && edge.to().equals(from));
      if (connects) {
        return edge;
      }
    }
    return null;
  }

  /**
   * 根据到前车的 edge 数量返回信号等级。
   *
   * <ul>
   *   <li>1 edge → STOP
   *   <li>2 edges → CAUTION
   *   <li>3+ edges → PROCEED_WITH_CAUTION
   * </ul>
   */
  private SignalAspect edgeCountToSignal(int edgeCount) {
    if (edgeCount <= 1) {
      return SignalAspect.STOP;
    } else if (edgeCount <= 2) {
      return SignalAspect.CAUTION;
    } else {
      return SignalAspect.PROCEED_WITH_CAUTION;
    }
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
    // 边限速前瞻：根据前方边的限速约束提前减速
    if (lookahead != null
        && !lookahead.edgeSpeedConstraints().isEmpty()
        && configManager.current().runtimeSettings().speedCurveEnabled()) {
      targetBps =
          applyEdgeSpeedLookahead(targetBps, config.decelBps2(), lookahead.edgeSpeedConstraints());
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
   * Waypoint STOP/TERM 停稳后居中。
   *
   * <p>在 GROUP_ENTER 时只写入 stopState，随后等待列车停稳再执行 launchReset + centerTrain， 以获得与 AutoStation
   * 类似的“先刹车后居中”观感，避免触发即硬停。
   */
  private void scheduleWaypointCenterAfterStop(
      SignActionEvent event, NodeId nodeId, String trainName, int dwellSeconds) {
    if (event == null || nodeId == null || trainName == null || trainName.isBlank()) {
      return;
    }
    if (event.getAction() != SignActionType.GROUP_ENTER) {
      return;
    }
    if (!event.hasGroup() || event.getGroup() == null) {
      return;
    }
    com.bergerkiller.bukkit.tc.controller.MinecartGroup group = event.getGroup();
    if (!group.isValid()) {
      return;
    }

    // 先设置 waypointStopState，防止 handleSignalTick 在列车停稳前走到 stall failover 等逻辑
    String key = trainName.toLowerCase(java.util.Locale.ROOT);
    String sessionId = Long.toString(waypointStopCounter.incrementAndGet());
    WaypointStopState stopState =
        new WaypointStopState(sessionId, nodeId, Instant.now(), dwellSeconds);
    waypointStopStates.put(key, stopState);

    JavaPlugin plugin = resolveSchedulerPlugin();
    if (plugin == null || !plugin.isEnabled()) {
      // 兜底：无法调度任务时仍尝试居中，避免停稳后无反馈
      performWaypointCenter(
          event, group, trainName, nodeId, dwellSeconds, key, sessionId, "scheduler_missing");
      return;
    }

    new org.bukkit.scheduler.BukkitRunnable() {
      private int waitedTicks = 0;
      private int stoppedTicks = 0;
      private static final int STABLE_TICKS = 2;
      private static final int MAX_WAIT_TICKS = 100; // 5 秒超时

      @Override
      public void run() {
        WaypointStopState current = waypointStopStates.get(key);
        if (current == null || !sessionId.equals(current.sessionId())) {
          cancel();
          return;
        }
        if (!group.isValid()) {
          cancel();
          clearWaypointStopState(key, sessionId);
          return;
        }
        waitedTicks++;
        if (!group.isMoving()) {
          stoppedTicks++;
          if (stoppedTicks >= STABLE_TICKS) {
            cancel();
            performWaypointCenter(
                event, group, trainName, nodeId, dwellSeconds, key, sessionId, "stopped");
            return;
          }
        } else {
          stoppedTicks = 0;
        }
        if (waitedTicks >= MAX_WAIT_TICKS) {
          cancel();
          performWaypointCenter(
              event, group, trainName, nodeId, dwellSeconds, key, sessionId, "timeout");
        }
      }
    }.runTaskTimer(plugin, 1L, 1L);
  }

  private void performWaypointCenter(
      SignActionEvent event,
      com.bergerkiller.bukkit.tc.controller.MinecartGroup group,
      String trainName,
      NodeId nodeId,
      int dwellSeconds,
      String key,
      String sessionId,
      String reason) {
    try {
      com.bergerkiller.bukkit.tc.Station station = new com.bergerkiller.bukkit.tc.Station(event);
      group.getActions().launchReset();
      station.centerTrain();

      debugLogger.accept(
          "Waypoint 居中: train="
              + trainName
              + " node="
              + nodeId.value()
              + " dwell="
              + dwellSeconds
              + "s reason="
              + reason);

      if (dwellSeconds > 0 && dwellRegistry != null) {
        scheduleWaypointDwellAfterCenter(group, trainName, dwellSeconds, key, sessionId);
      } else {
        group.getActions().addActionWaitState();
        clearWaypointStopState(key, sessionId);
      }
    } catch (Throwable ex) {
      clearWaypointStopState(key, sessionId);
      debugLogger.accept(
          "Waypoint 居中失败: train="
              + trainName
              + " node="
              + nodeId.value()
              + " error="
              + ex.getClass().getSimpleName());
    }
  }

  /**
   * Waypoint 居中后调度 dwell：等待列车停稳后启动 dwell 计时。
   *
   * <p>与 AutoStation 类似，在列车停稳后（centerTrain 动作完成后）启动 dwell，并添加 WaitState 等待发车信号。 当 dwell 启动后，清理
   * waypointStopState，由 dwellRegistry 接管控制。
   */
  private void scheduleWaypointDwellAfterCenter(
      com.bergerkiller.bukkit.tc.controller.MinecartGroup group,
      String trainName,
      int dwellSeconds,
      String waypointKey,
      String sessionId) {
    JavaPlugin plugin = resolveSchedulerPlugin();
    if (plugin == null || !plugin.isEnabled()) {
      // 兜底：直接启动 dwell
      if (dwellRegistry != null && dwellRegistry.remainingSeconds(trainName).isEmpty()) {
        dwellRegistry.start(trainName, dwellSeconds);
      }
      group.getActions().addActionWaitState();
      clearWaypointStopState(waypointKey, sessionId);
      return;
    }

    // 等待居中动作完成后启动 dwell
    new org.bukkit.scheduler.BukkitRunnable() {
      private int waitedTicks = 0;
      private int stoppedTicks = 0;
      private static final int STABLE_TICKS = 2;
      private static final int MAX_WAIT_TICKS = 100; // 5 秒超时

      @Override
      public void run() {
        if (!group.isValid()) {
          cancel();
          clearWaypointStopState(waypointKey, sessionId);
          return;
        }
        waitedTicks++;
        if (!group.isMoving()) {
          stoppedTicks++;
          if (stoppedTicks >= STABLE_TICKS) {
            cancel();
            if (dwellRegistry != null && dwellRegistry.remainingSeconds(trainName).isEmpty()) {
              dwellRegistry.start(trainName, dwellSeconds);
            }
            group.getActions().addActionWaitState();
            // dwell 启动后，清理 waypointStopState，由 dwellRegistry 接管
            clearWaypointStopState(waypointKey, sessionId);
            return;
          }
        } else {
          stoppedTicks = 0;
        }
        if (waitedTicks >= MAX_WAIT_TICKS) {
          cancel();
          // 超时兜底
          if (dwellRegistry != null && dwellRegistry.remainingSeconds(trainName).isEmpty()) {
            dwellRegistry.start(trainName, dwellSeconds);
          }
          group.getActions().addActionWaitState();
          clearWaypointStopState(waypointKey, sessionId);
        }
      }
    }.runTaskTimer(plugin, 1L, 1L);
  }

  private Optional<WaypointStopState> resolveWaypointStopState(String trainName, NodeId nodeId) {
    if (trainName == null || trainName.isBlank() || nodeId == null) {
      return Optional.empty();
    }
    String key = trainName.toLowerCase(java.util.Locale.ROOT);
    WaypointStopState current = waypointStopStates.get(key);
    if (current == null) {
      return Optional.empty();
    }
    if (!nodeId.equals(current.nodeId())) {
      waypointStopStates.remove(key, current);
      return Optional.empty();
    }
    return Optional.of(current);
  }

  /**
   * 清理 waypoint 停站状态：仅当 sessionId 匹配时移除，避免并发覆盖。
   *
   * @param key 列车名（小写）
   * @param sessionId 会话 ID
   */
  private void clearWaypointStopState(String key, String sessionId) {
    if (key == null || sessionId == null) {
      return;
    }
    WaypointStopState state = waypointStopStates.get(key);
    if (state != null && sessionId.equals(state.sessionId())) {
      waypointStopStates.remove(key, state);
    }
  }

  private JavaPlugin resolveSchedulerPlugin() {
    try {
      return JavaPlugin.getProvidingPlugin(RuntimeDispatchService.class);
    } catch (Throwable ignored) {
      return null;
    }
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

  /**
   * 计算 STOP 信号的制动距离。
   *
   * <p>阻塞点距离是“从当前节点起算”的静态值，无法随列车前进而缩短。为避免速度被卡在非零值，STOP 优先使用基于当前速度的软刹车距离；仅当阻塞距离为 0（紧贴阻塞点）时才直接返回 0。
   */
  private OptionalLong resolveMergedStopDistance(
      OptionalLong distanceOpt, RuntimeTrainHandle train, TrainProperties properties) {
    if (distanceOpt != null && distanceOpt.isPresent() && distanceOpt.getAsLong() <= 0L) {
      return distanceOpt;
    }
    OptionalLong softStop = resolveSoftStopDistance(train, properties);
    if (softStop.isPresent()) {
      return softStop;
    }
    return distanceOpt != null ? distanceOpt : OptionalLong.empty();
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
    waypointStopStates.remove(trainName.toLowerCase(java.util.Locale.ROOT));
    stopWaypointLogState.remove(trainName.toLowerCase(java.util.Locale.ROOT));
    clearNodeHistory(trainName);
    dynamicAllocator.clearAllocations(trainName);
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
    // 排除正在停站（dwell）的列车：停站期间低速是正常的
    if (dwellRegistry.remainingSeconds(trainName).isPresent()) {
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
    // 精确匹配
    if (currentNode.value().equalsIgnoreCase(target)) {
      return true;
    }
    // 尝试 DYNAMIC 匹配（支持 "DSTY DYNAMIC:OP:D:DEPOT" 格式）
    Optional<DynamicStopMatcher.DynamicSpec> specOpt = DynamicStopMatcher.parseDynamicSpec(target);
    if (specOpt.isPresent()) {
      return DynamicStopMatcher.matches(currentNode, specOpt.get());
    }
    return false;
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

  /**
   * 判断列车是否由 FTA 管控。
   *
   * <p>非 FTA 列车（无 route UUID 或 route code tags）应静默跳过，不做任何调度处理。
   *
   * @return true 如果列车有 FTA route tags
   */
  private boolean isFtaManagedTrain(TrainProperties properties) {
    if (properties == null) {
      return false;
    }
    // 检查 route UUID tag
    Optional<String> routeIdTag =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_ROUTE_ID);
    if (routeIdTag.isPresent() && !routeIdTag.get().isBlank()) {
      return true;
    }
    // 检查 route code tags (operator/line/route)
    Optional<String> operatorTag =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_OPERATOR_CODE);
    Optional<String> lineTag =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_LINE_CODE);
    Optional<String> routeTag =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_ROUTE_CODE);
    return (operatorTag.isPresent() && !operatorTag.get().isBlank())
        || (lineTag.isPresent() && !lineTag.get().isBlank())
        || (routeTag.isPresent() && !routeTag.get().isBlank());
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
      if (target.isBlank()) {
        continue;
      }
      // 精确匹配
      if (currentNode.value().equalsIgnoreCase(target)) {
        return true;
      }
      // 尝试 DYNAMIC 匹配（支持 "DSTY DYNAMIC:OP:D:DEPOT" 格式）
      Optional<DynamicStopMatcher.DynamicSpec> specOpt =
          DynamicStopMatcher.parseDynamicSpec(target);
      if (specOpt.isPresent() && DynamicStopMatcher.matches(currentNode, specOpt.get())) {
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

  /**
   * 处理 CHANGE 换线指令：更新列车所属的 operator/line 标识。
   *
   * <p>语法：{@code CHANGE:<OperatorCode>:<LineCode>}，例如 {@code CHANGE:SURN:LT}。
   *
   * <p>行为：
   *
   * <ul>
   *   <li>仅更新列车 tags（OPERATOR_CODE/LINE_CODE），不改变当前 Route 或 routeIndex
   *   <li>列车继续沿当前 route 运行，但逻辑上归属于新的 operator/line
   *   <li>典型场景：直通车在枢纽站由 A 线移交给 B 线运营
   * </ul>
   *
   * @param trainName 列车名
   * @param properties 列车属性
   * @param stop 当前 RouteStop
   * @return 是否成功执行换线标识更新
   */
  private boolean handleChangeAction(String trainName, TrainProperties properties, RouteStop stop) {
    if (stop == null || trainName == null || properties == null) {
      return false;
    }
    Optional<String> remainderOpt = findDirectiveTarget(stop, "CHANGE");
    if (remainderOpt.isEmpty()) {
      return false;
    }
    String remainder = remainderOpt.get();
    // 解析 CHANGE:OperatorCode:LineCode
    String[] parts = remainder.split(":", -1);
    if (parts.length < 2) {
      debugLogger.accept("CHANGE 解析失败: 格式错误 train=" + trainName + " raw=" + remainder);
      return false;
    }
    String operatorCode = parts[0].trim();
    String lineCode = parts[1].trim();

    if (operatorCode.isBlank() || lineCode.isBlank()) {
      debugLogger.accept("CHANGE 解析失败: operator/line 为空 train=" + trainName + " raw=" + remainder);
      return false;
    }

    // 仅更新 operator/line tags，不改变 route
    TrainTagHelper.writeTag(properties, RouteProgressRegistry.TAG_OPERATOR_CODE, operatorCode);
    TrainTagHelper.writeTag(properties, RouteProgressRegistry.TAG_LINE_CODE, lineCode);

    debugLogger.accept(
        "CHANGE 移交成功: train=" + trainName + " newOp=" + operatorCode + " newLine=" + lineCode);
    return true;
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
      if (rest.startsWith(":")) {
        rest = rest.substring(1).trim();
      }
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

  /**
   * 从 DSTY 指令中提取 DYNAMIC 规范。
   *
   * <p>支持格式 {@code DSTY DYNAMIC:OP:STATION:[1:3]}，返回 {@code OP:STATION:[1:3]}。
   *
   * @param stop RouteStop
   * @return DYNAMIC 规范字符串，或 empty
   */
  private Optional<String> extractDynamicFromDsty(RouteStop stop) {
    Optional<String> dstyTarget = findDirectiveTarget(stop, "DSTY");
    if (dstyTarget.isEmpty()) {
      return Optional.empty();
    }
    String target = dstyTarget.get();
    // 检查是否以 DYNAMIC: 开头（不区分大小写）
    if (target.length() > 8 && target.regionMatches(true, 0, "DYNAMIC:", 0, 8)) {
      return Optional.of(target.substring(8));
    }
    return Optional.empty();
  }

  /**
   * 查找 route 中的 DSTY DYNAMIC depot 规范。
   *
   * <p>用于在"已到终点"时检查是否需要继续前往 depot 销毁。
   *
   * @param route RouteDefinition
   * @return DSTY DYNAMIC depot 的 DynamicSpec，或 empty
   */
  private Optional<DynamicStopMatcher.DynamicSpec> findDstyDynamicDepotSpec(RouteDefinition route) {
    if (route == null) {
      return Optional.empty();
    }
    List<RouteStop> stops = routeDefinitions.listStops(route.id());
    for (RouteStop stop : stops) {
      Optional<String> dstyTarget = findDirectiveTarget(stop, "DSTY");
      if (dstyTarget.isEmpty()) {
        continue;
      }
      String target = dstyTarget.get();
      Optional<DynamicStopMatcher.DynamicSpec> specOpt =
          DynamicStopMatcher.parseDynamicSpec(target);
      if (specOpt.isPresent() && specOpt.get().isDepot()) {
        return specOpt;
      }
    }
    return Optional.empty();
  }

  /**
   * 尝试为 DSTY DYNAMIC depot 分配站台并设置为下一个 destination。
   *
   * <p>在"已到终点"时调用，检查是否有 DSTY DYNAMIC depot，如果有则动态分配并继续前进。
   *
   * @param trainName 列车名
   * @param train 列车句柄（未使用但保留签名以便后续扩展）
   * @param properties 列车属性
   * @param route RouteDefinition
   * @param currentNode 当前节点
   * @param graph RailGraph
   * @return true 如果成功设置了 depot destination，false 表示无需或无法分配
   */
  @SuppressWarnings("unused")
  private boolean tryAllocateDstyDynamicDepot(
      String trainName,
      RuntimeTrainHandle train,
      TrainProperties properties,
      RouteDefinition route,
      NodeId currentNode,
      RailGraph graph) {
    Optional<DynamicStopMatcher.DynamicSpec> specOpt = findDstyDynamicDepotSpec(route);
    if (specOpt.isEmpty()) {
      return false;
    }
    DynamicStopMatcher.DynamicSpec spec = specOpt.get();
    // 使用 DynamicPlatformAllocator 分配 depot 站台
    Optional<NodeId> allocatedOpt =
        dynamicAllocator.allocateDirect(trainName, spec, graph, currentNode);
    if (allocatedOpt.isEmpty()) {
      debugLogger.accept(
          "DSTY DYNAMIC depot 分配失败: train="
              + trainName
              + " spec="
              + DynamicStopMatcher.specToStationKey(spec));
      return false;
    }
    NodeId depotNode = allocatedOpt.get();
    String destinationName = resolveDestinationName(depotNode);
    if (destinationName == null || destinationName.isBlank()) {
      destinationName = depotNode.value();
    }
    properties.clearDestinationRoute();
    properties.setDestination(destinationName);
    debugLogger.accept(
        "DSTY DYNAMIC depot 分配成功: train="
            + trainName
            + " depot="
            + depotNode.value()
            + " dest="
            + destinationName);
    return true;
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

  /**
   * 解析 DYNAMIC 动态站台指令。
   *
   * <p>支持格式（大小写不敏感）：
   *
   * <ul>
   *   <li>{@code DYNAMIC:OP:STATION:[1:3]}
   *   <li>{@code OP:STATION:[1:3]}（已去掉 DYNAMIC: 前缀的 remainder）
   *   <li>{@code DYNAMIC:OP:STATION} / {@code OP:STATION}（默认 track=1）
   * </ul>
   */
  /**
   * 解析 DYNAMIC stop 规范字符串。
   *
   * <p>支持以下格式：
   *
   * <ul>
   *   <li>{@code OP:S:STATION:[1:3]} - Station 类型，轨道范围 1-3
   *   <li>{@code OP:D:DEPOT:[1:3]} - Depot 类型，轨道范围 1-3
   *   <li>{@code OP:S:STATION:1} - Station 类型，单轨道 1
   *   <li>{@code OP:STATION:[1:3]} - 旧格式兼容（默认 Station 类型）
   * </ul>
   *
   * @param raw 原始字符串（可能带有 DYNAMIC: 前缀）
   * @return 解析结果
   */
  private static Optional<DynamicStopSpec> parseDynamicStopSpec(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String trimmed = raw.trim();
    // 去掉可能的 DYNAMIC: 前缀
    if (trimmed.regionMatches(true, 0, "DYNAMIC", 0, "DYNAMIC".length())) {
      String rest = trimmed.substring("DYNAMIC".length()).trim();
      if (rest.startsWith(":")) {
        rest = rest.substring(1).trim();
      }
      trimmed = rest;
    }
    if (trimmed.isBlank()) {
      return Optional.empty();
    }

    // 解析格式: OP:S:STATION:[range] 或 OP:D:DEPOT:[range] 或 OP:STATION:[range]
    String[] parts = trimmed.split(":", -1);
    if (parts.length < 2) {
      return Optional.empty();
    }

    String operatorCode;
    String nodeType; // "S" or "D"
    String nodeName;
    String rangeRaw;

    if (parts.length >= 3 && (parts[1].equalsIgnoreCase("S") || parts[1].equalsIgnoreCase("D"))) {
      // 新格式: OP:S:STATION:... 或 OP:D:DEPOT:...
      operatorCode = parts[0].trim();
      nodeType = parts[1].trim().toUpperCase(java.util.Locale.ROOT);
      nodeName = parts[2].trim();
      // 剩余部分作为 range（可能是 "1" 或 "[1:3]" 或 "1:3"）
      if (parts.length > 3) {
        rangeRaw = String.join(":", java.util.Arrays.copyOfRange(parts, 3, parts.length)).trim();
      } else {
        rangeRaw = "";
      }
    } else {
      // 旧格式兼容: OP:STATION:[range]
      operatorCode = parts[0].trim();
      nodeType = "S"; // 默认 Station
      nodeName = parts[1].trim();
      if (parts.length > 2) {
        rangeRaw = String.join(":", java.util.Arrays.copyOfRange(parts, 2, parts.length)).trim();
      } else {
        rangeRaw = "";
      }
    }

    if (operatorCode.isBlank() || nodeName.isBlank()) {
      return Optional.empty();
    }

    // 解析范围
    int fromTrack = 1;
    int toTrack = 1;
    if (!rangeRaw.isBlank()) {
      String normalizedRange = rangeRaw.trim();
      if (normalizedRange.startsWith("[") && normalizedRange.endsWith("]")) {
        normalizedRange = normalizedRange.substring(1, normalizedRange.length() - 1).trim();
      }
      if (!normalizedRange.isBlank()) {
        int colon = normalizedRange.indexOf(':');
        if (colon < 0) {
          OptionalInt single = parsePositiveInt(normalizedRange);
          if (single.isEmpty()) {
            return Optional.empty();
          }
          fromTrack = single.getAsInt();
          toTrack = single.getAsInt();
        } else {
          OptionalInt from = parsePositiveInt(normalizedRange.substring(0, colon));
          OptionalInt to = parsePositiveInt(normalizedRange.substring(colon + 1));
          if (from.isEmpty() || to.isEmpty()) {
            return Optional.empty();
          }
          fromTrack = from.getAsInt();
          toTrack = to.getAsInt();
        }
      }
    }
    if (fromTrack <= 0 || toTrack <= 0) {
      return Optional.empty();
    }
    int start = Math.min(fromTrack, toTrack);
    int end = Math.max(fromTrack, toTrack);
    return Optional.of(new DynamicStopSpec(operatorCode, nodeType, nodeName, start, end));
  }

  private static OptionalInt parsePositiveInt(String raw) {
    if (raw == null) {
      return OptionalInt.empty();
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return OptionalInt.empty();
    }
    try {
      int value = Integer.parseInt(trimmed);
      return value > 0 ? OptionalInt.of(value) : OptionalInt.empty();
    } catch (NumberFormatException ex) {
      return OptionalInt.empty();
    }
  }

  /**
   * DYNAMIC 动态站台指令规范化结果。
   *
   * @param operatorCode 运营商代码
   * @param nodeType 节点类型："S" 表示 Station，"D" 表示 Depot
   * @param nodeName 站点/车库名称
   * @param fromTrack 起始轨道号
   * @param toTrack 结束轨道号
   */
  private record DynamicStopSpec(
      String operatorCode, String nodeType, String nodeName, int fromTrack, int toTrack) {
    private DynamicStopSpec {
      Objects.requireNonNull(operatorCode, "operatorCode");
      Objects.requireNonNull(nodeType, "nodeType");
      Objects.requireNonNull(nodeName, "nodeName");
    }
  }

  private Optional<NodeId> resolveDynamicStationTargetIfNeeded(
      String trainName, RouteDefinition route, int targetIndex, NodeId fromNode, RailGraph graph) {
    if (trainName == null
        || trainName.isBlank()
        || route == null
        || fromNode == null
        || graph == null
        || targetIndex < 0) {
      return Optional.empty();
    }
    Optional<RouteStop> stopOpt = routeDefinitions.findStop(route.id(), targetIndex);
    if (stopOpt.isEmpty()) {
      return Optional.empty();
    }
    // 尝试直接 DYNAMIC 指令
    Optional<String> remainder = findDirectiveTarget(stopOpt.get(), "DYNAMIC");
    // 如果未找到，尝试从 DSTY 指令中提取 DYNAMIC 规范（支持 "DSTY DYNAMIC:..." 格式）
    if (remainder.isEmpty()) {
      remainder = extractDynamicFromDsty(stopOpt.get());
    }
    if (remainder.isEmpty()) {
      return Optional.empty();
    }
    Optional<DynamicStopSpec> specOpt = parseDynamicStopSpec(remainder.get());
    if (specOpt.isEmpty()) {
      debugLogger.accept(
          "DYNAMIC 解析失败: train=" + trainName + " idx=" + targetIndex + " raw=" + remainder.get());
      return Optional.empty();
    }
    DynamicStopSpec spec = specOpt.get();
    return selectDynamicStationTarget(trainName, fromNode, graph, spec);
  }

  /**
   * 选择 DYNAMIC 站台/车库目标。
   *
   * <p>选择规则：
   *
   * <ol>
   *   <li>Pass 1: 优先选择空闲且可达的轨道（按轨道号顺序）
   *   <li>Pass 2: 若无空闲，回退到任意可达轨道
   * </ol>
   *
   * @param trainName 列车名称
   * @param fromNode 当前节点
   * @param graph 调度图
   * @param spec DYNAMIC 规范
   * @return 选择的目标节点
   */
  private Optional<NodeId> selectDynamicStationTarget(
      String trainName, NodeId fromNode, RailGraph graph, DynamicStopSpec spec) {
    if (spec == null || fromNode == null || graph == null) {
      return Optional.empty();
    }
    String operator = spec.operatorCode().trim();
    String nodeType = spec.nodeType().trim(); // "S" or "D"
    String nodeName = spec.nodeName().trim();
    if (operator.isEmpty() || nodeName.isEmpty()) {
      return Optional.empty();
    }

    // Pass 1: free first, then reachable.
    for (int track = spec.fromTrack(); track <= spec.toTrack(); track++) {
      NodeId candidate = NodeId.of(operator + ":" + nodeType + ":" + nodeName + ":" + track);
      if (!isDynamicCandidateKnown(candidate, graph)) {
        continue;
      }
      if (!isNodeFree(trainName, candidate)) {
        continue;
      }
      if (resolveShortestDistance(graph, fromNode, candidate).isEmpty()) {
        continue;
      }
      return Optional.of(candidate);
    }

    // Pass 2 (fallback): if no free platform, pick any reachable platform (still deterministic).
    for (int track = spec.fromTrack(); track <= spec.toTrack(); track++) {
      NodeId candidate = NodeId.of(operator + ":" + nodeType + ":" + nodeName + ":" + track);
      if (!isDynamicCandidateKnown(candidate, graph)) {
        continue;
      }
      if (resolveShortestDistance(graph, fromNode, candidate).isEmpty()) {
        continue;
      }
      debugLogger.accept(
          "DYNAMIC 回退: 无空闲站台，选择可达站台 train="
              + trainName
              + " from="
              + fromNode.value()
              + " target="
              + candidate.value());
      return Optional.of(candidate);
    }
    debugLogger.accept(
        "DYNAMIC 失败: 未找到可达站台 train="
            + trainName
            + " from="
            + fromNode.value()
            + " operator="
            + operator
            + " type="
            + nodeType
            + " name="
            + nodeName
            + " range="
            + spec.fromTrack()
            + ":"
            + spec.toTrack());
    return Optional.empty();
  }

  /**
   * 推进点专用：为“下一站 DYNAMIC stop”选择一个可进入的站台，并返回对应的 OccupancyRequestContext。
   *
   * <p>选择规则（满足用户语义）：先挑空闲站台（NODE 资源未被其他列车占用），再检查可达性与占用许可。
   *
   * <p>实现上通过对每个候选站台构建一次 lookahead 请求并调用 canEnter() 验证，避免“站台空闲但冲突组/走廊方向阻塞”的误选。
   */
  private Optional<DynamicSelection> selectDynamicStationTargetForProgress(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      int targetIndex,
      NodeId fromNode,
      RailGraph graph,
      OccupancyRequestBuilder builder,
      Instant now,
      int priority) {
    if (trainName == null
        || trainName.isBlank()
        || route == null
        || fromNode == null
        || graph == null
        || builder == null
        || occupancyManager == null) {
      return Optional.empty();
    }
    Optional<RouteStop> stopOpt = routeDefinitions.findStop(route.id(), targetIndex);
    if (stopOpt.isEmpty()) {
      return Optional.empty();
    }
    // 尝试直接 DYNAMIC 指令
    Optional<String> remainder = findDirectiveTarget(stopOpt.get(), "DYNAMIC");
    // 如果未找到，尝试从 DSTY 指令中提取 DYNAMIC 规范（支持 "DSTY DYNAMIC:..." 格式）
    if (remainder.isEmpty()) {
      remainder = extractDynamicFromDsty(stopOpt.get());
    }
    if (remainder.isEmpty()) {
      return Optional.empty();
    }
    Optional<DynamicStopSpec> specOpt = parseDynamicStopSpec(remainder.get());
    if (specOpt.isEmpty()) {
      debugLogger.accept(
          "DYNAMIC 解析失败: train=" + trainName + " idx=" + targetIndex + " raw=" + remainder.get());
      return Optional.empty();
    }
    DynamicStopSpec spec = specOpt.get();
    List<NodeId> baseNodes = resolveEffectiveWaypoints(trainName, route);
    if (targetIndex < 0 || targetIndex >= baseNodes.size()) {
      return Optional.empty();
    }

    // Pass 1: free first.
    Optional<DynamicSelection> freeSelection =
        selectDynamicStationTargetForProgressPass(
            trainName,
            route,
            currentIndex,
            targetIndex,
            fromNode,
            graph,
            builder,
            now,
            priority,
            baseNodes,
            spec,
            true);
    if (freeSelection.isPresent()) {
      return freeSelection;
    }

    // Pass 2: fallback to any enterable platform (deterministic order).
    return selectDynamicStationTargetForProgressPass(
        trainName,
        route,
        currentIndex,
        targetIndex,
        fromNode,
        graph,
        builder,
        now,
        priority,
        baseNodes,
        spec,
        false);
  }

  private Optional<DynamicSelection> selectDynamicStationTargetForProgressPass(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      int targetIndex,
      NodeId fromNode,
      RailGraph graph,
      OccupancyRequestBuilder builder,
      Instant now,
      int priority,
      List<NodeId> baseNodes,
      DynamicStopSpec spec,
      boolean requireFree) {
    String operator = spec.operatorCode().trim();
    String nodeType = spec.nodeType().trim();
    String nodeName = spec.nodeName().trim();

    // 收集所有可行的候选
    List<DynamicCandidate> candidates = new java.util.ArrayList<>();
    for (int track = spec.fromTrack(); track <= spec.toTrack(); track++) {
      NodeId candidate = NodeId.of(operator + ":" + nodeType + ":" + nodeName + ":" + track);
      if (!isDynamicCandidateKnown(candidate, graph)) {
        continue;
      }
      if (requireFree && !isNodeFree(trainName, candidate)) {
        continue;
      }
      if (resolveShortestDistance(graph, fromNode, candidate).isEmpty()) {
        continue;
      }
      List<NodeId> nodes = new java.util.ArrayList<>(baseNodes);
      nodes.set(targetIndex, candidate);
      Optional<OccupancyRequestContext> ctxOpt =
          builder.buildContextFromNodes(
              trainName, Optional.ofNullable(route.id()), nodes, currentIndex, now, priority);
      if (ctxOpt.isEmpty()) {
        continue;
      }
      OccupancyRequest request = ctxOpt.get().request();
      OccupancyDecision decision = occupancyManager.canEnter(request);
      if (!decision.allowed()) {
        continue;
      }
      candidates.add(new DynamicCandidate(candidate, ctxOpt.get(), decision));
    }

    if (candidates.isEmpty()) {
      return Optional.empty();
    }

    // 如果只有一个候选，直接返回
    if (candidates.size() == 1) {
      DynamicCandidate single = candidates.get(0);
      if (!requireFree) {
        debugLogger.accept(
            "DYNAMIC 回退: 无空闲站台，选择可进入站台 train="
                + trainName
                + " from="
                + fromNode.value()
                + " target="
                + single.candidate.value());
      }
      return Optional.of(new DynamicSelection(single.candidate, single.context, single.decision));
    }

    // 多个候选时，按方向优选
    DynamicCandidate best =
        selectBestCandidateByDirection(
            trainName, fromNode, baseNodes, currentIndex, candidates, graph);

    if (!requireFree && best != null) {
      debugLogger.accept(
          "DYNAMIC 方向优选: train="
              + trainName
              + " from="
              + fromNode.value()
              + " target="
              + best.candidate.value()
              + " (共 "
              + candidates.size()
              + " 个候选)");
    }

    if (best != null) {
      return Optional.of(new DynamicSelection(best.candidate, best.context, best.decision));
    }

    // 兜底：返回第一个候选
    DynamicCandidate fallback = candidates.get(0);
    return Optional.of(
        new DynamicSelection(fallback.candidate, fallback.context, fallback.decision));
  }

  /** DYNAMIC 候选站台记录。 */
  private record DynamicCandidate(
      NodeId candidate, OccupancyRequestContext context, OccupancyDecision decision) {}

  /**
   * 按方向选择最佳候选站台。
   *
   * <p>优选规则：
   *
   * <ol>
   *   <li>计算列车运行方向：从前一个节点到当前节点的向量
   *   <li>计算每个候选的首跳方向：从当前节点到路径上第一个非 switcher 节点的向量
   *   <li>选择与运行方向夹角最小的候选（避免 180 度折回）
   * </ol>
   */
  private DynamicCandidate selectBestCandidateByDirection(
      String trainName,
      NodeId fromNode,
      List<NodeId> baseNodes,
      int currentIndex,
      List<DynamicCandidate> candidates,
      RailGraph graph) {
    if (candidates == null || candidates.isEmpty() || graph == null) {
      return null;
    }

    // 获取前一个节点用于计算运行方向
    NodeId prevNode = currentIndex > 0 ? baseNodes.get(currentIndex - 1) : null;
    if (prevNode == null) {
      // 无法确定运行方向，返回第一个
      return candidates.get(0);
    }

    // 获取节点位置
    org.bukkit.util.Vector prevPos = getNodePosition(graph, prevNode);
    org.bukkit.util.Vector fromPos = getNodePosition(graph, fromNode);
    if (prevPos == null || fromPos == null) {
      return candidates.get(0);
    }

    // 计算运行方向向量
    double travelDx = fromPos.getX() - prevPos.getX();
    double travelDz = fromPos.getZ() - prevPos.getZ();
    double travelMag = Math.sqrt(travelDx * travelDx + travelDz * travelDz);
    if (travelMag < 1.0e-6) {
      return candidates.get(0);
    }
    // 归一化
    travelDx /= travelMag;
    travelDz /= travelMag;

    DynamicCandidate best = null;
    double bestScore = Double.NEGATIVE_INFINITY;

    for (DynamicCandidate cand : candidates) {
      // 获取路径中第一个"引导节点"：过了 switcher 区域后的第一个非 switcher 节点
      NodeId guideNode = findPathGuideNode(cand.context.pathNodes(), graph, fromNode);
      if (guideNode == null) {
        guideNode = cand.candidate;
      }

      org.bukkit.util.Vector guidePos = getNodePosition(graph, guideNode);
      if (guidePos == null) {
        continue;
      }

      // 计算从当前节点到引导节点的方向
      double guideDx = guidePos.getX() - fromPos.getX();
      double guideDz = guidePos.getZ() - fromPos.getZ();
      double guideMag = Math.sqrt(guideDx * guideDx + guideDz * guideDz);
      if (guideMag < 1.0e-6) {
        continue;
      }
      guideDx /= guideMag;
      guideDz /= guideMag;

      // 计算方向相似度（点积，范围 -1 到 1，越大越顺）
      double dotProduct = travelDx * guideDx + travelDz * guideDz;

      debugLogger.accept(
          "DYNAMIC 候选方向评估: train="
              + trainName
              + " candidate="
              + cand.candidate.value()
              + " guide="
              + guideNode.value()
              + " score="
              + String.format("%.3f", dotProduct));

      if (dotProduct > bestScore) {
        bestScore = dotProduct;
        best = cand;
      }
    }

    return best;
  }

  /**
   * 从路径中找到引导节点：跳过起始的 switcher 区域，返回第一个非 switcher 节点。
   *
   * <p>用于确定列车应该朝哪个方向行驶（避免被 switcher 误导）。
   */
  private NodeId findPathGuideNode(List<NodeId> pathNodes, RailGraph graph, NodeId fromNode) {
    if (pathNodes == null || pathNodes.size() < 2 || graph == null) {
      return null;
    }

    // 跳过起始节点和 switcher 节点，找到第一个非 switcher 的目标节点
    boolean passedFrom = false;
    for (NodeId node : pathNodes) {
      if (node == null) {
        continue;
      }
      if (!passedFrom) {
        if (node.equals(fromNode)) {
          passedFrom = true;
        }
        continue;
      }
      // 检查是否是 switcher
      Optional<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> railNodeOpt =
          graph.findNode(node);
      if (railNodeOpt.isEmpty()) {
        continue;
      }
      if (railNodeOpt.get().type() != NodeType.SWITCHER) {
        return node;
      }
    }
    return null;
  }

  private org.bukkit.util.Vector getNodePosition(RailGraph graph, NodeId nodeId) {
    if (graph == null || nodeId == null) {
      return null;
    }
    return graph
        .findNode(nodeId)
        .map(org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode::worldPosition)
        .orElse(null);
  }

  private record DynamicSelection(
      NodeId targetNode, OccupancyRequestContext context, OccupancyDecision decision) {
    private DynamicSelection {
      Objects.requireNonNull(targetNode, "targetNode");
      Objects.requireNonNull(context, "context");
      Objects.requireNonNull(decision, "decision");
    }
  }

  private boolean isDynamicCandidateKnown(NodeId candidate, RailGraph graph) {
    if (candidate == null) {
      return false;
    }
    // 站台节点必须：
    // 1) 存在于注册表（能解析到 TrainCarts destination）；2) 存在于图快照（否则无法寻路/估距）。
    if (signNodeRegistry.findByNodeId(candidate, null).isEmpty()) {
      return false;
    }
    return graph.findNode(candidate).isPresent();
  }

  private boolean isNodeFree(String trainName, NodeId nodeId) {
    if (nodeId == null || occupancyManager == null) {
      return true;
    }
    OccupancyResource resource = OccupancyResource.forNode(nodeId);
    for (OccupancyClaim claim : occupancyManager.snapshotClaims()) {
      if (claim == null || claim.resource() == null) {
        continue;
      }
      if (!resource.equals(claim.resource())) {
        continue;
      }
      if (claim.trainName() != null && claim.trainName().equalsIgnoreCase(trainName)) {
        continue;
      }
      return false;
    }
    return true;
  }

  private record WaypointStopState(
      String sessionId, NodeId nodeId, Instant createdAt, int dwellSeconds) {
    private WaypointStopState {
      Objects.requireNonNull(sessionId, "sessionId");
      Objects.requireNonNull(nodeId, "nodeId");
      Objects.requireNonNull(createdAt, "createdAt");
    }
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

  // ======== 节点历史与回退检测 ========

  /** 记录节点历史。 */
  private void recordNodeHistory(String trainName, NodeId node) {
    if (trainName == null || trainName.isBlank() || node == null) {
      return;
    }
    nodeHistoryCache
        .computeIfAbsent(trainName, k -> new NodeHistory(NODE_HISTORY_CAPACITY))
        .record(node);
  }

  /**
   * 检测并处理异常回退。
   *
   * <p>当列车触发一个"已在历史中且位置更靠前"的节点时，判定为异常回退（被反弹），执行停车 + 重新 launch。
   *
   * @return true 表示检测到回退并已处理，调用方应跳过后续流程
   */
  private boolean detectAndHandleRegression(
      SignActionEvent event,
      RuntimeTrainHandle train,
      TrainProperties properties,
      String trainName,
      RouteDefinition route,
      NodeId currentNode) {
    if (trainName == null || currentNode == null || route == null) {
      return false;
    }
    NodeHistory history = nodeHistoryCache.get(trainName);
    if (history == null) {
      return false;
    }
    int regressionIdx = history.detectRegression(currentNode);
    if (regressionIdx < 0) {
      return false; // 未检测到回退
    }
    // 冷却检查：避免短时间内反复 relaunch
    long now = System.currentTimeMillis();
    if (now - history.lastRelaunchAtMs() < RELAUNCH_COOLDOWN_MS) {
      debugLogger.accept(
          "回退检测冷却中: train="
              + trainName
              + " node="
              + currentNode.value()
              + " cooldownMs="
              + RELAUNCH_COOLDOWN_MS);
      return false;
    }
    debugLogger.accept(
        "回退检测触发: train="
            + trainName
            + " node="
            + currentNode.value()
            + " regressionIdx="
            + regressionIdx
            + " history="
            + history.snapshot());

    // 执行停车 + 重新 launch
    boolean success =
        relaunchToCorrectDirection(event, train, properties, trainName, route, currentNode);
    if (success) {
      // 先清除历史，再记录 relaunch 时间（避免 clear 重置时间戳）
      history.clear();
      history.recordRelaunch();
      history.record(currentNode); // 重新记录当前节点作为起点
    }
    return success;
  }

  /**
   * 强制停车并重新 launch 到正确方向。
   *
   * @return true 表示成功 relaunch
   */
  private boolean relaunchToCorrectDirection(
      SignActionEvent event,
      RuntimeTrainHandle train,
      TrainProperties properties,
      String trainName,
      RouteDefinition route,
      NodeId currentNode) {
    if (train == null || properties == null || route == null) {
      return false;
    }

    // 找到当前节点在 route 中的索引
    int currentIndex = findIndexInRoute(route, currentNode);
    if (currentIndex < 0) {
      debugLogger.accept(
          "relaunch 失败: 当前节点不在 route 中 train=" + trainName + " node=" + currentNode.value());
      return false;
    }
    int nextIndex = currentIndex + 1;
    if (nextIndex >= route.waypoints().size()) {
      debugLogger.accept("relaunch 失败: 已到终点 train=" + trainName);
      return false;
    }
    NodeId nextNode = route.waypoints().get(nextIndex);

    // 获取图以计算 launch 方向
    Optional<RailGraph> graphOpt = resolveGraph(event);
    if (graphOpt.isEmpty()) {
      debugLogger.accept("relaunch 失败: 未找到调度图 train=" + trainName);
      return false;
    }
    RailGraph graph = graphOpt.get();

    // 设置 destination
    String destinationName = resolveDestinationName(nextNode);
    if (destinationName == null || destinationName.isBlank()) {
      destinationName = nextNode.value();
    }
    properties.clearDestinationRoute();
    properties.setDestination(destinationName);

    // 计算 launch 方向（必须有方向才能 forceRelaunch）
    java.util.Optional<org.bukkit.block.BlockFace> launchDirection =
        resolveLaunchDirectionByGraph(graph, currentNode, nextNode);
    if (launchDirection.isEmpty()) {
      debugLogger.accept(
          "relaunch 失败: 无法计算方向 train="
              + trainName
              + " from="
              + currentNode.value()
              + " to="
              + nextNode.value());
      return false;
    }

    // 使用配置的速度参数
    TrainConfig config = trainConfigResolver.resolve(properties, configManager.current());
    double targetBps = configManager.current().graphSettings().defaultSpeedBlocksPerSecond();
    double targetBpt = targetBps / 20.0;
    double accelBpt2 = config.accelBps2() / 400.0;
    properties.setSpeedLimit(targetBpt);

    // 执行强制重发（立即停车 + 立即发车，不检查 isMoving）
    train.forceRelaunch(launchDirection.get(), targetBpt, accelBpt2);

    Instant now = Instant.now();
    updateSignalOrWarn(trainName, SignalAspect.PROCEED, now);

    debugLogger.accept(
        "relaunch 成功: train="
            + trainName
            + " from="
            + currentNode.value()
            + " to="
            + nextNode.value()
            + " direction="
            + launchDirection.get().name());
    return true;
  }

  /** 在 route 的 waypoints 中查找节点索引。 */
  private int findIndexInRoute(RouteDefinition route, NodeId node) {
    if (route == null || node == null) {
      return -1;
    }
    List<NodeId> waypoints = route.waypoints();
    for (int i = 0; i < waypoints.size(); i++) {
      if (waypoints.get(i).equals(node)) {
        return i;
      }
    }
    return -1;
  }

  /** 清除列车的节点历史（用于列车销毁时清理）。 */
  private void clearNodeHistory(String trainName) {
    if (trainName != null && !trainName.isBlank()) {
      nodeHistoryCache.remove(trainName);
    }
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

  private static String normalizeTrainKey(String trainName) {
    if (trainName == null) {
      return "";
    }
    return trainName.trim().toLowerCase(java.util.Locale.ROOT);
  }

  private void recordEffectiveNode(
      String trainName, RouteDefinition route, int index, NodeId effectiveNode) {
    if (trainName == null
        || trainName.isBlank()
        || route == null
        || effectiveNode == null
        || index < 0
        || index >= route.waypoints().size()) {
      return;
    }
    NodeId declared = route.waypoints().get(index);
    if (effectiveNode.equals(declared)) {
      return;
    }
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return;
    }
    effectiveNodeOverrides
        .computeIfAbsent(key, k -> new java.util.concurrent.ConcurrentHashMap<>())
        .put(index, effectiveNode);
  }

  /**
   * 强制写入有效节点覆盖（即使与声明相同也写入）。
   *
   * <p>用于"首站位置初始化"场景：即使 spawn 位置与 route 第 0 站相同，也需要写入覆盖表以标记"已处理"，避免每 tick 重复检查和打印日志。
   */
  private void forceRecordEffectiveNode(String trainName, int index, NodeId effectiveNode) {
    if (trainName == null || trainName.isBlank() || effectiveNode == null || index < 0) {
      return;
    }
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return;
    }
    effectiveNodeOverrides
        .computeIfAbsent(key, k -> new java.util.concurrent.ConcurrentHashMap<>())
        .put(index, effectiveNode);
  }

  private Optional<NodeId> readEffectiveNode(String trainName, int index) {
    if (trainName == null || trainName.isBlank() || index < 0) {
      return Optional.empty();
    }
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return Optional.empty();
    }
    java.util.concurrent.ConcurrentMap<Integer, NodeId> overrides = effectiveNodeOverrides.get(key);
    if (overrides == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(overrides.get(index));
  }

  /**
   * 尝试为列车分配动态站台。
   *
   * <p>当列车距离 DYNAMIC 站点 ≤5 edges 时，从候选范围内选择可用站台并写入节点覆盖表和 destination。
   */
  private void tryDynamicPlatformAllocation(
      RuntimeTrainHandle train,
      TrainProperties properties,
      String trainName,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode) {
    if (train == null || route == null || railGraphService == null) {
      return;
    }
    Optional<RailGraph> graphOpt = resolveGraph(train.worldId(), Instant.now());
    if (graphOpt.isEmpty()) {
      return;
    }
    RailGraph graph = graphOpt.get();

    Optional<DynamicPlatformAllocator.AllocationResult> resultOpt =
        dynamicAllocator.tryAllocate(
            trainName, route, currentIndex, graph, currentNode, train.forwardDirection());
    if (resultOpt.isEmpty()) {
      return;
    }

    DynamicPlatformAllocator.AllocationResult result = resultOpt.get();

    // 写入节点覆盖表
    recordEffectiveNode(trainName, route, result.stopIndex(), result.allocatedNode());

    // 更新列车 destination（如果分配的是下一站）
    int nextIndex = currentIndex + 1;
    if (result.stopIndex() == nextIndex && properties != null) {
      String dest = result.allocatedNode().value();
      properties.setDestination(dest);
      debugLogger.accept(
          "DYNAMIC destination 写入: train="
              + trainName
              + ", dest="
              + dest
              + ", idx="
              + result.stopIndex());
    }
  }

  private NodeId resolveEffectiveNode(String trainName, RouteDefinition route, int index) {
    if (route == null) {
      return null;
    }
    if (index < 0 || index >= route.waypoints().size()) {
      return null;
    }
    return readEffectiveNode(trainName, index).orElse(route.waypoints().get(index));
  }

  /**
   * 解析“当前节点”的信号评估位置。
   *
   * <p>当列车经过未在 route 中声明的中间 waypoint 时，允许使用 lastPassedGraphNode 作为“当前节点”，
   * 使占用与信号评估贴合真实位置，避免路径滞后导致的反向发车/误放行。
   *
   * <p>仅当 lastPassedGraphNode 位于 currentIndex -> nextIndex 的最短路路径中时才采用，确保不会跨段跳跃。
   */
  private NodeId resolveEffectiveCurrentNodeForSignal(
      String trainName, RouteDefinition route, int currentIndex, RailGraph graph) {
    NodeId routeNode = resolveEffectiveNode(trainName, route, currentIndex);
    if (routeNode == null || trainName == null || trainName.isBlank() || graph == null) {
      return routeNode;
    }
    Optional<RouteProgressRegistry.RouteProgressEntry> entryOpt = progressRegistry.get(trainName);
    if (entryOpt.isEmpty()) {
      return routeNode;
    }
    Optional<NodeId> lastPassedOpt = entryOpt.get().lastPassedGraphNode();
    if (lastPassedOpt.isEmpty()) {
      return routeNode;
    }
    NodeId lastPassed = lastPassedOpt.get();
    if (lastPassed.equals(routeNode)) {
      return routeNode;
    }
    if (currentIndex + 1 >= route.waypoints().size()) {
      return routeNode;
    }
    NodeId nextNode = resolveEffectiveNode(trainName, route, currentIndex + 1);
    if (nextNode == null) {
      return routeNode;
    }
    Optional<RailGraphPath> pathOpt =
        pathFinder.shortestPath(
            graph, routeNode, nextNode, RailGraphPathFinder.Options.shortestDistance());
    if (pathOpt.isEmpty()) {
      return routeNode;
    }
    if (!pathOpt.get().nodes().contains(lastPassed)) {
      return routeNode;
    }
    return lastPassed;
  }

  private List<NodeId> resolveEffectiveWaypoints(String trainName, RouteDefinition route) {
    if (route == null) {
      return List.of();
    }
    List<NodeId> base = route.waypoints();
    if (trainName == null || trainName.isBlank()) {
      return base;
    }
    String key = normalizeTrainKey(trainName);
    java.util.concurrent.ConcurrentMap<Integer, NodeId> overrides =
        key.isEmpty() ? null : effectiveNodeOverrides.get(key);
    if (overrides == null || overrides.isEmpty()) {
      return base;
    }
    List<NodeId> copy = new java.util.ArrayList<>(base);
    for (var entry : overrides.entrySet()) {
      Integer idx = entry.getKey();
      NodeId node = entry.getValue();
      if (idx == null || node == null) {
        continue;
      }
      if (idx < 0 || idx >= copy.size()) {
        continue;
      }
      copy.set(idx, node);
    }
    return copy;
  }

  private List<NodeId> applyCurrentNodeOverride(
      List<NodeId> nodes, int currentIndex, NodeId currentNode) {
    if (nodes == null || nodes.isEmpty() || currentNode == null) {
      return nodes;
    }
    if (currentIndex < 0 || currentIndex >= nodes.size()) {
      return nodes;
    }
    NodeId existing = nodes.get(currentIndex);
    if (currentNode.equals(existing)) {
      return nodes;
    }
    List<NodeId> copy = new java.util.ArrayList<>(nodes);
    copy.set(currentIndex, currentNode);
    return List.copyOf(copy);
  }

  /**
   * 停站期间保留“当前节点 + 尾部保护边”的占用，避免后车提前释放后互卡。
   *
   * <p>当调度图不可用时退化为“仅当前节点占用”。
   */
  private void retainStopOccupancy(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode,
      RailGraph graph,
      Instant now) {
    if (occupancyManager == null || trainName == null || trainName.isBlank()) {
      return;
    }
    if (currentNode == null) {
      occupancyManager.releaseByTrain(trainName);
      return;
    }
    if (graph == null || route == null) {
      OccupancyResource keepResource = OccupancyResource.forNode(currentNode);
      releaseResourcesNotInRequest(trainName, List.of(keepResource));
      OccupancyRequest locationRequest =
          new OccupancyRequest(
              trainName, Optional.empty(), now, List.of(keepResource), java.util.Map.of(), 0);
      occupancyManager.acquire(locationRequest);
      return;
    }
    ConfigManager.RuntimeSettings runtimeSettings = configManager.current().runtimeSettings();
    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(
            graph,
            runtimeSettings.lookaheadEdges(),
            runtimeSettings.minClearEdges(),
            runtimeSettings.rearGuardEdges(),
            runtimeSettings.switcherZoneEdges());
    List<NodeId> effectiveNodes = resolveEffectiveWaypoints(trainName, route);
    OccupancyRequest request =
        builder.buildRearGuardRequestFromNodes(
            trainName, Optional.ofNullable(route.id()), effectiveNodes, currentIndex, now, 0);
    releaseResourcesNotInRequest(trainName, request.resourceList());
    occupancyManager.acquire(request);
  }

  private void pruneEffectiveNodeOverrides(String trainName, int minIndexToKeep) {
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return;
    }
    java.util.concurrent.ConcurrentMap<Integer, NodeId> overrides = effectiveNodeOverrides.get(key);
    if (overrides == null || overrides.isEmpty()) {
      return;
    }
    for (Integer idx : new java.util.ArrayList<>(overrides.keySet())) {
      if (idx == null) {
        continue;
      }
      if (idx < minIndexToKeep) {
        overrides.remove(idx);
      }
    }
    if (overrides.isEmpty()) {
      effectiveNodeOverrides.remove(key);
    }
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

  /**
   * 创建边限速解析器（用于 SignalLookahead 前瞻）。
   *
   * <p>返回的解析器会考虑 edge override 和 temp speed limit。
   */
  private SignalLookahead.EdgeSpeedResolver createEdgeSpeedResolver(UUID worldId) {
    double defaultSpeed = configManager.current().graphSettings().defaultSpeedBlocksPerSecond();
    Instant now = Instant.now();
    return edge ->
        railGraphService.effectiveSpeedLimitBlocksPerSecond(worldId, edge, now, defaultSpeed);
  }

  /**
   * 边限速前瞻：根据前方边的限速约束计算当前应该的最大速度。
   *
   * <p>算法：对于每个前方的限速约束，使用物理公式反推"从当前位置能安全减速到目标限速所需的最大起始速度"：
   *
   * <ul>
   *   <li>制动距离公式: d = (v² - v_target²) / (2 * a)
   *   <li>反推: v_max = √(v_target² + 2 * a * d)
   * </ul>
   *
   * <p>取所有约束计算结果的最小值作为当前允许的最大速度。
   *
   * @param currentTargetBps 当前目标速度（blocks/s）
   * @param decelBps2 减速度（blocks/s²）
   * @param constraints 前方边限速约束列表
   * @return 调整后的目标速度
   */
  private double applyEdgeSpeedLookahead(
      double currentTargetBps,
      double decelBps2,
      List<SignalLookahead.EdgeSpeedConstraint> constraints) {
    if (constraints == null || constraints.isEmpty()) {
      return currentTargetBps;
    }
    if (!Double.isFinite(decelBps2) || decelBps2 <= 0.0) {
      return currentTargetBps;
    }

    double minAllowedSpeed = currentTargetBps;
    for (SignalLookahead.EdgeSpeedConstraint constraint : constraints) {
      double distance = constraint.distanceBlocks();
      double targetLimit = constraint.speedLimitBps();

      // 跳过"距离为 0 且限速不低于当前目标"的约束（当前边，已在 targetBps 中考虑）
      if (distance <= 0 && targetLimit >= currentTargetBps) {
        continue;
      }

      // 计算从当前位置能安全减速到 targetLimit 所需的最大起始速度
      // v_max = √(v_target² + 2 * a * d)
      double maxSpeedForConstraint =
          Math.sqrt(targetLimit * targetLimit + 2.0 * decelBps2 * distance);

      if (Double.isFinite(maxSpeedForConstraint) && maxSpeedForConstraint > 0.0) {
        minAllowedSpeed = Math.min(minAllowedSpeed, maxSpeedForConstraint);
      }
    }

    return minAllowedSpeed;
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

  /**
   * 解析 Route 的终点站信息（End of Operation）。
   *
   * <p>优先级：
   *
   * <ol>
   *   <li>TERMINATE 类型的 stop
   *   <li>最后一个 STOP 类型的 stop
   *   <li>最后一个 stop
   * </ol>
   *
   * <p>支持 DYNAMIC stop：从 DYNAMIC 规范中提取站点信息。
   *
   * @param route RouteDefinition
   * @return 终点站信息（name, code）
   */
  private Optional<DestinationDisplayInfo> resolveEndOfOperationInfo(RouteDefinition route) {
    if (route == null || routeDefinitions == null) {
      return Optional.empty();
    }
    List<RouteStop> stops = routeDefinitions.listStops(route.id());
    if (stops.isEmpty()) {
      return Optional.empty();
    }

    // 找到终点 stop
    RouteStop candidate = null;
    for (RouteStop stop : stops) {
      if (stop != null && stop.passType() == RouteStopPassType.TERMINATE) {
        candidate = stop;
      }
    }
    if (candidate == null) {
      for (RouteStop stop : stops) {
        if (stop != null && stop.passType() == RouteStopPassType.STOP) {
          candidate = stop;
        }
      }
    }
    if (candidate == null) {
      candidate = stops.get(stops.size() - 1);
    }

    // 优先从 stationId 解析
    UUID stationId = candidate.stationId().orElse(null);
    if (stationId != null && storageManager != null && storageManager.isReady()) {
      Optional<org.fetarute.fetaruteTCAddon.company.model.Station> stationOpt =
          storageManager.provider().flatMap(p -> p.stations().findById(stationId));
      if (stationOpt.isPresent()) {
        org.fetarute.fetaruteTCAddon.company.model.Station station = stationOpt.get();
        return Optional.of(new DestinationDisplayInfo(station.name(), station.code()));
      }
    }

    // 尝试从 DYNAMIC 规范解析
    Optional<DynamicStopMatcher.DynamicSpec> dynamicSpec =
        DynamicStopMatcher.parseDynamicSpec(candidate);
    if (dynamicSpec.isPresent() && dynamicSpec.get().isStation()) {
      DynamicStopMatcher.DynamicSpec spec = dynamicSpec.get();
      // 直接使用 DYNAMIC 规范中的 nodeName 作为显示名称
      // 注：完整的站点名称查询需要 operatorId，这里简化处理
      return Optional.of(new DestinationDisplayInfo(spec.nodeName(), spec.nodeName()));
    }

    // 从 waypointNodeId 解析
    if (candidate.waypointNodeId().isPresent()) {
      String nodeId = candidate.waypointNodeId().get();
      // 尝试解析站点格式 OP:S:STATION:TRACK
      String[] parts = nodeId.split(":", -1);
      if (parts.length >= 4 && "S".equalsIgnoreCase(parts[1])) {
        String stationName = parts[2];
        // 直接使用解析出的站点名称
        return Optional.of(new DestinationDisplayInfo(stationName, stationName));
      }
      return Optional.of(new DestinationDisplayInfo(nodeId, nodeId));
    }

    // fallback: 使用 route name
    return route
        .metadata()
        .map(meta -> new DestinationDisplayInfo(meta.serviceId(), meta.serviceId()));
  }

  /**
   * 根据新的 destination 重新生成 trainName。
   *
   * <p>用于 Layover 复用时更新列车名，确保 destination 首字母正确。
   */
  private String regenerateTrainName(RouteDefinition route, String destName) {
    if (route == null) {
      return null;
    }
    Optional<org.fetarute.fetaruteTCAddon.dispatcher.route.RouteMetadata> metaOpt =
        route.metadata();
    String operator = metaOpt.map(m -> m.operator()).orElse("OP");
    String line = metaOpt.map(m -> m.lineId()).orElse("LINE");
    RoutePatternType pattern = resolvePatternType(route);
    String dest = destName;
    if (dest == null || dest.isBlank()) {
      dest = route.id().value();
    }
    return TrainNameFormatter.buildTrainName(operator, line, pattern, dest, UUID.randomUUID());
  }

  /** 从 RouteDefinition 解析 RoutePatternType，查询数据库或回退默认值。 */
  /**
   * 从 RouteDefinition 解析 RoutePatternType。
   *
   * <p>当前简化实现：直接使用 LOCAL 作为默认值。 完整实现需要从 metadata 中解析 operator/line 并查询数据库， 但这会增加复杂度且 trainName 中的
   * pattern 主要用于人眼识别，不影响调度逻辑。
   */
  private RoutePatternType resolvePatternType(RouteDefinition route) {
    // 简化实现：从 route metadata 中无法直接获取 patternType，
    // 完整查询需要 operator->line->route 链路，这里回退到 LOCAL
    return RoutePatternType.LOCAL;
  }

  /** 终点站显示信息。 */
  private record DestinationDisplayInfo(String name, String code) {
    private DestinationDisplayInfo {
      name = name == null ? "" : name;
      code = code == null ? "" : code;
    }
  }

  /**
   * 节点历史：记录列车最近经过的节点序列。
   *
   * <p>用于回退检测：当列车触发一个"已在历史中且位置更靠前"的节点时，判定为异常回退。
   */
  private static final class NodeHistory {
    private final java.util.Deque<NodeId> nodes;
    private final int capacity;
    private volatile long lastRelaunchAtMs;

    NodeHistory(int capacity) {
      this.capacity = capacity;
      this.nodes = new java.util.concurrent.ConcurrentLinkedDeque<>();
      this.lastRelaunchAtMs = 0L;
    }

    /** 记录经过的节点。 */
    void record(NodeId node) {
      if (node == null) {
        return;
      }
      // 如果最近一个节点就是当前节点，跳过（去重）
      NodeId last = nodes.peekLast();
      if (last != null && last.equals(node)) {
        return;
      }
      nodes.addLast(node);
      while (nodes.size() > capacity) {
        nodes.pollFirst();
      }
    }

    /**
     * 检测是否发生回退：当前节点在历史中，且不是最后一个（即走回头路）。
     *
     * @return 回退位置（0=最旧，size-1=最新），-1 表示未回退
     */
    int detectRegression(NodeId node) {
      if (node == null || nodes.isEmpty()) {
        return -1;
      }
      // 最后一个节点不算回退（正常经过）
      NodeId last = nodes.peekLast();
      if (last != null && last.equals(node)) {
        return -1;
      }
      // 在历史中搜索
      int idx = 0;
      for (NodeId n : nodes) {
        if (n.equals(node)) {
          return idx;
        }
        idx++;
      }
      return -1;
    }

    /** 清除节点历史（保留 relaunch 时间戳）。 */
    void clear() {
      nodes.clear();
      // 注意：不重置 lastRelaunchAtMs，冷却时间应跨越 clear 生效
    }

    /** 获取最后一次 relaunch 时间戳。 */
    long lastRelaunchAtMs() {
      return lastRelaunchAtMs;
    }

    /** 记录 relaunch 时间戳。 */
    void recordRelaunch() {
      this.lastRelaunchAtMs = System.currentTimeMillis();
    }

    /** 获取历史中的最后 N 个节点（用于诊断）。 */
    java.util.List<NodeId> snapshot() {
      return java.util.List.copyOf(nodes);
    }
  }
}
