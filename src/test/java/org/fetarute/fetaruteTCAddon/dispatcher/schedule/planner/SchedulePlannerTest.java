package org.fetarute.fetaruteTCAddon.dispatcher.schedule.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.ServiceTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.LineSpawnMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.ServiceTripSpawnAdapter;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnDepot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnGroup;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.Test;

class SchedulePlannerTest {

  private static final Instant START = Instant.parse("2026-02-01T00:00:00Z");

  @Test
  void planGeneratesSingleRouteFixedHeadway() {
    Fixture fixture = singleRouteFixture(Optional.of(300), Map.of(), Map.of("spawn_enabled", true));
    SchedulePlanner planner = new SchedulePlanner();

    List<ServiceTrip> trips =
        planner.plan(mockProvider(fixture), START, START.plusSeconds(600)).trips();

    assertEquals(2, trips.size());
    assertEquals(START, trips.get(0).plannedDeparture());
    assertEquals(START.plusSeconds(300), trips.get(1).plannedDeparture());
    assertEquals("R1", trips.get(0).routeCode());
    assertEquals(TripSource.SCHEDULED, trips.get(0).source());
  }

  @Test
  void planGeneratesMultipleRoutesByWeight() {
    Fixture fixture = weightedRoutesFixture();
    SchedulePlanner planner = new SchedulePlanner();

    List<ServiceTrip> trips =
        planner.plan(mockProvider(fixture), START, START.plusSeconds(900)).trips();
    Map<String, Long> countByRoute =
        trips.stream()
            .collect(Collectors.groupingBy(ServiceTrip::routeCode, Collectors.counting()));

    assertEquals(1L, countByRoute.get("R1"));
    assertEquals(2L, countByRoute.get("R2"));
  }

  @Test
  void planUsesRouteGroupBaselineBeforeLineBaseline() {
    Map<String, Object> lineMeta =
        Map.of(
            LineSpawnMetadata.KEY_GROUPS,
            LineSpawnMetadata.toGroupMetadata(
                List.of(new SpawnGroup("group-a", Optional.of(120), Optional.of(3)))));
    Fixture fixture =
        singleRouteFixture(
            Optional.of(600), lineMeta, Map.of("spawn_group", "group-a", "spawn_enabled", true));
    SchedulePlanner planner = new SchedulePlanner();

    List<ServiceTrip> trips =
        planner.plan(mockProvider(fixture), START, START.plusSeconds(360)).trips();

    assertEquals(3, trips.size());
    assertEquals(Optional.of(3), trips.get(0).maxOperationTrips());
    assertEquals(START.plusSeconds(120), trips.get(1).plannedDeparture());
  }

  @Test
  void planWritesDepotPoolCandidatesIntoTrip() {
    Map<String, Object> lineMeta =
        Map.of(
            LineSpawnMetadata.KEY_DEPOTS,
            LineSpawnMetadata.toDepotMetadata(
                List.of(new SpawnDepot("OP:D:DEPOT:1", 2), new SpawnDepot("OP:D:DEPOT:2", 1))));
    Fixture fixture = singleRouteFixture(Optional.of(300), lineMeta, Map.of("spawn_enabled", true));
    SchedulePlanner planner = new SchedulePlanner();

    ServiceTrip trip =
        planner.plan(mockProvider(fixture), START, START.plusSeconds(300)).trips().get(0);

    assertEquals(2, trip.depotCandidates().size());
    assertEquals("OP:D:DEPOT:1", trip.depotCandidates().get(0).nodeId());
    assertEquals(2, trip.depotCandidates().get(0).weight());
  }

  @Test
  void onDemandTripUsesSameModelAndSpawnAdapter() {
    Fixture fixture = singleRouteFixture(Optional.of(300), Map.of(), Map.of("spawn_enabled", true));
    ServiceTrip scheduled =
        new SchedulePlanner()
            .plan(mockProvider(fixture), START, START.plusSeconds(300))
            .trips()
            .get(0);
    ServiceTrip onDemand =
        new ServiceTrip(
            "manual-extra",
            TripSource.ON_DEMAND,
            scheduled.companyId(),
            scheduled.companyCode(),
            scheduled.operatorId(),
            scheduled.operatorCode(),
            scheduled.lineId(),
            scheduled.lineCode(),
            scheduled.routeId(),
            scheduled.routeCode(),
            scheduled.direction(),
            scheduled.plannedDeparture().plusSeconds(90),
            scheduled.plannedStops(),
            8,
            scheduled.depotCandidates(),
            Optional.empty(),
            Optional.of("manual overlay"));

    SpawnTicket ticket =
        ServiceTripSpawnAdapter.toSpawnTicket(onDemand, java.time.Duration.ofMinutes(5), 7);

    assertEquals(TripSource.ON_DEMAND, ticket.source());
    assertEquals(8, ticket.priority());
    assertEquals(Optional.of("manual-extra"), ticket.serviceTripId());
    assertFalse(onDemand.plannedStops().isEmpty());
  }

