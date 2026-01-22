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
    OccupancyRequest request = ctxOpt.get().request();
    OccupancyDecision decision = occupancyManager.canEnter(request);
    if (!decision.allowed()) {
      requeue(ticket, now, "gate-blocked:" + decision.signal());
      return false;
    }

    Optional<MinecartGroup> groupOpt = depotSpawner.spawn(provider, ticket, trainName, now);
    if (groupOpt.isEmpty()) {
      requeue(ticket, now, "spawn-failed");
      return false;
    }
    MinecartGroup group = groupOpt.get();
    if (group.getProperties() != null && route.waypoints().size() >= 2) {
      group.getProperties().clearDestinationRoute();
      group.getProperties().clearDestination();
      group.getProperties().setDestination(route.waypoints().get(1).value());
    }
    occupancyManager.acquire(request);
    runtimeDispatchService.refreshSignal(group);
    spawnManager.complete(ticket);
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
      pendingLayoverTickets.remove(ticket.id());
      debugLogger.accept("Layover 复用成功: " + candidate.trainName() + " -> " + service.routeCode());
      return true;
    }
    pendingLayoverTickets.put(ticket.id(), ticket);
    debugLogger.accept(
        "Layover 复用受阻: route=" + service.routeCode() + " train=" + candidate.trainName());
    return false;
  }

  private void requeue(SpawnTicket ticket, Instant now, String error) {
    if (ticket == null) {
      return;
    }
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
  }

  private Optional<java.util.UUID> resolveDepotWorldId(SpawnService service) {
    if (service == null || service.depotNodeId().isBlank()) {
      return Optional.empty();
    }
    return signNodeRegistry.snapshotInfos().values().stream()
        .filter(info -> info != null && info.definition() != null)
        .filter(info -> info.definition().nodeType() == NodeType.DEPOT)
        .filter(info -> service.depotNodeId().equalsIgnoreCase(info.definition().nodeId().value()))
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
