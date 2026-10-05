package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.junit.jupiter.api.Test;

/**
 * 瓶颈：每个资源在最忙一小时里被闭塞时间占了多久。
 *
 * <p>链 A–B–C–D–E 每段 100 格、10 格/秒，慢车每站停 30 秒，车长 30 格（闭塞时间见 {@link BlockingTimesTest}）。B
 * 站台每班从发车就要用到（前沿起步即到 B）， 车头 10 秒到、40 秒开，车头到 E（130 秒）才放出：每次 131 秒，其中进入前 10、占用（含停站）30、出清 91。
 */
class CapacityReportTest {

  private static final String A = "OP:S:A:1";
  private static final String B = "OP:S:B:1";
  private static final String C = "OP:S:C:1";
  private static final String D = "OP:S:D:1";
  private static final String E = "OP:S:E:1";
  private static final UUID LOC = TimetableTestFixtures.routeId("LOC");
  private static final TimetableBuildOptions.Following RULES =
      new TimetableBuildOptions.Following(1.0D, 20.0D, 1, 1.0D, Map.of(LOC, 30L));
  private static final UUID TABLE =
      UUID.nameUUIDFromBytes("table".getBytes(StandardCharsets.UTF_8));

  private final RailGraph chain =
      TimetableTestFixtures.chain(
          List.of(A, B, C, D, E),
          new int[] {100, 100, 100, 100},
          new double[] {10.0, 10.0, 10.0, 10.0});
  private final TimetableConflictChecker.GraphIndex index =
      TimetableConflictChecker.GraphIndex.of(chain);

  /** 每 300 秒一班：一小时 12 班，B 站台被占 12 × 131 秒，最紧；组成按每次平均。 */
  @Test
  void theBusiestResourceComesFirstWithItsPerPassComposition() {
    List<CapacityReport.Bottleneck> top = measure(departures(0, 300, 24), RULES);

    CapacityReport.Bottleneck b = top.get(0);
    assertEquals("platform:" + B, b.key());
    assertEquals(12, b.passes());
    assertEquals(12 * 131 / 3600.0D, b.utilization(), 1.0E-9);
    assertEquals(10.0D, b.approachSeconds(), 1.0E-9);
    assertEquals(30.0D, b.occupySeconds(), 1.0E-9);
    assertEquals(91.0D, b.clearSeconds(), 1.0E-9);
    assertEquals(131.0D, b.perPassSeconds(), 1.0E-9);
    assertFalse(b.occupationOnly());
    for (int k = 1; k < top.size(); k++) {
      assertTrue(top.get(k - 1).utilization() >= top.get(k).utilization());
    }
  }

  /** 最忙的一小时：前一小时每 300 秒一班、之后每 900 秒一班，算的是前一小时。 */
  @Test
  void thePeakHourIsTheBusiestOne() {
    List<Integer> departures = new ArrayList<>(departures(0, 300, 12));
    departures.addAll(departures(3600, 900, 8));

    CapacityReport.Bottleneck b = measure(departures, RULES).get(0);

    assertEquals(12, b.passes());
    assertTrue(b.peakStartSeconds() < 300, () -> "最忙一小时从 " + b.peakStartSeconds() + " 起");
  }

  /** 车长未知：放不出时刻，占用退回占用区间（B 站台只剩车头到发之间的 30 秒），并标出来。 */
  @Test
  void anUnknownTrainLengthFallsBackToOccupation() {
    CapacityReport.Bottleneck b =
        measure(departures(0, 300, 24), RULES.withTrainLengths(Map.of())).stream()
            .filter(one -> one.key().equals("platform:" + B))
            .findFirst()
            .orElseThrow();

    assertTrue(b.occupationOnly());
    assertEquals(0.0D, b.clearSeconds(), 1.0E-9);
    assertEquals(40.0D, b.perPassSeconds(), 1.0E-9);
  }

  /** 报告行写出每次占用的组成，最后一行点名最紧的资源与"降到第二紧要省多少"。 */
  @Test
  void theReportLinesNameTheTopResourceAndTheGapToTheNext() {
    List<CapacityReport.Bottleneck> top = measure(departures(0, 300, 24), RULES);

    List<String> lines =
        TimetableBuildReportText.describeBottlenecks(new CapacityReport.Report(top, 0), 0);

    assertTrue(lines.get(1).contains("站台 " + B), lines::toString);
    assertTrue(lines.get(1).contains("进入前 10 + 占用（含停站） 30 + 出清 91"), lines::toString);
    assertTrue(lines.get(lines.size() - 1).contains("最紧的是 站台 " + B), lines::toString);
    assertTrue(lines.get(lines.size() - 1).contains("要降到第二紧"), lines::toString);
  }

