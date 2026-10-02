package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.CompanyStatus;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.LineServiceType;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyRepository;
import org.fetarute.fetaruteTCAddon.company.repository.LineRepository;
import org.fetarute.fetaruteTCAddon.company.repository.OperatorRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RouteRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RouteStopRepository;
import org.fetarute.fetaruteTCAddon.company.repository.StationRepository;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnServiceKey;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
import org.fetarute.fetaruteTCAddon.storage.StorageManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.Test;

class ReclaimManagerTest {

  @Test
  void performReclaimCheckReclaimsWhenDirectionSupplyExceedsPendingDemand() {
    Instant now = Instant.now();
    UUID routeId = UUID.randomUUID();
    UUID stationId = UUID.randomUUID();
    StorageProvider provider = mockProvider(routeId, stationId);

    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    StorageManager storageManager = mock(StorageManager.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(storageManager.provider()).thenReturn(Optional.of(provider));

    TicketAssigner ticketAssigner = mock(TicketAssigner.class);
    when(ticketAssigner.snapshotPendingTickets()).thenReturn(List.of());
    when(ticketAssigner.forceAssign(eq(provider), eq("train-a"), any())).thenReturn(true);

    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register(
        "train-a",
        "surc:s:ppk:1",
        NodeId.of("SURC:S:PPK:1"),
        now.minusSeconds(400),
        Map.of("FTA_OPERATOR_CODE", "SURC"));
    layoverRegistry.register(
        "train-b",
        "surc:s:ppk:2",
        NodeId.of("SURC:S:PPK:2"),
        now.minusSeconds(300),
        Map.of("FTA_OPERATOR_CODE", "SURC"));

    ReclaimManager manager =
        new ReclaimManager(
            plugin, layoverRegistry, ticketAssigner, mockConfigManager(), null, () -> 0);

    manager.performReclaimCheck();

    verify(ticketAssigner, times(1)).forceAssign(eq(provider), eq("train-a"), any());
  }

  @Test
  void performReclaimCheckKeepsOneStandbyWhenPendingDemandExists() {
    Instant now = Instant.now();
    UUID routeId = UUID.randomUUID();
    UUID stationId = UUID.randomUUID();
    StorageProvider provider = mockProvider(routeId, stationId);

    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    StorageManager storageManager = mock(StorageManager.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(storageManager.provider()).thenReturn(Optional.of(provider));

    TicketAssigner ticketAssigner = mock(TicketAssigner.class);
    when(ticketAssigner.snapshotPendingTickets())
        .thenReturn(List.of(buildPendingTicket(routeId, "SURC:S:PPK:1")));

    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register(
        "train-a",
        "surc:s:ppk:1",
        NodeId.of("SURC:S:PPK:1"),
        now.minusSeconds(400),
        Map.of("FTA_OPERATOR_CODE", "SURC"));
    layoverRegistry.register(
        "train-b",
        "surc:s:ppk:2",
        NodeId.of("SURC:S:PPK:2"),
        now.minusSeconds(300),
        Map.of("FTA_OPERATOR_CODE", "SURC"));

    ReclaimManager manager =
        new ReclaimManager(
            plugin, layoverRegistry, ticketAssigner, mockConfigManager(), null, () -> 0);

    manager.performReclaimCheck();

    verify(ticketAssigner, never()).forceAssign(any(), any(), any());
  }

  @Test
  void performReclaimCheckReclaimsWhenOperationTripsReachMax() {
    Instant now = Instant.now();
    UUID routeId = UUID.randomUUID();
    UUID stationId = UUID.randomUUID();
    StorageProvider provider = mockProvider(routeId, stationId);

    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    StorageManager storageManager = mock(StorageManager.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(storageManager.provider()).thenReturn(Optional.of(provider));

    TicketAssigner ticketAssigner = mock(TicketAssigner.class);
    when(ticketAssigner.snapshotPendingTickets()).thenReturn(List.of());
    when(ticketAssigner.forceAssign(eq(provider), eq("train-life"), any())).thenReturn(true);

    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register(
        "train-life",
        "surc:s:ppk:1",
        NodeId.of("SURC:S:PPK:1"),
        now.minusSeconds(30),
        Map.of(
            "FTA_OPERATOR_CODE", "SURC",
            "FTA_OP_TRIPS", "4",
            "FTA_OP_MAX", "4"));

    ReclaimManager manager =
        new ReclaimManager(
            plugin, layoverRegistry, ticketAssigner, mockConfigManager(), null, () -> 99);

    manager.performReclaimCheck();

    verify(ticketAssigner, times(1)).forceAssign(eq(provider), eq("train-life"), any());
  }

  @Test
  void performReclaimCheckDoesNotOverReclaimSameDirectionAfterSuccessfulClaim() {
    Instant now = Instant.now();
    UUID routeId = UUID.randomUUID();
    UUID stationId = UUID.randomUUID();
    StorageProvider provider = mockProvider(routeId, stationId);

    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    StorageManager storageManager = mock(StorageManager.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(storageManager.provider()).thenReturn(Optional.of(provider));

    TicketAssigner ticketAssigner = mock(TicketAssigner.class);
    when(ticketAssigner.snapshotPendingTickets()).thenReturn(List.of());
    when(ticketAssigner.forceAssign(eq(provider), eq("train-a"), any())).thenReturn(true);

    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register(
        "train-a",
        "surc:s:ppk:1",
        NodeId.of("SURC:S:PPK:1"),
        now.minusSeconds(360),
        Map.of("FTA_OPERATOR_CODE", "SURC"));
    layoverRegistry.register(
        "train-b",
        "surc:s:ppk:2",
        NodeId.of("SURC:S:PPK:2"),
        now.minusSeconds(240),
        Map.of("FTA_OPERATOR_CODE", "SURC"));

    ReclaimManager manager =
        new ReclaimManager(
            plugin, layoverRegistry, ticketAssigner, mockConfigManager(), null, () -> 0);

    manager.performReclaimCheck();

    verify(ticketAssigner, times(1)).forceAssign(eq(provider), eq("train-a"), any());
    verify(ticketAssigner, never()).forceAssign(eq(provider), eq("train-b"), any());
  }

  @Test
  void performReclaimCheckRetriesRenamedCandidateWithStableTicketId() {
    Instant now = Instant.now();
    UUID routeId = UUID.randomUUID();
    UUID stationId = UUID.randomUUID();
    StorageProvider provider = mockProvider(routeId, stationId);
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    StorageManager storageManager = mock(StorageManager.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(storageManager.provider()).thenReturn(Optional.of(provider));
    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register(
        "train-a",
        "surc:s:ppk:1",
        NodeId.of("SURC:S:PPK:1"),
        now.minusSeconds(30),
        Map.of(
            "FTA_OPERATOR_CODE", "SURC",
            "FTA_OP_TRIPS", "4",
            "FTA_OP_MAX", "4"));
    TicketAssigner ticketAssigner = mock(TicketAssigner.class);
    when(ticketAssigner.snapshotPendingTickets()).thenReturn(List.of());
    List<String> ticketIds = new ArrayList<>();
    when(ticketAssigner.forceAssign(eq(provider), any(), any(ServiceTicket.class)))
        .thenAnswer(
            invocation -> {
              String trainName = invocation.getArgument(1);
              ServiceTicket ticket = invocation.getArgument(2);
              ticketIds.add(ticket.ticketId());
              if (ticketIds.size() == 1) {
                layoverRegistry
                    .claimDispatch(trainName, ticket.ticketId(), "train-renamed", Instant.now())
                    .orElseThrow();
                layoverRegistry.rename(trainName, "train-renamed");
                layoverRegistry.register(
                    "train-renamed",
                    "surc:s:ppk:2",
                    NodeId.of("SURC:S:PPK:2"),
                    now.minusSeconds(10),
                    Map.of(
                        "FTA_OPERATOR_CODE", "SURC",
                        "FTA_OP_TRIPS", "4",
                        "FTA_OP_MAX", "4"));
                return false;
              }
              return true;
            });
    ReclaimManager manager =
        new ReclaimManager(
            plugin, layoverRegistry, ticketAssigner, mockConfigManager(), null, () -> 0);

    manager.performReclaimCheck();
    manager.performReclaimCheck();

    assertEquals(2, ticketIds.size());
    assertEquals(ticketIds.get(0), ticketIds.get(1));
    verify(ticketAssigner).forceAssign(eq(provider), eq("train-a"), any(ServiceTicket.class));
    verify(ticketAssigner).forceAssign(eq(provider), eq("train-renamed"), any(ServiceTicket.class));
  }

  @Test
  void performReclaimCheckDoesNotShareTicketBetweenTrainsAtSameTerminalAndTime() {
    Instant readyAt = Instant.now().minusSeconds(30);
    UUID routeId = UUID.randomUUID();
    UUID stationId = UUID.randomUUID();
    StorageProvider provider = mockProvider(routeId, stationId);
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    StorageManager storageManager = mock(StorageManager.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(storageManager.provider()).thenReturn(Optional.of(provider));
    LayoverRegistry layoverRegistry = new LayoverRegistry();
    Map<String, String> tags =
        Map.of(
            "FTA_OPERATOR_CODE", "SURC",
            "FTA_OP_TRIPS", "4",
            "FTA_OP_MAX", "4");
    layoverRegistry.register("train-a", "surc:s:ppk:1", NodeId.of("SURC:S:PPK:1"), readyAt, tags);
    layoverRegistry.register("train-b", "surc:s:ppk:1", NodeId.of("SURC:S:PPK:1"), readyAt, tags);
    TicketAssigner ticketAssigner = mock(TicketAssigner.class);
    when(ticketAssigner.snapshotPendingTickets()).thenReturn(List.of());
    List<String> ticketIds = new ArrayList<>();
    when(ticketAssigner.forceAssign(eq(provider), any(), any(ServiceTicket.class)))
        .thenAnswer(
            invocation -> {
              String trainName = invocation.getArgument(1);
              ServiceTicket ticket = invocation.getArgument(2);
              ticketIds.add(ticket.ticketId());
              layoverRegistry.claimDispatch(
                  trainName, ticket.ticketId(), trainName + "-returning", Instant.now());
              return false;
            });
    ReclaimManager manager =
        new ReclaimManager(
            plugin, layoverRegistry, ticketAssigner, mockConfigManager(), null, () -> 0);

    manager.performReclaimCheck();

    assertEquals(2, ticketIds.size());
    assertEquals(2L, ticketIds.stream().distinct().count());
  }

  @Test
  void performReclaimCheckDoesNotHijackForeignDispatchAttempt() {
    Instant now = Instant.now();
    UUID routeId = UUID.randomUUID();
    UUID stationId = UUID.randomUUID();
    StorageProvider provider = mockProvider(routeId, stationId);
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    StorageManager storageManager = mock(StorageManager.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(storageManager.provider()).thenReturn(Optional.of(provider));
    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register(
        "train-operation",
        "surc:s:ppk:1",
        NodeId.of("SURC:S:PPK:1"),
        now.minusSeconds(30),
        Map.of(
            "FTA_OPERATOR_CODE", "SURC",
            "FTA_OP_TRIPS", "4",
            "FTA_OP_MAX", "4"));
    layoverRegistry
        .claimDispatch(
            "train-operation", "operation-ticket", "train-operation-returning", Instant.now())
        .orElseThrow();
    TicketAssigner ticketAssigner = mock(TicketAssigner.class);
    when(ticketAssigner.snapshotPendingTickets()).thenReturn(List.of());
    ReclaimManager manager =
        new ReclaimManager(
            plugin, layoverRegistry, ticketAssigner, mockConfigManager(), null, () -> 0);

    manager.performReclaimCheck();

    verify(ticketAssigner, never()).forceAssign(any(), any(), any());
  }

  @Test
  void performReclaimCheckResolvesDuplicateOperatorCodeThroughRouteIdentity() {
    Instant now = Instant.now();
    UUID routeId = UUID.randomUUID();
    UUID stationId = UUID.randomUUID();
    StorageProvider provider = mockProvider(routeId, stationId);
    Company correctCompany = provider.companies().listAll().get(0);
    Company otherCompany = mock(Company.class);
    Operator otherOperator = mock(Operator.class);
    UUID otherCompanyId = UUID.randomUUID();
    UUID otherOperatorId = UUID.randomUUID();
    when(otherCompany.id()).thenReturn(otherCompanyId);
    when(otherOperator.id()).thenReturn(otherOperatorId);
    when(otherOperator.code()).thenReturn("SURC");
    when(provider.companies().listAll()).thenReturn(List.of(otherCompany, correctCompany));
    when(provider.operators().findByCompanyAndCode(otherCompanyId, "SURC"))
        .thenReturn(Optional.of(otherOperator));
    when(provider.lines().listByOperator(otherOperatorId)).thenReturn(List.of());
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    StorageManager storageManager = mock(StorageManager.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(storageManager.provider()).thenReturn(Optional.of(provider));
    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register(
        "route-owned",
        "surc:s:ppk:1",
        NodeId.of("SURC:S:PPK:1"),
        now.minusSeconds(30),
        Map.of(
            "FTA_OPERATOR_CODE", "SURC",
            "FTA_ROUTE_ID", routeId.toString(),
            "FTA_OP_TRIPS", "4",
            "FTA_OP_MAX", "4"));
    TicketAssigner ticketAssigner = mock(TicketAssigner.class);
    when(ticketAssigner.snapshotPendingTickets()).thenReturn(List.of());
    when(ticketAssigner.forceAssign(eq(provider), eq("route-owned"), any())).thenReturn(true);
    ReclaimManager manager =
        new ReclaimManager(
            plugin, layoverRegistry, ticketAssigner, mockConfigManager(), null, () -> 0);

    manager.performReclaimCheck();

    verify(ticketAssigner).forceAssign(eq(provider), eq("route-owned"), any(ServiceTicket.class));
  }

  @Test
  void performReclaimCheckFailsClosedForAmbiguousOperatorCodeWithoutRouteIdentity() {
    Instant now = Instant.now();
    UUID routeId = UUID.randomUUID();
    UUID stationId = UUID.randomUUID();
    StorageProvider provider = mockProvider(routeId, stationId);
    Company correctCompany = provider.companies().listAll().get(0);
    Company otherCompany = mock(Company.class);
    Operator otherOperator = mock(Operator.class);
    UUID otherCompanyId = UUID.randomUUID();
    when(otherCompany.id()).thenReturn(otherCompanyId);
    when(otherOperator.code()).thenReturn("SURC");
    when(provider.companies().listAll()).thenReturn(List.of(correctCompany, otherCompany));
    when(provider.operators().findByCompanyAndCode(otherCompanyId, "SURC"))
        .thenReturn(Optional.of(otherOperator));
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    StorageManager storageManager = mock(StorageManager.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(storageManager.provider()).thenReturn(Optional.of(provider));
    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register(
        "ambiguous",
        "surc:s:ppk:1",
        NodeId.of("SURC:S:PPK:1"),
        now.minusSeconds(30),
        Map.of(
            "FTA_OPERATOR_CODE", "SURC",
            "FTA_OP_TRIPS", "4",
            "FTA_OP_MAX", "4"));
    TicketAssigner ticketAssigner = mock(TicketAssigner.class);
    when(ticketAssigner.snapshotPendingTickets()).thenReturn(List.of());
    ReclaimManager manager =
        new ReclaimManager(
            plugin, layoverRegistry, ticketAssigner, mockConfigManager(), null, () -> 0);

    manager.performReclaimCheck();

    verify(ticketAssigner, never()).forceAssign(any(), any(), any());
  }

  @Test
  void performReclaimCheckTriesNextReturnRouteWhenFirstRejectsBeforeAttempt() {
    Instant now = Instant.now();
    UUID firstRouteId = UUID.randomUUID();
    UUID secondRouteId = UUID.randomUUID();
    UUID stationId = UUID.randomUUID();
    StorageProvider provider = mockProvider(firstRouteId, stationId);
    Company company = provider.companies().listAll().get(0);
    Operator operator =
        provider.operators().findByCompanyAndCode(company.id(), "SURC").orElseThrow();
    Line line = provider.lines().listByOperator(operator.id()).get(0);
    Route firstRoute = provider.routes().listByLine(line.id()).get(0);
    Route secondRoute =
        new Route(
            secondRouteId,
            "MT-RET-2",
            line.id(),
            "Return 2",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.RETURN,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            now,
            now);
    RouteStop secondFirstStop =
        new RouteStop(
            secondRouteId,
            0,
            Optional.of(stationId),
            Optional.empty(),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());
    when(provider.routes().listByLine(line.id())).thenReturn(List.of(firstRoute, secondRoute));
    when(provider.routeStops().listByRoute(secondRouteId)).thenReturn(List.of(secondFirstStop));

    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    StorageManager storageManager = mock(StorageManager.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(storageManager.provider()).thenReturn(Optional.of(provider));
    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register(
        "train-a",
        "surc:s:ppk:1",
        NodeId.of("SURC:S:PPK:1"),
        now.minusSeconds(30),
        Map.of(
            "FTA_OPERATOR_CODE", "SURC",
            "FTA_OP_TRIPS", "4",
            "FTA_OP_MAX", "4"));
    TicketAssigner ticketAssigner = mock(TicketAssigner.class);
    when(ticketAssigner.snapshotPendingTickets()).thenReturn(List.of());
    List<UUID> attemptedRoutes = new ArrayList<>();
    when(ticketAssigner.forceAssign(eq(provider), eq("train-a"), any(ServiceTicket.class)))
        .thenAnswer(
            invocation -> {
              ServiceTicket ticket = invocation.getArgument(2);
              attemptedRoutes.add(ticket.routeId());
              return secondRouteId.equals(ticket.routeId());
            });
    ReclaimManager manager =
        new ReclaimManager(
            plugin, layoverRegistry, ticketAssigner, mockConfigManager(), null, () -> 0);

    manager.performReclaimCheck();

    assertEquals(List.of(firstRouteId, secondRouteId), attemptedRoutes);
  }

  /** 终点有两条回库线路：先试列车所绑交路的那一条，回到按表该回的车库。 */
  @Test
  void performReclaimCheckTriesTheDutysOwnReturnRouteFirst() {
    Instant now = Instant.now();
    UUID firstRouteId = UUID.randomUUID();
    UUID secondRouteId = UUID.randomUUID();
    UUID stationId = UUID.randomUUID();
    StorageProvider provider = mockProvider(firstRouteId, stationId);
    Company company = provider.companies().listAll().get(0);
    Operator operator =
        provider.operators().findByCompanyAndCode(company.id(), "SURC").orElseThrow();
    Line line = provider.lines().listByOperator(operator.id()).get(0);
    Route firstRoute = provider.routes().listByLine(line.id()).get(0);
    Route secondRoute =
        new Route(
            secondRouteId,
            "MT-RET-2",
            line.id(),
            "Return 2",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.RETURN,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            now,
            now);
    when(provider.routes().listByLine(line.id())).thenReturn(List.of(firstRoute, secondRoute));
    when(provider.routeStops().listByRoute(secondRouteId))
        .thenReturn(
            List.of(
                new RouteStop(
                    secondRouteId,
                    0,
                    Optional.of(stationId),
                    Optional.empty(),
                    Optional.empty(),
                    RouteStopPassType.STOP,
                    Optional.empty())));

    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    StorageManager storageManager = mock(StorageManager.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(storageManager.provider()).thenReturn(Optional.of(provider));
    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register(
        "train-a",
        "surc:s:ppk:1",
        NodeId.of("SURC:S:PPK:1"),
        now.minusSeconds(30),
        Map.of(
            "FTA_OPERATOR_CODE", "SURC",
            "FTA_OP_TRIPS", "4",
            "FTA_OP_MAX", "4"));
    TicketAssigner ticketAssigner = mock(TicketAssigner.class);
    when(ticketAssigner.snapshotPendingTickets()).thenReturn(List.of());
    List<UUID> attemptedRoutes = new ArrayList<>();
    when(ticketAssigner.forceAssign(eq(provider), eq("train-a"), any(ServiceTicket.class)))
        .thenAnswer(
            invocation -> {
              ServiceTicket ticket = invocation.getArgument(2);
              attemptedRoutes.add(ticket.routeId());
              return true;
            });
    ReclaimManager manager =
        new ReclaimManager(
            plugin, layoverRegistry, ticketAssigner, mockConfigManager(), null, () -> 0);
    manager.setPreferredReturnRoute(
        trainName -> "train-a".equals(trainName) ? Optional.of(secondRouteId) : Optional.empty());
    List<String> reclaimed = new ArrayList<>();
    manager.setReclaimListener((trainName, routeId) -> reclaimed.add(trainName + "@" + routeId));

    manager.performReclaimCheck();

    assertEquals(List.of(secondRouteId), attemptedRoutes);
    assertEquals(List.of("train-a@" + secondRouteId), reclaimed, "派走后通知时刻表结清交路");
  }

  /**
   * 等自己交路带客回库班的车：不回收，也不算本方向的闲置供给——否则它把同方向另一辆车推成“过剩”，被收走的是那一辆。
   *
   * <p>PPK 两辆待命车，train-a 在等回库班：可供给的只剩 train-b 一辆，不过剩，谁也不收。
   */
  @Test
  void performReclaimCheckLeavesTrainsWaitingForTheirOwnReturnLeg() {
    Instant now = Instant.now();
    UUID routeId = UUID.randomUUID();
    UUID stationId = UUID.randomUUID();
    StorageProvider provider = mockProvider(routeId, stationId);
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    StorageManager storageManager = mock(StorageManager.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(storageManager.provider()).thenReturn(Optional.of(provider));
    TicketAssigner ticketAssigner = mock(TicketAssigner.class);
    when(ticketAssigner.snapshotPendingTickets()).thenReturn(List.of());
    when(ticketAssigner.forceAssign(eq(provider), any(), any())).thenReturn(true);
    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register(
        "train-a",
        "surc:s:ppk:1",
        NodeId.of("SURC:S:PPK:1"),
        now.minusSeconds(400),
        Map.of("FTA_OPERATOR_CODE", "SURC"));
    layoverRegistry.register(
        "train-b",
        "surc:s:ppk:2",
        NodeId.of("SURC:S:PPK:2"),
        now.minusSeconds(300),
        Map.of("FTA_OPERATOR_CODE", "SURC"));
    ReclaimManager manager =
        new ReclaimManager(
            plugin, layoverRegistry, ticketAssigner, mockConfigManager(), null, () -> 0);
    manager.setOwnReturnWait((trainName, location) -> "train-a".equals(trainName));

    manager.performReclaimCheck();

    verify(ticketAssigner, never()).forceAssign(any(), any(), any());
  }

  @Test
  void activeTrainCountHandlesEmptyAndMissingGroups() {
    List<MinecartGroup> groups = new ArrayList<>();
    groups.add(null);

    assertEquals(0, ReclaimManager.countActiveGroups(groups));
    assertEquals(0, ReclaimManager.countActiveGroups(null));
  }

  /**
   * 兜底：泛用回收（这里是闲置超时）派不出 RETURN 票的车，滞留超过阈值就销毁；有乘客的不碰。
   *
   * <p>train-a 停在没有任何 RETURN 线路能出发的终点，闲置早已超时，但时刻表没有放行它（没装立即回收闸）：闲置超时不知道它还有没有班可跑，
   * 第一轮记下滞留起点，阈值到了才销毁；同一场景换成有乘客的车，永远不销毁。
   */
  @Test
  void performReclaimCheckDestroysStrandedTrainAfterThresholdUnlessPassengers() {
    Instant t0 = Instant.parse("2026-03-01T08:00:00Z");
    UUID routeId = UUID.randomUUID();
    UUID stationId = UUID.randomUUID();
    StorageProvider provider = mockProvider(routeId, stationId);
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    StorageManager storageManager = mock(StorageManager.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(storageManager.provider()).thenReturn(Optional.of(provider));
    TicketAssigner ticketAssigner = mock(TicketAssigner.class);
    when(ticketAssigner.snapshotPendingTickets()).thenReturn(List.of());

    LayoverRegistry layoverRegistry = new LayoverRegistry();
    // 终点 ZZZ 没有任何 RETURN 线路从这里出发。
    layoverRegistry.register(
        "train-a",
        "surn:s:zzz:1",
        NodeId.of("SURN:S:ZZZ:1"),
        t0.minusSeconds(4000),
        Map.of("FTA_OPERATOR_CODE", "SURC"));
    List<String> destroyed = new java.util.ArrayList<>();
    java.util.concurrent.atomic.AtomicReference<Instant> clock =
        new java.util.concurrent.atomic.AtomicReference<>(t0);
    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView view = mock(ConfigManager.ConfigView.class);
    when(configManager.current()).thenReturn(view);
    when(view.reclaimSettings())
        .thenReturn(new ConfigManager.ReclaimSettings(true, 3600, 100, 60, 600));
    ReclaimManager manager =
        new ReclaimManager(
            plugin,
            layoverRegistry,
            ticketAssigner,
            configManager,
            null,
            () -> 0,
            trainName -> false,
            (trainName, reason) -> destroyed.add(trainName + ":" + reason),
            clock::get);

    manager.performReclaimCheck();
    assertTrue(destroyed.isEmpty(), "第一轮只记滞留起点");
    clock.set(t0.plusSeconds(599));
    manager.performReclaimCheck();
    assertTrue(destroyed.isEmpty(), "未到阈值");
    clock.set(t0.plusSeconds(600));
    manager.performReclaimCheck();
    assertEquals(List.of("train-a:reclaim-stranded"), destroyed);
    verify(ticketAssigner, never()).forceAssign(any(), any(), any());

    // 有乘客：同样滞留同样超阈值，但不销毁。
    LayoverRegistry withPassengers = new LayoverRegistry();
    withPassengers.register(
        "train-p",
        "surn:s:zzz:1",
        NodeId.of("SURN:S:ZZZ:1"),
        t0.minusSeconds(4000),
        Map.of("FTA_OPERATOR_CODE", "SURC"));
    List<String> destroyedWithPassengers = new java.util.ArrayList<>();
    ReclaimManager guarded =
        new ReclaimManager(
            plugin,
            withPassengers,
            ticketAssigner,
            configManager,
            null,
            () -> 0,
            trainName -> true,
            (trainName, reason) -> destroyedWithPassengers.add(trainName),
            clock::get);
    guarded.performReclaimCheck();
    clock.set(t0.plusSeconds(5000));
    guarded.performReclaimCheck();
    assertTrue(destroyedWithPassengers.isEmpty(), "载客的车不能被兜底销毁");
  }

  /**
   * 回库闸：交路还有班次要跑的车不回收，也不算滞留；闸放行后照常回收。
   *
   * <p>回收以前绕过时刻表，闲置超时、车辆超限、方向供给过剩都能把正等着下一班的车送回车库，那一班就开了天窗。
   */
  @Test
  void performReclaimCheckLeavesTrainsWithRemainingTripsToTheTimetable() {
    Instant t0 = Instant.parse("2026-03-01T08:00:00Z");
    UUID routeId = UUID.randomUUID();
    UUID stationId = UUID.randomUUID();
    StorageProvider provider = mockProvider(routeId, stationId);
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    StorageManager storageManager = mock(StorageManager.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(storageManager.provider()).thenReturn(Optional.of(provider));
    TicketAssigner ticketAssigner = mock(TicketAssigner.class);
    when(ticketAssigner.snapshotPendingTickets()).thenReturn(List.of());
    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register(
        "train-a",
        "surc:s:ppk:1",
        NodeId.of("SURC:S:PPK:1"),
        t0.minusSeconds(4000),
        Map.of("FTA_OPERATOR_CODE", "SURC"));
    java.util.concurrent.atomic.AtomicReference<Instant> clock =
        new java.util.concurrent.atomic.AtomicReference<>(t0);
    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView view = mock(ConfigManager.ConfigView.class);
    when(configManager.current()).thenReturn(view);
    when(view.reclaimSettings())
        .thenReturn(new ConfigManager.ReclaimSettings(true, 3600, 100, 60, 600));
    List<String> destroyed = new java.util.ArrayList<>();
    ReclaimManager manager =
        new ReclaimManager(
            plugin,
            layoverRegistry,
            ticketAssigner,
            configManager,
            null,
            () -> 0,
            trainName -> false,
            (trainName, reason) -> destroyed.add(trainName),
            clock::get);
    java.util.concurrent.atomic.AtomicBoolean dutyFinished =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    manager.setReturnGate(trainName -> dutyFinished.get());

    manager.performReclaimCheck();
    clock.set(t0.plusSeconds(5000));
    manager.performReclaimCheck();

    verify(ticketAssigner, never()).forceAssign(any(), any(), any());
    assertTrue(destroyed.isEmpty(), "交路还有班次不是派不出回库票，不能按滞留销毁");

    dutyFinished.set(true);
    manager.performReclaimCheck();

    verify(ticketAssigner, times(1)).forceAssign(eq(provider), eq("train-a"), any());
  }

  /**
   * 挂起过久的折返交接只告警、不释放：认领之后交接可能已经改动了占用，只能由同一张票重试完成。
   *
   * <p>同一次认领只告警一次；释放后重新认领算新的一次，挂满阈值会再告警。
   */
  @Test
  void staleDispatchAttemptIsReportedOncePerClaimAndNeverReleased() {
    Instant t0 = Instant.parse("2026-03-01T08:00:00Z");
    java.util.concurrent.atomic.AtomicReference<Instant> clock =
        new java.util.concurrent.atomic.AtomicReference<>(t0);
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    StorageManager storageManager = mock(StorageManager.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(storageManager.provider()).thenReturn(Optional.empty());
    TicketAssigner ticketAssigner = mock(TicketAssigner.class);
    when(ticketAssigner.snapshotPendingTickets()).thenReturn(List.of());
    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register(
        "train-a",
        "surc:s:ppk:1",
        NodeId.of("SURC:S:PPK:1"),
        t0,
        Map.of("FTA_OPERATOR_CODE", "SURC"));
    layoverRegistry.claimDispatch("train-a", "ticket-1", "train-a-next", t0).orElseThrow();
    List<String> logs = new ArrayList<>();
    ReclaimManager manager =
        new ReclaimManager(
            plugin,
            layoverRegistry,
            ticketAssigner,
            mockConfigManager(),
            logs::add,
            () -> 0,
            trainName -> false,
            (trainName, reason) -> false,
            clock::get);

    clock.set(t0.plusSeconds(ReclaimManager.STALE_DISPATCH_ATTEMPT_SECONDS - 1));
    manager.performReclaimCheck();
    clock.set(t0.plusSeconds(130));
    manager.performReclaimCheck();
    manager.performReclaimCheck();

    assertEquals(1, staleReports(logs), logs::toString);
    assertTrue(logs.stream().anyMatch(line -> line.contains("ageSeconds=130")), logs::toString);
    assertTrue(layoverRegistry.hasDispatchAttemptForTicket("ticket-1"), "只告警，不释放做到一半的交接");

    // 这次交接结束（认领释放），同一张票稍后重新认领：是新的一次，挂满阈值再告警。
    layoverRegistry.releaseDispatchAttempt("train-a", "ticket-1");
    manager.performReclaimCheck();
    Instant reclaimedAt = t0.plusSeconds(200);
    layoverRegistry.claimDispatch("train-a", "ticket-1", "train-a-next", reclaimedAt).orElseThrow();
    clock.set(reclaimedAt.plusSeconds(ReclaimManager.STALE_DISPATCH_ATTEMPT_SECONDS));
    manager.performReclaimCheck();

    assertEquals(2, staleReports(logs), logs::toString);
  }

  private static long staleReports(List<String> logs) {
    return logs.stream().filter(line -> line.startsWith("RECLAIM_DISPATCH_ATTEMPT_STALE")).count();
  }

  /** 直通车滞留在别的运营商的终点：本运营商没有从那里出发的 RETURN，外方有一条首站写裸节点 id 的 RETURN——要认得出并派给它。 */
  @Test
  void performReclaimCheckFallsBackToForeignOperatorReturnRouteMatchedByNodeId() {
    Instant now = Instant.now();
    UUID ownRouteId = UUID.randomUUID();
    UUID stationId = UUID.randomUUID();
    StorageProvider provider = mockProvider(ownRouteId, stationId);
    UUID foreignRouteId = addForeignOperatorReturn(provider);

    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    StorageManager storageManager = mock(StorageManager.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(storageManager.provider()).thenReturn(Optional.of(provider));
    TicketAssigner ticketAssigner = mock(TicketAssigner.class);
    when(ticketAssigner.snapshotPendingTickets()).thenReturn(List.of());
    when(ticketAssigner.forceAssign(eq(provider), eq("train-thru"), any())).thenReturn(true);
    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register(
        "train-thru",
        "surn:s:xxx:2",
        NodeId.of("SURN:S:XXX:2"),
        now.minusSeconds(4000),
        Map.of("FTA_OPERATOR_CODE", "SURC"));
    ReclaimManager manager =
        new ReclaimManager(
            plugin, layoverRegistry, ticketAssigner, mockConfigManager(), null, () -> 0);

    manager.performReclaimCheck();

    org.mockito.ArgumentCaptor<ServiceTicket> captor =
        org.mockito.ArgumentCaptor.forClass(ServiceTicket.class);
    verify(ticketAssigner).forceAssign(eq(provider), eq("train-thru"), captor.capture());
    assertEquals(foreignRouteId, captor.getValue().routeId(), "应当派给外方的 RETURN（同站不同股道也算匹配）");
  }

  @Test
  void performReclaimCheckUsesRouteOperatorAfterCrossOperatorChange() {
    // 直通车在 SURC 交路上经 CHANGE:SURN:NL 换线：运营商标签被改写成 SURN，交路仍归 SURC 管理。
    // 修复前两者不一致即放弃回收，滞留在外方终点的直通车永远回不了库。
    Instant now = Instant.now();
    UUID ownRouteId = UUID.randomUUID();
    StorageProvider provider = mockProvider(ownRouteId, UUID.randomUUID());
    UUID foreignRouteId = addForeignOperatorReturn(provider);
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    StorageManager storageManager = mock(StorageManager.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(storageManager.provider()).thenReturn(Optional.of(provider));
    TicketAssigner ticketAssigner = mock(TicketAssigner.class);
    when(ticketAssigner.snapshotPendingTickets()).thenReturn(List.of());
    when(ticketAssigner.forceAssign(eq(provider), eq("train-thru"), any())).thenReturn(true);
    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register(
        "train-thru",
        "surn:s:xxx:2",
        NodeId.of("SURN:S:XXX:2"),
        now.minusSeconds(4000),
        Map.of("FTA_OPERATOR_CODE", "SURN", "FTA_ROUTE_ID", ownRouteId.toString()));
    List<String> logs = new ArrayList<>();
    ReclaimManager manager =
        new ReclaimManager(
            plugin, layoverRegistry, ticketAssigner, mockConfigManager(), logs::add, () -> 0);

    manager.performReclaimCheck();

    org.mockito.ArgumentCaptor<ServiceTicket> captor =
        org.mockito.ArgumentCaptor.forClass(ServiceTicket.class);
    verify(ticketAssigner).forceAssign(eq(provider), eq("train-thru"), captor.capture());
    assertEquals(foreignRouteId, captor.getValue().routeId(), "先搜交路所属的 SURC，再搜外方 SURN 的 RETURN");
    assertTrue(logs.stream().noneMatch(line -> line.contains("不一致")), "换线后的运营商标签不再让回收失败");
    assertTrue(logs.stream().anyMatch(line -> line.contains("按交路归属运营商 SURC")));
  }

  @Test
  void performReclaimCheckUsesRouteOperatorWhenOperatorTagIsMissing() {
    Instant now = Instant.now();
    UUID routeId = UUID.randomUUID();
    StorageProvider provider = mockProvider(routeId, UUID.randomUUID());
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    StorageManager storageManager = mock(StorageManager.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(storageManager.provider()).thenReturn(Optional.of(provider));
    TicketAssigner ticketAssigner = mock(TicketAssigner.class);
    when(ticketAssigner.snapshotPendingTickets()).thenReturn(List.of());
    when(ticketAssigner.forceAssign(eq(provider), eq("route-only"), any())).thenReturn(true);
    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register(
        "route-only",
        "surc:s:ppk:1",
        NodeId.of("SURC:S:PPK:1"),
        now.minusSeconds(30),
        Map.of("FTA_ROUTE_ID", routeId.toString(), "FTA_OP_TRIPS", "4", "FTA_OP_MAX", "4"));
    ReclaimManager manager =
        new ReclaimManager(
            plugin, layoverRegistry, ticketAssigner, mockConfigManager(), null, () -> 0);

    manager.performReclaimCheck();

    verify(ticketAssigner).forceAssign(eq(provider), eq("route-only"), any(ServiceTicket.class));
  }

  @Test
  void performReclaimCheckRefusesWhenRouteIdNoLongerResolves() {
    // 有交路 ID 却回溯不到（交路已删除）：运营商标签可能是换线后的外方运营商，不能拿它顶替管理归属。
    Instant now = Instant.now();
    StorageProvider provider = mockProvider(UUID.randomUUID(), UUID.randomUUID());
    FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
    StorageManager storageManager = mock(StorageManager.class);
    when(plugin.getStorageManager()).thenReturn(storageManager);
    when(storageManager.provider()).thenReturn(Optional.of(provider));
    TicketAssigner ticketAssigner = mock(TicketAssigner.class);
    when(ticketAssigner.snapshotPendingTickets()).thenReturn(List.of());
    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register(
        "orphan",
        "surc:s:ppk:1",
        NodeId.of("SURC:S:PPK:1"),
        now.minusSeconds(30),
        Map.of(
            "FTA_OPERATOR_CODE", "SURC",
            "FTA_ROUTE_ID", UUID.randomUUID().toString(),
            "FTA_OP_TRIPS", "4",
            "FTA_OP_MAX", "4"));
    List<String> logs = new ArrayList<>();
    ReclaimManager manager =
        new ReclaimManager(
            plugin, layoverRegistry, ticketAssigner, mockConfigManager(), logs::add, () -> 0);

    manager.performReclaimCheck();

    verify(ticketAssigner, never()).forceAssign(any(), any(), any());
    assertTrue(logs.stream().anyMatch(line -> line.contains("FTA_ROUTE_ID 回溯不到运营商")));
  }

  /**
   * 正线折返点上没有后续班次可接的车：闲置过一个短门槛就原地销毁，不等闲置上限，也不看站台上那道回库闸。
   *
   * <p>实服 MT-1O_ShortR 终到 {@code OFL:MLU:2:004}（正线 2 股上的路径点），车停在那里挡着同股道的后车；不倒车能到的车库在 2000 格外，
   * 开过去等于往已经晚点的干线里塞一趟表外车。
   */
  @Test
  void mainlineTurnbackWithoutAContinuationIsDestroyedInPlace() {
    MainlineFixture fixture = new MainlineFixture(MainlineFixture.MAINLINE_TURNBACK);
    List<Optional<UUID>> gatedRoutes = new ArrayList<>();
    fixture.manager.setMainlineReturnGate(
        (train, route) -> gatedRoutes.add(route) && "train-a".equals(train));
    fixture.manager.setReturnGate(train -> false);

    fixture.checkAfterIdle(ReclaimManager.MAINLINE_TURNBACK_MIN_IDLE_SECONDS - 1);
    assertTrue(fixture.destroyed.isEmpty(), "刚到时自己的下一班票可能还在路上");

    fixture.checkAfterIdle(ReclaimManager.MAINLINE_TURNBACK_MIN_IDLE_SECONDS);
    assertEquals(List.of("train-a:reclaim-mainline-turnback"), fixture.destroyed);
    assertTrue(
        fixture.logs.stream()
            .anyMatch(line -> line.startsWith("RECLAIM_MAINLINE_DESTROY train=train-a")),
        fixture.logs::toString);
    assertEquals(List.of(Optional.of(fixture.shortRoute)), gatedRoutes, "闸拿到的是车刚跑完的交路");
  }

  /** 有从折返点出发的 RETURN 交路（人工定义）时照它开走，不销毁。 */
  @Test
  void aReturnRouteFromTheTurnbackIsPreferredToDestroying() {
    MainlineFixture fixture =
        new MainlineFixture(MainlineFixture.MAINLINE_TURNBACK, true, false, 600);
    fixture.manager.setMainlineReturnGate((train, route) -> true);

    fixture.checkAfterIdle(ReclaimManager.MAINLINE_TURNBACK_MIN_IDLE_SECONDS);

    org.mockito.ArgumentCaptor<ServiceTicket> captor =
        org.mockito.ArgumentCaptor.forClass(ServiceTicket.class);
    verify(fixture.ticketAssigner)
        .forceAssign(eq(fixture.provider), eq("train-a"), captor.capture());
    assertEquals(fixture.mainlineReturnRoute, captor.getValue().routeId());
    assertTrue(fixture.destroyed.isEmpty());
  }

  /**
   * 首站写站 code 的 RETURN 只认停在那个站的车：区间路径点 {@code PPK:RVS:1:001} 名字里带 PPK，车并不在 PPK。
   *
   * <p>修复前按冒号拆段逐段比，会把车派上一条从它不在的车站出发的回库线。
   */
  @Test
  void aStationReturnDoesNotMatchAnIntervalPointNamedAfterIt() {
    MainlineFixture fixture = new MainlineFixture(NodeId.of("SURC:PPK:RVS:1:001"));
    fixture.manager.setMainlineReturnGate((train, route) -> true);

    fixture.checkAfterIdle(ReclaimManager.MAINLINE_TURNBACK_MIN_IDLE_SECONDS);

    verify(fixture.ticketAssigner, never()).forceAssign(any(), any(), any());
    assertEquals(List.of("train-a:reclaim-mainline-turnback"), fixture.destroyed);
  }

  /** 车上有乘客不销毁；{@code stranded-destroy-seconds} 为 0（回收不销毁车）时也不销毁。 */
  @Test
  void passengersAndTheDestroySwitchKeepTheTurnbackTrain() {
    MainlineFixture withPassengers =
        new MainlineFixture(MainlineFixture.MAINLINE_TURNBACK, false, true, 600);
    withPassengers.manager.setMainlineReturnGate((train, route) -> true);
    withPassengers.checkAfterIdle(ReclaimManager.MAINLINE_TURNBACK_MIN_IDLE_SECONDS);
    assertTrue(withPassengers.destroyed.isEmpty());
    assertTrue(
        withPassengers.logs.contains("RECLAIM_MAINLINE_SKIP train=train-a reason=has-passengers"),
        withPassengers.logs::toString);

    MainlineFixture destroyDisabled =
        new MainlineFixture(MainlineFixture.MAINLINE_TURNBACK, false, false, 0);
    destroyDisabled.manager.setMainlineReturnGate((train, route) -> true);
    destroyDisabled.checkAfterIdle(ReclaimManager.MAINLINE_TURNBACK_MIN_IDLE_SECONDS);
    assertTrue(destroyDisabled.destroyed.isEmpty());
  }

  /** 正线闸不放行（下一班还接得上）时，闲置超时仍要过站台那道回库闸，不能因为停在正线上就绕过去。 */
  @Test
  void aDeniedMainlineGateStillHonoursTheReturnGate() {
    MainlineFixture fixture =
        new MainlineFixture(MainlineFixture.MAINLINE_TURNBACK, true, false, 600);
    fixture.manager.setMainlineReturnGate((train, route) -> false);
    java.util.concurrent.atomic.AtomicBoolean dutyFinished =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    fixture.manager.setReturnGate(train -> dutyFinished.get());

    fixture.checkAfterIdle(4000);
    verify(fixture.ticketAssigner, never()).forceAssign(any(), any(), any());

    dutyFinished.set(true);
    fixture.checkAfterIdle(4001);
    verify(fixture.ticketAssigner, times(1))
        .forceAssign(eq(fixture.provider), eq("train-a"), any());
    assertTrue(fixture.destroyed.isEmpty());
  }

  /** 没装正线闸（不按表运行）时不提前回收：没有"接哪一班"的对应关系，照旧等闲置上限。 */
  @Test
  void withoutAMainlineGateTheTurnbackWaitsForTheIdleLimit() {
    MainlineFixture fixture = new MainlineFixture(MainlineFixture.MAINLINE_TURNBACK);

    fixture.checkAfterIdle(ReclaimManager.MAINLINE_TURNBACK_MIN_IDLE_SECONDS + 60);

    verify(fixture.ticketAssigner, never()).forceAssign(any(), any(), any());
    assertTrue(fixture.destroyed.isEmpty());
  }

  /** 站台上的车不走正线规则：正线闸放行也不提前回收、不销毁。 */
  @Test
  void aPlatformTerminalIsNotAMainlineTurnback() {
    MainlineFixture fixture = new MainlineFixture(NodeId.of("SURC:S:PPK:1"));
    fixture.manager.setMainlineReturnGate((train, route) -> true);

    fixture.checkAfterIdle(ReclaimManager.MAINLINE_TURNBACK_MIN_IDLE_SECONDS + 60);

    verify(fixture.ticketAssigner, never()).forceAssign(any(), any(), any());
    assertTrue(fixture.destroyed.isEmpty());
  }

  /** 交路已换车的车再也没有班可跑：闲置满短门槛就回收，不等闲置上限（这里是 3600 秒）。 */
  @Test
  void aRetiredVehicleIsReclaimedWithoutWaitingForTheIdleLimit() {
    MainlineFixture fixture = new MainlineFixture(NodeId.of("SURC:S:PPK:1"));
    fixture.manager.setRetiredVehicle(train -> train.equals("train-a"));

    fixture.checkAfterIdle(ReclaimManager.MAINLINE_TURNBACK_MIN_IDLE_SECONDS - 1);
    verify(fixture.ticketAssigner, never()).forceAssign(any(), any(), any());

    fixture.checkAfterIdle(ReclaimManager.MAINLINE_TURNBACK_MIN_IDLE_SECONDS);
    verify(fixture.ticketAssigner).forceAssign(eq(fixture.provider), eq("train-a"), any());
    assertTrue(
        fixture.logs.stream().anyMatch(line -> line.startsWith("回收触发: 交路已换车 train=train-a")),
        fixture.logs::toString);
  }

  /**
   * 单股道车站（CHT 只有 3 道）与正线折返点同一条规则：下一班接不上就立即回收，不占着唯一的股道等后面的车次。
   *
   * <p>2026-09-30 实服：WS 车晚点到 CHT，下一班 2C 已过容差作废，剩下的 2N 从 NTA 发车、它赶不过去；回库闸因"交路还有班次"不放， 它在唯一的股道上一直等到
   * 2N 也过期，后车全部等待放行、严重晚点 20 分钟。
   */
  @Test
  void aSingleTrackStationWithoutAContinuationIsReclaimedImmediately() {
    MainlineFixture fixture = new MainlineFixture(NodeId.of("SURC:S:CHT:3"));
    fixture.manager.setSingleTrackStation(node -> node.value().equals("SURC:S:CHT:3"));
    fixture.manager.setMainlineReturnGate((train, route) -> true);
    fixture.manager.setReturnGate(train -> false);

    fixture.checkAfterIdle(ReclaimManager.MAINLINE_TURNBACK_MIN_IDLE_SECONDS);

    assertTrue(
        fixture.logs.stream().anyMatch(line -> line.startsWith("回收触发: 单股道车站无后续班次 train=train-a")),
        fixture.logs::toString);
    assertEquals(
        List.of("train-a:reclaim-mainline-turnback"), fixture.destroyed, "没有从 CHT 出发的回库线路时原地处理");
  }

  /** 单股道车站上、下一班还接得上的车照常等：立即回收闸不放行时，站台那道回库闸照旧说了算。 */
  @Test
  void aSingleTrackStationKeepsATrainWhoseNextTripIsStillReachable() {
    MainlineFixture fixture = new MainlineFixture(NodeId.of("SURC:S:CHT:3"));
    fixture.manager.setSingleTrackStation(node -> true);
    fixture.manager.setMainlineReturnGate((train, route) -> false);
    fixture.manager.setReturnGate(train -> false);

    fixture.checkAfterIdle(4000);

    verify(fixture.ticketAssigner, never()).forceAssign(any(), any(), any());
    assertTrue(fixture.destroyed.isEmpty());
  }

  /** 有从折返点出发的 RETURN 交路、只是这一拍没派出去（闭塞、被拒）：不能断定没有回库路，不销毁，照常进滞留计时。 */
  @Test
  void aReturnRouteThatIsOnlyBlockedDoesNotDestroyTheTrain() {
    MainlineFixture fixture =
        new MainlineFixture(MainlineFixture.MAINLINE_TURNBACK, true, false, 600);
    fixture.manager.setMainlineReturnGate((train, route) -> true);
    when(fixture.ticketAssigner.forceAssign(eq(fixture.provider), eq("train-a"), any()))
        .thenReturn(false);

    fixture.checkAfterIdle(ReclaimManager.MAINLINE_TURNBACK_MIN_IDLE_SECONDS);

    verify(fixture.ticketAssigner).forceAssign(eq(fixture.provider), eq("train-a"), any());
    assertTrue(fixture.destroyed.isEmpty());
  }

  /**
   * 销毁前按待命池的当前记录判交接：这一拍的派票刚认领了交接、授权后又失败（认领保留等重试）时，扫描开头的快照里还没有它。
   *
   * <p>滞留满阈值的那一拍正好发生这件事：照快照判会把一辆挂着交接的车删掉，留下悬空 attempt。
   */
  @Test
  void aHandoffClaimedEarlierInTheSameScanBlocksTheDestroy() {
    MainlineFixture fixture = new MainlineFixture(NodeId.of("SURC:S:PPK:1"), false, false, 60);
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    when(fixture.ticketAssigner.forceAssign(eq(fixture.provider), eq("train-a"), any()))
        .thenAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 2) {
                ServiceTicket ticket = invocation.getArgument(2);
                fixture.layoverRegistry.claimDispatch(
                    "train-a", ticket.ticketId(), "train-a", Instant.EPOCH);
              }
              return false;
            });

    fixture.checkAfterIdle(4000);
    fixture.checkAfterIdle(4061);

    assertEquals(2, calls.get());
    assertTrue(fixture.destroyed.isEmpty());
    assertTrue(
        fixture.logs.stream()
            .anyMatch(
                line ->
                    line.startsWith(
                        "RECLAIM_STRANDED_SKIP train=train-a reason=dispatch-attempt-in-progress")),
        fixture.logs::toString);
  }

  /**
   * 有回库线路、却一直派不出去（被拒、闭塞）的车才进滞留计时：第一次判定起记时，满 {@code stranded-destroy-seconds} 才销毁。
   *
   * <p>有交路就可能等来：下一拍闭塞解除就能开走，不能当场删车。
   */
  @Test
  void aReturnRouteThatKeepsFailingIsDestroyedAfterTheStrandedThreshold() {
    MainlineFixture fixture = new MainlineFixture(NodeId.of("SURC:S:PPK:1"), false, false, 600);
    when(fixture.ticketAssigner.forceAssign(eq(fixture.provider), eq("train-a"), any()))
        .thenReturn(false);

    fixture.checkAfterIdle(4000);
    assertTrue(fixture.destroyed.isEmpty(), "第一次只记滞留起点");
    fixture.checkAfterIdle(4599);
    assertTrue(fixture.destroyed.isEmpty(), "未到阈值");
    fixture.checkAfterIdle(4600);

    assertEquals(List.of("train-a:reclaim-stranded"), fixture.destroyed);
  }

  /**
   * 原地折返的车站（没有一条回库线路从这里出发）与正线折返点同一条规则：时刻表放行就当场处理，不等闲置上限。
   *
   * <p>车在这样的站上只能接本交路的下一班，接不上就再也走不了；两股道的站被两辆这样的车占满，在这里折返的线路全都进不来。
   */
  @Test
  void aStationWithoutReturnRoutesIsClearedOnceTheTimetableLetsTheTrainGo() {
    MainlineFixture fixture = new MainlineFixture(NodeId.of("SURC:S:NTA:2"));
    fixture.manager.setDutyBound(train -> true);
    fixture.manager.setMainlineReturnGate((train, route) -> true);
    fixture.manager.setReturnGate(train -> false);

    fixture.checkAfterIdle(ReclaimManager.MAINLINE_TURNBACK_MIN_IDLE_SECONDS - 1);
    assertTrue(fixture.destroyed.isEmpty(), "刚到时自己的下一班票可能还在路上");

    fixture.checkAfterIdle(ReclaimManager.MAINLINE_TURNBACK_MIN_IDLE_SECONDS);

    verify(fixture.ticketAssigner, never()).forceAssign(any(), any(), any());
    assertEquals(List.of("train-a:reclaim-no-return-route"), fixture.destroyed);
    assertTrue(
        fixture.logs.stream()
            .anyMatch(line -> line.startsWith("回收触发: 无回库线路的车站无后续班次 train=train-a")),
        fixture.logs::toString);
    assertTrue(
        fixture.logs.stream()
            .anyMatch(line -> line.startsWith("RECLAIM_NO_ROUTE_DESTROY train=train-a")),
        fixture.logs::toString);
  }

  /** 原地折返的车站上、下一班的票还在等它的车照常等：时刻表不放行时，回库闸照旧说了算。 */
  @Test
  void aStationWithoutReturnRoutesKeepsATrainWhoseNextTripStillWaits() {
    MainlineFixture fixture = new MainlineFixture(NodeId.of("SURC:S:NTA:2"));
    fixture.manager.setDutyBound(train -> true);
    fixture.manager.setMainlineReturnGate((train, route) -> false);
    fixture.manager.setReturnGate(train -> false);

    fixture.checkAfterIdle(4000);

    verify(fixture.ticketAssigner, never()).forceAssign(any(), any(), any());
    assertTrue(fixture.destroyed.isEmpty());
  }

  /**
   * 没绑交路的车（例如重启后账本丢了）停在原地折返的车站：不走立即回收，照旧等闲置上限，之后进滞留计时。
   *
   * <p>它还可能接一张从这里始发的首班票；也不为它查库。
   */
  @Test
  void anUnboundVehicleAtAStationWithoutReturnRoutesWaitsForTheIdleLimit() {
    MainlineFixture fixture = new MainlineFixture(NodeId.of("SURC:S:NTA:2"));
    fixture.manager.setMainlineReturnGate((train, route) -> true);
    org.mockito.Mockito.clearInvocations(fixture.provider.companies());

    fixture.checkAfterIdle(ReclaimManager.MAINLINE_TURNBACK_MIN_IDLE_SECONDS);
    verify(fixture.provider.companies(), never()).listAll();
    fixture.checkAfterIdle(4000);

    verify(fixture.ticketAssigner, never()).forceAssign(any(), any(), any());
    assertTrue(fixture.destroyed.isEmpty(), "闲置超时只记滞留起点");
  }

  /** 查"终点有没有回库线路"时存储出错：按有处理、不缓存，本轮回收照常走完，不会被掐断。 */
  @Test
  void aStorageFailureWhileLookingForReturnRoutesKeepsTheScanGoing() {
    MainlineFixture fixture = new MainlineFixture(NodeId.of("SURC:S:NTA:2"));
    fixture.manager.setDutyBound(train -> true);
    fixture.manager.setMainlineReturnGate((train, route) -> true);
    when(fixture.provider.companies().listAll())
        .thenThrow(new org.fetarute.fetaruteTCAddon.storage.api.StorageException("db down"));

    fixture.checkAfterIdle(ReclaimManager.MAINLINE_TURNBACK_MIN_IDLE_SECONDS);

    assertTrue(fixture.destroyed.isEmpty());
    assertTrue(
        fixture.logs.stream()
            .anyMatch(line -> line.startsWith("回收: 查询回库线路失败 terminal=surc:s:nta:2")),
        fixture.logs::toString);
  }

  /** 交路已换车的车停在原地折返的车站：再也没有班可跑、也没有回库线路，当场处理。 */
  @Test
  void aRetiredVehicleAtAStationWithoutReturnRoutesIsClearedInPlace() {
    MainlineFixture fixture = new MainlineFixture(NodeId.of("SURC:S:NTA:2"));
    fixture.manager.setRetiredVehicle(train -> train.equals("train-a"));

    fixture.checkAfterIdle(ReclaimManager.MAINLINE_TURNBACK_MIN_IDLE_SECONDS);

    assertEquals(List.of("train-a:reclaim-no-return-route"), fixture.destroyed);
  }

  /** "终点有没有回库线路"在复查窗口内只查一次库：每轮扫描都要问，查一次要扫遍全部运营商的交路与首站。 */
  @Test
  void theReturnRouteLookupIsReusedWithinTheRecheckWindow() {
    MainlineFixture fixture = new MainlineFixture(NodeId.of("SURC:S:NTA:2"));
    fixture.manager.setDutyBound(train -> true);
    org.mockito.Mockito.clearInvocations(fixture.provider.companies());

    fixture.checkAfterIdle(ReclaimManager.MAINLINE_TURNBACK_MIN_IDLE_SECONDS);
    fixture.checkAfterIdle(ReclaimManager.MAINLINE_TURNBACK_MIN_IDLE_SECONDS + 60);
    verify(fixture.provider.companies(), times(1)).listAll();

    fixture.checkAfterIdle(
        ReclaimManager.MAINLINE_TURNBACK_MIN_IDLE_SECONDS
            + ReclaimManager.RETURN_ROUTE_RECHECK_SECONDS);
    verify(fixture.provider.companies(), times(2)).listAll();
  }

  /**
   * 正线折返用例的夹具：本运营商 MT 线上一条刚跑完的交路 {@link #shortRoute} 与一条站台 PPK 出发的 RETURN；可选再加一条从正线折返点出发的 RETURN
   * {@link #mainlineReturnRoute}。闲置上限 3600 秒，时钟由用例推进，销毁记为 {@code 列车:原因}。
   */
  private static final class MainlineFixture {
    static final NodeId MAINLINE_TURNBACK = NodeId.of("SURC:OFL:MLU:2:004");
    private static final Instant ARRIVED = Instant.parse("2026-09-27T23:20:00Z");

    final StorageProvider provider;
    final TicketAssigner ticketAssigner = mock(TicketAssigner.class);
    final UUID shortRoute = UUID.randomUUID();
    final UUID mainlineReturnRoute = UUID.randomUUID();
    final ReclaimManager manager;
    final List<String> destroyed = new ArrayList<>();
    final List<String> logs = new ArrayList<>();
    final LayoverRegistry layoverRegistry = new LayoverRegistry();
    private final java.util.concurrent.atomic.AtomicReference<Instant> clock =
        new java.util.concurrent.atomic.AtomicReference<>(ARRIVED);

    MainlineFixture(NodeId layoverNode) {
      this(layoverNode, false, false, 600);
    }

    MainlineFixture(
        NodeId layoverNode,
        boolean withMainlineReturn,
        boolean passengers,
        long strandedDestroySeconds) {
      provider = mockProvider(UUID.randomUUID(), UUID.randomUUID());
      UUID companyId = provider.companies().listAll().get(0).id();
      Operator operator =
          provider.operators().findByCompanyAndCode(companyId, "SURC").orElseThrow();
      Line line = provider.lines().listByOperator(operator.id()).get(0);
      Route platformReturn = provider.routes().listByLine(line.id()).get(0);
      Route shortR = route(shortRoute, "MT-1O_ShortR", line.id(), RouteOperationType.OPERATION);
      Route mainlineReturn =
          route(mainlineReturnRoute, "MT-1O_ShortR-RET", line.id(), RouteOperationType.RETURN);
      when(provider.routes().findById(shortRoute)).thenReturn(Optional.of(shortR));
      when(provider.routes().listByLine(line.id()))
          .thenReturn(
              withMainlineReturn
                  ? List.of(platformReturn, shortR, mainlineReturn)
                  : List.of(platformReturn, shortR));
      when(provider.routeStops().listByRoute(mainlineReturnRoute))
          .thenReturn(
              List.of(
                  new RouteStop(
                      mainlineReturnRoute,
                      0,
                      Optional.empty(),
                      Optional.of(MAINLINE_TURNBACK.value()),
                      Optional.empty(),
                      RouteStopPassType.STOP,
                      Optional.empty())));

      FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
      StorageManager storageManager = mock(StorageManager.class);
      when(plugin.getStorageManager()).thenReturn(storageManager);
      when(storageManager.provider()).thenReturn(Optional.of(provider));
      when(ticketAssigner.snapshotPendingTickets()).thenReturn(List.of());
      when(ticketAssigner.forceAssign(eq(provider), eq("train-a"), any())).thenReturn(true);
      layoverRegistry.register(
          "train-a",
          layoverNode.value().toLowerCase(java.util.Locale.ROOT),
          layoverNode,
          ARRIVED,
          Map.of(
              "FTA_OPERATOR_CODE",
              "SURC",
              RouteProgressRegistry.TAG_ROUTE_ID,
              shortRoute.toString()));
      ConfigManager configManager = mock(ConfigManager.class);
      ConfigManager.ConfigView view = mock(ConfigManager.ConfigView.class);
      when(configManager.current()).thenReturn(view);
      when(view.reclaimSettings())
          .thenReturn(
              new ConfigManager.ReclaimSettings(true, 3600, 100, 60, strandedDestroySeconds));
      manager =
          new ReclaimManager(
              plugin,
              layoverRegistry,
              ticketAssigner,
              configManager,
              logs::add,
              () -> 0,
              trainName -> passengers,
              (trainName, reason) -> destroyed.add(trainName + ":" + reason),
              clock::get);
    }

    void checkAfterIdle(long seconds) {
      clock.set(ARRIVED.plusSeconds(seconds));
      manager.performReclaimCheck();
    }

    private static Route route(UUID id, String code, UUID lineId, RouteOperationType type) {
      Instant ts = Instant.parse("2026-02-01T00:00:00Z");
      return new Route(
          id,
          code,
          lineId,
          code,
          Optional.empty(),
          RoutePatternType.LOCAL,
          type,
          Optional.empty(),
          Optional.empty(),
          Map.of(),
          ts,
          ts);
    }
  }

  /**
   * 在样例库里加一个外方运营商 SURN 及其 RETURN 交路：首站是裸节点 {@code SURN:S:XXX:1}，不引用站点主数据。
   *
   * @return 外方 RETURN 交路 ID
   */
  private static UUID addForeignOperatorReturn(StorageProvider provider) {
    Instant ts = Instant.parse("2026-02-01T00:00:00Z");
    UUID companyId = provider.companies().listAll().get(0).id();
    UUID foreignOperatorId = UUID.randomUUID();
    UUID foreignLineId = UUID.randomUUID();
    UUID foreignRouteId = UUID.randomUUID();
    Operator foreign =
        new Operator(
            foreignOperatorId,
            "SURN",
            companyId,
            "SURN",
            Optional.empty(),
            Optional.empty(),
            0,
            Optional.empty(),
            Map.of(),
            ts,
            ts);
    Line foreignLine =
        new Line(
            foreignLineId,
            "NL",
            foreignOperatorId,
            "North",
            Optional.empty(),
            LineServiceType.METRO,
            Optional.empty(),
            LineStatus.ACTIVE,
            Optional.of(100),
            Map.of(),
            ts,
            ts);
    Route foreignReturn =
        new Route(
            foreignRouteId,
            "NL-RET",
            foreignLineId,
            "Return",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.RETURN,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            ts,
            ts);
    RouteStop foreignFirst =
        new RouteStop(
            foreignRouteId,
            0,
            Optional.empty(),
            Optional.of("SURN:S:XXX:1"),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());
    Operator own = provider.operators().findByCompanyAndCode(companyId, "SURC").orElseThrow();
    List<Operator> operators = List.of(own, foreign);
    when(provider.operators().listByCompany(companyId)).thenReturn(operators);
    when(provider.lines().listByOperator(foreignOperatorId)).thenReturn(List.of(foreignLine));
    when(provider.routes().listByLine(foreignLineId)).thenReturn(List.of(foreignReturn));
    when(provider.routeStops().listByRoute(foreignRouteId)).thenReturn(List.of(foreignFirst));

    return foreignRouteId;
  }

  private static ConfigManager mockConfigManager() {
    ConfigManager configManager = mock(ConfigManager.class);
    ConfigManager.ConfigView view = mock(ConfigManager.ConfigView.class);
    when(configManager.current()).thenReturn(view);
    when(view.reclaimSettings()).thenReturn(new ConfigManager.ReclaimSettings(true, 3600, 100, 60));
    return configManager;
  }

  private static StorageProvider mockProvider(UUID routeId, UUID stationId) {
    StorageProvider provider = mock(StorageProvider.class);

    CompanyRepository companyRepository = mock(CompanyRepository.class);
    OperatorRepository operatorRepository = mock(OperatorRepository.class);
    LineRepository lineRepository = mock(LineRepository.class);
    RouteRepository routeRepository = mock(RouteRepository.class);
    RouteStopRepository routeStopRepository = mock(RouteStopRepository.class);
    StationRepository stationRepository = mock(StationRepository.class);

    when(provider.companies()).thenReturn(companyRepository);
    when(provider.operators()).thenReturn(operatorRepository);
    when(provider.lines()).thenReturn(lineRepository);
    when(provider.routes()).thenReturn(routeRepository);
    when(provider.routeStops()).thenReturn(routeStopRepository);
    when(provider.stations()).thenReturn(stationRepository);

    Instant ts = Instant.parse("2026-02-01T00:00:00Z");
    UUID companyId = UUID.randomUUID();
    UUID operatorId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();

    Company company =
        new Company(
            companyId,
            "C1",
            "Company",
            Optional.empty(),
            UUID.randomUUID(),
            CompanyStatus.ACTIVE,
            0L,
            Map.of(),
            ts,
            ts);
    Operator operator =
        new Operator(
            operatorId,
            "SURC",
            companyId,
            "SURC",
            Optional.empty(),
            Optional.empty(),
            0,
            Optional.empty(),
            Map.of(),
            ts,
            ts);
    Line line =
        new Line(
            lineId,
            "MT",
            operatorId,
            "Metro",
            Optional.empty(),
            LineServiceType.METRO,
            Optional.empty(),
            LineStatus.ACTIVE,
            Optional.of(100),
            Map.of(),
            ts,
            ts);
    Route returnRoute =
        new Route(
            routeId,
            "MT-RET",
            lineId,
            "Return",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.RETURN,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            ts,
            ts);
    RouteStop firstStop =
        new RouteStop(
            routeId,
            0,
            Optional.of(stationId),
            Optional.empty(),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());
    Station station =
        new Station(
            stationId,
            "PPK",
            operatorId,
            Optional.empty(),
            "PPK",
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of("SURC:S:PPK:1"),
            Optional.empty(),
            List.of(),
            Map.of(),
            ts,
            ts);

    when(companyRepository.listAll()).thenReturn(List.of(company));
    when(operatorRepository.findByCompanyAndCode(companyId, "SURC"))
        .thenReturn(Optional.of(operator));
    when(operatorRepository.findById(operatorId)).thenReturn(Optional.of(operator));
    when(operatorRepository.listByCompany(companyId)).thenReturn(List.of(operator));
    when(lineRepository.listByOperator(operatorId)).thenReturn(List.of(line));
    when(lineRepository.findById(lineId)).thenReturn(Optional.of(line));
    when(routeRepository.listByLine(lineId)).thenReturn(List.of(returnRoute));
    when(routeRepository.findById(routeId)).thenReturn(Optional.of(returnRoute));
    when(routeStopRepository.listByRoute(routeId)).thenReturn(List.of(firstStop));
    when(stationRepository.findById(stationId)).thenReturn(Optional.of(station));

    return provider;
  }

  private static SpawnTicket buildPendingTicket(UUID routeId, String startNode) {
    SpawnService service =
        new SpawnService(
            new SpawnServiceKey(routeId),
            UUID.randomUUID(),
            "C1",
            UUID.randomUUID(),
            "SURC",
            UUID.randomUUID(),
            "MT",
            routeId,
            "MT-RET",
            Duration.ofSeconds(100),
            startNode);
    Instant now = Instant.now();
    SpawnTicket ticket =
        new SpawnTicket(
            UUID.randomUUID(), service, now, now, 0, 0L, Optional.empty(), Optional.empty());
    assertEquals(startNode, ticket.service().depotNodeId());
    return ticket;
  }
}
