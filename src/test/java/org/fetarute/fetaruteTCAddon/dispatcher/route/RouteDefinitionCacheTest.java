package org.fetarute.fetaruteTCAddon.dispatcher.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.Test;

class RouteDefinitionCacheTest {

  @Test
  void reloadBuildsRouteDefinitionFromStops() {
    UUID companyId = UUID.randomUUID();
    UUID operatorId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID routeId = UUID.randomUUID();
    UUID stationId = UUID.randomUUID();
    Instant now = Instant.now();

    Company company =
        new Company(
            companyId,
            "INT",
            "Intercity",
            Optional.empty(),
            UUID.randomUUID(),
            CompanyStatus.ACTIVE,
            0L,
            Map.of(),
            now,
            now);
    Operator operator =
        new Operator(
            operatorId,
            "INT",
            companyId,
            "Intercity",
            Optional.empty(),
            Optional.empty(),
            0,
            Optional.empty(),
            Map.of(),
            now,
            now);
    Line line =
        new Line(
            lineId,
            "L1",
            operatorId,
            "Line 1",
            Optional.empty(),
            LineServiceType.METRO,
            Optional.empty(),
            LineStatus.ACTIVE,
            Optional.empty(),
            Map.of(),
            now,
            now);
    Route route =
        new Route(
            routeId,
            "R1",
            lineId,
            "Route 1",
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
            "STA",
            operatorId,
            Optional.empty(),
            "Station A",
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of("SURN:S:STA:1"),
            Optional.empty(),
            Map.of(),
            now,
            now);

    RouteStop stopA =
        new RouteStop(
            routeId,
            0,
            Optional.of(stationId),
            Optional.empty(),
            Optional.of(20),
            RouteStopPassType.STOP,
            Optional.empty());
    RouteStop stopB =
        new RouteStop(
            routeId,
            1,
            Optional.empty(),
            Optional.of("SURN:A:B:1:01"),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());

    StorageProvider provider = mock(StorageProvider.class);
    CompanyRepository companyRepo = mock(CompanyRepository.class);
    OperatorRepository operatorRepo = mock(OperatorRepository.class);
    LineRepository lineRepo = mock(LineRepository.class);
    RouteRepository routeRepo = mock(RouteRepository.class);
    RouteStopRepository stopRepo = mock(RouteStopRepository.class);
    StationRepository stationRepo = mock(StationRepository.class);

    when(provider.companies()).thenReturn(companyRepo);
    when(provider.operators()).thenReturn(operatorRepo);
    when(provider.lines()).thenReturn(lineRepo);
    when(provider.routes()).thenReturn(routeRepo);
    when(provider.routeStops()).thenReturn(stopRepo);
    when(provider.stations()).thenReturn(stationRepo);

    when(companyRepo.listAll()).thenReturn(List.of(company));
    when(operatorRepo.listByCompany(companyId)).thenReturn(List.of(operator));
    when(lineRepo.listByOperator(operatorId)).thenReturn(List.of(line));
    when(routeRepo.listByLine(lineId)).thenReturn(List.of(route));
    when(stopRepo.listByRoute(routeId))
        .thenReturn(new java.util.ArrayList<>(List.of(stopB, stopA)));
    when(stationRepo.findById(stationId)).thenReturn(Optional.of(station));

    RouteDefinitionCache cache = new RouteDefinitionCache(message -> {});
    cache.reload(provider);

    Optional<RouteDefinition> defOpt = cache.findById(routeId);
    assertTrue(defOpt.isPresent());
    RouteDefinition def = defOpt.get();
    assertEquals("INT:L1:R1", def.id().value());
    assertEquals(List.of(NodeId.of("SURN:S:STA:1"), NodeId.of("SURN:A:B:1:01")), def.waypoints());

    Optional<RouteDefinition> byCodes = cache.findByCodes("int", "L1", "r1");
    assertTrue(byCodes.isPresent());
    assertEquals(def, byCodes.get());
  }

  @Test
  void refreshUpdatesAndRemovesDefinition() {
    UUID operatorId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID routeId = UUID.randomUUID();
    Instant now = Instant.now();

    Operator operator =
        new Operator(
            operatorId,
            "INT",
            UUID.randomUUID(),
            "Intercity",
            Optional.empty(),
            Optional.empty(),
            0,
            Optional.empty(),
            Map.of(),
            now,
            now);
    Line line =
        new Line(
            lineId,
            "L1",
            operatorId,
            "Line 1",
            Optional.empty(),
            LineServiceType.METRO,
            Optional.empty(),
            LineStatus.ACTIVE,
            Optional.empty(),
            Map.of(),
            now,
            now);
    Route route =
        new Route(
            routeId,
            "R1",
            lineId,
            "Route 1",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            now,
            now);

    RouteStop stopA =
        new RouteStop(
            routeId,
            0,
            Optional.empty(),
            Optional.of("SURN:A:B:1:01"),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());
    RouteStop stopB =
        new RouteStop(
            routeId,
            1,
            Optional.empty(),
            Optional.of("SURN:A:B:1:02"),
            Optional.empty(),
            RouteStopPassType.STOP,
            Optional.empty());

    StorageProvider provider = mock(StorageProvider.class);
    RouteStopRepository stopRepo = mock(RouteStopRepository.class);
    StationRepository stationRepo = mock(StationRepository.class);

    when(provider.routeStops()).thenReturn(stopRepo);
    when(provider.stations()).thenReturn(stationRepo);
    when(stopRepo.listByRoute(routeId))
        .thenReturn(new java.util.ArrayList<>(List.of(stopA, stopB)))
        .thenReturn(List.of());

    RouteDefinitionCache cache = new RouteDefinitionCache(message -> {});
    Optional<RouteDefinition> loaded = cache.refresh(provider, operator, line, route);
    assertTrue(loaded.isPresent());

    Optional<RouteDefinition> removed = cache.refresh(provider, operator, line, route);
    assertTrue(removed.isEmpty());
    assertTrue(cache.findById(routeId).isEmpty());
    assertTrue(cache.findByCodes("INT", "L1", "R1").isEmpty());
  }
}
