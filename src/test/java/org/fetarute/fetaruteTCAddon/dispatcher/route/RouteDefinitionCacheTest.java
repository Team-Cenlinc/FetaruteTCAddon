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
            List.of(),
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
            RouteStopPassType.TERMINATE,
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
    assertEquals(RouteLifecycleMode.REUSE_AT_TERM, def.lifecycleMode());

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

  @Test
  void reloadIncludesDynamicStopsInWaypoints() {
    UUID companyId = UUID.randomUUID();
    UUID operatorId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID routeId = UUID.randomUUID();
    Instant now = Instant.now();

    Company company =
        new Company(
            companyId,
            "SURC",
            "SUR Company",
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
            "SURC",
            companyId,
            "SUR Corp",
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
            "MT",
            operatorId,
            "Metro Line",
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
            "MT-1N",
            lineId,
            "北陆小交路",
            Optional.empty(),
            RoutePatternType.LOCAL,
            RouteOperationType.OPERATION,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            now,
            now);

    // 模拟 MT-1N_ShortC 的停靠表结构
    // seq 0: CRET DYNAMIC:SURC:D:OFL (无 waypointNodeId)
    // seq 1: SURC:OFL:MLU:1:003
    // seq 2: SURC:S:OFL:1 (站台)
    // seq 3: SURC:S:PPK:1 (站台)
    // seq 4: DYNAMIC:SURC:S:PPK (无 waypointNodeId)
    RouteStop stopCret =
        new RouteStop(
            routeId,
            0,
            Optional.empty(),
            Optional.empty(), // 无 waypointNodeId
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("CRET DYNAMIC:SURC:D:OFL"));
    RouteStop stopWaypoint =
        new RouteStop(
            routeId,
            1,
            Optional.empty(),
            Optional.of("SURC:OFL:MLU:1:003"),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.empty());
    RouteStop stopOfl =
        new RouteStop(
            routeId,
            2,
            Optional.empty(),
            Optional.of("SURC:S:OFL:1"),
            Optional.of(20),
            RouteStopPassType.STOP,
            Optional.empty());
    RouteStop stopPpkStation =
        new RouteStop(
            routeId,
            3,
            Optional.empty(),
            Optional.of("SURC:S:PPK:1"),
            Optional.of(20),
            RouteStopPassType.STOP,
            Optional.empty());
    RouteStop stopTermDynamic =
        new RouteStop(
            routeId,
            4,
            Optional.empty(),
            Optional.empty(), // 无 waypointNodeId
            Optional.of(20),
            RouteStopPassType.TERMINATE,
            Optional.of("DYNAMIC:SURC:S:PPK"));

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
        .thenReturn(
            new java.util.ArrayList<>(
                List.of(stopCret, stopWaypoint, stopOfl, stopPpkStation, stopTermDynamic)));

    RouteDefinitionCache cache = new RouteDefinitionCache(message -> {});
    cache.reload(provider);

    Optional<RouteDefinition> defOpt = cache.findById(routeId);
    assertTrue(defOpt.isPresent());
    RouteDefinition def = defOpt.get();

    // 验证 waypoints 包含 5 个节点（包括 DYNAMIC stop 生成的占位节点）
    List<NodeId> waypoints = def.waypoints();
    assertEquals(5, waypoints.size());

    // 验证首站（CRET DYNAMIC:SURC:D:OFL）生成 SURC:D:OFL:1
    assertEquals(NodeId.of("SURC:D:OFL:1"), waypoints.get(0));
    // 验证普通 waypoint
    assertEquals(NodeId.of("SURC:OFL:MLU:1:003"), waypoints.get(1));
    // 验证站台节点
    assertEquals(NodeId.of("SURC:S:OFL:1"), waypoints.get(2));
    assertEquals(NodeId.of("SURC:S:PPK:1"), waypoints.get(3));
    // 验证终点（DYNAMIC:SURC:S:PPK）生成 SURC:S:PPK:1
    assertEquals(NodeId.of("SURC:S:PPK:1"), waypoints.get(4));

    // 验证 RouteStop 列表与 waypoints 长度一致
    List<RouteStop> stops = cache.listStops(def.id());
    assertEquals(waypoints.size(), stops.size());
  }
}
