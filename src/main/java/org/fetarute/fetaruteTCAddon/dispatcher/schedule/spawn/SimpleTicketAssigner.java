package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
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
  private final java.util.Map<java.util.UUID, SpawnTicket> pendingLayoverTickets =
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
      int maxSpawnPerTick) {
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
    return List.copyOf(pendingLayoverTickets.values());
  }

  @Override
  public void tick(StorageProvider provider, Instant now) {
    if (provider == null || now == null) {
      return;
    }
    if (!pendingLayoverTickets.isEmpty()) {
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

  private boolean trySpawn(StorageProvider provider, Instant now, SpawnTicket ticket) {
    SpawnService service = ticket.service();
    Optional<org.fetarute.fetaruteTCAddon.company.model.Route> routeEntityOpt =
        provider.routes().findById(service.routeId());
    if (routeEntityOpt.isEmpty()) {
      requeue(ticket, now, "route-not-found");
      return false;
    }
    org.fetarute.fetaruteTCAddon.company.model.Route routeEntity = routeEntityOpt.get();

    Optional<RouteDefinition> routeOpt = routeDefinitions.findById(service.routeId());
    if (routeOpt.isEmpty()) {
      requeue(ticket, now, "route-not-found");
      return false;
    }
    RouteDefinition route = routeOpt.get();

    if (routeEntity.operationType() == RouteOperationType.RETURN) {
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

    String destName = resolveDestinationName(provider, routeEntity).orElse(routeEntity.name());
    String trainName =
        TrainNameFormatter.buildTrainName(
            service.operatorCode(),
            service.lineCode(),
            routeEntity.patternType(),
            destName,
            ticket.id());

    // 线路信息已在前序步骤解析完成

    Optional<java.util.UUID> worldIdOpt = resolveDepotWorldId(service);
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
      groupOpt = depotSpawner.spawn(provider, ticket, trainName, now);
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
    spawnManager.complete(ticket);
    spawnSuccess.increment();
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
            + service.depotNodeId());
    return true;
  }

  private void tryDispatchPendingLayover(Instant now, Optional<String> terminalFilter) {
    if (pendingLayoverTickets.isEmpty()) {
      return;
    }
    java.util.List<SpawnTicket> snapshot =
        new java.util.ArrayList<>(pendingLayoverTickets.values());
    for (SpawnTicket ticket : snapshot) {
      if (ticket == null) {
        continue;
      }
      SpawnService service = ticket.service();
      Optional<RouteDefinition> routeOpt = routeDefinitions.findById(service.routeId());
      if (routeOpt.isEmpty()) {
        pendingLayoverTickets.remove(ticket.id());
        continue;
      }
      RouteDefinition route = routeOpt.get();
      if (terminalFilter.isPresent()) {
        String startNodeVal = route.waypoints().get(0).value();
        if (!terminalFilter.get().equalsIgnoreCase(startNodeVal)) {
          continue;
        }
      }
      if (tryReuseLayover(ticket, service, route, now, true)) {
        pendingLayoverTickets.remove(ticket.id());
      }
    }
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
        pendingLayoverTickets.put(ticket.id(), ticket);
        debugLogger.accept("Layover 复用等待: route=" + service.routeCode() + " start=" + startNodeVal);
      }
      return false;
    }
    LayoverRegistry.LayoverCandidate candidate = candidates.get(0);
    ServiceTicket serviceTicket =
        new ServiceTicket(
            ticket.id().toString(),
            ticket.scheduledTime(),
            service.routeId(),
            startNodeVal,
            0,
            ServiceTicket.TicketMode.OPERATION);
    if (runtimeDispatchService.dispatchLayover(candidate, serviceTicket)) {
      spawnManager.complete(ticket);
      spawnSuccess.increment();
      pendingLayoverTickets.remove(ticket.id());
      debugLogger.accept("Layover 复用成功: " + candidate.trainName() + " -> " + service.routeCode());
      return true;
    }
    pendingLayoverTickets.put(ticket.id(), ticket);
    debugLogger.accept(
        "Layover 复用受阻: route=" + service.routeCode() + " train=" + candidate.trainName());
    return false;
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

  private Optional<java.util.UUID> resolveDepotWorldId(SpawnService service) {
    if (service == null || service.depotNodeId().isBlank()) {
      return Optional.empty();
    }
    String depotSpec = service.depotNodeId();

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