  private static Fixture singleRouteFixture(
      Optional<Integer> baseline, Map<String, Object> lineMeta, Map<String, Object> routeMeta) {
    UUID routeId = UUID.randomUUID();
    Route route = route(routeId, "R1", RouteOperationType.OPERATION, routeMeta, UUID.randomUUID());
    return fixture(
        baseline,
        lineMeta,
        List.of(route),
        Map.of(routeId, List.of(cret(routeId), stop(routeId, 1, "OP:S:AAA:1"))));
  }

  private static Fixture weightedRoutesFixture() {
    UUID lineId = UUID.randomUUID();
    UUID route1Id = UUID.randomUUID();
    UUID route2Id = UUID.randomUUID();
    Route route1 =
        route(route1Id, "R1", RouteOperationType.OPERATION, Map.of("spawn_weight", 1), lineId);
    Route route2 =
        route(route2Id, "R2", RouteOperationType.OPERATION, Map.of("spawn_weight", 2), lineId);
    return fixture(
        Optional.of(300),
        Map.of(),
        List.of(route1, route2),
        Map.of(
            route1Id,
            List.of(cret(route1Id), stop(route1Id, 1, "OP:S:AAA:1")),
            route2Id,
            List.of(cret(route2Id), stop(route2Id, 1, "OP:S:BBB:1"))));
  }

  private static Fixture fixture(
      Optional<Integer> baseline,
      Map<String, Object> lineMeta,
      List<Route> routes,
      Map<UUID, List<RouteStop>> stopsByRoute) {
    Instant ts = Instant.parse("2026-01-01T00:00:00Z");
    UUID companyId = UUID.randomUUID();
    UUID operatorId = UUID.randomUUID();
    UUID lineId = routes.get(0).lineId();
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
            "OP",
            companyId,
            "Operator",
            Optional.empty(),
            Optional.empty(),
            5,
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
            baseline,
            lineMeta,
            ts,
            ts);
    return new Fixture(company, operator, line, routes, stopsByRoute);
  }

  private static Route route(
      UUID routeId,
      String code,
      RouteOperationType operationType,
      Map<String, Object> metadata,
      UUID lineId) {
    Instant ts = Instant.parse("2026-01-01T00:00:00Z");
    return new Route(
        routeId,
        code,
        lineId,
        code,
        Optional.empty(),
        RoutePatternType.LOCAL,
        operationType,
        Optional.empty(),
        Optional.of(300),
        metadata,
        ts,
        ts);
  }

  private static RouteStop cret(UUID routeId) {
    return new RouteStop(
        routeId,
        0,
        Optional.empty(),
        Optional.of("OP:D:DEPOT:1"),
        Optional.empty(),
        RouteStopPassType.PASS,
        Optional.of("CRET OP:D:DEPOT:1"));
  }

  private static RouteStop stop(UUID routeId, int sequence, String nodeId) {
    return new RouteStop(
        routeId,
        sequence,
        Optional.empty(),
        Optional.of(nodeId),
        Optional.of(30),
        RouteStopPassType.STOP,
        Optional.empty());
  }

  private static StorageProvider mockProvider(Fixture fixture) {
    StorageProvider provider = mock(StorageProvider.class);
    CompanyRepository companies = mock(CompanyRepository.class);
    OperatorRepository operators = mock(OperatorRepository.class);
    LineRepository lines = mock(LineRepository.class);
    RouteRepository routes = mock(RouteRepository.class);
    RouteStopRepository stops = mock(RouteStopRepository.class);

    when(provider.companies()).thenReturn(companies);
    when(provider.operators()).thenReturn(operators);
    when(provider.lines()).thenReturn(lines);
    when(provider.routes()).thenReturn(routes);
    when(provider.routeStops()).thenReturn(stops);

    when(companies.listAll()).thenReturn(List.of(fixture.company()));
    when(companies.findById(fixture.company().id())).thenReturn(Optional.of(fixture.company()));
    when(operators.listByCompany(fixture.company().id())).thenReturn(List.of(fixture.operator()));
    when(operators.findById(fixture.operator().id())).thenReturn(Optional.of(fixture.operator()));
    when(lines.listByOperator(fixture.operator().id())).thenReturn(List.of(fixture.line()));
    when(lines.findById(fixture.line().id())).thenReturn(Optional.of(fixture.line()));
    when(routes.listByLine(fixture.line().id())).thenReturn(fixture.routes());
    for (Route route : fixture.routes()) {
      when(routes.findById(route.id())).thenReturn(Optional.of(route));
      when(stops.listByRoute(route.id())).thenReturn(fixture.stopsByRoute().get(route.id()));
    }
    return provider;
  }

  private record Fixture(
      Company company,
      Operator operator,
      Line line,
      List<Route> routes,
      Map<UUID, List<RouteStop>> stopsByRoute) {
    private Fixture {
      routes =
          routes.stream()
              .map(
                  route ->
                      route.lineId().equals(line.id())
                          ? route
                          : copyRouteWithLine(route, line.id()))
              .toList();
      stopsByRoute = Map.copyOf(stopsByRoute);
    }

    private static Route copyRouteWithLine(Route route, UUID lineId) {
      return new Route(
          route.id(),
          route.code(),
          lineId,
          route.name(),
          route.secondaryName(),
          route.patternType(),
          route.operationType(),
          route.distanceMeters(),
          route.runtimeSeconds(),
          route.metadata(),
          route.createdAt(),
          route.updatedAt());
    }
  }
}
