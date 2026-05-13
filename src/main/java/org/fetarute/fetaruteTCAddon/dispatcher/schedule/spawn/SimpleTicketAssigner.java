package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
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
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LaunchAuthorizationService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.ServiceTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TerminalKeyResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainCartsRuntimeHandle;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainNameFormatter;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestBuilder;
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

  private static final java.util.logging.Logger HEALTH_LOGGER =
      java.util.logging.Logger.getLogger("FetaruteTCAddon");

  /** 列车已完成运营圈数（按 OPERATION 票据发车成功累计）。 */
  static final String TAG_OPERATION_TRIPS = "FTA_OP_TRIPS";

  /** 列车最大运营圈数（达到后应优先分配 RETURN 回库）。 */
  static final String TAG_MAX_OPERATION_TRIPS = "FTA_OP_MAX";

  /** 列车绑定的交路组名（用于诊断）。 */
  static final String TAG_CIRCULATION_GROUP = "FTA_SPAWN_GROUP";

  /** 待复用票据刷新窗口（秒）：超过后会刷新等待窗口并记录告警，避免长期静默卡死。 */
  private static final long PENDING_LAYOVER_REFRESH_SECONDS = 300L;

  /** 待复用票据默认最大保留时间；配置缺失时使用，0 表示显式禁用硬清理。 */
  private static final Duration DEFAULT_PENDING_LAYOVER_MAX_AGE = Duration.ofDays(1);

  /** 拥挤度进入 HOLD 的阈值。 */
  private static final double CONGESTION_HOLD_THRESHOLD = 0.72D;

  /** 拥挤度退出 HOLD 的阈值（滞回，避免频繁抖动）。 */
  private static final double CONGESTION_RELEASE_THRESHOLD = 0.58D;

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
   * 拥挤度评估快照。
   *
   * <p>score 范围为 [0,1]，值越高表示越拥挤。
   */
  private record CongestionAssessment(
      double score,
      double edgeBusyRate,
      double routeTrainPressure,
      double lineSignalPressure,
      int busyEdges,
      int totalEdges,
      int activeRouteTrains,
      int targetRouteTrains) {}

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
    this.retryDelay = retryDelay == null ? Duration.ofSeconds(2) : retryDelay;
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
    if (runtimeDispatchService.dispatchLayover(candidate, ticket)) {
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
    int count = pendingLayoverTickets.size();
    pendingLayoverTickets.clear();
    pendingLayoverRouteCursor.clear();
    immediateLayoverRouteCursor.clear();
    return count;
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
    spawnControl.pruneExpired(now);
    cleanupStaleCongestionGates(now);
    if (!pendingLayoverTickets.isEmpty()) {
      refreshExpiredPendingTickets(provider, now);
      tryDispatchPendingLayover(now, Optional.of(provider), Optional.empty());
    }
    List<SpawnTicket> dueTickets = spawnManager.pollDueTickets(provider, now);
    if (dueTickets.isEmpty()) {
      return;
    }
    dueTickets = orderDueTicketsWithRouteRotation(dueTickets);
    dueTickets = applyDepotDispatchCoordination(provider, dueTickets, now);
    dueTickets = orderDepotTicketsByLineDepotLoad(provider, dueTickets);
    int remaining = maxSpawnPerTick;
    for (SpawnTicket ticket : dueTickets) {
      if (ticket == null) {
        continue;
      }
      if (remaining <= 0) {
        deferWithoutAttempt(ticket, now, "spawn-per-tick-limit");
        continue;
      }
      remaining--;
      trySpawn(provider, now, ticket);
    }
  }

  private List<SpawnTicket> applyDepotDispatchCoordination(
      StorageProvider provider, List<SpawnTicket> dueTickets, Instant now) {
    if (provider == null || dueTickets == null || dueTickets.isEmpty()) {
      return dueTickets == null ? List.of() : dueTickets;
    }
    List<SpawnTicket> depotTickets = new ArrayList<>();
    LineRuntimeSnapshot runtimeSnapshot = LineRuntimeSnapshot.capture(runtimeDispatchService);
    Map<String, Integer> selectedThisTick = new HashMap<>();
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
  private void refreshExpiredPendingTickets(StorageProvider provider, Instant now) {
    java.util.List<java.util.UUID> removeIds = new java.util.ArrayList<>();
    java.util.Map<java.util.UUID, PendingLayoverEntry> refreshedEntries = new java.util.HashMap<>();
    int refreshed = 0;
    int fallbackTriggered = 0;
    int hardExpired = 0;
    Duration hardMaxAge = resolvePendingLayoverMaxAge();

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

      if (isPendingLayoverHardExpired(pendingEntry, now, hardMaxAge)) {
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
          removeIds.add(ticketId);
          fallbackTriggered++;
          HEALTH_LOGGER.warning(
              "[FTA] 折返票据等待过久，尝试 depot 补发: route="
                  + (service != null ? service.routeCode() : "?")
                  + " 等待="
                  + waitSeconds
                  + "s ticketId="
                  + ticketId);
          tryFallbackSpawnForPending(provider, ticket, now, waitSeconds);
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
      StorageProvider provider, SpawnTicket ticket, Instant now, long waitSeconds) {
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
    trySpawnFromDepot(provider, ticket, service, routeOpt.get(), lineOpt.get(), now);
  }

  private boolean trySpawn(StorageProvider provider, Instant now, SpawnTicket ticket) {
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
              pendingLayoverTickets.remove(ticket.id());
              debugLogger.accept(
                  "Layover 降级发车: route="
                      + service.routeCode()
                      + " 等待="
                      + waitSeconds
                      + "s (超时="
                      + fallbackTimeoutSeconds.getAsLong()
                      + "s) 尝试从 depot 补发");
              return trySpawnFromDepot(provider, ticket, service, route, line, now);
            }
            debugLogger.accept(
                "Layover 降级跳过: route="
                    + service.routeCode()
                    + " 等待="
                    + waitSeconds
                    + "s 但首站非 depot，继续等待复用");
          }
        }
      }
      return tryReuseLayover(Optional.of(provider), ticket, service, route, now, false);
    }

    if (shouldHoldByCongestion(provider, service, line, routeEntity, route, now)) {
      requeue(ticket, now, "congestion-hold");
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

    List<SpawnDepot> lineDepots = LineSpawnMetadata.parseDepots(line.metadata());
    Optional<SpawnDepot> selectedDepotOpt = Optional.empty();
    if (!lineDepots.isEmpty() && ticket.selectedDepotNodeId().isEmpty()) {
      LineRuntimeSnapshot runtimeSnapshot = LineRuntimeSnapshot.capture(runtimeDispatchService);
      selectedDepotOpt =
          selectBalancedDepot(provider, line.id(), lineDepots, runtimeSnapshot, Map.of(), now);
    }
    SpawnTicket effectiveTicket =
        selectedDepotOpt.map(depot -> ticket.withSelectedDepot(depot.nodeId())).orElse(ticket);
    effectiveTicket =
        materializeDynamicDepotSelection(
            provider,
            service,
            effectiveTicket,
            LineRuntimeSnapshot.capture(runtimeDispatchService),
            Map.of(),
            now);

    String destCode =
        RouteDestinationResolver.resolve(provider, routeEntity)
            .map(RouteDestinationResolver.DestinationInfo::code)
            .orElse(routeEntity.code());
    String trainName =
        TrainNameFormatter.buildTrainName(
            service.operatorCode(),
            service.lineCode(),
            routeEntity.patternType(),
            destCode,
            ticket.id());

    Optional<java.util.UUID> worldIdOpt =
        resolveDepotWorldId(service, effectiveTicket.selectedDepotNodeId());
    if (worldIdOpt.isEmpty()) {
      releaseSpawnLease(spawnLease);
      requeue(effectiveTicket, now, "depot-world-missing");
      return false;
    }
    Optional<RailGraph> graphOpt =
        railGraphService.getSnapshot(worldIdOpt.get()).map(s -> s.graph());
    if (graphOpt.isEmpty()) {
      releaseSpawnLease(spawnLease);
      requeue(effectiveTicket, now, "graph-missing");
      return false;
    }
    ConfigManager.RuntimeSettings runtime = configManager.current().runtimeSettings();
    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(
            graphOpt.get(),
            runtime.lookaheadEdges(),
            runtime.minClearEdges(),
            runtime.rearGuardEdges(),
            runtime.switcherZoneEdges(),
            debugLogger);
    Optional<DepotGateRequest> gateRequestOpt =
        buildDepotSpawnGateRequest(builder, trainName, route, service, effectiveTicket, now);
    if (gateRequestOpt.isEmpty()) {
      releaseSpawnLease(spawnLease);
      requeue(effectiveTicket, now, "occupancy-context-failed");
      return false;
    }
    DepotGateRequest gateRequest = gateRequestOpt.get();
    OccupancyRequest request = gateRequest.request();
    LaunchAuthorizationService.AuthorizationResult authorization = previewSpawnGate(request);
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
          "preview");
      releaseSpawnLease(spawnLease);
      requeue(effectiveTicket, now, "gate-blocked:" + spawnGateSignalText(authorization));
      return false;
    }

    Optional<MinecartGroup> groupOpt;
    try {
      groupOpt = depotSpawner.spawn(provider, effectiveTicket, trainName, now);
    } catch (Exception e) {
      releaseSpawnLease(spawnLease);
      occupancyManager.releaseByTrain(trainName);
      debugLogger.accept("自动发车异常: spawn 抛出异常 train=" + trainName + " error=" + e);
      requeue(effectiveTicket, now, "spawn-failed");
      return false;
    }
    if (groupOpt.isEmpty()) {
      releaseSpawnLease(spawnLease);
      occupancyManager.releaseByTrain(trainName);
      requeue(effectiveTicket, now, "spawn-failed");
      return false;
    }
    MinecartGroup group = groupOpt.get();
    authorization = acquireSpawnGate(request);
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
          "acquire");
      releaseSpawnLease(spawnLease);
      occupancyManager.releaseByTrain(trainName);
      destroySpawnedGroup(group);
      requeue(effectiveTicket, now, "gate-blocked:" + spawnGateSignalText(authorization));
      return false;
    }
    if (group.getProperties() != null && route.waypoints().size() >= 2) {
      group.getProperties().clearDestinationRoute();
      group.getProperties().clearDestination();
      group.getProperties().setDestination(route.waypoints().get(1).value());
      applySpawnLifecycleTags(
          Optional.of(provider), group.getProperties(), service, routeEntity.operationType());
    }
    runtimeDispatchService.refreshSignal(group);
    runtimeDispatchService.refreshSignalsForResources(request.resourceList(), trainName);
    spawnManager.complete(effectiveTicket);
    spawnSuccess.increment();
    String depotUsed = effectiveTicket.selectedDepotNodeId().orElse(service.depotNodeId());
    debugLogger.accept(
        "自动发车成功: train="
            + trainName
            + " route="
            + service.operatorCode()
            + "/"
            + service.lineCode()
            + "/"
            + service.routeCode()
            + " depot="
            + depotUsed);
    return true;
  }

  /**
   * 按“线路+方向”的拥挤度执行 HOLD 门控。
   *
   * <p>该门控只作用于 {@code OPERATION/CREATE}，RETURN 始终允许通过以便回库释放压力。
   */
  private boolean shouldHoldByCongestion(
      StorageProvider provider,
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
      return false;
    }
    CongestionAssessment assessment =
        evaluateCongestion(provider, service, line, routeEntity, route);
    String gateKey = buildCongestionGateKey(service);
    CongestionGateState previous = congestionGates.get(gateKey);
    boolean wasHolding = previous != null && previous.holding();
    boolean holding =
        wasHolding
            ? assessment.score() >= CONGESTION_RELEASE_THRESHOLD
            : assessment.score() >= CONGESTION_HOLD_THRESHOLD;
    congestionGates.put(gateKey, new CongestionGateState(holding, assessment.score(), now));

    if (holding) {
      String scoreSummary =
          String.format(
              Locale.ROOT,
              "score=%.2f edge=%.2f(%d/%d) route=%.2f(%d/%d) signal=%.2f",
              assessment.score(),
              assessment.edgeBusyRate(),
              assessment.busyEdges(),
              assessment.totalEdges(),
              assessment.routeTrainPressure(),
              assessment.activeRouteTrains(),
              assessment.targetRouteTrains(),
              assessment.lineSignalPressure());
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
   * <p>评分由三部分线性组合：
   *
   * <ul>
   *   <li>edgeBusyRate：route 边集合中被占用的比例
   *   <li>routeTrainPressure：同 route 在途车数 / 目标车数
   *   <li>lineSignalPressure：同 line 列车信号压力（STOP/CAUTION 等）
   * </ul>
   */
  private CongestionAssessment evaluateCongestion(
      StorageProvider provider,
      SpawnService service,
      Line line,
      Route routeEntity,
      RouteDefinition route) {
    Set<String> routeEdges = collectRouteEdgeKeys(route);
    int totalEdges = routeEdges.size();
    Set<String> busyEdgeKeys = new HashSet<>();
    if (!routeEdges.isEmpty()) {
      for (OccupancyClaim claim : occupancyManager.snapshotClaims()) {
        if (claim == null || claim.resource() == null) {
          continue;
        }
        if (claim.resource().kind() != ResourceKind.EDGE) {
          continue;
        }
        String key = normalizeEdgeKey(claim.resource().key());
        if (!key.isBlank() && routeEdges.contains(key)) {
          busyEdgeKeys.add(key);
        }
      }
    }
    int busyEdges = busyEdgeKeys.size();
    double edgeBusyRate =
        totalEdges <= 0 ? 0.0D : clamp01((double) busyEdges / (double) totalEdges);

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

    double score =
        clamp01(edgeBusyRate * 0.55D + routeTrainPressure * 0.30D + lineSignalPressure * 0.15D);
    return new CongestionAssessment(
        score,
        edgeBusyRate,
        routeTrainPressure,
        lineSignalPressure,
        busyEdges,
        totalEdges,
        activeRouteTrains,
        targetRouteTrains);
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
        pendingLayoverTickets.remove(ticket.id());
        continue;
      }
      Optional<RouteDefinition> routeOpt = routeDefinitions.findById(service.routeId());
      if (routeOpt.isEmpty()) {
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
    List<LayoverRegistry.LayoverCandidate> candidates =
        layoverRegistry.findCandidates(startNodeVal);
    if (candidates.isEmpty()) {
      if (!pendingAttempt) {
        putPendingLayoverTicket(ticket, now);
        debugLogger.accept("Layover 复用等待: route=" + service.routeCode() + " start=" + startNodeVal);
      }
      return false;
    }
    // 过滤掉 readyAt 尚未到达（dwell 未结束）的候选
    List<LayoverRegistry.LayoverCandidate> readyCandidates = new ArrayList<>();
    for (LayoverRegistry.LayoverCandidate c : candidates) {
      if (c.readyAt().isAfter(now)) {
        continue;
      }
      readyCandidates.add(c);
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
    ServiceTicket serviceTicket =
        new ServiceTicket(
            ticket.id().toString(),
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
      if (runtimeDispatchService.dispatchLayover(candidate, serviceTicket)) {
        applyDispatchLifecycleTags(providerOpt, candidate.trainName(), service, operationType);
        spawnManager.complete(ticket);
        spawnSuccess.increment();
        pendingLayoverTickets.remove(ticket.id());
        debugLogger.accept("Layover 复用成功: " + candidate.trainName() + " -> " + service.routeCode());
        return true;
      }
      releaseSpawnLease(spawnLease);
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
    if (trainName == null || trainName.isBlank() || service == null) {
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
   * @return 是否成功发车
   */
  private boolean trySpawnFromDepot(
      StorageProvider provider,
      SpawnTicket ticket,
      SpawnService service,
      RouteDefinition route,
      Line line,
      Instant now) {

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

    List<SpawnDepot> lineDepots = LineSpawnMetadata.parseDepots(line.metadata());
    Optional<SpawnDepot> selectedDepotOpt = Optional.empty();
    if (!lineDepots.isEmpty() && ticket.selectedDepotNodeId().isEmpty()) {
      LineRuntimeSnapshot runtimeSnapshot = LineRuntimeSnapshot.capture(runtimeDispatchService);
      selectedDepotOpt =
          selectBalancedDepot(provider, line.id(), lineDepots, runtimeSnapshot, Map.of(), now);
    }
    SpawnTicket effectiveTicket =
        selectedDepotOpt.map(depot -> ticket.withSelectedDepot(depot.nodeId())).orElse(ticket);
    effectiveTicket =
        materializeDynamicDepotSelection(
            provider,
            service,
            effectiveTicket,
            LineRuntimeSnapshot.capture(runtimeDispatchService),
            Map.of(),
            now);

    String destCode =
        RouteDestinationResolver.resolve(provider, routeEntity)
            .map(RouteDestinationResolver.DestinationInfo::code)
            .orElse(routeEntity.code());
    String trainName =
        TrainNameFormatter.buildTrainName(
            service.operatorCode(),
            service.lineCode(),
            routeEntity.patternType(),
            destCode,
            ticket.id());

    Optional<java.util.UUID> worldIdOpt =
        resolveDepotWorldId(service, effectiveTicket.selectedDepotNodeId());
    if (worldIdOpt.isEmpty()) {
      releaseSpawnLease(spawnLease);
      requeue(effectiveTicket, now, "fallback-depot-world-missing");
      return false;
    }
    Optional<RailGraph> graphOpt =
        railGraphService.getSnapshot(worldIdOpt.get()).map(s -> s.graph());
    if (graphOpt.isEmpty()) {
      releaseSpawnLease(spawnLease);
      requeue(effectiveTicket, now, "fallback-graph-missing");
      return false;
    }
    ConfigManager.RuntimeSettings runtime = configManager.current().runtimeSettings();
    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(
            graphOpt.get(),
            runtime.lookaheadEdges(),
            runtime.minClearEdges(),
            runtime.rearGuardEdges(),
            runtime.switcherZoneEdges(),
            debugLogger);
    Optional<DepotGateRequest> gateRequestOpt =
        buildDepotSpawnGateRequest(builder, trainName, route, service, effectiveTicket, now);
    if (gateRequestOpt.isEmpty()) {
      releaseSpawnLease(spawnLease);
      requeue(effectiveTicket, now, "fallback-occupancy-context-failed");
      return false;
    }
    DepotGateRequest gateRequest = gateRequestOpt.get();
    OccupancyRequest request = gateRequest.request();
    LaunchAuthorizationService.AuthorizationResult authorization = previewSpawnGate(request);
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
          "fallback-preview");
      releaseSpawnLease(spawnLease);
      requeue(effectiveTicket, now, "fallback-gate-blocked:" + spawnGateSignalText(authorization));
      return false;
    }

    Optional<MinecartGroup> groupOpt;
    try {
      groupOpt = depotSpawner.spawn(provider, effectiveTicket, trainName, now);
    } catch (Exception e) {
      releaseSpawnLease(spawnLease);
      occupancyManager.releaseByTrain(trainName);
      debugLogger.accept("Layover 降级发车异常: spawn 抛出异常 train=" + trainName + " error=" + e);
      requeue(effectiveTicket, now, "fallback-spawn-failed");
      return false;
    }
    if (groupOpt.isEmpty()) {
      releaseSpawnLease(spawnLease);
      occupancyManager.releaseByTrain(trainName);
      requeue(effectiveTicket, now, "fallback-spawn-failed");
      return false;
    }
    MinecartGroup group = groupOpt.get();
    authorization = acquireSpawnGate(request);
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
          "fallback-acquire");
      releaseSpawnLease(spawnLease);
      occupancyManager.releaseByTrain(trainName);
      destroySpawnedGroup(group);
      requeue(effectiveTicket, now, "fallback-gate-blocked:" + spawnGateSignalText(authorization));
      return false;
    }
    if (group.getProperties() != null && route.waypoints().size() >= 2) {
      group.getProperties().clearDestinationRoute();
      group.getProperties().clearDestination();
      group.getProperties().setDestination(route.waypoints().get(1).value());
      applySpawnLifecycleTags(
          Optional.of(provider), group.getProperties(), service, routeEntity.operationType());
    }
    runtimeDispatchService.refreshSignal(group);
    runtimeDispatchService.refreshSignalsForResources(request.resourceList(), trainName);
    spawnManager.complete(effectiveTicket);
    spawnSuccess.increment();
    String depotUsed = effectiveTicket.selectedDepotNodeId().orElse(service.depotNodeId());
    debugLogger.accept(
        "Layover 降级发车成功: train="
            + trainName
            + " route="
            + service.operatorCode()
            + "/"
            + service.lineCode()
            + "/"
            + service.routeCode()
            + " depot="
            + depotUsed);
    return true;
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
      pendingLayoverTickets.remove(ticket.id());
      spawnManager.complete(ticket);
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
      List<NodeId> effectiveWaypoints,
      List<NodeId> expandedPathNodes,
      int lookoverDepth,
      Optional<NodeId> selectedDepotNode,
      NodeId originalFirstWaypoint,
      NodeId effectiveFirstWaypoint) {
    private DepotGateRequest {
      Objects.requireNonNull(request, "request");
      effectiveWaypoints = effectiveWaypoints == null ? List.of() : List.copyOf(effectiveWaypoints);
      expandedPathNodes = expandedPathNodes == null ? List.of() : List.copyOf(expandedPathNodes);
      selectedDepotNode = selectedDepotNode == null ? Optional.empty() : selectedDepotNode;
    }
  }

  private Optional<DepotGateRequest> buildDepotSpawnGateRequest(
      OccupancyRequestBuilder builder,
      String trainName,
      RouteDefinition route,
      SpawnService service,
      SpawnTicket ticket,
      Instant now) {
    List<NodeId> spawnWaypoints = resolveDepotSpawnWaypoints(route, service, ticket);
    Optional<org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestContext>
        ctxOpt =
            builder.buildContextFromNodes(
                trainName,
                Optional.ofNullable(route.id()),
                spawnWaypoints,
                0,
                now,
                100 + Math.max(0, ticket == null ? 0 : ticket.priority()),
                AuthorizationPurpose.DEPOT_SPAWN);
    if (ctxOpt.isEmpty()) {
      return Optional.empty();
    }
    Optional<NodeId> depotNode =
        resolveDepotLookoverNode(
            service, ticket == null ? Optional.empty() : ticket.selectedDepotNodeId());
    if (depotNode.isEmpty()) {
      debugLogger.accept("Depot lookover 回退: 未解析到显式 depot 节点 train=" + trainName);
    }
    OccupancyRequest request = builder.applyDepotLookover(ctxOpt.get(), depotNode);
    NodeId originalFirst = route.waypoints().isEmpty() ? null : route.waypoints().get(0);
    NodeId effectiveFirst = spawnWaypoints.isEmpty() ? null : spawnWaypoints.get(0);
    int lookoverDepth = builder.depotLookoverDepthForDiagnostics(depotNode.isPresent());
    return Optional.of(
        new DepotGateRequest(
            request,
            spawnWaypoints,
            ctxOpt.get().pathNodes(),
            lookoverDepth,
            depotNode,
            originalFirst,
            effectiveFirst));
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
        resolveDepotLookoverNode(
            service, ticket == null ? Optional.empty() : ticket.selectedDepotNodeId());
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
            + " lookoverDepth="
            + gateRequest.lookoverDepth()
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

  /**
   * 回收已实体化但未取得调度占用的 Depot 列车。
   *
   * <p>spawn 后 acquire 可能因同 tick 内其他状态变化失败；此时必须销毁刚创建的 TrainCarts group，避免没有 occupancy lease
   * 的实体车留在线路上。
   */
  private static void destroySpawnedGroup(MinecartGroup group) {
    if (group == null) {
      return;
    }
    new TrainCartsRuntimeHandle(group).destroy();
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
   * <p>{@code DepotDispatchCoordinator} 只负责同一 depot 的互斥与退避；当 {@code maxSpawnPerTick} 小于本轮 ready
   * 票据数时，仍需要在进入执行循环前把低负载 depot 的票据排到前面。否则列表稳定排序会让 route code 靠前、且经常被单线 depot gate 阻塞的票据反复占用本 tick
   * 执行名额，导致同线路另一个 depot 的交路组长期得不到尝试。
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

  private Optional<NodeId> resolveDepotLookoverNode(
      SpawnService service, Optional<String> depotOverride) {
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
