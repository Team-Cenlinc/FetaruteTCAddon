package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.junit.jupiter.api.Test;

/**
 * 共线追车：同一串互斥资源上不能超车，快车跟在慢车后面就被一路拖住。
 *
 * <p>链 A–B–C–D–E 每段 100 格、10 格/秒（10 秒）。慢车每个中间站停 30 秒，走完全程 130 秒；快车中间站通过，40 秒。 快车跟在慢车后面至少要晚发 100
 * 秒（慢车离开 D–E 在 130、快车进入 D–E 在 30）才追不上。
 */
class CorridorCatchUpTest {

  private static final String A = "OP:S:A:1";
  private static final String B = "OP:S:B:1";
  private static final String C = "OP:S:C:1";
  private static final String D = "OP:S:D:1";
  private static final String E = "OP:S:E:1";
  private static final int SEPARATION = 5;

  private final Map<UUID, TimetableConflictChecker.RouteProfile> profiles = new LinkedHashMap<>();

  private final RailGraph chain =
      TimetableTestFixtures.chain(
          List.of(A, B, C, D, E),
          new int[] {100, 100, 100, 100},
          new double[] {10.0, 10.0, 10.0, 10.0});

  /** 同一起点、同一串股道：一整段共线，最小间隔是最后一段上慢车离开减快车进入。 */
  @Test
  void aSharedCorridorFromTheSameOriginIsOneRun() {
    UUID local = add(chain, "LOCAL", List.of(A, B, C, D, E), 30);
    UUID rapid = add(chain, "RAPID", List.of(A, E), 0);

    List<CorridorCatchUp.Run> runs = catchUp(chain).runs(local, rapid);

    assertEquals(1, runs.size(), runs.toString());
    CorridorCatchUp.Run run = runs.get(0);
    assertEquals(100, run.lead());
    assertEquals(130, run.aheadSeconds());
    assertEquals(40, run.behindSeconds());
    assertEquals(0, run.behindFrom());
  }

  /** 同一站不同股道出发：出站那条边各走各的，到 B 汇合之后才共线，进入时刻都从 B 起算。 */
  @Test
  void trainsLeavingFromDifferentTracksShareTheCorridorFromWhereTheyMeet() {
    String a1 = "OP:S:A:1";
    String a2 = "OP:S:A:2";
    RailGraph fork =
        graph(
            List.of(a1, a2, B, C, D, E),
            List.of(edge(a1, B), edge(a2, B), edge(B, C), edge(C, D), edge(D, E)));
    UUID local = add(fork, "LOCAL", List.of(a1, B, C, D, E), 30);
    UUID rapid = add(fork, "RAPID", List.of(a2, E), 0);

    List<CorridorCatchUp.Run> runs = catchUp(fork).runs(local, rapid);

    assertEquals(1, runs.size(), runs.toString());
    assertEquals(10, runs.get(0).aheadEntry(), "慢车 10 秒到 B");
    assertEquals(10, runs.get(0).behindEntry(), "快车也 10 秒到 B");
    assertEquals(100, runs.get(0).lead());
  }

  /** 中间有一站两股道（慢车停一道、快车走二道）：在那里可以越行，共线被切成前后两段，各算各的。 */
  @Test
  void aPassingLoopSplitsTheCorridorInTwo() {
    String f = "OP:S:F:1";
    String g = "OP:S:G:1";
    String c1 = "OP:S:C:1";
    String c2 = "OP:S:C:2";
    String x = "OP:S:X:1";
    RailGraph loop =
        graph(
            List.of(A, B, x, c1, c2, D, E, f, g),
            List.of(
                edge(A, B),
                edge(B, x),
                edge(x, c1),
                edge(x, c2),
                edge(c1, D),
                edge(c2, D),
                edge(D, E),
                edge(E, f),
                edge(f, g)));
    UUID local = add(loop, "LOCAL", List.of(A, B, x, c1, D, E, f, g), 30);
    UUID rapid = add(loop, "RAPID", List.of(A, x, c2, g), 0);

    List<CorridorCatchUp.Run> runs = catchUp(loop).runs(local, rapid);

    assertEquals(2, runs.size(), runs.toString());
    assertTrue(runs.get(1).behindFrom() > runs.get(0).behindFrom(), runs.toString());
  }

  /** 对开的两条交路走同一串股道但方向相反：资源次序反过来，不算共线。 */
  @Test
  void opposingTrainsShareNoCorridor() {
    UUID forward = add(chain, "FWD", List.of(A, B, C, D, E), 30);
    UUID backward = add(chain, "BWD", List.of(E, D, C, B, A), 30);

    assertTrue(catchUp(chain).runs(forward, backward).isEmpty());
  }

