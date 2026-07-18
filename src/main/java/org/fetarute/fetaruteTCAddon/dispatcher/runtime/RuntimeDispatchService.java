package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.plugin.java.JavaPlugin;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphConflictSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.EdgeOverrideRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry.LayoverCandidate;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfig;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.ControlDiagnostics;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.ControlDiagnosticsCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.MovementAuthorityService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.ShortestPathDistanceCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignalLookahead;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.TrainPositionResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.DispatchAction;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.DispatchDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.DispatchDecisionSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.DispatchEffectClass;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.ForwardSignalRiskSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.RiskFreshness;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.RiskSource;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.RouteUnlockPotentialDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.RouteUnlockPotentialPreview;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.RouteUnlockPotentialSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SameDirectionFollowThroughDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SameDirectionFollowThroughPreview;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherController;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherModeGate;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherPlannerMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartWaitForPlanner;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AdvisoryRisk;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorityHandoffSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.BlockerClassifier;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ConflictClearingEvidenceKind;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ConflictReleaseHint;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.DirectedTraversalContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.MovementPlanSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyAdvisoryPreviewSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyPreviewSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueEntry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestBuilder;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResourceResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnDirectiveParser;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignTextParser;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalComputationTrace;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalDecisionInputClassifier;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalDecisionInputType;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalPublicationGate;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;

/**
 * 运行时调度编排器。
 *
 * <p>本类负责 route progress、RouteStop action 意图、DYNAMIC effective node、Layover/Depot/Station
 * 授权，以及授权成功后的 destination 写入。TrainCarts 控车动作由 {@link RuntimeTrainController} 执行；RouteStop 纯解析由
 * {@link RouteStopActionResolver} 处理；普通移动授权顺序由 {@link MovementAuthorizationCoordinator} 收敛。
 *
 * <p>普通下一跳 destination 只能在 preview/canEnter、hard blocker 检查、acquire 与 acquire 结果复核均允许后写入。站点行为、
 * waypoint dwell、debug setup、spawn execution 等行为例外必须在写入处保留 reason 注释。
 *
 * <p>假设列车已写入 {@code FTA_OPERATOR_CODE/FTA_LINE_CODE/FTA_ROUTE_CODE}（或 {@code FTA_ROUTE_ID}）与 {@code
 * FTA_ROUTE_INDEX} tag，且 RouteDefinitionCache 已完成预热。
 */
public final class RuntimeDispatchService {

  private static final java.util.logging.Logger HEALTH_LOGGER =
      java.util.logging.Logger.getLogger("FetaruteTCAddon");

  private static final double SPEED_TICKS_PER_SECOND = 20.0;

  /** approach 正式窗口外的预制动距离（blocks），用于避免到窗口边界才突然套低速上限。 */
  static final double APPROACH_PREVIEW_DISTANCE_BLOCKS = 64.0;

  /** 当前 tick 可实际授权进入的最短硬窗口。更远路径只参与 advisory/lookahead。 */
  private static final int HARD_AUTHORITY_LOOKAHEAD_EDGES = 1;

  /** 硬授权距离化后的最大边数，覆盖短边密集咽喉，同时避免授权窗口无界膨胀。 */
  private static final int HARD_AUTHORITY_DISTANCE_MAX_EDGES =
      DynamicPlatformAllocator.ALLOCATION_EDGE_THRESHOLD;

  /** 前车扫描最多沿展开图边向前看的边数，避免短边密集区无界查询。 */
  private static final int FORWARD_TRAIN_SCAN_MAX_EDGES = 12;

  /** 普通 lookahead 距离化后的最大边数倍数，保留硬上限防止资源集膨胀。 */
  private static final int DISTANCE_LOOKAHEAD_MAX_EDGE_MULTIPLIER = 4;

  /** 普通 lookahead 距离化后的最小硬上限，覆盖默认短边密集咽喉。 */
  private static final int DISTANCE_LOOKAHEAD_MIN_MAX_EDGES = 12;

  /** 普通 lookahead 距离化后的绝对硬上限，避免极端配置放大占用资源集。 */
  private static final int DISTANCE_LOOKAHEAD_ABSOLUTE_MAX_EDGES = 24;

  /** Waypoint 作为 STOP/TERM 时的默认停站时长（秒）。 */
  private static final int DEFAULT_WAYPOINT_DWELL_SECONDS = 20;

  /** 居中动作执行时的临时速度限制（blocks/tick）。需大于 0 以允许 Station.centerTrain() 移动列车。 */
  private static final double WAYPOINT_CENTER_SPEED_LIMIT = 0.4;

  /** 推进点去重窗口（毫秒），用于压制同一节点的重复触发。 */
  private static final long PROGRESS_TRIGGER_DEDUP_MS = 800L;

  /** 异常列车清理去重窗口（毫秒），用于压制 split/member-remove 事件风暴。 */
  private static final long ABNORMAL_CLEANUP_DEDUP_MS = 2_000L;

  /** already-inside 同向 leader hold 的短期互等探测窗口。 */
  private static final Duration SAME_DIRECTION_LEADER_HOLD_TTL = Duration.ofSeconds(5);

  /** 非 FTA 列车只允许因明确脱轨状态进入实体销毁兜底。 */
  private static final String ABNORMAL_REASON_STATUS_DERAILED = "status-derailed";

  private final OccupancyManager occupancyManager;
  private final LaunchAuthorizationService launchAuthorizationService;
  private final MovementAuthorizationCoordinator movementAuthorizationCoordinator;
  private final RouteStopActionResolver routeStopActionResolver = new RouteStopActionResolver();
  private final RailGraphService railGraphService;
  private final RouteDefinitionCache routeDefinitions;
  private final RouteProgressRegistry progressRegistry;
  private final SignNodeRegistry signNodeRegistry;
  private final LayoverRegistry layoverRegistry;

  private final DwellRegistry dwellRegistry;

  private final ConfigManager configManager;
  private final StorageManager storageManager;
  private final TrainConfigResolver trainConfigResolver;
  private final DispatchPriorityResolver dispatchPriorityResolver;
  private final RuntimeTrainController runtimeTrainController = new RuntimeTrainController();
  private final Consumer<String> debugLogger;
  private Consumer<LayoverRegistry.LayoverCandidate> layoverListener = candidate -> {};
  private final RailGraphPathFinder pathFinder = new RailGraphPathFinder();
  private final ShortestPathDistanceCache shortestPathDistanceCache;
  private final MovementAuthorityService movementAuthorityService = new MovementAuthorityService();
  private final SmartDispatcherController smartDispatcherController;
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
  private final java.util.concurrent.ConcurrentMap<String, Long> abnormalCleanupLastAtMs =
      new java.util.concurrent.ConcurrentHashMap<>();
  private volatile CleanupResult lastCleanupResult =
      new CleanupResult(java.time.Instant.EPOCH, 0, 0, 0);
  private final java.util.concurrent.ConcurrentMap<String, BlockerSnapshot> blockerSnapshots =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.ConcurrentMap<String, FollowerStuckLeaderEvidence>
      followerStuckLeaderEvidence = new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.ConcurrentMap<String, SmartUnlockReservation>
      smartUnlockReservationsByCycle = new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.ConcurrentMap<String, SmartUnlockReservation>
      smartUnlockReservationsByTrain = new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.ConcurrentMap<String, Instant> smartUnlockNoReleaseTimeouts =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.ConcurrentMap<String, Instant> smartUnlockBlockerReleaseAt =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.ConcurrentMap<String, Instant> smartUnlockNoReleaseCooldowns =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.ConcurrentMap<String, SmartDirectionAuditSnapshot>
      smartDirectionAuditSnapshots = new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.ConcurrentMap<String, SameDirectionLeaderHold>
      sameDirectionLeaderHolds = new java.util.concurrent.ConcurrentHashMap<>();
  private volatile String lastSmartDispatchPlannerThrottleKey = "";
  private final java.util.concurrent.ConcurrentMap<String, String> survivorRefreshAfterRemoval =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.ConcurrentMap<String, DepartureGate> departureGates =
      new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * 运行时有效节点覆盖（按列车名 + route index）。
   *
   * <p>用于支持动态站台/同站不同站台容错：当列车实际到达的 NodeId 与线路定义不一致时，将“该索引的真实 NodeId”写入覆盖表， 后续信号 tick /
   * 占用请求构建将优先使用覆盖值。
   */
  private final java.util.concurrent.ConcurrentMap<
          String, java.util.concurrent.ConcurrentMap<Integer, EffectiveNodeOverride>>
      effectiveNodeOverrides = new java.util.concurrent.ConcurrentHashMap<>();

  /** 仅用于需要跨两个列车 key 原子迁移有效节点覆盖的复合操作。 */
  private final Object effectiveNodeOverridesLock = new Object();

  /** 串行化 TrainCarts 改名触发的跨注册表 owner 迁移。 */
  private final Object runtimeOwnerMigrationLock = new Object();

  /** 折返交接后等待真实列尾推进证据的旧进路保护。 */
  private final TurnbackFootprintGuardRegistry turnbackFootprintGuards =
      new TurnbackFootprintGuardRegistry();

  /** 最近一次 fresh acquire 生成的运动授权；旧 destination 不具备运动授权。 */
  private final java.util.concurrent.ConcurrentMap<String, MovementAuthorizationToken>
      movementAuthorizationTokens = new java.util.concurrent.ConcurrentHashMap<>();

  /** 被硬 STOP 抑制的列车；只有 fresh acquire 成功后才解除。 */
  private final java.util.concurrent.ConcurrentMap<String, HardStopReason> movementInhibitors =
      new java.util.concurrent.ConcurrentHashMap<>();

  private final java.util.concurrent.atomic.AtomicLong movementClaimVersion =
      new java.util.concurrent.atomic.AtomicLong();
  private final java.util.concurrent.atomic.AtomicLong dispatchDecisionVersion =
      new java.util.concurrent.atomic.AtomicLong();
  private final java.util.concurrent.ConcurrentMap<String, SignalAspect> dirtyEventSignals =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** 已实际下发到 live/physical 信号层的最近 aspect，用于避免黄灯候选只停留在 trace 中。 */
  private final java.util.concurrent.ConcurrentMap<String, SignalAspect> publishedPhysicalSignals =
      new java.util.concurrent.ConcurrentHashMap<>();

  private final java.util.Set<String> applyingEventSignals =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final java.util.concurrent.atomic.LongAdder coalescedEventCount =
      new java.util.concurrent.atomic.LongAdder();
  private final java.util.concurrent.atomic.LongAdder reentrantStopSuppressed =
      new java.util.concurrent.atomic.LongAdder();
  private final java.util.concurrent.ConcurrentMap<String, String> healthTraceFingerprints =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.ConcurrentMap<String, Long> healthTraceLastAtMs =
      new java.util.concurrent.ConcurrentHashMap<>();

  private final ControlDiagnosticsCache diagnosticsCache = new ControlDiagnosticsCache();
  private static final Duration BLOCKER_SNAPSHOT_TTL = Duration.ofSeconds(20);
  private static final Duration SMART_UNLOCK_NO_RELEASE_COOLDOWN = Duration.ofSeconds(30);

  /** 节点历史缓存：记录列车最近经过的节点（用于回退检测）。 */
  private final java.util.concurrent.ConcurrentMap<String, NodeHistory> nodeHistoryCache =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** 节点历史容量上限。 */
  private static final int NODE_HISTORY_CAPACITY = 10;

  /** 回退检测冷却时间（毫秒），避免重复触发 relaunch。 */
  private static final long RELAUNCH_COOLDOWN_MS = 5000L;

  /** 动态站台分配器。 */
  private final DynamicPlatformAllocator dynamicAllocator;

  /** DYNAMIC effective node 解析器：只返回候选选择结果，不写 TrainCarts destination。 */
  private final DynamicDestinationResolver dynamicDestinationResolver;

  /**
   * 控车速度附加限制。
   *
   * <p>基础速度由信号等级与边限速决定；这里仅承载运行时额外限制，例如 STOP/TERM 停靠点 approach 速度，以及移动授权反推出的最大速度。
   */
  private record ControlSpeedOverrides(
      OptionalDouble approachLimitBps,
      OptionalDouble movementAuthorityLimitBps,
      ApproachControl approachControl,
      AuthorityEnd authorityEnd,
      BlockedDestinationDiagnostic blockedDestination,
      ControlDebugResources debugResources) {
    private ControlSpeedOverrides {
      approachLimitBps = approachLimitBps == null ? OptionalDouble.empty() : approachLimitBps;
      movementAuthorityLimitBps =
          movementAuthorityLimitBps == null ? OptionalDouble.empty() : movementAuthorityLimitBps;
      approachControl = approachControl == null ? ApproachControl.none() : approachControl;
      authorityEnd = authorityEnd == null ? AuthorityEnd.none() : authorityEnd;
      blockedDestination =
          blockedDestination == null ? BlockedDestinationDiagnostic.none() : blockedDestination;
      debugResources = debugResources == null ? ControlDebugResources.empty() : debugResources;
    }

    private static ControlSpeedOverrides empty() {
      return new ControlSpeedOverrides(
          OptionalDouble.empty(),
          OptionalDouble.empty(),
          ApproachControl.none(),
          AuthorityEnd.none(),
          BlockedDestinationDiagnostic.none(),
          ControlDebugResources.empty());
    }
  }

  /** Smart Dispatcher 对当前信号 tick 的可落地修正。 */
  private record SmartSignalDecisionResult(
      SignalAspect aspect,
      OptionalDouble movementAuthorityLimitBps,
      OptionalLong distanceOpt,
      DispatchAction action,
      RiskSource riskSource,
      boolean invalidatingStop,
      String stopReason) {
    private SmartSignalDecisionResult(
        SignalAspect aspect, OptionalDouble movementAuthorityLimitBps, OptionalLong distanceOpt) {
      this(
          aspect,
          movementAuthorityLimitBps,
          distanceOpt,
          DispatchAction.NO_ACTION,
          RiskSource.NONE,
          false,
          "none");
    }

    private SmartSignalDecisionResult {
      aspect = aspect == null ? SignalAspect.STOP : aspect;
      movementAuthorityLimitBps =
          movementAuthorityLimitBps == null ? OptionalDouble.empty() : movementAuthorityLimitBps;
      distanceOpt = distanceOpt == null ? OptionalLong.empty() : distanceOpt;
      action = action == null ? DispatchAction.NO_ACTION : action;
      riskSource = riskSource == null ? RiskSource.NONE : riskSource;
      stopReason = stopReason == null || stopReason.isBlank() ? "none" : stopReason.trim();
      invalidatingStop = aspect == SignalAspect.STOP && invalidatingStop;
    }

    /** 是否是可重试的 STOP：释放本 tick 临时授权，但不破坏 destination/token 生命周期。 */
    private boolean isRecoverableHold() {
      return aspect == SignalAspect.STOP && !invalidatingStop;
    }

    /** 是否是真实物理授权边界失败，需要进入硬失效路径。 */
    private boolean isPhysicalAuthorityFailure() {
      return aspect == SignalAspect.STOP && invalidatingStop;
    }
  }

  /** Advisory lookahead 的只读扫描结果。 */
  private record AdvisoryPreviewResult(OccupancyDecision decision, List<AdvisoryRisk> risks) {
    private AdvisoryPreviewResult {
      risks = risks == null ? List.of() : List.copyOf(risks);
    }
  }

  /** Phase 1.8 minimal forward unlock 的短生命周期 reservation。 */
  private record SmartUnlockReservation(
      String reservationId,
      String trainName,
      String cycleId,
      String planKind,
      List<OccupancyResource> resources,
      NodeId authorityEnd,
      String planHash,
      long createdTick,
      int ttlTicks,
      List<String> expectedReleasedResources,
      List<String> initiallyBlockedTrains,
      String initialCurrentNode,
      String initialLastPassedGraphNode,
      long tokenClaimVersion,
      boolean committed) {
    private SmartUnlockReservation {
      reservationId = reservationId == null || reservationId.isBlank() ? "-" : reservationId;
      trainName = trainName == null || trainName.isBlank() ? "-" : trainName.trim();
      cycleId = cycleId == null || cycleId.isBlank() ? "-" : cycleId.trim();
      planKind = planKind == null || planKind.isBlank() ? "-" : planKind.trim();
      planHash = planHash == null || planHash.isBlank() ? "-" : planHash.trim();
      resources = resources == null ? List.of() : List.copyOf(resources);
      ttlTicks = Math.max(1, ttlTicks);
      expectedReleasedResources =
          expectedReleasedResources == null ? List.of() : List.copyOf(expectedReleasedResources);
      initiallyBlockedTrains =
          initiallyBlockedTrains == null ? List.of() : List.copyOf(initiallyBlockedTrains);
      initialCurrentNode =
          initialCurrentNode == null || initialCurrentNode.isBlank()
              ? "-"
              : initialCurrentNode.trim();
      initialLastPassedGraphNode =
          initialLastPassedGraphNode == null || initialLastPassedGraphNode.isBlank()
              ? "-"
              : initialLastPassedGraphNode.trim();
      tokenClaimVersion = Math.max(-1L, tokenClaimVersion);
    }

    private boolean expired(long tick) {
      return tick - createdTick >= ttlTicks;
    }

    private SmartUnlockReservation committed(long claimVersion) {
      return new SmartUnlockReservation(
          reservationId,
          trainName,
          cycleId,
          planKind,
          resources,
          authorityEnd,
          planHash,
          createdTick,
          ttlTicks,
          expectedReleasedResources,
          initiallyBlockedTrains,
          initialCurrentNode,
          initialLastPassedGraphNode,
          claimVersion,
          true);
    }
  }

  /** 当前 tick 对 live/physical 信号层的发布判定。 */
  private record PhysicalSignalPublication(
      SignalAspect before,
      SignalAspect after,
      boolean updateRequired,
      boolean updated,
      String skippedReason) {}

  /** 信号刷新后的可观测结果。 */
  public record SignalRefreshResult(
      boolean resolved,
      String trainName,
      SignalAspect before,
      SignalAspect after,
      boolean physicalPublished,
      String reason) {

    public SignalRefreshResult {
      trainName = trainName == null ? "" : trainName.trim();
      before = before == null ? SignalAspect.STOP : before;
      after = after == null ? before : after;
      reason = reason == null || reason.isBlank() ? "-" : reason.trim();
    }

    public static SignalRefreshResult unresolved(String trainName, String reason) {
      return new SignalRefreshResult(
          false, trainName, SignalAspect.STOP, SignalAspect.STOP, false, reason);
    }
  }

  /** 已通过最终授权链路的信号发布上下文。 */
  private record FinalSignalAuthorization(
      String source,
      OccupancyRequest request,
      OccupancyDecision decision,
      SignalPublicationGate.Decision publication,
      NodeId currentNode,
      NodeId nextNode,
      SignalComputationTrace.TokenState tokenState,
      boolean destinationPresent,
      boolean advisoryApplied) {

    private FinalSignalAuthorization {
      source = source == null || source.isBlank() ? "UNKNOWN" : source.trim();
      tokenState = tokenState == null ? SignalComputationTrace.TokenState.NONE : tokenState;
      publication =
          publication == null
              ? new SignalPublicationGate.Decision(
                  SignalAspect.STOP,
                  SignalAspect.STOP,
                  SignalDecisionInputType.UNKNOWN,
                  true,
                  false,
                  "publication-missing")
              : publication;
    }
  }

  /** 最终信号发布前的安全校验结果。 */
  private record FinalSignalValidation(
      boolean allowed,
      boolean hardBarrierPresent,
      boolean snapshotStale,
      String reason,
      String hardBarrierReason) {

    private FinalSignalValidation {
      reason = reason == null || reason.isBlank() ? "-" : reason.trim();
      hardBarrierReason =
          hardBarrierReason == null || hardBarrierReason.isBlank() ? "-" : hardBarrierReason.trim();
    }

    static FinalSignalValidation ok() {
      return new FinalSignalValidation(true, false, false, "-", "-");
    }

    static FinalSignalValidation blocked(
        String reason,
        boolean hardBarrierPresent,
        boolean snapshotStale,
        String hardBarrierReason) {
      return new FinalSignalValidation(
          false, hardBarrierPresent, snapshotStale, reason, hardBarrierReason);
    }
  }

  private record SingleZoneAdmissionState(
      boolean hasOtherPresence,
      boolean hasClaimPresence,
      boolean sameDirectionLeader,
      boolean oppositeOrUnknownPresence,
      boolean oppositeDirectionPresent,
      boolean unknownDirectionPresent,
      boolean leaderStalled,
      boolean leaderProgressFresh,
      boolean leaderWillTerminalOrDwell,
      boolean followerSafeHoldPoint,
      String leaderTrain,
      CorridorDirection leaderDirection,
      String blockerReason,
      List<String> occupantTrains,
      List<String> occupantDirections) {
    private SingleZoneAdmissionState {
      occupantTrains = occupantTrains == null ? List.of() : List.copyOf(occupantTrains);
      occupantDirections = occupantDirections == null ? List.of() : List.copyOf(occupantDirections);
    }

    private boolean queueOnlyPresence() {
      return hasOtherPresence && !hasClaimPresence;
    }
  }

  /** 同向入口准入在缺少明确前后序时的单侧放行裁决。 */
  private record SameDirectionCorridorPriority(
      boolean applicable,
      SameDirectionLeaderOrder leaderOrder,
      boolean selfIsCorridorPriority,
      String reason) {
    private SameDirectionCorridorPriority {
      leaderOrder = leaderOrder == null ? SameDirectionLeaderOrder.UNKNOWN : leaderOrder;
      reason = reason == null || reason.isBlank() ? "-" : reason.trim();
    }

    private static SameDirectionCorridorPriority notApplicable(String reason) {
      return new SameDirectionCorridorPriority(
          false, SameDirectionLeaderOrder.UNKNOWN, false, reason);
    }
  }

  /**
   * 外部同向前车的 drain 预测。
   *
   * <p>该证据只用于收紧现有 same-direction admission：前车必须在自己的 route plan 中覆盖同一个 conflict，且与本车未来 route window
   * 共享至少一条该 conflict 下的物理区间边。方向一致后，外部跟驰可在入口前安全持车点存在时继续由真实 {@code NODE}/{@code EDGE} 闭塞控制；只到
   * Station/Depot 边界且缺少安全持车点时仍按 fail-safe 保持互斥。
   */
  private record SameDirectionLeaderDrainPrediction(
      boolean applicable,
      boolean drainProven,
      boolean routeOverlap,
      boolean directionMatches,
      boolean exitVisible,
      boolean boundaryOnly,
      String reason,
      String leaderRouteId,
      int leaderCurrentIndex,
      String leaderCurrentNode,
      String leaderExitReason) {
    private SameDirectionLeaderDrainPrediction {
      reason = reason == null || reason.isBlank() ? "-" : reason.trim();
      leaderRouteId = leaderRouteId == null || leaderRouteId.isBlank() ? "-" : leaderRouteId.trim();
      leaderCurrentNode =
          leaderCurrentNode == null || leaderCurrentNode.isBlank() ? "-" : leaderCurrentNode.trim();
      leaderExitReason =
          leaderExitReason == null || leaderExitReason.isBlank() ? "-" : leaderExitReason.trim();
    }

    private static SameDirectionLeaderDrainPrediction notApplicable() {
      return new SameDirectionLeaderDrainPrediction(
          false, true, false, false, false, false, "not-same-direction-leader", "-", -1, "-", "-");
    }

    private static SameDirectionLeaderDrainPrediction blocked(
        String reason,
        boolean routeOverlap,
        boolean directionMatches,
        boolean exitVisible,
        boolean boundaryOnly,
        RouteDefinition route,
        int leaderCurrentIndex,
        NodeId leaderCurrentNode,
        EntryLookaheadEvaluator.Result lookahead) {
      return new SameDirectionLeaderDrainPrediction(
          true,
          false,
          routeOverlap,
          directionMatches,
          exitVisible,
          boundaryOnly,
          reason,
          route == null ? "-" : route.id().value(),
          leaderCurrentIndex,
          leaderCurrentNode == null ? "-" : leaderCurrentNode.value(),
          lookahead == null ? "-" : lookahead.failureReason());
    }

    private static SameDirectionLeaderDrainPrediction proven(
        RouteDefinition route,
        int leaderCurrentIndex,
        NodeId leaderCurrentNode,
        EntryLookaheadEvaluator.Result lookahead) {
      return new SameDirectionLeaderDrainPrediction(
          true,
          true,
          true,
          true,
          true,
          false,
          "same-direction-leader-drain-predicted",
          route == null ? "-" : route.id().value(),
          leaderCurrentIndex,
          leaderCurrentNode == null ? "-" : leaderCurrentNode.value(),
          lookahead == null ? "-" : lookahead.failureReason());
    }

    private static SameDirectionLeaderDrainPrediction terminalFollowThrough(
        RouteDefinition route,
        int leaderCurrentIndex,
        NodeId leaderCurrentNode,
        EntryLookaheadEvaluator.Result lookahead) {
      return new SameDirectionLeaderDrainPrediction(
          true,
          true,
          true,
          true,
          true,
          false,
          "same-direction-leader-terminal-follow-through",
          route == null ? "-" : route.id().value(),
          leaderCurrentIndex,
          leaderCurrentNode == null ? "-" : leaderCurrentNode.value(),
          lookahead == null ? "-" : lookahead.failureReason());
    }
  }

  /** Smart traffic control 对 single/controlled region 入口的判定。 */
  private enum SmartAdmissionDecision {
    ALLOW_ENTER,
    ALLOW_ALREADY_INSIDE_CONTINUE,
    HOLD_AT_ENTRY,
    HOLD_AT_DEPOT,
    HOLD_AT_STATION,
    REJECT_OPPOSITE_DIRECTION,
    REJECT_UNKNOWN_DIRECTION,
    REJECT_LEADER_STALLED,
    REJECT_LEADER_OCCUPYING,
    REJECT_LEADER_EXIT_NOT_VISIBLE,
    REJECT_REGION_OCCUPIED_UNSAFE,
    REJECT_DOWNSTREAM_BLOCKED,
    REJECT_THROAT_SECTION_BUSY
  }

  /** already-inside 同向 leader 与本车的可证明顺序。 */
  private enum SameDirectionLeaderOrder {
    LEADER_AHEAD,
    TRAIN_AHEAD,
    UNKNOWN
  }

  /** Smart admission 的触发上下文，用于区分 depot/station/entry 的 local-only hold。 */
  private record SmartAdmissionContext(boolean atDepot, boolean atStation, String source) {
    private SmartAdmissionContext {
      source = source == null || source.isBlank() ? "smart-admission" : source.trim();
    }

    private static SmartAdmissionContext entry(String source) {
      return new SmartAdmissionContext(false, false, source);
    }

    private static SmartAdmissionContext depot(String source) {
      return new SmartAdmissionContext(true, false, source);
    }

    private static SmartAdmissionContext station(String source) {
      return new SmartAdmissionContext(false, true, source);
    }
  }

  /** already-inside 守卫建立的短期 wait-for 关系，用于打破同向互等。 */
  private record SameDirectionLeaderHold(String leaderTrain, Instant capturedAt) {
    private SameDirectionLeaderHold {
      leaderTrain = leaderTrain == null || leaderTrain.isBlank() ? "-" : leaderTrain.trim();
      capturedAt = capturedAt == null ? Instant.now() : capturedAt;
    }
  }

  /** 停站 retain 期间“身后旧 single 资源是否可释放”的只读证明结果。 */
  private record StopRetainBehindReleaseProof(
      boolean routeProgressCanProveBehind,
      boolean wouldReleaseIfBehaviorPatchExisted,
      String proofSource,
      String noReleaseReason) {
    private StopRetainBehindReleaseProof {
      proofSource = proofSource == null || proofSource.isBlank() ? "-" : proofSource.trim();
      noReleaseReason =
          noReleaseReason == null || noReleaseReason.isBlank() ? "-" : noReleaseReason.trim();
    }
  }

  /** 咽喉整段原子准入的只读判定结果。 */
  private record ThroatSectionAtomicCheck(
      boolean applicable,
      boolean clear,
      String reason,
      String throatEntry,
      String throatExitSafePoint,
      String occupiedResource,
      List<OccupancyResource> resources) {
    private ThroatSectionAtomicCheck {
      reason = reason == null || reason.isBlank() ? "-" : reason.trim();
      throatEntry = throatEntry == null || throatEntry.isBlank() ? "-" : throatEntry.trim();
      throatExitSafePoint =
          throatExitSafePoint == null || throatExitSafePoint.isBlank()
              ? "-"
              : throatExitSafePoint.trim();
      occupiedResource =
          occupiedResource == null || occupiedResource.isBlank() ? "-" : occupiedResource.trim();
      resources = resources == null ? List.of() : List.copyOf(resources);
    }

    private static ThroatSectionAtomicCheck notApplicable(String reason) {
      return new ThroatSectionAtomicCheck(false, true, reason, "-", "-", "-", List.of());
    }

    private static ThroatSectionAtomicCheck clear(
        NodeId entry, NodeId exit, Collection<OccupancyResource> resources) {
      return new ThroatSectionAtomicCheck(
          true,
          true,
          "throat-section-clear",
          nodeValue(entry),
          nodeValue(exit),
          "-",
          resources == null ? List.of() : List.copyOf(resources));
    }

    private static ThroatSectionAtomicCheck blocked(
        String reason,
        NodeId entry,
        NodeId exit,
        String occupiedResource,
        Collection<OccupancyResource> resources) {
      return new ThroatSectionAtomicCheck(
          true,
          false,
          reason,
          nodeValue(entry),
          nodeValue(exit),
          occupiedResource,
          resources == null ? List.of() : List.copyOf(resources));
    }

    private static String nodeValue(NodeId node) {
      return node == null || node.value() == null ? "-" : node.value();
    }
  }

  /** admission 内部使用的有向路径视图，优先来自 MovementPlanSnapshot。 */
  private record ThroatAtomicPath(
      List<NodeId> nodes, List<DirectedTraversalContext.DirectedEdge> edges) {
    private ThroatAtomicPath {
      nodes = nodes == null ? List.of() : List.copyOf(nodes);
      edges = edges == null ? List.of() : List.copyOf(edges);
    }

    private static ThroatAtomicPath from(OccupancyRequestContext context) {
      if (context == null || context.request() == null) {
        return new ThroatAtomicPath(List.of(), List.of());
      }
      Optional<MovementPlanSnapshot> plan = context.request().movementPlanSnapshot();
      if (plan.isPresent()
          && plan.get().expandedPathNodes().size() >= 2
          && !plan.get().directedEdges().isEmpty()) {
        return new ThroatAtomicPath(plan.get().expandedPathNodes(), plan.get().directedEdges());
      }
      List<NodeId> nodes = context.pathNodes();
      List<RailEdge> railEdges = context.edges();
      if (nodes == null || railEdges == null || nodes.size() < 2 || railEdges.isEmpty()) {
        return new ThroatAtomicPath(List.of(), List.of());
      }
      List<DirectedTraversalContext.DirectedEdge> directedEdges = new ArrayList<>();
      int edgeCount = Math.min(railEdges.size(), nodes.size() - 1);
      for (int i = 0; i < edgeCount; i++) {
        RailEdge edge = railEdges.get(i);
        if (edge == null) {
          continue;
        }
        directedEdges.add(
            new DirectedTraversalContext.DirectedEdge(edge.id(), nodes.get(i), nodes.get(i + 1)));
      }
      return new ThroatAtomicPath(nodes, directedEdges);
    }
  }

  /** Smart admission 的只读计算结果；执行侧仍必须经过 mode/effect gate。 */
  private record SmartAdmissionResult(
      boolean applies,
      boolean allowed,
      SmartAdmissionDecision decision,
      DispatchEffectClass effectClass,
      String reason,
      OccupancyResource region,
      CorridorDirection requestedDirection,
      boolean alreadyInside,
      boolean localOnlyHold,
      SmartAdmissionContext context,
      SingleZoneAdmissionState state,
      EntryLookaheadEvaluator.Result lookahead) {
    private SmartAdmissionResult {
      decision = decision == null ? SmartAdmissionDecision.ALLOW_ENTER : decision;
      effectClass = effectClass == null ? DispatchEffectClass.DIAGNOSTIC_ONLY : effectClass;
      reason = reason == null || reason.isBlank() ? "-" : reason.trim();
      requestedDirection =
          requestedDirection == null ? CorridorDirection.UNKNOWN : requestedDirection;
      context = context == null ? SmartAdmissionContext.entry("smart-admission") : context;
    }

    private static SmartAdmissionResult notApplicable(SmartAdmissionContext context) {
      return new SmartAdmissionResult(
          false,
          true,
          SmartAdmissionDecision.ALLOW_ENTER,
          DispatchEffectClass.DIAGNOSTIC_ONLY,
          "not-single-region-entry",
          null,
          CorridorDirection.UNKNOWN,
          false,
          false,
          context,
          null,
          null);
    }
  }

  /**
   * 前向授权窗口末端。
   *
   * <p>distance 表示从当前节点到“第一处未授权资源边界”的距离；resource 为第一处未授权 EDGE/NODE 的摘要，reason 区分真实物理边界与 人工窗口截断。只有
   * {@link AuthorityEndReason#physical()} 为 true 的末端才能影响可见信号。
   */
  private record AuthorityEnd(
      OptionalLong distanceBlocks,
      String resource,
      int authorizedEdgeCount,
      AuthorityEndReason reason,
      boolean windowDerivedFromExpandedPath,
      boolean extensionAttempted,
      boolean extensionSucceeded) {
    private AuthorityEnd {
      distanceBlocks = distanceBlocks == null ? OptionalLong.empty() : distanceBlocks;
      resource = resource == null || resource.isBlank() ? "none" : resource.trim();
      authorizedEdgeCount = Math.max(0, authorizedEdgeCount);
      reason = reason == null ? AuthorityEndReason.NONE : reason;
    }

    private static AuthorityEnd none() {
      return new AuthorityEnd(
          OptionalLong.empty(), "none", 0, AuthorityEndReason.NONE, false, false, false);
    }

    private boolean physical() {
      return reason.physical();
    }

    private AuthorityEnd withReason(AuthorityEndReason nextReason) {
      return new AuthorityEnd(
          distanceBlocks,
          resource,
          authorizedEdgeCount,
          nextReason,
          windowDerivedFromExpandedPath,
          extensionAttempted,
          extensionSucceeded);
    }
  }

  /** 授权失败/STOP 时 TrainCarts destination 是否仍被保留。 */
  private record BlockedDestinationDiagnostic(
      boolean destinationPresentWhileBlocked, String retainedDestination, String blockedReason) {
    private BlockedDestinationDiagnostic {
      retainedDestination =
          retainedDestination == null || retainedDestination.isBlank()
              ? "-"
              : retainedDestination.trim();
      blockedReason =
          blockedReason == null || blockedReason.isBlank() ? "none" : blockedReason.trim();
    }

    private static BlockedDestinationDiagnostic none() {
      return new BlockedDestinationDiagnostic(false, "-", "none");
    }
  }

  /** 本次控车判定的资源诊断摘要。 */
  private record ControlDebugResources(
      List<String> blockers, List<String> requestResources, List<String> currentClaims) {
    private ControlDebugResources {
      blockers = blockers == null ? List.of() : List.copyOf(blockers);
      requestResources = requestResources == null ? List.of() : List.copyOf(requestResources);
      currentClaims = currentClaims == null ? List.of() : List.copyOf(currentClaims);
    }

    private static ControlDebugResources empty() {
      return new ControlDebugResources(List.of(), List.of(), List.of());
    }
  }

  /**
   * 当前信号 tick 的 approach 限速语义。
   *
   * <p>approach 描述“列车已经进入下一处停靠 stop 的进路窗口或预制动 preview 区”。窗口按当前节点到停靠 stop 的展开路径距离计算，普通 PASS
   * 站、普通区间点与动态占位符不应仅凭节点编码触发 approach。
   */
  private record ApproachControl(
      Optional<NodeId> node,
      String kind,
      String reason,
      OptionalDouble limitBps,
      OptionalLong distanceBlocks,
      OptionalLong targetEdgeDistanceBlocks,
      int edgeCount) {
    private ApproachControl {
      node = node == null ? Optional.empty() : node;
      kind = kind == null || kind.isBlank() ? "none" : kind.trim();
      reason = reason == null || reason.isBlank() ? "none" : reason.trim();
      limitBps = limitBps == null ? OptionalDouble.empty() : limitBps;
      distanceBlocks = distanceBlocks == null ? OptionalLong.empty() : distanceBlocks;
      targetEdgeDistanceBlocks =
          targetEdgeDistanceBlocks == null ? OptionalLong.empty() : targetEdgeDistanceBlocks;
    }

    private static ApproachControl none() {
      return new ApproachControl(
          Optional.empty(),
          "none",
          "none",
          OptionalDouble.empty(),
          OptionalLong.empty(),
          OptionalLong.empty(),
          -1);
    }

    private boolean activeFor(NodeId candidate) {
      return candidate != null && node.isPresent() && node.get().equals(candidate);
    }
  }

  /** approach 窗口与预制动区间的判定结果。 */
  private record ApproachWindowState(
      boolean active,
      boolean preview,
      double previewDistanceBlocks,
      double distanceToBoundaryBlocks,
      double ratio) {
    private static ApproachWindowState inactive(double previewDistanceBlocks) {
      return new ApproachWindowState(
          false, false, previewDistanceBlocks, Double.POSITIVE_INFINITY, 0.0);
    }
  }

  /** 下一处需要停靠的 route stop。 */
  private record IndexedRouteStop(int index, NodeId node, RouteStop stop) {}

  /** station/depot approach 目标的结构化匹配键。 */
  private record ApproachNodeKey(String kind, String operator, String name, int trackNumber) {
    private ApproachNodeKey {
      kind = normalizeKeyPart(kind);
      operator = normalizeKeyPart(operator);
      name = normalizeKeyPart(name);
    }

    private boolean matches(ApproachNodeKey other) {
      return other != null
          && kind.equals(other.kind())
          && operator.equals(other.operator())
          && name.equals(other.name())
          && trackNumber == other.trackNumber();
    }
  }

  /** 下一停靠 stop 在图上的 approach 语义。 */
  private record ApproachTarget(
      NodeId stopNode, String kind, RouteStopPassType passType, Optional<ApproachNodeKey> key) {
    private ApproachTarget {
      key = key == null ? Optional.empty() : key;
    }
  }

  /** route stop 序列按调度图展开后的路径。 */
  private record ExpandedRoutePath(List<NodeId> nodes, List<RailEdge> edges) {
    private ExpandedRoutePath {
      nodes = nodes == null ? List.of() : List.copyOf(nodes);
      edges = edges == null ? List.of() : List.copyOf(edges);
    }
  }

  /** 展开路径上可触发 approach 的节点。 */
  private record ApproachTrigger(
      NodeId node, long distanceBlocks, int edgeCount, List<Long> edgeLengths, String reason) {
    private ApproachTrigger {
      edgeLengths = edgeLengths == null ? List.of() : List.copyOf(edgeLengths);
    }

    private long targetEdgeDistanceBlocks(int targetEdges) {
      if (targetEdges <= 0 || edgeLengths.isEmpty()) {
        return 0L;
      }
      int from = Math.max(0, edgeLengths.size() - targetEdges);
      long sum = 0L;
      for (int i = from; i < edgeLengths.size(); i++) {
        sum += Math.max(0L, edgeLengths.get(i));
      }
      return sum;
    }
  }

  /**
   * 调度层速度决策结果。
   *
   * <p>该结果刻意保留每一层限制的原始值，用于把“为什么最终只有某个速度”清楚暴露给运维诊断。
   */
  private record TargetSpeedDecision(
      double edgeLimitBps,
      double aspectBaseSpeedBps,
      String cautionSource,
      OptionalDouble approachLimitBps,
      OptionalDouble movementAuthorityLimitBps,
      OptionalDouble edgeSpeedLookaheadMinBps,
      double targetBps,
      String limiterSource) {
    private TargetSpeedDecision {
      cautionSource =
          cautionSource == null || cautionSource.isBlank() ? "none" : cautionSource.trim();
      approachLimitBps = approachLimitBps == null ? OptionalDouble.empty() : approachLimitBps;
      movementAuthorityLimitBps =
          movementAuthorityLimitBps == null ? OptionalDouble.empty() : movementAuthorityLimitBps;
      edgeSpeedLookaheadMinBps =
          edgeSpeedLookaheadMinBps == null ? OptionalDouble.empty() : edgeSpeedLookaheadMinBps;
      limiterSource =
          limiterSource == null || limiterSource.isBlank() ? "none" : limiterSource.trim();
    }
  }

  /** CAUTION 速度解析结果。 */
  private record CautionSpeedDecision(double speedBps, String source) {
    private CautionSpeedDecision {
      source = source == null || source.isBlank() ? "config" : source.trim();
    }
  }

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
    this.dispatchPriorityResolver =
        new DispatchPriorityResolver(
            storageManager, routeDefinitions, progressRegistry, this.debugLogger);
    SignalComputationTrace.configureLogger(this.debugLogger);
    if (occupancyManager instanceof SimpleOccupancyManager simpleOccupancyManager) {
      simpleOccupancyManager.setLiveBlockerSnapshotListener(this::updateLiveBlockerSnapshot);
    }
    this.smartDispatcherController = new SmartDispatcherController(this.debugLogger);
    this.launchAuthorizationService =
        new LaunchAuthorizationService(
            occupancyManager,
            (trainName, decision, now, scope) -> updateBlockerSnapshot(trainName, decision, now),
            this.debugLogger);
    this.movementAuthorizationCoordinator =
        new MovementAuthorizationCoordinator(occupancyManager, this.debugLogger);
    this.dynamicAllocator =
        new DynamicPlatformAllocator(routeDefinitions, occupancyManager, this.debugLogger);
    this.dynamicDestinationResolver =
        new DynamicDestinationResolver(dynamicAllocator, railGraphService, this.debugLogger);
    this.shortestPathDistanceCache =
        new ShortestPathDistanceCache(
            pathFinder,
            Duration.ofSeconds(resolveDistanceCacheRefreshSeconds()),
            resolvePathCacheMaxSize(),
            this.debugLogger);
  }

  private int resolveDistanceCacheRefreshSeconds() {
    try {
      ConfigManager.ConfigView view = configManager.current();
      if (view != null) {
        return Math.max(1, view.runtimeSettings().distanceCacheRefreshSeconds());
      }
    } catch (RuntimeException ignored) {
      // 启动早期配置尚未可用时回退默认值
    }
    return 3;
  }

  private int resolvePathCacheMaxSize() {
    try {
      ConfigManager.ConfigView view = configManager.current();
      if (view != null) {
        return Math.max(1, view.runtimeSettings().pathCacheMaxSize());
      }
    } catch (RuntimeException ignored) {
      // 启动早期配置尚未可用时回退默认值
    }
    return 4096;
  }

  /**
   * 当前 Smart Dispatcher / Traffic Control Supervisor 模式。
   *
   * <p>配置尚未加载或读取失败时一律回退到 OBSERVE_ONLY，保证启动早期不会意外执行智能调度副作用。
   */
  public SmartDispatcherMode smartDispatcherMode() {
    try {
      ConfigManager.ConfigView view = configManager.current();
      if (view != null && view.smartDispatcherSettings() != null) {
        return view.smartDispatcherSettings().mode();
      }
    } catch (RuntimeException ignored) {
      // 启动早期配置尚未可用时回退为只观察模式
    }
    return SmartDispatcherMode.OBSERVE_ONLY;
  }

  /** 注册 Layover 事件监听器（在列车进入 Layover 时触发）。 */
  public void setLayoverListener(Consumer<LayoverRegistry.LayoverCandidate> listener) {
    this.layoverListener = listener != null ? listener : candidate -> {};
  }

  /** 设置 EtaService（可选），用于在推进点时使 ETA 缓存失效。 */
  public void setEtaService(EtaService etaService) {
    this.etaService = etaService;
  }

  /** 返回运行时与事件驱动信号共用的 dispatch priority 解析器。 */
  public DispatchPriorityResolver dispatchPriorityResolver() {
    return dispatchPriorityResolver;
  }

  /**
   * 获取列车是否持有“发车许可锁”。
   *
   * <p>当锁存在时，表示列车处于站台停站/门控等待阶段，信号 tick 必须保持 STOP，不允许发车。
   *
   * @param trainName 列车名
   * @return true 表示锁存在
   */
  public boolean hasDepartureGate(String trainName) {
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return false;
    }
    return departureGates.containsKey(key);
  }

  /**
   * 获取（或覆盖）列车的发车许可锁。
   *
   * <p>通常由 AutoStation 在进入 WaitState 后调用。若同列车重复调用，会覆盖旧会话。
   *
   * @param trainName 列车名
   * @param sessionId 会话 ID（用于防止旧任务误释放）
   * @param reason 持锁原因（用于诊断）
   */
  public void acquireDepartureGate(String trainName, String sessionId, String reason) {
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty() || sessionId == null || sessionId.isBlank()) {
      return;
    }
    String normalizedReason = reason == null || reason.isBlank() ? "unspecified" : reason.trim();
    departureGates.put(key, new DepartureGate(sessionId.trim(), Instant.now(), normalizedReason));
  }

  /**
   * 释放列车的发车许可锁。
   *
   * <p>当传入 sessionId 时，只有会话匹配才会释放；用于防止旧停站任务误释放新会话的锁。 传入空 sessionId 时会无条件释放该列车锁。
   *
   * @param trainName 列车名
   * @param sessionId 会话 ID（可为空）
   * @return true 表示成功释放
   */
  public boolean releaseDepartureGate(String trainName, String sessionId) {
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return false;
    }
    if (sessionId == null || sessionId.isBlank()) {
      return departureGates.remove(key) != null;
    }
    DepartureGate existing = departureGates.get(key);
    if (existing == null || !existing.sessionId().equals(sessionId.trim())) {
      return false;
    }
    return departureGates.remove(key, existing);
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

  /** 返回信号事件合并与 envelope/cache 相关的运行统计。 */
  public SignalRuntimeStats signalRuntimeStats() {
    ShortestPathDistanceCache.Stats pathStats = shortestPathDistanceCache.stats();
    return new SignalRuntimeStats(
        pathStats.hits(),
        pathStats.misses(),
        0,
        0,
        0,
        dirtyEventSignals.size(),
        coalescedEventCount.sum(),
        reentrantStopSuppressed.sum(),
        0);
  }

  /**
   * 构建并输出 Smart Dispatcher 全局快照。
   *
   * <p>该快照在每轮巡检开始时生成，只做全局状态归纳与 trace，不直接写 destination、占用或 TrainCarts 控车命令。 后续每列车局部 tick
   * 会继续把同一轮的风险与决策写入更细的 trace。
   *
   * @param activeTrainNames 本轮巡检确认仍存在的 FTA 逻辑列车名
   * @param now 快照时间
   */
  public void traceSmartDispatchGlobalSnapshot(Set<String> activeTrainNames, Instant now) {
    Set<String> active = activeTrainNames == null ? Set.of() : Set.copyOf(activeTrainNames);
    Map<String, RouteProgressRegistry.RouteProgressEntry> progress = progressRegistry.snapshot();
    List<String> trainIds = new ArrayList<>();
    trainIds.addAll(active);
    for (String trainName : progress.keySet()) {
      if (trainName != null && !trainName.isBlank() && !trainIds.contains(trainName)) {
        trainIds.add(trainName);
      }
    }
    int queueCount =
        occupancyManager instanceof OccupancyQueueSupport queueSupport
            ? queueSupport.snapshotQueues().size()
            : 0;
    int claimCount = occupancyManager == null ? 0 : occupancyManager.snapshotClaims().size();
    SmartDispatcherController.GlobalRailwayStateSnapshot snapshot =
        new SmartDispatcherController.GlobalRailwayStateSnapshot(
            now == null ? Instant.now() : now,
            trainIds.size(),
            active.size(),
            progress.size(),
            claimCount,
            queueCount,
            blockerSnapshots.size(),
            movementAuthorizationTokens.size(),
            movementInhibitors.size(),
            occupancyVersion(),
            progressRegistry.version(),
            0,
            0,
            trainIds);
    smartDispatcherController.traceGlobalSnapshot(snapshot);
    traceSmartMinimalForwardPlanner(active, progress, now == null ? Instant.now() : now);
  }

  private void traceSmartMinimalForwardPlanner(
      Set<String> activeTrainNames,
      Map<String, RouteProgressRegistry.RouteProgressEntry> progress,
      Instant now) {
    ConfigManager.SmartDispatcherPlannerSettings config = smartDispatcherPlannerSettings();
    if (!config.enabled()
        || config.mode() == SmartDispatcherPlannerMode.OFF
        || smartDispatcherMode() == SmartDispatcherMode.OFF) {
      return;
    }
    observeSmartUnlockReservations(now);
    SmartWaitForPlanner.PlannerInput input =
        new SmartWaitForPlanner.PlannerInput(
            now,
            new SmartWaitForPlanner.PlannerSettings(
                config.enabled(),
                config.mode(),
                config.maxReservationResources(),
                config.reservationTtlTicks(),
                config.blockerSnapshotTtlMs(),
                config.requireSameDirection(),
                config.allowReverse(),
                config.allowTurnbackBeforeBoundary(),
                config.oneActiveReservationPerCycle()),
            smartPlannerInputEdges(progress, activeTrainNames, now),
            smartPlannerTrainStates(progress, activeTrainNames, now),
            smartUnlockReservationsByCycle.keySet());
    SmartWaitForPlanner.PlanResult result =
        smartDispatcherController.planMinimalForwardUnlock(input);
    rememberSmartDirectionAudit(result, now);
    if (result.traceLines().isEmpty()) {
      return;
    }
    if (result.throttleKey().equals(lastSmartDispatchPlannerThrottleKey)) {
      result
          .selectedPlan()
          .ifPresent(
              plan -> {
                traceSmartUnlockPlanApply(
                    plan, null, "selected", "unchanged-suppressed", true, "-");
                traceMutualConflictOwnerSet(input, plan, true);
              });
      debugLogger.accept(
          "SMART_DISPATCH_PLAN_UNCHANGED_SUPPRESSED graphHash="
              + result.graphHash()
              + " selectedPlanHash="
              + result.selectedPlanHash()
              + " throttleKey="
              + result.throttleKey());
      return;
    }
    lastSmartDispatchPlannerThrottleKey = result.throttleKey();
    smartDispatcherController.traceMinimalForwardPlan(result);
    result
        .selectedPlan()
        .ifPresent(
            plan -> {
              traceSmartUnlockPlanApply(plan, null, "selected", "selected", false, "-");
              traceMutualConflictOwnerSet(input, plan, false);
            });
    if (config.mode() == SmartDispatcherPlannerMode.ENFORCE_MINIMAL_FORWARD) {
      result
          .selectedPlan()
          .ifPresent(
              plan -> {
                DispatchAction action = smartPlannerAction(plan);
                String skipReason = smartDispatchExecutorSkipReason(plan, config, action);
                if (!"-".equals(skipReason)) {
                  traceSmartUnlockReservationWouldCreate(plan, action, skipReason);
                  traceSmartDispatchExecutorSkipped(plan, skipReason);
                  return;
                }
                if (isHeadOnYieldPlan(plan)) {
                  executeSmartHeadOnYield(plan, config, now);
                } else {
                  executeSmartUnlockReservation(plan, config, now);
                }
              });
    }
  }

  private void rememberSmartDirectionAudit(SmartWaitForPlanner.PlanResult result, Instant now) {
    if (result == null || result.candidates().isEmpty()) {
      return;
    }
    Instant capturedAt = now == null ? Instant.now() : now;
    for (SmartWaitForPlanner.UnlockCandidate candidate : result.candidates()) {
      if (candidate == null || !smartDirectionAuditReason(candidate.rejectReason())) {
        continue;
      }
      smartDirectionAuditSnapshots.put(
          normalizeTrainKey(candidate.train()),
          new SmartDirectionAuditSnapshot(candidate.train(), candidate.rejectReason(), capturedAt));
    }
  }

  /** 返回列车最近一次 planner 方向审计原因；过期或不存在时为空。 */
  public Optional<String> recentDirectionAuditReason(String trainName, Duration ttl) {
    if (trainName == null || trainName.isBlank()) {
      return Optional.empty();
    }
    Duration effectiveTtl = ttl == null || ttl.isNegative() ? Duration.ZERO : ttl;
    String key = normalizeTrainKey(trainName);
    SmartDirectionAuditSnapshot snapshot = smartDirectionAuditSnapshots.get(key);
    if (snapshot == null) {
      return Optional.empty();
    }
    Instant now = Instant.now();
    if (!effectiveTtl.isZero() && snapshot.capturedAt().plus(effectiveTtl).isBefore(now)) {
      smartDirectionAuditSnapshots.remove(key, snapshot);
      return Optional.empty();
    }
    return Optional.of(snapshot.reason());
  }

  private static boolean smartDirectionAuditReason(String reason) {
    if (reason == null || reason.isBlank()) {
      return false;
    }
    String normalized = reason.trim().toUpperCase(Locale.ROOT);
    return normalized.equals("INSUFFICIENT_DIRECTION_EVIDENCE")
        || normalized.equals("NEED_DIRECTION_AUDIT")
        || normalized.equals("WOULD_CREATE_OPPOSITE_DIRECTION_CLAIM");
  }

  private ConfigManager.SmartDispatcherPlannerSettings smartDispatcherPlannerSettings() {
    try {
      ConfigManager.ConfigView view = configManager.current();
      if (view != null
          && view.smartDispatcherSettings() != null
          && view.smartDispatcherSettings().plannerSettings() != null) {
        return view.smartDispatcherSettings().plannerSettings();
      }
    } catch (RuntimeException ignored) {
      // 启动早期配置不可用时使用只观察默认值
    }
    return ConfigManager.SmartDispatcherPlannerSettings.defaults();
  }

  private List<SmartWaitForPlanner.InputEdge> smartPlannerInputEdges(
      Map<String, RouteProgressRegistry.RouteProgressEntry> progress,
      Set<String> activeTrainNames,
      Instant now) {
    List<SmartWaitForPlanner.InputEdge> edges = new ArrayList<>();
    for (Map.Entry<String, BlockerSnapshot> entry : blockerSnapshots.entrySet()) {
      BlockerSnapshot snapshot = entry.getValue();
      if (snapshot == null) {
        continue;
      }
      long ageMs = Duration.between(snapshot.sampledAt(), now).toMillis();
      String blockedTrain = displayTrainNameForKey(entry.getKey(), progress, activeTrainNames);
      if (!blockerSnapshotProgressCurrent(blockedTrain, snapshot)) {
        blockerSnapshots.remove(entry.getKey(), snapshot);
        traceBlockerSnapshotProgressMoved(blockedTrain, snapshot.progressWindow());
        continue;
      }
      for (DeadlockBlockerInfo blocker : snapshot.blockers()) {
        if (blocker == null) {
          continue;
        }
        String resource = blocker.resourceKey();
        String resourceKind = resourceKindName(resource);
        edges.add(
            new SmartWaitForPlanner.InputEdge(
                blockedTrain,
                blocker.trainName(),
                resource,
                resourceKind,
                blocker.relation(),
                blocker.intent(),
                blocker.role(),
                blocker.source(),
                blocker.direction().orElse(CorridorDirection.UNKNOWN),
                ageMs,
                smartPlannerEdgeActiveForNormalAdmission(blocker)));
      }
    }
    return List.copyOf(edges);
  }

  private Map<String, SmartWaitForPlanner.TrainState> smartPlannerTrainStates(
      Map<String, RouteProgressRegistry.RouteProgressEntry> progress,
      Set<String> activeTrainNames,
      Instant now) {
    Set<String> names = new LinkedHashSet<>();
    if (activeTrainNames != null) {
      names.addAll(activeTrainNames);
    }
    if (progress != null) {
      names.addAll(progress.keySet());
    }
    for (BlockerSnapshot snapshot : blockerSnapshots.values()) {
      if (snapshot == null) {
        continue;
      }
      for (DeadlockBlockerInfo blocker : snapshot.blockers()) {
        if (blocker != null && !blocker.trainName().isBlank()) {
          names.add(blocker.trainName());
        }
      }
    }
    Map<String, SmartWaitForPlanner.TrainState> states = new LinkedHashMap<>();
    for (String trainName : names) {
      if (trainName == null || trainName.isBlank()) {
        continue;
      }
      SmartRecoveryInput input = smartRecoveryInput(trainName, Duration.ZERO, SignalAspect.STOP);
      CorridorDirection inferredDirection = smartPlannerForwardDirection(input);
      states.put(
          input.train(),
          new SmartWaitForPlanner.TrainState(
              input.train(),
              input.routeId(),
              input.currentIndex(),
              nodeText(input.currentNode()),
              nodeText(input.nextNode()),
              input.lastPassedGraphNode(),
              inferredDirection,
              inferredDirection == CorridorDirection.UNKNOWN ? "UNKNOWN" : "RUNTIME_ROUTE_CONTEXT",
              inferredDirection == CorridorDirection.UNKNOWN
                  ? smartPlannerDirectionFailureReason(input)
                  : "-",
              input.stuckDurationSeconds(),
              input.signal() == SignalAspect.STOP,
              false,
              false,
              false,
              input.oppositeSingleConflictPresent(),
              false,
              input.movementTokenState().name()));
    }
    return Map.copyOf(states);
  }

  private boolean smartPlannerEdgeActiveForNormalAdmission(DeadlockBlockerInfo blocker) {
    if (blocker == null) {
      return false;
    }
    String intent = blocker.intent() == null ? "" : blocker.intent();
    String role = blocker.role() == null ? "" : blocker.role();
    String relation = blocker.relation() == null ? "" : blocker.relation();
    return !intent.equals("LOOKAHEAD_PREVIEW")
        && !role.equals("LOOKAHEAD_PREVIEW")
        && !relation.equals("STALE_PROTECTIVE_CLAIM");
  }

  private static CorridorDirection smartPlannerForwardDirection(SmartRecoveryInput input) {
    // 单线方向必须来自 OccupancyRequest/MovementPlanSnapshot 的语义资源方向。
    // route current/next 只能证明列车仍有前方目标，不能证明它在某个 single conflict 内的 A/B 方向。
    return CorridorDirection.UNKNOWN;
  }

  private static String smartPlannerDirectionFailureReason(SmartRecoveryInput input) {
    if (input == null || input.train().isBlank()) {
      return "ACTIVE_STATE_MISSING";
    }
    if (input.currentNode() == null && input.lastPassedGraphNode().equals("-")) {
      return "CURRENT_ROUTE_NODE_MISSING";
    }
    if (input.nextNode() == null) {
      return "NEXT_ROUTE_NODE_MISSING";
    }
    return "INSUFFICIENT_DIRECTION_EVIDENCE";
  }

  private String displayTrainNameForKey(
      String key,
      Map<String, RouteProgressRegistry.RouteProgressEntry> progress,
      Set<String> activeTrainNames) {
    if (progress != null) {
      for (String candidate : progress.keySet()) {
        if (normalizeTrainKey(candidate).equals(key)) {
          return candidate;
        }
      }
    }
    if (activeTrainNames != null) {
      for (String candidate : activeTrainNames) {
        if (normalizeTrainKey(candidate).equals(key)) {
          return candidate;
        }
      }
    }
    return key == null || key.isBlank() ? "-" : key;
  }

  private static String resourceKindName(String resource) {
    if (resource == null || resource.isBlank() || !resource.contains(":")) {
      return "UNKNOWN";
    }
    return resource.substring(0, resource.indexOf(':')).toUpperCase(Locale.ROOT);
  }

  private static String nodeText(NodeId node) {
    return node == null ? "-" : node.value();
  }

  private void executeSmartUnlockReservation(
      SmartWaitForPlanner.UnlockCandidate plan,
      ConfigManager.SmartDispatcherPlannerSettings config,
      Instant now) {
    if (isHeadOnYieldPlan(plan)) {
      executeSmartHeadOnYield(plan, config, now);
      return;
    }
    traceSmartDispatchExecutorBridge(plan, config);
    if (!smartDispatcherRegisteredActionAllowed(
        plan == null ? "-" : plan.train(),
        "smart-minimal-forward-planner",
        DispatchAction.ACQUIRE_SPECULATIVE_UNLOCK_RESERVATION)) {
      traceSmartUnlockReservationWouldCreate(plan, "EFFECT_GATE_SUPPRESSED");
      traceSmartDispatchExecutorSkipped(plan, "EFFECT_GATE_SUPPRESSED");
      return;
    }
    if (plan == null || !plan.accepted()) {
      traceSmartDispatchExecutorSkipped(plan, "PLAN_NOT_ACCEPTED");
      return;
    }
    if (smartUnlockNoReleaseCooldownActive(plan, now)) {
      traceSmartDispatchExecutorSkipped(plan, "NO_RELEASE_COOLDOWN");
      return;
    }
    if (config.oneActiveReservationPerCycle()
        && smartUnlockReservationsByCycle.containsKey(plan.cycleId())) {
      debugLogger.accept(
          "SMART_UNLOCK_RESERVATION_REJECTED train="
              + plan.train()
              + " cycleId="
              + plan.cycleId()
              + " reason=one-active-reservation-per-cycle");
      traceSmartDispatchExecutorSkipped(plan, "ACTIVE_RESERVATION_EXISTS");
      return;
    }
    List<OccupancyResource> planResources = parsePlannerResources(plan.resources());
    if (planResources.isEmpty()) {
      debugLogger.accept(
          "SMART_UNLOCK_RESERVATION_REJECTED train="
              + plan.train()
              + " cycleId="
              + plan.cycleId()
              + " reason=no-reservation-resources");
      traceSmartDispatchExecutorSkipped(plan, "RESOURCE_WINDOW_INVALID");
      return;
    }
    if (singleRegionOppositeOrUnknownExternalBarrier(
        plan.train(), planResources, plan.direction())) {
      traceSingleRegionHardBarrier(
          plan.train(),
          DispatchAction.ACQUIRE_SPECULATIVE_UNLOCK_RESERVATION,
          "smart-minimal-forward-planner",
          planResources);
      traceSmartDispatchExecutorSkipped(
          plan, SimpleOccupancyManager.OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER);
      return;
    }
    if (plan.direction() == CorridorDirection.UNKNOWN || config.allowReverse()) {
      String reason =
          plan.direction() == CorridorDirection.UNKNOWN
              ? "INSUFFICIENT_DIRECTION_EVIDENCE"
              : "SAME_DIRECTION_FAILED";
      debugLogger.accept(
          "SMART_DIRECTION_INVARIANT_BLOCKED train="
              + plan.train()
              + " cycleId="
              + plan.cycleId()
              + " reason="
              + (plan.direction() == CorridorDirection.UNKNOWN
                  ? "INSUFFICIENT_DIRECTION_EVIDENCE"
                  : "allow-reverse-misconfigured"));
      traceSmartDispatchExecutorSkipped(plan, reason);
      return;
    }
    if (planResources.size() > config.maxReservationResources()) {
      debugLogger.accept(
          "SMART_RESERVATION_RESOURCE_LIMIT_EXCEEDED train="
              + plan.train()
              + " reservationResourceCount="
              + planResources.size()
              + " limit="
              + config.maxReservationResources());
      traceSmartDispatchExecutorSkipped(plan, "RESERVATION_RESOURCE_LIMIT_EXCEEDED");
      return;
    }
    NodeId authorityEnd = plannerNode(plan.authorityEnd()).orElse(null);
    if (authorityEnd == null) {
      debugLogger.accept(
          "SMART_UNLOCK_AUTHORITY_REJECTED train="
              + plan.train()
              + " reason=authority-end-missing");
      traceSmartDispatchExecutorSkipped(plan, "AUTHORITY_BOUNDARY_MISSING");
      return;
    }
    long tick = currentSignalTraceTick();
    String reservationId = "unlock-" + Long.toUnsignedString(tick) + "-" + plan.planHash();
    List<String> initiallyBlockedTrains = initiallyBlockedTrains(plan.train(), plan.resources());
    SmartUnlockReservation reservation =
        new SmartUnlockReservation(
            reservationId,
            plan.train(),
            plan.cycleId(),
            plan.kind().name(),
            planResources,
            authorityEnd,
            plan.planHash(),
            tick,
            config.reservationTtlTicks(),
            plan.resources(),
            initiallyBlockedTrains,
            plan.currentNode(),
            plan.currentNode(),
            -1L,
            false);
    smartUnlockReservationsByCycle.put(plan.cycleId(), reservation);
    smartUnlockReservationsByTrain.put(normalizeTrainKey(plan.train()), reservation);
    debugLogger.accept(
        "SMART_UNLOCK_RESERVATION_CREATED reservationId="
            + reservationId
            + " train="
            + plan.train()
            + " cycleId="
            + plan.cycleId()
            + " resources="
            + planResources
            + " authorityEnd="
            + authorityEnd.value()
            + " createdTick="
            + tick
            + " ttl="
            + config.reservationTtlTicks()
            + " expectedReleasedResources="
            + plan.resources());
    traceSmartUnlockPlanApply(
        plan, reservation, "reservation-created", "reservation-created", false, "-");
    commitSmartUnlockReservation(reservation, plan, now);
  }

  private void traceSmartDispatchExecutorBridge(
      SmartWaitForPlanner.UnlockCandidate plan,
      ConfigManager.SmartDispatcherPlannerSettings config) {
    String reasonIfNot =
        smartDispatchExecutorSkipReason(
            plan, config, DispatchAction.ACQUIRE_SPECULATIVE_UNLOCK_RESERVATION);
    MovementAuthorizationToken token =
        plan == null ? null : movementToken(plan.train()).orElse(null);
    String destination = token == null ? "-" : token.committedDestination().orElse("-");
    debugLogger.accept(
        "SMART_DISPATCH_EXECUTOR_BRIDGE planId="
            + (plan == null ? "-" : plan.planHash())
            + " cycleId="
            + (plan == null ? "-" : plan.cycleId())
            + " tick="
            + currentSignalTraceTick()
            + " train="
            + (plan == null ? "-" : plan.train())
            + " globalMode="
            + smartDispatcherMode()
            + " plannerMode="
            + (config == null ? SmartDispatcherPlannerMode.OFF : config.mode())
            + " willCreateReservation="
            + "-".equals(reasonIfNot)
            + " reasonIfNot="
            + reasonIfNot
            + " reservationResources="
            + (plan == null ? List.of() : plan.resources())
            + " releaseResources="
            + (plan == null ? List.of() : plan.releaseResources())
            + " authorityBoundary="
            + (plan == null ? "-" : plan.authorityEnd())
            + " currentTokenState="
            + tokenState(plan == null ? "-" : plan.train(), token)
            + " destinationPresent="
            + (token != null && token.committedDestination().isPresent())
            + " destination="
            + destination
            + " currentNode="
            + (plan == null ? "-" : plan.currentNode())
            + " nextNode="
            + (plan == null ? "-" : plan.nextNode()));
  }

  String smartDispatchExecutorSkipReason(
      SmartWaitForPlanner.UnlockCandidate plan,
      ConfigManager.SmartDispatcherPlannerSettings config) {
    return smartDispatchExecutorSkipReason(plan, config, smartPlannerAction(plan));
  }

  private String smartDispatchExecutorSkipReason(
      SmartWaitForPlanner.UnlockCandidate plan,
      ConfigManager.SmartDispatcherPlannerSettings config,
      DispatchAction action) {
    if (plan == null || !plan.accepted()) {
      return "PLAN_NOT_ACCEPTED";
    }
    ConfigManager.SmartDispatcherPlannerSettings settings =
        config == null ? ConfigManager.SmartDispatcherPlannerSettings.defaults() : config;
    if (settings.mode() != SmartDispatcherPlannerMode.ENFORCE_MINIMAL_FORWARD) {
      return "CONFIG_NOT_EXECUTING_PLANNER";
    }
    if (!smartDispatcherActionRegistered(action)) {
      return "ACTION_NOT_REGISTERED";
    }
    if (smartDispatcherMode() != SmartDispatcherMode.ENFORCE) {
      return "GLOBAL_MODE_NOT_ENFORCE";
    }
    if (!smartDispatcherActionEffectPermitted(action)) {
      return "EFFECT_GATE_SUPPRESSED";
    }
    if (smartUnlockNoReleaseCooldownActive(plan, Instant.now())) {
      return "NO_RELEASE_COOLDOWN";
    }
    if (settings.oneActiveReservationPerCycle()
        && smartUnlockReservationsByCycle.containsKey(plan.cycleId())) {
      return "ACTIVE_RESERVATION_EXISTS";
    }
    List<OccupancyResource> resources = parsePlannerResources(plan.resources());
    if (resources.isEmpty()) {
      return "RESOURCE_WINDOW_INVALID";
    }
    if (!isHeadOnYieldPlan(plan)
        && singleRegionOppositeOrUnknownExternalBarrier(
            plan.train(), resources, plan.direction())) {
      return SimpleOccupancyManager.OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER;
    }
    if (plan.direction() == CorridorDirection.UNKNOWN) {
      return "INSUFFICIENT_DIRECTION_EVIDENCE";
    }
    if (settings.allowReverse()) {
      return "SAME_DIRECTION_FAILED";
    }
    if (resources.size() > settings.maxReservationResources()) {
      return "RESERVATION_RESOURCE_LIMIT_EXCEEDED";
    }
    if (!isHeadOnYieldPlan(plan) && plannerNode(plan.authorityEnd()).isEmpty()) {
      return "AUTHORITY_BOUNDARY_MISSING";
    }
    return "-";
  }

  private boolean smartUnlockNoReleaseCooldownActive(
      SmartWaitForPlanner.UnlockCandidate plan, Instant now) {
    if (plan == null) {
      return false;
    }
    Instant effectiveNow = now == null ? Instant.now() : now;
    Instant until =
        firstActiveSmartUnlockCooldown(
            effectiveNow,
            smartUnlockCooldownKey(plan.cycleId(), plan.planHash()),
            smartUnlockTrainCycleCooldownKey(plan.train(), plan.cycleId()));
    return until != null;
  }

  private Instant firstActiveSmartUnlockCooldown(Instant now, String... keys) {
    if (keys == null || keys.length == 0) {
      return null;
    }
    Instant effectiveNow = now == null ? Instant.now() : now;
    for (String key : keys) {
      if (key == null || key.isBlank()) {
        continue;
      }
      Instant until = smartUnlockNoReleaseCooldowns.get(key);
      if (until == null) {
        continue;
      }
      if (!effectiveNow.isBefore(until)) {
        smartUnlockNoReleaseCooldowns.remove(key, until);
        continue;
      }
      return until;
    }
    return null;
  }

  private static String smartUnlockCooldownKey(String cycleId, String planHash) {
    String cycle = cycleId == null || cycleId.isBlank() ? "-" : cycleId.trim();
    String hash = planHash == null || planHash.isBlank() ? "-" : planHash.trim();
    return "plan:" + cycle + ":" + hash;
  }

  private static String smartUnlockTrainCycleCooldownKey(String trainName, String cycleId) {
    String trainKey = normalizeTrainKey(trainName);
    String cycle = cycleId == null || cycleId.isBlank() ? "-" : cycleId.trim();
    return "train-cycle:" + (trainKey.isEmpty() ? "-" : trainKey) + ":" + cycle;
  }

  private void traceSmartDispatchExecutorSkipped(
      SmartWaitForPlanner.UnlockCandidate plan, String reason) {
    debugLogger.accept(
        "SMART_DISPATCH_EXECUTOR_SKIPPED planId="
            + (plan == null ? "-" : plan.planHash())
            + " cycleId="
            + (plan == null ? "-" : plan.cycleId())
            + " train="
            + (plan == null ? "-" : plan.train())
            + " reason="
            + (reason == null || reason.isBlank() ? "UNKNOWN_BUG" : reason));
  }

  /**
   * 输出 minimal forward unlock 从 planner 选择到物理信号发布之间的 apply 生命周期。
   *
   * <p>该 trace 不参与状态机推进，只把 token/destination、物理信号和 blocker release 证据放在同一条线上，避免把 {@code
   * TOKEN_ACTIVE} 误读为 physical PROCEED。
   */
  private void traceSmartUnlockPlanApply(
      SmartWaitForPlanner.UnlockCandidate plan,
      SmartUnlockReservation reservation,
      String applyPhase,
      String applyResult,
      boolean suppressedAsUnchanged,
      String failureReason) {
    String trainName = plan == null ? reservationTrain(reservation) : plan.train();
    SignalComputationTrace.TokenState tokenState =
        tokenState(trainName, movementToken(trainName).orElse(null));
    traceSmartUnlockPlanApply(
        plan,
        reservation,
        applyPhase,
        applyResult,
        "-",
        "-",
        "-",
        "unknown",
        "unknown",
        "-",
        tokenState,
        destinationPresent(trainName),
        false,
        "unknown",
        "unknown",
        "unknown",
        tokenState,
        suppressedAsUnchanged,
        failureReason);
  }

  private void traceSmartUnlockPlanApply(
      SmartUnlockReservation reservation,
      String applyPhase,
      String applyResult,
      SignalAspect requestedAspect,
      SignalAspect computedAspect,
      SignalAspect finalAspect,
      boolean physicalPublished,
      boolean publishSuppressed,
      String publicationGateReason,
      boolean recoverableHold,
      String blockerResourceReleased,
      String currentNodeChanged,
      String lastPassedGraphNodeChanged,
      SignalComputationTrace.TokenState movementTokenState,
      String failureReason) {
    String trainName = reservationTrain(reservation);
    SignalComputationTrace.TokenState tokenState =
        tokenState(trainName, movementToken(trainName).orElse(null));
    traceSmartUnlockPlanApply(
        null,
        reservation,
        applyPhase,
        applyResult,
        aspectText(requestedAspect),
        aspectText(computedAspect),
        aspectText(finalAspect),
        String.valueOf(physicalPublished),
        String.valueOf(publishSuppressed),
        publicationGateReason,
        tokenState,
        destinationPresent(trainName),
        recoverableHold,
        blockerResourceReleased,
        currentNodeChanged,
        lastPassedGraphNodeChanged,
        movementTokenState == null ? tokenState : movementTokenState,
        false,
        failureReason);
  }

  private void traceSmartUnlockPlanApply(
      SmartWaitForPlanner.UnlockCandidate plan,
      SmartUnlockReservation reservation,
      String applyPhase,
      String applyResult,
      String requestedAspect,
      String computedAspect,
      String finalAspect,
      String physicalPublished,
      String publishSuppressed,
      String publicationGateReason,
      SignalComputationTrace.TokenState authorityTokenState,
      boolean destinationPresent,
      boolean recoverableHold,
      String blockerResourceReleased,
      String currentNodeChanged,
      String lastPassedGraphNodeChanged,
      SignalComputationTrace.TokenState movementTokenState,
      boolean suppressedAsUnchanged,
      String failureReason) {
    String trainName = plan == null ? reservationTrain(reservation) : plan.train();
    debugLogger.accept(
        "SMART_UNLOCK_PLAN_APPLY cycleId="
            + safeTraceValue(plan == null ? reservationCycle(reservation) : plan.cycleId())
            + " reservationId="
            + safeTraceValue(reservation == null ? "-" : reservation.reservationId())
            + " selectedTrain="
            + safeTraceValue(trainName)
            + " planKind="
            + safeTraceValue(planKind(plan, reservation))
            + " targetResource="
            + safeTraceValue(targetResource(plan, reservation))
            + " authorityEnd="
            + safeTraceValue(authorityEndText(plan, reservation))
            + " applyPhase="
            + safeTraceValue(applyPhase)
            + " applyResult="
            + safeTraceValue(applyResult)
            + " requestedAspect="
            + safeTraceValue(requestedAspect)
            + " computedAspect="
            + safeTraceValue(computedAspect)
            + " finalAspect="
            + safeTraceValue(finalAspect)
            + " physicalPublished="
            + safeTraceValue(physicalPublished)
            + " publishSuppressed="
            + safeTraceValue(publishSuppressed)
            + " publicationGateReason="
            + safeTraceValue(publicationGateReason)
            + " authorityTokenState="
            + (authorityTokenState == null
                ? SignalComputationTrace.TokenState.NONE
                : authorityTokenState)
            + " destinationPresent="
            + destinationPresent
            + " recoverableHold="
            + recoverableHold
            + " blockerResourceReleased="
            + safeTraceValue(blockerResourceReleased)
            + " currentNodeChanged="
            + safeTraceValue(currentNodeChanged)
            + " lastPassedGraphNodeChanged="
            + safeTraceValue(lastPassedGraphNodeChanged)
            + " movementTokenState="
            + (movementTokenState == null
                ? SignalComputationTrace.TokenState.NONE
                : movementTokenState)
            + " planHash="
            + safeTraceValue(plan == null ? reservationPlanHash(reservation) : plan.planHash())
            + " suppressedAsUnchanged="
            + suppressedAsUnchanged
            + " failureReason="
            + safeTraceValue(failureReason));
  }

  private void traceMutualConflictOwnerSet(
      SmartWaitForPlanner.PlannerInput input,
      SmartWaitForPlanner.UnlockCandidate selectedPlan,
      boolean suppressedAsUnchanged) {
    if (input == null || selectedPlan == null) {
      return;
    }
    Set<String> resources = new LinkedHashSet<>();
    resources.addAll(selectedPlan.resources());
    resources.addAll(selectedPlan.releaseResources());
    for (String resource : resources) {
      if (resource == null || resource.isBlank() || "-".equals(resource)) {
        continue;
      }
      List<SmartWaitForPlanner.InputEdge> edges =
          input.edges().stream().filter(edge -> resource.equals(edge.resource())).toList();
      if (edges.isEmpty()) {
        continue;
      }
      Set<String> owners = new LinkedHashSet<>();
      Map<String, String> ownerRoles = new LinkedHashMap<>();
      Map<String, String> ownerDirections = new LinkedHashMap<>();
      Map<String, String> ownerRequestInputTypes = new LinkedHashMap<>();
      Map<String, String> ownerRouteIndexes = new LinkedHashMap<>();
      Map<String, String> ownerPriorities = new LinkedHashMap<>();
      Map<String, String> ownerPhysicalFootprints = new LinkedHashMap<>();
      Map<String, String> ownerReservedAuthorities = new LinkedHashMap<>();
      for (SmartWaitForPlanner.InputEdge edge : edges) {
        addPlannerOwner(
            owners,
            ownerRoles,
            ownerDirections,
            ownerRequestInputTypes,
            ownerRouteIndexes,
            ownerPriorities,
            ownerPhysicalFootprints,
            ownerReservedAuthorities,
            input,
            edge.blockerTrain(),
            edge.role(),
            edge.direction(),
            edge.intent());
        addPlannerOwner(
            owners,
            ownerRoles,
            ownerDirections,
            ownerRequestInputTypes,
            ownerRouteIndexes,
            ownerPriorities,
            ownerPhysicalFootprints,
            ownerReservedAuthorities,
            input,
            edge.blockedTrain(),
            "REQUESTER",
            edge.direction(),
            edge.source());
      }
      Set<String> nonSelectedOwners = new LinkedHashSet<>(owners);
      nonSelectedOwners.removeIf(
          owner -> TrainNameNormalizer.sameLogicalTrain(owner, selectedPlan.train()));
      debugLogger.accept(
          "SMART_MUTUAL_CONFLICT_OWNER_SET cycleId="
              + safeTraceValue(selectedPlan.cycleId())
              + " resource="
              + safeTraceValue(resource)
              + " owners="
              + owners
              + " ownerRoles="
              + ownerRoles
              + " ownerDirections="
              + ownerDirections
              + " ownerRequestInputTypes="
              + ownerRequestInputTypes
              + " ownerRouteIndexes="
              + ownerRouteIndexes
              + " ownerPriorities="
              + ownerPriorities
              + " ownerPhysicalFootprints="
              + ownerPhysicalFootprints
              + " ownerReservedAuthorities="
              + ownerReservedAuthorities
              + " selectedOwner="
              + safeTraceValue(selectedPlan.train())
              + " nonSelectedOwners="
              + nonSelectedOwners
              + " tieBreakReason="
              + "score:"
              + selectedPlan.score()
              + ":"
              + safeTraceValue(selectedPlan.recommendation())
              + " selectedPlanKind="
              + selectedPlan.kind()
              + " canDowngradeNonSelectedReservationDryRun=unknown"
              + " noDowngradeReason=diagnostic-only"
              + " suppressedAsUnchanged="
              + suppressedAsUnchanged);
    }
  }

  private void addPlannerOwner(
      Set<String> owners,
      Map<String, String> ownerRoles,
      Map<String, String> ownerDirections,
      Map<String, String> ownerRequestInputTypes,
      Map<String, String> ownerRouteIndexes,
      Map<String, String> ownerPriorities,
      Map<String, String> ownerPhysicalFootprints,
      Map<String, String> ownerReservedAuthorities,
      SmartWaitForPlanner.PlannerInput input,
      String owner,
      String role,
      CorridorDirection direction,
      String requestInputType) {
    String safeOwner = safeTraceValue(owner);
    if ("-".equals(safeOwner)) {
      return;
    }
    owners.add(safeOwner);
    SmartWaitForPlanner.TrainState state = input.trainStates().get(safeOwner);
    ownerRoles.put(safeOwner, safeTraceValue(role));
    ownerDirections.put(
        safeOwner, String.valueOf(direction == null ? CorridorDirection.UNKNOWN : direction));
    ownerRequestInputTypes.put(safeOwner, safeTraceValue(requestInputType));
    ownerRouteIndexes.put(safeOwner, state == null ? "-" : String.valueOf(state.currentIndex()));
    ownerPriorities.put(safeOwner, "unknown");
    ownerPhysicalFootprints.put(safeOwner, "unknown");
    ownerReservedAuthorities.put(safeOwner, "unknown");
  }

  private static String reservationTrain(SmartUnlockReservation reservation) {
    return reservation == null ? "-" : reservation.trainName();
  }

  private static String reservationCycle(SmartUnlockReservation reservation) {
    return reservation == null ? "-" : reservation.cycleId();
  }

  private static String reservationPlanHash(SmartUnlockReservation reservation) {
    return reservation == null ? "-" : reservation.planHash();
  }

  private static String planKind(
      SmartWaitForPlanner.UnlockCandidate plan, SmartUnlockReservation reservation) {
    if (plan != null && plan.kind() != null) {
      return plan.kind().name();
    }
    return reservation == null ? "-" : reservation.planKind();
  }

  private static String targetResource(
      SmartWaitForPlanner.UnlockCandidate plan, SmartUnlockReservation reservation) {
    if (plan != null && !plan.resources().isEmpty()) {
      return plan.resources().get(0);
    }
    if (reservation != null && !reservation.resources().isEmpty()) {
      return reservation.resources().get(0).toString();
    }
    return "-";
  }

  private static String authorityEndText(
      SmartWaitForPlanner.UnlockCandidate plan, SmartUnlockReservation reservation) {
    if (plan != null && !plan.authorityEnd().isBlank()) {
      return plan.authorityEnd();
    }
    return reservation == null || reservation.authorityEnd() == null
        ? "-"
        : reservation.authorityEnd().value();
  }

  private static String aspectText(SignalAspect aspect) {
    return aspect == null ? "-" : aspect.name();
  }

  private void traceSmartUnlockReservationWouldCreate(
      SmartWaitForPlanner.UnlockCandidate plan, String reason) {
    traceSmartUnlockReservationWouldCreate(
        plan, DispatchAction.ACQUIRE_SPECULATIVE_UNLOCK_RESERVATION, reason);
  }

  private void traceSmartUnlockReservationWouldCreate(
      SmartWaitForPlanner.UnlockCandidate plan, DispatchAction action, String reason) {
    debugLogger.accept(
        "SMART_UNLOCK_RESERVATION_WOULD_CREATE action="
            + (action == null ? DispatchAction.ACQUIRE_SPECULATIVE_UNLOCK_RESERVATION : action)
            + " train="
            + (plan == null ? "-" : plan.train())
            + " cycleId="
            + (plan == null ? "-" : plan.cycleId())
            + " mode="
            + smartDispatcherMode()
            + " wouldMutate=false didMutate=false reason="
            + (reason == null || reason.isBlank() ? "mode-gate" : reason));
  }

  private static boolean isHeadOnYieldPlan(SmartWaitForPlanner.UnlockCandidate plan) {
    return plan != null && plan.kind() == SmartWaitForPlanner.CandidateKind.YIELD_TO_HEAD_ON;
  }

  private static DispatchAction smartPlannerAction(SmartWaitForPlanner.UnlockCandidate plan) {
    return isHeadOnYieldPlan(plan)
        ? DispatchAction.SMART_HEAD_ON_YIELD
        : DispatchAction.ACQUIRE_SPECULATIVE_UNLOCK_RESERVATION;
  }

  private void executeSmartHeadOnYield(
      SmartWaitForPlanner.UnlockCandidate plan,
      ConfigManager.SmartDispatcherPlannerSettings config,
      Instant now) {
    DispatchAction action = DispatchAction.SMART_HEAD_ON_YIELD;
    if (!smartDispatcherRegisteredActionAllowed(
        plan == null ? "-" : plan.train(), "smart-head-on-yield", action)) {
      traceSmartUnlockReservationWouldCreate(plan, action, "EFFECT_GATE_SUPPRESSED");
      traceSmartDispatchExecutorSkipped(plan, "EFFECT_GATE_SUPPRESSED");
      return;
    }
    if (plan == null || !plan.accepted()) {
      traceSmartDispatchExecutorSkipped(plan, "PLAN_NOT_ACCEPTED");
      return;
    }
    if (smartUnlockNoReleaseCooldownActive(plan, now)) {
      traceSmartDispatchExecutorSkipped(plan, "NO_RELEASE_COOLDOWN");
      return;
    }
    List<OccupancyResource> resources = parsePlannerResources(plan.resources());
    if (resources.isEmpty()) {
      traceSmartDispatchExecutorSkipped(plan, "RESOURCE_WINDOW_INVALID");
      return;
    }
    if (plan.direction() == CorridorDirection.UNKNOWN) {
      traceSmartDispatchExecutorSkipped(plan, "INSUFFICIENT_DIRECTION_EVIDENCE");
      return;
    }
    if (resources.size() > config.maxReservationResources()) {
      traceSmartDispatchExecutorSkipped(plan, "RESERVATION_RESOURCE_LIMIT_EXCEEDED");
      return;
    }
    int releasedClaims = 0;
    releasedClaims +=
        occupancyManager.releaseResourcesByTrainAndRole(
            plan.train(), resources, ClaimRole.UNLOCK_RESERVATION);
    releasedClaims +=
        occupancyManager.releaseResourcesByTrainAndRole(
            plan.train(), resources, ClaimRole.LOOKAHEAD_PREVIEW);
    releasedClaims +=
        occupancyManager.releaseResourcesByTrainAndRole(
            plan.train(), resources, ClaimRole.QUEUE_POSITION);
    int removedQueues = 0;
    if (occupancyManager instanceof OccupancyQueueSupport queueSupport) {
      removedQueues = queueSupport.removeQueueEntries(plan.train(), resources);
    }
    Instant effectiveNow = now == null ? Instant.now() : now;
    smartUnlockNoReleaseCooldowns.put(
        smartUnlockTrainCycleCooldownKey(plan.train(), plan.cycleId()),
        effectiveNow.plus(SMART_UNLOCK_NO_RELEASE_COOLDOWN));
    debugLogger.accept(
        "SMART_HEAD_ON_YIELD_APPLIED train="
            + plan.train()
            + " cycleId="
            + plan.cycleId()
            + " resources="
            + resources
            + " releasedNonPhysicalClaims="
            + releasedClaims
            + " removedQueueEntries="
            + removedQueues
            + " physicalClaimsReleased=0"
            + " destinationMutated=false"
            + " tokenInvalidated=false"
            + " movementAuthorityIssued=false"
            + " occupancyMutated="
            + (releasedClaims + removedQueues > 0));
  }

  private void commitSmartUnlockReservation(
      SmartUnlockReservation reservation, SmartWaitForPlanner.UnlockCandidate plan, Instant now) {
    List<OccupancyResource> claimResources =
        reservation.resources().stream()
            .filter(resource -> !hasSelfClaim(resource, reservation.trainName()))
            .toList();
    OccupancyRequest request =
        smartUnlockReservationRequest(
            reservation.trainName(),
            claimResources,
            plan.direction(),
            now == null ? Instant.now() : now);
    if (singleRegionOppositeOrUnknownExternalBarrier(
        reservation.trainName(), reservation.resources(), plan.direction())) {
      traceSingleRegionHardBarrier(
          reservation.trainName(),
          DispatchAction.ISSUE_UNLOCK_AUTHORITY,
          "smart-unlock-authority",
          reservation.resources());
      traceSmartUnlockAuthorityRejected(
          reservation, SimpleOccupancyManager.OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER);
      rollbackSmartUnlockReservation(
          reservation, SimpleOccupancyManager.OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER, true);
      return;
    }
    if (!claimResources.isEmpty()) {
      if (!(occupancyManager instanceof OccupancyPreviewSupport previewSupport)) {
        debugLogger.accept(
            "SMART_UNLOCK_RESERVATION_REJECTED reservationId="
                + reservation.reservationId()
                + " train="
                + reservation.trainName()
                + " reason=preview-support-missing");
        traceSmartUnlockAuthorityRejected(reservation, "preview-support-missing");
        rollbackSmartUnlockReservation(reservation, "preview-support-missing", false);
        return;
      }
      OccupancyDecision preview = previewSupport.canEnterPreview(request);
      if (!preview.allowed()) {
        debugLogger.accept(
            "SMART_UNLOCK_RESERVATION_REJECTED reservationId="
                + reservation.reservationId()
                + " train="
                + reservation.trainName()
                + " reason=resources-not-available blockers="
                + preview.blockers().size());
        traceSmartUnlockAuthorityRejected(reservation, "resources-not-available");
        rollbackSmartUnlockReservation(reservation, "commit-preview-rejected", false);
        return;
      }
      OccupancyDecision acquired = occupancyManager.acquire(request);
      if (!acquired.allowed()) {
        debugLogger.accept(
            "SMART_UNLOCK_RESERVATION_REJECTED reservationId="
                + reservation.reservationId()
                + " train="
                + reservation.trainName()
                + " reason=commit-acquire-rejected blockers="
                + acquired.blockers().size());
        traceSmartUnlockAuthorityRejected(reservation, "commit-acquire-rejected");
        rollbackSmartUnlockReservation(reservation, "commit-acquire-rejected", false);
        return;
      }
    } else {
      debugLogger.accept(
          "SMART_SPECULATIVE_CLAIM_NOT_BLOCKING_NORMAL_ADMISSION reservationId="
              + reservation.reservationId()
              + " train="
              + reservation.trainName()
              + " reason=selected-train-already-holds-window");
    }
    if (!smartDispatcherRegisteredActionAllowed(
        reservation.trainName(), "smart-unlock-authority", DispatchAction.ISSUE_UNLOCK_AUTHORITY)) {
      traceSmartUnlockAuthorityRejected(reservation, "issue-authority-effect-gate-suppressed");
      rollbackSmartUnlockReservation(reservation, "issue-authority-effect-gate-suppressed", false);
      return;
    }
    if (singleRegionOppositeOrUnknownExternalBarrier(
        reservation.trainName(), reservation.resources(), plan.direction())) {
      traceSingleRegionHardBarrier(
          reservation.trainName(),
          DispatchAction.ISSUE_UNLOCK_AUTHORITY,
          "smart-unlock-authority-pre-token",
          reservation.resources());
      traceSmartUnlockAuthorityRejected(
          reservation, SimpleOccupancyManager.OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER);
      rollbackSmartUnlockReservation(
          reservation,
          SimpleOccupancyManager.OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER,
          false);
      return;
    }
    MovementAuthorizationToken token =
        issueMovementAuthorizationToken(
            reservation.trainName(),
            plannerNode(plan.currentNode()).orElse(null),
            reservation.authorityEnd(),
            smartUnlockReservationRequest(
                reservation.trainName(),
                reservation.resources(),
                plan.direction(),
                now == null ? Instant.now() : now),
            SignalAspect.PROCEED,
            now == null ? Instant.now() : now);
    String destination = resolveDestinationName(reservation.authorityEnd());
    if (destination == null || destination.isBlank()) {
      debugLogger.accept(
          "SMART_UNLOCK_AUTHORITY_REJECTED train="
              + reservation.trainName()
              + " reservationId="
              + reservation.reservationId()
              + " reason=destination-boundary-unavailable");
      traceSmartUnlockAuthorityRejected(reservation, "destination-boundary-unavailable");
      rollbackSmartUnlockReservation(reservation, "authority-destination-unavailable", true);
      return;
    }
    if (!plan.nextNode().equals("-")
        && !plan.nextNode().equals(reservation.authorityEnd().value())) {
      debugLogger.accept(
          "SMART_AUTHORITY_WINDOW_DESTINATION_MISMATCH train="
              + reservation.trainName()
              + " action=clamp-to-valid-boundary requested="
              + plan.nextNode()
              + " authorityEnd="
              + reservation.authorityEnd().value());
    }
    if (!activateMovementAuthorizationTokenRetainingInhibitor(
        reservation.trainName(), token, destination)) {
      debugLogger.accept(
          "SMART_UNLOCK_AUTHORITY_REJECTED train="
              + reservation.trainName()
              + " reservationId="
              + reservation.reservationId()
              + " reason=token-activation-failed");
      traceSmartUnlockAuthorityRejected(reservation, "token-activation-failed");
      rollbackSmartUnlockReservation(reservation, "token-activation-failed", true);
      return;
    }
    SmartUnlockReservation committed = reservation.committed(token.claimVersion());
    smartUnlockReservationsByCycle.put(committed.cycleId(), committed);
    smartUnlockReservationsByTrain.put(normalizeTrainKey(committed.trainName()), committed);
    debugLogger.accept(
        "SMART_UNLOCK_RESERVATION_COMMITTED reservationId="
            + committed.reservationId()
            + " train="
            + committed.trainName()
            + " cycleId="
            + committed.cycleId()
            + " resourceCount="
            + committed.resources().size());
    debugLogger.accept(
        "SMART_UNLOCK_AUTHORITY_ISSUED train="
            + committed.trainName()
            + " reservationId="
            + committed.reservationId()
            + " authorityEnd="
            + committed.authorityEnd().value()
            + " resourceCount="
            + committed.resources().size());
    traceSmartUnlockPlanApply(plan, committed, "authority-issued", "authority-issued", false, "-");
  }

  private OccupancyRequest smartUnlockReservationRequest(
      String trainName,
      List<OccupancyResource> resources,
      CorridorDirection direction,
      Instant now) {
    Map<String, CorridorDirection> directions = new LinkedHashMap<>();
    Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>();
    for (OccupancyResource resource : resources) {
      if (resource == null) {
        continue;
      }
      intents.put(resource, ResourceIntent.UNLOCK_RESERVATION);
      if (resource.kind() == ResourceKind.CONFLICT && direction != CorridorDirection.UNKNOWN) {
        directions.put(resource.key(), direction);
      }
    }
    return new OccupancyRequest(
        trainName,
        Optional.empty(),
        now,
        resources,
        directions,
        Map.of(),
        0,
        AuthorizationPurpose.UNLOCK_RESERVATION,
        Map.of(),
        intents);
  }

  private void observeSmartUnlockReservations(Instant now) {
    if (smartUnlockReservationsByCycle.isEmpty()) {
      return;
    }
    if (!smartDispatcherRegisteredActionAllowed(
        "-", "smart-unlock-observer", DispatchAction.RELEASE_SPECULATIVE_UNLOCK_RESERVATION)) {
      debugLogger.accept(
          "SMART_UNLOCK_RESERVATION_OBSERVER_SUPPRESSED mode="
              + smartDispatcherMode()
              + " action="
              + DispatchAction.RELEASE_SPECULATIVE_UNLOCK_RESERVATION
              + " activeReservations="
              + smartUnlockReservationsByCycle.size()
              + " wouldMutate=false didMutate=false reason=mode-or-effect-gate");
      return;
    }
    long tick = currentSignalTraceTick();
    List<SmartUnlockReservation> reservations =
        List.copyOf(smartUnlockReservationsByCycle.values());
    for (SmartUnlockReservation reservation : reservations) {
      if (reservation == null) {
        continue;
      }
      SmartRecoveryInput state =
          smartRecoveryInput(reservation.trainName(), Duration.ZERO, SignalAspect.STOP);
      boolean nodeChanged = !nodeText(state.currentNode()).equals(reservation.initialCurrentNode());
      boolean lastPassedChanged =
          !state.lastPassedGraphNode().equals(reservation.initialLastPassedGraphNode());
      boolean blockersReleased = expectedSmartUnlockBlockersReleased(reservation, now);
      boolean tokenInvalid =
          state.movementTokenState() == SignalComputationTrace.TokenState.INVALID;
      debugLogger.accept(
          "SMART_UNLOCK_PROGRESS_OBSERVED reservationId="
              + reservation.reservationId()
              + " train="
              + reservation.trainName()
              + " currentNodeChanged="
              + nodeChanged
              + " lastPassedGraphNodeChanged="
              + lastPassedChanged
              + " blockerResourceReleased="
              + blockersReleased
              + " movementTokenState="
              + state.movementTokenState());
      traceSmartUnlockPlanApply(
          reservation,
          "progress-check",
          blockersReleased ? "blocker-released" : "no-progress",
          SignalAspect.STOP,
          SignalAspect.STOP,
          SignalAspect.STOP,
          false,
          true,
          "observer",
          false,
          String.valueOf(blockersReleased),
          String.valueOf(nodeChanged),
          String.valueOf(lastPassedChanged),
          state.movementTokenState(),
          blockersReleased ? "-" : "blocker-not-released");
      if (reservation.committed() && tokenInvalid) {
        debugLogger.accept(
            "SMART_UNLOCK_AUTHORITY_INVALID_NO_RELEASE reservationId="
                + reservation.reservationId()
                + " train="
                + reservation.trainName()
                + " blockerResourceReleased="
                + blockersReleased
                + " currentNodeChanged="
                + nodeChanged
                + " lastPassedGraphNodeChanged="
                + lastPassedChanged);
        rememberSmartUnlockNoReleaseTimeout(reservation, now);
        rollbackSmartUnlockReservation(reservation, "authority-invalid-no-release", true);
        continue;
      }
      if (reservation.committed() && blockersReleased) {
        int released =
            occupancyManager.releaseResourcesByTrainAndRole(
                reservation.trainName(), reservation.resources(), ClaimRole.UNLOCK_RESERVATION);
        smartUnlockReservationsByCycle.remove(reservation.cycleId(), reservation);
        smartUnlockReservationsByTrain.remove(
            normalizeTrainKey(reservation.trainName()), reservation);
        rememberSmartUnlockBlockerRelease(reservation, now);
        debugLogger.accept(
            "SMART_UNLOCK_SUCCESS reservationId="
                + reservation.reservationId()
                + " train="
                + reservation.trainName()
                + " releasedReservationClaims="
                + released
                + " cycleBroken="
                + blockersReleased);
        debugLogger.accept(
            "SMART_UNLOCK_RESERVATION_SUCCESS reservationId="
                + reservation.reservationId()
                + " train="
                + reservation.trainName()
                + " releasedReservationClaims="
                + released
                + " cycleBroken="
                + blockersReleased);
        continue;
      }
      if (reservation.committed() && (nodeChanged || lastPassedChanged)) {
        debugLogger.accept(
            "SMART_UNLOCK_PROGRESS_NO_RELEASE reservationId="
                + reservation.reservationId()
                + " train="
                + reservation.trainName()
                + " currentNodeChanged="
                + nodeChanged
                + " lastPassedGraphNodeChanged="
                + lastPassedChanged
                + " blockerResourceReleased=false"
                + " movementTokenState="
                + state.movementTokenState());
      }
      if (reservation.expired(tick)) {
        debugLogger.accept(
            "SMART_UNLOCK_RESERVATION_EXPIRED reservationId="
                + reservation.reservationId()
                + " train="
                + reservation.trainName()
                + " ttl="
                + reservation.ttlTicks());
        debugLogger.accept(
            "SMART_UNLOCK_FAILED reservationId="
                + reservation.reservationId()
                + " train="
                + reservation.trainName()
                + " reason=ttl-expired sameBlockersRemain="
                + !blockersReleased);
        if (!blockersReleased) {
          debugLogger.accept(
              "SMART_UNLOCK_RESERVATION_NO_RELEASE_TIMEOUT reservationId="
                  + reservation.reservationId()
                  + " train="
                  + reservation.trainName()
                  + " cycleId="
                  + reservation.cycleId()
                  + " selectedPlanHash="
                  + reservation.planHash()
                  + " blockerResourceReleased=false"
                  + " currentNodeChanged="
                  + nodeChanged
                  + " lastPassedGraphNodeChanged="
                  + lastPassedChanged);
          rememberSmartUnlockNoReleaseTimeout(reservation, now);
          rollbackSmartUnlockReservation(reservation, "no-release-timeout", true);
        } else {
          rollbackSmartUnlockReservation(reservation, "ttl-expired", true);
        }
      }
    }
  }

  private void rememberSmartUnlockBlockerRelease(SmartUnlockReservation reservation, Instant now) {
    if (reservation == null) {
      return;
    }
    Instant effectiveNow = now == null ? Instant.now() : now;
    rememberTrainInstant(smartUnlockBlockerReleaseAt, reservation.trainName(), effectiveNow);
    for (String blockedTrain : reservation.initiallyBlockedTrains()) {
      rememberTrainInstant(smartUnlockBlockerReleaseAt, blockedTrain, effectiveNow);
    }
  }

  private void rememberSmartUnlockNoReleaseTimeout(
      SmartUnlockReservation reservation, Instant now) {
    if (reservation == null) {
      return;
    }
    Instant effectiveNow = now == null ? Instant.now() : now;
    rememberTrainInstant(smartUnlockNoReleaseTimeouts, reservation.trainName(), effectiveNow);
    Instant until = effectiveNow.plus(SMART_UNLOCK_NO_RELEASE_COOLDOWN);
    smartUnlockNoReleaseCooldowns.put(
        smartUnlockCooldownKey(reservation.cycleId(), reservation.planHash()), until);
    smartUnlockNoReleaseCooldowns.put(
        smartUnlockTrainCycleCooldownKey(reservation.trainName(), reservation.cycleId()), until);
  }

  private static void rememberTrainInstant(
      java.util.concurrent.ConcurrentMap<String, Instant> target, String trainName, Instant at) {
    if (target == null || trainName == null || trainName.isBlank() || at == null) {
      return;
    }
    String key = normalizeTrainKey(trainName);
    if (!key.isEmpty()) {
      target.put(key, at);
    }
  }

  private boolean expectedSmartUnlockBlockersReleased(
      SmartUnlockReservation reservation, Instant now) {
    if (reservation.initiallyBlockedTrains().isEmpty()) {
      return false;
    }
    Set<String> expectedResources = new LinkedHashSet<>(reservation.expectedReleasedResources());
    if (expectedResources.isEmpty()) {
      traceSmartUnlockReleaseEvidenceUnknown(reservation, "-", "EXPECTED_RESOURCES_MISSING", now);
      return false;
    }
    String trainKey = normalizeTrainKey(reservation.trainName());
    for (String blockedTrain : reservation.initiallyBlockedTrains()) {
      String blockedKey = normalizeTrainKey(blockedTrain);
      BlockerSnapshot snapshot = blockerSnapshots.get(blockedKey);
      if (snapshot == null) {
        traceSmartUnlockReleaseEvidenceUnknown(
            reservation, blockedTrain, "BLOCKER_SNAPSHOT_MISSING", now);
        return false;
      }
      Instant effectiveNow = now == null ? Instant.now() : now;
      if (snapshot.sampledAt().isBefore(effectiveNow.minus(BLOCKER_SNAPSHOT_TTL))) {
        blockerSnapshots.remove(blockedKey, snapshot);
        traceSmartUnlockReleaseEvidenceUnknown(
            reservation, blockedTrain, "BLOCKER_SNAPSHOT_STALE", now);
        return false;
      }
      for (DeadlockBlockerInfo blocker : snapshot.blockers()) {
        if (blocker == null) {
          continue;
        }
        boolean sameBlocker = normalizeTrainKey(blocker.trainName()).equals(trainKey);
        if (sameBlocker && (blocker.resourceKey().isBlank() || "-".equals(blocker.resourceKey()))) {
          traceSmartUnlockReleaseEvidenceUnknown(
              reservation, blockedTrain, "BLOCKER_RESOURCE_UNKNOWN", now);
          return false;
        }
        if (sameBlocker && expectedResources.contains(blocker.resourceKey())) {
          return false;
        }
      }
    }
    return true;
  }

  private void traceSmartUnlockReleaseEvidenceUnknown(
      SmartUnlockReservation reservation, String blockedTrain, String reason, Instant now) {
    if (reservation == null) {
      return;
    }
    debugLogger.accept(
        "SMART_UNLOCK_RELEASE_EVIDENCE_UNKNOWN reservationId="
            + reservation.reservationId()
            + " train="
            + reservation.trainName()
            + " blockedTrain="
            + (blockedTrain == null || blockedTrain.isBlank() ? "-" : blockedTrain)
            + " cycleId="
            + reservation.cycleId()
            + " selectedPlanHash="
            + reservation.planHash()
            + " reason="
            + (reason == null || reason.isBlank() ? "UNKNOWN" : reason)
            + " sampledAt="
            + (now == null ? Instant.now() : now));
  }

  private void rollbackSmartUnlockReservation(
      SmartUnlockReservation reservation, String reason, boolean clearToken) {
    if (reservation == null) {
      return;
    }
    debugLogger.accept(
        "SMART_UNLOCK_ROLLBACK_STARTED reservationId="
            + reservation.reservationId()
            + " train="
            + reservation.trainName()
            + " reason="
            + (reason == null || reason.isBlank() ? "-" : reason));
    traceSmartUnlockPlanApply(
        reservation,
        "rollback",
        "rolled-back",
        SignalAspect.STOP,
        SignalAspect.STOP,
        SignalAspect.STOP,
        false,
        true,
        "rollback",
        false,
        "unknown",
        "unknown",
        "unknown",
        tokenState(reservation.trainName(), movementToken(reservation.trainName()).orElse(null)),
        reason);
    int released =
        occupancyManager.releaseResourcesByTrainAndRole(
            reservation.trainName(), reservation.resources(), ClaimRole.UNLOCK_RESERVATION);
    if (clearToken && reservation.tokenClaimVersion() >= 0L) {
      String key = normalizeTrainKey(reservation.trainName());
      MovementAuthorizationToken current = movementAuthorizationTokens.get(key);
      if (current != null && current.claimVersion() == reservation.tokenClaimVersion()) {
        movementAuthorizationTokens.remove(key, current);
      }
    }
    smartUnlockReservationsByCycle.remove(reservation.cycleId(), reservation);
    smartUnlockReservationsByTrain.remove(normalizeTrainKey(reservation.trainName()), reservation);
    debugLogger.accept(
        "SMART_UNLOCK_RESERVATION_ROLLED_BACK reservationId="
            + reservation.reservationId()
            + " train="
            + reservation.trainName()
            + " releasedReservationClaims="
            + released);
    debugLogger.accept(
        "SMART_UNLOCK_RESERVATION_ROLLBACK reservationId="
            + reservation.reservationId()
            + " train="
            + reservation.trainName()
            + " releasedReservationClaims="
            + released
            + " reason="
            + (reason == null || reason.isBlank() ? "-" : reason));
    debugLogger.accept(
        "SMART_UNLOCK_ROLLBACK_DONE reservationId="
            + reservation.reservationId()
            + " train="
            + reservation.trainName());
  }

  private void traceSmartUnlockAuthorityRejected(
      SmartUnlockReservation reservation, String reason) {
    if (reservation == null) {
      return;
    }
    debugLogger.accept(
        "SMART_UNLOCK_AUTHORITY_REJECTED train="
            + reservation.trainName()
            + " reservationId="
            + reservation.reservationId()
            + " reason="
            + (reason == null || reason.isBlank() ? "UNKNOWN_BUG" : reason)
            + " authorityEnd="
            + (reservation.authorityEnd() == null ? "-" : reservation.authorityEnd().value())
            + " resourceCount="
            + reservation.resources().size());
  }

  private List<OccupancyResource> parsePlannerResources(List<String> resources) {
    if (resources == null || resources.isEmpty()) {
      return List.of();
    }
    List<OccupancyResource> parsed = new ArrayList<>();
    for (String resource : resources) {
      parsePlannerResource(resource).ifPresent(parsed::add);
    }
    return List.copyOf(parsed);
  }

  private Optional<OccupancyResource> parsePlannerResource(String value) {
    if (value == null || value.isBlank() || !value.contains(":")) {
      return Optional.empty();
    }
    String kind = value.substring(0, value.indexOf(':')).trim();
    String key = value.substring(value.indexOf(':') + 1).trim();
    if (key.isBlank()) {
      return Optional.empty();
    }
    try {
      ResourceKind resourceKind = ResourceKind.valueOf(kind.toUpperCase(Locale.ROOT));
      return Optional.of(new OccupancyResource(resourceKind, key));
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
  }

  private Optional<NodeId> plannerNode(String raw) {
    if (raw == null || raw.isBlank() || "-".equals(raw.trim())) {
      return Optional.empty();
    }
    return Optional.of(NodeId.of(raw.trim()));
  }

  private boolean hasSelfClaim(OccupancyResource resource, String trainName) {
    if (resource == null || trainName == null || trainName.isBlank() || occupancyManager == null) {
      return false;
    }
    for (OccupancyClaim claim : occupancyManager.snapshotClaims()) {
      if (claim == null || claim.resource() == null) {
        continue;
      }
      if (claim.resource().equals(resource)
          && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
        return true;
      }
    }
    return false;
  }

  private List<String> initiallyBlockedTrains(String blockerTrain, List<String> resources) {
    Set<String> result = new LinkedHashSet<>();
    Set<String> expectedResources = new LinkedHashSet<>(resources == null ? List.of() : resources);
    String blockerKey = normalizeTrainKey(blockerTrain);
    for (Map.Entry<String, BlockerSnapshot> entry : blockerSnapshots.entrySet()) {
      BlockerSnapshot snapshot = entry.getValue();
      if (snapshot == null) {
        continue;
      }
      for (DeadlockBlockerInfo blocker : snapshot.blockers()) {
        if (blocker == null) {
          continue;
        }
        if (normalizeTrainKey(blocker.trainName()).equals(blockerKey)
            && expectedResources.contains(blocker.resourceKey())) {
          result.add(entry.getKey());
        }
      }
    }
    return List.copyOf(result);
  }

  private SignalComputationTrace.Builder signalTrace(
      String trainName,
      TrainProperties properties,
      SignalComputationTrace.Source source,
      SignalAspect previousAspect,
      SignalAspect newAspect,
      String reason) {
    MovementAuthorizationToken token = movementToken(trainName).orElse(null);
    String destination = properties == null ? "" : readDestination(properties);
    return SignalComputationTrace.builder(
            trainName,
            properties == null ? trainName : properties.getTrainName(),
            source,
            newAspect)
        .previousAspect(previousAspect)
        .primaryReason(reason)
        .field("progressVersion", progressRegistry.version())
        .field("occupancyVersion", occupancyVersion())
        .field("dirtyEventPending", dirtyEventSignals.containsKey(normalizeTrainKey(trainName)))
        .field("eventCoalesced", coalescedEventCount.sum())
        .field("staleQueueCleanupCount", staleQueueCleanupCount())
        .token(
            isMovementInhibited(trainName),
            tokenState(trainName, token),
            token == null ? null : token.claimVersion(),
            !destination.isBlank(),
            destination);
  }

  private SignalComputationTrace.Builder withAuthorityTraceFields(
      SignalComputationTrace.Builder trace, AuthorityEnd authorityEnd) {
    return withAuthorityTraceFields(
        trace,
        authorityEnd,
        authorityEnd == null ? AuthorityEndReason.NONE : authorityEnd.reason());
  }

  private SignalComputationTrace.Builder withAuthorityTraceFields(
      SignalComputationTrace.Builder trace,
      AuthorityEnd authorityEnd,
      AuthorityEndReason overrideReason) {
    if (trace == null) {
      return null;
    }
    AuthorityEnd end = authorityEnd == null ? AuthorityEnd.none() : authorityEnd;
    AuthorityEndReason reason = overrideReason == null ? end.reason() : overrideReason;
    return trace
        .field("authorityEndReason", reason)
        .field("authorityWindowDerivedFromExpandedPath", end.windowDerivedFromExpandedPath())
        .field("authorityExtensionAttempted", end.extensionAttempted())
        .field("authorityExtensionSucceeded", end.extensionSucceeded())
        .field("authorityEndIsPhysical", reason.physical());
  }

  private SignalComputationTrace.Builder withDrainGateTraceFields(
      SignalComputationTrace.Builder trace,
      OccupancyRequest request,
      OccupancyDecision decision,
      SignalPublicationGate.Decision publication,
      String authorizationFailureSource,
      String destinationClearReason,
      String tokenInvalidReason) {
    if (trace == null) {
      return null;
    }
    DrainGateContext context = resolveDrainGateContext(request, decision);
    boolean drainGateApplied =
        publication != null && publication.inputType() == SignalDecisionInputType.DRAIN_THROUGH;
    return trace
        .field("inputTypeBeforeDrainClassification", context.inputTypeBeforeDrainClassification())
        .field("inputTypeAfterDrainClassification", context.inputType())
        .field("canEnterConflictRelease", decision != null && decision.conflictRelease())
        .field("canEnterReleaseLeader", context.drainAuthorityLeader())
        .field("releaseHintVerified", context.releaseHintVerified())
        .field("drainAuthorityActive", context.drainAuthorityActive())
        .field("drainAuthorityLeader", context.drainAuthorityLeader())
        .field("drainAuthorityFresh", context.drainAuthorityFresh())
        .field("drainAuthorityZoneMatches", context.drainAuthorityZoneMatches())
        .field("drainGateApplied", drainGateApplied)
        .field("drainGateSkippedReason", drainGateApplied ? "-" : drainGateSkippedReason(context))
        .field("authorizationFailureSource", authorizationFailureSource)
        .field("destinationClearReason", destinationClearReason)
        .field("tokenInvalidReason", tokenInvalidReason);
  }

  private static String drainGateSkippedReason(DrainGateContext context) {
    if (context == null) {
      return "context-missing";
    }
    if (context.inputType() == SignalDecisionInputType.DRAIN_THROUGH) {
      return "-";
    }
    if (context.topologyExitHintOnly()) {
      return "topology-exit-hint-only";
    }
    if (!context.drainAuthorityActive()) {
      return "drain-authority-inactive";
    }
    if (!context.drainAuthorityFresh()) {
      return "drain-authority-stale";
    }
    if (!context.drainAuthorityZoneMatches()) {
      return "drain-authority-zone-mismatch";
    }
    if (context.ordinaryDeparture()) {
      return "ordinary-departure";
    }
    return "not-drain-through";
  }

  private SignalPublicationGate.Decision evaluatePublicationGate(
      String trainName,
      OccupancyRequest request,
      OccupancyDecision decision,
      SignalAspect candidateAspect,
      boolean movementInhibited,
      SignalComputationTrace.TokenState tokenState) {
    DrainGateContext drainGate = resolveDrainGateContext(request, decision);
    boolean drainLeader = decision != null && decision.conflictRelease();
    String incident =
        drainGate.drainAuthorityActive()
                && drainGate.inputType() == SignalDecisionInputType.DRAIN_THROUGH
                && !drainLeader
            ? SignalPublicationGate.INCIDENT_DRAIN_AUTHORITY_INCONSISTENT
            : "-";
    return SignalPublicationGate.evaluate(
        new SignalPublicationGate.Input(
            request,
            candidateAspect,
            movementInhibited,
            tokenState,
            drainLeader,
            drainGate.drainAuthorityActive(),
            drainGate.drainAuthorityFresh(),
            drainGate.drainAuthorityZoneMatches(),
            drainGate.trainInsideMatchingSingleConflict(),
            drainGate.pathDrainingTowardExit(),
            drainGate.ordinaryDeparture(),
            drainGate.topologyExitHintOnly(),
            true,
            incident));
  }

  private DrainGateContext resolveDrainGateContext(
      OccupancyRequest request, OccupancyDecision decision) {
    SignalDecisionInputType baseType = SignalDecisionInputClassifier.classify(request);
    boolean releaseLeader =
        decision != null
            && decision.conflictRelease()
            && request != null
            && request.purpose() == AuthorizationPurpose.CONFLICT_CLEARING;
    boolean releaseHintVerified = hasVerifiedReleaseHint(request);
    boolean verifiedDrainAuthority = hasVerifiedDrainAuthorityHint(request);
    boolean topologyExitHintOnly = hasOnlyTopologyExitHints(request);
    boolean drainAuthorityActive = releaseLeader || verifiedDrainAuthority;
    boolean drainAuthorityFresh = drainAuthorityActive && hasFreshMovementSnapshot(request);
    boolean drainAuthorityZoneMatches =
        drainAuthorityActive && releaseHintsMatchMovementPlanZones(request);
    boolean trainInsideMatchingSingleConflict =
        drainAuthorityActive && releaseHintsAlreadyInsideConflict(request);
    boolean pathDrainingTowardExit = drainAuthorityActive && releaseHintsTargetExit(request);
    boolean ordinaryDeparture =
        request != null
            && request.movementPlanSnapshot().map(plan -> plan.routeIndex() == 0).orElse(false);
    if (ordinaryDeparture && hasVerifiedSwitcherOccupantReleaseHint(request)) {
      ordinaryDeparture = false;
    }
    SignalDecisionInputClassifier.DrainClassificationContext drainContext =
        new SignalDecisionInputClassifier.DrainClassificationContext(
            drainAuthorityActive,
            drainAuthorityFresh,
            drainAuthorityZoneMatches,
            trainInsideMatchingSingleConflict,
            pathDrainingTowardExit,
            ordinaryDeparture,
            topologyExitHintOnly);
    SignalDecisionInputType finalType =
        SignalDecisionInputClassifier.classify(request, drainContext);
    return new DrainGateContext(
        baseType,
        finalType,
        releaseHintVerified,
        drainAuthorityActive,
        releaseLeader,
        drainAuthorityFresh,
        drainAuthorityZoneMatches,
        trainInsideMatchingSingleConflict,
        pathDrainingTowardExit,
        ordinaryDeparture,
        topologyExitHintOnly);
  }

  private static boolean hasVerifiedReleaseHint(OccupancyRequest request) {
    if (request == null || request.conflictReleaseHints().isEmpty()) {
      return false;
    }
    for (ConflictReleaseHint hint : request.conflictReleaseHints().values()) {
      if (hint != null && hint.verifiedFor(hint.conflictKey())) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasVerifiedDrainAuthorityHint(OccupancyRequest request) {
    if (request == null || request.conflictReleaseHints().isEmpty()) {
      return false;
    }
    for (ConflictReleaseHint hint : request.conflictReleaseHints().values()) {
      if (hint != null
          && hint.kind() == ConflictClearingEvidenceKind.VERIFIED_DRAIN_AUTHORITY
          && hint.verifiedFor(hint.conflictKey())) {
        return true;
      }
    }
    return false;
  }

  /**
   * 判断请求是否携带由实体道岔位置证明的清空证据。
   *
   * <p>这种证据可出现在 route 第一段的中间 switcher；它不是普通首发，不能仅因 routeIndex 为 0 而降级成普通 departure。
   */
  private static boolean hasVerifiedSwitcherOccupantReleaseHint(OccupancyRequest request) {
    if (request == null || request.conflictReleaseHints().isEmpty()) {
      return false;
    }
    for (ConflictReleaseHint hint : request.conflictReleaseHints().values()) {
      if (hint != null
          && hint.kind() == ConflictClearingEvidenceKind.VERIFIED_SWITCHER_OCCUPANT
          && hint.verifiedFor(hint.conflictKey())) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasOnlyTopologyExitHints(OccupancyRequest request) {
    if (request == null || request.conflictReleaseHints().isEmpty()) {
      return false;
    }
    for (ConflictReleaseHint hint : request.conflictReleaseHints().values()) {
      if (hint == null || hint.kind() != ConflictClearingEvidenceKind.TOPOLOGY_EXIT_HINT) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasFreshMovementSnapshot(OccupancyRequest request) {
    return request != null
        && request
            .movementPlanSnapshot()
            .map(plan -> plan.occupancyVersion() >= 0 && plan.progressVersion() >= 0)
            .orElse(false);
  }

  private static boolean releaseHintsMatchMovementPlanZones(OccupancyRequest request) {
    if (request == null || request.conflictReleaseHints().isEmpty()) {
      return false;
    }
    Optional<MovementPlanSnapshot> planOpt = request.movementPlanSnapshot();
    if (planOpt.isEmpty()) {
      return false;
    }
    Set<String> planZones = new LinkedHashSet<>();
    planZones.addAll(planOpt.get().singleConflictDirections().keySet());
    planZones.addAll(planOpt.get().switcherPathSignatures().keySet());
    for (String key : request.conflictReleaseHints().keySet()) {
      if (planZones.contains(key)) {
        return true;
      }
    }
    return false;
  }

  private static boolean releaseHintsAlreadyInsideConflict(OccupancyRequest request) {
    if (request == null || request.conflictReleaseHints().isEmpty()) {
      return false;
    }
    for (ConflictReleaseHint hint : request.conflictReleaseHints().values()) {
      if (hint != null && hint.trainAlreadyInsideSameConflict()) {
        return true;
      }
    }
    return false;
  }

  private static boolean releaseHintsTargetExit(OccupancyRequest request) {
    if (request == null || request.conflictReleaseHints().isEmpty()) {
      return false;
    }
    for (ConflictReleaseHint hint : request.conflictReleaseHints().values()) {
      if (hint != null && hint.targetIsExitFromConflict()) {
        return true;
      }
    }
    return false;
  }

  private record DrainGateContext(
      SignalDecisionInputType inputTypeBeforeDrainClassification,
      SignalDecisionInputType inputType,
      boolean releaseHintVerified,
      boolean drainAuthorityActive,
      boolean drainAuthorityLeader,
      boolean drainAuthorityFresh,
      boolean drainAuthorityZoneMatches,
      boolean trainInsideMatchingSingleConflict,
      boolean pathDrainingTowardExit,
      boolean ordinaryDeparture,
      boolean topologyExitHintOnly) {}

  private Optional<MovementAuthorizationToken> movementToken(String trainName) {
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(movementAuthorizationTokens.get(key));
  }

  private SignalComputationTrace.TokenState tokenState(
      String trainName, MovementAuthorizationToken token) {
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return SignalComputationTrace.TokenState.INVALID;
    }
    if (movementInhibitors.containsKey(key)) {
      return token == null
          ? SignalComputationTrace.TokenState.INVALID
          : SignalComputationTrace.TokenState.PENDING;
    }
    if (token == null) {
      return SignalComputationTrace.TokenState.NONE;
    }
    return token.active()
        ? SignalComputationTrace.TokenState.ACTIVE
        : SignalComputationTrace.TokenState.PENDING;
  }

  private long occupancyVersion() {
    return occupancyManager instanceof SimpleOccupancyManager manager ? manager.version() : -1L;
  }

  private long staleQueueCleanupCount() {
    return occupancyManager instanceof SimpleOccupancyManager manager
        ? manager.staleQueueCleanupCount()
        : -1L;
  }

  private boolean finalSignalSnapshotFresh(OccupancyRequest request) {
    if (request == null || request.directedContext().isEmpty()) {
      return false;
    }
    DirectedTraversalContext context = request.directedContext().get();
    long requestOccupancyVersion = context.occupancyVersion();
    long currentOccupancyVersion = occupancyVersion();
    if (requestOccupancyVersion >= 0 && currentOccupancyVersion >= 0) {
      if (requestOccupancyVersion != currentOccupancyVersion) {
        return false;
      }
    }
    long requestProgressVersion = context.progressVersion();
    long currentProgressVersion = progressRegistry.version();
    return requestProgressVersion < 0
        || currentProgressVersion < 0
        || requestProgressVersion == currentProgressVersion;
  }

  private String firstSingleConflictKey(OccupancyRequest request) {
    if (request == null) {
      return "-";
    }
    for (OccupancyResource resource : request.resourceList()) {
      if (isSingleConflict(resource)) {
        return resource.key();
      }
    }
    return "-";
  }

  private boolean finalSignalHardBarrierPresent(String trainName, OccupancyRequest request) {
    if (request == null) {
      return false;
    }
    for (OccupancyResource resource : request.resourceList()) {
      if (!isSingleConflict(resource)) {
        continue;
      }
      if (singleRegionOppositeOrUnknownExternalBarrier(
          trainName, List.of(resource), requestedDirectionFor(request, resource))) {
        return true;
      }
    }
    return false;
  }

  private static CorridorDirection requestedDirectionFor(
      OccupancyRequest request, OccupancyResource resource) {
    if (request == null || resource == null) {
      return CorridorDirection.UNKNOWN;
    }
    CorridorDirection direct = request.corridorDirections().get(resource.key());
    if (direct != null && direct != CorridorDirection.UNKNOWN) {
      return direct;
    }
    return request
        .directedContext()
        .map(context -> context.singleConflictDirections().get(resource.key()))
        .filter(direction -> direction != null && direction != CorridorDirection.UNKNOWN)
        .orElse(CorridorDirection.UNKNOWN);
  }

  private static boolean isSingleConflict(OccupancyResource resource) {
    return resource != null
        && resource.kind() == ResourceKind.CONFLICT
        && resource.key().startsWith("single:");
  }

  private OccupancyRequest markDirectedRequest(
      OccupancyRequest request, SignalComputationTrace.Source source) {
    if (request == null) {
      return null;
    }
    SignalComputationTrace.Source effectiveSource =
        source == null ? SignalComputationTrace.Source.PERIODIC_TICK : source;
    return request
        .withDirectedSource(effectiveSource.name())
        .withDirectedOccupancyVersion(occupancyVersion())
        .withDirectedProgressVersion(progressRegistry.version());
  }

  /**
   * 为运行时授权请求写入最新快照版本，并在同一份有向上下文上计算冲突清空证据。
   *
   * <p>版本必须先写入：builder 初始使用 {@code -1} 表示尚未绑定实时快照，若先计算 evidence，实体道岔 occupant 会被误判为陈旧证据而永远无法进入
   * DRAIN_THROUGH。
   */
  private OccupancyRequest prepareRuntimeAuthorizationRequest(
      OccupancyRequestContext context, RailGraph graph, SignalComputationTrace.Source source) {
    if (context == null || context.request() == null) {
      return null;
    }
    OccupancyRequest directed = markDirectedRequest(context.request(), source);
    OccupancyRequestContext directedContext =
        new OccupancyRequestContext(directed, context.pathNodes(), context.edges());
    return withRuntimeConflictClearingEvidence(directed, directedContext, graph);
  }

  private SignalComputationTrace.Builder withStopFields(
      SignalComputationTrace.Builder trace,
      Optional<RouteStop> stopOpt,
      boolean dwellActive,
      boolean approachHandoff) {
    RouteStopPassType passType =
        stopOpt == null || stopOpt.isEmpty() ? null : stopOpt.get().passType();
    return trace
        .field("currentStop.passType", passType == null ? "-" : passType.name())
        .field("isPassNode", passType == RouteStopPassType.PASS)
        .field("isStopNode", passType == RouteStopPassType.STOP)
        .field("isTermNode", passType == RouteStopPassType.TERMINATE)
        .field("isDwellActive", dwellActive)
        .field("isApproachHandoff", approachHandoff);
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
    return checkDeparture(new TrainCartsRuntimeHandle(group), definition);
  }

  /**
   * 检查列车是否允许从当前站点发车。
   *
   * <p>该重载用于把 TrainCarts 句柄适配与门控判定拆开，便于测试验证“先预判、后写占用”的安全边界。
   */
  boolean checkDeparture(RuntimeTrainHandle train, SignNodeDefinition definition) {
    if (train == null || definition == null) {
      return true;
    }
    TrainProperties properties = train.properties();
    String trainName = resolveTrackedTrainName(properties).orElse(null);
    if (trainName == null || trainName.isBlank()) {
      trainName = "unknown";
    }
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
    observePhysicalNodeForSpawnOrigin(properties, definition.nodeId(), currentIndex);
    recordEffectiveNode(trainName, route, currentIndex, definition.nodeId());
    pruneDynamicResolutionState(trainName, route, currentIndex);
    Optional<RouteStop> stopOpt = routeDefinitions.findStop(route.id(), currentIndex);
    if (stopOpt.isPresent()) {
      RouteStop stop = stopOpt.get();
      if (shouldEnterLayoverAtTerminateStop(route, currentIndex, stop)) {
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
      if (isDeclaredDynamicStop(route, currentIndex + 1)) {
        holdForUnavailableDynamicDestination(
            train,
            properties,
            trainName,
            route,
            currentIndex,
            definition.nodeId(),
            now,
            "graph-snapshot-missing");
        return false;
      }
      return true;
    }
    RailGraph graph = graphOpt.get();
    ConfigManager.RuntimeSettings runtimeSettings = configManager.current().runtimeSettings();
    int rearGuardEdges = runtimeSettings.rearGuardEdges();
    int priority = resolvePriority(properties, route);

    OccupancyRequestBuilder builder =
        runtimeLookaheadBuilder(graph, runtimeSettings, rearGuardEdges);
    List<NodeId> effectiveNodes = resolveEffectiveWaypoints(trainName, route);
    int nextIndex = currentIndex + 1;
    DynamicResolution<DynamicSelection> dynamicSelection =
        selectDynamicStationTargetForProgress(
            trainName,
            route,
            currentIndex,
            nextIndex,
            definition.nodeId(),
            graph,
            builder,
            now,
            priority,
            AuthorizationPurpose.STATION_DEPARTURE);

    Optional<OccupancyRequestContext> contextOpt;
    if (dynamicSelection.isBlocked()) {
      holdForUnavailableDynamicDestination(
          train,
          properties,
          trainName,
          route,
          currentIndex,
          definition.nodeId(),
          now,
          dynamicSelection.reason());
      return false;
    }
    if (dynamicSelection.isSelected()) {
      DynamicSelection selection = dynamicSelection.selected().orElseThrow();
      recordEffectiveNode(trainName, route, nextIndex, selection.targetNode());
      effectiveNodes = resolveEffectiveWaypoints(trainName, route);
      contextOpt = Optional.of(selection.context());
    } else {
      contextOpt =
          buildContextWithinDynamicBoundary(
              builder,
              trainName,
              route,
              effectiveNodes,
              effectiveNodes,
              currentIndex,
              now,
              priority,
              AuthorizationPurpose.STATION_DEPARTURE);
    }

    if (contextOpt.isEmpty()) {
      debugLogger.accept("发车门控失败: 构建占用请求失败 train=" + trainName);
      retainStopOccupancy(trainName, route, currentIndex, definition.nodeId(), graph, now);
      return false;
    }

    OccupancyRequestContext context = contextOpt.get();
    // 发车门控是 admission gate，需要保留选定前向路径与必要的原子联锁窗口；1-edge hard authority
    // 只用于信号 aspect staging，不能替代发车授权窗口；尾部保护资源仍由 retain 流程持有，不随发车 acquire 覆盖。
    OccupancyRequest authorizationRequest =
        markDirectedRequest(
            movementRequiredAdmissionRequest(context.request()),
            SignalComputationTrace.Source.DEPARTURE_GATE);
    OccupancyRequestContext stationAdmissionContext =
        new OccupancyRequestContext(
            authorizationRequest, context.pathNodes(), context.edges(), context.directedContext());
    AuthorityEnd stationAdmissionAuthorityEnd =
        resolveAuthorityEnd(graph, context.pathNodes(), currentIndex, stationAdmissionContext);
    SmartAdmissionResult stationAdmission =
        evaluateSmartSingleCorridorAdmission(
            trainName,
            graph,
            stationAdmissionContext,
            stationAdmissionAuthorityEnd,
            true,
            SmartAdmissionContext.station("station-departure-smart-admission"));
    if (smartAdmissionShouldBlock(trainName, stationAdmission)) {
      retainStopOccupancy(
          trainName,
          route,
          currentIndex,
          definition.nodeId(),
          Optional.of(authorizationRequest),
          graph,
          now);
      debugLogger.accept(
          "SMART_STATION_DEPARTURE_HELD train="
              + trainName
              + " reason="
              + stationAdmission.reason()
              + " localOnlyHold=true destinationMutated=false tokenInvalidated=false");
      return false;
    }
    Optional<NodeId> nextNode =
        currentIndex + 1 < route.waypoints().size()
            ? Optional.of(resolveEffectiveNode(trainName, route, currentIndex + 1))
            : Optional.empty();
    List<OccupancyResource> keepResources =
        mergeKeepResourcesWithCurrentPosition(
            authorizationRequest.resourceList(), Optional.of(definition.nodeId()), nextNode, graph);
    releaseResourcesNotInRequest(
        trainName,
        keepResources,
        protectedSwitcherZoneClaims(
            trainName, route, currentIndex, definition.nodeId(), graph, "DEPARTURE_GATE"));
    releaseSpeculativeClaimsFromBehindSameRoute(
        trainName,
        route,
        currentIndex,
        definition.nodeId(),
        graph,
        authorizationRequest.resourceList());
    releaseSpeculativeQueueEntriesFromBehindSameRoute(
        trainName,
        route,
        currentIndex,
        definition.nodeId(),
        graph,
        authorizationRequest.resourceList());
    maybeRecoverSelfOwnedStaleRetainPreview(authorizationRequest, "departure-self-owned-retain");
    String departureTrainName = trainName;
    LaunchAuthorizationService.AuthorizationResult authorization =
        launchAuthorizationService.authorize(
            new LaunchAuthorizationService.AuthorizationPlan(
                authorizationRequest,
                "departure",
                false,
                true,
                true,
                true,
                new LaunchAuthorizationService.LaunchActions() {
                  @Override
                  public void holdStop(LaunchAuthorizationService.AuthorizationResult result) {
                    retainStopOccupancy(
                        departureTrainName,
                        route,
                        currentIndex,
                        definition.nodeId(),
                        Optional.of(authorizationRequest),
                        graph,
                        now);
                  }
                }));
    if (!authorization.allowed()) {
      OccupancyDecision decision = authorization.effectiveDecision();
      SignalComputationTrace.emit(
          signalTrace(
                  trainName,
                  properties,
                  SignalComputationTrace.Source.DEPARTURE_GATE,
                  progressRegistry
                      .get(trainName)
                      .map(RouteProgressRegistry.RouteProgressEntry::lastSignal)
                      .orElse(null),
                  decision == null ? SignalAspect.STOP : decision.signal(),
                  authorization.yielded() ? "departure-gate-yield" : "departure-gate-blocked")
              .nodes(definition.nodeId(), nextNode.orElse(null))
              .progress(
                  progressRegistry.version(),
                  currentIndex,
                  currentIndex,
                  progressRegistry
                      .get(trainName)
                      .flatMap(RouteProgressRegistry.RouteProgressEntry::lastPassedGraphNode),
                  progressRegistry
                      .get(trainName)
                      .flatMap(RouteProgressRegistry.RouteProgressEntry::lastPassedGraphNode))
              .request(authorizationRequest)
              .decision(decision, authorizationRequest));
      if (authorization.yielded()) {
        debugLogger.accept("发车门控让行: train=" + trainName + " priority=" + priority);
      } else {
        SignalAspect signal = decision == null ? SignalAspect.STOP : decision.signal();
        String blockers = decision == null ? "-" : summarizeBlockers(decision);
        debugLogger.accept(
            "发车门控阻塞: train=" + trainName + " aspect=" + signal + " blockers=" + blockers);
      }
      return false;
    }
    if (authorization.acquireAttempted() && !authorization.acquired()) {
      debugLogger.accept(
          "发车门控阻塞: train="
              + trainName
              + " aspect="
              + authorization.signal()
              + " blockers="
              + summarizeBlockers(authorization.effectiveDecision()));
      return false;
    }
    retainRearGuardOccupancyBestEffort(
        trainName,
        route,
        currentIndex,
        effectiveNodes,
        authorizationRequest.movementPlanSnapshot(),
        graph,
        runtimeSettings,
        now);
    return true;
  }

  private OccupancyRequest movementRequiredAdmissionRequest(OccupancyRequest request) {
    if (request == null) {
      return null;
    }
    List<OccupancyResource> resources = new ArrayList<>();
    Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>();
    for (OccupancyResource resource : request.resourceList()) {
      if (resource == null || !request.intentFor(resource).hardAuthority()) {
        continue;
      }
      resources.add(resource);
      intents.put(resource, ResourceIntent.MOVEMENT_REQUIRED);
    }
    if (resources.size() == request.resourceList().size()) {
      return request;
    }
    return new OccupancyRequest(
        request.trainName(),
        request.routeId(),
        request.now(),
        resources,
        request.corridorDirections(),
        request.conflictEntryOrders(),
        request.priority(),
        request.purpose(),
        request.conflictReleaseHints(),
        intents,
        request.directedContext());
  }

  /**
   * AutoStation 停车时推进 routeIndex，并在安全时设置下一站 destination。
   *
   * <p>此方法应在列车于 AutoStation 停稳后（进入 dwell 前）调用，用于：
   *
   * <ul>
   *   <li>推进 routeIndex 到当前站点
   *   <li>解析 Dynamic 站台选择（如果下一站是 DYNAMIC）
   *   <li>未处于停站门控时设置下一站 destination
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

    // 非 FTA 管控列车：静默跳过
    if (!isFtaManagedTrain(properties)) {
      return;
    }

    String trainName = handleRenameIfNeeded(properties);
    if (trainName == null || trainName.isBlank()) {
      trainName = resolveTrackedTrainName(properties).orElse("unknown");
    }
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
    observePhysicalNodeForSpawnOrigin(properties, currentNode, currentIndex);
    recordEffectiveNode(trainName, route, currentIndex, currentNode);
    pruneDynamicResolutionState(trainName, route, currentIndex);
    observeTurnbackFootprintProgress(trainName, currentNode);
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
    DynamicResolution<NodeId> dynamicResolution;
    if (graphOpt.isPresent()) {
      dynamicResolution =
          resolveDynamicStationTargetIfNeeded(
              trainName, route, nextIndex, currentNode, graphOpt.get());
    } else {
      dynamicResolution =
          isDeclaredDynamicStop(route, nextIndex)
              ? DynamicResolution.blocked("graph-snapshot-missing")
              : DynamicResolution.notApplicable("graph-snapshot-missing");
    }
    if (dynamicResolution.isBlocked()) {
      holdForUnavailableDynamicDestination(
          train,
          properties,
          trainName,
          route,
          currentIndex,
          currentNode,
          now,
          dynamicResolution.reason());
      return;
    }
    if (graphOpt.isPresent()) {
      final String logicalTrainName = trainName;
      dynamicResolution
          .selected()
          .ifPresent(selected -> recordEffectiveNode(logicalTrainName, route, nextIndex, selected));
      nextNode = resolveEffectiveNode(trainName, route, nextIndex);
    }
    // 停站门控期间只推进 routeIndex 与 materialize dynamic effective node，不提前写 TrainCarts
    // destination。TrainCarts 在静止 WaitState 中看到下一站 destination 时可能提前按出口方向反向；
    // 物理 destination 由门控释放后的 signal tick 在最终授权通过时提交。
    if (shouldDeferStationDepartureDestination(trainName, route, currentIndex)) {
      debugLogger.accept(
          "Station 延迟 destination: train="
              + trainName
              + " next="
              + nextNode.value()
              + " nextIdx="
              + nextIndex
              + " reason=departure-gate-held");
      return;
    }
    // 行为流程例外：AutoStation 已完成到站推进，此处只 materialize 下一跳 destination。
    // 真正离站仍会经过 Station/TERM departure gate 与 LaunchAuthorizationService 授权。
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
   * 判定 AutoStation 到站推进后是否需要延迟写入 TrainCarts destination。
   *
   * <p>departure gate 持有期间，列车处于停站/开关门/WaitState 窗口。此时写入下一跳 destination 会让 TrainCarts
   * 立即按下一站路径重算静止列车朝向，表现为停站时车组被反向；实际发车仍应由门控释放后的 signal tick 统一完成。
   */
  boolean shouldDeferStationDepartureDestination(
      String trainName, RouteDefinition route, int currentIndex) {
    return hasDepartureGate(trainName)
        && route != null
        && currentIndex >= 0
        && currentIndex < route.waypoints().size() - 1;
  }

  /**
   * 判断经过的 AutoStation 是否应按 PASS 站推进。
   *
   * <p>AutoStation 的 STOP/TERMINATE 必须等列车停稳后由 {@link #handleStationArrival(MinecartGroup,
   * SignNodeDefinition)} 推进；PASS 不会进入停站流程，因此需要在牌子触发时直接走普通推进逻辑。
   *
   * @param properties TrainCarts 列车属性
   * @param definition 当前 AutoStation 节点定义
   * @return 当前节点对应 RouteStop 且 passType 为 PASS 时返回 true
   */
  boolean shouldAdvancePassedStation(TrainProperties properties, SignNodeDefinition definition) {
    if (properties == null || definition == null || definition.nodeType() != NodeType.STATION) {
      return false;
    }
    if (!isFtaManagedTrain(properties) || routeDefinitions == null) {
      return false;
    }
    Optional<RouteDefinition> routeOpt = resolveRouteDefinition(properties);
    if (routeOpt.isEmpty()) {
      return false;
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
      return false;
    }
    return routeDefinitions
        .findStop(route.id(), currentIndex)
        .map(stop -> stop.passType() == RouteStopPassType.PASS)
        .orElse(false);
  }

  /**
   * 更新列车经过的最后一个图节点（用于 arriving 判定优化）。
   *
   * <p>当列车经过“未写入 route 的中间图节点”（如 waypoint/switcher）时调用，仅更新 lastPassedGraphNode，不推进 routeIndex。
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
    String trainName = resolveTrackedTrainName(properties).orElse(null);
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    NodeId nodeId = definition.nodeId();
    if (nodeId == null) {
      return;
    }
    Instant now = Instant.now();
    progressRegistry.updateLastPassedGraphNode(trainName, nodeId, now);
    observePhysicalNodeForSpawnOrigin(properties, nodeId, -1);
    observeTurnbackFootprintProgress(trainName, nodeId);
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
    handleProgressTrigger(new TrainCartsRuntimeHandle(group), event, definition);
  }

  /**
   * 使用可替换列车句柄处理推进点。
   *
   * <p>事件入口只负责适配 TrainCarts group；调度决策依赖 {@link RuntimeTrainHandle}，从而可以在不初始化完整 TrainCarts
   * 静态运行时的情况下验证进路授权与停车边界。
   */
  void handleProgressTrigger(
      RuntimeTrainHandle train, SignActionEvent event, SignNodeDefinition definition) {
    if (train == null || event == null || definition == null) {
      return;
    }
    TrainProperties properties = train.properties();

    // 非 FTA 管控列车：静默跳过，不输出日志
    if (!isFtaManagedTrain(properties)) {
      return;
    }

    String trainName = handleRenameIfNeeded(properties);
    if (trainName == null || trainName.isBlank()) {
      trainName = resolveTrackedTrainName(properties).orElse("unknown");
    }
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
      if (shouldTrackIntermediateGraphNode(definition)) {
        if (progressRegistry.get(trainName).isEmpty()) {
          progressRegistry.initFromTags(trainName, properties, route);
        }
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

    observePhysicalNodeForSpawnOrigin(properties, currentNode, currentIndex);
    recordEffectiveNode(trainName, route, currentIndex, currentNode);
    pruneDynamicResolutionState(trainName, route, currentIndex);
    Instant now = Instant.now();
    Optional<RouteProgressRegistry.RouteProgressEntry> progressBeforeTrigger =
        progressRegistry.get(trainName);
    if (!shouldHandleProgressTrigger(trainName, currentNode, currentIndex, now)) {
      return;
    }
    observeTurnbackFootprintProgress(trainName, currentNode);
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
      if (shouldEnterLayoverAtTerminateStop(route, currentIndex, stop)) {
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
          // Waypoint STOP/TERM 的平滑减速由到站前 approach 完成；触发后进入停车保持并等待居中。
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
      DynamicResolution<NodeId> dynamicResolution =
          isDeclaredDynamicStop(route, nextIndex)
              ? DynamicResolution.blocked("graph-snapshot-missing")
              : DynamicResolution.notApplicable("graph-snapshot-missing");
      if (graph != null) {
        dynamicResolution =
            resolveDynamicStationTargetIfNeeded(trainName, route, nextIndex, currentNode, graph);
        final String logicalTrainName = trainName;
        dynamicResolution
            .selected()
            .ifPresent(
                selected -> recordEffectiveNode(logicalTrainName, route, nextIndex, selected));
        nextNode = resolveEffectiveNode(trainName, route, nextIndex);
      }
      if (event.getAction() == SignActionType.MEMBER_ENTER) {
        return;
      }
      // STOP waypoint 到达：只保留当前节点占用，释放窗口外资源，防止后车追尾
      if (occupancyManager != null) {
        if (dynamicResolution.isBlocked()) {
          retainDynamicCapacityWaitOccupancy(trainName, route, currentNode, now);
        } else {
          retainStopOccupancy(trainName, route, currentIndex, currentNode, graph, now);
        }
      }
      progressRegistry.advance(
          trainName, routeUuidOpt.orElse(null), route, currentIndex, properties, now);
      invalidateTrainEta(trainName);
      updateSignalOrWarn(trainName, SignalAspect.STOP, now);
      String destinationName =
          dynamicResolution.isBlocked() ? null : resolveDestinationName(nextNode);
      if (destinationName != null && !destinationName.isBlank() && properties != null) {
        // 行为流程例外：STOP waypoint dwell handoff 需要提前写入下一跳，便于停站结束后恢复寻路。
        // 控车仍保持 STOP/居中/等待动作，不在此处放行或发车。
        properties.clearDestinationRoute();
        properties.setDestination(destinationName);
      } else if (dynamicResolution.isBlocked() && properties != null) {
        properties.clearDestinationRoute();
        properties.clearDestination();
        debugLogger.accept(
            dynamicWaitLogLabel(dynamicResolution.reason())
                + ": source=waypoint train="
                + trainName
                + " nextIdx="
                + nextIndex
                + " reason="
                + dynamicResolution.reason());
      }
      // Waypoint STOP 的平滑减速由到站前 approach 完成；触发后进入停车保持并等待居中。
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
    int rearGuardEdges = runtimeSettings.rearGuardEdges();
    int priority = resolvePriority(properties, route);
    OccupancyRequestBuilder builder =
        runtimeLookaheadBuilder(graph, runtimeSettings, rearGuardEdges);
    List<NodeId> effectiveNodes = resolveEffectiveWaypoints(trainName, route);
    DynamicResolution<DynamicSelection> dynamicSelection =
        selectDynamicStationTargetForProgress(
            trainName,
            route,
            currentIndex,
            nextIndex,
            currentNode,
            graph,
            builder,
            now,
            priority,
            AuthorizationPurpose.RUNTIME_MOVE);
    OccupancyRequestContext context;
    if (dynamicSelection.isBlocked()) {
      holdForUnavailableDynamicDestination(
          train,
          properties,
          trainName,
          route,
          currentIndex,
          currentNode,
          now,
          dynamicSelection.reason());
      return;
    }
    if (dynamicSelection.isSelected()) {
      DynamicSelection selection = dynamicSelection.selected().orElseThrow();
      recordEffectiveNode(trainName, route, nextIndex, selection.targetNode());
      effectiveNodes = resolveEffectiveWaypoints(trainName, route);
      nextNode = selection.targetNode();
      context = selection.context();
    } else {
      Optional<OccupancyRequestContext> contextOpt =
          buildContextWithinDynamicBoundary(
              builder,
              trainName,
              route,
              effectiveNodes,
              effectiveNodes,
              currentIndex,
              now,
              priority,
              AuthorizationPurpose.RUNTIME_MOVE);
      if (contextOpt.isEmpty()) {
        String buildFailure =
            diagnoseBuildFailure(
                graph,
                route,
                currentIndex,
                Math.max(runtimeSettings.lookaheadEdges(), runtimeSettings.minClearEdges()));
        applyUnresolvableMovementPlanStop(
            train,
            properties,
            trainName,
            route,
            currentIndex,
            currentNode,
            nextNode,
            graph,
            now,
            "progress-trigger",
            buildFailure);
        return;
      }
      context = contextOpt.get();
      OccupancyRequest request = context.request();
      releaseResourcesNotInRequest(
          trainName,
          request.resourceList(),
          protectedSwitcherZoneClaims(
              trainName, route, currentIndex, currentNode, graph, "PROGRESS_TRIGGER"));
      releaseSpeculativeClaimsFromBehindSameRoute(
          trainName, route, currentIndex, currentNode, graph, request.resourceList());
      releaseSpeculativeQueueEntriesFromBehindSameRoute(
          trainName, route, currentIndex, currentNode, graph, request.resourceList());
    }
    OccupancyRequestContext authorizationContext =
        buildHardAuthorityContext(
                graph,
                runtimeSettings,
                trainName,
                route,
                effectiveNodes,
                currentIndex,
                now,
                priority,
                AuthorizationPurpose.RUNTIME_MOVE,
                train.currentSpeedBlocksPerTick() * SPEED_TICKS_PER_SECOND,
                trainConfigResolver.resolve(properties, configManager.current()).decelBps2())
            .orElse(context);
    OccupancyRequest request =
        prepareRuntimeAuthorizationRequest(
            authorizationContext, graph, SignalComputationTrace.Source.PROGRESS_TRIGGER);
    List<OccupancyResource> keepResources =
        mergeKeepResourcesWithCurrentPosition(
            context.request().resourceList(),
            Optional.ofNullable(currentNode),
            Optional.of(nextNode),
            graph);
    releaseResourcesNotInRequest(
        trainName,
        keepResources,
        protectedSwitcherZoneClaims(
            trainName, route, currentIndex, currentNode, graph, "PROGRESS_TRIGGER"));
    MovementAuthorizationCoordinator.AuthorizationResult authorization =
        movementAuthorizationCoordinator.authorize(
            new MovementAuthorizationCoordinator.AuthorizationRequest(
                trainName,
                request,
                Optional.empty(),
                now,
                "progress",
                "progress-acquire",
                this::evaluateMovementAuthorization));
    OccupancyDecision decision = authorization.decision();
    boolean proceedAllowed = authorization.proceedAllowed();
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
            + proceedAllowed
            + " rawAllowed="
            + authorization.rawAllowed()
            + " blockers="
            + decision.blockers().size()
            + " signal="
            + decision.signal());
    if (!proceedAllowed) {
      SignalAspect aspect = deriveBlockedAspect(decision, authorizationContext);
      SignalComputationTrace.emit(
          withStopFields(
                  signalTrace(
                      trainName,
                      properties,
                      SignalComputationTrace.Source.PROGRESS_TRIGGER,
                      progressBeforeTrigger
                          .map(RouteProgressRegistry.RouteProgressEntry::lastSignal)
                          .orElse(null),
                      aspect,
                      "progress-authorization-blocked:" + decision.reason()),
                  stopOpt,
                  false,
                  stopAtWaypoint)
              .nodes(currentNode, nextNode)
              .progress(
                  progressRegistry.version(),
                  progressBeforeTrigger
                      .map(RouteProgressRegistry.RouteProgressEntry::currentIndex)
                      .orElse(currentIndex),
                  currentIndex,
                  progressBeforeTrigger.flatMap(
                      RouteProgressRegistry.RouteProgressEntry::lastPassedGraphNode),
                  Optional.of(currentNode))
              .request(request)
              .decision(decision, request));
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
      // 阻塞时：已到达当前节点，但前方资源尚未 acquire 成功，不能写入下一跳 destination。
      progressRegistry.advance(
          trainName, routeUuidOpt.orElse(null), route, currentIndex, properties, now);
      invalidateTrainEta(trainName);
      // 阻塞等待期间与停站逻辑保持一致：保留当前位置/尾部保护，并持续刷新前向冲突队列位次，
      // 避免后车在当前车等待放行时先抢到更靠前的队头。
      if (occupancyManager != null) {
        retainStopOccupancy(
            trainName, route, currentIndex, currentNode, Optional.of(request), graph, now);
      }
      applyHardStop(
          train,
          properties,
          trainName,
          HardStopReason.AUTHORIZATION_FAILURE,
          true,
          route,
          currentNode,
          nextNode,
          graph,
          decision,
          request,
          AuthorityEnd.none());
      return;
    }
    progressRegistry.advance(
        trainName, routeUuidOpt.orElse(null), route, currentIndex, properties, now);
    invalidateTrainEta(trainName);
    MovementAuthorizationToken token =
        issueMovementAuthorizationToken(
            trainName, currentNode, nextNode, request, SignalAspect.PROCEED, now);
    retainRearGuardOccupancyBestEffort(
        trainName,
        route,
        currentIndex,
        effectiveNodes,
        request.movementPlanSnapshot(),
        graph,
        runtimeSettings,
        now);
    request = markDirectedRequest(request, SignalComputationTrace.Source.PROGRESS_TRIGGER);

    Optional<String> destinationName =
        commitAuthorizedDestination(properties, trainName, route, currentIndex + 1, nextNode);
    if (destinationName.isEmpty()
        || !activateMovementAuthorizationTokenRetainingInhibitor(
            trainName, token, destinationName.get())) {
      rollbackMovementAuthorization(
          trainName, token, request, HardStopReason.AUTHORIZATION_FAILURE);
      OccupancyDecision blocked =
          new OccupancyDecision(
              false, now, SignalAspect.STOP, List.of(), false, "destination-commit-failed");
      applyHardStop(
          train,
          properties,
          trainName,
          HardStopReason.AUTHORIZATION_FAILURE,
          true,
          route,
          currentNode,
          nextNode,
          graph,
          blocked,
          request,
          AuthorityEnd.none());
      return;
    }
    SignalPublicationGate.Decision publication =
        evaluatePublicationGate(
            trainName,
            request,
            decision,
            SignalAspect.PROCEED,
            false,
            SignalComputationTrace.TokenState.ACTIVE);
    FinalSignalAuthorization finalAuthorization =
        new FinalSignalAuthorization(
            "PROGRESS_TRIGGER",
            request,
            decision,
            publication,
            currentNode,
            nextNode,
            SignalComputationTrace.TokenState.ACTIVE,
            true,
            false);
    FinalSignalValidation validation =
        validateFinalSignalAuthorization(trainName, SignalAspect.PROCEED, finalAuthorization, true);
    if (!validation.allowed()) {
      rollbackMovementAuthorization(
          trainName, token, request, HardStopReason.AUTHORIZATION_FAILURE);
      OccupancyDecision blocked =
          new OccupancyDecision(
              false,
              now,
              SignalAspect.STOP,
              decision.blockers(),
              false,
              "final-authorization:" + validation.reason());
      traceStructuredSignalFinalDecision(
          trainName,
          finalAuthorization,
          SignalAspect.STOP,
          false,
          false,
          "progress-final-authorization:" + validation.reason());
      applyHardStop(
          train,
          properties,
          trainName,
          HardStopReason.AUTHORIZATION_FAILURE,
          true,
          route,
          currentNode,
          nextNode,
          graph,
          blocked,
          request,
          AuthorityEnd.none());
      return;
    }
    if (!clearMovementInhibitorAfterFinalAuthorization(
        trainName, finalAuthorization, SignalAspect.PROCEED)) {
      rollbackMovementAuthorization(
          trainName, token, request, HardStopReason.AUTHORIZATION_FAILURE);
      applyHardStop(
          train,
          properties,
          trainName,
          HardStopReason.AUTHORIZATION_FAILURE,
          true,
          route,
          currentNode,
          nextNode,
          graph,
          new OccupancyDecision(
              false, now, SignalAspect.STOP, List.of(), false, "movement-inhibitor-clear-failed"),
          request,
          AuthorityEnd.none());
      return;
    }
    SignalComputationTrace.emit(
        withStopFields(
                signalTrace(
                    trainName,
                    properties,
                    SignalComputationTrace.Source.PROGRESS_TRIGGER,
                    progressBeforeTrigger
                        .map(RouteProgressRegistry.RouteProgressEntry::lastSignal)
                        .orElse(null),
                    SignalAspect.PROCEED,
                    "progress-committed"),
                stopOpt,
                false,
                stopAtWaypoint)
            .nodes(currentNode, nextNode)
            .progress(
                progressRegistry.version(),
                progressBeforeTrigger
                    .map(RouteProgressRegistry.RouteProgressEntry::currentIndex)
                    .orElse(currentIndex),
                currentIndex,
                progressBeforeTrigger.flatMap(
                    RouteProgressRegistry.RouteProgressEntry::lastPassedGraphNode),
                progressRegistry
                    .get(trainName)
                    .flatMap(RouteProgressRegistry.RouteProgressEntry::lastPassedGraphNode))
            .request(request)
            .decision(decision, request));
    updateSignalOrWarn(trainName, SignalAspect.PROCEED, now, finalAuthorization);
    traceStructuredSignalFinalDecision(
        trainName, finalAuthorization, SignalAspect.PROCEED, true, true, "progress-final");
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
   *
   * @param activeTrainNames 当前存活的列车名集合
   */
  public void cleanupOrphanOccupancyClaims(java.util.Set<String> activeTrainNames) {
    cleanupOrphanOccupancyClaimsWithReport(activeTrainNames);
  }

  /**
   * 清理“已不存在列车”的占用记录与进度缓存，并返回本次自愈的统计结果。
   *
   * <p>清理范围：
   *
   * <ul>
   *   <li>进度注册表（{@link RouteProgressRegistry}）中已不存在列车的条目
   *   <li>占用管理器中已不存在列车的所有占用资源
   *   <li>折返候选注册表中已不存在列车的记录
   *   <li>动态站台分配缓存
   *   <li>发车门控、blocker 快照与其他运行时派生缓存
   * </ul>
   *
   * <p>该方法不会主动加载区块或扫描轨道，仅根据当前在线列车名集合做一致性修复。比较使用小写规范化， 以兼容 TrainCarts 不同路径返回不同大小写的情况。
   *
   * @param activeTrainNames 当前存活的列车名集合
   * @return 本次清理的统计结果
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
    departureGates.keySet().removeIf(key -> !activeLower.contains(key));

    int removedProgress = 0;
    for (String name : progressRegistry.snapshot().keySet()) {
      if (name == null || name.isBlank()) {
        continue;
      }
      if (!activeLower.contains(name.trim().toLowerCase(java.util.Locale.ROOT))) {
        progressRegistry.remove(name);
        clearRuntimeCachesForTrain(name);
        removedProgress++;
      }
    }

    java.util.Set<String> released = new java.util.HashSet<>();
    int releasedTrains = 0;
    List<OccupancyClaim> claims = occupancyManager.snapshotClaims();
    if (claims != null && !claims.isEmpty()) {
      for (OccupancyClaim claim : claims) {
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
        clearRuntimeCachesForTrain(trainName);
        releasedTrains++;
      }
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
      clearRuntimeCachesForTrain(candidate.trainName());
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

  /** 信号事件合并与缓存统计。 */
  public record SignalRuntimeStats(
      long pathCacheHit,
      long pathCacheMiss,
      long directionCacheHit,
      long directionCacheMiss,
      long envelopeBuildCount,
      int dirtyTrainCount,
      long coalescedEventCount,
      long reentrantStopSuppressed,
      long staleQueueCleanupCount) {}

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
      resolveTrackedTrainName(properties).ifPresent(activeTrainNames::add);
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
    TrainProperties properties = TrainPropertiesStore.get(trainName);
    String resolvedTrainName =
        resolveTrackedTrainName(properties)
            .filter(name -> !name.isBlank())
            .orElse(trainName.trim());
    if (properties != null
        && isSplitAliasName(
            normalizeTrainName(properties.getTrainName()).orElse(null), resolvedTrainName)) {
      return;
    }
    layoverRegistry.unregister(resolvedTrainName);
    if (occupancyManager != null) {
      occupancyManager.releaseByTrain(resolvedTrainName);
    }
    progressRegistry.remove(resolvedTrainName);
    clearRuntimeCachesForTrain(resolvedTrainName);
    refreshScheduledSurvivorAfterRemoval(resolvedTrainName, trainName);
  }

  private void refreshScheduledSurvivorAfterRemoval(String resolvedTrainName, String rawTrainName) {
    String resolvedKey = normalizeTrainKey(resolvedTrainName);
    String rawKey = normalizeTrainKey(rawTrainName);
    String survivor =
        resolvedKey.isEmpty() ? null : survivorRefreshAfterRemoval.remove(resolvedKey);
    if ((survivor == null || survivor.isBlank()) && !rawKey.isEmpty()) {
      survivor = survivorRefreshAfterRemoval.remove(rawKey);
    }
    if (survivor != null && !survivor.isBlank()) {
      refreshSignalByName(survivor);
    }
  }

  /** 清理某列车的运行时派生缓存，供事件链与周期 heal 共用。 */
  private void clearRuntimeCachesForTrain(String trainName) {
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return;
    }
    stallStates.remove(key);
    waypointStopStates.remove(key);
    missingSignalWarned.remove(key);
    stopWaypointLogState.remove(key);
    progressTriggerState.remove(key);
    clearDepartureGate(trainName);
    clearNodeHistory(trainName);
    dynamicAllocator.clearAllocations(trainName);
    routeTrainTracker.remove(trainName);
    effectiveNodeOverrides.remove(key);
    blockerSnapshots.remove(key);
    movementAuthorizationTokens.remove(key);
    movementInhibitors.remove(key);
    turnbackFootprintGuards.clear(trainName);
  }

  /**
   * 异常列车清理入口：用于 derail/split/脱挂等场景的兜底回收。
   *
   * <p>按来源收窄清理边界：
   *
   * <ul>
   *   <li>FTA 托管列车：split/脱挂/脱轨都记录诊断、清理运行时状态与占用，再销毁实体
   *   <li>普通 TrainCarts 列车：仅在 TrainCarts 明确报告 {@code Derailed} 时销毁实体
   * </ul>
   *
   * <p>普通列车的 member-remove 可能来自玩家拆车、其他插件重组或 TrainCarts 内部拆分过渡，不能按 FTA 半编组异常处理，否则会误伤非 FTA 列车。
   *
   * @param group 目标列车组
   * @param reason 清理原因（用于日志）
   */
  public void handleAbnormalGroup(MinecartGroup group, String reason) {
    handleAbnormalGroup(group, reason, null);
  }

  /**
   * 异常列车清理入口：记录 split/脱轨等诊断上下文后执行兜底回收。
   *
   * <p>detail 用于补充事件侧信息（例如被拆出的 member UUID、源/目标编组名），方便事后排查 TrainCarts unexpected split 的成因。 非 FTA
   * 列车只有明确脱轨状态会进入此清理；普通 split 事件会静默跳过并仅写入 debug，避免把其他系统的列车误当成 FTA 残编。
   *
   * @param group 目标列车组
   * @param reason 清理原因（用于日志）
   * @param detail 补充诊断信息（可为空）
   */
  public void handleAbnormalGroup(MinecartGroup group, String reason, String detail) {
    if (group == null) {
      return;
    }
    TrainProperties properties = group.getProperties();
    String normalizedReason =
        reason == null || reason.isBlank() ? "unknown" : reason.trim().toLowerCase(Locale.ROOT);
    boolean ftaManaged = properties != null && hasFtaRuntimeTag(properties);
    AbnormalCleanupPolicy cleanupPolicy =
        resolveAbnormalCleanupPolicy(ftaManaged, normalizedReason);
    if (!cleanupPolicy.process()) {
      debugLogger.accept(
          "异常列车清理跳过: train="
              + (properties != null
                  ? normalizeTrainName(properties.getTrainName()).orElse("-")
                  : "-")
              + " reason="
              + normalizedReason
              + " ftaManaged=false");
      return;
    }
    String rawTrainName =
        properties != null ? normalizeTrainName(properties.getTrainName()).orElse(null) : null;
    String logicalTrainName =
        ftaManaged
            ? normalizeTrainName(handleRenameIfNeeded(properties)).orElse(rawTrainName)
            : resolveTrackedTrainName(properties).orElse(rawTrainName);
    AbnormalGroupSnapshot snapshot =
        captureAbnormalGroupSnapshot(
            group,
            properties,
            normalizedReason,
            detail,
            rawTrainName,
            logicalTrainName,
            ftaManaged);
    HEALTH_LOGGER.warning(buildAbnormalCleanupWarning(snapshot));

    if (cleanupPolicy.cleanupRuntimeState()
        && snapshot.cleanupTrainName() != null
        && !snapshot.cleanupTrainName().isBlank()) {
      boolean skipStateCleanup = shouldSkipDuplicateAbnormalCleanup(snapshot.cleanupKey());
      if (!skipStateCleanup) {
        debugLogger.accept(
            "异常列车清理: train=" + snapshot.cleanupTrainName() + " reason=" + normalizedReason);
        handleTrainRemoved(snapshot.cleanupTrainName());
      } else {
        debugLogger.accept(
            "异常列车状态清理去重: train=" + snapshot.cleanupTrainName() + " reason=" + normalizedReason);
      }
    }
    // 状态清理按 trainName 去重，但实体销毁必须始终尝试覆盖当前事件组。
    // FTA split/脱挂的后续半编组可能命中去重窗口；非 FTA derailed 也没有 progress 可清，
    // 两者都依赖这里继续尝试销毁事件传入的实体。
    if (cleanupPolicy.destroyEntities()) {
      destroyAbnormalTrainEntities(snapshot.cleanupTrainName(), group, properties);
    }
  }

  /**
   * 异常清理去重：同一列车在短窗口内仅执行一次“运行时状态清理”。
   *
   * <p>TrainCarts 在 split/脱挂时可能连续抛出多个 member-remove 事件，不去重会导致重复日志与重复释放占用；但实体销毁仍由调用方继续尝试，
   * 以覆盖后续拆出的残余编组。
   *
   * @param trainName 列车名
   * @return true 表示命中去重窗口，应跳过本次状态清理
   */
  private boolean shouldSkipDuplicateAbnormalCleanup(String trainName) {
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return false;
    }
    long now = System.currentTimeMillis();
    java.util.concurrent.atomic.AtomicBoolean duplicate =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    abnormalCleanupLastAtMs.compute(
        key,
        (ignored, previous) -> {
          if (previous != null && now - previous < ABNORMAL_CLEANUP_DEDUP_MS) {
            duplicate.set(true);
            return previous;
          }
          return now;
        });
    if (abnormalCleanupLastAtMs.size() > 2_048) {
      pruneExpiredAbnormalCleanupKeys(now);
    }
    return duplicate.get();
  }

  /** 清理异常去重表中过期键，避免长期运行时键集合无限增长。 */
  private void pruneExpiredAbnormalCleanupKeys(long nowMs) {
    long expireBefore = nowMs - (ABNORMAL_CLEANUP_DEDUP_MS * 30L);
    abnormalCleanupLastAtMs.entrySet().removeIf(entry -> entry.getValue() < expireBefore);
  }

  /**
   * 执行异常列车实体销毁。
   *
   * <p>优先销毁 trainName 当前 holder，同时兜底销毁事件传入 group 与其 properties.holder，避免事件组引用失效时漏销毁。
   */
  private void destroyAbnormalTrainEntities(
      String trainName, MinecartGroup eventGroup, TrainProperties eventProperties) {
    java.util.Set<MinecartGroup> targets = new java.util.LinkedHashSet<>();
    TrainProperties canonicalProperties =
        trainName == null || trainName.isBlank() ? null : TrainPropertiesStore.get(trainName);
    if (canonicalProperties != null) {
      MinecartGroup holder = canonicalProperties.getHolder();
      if (holder != null) {
        targets.add(holder);
      }
    }
    if (eventProperties != null) {
      MinecartGroup holder = eventProperties.getHolder();
      if (holder != null) {
        targets.add(holder);
      }
    }
    if (eventGroup != null) {
      targets.add(eventGroup);
    }
    targets.addAll(findRelatedGroupsByLogicalTrainName(trainName));
    for (com.bergerkiller.bukkit.tc.controller.MinecartGroup target : targets) {
      if (target == null) {
        continue;
      }
      if (target.isValid()) {
        new TrainCartsRuntimeHandle(target).destroy();
      } else {
        debugLogger.accept(
            "异常列车销毁跳过（group 已 invalid）: train="
                + (trainName == null || trainName.isBlank() ? "-" : trainName));
      }
    }
  }

  /**
   * 按逻辑列车名查找同一列车的所有当前实体。
   *
   * <p>TrainCarts 脱轨或 split 事件可能只把单节车厢所在的半编组交给事件回调；这里再扫描当前在线 group，补齐仍携带同一 {@code FTA_TRAIN_NAME} 或
   * split 临时别名的其他半编组，尽量做到“发现单节异常，销毁整列逻辑列车”。
   */
  private Set<MinecartGroup> findRelatedGroupsByLogicalTrainName(String trainName) {
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return Set.of();
    }
    Set<MinecartGroup> matches = new LinkedHashSet<>();
    java.util.Collection<MinecartGroup> groups = MinecartGroupStore.getGroups();
    if (groups == null || groups.isEmpty()) {
      return matches;
    }
    for (MinecartGroup group : groups) {
      if (group == null || group.getProperties() == null) {
        continue;
      }
      if (isRelatedLogicalTrain(group.getProperties(), trainName)) {
        matches.add(group);
      }
    }
    return matches;
  }

  /**
   * 判断 TrainProperties 是否属于指定逻辑列车。
   *
   * <p>优先使用 {@code FTA_TRAIN_NAME}，同时兼容 TrainCarts split 后的 {@code ~a/~b}
   * 临时别名；用于异常清理时定位同一逻辑列车的所有残余实体。
   */
  boolean isRelatedLogicalTrain(TrainProperties properties, String trainName) {
    String key = normalizeTrainKey(trainName);
    if (properties == null || key.isEmpty()) {
      return false;
    }
    Optional<String> tracked = resolveTrackedTrainName(properties);
    if (tracked.isPresent() && key.equals(normalizeTrainKey(tracked.get()))) {
      return true;
    }
    String rawName = normalizeTrainName(properties.getTrainName()).orElse(null);
    return isSplitAliasName(rawName, trainName);
  }

  private AbnormalGroupSnapshot captureAbnormalGroupSnapshot(
      MinecartGroup group,
      TrainProperties properties,
      String reason,
      String detail,
      String rawTrainName,
      String logicalTrainName,
      boolean ftaManaged) {
    String cleanupTrainName = firstNonBlank(logicalTrainName, rawTrainName);
    String cleanupKey =
        cleanupTrainName != null
            ? cleanupTrainName
            : "group@" + Integer.toHexString(System.identityHashCode(group));
    RouteProgressRegistry.RouteProgressEntry progressEntry =
        cleanupTrainName != null ? progressRegistry.get(cleanupTrainName).orElse(null) : null;
    return new AbnormalGroupSnapshot(
        reason,
        detail,
        rawTrainName,
        logicalTrainName,
        cleanupTrainName,
        cleanupKey,
        ftaManaged,
        safeGroupSize(group),
        resolveGroupWorldName(group),
        describeMemberLocation(group != null ? group.head() : null),
        describeMemberLocation(group != null ? group.middle() : null),
        describeMemberLocation(group != null ? group.tail() : null),
        captureAbnormalProgressSnapshot(properties, progressEntry));
  }

  private AbnormalProgressSnapshot captureAbnormalProgressSnapshot(
      TrainProperties properties, RouteProgressRegistry.RouteProgressEntry entry) {
    if (entry != null) {
      return new AbnormalProgressSnapshot(
          entry.routeId() != null ? entry.routeId().value() : null,
          entry.currentIndex(),
          entry.nextTarget().map(NodeId::value).orElse(null),
          entry.lastPassedGraphNode().map(NodeId::value).orElse(null),
          entry.lastSignal() != null ? entry.lastSignal().name() : null);
    }
    Integer taggedIndex =
        properties != null
            ? TrainTagHelper.readIntTag(properties, RouteProgressRegistry.TAG_ROUTE_INDEX)
                .orElse(null)
            : null;
    String routeCode =
        properties != null
            ? TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_ROUTE_CODE)
                .orElse(null)
            : null;
    if (taggedIndex == null && routeCode == null) {
      return null;
    }
    return new AbnormalProgressSnapshot(routeCode, taggedIndex, null, null, null);
  }

  static String buildAbnormalCleanupWarning(AbnormalGroupSnapshot snapshot) {
    StringBuilder builder = new StringBuilder("异常列车清理");
    if (snapshot == null) {
      return builder.toString();
    }
    RuntimeDiagnosticFormatter.appendKeyValue(builder, "reason", snapshot.reason());
    RuntimeDiagnosticFormatter.appendKeyValue(builder, "detail", snapshot.detail());
    RuntimeDiagnosticFormatter.appendKeyValue(builder, "rawTrain", snapshot.rawTrainName());
    if (!sameDiagnosticText(snapshot.logicalTrainName(), snapshot.rawTrainName())) {
      RuntimeDiagnosticFormatter.appendKeyValue(
          builder, "logicalTrain", snapshot.logicalTrainName());
    }
    builder.append(" ftaManaged=").append(snapshot.ftaManaged());
    if (snapshot.carCount() >= 0) {
      builder.append(" cars=").append(snapshot.carCount());
    }
    RuntimeDiagnosticFormatter.appendKeyValue(builder, "world", snapshot.worldName());
    RuntimeDiagnosticFormatter.appendKeyValue(builder, "head", snapshot.headLocation());
    if (!sameDiagnosticText(snapshot.middleLocation(), snapshot.headLocation())
        && !sameDiagnosticText(snapshot.middleLocation(), snapshot.tailLocation())) {
      RuntimeDiagnosticFormatter.appendKeyValue(builder, "middle", snapshot.middleLocation());
    }
    RuntimeDiagnosticFormatter.appendKeyValue(builder, "tail", snapshot.tailLocation());
    if (snapshot.progress() != null) {
      RuntimeDiagnosticFormatter.appendKeyValue(builder, "route", snapshot.progress().routeId());
      if (snapshot.progress().currentIndex() != null) {
        builder.append(" index=").append(snapshot.progress().currentIndex());
      }
      RuntimeDiagnosticFormatter.appendKeyValue(builder, "next", snapshot.progress().nextTarget());
      RuntimeDiagnosticFormatter.appendKeyValue(
          builder, "lastPassed", snapshot.progress().lastPassedNode());
      RuntimeDiagnosticFormatter.appendKeyValue(
          builder, "signal", snapshot.progress().lastSignal());
    }
    return builder.toString();
  }

  private static boolean sameDiagnosticText(String left, String right) {
    if (left == null || right == null) {
      return false;
    }
    return left.equalsIgnoreCase(right);
  }

  private static String firstNonBlank(String... candidates) {
    if (candidates == null) {
      return null;
    }
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate.trim();
      }
    }
    return null;
  }

  private static int safeGroupSize(MinecartGroup group) {
    if (group == null) {
      return -1;
    }
    try {
      return group.size();
    } catch (Throwable ignored) {
      return -1;
    }
  }

  private static String resolveGroupWorldName(MinecartGroup group) {
    if (group != null && group.getWorld() != null) {
      return group.getWorld().getName();
    }
    return null;
  }

  private static String describeMemberLocation(MinecartMember<?> member) {
    if (member == null) {
      return null;
    }
    org.bukkit.block.Block block = member.getBlock(0, 0, 0);
    return block != null ? RuntimeDiagnosticFormatter.formatLocation(block.getLocation()) : null;
  }

  /** 异常列车诊断快照。 */
  record AbnormalGroupSnapshot(
      String reason,
      String detail,
      String rawTrainName,
      String logicalTrainName,
      String cleanupTrainName,
      String cleanupKey,
      boolean ftaManaged,
      int carCount,
      String worldName,
      String headLocation,
      String middleLocation,
      String tailLocation,
      AbnormalProgressSnapshot progress) {}

  /** 异常列车的运行时进度快照。 */
  record AbnormalProgressSnapshot(
      String routeId,
      Integer currentIndex,
      String nextTarget,
      String lastPassedNode,
      String lastSignal) {}

  /** 异常列车清理策略。 */
  record AbnormalCleanupPolicy(
      boolean process, boolean cleanupRuntimeState, boolean destroyEntities) {}

  /**
   * 列车运行时状态快照（用于健康检查）。
   *
   * @param trainName 列车名
   * @param progressIndex 当前进度索引
   * @param signalAspect 当前信号
   * @param speedBlocksPerTick 当前速度（blocks/tick）
   * @param lastPassedGraphNode 最近一次经过的图节点（可为空）
   */
  public record TrainRuntimeState(
      String trainName,
      int progressIndex,
      SignalAspect signalAspect,
      double speedBlocksPerTick,
      Optional<NodeId> lastPassedGraphNode) {

    /**
     * 兼容旧构造：未提供图节点推进信息时，默认视为未知。
     *
     * @param trainName 列车名
     * @param progressIndex 当前进度索引
     * @param signalAspect 当前信号
     * @param speedBlocksPerTick 当前速度（blocks/tick）
     */
    public TrainRuntimeState(
        String trainName, int progressIndex, SignalAspect signalAspect, double speedBlocksPerTick) {
      this(trainName, progressIndex, signalAspect, speedBlocksPerTick, Optional.empty());
    }
  }

  /** Health/runtime 桥接解析用途。 */
  public enum RuntimeTrainResolvePurpose {
    DESTROY,
    REFRESH_SIGNAL,
    REAPPLY_HARD_STOP,
    GET_STATE,
    DEADLOCK_CONTEXT,
    CLEAR_SELF_OWNED_CONFLICT
  }

  /** Health/runtime 桥接列车名匹配来源。 */
  public enum RuntimeTrainMatchKind {
    EXACT,
    RUNTIME_ALIAS,
    CANONICAL_NAME,
    ACTIVE_STATE,
    SPLIT_ALIAS,
    PREVIOUS_NAME,
    FAILED
  }

  /** Health/runtime 桥接解析结果。 */
  @SuppressFBWarnings(
      value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
      justification =
          "TrainProperties 是 TrainCarts 托管实体句柄；解析结果必须返回同一对象供 destroy/refresh/hard-stop 使用。")
  public record RuntimeTrainResolution(
      String requestedName,
      String resolvedName,
      TrainProperties properties,
      RuntimeTrainMatchKind matchedBy,
      Optional<UUID> trainPropertiesUuid,
      Optional<UUID> entityUuid,
      String failureReason) {

    public RuntimeTrainResolution {
      requestedName = requestedName == null ? "" : requestedName.trim();
      resolvedName = resolvedName == null ? "" : resolvedName.trim();
      matchedBy = matchedBy == null ? RuntimeTrainMatchKind.FAILED : matchedBy;
      trainPropertiesUuid = trainPropertiesUuid == null ? Optional.empty() : trainPropertiesUuid;
      entityUuid = entityUuid == null ? Optional.empty() : entityUuid;
      failureReason =
          failureReason == null || failureReason.isBlank() ? "NONE" : failureReason.trim();
    }

    public boolean propertiesFound() {
      return properties != null;
    }

    public boolean resolved() {
      return matchedBy != RuntimeTrainMatchKind.FAILED && !resolvedName.isBlank();
    }
  }

  /**
   * 互卡诊断用 blocker 条目。
   *
   * @param trainName blocker 列车名
   * @param conflictKey 命中的 conflict key；非冲突 blocker 为空
   * @param direction blocker 已知的单线走廊方向
   * @param ownerCanonical blocker 规范化列车名
   * @param resourceKey 原始资源键，包含资源类型
   * @param relation blocker 与当前请求的关系
   * @param intent 当前请求对该资源的意图
   * @param role blocker claim 角色
   * @param source 产生该 blocker 的 OCCUPANCY 判定来源
   * @param tick 近似服务端 tick
   * @param occupancyVersion 占用管理器版本
   */
  public record DeadlockBlockerInfo(
      String trainName,
      String conflictKey,
      Optional<CorridorDirection> direction,
      String ownerCanonical,
      String resourceKey,
      String relation,
      String intent,
      String role,
      String source,
      long tick,
      long occupancyVersion) {

    public DeadlockBlockerInfo {
      trainName = trainName == null ? "" : trainName.trim();
      conflictKey = conflictKey == null ? "" : conflictKey.trim();
      direction = direction == null ? Optional.empty() : direction;
      ownerCanonical =
          ownerCanonical == null || ownerCanonical.isBlank()
              ? TrainNameNormalizer.normalizeKey(trainName)
              : ownerCanonical.trim();
      resourceKey = resourceKey == null || resourceKey.isBlank() ? conflictKey : resourceKey.trim();
      relation = relation == null || relation.isBlank() ? "UNKNOWN" : relation.trim();
      intent = intent == null || intent.isBlank() ? "UNKNOWN" : intent.trim();
      role = role == null || role.isBlank() ? "UNKNOWN" : role.trim();
      source = source == null || source.isBlank() ? "unknown" : source.trim();
    }

    public DeadlockBlockerInfo(
        String trainName, String conflictKey, Optional<CorridorDirection> direction) {
      this(
          trainName,
          conflictKey,
          direction,
          TrainNameNormalizer.normalizeKey(trainName),
          conflictKey,
          "UNKNOWN",
          "UNKNOWN",
          "UNKNOWN",
          "legacy",
          -1L,
          -1L);
    }
  }

  /**
   * 互卡诊断用 blocker 快照。
   *
   * <p>保留 conflict/direction，供 HealthMonitor 只在“同一 single conflict 且双方方向已知相反”时进入自动 destroy。
   */
  public record DeadlockBlockerSnapshot(Set<DeadlockBlockerInfo> blockers, Instant sampledAt) {
    public DeadlockBlockerSnapshot {
      blockers = blockers == null ? Set.of() : Set.copyOf(blockers);
      sampledAt = sampledAt == null ? Instant.EPOCH : sampledAt;
    }

    public Set<String> trainNames() {
      Set<String> names = new LinkedHashSet<>();
      for (DeadlockBlockerInfo blocker : blockers) {
        if (blocker != null && !blocker.trainName().isBlank()) {
          names.add(blocker.trainName());
        }
      }
      return Set.copyOf(names);
    }
  }

  /**
   * 同向 follower 因 leader 停滞而无法进入 single zone 的低频证据。
   *
   * <p>该证据只供健康监控在 destroy 阈值之后做兜底目标选择，不参与 admission 放行，也不改变 physical signal 策略。
   */
  public record FollowerStuckLeaderEvidence(
      String followerTrain,
      String leaderTrain,
      String resource,
      Instant firstSeenAt,
      Instant sampledAt,
      int samples) {

    public FollowerStuckLeaderEvidence {
      followerTrain = followerTrain == null ? "" : followerTrain.trim();
      leaderTrain = leaderTrain == null ? "" : leaderTrain.trim();
      resource = resource == null || resource.isBlank() ? "-" : resource.trim();
      firstSeenAt = firstSeenAt == null ? Instant.EPOCH : firstSeenAt;
      sampledAt = sampledAt == null ? firstSeenAt : sampledAt;
      samples = Math.max(0, samples);
    }

    private FollowerStuckLeaderEvidence seen(Instant now) {
      Instant sampled = now == null ? Instant.now() : now;
      return new FollowerStuckLeaderEvidence(
          followerTrain, leaderTrain, resource, firstSeenAt, sampled, samples + 1);
    }
  }

  /**
   * HealthMonitor 选择互卡销毁 leader 所需的低频上下文。
   *
   * <p>该快照只用于最终兜底排序，不参与运行时信号放行。
   */
  public record DeadlockTrainContext(
      String trainName,
      int progressIndex,
      int routeSize,
      SignalAspect signalAspect,
      double speedBlocksPerTick,
      RouteOperationType operationType,
      int priority,
      boolean dwelling,
      boolean departureGateHeld,
      boolean layoverReady,
      boolean depotRelated,
      boolean nearRouteEnd,
      boolean hasPassengers,
      boolean manualHold) {

    public DeadlockTrainContext {
      trainName = trainName == null ? "" : trainName.trim();
      signalAspect = signalAspect == null ? SignalAspect.STOP : signalAspect;
      operationType = operationType == null ? RouteOperationType.OPERATION : operationType;
    }
  }

  /**
   * HealthMonitor 在销毁兜底前查询的 drain-through 诊断。
   *
   * <p>该记录只描述“已有 occupant 是否能沿当前有向路径清空 single/switcher zone”，不降低互卡销毁阈值，也不把硬 blocker 升级为 confirmed
   * deadlock。
   */
  public record DeadlockDrainability(
      String episodeId,
      String trainA,
      String trainB,
      String zoneId,
      boolean drainable,
      String drainCandidate,
      Optional<NodeId> exitNode,
      List<NodeId> drainPath,
      String reason,
      long remainingMsUntilDestroy) {

    public DeadlockDrainability {
      episodeId = episodeId == null ? "" : episodeId.trim();
      trainA = trainA == null ? "" : trainA.trim();
      trainB = trainB == null ? "" : trainB.trim();
      zoneId = zoneId == null ? "" : zoneId.trim();
      drainCandidate = drainCandidate == null ? "" : drainCandidate.trim();
      exitNode = exitNode == null ? Optional.empty() : exitNode;
      drainPath = drainPath == null ? List.of() : List.copyOf(drainPath);
      reason = reason == null || reason.isBlank() ? "UNKNOWN" : reason.trim();
    }
  }

  /**
   * HealthMonitor 传入 Smart recovery pipeline 的只读输入。
   *
   * <p>该快照只汇总 stuck 判定、信号、movement token、destination 与 blocker 状态；构造本身不执行任何控车、
   * destination、occupancy 或 destroy 副作用。
   */
  public record SmartRecoveryInput(
      String train,
      long stuckDurationSeconds,
      SignalAspect signal,
      boolean movementInhibited,
      SignalComputationTrace.TokenState movementTokenState,
      boolean destinationPresent,
      int blockerCount,
      Set<String> hardBlockers,
      NodeId currentNode,
      NodeId nextNode,
      String routeId,
      int currentIndex,
      String lastPassedGraphNode,
      boolean insideSingleRegion,
      boolean insideSwitcherRegion,
      boolean oppositeSingleConflictPresent,
      boolean downstreamBlocked,
      String primaryReason) {

    public SmartRecoveryInput {
      train = train == null ? "" : train.trim();
      stuckDurationSeconds = Math.max(0L, stuckDurationSeconds);
      signal = signal == null ? SignalAspect.STOP : signal;
      movementTokenState =
          movementTokenState == null ? SignalComputationTrace.TokenState.NONE : movementTokenState;
      blockerCount = Math.max(0, blockerCount);
      hardBlockers = hardBlockers == null ? Set.of() : Set.copyOf(hardBlockers);
      routeId = routeId == null || routeId.isBlank() ? "-" : routeId.trim();
      currentIndex = Math.max(-1, currentIndex);
      lastPassedGraphNode =
          lastPassedGraphNode == null || lastPassedGraphNode.isBlank()
              ? "-"
              : lastPassedGraphNode.trim();
      primaryReason =
          primaryReason == null || primaryReason.isBlank() ? "none" : primaryReason.trim();
    }

    public static SmartRecoveryInput fallback(
        String trainName, Duration stuckDuration, SignalAspect signal) {
      return new SmartRecoveryInput(
          trainName,
          stuckDuration == null ? 0L : stuckDuration.toSeconds(),
          signal,
          false,
          SignalComputationTrace.TokenState.NONE,
          false,
          0,
          Set.of(),
          null,
          null,
          "-",
          -1,
          "-",
          false,
          false,
          false,
          false,
          "health-progress-stuck");
    }
  }

  /**
   * 单线 stuck recovery 的排空证明。
   *
   * <p>该证明只用于允许 health recovery 进入既有 signal refresh 复判流程，不代表已经创建新的占用、movement token 或 DRAIN_THROUGH
   * authority。调用方仍必须经过最终信号判定。
   */
  private record SmartDrainOutProof(
      boolean allowed,
      String reason,
      OccupancyResource section,
      CorridorDirection trainDirection,
      String externalTrain,
      CorridorDirection externalDirection,
      OccupancyResource externalResource,
      OccupancyResource exitEdge,
      OccupancyResource exitNode) {

    private SmartDrainOutProof {
      reason = reason == null || reason.isBlank() ? "none" : reason.trim();
      trainDirection = trainDirection == null ? CorridorDirection.UNKNOWN : trainDirection;
      externalTrain = externalTrain == null || externalTrain.isBlank() ? "-" : externalTrain;
      externalDirection = externalDirection == null ? CorridorDirection.UNKNOWN : externalDirection;
    }

    private static SmartDrainOutProof denied(String reason) {
      return new SmartDrainOutProof(
          false,
          reason,
          null,
          CorridorDirection.UNKNOWN,
          "-",
          CorridorDirection.UNKNOWN,
          null,
          null,
          null);
    }

    private static SmartDrainOutProof allowed(
        OccupancyResource section,
        CorridorDirection trainDirection,
        OccupancyClaim externalClaim,
        OccupancyResource exitEdge,
        OccupancyResource exitNode) {
      return new SmartDrainOutProof(
          true,
          "occupancy-decreasing-drain-out",
          section,
          trainDirection,
          externalClaim == null ? "-" : externalClaim.trainName(),
          externalClaim == null
              ? CorridorDirection.UNKNOWN
              : externalClaim.corridorDirection().orElse(CorridorDirection.UNKNOWN),
          externalClaim == null ? null : externalClaim.resource(),
          exitEdge,
          exitNode);
    }
  }

  /**
   * Smart planner 最近一次方向证据审计快照。
   *
   * <p>HealthMonitor destroy review 只读取这个快照作为“需要重新审计方向”的证据，不把它视为可执行行车授权。
   */
  private record SmartDirectionAuditSnapshot(String trainName, String reason, Instant capturedAt) {
    private SmartDirectionAuditSnapshot {
      trainName = trainName == null || trainName.isBlank() ? "-" : trainName.trim();
      reason = reason == null || reason.isBlank() ? "NEED_DIRECTION_AUDIT" : reason.trim();
      capturedAt = capturedAt == null ? Instant.EPOCH : capturedAt;
    }
  }

  /** Smart recovery 动作尝试结果。 */
  public record SmartRecoveryActionResult(
      boolean candidate,
      boolean applied,
      String decision,
      String reason,
      DispatchEffectClass effectClass,
      SmartRecoveryEffectiveness effectiveness) {

    public SmartRecoveryActionResult {
      decision = decision == null || decision.isBlank() ? "SMART_RECOVERY_SKIPPED" : decision;
      reason = reason == null || reason.isBlank() ? "-" : reason.trim();
      effectClass = effectClass == null ? DispatchEffectClass.DIAGNOSTIC_ONLY : effectClass;
      effectiveness =
          effectiveness == null
              ? SmartRecoveryEffectiveness.defaultFor(applied, decision, "-")
              : effectiveness;
    }

    public SmartRecoveryActionResult(
        boolean candidate,
        boolean applied,
        String decision,
        String reason,
        DispatchEffectClass effectClass) {
      this(
          candidate,
          applied,
          decision,
          reason,
          effectClass,
          SmartRecoveryEffectiveness.defaultFor(applied, decision, "-"));
    }

    public static SmartRecoveryActionResult skipped(String reason) {
      return new SmartRecoveryActionResult(
          false, false, "SMART_RECOVERY_SKIPPED", reason, DispatchEffectClass.DIAGNOSTIC_ONLY);
    }
  }

  /** Smart recovery 执行后的有效性核验摘要。 */
  public record SmartRecoveryEffectiveness(
      String action,
      String conflictKey,
      boolean effective,
      SignalAspect finalAspect,
      boolean destinationBefore,
      boolean destinationAfter,
      SignalComputationTrace.TokenState tokenBefore,
      SignalComputationTrace.TokenState tokenAfter,
      boolean movementInhibitedBefore,
      boolean movementInhibitedAfter,
      boolean candidateReappeared,
      boolean sameStopReason,
      String reason) {

    public SmartRecoveryEffectiveness {
      action = action == null || action.isBlank() ? "SMART_RECOVERY_SKIPPED" : action.trim();
      conflictKey = conflictKey == null || conflictKey.isBlank() ? "-" : conflictKey.trim();
      finalAspect = finalAspect == null ? SignalAspect.STOP : finalAspect;
      tokenBefore = tokenBefore == null ? SignalComputationTrace.TokenState.NONE : tokenBefore;
      tokenAfter = tokenAfter == null ? SignalComputationTrace.TokenState.NONE : tokenAfter;
      reason = reason == null || reason.isBlank() ? "-" : reason.trim();
    }

    private static SmartRecoveryEffectiveness defaultFor(
        boolean applied, String action, String conflictKey) {
      return new SmartRecoveryEffectiveness(
          action,
          conflictKey,
          applied,
          SignalAspect.STOP,
          false,
          false,
          SignalComputationTrace.TokenState.NONE,
          SignalComputationTrace.TokenState.NONE,
          false,
          false,
          false,
          false,
          applied ? "legacy-result-assumed-effective" : "not-applied");
    }
  }

  private record BlockerSnapshot(
      Set<DeadlockBlockerInfo> blockers, Instant sampledAt, BlockerProgressWindow progressWindow) {
    private BlockerSnapshot {
      blockers = blockers == null ? Set.of() : Set.copyOf(blockers);
      sampledAt = sampledAt == null ? Instant.EPOCH : sampledAt;
      progressWindow = progressWindow == null ? BlockerProgressWindow.unknown() : progressWindow;
    }

    private BlockerSnapshot(Set<DeadlockBlockerInfo> blockers, Instant sampledAt) {
      this(blockers, sampledAt, BlockerProgressWindow.unknown());
    }

    private Set<String> blockerTrainNames() {
      Set<String> names = new LinkedHashSet<>();
      for (DeadlockBlockerInfo blocker : blockers) {
        if (blocker != null && !blocker.trainName().isBlank()) {
          names.add(blocker.trainName());
        }
      }
      return Set.copyOf(names);
    }
  }

  /** blocker 快照产生时的线路进度窗口；未知窗口仅保留旧 TTL 行为。 */
  private record BlockerProgressWindow(String routeId, int currentIndex, String lastPassedNode) {
    private BlockerProgressWindow {
      routeId = routeId == null || routeId.isBlank() ? "-" : routeId.trim();
      lastPassedNode =
          lastPassedNode == null || lastPassedNode.isBlank() ? "-" : lastPassedNode.trim();
    }

    private static BlockerProgressWindow unknown() {
      return new BlockerProgressWindow("-", -1, "-");
    }

    private boolean known() {
      return currentIndex >= 0 || !routeId.equals("-") || !lastPassedNode.equals("-");
    }
  }

  /**
   * 放行判定结果。
   *
   * @param proceedAllowed 最终是否允许放行（已扣除硬阻塞抑制）
   * @param rawAllowed 占用判定原始结果
   * @param hardBlockerBypass 是否命中了“allowed=true 但仍有硬阻塞”的抑制场景
   */
  private record ProceedDecision(
      boolean proceedAllowed, boolean rawAllowed, boolean hardBlockerBypass) {}

  /**
   * 获取列车运行时状态快照。
   *
   * @param trainName 列车名
   * @return 状态快照，若列车不存在或无进度则为空
   */
  public Optional<TrainRuntimeState> getTrainState(String trainName) {
    RuntimeTrainResolution resolution =
        resolveRuntimeTrainForHealth(trainName, RuntimeTrainResolvePurpose.GET_STATE);
    if (!resolution.resolved()) {
      traceRuntimeTrainResolveFailed(RuntimeTrainResolvePurpose.GET_STATE, resolution);
      return Optional.empty();
    }
    Optional<RouteProgressRegistry.RouteProgressEntry> entryOpt =
        progressRegistry.get(resolution.resolvedName());
    if (entryOpt.isEmpty()) {
      traceRuntimeTrainResolveFailed(
          RuntimeTrainResolvePurpose.GET_STATE,
          failedRuntimeTrainResolution(
              trainName, "ACTIVE_STATE_MISSING", resolution.resolvedName()));
      return Optional.empty();
    }
    RouteProgressRegistry.RouteProgressEntry entry = entryOpt.get();
    SignalAspect signal = entry.lastSignal();
    int idx = entry.currentIndex();

    TrainProperties properties = resolution.properties();
    double speedBpt = getCurrentSpeedBlocksPerTick(properties);

    return Optional.of(
        new TrainRuntimeState(
            entry.trainName(), idx, signal, speedBpt, entry.lastPassedGraphNode()));
  }

  private static double getCurrentSpeedBlocksPerTick(TrainProperties properties) {
    if (properties == null) {
      return 0.0;
    }
    com.bergerkiller.bukkit.tc.controller.MinecartGroup group = properties.getHolder();
    if (group == null || !group.isValid()) {
      return 0.0;
    }
    RuntimeTrainHandle handle = new TrainCartsRuntimeHandle(group);
    return handle.currentSpeedBlocksPerTick();
  }

  private Optional<RouteOperationType> resolveRouteOperationTypeForRecovery(
      RouteProgressRegistry.RouteProgressEntry entry) {
    if (entry == null || entry.routeUuid() == null) {
      return Optional.empty();
    }
    return dispatchPriorityResolver.resolveOperationType(entry.routeUuid());
  }

  private static boolean isDepotRelated(
      RouteProgressRegistry.RouteProgressEntry entry,
      RouteDefinition route,
      RouteOperationType operationType) {
    if (entry == null || route == null || route.waypoints().isEmpty()) {
      return false;
    }
    int index = Math.max(0, Math.min(entry.currentIndex(), route.waypoints().size() - 1));
    NodeId current = route.waypoints().get(index);
    NodeId next = index + 1 < route.waypoints().size() ? route.waypoints().get(index + 1) : null;
    return nodeLooksLikeDepot(current)
        || nodeLooksLikeDepot(next)
        || (operationType == RouteOperationType.CREATE && index <= 1);
  }

  private static boolean nodeLooksLikeDepot(NodeId nodeId) {
    return nodeId != null && nodeId.value().toUpperCase(Locale.ROOT).contains(":D:");
  }

  private static boolean hasPassenger(TrainProperties properties) {
    return TrainTagHelper.readTagValue(properties, "FTA_HAS_PASSENGERS")
        .map(Boolean::parseBoolean)
        .orElse(false);
  }

  /**
   * 获取列车最近一次占用判定中的阻塞列车集合。
   *
   * <p>用于健康监控识别“互相阻塞”场景。返回值会按 {@code maxAge} 过滤，避免使用过期快照。
   *
   * @param trainName 列车名
   * @param maxAge 最大快照年龄（为空时使用默认 TTL）
   * @return 阻塞列车集合（不含自身）
   */
  public Set<String> recentBlockerTrains(String trainName, Duration maxAge) {
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return Set.of();
    }
    BlockerSnapshot snapshot = blockerSnapshots.get(key);
    if (snapshot == null) {
      return Set.of();
    }
    Duration ttl =
        maxAge == null || maxAge.isNegative() || maxAge.isZero() ? BLOCKER_SNAPSHOT_TTL : maxAge;
    Instant cutoff = Instant.now().minus(ttl);
    if (snapshot.sampledAt().isBefore(cutoff)) {
      blockerSnapshots.remove(key, snapshot);
      return Set.of();
    }
    if (!blockerSnapshotProgressCurrent(trainName, snapshot)) {
      blockerSnapshots.remove(key, snapshot);
      traceBlockerSnapshotProgressMoved(trainName, snapshot.progressWindow());
      return Set.of();
    }
    return snapshot.blockerTrainNames();
  }

  private BlockerProgressWindow captureBlockerProgressWindow(String trainName) {
    return progressRegistry
        .get(trainName)
        .map(
            entry ->
                new BlockerProgressWindow(
                    entry.routeId().value(),
                    entry.currentIndex(),
                    entry.lastPassedGraphNode().map(NodeId::value).orElse("-")))
        .orElseGet(BlockerProgressWindow::unknown);
  }

  private boolean blockerSnapshotProgressCurrent(String trainName, BlockerSnapshot snapshot) {
    if (snapshot == null || !snapshot.progressWindow().known()) {
      return true;
    }
    Optional<RouteProgressRegistry.RouteProgressEntry> current = progressRegistry.get(trainName);
    if (current.isEmpty()) {
      return false;
    }
    BlockerProgressWindow expected = snapshot.progressWindow();
    RouteProgressRegistry.RouteProgressEntry entry = current.get();
    String currentLastPassed = entry.lastPassedGraphNode().map(NodeId::value).orElse("-");
    return (expected.routeId().equals("-") || expected.routeId().equals(entry.routeId().value()))
        && (expected.currentIndex() < 0 || expected.currentIndex() == entry.currentIndex())
        && (expected.lastPassedNode().equals("-")
            || expected.lastPassedNode().equals(currentLastPassed));
  }

  private void traceBlockerSnapshotProgressMoved(
      String trainName, BlockerProgressWindow snapshotWindow) {
    BlockerProgressWindow expected =
        snapshotWindow == null ? BlockerProgressWindow.unknown() : snapshotWindow;
    Optional<RouteProgressRegistry.RouteProgressEntry> current = progressRegistry.get(trainName);
    debugLogger.accept(
        "SMART_LIVE_BLOCKER_SNAPSHOT_REJECTED train="
            + trainName
            + " reason=PROGRESS_WINDOW_MOVED snapshotRouteId="
            + expected.routeId()
            + " currentRouteId="
            + current.map(entry -> entry.routeId().value()).orElse("-")
            + " snapshotCurrentIndex="
            + expected.currentIndex()
            + " currentIndex="
            + current.map(RouteProgressRegistry.RouteProgressEntry::currentIndex).orElse(-1)
            + " snapshotLastPassed="
            + expected.lastPassedNode()
            + " currentLastPassed="
            + current
                .flatMap(RouteProgressRegistry.RouteProgressEntry::lastPassedGraphNode)
                .map(NodeId::value)
                .orElse("-"));
  }

  /**
   * 获取列车最近一次占用判定中的互卡诊断 blocker 快照。
   *
   * <p>与 {@link #recentBlockerTrains(String, Duration)} 不同，该接口保留 single conflict key 与走廊方向；健康监控的自动
   * destroy 只使用这类带方向的快照，避免 UNKNOWN/硬 blocker 被误判成可释放互卡。
   *
   * @param trainName 列车名
   * @param maxAge 最大快照年龄
   * @return 仍在有效期内的 blocker 快照
   */
  public DeadlockBlockerSnapshot recentDeadlockBlockers(String trainName, Duration maxAge) {
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return new DeadlockBlockerSnapshot(Set.of(), Instant.EPOCH);
    }
    BlockerSnapshot snapshot = blockerSnapshots.get(key);
    if (snapshot == null) {
      debugLogger.accept(
          "SMART_LIVE_BLOCKER_SNAPSHOT_REJECTED train=" + trainName + " reason=missing ageMs=-1");
      return new DeadlockBlockerSnapshot(Set.of(), Instant.EPOCH);
    }
    Duration ttl =
        maxAge == null || maxAge.isNegative() || maxAge.isZero() ? BLOCKER_SNAPSHOT_TTL : maxAge;
    Instant cutoff = Instant.now().minus(ttl);
    if (snapshot.sampledAt().isBefore(cutoff)) {
      blockerSnapshots.remove(key, snapshot);
      debugLogger.accept(
          "SMART_LIVE_BLOCKER_SNAPSHOT_REJECTED train="
              + trainName
              + " reason=expired ageMs="
              + Duration.between(snapshot.sampledAt(), Instant.now()).toMillis());
      return new DeadlockBlockerSnapshot(Set.of(), Instant.EPOCH);
    }
    if (!blockerSnapshotProgressCurrent(trainName, snapshot)) {
      blockerSnapshots.remove(key, snapshot);
      traceBlockerSnapshotProgressMoved(trainName, snapshot.progressWindow());
      return new DeadlockBlockerSnapshot(Set.of(), Instant.EPOCH);
    }
    long ageMs = Duration.between(snapshot.sampledAt(), Instant.now()).toMillis();
    for (DeadlockBlockerInfo blocker : snapshot.blockers()) {
      if (blocker == null) {
        continue;
      }
      debugLogger.accept(
          "SMART_LIVE_BLOCKER_SNAPSHOT_USED train="
              + trainName
              + " blockerTrain="
              + blocker.trainName()
              + " resource="
              + blocker.resourceKey()
              + " ageMs="
              + ageMs);
    }
    return new DeadlockBlockerSnapshot(snapshot.blockers(), snapshot.sampledAt());
  }

  /**
   * 获取 follower 被同向 stuck leader 阻塞的近期证据。
   *
   * <p>返回值按 TTL 过滤；过期证据会被清理，避免 health fallback 使用旧 admission 结论。
   *
   * @param trainName follower 列车名
   * @param maxAge 最大证据年龄
   * @return 仍在有效期内的 stuck leader 证据
   */
  public Optional<FollowerStuckLeaderEvidence> recentFollowerStuckLeaderEvidence(
      String trainName, Duration maxAge) {
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return Optional.empty();
    }
    FollowerStuckLeaderEvidence evidence = followerStuckLeaderEvidence.get(key);
    if (evidence == null) {
      return Optional.empty();
    }
    Duration ttl =
        maxAge == null || maxAge.isNegative() || maxAge.isZero() ? BLOCKER_SNAPSHOT_TTL : maxAge;
    Instant cutoff = Instant.now().minus(ttl);
    if (evidence.sampledAt().isBefore(cutoff)) {
      followerStuckLeaderEvidence.remove(key, evidence);
      return Optional.empty();
    }
    return Optional.of(evidence);
  }

  private void rememberFollowerStuckLeaderEvidence(
      String followerTrain, String leaderTrain, String resource, Instant now) {
    String followerKey = normalizeTrainKey(followerTrain);
    String leaderKey = normalizeTrainKey(leaderTrain);
    if (followerKey.isEmpty() || leaderKey.isEmpty() || followerKey.equals(leaderKey)) {
      return;
    }
    Instant sampled = now == null ? Instant.now() : now;
    String resourceKey = resource == null || resource.isBlank() ? "-" : resource.trim();
    followerStuckLeaderEvidence.compute(
        followerKey,
        (unused, existing) -> {
          if (existing == null
              || !TrainNameNormalizer.sameLogicalTrain(existing.leaderTrain(), leaderTrain)
              || !existing.resource().equals(resourceKey)) {
            return new FollowerStuckLeaderEvidence(
                followerTrain, leaderTrain, resourceKey, sampled, sampled, 1);
          }
          return existing.seen(sampled);
        });
  }

  /** 返回指定列车当前持有的 conflict claim key，用于健康诊断判定 occupant-to-many 模式。 */
  public Set<String> currentConflictClaimKeys(String trainName) {
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty() || occupancyManager == null) {
      return Set.of();
    }
    Set<String> keys = new LinkedHashSet<>();
    for (OccupancyClaim claim : occupancyManager.snapshotClaims()) {
      if (claim == null
          || claim.trainName() == null
          || claim.resource() == null
          || claim.resource().kind() != ResourceKind.CONFLICT) {
        continue;
      }
      if (TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
        keys.add(claim.resource().key());
      }
    }
    return Set.copyOf(keys);
  }

  /** HealthMonitor 在 destroy 前查询列车是否仍处于 Phase 1.8 unlock reservation 观察期。 */
  public boolean hasActiveSmartUnlockReservation(String trainName) {
    String key = normalizeTrainKey(trainName);
    return !key.isEmpty() && smartUnlockReservationsByTrain.containsKey(key);
  }

  /** 查询近期是否已有 unlock reservation 真实释放 blocker，避免 destroy 抢在成功恢复后执行。 */
  public boolean recentSmartUnlockBlockerRelease(String trainName, Duration maxAge) {
    return recentSmartUnlockEvent(smartUnlockBlockerReleaseAt, trainName, maxAge);
  }

  /** 查询近期是否出现过 unlock reservation 无释放超时，用作 destroy 兜底证据。 */
  public boolean recentSmartUnlockNoReleaseTimeout(String trainName, Duration maxAge) {
    return recentSmartUnlockEvent(smartUnlockNoReleaseTimeouts, trainName, maxAge);
  }

  private static boolean recentSmartUnlockEvent(
      java.util.concurrent.ConcurrentMap<String, Instant> events,
      String trainName,
      Duration maxAge) {
    if (events == null) {
      return false;
    }
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return false;
    }
    Instant at = events.get(key);
    if (at == null) {
      return false;
    }
    Duration ttl =
        maxAge == null || maxAge.isNegative() || maxAge.isZero()
            ? SMART_UNLOCK_NO_RELEASE_COOLDOWN
            : maxAge;
    if (Instant.now().isAfter(at.plus(ttl))) {
      events.remove(key, at);
      return false;
    }
    return true;
  }

  /** 构建 Smart recovery 的只读输入快照。 */
  public SmartRecoveryInput smartRecoveryInput(
      String trainName, Duration stuckDuration, SignalAspect fallbackSignal) {
    RuntimeTrainResolution resolution =
        resolveRuntimeTrainForHealth(trainName, RuntimeTrainResolvePurpose.GET_STATE);
    String resolvedName = resolution.resolved() ? resolution.resolvedName() : trainName;
    Optional<RouteProgressRegistry.RouteProgressEntry> entryOpt =
        progressRegistry.get(resolvedName);
    if (entryOpt.isEmpty() && trainName != null && !trainName.equals(resolvedName)) {
      entryOpt = progressRegistry.get(trainName);
    }
    Optional<ControlDiagnostics> diagnosticsOpt = getDiagnostics(resolvedName);
    if (diagnosticsOpt.isEmpty() && trainName != null && !trainName.equals(resolvedName)) {
      diagnosticsOpt = getDiagnostics(trainName);
    }
    TrainProperties properties = resolution.properties();
    if (properties == null) {
      properties = resolveTrainPropertiesByName(resolvedName).orElse(null);
    }
    MovementAuthorizationToken token =
        movementToken(resolvedName).or(() -> movementToken(trainName)).orElse(null);
    boolean movementInhibited = isMovementInhibited(resolvedName) || isMovementInhibited(trainName);
    SignalComputationTrace.TokenState tokenState = tokenState(resolvedName, token);
    if (tokenState == SignalComputationTrace.TokenState.NONE && trainName != null) {
      tokenState = tokenState(trainName, token);
    }
    boolean destinationPresent =
        (properties != null && !readDestination(properties).isBlank())
            || diagnosticsOpt.map(ControlDiagnostics::destinationPresentWhileBlocked).orElse(false);
    Set<String> blockers = recentBlockerTrains(resolvedName, BLOCKER_SNAPSHOT_TTL);
    if (blockers.isEmpty() && trainName != null && !trainName.equals(resolvedName)) {
      blockers = recentBlockerTrains(trainName, BLOCKER_SNAPSHOT_TTL);
    }
    Set<String> conflictClaims = currentConflictClaimKeys(resolvedName);
    if (conflictClaims.isEmpty() && trainName != null && !trainName.equals(resolvedName)) {
      conflictClaims = currentConflictClaimKeys(trainName);
    }
    boolean insideSingle =
        conflictClaims.stream().anyMatch(key -> key != null && key.startsWith("single:"));
    boolean insideSwitcher =
        conflictClaims.stream().anyMatch(key -> key != null && key.startsWith("switcher:"));
    boolean oppositeSingleConflict =
        recentDeadlockBlockers(resolvedName, BLOCKER_SNAPSHOT_TTL).blockers().stream()
            .anyMatch(
                blocker ->
                    blocker != null
                        && blocker.conflictKey().startsWith("single:")
                        && blocker.direction().isPresent()
                        && blocker.direction().get() != CorridorDirection.UNKNOWN);
    ControlDiagnostics diagnostics = diagnosticsOpt.orElse(null);
    RouteProgressRegistry.RouteProgressEntry entry = entryOpt.orElse(null);
    NodeId currentNode = diagnostics == null ? null : diagnostics.currentNode();
    NodeId nextNode =
        diagnostics != null && diagnostics.nextNode() != null
            ? diagnostics.nextNode()
            : entry == null ? null : entry.nextTarget().orElse(null);
    String routeId =
        diagnostics != null && diagnostics.routeId() != null
            ? diagnostics.routeId().toString()
            : entry == null ? "-" : entry.routeId().toString();
    int currentIndex =
        diagnostics != null
            ? diagnostics.currentIndex()
            : entry == null ? -1 : entry.currentIndex();
    String lastPassed =
        entry == null ? "-" : entry.lastPassedGraphNode().map(NodeId::value).orElse("-");
    SignalAspect signal =
        fallbackSignal != null
            ? fallbackSignal
            : diagnostics != null
                ? diagnostics.currentSignal()
                : entry == null ? SignalAspect.STOP : entry.lastSignal();
    String primaryReason = recoveryPrimaryReason(diagnostics);
    boolean downstreamBlocked =
        !blockers.isEmpty()
            || (diagnostics != null
                && diagnostics.signalBlockerResources() != null
                && !diagnostics.signalBlockerResources().isEmpty());
    return new SmartRecoveryInput(
        resolvedName,
        stuckDuration == null ? 0L : stuckDuration.toSeconds(),
        signal,
        movementInhibited,
        tokenState,
        destinationPresent,
        blockers.size(),
        blockers,
        currentNode,
        nextNode,
        routeId,
        currentIndex,
        lastPassed,
        insideSingle,
        insideSwitcher,
        oppositeSingleConflict,
        downstreamBlocked,
        primaryReason);
  }

  /** 兼容测试与调用方省略当前信号时的恢复输入构造。 */
  public SmartRecoveryInput smartRecoveryInput(String trainName, Duration stuckDuration) {
    return smartRecoveryInput(trainName, stuckDuration, null);
  }

  /**
   * 执行 Smart self-owned stale retain release。
   *
   * <p>该入口只释放占用层已识别的自持 stale/protective CONFLICT retain。它不会清理 destination、不会 invalidate movement
   * token，也不会释放车体 NODE/EDGE claim；真实 mutation 必须先通过 OCCUPANCY_MUTATION effect gate。
   */
  public SmartRecoveryActionResult applySmartSelfOwnedStaleRetainRelease(SmartRecoveryInput input) {
    if (input == null || input.train().isBlank()) {
      return SmartRecoveryActionResult.skipped("missing-input");
    }
    if (!(occupancyManager instanceof SimpleOccupancyManager manager)) {
      return SmartRecoveryActionResult.skipped("occupancy-manager-does-not-support-self-retain");
    }
    Optional<BoundedSelfOwnedRetainCandidate> boundedCandidateOpt =
        boundedSelfOwnedRetainCandidate(manager, input);
    if (boundedCandidateOpt.isEmpty()) {
      debugLogger.accept(
          "SMART_STALE_SELF_RETAIN_RELEASE_SKIPPED train="
              + input.train()
              + " reason=self-owned-stale-retain-not-found");
      return SmartRecoveryActionResult.skipped("self-owned-stale-retain-not-found");
    }
    BoundedSelfOwnedRetainCandidate boundedCandidate = boundedCandidateOpt.get();
    SimpleOccupancyManager.SelfOwnedStaleRetainCandidate candidate = boundedCandidate.candidate();
    DispatchEffectClass effectClass = DispatchEffectClass.OCCUPANCY_MUTATION;
    debugLogger.accept(
        "SMART_STALE_SELF_RETAIN_RELEASE_CANDIDATE train="
            + input.train()
            + " resource="
            + candidate.resource()
            + " claimRole="
            + candidate.claimRole()
            + " requestIntent="
            + candidate.requestIntent()
            + " heldDirection="
            + candidate.heldDirection()
            + " requestedDirection="
            + candidate.requestedDirection()
            + " reason="
            + candidate.reason()
            + " effectClass="
            + effectClass);
    debugLogger.accept(
        "SMART_UNLOCK_ATTEMPTED train="
            + input.train()
            + " recoveryDecision=SMART_RELEASE_SELF_OWNED_STALE_RETAIN"
            + " effectClass="
            + effectClass);
    if (!smartDispatcherRegisteredActionAllowed(
        input.train(),
        "health-progress-stuck",
        DispatchAction.RELEASE_SELF_OWNED_STALE_PROTECTIVE_RETAIN)) {
      if (smartDispatcherMode() == SmartDispatcherMode.OBSERVE_ONLY) {
        debugLogger.accept(
            "SMART_STALE_SELF_RETAIN_WOULD_RELEASE train="
                + input.train()
                + " resource="
                + candidate.resource()
                + " mode="
                + smartDispatcherMode()
                + " effectClass="
                + effectClass
                + " occupancyMutated=false");
      } else {
        debugLogger.accept(
            "SMART_STALE_SELF_RETAIN_RELEASE_SUPPRESSED_BY_MODE train="
                + input.train()
                + " mode="
                + smartDispatcherMode()
                + " effectClass="
                + effectClass);
      }
      debugLogger.accept(
          "SMART_RECOVERY_SUPPRESSED_BY_MODE train="
              + input.train()
              + " mode="
              + smartDispatcherMode()
              + " recoveryDecision=SMART_RELEASE_SELF_OWNED_STALE_RETAIN"
              + " effectClass="
              + effectClass);
      return new SmartRecoveryActionResult(
          true, false, "SMART_RELEASE_SELF_OWNED_STALE_RETAIN", "suppressed-by-mode", effectClass);
    }
    debugLogger.accept(
        "SMART_RECOVERY_ALLOWED_BY_EFFECT_GATE train="
            + input.train()
            + " recoveryDecision=SMART_RELEASE_SELF_OWNED_STALE_RETAIN"
            + " effectClass="
            + effectClass
            + " mode="
            + smartDispatcherMode());
    SimpleOccupancyManager.SelfOwnedStaleRetainReleaseResult release =
        manager.releaseSelfOwnedStaleRetain(input.train(), candidate);
    if (!release.released()) {
      debugLogger.accept(
          "SMART_UNLOCK_SKIPPED train="
              + input.train()
              + " recoveryDecision=SMART_RELEASE_SELF_OWNED_STALE_RETAIN"
              + " reason="
              + release.reason());
      debugLogger.accept(
          "SMART_STALE_SELF_RETAIN_RELEASE_SKIPPED train="
              + input.train()
              + " reason="
              + release.reason());
      return new SmartRecoveryActionResult(
          true, false, "SMART_RELEASE_SELF_OWNED_STALE_RETAIN", release.reason(), effectClass);
    }
    debugLogger.accept(
        "SMART_STALE_SELF_RETAIN_RELEASE_APPLIED train="
            + input.train()
            + " releasedResources="
            + release.releasedResources()
            + " destinationMutated=false"
            + " tokenInvalidated=false"
            + " occupancyMutated=true"
            + " destroy=false");
    debugLogger.accept(
        "SMART_STALE_SELF_RETAIN_RELEASE_VERIFY train="
            + input.train()
            + " remainingCandidate="
            + manager.selfOwnedStaleRetainReleaseCandidate(input.train()).isPresent()
            + " releasedResources="
            + release.releasedResources());
    OccupancyDecision verified = manager.canEnterPreview(boundedCandidate.request());
    debugLogger.accept(
        "SMART_STALE_SELF_RETAIN_RELEASE_REEVALUATED train="
            + input.train()
            + " result="
            + (verified.allowed() ? "ALLOW" : "STILL_BLOCKED")
            + " reason="
            + verified.reason()
            + " blockers="
            + summarizeBlockers(verified));
    boolean candidateReappeared =
        manager.selfOwnedStaleRetainReleaseCandidate(input.train()).isPresent();
    SmartRecoveryEffectiveness effectiveness =
        verifySmartRecoveryEffect(
            "SMART_RELEASE_SELF_OWNED_STALE_RETAIN",
            input,
            input.train(),
            candidate.resource().key(),
            candidateReappeared,
            null);
    debugLogger.accept(
        "SMART_UNLOCK_APPLIED train="
            + input.train()
            + " recoveryDecision=SMART_RELEASE_SELF_OWNED_STALE_RETAIN"
            + " reason="
            + release.reason());
    return new SmartRecoveryActionResult(
        true,
        true,
        "SMART_RELEASE_SELF_OWNED_STALE_RETAIN",
        release.reason(),
        effectClass,
        effectiveness);
  }

  /** 自持 stale retain 在占用判定链中的一次收敛尝试。 */
  private record SelfOwnedRetainDecisionRecovery(OccupancyDecision decision, boolean applied) {
    private SelfOwnedRetainDecisionRecovery {
      Objects.requireNonNull(decision, "decision");
    }

    private static SelfOwnedRetainDecisionRecovery noChange(OccupancyDecision decision) {
      return new SelfOwnedRetainDecisionRecovery(decision, false);
    }
  }

  /** 已通过 P0 边界校验的自持 retain 候选和对应只读复核请求。 */
  private record BoundedSelfOwnedRetainCandidate(
      OccupancyRequest request,
      OccupancyDecision decision,
      SimpleOccupancyManager.SelfOwnedStaleRetainCandidate candidate) {}

  private Optional<BoundedSelfOwnedRetainCandidate> boundedSelfOwnedRetainCandidate(
      SimpleOccupancyManager manager, SmartRecoveryInput input) {
    if (manager == null || input == null || input.train().isBlank()) {
      return Optional.empty();
    }
    Optional<SimpleOccupancyManager.SelfOwnedStaleRetainCandidate> candidateOpt =
        manager.selfOwnedStaleRetainReleaseCandidate(input.train());
    if (candidateOpt.isEmpty()) {
      return Optional.empty();
    }
    SimpleOccupancyManager.SelfOwnedStaleRetainCandidate candidate = candidateOpt.get();
    Set<String> currentConflictClaims = currentConflictClaimKeys(input.train());
    if (!input.insideSingleRegion()
        || candidate.resource().kind() != ResourceKind.CONFLICT
        || !candidate.resource().key().startsWith("single:")
        || !currentConflictClaims.contains(candidate.resource().key())) {
      debugLogger.accept(
          "SMART_STALE_SELF_RETAIN_RELEASE_SKIPPED train="
              + input.train()
              + " resource="
              + candidate.resource()
              + " currentConflictClaims="
              + currentConflictClaims
              + " insideSingleRegion="
              + input.insideSingleRegion()
              + " reason=outside-current-single-region");
      return Optional.empty();
    }
    OccupancyRequest request =
        selfOwnedRetainValidationRequest(input.train(), candidate, Instant.now());
    if (singleRegionOppositeOrUnknownExternalBarrier(
        input.train(), List.of(candidate.resource()), candidate.requestedDirection())) {
      traceSingleRegionHardBarrier(
          input.train(),
          DispatchAction.RELEASE_SELF_OWNED_STALE_PROTECTIVE_RETAIN,
          "self-retain-release-validator",
          List.of(candidate.resource()));
      return Optional.empty();
    }
    OccupancyDecision decision = selfOwnedRetainValidationDecision(request, candidate);
    Optional<SimpleOccupancyManager.SelfOwnedStaleRetainCandidate> previewCandidate =
        manager.previewSelfOwnedStaleRetainReleaseCandidate(request);
    if (previewCandidate.isEmpty()
        || !sameSelfOwnedRetainCandidate(candidate, previewCandidate.get())
        || !isBoundedSelfOwnedProtectiveRetainCandidate(request, decision, candidate)) {
      debugLogger.accept(
          "SMART_STALE_SELF_RETAIN_RELEASE_SKIPPED train="
              + input.train()
              + " resource="
              + candidate.resource()
              + " claimRole="
              + candidate.claimRole()
              + " heldDirection="
              + candidate.heldDirection()
              + " requestedDirection="
              + candidate.requestedDirection()
              + " reason=outside-p0-boundary");
      return Optional.empty();
    }
    return Optional.of(new BoundedSelfOwnedRetainCandidate(request, decision, candidate));
  }

  /**
   * 将 self-owned opposite-direction PROTECTIVE_RETAIN 从诊断候选推进到真实占用 mutation。
   *
   * <p>该方法只服务发车/信号授权链上的 recoverable blocker：OFF 与 OBSERVE_ONLY 均不改变状态；ENFORCE
   * 也只释放同一逻辑列车、同一冲突资源、相反方向的 {@link ClaimRole#PROTECTIVE_RETAIN}。释放后必须立即重新判定占用，调用方只使用重新判定结果继续
   * acquire 或 fail-safe STOP。
   */
  private SelfOwnedRetainDecisionRecovery maybeRecoverSelfOwnedStaleRetainPreview(
      OccupancyRequest request, String source) {
    OccupancyDecision noChange =
        new OccupancyDecision(false, Instant.now(), SignalAspect.STOP, List.of(), false, "none");
    if (request == null || !(occupancyManager instanceof SimpleOccupancyManager manager)) {
      return SelfOwnedRetainDecisionRecovery.noChange(noChange);
    }
    Optional<SimpleOccupancyManager.SelfOwnedStaleRetainCandidate> candidateOpt =
        manager.previewSelfOwnedStaleRetainReleaseCandidate(request);
    if (candidateOpt.isEmpty()) {
      return SelfOwnedRetainDecisionRecovery.noChange(noChange);
    }
    SimpleOccupancyManager.SelfOwnedStaleRetainCandidate candidate = candidateOpt.get();
    OccupancyDecision decision = selfOwnedRetainValidationDecision(request, candidate);
    return maybeRecoverSelfOwnedStaleRetain(request, decision, candidate, source);
  }

  private SelfOwnedRetainDecisionRecovery maybeRecoverSelfOwnedStaleRetain(
      OccupancyRequest request, OccupancyDecision decision, String source) {
    if (decision == null) {
      return new SelfOwnedRetainDecisionRecovery(
          new OccupancyDecision(false, Instant.now(), SignalAspect.STOP, List.of(), false, "none"),
          false);
    }
    if (request == null || decision.allowed()) {
      return SelfOwnedRetainDecisionRecovery.noChange(decision);
    }
    if (!(occupancyManager instanceof SimpleOccupancyManager manager)) {
      return SelfOwnedRetainDecisionRecovery.noChange(decision);
    }
    Optional<SimpleOccupancyManager.SelfOwnedStaleRetainCandidate> candidateOpt =
        manager.selfOwnedStaleRetainReleaseCandidate(request.trainName());
    if (candidateOpt.isEmpty()) {
      return SelfOwnedRetainDecisionRecovery.noChange(decision);
    }
    SimpleOccupancyManager.SelfOwnedStaleRetainCandidate candidate = candidateOpt.get();
    return maybeRecoverSelfOwnedStaleRetain(request, decision, candidate, source);
  }

  private SelfOwnedRetainDecisionRecovery maybeRecoverSelfOwnedStaleRetain(
      OccupancyRequest request,
      OccupancyDecision decision,
      SimpleOccupancyManager.SelfOwnedStaleRetainCandidate candidate,
      String source) {
    if (decision == null) {
      return new SelfOwnedRetainDecisionRecovery(
          new OccupancyDecision(false, Instant.now(), SignalAspect.STOP, List.of(), false, "none"),
          false);
    }
    if (request == null || decision.allowed()) {
      return SelfOwnedRetainDecisionRecovery.noChange(decision);
    }
    if (!(occupancyManager instanceof SimpleOccupancyManager manager)) {
      return SelfOwnedRetainDecisionRecovery.noChange(decision);
    }
    if (!isBoundedSelfOwnedProtectiveRetainCandidate(request, decision, candidate)) {
      debugLogger.accept(
          "SMART_SELF_RETAIN_RELEASE_REJECTED train="
              + request.trainName()
              + " source="
              + normalizeSource(source)
              + " resource="
              + candidate.resource()
              + " claimRole="
              + candidate.claimRole()
              + " heldDirection="
              + candidate.heldDirection()
              + " requestedDirection="
              + candidate.requestedDirection()
              + " reason=outside-p0-boundary");
      return SelfOwnedRetainDecisionRecovery.noChange(decision);
    }

    DispatchEffectClass effectClass = DispatchEffectClass.OCCUPANCY_MUTATION;
    DispatchAction action = DispatchAction.RELEASE_SELF_OWNED_STALE_PROTECTIVE_RETAIN;
    String normalizedSource = normalizeSource(source);
    debugLogger.accept(
        "SMART_SELF_RETAIN_RECOVERABLE_BLOCKER train="
            + request.trainName()
            + " source="
            + normalizedSource
            + " resource="
            + candidate.resource()
            + " heldDirection="
            + candidate.heldDirection()
            + " requestedDirection="
            + candidate.requestedDirection()
            + " claimRole="
            + candidate.claimRole()
            + " reason="
            + candidate.reason()
            + " effectClass="
            + effectClass);
    if (!smartDispatcherRegisteredActionAllowed(request.trainName(), normalizedSource, action)) {
      if (smartDispatcherMode() == SmartDispatcherMode.OBSERVE_ONLY) {
        debugLogger.accept(
            "SMART_SELF_RETAIN_RELEASE_WOULD_APPLY train="
                + request.trainName()
                + " source="
                + normalizedSource
                + " resource="
                + candidate.resource()
                + " mode="
                + smartDispatcherMode()
                + " destinationMutated=false tokenInvalidated=false occupancyMutated=false");
      } else {
        debugLogger.accept(
            "SMART_SELF_RETAIN_RELEASE_SUPPRESSED train="
                + request.trainName()
                + " source="
                + normalizedSource
                + " resource="
                + candidate.resource()
                + " mode="
                + smartDispatcherMode());
      }
      return SelfOwnedRetainDecisionRecovery.noChange(decision);
    }

    long versionBefore = manager.version();
    SimpleOccupancyManager.SelfOwnedStaleRetainReleaseResult release =
        manager.releaseSelfOwnedStaleRetain(request.trainName(), candidate);
    if (!release.released()) {
      debugLogger.accept(
          "SMART_SELF_RETAIN_RELEASE_VERIFY_FAILED train="
              + request.trainName()
              + " source="
              + normalizedSource
              + " resource="
              + candidate.resource()
              + " result=NOT_RELEASED"
              + " reason="
              + release.reason()
              + " occupancyVersionBefore="
              + versionBefore
              + " occupancyVersionAfter="
              + manager.version());
      return SelfOwnedRetainDecisionRecovery.noChange(decision);
    }
    debugLogger.accept(
        "SMART_SELF_RETAIN_RELEASE_APPLIED train="
            + request.trainName()
            + " source="
            + normalizedSource
            + " resource="
            + candidate.resource()
            + " claimVersion="
            + versionBefore
            + " occupancyVersionBefore="
            + versionBefore
            + " occupancyVersionAfter="
            + manager.version()
            + " releasedResources="
            + release.releasedResources()
            + " destinationMutated=false tokenInvalidated=false occupancyMutated=true");

    OccupancyDecision verified = previewOccupancyDecision(request);
    boolean allow = verified.allowed();
    debugLogger.accept(
        "SMART_SELF_RETAIN_RELEASE_VERIFY train="
            + request.trainName()
            + " source="
            + normalizedSource
            + " resource="
            + candidate.resource()
            + " result="
            + (allow ? "ALLOW" : "STILL_BLOCKED")
            + " reason="
            + verified.reason()
            + " blockers="
            + summarizeBlockers(verified));
    if (!allow) {
      debugLogger.accept(
          "SMART_SELF_RETAIN_RELEASE_VERIFY_FAILED train="
              + request.trainName()
              + " source="
              + normalizedSource
              + " resource="
              + candidate.resource()
              + " result=STILL_BLOCKED"
              + " reason="
              + verified.reason());
    }
    return new SelfOwnedRetainDecisionRecovery(verified, true);
  }

  private static OccupancyRequest selfOwnedRetainValidationRequest(
      String trainName,
      SimpleOccupancyManager.SelfOwnedStaleRetainCandidate candidate,
      Instant now) {
    if (candidate == null) {
      return null;
    }
    Map<String, CorridorDirection> directions =
        Map.of(candidate.resource().key(), candidate.requestedDirection());
    Map<String, Integer> entryOrders = Map.of(candidate.resource().key(), 0);
    return new OccupancyRequest(
        trainName == null || trainName.isBlank() ? candidate.trainName() : trainName,
        Optional.empty(),
        now == null ? Instant.now() : now,
        List.of(candidate.resource()),
        directions,
        entryOrders,
        0,
        AuthorizationPurpose.RUNTIME_MOVE);
  }

  private static OccupancyDecision selfOwnedRetainValidationDecision(
      OccupancyRequest request, SimpleOccupancyManager.SelfOwnedStaleRetainCandidate candidate) {
    if (request == null || candidate == null) {
      return new OccupancyDecision(
          false, Instant.now(), SignalAspect.STOP, List.of(), false, "none");
    }
    OccupancyClaim blocker =
        new OccupancyClaim(
            candidate.resource(),
            candidate.trainName(),
            Optional.empty(),
            candidate.sampledAt(),
            Duration.ZERO,
            Optional.of(candidate.heldDirection()),
            candidate.claimRole());
    return new OccupancyDecision(
        false,
        request.now(),
        SignalAspect.STOP,
        List.of(blocker),
        false,
        "self-owned-single-opposite-direction");
  }

  private static boolean sameSelfOwnedRetainCandidate(
      SimpleOccupancyManager.SelfOwnedStaleRetainCandidate expected,
      SimpleOccupancyManager.SelfOwnedStaleRetainCandidate actual) {
    return expected != null
        && actual != null
        && TrainNameNormalizer.sameLogicalTrain(expected.trainName(), actual.trainName())
        && expected.resource().equals(actual.resource())
        && expected.claimRole() == actual.claimRole()
        && expected.heldDirection() == actual.heldDirection()
        && expected.requestedDirection() == actual.requestedDirection();
  }

  private boolean isBoundedSelfOwnedProtectiveRetainCandidate(
      OccupancyRequest request,
      OccupancyDecision decision,
      SimpleOccupancyManager.SelfOwnedStaleRetainCandidate candidate) {
    if (request == null || decision == null || candidate == null) {
      return false;
    }
    if (!TrainNameNormalizer.sameLogicalTrain(candidate.trainName(), request.trainName())) {
      return false;
    }
    if (candidate.resource().kind() != ResourceKind.CONFLICT
        || !request.resourceList().contains(candidate.resource())) {
      return false;
    }
    if (candidate.claimRole() != ClaimRole.PROTECTIVE_RETAIN) {
      return false;
    }
    if (request.intentFor(candidate.resource()) != ResourceIntent.MOVEMENT_REQUIRED) {
      return false;
    }
    if (candidate.heldDirection() == CorridorDirection.UNKNOWN
        || candidate.requestedDirection() == CorridorDirection.UNKNOWN
        || candidate.heldDirection() == candidate.requestedDirection()) {
      return false;
    }
    if (!"self-owned-single-opposite-direction".equals(decision.reason())) {
      return false;
    }
    CorridorDirection requestDirection = requestDirectionFor(request, candidate.resource());
    if (requestDirection == CorridorDirection.UNKNOWN
        || requestDirection != candidate.requestedDirection()) {
      return false;
    }
    if (decision.blockers().isEmpty()) {
      return false;
    }
    for (OccupancyClaim blocker : decision.blockers()) {
      if (blocker == null
          || !TrainNameNormalizer.sameLogicalTrain(blocker.trainName(), request.trainName())
          || !candidate.resource().equals(blocker.resource())
          || blocker.role() != ClaimRole.PROTECTIVE_RETAIN) {
        return false;
      }
    }
    return true;
  }

  private static CorridorDirection requestDirectionFor(
      OccupancyRequest request, OccupancyResource resource) {
    if (request == null || resource == null) {
      return CorridorDirection.UNKNOWN;
    }
    CorridorDirection direction = request.corridorDirections().get(resource.key());
    if (direction != null && direction != CorridorDirection.UNKNOWN) {
      return direction;
    }
    return request
        .directedContext()
        .map(context -> context.singleConflictDirections().get(resource.key()))
        .filter(value -> value != null && value != CorridorDirection.UNKNOWN)
        .orElse(CorridorDirection.UNKNOWN);
  }

  private static String normalizeSource(String source) {
    return source == null || source.isBlank() ? "runtime" : source.trim();
  }

  /**
   * 执行 Smart drain unlock。
   *
   * <p>该动作只解除本车因 Smart 控制留下的本地 inhibitor 并触发重新判定；它不会创建 DRAIN_THROUGH，也不会把 TOPOLOGY_EXIT_HINT 当成
   * VERIFIED_DRAIN_AUTHORITY。
   */
  public SmartRecoveryActionResult applySmartDrainUnlock(SmartRecoveryInput input) {
    if (input == null || input.train().isBlank()) {
      return SmartRecoveryActionResult.skipped("missing-input");
    }
    SmartDrainOutProof drainOutProof = SmartDrainOutProof.denied("not-evaluated");
    if (smartRecoverySingleRegionHardBarrierPresent(input)) {
      drainOutProof = smartRecoveryDrainOutProof(input);
      if (!drainOutProof.allowed()) {
        debugLogger.accept(
            "SMART_DRAIN_UNLOCK_DRAIN_OUT_DENIED train="
                + input.train()
                + " reason="
                + drainOutProof.reason());
        traceSignalAdvisorySuppressed(
            DispatchAction.SMART_DRAIN_UNLOCK_SIGNAL_ADVISORY,
            input,
            "SIGNAL_ADVISORY_SUPPRESSED_BY_HARD_BARRIER",
            true,
            false,
            isMovementInhibited(input.train()));
        return smartRecoveryBlockedBySingleRegionHardBarrier(
            input,
            "SMART_DRAIN_UNLOCK_BLOCKED_BY_SINGLE_REGION_HARD_BARRIER",
            DispatchEffectClass.DIAGNOSTIC_ONLY);
      }
      traceSmartRecoveryDrainOutAllowed(
          "SMART_DRAIN_UNLOCK_DRAIN_OUT_ALLOWED",
          DispatchAction.SMART_DRAIN_UNLOCK_SIGNAL_ADVISORY,
          input,
          drainOutProof);
    }
    if (!drainOutProof.allowed() && (!input.hardBlockers().isEmpty() || input.blockerCount() > 0)) {
      debugLogger.accept(
          "SMART_DRAIN_UNLOCK_BLOCKED_BY_EXTERNAL_OWNER train="
              + input.train()
              + " hardBlockers="
              + input.hardBlockers()
              + " blockerCount="
              + input.blockerCount());
      return new SmartRecoveryActionResult(
          false,
          false,
          "SMART_DRAIN_UNLOCK_BLOCKED_BY_EXTERNAL_OWNER",
          "external-owner-present",
          DispatchEffectClass.DIAGNOSTIC_ONLY);
    }
    if (!drainOutProof.allowed() && input.oppositeSingleConflictPresent()) {
      debugLogger.accept(
          "SMART_DRAIN_UNLOCK_BLOCKED_BY_EXTERNAL_OWNER train="
              + input.train()
              + " reason=opposite-single-conflict");
      return new SmartRecoveryActionResult(
          false,
          false,
          "SMART_DRAIN_UNLOCK_BLOCKED_BY_EXTERNAL_OWNER",
          "opposite-single-conflict",
          DispatchEffectClass.DIAGNOSTIC_ONLY);
    }
    if (!input.insideSingleRegion() && !input.insideSwitcherRegion()) {
      return SmartRecoveryActionResult.skipped("not-inside-controlled-region");
    }
    DispatchEffectClass effectClass = DispatchEffectClass.SIGNAL_CONSTRAINT;
    debugLogger.accept(
        "SMART_DRAIN_UNLOCK_CANDIDATE train="
            + input.train()
            + " insideSingleRegion="
            + input.insideSingleRegion()
            + " insideSwitcherRegion="
            + input.insideSwitcherRegion()
            + " drainThroughAuthorityCreated=false"
            + " topologyExitHintPromoted=false"
            + " effectClass="
            + effectClass);
    debugLogger.accept(
        "SMART_UNLOCK_ATTEMPTED train="
            + input.train()
            + " recoveryDecision=SMART_DRAIN_UNLOCK"
            + " effectClass="
            + effectClass);
    if (!smartDispatcherRegisteredActionAllowed(
        input.train(),
        "health-progress-stuck",
        DispatchAction.SMART_DRAIN_UNLOCK_SIGNAL_ADVISORY)) {
      debugLogger.accept(
          "SMART_DRAIN_UNLOCK_SUPPRESSED_BY_MODE train="
              + input.train()
              + " mode="
              + smartDispatcherMode()
              + " effectClass="
              + effectClass);
      debugLogger.accept(
          "SMART_RECOVERY_SUPPRESSED_BY_MODE train="
              + input.train()
              + " mode="
              + smartDispatcherMode()
              + " recoveryDecision=SMART_DRAIN_UNLOCK"
              + " effectClass="
              + effectClass);
      return new SmartRecoveryActionResult(
          true, false, "SMART_DRAIN_UNLOCK", "suppressed-by-mode", effectClass);
    }
    debugLogger.accept(
        "SMART_RECOVERY_ALLOWED_BY_EFFECT_GATE train="
            + input.train()
            + " recoveryDecision=SMART_DRAIN_UNLOCK"
            + " effectClass="
            + effectClass
            + " mode="
            + smartDispatcherMode());
    boolean movementInhibitedBefore = isMovementInhibited(input.train());
    SignalRefreshResult refresh = refreshSignalByName(input.train());
    if (!finalRefreshProvedProceed(input.train(), refresh)) {
      boolean hardBarrier = recentSingleRegionHardBarrierSnapshotPresent(input.train());
      traceSignalAdvisorySuppressed(
          DispatchAction.SMART_DRAIN_UNLOCK_SIGNAL_ADVISORY,
          input,
          hardBarrier
              ? "SIGNAL_ADVISORY_SUPPRESSED_BY_HARD_BARRIER"
              : refresh.resolved()
                  ? "SIGNAL_ADVISORY_SUPPRESSED_BY_FINAL_AUTHORIZATION"
                  : "SIGNAL_ADVISORY_SUPPRESSED_BY_STALE_SNAPSHOT",
          hardBarrier,
          !refresh.resolved(),
          movementInhibitedBefore);
    }
    debugLogger.accept(
        "SMART_DRAIN_UNLOCK_APPLIED train="
            + input.train()
            + " inhibitorCleared="
            + false
            + " drainThroughAuthorityCreated=false"
            + " topologyExitHintPromoted=false"
            + " destinationMutated=false"
            + " tokenInvalidated=false"
            + " occupancyMutated=false"
            + " destroy=false");
    debugLogger.accept(
        "SMART_DRAIN_UNLOCK_VERIFY train="
            + input.train()
            + " movementInhibited="
            + isMovementInhibited(input.train())
            + " drainThroughAuthorityCreated=false");
    debugLogger.accept(
        "SMART_UNLOCK_APPLIED train="
            + input.train()
            + " recoveryDecision=SMART_DRAIN_UNLOCK"
            + " reason=drain-refresh");
    SmartRecoveryEffectiveness effectiveness =
        verifySmartRecoveryEffect(
            "SMART_DRAIN_UNLOCK",
            input,
            refresh.resolved() ? refresh.trainName() : input.train(),
            primaryRecoveryConflictKey(input),
            false,
            refresh);
    return new SmartRecoveryActionResult(
        true, true, "SMART_DRAIN_UNLOCK", "drain-refresh", effectClass, effectiveness);
  }

  /**
   * 执行 Smart forward unlock / authority-token repair。
   *
   * <p>该入口只处理“授权窗口/移动 token 卡住但没有可见硬 blocker”的 stuck case；所有真实副作用必须先经过 {@link
   * SmartDispatcherModeGate}。
   */
  public SmartRecoveryActionResult applySmartForwardUnlock(SmartRecoveryInput input) {
    if (input == null || input.train().isBlank()) {
      return SmartRecoveryActionResult.skipped("missing-input");
    }
    SmartDrainOutProof drainOutProof = SmartDrainOutProof.denied("not-evaluated");
    if (smartRecoverySingleRegionHardBarrierPresent(input)) {
      drainOutProof = smartRecoveryDrainOutProof(input);
      if (!drainOutProof.allowed()) {
        debugLogger.accept(
            "SMART_FORWARD_UNLOCK_DRAIN_OUT_DENIED train="
                + input.train()
                + " reason="
                + drainOutProof.reason());
        traceSignalAdvisorySuppressed(
            DispatchAction.SMART_FORWARD_UNLOCK_SIGNAL_ADVISORY,
            input,
            "SIGNAL_ADVISORY_SUPPRESSED_BY_HARD_BARRIER",
            true,
            false,
            isMovementInhibited(input.train()));
        return smartRecoveryBlockedBySingleRegionHardBarrier(
            input,
            "SMART_FORWARD_UNLOCK_BLOCKED_BY_SINGLE_REGION_HARD_BARRIER",
            DispatchEffectClass.DIAGNOSTIC_ONLY);
      }
      traceSmartRecoveryDrainOutAllowed(
          "SMART_FORWARD_UNLOCK_DRAIN_OUT_ALLOWED",
          DispatchAction.SMART_FORWARD_UNLOCK_SIGNAL_ADVISORY,
          input,
          drainOutProof);
    }
    if (!drainOutProof.allowed() && (!input.hardBlockers().isEmpty() || input.blockerCount() > 0)) {
      debugLogger.accept(
          "SMART_FORWARD_UNLOCK_BLOCKED_BY_HARD_BLOCKER train="
              + input.train()
              + " hardBlockers="
              + input.hardBlockers()
              + " blockerCount="
              + input.blockerCount());
      return new SmartRecoveryActionResult(
          false,
          false,
          "SMART_FORWARD_UNLOCK_BLOCKED_BY_HARD_BLOCKER",
          "hard-blocker-present",
          DispatchEffectClass.DIAGNOSTIC_ONLY);
    }
    if (!drainOutProof.allowed() && input.oppositeSingleConflictPresent()) {
      debugLogger.accept(
          "SMART_FORWARD_UNLOCK_BLOCKED_BY_SINGLE_CONFLICT train="
              + input.train()
              + " reason=opposite-single-conflict");
      return new SmartRecoveryActionResult(
          false,
          false,
          "SMART_FORWARD_UNLOCK_BLOCKED_BY_SINGLE_CONFLICT",
          "opposite-single-conflict",
          DispatchEffectClass.DIAGNOSTIC_ONLY);
    }
    if (!isSmartForwardUnlockCandidate(input)) {
      return SmartRecoveryActionResult.skipped("forward-unlock-conditions-not-met");
    }
    DispatchEffectClass effectClass = DispatchEffectClass.SIGNAL_CONSTRAINT;
    debugLogger.accept(
        "SMART_FORWARD_UNLOCK_CANDIDATE train="
            + input.train()
            + " reason="
            + input.primaryReason()
            + " movementInhibited="
            + input.movementInhibited()
            + " movementTokenState="
            + input.movementTokenState()
            + " destinationPresent="
            + input.destinationPresent()
            + " blockers="
            + input.hardBlockers());
    if (!smartDispatcherRegisteredActionAllowed(
        input.train(),
        "health-progress-stuck",
        DispatchAction.SMART_FORWARD_UNLOCK_SIGNAL_ADVISORY)) {
      debugLogger.accept(
          "SMART_FORWARD_UNLOCK_SUPPRESSED_BY_MODE train="
              + input.train()
              + " mode="
              + smartDispatcherMode()
              + " effectClass="
              + effectClass);
      debugLogger.accept(
          "SMART_RECOVERY_SUPPRESSED_BY_MODE train="
              + input.train()
              + " mode="
              + smartDispatcherMode()
              + " recoveryDecision=SMART_FORWARD_UNLOCK_CANDIDATE"
              + " effectClass="
              + effectClass);
      return new SmartRecoveryActionResult(
          true, false, "SMART_FORWARD_UNLOCK_CANDIDATE", "suppressed-by-mode", effectClass);
    }
    debugLogger.accept(
        "SMART_RECOVERY_ALLOWED_BY_EFFECT_GATE train="
            + input.train()
            + " recoveryDecision=SMART_FORWARD_UNLOCK_CANDIDATE"
            + " effectClass="
            + effectClass
            + " mode="
            + smartDispatcherMode());
    RuntimeTrainResolution resolution =
        resolveRuntimeTrainForHealth(input.train(), RuntimeTrainResolvePurpose.REFRESH_SIGNAL);
    if (!resolution.resolved()) {
      debugLogger.accept(
          "SMART_RECOVERY_SKIPPED train="
              + input.train()
              + " recoveryDecision=SMART_FORWARD_UNLOCK_CANDIDATE"
              + " reason=runtime-train-not-resolved");
      return new SmartRecoveryActionResult(
          true, false, "SMART_FORWARD_UNLOCK_CANDIDATE", "runtime-train-not-resolved", effectClass);
    }
    String resolvedName = resolution.resolvedName();
    boolean movementInhibitedBefore =
        isMovementInhibited(resolvedName) || isMovementInhibited(input.train());
    SignalRefreshResult refresh = refreshSignalByName(resolvedName);
    if (!finalRefreshProvedProceed(resolvedName, refresh)) {
      boolean hardBarrier = recentSingleRegionHardBarrierSnapshotPresent(resolvedName);
      traceSignalAdvisorySuppressed(
          DispatchAction.SMART_FORWARD_UNLOCK_SIGNAL_ADVISORY,
          input,
          hardBarrier
              ? "SIGNAL_ADVISORY_SUPPRESSED_BY_HARD_BARRIER"
              : refresh.resolved()
                  ? "SIGNAL_ADVISORY_SUPPRESSED_BY_FINAL_AUTHORIZATION"
                  : "SIGNAL_ADVISORY_SUPPRESSED_BY_STALE_SNAPSHOT",
          hardBarrier,
          !refresh.resolved(),
          movementInhibitedBefore);
    }
    debugLogger.accept(
        "SMART_FORWARD_UNLOCK_APPLIED train="
            + resolvedName
            + " inhibitorCleared="
            + false
            + " destinationPresentBefore="
            + input.destinationPresent()
            + " tokenInvalidated=false"
            + " occupancyMutated=false"
            + " destroy=false");
    debugLogger.accept(
        "SMART_FORWARD_UNLOCK_VERIFY train="
            + resolvedName
            + " movementInhibited="
            + isMovementInhibited(resolvedName)
            + " movementTokenState="
            + tokenState(resolvedName, movementToken(resolvedName).orElse(null))
            + " destinationPresentBefore="
            + input.destinationPresent());
    SmartRecoveryEffectiveness effectiveness =
        verifySmartRecoveryEffect(
            "SMART_FORWARD_UNLOCK",
            input,
            resolvedName,
            primaryRecoveryConflictKey(input),
            false,
            refresh);
    return new SmartRecoveryActionResult(
        true,
        true,
        "SMART_FORWARD_UNLOCK_APPLIED",
        "authority-token-repair",
        effectClass,
        effectiveness);
  }

  private SmartRecoveryEffectiveness verifySmartRecoveryEffect(
      String action,
      SmartRecoveryInput before,
      String trainName,
      String conflictKey,
      boolean candidateReappeared,
      SignalRefreshResult refresh) {
    SmartRecoveryInput safeBefore =
        before == null
            ? SmartRecoveryInput.fallback(trainName, Duration.ZERO, SignalAspect.STOP)
            : before;
    String resolvedTrain =
        trainName == null || trainName.isBlank() ? safeBefore.train() : trainName.trim();
    SmartRecoveryInput after =
        smartRecoveryInput(
            resolvedTrain,
            Duration.ofSeconds(safeBefore.stuckDurationSeconds()),
            safeBefore.signal());
    SignalAspect finalAspect =
        refresh != null && refresh.resolved() ? refresh.after() : after.signal();
    boolean sameStopReason =
        safeBefore.signal() == SignalAspect.STOP
            && after.signal() == SignalAspect.STOP
            && Objects.equals(safeBefore.primaryReason(), after.primaryReason());
    boolean destinationChanged = safeBefore.destinationPresent() != after.destinationPresent();
    boolean tokenBecameActive =
        safeBefore.movementTokenState() != SignalComputationTrace.TokenState.ACTIVE
            && after.movementTokenState() == SignalComputationTrace.TokenState.ACTIVE;
    boolean inhibitorCleared = safeBefore.movementInhibited() && !after.movementInhibited();
    boolean effective =
        !candidateReappeared
            && !sameStopReason
            && (finalAspect != SignalAspect.STOP
                || destinationChanged
                || tokenBecameActive
                || (inhibitorCleared && finalAspect != SignalAspect.STOP));
    String reason =
        effective
            ? "verified-runtime-input-changed"
            : recoveryIneffectiveReason(
                candidateReappeared,
                sameStopReason,
                destinationChanged,
                tokenBecameActive,
                inhibitorCleared,
                finalAspect);
    SmartRecoveryEffectiveness effectiveness =
        new SmartRecoveryEffectiveness(
            action,
            conflictKey,
            effective,
            finalAspect,
            safeBefore.destinationPresent(),
            after.destinationPresent(),
            safeBefore.movementTokenState(),
            after.movementTokenState(),
            safeBefore.movementInhibited(),
            after.movementInhibited(),
            candidateReappeared,
            sameStopReason,
            reason);
    debugLogger.accept(
        "SMART_RECOVERY_EFFECT_VERIFY action="
            + effectiveness.action()
            + " train="
            + resolvedTrain
            + " effective="
            + effectiveness.effective()
            + " finalAspect="
            + effectiveness.finalAspect()
            + " destinationBefore="
            + effectiveness.destinationBefore()
            + " destinationAfter="
            + effectiveness.destinationAfter()
            + " tokenBefore="
            + effectiveness.tokenBefore()
            + " tokenAfter="
            + effectiveness.tokenAfter()
            + " movementInhibitedBefore="
            + effectiveness.movementInhibitedBefore()
            + " movementInhibitedAfter="
            + effectiveness.movementInhibitedAfter()
            + " candidateReappeared="
            + effectiveness.candidateReappeared()
            + " sameStopReason="
            + effectiveness.sameStopReason()
            + " conflictKey="
            + effectiveness.conflictKey());
    if (!effectiveness.effective()) {
      debugLogger.accept(
          "SMART_RECOVERY_SAFE_CANDIDATE_INEFFECTIVE action="
              + effectiveness.action()
              + " train="
              + resolvedTrain
              + " reason="
              + effectiveness.reason()
              + " conflictKey="
              + effectiveness.conflictKey());
    }
    return effectiveness;
  }

  private static String recoveryIneffectiveReason(
      boolean candidateReappeared,
      boolean sameStopReason,
      boolean destinationChanged,
      boolean tokenBecameActive,
      boolean inhibitorCleared,
      SignalAspect finalAspect) {
    if (candidateReappeared) {
      return "candidate-reappeared";
    }
    if (sameStopReason) {
      return "same-stop-reason";
    }
    if (finalAspect == SignalAspect.STOP
        && !destinationChanged
        && !tokenBecameActive
        && !inhibitorCleared) {
      return "no-runtime-input-changed";
    }
    if (finalAspect == SignalAspect.STOP) {
      return "final-aspect-still-stop";
    }
    return "unknown";
  }

  private static String primaryRecoveryConflictKey(SmartRecoveryInput input) {
    if (input == null) {
      return "-";
    }
    return input.hardBlockers().stream()
        .filter(Objects::nonNull)
        .filter(resource -> resource.startsWith("single:") || resource.startsWith("switcher:"))
        .sorted()
        .findFirst()
        .orElse(
            input.insideSingleRegion()
                ? "single:*"
                : input.insideSwitcherRegion() ? "switcher:*" : "-");
  }

  private static String recoveryPrimaryReason(ControlDiagnostics diagnostics) {
    if (diagnostics == null) {
      return "none";
    }
    if (diagnostics.blockedReason() != null
        && !diagnostics.blockedReason().isBlank()
        && !"none".equals(diagnostics.blockedReason())) {
      return diagnostics.blockedReason();
    }
    if (diagnostics.signalReason() != null && !diagnostics.signalReason().isBlank()) {
      return diagnostics.signalReason();
    }
    return "none";
  }

  private static boolean isSmartForwardUnlockCandidate(SmartRecoveryInput input) {
    if (input == null) {
      return false;
    }
    boolean authorityWindow = isForwardUnlockAuthorityReason(input.primaryReason());
    boolean tokenBlocked =
        input.movementInhibited()
            || input.movementTokenState() == SignalComputationTrace.TokenState.PENDING
            || input.movementTokenState() == SignalComputationTrace.TokenState.INVALID;
    return authorityWindow && tokenBlocked && !input.downstreamBlocked();
  }

  private static boolean isForwardUnlockAuthorityReason(String reason) {
    if (reason == null || reason.isBlank()) {
      return false;
    }
    String normalized = reason.toLowerCase(Locale.ROOT);
    return normalized.contains("authority-window")
        || normalized.contains("movement-authority")
        || normalized.contains("movement-token")
        || normalized.contains("authorization");
  }

  /**
   * 获取健康监控互卡兜底所需的列车上下文。
   *
   * <p>该接口只读运行时状态，不触发发车、占用或 destination 改写。
   */
  public Optional<DeadlockTrainContext> deadlockTrainContext(String trainName) {
    RuntimeTrainResolution resolution =
        resolveRuntimeTrainForHealth(trainName, RuntimeTrainResolvePurpose.DEADLOCK_CONTEXT);
    if (!resolution.resolved()) {
      traceRuntimeTrainResolveFailed(RuntimeTrainResolvePurpose.DEADLOCK_CONTEXT, resolution);
      return Optional.empty();
    }
    Optional<RouteProgressRegistry.RouteProgressEntry> entryOpt =
        progressRegistry.get(resolution.resolvedName());
    if (entryOpt.isEmpty()) {
      traceRuntimeTrainResolveFailed(
          RuntimeTrainResolvePurpose.DEADLOCK_CONTEXT,
          failedRuntimeTrainResolution(
              trainName, "ACTIVE_STATE_MISSING", resolution.resolvedName()));
      return Optional.empty();
    }
    RouteProgressRegistry.RouteProgressEntry entry = entryOpt.get();
    TrainProperties properties = resolution.properties();
    Optional<RouteDefinition> routeOpt = resolveRouteForRecovery(entry, properties);
    int routeSize = routeOpt.map(route -> route.waypoints().size()).orElse(0);
    RouteOperationType operationType =
        resolveRouteOperationTypeForRecovery(entry).orElse(RouteOperationType.OPERATION);
    int priority = resolvePriority(properties, routeOpt.orElse(null));
    boolean dwelling =
        dwellRegistry != null && dwellRegistry.remainingSeconds(entry.trainName()).isPresent();
    boolean layoverReady = layoverRegistry.get(entry.trainName()).isPresent();
    boolean departureGateHeld = hasDepartureGate(entry.trainName());
    boolean depotRelated =
        routeOpt.map(route -> isDepotRelated(entry, route, operationType)).orElse(false);
    boolean nearRouteEnd = routeSize > 0 && entry.currentIndex() >= Math.max(0, routeSize - 2);
    boolean manualHold =
        TrainTagHelper.readTagValue(properties, "FTA_MANUAL_HOLD").isPresent()
            || TrainTagHelper.readTagValue(properties, "FTA_MAINTENANCE_HOLD").isPresent();
    return Optional.of(
        new DeadlockTrainContext(
            entry.trainName(),
            entry.currentIndex(),
            routeSize,
            entry.lastSignal(),
            getCurrentSpeedBlocksPerTick(properties),
            operationType,
            priority,
            dwelling,
            departureGateHeld,
            layoverReady,
            depotRelated,
            nearRouteEnd,
            hasPassenger(properties),
            manualHold));
  }

  /**
   * 判断互卡 episode 在销毁兜底前是否存在保守的 drain-through 候选。
   *
   * <p>本轮只确认 single corridor occupant 的清空路径：候选列车必须已经持有同一 {@code CONFLICT:single:*}
   * claim，并且当前有向前方路径能走出该 conflict。switcher occupant-to-many 仅保留诊断，不在这里放宽为可销毁或可放行。
   */
  public Optional<DeadlockDrainability> deadlockDrainability(
      String episodeId, String trainA, String trainB, String zoneId, long remainingMsUntilDestroy) {
    String zone = zoneId == null ? "" : zoneId.trim();
    if (!zone.startsWith("single:") || zone.contains(":cycle:")) {
      return Optional.of(
          new DeadlockDrainability(
              episodeId,
              trainA,
              trainB,
              zone,
              false,
              "",
              Optional.empty(),
              List.of(),
              "SWITCHER_OR_NODE_EDGE_DIAGNOSTIC_ONLY",
              remainingMsUntilDestroy));
    }
    Optional<DeadlockDrainability> first =
        deadlockDrainabilityForTrain(
            episodeId, trainA, trainA, trainB, zone, remainingMsUntilDestroy);
    if (first.isPresent() && first.get().drainable()) {
      return first;
    }
    Optional<DeadlockDrainability> second =
        deadlockDrainabilityForTrain(
            episodeId, trainB, trainA, trainB, zone, remainingMsUntilDestroy);
    if (second.isPresent() && second.get().drainable()) {
      return second;
    }
    return first.isPresent()
        ? first
        : Optional.of(
            new DeadlockDrainability(
                episodeId,
                trainA,
                trainB,
                zone,
                false,
                "",
                Optional.empty(),
                List.of(),
                "NO_DRAIN_CANDIDATE",
                remainingMsUntilDestroy));
  }

  private Optional<DeadlockDrainability> deadlockDrainabilityForTrain(
      String episodeId,
      String candidateName,
      String trainA,
      String trainB,
      String zoneId,
      long remainingMsUntilDestroy) {
    if (candidateName == null || candidateName.isBlank()) {
      return Optional.empty();
    }
    RuntimeTrainResolution resolution =
        resolveRuntimeTrainForHealth(candidateName, RuntimeTrainResolvePurpose.DEADLOCK_CONTEXT);
    if (!resolution.resolved() || resolution.properties() == null) {
      return Optional.of(
          drainabilityResult(
              episodeId,
              trainA,
              trainB,
              zoneId,
              false,
              candidateName,
              Optional.empty(),
              List.of(),
              "BLOCKER_NOT_RESOLVED",
              remainingMsUntilDestroy));
    }
    MinecartGroup group = resolution.properties().getHolder();
    if (group == null || !group.isValid()) {
      return Optional.of(
          drainabilityResult(
              episodeId,
              trainA,
              trainB,
              zoneId,
              false,
              resolution.resolvedName(),
              Optional.empty(),
              List.of(),
              "ENTITY_NOT_FOUND",
              remainingMsUntilDestroy));
    }
    Optional<RouteProgressRegistry.RouteProgressEntry> entryOpt =
        progressRegistry.get(resolution.resolvedName());
    if (entryOpt.isEmpty()) {
      return Optional.of(
          drainabilityResult(
              episodeId,
              trainA,
              trainB,
              zoneId,
              false,
              resolution.resolvedName(),
              Optional.empty(),
              List.of(),
              "ACTIVE_STATE_MISSING",
              remainingMsUntilDestroy));
    }
    RouteProgressRegistry.RouteProgressEntry entry = entryOpt.get();
    Optional<RouteDefinition> routeOpt = resolveRouteForRecovery(entry, resolution.properties());
    Optional<RailGraph> graphOpt =
        group.getWorld() == null
            ? Optional.empty()
            : resolveGraph(group.getWorld().getUID(), Instant.now());
    if (routeOpt.isEmpty() || graphOpt.isEmpty()) {
      return Optional.of(
          drainabilityResult(
              episodeId,
              trainA,
              trainB,
              zoneId,
              false,
              resolution.resolvedName(),
              Optional.empty(),
              List.of(),
              routeOpt.isEmpty() ? "ROUTE_MISSING" : "GRAPH_MISSING",
              remainingMsUntilDestroy));
    }
    RailGraph graph = graphOpt.get();
    RouteDefinition route = routeOpt.get();
    ConfigManager.RuntimeSettings runtimeSettings = configManager.current().runtimeSettings();
    List<NodeId> effectiveNodes = resolveEffectiveWaypoints(resolution.resolvedName(), route);
    Optional<OccupancyRequestContext> contextOpt =
        buildForwardAuthorizationContext(
            graph,
            runtimeSettings,
            resolution.resolvedName(),
            route,
            effectiveNodes,
            entry.currentIndex(),
            Instant.now(),
            resolvePriority(resolution.properties(), route),
            AuthorizationPurpose.RUNTIME_MOVE);
    if (contextOpt.isEmpty()) {
      return Optional.of(
          drainabilityResult(
              episodeId,
              trainA,
              trainB,
              zoneId,
              false,
              resolution.resolvedName(),
              Optional.empty(),
              List.of(),
              "REQUEST_BUILD_FAILED",
              remainingMsUntilDestroy));
    }
    OccupancyRequestContext context = contextOpt.get();
    OccupancyResource zoneResource = OccupancyResource.forConflict(zoneId);
    RailGraphConflictSupport support = singleConflictMembershipSupport(graph, zoneResource);
    if (!trainAlreadyHoldsResource(resolution.resolvedName(), zoneResource)) {
      return Optional.of(
          drainabilityResult(
              episodeId,
              trainA,
              trainB,
              zoneId,
              false,
              resolution.resolvedName(),
              Optional.empty(),
              context.pathNodes(),
              "NOT_INSIDE_ZONE",
              remainingMsUntilDestroy));
    }
    CorridorDirection direction = context.request().corridorDirections().get(zoneId);
    if (direction == null || direction == CorridorDirection.UNKNOWN) {
      return Optional.of(
          drainabilityResult(
              episodeId,
              trainA,
              trainB,
              zoneId,
              false,
              resolution.resolvedName(),
              Optional.empty(),
              context.pathNodes(),
              "UNKNOWN_DIRECTION",
              remainingMsUntilDestroy));
    }
    if (!pathTargetsExitFromConflict(context.edges(), support, zoneId)) {
      return Optional.of(
          drainabilityResult(
              episodeId,
              trainA,
              trainB,
              zoneId,
              false,
              resolution.resolvedName(),
              Optional.empty(),
              context.pathNodes(),
              "NO_EXIT_IN_AUTHORITY_WINDOW",
              remainingMsUntilDestroy));
    }
    Optional<NodeId> exitNode = conflictExitNode(context, support, zoneId);
    return Optional.of(
        drainabilityResult(
            episodeId,
            trainA,
            trainB,
            zoneId,
            true,
            resolution.resolvedName(),
            exitNode,
            context.pathNodes(),
            "DRAIN_THROUGH_SAFE",
            remainingMsUntilDestroy));
  }

  private DeadlockDrainability drainabilityResult(
      String episodeId,
      String trainA,
      String trainB,
      String zoneId,
      boolean drainable,
      String candidate,
      Optional<NodeId> exitNode,
      List<NodeId> drainPath,
      String reason,
      long remainingMsUntilDestroy) {
    return new DeadlockDrainability(
        episodeId,
        trainA,
        trainB,
        zoneId,
        drainable,
        candidate,
        exitNode,
        drainPath,
        reason,
        remainingMsUntilDestroy);
  }

  /** 注册“销毁完成后刷新幸存列车”的一次性钩子。 */
  public void scheduleSurvivorRefreshAfterTrainRemoved(
      String removedTrainName, String survivorTrainName) {
    String removedKey = normalizeTrainKey(removedTrainName);
    String survivor = survivorTrainName == null ? "" : survivorTrainName.trim();
    if (removedKey.isEmpty() || survivor.isBlank()) {
      return;
    }
    survivorRefreshAfterRemoval.put(removedKey, survivor);
  }

  /**
   * 通过列车名刷新信号。
   *
   * @param trainName 列车名
   */
  public SignalRefreshResult refreshSignalByName(String trainName) {
    RuntimeTrainResolution resolution =
        resolveRuntimeTrainForHealth(trainName, RuntimeTrainResolvePurpose.REFRESH_SIGNAL);
    TrainProperties properties = resolution.properties();
    if (properties == null) {
      traceRuntimeTrainResolveFailed(RuntimeTrainResolvePurpose.REFRESH_SIGNAL, resolution);
      return SignalRefreshResult.unresolved(trainName, "properties-not-found");
    }
    String resolvedName = resolution.resolvedName();
    SignalAspect before = currentPhysicalAspect(resolvedName, SignalAspect.STOP);
    com.bergerkiller.bukkit.tc.controller.MinecartGroup group = properties.getHolder();
    if (group == null || !group.isValid()) {
      traceRuntimeTrainResolveFailed(
          RuntimeTrainResolvePurpose.REFRESH_SIGNAL,
          failedRuntimeTrainResolution(
              trainName,
              group == null ? "ENTITY_NOT_FOUND" : "ALREADY_REMOVED",
              resolution.resolvedName()));
      return new SignalRefreshResult(
          false,
          resolvedName,
          before,
          before,
          false,
          group == null ? "entity-not-found" : "already-removed");
    }
    refreshSignal(group);
    SignalAspect after = currentPhysicalAspect(resolvedName, before);
    return new SignalRefreshResult(true, resolvedName, before, after, before != after, "refreshed");
  }

  /**
   * 按占用资源集合刷新受影响列车信号。
   *
   * <p>用于“新列车出库/复用发车后”的即时联动：在下一次周期 tick 之前，先主动刷新同资源上的列车，减少出库咽喉区的误放行窗口。
   *
   * <p>受影响列车来源：
   *
   * <ul>
   *   <li>当前占用快照中命中这些资源的列车
   *   <li>冲突队列中等待这些资源的列车
   * </ul>
   *
   * @param resources 本次占用资源集合
   * @param sourceTrainName 触发刷新的列车（会被排除，避免重复刷新）
   */
  public void refreshSignalsForResources(
      List<OccupancyResource> resources, String sourceTrainName) {
    if (resources == null || resources.isEmpty() || occupancyManager == null) {
      return;
    }
    Set<OccupancyResource> targets = new LinkedHashSet<>();
    for (OccupancyResource resource : resources) {
      if (resource != null) {
        targets.add(resource);
      }
    }
    if (targets.isEmpty()) {
      return;
    }
    String sourceKey = normalizeTrainKey(sourceTrainName);
    Map<String, String> impactedByKey = collectImpactedTrainsForResourceRefresh(targets, sourceKey);
    if (impactedByKey.isEmpty()) {
      return;
    }
    for (String impactedTrain : impactedByKey.values()) {
      refreshSignalByName(impactedTrain);
    }
    debugLogger.accept(
        "占用联动刷新: source="
            + (sourceTrainName == null || sourceTrainName.isBlank() ? "unknown" : sourceTrainName)
            + " resources="
            + targets.size()
            + " trains="
            + impactedByKey.values().size());
  }

  /**
   * 收集“资源联动刷新”涉及的列车清单（key=规范化列车名，value=原始列车名）。
   *
   * <p>抽成独立方法便于单测验证“claim + queue”的收集逻辑。
   */
  private Map<String, String> collectImpactedTrainsForResourceRefresh(
      Set<OccupancyResource> targets, String sourceTrainKey) {
    Map<String, String> impactedByKey = new java.util.LinkedHashMap<>();
    if (targets == null || targets.isEmpty() || occupancyManager == null) {
      return impactedByKey;
    }

    List<OccupancyClaim> claims = occupancyManager.snapshotClaims();
    if (claims == null || claims.isEmpty()) {
      return impactedByKey;
    }
    for (OccupancyClaim claim : claims) {
      if (claim == null || claim.resource() == null) {
        continue;
      }
      if (!targets.contains(claim.resource())) {
        continue;
      }
      String candidate = claim.trainName();
      if (candidate == null || candidate.isBlank()) {
        continue;
      }
      String key = normalizeTrainKey(candidate);
      if (key.isEmpty() || key.equals(sourceTrainKey)) {
        continue;
      }
      impactedByKey.putIfAbsent(key, candidate);
    }

    if (!(occupancyManager instanceof OccupancyQueueSupport queueSupport)) {
      return impactedByKey;
    }
    for (OccupancyQueueSnapshot snapshot : queueSupport.snapshotQueues()) {
      if (snapshot == null || snapshot.resource() == null) {
        continue;
      }
      if (!targets.contains(snapshot.resource())) {
        continue;
      }
      for (OccupancyQueueEntry entry : snapshot.entries()) {
        if (entry == null || entry.trainName() == null || entry.trainName().isBlank()) {
          continue;
        }
        String key = normalizeTrainKey(entry.trainName());
        if (key.isEmpty() || key.equals(sourceTrainKey)) {
          continue;
        }
        impactedByKey.putIfAbsent(key, entry.trainName());
      }
    }
    return impactedByKey;
  }

  /**
   * 清理健康恢复场景中的自持 single 反向残留。
   *
   * <p>该入口只在列车当前仍处于 STOP，且只读预览确认 blocker 是同一逻辑列车持有的 single conflict 旧方向时生效。它不会重发 destination 或
   * relaunch；清理完成后仅触发一次正常信号刷新，让运行时按常规授权链路重新判定。
   *
   * @param trainName 列车名或运行时别名
   * @return true 表示已清理至少一个残留 claim/queue 条目
   */
  public boolean clearSelfOwnedSingleDirectionMismatchByName(String trainName) {
    if (trainName == null || trainName.isBlank() || occupancyManager == null) {
      return false;
    }
    RuntimeTrainResolution resolution =
        resolveRuntimeTrainForHealth(
            trainName, RuntimeTrainResolvePurpose.CLEAR_SELF_OWNED_CONFLICT);
    TrainProperties properties = resolution.properties();
    if (properties == null || !isFtaManagedTrain(properties)) {
      traceRuntimeTrainResolveFailed(
          RuntimeTrainResolvePurpose.CLEAR_SELF_OWNED_CONFLICT,
          properties == null
              ? resolution
              : failedRuntimeTrainResolution(
                  trainName, "NOT_FTA_MANAGED", resolution.resolvedName()));
      return false;
    }
    MinecartGroup group = properties.getHolder();
    if (group == null || !group.isValid()) {
      traceRuntimeTrainResolveFailed(
          RuntimeTrainResolvePurpose.CLEAR_SELF_OWNED_CONFLICT,
          failedRuntimeTrainResolution(
              trainName,
              group == null ? "ENTITY_NOT_FOUND" : "ALREADY_REMOVED",
              resolution.resolvedName()));
      return false;
    }
    Optional<RouteProgressRegistry.RouteProgressEntry> entryOpt =
        progressRegistry.get(resolution.resolvedName()).or(() -> progressRegistry.get(trainName));
    if (entryOpt.isEmpty()) {
      return false;
    }
    RouteProgressRegistry.RouteProgressEntry entry = entryOpt.get();
    if (entry.lastSignal() != SignalAspect.STOP) {
      return false;
    }
    Optional<RouteDefinition> routeOpt = resolveRouteForRecovery(entry, properties);
    if (routeOpt.isEmpty()) {
      return false;
    }
    RouteDefinition route = routeOpt.get();
    int currentIndex = entry.currentIndex();
    if (currentIndex < 0 || currentIndex >= route.waypoints().size() - 1) {
      return false;
    }
    Optional<RailGraph> graphOpt = resolveGraphByGroup(group);
    if (graphOpt.isEmpty()) {
      return false;
    }
    RailGraph graph = graphOpt.get();
    String resolvedTrainName = entry.trainName();
    ConfigManager.RuntimeSettings runtimeSettings = configManager.current().runtimeSettings();
    List<NodeId> effectiveNodes = resolveEffectiveWaypoints(resolvedTrainName, route);
    Optional<OccupancyRequestContext> contextOpt =
        buildHardAuthorityContext(
            graph,
            runtimeSettings,
            resolvedTrainName,
            route,
            effectiveNodes,
            currentIndex,
            Instant.now(),
            resolvePriority(properties, route),
            AuthorizationPurpose.RUNTIME_MOVE);
    if (contextOpt.isEmpty()) {
      return false;
    }
    OccupancyRequestContext context = contextOpt.get();
    OccupancyRequest request =
        prepareRuntimeAuthorizationRequest(
            context, graph, SignalComputationTrace.Source.PERIODIC_TICK);
    OccupancyDecision preview = previewOccupancyDecision(request);
    if (!isSelfOwnedSingleDirectionMismatch(preview, request)) {
      return false;
    }
    if (hasTurnbackProtectedBlocker(resolvedTrainName, preview)) {
      debugLogger.accept(
          "HealthMonitor 拒绝清理折返 footprint 中的 single 方向: train="
              + resolvedTrainName
              + " blockers="
              + summarizeBlockers(preview));
      return false;
    }
    int removed = occupancyManager.clearSelfOwnedSingleDirectionMismatches(request);
    if (removed <= 0) {
      return false;
    }
    debugLogger.accept(
        "HealthMonitor 清理自持 single 方向残留: train="
            + resolvedTrainName
            + " removed="
            + removed
            + " reason="
            + preview.reason());
    refreshSignalByName(resolvedTrainName);
    return true;
  }

  /** 折返 sidecar 的共享 movement claim 也必须等待 rear-clear，不能被 single 方向自愈旁路删除。 */
  boolean hasTurnbackProtectedBlocker(String trainName, OccupancyDecision decision) {
    if (decision == null || decision.blockers().isEmpty()) {
      return false;
    }
    Set<OccupancyResource> protectedResources =
        turnbackFootprintGuards.protectedResources(trainName);
    if (protectedResources.isEmpty()) {
      return false;
    }
    return decision.blockers().stream()
        .filter(Objects::nonNull)
        .map(OccupancyClaim::resource)
        .filter(Objects::nonNull)
        .anyMatch(protectedResources::contains);
  }

  private boolean isSelfOwnedSingleDirectionMismatch(
      OccupancyDecision decision, OccupancyRequest request) {
    if (decision == null
        || request == null
        || decision.allowed()
        || !"self-owned-single-opposite-direction".equals(decision.reason())
        || decision.blockers().isEmpty()) {
      return false;
    }
    for (OccupancyClaim blocker : decision.blockers()) {
      if (!isSelfOwnedSingleConflictBlocker(blocker, request)) {
        return false;
      }
    }
    return true;
  }

  private boolean isSelfOwnedSingleConflictBlocker(
      OccupancyClaim blocker, OccupancyRequest request) {
    if (blocker == null || blocker.resource() == null || request == null) {
      return false;
    }
    OccupancyResource resource = blocker.resource();
    if (resource.kind() != ResourceKind.CONFLICT || !resource.key().startsWith("single:")) {
      return false;
    }
    if (!TrainNameNormalizer.sameLogicalTrain(blocker.trainName(), request.trainName())) {
      return false;
    }
    CorridorDirection requested = request.corridorDirections().get(resource.key());
    if (!isKnownDirection(requested)) {
      // hard-authority context 的方向可能只写在 movement plan 里，与 occupancy 层的
      // resolveCorridorDirection 保持同一回退顺序，否则恢复入口会漏判自持反向 claim。
      requested =
          request
              .movementPlanSnapshot()
              .map(plan -> plan.singleConflictDirections().get(resource.key()))
              .orElse(null);
    }
    Optional<CorridorDirection> held = blocker.corridorDirection();
    return isKnownDirection(requested) && isKnownDirection(held) && held.get() != requested;
  }

  private static boolean isKnownDirection(CorridorDirection direction) {
    return direction != null && direction != CorridorDirection.UNKNOWN;
  }

  private static boolean isKnownDirection(Optional<CorridorDirection> direction) {
    return direction.isPresent() && isKnownDirection(direction.get());
  }

  /**
   * 通过列车名重下发“下一跳 destination”（不强制重发）。
   *
   * <p>用于健康检查修复：当 routeIndex 长时间不推进、但列车实体仍在线时，先尝试把 destination 恢复为当前索引的下一节点，避免直接 force relaunch
   * 带来的动作扰动。
   *
   * @param trainName 列车名
   * @return true 表示成功重下发 destination
   */
  public boolean reissueDestinationByName(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return false;
    }
    TrainProperties properties = resolveTrainPropertiesByName(trainName).orElse(null);
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
    Optional<RouteDefinition> routeOpt = resolveRouteForRecovery(entry, properties);
    if (routeOpt.isEmpty()) {
      return false;
    }
    RouteDefinition route = routeOpt.get();
    int currentIndex = entry.currentIndex();
    if (currentIndex < 0 || currentIndex >= route.waypoints().size() - 1) {
      return false;
    }
    if (isRecoveryMovementBlocked(trainName, entry)) {
      debugLogger.accept(
          "HealthMonitor reissueDestination blocked: train="
              + trainName
              + " reason=blocked:hard-stop-inhibited");
      return false;
    }
    Optional<RailGraph> graphOpt = resolveGraphByGroup(group);
    if (graphOpt.isEmpty()) {
      return false;
    }
    RailGraph graph = graphOpt.get();
    ConfigManager.RuntimeSettings runtimeSettings = configManager.current().runtimeSettings();
    OccupancyRequestBuilder builder =
        runtimeLookaheadBuilder(graph, runtimeSettings, runtimeSettings.rearGuardEdges());
    Instant recoveryNow = Instant.now();
    RuntimeTrainHandle trainHandle = new TrainCartsRuntimeHandle(group);
    NodeId currentNode = resolveEffectiveNode(trainName, route, currentIndex);
    DynamicResolution<DynamicSelection> dynamicSelection =
        selectDynamicStationTargetForProgress(
            trainName,
            route,
            currentIndex,
            currentIndex + 1,
            currentNode,
            graph,
            builder,
            recoveryNow,
            resolvePriority(properties, route),
            AuthorizationPurpose.RUNTIME_MOVE);
    if (dynamicSelection.isBlocked()) {
      holdForUnavailableDynamicDestination(
          trainHandle,
          properties,
          trainName,
          route,
          currentIndex,
          currentNode,
          recoveryNow,
          dynamicSelection.reason());
      debugLogger.accept(
          "HealthMonitor reissueDestination blocked: train="
              + trainName
              + " reason=dynamic-target-unavailable");
      return false;
    }
    dynamicSelection
        .selected()
        .ifPresent(
            selected ->
                recordEffectiveNode(trainName, route, currentIndex + 1, selected.targetNode()));
    List<NodeId> effectiveNodes = resolveEffectiveWaypoints(trainName, route);
    Optional<OccupancyRequestContext> contextOpt =
        buildContextWithinDynamicBoundary(
            builder,
            trainName,
            route,
            effectiveNodes,
            effectiveNodes,
            currentIndex,
            Instant.now(),
            resolvePriority(properties, route),
            AuthorizationPurpose.RUNTIME_MOVE);
    if (contextOpt.isEmpty()) {
      return false;
    }
    OccupancyRequestContext context = contextOpt.get();
    OccupancyRequestContext authorizationContext =
        buildHardAuthorityContext(
                graph,
                runtimeSettings,
                trainName,
                route,
                effectiveNodes,
                currentIndex,
                Instant.now(),
                resolvePriority(properties, route),
                AuthorizationPurpose.RUNTIME_MOVE)
            .orElse(context);
    OccupancyRequest authorizationRequest =
        prepareRuntimeAuthorizationRequest(
            authorizationContext, graph, SignalComputationTrace.Source.PERIODIC_TICK);
    AuthorityEnd authorityEnd =
        resolveAuthorityEnd(graph, effectiveNodes, currentIndex, authorizationContext);
    SmartAdmissionResult reissueAdmission =
        evaluateSmartSingleCorridorAdmission(
            trainName,
            graph,
            authorizationContext,
            authorityEnd,
            true,
            SmartAdmissionContext.entry("health-reissue-smart-admission"));
    if (smartAdmissionShouldBlock(trainName, reissueAdmission)) {
      debugLogger.accept(
          "HealthMonitor reissueDestination blocked: train="
              + trainName
              + " reason="
              + reissueAdmission.reason());
      return false;
    }
    if (occupancyManager == null) {
      return false;
    }
    OccupancyDecision preview = previewOccupancyDecision(authorizationRequest);
    ProceedDecision previewProceed =
        evaluateProceedDecision(trainName, preview, Instant.now(), "health-reissue");
    if (!previewProceed.proceedAllowed()) {
      debugLogger.accept(
          "HEALTH_REISSUE_PREVIEW_BLOCKED train="
              + trainName
              + " reason="
              + preview.reason()
              + " wouldMutate=false didMutate=false");
      return false;
    }
    OccupancyDecision acquired = occupancyManager.acquire(authorizationRequest);
    ProceedDecision acquiredProceed =
        evaluateProceedDecision(trainName, acquired, Instant.now(), "health-reissue-acquire");
    if (!acquiredProceed.proceedAllowed()) {
      applyHardStop(
          new TrainCartsRuntimeHandle(group),
          properties,
          trainName,
          HardStopReason.ACQUIRE_FAILED,
          true,
          route,
          resolveEffectiveNode(trainName, route, currentIndex),
          resolveEffectiveNode(trainName, route, currentIndex + 1),
          graph,
          acquired,
          authorizationRequest,
          authorityEnd);
      return false;
    }
    NodeId nextNode = resolveEffectiveNode(trainName, route, currentIndex + 1);
    MovementAuthorizationToken token =
        issueMovementAuthorizationToken(
            trainName,
            resolveEffectiveNode(trainName, route, currentIndex),
            nextNode,
            authorizationRequest,
            acquired.signal(),
            Instant.now());
    retainRearGuardOccupancyBestEffort(
        trainName,
        route,
        currentIndex,
        effectiveNodes,
        authorizationRequest.movementPlanSnapshot(),
        graph,
        runtimeSettings,
        Instant.now());
    Optional<String> destinationName =
        commitAuthorizedDestination(properties, trainName, route, currentIndex + 1, nextNode);
    if (destinationName.isEmpty()
        || !activateMovementAuthorizationTokenRetainingInhibitor(
            trainName, token, destinationName.get())) {
      rollbackMovementAuthorization(
          trainName, token, authorizationRequest, HardStopReason.AUTHORIZATION_FAILURE);
      applyHardStop(
          new TrainCartsRuntimeHandle(group),
          properties,
          trainName,
          HardStopReason.AUTHORIZATION_FAILURE,
          true,
          route,
          resolveEffectiveNode(trainName, route, currentIndex),
          nextNode,
          graph,
          new OccupancyDecision(
              false,
              Instant.now(),
              SignalAspect.STOP,
              List.of(),
              false,
              "destination-commit-failed"),
          authorizationRequest,
          authorityEnd);
      return false;
    }
    debugLogger.accept(
        "HealthMonitor reissueDestination: train="
            + trainName
            + " idx="
            + currentIndex
            + " next="
            + nextNode.value()
            + " dest="
            + destinationName.get());
    return true;
  }

  private boolean isRecoveryMovementBlocked(
      String trainName, RouteProgressRegistry.RouteProgressEntry entry) {
    if (trainName == null || trainName.isBlank()) {
      return true;
    }
    if (entry != null && entry.lastSignal() == SignalAspect.STOP) {
      return true;
    }
    if (isMovementInhibited(trainName)) {
      return true;
    }
    return !recentBlockerTrains(trainName, BLOCKER_SNAPSHOT_TTL).isEmpty();
  }

  private boolean finalRefreshProvedProceed(String trainName, SignalRefreshResult refresh) {
    if (refresh == null
        || !refresh.resolved()
        || !SignalDecisionInputClassifier.isProceedLike(refresh.after())) {
      return false;
    }
    return !isMovementInhibited(trainName)
        && destinationPresent(trainName)
        && tokenState(trainName, movementToken(trainName).orElse(null))
            == SignalComputationTrace.TokenState.ACTIVE;
  }

  private boolean recentSingleRegionHardBarrierSnapshotPresent(String trainName) {
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return false;
    }
    BlockerSnapshot snapshot = blockerSnapshots.get(key);
    if (snapshot == null) {
      return false;
    }
    for (DeadlockBlockerInfo blocker : snapshot.blockers()) {
      if (blocker == null) {
        continue;
      }
      if ((blocker.conflictKey() != null && blocker.conflictKey().startsWith("single:"))
          || (blocker.resourceKey() != null
              && blocker.resourceKey().contains("CONFLICT:single:"))) {
        return true;
      }
    }
    return false;
  }

  private void traceRelaunchSuppressedByFinalAuthorization(
      String trainName, String reason, SignalAspect lastSignal) {
    debugLogger.accept(
        "event=RELAUNCH_SUPPRESSED_BY_FINAL_AUTHORIZATION train="
            + (trainName == null || trainName.isBlank() ? "-" : trainName)
            + " reason="
            + (reason == null || reason.isBlank() ? "final-authorization-not-proven" : reason)
            + " hardBarrierPresent="
            + recentSingleRegionHardBarrierSnapshotPresent(trainName)
            + " tokenState="
            + tokenState(trainName, movementToken(trainName).orElse(null))
            + " destinationPresent="
            + destinationPresent(trainName)
            + " occupancyVersion="
            + occupancyVersion()
            + " lastSignal="
            + (lastSignal == null ? SignalAspect.STOP : lastSignal)
            + " movementInhibited="
            + isMovementInhibited(trainName));
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
    TrainProperties properties = resolveTrainPropertiesByName(trainName).orElse(null);
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
    Optional<RouteDefinition> routeOpt = resolveRouteForRecovery(entry, properties);
    if (routeOpt.isEmpty()) {
      return false;
    }
    RouteDefinition route = routeOpt.get();
    int currentIndex = entry.currentIndex();
    if (currentIndex < 0 || currentIndex >= route.waypoints().size()) {
      return false;
    }
    if (isRecoveryMovementBlocked(trainName, entry)) {
      debugLogger.accept(
          "HealthMonitor forceRelaunch blocked: train="
              + trainName
              + " reason=blocked:hard-stop-inhibited");
      traceRelaunchSuppressedByFinalAuthorization(
          trainName, "blocked:hard-stop-inhibited", entry.lastSignal());
      return false;
    }
    SignalRefreshResult refresh = refreshSignalByName(trainName);
    if (finalRefreshProvedProceed(trainName, refresh)) {
      debugLogger.accept(
          "HealthMonitor forceRelaunch delegated: train="
              + trainName
              + " reason=normal-final-authorization");
      return true;
    }
    traceRelaunchSuppressedByFinalAuthorization(
        trainName, "final-authorization-not-proven:" + refresh.reason(), entry.lastSignal());
    return false;
  }

  /**
   * 按列车名销毁一列 FTA 管控列车。
   *
   * <p>该入口供健康监控在多轮互卡恢复无效后兜底使用。销毁仍走 {@link #handleDestroy(RuntimeTrainHandle, TrainProperties,
   * String, String)}，因此不会在物理实体消失前立即释放占用；占用释放继续等待 {@code GroupRemoveEvent →
   * handleTrainRemoved}，避免后车在旧实体尚未删除时抢占同一段轨道。
   *
   * @param trainName 列车名或 FTA 逻辑列车名
   * @param reason 销毁原因
   * @return 找到列车属性并发起销毁时返回 true
   */
  public boolean destroyTrainByName(String trainName, String reason) {
    RuntimeTrainResolution resolution =
        resolveRuntimeTrainForHealth(trainName, RuntimeTrainResolvePurpose.DESTROY);
    TrainProperties properties = resolution.properties();
    traceDeadlockDestroyAttempted(resolution, reason);
    if (properties == null) {
      traceRuntimeTrainResolveFailed(RuntimeTrainResolvePurpose.DESTROY, resolution);
      traceDeadlockDestroyResult(resolution, reason, false, "RESOLVE_FAILED", null);
      return false;
    }
    String logicalTrainName =
        resolveTrackedTrainName(properties).orElseGet(() -> properties.getTrainName());
    if (logicalTrainName == null || logicalTrainName.isBlank()) {
      logicalTrainName = trainName.trim();
    }
    com.bergerkiller.bukkit.tc.controller.MinecartGroup group = properties.getHolder();
    if (group == null || !group.isValid()) {
      String failure = group == null ? "ENTITY_NOT_FOUND" : "ALREADY_REMOVED";
      traceDeadlockDestroyResult(resolution, reason, false, failure, null);
      return false;
    }
    int memberCount = countMembers(group);
    if (memberCount == 0) {
      traceDeadlockDestroyResult(resolution, reason, false, "NO_MEMBERS", null);
      return false;
    }
    RuntimeTrainHandle handle = new TrainCartsRuntimeHandle(group);
    String normalizedReason =
        reason == null || reason.isBlank() ? "health-deadlock" : reason.trim();
    try {
      handleDestroy(handle, properties, logicalTrainName, normalizedReason);
      traceDeadlockDestroyResult(resolution, reason, true, "NONE", null);
      scheduleDestroyPostVerification(logicalTrainName, normalizedReason);
      return true;
    } catch (RuntimeException ex) {
      traceDeadlockDestroyResult(resolution, reason, false, "EXCEPTION", ex);
      return false;
    }
  }

  /**
   * Smart Dispatcher 对 deadlock destroy 的最终前置审查。
   *
   * <p>HealthMonitor 可以发现 stuck/deadlock，但 destroy 必须经过这里重新解释为 confirmed live hard cycle。
   * weak/missing/stale/protective-only blocker 只能作为诊断或重新取证来源，不允许直接销毁。
   */
  public SmartDispatcherController.DeadlockDestroyReview reviewDeadlockDestroyCandidate(
      String episodeId,
      String trainA,
      String trainB,
      String targetTrain,
      String conflictKey,
      boolean weak,
      boolean allBlockersLiveHard,
      boolean safeDrainCandidate,
      boolean staleReleaseCandidate,
      boolean forwardUnlockCandidate,
      boolean prioritySchedulingCandidate,
      RuntimeTrainResolution resolution,
      boolean targetRecentlyProgressed,
      boolean targetFtaManagedOrConfirmedOrphan,
      boolean directionAuditRequired,
      String directionAuditReason,
      boolean directionReauditAttempted,
      boolean lastResortDestroy,
      boolean blockingActiveTraffic,
      Duration persisted,
      Duration threshold) {
    boolean resolved =
        resolution != null
            && resolution.resolved()
            && resolution.properties() != null
            && resolution.properties().getHolder() != null
            && resolution.properties().getHolder().isValid();
    return smartDispatcherController.reviewDestroyCandidate(
        new SmartDispatcherController.DeadlockDestroyInput(
            episodeId,
            trainA,
            trainB,
            targetTrain,
            conflictKey,
            weak,
            allBlockersLiveHard,
            safeDrainCandidate,
            staleReleaseCandidate,
            forwardUnlockCandidate,
            prioritySchedulingCandidate,
            resolved,
            targetRecentlyProgressed,
            targetFtaManagedOrConfirmedOrphan,
            directionAuditRequired,
            directionAuditReason,
            directionReauditAttempted,
            lastResortDestroy,
            blockingActiveTraffic,
            persisted,
            threshold));
  }

  /**
   * destroy 发起后的延迟验证。
   *
   * <p>TrainCarts 实体销毁通常在下一 tick 执行；因此这里延迟数 tick 后检查 runtime group 是否真的消失。
   * 只有实体已消失时才执行占用/队列/缓存兜底清理，避免旧实体还在轨道上时释放资源导致后车抢占。
   */
  private void scheduleDestroyPostVerification(String trainName, String reason) {
    JavaPlugin plugin = resolveSchedulerPlugin();
    if (plugin == null || !plugin.isEnabled()) {
      verifyDestroyedTrainState(trainName, reason, false);
      return;
    }
    new org.bukkit.scheduler.BukkitRunnable() {
      @Override
      public void run() {
        verifyDestroyedTrainState(trainName, reason, true);
      }
    }.runTaskLater(plugin, 4L);
  }

  private void verifyDestroyedTrainState(String trainName, String reason, boolean delayed) {
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    RuntimeTrainResolution resolution =
        resolveRuntimeTrainForHealth(trainName, RuntimeTrainResolvePurpose.GET_STATE);
    MinecartGroup group =
        resolution.properties() == null ? null : resolution.properties().getHolder();
    boolean runtimeGroupGone = group == null || !group.isValid() || countMembers(group) == 0;
    if (runtimeGroupGone) {
      cleanupDestroyedTrainState(trainName);
    }
    boolean managedStateGone =
        progressRegistry.get(trainName).isEmpty() && layoverRegistry.get(trainName).isEmpty();
    boolean occupancyGone = !occupancyReferencesTrain(trainName);
    boolean queueGone = !queueReferencesTrain(trainName);
    boolean switcherClaimsGone = !switcherClaimReferencesTrain(trainName);
    boolean deadlockGraphGone = !blockerGraphReferencesTrain(trainName);
    boolean healthEpisodeClosed = reason != null && reason.startsWith("health-");
    smartDispatcherController.traceDestroyVerification(
        new SmartDispatcherController.DestroyVerificationResult(
            trainName,
            runtimeGroupGone,
            managedStateGone,
            occupancyGone,
            queueGone,
            switcherClaimsGone,
            deadlockGraphGone,
            healthEpisodeClosed));
    debugLogger.accept(
        "DEADLOCK_DESTROY_POST_CLEANUP train="
            + trainName
            + " delayed="
            + delayed
            + " runtimeGroupGone="
            + runtimeGroupGone
            + " reason="
            + reason);
  }

  private void cleanupDestroyedTrainState(String trainName) {
    layoverRegistry.unregister(trainName);
    if (occupancyManager != null) {
      occupancyManager.releaseByTrain(trainName);
    }
    progressRegistry.remove(trainName);
    clearRuntimeCachesForTrain(trainName);
  }

  private boolean occupancyReferencesTrain(String trainName) {
    if (occupancyManager == null) {
      return false;
    }
    for (OccupancyClaim claim : occupancyManager.snapshotClaims()) {
      if (claim != null && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
        return true;
      }
    }
    return false;
  }

  private boolean queueReferencesTrain(String trainName) {
    if (!(occupancyManager instanceof OccupancyQueueSupport queueSupport)) {
      return false;
    }
    for (OccupancyQueueSnapshot queue : queueSupport.snapshotQueues()) {
      if (queue == null || queue.entries() == null) {
        continue;
      }
      for (OccupancyQueueEntry entry : queue.entries()) {
        if (entry != null && TrainNameNormalizer.sameLogicalTrain(entry.trainName(), trainName)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean switcherClaimReferencesTrain(String trainName) {
    if (occupancyManager == null) {
      return false;
    }
    for (OccupancyClaim claim : occupancyManager.snapshotClaims()) {
      if (claim == null || claim.resource() == null) {
        continue;
      }
      if (claim.resource().kind() == ResourceKind.CONFLICT
          && claim.resource().key().startsWith("switcher:")
          && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
        return true;
      }
    }
    return false;
  }

  private boolean blockerGraphReferencesTrain(String trainName) {
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return false;
    }
    if (blockerSnapshots.containsKey(key)) {
      return true;
    }
    for (BlockerSnapshot snapshot : blockerSnapshots.values()) {
      if (snapshot == null) {
        continue;
      }
      for (DeadlockBlockerInfo blocker : snapshot.blockers()) {
        if (blocker != null
            && TrainNameNormalizer.sameLogicalTrain(blocker.trainName(), trainName)) {
          return true;
        }
      }
    }
    return false;
  }

  private Optional<TrainProperties> resolveTrainPropertiesByName(String trainName) {
    RuntimeTrainResolution resolution =
        resolveRuntimeTrainForHealth(trainName, RuntimeTrainResolvePurpose.GET_STATE);
    return Optional.ofNullable(resolution.properties());
  }

  /**
   * 统一解析 health/runtime 桥接入口使用的列车实体。
   *
   * <p>解析顺序保持保守：先精确 TrainCarts 名，再找运行时 active state，再找已有 canonical/tag/split
   * 别名。这里不做包含、前缀、编辑距离等模糊匹配，避免误操作其它列车。
   */
  public RuntimeTrainResolution resolveRuntimeTrainForHealth(
      String trainName, RuntimeTrainResolvePurpose purpose) {
    Optional<String> requestedOpt = normalizeTrainName(trainName);
    if (requestedOpt.isEmpty()) {
      return failedRuntimeTrainResolution(trainName, "BLANK_NAME", "");
    }
    String requested = requestedOpt.get();
    TrainProperties direct = TrainPropertiesStore.get(requested);
    if (direct != null) {
      return runtimeTrainResolution(requested, direct, RuntimeTrainMatchKind.EXACT);
    }
    Optional<RouteProgressRegistry.RouteProgressEntry> activeEntry =
        progressRegistry.get(requested);
    if (activeEntry.isPresent()) {
      TrainProperties properties = findTrainPropertiesByKnownAlias(activeEntry.get().trainName());
      return runtimeTrainResolution(
          requested, activeEntry.get().trainName(), properties, RuntimeTrainMatchKind.ACTIVE_STATE);
    }
    for (RouteProgressRegistry.RouteProgressEntry entry : progressRegistry.snapshot().values()) {
      if (entry == null || entry.trainName() == null) {
        continue;
      }
      String activeName = entry.trainName();
      if (TrainNameNormalizer.sameLogicalTrain(activeName, requested)) {
        TrainProperties properties = findTrainPropertiesByKnownAlias(activeName);
        return runtimeTrainResolution(
            requested, activeName, properties, RuntimeTrainMatchKind.ACTIVE_STATE);
      }
    }
    for (TrainProperties candidate : TrainPropertiesStore.getAll()) {
      if (candidate == null) {
        continue;
      }
      String current = normalizeTrainName(candidate.getTrainName()).orElse("");
      Optional<String> previous =
          TrainTagHelper.readTagValue(candidate, RouteProgressRegistry.TAG_TRAIN_NAME)
              .flatMap(RuntimeDispatchService::normalizeTrainName);
      Optional<String> tracked = resolveTrackedTrainName(candidate);
      if (requested.equalsIgnoreCase(current)) {
        return runtimeTrainResolution(requested, candidate, RuntimeTrainMatchKind.CANONICAL_NAME);
      }
      if (tracked.filter(name -> name.equalsIgnoreCase(requested)).isPresent()) {
        return runtimeTrainResolution(requested, candidate, RuntimeTrainMatchKind.RUNTIME_ALIAS);
      }
      if (previous.filter(name -> name.equalsIgnoreCase(requested)).isPresent()) {
        return runtimeTrainResolution(requested, candidate, RuntimeTrainMatchKind.PREVIOUS_NAME);
      }
      if (isSplitAliasName(current, requested)
          || TrainNameNormalizer.sameLogicalTrain(current, requested)) {
        return runtimeTrainResolution(requested, candidate, RuntimeTrainMatchKind.SPLIT_ALIAS);
      }
    }
    RuntimeTrainResolution failed = failedRuntimeTrainResolution(requested, "RESOLVE_FAILED", "");
    if (purpose != null) {
      traceRuntimeTrainResolveFailed(purpose, failed);
    }
    return failed;
  }

  private TrainProperties findTrainPropertiesByKnownAlias(String trainName) {
    RuntimeTrainResolution resolution =
        resolveRuntimeTrainForHealthFromStoreOnly(trainName, RuntimeTrainResolvePurpose.GET_STATE);
    return resolution.properties();
  }

  private RuntimeTrainResolution resolveRuntimeTrainForHealthFromStoreOnly(
      String trainName, RuntimeTrainResolvePurpose purpose) {
    Optional<String> requestedOpt = normalizeTrainName(trainName);
    if (requestedOpt.isEmpty()) {
      return failedRuntimeTrainResolution(trainName, "BLANK_NAME", "");
    }
    String requested = requestedOpt.get();
    TrainProperties direct = TrainPropertiesStore.get(requested);
    if (direct != null) {
      return runtimeTrainResolution(requested, direct, RuntimeTrainMatchKind.EXACT);
    }
    for (TrainProperties candidate : TrainPropertiesStore.getAll()) {
      if (candidate == null) {
        continue;
      }
      String current = normalizeTrainName(candidate.getTrainName()).orElse("");
      Optional<String> previous =
          TrainTagHelper.readTagValue(candidate, RouteProgressRegistry.TAG_TRAIN_NAME)
              .flatMap(RuntimeDispatchService::normalizeTrainName);
      Optional<String> tracked = resolveTrackedTrainName(candidate);
      if (requested.equalsIgnoreCase(current)) {
        return runtimeTrainResolution(requested, candidate, RuntimeTrainMatchKind.CANONICAL_NAME);
      }
      if (tracked.filter(name -> name.equalsIgnoreCase(requested)).isPresent()) {
        return runtimeTrainResolution(requested, candidate, RuntimeTrainMatchKind.RUNTIME_ALIAS);
      }
      if (previous.filter(name -> name.equalsIgnoreCase(requested)).isPresent()) {
        return runtimeTrainResolution(requested, candidate, RuntimeTrainMatchKind.PREVIOUS_NAME);
      }
      if (isSplitAliasName(current, requested)
          || TrainNameNormalizer.sameLogicalTrain(current, requested)) {
        return runtimeTrainResolution(requested, candidate, RuntimeTrainMatchKind.SPLIT_ALIAS);
      }
    }
    RuntimeTrainResolution failed = failedRuntimeTrainResolution(requested, "RESOLVE_FAILED", "");
    if (purpose != null) {
      traceRuntimeTrainResolveFailed(purpose, failed);
    }
    return failed;
  }

  private RuntimeTrainResolution runtimeTrainResolution(
      String requestedName, TrainProperties properties, RuntimeTrainMatchKind matchedBy) {
    String resolved =
        resolveTrackedTrainName(properties)
            .or(() -> normalizeTrainName(properties != null ? properties.getTrainName() : null))
            .orElse(requestedName);
    return runtimeTrainResolution(requestedName, resolved, properties, matchedBy);
  }

  private RuntimeTrainResolution runtimeTrainResolution(
      String requestedName,
      String resolvedName,
      TrainProperties properties,
      RuntimeTrainMatchKind matchedBy) {
    traceRuntimeTrainMatched(requestedName, resolvedName, matchedBy);
    return new RuntimeTrainResolution(
        requestedName,
        resolvedName,
        properties,
        matchedBy,
        Optional.empty(),
        Optional.empty(),
        properties == null ? "NO_PROPERTIES" : "NONE");
  }

  private void traceRuntimeTrainMatched(
      String requestedName, String resolvedName, RuntimeTrainMatchKind matchedBy) {
    if (matchedBy == null
        || matchedBy == RuntimeTrainMatchKind.EXACT
        || matchedBy == RuntimeTrainMatchKind.CANONICAL_NAME) {
      return;
    }
    traceHealthEvent(
        "DEADLOCK_RESOLVE_MATCHED_RUNTIME",
        "resolve-match:" + normalizeTrainKey(requestedName),
        "requestedName="
            + emptyDash(requestedName)
            + " resolvedName="
            + emptyDash(resolvedName)
            + " matchedBy="
            + matchedBy);
  }

  private RuntimeTrainResolution failedRuntimeTrainResolution(
      String requestedName, String failureReason, String resolvedName) {
    return new RuntimeTrainResolution(
        requestedName,
        resolvedName,
        null,
        RuntimeTrainMatchKind.FAILED,
        Optional.empty(),
        Optional.empty(),
        failureReason);
  }

  private void traceRuntimeTrainResolveFailed(
      RuntimeTrainResolvePurpose purpose, RuntimeTrainResolution resolution) {
    RuntimeTrainResolution effective =
        resolution == null ? failedRuntimeTrainResolution("", "RESOLVE_FAILED", "") : resolution;
    String requested = effective.requestedName();
    if (effective.failureReason().equals("RESOLVE_FAILED")
        || effective.failureReason().equals("ACTIVE_STATE_MISSING")) {
      traceHealthEvent(
          "DEADLOCK_RESOLVE_SKIPPED_NON_FTA",
          "resolve-non-fta:" + normalizeTrainKey(requested),
          "trainPropertiesName="
              + emptyDash(requested)
              + " purpose="
              + (purpose == null ? "UNKNOWN" : purpose.name())
              + " failureReason="
              + effective.failureReason());
    }
    traceHealthEvent(
        "DEADLOCK_RESOLVE_FAILED",
        "resolve:" + purpose + ":" + normalizeTrainKey(requested),
        "requestedName="
            + emptyDash(requested)
            + " purpose="
            + (purpose == null ? "UNKNOWN" : purpose.name())
            + " resolvedName="
            + emptyDash(effective.resolvedName())
            + " matchedBy="
            + effective.matchedBy()
            + " failureReason="
            + effective.failureReason()
            + " runtimeNames="
            + summarizeRuntimeTrainNames(12)
            + " aliases="
            + summarizeRuntimeAliases(12)
            + " activeTrainProperties="
            + summarizeTrainPropertiesNames(12));
  }

  private void traceDeadlockDestroyAttempted(RuntimeTrainResolution resolution, String reason) {
    RuntimeTrainResolution effective =
        resolution == null ? failedRuntimeTrainResolution("", "RESOLVE_FAILED", "") : resolution;
    MinecartGroup group =
        effective.properties() == null ? null : effective.properties().getHolder();
    traceHealthEvent(
        "DEADLOCK_DESTROY_ATTEMPTED",
        "destroy-attempt:" + normalizeTrainKey(effective.requestedName()),
        "requestedName="
            + emptyDash(effective.requestedName())
            + " resolvedName="
            + emptyDash(effective.resolvedName())
            + " matchedBy="
            + effective.matchedBy()
            + " propertiesFound="
            + effective.propertiesFound()
            + " memberCount="
            + countMembers(group)
            + " entityCount="
            + countMembers(group)
            + " source=HEALTH_MONITOR_DEADLOCK reason="
            + emptyDash(reason));
  }

  private void traceDeadlockDestroyResult(
      RuntimeTrainResolution resolution,
      String reason,
      boolean success,
      String failureReason,
      RuntimeException exception) {
    RuntimeTrainResolution effective =
        resolution == null ? failedRuntimeTrainResolution("", "RESOLVE_FAILED", "") : resolution;
    traceHealthEvent(
        "DEADLOCK_DESTROY_RESULT",
        "destroy-result:"
            + normalizeTrainKey(effective.requestedName())
            + ":"
            + success
            + ":"
            + failureReason,
        "requestedName="
            + emptyDash(effective.requestedName())
            + " resolvedName="
            + emptyDash(effective.resolvedName())
            + " matchedBy="
            + effective.matchedBy()
            + " success="
            + success
            + " failureReason="
            + (failureReason == null || failureReason.isBlank() ? "NONE" : failureReason)
            + " source=HEALTH_MONITOR_DEADLOCK reason="
            + emptyDash(reason)
            + (exception == null
                ? ""
                : " exception="
                    + exception.getClass().getSimpleName()
                    + ":"
                    + emptyDash(exception.getMessage())));
  }

  private void traceHealthEvent(String eventName, String key, String message) {
    String event = eventName == null || eventName.isBlank() ? "HEALTH_TRACE" : eventName;
    String traceKey = key == null || key.isBlank() ? event : key;
    String fingerprint = event + "|" + message;
    long nowMs = System.currentTimeMillis();
    String previous = healthTraceFingerprints.get(traceKey);
    Long lastAt = healthTraceLastAtMs.get(traceKey);
    if (fingerprint.equals(previous) && lastAt != null && nowMs - lastAt < 30_000L) {
      if (event.startsWith("DEADLOCK_RESOLVE")) {
        String suppressedKey = "suppressed:" + traceKey;
        Long lastSuppressedAt = healthTraceLastAtMs.get(suppressedKey);
        if (lastSuppressedAt == null || nowMs - lastSuppressedAt >= 30_000L) {
          healthTraceLastAtMs.put(suppressedKey, nowMs);
          debugLogger.accept("DEADLOCK_RESOLVE_COOLDOWN_SUPPRESSED requestedName=" + traceKey);
        }
      }
      return;
    }
    healthTraceFingerprints.put(traceKey, fingerprint);
    healthTraceLastAtMs.put(traceKey, nowMs);
    debugLogger.accept(event + ": " + message);
  }

  private String summarizeRuntimeTrainNames(int limit) {
    return summarizeStrings(progressRegistry.snapshot().keySet(), limit);
  }

  private String summarizeRuntimeAliases(int limit) {
    List<String> aliases = new ArrayList<>();
    for (TrainProperties properties : TrainPropertiesStore.getAll()) {
      if (properties == null) {
        continue;
      }
      String current = normalizeTrainName(properties.getTrainName()).orElse("");
      TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_TRAIN_NAME)
          .flatMap(RuntimeDispatchService::normalizeTrainName)
          .filter(alias -> !alias.equalsIgnoreCase(current))
          .ifPresent(alias -> aliases.add(alias + "->" + current));
      if (aliases.size() >= Math.max(1, limit)) {
        break;
      }
    }
    return aliases.toString();
  }

  private String summarizeTrainPropertiesNames(int limit) {
    List<String> names = new ArrayList<>();
    for (TrainProperties properties : TrainPropertiesStore.getAll()) {
      if (properties == null) {
        continue;
      }
      normalizeTrainName(properties.getTrainName()).ifPresent(names::add);
      if (names.size() >= Math.max(1, limit)) {
        break;
      }
    }
    return names.toString();
  }

  private static String summarizeStrings(Iterable<String> values, int limit) {
    if (values == null) {
      return "[]";
    }
    int max = Math.max(1, limit);
    List<String> summary = new ArrayList<>();
    int total = 0;
    for (String value : values) {
      if (value == null || value.isBlank()) {
        continue;
      }
      total++;
      if (summary.size() < max) {
        summary.add(value);
      }
    }
    if (total > max) {
      summary.add("+" + (total - max));
    }
    return summary.toString();
  }

  private static String emptyDash(String value) {
    return value == null || value.isBlank() ? "-" : value;
  }

  private static int countMembers(MinecartGroup group) {
    if (group == null || !group.isValid()) {
      return 0;
    }
    int count = 0;
    try {
      Iterator<MinecartMember<?>> iterator = group.iterator();
      while (iterator.hasNext()) {
        iterator.next();
        count++;
      }
    } catch (RuntimeException ex) {
      return -1;
    }
    return count;
  }

  /**
   * 健康修复场景下解析线路定义。
   *
   * <p>优先使用 progress entry 里的 routeUuid，缺失时回退到列车 tags（operator/line/route 或 FTA_ROUTE_ID）。
   */
  private Optional<RouteDefinition> resolveRouteForRecovery(
      RouteProgressRegistry.RouteProgressEntry entry, TrainProperties properties) {
    if (entry != null && entry.routeUuid() != null) {
      Optional<RouteDefinition> byUuid = routeDefinitions.findById(entry.routeUuid());
      if (byUuid.isPresent()) {
        return byUuid;
      }
    }
    return resolveRouteDefinition(properties);
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
   * <p>此方法将事件信号与现有的 handleSignalTick 逻辑桥接：仅“更严格”事件会立即生效；更宽松事件交由周期 tick 决策，避免事件链路误放行。
   *
   * @param trainName 列车名
   * @param signal 新的信号等级
   */
  public void applySignalFromEvent(String trainName, SignalAspect signal) {
    if (trainName == null || trainName.isBlank() || signal == null) {
      return;
    }
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return;
    }
    if (configManager.current().runtimeSettings().signalEventCoalesce()) {
      SignalAspect previous =
          progressRegistry
              .get(trainName)
              .map(RouteProgressRegistry.RouteProgressEntry::lastSignal)
              .orElse(null);
      boolean proceedLike = SignalDecisionInputClassifier.isProceedLike(signal);
      boolean movementInhibited = movementInhibitors.containsKey(key);
      if (proceedLike && movementInhibited) {
        coalescedEventCount.increment();
        SignalAspect traceAspect = previous == null ? SignalAspect.STOP : previous;
        SignalComputationTrace.emit(
            signalTrace(
                    trainName,
                    resolveTrainPropertiesByName(trainName).orElse(null),
                    SignalComputationTrace.Source.EVENT,
                    previous,
                    traceAspect,
                    "event-coalesced-suppressed")
                .field("dirtyEventPending", false)
                .field("computedAspect", signal)
                .field("publishedAspect", "PRESERVE")
                .field("physicalPublished", false)
                .field("dirtyOnly", false)
                .field("publishSuppressed", true)
                .field("movementInhibited", true)
                .field(
                    "inhibitorReason",
                    Optional.ofNullable(movementInhibitors.get(key)).map(Enum::name).orElse("-")));
        debugLogger.accept(
            "事件信号合并抑制: train="
                + trainName
                + " signal="
                + signal
                + " computedAspect="
                + signal
                + " publishedAspect=PRESERVE"
                + " physicalPublished=false"
                + " dirtyOnly=false"
                + " reason=movement-inhibited");
        return;
      }
      dirtyEventSignals.put(key, signal);
      coalescedEventCount.increment();
      SignalComputationTrace.emit(
          signalTrace(
                  trainName,
                  resolveTrainPropertiesByName(trainName).orElse(null),
                  SignalComputationTrace.Source.EVENT,
                  previous,
                  signal,
                  "event-coalesced")
              .field("dirtyEventPending", true)
              .field("computedAspect", signal)
              .field("publishedAspect", "DIRTY_ONLY")
              .field("physicalPublished", false)
              .field("dirtyOnly", true));
      debugLogger.accept(
          "事件信号合并: train="
              + trainName
              + " signal="
              + signal
              + " computedAspect="
              + signal
              + " publishedAspect=DIRTY_ONLY"
              + " physicalPublished=false"
              + " dirtyOnly=true"
              + " mode=dirty");
      return;
    }
    if (!applyingEventSignals.add(key)) {
      if (signal == SignalAspect.STOP) {
        reentrantStopSuppressed.increment();
      }
      dirtyEventSignals.put(key, signal);
      return;
    }
    try {
      applySignalFromEventImmediate(trainName, signal);
    } finally {
      applyingEventSignals.remove(key);
    }
  }

  private void applySignalFromEventImmediate(String trainName, SignalAspect signal) {
    TrainProperties properties = resolveTrainPropertiesByName(trainName).orElse(null);
    if (properties == null || !isFtaManagedTrain(properties)) {
      return;
    }
    com.bergerkiller.bukkit.tc.controller.MinecartGroup group = properties.getHolder();
    if (group == null || !group.isValid()) {
      return;
    }
    RuntimeTrainHandle train = new TrainCartsRuntimeHandle(group);
    SignalAspect currentSignal =
        progressRegistry
            .get(trainName)
            .map(RouteProgressRegistry.RouteProgressEntry::lastSignal)
            .orElse(null);
    boolean forceApply = shouldApplyEventSignal(currentSignal, signal);
    boolean reapplyStop =
        !forceApply
            && signal == SignalAspect.STOP
            && currentSignal == SignalAspect.STOP
            && shouldReapplyStopEvent(trainName, train, properties);
    if ((forceApply && signal == SignalAspect.STOP) || reapplyStop) {
      String key = normalizeTrainKey(trainName);
      dirtyEventSignals.put(key, signal);
      handleSignalTick(train, true);
      return;
    }
    debugLogger.accept(
        "事件信号下发: train="
            + trainName
            + " signal="
            + signal
            + " current="
            + (currentSignal == null ? "null" : currentSignal)
            + " forceApply="
            + forceApply);
    if (!forceApply) {
      return;
    }
    handleSignalTick(train, forceApply);
  }

  private boolean shouldReapplyStopEvent(
      String trainName, RuntimeTrainHandle train, TrainProperties properties) {
    if (train != null && train.isMoving()) {
      return true;
    }
    if (properties != null && !readDestination(properties).isBlank()) {
      return true;
    }
    String key = normalizeTrainKey(trainName);
    return !key.isEmpty()
        && (movementAuthorizationTokens.containsKey(key) || movementInhibitors.containsKey(key));
  }

  private static boolean shouldApplyEventSignal(
      SignalAspect currentSignal, SignalAspect eventSignal) {
    if (eventSignal == null) {
      return false;
    }
    if (currentSignal == null) {
      // 初始化窗口只允许“未知 -> STOP”立即生效，防止事件链路误放行。
      return eventSignal == SignalAspect.STOP;
    }
    return signalSeverity(eventSignal) > signalSeverity(currentSignal);
  }

  private static boolean shouldTrackIntermediateGraphNode(SignNodeDefinition definition) {
    if (definition == null || definition.nodeType() == null) {
      return false;
    }
    return definition.nodeType() == NodeType.WAYPOINT || definition.nodeType() == NodeType.SWITCHER;
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
    String trainName = resolveTrackedTrainName(properties).orElse(properties.getTrainName());
    // 非 FTA 管控列车：静默跳过
    if (!isFtaManagedTrain(properties)) {
      clearDepartureGate(trainName);
      return;
    }
    Instant now = Instant.now();
    trainName = handleRenameIfNeeded(properties);
    if (layoverRegistry.get(trainName).isPresent()) {
      clearDepartureGate(trainName);
      LayoverCandidate candidate = layoverRegistry.get(trainName).orElse(null);
      if (occupancyManager != null && candidate != null && candidate.dispatchAttempt().isEmpty()) {
        retainLayoverReadyOccupancy(train, properties, trainName, candidate, now);
      } else if (candidate != null && candidate.dispatchAttempt().isPresent()) {
        debugLogger.accept(
            "Layover 提交中保持完整进路: train="
                + trainName
                + " ticket="
                + candidate.dispatchAttempt().get().ticketId());
      }
      updateSignalOrWarn(trainName, SignalAspect.STOP, now);
      TrainConfig config = trainConfigResolver.resolve(properties, configManager.current());
      runtimeTrainController.applyControl(
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
        runtimeTrainController.stopNow(train);
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
      clearRuntimeCachesForTrain(trainName);
      return;
    }
    Optional<RouteDefinition> routeOpt = resolveRouteDefinition(properties);
    if (routeOpt.isEmpty()) {
      return;
    }
    RouteDefinition route = routeOpt.get();
    final String logicalTrainName = trainName;
    RouteProgressRegistry.RouteProgressEntry progressEntry =
        progressRegistry
            .get(trainName)
            .orElseGet(() -> progressRegistry.initFromTags(logicalTrainName, properties, route));
    SignalAspect previousTickAspect = progressEntry.lastSignal();
    Optional<NodeId> lastPassedBeforeTick = progressEntry.lastPassedGraphNode();
    int currentIndex = progressEntry.currentIndex();
    if (currentIndex < 0) {
      return;
    }

    int boundedIndex =
        currentIndex < route.waypoints().size()
            ? currentIndex
            : Math.max(0, route.waypoints().size() - 1);

    pruneDynamicResolutionState(trainName, route, boundedIndex);
    restoreSpawnOriginOverrideIfNeeded(trainName, route, boundedIndex, properties);

    NodeId currentNode = resolveEffectiveNode(trainName, route, boundedIndex);
    // 容量判断必须早于 DepartureGate/dwell 的 STOP early-return；否则等待窗口会继续为
    // DYNAMIC 声明占位节点刷新前方 queue，反向阻塞站内列车出站。
    DynamicResolution<DynamicDestinationResolver.ResolvedDynamicDestination> dynamicResolution =
        tryDynamicPlatformAllocation(train, trainName, route, currentIndex, currentNode);
    if (dynamicResolution.isBlocked()) {
      holdForUnavailableDynamicDestination(
          train,
          properties,
          trainName,
          route,
          currentIndex,
          currentNode,
          now,
          dynamicResolution.reason());
      return;
    }

    if (hasDepartureGate(trainName)) {
      if (train.isMoving()) {
        // 容错：列车已恢复移动时清理遗留 gate，避免后续长时间锁死。
        clearDepartureGate(trainName);
      } else {
        holdStopAtCurrentNode(train, properties, trainName, route, currentIndex, currentNode, now);
        return;
      }
    }

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
      if (remaining.isPresent()) {
        // 只要列车仍处于 dwell 窗口，就必须保持 STOP。
        // 不能依赖当前 index 重新命中 RouteStop：在动态站台/中间点跳过场景下，索引可能短暂偏移，导致”停站后被信号 tick 提前放行”。
        holdStopAtCurrentNode(train, properties, trainName, route, currentIndex, currentNode, now);
        return;
      }
    }

    Optional<WaypointStopState> waypointStopState =
        resolveWaypointStopState(trainName, currentNode);
    if (waypointStopState.isPresent()) {
      // 居中进行中：不干预控车，避免 setSpeedLimit(0) 阻止 Station.centerTrain() 移动
      if (waypointStopState.get().centering()) {
        return;
      }
      holdStopAtCurrentNode(train, properties, trainName, route, currentIndex, currentNode, now);
      return;
    }

    if (currentIndex >= route.waypoints().size() - 1) {
      // 终点：持续下发 STOP 控制，确保减速停车
      // 若已停止，清理 destination 并注册 Layover
      updateSignalOrWarn(trainName, SignalAspect.STOP, now);
      TrainConfig config = trainConfigResolver.resolve(properties, configManager.current());
      runtimeTrainController.applyControl(
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
          runtimeTrainController.stopNow(train); // 确保完全停止
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
    shortestPathDistanceCache.setRefreshAfter(
        Duration.ofSeconds(Math.max(1, runtimeSettings.distanceCacheRefreshSeconds())));
    shortestPathDistanceCache.setMaxCacheSize(runtimeSettings.pathCacheMaxSize());
    OccupancyRequestBuilder builder =
        runtimeLookaheadBuilder(graph, runtimeSettings, runtimeSettings.rearGuardEdges());
    NodeId currentNodeForSignal =
        resolveEffectiveCurrentNodeForSignal(trainName, route, currentIndex, graph);
    List<NodeId> directionContextNodes = resolveEffectiveWaypoints(trainName, route);
    List<NodeId> effectiveNodes =
        applyCurrentNodeOverride(directionContextNodes, currentIndex, currentNodeForSignal);
    DispatchPriorityResolution priorityResolution =
        dispatchPriorityResolver.resolve(
            "periodic-signal-tick", trainName, properties, route, Optional.of(progressEntry));
    traceDispatchRequestContext(
        "periodic-signal-tick", trainName, route, currentIndex, priorityResolution);
    Optional<OccupancyRequestContext> contextOpt =
        buildContextWithinDynamicBoundary(
            builder,
            trainName,
            route,
            effectiveNodes,
            directionContextNodes,
            currentIndex,
            now,
            priorityResolution.priority(),
            AuthorizationPurpose.RUNTIME_MOVE);
    if (contextOpt.isEmpty()) {
      applyUnresolvableMovementPlanStop(
          train,
          properties,
          trainName,
          route,
          currentIndex,
          currentNodeForSignal,
          resolveEffectiveNode(trainName, route, currentIndex + 1),
          graph,
          now,
          "periodic-signal-tick",
          diagnoseBuildFailure(
              graph,
              route,
              currentIndex,
              Math.max(runtimeSettings.lookaheadEdges(), runtimeSettings.minClearEdges())));
      return;
    }
    OccupancyRequestContext context = contextOpt.get();
    OccupancyRequestContext advisoryContext = toAdvisoryLookaheadContext(context);
    OccupancyRequest request = advisoryContext.request();
    OccupancyRequestContext authorizationContext =
        buildHardAuthorityContextWithDirectionContext(
                graph,
                runtimeSettings,
                trainName,
                route,
                effectiveNodes,
                directionContextNodes,
                currentIndex,
                now,
                priorityResolution.priority(),
                AuthorizationPurpose.RUNTIME_MOVE,
                train.currentSpeedBlocksPerTick() * SPEED_TICKS_PER_SECOND,
                trainConfigResolver.resolve(properties, configManager.current()).decelBps2())
            .orElse(context);
    OccupancyRequest authorizationRequest =
        prepareRuntimeAuthorizationRequest(
            authorizationContext, graph, SignalComputationTrace.Source.PERIODIC_TICK);
    AuthorityEnd authorityEnd =
        resolveAuthorityEnd(graph, effectiveNodes, currentIndex, authorizationContext);
    Optional<NodeId> nextNode =
        currentIndex + 1 < route.waypoints().size()
            ? Optional.of(resolveEffectiveNode(trainName, route, currentIndex + 1))
            : Optional.empty();
    Optional<NodeId> currentNodeOpt =
        currentIndex < route.waypoints().size()
            ? Optional.ofNullable(currentNodeForSignal)
            : Optional.empty();
    List<OccupancyResource> keepResources =
        mergeKeepResourcesWithCurrentPosition(
            authorizationRequest.resourceList(), currentNodeOpt, nextNode, graph);
    releaseSpeculativeClaimsFromBehindSameRoute(
        trainName,
        route,
        currentIndex,
        currentNodeForSignal,
        graph,
        authorizationRequest.resourceList());
    releaseSpeculativeQueueEntriesFromBehindSameRoute(
        trainName,
        route,
        currentIndex,
        currentNodeForSignal,
        graph,
        authorizationRequest.resourceList());
    SmartAdmissionResult singleSafety =
        evaluateSmartSingleCorridorAdmission(
            trainName,
            graph,
            authorizationContext,
            authorityEnd,
            true,
            SmartAdmissionContext.entry("signal-entry-smart-admission"));
    if (smartAdmissionShouldBlock(trainName, singleSafety)) {
      OccupancyDecision blocked =
          singleZoneBlockedDecision(authorizationRequest, singleSafety.reason(), now);
      retainStopOccupancy(
          trainName,
          route,
          currentIndex,
          currentNodeForSignal,
          Optional.of(authorizationRequest),
          graph,
          now);
      applyNonInvalidatingBlockedStop(
          train,
          properties,
          trainName,
          "WAITING_FOR_SINGLE_ZONE",
          route,
          currentNodeOpt.orElse(null),
          nextNode.orElse(null),
          graph,
          blocked,
          authorizationRequest,
          authorityEnd);
      return;
    }
    releaseResourcesNotInRequest(
        trainName,
        keepResources,
        protectedSwitcherZoneClaims(
            trainName, route, currentIndex, currentNodeForSignal, graph, "SIGNAL_TICK"));
    retainCurrentPositionOccupancy(
        trainName,
        route.id(),
        currentNodeOpt,
        nextNode,
        authorizationRequest.movementPlanSnapshot(),
        graph,
        now);
    dirtyEventSignals.remove(normalizeTrainKey(trainName));
    OccupancyDecision decision = occupancyManager.canEnter(authorizationRequest);
    decision =
        maybeRecoverSelfOwnedStaleRetain(authorizationRequest, decision, "signal-canenter")
            .decision();
    ProceedDecision proceedDecision = evaluateProceedDecision(trainName, decision, now, "signal");
    boolean proceedAllowed = proceedDecision.proceedAllowed();
    if (!proceedAllowed) {
      SignalComputationTrace.emit(
          signalTrace(
                  trainName,
                  properties,
                  SignalComputationTrace.Source.PERIODIC_TICK,
                  previousTickAspect,
                  decision.signal(),
                  "signal-canenter-blocked:" + decision.reason())
              .nodes(currentNodeOpt.orElse(null), nextNode.orElse(null))
              .progress(
                  progressRegistry.version(),
                  currentIndex,
                  currentIndex,
                  lastPassedBeforeTick,
                  progressRegistry
                      .get(trainName)
                      .flatMap(RouteProgressRegistry.RouteProgressEntry::lastPassedGraphNode))
              .request(authorizationRequest)
              .decision(decision, authorizationRequest)
              .distances(
                  OptionalLong.empty(),
                  OptionalLong.empty(),
                  OptionalLong.empty(),
                  authorityEnd.distanceBlocks(),
                  authorityEnd.resource(),
                  authorizationContext.edges().size()));
      retainStopOccupancy(
          trainName,
          route,
          currentIndex,
          currentNodeForSignal,
          Optional.of(authorizationRequest),
          graph,
          now);
      if (isProtectiveOnlyStop(decision)) {
        applyProtectiveOnlyStop(
            train,
            properties,
            trainName,
            route,
            currentNodeOpt.orElse(null),
            nextNode.orElse(null),
            decision);
        return;
      }
      applyNonInvalidatingBlockedStop(
          train,
          properties,
          trainName,
          "BLOCKED_BY_OCCUPANCY",
          route,
          currentNodeOpt.orElse(null),
          nextNode.orElse(null),
          graph,
          decision,
          authorizationRequest,
          authorityEnd);
      return;
    }
    boolean deadlockRelease = decision.conflictRelease();
    if (deadlockRelease && train.isMoving()) {
      retainStopOccupancy(
          trainName,
          route,
          currentIndex,
          currentNodeForSignal,
          Optional.of(authorizationRequest),
          graph,
          now);
      applyHardStop(
          train,
          properties,
          trainName,
          HardStopReason.DEADLOCK_CONFIRMED_WAITING,
          true,
          route,
          currentNodeOpt.orElse(null),
          nextNode.orElse(null),
          graph,
          decision,
          authorizationRequest,
          authorityEnd);
      return;
    }
    OccupancyDecision acquired = occupancyManager.acquire(authorizationRequest);
    SelfOwnedRetainDecisionRecovery acquiredRecovery =
        maybeRecoverSelfOwnedStaleRetain(authorizationRequest, acquired, "signal-acquire");
    if (acquiredRecovery.applied() && acquiredRecovery.decision().allowed()) {
      acquired = occupancyManager.acquire(authorizationRequest);
    } else {
      acquired = acquiredRecovery.decision();
    }
    ProceedDecision acquiredProceed =
        evaluateProceedDecision(trainName, acquired, now, "signal-acquire");
    if (!acquiredProceed.proceedAllowed()) {
      SignalComputationTrace.emit(
          signalTrace(
                  trainName,
                  properties,
                  SignalComputationTrace.Source.PERIODIC_TICK,
                  previousTickAspect,
                  acquired.signal(),
                  "signal-acquire-blocked:" + acquired.reason())
              .nodes(currentNodeOpt.orElse(null), nextNode.orElse(null))
              .progress(
                  progressRegistry.version(),
                  currentIndex,
                  currentIndex,
                  lastPassedBeforeTick,
                  progressRegistry
                      .get(trainName)
                      .flatMap(RouteProgressRegistry.RouteProgressEntry::lastPassedGraphNode))
              .request(authorizationRequest)
              .decision(acquired, authorizationRequest)
              .distances(
                  OptionalLong.empty(),
                  OptionalLong.empty(),
                  OptionalLong.empty(),
                  authorityEnd.distanceBlocks(),
                  authorityEnd.resource(),
                  authorizationContext.edges().size()));
      retainStopOccupancy(
          trainName,
          route,
          currentIndex,
          currentNodeForSignal,
          Optional.of(authorizationRequest),
          graph,
          now);
      if (isProtectiveOnlyStop(acquired)) {
        applyProtectiveOnlyStop(
            train,
            properties,
            trainName,
            route,
            currentNodeOpt.orElse(null),
            nextNode.orElse(null),
            acquired);
        return;
      }
      debugLogger.accept(
          "信号Tick acquire 阻塞: train="
              + trainName
              + " idx="
              + currentIndex
              + " signal="
              + acquired.signal()
              + " blockers="
              + summarizeBlockers(acquired));
      applyNonInvalidatingBlockedStop(
          train,
          properties,
          trainName,
          "BLOCKED_BY_OCCUPANCY",
          route,
          currentNodeOpt.orElse(null),
          nextNode.orElse(null),
          graph,
          acquired,
          authorizationRequest,
          authorityEnd);
      return;
    }
    decision = acquired;
    MovementAuthorizationToken token =
        issueMovementAuthorizationToken(
            trainName,
            currentNodeOpt.orElse(null),
            nextNode.orElse(null),
            authorizationRequest,
            decision.signal(),
            now);
    SignalAspect baseAspect = decision.signal();
    // 前方列车信号调整：扫描更远范围（4 edges）检测其他列车并降级信号
    MovementPlanSnapshot forwardScanPlan =
        advisoryContext
            .request()
            .movementPlanSnapshot()
            .or(() -> context.request().movementPlanSnapshot())
            .orElse(null);
    SignalAspect nextAspect =
        adjustSignalForForwardTrains(
            trainName,
            route,
            currentIndex,
            graph,
            forwardScanPlan,
            baseAspect,
            train.currentSpeedBlocksPerTick() * SPEED_TICKS_PER_SECOND,
            trainConfigResolver.resolve(properties, configManager.current()).decelBps2(),
            runtimeSettings);
    SignalAspect lastAspect = progressEntry.lastSignal();
    boolean stopAtNextWaypoint = false;
    if (nextNode.isPresent()) {
      Optional<RouteStop> nextStopOpt = routeDefinitions.findStop(route.id(), currentIndex + 1);
      if (nextStopOpt.isPresent()) {
        stopAtNextWaypoint = shouldStopAtWaypoint(nextNode.get(), nextStopOpt.get());
      }
    }
    if (stopAtNextWaypoint) {
      authorityEnd = authorityEnd.withReason(AuthorityEndReason.DWELL_OR_STATION_STOP);
    }
    AdvisoryPreviewResult advisoryPreview =
        previewAdvisoryLookaheadReadOnly(request, decision, authorizationRequest, decision);
    OccupancyDecision advisoryDecision = advisoryPreview.decision();
    if (currentNodeOpt.isEmpty() || nextNode.isEmpty()) {
      // 若已到终点且无下一目标，检查是否需要注册 Layover
      boolean allowLaunch = forceApply || lastAspect != nextAspect;
      if (allowLaunch) {
        updateSignalOrWarn(trainName, nextAspect, now);
      }
      if (nextNode.isEmpty() && forceApply && currentNodeOpt.isPresent()) {
        handleLayoverRegistrationIfNeeded(trainName, route, currentNodeOpt.get(), properties);
      }
      rollbackMovementAuthorization(
          trainName, token, authorizationRequest, HardStopReason.AUTHORIZATION_FAILURE);
      return;
    }
    ApproachControl approachControl =
        resolveApproachControl(graph, route, effectiveNodes, currentIndex, currentNodeOpt.get());
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
        OccupancyDecision blocked =
            new OccupancyDecision(
                false, now, SignalAspect.STOP, List.of(), false, "unreachable-failover");
        retainStopOccupancy(
            trainName,
            route,
            currentIndex,
            currentNodeForSignal,
            Optional.of(authorizationRequest),
            graph,
            now);
        rollbackMovementAuthorization(
            trainName, token, authorizationRequest, HardStopReason.UNREACHABLE_FAILOVER);
        applyHardStop(
            train,
            properties,
            trainName,
            HardStopReason.UNREACHABLE_FAILOVER,
            true,
            route,
            currentNodeOpt.get(),
            nextNode.get(),
            graph,
            blocked,
            authorizationRequest,
            authorityEnd);
        debugLogger.accept(
            "调度 failover: 目标不可达 train="
                + trainName
                + " from="
                + currentNodeOpt.get().value()
                + " to="
                + nextNode.get().value());
        return;
      }
    }
    // 信号前瞻：计算到前方所有限制点的距离，供速度曲线与移动授权共同使用。
    SignalLookahead.LookaheadResult lookahead = null;
    boolean needsLookahead =
        runtimeSettings.speedCurveEnabled() || runtimeSettings.movementAuthorityEnabled();
    if (needsLookahead) {
      if (runtimeSettings.speedCurveEnabled()) {
        UUID worldIdForLookahead = train.worldId();
        SignalLookahead.EdgeSpeedResolver edgeSpeedResolver =
            createEdgeSpeedResolver(worldIdForLookahead);
        lookahead =
            SignalLookahead.computeWithEdgeSpeed(
                advisoryDecision,
                advisoryContext,
                nextAspect,
                approachControl::activeFor,
                edgeSpeedResolver);
      } else {
        lookahead =
            SignalLookahead.compute(
                advisoryDecision, advisoryContext, nextAspect, approachControl::activeFor);
      }
      blockerDistanceOpt = lookahead.distanceToBlocker();
      constraintDistanceOpt = lookahead.minConstraintDistance();
    }
    nextAspect =
        stageSignalAspectForAuthorityAndAdvisory(
            trainName, nextAspect, decision, advisoryDecision, advisoryPreview.risks());
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
    OptionalDouble approachOverrideBps = OptionalDouble.empty();
    OptionalDouble movementAuthorityLimitBps = OptionalDouble.empty();
    SignalAspect aspectBeforeMovementAuthority = nextAspect;
    OptionalLong movementAuthorityDistance = OptionalLong.empty();
    double movementAuthorityStopMargin = runtimeSettings.movementAuthorityStopMarginBlocks();
    double movementAuthorityCautionMargin = runtimeSettings.movementAuthorityCautionMarginBlocks();
    double movementAuthorityCurrentSpeed = 0.0;
    String movementAuthorityDecisionReason = "none";
    boolean movementAuthorityStopApplied = false;
    if (nextAspect != SignalAspect.STOP && approachControl.limitBps().isPresent()) {
      approachOverrideBps = approachControl.limitBps();
    }
    if (runtimeSettings.movementAuthorityEnabled() && !stopAtNextWaypoint) {
      TrainConfig trainConfig = trainConfigResolver.resolve(properties, configManager.current());
      boolean authorityFromHardConstraint =
          constraintDistanceOpt != null && constraintDistanceOpt.isPresent();
      AuthorityEndReason authorityEndReason =
          authorityFromHardConstraint ? AuthorityEndReason.HARD_BLOCKER : authorityEnd.reason();
      OptionalLong authorityDistance =
          resolveMovementAuthorityDistance(
              constraintDistanceOpt,
              !authorityFromHardConstraint && authorityEnd.physical()
                  ? authorityEnd.distanceBlocks()
                  : OptionalLong.empty());
      movementAuthorityDistance = authorityDistance;
      String authoritySource =
          authorityFromHardConstraint
              ? "hard_constraint"
              : authorityEndReason.name().toLowerCase(Locale.ROOT);
      movementAuthorityCurrentSpeed = train.currentSpeedBlocksPerTick() * SPEED_TICKS_PER_SECOND;
      MovementAuthorityService.MovementAuthorityDecision authorityDecision =
          movementAuthorityService.evaluate(
              new MovementAuthorityService.MovementAuthorityInput(
                  nextAspect,
                  movementAuthorityCurrentSpeed,
                  trainConfig.decelBps2(),
                  authorityDistance,
                  movementAuthorityStopMargin,
                  movementAuthorityCautionMargin));
      SignalAspect authorityAspect = authorityDecision.effectiveAspect();
      movementAuthorityDecisionReason =
          movementAuthorityDecisionReason(authoritySource, nextAspect, authorityAspect);
      if (signalSeverity(authorityAspect) > signalSeverity(nextAspect)) {
        SignalComputationTrace.emit(
            withAuthorityTraceFields(
                    signalTrace(
                            trainName,
                            properties,
                            SignalComputationTrace.Source.MOVEMENT_AUTHORITY,
                            nextAspect,
                            authorityAspect,
                            "movement-authority-downgrade:" + authoritySource)
                        .field("authorityDistance", formatOptionalLong(authorityDistance))
                        .field("stopMargin", movementAuthorityStopMargin)
                        .field("cautionMargin", movementAuthorityCautionMargin)
                        .field("currentSpeed", movementAuthorityCurrentSpeed)
                        .field("authorityDecision", movementAuthorityDecisionReason)
                        .field("authorityReason", movementAuthorityDecisionReason)
                        .nodes(currentNodeOpt.orElse(null), nextNode.orElse(null))
                        .progress(
                            progressRegistry.version(),
                            currentIndex,
                            currentIndex,
                            lastPassedBeforeTick,
                            progressRegistry
                                .get(trainName)
                                .flatMap(
                                    RouteProgressRegistry.RouteProgressEntry::lastPassedGraphNode))
                        .request(authorizationRequest)
                        .decision(decision, authorizationRequest)
                        .distances(
                            blockerDistanceOpt,
                            lookahead == null
                                ? OptionalLong.empty()
                                : lookahead.distanceToCaution(),
                            lookahead == null
                                ? OptionalLong.empty()
                                : lookahead.distanceToApproach(),
                            authorityDecision.authorityDistanceBlocks(),
                            authorityEnd.resource(),
                            authorityEnd.authorizedEdgeCount()),
                    authorityEnd,
                    authorityEndReason)
                .field("reason", movementAuthorityDecisionReason));
        debugLogger.accept(
            "移动授权降级: train="
                + trainName
                + " idx="
                + currentIndex
                + " "
                + nextAspect
                + " -> "
                + authorityAspect
                + " source="
                + authoritySource
                + " authorityDistance="
                + formatOptionalLong(authorityDecision.authorityDistanceBlocks())
                + " stopMargin="
                + movementAuthorityStopMargin
                + " cautionMargin="
                + movementAuthorityCautionMargin
                + " currentSpeed="
                + movementAuthorityCurrentSpeed
                + " reason="
                + movementAuthorityDecisionReason);
        nextAspect = authorityAspect;
        movementAuthorityStopApplied = authorityAspect == SignalAspect.STOP;
      }
      movementAuthorityLimitBps = authorityDecision.recommendedMaxSpeedBps();
      if (authorityDecision.authorityDistanceBlocks().isPresent()
          && runtimeSettings.speedCurveEnabled()) {
        distanceOpt = minOptionalLong(distanceOpt, authorityDecision.authorityDistanceBlocks());
      }
    }
    traceAuthorityWindowSplit(
        trainName, authorizationRequest, request, decision, advisoryDecision, lookahead);
    SmartSignalDecisionResult smartDecision =
        applySmartForwardSignalDecision(
            trainName,
            train,
            properties,
            authorizationRequest,
            advisoryDecision,
            lookahead,
            authorityEnd,
            nextAspect,
            distanceOpt,
            movementAuthorityLimitBps,
            stopAtNextWaypoint);
    nextAspect = smartDecision.aspect();
    movementAuthorityLimitBps = smartDecision.movementAuthorityLimitBps();
    distanceOpt = smartDecision.distanceOpt();
    if (nextAspect == SignalAspect.STOP
        && !stopAtNextWaypoint
        && shouldSuppressRecoverableHoldAsAdvisoryOnly(
            smartDecision,
            decision,
            aspectBeforeMovementAuthority,
            advisoryDecision,
            movementAuthorityStopApplied)) {
      SignalAspect retainedAspect =
          recoverableHoldAdvisoryAspect(aspectBeforeMovementAuthority, advisoryDecision);
      debugLogger.accept(
          "SMART_DISPATCH_RECOVERABLE_HOLD_OBSERVED_ONLY train="
              + trainName
              + " action="
              + smartDecision.action()
              + " riskSource="
              + smartDecision.riskSource()
              + " stopReason="
              + smartDecision.stopReason()
              + " authorityDistance="
              + formatOptionalLong(movementAuthorityDistance)
              + " stopMargin="
              + movementAuthorityStopMargin
              + " cautionMargin="
              + movementAuthorityCautionMargin
              + " currentSpeed="
              + movementAuthorityCurrentSpeed);
      debugLogger.accept(
          "SMART_DISPATCH_RECOVERABLE_HOLD_SUPPRESSED train="
              + trainName
              + " result=ADVISORY_ONLY"
              + " resultAspect="
              + retainedAspect
              + " reason=recoverable-hold-advisory-only");
      nextAspect = retainedAspect;
    }
    if (nextAspect == SignalAspect.STOP && !stopAtNextWaypoint) {
      if (shouldInvalidateForAuthorityFailure(authorityEnd, smartDecision)) {
        debugLogger.accept(
            "SMART_AUTHORITY_WINDOW_EXCEEDED_REAL train="
                + trainName
                + " destination="
                + nextNode.get().value()
                + " authorityEnd="
                + authorityEnd.resource()
                + " currentNode="
                + currentNodeOpt.get().value()
                + " action="
                + smartDecision.action()
                + " riskSource="
                + smartDecision.riskSource()
                + " stopReason="
                + smartDecision.stopReason());
        traceAdmissionAuthorityConsistency(
            trainName,
            "PERIODIC_TICK",
            singleSafety,
            previousTickAspect,
            nextAspect,
            false,
            tokenState(trainName, token),
            destinationPresent(trainName),
            currentDestination(trainName),
            authorityEnd,
            currentNodeOpt.get(),
            nextNode.get(),
            "false",
            "authority_window_exceeded",
            nextAspect,
            currentPhysicalAspect(trainName, previousTickAspect),
            singleSafety.applies() && singleSafety.allowed());
        SignalComputationTrace.emit(
            signalTrace(
                    trainName,
                    properties,
                    SignalComputationTrace.Source.PERIODIC_TICK,
                    previousTickAspect,
                    nextAspect,
                    "signal-authority-window-exceeded")
                .field("smartDispatchAction", smartDecision.action())
                .field("riskSource", smartDecision.riskSource())
                .field("invalidatingStop", true)
                .field("stopReason", smartDecision.stopReason())
                .nodes(currentNodeOpt.get(), nextNode.get())
                .progress(
                    progressRegistry.version(),
                    currentIndex,
                    currentIndex,
                    lastPassedBeforeTick,
                    progressRegistry
                        .get(trainName)
                        .flatMap(RouteProgressRegistry.RouteProgressEntry::lastPassedGraphNode))
                .request(authorizationRequest)
                .decision(decision, authorizationRequest)
                .distances(
                    blockerDistanceOpt,
                    lookahead == null ? OptionalLong.empty() : lookahead.distanceToCaution(),
                    lookahead == null ? OptionalLong.empty() : lookahead.distanceToApproach(),
                    authorityEnd.distanceBlocks(),
                    authorityEnd.resource(),
                    authorityEnd.authorizedEdgeCount()));
        rollbackMovementAuthorization(
            trainName, token, authorizationRequest, HardStopReason.AUTHORITY_WINDOW_EXCEEDED);
        applyHardStop(
            train,
            properties,
            trainName,
            HardStopReason.AUTHORITY_WINDOW_EXCEEDED,
            true,
            route,
            currentNodeOpt.get(),
            nextNode.get(),
            graph,
            decision,
            authorizationRequest,
            authorityEnd);
        traceSmartSignalFinalDecision(
            trainName,
            "PERIODIC_TICK",
            singleSafety,
            smartDecision,
            nextAspect,
            SignalComputationTrace.TokenState.INVALID,
            false,
            authorityEnd,
            "false",
            "authority_window_exceeded",
            false,
            true,
            true,
            true,
            true);
        return;
      }
      String retainedDestination = readDestination(properties);
      String holdReason =
          smartDecision.isRecoverableHold()
              ? smartDecision.stopReason()
              : "non-physical-authority-stop";
      debugLogger.accept(
          "SMART_DISPATCH_RECOVERABLE_HOLD_CONVERTED_TO_STOP train="
              + trainName
              + " action="
              + smartDecision.action()
              + " riskSource="
              + smartDecision.riskSource()
              + " authorityDecision="
              + movementAuthorityDecisionReason
              + " stopReason="
              + holdReason);
      debugLogger.accept(
          "SMART_DISPATCH_RECOVERABLE_HOLD train="
              + trainName
              + " action="
              + smartDecision.action()
              + " riskSource="
              + smartDecision.riskSource()
              + " authorityEndReason="
              + authorityEnd.reason()
              + " authorityEnd="
              + authorityEnd.resource()
              + " stopReason="
              + holdReason
              + " destinationPresent="
              + !retainedDestination.isBlank()
              + " replanTriggered=true");
      traceAdmissionAuthorityConsistency(
          trainName,
          "PERIODIC_TICK",
          singleSafety,
          previousTickAspect,
          nextAspect,
          false,
          tokenState(trainName, token),
          !retainedDestination.isBlank(),
          retainedDestination.isBlank() ? "-" : retainedDestination,
          authorityEnd,
          currentNodeOpt.get(),
          nextNode.get(),
          "recoverable",
          "recoverable_hold:" + holdReason,
          nextAspect,
          currentPhysicalAspect(trainName, previousTickAspect),
          singleSafety.applies() && singleSafety.allowed());
      SignalComputationTrace.emit(
          signalTrace(
                  trainName,
                  properties,
                  SignalComputationTrace.Source.PERIODIC_TICK,
                  previousTickAspect,
                  nextAspect,
                  "smart-dispatch-recoverable-hold")
              .field("smartDispatchAction", smartDecision.action())
              .field("riskSource", smartDecision.riskSource())
              .field("invalidatingStop", false)
              .field("stopReason", holdReason)
              .field("tokenInvalidReason", "-")
              .field("destinationClearReason", "-")
              .field("replanTriggered", true)
              .nodes(currentNodeOpt.get(), nextNode.get())
              .progress(
                  progressRegistry.version(),
                  currentIndex,
                  currentIndex,
                  lastPassedBeforeTick,
                  progressRegistry
                      .get(trainName)
                      .flatMap(RouteProgressRegistry.RouteProgressEntry::lastPassedGraphNode))
              .request(authorizationRequest)
              .decision(advisoryDecision, authorizationRequest)
              .distances(
                  blockerDistanceOpt,
                  lookahead == null ? OptionalLong.empty() : lookahead.distanceToCaution(),
                  lookahead == null ? OptionalLong.empty() : lookahead.distanceToApproach(),
                  authorityEnd.distanceBlocks(),
                  authorityEnd.resource(),
                  authorityEnd.authorizedEdgeCount()));
      markRecoverableHoldForRetry(trainName);
      retainRecoverableMovementDestination(trainName, token, retainedDestination);
      rollbackMovementAuthorizationWithoutInhibitor(
          trainName,
          token,
          authorizationRequest,
          smartDecision,
          priorityResolution,
          retainedDestination);
      traceSmartSignalFinalDecision(
          trainName,
          "PERIODIC_TICK",
          singleSafety,
          smartDecision,
          nextAspect,
          tokenState(trainName, token),
          !retainedDestination.isBlank(),
          authorityEnd,
          "recoverable",
          "recoverable_hold:" + holdReason,
          true,
          false,
          false,
          false,
          true);
      applyNonInvalidatingBlockedStop(
          train,
          properties,
          trainName,
          "SMART_DISPATCH_RECOVERABLE_HOLD",
          route,
          currentNodeOpt.get(),
          nextNode.get(),
          graph,
          advisoryDecision != null ? advisoryDecision : decision,
          authorizationRequest,
          authorityEnd);
      return;
    }

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
              + true
              + " rawAllowed="
              + decision.allowed()
              + " blockers="
              + decision.blockers().size());
    }
    retainRearGuardOccupancyBestEffort(
        trainName,
        route,
        currentIndex,
        effectiveNodes,
        authorizationRequest.movementPlanSnapshot(),
        graph,
        runtimeSettings,
        now);
    authorizationRequest =
        markDirectedRequest(authorizationRequest, SignalComputationTrace.Source.PERIODIC_TICK);
    boolean allowLaunch =
        forceApply
            || lastAspect != nextAspect
            || shouldRelaunchStationaryProceedTrain(train, trainName, nextAspect);
    Optional<String> committedDestination = Optional.empty();
    if (nextAspect != SignalAspect.STOP && nextNode.isPresent()) {
      Optional<NodeId> authorityBoundaryNode =
          authorityBoundaryNode(authorizationRequest, authorityEnd);
      if (authorityBoundaryNode.isPresent()
          && !authorityBoundaryNode.get().equals(nextNode.get())) {
        debugLogger.accept(
            "SMART_AUTHORITY_WINDOW_DESTINATION_MISMATCH train="
                + trainName
                + " destination="
                + nextNode.get().value()
                + " authorityEnd="
                + authorityBoundaryNode.get().value()
                + " action=segment-authority");
      }
      debugLogger.accept(
          "SMART_AUTHORITY_WINDOW_VALID train="
              + trainName
              + " destination="
              + nextNode.get().value()
              + " authorityEnd="
              + authorityEnd.resource());
      committedDestination =
          commitAuthorizedDestination(
              properties, trainName, route, currentIndex + 1, nextNode.get());
      if (committedDestination.isEmpty()
          || !activateMovementAuthorizationTokenRetainingInhibitor(
              trainName, token, committedDestination.get())) {
        rollbackMovementAuthorization(
            trainName, token, authorizationRequest, HardStopReason.AUTHORIZATION_FAILURE);
        OccupancyDecision blocked =
            new OccupancyDecision(
                false, now, SignalAspect.STOP, List.of(), false, "destination-commit-failed");
        applyHardStop(
            train,
            properties,
            trainName,
            HardStopReason.AUTHORIZATION_FAILURE,
            true,
            route,
            currentNodeOpt.orElse(null),
            nextNode.orElse(null),
            graph,
            blocked,
            authorizationRequest,
            authorityEnd);
        return;
      }
    }
    if (nextAspect != SignalAspect.STOP
        && !hasActiveMovementAuthorization(
            trainName,
            currentNodeOpt.orElse(null),
            nextNode.orElse(null),
            authorizationRequest.resourceList(),
            true)) {
      OccupancyDecision blocked =
          new OccupancyDecision(
              false, now, SignalAspect.STOP, List.of(), false, "movement-token-missing");
      applyHardStop(
          train,
          properties,
          trainName,
          HardStopReason.AUTHORIZATION_FAILURE,
          true,
          route,
          currentNodeOpt.orElse(null),
          nextNode.orElse(null),
          graph,
          blocked,
          authorizationRequest,
          authorityEnd);
      return;
    }
    SignalPublicationGate.Decision publication =
        evaluatePublicationGate(
            trainName,
            authorizationRequest,
            decision,
            nextAspect,
            false,
            SignalComputationTrace.TokenState.ACTIVE);
    FinalSignalAuthorization finalAuthorization =
        new FinalSignalAuthorization(
            "PERIODIC_TICK",
            authorizationRequest,
            decision,
            publication,
            currentNodeOpt.orElse(null),
            nextNode.orElse(null),
            SignalComputationTrace.TokenState.ACTIVE,
            committedDestination.isPresent(),
            false);
    if (publication.blocked()) {
      if (publication.localOnlyStop()) {
        SignalComputationTrace.emit(
            withDrainGateTraceFields(
                withAuthorityTraceFields(
                    signalTrace(
                            trainName,
                            properties,
                            SignalComputationTrace.Source.PERIODIC_TICK,
                            previousTickAspect,
                            SignalAspect.STOP,
                            "signal-publication-gate-local-stop:" + publication.reason())
                        .field("candidateAspect", publication.candidateAspect())
                        .field("publicationGateAspect", publication.visibleAspect())
                        .field("publicationGateReason", publication.reason())
                        .field("signalDecisionInputType", publication.inputType())
                        .nodes(currentNodeOpt.get(), nextNode.get())
                        .progress(
                            progressRegistry.version(),
                            currentIndex,
                            currentIndex,
                            lastPassedBeforeTick,
                            progressRegistry
                                .get(trainName)
                                .flatMap(
                                    RouteProgressRegistry.RouteProgressEntry::lastPassedGraphNode))
                        .request(authorizationRequest)
                        .decision(decision, authorizationRequest)
                        .distances(
                            blockerDistanceOpt,
                            lookahead == null
                                ? OptionalLong.empty()
                                : lookahead.distanceToCaution(),
                            lookahead == null
                                ? OptionalLong.empty()
                                : lookahead.distanceToApproach(),
                            authorityEnd.distanceBlocks(),
                            authorityEnd.resource(),
                            authorityEnd.authorizedEdgeCount()),
                    authorityEnd),
                authorizationRequest,
                decision,
                publication,
                "drain-local-only",
                "-",
                "-"));
        boolean localStopPublished = updateSignalOrWarn(trainName, SignalAspect.STOP, now);
        SmartUnlockReservation activeUnlock =
            smartUnlockReservationsByTrain.get(normalizeTrainKey(trainName));
        if (activeUnlock != null) {
          traceSmartUnlockPlanApply(
              activeUnlock,
              "published",
              localStopPublished ? "physical-stop-published" : "physical-stop-not-published",
              publication.candidateAspect(),
              SignalAspect.STOP,
              SignalAspect.STOP,
              localStopPublished,
              false,
              "publication-gate-local-stop:" + publication.reason(),
              false,
              "unknown",
              "unknown",
              "unknown",
              tokenState(trainName, movementToken(trainName).orElse(null)),
              "publication-gate-local-stop:" + publication.reason());
        }
        TrainConfig localStopConfig =
            trainConfigResolver.resolve(properties, configManager.current());
        runtimeTrainController.applyControl(
            train,
            properties,
            SignalAspect.STOP,
            0.0,
            localStopConfig,
            false,
            OptionalLong.empty(),
            java.util.Optional.empty(),
            configManager.current().runtimeSettings());
        return;
      }
      rollbackMovementAuthorization(
          trainName, token, authorizationRequest, HardStopReason.AUTHORIZATION_FAILURE);
      OccupancyDecision blocked =
          new OccupancyDecision(
              false,
              now,
              SignalAspect.STOP,
              decision.blockers(),
              false,
              "publication-gate:" + publication.reason());
      SignalComputationTrace.emit(
          withDrainGateTraceFields(
              withAuthorityTraceFields(
                  signalTrace(
                          trainName,
                          properties,
                          SignalComputationTrace.Source.PERIODIC_TICK,
                          previousTickAspect,
                          SignalAspect.STOP,
                          "signal-publication-gate-blocked:" + publication.reason())
                      .field("candidateAspect", publication.candidateAspect())
                      .field("publicationGateAspect", publication.visibleAspect())
                      .field("publicationGateReason", publication.reason())
                      .field("signalDecisionInputType", publication.inputType())
                      .nodes(currentNodeOpt.get(), nextNode.get())
                      .progress(
                          progressRegistry.version(),
                          currentIndex,
                          currentIndex,
                          lastPassedBeforeTick,
                          progressRegistry
                              .get(trainName)
                              .flatMap(
                                  RouteProgressRegistry.RouteProgressEntry::lastPassedGraphNode))
                      .request(authorizationRequest)
                      .decision(blocked, authorizationRequest)
                      .distances(
                          blockerDistanceOpt,
                          lookahead == null ? OptionalLong.empty() : lookahead.distanceToCaution(),
                          lookahead == null ? OptionalLong.empty() : lookahead.distanceToApproach(),
                          authorityEnd.distanceBlocks(),
                          authorityEnd.resource(),
                          authorityEnd.authorizedEdgeCount()),
                  authorityEnd),
              authorizationRequest,
              blocked,
              publication,
              "publication-gate:" + publication.reason(),
              "hard-stop:authorization_failure",
              "rollback:authorization_failure"));
      applyHardStop(
          train,
          properties,
          trainName,
          HardStopReason.AUTHORIZATION_FAILURE,
          true,
          route,
          currentNodeOpt.orElse(null),
          nextNode.orElse(null),
          graph,
          blocked,
          authorizationRequest,
          authorityEnd);
      return;
    }
    nextAspect = publication.visibleAspect();
    FinalSignalValidation finalValidation =
        validateFinalSignalAuthorization(trainName, nextAspect, finalAuthorization, true);
    if (!finalValidation.allowed()) {
      rollbackMovementAuthorization(
          trainName, token, authorizationRequest, HardStopReason.AUTHORIZATION_FAILURE);
      OccupancyDecision blocked =
          new OccupancyDecision(
              false,
              now,
              SignalAspect.STOP,
              decision.blockers(),
              false,
              "final-authorization:" + finalValidation.reason());
      traceStructuredSignalFinalDecision(
          trainName,
          finalAuthorization,
          SignalAspect.STOP,
          false,
          false,
          "final-authorization:" + finalValidation.reason());
      applyHardStop(
          train,
          properties,
          trainName,
          HardStopReason.AUTHORIZATION_FAILURE,
          true,
          route,
          currentNodeOpt.orElse(null),
          nextNode.orElse(null),
          graph,
          blocked,
          authorizationRequest,
          authorityEnd);
      return;
    }
    if (SignalDecisionInputClassifier.isProceedLike(nextAspect)
        && !clearMovementInhibitorAfterFinalAuthorization(
            trainName, finalAuthorization, nextAspect)) {
      rollbackMovementAuthorization(
          trainName, token, authorizationRequest, HardStopReason.AUTHORIZATION_FAILURE);
      OccupancyDecision blocked =
          new OccupancyDecision(
              false, now, SignalAspect.STOP, List.of(), false, "movement-inhibitor-clear-failed");
      applyHardStop(
          train,
          properties,
          trainName,
          HardStopReason.AUTHORIZATION_FAILURE,
          true,
          route,
          currentNodeOpt.orElse(null),
          nextNode.orElse(null),
          graph,
          blocked,
          authorizationRequest,
          authorityEnd);
      return;
    }
    PhysicalSignalPublication physicalPublication =
        publishPhysicalSignalIfRequired(trainName, nextAspect, lastAspect, now, finalAuthorization);
    SmartUnlockReservation activeUnlock =
        smartUnlockReservationsByTrain.get(normalizeTrainKey(trainName));
    if (activeUnlock != null) {
      boolean proceedLike = SignalDecisionInputClassifier.isProceedLike(nextAspect);
      traceSmartUnlockPlanApply(
          activeUnlock,
          "published",
          proceedLike ? "physical-proceed-published" : "physical-stop-published",
          publication.candidateAspect(),
          publication.visibleAspect(),
          nextAspect,
          physicalPublication.updated(),
          !physicalPublication.updateRequired(),
          publication.reason() + ":" + physicalPublication.skippedReason(),
          false,
          "unknown",
          "unknown",
          "unknown",
          tokenState(trainName, movementToken(trainName).orElse(null)),
          "-");
    }
    long finalTick = currentSignalTraceTick();
    String finalRequestId = requestIdOf(authorizationRequest);
    traceSmartSignalFinal(
        trainName,
        "PERIODIC_TICK",
        publication.candidateAspect(),
        publication.visibleAspect(),
        nextAspect,
        physicalPublication.updated(),
        !physicalPublication.updateRequired(),
        physicalPublication.before(),
        publication.reason() + ":" + physicalPublication.skippedReason(),
        tokenState(trainName, movementToken(trainName).orElse(null)),
        destinationPresent(trainName),
        "true",
        "-",
        DispatchEffectClass.SIGNAL_CONSTRAINT,
        finalTick,
        finalRequestId);
    traceSignalAuthorityLifecycle(
        "periodic-signal-final",
        trainName,
        route,
        currentIndex,
        currentNodeOpt.orElse(null),
        nextNode,
        authorizationRequest,
        priorityResolution,
        String.valueOf(publication.visibleAspect()),
        String.valueOf(nextAspect),
        String.valueOf(!physicalPublication.updateRequired()));
    traceAdmissionAuthorityConsistency(
        trainName,
        "PERIODIC_TICK",
        singleSafety,
        previousTickAspect,
        nextAspect,
        physicalPublication.updated(),
        tokenState(trainName, movementToken(trainName).orElse(null)),
        destinationPresent(trainName),
        currentDestination(trainName),
        authorityEnd,
        currentNodeOpt.orElse(null),
        nextNode.orElse(null),
        "true",
        "-",
        nextAspect,
        physicalPublication.after(),
        false);
    traceStructuredSignalFinalDecision(
        trainName,
        finalAuthorization,
        nextAspect,
        physicalPublication.updated(),
        physicalPublication.updated(),
        publication.reason() + ":" + physicalPublication.skippedReason());
    SignalComputationTrace.emit(
        withDrainGateTraceFields(
            withAuthorityTraceFields(
                withStopFields(
                        signalTrace(
                                trainName,
                                properties,
                                SignalComputationTrace.Source.PERIODIC_TICK,
                                previousTickAspect,
                                nextAspect,
                                "signal-final")
                            .field("candidateAspect", publication.candidateAspect())
                            .field("publicationGateAspect", publication.visibleAspect())
                            .field("finalPublishedAspect", nextAspect)
                            .field("publicationAuthority", "RUNTIME_PHYSICAL")
                            .field("physicalPublished", physicalPublication.updated())
                            .field("finalAspect", nextAspect)
                            .field("updateReason", physicalPublication.skippedReason())
                            .field("dispatchCycleId", finalTick)
                            .field("currentPhysicalAspectBefore", physicalPublication.before())
                            .field("currentPhysicalAspectAfter", physicalPublication.after())
                            .field("physicalUpdateRequired", physicalPublication.updateRequired())
                            .field("physicalUpdateApplied", physicalPublication.updated())
                            .field(
                                "physicalUpdateSkippedReason", physicalPublication.skippedReason())
                            .field("publicationGateReason", publication.reason())
                            .field("signalDecisionInputType", publication.inputType()),
                        stopOpt,
                        dwellRegistry != null
                            && dwellRegistry.remainingSeconds(trainName).isPresent(),
                        stopAtNextWaypoint)
                    .nodes(currentNodeOpt.get(), nextNode.get())
                    .progress(
                        progressRegistry.version(),
                        currentIndex,
                        currentIndex,
                        lastPassedBeforeTick,
                        progressRegistry
                            .get(trainName)
                            .flatMap(RouteProgressRegistry.RouteProgressEntry::lastPassedGraphNode))
                    .request(authorizationRequest)
                    .decision(decision, authorizationRequest)
                    .distances(
                        blockerDistanceOpt,
                        lookahead == null ? OptionalLong.empty() : lookahead.distanceToCaution(),
                        lookahead == null ? OptionalLong.empty() : lookahead.distanceToApproach(),
                        authorityEnd.distanceBlocks(),
                        authorityEnd.resource(),
                        authorityEnd.authorizedEdgeCount()),
                authorityEnd),
            authorizationRequest,
            decision,
            publication,
            "-",
            "-",
            "-"));

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
              + (approachOverrideBps.isPresent() ? approachOverrideBps.getAsDouble() : "-")
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
            ? resolveStopControlDistance(
                train, graph, currentNodeOpt.get(), nextNode.get(), distanceOpt)
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
        new ControlSpeedOverrides(
            approachOverrideBps,
            movementAuthorityLimitBps,
            approachControl,
            authorityEnd,
            resolveBlockedDestinationDiagnostic(properties, nextAspect, true, decision),
            new ControlDebugResources(
                summarizeClaims(
                    decision.blockers(),
                    trainName,
                    route,
                    currentIndex,
                    Set.copyOf(authorizationRequest.resourceList())),
                summarizeResources(authorizationRequest.resourceList(), 12),
                summarizeTrainClaims(
                    trainName,
                    route,
                    currentIndex,
                    Set.copyOf(authorizationRequest.resourceList()),
                    12))));
    if (stallDecision.triggerFailover()) {
      triggerStallFailover(
          train,
          properties,
          trainName,
          nextAspect,
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
   * @return 折返事务结果；调用方必须使用其中的 committed trainName 更新生命周期标签
   */
  public LayoverDispatchResult dispatchLayover(LayoverCandidate candidate, ServiceTicket ticket) {
    if (candidate == null || ticket == null) {
      return LayoverDispatchResult.failed("missing-candidate-or-ticket");
    }
    String trainName = candidate.trainName();
    TrainProperties properties = TrainPropertiesStore.get(trainName);
    if (properties == null || properties.getHolder() == null) {
      debugLogger.accept("Layover 发车失败: 列车未找到 " + trainName);
      layoverRegistry.unregister(trainName);
      return LayoverDispatchResult.failed(trainName, "train-not-found");
    }
    RuntimeTrainHandle trainHandle = new TrainCartsRuntimeHandle(properties.getHolder());
    return dispatchLayover(candidate, ticket, properties, trainHandle);
  }

  /**
   * 使用已经解析的属性与运行时句柄执行 Layover 折返。
   *
   * <p>TrainCarts 查找与 group 适配留在公开入口；折返事务本身只依赖运行时句柄，便于验证 DYNAMIC 容量、原子 handoff 与提交失败边界。
   */
  LayoverDispatchResult dispatchLayover(
      LayoverCandidate candidate,
      ServiceTicket ticket,
      TrainProperties properties,
      RuntimeTrainHandle trainHandle) {
    if (candidate == null || ticket == null || properties == null || trainHandle == null) {
      return LayoverDispatchResult.failed("missing-dispatch-context");
    }
    String trainName = candidate.trainName();
    if (!trainHandle.isValid()) {
      debugLogger.accept("Layover 发车失败: 列车无效 " + trainName);
      layoverRegistry.unregister(trainName);
      return LayoverDispatchResult.failed(trainName, "train-invalid");
    }
    Instant now = Instant.now();
    Optional<String> readinessBlocker = layoverReadinessBlocker(candidate, trainHandle, now);
    if (readinessBlocker.isPresent()) {
      debugLogger.accept("Layover 发车等待: train=" + trainName + " reason=" + readinessBlocker.get());
      return LayoverDispatchResult.failed(trainName, readinessBlocker.get());
    }
    // 新任务开始前清理旧的“有效节点覆盖”，避免跨线路遗留导致寻路/占用异常。
    effectiveNodeOverrides.remove(normalizeTrainKey(trainName));
    blockerSnapshots.remove(normalizeTrainKey(trainName));

    Optional<RouteDefinition> routeOpt = routeDefinitions.findById(ticket.routeId());
    if (routeOpt.isEmpty()) {
      debugLogger.accept("Layover 发车失败: Route 未找到 " + ticket.routeId());
      return LayoverDispatchResult.failed(trainName, "route-not-found");
    }
    RouteDefinition route = routeOpt.get();
    NodeId startNode = candidate.locationNodeId();
    List<RouteStop> stops = routeDefinitions.listStops(route.id());
    Optional<DestinationDisplayInfo> destInfoOpt = resolveEndOfOperationInfo(route);
    String regeneratedTrainName =
        regenerateTrainName(route, destInfoOpt.map(DestinationDisplayInfo::name).orElse(null));

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
      return LayoverDispatchResult.failed(trainName, "terminal-mismatch");
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
      return LayoverDispatchResult.failed(trainName, "route-has-no-next-node");
    }
    observePhysicalNodeForSpawnOrigin(properties, startNode, startIndex);
    recordEffectiveNode(trainName, route, startIndex, startNode);
    pruneDynamicResolutionState(trainName, route, startIndex);

    Optional<RailGraph> graphOpt = resolveGraph(trainHandle.worldId(), now);
    if (graphOpt.isEmpty()) {
      debugLogger.accept("Layover 发车失败: 图快照缺失 train=" + trainName);
      return LayoverDispatchResult.failed(trainName, "graph-missing");
    }
    RailGraph graph = graphOpt.get();
    ConfigManager.RuntimeSettings runtime = configManager.current().runtimeSettings();
    OccupancyRequestBuilder builder =
        runtimeLookaheadBuilder(graph, runtime, runtime.rearGuardEdges());
    List<NodeId> effectiveNodes = resolveEffectiveWaypoints(trainName, route);
    int nextIndex = startIndex + 1;
    DynamicResolution<DynamicSelection> dynamicSelection =
        selectDynamicStationTargetForProgress(
            trainName,
            route,
            startIndex,
            nextIndex,
            startNode,
            graph,
            builder,
            now,
            ticket.priority(),
            AuthorizationPurpose.LAYOVER_REUSE);
    OccupancyRequestContext ctx;
    if (dynamicSelection.isBlocked()) {
      withdrawForwardQueuePositions(trainName);
      debugLogger.accept(
          "Layover 发车等待: train="
              + trainName
              + " reason=dynamic-target-unavailable:"
              + dynamicSelection.reason());
      return LayoverDispatchResult.failed(trainName, "dynamic-target-unavailable");
    }
    if (dynamicSelection.isSelected()) {
      DynamicSelection selection = dynamicSelection.selected().orElseThrow();
      recordEffectiveNode(trainName, route, nextIndex, selection.targetNode());
      effectiveNodes = resolveEffectiveWaypoints(trainName, route);
      ctx = selection.context();
    } else {
      Optional<OccupancyRequestContext> ctxOpt =
          buildContextWithinDynamicBoundary(
              builder,
              trainName,
              route,
              effectiveNodes,
              effectiveNodes,
              startIndex,
              now,
              ticket.priority(),
              AuthorizationPurpose.LAYOVER_REUSE);
      if (ctxOpt.isEmpty()) {
        debugLogger.accept("Layover 发车失败: 无法构建占用请求 train=" + trainName);
        return LayoverDispatchResult.failed(trainName, "occupancy-request-unresolvable");
      }
      ctx = ctxOpt.get();
    }
    OccupancyRequest request = ctx.request();
    Optional<LayoverRegistry.DispatchAttempt> dispatchAttempt =
        layoverRegistry.claimDispatch(
            trainName,
            ticket.ticketId(),
            regeneratedTrainName == null || regeneratedTrainName.isBlank()
                ? trainName
                : regeneratedTrainName);
    if (dispatchAttempt.isEmpty()) {
      debugLogger.accept(
          "Layover 发车失败: 候选已被其他票据认领 train=" + trainName + " ticket=" + ticket.ticketId());
      return LayoverDispatchResult.failed(trainName, "candidate-claimed-by-other-ticket");
    }
    regeneratedTrainName = dispatchAttempt.get().targetTrainName();
    Set<OccupancyResource> turnbackFootprintBeforeHandoff =
        snapshotTurnbackFootprintResources(trainName);
    LaunchAuthorizationService.AuthorizationResult authorization =
        launchAuthorizationService.authorizeHandoff(
            new LaunchAuthorizationService.AuthorizationPlan(
                request,
                "layover-turnback",
                false,
                true,
                false,
                true,
                LaunchAuthorizationService.LaunchActions.none()));
    if (!authorization.allowed()) {
      debugLogger.accept(
          "Layover 发车受阻: train="
              + trainName
              + " signal="
              + authorization.signal()
              + " blockers="
              + summarizeBlockers(authorization.effectiveDecision()));
      layoverRegistry.releaseDispatchAttempt(trainName, ticket.ticketId());
      return LayoverDispatchResult.failed(trainName, "authority-handoff-blocked");
    }

    registerTurnbackFootprintGuard(
        trainHandle,
        trainName,
        candidate.locationNodeId(),
        turnbackFootprintBeforeHandoff,
        resolveTurnbackForwardPath(graph, ctx, effectiveNodes, startIndex),
        configManager.current().runtimeSettings().rearGuardEdges());

    String previousTrainName = trainName;
    if (regeneratedTrainName != null && !regeneratedTrainName.equals(previousTrainName)) {
      if (!(occupancyManager instanceof AuthorityHandoffSupport handoffSupport)) {
        debugLogger.accept("Layover 发车失败: 占用实现不支持 owner 迁移 train=" + previousTrainName);
        holdLayoverAuthorityAfterFailedCommit(
            trainHandle, properties, previousTrainName, null, "owner-migration-support-missing");
        return LayoverDispatchResult.failed(previousTrainName, "owner-migration-support-missing");
      }
      if (!layoverRegistry.rename(previousTrainName, regeneratedTrainName)) {
        debugLogger.accept(
            "Layover 发车失败: 待命候选名称迁移被拒绝 train="
                + previousTrainName
                + " target="
                + regeneratedTrainName);
        holdLayoverAuthorityAfterFailedCommit(
            trainHandle, properties, previousTrainName, null, "layover-candidate-rename-rejected");
        return LayoverDispatchResult.failed(previousTrainName, "candidate-rename-rejected");
      }
      if (!migrateEffectiveNodeOverrides(previousTrainName, regeneratedTrainName)) {
        layoverRegistry.rename(regeneratedTrainName, previousTrainName);
        debugLogger.accept(
            "Layover 发车失败: 有效节点覆盖迁移被拒绝 train="
                + previousTrainName
                + " target="
                + regeneratedTrainName);
        holdLayoverAuthorityAfterFailedCommit(
            trainHandle, properties, previousTrainName, null, "effective-node-rename-rejected");
        return LayoverDispatchResult.failed(previousTrainName, "effective-node-rename-rejected");
      }
      if (!turnbackFootprintGuards.rename(previousTrainName, regeneratedTrainName)) {
        migrateEffectiveNodeOverrides(regeneratedTrainName, previousTrainName);
        layoverRegistry.rename(regeneratedTrainName, previousTrainName);
        debugLogger.accept(
            "Layover 发车失败: 折返防护迁移被拒绝 train="
                + previousTrainName
                + " target="
                + regeneratedTrainName);
        holdLayoverAuthorityAfterFailedCommit(
            trainHandle, properties, previousTrainName, null, "footprint-guard-rename-rejected");
        return LayoverDispatchResult.failed(previousTrainName, "footprint-guard-rename-rejected");
      }
      try {
        properties.setTrainName(regeneratedTrainName);
      } catch (RuntimeException exception) {
        turnbackFootprintGuards.rename(regeneratedTrainName, previousTrainName);
        migrateEffectiveNodeOverrides(regeneratedTrainName, previousTrainName);
        layoverRegistry.rename(regeneratedTrainName, previousTrainName);
        holdLayoverAuthorityAfterFailedCommit(
            trainHandle, properties, previousTrainName, null, "traincarts-rename-rejected");
        debugLogger.accept(
            "Layover 发车失败: TrainCarts 重命名异常 train="
                + previousTrainName
                + " target="
                + regeneratedTrainName
                + " error="
                + exception.getClass().getSimpleName());
        return LayoverDispatchResult.failed(previousTrainName, "traincarts-rename-rejected");
      }
      if (!handoffSupport.migrateAuthorityOwner(previousTrainName, regeneratedTrainName)) {
        turnbackFootprintGuards.rename(regeneratedTrainName, previousTrainName);
        migrateEffectiveNodeOverrides(regeneratedTrainName, previousTrainName);
        layoverRegistry.rename(regeneratedTrainName, previousTrainName);
        tryRestoreTrainCartsName(properties, regeneratedTrainName, previousTrainName);
        debugLogger.accept(
            "Layover 发车失败: owner 迁移被拒绝 train="
                + previousTrainName
                + " target="
                + regeneratedTrainName);
        holdLayoverAuthorityAfterFailedCommit(
            trainHandle, properties, previousTrainName, null, "authority-owner-rename-rejected");
        return LayoverDispatchResult.failed(previousTrainName, "authority-owner-rename-rejected");
      }
      trainName = regeneratedTrainName;
      request = request.withTrainName(trainName);
      debugLogger.accept("Layover 复用: trainName 更新为 " + trainName);
    }
    if (!(occupancyManager instanceof AuthorityHandoffSupport handoffSupport)
        || !handoffSupport.holdsHardAuthority(request)) {
      if (!previousTrainName.equals(trainName)
          && occupancyManager instanceof AuthorityHandoffSupport rollbackSupport
          && rollbackSupport.migrateAuthorityOwner(trainName, previousTrainName)) {
        turnbackFootprintGuards.rename(trainName, previousTrainName);
        migrateEffectiveNodeOverrides(trainName, previousTrainName);
        layoverRegistry.rename(trainName, previousTrainName);
        tryRestoreTrainCartsName(properties, trainName, previousTrainName);
        trainName = previousTrainName;
      }
      debugLogger.accept("Layover 发车失败: 原子交接后硬授权不完整 train=" + trainName);
      holdLayoverAuthorityAfterFailedCommit(
          trainHandle, properties, trainName, null, "hard-authority-verification-failed");
      return LayoverDispatchResult.failed(trainName, "hard-authority-verification-failed");
    }
    if (!previousTrainName.equals(trainName)) {
      progressRegistry.remove(previousTrainName);
    }

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
    destInfoOpt.ifPresent(
        dest -> {
          TrainTagHelper.writeTag(properties, "FTA_DEST_NAME", dest.name());
          TrainTagHelper.writeTag(properties, "FTA_DEST_CODE", dest.code());
        });
    // 折返 ticket 已经建立新的交路位置语义；即使折返点恰好与原 Depot 同名，也不得再恢复旧 spawn origin。
    TrainTagHelper.writeTag(properties, TrainSpawnTagInitializer.TAG_SPAWN_ORIGIN_PENDING, "false");
    TrainTagHelper.writeTag(properties, "FTA_TICKET_ID", ticket.ticketId());
    progressRegistry.advance(trainName, ticket.routeId(), route, startIndex, properties, now);
    invalidateTrainEta(trainName);
    request = markDirectedRequest(request, SignalComputationTrace.Source.DEPARTURE_GATE);

    NodeId nextNode = resolveEffectiveNode(trainName, route, startIndex + 1);
    NodeId currentNode = resolveEffectiveNode(trainName, route, startIndex);
    String destinationName = resolveDestinationName(nextNode);
    if (destinationName == null || destinationName.isBlank()) {
      destinationName = nextNode.value();
    }
    MovementAuthorizationToken token =
        issueMovementAuthorizationToken(
            trainName, currentNode, nextNode, request, SignalAspect.PROCEED, now);
    // 授权路径：Layover 复用已经通过 LaunchAuthorizationService.authorize 后才写 destination。
    properties.clearDestinationRoute();
    trainHandle.setDestination(destinationName);
    if (!activateMovementAuthorizationTokenRetainingInhibitor(trainName, token, destinationName)) {
      holdLayoverAuthorityAfterFailedCommit(
          trainHandle, properties, trainName, token, "movement-token-activation-rejected");
      return LayoverDispatchResult.failed(trainName, "movement-token-activation-rejected");
    }
    OccupancyDecision effectiveDecision = authorization.effectiveDecision();
    SignalPublicationGate.Decision publication =
        evaluatePublicationGate(
            trainName,
            request,
            effectiveDecision,
            SignalAspect.PROCEED,
            false,
            SignalComputationTrace.TokenState.ACTIVE);
    FinalSignalAuthorization finalAuthorization =
        new FinalSignalAuthorization(
            "DEPARTURE_GATE",
            request,
            effectiveDecision,
            publication,
            currentNode,
            nextNode,
            SignalComputationTrace.TokenState.ACTIVE,
            true,
            false);
    FinalSignalValidation validation =
        validateFinalSignalAuthorization(trainName, SignalAspect.PROCEED, finalAuthorization, true);
    if (!validation.allowed()) {
      holdLayoverAuthorityAfterFailedCommit(
          trainHandle, properties, trainName, token, "final-authorization:" + validation.reason());
      traceStructuredSignalFinalDecision(
          trainName,
          finalAuthorization,
          SignalAspect.STOP,
          false,
          false,
          "layover-final-authorization:" + validation.reason());
      return LayoverDispatchResult.failed(trainName, "final-authorization:" + validation.reason());
    }
    if (!clearMovementInhibitorAfterFinalAuthorization(
        trainName, finalAuthorization, SignalAspect.PROCEED)) {
      holdLayoverAuthorityAfterFailedCommit(
          trainHandle, properties, trainName, token, "movement-inhibitor-clear-rejected");
      return LayoverDispatchResult.failed(trainName, "movement-inhibitor-clear-rejected");
    }

    // 直接触发发车，不依赖 refreshSignal 的复杂逻辑
    // refreshSignal 可能因为图路径问题导致 failover 而不发车
    TrainConfig config = trainConfigResolver.resolve(properties, configManager.current());
    double targetBps = configManager.current().graphSettings().defaultSpeedBlocksPerSecond();
    java.util.Optional<org.bukkit.block.BlockFace> fallbackDirection =
        resolveLaunchDirectionByGraph(
            graph, resolveEffectiveNode(trainName, route, startIndex), nextNode);
    updateSignalOrWarn(trainName, SignalAspect.PROCEED, now, finalAuthorization);
    TrainLaunchManager.ControlApplicationResult controlResult =
        runtimeTrainController.applyControl(
            trainHandle,
            properties,
            SignalAspect.PROCEED,
            targetBps,
            config,
            true,
            OptionalLong.empty(),
            fallbackDirection,
            configManager.current().runtimeSettings());
    if (!controlResult.launchCommandAccepted()) {
      holdLayoverAuthorityAfterFailedCommit(
          trainHandle, properties, trainName, token, "traincarts-launch-action-rejected");
      traceStructuredSignalFinalDecision(
          trainName,
          finalAuthorization,
          SignalAspect.STOP,
          false,
          false,
          "layover-launch-action-rejected");
      return LayoverDispatchResult.failed(trainName, "traincarts-launch-action-rejected");
    }
    traceStructuredSignalFinalDecision(
        trainName, finalAuthorization, SignalAspect.PROCEED, true, true, "layover-final");
    layoverRegistry.unregister(trainName);
    dynamicAllocator.clearAllocations(previousTrainName);
    if (!TrainNameNormalizer.sameLogicalTrain(previousTrainName, trainName)) {
      dynamicAllocator.clearAllocations(trainName);
    }
    refreshSignalsForResources(request.resourceList(), trainName);

    debugLogger.accept("Layover 发车成功: train=" + trainName + " route=" + route.id().value());
    return LayoverDispatchResult.success(trainName);
  }

  /**
   * 以运行时真实停车状态校验折返候选，而不是只相信候选登记时推算的 {@code readyAt}。
   *
   * <p>Waypoint TERM 在列车触牌时登记候选，随后才执行居中并启动 dwell；因此 {@code readyAt} 可能比实际关停流程早。只要居中状态或 {@link
   * DwellRegistry} 仍然存在，就必须保持旧方向授权，不能提前占用折返后的站外咽喉/平交资源。AutoStation 在正常关门后可能仍持有 departure gate，故这里不以
   * gate 本身作为阻塞证据。
   */
  Optional<String> layoverReadinessBlocker(
      LayoverCandidate candidate, RuntimeTrainHandle train, Instant now) {
    if (candidate == null || train == null || now == null) {
      return Optional.of("readiness-state-missing");
    }
    if (candidate.readyAt().isAfter(now)) {
      return Optional.of("dwell-not-ready");
    }
    if (resolveWaypointStopState(candidate.trainName(), candidate.locationNodeId()).isPresent()) {
      return Optional.of("waypoint-centering-active");
    }
    if (dwellRegistry != null
        && dwellRegistry.remainingSeconds(candidate.trainName()).isPresent()) {
      return Optional.of("dwell-active");
    }
    if (train.isMoving()) {
      return Optional.of("train-still-moving");
    }
    return Optional.empty();
  }

  /**
   * 折返提交失败时保持新旧进路的保守占用，并撤销一切可运动状态。
   *
   * <p>此路径不能调用普通 {@link #rollbackMovementAuthorization}：折返交接已经原子替换了方向授权，若在 TrainCarts 接受 launch
   * action 前释放新请求的 hard authority，会让仍压在站内进路上的列车失去防护。候选与占用都保留，后续调度 tick
   * 可在同一事实状态上重试；只有列车推进/移除事件才能释放残余防护。
   */
  private void holdLayoverAuthorityAfterFailedCommit(
      RuntimeTrainHandle train,
      TrainProperties properties,
      String trainName,
      MovementAuthorizationToken token,
      String failureReason) {
    String key = normalizeTrainKey(trainName);
    if (!key.isEmpty() && token != null) {
      MovementAuthorizationToken current = movementAuthorizationTokens.get(key);
      if (current != null && current.claimVersion() == token.claimVersion()) {
        movementAuthorizationTokens.remove(key);
      }
    }
    invalidateMovementAuthorization(trainName, HardStopReason.AUTHORIZATION_FAILURE);
    properties.clearDestinationRoute();
    properties.clearDestination();
    runtimeTrainController.stopHard(train, properties);
    updateSignalOrWarn(trainName, SignalAspect.STOP, Instant.now());
    debugLogger.accept(
        "Layover 发车提交失败并保留进路: train="
            + trainName
            + " reason="
            + (failureReason == null || failureReason.isBlank() ? "unknown" : failureReason));
  }

  /** TrainCarts 拒绝回滚改名时保留占用事实并记录；不得让异常跳出折返事务后丢失硬停车。 */
  private boolean tryRestoreTrainCartsName(
      TrainProperties properties, String currentTrainName, String previousTrainName) {
    try {
      properties.setTrainName(previousTrainName);
      return true;
    } catch (RuntimeException exception) {
      debugLogger.accept(
          "Layover 改名回滚失败并保留旧 owner 授权: current="
              + currentTrainName
              + " previous="
              + previousTrainName
              + " error="
              + exception.getClass().getSimpleName());
      return false;
    }
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
   * 兼容旧测试与诊断入口的硬阻塞判定。
   *
   * <p>实际语义已迁移到 {@link LaunchAuthorizationService}，这里仅委托同一实现，避免运行时和统一出发授权服务产生两套 hard blocker 规则。
   */
  static boolean hasHardBlockersForTrain(String trainName, List<OccupancyClaim> blockers) {
    return LaunchAuthorizationService.hasHardBlockersForTrain(trainName, blockers);
  }

  /**
   * 统一计算“当前判定是否允许继续放行”。
   *
   * <p>会顺序执行：
   *
   * <ul>
   *   <li>刷新 blocker 快照（供 HealthMonitor 诊断）
   *   <li>检查 {@code allowed=true} 但未带 conflictRelease 标记的 NODE/EDGE 硬阻塞“误放行”场景
   *   <li>输出冲突区放行/抑制日志
   * </ul>
   *
   * <p>用于统一 departure/progress/signal/layover 四条路径的信号放行判定，避免各处分支各自复制同一套逻辑而逐步漂移。
   *
   * @param trainName 当前列车名
   * @param decision 占用判定结果
   * @param now 当前时间（用于 blocker 快照）
   * @param scope 日志作用域（如 departure/progress/signal）
   * @return 统一的放行结果
   */
  private ProceedDecision evaluateProceedDecision(
      String trainName, OccupancyDecision decision, Instant now, String scope) {
    LaunchAuthorizationService.AuthorizationResult result =
        launchAuthorizationService.evaluateDecision(trainName, decision, now, scope, false);
    return new ProceedDecision(result.allowed(), result.rawAllowed(), result.hardBlockerBypass());
  }

  private MovementAuthorizationCoordinator.ProceedEvaluation evaluateMovementAuthorization(
      String trainName, OccupancyDecision decision, Instant now, String scope) {
    ProceedDecision proceedDecision = evaluateProceedDecision(trainName, decision, now, scope);
    return new MovementAuthorizationCoordinator.ProceedEvaluation(
        proceedDecision.proceedAllowed(),
        proceedDecision.rawAllowed(),
        proceedDecision.hardBlockerBypass());
  }

  /**
   * 前方列车信号调整：扫描更远的前方路径，检测其他列车占用并调整信号。
   *
   * <p>信号规则（按累计距离）：
   *
   * <ul>
   *   <li>制动距离 + STOP 跟驰余量内有车 → STOP
   *   <li>制动距离 + CAUTION 跟驰余量内有车 → CAUTION
   *   <li>扫描范围内更远处有车 → PROCEED_WITH_CAUTION
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
      int currentIndex,
      RailGraph graph,
      MovementPlanSnapshot plan,
      SignalAspect baseAspect,
      double currentSpeedBps,
      double decelBps2,
      ConfigManager.RuntimeSettings runtimeSettings) {
    if (trainName == null
        || trainName.isBlank()
        || route == null
        || graph == null
        || occupancyManager == null
        || plan == null) {
      return baseAspect;
    }
    List<NodeId> scanNodes = plan.expandedPathNodes();
    List<DirectedTraversalContext.DirectedEdge> scanEdgesList = plan.directedEdges();
    if (currentIndex < 0 || scanNodes.isEmpty() || scanEdgesList.isEmpty()) {
      return baseAspect;
    }
    int edgeLimit = Math.min(FORWARD_TRAIN_SCAN_MAX_EDGES, scanEdgesList.size());
    long distanceBlocks = 0L;
    for (int edgeIndex = 0; edgeIndex < edgeLimit; edgeIndex++) {
      DirectedTraversalContext.DirectedEdge directedEdge = scanEdgesList.get(edgeIndex);
      distanceBlocks += forwardScanEdgeLength(graph, directedEdge);
      NodeId toNode = edgeIndex + 1 < scanNodes.size() ? scanNodes.get(edgeIndex + 1) : null;

      // 检查目标节点是否被其他列车占用
      if (toNode != null) {
        Optional<OccupancyClaim> nodeClaim =
            occupancyManager.getClaim(OccupancyResource.forNode(toNode));
        if (nodeClaim.isPresent()
            && !shouldIgnoreForwardScanClaim(trainName, route, currentIndex, nodeClaim.get())) {
          return distanceToForwardTrainSignal(
              distanceBlocks, currentSpeedBps, decelBps2, runtimeSettings);
        }
      }

      Optional<OccupancyClaim> edgeClaim =
          occupancyManager.getClaim(OccupancyResource.forEdge(directedEdge.edgeId()));
      if (edgeClaim.isPresent()
          && !shouldIgnoreForwardScanClaim(trainName, route, currentIndex, edgeClaim.get())) {
        return distanceToForwardTrainSignal(
            distanceBlocks, currentSpeedBps, decelBps2, runtimeSettings);
      }
    }

    return baseAspect;
  }

  /**
   * 展开前方扫描路径。
   *
   * <p>route waypoint 之间可能跨越多段实际图边，尤其是长单线。前方列车信号调整必须按展开后的图边计数，否则远处站点会被压缩成“一格前方”， 导致同向列车被过早降级到
   * STOP/CAUTION。
   */
  private Optional<ExpandedRoutePath> expandForwardScanPath(
      RailGraph graph, List<NodeId> waypoints, int currentIndex, int maxEdges) {
    if (graph == null
        || waypoints == null
        || currentIndex < 0
        || currentIndex >= waypoints.size() - 1
        || maxEdges <= 0) {
      return Optional.empty();
    }
    NodeId start = waypoints.get(currentIndex);
    if (start == null) {
      return Optional.empty();
    }
    List<NodeId> expandedNodes = new ArrayList<>();
    List<RailEdge> expandedEdges = new ArrayList<>();
    expandedNodes.add(start);
    for (int i = currentIndex; i + 1 < waypoints.size() && expandedEdges.size() < maxEdges; i++) {
      Optional<ExpandedRoutePath> segmentOpt = expandRoutePath(graph, waypoints, i, i + 1);
      if (segmentOpt.isEmpty()) {
        break;
      }
      ExpandedRoutePath segment = segmentOpt.get();
      List<NodeId> segmentNodes = segment.nodes();
      List<RailEdge> segmentEdges = segment.edges();
      for (int edgeIndex = 0;
          edgeIndex < segmentEdges.size() && expandedEdges.size() < maxEdges;
          edgeIndex++) {
        expandedEdges.add(segmentEdges.get(edgeIndex));
        if (edgeIndex + 1 < segmentNodes.size()) {
          expandedNodes.add(segmentNodes.get(edgeIndex + 1));
        }
      }
    }
    if (expandedEdges.isEmpty() || expandedNodes.size() < 2) {
      return Optional.empty();
    }
    return Optional.of(new ExpandedRoutePath(expandedNodes, expandedEdges));
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
    if (TrainNameNormalizer.sameLogicalTrain(claim.trainName(), currentTrainName)) {
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
   * 根据到前车的累计距离返回信号等级。
   *
   * <p>这里复用移动授权的制动距离思路，但使用跟驰专用 margin。edge count 只作为扫描上限，不再决定信号等级。
   */
  private SignalAspect distanceToForwardTrainSignal(
      long distanceBlocks,
      double currentSpeedBps,
      double decelBps2,
      ConfigManager.RuntimeSettings runtimeSettings) {
    double speed = Double.isFinite(currentSpeedBps) ? Math.max(0.0, currentSpeedBps) : 0.0;
    double decel = Double.isFinite(decelBps2) && decelBps2 > 0.0 ? decelBps2 : 0.001;
    int followingStopMargin =
        runtimeSettings == null ? 0 : Math.max(0, runtimeSettings.followingStopMarginBlocks());
    int followingClearMargin =
        runtimeSettings == null ? 0 : Math.max(0, runtimeSettings.followingMinClearBlocks());
    double authorityCautionMargin =
        runtimeSettings == null
            ? 0.0
            : Math.max(0.0, runtimeSettings.movementAuthorityCautionMarginBlocks());
    double brakingDistance = (speed * speed) / (2.0 * decel);
    double stopThreshold = brakingDistance + followingStopMargin;
    double cautionThreshold =
        brakingDistance
            + Math.max(followingStopMargin + authorityCautionMargin, followingClearMargin);
    if (distanceBlocks + 1.0e-6 <= stopThreshold) {
      return SignalAspect.STOP;
    } else if (distanceBlocks + 1.0e-6 <= cautionThreshold) {
      return SignalAspect.CAUTION;
    } else {
      return SignalAspect.PROCEED_WITH_CAUTION;
    }
  }

  private long forwardScanEdgeLength(
      RailGraph graph, DirectedTraversalContext.DirectedEdge directedEdge) {
    if (graph == null || directedEdge == null) {
      return 0L;
    }
    return findEdge(graph, directedEdge.fromNode(), directedEdge.toNode())
        .map(edge -> Math.max(0L, edge.lengthBlocks()))
        .orElse(0L);
  }

  /** 更新信号并在 entry 缺失时给出一次性告警，避免静默漂移。 */
  private boolean updateSignalOrWarn(String trainName, SignalAspect aspect, Instant now) {
    return updateSignalOrWarn(trainName, aspect, now, null);
  }

  private boolean updateSignalOrWarn(
      String trainName, SignalAspect aspect, Instant now, FinalSignalAuthorization authorization) {
    if (trainName == null || trainName.isBlank() || aspect == null) {
      return false;
    }
    FinalSignalValidation validation =
        validateFinalSignalAuthorization(trainName, aspect, authorization, false);
    if (!validation.allowed()) {
      debugLogger.accept(
          "DIRECT_SIGNAL_UPDATE_SUPPRESSED train="
              + trainName
              + " requestedAspect="
              + aspect
              + " reason="
              + validation.reason()
              + " hardBarrierPresent="
              + validation.hardBarrierPresent()
              + " hardBarrierReason="
              + validation.hardBarrierReason()
              + " snapshotStale="
              + validation.snapshotStale()
              + " wouldMutate=true didMutate=false");
      traceStructuredSignalFinalDecision(
          trainName,
          authorization,
          aspect,
          false,
          false,
          "direct-signal-update-suppressed:" + validation.reason());
      return false;
    }
    SignalAspect previous = currentPhysicalAspect(trainName, SignalAspect.STOP);
    boolean updated = progressRegistry.updateSignal(trainName, aspect, now);
    String key = trainName.toLowerCase(Locale.ROOT);
    if (updated) {
      missingSignalWarned.remove(key);
      publishedPhysicalSignals.put(normalizeTrainKey(trainName), aspect);
      traceSmartSignalFinal(
          trainName,
          "DIRECT_SIGNAL_UPDATE",
          aspect,
          aspect,
          aspect,
          true,
          false,
          previous,
          "updateSignalOrWarn",
          tokenState(trainName, movementToken(trainName).orElse(null)),
          destinationPresent(trainName),
          "UNKNOWN",
          "updateSignalOrWarn",
          DispatchEffectClass.SIGNAL_CONSTRAINT,
          currentSignalTraceTick(),
          "-");
      return true;
    }
    if (missingSignalWarned.add(key)) {
      debugLogger.accept("信号更新失败: entry 缺失 train=" + trainName + " aspect=" + aspect.name());
    }
    return false;
  }

  private boolean destinationPresent(String trainName) {
    return movementToken(trainName)
        .flatMap(MovementAuthorizationToken::committedDestination)
        .filter(value -> !value.isBlank())
        .isPresent();
  }

  private String currentDestination(String trainName) {
    return movementToken(trainName)
        .flatMap(MovementAuthorizationToken::committedDestination)
        .filter(value -> !value.isBlank())
        .orElse("-");
  }

  private void traceAdmissionAuthorityConsistency(
      String trainName,
      String source,
      SmartAdmissionResult admission,
      SignalAspect signalAspectBefore,
      SignalAspect signalAspectAfter,
      boolean physicalPublished,
      SignalComputationTrace.TokenState movementTokenState,
      boolean destinationPresent,
      String destination,
      AuthorityEnd authorityEnd,
      NodeId currentNode,
      NodeId nextNode,
      String authorityWindowValid,
      String authorityWindowFailureReason,
      SignalAspect finalEffectiveAspect,
      SignalAspect finalPhysicalPublishedAspect,
      boolean contradictionDetected) {
    debugLogger.accept(
        "SMART_ADMISSION_AUTHORITY_CONSISTENCY train="
            + (trainName == null || trainName.isBlank() ? "-" : trainName)
            + " tick="
            + currentSignalTraceTick()
            + " source="
            + (source == null || source.isBlank() ? "UNKNOWN" : source)
            + " admissionDecision="
            + admissionDecisionText(admission)
            + " admissionReason="
            + admissionReasonText(admission)
            + " signalAspectBefore="
            + (signalAspectBefore == null ? SignalAspect.STOP : signalAspectBefore)
            + " signalAspectAfter="
            + (signalAspectAfter == null ? SignalAspect.STOP : signalAspectAfter)
            + " physicalPublished="
            + physicalPublished
            + " movementTokenState="
            + (movementTokenState == null
                ? SignalComputationTrace.TokenState.NONE
                : movementTokenState)
            + " destinationPresent="
            + destinationPresent
            + " destination="
            + (destination == null || destination.isBlank() ? "-" : destination)
            + " authorityEndResource="
            + (authorityEnd == null ? "-" : authorityEnd.resource())
            + " currentNode="
            + (currentNode == null ? "-" : currentNode.value())
            + " nextNode="
            + (nextNode == null ? "-" : nextNode.value())
            + " authorizedEdgeCount="
            + (authorityEnd == null ? 0 : authorityEnd.authorizedEdgeCount())
            + " authorityWindowValid="
            + (authorityWindowValid == null || authorityWindowValid.isBlank()
                ? "UNKNOWN"
                : authorityWindowValid)
            + " authorityWindowFailureReason="
            + (authorityWindowFailureReason == null || authorityWindowFailureReason.isBlank()
                ? "-"
                : authorityWindowFailureReason)
            + " finalEffectiveAspect="
            + (finalEffectiveAspect == null ? SignalAspect.STOP : finalEffectiveAspect)
            + " finalPhysicalPublishedAspect="
            + (finalPhysicalPublishedAspect == null
                ? SignalAspect.STOP
                : finalPhysicalPublishedAspect)
            + " contradictionDetected="
            + contradictionDetected);
  }

  private static String admissionDecisionText(SmartAdmissionResult admission) {
    if (admission == null || !admission.applies()) {
      return "NOT_APPLIED";
    }
    return admission.decision().name();
  }

  private static String admissionReasonText(SmartAdmissionResult admission) {
    if (admission == null || !admission.applies()) {
      return "-";
    }
    return admission.reason();
  }

  private void traceSmartSignalFinal(
      String trainName,
      String source,
      SignalAspect requestedAspect,
      SignalAspect computedAspect,
      SignalAspect finalAspect,
      boolean physicalPublished,
      boolean publishSuppressed,
      SignalAspect previousPhysicalAspect,
      String reason,
      SignalComputationTrace.TokenState authorityTokenState,
      boolean destinationPresent,
      String authorityWindowValid,
      String dispatcherAction,
      DispatchEffectClass effectClass,
      long tick,
      String requestId) {
    debugLogger.accept(
        "SMART_SIGNAL_FINAL train="
            + (trainName == null || trainName.isBlank() ? "-" : trainName)
            + " tick="
            + tick
            + " source="
            + (source == null || source.isBlank() ? "UNKNOWN" : source)
            + " requestedAspect="
            + (requestedAspect == null ? SignalAspect.STOP : requestedAspect)
            + " computedAspect="
            + (computedAspect == null ? SignalAspect.STOP : computedAspect)
            + " finalAspect="
            + (finalAspect == null ? SignalAspect.STOP : finalAspect)
            + " physicalPublished="
            + physicalPublished
            + " publishSuppressed="
            + publishSuppressed
            + " previousPhysicalAspect="
            + (previousPhysicalAspect == null ? SignalAspect.STOP : previousPhysicalAspect)
            + " reason="
            + (reason == null || reason.isBlank() ? "-" : reason)
            + " authorityTokenState="
            + (authorityTokenState == null
                ? SignalComputationTrace.TokenState.NONE
                : authorityTokenState)
            + " destinationPresent="
            + destinationPresent
            + " authorityWindowValid="
            + (authorityWindowValid == null || authorityWindowValid.isBlank()
                ? "UNKNOWN"
                : authorityWindowValid)
            + " dispatcherAction="
            + (dispatcherAction == null || dispatcherAction.isBlank() ? "-" : dispatcherAction)
            + " effectClass="
            + (effectClass == null ? DispatchEffectClass.DIAGNOSTIC_ONLY : effectClass)
            + " requestId="
            + (requestId == null || requestId.isBlank() ? "-" : requestId));
  }

  private void traceSmartSignalFinalDecision(
      String trainName,
      String source,
      SmartAdmissionResult admission,
      SmartSignalDecisionResult smartDecision,
      SignalAspect finalAspect,
      SignalComputationTrace.TokenState movementTokenState,
      boolean destinationPresent,
      AuthorityEnd authorityEnd,
      String authorityWindowValid,
      String authorityWindowFailureReason,
      boolean recoverableHold,
      boolean hardInvalid,
      boolean physicalBoundaryFailure,
      boolean destinationCleared,
      boolean replanTriggered) {
    debugLogger.accept(
        "SMART_SIGNAL_FINAL_DECISION train="
            + (trainName == null || trainName.isBlank() ? "-" : trainName)
            + " tick="
            + currentSignalTraceTick()
            + " decisionVersion="
            + progressRegistry.version()
            + " source="
            + (source == null || source.isBlank() ? "UNKNOWN" : source)
            + " admission="
            + admissionDecisionText(admission)
            + " admissionReason="
            + admissionReasonText(admission)
            + " action="
            + (smartDecision == null ? DispatchAction.HOLD_AT_SIGNAL : smartDecision.action())
            + " riskSource="
            + (smartDecision == null ? RiskSource.NONE : smartDecision.riskSource())
            + " finalAspect="
            + (finalAspect == null ? SignalAspect.STOP : finalAspect)
            + " movementTokenState="
            + (movementTokenState == null
                ? SignalComputationTrace.TokenState.NONE
                : movementTokenState)
            + " destinationPresent="
            + destinationPresent
            + " recoverableHold="
            + recoverableHold
            + " hardInvalid="
            + hardInvalid
            + " physicalBoundaryFailure="
            + physicalBoundaryFailure
            + " destinationCleared="
            + destinationCleared
            + " authorityEndReason="
            + (authorityEnd == null ? "-" : authorityEnd.reason())
            + " authorityEnd="
            + (authorityEnd == null ? "-" : authorityEnd.resource())
            + " authorityWindowValid="
            + (authorityWindowValid == null || authorityWindowValid.isBlank()
                ? "UNKNOWN"
                : authorityWindowValid)
            + " authorityWindowFailureReason="
            + (authorityWindowFailureReason == null || authorityWindowFailureReason.isBlank()
                ? "-"
                : authorityWindowFailureReason)
            + " replanTriggered="
            + replanTriggered);
  }

  private void traceStructuredSignalFinalDecision(
      String trainName,
      FinalSignalAuthorization authorization,
      SignalAspect finalSignal,
      boolean physicalPublished,
      boolean didMutate,
      String reason) {
    OccupancyRequest request = authorization == null ? null : authorization.request();
    FinalSignalValidation validation =
        validateFinalSignalAuthorization(trainName, finalSignal, authorization, true);
    String conflictZone = firstSingleConflictKey(request);
    CorridorDirection direction =
        request == null || "-".equals(conflictZone)
            ? CorridorDirection.UNKNOWN
            : requestedDirectionFor(request, OccupancyResource.forConflict(conflictZone));
    SignalComputationTrace.TokenState tokenState =
        authorization == null
            ? tokenState(trainName, movementToken(trainName).orElse(null))
            : authorization.tokenState();
    boolean destinationPresent =
        authorization == null ? destinationPresent(trainName) : authorization.destinationPresent();
    debugLogger.accept(
        "event=SIGNAL_FINAL_DECISION train="
            + (trainName == null || trainName.isBlank() ? "-" : trainName)
            + " requestId="
            + requestIdOf(request)
            + " decisionVersion="
            + progressRegistry.version()
            + " occupancyVersion="
            + occupancyVersion()
            + " progressVersion="
            + (request == null
                ? "-"
                : request
                    .directedContext()
                    .map(DirectedTraversalContext::progressVersion)
                    .map(String::valueOf)
                    .orElse("-"))
            + " conflictZone="
            + conflictZone
            + " singleRegion="
            + !"-".equals(conflictZone)
            + " directionRelation="
            + direction
            + " hardBarrierPresent="
            + validation.hardBarrierPresent()
            + " hardBarrierReason="
            + validation.hardBarrierReason()
            + " movementInhibited="
            + isMovementInhibited(trainName)
            + " tokenState="
            + tokenState
            + " destinationPresent="
            + destinationPresent
            + " finalSignal="
            + (finalSignal == null ? SignalAspect.STOP : finalSignal)
            + " advisoryApplied="
            + (authorization != null && authorization.advisoryApplied())
            + " physicalPublished="
            + physicalPublished
            + " didMutate="
            + didMutate
            + " reason="
            + (reason == null || reason.isBlank() ? validation.reason() : reason));
  }

  private SignalAspect currentPhysicalAspect(String trainName, SignalAspect fallback) {
    String key = normalizeTrainKey(trainName);
    if (!key.isEmpty()) {
      SignalAspect published = publishedPhysicalSignals.get(key);
      if (published != null) {
        return published;
      }
    }
    SignalAspect registryAspect =
        progressRegistry
            .get(trainName)
            .map(RouteProgressRegistry.RouteProgressEntry::lastSignal)
            .orElse(null);
    if (registryAspect != null) {
      return registryAspect;
    }
    return fallback == null ? SignalAspect.STOP : fallback;
  }

  private static long currentSignalTraceTick() {
    return System.currentTimeMillis() / 50L;
  }

  private static String requestIdOf(OccupancyRequest request) {
    return request == null
        ? "-"
        : request.directedContext().map(DirectedTraversalContext::requestId).orElse("-");
  }

  private FinalSignalValidation validateFinalSignalAuthorization(
      String trainName,
      SignalAspect aspect,
      FinalSignalAuthorization authorization,
      boolean allowPendingInhibitorClear) {
    if (!SignalDecisionInputClassifier.isProceedLike(aspect)) {
      return FinalSignalValidation.ok();
    }
    if (authorization == null) {
      return FinalSignalValidation.blocked("final-authorization-missing", false, true, "-");
    }
    OccupancyRequest request = authorization.request();
    OccupancyDecision decision = authorization.decision();
    SignalPublicationGate.Decision publication = authorization.publication();
    boolean hardBarrierPresent = finalSignalHardBarrierPresent(trainName, request);
    String hardBarrierReason =
        hardBarrierPresent
            ? SimpleOccupancyManager.OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER
            : "-";
    if (hardBarrierPresent) {
      return FinalSignalValidation.blocked(
          "single-region-hard-barrier", true, false, hardBarrierReason);
    }
    if (!finalSignalSnapshotFresh(request)) {
      return FinalSignalValidation.blocked("stale-final-snapshot", false, true, "-");
    }
    if (decision == null || !decision.allowed()) {
      return FinalSignalValidation.blocked("occupancy-not-allowed", false, false, "-");
    }
    if (publication == null || publication.blocked() || publication.visibleAspect() != aspect) {
      return FinalSignalValidation.blocked(
          publication == null ? "publication-missing" : "publication-gate:" + publication.reason(),
          false,
          false,
          "-");
    }
    if (!authorization.destinationPresent()) {
      return FinalSignalValidation.blocked("destination-missing", false, false, "-");
    }
    if (authorization.tokenState() != SignalComputationTrace.TokenState.ACTIVE) {
      return FinalSignalValidation.blocked("movement-token-not-active", false, false, "-");
    }
    if (!hasActiveMovementAuthorization(
        trainName,
        authorization.currentNode(),
        authorization.nextNode(),
        request == null ? List.of() : request.resourceList(),
        allowPendingInhibitorClear)) {
      return FinalSignalValidation.blocked("movement-token-invalid", false, false, "-");
    }
    return FinalSignalValidation.ok();
  }

  private boolean hasActiveMovementAuthorization(
      String trainName,
      NodeId fromNode,
      NodeId toNode,
      List<OccupancyResource> resources,
      boolean ignoreInhibitor) {
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return false;
    }
    if (!ignoreInhibitor && movementInhibitors.containsKey(key)) {
      return false;
    }
    MovementAuthorizationToken token = movementAuthorizationTokens.get(key);
    if (token == null || !token.active()) {
      return false;
    }
    if (fromNode != null && token.fromNode() != null && !fromNode.equals(token.fromNode())) {
      return false;
    }
    if (toNode != null && token.toNode() != null && !toNode.equals(token.toNode())) {
      return false;
    }
    return resources == null || resources.isEmpty() || token.resources().containsAll(resources);
  }

  private PhysicalSignalPublication publishPhysicalSignalIfRequired(
      String trainName,
      SignalAspect finalPublishedAspect,
      SignalAspect fallback,
      Instant now,
      FinalSignalAuthorization authorization) {
    SignalAspect before = currentPhysicalAspect(trainName, fallback);
    boolean updateRequired = before != finalPublishedAspect;
    if (!updateRequired) {
      return new PhysicalSignalPublication(
          before, before, false, false, "already-current-physical-aspect");
    }
    boolean updated = updateSignalOrWarn(trainName, finalPublishedAspect, now, authorization);
    SignalAspect after = currentPhysicalAspect(trainName, before);
    return new PhysicalSignalPublication(
        before, after, true, updated, updated ? "-" : "progress-entry-missing");
  }

  private boolean updateSignalOrWarnPreservingPublishedCaution(
      String trainName, SignalAspect aspect, Instant now, String reason) {
    if (aspect == SignalAspect.STOP
        && currentPhysicalAspect(trainName, SignalAspect.STOP)
            == SignalAspect.PROCEED_WITH_CAUTION) {
      debugLogger.accept(
          "SIGNAL_CAUTION_PUBLISHED train="
              + trainName
              + " preserve=true suppressedStopReason="
              + (reason == null || reason.isBlank() ? "-" : reason));
      return false;
    }
    return updateSignalOrWarn(trainName, aspect, now);
  }

  /** 无条件清理列车发车许可锁。 */
  private void clearDepartureGate(String trainName) {
    String key = normalizeTrainKey(trainName);
    if (!key.isEmpty()) {
      departureGates.remove(key);
    }
  }

  /** 迁移列车发车许可锁（用于列车改名场景）。 */
  private void moveDepartureGate(String oldTrainName, String newTrainName) {
    String oldKey = normalizeTrainKey(oldTrainName);
    String newKey = normalizeTrainKey(newTrainName);
    if (oldKey.isEmpty() || newKey.isEmpty() || oldKey.equals(newKey)) {
      return;
    }
    DepartureGate gate = departureGates.remove(oldKey);
    if (gate != null) {
      departureGates.put(newKey, gate);
    }
  }

  private static String formatDepartureGate(DepartureGate gate) {
    if (gate == null) {
      return "none";
    }
    return gate.reason() + "@" + gate.sessionId();
  }

  private static String resolveSignalReason(
      SignalAspect aspect,
      boolean allowLaunch,
      DepartureGate gate,
      ControlDebugResources debugResources) {
    if (gate != null) {
      return "door_gate:" + gate.reason();
    }
    if (debugResources != null && !debugResources.blockers().isEmpty()) {
      return "occupancy_blocked";
    }
    if (aspect == SignalAspect.STOP && !allowLaunch) {
      return "hold_stop";
    }
    return allowLaunch ? "authorized_launch" : "signal_refresh";
  }

  /**
   * DYNAMIC 目标暂时无法安全 materialize 时保持在当前位置。
   *
   * <p>该状态与普通冲突队列等待不同：列车尚未拥有可进入的目标站台，因此必须撤回当前全部纯排队位次，且不得新建任何前向 claim。当前位置缺少保护时仅补
   * NODE/HOLD_ONLY；已经存在的 NODE、EDGE、CONFLICT、PHYSICAL_FOOTPRINT 等真实 claim
   * 原样保留。这样站内列车可以取得清空站台的出站授权，同时不会绕过进站车已经真实占住的共享资源。
   */
  private void holdForUnavailableDynamicDestination(
      RuntimeTrainHandle train,
      TrainProperties properties,
      String trainName,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode,
      Instant now,
      String reason) {
    RailGraph graph = resolveGraph(train.worldId(), now).orElse(null);
    int withdrawnQueueEntries =
        retainDynamicCapacityWaitOccupancy(trainName, route, currentNode, now);
    properties.clearDestinationRoute();
    properties.clearDestination();
    NodeId nextNode =
        currentIndex + 1 < route.waypoints().size()
            ? resolveEffectiveNode(trainName, route, currentIndex + 1)
            : null;
    String resolvedReason =
        reason == null || reason.isBlank() ? "dynamic-target-unavailable" : reason;
    OccupancyDecision blocked =
        new OccupancyDecision(
            false,
            now,
            SignalAspect.STOP,
            List.of(),
            false,
            "dynamic-target-blocked:" + resolvedReason);
    applyHardStop(
        train,
        properties,
        trainName,
        HardStopReason.AUTHORIZATION_FAILURE,
        false,
        route,
        currentNode,
        nextNode,
        graph,
        blocked,
        null,
        AuthorityEnd.none());
    debugLogger.accept(
        dynamicWaitLogLabel(resolvedReason)
            + ": train="
            + trainName
            + " route="
            + route.id().value()
            + " index="
            + currentIndex
            + " reason="
            + resolvedReason
            + " withdrawnQueueEntries="
            + withdrawnQueueEntries);
  }

  private static String dynamicWaitLogLabel(String reason) {
    return reason != null && reason.startsWith("no-available") ? "DYNAMIC 容量等待" : "DYNAMIC 目标等待";
  }

  /**
   * 信号 tick 中"保持 STOP 并保留当前占用"的统一处理。
   *
   * <p>用于门控等待、dwell 窗口、waypoint 停站三种场景，避免在 handleSignalTick 中重复相同的占用保留 + 控车下发逻辑。
   *
   * @param train 列车句柄
   * @param properties 列车属性
   * @param trainName 逻辑列车名
   * @param route 当前线路定义
   * @param currentIndex 当前进度索引
   * @param currentNode 当前有效节点（可为 null）
   * @param now 当前时间
   */
  private void holdStopAtCurrentNode(
      RuntimeTrainHandle train,
      TrainProperties properties,
      String trainName,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode,
      Instant now) {
    Optional<NodeId> nextNode =
        currentIndex + 1 < route.waypoints().size()
            ? Optional.of(resolveEffectiveNode(trainName, route, currentIndex + 1))
            : Optional.empty();
    if (nextNode.isPresent()) {
      if (occupancyManager != null) {
        if (currentNode != null) {
          RailGraph graph = resolveGraph(train.worldId(), now).orElse(null);
          retainStopOccupancy(trainName, route, currentIndex, currentNode, graph, now);
        }
      }
      updateSignalOrWarn(trainName, SignalAspect.STOP, now);
      applyControl(
          train,
          properties,
          SignalAspect.STOP,
          route,
          currentNode,
          nextNode.get(),
          null,
          false,
          OptionalLong.empty());
    } else {
      runtimeTrainController.stopNow(train);
    }
  }

  /**
   * 落地闭塞硬 STOP。
   *
   * <p>硬 STOP 是运行时安全边界：始终撤销运动授权并安装 movement inhibitor；TrainCarts destination/route
   * 是否清理由失败原因、调用意图与运行时配置共同决定。物理控车使用 {@link StopControlMode#HARD_STOP} 绕过速度曲线、launch cooldown 与信号
   * no-op。计划停站/居中流程不得调用该方法。
   */
  private void applyHardStop(
      RuntimeTrainHandle train,
      TrainProperties properties,
      String trainName,
      HardStopReason reason,
      boolean clearDestination,
      RouteDefinition route,
      NodeId currentNode,
      NodeId nextNode,
      RailGraph graph,
      OccupancyDecision decision,
      OccupancyRequest request,
      AuthorityEnd authorityEnd) {
    if (properties == null || trainName == null || trainName.isBlank()) {
      return;
    }
    Instant stoppedAt = Instant.now();
    invalidateMovementAuthorization(trainName, reason);
    if (shouldClearDestinationOnHardStop(reason, clearDestination)) {
      properties.clearDestinationRoute();
      properties.clearDestination();
    }
    updateBlockerSnapshot(trainName, decision, stoppedAt);
    if (route == null) {
      runtimeTrainController.stopHard(train, properties);
    }
    updateSignalOrWarn(trainName, SignalAspect.STOP, stoppedAt);
    ControlDebugResources debugResources =
        new ControlDebugResources(
            summarizeClaims(
                decision != null ? decision.blockers() : List.of(),
                trainName,
                route,
                Math.max(
                    0,
                    progressRegistry
                        .get(trainName)
                        .map(RouteProgressRegistry.RouteProgressEntry::currentIndex)
                        .orElse(0)),
                request != null ? Set.copyOf(request.resourceList()) : Set.of()),
            request != null ? summarizeResources(request.resourceList(), 12) : List.of(),
            summarizeTrainClaims(
                trainName,
                route,
                Math.max(
                    0,
                    progressRegistry
                        .get(trainName)
                        .map(RouteProgressRegistry.RouteProgressEntry::currentIndex)
                        .orElse(0)),
                request != null ? Set.copyOf(request.resourceList()) : Set.of(),
                12));
    BlockedDestinationDiagnostic blockedDestination =
        resolveBlockedDestinationDiagnostic(properties, SignalAspect.STOP, false, decision);
    String reasonText =
        reason == null ? HardStopReason.UNKNOWN.name() : reason.name().toLowerCase(Locale.ROOT);
    if ("authorization-blocked".equals(blockedDestination.blockedReason())
        || "signal-stop".equals(blockedDestination.blockedReason())
        || "none".equals(blockedDestination.blockedReason())) {
      blockedDestination =
          new BlockedDestinationDiagnostic(
              blockedDestination.destinationPresentWhileBlocked(),
              blockedDestination.retainedDestination(),
              reasonText);
    }
    SignalComputationTrace.emit(
        signalTrace(
                trainName,
                properties,
                sourceForHardStop(reason),
                progressRegistry
                    .get(trainName)
                    .map(RouteProgressRegistry.RouteProgressEntry::lastSignal)
                    .orElse(null),
                SignalAspect.STOP,
                "hard-stop:" + reasonText)
            .nodes(currentNode, nextNode)
            .progress(
                progressRegistry.version(),
                progressRegistry
                    .get(trainName)
                    .map(RouteProgressRegistry.RouteProgressEntry::currentIndex)
                    .orElse(-1),
                progressRegistry
                    .get(trainName)
                    .map(RouteProgressRegistry.RouteProgressEntry::currentIndex)
                    .orElse(-1),
                progressRegistry
                    .get(trainName)
                    .flatMap(RouteProgressRegistry.RouteProgressEntry::lastPassedGraphNode),
                progressRegistry
                    .get(trainName)
                    .flatMap(RouteProgressRegistry.RouteProgressEntry::lastPassedGraphNode))
            .request(request)
            .decision(decision, request)
            .distances(
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                authorityEnd.distanceBlocks(),
                authorityEnd.resource(),
                authorityEnd.authorizedEdgeCount()));
    applyControl(
        train,
        properties,
        SignalAspect.STOP,
        route,
        currentNode,
        nextNode,
        graph,
        false,
        OptionalLong.empty(),
        null,
        new ControlSpeedOverrides(
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            ApproachControl.none(),
            authorityEnd,
            blockedDestination,
            debugResources),
        StopControlMode.HARD_STOP);
  }

  /**
   * 当当前移动计划无法形成完整安全进路时立即 fail-closed。
   *
   * <p>请求构造失败通常意味着已进入联锁窗口却找不到清出点，或图快照无法证明下一段进路完整。此时不能保留上一周期的 PROCEED/destination
   * 控车状态；先尽力保留当前位置和列尾防护，再撤销移动授权并落地硬 STOP。该方法不清 destination， 因为路径证据缺失可能只是临时图状态；重新获得完整授权前 movement
   * inhibitor 会阻止继续动车。
   *
   * @param source 触发 fail-closed 的运行时入口
   * @param buildFailure 请求构造失败诊断
   */
  private void applyUnresolvableMovementPlanStop(
      RuntimeTrainHandle train,
      TrainProperties properties,
      String trainName,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode,
      NodeId nextNode,
      RailGraph graph,
      Instant now,
      String source,
      String buildFailure) {
    String resolvedSource = source == null || source.isBlank() ? "unknown" : source;
    String resolvedFailure =
        buildFailure == null || buildFailure.isBlank() ? "unknown" : buildFailure;
    debugLogger.accept(
        "调度 fail-closed: 构建占用请求失败 train="
            + trainName
            + " source="
            + resolvedSource
            + " node="
            + (currentNode == null ? "-" : currentNode.value())
            + " route="
            + (route == null ? "-" : route.id().value())
            + " reason="
            + resolvedFailure);
    if (occupancyManager != null && currentNode != null) {
      retainStopOccupancy(
          trainName, route, currentIndex, currentNode, graph, now == null ? Instant.now() : now);
    }
    OccupancyDecision blocked =
        new OccupancyDecision(
            false,
            now == null ? Instant.now() : now,
            SignalAspect.STOP,
            List.of(),
            false,
            "movement-plan-unresolvable:" + resolvedFailure);
    applyHardStop(
        train,
        properties,
        trainName,
        HardStopReason.SINGLE_CORRIDOR_FAIL_CLOSED,
        false,
        route,
        currentNode,
        nextNode,
        graph,
        blocked,
        null,
        AuthorityEnd.none());
  }

  private boolean shouldClearDestinationOnHardStop(HardStopReason reason, boolean requested) {
    if (!requested || !configManager.current().runtimeSettings().clearDestinationOnHardStop()) {
      return false;
    }
    return reason == HardStopReason.AUTHORIZATION_FAILURE
        || reason == HardStopReason.UNREACHABLE_FAILOVER
        || reason == HardStopReason.AUTHORITY_WINDOW_EXCEEDED;
  }

  /**
   * 普通占用等待 STOP。
   *
   * <p>这类 STOP 代表“当前不能继续取得 hard authority”，不是 destination/token 自身损坏；因此只更新 STOP 信号与控车诊断，不清
   * TrainCarts destination，也不写入 movement inhibitor。
   */
  private void applyNonInvalidatingBlockedStop(
      RuntimeTrainHandle train,
      TrainProperties properties,
      String trainName,
      String waitReason,
      RouteDefinition route,
      NodeId currentNode,
      NodeId nextNode,
      RailGraph graph,
      OccupancyDecision decision,
      OccupancyRequest request,
      AuthorityEnd authorityEnd) {
    Instant stoppedAt = Instant.now();
    String reason =
        waitReason == null || waitReason.isBlank() ? "BLOCKED_BY_OCCUPANCY" : waitReason;
    updateBlockerSnapshot(trainName, decision, stoppedAt);
    boolean physicalPublished = updateSignalOrWarn(trainName, SignalAspect.STOP, stoppedAt);
    SmartUnlockReservation activeUnlock =
        smartUnlockReservationsByTrain.get(normalizeTrainKey(trainName));
    if (activeUnlock != null) {
      SignalComputationTrace.TokenState movementTokenState =
          tokenState(trainName, movementToken(trainName).orElse(null));
      traceSmartUnlockPlanApply(
          activeUnlock,
          "signal-evaluated",
          physicalPublished ? "physical-stop-published" : "physical-stop-not-published",
          SignalAspect.STOP,
          decision == null ? SignalAspect.STOP : decision.signal(),
          SignalAspect.STOP,
          physicalPublished,
          false,
          waitReason,
          "SMART_DISPATCH_RECOVERABLE_HOLD".equals(waitReason),
          "unknown",
          "unknown",
          "unknown",
          movementTokenState,
          reason);
    }
    BlockedDestinationDiagnostic blockedDestination =
        resolveBlockedDestinationDiagnostic(properties, SignalAspect.STOP, false, decision);
    blockedDestination =
        new BlockedDestinationDiagnostic(
            blockedDestination.destinationPresentWhileBlocked(),
            blockedDestination.retainedDestination(),
            reason);
    ControlDebugResources debugResources =
        new ControlDebugResources(
            summarizeClaims(
                decision != null ? decision.blockers() : List.of(),
                trainName,
                route,
                Math.max(
                    0,
                    progressRegistry
                        .get(trainName)
                        .map(RouteProgressRegistry.RouteProgressEntry::currentIndex)
                        .orElse(0)),
                request != null ? Set.copyOf(request.resourceList()) : Set.of()),
            request != null ? summarizeResources(request.resourceList(), 12) : List.of(),
            summarizeTrainClaims(
                trainName,
                route,
                Math.max(
                    0,
                    progressRegistry
                        .get(trainName)
                        .map(RouteProgressRegistry.RouteProgressEntry::currentIndex)
                        .orElse(0)),
                request != null ? Set.copyOf(request.resourceList()) : Set.of(),
                12));
    SignalComputationTrace.emit(
        signalTrace(
                trainName,
                properties,
                SignalComputationTrace.Source.PERIODIC_TICK,
                progressRegistry
                    .get(trainName)
                    .map(RouteProgressRegistry.RouteProgressEntry::lastSignal)
                    .orElse(null),
                SignalAspect.STOP,
                "blocked-stop:" + reason)
            .field("stopDoesNotInvalidateReason", reason)
            .field("tokenInvalidReason", "-")
            .field("destinationClearReason", "-")
            .field("authorizationFailureSource", "-")
            .nodes(currentNode, nextNode)
            .request(request)
            .decision(decision, request)
            .distances(
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                authorityEnd.distanceBlocks(),
                authorityEnd.resource(),
                authorityEnd.authorizedEdgeCount()));
    applyControl(
        train,
        properties,
        SignalAspect.STOP,
        route,
        currentNode,
        nextNode,
        graph,
        false,
        OptionalLong.empty(),
        null,
        new ControlSpeedOverrides(
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            ApproachControl.none(),
            authorityEnd,
            blockedDestination,
            debugResources));
  }

  /** 对在线列车重新下发硬 STOP，供健康监控在 STOP 互卡等待期间使用。 */
  public boolean reapplyHardStopByName(String trainName, String reason) {
    RuntimeTrainResolution resolution =
        resolveRuntimeTrainForHealth(trainName, RuntimeTrainResolvePurpose.REAPPLY_HARD_STOP);
    TrainProperties properties = resolution.properties();
    if (properties == null || !isFtaManagedTrain(properties)) {
      traceRuntimeTrainResolveFailed(
          RuntimeTrainResolvePurpose.REAPPLY_HARD_STOP,
          properties == null
              ? resolution
              : failedRuntimeTrainResolution(
                  trainName, "NOT_FTA_MANAGED", resolution.resolvedName()));
      return false;
    }
    MinecartGroup group = properties.getHolder();
    RuntimeTrainHandle train =
        group != null && group.isValid() ? new TrainCartsRuntimeHandle(group) : null;
    Optional<RouteProgressRegistry.RouteProgressEntry> entryOpt =
        progressRegistry.get(resolution.resolvedName());
    RouteDefinition route = null;
    NodeId currentNode = null;
    NodeId nextNode = null;
    RailGraph graph = null;
    if (entryOpt.isPresent()) {
      RouteProgressRegistry.RouteProgressEntry entry = entryOpt.get();
      Optional<RouteDefinition> routeOpt = resolveRouteForRecovery(entry, properties);
      if (routeOpt.isPresent()) {
        route = routeOpt.get();
        int index = entry.currentIndex();
        if (index >= 0 && index < route.waypoints().size()) {
          currentNode = resolveEffectiveNode(trainName, route, index);
        }
        if (index + 1 < route.waypoints().size()) {
          nextNode = resolveEffectiveNode(trainName, route, index + 1);
        }
        if (train != null) {
          graph = resolveGraph(train.worldId(), Instant.now()).orElse(null);
        }
      }
    }
    String resolvedTrainName =
        entryOpt
            .map(RouteProgressRegistry.RouteProgressEntry::trainName)
            .orElse(resolution.resolvedName());
    applyHardStop(
        train,
        properties,
        resolvedTrainName,
        HardStopReason.DEADLOCK_CONFIRMED_WAITING,
        true,
        route,
        currentNode,
        nextNode,
        graph,
        null,
        null,
        AuthorityEnd.none());
    debugLogger.accept(
        "HealthMonitor reapplyHardStop: train="
            + resolvedTrainName
            + " reason="
            + (reason == null || reason.isBlank() ? "health" : reason));
    return true;
  }

  private MovementAuthorizationToken issueMovementAuthorizationToken(
      String trainName,
      NodeId fromNode,
      NodeId toNode,
      OccupancyRequest request,
      SignalAspect aspect,
      Instant now) {
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return null;
    }
    MovementAuthorizationToken token =
        new MovementAuthorizationToken(
            trainName,
            movementClaimVersion.incrementAndGet(),
            now,
            fromNode,
            toNode,
            request != null ? request.resourceList() : List.of(),
            aspect);
    movementAuthorizationTokens.put(key, token);
    return token;
  }

  private boolean activateMovementAuthorizationTokenRetainingInhibitor(
      String trainName, MovementAuthorizationToken token, String destinationName) {
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty() || token == null || destinationName == null || destinationName.isBlank()) {
      return false;
    }
    MovementAuthorizationToken current = movementAuthorizationTokens.get(key);
    if (current == null || current.claimVersion() != token.claimVersion()) {
      return false;
    }
    movementAuthorizationTokens.put(key, current.activate(destinationName));
    return true;
  }

  private boolean clearMovementInhibitorAfterFinalAuthorization(
      String trainName, FinalSignalAuthorization authorization, SignalAspect finalAspect) {
    FinalSignalValidation validation =
        validateFinalSignalAuthorization(trainName, finalAspect, authorization, true);
    if (!validation.allowed()) {
      debugLogger.accept(
          "MOVEMENT_INHIBITOR_CLEAR_SUPPRESSED train="
              + trainName
              + " reason="
              + validation.reason()
              + " hardBarrierPresent="
              + validation.hardBarrierPresent()
              + " hardBarrierReason="
              + validation.hardBarrierReason()
              + " snapshotStale="
              + validation.snapshotStale()
              + " wouldMutate=true didMutate=false");
      return false;
    }
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return false;
    }
    movementInhibitors.remove(key);
    return true;
  }

  private void rollbackMovementAuthorization(
      String trainName,
      MovementAuthorizationToken token,
      OccupancyRequest request,
      HardStopReason reason) {
    String key = normalizeTrainKey(trainName);
    if (!key.isEmpty() && token != null) {
      MovementAuthorizationToken current = movementAuthorizationTokens.get(key);
      if (current != null && current.claimVersion() == token.claimVersion()) {
        movementAuthorizationTokens.remove(key);
      }
    }
    releaseMovementAuthorityResources(trainName, request);
    invalidateMovementAuthorization(trainName, reason);
  }

  /**
   * 释放本 tick acquire 到的 hard authority，但不写入 movement inhibitor。
   *
   * <p>Smart Dispatcher 的 recoverable hold 只是要求当前授权片段重试/重算，不代表 TrainCarts destination 或整列车运动授权永久损坏。
   * 因此这里保留 pending token 作为可恢复状态证据，避免把后续诊断误导成 {@code INVALID}。
   */
  private void rollbackMovementAuthorizationWithoutInhibitor(
      String trainName,
      MovementAuthorizationToken token,
      OccupancyRequest request,
      SmartSignalDecisionResult smartDecision,
      DispatchPriorityResolution priorityResolution,
      String retainedDestination) {
    releaseRecoverableMovementAuthorityResources(
        trainName, token, request, smartDecision, priorityResolution, retainedDestination);
  }

  private void retainRecoverableMovementDestination(
      String trainName, MovementAuthorizationToken token, String destinationName) {
    if (token == null || destinationName == null || destinationName.isBlank()) {
      return;
    }
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return;
    }
    movementAuthorizationTokens.computeIfPresent(
        key,
        (ignored, current) ->
            current != null && current.claimVersion() == token.claimVersion()
                ? current.retainDestination(destinationName)
                : current);
  }

  private void releaseMovementAuthorityResources(String trainName, OccupancyRequest request) {
    if (occupancyManager == null || request == null) {
      return;
    }
    Set<OccupancyResource> protectedFootprint =
        turnbackFootprintGuards.protectedResources(trainName);
    for (OccupancyResource resource : request.resourceList()) {
      if (resource != null
          && request.intentFor(resource).hardAuthority()
          && !protectedFootprint.contains(resource)) {
        occupancyManager.releaseResource(resource, Optional.of(trainName));
      }
    }
  }

  private void releaseRecoverableMovementAuthorityResources(
      String trainName,
      MovementAuthorizationToken token,
      OccupancyRequest request,
      SmartSignalDecisionResult smartDecision,
      DispatchPriorityResolution priorityResolution,
      String retainedDestination) {
    if (occupancyManager == null || request == null) {
      return;
    }
    SignalComputationTrace.TokenState tokenState = tokenState(trainName, token);
    boolean destinationPresent = retainedDestination != null && !retainedDestination.isBlank();
    boolean retainPendingWinner =
        destinationPresent
            && (tokenState == SignalComputationTrace.TokenState.PENDING
                || tokenState == SignalComputationTrace.TokenState.ACTIVE);
    Set<OccupancyResource> protectedFootprint =
        turnbackFootprintGuards.protectedResources(trainName);
    for (OccupancyResource resource : request.resourceList()) {
      if (resource == null || !request.intentFor(resource).hardAuthority()) {
        continue;
      }
      if (protectedFootprint.contains(resource)) {
        continue;
      }
      ClaimRole claimRoleBeforeRelease = claimRoleForTrain(resource, trainName).orElse(null);
      String competingOwners = competingOwners(resource, trainName).toString();
      String queueHeadBeforeRelease = queueHeadFor(resource).orElse("-");
      boolean queueRetained =
          retainPendingWinner
              && occupancyManager.releaseResourceRetainingQueuePosition(
                  resource, Optional.of(trainName), request);
      if (!retainPendingWinner) {
        occupancyManager.releaseResource(resource, Optional.of(trainName));
      }
      traceRecoverableAuthorityRelease(
          trainName,
          resource,
          smartDecision,
          tokenState,
          destinationPresent,
          priorityResolution,
          request,
          claimRoleBeforeRelease,
          competingOwners,
          queueHeadBeforeRelease,
          queueRetained);
    }
  }

  private Optional<ClaimRole> claimRoleForTrain(OccupancyResource resource, String trainName) {
    if (resource == null || trainName == null || trainName.isBlank() || occupancyManager == null) {
      return Optional.empty();
    }
    for (OccupancyClaim claim : occupancyManager.snapshotClaims()) {
      if (claim == null
          || claim.resource() == null
          || !resource.equals(claim.resource())
          || !TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
        continue;
      }
      return Optional.ofNullable(claim.role());
    }
    return Optional.empty();
  }

  private List<String> competingOwners(OccupancyResource resource, String trainName) {
    if (resource == null || occupancyManager == null) {
      return List.of();
    }
    List<String> owners = new ArrayList<>();
    for (OccupancyClaim claim : occupancyManager.snapshotClaims()) {
      if (claim == null
          || claim.resource() == null
          || !resource.equals(claim.resource())
          || TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
        continue;
      }
      owners.add(claim.trainName() + ":" + claim.role());
    }
    return List.copyOf(owners);
  }

  private Optional<String> queueHeadFor(OccupancyResource resource) {
    if (resource == null || !(occupancyManager instanceof OccupancyQueueSupport queueSupport)) {
      return Optional.empty();
    }
    for (OccupancyQueueSnapshot snapshot : queueSupport.snapshotQueues()) {
      if (snapshot == null
          || !resource.equals(snapshot.resource())
          || snapshot.entries().isEmpty()) {
        continue;
      }
      OccupancyQueueEntry head = snapshot.entries().get(0);
      return Optional.of(head.trainName() + ":" + head.priority());
    }
    return Optional.empty();
  }

  private void traceRecoverableAuthorityRelease(
      String trainName,
      OccupancyResource resource,
      SmartSignalDecisionResult smartDecision,
      SignalComputationTrace.TokenState tokenState,
      boolean destinationPresent,
      DispatchPriorityResolution priorityResolution,
      OccupancyRequest request,
      ClaimRole claimRoleBeforeRelease,
      String competingOwners,
      String queueHeadBeforeRelease,
      boolean pendingWinnerInstalled) {
    int priority = priorityResolution == null ? request.priority() : priorityResolution.priority();
    String operationType =
        priorityResolution == null
            ? "-"
            : priorityResolution.operationType().map(Enum::name).orElse("-");
    debugLogger.accept(
        "SMART_RECOVERABLE_AUTHORITY_RELEASE train="
            + trainName
            + " resource="
            + resource
            + " recoverableHold=true riskSource="
            + (smartDecision == null ? RiskSource.NONE : smartDecision.riskSource())
            + " tokenState="
            + tokenState
            + " destinationPresent="
            + destinationPresent
            + " priority="
            + priority
            + " operationType="
            + operationType
            + " requestInputType="
            + SignalDecisionInputClassifier.classify(request)
            + " claimRoleBeforeRelease="
            + (claimRoleBeforeRelease == null ? "-" : claimRoleBeforeRelease)
            + " competingOwners="
            + (competingOwners == null || competingOwners.isBlank() ? List.of() : competingOwners)
            + " queueHeadBeforeRelease="
            + queueHeadBeforeRelease
            + " pendingWinnerInstalled="
            + pendingWinnerInstalled
            + " pendingWinnerOwner="
            + (pendingWinnerInstalled ? trainName : "-")
            + " pendingWinnerPriority="
            + (pendingWinnerInstalled ? priority : "-")
            + " releaseProtectedByInhibitor=false"
            + " releaseAction="
            + recoverableReleaseAction(claimRoleBeforeRelease, pendingWinnerInstalled));
  }

  private static String recoverableReleaseAction(
      ClaimRole claimRoleBeforeRelease, boolean pendingWinnerInstalled) {
    if (claimRoleBeforeRelease == null) {
      return "skipped";
    }
    return pendingWinnerInstalled ? "released-with-pending-winner" : "released-without-protection";
  }

  private void markRecoverableHoldForRetry(String trainName) {
    String key = normalizeTrainKey(trainName);
    if (!key.isEmpty()) {
      dirtyEventSignals.put(key, SignalAspect.STOP);
    }
  }

  private void invalidateMovementAuthorization(String trainName, HardStopReason reason) {
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return;
    }
    movementAuthorizationTokens.remove(key);
    movementInhibitors.put(key, reason == null ? HardStopReason.UNKNOWN : reason);
  }

  private static SignalComputationTrace.Source sourceForHardStop(HardStopReason reason) {
    if (reason == HardStopReason.EVENT_STOP) {
      return SignalComputationTrace.Source.EVENT;
    }
    if (reason == HardStopReason.DEADLOCK_CONFIRMED_WAITING) {
      return SignalComputationTrace.Source.HEALTH;
    }
    return SignalComputationTrace.Source.PERIODIC_TICK;
  }

  private boolean hasValidMovementAuthorization(
      String trainName, NodeId fromNode, NodeId toNode, List<OccupancyResource> resources) {
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty() || movementInhibitors.containsKey(key)) {
      return false;
    }
    MovementAuthorizationToken token = movementAuthorizationTokens.get(key);
    if (token == null) {
      return false;
    }
    if (!token.active()) {
      return false;
    }
    if (fromNode != null && token.fromNode() != null && !fromNode.equals(token.fromNode())) {
      return false;
    }
    if (toNode != null && token.toNode() != null && !toNode.equals(token.toNode())) {
      return false;
    }
    return resources == null || resources.isEmpty() || token.resources().containsAll(resources);
  }

  public boolean isMovementInhibited(String trainName) {
    String key = normalizeTrainKey(trainName);
    return !key.isEmpty() && movementInhibitors.containsKey(key);
  }

  /**
   * 将信号许可映射为速度/制动控制。
   *
   * <p>PROCEED 发车并恢复巡航速度；CAUTION 限速；STOP 按剩余距离制动并在停车点保持。
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
        ControlSpeedOverrides.empty());
  }

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
      ControlSpeedOverrides speedOverrides,
      StopControlMode stopMode) {
    applyControlInternal(
        train,
        properties,
        aspect,
        route,
        currentNode,
        nextNode,
        graph,
        allowLaunch,
        distanceOpt,
        lookahead,
        speedOverrides,
        stopMode);
  }

  /**
   * 将信号许可映射为速度/制动控制（含前瞻数据）。
   *
   * <p>PROCEED 发车并恢复巡航速度；CAUTION 限速；STOP 按剩余距离制动并在停车点保持。
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
      ControlSpeedOverrides speedOverrides) {
    applyControlInternal(
        train,
        properties,
        aspect,
        route,
        currentNode,
        nextNode,
        graph,
        allowLaunch,
        distanceOpt,
        lookahead,
        speedOverrides,
        StopControlMode.BRAKING_TO_PLANNED_STOP);
  }

  private void applyControlInternal(
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
      ControlSpeedOverrides speedOverrides,
      StopControlMode stopMode) {
    if (properties == null || aspect == null || route == null) {
      return;
    }
    String trainName = resolveTrackedTrainName(properties).orElse(properties.getTrainName());
    if (aspect != SignalAspect.STOP
        && !hasValidMovementAuthorization(trainName, currentNode, nextNode, List.of())) {
      applyHardStop(
          train,
          properties,
          trainName,
          HardStopReason.AUTHORIZATION_FAILURE,
          true,
          route,
          currentNode,
          nextNode,
          graph,
          new OccupancyDecision(
              false, Instant.now(), SignalAspect.STOP, List.of(), false, "movement-token-missing"),
          null,
          AuthorityEnd.none());
      return;
    }
    TrainConfig config = trainConfigResolver.resolve(properties, configManager.current());
    // 先获取边限速作为 PROCEED 基准（而非固定 defaultSpeed）
    double edgeLimit =
        resolveEdgeSpeedLimit(train, graph, currentNode, nextNode, configManager.current());
    TargetSpeedDecision speedDecision =
        resolveTargetSpeedDecision(
            train != null ? train.worldId() : null,
            aspect,
            nextNode,
            edgeLimit,
            config.decelBps2(),
            lookahead,
            speedOverrides);
    // 发车方向由 TrainCarts 依据 destination 自动推导（见 TrainCartsRuntimeHandle#launch），此处不写入额外 tag。
    java.util.Optional<org.bukkit.block.BlockFace> launchFallbackDirection =
        resolveLaunchDirectionByGraph(graph, currentNode, nextNode);
    TrainLaunchManager.ControlApplicationResult applicationResult =
        runtimeTrainController.applyControl(
            train,
            properties,
            aspect,
            speedDecision.targetBps(),
            config,
            allowLaunch,
            distanceOpt,
            launchFallbackDirection,
            configManager.current().runtimeSettings(),
            stopMode);

    // 记录诊断数据
    recordDiagnostics(
        train,
        properties,
        aspect,
        route,
        currentNode,
        nextNode,
        speedDecision,
        applicationResult,
        allowLaunch,
        lookahead,
        speedOverrides);
  }

  /** 记录控车诊断数据到缓存。 */
  private void recordDiagnostics(
      RuntimeTrainHandle train,
      TrainProperties properties,
      SignalAspect aspect,
      RouteDefinition route,
      NodeId currentNode,
      NodeId nextNode,
      TargetSpeedDecision speedDecision,
      TrainLaunchManager.ControlApplicationResult applicationResult,
      boolean allowLaunch,
      SignalLookahead.LookaheadResult lookahead,
      ControlSpeedOverrides speedOverrides) {
    if (properties == null) {
      return;
    }
    String trainName = properties.getTrainName();
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    int progressIndex =
        progressRegistry
            .get(trainName)
            .map(RouteProgressRegistry.RouteProgressEntry::currentIndex)
            .orElse(-1);
    DepartureGate departureGate = departureGates.get(normalizeTrainKey(trainName));
    Instant now = Instant.now();
    double currentSpeedBps =
        train != null ? train.currentSpeedBlocksPerTick() * SPEED_TICKS_PER_SECOND : 0.0;
    TargetSpeedDecision decision =
        speedDecision != null
            ? speedDecision
            : new TargetSpeedDecision(
                -1.0,
                0.0,
                "none",
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                0.0,
                "none");
    TrainLaunchManager.ControlApplicationResult result =
        applicationResult != null
            ? applicationResult
            : new TrainLaunchManager.ControlApplicationResult(
                decision.targetBps(), OptionalDouble.empty(), decision.targetBps(), "none");
    String finalLimiterSource =
        "none".equals(result.finalLimiterSource())
            ? decision.limiterSource()
            : result.finalLimiterSource();
    ControlSpeedOverrides overrides =
        speedOverrides != null ? speedOverrides : ControlSpeedOverrides.empty();
    ApproachControl approach = overrides.approachControl();
    AuthorityEnd authorityEnd = overrides.authorityEnd();
    BlockedDestinationDiagnostic blockedDestination = overrides.blockedDestination();

    var builder =
        new ControlDiagnostics.Builder()
            .trainName(trainName)
            .routeId(route != null ? route.id() : null)
            .currentNode(currentNode)
            .nextNode(nextNode)
            .currentIndex(progressIndex)
            .departureGate(formatDepartureGate(departureGate))
            .signalReason(
                resolveSignalReason(aspect, allowLaunch, departureGate, overrides.debugResources()))
            .currentSpeedBps(currentSpeedBps)
            .targetSpeedBps(result.finalTargetBps())
            .edgeLimitBps(decision.edgeLimitBps())
            .aspectBaseSpeedBps(decision.aspectBaseSpeedBps())
            .cautionSource(decision.cautionSource())
            .approachLimitBps(decision.approachLimitBps())
            .movementAuthorityLimitBps(decision.movementAuthorityLimitBps())
            .edgeSpeedLookaheadMinBps(decision.edgeSpeedLookaheadMinBps())
            .speedCurveLimitBps(result.speedCurveLimitBps())
            .finalTargetBps(result.finalTargetBps())
            .finalLimiterSource(finalLimiterSource)
            .approachNode(approach.node().orElse(null))
            .approachKind(approach.kind())
            .approachReason(approach.reason())
            .distanceToAuthorityEnd(authorityEnd.distanceBlocks())
            .authorityEndResource(authorityEnd.resource())
            .authorizedEdgeCount(authorityEnd.authorizedEdgeCount())
            .currentSignal(aspect)
            .allowLaunch(allowLaunch)
            .destinationPresentWhileBlocked(blockedDestination.destinationPresentWhileBlocked())
            .retainedDestination(blockedDestination.retainedDestination())
            .blockedReason(blockedDestination.blockedReason())
            .signalBlockerResources(overrides.debugResources().blockers())
            .requestResources(overrides.debugResources().requestResources())
            .currentClaimsForTrain(overrides.debugResources().currentClaims())
            .sampledAt(now);

    if (lookahead != null) {
      builder.lookahead(lookahead);
    } else {
      builder.effectiveSignal(aspect);
    }
    if (approach.distanceBlocks().isPresent()) {
      builder.distanceToApproach(approach.distanceBlocks());
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
    return shortestPathDistanceCache.resolve(graph, from, to);
  }

  /**
   * 计算 STOP 控车使用的剩余停车距离。
   *
   * <p>lookahead 给出的 blocker 距离以“当前图节点”为起点；列车已经进入当前边后，该值可能不会随物理位置连续缩短。这里用 TrainCarts railState
   * 与图节点坐标估算到下一节点的剩余距离，再与 blocker 距离取更保守值，保证 STOP 曲线会向 0 收敛并在下一节点前停下。
   */
  private OptionalLong resolveStopControlDistance(
      RuntimeTrainHandle train,
      RailGraph graph,
      NodeId currentNode,
      NodeId nextNode,
      OptionalLong blockerDistance) {
    OptionalLong remainingToNext =
        resolveRemainingDistanceToNode(train, graph, currentNode, nextNode);
    if (blockerDistance != null && blockerDistance.isPresent()) {
      if (remainingToNext.isPresent()) {
        return OptionalLong.of(Math.min(blockerDistance.getAsLong(), remainingToNext.getAsLong()));
      }
      return blockerDistance;
    }
    return remainingToNext;
  }

  private OptionalLong resolveRemainingDistanceToNode(
      RuntimeTrainHandle train, RailGraph graph, NodeId currentNode, NodeId nextNode) {
    if (graph == null || currentNode == null || nextNode == null) {
      return OptionalLong.empty();
    }
    Optional<RailGraphPath> pathOpt =
        pathFinder.shortestPath(
            graph, currentNode, nextNode, RailGraphPathFinder.Options.shortestDistance());
    if (pathOpt.isEmpty()) {
      return resolveShortestDistance(graph, currentNode, nextNode);
    }
    RailGraphPath path = pathOpt.get();
    if (path.edges().isEmpty()) {
      return OptionalLong.of(0L);
    }
    RailEdge firstEdge = path.edges().get(0);
    NodeId firstTarget = path.nodes().size() > 1 ? path.nodes().get(1) : nextNode;
    TrainPositionResolver.PositionResult position =
        TrainPositionResolver.resolve(
            train, graph, currentNode, firstTarget, firstEdge.lengthBlocks());
    if (position.distanceToNextBlocks().isEmpty()) {
      return OptionalLong.of(path.totalLengthBlocks());
    }
    long remaining =
        TrainPositionResolver.totalRemainingDistance(
            position.distanceToNextBlocks().getAsLong(),
            path.edges().size() > 1 ? path.edges().subList(1, path.edges().size()) : List.of());
    return OptionalLong.of(Math.max(0L, remaining));
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
        new WaypointStopState(sessionId, nodeId, Instant.now(), dwellSeconds, false);
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
      // 标记居中阶段：防止 handleSignalTick 在居中期间下发 speedLimit=0 + stop，阻止居中移动
      WaypointStopState prev = waypointStopStates.get(key);
      if (prev != null && sessionId.equals(prev.sessionId())) {
        waypointStopStates.put(
            key, new WaypointStopState(sessionId, nodeId, prev.createdAt(), dwellSeconds, true));
      }

      // 恢复速度限制：signal tick 遗留的 speedLimit=0 会阻止 Station.centerTrain() 的移动动作
      com.bergerkiller.bukkit.tc.properties.TrainProperties props = group.getProperties();
      if (props != null) {
        runtimeTrainController.setTemporarySpeedLimit(props, WAYPOINT_CENTER_SPEED_LIMIT);
      }

      // 关键：强制按 group 语义执行 centerTrain，避免某些事件上下文被识别为 cart sign 后只按单车居中。
      SignActionEvent centerEvent = adaptForGroupCenter(event, group);
      com.bergerkiller.bukkit.tc.Station station =
          new com.bergerkiller.bukkit.tc.Station(centerEvent);
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
              + reason
              + " signMode="
              + (event.isTrainSign() ? "train" : event.isCartSign() ? "cart" : "unknown")
              + " centeredAs="
              + (centerEvent.isTrainSign() ? "train" : "cart"));

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

  private boolean isProtectiveOnlyStop(OccupancyDecision decision) {
    if (decision == null || decision.allowed() || decision.blockers().isEmpty()) {
      return false;
    }
    for (OccupancyClaim blocker : decision.blockers()) {
      if (blocker == null) {
        continue;
      }
      if (blocker.role() != ClaimRole.PROTECTIVE_RETAIN && blocker.role() != ClaimRole.HOLD_ONLY) {
        return false;
      }
    }
    return true;
  }

  /**
   * protective-only blocker 的保守停车。
   *
   * <p>这类 blocker 需要在后续 tick 重新评估或进入 stale release 候选，但它不是 token/destination 本身失效；因此这里只发布 STOP
   * 与控车，不清 destination，也不把 movement token 标记为 INVALID。
   */
  private void applyProtectiveOnlyStop(
      RuntimeTrainHandle train,
      TrainProperties properties,
      String trainName,
      RouteDefinition route,
      NodeId currentNode,
      NodeId nextNode,
      OccupancyDecision decision) {
    Instant now = Instant.now();
    updateBlockerSnapshot(trainName, decision, now);
    boolean releaseCandidate =
        occupancyManager instanceof SimpleOccupancyManager manager
            && manager.selfOwnedStaleRetainReleaseCandidate(trainName).isPresent();
    debugLogger.accept(
        "STALE_PROTECTIVE_RETAIN_CANDIDATE train="
            + trainName
            + " blockerHardness=PROTECTIVE_ONLY releaseCandidate="
            + releaseCandidate
            + " releaseBlockedReason="
            + (releaseCandidate ? "not-yet-mutating" : "no-self-retain-candidate")
            + " blockers="
            + summarizeBlockers(decision));
    updateSignalOrWarnPreservingPublishedCaution(
        trainName, SignalAspect.STOP, now, "protective-only-stop");
    if (train != null
        && properties != null
        && route != null
        && currentNode != null
        && nextNode != null) {
      applyControl(
          train,
          properties,
          SignalAspect.STOP,
          route,
          currentNode,
          nextNode,
          null,
          false,
          OptionalLong.empty());
    }
  }

  /**
   * 生成“组居中专用”的 SignActionEvent。
   *
   * <p>Waypoint STOP/TERM 的目标是让整个 MinecartGroup 以牌子中心对齐；若事件在某些上下文被视为 cart sign，TrainCarts 的
   * Station.centerTrain() 会退化为单车居中。这里通过事件包装强制走 train-sign 分支，保证按整列车计算中心。
   *
   * @param original 原始牌子事件
   * @param group 当前列车组
   * @return 强制 train-sign 语义的事件视图
   */
  private static SignActionEvent adaptForGroupCenter(
      SignActionEvent original, com.bergerkiller.bukkit.tc.controller.MinecartGroup group) {
    if (original.getTrackedSign() != null) {
      return new GroupCenterSignActionEvent(original.getTrackedSign(), group, original.getAction());
    }
    // 无 tracked sign 时避免使用已弃用的 Block 构造器，回退原事件语义。
    return original;
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
   * 强制“train sign 语义”的事件包装。
   *
   * <p>只用于 waypoint 居中流程，避免影响其他调度逻辑。
   */
  private static final class GroupCenterSignActionEvent extends SignActionEvent {

    GroupCenterSignActionEvent(
        com.bergerkiller.bukkit.tc.rails.RailLookup.TrackedSign sign,
        com.bergerkiller.bukkit.tc.controller.MinecartGroup group,
        SignActionType actionType) {
      super(sign, group);
      if (actionType != null) {
        setAction(actionType);
      }
    }

    @Override
    public boolean isCartSign() {
      return false;
    }

    @Override
    public boolean isTrainSign() {
      return true;
    }
  }

  private static String formatOptionalLong(OptionalLong value) {
    return value != null && value.isPresent() ? String.valueOf(value.getAsLong()) : "-";
  }

  private static String formatApproachDouble(double value) {
    return Double.isFinite(value) ? String.format(Locale.ROOT, "%.3f", value) : "-";
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
      SignalAspect aspect,
      RouteDefinition route,
      NodeId currentNode,
      NodeId nextNode,
      RailGraph graph,
      OptionalLong distanceOpt) {
    if (train == null
        || properties == null
        || trainName == null
        || trainName.isBlank()
        || aspect == null
        || aspect == SignalAspect.STOP) {
      return;
    }
    String destination = resolveDestinationName(nextNode);
    if (destination == null || destination.isBlank()) {
      return;
    }
    properties.clearDestinationRoute();
    properties.setDestination(destination);
    applyControl(train, properties, aspect, route, currentNode, nextNode, graph, true, distanceOpt);
    debugLogger.accept(
        "调度 failover: 低速按已授权信号重下发 destination train="
            + trainName
            + " signal="
            + aspect
            + " dest="
            + destination);
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
  /**
   * 执行列车调度销毁：清理运行时状态并触发实体销毁。
   *
   * <p>不在此处释放占用——{@code train.destroy()} 延迟 1 tick 执行物理销毁，若同步释放占用，{@code SpawnMonitor} 可能在物理销毁前
   * acquire 并 spawn 新车导致撞车。占用将由 {@code GroupRemoveEvent → handleTrainRemoved} 在实体实际销毁后释放。
   *
   * <p>清理范围与 {@link #handleTrainRemoved} 保持一致（除占用释放外），避免残留缓存数据。
   *
   * @param train 运行时句柄（可为空，此时仅清理缓存不销毁实体）
   * @param properties 列车属性（用于清理 tag）
   * @param trainName 列车名
   * @param reason 销毁原因（用于日志）
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
    progressRegistry.remove(trainName);
    clearRuntimeCachesForTrain(trainName);
    TrainTagHelper.removeTagKey(properties, RouteProgressRegistry.TAG_ROUTE_INDEX);
    TrainTagHelper.removeTagKey(properties, RouteProgressRegistry.TAG_ROUTE_UPDATED_AT);
    debugLogger.accept("调度销毁: reason=" + reason + " train=" + trainName);
  }

  /**
   * 信号未变化但列车在 proceed-like 信号下已经物理静止时，仍需要重新下发 launch。
   *
   * <p>“aspect 未变化则跳过 launch”只用于避免对运动中的列车重复发车；若上一次 launch 被冷却窗口或停车竞态吞掉， 列车会停在绿灯下，不在这里补发就要等 stall
   * failover（failover-stall-ticks 个 dispatch tick）或健康监控兜底， 实测表现为前车已驶离、后车却以 PROCEED 静止数分钟。停站
   * dwell、发车门控与 movement inhibitor 中的静止是计划行为，不在补发范围内；layover 列车在进入本路径前已经返回。
   */
  private boolean shouldRelaunchStationaryProceedTrain(
      RuntimeTrainHandle train, String trainName, SignalAspect aspect) {
    if (train == null || !SignalDecisionInputClassifier.isProceedLike(aspect)) {
      return false;
    }
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty() || movementInhibitors.containsKey(key) || departureGates.containsKey(key)) {
      return false;
    }
    if (dwellRegistry != null && dwellRegistry.remainingSeconds(trainName).isPresent()) {
      return false;
    }
    double speedBps = train.currentSpeedBlocksPerTick() * SPEED_TICKS_PER_SECOND;
    double stallSpeedBps = configManager.current().runtimeSettings().failoverStallSpeedBps();
    return speedBps <= Math.max(0.01, stallSpeedBps);
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
   * 判断当前节点是否为 DSTY（销毁目标）。
   *
   * @param stop 当前 RouteStop
   * @param currentNode 当前节点
   * @return 是否应销毁
   */
  private boolean shouldDestroyAt(RouteStop stop, NodeId currentNode) {
    return routeStopActionResolver.shouldDestroyAt(stop, currentNode);
  }

  /**
   * DSTY 兜底判定：当 stop 未携带指令 notes 时，允许“最后一站 + depot + PASS”触发销毁。
   *
   * <p>用于兼容历史数据中 DSTY notes 缺失的情况，避免 RETURN 线路无法回收。
   */
  private boolean shouldDestroyAtFallback(
      RouteStop stop, int currentIndex, RouteDefinition route, SignNodeDefinition definition) {
    return routeStopActionResolver.shouldDestroyAtFallback(stop, currentIndex, route, definition);
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
        readFirstTag(properties, RouteProgressRegistry.TAG_OPERATOR_CODE, "FTA_OPERATOR");
    Optional<String> lineTag =
        readFirstTag(properties, RouteProgressRegistry.TAG_LINE_CODE, "FTA_LINE");
    Optional<String> routeTag =
        readFirstTag(properties, RouteProgressRegistry.TAG_ROUTE_CODE, "FTA_ROUTE");
    return (operatorTag.isPresent() && !operatorTag.get().isBlank())
        || (lineTag.isPresent() && !lineTag.get().isBlank())
        || (routeTag.isPresent() && !routeTag.get().isBlank());
  }

  /**
   * 判断 TrainProperties 是否携带任意 FTA 运行时标签。
   *
   * <p>异常清理比常规 dispatch 更宽：split 事件中残编可能只保留 {@code FTA_TRAIN_NAME} 或 {@code FTA_ROUTE_INDEX}，仍应视为
   * FTA 残余实体并清理整列；常规信号控制则继续使用 {@link #isFtaManagedTrain(TrainProperties)}，要求具备可解析的交路标签。
   *
   * @param properties TrainCarts 列车属性
   * @return true 表示该列车曾由 FTA 写入过运行时标签
   */
  boolean hasFtaRuntimeTag(TrainProperties properties) {
    if (properties == null) {
      return false;
    }
    if (isFtaManagedTrain(properties)) {
      return true;
    }
    if (TrainTagHelper.readIntTag(properties, RouteProgressRegistry.TAG_ROUTE_INDEX).isPresent()) {
      return true;
    }
    return TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_TRAIN_NAME)
        .filter(value -> !value.isBlank())
        .isPresent();
  }

  /**
   * 判断普通 TrainCarts 列车是否允许进入异常实体销毁。
   *
   * <p>非 FTA 列车的 split/member-remove 语义不可靠，只有 TrainCarts 状态明确为 derailed 时才按安全兜底销毁。
   */
  static boolean shouldCleanupUnmanagedAbnormalReason(String normalizedReason) {
    return ABNORMAL_REASON_STATUS_DERAILED.equals(normalizedReason);
  }

  /**
   * 解析异常编组清理策略。
   *
   * <p>抽成纯逻辑，避免在单测中直接构造 TrainCarts {@link MinecartGroup}。策略含义：
   *
   * <ul>
   *   <li>FTA runtime tag 存在：清理运行时状态并销毁实体
   *   <li>非 FTA 且明确 derailed：只销毁实体，不触碰 FTA progress/occupancy
   *   <li>非 FTA split/member-remove：跳过，避免误伤普通 TrainCarts 列车
   * </ul>
   */
  static AbnormalCleanupPolicy resolveAbnormalCleanupPolicy(
      boolean hasFtaRuntimeTag, String normalizedReason) {
    if (hasFtaRuntimeTag) {
      return new AbnormalCleanupPolicy(true, true, true);
    }
    if (shouldCleanupUnmanagedAbnormalReason(normalizedReason)) {
      return new AbnormalCleanupPolicy(true, false, true);
    }
    return new AbnormalCleanupPolicy(false, false, false);
  }

  private boolean matchesDstyTarget(RouteDefinition route, NodeId currentNode) {
    if (route == null || currentNode == null) {
      return false;
    }
    List<RouteStop> stops = routeDefinitions.listStops(route.id());
    return routeStopActionResolver.routeMatchesDstyTarget(stops, currentNode);
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
    Optional<WaypointKind> kindOpt = parseWaypointKind(nodeId);
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
    Optional<RouteStopActionResolver.ChangeIntent> intentOpt =
        routeStopActionResolver.changeIntent(stop);
    if (intentOpt.isEmpty()) {
      return false;
    }
    RouteStopActionResolver.ChangeIntent intent = intentOpt.get();
    if (!intent.valid()) {
      debugLogger.accept(
          "CHANGE 解析失败: reason="
              + intent.reason()
              + " train="
              + trainName
              + " raw="
              + intent.raw());
      return false;
    }

    // 仅更新 operator/line tags，不改变 route
    TrainTagHelper.writeTag(
        properties, RouteProgressRegistry.TAG_OPERATOR_CODE, intent.operatorCode());
    TrainTagHelper.writeTag(properties, RouteProgressRegistry.TAG_LINE_CODE, intent.lineCode());

    debugLogger.accept(
        "CHANGE 移交成功: train="
            + trainName
            + " newOp="
            + intent.operatorCode()
            + " newLine="
            + intent.lineCode());
    return true;
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
    return routeStopActionResolver.dstyDynamicDepotSpec(stops);
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
    // DYNAMIC materialize 例外：这里仅把已选车库写给 TrainCarts 寻路，发车/控车不在此处分发。
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

  /**
   * 解析紧邻 RouteStop 的 DYNAMIC 目标。
   *
   * <p>只有当前 stop 不声明 DYNAMIC 时返回 {@code NOT_APPLICABLE}。声明存在但格式无效、没有空闲可达候选时返回 {@code
   * BLOCKED}，调用方不得回退到 route 声明占位节点。
   */
  private DynamicResolution<NodeId> resolveDynamicStationTargetIfNeeded(
      String trainName, RouteDefinition route, int targetIndex, NodeId fromNode, RailGraph graph) {
    if (trainName == null
        || trainName.isBlank()
        || route == null
        || fromNode == null
        || graph == null
        || targetIndex < 0) {
      return DynamicResolution.notApplicable("invalid-dynamic-target-context");
    }
    Optional<RouteStop> stopOpt = routeDefinitions.findStop(route.id(), targetIndex);
    if (stopOpt.isEmpty()) {
      return DynamicResolution.notApplicable("route-stop-missing");
    }
    Optional<String> remainder = routeStopActionResolver.dynamicTarget(stopOpt.get());
    if (remainder.isEmpty()) {
      return DynamicStopMatcher.isDynamicStop(stopOpt.get())
          ? DynamicResolution.blocked("dynamic-stop-invalid")
          : DynamicResolution.notApplicable("route-stop-not-dynamic");
    }
    Optional<DynamicStopSpec> specOpt = parseDynamicStopSpec(remainder.get());
    if (specOpt.isEmpty()) {
      debugLogger.accept(
          "DYNAMIC 解析失败: train=" + trainName + " idx=" + targetIndex + " raw=" + remainder.get());
      return DynamicResolution.blocked("dynamic-stop-invalid");
    }
    DynamicStopSpec spec = specOpt.get();
    return selectDynamicStationTarget(trainName, fromNode, graph, spec)
        .<DynamicResolution<NodeId>>map(DynamicResolution::selected)
        .orElseGet(() -> DynamicResolution.blocked("no-available-dynamic-target"));
  }

  /**
   * 选择 DYNAMIC 站台/车库目标。
   *
   * <p>选择规则：
   *
   * <ol>
   *   <li>优先选择空闲且可达的轨道（按轨道号顺序）
   *   <li>若无空闲候选，不 materialize 到已占用站台
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

    debugLogger.accept(
        "DYNAMIC 失败: 未找到空闲可达站台 train="
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
   * 推进点专用：为“下一站 DYNAMIC stop”选择一个空闲且可达的站台，并返回对应的 OccupancyRequestContext。
   *
   * <p>先筛选 NODE 资源空闲且图上可达的站台；只读占用预判仅用于在多个候选中优先选择当前可进入项，不决定站台是否有容量。
   *
   * <p>实现上为每个空闲且可达候选构建一次 lookahead 请求。若至少一个候选当前可进入，优先在这些候选中按方向选台；若全部仅因临时咽喉/队列冲突被拒绝，仍 materialize
   * 一个站台并把等待交给普通授权链，避免把“有容量但进路繁忙”误当成“容量耗尽”而持续撤队。
   */
  private DynamicResolution<DynamicSelection> selectDynamicStationTargetForProgress(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      int targetIndex,
      NodeId fromNode,
      RailGraph graph,
      OccupancyRequestBuilder builder,
      Instant now,
      int priority,
      AuthorizationPurpose purpose) {
    if (trainName == null
        || trainName.isBlank()
        || route == null
        || fromNode == null
        || graph == null
        || builder == null) {
      return DynamicResolution.notApplicable("invalid-dynamic-selection-context");
    }
    Optional<RouteStop> stopOpt = routeDefinitions.findStop(route.id(), targetIndex);
    if (stopOpt.isEmpty()) {
      return DynamicResolution.notApplicable("route-stop-missing");
    }
    Optional<String> remainder = routeStopActionResolver.dynamicTarget(stopOpt.get());
    if (remainder.isEmpty()) {
      return DynamicStopMatcher.isDynamicStop(stopOpt.get())
          ? DynamicResolution.blocked("dynamic-stop-invalid")
          : DynamicResolution.notApplicable("route-stop-not-dynamic");
    }
    if (occupancyManager == null) {
      return DynamicResolution.blocked("occupancy-manager-unavailable");
    }
    Optional<DynamicStopSpec> specOpt = parseDynamicStopSpec(remainder.get());
    if (specOpt.isEmpty()) {
      debugLogger.accept(
          "DYNAMIC 解析失败: train=" + trainName + " idx=" + targetIndex + " raw=" + remainder.get());
      return DynamicResolution.blocked("dynamic-stop-invalid");
    }
    DynamicStopSpec spec = specOpt.get();
    List<NodeId> baseNodes = resolveEffectiveWaypoints(trainName, route);
    if (targetIndex < 0 || targetIndex >= baseNodes.size()) {
      return DynamicResolution.blocked("dynamic-target-index-out-of-range");
    }

    Optional<DynamicSelection> selection =
        selectDynamicStationTargetForProgressCandidate(
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
            purpose);
    return selection
        .<DynamicResolution<DynamicSelection>>map(DynamicResolution::selected)
        .orElseGet(() -> DynamicResolution.blocked("no-available-dynamic-target"));
  }

  private Optional<DynamicSelection> selectDynamicStationTargetForProgressCandidate(
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
      AuthorizationPurpose purpose) {
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
      if (!isNodeFree(trainName, candidate)) {
        continue;
      }
      if (resolveShortestDistance(graph, fromNode, candidate).isEmpty()) {
        continue;
      }
      List<NodeId> nodes = new java.util.ArrayList<>(baseNodes);
      nodes.set(targetIndex, candidate);
      Optional<OccupancyRequestContext> ctxOpt =
          buildContextWithinDynamicBoundary(
              builder,
              trainName,
              route,
              nodes,
              nodes,
              currentIndex,
              now,
              priority,
              purpose,
              OptionalInt.of(targetIndex));
      if (ctxOpt.isEmpty()) {
        continue;
      }
      OccupancyRequest request = ctxOpt.get().request();
      candidates.add(
          new DynamicCandidate(candidate, ctxOpt.get(), previewOccupancyDecision(request)));
    }

    if (candidates.isEmpty()) {
      debugLogger.accept(
          "DYNAMIC 失败: 无空闲可达站台 train="
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

    List<DynamicCandidate> preferredCandidates =
        candidates.stream().filter(candidate -> candidate.decision.allowed()).toList();
    List<DynamicCandidate> selectionPool =
        preferredCandidates.isEmpty() ? candidates : preferredCandidates;
    if (selectionPool.size() == 1) {
      DynamicCandidate single = selectionPool.get(0);
      return Optional.of(new DynamicSelection(single.candidate, single.context, single.decision));
    }

    // 多个候选时，按方向优选
    DynamicCandidate best =
        selectBestCandidateByDirection(
            trainName, fromNode, baseNodes, currentIndex, selectionPool, graph);

    if (best != null) {
      return Optional.of(new DynamicSelection(best.candidate, best.context, best.decision));
    }

    // 兜底：返回第一个候选
    DynamicCandidate fallback = selectionPool.get(0);
    return Optional.of(
        new DynamicSelection(fallback.candidate, fallback.context, fallback.decision));
  }

  /** DYNAMIC 候选站台记录。 */
  private record DynamicCandidate(
      NodeId candidate, OccupancyRequestContext context, OccupancyDecision decision) {}

  /**
   * 只读占用预判。
   *
   * <p>候选站台筛选会连续尝试多个候选目标，未被选中的候选不能写入冲突队列。否则一次 DYNAMIC 选择就可能把列车排进多个无关站台/道岔队列，后续信号 tick
   * 会看到“没有真实占用但队列阻塞”的异常 STOP。
   */
  private OccupancyDecision previewOccupancyDecision(OccupancyRequest request) {
    if (occupancyManager instanceof OccupancyPreviewSupport preview) {
      return preview.canEnterPreview(request);
    }
    Instant now = request == null ? Instant.now() : request.now();
    return new OccupancyDecision(
        false, now, SignalAspect.STOP, List.of(), false, "preview-support-missing");
  }

  /**
   * 只读前瞻判定。
   *
   * <p>advisory lookahead 不能把列车写入远端冲突队列；如果当前占用实现没有提供 preview 接口，调用方继续使用已经完成的硬授权结果作为兜底， 而不是退化为带副作用的
   * {@code canEnter}。
   */
  private OccupancyDecision previewOccupancyDecisionReadOnly(
      OccupancyRequest request, OccupancyDecision fallback) {
    if (occupancyManager instanceof OccupancyPreviewSupport preview) {
      return preview.canEnterPreview(request);
    }
    return fallback != null
        ? fallback
        : new OccupancyDecision(true, Instant.now(), SignalAspect.PROCEED, List.of());
  }

  private OccupancyDecision singleZoneBlockedDecision(
      OccupancyRequest request, String reason, Instant now) {
    OccupancyDecision fallback =
        new OccupancyDecision(
            false, now == null ? Instant.now() : now, SignalAspect.STOP, List.of(), false, reason);
    OccupancyDecision preview = previewOccupancyDecisionReadOnly(request, fallback);
    if (preview == null || preview.blockers().isEmpty()) {
      return fallback;
    }
    return new OccupancyDecision(
        false,
        request == null ? fallback.earliestTime() : request.now(),
        SignalAspect.STOP,
        preview.blockers(),
        false,
        reason);
  }

  /**
   * 将完整前瞻窗口改写为 advisory 语义。
   *
   * <p>该请求仍保留 expanded path、方向与入口序号，供距离和风险定位使用；但所有资源均标记为 {@link
   * ResourceIntent#LOOKAHEAD_PREVIEW}，因此不得参与 hard blocker、acquire 或 queue 写入。
   */
  private OccupancyRequestContext toAdvisoryLookaheadContext(OccupancyRequestContext context) {
    if (context == null || context.request() == null) {
      return context;
    }
    OccupancyRequest request = toAdvisoryLookaheadRequest(context.request());
    return new OccupancyRequestContext(request, context.pathNodes(), context.edges());
  }

  private OccupancyRequest toAdvisoryLookaheadRequest(OccupancyRequest request) {
    if (request == null) {
      return null;
    }
    OccupancyRequest advisory = request.asLookaheadPreview();
    debugLogger.accept(
        "LOOKAHEAD_PREVIEW_REQUEST train="
            + request.trainName()
            + " resourceCount="
            + advisory.resourceList().size());
    for (OccupancyResource resource : advisory.resourceList()) {
      SignalComputationTrace.emitRaw(
          "ADVISORY_LOOKAHEAD_RESOURCE train=" + request.trainName() + " resource=" + resource,
          debugLogger);
    }
    return advisory;
  }

  private AdvisoryPreviewResult previewAdvisoryLookaheadReadOnly(
      OccupancyRequest advisoryRequest,
      OccupancyDecision fallback,
      OccupancyRequest authorizationRequest,
      OccupancyDecision authorizationDecision) {
    if (advisoryRequest == null) {
      return new AdvisoryPreviewResult(
          fallback != null
              ? fallback
              : new OccupancyDecision(true, Instant.now(), SignalAspect.PROCEED, List.of()),
          List.of());
    }
    if (occupancyManager instanceof OccupancyAdvisoryPreviewSupport advisoryPreview) {
      List<AdvisoryRisk> risks =
          suppressReleasedSwitcherAdvisoryRisks(
              advisoryPreview.scanAdvisoryRisks(advisoryRequest),
              authorizationRequest,
              authorizationDecision);
      if (!risks.isEmpty()) {
        List<OccupancyClaim> blockers =
            risks.stream().map(AdvisoryRisk::claim).filter(Objects::nonNull).toList();
        return new AdvisoryPreviewResult(
            new OccupancyDecision(
                true,
                advisoryRequest.now(),
                SignalAspect.PROCEED_WITH_CAUTION,
                blockers,
                false,
                "advisory-risk"),
            risks);
      }
      debugLogger.accept(
          "ADVISORY_CAUTION_SKIPPED train="
              + advisoryRequest.trainName()
              + " reason=no-advisory-risk");
      return new AdvisoryPreviewResult(
          new OccupancyDecision(
              true, advisoryRequest.now(), SignalAspect.PROCEED, List.of(), false, "none"),
          List.of());
    }
    OccupancyDecision decision = previewOccupancyDecisionReadOnly(advisoryRequest, fallback);
    List<OccupancyClaim> blockers =
        suppressReleasedSwitcherAdvisoryBlockers(
            decision == null ? List.of() : decision.blockers(),
            authorizationRequest,
            authorizationDecision);
    if (decision != null && !decision.allowed() && !blockers.isEmpty()) {
      return new AdvisoryPreviewResult(
          new OccupancyDecision(
              true,
              advisoryRequest.now(),
              SignalAspect.PROCEED_WITH_CAUTION,
              blockers,
              false,
              "advisory-risk"),
          List.of());
    }
    if (decision != null && !decision.allowed() && decision.blockers().size() != blockers.size()) {
      return new AdvisoryPreviewResult(
          new OccupancyDecision(
              true, advisoryRequest.now(), SignalAspect.PROCEED, List.of(), false, "none"),
          List.of());
    }
    return new AdvisoryPreviewResult(decision, List.of());
  }

  /**
   * 过滤本 tick 已验证清空资格的同 key switcher 抽象 advisory 风险。
   *
   * <p>只移除 {@link ConflictClearingEvidenceKind#VERIFIED_SWITCHER_OCCUPANT} 对应的
   * CONFLICT；NODE、EDGE、single 与其他 switcher 仍会进入 SignalLookahead 和 Movement Authority，确保 drain
   * leader 只能驶出当前道口。
   */
  private List<AdvisoryRisk> suppressReleasedSwitcherAdvisoryRisks(
      List<AdvisoryRisk> risks,
      OccupancyRequest authorizationRequest,
      OccupancyDecision authorizationDecision) {
    if (risks == null || risks.isEmpty()) {
      return List.of();
    }
    Set<String> released =
        releasedSwitcherConflictKeys(authorizationRequest, authorizationDecision);
    if (released.isEmpty()) {
      return List.copyOf(risks);
    }
    List<AdvisoryRisk> retained = new ArrayList<>();
    for (AdvisoryRisk risk : risks) {
      if (risk != null && releasedSwitcherConflict(risk.resource(), released)) {
        traceSuppressedSwitcherAdvisoryRisk(
            authorizationRequest.trainName(), risk.resource(), risk.claim(), risk.source().name());
        continue;
      }
      if (risk != null) {
        retained.add(risk);
      }
    }
    return List.copyOf(retained);
  }

  /** 为不支持结构化 advisory risk 的兼容实现应用同一 exact-key 过滤规则。 */
  private List<OccupancyClaim> suppressReleasedSwitcherAdvisoryBlockers(
      List<OccupancyClaim> blockers,
      OccupancyRequest authorizationRequest,
      OccupancyDecision authorizationDecision) {
    if (blockers == null || blockers.isEmpty()) {
      return List.of();
    }
    Set<String> released =
        releasedSwitcherConflictKeys(authorizationRequest, authorizationDecision);
    if (released.isEmpty()) {
      return List.copyOf(blockers);
    }
    List<OccupancyClaim> retained = new ArrayList<>();
    for (OccupancyClaim blocker : blockers) {
      if (blocker != null && releasedSwitcherConflict(blocker.resource(), released)) {
        traceSuppressedSwitcherAdvisoryRisk(
            authorizationRequest.trainName(), blocker.resource(), blocker, "LEGACY_ADVISORY");
        continue;
      }
      if (blocker != null) {
        retained.add(blocker);
      }
    }
    return List.copyOf(retained);
  }

  /**
   * 返回本次 advisory 可忽略的实体 switcher keys。
   *
   * <p>正常路径要求 hard decision 已成为 release leader。若竞争 claim 在 hard decision 之后才出现，则允许使用本次请求形成时已经验证的实体
   * switcher occupant 证据；该证据仍必须属于 CONFLICT_CLEARING、fresh movement snapshot 与匹配 zone，并且只放宽同 key
   * switcher 抽象风险。
   */
  private static Set<String> releasedSwitcherConflictKeys(
      OccupancyRequest request, OccupancyDecision decision) {
    if (request == null
        || decision == null
        || !decision.allowed()
        || request.purpose() != AuthorizationPurpose.CONFLICT_CLEARING
        || !hasFreshMovementSnapshot(request)
        || !releaseHintsMatchMovementPlanZones(request)) {
      return Set.of();
    }
    if (!decision.conflictRelease() && !hasVerifiedSwitcherOccupantReleaseHint(request)) {
      return Set.of();
    }
    Set<String> released = new LinkedHashSet<>();
    for (Map.Entry<String, ConflictReleaseHint> entry : request.conflictReleaseHints().entrySet()) {
      ConflictReleaseHint hint = entry.getValue();
      if (hint == null
          || hint.kind() != ConflictClearingEvidenceKind.VERIFIED_SWITCHER_OCCUPANT
          || !hint.verifiedFor(entry.getKey())) {
        continue;
      }
      released.add(entry.getKey());
    }
    return Set.copyOf(released);
  }

  private static boolean releasedSwitcherConflict(
      OccupancyResource resource, Set<String> released) {
    return resource != null
        && resource.kind() == ResourceKind.CONFLICT
        && resource.key().startsWith("switcher:")
        && released != null
        && released.contains(resource.key());
  }

  private void traceSuppressedSwitcherAdvisoryRisk(
      String trainName, OccupancyResource resource, OccupancyClaim blocker, String advisorySource) {
    debugLogger.accept(
        "DRAIN_THROUGH_ADVISORY_RISK_SUPPRESSED train="
            + safeTraceValue(trainName)
            + " conflictKey="
            + (resource == null ? "-" : resource.key())
            + " blockerOwner="
            + (blocker == null ? "-" : safeTraceValue(blocker.trainName()))
            + " advisorySource="
            + safeTraceValue(advisorySource)
            + " reason=exact-verified-drain-switcher-conflict");
  }

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

  private boolean isDeclaredDynamicStop(RouteDefinition route, int targetIndex) {
    return route != null
        && targetIndex >= 0
        && routeDefinitions
            .findStop(route.id(), targetIndex)
            .map(DynamicStopMatcher::isDynamicStop)
            .orElse(false);
  }

  private boolean isNodeFree(String trainName, NodeId nodeId) {
    if (nodeId == null || occupancyManager == null) {
      return false;
    }
    OccupancyResource resource = OccupancyResource.forNode(nodeId);
    for (OccupancyClaim claim : occupancyManager.snapshotClaims()) {
      if (claim == null || claim.resource() == null) {
        continue;
      }
      if (!resource.equals(claim.resource())) {
        continue;
      }
      if (TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
        continue;
      }
      return false;
    }
    return true;
  }

  /**
   * Waypoint 停站状态快照。
   *
   * @param centering 是否正在执行居中动作；居中期间信号 tick 不干预控车，避免 speedLimit=0 阻止移动
   */
  private record WaypointStopState(
      String sessionId, NodeId nodeId, Instant createdAt, int dwellSeconds, boolean centering) {
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

  /** 发车许可锁快照。 */
  private record DepartureGate(String sessionId, Instant acquiredAt, String reason) {
    private DepartureGate {
      Objects.requireNonNull(sessionId, "sessionId");
      Objects.requireNonNull(acquiredAt, "acquiredAt");
      Objects.requireNonNull(reason, "reason");
    }
  }

  private record ProgressTriggerState(String stateKey, long atMillis) {}

  private record StallDecision(boolean forceLaunch, boolean triggerFailover) {
    private static StallDecision none() {
      return new StallDecision(false, false);
    }
  }

  /**
   * 解析运行时应使用的“逻辑列车名”。
   *
   * <p>优先使用 {@code FTA_TRAIN_NAME}，并把 TrainCarts split 后自动追加的 {@code ~a/~b/...}
   * 视为临时别名，避免异常分裂时把临时名误当成真实主键。 对于真正的人为重命名（不符合 split 别名形态），则仍以当前 TrainCarts 名为准，后续由 {@link
   * #handleRenameIfNeeded(TrainProperties)} 迁移运行时状态。
   *
   * @param properties 列车属性
   * @return 逻辑列车名；无法解析时返回 empty
   */
  public Optional<String> resolveTrackedTrainName(TrainProperties properties) {
    if (properties == null) {
      return Optional.empty();
    }
    Optional<String> currentOpt = normalizeTrainName(properties.getTrainName());
    Optional<String> taggedOpt =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_TRAIN_NAME)
            .flatMap(RuntimeDispatchService::normalizeTrainName);
    if (taggedOpt.isEmpty()) {
      return currentOpt;
    }
    if (currentOpt.isEmpty()) {
      return taggedOpt;
    }
    String current = currentOpt.get();
    String tagged = taggedOpt.get();
    if (tagged.equalsIgnoreCase(current) || isSplitAliasName(current, tagged)) {
      return Optional.of(tagged);
    }
    return Optional.of(current);
  }

  /**
   * 解析应参与运行时状态清理的 FTA 托管列车名。
   *
   * <p>普通 TrainCarts 列车即使被 split/异常销毁，也不应触发 progress 或 occupancy 清理。 split 过渡态的临时别名同样返回空，避免
   * `GroupRemoveEvent` 把残留的 canonical 列车误当成已拆除实体一起清掉。
   *
   * @param properties 列车属性
   * @return 受调度托管的逻辑列车名；非 FTA 列车返回 empty
   */
  Optional<String> resolveManagedTrainName(TrainProperties properties) {
    if (properties == null || !isFtaManagedTrain(properties)) {
      return Optional.empty();
    }
    Optional<String> resolved = resolveTrackedTrainName(properties).filter(name -> !name.isBlank());
    if (resolved.isEmpty()) {
      return Optional.empty();
    }
    String currentName = normalizeTrainName(properties.getTrainName()).orElse(null);
    return resolved.filter(name -> !isSplitAliasName(currentName, name));
  }

  /**
   * 列车重命名处理：如有必要，自动同步列车名与属性。
   *
   * <p>split 后的 {@code ~a/~b} 临时名不会触发 rename 迁移，而是继续沿用 tag 中记录的原始列车名。
   *
   * @param properties 列车属性
   * @return 同步后应使用的逻辑列车名；无法解析时返回 null
   */
  String handleRenameIfNeeded(TrainProperties properties) {
    if (properties == null) {
      return null;
    }
    Optional<String> currentOpt = normalizeTrainName(properties.getTrainName());
    Optional<String> previousOpt =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_TRAIN_NAME)
            .flatMap(RuntimeDispatchService::normalizeTrainName);
    if (currentOpt.isEmpty()) {
      return previousOpt.orElse(null);
    }

    String current = currentOpt.get();
    String resolved = current;
    if (previousOpt.isPresent()) {
      String previous = previousOpt.get();
      if (!previous.equalsIgnoreCase(current) && !isSplitAliasName(current, previous)) {
        if (hasUnmigratableStopState(previous)) {
          debugLogger.accept(
              "列车改名 owner 迁移延后: 停站/居中状态仍由旧 owner 持有 previous=" + previous + " current=" + current);
          resolved = previous;
        } else if (migrateRuntimeOwner(previous, current)) {
          resolved = current;
        } else {
          holdRenameMigrationFailure(properties, previous, current);
          resolved = previous;
        }
      } else {
        resolved = previous;
      }
    }
    TrainTagHelper.writeTag(properties, RouteProgressRegistry.TAG_TRAIN_NAME, resolved);
    return resolved;
  }

  /**
   * 判断旧 owner 是否仍被异步停站流程捕获。
   *
   * <p>Waypoint 居中调度器和 dwell 计时都以启动时列车名为 key；在流程中途迁移其它注册表会让新 owner 看不到 STOP
   * 事实。这里延后通用手动改名，等异步流程自行清理后再于后续 signal tick 完成原子 owner 迁移。
   */
  private boolean hasUnmigratableStopState(String trainName) {
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return false;
    }
    if (waypointStopStates.containsKey(key)) {
      return true;
    }
    return dwellRegistry != null && dwellRegistry.remainingSeconds(trainName).isPresent();
  }

  /**
   * 把 TrainCarts 改名同步为运行时 owner 迁移，而不是释放旧 owner 的行车授权。
   *
   * <p>目标 key 已有任一关键状态时先拒绝；所有注册表迁移完成后才迁移占用账本。这样失败路径可以无损回滚，且不会出现改名瞬间的无保护窗口。
   */
  private boolean migrateRuntimeOwner(String previousTrainName, String currentTrainName) {
    String previousKey = normalizeTrainKey(previousTrainName);
    String currentKey = normalizeTrainKey(currentTrainName);
    if (previousKey.isEmpty() || currentKey.isEmpty()) {
      return false;
    }
    if (previousKey.equals(currentKey)) {
      return true;
    }
    synchronized (runtimeOwnerMigrationLock) {
      boolean progressPresent = progressRegistry.get(previousTrainName).isPresent();
      boolean layoverPresent = layoverRegistry.get(previousTrainName).isPresent();
      boolean overridesPresent = effectiveNodeOverrides.containsKey(previousKey);
      boolean guardPresent = turnbackFootprintGuards.contains(previousTrainName);
      boolean departureGatePresent = departureGates.containsKey(previousKey);
      boolean allocationsPresent = dynamicAllocator.hasAllocations(previousTrainName);
      if ((progressPresent && progressRegistry.get(currentTrainName).isPresent())
          || (layoverPresent && layoverRegistry.get(currentTrainName).isPresent())
          || (overridesPresent && effectiveNodeOverrides.containsKey(currentKey))
          || (guardPresent && turnbackFootprintGuards.contains(currentTrainName))
          || (departureGatePresent && departureGates.containsKey(currentKey))
          || (allocationsPresent && dynamicAllocator.hasAllocations(currentTrainName))) {
        return false;
      }

      boolean progressMoved =
          !progressPresent || progressRegistry.rename(previousTrainName, currentTrainName);
      boolean layoverMoved =
          !layoverPresent || layoverRegistry.rename(previousTrainName, currentTrainName);
      boolean overridesMoved =
          !overridesPresent || migrateEffectiveNodeOverrides(previousTrainName, currentTrainName);
      boolean guardMoved =
          !guardPresent || turnbackFootprintGuards.rename(previousTrainName, currentTrainName);
      boolean allocationsMoved =
          !allocationsPresent
              || dynamicAllocator.migrateAllocations(previousTrainName, currentTrainName);
      if (!progressMoved || !layoverMoved || !overridesMoved || !guardMoved || !allocationsMoved) {
        rollbackRuntimeOwnerRegistries(
            previousTrainName,
            currentTrainName,
            progressPresent && progressMoved,
            layoverPresent && layoverMoved,
            overridesPresent && overridesMoved,
            guardPresent && guardMoved,
            allocationsPresent && allocationsMoved,
            false);
        return false;
      }
      if (departureGatePresent) {
        moveDepartureGate(previousTrainName, currentTrainName);
      }

      boolean authorityMoved = true;
      if (occupancyManager instanceof AuthorityHandoffSupport handoffSupport) {
        authorityMoved = handoffSupport.migrateAuthorityOwner(previousTrainName, currentTrainName);
      } else if (hasOccupancyOwnerState(previousTrainName)) {
        authorityMoved = false;
      }
      if (!authorityMoved) {
        rollbackRuntimeOwnerRegistries(
            previousTrainName,
            currentTrainName,
            progressPresent,
            layoverPresent,
            overridesPresent,
            guardPresent,
            allocationsPresent,
            departureGatePresent);
        return false;
      }

      movementAuthorizationTokens.remove(previousKey);
      movementAuthorizationTokens.remove(currentKey);
      movementInhibitors.remove(previousKey);
      movementInhibitors.put(currentKey, HardStopReason.AUTHORIZATION_FAILURE);
      return true;
    }
  }

  private void rollbackRuntimeOwnerRegistries(
      String previousTrainName,
      String currentTrainName,
      boolean progressMoved,
      boolean layoverMoved,
      boolean overridesMoved,
      boolean guardMoved,
      boolean allocationsMoved,
      boolean departureGateMoved) {
    if (departureGateMoved) {
      moveDepartureGate(currentTrainName, previousTrainName);
    }
    if (guardMoved) {
      turnbackFootprintGuards.rename(currentTrainName, previousTrainName);
    }
    if (allocationsMoved) {
      dynamicAllocator.migrateAllocations(currentTrainName, previousTrainName);
    }
    if (overridesMoved) {
      migrateEffectiveNodeOverrides(currentTrainName, previousTrainName);
    }
    if (layoverMoved) {
      layoverRegistry.rename(currentTrainName, previousTrainName);
    }
    if (progressMoved) {
      progressRegistry.rename(currentTrainName, previousTrainName);
    }
  }

  private boolean hasOccupancyOwnerState(String trainName) {
    if (occupancyManager == null) {
      return false;
    }
    List<OccupancyClaim> claims = occupancyManager.snapshotClaims();
    if (claims != null
        && claims.stream()
            .filter(Objects::nonNull)
            .anyMatch(
                claim -> TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName))) {
      return true;
    }
    if (!(occupancyManager instanceof OccupancyQueueSupport queueSupport)) {
      return false;
    }
    List<OccupancyQueueSnapshot> queues = queueSupport.snapshotQueues();
    return queues != null
        && queues.stream()
            .filter(Objects::nonNull)
            .flatMap(queue -> queue.entries().stream())
            .filter(Objects::nonNull)
            .anyMatch(entry -> TrainNameNormalizer.sameLogicalTrain(entry.trainName(), trainName));
  }

  private void holdRenameMigrationFailure(
      TrainProperties properties, String previousTrainName, String currentTrainName) {
    invalidateMovementAuthorization(previousTrainName, HardStopReason.AUTHORIZATION_FAILURE);
    invalidateMovementAuthorization(currentTrainName, HardStopReason.AUTHORIZATION_FAILURE);
    properties.clearDestinationRoute();
    properties.clearDestination();
    if (properties.getHolder() != null) {
      RuntimeTrainHandle train = new TrainCartsRuntimeHandle(properties.getHolder());
      if (train.isValid()) {
        runtimeTrainController.stopHard(train, properties);
      }
    }
    updateSignalOrWarn(previousTrainName, SignalAspect.STOP, Instant.now());
    debugLogger.accept(
        "列车改名 owner 迁移失败并保持旧授权: previous=" + previousTrainName + " current=" + currentTrainName);
  }

  private static Optional<String> normalizeTrainName(String trainName) {
    if (trainName == null) {
      return Optional.empty();
    }
    String normalized = trainName.trim();
    return normalized.isEmpty() ? Optional.empty() : Optional.of(normalized);
  }

  /**
   * 判断当前 TrainCarts 列车名是否只是 split 后附加的临时别名。
   *
   * <p>形如 {@code main~a}、{@code main~b}、{@code main~a~b} 都视为同一逻辑列车的分裂别名。
   */
  private static boolean isSplitAliasName(String currentTrainName, String taggedTrainName) {
    if (currentTrainName == null || taggedTrainName == null) {
      return false;
    }
    if (currentTrainName.length() <= taggedTrainName.length()
        || !currentTrainName.regionMatches(true, 0, taggedTrainName, 0, taggedTrainName.length())) {
      return false;
    }
    int index = taggedTrainName.length();
    while (index < currentTrainName.length()) {
      if (currentTrainName.charAt(index) != '~') {
        return false;
      }
      index++;
      int segmentStart = index;
      while (index < currentTrainName.length() && currentTrainName.charAt(index) != '~') {
        char c = currentTrainName.charAt(index);
        if (!Character.isLetterOrDigit(c)) {
          return false;
        }
        index++;
      }
      if (segmentStart == index) {
        return false;
      }
    }
    return true;
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

    SignalRefreshResult refresh = refreshSignalByName(trainName);
    if (finalRefreshProvedProceed(trainName, refresh)) {
      debugLogger.accept(
          "relaunch delegated: train="
              + trainName
              + " from="
              + currentNode.value()
              + " to="
              + nextNode.value()
              + " reason=normal-final-authorization");
      return true;
    }
    traceRelaunchSuppressedByFinalAuthorization(
        trainName, "final-authorization-not-proven:" + refresh.reason(), SignalAspect.STOP);
    applyHardStop(
        train,
        properties,
        trainName,
        HardStopReason.AUTHORIZATION_FAILURE,
        true,
        route,
        currentNode,
        nextNode,
        graph,
        new OccupancyDecision(
            false, Instant.now(), SignalAspect.STOP, List.of(), false, "relaunch-suppressed"),
        null,
        AuthorityEnd.none());
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
  List<OccupancyResource> releaseResourcesNotInRequest(
      String trainName,
      List<OccupancyResource> keepResources,
      Set<OccupancyResource> protectedResources) {
    if (trainName == null || trainName.isBlank() || occupancyManager == null) {
      return List.of();
    }
    java.util.Set<OccupancyResource> keep =
        keepResources == null ? java.util.Set.of() : java.util.Set.copyOf(keepResources);
    java.util.Set<OccupancyResource> protectedSet = new LinkedHashSet<>();
    if (protectedResources != null) {
      protectedSet.addAll(protectedResources);
    }
    protectedSet.addAll(turnbackFootprintGuards.protectedResources(trainName));
    List<OccupancyResource> released = new ArrayList<>();
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
      if (protectedSet.contains(claim.resource())) {
        continue;
      }
      if (occupancyManager.releaseResource(claim.resource(), Optional.of(trainName))) {
        released.add(claim.resource());
      }
    }
    return List.copyOf(released);
  }

  /**
   * 组合“本次占用窗口 + 当前位置资源”保留集。
   *
   * <p>目的：即使前方窗口调整，也持续保留列车当前位置（节点/当前边）占用，避免“车下扳道岔”。
   */
  private List<OccupancyResource> mergeKeepResourcesWithCurrentPosition(
      List<OccupancyResource> requestResources,
      Optional<NodeId> currentNodeOpt,
      Optional<NodeId> nextNodeOpt,
      RailGraph graph) {
    java.util.LinkedHashSet<OccupancyResource> keep = new java.util.LinkedHashSet<>();
    if (requestResources != null) {
      keep.addAll(requestResources);
    }
    if (currentNodeOpt.isEmpty()) {
      return List.copyOf(keep);
    }
    keep.addAll(resolveCurrentPositionResources(currentNodeOpt.get(), nextNodeOpt, graph));
    return List.copyOf(keep);
  }

  private List<OccupancyResource> resolveCurrentPositionResources(
      NodeId currentNode, Optional<NodeId> nextNodeOpt, RailGraph graph) {
    if (currentNode == null) {
      return List.of();
    }
    if (graph == null) {
      return List.of(OccupancyResource.forNode(currentNode));
    }
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 1, 0, 0, 0, debugLogger);
    OccupancyRequest request =
        builder.buildCurrentPositionRequest(
            "_fta_current_position",
            Optional.empty(),
            currentNode,
            nextNodeOpt != null ? nextNodeOpt : Optional.empty(),
            Instant.EPOCH,
            0,
            AuthorizationPurpose.RUNTIME_MOVE);
    return request.resourceList();
  }

  /**
   * 主动维持当前位置占用。
   *
   * <p>当列车处于运行中时，当前节点/当前边必须始终保持 claim，以防调度窗口切换导致尾部资源提前释放。
   */
  private void retainCurrentPositionOccupancy(
      String trainName,
      RouteId routeId,
      Optional<NodeId> currentNodeOpt,
      Optional<NodeId> nextNodeOpt,
      Optional<MovementPlanSnapshot> movementPlan,
      RailGraph graph,
      Instant now) {
    if (occupancyManager == null
        || trainName == null
        || trainName.isBlank()
        || currentNodeOpt.isEmpty()) {
      return;
    }
    NodeId currentNode = currentNodeOpt.get();
    OccupancyRequest request;
    if (graph == null) {
      request =
          new OccupancyRequest(
              trainName,
              Optional.ofNullable(routeId),
              now,
              List.of(OccupancyResource.forNode(currentNode)),
              Map.of(),
              0,
              AuthorizationPurpose.RUNTIME_MOVE);
    } else {
      OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 1, 0, 0, 0, debugLogger);
      Optional<MovementPlanSnapshot> canonicalPlan =
          movementPlan == null ? Optional.empty() : movementPlan;
      request =
          canonicalPlan
              .map(
                  plan ->
                      builder.buildCurrentPositionRequestFromPlan(
                          trainName,
                          Optional.ofNullable(routeId),
                          currentNode,
                          nextNodeOpt != null ? nextNodeOpt : Optional.empty(),
                          now,
                          0,
                          AuthorizationPurpose.RUNTIME_MOVE,
                          plan))
              .orElseGet(
                  () ->
                      builder.buildCurrentPositionRequest(
                          trainName,
                          Optional.ofNullable(routeId),
                          currentNode,
                          nextNodeOpt != null ? nextNodeOpt : Optional.empty(),
                          now,
                          0,
                          AuthorizationPurpose.RUNTIME_MOVE));
    }
    occupancyManager.acquire(request);
  }

  /**
   * 在 DYNAMIC materialization 边界内构建占用请求。
   *
   * <p>route 中的声明节点只是 DYNAMIC 语法占位，不是已经选定的物理站台。请求最多到达首个已 materialize 的 DYNAMIC 目标；若首个 DYNAMIC 尚未
   * materialize，则只允许使用它之前的安全 route 前缀。紧邻目标尚未 materialize 时没有可写前缀，必须 fail-closed。这样普通 lookahead
   * 与原子联锁扩展都无法把后续占位节点写成 {@link ResourceIntent#MOVEMENT_REQUIRED}。
   *
   * @param provisionalDynamicIndex 候选选台期间尚未写入 effective override 的临时目标索引
   */
  private Optional<OccupancyRequestContext> buildContextWithinDynamicBoundary(
      OccupancyRequestBuilder builder,
      String trainName,
      RouteDefinition route,
      List<NodeId> movementNodes,
      List<NodeId> directionContextNodes,
      int currentIndex,
      Instant now,
      int priority,
      AuthorizationPurpose purpose,
      OptionalInt provisionalDynamicIndex) {
    if (builder == null || route == null || movementNodes == null || movementNodes.isEmpty()) {
      return Optional.empty();
    }
    Optional<DynamicAuthorityWindow> windowOpt =
        resolveDynamicAuthorityWindow(
            trainName,
            route,
            movementNodes,
            directionContextNodes,
            currentIndex,
            provisionalDynamicIndex);
    if (windowOpt.isEmpty()) {
      return Optional.empty();
    }
    DynamicAuthorityWindow window = windowOpt.get();
    return builder.buildContextFromNodesWithDirectionContext(
        trainName,
        Optional.ofNullable(route.id()),
        window.movementNodes(),
        window.directionContextNodes(),
        currentIndex,
        now,
        priority,
        purpose);
  }

  private Optional<OccupancyRequestContext> buildContextWithinDynamicBoundary(
      OccupancyRequestBuilder builder,
      String trainName,
      RouteDefinition route,
      List<NodeId> movementNodes,
      List<NodeId> directionContextNodes,
      int currentIndex,
      Instant now,
      int priority,
      AuthorizationPurpose purpose) {
    return buildContextWithinDynamicBoundary(
        builder,
        trainName,
        route,
        movementNodes,
        directionContextNodes,
        currentIndex,
        now,
        priority,
        purpose,
        OptionalInt.empty());
  }

  /**
   * 为 Depot spawn 准备经过 DYNAMIC materialization 边界裁剪的节点序列。
   *
   * <p>出车门控发生在列车实体生成之前，不能依赖后续 signal tick 才解析紧邻的 DYNAMIC stop。本方法复用运行时分配器：若首个 DYNAMIC
   * 当前可安全选台，则把实际节点写入同一份 effective-node 状态；若容量或证据不足则 fail-closed；更远且尚未进入分配窗口的 DYNAMIC
   * 会在声明占位节点前截断。调用方只能使用返回节点构建可写占用请求与提交首个 destination。
   *
   * <p>本方法可能创建临时站台预订。调用方在 gate、spawn 或 acquire 失败时必须调用 {@link
   * #cancelPreparedDepotSpawnDynamicAuthority(String)}；成功生成后保留预订，由正常 route progress 生命周期接管。
   *
   * @param trainName 预生成列车的稳定逻辑名称
   * @param route 当前 Route 定义
   * @param spawnWaypoints 已将第 0 个节点替换为实际 Depot 的节点序列
   * @param graph 本次 Depot gate 使用的调度图
   * @param now 本次准备时间
   * @return 可安全用于 gate 的节点序列；无法安全 materialize 紧邻目标时返回 empty
   */
  public Optional<List<NodeId>> prepareDepotSpawnDynamicAuthority(
      String trainName,
      RouteDefinition route,
      List<NodeId> spawnWaypoints,
      RailGraph graph,
      Instant now) {
    if (trainName == null
        || trainName.isBlank()
        || route == null
        || spawnWaypoints == null
        || spawnWaypoints.size() != route.waypoints().size()
        || spawnWaypoints.size() < 2
        || graph == null) {
      return Optional.empty();
    }
    NodeId currentNode = spawnWaypoints.get(0);
    RouteDefinition spawnRoute =
        new RouteDefinition(route.id(), spawnWaypoints, route.metadata(), route.lifecycleMode());
    DynamicResolution<DynamicPlatformAllocator.AllocationResult> resolution =
        dynamicAllocator.resolveAllocation(
            trainName, spawnRoute, 0, graph, currentNode, Optional.empty());
    if (resolution.isBlocked()) {
      cancelPreparedDepotSpawnDynamicAuthority(trainName);
      debugLogger.accept(
          dynamicWaitLogLabel(resolution.reason())
              + ": source=depot-spawn train="
              + trainName
              + " route="
              + route.id().value()
              + " reason="
              + resolution.reason());
      return Optional.empty();
    }
    resolution
        .selected()
        .ifPresent(
            selected ->
                recordEffectiveNode(
                    trainName, route, selected.stopIndex(), selected.allocatedNode()));

    List<NodeId> effectiveNodes =
        new java.util.ArrayList<>(resolveEffectiveWaypoints(trainName, route));
    effectiveNodes.set(0, currentNode);
    Optional<DynamicAuthorityWindow> windowOpt =
        resolveDynamicAuthorityWindow(
            trainName, route, effectiveNodes, effectiveNodes, 0, OptionalInt.empty());
    if (windowOpt.isEmpty() || windowOpt.get().movementNodes().size() < 2) {
      cancelPreparedDepotSpawnDynamicAuthority(trainName);
      debugLogger.accept(
          "DYNAMIC 目标等待: source=depot-spawn train="
              + trainName
              + " route="
              + route.id().value()
              + " reason=authority-prefix-unavailable");
      return Optional.empty();
    }
    return Optional.of(windowOpt.get().movementNodes());
  }

  /**
   * 取消尚未成功生成列车的 DYNAMIC materialization 状态。
   *
   * <p>这里只清理 Depot spawn 准备阶段创建的站台预订与 effective-node 覆盖，不触碰任何真实 occupancy claim。
   *
   * @param trainName 预生成列车的稳定逻辑名称
   */
  public void cancelPreparedDepotSpawnDynamicAuthority(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    dynamicAllocator.clearAllocations(trainName);
    String key = normalizeTrainKey(trainName);
    if (!key.isEmpty()) {
      effectiveNodeOverrides.remove(key);
    }
  }

  private Optional<DynamicAuthorityWindow> resolveDynamicAuthorityWindow(
      String trainName,
      RouteDefinition route,
      List<NodeId> movementNodes,
      List<NodeId> directionContextNodes,
      int currentIndex,
      OptionalInt provisionalDynamicIndex) {
    if (route == null
        || movementNodes == null
        || movementNodes.isEmpty()
        || currentIndex < 0
        || currentIndex >= movementNodes.size()) {
      return Optional.empty();
    }
    List<NodeId> effectiveDirectionNodes =
        directionContextNodes == null || directionContextNodes.isEmpty()
            ? movementNodes
            : directionContextNodes;
    int scanLimit = Math.min(route.waypoints().size(), movementNodes.size());
    for (int index = currentIndex + 1; index < scanLimit; index++) {
      if (!isDeclaredDynamicStop(route, index)) {
        continue;
      }
      boolean materialized =
          (provisionalDynamicIndex != null
                  && provisionalDynamicIndex.isPresent()
                  && provisionalDynamicIndex.getAsInt() == index)
              || readEffectiveNode(trainName, route, index).isPresent();
      int endExclusive = materialized ? index + 1 : index;
      if (endExclusive <= currentIndex + 1) {
        debugLogger.accept(
            "DYNAMIC_AUTHORITY_BOUNDARY_BLOCKED train="
                + trainName
                + " route="
                + route.id().value()
                + " currentIndex="
                + currentIndex
                + " dynamicIndex="
                + index
                + " reason=immediate-target-not-materialized");
        return Optional.empty();
      }
      int movementEnd = Math.min(endExclusive, movementNodes.size());
      int directionEnd = Math.min(endExclusive, effectiveDirectionNodes.size());
      if (movementEnd <= currentIndex + 1 || directionEnd <= currentIndex + 1) {
        return Optional.empty();
      }
      debugLogger.accept(
          "DYNAMIC_AUTHORITY_BOUNDARY train="
              + trainName
              + " route="
              + route.id().value()
              + " currentIndex="
              + currentIndex
              + " dynamicIndex="
              + index
              + " materialized="
              + materialized
              + " endExclusive="
              + endExclusive);
      return Optional.of(
          new DynamicAuthorityWindow(
              movementNodes.subList(0, movementEnd),
              effectiveDirectionNodes.subList(0, directionEnd)));
    }
    return Optional.of(new DynamicAuthorityWindow(movementNodes, effectiveDirectionNodes));
  }

  /** DYNAMIC 边界裁剪后的物理节点与方向语义节点。 */
  private record DynamicAuthorityWindow(
      List<NodeId> movementNodes, List<NodeId> directionContextNodes) {
    private DynamicAuthorityWindow {
      movementNodes = List.copyOf(movementNodes);
      directionContextNodes = List.copyOf(directionContextNodes);
    }
  }

  /**
   * 构建“前向授权”占用请求。
   *
   * <p>常规运行请求会同时携带尾部保护资源；这些资源只用于防止后车贴近，不应参与前车的红绿灯判定。信号判定使用该前向请求， 避免后车在尾部保护/预占用窗口内反向把前车打成 STOP。
   */
  private Optional<OccupancyRequestContext> buildForwardAuthorizationContext(
      RailGraph graph,
      ConfigManager.RuntimeSettings runtimeSettings,
      String trainName,
      RouteDefinition route,
      List<NodeId> effectiveNodes,
      int currentIndex,
      Instant now,
      int priority,
      AuthorizationPurpose purpose) {
    if (graph == null || runtimeSettings == null || route == null) {
      return Optional.empty();
    }
    OccupancyRequestBuilder authorizationBuilder =
        runtimeLookaheadBuilder(graph, runtimeSettings, 0);
    return buildContextWithinDynamicBoundary(
        authorizationBuilder,
        trainName,
        route,
        effectiveNodes,
        effectiveNodes,
        currentIndex,
        now,
        priority,
        purpose);
  }

  private OccupancyRequestBuilder runtimeLookaheadBuilder(
      RailGraph graph, ConfigManager.RuntimeSettings runtimeSettings, int rearGuardEdges) {
    return new OccupancyRequestBuilder(
        graph,
        runtimeSettings.lookaheadEdges(),
        runtimeSettings.minClearEdges(),
        rearGuardEdges,
        runtimeSettings.switcherZoneEdges(),
        runtimeLookaheadMinDistanceBlocks(runtimeSettings),
        runtimeLookaheadMaxEdges(runtimeSettings),
        debugLogger);
  }

  private static long runtimeLookaheadMinDistanceBlocks(
      ConfigManager.RuntimeSettings runtimeSettings) {
    if (runtimeSettings == null) {
      return 0L;
    }
    double cautionMargin = Math.max(0.0, runtimeSettings.movementAuthorityCautionMarginBlocks());
    int followingMin = Math.max(0, runtimeSettings.followingMinClearBlocks());
    int followingStop = Math.max(0, runtimeSettings.followingStopMarginBlocks());
    return Math.max(followingMin, (long) Math.ceil(followingStop + cautionMargin));
  }

  private static int runtimeLookaheadMaxEdges(ConfigManager.RuntimeSettings runtimeSettings) {
    if (runtimeSettings == null) {
      return 0;
    }
    int minEdges = Math.max(runtimeSettings.lookaheadEdges(), runtimeSettings.minClearEdges());
    int preferredMax =
        Math.max(
            DISTANCE_LOOKAHEAD_MIN_MAX_EDGES, minEdges * DISTANCE_LOOKAHEAD_MAX_EDGE_MULTIPLIER);
    return Math.max(minEdges, Math.min(DISTANCE_LOOKAHEAD_ABSOLUTE_MAX_EDGES, preferredMax));
  }

  /**
   * 构建“硬授权窗口”占用请求。
   *
   * <p>硬窗口只覆盖当前 tick 真实允许进入的下一段物理进路。更远的 expanded path 仍由普通前向上下文保留，用于 advisory lookahead、黄灯和速度曲线；
   * 但它不能直接进入 {@code canEnter/acquire} 的 hard authority，否则远端 blocker 会把当前可走区段提前打成红灯。
   */
  private Optional<OccupancyRequestContext> buildHardAuthorityContext(
      RailGraph graph,
      ConfigManager.RuntimeSettings runtimeSettings,
      String trainName,
      RouteDefinition route,
      List<NodeId> effectiveNodes,
      int currentIndex,
      Instant now,
      int priority,
      AuthorizationPurpose purpose) {
    return buildHardAuthorityContext(
        graph,
        runtimeSettings,
        trainName,
        route,
        effectiveNodes,
        currentIndex,
        now,
        priority,
        purpose,
        0.0,
        0.0);
  }

  /**
   * 构建“硬授权窗口”占用请求。
   *
   * <p>信号 tick 与推进触发会传入列车当前速度，使硬窗口至少覆盖可制动距离。静止或缺少速度证据的恢复路径仍退回单边窗口，避免把 advisory 远端 blocker 提前变成物理
   * STOP。
   */
  private Optional<OccupancyRequestContext> buildHardAuthorityContext(
      RailGraph graph,
      ConfigManager.RuntimeSettings runtimeSettings,
      String trainName,
      RouteDefinition route,
      List<NodeId> effectiveNodes,
      int currentIndex,
      Instant now,
      int priority,
      AuthorizationPurpose purpose,
      double currentSpeedBps,
      double decelBps2) {
    return buildHardAuthorityContextWithDirectionContext(
        graph,
        runtimeSettings,
        trainName,
        route,
        effectiveNodes,
        effectiveNodes,
        currentIndex,
        now,
        priority,
        purpose,
        currentSpeedBps,
        decelBps2);
  }

  /**
   * 构建物理窗口从实时节点起算、但单线方向继承 canonical route leg 的硬授权。
   *
   * <p>{@code directionContextNodes} 只参与方向解析，不会扩大本 tick 的 NODE/EDGE/CONFLICT 资源。
   */
  private Optional<OccupancyRequestContext> buildHardAuthorityContextWithDirectionContext(
      RailGraph graph,
      ConfigManager.RuntimeSettings runtimeSettings,
      String trainName,
      RouteDefinition route,
      List<NodeId> effectiveNodes,
      List<NodeId> directionContextNodes,
      int currentIndex,
      Instant now,
      int priority,
      AuthorizationPurpose purpose,
      double currentSpeedBps,
      double decelBps2) {
    if (graph == null || runtimeSettings == null || route == null) {
      return Optional.empty();
    }
    OccupancyRequestBuilder authorizationBuilder =
        new OccupancyRequestBuilder(
            graph,
            HARD_AUTHORITY_LOOKAHEAD_EDGES,
            0,
            0,
            runtimeSettings.switcherZoneEdges(),
            hardAuthorityMinDistanceBlocks(runtimeSettings, currentSpeedBps, decelBps2),
            HARD_AUTHORITY_DISTANCE_MAX_EDGES,
            debugLogger);
    return buildContextWithinDynamicBoundary(
        authorizationBuilder,
        trainName,
        route,
        effectiveNodes,
        directionContextNodes,
        currentIndex,
        now,
        priority,
        purpose);
  }

  private static long hardAuthorityMinDistanceBlocks(
      ConfigManager.RuntimeSettings runtimeSettings, double currentSpeedBps, double decelBps2) {
    double speed = Double.isFinite(currentSpeedBps) ? Math.max(0.0, currentSpeedBps) : 0.0;
    if (speed <= 1.0e-6) {
      return 0L;
    }
    double decel = Double.isFinite(decelBps2) && decelBps2 > 0.0 ? decelBps2 : 0.001;
    double brakingDistance = (speed * speed) / (2.0 * decel);
    double cautionMargin =
        runtimeSettings == null
            ? 0.0
            : Math.max(0.0, runtimeSettings.movementAuthorityCautionMarginBlocks());
    int followingMin =
        runtimeSettings == null ? 0 : Math.max(0, runtimeSettings.followingMinClearBlocks());
    int followingStop =
        runtimeSettings == null ? 0 : Math.max(0, runtimeSettings.followingStopMarginBlocks());
    double safetyMargin = Math.max(followingMin, followingStop + cautionMargin);
    return (long) Math.ceil(brakingDistance + safetyMargin);
  }

  /**
   * 将普通运行请求升级为“冲突区清空”请求。
   *
   * <p>single conflict 仍只附加只读拓扑提示。switcher 只有在列车的当前位置就是该道岔、已持有该节点的实体占用、当前有向路径从该节点驶向出口，且路径上没有外部
   * NODE/EDGE 或其他 switcher blocker 时，才会生成已验证的 drain authority。证明不依赖同一快照中已存在竞争
   * claim，避免竞争车在授权请求形成后才申请道岔时，把实体占用者重新压成 STOP。这样能让已进入平交道口的列车先清空，同时让入口外列车继续保持硬停车。
   */
  private OccupancyRequest withRuntimeConflictClearingEvidence(
      OccupancyRequest request, OccupancyRequestContext context, RailGraph graph) {
    if (request == null
        || context == null
        || graph == null
        || request.purpose() != AuthorizationPurpose.RUNTIME_MOVE) {
      return request;
    }
    Map<String, ConflictReleaseHint> topologyHints = new LinkedHashMap<>();
    Map<String, ConflictReleaseHint> verifiedDrainHints = new LinkedHashMap<>();
    for (OccupancyResource resource : request.resourceList()) {
      if (isDirectionalSingleConflict(resource)) {
        if (!trainAlreadyHoldsResource(request.trainName(), resource)) {
          continue;
        }
        RailGraphConflictSupport support = singleConflictMembershipSupport(graph, resource);
        if (!pathTargetsExitFromConflict(context.edges(), support, resource.key())) {
          continue;
        }
        topologyHints.put(
            resource.key(),
            ConflictReleaseHint.topologyExit(resource.key(), "runtime-conflict-clearing-evidence"));
        continue;
      }
      if (switcherDrainAuthorityProved(request, resource)) {
        verifiedDrainHints.put(
            resource.key(),
            ConflictReleaseHint.verifiedSwitcherOccupant(
                resource.key(), "runtime-switcher-occupant-drain"));
      }
    }
    Map<String, ConflictReleaseHint> hints =
        verifiedDrainHints.isEmpty() ? topologyHints : verifiedDrainHints;
    if (hints.isEmpty()) {
      return request;
    }
    Optional<OccupancyClaim> hardBlocker =
        drainPathHardBlocker(request, verifiedDrainHints.keySet());
    if (hardBlocker.isPresent()) {
      OccupancyClaim blocker = hardBlocker.get();
      debugLogger.accept(
          "DRAIN_THROUGH_BLOCKED train="
              + request.trainName()
              + " zoneIds="
              + hints.keySet()
              + " blockerResource="
              + blocker.resource()
              + " blockerOwner="
              + blocker.trainName()
              + " reason=hard-blocker-on-drain-path"
              + " occupancyVersion="
              + occupancyVersion());
      return request;
    }
    if (!verifiedDrainHints.isEmpty()) {
      debugLogger.accept(
          "SWITCHER_DRAIN_THROUGH_AUTHORITY train="
              + request.trainName()
              + " zoneIds="
              + verifiedDrainHints.keySet()
              + " currentNode="
              + request
                  .movementPlanSnapshot()
                  .flatMap(MovementPlanSnapshot::currentNode)
                  .map(NodeId::value)
                  .orElse("-")
              + " path="
              + context.pathNodes()
              + " reason=physical-switcher-occupant-clears-first"
              + " occupancyVersion="
              + occupancyVersion());
      return request.withConflictReleaseHints(
          AuthorizationPurpose.CONFLICT_CLEARING, verifiedDrainHints);
    }
    debugLogger.accept(
        "DRAIN_THROUGH_AUTHORITY train="
            + request.trainName()
            + " zoneIds="
            + hints.keySet()
            + " path="
            + context.pathNodes()
            + " reason=topology-exit-hint-only"
            + " occupancyVersion="
            + occupancyVersion());
    return request.withConflictClearingEvidence(hints);
  }

  private boolean switcherDrainAuthorityProved(
      OccupancyRequest request, OccupancyResource conflict) {
    if (request == null
        || conflict == null
        || conflict.kind() != ResourceKind.CONFLICT
        || !conflict.key().startsWith("switcher:")
        || !request.intentFor(conflict).hardAuthority()) {
      return false;
    }
    Optional<MovementPlanSnapshot> planOpt = request.movementPlanSnapshot();
    Optional<NodeId> switcherNodeOpt = switcherNodeFromConflict(conflict);
    if (planOpt.isEmpty() || switcherNodeOpt.isEmpty()) {
      return false;
    }
    MovementPlanSnapshot plan = planOpt.get();
    NodeId switcherNode = switcherNodeOpt.get();
    OccupancyResource switcherNodeResource = OccupancyResource.forNode(switcherNode);
    if (plan.occupancyVersion() < 0
        || plan.progressVersion() < 0
        || plan.currentNode().filter(switcherNode::equals).isEmpty()
        || plan.effectiveFromNode().filter(switcherNode::equals).isEmpty()
        || !request.resourceList().contains(switcherNodeResource)
        || !request.intentFor(switcherNodeResource).hardAuthority()
        || !trainHoldsPhysicalSwitcherNode(request.trainName(), switcherNodeResource)) {
      return false;
    }
    DirectedTraversalContext.SwitcherPathSignature signature =
        plan.switcherPathSignatures().get(conflict.key());
    if (signature == null
        || !conflict.key().equals(signature.switcherKey())
        || signature.pathNodes().isEmpty()
        || !switcherNode.equals(signature.pathNodes().get(0))
        || plan.directedEdges().isEmpty()) {
      return false;
    }
    DirectedTraversalContext.DirectedEdge firstEdge = plan.directedEdges().get(0);
    return switcherNode.equals(firstEdge.fromNode())
        && !switcherNode.equals(firstEdge.toNode())
        && plan.effectiveToNode().filter(firstEdge.toNode()::equals).isPresent();
  }

  private boolean trainHoldsPhysicalSwitcherNode(String trainName, OccupancyResource switcherNode) {
    if (occupancyManager == null
        || trainName == null
        || trainName.isBlank()
        || switcherNode == null
        || switcherNode.kind() != ResourceKind.NODE) {
      return false;
    }
    List<OccupancyClaim> claims = occupancyManager.snapshotClaims();
    if (claims == null || claims.isEmpty()) {
      return false;
    }
    for (OccupancyClaim claim : claims) {
      if (claim == null
          || claim.resource() == null
          || claim.trainName() == null
          || !switcherNode.equals(claim.resource())
          || !TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
        continue;
      }
      if (claim.role() == ClaimRole.MOVEMENT_REQUIRED
          || claim.role() == ClaimRole.PHYSICAL_FOOTPRINT
          || claim.role() == ClaimRole.PROTECTIVE_RETAIN
          || claim.role() == ClaimRole.HOLD_ONLY) {
        return true;
      }
    }
    return false;
  }

  private Optional<OccupancyClaim> drainPathHardBlocker(
      OccupancyRequest request, Set<String> bypassableSwitcherConflicts) {
    if (request == null || occupancyManager == null) {
      return Optional.empty();
    }
    List<OccupancyClaim> claims = occupancyManager.snapshotClaims();
    if (claims == null || claims.isEmpty()) {
      return Optional.empty();
    }
    Set<OccupancyResource> hardPathResources = new LinkedHashSet<>();
    for (OccupancyResource resource : request.resourceList()) {
      if (isDrainPathHardBlockerResource(resource)
          && !isBypassableSwitcherConflict(resource, bypassableSwitcherConflicts)) {
        hardPathResources.add(resource);
      }
    }
    if (hardPathResources.isEmpty()) {
      return Optional.empty();
    }
    for (OccupancyClaim claim : claims) {
      if (claim == null || claim.resource() == null || claim.trainName() == null) {
        continue;
      }
      if (!hardPathResources.contains(claim.resource())) {
        continue;
      }
      if (!TrainNameNormalizer.sameLogicalTrain(claim.trainName(), request.trainName())) {
        return Optional.of(claim);
      }
    }
    return Optional.empty();
  }

  private static boolean isBypassableSwitcherConflict(
      OccupancyResource resource, Set<String> bypassableSwitcherConflicts) {
    return resource != null
        && resource.kind() == ResourceKind.CONFLICT
        && resource.key().startsWith("switcher:")
        && bypassableSwitcherConflicts != null
        && bypassableSwitcherConflicts.contains(resource.key());
  }

  private static boolean isDrainPathHardBlockerResource(OccupancyResource resource) {
    if (resource == null) {
      return false;
    }
    if (resource.kind() == ResourceKind.NODE || resource.kind() == ResourceKind.EDGE) {
      return true;
    }
    return resource.kind() == ResourceKind.CONFLICT && resource.key().startsWith("switcher:");
  }

  private boolean trainAlreadyHoldsResource(String trainName, OccupancyResource resource) {
    if (occupancyManager == null || trainName == null || trainName.isBlank() || resource == null) {
      return false;
    }
    List<OccupancyClaim> claims = occupancyManager.snapshotClaims();
    if (claims == null || claims.isEmpty()) {
      return false;
    }
    for (OccupancyClaim claim : claims) {
      if (claim == null || claim.resource() == null || claim.trainName() == null) {
        continue;
      }
      if (resource.equals(claim.resource())
          && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
        return true;
      }
    }
    return false;
  }

  private boolean pathTargetsExitFromConflict(
      List<RailEdge> edges, RailGraphConflictSupport support, String conflictKey) {
    if (edges == null || edges.isEmpty() || support == null || conflictKey == null) {
      return false;
    }
    boolean sawSameConflict = false;
    for (RailEdge edge : edges) {
      if (edge == null || edge.id() == null) {
        continue;
      }
      Optional<String> edgeConflict = support.conflictKeyForEdge(edge.id());
      if (edgeConflict.isPresent() && edgeConflict.get().equals(conflictKey)) {
        sawSameConflict = true;
        continue;
      }
      if (sawSameConflict || edgeConflict.isEmpty() || !edgeConflict.get().equals(conflictKey)) {
        return true;
      }
    }
    return false;
  }

  private Optional<NodeId> conflictExitNode(
      OccupancyRequestContext context, RailGraphConflictSupport support, String conflictKey) {
    if (context == null
        || context.edges().isEmpty()
        || context.pathNodes().isEmpty()
        || support == null
        || conflictKey == null) {
      return Optional.empty();
    }
    boolean sawSameConflict = false;
    List<RailEdge> edges = context.edges();
    List<NodeId> nodes = context.pathNodes();
    for (int i = 0; i < edges.size(); i++) {
      RailEdge edge = edges.get(i);
      if (edge == null || edge.id() == null) {
        continue;
      }
      Optional<String> edgeConflict = support.conflictKeyForEdge(edge.id());
      if (edgeConflict.isPresent() && edgeConflict.get().equals(conflictKey)) {
        sawSameConflict = true;
        continue;
      }
      if (sawSameConflict) {
        int nodeIndex = Math.min(i, nodes.size() - 1);
        return Optional.ofNullable(nodes.get(nodeIndex));
      }
    }
    return Optional.empty();
  }

  private static boolean isDirectionalSingleConflict(OccupancyResource resource) {
    return resource != null
        && resource.kind() == ResourceKind.CONFLICT
        && resource.key().startsWith("single:")
        && !resource.key().contains(":cycle:");
  }

  /**
   * 清理同线路后车留在前车授权窗口内的预占用。
   *
   * <p>后车 lookahead 可能提前占到前车当前节点或下一段边；若前车下一次 signal tick 再申请这些资源，就会被后车错误阻塞。 这里仅清理“同 Route
   * 且进度索引更小”的列车，并且只清理前向授权资源，不碰尾部保护资源。
   */
  private int releaseSpeculativeClaimsFromBehindSameRoute(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode,
      RailGraph graph,
      List<OccupancyResource> authorityResources) {
    if (occupancyManager == null
        || progressRegistry == null
        || trainName == null
        || trainName.isBlank()
        || route == null
        || currentIndex < 0
        || authorityResources == null
        || authorityResources.isEmpty()) {
      return 0;
    }
    Set<OccupancyResource> authoritySet = Set.copyOf(authorityResources);
    Set<String> releasedOwners = new LinkedHashSet<>();
    int released = 0;
    for (OccupancyClaim claim : occupancyManager.snapshotClaims()) {
      if (!isSpeculativeBehindClaim(
          trainName, route, currentIndex, currentNode, graph, authoritySet, claim)) {
        continue;
      }
      if (occupancyManager.releaseResource(claim.resource(), Optional.of(claim.trainName()))) {
        released++;
        releasedOwners.add(claim.trainName());
      }
    }
    if (released > 0) {
      debugLogger.accept(
          "清理后车前瞻占用: train=" + trainName + " released=" + released + " owners=" + releasedOwners);
    }
    return released;
  }

  /**
   * 清理同线路后车留在前车授权窗口内的冲突队列条目。
   *
   * <p>后车可能只刷新了 queue entry，尚未写入 claim。若不清理，这类队列条目会在 {@code canEnter()} 阶段把前车判为非队头，导致前车被后车反向锁成
   * STOP。这里仅清理“同 Route 且进度索引更小”的列车，并且只作用于当前前向授权请求覆盖的冲突资源，不影响对向列车、交叉线路或真实道岔冲突。
   */
  private int releaseSpeculativeQueueEntriesFromBehindSameRoute(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode,
      RailGraph graph,
      List<OccupancyResource> authorityResources) {
    if (!(occupancyManager instanceof OccupancyQueueSupport queueSupport)
        || progressRegistry == null
        || trainName == null
        || trainName.isBlank()
        || route == null
        || currentIndex < 0
        || authorityResources == null
        || authorityResources.isEmpty()) {
      return 0;
    }
    Set<OccupancyResource> authoritySet = Set.copyOf(authorityResources);
    Map<String, LinkedHashSet<OccupancyResource>> resourcesByTrain = new LinkedHashMap<>();
    for (OccupancyQueueSnapshot snapshot : queueSupport.snapshotQueues()) {
      if (snapshot == null || snapshot.resource() == null) {
        continue;
      }
      OccupancyResource resource = snapshot.resource();
      if (!authoritySet.contains(resource)) {
        continue;
      }
      for (OccupancyQueueEntry entry : snapshot.entries()) {
        if (!isSpeculativeBehindQueueEntry(
            trainName, route, currentIndex, currentNode, graph, entry)) {
          continue;
        }
        resourcesByTrain
            .computeIfAbsent(entry.trainName(), unused -> new LinkedHashSet<>())
            .add(resource);
      }
    }
    if (resourcesByTrain.isEmpty()) {
      return 0;
    }
    int removed = 0;
    for (Map.Entry<String, LinkedHashSet<OccupancyResource>> entry : resourcesByTrain.entrySet()) {
      removed += queueSupport.removeQueueEntries(entry.getKey(), List.copyOf(entry.getValue()));
    }
    if (removed > 0) {
      debugLogger.accept(
          "清理后车冲突队列: train="
              + trainName
              + " removed="
              + removed
              + " owners="
              + resourcesByTrain.keySet());
    }
    return removed;
  }

  private boolean isSpeculativeBehindClaim(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode,
      RailGraph graph,
      Set<OccupancyResource> authorityResources,
      OccupancyClaim claim) {
    if (claim == null
        || claim.resource() == null
        || claim.trainName() == null
        || claim.trainName().equalsIgnoreCase(trainName)
        || !authorityResources.contains(claim.resource())) {
      return false;
    }
    if (claim.role() != ClaimRole.MOVEMENT_REQUIRED
        && claim.role() != ClaimRole.LOOKAHEAD_PREVIEW) {
      return false;
    }
    if (turnbackFootprintGuards.protectedResources(claim.trainName()).contains(claim.resource())) {
      return false;
    }
    if (claim.routeId().isEmpty() || !claim.routeId().get().equals(route.id())) {
      return false;
    }
    Optional<RouteProgressRegistry.RouteProgressEntry> ownerEntryOpt =
        progressRegistry.get(claim.trainName());
    if (ownerEntryOpt.isEmpty()) {
      return false;
    }
    RouteProgressRegistry.RouteProgressEntry ownerEntry = ownerEntryOpt.get();
    return isBehindOnSameRouteSegment(
        trainName, route, currentIndex, currentNode, graph, ownerEntry);
  }

  private boolean isSpeculativeBehindQueueEntry(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode,
      RailGraph graph,
      OccupancyQueueEntry entry) {
    if (entry == null
        || entry.trainName() == null
        || entry.trainName().equalsIgnoreCase(trainName)
        || route == null
        || currentIndex < 0) {
      return false;
    }
    Optional<RouteProgressRegistry.RouteProgressEntry> ownerEntryOpt =
        progressRegistry.get(entry.trainName());
    if (ownerEntryOpt.isEmpty()) {
      return false;
    }
    RouteProgressRegistry.RouteProgressEntry ownerEntry = ownerEntryOpt.get();
    return isBehindOnSameRouteSegment(
        trainName, route, currentIndex, currentNode, graph, ownerEntry);
  }

  /**
   * 判定同一 Route 上的占用者是否位于当前列车后方。
   *
   * <p>过去只比较 route index；当两列车同向跟驰且都处在同一个 route 段内时，后车可能已经写入前瞻 claim/queue，前车也仍是相同 index，
   * 于是旧逻辑无法清理后车前瞻，前车会被反向打成红灯。这里补充比较 {@code lastPassedGraphNode} 在当前段最短路上的顺序：同 index
   * 但最后经过节点更靠后的列车才保留阻塞，更靠前/靠后的前瞻会被清理。
   */
  private boolean isBehindOnSameRouteSegment(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode,
      RailGraph graph,
      RouteProgressRegistry.RouteProgressEntry ownerEntry) {
    if (route == null || ownerEntry == null || !route.id().equals(ownerEntry.routeId())) {
      return false;
    }
    if (ownerEntry.currentIndex() < currentIndex) {
      return true;
    }
    if (ownerEntry.currentIndex() > currentIndex) {
      return false;
    }
    if (graph == null || currentNode == null || currentIndex < 0) {
      return false;
    }
    Optional<NodeId> ownerNodeOpt = ownerEntry.lastPassedGraphNode();
    if (ownerNodeOpt.isEmpty()) {
      return false;
    }
    NodeId ownerNode = ownerNodeOpt.get();
    if (ownerNode.equals(currentNode)) {
      return false;
    }
    if (currentIndex + 1 >= route.waypoints().size()) {
      return false;
    }
    NodeId segmentStart = resolveEffectiveNode(trainName, route, currentIndex);
    NodeId segmentEnd = resolveEffectiveNode(trainName, route, currentIndex + 1);
    if (segmentStart == null || segmentEnd == null) {
      return false;
    }
    Optional<RailGraphPath> pathOpt =
        pathFinder.shortestPath(
            graph, segmentStart, segmentEnd, RailGraphPathFinder.Options.shortestDistance());
    if (pathOpt.isEmpty()) {
      return false;
    }
    List<NodeId> pathNodes = pathOpt.get().nodes();
    int currentPosition = pathNodes.indexOf(currentNode);
    int ownerPosition = pathNodes.indexOf(ownerNode);
    return ownerPosition >= 0 && currentPosition >= 0 && ownerPosition < currentPosition;
  }

  /**
   * 成功取得前向授权后，尽力补齐尾部保护资源。
   *
   * <p>该步骤不能反过来影响信号：若后车已经过近并持有尾部资源，前车仍应继续前进，真正需要停车的是后车。
   */
  private void retainRearGuardOccupancyBestEffort(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      List<NodeId> effectiveNodes,
      Optional<MovementPlanSnapshot> movementPlan,
      RailGraph graph,
      ConfigManager.RuntimeSettings runtimeSettings,
      Instant now) {
    if (occupancyManager == null
        || runtimeSettings == null
        || runtimeSettings.rearGuardEdges() <= 0
        || graph == null
        || route == null
        || effectiveNodes == null) {
      return;
    }
    OccupancyRequestBuilder rearGuardBuilder =
        new OccupancyRequestBuilder(
            graph,
            runtimeSettings.lookaheadEdges(),
            runtimeSettings.minClearEdges(),
            runtimeSettings.rearGuardEdges(),
            runtimeSettings.switcherZoneEdges(),
            debugLogger);
    OccupancyRequest rearGuardRequest =
        movementPlan
            .map(
                plan ->
                    rearGuardBuilder.buildRearGuardRequestFromPlan(
                        trainName,
                        Optional.ofNullable(route.id()),
                        effectiveNodes,
                        currentIndex,
                        now,
                        0,
                        AuthorizationPurpose.RUNTIME_MOVE,
                        plan))
            .orElseGet(
                () ->
                    rearGuardBuilder.buildRearGuardRequestFromNodes(
                        trainName,
                        Optional.ofNullable(route.id()),
                        effectiveNodes,
                        currentIndex,
                        now,
                        0,
                        AuthorizationPurpose.RUNTIME_MOVE));
    occupancyManager.acquire(rearGuardRequest);
  }

  private static OptionalLong minOptionalLong(OptionalLong first, OptionalLong second) {
    if (first == null || first.isEmpty()) {
      return second != null ? second : OptionalLong.empty();
    }
    if (second == null || second.isEmpty()) {
      return first;
    }
    return OptionalLong.of(Math.min(first.getAsLong(), second.getAsLong()));
  }

  /**
   * 解析移动授权使用的约束距离。
   *
   * <p>优先使用 blocker/caution 等硬约束；若前方没有硬约束，则使用授权窗口末端。这里不再回退“到下一 route 节点距离”，避免把普通站点距离误当成闭塞终点。
   */
  private static OptionalLong resolveMovementAuthorityDistance(
      OptionalLong constraintDistance, OptionalLong authorityEndDistance) {
    if (constraintDistance != null && constraintDistance.isPresent()) {
      return constraintDistance;
    }
    return authorityEndDistance != null ? authorityEndDistance : OptionalLong.empty();
  }

  private AuthorityEnd resolveAuthorityEnd(
      RailGraph graph,
      List<NodeId> effectiveNodes,
      int currentIndex,
      OccupancyRequestContext authorizationContext) {
    if (graph == null || authorizationContext == null) {
      return AuthorityEnd.none();
    }
    List<RailEdge> authorizedEdges = authorizationContext.edges();
    int authorizedEdgeCount = authorizedEdges == null ? 0 : authorizedEdges.size();
    if (authorizedEdgeCount <= 0) {
      return AuthorityEnd.none();
    }
    long distance = 0L;
    for (RailEdge edge : authorizedEdges) {
      if (edge != null) {
        distance += Math.max(0L, edge.lengthBlocks());
      }
    }
    Optional<MovementPlanSnapshot> planOpt = authorizationContext.request().movementPlanSnapshot();
    if (planOpt.isPresent()) {
      MovementPlanSnapshot plan = planOpt.get();
      List<DirectedTraversalContext.DirectedEdge> planEdges = plan.directedEdges();
      if (planEdges.size() > authorizedEdgeCount) {
        DirectedTraversalContext.DirectedEdge firstUnauthorized =
            planEdges.get(authorizedEdgeCount);
        String resource = OccupancyResource.forEdge(firstUnauthorized.edgeId()).toString();
        return new AuthorityEnd(
            OptionalLong.of(Math.max(0L, distance)),
            resource,
            authorizedEdgeCount,
            AuthorityEndReason.ARTIFICIAL_WINDOW_LIMIT,
            true,
            true,
            true);
      }
      String resource =
          plan.expandedPathNodes().isEmpty()
              ? "route_end"
              : OccupancyResource.forNode(
                      plan.expandedPathNodes().get(plan.expandedPathNodes().size() - 1))
                  .toString();
      return new AuthorityEnd(
          OptionalLong.of(Math.max(0L, distance)),
          resource,
          authorizedEdgeCount,
          AuthorityEndReason.ROUTE_STOP_OR_TERMINAL,
          true,
          false,
          false);
    }
    Optional<ExpandedRoutePath> expandedOpt =
        expandForwardScanPath(graph, effectiveNodes, currentIndex, authorizedEdgeCount + 1);
    if (expandedOpt.isEmpty() || expandedOpt.get().edges().size() <= authorizedEdgeCount) {
      String resource = "route_end";
      if (expandedOpt.isPresent() && !expandedOpt.get().nodes().isEmpty()) {
        NodeId endNode = expandedOpt.get().nodes().get(expandedOpt.get().nodes().size() - 1);
        if (endNode != null) {
          resource = OccupancyResource.forNode(endNode).toString();
        }
      }
      return new AuthorityEnd(
          OptionalLong.of(Math.max(0L, distance)),
          resource,
          authorizedEdgeCount,
          AuthorityEndReason.ROUTE_STOP_OR_TERMINAL,
          false,
          false,
          false);
    }
    RailEdge firstUnauthorizedEdge = expandedOpt.get().edges().get(authorizedEdgeCount);
    String resource =
        firstUnauthorizedEdge == null
            ? "none"
            : OccupancyResource.forEdge(firstUnauthorizedEdge.id()).toString();
    return new AuthorityEnd(
        OptionalLong.of(Math.max(0L, distance)),
        resource,
        authorizedEdgeCount,
        AuthorityEndReason.ARTIFICIAL_WINDOW_LIMIT,
        false,
        true,
        true);
  }

  private static Optional<NodeId> authorityBoundaryNode(
      OccupancyRequest request, AuthorityEnd authorityEnd) {
    if (request == null || authorityEnd == null || authorityEnd.authorizedEdgeCount() <= 0) {
      return Optional.empty();
    }
    Optional<MovementPlanSnapshot> planOpt = request.movementPlanSnapshot();
    if (planOpt.isEmpty()) {
      return Optional.empty();
    }
    List<NodeId> nodes = planOpt.get().expandedPathNodes();
    int boundaryIndex = authorityEnd.authorizedEdgeCount();
    if (nodes == null || boundaryIndex < 0 || boundaryIndex >= nodes.size()) {
      return Optional.empty();
    }
    return Optional.ofNullable(nodes.get(boundaryIndex));
  }

  private SmartAdmissionResult evaluateSmartSingleCorridorAdmission(
      String trainName,
      RailGraph graph,
      OccupancyRequestContext context,
      AuthorityEnd authorityEnd,
      boolean requireAuthorityEnd,
      SmartAdmissionContext admissionContext) {
    SmartAdmissionContext safeContext =
        admissionContext == null
            ? SmartAdmissionContext.entry("smart-admission")
            : admissionContext;
    SmartDispatcherMode mode = smartDispatcherMode();
    traceSmartTrafficControlGate(
        trainName, mode, safeContext.source(), DispatchEffectClass.SIGNAL_CONSTRAINT);
    if (mode == SmartDispatcherMode.OFF) {
      debugLogger.accept(
          "SMART_DISPATCH_DISABLED train="
              + trainName
              + " source="
              + safeContext.source()
              + " mode="
              + mode);
      return SmartAdmissionResult.notApplicable(safeContext);
    }
    if (graph == null || context == null || context.edges().isEmpty()) {
      traceSmartRegionDataUnknown(
          trainName, context, null, safeContext, "graph-or-context-missing");
      return SmartAdmissionResult.notApplicable(safeContext);
    }
    Optional<OccupancyResource> firstSingle = singleConflictForEdge(graph, context.edges().get(0));
    if (firstSingle.isEmpty()) {
      return SmartAdmissionResult.notApplicable(safeContext);
    }
    OccupancyResource conflict = firstSingle.get();
    OccupancyRequest request = context.request();
    if (request == null || !request.resourceList().contains(conflict)) {
      SmartAdmissionResult result =
          smartAdmissionBlocked(
              trainName,
              context,
              conflict,
              CorridorDirection.UNKNOWN,
              null,
              null,
              safeContext,
              SmartAdmissionDecision.REJECT_REGION_OCCUPIED_UNSAFE,
              "single-conflict-missing",
              false);
      traceSmartAdmissionResult(trainName, context, result);
      return result;
    }
    RailGraphConflictSupport support = singleConflictMembershipSupport(graph, conflict);
    CorridorDirection direction = request.corridorDirections().get(conflict.key());
    if (hasClaimByTrain(conflict, trainName)) {
      if (singleRegionOppositeOrUnknownExternalBarrier(trainName, List.of(conflict), direction)) {
        SmartAdmissionResult result =
            smartAdmissionBlocked(
                trainName,
                context,
                conflict,
                direction == null ? CorridorDirection.UNKNOWN : direction,
                null,
                null,
                safeContext,
                SmartAdmissionDecision.REJECT_OPPOSITE_DIRECTION,
                SimpleOccupancyManager.OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER,
                true);
        traceSingleRegionHardBarrier(
            trainName,
            DispatchAction.SAME_DIRECTION_FOLLOW_THROUGH_PREVIEW,
            safeContext.source(),
            List.of(conflict));
        traceSmartAdmissionResult(trainName, context, result);
        return result;
      }
      EntryLookaheadEvaluator.Result lookahead =
          EntryLookaheadEvaluator.evaluate(
              context.request().movementPlanSnapshot().orElse(null),
              support,
              conflict,
              context.edges().size(),
              context
                  .request()
                  .movementPlanSnapshot()
                  .map(plan -> plan.directedEdges().size())
                  .orElse(context.edges().size()));
      if (lookahead.failClosed()) {
        SmartAdmissionResult result =
            smartAdmissionBlocked(
                trainName,
                context,
                conflict,
                direction == null ? CorridorDirection.UNKNOWN : direction,
                null,
                null,
                safeContext,
                SmartAdmissionDecision.REJECT_LEADER_EXIT_NOT_VISIBLE,
                "ALREADY_INSIDE_CONTINUE_MISSING_EXIT_PROOF",
                false);
        traceEntryLookaheadBlocked(trainName, context, conflict, lookahead);
        traceSmartAdmissionResult(trainName, context, result);
        return result;
      }
      SingleZoneAdmissionState admission =
          singleZoneAdmissionState(conflict, trainName, direction, authorityEnd);
      Optional<SmartAdmissionResult> sameDirectionLeaderBlock =
          evaluateAlreadyInsideSameDirectionLeaderBlock(
              trainName,
              graph,
              support,
              context,
              conflict,
              direction,
              lookahead,
              admission,
              safeContext);
      if (sameDirectionLeaderBlock.isPresent()) {
        SmartAdmissionResult result = sameDirectionLeaderBlock.get();
        traceSmartAdmissionResult(trainName, context, result);
        return result;
      }
      forgetSameDirectionLeaderHold(trainName);
      SmartAdmissionResult result =
          smartAdmissionAllowed(
              context,
              conflict,
              direction,
              null,
              null,
              safeContext,
              SmartAdmissionDecision.ALLOW_ALREADY_INSIDE_CONTINUE,
              "already-inside-single-region",
              true);
      traceSmartAdmissionResult(trainName, context, result);
      debugLogger.accept(
          "SMART_ALREADY_INSIDE_REGION_BYPASS_ENTRY_GATE train="
              + trainName
              + " regionId="
              + conflict.key()
              + " source="
              + safeContext.source());
      return result;
    }
    if (direction == null || direction == CorridorDirection.UNKNOWN) {
      SmartAdmissionResult result =
          smartAdmissionBlocked(
              trainName,
              context,
              conflict,
              CorridorDirection.UNKNOWN,
              null,
              null,
              safeContext,
              SmartAdmissionDecision.REJECT_UNKNOWN_DIRECTION,
              "single-conflict-direction-unknown",
              false);
      traceSmartAdmissionResult(trainName, context, result);
      return result;
    }
    if (requireAuthorityEnd && (authorityEnd == null || authorityEnd.distanceBlocks().isEmpty())) {
      SmartAdmissionResult result =
          smartAdmissionBlocked(
              trainName,
              context,
              conflict,
              direction,
              null,
              null,
              safeContext,
              SmartAdmissionDecision.REJECT_LEADER_EXIT_NOT_VISIBLE,
              "authority-end-missing",
              false);
      traceSmartRegionDataUnknown(
          trainName, context, conflict, safeContext, "authority-end-missing");
      traceSmartAdmissionResult(trainName, context, result);
      return result;
    }
    EntryLookaheadEvaluator.Result lookahead =
        EntryLookaheadEvaluator.evaluate(
            context.request().movementPlanSnapshot().orElse(null),
            support,
            conflict,
            context.edges().size(),
            context
                .request()
                .movementPlanSnapshot()
                .map(plan -> plan.directedEdges().size())
                .orElse(context.edges().size()));
    traceSingleZoneAdmissionCheck(trainName, context, conflict, direction, lookahead);
    traceSmartRegionView(trainName, context, conflict, direction, lookahead, safeContext);
    SingleZoneAdmissionState admission =
        singleZoneAdmissionState(conflict, trainName, direction, authorityEnd);
    Optional<SmartAdmissionResult> queueOnlyAdmission =
        evaluateQueueOnlySmartAdmission(
            trainName, context, conflict, direction, lookahead, admission, safeContext);
    if (queueOnlyAdmission.isPresent() && !queueOnlyAdmission.get().allowed()) {
      SmartAdmissionResult result = queueOnlyAdmission.get();
      traceSingleZoneAdmissionDecision(
          trainName,
          context,
          conflict,
          direction,
          lookahead,
          admission,
          result.allowed(),
          result.reason());
      traceSmartAdmissionResult(trainName, context, result);
      return result;
    }
    ThroatSectionAtomicCheck throatSection =
        throatSectionAtomicClear(conflict, context, trainName, graph, support);
    traceThroatSectionAtomic(trainName, conflict, throatSection);
    if (throatSection.applicable() && !throatSection.clear()) {
      SmartAdmissionResult result =
          smartAdmissionBlocked(
              trainName,
              context,
              conflict,
              direction,
              lookahead,
              admission,
              safeContext,
              SmartAdmissionDecision.REJECT_THROAT_SECTION_BUSY,
              "throat-section-not-atomically-clear",
              false);
      traceSingleZoneAdmissionDecision(
          trainName, context, conflict, direction, lookahead, admission, false, result.reason());
      traceSmartAdmissionResult(trainName, context, result);
      return result;
    }
    if (queueOnlyAdmission.isPresent()) {
      SmartAdmissionResult result = queueOnlyAdmission.get();
      traceSingleZoneAdmissionDecision(
          trainName,
          context,
          conflict,
          direction,
          lookahead,
          admission,
          result.allowed(),
          result.reason());
      traceSmartAdmissionResult(trainName, context, result);
      return result;
    }
    SameDirectionLeaderDrainPrediction leaderDrainPrediction =
        predictSameDirectionLeaderDrain(
            trainName, graph, support, conflict, direction, admission, context);
    traceSameDirectionLeaderDrainPrediction(
        trainName, context, conflict, direction, admission, leaderDrainPrediction);
    traceSameDirectionFollowThroughPreview(
        trainName,
        context,
        conflict,
        direction,
        authorityEnd,
        lookahead,
        admission,
        leaderDrainPrediction);
    traceRouteUnlockPotentialPreview(
        trainName,
        context,
        conflict,
        direction,
        authorityEnd,
        lookahead,
        admission,
        leaderDrainPrediction);
    if (lookahead.failClosed()) {
      traceEntryLookaheadBlocked(trainName, context, conflict, lookahead);
      if (admission.hasOtherPresence()
          && !admission.oppositeOrUnknownPresence()
          && !admission.leaderStalled()) {
        SmartAdmissionResult result =
            smartAdmissionBlocked(
                trainName,
                context,
                conflict,
                direction,
                lookahead,
                admission,
                safeContext,
                SmartAdmissionDecision.REJECT_LEADER_EXIT_NOT_VISIBLE,
                "entry-lookahead-exit-not-feasible",
                false);
        traceSingleZoneAdmissionDecision(
            trainName,
            context,
            conflict,
            direction,
            lookahead,
            admission,
            false,
            "exit-not-visible");
        traceSmartAdmissionResult(trainName, context, result);
        return result;
      }
      traceSingleZoneAdmissionDecision(
          trainName, context, conflict, direction, lookahead, admission, true, "zone-empty");
    }
    if (admission.unknownDirectionPresent()) {
      SmartAdmissionResult result =
          smartAdmissionBlocked(
              trainName,
              context,
              conflict,
              direction,
              lookahead,
              admission,
              safeContext,
              SmartAdmissionDecision.REJECT_UNKNOWN_DIRECTION,
              admission.blockerReason(),
              false);
      traceSingleZoneAdmissionDecision(
          trainName,
          context,
          conflict,
          direction,
          lookahead,
          admission,
          false,
          admission.blockerReason());
      traceSmartAdmissionResult(trainName, context, result);
      return result;
    }
    if (admission.oppositeDirectionPresent()) {
      SmartAdmissionResult result =
          smartAdmissionBlocked(
              trainName,
              context,
              conflict,
              direction,
              lookahead,
              admission,
              safeContext,
              SmartAdmissionDecision.REJECT_OPPOSITE_DIRECTION,
              admission.blockerReason(),
              false);
      traceSingleZoneAdmissionDecision(
          trainName,
          context,
          conflict,
          direction,
          lookahead,
          admission,
          false,
          admission.blockerReason());
      traceSmartAdmissionResult(trainName, context, result);
      return result;
    }
    SameDirectionCorridorPriority corridorPriority =
        sameDirectionCorridorPriority(trainName, graph, context, admission);
    traceSameDirectionCorridorPriority(
        trainName, conflict, admission, corridorPriority, "can-enter");
    if (!corridorPriority.selfIsCorridorPriority()
        && (admission.leaderStalled()
            || (admission.sameDirectionLeader() && !admission.leaderProgressFresh()))) {
      SmartAdmissionResult result =
          smartAdmissionBlocked(
              trainName,
              context,
              conflict,
              direction,
              lookahead,
              admission,
              safeContext,
              SmartAdmissionDecision.REJECT_LEADER_STALLED,
              admission.leaderStalled()
                  ? admission.blockerReason()
                  : "same-direction-leader-progress-stale",
              false);
      traceSingleZoneAdmissionDecision(
          trainName, context, conflict, direction, lookahead, admission, false, result.reason());
      traceSmartAdmissionResult(trainName, context, result);
      return result;
    }
    if (!corridorPriority.selfIsCorridorPriority()
        && admission.sameDirectionLeader()
        && sameDirectionLeaderTerminalOrDwellShouldBlock(admission, leaderDrainPrediction)) {
      SmartAdmissionResult result =
          smartAdmissionBlocked(
              trainName,
              context,
              conflict,
              direction,
              lookahead,
              admission,
              safeContext,
              SmartAdmissionDecision.REJECT_DOWNSTREAM_BLOCKED,
              "same-direction-leader-terminal-or-dwell",
              false);
      traceSmartAdmissionResult(trainName, context, result);
      return result;
    }
    if (!corridorPriority.selfIsCorridorPriority()
        && admission.sameDirectionLeader()
        && leaderDrainPrediction.applicable()
        && !leaderDrainPrediction.drainProven()) {
      SmartAdmissionResult result =
          smartAdmissionBlocked(
              trainName,
              context,
              conflict,
              direction,
              lookahead,
              admission,
              safeContext,
              SmartAdmissionDecision.REJECT_LEADER_EXIT_NOT_VISIBLE,
              leaderDrainPrediction.reason(),
              false);
      traceSingleZoneAdmissionDecision(
          trainName, context, conflict, direction, lookahead, admission, false, result.reason());
      traceSmartAdmissionResult(trainName, context, result);
      return result;
    }
    if (!corridorPriority.selfIsCorridorPriority()
        && admission.sameDirectionLeader()
        && !lookahead.exitFeasible()) {
      SmartAdmissionResult result =
          smartAdmissionBlocked(
              trainName,
              context,
              conflict,
              direction,
              lookahead,
              admission,
              safeContext,
              SmartAdmissionDecision.REJECT_LEADER_EXIT_NOT_VISIBLE,
              "same-direction-leader-exit-not-visible",
              false);
      traceSmartAdmissionResult(trainName, context, result);
      return result;
    }
    if (admission.hasOtherPresence() && !admission.followerSafeHoldPoint()) {
      SmartAdmissionResult result =
          smartAdmissionBlocked(
              trainName,
              context,
              conflict,
              direction,
              lookahead,
              admission,
              safeContext,
              SmartAdmissionDecision.REJECT_REGION_OCCUPIED_UNSAFE,
              "follower-hold-point-missing",
              false);
      traceSingleZoneAdmissionDecision(
          trainName,
          context,
          conflict,
          direction,
          lookahead,
          admission,
          false,
          "follower-hold-point-missing");
      traceSmartAdmissionResult(trainName, context, result);
      return result;
    }
    SmartAdmissionResult result =
        smartAdmissionAllowed(
            context,
            conflict,
            direction,
            lookahead,
            admission,
            safeContext,
            SmartAdmissionDecision.ALLOW_ENTER,
            admission.hasOtherPresence() ? "same-direction-leader-safe" : "zone-empty",
            false);
    traceSingleZoneAdmissionDecision(
        trainName, context, conflict, direction, lookahead, admission, true, result.reason());
    traceSmartAdmissionResult(trainName, context, result);
    return result;
  }

  /**
   * 咽喉区间整段原子准入。
   *
   * <p>STATION/DEPOT 是安全停车点；STATION_THROAT、DEPOT_THROAT 与 SWITCHER 组成不可中停咽喉。列车从安全点进入咽喉前，
   * 必须能沿本轮展开路径看到咽喉清出点，并确认入口之后到清出点之间所有 NODE/EDGE/CONFLICT 资源未被他车真实占用。排队只表示仲裁位次， 由完整原子请求的 Gate Queue
   * preview/正式 canEnter 统一选出赢家，不能在这里再次当成物理占用。
   *
   * <p>清出点可以是下一个 STATION/DEPOT，也可以是紧随道岔群之后的 INTERVAL 节点。后者覆盖“车站 -> 道岔群 -> 开放区间”的出站拓扑，
   * 避免把整段站间区间错误并入咽喉原子段。
   */
  private ThroatSectionAtomicCheck throatSectionAtomicClear(
      OccupancyResource conflict,
      OccupancyRequestContext context,
      String trainName,
      RailGraph graph,
      RailGraphConflictSupport support) {
    if (conflict == null || context == null || graph == null || support == null) {
      return ThroatSectionAtomicCheck.notApplicable("missing-input");
    }
    ThroatAtomicPath path = ThroatAtomicPath.from(context);
    if (path.nodes().size() < 2 || path.edges().isEmpty()) {
      return ThroatSectionAtomicCheck.notApplicable("path-missing");
    }
    int entryEdgeIndex = throatEntryEdgeIndex(path, conflict, support);
    if (entryEdgeIndex < 0) {
      return ThroatSectionAtomicCheck.notApplicable("conflict-not-on-expanded-path");
    }
    if (entryEdgeIndex + 1 >= path.nodes().size()) {
      return ThroatSectionAtomicCheck.blocked(
          "throat-topology-unresolvable", path.nodes().get(entryEdgeIndex), null, "-", List.of());
    }
    NodeId entry = path.nodes().get(entryEdgeIndex);
    NodeId firstInside = path.nodes().get(entryEdgeIndex + 1);
    if (!isThroatAtomicNode(graph, firstInside)) {
      return ThroatSectionAtomicCheck.notApplicable("not-throat-section");
    }
    int exitIndex = throatExitPointIndex(path.nodes(), entryEdgeIndex + 1, graph);
    if (exitIndex < 0 || exitIndex > path.edges().size()) {
      return ThroatSectionAtomicCheck.blocked(
          "throat-topology-unresolvable", entry, null, "-", List.of());
    }
    NodeId exit = path.nodes().get(exitIndex);
    LinkedHashSet<OccupancyResource> resources =
        throatSectionResources(path, entryEdgeIndex, exitIndex, graph);
    if (resources.isEmpty()) {
      return ThroatSectionAtomicCheck.blocked(
          "throat-topology-unresolvable", entry, exit, "-", resources);
    }
    Optional<String> occupiedResource = firstExternalThroatSectionClaim(resources, trainName);
    if (occupiedResource.isPresent()) {
      return ThroatSectionAtomicCheck.blocked(
          "throat-section-resource-occupied", entry, exit, occupiedResource.get(), resources);
    }
    return ThroatSectionAtomicCheck.clear(entry, exit, resources);
  }

  private int throatEntryEdgeIndex(
      ThroatAtomicPath path, OccupancyResource conflict, RailGraphConflictSupport support) {
    if (path == null || conflict == null || support == null) {
      return -1;
    }
    for (int i = 0; i < path.edges().size(); i++) {
      DirectedTraversalContext.DirectedEdge edge = path.edges().get(i);
      if (edge != null
          && support.conflictKeyForEdge(edge.edgeId()).filter(conflict.key()::equals).isPresent()) {
        return i;
      }
    }
    return -1;
  }

  private int throatExitPointIndex(List<NodeId> nodes, int firstInsideIndex, RailGraph graph) {
    if (nodes == null || firstInsideIndex < 0 || firstInsideIndex >= nodes.size()) {
      return -1;
    }
    for (int i = firstInsideIndex; i < nodes.size(); i++) {
      NodeId node = nodes.get(i);
      if (isThroatSafeStopNode(graph, node) || isThroatClearanceExitNode(graph, node)) {
        return i;
      }
      if (!isThroatAtomicNode(graph, node)) {
        return -1;
      }
    }
    return -1;
  }

  private LinkedHashSet<OccupancyResource> throatSectionResources(
      ThroatAtomicPath path, int entryEdgeIndex, int exitSafePointIndex, RailGraph graph) {
    LinkedHashSet<OccupancyResource> resources = new LinkedHashSet<>();
    if (path == null || graph == null || entryEdgeIndex < 0 || exitSafePointIndex < 0) {
      return resources;
    }
    for (int i = entryEdgeIndex; i < exitSafePointIndex && i < path.edges().size(); i++) {
      DirectedTraversalContext.DirectedEdge edge = path.edges().get(i);
      if (edge == null) {
        continue;
      }
      findGraphEdge(graph, edge.edgeId())
          .map(found -> OccupancyResourceResolver.resourcesForEdge(graph, found))
          .ifPresent(resources::addAll);
    }
    for (int i = entryEdgeIndex + 1; i <= exitSafePointIndex && i < path.nodes().size(); i++) {
      NodeId node = path.nodes().get(i);
      if (node != null) {
        graph
            .findNode(node)
            .map(OccupancyResourceResolver::resourcesForNode)
            .ifPresentOrElse(
                resources::addAll, () -> resources.add(OccupancyResource.forNode(node)));
      }
    }
    return resources;
  }

  private Optional<String> firstExternalThroatSectionClaim(
      Collection<OccupancyResource> resources, String trainName) {
    if (resources == null || resources.isEmpty()) {
      return Optional.empty();
    }
    List<OccupancyClaim> claims =
        occupancyManager == null ? null : occupancyManager.snapshotClaims();
    if (claims == null) {
      return Optional.of("occupancy-snapshot-unavailable");
    }
    Set<OccupancyResource> resourceSet = Set.copyOf(resources);
    for (OccupancyClaim claim : claims) {
      if (claim == null
          || claim.resource() == null
          || !resourceSet.contains(claim.resource())
          || TrainNameNormalizer.sameLogicalTrain(trainName, claim.trainName())) {
        continue;
      }
      return Optional.of(claim.resource() + "@claim:" + claim.trainName());
    }
    return Optional.empty();
  }

  private boolean isThroatAtomicNode(RailGraph graph, NodeId nodeId) {
    if (nodeId == null) {
      return false;
    }
    if (isSwitcherNode(graph, nodeId)) {
      return true;
    }
    return resolveWaypointMetadata(graph, nodeId)
        .map(
            metadata ->
                metadata.kind() == WaypointKind.STATION_THROAT
                    || metadata.kind() == WaypointKind.DEPOT_THROAT
                    || metadata.kind() == WaypointKind.SWITCHER)
        .orElse(false);
  }

  private boolean isThroatSafeStopNode(RailGraph graph, NodeId nodeId) {
    return isStationOrDepotBehaviorNode(nodeId, graph);
  }

  private boolean isThroatClearanceExitNode(RailGraph graph, NodeId nodeId) {
    return resolveWaypointMetadata(graph, nodeId)
        .map(metadata -> metadata.kind() == WaypointKind.INTERVAL)
        .orElse(false);
  }

  /**
   * 判断同向入口准入是否应由本车作为 corridor 优先侧继续走后续物理闭塞检查。
   *
   * <p>该裁决只在没有对向/未知方向占用时生效，且只跳过同向 leader 的软阻塞；真实 {@code NODE}/{@code EDGE} 硬占用仍由后续 {@code
   * OccupancyManager.canEnter()} 判定。
   */
  private SameDirectionCorridorPriority sameDirectionCorridorPriority(
      String trainName,
      RailGraph graph,
      OccupancyRequestContext context,
      SingleZoneAdmissionState admission) {
    if (admission == null || !admission.sameDirectionLeader()) {
      return SameDirectionCorridorPriority.notApplicable("not-same-direction-leader");
    }
    if (admission.oppositeDirectionPresent()
        || admission.unknownDirectionPresent()
        || admission.oppositeOrUnknownPresence()) {
      return SameDirectionCorridorPriority.notApplicable("opposite-or-unknown-present");
    }
    SameDirectionLeaderOrder leaderOrder =
        sameDirectionCorridorPriorityOrder(trainName, admission.leaderTrain(), context, graph);
    boolean selfPriority =
        leaderOrder == SameDirectionLeaderOrder.TRAIN_AHEAD
            || (leaderOrder == SameDirectionLeaderOrder.UNKNOWN
                && admission.leaderStalled()
                && !deterministicSameDirectionYield(trainName, admission.leaderTrain()));
    String reason =
        switch (leaderOrder) {
          case TRAIN_AHEAD -> "candidate-train-ahead";
          case LEADER_AHEAD -> "leader-ahead";
          case UNKNOWN -> selfPriority
              ? "leader-order-unknown-deterministic-pass"
              : "leader-order-unknown-deterministic-yield";
        };
    return new SameDirectionCorridorPriority(true, leaderOrder, selfPriority, reason);
  }

  private SameDirectionLeaderOrder sameDirectionCorridorPriorityOrder(
      String trainName, String leaderTrain, OccupancyRequestContext context, RailGraph graph) {
    if (TrainNameNormalizer.sameLogicalTrain(trainName, leaderTrain)) {
      return SameDirectionLeaderOrder.UNKNOWN;
    }
    Optional<RouteProgressRegistry.RouteProgressEntry> trainEntry =
        progressRegistry == null ? Optional.empty() : progressRegistry.get(trainName);
    Optional<RouteProgressRegistry.RouteProgressEntry> leaderEntry =
        progressRegistry == null ? Optional.empty() : progressRegistry.get(leaderTrain);
    if (trainEntry.isPresent()
        && leaderEntry.isPresent()
        && trainEntry.get().routeId().equals(leaderEntry.get().routeId())) {
      int trainIndex = trainEntry.get().currentIndex();
      int leaderIndex = leaderEntry.get().currentIndex();
      if (leaderIndex > trainIndex) {
        return SameDirectionLeaderOrder.LEADER_AHEAD;
      }
      if (leaderIndex < trainIndex) {
        return SameDirectionLeaderOrder.TRAIN_AHEAD;
      }
    }
    SameDirectionLeaderOrder visiblePathOrder =
        sameDirectionLeaderOrderByVisiblePath(trainEntry, leaderEntry, context);
    if (visiblePathOrder != SameDirectionLeaderOrder.UNKNOWN) {
      return visiblePathOrder;
    }
    return sameDirectionLeaderOrderBySharedAnchor(
        trainName, leaderTrain, trainEntry, leaderEntry, context, graph);
  }

  private Optional<SmartAdmissionResult> evaluateAlreadyInsideSameDirectionLeaderBlock(
      String trainName,
      RailGraph graph,
      RailGraphConflictSupport support,
      OccupancyRequestContext context,
      OccupancyResource conflict,
      CorridorDirection direction,
      EntryLookaheadEvaluator.Result lookahead,
      SingleZoneAdmissionState admission,
      SmartAdmissionContext safeContext) {
    if (admission == null || !admission.sameDirectionLeader()) {
      return Optional.empty();
    }
    String leaderTrain = admission.leaderTrain();
    if (sameDirectionLeaderAlreadyHoldingForTrain(trainName, leaderTrain)) {
      traceAlreadyInsideLeaderGuardSkipped(
          trainName, leaderTrain, conflict, "leader-already-held-by-this-train");
      return Optional.empty();
    }
    SameDirectionLeaderOrder leaderOrder =
        sameDirectionLeaderOrder(trainName, leaderTrain, context, graph);
    if (leaderOrder == SameDirectionLeaderOrder.TRAIN_AHEAD) {
      traceAlreadyInsideLeaderGuardSkipped(
          trainName, leaderTrain, conflict, "candidate-leader-not-ahead");
      return Optional.empty();
    }
    if (leaderOrder == SameDirectionLeaderOrder.UNKNOWN
        && !deterministicSameDirectionYield(trainName, leaderTrain)) {
      traceAlreadyInsideLeaderGuardSkipped(
          trainName, leaderTrain, conflict, "leader-order-unknown-deterministic-pass");
      return Optional.empty();
    }
    CorridorDirection safeDirection = direction == null ? CorridorDirection.UNKNOWN : direction;
    String reason = null;
    if (admission.leaderStalled()) {
      reason = admission.blockerReason();
    } else if (!admission.leaderProgressFresh()) {
      reason = "same-direction-leader-progress-stale";
    } else {
      SameDirectionLeaderDrainPrediction leaderDrainPrediction =
          predictSameDirectionLeaderDrain(
              trainName, graph, support, conflict, safeDirection, admission, context);
      traceSameDirectionLeaderDrainPrediction(
          trainName, context, conflict, safeDirection, admission, leaderDrainPrediction);
      if (sameDirectionLeaderTerminalOrDwellShouldBlock(admission, leaderDrainPrediction)) {
        reason = "same-direction-leader-terminal-or-dwell";
      } else if (leaderDrainPrediction.applicable() && !leaderDrainPrediction.drainProven()) {
        reason = leaderDrainPrediction.reason();
      }
    }
    if (reason == null || reason.isBlank()) {
      return Optional.empty();
    }
    SmartAdmissionResult result =
        smartAdmissionBlocked(
            trainName,
            context,
            conflict,
            safeDirection,
            lookahead,
            admission,
            safeContext,
            SmartAdmissionDecision.REJECT_LEADER_OCCUPYING,
            reason,
            true);
    traceSingleZoneAdmissionDecision(
        trainName, context, conflict, safeDirection, lookahead, admission, false, result.reason());
    debugLogger.accept(
        "SMART_ALREADY_INSIDE_REGION_BLOCKED_BY_SAME_DIRECTION_LEADER train="
            + trainName
            + " leader="
            + leaderTrain
            + " regionId="
            + (conflict == null ? "-" : conflict.key())
            + " reason="
            + result.reason()
            + " leaderOrder="
            + leaderOrder
            + " alreadyInside=true");
    rememberSameDirectionLeaderHold(trainName, leaderTrain);
    return Optional.of(result);
  }

  /**
   * 判断 same-direction guard 里的候选 leader 是否确实位于本车前方。
   *
   * <p>优先使用同一 route 的 {@code currentIndex} 建立严格顺序；缺少同 route 证据时，再用本车本轮可见下游锚点比较两车剩余图距离，覆盖跨 route
   * 但共用同一物理走廊的跟驰关系。只有 {@link SameDirectionLeaderOrder#LEADER_AHEAD} 才能直接触发 already-inside hold。
   */
  private SameDirectionLeaderOrder sameDirectionLeaderOrder(
      String trainName, String leaderTrain, OccupancyRequestContext context, RailGraph graph) {
    if (TrainNameNormalizer.sameLogicalTrain(trainName, leaderTrain)) {
      return SameDirectionLeaderOrder.UNKNOWN;
    }
    Optional<RouteProgressRegistry.RouteProgressEntry> trainEntry =
        progressRegistry == null ? Optional.empty() : progressRegistry.get(trainName);
    Optional<RouteProgressRegistry.RouteProgressEntry> leaderEntry =
        progressRegistry == null ? Optional.empty() : progressRegistry.get(leaderTrain);
    if (trainEntry.isPresent()
        && leaderEntry.isPresent()
        && trainEntry.get().routeId().equals(leaderEntry.get().routeId())) {
      int trainIndex = trainEntry.get().currentIndex();
      int leaderIndex = leaderEntry.get().currentIndex();
      if (leaderIndex > trainIndex) {
        return SameDirectionLeaderOrder.LEADER_AHEAD;
      }
      if (leaderIndex < trainIndex) {
        return SameDirectionLeaderOrder.TRAIN_AHEAD;
      }
    }
    SameDirectionLeaderOrder visiblePathOrder =
        sameDirectionLeaderOrderByVisiblePath(trainEntry, leaderEntry, context);
    if (visiblePathOrder != SameDirectionLeaderOrder.UNKNOWN) {
      return visiblePathOrder;
    }
    SameDirectionLeaderOrder anchorDistanceOrder =
        sameDirectionLeaderOrderBySharedAnchor(
            trainName, leaderTrain, trainEntry, leaderEntry, context, graph);
    if (anchorDistanceOrder != SameDirectionLeaderOrder.UNKNOWN) {
      return anchorDistanceOrder;
    }
    Optional<NodeId> leaderNode =
        leaderEntry.flatMap(RouteProgressRegistry.RouteProgressEntry::lastPassedGraphNode);
    if (leaderNode.isPresent() && context != null && context.pathNodes() != null) {
      int index = context.pathNodes().indexOf(leaderNode.get());
      if (index > 0) {
        return SameDirectionLeaderOrder.LEADER_AHEAD;
      }
      if (index == 0) {
        return SameDirectionLeaderOrder.TRAIN_AHEAD;
      }
    }
    return SameDirectionLeaderOrder.UNKNOWN;
  }

  /**
   * 用本车可见路径中的节点顺序判断同向 leader 前后。
   *
   * <p>该路径来自本轮占用请求，天然带有从当前车向下游走的方向；只有两车最近经过节点都落在这条路径上时才给出结论。
   */
  private SameDirectionLeaderOrder sameDirectionLeaderOrderByVisiblePath(
      Optional<RouteProgressRegistry.RouteProgressEntry> trainEntry,
      Optional<RouteProgressRegistry.RouteProgressEntry> leaderEntry,
      OccupancyRequestContext context) {
    if (context == null || context.pathNodes() == null || context.pathNodes().isEmpty()) {
      return SameDirectionLeaderOrder.UNKNOWN;
    }
    Optional<NodeId> trainNode = sameDirectionOrderTrainNode(trainEntry, context, true);
    Optional<NodeId> leaderNode = sameDirectionOrderTrainNode(leaderEntry, context, false);
    if (trainNode.isEmpty() || leaderNode.isEmpty()) {
      return SameDirectionLeaderOrder.UNKNOWN;
    }
    int trainIndex = context.pathNodes().indexOf(trainNode.get());
    int leaderIndex = context.pathNodes().indexOf(leaderNode.get());
    if (trainIndex < 0 || leaderIndex < 0 || trainIndex == leaderIndex) {
      return SameDirectionLeaderOrder.UNKNOWN;
    }
    return leaderIndex > trainIndex
        ? SameDirectionLeaderOrder.LEADER_AHEAD
        : SameDirectionLeaderOrder.TRAIN_AHEAD;
  }

  /**
   * 用两车到同一个下游可见锚点的剩余图距离判断前后。
   *
   * <p>跨 route 跟驰时，route index 不可比较；leader
   * 的节点也可能已经在本车当前路径窗口后方，导致可见路径索引无法证明“本车在前”。此处只在两车都能到达本车本轮展开路径末端时生效，距离锚点更近的一方视为物理队列前方。
   */
  private SameDirectionLeaderOrder sameDirectionLeaderOrderBySharedAnchor(
      String trainName,
      String leaderTrain,
      Optional<RouteProgressRegistry.RouteProgressEntry> trainEntry,
      Optional<RouteProgressRegistry.RouteProgressEntry> leaderEntry,
      OccupancyRequestContext context,
      RailGraph graph) {
    if (graph == null || context == null) {
      return SameDirectionLeaderOrder.UNKNOWN;
    }
    Optional<NodeId> anchor = sameDirectionOrderAnchor(context);
    Optional<NodeId> trainNode = sameDirectionOrderTrainNode(trainEntry, context, true);
    Optional<NodeId> leaderNode = sameDirectionOrderTrainNode(leaderEntry, context, false);
    if (anchor.isEmpty()
        || trainNode.isEmpty()
        || leaderNode.isEmpty()
        || trainNode.get().equals(leaderNode.get())) {
      return SameDirectionLeaderOrder.UNKNOWN;
    }
    OptionalLong trainDistance = resolveShortestDistance(graph, trainNode.get(), anchor.get());
    OptionalLong leaderDistance = resolveShortestDistance(graph, leaderNode.get(), anchor.get());
    if (trainDistance.isEmpty()
        || leaderDistance.isEmpty()
        || trainDistance.getAsLong() == leaderDistance.getAsLong()) {
      return SameDirectionLeaderOrder.UNKNOWN;
    }
    SameDirectionLeaderOrder order =
        leaderDistance.getAsLong() < trainDistance.getAsLong()
            ? SameDirectionLeaderOrder.LEADER_AHEAD
            : SameDirectionLeaderOrder.TRAIN_AHEAD;
    debugLogger.accept(
        "SMART_SAME_DIRECTION_LEADER_DISTANCE_ORDER train="
            + trainName
            + " leader="
            + leaderTrain
            + " trainNode="
            + trainNode.get().value()
            + " leaderNode="
            + leaderNode.get().value()
            + " anchor="
            + anchor.get().value()
            + " trainDistance="
            + trainDistance.getAsLong()
            + " leaderDistance="
            + leaderDistance.getAsLong()
            + " result="
            + order);
    return order;
  }

  private Optional<NodeId> sameDirectionOrderAnchor(OccupancyRequestContext context) {
    if (context == null) {
      return Optional.empty();
    }
    if (context.pathNodes() != null && !context.pathNodes().isEmpty()) {
      return Optional.ofNullable(context.pathNodes().get(context.pathNodes().size() - 1));
    }
    return context.request().directedContext().flatMap(DirectedTraversalContext::effectiveToNode);
  }

  private Optional<NodeId> sameDirectionOrderTrainNode(
      Optional<RouteProgressRegistry.RouteProgressEntry> entry,
      OccupancyRequestContext context,
      boolean useRequestFallback) {
    Optional<NodeId> progressNode =
        entry == null
            ? Optional.empty()
            : entry.flatMap(RouteProgressRegistry.RouteProgressEntry::lastPassedGraphNode);
    if (progressNode.isPresent() || !useRequestFallback || context == null) {
      return progressNode;
    }
    return context
        .request()
        .directedContext()
        .flatMap(DirectedTraversalContext::lastPassedGraphNode)
        .or(
            () ->
                context.request().directedContext().flatMap(DirectedTraversalContext::currentNode));
  }

  /**
   * 在缺少可证明拓扑顺序时给同向 already-inside 守卫提供稳定让行侧。
   *
   * <p>该结果只用于“不可定序”的兜底，不能替代 {@link #sameDirectionLeaderOrder(String, String,
   * OccupancyRequestContext, RailGraph)} 的严格领先证明。稳定排序保证两辆车最多只有一辆被本守卫 hold，避免 A 等 B、B 又等 A 的对称死锁。
   */
  private boolean deterministicSameDirectionYield(String trainName, String leaderTrain) {
    String trainKey = normalizeTrainKey(trainName);
    String leaderKey = normalizeTrainKey(leaderTrain);
    if (trainKey.isEmpty() || leaderKey.isEmpty() || trainKey.equals(leaderKey)) {
      return false;
    }
    return trainKey.compareTo(leaderKey) > 0;
  }

  /**
   * 判断候选 leader 是否已经因为本车被 same-direction guard hold。
   *
   * <p>记录只保留很短时间，用于打破同一 tick 或相邻 tick 形成的互等关系；过期后让实时占用和进度状态重新裁决。
   */
  private boolean sameDirectionLeaderAlreadyHoldingForTrain(String trainName, String leaderTrain) {
    String leaderKey = normalizeTrainKey(leaderTrain);
    if (leaderKey.isEmpty()) {
      return false;
    }
    SameDirectionLeaderHold hold = sameDirectionLeaderHolds.get(leaderKey);
    if (hold == null) {
      return false;
    }
    Instant now = Instant.now();
    if (hold.capturedAt().plus(SAME_DIRECTION_LEADER_HOLD_TTL).isBefore(now)) {
      sameDirectionLeaderHolds.remove(leaderKey, hold);
      return false;
    }
    return TrainNameNormalizer.sameLogicalTrain(hold.leaderTrain(), trainName);
  }

  private void rememberSameDirectionLeaderHold(String trainName, String leaderTrain) {
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return;
    }
    sameDirectionLeaderHolds.put(key, new SameDirectionLeaderHold(leaderTrain, Instant.now()));
  }

  private void forgetSameDirectionLeaderHold(String trainName) {
    String key = normalizeTrainKey(trainName);
    if (!key.isEmpty()) {
      sameDirectionLeaderHolds.remove(key);
    }
  }

  private void traceAlreadyInsideLeaderGuardSkipped(
      String trainName, String leaderTrain, OccupancyResource conflict, String reason) {
    debugLogger.accept(
        "SMART_ALREADY_INSIDE_LEADER_GUARD_SKIPPED train="
            + trainName
            + " leader="
            + (leaderTrain == null || leaderTrain.isBlank() ? "-" : leaderTrain)
            + " regionId="
            + (conflict == null ? "-" : conflict.key())
            + " reason="
            + (reason == null || reason.isBlank() ? "-" : reason));
  }

  private void traceSameDirectionCorridorPriority(
      String trainName,
      OccupancyResource conflict,
      SingleZoneAdmissionState admission,
      SameDirectionCorridorPriority priority,
      String source) {
    if (priority == null || !priority.applicable()) {
      return;
    }
    debugLogger.accept(
        "SMART_SAME_DIRECTION_CORRIDOR_PRIORITY train="
            + trainName
            + " leader="
            + (admission == null
                    || admission.leaderTrain() == null
                    || admission.leaderTrain().isBlank()
                ? "-"
                : admission.leaderTrain())
            + " regionId="
            + (conflict == null ? "-" : conflict.key())
            + " corridorOrder="
            + priority.leaderOrder()
            + " selfIsCorridorPriority="
            + priority.selfIsCorridorPriority()
            + " reason="
            + priority.reason()
            + " source="
            + (source == null || source.isBlank() ? "-" : source));
  }

  private SmartAdmissionResult smartAdmissionAllowed(
      OccupancyRequestContext context,
      OccupancyResource conflict,
      CorridorDirection direction,
      EntryLookaheadEvaluator.Result lookahead,
      SingleZoneAdmissionState admission,
      SmartAdmissionContext admissionContext,
      SmartAdmissionDecision decision,
      String reason,
      boolean alreadyInside) {
    return new SmartAdmissionResult(
        true,
        true,
        decision,
        DispatchEffectClass.DIAGNOSTIC_ONLY,
        reason,
        conflict,
        direction,
        alreadyInside,
        false,
        admissionContext,
        admission,
        lookahead);
  }

  private SmartAdmissionResult smartAdmissionBlocked(
      String trainName,
      OccupancyRequestContext context,
      OccupancyResource conflict,
      CorridorDirection direction,
      EntryLookaheadEvaluator.Result lookahead,
      SingleZoneAdmissionState admission,
      SmartAdmissionContext admissionContext,
      SmartAdmissionDecision decision,
      String reason,
      boolean alreadyInside) {
    traceSmartDownstreamCongestion(trainName, context, conflict, admission, decision, reason);
    return new SmartAdmissionResult(
        true,
        false,
        smartHoldDecisionForContext(admissionContext, decision),
        DispatchEffectClass.SIGNAL_CONSTRAINT,
        reason,
        conflict,
        direction,
        alreadyInside,
        true,
        admissionContext,
        admission,
        lookahead);
  }

  /**
   * 处理只有冲突队列、没有真实占用 claim 的 Smart admission 竞争。
   *
   * <p>队列条目表示“谁先进入仲裁”，不等价于列车已经物理进入 single region。若 Smart admission 把 queue-only 对手直接当作 opposite
   * occupant，就会在 JBS 这类双向抢占中把双方都挡在 {@code canEnter()} 之前，Gate Queue 永远无法选出一个赢家。
   *
   * <p>因此这里仅在没有外部 claim 时调用只读 {@link OccupancyPreviewSupport#canEnterPreview(OccupancyRequest)}：
   * 预览认为本车会成为队头时，允许进入后续正式占用判定；预览认为不是队头时，保持 local-only hold。真实 claim、未知方向和 NODE/EDGE blocker
   * 仍由占用层与后续 hard blocker 检查 fail closed。
   */
  private Optional<SmartAdmissionResult> evaluateQueueOnlySmartAdmission(
      String trainName,
      OccupancyRequestContext context,
      OccupancyResource conflict,
      CorridorDirection direction,
      EntryLookaheadEvaluator.Result lookahead,
      SingleZoneAdmissionState admission,
      SmartAdmissionContext admissionContext) {
    if (context == null
        || context.request() == null
        || admission == null
        || !admission.queueOnlyPresence()
        || !(occupancyManager instanceof OccupancyPreviewSupport previewSupport)) {
      return Optional.empty();
    }
    OccupancyDecision preview = previewSupport.canEnterPreview(context.request());
    if (preview.allowed()) {
      traceQueueOnlyAdmission(
          trainName, conflict, context.request(), true, "queue-head-arbitration");
      return Optional.of(
          smartAdmissionAllowed(
              context,
              conflict,
              direction,
              lookahead,
              admission,
              admissionContext,
              SmartAdmissionDecision.ALLOW_ENTER,
              "queue-head-arbitration",
              false));
    }
    String reason = queueAdmissionBlockReason(preview);
    traceQueueOnlyAdmission(trainName, conflict, context.request(), false, reason);
    return Optional.of(
        smartAdmissionBlocked(
            trainName,
            context,
            conflict,
            direction,
            lookahead,
            admission,
            admissionContext,
            SmartAdmissionDecision.REJECT_OPPOSITE_DIRECTION,
            reason,
            false));
  }

  private void traceQueueOnlyAdmission(
      String trainName,
      OccupancyResource conflict,
      OccupancyRequest request,
      boolean allowed,
      String reason) {
    debugLogger.accept(
        "SMART_QUEUE_ARBITRATION_ADMISSION train="
            + (trainName == null || trainName.isBlank() ? "-" : trainName)
            + " regionId="
            + (conflict == null ? "-" : conflict.key())
            + " allowed="
            + allowed
            + " priority="
            + (request == null ? 0 : request.priority())
            + " reason="
            + (reason == null || reason.isBlank() ? "-" : reason)
            + " wouldMutate=false didMutate=false");
  }

  private static String queueAdmissionBlockReason(OccupancyDecision preview) {
    if (preview == null || preview.reason() == null || "none".equals(preview.reason())) {
      return "queue-arbitration-wait";
    }
    return preview.reason();
  }

  private SmartAdmissionDecision smartHoldDecisionForContext(
      SmartAdmissionContext context, SmartAdmissionDecision fallback) {
    if (context != null && context.atDepot()) {
      return SmartAdmissionDecision.HOLD_AT_DEPOT;
    }
    if (context != null && context.atStation()) {
      return SmartAdmissionDecision.HOLD_AT_STATION;
    }
    if (fallback == SmartAdmissionDecision.REJECT_DOWNSTREAM_BLOCKED
        || fallback == SmartAdmissionDecision.REJECT_LEADER_STALLED
        || fallback == SmartAdmissionDecision.REJECT_LEADER_OCCUPYING
        || fallback == SmartAdmissionDecision.REJECT_LEADER_EXIT_NOT_VISIBLE
        || fallback == SmartAdmissionDecision.REJECT_REGION_OCCUPIED_UNSAFE
        || fallback == SmartAdmissionDecision.REJECT_OPPOSITE_DIRECTION
        || fallback == SmartAdmissionDecision.REJECT_UNKNOWN_DIRECTION
        || fallback == SmartAdmissionDecision.REJECT_THROAT_SECTION_BUSY) {
      return fallback;
    }
    return SmartAdmissionDecision.HOLD_AT_ENTRY;
  }

  private boolean smartAdmissionShouldBlock(String trainName, SmartAdmissionResult result) {
    if (result == null || !result.applies() || result.allowed()) {
      return false;
    }
    return smartTrafficControlActionAllowed(
        trainName, result.context().source(), result.decision().name(), result.effectClass());
  }

  /**
   * Depot pre-spawn Smart admission gate.
   *
   * <p>该方法只决定“是否允许继续进入已有 depot spawn 流程”。OBSERVE_ONLY/OFF 永远不落地副作用；ENFORCE 下只在 SIGNAL_CONSTRAINT
   * gate 允许时返回 false，从而让调用方保持 local-only hold：不 spawn、不写 destination、不释放既有占用、不清 token。
   */
  public boolean smartDepotAdmissionAllowsSpawn(
      String trainName, RailGraph graph, OccupancyRequestContext context) {
    AuthorityEnd authorityEnd =
        resolveAuthorityEnd(graph, context == null ? List.of() : context.pathNodes(), 0, context);
    SmartAdmissionResult result =
        evaluateSmartSingleCorridorAdmission(
            trainName,
            graph,
            context,
            authorityEnd,
            true,
            SmartAdmissionContext.depot("depot-spawn-smart-admission"));
    return !smartAdmissionShouldBlock(trainName, result);
  }

  private boolean singleRegionOppositeOrUnknownExternalBarrier(
      String trainName,
      Collection<OccupancyResource> resources,
      CorridorDirection requestedDirection) {
    if (resources == null || resources.isEmpty() || occupancyManager == null) {
      return false;
    }
    for (OccupancyClaim claim : occupancyManager.snapshotClaims()) {
      if (claim == null
          || claim.resource() == null
          || claim.role() == ClaimRole.UNLOCK_RESERVATION
          || TrainNameNormalizer.sameLogicalTrain(trainName, claim.trainName())
          || !resources.contains(claim.resource())
          || !isSingleConflict(claim)) {
        continue;
      }
      CorridorDirection held = claim.corridorDirection().orElse(CorridorDirection.UNKNOWN);
      if (requestedDirection == null
          || requestedDirection == CorridorDirection.UNKNOWN
          || held == CorridorDirection.UNKNOWN
          || held != requestedDirection) {
        return true;
      }
    }
    return false;
  }

  private boolean smartRecoverySingleRegionHardBarrierPresent(SmartRecoveryInput input) {
    if (input == null) {
      return false;
    }
    String reason = input.primaryReason().toLowerCase(Locale.ROOT);
    return input.oppositeSingleConflictPresent()
        || reason.contains("opposite-single-conflict")
        || reason.contains("single-conflict-direction-unknown")
        || reason.contains("unknown-single")
        || reason.contains("unknown-direction");
  }

  /**
   * 判断 stuck recovery 是否只是“已在单线内的列车继续向外排空”。
   *
   * <p>这个判定刻意比普通准入更窄：它要求本车已经持有目标 single conflict，持有方向明确，下一跳是真实向前移动，并且下一段 edge/node 没有外部占用。这样可以让
   * drain-out 进入最终信号复判，同时避免把“反向进单线”的 hard barrier 误放行。
   */
  private SmartDrainOutProof smartRecoveryDrainOutProof(SmartRecoveryInput input) {
    if (input == null) {
      return SmartDrainOutProof.denied("missing-input");
    }
    if (!input.insideSingleRegion()) {
      return SmartDrainOutProof.denied("not-inside-single-region");
    }
    if (input.downstreamBlocked()) {
      return SmartDrainOutProof.denied("downstream-blocked");
    }
    if (input.currentNode() == null
        || input.nextNode() == null
        || input.currentNode().equals(input.nextNode())) {
      return SmartDrainOutProof.denied("missing-forward-exit");
    }
    if (occupancyManager == null) {
      return SmartDrainOutProof.denied("occupancy-snapshot-unavailable");
    }
    Collection<OccupancyClaim> claims = occupancyManager.snapshotClaims();
    if (claims == null || claims.isEmpty()) {
      return SmartDrainOutProof.denied("occupancy-snapshot-empty");
    }
    OccupancyResource exitEdge =
        OccupancyResource.forEdge(EdgeId.undirected(input.currentNode(), input.nextNode()));
    OccupancyResource exitNode = OccupancyResource.forNode(input.nextNode());
    String lastDenial = "self-single-claim-missing";
    for (OccupancyClaim selfClaim : claims) {
      if (!selfSingleDrainOutClaim(input.train(), selfClaim)) {
        continue;
      }
      CorridorDirection selfDirection =
          selfClaim.corridorDirection().orElse(CorridorDirection.UNKNOWN);
      if (selfDirection == CorridorDirection.UNKNOWN) {
        lastDenial = "self-direction-unknown";
        continue;
      }
      if (!smartRecoveryHardBlockersCoveredByDrainOut(input, selfClaim.resource())) {
        lastDenial = "hard-blocker-not-covered-by-drain-out";
        continue;
      }
      Optional<OccupancyClaim> externalBarrier =
          claims.stream()
              .filter(
                  claim ->
                      externalSingleDrainBarrierClaim(
                          input.train(), selfClaim.resource(), selfDirection, claim))
              .findFirst();
      if (externalBarrier.isEmpty()) {
        lastDenial = "external-opposite-or-unknown-claim-missing";
        continue;
      }
      Optional<OccupancyClaim> exitBlocker =
          claims.stream()
              .filter(claim -> externalDrainExitBlocker(input.train(), claim, exitEdge, exitNode))
              .findFirst();
      if (exitBlocker.isPresent()) {
        return SmartDrainOutProof.denied("exit-resource-held-by-external-train");
      }
      return SmartDrainOutProof.allowed(
          selfClaim.resource(), selfDirection, externalBarrier.get(), exitEdge, exitNode);
    }
    return SmartDrainOutProof.denied(lastDenial);
  }

  private static boolean selfSingleDrainOutClaim(String trainName, OccupancyClaim claim) {
    return claim != null
        && claim.resource() != null
        && claim.role() != ClaimRole.UNLOCK_RESERVATION
        && TrainNameNormalizer.sameLogicalTrain(trainName, claim.trainName())
        && isSingleConflict(claim);
  }

  private static boolean externalSingleDrainBarrierClaim(
      String trainName,
      OccupancyResource section,
      CorridorDirection selfDirection,
      OccupancyClaim claim) {
    if (claim == null
        || claim.resource() == null
        || claim.role() == ClaimRole.UNLOCK_RESERVATION
        || TrainNameNormalizer.sameLogicalTrain(trainName, claim.trainName())
        || !claim.resource().equals(section)
        || !isSingleConflict(claim)) {
      return false;
    }
    CorridorDirection heldDirection = claim.corridorDirection().orElse(CorridorDirection.UNKNOWN);
    return selfDirection == CorridorDirection.UNKNOWN
        || heldDirection == CorridorDirection.UNKNOWN
        || heldDirection != selfDirection;
  }

  private static boolean externalDrainExitBlocker(
      String trainName,
      OccupancyClaim claim,
      OccupancyResource exitEdge,
      OccupancyResource exitNode) {
    return claim != null
        && claim.resource() != null
        && claim.role() != ClaimRole.UNLOCK_RESERVATION
        && !TrainNameNormalizer.sameLogicalTrain(trainName, claim.trainName())
        && (claim.resource().equals(exitEdge) || claim.resource().equals(exitNode));
  }

  private static boolean smartRecoveryHardBlockersCoveredByDrainOut(
      SmartRecoveryInput input, OccupancyResource section) {
    if (input == null || section == null) {
      return false;
    }
    if (input.blockerCount() > input.hardBlockers().size()) {
      return false;
    }
    return input.hardBlockers().stream()
        .filter(Objects::nonNull)
        .allMatch(blocker -> smartRecoveryBlockerMatchesResource(blocker, section));
  }

  private static boolean smartRecoveryBlockerMatchesResource(
      String blocker, OccupancyResource resource) {
    if (blocker == null || blocker.isBlank() || resource == null) {
      return false;
    }
    String normalized = blocker.trim();
    String key = resource.key();
    String rendered = resource.toString();
    return normalized.equals(key)
        || normalized.equals(rendered)
        || normalized.equals(resource.kind().name() + ":" + key)
        || normalized.endsWith(":" + key);
  }

  private void traceSmartRecoveryDrainOutAllowed(
      String decision, DispatchAction action, SmartRecoveryInput input, SmartDrainOutProof proof) {
    debugLogger.accept(
        decision
            + " train="
            + input.train()
            + " action="
            + action
            + " section="
            + proof.section()
            + " trainDirection="
            + proof.trainDirection()
            + " exitEdge="
            + proof.exitEdge()
            + " exitNode="
            + proof.exitNode()
            + " externalTrain="
            + proof.externalTrain()
            + " externalDirection="
            + proof.externalDirection()
            + " externalResource="
            + proof.externalResource()
            + " reason="
            + proof.reason()
            + " occupancyDecreasing=true");
  }

  private SmartRecoveryActionResult smartRecoveryBlockedBySingleRegionHardBarrier(
      SmartRecoveryInput input, String decision, DispatchEffectClass effectClass) {
    String safeDecision =
        decision == null || decision.isBlank() ? "SMART_RECOVERY_BLOCKED" : decision;
    debugLogger.accept(
        safeDecision
            + " train="
            + (input == null ? "-" : input.train())
            + " reason="
            + SimpleOccupancyManager.OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER
            + " movementInhibitorCleared=false"
            + " signalRefreshed=false"
            + " occupancyMutated=false"
            + " tokenIssued=false"
            + " destinationMutated=false"
            + " didMutate=false");
    return new SmartRecoveryActionResult(
        false,
        false,
        safeDecision,
        SimpleOccupancyManager.OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER,
        effectClass == null ? DispatchEffectClass.DIAGNOSTIC_ONLY : effectClass);
  }

  private void traceSignalAdvisorySuppressed(
      DispatchAction action,
      SmartRecoveryInput input,
      String reason,
      boolean hardBarrierPresent,
      boolean snapshotStale,
      boolean movementInhibitedBefore) {
    String trainName = input == null ? "-" : input.train();
    debugLogger.accept(
        "event=SIGNAL_ADVISORY_SUPPRESSED action="
            + (action == null ? DispatchAction.NONE : action)
            + " train="
            + (trainName == null || trainName.isBlank() ? "-" : trainName)
            + " reason="
            + (reason == null || reason.isBlank() ? "SIGNAL_ADVISORY_SUPPRESSED" : reason)
            + " hardBarrierPresent="
            + hardBarrierPresent
            + " snapshotStale="
            + snapshotStale
            + " movementInhibitedBefore="
            + movementInhibitedBefore
            + " movementInhibitedAfter="
            + isMovementInhibited(trainName)
            + " wouldMutate=true didMutate=false");
  }

  private void traceSingleRegionHardBarrier(
      String trainName,
      DispatchAction action,
      String source,
      Collection<OccupancyResource> resources) {
    debugLogger.accept(
        "SMART_SINGLE_REGION_HARD_BARRIER train="
            + (trainName == null || trainName.isBlank() ? "-" : trainName)
            + " action="
            + (action == null ? DispatchAction.NONE : action)
            + " source="
            + (source == null || source.isBlank() ? "-" : source)
            + " resources="
            + (resources == null ? List.of() : List.copyOf(resources))
            + " reason="
            + SimpleOccupancyManager.OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER
            + " wouldMutate=false didMutate=false");
  }

  private boolean smartTrafficControlActionAllowed(
      String trainName, String source, String action, DispatchEffectClass effectClass) {
    SmartDispatcherMode mode = smartDispatcherMode();
    traceSmartTrafficControlGate(trainName, mode, source, effectClass);
    if (mode == SmartDispatcherMode.OFF) {
      debugLogger.accept(
          "SMART_DISPATCH_DISABLED train=" + trainName + " source=" + source + " mode=" + mode);
      traceSmartDispatcherActionSuppressed(
          trainName, mode, action, effectClass, "smart-dispatcher-off");
      return false;
    }
    SmartDispatcherModeGate.EffectPermissions permissions =
        SmartDispatcherModeGate.permissions(mode, effectClass);
    boolean allowed =
        switch (effectClass == null ? DispatchEffectClass.DIAGNOSTIC_ONLY : effectClass) {
          case SIGNAL_ADVISORY, SIGNAL_CONSTRAINT -> permissions.canChangeAspect()
              || permissions.canChangeTargetSpeed();
          case OCCUPANCY_MUTATION -> permissions.canMutateOccupancy();
          case DESTROY_ACTION -> permissions.canDestroy();
          default -> false;
        };
    if (allowed) {
      debugLogger.accept(
          "SMART_ACTION_ALLOWED_BY_EFFECT_GATE train="
              + trainName
              + " mode="
              + mode
              + " action="
              + action
              + " effectClass="
              + effectClass
              + " source="
              + source);
      return true;
    }
    traceSmartDispatcherActionSuppressed(
        trainName,
        mode,
        action,
        effectClass,
        mode == SmartDispatcherMode.OBSERVE_ONLY
            ? "observe-only-no-side-effects"
            : "effect-class-not-enabled");
    return false;
  }

  private boolean smartDispatcherRegisteredActionAllowed(
      String trainName, String source, DispatchAction action) {
    DispatchAction safeAction = action == null ? DispatchAction.NONE : action;
    if (!smartDispatcherActionRegistered(safeAction)) {
      debugLogger.accept(
          "SMART_DISPATCHER_ACTION_REJECTED train="
              + trainName
              + " mode="
              + smartDispatcherMode()
              + " action="
              + safeAction
              + " effectClass="
              + safeAction.effectClass()
              + " wouldMutate=false didMutate=false reason=action-not-registered-or-forbidden");
      return false;
    }
    return smartTrafficControlActionAllowed(
        trainName, source, safeAction.name(), safeAction.effectClass());
  }

  private static boolean smartDispatcherActionRegistered(DispatchAction action) {
    return action != null && action.executableDispatcherAction();
  }

  private boolean smartDispatcherActionEffectPermitted(DispatchAction action) {
    if (!smartDispatcherActionRegistered(action)) {
      return false;
    }
    SmartDispatcherModeGate.EffectPermissions permissions =
        SmartDispatcherModeGate.permissions(smartDispatcherMode(), action.effectClass());
    return switch (action.effectClass()) {
      case SIGNAL_ADVISORY, SIGNAL_CONSTRAINT -> permissions.canChangeAspect()
          || permissions.canChangeTargetSpeed();
      case OCCUPANCY_MUTATION -> permissions.canMutateOccupancy();
      case DESTROY_ACTION -> permissions.canDestroy();
      default -> false;
    };
  }

  private void traceSmartDispatcherActionSuppressed(
      String trainName,
      SmartDispatcherMode mode,
      String action,
      DispatchEffectClass effectClass,
      String reason) {
    debugLogger.accept(
        "SMART_ACTION_SUPPRESSED_BY_MODE train="
            + trainName
            + " mode="
            + mode
            + " action="
            + action
            + " effectClass="
            + effectClass
            + " reason="
            + (reason == null || reason.isBlank() ? "mode-gate" : reason));
    debugLogger.accept(
        "SMART_DISPATCH_ACTION_SUPPRESSED_BY_MODE train="
            + trainName
            + " mode="
            + mode
            + " action="
            + action
            + " effectClass="
            + effectClass
            + " reason="
            + (reason == null || reason.isBlank() ? "mode-gate" : reason));
  }

  private SingleZoneAdmissionState singleZoneAdmissionState(
      OccupancyResource conflict,
      String trainName,
      CorridorDirection direction,
      AuthorityEnd authorityEnd) {
    boolean hasPresence = false;
    boolean hasClaimPresence = false;
    boolean sameDirectionLeader = false;
    boolean oppositeOrUnknown = false;
    boolean oppositeDirectionPresent = false;
    boolean unknownDirectionPresent = false;
    boolean leaderStalled = false;
    boolean leaderProgressFresh = false;
    boolean leaderWillTerminalOrDwell = false;
    String leaderTrain = "-";
    CorridorDirection leaderDirection = CorridorDirection.UNKNOWN;
    String blockerReason = "-";
    List<String> occupantTrains = new ArrayList<>();
    List<String> occupantDirections = new ArrayList<>();
    if (conflict != null && occupancyManager != null) {
      for (OccupancyClaim claim : occupancyManager.snapshotClaims()) {
        if (claim == null
            || !conflict.equals(claim.resource())
            || TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
          continue;
        }
        hasPresence = true;
        hasClaimPresence = true;
        leaderTrain = claim.trainName();
        occupantTrains.add(claim.trainName());
        CorridorDirection claimDirection =
            claim.corridorDirection().orElse(CorridorDirection.UNKNOWN);
        leaderDirection = claimDirection;
        occupantDirections.add(claimDirection.name());
        if (claimDirection == CorridorDirection.UNKNOWN) {
          oppositeOrUnknown = true;
          unknownDirectionPresent = true;
          blockerReason = "opposite-or-unknown-claim";
        } else if (claimDirection != direction) {
          oppositeOrUnknown = true;
          oppositeDirectionPresent = true;
          blockerReason = "opposite-direction-claim";
        } else {
          sameDirectionLeader = true;
          leaderProgressFresh |= hasFreshLeaderProgress(claim.trainName(), Instant.now());
          leaderWillTerminalOrDwell |= leaderWillTerminalOrDwell(claim.trainName());
          if (isMovementInhibited(claim.trainName())) {
            leaderStalled = true;
            blockerReason = "same-direction-leader-stalled";
          }
        }
      }
      if (occupancyManager instanceof OccupancyQueueSupport queueSupport) {
        for (OccupancyQueueSnapshot snapshot : queueSupport.snapshotQueues()) {
          if (snapshot == null || !conflict.equals(snapshot.resource())) {
            continue;
          }
          for (OccupancyQueueEntry entry : snapshot.entries()) {
            if (entry == null
                || TrainNameNormalizer.sameLogicalTrain(entry.trainName(), trainName)) {
              continue;
            }
            hasPresence = true;
            leaderTrain = entry.trainName();
            occupantTrains.add(entry.trainName());
            CorridorDirection entryDirection =
                entry.direction() == null ? CorridorDirection.UNKNOWN : entry.direction();
            leaderDirection = entryDirection;
            occupantDirections.add(entryDirection.name());
            if (entryDirection == CorridorDirection.UNKNOWN) {
              oppositeOrUnknown = true;
              unknownDirectionPresent = true;
              blockerReason = "opposite-or-unknown-queue";
            } else if (entryDirection != direction) {
              oppositeOrUnknown = true;
              oppositeDirectionPresent = true;
              blockerReason = "opposite-direction-queue";
            } else {
              sameDirectionLeader = true;
              leaderProgressFresh |= hasFreshLeaderProgress(entry.trainName(), Instant.now());
              leaderWillTerminalOrDwell |= leaderWillTerminalOrDwell(entry.trainName());
              if (isMovementInhibited(entry.trainName())) {
                leaderStalled = true;
                blockerReason = "same-direction-queued-leader-stalled";
              }
            }
          }
        }
      }
    }
    boolean safeHoldPoint =
        authorityEnd != null
            && authorityEnd.distanceBlocks().isPresent()
            && authorityEnd.distanceBlocks().getAsLong() >= 0L;
    return new SingleZoneAdmissionState(
        hasPresence,
        hasClaimPresence,
        sameDirectionLeader,
        oppositeOrUnknown,
        oppositeDirectionPresent,
        unknownDirectionPresent,
        leaderStalled,
        !sameDirectionLeader || leaderProgressFresh,
        leaderWillTerminalOrDwell,
        safeHoldPoint,
        leaderTrain,
        leaderDirection,
        blockerReason,
        occupantTrains,
        occupantDirections);
  }

  private SameDirectionLeaderDrainPrediction predictSameDirectionLeaderDrain(
      String followerTrain,
      RailGraph graph,
      RailGraphConflictSupport support,
      OccupancyResource conflict,
      CorridorDirection followerDirection,
      SingleZoneAdmissionState admission,
      OccupancyRequestContext followerContext) {
    if (admission == null || !admission.sameDirectionLeader()) {
      return SameDirectionLeaderDrainPrediction.notApplicable();
    }
    if (graph == null || support == null || conflict == null) {
      return SameDirectionLeaderDrainPrediction.blocked(
          "same-direction-leader-topology-missing",
          false,
          false,
          false,
          false,
          null,
          -1,
          null,
          null);
    }
    String leaderTrain = admission.leaderTrain();
    Optional<RouteProgressRegistry.RouteProgressEntry> leaderEntryOpt =
        progressRegistry == null ? Optional.empty() : progressRegistry.get(leaderTrain);
    if (leaderEntryOpt.isEmpty()) {
      return SameDirectionLeaderDrainPrediction.blocked(
          "same-direction-leader-progress-missing",
          false,
          false,
          false,
          false,
          null,
          -1,
          null,
          null);
    }
    RouteProgressRegistry.RouteProgressEntry leaderEntry = leaderEntryOpt.get();
    Optional<RouteDefinition> leaderRouteOpt = routeDefinitionForProgress(leaderEntry);
    if (leaderRouteOpt.isEmpty()) {
      return SameDirectionLeaderDrainPrediction.blocked(
          "same-direction-leader-route-missing",
          false,
          false,
          false,
          false,
          null,
          leaderEntry.currentIndex(),
          null,
          null);
    }
    RouteDefinition leaderRoute = leaderRouteOpt.get();
    int leaderCurrentIndex = leaderEntry.currentIndex();
    if (leaderCurrentIndex < 0 || leaderCurrentIndex >= leaderRoute.waypoints().size() - 1) {
      return SameDirectionLeaderDrainPrediction.blocked(
          "same-direction-leader-route-terminal",
          false,
          false,
          false,
          false,
          leaderRoute,
          leaderCurrentIndex,
          null,
          null);
    }
    NodeId leaderCurrentNode =
        resolveEffectiveCurrentNodeForSignal(leaderTrain, leaderRoute, leaderCurrentIndex, graph);
    List<NodeId> leaderDirectionContextNodes = resolveEffectiveWaypoints(leaderTrain, leaderRoute);
    List<NodeId> effectiveNodes =
        applyCurrentNodeOverride(
            leaderDirectionContextNodes, leaderCurrentIndex, leaderCurrentNode);
    ConfigManager.RuntimeSettings runtimeSettings = configManager.current().runtimeSettings();
    OccupancyRequestBuilder builder =
        runtimeLookaheadBuilder(graph, runtimeSettings, runtimeSettings.rearGuardEdges());
    Optional<OccupancyRequestContext> contextOpt =
        buildContextWithinDynamicBoundary(
            builder,
            leaderTrain,
            leaderRoute,
            effectiveNodes,
            leaderDirectionContextNodes,
            leaderCurrentIndex,
            Instant.now(),
            0,
            AuthorizationPurpose.RUNTIME_MOVE);
    if (contextOpt.isEmpty()) {
      return SameDirectionLeaderDrainPrediction.blocked(
          "same-direction-leader-plan-missing",
          false,
          false,
          false,
          false,
          leaderRoute,
          leaderCurrentIndex,
          leaderCurrentNode,
          null);
    }
    MovementPlanSnapshot leaderPlan =
        contextOpt.get().request().movementPlanSnapshot().orElse(null);
    if (leaderPlan == null || leaderPlan.directedEdges().isEmpty()) {
      return SameDirectionLeaderDrainPrediction.blocked(
          "same-direction-leader-plan-missing",
          false,
          false,
          false,
          false,
          leaderRoute,
          leaderCurrentIndex,
          leaderCurrentNode,
          null);
    }
    MovementPlanSnapshot followerPlan =
        followerContext == null
            ? null
            : followerContext.request().movementPlanSnapshot().orElse(null);
    if (followerPlan == null || followerPlan.directedEdges().isEmpty()) {
      return SameDirectionLeaderDrainPrediction.blocked(
          "same-direction-leader-follower-plan-missing",
          false,
          false,
          false,
          false,
          leaderRoute,
          leaderCurrentIndex,
          leaderCurrentNode,
          null);
    }
    Optional<CorridorDirection> leaderPlanDirectionOpt =
        leaderPlanDirectionForConflict(
            leaderPlan, support, conflict.key(), admission.leaderDirection());
    CorridorDirection leaderPlanDirection =
        leaderPlanDirectionOpt.orElse(CorridorDirection.UNKNOWN);
    boolean leaderCoversConflict = leaderPlanDirectionOpt.isPresent();
    boolean routeOverlap =
        leaderCoversConflict
            && sameConflictRouteWindowOverlaps(followerPlan, leaderPlan, support, conflict.key());
    boolean directionMatches =
        routeOverlap
            && followerDirection != null
            && followerDirection != CorridorDirection.UNKNOWN
            && leaderPlanDirection == followerDirection;
    if (!leaderCoversConflict) {
      return SameDirectionLeaderDrainPrediction.blocked(
          "same-direction-leader-route-not-overlapping",
          false,
          false,
          false,
          false,
          leaderRoute,
          leaderCurrentIndex,
          leaderCurrentNode,
          null);
    }
    if (!routeOverlap) {
      return SameDirectionLeaderDrainPrediction.blocked(
          "same-direction-leader-route-window-not-overlapping",
          false,
          false,
          false,
          false,
          leaderRoute,
          leaderCurrentIndex,
          leaderCurrentNode,
          null);
    }
    if (!directionMatches) {
      return SameDirectionLeaderDrainPrediction.blocked(
          "same-direction-leader-route-direction-mismatch",
          true,
          false,
          false,
          false,
          leaderRoute,
          leaderCurrentIndex,
          leaderCurrentNode,
          null);
    }
    int fullPlanEdges = leaderPlan.directedEdges().size();
    EntryLookaheadEvaluator.Result leaderLookahead =
        EntryLookaheadEvaluator.evaluate(
            leaderPlan, support, conflict, fullPlanEdges, fullPlanEdges);
    boolean exitVisible = leaderLookahead.exitFeasible();
    boolean boundaryOnly = "exit-is-target-boundary".equals(leaderLookahead.failureReason());
    if (!exitVisible) {
      return SameDirectionLeaderDrainPrediction.blocked(
          "same-direction-leader-exit-not-visible",
          true,
          true,
          false,
          false,
          leaderRoute,
          leaderCurrentIndex,
          leaderCurrentNode,
          leaderLookahead);
    }
    if (boundaryOnly) {
      return SameDirectionLeaderDrainPrediction.blocked(
          "same-direction-leader-boundary-only",
          true,
          true,
          true,
          true,
          leaderRoute,
          leaderCurrentIndex,
          leaderCurrentNode,
          leaderLookahead);
    }
    if (leaderPlanTerminatesAtDeadEndBehaviorNode(leaderPlan, graph)
        && !admission.followerSafeHoldPoint()) {
      return SameDirectionLeaderDrainPrediction.blocked(
          "same-direction-leader-terminal-station-mutex",
          true,
          true,
          true,
          false,
          leaderRoute,
          leaderCurrentIndex,
          leaderCurrentNode,
          leaderLookahead);
    }
    if (leaderPlanTerminatesAtDeadEndBehaviorNode(leaderPlan, graph)) {
      return SameDirectionLeaderDrainPrediction.terminalFollowThrough(
          leaderRoute, leaderCurrentIndex, leaderCurrentNode, leaderLookahead);
    }
    return SameDirectionLeaderDrainPrediction.proven(
        leaderRoute, leaderCurrentIndex, leaderCurrentNode, leaderLookahead);
  }

  /**
   * 判定同向前车的终端/停站粗判是否仍应阻挡本车。
   *
   * <p>CBTC 风格的 prior train prediction 要求把“前车未来可穿出”作为更强证据：若 route window 已证明前车会从同一 conflict
   * 向前排空，则不能再用终端边界或 dwell 的粗略状态把它降级成互斥。dwell 只描述前车当前暂时停车；真正的安全边界仍由前车的有向 Movement
   * Plan、出口可见性和后车安全停车点共同证明。无法证明排空时继续 fail-closed。
   */
  private boolean sameDirectionLeaderTerminalOrDwellShouldBlock(
      SingleZoneAdmissionState admission, SameDirectionLeaderDrainPrediction prediction) {
    if (admission == null || !admission.sameDirectionLeader()) {
      return false;
    }
    if (!admission.leaderWillTerminalOrDwell()) {
      return false;
    }
    return !sameDirectionLeaderDrainProven(prediction);
  }

  private static boolean sameDirectionLeaderDrainProven(
      SameDirectionLeaderDrainPrediction prediction) {
    return prediction != null && prediction.applicable() && prediction.drainProven();
  }

  /**
   * 校验前车未来路径是否确实与本车的 single-region 窗口重合。
   *
   * <p>同一个 conflict key 可以覆盖折返口、长单线、车库切入等多个物理分支。只有两列车未来展开路径在该 key 下共享至少一条 {@link
   * EdgeId}，前车才可被视为本车前方的同线路 leader；否则必须按安全侧阻塞，避免不同分支互相“证明”会 drain。
   */
  private boolean sameConflictRouteWindowOverlaps(
      MovementPlanSnapshot followerPlan,
      MovementPlanSnapshot leaderPlan,
      RailGraphConflictSupport support,
      String conflictKey) {
    if (followerPlan == null
        || leaderPlan == null
        || support == null
        || conflictKey == null
        || conflictKey.isBlank()) {
      return false;
    }
    Set<EdgeId> followerConflictEdges = conflictEdgesInPlan(followerPlan, support, conflictKey);
    if (followerConflictEdges.isEmpty()) {
      return false;
    }
    for (DirectedTraversalContext.DirectedEdge edge : leaderPlan.directedEdges()) {
      if (edge != null
          && followerConflictEdges.contains(edge.edgeId())
          && support.conflictKeyForEdge(edge.edgeId()).filter(conflictKey::equals).isPresent()) {
        return true;
      }
    }
    return false;
  }

  private Set<EdgeId> conflictEdgesInPlan(
      MovementPlanSnapshot plan, RailGraphConflictSupport support, String conflictKey) {
    if (plan == null || support == null || conflictKey == null || conflictKey.isBlank()) {
      return Set.of();
    }
    Set<EdgeId> edges = new LinkedHashSet<>();
    for (DirectedTraversalContext.DirectedEdge edge : plan.directedEdges()) {
      if (edge != null
          && support.conflictKeyForEdge(edge.edgeId()).filter(conflictKey::equals).isPresent()) {
        edges.add(edge.edgeId());
      }
    }
    return edges;
  }

  /**
   * 判断前车 route window 是否终止在死端 Station/Depot。
   *
   * <p>CHT 这类站前折返终端没有站台后的继续出路。即使当前 single conflict key 能在路径中切换到下一个 key，也不能证明前车会同向驶出整组站区；
   * 外部后车必须把该终端站前区域视作完全互斥。
   */
  private boolean leaderPlanTerminatesAtDeadEndBehaviorNode(
      MovementPlanSnapshot leaderPlan, RailGraph graph) {
    if (leaderPlan == null || graph == null || leaderPlan.expandedPathNodes().isEmpty()) {
      return false;
    }
    NodeId terminal = leaderPlan.expandedPathNodes().get(leaderPlan.expandedPathNodes().size() - 1);
    if (!isStationOrDepotBehaviorNode(terminal, graph)) {
      return false;
    }
    Collection<RailEdge> terminalEdges = graph.edgesFrom(terminal);
    return terminalEdges == null || terminalEdges.size() <= 1;
  }

  private boolean isStationOrDepotBehaviorNode(NodeId nodeId, RailGraph graph) {
    if (nodeId == null) {
      return false;
    }
    Optional<NodeType> graphType =
        graph == null ? Optional.empty() : graph.findNode(nodeId).map(RailNode::type);
    if (graphType.filter(type -> type == NodeType.STATION || type == NodeType.DEPOT).isPresent()) {
      return true;
    }
    return parseWaypointKind(nodeId)
        .map(kind -> kind == WaypointKind.STATION || kind == WaypointKind.DEPOT)
        .orElse(false);
  }

  /**
   * 读取前车计划中与当前 single-region 重合的方向。
   *
   * <p>生产图优先使用 {@link MovementPlanSnapshot#singleConflictDirections()} 中由 corridor
   * 元数据生成的方向；测试图或降级图可能只有 conflict key 而没有 corridor 元数据，此时只能用前车 plan 证明“路线确实覆盖该 conflict
   * edge”，方向沿用已占用 claim 里记录的前车方向。fallback 不尝试从 {@code EdgeId} 的排序端点反推业务 A/B 语义，避免站点/道岔 ID
   * 排序导致方向被反置；它只参与同向预测，仍不能绕过 exit proof、token 或 hard barrier。
   */
  private Optional<CorridorDirection> leaderPlanDirectionForConflict(
      MovementPlanSnapshot leaderPlan,
      RailGraphConflictSupport support,
      String conflictKey,
      CorridorDirection leaderClaimDirection) {
    if (leaderPlan == null || support == null || conflictKey == null || conflictKey.isBlank()) {
      return Optional.empty();
    }
    CorridorDirection mappedDirection = leaderPlan.singleConflictDirections().get(conflictKey);
    if (mappedDirection != null && mappedDirection != CorridorDirection.UNKNOWN) {
      return Optional.of(mappedDirection);
    }
    boolean routeCoversConflict =
        leaderPlan.directedEdges().stream()
            .anyMatch(
                edge ->
                    support
                        .conflictKeyForEdge(edge.edgeId())
                        .filter(conflictKey::equals)
                        .isPresent());
    if (!routeCoversConflict
        || leaderClaimDirection == null
        || leaderClaimDirection == CorridorDirection.UNKNOWN) {
      return Optional.empty();
    }
    return Optional.of(leaderClaimDirection);
  }

  private Optional<RouteDefinition> routeDefinitionForProgress(
      RouteProgressRegistry.RouteProgressEntry entry) {
    if (entry == null || routeDefinitions == null) {
      return Optional.empty();
    }
    if (entry.routeUuid() != null) {
      Optional<RouteDefinition> byUuid = routeDefinitions.findById(entry.routeUuid());
      if (byUuid != null && byUuid.isPresent()) {
        return byUuid;
      }
    }
    Map<UUID, RouteDefinition> snapshot = routeDefinitions.snapshot();
    if (snapshot == null || snapshot.isEmpty()) {
      return Optional.empty();
    }
    return snapshot.values().stream()
        .filter(route -> route != null && route.id().equals(entry.routeId()))
        .findFirst();
  }

  private boolean hasFreshLeaderProgress(String leaderTrain, Instant now) {
    if (leaderTrain == null || leaderTrain.isBlank() || progressRegistry == null || now == null) {
      return false;
    }
    return progressRegistry
        .get(leaderTrain)
        .map(RouteProgressRegistry.RouteProgressEntry::lastUpdatedAt)
        .filter(Objects::nonNull)
        .map(lastUpdated -> !lastUpdated.isBefore(now.minusSeconds(30)))
        .orElse(false);
  }

  private boolean leaderWillTerminalOrDwell(String leaderTrain) {
    if (leaderTrain == null || leaderTrain.isBlank()) {
      return false;
    }
    boolean terminal =
        progressRegistry != null
            && progressRegistry
                .get(leaderTrain)
                .map(RouteProgressRegistry.RouteProgressEntry::nextTarget)
                .map(Optional::isEmpty)
                .orElse(false);
    return terminal || leaderCurrentlyDwelling(leaderTrain);
  }

  private boolean leaderCurrentlyDwelling(String leaderTrain) {
    return leaderTrain != null
        && !leaderTrain.isBlank()
        && dwellRegistry != null
        && dwellRegistry.remainingSeconds(leaderTrain).isPresent();
  }

  private void traceSmartTrafficControlGate(
      String trainName, SmartDispatcherMode mode, String source, DispatchEffectClass effectClass) {
    debugLogger.accept(
        "SMART_TRAFFIC_CONTROL_GATE train="
            + trainName
            + " mode="
            + (mode == null ? SmartDispatcherMode.OBSERVE_ONLY : mode)
            + " source="
            + (source == null || source.isBlank() ? "smart-traffic-control" : source)
            + " effectClass="
            + (effectClass == null ? DispatchEffectClass.DIAGNOSTIC_ONLY : effectClass));
  }

  private void traceSmartRegionDataUnknown(
      String trainName,
      OccupancyRequestContext context,
      OccupancyResource conflict,
      SmartAdmissionContext admissionContext,
      String reason) {
    debugLogger.accept(
        "SMART_REGION_DATA_UNKNOWN train="
            + trainName
            + " regionId="
            + (conflict == null ? "-" : conflict.key())
            + " source="
            + (admissionContext == null ? "smart-admission" : admissionContext.source())
            + " reason="
            + (reason == null || reason.isBlank() ? "unknown" : reason));
    SignalComputationTrace.emit(
        signalTrace(
                trainName,
                null,
                SignalComputationTrace.Source.AUTHORIZATION,
                null,
                SignalAspect.STOP,
                "SMART_REGION_DATA_UNKNOWN")
            .field("regionId", conflict == null ? "-" : conflict.key())
            .field("reason", reason == null || reason.isBlank() ? "unknown" : reason)
            .request(context == null ? null : context.request()));
  }

  private void traceSmartRegionView(
      String trainName,
      OccupancyRequestContext context,
      OccupancyResource conflict,
      CorridorDirection direction,
      EntryLookaheadEvaluator.Result lookahead,
      SmartAdmissionContext admissionContext) {
    String reason = admissionContext == null ? "smart-admission" : admissionContext.source();
    SignalAspect traceAspect = smartRegionViewTraceAspect(lookahead);
    SignalComputationTrace.emit(
        signalTrace(
                trainName,
                null,
                SignalComputationTrace.Source.AUTHORIZATION,
                null,
                traceAspect,
                "SMART_REGION_VIEW")
            .field("regionId", conflict == null ? "-" : conflict.key())
            .field("singleConflictId", conflict == null ? "-" : conflict.key())
            .field("entryDirection", direction == null ? CorridorDirection.UNKNOWN : direction)
            .field("leaderExitVisible", lookahead != null && lookahead.exitFeasible())
            .field("source", reason)
            .request(context == null ? null : context.request()));
    SignalComputationTrace.emit(
        signalTrace(
                trainName,
                null,
                SignalComputationTrace.Source.AUTHORIZATION,
                null,
                traceAspect,
                "SMART_LONG_SINGLE_VIEW")
            .field("regionId", conflict == null ? "-" : conflict.key())
            .field("leaderExitVisible", lookahead != null && lookahead.exitFeasible())
            .field(
                "lookaheadWindowNodeCount",
                lookahead == null ? 0 : lookahead.lookaheadWindowNodeCount())
            .request(context == null ? null : context.request()));
    if (admissionContext != null && admissionContext.atDepot()) {
      SignalComputationTrace.emit(
          signalTrace(
                  trainName,
                  null,
                  SignalComputationTrace.Source.AUTHORIZATION,
                  null,
                  traceAspect,
                  "SMART_DEPOT_EXIT_VIEW")
              .field("regionId", conflict == null ? "-" : conflict.key())
              .field("depotExitIntoLongSingle", true)
              .field("leaderExitVisible", lookahead != null && lookahead.exitFeasible())
              .request(context == null ? null : context.request()));
    }
  }

  private void traceSameDirectionLeaderDrainPrediction(
      String followerTrain,
      OccupancyRequestContext context,
      OccupancyResource conflict,
      CorridorDirection followerDirection,
      SingleZoneAdmissionState admission,
      SameDirectionLeaderDrainPrediction prediction) {
    if (admission == null
        || !admission.sameDirectionLeader()
        || prediction == null
        || !prediction.applicable()) {
      return;
    }
    debugLogger.accept(
        "SMART_SAME_DIRECTION_LEADER_DRAIN_PREDICTION follower="
            + (followerTrain == null || followerTrain.isBlank() ? "-" : followerTrain)
            + " leader="
            + admission.leaderTrain()
            + " conflictZone="
            + (conflict == null ? "-" : conflict.key())
            + " followerDirection="
            + (followerDirection == null ? CorridorDirection.UNKNOWN : followerDirection)
            + " leaderDirection="
            + admission.leaderDirection()
            + " routeOverlap="
            + prediction.routeOverlap()
            + " directionMatches="
            + prediction.directionMatches()
            + " exitVisible="
            + prediction.exitVisible()
            + " boundaryOnly="
            + prediction.boundaryOnly()
            + " drainProven="
            + prediction.drainProven()
            + " leaderRouteId="
            + prediction.leaderRouteId()
            + " leaderCurrentIndex="
            + prediction.leaderCurrentIndex()
            + " leaderCurrentNode="
            + prediction.leaderCurrentNode()
            + " leaderExitReason="
            + prediction.leaderExitReason()
            + " reason="
            + prediction.reason()
            + " wouldMutate=false didMutate=false");
    SignalComputationTrace.emit(
        signalTrace(
                followerTrain,
                null,
                SignalComputationTrace.Source.AUTHORIZATION,
                null,
                prediction.drainProven() ? SignalAspect.PROCEED : SignalAspect.STOP,
                "SMART_SAME_DIRECTION_LEADER_DRAIN_PREDICTION")
            .field("leader", admission.leaderTrain())
            .field("conflictZone", conflict == null ? "-" : conflict.key())
            .field("routeOverlap", prediction.routeOverlap())
            .field("directionMatches", prediction.directionMatches())
            .field("exitVisible", prediction.exitVisible())
            .field("boundaryOnly", prediction.boundaryOnly())
            .field("drainProven", prediction.drainProven())
            .field("leaderRouteId", prediction.leaderRouteId())
            .field("leaderCurrentIndex", prediction.leaderCurrentIndex())
            .field("leaderCurrentNode", prediction.leaderCurrentNode())
            .field("leaderExitReason", prediction.leaderExitReason())
            .field("reason", prediction.reason())
            .field("wouldMutate", false)
            .field("didMutate", false)
            .request(context == null ? null : context.request()));
  }

  private void traceSameDirectionFollowThroughPreview(
      String followerTrain,
      OccupancyRequestContext context,
      OccupancyResource conflict,
      CorridorDirection followerDirection,
      AuthorityEnd authorityEnd,
      EntryLookaheadEvaluator.Result lookahead,
      SingleZoneAdmissionState admission,
      SameDirectionLeaderDrainPrediction leaderDrainPrediction) {
    if (admission == null || !admission.hasOtherPresence()) {
      return;
    }
    MovementAuthorizationToken leaderToken = movementToken(admission.leaderTrain()).orElse(null);
    boolean leaderDestinationPresent =
        leaderToken != null && leaderToken.committedDestination().isPresent();
    boolean leaderAuthorityWindowPresent = leaderToken != null && leaderToken.toNode() != null;
    boolean leaderExitVisible =
        leaderDrainPrediction != null
            && leaderDrainPrediction.applicable()
            && leaderDrainPrediction.drainProven();
    boolean safeGapKnown = leaderExitVisible && admission.followerSafeHoldPoint();
    boolean repeatedEdge = hasRepeatedEdge(context);
    boolean turnback = hasTurnback(context);
    DispatchDecisionSnapshot snapshot =
        new DispatchDecisionSnapshot(
            dispatchDecisionVersion.incrementAndGet(),
            occupancyVersion(),
            System.currentTimeMillis(),
            0L,
            followerTrain,
            firstContextNode(context),
            conflict == null ? "-" : conflict.key(),
            admission.occupantTrains(),
            "external",
            admission.sameDirectionLeader()
                ? "SAME_DIRECTION"
                : admission.oppositeDirectionPresent() ? "OPPOSITE_DIRECTION" : "UNKNOWN_DIRECTION",
            tokenState(admission.leaderTrain(), leaderToken),
            leaderDestinationPresent,
            leaderAuthorityWindowPresent,
            smartDispatcherMode(),
            DispatchAction.SAME_DIRECTION_FOLLOW_THROUGH_PREVIEW,
            admission.leaderTrain(),
            conflict == null ? "-" : conflict.key(),
            admission.sameDirectionLeader(),
            admission.oppositeDirectionPresent(),
            admission.oppositeOrUnknownPresence(),
            repeatedEdge,
            turnback,
            leaderExitVisible,
            safeGapKnown,
            safeGapKnown);
    SameDirectionFollowThroughDecision decision =
        SameDirectionFollowThroughPreview.evaluate(snapshot);
    String reason = sameDirectionFollowThroughReason(snapshot, decision);
    debugLogger.accept(
        "SMART_FOLLOW_THROUGH_PREVIEW mode="
            + snapshot.mode()
            + " action="
            + snapshot.actionCandidate()
            + " follower="
            + snapshot.trainId()
            + " leader="
            + snapshot.leaderTrain()
            + " conflictZone="
            + snapshot.conflictZone()
            + " followerCurrentResource="
            + snapshot.currentResource()
            + " followerEntryResource="
            + snapshot.requestedResource()
            + " leaderCurrentResource="
            + snapshot.conflictZone()
            + " leaderPredictedExitResource="
            + leaderPredictedExitResource(leaderToken, authorityEnd)
            + " sameDirection="
            + snapshot.sameDirection()
            + " oppositeDirection="
            + snapshot.oppositeDirection()
            + " repeatedEdge="
            + snapshot.repeatedEdge()
            + " turnback="
            + snapshot.turnback()
            + " leaderTokenState="
            + snapshot.movementTokenState()
            + " leaderDestinationPresent="
            + snapshot.destinationPresent()
            + " leaderAuthorityWindowPresent="
            + snapshot.authorityWindowPresent()
            + " externalBlockerPresent="
            + snapshot.externalBlockerPresent()
            + " safeGapKnown="
            + snapshot.safeGapKnown()
            + " safeGapSatisfied="
            + snapshot.safeGapSatisfied()
            + " leaderDrainPredictionReason="
            + (leaderDrainPrediction == null ? "-" : leaderDrainPrediction.reason())
            + " decisionVersion="
            + snapshot.decisionVersion()
            + " occupancyVersion="
            + snapshot.occupancyVersion()
            + " snapshotAgeMs="
            + snapshot.snapshotAgeMs()
            + " decision="
            + decision
            + " reason="
            + reason
            + " wouldMutate=false didMutate=false");
    SignalComputationTrace.emit(
        signalTrace(
                followerTrain,
                null,
                SignalComputationTrace.Source.AUTHORIZATION,
                null,
                sameDirectionFollowThroughTraceAspect(decision),
                "SMART_FOLLOW_THROUGH_PREVIEW")
            .field("mode", snapshot.mode())
            .field("action", snapshot.actionCandidate())
            .field("follower", snapshot.trainId())
            .field("leader", snapshot.leaderTrain())
            .field("conflictZone", snapshot.conflictZone())
            .field("sameDirection", snapshot.sameDirection())
            .field("oppositeDirection", snapshot.oppositeDirection())
            .field("decisionVersion", snapshot.decisionVersion())
            .field("occupancyVersion", snapshot.occupancyVersion())
            .field("decision", decision)
            .field("reason", reason)
            .field(
                "leaderDrainPredictionReason",
                leaderDrainPrediction == null ? "-" : leaderDrainPrediction.reason())
            .field("wouldMutate", false)
            .field("didMutate", false)
            .request(context == null ? null : context.request()));
  }

  private void traceRouteUnlockPotentialPreview(
      String trainA,
      OccupancyRequestContext context,
      OccupancyResource conflict,
      CorridorDirection direction,
      AuthorityEnd authorityEnd,
      EntryLookaheadEvaluator.Result lookahead,
      SingleZoneAdmissionState admission,
      SameDirectionLeaderDrainPrediction leaderDrainPrediction) {
    if (admission == null || !admission.hasOtherPresence()) {
      return;
    }
    MovementAuthorizationToken tokenA = movementToken(trainA).orElse(null);
    MovementAuthorizationToken tokenB = movementToken(admission.leaderTrain()).orElse(null);
    List<String> trainAHeld = heldResourceKeys(trainA);
    List<String> trainBHeld = heldResourceKeys(admission.leaderTrain());
    List<String> trainARequested =
        context == null || context.request() == null
            ? List.of()
            : context.request().resourceList().stream().map(Object::toString).toList();
    boolean trainAWouldDrain =
        hasClaimByTrain(conflict, trainA) && lookahead != null && lookahead.exitFeasible();
    boolean trainBWouldDrain =
        admission.sameDirectionLeader()
            && leaderDrainPrediction != null
            && leaderDrainPrediction.drainProven();
    RouteUnlockPotentialSnapshot snapshot =
        new RouteUnlockPotentialSnapshot(
            trainA,
            admission.leaderTrain(),
            conflict == null ? "-" : conflict.key(),
            conflict != null && conflict.key().startsWith("single:"),
            admission.oppositeDirectionPresent()
                ? "OPPOSITE_DIRECTION"
                : admission.unknownDirectionPresent()
                    ? "UNKNOWN_DIRECTION"
                    : admission.sameDirectionLeader() ? "SAME_DIRECTION" : "UNKNOWN_DIRECTION",
            firstContextNode(context),
            conflict == null ? "-" : conflict.key(),
            tokenA != null && tokenA.committedDestination().isPresent(),
            tokenB != null && tokenB.committedDestination().isPresent(),
            authorityEnd != null && authorityEnd.distanceBlocks().isPresent(),
            tokenB != null && tokenB.toNode() != null,
            trainAHeld,
            trainBHeld,
            trainARequested,
            List.of(),
            trainAWouldDrain,
            trainBWouldDrain,
            trainAWouldDrain && trainAHeld.contains(conflict == null ? "-" : conflict.toString()),
            trainBWouldDrain && trainBHeld.contains(conflict == null ? "-" : conflict.toString()),
            admission.oppositeOrUnknownPresence(),
            0L,
            occupancyVersion(),
            dispatchDecisionVersion.incrementAndGet());
    RouteUnlockPotentialDecision decision = RouteUnlockPotentialPreview.evaluate(snapshot);
    String reason = routeUnlockPotentialReason(snapshot, decision);
    debugLogger.accept(
        "ROUTE_UNLOCK_POTENTIAL_PREVIEW trainA="
            + snapshot.trainA()
            + " trainB="
            + snapshot.trainB()
            + " conflictZone="
            + snapshot.conflictZone()
            + " singleRegion="
            + snapshot.singleRegion()
            + " directionRelation="
            + snapshot.directionRelation()
            + " trainACurrentResource="
            + snapshot.trainACurrentResource()
            + " trainBCurrentResource="
            + snapshot.trainBCurrentResource()
            + " trainADestinationPresent="
            + snapshot.trainADestinationPresent()
            + " trainBDestinationPresent="
            + snapshot.trainBDestinationPresent()
            + " trainARouteWindowPresent="
            + snapshot.trainARouteWindowPresent()
            + " trainBRouteWindowPresent="
            + snapshot.trainBRouteWindowPresent()
            + " trainAHeldResources="
            + snapshot.trainAHeldResources()
            + " trainBHeldResources="
            + snapshot.trainBHeldResources()
            + " trainARequestedResources="
            + snapshot.trainARequestedResources()
            + " trainBRequestedResources="
            + snapshot.trainBRequestedResources()
            + " trainAWouldDrain="
            + snapshot.trainAWouldDrain()
            + " trainBWouldDrain="
            + snapshot.trainBWouldDrain()
            + " trainAWouldReleaseBlockerForB="
            + snapshot.trainAWouldReleaseBlockerForB()
            + " trainBWouldReleaseBlockerForA="
            + snapshot.trainBWouldReleaseBlockerForA()
            + " externalOppositeOccupantPresent="
            + snapshot.externalOppositeOccupantPresent()
            + " snapshotAgeMs="
            + snapshot.snapshotAgeMs()
            + " occupancyVersion="
            + snapshot.occupancyVersion()
            + " decisionVersion="
            + snapshot.decisionVersion()
            + " decision="
            + decision
            + " reason="
            + reason
            + " wouldMutate=false didMutate=false");
  }

  private List<String> heldResourceKeys(String trainName) {
    if (trainName == null || trainName.isBlank() || occupancyManager == null) {
      return List.of();
    }
    return occupancyManager.snapshotClaims().stream()
        .filter(
            claim ->
                claim != null && TrainNameNormalizer.sameLogicalTrain(trainName, claim.trainName()))
        .map(OccupancyClaim::resource)
        .filter(Objects::nonNull)
        .map(Object::toString)
        .toList();
  }

  private void traceThroatSectionAtomic(
      String trainName, OccupancyResource conflict, ThroatSectionAtomicCheck check) {
    if (check == null || !check.applicable()) {
      return;
    }
    debugLogger.accept(
        "SMART_THROAT_SECTION_ATOMIC train="
            + (trainName == null || trainName.isBlank() ? "-" : trainName)
            + " conflict="
            + (conflict == null ? "-" : conflict.key())
            + " throatEntry="
            + check.throatEntry()
            + " throatExitSafePoint="
            + check.throatExitSafePoint()
            + " occupiedResource="
            + check.occupiedResource()
            + " clear="
            + check.clear()
            + " reason="
            + check.reason()
            + " resourceCount="
            + check.resources().size());
  }

  private static String routeUnlockPotentialReason(
      RouteUnlockPotentialSnapshot snapshot, RouteUnlockPotentialDecision decision) {
    return switch (decision) {
      case A_CAN_DRAIN_TO_UNLOCK_B -> "train-a-can-drain";
      case B_CAN_DRAIN_TO_UNLOCK_A -> "train-b-can-drain";
      case BOTH_CAN_DRAIN -> "both-can-drain";
      case NEITHER_CAN_DRAIN -> "neither-can-drain";
      case UNSAFE_OPPOSITE_SINGLE_REGION -> SimpleOccupancyManager
          .OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER;
      case UNKNOWN_FAIL_SAFE -> snapshot == null || snapshot.snapshotAgeMs() < 0
          ? "snapshot-stale"
          : "missing-route-token-destination-or-topology";
    };
  }

  private static String sameDirectionFollowThroughReason(
      DispatchDecisionSnapshot snapshot, SameDirectionFollowThroughDecision decision) {
    return switch (decision) {
      case WOULD_ALLOW_FOLLOW_THROUGH -> "all-same-direction-evidence-present";
      case WOULD_HOLD_FOR_LEADER_EXIT -> "leader-exit-not-yet-provable";
      case WOULD_DENY_UNSAFE -> snapshot != null && snapshot.oppositeDirection()
          ? "opposite-direction"
          : snapshot != null && snapshot.externalBlockerPresent()
              ? "external-blocker-present"
              : snapshot != null && snapshot.repeatedEdge()
                  ? "repeated-edge"
                  : snapshot != null && snapshot.turnback() ? "turnback" : "unsafe-gap";
      case UNKNOWN_FAIL_SAFE -> "missing-or-stale-safety-evidence";
    };
  }

  private static SignalAspect smartRegionViewTraceAspect(EntryLookaheadEvaluator.Result lookahead) {
    return lookahead != null && lookahead.exitFeasible() ? SignalAspect.PROCEED : SignalAspect.STOP;
  }

  private static SignalAspect sameDirectionFollowThroughTraceAspect(
      SameDirectionFollowThroughDecision decision) {
    return decision == SameDirectionFollowThroughDecision.WOULD_ALLOW_FOLLOW_THROUGH
        ? SignalAspect.PROCEED
        : SignalAspect.STOP;
  }

  private static String leaderPredictedExitResource(
      MovementAuthorizationToken leaderToken, AuthorityEnd authorityEnd) {
    if (leaderToken != null && leaderToken.toNode() != null) {
      return leaderToken.toNode().value();
    }
    return authorityEnd == null ? "-" : authorityEnd.resource();
  }

  private static boolean hasRepeatedEdge(OccupancyRequestContext context) {
    if (context == null || context.edges().isEmpty()) {
      return false;
    }
    return new HashSet<>(context.edges()).size() < context.edges().size();
  }

  private static boolean hasTurnback(OccupancyRequestContext context) {
    if (context == null
        || context.request() == null
        || context.request().directedContext().isEmpty()) {
      return false;
    }
    List<DirectedTraversalContext.DirectedEdge> edges =
        context.request().directedContext().get().directedEdges();
    for (int i = 1; i < edges.size(); i++) {
      DirectedTraversalContext.DirectedEdge previous = edges.get(i - 1);
      DirectedTraversalContext.DirectedEdge current = edges.get(i);
      if (previous.fromNode().equals(current.toNode())
          && previous.toNode().equals(current.fromNode())) {
        return true;
      }
    }
    return false;
  }

  private void traceSmartAdmissionResult(
      String trainName, OccupancyRequestContext context, SmartAdmissionResult result) {
    if (result == null || !result.applies()) {
      return;
    }
    SingleZoneAdmissionState admission = result.state();
    String event = result.allowed() ? "SMART_ADMISSION_ALLOWED" : "SMART_ADMISSION_BLOCKED";
    SignalComputationTrace.emit(
        signalTrace(
                trainName,
                null,
                SignalComputationTrace.Source.AUTHORIZATION,
                null,
                result.allowed() ? SignalAspect.PROCEED : SignalAspect.STOP,
                "SMART_ADMISSION_CHECK")
            .field("trainId", trainName)
            .field("regionId", result.region() == null ? "-" : result.region().key())
            .field("singleConflictId", result.region() == null ? "-" : result.region().key())
            .field("entryNode", firstContextNode(context))
            .field("intendedExitNode", lastContextNode(context))
            .field("occupantTrains", admission == null ? List.of() : admission.occupantTrains())
            .field(
                "occupantDirections",
                admission == null ? List.of() : admission.occupantDirections())
            .field("leaderTrain", admission == null ? "-" : admission.leaderTrain())
            .field("leaderProgressFreshness", admission == null || admission.leaderProgressFresh())
            .field(
                "leaderExitVisible",
                result.lookahead() != null && result.lookahead().exitFeasible())
            .field(
                "oppositeDirectionPresent",
                admission != null && admission.oppositeDirectionPresent())
            .field(
                "unknownDirectionPresent", admission != null && admission.unknownDirectionPresent())
            .field("alreadyInside", result.alreadyInside())
            .field("atDepot", result.context().atDepot())
            .field("atStation", result.context().atStation())
            .field("localOnlyHold", result.localOnlyHold())
            .field("destinationMutated", false)
            .field("tokenInvalidated", false)
            .request(context == null ? null : context.request()));
    SignalComputationTrace.emit(
        signalTrace(
                trainName,
                null,
                SignalComputationTrace.Source.AUTHORIZATION,
                null,
                result.allowed() ? SignalAspect.PROCEED : SignalAspect.STOP,
                event)
            .field("admissionDecision", result.decision())
            .field("SMART_ADMISSION_REASON", result.reason())
            .field("regionId", result.region() == null ? "-" : result.region().key())
            .field("localOnlyHold", result.localOnlyHold())
            .field("destinationMutated", false)
            .field("tokenInvalidated", false)
            .request(context == null ? null : context.request()));
    if (!result.allowed()) {
      debugLogger.accept(
          "SMART_ADMISSION_REASON train="
              + trainName
              + " decision="
              + result.decision()
              + " reason="
              + result.reason()
              + " regionId="
              + (result.region() == null ? "-" : result.region().key())
              + " localOnlyHold=true destinationMutated=false tokenInvalidated=false");
      if (result.context().atDepot()) {
        debugLogger.accept(
            "SMART_DEPOT_LONG_SINGLE_HELD train="
                + trainName
                + " regionId="
                + (result.region() == null ? "-" : result.region().key())
                + " reason="
                + result.reason()
                + " localOnlyHold=true destinationMutated=false tokenInvalidated=false");
      }
      if (result.context().atStation()) {
        debugLogger.accept(
            "SMART_STATION_DEPARTURE_HELD train="
                + trainName
                + " regionId="
                + (result.region() == null ? "-" : result.region().key())
                + " reason="
                + result.reason()
                + " localOnlyHold=true destinationMutated=false tokenInvalidated=false");
      }
    }
  }

  private void traceSmartDownstreamCongestion(
      String trainName,
      OccupancyRequestContext context,
      OccupancyResource conflict,
      SingleZoneAdmissionState admission,
      SmartAdmissionDecision decision,
      String reason) {
    debugLogger.accept(
        "SMART_DOWNSTREAM_CONGESTION_CHECK train="
            + trainName
            + " regionId="
            + (conflict == null ? "-" : conflict.key())
            + " leaderTrain="
            + (admission == null ? "-" : admission.leaderTrain())
            + " reason="
            + (reason == null || reason.isBlank() ? "-" : reason));
    if (admission == null || !admission.sameDirectionLeader()) {
      return;
    }
    debugLogger.accept(
        "SMART_DOWNSTREAM_CONGESTION_DETECTED train="
            + trainName
            + " leaderTrain="
            + admission.leaderTrain()
            + " regionId="
            + (conflict == null ? "-" : conflict.key())
            + " decision="
            + decision
            + " reason="
            + (reason == null || reason.isBlank() ? "-" : reason));
    if (decision == SmartAdmissionDecision.HOLD_AT_STATION) {
      debugLogger.accept("SMART_FOLLOWER_DEPARTURE_HELD train=" + trainName + " location=station");
    } else if (decision == SmartAdmissionDecision.HOLD_AT_DEPOT) {
      debugLogger.accept("SMART_FOLLOWER_DEPARTURE_HELD train=" + trainName + " location=depot");
    } else {
      debugLogger.accept(
          "SMART_FOLLOWER_ENTRY_BLOCKED_BY_STUCK_LEADER train="
              + trainName
              + " leaderTrain="
              + admission.leaderTrain());
      rememberFollowerStuckLeaderEvidence(
          trainName,
          admission.leaderTrain(),
          conflict == null ? "-" : "CONFLICT:" + conflict.key(),
          Instant.now());
    }
    if (context != null && context.request() != null) {
      debugLogger.accept(
          "SMART_FOLLOWER_DESTINATION_NOT_ISSUED train="
              + trainName
              + " regionId="
              + (conflict == null ? "-" : conflict.key()));
    }
  }

  private String firstContextNode(OccupancyRequestContext context) {
    if (context == null || context.pathNodes().isEmpty() || context.pathNodes().get(0) == null) {
      return "-";
    }
    return context.pathNodes().get(0).value();
  }

  private String lastContextNode(OccupancyRequestContext context) {
    if (context == null || context.pathNodes().isEmpty()) {
      return "-";
    }
    NodeId node = context.pathNodes().get(context.pathNodes().size() - 1);
    return node == null ? "-" : node.value();
  }

  private void traceSingleZoneAdmissionCheck(
      String trainName,
      OccupancyRequestContext context,
      OccupancyResource conflict,
      CorridorDirection direction,
      EntryLookaheadEvaluator.Result lookahead) {
    OccupancyRequest request = context == null ? null : context.request();
    SignalComputationTrace.emit(
        signalTrace(
                trainName,
                null,
                SignalComputationTrace.Source.AUTHORIZATION,
                null,
                SignalAspect.STOP,
                "SINGLE_ZONE_ADMISSION_CHECK")
            .field("zoneId", conflict == null ? "-" : conflict.key())
            .field("entryDirection", direction == null ? CorridorDirection.UNKNOWN : direction)
            .field("zoneExitVisible", lookahead != null && lookahead.exitFeasible())
            .field("leaderExitVisible", lookahead != null && lookahead.exitFeasible())
            .field("alreadyInsideSingleZone", false)
            .request(request));
  }

  private void traceSingleZoneAdmissionDecision(
      String trainName,
      OccupancyRequestContext context,
      OccupancyResource conflict,
      CorridorDirection direction,
      EntryLookaheadEvaluator.Result lookahead,
      SingleZoneAdmissionState admission,
      boolean allowed,
      String reason) {
    OccupancyRequest request = context == null ? null : context.request();
    boolean sameDirectionButUnsafe =
        admission != null
            && admission.sameDirectionLeader()
            && (admission.leaderStalled() || !admission.followerSafeHoldPoint());
    SignalComputationTrace.emit(
        signalTrace(
                trainName,
                null,
                SignalComputationTrace.Source.AUTHORIZATION,
                null,
                allowed ? SignalAspect.PROCEED : SignalAspect.STOP,
                allowed ? "SINGLE_ZONE_ADMISSION_ALLOWED" : "SINGLE_ZONE_ADMISSION_BLOCKED")
            .field("zoneId", conflict == null ? "-" : conflict.key())
            .field("entryDirection", direction == null ? CorridorDirection.UNKNOWN : direction)
            .field("sameDirectionButUnsafe", sameDirectionButUnsafe)
            .field("leaderExitVisible", lookahead != null && lookahead.exitFeasible())
            .field("leaderWillReverse", admission != null && admission.oppositeOrUnknownPresence())
            .field("leaderWillDwellOrStop", admission != null && admission.leaderStalled())
            .field("leaderStalled", admission != null && admission.leaderStalled())
            .field("followerSafeHoldPoint", admission != null && admission.followerSafeHoldPoint())
            .field(
                "followerWouldOverrunEntry",
                admission != null && !admission.followerSafeHoldPoint())
            .field("zoneExitVisible", lookahead != null && lookahead.exitFeasible())
            .field("leaderTrain", admission == null ? "-" : admission.leaderTrain())
            .field(
                "leaderDirection",
                admission == null ? CorridorDirection.UNKNOWN : admission.leaderDirection())
            .field("failureReason", reason == null || reason.isBlank() ? "-" : reason)
            .request(request));
  }

  private void traceEntryLookaheadBlocked(
      String trainName,
      OccupancyRequestContext context,
      OccupancyResource conflict,
      EntryLookaheadEvaluator.Result lookahead) {
    OccupancyRequest request = context == null ? null : context.request();
    SignalComputationTrace.emit(
        signalTrace(
                trainName,
                null,
                SignalComputationTrace.Source.AUTHORIZATION,
                null,
                SignalAspect.STOP,
                "ENTRY_LOOKAHEAD_BLOCKED")
            .field("lookaheadUsesExpandedPath", true)
            .field(
                "lookaheadWindowNodeCount",
                lookahead == null ? 0 : lookahead.lookaheadWindowNodeCount())
            .field("entryZoneId", conflict == null ? "-" : conflict.key())
            .field("entryZoneStartIndex", lookahead == null ? -1 : lookahead.entryZoneStartIndex())
            .field(
                "exitIndexBeforeExtension",
                lookahead == null ? -1 : lookahead.exitIndexBeforeExtension())
            .field("extensionAttempted", lookahead != null && lookahead.extensionAttempted())
            .field(
                "exitIndexAfterExtension",
                lookahead == null ? -1 : lookahead.exitIndexAfterExtension())
            .field("exitFeasible", lookahead != null && lookahead.exitFeasible())
            .field(
                "failureReason",
                lookahead == null ? "exit-not-visible-after-extension" : lookahead.failureReason())
            .field("zoneId", conflict == null ? "-" : conflict.key())
            .field("zoneMembership", "ENTERING_ZONE")
            .field("drainLeader", false)
            .request(request));
  }

  private Optional<OccupancyResource> singleConflictForEdge(RailGraph graph, RailEdge edge) {
    if (graph == null || edge == null) {
      return Optional.empty();
    }
    return OccupancyResourceResolver.resourcesForEdge(graph, edge).stream()
        .filter(this::isSingleCorridorResource)
        .findFirst();
  }

  /**
   * 为已选 single 资源生成 edge membership 视图。
   *
   * <p>legacy corridor 与 bridge section 都经 {@link OccupancyResourceResolver}
   * 进入请求；运行时的出口、重合与排空判断必须读取同一资源边界， 不能绕过 resolver 再从原始 micro corridor 索引重建一个不同的区域。
   */
  private RailGraphConflictSupport singleConflictMembershipSupport(
      RailGraph graph, OccupancyResource conflict) {
    return edgeId ->
        findGraphEdge(graph, edgeId)
            .filter(
                edge -> OccupancyResourceResolver.resourcesForEdge(graph, edge).contains(conflict))
            .map(ignored -> conflict.key());
  }

  private Optional<RailEdge> findGraphEdge(RailGraph graph, EdgeId edgeId) {
    if (graph == null || edgeId == null || edgeId.a() == null || edgeId.b() == null) {
      return Optional.empty();
    }
    EdgeId normalized = EdgeId.undirected(edgeId.a(), edgeId.b());
    Collection<RailEdge> adjacent = graph.edgesFrom(edgeId.a());
    if (adjacent == null || adjacent.isEmpty()) {
      return Optional.empty();
    }
    for (RailEdge edge : adjacent) {
      if (edge != null && normalized.equals(EdgeId.undirected(edge.from(), edge.to()))) {
        return Optional.of(edge);
      }
    }
    return Optional.empty();
  }

  private boolean hasClaimByTrain(OccupancyResource resource, String trainName) {
    if (occupancyManager == null || resource == null || trainName == null || trainName.isBlank()) {
      return false;
    }
    return occupancyManager.snapshotClaims().stream()
        .anyMatch(
            claim ->
                claim != null
                    && resource.equals(claim.resource())
                    && claim.trainName() != null
                    && TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName));
  }

  private Set<OccupancyResource> protectedSingleCorridorClaims(
      String trainName,
      OccupancyRequest request,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode,
      Optional<NodeId> targetNode,
      DispatchPriorityResolution priorityResolution) {
    if (occupancyManager == null || trainName == null || trainName.isBlank()) {
      return Set.of();
    }
    Set<OccupancyResource> keep =
        request == null ? Set.of() : new LinkedHashSet<>(request.resourceList());
    boolean keepHasSingle = keep.stream().anyMatch(this::isSingleCorridorResource);
    Set<OccupancyResource> protectedResources = new LinkedHashSet<>();
    for (OccupancyClaim claim : occupancyManager.snapshotClaims()) {
      if (claim == null
          || claim.resource() == null
          || claim.trainName() == null
          || !claim.trainName().equalsIgnoreCase(trainName)
          || !isSingleCorridorResource(claim.resource())) {
        continue;
      }
      boolean keepContains = keep.contains(claim.resource());
      // hold request 已经能证明当前位置仍在该 corridor；或者无法可靠判定出口时，先保守保护旧 claim。
      boolean protectedByCurrentLogic = keepContains || !keepHasSingle;
      traceStopRetainProtectOldSingle(
          trainName,
          claim,
          request,
          route,
          currentIndex,
          currentNode,
          targetNode,
          priorityResolution,
          keepContains,
          keepHasSingle,
          protectedByCurrentLogic);
      if (protectedByCurrentLogic) {
        protectedResources.add(claim.resource());
      }
    }
    return Set.copyOf(protectedResources);
  }

  private Set<OccupancyResource> protectedSwitcherZoneClaims(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode,
      RailGraph graph,
      String source) {
    if (occupancyManager == null
        || trainName == null
        || trainName.isBlank()
        || route == null
        || currentIndex < 0
        || currentNode == null
        || graph == null) {
      return Set.of();
    }
    Set<OccupancyResource> protectedResources = new LinkedHashSet<>();
    Optional<NodeId> lastPassed =
        progressRegistry
            .get(trainName)
            .flatMap(RouteProgressRegistry.RouteProgressEntry::lastPassedGraphNode);
    Optional<NodeId> nextTarget =
        currentIndex + 1 < route.waypoints().size()
            ? Optional.ofNullable(resolveEffectiveNode(trainName, route, currentIndex + 1))
            : Optional.empty();
    for (OccupancyClaim claim : occupancyManager.snapshotClaims()) {
      if (claim == null
          || claim.resource() == null
          || claim.trainName() == null
          || !TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)) {
        continue;
      }
      Optional<NodeId> switcherNode = switcherNodeFromConflict(claim.resource());
      if (switcherNode.isEmpty()) {
        continue;
      }
      if (!isTrainStillInsideSwitcherZone(
          trainName, route, currentIndex, currentNode, graph, switcherNode.get())) {
        continue;
      }
      protectedResources.add(claim.resource());
      debugLogger.accept(
          "保护道岔占用: train="
              + trainName
              + " key="
              + normalizeTrainKey(trainName)
              + " resource=CONFLICT:"
              + claim.resource().key()
              + " reason=still-inside-switcher-zone"
              + " currentNode="
              + currentNode.value()
              + " lastPassedGraphNode="
              + lastPassed.map(NodeId::value).orElse("-")
              + " nextTarget="
              + nextTarget.map(NodeId::value).orElse("-")
              + " currentIndex="
              + currentIndex
              + " source="
              + (source == null || source.isBlank() ? "RELEASE_OUTSIDE_WINDOW" : source));
    }
    return Set.copyOf(protectedResources);
  }

  private boolean isTrainStillInsideSwitcherZone(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode,
      RailGraph graph,
      NodeId switcherNode) {
    if (route == null
        || currentIndex < 0
        || currentNode == null
        || graph == null
        || switcherNode == null) {
      return false;
    }
    if (graph.findNode(switcherNode).filter(node -> node.type() == NodeType.SWITCHER).isEmpty()) {
      return false;
    }
    if (currentNode.equals(switcherNode)) {
      return true;
    }
    if (currentIndex + 1 >= route.waypoints().size()) {
      return false;
    }
    NodeId segmentStart = resolveEffectiveNode(trainName, route, currentIndex);
    NodeId segmentEnd = resolveEffectiveNode(trainName, route, currentIndex + 1);
    if (segmentStart == null || segmentEnd == null) {
      return false;
    }
    Optional<RailGraphPath> pathOpt =
        pathFinder.shortestPath(
            graph, segmentStart, segmentEnd, RailGraphPathFinder.Options.shortestDistance());
    if (pathOpt.isEmpty()) {
      return false;
    }
    List<NodeId> pathNodes = pathOpt.get().nodes();
    int switcherIndex = pathNodes.indexOf(switcherNode);
    if (switcherIndex < 0) {
      return false;
    }
    NodeId positionNode = currentNode;
    int positionIndex = pathNodes.indexOf(positionNode);
    if (positionIndex < 0) {
      Optional<NodeId> lastPassed =
          progressRegistry
              .get(trainName)
              .flatMap(RouteProgressRegistry.RouteProgressEntry::lastPassedGraphNode)
              .filter(pathNodes::contains);
      if (lastPassed.isPresent()) {
        positionNode = lastPassed.get();
        positionIndex = pathNodes.indexOf(positionNode);
      }
    }
    if (positionIndex < 0) {
      return false;
    }
    int switcherZoneEdges =
        Math.max(0, configManager.current().runtimeSettings().switcherZoneEdges());
    return Math.abs(positionIndex - switcherIndex) <= switcherZoneEdges;
  }

  private Optional<NodeId> switcherNodeFromConflict(OccupancyResource resource) {
    if (resource == null || resource.kind() != ResourceKind.CONFLICT) {
      return Optional.empty();
    }
    String key = resource.key();
    if (key == null || !key.startsWith("switcher:")) {
      return Optional.empty();
    }
    String nodeId = key.substring("switcher:".length()).trim();
    if (nodeId.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(NodeId.of(nodeId));
  }

  private static Set<OccupancyResource> mergeProtectedResources(
      Set<OccupancyResource> first, Set<OccupancyResource> second) {
    if ((first == null || first.isEmpty()) && (second == null || second.isEmpty())) {
      return Set.of();
    }
    Set<OccupancyResource> merged = new LinkedHashSet<>();
    if (first != null) {
      merged.addAll(first);
    }
    if (second != null) {
      merged.addAll(second);
    }
    return Set.copyOf(merged);
  }

  private boolean isSingleCorridorResource(OccupancyResource resource) {
    return resource != null
        && resource.kind() == ResourceKind.CONFLICT
        && resource.key().startsWith("single:")
        && !resource.key().contains(":cycle:");
  }

  private BlockedDestinationDiagnostic resolveBlockedDestinationDiagnostic(
      TrainProperties properties,
      SignalAspect effectiveSignal,
      boolean proceedAllowed,
      OccupancyDecision decision) {
    boolean blocked = !proceedAllowed || effectiveSignal == SignalAspect.STOP;
    String retained = readDestination(properties);
    boolean present = blocked && !retained.isBlank();
    String reason = resolveBlockedReason(effectiveSignal, proceedAllowed, decision);
    return new BlockedDestinationDiagnostic(present, present ? retained : "-", reason);
  }

  private static String readDestination(TrainProperties properties) {
    if (properties == null) {
      return "";
    }
    String destination = properties.getDestination();
    return destination == null ? "" : destination.trim();
  }

  private static String resolveBlockedReason(
      SignalAspect effectiveSignal, boolean proceedAllowed, OccupancyDecision decision) {
    if (decision != null && decision.reason() != null && !"none".equals(decision.reason())) {
      return decision.reason();
    }
    if (!proceedAllowed) {
      return "authorization-blocked";
    }
    if (effectiveSignal == SignalAspect.STOP) {
      return "signal-stop";
    }
    return "none";
  }

  private static int signalSeverity(SignalAspect aspect) {
    if (aspect == null) {
      return Integer.MAX_VALUE;
    }
    return switch (aspect) {
      case PROCEED -> 0;
      case PROCEED_WITH_CAUTION -> 1;
      case CAUTION -> 2;
      case STOP -> 3;
    };
  }

  private SignalAspect stageSignalAspectForAuthorityAndAdvisory(
      String trainName,
      SignalAspect currentAspect,
      OccupancyDecision hardDecision,
      OccupancyDecision advisoryDecision,
      List<AdvisoryRisk> advisoryRisks) {
    SignalAspect safeAspect = currentAspect == null ? SignalAspect.STOP : currentAspect;
    boolean hardBlocked = hardDecision != null && !hardDecision.allowed();
    boolean advisoryRisk =
        advisoryDecision != null
            && advisoryDecision.allowed()
            && SignalDecisionInputClassifier.isProceedLike(advisoryDecision.signal())
            && advisoryDecision.signal() == SignalAspect.PROCEED_WITH_CAUTION
            && advisoryDecision.blockers() != null
            && !advisoryDecision.blockers().isEmpty();
    if (hardBlocked) {
      debugLogger.accept(
          "SIGNAL_ASPECT_STAGING train="
              + trainName
              + " result=STOP reason=HARD_AUTHORITY_BLOCKED");
      return SignalAspect.STOP;
    }
    if (advisoryRisk) {
      String resource =
          advisoryRisks == null || advisoryRisks.isEmpty()
              ? nearestBlockerResource(advisoryDecision)
              : String.valueOf(advisoryRisks.get(0).resource());
      debugLogger.accept(
          "SIGNAL_ASPECT_STAGING train="
              + trainName
              + " result=PROCEED_WITH_CAUTION reason=ADVISORY_CAUTION_SELECTED"
              + " advisoryRiskResource="
              + resource);
      debugLogger.accept(
          "ADVISORY_RISK_TO_CAUTION train=" + trainName + " advisoryRiskResource=" + resource);
      return signalSeverity(SignalAspect.PROCEED_WITH_CAUTION) > signalSeverity(safeAspect)
          ? SignalAspect.PROCEED_WITH_CAUTION
          : safeAspect;
    }
    debugLogger.accept(
        "SIGNAL_ASPECT_STAGING train="
            + trainName
            + " result="
            + safeAspect
            + " reason=ADVISORY_CAUTION_SKIPPED");
    return safeAspect;
  }

  /**
   * 运行 Smart Dispatcher 的前方风险与制动预判。
   *
   * <p>该方法只允许把可见的前方风险提前压成 CAUTION/限速；不会把 stale/unknown/protective-only 风险升级成 hard STOP，也不会绕过后续
   * {@link SignalPublicationGate} 最终安全检查。
   */
  private void traceAuthorityWindowSplit(
      String trainName,
      OccupancyRequest hardRequest,
      OccupancyRequest advisoryRequest,
      OccupancyDecision hardDecision,
      OccupancyDecision advisoryDecision,
      SignalLookahead.LookaheadResult advisoryLookahead) {
    debugLogger.accept(
        "SIGNAL_CAUTION_REASON train="
            + trainName
            + " hardAuthorityWindowResourceCount="
            + (hardRequest == null ? 0 : hardRequest.resourceList().size())
            + " advisoryLookaheadWindowResourceCount="
            + (advisoryRequest == null ? 0 : advisoryRequest.resourceList().size())
            + " nearestAdvisoryRisk="
            + formatOptionalLong(
                advisoryLookahead == null
                    ? OptionalLong.empty()
                    : advisoryLookahead.distanceToBlocker())
            + " nearestHardAuthorityBlocker="
            + nearestBlockerResource(hardDecision)
            + " advisoryBlocker="
            + nearestBlockerResource(advisoryDecision));
  }

  private static String nearestBlockerResource(OccupancyDecision decision) {
    OccupancyClaim blocker = primaryBlocker(decision);
    if (blocker == null || blocker.resource() == null) {
      return "-";
    }
    return blocker.resource().toString();
  }

  private SmartSignalDecisionResult applySmartForwardSignalDecision(
      String trainName,
      RuntimeTrainHandle train,
      TrainProperties properties,
      OccupancyRequest request,
      OccupancyDecision decision,
      SignalLookahead.LookaheadResult lookahead,
      AuthorityEnd authorityEnd,
      SignalAspect currentAspect,
      OptionalLong distanceOpt,
      OptionalDouble movementAuthorityLimitBps,
      boolean stopAtNextWaypoint) {
    SignalAspect safeAspect = currentAspect == null ? SignalAspect.STOP : currentAspect;
    OptionalDouble safeAuthorityLimit =
        movementAuthorityLimitBps == null ? OptionalDouble.empty() : movementAuthorityLimitBps;
    OptionalLong safeDistance = distanceOpt == null ? OptionalLong.empty() : distanceOpt;
    SmartDispatcherMode mode = smartDispatcherMode();
    traceSmartDispatcherMode(trainName, mode, "forward-signal");
    if (mode == SmartDispatcherMode.OFF) {
      debugLogger.accept(
          "SMART_DISPATCH_DISABLED train=" + trainName + " source=forward-signal mode=" + mode);
      return new SmartSignalDecisionResult(safeAspect, safeAuthorityLimit, safeDistance);
    }
    ForwardSignalRiskSnapshot risk =
        buildForwardSignalRiskSnapshot(
            trainName, request, decision, lookahead, authorityEnd, stopAtNextWaypoint);
    TrainConfig trainConfig = trainConfigResolver.resolve(properties, configManager.current());
    double edgeLimit = 0.0;
    if (train != null && train.isValid()) {
      edgeLimit = configManager.current().graphSettings().defaultSpeedBlocksPerSecond();
    }
    double cautionSpeed =
        resolveCautionSpeedDecision(train == null ? null : train.worldId(), null).speedBps();
    SmartDispatcherController.ForwardDecisionInput input =
        new SmartDispatcherController.ForwardDecisionInput(
            trainName,
            risk,
            safeAspect,
            train == null ? 0.0 : train.currentSpeedBlocksPerTick() * SPEED_TICKS_PER_SECOND,
            edgeLimit,
            cautionSpeed,
            trainConfig.decelBps2(),
            Math.max(1L, configManager.current().runtimeSettings().lookaheadEdges()) * 64L,
            configManager.current().runtimeSettings().movementAuthorityStopMarginBlocks(),
            configManager.current().runtimeSettings().movementAuthorityCautionMarginBlocks(),
            false,
            "none",
            authorityEnd == null ? AuthorityEndReason.NONE.name() : authorityEnd.reason().name());
    DispatchDecision dispatchDecision = smartDispatcherController.decideForwardSignal(input);
    traceSmartDispatcherActionObserved(trainName, mode, dispatchDecision);
    if (mode == SmartDispatcherMode.OBSERVE_ONLY) {
      traceSmartDispatcherActionSuppressed(
          trainName, mode, dispatchDecision, "observe-only-no-side-effects");
      return new SmartSignalDecisionResult(
          safeAspect,
          safeAuthorityLimit,
          safeDistance,
          dispatchDecision.action(),
          dispatchDecision.riskSource(),
          false,
          smartStopReason(dispatchDecision));
    }
    if (safeAspect == SignalAspect.STOP) {
      return new SmartSignalDecisionResult(
          safeAspect,
          safeAuthorityLimit,
          safeDistance,
          dispatchDecision.action(),
          dispatchDecision.riskSource(),
          invalidatingAuthorityStop(authorityEnd, dispatchDecision),
          smartStopReason(dispatchDecision));
    }
    if (dispatchDecision.action() == DispatchAction.PROCEED_WITH_CAUTION
        || dispatchDecision.action() == DispatchAction.CAUTION_SPEED_LIMIT) {
      SmartDispatcherModeGate.EffectPermissions permissions =
          SmartDispatcherModeGate.permissions(mode, dispatchDecision.effectClass());
      if (!permissions.canChangeAspect() && !permissions.canChangeTargetSpeed()) {
        traceSmartDispatcherActionSuppressed(
            trainName, mode, dispatchDecision, "effect-class-not-enabled");
        return new SmartSignalDecisionResult(
            safeAspect,
            safeAuthorityLimit,
            safeDistance,
            dispatchDecision.action(),
            dispatchDecision.riskSource(),
            false,
            smartStopReason(dispatchDecision));
      }
      debugLogger.accept(
          "SMART_ACTION_ALLOWED_BY_EFFECT_GATE train="
              + trainName
              + " mode="
              + mode
              + " action="
              + dispatchDecision.action()
              + " effectClass="
              + dispatchDecision.effectClass()
              + " source=forward-signal");
      if (!shouldApplySmartRuntimeOverride(dispatchDecision.riskSource())) {
        debugLogger.accept(
            "SMART_DISPATCH_ACTION_REJECTED train="
                + trainName
                + " action="
                + dispatchDecision.action()
                + " reason=handled-by-runtime-speed-envelope source="
                + dispatchDecision.riskSource());
        return new SmartSignalDecisionResult(
            safeAspect,
            safeAuthorityLimit,
            safeDistance,
            dispatchDecision.action(),
            dispatchDecision.riskSource(),
            false,
            smartStopReason(dispatchDecision));
      }
      SignalAspect nextAspect =
          signalSeverity(dispatchDecision.targetAspect()) > signalSeverity(safeAspect)
              ? dispatchDecision.targetAspect()
              : safeAspect;
      OptionalDouble nextLimit = safeAuthorityLimit;
      if (nextLimit.isEmpty() && dispatchDecision.targetSpeedBps() > 0.0) {
        nextLimit = OptionalDouble.of(dispatchDecision.targetSpeedBps());
      }
      OptionalLong nextDistance =
          minOptionalLong(
              minOptionalLong(safeDistance, dispatchDecision.distanceToCaution()),
              dispatchDecision.distanceToStop());
      debugLogger.accept(
          "SMART_DISPATCH_ENFORCED train="
              + trainName
              + " mode="
              + mode
              + " action="
              + dispatchDecision.action()
              + " effectClass="
              + dispatchDecision.effectClass()
              + " targetAspect="
              + nextAspect
              + " targetSpeed="
              + (nextLimit.isPresent() ? nextLimit.getAsDouble() : "-"));
      return new SmartSignalDecisionResult(
          nextAspect,
          nextLimit,
          nextDistance,
          dispatchDecision.action(),
          dispatchDecision.riskSource(),
          false,
          smartStopReason(dispatchDecision));
    }
    if (SmartDispatcherModeGate.suppresses(mode, dispatchDecision.effectClass())
        && dispatchDecision.effectClass() != DispatchEffectClass.DIAGNOSTIC_ONLY) {
      traceSmartDispatcherActionSuppressed(
          trainName, mode, dispatchDecision, "effect-class-not-enabled");
    }
    return new SmartSignalDecisionResult(
        safeAspect,
        safeAuthorityLimit,
        safeDistance,
        dispatchDecision.action(),
        dispatchDecision.riskSource(),
        false,
        smartStopReason(dispatchDecision));
  }

  private static boolean invalidatingAuthorityStop(
      AuthorityEnd authorityEnd, DispatchDecision decision) {
    AuthorityEnd safeEnd = authorityEnd == null ? AuthorityEnd.none() : authorityEnd;
    if (!safeEnd.physical()) {
      return false;
    }
    RiskSource source = decision == null ? RiskSource.NONE : decision.riskSource();
    if (source == RiskSource.ARTIFICIAL_WINDOW_LIMIT || recoverableSmartHoldRisk(source)) {
      return false;
    }
    return true;
  }

  private static boolean shouldInvalidateForAuthorityFailure(
      AuthorityEnd authorityEnd, SmartSignalDecisionResult smartDecision) {
    AuthorityEnd safeEnd = authorityEnd == null ? AuthorityEnd.none() : authorityEnd;
    return smartDecision != null
        && smartDecision.isPhysicalAuthorityFailure()
        && safeEnd.physical();
  }

  private static boolean shouldSuppressRecoverableHoldAsAdvisoryOnly(
      SmartSignalDecisionResult smartDecision,
      OccupancyDecision hardDecision,
      SignalAspect aspectBeforeMovementAuthority,
      OccupancyDecision advisoryDecision,
      boolean movementAuthorityStopApplied) {
    if (smartDecision == null
        || !smartDecision.isRecoverableHold()
        || movementAuthorityStopApplied
        || smartDecision.action() != DispatchAction.NO_ACTION
        || smartDecision.riskSource() != RiskSource.SWITCHER_NOT_VERIFIED
        || !smartDecision.stopReason().contains("no-risk-inside-planning-envelope")
        || hardDecision == null
        || !hardDecision.allowed()) {
      return false;
    }
    return recoverableHoldAdvisoryAspect(aspectBeforeMovementAuthority, advisoryDecision)
        != SignalAspect.STOP;
  }

  private static SignalAspect recoverableHoldAdvisoryAspect(
      SignalAspect aspectBeforeMovementAuthority, OccupancyDecision advisoryDecision) {
    if (aspectBeforeMovementAuthority != null
        && aspectBeforeMovementAuthority != SignalAspect.STOP) {
      return aspectBeforeMovementAuthority;
    }
    if (advisoryDecision != null
        && advisoryDecision.allowed()
        && advisoryDecision.signal() != null
        && advisoryDecision.signal() != SignalAspect.STOP) {
      return advisoryDecision.signal();
    }
    return SignalAspect.STOP;
  }

  private static String movementAuthorityDecisionReason(
      String authoritySource, SignalAspect requestedAspect, SignalAspect authorityAspect) {
    String source =
        authoritySource == null || authoritySource.isBlank() ? "unknown" : authoritySource;
    if (authorityAspect == SignalAspect.STOP && requestedAspect != SignalAspect.STOP) {
      return "authority-" + source + "-stop";
    }
    if (signalSeverity(authorityAspect) > signalSeverity(requestedAspect)) {
      return "authority-" + source + "-caution";
    }
    return "authority-" + source + "-clear";
  }

  private static boolean recoverableSmartHoldRisk(RiskSource source) {
    return switch (source == null ? RiskSource.NONE : source) {
      case ACTIVE_OPPOSITE_CONFLICT,
          STALE_RETAIN,
          STALE_QUEUE,
          PROTECTIVE_ONLY_CLAIM,
          SWITCHER_NOT_VERIFIED,
          ROUTE_STOP_OR_TERMINAL,
          ARTIFICIAL_WINDOW_LIMIT -> true;
      default -> false;
    };
  }

  private static String smartStopReason(DispatchDecision decision) {
    if (decision == null) {
      return "smart-dispatch:none";
    }
    return decision.action().name().toLowerCase(Locale.ROOT)
        + ":"
        + decision.riskSource().name().toLowerCase(Locale.ROOT)
        + ":"
        + decision.safetyReason();
  }

  private void traceSmartDispatcherMode(String trainName, SmartDispatcherMode mode, String source) {
    debugLogger.accept(
        "SMART_DISPATCH_MODE train="
            + trainName
            + " mode="
            + (mode == null ? SmartDispatcherMode.OBSERVE_ONLY : mode)
            + " source="
            + source);
  }

  private void traceSmartDispatcherActionObserved(
      String trainName, SmartDispatcherMode mode, DispatchDecision decision) {
    if (decision == null) {
      return;
    }
    debugLogger.accept(
        "SMART_DISPATCH_ACTION_OBSERVED train="
            + trainName
            + " mode="
            + mode
            + " action="
            + decision.action()
            + " effectClass="
            + decision.effectClass());
  }

  private void traceSmartDispatcherActionSuppressed(
      String trainName, SmartDispatcherMode mode, DispatchDecision decision, String reason) {
    if (decision == null) {
      return;
    }
    debugLogger.accept(
        "SMART_DISPATCH_ACTION_SUPPRESSED_BY_MODE train="
            + trainName
            + " mode="
            + mode
            + " action="
            + decision.action()
            + " effectClass="
            + decision.effectClass()
            + " reason="
            + (reason == null || reason.isBlank() ? "mode-gate" : reason));
  }

  private static boolean shouldApplySmartRuntimeOverride(RiskSource source) {
    if (source == null) {
      return false;
    }
    return switch (source) {
      case EDGE_SPEED_DROP, STATION_STOP -> false;
      default -> true;
    };
  }

  private ForwardSignalRiskSnapshot buildForwardSignalRiskSnapshot(
      String trainName,
      OccupancyRequest request,
      OccupancyDecision decision,
      SignalLookahead.LookaheadResult lookahead,
      AuthorityEnd authorityEnd,
      boolean stopAtNextWaypoint) {
    OptionalLong blockerDistance =
        lookahead == null ? OptionalLong.empty() : lookahead.distanceToBlocker();
    OptionalLong approachDistance =
        lookahead == null ? OptionalLong.empty() : lookahead.distanceToApproach();
    OptionalLong speedDropDistance = nearestEdgeSpeedDropDistance(lookahead);
    OccupancyClaim blocker = primaryBlocker(decision);
    if (blocker != null) {
      RiskFreshness freshness = freshnessForClaim(blocker);
      RiskSource source = riskSourceForBlocker(request, blocker);
      traceForwardRiskBlockerDetail(
          trainName, request, decision, blocker, source, freshness, blockerDistance, authorityEnd);
      return new ForwardSignalRiskSnapshot(
          trainName,
          freshness == RiskFreshness.LIVE ? blockerDistance : OptionalLong.empty(),
          freshness == RiskFreshness.LIVE ? blockerDistance : OptionalLong.empty(),
          freshness == RiskFreshness.LIVE ? blockerDistance : OptionalLong.empty(),
          speedDropDistance,
          OptionalLong.empty(),
          OptionalLong.empty(),
          isSingleConflict(blocker) ? blockerDistance : OptionalLong.empty(),
          isSwitcherConflict(blocker) ? blockerDistance : OptionalLong.empty(),
          OptionalLong.empty(),
          source,
          freshness,
          blocker.trainName(),
          blocker.resource() == null ? "-" : blocker.resource().key(),
          decision != null && decision.conflictRelease(),
          freshness != RiskFreshness.LIVE,
          true,
          freshness != RiskFreshness.LIVE,
          false);
    }
    AuthorityEnd safeEnd = authorityEnd == null ? AuthorityEnd.none() : authorityEnd;
    if (safeEnd.reason() == AuthorityEndReason.ARTIFICIAL_WINDOW_LIMIT) {
      return new ForwardSignalRiskSnapshot(
          trainName,
          OptionalLong.empty(),
          OptionalLong.empty(),
          OptionalLong.empty(),
          speedDropDistance,
          OptionalLong.empty(),
          OptionalLong.empty(),
          OptionalLong.empty(),
          OptionalLong.empty(),
          safeEnd.distanceBlocks(),
          RiskSource.ARTIFICIAL_WINDOW_LIMIT,
          RiskFreshness.UNKNOWN,
          "-",
          safeEnd.resource(),
          false,
          false,
          true,
          false,
          false);
    }
    if (safeEnd.physical() && safeEnd.distanceBlocks().isPresent()) {
      RiskSource source = riskSourceForAuthorityEnd(safeEnd.reason());
      return new ForwardSignalRiskSnapshot(
          trainName,
          OptionalLong.empty(),
          safeEnd.distanceBlocks(),
          safeEnd.distanceBlocks(),
          speedDropDistance,
          stopAtNextWaypoint ? approachDistance : OptionalLong.empty(),
          source == RiskSource.TERMINAL_STOP ? safeEnd.distanceBlocks() : OptionalLong.empty(),
          source == RiskSource.SINGLE_EXIT_NOT_VERIFIED
              ? safeEnd.distanceBlocks()
              : OptionalLong.empty(),
          OptionalLong.empty(),
          safeEnd.distanceBlocks(),
          source,
          RiskFreshness.LIVE,
          "-",
          safeEnd.resource(),
          false,
          false,
          true,
          false,
          false);
    }
    if (speedDropDistance.isPresent()) {
      return new ForwardSignalRiskSnapshot(
          trainName,
          OptionalLong.empty(),
          speedDropDistance,
          OptionalLong.empty(),
          speedDropDistance,
          OptionalLong.empty(),
          OptionalLong.empty(),
          OptionalLong.empty(),
          OptionalLong.empty(),
          OptionalLong.empty(),
          RiskSource.EDGE_SPEED_DROP,
          RiskFreshness.LIVE,
          "-",
          "-",
          false,
          false,
          true,
          false,
          false);
    }
    if (stopAtNextWaypoint && approachDistance.isPresent()) {
      return new ForwardSignalRiskSnapshot(
          trainName,
          OptionalLong.empty(),
          approachDistance,
          approachDistance,
          OptionalLong.empty(),
          approachDistance,
          OptionalLong.empty(),
          OptionalLong.empty(),
          OptionalLong.empty(),
          OptionalLong.empty(),
          RiskSource.STATION_STOP,
          RiskFreshness.LIVE,
          "-",
          "-",
          false,
          false,
          true,
          false,
          false);
    }
    return ForwardSignalRiskSnapshot.none(trainName);
  }

  private void traceForwardRiskBlockerDetail(
      String trainName,
      OccupancyRequest request,
      OccupancyDecision decision,
      OccupancyClaim blocker,
      RiskSource riskSource,
      RiskFreshness freshness,
      OptionalLong blockerDistance,
      AuthorityEnd authorityEnd) {
    if (blocker == null || blocker.resource() == null) {
      return;
    }
    RouteProgressRegistry.RouteProgressEntry blockerProgress =
        progressRegistry.get(blocker.trainName()).orElse(null);
    OccupancyResource resource = blocker.resource();
    debugLogger.accept(
        "SMART_FORWARD_RISK_BLOCKER_DETAIL train="
            + safeTraceValue(trainName)
            + " requestId="
            + requestIdOf(request)
            + " requestInputType="
            + (request == null ? "-" : SignalDecisionInputClassifier.classify(request))
            + " requesterRoute="
            + (request == null ? "-" : request.routeId().map(Object::toString).orElse("-"))
            + " requesterIndex="
            + (request == null
                ? "-"
                : request
                    .directedContext()
                    .map(DirectedTraversalContext::currentIndex)
                    .map(String::valueOf)
                    .orElse("-"))
            + " requesterCurrentNode="
            + (request == null
                ? "-"
                : request
                    .directedContext()
                    .flatMap(DirectedTraversalContext::currentNode)
                    .map(NodeId::value)
                    .orElse("-"))
            + " requesterEffectiveTo="
            + effectiveToText(request, Optional.empty())
            + " requesterDirection="
            + requestedDirectionFor(request, resource)
            + " blockerTrain="
            + safeTraceValue(blocker.trainName())
            + " blockerRoute="
            + blocker.routeId().map(Object::toString).orElse("-")
            + " blockerIndex="
            + (blockerProgress == null ? "-" : blockerProgress.currentIndex())
            + " blockerCurrentNode="
            + (blockerProgress == null
                ? "-"
                : blockerProgress.lastPassedGraphNode().map(NodeId::value).orElse("-"))
            + " blockerEffectiveTo="
            + (blockerProgress == null
                ? "-"
                : blockerProgress.nextTarget().map(NodeId::value).orElse("-"))
            + " blockerRole="
            + blocker.role()
            + " blockerClaimRole="
            + blocker.role()
            + " blockerClaimSource="
            + claimSourceText(blocker.role())
            + " blockerPhysicalFootprint="
            + claimPhysicalFootprintText(blocker.role())
            + " blockerReservedAuthority="
            + claimReservedAuthorityText(blocker.role())
            + " blockerDirection="
            + blocker.corridorDirection().orElse(CorridorDirection.UNKNOWN)
            + " resource="
            + resource
            + " blockerRelation="
            + (request == null ? "-" : BlockerClassifier.classify(request, resource, blocker))
            + " decisionReason="
            + (decision == null ? "-" : decision.reason())
            + " switcherVerificationState="
            + switcherVerificationState(resource, riskSource)
            + " physicalSwitchState=unknown"
            + " requiredSwitchState=unknown"
            + " conflictRelease="
            + (decision != null && decision.conflictRelease())
            + " riskSource="
            + (riskSource == null ? RiskSource.NONE : riskSource)
            + " riskFreshness="
            + (freshness == null ? RiskFreshness.UNKNOWN : freshness)
            + " distanceToBlocker="
            + formatOptionalLong(blockerDistance)
            + " authorityEndReason="
            + (authorityEnd == null ? "-" : authorityEnd.reason())
            + " authorityEndResource="
            + (authorityEnd == null ? "-" : authorityEnd.resource()));
  }

  private static String switcherVerificationState(
      OccupancyResource resource, RiskSource riskSource) {
    if (resource == null
        || resource.kind() != ResourceKind.CONFLICT
        || !resource.key().toLowerCase(Locale.ROOT).startsWith("switcher:")) {
      return "-";
    }
    if (riskSource == RiskSource.SWITCHER_NOT_VERIFIED) {
      return "NOT_VERIFIED";
    }
    return "CLAIM_BLOCKER";
  }

  private static String claimSourceText(ClaimRole role) {
    return role == null ? "unknown" : role.name().toLowerCase(Locale.ROOT);
  }

  private static String claimPhysicalFootprintText(ClaimRole role) {
    if (role == ClaimRole.PHYSICAL_FOOTPRINT) {
      return "true";
    }
    if (role == ClaimRole.UNLOCK_RESERVATION
        || role == ClaimRole.QUEUE_POSITION
        || role == ClaimRole.LOOKAHEAD_PREVIEW) {
      return "false";
    }
    return "unknown";
  }

  private static String claimReservedAuthorityText(ClaimRole role) {
    if (role == ClaimRole.UNLOCK_RESERVATION) {
      return "true";
    }
    if (role == ClaimRole.QUEUE_POSITION || role == ClaimRole.LOOKAHEAD_PREVIEW) {
      return "false";
    }
    return "unknown";
  }

  private static OccupancyClaim primaryBlocker(OccupancyDecision decision) {
    if (decision == null || decision.blockers().isEmpty()) {
      return null;
    }
    return decision.blockers().stream()
        .filter(Objects::nonNull)
        .sorted(
            java.util.Comparator.comparing(
                    (OccupancyClaim claim) ->
                        claim.resource() == null ? "" : claim.resource().kind().name())
                .thenComparing(claim -> claim.resource() == null ? "" : claim.resource().key())
                .thenComparing(OccupancyClaim::trainName, String.CASE_INSENSITIVE_ORDER))
        .findFirst()
        .orElse(null);
  }

  private static RiskFreshness freshnessForClaim(OccupancyClaim claim) {
    if (claim == null || claim.role() == null) {
      return RiskFreshness.UNKNOWN;
    }
    if (claim.role() == ClaimRole.MOVEMENT_REQUIRED
        || claim.role() == ClaimRole.PHYSICAL_FOOTPRINT) {
      return RiskFreshness.LIVE;
    }
    if (claim.role() == ClaimRole.PROTECTIVE_RETAIN || claim.role() == ClaimRole.HOLD_ONLY) {
      return RiskFreshness.PROTECTIVE_ONLY;
    }
    return RiskFreshness.STALE;
  }

  private static RiskSource riskSourceForBlocker(OccupancyClaim claim) {
    if (claim == null || claim.resource() == null) {
      return RiskSource.HARD_BLOCKER;
    }
    if (claim.role() == ClaimRole.PHYSICAL_FOOTPRINT) {
      return RiskSource.HARD_BLOCKER;
    }
    if (freshnessForClaim(claim) == RiskFreshness.PROTECTIVE_ONLY) {
      return RiskSource.PROTECTIVE_ONLY_CLAIM;
    }
    if (claim.role() == ClaimRole.QUEUE_POSITION) {
      return RiskSource.STALE_QUEUE;
    }
    if (isSingleConflict(claim)) {
      return RiskSource.ACTIVE_OPPOSITE_CONFLICT;
    }
    if (isSwitcherConflict(claim)) {
      return RiskSource.SWITCHER_NOT_VERIFIED;
    }
    return RiskSource.HARD_BLOCKER;
  }

  private RiskSource riskSourceForBlocker(OccupancyRequest request, OccupancyClaim claim) {
    RiskSource source = riskSourceForBlocker(claim);
    if (isSameDirectionFollowRisk(request, claim)) {
      return RiskSource.SAME_DIRECTION_FOLLOW;
    }
    return source;
  }

  private boolean isSameDirectionFollowRisk(OccupancyRequest request, OccupancyClaim claim) {
    return request != null
        && claim != null
        && isSameDirectionFollowConflict(claim.resource())
        && occupancyManager.isProvenSameDirectionFollower(
            request,
            claim.resource(),
            claim.trainName(),
            provenSameRouteLeader(request, claim.trainName()));
  }

  /**
   * 用运行时 route 进度证明 blocker 是同一交路上的前车。
   *
   * <p>该证明只作为抽象 single/switcher 冲突的同向跟驰证据，不直接放行物理 NODE/EDGE。缺少任一进度、不同 route、blocker
   * 未在前方，或当前路径/route 定义存在折返不确定性时均 fail-closed。
   */
  private boolean provenSameRouteLeader(OccupancyRequest request, String blockerTrain) {
    if (request == null) {
      return false;
    }
    if (request.directedContext().map(RuntimeDispatchService::hasTurnback).orElse(false)) {
      return false;
    }
    return provenSameRouteLeader(request.trainName(), blockerTrain);
  }

  private boolean provenSameRouteLeader(String requesterTrain, String blockerTrain) {
    if (requesterTrain == null
        || requesterTrain.isBlank()
        || blockerTrain == null
        || blockerTrain.isBlank()
        || TrainNameNormalizer.sameLogicalTrain(requesterTrain, blockerTrain)
        || progressRegistry == null) {
      return false;
    }
    Optional<RouteProgressRegistry.RouteProgressEntry> requesterEntryOpt =
        progressRegistry.get(requesterTrain);
    Optional<RouteProgressRegistry.RouteProgressEntry> blockerEntryOpt =
        progressRegistry.get(blockerTrain);
    if (requesterEntryOpt.isEmpty() || blockerEntryOpt.isEmpty()) {
      return false;
    }
    RouteProgressRegistry.RouteProgressEntry requesterEntry = requesterEntryOpt.get();
    RouteProgressRegistry.RouteProgressEntry blockerEntry = blockerEntryOpt.get();
    if (requesterEntry.routeId() == null
        || blockerEntry.routeId() == null
        || !requesterEntry.routeId().equals(blockerEntry.routeId())
        || blockerEntry.currentIndex() <= requesterEntry.currentIndex()) {
      return false;
    }
    Optional<RouteDefinition> route = routeDefinitionForProgress(requesterEntry);
    return route.isPresent() && !routeContainsTurnbackBoundary(route.get());
  }

  private static boolean hasTurnback(DirectedTraversalContext context) {
    if (context == null || context.directedEdges().isEmpty()) {
      return false;
    }
    List<DirectedTraversalContext.DirectedEdge> edges = context.directedEdges();
    for (int i = 1; i < edges.size(); i++) {
      DirectedTraversalContext.DirectedEdge previous = edges.get(i - 1);
      DirectedTraversalContext.DirectedEdge current = edges.get(i);
      if (previous.fromNode().equals(current.toNode())
          && previous.toNode().equals(current.fromNode())) {
        return true;
      }
    }
    return false;
  }

  private static boolean routeContainsTurnbackBoundary(RouteDefinition route) {
    if (route == null || route.waypoints().isEmpty()) {
      return true;
    }
    Set<NodeId> seen = new HashSet<>();
    for (NodeId waypoint : route.waypoints()) {
      if (waypoint == null || !seen.add(waypoint)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isSameDirectionFollowConflict(OccupancyResource resource) {
    if (resource == null || resource.kind() != ResourceKind.CONFLICT) {
      return false;
    }
    String key = resource.key();
    return key.startsWith("switcher:") || (key.startsWith("single:") && !key.contains(":cycle:"));
  }

  private static RiskSource riskSourceForAuthorityEnd(AuthorityEndReason reason) {
    if (reason == null) {
      return RiskSource.NONE;
    }
    return switch (reason) {
      case HARD_BLOCKER -> RiskSource.HARD_BLOCKER;
      case ROUTE_STOP_OR_TERMINAL -> RiskSource.ROUTE_STOP_OR_TERMINAL;
      case DWELL_OR_STATION_STOP -> RiskSource.DWELL_STOP;
      case SINGLE_EXIT_NOT_VERIFIED -> RiskSource.SINGLE_EXIT_NOT_VERIFIED;
      case MAX_AUTHORITY_CAP_REACHED -> RiskSource.MAX_AUTHORITY_CAP_REACHED;
      case ARTIFICIAL_WINDOW_LIMIT -> RiskSource.ARTIFICIAL_WINDOW_LIMIT;
      case NONE -> RiskSource.NONE;
    };
  }

  private static boolean isSingleConflict(OccupancyClaim claim) {
    return claim != null
        && claim.resource() != null
        && claim.resource().kind() == ResourceKind.CONFLICT
        && claim.resource().key().startsWith("single:");
  }

  private static boolean isSwitcherConflict(OccupancyClaim claim) {
    return claim != null
        && claim.resource() != null
        && claim.resource().kind() == ResourceKind.CONFLICT
        && claim.resource().key().startsWith("switcher:");
  }

  private static OptionalLong nearestEdgeSpeedDropDistance(
      SignalLookahead.LookaheadResult lookahead) {
    if (lookahead == null || lookahead.edgeSpeedConstraints().isEmpty()) {
      return OptionalLong.empty();
    }
    long best = Long.MAX_VALUE;
    for (SignalLookahead.EdgeSpeedConstraint constraint : lookahead.edgeSpeedConstraints()) {
      if (constraint != null && constraint.distanceBlocks() > 0L) {
        best = Math.min(best, constraint.distanceBlocks());
      }
    }
    return best == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(best);
  }

  /**
   * 根据许可等级与全部附加限制计算目标速度（blocks/s）。
   *
   * <p>PROCEED 基准速度取边限速（若有效），否则回退 defaultSpeed；警示信号使用连通分量的 caution
   * 速度上限（无覆盖时回退为配置默认值）。后续再依次应用进站/停靠点限速、移动授权限速与前方低限速边前瞻，并记录最终限制来源。
   */
  private TargetSpeedDecision resolveTargetSpeedDecision(
      UUID worldId,
      SignalAspect aspect,
      NodeId nextNode,
      double edgeLimit,
      double decelBps2,
      SignalLookahead.LookaheadResult lookahead,
      ControlSpeedOverrides speedOverrides) {
    double defaultSpeed = configManager.current().graphSettings().defaultSpeedBlocksPerSecond();
    double proceedBase = edgeLimit > 0.0 ? edgeLimit : defaultSpeed;
    String limiterSource = edgeLimit > 0.0 ? "edge_limit" : "default_speed";
    CautionSpeedDecision caution = new CautionSpeedDecision(0.0, "none");
    double base = 0.0;
    switch (aspect) {
      case PROCEED -> base = proceedBase;
      case PROCEED_WITH_CAUTION, CAUTION -> {
        caution = resolveCautionSpeedDecision(worldId, nextNode);
        base = caution.speedBps();
        limiterSource =
            "component".equals(caution.source()) ? "component_caution" : "config_caution";
      }
      case STOP -> {
        base = 0.0;
        limiterSource = "stop";
      }
    }
    double target = base;
    OptionalDouble approachLimitBps = OptionalDouble.empty();
    ControlSpeedOverrides overrides =
        speedOverrides != null ? speedOverrides : ControlSpeedOverrides.empty();
    if (overrides.approachLimitBps().isPresent()) {
      double override = overrides.approachLimitBps().getAsDouble();
      if (Double.isFinite(override) && override > 0.0) {
        ConfigManager.RuntimeSettings runtime = configManager.current().runtimeSettings();
        double approachEnvelope =
            RuntimeTrainController.resolveApproachSpeedEnvelope(
                target,
                override,
                decelBps2,
                overrides.approachControl().distanceBlocks(),
                overrides.approachControl().targetEdgeDistanceBlocks(),
                overrides.approachControl().edgeCount(),
                runtime,
                APPROACH_PREVIEW_DISTANCE_BLOCKS);
        approachLimitBps =
            OptionalDouble.of(
                approachLimitBps.isPresent()
                    ? Math.min(approachLimitBps.getAsDouble(), override)
                    : override);
        if (approachEnvelope < target) {
          target = approachEnvelope;
          limiterSource =
              resolveApproachLimiterSource(overrides.approachControl(), override, target);
        }
      }
    }
    if (overrides.movementAuthorityLimitBps().isPresent()) {
      double authorityLimit = overrides.movementAuthorityLimitBps().getAsDouble();
      if (Double.isFinite(authorityLimit) && authorityLimit >= 0.0 && authorityLimit < target) {
        target = authorityLimit;
        limiterSource = "movement_authority";
      }
    }
    OptionalDouble edgeLookaheadLimit = OptionalDouble.empty();
    if (lookahead != null
        && !lookahead.edgeSpeedConstraints().isEmpty()
        && configManager.current().runtimeSettings().speedCurveEnabled()) {
      double lookedAhead =
          applyEdgeSpeedLookahead(target, decelBps2, lookahead.edgeSpeedConstraints());
      if (lookedAhead < target - 1.0e-6) {
        edgeLookaheadLimit = OptionalDouble.of(lookedAhead);
        target = lookedAhead;
        limiterSource = "edge_speed_lookahead";
      }
    }
    return new TargetSpeedDecision(
        edgeLimit,
        base,
        caution.source(),
        approachLimitBps,
        overrides.movementAuthorityLimitBps(),
        edgeLookaheadLimit,
        target,
        limiterSource);
  }

  private static String resolveApproachLimiterSource(
      ApproachControl approachControl, double configuredLimitBps, double appliedLimitBps) {
    boolean atConfiguredLimit = appliedLimitBps <= configuredLimitBps + 1.0e-6;
    if (!atConfiguredLimit) {
      return "approach_curve";
    }
    return switch (approachControl.kind()) {
      case "station" -> "approach";
      case "depot" -> "depot_approach";
      case "stop_waypoint" -> "stop_waypoint_approach";
      default -> "approach";
    };
  }

  /**
   * 按展开后的 route 路径解析当前是否进入 approach 窗口。
   *
   * <p>Route 往往只写停靠点，不写沿途 throat/switcher。本方法从当前 route index 向前找到下一处非 PASS stop，再按调度图最短路展开 route
   * 片段，计算到 station/depot/STOP waypoint 的真实距离。只有距离或边数进入配置窗口后，才返回有效 approach 限速。
   */
  private ApproachControl resolveApproachControl(
      RailGraph graph,
      RouteDefinition route,
      List<NodeId> effectiveNodes,
      int currentIndex,
      NodeId currentNode) {
    if (graph == null
        || route == null
        || effectiveNodes == null
        || effectiveNodes.isEmpty()
        || currentNode == null
        || currentIndex < 0
        || currentIndex >= effectiveNodes.size() - 1) {
      return ApproachControl.none();
    }
    Optional<IndexedRouteStop> stopOpt =
        findNextStoppingRouteStop(route, effectiveNodes, currentIndex);
    if (stopOpt.isEmpty()) {
      return ApproachControl.none();
    }
    IndexedRouteStop indexedStop = stopOpt.get();
    Optional<ApproachTarget> targetOpt = resolveApproachTarget(graph, indexedStop);
    if (targetOpt.isEmpty()) {
      return ApproachControl.none();
    }
    Optional<ExpandedRoutePath> pathOpt =
        expandRoutePath(graph, effectiveNodes, currentIndex, indexedStop.index());
    if (pathOpt.isEmpty()) {
      return ApproachControl.none();
    }
    ApproachTarget target = targetOpt.get();
    Optional<ApproachTrigger> triggerOpt = findApproachTrigger(graph, pathOpt.get(), target);
    if (triggerOpt.isEmpty()) {
      return ApproachControl.none();
    }
    ConfigManager.RuntimeSettings runtime = configManager.current().runtimeSettings();
    ApproachTrigger trigger = triggerOpt.get();
    ApproachWindowState windowState =
        resolveApproachWindowState(runtime, trigger.distanceBlocks(), trigger.edgeCount());
    if (!windowState.active()) {
      return ApproachControl.none();
    }
    double limit =
        "depot".equals(target.kind())
            ? runtime.approachDepotSpeedBps()
            : runtime.approachSpeedBps();
    long targetEdgeDistance = trigger.targetEdgeDistanceBlocks(runtime.approachTargetEdges());
    String reason =
        "route_stop:"
            + target.passType().name()
            + ";"
            + trigger.reason()
            + ";distance="
            + trigger.distanceBlocks()
            + ";edges="
            + trigger.edgeCount()
            + ";target_edges="
            + runtime.approachTargetEdges()
            + ";target_edge_distance="
            + targetEdgeDistance
            + ";preview="
            + windowState.preview()
            + ";preview_distance="
            + formatApproachDouble(windowState.previewDistanceBlocks())
            + ";distance_to_approach_boundary="
            + formatApproachDouble(windowState.distanceToBoundaryBlocks())
            + ";approach_ratio="
            + formatApproachDouble(windowState.ratio())
            + ";approach_limit="
            + formatApproachDouble(limit);
    return approachWithLimit(
        trigger.node(),
        target.kind(),
        reason,
        limit,
        OptionalLong.of(trigger.distanceBlocks()),
        OptionalLong.of(targetEdgeDistance),
        trigger.edgeCount());
  }

  private Optional<IndexedRouteStop> findNextStoppingRouteStop(
      RouteDefinition route, List<NodeId> effectiveNodes, int currentIndex) {
    if (route == null
        || effectiveNodes == null
        || currentIndex < 0
        || currentIndex >= effectiveNodes.size() - 1) {
      return Optional.empty();
    }
    for (int i = currentIndex + 1; i < effectiveNodes.size(); i++) {
      Optional<RouteStop> stopOpt = routeDefinitions.findStop(route.id(), i);
      if (stopOpt.isEmpty() || stopOpt.get().passType() == RouteStopPassType.PASS) {
        continue;
      }
      return Optional.of(new IndexedRouteStop(i, effectiveNodes.get(i), stopOpt.get()));
    }
    return Optional.empty();
  }

  private Optional<ApproachTarget> resolveApproachTarget(
      RailGraph graph, IndexedRouteStop indexedStop) {
    if (graph == null || indexedStop == null || indexedStop.node() == null) {
      return Optional.empty();
    }
    NodeId node = indexedStop.node();
    RouteStop stop = indexedStop.stop();
    if (stop == null || stop.passType() == RouteStopPassType.PASS) {
      return Optional.empty();
    }
    Optional<WaypointMetadata> metadataOpt = resolveWaypointMetadata(graph, node);
    Optional<ApproachNodeKey> keyOpt = metadataOpt.flatMap(RuntimeDispatchService::approachKey);
    if (isStationApproachNode(graph, node, keyOpt)) {
      return Optional.of(new ApproachTarget(node, "station", stop.passType(), keyOpt));
    }
    if (isDepotApproachNode(graph, node, keyOpt)) {
      return Optional.of(new ApproachTarget(node, "depot", stop.passType(), keyOpt));
    }
    if (shouldStopAtWaypoint(node, stop)) {
      return Optional.of(
          new ApproachTarget(node, "stop_waypoint", stop.passType(), Optional.empty()));
    }
    return Optional.empty();
  }

  private Optional<ExpandedRoutePath> expandRoutePath(
      RailGraph graph, List<NodeId> nodes, int fromIndex, int toIndex) {
    if (graph == null
        || nodes == null
        || fromIndex < 0
        || toIndex <= fromIndex
        || toIndex >= nodes.size()) {
      return Optional.empty();
    }
    List<NodeId> expandedNodes = new ArrayList<>();
    List<RailEdge> expandedEdges = new ArrayList<>();
    expandedNodes.add(nodes.get(fromIndex));
    for (int i = fromIndex; i < toIndex; i++) {
      NodeId from = nodes.get(i);
      NodeId to = nodes.get(i + 1);
      if (from == null || to == null) {
        return Optional.empty();
      }
      Optional<RailEdge> directEdge = findEdge(graph, from, to);
      if (directEdge.isPresent()) {
        expandedEdges.add(directEdge.get());
        expandedNodes.add(to);
        continue;
      }
      Optional<RailGraphPath> pathOpt =
          pathFinder.shortestPath(graph, from, to, RailGraphPathFinder.Options.shortestDistance());
      if (pathOpt.isEmpty()
          || pathOpt.get().nodes().size() < 2
          || pathOpt.get().edges().isEmpty()) {
        return Optional.empty();
      }
      RailGraphPath segment = pathOpt.get();
      expandedEdges.addAll(segment.edges());
      List<NodeId> segmentNodes = segment.nodes();
      for (int j = 1; j < segmentNodes.size(); j++) {
        expandedNodes.add(segmentNodes.get(j));
      }
    }
    if (expandedNodes.size() < 2 || expandedEdges.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new ExpandedRoutePath(expandedNodes, expandedEdges));
  }

  private Optional<ApproachTrigger> findApproachTrigger(
      RailGraph graph, ExpandedRoutePath path, ApproachTarget target) {
    if (graph == null || path == null || target == null || path.nodes().size() < 2) {
      return Optional.empty();
    }
    long distance = 0L;
    int edgeCount = 0;
    List<Long> edgeLengths = new ArrayList<>();
    for (int i = 1; i < path.nodes().size(); i++) {
      if (i - 1 < path.edges().size()) {
        RailEdge previous = path.edges().get(i - 1);
        long edgeLength = previous == null ? 0L : Math.max(0, previous.lengthBlocks());
        distance += edgeLength;
        edgeLengths.add(edgeLength);
        edgeCount++;
      }
      NodeId node = path.nodes().get(i);
      if (!matchesApproachTarget(graph, node, target)) {
        continue;
      }
      return Optional.of(
          new ApproachTrigger(
              node,
              distance,
              edgeCount,
              edgeLengths,
              "expanded_route:" + target.stopNode().value()));
    }
    return Optional.empty();
  }

  private boolean matchesApproachTarget(RailGraph graph, NodeId node, ApproachTarget target) {
    if (graph == null || node == null || target == null) {
      return false;
    }
    if ("stop_waypoint".equals(target.kind())) {
      return node.equals(target.stopNode());
    }
    if (target.key().isEmpty()) {
      return node.equals(target.stopNode());
    }
    if ("station".equals(target.kind())) {
      return isStationApproachNode(graph, node, target.key());
    }
    if ("depot".equals(target.kind())) {
      return isDepotApproachNode(graph, node, target.key());
    }
    return false;
  }

  private static ApproachWindowState resolveApproachWindowState(
      ConfigManager.RuntimeSettings runtime, long distanceBlocks, int edgeCount) {
    if (runtime == null) {
      return ApproachWindowState.inactive(APPROACH_PREVIEW_DISTANCE_BLOCKS);
    }
    if (withinApproachWindow(runtime, distanceBlocks, edgeCount)) {
      return new ApproachWindowState(
          true,
          false,
          APPROACH_PREVIEW_DISTANCE_BLOCKS,
          approachDistanceToBoundary(runtime.approachWindowBlocks(), distanceBlocks),
          1.0);
    }
    double ratio =
        RuntimeTrainController.approachPreviewRatio(
            runtime.approachWindowBlocks(), APPROACH_PREVIEW_DISTANCE_BLOCKS, distanceBlocks);
    if (ratio <= 0.0) {
      return ApproachWindowState.inactive(APPROACH_PREVIEW_DISTANCE_BLOCKS);
    }
    return new ApproachWindowState(
        true,
        true,
        APPROACH_PREVIEW_DISTANCE_BLOCKS,
        approachDistanceToBoundary(runtime.approachWindowBlocks(), distanceBlocks),
        ratio);
  }

  private static double approachDistanceToBoundary(
      double approachWindowBlocks, long distanceBlocks) {
    if (!Double.isFinite(approachWindowBlocks) || approachWindowBlocks < 0.0) {
      return Double.NaN;
    }
    return Math.max(0.0, distanceBlocks - approachWindowBlocks);
  }

  private static boolean withinApproachWindow(
      ConfigManager.RuntimeSettings runtime, long distanceBlocks, int edgeCount) {
    if (runtime == null) {
      return false;
    }
    double windowBlocks = runtime.approachWindowBlocks();
    if (Double.isFinite(windowBlocks) && windowBlocks > 0.0 && distanceBlocks <= windowBlocks) {
      return true;
    }
    int windowEdges = runtime.approachWindowEdges();
    return windowEdges > 0 && edgeCount >= 0 && edgeCount <= windowEdges;
  }

  private static ApproachControl approachWithLimit(
      NodeId node,
      String kind,
      String reason,
      double limitBps,
      OptionalLong distanceBlocks,
      OptionalLong targetEdgeDistanceBlocks,
      int edgeCount) {
    OptionalDouble limit =
        Double.isFinite(limitBps) && limitBps > 0.0
            ? OptionalDouble.of(limitBps)
            : OptionalDouble.empty();
    return new ApproachControl(
        Optional.of(node),
        kind,
        reason,
        limit,
        distanceBlocks,
        targetEdgeDistanceBlocks,
        edgeCount);
  }

  private CautionSpeedDecision resolveCautionSpeedDecision(UUID worldId, NodeId nodeId) {
    double fallback = configManager.current().runtimeSettings().cautionSpeedBps();
    if (worldId == null || nodeId == null || railGraphService == null) {
      return new CautionSpeedDecision(fallback, "config");
    }
    Optional<String> componentKey = railGraphService.componentKey(worldId, nodeId);
    if (componentKey.isEmpty()) {
      return new CautionSpeedDecision(fallback, "config");
    }
    OptionalDouble componentSpeed =
        railGraphService.componentCautionSpeedBlocksPerSecond(worldId, componentKey.get());
    return componentSpeed.isPresent()
        ? new CautionSpeedDecision(componentSpeed.getAsDouble(), "component")
        : new CautionSpeedDecision(fallback, "config");
  }

  private boolean isStationApproachNode(
      RailGraph graph, NodeId nodeId, Optional<ApproachNodeKey> targetKey) {
    if (nodeId == null) {
      return false;
    }
    Optional<ApproachNodeKey> nodeKey = resolveApproachKey(graph, nodeId);
    if (targetKey != null && targetKey.isPresent()) {
      ApproachNodeKey target = targetKey.get();
      if (nodeKey.map(target::matches).orElse(false) && isStationOrStationThroat(graph, nodeId)) {
        return true;
      }
      return isSwitcherAdjacentTo(graph, nodeId, target);
    }
    return isStationOrStationThroat(graph, nodeId);
  }

  private boolean isDepotApproachNode(
      RailGraph graph, NodeId nodeId, Optional<ApproachNodeKey> targetKey) {
    if (nodeId == null) {
      return false;
    }
    Optional<ApproachNodeKey> nodeKey = resolveApproachKey(graph, nodeId);
    if (targetKey != null && targetKey.isPresent()) {
      ApproachNodeKey target = targetKey.get();
      if (nodeKey.map(target::matches).orElse(false) && isDepotOrDepotThroat(graph, nodeId)) {
        return true;
      }
      return isSwitcherAdjacentTo(graph, nodeId, target);
    }
    return isDepotOrDepotThroat(graph, nodeId);
  }

  private boolean isStationOrStationThroat(RailGraph graph, NodeId nodeId) {
    if (graph != null
        && graph.findNode(nodeId).map(node -> node.type() == NodeType.STATION).orElse(false)) {
      return true;
    }
    if (isStationNode(nodeId)) {
      return true;
    }
    return resolveWaypointMetadata(graph, nodeId)
        .map(metadata -> metadata.kind() == WaypointKind.STATION_THROAT)
        .orElse(false);
  }

  private boolean isDepotOrDepotThroat(RailGraph graph, NodeId nodeId) {
    if (graph != null
        && graph.findNode(nodeId).map(node -> node.type() == NodeType.DEPOT).orElse(false)) {
      return true;
    }
    if (isDepotNode(nodeId)) {
      return true;
    }
    return resolveWaypointMetadata(graph, nodeId)
        .map(metadata -> metadata.kind() == WaypointKind.DEPOT_THROAT)
        .orElse(false);
  }

  private boolean isSwitcherAdjacentTo(RailGraph graph, NodeId nodeId, ApproachNodeKey targetKey) {
    if (graph == null || nodeId == null || targetKey == null || !isSwitcherNode(graph, nodeId)) {
      return false;
    }
    for (RailEdge edge : graph.edgesFrom(nodeId)) {
      if (edge == null) {
        continue;
      }
      NodeId neighbor = nodeId.equals(edge.from()) ? edge.to() : edge.from();
      if (neighbor == null) {
        continue;
      }
      Optional<ApproachNodeKey> neighborKey = resolveApproachKey(graph, neighbor);
      if (neighborKey.map(targetKey::matches).orElse(false)) {
        return true;
      }
    }
    return false;
  }

  private boolean isSwitcherNode(RailGraph graph, NodeId nodeId) {
    if (nodeId == null) {
      return false;
    }
    if (graph != null
        && graph.findNode(nodeId).map(node -> node.type() == NodeType.SWITCHER).orElse(false)) {
      return true;
    }
    Optional<SignNodeDefinition> def =
        signNodeRegistry.findByNodeId(nodeId, null).map(SignNodeRegistry.SignNodeInfo::definition);
    if (def.map(value -> value.nodeType() == NodeType.SWITCHER).orElse(false)) {
      return true;
    }
    return resolveWaypointMetadata(graph, nodeId)
        .map(metadata -> metadata.kind() == WaypointKind.SWITCHER)
        .orElse(false);
  }

  private Optional<ApproachNodeKey> resolveApproachKey(RailGraph graph, NodeId nodeId) {
    return resolveWaypointMetadata(graph, nodeId).flatMap(RuntimeDispatchService::approachKey);
  }

  private Optional<WaypointMetadata> resolveWaypointMetadata(RailGraph graph, NodeId nodeId) {
    if (nodeId == null) {
      return Optional.empty();
    }
    Optional<WaypointMetadata> graphMetadata =
        graph != null
            ? graph.findNode(nodeId).flatMap(node -> node.waypointMetadata())
            : Optional.empty();
    if (graphMetadata.isPresent()) {
      return graphMetadata;
    }
    Optional<WaypointMetadata> registryMetadata =
        signNodeRegistry
            .findByNodeId(nodeId, null)
            .map(SignNodeRegistry.SignNodeInfo::definition)
            .flatMap(SignNodeDefinition::waypointMetadata);
    if (registryMetadata.isPresent()) {
      return registryMetadata;
    }
    return parseWaypointMetadata(nodeId);
  }

  private static Optional<ApproachNodeKey> approachKey(WaypointMetadata metadata) {
    if (metadata == null) {
      return Optional.empty();
    }
    return switch (metadata.kind()) {
      case STATION, STATION_THROAT -> Optional.of(
          new ApproachNodeKey(
              "station", metadata.operator(), metadata.originStation(), metadata.trackNumber()));
      case DEPOT, DEPOT_THROAT -> Optional.of(
          new ApproachNodeKey(
              "depot", metadata.operator(), metadata.originStation(), metadata.trackNumber()));
      default -> Optional.empty();
    };
  }

  private boolean isStationNode(NodeId nodeId) {
    if (nodeId == null) {
      return false;
    }
    Optional<SignNodeDefinition> def =
        signNodeRegistry.findByNodeId(nodeId, null).map(SignNodeRegistry.SignNodeInfo::definition);
    if (def.isEmpty()) {
      return parseWaypointKind(nodeId).map(kind -> kind == WaypointKind.STATION).orElse(false);
    }
    if (def.get().nodeType() == NodeType.STATION) {
      return true;
    }
    return false;
  }

  private boolean isDepotNode(NodeId nodeId) {
    if (nodeId == null) {
      return false;
    }
    Optional<SignNodeDefinition> def =
        signNodeRegistry.findByNodeId(nodeId, null).map(SignNodeRegistry.SignNodeInfo::definition);
    if (def.isEmpty()) {
      return parseWaypointKind(nodeId).map(kind -> kind == WaypointKind.DEPOT).orElse(false);
    }
    if (def.get().nodeType() == NodeType.DEPOT) {
      return true;
    }
    return false;
  }

  private static Optional<WaypointKind> parseWaypointKind(NodeId nodeId) {
    return parseWaypointMetadata(nodeId).map(WaypointMetadata::kind);
  }

  private static Optional<WaypointMetadata> parseWaypointMetadata(NodeId nodeId) {
    if (nodeId == null || nodeId.value() == null) {
      return Optional.empty();
    }
    return SignTextParser.parseWaypointLike(nodeId.value(), NodeType.WAYPOINT)
        .flatMap(SignNodeDefinition::waypointMetadata);
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
    return TrainNameNormalizer.normalizeKey(trainName);
  }

  private static String normalizeKeyPart(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * 迁移 DYNAMIC/同站异台的真实节点覆盖。
   *
   * <p>无覆盖是合法 no-op；只有目标列车已经持有另一份覆盖时才拒绝，避免把两列车的 materialized platform 状态合并。
   */
  private boolean migrateEffectiveNodeOverrides(String currentTrainName, String nextTrainName) {
    String currentKey = normalizeTrainKey(currentTrainName);
    String nextKey = normalizeTrainKey(nextTrainName);
    if (currentKey.isEmpty() || nextKey.isEmpty()) {
      return false;
    }
    if (currentKey.equals(nextKey)) {
      return true;
    }
    synchronized (effectiveNodeOverridesLock) {
      java.util.concurrent.ConcurrentMap<Integer, EffectiveNodeOverride> current =
          effectiveNodeOverrides.remove(currentKey);
      if (current == null) {
        return true;
      }
      java.util.concurrent.ConcurrentMap<Integer, EffectiveNodeOverride> collision =
          effectiveNodeOverrides.putIfAbsent(nextKey, current);
      if (collision == null) {
        return true;
      }
      effectiveNodeOverrides.putIfAbsent(currentKey, current);
      return false;
    }
  }

  /**
   * 在 handoff 前拍摄当前列车的可保留物理 footprint。
   *
   * <p>必须在原子替换前取样：与新窗口重叠的旧资源会在账本中变为 {@link ClaimRole#MOVEMENT_REQUIRED}。已有 {@link
   * ClaimRole#PHYSICAL_FOOTPRINT} 已由先前 guard epoch 负责，不能再次装入新 epoch；否则后一次证据缺失会反向污染本可独立清界的旧资源。
   */
  Set<OccupancyResource> snapshotTurnbackFootprintResources(String trainName) {
    if (occupancyManager == null || trainName == null || trainName.isBlank()) {
      return Set.of();
    }
    Set<OccupancyResource> resources = new LinkedHashSet<>();
    for (OccupancyClaim claim : occupancyManager.snapshotClaims()) {
      if (claim == null
          || claim.resource() == null
          || !TrainNameNormalizer.sameLogicalTrain(claim.trainName(), trainName)
          || !isRetainableTurnbackFootprintRole(claim.role())) {
        continue;
      }
      resources.add(claim.resource());
    }
    return Set.copyOf(resources);
  }

  private static boolean isRetainableTurnbackFootprintRole(ClaimRole role) {
    return role == ClaimRole.MOVEMENT_REQUIRED
        || role == ClaimRole.PROTECTIVE_RETAIN
        || role == ClaimRole.HOLD_ONLY;
  }

  /** 把 handoff 前拍摄的旧车体占用登记为只允许由真实推进释放的保护。 */
  void registerTurnbackFootprintGuard(
      RuntimeTrainHandle train,
      String trainName,
      NodeId handoffNode,
      Set<OccupancyResource> retainedFootprint,
      List<TurnbackFootprintGuardRegistry.ForwardPathEdge> forwardPath,
      int rearGuardEdges) {
    if (occupancyManager == null || train == null || retainedFootprint == null) {
      return;
    }
    turnbackFootprintGuards.register(
        trainName,
        handoffNode,
        retainedFootprint,
        forwardPath == null ? List.of() : List.copyOf(forwardPath),
        train.estimatedTrainLengthBlocks(),
        rearGuardEdges);
  }

  /**
   * 展开折返后的真实前进路径，供列尾清空按方块距离取证。
   *
   * <p>先采用本次授权已经选定的路径，再沿后续 route waypoint 逐段追加最短路。授权窗口本身可能只到站外清出点，若直接拿它估算长编组列尾，安全实现只能永久
   * retain；追加后续路线可在列车真正前进足够距离后正常释放旧进路。
   */
  List<TurnbackFootprintGuardRegistry.ForwardPathEdge> resolveTurnbackForwardPath(
      RailGraph graph,
      OccupancyRequestContext context,
      List<NodeId> effectiveNodes,
      int startIndex) {
    if (graph == null || context == null || context.pathNodes().size() < 2) {
      return List.of();
    }
    List<TurnbackFootprintGuardRegistry.ForwardPathEdge> result = new ArrayList<>();
    List<NodeId> authorityNodes = context.pathNodes();
    appendTurnbackPathEdges(graph, authorityNodes, result);
    NodeId cursor = authorityNodes.get(authorityNodes.size() - 1);
    if (effectiveNodes == null || startIndex < 0 || startIndex >= effectiveNodes.size()) {
      return List.copyOf(result);
    }
    int nextRouteIndex = startIndex + 1;
    for (NodeId authorityNode : authorityNodes) {
      if (nextRouteIndex < effectiveNodes.size()
          && Objects.equals(authorityNode, effectiveNodes.get(nextRouteIndex))) {
        nextRouteIndex++;
      }
    }
    for (int routeIndex = nextRouteIndex; routeIndex < effectiveNodes.size(); routeIndex++) {
      NodeId target = effectiveNodes.get(routeIndex);
      if (target == null || target.equals(cursor)) {
        continue;
      }
      Optional<RailGraphPath> path =
          pathFinder.shortestPath(
              graph, cursor, target, RailGraphPathFinder.Options.shortestDistance());
      if (path.isEmpty() || path.get().nodes().size() < 2) {
        break;
      }
      appendTurnbackPathEdges(graph, path.get().nodes(), result);
      cursor = target;
    }
    return List.copyOf(result);
  }

  private void appendTurnbackPathEdges(
      RailGraph graph,
      List<NodeId> nodes,
      List<TurnbackFootprintGuardRegistry.ForwardPathEdge> target) {
    if (graph == null || nodes == null || target == null || nodes.size() < 2) {
      return;
    }
    for (int index = 0; index + 1 < nodes.size(); index++) {
      NodeId from = nodes.get(index);
      NodeId to = nodes.get(index + 1);
      if (from == null || to == null) {
        return;
      }
      Optional<RailEdge> edge = findEdge(graph, from, to);
      if (edge.isEmpty()) {
        return;
      }
      target.add(
          new TurnbackFootprintGuardRegistry.ForwardPathEdge(
              from, to, Math.max(1, edge.get().lengthBlocks())));
    }
  }

  /** 只在真实节点事件跨过列尾保护窗口后，按物理 footprint 角色释放折返旧进路。 */
  void observeTurnbackFootprintProgress(String trainName, NodeId observedNode) {
    if (occupancyManager == null) {
      return;
    }
    turnbackFootprintGuards
        .observeProgress(trainName, observedNode)
        .ifPresent(
            release -> {
              int released =
                  occupancyManager.releaseResourcesByTrainAndRole(
                      trainName, List.copyOf(release.resources()), ClaimRole.PHYSICAL_FOOTPRINT);
              debugLogger.accept(
                  "Layover 折返列尾已离开旧进路: train="
                      + trainName
                      + " node="
                      + observedNode.value()
                      + " resources="
                      + release.resources().size()
                      + " released="
                      + released);
            });
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
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return;
    }
    EffectiveNodeOverride previous =
        effectiveNodeOverrides
            .computeIfAbsent(key, k -> new java.util.concurrent.ConcurrentHashMap<>())
            .put(index, createEffectiveNodeOverride(route, index, effectiveNode));
    if (effectiveNode.equals(declared) && previous != null && !previous.node().equals(declared)) {
      debugLogger.accept(
          "SMART_EFFECTIVE_NODE_OVERRIDE_CLEARED train="
              + trainName
              + " index="
              + index
              + " staleNode="
              + previous.node().value()
              + " declaredNode="
              + declared.value()
              + " replacement=observed-declared-marker");
    }
  }

  /**
   * 仅在尚未提交任何 route 进度的 CRET 新车上，从持久 tag 恢复实际 Depot 股道。
   *
   * <p>{@code FTA_DEPOT_ID} 描述的是列车最初生成位置，不是跨交路持久有效的当前位置。折返 handoff 会把 route index 重新置为 0； 若只凭
   * index=0 重放该 tag，插件重载后就会把终点站列车重新锚定到旧车库，并生成横跨整条旧运行腿的虚假 Movement Authority。因此这里必须同时要求首个 RouteStop
   * 带有 CRET 证据，且 spawn-origin lifecycle marker 仍为 pending。正常新车会显式写入 marker；兼容旧版本时，仅当 {@code
   * FTA_RUN_AT} 与首个 route 更新时间属于同一生成时刻、且还没有折返 ticket 时才视为 pending。
   */
  private void restoreSpawnOriginOverrideIfNeeded(
      String trainName, RouteDefinition route, int boundedIndex, TrainProperties properties) {
    if (route == null
        || properties == null
        || route.waypoints().isEmpty()
        || boundedIndex != 0
        || readEffectiveNode(trainName, route, 0).isPresent()
        || !hasPendingSpawnOrigin(properties)) {
      return;
    }
    Optional<RouteStop> firstStop = routeDefinitions.findStop(route.id(), 0);
    if (firstStop
        .flatMap(stop -> SpawnDirectiveParser.findDirectiveTarget(stop, "CRET"))
        .isEmpty()) {
      return;
    }
    Optional<String> depotIdOpt = TrainTagHelper.readTagValue(properties, "FTA_DEPOT_ID");
    if (depotIdOpt.isEmpty() || depotIdOpt.get().isBlank()) {
      return;
    }
    NodeId actualStartNode = NodeId.of(depotIdOpt.get());
    NodeId declared = route.waypoints().get(0);
    if (!actualStartNode.equals(declared)) {
      debugLogger.accept(
          "首站位置初始化: train="
              + trainName
              + " depotId="
              + actualStartNode.value()
              + " reason=cret-spawn-origin");
    }
    // 即使等于声明节点也记录已恢复标记，避免每 tick 重复解析 CRET/tag。
    forceRecordEffectiveNode(trainName, route, 0, actualStartNode);
  }

  /**
   * 判断实际 Depot tag 是否仍属于“新车尚未驶离”的位置恢复阶段。
   *
   * <p>显式 marker 优先。旧版本没有 marker 时，仅接受 spawn/run 时间与首个 route 更新时间相差不超过 5 秒且不存在折返 ticket
   * 的列车；这既兼容服务器在新车刚生成后重启，也不会把长期运行或站内折返列车重新送回旧 Depot。
   */
  private boolean hasPendingSpawnOrigin(TrainProperties properties) {
    Optional<String> marker =
        TrainTagHelper.readTagValue(properties, TrainSpawnTagInitializer.TAG_SPAWN_ORIGIN_PENDING);
    if (marker.isPresent()) {
      return Boolean.parseBoolean(marker.get());
    }
    if (TrainTagHelper.readTagValue(properties, "FTA_TICKET_ID").isPresent()) {
      return false;
    }
    Optional<Long> runAt = TrainTagHelper.readLongTag(properties, "FTA_RUN_AT");
    Optional<Long> routeUpdatedAt =
        TrainTagHelper.readLongTag(properties, RouteProgressRegistry.TAG_ROUTE_UPDATED_AT);
    if (runAt.isEmpty() || routeUpdatedAt.isEmpty()) {
      return false;
    }
    long difference;
    try {
      difference = Math.abs(Math.subtractExact(runAt.get(), routeUpdatedAt.get()));
    } catch (ArithmeticException exception) {
      return false;
    }
    return difference <= 5_000L;
  }

  /**
   * 用真实图节点观测结束 spawn-origin bootstrap。
   *
   * <p>列车仍位于实际 Depot 且 route index 为 0 时保留 pending，保证此时服务器重启仍能恢复 DYNAMIC 股道；一旦抵达其它节点、推进到后续
   * index，或已经携带折返 ticket，就持久写入 false。该 marker 只控制历史 Depot tag 的解释权，不改变 route progress。
   */
  private void observePhysicalNodeForSpawnOrigin(
      TrainProperties properties, NodeId observedNode, int currentIndex) {
    if (properties == null || observedNode == null) {
      return;
    }
    Optional<String> depotId = TrainTagHelper.readTagValue(properties, "FTA_DEPOT_ID");
    boolean stillAtSpawnOrigin =
        currentIndex == 0
            && depotId.map(value -> value.equals(observedNode.value())).orElse(false)
            && TrainTagHelper.readTagValue(properties, "FTA_TICKET_ID").isEmpty();
    if (stillAtSpawnOrigin) {
      return;
    }
    TrainTagHelper.writeTag(properties, TrainSpawnTagInitializer.TAG_SPAWN_ORIGIN_PENDING, "false");
  }

  /**
   * 强制写入与 route 定义证据绑定的有效节点覆盖（即使与声明相同也写入）。
   *
   * <p>用于首站 spawn bootstrap。routeId、声明节点与对应 RouteStop 都会成为覆盖证据：同一列车在折返 handoff 后可能重新回到 index 0，同一
   * routeId 也可能在 reload 时更新 DYNAMIC 规则，旧动态股道或 Depot 位置不得跨定义复用。
   */
  private void forceRecordEffectiveNode(
      String trainName, RouteDefinition route, int index, NodeId effectiveNode) {
    if (trainName == null
        || trainName.isBlank()
        || route == null
        || effectiveNode == null
        || index < 0
        || index >= route.waypoints().size()) {
      return;
    }
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return;
    }
    effectiveNodeOverrides
        .computeIfAbsent(key, k -> new java.util.concurrent.ConcurrentHashMap<>())
        .put(index, createEffectiveNodeOverride(route, index, effectiveNode));
  }

  /**
   * 读取当前 route 定义证据下的有效节点覆盖。
   *
   * <p>若列车已经切换 route，或同一 routeId 的声明节点/RouteStop 已经刷新，读取动作会立即丢弃旧覆盖。这样 {@code /fta reload} 无需重建整个
   * RuntimeDispatchService，也不会让 reload 前遗留的位置覆盖污染新定义。
   */
  private Optional<NodeId> readEffectiveNode(String trainName, RouteDefinition route, int index) {
    if (trainName == null || trainName.isBlank() || route == null || index < 0) {
      return Optional.empty();
    }
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return Optional.empty();
    }
    java.util.concurrent.ConcurrentMap<Integer, EffectiveNodeOverride> overrides =
        effectiveNodeOverrides.get(key);
    if (overrides == null) {
      return Optional.empty();
    }
    EffectiveNodeOverride override = overrides.get(index);
    if (override == null) {
      return Optional.empty();
    }
    if (effectiveNodeOverrideMatches(override, route, index)) {
      return Optional.of(override.node());
    }
    if (overrides.remove(index, override)) {
      if (overrides.isEmpty()) {
        effectiveNodeOverrides.remove(key, overrides);
      }
      traceEffectiveNodeOverrideRejected(trainName, index, override, route);
    }
    return Optional.empty();
  }

  private EffectiveNodeOverride createEffectiveNodeOverride(
      RouteDefinition route, int index, NodeId effectiveNode) {
    return new EffectiveNodeOverride(
        route.id(),
        route.waypoints().get(index),
        routeDefinitions == null ? Optional.empty() : routeDefinitions.findStop(route.id(), index),
        effectiveNode);
  }

  private boolean effectiveNodeOverrideMatches(
      EffectiveNodeOverride override, RouteDefinition route, int index) {
    if (override == null
        || route == null
        || index < 0
        || index >= route.waypoints().size()
        || !override.routeId().equals(route.id())
        || !override.declaredNode().equals(route.waypoints().get(index))) {
      return false;
    }
    Optional<RouteStop> currentStop =
        routeDefinitions == null ? Optional.empty() : routeDefinitions.findStop(route.id(), index);
    return override.declaredStop().equals(currentStop);
  }

  private void traceEffectiveNodeOverrideRejected(
      String trainName, int index, EffectiveNodeOverride override, RouteDefinition currentRoute) {
    boolean routeChanged = !override.routeId().equals(currentRoute.id());
    NodeId currentDeclared =
        index >= 0 && index < currentRoute.waypoints().size()
            ? currentRoute.waypoints().get(index)
            : null;
    debugLogger.accept(
        (routeChanged
                ? "SMART_EFFECTIVE_NODE_OVERRIDE_ROUTE_REJECTED"
                : "SMART_EFFECTIVE_NODE_OVERRIDE_DEFINITION_REJECTED")
            + " train="
            + trainName
            + " index="
            + index
            + " staleRoute="
            + override.routeId().value()
            + " currentRoute="
            + currentRoute.id().value()
            + " staleDeclared="
            + override.declaredNode().value()
            + " currentDeclared="
            + (currentDeclared == null ? "-" : currentDeclared.value())
            + " staleNode="
            + override.node().value());
  }

  /**
   * 与 route 定义证据绑定的 materialized/observed 节点事实。
   *
   * <p>RouteId 本身不是定义版本：热刷新可在同一个 ID 下改变 waypoint 或 DYNAMIC stop 规则。覆盖必须同时绑定该索引的声明节点与
   * RouteStop，否则旧站台/Depot 会穿透 reload 进入新定义。
   */
  private record EffectiveNodeOverride(
      RouteId routeId, NodeId declaredNode, Optional<RouteStop> declaredStop, NodeId node) {
    private EffectiveNodeOverride {
      Objects.requireNonNull(routeId, "routeId");
      Objects.requireNonNull(declaredNode, "declaredNode");
      declaredStop = declaredStop == null ? Optional.empty() : declaredStop;
      Objects.requireNonNull(node, "node");
    }
  }

  /**
   * 尝试为列车分配动态站台。
   *
   * <p>当列车进入 {@link DynamicPlatformAllocator#ALLOCATION_EDGE_THRESHOLD} edges 的 DYNAMIC
   * 授权窗口时，从候选范围内选择可用站台并写入节点覆盖表。TrainCarts destination 必须等本轮信号 tick 的 canEnter/acquire
   * 成功后再写入，避免未授权寻路。
   */
  private DynamicResolution<DynamicDestinationResolver.ResolvedDynamicDestination>
      tryDynamicPlatformAllocation(
          RuntimeTrainHandle train,
          String trainName,
          RouteDefinition route,
          int currentIndex,
          NodeId currentNode) {
    if (train == null || route == null || dynamicDestinationResolver == null) {
      return DynamicResolution.notApplicable("dynamic-resolver-unavailable");
    }
    DynamicResolution<DynamicDestinationResolver.ResolvedDynamicDestination> resolution =
        dynamicDestinationResolver.resolveSignalTickDestination(
            trainName, route, currentIndex, train.worldId(), currentNode, train.forwardDirection());
    if (!resolution.isSelected()) {
      return resolution;
    }
    DynamicDestinationResolver.ResolvedDynamicDestination result =
        resolution.selected().orElseThrow();

    // 写入节点覆盖表
    recordEffectiveNode(trainName, route, result.stopIndex(), result.node());

    debugLogger.accept(
        "DYNAMIC effective node 写入: train="
            + trainName
            + ", node="
            + result.node().value()
            + ", idx="
            + result.stopIndex()
            + ", reason="
            + result.reason());
    return resolution;
  }

  /**
   * 授权后写入 DYNAMIC materialized destination。
   *
   * <p>该方法只处理“下一站 DYNAMIC stop 已经被 effective node 覆盖”的场景。调用方必须已经完成本轮信号 tick 的
   * canEnter/acquire，并确认最终信号不是 STOP。
   */
  private Optional<String> commitAuthorizedDestination(
      TrainProperties properties,
      String trainName,
      RouteDefinition route,
      int targetIndex,
      NodeId targetNode) {
    if (properties == null || targetNode == null) {
      return Optional.empty();
    }
    String destinationName = resolveDestinationName(targetNode);
    if (destinationName == null || destinationName.isBlank()) {
      destinationName = targetNode.value();
    }
    if (destinationName == null || destinationName.isBlank()) {
      return Optional.empty();
    }
    properties.clearDestinationRoute();
    properties.setDestination(destinationName);
    debugLogger.accept(
        "destination 授权提交: train="
            + trainName
            + ", dest="
            + destinationName
            + ", idx="
            + targetIndex
            + ", dynamic="
            + (route != null && isDynamicMaterializedStop(route, targetIndex)));
    return Optional.of(destinationName);
  }

  private boolean isDynamicMaterializedStop(RouteDefinition route, int targetIndex) {
    if (route == null || targetIndex < 0) {
      return false;
    }
    Optional<RouteStop> stopOpt = routeDefinitions.findStop(route.id(), targetIndex);
    if (stopOpt.isEmpty()) {
      return false;
    }
    return routeStopActionResolver.dynamicTarget(stopOpt.get()).isPresent();
  }

  private NodeId resolveEffectiveNode(String trainName, RouteDefinition route, int index) {
    if (route == null) {
      return null;
    }
    if (index < 0 || index >= route.waypoints().size()) {
      return null;
    }
    return readEffectiveNode(trainName, route, index).orElse(route.waypoints().get(index));
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
    java.util.concurrent.ConcurrentMap<Integer, EffectiveNodeOverride> overrides =
        key.isEmpty() ? null : effectiveNodeOverrides.get(key);
    if (overrides == null || overrides.isEmpty()) {
      return base;
    }
    List<NodeId> copy = new java.util.ArrayList<>(base);
    for (var entry : overrides.entrySet()) {
      Integer idx = entry.getKey();
      EffectiveNodeOverride override = entry.getValue();
      if (idx == null || override == null) {
        continue;
      }
      if (idx < 0 || idx >= copy.size()) {
        continue;
      }
      if (!effectiveNodeOverrideMatches(override, route, idx)) {
        overrides.remove(idx, override);
        traceEffectiveNodeOverrideRejected(trainName, idx, override, route);
        continue;
      }
      copy.set(idx, override.node());
    }
    if (overrides.isEmpty()) {
      effectiveNodeOverrides.remove(key, overrides);
    }
    return copy;
  }

  /**
   * 供事件驱动信号 provider 复用的 effective waypoint 解析入口。
   *
   * <p>事件链路不能直接使用 {@link RouteDefinition#waypoints()}，否则 DYNAMIC materialized node 会退回
   * placeholder，导致 preview 与周期 tick 的授权窗口不一致。
   */
  public List<NodeId> resolveEffectiveWaypointsForEvent(String trainName, RouteDefinition route) {
    return resolveEffectiveWaypoints(trainName, route);
  }

  /**
   * 供事件信号 provider 解析单线方向的 canonical route leg。
   *
   * <p>与 movement waypoint 不同，这里刻意不应用 lastPassedGraphNode current-node override。DYNAMIC
   * materialization 仍会保留，但 route 当前段的上游语义锚点不会因列车进入中间图节点而消失。
   */
  public List<NodeId> resolveDirectionContextWaypointsForEvent(
      String trainName, RouteDefinition route) {
    return resolveEffectiveWaypoints(trainName, route);
  }

  /**
   * 供事件驱动信号 provider 复用的 directed current-node 解析入口。
   *
   * <p>该方法与周期性 signal tick 使用同一套 lastPassedGraphNode 校验规则：只有当最后经过的图节点位于当前 route
   * 段最短路上时，才把它作为当前节点写入请求快照。这样 EVENT 与 PERIODIC 会从同一个 committed progress snapshot 构建 forward
   * request。
   */
  public List<NodeId> resolveEffectiveWaypointsForEvent(
      String trainName, RouteDefinition route, int currentIndex, RailGraph graph) {
    List<NodeId> effectiveNodes = resolveEffectiveWaypoints(trainName, route);
    if (route == null || currentIndex < 0 || graph == null) {
      return effectiveNodes;
    }
    NodeId currentNode =
        resolveEffectiveCurrentNodeForSignal(trainName, route, currentIndex, graph);
    return applyCurrentNodeOverride(effectiveNodes, currentIndex, currentNode);
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
   * 保留尚未被票据认领的终点待命列车所覆盖的完整站内进路。
   *
   * <p>列车进入 READY 后仍可能有列尾压在站咽喉、道岔或平交道口上，因此不能把占用缩成单个站台 NODE。图与原 route
   * 均可用时复用正常停站的尾部保护窗口；任一证据暂时缺失时仅补齐当前位置 NODE，并 fail-retain 既有 claims。
   */
  private void retainLayoverReadyOccupancy(
      RuntimeTrainHandle train,
      TrainProperties properties,
      String trainName,
      LayoverCandidate candidate,
      Instant now) {
    NodeId locationNode = candidate.locationNodeId();
    Optional<RouteDefinition> route = resolveRouteDefinition(properties);
    Optional<RailGraph> graph = resolveGraph(train.worldId(), now);
    if (route.isPresent() && graph.isPresent() && !route.get().waypoints().isEmpty()) {
      retainStopOccupancy(
          trainName,
          route.get(),
          route.get().waypoints().size() - 1,
          locationNode,
          graph.get(),
          now);
      return;
    }
    OccupancyResource locationResource = OccupancyResource.forNode(locationNode);
    OccupancyRequest locationRequest =
        new OccupancyRequest(
            trainName,
            Optional.empty(),
            now,
            List.of(locationResource),
            Map.of(),
            0,
            AuthorizationPurpose.LAYOVER_REUSE);
    occupancyManager.acquire(locationRequest);
    debugLogger.accept("Layover READY 缺少完整 route/graph 证据，fail-retain 既有进路: train=" + trainName);
  }

  /**
   * 停站期间保留“当前节点 + 尾部保护边”的占用，避免后车提前释放后互卡。
   *
   * <p>当调度图或当前位置证据不可用时采用 fail-retain：不缩减既有占用，只在当前位置已知时补齐节点占用。临时图快照缺失不能成为释放站内进路或列尾防护的证据。
   */
  private void retainStopOccupancy(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode,
      RailGraph graph,
      Instant now) {
    retainStopOccupancy(trainName, route, currentIndex, currentNode, Optional.empty(), graph, now);
  }

  private void retainStopOccupancy(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode,
      Optional<OccupancyRequest> movementRequest,
      RailGraph graph,
      Instant now) {
    if (occupancyManager == null || trainName == null || trainName.isBlank()) {
      return;
    }
    if (currentNode == null) {
      return;
    }
    if (graph == null || route == null) {
      OccupancyResource keepResource = OccupancyResource.forNode(currentNode);
      OccupancyRequest locationRequest =
          new OccupancyRequest(
              trainName,
              Optional.empty(),
              now,
              List.of(keepResource),
              java.util.Map.of(),
              0,
              AuthorizationPurpose.RUNTIME_MOVE);
      occupancyManager.acquire(locationRequest);
      return;
    }
    ConfigManager.RuntimeSettings runtimeSettings = configManager.current().runtimeSettings();
    OccupancyRequestBuilder builder =
        runtimeLookaheadBuilder(graph, runtimeSettings, runtimeSettings.rearGuardEdges());
    currentNode = resolveStopRetainCurrentNode(trainName, route, currentIndex, currentNode, graph);
    List<NodeId> effectiveNodes =
        applyCurrentNodeOverride(
            resolveEffectiveWaypoints(trainName, route), currentIndex, currentNode);
    Optional<NodeId> targetNode =
        currentIndex + 1 < effectiveNodes.size()
            ? Optional.ofNullable(effectiveNodes.get(currentIndex + 1))
            : Optional.empty();
    if (isDeclaredDynamicStop(route, currentIndex + 1)
        && readEffectiveNode(trainName, route, currentIndex + 1).isEmpty()) {
      targetNode = Optional.empty();
    }
    TrainProperties properties = TrainPropertiesStore.get(trainName);
    DispatchPriorityResolution priorityResolution =
        dispatchPriorityResolver.resolve(
            "stop-retain", trainName, properties, route, progressRegistry.get(trainName));
    int priority = priorityResolution.priority();
    Optional<OccupancyRequest> safeMovementRequest =
        movementRequest == null ? Optional.empty() : movementRequest;
    Optional<OccupancyRequest> canonicalRequest =
        safeMovementRequest.isPresent()
            ? safeMovementRequest
            : buildContextWithinDynamicBoundary(
                    builder,
                    trainName,
                    route,
                    effectiveNodes,
                    effectiveNodes,
                    currentIndex,
                    now,
                    priority,
                    AuthorizationPurpose.RUNTIME_MOVE)
                .map(OccupancyRequestContext::request);
    Optional<MovementPlanSnapshot> canonicalPlan =
        canonicalRequest.flatMap(OccupancyRequest::movementPlanSnapshot);
    OccupancyRequest request;
    if (canonicalPlan.isPresent()) {
      request =
          builder.buildHoldPositionRequestFromPlan(
              trainName,
              Optional.ofNullable(route.id()),
              currentNode,
              targetNode,
              effectiveNodes,
              currentIndex,
              now,
              priority,
              AuthorizationPurpose.RUNTIME_MOVE,
              canonicalPlan.get());
    } else {
      request =
          builder.buildHoldPositionRequest(
              trainName,
              Optional.ofNullable(route.id()),
              currentNode,
              targetNode,
              effectiveNodes,
              currentIndex,
              now,
              priority,
              AuthorizationPurpose.RUNTIME_MOVE);
    }
    List<OccupancyClaim> oldSelfClaims = snapshotSelfClaims(trainName);
    traceSignalAuthorityLifecycle(
        "stop-retain",
        trainName,
        route,
        currentIndex,
        currentNode,
        targetNode,
        request,
        priorityResolution,
        "-",
        "-",
        "-");
    Set<OccupancyResource> protectedResources =
        mergeProtectedResources(
            protectedSingleCorridorClaims(
                trainName,
                request,
                route,
                currentIndex,
                currentNode,
                targetNode,
                priorityResolution),
            protectedSwitcherZoneClaims(
                trainName, route, currentIndex, currentNode, graph, "STOP_HOLD"));
    protectedResources =
        removeStopRetainBehindReleaseResources(
            protectedResources,
            stopRetainBehindReleaseResources(effectiveNodes, currentIndex, oldSelfClaims, request));
    List<OccupancyResource> releaseEligibleResources =
        resourcesEligibleForRelease(trainName, request.resourceList(), protectedResources);
    traceStopRetainResourceShrink(
        "before-release",
        trainName,
        route,
        currentIndex,
        currentNode,
        targetNode,
        request,
        priorityResolution,
        oldSelfClaims,
        protectedResources,
        releaseEligibleResources,
        List.of());
    traceStopRetainBehindReleaseDryRun(
        trainName,
        route,
        currentIndex,
        currentNode,
        targetNode,
        effectiveNodes,
        request,
        priorityResolution,
        oldSelfClaims,
        protectedResources);
    List<OccupancyResource> releasedResources =
        releaseResourcesNotInRequest(trainName, request.resourceList(), protectedResources);
    traceStopRetainResourceShrink(
        "after-release",
        trainName,
        route,
        currentIndex,
        currentNode,
        targetNode,
        request,
        priorityResolution,
        snapshotSelfClaims(trainName),
        protectedResources,
        resourcesEligibleForRelease(trainName, request.resourceList(), protectedResources),
        releasedResources);
    occupancyManager.acquire(request);
    retainForwardQueuePositionAtStop(canonicalRequest, now, priority);
  }

  /**
   * 在 DYNAMIC 容量耗尽时刷新当前位置保护。
   *
   * <p>该入口先撤回纯排队位次，再仅在当前位置没有任何本车 claim 时补一个 NODE/HOLD_ONLY claim。它不构造前向边或冲突资源，也不刷新、降级、收缩既有
   * claim；无可用站台不是改变真实占用角色的物理证据。
   */
  private int retainDynamicCapacityWaitOccupancy(
      String trainName, RouteDefinition route, NodeId currentNode, Instant now) {
    int withdrawnQueueEntries = withdrawForwardQueuePositions(trainName);
    if (occupancyManager == null
        || trainName == null
        || trainName.isBlank()
        || currentNode == null) {
      return withdrawnQueueEntries;
    }
    OccupancyResource currentNodeResource = OccupancyResource.forNode(currentNode);
    boolean alreadyOwned =
        occupancyManager.snapshotClaims().stream()
            .filter(Objects::nonNull)
            .anyMatch(
                claim ->
                    currentNodeResource.equals(claim.resource())
                        && TrainNameNormalizer.sameLogicalTrain(trainName, claim.trainName()));
    if (!alreadyOwned) {
      OccupancyRequest locationHold =
          new OccupancyRequest(
              trainName,
              route == null ? Optional.empty() : Optional.of(route.id()),
              now,
              List.of(currentNodeResource),
              java.util.Map.of(),
              java.util.Map.of(),
              0,
              AuthorizationPurpose.RUNTIME_MOVE,
              java.util.Map.of(),
              java.util.Map.of(currentNodeResource, ResourceIntent.HOLD_ONLY));
      occupancyManager.acquire(locationHold);
    }
    return withdrawnQueueEntries;
  }

  /**
   * 解析停站 retain 的实际当前位置。
   *
   * <p>普通信号请求已经会用 {@code lastPassedGraphNode} 修正当前位置，但 stop-retain 过去仍直接使用 route index 节点。折返复用时
   * route index 可能重新落在段起点，而列车已经经过下一段 interval 的起点站；此时继续按旧段起点构造 HOLD_ONLY footprint 会把身后的咽喉 single
   * 误当作“当前车体”保住。这里只在 last-passed 安全停车点确认为下一 leg 的 origin boundary，且它能到达下一目标时才纠偏。
   */
  private NodeId resolveStopRetainCurrentNode(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode,
      RailGraph graph) {
    if (currentNode == null || route == null || graph == null || currentIndex < 0) {
      return currentNode;
    }
    NodeId routeNode = resolveEffectiveNode(trainName, route, currentIndex);
    if (routeNode == null || !routeNode.equals(currentNode)) {
      return currentNode;
    }
    NodeId signalNode = resolveEffectiveCurrentNodeForSignal(trainName, route, currentIndex, graph);
    if (signalNode != null && !signalNode.equals(routeNode)) {
      traceStopRetainCurrentNodeOverride(
          trainName,
          routeNode,
          signalNode,
          nextRouteNode(trainName, route, currentIndex),
          "signal-path");
      return signalNode;
    }
    Optional<NodeId> lastPassedOpt =
        progressRegistry
            .get(trainName)
            .flatMap(RouteProgressRegistry.RouteProgressEntry::lastPassedGraphNode);
    if (lastPassedOpt.isEmpty()) {
      return currentNode;
    }
    NodeId lastPassed = lastPassedOpt.get();
    if (lastPassed.equals(routeNode)) {
      return currentNode;
    }
    Optional<NodeId> nextNodeOpt = nextRouteNode(trainName, route, currentIndex);
    if (nextNodeOpt.isEmpty()) {
      return currentNode;
    }
    NodeId nextNode = nextNodeOpt.get();
    if (!lastPassedStartsNextLeg(lastPassed, nextNode, graph)) {
      return currentNode;
    }
    if (pathFinder
        .shortestPath(graph, lastPassed, nextNode, RailGraphPathFinder.Options.shortestDistance())
        .isEmpty()) {
      return currentNode;
    }
    traceStopRetainCurrentNodeOverride(
        trainName, routeNode, lastPassed, Optional.of(nextNode), "last-passed-next-leg-origin");
    return lastPassed;
  }

  private Optional<NodeId> nextRouteNode(
      String trainName, RouteDefinition route, int currentIndex) {
    if (route == null || currentIndex + 1 >= route.waypoints().size()) {
      return Optional.empty();
    }
    return Optional.ofNullable(resolveEffectiveNode(trainName, route, currentIndex + 1));
  }

  private boolean lastPassedStartsNextLeg(NodeId lastPassed, NodeId nextNode, RailGraph graph) {
    Optional<WaypointMetadata> lastMetadata = resolveWaypointMetadata(graph, lastPassed);
    Optional<WaypointMetadata> nextMetadata = resolveWaypointMetadata(graph, nextNode);
    if (lastMetadata.isEmpty() || nextMetadata.isEmpty()) {
      return false;
    }
    WaypointMetadata last = lastMetadata.get();
    WaypointMetadata next = nextMetadata.get();
    if (!sameText(last.operator(), next.operator())) {
      return false;
    }
    if (last.kind() != WaypointKind.STATION && last.kind() != WaypointKind.DEPOT) {
      return false;
    }
    if (next.kind() == WaypointKind.INTERVAL) {
      return sameText(last.originStation(), next.originStation());
    }
    if (last.kind() == WaypointKind.STATION && next.kind() == WaypointKind.STATION_THROAT) {
      return sameText(last.originStation(), next.originStation());
    }
    if (last.kind() == WaypointKind.DEPOT && next.kind() == WaypointKind.DEPOT_THROAT) {
      return sameText(last.originStation(), next.originStation());
    }
    return false;
  }

  private static boolean sameText(String first, String second) {
    return first != null && second != null && first.equalsIgnoreCase(second);
  }

  private void traceStopRetainCurrentNodeOverride(
      String trainName,
      NodeId routeNode,
      NodeId effectiveNode,
      Optional<NodeId> nextNode,
      String reason) {
    debugLogger.accept(
        "SMART_STOP_RETAIN_CURRENT_NODE_OVERRIDDEN train="
            + safeTraceValue(trainName)
            + " routeNode="
            + (routeNode == null ? "-" : routeNode.value())
            + " effectiveNode="
            + (effectiveNode == null ? "-" : effectiveNode.value())
            + " nextTarget="
            + nextNode.map(NodeId::value).orElse("-")
            + " reason="
            + safeTraceValue(reason));
  }

  /**
   * 计算停站 retain 时可安全释放的旧 single corridor claim。
   *
   * <p>该释放只处理本车旧 {@link ClaimRole#MOVEMENT_REQUIRED} single claim；资源必须不在当前 hold request 中，并且 route
   * 进度能证明它只触及当前索引之前的节点。这样可以清掉 layover/停站后残留在身后的旧方向证据，同时不会释放当前车体位置或外部列车占用。
   */
  private Set<OccupancyResource> stopRetainBehindReleaseResources(
      List<NodeId> effectiveNodes,
      int currentIndex,
      List<OccupancyClaim> oldSelfClaims,
      OccupancyRequest request) {
    if (oldSelfClaims == null || oldSelfClaims.isEmpty()) {
      return Set.of();
    }
    Set<OccupancyResource> releaseResources = new LinkedHashSet<>();
    for (OccupancyClaim claim : oldSelfClaims) {
      if (claim == null
          || claim.resource() == null
          || !isSingleCorridorResource(claim.resource())) {
        continue;
      }
      StopRetainBehindReleaseProof proof =
          evaluateStopRetainBehindReleaseDryRun(effectiveNodes, currentIndex, claim, request);
      if (proof.wouldReleaseIfBehaviorPatchExisted()) {
        releaseResources.add(claim.resource());
      }
    }
    return Set.copyOf(releaseResources);
  }

  /**
   * 从保护集中移除已由 route 进度证明落在身后的旧 single 资源。
   *
   * <p>真正的释放仍由 {@link #releaseResourcesNotInRequest(String, List, Set)} 执行，因此当前 request
   * 覆盖的资源不会被释放；这里仅撤销旧保守保护，避免旧方向 claim 长期阻塞对向准入。
   */
  private static Set<OccupancyResource> removeStopRetainBehindReleaseResources(
      Set<OccupancyResource> protectedResources, Set<OccupancyResource> releaseResources) {
    if (protectedResources == null || protectedResources.isEmpty()) {
      return Set.of();
    }
    if (releaseResources == null || releaseResources.isEmpty()) {
      return protectedResources;
    }
    Set<OccupancyResource> result = new LinkedHashSet<>(protectedResources);
    result.removeAll(releaseResources);
    return Set.copyOf(result);
  }

  private List<OccupancyClaim> snapshotSelfClaims(String trainName) {
    if (occupancyManager == null || trainName == null || trainName.isBlank()) {
      return List.of();
    }
    List<OccupancyClaim> result = new ArrayList<>();
    for (OccupancyClaim claim : occupancyManager.snapshotClaims()) {
      if (claim == null || claim.trainName() == null) {
        continue;
      }
      if (claim.trainName().equalsIgnoreCase(trainName)) {
        result.add(claim);
      }
    }
    return List.copyOf(result);
  }

  private List<OccupancyResource> resourcesEligibleForRelease(
      String trainName,
      List<OccupancyResource> keepResources,
      Set<OccupancyResource> protectedResources) {
    if (occupancyManager == null || trainName == null || trainName.isBlank()) {
      return List.of();
    }
    Set<OccupancyResource> keep =
        keepResources == null ? Set.of() : new LinkedHashSet<>(keepResources);
    Set<OccupancyResource> protectedSet =
        protectedResources == null ? Set.of() : Set.copyOf(protectedResources);
    List<OccupancyResource> result = new ArrayList<>();
    for (OccupancyClaim claim : occupancyManager.snapshotClaims()) {
      if (claim == null || claim.resource() == null || claim.trainName() == null) {
        continue;
      }
      if (!claim.trainName().equalsIgnoreCase(trainName)) {
        continue;
      }
      if (keep.contains(claim.resource()) || protectedSet.contains(claim.resource())) {
        continue;
      }
      result.add(claim.resource());
    }
    return List.copyOf(result);
  }

  private void traceSignalAuthorityLifecycle(
      String source,
      String trainName,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode,
      Optional<NodeId> targetNode,
      OccupancyRequest request,
      DispatchPriorityResolution priorityResolution,
      String computedAspect,
      String publishedAspect,
      String publishSuppressed) {
    SignalComputationTrace.emitRaw(
        "SMART_SIGNAL_AUTHORITY_LIFECYCLE source="
            + safeTraceValue(source)
            + " train="
            + safeTraceValue(trainName)
            + " requestId="
            + requestIdOf(request)
            + " route="
            + (route == null || route.id() == null ? "-" : safeTraceValue(route.id().value()))
            + " index="
            + currentIndex
            + " currentNode="
            + (currentNode == null ? "-" : currentNode.value())
            + " effectiveTo="
            + effectiveToText(request, targetNode)
            + " priority="
            + (request == null ? "-" : request.priority())
            + " resolvedPriority="
            + (priorityResolution == null ? "-" : priorityResolution.priority())
            + " prioritySource="
            + (priorityResolution == null ? "-" : priorityResolution.source())
            + " operationType="
            + (priorityResolution == null
                ? "-"
                : priorityResolution.operationType().map(Enum::name).orElse("-"))
            + " requestInputType="
            + (request == null ? "-" : SignalDecisionInputClassifier.classify(request))
            + " requestResourceCount="
            + (request == null ? 0 : request.resourceList().size())
            + " movementRequiredResources="
            + countResourcesByIntent(request, ResourceIntent.MOVEMENT_REQUIRED)
            + " protectiveRetainResources="
            + countResourcesByIntent(request, ResourceIntent.PROTECTIVE_RETAIN)
            + " holdOnlyResources="
            + countResourcesByIntent(request, ResourceIntent.HOLD_ONLY)
            + " queuePositionResources="
            + countResourcesByIntent(request, ResourceIntent.QUEUE_POSITION)
            + " oldSelfClaims="
            + snapshotSelfClaims(trainName).size()
            + " computedAspect="
            + safeTraceValue(computedAspect)
            + " publishedAspect="
            + safeTraceValue(publishedAspect)
            + " publishSuppressed="
            + safeTraceValue(publishSuppressed),
        debugLogger);
  }

  private void traceStopRetainResourceShrink(
      String phase,
      String trainName,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode,
      Optional<NodeId> targetNode,
      OccupancyRequest request,
      DispatchPriorityResolution priorityResolution,
      List<OccupancyClaim> selfClaims,
      Set<OccupancyResource> protectedResources,
      List<OccupancyResource> releaseEligibleResources,
      List<OccupancyResource> releasedResources) {
    debugLogger.accept(
        "SMART_STOP_RETAIN_RESOURCE_SHRINK phase="
            + safeTraceValue(phase)
            + " train="
            + safeTraceValue(trainName)
            + " requestId="
            + requestIdOf(request)
            + " route="
            + (route == null || route.id() == null ? "-" : safeTraceValue(route.id().value()))
            + " index="
            + currentIndex
            + " currentNode="
            + (currentNode == null ? "-" : currentNode.value())
            + " effectiveTo="
            + targetNode.map(NodeId::value).orElse("-")
            + " priority="
            + (request == null ? "-" : request.priority())
            + " resolvedPriority="
            + (priorityResolution == null ? "-" : priorityResolution.priority())
            + " operationType="
            + (priorityResolution == null
                ? "-"
                : priorityResolution.operationType().map(Enum::name).orElse("-"))
            + " requestInputType="
            + (request == null ? "-" : SignalDecisionInputClassifier.classify(request))
            + " requestResources="
            + summarizeResources(request == null ? List.of() : request.resourceList(), 16)
            + " oldSelfClaims="
            + (selfClaims == null ? 0 : selfClaims.size())
            + " oldSelfSingleClaims="
            + countSingleClaims(selfClaims)
            + " movementRequiredResources="
            + countResourcesByIntent(request, ResourceIntent.MOVEMENT_REQUIRED)
            + " protectiveRetainResources="
            + countResourcesByIntent(request, ResourceIntent.PROTECTIVE_RETAIN)
            + " holdOnlyResources="
            + countResourcesByIntent(request, ResourceIntent.HOLD_ONLY)
            + " protectedResources="
            + summarizeResources(
                new ArrayList<>(protectedResources == null ? Set.of() : protectedResources), 16)
            + " releaseEligibleResources="
            + summarizeResources(releaseEligibleResources, 16)
            + " actualReleasedResources="
            + summarizeResources(releasedResources, 16));
  }

  private void traceStopRetainProtectOldSingle(
      String trainName,
      OccupancyClaim claim,
      OccupancyRequest request,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode,
      Optional<NodeId> targetNode,
      DispatchPriorityResolution priorityResolution,
      boolean keepContains,
      boolean keepHasSingle,
      boolean protectedByCurrentLogic) {
    if (claim == null || claim.resource() == null) {
      return;
    }
    debugLogger.accept(
        "SMART_STOP_RETAIN_PROTECT_OLD_SINGLE train="
            + safeTraceValue(trainName)
            + " requestId="
            + requestIdOf(request)
            + " route="
            + (route == null || route.id() == null ? "-" : safeTraceValue(route.id().value()))
            + " index="
            + currentIndex
            + " currentNode="
            + (currentNode == null ? "-" : currentNode.value())
            + " effectiveTo="
            + targetNode.map(NodeId::value).orElse("-")
            + " resource="
            + claim.resource()
            + " oldRole="
            + claim.role()
            + " heldDirection="
            + claim.corridorDirection().orElse(CorridorDirection.UNKNOWN)
            + " requestedDirection="
            + requestedDirectionFor(request, claim.resource())
            + " keepContains="
            + keepContains
            + " keepHasSingle="
            + keepHasSingle
            + " protected="
            + protectedByCurrentLogic
            + " reason="
            + stopRetainProtectionReason(keepContains, keepHasSingle)
            + " priority="
            + (request == null ? "-" : request.priority())
            + " resolvedPriority="
            + (priorityResolution == null ? "-" : priorityResolution.priority())
            + " operationType="
            + (priorityResolution == null
                ? "-"
                : priorityResolution.operationType().map(Enum::name).orElse("-")));
  }

  private void traceStopRetainBehindReleaseDryRun(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      NodeId currentNode,
      Optional<NodeId> targetNode,
      List<NodeId> effectiveNodes,
      OccupancyRequest request,
      DispatchPriorityResolution priorityResolution,
      List<OccupancyClaim> oldSelfClaims,
      Set<OccupancyResource> protectedResources) {
    if (oldSelfClaims == null || oldSelfClaims.isEmpty()) {
      return;
    }
    for (OccupancyClaim claim : oldSelfClaims) {
      if (claim == null
          || claim.resource() == null
          || !isSingleCorridorResource(claim.resource())) {
        continue;
      }
      StopRetainBehindReleaseProof proof =
          evaluateStopRetainBehindReleaseDryRun(effectiveNodes, currentIndex, claim, request);
      ResourceIntent intent =
          request != null && request.resourceList().contains(claim.resource())
              ? request.intentFor(claim.resource())
              : null;
      debugLogger.accept(
          "SMART_STOP_RETAIN_BEHIND_RELEASE_DRY_RUN train="
              + safeTraceValue(trainName)
              + " requestId="
              + requestIdOf(request)
              + " route="
              + (route == null || route.id() == null ? "-" : safeTraceValue(route.id().value()))
              + " index="
              + currentIndex
              + " currentNode="
              + (currentNode == null ? "-" : currentNode.value())
              + " effectiveTo="
              + targetNode.map(NodeId::value).orElse("-")
              + " resource="
              + claim.resource()
              + " oldRole="
              + claim.role()
              + " heldDirection="
              + claim.corridorDirection().orElse(CorridorDirection.UNKNOWN)
              + " requestedDirection="
              + requestedDirectionFor(request, claim.resource())
              + " inMovementRequiredResources="
              + (intent == ResourceIntent.MOVEMENT_REQUIRED)
              + " inProtectiveRetainResources="
              + (intent == ResourceIntent.PROTECTIVE_RETAIN)
              + " inCurrentFootprintResources="
              + (intent == ResourceIntent.HOLD_ONLY)
              + " inRearGuardResources="
              + (intent == ResourceIntent.PROTECTIVE_RETAIN)
              + " inNextAuthorityResources="
              + (intent == ResourceIntent.MOVEMENT_REQUIRED)
              + " currentlyProtected="
              + (protectedResources != null && protectedResources.contains(claim.resource()))
              + " directionMatchesRouteOrPriorClaim="
              + directionMatchesRouteOrPriorClaim(
                  claim.corridorDirection().orElse(CorridorDirection.UNKNOWN),
                  requestedDirectionFor(request, claim.resource()))
              + " routeProgressCanProveBehind="
              + proof.routeProgressCanProveBehind()
              + " wouldReleaseIfBehaviorPatchExisted="
              + proof.wouldReleaseIfBehaviorPatchExisted()
              + " noReleaseReason="
              + proof.noReleaseReason()
              + " proofSource="
              + proof.proofSource()
              + " priority="
              + (request == null ? "-" : request.priority())
              + " resolvedPriority="
              + (priorityResolution == null ? "-" : priorityResolution.priority())
              + " operationType="
              + (priorityResolution == null
                  ? "-"
                  : priorityResolution.operationType().map(Enum::name).orElse("-")));
    }
  }

  private StopRetainBehindReleaseProof evaluateStopRetainBehindReleaseDryRun(
      List<NodeId> effectiveNodes,
      int currentIndex,
      OccupancyClaim claim,
      OccupancyRequest request) {
    if (claim == null || claim.resource() == null) {
      return new StopRetainBehindReleaseProof(false, false, "missing-claim", "missing-claim");
    }
    boolean inRequest = request != null && request.resourceList().contains(claim.resource());
    ResourceIntent intent = inRequest ? request.intentFor(claim.resource()) : null;
    if (intent == ResourceIntent.MOVEMENT_REQUIRED) {
      return new StopRetainBehindReleaseProof(false, false, "request", "still-in-next-authority");
    }
    if (intent == ResourceIntent.PROTECTIVE_RETAIN) {
      return new StopRetainBehindReleaseProof(false, false, "request", "still-in-rear-guard");
    }
    if (intent == ResourceIntent.HOLD_ONLY) {
      return new StopRetainBehindReleaseProof(
          false, false, "request", "still-in-current-footprint");
    }
    if (claim.role() != ClaimRole.MOVEMENT_REQUIRED) {
      return new StopRetainBehindReleaseProof(
          false, false, "claim-role", "old-claim-not-movement-required");
    }
    CorridorDirection held = claim.corridorDirection().orElse(CorridorDirection.UNKNOWN);
    CorridorDirection requested = requestedDirectionFor(request, claim.resource());
    if (held == CorridorDirection.UNKNOWN && requested == CorridorDirection.UNKNOWN) {
      return new StopRetainBehindReleaseProof(false, false, "direction", "direction-unknown");
    }
    if (!directionMatchesRouteOrPriorClaim(held, requested)) {
      return new StopRetainBehindReleaseProof(false, false, "direction", "direction-mismatch");
    }
    StopRetainBehindReleaseProof routeProof =
        routeProgressBehindProof(effectiveNodes, currentIndex, claim.resource());
    if (!routeProof.routeProgressCanProveBehind()) {
      return routeProof;
    }
    return new StopRetainBehindReleaseProof(
        true, true, routeProof.proofSource(), "dry-run-only-no-behavior-change");
  }

  private StopRetainBehindReleaseProof routeProgressBehindProof(
      List<NodeId> effectiveNodes, int currentIndex, OccupancyResource resource) {
    if (resource == null) {
      return new StopRetainBehindReleaseProof(false, false, "missing-resource", "missing-resource");
    }
    if (effectiveNodes == null || effectiveNodes.isEmpty()) {
      return new StopRetainBehindReleaseProof(
          false, false, "route-progress", "route-nodes-missing");
    }
    String key = resource.key();
    int boundedIndex = Math.max(0, Math.min(currentIndex, effectiveNodes.size()));
    boolean touchesBehind = false;
    boolean touchesCurrentOrAhead = false;
    for (int i = 0; i < effectiveNodes.size(); i++) {
      NodeId node = effectiveNodes.get(i);
      if (node == null || node.value() == null || !key.contains(node.value())) {
        continue;
      }
      if (i < boundedIndex) {
        touchesBehind = true;
      } else {
        touchesCurrentOrAhead = true;
      }
    }
    if (touchesBehind && !touchesCurrentOrAhead) {
      return new StopRetainBehindReleaseProof(true, false, "route-node-token", "-");
    }
    if (touchesCurrentOrAhead) {
      return new StopRetainBehindReleaseProof(
          false, false, "route-node-token", "resource-touches-current-or-future-route-node");
    }
    return new StopRetainBehindReleaseProof(
        false, false, "route-node-token", "cannot-map-resource-to-route-progress");
  }

  private static boolean directionMatchesRouteOrPriorClaim(
      CorridorDirection held, CorridorDirection requested) {
    CorridorDirection safeHeld = held == null ? CorridorDirection.UNKNOWN : held;
    CorridorDirection safeRequested = requested == null ? CorridorDirection.UNKNOWN : requested;
    if (safeHeld == CorridorDirection.UNKNOWN && safeRequested == CorridorDirection.UNKNOWN) {
      return false;
    }
    return safeHeld == CorridorDirection.UNKNOWN
        || safeRequested == CorridorDirection.UNKNOWN
        || safeHeld == safeRequested;
  }

  private static String stopRetainProtectionReason(boolean keepContains, boolean keepHasSingle) {
    if (keepContains) {
      return "resource-in-hold-request";
    }
    if (!keepHasSingle) {
      return "hold-request-has-no-single-corridor";
    }
    return "hold-request-has-different-single-corridor";
  }

  private static int countResourcesByIntent(OccupancyRequest request, ResourceIntent intent) {
    if (request == null || intent == null) {
      return 0;
    }
    int count = 0;
    for (OccupancyResource resource : request.resourceList()) {
      if (request.intentFor(resource) == intent) {
        count++;
      }
    }
    return count;
  }

  private int countSingleClaims(List<OccupancyClaim> claims) {
    if (claims == null || claims.isEmpty()) {
      return 0;
    }
    int count = 0;
    for (OccupancyClaim claim : claims) {
      if (claim != null && isSingleCorridorResource(claim.resource())) {
        count++;
      }
    }
    return count;
  }

  private static String effectiveToText(OccupancyRequest request, Optional<NodeId> fallback) {
    if (request != null) {
      Optional<NodeId> effectiveTo =
          request.directedContext().flatMap(DirectedTraversalContext::effectiveToNode);
      if (effectiveTo.isPresent()) {
        return effectiveTo.get().value();
      }
    }
    return fallback == null ? "-" : fallback.map(NodeId::value).orElse("-");
  }

  /**
   * 停站/阻塞等待期间刷新前向冲突队列位次。
   *
   * <p>列车在门控等待时虽然不会立即占用前方区段，但应持续保留自己在冲突队列中的先后顺序；否则后车可能在等待窗口内先触发一次前向判定， 反而抢到更靠前的队头，造成前车被后车卡住。
   */
  private void retainForwardQueuePositionAtStop(
      Optional<OccupancyRequest> canonicalRequest, Instant now, int priority) {
    if (!(occupancyManager instanceof OccupancyQueueSupport queueSupport)
        || canonicalRequest == null
        || canonicalRequest.isEmpty()
        || now == null
        || canonicalRequest.get().resourceList().isEmpty()) {
      return;
    }
    queueSupport.touchQueues(canonicalRequest.get().withSchedulingMetadata(now, priority));
  }

  /**
   * 撤回列车当前所有纯排队位次。
   *
   * <p>只通过 {@link OccupancyQueueSupport#removeQueueEntries(String, List)} 删除 queue，不释放任何
   * claim。调用点必须已经确认列车当前没有可 materialize 的 DYNAMIC 目标；真实车体、节点、边和对向单线保护仍由占用 claim 保持。
   */
  private int withdrawForwardQueuePositions(String trainName) {
    if (!(occupancyManager instanceof OccupancyQueueSupport queueSupport)
        || trainName == null
        || trainName.isBlank()) {
      return 0;
    }
    LinkedHashSet<OccupancyResource> queuedResources = new LinkedHashSet<>();
    for (OccupancyQueueSnapshot snapshot : queueSupport.snapshotQueues()) {
      if (snapshot == null) {
        continue;
      }
      boolean containsTrain =
          snapshot.entries().stream()
              .anyMatch(
                  entry ->
                      entry != null
                          && TrainNameNormalizer.sameLogicalTrain(trainName, entry.trainName()));
      if (containsTrain) {
        queuedResources.add(snapshot.resource());
      }
    }
    if (queuedResources.isEmpty()) {
      return 0;
    }
    return queueSupport.removeQueueEntries(trainName, List.copyOf(queuedResources));
  }

  /**
   * 判定 TERMINATE 停靠是否应立即进入 Layover。
   *
   * <p>仅当满足以下条件时进入 Layover：
   *
   * <ul>
   *   <li>当前 stop 为 {@link RouteStopPassType#TERMINATE}
   *   <li>线路生命周期为 {@code REUSE_AT_TERM}
   *   <li>当前索引已是线路尾节点
   * </ul>
   *
   * <p>这样可避免“线路在 TERM 后仍有回库/折返段”时被提前拦停。
   */
  private boolean shouldEnterLayoverAtTerminateStop(
      RouteDefinition route, int currentIndex, RouteStop stop) {
    if (route == null || stop == null) {
      return false;
    }
    if (stop.passType() != RouteStopPassType.TERMINATE) {
      return false;
    }
    if (route.lifecycleMode()
        != org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLifecycleMode.REUSE_AT_TERM) {
      return false;
    }
    int tailIndex = route.waypoints().size() - 1;
    return currentIndex >= tailIndex;
  }

  /**
   * 释放已经越过的 DYNAMIC 预订与 effective-node 覆盖。
   *
   * <p>站台预订保留到列车实际推进至该 stop；只有当前索引越过它后才释放，避免尚在站台内时被后车重新选中。
   */
  private void pruneDynamicResolutionState(
      String trainName, RouteDefinition route, int minIndexToKeep) {
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    if (route != null) {
      dynamicAllocator.releaseCompletedAllocations(trainName, route.id(), minIndexToKeep);
    }
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return;
    }
    java.util.concurrent.ConcurrentMap<Integer, EffectiveNodeOverride> overrides =
        effectiveNodeOverrides.get(key);
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
    String trainName =
        properties == null
            ? null
            : resolveTrackedTrainName(properties).orElseGet(properties::getTrainName);
    return dispatchPriorityResolver
        .resolve("runtime-dispatch", trainName, properties, route)
        .priority();
  }

  private void traceDispatchRequestContext(
      String context,
      String trainName,
      RouteDefinition route,
      int currentIndex,
      DispatchPriorityResolution priorityResolution) {
    if (priorityResolution == null) {
      return;
    }
    SignalComputationTrace.emitRaw(
        "SMART_DISPATCH_REQUEST_CONTEXT context="
            + safeTraceValue(context)
            + " train="
            + safeTraceValue(trainName)
            + " route="
            + (route == null || route.id() == null ? "-" : safeTraceValue(route.id().value()))
            + " index="
            + currentIndex
            + " priority="
            + priorityResolution.priority()
            + " prioritySource="
            + priorityResolution.source()
            + " operationType="
            + priorityResolution.operationType().map(Enum::name).orElse("-")
            + " fallbackReason="
            + priorityResolution.fallbackReason(),
        debugLogger);
  }

  private static String safeTraceValue(String value) {
    return value == null || value.isBlank() ? "-" : value.trim();
  }

  private static Optional<String> readFirstTag(
      TrainProperties properties, String primaryKey, String fallbackKey) {
    Optional<String> primary = TrainTagHelper.readTagValue(properties, primaryKey);
    return primary.isPresent() ? primary : TrainTagHelper.readTagValue(properties, fallbackKey);
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
        readFirstTag(properties, RouteProgressRegistry.TAG_OPERATOR_CODE, "FTA_OPERATOR");
    Optional<String> lineCode =
        readFirstTag(properties, RouteProgressRegistry.TAG_LINE_CODE, "FTA_LINE");
    Optional<String> routeCode =
        readFirstTag(properties, RouteProgressRegistry.TAG_ROUTE_CODE, "FTA_ROUTE");
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
        readFirstTag(properties, RouteProgressRegistry.TAG_OPERATOR_CODE, "FTA_OPERATOR")
            .orElse("-");
    String lineCode =
        readFirstTag(properties, RouteProgressRegistry.TAG_LINE_CODE, "FTA_LINE").orElse("-");
    String routeCode =
        readFirstTag(properties, RouteProgressRegistry.TAG_ROUTE_CODE, "FTA_ROUTE").orElse("-");
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

  private List<String> summarizeClaims(
      List<OccupancyClaim> claims,
      String currentTrainName,
      RouteDefinition route,
      int currentIndex,
      Set<OccupancyResource> authorityResources) {
    if (claims == null || claims.isEmpty()) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (OccupancyClaim claim : claims) {
      if (claim == null || claim.resource() == null) {
        continue;
      }
      result.add(
          formatClaimSummary(claim, currentTrainName, route, currentIndex, authorityResources));
      if (result.size() >= 12) {
        break;
      }
    }
    return List.copyOf(result);
  }

  private List<String> summarizeTrainClaims(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      Set<OccupancyResource> authorityResources,
      int limit) {
    if (occupancyManager == null || trainName == null || trainName.isBlank()) {
      return List.of();
    }
    int max = Math.max(1, limit);
    List<String> result = new ArrayList<>();
    for (OccupancyClaim claim : occupancyManager.snapshotClaims()) {
      if (claim == null || claim.resource() == null || claim.trainName() == null) {
        continue;
      }
      if (!claim.trainName().equalsIgnoreCase(trainName)) {
        continue;
      }
      result.add(formatClaimSummary(claim, trainName, route, currentIndex, authorityResources));
      if (result.size() >= max) {
        break;
      }
    }
    return List.copyOf(result);
  }

  private static List<String> summarizeResources(List<OccupancyResource> resources, int limit) {
    if (resources == null || resources.isEmpty()) {
      return List.of();
    }
    int max = Math.max(1, limit);
    List<String> result = new ArrayList<>();
    for (OccupancyResource resource : resources) {
      if (resource == null) {
        continue;
      }
      result.add(resource.kind() + ":" + resource.key());
      if (result.size() >= max) {
        break;
      }
    }
    return List.copyOf(result);
  }

  private String formatClaimSummary(
      OccupancyClaim claim,
      String currentTrainName,
      RouteDefinition route,
      int currentIndex,
      Set<OccupancyResource> authorityResources) {
    StringBuilder builder = new StringBuilder();
    builder.append(claim.resource().kind()).append(":").append(claim.resource().key());
    builder.append("@").append(claim.trainName());
    claim.routeId().ifPresent(routeId -> builder.append(" route=").append(routeId.value()));
    progressRegistry
        .get(claim.trainName())
        .ifPresent(entry -> builder.append(" idx=").append(entry.currentIndex()));
    builder
        .append(" role=")
        .append(classifyClaim(claim, currentTrainName, route, currentIndex, authorityResources));
    return builder.toString();
  }

  private String classifyClaim(
      OccupancyClaim claim,
      String currentTrainName,
      RouteDefinition route,
      int currentIndex,
      Set<OccupancyResource> authorityResources) {
    if (claim == null || claim.trainName() == null) {
      return "unknown";
    }
    if (currentTrainName != null && claim.trainName().equalsIgnoreCase(currentTrainName)) {
      return "self";
    }
    Optional<RouteProgressRegistry.RouteProgressEntry> ownerEntryOpt =
        progressRegistry.get(claim.trainName());
    if (ownerEntryOpt.isEmpty()) {
      return claim.routeId().isPresent() ? "depot_spawn" : "orphan";
    }
    if (route == null || currentIndex < 0) {
      return "other";
    }
    RouteProgressRegistry.RouteProgressEntry ownerEntry = ownerEntryOpt.get();
    if (!route.id().equals(ownerEntry.routeId())) {
      return "other_route";
    }
    boolean inAuthority =
        authorityResources != null
            && claim.resource() != null
            && authorityResources.contains(claim.resource());
    if (ownerEntry.currentIndex() < currentIndex && inAuthority) {
      return "speculative";
    }
    if (ownerEntry.currentIndex() <= currentIndex && !inAuthority) {
      return "rear_guard";
    }
    return "forward";
  }

  private void updateLiveBlockerSnapshot(
      String trainName,
      OccupancyDecision decision,
      OccupancyRequest request,
      Instant now,
      String source) {
    if (!liveBlockerSnapshotProgressFresh(trainName, request)) {
      return;
    }
    updateBlockerSnapshot(trainName, decision, request, now, source);
  }

  private boolean liveBlockerSnapshotProgressFresh(String trainName, OccupancyRequest request) {
    if (request == null || request.directedContext().isEmpty()) {
      return true;
    }
    DirectedTraversalContext context = request.directedContext().get();
    String resolvedTrain =
        trainName == null || trainName.isBlank() ? request.trainName() : trainName;
    Optional<RouteProgressRegistry.RouteProgressEntry> entryOpt =
        progressRegistry.get(resolvedTrain);
    if (entryOpt.isEmpty()) {
      traceLiveBlockerSnapshotRejected(
          resolvedTrain, context, "PROGRESS_CONTEXT_MISSING", -1L, null);
      return false;
    }
    RouteProgressRegistry.RouteProgressEntry entry = entryOpt.get();
    long currentProgressVersion = progressRegistry.version();
    // 异步占用结果只能写回产生它的同一进度窗口；Route index 不变时，中间图节点推进同样会使旧判定失效。
    boolean routeMoved =
        context.routeId().isPresent() && !context.routeId().get().equals(entry.routeId());
    boolean indexMoved =
        context.currentIndex() >= 0 && context.currentIndex() != entry.currentIndex();
    Optional<NodeId> requestProgressNode =
        context.lastPassedGraphNode().isPresent()
            ? context.lastPassedGraphNode()
            : context.currentNode();
    boolean graphNodeMoved =
        requestProgressNode.isPresent()
            && entry.lastPassedGraphNode().isPresent()
            && !requestProgressNode.get().equals(entry.lastPassedGraphNode().get());
    if (!routeMoved && !indexMoved && !graphNodeMoved) {
      return true;
    }
    traceLiveBlockerSnapshotRejected(
        resolvedTrain, context, "STALE_PROGRESS_CONTEXT", currentProgressVersion, entry);
    return false;
  }

  private void traceLiveBlockerSnapshotRejected(
      String trainName,
      DirectedTraversalContext context,
      String reason,
      long currentProgressVersion,
      RouteProgressRegistry.RouteProgressEntry currentEntry) {
    debugLogger.accept(
        "SMART_LIVE_BLOCKER_SNAPSHOT_REJECTED reason="
            + (reason == null || reason.isBlank() ? "UNKNOWN" : reason)
            + " train="
            + (trainName == null || trainName.isBlank() ? "-" : trainName)
            + " requestProgressVersion="
            + (context == null ? -1L : context.progressVersion())
            + " currentProgressVersion="
            + currentProgressVersion
            + " requestCurrentIndex="
            + (context == null ? -1 : context.currentIndex())
            + " currentIndex="
            + (currentEntry == null ? -1 : currentEntry.currentIndex())
            + " requestRouteId="
            + (context == null ? "-" : context.routeId().map(RouteId::value).orElse("-"))
            + " currentRouteId="
            + (currentEntry == null ? "-" : currentEntry.routeId().value())
            + " requestLastPassed="
            + (context == null
                ? "-"
                : context
                    .lastPassedGraphNode()
                    .or(context::currentNode)
                    .map(NodeId::value)
                    .orElse("-"))
            + " currentLastPassed="
            + (currentEntry == null
                ? "-"
                : currentEntry.lastPassedGraphNode().map(NodeId::value).orElse("-")));
  }

  private void updateBlockerSnapshot(String trainName, OccupancyDecision decision, Instant now) {
    updateBlockerSnapshot(trainName, decision, null, now, "runtime");
  }

  private void updateBlockerSnapshot(
      String trainName,
      OccupancyDecision decision,
      OccupancyRequest request,
      Instant now,
      String source) {
    String key = normalizeTrainKey(trainName);
    if (key.isEmpty()) {
      return;
    }
    if (decision == null || decision.blockers().isEmpty()) {
      blockerSnapshots.remove(key);
      return;
    }
    Set<DeadlockBlockerInfo> blockers = new LinkedHashSet<>();
    for (OccupancyClaim claim : decision.blockers()) {
      if (claim == null || claim.trainName() == null || claim.trainName().isBlank()) {
        continue;
      }
      String blocker = claim.trainName().trim();
      if (TrainNameNormalizer.sameLogicalTrain(blocker, trainName)) {
        continue;
      }
      OccupancyResource resource = claim.resource();
      String conflictKey = blockerConflictKey(resource);
      String resourceKey = snapshotResourceKey(resource);
      Optional<CorridorDirection> direction = claim.corridorDirection();
      String relation = snapshotRelation(request, resource, claim);
      String intent = snapshotIntent(request, resource);
      String role = claim.role() == null ? "UNKNOWN" : claim.role().name();
      String ownerCanonical = TrainNameNormalizer.normalizeKey(blocker);
      DeadlockBlockerInfo info =
          new DeadlockBlockerInfo(
              blocker,
              conflictKey,
              direction,
              ownerCanonical,
              resourceKey,
              relation,
              intent,
              role,
              source,
              currentSignalTraceTick(),
              occupancyVersion());
      blockers.add(info);
      SignalComputationTrace.emitRaw(
          "SMART_LIVE_BLOCKER_SNAPSHOT_UPDATED train="
              + trainName
              + " blockerTrain="
              + blocker
              + " blockerCanonical="
              + ownerCanonical
              + " resource="
              + resourceKey
              + " relation="
              + relation
              + " intent="
              + intent
              + " role="
              + role
              + " direction="
              + direction.map(Enum::name).orElse("UNKNOWN")
              + " source="
              + info.source(),
          debugLogger);
    }
    if (blockers.isEmpty()) {
      blockerSnapshots.remove(key);
      return;
    }
    Instant sampledAt = now == null ? Instant.now() : now;
    blockerSnapshots.put(
        key, new BlockerSnapshot(blockers, sampledAt, captureBlockerProgressWindow(trainName)));
  }

  private static String blockerConflictKey(OccupancyResource resource) {
    return resource != null && resource.kind() == ResourceKind.CONFLICT ? resource.key() : "";
  }

  private static String snapshotResourceKey(OccupancyResource resource) {
    if (resource == null) {
      return "-";
    }
    return resource.kind() + ":" + resource.key();
  }

  private static String snapshotRelation(
      OccupancyRequest request, OccupancyResource resource, OccupancyClaim claim) {
    return BlockerClassifier.classify(request, resource, claim).name();
  }

  private static String snapshotIntent(OccupancyRequest request, OccupancyResource resource) {
    if (request == null || resource == null) {
      return "UNKNOWN";
    }
    ResourceIntent intent = request.intentFor(resource);
    return intent == null ? "UNKNOWN" : intent.name();
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
