package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.ServiceTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainNameFormatter;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestBuilder;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 简单票据分配器：优先复用 Layover 列车，不足时从 Depot 生成。
 *
 * <p>当前实现整合 LayoverRegistry 查找与 RuntimeDispatchService 的“复用发车”能力。
 */
public final class SimpleTicketAssigner implements TicketAssigner {

  private static final java.util.logging.Logger HEALTH_LOGGER =
      java.util.logging.Logger.getLogger("FetaruteTCAddon");

  /** 待复用票据刷新窗口（秒）：超过后会刷新等待窗口并记录告警，避免长期静默卡死。 */
  private static final long PENDING_LAYOVER_REFRESH_SECONDS = 300L;

  /**
   * 待复用票据条目：记录票据及其加入时间，用于超时清理。
   *
   * @param ticket 原始出车票据
   * @param addedAt 加入等待队列的时间
   */
  private record PendingLayoverEntry(SpawnTicket ticket, Instant addedAt) {}

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
    tryDispatchPendingLayover(Instant.now(), Optional.of(candidate.terminalKey()));
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
    if (!pendingLayoverTickets.isEmpty()) {
      refreshExpiredPendingTickets(provider, now);
      tryDispatchPendingLayover(now, Optional.empty());
    }
    List<SpawnTicket> dueTickets = spawnManager.pollDueTickets(provider, now);
    if (dueTickets.isEmpty()) {
      return;
    }
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
   *   <li>在可配置阈值到达后，允许 RETURN 服务从 depot 补发，避免 Layover 长时间不可用
   *   <li>保留 pending 的“首次入队时间语义”，只在真正超时时刷新窗口
   * </ul>
   */
  private void refreshExpiredPendingTickets(StorageProvider provider, Instant now) {
    java.util.List<java.util.UUID> removeIds = new java.util.ArrayList<>();
    java.util.Map<java.util.UUID, PendingLayoverEntry> refreshedEntries = new java.util.HashMap<>();
    int refreshed = 0;
    int fallbackTriggered = 0;

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

      java.util.OptionalLong fallbackTimeoutSeconds = resolveLayoverFallbackTimeoutSeconds(service);
      if (fallbackTimeoutSeconds.isPresent() && waitSeconds >= fallbackTimeoutSeconds.getAsLong()) {
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
        continue;
      }

      if (waitSeconds >= PENDING_LAYOVER_REFRESH_SECONDS) {
        refreshedEntries.put(ticketId, new PendingLayoverEntry(ticket, now));
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
    if (refreshed > 0 || fallbackTriggered > 0 || !removeIds.isEmpty()) {
      debugLogger.accept(
          "刷新待复用票据: refreshed="
              + refreshed
              + " fallback="
              + fallbackTriggered
              + " removed="
              + removeIds.size());
    }
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
        }
      }
      return tryReuseLayover(ticket, service, route, now, false);
    }

    List<org.fetarute.fetaruteTCAddon.company.model.RouteStop> stops =
        provider.routeStops().listByRoute(routeEntity.id());
    boolean startsWithCret =
        !stops.isEmpty()
            && SpawnDirectiveParser.findDirectiveTarget(stops.get(0), "CRET").isPresent();
    if (!startsWithCret) {
      return tryReuseLayover(ticket, service, route, now, false);
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
    Optional<org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestContext>
        ctxOpt =
            builder.buildContextFromNodes(
                trainName, Optional.ofNullable(route.id()), route.waypoints(), 0, now, 100);
    if (ctxOpt.isEmpty()) {
      requeue(ticket, now, "occupancy-context-failed");
      return false;
    }
    org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestContext ctx =
        ctxOpt.get();
    OccupancyRequest request = builder.applyDepotLookover(ctx);
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
    }
    runtimeDispatchService.refreshSignal(group);
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

  private void tryDispatchPendingLayover(Instant now, Optional<String> terminalFilter) {
    if (pendingLayoverTickets.isEmpty()) {
      return;
    }
    List<PendingLayoverDispatchEntry> dispatchOrder = buildPendingDispatchOrder(terminalFilter);
    for (PendingLayoverDispatchEntry entry : dispatchOrder) {
      SpawnTicket ticket = entry.ticket();
      if (ticket == null || !pendingLayoverTickets.containsKey(ticket.id())) {
        continue;
      }
      if (tryReuseLayover(ticket, entry.service(), entry.route(), now, true)) {
        pendingLayoverTickets.remove(ticket.id());
      }
    }
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
      if (terminalFilter.isPresent() && !terminalFilter.get().equalsIgnoreCase(terminalKey)) {
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
   * 对同一 (line + terminal) 分组执行 route 轮转。
   *
   * <p>游标按成功候选次序持续推进，确保在高频 pending 下也能保持 route 级公平。
   */
  private List<PendingLayoverDispatchEntry> orderPendingGroupWithRouteRotation(
      String groupKey, List<PendingLayoverDispatchEntry> groupEntries) {
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
    int startCursor =
        Math.floorMod(pendingLayoverRouteCursor.getOrDefault(groupKey, 0), Math.max(1, routeCount));
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
    pendingLayoverRouteCursor.put(groupKey, (startCursor + 1) % routeCount);
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

  private boolean tryReuseLayover(
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
    ServiceTicket serviceTicket =
        new ServiceTicket(
            ticket.id().toString(),
            ticket.scheduledTime(),
            service.routeId(),
            startNodeVal,
            0,
            ServiceTicket.TicketMode.OPERATION);
    for (LayoverRegistry.LayoverCandidate candidate : readyCandidates) {
      if (runtimeDispatchService.dispatchLayover(candidate, serviceTicket)) {
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
          return new PendingLayoverEntry(ticket, addedAt);
        });
  }

  /**
   * 尝试从 Depot 直接发车（降级发车路径）。
   *
   * <p>当 RETURN 票据等待 Layover 超时后，调用此方法尝试从 Depot 直接补发列车。
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
    Optional<org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestContext>
        ctxOpt =
            builder.buildContextFromNodes(
                trainName, Optional.ofNullable(route.id()), route.waypoints(), 0, now, 100);
    if (ctxOpt.isEmpty()) {
      requeue(ticket, now, "fallback-occupancy-context-failed");
      return false;
    }
    org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestContext ctx =
        ctxOpt.get();
    OccupancyRequest request = builder.applyDepotLookover(ctx);
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
    }
    runtimeDispatchService.refreshSignal(group);
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

  private Optional<java.util.UUID> resolveDepotWorldId(
      SpawnService service, Optional<String> depotOverride) {
    if (service == null) {
      return Optional.empty();
    }
    String depotSpec = depotOverride.filter(s -> !s.isBlank()).orElseGet(service::depotNodeId);
    if (depotSpec == null || depotSpec.isBlank()) {
      return Optional.empty();
    }

    // 检查是否是 DYNAMIC depot
    if (SpawnDirectiveParser.isDynamicTarget(depotSpec)) {
      // 解析 DYNAMIC spec，查找任意匹配轨道的世界
      return resolveDynamicDepotWorldId(depotSpec);
    }

    // 普通 depot：精确匹配 nodeId
    return signNodeRegistry.snapshotInfos().values().stream()
        .filter(info -> info != null && info.definition() != null)
        .filter(info -> info.definition().nodeType() == NodeType.DEPOT)
        .filter(info -> depotSpec.equalsIgnoreCase(info.definition().nodeId().value()))
        .map(SignNodeRegistry.SignNodeInfo::worldId)
        .findFirst();
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
    // 解析 DYNAMIC:OP:D:DEPOT 或 DYNAMIC:OP:D:DEPOT:[1:3]
    // 格式：DYNAMIC:operatorCode:nodeType:nodeName[:range]
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
    String nodeType = parts[1].trim(); // "D" for depot
    String nodeName = parts[2].trim();

    // 构建 nodeId 前缀用于匹配
    String nodeIdPrefix = operatorCode + ":" + nodeType + ":" + nodeName + ":";

    // 查找任意匹配的 depot 节点
    return signNodeRegistry.snapshotInfos().values().stream()
        .filter(info -> info != null && info.definition() != null)
        .filter(info -> info.definition().nodeType() == NodeType.DEPOT)
        .filter(
            info -> {
              String nodeIdValue = info.definition().nodeId().value();
              return nodeIdValue != null
                  && nodeIdValue
                      .toUpperCase(java.util.Locale.ROOT)
                      .startsWith(nodeIdPrefix.toUpperCase(java.util.Locale.ROOT));
            })
        .map(SignNodeRegistry.SignNodeInfo::worldId)
        .findFirst();
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