  /**
   * 动态站台按计划股道算：OUT 终到 BBB、BACK 从 BBB 始发，两班都计划 BBB:2；中间的折返停留不属于哪一班，跟着同一辆车相邻停靠的股道算到 BBB:2。 到站 1120、发车
   * 1300，BBB:2 被占一段 180 秒（折返与待命）。
   */
  @Test
  void aDynamicStopUsesItsPlannedTrackAndTheTurnbackFollowsIt() {
    Timetable table = turnbackTable();
    List<PlatformPlan> plans =
        List.of(
            new PlatformPlan(tripId("OUT"), 2, BBB2), new PlatformPlan(tripId("BACK"), 0, BBB2));

    CapacityReport.Report report = measureTurnback(table, plans);

    CapacityReport.Bottleneck bbb =
        report.top().stream()
            .filter(one -> one.key().equals("platform:" + BBB2))
            .findFirst()
            .orElseThrow(() -> new AssertionError(report.toString()));
    assertEquals(1, bbb.passes());
    assertEquals(180.0D, bbb.perPassSeconds(), 1.0E-9);
    assertEquals(0, report.unplaced());
  }

  /** 只有 BACK 始发有计划股道：折返待命先跟着它认 BBB:2，OUT 到站再跟着待命认，一段段传过去。 */
  @Test
  void aChainOfStaysFollowsTheOnePlannedTrack() {
    CapacityReport.Report report =
        measureTurnback(turnbackTable(), List.of(new PlatformPlan(tripId("BACK"), 0, BBB2)));

    CapacityReport.Bottleneck bbb =
        report.top().stream()
            .filter(one -> one.key().equals("platform:" + BBB2))
            .findFirst()
            .orElseThrow(() -> new AssertionError(report.toString()));
    assertEquals(180.0D, bbb.perPassSeconds(), 1.0E-9);
    assertEquals(0, report.unplaced());
  }

  /** 没有计划股道：动态站台上的停留定不下股道，计数、不计入。 */
  @Test
  void aDynamicStopWithoutAPlanIsCountedButNotMeasured() {
    CapacityReport.Report report = measureTurnback(turnbackTable(), List.of());

    assertTrue(
        report.top().stream().noneMatch(one -> one.key().startsWith("platform:OP:S:BBB")),
        report::toString);
    assertTrue(report.unplaced() > 0, report::toString);
  }

  private static final String AAA = "OP:S:AAA:1";
  private static final String WAY = "OP:AAA:BBB:1:001";
  private static final String BBB1 = "OP:S:BBB:1";
  private static final String BBB2 = "OP:S:BBB:2";
  private static final UUID OUT = TimetableTestFixtures.routeId("OUT");
  private static final UUID BACK = TimetableTestFixtures.routeId("BACK");

  private static UUID tripId(String code) {
    return UUID.nameUUIDFromBytes(("trip-" + code).getBytes(StandardCharsets.UTF_8));
  }

