package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.cache.EtaCache;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.ApproachingConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.ArrivingClassifier;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.DwellModel;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.DynamicTravelTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.PathProgressModel;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.SpawnTrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.TravelTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainSnapshotStore;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeStopState;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnForecastSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignTextParser;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * ETA 服务（唯一入口）。
 *
 * <p>职责：为 HUD/内部占位符提供结构化 ETA。
 *
 * <p>数据来源：
 *
 * <ul>
 *   <li>运行时列车快照：{@link TrainSnapshotStore}
 *   <li>未发车票据：{@link SpawnManager}/{@link TicketAssigner} 的队列快照
 * </ul>
 *
 * <p>约束：EtaService 不做运行时采样；只读 Snapshot + Graph/Route/Occupancy 快照 + Cache。
 *
 * <p>注意：站牌会合并未出票服务预测（见 {@link SpawnForecastSupport}）；若 SpawnMonitor 未运行或计划为空，站牌仍可能为空。
 *
 * <p>Layover：若起点存在待命列车，未发车 ETA 会使用候选的 readyAt 修正最早发车时间。
 *
 * <h2>动态速度模型</h2>
 *
 * <p>ETA 计算使用动态速度模型，综合考虑：
 *
 * <ul>
 *   <li>边限速（{@code RailEdge.baseSpeedLimit}）
 *   <li>列车加减速参数（运行中列车从快照获取，未发车从 depot 配置推断）
 *   <li>当前速度（若快照有采样）
 * </ul>
 *
 * @see DynamicTravelTimeModel
 * @see SpawnTrainConfigResolver
 */
public final class EtaService {

  private static final int FORECAST_LIMIT_PER_SERVICE = 5;

  /** 默认速度（blocks/s），当边无限速配置时使用。 */
  private static final double DEFAULT_FALLBACK_SPEED_BPS = 6.0;

  /**
   * 未知时长扣停的估算上限（秒）。
   *
   * <p>扣停时长重尾（实服第二十七轮 PROTECTIVE_RETAIN_HOLD 中位 2 秒、p90 12 秒，个别死锁上千秒）， 按“已扣多久就估计还要多久”顺延：扣得越久 ETA
   * 越往后推，解除后立刻回落。上限防止一次长时间死锁把站牌推到半小时后。
   */
  static final long HOLD_ESTIMATE_CAP_SEC = 300L;

  /**
   * 停站计时结束后、正常关门与过发车门控的用时（秒）；超出才算停站超时（扣停）。
   *
   * <p>实服第二十七轮：停站计时结束到实际发车通常在 5 秒内（例：13:33:03 开始 20 秒停站，13:33:23 发车）。
   */
  static final long STATION_DEPARTURE_OVERHEAD_SEC = 5L;

  private final TrainSnapshotStore snapshotStore;
  private final RailGraphService railGraphService;
  private final RouteDefinitionCache routeDefinitions;

  private final PathProgressModel pathProgressModel = new PathProgressModel();
  private final DwellModel dwellModel = new DwellModel();
  private final ArrivingClassifier arrivingClassifier = new ArrivingClassifier();

  /** 运行时真实停车状态（列车名 → 当前 STOP 生命周期）；未接入时视为无扣停。 */
  private volatile java.util.function.Function<String, Optional<RuntimeStopState>> stopStates;

  /** 时刻表计划发车（列车名, 停靠序号 → 计划发车时刻）；未接入或未按表运行时为空。 */
  private volatile java.util.function.BiFunction<String, Integer, Optional<Instant>>
      plannedDepartures;

  /** 与 StationStopCoordinator 同口径的计划扣留上限；早于计划超过它的不会被扣留。 */
  private volatile Duration plannedHoldCap = Duration.ZERO;

  private volatile java.util.function.Consumer<String> debugLogger = message -> {};

  /** 动态旅行时间模型（考虑边限速、加减速与 approaching 限速）。 */
  private volatile DynamicTravelTimeModel dynamicTravelTimeModel;

  /** 适配层，将 DynamicTravelTimeModel 包装为 TravelTimeModel 供现有逻辑使用。 */
  private volatile TravelTimeModel travelTimeModel;

  private final EtaCache<String, EtaResult> trainCache = new EtaCache<>(Duration.ofMillis(800));
  private final EtaCache<String, EtaResult> ticketCache = new EtaCache<>(Duration.ofMillis(1200));
  private final EtaCache<String, BoardResult> boardCache = new EtaCache<>(Duration.ofMillis(1500));

  private volatile SpawnManager spawnManager;
  private volatile java.util.function.Supplier<List<SpawnTicket>> pendingTicketSupplier;
  private volatile LayoverRegistry layoverRegistry;
  private volatile StorageProvider storageProvider;
  private volatile SignNodeRegistry signNodeRegistry;
  private volatile ConfigManager.ConfigView configView;

  public EtaService(
      TrainSnapshotStore snapshotStore,
      RailGraphService railGraphService,
      RouteDefinitionCache routeDefinitions) {
    // ETA 不用占用预判估算等待，改读运行时真实停车状态（见 attachRuntimeStopStates）。
    this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
    this.railGraphService = Objects.requireNonNull(railGraphService, "railGraphService");
    this.routeDefinitions = Objects.requireNonNull(routeDefinitions, "routeDefinitions");

    // 初始化动态旅行时间模型（使用默认加减速参数）
    this.dynamicTravelTimeModel =
        new DynamicTravelTimeModel(
            DynamicTravelTimeModel.TrainMotionParams.defaults(), DEFAULT_FALLBACK_SPEED_BPS);
    this.travelTimeModel = new TravelTimeModel(dynamicTravelTimeModel);
  }

  /** 接入调试日志（读取外部状态失败时留痕）。 */
  public void attachDebugLogger(java.util.function.Consumer<String> logger) {
    this.debugLogger = logger == null ? message -> {} : logger;
  }

  /**
   * 接入运行时真实停车状态，ETA 据此判断列车是否被扣停、扣了多久。
   *
   * @param stopStates 列车名 → 当前 STOP 生命周期；传 null 表示断开
   */
  public void attachRuntimeStopStates(
      java.util.function.Function<String, Optional<RuntimeStopState>> stopStates) {
    this.stopStates = stopStates;
  }

  /**
   * 接入时刻表计划发车，早到列车在站内等点的时间计入 ETA。
   *
   * @param plannedDepartures (列车名, 停靠序号) → 计划发车时刻；传 null 表示断开
   * @param holdCap 计划扣留上限，与 StationStopCoordinator 同口径；非正值表示不扣留
   */
  public void attachPlannedDepartures(
      java.util.function.BiFunction<String, Integer, Optional<Instant>> plannedDepartures,
      Duration holdCap) {
    this.plannedDepartures = plannedDepartures;
    this.plannedHoldCap =
        holdCap == null || holdCap.isNegative() || holdCap.isZero() ? Duration.ZERO : holdCap;
  }

  /** 绑定票据来源（SpawnManager/TicketAssigner），用于未发车 ETA。 */
  public void attachTicketSources(SpawnManager spawnManager, TicketAssigner ticketAssigner) {
    this.spawnManager = spawnManager;
    this.pendingTicketSupplier =
        ticketAssigner != null ? ticketAssigner::snapshotPendingTickets : null;
  }

  /** 绑定 LayoverRegistry，用于未发车 ETA 的待命时间修正。 */
  public void attachLayoverRegistry(LayoverRegistry layoverRegistry) {
    this.layoverRegistry = layoverRegistry;
  }

  /** 绑定 StorageProvider，用于站牌终点站解析与名称辅助。 */
  public void attachStorageProvider(StorageProvider storageProvider) {
    this.storageProvider = storageProvider;
  }

  /**
   * 绑定 SignNodeRegistry 与配置，用于未发车列车的配置推断与 approaching 限速。
   *
   * <p>调用此方法后会重建动态旅行时间模型，加入 approaching 限速支持。
   *
   * @param signNodeRegistry 牌子注册表
   * @param configView 配置视图
   */
  public void attachConfigSources(
      SignNodeRegistry signNodeRegistry, ConfigManager.ConfigView configView) {
    this.signNodeRegistry = signNodeRegistry;
    this.configView = configView;

    // 重建动态模型，加入 approaching 限速
    rebuildTravelTimeModel();
  }

  /** 重建动态旅行时间模型（使用最新的 configView 和 signNodeRegistry）。 */
  private void rebuildTravelTimeModel() {
    ApproachingConfig approachingConfig = buildApproachingConfig();
    this.dynamicTravelTimeModel =
        new DynamicTravelTimeModel(
            DynamicTravelTimeModel.TrainMotionParams.defaults(),
            DEFAULT_FALLBACK_SPEED_BPS,
            approachingConfig);
    this.travelTimeModel = new TravelTimeModel(dynamicTravelTimeModel);
  }

  /** 根据当前配置构建 ApproachingConfig。 */
  private ApproachingConfig buildApproachingConfig() {
    ConfigManager.ConfigView config = this.configView;
    SignNodeRegistry registry = this.signNodeRegistry;

    if (config == null || registry == null) {
      return ApproachingConfig.disabled();
    }

    double stationSpeed = config.runtimeSettings().approachSpeedBps();
    double depotSpeed = config.runtimeSettings().approachDepotSpeedBps();

    return ApproachingConfig.of(
        stationSpeed,
        depotSpeed,
        nodeId -> isStationNode(registry, nodeId),
        nodeId -> isDepotNode(registry, nodeId));
  }

  /** 判断节点是否为站点。 */
  private static boolean isStationNode(SignNodeRegistry registry, NodeId nodeId) {
    if (registry == null || nodeId == null) {
      return false;
    }
    return registry
        .findByNodeId(nodeId, null)
        .map(SignNodeRegistry.SignNodeInfo::definition)
        .map(def -> def.nodeType() == NodeType.STATION)
        .orElseGet(
            () ->
                parseWaypointKind(nodeId).map(kind -> kind == WaypointKind.STATION).orElse(false));
  }

  /** 判断节点是否为车库。 */
  private static boolean isDepotNode(SignNodeRegistry registry, NodeId nodeId) {
    if (registry == null || nodeId == null) {
      return false;
    }
    return registry
        .findByNodeId(nodeId, null)
        .map(SignNodeRegistry.SignNodeInfo::definition)
        .map(def -> def.nodeType() == NodeType.DEPOT)
        .orElseGet(
            () -> parseWaypointKind(nodeId).map(kind -> kind == WaypointKind.DEPOT).orElse(false));
  }