  /** 快车是在某段共线上比另一条快出一个裕量以上的；一样快的不算。 */
  @Test
  void onlyTheFasterRouteIsAFastRoute() {
    UUID local = add(chain, "LOCAL", List.of(A, B, C, D, E), 30);
    UUID otherLocal = add(chain, "LOCAL2", List.of(A, B, C, D, E), 30);
    UUID rapid = add(chain, "RAPID", List.of(A, E), 0);

    assertEquals(Set.of(rapid), catchUp(chain).fasterRoutes(List.of(local, otherLocal, rapid)));
  }

  /** 快车在慢车之后 30 秒发：要晚 100 秒才追不上，被拖住 70 秒（超过裕量的部分）；慢车在快车之后发不算被卡。 */
  @Test
  void onlyTheFasterTrainBehindIsCaught() {
    UUID local = add(chain, "LOCAL", List.of(A, B, C, D, E), 30);
    UUID rapid = add(chain, "RAPID", List.of(A, E), 0);

    List<CorridorCatchUp.Caught> caught =
        catchUp(chain)
            .caught(
                List.of(
                    new TimetableConflictChecker.Movement("L1", local, 0),
                    new TimetableConflictChecker.Movement("R1", rapid, 30),
                    new TimetableConflictChecker.Movement("L2", local, 400)));

    assertEquals(1, caught.size(), caught.toString());
    assertEquals("R1", caught.get(0).code());
    assertEquals(70, caught.get(0).seconds());
    assertEquals(local, caught.get(0).blockingRouteId());
  }

  /** 发得够远就追不上；同一 code 的是同一辆车（班次与回库走行），互相不算。 */
  @Test
  void aFarEnoughGapOrTheSameVehicleIsNotCaught() {
    UUID local = add(chain, "LOCAL", List.of(A, B, C, D, E), 30);
    UUID rapid = add(chain, "RAPID", List.of(A, E), 0);

    assertTrue(
        catchUp(chain)
            .caught(
                List.of(
                    new TimetableConflictChecker.Movement("L1", local, 0),
                    new TimetableConflictChecker.Movement("R1", rapid, 100)))
            .isEmpty());
    assertTrue(
        catchUp(chain)
            .caught(
                List.of(
                    new TimetableConflictChecker.Movement("V1", local, 0),
                    new TimetableConflictChecker.Movement("V1", rapid, 30)))
            .isEmpty());
  }

  /** 同一班快车被前面两班慢车先后挡住同一段：只记最狠的那一班。 */
  @Test
  void theWorstTrainAheadOnACorridorCounts() {
    UUID local = add(chain, "LOCAL", List.of(A, B, C, D, E), 30);
    UUID rapid = add(chain, "RAPID", List.of(A, E), 0);

    List<CorridorCatchUp.Caught> caught =
        catchUp(chain)
            .caught(
                List.of(
                    new TimetableConflictChecker.Movement("L1", local, 0),
                    new TimetableConflictChecker.Movement("L2", local, 20),
                    new TimetableConflictChecker.Movement("R1", rapid, 60)));

    assertEquals(1, caught.size(), caught.toString());
    assertEquals(60, caught.get(0).seconds(), "被 20 秒那班拖住 100 + 20 − 60");
  }

  private CorridorCatchUp catchUp(RailGraph graph) {
    return new CorridorCatchUp(profiles, TimetableConflictChecker.GraphIndex.of(graph), SEPARATION);
  }

  private static RailGraph graph(List<String> stations, List<TimetableTestFixtures.Edge> edges) {
    Map<String, NodeType> nodes = new LinkedHashMap<>();
    for (String station : stations) {
      nodes.put(station, NodeType.STATION);
    }
    return TimetableTestFixtures.graph(nodes, edges);
  }

  private static TimetableTestFixtures.Edge edge(String from, String to) {
    return new TimetableTestFixtures.Edge(from, to, 100, 10.0);
  }

  private UUID add(RailGraph network, String code, List<String> waypoints, int dwell) {
    UUID id = TimetableTestFixtures.routeId(code);
    RouteDefinition route = TimetableTestFixtures.route(code, waypoints);
    List<RouteStop> stops = TimetableTestFixtures.stops(id, waypoints.size(), dwell);
    TimetableTimingCalculator.TimingResult timing =
        new TimetableTimingCalculator()
            .compute(
                network, TimetableTestFixtures.perEdgeSpeedModel(), route, stops, Duration.ZERO);
    assertTrue(timing.ok(), () -> code + " 时分必须算得出来: " + timing.failure());
    profiles.put(
        id,
        new TimetableConflictChecker.RouteProfile(
            id,
            code,
            timing.stops(),
            timing.segments(),
            TimetableConflictChecker.platformsOf(timing.stops(), stops, route.waypoints())));
    return id;
  }
}
