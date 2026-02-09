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
import org.bukkit.util.Vector;
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
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.ServiceTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
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
        .thenReturn(List.of()) // tick: route1 入 pending
        .thenReturn(List.of()) // tick: route2 入 pending
        .thenReturn(List.of(candidate)) // 回调1: 第一次尝试
        .thenReturn(List.of(candidate)) // 回调1: 第二次尝试
        .thenReturn(List.of(candidate)) // 回调2: 第一次尝试（应轮转到 R2）
        .thenReturn(List.of(candidate)); // 回调2: 第二次尝试

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
  void tickRotatesImmediateLayoverDispatchOrderWithinSameLineAndTerminal() {
    UUID lineId = UUID.randomUUID();
    UUID route1Id = UUID.randomUUID();
    UUID route2Id = UUID.randomUUID();
    SpawnTicket route1TicketA = buildTicket(route1Id, lineId, "R1", 0L);
    SpawnTicket route2TicketA = buildTicket(route2Id, lineId, "R2", 1L);
    SpawnTicket route1TicketB = buildTicket(route1Id, lineId, "R1", 2L);
    SpawnTicket route2TicketB = buildTicket(route2Id, lineId, "R2", 3L);
    StorageProvider provider =
        mockProviderForRoutes(lineId, Map.of(route1Id, "R1", route2Id, "R2"), false);
    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.pollDueTickets(eq(provider), any()))
        .thenReturn(List.of(route1TicketA, route2TicketA))
        .thenReturn(List.of(route1TicketB, route2TicketB));

    LayoverRegistry.LayoverCandidate candidate =
        new LayoverRegistry.LayoverCandidate(
            "train-1", "A", NodeId.of("A"), Instant.now(), Map.of());
    LayoverRegistry layoverRegistry = mock(LayoverRegistry.class);
    when(layoverRegistry.findCandidates("A"))
        .thenReturn(List.of(candidate))
        .thenReturn(List.of(candidate))
        .thenReturn(List.of(candidate))
        .thenReturn(List.of(candidate));

    RuntimeDispatchService runtimeDispatchService = mock(RuntimeDispatchService.class);
    when(runtimeDispatchService.dispatchLayover(eq(candidate), any(ServiceTicket.class)))
        .thenReturn(true);

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
    assigner.tick(provider, Instant.now().plusSeconds(1));

    ArgumentCaptor<ServiceTicket> ticketCaptor = ArgumentCaptor.forClass(ServiceTicket.class);
    verify(runtimeDispatchService, times(4)).dispatchLayover(eq(candidate), ticketCaptor.capture());
    List<ServiceTicket> attempted = ticketCaptor.getAllValues();
    assertEquals(route1Id, attempted.get(0).routeId(), "第一轮应先尝试 R1");
    assertEquals(route2Id, attempted.get(1).routeId(), "第一轮第二次应尝试 R2");
    assertEquals(route2Id, attempted.get(2).routeId(), "第二轮应从 R2 开始（轮转）");
    assertEquals(route1Id, attempted.get(3).routeId(), "第二轮第二次应回到 R1");
  }

  @Test
  void onLayoverRegisteredMatchesPendingTerminalByStationKeyForDynamicPlaceholder() {
    UUID lineId = UUID.randomUUID();
    UUID routeId = UUID.randomUUID();
    SpawnTicket ticket = buildTicket(routeId, lineId, "R1", 0L);
    StorageProvider provider = mockProviderForRoutes(lineId, Map.of(routeId, "R1"), false);
    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.pollDueTickets(eq(provider), any())).thenReturn(List.of(ticket));

    LayoverRegistry.LayoverCandidate candidate =
        new LayoverRegistry.LayoverCandidate(
            "train-1", "surc:s:ppk:2", NodeId.of("SURC:S:PPK:2"), Instant.now(), Map.of());
    LayoverRegistry layoverRegistry = mock(LayoverRegistry.class);
    when(layoverRegistry.findCandidates("SURC:S:PPK:1"))
        .thenReturn(List.of()) // tick: 首次进入 pending
        .thenReturn(List.of(candidate)); // onLayoverRegistered: 触发 pending 派发

    RuntimeDispatchService runtimeDispatchService = mock(RuntimeDispatchService.class);
    when(runtimeDispatchService.dispatchLayover(eq(candidate), any(ServiceTicket.class)))
        .thenReturn(true);

    SimpleTicketAssigner assigner =
        new SimpleTicketAssigner(
            spawnManager,
            mock(DepotSpawner.class),
            mock(OccupancyManager.class),
            mock(RailGraphService.class),
            mockRouteDefinitions(Map.of(routeId, routeDefinition("SURC:MT:R1", "SURC:S:PPK:1"))),
            runtimeDispatchService,
            mockConfigManager(),
            mock(SignNodeRegistry.class),
            layoverRegistry,
            null,
            Duration.ofSeconds(1),
            1,
            10);

    assigner.tick(provider, Instant.now());
    assertEquals(1, assigner.snapshotPendingTickets().size(), "首次应进入 pending");

    assigner.onLayoverRegistered(candidate);

    verify(runtimeDispatchService).dispatchLayover(eq(candidate), any(ServiceTicket.class));
    verify(spawnManager).complete(ticket);
    assertEquals(0, assigner.snapshotPendingTickets().size(), "匹配成功后应从 pending 移除");
  }

  @Test
  void tickCompletesPendingTicketWhenRouteDefinitionDisappears() {
    UUID lineId = UUID.randomUUID();
    UUID routeId = UUID.randomUUID();
    SpawnTicket ticket = buildTicket(routeId, lineId, "R1", 0L);
    StorageProvider provider = mockProviderForRoutes(lineId, Map.of(routeId, "R1"), false);
    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.pollDueTickets(eq(provider), any()))
        .thenReturn(List.of(ticket))
        .thenReturn(List.of());

    LayoverRegistry layoverRegistry = mock(LayoverRegistry.class);
    when(layoverRegistry.findCandidates("A")).thenReturn(List.of());

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findById(routeId))
        .thenReturn(Optional.of(routeDefinition("OP:L1:R1")))
        .thenReturn(Optional.empty());

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

  private static StorageProvider mockProviderForRouteOperation(
      UUID lineId,
      UUID routeId,
      String routeCode,
      RouteOperationType operationType,
      String firstNode,
      String secondNode) {
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

    Route route =
        new Route(
            routeId,
            routeCode,
            lineId,
            "Route",
            Optional.empty(),
            RoutePatternType.LOCAL,
            operationType,
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
            Optional.of(firstNode),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());
    RouteStop second =
        new RouteStop(
            routeId,
            1,
            Optional.empty(),
            Optional.of(secondNode),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());
    when(stopRepository.listByRoute(routeId)).thenReturn(List.of(first, second));
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
    return routeDefinition(routeValue, "A");
  }

  private static RouteDefinition routeDefinition(String routeValue, String startNode) {
    return new RouteDefinition(
        RouteId.of(routeValue), List.of(NodeId.of(startNode), NodeId.of("B")), Optional.empty());
  }

  private static ConfigManager mockConfigManager() {
    return mockConfigManager(0.0);
  }

  private static ConfigManager mockConfigManager(double layoverFallbackMultiplier) {
    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView view = mock(ConfigManager.ConfigView.class);
    ConfigManager.SpawnSettings spawnSettings = mock(ConfigManager.SpawnSettings.class);
    ConfigManager.RuntimeSettings runtimeSettings = mock(ConfigManager.RuntimeSettings.class);
    when(configManager.current()).thenReturn(view);
    when(view.spawnSettings()).thenReturn(spawnSettings);
    when(view.runtimeSettings()).thenReturn(runtimeSettings);
    when(spawnSettings.layoverFallbackMultiplier()).thenReturn(layoverFallbackMultiplier);
    when(runtimeSettings.lookaheadEdges()).thenReturn(2);
    when(runtimeSettings.minClearEdges()).thenReturn(0);
    when(runtimeSettings.rearGuardEdges()).thenReturn(0);
    when(runtimeSettings.switcherZoneEdges()).thenReturn(1);
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
        .thenReturn(List.of(ticket))
        .thenReturn(List.of())
        .thenReturn(List.of());

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

  @Test
  void tickSkipsFallbackWhenReturnRouteStartsFromStation() {
    UUID lineId = UUID.randomUUID();
    UUID routeId = UUID.randomUUID();
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
            "RET-S",
            Duration.ofSeconds(60),
            "DYNAMIC:SURC:S:PPK");
    Instant now = Instant.now();
    SpawnTicket ticket =
        new SpawnTicket(
            UUID.randomUUID(), service, now, now, 0, 0L, Optional.empty(), Optional.empty());

    StorageProvider provider =
        mockProviderForRouteOperation(
            lineId, routeId, "RET-S", RouteOperationType.RETURN, "SURC:S:PPK:1", "SURC:S:RVS:2");
    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.pollDueTickets(eq(provider), any()))
        .thenReturn(List.of(ticket))
        .thenReturn(List.of());

    LayoverRegistry layoverRegistry = mock(LayoverRegistry.class);
    when(layoverRegistry.findCandidates("SURC:S:PPK:1")).thenReturn(List.of());

    DepotSpawner depotSpawner = mock(DepotSpawner.class);
    SimpleTicketAssigner assigner =
        new SimpleTicketAssigner(
            spawnManager,
            depotSpawner,
            mock(OccupancyManager.class),
            mock(RailGraphService.class),
            mockRouteDefinitions(Map.of(routeId, routeDefinition("SURC:MT:RET-S", "SURC:S:PPK:1"))),
            mock(RuntimeDispatchService.class),
            mockConfigManager(1.0),
            mock(SignNodeRegistry.class),
            layoverRegistry,
            null,
            Duration.ofSeconds(1),
            1,
            10);

    Instant t0 = Instant.now();
    assigner.tick(provider, t0);
    assertEquals(1, assigner.snapshotPendingTickets().size(), "首轮应进入 pending");

    assigner.tick(provider, t0.plusSeconds(70));
    assertEquals(1, assigner.snapshotPendingTickets().size(), "非 depot 首站应继续等待 Layover 复用");
    verify(spawnManager, never()).requeue(any());
    verify(depotSpawner, never()).spawn(any(), any(), any(), any());
  }

  @Test
  void tickDepotSpawnRequestIncludesDepotLookoverWhenRouteStartsAtStation() {
    UUID routeId = UUID.randomUUID();
    SpawnTicket ticket = buildTicket(routeId);
    StorageProvider provider = mockProvider(routeId, true);
    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.pollDueTickets(eq(provider), any())).thenReturn(List.of(ticket));

    UUID worldId = UUID.randomUUID();
    NodeId depotNode = NodeId.of("SURN:D:DEPOT:1");
    NodeId throatNode = NodeId.of("SURN:D:DEPOT:1:001");
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
    RailNode depot =
        new SignRailNode(
            depotNode,
            NodeType.DEPOT,
            new Vector(-10.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode throat =
        new SignRailNode(
            throatNode,
            NodeType.SWITCHER,
            new Vector(0.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode a =
        new SignRailNode(
            nodeA,
            NodeType.WAYPOINT,
            new Vector(10.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode b =
        new SignRailNode(
            nodeB,
            NodeType.WAYPOINT,
            new Vector(20.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    EdgeId edgeDepotThroat = EdgeId.undirected(depotNode, throatNode);
    EdgeId edgeThroatA = EdgeId.undirected(throatNode, nodeA);
    EdgeId edgeAB = EdgeId.undirected(nodeA, nodeB);
    RailEdge depotThroat =
        new RailEdge(edgeDepotThroat, depotNode, throatNode, 10, 8.0, true, Optional.empty());
    RailEdge throatA =
        new RailEdge(edgeThroatA, throatNode, nodeA, 10, 8.0, true, Optional.empty());
    RailEdge ab = new RailEdge(edgeAB, nodeA, nodeB, 10, 8.0, true, Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(depotNode, depot, throatNode, throat, nodeA, a, nodeB, b),
            Map.of(edgeDepotThroat, depotThroat, edgeThroatA, throatA, edgeAB, ab),
            java.util.Set.of());
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    ArgumentCaptor<OccupancyRequest> requestCaptor =
        ArgumentCaptor.forClass(OccupancyRequest.class);
    when(occupancyManager.acquire(requestCaptor.capture()))
        .thenReturn(new OccupancyDecision(false, Instant.now(), SignalAspect.STOP, List.of()));

    SignNodeRegistry signNodeRegistry = mock(SignNodeRegistry.class);
    SignNodeDefinition depotDefinition =
        new SignNodeDefinition(depotNode, NodeType.DEPOT, Optional.empty(), Optional.empty());
    SignNodeRegistry.SignNodeInfo depotInfo =
        new SignNodeRegistry.SignNodeInfo(depotDefinition, worldId, "TestWorld", 0, 64, 0);
    when(signNodeRegistry.snapshotInfos()).thenReturn(Map.of("depot", depotInfo));

    DepotSpawner depotSpawner = mock(DepotSpawner.class);
    RuntimeDispatchService runtimeDispatchService = mock(RuntimeDispatchService.class);
    LayoverRegistry layoverRegistry = mock(LayoverRegistry.class);

    SimpleTicketAssigner assigner =
        new SimpleTicketAssigner(
            spawnManager,
            depotSpawner,
            occupancyManager,
            railGraphService,
            mockRouteDefinitions(routeId),
            runtimeDispatchService,
            mockConfigManager(),
            signNodeRegistry,
            layoverRegistry,
            null,
            Duration.ofSeconds(1),
            1,
            10);

    assigner.tick(provider, Instant.now());

    OccupancyRequest captured = requestCaptor.getValue();
    assertTrue(captured.resourceList().contains(OccupancyResource.forEdge(edgeDepotThroat)));
    verify(depotSpawner, never()).spawn(any(), any(), any(), any());
  }
}
