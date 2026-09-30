package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDestinationResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DispatchPriorityPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LaunchAuthorizationService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverDispatchResult;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeTrainHandle;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.ServiceTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TerminalKeyResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainNameFormatter;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainSpawnTagInitializer;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestBuilder;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 简单票据分配器。
 *
 * <p>这个类只负责“票据到列车”的执行，不重新规划时刻表，也不接管运行时信号控制。它会先尝试复用 {@link LayoverRegistry} 中的待命列车，失败后再走 Depot
 * 出车；所有失败都回退到重试或 pending 队列，不直接修改运行时占用/信号状态。
 *
 * <p>真正的运行控制仍由 {@link RuntimeDispatchService} 负责。
 */
public final class SimpleTicketAssigner implements TicketAssigner {

  enum MaterializedSpawnProgress {
    WAIT,
    COMPLETE,
    ROLLBACK
  }

  enum PendingMaterializedSpawnPhase {
    AWAITING_PROMOTION,
    ROLLBACK_REQUIRED,
    AWAITING_REMOVAL
  }

  /** 按票据与精确物理身份区分实体化事务，避免额外实体覆盖唯一票据 owner。 */
  private static final class MaterializedSpawnKey {
    private final UUID ticketId;
    private final Object physicalIdentity;

    private MaterializedSpawnKey(UUID ticketId, RuntimeTrainHandle train) {
      this.ticketId = Objects.requireNonNull(ticketId, "ticketId");
      RuntimeTrainHandle requiredTrain = Objects.requireNonNull(train, "train");
      Object identity = requiredTrain.physicalRuntimeIdentity();
      this.physicalIdentity = identity == null ? requiredTrain : identity;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof MaterializedSpawnKey key
          && ticketId.equals(key.ticketId)
          && physicalIdentity == key.physicalIdentity;
    }

    @Override
    public int hashCode() {
      return 31 * ticketId.hashCode() + System.identityHashCode(physicalIdentity);
    }
  }

  private static final java.util.logging.Logger HEALTH_LOGGER =
      java.util.logging.Logger.getLogger("FetaruteTCAddon");

  /** 列车已完成运营圈数（按 OPERATION 票据发车成功累计）。 */
  /**
   * 车辆复用闸：回答"这辆车还能不能再接一班运营车次"。
   *
   * <p>默认恒放行，因此未装配时本类行为与之前完全一致。时刻表层会装上一个按车辆交路（duty）判定的实现， 用来保证"每辆车最终都会回库"——交路额度用完的车会被拒绝复用，从而落进
   * {@code ReclaimManager} 的闲置回收窗口，由既有回收链路派 RETURN 票送它回库。本类不负责送车回库，只负责不再给它派活。
   */
  private volatile java.util.function.Predicate<String> layoverReuseGate = trainName -> true;

  /** RETURN 票的复用闸：交路还没跑完的车不准被回库票带走。默认恒放行。 */
  private volatile java.util.function.Predicate<String> returnReuseGate = trainName -> true;

  /** 票据级候选过滤：这辆待命车能不能接这张票（时刻表用它把"接班"限定在本交路的车）。默认恒放行。 */
  private volatile java.util.function.BiPredicate<SpawnTicket, String> layoverCandidateFilter =
      (ticket, trainName) -> true;

  /** 票据级到期时刻：过了它还挂在 pending 里的票直接作废。默认没有到期。 */
  private volatile java.util.function.Function<SpawnTicket, Optional<Instant>> ticketExpiry =
      ticket -> Optional.empty();

  /** 派发成功回调：票据最终派给了哪辆车。默认什么都不做。 */
  private volatile java.util.function.BiConsumer<SpawnTicket, String> dispatchListener =
      (ticket, trainName) -> {};

  static final String TAG_OPERATION_TRIPS = "FTA_OP_TRIPS";

  /** 列车最大运营圈数（达到后应优先分配 RETURN 回库）。 */
  static final String TAG_MAX_OPERATION_TRIPS = "FTA_OP_MAX";

  /** 列车绑定的交路组名（用于诊断）。 */
  static final String TAG_CIRCULATION_GROUP = "FTA_SPAWN_GROUP";

  /** 待复用票据刷新窗口（秒）：超过后会刷新等待窗口并记录告警，避免长期静默卡死。 */
  private static final long PENDING_LAYOVER_REFRESH_SECONDS = 300L;

  /** 待复用票据默认最大保留时间；配置缺失时使用，0 表示显式禁用硬清理。 */
  private static final Duration DEFAULT_PENDING_LAYOVER_MAX_AGE = Duration.ofDays(1);

  /** 新物理编组等待 TrainCarts 提供完整实时 rail footprint 的最长宽限。 */
  private static final Duration MATERIALIZED_SPAWN_HYDRATION_GRACE = Duration.ofSeconds(4);

  /** 拥挤门控状态保留时长（超过后会自动清理）。 */
  private static final Duration CONGESTION_GATE_TTL = Duration.ofMinutes(10);

  /**
   * 待复用票据条目：记录票据及其加入时间，用于超时清理。
   *
   * @param ticket 原始出车票据
   * @param addedAt 当前等待窗口开始时间
   * @param firstAddedAt 首次进入等待队列的时间
   */
  private record PendingLayoverEntry(SpawnTicket ticket, Instant addedAt, Instant firstAddedAt) {
    private PendingLayoverEntry(SpawnTicket ticket, Instant addedAt) {
      this(ticket, addedAt, addedAt);
    }

    private PendingLayoverEntry {
      firstAddedAt = firstAddedAt == null ? addedAt : firstAddedAt;
    }

    private PendingLayoverEntry refreshedAt(Instant now) {
      return new PendingLayoverEntry(ticket, now, firstAddedAt);
    }
  }

  /**
   * 待复用派发快照：缓存一次派发中所需的 route 与分组信息，避免重复查询。
   *
   * @param ticket 原始票据
   * @param service 发车服务
   * @param route 线路定义
   * @param terminalKey 复用匹配终端（首站节点）
   * @param groupKey 轮转分组键（line + terminal）
   * @param addedAt 进入 pending 的时间
   */
  private record PendingLayoverDispatchEntry(
      SpawnTicket ticket,
      SpawnService service,
      RouteDefinition route,
      String terminalKey,
      String groupKey,
      Instant addedAt) {}

  /**
   * 已实体化且尚未完成票据提交或安全回滚的发车事务。
   *
   * <p>{@link PendingMaterializedSpawnPhase#AWAITING_PROMOTION} 才允许刷新信号并查询真实 footprint；一旦转入 {@link
   * PendingMaterializedSpawnPhase#ROLLBACK_REQUIRED}，后续 tick 只能重试硬停车、销毁和账务回滚。账务动作完成后进入 {@link
   * PendingMaterializedSpawnPhase#AWAITING_REMOVAL}，继续保留 ticket guard 并重试物理销毁，直到 runtime 收到精确
   * GroupRemove；两个回滚阶段都不能重新进入 promotion 或票据完成路径。
   */
  private record PendingMaterializedSpawn(
      SpawnTicket ticket,
      SpawnService service,
      String trainName,
      SpawnControl.Lease spawnLease,
      RuntimeTrainHandle train,
      long recoveryEpoch,
      Instant deadline,
      boolean fallback,
      boolean ticketOwner,
      PendingMaterializedSpawnPhase phase,
      String rollbackReason) {

    private PendingMaterializedSpawn {
      Objects.requireNonNull(ticket, "ticket");
      Objects.requireNonNull(service, "service");
      Objects.requireNonNull(trainName, "trainName");
      Objects.requireNonNull(spawnLease, "spawnLease");
      Objects.requireNonNull(train, "train");
      Objects.requireNonNull(deadline, "deadline");
      phase = phase == null ? PendingMaterializedSpawnPhase.AWAITING_PROMOTION : phase;
      rollbackReason = rollbackReason == null ? "-" : rollbackReason;
    }

    private PendingMaterializedSpawn requiringRollback(String reason) {
      if (phase == PendingMaterializedSpawnPhase.AWAITING_REMOVAL) {
        return this;
      }
      return new PendingMaterializedSpawn(
          ticket,
          service,
          trainName,
          spawnLease,
          train,
          recoveryEpoch,
          deadline,
          fallback,
          ticketOwner,
          PendingMaterializedSpawnPhase.ROLLBACK_REQUIRED,
          reason);
    }

    private PendingMaterializedSpawn awaitingRemoval() {
      return new PendingMaterializedSpawn(
          ticket,
          service,
          trainName,
          spawnLease,
          train,
          recoveryEpoch,
          deadline,
          fallback,
          ticketOwner,
          PendingMaterializedSpawnPhase.AWAITING_REMOVAL,
          rollbackReason);
    }

    private MaterializedSpawnKey key() {
      return new MaterializedSpawnKey(ticket.id(), train);
    }
  }

  /**
   * 已生成物理编组后，提交 Depot 发车事务所需的不可变上下文。
   *
   * <p>常规发车与 Layover fallback 都必须经过同一提交器。这样两条入口会一致地执行 recovery epoch 检查、硬授权、 expected physical
   * identity 登记、footprint promotion 以及失败回滚，不能因复制实现而出现一条入口重新引入“新车被视为迟加载”的时序漏洞。
   */
  private record MaterializedDepotSpawnContext(
      StorageProvider provider,
      SpawnTicket ticket,
      SpawnService service,
      RouteDefinition route,
      RouteOperationType operationType,
      String trainName,
      SpawnControl.Lease spawnLease,
      DepotGateRequest gateRequest,
      OccupancyRequest authorityRequest,
      List<SpawnDepot> lineDepots,
      DepotSpawner.MaterializedSpawn materializedSpawn,
      long recoveryEpoch,
      Instant now,
      boolean fallback) {

    private MaterializedDepotSpawnContext {
      // 此对象仅在精确物理 runtime identity 已创建后构造。不能在这里做会逃离回滚边界的校验；
      // finalizer 会把字段访问或 TrainCarts 调用的任何异常统一转入物理收容路径。
      lineDepots =
          lineDepots == null ? List.of() : lineDepots.stream().filter(Objects::nonNull).toList();
    }
  }

  /**
   * 已通过 Depot 发车预检、尚未创建物理编组的不可变上下文。
   *
   * <p>常规发车和 Layover fallback 必须共用这一上下文。它只承载已经建立的逻辑发车准备：Depot 选择、动态授权准备、预览联锁判定与 recovery epoch；它不包含
   * {@link RuntimeTrainHandle}，因此不能触发 TrainCarts 实体化或绕开后续的统一提交与回滚。
   */
  private record PreparedDepotSpawn(
      SpawnTicket ticket,
      String trainName,
      SpawnControl.Lease spawnLease,
      List<SpawnDepot> lineDepots,
      DepotGateRequest gateRequest,
      long recoveryEpoch) {

    private PreparedDepotSpawn {
      ticket = Objects.requireNonNull(ticket, "ticket");
      trainName = Objects.requireNonNull(trainName, "trainName");
      spawnLease = Objects.requireNonNull(spawnLease, "spawnLease");
      lineDepots =
          lineDepots == null ? List.of() : lineDepots.stream().filter(Objects::nonNull).toList();
      gateRequest = Objects.requireNonNull(gateRequest, "gateRequest");
    }
  }

  /** Depot 发车入口的来源语义。 */
  private enum DepotSpawnOrigin {
    NORMAL(""),
    FALLBACK("fallback-");

    private final String reasonPrefix;

    DepotSpawnOrigin(String reasonPrefix) {
      this.reasonPrefix = reasonPrefix;
    }

    private boolean fallback() {
      return this == FALLBACK;
    }

    private String reasonPrefix() {
      return reasonPrefix;
    }
  }

  /**
   * 拥挤度评估快照。
   *
   * <p>score 范围为 [0,1]，值越高表示越拥挤。
   */
  private record CongestionAssessment(
      double score,
      double occupancyRate,
      double routeTrainPressure,
      double lineSignalPressure,
      double networkPressure,
      int busyEdges,
      int totalEdges,
      int busyNodes,
      int totalNodes,
      int activeRouteTrains,
      int targetRouteTrains,
      int activeTrains,
      /** “全网算满”的参考车数，**不是**准入上限。 */
      int networkReference) {

    int busyResources() {
      return busyEdges + busyNodes;
    }

    int totalResources() {
      return totalEdges + totalNodes;
    }
  }

  /** 拥挤门控状态（按 line+方向 key）。 */
  private record CongestionGateState(boolean holding, double lastScore, Instant updatedAt) {}

  private final SpawnManager spawnManager;
  private final DepotSpawner depotSpawner;
  private final OccupancyManager occupancyManager;
  private final LaunchAuthorizationService launchAuthorizationService;
  private final RailGraphService railGraphService;
  private final RouteDefinitionCache routeDefinitions;
  private final RuntimeDispatchService runtimeDispatchService;
  private final ConfigManager configManager;
  private final SignNodeRegistry signNodeRegistry;
  private final SpawnControl spawnControl;
  private final DepotDispatchCoordinator depotDispatchCoordinator;
  private final Consumer<String> debugLogger;

  private final LayoverRegistry layoverRegistry;
  private final Duration retryDelay;
  private final int maxSpawnPerTick;
  private final int maxRetryAttempts;

  /**
   * 本 tick 已经实体化（调用 {@link DepotSpawner#spawn}）的次数。{@code max-spawn-per-tick} 限的就是它。
   *
   * <p>限的是"生成了几辆车"而不是"试了几张票"：被闭塞挡在预检的票、折返复用的票都不生成实体，不占名额。
   * 若按尝试计，每拍唯一的名额会反复落在同一张出不了库的票上（例如被车库咽喉挡住的出库票），全网其余的票（含终点折返）一张都轮不到。
   */
  private int materializationsThisTick;

  /** 出车成功次数（含 Layover 复用）。 */
  private final java.util.concurrent.atomic.LongAdder spawnSuccess =
      new java.util.concurrent.atomic.LongAdder();

  /** 出车重试次数（requeue 计数）。 */
  private final java.util.concurrent.atomic.LongAdder spawnRetries =
      new java.util.concurrent.atomic.LongAdder();

  private final java.util.concurrent.ConcurrentMap<String, java.util.concurrent.atomic.LongAdder>
      requeueByError = new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.ConcurrentMap<String, Long> lastWarnAtMs =
      new java.util.concurrent.ConcurrentHashMap<>();
  // key 为 ticketId：避免同一 route 在 backlog>1 时覆盖导致“丢票据/永久卡 backlog”。
  private final java.util.Map<java.util.UUID, PendingLayoverEntry> pendingLayoverTickets =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.Map<MaterializedSpawnKey, PendingMaterializedSpawn>
      pendingMaterializedSpawns = new java.util.concurrent.ConcurrentHashMap<>();
  // key 为 "<lineId>|<terminal>"：记录下一次优先尝试的 route 游标，实现同组 route 轮转。
  private final java.util.concurrent.ConcurrentMap<String, Integer> pendingLayoverRouteCursor =
      new java.util.concurrent.ConcurrentHashMap<>();
  private volatile StorageProvider lastStorageProvider;
  // key 为 "<lineId>|<terminal>"：记录即时复用路径（非 pending）的 route 轮转游标。
  private final java.util.concurrent.ConcurrentMap<String, Integer> immediateLayoverRouteCursor =
      new java.util.concurrent.ConcurrentHashMap<>();
  // key 为 "<lineId>|<direction>"：记录该方向当前是否触发拥挤 HOLD。
  private final java.util.concurrent.ConcurrentMap<String, CongestionGateState> congestionGates =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.atomic.AtomicLong lastCongestionCleanupMs =
      new java.util.concurrent.atomic.AtomicLong(0L);
  // key 为 lineId：同负载 depot 的轮转游标。负载统计在同一 tick 内可能还看不到刚生成的车，
  // 因此需要一个轻量游标防止连续票据都选中配置列表第一个 depot。
  private final java.util.concurrent.ConcurrentMap<UUID, java.util.concurrent.atomic.AtomicInteger>
      depotSelectionCursors = new java.util.concurrent.ConcurrentHashMap<>();
  // key 为 "<lineId>|<dynamicDepotSpec>"：DYNAMIC depot 内实际股道轮转游标。
  private final java.util.concurrent.ConcurrentMap<
          String, java.util.concurrent.atomic.AtomicInteger>
      dynamicDepotSelectionCursors = new java.util.concurrent.ConcurrentHashMap<>();

  public SimpleTicketAssigner(
      SpawnManager spawnManager,
      DepotSpawner depotSpawner,
      OccupancyManager occupancyManager,
      RailGraphService railGraphService,
      RouteDefinitionCache routeDefinitions,
      RuntimeDispatchService runtimeDispatchService,
      ConfigManager configManager,
      SignNodeRegistry signNodeRegistry,
      org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry layoverRegistry,
      Consumer<String> debugLogger,
      Duration retryDelay,
      int maxSpawnPerTick,
      int maxRetryAttempts) {
    this(
        spawnManager,
        depotSpawner,
        occupancyManager,
        railGraphService,
        routeDefinitions,
        runtimeDispatchService,
        configManager,
        signNodeRegistry,
        layoverRegistry,
        new SpawnControl(),
        debugLogger,
        retryDelay,
        maxSpawnPerTick,
        maxRetryAttempts);
  }

  public SimpleTicketAssigner(
      SpawnManager spawnManager,
      DepotSpawner depotSpawner,
      OccupancyManager occupancyManager,
      RailGraphService railGraphService,
      RouteDefinitionCache routeDefinitions,
      RuntimeDispatchService runtimeDispatchService,
      ConfigManager configManager,
      SignNodeRegistry signNodeRegistry,
      org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry layoverRegistry,
      SpawnControl spawnControl,
      Consumer<String> debugLogger,
      Duration retryDelay,
      int maxSpawnPerTick,
      int maxRetryAttempts) {
    this.spawnManager = Objects.requireNonNull(spawnManager, "spawnManager");
    this.depotSpawner = Objects.requireNonNull(depotSpawner, "depotSpawner");
    this.occupancyManager = Objects.requireNonNull(occupancyManager, "occupancyManager");
    this.railGraphService = Objects.requireNonNull(railGraphService, "railGraphService");
    this.routeDefinitions = Objects.requireNonNull(routeDefinitions, "routeDefinitions");
    this.runtimeDispatchService =
        Objects.requireNonNull(runtimeDispatchService, "runtimeDispatchService");
    this.configManager = Objects.requireNonNull(configManager, "configManager");
    this.signNodeRegistry = Objects.requireNonNull(signNodeRegistry, "signNodeRegistry");
    this.layoverRegistry = Objects.requireNonNull(layoverRegistry, "layoverRegistry");
    this.spawnControl = Objects.requireNonNull(spawnControl, "spawnControl");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
    this.launchAuthorizationService =
        new LaunchAuthorizationService(occupancyManager, null, this.debugLogger);
    Duration configuredRetryDelay = retryDelay == null ? Duration.ofSeconds(2) : retryDelay;
    this.retryDelay =
        configuredRetryDelay.compareTo(Duration.ofMillis(50)) < 0
            ? Duration.ofMillis(50)
            : configuredRetryDelay;
    this.depotDispatchCoordinator = new DepotSpawnScheduler(this.retryDelay);
    this.maxSpawnPerTick = Math.max(1, maxSpawnPerTick);
    this.maxRetryAttempts = Math.max(1, maxRetryAttempts);
  }

  public boolean forceAssign(String trainName, ServiceTicket ticket) {
    debugLogger.accept("强制分配失败: 缺少 StorageProvider，无法执行 SpawnControl 容量判定 train=" + trainName);
    return false;
  }

  @Override
  public boolean forceAssign(StorageProvider provider, String trainName, ServiceTicket ticket) {
    if (provider == null) {
      debugLogger.accept("强制分配失败: 缺少 StorageProvider，无法执行 SpawnControl 容量判定 train=" + trainName);
      return false;
    }
    // 在 LayoverRegistry 中查找候选列车
    Optional<LayoverRegistry.LayoverCandidate> candidateOpt = layoverRegistry.get(trainName);
    if (candidateOpt.isEmpty()) {
      debugLogger.accept("强制分配失败: 未找到 Layover 列车 " + trainName);
      return false;
    }

    LayoverRegistry.LayoverCandidate candidate = candidateOpt.get();
    SpawnControl.Lease lease =
        tryAcquireSpawnControlForLayover(
                Optional.ofNullable(provider),
                ticket,
                SpawnControl.LeaseKind.RECLAIM_RETURN,
                Optional.of(candidate),
                Instant.now())
            .orElse(null);
    if (lease == null) {
      return false;
    }

    // 尝试复用发车
    LayoverDispatchResult dispatch = runtimeDispatchService.dispatchLayover(candidate, ticket);
    if (dispatch.dispatched()) {
      String committedTrainName = dispatch.trainName().orElseThrow();
      RouteOperationType operationType =
          resolveRouteOperationType(Optional.of(provider), ticket.routeId())
              .orElse(
                  ticket.mode() == ServiceTicket.TicketMode.RETURN
                      ? RouteOperationType.RETURN
                      : RouteOperationType.OPERATION);
      applyDispatchLifecycleTags(
          Optional.of(provider), committedTrainName, ticket.routeId(), operationType);
      debugLogger.accept("强制分配成功: " + trainName + " -> ticket " + ticket.ticketId());
      return true;
    }

    lease.release();
    debugLogger.accept("强制分配失败: dispatchLayover 拒绝 " + trainName);
    return false;
  }

  /**
   * Layover 注册事件：有新列车可复用时尝试立刻派发待处理票据。
   *
   * <p>用于减少 reuse-not-ready 的轮询等待。
   */
  @Override
  public void onLayoverRegistered(LayoverRegistry.LayoverCandidate candidate) {
    if (candidate == null) {
      return;
    }
    StorageProvider provider = lastStorageProvider;
    if (provider == null) {
      debugLogger.accept("Layover 即时复用跳过: 缺少 StorageProvider，等待下一轮 spawn tick");
      return;
    }
    tryDispatchPendingLayover(
        Instant.now(), Optional.of(provider), Optional.of(candidate.terminalKey()));
  }

  @Override
  public List<SpawnTicket> snapshotPendingTickets() {
    if (pendingLayoverTickets.isEmpty()) {
      return List.of();
    }
    return pendingLayoverTickets.values().stream()
        .map(PendingLayoverEntry::ticket)
        .filter(Objects::nonNull)
        .toList();
  }

  @Override
  public int clearPendingTickets() {
    int removed = 0;
    for (var entry : List.copyOf(pendingLayoverTickets.entrySet())) {
      PendingLayoverEntry pending = entry.getValue();
      SpawnTicket ticket = pending == null ? null : pending.ticket();
      if (preservePendingDispatchAttempt(ticket, "manual-clear")) {
        continue;
      }
      if (pendingLayoverTickets.remove(entry.getKey(), pending)) {
        removed++;
      }
    }
    pendingLayoverRouteCursor.clear();
    immediateLayoverRouteCursor.clear();
    return removed;
  }

  /**
   * 把全部已实体化事务切换为只回滚状态，并同步尝试一次物理收容。
   *
   * <p>返回失败时调用方不得替换本 assigner；否则内存中的物理 identity、租约与票据 owner 会失去恢复者。
   */
  @Override
  public boolean prepareForReplacement(Instant now) {
    Instant recoveryAt = now == null ? Instant.now() : now;
    List<PendingMaterializedSpawn> snapshot = List.copyOf(pendingMaterializedSpawns.values());
    List<PendingMaterializedSpawn> rollbackSnapshot = new ArrayList<>();
    for (PendingMaterializedSpawn pending : snapshot) {
      if (pending == null) {
        continue;
      }
      if (pending.phase() == PendingMaterializedSpawnPhase.AWAITING_REMOVAL) {
        rollbackSnapshot.add(pending);
        continue;
      }
      PendingMaterializedSpawn rollback =
          pending.phase() == PendingMaterializedSpawnPhase.ROLLBACK_REQUIRED
              ? pending
              : pending.requiringRollback("ticket-assigner-replacement");
      if (rollback != pending
          && !pendingMaterializedSpawns.replace(pending.key(), pending, rollback)) {
        continue;
      }
      rollbackSnapshot.add(rollback);
    }
    boolean allQuarantined = true;
    for (PendingMaterializedSpawn pending : rollbackSnapshot) {
      if (pending.phase() == PendingMaterializedSpawnPhase.AWAITING_REMOVAL) {
        continue;
      }
      RuntimeTrainHandle handle = pending.train();
      try {
        if (handle.isValid()
            && !runtimeDispatchService.quarantineMaterializedSpawnRollback(
                handle, pending.trainName(), pending.rollbackReason(), pending.ticketOwner())) {
          allQuarantined = false;
        }
      } catch (RuntimeException | LinkageError failure) {
        allQuarantined = false;
        try {
          debugLogger.accept(
              "替换前登记实体化回滚隔离失败: train="
                  + pending.trainName()
                  + " error="
                  + failure.getClass().getSimpleName()
                  + ":"
                  + String.valueOf(failure.getMessage()));
        } catch (RuntimeException | LinkageError logFailure) {
          HEALTH_LOGGER.warning("替换前回滚隔离日志写入失败: " + logFailure.getClass().getSimpleName());
        }
      }
    }
    if (!allQuarantined) {
      return false;
    }
    for (PendingMaterializedSpawn pending : rollbackSnapshot) {
      if (pending.phase() == PendingMaterializedSpawnPhase.AWAITING_REMOVAL) {
        retryPendingMaterializedSpawnRemoval(pending);
      } else {
        retryPendingMaterializedSpawnRollback(pending, recoveryAt);
      }
    }
    return pendingMaterializedSpawns.isEmpty();
  }

