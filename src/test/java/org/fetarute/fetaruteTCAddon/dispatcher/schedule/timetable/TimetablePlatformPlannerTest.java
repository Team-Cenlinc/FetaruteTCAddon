package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.NeighborTimetable;
import org.junit.jupiter.api.Test;

/**
 * 计划站台的区间着色。
 *
 * <p>路网：AAA 一股道，经一个路径点进 BBB 两股道。BBB:2 在来车方向正前方，BBB:1 在侧面，所以只有一辆车时排 BBB:2。 OUT（AAA→BBB）跑 120 秒，BBB 是
 * DYNAMIC 终点；BACK（BBB→AAA）从 BBB 的 DYNAMIC 始发。
 */
class TimetablePlatformPlannerTest {

  private static final String AAA = "OP:S:AAA:1";
  private static final String WAY = "OP:AAA:BBB:1:001";
  private static final String BBB1 = "OP:S:BBB:1";
  private static final String BBB2 = "OP:S:BBB:2";
  private static final String DYNAMIC = "DYNAMIC:OP:S:BBB";

  private static final UUID TABLE =
      UUID.nameUUIDFromBytes("table".getBytes(StandardCharsets.UTF_8));
  private static final UUID OUT = UUID.nameUUIDFromBytes("out".getBytes(StandardCharsets.UTF_8));
  private static final UUID BACK = UUID.nameUUIDFromBytes("back".getBytes(StandardCharsets.UTF_8));
  private static final UUID FIXED =
      UUID.nameUUIDFromBytes("fixed".getBytes(StandardCharsets.UTF_8));

  private final RailGraph graph = graph();
  private final TimetableConflictChecker.GraphIndex index =
      TimetableConflictChecker.GraphIndex.of(graph);
  private final Map<UUID, RouteDefinition> definitions =
      Map.of(
          OUT, TimetableTestFixtures.route("OUT", List.of(AAA, WAY, BBB1)),
          BACK, TimetableTestFixtures.route("BACK", List.of(BBB1, WAY, AAA)),
          FIXED, TimetableTestFixtures.route("FIXED", List.of(AAA, WAY, BBB2)));
  private final Map<UUID, List<RouteStop>> stops =
      Map.of(
          OUT, routeStops(OUT, Optional.empty(), Optional.of(DYNAMIC)),
          BACK, routeStops(BACK, Optional.of(DYNAMIC), Optional.empty()),
          FIXED, routeStops(FIXED, Optional.empty(), Optional.empty()));

  /** 只有一辆车时取来车方向正前方的那条股道。 */
  @Test
  void aLoneTrainTakesTheTrackStraightAhead() {
    Duty d1 = new Duty("D001", List.of(new Run(OUT, 1000)));

    TimetablePlatformPlanner.Result result = plan(List.of(d1), List.of());

    assertEquals(Map.of(key(d1, 0, 2), BBB2), plans(result));
    assertEquals(1, result.planned());
    assertEquals(0, result.unplaced());
  }

  /** 终到与下一班始发是同一段停留：两处计划同一条股道。 */
  @Test
  void aTurnbackKeepsOneTrackForArrivalAndTheNextDeparture() {
    Duty d1 = new Duty("D001", List.of(new Run(OUT, 1000), new Run(BACK, 1300)));

    TimetablePlatformPlanner.Result result = plan(List.of(d1), List.of());

    assertEquals(Map.of(key(d1, 0, 2), BBB2, key(d1, 1, 0), BBB2), plans(result));
    assertEquals(1, result.planned(), "终到加折返始发只算一段");
  }

  /** 两辆车在 BBB 的停留重叠：先到的拿正前方的股道，后到的拿另一条。 */
  @Test
  void overlappingTrainsGetDifferentTracks() {
    Duty d1 = new Duty("D001", List.of(new Run(OUT, 1000), new Run(BACK, 1300)));
    Duty d2 = new Duty("D002", List.of(new Run(OUT, 1100), new Run(BACK, 1400)));

    Map<String, String> plans = plans(plan(List.of(d1, d2), List.of()));

    assertEquals(BBB2, plans.get(key(d1, 0, 2)));
    assertEquals(BBB2, plans.get(key(d1, 1, 0)));
    assertEquals(BBB1, plans.get(key(d2, 0, 2)));
    assertEquals(BBB1, plans.get(key(d2, 1, 0)));
  }

  /** 前车离开后留够间隔，后车可以接着用同一条股道。 */
  @Test
  void aTrackIsReusedOnceTheSeparationHasPassed() {
    Duty d1 = new Duty("D001", List.of(new Run(OUT, 1000), new Run(BACK, 1300)));
    Duty d2 = new Duty("D002", List.of(new Run(OUT, 1300), new Run(BACK, 1600)));

    Map<String, String> plans = plans(plan(List.of(d1, d2), List.of()));

    assertEquals(BBB2, plans.get(key(d1, 0, 2)));
    assertEquals(BBB2, plans.get(key(d2, 0, 2)), "D001 1300 发车，D002 1420 才到，间隔 30 秒绰绰有余");
  }

