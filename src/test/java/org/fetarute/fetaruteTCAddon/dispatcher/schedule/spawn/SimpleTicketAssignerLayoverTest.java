package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.repository.LineRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RouteRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RouteStopRepository;
import org.fetarute.fetaruteTCAddon.company.repository.StationRepository;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphConflictSupport;
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
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.ServiceTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyPreviewSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class SimpleTicketAssignerLayoverTest {

  private interface PreviewOccupancyManager extends OccupancyManager, OccupancyPreviewSupport {}

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
  void tickRequeuesTicketsBeyondPerTickCapacityWithoutAttemptIncrement() {
    UUID lineId = UUID.randomUUID();
    UUID firstRouteId = UUID.randomUUID();
    UUID secondRouteId = UUID.randomUUID();
    SpawnTicket firstTicket = buildTicket(firstRouteId, lineId, "R1", 0L);
    SpawnTicket secondTicket = buildTicket(secondRouteId, lineId, "R2", 1L);
    StorageProvider provider =
        mockProviderForRoutes(lineId, Map.of(firstRouteId, "R1", secondRouteId, "R2"), false);
    SpawnManager spawnManager = mock(SpawnManager.class);
    Instant now = Instant.parse("2026-02-01T00:00:00Z");
    when(spawnManager.pollDueTickets(eq(provider), eq(now)))
        .thenReturn(List.of(firstTicket, secondTicket));

    LayoverRegistry layoverRegistry = mock(LayoverRegistry.class);
    when(layoverRegistry.findCandidates("A")).thenReturn(List.of());

    SimpleTicketAssigner assigner =
        new SimpleTicketAssigner(
            spawnManager,
            mock(DepotSpawner.class),
            mock(OccupancyManager.class),
            mock(RailGraphService.class),
            mockRouteDefinitions(firstRouteId),
            mock(RuntimeDispatchService.class),
            mockConfigManager(),
            mock(SignNodeRegistry.class),
            layoverRegistry,
            null,
            Duration.ofSeconds(1),
            1,
            10);

    assigner.tick(provider, now);

    ArgumentCaptor<SpawnTicket> requeueCaptor = ArgumentCaptor.forClass(SpawnTicket.class);
    verify(spawnManager).requeue(requeueCaptor.capture());
    SpawnTicket deferred = requeueCaptor.getValue();
    assertEquals(secondTicket.id(), deferred.id());
    assertEquals(0, deferred.attempts());
    assertEquals(Optional.of("spawn-per-tick-limit"), deferred.lastError());
    assertEquals(now.plusSeconds(1), deferred.notBefore());
    assertEquals(1, assigner.snapshotPendingTickets().size());
  }

  @Test
  void tickKeepsLayoverPendingWhenSpawnControlLineCapIsFull() {
    UUID lineId = UUID.randomUUID();
    UUID routeId = UUID.randomUUID();
    SpawnTicket ticket = buildTicket(routeId, lineId, "R1", 0L);
    StorageProvider provider =
        mockProviderForRoutes(
            lineId, Map.of(routeId, "R1"), false, Map.of(LineSpawnMetadata.KEY_MAX_TRAINS, 1));
    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.pollDueTickets(eq(provider), any())).thenReturn(List.of(ticket));

    LayoverRegistry.LayoverCandidate candidate =
        new LayoverRegistry.LayoverCandidate(
            "train-1", "A", NodeId.of("A"), Instant.now(), Map.of());
    LayoverRegistry layoverRegistry = mock(LayoverRegistry.class);
    when(layoverRegistry.findCandidates("A")).thenReturn(List.of(candidate));

    RuntimeDispatchService runtimeDispatchService = mock(RuntimeDispatchService.class);
    when(runtimeDispatchService.snapshotProgressEntries())
        .thenReturn(congestedProgressEntries(routeId, "OP:L1:R1"));

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

    verify(runtimeDispatchService, never())
        .dispatchLayover(eq(candidate), any(ServiceTicket.class));
    assertEquals(1, assigner.snapshotPendingTickets().size(), "超出容量时应等待后续复用窗口");
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
    return mockProviderForRoutes(lineId, routes, withCret, Map.of());
  }

  private static StorageProvider mockProviderForRoutes(
      UUID lineId, Map<UUID, String> routes, boolean withCret, Map<String, Object> lineMetadata) {
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
            lineMetadata,
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

  private static StorageProvider mockProviderForOperationAndCreateRoute(
      UUID lineId, UUID operationRouteId, UUID createRouteId) {
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

    Route operationRoute =
        new Route(
            operationRouteId,
            "OP-1",
            lineId,
            "Operation",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            Instant.now(),
            Instant.now());
    Route createRoute =
        new Route(
            createRouteId,
            "CREATE-1",
            lineId,
            "Create",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.CREATE,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            Instant.now(),
            Instant.now());
    when(routeRepository.findById(operationRouteId)).thenReturn(Optional.of(operationRoute));
    when(routeRepository.findById(createRouteId)).thenReturn(Optional.of(createRoute));
    when(routeRepository.listByLine(lineId)).thenReturn(List.of(operationRoute, createRoute));

    RouteStop operationFirst =
        new RouteStop(
            operationRouteId,
            0,
            Optional.empty(),
            Optional.of("A"),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.of("CRET SURN:D:DEPOT:1"));
    RouteStop operationSecond =
        new RouteStop(
            operationRouteId,
            1,
            Optional.empty(),
            Optional.of("B"),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());
    RouteStop createFirst =
        new RouteStop(
            createRouteId,
            0,
            Optional.empty(),
            Optional.of("A"),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.of("CRET SURN:D:DEPOT:2"));
    RouteStop createSecond =
        new RouteStop(
            createRouteId,
            1,
            Optional.empty(),
            Optional.of("B"),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());
    when(stopRepository.listByRoute(operationRouteId))
        .thenReturn(List.of(operationFirst, operationSecond));
    when(stopRepository.listByRoute(createRouteId)).thenReturn(List.of(createFirst, createSecond));
    return provider;
  }

  private static StorageProvider mockProviderForRouteWithStationDestination(
      UUID lineId, UUID routeId, UUID stationId, NodeId depotNode, String stationCode) {
    StorageProvider provider = mock(StorageProvider.class);
    LineRepository lineRepository = mock(LineRepository.class);
    RouteRepository routeRepository = mock(RouteRepository.class);
    RouteStopRepository stopRepository = mock(RouteStopRepository.class);
    StationRepository stationRepository = mock(StationRepository.class);
    when(provider.lines()).thenReturn(lineRepository);
    when(provider.routes()).thenReturn(routeRepository);
    when(provider.routeStops()).thenReturn(stopRepository);
    when(provider.stations()).thenReturn(stationRepository);

    UUID operatorId = UUID.randomUUID();
    Instant now = Instant.parse("2026-02-01T00:00:00Z");
    Line line =
        new Line(
            lineId,
            "L1",
            operatorId,
            "Line",
            Optional.empty(),
            LineServiceType.METRO,
            Optional.empty(),
            LineStatus.ACTIVE,
            Optional.of(60),
            Map.of(),
            now,
            now);
    Route route =
        new Route(
            routeId,
            "R1",
            lineId,
            "RouteName",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            now,
            now);
    Station station =
        new Station(
            stationId,
            stationCode,
            operatorId,
            Optional.of(lineId),
            "Central",
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of("SURN:S:" + stationCode + ":1"),
            Optional.empty(),
            List.of(),
            Map.of(),
            now,
            now);
    RouteStop first =
        new RouteStop(
            routeId,
            0,
            Optional.empty(),
            Optional.of(depotNode.value()),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.of("CRET " + depotNode.value()));
    RouteStop second =
        new RouteStop(
            routeId,
            1,
            Optional.of(stationId),
            Optional.of("SURN:S:" + stationCode + ":1"),
            Optional.empty(),
            RouteStopPassType.TERMINATE,
            Optional.empty());

    when(lineRepository.findById(lineId)).thenReturn(Optional.of(line));
    when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
    when(stopRepository.listByRoute(routeId)).thenReturn(List.of(first, second));
    when(stationRepository.findById(stationId)).thenReturn(Optional.of(station));
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

  private static SimpleRailGraph graphWithSingleEdge(NodeId from, NodeId to) {
    RailNode fromNode =
        new SignRailNode(
            from, NodeType.DEPOT, new Vector(0.0, 64.0, 0.0), Optional.empty(), Optional.empty());
    RailNode toNode =
        new SignRailNode(
            to, NodeType.WAYPOINT, new Vector(10.0, 64.0, 0.0), Optional.empty(), Optional.empty());
    EdgeId edgeId = EdgeId.undirected(from, to);
    RailEdge edge = new RailEdge(edgeId, from, to, 10, 8.0, true, Optional.empty());
    return new SimpleRailGraph(Map.of(from, fromNode, to, toNode), Map.of(edgeId, edge), Set.of());
  }

  private static SimpleRailGraph graphWithDepotLinearPath(List<NodeId> nodes) {
    java.util.Map<NodeId, RailNode> railNodes = new java.util.LinkedHashMap<>();
    java.util.Map<EdgeId, RailEdge> edges = new java.util.LinkedHashMap<>();
    for (int i = 0; i < nodes.size(); i++) {
      NodeId node = nodes.get(i);
      NodeType type = i == 0 ? NodeType.DEPOT : NodeType.WAYPOINT;
      railNodes.put(
          node,
          new SignRailNode(
              node, type, new Vector(i * 10.0, 64.0, 0.0), Optional.empty(), Optional.empty()));
    }
    for (int i = 0; i + 1 < nodes.size(); i++) {
      NodeId from = nodes.get(i);
      NodeId to = nodes.get(i + 1);
      EdgeId edgeId = EdgeId.undirected(from, to);
      edges.put(edgeId, new RailEdge(edgeId, from, to, 10, 8.0, true, Optional.empty()));
    }
    return new SimpleRailGraph(railNodes, edges, Set.of());
  }

  private static SimpleTicketAssigner createDepotGateAssigner(
      StorageProvider provider,
      UUID routeId,
      NodeId depotNode,
      SimpleRailGraph graph,
      RouteDefinition route,
      OccupancyResource blockedResource,
      DepotSpawner depotSpawner) {
    return createDepotGateAssigner(
        provider, routeId, depotNode, graph, route, blockedResource, depotSpawner, message -> {});
  }

  private static SimpleTicketAssigner createDepotGateAssigner(
      StorageProvider provider,
      UUID routeId,
      NodeId depotNode,
      SimpleRailGraph graph,
      RouteDefinition route,
      OccupancyResource blockedResource,
      DepotSpawner depotSpawner,
      java.util.function.Consumer<String> debugLogger) {
    SpawnTicket ticket = buildTicket(routeId);
    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.pollDueTickets(eq(provider), any())).thenReturn(List.of(ticket));
    when(spawnManager.snapshotQueue()).thenReturn(List.of());

    UUID worldId = UUID.randomUUID();
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    SignNodeRegistry signNodeRegistry = registryWithDepot(worldId, depotNode);

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    when(occupancyManager.canEnter(any(OccupancyRequest.class)))
        .thenAnswer(
            inv -> {
              OccupancyRequest request = inv.getArgument(0);
              boolean blocked = request.resourceList().contains(blockedResource);
              if (!blocked) {
                return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
              }
              OccupancyClaim blocker =
                  new OccupancyClaim(
                      blockedResource,
                      "busy-train",
                      Optional.empty(),
                      request.now(),
                      Duration.ZERO,
                      Optional.empty());
              return new OccupancyDecision(
                  false, request.now(), SignalAspect.STOP, List.of(blocker));
            });

    RuntimeDispatchService runtimeDispatchService = mock(RuntimeDispatchService.class);
    when(runtimeDispatchService.snapshotProgressEntries()).thenReturn(Map.of());
    when(runtimeDispatchService.snapshotEffectiveStartNodes()).thenReturn(Map.of());

    return new SimpleTicketAssigner(
        spawnManager,
        depotSpawner,
        occupancyManager,
        railGraphService,
        mockRouteDefinitions(Map.of(routeId, route)),
        runtimeDispatchService,
        mockConfigManager(),
        signNodeRegistry,
        mock(LayoverRegistry.class),
        debugLogger,
        Duration.ofSeconds(1),
        1,
        10);
  }

  private static SimpleRailGraph graphWithTwoDepotStarts(
      NodeId depotOne, NodeId depotTwo, NodeId nodeA, NodeId nodeB) {
    RailNode depotOneNode =
        new SignRailNode(
            depotOne,
            NodeType.DEPOT,
            new Vector(-20.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode depotTwoNode =
        new SignRailNode(
            depotTwo,
            NodeType.DEPOT,
            new Vector(-10.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode a =
        new SignRailNode(
            nodeA,
            NodeType.WAYPOINT,
            new Vector(0.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode b =
        new SignRailNode(
            nodeB,
            NodeType.WAYPOINT,
            new Vector(10.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    EdgeId edgeDepotOneA = EdgeId.undirected(depotOne, nodeA);
    EdgeId edgeDepotTwoA = EdgeId.undirected(depotTwo, nodeA);
    EdgeId edgeAB = EdgeId.undirected(nodeA, nodeB);
    return new SimpleRailGraph(
        Map.of(depotOne, depotOneNode, depotTwo, depotTwoNode, nodeA, a, nodeB, b),
        Map.of(
            edgeDepotOneA,
            new RailEdge(edgeDepotOneA, depotOne, nodeA, 10, 8.0, true, Optional.empty()),
            edgeDepotTwoA,
            new RailEdge(edgeDepotTwoA, depotTwo, nodeA, 10, 8.0, true, Optional.empty()),
            edgeAB,
            new RailEdge(edgeAB, nodeA, nodeB, 10, 8.0, true, Optional.empty())),
        Set.of());
  }

  private static SignNodeRegistry registryWithDepot(UUID worldId, NodeId depotNode) {
    SignNodeRegistry signNodeRegistry = mock(SignNodeRegistry.class);
    SignNodeRegistry.SignNodeInfo depotInfo =
        new SignNodeRegistry.SignNodeInfo(
            new SignNodeDefinition(depotNode, NodeType.DEPOT, Optional.empty(), Optional.empty()),
            worldId,
            "TestWorld",
            0,
            64,
            0);
    when(signNodeRegistry.snapshotInfos()).thenReturn(Map.of("depot", depotInfo));
    return signNodeRegistry;
  }

  private static SignNodeRegistry registryWithDepots(
      UUID worldId, NodeId depotOne, NodeId depotTwo) {
    SignNodeRegistry signNodeRegistry = mock(SignNodeRegistry.class);
    SignNodeRegistry.SignNodeInfo depotOneInfo =
        new SignNodeRegistry.SignNodeInfo(
            new SignNodeDefinition(depotOne, NodeType.DEPOT, Optional.empty(), Optional.empty()),
            worldId,
            "TestWorld",
            -20,
            64,
            0);
    SignNodeRegistry.SignNodeInfo depotTwoInfo =
        new SignNodeRegistry.SignNodeInfo(
            new SignNodeDefinition(depotTwo, NodeType.DEPOT, Optional.empty(), Optional.empty()),
            worldId,
            "TestWorld",
            -10,
            64,
            0);
    when(signNodeRegistry.snapshotInfos())
        .thenReturn(Map.of("depot-1", depotOneInfo, "depot-2", depotTwoInfo));
    return signNodeRegistry;
  }

  private static ConfigManager mockConfigManager() {
    return mockConfigManager(0.0);
  }

  private static ConfigManager mockConfigManager(double layoverFallbackMultiplier) {
    return mockConfigManager(layoverFallbackMultiplier, Duration.ofDays(1));
  }

  private static ConfigManager mockConfigManager(
      double layoverFallbackMultiplier, Duration pendingLayoverMaxAge) {
    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView view = mock(ConfigManager.ConfigView.class);
    ConfigManager.SpawnSettings spawnSettings = mock(ConfigManager.SpawnSettings.class);
    ConfigManager.RuntimeSettings runtimeSettings = mock(ConfigManager.RuntimeSettings.class);
    when(configManager.current()).thenReturn(view);
    when(view.spawnSettings()).thenReturn(spawnSettings);
    when(view.runtimeSettings()).thenReturn(runtimeSettings);
    when(spawnSettings.layoverFallbackMultiplier()).thenReturn(layoverFallbackMultiplier);
    when(spawnSettings.pendingLayoverMaxAgeSeconds())
        .thenReturn(Math.max(0L, pendingLayoverMaxAge.toSeconds()));
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
  void tickDropsPendingLayoverTicketsAfterHardMaxAgeEvenAfterRefresh() {
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
            mockConfigManager(0.0, Duration.ofSeconds(600)),
            mock(SignNodeRegistry.class),
            layoverRegistry,
            null,
            Duration.ofSeconds(1),
            1,
            10);

    Instant t0 = Instant.now();
    assigner.tick(provider, t0);
    assertEquals(1, assigner.snapshotPendingTickets().size(), "首次无候选应进入 pending");

    when(spawnManager.pollDueTickets(eq(provider), any())).thenReturn(List.of());
    assigner.tick(provider, t0.plusSeconds(301));
    assertEquals(1, assigner.snapshotPendingTickets().size(), "刷新窗口不应重置首次入队时间");

    assigner.tick(provider, t0.plusSeconds(601));
    assertEquals(0, assigner.snapshotPendingTickets().size(), "超过硬最大等待时间后应清理 pending");
    verify(spawnManager).complete(ticket);
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
    when(occupancyManager.canEnter(requestCaptor.capture()))
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

  @Test
  void tickDepotSpawnRequestUsesSelectedLineDepotAsGateStart() {
    UUID lineId = UUID.randomUUID();
    UUID routeId = UUID.randomUUID();
    NodeId depotOne = NodeId.of("SURN:D:DEPOT:1");
    NodeId depotTwo = NodeId.of("SURN:D:DEPOT:2");
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
    SpawnTicket ticket = buildTicket(routeId, lineId, "R1", 0L);
    StorageProvider provider =
        mockProviderForRoutes(
            lineId,
            Map.of(routeId, "R1"),
            true,
            Map.of(LineSpawnMetadata.KEY_DEPOTS, List.of(depotTwo.value())));
    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.pollDueTickets(eq(provider), any())).thenReturn(List.of(ticket));

    UUID worldId = UUID.randomUUID();
    RailNode depot1 =
        new SignRailNode(
            depotOne,
            NodeType.DEPOT,
            new Vector(-20.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode depot2 =
        new SignRailNode(
            depotTwo,
            NodeType.DEPOT,
            new Vector(-10.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode a =
        new SignRailNode(
            nodeA,
            NodeType.WAYPOINT,
            new Vector(0.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode b =
        new SignRailNode(
            nodeB,
            NodeType.WAYPOINT,
            new Vector(10.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    EdgeId edgeDepotOneA = EdgeId.undirected(depotOne, nodeA);
    EdgeId edgeDepotTwoA = EdgeId.undirected(depotTwo, nodeA);
    EdgeId edgeAB = EdgeId.undirected(nodeA, nodeB);
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(depotOne, depot1, depotTwo, depot2, nodeA, a, nodeB, b),
            Map.of(
                edgeDepotOneA,
                new RailEdge(edgeDepotOneA, depotOne, nodeA, 10, 8.0, true, Optional.empty()),
                edgeDepotTwoA,
                new RailEdge(edgeDepotTwoA, depotTwo, nodeA, 10, 8.0, true, Optional.empty()),
                edgeAB,
                new RailEdge(edgeAB, nodeA, nodeB, 10, 8.0, true, Optional.empty())),
            java.util.Set.of());
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));

    SignNodeRegistry signNodeRegistry = mock(SignNodeRegistry.class);
    SignNodeRegistry.SignNodeInfo depotOneInfo =
        new SignNodeRegistry.SignNodeInfo(
            new SignNodeDefinition(depotOne, NodeType.DEPOT, Optional.empty(), Optional.empty()),
            worldId,
            "TestWorld",
            -20,
            64,
            0);
    SignNodeRegistry.SignNodeInfo depotTwoInfo =
        new SignNodeRegistry.SignNodeInfo(
            new SignNodeDefinition(depotTwo, NodeType.DEPOT, Optional.empty(), Optional.empty()),
            worldId,
            "TestWorld",
            -10,
            64,
            0);
    when(signNodeRegistry.snapshotInfos())
        .thenReturn(Map.of("depot-1", depotOneInfo, "depot-2", depotTwoInfo));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    ArgumentCaptor<OccupancyRequest> requestCaptor =
        ArgumentCaptor.forClass(OccupancyRequest.class);
    when(occupancyManager.canEnter(requestCaptor.capture()))
        .thenReturn(new OccupancyDecision(false, Instant.now(), SignalAspect.STOP, List.of()));

    RuntimeDispatchService runtimeDispatchService = mock(RuntimeDispatchService.class);
    when(runtimeDispatchService.snapshotProgressEntries()).thenReturn(Map.of());
    when(runtimeDispatchService.snapshotEffectiveStartNodes()).thenReturn(Map.of());
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("OP:L1:R1"), List.of(depotOne, nodeA, nodeB), Optional.empty());
    SimpleTicketAssigner assigner =
        new SimpleTicketAssigner(
            spawnManager,
            mock(DepotSpawner.class),
            occupancyManager,
            railGraphService,
            mockRouteDefinitions(Map.of(routeId, route)),
            runtimeDispatchService,
            mockConfigManager(),
            signNodeRegistry,
            mock(LayoverRegistry.class),
            null,
            Duration.ofSeconds(1),
            1,
            10);

    assigner.tick(provider, Instant.now());

    OccupancyRequest captured = requestCaptor.getValue();
    assertTrue(captured.resourceList().contains(OccupancyResource.forNode(depotTwo)));
    assertFalse(captured.resourceList().contains(OccupancyResource.forNode(depotOne)));
    assertTrue(captured.resourceList().contains(OccupancyResource.forEdge(edgeDepotTwoA)));
    String conflictKey =
        ((RailGraphConflictSupport) graph).conflictKeyForEdge(edgeDepotTwoA).orElseThrow();
    assertEquals(CorridorDirection.B_TO_A, captured.corridorDirections().get(conflictKey));
    assertEquals(0, captured.conflictEntryOrders().get(conflictKey));
  }

  @Test
  void tickDepotSpawnBlocksWhenLookoverCoversLongSingleOccupancy() {
    UUID routeId = UUID.randomUUID();
    NodeId depotNode = NodeId.of("SURN:D:DEPOT:1");
    NodeId throatNode = NodeId.of("SURN:D:DEPOT:1:001");
    NodeId midOne = NodeId.of("M1");
    NodeId midTwo = NodeId.of("M2");
    NodeId midThree = NodeId.of("M3");
    NodeId endNode = NodeId.of("B");
    EdgeId blockedEdge = EdgeId.undirected(midTwo, midThree);
    SimpleRailGraph graph =
        graphWithDepotLinearPath(List.of(depotNode, throatNode, midOne, midTwo, midThree, endNode));
    StorageProvider provider = mockProvider(routeId, true);
    DepotSpawner depotSpawner = mock(DepotSpawner.class);
    SimpleTicketAssigner assigner =
        createDepotGateAssigner(
            provider,
            routeId,
            depotNode,
            graph,
            new RouteDefinition(
                RouteId.of("OP:L1:R1"), List.of(depotNode, endNode), Optional.empty()),
            OccupancyResource.forEdge(blockedEdge),
            depotSpawner);

    assigner.tick(provider, Instant.now());

    verify(depotSpawner, never()).spawn(any(), any(), any(), any());
  }

  @Test
  void tickDepotSpawnBlocksWhenLookoverCoversSwitcherBranchOccupancy() {
    UUID routeId = UUID.randomUUID();
    NodeId depotNode = NodeId.of("SURN:D:DEPOT:1");
    NodeId switcherNode = NodeId.of("SW");
    NodeId mainNode = NodeId.of("B");
    NodeId branchNode = NodeId.of("BRANCH");
    EdgeId depotSwitcher = EdgeId.undirected(depotNode, switcherNode);
    EdgeId switcherMain = EdgeId.undirected(switcherNode, mainNode);
    EdgeId switcherBranch = EdgeId.undirected(switcherNode, branchNode);
    RailNode depot =
        new SignRailNode(
            depotNode,
            NodeType.DEPOT,
            new Vector(-10.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode switcher =
        new SignRailNode(
            switcherNode,
            NodeType.SWITCHER,
            new Vector(0.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode main =
        new SignRailNode(
            mainNode,
            NodeType.WAYPOINT,
            new Vector(10.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode branch =
        new SignRailNode(
            branchNode,
            NodeType.WAYPOINT,
            new Vector(0.0, 64.0, 10.0),
            Optional.empty(),
            Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(depotNode, depot, switcherNode, switcher, mainNode, main, branchNode, branch),
            Map.of(
                depotSwitcher,
                new RailEdge(
                    depotSwitcher, depotNode, switcherNode, 10, 8.0, true, Optional.empty()),
                switcherMain,
                new RailEdge(switcherMain, switcherNode, mainNode, 10, 8.0, true, Optional.empty()),
                switcherBranch,
                new RailEdge(
                    switcherBranch, switcherNode, branchNode, 10, 8.0, true, Optional.empty())),
            Set.of());
    StorageProvider provider = mockProvider(routeId, true);
    DepotSpawner depotSpawner = mock(DepotSpawner.class);
    SimpleTicketAssigner assigner =
        createDepotGateAssigner(
            provider,
            routeId,
            depotNode,
            graph,
            new RouteDefinition(
                RouteId.of("OP:L1:R1"), List.of(depotNode, mainNode), Optional.empty()),
            OccupancyResource.forEdge(switcherBranch),
            depotSpawner);

    assigner.tick(provider, Instant.now());

    verify(depotSpawner, never()).spawn(any(), any(), any(), any());
  }

  @Test
  void depotGateTraceIncludesSelectedDepotAndBlockers() {
    UUID routeId = UUID.randomUUID();
    NodeId depotNode = NodeId.of("SURN:D:DEPOT:1");
    NodeId switcherNode = NodeId.of("SW");
    NodeId mainNode = NodeId.of("B");
    EdgeId depotSwitcher = EdgeId.undirected(depotNode, switcherNode);
    EdgeId switcherMain = EdgeId.undirected(switcherNode, mainNode);
    RailNode depot =
        new SignRailNode(
            depotNode,
            NodeType.DEPOT,
            new Vector(-10.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode switcher =
        new SignRailNode(
            switcherNode,
            NodeType.SWITCHER,
            new Vector(0.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode main =
        new SignRailNode(
            mainNode,
            NodeType.WAYPOINT,
            new Vector(10.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(depotNode, depot, switcherNode, switcher, mainNode, main),
            Map.of(
                depotSwitcher,
                new RailEdge(
                    depotSwitcher, depotNode, switcherNode, 10, 8.0, true, Optional.empty()),
                switcherMain,
                new RailEdge(
                    switcherMain, switcherNode, mainNode, 10, 8.0, true, Optional.empty())),
            Set.of());
    StorageProvider provider = mockProvider(routeId, true);
    DepotSpawner depotSpawner = mock(DepotSpawner.class);
    List<String> debugMessages = new ArrayList<>();
    SimpleTicketAssigner assigner =
        createDepotGateAssigner(
            provider,
            routeId,
            depotNode,
            graph,
            new RouteDefinition(
                RouteId.of("OP:L1:R1"), List.of(depotNode, mainNode), Optional.empty()),
            OccupancyResource.forConflict("switcher:SW"),
            depotSpawner,
            debugMessages::add);

    assigner.tick(provider, Instant.now());

    verify(depotSpawner, never()).spawn(any(), any(), any(), any());
    String trace =
        debugMessages.stream()
            .filter(message -> message.startsWith("Depot gate blocked trace:"))
            .findFirst()
            .orElse("");
    assertFalse(trace.isBlank());
    assertTrue(trace.contains("ticket="));
    assertTrue(trace.contains("route=OP/L1/R1"));
    assertTrue(trace.contains("selectedDepotNodeId=" + depotNode.value()));
    assertTrue(trace.contains("candidateDepots="));
    assertTrue(trace.contains("originalFirstWaypoint=" + depotNode.value()));
    assertTrue(trace.contains("effectiveFirstWaypoint=" + depotNode.value()));
    assertTrue(trace.contains("expandedPath=[SURN:D:DEPOT:1, SW, B]"));
    assertTrue(trace.contains("lookoverDepth="));
    assertTrue(trace.contains("CONFLICT:switcher:SW"));
    assertTrue(trace.contains("busy-train"));
    assertTrue(trace.contains("spawnLease=held"));
    assertTrue(trace.contains("occupancyVersion="));
  }

  @Test
  void tickPrioritizesUnderloadedDepotBeforePerTickLimit() {
    UUID lineId = UUID.randomUUID();
    UUID routeOneId = UUID.randomUUID();
    UUID routeTwoId = UUID.randomUUID();
    NodeId depotOne = NodeId.of("SURN:D:DEPOT:1");
    NodeId depotTwo = NodeId.of("SURN:D:DEPOT:2");
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
    SpawnTicket depotOneTicket =
        buildTicket(routeOneId, lineId, "R1", 0L).withSelectedDepot(depotOne.value());
    SpawnTicket depotTwoTicket =
        buildTicket(routeTwoId, lineId, "R2", 1L).withSelectedDepot(depotTwo.value());
    StorageProvider provider =
        mockProviderForRoutes(
            lineId,
            Map.of(routeOneId, "R1", routeTwoId, "R2"),
            true,
            Map.of(LineSpawnMetadata.KEY_DEPOTS, List.of(depotOne.value(), depotTwo.value())));
    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.pollDueTickets(eq(provider), any()))
        .thenReturn(List.of(depotOneTicket, depotTwoTicket));

    UUID worldId = UUID.randomUUID();
    SimpleRailGraph graph = graphWithTwoDepotStarts(depotOne, depotTwo, nodeA, nodeB);
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));

    SignNodeRegistry signNodeRegistry = registryWithDepots(worldId, depotOne, depotTwo);
    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    ArgumentCaptor<OccupancyRequest> requestCaptor =
        ArgumentCaptor.forClass(OccupancyRequest.class);
    when(occupancyManager.canEnter(requestCaptor.capture()))
        .thenReturn(new OccupancyDecision(false, Instant.now(), SignalAspect.STOP, List.of()));

    RuntimeDispatchService runtimeDispatchService = mock(RuntimeDispatchService.class);
    RouteProgressRegistry.RouteProgressEntry activeAtDepotOne =
        new RouteProgressRegistry.RouteProgressEntry(
            "active-d1",
            routeOneId,
            RouteId.of("OP:L1:R1"),
            0,
            Optional.of(depotOne),
            Optional.empty(),
            SignalAspect.PROCEED,
            Instant.now());
    when(runtimeDispatchService.snapshotProgressEntries())
        .thenReturn(Map.of("active-d1", activeAtDepotOne));
    when(runtimeDispatchService.snapshotEffectiveStartNodes())
        .thenReturn(Map.of("active-d1", depotOne));

    RouteDefinition routeOne =
        new RouteDefinition(
            RouteId.of("OP:L1:R1"), List.of(depotOne, nodeA, nodeB), Optional.empty());
    RouteDefinition routeTwo =
        new RouteDefinition(
            RouteId.of("OP:L1:R2"), List.of(depotTwo, nodeA, nodeB), Optional.empty());
    SimpleTicketAssigner assigner =
        new SimpleTicketAssigner(
            spawnManager,
            mock(DepotSpawner.class),
            occupancyManager,
            railGraphService,
            mockRouteDefinitions(Map.of(routeOneId, routeOne, routeTwoId, routeTwo)),
            runtimeDispatchService,
            mockConfigManager(),
            signNodeRegistry,
            mock(LayoverRegistry.class),
            null,
            Duration.ofSeconds(1),
            1,
            10);

    assigner.tick(provider, Instant.now());

    OccupancyRequest captured = requestCaptor.getValue();
    assertTrue(captured.resourceList().contains(OccupancyResource.forNode(depotTwo)));
    assertFalse(captured.resourceList().contains(OccupancyResource.forNode(depotOne)));
  }

  @Test
  void tickMaterializesDynamicDepotTicketsAcrossActualDepotsInSameTick() {
    UUID lineId = UUID.randomUUID();
    UUID routeOneId = UUID.randomUUID();
    UUID routeTwoId = UUID.randomUUID();
    NodeId depotOne = NodeId.of("SURN:D:DEPOT:1");
    NodeId depotTwo = NodeId.of("SURN:D:DEPOT:2");
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
    String dynamicDepot = "DYNAMIC:SURN:D:DEPOT:[1:2]";
    SpawnTicket first = buildTicket(routeOneId, lineId, "R1", 0L);
    SpawnTicket second = buildTicket(routeTwoId, lineId, "R2", 1L);
    StorageProvider provider =
        mockProviderForRoutes(
            lineId,
            Map.of(routeOneId, "R1", routeTwoId, "R2"),
            true,
            Map.of(LineSpawnMetadata.KEY_DEPOTS, List.of(dynamicDepot)));
    SpawnManager spawnManager = mock(SpawnManager.class);
    Instant now = Instant.parse("2026-02-01T00:00:00Z");
    when(spawnManager.pollDueTickets(eq(provider), eq(now))).thenReturn(List.of(first, second));
    when(spawnManager.snapshotQueue()).thenReturn(List.of());

    UUID worldId = UUID.randomUUID();
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithTwoDepotStarts(depotOne, depotTwo, nodeA, nodeB), now)));
    SignNodeRegistry signNodeRegistry = registryWithDepots(worldId, depotOne, depotTwo);
    PreviewOccupancyManager occupancyManager = mock(PreviewOccupancyManager.class);
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    when(occupancyManager.canEnterPreview(any(OccupancyRequest.class)))
        .thenAnswer(
            inv -> {
              OccupancyRequest request = inv.getArgument(0);
              return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
            });

    DepotSpawner depotSpawner = mock(DepotSpawner.class);
    when(depotSpawner.spawn(eq(provider), any(), any(), eq(now))).thenReturn(Optional.empty());
    RuntimeDispatchService runtimeDispatchService = mock(RuntimeDispatchService.class);
    when(runtimeDispatchService.snapshotProgressEntries()).thenReturn(Map.of());
    when(runtimeDispatchService.snapshotEffectiveStartNodes()).thenReturn(Map.of());

    RouteDefinition routeOne =
        new RouteDefinition(
            RouteId.of("OP:L1:R1"), List.of(depotOne, nodeA, nodeB), Optional.empty());
    RouteDefinition routeTwo =
        new RouteDefinition(
            RouteId.of("OP:L1:R2"), List.of(depotOne, nodeA, nodeB), Optional.empty());
    SimpleTicketAssigner assigner =
        new SimpleTicketAssigner(
            spawnManager,
            depotSpawner,
            occupancyManager,
            railGraphService,
            mockRouteDefinitions(Map.of(routeOneId, routeOne, routeTwoId, routeTwo)),
            runtimeDispatchService,
            mockConfigManager(),
            signNodeRegistry,
            mock(LayoverRegistry.class),
            null,
            Duration.ofSeconds(1),
            2,
            10);

    assigner.tick(provider, now);

    ArgumentCaptor<SpawnTicket> ticketCaptor = ArgumentCaptor.forClass(SpawnTicket.class);
    verify(depotSpawner, times(2)).spawn(eq(provider), ticketCaptor.capture(), any(), eq(now));
    assertEquals(
        Set.of(depotOne.value(), depotTwo.value()),
        ticketCaptor.getAllValues().stream()
            .map(ticket -> ticket.selectedDepotNodeId().orElse(""))
            .collect(java.util.stream.Collectors.toSet()));
  }

  @Test
  void tickBuildsTrainNameFromDestinationCodeForCretSpawn() {
    UUID lineId = UUID.randomUUID();
    UUID routeId = UUID.randomUUID();
    UUID stationId = UUID.randomUUID();
    NodeId depotNode = NodeId.of("SURN:D:DEPOT:1");
    NodeId stationNode = NodeId.of("SURN:S:DST:1");
    StorageProvider provider =
        mockProviderForRouteWithStationDestination(lineId, routeId, stationId, depotNode, "DST");
    SpawnTicket ticket = buildTicket(routeId, lineId, "R1", 0L);
    SpawnManager spawnManager = mock(SpawnManager.class);
    Instant now = Instant.parse("2026-02-01T00:00:00Z");
    when(spawnManager.pollDueTickets(eq(provider), eq(now))).thenReturn(List.of(ticket));
    when(spawnManager.snapshotQueue()).thenReturn(List.of());

    UUID worldId = UUID.randomUUID();
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(depotNode, stationNode), now)));
    SignNodeRegistry signNodeRegistry = registryWithDepot(worldId, depotNode);
    PreviewOccupancyManager occupancyManager = mock(PreviewOccupancyManager.class);
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    when(occupancyManager.canEnterPreview(any(OccupancyRequest.class)))
        .thenAnswer(
            inv -> {
              OccupancyRequest request = inv.getArgument(0);
              return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
            });

    DepotSpawner depotSpawner = mock(DepotSpawner.class);
    when(depotSpawner.spawn(eq(provider), any(), any(), eq(now))).thenReturn(Optional.empty());
    RuntimeDispatchService runtimeDispatchService = mock(RuntimeDispatchService.class);
    when(runtimeDispatchService.snapshotProgressEntries()).thenReturn(Map.of());
    when(runtimeDispatchService.snapshotEffectiveStartNodes()).thenReturn(Map.of());
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("OP:L1:R1"), List.of(depotNode, stationNode), Optional.empty());
    SimpleTicketAssigner assigner =
        new SimpleTicketAssigner(
            spawnManager,
            depotSpawner,
            occupancyManager,
            railGraphService,
            mockRouteDefinitions(Map.of(routeId, route)),
            runtimeDispatchService,
            mockConfigManager(),
            signNodeRegistry,
            mock(LayoverRegistry.class),
            null,
            Duration.ofSeconds(1),
            1,
            10);

    assigner.tick(provider, now);

    ArgumentCaptor<String> trainNameCaptor = ArgumentCaptor.forClass(String.class);
    verify(depotSpawner).spawn(eq(provider), any(), trainNameCaptor.capture(), eq(now));
    assertTrue(
        trainNameCaptor.getValue().startsWith("OP-L1-LD-"),
        "列车名目的地位应使用目的地 code 的首字母，而不是站名或 route 名");
  }

  @Test
  void tickOperationRouteWithCretStillSpawnsWhenLineHasCreateRoute() {
    UUID lineId = UUID.randomUUID();
    UUID operationRouteId = UUID.randomUUID();
    UUID createRouteId = UUID.randomUUID();
    NodeId depotNode = NodeId.of("SURN:D:DEPOT:1");
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
    SpawnTicket ticket = buildTicket(operationRouteId, lineId, "OP-1", 0L);
    StorageProvider provider =
        mockProviderForOperationAndCreateRoute(lineId, operationRouteId, createRouteId);
    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.pollDueTickets(eq(provider), any())).thenReturn(List.of(ticket));

    UUID worldId = UUID.randomUUID();
    RailNode depot =
        new SignRailNode(
            depotNode,
            NodeType.DEPOT,
            new Vector(-10.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode a =
        new SignRailNode(
            nodeA,
            NodeType.WAYPOINT,
            new Vector(0.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode b =
        new SignRailNode(
            nodeB,
            NodeType.WAYPOINT,
            new Vector(10.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    EdgeId edgeDepotA = EdgeId.undirected(depotNode, nodeA);
    EdgeId edgeAB = EdgeId.undirected(nodeA, nodeB);
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(depotNode, depot, nodeA, a, nodeB, b),
            Map.of(
                edgeDepotA,
                new RailEdge(edgeDepotA, depotNode, nodeA, 10, 8.0, true, Optional.empty()),
                edgeAB,
                new RailEdge(edgeAB, nodeA, nodeB, 10, 8.0, true, Optional.empty())),
            java.util.Set.of());
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));

    SignNodeRegistry signNodeRegistry = mock(SignNodeRegistry.class);
    SignNodeRegistry.SignNodeInfo depotInfo =
        new SignNodeRegistry.SignNodeInfo(
            new SignNodeDefinition(depotNode, NodeType.DEPOT, Optional.empty(), Optional.empty()),
            worldId,
            "TestWorld",
            0,
            64,
            0);
    when(signNodeRegistry.snapshotInfos()).thenReturn(Map.of("depot", depotInfo));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any(OccupancyRequest.class)))
        .thenReturn(new OccupancyDecision(false, Instant.now(), SignalAspect.STOP, List.of()));
    LayoverRegistry layoverRegistry = mock(LayoverRegistry.class);
    when(layoverRegistry.findCandidates(any())).thenReturn(List.of());
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("OP:L1:OP-1"), List.of(depotNode, nodeA, nodeB), Optional.empty());
    SimpleTicketAssigner assigner =
        new SimpleTicketAssigner(
            spawnManager,
            mock(DepotSpawner.class),
            occupancyManager,
            railGraphService,
            mockRouteDefinitions(Map.of(operationRouteId, route)),
            mock(RuntimeDispatchService.class),
            mockConfigManager(),
            signNodeRegistry,
            layoverRegistry,
            null,
            Duration.ofSeconds(1),
            1,
            10);

    assigner.tick(provider, Instant.now());

    verify(occupancyManager).canEnter(any(OccupancyRequest.class));
    assertEquals(
        0,
        assigner.snapshotPendingTickets().size(),
        "CRET 运营 route 不应因 CREATE route 存在而转入 Layover");
  }

  @Test
  void depotSpawnDoesNotAcquireOccupancyBeforeGroupCreation() {
    UUID routeId = UUID.randomUUID();
    NodeId depotNode = NodeId.of("SURN:D:DEPOT:1");
    NodeId nextNode = NodeId.of("B");
    SpawnTicket ticket = buildTicket(routeId);
    StorageProvider provider = mockProvider(routeId, true);
    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.pollDueTickets(eq(provider), any())).thenReturn(List.of(ticket));
    when(spawnManager.snapshotQueue()).thenReturn(List.of());

    UUID worldId = UUID.randomUUID();
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(
            Optional.of(
                new RailGraphService.RailGraphSnapshot(
                    graphWithSingleEdge(depotNode, nextNode), Instant.now())));

    SignNodeRegistry signNodeRegistry = registryWithDepot(worldId, depotNode);
    PreviewOccupancyManager occupancyManager = mock(PreviewOccupancyManager.class);
    when(occupancyManager.snapshotClaims()).thenReturn(List.of());
    when(occupancyManager.canEnterPreview(any(OccupancyRequest.class)))
        .thenAnswer(
            inv -> {
              OccupancyRequest request = inv.getArgument(0);
              return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
            });
    when(occupancyManager.acquire(any(OccupancyRequest.class)))
        .thenAnswer(
            inv -> {
              OccupancyRequest request = inv.getArgument(0);
              return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
            });

    DepotSpawner depotSpawner = mock(DepotSpawner.class);
    when(depotSpawner.spawn(any(), any(), any(), any())).thenReturn(Optional.empty());
    RuntimeDispatchService runtimeDispatchService = mock(RuntimeDispatchService.class);
    when(runtimeDispatchService.snapshotProgressEntries()).thenReturn(Map.of());
    when(runtimeDispatchService.snapshotEffectiveStartNodes()).thenReturn(Map.of());

    SimpleTicketAssigner assigner =
        new SimpleTicketAssigner(
            spawnManager,
            depotSpawner,
            occupancyManager,
            railGraphService,
            mockRouteDefinitions(
                Map.of(
                    routeId,
                    new RouteDefinition(
                        RouteId.of("OP:L1:R1"), List.of(depotNode, nextNode), Optional.empty()))),
            runtimeDispatchService,
            mockConfigManager(),
            signNodeRegistry,
            mock(LayoverRegistry.class),
            null,
            Duration.ofSeconds(1),
            1,
            10);

    assigner.tick(provider, Instant.now());

    InOrder inOrder = inOrder(occupancyManager, depotSpawner);
    inOrder.verify(occupancyManager).canEnterPreview(any(OccupancyRequest.class));
    inOrder.verify(depotSpawner).spawn(any(), any(), any(), any());
    verify(spawnManager, never()).complete(any());
    verify(occupancyManager, never()).canEnter(any(OccupancyRequest.class));
    verify(occupancyManager, never()).acquire(any(OccupancyRequest.class));
    verify(spawnManager).requeue(any(SpawnTicket.class));
  }

  @Test
  void tickRequeuesOperationTicketWhenCongestionHoldTriggered() {
    UUID lineId = UUID.randomUUID();
    UUID routeId = UUID.randomUUID();
    SpawnTicket ticket = buildTicket(routeId, lineId, "R1", 0L);
    StorageProvider provider =
        mockProviderForRouteOperation(
            lineId, routeId, "R1", RouteOperationType.OPERATION, "SURN:D:DEPOT:1", "B");
    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.pollDueTickets(eq(provider), any())).thenReturn(List.of(ticket));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.snapshotClaims()).thenReturn(List.of(congestedEdgeClaim()));

    RuntimeDispatchService runtimeDispatchService = mock(RuntimeDispatchService.class);
    when(runtimeDispatchService.snapshotProgressEntries())
        .thenReturn(congestedProgressEntries(routeId, "OP:L1:R1"));

    SimpleTicketAssigner assigner =
        new SimpleTicketAssigner(
            spawnManager,
            mock(DepotSpawner.class),
            occupancyManager,
            mock(RailGraphService.class),
            mockRouteDefinitions(routeId),
            runtimeDispatchService,
            mockConfigManager(),
            mock(SignNodeRegistry.class),
            mock(LayoverRegistry.class),
            null,
            Duration.ofSeconds(1),
            1,
            10);

    assigner.tick(provider, Instant.now());

    verify(spawnManager).requeue(any(SpawnTicket.class));
    verify(spawnManager, never()).complete(any());
    assertEquals(1L, assigner.snapshotDiagnostics().retries());
    assertEquals(
        1L, assigner.snapshotDiagnostics().requeueByError().getOrDefault("congestion-hold", 0L));
  }

  @Test
  void tickAllowsReturnRouteToEnterPendingWhenCongestionHigh() {
    UUID lineId = UUID.randomUUID();
    UUID routeId = UUID.randomUUID();
    SpawnTicket ticket = buildTicket(routeId, lineId, "RET-1", 0L);
    StorageProvider provider =
        mockProviderForRouteOperation(
            lineId, routeId, "RET-1", RouteOperationType.RETURN, "A", "B");
    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.pollDueTickets(eq(provider), any())).thenReturn(List.of(ticket));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.snapshotClaims()).thenReturn(List.of(congestedEdgeClaim()));

    RuntimeDispatchService runtimeDispatchService = mock(RuntimeDispatchService.class);
    when(runtimeDispatchService.snapshotProgressEntries())
        .thenReturn(congestedProgressEntries(routeId, "OP:L1:RET-1"));

    LayoverRegistry layoverRegistry = mock(LayoverRegistry.class);
    when(layoverRegistry.findCandidates("A")).thenReturn(List.of());

    SimpleTicketAssigner assigner =
        new SimpleTicketAssigner(
            spawnManager,
            mock(DepotSpawner.class),
            occupancyManager,
            mock(RailGraphService.class),
            mockRouteDefinitions(Map.of(routeId, routeDefinition("OP:L1:RET-1", "A"))),
            runtimeDispatchService,
            mockConfigManager(),
            mock(SignNodeRegistry.class),
            layoverRegistry,
            null,
            Duration.ofSeconds(1),
            1,
            10);

    assigner.tick(provider, Instant.now());

    verify(spawnManager, never()).requeue(any(SpawnTicket.class));
    assertEquals(1, assigner.snapshotPendingTickets().size());
    assertEquals(0L, assigner.snapshotDiagnostics().retries());
  }

  private static OccupancyClaim congestedEdgeClaim() {
    return new OccupancyClaim(
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("A"), NodeId.of("B"))),
        "T-BUSY",
        Optional.empty(),
        Instant.now(),
        Duration.ZERO,
        Optional.empty());
  }

  private static Map<String, RouteProgressRegistry.RouteProgressEntry> congestedProgressEntries(
      UUID routeId, String routeValue) {
    RouteProgressRegistry.RouteProgressEntry entry1 =
        new RouteProgressRegistry.RouteProgressEntry(
            "T1",
            routeId,
            RouteId.of(routeValue),
            0,
            Optional.of(NodeId.of("B")),
            Optional.empty(),
            SignalAspect.STOP,
            Instant.now());
    RouteProgressRegistry.RouteProgressEntry entry2 =
        new RouteProgressRegistry.RouteProgressEntry(
            "T2",
            routeId,
            RouteId.of(routeValue),
            0,
            Optional.of(NodeId.of("B")),
            Optional.empty(),
            SignalAspect.STOP,
            Instant.now());
    RouteProgressRegistry.RouteProgressEntry entry3 =
        new RouteProgressRegistry.RouteProgressEntry(
            "T3",
            routeId,
            RouteId.of(routeValue),
            0,
            Optional.of(NodeId.of("B")),
            Optional.empty(),
            SignalAspect.STOP,
            Instant.now());
    return Map.of("t1", entry1, "t2", entry2, "t3", entry3);
  }
}
