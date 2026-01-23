package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.repository.RouteRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RouteStopRepository;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.ServiceTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.Test;

class SimpleTicketAssignerLayoverTest {

  @Test
  void tickMarksPendingWhenRouteStartsWithStopAndNoLayoverCandidate() {
    UUID routeId = UUID.randomUUID();
    SpawnTicket ticket = buildTicket(routeId);
    StorageProvider provider = mockProvider(routeId, false);
    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.pollDueTickets(eq(provider), any())).thenReturn(List.of(ticket));

    LayoverRegistry layoverRegistry = mock(LayoverRegistry.class);
    when(layoverRegistry.findCandidates("A")).thenReturn(List.of());

    SimpleTicketAssigner assigner =
        new SimpleTicketAssigner(
            spawnManager,
            mock(DepotSpawner.class),
            mock(OccupancyManager.class),
            mock(RailGraphService.class),
            mockRouteDefinitions(routeId),
            mock(RuntimeDispatchService.class),
            mockConfigManager(),
            mock(SignNodeRegistry.class),
            layoverRegistry,
            null,
            Duration.ofSeconds(1),
            1);

    assigner.tick(provider, Instant.now());

    verify(spawnManager, never()).requeue(any());
    verify(spawnManager, never()).complete(any());
  }

  @Test
  void tickCompletesWhenLayoverDispatchSucceeds() {
    UUID routeId = UUID.randomUUID();
    SpawnTicket ticket = buildTicket(routeId);
    StorageProvider provider = mockProvider(routeId, false);
    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.pollDueTickets(eq(provider), any())).thenReturn(List.of(ticket));

    LayoverRegistry.LayoverCandidate candidate =
        new LayoverRegistry.LayoverCandidate(
            "train-1", "A", NodeId.of("A"), Instant.now(), Map.of());
    LayoverRegistry layoverRegistry = mock(LayoverRegistry.class);
    when(layoverRegistry.findCandidates("A")).thenReturn(List.of(candidate));

    RuntimeDispatchService runtimeDispatchService = mock(RuntimeDispatchService.class);
    when(runtimeDispatchService.dispatchLayover(eq(candidate), any(ServiceTicket.class)))
        .thenReturn(true);

    SimpleTicketAssigner assigner =
        new SimpleTicketAssigner(
            spawnManager,
            mock(DepotSpawner.class),
            mock(OccupancyManager.class),
            mock(RailGraphService.class),
            mockRouteDefinitions(routeId),
            runtimeDispatchService,
            mockConfigManager(),
            mock(SignNodeRegistry.class),
            layoverRegistry,
            null,
            Duration.ofSeconds(1),
            1);

    assigner.tick(provider, Instant.now());

    verify(spawnManager).complete(ticket);
    verify(spawnManager, never()).requeue(any());
    assertEquals(1L, assigner.snapshotDiagnostics().success());
    assertEquals(0L, assigner.snapshotDiagnostics().retries());
  }

  @Test
  void tickRequeueIncrementsDiagnostics() {
    UUID routeId = UUID.randomUUID();
    SpawnTicket ticket = buildTicket(routeId);
    StorageProvider provider = mockProvider(routeId, false);
    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.pollDueTickets(eq(provider), any())).thenReturn(List.of(ticket));

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findById(routeId)).thenReturn(Optional.empty());

    SimpleTicketAssigner assigner =
        new SimpleTicketAssigner(
            spawnManager,
            mock(DepotSpawner.class),
            mock(OccupancyManager.class),
            mock(RailGraphService.class),
            routeDefinitions,
            mock(RuntimeDispatchService.class),
            mockConfigManager(),
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            null,
            Duration.ofSeconds(1),
            1);

    assigner.tick(provider, Instant.now());

    assertEquals(0L, assigner.snapshotDiagnostics().success());
    assertEquals(1L, assigner.snapshotDiagnostics().retries());
    assertEquals(
        1L, assigner.snapshotDiagnostics().requeueByError().getOrDefault("route-not-found", 0L));
  }

  private static SpawnTicket buildTicket(UUID routeId) {
    SpawnService service =
        new SpawnService(
            new SpawnServiceKey(routeId),
            UUID.randomUUID(),
            "COMP",
            UUID.randomUUID(),
            "OP",
            UUID.randomUUID(),
            "L1",
            routeId,
            "R1",
            Duration.ofSeconds(60),
            "SURN:D:DEPOT:1");
    Instant now = Instant.now();
    return new SpawnTicket(UUID.randomUUID(), service, now, now, 0, Optional.empty());
  }

  private static StorageProvider mockProvider(UUID routeId, boolean withCret) {
    StorageProvider provider = mock(StorageProvider.class);
    RouteRepository routeRepository = mock(RouteRepository.class);
    RouteStopRepository stopRepository = mock(RouteStopRepository.class);
    when(provider.routes()).thenReturn(routeRepository);
    when(provider.routeStops()).thenReturn(stopRepository);

    Route route =
        new Route(
            routeId,
            "R1",
            UUID.randomUUID(),
            "Route",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            Instant.now(),
            Instant.now());
    when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));

    RouteStop first =
        new RouteStop(
            routeId,
            0,
            Optional.empty(),
            Optional.of("A"),
            Optional.empty(),
            RouteStopPassType.STOP,
            withCret ? Optional.of("CRET SURN:D:DEPOT:1") : Optional.empty());
    RouteStop second =
        new RouteStop(
            routeId,
            1,
            Optional.empty(),
            Optional.of("B"),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());
    when(stopRepository.listByRoute(routeId)).thenReturn(List.of(first, second));
    return provider;
  }

  private static RouteDefinitionCache mockRouteDefinitions(UUID routeId) {
    RouteDefinitionCache cache = mock(RouteDefinitionCache.class);
    RouteDefinition definition =
        new RouteDefinition(
            RouteId.of("OP:L1:R1"), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
    when(cache.findById(routeId)).thenReturn(Optional.of(definition));
    return cache;
  }

  private static ConfigManager mockConfigManager() {
    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView view = mock(ConfigManager.ConfigView.class);
    when(configManager.current()).thenReturn(view);
    return configManager;
  }
}
