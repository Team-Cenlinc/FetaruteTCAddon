package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainNameFormatter;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestBuilder;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 简单票据分配器：只实现“从 Depot 生成列车”。
 *
 * <p>后续可扩展：优先把票据分配给 layover 列车；不足时再向 depot 发 SpawnRequest。
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

  private final Duration retryDelay;
  private final int maxSpawnPerTick;

  public SimpleTicketAssigner(
      SpawnManager spawnManager,
      DepotSpawner depotSpawner,
      OccupancyManager occupancyManager,
      RailGraphService railGraphService,
      RouteDefinitionCache routeDefinitions,
      RuntimeDispatchService runtimeDispatchService,
      ConfigManager configManager,
      SignNodeRegistry signNodeRegistry,
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
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
    this.retryDelay = retryDelay == null ? Duration.ofSeconds(2) : retryDelay;
    this.maxSpawnPerTick = Math.max(1, maxSpawnPerTick);
  }

  @Override
  public void tick(StorageProvider provider, Instant now) {
    if (provider == null || now == null) {
      return;
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
    String destName = resolveDestinationName(provider, routeEntity).orElse(routeEntity.name());
    String trainName =
        TrainNameFormatter.buildTrainName(
            service.operatorCode(),
            service.lineCode(),
            routeEntity.patternType(),
            destName,
            ticket.id());

    Optional<RouteDefinition> routeOpt = routeDefinitions.findById(service.routeId());
    if (routeOpt.isEmpty()) {
      requeue(ticket, now, "route-not-found");
      return false;
    }
    RouteDefinition route = routeOpt.get();

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
            runtime.switcherZoneEdges());
    Optional<org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestContext>
        ctxOpt =
            builder.buildContextFromNodes(
                trainName, Optional.ofNullable(route.id()), route.waypoints(), 0, now);
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

  private void requeue(SpawnTicket ticket, Instant now, String error) {
    Instant next = now.plus(retryDelay);
    SpawnTicket retry = ticket.withRetry(next, error);
    spawnManager.requeue(retry);
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