  /** 一辆车：OUT 1000 发（120 秒到 BBB），BACK 1300 发回 AAA。 */
  private static Timetable turnbackTable() {
    List<TimetableTrip> trips =
        List.of(
            new TimetableTrip(tripId("OUT"), TABLE, OUT, 0, "OUT", 1000, Optional.empty()),
            new TimetableTrip(tripId("BACK"), TABLE, BACK, 1, "BACK", 1300, Optional.empty()));
    VehicleDuty duty =
        new VehicleDuty(
            UUID.nameUUIDFromBytes("duty-turnback".getBytes(StandardCharsets.UTF_8)),
            TABLE,
            0,
            "D1",
            "OP:D:DEPOT:1",
            "OP:D:DEPOT:1",
            Optional.empty(),
            Optional.empty(),
            List.of(tripId("OUT"), tripId("BACK")),
            1000,
            1420,
            1420,
            VehicleDuty.CloseReason.HORIZON_END);
    return new Timetable(
        TABLE,
        UUID.nameUUIDFromBytes("company".getBytes(StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes("operator".getBytes(StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes("line".getBytes(StandardCharsets.UTF_8)),
        "CAP",
        "瓶颈夹具",
        TimetableStatus.DRAFT,
        ZoneId.of("UTC"),
        0,
        86_400,
        List.of(plan(OUT, "OUT", AAA, BBB1), plan(BACK, "BACK", BBB1, AAA)),
        trips,
        List.of(duty),
        Optional.empty(),
        Instant.EPOCH,
        Instant.EPOCH);
  }

  private static TimetableRoutePlan plan(UUID route, String code, String from, String to) {
    return new TimetableRoutePlan(
        route,
        code,
        RouteOperationType.OPERATION,
        1,
        List.of(),
        from,
        to,
        Optional.empty(),
        Optional.empty(),
        false);
  }

  private CapacityReport.Report measureTurnback(Timetable table, List<PlatformPlan> plans) {
    TimetableConflictChecker.Platform aaa =
        new TimetableConflictChecker.Platform(AAA, "OP:S:AAA", false);
    TimetableConflictChecker.Platform bbb =
        new TimetableConflictChecker.Platform(BBB1, "OP:S:BBB", true);
    TimetableConflictChecker.Platform way = TimetableConflictChecker.Platform.none();
    Map<UUID, TimetableConflictChecker.RouteProfile> profiles =
        Map.of(
            OUT, turnbackProfile(OUT, "OUT", List.of(aaa, way, bbb)),
            BACK, turnbackProfile(BACK, "BACK", List.of(bbb, way, aaa)));
    return CapacityReport.measure(
        List.of(table),
        Map.of(TABLE, profiles),
        Map.of(TABLE, plans),
        index,
        TimetableBuildOptions.Following.NONE,
        BlockingTimes.Trajectories.NONE,
        0,
        10);
  }

  /** 首站发车、60 秒过路径点、120 秒到终点；不带区间（只量站台）。 */
  private static TimetableConflictChecker.RouteProfile turnbackProfile(
      UUID route, String code, List<TimetableConflictChecker.Platform> platforms) {
    return new TimetableConflictChecker.RouteProfile(
        route,
        code,
        List.of(
            new TimetableStop(0, Optional.empty(), Optional.empty(), 0, 0, RouteStopPassType.STOP),
            new TimetableStop(
                1, Optional.empty(), Optional.empty(), 60, 60, RouteStopPassType.PASS),
            new TimetableStop(
                2, Optional.empty(), Optional.empty(), 120, 120, RouteStopPassType.TERMINATE)),
        List.of(),
        platforms);
  }

  private List<CapacityReport.Bottleneck> measure(
      List<Integer> departures, TimetableBuildOptions.Following rules) {
    return CapacityReport.measure(
            List.of(table(departures)),
            Map.of(TABLE, Map.of(LOC, profile())),
            Map.of(),
            index,
            rules,
            BlockingTimes.Trajectories.NONE,
            0,
            5)
        .top();
  }

  private static List<Integer> departures(int from, int every, int count) {
    List<Integer> out = new ArrayList<>();
    for (int k = 0; k < count; k++) {
      out.add(from + k * every);
    }
    return out;
  }

  /** 每班一条交路（一辆车）。 */
  private static Timetable table(List<Integer> departures) {
    TimetableRoutePlan plan =
        new TimetableRoutePlan(
            LOC,
            "LOC",
            RouteOperationType.OPERATION,
            1,
            List.of(),
            A,
            E,
            Optional.empty(),
            Optional.empty(),
            false);
    List<TimetableTrip> trips = new ArrayList<>();
    List<VehicleDuty> duties = new ArrayList<>();
    for (int k = 0; k < departures.size(); k++) {
      UUID tripId = UUID.nameUUIDFromBytes(("trip" + k).getBytes(StandardCharsets.UTF_8));
      trips.add(
          new TimetableTrip(tripId, TABLE, LOC, k, "L" + k, departures.get(k), Optional.empty()));
      duties.add(
          new VehicleDuty(
              UUID.nameUUIDFromBytes(("duty" + k).getBytes(StandardCharsets.UTF_8)),
              TABLE,
              k,
              "D" + k,
              "OP:D:DEPOT:1",
              "OP:D:DEPOT:1",
              Optional.empty(),
              Optional.empty(),
              List.of(tripId),
              departures.get(k),
              departures.get(k) + 130,
              departures.get(k) + 130,
              VehicleDuty.CloseReason.HORIZON_END));
    }
    return new Timetable(
        TABLE,
        UUID.nameUUIDFromBytes("company".getBytes(StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes("operator".getBytes(StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes("line".getBytes(StandardCharsets.UTF_8)),
        "CAP",
        "瓶颈夹具",
        TimetableStatus.DRAFT,
        ZoneId.of("UTC"),
        0,
        86_400,
        List.of(plan),
        trips,
        duties,
        Optional.empty(),
        Instant.EPOCH,
        Instant.EPOCH);
  }

  private TimetableConflictChecker.RouteProfile profile() {
    List<String> waypoints = List.of(A, B, C, D, E);
    RouteDefinition route = TimetableTestFixtures.route("LOC", waypoints);
    List<RouteStop> stops = TimetableTestFixtures.stops(LOC, waypoints.size(), 30);
    TimetableTimingCalculator.TimingResult timing =
        new TimetableTimingCalculator()
            .compute(chain, TimetableTestFixtures.perEdgeSpeedModel(), route, stops, Duration.ZERO);
    assertTrue(timing.ok(), () -> "LOC: " + timing.failure());
    return new TimetableConflictChecker.RouteProfile(
        LOC,
        "LOC",
        timing.stops(),
        timing.segments(),
        TimetableConflictChecker.platformsOf(timing.stops(), stops, route.waypoints()));
  }
}