  /** 固定股道的停靠先占住，DYNAMIC 的车让开它。 */
  @Test
  void aFixedTrackStopIsAvoided() {
    Duty d0 = new Duty("D000", List.of(new Run(FIXED, 1000)));
    Duty d1 = new Duty("D001", List.of(new Run(OUT, 1000), new Run(BACK, 1300)));

    Map<String, String> plans = plans(plan(List.of(d0, d1), List.of()));

    assertEquals(BBB1, plans.get(key(d1, 0, 2)));
  }

  /** 邻表停在固定股道上的车同样先占住。 */
  @Test
  void aNeighborOnAFixedTrackIsAvoided() {
    Duty d1 = new Duty("D001", List.of(new Run(OUT, 1000), new Run(BACK, 1300)));
    NeighborTimetable neighbor =
        new NeighborTimetable(
            UUID.randomUUID(),
            "C/OP/L2/T",
            Instant.EPOCH,
            ZoneId.of("UTC"),
            1,
            false,
            false,
            Map.of(),
            List.of(),
            List.of(
                new TimetableConflictChecker.Stay(
                    "D9",
                    new TimetableConflictChecker.Platform(BBB2, "OP:S:BBB", false),
                    900,
                    2000,
                    Optional.of("C/OP/L2/T"))),
            List.of());

    Map<String, String> plans = plans(plan(List.of(d1), List.of(neighbor)));

    assertEquals(BBB1, plans.get(key(d1, 0, 2)));
  }

  /** 股道不够时第三辆车不排，运行时照常临时选台。 */
  @Test
  void noFreeTrackLeavesTheStopUnplanned() {
    Duty d1 = new Duty("D001", List.of(new Run(OUT, 1000), new Run(BACK, 1300)));
    Duty d2 = new Duty("D002", List.of(new Run(OUT, 1050), new Run(BACK, 1350)));
    Duty d3 = new Duty("D003", List.of(new Run(OUT, 1100), new Run(BACK, 1400)));

    TimetablePlatformPlanner.Result result = plan(List.of(d1, d2, d3), List.of());

    assertEquals(2, result.planned());
    assertEquals(1, result.unplaced());
    Map<String, String> plans = plans(result);
    assertTrue(!plans.containsKey(key(d3, 0, 2)) && !plans.containsKey(key(d3, 1, 0)));
  }

  // ------------------------------------------------------------------ 夹具

  private record Run(UUID routeId, int departure) {}

  private record Duty(String code, List<Run> runs) {}

  private static String key(Duty duty, int run, int stop) {
    return duty.code() + "#" + run + "@" + stop;
  }

  private TimetablePlatformPlanner.Result plan(
      List<Duty> duties, List<NeighborTimetable> neighbors) {
    Timetable table = table(duties);
    Map<UUID, TimetableConflictChecker.RouteProfile> profiles = new LinkedHashMap<>();
    for (UUID routeId : List.of(OUT, BACK, FIXED)) {
      profiles.put(routeId, profile(routeId));
    }
    return TimetablePlatformPlanner.plan(
        new TimetablePlatformPlanner.Input(
            List.of(table),
            Map.of(TABLE, profiles),
            stops,
            definitions,
            graph,
            index,
            neighbors,
            0,
            30));
  }

  /** 计划换成"交路号#第几班@停靠序号 → 股道"，用例按交路与班次断言。 */
  private Map<String, String> plans(TimetablePlatformPlanner.Result result) {
    Map<UUID, String> keyByTrip = new LinkedHashMap<>();
    for (String code : List.of("D000", "D001", "D002", "D003")) {
      for (int run = 0; run < 3; run++) {
        keyByTrip.put(tripId(code, run), code + "#" + run);
      }
    }
    return result.plans().getOrDefault(TABLE, List.of()).stream()
        .collect(
            Collectors.toMap(
                plan -> keyByTrip.get(plan.tripId()) + "@" + plan.stopSequence(),
                PlatformPlan::nodeId));
  }

  private static UUID tripId(String dutyCode, int run) {
    return UUID.nameUUIDFromBytes((dutyCode + "#" + run).getBytes(StandardCharsets.UTF_8));
  }

