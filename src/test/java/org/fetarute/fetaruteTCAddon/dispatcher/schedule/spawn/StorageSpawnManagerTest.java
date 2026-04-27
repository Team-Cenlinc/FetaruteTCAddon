package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.stream.Stream;
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
  void pollDueTicketsDropsStaleQueuedTicketAndReleasesBacklog() {
    StorageProvider provider = mockProvider(enabledRoute());
    StorageSpawnManager.SpawnManagerSettings settings =
        new StorageSpawnManager.SpawnManagerSettings(
            Duration.ofSeconds(999), Duration.ZERO, 1, 1, 10, Duration.ofHours(1));
    StorageSpawnManager manager = new StorageSpawnManager(settings, null);

    Instant now = Instant.parse("2026-01-19T00:00:00Z");
    SpawnTicket first = manager.pollDueTickets(provider, now).get(0);
    SpawnTicket retry = first.withRetry(now.plusSeconds(5), "gate-blocked:STOP");
    manager.requeue(retry);

    List<SpawnTicket> recovered = manager.pollDueTickets(provider, now.plusSeconds(3600));

    assertEquals(1, recovered.size(), "清理超龄票据后应释放 backlog 并允许生成新票据");
    assertNotEquals(first.id(), recovered.get(0).id(), "返回的应是新票据，而不是超龄重试票据");
    assertTrue(manager.snapshotQueue().isEmpty());
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

    // 交路组引入首发相位后，同组 route 不再同 tick 同时出票。
    assertEquals(1, due.size());
    assertEquals(2, manager.snapshotPlan().size());

    Map<String, SpawnService> servicesByRoute =
        manager.snapshotPlan().services().stream()
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

    assertEquals(1, due.size());
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

  @Test
  void pollDueTicketsSharesHeadwayWithinSameCirculationGroup() {
    StorageProvider provider = mockProvider(operationAndReturnSharedCirculationGroup());
    StorageSpawnManager.SpawnManagerSettings settings =
        new StorageSpawnManager.SpawnManagerSettings(
            Duration.ofSeconds(999), Duration.ZERO, 5, 10, 10);
    StorageSpawnManager manager = new StorageSpawnManager(settings, null);

    Instant now = Instant.parse("2026-01-19T00:00:00Z");
    manager.pollDueTickets(provider, now);

    Map<String, SpawnService> servicesByRoute =
        manager.snapshotPlan().services().stream()
            .collect(Collectors.toMap(SpawnService::routeCode, Function.identity()));
    assertEquals(2, servicesByRoute.size());
    assertEquals(Duration.ofSeconds(200), servicesByRoute.get("R-OP").baseHeadway());
    assertEquals(Duration.ofSeconds(200), servicesByRoute.get("R-RET").baseHeadway());
  }

  @Test
  void pollDueTicketsSupportsGroupBaselineWithoutLineBaseline() {
    StorageProvider provider = mockProvider(groupBaselineWithoutLineBaseline());
    StorageSpawnManager.SpawnManagerSettings settings =
        new StorageSpawnManager.SpawnManagerSettings(
            Duration.ofSeconds(999), Duration.ZERO, 5, 10, 10);
    StorageSpawnManager manager = new StorageSpawnManager(settings, null);

    Instant now = Instant.parse("2026-01-19T00:00:00Z");
    List<SpawnTicket> due = manager.pollDueTickets(provider, now);

    assertEquals(1, due.size());
    Map<String, SpawnService> servicesByRoute =
        manager.snapshotPlan().services().stream()
            .collect(Collectors.toMap(SpawnService::routeCode, Function.identity()));
    assertEquals(2, servicesByRoute.size());
    assertEquals(Duration.ofSeconds(240), servicesByRoute.get("OP-G").baseHeadway());
    assertEquals(Duration.ofSeconds(240), servicesByRoute.get("RET-G").baseHeadway());
  }

  @Test
  void pollDueTicketsAllowsSingleOperationRoutePerExplicitGroupWithoutRouteWeights() {
    StorageProvider provider = mockProvider(twoSingleRouteGroupsWithoutRouteWeights());
    StorageSpawnManager.SpawnManagerSettings settings =
        new StorageSpawnManager.SpawnManagerSettings(
            Duration.ofSeconds(999), Duration.ZERO, 5, 10, 10);
    StorageSpawnManager manager = new StorageSpawnManager(settings, null);

    Instant now = Instant.parse("2026-01-19T00:00:00Z");
    List<SpawnTicket> due = manager.pollDueTickets(provider, now);

    assertEquals(2, due.size());
    Map<String, SpawnService> servicesByRoute =
        manager.snapshotPlan().services().stream()
            .collect(Collectors.toMap(SpawnService::routeCode, Function.identity()));
    assertEquals(2, servicesByRoute.size());
    assertEquals(Duration.ofSeconds(120), servicesByRoute.get("OP-A").baseHeadway());
    assertEquals(Duration.ofSeconds(180), servicesByRoute.get("OP-B").baseHeadway());
  }

  @Test
  void pollDueTicketsMixesExplicitGroupsAcrossPollsWhenPollLimitIsOne() {
    StorageProvider provider = mockProvider(twoSingleRouteGroupsWithoutRouteWeights());
    StorageSpawnManager.SpawnManagerSettings settings =
        new StorageSpawnManager.SpawnManagerSettings(
            Duration.ofSeconds(999), Duration.ZERO, 5, 10, 1);
    StorageSpawnManager manager = new StorageSpawnManager(settings, null);

    Instant now = Instant.parse("2026-01-19T00:00:00Z");
    List<SpawnTicket> firstPoll = manager.pollDueTickets(provider, now);
    List<SpawnTicket> secondPoll = manager.pollDueTickets(provider, now.plusSeconds(1));

    Set<String> routes =
        Stream.concat(firstPoll.stream(), secondPoll.stream())
            .map(ticket -> ticket.service().routeCode())
            .collect(Collectors.toSet());
    assertEquals(1, firstPoll.size());
    assertEquals(1, secondPoll.size());
    assertEquals(Set.of("OP-A", "OP-B"), routes);
  }

  @Test
  void pollDueTicketsIncludesCreateRouteEvenWhenOperationWeightsMissing() {
    StorageProvider provider = mockProvider(multiOperationWithoutWeightAndCreateRoute());
    StorageSpawnManager.SpawnManagerSettings settings =
        new StorageSpawnManager.SpawnManagerSettings(
            Duration.ofSeconds(999), Duration.ZERO, 5, 10, 10);
    StorageSpawnManager manager = new StorageSpawnManager(settings, null);

    Instant now = Instant.parse("2026-01-19T00:00:00Z");
    List<SpawnTicket> due = manager.pollDueTickets(provider, now);

    assertEquals(1, due.size());
    assertEquals(1, manager.snapshotPlan().size());
    assertEquals("CRT", due.get(0).service().routeCode());
  }

  @Test
  void pollDueTicketsKeepsReturnRouteWhenReturnWeightIsZero() {
    StorageProvider provider =
        mockProvider(operationAndReturnSharedCirculationGroupWithReturnWeightZero());
    StorageSpawnManager.SpawnManagerSettings settings =
        new StorageSpawnManager.SpawnManagerSettings(
            Duration.ofSeconds(999), Duration.ZERO, 5, 10, 10);
    StorageSpawnManager manager = new StorageSpawnManager(settings, null);

    Instant now = Instant.parse("2026-01-19T00:00:00Z");
    List<SpawnTicket> due = manager.pollDueTickets(provider, now);

    assertEquals(1, due.size());
    Map<String, SpawnService> servicesByRoute =
        manager.snapshotPlan().services().stream()
            .collect(Collectors.toMap(SpawnService::routeCode, Function.identity()));
    assertEquals(2, servicesByRoute.size());
    assertTrue(servicesByRoute.containsKey("R-OP"));
    assertTrue(servicesByRoute.containsKey("R-RET"));
    assertEquals(Duration.ofSeconds(200), servicesByRoute.get("R-OP").baseHeadway());
    assertEquals(Duration.ofSeconds(200), servicesByRoute.get("R-RET").baseHeadway());
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

  private static Fixture operationAndReturnSharedCirculationGroup() {
    UUID companyId = UUID.randomUUID();
    UUID ownerIdentityId = UUID.randomUUID();
    UUID operatorId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID operationRouteId = UUID.randomUUID();
    UUID returnRouteId = UUID.randomUUID();
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
            Optional.of(100),
            Map.of(),
            ts,
            ts);
    Route operationRoute =
        new Route(
            operationRouteId,
            "R-OP",
            lineId,
            "Operation",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of("spawn_enabled", true),
            ts,
            ts);
    Route returnRoute =
        new Route(
            returnRouteId,
            "R-RET",
            lineId,
            "Return",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.RETURN,
            Optional.empty(),
            Optional.empty(),
            Map.of("spawn_enabled", true),
            ts,
            ts);

    RouteStop opStart =
        new RouteStop(
            operationRouteId,
            0,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.of("DYNAMIC:SURN:S:PPK"));
    RouteStop opNext =
        new RouteStop(
            operationRouteId,
            1,
            Optional.empty(),
            Optional.of("SURN:S:RVS:1"),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());
    RouteStop retStart =
        new RouteStop(
            returnRouteId,
            0,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.of("DYNAMIC:SURN:S:PPK"));
    RouteStop retNext =
        new RouteStop(
            returnRouteId,
            1,
            Optional.empty(),
            Optional.of("SURN:S:OFL:1"),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());

    return new Fixture(
        company,
        operator,
        line,
        List.of(operationRoute, returnRoute),
        Map.of(
            operationRouteId, List.of(opStart, opNext), returnRouteId, List.of(retStart, retNext)));
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

  private static Fixture multiOperationWithoutWeightAndCreateRoute() {
    UUID companyId = UUID.randomUUID();
    UUID ownerIdentityId = UUID.randomUUID();
    UUID operatorId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID operationRouteAId = UUID.randomUUID();
    UUID operationRouteBId = UUID.randomUUID();
    UUID createRouteId = UUID.randomUUID();
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

    Route operationA =
        new Route(
            operationRouteAId,
            "OP-A",
            lineId,
            "Operation A",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            ts,
            ts);
    Route operationB =
        new Route(
            operationRouteBId,
            "OP-B",
            lineId,
            "Operation B",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            ts,
            ts);
    Route createRoute =
        new Route(
            createRouteId,
            "CRT",
            lineId,
            "Create",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.CREATE,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            ts,
            ts);

    RouteStop opAStart =
        new RouteStop(
            operationRouteAId,
            0,
            Optional.empty(),
            Optional.of("SURN:D:DEPOT:1"),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("CRET SURN:D:DEPOT:1"));
    RouteStop opBStart =
        new RouteStop(
            operationRouteBId,
            0,
            Optional.empty(),
            Optional.of("SURN:D:DEPOT:2"),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("CRET SURN:D:DEPOT:2"));
    RouteStop createStart =
        new RouteStop(
            createRouteId,
            0,
            Optional.empty(),
            Optional.of("SURN:D:DEPOT:1"),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("CRET SURN:D:DEPOT:1"));

    return new Fixture(
        company,
        operator,
        line,
        List.of(operationA, operationB, createRoute),
        Map.of(
            operationRouteAId,
            List.of(opAStart),
            operationRouteBId,
            List.of(opBStart),
            createRouteId,
            List.of(createStart)));
  }

  private static Fixture operationAndReturnSharedCirculationGroupWithReturnWeightZero() {
    UUID companyId = UUID.randomUUID();
    UUID ownerIdentityId = UUID.randomUUID();
    UUID operatorId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID operationRouteId = UUID.randomUUID();
    UUID returnRouteId = UUID.randomUUID();
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
            Optional.of(100),
            Map.of(),
            ts,
            ts);

    Route operationRoute =
        new Route(
            operationRouteId,
            "R-OP",
            lineId,
            "Operation",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of("spawn_enabled", true, "spawn_weight", 1),
            ts,
            ts);
    Route returnRoute =
        new Route(
            returnRouteId,
            "R-RET",
            lineId,
            "Return",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.RETURN,
            Optional.empty(),
            Optional.empty(),
            Map.of("spawn_enabled", true, "spawn_weight", 0),
            ts,
            ts);

    RouteStop opStart =
        new RouteStop(
            operationRouteId,
            0,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.of("DYNAMIC:SURN:S:PPK"));
    RouteStop opNext =
        new RouteStop(
            operationRouteId,
            1,
            Optional.empty(),
            Optional.of("SURN:S:RVS:1"),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());
    RouteStop retStart =
        new RouteStop(
            returnRouteId,
            0,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.of("DYNAMIC:SURN:S:PPK"));
    RouteStop retNext =
        new RouteStop(
            returnRouteId,
            1,
            Optional.empty(),
            Optional.of("SURN:S:OFL:1"),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());

    return new Fixture(
        company,
        operator,
        line,
        List.of(operationRoute, returnRoute),
        Map.of(
            operationRouteId, List.of(opStart, opNext), returnRouteId, List.of(retStart, retNext)));
  }

  private static Fixture groupBaselineWithoutLineBaseline() {
    UUID companyId = UUID.randomUUID();
    UUID ownerIdentityId = UUID.randomUUID();
    UUID operatorId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID operationRouteId = UUID.randomUUID();
    UUID returnRouteId = UUID.randomUUID();
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
            Optional.empty(),
            Map.of(
                LineSpawnMetadata.KEY_GROUPS,
                LineSpawnMetadata.toGroupMetadata(
                    List.of(new SpawnGroup("ppk-turnback", Optional.of(120))))),
            ts,
            ts);
    Map<String, Object> sharedMeta =
        Map.of("spawn_enabled", true, "spawn_group", "ppk-turnback", "spawn_weight", 1);
    Route operationRoute =
        new Route(
            operationRouteId,
            "OP-G",
            lineId,
            "Operation",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            sharedMeta,
            ts,
            ts);
    Route returnRoute =
        new Route(
            returnRouteId,
            "RET-G",
            lineId,
            "Return",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.RETURN,
            Optional.empty(),
            Optional.empty(),
            sharedMeta,
            ts,
            ts);

    RouteStop opStart =
        new RouteStop(
            operationRouteId,
            0,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.of("DYNAMIC:SURN:S:PPK"));
    RouteStop opNext =
        new RouteStop(
            operationRouteId,
            1,
            Optional.empty(),
            Optional.of("SURN:S:RVS:1"),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());
    RouteStop retStart =
        new RouteStop(
            returnRouteId,
            0,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.of("DYNAMIC:SURN:S:PPK"));
    RouteStop retNext =
        new RouteStop(
            returnRouteId,
            1,
            Optional.empty(),
            Optional.of("SURN:S:OFL:1"),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());

    return new Fixture(
        company,
        operator,
        line,
        List.of(operationRoute, returnRoute),
        Map.of(
            operationRouteId, List.of(opStart, opNext), returnRouteId, List.of(retStart, retNext)));
  }

  private static Fixture twoSingleRouteGroupsWithoutRouteWeights() {
    UUID companyId = UUID.randomUUID();
    UUID ownerIdentityId = UUID.randomUUID();
    UUID operatorId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID routeAId = UUID.randomUUID();
    UUID routeBId = UUID.randomUUID();
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
            Optional.empty(),
            Map.of(
                LineSpawnMetadata.KEY_GROUPS,
                LineSpawnMetadata.toGroupMetadata(
                    List.of(
                        new SpawnGroup("group-a", Optional.of(120)),
                        new SpawnGroup("group-b", Optional.of(180))))),
            ts,
            ts);
    Route routeA =
        new Route(
            routeAId,
            "OP-A",
            lineId,
            "Operation A",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of("spawn_group", "group-a"),
            ts,
            ts);
    Route routeB =
        new Route(
            routeBId,
            "OP-B",
            lineId,
            "Operation B",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of("spawn_group", "group-b"),
            ts,
            ts);
    RouteStop routeAStart =
        new RouteStop(
            routeAId,
            0,
            Optional.empty(),
            Optional.of("SURN:D:DEPOT:1"),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("CRET SURN:D:DEPOT:1"));
    RouteStop routeBStart =
        new RouteStop(
            routeBId,
            0,
            Optional.empty(),
            Optional.of("SURN:D:DEPOT:2"),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("CRET SURN:D:DEPOT:2"));

    return new Fixture(
        company,
        operator,
        line,
        List.of(routeA, routeB),
        Map.of(routeAId, List.of(routeAStart), routeBId, List.of(routeBStart)));
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
