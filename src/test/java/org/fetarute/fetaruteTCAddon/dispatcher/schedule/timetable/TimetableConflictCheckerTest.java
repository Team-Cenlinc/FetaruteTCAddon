package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.util.Vector;
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
import org.junit.jupiter.api.Test;

/**
 * 第二层：多车交互。孤立的车永远不会冲突，所以这里每个用例都是两辆车。
 *
 * <p>四类资源各一组正反用例：同一条边不能同时有两辆车；站台按股道数计容量；单线区段对向互斥、同向可追踪；道岔两次通过要留间隔。
 * 还有一条最容易漏的：不停靠经过单股道车站的车，撞的是在那里待命的车。
 */
class TimetableConflictCheckerTest {

  private static final String A = "OP:S:A:1";
  private static final String B = "OP:S:B:1";
  private static final String B2 = "OP:S:B:2";
  private static final String C = "OP:S:C:1";
  private static final String X = "OP:X:JUNCTION:1";
  private static final String D = "OP:S:D:1";
  private static final String E = "OP:S:E:1";

  /** 同一条边上两辆车时间重叠：区间冲突。错开足够时间就没有。 */
  @Test
  void overlappingTrainsOnTheSameEdgeConflict() {
    RailGraph graph =
        TimetableTestFixtures.chain(List.of(A, B), new int[] {100}, new double[] {10.0});
    Profiles profiles = new Profiles(graph);
    UUID r1 = profiles.add("R1", List.of(A, B));

    TimetableConflictChecker.Report overlapping =
        TimetableConflictChecker.check(
            graph, profiles.map, List.of(move("T1", r1, 0), move("T2", r1, 5)), List.of(), 0);
    TimetableConflictChecker.Report separated =
        TimetableConflictChecker.check(
            graph, profiles.map, List.of(move("T1", r1, 0), move("T2", r1, 40)), List.of(), 30);

    assertEquals(1, overlapping.countByKind().getOrDefault(TimetableConflictChecker.Kind.TRACK, 0));
    assertTrue(separated.clean(), () -> separated.conflicts().toString());
  }

  /** 间隔裕量算数：边上前车 10 秒离开，后车 45 秒进入，裕量 30 就够、裕量 60 就不够。 */
  @Test
  void separationMarginIsEnforcedBetweenSuccessiveOccupations() {
    RailGraph graph =
        TimetableTestFixtures.chain(List.of(A, B), new int[] {100}, new double[] {10.0});
    Profiles profiles = new Profiles(graph);
    UUID r1 = profiles.add("R1", List.of(A, B));
    List<TimetableConflictChecker.Movement> movements =
        List.of(move("T1", r1, 0), move("T2", r1, 45));

    assertTrue(
        TimetableConflictChecker.check(graph, profiles.map, movements, List.of(), 30).clean());
    assertEquals(
        1,
        TimetableConflictChecker.check(graph, profiles.map, movements, List.of(), 60)
            .conflicts()
            .size());
  }