  @Override
  public void resetDiagnostics() {
    spawnSuccess.reset();
    spawnRetries.reset();
    requeueByError.clear();
    lastWarnAtMs.clear();
  }

  @Override
  public void tick(StorageProvider provider, Instant now) {
    if (provider == null || now == null) {
      return;
    }
    lastStorageProvider = provider;
    materializationsThisTick = 0;
    spawnControl.pruneExpired(now);
    cleanupStaleCongestionGates(now);
    advancePendingMaterializedSpawns(now);
    Map<String, Integer> selectedDepotsThisTick = new HashMap<>();
    if (!pendingLayoverTickets.isEmpty()) {
      refreshExpiredPendingTickets(provider, now, selectedDepotsThisTick);
      tryDispatchPendingLayover(now, Optional.of(provider), Optional.empty());
    }
    List<SpawnTicket> dueTickets = spawnManager.pollDueTickets(provider, now);
    if (dueTickets.isEmpty()) {
      return;
    }
    dueTickets = orderDueTicketsWithRouteRotation(dueTickets);
    dueTickets = applyDepotDispatchCoordination(provider, dueTickets, selectedDepotsThisTick, now);
    dueTickets = orderDepotTicketsByLineDepotLoad(provider, dueTickets);
    // 每张票都试一次；实体化名额在 materializePreparedDepotSpawn 里扣，名额用完的出库票在那里延后。
    for (SpawnTicket ticket : dueTickets) {
      if (ticket != null) {
        trySpawn(provider, now, ticket, selectedDepotsThisTick);
      }
    }
  }

  /** 本 tick 还能不能再实体化一辆车。 */
  private boolean spawnBudgetLeft() {
    return materializationsThisTick < maxSpawnPerTick;
  }

  /** 推进已实体化发车事务；只有真实 footprint promotion 后才提交票据。 */
  private void advancePendingMaterializedSpawns(Instant now) {
    if (pendingMaterializedSpawns.isEmpty()) {
      return;
    }
    for (PendingMaterializedSpawn pending : List.copyOf(pendingMaterializedSpawns.values())) {
      if (pending == null || pending.ticket() == null || pending.train() == null) {
        continue;
      }
      if (pending.phase() == PendingMaterializedSpawnPhase.AWAITING_REMOVAL) {
        retryPendingMaterializedSpawnRemoval(pending);
        continue;
      }
      if (!shouldEvaluateMaterializedSpawnPromotion(pending.phase())) {
        retryPendingMaterializedSpawnRollback(pending, now);
        continue;
      }
      if (!runtimeDispatchService.isStartupRecoveryEpochReady(pending.recoveryEpoch())) {
        failPendingMaterializedSpawn(
            pending, now, "startup-recovery-epoch-changed-before-pending-refresh");
        continue;
      }
      RuntimeTrainHandle handle = pending.train();
      if (!handle.isValid()) {
        failPendingMaterializedSpawn(pending, now, "physical-group-invalid");
        continue;
      }
      RuntimeDispatchService.ExpectedMaterializedSpawnStatus status;
      try {
        runtimeDispatchService.refreshSignal(handle);
        status =
            runtimeDispatchService.expectedMaterializedSpawnStatus(handle, pending.recoveryEpoch());
      } catch (RuntimeException | LinkageError failure) {
        debugLogger.accept(
            "SMART_EXPECTED_SPAWN_PHYSICAL_REGISTRATION result=refresh-exception train="
                + pending.trainName()
                + " error="
                + failure.getClass().getSimpleName()
                + ":"
                + String.valueOf(failure.getMessage()));
        failPendingMaterializedSpawn(
            pending, now, "pending-hydration-refresh-failed:" + failure.getClass().getSimpleName());
        continue;
      }
      MaterializedSpawnProgress progress =
          materializedSpawnProgress(status, now, pending.deadline());
      if (progress == MaterializedSpawnProgress.COMPLETE) {
        completePendingMaterializedSpawn(pending, now);
        continue;
      }
      if (progress == MaterializedSpawnProgress.WAIT) {
        continue;
      }
      String reason =
          status == RuntimeDispatchService.ExpectedMaterializedSpawnStatus.PROVISIONAL
              ? "physical-footprint-hydration-timeout"
              : "physical-footprint-hydration-state-lost";
      if (status == RuntimeDispatchService.ExpectedMaterializedSpawnStatus.PROVISIONAL) {
        debugLogger.accept(
            "SMART_EXPECTED_SPAWN_PHYSICAL_REGISTRATION result=timeout train="
                + pending.trainName()
                + " epoch="
                + pending.recoveryEpoch()
                + " deadline="
                + pending.deadline());
      }
      failPendingMaterializedSpawn(pending, now, reason);
    }
  }

  /** 根据认证状态与宽限截止时间决定票据继续等待、提交或回滚。 */
  static MaterializedSpawnProgress materializedSpawnProgress(
      RuntimeDispatchService.ExpectedMaterializedSpawnStatus status,
      Instant now,
      Instant deadline) {
    if (status == RuntimeDispatchService.ExpectedMaterializedSpawnStatus.PROMOTED) {
      return MaterializedSpawnProgress.COMPLETE;
    }
    if (status == RuntimeDispatchService.ExpectedMaterializedSpawnStatus.PROVISIONAL
        && now != null
        && deadline != null
        && now.isBefore(deadline)) {
      return MaterializedSpawnProgress.WAIT;
    }
    return MaterializedSpawnProgress.ROLLBACK;
  }

  /** 只有尚未决定回滚的事务可以刷新信号并继续 footprint promotion。 */
  static boolean shouldEvaluateMaterializedSpawnPromotion(PendingMaterializedSpawnPhase phase) {
    return phase == PendingMaterializedSpawnPhase.AWAITING_PROMOTION;
  }

  private void completePendingMaterializedSpawn(PendingMaterializedSpawn pending, Instant now) {
    if (!pending.ticketOwner() || !pendingMaterializedSpawns.remove(pending.key(), pending)) {
      return;
    }
    if (!runtimeDispatchService.isStartupRecoveryEpochReady(pending.recoveryEpoch())) {
      retainAndRetryMaterializedSpawnRollback(
          pending.requiringRollback("startup-recovery-epoch-changed-before-pending-complete"), now);
      return;
    }
    try {
      notifyDispatched(pending.ticket(), pending.trainName());
      spawnManager.complete(pending.ticket());
    } catch (RuntimeException | LinkageError failure) {
      debugLogger.accept(
          "发车票据提交异常: train="
              + pending.trainName()
              + " error="
              + failure.getClass().getSimpleName()
              + ":"
              + String.valueOf(failure.getMessage()));
      retainAndRetryMaterializedSpawnRollback(
          pending.requiringRollback(
              "pending-ticket-complete-failed:" + failure.getClass().getSimpleName()),
          now);
      return;
    }
    clearCompletedMaterializedSpawnMarker(pending.train(), pending.trainName());
    recordMaterializedSpawnSuccess(
        pending.ticket(), pending.service(), pending.trainName(), pending.fallback());
  }

  /**
   * 在票据已经提交后清除跨重启事务墓碑。
   *
   * <p>清理异常不能把已提交票据重新回队；保留墓碑会让下一次启动 fail-closed 地收容该编组，并留下明确诊断。
   */
  private void clearCompletedMaterializedSpawnMarker(RuntimeTrainHandle train, String trainName) {
    try {
      TrainProperties properties = Objects.requireNonNull(train, "train").properties();
      TrainSpawnTagInitializer.clearMaterializedSpawnTransactionPending(
          Objects.requireNonNull(properties, "properties"));
      if (TrainTagHelper.readTagValue(
              properties, TrainSpawnTagInitializer.TAG_MATERIALIZED_ROLLBACK_PENDING)
          .isPresent()) {
        debugLogger.accept("实体化发车票据已提交但事务墓碑仍存在，将在下次启动收容: train=" + trainName);
      }
    } catch (RuntimeException | LinkageError failure) {
      debugLogger.accept(
          "实体化发车票据已提交但事务墓碑清理失败，将在下次启动收容: train="
              + trainName
              + " error="
              + failure.getClass().getSimpleName()
              + ":"
              + String.valueOf(failure.getMessage()));
    }
  }

  private void failPendingMaterializedSpawn(
      PendingMaterializedSpawn pending, Instant now, String reason) {
    PendingMaterializedSpawn rollback = pending.requiringRollback(reason);
    if (!pendingMaterializedSpawns.replace(pending.key(), pending, rollback)) {
      return;
    }
    retryPendingMaterializedSpawnRollback(rollback, now);
  }

  private void retainAndRetryMaterializedSpawnRollback(
      PendingMaterializedSpawn rollback, Instant now) {
    PendingMaterializedSpawn tracked = rollback;
    PendingMaterializedSpawn previous =
        pendingMaterializedSpawns.putIfAbsent(rollback.key(), rollback);
    if (previous != null) {
      tracked = previous.requiringRollback(rollback.rollbackReason());
      if (!pendingMaterializedSpawns.replace(rollback.key(), previous, tracked)) {
        return;
      }
    }
    retryPendingMaterializedSpawnRollback(tracked, now);
  }

  private void retryPendingMaterializedSpawnRollback(
      PendingMaterializedSpawn pending, Instant now) {
    if (pending.phase() != PendingMaterializedSpawnPhase.ROLLBACK_REQUIRED
        || pendingMaterializedSpawns.get(pending.key()) != pending) {
      return;
    }
    RuntimeTrainHandle handle = pending.train();
    if (!handle.isValid()) {
      if (runtimeDispatchService.consumeMaterializedSpawnRollbackRemoval(handle)) {
        // 真实 GroupRemove 已先于本次状态推进到达；物理收容已经完成，可以继续回滚账务。
      } else if (runtimeDispatchService.hasMaterializedSpawnRollbackQuarantine(
          pending.trainName())) {
        return;
      } else {
        runtimeDispatchService.quarantineMaterializedSpawnRollback(
            handle, pending.trainName(), pending.rollbackReason(), pending.ticketOwner());
        debugLogger.accept(
            "实体化编组句柄失效但尚无 GroupRemove 证明，保留物理隔离: train="
                + pending.trainName()
                + " unloaded="
                + runtimeDispatchService.isMaterializedSpawnRollbackUnloaded(handle));
        return;
      }
    }
    Optional<RuntimeTrainHandle> liveTrain =
        handle.isValid() ? Optional.of(handle) : Optional.empty();
    boolean contained;
    try {
      if (liveTrain.isPresent()
          && !runtimeDispatchService.quarantineMaterializedSpawnRollback(
              handle, pending.trainName(), pending.rollbackReason(), pending.ticketOwner())) {
        handle.stopHard();
        debugLogger.accept("已实体化发车无法登记 runtime 回滚隔离，保持硬停并等待重试: train=" + pending.trainName());
        return;
      }
      contained =
          pending.ticketOwner()
              ? abortDepotSpawnForRecovery(
                  pending.ticket(),
                  now,
                  pending.trainName(),
                  pending.spawnLease(),
                  liveTrain,
                  pending.rollbackReason())
              : containDuplicateMaterializedSpawn(liveTrain, pending.trainName());
    } catch (RuntimeException | LinkageError failure) {
      contained = false;
      debugLogger.accept(
          "已实体化发车恢复重试异常，继续保留隔离记录: train="
              + pending.trainName()
              + " error="
              + failure.getClass().getSimpleName()
              + ":"
              + String.valueOf(failure.getMessage()));
    }
    if (contained) {
      PendingMaterializedSpawn awaitingRemoval = pending.awaitingRemoval();
      if (pendingMaterializedSpawns.replace(pending.key(), pending, awaitingRemoval)) {
        retryPendingMaterializedSpawnRemoval(awaitingRemoval);
      }
      return;
    }
    debugLogger.accept(
        "SMART_EXPECTED_SPAWN_PHYSICAL_REGISTRATION result=rollback-retained train="
            + pending.trainName()
            + " ticket="
            + pending.ticket().id()
            + " reason="
            + pending.rollbackReason());
  }

  /** 重试销毁已完成账务回滚、但仍等待精确 GroupRemove 的物理编组。 */
  private void retryPendingMaterializedSpawnRemoval(PendingMaterializedSpawn pending) {
    if (pending.phase() != PendingMaterializedSpawnPhase.AWAITING_REMOVAL
        || pendingMaterializedSpawns.get(pending.key()) != pending) {
      return;
    }
    if (!runtimeDispatchService.hasMaterializedSpawnRollbackQuarantine(pending.trainName())) {
      // 已由 GroupRemove 或官方离线清理完成实体收容；消费确认，避免旧 group identity 在长运行中滞留。
      runtimeDispatchService.consumeMaterializedSpawnRollbackRemoval(pending.train());
      pendingMaterializedSpawns.remove(pending.key(), pending);
      return;
    }
    RuntimeTrainHandle handle = pending.train();
    if (!handle.isValid()) {
      // invalid 既可能是销毁，也可能只是 GroupUnload；这里只等待真实 GroupRemove 清除 quarantine。
      return;
    }
    try {
      handle.stopHard();
      handle.destroy();
    } catch (RuntimeException | LinkageError failure) {
      debugLogger.accept(
          "已实体化发车等待移除时销毁重试失败: train="
              + pending.trainName()
              + " error="
              + failure.getClass().getSimpleName()
              + ":"
              + String.valueOf(failure.getMessage()));
    }
  }

  private boolean containDuplicateMaterializedSpawn(
      Optional<RuntimeTrainHandle> liveTrain, String trainName) {
    if (liveTrain.isEmpty()) {
      return true;
    }
    boolean contained =
        containMaterializedSpawnBeforeRelease(
            liveTrain.orElseThrow(), () -> {}, () -> {}, () -> {}, debugLogger);
    if (!contained) {
      debugLogger.accept("额外物理编组收容失败，保持隔离记录: train=" + trainName);
    }
    return contained;
  }

  private void rollbackOrRetainMaterializedSpawn(
      SpawnTicket ticket,
      SpawnService service,
      Instant now,
      String trainName,
      SpawnControl.Lease spawnLease,
      RuntimeTrainHandle train,
      long recoveryEpoch,
      boolean fallback,
      String reason) {
    Optional<PendingMaterializedSpawn> existingTransaction =
        pendingMaterializedSpawns.values().stream()
            .filter(pending -> pending.ticket().id().equals(ticket.id()))
            .findFirst();
    boolean ownsTicket =
        existingTransaction.isEmpty() || existingTransaction.orElseThrow().train() == train;
    PendingMaterializedSpawn rollback =
        new PendingMaterializedSpawn(
            ticket,
            service,
            trainName,
            spawnLease,
            train,
            recoveryEpoch,
            now,
            fallback,
            ownsTicket,
            PendingMaterializedSpawnPhase.ROLLBACK_REQUIRED,
            reason);
    if (!ownsTicket) {
      debugLogger.accept(
          "检测到同票据的额外物理编组，仅执行物理收容且不重复回队: ticket=" + ticket.id() + " train=" + trainName);
    }
    retainAndRetryMaterializedSpawnRollback(rollback, now);
  }

  private boolean deferMaterializedSpawnUntilPromotion(
      SpawnTicket ticket,
      SpawnService service,
      String trainName,
      SpawnControl.Lease spawnLease,
      RuntimeTrainHandle train,
      long recoveryEpoch,
      Instant now,
      boolean fallback) {
    PendingMaterializedSpawn pending =
        new PendingMaterializedSpawn(
            ticket,
            service,
            trainName,
            spawnLease,
            train,
            recoveryEpoch,
            now.plus(MATERIALIZED_SPAWN_HYDRATION_GRACE),
            fallback,
            true,
            PendingMaterializedSpawnPhase.AWAITING_PROMOTION,
            "-");
    if (hasMaterializedSpawnTransaction(ticket.id())
        || pendingMaterializedSpawns.putIfAbsent(pending.key(), pending) != null) {
      return false;
    }
    debugLogger.accept(
        "SMART_EXPECTED_SPAWN_PHYSICAL_REGISTRATION result=pending-ticket train="
            + trainName
            + " ticket="
            + ticket.id()
            + " epoch="
            + recoveryEpoch
            + " deadline="
            + pending.deadline());
    return true;
  }

  /** 判断票据是否已有尚未提交或完成物理收容的实体化事务。 */
  private boolean hasMaterializedSpawnTransaction(java.util.UUID ticketId) {
    return ticketId != null
        && pendingMaterializedSpawns.values().stream()
            .anyMatch(
                pending ->
                    pending != null
                        && pending.ticket() != null
                        && ticketId.equals(pending.ticket().id()));
  }

  private void recordMaterializedSpawnSuccess(
      SpawnTicket ticket, SpawnService service, String trainName, boolean fallback) {
    spawnSuccess.increment();
    String depotUsed = ticket.selectedDepotNodeId().orElse(service.depotNodeId());
    debugLogger.accept(
        (fallback ? "Layover 降级发车成功: train=" : "自动发车成功: train=")
            + trainName
            + " route="
            + service.operatorCode()
            + "/"
            + service.lineCode()
            + "/"
            + service.routeCode()
            + " depot="
            + depotUsed);
  }

  private List<SpawnTicket> applyDepotDispatchCoordination(
      StorageProvider provider,
      List<SpawnTicket> dueTickets,
      Map<String, Integer> selectedDepotsThisTick,
      Instant now) {
    if (provider == null || dueTickets == null || dueTickets.isEmpty()) {
      return dueTickets == null ? List.of() : dueTickets;
    }
    List<SpawnTicket> depotTickets = new ArrayList<>();
    LineRuntimeSnapshot runtimeSnapshot = LineRuntimeSnapshot.capture(runtimeDispatchService);
    Map<String, Integer> selectedThisTick =
        selectedDepotsThisTick == null ? new HashMap<>() : selectedDepotsThisTick;
    for (SpawnTicket ticket : dueTickets) {
      if (!isDepotSpawnTicket(provider, ticket)) {
        continue;
      }
      SpawnTicket prepared =
          prepareDepotDispatchTicket(provider, ticket, runtimeSnapshot, selectedThisTick, now);
      depotTickets.add(prepared);
    }
    if (depotTickets.isEmpty()) {
      return dueTickets;
    }
    DepotDispatchCoordinator.DispatchBatch batch =
        depotDispatchCoordinator.coordinate(depotTickets, now);
    for (SpawnTicket deferred : batch.deferred()) {
      spawnManager.requeue(deferred);
    }
    Map<UUID, SpawnTicket> readyDepotTickets =
        batch.ready().stream().collect(Collectors.toMap(SpawnTicket::id, ticket -> ticket));
    List<SpawnTicket> result = new ArrayList<>(dueTickets.size());
    for (SpawnTicket original : dueTickets) {
      if (!isDepotSpawnTicket(provider, original)) {
        result.add(original);
        continue;
      }
      SpawnTicket ready = readyDepotTickets.get(original.id());
      if (ready != null) {
        result.add(ready);
      }
    }
    return List.copyOf(result);
  }

  /**
   * 在 depot 仲裁前固化本次实际 depot。
   *
   * <p>原始票据可能只携带 route 的默认 CRET 或 DYNAMIC depot 规范；如果直接按该值仲裁，会把多 depot 线路和动态股道错误折叠到同一个 key。这里复用实际
   * spawn 前的 depot 选择口径，使仲裁、backoff 与 gate 检查使用同一个 depot。
   */
  private SpawnTicket prepareDepotDispatchTicket(
      StorageProvider provider,
      SpawnTicket ticket,
      LineRuntimeSnapshot runtimeSnapshot,
      Map<String, Integer> selectedThisTick,
      Instant now) {
    if (provider == null || ticket == null || ticket.service() == null) {
      return ticket;
    }
    SpawnTicket prepared = ticket;
    List<SpawnDepot> lineDepots = List.of();
    Optional<SpawnDepot> configuredSelection = Optional.empty();
    if (prepared.selectedDepotNodeId().isEmpty()) {
      Optional<Route> routeOpt = provider.routes().findById(ticket.service().routeId());
      Optional<Line> lineOpt = routeOpt.flatMap(route -> provider.lines().findById(route.lineId()));
      if (lineOpt.isPresent()) {
        lineDepots = LineSpawnMetadata.parseDepots(lineOpt.get().metadata());
        if (!lineDepots.isEmpty()) {
          Optional<SpawnDepot> selectedDepotOpt =
              selectBalancedDepot(
                  provider, lineOpt.get().id(), lineDepots, runtimeSnapshot, selectedThisTick, now);
          if (selectedDepotOpt.isPresent()) {
            configuredSelection = selectedDepotOpt;
            prepared = prepared.withSelectedDepot(selectedDepotOpt.get().nodeId());
          }
        }
      }
    } else {
      Optional<Route> routeOpt = provider.routes().findById(ticket.service().routeId());
      Optional<Line> lineOpt = routeOpt.flatMap(route -> provider.lines().findById(route.lineId()));
      if (lineOpt.isPresent()) {
        lineDepots = LineSpawnMetadata.parseDepots(lineOpt.get().metadata());
      }
    }
    prepared =
        materializeDynamicDepotSelection(
            provider, ticket.service(), prepared, runtimeSnapshot, selectedThisTick, now);
    recordSelectedDepotForTick(prepared, lineDepots, configuredSelection, selectedThisTick);
    return prepared;
  }

  private static boolean isDepotSpawnTicket(StorageProvider provider, SpawnTicket ticket) {
    if (provider == null || ticket == null || ticket.service() == null) {
      return false;
    }
    Optional<Route> routeOpt = provider.routes().findById(ticket.service().routeId());
    if (routeOpt.isEmpty() || routeOpt.get().operationType() == RouteOperationType.RETURN) {
      return false;
    }
    List<org.fetarute.fetaruteTCAddon.company.model.RouteStop> stops =
        provider.routeStops().listByRoute(routeOpt.get().id());
    return !stops.isEmpty()
        && SpawnDirectiveParser.findDirectiveTarget(stops.get(0), "CRET").isPresent();
  }

  /**
   * 刷新超时的待复用票据，并在达到降级阈值时尝试 depot 补发。
   *
   * <p>设计目标：
   *
   * <ul>
   *   <li>避免“超时即删除”导致某条 return route 饥饿
   *   <li>在可配置阈值到达后，仅对“可从 depot 发车”的 RETURN 服务尝试降级补发
   *   <li>保留 pending 的“首次入队时间语义”，只在真正超时时刷新窗口
   * </ul>
   */
  private void refreshExpiredPendingTickets(
      StorageProvider provider, Instant now, Map<String, Integer> selectedDepotsThisTick) {
    java.util.List<java.util.UUID> removeIds = new java.util.ArrayList<>();
    java.util.Map<java.util.UUID, PendingLayoverEntry> refreshedEntries = new java.util.HashMap<>();
    int refreshed = 0;
    int fallbackTriggered = 0;
    int hardExpired = 0;
    Duration hardMaxAge = resolvePendingLayoverMaxAge();
    Map<String, Integer> fallbackSelectedThisTick =
        selectedDepotsThisTick == null ? new HashMap<>() : selectedDepotsThisTick;

    for (var entry : pendingLayoverTickets.entrySet()) {
      java.util.UUID ticketId = entry.getKey();
      PendingLayoverEntry pendingEntry = entry.getValue();
      if (ticketId == null || pendingEntry == null || pendingEntry.ticket() == null) {
        removeIds.add(ticketId);
        continue;
      }
      SpawnTicket ticket = pendingEntry.ticket();
      SpawnService service = ticket.service();
      long waitSeconds = java.time.Duration.between(pendingEntry.addedAt(), now).getSeconds();
      if (waitSeconds <= 0L) {
        continue;
      }

      Optional<Instant> expiry = ticketExpiry.apply(ticket);
      if (expiry.isPresent() && !now.isBefore(expiry.get())) {
        if (preservePendingDispatchAttempt(ticket, "ticket-expiry")) {
          continue;
        }
        removeIds.add(ticketId);
        hardExpired++;
        spawnManager.complete(ticket);
        debugLogger.accept(
            "票据到期作废: route="
                + (service != null ? service.routeCode() : "?")
                + " ticket="
                + ticketId
                + " expiry="
                + expiry.get());
        continue;
      }

      if (isPendingLayoverHardExpired(pendingEntry, now, hardMaxAge)) {
        if (preservePendingDispatchAttempt(ticket, "hard-expiry")) {
          continue;
        }
        long totalWaitSeconds =
            java.time.Duration.between(pendingEntry.firstAddedAt(), now).getSeconds();
        removeIds.add(ticketId);
        hardExpired++;
        spawnManager.complete(ticket);
        HEALTH_LOGGER.warning(
            "[FTA] 折返票据超过最大等待时间，放弃并释放 backlog: route="
                + (service != null ? service.routeCode() : "?")
                + " 等待="
                + totalWaitSeconds
                + "s ticketId="
                + ticketId);
        continue;
      }

      java.util.OptionalLong fallbackTimeoutSeconds = resolveLayoverFallbackTimeoutSeconds(service);
      if (fallbackTimeoutSeconds.isPresent() && waitSeconds >= fallbackTimeoutSeconds.getAsLong()) {
        if (canFallbackSpawnFromDepot(service)) {
          if (!spawnBudgetLeft() || preservePendingDispatchAttempt(ticket, "depot-fallback")) {
            // 名额用完时原样保留等待记录：刷新窗口会让降级计时从头再来。
            continue;
          }
          removeIds.add(ticketId);
          fallbackTriggered++;
          HEALTH_LOGGER.warning(
              "[FTA] 折返票据等待过久，尝试 depot 补发: route="
                  + (service != null ? service.routeCode() : "?")
                  + " 等待="
                  + waitSeconds
                  + "s ticketId="
                  + ticketId);
          tryFallbackSpawnForPending(provider, ticket, now, waitSeconds, fallbackSelectedThisTick);
        } else {
          refreshedEntries.put(ticketId, pendingEntry.refreshedAt(now));
          refreshed++;
          HEALTH_LOGGER.warning(
              "[FTA] 折返票据等待过久，但首站非 depot，刷新等待窗口: route="
                  + (service != null ? service.routeCode() : "?")
                  + " 等待="
                  + waitSeconds
                  + "s ticketId="
                  + ticketId);
        }
        continue;
      }

      if (waitSeconds >= PENDING_LAYOVER_REFRESH_SECONDS) {
        refreshedEntries.put(ticketId, pendingEntry.refreshedAt(now));
        refreshed++;
        HEALTH_LOGGER.warning(
            "[FTA] 折返票据等待过久，刷新等待窗口: route="
                + (service != null ? service.routeCode() : "?")
                + " 等待="
                + waitSeconds
                + "s ticketId="
                + ticketId);
      }
    }

    for (java.util.UUID id : removeIds) {
      if (id != null) {
        pendingLayoverTickets.remove(id);
      }
    }
    if (!refreshedEntries.isEmpty()) {
      pendingLayoverTickets.putAll(refreshedEntries);
    }
    if (refreshed > 0 || fallbackTriggered > 0 || hardExpired > 0 || !removeIds.isEmpty()) {
      debugLogger.accept(
          "刷新待复用票据: refreshed="
              + refreshed
              + " fallback="
              + fallbackTriggered
              + " hardExpired="
              + hardExpired
              + " removed="
              + removeIds.size());
    }
  }