  private Timetable table(List<Duty> duties) {
    List<TimetableTrip> trips = new ArrayList<>();
    List<VehicleDuty> vehicleDuties = new ArrayList<>();
    for (int d = 0; d < duties.size(); d++) {
      Duty duty = duties.get(d);
      UUID dutyId = UUID.nameUUIDFromBytes(duty.code().getBytes(StandardCharsets.UTF_8));
      List<UUID> tripIds = new ArrayList<>();
      for (int r = 0; r < duty.runs().size(); r++) {
        Run run = duty.runs().get(r);
        UUID tripId = tripId(duty.code(), r);
        tripIds.add(tripId);
        trips.add(
            new TimetableTrip(
                tripId,
                TABLE,
                run.routeId(),
                trips.size(),
                duty.code() + "-" + r,
                run.departure(),
                Optional.of(dutyId)));
      }
      int start = duty.runs().get(0).departure();
      int end = duty.runs().get(duty.runs().size() - 1).departure() + 120;
      vehicleDuties.add(
          new VehicleDuty(
              dutyId,
              TABLE,
              d,
              duty.code(),
              "OP:D:DEP:1",
              "OP:D:DEP:1",
              Optional.empty(),
              Optional.empty(),
              tripIds,
              start,
              end,
              end,
              VehicleDuty.CloseReason.HORIZON_END));
    }
    return new Timetable(
        TABLE,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "T",
        "T",
        TimetableStatus.DRAFT,
        ZoneId.of("UTC"),
        0,
        86_400,
        List.of(routePlan(OUT, "OUT"), routePlan(BACK, "BACK"), routePlan(FIXED, "FIXED")),
        trips,
        vehicleDuties,
        Optional.empty(),
        Instant.EPOCH,
        Instant.EPOCH);
  }

  private TimetableRoutePlan routePlan(UUID routeId, String code) {
    List<NodeId> waypoints = definitions.get(routeId).waypoints();
    return new TimetableRoutePlan(
        routeId,
        code,
        RouteOperationType.OPERATION,
        1,
        timetableStops(),
        waypoints.get(0).value(),
        waypoints.get(2).value(),
        Optional.empty(),
        Optional.empty());
  }

  private TimetableConflictChecker.RouteProfile profile(UUID routeId) {
    List<TimetableStop> timetableStops = timetableStops();
    return new TimetableConflictChecker.RouteProfile(
        routeId,
        routeId.toString(),
        timetableStops,
        List.of(),
        TimetableConflictChecker.platformsOf(
            timetableStops,
            stops.get(routeId),
            definitions.get(routeId).waypoints(),
            index.nodeTypes()));
  }

  /** 首站发车、60 秒过路径点、120 秒到终点。 */
  private static List<TimetableStop> timetableStops() {
    return List.of(
        new TimetableStop(0, Optional.empty(), Optional.empty(), 0, 0, RouteStopPassType.STOP),
        new TimetableStop(1, Optional.empty(), Optional.empty(), 60, 60, RouteStopPassType.PASS),
        new TimetableStop(
            2, Optional.empty(), Optional.empty(), 120, 120, RouteStopPassType.TERMINATE));
  }

  private static List<RouteStop> routeStops(
      UUID routeId, Optional<String> originNotes, Optional<String> terminalNotes) {
    return List.of(
        new RouteStop(
            routeId,
            0,
            Optional.empty(),
            Optional.empty(),
            Optional.of(0),
            RouteStopPassType.STOP,
            originNotes),
        new RouteStop(
            routeId,
            1,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            RouteStopPassType.PASS,
            Optional.empty()),
        new RouteStop(
            routeId,
            2,
            Optional.empty(),
            Optional.empty(),
            Optional.of(0),
            RouteStopPassType.TERMINATE,
            terminalNotes));
  }

  /** AAA(0,0) → 路径点(10,0) → BBB:2 在正前方 (20,0)，BBB:1 在侧面 (10,10)。 */
  private static RailGraph graph() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    node(nodes, AAA, NodeType.STATION, 0, 0);
    node(nodes, WAY, NodeType.WAYPOINT, 10, 0);
    node(nodes, BBB1, NodeType.STATION, 10, 10);
    node(nodes, BBB2, NodeType.STATION, 20, 0);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    edge(edges, AAA, WAY);
    edge(edges, WAY, BBB1);
    edge(edges, WAY, BBB2);
    return new SimpleRailGraph(nodes, edges, Set.of());
  }

  private static void node(Map<NodeId, RailNode> nodes, String id, NodeType type, int x, int z) {
    NodeId nodeId = NodeId.of(id);
    nodes.put(
        nodeId,
        new SignRailNode(nodeId, type, new Vector(x, 64, z), Optional.empty(), Optional.empty()));
  }

  private static void edge(Map<EdgeId, RailEdge> edges, String a, String b) {
    NodeId from = NodeId.of(a);
    NodeId to = NodeId.of(b);
    EdgeId id = EdgeId.undirected(from, to);
    edges.put(id, new RailEdge(id, from, to, 10, 8.0, true, Optional.empty()));
  }
}
