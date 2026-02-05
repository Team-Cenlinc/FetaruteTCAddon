package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
import org.fetarute.fetaruteTCAddon.company.repository.CompanyRepository;
import org.fetarute.fetaruteTCAddon.company.repository.LineRepository;
import org.fetarute.fetaruteTCAddon.company.repository.OperatorRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RouteRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RouteStopRepository;
import org.fetarute.fetaruteTCAddon.company.repository.StationRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailComponentCautionRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailEdgeOverrideRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailEdgeRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailGraphSnapshotRepository;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.repository.RailNodeRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.storage.api.StorageTransactionManager;
import org.junit.jupiter.api.Test;

class StorageSpawnManagerTest {

  @Test
  void pollDueTicketsGeneratesTicketForEnabledRoute() {
    StorageProvider provider = mockProvider(enabledRoute());
    StorageSpawnManager.SpawnManagerSettings settings =
        new StorageSpawnManager.SpawnManagerSettings(
            Duration.ofSeconds(999), Duration.ZERO, 5, 5, 10);
    StorageSpawnManager manager = new StorageSpawnManager(settings, null);

    Instant now = Instant.parse("2026-01-19T00:00:00Z");
    List<SpawnTicket> due = manager.pollDueTickets(provider, now);

    assertEquals(1, due.size());
    SpawnTicket ticket = due.get(0);
    assertEquals("SURN", ticket.service().operatorCode());
    assertEquals("L1", ticket.service().lineCode());
    assertEquals("R1", ticket.service().routeCode());
    assertEquals("SURN:D:DEPOT:1", ticket.service().depotNodeId());
    assertEquals(now, ticket.dueAt());
    assertEquals(now, ticket.notBefore());
  }

  @Test
  void pollDueTicketsSkipsLineWhenMultipleCandidatesAndNoneEnabled() {
    StorageProvider provider = mockProvider(twoCandidatesNoneEnabled());
    StorageSpawnManager.SpawnManagerSettings settings =
        new StorageSpawnManager.SpawnManagerSettings(
            Duration.ofSeconds(999), Duration.ZERO, 5, 5, 10);
    StorageSpawnManager manager = new StorageSpawnManager(settings, null);

    Instant now = Instant.parse("2026-01-19T00:00:00Z");
    List<SpawnTicket> due = manager.pollDueTickets(provider, now);

    assertTrue(due.isEmpty());
    assertEquals(0, manager.snapshotPlan().size());
  }

  @Test
  void pollDueTicketsSkipsLineWhenBaselineZero() {
    StorageProvider provider = mockProvider(baselineZeroRoute());
    StorageSpawnManager.SpawnManagerSettings settings =
        new StorageSpawnManager.SpawnManagerSettings(
            Duration.ofSeconds(999), Duration.ZERO, 5, 5, 10);
    StorageSpawnManager manager = new StorageSpawnManager(settings, null);

    Instant now = Instant.parse("2026-01-19T00:00:00Z");
    List<SpawnTicket> due = manager.pollDueTickets(provider, now);

    assertTrue(due.isEmpty());
    assertEquals(0, manager.snapshotPlan().size());
  }

  @Test
  void pollDueTicketsSkipsRouteWhenWeightZero() {
    StorageProvider provider = mockProvider(weightZeroRoute());
    StorageSpawnManager.SpawnManagerSettings settings =
        new StorageSpawnManager.SpawnManagerSettings(
            Duration.ofSeconds(999), Duration.ZERO, 5, 5, 10);
    StorageSpawnManager manager = new StorageSpawnManager(settings, null);

    Instant now = Instant.parse("2026-01-19T00:00:00Z");
    List<SpawnTicket> due = manager.pollDueTickets(provider, now);

    assertTrue(due.isEmpty());
    assertEquals(0, manager.snapshotPlan().size());
  }

  @Test
  void pollDueTicketsSupportsMultiRouteWeightsWithSameDepot() {
    StorageProvider provider = mockProvider(twoEnabledRoutesSameDepotWithWeights());
    StorageSpawnManager.SpawnManagerSettings settings =
        new StorageSpawnManager.SpawnManagerSettings(
            Duration.ofSeconds(999), Duration.ZERO, 5, 10, 10);
    StorageSpawnManager manager = new StorageSpawnManager(settings, null);

    Instant now = Instant.parse("2026-01-19T00:00:00Z");
    List<SpawnTicket> due = manager.pollDueTickets(provider, now);

    assertEquals(2, due.size());
    assertEquals(2, manager.snapshotPlan().size());

    Map<String, SpawnService> servicesByRoute =
        due.stream()
            .map(SpawnTicket::service)
            .collect(Collectors.toMap(SpawnService::routeCode, Function.identity()));
    assertEquals(Duration.ofSeconds(900), servicesByRoute.get("R1").baseHeadway());
    assertEquals(Duration.ofSeconds(450), servicesByRoute.get("R2").baseHeadway());
    assertEquals("SURN:D:DEPOT:1", servicesByRoute.get("R1").depotNodeId());
    assertEquals("SURN:D:DEPOT:1", servicesByRoute.get("R2").depotNodeId());
  }

