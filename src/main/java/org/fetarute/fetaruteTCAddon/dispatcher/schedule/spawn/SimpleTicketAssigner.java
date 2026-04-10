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
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.ServiceTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TerminalKeyResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainNameFormatter;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestBuilder;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;
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
  private final RailGraphService railGraphService;
  private final RouteDefinitionCache routeDefinitions;
  private final RuntimeDispatchService runtimeDispatchService;
  private final ConfigManager configManager;
  private final SignNodeRegistry signNodeRegistry;
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
  // key 为 "<lineId>|<terminal>"：记录即时复用路径（非 pending）的 route 轮转游标。
  private final java.util.concurrent.ConcurrentMap<String, Integer> immediateLayoverRouteCursor =
      new java.util.concurrent.ConcurrentHashMap<>();
  // key 为 "<lineId>|<direction>"：记录该方向当前是否触发拥挤 HOLD。
  private final java.util.concurrent.ConcurrentMap<String, CongestionGateState> congestionGates =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.atomic.AtomicLong lastCongestionCleanupMs =
      new java.util.concurrent.atomic.AtomicLong(0L);

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
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
    this.retryDelay = retryDelay == null ? Duration.ofSeconds(2) : retryDelay;
    this.maxSpawnPerTick = Math.max(1, maxSpawnPerTick);
    this.maxRetryAttempts = Math.max(1, maxRetryAttempts);
  }

  public boolean forceAssign(String trainName, ServiceTicket ticket) {
    // 在 LayoverRegistry 中查找候选列车
    Optional<LayoverRegistry.LayoverCandidate> candidateOpt = layoverRegistry.get(trainName);
    if (candidateOpt.isEmpty()) {
      debugLogger.accept("强制分配失败: 未找到 Layover 列车 " + trainName);
      return false;
    }

    LayoverRegistry.LayoverCandidate candidate = candidateOpt.get();

    // 尝试复用发车
    if (runtimeDispatchService.dispatchLayover(candidate, ticket)) {
      debugLogger.accept("强制分配成功: " + trainName + " -> ticket " + ticket.ticketId());
      return true;
    }

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
    tryDispatchPendingLayover(
        Instant.now(), Optional.empty(), Optional.of(candidate.terminalKey()));
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
    int remaining = maxSpawnPerTick;
    for (SpawnTicket ticket : dueTickets) {
      if (ticket == null || remaining <= 0) {
        break;
      }
      remaining--;
      trySpawn(provider, now, ticket);
    }
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
    if (routeEntity.operationType() == RouteOperationType.OPERATION
        && startsWithCret
        && lineHasCreateRoute(provider, line.id())) {
      debugLogger.accept(
          "自动发车改为复用: line="
              + line.code()
              + " route="
              + routeEntity.code()
              + " reason=create-route-present");
      return tryReuseLayover(Optional.of(provider), ticket, service, route, now, false);
    }
    if (!startsWithCret) {
      return tryReuseLayover(Optional.of(provider), ticket, service, route, now, false);
    }

    OptionalInt lineMaxTrains = resolveLineMaxTrains(provider, line);
    LineRuntimeSnapshot runtimeSnapshot = null;
    if (lineMaxTrains.isPresent()) {
      runtimeSnapshot = LineRuntimeSnapshot.capture(runtimeDispatchService);
      int active = runtimeSnapshot.countActiveTrains(provider, line.id());
      if (active >= lineMaxTrains.getAsInt()) {
        debugLogger.accept(
            "自动发车阻塞: line-cap line="
                + line.code()
                + " active="
                + active
                + " max="
                + lineMaxTrains.getAsInt());
        requeue(ticket, now, "line-cap");
        return false;
      }
    }

    List<SpawnDepot> lineDepots = LineSpawnMetadata.parseDepots(line.metadata());
    Optional<SpawnDepot> selectedDepotOpt = Optional.empty();
    if (!lineDepots.isEmpty()) {
      if (runtimeSnapshot == null) {
        runtimeSnapshot = LineRuntimeSnapshot.capture(runtimeDispatchService);
      }
      selectedDepotOpt = selectBalancedDepot(provider, line.id(), lineDepots, runtimeSnapshot);
    }
    SpawnTicket effectiveTicket =
        selectedDepotOpt.map(depot -> ticket.withSelectedDepot(depot.nodeId())).orElse(ticket);

    String destName = resolveDestinationName(provider, routeEntity).orElse(routeEntity.name());
    String trainName =
        TrainNameFormatter.buildTrainName(
            service.operatorCode(),
            service.lineCode(),
            routeEntity.patternType(),
            destName,
            ticket.id());

    Optional<java.util.UUID> worldIdOpt =
        resolveDepotWorldId(service, effectiveTicket.selectedDepotNodeId());
    if (worldIdOpt.isEmpty()) {
      requeue(ticket, now, "depot-world-missing");
      return false;
    }
    Optional<RailGraph> graphOpt =
        railGraphService.getSnapshot(worldIdOpt.get()).map(s -> s.graph());
    if (graphOpt.isEmpty()) {
      requeue(ticket, now, "graph-missing");
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
    Optional<OccupancyRequest> requestOpt =
        buildDepotSpawnRequest(builder, trainName, route, service, effectiveTicket, now);
    if (requestOpt.isEmpty()) {
      requeue(ticket, now, "occupancy-context-failed");
      return false;
    }
    OccupancyRequest request = requestOpt.get();
    OccupancyDecision decision = occupancyManager.acquire(request);
    if (!decision.allowed()) {
      requeue(ticket, now, "gate-blocked:" + decision.signal());
      return false;
    }

    Optional<MinecartGroup> groupOpt;
    try {
      groupOpt = depotSpawner.spawn(provider, effectiveTicket, trainName, now);
    } catch (Exception e) {
      occupancyManager.releaseByTrain(trainName);
      debugLogger.accept("自动发车异常: spawn 抛出异常 train=" + trainName + " error=" + e);
      requeue(ticket, now, "spawn-failed");
      return false;
    }
    if (groupOpt.isEmpty()) {
      occupancyManager.releaseByTrain(trainName);
      requeue(ticket, now, "spawn-failed");
      return false;
    }
    MinecartGroup group = groupOpt.get();
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

  private static boolean lineHasCreateRoute(StorageProvider provider, UUID lineId) {
    if (provider == null || lineId == null) {
      return false;
    }
    for (Route route : provider.routes().listByLine(lineId)) {
      if (route != null && route.operationType() == RouteOperationType.CREATE) {
        return true;
      }
    }
    return false;
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
      if (runtimeDispatchService.dispatchLayover(candidate, serviceTicket)) {
        applyDispatchLifecycleTags(providerOpt, candidate.trainName(), service, operationType);
        spawnManager.complete(ticket);
        spawnSuccess.increment();
        pendingLayoverTickets.remove(ticket.id());
        debugLogger.accept("Layover 复用成功: " + candidate.trainName() + " -> " + service.routeCode());
        return true;
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

    OptionalInt lineMaxTrains = resolveLineMaxTrains(provider, line);
    LineRuntimeSnapshot runtimeSnapshot = null;
    if (lineMaxTrains.isPresent()) {
      runtimeSnapshot = LineRuntimeSnapshot.capture(runtimeDispatchService);
      int active = runtimeSnapshot.countActiveTrains(provider, line.id());
      if (active >= lineMaxTrains.getAsInt()) {
        debugLogger.accept(
            "Layover 降级发车阻塞: line-cap line="
                + line.code()
                + " active="
                + active
                + " max="
                + lineMaxTrains.getAsInt());
        requeue(ticket, now, "fallback-line-cap");
        return false;
      }
    }

    List<SpawnDepot> lineDepots = LineSpawnMetadata.parseDepots(line.metadata());
    Optional<SpawnDepot> selectedDepotOpt = Optional.empty();
    if (!lineDepots.isEmpty()) {
      if (runtimeSnapshot == null) {
        runtimeSnapshot = LineRuntimeSnapshot.capture(runtimeDispatchService);
      }
      selectedDepotOpt = selectBalancedDepot(provider, line.id(), lineDepots, runtimeSnapshot);
    }
    SpawnTicket effectiveTicket =
        selectedDepotOpt.map(depot -> ticket.withSelectedDepot(depot.nodeId())).orElse(ticket);

    String destName = resolveDestinationName(provider, routeEntity).orElse(routeEntity.name());
    String trainName =
        TrainNameFormatter.buildTrainName(
            service.operatorCode(),
            service.lineCode(),
            routeEntity.patternType(),
            destName,
            ticket.id());

    Optional<java.util.UUID> worldIdOpt =
        resolveDepotWorldId(service, effectiveTicket.selectedDepotNodeId());
    if (worldIdOpt.isEmpty()) {
      requeue(ticket, now, "fallback-depot-world-missing");
      return false;
    }
    Optional<RailGraph> graphOpt =
        railGraphService.getSnapshot(worldIdOpt.get()).map(s -> s.graph());
    if (graphOpt.isEmpty()) {
      requeue(ticket, now, "fallback-graph-missing");
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
    Optional<OccupancyRequest> requestOpt =
        buildDepotSpawnRequest(builder, trainName, route, service, effectiveTicket, now);
    if (requestOpt.isEmpty()) {
      requeue(ticket, now, "fallback-occupancy-context-failed");
      return false;
    }
    OccupancyRequest request = requestOpt.get();
    OccupancyDecision decision = occupancyManager.acquire(request);
    if (!decision.allowed()) {
      requeue(ticket, now, "fallback-gate-blocked:" + decision.signal());
      return false;
    }

    Optional<MinecartGroup> groupOpt;
    try {
      groupOpt = depotSpawner.spawn(provider, effectiveTicket, trainName, now);
    } catch (Exception e) {
      occupancyManager.releaseByTrain(trainName);
      debugLogger.accept("Layover 降级发车异常: spawn 抛出异常 train=" + trainName + " error=" + e);
      requeue(ticket, now, "fallback-spawn-failed");
      return false;
    }
    if (groupOpt.isEmpty()) {
      occupancyManager.releaseByTrain(trainName);
      requeue(ticket, now, "fallback-spawn-failed");
      return false;
    }
    MinecartGroup group = groupOpt.get();
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

  /**
   * 构建 depot spawn 门控请求。
   *
   * <p>优先使用真实 depot 节点做 lookover 锚点，避免 route 首节点不是 depot 时漏检回库车占用。
   */
  private Optional<OccupancyRequest> buildDepotSpawnRequest(
      OccupancyRequestBuilder builder,
      String trainName,
      RouteDefinition route,
      SpawnService service,
      SpawnTicket ticket,
      Instant now) {
    Optional<org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestContext>
        ctxOpt =
            builder.buildContextFromNodes(
                trainName, Optional.ofNullable(route.id()), route.waypoints(), 0, now, 100);
    if (ctxOpt.isEmpty()) {
      return Optional.empty();
    }
    Optional<NodeId> depotNode =
        resolveDepotLookoverNode(
            service, ticket == null ? Optional.empty() : ticket.selectedDepotNodeId());
    if (depotNode.isEmpty()) {
      debugLogger.accept("Depot lookover 回退: 未解析到显式 depot 节点 train=" + trainName);
    }
    return Optional.of(builder.applyDepotLookover(ctxOpt.get(), depotNode));
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

    // 普通 depot：精确匹配 nodeId
    return signNodeRegistry.snapshotInfos().values().stream()
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

  private Optional<SpawnDepot> selectBalancedDepot(
      StorageProvider provider,
      UUID lineId,
      List<SpawnDepot> depots,
      LineRuntimeSnapshot runtimeSnapshot) {
    if (provider == null || lineId == null || depots == null || depots.isEmpty()) {
      return Optional.empty();
    }
    Map<String, Integer> activeByDepot =
        runtimeSnapshot.countActiveTrainsByDepot(provider, lineId, depots);
    SpawnDepot selected = null;
    double bestScore = Double.MAX_VALUE;
    for (SpawnDepot depot : depots) {
      if (depot == null) {
        continue;
      }
      int active = activeByDepot.getOrDefault(depot.normalizedKey(), 0);
      double score = active / (double) depot.weight();
      if (selected == null || score < bestScore) {
        selected = depot;
        bestScore = score;
      }
    }
    return Optional.ofNullable(selected);
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
      if (provider == null || lineId == null) {
        return 0;
      }
      int count = 0;
      for (RouteProgressRegistry.RouteProgressEntry entry : progressEntries.values()) {
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
        StorageProvider provider, UUID lineId, List<SpawnDepot> depots) {
      if (provider == null || lineId == null || depots == null || depots.isEmpty()) {
        return Map.of();
      }
      Map<String, Integer> counts = new HashMap<>();
      Map<String, String> depotKeys =
          depots.stream()
              .filter(Objects::nonNull)
              .collect(
                  Collectors.toMap(
                      SpawnDepot::normalizedKey,
                      SpawnDepot::normalizedKey,
                      (a, b) -> a,
                      java.util.LinkedHashMap::new));
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
        if (!depotKeys.containsKey(key)) {
          continue;
        }
        counts.merge(key, 1, Integer::sum);
      }
      return counts;
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
    if (dynamicSpec == null
        || !dynamicSpec.toUpperCase(java.util.Locale.ROOT).startsWith("DYNAMIC:")) {
      return Optional.empty();
    }
    String rest = dynamicSpec.substring("DYNAMIC:".length());
    String[] parts = rest.split(":", 4);
    if (parts.length < 3) {
      return Optional.empty();
    }
    String operatorCode = parts[0].trim();
    String nodeType = parts[1].trim();
    String nodeName = parts[2].trim();
    if (operatorCode.isEmpty() || nodeType.isEmpty() || nodeName.isEmpty()) {
      return Optional.empty();
    }
    String nodeIdPrefix = operatorCode + ":" + nodeType + ":" + nodeName + ":";
    String upperPrefix = nodeIdPrefix.toUpperCase(java.util.Locale.ROOT);
    List<SignNodeRegistry.SignNodeInfo> matches =
        signNodeRegistry.snapshotInfos().values().stream()
            .filter(info -> info != null && info.definition() != null)
            .filter(
                info -> {
                  String nodeIdValue = info.definition().nodeId().value();
                  return nodeIdValue != null
                      && nodeIdValue.toUpperCase(java.util.Locale.ROOT).startsWith(upperPrefix);
                })
            .sorted(
                Comparator.comparing(
                    info -> info.definition().nodeId().value(), String.CASE_INSENSITIVE_ORDER))
            .toList();
    if (matches.isEmpty()) {
      return Optional.empty();
    }
    // 优先使用 Depot 行为节点；若不存在则回退到同前缀的图节点（如咽喉 Waypoint），用于兜底 world 推断。
    for (SignNodeRegistry.SignNodeInfo info : matches) {
      if (info.definition().nodeType() == NodeType.DEPOT) {
        return Optional.of(info);
      }
    }
    debugLogger.accept("DYNAMIC depot world 回退: 未找到 DEPOT 行为节点，使用同前缀图节点 " + dynamicSpec);
    return Optional.of(matches.get(0));
  }

  private static Optional<String> resolveDestinationName(
      StorageProvider provider, org.fetarute.fetaruteTCAddon.company.model.Route route) {
    if (provider == null || route == null) {
      return Optional.empty();
    }
    List<org.fetarute.fetaruteTCAddon.company.model.RouteStop> stops =
        provider.routeStops().listByRoute(route.id());
    if (stops.isEmpty()) {
      return Optional.empty();
    }
    org.fetarute.fetaruteTCAddon.company.model.RouteStop candidate = null;
    for (org.fetarute.fetaruteTCAddon.company.model.RouteStop stop : stops) {
      if (stop != null
          && stop.passType()
              == org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType.TERMINATE) {
        candidate = stop;
      }
    }
    if (candidate == null) {
      for (org.fetarute.fetaruteTCAddon.company.model.RouteStop stop : stops) {
        if (stop != null
            && stop.passType()
                == org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType.STOP) {
          candidate = stop;
        }
      }
    }
    if (candidate == null) {
      candidate = stops.get(stops.size() - 1);
    }
    if (candidate.stationId().isPresent()) {
      Optional<org.fetarute.fetaruteTCAddon.company.model.Station> stationOpt =
          provider.stations().findById(candidate.stationId().get());
      if (stationOpt.isPresent()) {
        return Optional.of(stationOpt.get().name());
      }
    }
    if (candidate.waypointNodeId().isPresent()) {
      return Optional.of(candidate.waypointNodeId().get());
    }
    return Optional.of(route.name());
  }
}