  private static Optional<WaypointKind> parseWaypointKind(NodeId nodeId) {
    if (nodeId == null || nodeId.value() == null) {
      return Optional.empty();
    }
    return org.fetarute
        .fetaruteTCAddon
        .dispatcher
        .sign
        .SignTextParser
        .parseWaypointLike(nodeId.value(), NodeType.WAYPOINT)
        .flatMap(org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition::waypointMetadata)
        .map(meta -> meta.kind());
  }

  /**
   * 查询指定列车的 ETA。
   *
   * @param trainName 列车名（trainId）
   * @param target 目标
   */
  public EtaResult getForTrain(String trainName, EtaTarget target) {
    Instant now = Instant.now();
    String key = trainName + "|" + (target == null ? "null" : target);
    return trainCache
        .getIfFresh(key, now)
        .orElseGet(
            () -> {
              EtaResult r = computeForTrain(trainName, target, now);
              trainCache.put(key, r, now);
              return r;
            });
  }

  /**
   * 使指定列车的 ETA 缓存失效，下次 getForTrain 会重新计算。
   *
   * <p>适用于列车经过 waypoint 或信号确认等需要立即刷新 ETA 的场景。
   */
  public void invalidateTrainEta(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    trainCache.invalidateByPrefix(trainName + "|");
  }

  /**
   * 预排班/未发车 ETA。
   *
   * <p>说明：仅基于已生成的票据（pending queue），不会读取 SpawnPlan 本身。
   */
  public EtaResult getForTicket(String ticketId) {
    Instant now = Instant.now();
    String key = ticketId == null ? "null" : ticketId.trim();
    return ticketCache
        .getIfFresh(key, now)
        .orElseGet(
            () -> {
              EtaResult r = computeForTicket(ticketId, now);
              ticketCache.put(key, r, now);
              return r;
            });
  }

  /**
   * 站牌列表：基于运行中列车 + 未发车票据聚合输出。
   *
   * <p>注意：stationId 支持两种输入：
   *
   * <ul>
   *   <li><code>StationCode</code>
   *   <li><code>Operator:StationCode</code>
   * </ul>
   *
   * <p>限制：
   *
   * <ul>
   *   <li>站牌会合并已生成票据与未出票预测（若 SpawnManager 支持预测）。
   *   <li>仅保留 ETA 落在 horizon 窗口内的行，窗口外会被过滤。
   *   <li>站点匹配依赖 RouteDefinition 的节点序列，若站点未映射到图节点会被忽略。
   * </ul>
   */
  public BoardResult getBoard(String stationId, String lineId, Duration horizon) {
    Instant now = Instant.now();
    String key =
        (stationId == null ? "null" : stationId.trim())
            + "|"
            + (lineId == null ? "null" : lineId.trim())
            + "|"
            + (horizon == null ? "null" : horizon.toString());
    return boardCache
        .getIfFresh(key, now)
        .orElseGet(
            () -> {
              BoardResult r = computeBoard(stationId, lineId, horizon, now);
              boardCache.put(key, r, now);
              return r;
            });
  }

  /**
   * 站牌列表（推荐形式）：显式传入 operator + stationCode，避免同名站点冲突。
   *
   * @param operator 运营商 code（全局唯一）
   * @param stationCode 站点 code（可能跨 operator 重名）
   */
  public BoardResult getBoard(
      String operator, String stationCode, String lineId, Duration horizon) {
    String stationId =
        operator == null || operator.isBlank()
            ? stationCode
            : operator.trim() + ":" + (stationCode == null ? "" : stationCode.trim());
    return getBoard(stationId, lineId, horizon);
  }

  /** 查询运行时采样快照（用于调试输出）。 */
  public Optional<TrainRuntimeSnapshot> getRuntimeSnapshot(String trainName) {
    return snapshotStore.getSnapshot(trainName);
  }

  /** 获取当前采样到的列车名集合（用于补全）。 */
  public Set<String> snapshotTrainNames() {
    return Set.copyOf(snapshotStore.snapshot().keySet());
  }

  /**
   * 供 /fta eta 命令 tab 补全使用：返回可见的 ticketId 候选。
   *
   * <p>来源包含：
   *
   * <ul>
   *   <li>运行中列车已绑定的 FTA_TICKET_ID
   *   <li>SpawnManager/TicketAssigner 队列中的待发车票据
   * </ul>
   */
  public List<String> suggestTicketIds() {
    Set<String> out = new HashSet<>();
    for (var entry : snapshotStore.snapshot().entrySet()) {
      TrainRuntimeSnapshot snap = entry.getValue();
      if (snap == null) {
        continue;
      }
      snap.ticketId().ifPresent(out::add);
    }
    for (SpawnTicket ticket : collectPendingTickets()) {
      if (ticket != null && ticket.id() != null) {
        out.add(ticket.id().toString());
      }
    }
    return out.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
  }

  /** 供 /fta eta 命令 tab 补全使用：返回已知 lineId 候选（来自运行中列车与待发车票据）。 */
  public List<String> suggestLineIds() {
    Set<String> out = new HashSet<>();
    for (TrainRuntimeSnapshot snap : snapshotStore.snapshot().values()) {
      if (snap == null) {
        continue;
      }
      routeDefinitions
          .findById(snap.routeUuid())
          .flatMap(RouteDefinition::metadata)
          .map(RouteMetadata::lineId)
          .filter(id -> id != null && !id.isBlank())
          .ifPresent(out::add);
    }
    for (SpawnTicket ticket : collectPendingTickets()) {
      if (ticket == null || ticket.service() == null) {
        continue;
      }
      String line = ticket.service().lineCode();
      if (line != null && !line.isBlank()) {
        out.add(line);
      }
    }
    return out.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
  }

