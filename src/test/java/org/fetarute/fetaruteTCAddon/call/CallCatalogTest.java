package org.fetarute.fetaruteTCAddon.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.LineServiceType;
import org.fetarute.fetaruteTCAddon.company.model.LineStatus;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.display.pids.PidsStationKey;
import org.junit.jupiter.api.Test;

/** 车站上能叫的车：按线路、运营类型、开往与站台分方向；本站是终点的、线路没开放叫车的不列。 */
class CallCatalogTest {

  private static final Instant T = Instant.EPOCH;
  private static final PidsStationKey PPK = new PidsStationKey("SURC", "PPK");
  private static final Operator OPERATOR =
      new Operator(
          UUID.randomUUID(),
          "SURC",
          UUID.randomUUID(),
          "SURC",
          Optional.empty(),
          Optional.empty(),
          0,
          Optional.empty(),
          Map.of(),
          T,
          T);
  private static final Line WS = line("WS");
  private static final Line MT = line("MT");

  @Test
  void listsDirectionsThroughTheStationButNotTerminatingOnes() {
    List<RouteDefinitionCache.RouteEntry> entries =
        List.of(
            route(
                WS, "WS-N", RoutePatternType.LOCAL, "SURC:S:AAA:1", "SURC:S:PPK:2", "SURC:S:NTA:1"),
            route(
                WS, "WS-S", RoutePatternType.LOCAL, "SURC:S:NTA:1", "SURC:S:PPK:1", "SURC:S:AAA:1"),
            route(WS, "WS-T", RoutePatternType.LOCAL, "SURC:S:AAA:1", "SURC:S:PPK:2"));

    List<CallCatalog.CallDirection> directions =
        CallCatalog.directions(entries, null, line -> true, PPK, Set.of());

    assertEquals(2, directions.size(), "本站终到的交路不列");
    CallCatalog.CallDirection toAaa = directions.get(0);
    CallCatalog.CallDirection toNta = directions.get(1);
    assertEquals(new PidsStationKey("SURC", "AAA"), toAaa.destination());
    assertEquals("1", toAaa.platformLabel());
    assertEquals(new PidsStationKey("SURC", "NTA"), toNta.destination());
    assertEquals("2", toNta.platformLabel());
    assertEquals(1, toNta.routes().get(0).stopIndex());
    assertFalse(toNta.routes().get(0).fromDepot());
    assertEquals("SURC:S:AAA:1", toNta.routes().get(0).startNode());
  }

  @Test
  void screenPlatformsAndClosedLinesFilterDirections() {
    List<RouteDefinitionCache.RouteEntry> entries =
        List.of(
            route(
                WS, "WS-N", RoutePatternType.LOCAL, "SURC:S:AAA:1", "SURC:S:PPK:2", "SURC:S:NTA:1"),
            route(
                WS, "WS-S", RoutePatternType.LOCAL, "SURC:S:NTA:1", "SURC:S:PPK:1", "SURC:S:AAA:1"),
            route(
                MT,
                "MT-N",
                RoutePatternType.LOCAL,
                "SURC:S:AAA:3",
                "SURC:S:PPK:2",
                "SURC:S:NTA:3"));

    List<CallCatalog.CallDirection> onPlatformTwo =
        CallCatalog.directions(entries, null, line -> line.code().equals("WS"), PPK, Set.of("02"));

    assertEquals(1, onPlatformTwo.size(), "只列屏幕站台上、开放叫车的线路");
    assertEquals("WS", onPlatformTwo.get(0).lineCode());
    assertEquals(new PidsStationKey("SURC", "NTA"), onPlatformTwo.get(0).destination());
  }

  @Test
  void separatesPatternsAndGroupsRoutesOfTheSameDirection() {
    List<RouteDefinitionCache.RouteEntry> entries =
        List.of(
            route(
                WS,
                "WS-N1",
                RoutePatternType.LOCAL,
                "SURC:S:AAA:1",
                "SURC:S:PPK:2",
                "SURC:S:NTA:1"),
            route(
                WS,
                "WS-N2",
                RoutePatternType.LOCAL,
                "SURC:S:BBB:1",
                "SURC:S:PPK:2",
                "SURC:S:NTA:1"),
            route(
                WS,
                "WS-NR",
                RoutePatternType.RAPID,
                "SURC:S:AAA:1",
                "SURC:S:PPK:2",
                "SURC:S:NTA:1"));

    List<CallCatalog.CallDirection> directions =
        CallCatalog.directions(entries, null, line -> true, PPK, Set.of());

    assertEquals(2, directions.size(), "各停、快速分开列");
    assertEquals(RoutePatternType.LOCAL, directions.get(0).pattern());
    assertEquals(2, directions.get(0).routes().size(), "同一方向的两条交路归在一起");
    assertEquals(RoutePatternType.RAPID, directions.get(1).pattern());
  }

