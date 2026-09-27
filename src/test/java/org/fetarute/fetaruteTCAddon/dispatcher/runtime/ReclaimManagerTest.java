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

  @Test
  void activeTrainCountHandlesEmptyAndMissingGroups() {
    List<MinecartGroup> groups = new ArrayList<>();
    groups.add(null);

    assertEquals(0, ReclaimManager.countActiveGroups(groups));
    assertEquals(0, ReclaimManager.countActiveGroups(null));
  }

  /**
   * 兜底：该回收却派不出 RETURN 票的车，滞留超过阈值就销毁；有乘客的不碰。
   *
   * <p>train-a 停在没有任何 RETURN 线路能出发的终点，闲置早已超时。第一轮记下滞留起点，阈值到了才销毁； 同一场景换成有乘客的车，永远不销毁。
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
    // 外方运营商 SURN 及其 RETURN：首站是裸节点 SURN:S:XXX:1，不引用站点主数据。
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