  private Duration resolvePendingLayoverMaxAge() {
    if (configManager == null) {
      return DEFAULT_PENDING_LAYOVER_MAX_AGE;
    }
    ConfigManager.ConfigView view = configManager.current();
    if (view == null || view.spawnSettings() == null) {
      return DEFAULT_PENDING_LAYOVER_MAX_AGE;
    }
    long seconds = view.spawnSettings().pendingLayoverMaxAgeSeconds();
    if (seconds <= 0L) {
      return Duration.ZERO;
    }
    return Duration.ofSeconds(seconds);
  }

  private boolean isPendingLayoverHardExpired(
      PendingLayoverEntry pendingEntry, Instant now, Duration maxAge) {
    if (pendingEntry == null || now == null || maxAge == null) {
      return false;
    }
    if (maxAge.isZero() || maxAge.isNegative()) {
      return false;
    }
    Instant firstAddedAt =
        pendingEntry.firstAddedAt() == null ? pendingEntry.addedAt() : pendingEntry.firstAddedAt();
    if (firstAddedAt == null || firstAddedAt.isAfter(now)) {
      return false;
    }
    return Duration.between(firstAddedAt, now).compareTo(maxAge) >= 0;
  }

  private java.util.OptionalLong resolveLayoverFallbackTimeoutSeconds(SpawnService service) {
    if (service == null || service.baseHeadway() == null) {
      return java.util.OptionalLong.empty();
    }
    double multiplier = configManager.current().spawnSettings().layoverFallbackMultiplier();
    if (multiplier <= 0D) {
      return java.util.OptionalLong.empty();
    }
    long baselineSeconds = Math.max(1L, service.baseHeadway().getSeconds());
    long timeoutSeconds = (long) Math.ceil(baselineSeconds * multiplier);
    if (timeoutSeconds <= 0L) {
      return java.util.OptionalLong.empty();
    }
    return java.util.OptionalLong.of(timeoutSeconds);
  }

  private void tryFallbackSpawnForPending(
      StorageProvider provider,
      SpawnTicket ticket,
      Instant now,
      long waitSeconds,
      Map<String, Integer> selectedThisTick) {
    if (provider == null || ticket == null || ticket.service() == null) {
      return;
    }
    SpawnService service = ticket.service();
    Optional<Route> routeEntityOpt = provider.routes().findById(service.routeId());
    if (routeEntityOpt.isEmpty()) {
      requeue(ticket, now, "fallback-route-not-found");
      return;
    }
    Route routeEntity = routeEntityOpt.get();
    Optional<Line> lineOpt = provider.lines().findById(routeEntity.lineId());
    if (lineOpt.isEmpty()) {
      requeue(ticket, now, "fallback-line-not-found");
      return;
    }
    Optional<RouteDefinition> routeOpt = routeDefinitions.findById(service.routeId());
    if (routeOpt.isEmpty()) {
      requeue(ticket, now, "fallback-route-definition-not-found");
      return;
    }
    debugLogger.accept(
        "Layover pending 降级发车: route="
            + service.routeCode()
            + " wait="
            + waitSeconds
            + "s ticket="
            + ticket.id());
    trySpawnFromDepot(
        provider, ticket, service, routeOpt.get(), lineOpt.get(), now, selectedThisTick);
  }

  private boolean trySpawn(
      StorageProvider provider,
      Instant now,
      SpawnTicket ticket,
      Map<String, Integer> selectedDepotsThisTick) {
    if (hasMaterializedSpawnTransaction(ticket.id())) {
      deferWithoutAttempt(ticket, now, "materialized-transaction-active");
      return false;
    }
    SpawnService service = ticket.service();
    Optional<Route> routeEntityOpt = provider.routes().findById(service.routeId());
    if (routeEntityOpt.isEmpty()) {
      requeue(ticket, now, "route-not-found");
      return false;
    }
    Route routeEntity = routeEntityOpt.get();
    Optional<Line> lineOpt = provider.lines().findById(routeEntity.lineId());
    if (lineOpt.isEmpty()) {
      requeue(ticket, now, "line-not-found");
      return false;
    }
    Line line = lineOpt.get();

    Optional<RouteDefinition> routeOpt = routeDefinitions.findById(service.routeId());
    if (routeOpt.isEmpty()) {
      requeue(ticket, now, "route-not-found");
      return false;
    }
    RouteDefinition route = routeOpt.get();

    if (routeEntity.operationType() == RouteOperationType.RETURN) {
      // 若票据已经进入 pending 且达到降级阈值，则尝试 depot 补发。
      java.util.OptionalLong fallbackTimeoutSeconds = resolveLayoverFallbackTimeoutSeconds(service);
      if (fallbackTimeoutSeconds.isPresent()) {
        PendingLayoverEntry pendingEntry = pendingLayoverTickets.get(ticket.id());
        if (pendingEntry != null) {
          long waitSeconds = Duration.between(pendingEntry.addedAt(), now).getSeconds();
          if (waitSeconds >= fallbackTimeoutSeconds.getAsLong()) {
            if (canFallbackSpawnFromDepot(service)) {
              if (spawnBudgetLeft()
                  && !preservePendingDispatchAttempt(ticket, "due-ticket-depot-fallback")) {
                pendingLayoverTickets.remove(ticket.id());
                debugLogger.accept(
                    "Layover 降级发车: route="
                        + service.routeCode()
                        + " 等待="
                        + waitSeconds
                        + "s (超时="
                        + fallbackTimeoutSeconds.getAsLong()
                        + "s) 尝试从 depot 补发");
                return trySpawnFromDepot(
                    provider, ticket, service, route, line, now, selectedDepotsThisTick);
              }
            } else {
              debugLogger.accept(
                  "Layover 降级跳过: route="
                      + service.routeCode()
                      + " 等待="
                      + waitSeconds
                      + "s 但首站非 depot，继续等待复用");
            }
          }
        }
      }
      return tryReuseLayover(Optional.of(provider), ticket, service, route, now, false);
    }

    if (shouldHoldByCongestion(provider, ticket, service, line, routeEntity, route, now)) {
      // 同 fleet-cap：拥堵是线网状态，不是这张票的过错，不该消耗它的重试预算。
      deferByGate(ticket, now, "congestion-hold");
      return false;
    }

    List<org.fetarute.fetaruteTCAddon.company.model.RouteStop> stops =
        provider.routeStops().listByRoute(routeEntity.id());
    boolean startsWithCret =
        !stops.isEmpty()
            && SpawnDirectiveParser.findDirectiveTarget(stops.get(0), "CRET").isPresent();
    if (routeEntity.operationType() == RouteOperationType.CREATE && !startsWithCret) {
      requeue(ticket, now, "create-without-cret");
      return false;
    }
    if (!startsWithCret) {
      return tryReuseLayover(Optional.of(provider), ticket, service, route, now, false);
    }

    Optional<SpawnControl.Lease> spawnLeaseOpt =
        tryAcquireSpawnControlForTicket(
            provider,
            line,
            ticket,
            service,
            routeEntity,
            SpawnControl.LeaseKind.SPAWN,
            Optional.empty(),
            now);
    if (spawnLeaseOpt.isEmpty()) {
      requeue(ticket, now, "line-cap");
      return false;
    }
    SpawnControl.Lease spawnLease = spawnLeaseOpt.get();

    Optional<PreparedDepotSpawn> preparedOpt =
        prepareDepotSpawn(
            provider,
            ticket,
            service,
            route,
            line,
            routeEntity,
            spawnLease,
            Map.of(),
            now,
            DepotSpawnOrigin.NORMAL);
    if (preparedOpt.isEmpty()) {
      return false;
    }
    PreparedDepotSpawn prepared = preparedOpt.get();
    Optional<DepotSpawner.MaterializedSpawn> materializedSpawnOpt =
        materializePreparedDepotSpawn(provider, prepared, now, DepotSpawnOrigin.NORMAL);
    if (materializedSpawnOpt.isEmpty()) {
      return false;
    }
    return finalizeMaterializedDepotSpawn(
        new MaterializedDepotSpawnContext(
            provider,
            prepared.ticket(),
            service,
            route,
            routeEntity.operationType(),
            prepared.trainName(),
            prepared.spawnLease(),
            prepared.gateRequest(),
            prepared.gateRequest().request(),
            prepared.lineDepots(),
            materializedSpawnOpt.get(),
            prepared.recoveryEpoch(),
            now,
            false));
  }

  /**
   * 按“线路+方向”的拥挤度执行 HOLD 门控。
   *
   * <p>该门控只作用于 {@code OPERATION/CREATE}，RETURN 始终允许通过以便回库释放压力。
   */
  /** 拥堵分数的上次报告分档（按 gateKey），用于去重。 */
  private final java.util.concurrent.ConcurrentMap<String, String> fleetCapReported =
      new java.util.concurrent.ConcurrentHashMap<>();

  private final java.util.concurrent.ConcurrentMap<String, String> congestionScoreReported =
      new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * 报告拥堵分数——**无论闸门是否触发**。
   *
   * <p>去重按分数的 0.05 分档：分档不变就不重复输出，因此规模由"闸门数 × 分档变化次数"决定， 不随 tick 放大。
   */
  private void traceCongestionScore(
      String gateKey,
      Line line,
      Route routeEntity,
      CongestionAssessment assessment,
      boolean holding,
      double holdThreshold,
      double releaseThreshold) {
    String bucket =
        String.format(Locale.ROOT, "%.2f", Math.floor(assessment.score() * 20.0) / 20.0);
    String signature = bucket + ":" + holding;
    if (signature.equals(congestionScoreReported.put(gateKey, signature))) {
      return;
    }
    debugLogger.accept(
        String.format(
            Locale.ROOT,
            "SMART_SPAWN_CONGESTION_SCORE line=%s route=%s key=%s score=%.3f holding=%b"
                + " holdThreshold=%.2f releaseThreshold=%.2f"
                + " occ=%.3f(%d/%d) edgeBusy=%d nodeBusy=%d"
                + " route=%.3f(%d/%d) signal=%.3f network=%.3f(%d/%d)",
            line == null ? "-" : line.code(),
            routeEntity == null ? "-" : routeEntity.code(),
            gateKey,
            assessment.score(),
            holding,
            holdThreshold,
            releaseThreshold,
            assessment.occupancyRate(),
            assessment.busyResources(),
            assessment.totalResources(),
            assessment.busyEdges(),
            assessment.busyNodes(),
            assessment.routeTrainPressure(),
            assessment.activeRouteTrains(),
            assessment.targetRouteTrains(),
            assessment.lineSignalPressure(),
            assessment.networkPressure(),
            assessment.activeTrains(),
            assessment.networkReference()));
  }

  /** 在网列车数：progress 条目数，与 SMART_DISPATCH_GLOBAL_SNAPSHOT 的 trains= 同源。 */
  private int activeTrainCount() {
    Map<String, RouteProgressRegistry.RouteProgressEntry> entries =
        runtimeDispatchService == null ? null : runtimeDispatchService.snapshotProgressEntries();
    return entries == null ? 0 : entries.size();
  }

  /**
   * 是否因全网在网列车达到上限而拒绝再实体化新车。
   *
   * <p>这是在"网里已经有多少车"这个维度上设限的**准入控制**。拥堵闸门测的主要是单条 route 自己的占用比例， 全网堵死时评分仍可能够不着阈值，不能替代全网上限。
   *
   * <p>cap &lt;= 0 表示禁用，此时不做任何全网上限拦截。
   */
  boolean shouldHoldByFleetCap(Line line, Route routeEntity) {
    int cap = configManager.current().spawnSettings().maxActiveTrains();
    if (cap <= 0) {
      return false;
    }
    int active = activeTrainCount();
    boolean holding = active >= cap;
    traceFleetCap(line, routeEntity, active, cap, holding);
    return holding;
  }

  /**
   * 报告准入闸门状态——**无论是否拦下**。
   *
   * <p>按 (line|route, active, holding) 去重：达到上限后 active 会稳在 cap 附近，因此稳态下每条 route 至多几行，不随 tick
   * 放大。不触发时也报，是为了让"离上限还有多远"可归因：只在触发时才打印的闸门，无法判断它是否在正常工作。
   */
  private void traceFleetCap(Line line, Route routeEntity, int active, int cap, boolean holding) {
    String key =
        (line == null ? "-" : line.code()) + "|" + (routeEntity == null ? "-" : routeEntity.code());
    String signature = active + ":" + holding;
    if (signature.equals(fleetCapReported.put(key, signature))) {
      return;
    }
    debugLogger.accept(
        "SMART_SPAWN_FLEET_CAP line="
            + (line == null ? "-" : line.code())
            + " route="
            + (routeEntity == null ? "-" : routeEntity.code())
            + " active="
            + active
            + " cap="
            + cap
            + " holding="
            + holding);
  }

  private boolean shouldHoldByCongestion(
      StorageProvider provider,
      SpawnTicket ticket,
      SpawnService service,
      Line line,
      Route routeEntity,
      RouteDefinition route,
      Instant now) {
    if (provider == null
        || service == null
        || line == null
        || routeEntity == null
        || route == null) {
      return false;
    }
    if (routeEntity.operationType() == RouteOperationType.RETURN) {
      // RETURN 线路完全绕过拥堵闸门，这里输出豁免记录使其可见。
      // 豁免的理由：RETURN 是把车收回去，拦住反而会让车积在线上。按 gateKey 去重，一条线至多一行。
      String returnKey = buildCongestionGateKey(service);
      if (congestionScoreReported.put(returnKey, "return-exempt") == null) {
        debugLogger.accept(
            "SMART_SPAWN_CONGESTION_EXEMPT line="
                + line.code()
                + " route="
                + routeEntity.code()
                + " key="
                + returnKey
                + " reason=operation-type-return");
      }
      return false;
    }
    if (ticket != null && ticket.timetableDriven()) {
      // 表定车次不受拥堵闸门约束：何时发车由时刻表决定，编表时已经过冲突检查；拥堵评分是按间隔发车时代的吞吐启发式，
      // 不管行车安全（安全由占用与联锁负责）。它排在复用在网车之前，拦下表定班次会把折返的车扣在终点——
      // 单股道尽头一扣就堵死整条线。按表运行的在网车数远多于按间隔发车，全网压力一项就会顶满阈值。
      // 全网硬上限（max-active-trains）照旧生效。
      String timetableKey = buildCongestionGateKey(service) + "|timetable";
      if (congestionScoreReported.put(timetableKey, "timetable-exempt") == null) {
        debugLogger.accept(
            "SMART_SPAWN_CONGESTION_EXEMPT line="
                + line.code()
                + " route="
                + routeEntity.code()
                + " key="
                + timetableKey
                + " reason=timetable-trip");
      }
      return false;
    }
    CongestionAssessment assessment =
        evaluateCongestion(provider, service, line, routeEntity, route);
    String gateKey = buildCongestionGateKey(service);
    CongestionGateState previous = congestionGates.get(gateKey);
    boolean wasHolding = previous != null && previous.holding();
    ConfigManager.SpawnSettings spawnSettings = configManager.current().spawnSettings();
    double holdThreshold = spawnSettings.congestionHoldThreshold();
    double releaseThreshold = spawnSettings.congestionReleaseThreshold();

    // 阈值不在 (0,1] 就把闸门整个关掉，而不是"全部拦下"。
    //
    // 这里刻意不 fail-closed：拥堵闸门的"关闭"方向是停止发车，阈值为 0 会让
    // `score >= 0` 恒真，于是全网再也发不出一辆车——那不是保守，那是停运。
    // 真正的安全兜底是 shouldHoldByFleetCap 那道硬上限，它不依赖这两个数。
    if (!(holdThreshold > 0.0D) || holdThreshold > 1.0D || !(releaseThreshold > 0.0D)) {
      warnThrottled(
          "congestion-threshold-invalid",
          "[FTA] 拥挤门控阈值无效，已跳过该门控: hold=" + holdThreshold + " release=" + releaseThreshold);
      return false;
    }

    boolean holding =
        wasHolding ? assessment.score() >= releaseThreshold : assessment.score() >= holdThreshold;
    congestionGates.put(gateKey, new CongestionGateState(holding, assessment.score(), now));

    // 不触发时也要把分数报出来。
    //
    // 若只在 holding 为真时输出，闸门从不触发时分数便不可见，网络堵死而闸门未动作也无从判断原因。
    // "差一点没够着阈值"和"根本不在一个量级"要采取的行动完全相反：
    // 前者调阈值，后者要修评分本身（或那条 RETURN 豁免）。
    //
    // 按 (gateKey, 分数分档) 去重，不随 tick 放大。
    traceCongestionScore(
        gateKey, line, routeEntity, assessment, holding, holdThreshold, releaseThreshold);

    if (holding) {
      String scoreSummary =
          String.format(
              Locale.ROOT,
              "score=%.2f occ=%.2f(%d/%d) route=%.2f(%d/%d) signal=%.2f network=%.2f(%d/%d)",
              assessment.score(),
              assessment.occupancyRate(),
              assessment.busyResources(),
              assessment.totalResources(),
              assessment.routeTrainPressure(),
              assessment.activeRouteTrains(),
              assessment.targetRouteTrains(),
              assessment.lineSignalPressure(),
              assessment.networkPressure(),
              assessment.activeTrains(),
              assessment.networkReference());
      debugLogger.accept(
          "自动发车拥挤门控: line="
              + line.code()
              + " route="
              + routeEntity.code()
              + " key="
              + gateKey
              + " "
              + scoreSummary);
      warnThrottled(
          "congestion-hold:" + gateKey,
          "[FTA] 自动发车拥挤门控: line="
              + line.code()
              + " route="
              + routeEntity.code()
              + " "
              + scoreSummary);
      return true;
    }
    if (wasHolding) {
      debugLogger.accept(
          "自动发车拥挤门控解除: line="
              + line.code()
              + " route="
              + routeEntity.code()
              + " key="
              + gateKey
              + " score="
              + String.format(Locale.ROOT, "%.2f", assessment.score()));
    }
    return false;
  }

  /**
   * 评估当前票据对应方向的拥挤度。
   *
   * <p>评分由四部分线性组合：
   *
   * <ul>
   *   <li>occupancyRate：route 的边**与节点**集合中被占用的比例
   *   <li>routeTrainPressure：同 route 在途车数 / 目标车数
   *   <li>lineSignalPressure：同 line 列车信号压力（STOP/CAUTION 等）
   *   <li>networkPressure：全网在网车数 / 准入上限（准入控制关闭时为 0，权重退回旧的三分量口径）
   * </ul>
   */
  private CongestionAssessment evaluateCongestion(
      StorageProvider provider,
      SpawnService service,
      Line line,
      Route routeEntity,
      RouteDefinition route) {
    Set<String> routeEdges = collectRouteEdgeKeys(route);
    Set<String> routeNodes = collectRouteNodeKeys(route);
    int totalEdges = routeEdges.size();
    int totalNodes = routeNodes.size();
    Set<String> busyEdgeKeys = new HashSet<>();
    Set<String> busyNodeKeys = new HashSet<>();
    if (!routeEdges.isEmpty() || !routeNodes.isEmpty()) {
      for (OccupancyClaim claim : occupancyManager.snapshotClaims()) {
        if (claim == null || claim.resource() == null) {
          continue;
        }
        // EDGE 与 NODE 都要计入：两类占用数量相当，只数 EDGE 等于对约一半的占用视而不见，评分会系统性偏低。
        if (claim.resource().kind() == ResourceKind.EDGE) {
          String key = normalizeEdgeKey(claim.resource().key());
          if (!key.isBlank() && routeEdges.contains(key)) {
            busyEdgeKeys.add(key);
          }
        } else if (claim.resource().kind() == ResourceKind.NODE) {
          String key = normalizeNodeKey(claim.resource().key());
          if (!key.isBlank() && routeNodes.contains(key)) {
            busyNodeKeys.add(key);
          }
        }
      }
    }
    int busyEdges = busyEdgeKeys.size();
    int busyNodes = busyNodeKeys.size();
    int totalResources = totalEdges + totalNodes;
    double occupancyRate =
        totalResources <= 0
            ? 0.0D
            : clamp01((double) (busyEdges + busyNodes) / (double) totalResources);

    Map<String, RouteProgressRegistry.RouteProgressEntry> progressEntries =
        runtimeDispatchService.snapshotProgressEntries();
    Map<UUID, Route> routeCache = new HashMap<>();
    int activeRouteTrains = 0;
    int lineSignalSamples = 0;
    double lineSignalSum = 0.0D;
    for (RouteProgressRegistry.RouteProgressEntry entry : progressEntries.values()) {
      if (entry == null || entry.routeUuid() == null) {
        continue;
      }
      if (service.routeId().equals(entry.routeUuid())) {
        activeRouteTrains++;
      }
      Route progressRoute =
          routeCache.computeIfAbsent(
              entry.routeUuid(), id -> provider.routes().findById(id).orElse(null));
      if (progressRoute == null || !line.id().equals(progressRoute.lineId())) {
        continue;
      }
      lineSignalSamples++;
      lineSignalSum += signalPressure(entry.lastSignal());
    }
    double lineSignalPressure =
        lineSignalSamples <= 0 ? 0.0D : clamp01(lineSignalSum / (double) lineSignalSamples);

    int targetRouteTrains = estimateRouteTargetTrains(service, routeEntity, route);
    double routeTrainPressure =
        targetRouteTrains <= 0
            ? 0.0D
            : clamp01((double) activeRouteTrains / (double) targetRouteTrains);

    // 【全网压力】其余分量全部是"本 route 自己"的局部量，全网堵死时评分依然可能远低于阈值。
    // 加入全网在网车数/参考值这一项，闸门才可能在撞上硬上限之前就平滑地开始拦车。
    //
    // 分母用 congestion-network-reference-trains，**不是** max-active-trains。两者默认相等，
    // 但是不同的量：前者是“网络装多少车算满”，后者是“我们允许发多少车”。
    // 合用时把上限从 16 提到 24，会在抬高天花板的同时把软刹车也调钝
    // （分母变大 → networkPressure 变小），一次改两件事，密度实验就无法归因。
    ConfigManager.SpawnSettings congestionSettings = configManager.current().spawnSettings();
    int networkReference = congestionSettings.congestionNetworkReferenceTrains();
    int activeTrains = activeTrainCount();
    double networkPressure =
        networkReference <= 0 ? 0.0D : clamp01((double) activeTrains / (double) networkReference);

    double score =
        combineCongestionScore(
            occupancyRate,
            routeTrainPressure,
            lineSignalPressure,
            networkPressure,
            networkReference);
    return new CongestionAssessment(
        score,
        occupancyRate,
        routeTrainPressure,
        lineSignalPressure,
        networkPressure,
        busyEdges,
        totalEdges,
        busyNodes,
        totalNodes,
        activeRouteTrains,
        targetRouteTrains,
        activeTrains,
        networkReference);
  }