  /** 单线区段：对向重叠冲突；同向只要边不重叠就可以追踪。 */
  @Test
  void singleLineSectionRejectsOppositeDirectionOnly() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of(A, B, C), new int[] {100, 100}, new double[] {10.0, 10.0});
    Profiles profiles = new Profiles(graph);
    UUID forward = profiles.add("F", List.of(A, C));
    UUID backward = profiles.add("R", List.of(C, A));

    // F 在 0–20 从 A 到 C；R 在 15 从 C 出发：两车在区段里相遇，但没有同一条边重叠（R 15–25 在 C–B，F 10–20 在 B–C）。
    TimetableConflictChecker.Report opposite =
        TimetableConflictChecker.check(
            graph,
            profiles.map,
            List.of(move("F1", forward, 0), move("R1", backward, 15)),
            List.of(),
            0);
    // 同向追踪：F2 在 F1 离开每条边之后才进入。
    TimetableConflictChecker.Report following =
        TimetableConflictChecker.check(
            graph,
            profiles.map,
            List.of(move("F1", forward, 0), move("F2", forward, 12)),
            List.of(),
            0);

    assertTrue(
        opposite.countByKind().getOrDefault(TimetableConflictChecker.Kind.SINGLE_LINE, 0) >= 1,
        () -> "对向相遇必须被判为单线冲突: " + opposite.conflicts());
    assertTrue(following.clean(), () -> "同向追踪不该冲突: " + following.conflicts());
  }

  /** 站台按股道数计容量：DYNAMIC 两股道同时停两辆车可以，三辆不行。 */
  @Test
  void platformGroupCapacityFollowsTrackCount() {
    // A — B1 — C 与 A — B2 — C：B 站两股道。
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    for (String id : List.of(A, B, B2, C)) {
      nodes.put(NodeId.of(id), station(id));
    }
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    edge(edges, A, B, 100);
    edge(edges, B, C, 100);
    edge(edges, A, B2, 100);
    edge(edges, B2, C, 100);
    RailGraph graph = new SimpleRailGraph(nodes, edges, Set.of());
    Profiles profiles = new Profiles(graph);
    UUID r1 = profiles.addDynamicAtMiddle("R1", List.of(A, B, C), "OP:S:B:[1:2]", 60);

    TimetableConflictChecker.Report two =
        TimetableConflictChecker.check(
            graph, profiles.map, List.of(move("T1", r1, 0), move("T2", r1, 3)), List.of(), 0);
    TimetableConflictChecker.Report three =
        TimetableConflictChecker.check(
            graph,
            profiles.map,
            List.of(move("T1", r1, 0), move("T2", r1, 3), move("T3", r1, 6)),
            List.of(),
            0);

    assertTrue(
        two.conflicts().stream().noneMatch(c -> c.kind() == TimetableConflictChecker.Kind.PLATFORM),
        () -> "两股道停两辆车不该有站台冲突: " + two.conflicts());
    assertTrue(
        three.conflicts().stream()
            .anyMatch(c -> c.kind() == TimetableConflictChecker.Kind.PLATFORM),
        () -> "两股道停三辆车必须有站台冲突: " + three.conflicts());
  }

  /** 待命的车占着单股道车站：不停靠经过它的车撞上去。 */
  @Test
  void passingThroughAnOccupiedSingleTrackStationConflicts() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of(A, B, C), new int[] {100, 100}, new double[] {10.0, 10.0});
    Profiles profiles = new Profiles(graph);
    UUID through = profiles.add("R", List.of(A, C));
    TimetableConflictChecker.Platform platformB =
        new TimetableConflictChecker.Platform(B, TimetableConflictChecker.groupOf(B), false);

    TimetableConflictChecker.Report blocked =
        TimetableConflictChecker.check(
            graph,
            profiles.map,
            List.of(move("T1", through, 0)),
            List.of(new TimetableConflictChecker.Stay("D001", platformB, 0, 100)),
            0);
    TimetableConflictChecker.Report free =
        TimetableConflictChecker.check(
            graph,
            profiles.map,
            List.of(move("T1", through, 0)),
            List.of(new TimetableConflictChecker.Stay("D001", platformB, 40, 100)),
            0);

    assertTrue(
        blocked.conflicts().stream()
            .anyMatch(c -> c.kind() == TimetableConflictChecker.Kind.PLATFORM),
        () -> "经过被占用的站台必须冲突: " + blocked.conflicts());
    assertTrue(free.clean(), () -> "待命在列车经过之后开始，不该冲突: " + free.conflicts());
  }

  /** 道岔：两条互不共边的线路在同一道岔同时通过，冲突；隔开就没有。 */
  @Test
  void junctionPassagesNeedSeparation() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    for (String id : List.of(A, B, D, E)) {
      nodes.put(NodeId.of(id), station(id));
    }
    nodes.put(
        NodeId.of(X),
        new SignRailNode(
            NodeId.of(X),
            NodeType.SWITCHER,
            new Vector(0, 64, 0),
            Optional.empty(),
            Optional.empty()));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    edge(edges, A, X, 100);
    edge(edges, X, B, 100);
    edge(edges, D, X, 100);
    edge(edges, X, E, 100);
    RailGraph graph = new SimpleRailGraph(nodes, edges, Set.of());
    Profiles profiles = new Profiles(graph);
    UUID ab = profiles.add("AB", List.of(A, B));
    UUID de = profiles.add("DE", List.of(D, E));

    TimetableConflictChecker.Report same =
        TimetableConflictChecker.check(
            graph, profiles.map, List.of(move("T1", ab, 0), move("T2", de, 0)), List.of(), 30);
    TimetableConflictChecker.Report apart =
        TimetableConflictChecker.check(
            graph, profiles.map, List.of(move("T1", ab, 0), move("T2", de, 60)), List.of(), 30);

    assertEquals(1, same.countByKind().getOrDefault(TimetableConflictChecker.Kind.JUNCTION, 0));
    assertTrue(apart.clean(), () -> apart.conflicts().toString());
  }

  /** 同一辆车自己的占用不算冲突：待命区间与它自己的到发衔接。 */
  @Test
  void aVehicleDoesNotConflictWithItself() {
    RailGraph graph =
        TimetableTestFixtures.chain(List.of(A, B), new int[] {100}, new double[] {10.0});
    Profiles profiles = new Profiles(graph);
    UUID r1 = profiles.add("R1", List.of(A, B));
    TimetableConflictChecker.Platform platformA =
        new TimetableConflictChecker.Platform(A, TimetableConflictChecker.groupOf(A), false);

    TimetableConflictChecker.Report report =
        TimetableConflictChecker.check(
            graph,
            profiles.map,
            List.of(move("T1", r1, 0)),
            List.of(new TimetableConflictChecker.Stay("T1", platformA, -100, 0)),
            30);

    assertTrue(report.clean(), () -> report.conflicts().toString());
  }

  /** 邻表之间的冲突不属于我：两方都带 owner 的对不报；一方是我的照报，并带上对方的 owner。 */
  @Test
  void conflictsBetweenTwoNeighborsAreNotReportedButMineAgainstNeighborIs() {
    RailGraph graph =
        TimetableTestFixtures.chain(List.of(A, B), new int[] {100}, new double[] {10.0});
    Profiles profiles = new Profiles(graph);
    UUID r1 = profiles.add("R1", List.of(A, B));
    TimetableConflictChecker.Movement mine = move("T1", r1, 0);
    TimetableConflictChecker.Movement theirsA =
        new TimetableConflictChecker.Movement("X-001", r1, 2, Optional.of("C/O/X/TT"));
    TimetableConflictChecker.Movement theirsB =
        new TimetableConflictChecker.Movement("Y-001", r1, 4, Optional.of("C/O/Y/TT"));

    TimetableConflictChecker.Report onlyNeighbors =
        TimetableConflictChecker.check(
            graph, profiles.map, List.of(theirsA, theirsB), List.of(), 0);
    TimetableConflictChecker.Report withMine =
        TimetableConflictChecker.check(
            graph, profiles.map, List.of(mine, theirsA, theirsB), List.of(), 0);

    assertTrue(onlyNeighbors.clean(), () -> "邻表之间的冲突不该报: " + onlyNeighbors.conflicts());
    assertFalse(withMine.external().isEmpty(), () -> withMine.conflicts().toString());
    assertTrue(withMine.internal().isEmpty());
    assertTrue(
        withMine.external().stream().allMatch(c -> c.otherOwner().isPresent()), "外部冲突要带上对方的 owner");
    assertEquals(2, withMine.externalByOwner().size(), "与两份邻表各撞一次");
  }

  /** 两股道站台：两份邻表各占一股，我夹在中间——超容量的冲突要归到我头上，不能因为最早那个是邻表就当没看见。 */
  @Test
  void capacityOverflowBetweenTwoNeighborsIsChargedToMe() {
    String b1 = "OP:S:B:1";
    String b2 = "OP:S:B:2";
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of(A, b1, b2, C), new int[] {100, 10, 100}, new double[] {10.0, 10.0, 10.0});
    Profiles profiles = new Profiles(graph);
    TimetableConflictChecker.Platform track1 =
        new TimetableConflictChecker.Platform(b1, TimetableConflictChecker.groupOf(b1), false);
    TimetableConflictChecker.Platform track2 =
        new TimetableConflictChecker.Platform(b2, TimetableConflictChecker.groupOf(b2), false);
    Optional<String> x = Optional.of("C/O/X/TT");
    Optional<String> y = Optional.of("C/O/Y/TT");

    TimetableConflictChecker.Report report =
        TimetableConflictChecker.check(
            graph,
            profiles.map,
            List.of(),
            List.of(
                new TimetableConflictChecker.Stay("X-001", track1, 0, 20, x),
                new TimetableConflictChecker.Stay("D001", track2, 2, 22),
                new TimetableConflictChecker.Stay("Y-001", track1, 4, 24, y)),
            0);

    assertFalse(report.external().isEmpty(), () -> report.conflicts().toString());
    assertTrue(
        report.external().stream()
            .anyMatch(
                c ->
                    c.kind() == TimetableConflictChecker.Kind.PLATFORM
                        && (c.first().equals("D001") || c.second().equals("D001"))),
        () -> report.conflicts().toString());
  }

  /**
   * 进站路径点不是站台：{@code OP:S:X:1:001} 命名在 X 的命名空间下，但它是 WAYPOINT。按名字解析会让经过它的车占用 {@code
   * platform-group:OP:S:X}，与站台上待命的车"撞"上；按节点类型解析则不登记。
   */
  @Test
  void approachWaypointIsNotAPlatform() {
    String x = "OP:S:X:1";
    String approach = "OP:S:X:1:001";
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(NodeId.of(A), station(A));
    nodes.put(NodeId.of(approach), waypoint(approach));
    nodes.put(NodeId.of(x), station(x));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    edge(edges, A, approach, 100);
    edge(edges, approach, x, 100);
    RailGraph graph = new SimpleRailGraph(nodes, edges, Set.of());
    TimetableConflictChecker.GraphIndex index = TimetableConflictChecker.GraphIndex.of(graph);
    UUID routeId = TimetableTestFixtures.routeId("R");
    RouteDefinition route = TimetableTestFixtures.route("R", List.of(A, approach, x));
    List<RouteStop> stops = TimetableTestFixtures.stops(routeId, 3, 0);
    TimetableTimingCalculator.TimingResult timing =
        new TimetableTimingCalculator()
            .compute(graph, TimetableTestFixtures.perEdgeSpeedModel(), route, stops, Duration.ZERO);
    TimetableConflictChecker.Platform platformX =
        new TimetableConflictChecker.Platform(x, TimetableConflictChecker.groupOf(x), false);
    // 别的车正在 X 站台待命；T1 在 10 s 经过进站路径点、20 s 到 X（终点占用由 Stay 负责，这里不给它 Stay）。
    List<TimetableConflictChecker.Stay> stays =
        List.of(new TimetableConflictChecker.Stay("D002", platformX, 15, 100));

    TimetableConflictChecker.RouteProfile byName =
        new TimetableConflictChecker.RouteProfile(
            routeId,
            "R",
            timing.stops(),
            timing.segments(),
            TimetableConflictChecker.platformsOf(timing.stops(), stops, route.waypoints()));
    TimetableConflictChecker.RouteProfile byType =
        new TimetableConflictChecker.RouteProfile(
            routeId,
            "R",
            timing.stops(),
            timing.segments(),
            TimetableConflictChecker.platformsOf(
                timing.stops(), stops, route.waypoints(), index.nodeTypes()));

    assertTrue(byType.platforms().get(1).absent(), "路径点不是站台");
    assertFalse(byType.platforms().get(2).absent(), "X 是站台");
    TimetableConflictChecker.Report named =
        TimetableConflictChecker.check(
            index, Map.of(routeId, byName), List.of(move("T1", routeId, 0)), stays, 30);
    TimetableConflictChecker.Report typed =
        TimetableConflictChecker.check(
            index, Map.of(routeId, byType), List.of(move("T1", routeId, 0)), stays, 30);
    assertFalse(named.clean(), "按名字解析：路径点占了站台组，与待命的车撞上");
    assertTrue(typed.clean(), () -> typed.conflicts().toString());
  }

  /** 同一辆车不撞自己：待命（duty 号）与它自己的班次、回库走行（车次号 / Dxxx-RETURN）是同一辆车。 */
  @Test
  void aVehicleDoesNotConflictWithItsOwnLegsAndTrips() {
    String x = "OP:S:X:1";
    String approach = "OP:S:X:1:001";
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of(A, approach, x), new int[] {100, 100}, new double[] {10.0, 10.0});
    Profiles profiles = new Profiles(graph);
    UUID in = profiles.add("IN", List.of(A, approach, x));
    UUID ret = profiles.add("RET", List.of(x, approach, A));
    TimetableConflictChecker.Platform platformX =
        new TimetableConflictChecker.Platform(x, TimetableConflictChecker.groupOf(x), false);
    // 车 D001：T1 进站（10 s 过路径点、20 s 到 X），待命到 200，回库走行 200 s 发车、210 s 过路径点。
    List<TimetableConflictChecker.Movement> movements =
        List.of(move("T1", in, 0), move("D001-RETURN", ret, 200));
    List<TimetableConflictChecker.Stay> stays =
        List.of(new TimetableConflictChecker.Stay("D001", platformX, 20, 200));
    java.util.function.Function<String, String> vehicleOf =
        code -> code.equals("T1") || code.equals("D001-RETURN") ? "D001" : code;

    // chain 夹具把路径点也造成 STATION，站台组 X 会有 2 股道；这里按 CHT 的形态把它钉成 1。
    TimetableConflictChecker.GraphIndex base = TimetableConflictChecker.GraphIndex.of(graph);
    TimetableConflictChecker.GraphIndex index =
        new TimetableConflictChecker.GraphIndex(
            base.sections(), Map.of(TimetableConflictChecker.groupOf(x), 1), base.nodeTypes());

    TimetableConflictChecker.Report byCode =
        TimetableConflictChecker.check(index, profiles.map, movements, stays, 30);
    TimetableConflictChecker.Report byVehicle =
        TimetableConflictChecker.check(index, profiles.map, movements, stays, 30, vehicleOf);
    TimetableConflictChecker.Report otherVehicle =
        TimetableConflictChecker.check(
            index,
            profiles.map,
            movements,
            stays,
            30,
            code -> code.equals("D001-RETURN") ? "D002" : vehicleOf.apply(code));

    assertFalse(byCode.clean(), "只按 code：车在撞自己");
    assertTrue(byVehicle.clean(), () -> byVehicle.conflicts().toString());
    assertFalse(otherVehicle.clean(), "换成别的车的回库走行就是真冲突");
  }

  // ------------------------------------------------------------------ 夹具

  private static RailNode waypoint(String id) {
    return new SignRailNode(
        NodeId.of(id), NodeType.WAYPOINT, new Vector(0, 64, 0), Optional.empty(), Optional.empty());
  }

  private static TimetableConflictChecker.Movement move(String code, UUID route, int start) {
    return new TimetableConflictChecker.Movement(code, route, start);
  }

  private static RailNode station(String id) {
    return new SignRailNode(
        NodeId.of(id), NodeType.STATION, new Vector(0, 64, 0), Optional.empty(), Optional.empty());
  }

  private static void edge(Map<EdgeId, RailEdge> edges, String from, String to, int length) {
    EdgeId id = EdgeId.undirected(NodeId.of(from), NodeId.of(to));
    edges.put(
        id, new RailEdge(id, NodeId.of(from), NodeId.of(to), length, 10.0, true, Optional.empty()));
  }

  /** 用真实的计时器算出 profile，冲突检查和 build 用的是同一份逐边时分。 */
  private static final class Profiles {
    private final RailGraph graph;
    private final Map<UUID, TimetableConflictChecker.RouteProfile> map = new LinkedHashMap<>();

    private Profiles(RailGraph graph) {
      this.graph = graph;
    }

    private UUID add(String code, List<String> nodes) {
      UUID id = TimetableTestFixtures.routeId(code);
      RouteDefinition route = TimetableTestFixtures.route(code, nodes);
      List<RouteStop> stops = TimetableTestFixtures.stops(id, nodes.size(), 0);
      put(id, code, route, stops);
      return id;
    }

    /** 中间站是 DYNAMIC 停靠，停 {@code dwell} 秒。 */
    private UUID addDynamicAtMiddle(String code, List<String> nodes, String spec, int dwell) {
      UUID id = TimetableTestFixtures.routeId(code);
      RouteDefinition route = TimetableTestFixtures.route(code, nodes);
      List<RouteStop> stops =
          List.of(
              new RouteStop(
                  id,
                  0,
                  Optional.empty(),
                  Optional.empty(),
                  Optional.of(0),
                  RouteStopPassType.STOP,
                  Optional.empty()),
              new RouteStop(
                  id,
                  1,
                  Optional.empty(),
                  Optional.empty(),
                  Optional.of(dwell),
                  RouteStopPassType.STOP,
                  Optional.of("DYNAMIC " + spec)),
              new RouteStop(
                  id,
                  2,
                  Optional.empty(),
                  Optional.empty(),
                  Optional.of(0),
                  RouteStopPassType.TERMINATE,
                  Optional.empty()));
      put(id, code, route, stops);
      return id;
    }

    private void put(UUID id, String code, RouteDefinition route, List<RouteStop> stops) {
      TimetableTimingCalculator.TimingResult timing =
          new TimetableTimingCalculator()
              .compute(
                  graph, TimetableTestFixtures.perEdgeSpeedModel(), route, stops, Duration.ZERO);
      assertTrue(timing.ok(), () -> code + " 时分必须算得出来: " + timing.failure());
      map.put(
          id,
          new TimetableConflictChecker.RouteProfile(
              id,
              code,
              timing.stops(),
              timing.segments(),
              TimetableConflictChecker.platformsOf(timing.stops(), stops, route.waypoints())));
    }
  }
}