  @Test
  void depotRoutesAreMarkedFromDepot() {
    UUID routeId = UUID.randomUUID();
    List<String> nodes = List.of("SURC:D:DEP:1", "SURC:S:PPK:2", "SURC:S:NTA:1");
    List<RouteStop> stops = new ArrayList<>();
    stops.add(
        new RouteStop(
            routeId,
            0,
            Optional.empty(),
            Optional.of(nodes.get(0)),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("CRET SURC:D:DEP:1")));
    stops.add(stop(routeId, 1, nodes.get(1), RouteStopPassType.STOP));
    stops.add(stop(routeId, 2, nodes.get(2), RouteStopPassType.TERMINATE));

    List<CallCatalog.CallDirection> directions =
        CallCatalog.directions(
            List.of(entry(routeId, WS, "WS-D", RoutePatternType.LOCAL, nodes, stops)),
            null,
            line -> true,
            PPK,
            Set.of());

    assertEquals(1, directions.size());
    assertTrue(directions.get(0).routes().get(0).fromDepot());
  }

  /** 出库交路（首站是车库、沿途停站）也能叫：从车库出车；首站不是车库的出库交路出不了车，不列。 */
  @Test
  void createRoutesFromTheDepotAreCallable() {
    UUID routeId = UUID.randomUUID();
    List<String> nodes = List.of("SURC:D:DEP:3", "SURC:S:PPK:1", "SURC:S:NTA:1");
    List<RouteStop> stops = new ArrayList<>();
    stops.add(
        new RouteStop(
            routeId,
            0,
            Optional.empty(),
            Optional.of(nodes.get(0)),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("CRET SURC:D:DEP:3")));
    stops.add(stop(routeId, 1, nodes.get(1), RouteStopPassType.STOP));
    stops.add(stop(routeId, 2, nodes.get(2), RouteStopPassType.TERMINATE));
    UUID bareId = UUID.randomUUID();
    List<String> bareNodes = List.of("SURC:S:AAA:1", "SURC:S:PPK:1", "SURC:S:BBB:1");
    List<RouteStop> bareStops =
        List.of(
            stop(bareId, 0, bareNodes.get(0), RouteStopPassType.STOP),
            stop(bareId, 1, bareNodes.get(1), RouteStopPassType.STOP),
            stop(bareId, 2, bareNodes.get(2), RouteStopPassType.TERMINATE));

    List<CallCatalog.CallDirection> directions =
        CallCatalog.directions(
            List.of(
                entry(
                    routeId,
                    WS,
                    "WS-F",
                    RoutePatternType.LOCAL,
                    RouteOperationType.CREATE,
                    nodes,
                    stops),
                entry(
                    bareId,
                    WS,
                    "WS-X",
                    RoutePatternType.LOCAL,
                    RouteOperationType.CREATE,
                    bareNodes,
                    bareStops)),
            null,
            line -> true,
            PPK,
            Set.of());

    assertEquals(1, directions.size(), "首站不是车库的出库交路不列");
    assertEquals(new PidsStationKey("SURC", "NTA"), directions.get(0).destination());
    assertEquals(CallCatalog.Origin.DEPOT, directions.get(0).routes().get(0).origin());
  }

