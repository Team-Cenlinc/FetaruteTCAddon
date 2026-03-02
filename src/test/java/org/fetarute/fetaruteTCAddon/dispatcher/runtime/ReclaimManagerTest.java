package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    when(ticketAssigner.forceAssign(eq("train-a"), any())).thenReturn(true);

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

    verify(ticketAssigner, times(1)).forceAssign(eq("train-a"), any());
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

    verify(ticketAssigner, never()).forceAssign(any(), any());
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
    when(ticketAssigner.forceAssign(eq("train-life"), any())).thenReturn(true);

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

    verify(ticketAssigner, times(1)).forceAssign(eq("train-life"), any());
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
    when(lineRepository.listByOperator(operatorId)).thenReturn(List.of(line));
    when(routeRepository.listByLine(lineId)).thenReturn(List.of(returnRoute));
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
