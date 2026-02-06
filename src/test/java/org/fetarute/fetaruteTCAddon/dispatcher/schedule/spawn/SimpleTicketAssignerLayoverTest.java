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
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.LineServiceType;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.repository.LineRepository;
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
            1,
            10);

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
            1,
            10);

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
            1,
            10);

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
    return new SpawnTicket(
        UUID.randomUUID(), service, now, now, 0, 0L, Optional.empty(), Optional.empty());
  }

  private static StorageProvider mockProvider(UUID routeId, boolean withCret) {
    StorageProvider provider = mock(StorageProvider.class);
    LineRepository lineRepository = mock(LineRepository.class);
    RouteRepository routeRepository = mock(RouteRepository.class);
    RouteStopRepository stopRepository = mock(RouteStopRepository.class);
    when(provider.lines()).thenReturn(lineRepository);
    when(provider.routes()).thenReturn(routeRepository);
    when(provider.routeStops()).thenReturn(stopRepository);

    UUID lineId = UUID.randomUUID();
    Line line =
        new Line(
            lineId,
            "L1",
            UUID.randomUUID(),
            "Line",
            Optional.empty(),
            LineServiceType.METRO,
            Optional.empty(),
            LineStatus.ACTIVE,
            Optional.of(60),
            Map.of(),
            Instant.now(),
            Instant.now());
    when(lineRepository.findById(lineId)).thenReturn(Optional.of(line));

    Route route =
        new Route(
            routeId,
            "R1",
            lineId,
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

  @Test
  void tickCleansUpExpiredPendingLayoverTickets() {
    UUID routeId = UUID.randomUUID();
    SpawnTicket ticket = buildTicket(routeId);
    StorageProvider provider = mockProvider(routeId, false);
    SpawnManager spawnManager = mock(SpawnManager.class);
    // 首次 tick：返回票据，无候选，票据进入 pending
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
            1,
            10);

    Instant t0 = Instant.now();
    assigner.tick(provider, t0);
    assertEquals(1, assigner.snapshotPendingTickets().size(), "票据应进入 pending 队列");

    // 二次 tick：时间未过期，票据仍保留
    when(spawnManager.pollDueTickets(eq(provider), any())).thenReturn(List.of());
    Instant t1 = t0.plusSeconds(100);
    assigner.tick(provider, t1);
    assertEquals(1, assigner.snapshotPendingTickets().size(), "未超时，票据仍保留");

    // 三次 tick：时间超过 300 秒（超时），票据应被清理
    Instant t2 = t0.plusSeconds(301);
    assigner.tick(provider, t2);
    assertEquals(0, assigner.snapshotPendingTickets().size(), "超时后票据应被清理");
    verify(spawnManager).complete(ticket);
  }
}