  /**
   * 拥挤度四分量的线性组合。
   *
   * <p>networkReference &lt;= 0（没有全网压力信号）时退回本改动之前的三分量旧权重，便于用一个配置项
   * 把判别口径整体还原——这样"评分变了"和"准入控制生效了"两件事可以分别证伪。
   */
  /**
   * 合成拥堵分。
   *
   * @param networkReference “全网算满”的参考车数（{@code congestion-network-reference-trains}）。 为 0
   *     表示没有全网压力信号，此时退回三分量权重。<b>不是</b>准入上限； 两者默认相等但语义不同，详见调用处注释。
   */
  static double combineCongestionScore(
      double occupancyRate,
      double routeTrainPressure,
      double lineSignalPressure,
      double networkPressure,
      int networkReference) {
    if (networkReference <= 0) {
      return clamp01(
          occupancyRate * 0.55D + routeTrainPressure * 0.30D + lineSignalPressure * 0.15D);
    }
    return clamp01(
        occupancyRate * 0.40D
            + routeTrainPressure * 0.20D
            + lineSignalPressure * 0.10D
            + networkPressure * 0.30D);
  }

  /** 将 route waypoint 序列归一化为节点 key 集合（与 OccupancyResource.forNode 的 key 同源）。 */
  static Set<String> collectRouteNodeKeys(RouteDefinition route) {
    if (route == null || route.waypoints() == null || route.waypoints().isEmpty()) {
      return Set.of();
    }
    Set<String> keys = new HashSet<>();
    for (NodeId waypoint : route.waypoints()) {
      if (waypoint == null) {
        continue;
      }
      String key = normalizeNodeKey(waypoint.value());
      if (!key.isBlank()) {
        keys.add(key);
      }
    }
    return keys;
  }

