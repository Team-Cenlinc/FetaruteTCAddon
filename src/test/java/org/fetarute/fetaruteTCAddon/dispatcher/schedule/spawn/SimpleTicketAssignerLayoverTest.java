package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.mockito.ArgumentCaptor;

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

  @Test
  void tickTriesNextReadyLayoverCandidateWhenFirstCandidateBlocked() {
    UUID lineId = UUID.randomUUID();
    UUID routeId = UUID.randomUUID();
    SpawnTicket ticket = buildTicket(routeId, lineId, "R1", 0L);
    StorageProvider provider = mockProviderForRoutes(lineId, Map.of(routeId, "R1"), false);
    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.pollDueTickets(eq(provider), any())).thenReturn(List.of(ticket));

    LayoverRegistry.LayoverCandidate first =
        new LayoverRegistry.LayoverCandidate(
            "train-1", "A", NodeId.of("A"), Instant.now(), Map.of());
    LayoverRegistry.LayoverCandidate second =
        new LayoverRegistry.LayoverCandidate(
            "train-2", "A", NodeId.of("A"), Instant.now(), Map.of());
    LayoverRegistry layoverRegistry = mock(LayoverRegistry.class);
    when(layoverRegistry.findCandidates("A")).thenReturn(List.of(first, second));

    RuntimeDispatchService runtimeDispatchService = mock(RuntimeDispatchService.class);
    when(runtimeDispatchService.dispatchLayover(eq(first), any(ServiceTicket.class)))
        .thenReturn(false);
    when(runtimeDispatchService.dispatchLayover(eq(second), any(ServiceTicket.class)))
        .thenReturn(true);

    SimpleTicketAssigner assigner =
        new SimpleTicketAssigner(
            spawnManager,
            mock(DepotSpawner.class),
            mock(OccupancyManager.class),
            mock(RailGraphService.class),
            mockRouteDefinitions(Map.of(routeId, routeDefinition("OP:L1:R1"))),
            runtimeDispatchService,
            mockConfigManager(),
            mock(SignNodeRegistry.class),
            layoverRegistry,
            null,
            Duration.ofSeconds(1),
            1,
            10);

    assigner.tick(provider, Instant.now());

    verify(runtimeDispatchService).dispatchLayover(eq(first), any(ServiceTicket.class));
    verify(runtimeDispatchService).dispatchLayover(eq(second), any(ServiceTicket.class));
    verify(spawnManager).complete(ticket);
    assertEquals(1L, assigner.snapshotDiagnostics().success());
  }

  @Test
  void onLayoverRegisteredRotatesPendingRoutesWithinSameLineAndTerminal() {
    UUID lineId = UUID.randomUUID();
    UUID route1Id = UUID.randomUUID();
    UUID route2Id = UUID.randomUUID();
    SpawnTicket route1Ticket = buildTicket(route1Id, lineId, "R1", 0L);
    SpawnTicket route2Ticket = buildTicket(route2Id, lineId, "R2", 1L);
    StorageProvider provider =
        mockProviderForRoutes(lineId, Map.of(route1Id, "R1", route2Id, "R2"), false);
    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.pollDueTickets(eq(provider), any()))
        .thenReturn(List.of(route1Ticket, route2Ticket));

    LayoverRegistry.LayoverCandidate candidate =
        new LayoverRegistry.LayoverCandidate(
            "train-1", "A", NodeId.of("A"), Instant.now(), Map.of());
    LayoverRegistry layoverRegistry = mock(LayoverRegistry.class);
    when(layoverRegistry.findCandidates("A"))
        .thenReturn(
            List.of(), // tick: route1 入 pending
            List.of(), // tick: route2 入 pending
            List.of(candidate), // 回调1: 第一次尝试
            List.of(candidate), // 回调1: 第二次尝试
            List.of(candidate), // 回调2: 第一次尝试（应轮转到 R2）
            List.of(candidate)); // 回调2: 第二次尝试

    RuntimeDispatchService runtimeDispatchService = mock(RuntimeDispatchService.class);
    when(runtimeDispatchService.dispatchLayover(eq(candidate), any(ServiceTicket.class)))
        .thenReturn(false);

    SimpleTicketAssigner assigner =
        new SimpleTicketAssigner(
            spawnManager,
            mock(DepotSpawner.class),
            mock(OccupancyManager.class),
            mock(RailGraphService.class),
            mockRouteDefinitions(
                Map.of(
                    route1Id, routeDefinition("OP:L1:R1"),
                    route2Id, routeDefinition("OP:L1:R2"))),
            runtimeDispatchService,
            mockConfigManager(),
            mock(SignNodeRegistry.class),
            layoverRegistry,
            null,
            Duration.ofSeconds(1),
            2,
            10);

    assigner.tick(provider, Instant.now());
    assertEquals(2, assigner.snapshotPendingTickets().size(), "两张票据应进入 pending");

    assigner.onLayoverRegistered(candidate);
    assigner.onLayoverRegistered(candidate);

    ArgumentCaptor<ServiceTicket> ticketCaptor = ArgumentCaptor.forClass(ServiceTicket.class);
    verify(runtimeDispatchService, times(4)).dispatchLayover(eq(candidate), ticketCaptor.capture());
    List<ServiceTicket> attempted = ticketCaptor.getAllValues();
    assertEquals(route1Id, attempted.get(0).routeId(), "第一轮应先尝试 R1");
    assertEquals(route2Id, attempted.get(1).routeId(), "第一轮第二次应尝试 R2");
    assertEquals(route2Id, attempted.get(2).routeId(), "第二轮应从 R2 开始（轮转）");
    assertEquals(route1Id, attempted.get(3).routeId(), "第二轮第二次应回到 R1");
    assertTrue(assigner.snapshotPendingTickets().size() >= 2, "派发失败后 pending 不应被误删");
  }

  @Test
  void tickCompletesPendingTicketWhenRouteDefinitionDisappears() {
    UUID lineId = UUID.randomUUID();
    UUID routeId = UUID.randomUUID();
    SpawnTicket ticket = buildTicket(routeId, lineId, "R1", 0L);
    StorageProvider provider = mockProviderForRoutes(lineId, Map.of(routeId, "R1"), false);
    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.pollDueTickets(eq(provider), any())).thenReturn(List.of(ticket), List.of());

    LayoverRegistry layoverRegistry = mock(LayoverRegistry.class);
    when(layoverRegistry.findCandidates("A")).thenReturn(List.of());

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findById(routeId))
        .thenReturn(Optional.of(routeDefinition("OP:L1:R1")), Optional.empty());

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
            layoverRegistry,
            null,
            Duration.ofSeconds(1),
            1,
            10);

    Instant t0 = Instant.now();
    assigner.tick(provider, t0);
    assertEquals(1, assigner.snapshotPendingTickets().size(), "首轮应进入 pending");

    assigner.tick(provider, t0.plusSeconds(1));
    assertEquals(0, assigner.snapshotPendingTickets().size(), "route 丢失后应从 pending 清理");
    verify(spawnManager).complete(ticket);
  }

  private static SpawnTicket buildTicket(UUID routeId) {
    return buildTicket(routeId, UUID.randomUUID(), "R1", 0L);
  }

  private static SpawnTicket buildTicket(
      UUID routeId, UUID lineId, String routeCode, long sequence) {
    SpawnService service =
        new SpawnService(
            new SpawnServiceKey(routeId),
            UUID.randomUUID(),
            "COMP",
            UUID.randomUUID(),
            "OP",
            lineId,
            "L1",
            routeId,
            routeCode,
            Duration.ofSeconds(60),
            "SURN:D:DEPOT:1");
    Instant now = Instant.now();
    return new SpawnTicket(
        UUID.randomUUID(), service, now, now, 0, sequence, Optional.empty(), Optional.empty());
  }

  private static StorageProvider mockProvider(UUID routeId, boolean withCret) {
    return mockProviderForRoutes(UUID.randomUUID(), Map.of(routeId, "R1"), withCret);
  }

  private static StorageProvider mockProviderForRoutes(
      UUID lineId, Map<UUID, String> routes, boolean withCret) {
    StorageProvider provider = mock(StorageProvider.class);
    LineRepository lineRepository = mock(LineRepository.class);
    RouteRepository routeRepository = mock(RouteRepository.class);
    RouteStopRepository stopRepository = mock(RouteStopRepository.class);
    when(provider.lines()).thenReturn(lineRepository);
    when(provider.routes()).thenReturn(routeRepository);
    when(provider.routeStops()).thenReturn(stopRepository);

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

    for (Map.Entry<UUID, String> entry : routes.entrySet()) {
      UUID routeId = entry.getKey();
      String routeCode = entry.getValue();
      Route route =
          new Route(
              routeId,
              routeCode,
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
    }
    return provider;
  }

  private static RouteDefinitionCache mockRouteDefinitions(UUID routeId) {
    return mockRouteDefinitions(Map.of(routeId, routeDefinition("OP:L1:R1")));
  }

  private static RouteDefinitionCache mockRouteDefinitions(Map<UUID, RouteDefinition> definitions) {
    RouteDefinitionCache cache = mock(RouteDefinitionCache.class);
    for (Map.Entry<UUID, RouteDefinition> entry : definitions.entrySet()) {
      when(cache.findById(entry.getKey())).thenReturn(Optional.of(entry.getValue()));
    }
    return cache;
  }

  private static RouteDefinition routeDefinition(String routeValue) {
    return new RouteDefinition(
        RouteId.of(routeValue), List.of(NodeId.of("A"), NodeId.of("B")), Optional.empty());
  }

  private static ConfigManager mockConfigManager() {
    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView view = mock(ConfigManager.ConfigView.class);
    ConfigManager.SpawnSettings spawnSettings = mock(ConfigManager.SpawnSettings.class);
    when(configManager.current()).thenReturn(view);
    when(view.spawnSettings()).thenReturn(spawnSettings);
    when(spawnSettings.layoverFallbackMultiplier()).thenReturn(0.0);
    return configManager;
  }

  @Test
  void tickRefreshesExpiredPendingLayoverTicketsWithoutDropping() {
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

    // 三次 tick：时间超过刷新窗口，票据应保留并刷新等待窗口（不直接丢弃）
    Instant t2 = t0.plusSeconds(301);
    assigner.tick(provider, t2);
    assertEquals(1, assigner.snapshotPendingTickets().size(), "超时后应仅刷新窗口，不应丢票据");
    verify(spawnManager, never()).complete(ticket);
  }

  @Test
  void tickKeepsOriginalPendingTimestampWhenRetryBlockedBySignal() {
    UUID routeId = UUID.randomUUID();
    SpawnTicket ticket = buildTicket(routeId);
    StorageProvider provider = mockProvider(routeId, false);
    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.pollDueTickets(eq(provider), any()))
        .thenReturn(List.of(ticket), List.of(), List.of());

    LayoverRegistry.LayoverCandidate candidate =
        new LayoverRegistry.LayoverCandidate(
            "train-1", "A", NodeId.of("A"), Instant.now(), Map.of());
    LayoverRegistry layoverRegistry = mock(LayoverRegistry.class);
    when(layoverRegistry.findCandidates("A")).thenReturn(List.of(candidate));

    RuntimeDispatchService runtimeDispatchService = mock(RuntimeDispatchService.class);
    when(runtimeDispatchService.dispatchLayover(eq(candidate), any(ServiceTicket.class)))
        .thenReturn(false);

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

    Instant t0 = Instant.now();
    assigner.tick(provider, t0);
    assertEquals(1, assigner.snapshotPendingTickets().size(), "首次阻塞后应进入 pending");

    Instant t1 = t0.plusSeconds(100);
    assigner.tick(provider, t1);
    assertEquals(1, assigner.snapshotPendingTickets().size(), "中途重试受阻不应重置超时计时");

    Instant t2 = t0.plusSeconds(301);
    assigner.tick(provider, t2);
    assertEquals(1, assigner.snapshotPendingTickets().size(), "超过刷新窗口后仍应保留 pending");
    verify(spawnManager, never()).complete(ticket);
  }
}