  @Test
  void pollDueTicketsAllowsLayoverRouteAlongsideDepotRoute() {
    StorageProvider provider = mockProvider(depotAndLayoverRoutesSameLine());
    StorageSpawnManager.SpawnManagerSettings settings =
        new StorageSpawnManager.SpawnManagerSettings(
            Duration.ofSeconds(999), Duration.ZERO, 5, 10, 10);
    StorageSpawnManager manager = new StorageSpawnManager(settings, null);

    Instant now = Instant.parse("2026-01-19T00:00:00Z");
    List<SpawnTicket> due = manager.pollDueTickets(provider, now);

    assertEquals(2, due.size());
    assertEquals(2, manager.snapshotPlan().size());
  }

  @Test
  void pollDueTicketsAllowsEnabledRoutesWithDifferentDepots() {
    StorageProvider provider = mockProvider(twoEnabledRoutesDifferentDepotWithWeights());
    StorageSpawnManager.SpawnManagerSettings settings =
        new StorageSpawnManager.SpawnManagerSettings(
            Duration.ofSeconds(999), Duration.ZERO, 5, 10, 10);
    StorageSpawnManager manager = new StorageSpawnManager(settings, null);

    Instant now = Instant.parse("2026-01-19T00:00:00Z");
    List<SpawnTicket> due = manager.pollDueTickets(provider, now);

    assertEquals(2, due.size());
    assertEquals(2, manager.snapshotPlan().size());
  }

  @Test
  void snapshotForecastIncludesReturnRoutes() {
    StorageProvider provider = mockProvider(operationAndReturnRoutes());
    StorageSpawnManager.SpawnManagerSettings settings =
        new StorageSpawnManager.SpawnManagerSettings(
            Duration.ofSeconds(999), Duration.ZERO, 5, 1, 10);
    StorageSpawnManager manager = new StorageSpawnManager(settings, null);

    Instant now = Instant.parse("2026-01-19T00:00:00Z");
    manager.pollDueTickets(provider, now);

    List<SpawnTicket> forecast = manager.snapshotForecast(now, Duration.ofMinutes(10), 1);
    Set<String> routes =
        forecast.stream().map(t -> t.service().routeCode()).collect(Collectors.toSet());
    Set<String> plannedRoutes =
        manager.snapshotPlan().services().stream()
            .map(SpawnService::routeCode)
            .collect(Collectors.toSet());

    assertEquals(2, manager.snapshotPlan().size());
    assertTrue(routes.contains("R1"));
    assertTrue(routes.contains("RET"));
    assertTrue(plannedRoutes.contains("R1"));
    assertTrue(plannedRoutes.contains("RET"));
  }

  private static Fixture enabledRoute() {
    UUID companyId = UUID.randomUUID();
    UUID ownerIdentityId = UUID.randomUUID();
    UUID operatorId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID routeId = UUID.randomUUID();
    Instant ts = Instant.parse("2026-01-01T00:00:00Z");

    Company company =
        new Company(
            companyId,
            "C1",
            "Company",
            Optional.empty(),
            ownerIdentityId,
            CompanyStatus.ACTIVE,
            0L,
            Map.of(),
            ts,
            ts);
    Operator operator =
        new Operator(
            operatorId,
            "SURN",
            companyId,
            "Operator",
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
            "L1",
            operatorId,
            "Line",
            Optional.empty(),
            LineServiceType.METRO,
            Optional.empty(),
            LineStatus.ACTIVE,
            Optional.of(300),
            Map.of(),
            ts,
            ts);
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
            Map.of("spawn_enabled", true),
            ts,
            ts);
    RouteStop cret =
        new RouteStop(
            routeId,
            0,
            Optional.empty(),
            Optional.of("SURN:D:DEPOT:1"),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("CRET SURN:D:DEPOT:1"));