  static String normalizeNodeKey(String raw) {
    return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * 估算某 route 的目标在线列车数。
   *
   * <p>优先使用 route.runtimeSeconds；缺失时按停站数保守估算，避免因 metadata 缺失导致拥挤度长期失真。
   */
  private static int estimateRouteTargetTrains(
      SpawnService service, Route routeEntity, RouteDefinition route) {
    long headwaySeconds =
        service != null && service.baseHeadway() != null
            ? Math.max(1L, service.baseHeadway().getSeconds())
            : 60L;
    long runtimeSeconds =
        routeEntity != null
            ? routeEntity
                .runtimeSeconds()
                .map(Integer::longValue)
                .orElse(estimateRuntimeSeconds(route))
            : estimateRuntimeSeconds(route);
    if (runtimeSeconds <= 0L) {
      runtimeSeconds = 60L;
    }
    long target = (runtimeSeconds + headwaySeconds - 1L) / headwaySeconds;
    target = Math.max(1L, Math.min(32L, target));
    return (int) target;
  }

  private static long estimateRuntimeSeconds(RouteDefinition route) {
    if (route == null || route.waypoints() == null || route.waypoints().isEmpty()) {
      return 300L;
    }
    // 无 runtime 元数据时，用“每节点 30 秒 + 基础 120 秒”做保守估算。
    long estimated = 120L + (long) route.waypoints().size() * 30L;
    return Math.max(180L, Math.min(7200L, estimated));
  }

  /** 将 route waypoint 序列归一化为无向 edge key 集合。 */
  private static Set<String> collectRouteEdgeKeys(RouteDefinition route) {
    if (route == null || route.waypoints() == null || route.waypoints().size() < 2) {
      return Set.of();
    }
    Set<String> keys = new HashSet<>();
    List<NodeId> waypoints = route.waypoints();
    for (int i = 0; i + 1 < waypoints.size(); i++) {
      NodeId from = waypoints.get(i);
      NodeId to = waypoints.get(i + 1);
      if (from == null || to == null) {
        continue;
      }
      String key = normalizeUndirectedEdgeKey(from.value(), to.value());
      if (!key.isBlank()) {
        keys.add(key);
      }
    }
    return keys;
  }

  private static String normalizeEdgeKey(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    String trimmed = raw.trim();
    int separator = trimmed.indexOf('~');
    if (separator <= 0 || separator >= trimmed.length() - 1) {
      return trimmed;
    }
    String left = trimmed.substring(0, separator);
    String right = trimmed.substring(separator + 1);
    return normalizeUndirectedEdgeKey(left, right);
  }

  private static String normalizeUndirectedEdgeKey(String left, String right) {
    if (left == null || right == null) {
      return "";
    }
    String a = left.trim();
    String b = right.trim();
    if (a.isBlank() || b.isBlank()) {
      return "";
    }
    return a.compareToIgnoreCase(b) <= 0 ? a + "~" + b : b + "~" + a;
  }

  private static double signalPressure(
      org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect signal) {
    if (signal == null) {
      return 0.0D;
    }
    return switch (signal) {
      case STOP -> 1.0D;
      case CAUTION -> 0.65D;
      case PROCEED_WITH_CAUTION -> 0.35D;
      case PROCEED -> 0.0D;
    };
  }

  private static double clamp01(double value) {
    if (value <= 0.0D) {
      return 0.0D;
    }
    if (value >= 1.0D) {
      return 1.0D;
    }
    return value;
  }

  /** 生成拥挤门控 key：lineId + 方向（按首站/起点归一化）。 */
  private static String buildCongestionGateKey(SpawnService service) {
    if (service == null) {
      return "";
    }
    String direction = normalizeDirectionKey(service.depotNodeId());
    return service.lineId() + "|" + direction;
  }

  private static String normalizeDirectionKey(String startNode) {
    if (startNode == null || startNode.isBlank()) {
      return "unknown";
    }
    String normalized = startNode.trim().toLowerCase(Locale.ROOT);
    if (SpawnDirectiveParser.isDynamicTarget(normalized)) {
      Optional<String> dynamic = parseDynamicDirectionKey(normalized);
      if (dynamic.isPresent()) {
        return dynamic.get();
      }
    }
    return TerminalKeyResolver.extractStationKey(normalized).orElse(normalized);
  }

  private static Optional<String> parseDynamicDirectionKey(String dynamicSpec) {
    if (dynamicSpec == null || dynamicSpec.isBlank()) {
      return Optional.empty();
    }
    if (!dynamicSpec.startsWith("dynamic:")) {
      return Optional.empty();
    }
    String rest = dynamicSpec.substring("dynamic:".length());
    String[] parts = rest.split(":", 4);
    if (parts.length < 3) {
      return Optional.empty();
    }
    String operator = parts[0].trim();
    String type = parts[1].trim();
    String name = parts[2].trim();
    if (operator.isBlank() || type.isBlank() || name.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(operator + ":" + type + ":" + name);
  }

  /** 定期清理长时间未更新的拥挤门控状态，避免内存累积。 */
  private void cleanupStaleCongestionGates(Instant now) {
    long nowMs = System.currentTimeMillis();
    long previous = lastCongestionCleanupMs.get();
    if (nowMs - previous < 60_000L) {
      return;
    }
    if (!lastCongestionCleanupMs.compareAndSet(previous, nowMs)) {
      return;
    }
    if (congestionGates.isEmpty()) {
      return;
    }
    Instant cutoff = now.minus(CONGESTION_GATE_TTL);
    congestionGates
        .entrySet()
        .removeIf(
            entry ->
                entry.getValue() == null
                    || entry.getValue().updatedAt() == null
                    || entry.getValue().updatedAt().isBefore(cutoff));
  }

  /**
   * 写入列车生命周期标签。
   *
   * <p>约定如下：
   *
   * <ul>
   *   <li>{@code FTA_OP_TRIPS} 只在 {@link RouteOperationType#OPERATION} 成功发车后递增
   *   <li>{@link RouteOperationType#CREATE} 与 {@link RouteOperationType#RETURN} 会把 {@code
   *       FTA_OP_TRIPS} 重置为 0
   *   <li>{@code FTA_SPAWN_GROUP} 记录交路组名，供回收与诊断共用
   *   <li>{@code FTA_OP_MAX} 记录最大运营圈数，用于到期后触发回收
   * </ul>
   *
   * <p>该方法只改写 tag，不直接修改 route 或 occupancy 状态。
   */
  private void applySpawnLifecycleTags(
      Optional<StorageProvider> providerOpt,
      TrainProperties properties,
      SpawnService service,
      RouteOperationType operationType) {
    if (properties == null || service == null || operationType == null) {
      return;
    }
    TrainTagHelper.writeTag(properties, TAG_OPERATION_TRIPS, "0");
    Optional<String> groupOpt = resolveServiceSpawnGroup(providerOpt, service.routeId());
    if (groupOpt.isPresent()) {
      TrainTagHelper.writeTag(properties, TAG_CIRCULATION_GROUP, groupOpt.get());
    } else {
      TrainTagHelper.removeTagKey(properties, TAG_CIRCULATION_GROUP);
    }
    Optional<Integer> maxTripsOpt =
        resolveServiceMaxOperationTrips(providerOpt, service.routeId(), groupOpt);
    if (maxTripsOpt.isPresent()) {
      TrainTagHelper.writeTag(
          properties, TAG_MAX_OPERATION_TRIPS, String.valueOf(maxTripsOpt.get()));
    } else {
      TrainTagHelper.removeTagKey(properties, TAG_MAX_OPERATION_TRIPS);
    }
  }

  private void tryDispatchPendingLayover(
      Instant now, Optional<StorageProvider> providerOpt, Optional<String> terminalFilter) {
    if (pendingLayoverTickets.isEmpty()) {
      return;
    }
    List<PendingLayoverDispatchEntry> dispatchOrder = buildPendingDispatchOrder(terminalFilter);
    for (PendingLayoverDispatchEntry entry : dispatchOrder) {
      SpawnTicket ticket = entry.ticket();
      if (ticket == null || !pendingLayoverTickets.containsKey(ticket.id())) {
        continue;
      }
      if (tryReuseLayover(providerOpt, ticket, entry.service(), entry.route(), now, true)) {
        pendingLayoverTickets.remove(ticket.id());
      }
    }
  }

  /**
   * 对同一 (line + terminal) 的到期票据执行 route 轮转重排。
   *
   * <p>该重排只改变“同组票据之间”的处理次序，组外票据保持原位。这样在 layover 候选持续可用且未进入 pending 时，也能维持组内 route
   * 的公平轮转，避免固定顺序导致连续命中同一路由。
   */
  private List<SpawnTicket> orderDueTicketsWithRouteRotation(List<SpawnTicket> dueTickets) {
    if (dueTickets == null || dueTickets.size() <= 1) {
      return dueTickets == null ? List.of() : dueTickets;
    }
    Map<String, List<PendingLayoverDispatchEntry>> byGroup = new LinkedHashMap<>();
    Map<UUID, String> ticketToGroup = new HashMap<>();
    for (SpawnTicket ticket : dueTickets) {
      if (ticket == null || ticket.service() == null) {
        continue;
      }
      SpawnService service = ticket.service();
      Optional<RouteDefinition> routeOpt = routeDefinitions.findById(service.routeId());
      if (routeOpt.isEmpty()) {
        continue;
      }
      RouteDefinition route = routeOpt.get();
      if (route.waypoints().isEmpty()) {
        continue;
      }
      String terminalKey = route.waypoints().get(0).value();
      String groupKey = buildPendingLayoverGroupKey(service, terminalKey);
      PendingLayoverDispatchEntry entry =
          new PendingLayoverDispatchEntry(
              ticket, service, route, terminalKey, groupKey, ticket.dueAt());
      byGroup.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(entry);
      ticketToGroup.put(ticket.id(), groupKey);
    }
    if (byGroup.isEmpty()) {
      return dueTickets;
    }
    Map<String, ArrayDeque<SpawnTicket>> reorderedByGroup = new HashMap<>();
    for (Map.Entry<String, List<PendingLayoverDispatchEntry>> entry : byGroup.entrySet()) {
      String groupKey = entry.getKey();
      List<PendingLayoverDispatchEntry> groupEntries = entry.getValue();
      List<PendingLayoverDispatchEntry> orderedEntries;
      if (groupEntries == null || groupEntries.size() <= 1) {
        orderedEntries = groupEntries == null ? List.of() : groupEntries;
      } else {
        orderedEntries = orderDueGroupWithRouteRotation(groupKey, groupEntries);
      }
      ArrayDeque<SpawnTicket> queue = new ArrayDeque<>(orderedEntries.size());
      for (PendingLayoverDispatchEntry ordered : orderedEntries) {
        if (ordered != null && ordered.ticket() != null) {
          queue.add(ordered.ticket());
        }
      }
      reorderedByGroup.put(groupKey, queue);
    }

    List<SpawnTicket> reordered = new ArrayList<>(dueTickets.size());
    for (SpawnTicket original : dueTickets) {
      if (original == null) {
        continue;
      }
      String groupKey = ticketToGroup.get(original.id());
      if (groupKey == null) {
        reordered.add(original);
        continue;
      }
      ArrayDeque<SpawnTicket> queue = reorderedByGroup.get(groupKey);
      if (queue == null || queue.isEmpty()) {
        reordered.add(original);
        continue;
      }
      SpawnTicket next = queue.pollFirst();
      reordered.add(next == null ? original : next);
    }
    return reordered;
  }

  /**
   * 构建本轮 pending 派发顺序。
   *
   * <p>先保证同 route 内 FIFO，再在同一 (line + terminal) 分组中执行 route 轮转，避免同权重 route 长期偏斜。
   */
  private List<PendingLayoverDispatchEntry> buildPendingDispatchOrder(
      Optional<String> terminalFilter) {
    Map<String, List<PendingLayoverDispatchEntry>> byGroup = new LinkedHashMap<>();
    List<PendingLayoverEntry> snapshot = new ArrayList<>(pendingLayoverTickets.values());
    for (PendingLayoverEntry pendingEntry : snapshot) {
      if (pendingEntry == null || pendingEntry.ticket() == null) {
        continue;
      }
      SpawnTicket ticket = pendingEntry.ticket();
      SpawnService service = ticket.service();
      if (service == null) {
        if (preservePendingDispatchAttempt(ticket, "missing-service")) {
          continue;
        }
        pendingLayoverTickets.remove(ticket.id());
        continue;
      }
      Optional<RouteDefinition> routeOpt = routeDefinitions.findById(service.routeId());
      if (routeOpt.isEmpty()) {
        if (preservePendingDispatchAttempt(ticket, "route-definition-missing")) {
          continue;
        }
        pendingLayoverTickets.remove(ticket.id());
        spawnManager.complete(ticket);
        debugLogger.accept(
            "Layover pending 清理: route 定义缺失 route="
                + service.routeCode()
                + " ticket="
                + ticket.id());
        continue;
      }
      RouteDefinition route = routeOpt.get();
      if (route.waypoints().isEmpty()) {
        if (preservePendingDispatchAttempt(ticket, "route-waypoints-missing")) {
          continue;
        }
        pendingLayoverTickets.remove(ticket.id());
        spawnManager.complete(ticket);
        debugLogger.accept(
            "Layover pending 清理: route 无站点 route="
                + service.routeCode()
                + " ticket="
                + ticket.id());
        continue;
      }
      String terminalKey = route.waypoints().get(0).value();
      if (terminalFilter.isPresent()
          && !matchesPendingTerminal(terminalFilter.get(), terminalKey)) {
        continue;
      }
      String groupKey = buildPendingLayoverGroupKey(service, terminalKey);
      PendingLayoverDispatchEntry dispatchEntry =
          new PendingLayoverDispatchEntry(
              ticket, service, route, terminalKey, groupKey, pendingEntry.addedAt());
      byGroup.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(dispatchEntry);
    }

    List<String> groupKeys = new ArrayList<>(byGroup.keySet());
    groupKeys.sort(String.CASE_INSENSITIVE_ORDER);
    List<PendingLayoverDispatchEntry> dispatchOrder = new ArrayList<>();
    for (String groupKey : groupKeys) {
      List<PendingLayoverDispatchEntry> groupEntries = byGroup.get(groupKey);
      if (groupEntries == null || groupEntries.isEmpty()) {
        continue;
      }
      dispatchOrder.addAll(orderPendingGroupWithRouteRotation(groupKey, groupEntries));
    }
    return dispatchOrder;
  }

  /**
   * 判断 pending 票据的起点是否匹配当前 Layover 触发终端。
   *
   * <p>使用 terminalKey 语义匹配而非字符串全等，支持同站不同站台（如 {@code SURC:S:PPK:1} 与 {@code SURC:S:PPK:2}） 的即时唤醒派发。
   */
  private static boolean matchesPendingTerminal(
      String triggerTerminalKey, String pendingTerminalKey) {
    if (triggerTerminalKey == null || triggerTerminalKey.isBlank()) {
      return false;
    }
    if (pendingTerminalKey == null || pendingTerminalKey.isBlank()) {
      return false;
    }
    return TerminalKeyResolver.matches(triggerTerminalKey, pendingTerminalKey)
        || TerminalKeyResolver.matches(pendingTerminalKey, triggerTerminalKey);
  }

  /**
   * 对同一 (line + terminal) 分组执行 route 轮转。
   *
   * <p>游标按成功候选次序持续推进，确保在高频 pending 下也能保持 route 级公平。
   */
  private List<PendingLayoverDispatchEntry> orderPendingGroupWithRouteRotation(
      String groupKey, List<PendingLayoverDispatchEntry> groupEntries) {
    return orderGroupWithRouteRotation(groupKey, groupEntries, pendingLayoverRouteCursor);
  }

  /**
   * 对同组即时票据执行 route 轮转。
   *
   * <p>与 pending 轮转算法一致，但游标独立，避免两条路径互相污染轮转起点。
   */
  private List<PendingLayoverDispatchEntry> orderDueGroupWithRouteRotation(
      String groupKey, List<PendingLayoverDispatchEntry> groupEntries) {
    return orderGroupWithRouteRotation(groupKey, groupEntries, immediateLayoverRouteCursor);
  }

  private static List<PendingLayoverDispatchEntry> orderGroupWithRouteRotation(
      String groupKey,
      List<PendingLayoverDispatchEntry> groupEntries,
      java.util.concurrent.ConcurrentMap<String, Integer> routeCursor) {
    Comparator<PendingLayoverDispatchEntry> comparator =
        Comparator.comparing((PendingLayoverDispatchEntry entry) -> entry.ticket().dueAt())
            .thenComparingLong(entry -> entry.ticket().sequenceNumber())
            .thenComparing(PendingLayoverDispatchEntry::addedAt)
            .thenComparing(entry -> entry.ticket().id().toString());
    List<PendingLayoverDispatchEntry> sorted = new ArrayList<>(groupEntries);
    sorted.sort(comparator);
    if (sorted.size() <= 1) {
      return sorted;
    }

    Map<UUID, ArrayDeque<PendingLayoverDispatchEntry>> byRouteQueue = new LinkedHashMap<>();
    for (PendingLayoverDispatchEntry entry : sorted) {
      byRouteQueue
          .computeIfAbsent(entry.service().routeId(), ignored -> new ArrayDeque<>())
          .add(entry);
    }
    List<UUID> routeOrder = new ArrayList<>(byRouteQueue.keySet());
    routeOrder.sort(
        Comparator.comparing(
            routeId -> {
              ArrayDeque<PendingLayoverDispatchEntry> queue = byRouteQueue.get(routeId);
              PendingLayoverDispatchEntry first = queue == null ? null : queue.peekFirst();
              String routeCode = first == null ? "" : first.service().routeCode();
              return routeCode.toLowerCase(Locale.ROOT);
            }));
    if (routeOrder.size() <= 1) {
      return sorted;
    }

    int routeCount = routeOrder.size();
    int startCursor = Math.floorMod(routeCursor.getOrDefault(groupKey, 0), Math.max(1, routeCount));
    int cursor = startCursor;
    List<PendingLayoverDispatchEntry> ordered = new ArrayList<>(sorted.size());
    int remaining = sorted.size();
    while (remaining > 0) {
      boolean assigned = false;
      for (int step = 0; step < routeCount; step++) {
        int index = (cursor + step) % routeCount;
        UUID routeId = routeOrder.get(index);
        ArrayDeque<PendingLayoverDispatchEntry> queue = byRouteQueue.get(routeId);
        if (queue == null || queue.isEmpty()) {
          continue;
        }
        ordered.add(queue.removeFirst());
        remaining--;
        cursor = (index + 1) % routeCount;
        assigned = true;
        break;
      }
      if (!assigned) {
        break;
      }
    }
    routeCursor.put(groupKey, (startCursor + 1) % routeCount);
    return ordered;
  }

  private static String buildPendingLayoverGroupKey(SpawnService service, String terminalKey) {
    if (service == null) {
      return terminalKey == null ? "" : terminalKey.toLowerCase(Locale.ROOT);
    }
    String lineKey = service.lineId() == null ? "unknown" : service.lineId().toString();
    String terminal = terminalKey == null ? "" : terminalKey.trim().toLowerCase(Locale.ROOT);
    return lineKey + "|" + terminal;
  }

  /**
   * 尝试复用待命列车。
   *
   * <p>若当前没有可用候选，会把票据放入 pending 队列并保留首次入队时间；若候选存在但暂时被闭塞或门控阻塞，则保持 pending，等待后续 layover 通知或超时刷新。
   *
   * <p>成功时会同步写入生命周期标签、清理 pending，并通知调度层刷新相关占用。
   */
  /**
   * 注册车辆复用闸。
   *
   * <p>传入 {@code null} 恢复"恒放行"。闸只作用于 OPERATION 票：RETURN 票必须仍然能复用列车， 否则被拒绝复用的车反而没有回家的手段。
   *
   * @param gate 给定列车名，返回是否允许再接一班运营车次
   */
  public void setLayoverReuseGate(java.util.function.Predicate<String> gate) {
    this.layoverReuseGate = gate == null ? trainName -> true : gate;
  }

  /**
   * 注册回库复用闸。
   *
   * <p>传入 {@code null} 恢复"恒放行"。闸只作用于 RETURN 票：它回答的是"这辆车现在能不能被送回车库"， 用来防止按表发出的回库票把正等着跑下一班的车抓走。
   *
   * @param gate 给定列车名，返回是否允许被回库票带走
   */
  public void setReturnReuseGate(java.util.function.Predicate<String> gate) {
    this.returnReuseGate = gate == null ? trainName -> true : gate;
  }

  /**
   * 注册票据级候选过滤。
   *
   * <p>与两道闸的区别：闸只看列车（额度用完了没有），过滤同时看票（这张票属于哪个交路）。 过滤掉全部候选时票据进入 pending 等待，不会新出库。传入 {@code null}
   * 恢复"恒放行"。
   */
  public void setLayoverCandidateFilter(
      java.util.function.BiPredicate<SpawnTicket, String> filter) {
    this.layoverCandidateFilter = filter == null ? (ticket, trainName) -> true : filter;
  }

  /**
   * 注册票据级到期时刻。
   *
   * <p>pending 清理时先问它：到期的票直接作废并向 SpawnManager 报完成，不走全局的 max-age。 传入 {@code null} 恢复"没有到期"。
   */
  public void setTicketExpiry(java.util.function.Function<SpawnTicket, Optional<Instant>> expiry) {
    this.ticketExpiry = expiry == null ? ticket -> Optional.empty() : expiry;
  }

  /**
   * 注册派发成功回调。
   *
   * <p>在票据向 SpawnManager 报完成之前调用，带最终的列车名（复用时是改名后的名字）。传入 {@code null} 恢复空回调。
   */
  public void setDispatchListener(java.util.function.BiConsumer<SpawnTicket, String> listener) {
    this.dispatchListener = listener == null ? (ticket, trainName) -> {} : listener;
  }

  private void notifyDispatched(SpawnTicket ticket, String trainName) {
    if (ticket == null || trainName == null) {
      return;
    }
    try {
      dispatchListener.accept(ticket, trainName);
    } catch (RuntimeException failure) {
      debugLogger.accept(
          "派发回调异常: ticket="
              + ticket.id()
              + " train="
              + trainName
              + " error="
              + failure.getMessage());
    }
  }

  private boolean tryReuseLayover(
      Optional<StorageProvider> providerOpt,
      SpawnTicket ticket,
      SpawnService service,
      RouteDefinition route,
      Instant now,
      boolean pendingAttempt) {
    if (providerOpt.isEmpty()) {
      putPendingLayoverTicket(ticket, now);
      debugLogger.accept("Layover 复用等待: 缺少 StorageProvider，等待下一轮 spawn tick");
      return false;
    }
    String startNodeVal = route.waypoints().get(0).value();
    String ticketId = ticket.id().toString();
    Optional<LayoverRegistry.LayoverCandidate> attemptOwner =
        layoverRegistry.findDispatchAttemptOwner(ticketId);
    List<LayoverRegistry.LayoverCandidate> candidates =
        attemptOwner.map(List::of).orElseGet(() -> layoverRegistry.findCandidates(startNodeVal));
    if (candidates.isEmpty()) {
      if (!pendingAttempt) {
        putPendingLayoverTicket(ticket, now);
        debugLogger.accept("Layover 复用等待: route=" + service.routeCode() + " start=" + startNodeVal);
      }
      return false;
    }
    // 过滤掉 readyAt 尚未到达（dwell 未结束）的候选
    List<LayoverRegistry.LayoverCandidate> readyCandidates;
    if (attemptOwner.isPresent()) {
      readyCandidates = candidates;
    } else {
      readyCandidates = new ArrayList<>();
      for (LayoverRegistry.LayoverCandidate c : candidates) {
        if (c.readyAt().isAfter(now)) {
          continue;
        }
        readyCandidates.add(c);
      }
    }
    if (readyCandidates.isEmpty()) {
      // 所有候选都在 dwell 中，稍后重试
      if (!pendingAttempt) {
        putPendingLayoverTicket(ticket, now);
        debugLogger.accept(
            "Layover 复用等待 dwell: route="
                + service.routeCode()
                + " candidates="
                + candidates.size());
      }
      return false;
    }
    RouteOperationType operationType =
        resolveRouteOperationType(providerOpt, service.routeId())
            .orElse(RouteOperationType.OPERATION);
    if (operationType == RouteOperationType.OPERATION) {
      // 车辆交路额度用完的车不再接运营班次。这里只做否决，不改它的状态：
      // 它会留在 layover 闲置，由 ReclaimManager 在既有的回收窗口里派 RETURN 票送它回库。
      java.util.function.Predicate<String> gate = this.layoverReuseGate;
      List<LayoverRegistry.LayoverCandidate> allowed = new ArrayList<>(readyCandidates.size());
      for (LayoverRegistry.LayoverCandidate candidate : readyCandidates) {
        if (gate.test(candidate.trainName())) {
          allowed.add(candidate);
        }
      }
      if (allowed.size() != readyCandidates.size()) {
        debugLogger.accept(
            "Layover 复用被车辆交路否决: route="
                + service.routeCode()
                + " denied="
                + (readyCandidates.size() - allowed.size())
                + " remaining="
                + allowed.size());
      }
      readyCandidates = allowed;
      if (readyCandidates.isEmpty()) {
        if (!pendingAttempt) {
          putPendingLayoverTicket(ticket, now);
        }
        return false;
      }
    }
    if (operationType == RouteOperationType.RETURN) {
      // 回库票只能带走交路已经跑完（或根本不在交路里）的车。否则按表发出的回库票会把
      // 正在终点等着跑下一班的车送回车库，那一班就开了天窗，而时刻表侧看不出原因。
      java.util.function.Predicate<String> gate = this.returnReuseGate;
      List<LayoverRegistry.LayoverCandidate> allowed = new ArrayList<>(readyCandidates.size());
      for (LayoverRegistry.LayoverCandidate candidate : readyCandidates) {
        if (gate.test(candidate.trainName())) {
          allowed.add(candidate);
        }
      }
      if (allowed.size() != readyCandidates.size()) {
        debugLogger.accept(
            "Layover 回库被车辆交路否决: route="
                + service.routeCode()
                + " denied="
                + (readyCandidates.size() - allowed.size())
                + " remaining="
                + allowed.size());
      }
      readyCandidates = allowed;
      if (readyCandidates.isEmpty()) {
        if (!pendingAttempt) {
          putPendingLayoverTicket(ticket, now);
        }
        return false;
      }
    }
    java.util.function.BiPredicate<SpawnTicket, String> filter = this.layoverCandidateFilter;
    List<LayoverRegistry.LayoverCandidate> matching = new ArrayList<>(readyCandidates.size());
    for (LayoverRegistry.LayoverCandidate candidate : readyCandidates) {
      if (filter.test(ticket, candidate.trainName())) {
        matching.add(candidate);
      }
    }
    if (matching.size() != readyCandidates.size()) {
      debugLogger.accept(
          "Layover 候选被票据过滤: route="
              + service.routeCode()
              + " ticket="
              + ticket.id()
              + " rejected="
              + (readyCandidates.size() - matching.size())
              + " remaining="
              + matching.size());
    }
    readyCandidates = matching;
    if (readyCandidates.isEmpty()) {
      // 本交路的车还没到：等它，不抓别人的车，也不新出库。到期由 ticketExpiry 决定。
      if (!pendingAttempt) {
        putPendingLayoverTicket(ticket, now);
      }
      return false;
    }
    ServiceTicket serviceTicket =
        new ServiceTicket(
            ticketId,
            ticket.scheduledTime(),
            service.routeId(),
            startNodeVal,
            0,
            toTicketMode(operationType));
    for (LayoverRegistry.LayoverCandidate candidate : readyCandidates) {
      SpawnControl.Lease spawnLease =
          tryAcquireSpawnControlForLayover(
                  providerOpt,
                  ticket,
                  SpawnControl.LeaseKind.LAYOVER_REUSE,
                  Optional.of(candidate),
                  now)
              .orElse(null);
      if (spawnLease == null) {
        continue;
      }
      LayoverDispatchResult dispatch =
          runtimeDispatchService.dispatchLayover(candidate, serviceTicket);
      if (dispatch.dispatched()) {
        String committedTrainName = dispatch.trainName().orElseThrow();
        applyDispatchLifecycleTags(providerOpt, committedTrainName, service, operationType);
        notifyDispatched(ticket, committedTrainName);
        spawnManager.complete(ticket);
        spawnSuccess.increment();
        pendingLayoverTickets.remove(ticket.id());
        debugLogger.accept("Layover 复用成功: " + committedTrainName + " -> " + service.routeCode());
        return true;
      }
      releaseSpawnLease(spawnLease);
      if (layoverRegistry.findDispatchAttemptOwner(ticketId).isPresent()) {
        break;
      }
    }
    putPendingLayoverTicket(ticket, now);
    debugLogger.accept(
        "Layover 复用受阻: route="
            + service.routeCode()
            + " readyCandidates="
            + readyCandidates.size());
    return false;
  }

  private static ServiceTicket.TicketMode toTicketMode(RouteOperationType operationType) {
    if (operationType == RouteOperationType.RETURN) {
      return ServiceTicket.TicketMode.RETURN;
    }
    return ServiceTicket.TicketMode.OPERATION;
  }

  private Optional<RouteOperationType> resolveRouteOperationType(
      Optional<StorageProvider> providerOpt, UUID routeId) {
    if (providerOpt.isEmpty() || routeId == null) {
      return Optional.empty();
    }
    return providerOpt.get().routes().findById(routeId).map(Route::operationType);
  }

  /**
   * 更新列车生命周期标签。
   *
   * <p>约定：
   *
   * <ul>
   *   <li>OPERATION 发车成功：{@code FTA_OP_TRIPS +1}
   *   <li>CREATE/RETURN 发车成功：{@code FTA_OP_TRIPS=0}
   *   <li>若 route 绑定了交路组，写入 {@code FTA_SPAWN_GROUP}
   *   <li>若交路组配置了 {@code maxOperationTrips}，写入 {@code FTA_OP_MAX}
   * </ul>
   */
  private void applyDispatchLifecycleTags(
      Optional<StorageProvider> providerOpt,
      String trainName,
      SpawnService service,
      RouteOperationType operationType) {
    if (service == null) {
      return;
    }
    applyDispatchLifecycleTags(providerOpt, trainName, service.routeId(), operationType);
  }

  private void applyDispatchLifecycleTags(
      Optional<StorageProvider> providerOpt,
      String trainName,
      UUID routeId,
      RouteOperationType operationType) {
    if (trainName == null || trainName.isBlank() || routeId == null || operationType == null) {
      return;
    }
    TrainProperties properties = TrainPropertiesStore.get(trainName);
    if (properties == null) {
      return;
    }

    int currentTrips = TrainTagHelper.readIntTag(properties, TAG_OPERATION_TRIPS).orElse(0);
    int nextTrips =
        switch (operationType) {
          case OPERATION -> Math.max(0, currentTrips + 1);
          case CREATE, RETURN -> 0;
        };
    TrainTagHelper.writeTag(properties, TAG_OPERATION_TRIPS, String.valueOf(nextTrips));

    Optional<String> groupOpt = resolveServiceSpawnGroup(providerOpt, routeId);
    if (groupOpt.isPresent()) {
      TrainTagHelper.writeTag(properties, TAG_CIRCULATION_GROUP, groupOpt.get());
    } else {
      TrainTagHelper.removeTagKey(properties, TAG_CIRCULATION_GROUP);
    }

    Optional<Integer> maxTripsOpt = resolveServiceMaxOperationTrips(providerOpt, routeId, groupOpt);
    if (maxTripsOpt.isPresent()) {
      TrainTagHelper.writeTag(
          properties, TAG_MAX_OPERATION_TRIPS, String.valueOf(maxTripsOpt.get()));
    } else {
      TrainTagHelper.removeTagKey(properties, TAG_MAX_OPERATION_TRIPS);
    }
  }

  private Optional<String> resolveServiceSpawnGroup(
      Optional<StorageProvider> providerOpt, UUID routeId) {
    if (providerOpt.isEmpty() || routeId == null) {
      return Optional.empty();
    }
    return providerOpt
        .get()
        .routes()
        .findById(routeId)
        .flatMap(route -> readSpawnGroup(route.metadata()));
  }

  private Optional<Integer> resolveServiceMaxOperationTrips(
      Optional<StorageProvider> providerOpt, UUID routeId, Optional<String> groupOpt) {
    if (providerOpt.isEmpty() || routeId == null) {
      return Optional.empty();
    }
    StorageProvider provider = providerOpt.get();
    Optional<Route> routeOpt = provider.routes().findById(routeId);
    if (routeOpt.isEmpty()) {
      return Optional.empty();
    }
    Route route = routeOpt.get();
    Optional<Integer> routeOverride =
        readPositiveInt(route.metadata(), "spawn_group_max_trips", "max_operation_trips");
    if (routeOverride.isPresent()) {
      return routeOverride;
    }
    if (groupOpt.isEmpty()) {
      return Optional.empty();
    }
    Optional<Line> lineOpt = provider.lines().findById(route.lineId());
    if (lineOpt.isEmpty()) {
      return Optional.empty();
    }
    return LineSpawnMetadata.parseGroupMaxOperationTrips(lineOpt.get().metadata(), groupOpt.get());
  }

  private static Optional<String> readSpawnGroup(Map<String, Object> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return Optional.empty();
    }
    Object raw = metadata.get("spawn_group");
    if (raw == null) {
      return Optional.empty();
    }
    String group = raw.toString().trim();
    return group.isBlank() ? Optional.empty() : Optional.of(group);
  }

  private static Optional<Integer> readPositiveInt(Map<String, Object> metadata, String... keys) {
    if (metadata == null || metadata.isEmpty() || keys == null) {
      return Optional.empty();
    }
    for (String key : keys) {
      if (key == null || key.isBlank()) {
        continue;
      }
      Object raw = metadata.get(key);
      Integer value = tryParseInteger(raw);
      if (value != null && value > 0) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }

  private static Integer tryParseInteger(Object raw) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof Number number) {
      return number.intValue();
    }
    if (raw instanceof String text) {
      String trimmed = text.trim();
      if (trimmed.isBlank()) {
        return null;
      }
      try {
        return Integer.parseInt(trimmed);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  /**
   * 写入 pending layover 票据。
   *
   * <p>若票据已在 pending 中，保留首次入队时间，避免重试时不断刷新 addedAt 导致超时清理永不触发。
   */
  private void putPendingLayoverTicket(SpawnTicket ticket, Instant now) {
    if (ticket == null || now == null) {
      return;
    }
    pendingLayoverTickets.compute(
        ticket.id(),
        (ignored, existing) -> {
          Instant addedAt = existing == null ? now : existing.addedAt();
          Instant firstAddedAt = existing == null ? now : existing.firstAddedAt();
          return new PendingLayoverEntry(ticket, addedAt, firstAddedAt);
        });
  }

  /**
   * 在破坏性 pending 生命周期操作前确认票据是否已进入折返提交事务。
   *
   * <p>dispatch attempt 可能已经完成占用 handoff 与列车改名，此时 pending 是失败重试的唯一稳定票据。hard expiry、fallback、运维
   * clear 或最大重试都不得把它当作普通 backlog 删除；告警按票据节流，避免每 tick 刷屏。
   */
  private boolean preservePendingDispatchAttempt(SpawnTicket ticket, String attemptedAction) {
    if (ticket == null || !layoverRegistry.hasDispatchAttemptForTicket(ticket.id().toString())) {
      return false;
    }
    String action =
        attemptedAction == null || attemptedAction.isBlank() ? "unknown" : attemptedAction;
    SpawnService service = ticket.service();
    String routeCode = service == null ? "?" : service.routeCode();
    warnThrottled(
        "pending-dispatch-attempt:" + ticket.id(),
        "[FTA] 折返票据已进入 handoff，拒绝破坏性 pending 操作: ticket="
            + ticket.id()
            + " route="
            + routeCode
            + " action="
            + action);
    return true;
  }

  /**
   * 准备 Depot 发车的逻辑授权。
   *
   * <p>本方法是物理实体化前的唯一预检入口。它可以建立临时动态授权、取得发车租约并进行 preview，但绝不调用 {@link
   * DepotSpawner#spawn(StorageProvider, SpawnTicket, String,
   * Instant)}。任何预检失败都会在返回前撤销已建立的临时状态并安排原票据重试。
   *
   * @param origin 常规或 fallback 发车来源；决定重试原因与 depot 选择账本的归属
   * @return 已完成预检的上下文；空值表示失败已被处理
   */
  private Optional<PreparedDepotSpawn> prepareDepotSpawn(
      StorageProvider provider,
      SpawnTicket ticket,
      SpawnService service,
      RouteDefinition route,
      Line line,
      Route routeEntity,
      SpawnControl.Lease spawnLease,
      Map<String, Integer> selectedThisTick,
      Instant now,
      DepotSpawnOrigin origin) {
    DepotSpawnOrigin effectiveOrigin = origin == null ? DepotSpawnOrigin.NORMAL : origin;
    String reasonPrefix = effectiveOrigin.reasonPrefix();

    // 【准入控制】全网在网列车上限。
    //
    // 放在这里是因为本方法是"物理实体化前的唯一预检入口"——NORMAL 与 FALLBACK 两条路径都经过它。
    // 拥堵闸门 shouldHoldByCongestion 只挂在常规路径上，layover 降级补发那条 return 在它之前，
    // 于是降级补发能绕开一切拥堵约束往网里加车；这道闸门堵住的就是那个洞。
    //
    // 只拦"新造车"，不拦 layover 复用：复用的车本来就在网里，拦它只会让车积在终点站。
    if (shouldHoldByFleetCap(line, routeEntity)) {
      releaseSpawnLease(spawnLease);
      // 用 deferWithoutAttempt 而不是 requeue：requeue 会 +1 attempts，到 max-attempts
      // 就 spawnManager.complete(ticket) 把票据**丢掉**。
      // 持续顶住上限一段时间后本该发的车就再也不会发了——
      // 那是"取消发车"，不是"推迟发车"，等网疏通了班次已经凭空少了一批。
      // 无限延后的兜底是 spawn.queued-ticket-max-age-seconds。
      deferByGate(ticket, now, reasonPrefix + "fleet-cap");
      return Optional.empty();
    }

    List<SpawnDepot> lineDepots = LineSpawnMetadata.parseDepots(line.metadata());
    Map<String, Integer> depotSelections =
        selectedThisTick == null ? new HashMap<>() : selectedThisTick;
    Optional<SpawnDepot> selectedDepotOpt = Optional.empty();
    if (!lineDepots.isEmpty() && ticket.selectedDepotNodeId().isEmpty()) {
      LineRuntimeSnapshot runtimeSnapshot = LineRuntimeSnapshot.capture(runtimeDispatchService);
      selectedDepotOpt =
          selectBalancedDepot(
              provider, line.id(), lineDepots, runtimeSnapshot, depotSelections, now);
    }
    SpawnTicket effectiveTicket =
        selectedDepotOpt.map(depot -> ticket.withSelectedDepot(depot.nodeId())).orElse(ticket);
    effectiveTicket =
        materializeDynamicDepotSelection(
            provider,
            service,
            effectiveTicket,
            LineRuntimeSnapshot.capture(runtimeDispatchService),
            depotSelections,
            now);
    if (effectiveOrigin.fallback()) {
      recordSelectedDepotForTick(effectiveTicket, lineDepots, selectedDepotOpt, depotSelections);
    }

    String destinationCode =
        RouteDestinationResolver.resolve(provider, routeEntity)
            .map(RouteDestinationResolver.DestinationInfo::code)
            .orElse(routeEntity.code());
    String trainName =
        TrainNameFormatter.buildTrainName(
            service.operatorCode(),
            service.lineCode(),
            routeEntity.patternType(),
            destinationCode,
            ticket.id());
    if (runtimeDispatchService.hasMaterializedSpawnRollbackQuarantine(trainName)) {
      releaseSpawnLease(spawnLease);
      deferWithoutAttempt(effectiveTicket, now, "materialized-rollback-identity-live");
      return Optional.empty();
    }

    Optional<java.util.UUID> worldIdOpt =
        resolveDepotWorldId(service, effectiveTicket.selectedDepotNodeId());
    if (worldIdOpt.isEmpty()) {
      releaseSpawnLease(spawnLease);
      requeue(effectiveTicket, now, reasonPrefix + "depot-world-missing");
      return Optional.empty();
    }
    Optional<RailGraph> graphOpt =
        railGraphService.getSnapshot(worldIdOpt.get()).map(s -> s.graph());
    if (graphOpt.isEmpty()) {
      releaseSpawnLease(spawnLease);
      requeue(effectiveTicket, now, reasonPrefix + "graph-missing");
      return Optional.empty();
    }

    List<NodeId> spawnWaypoints = resolveDepotSpawnWaypoints(route, service, effectiveTicket);
    Optional<List<NodeId>> preparedWaypointsOpt =
        runtimeDispatchService.prepareDepotSpawnDynamicAuthority(
            trainName, route, spawnWaypoints, graphOpt.get(), now);
    if (preparedWaypointsOpt.isEmpty()) {
      runtimeDispatchService.cancelPreparedDepotSpawnDynamicAuthority(trainName);
      releaseSpawnLease(spawnLease);
      requeue(effectiveTicket, now, reasonPrefix + "dynamic-authority-unavailable");
      return Optional.empty();
    }

    ConfigManager.RuntimeSettings runtime = configManager.current().runtimeSettings();
    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(
            graphOpt.get(),
            depotSpawnLookaheadEdges(runtime),
            runtime.minClearEdges(),
            runtime.rearGuardEdges(),
            runtime.switcherZoneEdges(),
            debugLogger);
    Optional<DepotGateRequest> gateRequestOpt =
        buildDepotSpawnGateRequest(
            builder,
            trainName,
            route,
            preparedWaypointsOpt.get(),
            service,
            effectiveTicket,
            routeEntity.operationType(),
            now);
    if (gateRequestOpt.isEmpty()) {
      runtimeDispatchService.cancelPreparedDepotSpawnDynamicAuthority(trainName);
      releaseSpawnLease(spawnLease);
      requeue(effectiveTicket, now, reasonPrefix + "occupancy-context-failed");
      return Optional.empty();
    }
    DepotGateRequest gateRequest = gateRequestOpt.get();
    OccupancyRequest authorityRequest = gateRequest.request();
    if (!runtimeDispatchService.smartDepotAdmissionAllowsSpawn(
        trainName, graphOpt.get(), gateRequest.context())) {
      runtimeDispatchService.cancelPreparedDepotSpawnDynamicAuthority(trainName);
      releaseSpawnLease(spawnLease);
      deferBlockedAtDepot(effectiveTicket, now, reasonPrefix + "smart-depot-long-single-held");
      return Optional.empty();
    }
    LaunchAuthorizationService.AuthorizationResult authorization =
        previewSpawnGate(authorityRequest);
    if (!authorization.allowed()) {
      logDepotGateBlockedTrace(
          effectiveTicket,
          service,
          route,
          trainName,
          lineDepots,
          gateRequest,
          authorization,
          spawnLease,
          reasonPrefix + "preview");
      runtimeDispatchService.cancelPreparedDepotSpawnDynamicAuthority(trainName);
      releaseSpawnLease(spawnLease);
      deferBlockedAtDepot(
          effectiveTicket,
          now,
          reasonPrefix + "gate-blocked:" + spawnGateSignalText(authorization));
      return Optional.empty();
    }

    OptionalLong startupRecoveryEpoch = runtimeDispatchService.captureReadyStartupRecoveryEpoch();
    if (startupRecoveryEpoch.isEmpty()) {
      abortDepotSpawnForRecovery(
          effectiveTicket,
          now,
          trainName,
          spawnLease,
          Optional.empty(),
          reasonPrefix + "startup-recovery-active");
      return Optional.empty();
    }
    return Optional.of(
        new PreparedDepotSpawn(
            effectiveTicket,
            trainName,
            spawnLease,
            lineDepots,
            gateRequest,
            startupRecoveryEpoch.getAsLong()));
  }

  /**
   * 将已通过预检的 Depot 发车实体化。
   *
   * <p>此方法只负责 {@link DepotSpawner} 调用及其前置资源收口；物理 group 一旦返回，调用方必须立即交给 {@link
   * #finalizeMaterializedDepotSpawn(MaterializedDepotSpawnContext)}，不能在这里加入额外初始化。
   */
  private Optional<DepotSpawner.MaterializedSpawn> materializePreparedDepotSpawn(
      StorageProvider provider, PreparedDepotSpawn prepared, Instant now, DepotSpawnOrigin origin) {
    DepotSpawnOrigin effectiveOrigin = origin == null ? DepotSpawnOrigin.NORMAL : origin;
    if (!spawnBudgetLeft()) {
      // 常规、降级、pending 降级三条出库路径都经过这里：名额只在真要生成实体时扣，也只在这里扣。
      runtimeDispatchService.cancelPreparedDepotSpawnDynamicAuthority(prepared.trainName());
      releaseSpawnLease(prepared.spawnLease());
      deferWithoutAttempt(prepared.ticket(), now, "spawn-per-tick-limit");
      return Optional.empty();
    }
    materializationsThisTick++;
    try {
      Optional<DepotSpawner.MaterializedSpawn> materializedSpawn =
          depotSpawner.spawn(provider, prepared.ticket(), prepared.trainName(), now);
      if (materializedSpawn.isPresent()) {
        return materializedSpawn;
      }
    } catch (RuntimeException | LinkageError error) {
      debugLogger.accept(
          (effectiveOrigin.fallback() ? "Layover 降级发车异常" : "自动发车异常")
              + ": spawn 抛出异常 train="
              + prepared.trainName()
              + " error="
              + error);
    }
    runtimeDispatchService.cancelPreparedDepotSpawnDynamicAuthority(prepared.trainName());
    releaseSpawnLease(prepared.spawnLease());
    occupancyManager.releaseByTrain(prepared.trainName());
    requeue(prepared.ticket(), now, effectiveOrigin.reasonPrefix() + "spawn-failed");
    return Optional.empty();
  }

  /**
   * 从 Depot 直接发车的降级路径。
   *
   * <p>仅用于 RETURN 票据的 fallback 补发，不参与常规运营调度。若发车成功，会同步写入生命周期标签并刷新相关占用；失败则回到重试队列。
   *
   * @param provider 存储接口
   * @param ticket 发车票据
   * @param service 发车服务
   * @param route 路线定义
   * @param line 线路
   * @param now 当前时间
   * @param selectedThisTick 本 tick 已选 depot 计数，用于避免 fallback 连续压到同一短股道
   * @return 是否成功发车
   */
  private boolean trySpawnFromDepot(
      StorageProvider provider,
      SpawnTicket ticket,
      SpawnService service,
      RouteDefinition route,
      Line line,
      Instant now,
      Map<String, Integer> selectedThisTick) {

    if (hasMaterializedSpawnTransaction(ticket.id())) {
      deferWithoutAttempt(ticket, now, "materialized-transaction-active");
      return false;
    }

    Route routeEntity = provider.routes().findById(service.routeId()).orElse(null);
    if (routeEntity == null) {
      requeue(ticket, now, "fallback-route-not-found");
      return false;
    }

    Optional<SpawnControl.Lease> spawnLeaseOpt =
        tryAcquireSpawnControlForTicket(
            provider,
            line,
            ticket,
            service,
            routeEntity,
            SpawnControl.LeaseKind.FALLBACK,
            Optional.empty(),
            now);
    if (spawnLeaseOpt.isEmpty()) {
      requeue(ticket, now, "fallback-line-cap");
      return false;
    }
    SpawnControl.Lease spawnLease = spawnLeaseOpt.get();

    Optional<PreparedDepotSpawn> preparedOpt =
        prepareDepotSpawn(
            provider,
            ticket,
            service,
            route,
            line,
            routeEntity,
            spawnLease,
            selectedThisTick,
            now,
            DepotSpawnOrigin.FALLBACK);
    if (preparedOpt.isEmpty()) {
      return false;
    }
    PreparedDepotSpawn prepared = preparedOpt.get();
    Optional<DepotSpawner.MaterializedSpawn> materializedSpawnOpt =
        materializePreparedDepotSpawn(provider, prepared, now, DepotSpawnOrigin.FALLBACK);
    if (materializedSpawnOpt.isEmpty()) {
      return false;
    }
    return finalizeMaterializedDepotSpawn(
        new MaterializedDepotSpawnContext(
            provider,
            prepared.ticket(),
            service,
            route,
            routeEntity.operationType(),
            prepared.trainName(),
            prepared.spawnLease(),
            prepared.gateRequest(),
            prepared.gateRequest().request(),
            prepared.lineDepots(),
            materializedSpawnOpt.get(),
            prepared.recoveryEpoch(),
            now,
            true));
  }

  /**
   * 提交已实体化的 Depot 发车。
   *
   * <p>该方法是常规与 fallback 两条 Depot 入口唯一允许跨越“物理 group 已存在”边界的位置。它先取得硬授权，再写入 expected
   * identity，随后才允许首次信号刷新；footprint 未水合时保留事务而不完成票据。任何失败都会先收容实体，再释放账务资源。
   *
   * @return 已完成或已安全登记为等待 footprint promotion 时返回 {@code true}
   */
  private boolean finalizeMaterializedDepotSpawn(MaterializedDepotSpawnContext context) {
    RuntimeTrainHandle train = context.materializedSpawn().train();
    String reasonPrefix = context.fallback() ? "fallback-" : "";
    try {
      if (!runtimeDispatchService.isStartupRecoveryEpochReady(context.recoveryEpoch())) {
        retainMaterializedDepotSpawnRollback(
            context, reasonPrefix + "startup-recovery-epoch-changed");
        return false;
      }
      LaunchAuthorizationService.AuthorizationResult authorization =
          acquireSpawnGate(context.authorityRequest());
      if (!authorization.allowed()) {
        logDepotGateBlockedTrace(
            context.ticket(),
            context.service(),
            context.route(),
            context.trainName(),
            context.lineDepots(),
            context.gateRequest(),
            authorization,
            context.spawnLease(),
            context.fallback() ? "fallback-acquire" : "acquire");
        retainMaterializedDepotSpawnRollback(
            context, reasonPrefix + "gate-blocked:" + spawnGateSignalText(authorization));
        return false;
      }
      if (!runtimeDispatchService.isStartupRecoveryEpochReady(context.recoveryEpoch())) {
        retainMaterializedDepotSpawnRollback(
            context, reasonPrefix + "startup-recovery-epoch-changed-after-acquire");
        return false;
      }
      TrainProperties properties =
          initializeMaterializedSpawnWithRollbackMarker(context.materializedSpawn());
      if (applyPreparedSpawnDestination(properties, context.gateRequest().effectiveWaypoints())) {
        applySpawnLifecycleTags(
            Optional.of(context.provider()),
            properties,
            context.service(),
            context.operationType());
      }
      TrainSpawnTagInitializer.markMaterializedSpawnTransactionPending(properties);
      if (!registerExpectedMaterializedSpawnBeforeFirstRefresh(
          runtimeDispatchService,
          train,
          context.authorityRequest(),
          context.recoveryEpoch(),
          () -> runtimeDispatchService.refreshSignal(train))) {
        retainMaterializedDepotSpawnRollback(
            context, reasonPrefix + "expected-spawn-physical-registration-failed");
        return false;
      }
      runtimeDispatchService.requestSignalReevaluationForResources(
          context.authorityRequest().resourceList(), context.trainName());
      if (!runtimeDispatchService.isStartupRecoveryEpochReady(context.recoveryEpoch())) {
        retainMaterializedDepotSpawnRollback(
            context, reasonPrefix + "startup-recovery-epoch-changed-before-complete");
        return false;
      }
      RuntimeDispatchService.ExpectedMaterializedSpawnStatus materializedStatus =
          runtimeDispatchService.expectedMaterializedSpawnStatus(train, context.recoveryEpoch());
      if (materializedStatus
          == RuntimeDispatchService.ExpectedMaterializedSpawnStatus.PROVISIONAL) {
        if (deferMaterializedSpawnUntilPromotion(
            context.ticket(),
            context.service(),
            context.trainName(),
            context.spawnLease(),
            train,
            context.recoveryEpoch(),
            context.now(),
            context.fallback())) {
          return true;
        }
        retainMaterializedDepotSpawnRollback(
            context, reasonPrefix + "pending-materialized-spawn-registration-conflict");
        return false;
      }
      if (materializedStatus != RuntimeDispatchService.ExpectedMaterializedSpawnStatus.PROMOTED) {
        retainMaterializedDepotSpawnRollback(
            context, reasonPrefix + "expected-spawn-physical-state-lost-before-complete");
        return false;
      }
      notifyDispatched(context.ticket(), context.trainName());
      spawnManager.complete(context.ticket());
      clearCompletedMaterializedSpawnMarker(train, context.trainName());
    } catch (RuntimeException | LinkageError failure) {
      debugLogger.accept(
          (context.fallback() ? "Layover 降级发车实体化事务异常: train=" : "自动发车实体化事务异常: train=")
              + context.trainName()
              + " error="
              + failure.getClass().getSimpleName()
              + ":"
              + String.valueOf(failure.getMessage()));
      retainMaterializedDepotSpawnRollback(
          context,
          reasonPrefix
              + "materialized-spawn-initialization-failed:"
              + failure.getClass().getSimpleName());
      return false;
    }
    recordMaterializedSpawnSuccess(
        context.ticket(), context.service(), context.trainName(), context.fallback());
    return true;
  }

  /**
   * 在任何可失败初始化前为精确物理编组写入持久化回滚墓碑。
   *
   * <p>TrainCarts 初始化会规范化生命周期 tags，因此 finally 中必须再次确认墓碑仍存在。即使初始化动作删除墓碑后抛错，外层事务也能在本次进程中收容列车，且
   * 崩溃恢复仍不会把未提交编组误认为可运营列车。
   *
   * @param materializedSpawn 已经存在的精确物理编组及其延后初始化动作
   * @return 同一物理编组的 TrainCarts 属性
   */
  static TrainProperties initializeMaterializedSpawnWithRollbackMarker(
      DepotSpawner.MaterializedSpawn materializedSpawn) {
    DepotSpawner.MaterializedSpawn requiredSpawn =
        Objects.requireNonNull(materializedSpawn, "materializedSpawn");
    TrainProperties properties =
        Objects.requireNonNull(requiredSpawn.train().properties(), "properties");
    TrainSpawnTagInitializer.markMaterializedSpawnTransactionPending(properties);
    try {
      requiredSpawn.initialize();
    } finally {
      TrainSpawnTagInitializer.markMaterializedSpawnTransactionPending(properties);
    }
    return properties;
  }

  /** 将同一实体化事务的所有失败统一交给“先收容、后释放”的回滚入口。 */
  private void retainMaterializedDepotSpawnRollback(
      MaterializedDepotSpawnContext context, String reason) {
    rollbackOrRetainMaterializedSpawn(
        context.ticket(),
        context.service(),
        context.now(),
        context.trainName(),
        context.spawnLease(),
        context.materializedSpawn().train(),
        context.recoveryEpoch(),
        context.fallback(),
        reason);
  }

  /**
   * 在已实体化 Depot 编组的首次信号刷新前提交其物理身份登记。
   *
   * <p>调用方必须已经成功 acquire {@code acquiredAuthority}。登记失败时不执行刷新，由调用方按已实体化列车的 fail-closed
   * 顺序回滚；成功时刷新必然发生在登记之后，避免同步信号 tick 把本次新车误判为未知迟加载实体。
   */
  static boolean registerExpectedMaterializedSpawnBeforeFirstRefresh(
      RuntimeDispatchService runtimeDispatchService,
      RuntimeTrainHandle train,
      OccupancyRequest acquiredAuthority,
      long startupRecoveryEpoch,
      Runnable firstSignalRefresh) {
    Objects.requireNonNull(runtimeDispatchService, "runtimeDispatchService");
    Objects.requireNonNull(train, "train");
    Objects.requireNonNull(acquiredAuthority, "acquiredAuthority");
    Objects.requireNonNull(firstSignalRefresh, "firstSignalRefresh");
    if (!runtimeDispatchService.registerExpectedMaterializedSpawn(
        train, acquiredAuthority, startupRecoveryEpoch)) {
      return false;
    }
    firstSignalRefresh.run();
    return true;
  }

  /**
   * 启动占用恢复抢占发车事务时执行统一回滚。
   *
   * <p>未创建实体时直接撤销临时状态；已经实体化时先完成硬停车与延迟销毁安排，并把 occupancy 保留到 GroupRemove 精确释放。票据只能在新的 READY epoch
   * 中重试。物理收容或账务回滚异常时返回失败，调用方必须保留待处理记录，不得把票据视为已回队或继续执行任何放行动作。
   *
   * @return 实体已安全收容且账务动作全部完成，或尚未创建实体且普通回滚完成时为 {@code true}
   */
  private boolean abortDepotSpawnForRecovery(
      SpawnTicket ticket,
      Instant now,
      String trainName,
      SpawnControl.Lease spawnLease,
      Optional<RuntimeTrainHandle> spawnedTrain,
      String reason) {
    if (spawnedTrain.isPresent()) {
      return rollbackMaterializedDepotSpawn(
          ticket, now, trainName, spawnLease, spawnedTrain.orElseThrow(), reason);
    }
    runtimeDispatchService.cancelPreparedDepotSpawnDynamicAuthority(trainName);
    releaseSpawnLease(spawnLease);
    occupancyManager.releaseByTrain(trainName);
    requeue(ticket, now, reason);
    return true;
  }

  /**
   * 回滚已经实体化的 Depot 编组。
   *
   * <p>必须先硬停车并成功安排销毁，随后才能释放发车租约并回队。占用 claim 刻意保留到 GroupRemove 精确释放，避免延迟销毁的一 tick
   * 内出现“实体仍在、保护已撤销”的窗口。任一收容或账务动作异常都会停止后续放行动作并返回失败，使上层保留恢复记录供下一 tick 重试隔离。
   *
   * @return 物理收容和全部账务回滚均完成时为 {@code true}
   */
  private boolean rollbackMaterializedDepotSpawn(
      SpawnTicket ticket,
      Instant now,
      String trainName,
      SpawnControl.Lease spawnLease,
      RuntimeTrainHandle train,
      String reason) {
    boolean contained =
        containMaterializedSpawnBeforeRelease(
            train,
            () -> runtimeDispatchService.cancelPreparedDepotSpawnDynamicAuthority(trainName),
            () -> releaseSpawnLease(spawnLease),
            () -> requeueMaterializedSpawn(ticket, now, reason),
            debugLogger);
    if (!contained) {
      debugLogger.accept("已实体化发车回滚未完整完成，不得视为发车成功: train=" + trainName + " reason=" + reason);
    }
    return contained;
  }

  /**
   * 把已安全销毁的实体化失败票据重新入队。
   *
   * <p>普通失败达到尝试上限后可以结束票据；实体化失败则不能静默消费班次，否则一次 TrainCarts 水合竞态会永久丢失该发车。达到上限后保持原 attempts
   * 并按正常重试间隔退避，等待现场或版本问题修复。
   */
  private void requeueMaterializedSpawn(SpawnTicket ticket, Instant now, String error) {
    if (ticket == null) {
      return;
    }
    spawnRetries.increment();
    String reason = error == null ? "unknown" : error;
    String key = "materialized-spawn-rollback:" + reason;
    requeueByError
        .computeIfAbsent(key, ignored -> new java.util.concurrent.atomic.LongAdder())
        .increment();
    Instant retryAt = (now == null ? Instant.now() : now).plus(retryDelay);
    SpawnTicket retry =
        ticket.attempts() + 1 >= maxRetryAttempts
            ? ticket.delayedUntil(retryAt, key)
            : ticket.withRetry(retryAt, key);
    spawnManager.requeue(retry);
    try {
      debugLogger.accept(
          "已实体化发车回滚后保留票据: ticket="
              + ticket.id()
              + " route="
              + ticket.service().routeCode()
              + " attempts="
              + retry.attempts()
              + " retryAt="
              + retry.notBefore()
              + " reason="
              + key);
    } catch (RuntimeException | LinkageError logFailure) {
      HEALTH_LOGGER.warning("实体化发车回滚日志写入失败: " + logFailure.getClass().getSimpleName());
    }
  }

  /**
   * 以 fail-closed 顺序回滚已实体化编组。
   *
   * <p>该边界刻意不释放 occupancy；实体真正移除后由 GroupRemove 事件释放。物理停车或销毁安排失败时，不执行任何会允许重发的后续动作。
   *
   * @return 已硬停、安排销毁并完成后续账务动作时为 {@code true}
   */
  static boolean containMaterializedSpawnBeforeRelease(
      RuntimeTrainHandle handle,
      Runnable cancelPreparedAuthority,
      Runnable releaseLease,
      Runnable requeueTicket,
      Consumer<String> logger) {
    Objects.requireNonNull(handle, "handle");
    Objects.requireNonNull(cancelPreparedAuthority, "cancelPreparedAuthority");
    Objects.requireNonNull(releaseLease, "releaseLease");
    Objects.requireNonNull(requeueTicket, "requeueTicket");
    Consumer<String> safeLogger = logger != null ? logger : ignored -> {};
    try {
      handle.stopHard();
      handle.destroy();
    } catch (RuntimeException | LinkageError ex) {
      safeLogger.accept(
          "已实体化发车物理收容失败: error="
              + ex.getClass().getSimpleName()
              + ":"
              + String.valueOf(ex.getMessage()));
      return false;
    }
    try {
      cancelPreparedAuthority.run();
      releaseLease.run();
      requeueTicket.run();
      return true;
    } catch (RuntimeException | LinkageError ex) {
      safeLogger.accept(
          "已实体化发车账务回滚失败，编组已硬停并安排销毁: error="
              + ex.getClass().getSimpleName()
              + ":"
              + String.valueOf(ex.getMessage()));
      return false;
    }
  }

  /** 返回出车诊断快照（成功/重试/错误分布）。 */
  public SpawnDiagnostics snapshotDiagnostics() {
    java.util.Map<String, Long> byError = new java.util.HashMap<>();
    for (var entry : requeueByError.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        continue;
      }
      byError.put(entry.getKey(), entry.getValue().sum());
    }
    return new SpawnDiagnostics(
        spawnSuccess.sum(), spawnRetries.sum(), java.util.Map.copyOf(byError));
  }

  private void requeue(SpawnTicket ticket, Instant now, String error) {
    if (ticket == null) {
      return;
    }
    if (isDepotGateFailure(error)) {
      depotDispatchCoordinator.recordOccupancyFailure(ticket, now);
    }
    int nextAttempts = ticket.attempts() + 1;
    if (maxRetryAttempts > 0 && nextAttempts >= maxRetryAttempts) {
      if (preservePendingDispatchAttempt(ticket, "max-retry:" + error)) {
        putPendingLayoverTicket(ticket, now);
        return;
      }
      pendingLayoverTickets.remove(ticket.id());
      spawnManager.complete(ticket);
      try {
        debugLogger.accept(
            "自动发车放弃: ticket="
                + ticket.id()
                + " route="
                + ticket.service().routeCode()
                + " attempts="
                + nextAttempts
                + " max="
                + maxRetryAttempts
                + " error="
                + error);
      } catch (RuntimeException | LinkageError logFailure) {
        HEALTH_LOGGER.warning("自动发车放弃日志写入失败: " + logFailure.getClass().getSimpleName());
      }
      return;
    }
    spawnRetries.increment();
    String key = error == null ? "unknown" : error;
    requeueByError
        .computeIfAbsent(key, k -> new java.util.concurrent.atomic.LongAdder())
        .increment();

    Instant next = now.plus(retryDelay);
    SpawnTicket retry = ticket.withRetry(next, error);
    spawnManager.requeue(retry);
    String routeCode = ticket.service().routeCode();
    try {
      debugLogger.accept(
          "自动发车重试入队: ticket="
              + ticket.id()
              + " route="
              + routeCode
              + " attempts="
              + retry.attempts()
              + " notBefore="
              + retry.notBefore()
              + " error="
              + error);

      if (key.startsWith("spawn-failed")
          || key.startsWith("graph-missing")
          || key.startsWith("depot-world-missing")) {
        warnThrottled(
            "spawn:" + key + ":" + routeCode,
            "自动发车异常: route=" + routeCode + " error=" + key + " attempts=" + retry.attempts());
      }
    } catch (RuntimeException | LinkageError logFailure) {
      HEALTH_LOGGER.warning("自动发车重试日志写入失败: " + logFailure.getClass().getSimpleName());
    }
  }

  /**
   * 因线网状态（拥堵 / 准入上限）而延后发车。
   *
   * <p>与 {@link #requeue} 的区别是**不消耗票据的重试预算**：线网堵不是这张票的过错，而 requeue 累到 max-attempts 会直接 {@code
   * spawnManager.complete(ticket)} 把它丢掉——那是 取消发车而不是推迟发车。但仍按原因计数，否则"闸门拦了多少次"就没有了，而这一整轮改动
   * 的目的恰恰是让闸门可观测。
   */
  private void deferByGate(SpawnTicket ticket, Instant now, String reason) {
    String key = reason == null ? "unknown" : reason;
    requeueByError
        .computeIfAbsent(key, ignored -> new java.util.concurrent.atomic.LongAdder())
        .increment();
    deferWithoutAttempt(ticket, now, key);
  }

  /**
   * 出库被闭塞挡住（车库咽喉、长单线、预检 blocker）：记 depot backoff，延后重试，不消耗重试预算。
   *
   * <p>与 {@link #deferByGate} 同一个道理：挡住它的是别的车，累到 max-attempts 把票丢掉就是取消发车。 表定票被丢掉的代价尤其大——首班出库票没了，
   * 整个交路都不会有车，而重试预算可能远早于票据自身的容差耗尽。 兜底仍在：表定票有自己的到期时刻，按间隔发车的票有 {@code queued-ticket-max-age-seconds}。
   */
  private void deferBlockedAtDepot(SpawnTicket ticket, Instant now, String reason) {
    if (ticket == null) {
      return;
    }
    if (isDepotGateFailure(reason)) {
      depotDispatchCoordinator.recordOccupancyFailure(ticket, now);
    }
    spawnRetries.increment();
    requeueByError
        .computeIfAbsent(reason, ignored -> new java.util.concurrent.atomic.LongAdder())
        .increment();
    SpawnTicket retry = ticket.blockedUntil(now.plus(retryDelay), reason);
    spawnManager.requeue(retry);
    debugLogger.accept(
        "自动发车重试入队: ticket="
            + ticket.id()
            + " route="
            + ticket.service().routeCode()
            + " attempts="
            + retry.attempts()
            + " notBefore="
            + retry.notBefore()
            + " error="
            + reason);
  }

  private void deferWithoutAttempt(SpawnTicket ticket, Instant now, String reason) {
    if (ticket == null) {
      return;
    }
    Instant base = now == null ? Instant.now() : now;
    Instant next = base.plus(retryDelay);
    SpawnTicket deferred = ticket.delayedUntil(next, reason);
    spawnManager.requeue(deferred);
    debugLogger.accept(
        "自动发车延后: ticket="
            + ticket.id()
            + " route="
            + ticket.service().routeCode()
            + " notBefore="
            + deferred.notBefore()
            + " reason="
            + reason);
  }

  private static boolean isDepotGateFailure(String error) {
    if (error == null) {
      return false;
    }
    return error.contains("gate-blocked") || error.contains("occupancy");
  }

  private void warnThrottled(String key, String message) {
    long now = System.currentTimeMillis();
    long intervalMs = 60_000L;
    lastWarnAtMs.compute(
        key,
        (k, prev) -> {
          if (prev == null || now - prev > intervalMs) {
            HEALTH_LOGGER.warning(message);
            return now;
          }
          return prev;
        });
  }

  /** 出车诊断快照。 */
  public record SpawnDiagnostics(
      long success, long retries, java.util.Map<String, Long> requeueByError) {
    public SpawnDiagnostics {
      requeueByError =
          requeueByError == null ? java.util.Map.of() : java.util.Map.copyOf(requeueByError);
    }
  }

  private record DepotGateRequest(
      OccupancyRequest request,
      OccupancyRequestContext context,
      List<NodeId> effectiveWaypoints,
      List<NodeId> expandedPathNodes,
      Optional<NodeId> selectedDepotNode,
      NodeId originalFirstWaypoint,
      NodeId effectiveFirstWaypoint) {
    private DepotGateRequest {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(context, "context");
      effectiveWaypoints = effectiveWaypoints == null ? List.of() : List.copyOf(effectiveWaypoints);
      expandedPathNodes = expandedPathNodes == null ? List.of() : List.copyOf(expandedPathNodes);
      selectedDepotNode = selectedDepotNode == null ? Optional.empty() : selectedDepotNode;
    }
  }

  private Optional<DepotGateRequest> buildDepotSpawnGateRequest(
      OccupancyRequestBuilder builder,
      String trainName,
      RouteDefinition route,
      List<NodeId> spawnWaypoints,
      SpawnService service,
      SpawnTicket ticket,
      RouteOperationType operationType,
      Instant now) {
    int priority =
        DispatchPriorityPolicy.depotSpawnPriority(
            operationType, ticket == null ? 0 : ticket.priority());
    Optional<OccupancyRequestContext> ctxOpt =
        builder.buildContextFromNodes(
            trainName,
            Optional.ofNullable(route.id()),
            spawnWaypoints,
            0,
            now,
            priority,
            AuthorizationPurpose.DEPOT_SPAWN);
    if (ctxOpt.isEmpty()) {
      return Optional.empty();
    }
    Optional<NodeId> depotNode =
        resolveDepotNode(service, ticket == null ? Optional.empty() : ticket.selectedDepotNodeId());
    if (depotNode.isEmpty()) {
      debugLogger.accept("Depot authority 回退: 未解析到显式 depot 节点 train=" + trainName);
    }
    OccupancyRequestContext requestContext = ctxOpt.get();
    OccupancyRequest request = requestContext.request();
    NodeId originalFirst = route.waypoints().isEmpty() ? null : route.waypoints().get(0);
    NodeId effectiveFirst = spawnWaypoints.isEmpty() ? null : spawnWaypoints.get(0);
    debugLogger.accept(
        "SMART_DEPOT_SPAWN_AUTHORITY_WINDOW train="
            + trainName
            + " route="
            + formatRouteForTrace(service, route)
            + " operation="
            + (operationType == null ? "-" : operationType)
            + " priority="
            + priority
            + " firstNode="
            + formatNode(effectiveFirst)
            + " authorityEnd="
            + (ctxOpt.get().pathNodes().isEmpty()
                ? "-"
                : formatNode(ctxOpt.get().pathNodes().get(ctxOpt.get().pathNodes().size() - 1)))
            + " resourceCount="
            + request.resourceList().size()
            + " authorityEdgeCount="
            + requestContext.edges().size());
    return Optional.of(
        new DepotGateRequest(
            request,
            requestContext,
            spawnWaypoints,
            ctxOpt.get().pathNodes(),
            depotNode,
            originalFirst,
            effectiveFirst));
  }

  private static int depotSpawnLookaheadEdges(ConfigManager.RuntimeSettings runtime) {
    if (runtime == null) {
      return 1;
    }
    int localExitWindow = Math.max(1, runtime.switcherZoneEdges() + 1);
    return Math.max(1, Math.min(runtime.lookaheadEdges(), localExitWindow));
  }

  /**
   * 将已通过 Depot gate 的安全节点序列写入 TrainCarts。
   *
   * <p>第二个节点必须来自 DYNAMIC materialization 后的 {@link DepotGateRequest#effectiveWaypoints()}，不能回读
   * route 原始占位节点，否则列车生成成功后会把 destination 写回不可寻路的 DYNAMIC 声明。
   *
   * @param properties 已生成列车的属性
   * @param effectiveWaypoints 本次 gate 实际使用的安全节点序列
   * @return 已写入首个 destination 时返回 {@code true}
   */
  static boolean applyPreparedSpawnDestination(
      TrainProperties properties, List<NodeId> effectiveWaypoints) {
    if (properties == null || effectiveWaypoints == null || effectiveWaypoints.size() < 2) {
      return false;
    }
    properties.clearDestinationRoute();
    properties.clearDestination();
    properties.setDestination(effectiveWaypoints.get(1).value());
    return true;
  }

  /**
   * 解析本次发车实际使用的节点序列。
   *
   * <p>SpawnPlan 的 RouteDefinition 首节点来自 route 原始 CRET 或 DYNAMIC 占位；当 line depot pool 或动态 depot
   * 选择了其他实际股道时，若仍用原始首节点构建占用请求，默认股道被占用就会提前 gate-block，本次票据根本到不了真正的 spawner。这里把第 0 个节点替换为本次实际
   * depot，使闭塞检查、路径可达性与最终出车位置一致。
   */
  private List<NodeId> resolveDepotSpawnWaypoints(
      RouteDefinition route, SpawnService service, SpawnTicket ticket) {
    if (route == null || route.waypoints().isEmpty()) {
      return List.of();
    }
    Optional<NodeId> depotNode =
        resolveDepotNode(service, ticket == null ? Optional.empty() : ticket.selectedDepotNodeId());
    if (depotNode.isEmpty()) {
      return route.waypoints();
    }
    List<NodeId> nodes = new ArrayList<>(route.waypoints());
    nodes.set(0, depotNode.get());
    return List.copyOf(nodes);
  }

  /**
   * 将 DYNAMIC depot 选择固化为实际节点。
   *
   * <p>TrainCartsDepotSpawner 也能解析 DYNAMIC，但闭塞 gate 发生在 spawner 之前。提前固化可以让 gate 使用同一条实际股道，避免“检查的是 1
   * 号股道、生成想去 2 号股道”的状态分裂。
   */
  private SpawnTicket materializeDynamicDepotSelection(
      StorageProvider provider,
      SpawnService service,
      SpawnTicket ticket,
      LineRuntimeSnapshot runtimeSnapshot,
      Map<String, Integer> selectedThisTick,
      Instant now) {
    if (provider == null || ticket == null || service == null) {
      return ticket;
    }
    Optional<String> depotSpecOpt = resolveDepotSpec(service, ticket.selectedDepotNodeId());
    if (depotSpecOpt.isEmpty()) {
      return ticket;
    }
    String depotSpec = depotSpecOpt.get();
    if (!SpawnDirectiveParser.isDynamicTarget(depotSpec) || !isDynamicDepotSpec(depotSpec)) {
      return ticket;
    }
    return resolveDynamicDepotNodeInfo(
            depotSpec, true, provider, service.lineId(), runtimeSnapshot, selectedThisTick, now)
        .map(info -> ticket.withSelectedDepot(info.definition().nodeId().value()))
        .orElse(ticket);
  }

  private Optional<java.util.UUID> resolveDepotWorldId(
      SpawnService service, Optional<String> depotOverride) {
    Optional<String> depotSpecOpt = resolveDepotSpec(service, depotOverride);
    if (depotSpecOpt.isEmpty()) {
      return Optional.empty();
    }
    String depotSpec = depotSpecOpt.get();

    // 检查是否是 DYNAMIC depot
    if (SpawnDirectiveParser.isDynamicTarget(depotSpec)) {
      if (!isDynamicDepotSpec(depotSpec)) {
        return Optional.empty();
      }
      // 解析 DYNAMIC spec，查找匹配轨道的世界
      return resolveDynamicDepotWorldId(depotSpec);
    }

    // 普通 depot：精确匹配 nodeId。测试或重载早期 registry 可能尚未返回快照，按缺失处理。
    Map<String, SignNodeRegistry.SignNodeInfo> infos = signNodeRegistry.snapshotInfos();
    if (infos == null || infos.isEmpty()) {
      return Optional.empty();
    }
    return infos.values().stream()
        .filter(info -> info != null && info.definition() != null)
        .filter(info -> info.definition().nodeType() == NodeType.DEPOT)
        .filter(info -> depotSpec.equalsIgnoreCase(info.definition().nodeId().value()))
        .sorted(
            Comparator.comparing(
                info -> info.definition().nodeId().value(), String.CASE_INSENSITIVE_ORDER))
        .map(SignNodeRegistry.SignNodeInfo::worldId)
        .findFirst();
  }

  /**
   * 判断该 RETURN 服务是否允许走“depot 降级补发”。
   *
   * <p>只允许真实 depot 起点（固定 D 节点或 DYNAMIC:*:D:*），避免把站点折返误当成 depot 出车。
   */
  private static boolean canFallbackSpawnFromDepot(SpawnService service) {
    if (service == null) {
      return false;
    }
    String depotSpec = service.depotNodeId();
    if (depotSpec == null || depotSpec.isBlank()) {
      return false;
    }
    if (SpawnDirectiveParser.isDynamicTarget(depotSpec)) {
      return isDynamicDepotSpec(depotSpec);
    }
    String[] parts = depotSpec.split(":", 4);
    return parts.length >= 2 && "D".equalsIgnoreCase(parts[1]);
  }

  private static boolean isDynamicDepotSpec(String dynamicSpec) {
    if (dynamicSpec == null
        || !dynamicSpec.toUpperCase(java.util.Locale.ROOT).startsWith("DYNAMIC:")) {
      return false;
    }
    String rest = dynamicSpec.substring("DYNAMIC:".length());
    String[] parts = rest.split(":", 4);
    return parts.length >= 2 && "D".equalsIgnoreCase(parts[1]);
  }

  /**
   * 为实体发车票据申请 SpawnControl 租约。
   *
   * <p>该方法统一处理普通 spawn 与 fallback spawn 的容量判断；若拒绝，会输出包含 running/pending/lease 细分的诊断，调用方负责 requeue。
   */
  private Optional<SpawnControl.Lease> tryAcquireSpawnControlForTicket(
      StorageProvider provider,
      Line line,
      SpawnTicket ticket,
      SpawnService service,
      Route routeEntity,
      SpawnControl.LeaseKind kind,
      Optional<String> excludedTrain,
      Instant now) {
    if (provider == null || line == null || ticket == null || service == null) {
      return Optional.empty();
    }
    OptionalInt maxTrains = resolveLineMaxTrains(provider, line);
    SpawnControl.BaseCounters counters =
        buildSpawnControlCounters(provider, line.id(), ticket.id(), excludedTrain);
    SpawnControl.Decision decision =
        spawnControl.tryAcquire(
            new SpawnControl.Request(
                ticket.id().toString(),
                line.id(),
                service.routeId(),
                ticket.id(),
                excludedTrain,
                kind,
                maxTrains,
                counters,
                now));
    if (decision.allowed()) {
      return decision.lease();
    }
    logSpawnControlBlocked(line, routeEntity, kind, decision);
    return Optional.empty();
  }

  /** 为 Layover/RETURN 复用申请 SpawnControl 租约。 */
  private Optional<SpawnControl.Lease> tryAcquireSpawnControlForLayover(
      Optional<StorageProvider> providerOpt,
      SpawnTicket ticket,
      SpawnControl.LeaseKind kind,
      Optional<LayoverRegistry.LayoverCandidate> candidateOpt,
      Instant now) {
    if (providerOpt.isEmpty() || ticket == null || ticket.service() == null) {
      return Optional.empty();
    }
    StorageProvider provider = providerOpt.get();
    SpawnService service = ticket.service();
    Optional<Route> routeEntityOpt = provider.routes().findById(service.routeId());
    if (routeEntityOpt.isEmpty()) {
      return Optional.empty();
    }
    Optional<Line> lineOpt = provider.lines().findById(routeEntityOpt.get().lineId());
    if (lineOpt.isEmpty()) {
      return Optional.empty();
    }
    Optional<String> trainName = candidateOpt.map(LayoverRegistry.LayoverCandidate::trainName);
    return tryAcquireSpawnControlForTicket(
        provider, lineOpt.get(), ticket, service, routeEntityOpt.get(), kind, trainName, now);
  }

  /** 为 ReclaimManager 的 RETURN ServiceTicket 申请 SpawnControl 租约。 */
  private Optional<SpawnControl.Lease> tryAcquireSpawnControlForLayover(
      Optional<StorageProvider> providerOpt,
      ServiceTicket ticket,
      SpawnControl.LeaseKind kind,
      Optional<LayoverRegistry.LayoverCandidate> candidateOpt,
      Instant now) {
    if (providerOpt.isEmpty() || ticket == null || ticket.routeId() == null) {
      return Optional.empty();
    }
    StorageProvider provider = providerOpt.get();
    Optional<Route> routeEntityOpt = provider.routes().findById(ticket.routeId());
    if (routeEntityOpt.isEmpty()) {
      return Optional.empty();
    }
    Route routeEntity = routeEntityOpt.get();
    Optional<Line> lineOpt = provider.lines().findById(routeEntity.lineId());
    if (lineOpt.isEmpty()) {
      return Optional.empty();
    }
    Line line = lineOpt.get();
    Optional<String> trainName = candidateOpt.map(LayoverRegistry.LayoverCandidate::trainName);
    OptionalInt maxTrains = resolveLineMaxTrains(provider, line);
    SpawnControl.BaseCounters counters =
        buildSpawnControlCounters(provider, line.id(), null, trainName);
    UUID ownerTicketId = parseUuid(ticket.ticketId()).orElseGet(UUID::randomUUID);
    SpawnControl.Decision decision =
        spawnControl.tryAcquire(
            new SpawnControl.Request(
                ticket.ticketId(),
                line.id(),
                routeEntity.id(),
                ownerTicketId,
                trainName,
                kind,
                maxTrains,
                counters,
                now));
    if (decision.allowed()) {
      return decision.lease();
    }
    logSpawnControlBlocked(line, routeEntity, kind, decision);
    return Optional.empty();
  }

  private SpawnControl.BaseCounters buildSpawnControlCounters(
      StorageProvider provider,
      UUID lineId,
      UUID excludedTicketId,
      Optional<String> excludedTrain) {
    if (provider == null || lineId == null) {
      return SpawnControl.BaseCounters.empty();
    }
    LineRuntimeSnapshot runtimeSnapshot = LineRuntimeSnapshot.capture(runtimeDispatchService);
    int running = runtimeSnapshot.countActiveTrains(provider, lineId, excludedTrain);
    int pending = countPendingTicketsForLine(lineId, excludedTicketId);
    return new SpawnControl.BaseCounters(running, pending);
  }

  private int countPendingTicketsForLine(UUID lineId, UUID excludedTicketId) {
    if (lineId == null) {
      return 0;
    }
    int count = 0;
    List<SpawnTicket> queuedTickets = spawnManager.snapshotQueue();
    if (queuedTickets != null) {
      for (SpawnTicket queued : queuedTickets) {
        if (isTicketForLine(queued, lineId, excludedTicketId)) {
          count++;
        }
      }
    }
    for (PendingLayoverEntry pending : pendingLayoverTickets.values()) {
      SpawnTicket ticket = pending == null ? null : pending.ticket();
      if (isTicketForLine(ticket, lineId, excludedTicketId)) {
        count++;
      }
    }
    return count;
  }

  private static boolean isTicketForLine(SpawnTicket ticket, UUID lineId, UUID excludedTicketId) {
    if (ticket == null || ticket.service() == null || lineId == null) {
      return false;
    }
    if (excludedTicketId != null && excludedTicketId.equals(ticket.id())) {
      return false;
    }
    return lineId.equals(ticket.service().lineId());
  }

  private static Optional<UUID> parseUuid(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(raw.trim()));
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
  }

  private void logSpawnControlBlocked(
      Line line, Route routeEntity, SpawnControl.LeaseKind kind, SpawnControl.Decision decision) {
    SpawnControl.Snapshot snapshot =
        decision == null
            ? SpawnControl.Snapshot.empty(line == null ? null : line.id())
            : decision.snapshot();
    debugLogger.accept(
        "SpawnControl 阻塞: line="
            + (line == null ? "?" : line.code())
            + " route="
            + (routeEntity == null ? "?" : routeEntity.code())
            + " kind="
            + kind
            + " reason="
            + (decision == null ? "unknown" : decision.reason())
            + " running="
            + snapshot.running()
            + " pending="
            + snapshot.pending()
            + " spawnReserved="
            + snapshot.spawnReserved()
            + " layoverReserved="
            + snapshot.layoverReserved()
            + " reclaimReturn="
            + snapshot.reclaimReturn()
            + " total="
            + snapshot.total());
  }

  private static String spawnGateSignalText(
      LaunchAuthorizationService.AuthorizationResult authorization) {
    if (authorization == null) {
      return "unknown";
    }
    String signal = authorization.signal() == null ? "unknown" : authorization.signal().name();
    if (authorization.blockers().isEmpty()) {
      return signal;
    }
    String blockers =
        authorization.blockers().stream()
            .filter(Objects::nonNull)
            .limit(3)
            .map(SimpleTicketAssigner::formatGateBlocker)
            .collect(Collectors.joining(","));
    return blockers.isBlank() ? signal : signal + " blockers=" + blockers;
  }

  private static String formatGateBlocker(OccupancyClaim claim) {
    if (claim == null || claim.resource() == null) {
      return "unknown";
    }
    String owner =
        claim.trainName() == null || claim.trainName().isBlank() ? "-" : claim.trainName();
    return claim.resource().kind().name() + ":" + claim.resource().key() + "@" + owner;
  }

  private void logDepotGateBlockedTrace(
      SpawnTicket ticket,
      SpawnService service,
      RouteDefinition route,
      String trainName,
      List<SpawnDepot> candidateDepots,
      DepotGateRequest gateRequest,
      LaunchAuthorizationService.AuthorizationResult authorization,
      SpawnControl.Lease lease,
      String phase) {
    if (gateRequest == null || authorization == null) {
      return;
    }
    OccupancyRequest request = gateRequest.request();
    debugLogger.accept(
        "Depot gate blocked trace: phase="
            + phase
            + " ticket="
            + (ticket == null ? "-" : ticket.id())
            + " route="
            + formatRouteForTrace(service, route)
            + " train="
            + (trainName == null || trainName.isBlank() ? "-" : trainName)
            + " selectedDepotNodeId="
            + selectedDepotForTrace(ticket, gateRequest)
            + " candidateDepots="
            + formatCandidateDepots(candidateDepots)
            + " originalFirstWaypoint="
            + formatNode(gateRequest.originalFirstWaypoint())
            + " effectiveFirstWaypoint="
            + formatNode(gateRequest.effectiveFirstWaypoint())
            + " expandedPath="
            + formatNodes(gateRequest.expandedPathNodes(), 24)
            + " authorityEdgeCount="
            + gateRequest.context().edges().size()
            + " resources="
            + formatGateResources(request.resourceList(), 48)
            + " blockers="
            + formatGateBlockers(authorization.blockers(), 32)
            + " spawnLease="
            + (lease == null ? "none" : "held")
            + " notBefore="
            + (ticket == null ? "-" : ticket.notBefore())
            + " lastError="
            + (ticket == null ? "-" : ticket.lastError())
            + " occupancyVersion="
            + occupancyVersionForTrace());
    for (OccupancyClaim blocker : authorization.blockers()) {
      if (blocker == null || blocker.resource() == null) {
        continue;
      }
      debugLogger.accept(
          "SMART_DEPOT_SPAWN_LOCAL_BLOCKER train="
              + trainName
              + " blocker="
              + formatGateBlocker(blocker)
              + " authorityEnd="
              + (gateRequest.expandedPathNodes().isEmpty()
                  ? "-"
                  : formatNode(
                      gateRequest
                          .expandedPathNodes()
                          .get(gateRequest.expandedPathNodes().size() - 1)))
              + " action=blocked");
    }
  }

  private static String formatRouteForTrace(SpawnService service, RouteDefinition route) {
    if (service != null) {
      return service.operatorCode() + "/" + service.lineCode() + "/" + service.routeCode();
    }
    return route == null || route.id() == null ? "-" : route.id().value();
  }

  private static String selectedDepotForTrace(SpawnTicket ticket, DepotGateRequest gateRequest) {
    if (ticket != null && ticket.selectedDepotNodeId().isPresent()) {
      return ticket.selectedDepotNodeId().get();
    }
    return gateRequest.selectedDepotNode().map(NodeId::value).orElse("-");
  }

  private static String formatCandidateDepots(List<SpawnDepot> candidateDepots) {
    if (candidateDepots == null || candidateDepots.isEmpty()) {
      return "[]";
    }
    return candidateDepots.stream()
        .filter(Objects::nonNull)
        .map(depot -> depot.nodeId() + "(w=" + depot.weight() + ")")
        .collect(Collectors.joining(",", "[", "]"));
  }

  private static String formatNodes(List<NodeId> nodes, int limit) {
    if (nodes == null || nodes.isEmpty()) {
      return "[]";
    }
    int max = Math.max(1, limit);
    List<String> values =
        nodes.stream()
            .filter(Objects::nonNull)
            .limit(max)
            .map(NodeId::value)
            .collect(Collectors.toCollection(ArrayList::new));
    if (nodes.size() > max) {
      values.add("+" + (nodes.size() - max));
    }
    return values.toString();
  }

  private static String formatNode(NodeId node) {
    return node == null ? "-" : node.value();
  }

  private static String formatGateResources(List<OccupancyResource> resources, int limit) {
    if (resources == null || resources.isEmpty()) {
      return "[]";
    }
    int max = Math.max(1, limit);
    List<String> values = new ArrayList<>();
    int nodeCount = 0;
    int edgeCount = 0;
    int switcherCount = 0;
    int singleCount = 0;
    for (OccupancyResource resource : resources) {
      if (resource == null) {
        continue;
      }
      if (resource.kind() == ResourceKind.NODE) {
        nodeCount++;
      } else if (resource.kind() == ResourceKind.EDGE) {
        edgeCount++;
      } else if (resource.kind() == ResourceKind.CONFLICT
          && resource.key().startsWith("switcher:")) {
        switcherCount++;
      } else if (resource.kind() == ResourceKind.CONFLICT && resource.key().startsWith("single:")) {
        singleCount++;
      }
      if (values.size() < max) {
        values.add(resource.kind() + ":" + resource.key());
      }
    }
    if (resources.size() > max) {
      values.add("+" + (resources.size() - max));
    }
    return "counts[node="
        + nodeCount
        + ",edge="
        + edgeCount
        + ",switcher="
        + switcherCount
        + ",single="
        + singleCount
        + "]"
        + values;
  }

  private static String formatGateBlockers(List<OccupancyClaim> blockers, int limit) {
    if (blockers == null || blockers.isEmpty()) {
      return "[]";
    }
    int max = Math.max(1, limit);
    List<String> values =
        blockers.stream()
            .filter(Objects::nonNull)
            .limit(max)
            .map(SimpleTicketAssigner::formatGateBlocker)
            .collect(Collectors.toCollection(ArrayList::new));
    if (blockers.size() > max) {
      values.add("+" + (blockers.size() - max));
    }
    return values.toString();
  }

  private long occupancyVersionForTrace() {
    return occupancyManager instanceof SimpleOccupancyManager manager ? manager.version() : -1L;
  }

  /**
   * Depot 实体化前只做只读 gate 预判。
   *
   * <p>预判阶段还没有 TrainCarts group，不能写入 occupancy claim，也不能把待生成列车加入冲突队列。真正的资源 acquire 必须在 spawn 成功后执行；
   * 若此时 acquire 失败，会销毁刚创建的 group 并重排票据。
   */
  private LaunchAuthorizationService.AuthorizationResult previewSpawnGate(
      OccupancyRequest request) {
    return launchAuthorizationService.authorize(
        new LaunchAuthorizationService.AuthorizationPlan(
            request,
            "depot-spawn-preview",
            true,
            false,
            false,
            true,
            LaunchAuthorizationService.LaunchActions.none()));
  }

  /**
   * Depot 实体化后写入真实占用。
   *
   * <p>spawn 和 acquire 之间可能有同 tick 竞争；因此 acquire 失败时必须销毁刚生成的列车并释放 SpawnControl lease。该流程仍使用统一授权服务，
   * 保持 hard blocker 与 clean blocker 语义和站台/折返出发一致。
   */
  private LaunchAuthorizationService.AuthorizationResult acquireSpawnGate(
      OccupancyRequest request) {
    return launchAuthorizationService.authorize(
        new LaunchAuthorizationService.AuthorizationPlan(
            request,
            "depot-spawn",
            false,
            true,
            false,
            true,
            LaunchAuthorizationService.LaunchActions.none()));
  }

  private static void releaseSpawnLease(SpawnControl.Lease lease) {
    if (lease != null) {
      lease.release();
    }
  }

  private OptionalInt resolveLineMaxTrains(StorageProvider provider, Line line) {
    if (provider == null || line == null) {
      return OptionalInt.empty();
    }
    OptionalInt explicit = LineSpawnMetadata.parseMaxTrains(line.metadata());
    if (explicit.isPresent()) {
      return explicit;
    }
    Optional<Integer> baselineOpt = line.spawnFreqBaselineSec();
    if (baselineOpt.isEmpty()) {
      return OptionalInt.empty();
    }
    int baseline = baselineOpt.get() == null ? 0 : baselineOpt.get();
    if (baseline <= 0) {
      return OptionalInt.empty();
    }
    int runtimeSeconds = resolveLineRuntimeSeconds(provider, line.id());
    if (runtimeSeconds <= 0) {
      return OptionalInt.empty();
    }
    int max = (int) Math.ceil(runtimeSeconds / (double) baseline);
    return OptionalInt.of(Math.max(1, max));
  }

  private int resolveLineRuntimeSeconds(StorageProvider provider, UUID lineId) {
    if (provider == null || lineId == null) {
      return 0;
    }
    int max = 0;
    List<Route> routes = provider.routes().listByLine(lineId);
    for (Route route : routes) {
      if (route == null || route.operationType() != RouteOperationType.OPERATION) {
        continue;
      }
      Optional<Integer> runtimeOpt = route.runtimeSeconds();
      if (runtimeOpt.isEmpty() || runtimeOpt.get() == null) {
        continue;
      }
      max = Math.max(max, runtimeOpt.get());
    }
    return max;
  }

  /**
   * 按“本线路各 depot 在线负载”重排本轮 depot 发车票据。
   *
   * <p>{@code DepotDispatchCoordinator} 只负责同一 depot 的互斥与退避；本轮能出库的票多于 {@code maxSpawnPerTick}
   * 时，谁先拿到实体化名额由这里的顺序决定：低负载 depot 的票据排在前面，避免同线路另一个 depot 的交路组长期拿不到名额。 被闭塞挡住的票不占名额，不会再因为排在前面而饿死别人。
   */
  private List<SpawnTicket> orderDepotTicketsByLineDepotLoad(
      StorageProvider provider, List<SpawnTicket> dueTickets) {
    if (provider == null || dueTickets == null || dueTickets.size() <= 1) {
      return dueTickets == null ? List.of() : dueTickets;
    }
    LineRuntimeSnapshot runtimeSnapshot = LineRuntimeSnapshot.capture(runtimeDispatchService);
    Map<UUID, DepotLoadSnapshot> loadByLine = new HashMap<>();
    List<DepotExecutionCandidate> candidates = new ArrayList<>();
    for (int index = 0; index < dueTickets.size(); index++) {
      SpawnTicket ticket = dueTickets.get(index);
      if (!isDepotSpawnTicket(provider, ticket)) {
        continue;
      }
      Optional<DepotExecutionCandidate> candidate =
          buildDepotExecutionCandidate(provider, runtimeSnapshot, loadByLine, ticket, index);
      candidate.ifPresent(candidates::add);
    }
    if (candidates.size() <= 1) {
      return dueTickets;
    }
    candidates.sort(
        Comparator.comparingDouble(DepotExecutionCandidate::loadScore)
            .thenComparingInt(DepotExecutionCandidate::originalIndex));
    Set<UUID> reorderIds =
        candidates.stream().map(candidate -> candidate.ticket().id()).collect(Collectors.toSet());
    ArrayDeque<SpawnTicket> orderedDepotTickets = new ArrayDeque<>();
    for (DepotExecutionCandidate candidate : candidates) {
      orderedDepotTickets.add(candidate.ticket());
    }

    List<SpawnTicket> reordered = new ArrayList<>(dueTickets.size());
    for (SpawnTicket original : dueTickets) {
      if (original == null || !reorderIds.contains(original.id())) {
        reordered.add(original);
        continue;
      }
      SpawnTicket next = orderedDepotTickets.pollFirst();
      reordered.add(next == null ? original : next);
    }
    return List.copyOf(reordered);
  }

  private Optional<DepotExecutionCandidate> buildDepotExecutionCandidate(
      StorageProvider provider,
      LineRuntimeSnapshot runtimeSnapshot,
      Map<UUID, DepotLoadSnapshot> loadByLine,
      SpawnTicket ticket,
      int originalIndex) {
    if (provider == null
        || runtimeSnapshot == null
        || loadByLine == null
        || ticket == null
        || ticket.service() == null) {
      return Optional.empty();
    }
    UUID lineId = ticket.service().lineId();
    if (lineId == null) {
      return Optional.empty();
    }
    DepotLoadSnapshot loadSnapshot =
        loadByLine.computeIfAbsent(
            lineId, ignored -> buildDepotLoadSnapshot(provider, runtimeSnapshot, lineId));
    if (loadSnapshot == null || loadSnapshot.depots().isEmpty()) {
      return Optional.empty();
    }
    Optional<String> depotSpec = resolveDepotSpec(ticket.service(), ticket.selectedDepotNodeId());
    if (depotSpec.isEmpty()) {
      return Optional.empty();
    }
    Optional<String> configuredKey =
        resolveConfiguredDepotKey(
            depotSpec.get(), loadSnapshot.depots(), loadSnapshot.aliasIndex());
    if (configuredKey.isEmpty()) {
      return Optional.empty();
    }
    int active = loadSnapshot.activeByDepot().getOrDefault(configuredKey.get(), 0);
    int weight = Math.max(1, loadSnapshot.weightByDepot().getOrDefault(configuredKey.get(), 1));
    double score = active / (double) weight;
    return Optional.of(new DepotExecutionCandidate(ticket, originalIndex, score));
  }

  private DepotLoadSnapshot buildDepotLoadSnapshot(
      StorageProvider provider, LineRuntimeSnapshot runtimeSnapshot, UUID lineId) {
    if (provider == null || runtimeSnapshot == null || lineId == null) {
      return DepotLoadSnapshot.empty();
    }
    Optional<Line> lineOpt = provider.lines().findById(lineId);
    if (lineOpt.isEmpty()) {
      return DepotLoadSnapshot.empty();
    }
    List<SpawnDepot> depots = LineSpawnMetadata.parseDepots(lineOpt.get().metadata());
    if (depots.isEmpty()) {
      return DepotLoadSnapshot.empty();
    }
    Map<String, String> aliasIndex = buildDepotAliasIndex(depots);
    Map<String, Integer> activeByDepot =
        runtimeSnapshot.countActiveTrainsByDepot(provider, lineId, depots, aliasIndex);
    Map<String, Integer> weightByDepot = new HashMap<>();
    for (SpawnDepot depot : depots) {
      if (depot == null) {
        continue;
      }
      weightByDepot.merge(depot.normalizedKey(), Math.max(1, depot.weight()), Integer::sum);
    }
    return new DepotLoadSnapshot(depots, aliasIndex, activeByDepot, Map.copyOf(weightByDepot));
  }

  private Optional<SpawnDepot> selectBalancedDepot(
      StorageProvider provider,
      UUID lineId,
      List<SpawnDepot> depots,
      LineRuntimeSnapshot runtimeSnapshot,
      Map<String, Integer> selectedThisTick,
      Instant now) {
    if (provider == null || lineId == null || depots == null || depots.isEmpty()) {
      return Optional.empty();
    }
    Map<String, String> depotAliasIndex = buildDepotAliasIndex(depots);
    Map<String, Integer> activeByDepot =
        runtimeSnapshot.countActiveTrainsByDepot(provider, lineId, depots, depotAliasIndex);
    List<SpawnDepot> bestDepots = new ArrayList<>();
    double bestScore = Double.MAX_VALUE;
    for (SpawnDepot depot : depots) {
      if (depot == null) {
        continue;
      }
      int active = activeByDepot.getOrDefault(depot.normalizedKey(), 0);
      int selected =
          selectedThisTick == null ? 0 : selectedThisTick.getOrDefault(depot.normalizedKey(), 0);
      double backoffPenalty =
          depotDispatchCoordinator.backoffUntil(depot.nodeId(), now).isPresent() ? 1000.0D : 0.0D;
      double score = active / (double) depot.weight() + selected + backoffPenalty;
      if (score < bestScore - 0.000001D) {
        bestDepots.clear();
        bestDepots.add(depot);
        bestScore = score;
        continue;
      }
      if (Math.abs(score - bestScore) <= 0.000001D) {
        bestDepots.add(depot);
      }
    }
    return pickDepotByRoundRobin(lineId, bestDepots);
  }

  private void recordSelectedDepotForTick(
      SpawnTicket ticket,
      List<SpawnDepot> lineDepots,
      Optional<SpawnDepot> configuredSelection,
      Map<String, Integer> selectedThisTick) {
    if (ticket == null || selectedThisTick == null) {
      return;
    }
    Optional<String> configuredKey =
        configuredSelection
            .map(SpawnDepot::normalizedKey)
            .or(
                () ->
                    resolveDepotSpec(ticket.service(), ticket.selectedDepotNodeId())
                        .flatMap(
                            depotSpec ->
                                resolveConfiguredDepotKey(
                                    depotSpec, lineDepots, buildDepotAliasIndex(lineDepots))));
    String actualKey =
        ticket.selectedDepotNodeId().map(SimpleTicketAssigner::normalizeDepotKey).orElse("");
    configuredKey
        .filter(key -> !key.isBlank())
        .ifPresent(key -> selectedThisTick.merge(key, 1, Integer::sum));
    if (!actualKey.isBlank()) {
      selectedThisTick.merge(actualKey, 1, Integer::sum);
    }
  }

  private static String normalizeDepotKey(String depotNodeId) {
    if (depotNodeId == null || depotNodeId.isBlank()) {
      return "";
    }
    return depotNodeId.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * 构建“实际 depot 节点 → 配置 depot”索引。
   *
   * <p>线路配置允许使用 {@code DYNAMIC:OP:D:DEPOT:[1:3]}。列车生成后写入的 {@code FTA_DEPOT_ID} 是实际股道（例如 {@code
   * OP:D:DEPOT:2}），如果只做字符串全等，运行时统计永远匹配不到 DYNAMIC 配置，负载均衡就会一直认为第一个 depot 没车。这里把已注册的实际 Depot
   * 节点映射回配置项，确保动态与固定 depot 共用同一套计数口径。
   */
  private Map<String, String> buildDepotAliasIndex(List<SpawnDepot> depots) {
    if (depots == null || depots.isEmpty()) {
      return Map.of();
    }
    Map<String, String> aliases = new HashMap<>();
    Map<String, SignNodeRegistry.SignNodeInfo> registeredNodes = signNodeRegistry.snapshotInfos();
    Map<String, SignNodeRegistry.SignNodeInfo> safeRegisteredNodes =
        registeredNodes == null ? Map.of() : registeredNodes;
    for (SpawnDepot depot : depots) {
      if (depot == null) {
        continue;
      }
      String configuredKey = depot.normalizedKey();
      aliases.put(configuredKey, configuredKey);
      if (!SpawnDirectiveParser.isDynamicTarget(depot.nodeId())) {
        continue;
      }
      if (safeRegisteredNodes.isEmpty()) {
        continue;
      }
      for (SignNodeRegistry.SignNodeInfo info : safeRegisteredNodes.values()) {
        if (info == null || info.definition() == null || info.definition().nodeId() == null) {
          continue;
        }
        if (info.definition().nodeType() != NodeType.DEPOT) {
          continue;
        }
        NodeId actualNode = info.definition().nodeId();
        if (matchesDynamicDepotNode(depot.nodeId(), actualNode.value())) {
          aliases.put(actualNode.value().toLowerCase(Locale.ROOT), configuredKey);
        }
      }
    }
    return Map.copyOf(aliases);
  }

  private static Optional<String> resolveConfiguredDepotKey(
      String depotSpec, List<SpawnDepot> depots, Map<String, String> aliasIndex) {
    if (depotSpec == null || depotSpec.isBlank() || depots == null || depots.isEmpty()) {
      return Optional.empty();
    }
    String normalized = normalizeDepotKey(depotSpec);
    if (aliasIndex != null && aliasIndex.containsKey(normalized)) {
      return Optional.of(aliasIndex.get(normalized));
    }
    for (SpawnDepot depot : depots) {
      if (depot == null) {
        continue;
      }
      if (depot.normalizedKey().equals(normalized)) {
        return Optional.of(depot.normalizedKey());
      }
      if (SpawnDirectiveParser.isDynamicTarget(depot.nodeId())
          && matchesDynamicDepotNode(depot.nodeId(), depotSpec)) {
        return Optional.of(depot.normalizedKey());
      }
    }
    return Optional.empty();
  }

  /**
   * 从同负载 depot 中按权重轮转选择。
   *
   * <p>负载分数负责“少车优先”，本方法只处理分数相同的情况。权重通过复制槽位实现，并限制最大权重，避免错误配置造成过大临时集合。
   */
  private Optional<SpawnDepot> pickDepotByRoundRobin(UUID lineId, List<SpawnDepot> candidates) {
    if (lineId == null || candidates == null || candidates.isEmpty()) {
      return Optional.empty();
    }
    if (candidates.size() == 1) {
      return Optional.of(candidates.get(0));
    }
    List<SpawnDepot> weightedSlots = new ArrayList<>();
    for (SpawnDepot depot : candidates) {
      if (depot == null) {
        continue;
      }
      int weight = Math.max(1, Math.min(1000, depot.weight()));
      for (int i = 0; i < weight; i++) {
        weightedSlots.add(depot);
      }
    }
    if (weightedSlots.isEmpty()) {
      return Optional.empty();
    }
    int cursor =
        depotSelectionCursors
            .computeIfAbsent(lineId, ignored -> new java.util.concurrent.atomic.AtomicInteger())
            .getAndIncrement();
    return Optional.of(weightedSlots.get(Math.floorMod(cursor, weightedSlots.size())));
  }

  /**
   * 判断实际 Depot 节点是否落在 DYNAMIC depot 规范内。
   *
   * <p>DYNAMIC depot 支持 {@code DYNAMIC:OP:D:DEPOT} 与 {@code DYNAMIC:OP:D:DEPOT:[1:3]}
   * 两种常用写法。后者需要按轨道号过滤，否则负载统计与实际发车会把同名 depot 的其它股道错误纳入候选。
   */
  private static boolean matchesDynamicDepotNode(String dynamicSpec, String nodeId) {
    if (dynamicSpec == null
        || nodeId == null
        || !SpawnDirectiveParser.isDynamicTarget(dynamicSpec)) {
      return false;
    }
    String rest = dynamicSpec.substring("DYNAMIC:".length());
    String[] parts = rest.split(":", 4);
    if (parts.length < 3 || !"D".equalsIgnoreCase(parts[1].trim())) {
      return false;
    }
    String prefix = parts[0].trim() + ":" + parts[1].trim() + ":" + parts[2].trim() + ":";
    if (!nodeId.toUpperCase(Locale.ROOT).startsWith(prefix.toUpperCase(Locale.ROOT))) {
      return false;
    }
    if (parts.length < 4 || parts[3].isBlank()) {
      return true;
    }
    Optional<TrackRange> range = parseTrackRange(parts[3]);
    if (range.isEmpty()) {
      return true;
    }
    int track = extractTrackNumber(nodeId);
    return track >= range.get().from() && track <= range.get().to();
  }

  /**
   * 解析 DYNAMIC 轨道范围。
   *
   * <p>当前仅支持单股道（{@code 2}）和闭区间（{@code [1:3]}）。解析失败返回 empty，上层会按“无范围限制” 处理，以兼容历史上未写范围的 DYNAMIC
   * depot。
   */
  private static Optional<TrackRange> parseTrackRange(String rawRange) {
    if (rawRange == null || rawRange.isBlank()) {
      return Optional.empty();
    }
    String trimmed = rawRange.trim();
    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
      String inner = trimmed.substring(1, trimmed.length() - 1);
      int colon = inner.indexOf(':');
      if (colon > 0) {
        try {
          int from = Integer.parseInt(inner.substring(0, colon).trim());
          int to = Integer.parseInt(inner.substring(colon + 1).trim());
          return Optional.of(new TrackRange(Math.min(from, to), Math.max(from, to)));
        } catch (NumberFormatException ignored) {
          return Optional.empty();
        }
      }
    }
    try {
      int track = Integer.parseInt(trimmed);
      return Optional.of(new TrackRange(track, track));
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  /**
   * 从 NodeId 最后一段提取轨道号。
   *
   * <p>Depot 行为节点的轨道号位于最后一段；若传入的是无法识别的咽喉/自定义节点，则返回 {@link Integer#MAX_VALUE}， 使带范围的 DYNAMIC 匹配自然失败。
   */
  private static int extractTrackNumber(String nodeId) {
    if (nodeId == null || nodeId.isBlank()) {
      return Integer.MAX_VALUE;
    }
    int lastColon = nodeId.lastIndexOf(':');
    if (lastColon < 0 || lastColon >= nodeId.length() - 1) {
      return Integer.MAX_VALUE;
    }
    try {
      return Integer.parseInt(nodeId.substring(lastColon + 1).trim());
    } catch (NumberFormatException ignored) {
      return Integer.MAX_VALUE;
    }
  }

  /** DYNAMIC depot 轨道号闭区间。 */
  private record TrackRange(int from, int to) {}

  /** 本轮执行排序使用的 depot 负载候选。 */
  private record DepotExecutionCandidate(SpawnTicket ticket, int originalIndex, double loadScore) {}

  /** 某条线路的 depot 负载快照。 */
  private record DepotLoadSnapshot(
      List<SpawnDepot> depots,
      Map<String, String> aliasIndex,
      Map<String, Integer> activeByDepot,
      Map<String, Integer> weightByDepot) {
    private DepotLoadSnapshot {
      depots = depots == null ? List.of() : List.copyOf(depots);
      aliasIndex = aliasIndex == null ? Map.of() : Map.copyOf(aliasIndex);
      activeByDepot = activeByDepot == null ? Map.of() : Map.copyOf(activeByDepot);
      weightByDepot = weightByDepot == null ? Map.of() : Map.copyOf(weightByDepot);
    }

    private static DepotLoadSnapshot empty() {
      return new DepotLoadSnapshot(List.of(), Map.of(), Map.of(), Map.of());
    }
  }

  /** 一次 tick 内使用的运行时快照，用于线路发车限额与 depot 负载统计。 */
  private static final class LineRuntimeSnapshot {
    private final Map<String, RouteProgressRegistry.RouteProgressEntry> progressEntries;
    private final Map<String, org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId> startNodes;
    private final Map<UUID, UUID> routeLineCache = new HashMap<>();

    private LineRuntimeSnapshot(
        Map<String, RouteProgressRegistry.RouteProgressEntry> progressEntries,
        Map<String, org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId> startNodes) {
      this.progressEntries = progressEntries == null ? Map.of() : progressEntries;
      this.startNodes = startNodes == null ? Map.of() : startNodes;
    }

    static LineRuntimeSnapshot capture(RuntimeDispatchService runtimeDispatchService) {
      if (runtimeDispatchService == null) {
        return new LineRuntimeSnapshot(Map.of(), Map.of());
      }
      return new LineRuntimeSnapshot(
          runtimeDispatchService.snapshotProgressEntries(),
          runtimeDispatchService.snapshotEffectiveStartNodes());
    }

    int countActiveTrains(StorageProvider provider, UUID lineId) {
      return countActiveTrains(provider, lineId, Optional.empty());
    }

    int countActiveTrains(StorageProvider provider, UUID lineId, Optional<String> excludedTrain) {
      if (provider == null || lineId == null) {
        return 0;
      }
      String excludedKey =
          excludedTrain == null || excludedTrain.isEmpty()
              ? ""
              : excludedTrain.get().trim().toLowerCase(Locale.ROOT);
      int count = 0;
      for (RouteProgressRegistry.RouteProgressEntry entry : progressEntries.values()) {
        if (entry != null
            && entry.trainName() != null
            && !excludedKey.isBlank()
            && excludedKey.equals(entry.trainName().trim().toLowerCase(Locale.ROOT))) {
          continue;
        }
        UUID routeId = entry == null ? null : entry.routeUuid();
        if (routeId == null) {
          continue;
        }
        UUID resolvedLine = resolveLineId(provider, routeId);
        if (lineId.equals(resolvedLine)) {
          count++;
        }
      }
      return count;
    }

    Map<String, Integer> countActiveTrainsByDepot(
        StorageProvider provider,
        UUID lineId,
        List<SpawnDepot> depots,
        Map<String, String> depotAliasIndex) {
      if (provider == null || lineId == null || depots == null || depots.isEmpty()) {
        return Map.of();
      }
      Map<String, Integer> counts = new HashMap<>();
      Map<String, String> depotKeys =
          depotAliasIndex == null || depotAliasIndex.isEmpty()
              ? depots.stream()
                  .filter(Objects::nonNull)
                  .collect(
                      Collectors.toMap(
                          SpawnDepot::normalizedKey,
                          SpawnDepot::normalizedKey,
                          (a, b) -> a,
                          java.util.LinkedHashMap::new))
              : depotAliasIndex;
      for (RouteProgressRegistry.RouteProgressEntry entry : progressEntries.values()) {
        if (entry == null) {
          continue;
        }
        UUID routeId = entry.routeUuid();
        if (routeId == null) {
          continue;
        }
        UUID resolvedLine = resolveLineId(provider, routeId);
        if (!lineId.equals(resolvedLine)) {
          continue;
        }
        org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId start =
            startNodes.get(entry.trainName());
        if (start == null) {
          continue;
        }
        String key = start.value().toLowerCase(Locale.ROOT);
        String configuredKey = depotKeys.get(key);
        if (configuredKey == null) {
          continue;
        }
        counts.merge(configuredKey, 1, Integer::sum);
      }
      return counts;
    }

    int countActiveTrainsAtDepot(StorageProvider provider, UUID lineId, NodeId depotNode) {
      if (provider == null || lineId == null || depotNode == null) {
        return 0;
      }
      String expected = depotNode.value().toLowerCase(Locale.ROOT);
      int count = 0;
      for (RouteProgressRegistry.RouteProgressEntry entry : progressEntries.values()) {
        if (entry == null || entry.routeUuid() == null || entry.trainName() == null) {
          continue;
        }
        UUID resolvedLine = resolveLineId(provider, entry.routeUuid());
        if (!lineId.equals(resolvedLine)) {
          continue;
        }
        NodeId start = startNodes.get(entry.trainName());
        if (start != null && expected.equals(start.value().toLowerCase(Locale.ROOT))) {
          count++;
        }
      }
      return count;
    }

    private UUID resolveLineId(StorageProvider provider, UUID routeId) {
      if (routeId == null) {
        return null;
      }
      return routeLineCache.computeIfAbsent(
          routeId, id -> provider.routes().findById(id).map(Route::lineId).orElse(null));
    }
  }

  /**
   * 为 DYNAMIC depot 规范查找世界 ID。
   *
   * <p>解析 "DYNAMIC:OP:D:DEPOT" 或 "DYNAMIC:OP:D:DEPOT:[1:3]" 格式， 查找任意匹配轨道的世界。
   */
  private Optional<java.util.UUID> resolveDynamicDepotWorldId(String dynamicSpec) {
    return resolveDynamicDepotNodeInfo(dynamicSpec).map(SignNodeRegistry.SignNodeInfo::worldId);
  }

  private Optional<NodeId> resolveDepotNode(SpawnService service, Optional<String> depotOverride) {
    Optional<String> depotSpecOpt = resolveDepotSpec(service, depotOverride);
    if (depotSpecOpt.isEmpty()) {
      return Optional.empty();
    }
    String depotSpec = depotSpecOpt.get();
    if (SpawnDirectiveParser.isDynamicTarget(depotSpec)) {
      if (!isDynamicDepotSpec(depotSpec)) {
        return Optional.empty();
      }
      return resolveDynamicDepotNodeInfo(depotSpec).map(info -> info.definition().nodeId());
    }
    return Optional.of(NodeId.of(depotSpec));
  }

  private static Optional<String> resolveDepotSpec(
      SpawnService service, Optional<String> depotOverride) {
    if (service == null) {
      return Optional.empty();
    }
    String depotSpec = depotOverride.filter(s -> !s.isBlank()).orElseGet(service::depotNodeId);
    if (depotSpec == null || depotSpec.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(depotSpec.trim());
  }

  private Optional<SignNodeRegistry.SignNodeInfo> resolveDynamicDepotNodeInfo(String dynamicSpec) {
    return resolveDynamicDepotNodeInfo(dynamicSpec, true);
  }

  /**
   * 为 DYNAMIC depot 规范选择实际节点。
   *
   * <p>优先选择未被占用的 Depot 行为节点；这与实际 spawn 阶段的选择口径一致，能避免发车 gate 总是用排序第一个股道做检查。若没有空闲 Depot，则回退到第一个
   * Depot，使上层 gate 正常阻塞并重试。
   */
  private Optional<SignNodeRegistry.SignNodeInfo> resolveDynamicDepotNodeInfo(
      String dynamicSpec, boolean preferFreeDepot) {
    List<SignNodeRegistry.SignNodeInfo> matches = findDynamicDepotNodeInfos(dynamicSpec);
    if (matches.isEmpty()) {
      return Optional.empty();
    }
    if (preferFreeDepot) {
      for (SignNodeRegistry.SignNodeInfo info : matches) {
        if (info.definition().nodeType() == NodeType.DEPOT
            && !occupancyManager.isNodeOccupied(info.definition().nodeId())) {
          return Optional.of(info);
        }
      }
    }
    for (SignNodeRegistry.SignNodeInfo info : matches) {
      if (info.definition().nodeType() == NodeType.DEPOT) {
        return Optional.of(info);
      }
    }
    debugLogger.accept("DYNAMIC depot world 回退: 未找到 DEPOT 行为节点，使用同前缀图节点 " + dynamicSpec);
    return Optional.of(matches.get(0));
  }

  /**
   * 为 DYNAMIC depot 规范按实际股道负载选择节点。
   *
   * <p>同一个 DYNAMIC 配置可能覆盖多个真实 Depot 股道。本方法会把当前线路在各股道的活跃列车数、本 tick 已选择次数、占用状态与 depot backoff
   * 一并纳入评分，避免多张票据在同一轮全部固化到排序第一个股道。
   */
  private Optional<SignNodeRegistry.SignNodeInfo> resolveDynamicDepotNodeInfo(
      String dynamicSpec,
      boolean preferFreeDepot,
      StorageProvider provider,
      UUID lineId,
      LineRuntimeSnapshot runtimeSnapshot,
      Map<String, Integer> selectedThisTick,
      Instant now) {
    List<SignNodeRegistry.SignNodeInfo> matches = findDynamicDepotNodeInfos(dynamicSpec);
    if (matches.isEmpty()) {
      return Optional.empty();
    }
    List<SignNodeRegistry.SignNodeInfo> depotMatches =
        matches.stream().filter(info -> info.definition().nodeType() == NodeType.DEPOT).toList();
    if (depotMatches.isEmpty()) {
      debugLogger.accept("DYNAMIC depot world 回退: 未找到 DEPOT 行为节点，使用同前缀图节点 " + dynamicSpec);
      return Optional.of(matches.get(0));
    }

    double bestScore = Double.MAX_VALUE;
    List<SignNodeRegistry.SignNodeInfo> best = new ArrayList<>();
    for (SignNodeRegistry.SignNodeInfo info : depotMatches) {
      NodeId nodeId = info.definition().nodeId();
      String key = normalizeDepotKey(nodeId.value());
      int active =
          runtimeSnapshot == null
              ? 0
              : runtimeSnapshot.countActiveTrainsAtDepot(provider, lineId, nodeId);
      int selected = selectedThisTick == null ? 0 : selectedThisTick.getOrDefault(key, 0);
      double occupiedPenalty =
          preferFreeDepot && occupancyManager.isNodeOccupied(nodeId) ? 1000.0D : 0.0D;
      double backoffPenalty =
          depotDispatchCoordinator.backoffUntil(nodeId.value(), now).isPresent() ? 1000.0D : 0.0D;
      double score = active + selected + occupiedPenalty + backoffPenalty;
      if (score < bestScore - 0.000001D) {
        best.clear();
        best.add(info);
        bestScore = score;
        continue;
      }
      if (Math.abs(score - bestScore) <= 0.000001D) {
        best.add(info);
      }
    }
    return pickDynamicDepotByRoundRobin(lineId, dynamicSpec, best);
  }

  private Optional<SignNodeRegistry.SignNodeInfo> pickDynamicDepotByRoundRobin(
      UUID lineId, String dynamicSpec, List<SignNodeRegistry.SignNodeInfo> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return Optional.empty();
    }
    if (candidates.size() == 1 || lineId == null) {
      return Optional.of(candidates.get(0));
    }
    String cursorKey = lineId + "|" + normalizeDepotKey(dynamicSpec);
    int cursor =
        dynamicDepotSelectionCursors
            .computeIfAbsent(cursorKey, ignored -> new java.util.concurrent.atomic.AtomicInteger())
            .getAndIncrement();
    return Optional.of(candidates.get(Math.floorMod(cursor, candidates.size())));
  }

  private List<SignNodeRegistry.SignNodeInfo> findDynamicDepotNodeInfos(String dynamicSpec) {
    if (dynamicSpec == null
        || !dynamicSpec.toUpperCase(java.util.Locale.ROOT).startsWith("DYNAMIC:")) {
      return List.of();
    }
    String rest = dynamicSpec.substring("DYNAMIC:".length());
    String[] parts = rest.split(":", 4);
    if (parts.length < 3) {
      return List.of();
    }
    String operatorCode = parts[0].trim();
    String nodeType = parts[1].trim();
    String nodeName = parts[2].trim();
    if (operatorCode.isEmpty() || nodeType.isEmpty() || nodeName.isEmpty()) {
      return List.of();
    }
    Map<String, SignNodeRegistry.SignNodeInfo> infos = signNodeRegistry.snapshotInfos();
    if (infos == null || infos.isEmpty()) {
      return List.of();
    }
    return infos.values().stream()
        .filter(info -> info != null && info.definition() != null)
        .filter(
            info -> {
              String nodeIdValue = info.definition().nodeId().value();
              return matchesDynamicDepotNode(dynamicSpec, nodeIdValue);
            })
        .sorted(
            Comparator.comparing(
                info -> info.definition().nodeId().value(), String.CASE_INSENSITIVE_ORDER))
        .toList();
  }
}