  private EtaResult computeForTrain(String trainName, EtaTarget target, Instant now) {
    if (trainName == null || trainName.isBlank()) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_VEHICLE));
    }
    Optional<TrainRuntimeSnapshot> snapOpt = snapshotStore.getSnapshot(trainName);
    if (snapOpt.isEmpty()) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_VEHICLE));
    }
    TrainRuntimeSnapshot snap = snapOpt.get();

    Optional<RouteDefinition> routeOpt = routeDefinitions.findById(snap.routeUuid());
    if (routeOpt.isEmpty()) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_ROUTE));
    }
    RouteDefinition route = routeOpt.get();

    RailGraph graph =
        railGraphService
            .getSnapshot(snap.worldId())
            .map(
                org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService.RailGraphSnapshot
                    ::graph)
            .orElse(null);
    if (graph == null) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_PATH));
    }

    Optional<TargetSelection> targetSelOpt =
        resolveTargetSelection(route, snap.routeIndex(), target);
    if (targetSelOpt.isEmpty()) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_TARGET));
    }
    TargetSelection targetSel = targetSelOpt.get();

    // 使用 lastPassedNodeId 从中间图节点开始计算剩余路径，优化 arriving 判定
    NodeId lastPassed = snap.lastPassedNodeId().orElse(null);
    Optional<PathProgressModel.PathProgress> progressOpt =
        pathProgressModel.remainingToNode(
            graph, route, snap.routeIndex(), targetSel.nodeId(), lastPassed);
    if (progressOpt.isEmpty()) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_PATH));
    }

    PathProgressModel.PathProgress progress =
        trimTraveled(progressOpt.get(), lastPassed, snap.traveledSinceLastPassedBlocks());
    // 使用当前速度作为初速以提高 ETA 精度
    OptionalDouble initialSpeed = snap.currentSpeedBps();
    TravelTimeModel effectiveTravelTimeModel = travelTimeModelForWorld(snap.worldId(), now);
    Optional<Integer> travelSecOpt =
        effectiveTravelTimeModel.estimateTravelSec(
            graph,
            progress.remainingNodes(),
            progress.remainingEdges(),
            initialSpeed,
            progress.firstEdgeRemainingBlocks());
    if (travelSecOpt.isEmpty()) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_PATH));
    }

    int travelSec = travelSecOpt.get();
    // 当前停车时间 + 中途站点停车时间
    int currentDwellSec = dwellModel.dwellSec(snap.dwellRemainingSec().orElse(null)).orElse(0);
    int intermediateDwellSec =
        computeIntermediateDwellSec(route, snap.routeIndex(), targetSel.nodeId());
    int dwellSec = currentDwellSec + intermediateDwellSec;

    // 等待只看运行时真实的停车状态：旧的占用预判（lookahead preview）与现行准入口径不一致，
    // 既会在车被扣住时报“无需等待”，也会在畅通时误报阻塞，让 HUD 在进站时丢掉“即将到站”。
    HoldEstimate hold = estimateHold(trainName, snap, currentDwellSec, now);
    int waitSec = hold.waitSec();
    List<EtaReason> reasons = new ArrayList<>(hold.reasons());

    int remainingEdgeCount = progress.remainingEdgeCount();
    // 若目标站点有咽喉，检查到咽喉的剩余边数，取较小值用于 arriving 判定
    Optional<Integer> throatEdgesOpt =
        remainingEdgesToThroat(graph, route, snap.routeIndex(), targetSel.nodeId(), lastPassed);
    int edgesForArriving =
        throatEdgesOpt.map(te -> Math.min(te, remainingEdgeCount)).orElse(remainingEdgeCount);
    ArrivingClassifier.Arriving arriving =
        arrivingClassifier.classify(edgesForArriving, hold.blocked());
    if (!isApproachTarget(targetSel.nodeId()) && arriving.arriving()) {
      arriving = new ArrivingClassifier.Arriving(false, EtaConfidence.LOW);
    }

    long etaMillis = now.plusSeconds((long) travelSec + dwellSec + waitSec).toEpochMilli();
    int minutesRounded =
        (int) Math.max(0L, Math.round(((long) travelSec + dwellSec + waitSec) / 60.0));

    String statusText = arriving.arriving() ? "Arriving" : minutesRounded + "m";
    if (hold.delaySec() >= 60L) {
      statusText = "Delayed " + (hold.delaySec() / 60L) + "m";
    }

    EtaConfidence conf = hold.blocked() ? EtaConfidence.LOW : arriving.confidence();
    return new EtaResult(
        arriving.arriving(),
        statusText,
        etaMillis,
        minutesRounded,
        travelSec,
        dwellSec,
        waitSec,
        reasons,
        conf);
  }

  /**
   * 从剩余路径前端扣掉“自上一节点起已行驶的距离”。
   *
   * <p>只在剩余路径确实从上一经过节点起算时才扣；否则两个量的起点不同，扣了反而错。最后一条边至少留 1 格， 到站由进度推进确认，不由里程估算。
   */
  static PathProgressModel.PathProgress trimTraveled(
      PathProgressModel.PathProgress progress, NodeId lastPassed, OptionalDouble traveled) {
    if (progress == null
        || lastPassed == null
        || traveled == null
        || traveled.isEmpty()
        || progress.remainingEdges().isEmpty()
        || progress.remainingNodes().isEmpty()
        || !lastPassed.equals(progress.remainingNodes().get(0))) {
      return progress;
    }
    List<NodeId> nodes = progress.remainingNodes();
    List<RailEdge> edges = progress.remainingEdges();
    double left = Math.max(0.0, traveled.getAsDouble());
    int drop = 0;
    while (drop < edges.size() - 1 && left >= edges.get(drop).lengthBlocks()) {
      left -= edges.get(drop).lengthBlocks();
      drop++;
    }
    double firstRemaining = Math.max(1.0, edges.get(drop).lengthBlocks() - left);
    return new PathProgressModel.PathProgress(
        nodes.subList(drop, nodes.size()),
        edges.subList(drop, edges.size()),
        OptionalDouble.of(firstRemaining));
  }

  /**
   * 列车当前是否被扣停（ETA 顺延与公开 API 扣停事件共用这一个判定）。
   *
   * <ul>
   *   <li>非例行停车（信号、占用、授权、尾保、安全状态不可用等）：开始时刻取采样器记下的连续扣停起点—— 运行时停车状态换原因或阻挡者时会整体替换、{@code enteredAt}
   *       随之重置，不能拿来量“已经扣了多久”。
   *   <li>例行停站/门控：停站计时结束后（按表早到则到计划发车后）超过 {@link #STATION_DEPARTURE_OVERHEAD_SEC}
   *       秒仍未发车，按停站超时算扣停，起点就是超时开始的那一刻。
   *   <li>折返待命、终点作业不算扣停。
   * </ul>
   *
   * @param trainName 列车名
   * @return 当前扣停；未被扣停时为空
   */
  public Optional<TrainHold> currentHold(String trainName) {
    return currentHold(trainName, Instant.now());
  }

  Optional<TrainHold> currentHold(String trainName, Instant now) {
    java.util.function.Function<String, Optional<RuntimeStopState>> source = this.stopStates;
    if (trainName == null || trainName.isBlank() || source == null) {
      return Optional.empty();
    }
    Optional<RuntimeStopState> stateOpt = safeStopState(source, trainName);
    if (stateOpt.isEmpty()) {
      return Optional.empty();
    }
    RuntimeStopState state = stateOpt.get();
    Optional<TrainRuntimeSnapshot> snap = snapshotStore.getSnapshot(trainName);
    if (!state.routineStop()) {
      Instant since = snap.flatMap(s -> s.holdTimeline().holdSince()).orElse(state.enteredAt());
      return Optional.of(
          new TrainHold(since, state.reasonCode(), state.detail(), state.blockers(), false));
    }
    if (state.releaseCondition() != RuntimeStopState.ReleaseCondition.PLANNED_STOP_COMPLETED
        || snap.isEmpty()
        || snap.get().dwellRemainingSec().filter(sec -> sec > 0).isPresent()) {
      return Optional.empty();
    }
    Optional<Instant> dwellEndedAt = snap.get().holdTimeline().dwellEndedAt();
    if (dwellEndedAt.isEmpty()) {
      return Optional.empty();
    }
    Instant due = dwellEndedAt.get();
    Optional<Instant> planned = plannedHoldUntil(trainName, snap.get().routeIndex(), due);
    if (planned.isPresent() && planned.get().isAfter(due)) {
      due = planned.get();
    }
    Instant since = due.plusSeconds(STATION_DEPARTURE_OVERHEAD_SEC);
    if (!now.isAfter(since)) {
      return Optional.empty();
    }
    return Optional.of(
        new TrainHold(since, state.reasonCode(), state.detail(), state.blockers(), true));
  }

  /**
   * 估算列车还要额外等多久才能继续走（不含停站计时本身）。
   *
   * <ul>
   *   <li>时刻表早到扣留：计划发车时刻已知，按实际剩余计入（与 StationStopCoordinator 同口径：早到超过上限不扣）。
   *   <li>扣停（见 {@link #currentHold}）：时长未知，按已扣时长顺延，上限 {@link #HOLD_ESTIMATE_CAP_SEC}。
   * </ul>
   */
  private HoldEstimate estimateHold(
      String trainName, TrainRuntimeSnapshot snap, int currentDwellSec, Instant now) {
    long plannedWait = plannedWaitSec(trainName, snap.routeIndex(), currentDwellSec, now);
    Optional<TrainHold> hold = currentHold(trainName, now);
    if (hold.isPresent()) {
      long delay = Math.max(0L, Duration.between(hold.get().since(), now).getSeconds());
      List<EtaReason> reasons = new ArrayList<>(blockerReasons(hold.get().blockers()));
      reasons.add(EtaReason.HOLD);
      long wait = Math.max(plannedWait, Math.min(delay, HOLD_ESTIMATE_CAP_SEC));
      return new HoldEstimate((int) wait, delay, true, reasons);
    }
    if (plannedWait > 0L) {
      return new HoldEstimate((int) plannedWait, 0L, false, List.of(EtaReason.WAIT));
    }
    return HoldEstimate.NONE;
  }

  /** 按表早到时会被扣留到的计划发车时刻（与 StationStopCoordinator 同口径：早到超过上限不扣）。 */
  private Optional<Instant> plannedHoldUntil(String trainName, int stopIndex, Instant checkedAt) {
    java.util.function.BiFunction<String, Integer, Optional<Instant>> source =
        this.plannedDepartures;
    Duration cap = this.plannedHoldCap;
    if (source == null || cap.isZero()) {
      return Optional.empty();
    }
    Optional<Instant> planned = safePlannedDeparture(source, trainName, stopIndex);
    if (planned.isEmpty() || !checkedAt.isBefore(planned.get())) {
      return Optional.empty();
    }
    return Duration.between(checkedAt, planned.get()).compareTo(cap) > 0
        ? Optional.empty()
        : planned;
  }

  private long plannedWaitSec(String trainName, int stopIndex, int currentDwellSec, Instant now) {
    java.util.function.BiFunction<String, Integer, Optional<Instant>> source =
        this.plannedDepartures;
    Duration cap = this.plannedHoldCap;
    if (source == null || cap.isZero()) {
      return 0L;
    }
    Optional<Instant> planned = safePlannedDeparture(source, trainName, stopIndex);
    if (planned.isEmpty() || !now.isBefore(planned.get())) {
      return 0L;
    }
    Duration early = Duration.between(now, planned.get());
    if (early.compareTo(cap) > 0) {
      return 0L;
    }
    // 停站计时与等点重叠：发车时刻取两者较晚者。
    return Math.max(0L, ceilSeconds(early) - Math.max(0, currentDwellSec));
  }

  /** 正向时长向上取整到秒，负向时长向零取整（超时秒数不夸大）。 */
  static long ceilSeconds(Duration duration) {
    long millis = duration.toMillis();
    return millis > 0L ? (millis + 999L) / 1000L : -((-millis) / 1000L);
  }

  private Optional<RuntimeStopState> safeStopState(
      java.util.function.Function<String, Optional<RuntimeStopState>> source, String trainName) {
    try {
      Optional<RuntimeStopState> state = source.apply(trainName);
      return state == null ? Optional.empty() : state;
    } catch (RuntimeException ex) {
      // ETA 是展示层：读取失败按“无扣停”处理，但必须留痕。
      debugLogger.accept("ETA_STOP_STATE_READ_FAILED train=" + trainName + " error=" + ex);
      return Optional.empty();
    }
  }

  private Optional<Instant> safePlannedDeparture(
      java.util.function.BiFunction<String, Integer, Optional<Instant>> source,
      String trainName,
      int stopIndex) {
    try {
      Optional<Instant> planned = source.apply(trainName, stopIndex);
      return planned == null ? Optional.empty() : planned;
    } catch (RuntimeException ex) {
      debugLogger.accept("ETA_PLANNED_DEPARTURE_READ_FAILED train=" + trainName + " error=" + ex);
      return Optional.empty();
    }
  }

  /** 按阻塞资源归类（与原净空模型同一套标签）。 */
  private static List<EtaReason> blockerReasons(List<RuntimeStopState.Blocker> blockers) {
    List<EtaReason> reasons = new ArrayList<>();
    for (RuntimeStopState.Blocker blocker : blockers) {
      String resource = blocker.resource();
      EtaReason reason = null;
      if (resource.startsWith("NODE:")) {
        reason = EtaReason.PLATFORM;
      } else if (resource.startsWith("CONFLICT:switcher:")) {
        reason = EtaReason.THROAT;
      } else if (resource.startsWith("CONFLICT:single:")) {
        reason = EtaReason.SINGLELINE;
      }
      if (reason != null && !reasons.contains(reason)) {
        reasons.add(reason);
      }
    }
    return reasons;
  }

  /**
   * 额外等待估算。
   *
   * @param waitSec 计入 ETA 的等待秒数
   * @param delaySec 已产生的延误秒数（用于“Delayed N m”），与 waitSec 不同：waitSec 有上限、delaySec 没有
   * @param blocked 列车是否正被扣停（扣停时不报“即将到站”）
   * @param reasons 诊断标签
   */
  private record HoldEstimate(
      int waitSec, long delaySec, boolean blocked, List<EtaReason> reasons) {
    private static final HoldEstimate NONE = new HoldEstimate(0, 0L, false, List.of());
  }

  private EtaResult computeForTicket(String ticketId, Instant now) {
    if (ticketId == null || ticketId.isBlank()) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_VEHICLE));
    }
    Optional<String> trainOpt = findTrainByTicketId(ticketId);
    if (trainOpt.isPresent()) {
      return computeForTrain(trainOpt.get(), EtaTarget.nextStop(), now);
    }
    Optional<SpawnTicket> ticketOpt = findSpawnTicket(ticketId);
    if (ticketOpt.isEmpty()) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_VEHICLE));
    }
    return computeForSpawnTicket(ticketOpt.get(), EtaTarget.nextStop(), now);
  }

  private BoardResult computeBoard(String stationId, String lineId, Duration horizon, Instant now) {
    if (stationId == null || stationId.isBlank()) {
      return new BoardResult(List.of());
    }
    Duration window = horizon == null ? Duration.ofMinutes(10) : horizon;
    Instant cutoff = now.plus(window);
    List<BoardRowEntry> rows = new ArrayList<>();
    Map<UUID, TerminalInfo> terminalCache = new HashMap<>();
    TerminalResolveContext terminalContext =
        new TerminalResolveContext(
            storageProvider, new HashMap<>(), new HashMap<>(), new HashMap<>());
    Set<UUID> returnTicketSeen = new HashSet<>();

    for (var entry : snapshotStore.snapshot().entrySet()) {
      String trainName = entry.getKey();
      TrainRuntimeSnapshot snap = entry.getValue();
      if (trainName == null || snap == null) {
        continue;
      }
      Optional<RouteDefinition> routeOpt = routeDefinitions.findById(snap.routeUuid());
      if (routeOpt.isEmpty()) {
        continue;
      }
      RouteDefinition route = routeOpt.get();
      if (!lineMatches(route, lineId)) {
        continue;
      }
      EtaTarget stationTarget = new EtaTarget.Station(stationId);
      Optional<TargetSelection> targetOpt =
          resolveTargetSelection(route, snap.routeIndex(), stationTarget);
      if (targetOpt.isEmpty()) {
        continue;
      }
      EtaResult result = computeForTrain(trainName, stationTarget, now);
      if (result.etaEpochMillis() <= 0L || result.etaEpochMillis() > cutoff.toEpochMilli()) {
        continue;
      }
      TargetSelection target = targetOpt.get();
      String lineName = resolveLineName(route);
      String routeId = route.id().value();
      TerminalInfo terminal =
          resolveTerminalInfo(route, snap.routeUuid(), terminalCache, terminalContext);
      DestinationInfo endRoute = terminal.endRoute();
      DestinationInfo endOperation =
          resolveRowEndOperation(route, snap.routeUuid(), target, terminal, terminalContext);
      DestinationInfo destInfo = resolveBoardDestination(route, endOperation);
      String platform = resolvePlatform(target.nodeId());
      rows.add(
          new BoardRowEntry(
              result.etaEpochMillis(),
              new BoardResult.BoardRow(
                  lineName,
                  routeId,
                  destInfo.label(),
                  destInfo.destinationId(),
                  endRoute.label(),
                  endRoute.destinationId(),
                  endOperation.label(),
                  endOperation.destinationId(),
                  platform,
                  result.statusText(),
                  result.reasons())));
    }

    List<SpawnTicket> pendingTickets = collectPendingTickets();
    Set<String> reservedSlots = new HashSet<>();
    for (SpawnTicket ticket : pendingTickets) {
      RouteDefinition route = resolveRouteDefinition(ticket).orElse(null);
      buildTicketSlotKey(ticket, route).ifPresent(reservedSlots::add);
    }

    for (SpawnTicket ticket : pendingTickets) {
      UUID routeUuid =
          ticket != null && ticket.service() != null ? ticket.service().routeId() : null;
      if (routeUuid != null && isReturnRoute(routeUuid, terminalContext)) {
        if (!returnTicketSeen.add(routeUuid)) {
          continue;
        }
      }
      buildBoardRowForTicket(ticket, stationId, lineId, now, cutoff, terminalCache, terminalContext)
          .ifPresent(rows::add);
    }

    SpawnManager manager = this.spawnManager;
    if (manager instanceof SpawnForecastSupport forecastSupport) {
      Set<String> seenSlots = new HashSet<>(reservedSlots);
      List<SpawnTicket> forecastTickets =
          forecastSupport.snapshotForecast(now, window, FORECAST_LIMIT_PER_SERVICE);
      for (SpawnTicket ticket : forecastTickets) {
        RouteDefinition route = resolveRouteDefinition(ticket).orElse(null);
        Optional<String> slotKeyOpt = buildTicketSlotKey(ticket, route);
        if (slotKeyOpt.isPresent() && !seenSlots.add(slotKeyOpt.get())) {
          continue;
        }
        UUID routeUuid =
            ticket != null && ticket.service() != null ? ticket.service().routeId() : null;
        if (routeUuid != null && isReturnRoute(routeUuid, terminalContext)) {
          if (!returnTicketSeen.add(routeUuid)) {
            continue;
          }
        }
        buildBoardRowForTicket(
                ticket, stationId, lineId, now, cutoff, terminalCache, terminalContext)
            .ifPresent(rows::add);
      }
    }

    rows.sort(Comparator.comparingLong(BoardRowEntry::etaMillis));
    List<BoardResult.BoardRow> out = new ArrayList<>();
    for (BoardRowEntry entry : rows) {
      out.add(entry.row());
    }
    return new BoardResult(out);
  }

  private Optional<BoardRowEntry> buildBoardRowForTicket(
      SpawnTicket ticket,
      String stationId,
      String lineId,
      Instant now,
      Instant cutoff,
      Map<UUID, TerminalInfo> terminalCache,
      TerminalResolveContext terminalContext) {
    if (ticket == null || ticket.service() == null) {
      return Optional.empty();
    }
    if (lineId != null
        && !lineId.isBlank()
        && !ticket.service().lineCode().equalsIgnoreCase(lineId)) {
      return Optional.empty();
    }
    Optional<RouteDefinition> routeOpt = resolveRouteDefinition(ticket);
    if (routeOpt.isEmpty()) {
      return Optional.empty();
    }
    RouteDefinition route = routeOpt.get();
    EtaTarget stationTarget = new EtaTarget.Station(stationId);
    Optional<TargetSelection> targetOpt = resolveTargetSelection(route, -1, stationTarget);
    if (targetOpt.isEmpty()) {
      return Optional.empty();
    }
    EtaResult result = computeForSpawnTicket(ticket, stationTarget, now);
    if (result.etaEpochMillis() <= 0L || result.etaEpochMillis() > cutoff.toEpochMilli()) {
      return Optional.empty();
    }
    TargetSelection target = targetOpt.get();
    String lineName = ticket.service().lineCode();
    String routeId = route.id().value();
    TerminalInfo terminal =
        resolveTerminalInfo(route, ticket.service().routeId(), terminalCache, terminalContext);
    DestinationInfo endRoute = terminal.endRoute();
    DestinationInfo endOperation =
        resolveRowEndOperation(
            route, ticket.service().routeId(), target, terminal, terminalContext);
    DestinationInfo destInfo = resolveBoardDestination(route, endOperation);
    String platform = resolvePlatform(target.nodeId());
    return Optional.of(
        new BoardRowEntry(
            result.etaEpochMillis(),
            new BoardResult.BoardRow(
                lineName,
                routeId,
                destInfo.label(),
                destInfo.destinationId(),
                endRoute.label(),
                endRoute.destinationId(),
                endOperation.label(),
                endOperation.destinationId(),
                platform,
                result.statusText(),
                result.reasons())));
  }

  private Optional<String> buildTicketSlotKey(SpawnTicket ticket, RouteDefinition route) {
    if (ticket == null || ticket.service() == null || ticket.service().key() == null) {
      return Optional.empty();
    }
    Instant departAt =
        route != null ? resolveTicketDepartTime(ticket, route) : resolveTicketDepartTime(ticket);
    if (departAt == null) {
      return Optional.empty();
    }
    return Optional.of(ticket.service().key().routeId() + "|" + departAt.toEpochMilli());
  }

  private Optional<RouteDefinition> resolveRouteDefinition(SpawnTicket ticket) {
    if (ticket == null || ticket.service() == null) {
      return Optional.empty();
    }
    return routeDefinitions.findById(ticket.service().routeId());
  }

  private Optional<String> findTrainByTicketId(String ticketId) {
    if (ticketId == null || ticketId.isBlank()) {
      return Optional.empty();
    }
    for (var entry : snapshotStore.snapshot().entrySet()) {
      TrainRuntimeSnapshot snap = entry.getValue();
      if (snap == null) {
        continue;
      }
      Optional<String> snapTicket = snap.ticketId();
      if (snapTicket.isPresent() && snapTicket.get().equalsIgnoreCase(ticketId)) {
        return Optional.of(entry.getKey());
      }
    }
    return Optional.empty();
  }

  private Optional<SpawnTicket> findSpawnTicket(String ticketId) {
    if (ticketId == null || ticketId.isBlank()) {
      return Optional.empty();
    }
    UUID id;
    try {
      id = UUID.fromString(ticketId.trim());
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
    for (SpawnTicket ticket : collectPendingTickets()) {
      if (ticket != null && id.equals(ticket.id())) {
        return Optional.of(ticket);
      }
    }
    return Optional.empty();
  }

  private List<SpawnTicket> collectPendingTickets() {
    Set<UUID> seen = new HashSet<>();
    List<SpawnTicket> out = new ArrayList<>();
    SpawnManager manager = this.spawnManager;
    if (manager != null) {
      for (SpawnTicket ticket : manager.snapshotQueue()) {
        if (ticket == null) {
          continue;
        }
        if (seen.add(ticket.id())) {
          out.add(ticket);
        }
      }
    }
    java.util.function.Supplier<List<SpawnTicket>> supplier = this.pendingTicketSupplier;
    if (supplier != null) {
      for (SpawnTicket ticket : supplier.get()) {
        if (ticket == null) {
          continue;
        }
        if (seen.add(ticket.id())) {
          out.add(ticket);
        }
      }
    }
    return out;
  }

  /** 未发车票据的 ETA（包内可见供测试直接指定目标）。 */
  EtaResult computeForSpawnTicket(SpawnTicket ticket, EtaTarget target, Instant now) {
    if (ticket == null || ticket.service() == null) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_VEHICLE));
    }
    Optional<RouteDefinition> routeOpt = routeDefinitions.findById(ticket.service().routeId());
    if (routeOpt.isEmpty()) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_ROUTE));
    }
    RouteDefinition route = routeOpt.get();
    Optional<TargetSelection> targetSelOpt = resolveTargetSelection(route, -1, target);
    if (targetSelOpt.isEmpty()) {
      return EtaResult.unavailable("N/A", List.of(EtaReason.NO_TARGET));
    }
    TargetSelection targetSel = targetSelOpt.get();
    int remainingEdgeCount = 0;
    int travelSec = 0;
    int dwellSec = 0;

    // 使用 Route 对应的动态模型（考虑 CRET depot 的列车配置）
    TravelTimeModel routeTravelTimeModel =
        resolveTravelTimeModelForRoute(ticket.service().routeId());

    if (targetSel.index() > 0) {
      Optional<RailGraph> graphOpt = resolveGraphForRouteSegment(route, 0, targetSel.index());
      if (graphOpt.isEmpty()) {
        return EtaResult.unavailable("N/A", List.of(EtaReason.NO_PATH));
      }
      RailGraph graph = graphOpt.get();
      Optional<PathProgressModel.PathProgress> progressOpt =
          pathProgressModel.remainingToNode(graph, route, 0, targetSel.nodeId());
      if (progressOpt.isEmpty()) {
        return EtaResult.unavailable("N/A", List.of(EtaReason.NO_PATH));
      }
      PathProgressModel.PathProgress progress = progressOpt.get();
      Optional<Integer> travelSecOpt =
          routeTravelTimeModel.estimateTravelSec(
              graph, progress.remainingNodes(), progress.remainingEdges());
      if (travelSecOpt.isEmpty()) {
        return EtaResult.unavailable("N/A", List.of(EtaReason.NO_PATH));
      }
      travelSec = travelSecOpt.get();
      remainingEdgeCount = progress.remainingEdgeCount();
      // 中途停站同样要停：运行中列车的 ETA 一直在加，票据这里漏了，站牌上离起点越远的班次越早。
      dwellSec = computeIntermediateDwellSec(route, 0, targetSel.nodeId());
    }

    Instant departAt = resolveTicketDepartTime(ticket, route);
    // 距计划发车向上取整：向下取整会让 ETA 系统性早于计划发车（最多差 1 秒）。
    long untilDepart = ceilSeconds(Duration.between(now, departAt));
    long overdueSec = untilDepart < 0L ? -untilDepart : 0L;
    // 已过计划发车时刻仍未发出：按已超时长顺延（上限同扣停），不能一直报“马上就到”。
    long waitSecLong = untilDepart > 0L ? untilDepart : Math.min(overdueSec, HOLD_ESTIMATE_CAP_SEC);
    int waitSec = waitSecLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) waitSecLong;
    List<EtaReason> reasons = new ArrayList<>();
    if (untilDepart > 0L) {
      reasons.add(EtaReason.WAIT);
    } else if (overdueSec > 0L) {
      reasons.add(EtaReason.OVERDUE);
    }

    // 若目标站点有咽喉，检查到咽喉的剩余边数用于 arriving 判定
    final int baseEdgeCount = remainingEdgeCount;
    int edgesForArriving = remainingEdgeCount;
    if (targetSel.index() > 0) {
      Optional<RailGraph> graphOptForThroat =
          resolveGraphForRouteSegment(route, 0, targetSel.index());
      if (graphOptForThroat.isPresent()) {
        Optional<Integer> throatEdgesOpt =
            remainingEdgesToThroat(graphOptForThroat.get(), route, 0, targetSel.nodeId(), null);
        edgesForArriving =
            throatEdgesOpt.map(te -> Math.min(te, baseEdgeCount)).orElse(baseEdgeCount);
      }
    }
    ArrivingClassifier.Arriving arriving = arrivingClassifier.classify(edgesForArriving, false);
    if (waitSec > 0 || !isApproachTarget(targetSel.nodeId())) {
      arriving = new ArrivingClassifier.Arriving(false, EtaConfidence.LOW);
    }

    long etaMillis = now.plusSeconds((long) travelSec + dwellSec + waitSec).toEpochMilli();
    int minutesRounded =
        (int) Math.max(0L, Math.round(((long) travelSec + dwellSec + waitSec) / 60.0));

    String statusText = minutesRounded + "m";
    if (overdueSec >= 60L) {
      statusText = "Delayed " + (overdueSec / 60L) + "m";
    } else if (untilDepart > 60L) {
      statusText = "Scheduled " + (untilDepart / 60L) + "m";
    }

    return new EtaResult(
        arriving.arriving(),
        statusText,
        etaMillis,
        minutesRounded,
        travelSec,
        dwellSec,
        waitSec,
        reasons,
        EtaConfidence.LOW);
  }

  private Optional<RailGraph> resolveGraphForRouteSegment(
      RouteDefinition route, int startIndex, int targetIndex) {
    if (route == null) {
      return Optional.empty();
    }
    List<NodeId> waypoints = route.waypoints();
    if (startIndex < 0 || targetIndex >= waypoints.size() || startIndex >= targetIndex) {
      return Optional.empty();
    }
    List<NodeId> segment = waypoints.subList(startIndex, targetIndex + 1);
    Optional<UUID> worldIdOpt = railGraphService.findWorldIdForPath(segment);
    if (worldIdOpt.isEmpty()) {
      return Optional.empty();
    }
    return railGraphService
        .getSnapshot(worldIdOpt.get())
        .map(
            org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService.RailGraphSnapshot
                ::graph);
  }

  private Instant resolveTicketDepartTime(SpawnTicket ticket) {
    if (ticket == null) {
      return Instant.EPOCH;
    }
    Instant due = ticket.dueAt();
    Instant notBefore = ticket.notBefore();
    if (notBefore != null && due != null && notBefore.isAfter(due)) {
      return notBefore;
    }
    return due != null ? due : Instant.EPOCH;
  }

  private Instant resolveTicketDepartTime(SpawnTicket ticket, RouteDefinition route) {
    Instant base = resolveTicketDepartTime(ticket);
    Optional<Instant> readyAt = resolveLayoverReadyAt(route);
    if (readyAt.isPresent() && readyAt.get().isAfter(base)) {
      return readyAt.get();
    }
    return base;
  }

  private Optional<Instant> resolveLayoverReadyAt(RouteDefinition route) {
    if (route == null || route.waypoints().isEmpty()) {
      return Optional.empty();
    }
    LayoverRegistry registry = this.layoverRegistry;
    if (registry == null) {
      return Optional.empty();
    }
    NodeId startNode = route.waypoints().get(0);
    if (startNode == null) {
      return Optional.empty();
    }
    List<LayoverRegistry.LayoverCandidate> candidates = registry.findCandidates(startNode.value());
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    Instant readyAt = candidates.get(0).readyAt();
    return readyAt == null ? Optional.empty() : Optional.of(readyAt);
  }

  private record TargetSelection(NodeId nodeId, int index) {
    private TargetSelection {
      Objects.requireNonNull(nodeId, "nodeId");
    }
  }

  /**
   * 解析 ETA 目标对应的图节点与 waypoint 索引。
   *
   * <p>针对 {@link EtaTarget.NextStop}，会查找下一个"实际停靠点"（{@code STOP}/{@code TERMINATE}）而非仅按 waypoint
   * 索引推进，避免 PASS 类型的区间点被误认为下一站导致 arriving 判定失败。
   *
   * @param route 线路定义
   * @param currentIndex 当前 waypoint 索引（-1 表示未进入线路）
   * @param target ETA 目标（nextStop/station/platformNode）
   * @return 目标节点与索引，若无法解析则返回 empty
   */
  private Optional<TargetSelection> resolveTargetSelection(
      RouteDefinition route, int currentIndex, EtaTarget target) {
    if (route == null) {
      return Optional.empty();
    }
    List<NodeId> waypoints = route.waypoints();
    if (waypoints.isEmpty()) {
      return Optional.empty();
    }
    int startIndex = Math.max(-1, currentIndex);
    if (target == null || target instanceof EtaTarget.NextStop) {
      // 查找下一个实际停靠点（STOP/TERMINATE），而非仅按 waypoint 索引推进
      return findNextActualStop(route, waypoints, startIndex);
    }
    if (target instanceof EtaTarget.PlatformNode pn) {
      return findNodeTarget(waypoints, startIndex + 1, pn.nodeId());
    }
    if (target instanceof EtaTarget.Station station) {
      return findStationTarget(waypoints, startIndex + 1, station.stationId(), route);
    }
    return Optional.empty();
  }

  /**
   * 查找下一个实际停靠点（STOP/TERMINATE）。
   *
   * <p>遍历 RouteStop 列表，跳过 PASS 类型，找到第一个 waypoint 索引 > currentIndex 的停靠点。 若 RouteStop 数据不可用，则回退为简单的
   * waypoint 索引 +1。
   *
   * @param route 线路定义
   * @param waypoints waypoint 列表
   * @param currentIndex 当前 waypoint 索引
   * @return 下一停靠点的 NodeId 与索引
   */
  private Optional<TargetSelection> findNextActualStop(
      RouteDefinition route, List<NodeId> waypoints, int currentIndex) {
    // 构建 NodeId -> waypoint 索引映射
    Map<NodeId, Integer> nodeIndexMap = new HashMap<>();
    for (int i = 0; i < waypoints.size(); i++) {
      NodeId node = waypoints.get(i);
      if (node != null) {
        nodeIndexMap.putIfAbsent(node, i); // 保留首次出现的索引
      }
    }

    // 获取 RouteStop 列表
    List<RouteStop> stops = routeDefinitions.listStops(route.id());
    if (stops.isEmpty()) {
      // 回退：无 stop 数据时使用简单索引推进
      int next = currentIndex + 1;
      if (next >= waypoints.size()) {
        return Optional.empty();
      }
      return Optional.of(new TargetSelection(waypoints.get(next), next));
    }

    // 找到下一个实际停靠点
    int bestIndex = Integer.MAX_VALUE;
    NodeId bestNode = null;

    for (RouteStop stop : stops) {
      if (stop == null) {
        continue;
      }
      // 跳过 PASS 类型
      if (stop.passType() == RouteStopPassType.PASS) {
        continue;
      }
      // 解析 stop 对应的 NodeId
      Optional<NodeId> nodeIdOpt = resolveRouteStopNodeId(stop);
      if (nodeIdOpt.isEmpty()) {
        continue;
      }
      NodeId nodeId = nodeIdOpt.get();
      Integer waypointIndex = nodeIndexMap.get(nodeId);
      if (waypointIndex == null) {
        continue;
      }
      // 选择 > currentIndex 且最小的索引
      if (waypointIndex > currentIndex && waypointIndex < bestIndex) {
        bestIndex = waypointIndex;
        bestNode = nodeId;
      }
    }

    if (bestNode != null) {
      return Optional.of(new TargetSelection(bestNode, bestIndex));
    }

    // 回退：若无匹配的停靠点，使用简单索引推进
    int next = currentIndex + 1;
    if (next >= waypoints.size()) {
      return Optional.empty();
    }
    return Optional.of(new TargetSelection(waypoints.get(next), next));
  }

  /**
   * 解析 RouteStop 对应的 NodeId。
   *
   * <p>优先使用 waypointNodeId，其次通过 stationId 查询 Station.graphNodeId。
   */
  private Optional<NodeId> resolveRouteStopNodeId(RouteStop stop) {
    if (stop == null) {
      return Optional.empty();
    }
    if (stop.waypointNodeId().isPresent()) {
      return Optional.of(NodeId.of(stop.waypointNodeId().get()));
    }
    if (stop.stationId().isEmpty()) {
      return Optional.empty();
    }
    StorageProvider provider = this.storageProvider;
    if (provider == null) {
      return Optional.empty();
    }
    return provider
        .stations()
        .findById(stop.stationId().get())
        .flatMap(org.fetarute.fetaruteTCAddon.company.model.Station::graphNodeId)
        .map(NodeId::of);
  }

  private Optional<TargetSelection> findNodeTarget(
      List<NodeId> waypoints, int startIndex, NodeId target) {
    if (target == null || waypoints == null) {
      return Optional.empty();
    }
    for (int i = Math.max(0, startIndex); i < waypoints.size(); i++) {
      NodeId node = waypoints.get(i);
      if (node != null && node.equals(target)) {
        return Optional.of(new TargetSelection(node, i));
      }
    }
    return Optional.empty();
  }

  private Optional<TargetSelection> findStationTarget(
      List<NodeId> waypoints, int startIndex, String stationId, RouteDefinition route) {
    if (stationId == null || stationId.isBlank() || waypoints == null) {
      return Optional.empty();
    }
    StationKey key = parseStationKey(stationId, route);
    Optional<TargetSelection> exact = findExactNode(waypoints, startIndex, stationId);
    if (exact.isPresent()) {
      return exact;
    }
    Optional<TargetSelection> station =
        findStationByKind(waypoints, startIndex, key, WaypointKind.STATION);
    if (station.isPresent()) {
      return station;
    }
    return findStationByKind(waypoints, startIndex, key, WaypointKind.STATION_THROAT);
  }

  private Optional<TargetSelection> findExactNode(
      List<NodeId> waypoints, int startIndex, String rawId) {
    for (int i = Math.max(0, startIndex); i < waypoints.size(); i++) {
      NodeId node = waypoints.get(i);
      if (node == null) {
        continue;
      }
      if (node.value().equalsIgnoreCase(rawId)) {
        return Optional.of(new TargetSelection(node, i));
      }
    }
    return Optional.empty();
  }

  private Optional<TargetSelection> findStationByKind(
      List<NodeId> waypoints, int startIndex, StationKey key, WaypointKind kind) {
    for (int i = Math.max(0, startIndex); i < waypoints.size(); i++) {
      NodeId node = waypoints.get(i);
      if (node == null) {
        continue;
      }
      Optional<WaypointMetadata> metaOpt = parseWaypointMetadata(node);
      if (metaOpt.isEmpty()) {
        continue;
      }
      WaypointMetadata meta = metaOpt.get();
      if (meta.kind() != kind) {
        continue;
      }
      if (!meta.originStation().equalsIgnoreCase(key.station())) {
        continue;
      }
      if (key.operator().isPresent() && !meta.operator().equalsIgnoreCase(key.operator().get())) {
        continue;
      }
      if (key.operator().isEmpty() && key.routeOperator().isPresent()) {
        if (!meta.operator().equalsIgnoreCase(key.routeOperator().get())) {
          continue;
        }
      }
      return Optional.of(new TargetSelection(node, i));
    }
    return Optional.empty();
  }

  private Optional<WaypointMetadata> parseWaypointMetadata(NodeId nodeId) {
    if (nodeId == null) {
      return Optional.empty();
    }
    return SignTextParser.parseWaypointLike(nodeId.value(), NodeType.WAYPOINT)
        .flatMap(SignNodeDefinition::waypointMetadata);
  }

  private boolean isApproachTarget(NodeId nodeId) {
    Optional<WaypointMetadata> metaOpt = parseWaypointMetadata(nodeId);
    if (metaOpt.isEmpty()) {
      return false;
    }
    WaypointKind kind = metaOpt.get().kind();
    return kind == WaypointKind.STATION
        || kind == WaypointKind.STATION_THROAT
        || kind == WaypointKind.DEPOT
        || kind == WaypointKind.DEPOT_THROAT;
  }

  private StationKey parseStationKey(String raw, RouteDefinition route) {
    String trimmed = raw == null ? "" : raw.trim();
    String operator = null;
    String station = trimmed;
    int idx = trimmed.indexOf(':');
    if (idx > 0 && idx < trimmed.length() - 1) {
      operator = trimmed.substring(0, idx).trim();
      station = trimmed.substring(idx + 1).trim();
    }
    if (operator != null && !operator.isBlank()) {
      return new StationKey(Optional.of(operator), station, Optional.empty());
    }
    Optional<String> routeOp = resolveRouteOperator(route);
    return new StationKey(Optional.empty(), station, routeOp);
  }

  private Optional<String> resolveRouteOperator(RouteDefinition route) {
    if (route == null) {
      return Optional.empty();
    }
    Optional<RouteMetadata> metaOpt = route.metadata();
    if (metaOpt.isPresent() && metaOpt.get().operator() != null) {
      String op = metaOpt.get().operator();
      if (!op.isBlank()) {
        return Optional.of(op);
      }
    }
    return parseRouteId(route.id()).map(RouteCodeParts::operator);
  }

  private record StationKey(
      Optional<String> operator, String station, Optional<String> routeOperator) {
    private StationKey {
      operator = operator == null ? Optional.empty() : operator;
      routeOperator = routeOperator == null ? Optional.empty() : routeOperator;
    }
  }

  private boolean lineMatches(RouteDefinition route, String lineId) {
    if (lineId == null || lineId.isBlank()) {
      return true;
    }
    String expected = lineId.trim();
    if (route == null) {
      return false;
    }
    Optional<RouteMetadata> metaOpt = route.metadata();
    if (metaOpt.isPresent() && metaOpt.get().lineId() != null) {
      return metaOpt.get().lineId().equalsIgnoreCase(expected);
    }
    return parseRouteId(route.id())
        .map(parts -> parts.line().equalsIgnoreCase(expected))
        .orElse(false);
  }

  private String resolveLineName(RouteDefinition route) {
    if (route == null) {
      return "-";
    }
    Optional<RouteMetadata> metaOpt = route.metadata();
    if (metaOpt.isPresent() && metaOpt.get().lineId() != null) {
      return metaOpt.get().lineId();
    }
    return parseRouteId(route.id()).map(RouteCodeParts::line).orElse(route.id().value());
  }

  /**
   * 站牌主目的地（“开往 X”）：本行的运营终点（已按“先站名后回库”判定）。
   *
   * <p>不能用线路终点：EOR 是交路最后一个节点，回库交路会显示成车库，折返交路会显示成折返线。
   */
  private DestinationInfo resolveBoardDestination(
      RouteDefinition route, DestinationInfo rowEndOperation) {
    DestinationInfo base = rowEndOperation != null ? rowEndOperation : DestinationInfo.empty();
    if (route == null) {
      return base;
    }
    Optional<RouteMetadata> metaOpt = route.metadata();
    if (metaOpt.isPresent()) {
      Optional<String> displayNameOpt = metaOpt.get().displayName();
      if (displayNameOpt.isPresent() && !displayNameOpt.get().isBlank()) {
        return new DestinationInfo(displayNameOpt.get(), base.destinationId());
      }
    }
    if (!base.isBlank()) {
      return base;
    }
    return resolveDestinationInfo(route);
  }

  private String resolveDestination(RouteDefinition route) {
    if (route == null || route.waypoints().isEmpty()) {
      return "-";
    }
    Optional<RouteMetadata> metaOpt = route.metadata();
    if (metaOpt.isPresent() && metaOpt.get().displayName().isPresent()) {
      return metaOpt.get().displayName().get();
    }
    Optional<RouteCodeParts> partsOpt = parseRouteId(route.id());
    if (partsOpt.isPresent()) {
      return partsOpt.get().route();
    }
    NodeId last = route.waypoints().get(route.waypoints().size() - 1);
    Optional<WaypointMetadata> meta = parseWaypointMetadata(last);
    if (meta.isPresent()) {
      return meta.get().originStation();
    }
    return last.value();
  }

  private DestinationInfo resolveDestinationInfo(RouteDefinition route) {
    String label = resolveDestination(route);
    Optional<String> destinationId = resolveDestinationId(route);
    return new DestinationInfo(label, destinationId);
  }

  private TerminalInfo resolveTerminalInfo(
      RouteDefinition route,
      UUID routeUuid,
      Map<UUID, TerminalInfo> terminalCache,
      TerminalResolveContext terminalContext) {
    if (route == null || route.id() == null || route.id().value() == null) {
      return TerminalInfo.empty();
    }
    if (terminalCache != null && routeUuid != null) {
      TerminalInfo cached = terminalCache.get(routeUuid);
      if (cached != null) {
        return cached;
      }
    }
    DestinationInfo endRoute = resolveEndRoute(route, terminalContext);
    DestinationInfo endOperation = resolveEndOperation(route, terminalContext);
    TerminalInfo terminal = new TerminalInfo(endRoute, endOperation);
    if (terminalCache != null && routeUuid != null) {
      terminalCache.put(routeUuid, terminal);
    }
    return terminal;
  }

  /**
   * 站牌行的运营终点：列车到达本站时若已越过运营终点（回库车通过本站），显示“回库”；否则显示终点站名。
   *
   * <p>按目标站下标而不是列车当前下标判断——站牌描述的是“车到这一站时”的状态。口径见 {@link RouteTerminals#outOfService}。
   */
  private DestinationInfo resolveRowEndOperation(
      RouteDefinition route,
      UUID routeUuid,
      TargetSelection target,
      TerminalInfo terminal,
      TerminalResolveContext context) {
    if (route == null || target == null || terminal == null) {
      return terminal == null ? DestinationInfo.empty() : terminal.endOperation();
    }
    RouteOperationType operationType = resolveOperationType(routeUuid, context).orElse(null);
    if (RouteTerminals.outOfService(
        operationType, routeDefinitions.listStops(route.id()), target.index())) {
      return new DestinationInfo(
          RouteTerminals.OUT_OF_SERVICE_LABEL, Optional.of(RouteTerminals.OUT_OF_SERVICE_ID));
    }
    return terminal.endOperation();
  }

  /**
   * 线路终点：交路最后一个节点（常为车库或折返线），见 {@link RouteTerminals#endOfRouteIndex}。
   *
   * <p>显示与 HUD 同一规则：车库显示为「LWN Depot」（ID 为 {@code OP:D:LWN}，与同代码车站区分）； 折返线、区间点显示它之前最近的车站（见 {@link
   * RouteTerminals#endOfRouteLabelIndex}）。
   */
  private DestinationInfo resolveEndRoute(RouteDefinition route, TerminalResolveContext context) {
    if (route == null || route.id() == null) {
      return DestinationInfo.empty();
    }
    List<RouteStop> stops = routeDefinitions.listStops(route.id());
    if (!stops.isEmpty()) {
      Optional<RouteTerminals.StationRef> depot =
          RouteTerminals.depotRef(stops.get(stops.size() - 1));
      if (depot.isPresent()) {
        String code = depot.get().stationCode();
        return new DestinationInfo(
            RouteTerminals.depotCodeLabel(code),
            Optional.of(depot.get().operatorCode() + ":D:" + code));
      }
    }
    Optional<DestinationInfo> station =
        resolveStopDestination(route, RouteTerminals::endOfRouteLabelIndex, context);
    if (station.isPresent()) {
      return station.get();
    }
    if (route.waypoints().isEmpty()) {
      return DestinationInfo.empty();
    }
    NodeId last = route.waypoints().get(route.waypoints().size() - 1);
    return new DestinationInfo(last.value(), Optional.of(last.value()));
  }

  /** 运营终点；没有载客车站时为空（不拿线路终点顶替，站牌 API 调用方要能分辨这种情况）。 */
  private DestinationInfo resolveEndOperation(
      RouteDefinition route, TerminalResolveContext context) {
    return resolveStopDestination(route, RouteTerminals::endOfOperationIndex, context)
        .orElse(DestinationInfo.empty());
  }

  /** 用 {@link RouteTerminals} 挑出终点 stop，再按站牌口径解析显示。 */
  private Optional<DestinationInfo> resolveStopDestination(
      RouteDefinition route,
      java.util.function.Function<List<RouteStop>, java.util.OptionalInt> selector,
      TerminalResolveContext context) {
    if (route == null || route.id() == null) {
      return Optional.empty();
    }
    List<RouteStop> stops = routeDefinitions.listStops(route.id());
    java.util.OptionalInt index = selector.apply(stops);
    if (index.isEmpty() || !RouteTerminals.isStationStop(stops.get(index.getAsInt()))) {
      return Optional.empty();
    }
    return resolveStationDestination(stops.get(index.getAsInt()), context);
  }

  private Optional<DestinationInfo> resolveStationDestination(
      RouteStop stop, TerminalResolveContext context) {
    if (stop == null) {
      return Optional.empty();
    }
    // 1. 优先通过 stationId 查询
    if (stop.stationId().isPresent()) {
      return resolveStationDestination(stop.stationId().get(), context);
    }
    // 2. 通过 waypointNodeId 解析
    if (stop.waypointNodeId().isPresent()) {
      return resolveStationDestination(NodeId.of(stop.waypointNodeId().get()));
    }
    // 3. 尝试从 DYNAMIC spec 生成 placeholder nodeId 并解析
    Optional<DynamicStopMatcher.DynamicSpec> dynamicSpec =
        DynamicStopMatcher.parseDynamicSpec(stop);
    if (dynamicSpec.isPresent()) {
      DynamicStopMatcher.DynamicSpec spec = dynamicSpec.get();
      String displayName = spec.nodeName();
      String stationKey = spec.operatorCode() + ":" + spec.nodeName();
      return Optional.of(new DestinationInfo(displayName, Optional.of(stationKey)));
    }
    return Optional.empty();
  }

  private Optional<DestinationInfo> resolveStationDestination(NodeId nodeId) {
    if (nodeId == null) {
      return Optional.empty();
    }
    Optional<WaypointMetadata> metaOpt = parseWaypointMetadata(nodeId);
    if (metaOpt.isEmpty()) {
      return Optional.empty();
    }
    WaypointMetadata meta = metaOpt.get();
    if (meta.kind() != WaypointKind.STATION) {
      return Optional.empty();
    }
    String operator = meta.operator();
    String station = meta.originStation();
    if (operator == null || operator.isBlank() || station == null || station.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new DestinationInfo(station, Optional.of(operator + ":" + station)));
  }

  private Optional<DestinationInfo> resolveStationDestination(
      UUID stationId, TerminalResolveContext context) {
    if (stationId == null || context == null) {
      return Optional.empty();
    }
    Map<UUID, Optional<DestinationInfo>> stationCache = context.stationCache();
    if (stationCache.containsKey(stationId)) {
      return stationCache.get(stationId);
    }
    StorageProvider provider = context.provider();
    Optional<DestinationInfo> result = Optional.empty();
    if (provider != null) {
      Optional<Station> stationOpt = provider.stations().findById(stationId);
      if (stationOpt.isPresent()) {
        result = resolveStationDestination(stationOpt.get(), context);
      }
    }
    stationCache.put(stationId, result);
    return result;
  }

  private Optional<DestinationInfo> resolveStationDestination(
      Station station, TerminalResolveContext context) {
    if (station == null) {
      return Optional.empty();
    }
    if (station.graphNodeId().isPresent()) {
      Optional<DestinationInfo> infoOpt =
          resolveStationDestination(NodeId.of(station.graphNodeId().get()));
      if (infoOpt.isPresent()) {
        return infoOpt;
      }
    }
    String stationCode = station.code();
    if (stationCode == null || stationCode.isBlank() || context == null) {
      return Optional.empty();
    }
    Optional<String> operatorOpt = resolveOperatorCode(station.operatorId(), context);
    if (operatorOpt.isEmpty()) {
      return Optional.empty();
    }
    String operator = operatorOpt.get();
    if (operator.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new DestinationInfo(stationCode, Optional.of(operator + ":" + stationCode)));
  }

  private Optional<String> resolveOperatorCode(UUID operatorId, TerminalResolveContext context) {
    if (operatorId == null || context == null) {
      return Optional.empty();
    }
    Map<UUID, Optional<String>> operatorCache = context.operatorCache();
    if (operatorCache.containsKey(operatorId)) {
      return operatorCache.get(operatorId);
    }
    StorageProvider provider = context.provider();
    Optional<String> result = Optional.empty();
    if (provider != null) {
      result = provider.operators().findById(operatorId).map(Operator::code);
      if (result.isPresent() && result.get() != null) {
        String trimmed = result.get().trim();
        result = trimmed.isBlank() ? Optional.empty() : Optional.of(trimmed);
      }
    }
    operatorCache.put(operatorId, result);
    return result;
  }

  private boolean isReturnRoute(UUID routeUuid, TerminalResolveContext context) {
    return resolveOperationType(routeUuid, context)
        .map(type -> type == RouteOperationType.RETURN)
        .orElse(false);
  }

  private Optional<RouteOperationType> resolveOperationType(
      UUID routeUuid, TerminalResolveContext context) {
    if (routeUuid == null || context == null) {
      return Optional.empty();
    }
    Map<UUID, Optional<RouteOperationType>> cache = context.routeOperationCache();
    if (cache.containsKey(routeUuid)) {
      return cache.get(routeUuid);
    }
    StorageProvider provider = context.provider();
    Optional<RouteOperationType> result = Optional.empty();
    if (provider != null) {
      result = provider.routes().findById(routeUuid).map(r -> r.operationType());
    }
    cache.put(routeUuid, result);
    return result;
  }

  private Optional<String> resolveDestinationId(RouteDefinition route) {
    if (route == null || route.waypoints().isEmpty()) {
      return Optional.empty();
    }
    NodeId last = route.waypoints().get(route.waypoints().size() - 1);
    Optional<WaypointMetadata> metaOpt = parseWaypointMetadata(last);
    if (metaOpt.isEmpty()) {
      return Optional.empty();
    }
    WaypointMetadata meta = metaOpt.get();
    String operator = meta.operator();
    if (operator == null || operator.isBlank()) {
      return Optional.empty();
    }
    String stationCode;
    if (meta.kind() == WaypointKind.INTERVAL && meta.destinationStation().isPresent()) {
      stationCode = meta.destinationStation().get();
    } else {
      stationCode = meta.originStation();
    }
    if (stationCode == null || stationCode.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(operator + ":" + stationCode);
  }

  private String resolvePlatform(NodeId nodeId) {
    if (nodeId == null) {
      return "-";
    }
    Optional<WaypointMetadata> metaOpt = parseWaypointMetadata(nodeId);
    if (metaOpt.isPresent()) {
      return String.valueOf(metaOpt.get().trackNumber());
    }
    return "-";
  }

  private Optional<RouteCodeParts> parseRouteId(RouteId routeId) {
    if (routeId == null || routeId.value() == null) {
      return Optional.empty();
    }
    String[] parts = routeId.value().split(":");
    if (parts.length < 3) {
      return Optional.empty();
    }
    return Optional.of(new RouteCodeParts(parts[0].trim(), parts[1].trim(), parts[2].trim()));
  }

  private record RouteCodeParts(String operator, String line, String route) {}

  private record BoardRowEntry(long etaMillis, BoardResult.BoardRow row) {}

  private record TerminalInfo(DestinationInfo endRoute, DestinationInfo endOperation) {
    private TerminalInfo {
      endRoute = endRoute == null ? DestinationInfo.empty() : endRoute;
      endOperation = endOperation == null ? DestinationInfo.empty() : endOperation;
    }

    private static TerminalInfo empty() {
      DestinationInfo empty = DestinationInfo.empty();
      return new TerminalInfo(empty, empty);
    }
  }

  private record TerminalResolveContext(
      StorageProvider provider,
      Map<UUID, Optional<DestinationInfo>> stationCache,
      Map<UUID, Optional<String>> operatorCache,
      Map<UUID, Optional<RouteOperationType>> routeOperationCache) {
    private TerminalResolveContext {
      stationCache = stationCache == null ? new HashMap<>() : stationCache;
      operatorCache = operatorCache == null ? new HashMap<>() : operatorCache;
      routeOperationCache = routeOperationCache == null ? new HashMap<>() : routeOperationCache;
    }
  }

  private record DestinationInfo(String label, Optional<String> destinationId) {
    private DestinationInfo {
      label = label == null || label.isBlank() ? "-" : label;
      destinationId = destinationId == null ? Optional.empty() : destinationId;
    }

    private static DestinationInfo empty() {
      return new DestinationInfo("-", Optional.empty());
    }

    private boolean isBlank() {
      return "-".equals(label) && destinationId.isEmpty();
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // 动态旅行时间模型辅助
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * 根据 Route 的 CRET depot 配置创建动态旅行时间模型（用于未发车 ETA）。
   *
   * <p>解析优先级：
   *
   * <ol>
   *   <li>从 CRET depot spawn pattern 推断列车类型
   *   <li>根据列车类型获取加减速配置
   *   <li>fallback 到默认配置
   * </ol>
   *
   * @param routeUuid Route UUID
   * @return 适用于该 Route 的 TravelTimeModel
   */
  TravelTimeModel resolveTravelTimeModelForRoute(UUID routeUuid) {
    if (routeUuid == null || signNodeRegistry == null || configView == null) {
      return travelTimeModel;
    }
    StorageProvider provider = this.storageProvider;
    if (provider == null) {
      return travelTimeModel;
    }

    Optional<Route> routeOpt = provider.routes().findById(routeUuid);
    if (routeOpt.isEmpty()) {
      return travelTimeModel;
    }
    Route route = routeOpt.get();
    List<RouteStop> stops = provider.routeStops().listByRoute(routeUuid);

    SpawnTrainConfigResolver configResolver =
        new SpawnTrainConfigResolver(signNodeRegistry, configView);
    TrainConfig trainConfig = configResolver.resolveForRoute(route, stops);

    DynamicTravelTimeModel.TrainMotionParams params =
        new DynamicTravelTimeModel.TrainMotionParams(
            trainConfig.accelBps2(), trainConfig.decelBps2());
    DynamicTravelTimeModel dynamicModel =
        new DynamicTravelTimeModel(params, DEFAULT_FALLBACK_SPEED_BPS);
    return new TravelTimeModel(dynamicModel);
  }

  /**
   * 构建与运行时一致的边限速 ETA 模型。
   *
   * <p>运行时控车会合并图基础限速、永久 override 与临时限速；ETA 在有 worldId 时复用同一解析入口，避免 section speed 生效后仍按 base speed
   * 估算。
   */
  private TravelTimeModel travelTimeModelForWorld(UUID worldId, Instant now) {
    if (worldId == null) {
      return travelTimeModel;
    }
    ApproachingConfig approachingConfig = buildApproachingConfig();
    // 无限速边的默认速度必须与控车同一个配置值，否则同一条边 ETA 与实际按两个速度走。
    ConfigManager.ConfigView config = this.configView;
    double defaultSpeed =
        config != null && config.graphSettings().defaultSpeedBlocksPerSecond() > 0.0
            ? config.graphSettings().defaultSpeedBlocksPerSecond()
            : DEFAULT_FALLBACK_SPEED_BPS;
    DynamicTravelTimeModel model =
        new DynamicTravelTimeModel(
            DynamicTravelTimeModel.TrainMotionParams.defaults(),
            defaultSpeed,
            approachingConfig,
            (graph, edge, fallbackSpeed) ->
                railGraphService.effectiveSpeedLimitBlocksPerSecond(
                    worldId, edge, now != null ? now : Instant.now(), fallbackSpeed));
    return new TravelTimeModel(model);
  }

  /** 获取动态旅行时间模型（用于诊断/测试）。 */
  public DynamicTravelTimeModel getDynamicTravelTimeModel() {
    return dynamicTravelTimeModel;
  }

  /**
   * 查找目标节点对应的站咽喉节点列表。
   *
   * <p>仅当目标是 STATION/DEPOT 类型时才会查找；若目标本身就是咽喉则返回空列表。
   *
   * <p>站咽喉格式：{@code Operator:S:Station:Track:Seq}（5 段）。 站点格式：{@code Operator:S:Station:Track}（4 段）。
   * 匹配规则：咽喉的 operator/station/track 与目标站点相同。
   *
   * @param graph 调度图
   * @param target 目标节点（通常是站点）
   * @return 该站点的咽喉节点列表；若无咽喉则返回空列表
   */
  private List<NodeId> findThroatsForStation(RailGraph graph, NodeId target) {
    if (graph == null || target == null) {
      return List.of();
    }
    Optional<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodeOpt =
        graph.findNode(target);
    if (nodeOpt.isEmpty()) {
      return List.of();
    }
    var node = nodeOpt.get();
    Optional<WaypointMetadata> metaOpt = node.waypointMetadata();
    if (metaOpt.isEmpty()) {
      return List.of();
    }
    WaypointMetadata meta = metaOpt.get();
    // 只为 STATION/DEPOT 查找咽喉
    if (meta.kind() != WaypointKind.STATION && meta.kind() != WaypointKind.DEPOT) {
      return List.of();
    }
    WaypointKind throatKind =
        meta.kind() == WaypointKind.STATION
            ? WaypointKind.STATION_THROAT
            : WaypointKind.DEPOT_THROAT;
    String operator = meta.operator();
    String station = meta.originStation();
    int track = meta.trackNumber();

    List<NodeId> throats = new ArrayList<>();
    for (var n : graph.nodes()) {
      Optional<WaypointMetadata> nMetaOpt = n.waypointMetadata();
      if (nMetaOpt.isEmpty()) {
        continue;
      }
      WaypointMetadata nMeta = nMetaOpt.get();
      if (nMeta.kind() == throatKind
          && nMeta.operator().equalsIgnoreCase(operator)
          && nMeta.originStation().equalsIgnoreCase(station)
          && nMeta.trackNumber() == track) {
        throats.add(n.id());
      }
    }
    return throats;
  }

  /**
   * 计算到咽喉的剩余边数（若有咽喉）。
   *
   * <p>若目标站点有咽喉，返回当前位置到最近咽喉的剩余边数；否则返回空。
   *
   * @param graph 调度图
   * @param route 线路定义
   * @param currentIndex 当前 route waypoint 索引
   * @param target 目标节点
   * @param lastPassed 列车经过的最后一个图节点
   * @return 到最近咽喉的剩余边数；若无咽喉或不可达则返回空
   */
  private Optional<Integer> remainingEdgesToThroat(
      RailGraph graph, RouteDefinition route, int currentIndex, NodeId target, NodeId lastPassed) {
    List<NodeId> throats = findThroatsForStation(graph, target);
    if (throats.isEmpty()) {
      return Optional.empty();
    }
    int minEdges = Integer.MAX_VALUE;
    for (NodeId throat : throats) {
      // 构建一个临时路径：从当前位置到咽喉
      // 由于咽喉不一定在 route waypoints 中，需要用最短路计算
      var pathFinder =
          new org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder();
      NodeId from =
          lastPassed != null ? lastPassed : route.waypoints().get(Math.max(0, currentIndex));
      var pathOpt =
          pathFinder.shortestPath(
              graph,
              from,
              throat,
              org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder.Options
                  .shortestDistance());
      if (pathOpt.isPresent()) {
        int edgeCount = pathOpt.get().nodes().size() - 1;
        minEdges = Math.min(minEdges, edgeCount);
      }
    }
    return minEdges == Integer.MAX_VALUE ? Optional.empty() : Optional.of(minEdges);
  }

  /**
   * 计算从当前位置到目标节点之间所有中途停靠站点的累计停车时间。
   *
   * <p>只累加 passType 为 STOP 的站点的 dwellSeconds（不含 PASS 和 TERMINATE）。 目标站点本身不计入中途停车（会在到达后单独计算）。
   *
   * @param route 当前线路定义
   * @param currentIndex 当前 waypoint 索引
   * @param targetNodeId 目标节点
   * @return 中途停车总秒数
   */
  private int computeIntermediateDwellSec(
      RouteDefinition route, int currentIndex, NodeId targetNodeId) {
    if (route == null || route.id() == null || currentIndex < 0) {
      return 0;
    }

    List<NodeId> waypoints = route.waypoints();
    if (waypoints.isEmpty() || currentIndex >= waypoints.size() - 1) {
      return 0;
    }

    // 找到目标在 waypoint 中的位置
    int targetIndex = -1;
    for (int i = currentIndex + 1; i < waypoints.size(); i++) {
      if (targetNodeId.equals(waypoints.get(i))) {
        targetIndex = i;
        break;
      }
    }
    if (targetIndex <= currentIndex) {
      return 0;
    }

    // 获取 RouteStop 列表
    List<RouteStop> stops = routeDefinitions.listStops(route.id());
    if (stops.isEmpty()) {
      return 0;
    }

    int totalDwellSec = 0;

    // 遍历中途站点（currentIndex+1 到 targetIndex-1）
    for (int i = currentIndex + 1; i < targetIndex; i++) {
      Optional<RouteStop> stopOpt = routeDefinitions.findStop(route.id(), i);
      if (stopOpt.isEmpty()) {
        continue;
      }
      RouteStop stop = stopOpt.get();
      // 只累加 STOP 类型的站点
      if (stop.passType() == RouteStopPassType.STOP) {
        totalDwellSec += stop.dwellSeconds().orElse(0);
      }
    }

    return totalDwellSec;
  }
}