    return new Fixture(company, operator, line, List.of(route), Map.of(routeId, List.of(cret)));
  }

  private static Fixture operationAndReturnRoutes() {
    Fixture base = enabledRoute();
    UUID route2Id = UUID.randomUUID();
    Instant ts = Instant.parse("2026-01-01T00:00:00Z");

    Route returnRoute =
        new Route(
            route2Id,
            "RET",
            base.line().id(),
            "Return",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.RETURN,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            ts,
            ts);
    RouteStop start =
        new RouteStop(
            route2Id,
            0,
            Optional.empty(),
            Optional.of("SURN:S:RET:1"),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.empty());

    return new Fixture(
        base.company(),
        base.operator(),
        base.line(),
        List.of(base.routes().get(0), returnRoute),
        Map.of(
            base.routes().get(0).id(),
            base.stopsByRouteId().get(base.routes().get(0).id()),
            returnRoute.id(),
            List.of(start)));
  }

  private static Fixture twoCandidatesNoneEnabled() {
    Fixture base = enabledRoute();
    UUID route2Id = UUID.randomUUID();
    Instant ts = Instant.parse("2026-01-01T00:00:00Z");
    Route route1 =
        new Route(
            base.routes().get(0).id(),
            "R1",
            base.line().id(),
            "Route",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            ts,
            ts);
    Route route2 =
        new Route(
            route2Id,
            "R2",
            base.line().id(),
            "Route2",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            ts,
            ts);
    RouteStop cret1 =
        new RouteStop(
            route1.id(),
            0,
            Optional.empty(),
            Optional.of("SURN:D:DEPOT:1"),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("CRET SURN:D:DEPOT:1"));
    RouteStop cret2 =
        new RouteStop(
            route2.id(),
            0,
            Optional.empty(),
            Optional.of("SURN:D:DEPOT:2"),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("CRET SURN:D:DEPOT:2"));
    return new Fixture(
        base.company(),
        base.operator(),
        base.line(),
        List.of(route1, route2),
        Map.of(route1.id(), List.of(cret1), route2.id(), List.of(cret2)));
  }

  private static Fixture baselineZeroRoute() {
    Fixture base = enabledRoute();
    Instant ts = Instant.parse("2026-01-01T00:00:00Z");
    Line line =
        new Line(
            base.line().id(),
            "L1",
            base.operator().id(),
            "Line",
            Optional.empty(),
            LineServiceType.METRO,
            Optional.empty(),
            LineStatus.ACTIVE,
            Optional.of(0),
            Map.of(),
            ts,
            ts);
    Route route =
        new Route(
            base.routes().get(0).id(),
            "R1",
            line.id(),
            "Route",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of("spawn_enabled", true),
            ts,
            ts);
    RouteStop cret =
        new RouteStop(
            route.id(),
            0,
            Optional.empty(),
            Optional.of("SURN:D:DEPOT:1"),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("CRET SURN:D:DEPOT:1"));
    return new Fixture(
        base.company(), base.operator(), line, List.of(route), Map.of(route.id(), List.of(cret)));
  }

  private static Fixture weightZeroRoute() {
    Fixture base = enabledRoute();
    Instant ts = Instant.parse("2026-01-01T00:00:00Z");
    Route route =
        new Route(
            base.routes().get(0).id(),
            "R1",
            base.line().id(),
            "Route",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of("spawn_weight", 0),
            ts,
            ts);
    RouteStop cret =
        new RouteStop(
            route.id(),
            0,
            Optional.empty(),
            Optional.of("SURN:D:DEPOT:1"),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("CRET SURN:D:DEPOT:1"));
    return new Fixture(
        base.company(),
        base.operator(),
        base.line(),
        List.of(route),
        Map.of(route.id(), List.of(cret)));
  }

  private static Fixture twoEnabledRoutesSameDepotWithWeights() {
    Fixture base = enabledRoute();
    UUID route2Id = UUID.randomUUID();
    Instant ts = Instant.parse("2026-01-01T00:00:00Z");
    Route route1 =
        new Route(
            base.routes().get(0).id(),
            "R1",
            base.line().id(),
            "Route",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of("spawn_weight", 1),
            ts,
            ts);
    Route route2 =
        new Route(
            route2Id,
            "R2",
            base.line().id(),
            "Route2",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of("spawn_weight", 2),
            ts,
            ts);
    RouteStop cret1 =
        new RouteStop(
            route1.id(),
            0,
            Optional.empty(),
            Optional.of("SURN:D:DEPOT:1"),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("CRET SURN:D:DEPOT:1"));
    RouteStop cret2 =
        new RouteStop(
            route2.id(),
            0,
            Optional.empty(),
            Optional.of("SURN:D:DEPOT:1"),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("CRET SURN:D:DEPOT:1"));
    return new Fixture(
        base.company(),
        base.operator(),
        base.line(),
        List.of(route1, route2),
        Map.of(route1.id(), List.of(cret1), route2.id(), List.of(cret2)));
  }

  private static Fixture depotAndLayoverRoutesSameLine() {
    Fixture base = enabledRoute();
    UUID route2Id = UUID.randomUUID();
    Instant ts = Instant.parse("2026-01-01T00:00:00Z");
    Route route1 =
        new Route(
            base.routes().get(0).id(),
            "R1",
            base.line().id(),
            "Route",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of("spawn_weight", 1),
            ts,
            ts);
    Route route2 =
        new Route(
            route2Id,
            "R2",
            base.line().id(),
            "Route2",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of("spawn_weight", 1),
            ts,
            ts);
    RouteStop cret =
        new RouteStop(
            route1.id(),
            0,
            Optional.empty(),
            Optional.of("SURN:D:DEPOT:1"),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("CRET SURN:D:DEPOT:1"));
    RouteStop stop =
        new RouteStop(
            route2.id(),
            0,
            Optional.empty(),
            Optional.of("SURN:S:PEK:1"),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.of("STOP SURN:S:PEK:1"));
    return new Fixture(
        base.company(),
        base.operator(),
        base.line(),
        List.of(route1, route2),
        Map.of(route1.id(), List.of(cret), route2.id(), List.of(stop)));
  }

  private static Fixture twoEnabledRoutesDifferentDepotWithWeights() {
    Fixture base = enabledRoute();
    UUID route2Id = UUID.randomUUID();
    Instant ts = Instant.parse("2026-01-01T00:00:00Z");
    Route route1 =
        new Route(
            base.routes().get(0).id(),
            "R1",
            base.line().id(),
            "Route",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of("spawn_weight", 1),
            ts,
            ts);
    Route route2 =
        new Route(
            route2Id,
            "R2",
            base.line().id(),
            "Route2",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of("spawn_weight", 1),
            ts,
            ts);
    RouteStop cret1 =
        new RouteStop(
            route1.id(),
            0,
            Optional.empty(),
            Optional.of("SURN:D:DEPOT:1"),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("CRET SURN:D:DEPOT:1"));
    RouteStop cret2 =
        new RouteStop(
            route2.id(),
            0,
            Optional.empty(),
            Optional.of("SURN:D:DEPOT:2"),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("CRET SURN:D:DEPOT:2"));
    return new Fixture(
        base.company(),
        base.operator(),
        base.line(),
        List.of(route1, route2),
        Map.of(route1.id(), List.of(cret1), route2.id(), List.of(cret2)));
  }

  private static StorageProvider mockProvider(Fixture fixture) {
    StorageProvider provider = mock(StorageProvider.class);
    CompanyRepository companyRepo = mock(CompanyRepository.class);
    OperatorRepository operatorRepo = mock(OperatorRepository.class);
    LineRepository lineRepo = mock(LineRepository.class);
    RouteRepository routeRepo = mock(RouteRepository.class);
    RouteStopRepository stopRepo = mock(RouteStopRepository.class);

    when(provider.companies()).thenReturn(companyRepo);
    when(provider.operators()).thenReturn(operatorRepo);
    when(provider.lines()).thenReturn(lineRepo);
    when(provider.routes()).thenReturn(routeRepo);
    when(provider.routeStops()).thenReturn(stopRepo);

    when(companyRepo.listAll()).thenReturn(List.of(fixture.company()));
    when(operatorRepo.listByCompany(fixture.company().id()))
        .thenReturn(List.of(fixture.operator()));
    when(lineRepo.listByOperator(fixture.operator().id())).thenReturn(List.of(fixture.line()));
    when(routeRepo.listByLine(fixture.line().id())).thenReturn(fixture.routes());

    for (Route route : fixture.routes()) {
      when(stopRepo.listByRoute(route.id())).thenReturn(fixture.stopsByRouteId().get(route.id()));
    }

    // 不相关仓库：提供空实现即可
    when(provider.playerIdentities())
        .thenReturn(
            mock(org.fetarute.fetaruteTCAddon.company.repository.PlayerIdentityRepository.class));
    when(provider.companyMembers())
        .thenReturn(
            mock(org.fetarute.fetaruteTCAddon.company.repository.CompanyMemberRepository.class));
    when(provider.companyMemberInvites())
        .thenReturn(
            mock(
                org.fetarute
                    .fetaruteTCAddon
                    .company
                    .repository
                    .CompanyMemberInviteRepository
                    .class));
    when(provider.stations()).thenReturn(mock(StationRepository.class));
    when(provider.railNodes()).thenReturn(mock(RailNodeRepository.class));
    when(provider.railEdges()).thenReturn(mock(RailEdgeRepository.class));
    when(provider.railEdgeOverrides()).thenReturn(mock(RailEdgeOverrideRepository.class));
    when(provider.railComponentCautions()).thenReturn(mock(RailComponentCautionRepository.class));
    when(provider.railGraphSnapshots()).thenReturn(mock(RailGraphSnapshotRepository.class));
    when(provider.transactionManager()).thenReturn(mock(StorageTransactionManager.class));

    return provider;
  }

  private record Fixture(
      Company company,
      Operator operator,
      Line line,
      List<Route> routes,
      Map<UUID, List<RouteStop>> stopsByRouteId) {}
}