  /** 沿途停站的回库交路也能叫：开往最后一个载客站，只区间生成或靠折返车；车库那一段不算终点。 */
  @Test
  void returnRoutesCarryingPassengersAreEntryOnly() {
    UUID routeId = UUID.randomUUID();
    List<String> nodes =
        List.of("SURC:S:NTA:2", "SURC:S:BBB:2", "SURC:S:PPK:2", "SURC:S:AAA:2", "SURC:D:DEP:1");
    List<RouteStop> stops = new ArrayList<>();
    stops.add(stop(routeId, 0, nodes.get(0), RouteStopPassType.STOP));
    stops.add(stop(routeId, 1, nodes.get(1), RouteStopPassType.STOP));
    stops.add(stop(routeId, 2, nodes.get(2), RouteStopPassType.STOP));
    stops.add(stop(routeId, 3, nodes.get(3), RouteStopPassType.TERMINATE));
    stops.add(
        new RouteStop(
            routeId,
            4,
            Optional.empty(),
            Optional.of(nodes.get(4)),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.of("DSTY SURC:D:DEP:1")));
    List<RouteDefinitionCache.RouteEntry> entries =
        List.of(
            entry(
                routeId,
                MT,
                "MT-D",
                RoutePatternType.LOCAL,
                RouteOperationType.RETURN,
                nodes,
                stops));

    List<CallCatalog.CallDirection> directions =
        CallCatalog.directions(entries, null, line -> true, PPK, Set.of());

    assertEquals(1, directions.size());
    assertEquals(new PidsStationKey("SURC", "AAA"), directions.get(0).destination());
    assertEquals(CallCatalog.Origin.ENTRY, directions.get(0).routes().get(0).origin());
    assertFalse(directions.get(0).routes().get(0).fromDepot());
    assertEquals(
        1,
        CallCatalog.directions(
                entries, null, line -> true, new PidsStationKey("SURC", "BBB"), Set.of())
            .size(),
        "第二站上游生成不了车也列：车源由折返车（开进首站再折返）判定");
    assertEquals(
        List.of(new PidsStationKey("SURC", "NTA"), new PidsStationKey("SURC", "BBB"), PPK),
        CallCatalog.stationsServed(entries, null, MT.id()),
        "回库交路停的站也算线路停车的车站");
  }

  /** 线路停车的车站：只算本线运营交路、在终点之前停车的站，按交路顺序去重。 */
  @Test
  void stationsServedListsEveryStopBeforeTheTerminal() {
    List<RouteDefinitionCache.RouteEntry> entries =
        List.of(
            route(
                WS, "WS-N", RoutePatternType.LOCAL, "SURC:S:AAA:1", "SURC:S:PPK:2", "SURC:S:NTA:1"),
            route(
                WS, "WS-S", RoutePatternType.LOCAL, "SURC:S:NTA:1", "SURC:S:PPK:1", "SURC:S:AAA:1"),
            route(MT, "MT-N", RoutePatternType.LOCAL, "SURC:S:CCC:1", "SURC:S:DDD:1"));

    assertEquals(
        List.of(
            new PidsStationKey("SURC", "AAA"),
            new PidsStationKey("SURC", "PPK"),
            new PidsStationKey("SURC", "NTA")),
        CallCatalog.stationsServed(entries, null, WS.id()));
  }

  private static RouteDefinitionCache.RouteEntry route(
      Line line, String code, RoutePatternType pattern, String... nodes) {
    UUID routeId = UUID.randomUUID();
    List<RouteStop> stops = new ArrayList<>();
    for (int i = 0; i < nodes.length; i++) {
      stops.add(
          stop(
              routeId,
              i,
              nodes[i],
              i == nodes.length - 1 ? RouteStopPassType.TERMINATE : RouteStopPassType.STOP));
    }
    return entry(routeId, line, code, pattern, List.of(nodes), stops);
  }

  private static RouteDefinitionCache.RouteEntry entry(
      UUID routeId,
      Line line,
      String code,
      RoutePatternType pattern,
      List<String> nodes,
      List<RouteStop> stops) {
    return entry(routeId, line, code, pattern, RouteOperationType.OPERATION, nodes, stops);
  }

  private static RouteDefinitionCache.RouteEntry entry(
      UUID routeId,
      Line line,
      String code,
      RoutePatternType pattern,
      RouteOperationType operationType,
      List<String> nodes,
      List<RouteStop> stops) {
    Route route =
        new Route(
            routeId,
            code,
            line.id(),
            code,
            Optional.empty(),
            pattern,
            operationType,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            T,
            T);
    return new RouteDefinitionCache.RouteEntry(
        routeId,
        new RouteDefinition(
            new RouteId("SURC:" + line.code() + ":" + code),
            nodes.stream().map(NodeId::of).toList(),
            Optional.empty()),
        new RouteDefinitionCache.RouteRecord(OPERATOR, line, route),
        stops);
  }

  private static RouteStop stop(UUID routeId, int sequence, String node, RouteStopPassType type) {
    return new RouteStop(
        routeId,
        sequence,
        Optional.empty(),
        Optional.of(node),
        Optional.empty(),
        type,
        Optional.empty());
  }

  private static Line line(String code) {
    return new Line(
        UUID.randomUUID(),
        code,
        UUID.randomUUID(),
        code,
        Optional.empty(),
        LineServiceType.METRO,
        Optional.empty(),
        LineStatus.ACTIVE,
        Optional.empty(),
        Map.of(),
        T,
        T);
  }
}
