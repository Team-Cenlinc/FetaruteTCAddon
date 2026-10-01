package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

/**
 * 快车错峰接进编表器：每次 build 实测快车被卡、候选编表器按组平移相位。搜索策略本身由 {@link RapidStaggerTest} 用假的编表钉住， 在真路网上的效果靠离线重编复核。
 *
 * <p>慢车与快车同从 A 出发、沿同一串单股道走到 D，之后各进 E 的一道、二道；快车中间站通过。慢车每 400 秒、快车每 800 秒一班。
 */
class TimetableBuilderRapidStaggerTest {

  private static final Instant BUILT_AT = Instant.parse("2026-03-01T00:00:00Z");
  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final String DEP = "OP:D:DEP:1";
  private static final String DEP2 = "OP:D:DEQ:1";
  private static final String A = "OP:S:A:1";
  private static final String B = "OP:S:B:1";
  private static final String C = "OP:S:C:1";
  private static final String D = "OP:S:D:1";
  private static final String E = "OP:S:E:1";
  private static final String E2 = "OP:S:E:2";

  private final RailGraph graph =
      TimetableTestFixtures.graph(
          new LinkedHashMap<>(
              Map.of(
                  DEP, NodeType.DEPOT,
                  DEP2, NodeType.DEPOT,
                  A, NodeType.STATION,
                  B, NodeType.STATION,
                  C, NodeType.STATION,
                  D, NodeType.STATION,
                  E, NodeType.STATION,
                  E2, NodeType.STATION)),
          List.of(
              new TimetableTestFixtures.Edge(DEP, A, 50, 10.0),
              new TimetableTestFixtures.Edge(A, B, 100, 10.0),
              new TimetableTestFixtures.Edge(B, C, 100, 10.0),
              new TimetableTestFixtures.Edge(C, D, 100, 10.0),
              new TimetableTestFixtures.Edge(D, E, 100, 10.0),
              new TimetableTestFixtures.Edge(D, E2, 100, 10.0),
              new TimetableTestFixtures.Edge(E, DEP2, 50, 10.0),
              new TimetableTestFixtures.Edge(E2, DEP2, 50, 10.0)));
  private final UUID local = TimetableTestFixtures.routeId("LOC");
  private final UUID rapid = TimetableTestFixtures.routeId("RAP");

  /** 候选编表器按组平移相位：快车组的表定时刻整体晚 100 秒，慢车组不动（实际发车可能再被让车推一点，比的是名义时刻）。 */
  @Test
  void aCandidateBuilderShiftsTheWholeGroup() {
    TimetableBuildResult plain = build(new TimetableBuilder(), 0, false);
    TimetableBuildResult shifted =
        build(
            new TimetableBuilder(new TimetableTimingCalculator(), Map.of("rapid", 100), true),
            0,
            false);

    assertEquals(nominalOf(plain, local), nominalOf(shifted, local));
    List<Integer> before = nominalOf(plain, rapid);
    List<Integer> after = nominalOf(shifted, rapid);
    assertTrue(!before.isEmpty() && before.size() == after.size(), before + " / " + after);
    for (int i = 0; i < before.size(); i++) {
      assertEquals(before.get(i) + 100, after.get(i));
    }
  }

  /**
   * 实测接在成品表上：把一班快车挪到慢车之后 30 秒，它要晚 270 秒才追不上，被拖住 240 秒（超过裕量的部分）。
   *
   * <p>这个小路网上让车修复本来就会把快车在起点推后、自己躲开，编出来的表量不到被卡；所以手动挪一班来测。
   */
  @Test
  void theMeasureReadsTheFinishedTable() {
    Timetable table = build(new TimetableBuilder(), 90, false).timetable().orElseThrow();
    int firstLocal = departuresOf(table, local).get(0);
    List<TimetableTrip> trips = new java.util.ArrayList<>();
    boolean moved = false;
    for (TimetableTrip trip : table.trips()) {
      if (!moved && trip.routeId().equals(rapid)) {
        moved = true;
        trips.add(
            new TimetableTrip(
                trip.id(),
                trip.timetableId(),
                trip.routeId(),
                trip.sequence(),
                trip.tripCode(),
                firstLocal + 30,
                trip.dutyId()));
      } else {
        trips.add(trip);
      }
    }

    RapidStagger.Measure measure =
        RapidStagger.measure(
            table.withTripsAndDuties(trips, table.duties()),
            Map.of(
                local, profileOf(local, "LOC", List.of(A, B, C, D, E), 90),
                rapid, profileOf(rapid, "RAP", List.of(A, E2), 0)),
            TimetableConflictChecker.GraphIndex.of(graph),
            TimetableBuildOptions.DEFAULT_SEPARATION_SECONDS,
            5 * 3600);

    assertEquals(1, measure.trips(), measure::toString);
    assertEquals(240L, measure.seconds(), measure::toString);
  }

  /** 两组一样快就没有快车被卡，开了错峰也不搜。 */
  @Test
  void nothingCaughtNeedsNoSearch() {
    TimetableBuildResult result = build(new TimetableBuilder(), 0, true);

    assertTrue(result.success(), () -> result.warnings().toString());
    assertTrue(result.phaseNotes().contains("快车被卡（成品表实测）：无"), result.phaseNotes()::toString);
    assertTrue(result.phaseNotes().stream().noneMatch(note -> note.startsWith("快车错峰：")));
  }

  private TimetableBuildResult build(TimetableBuilder builder, int localDwell, boolean stagger) {
    List<TimetableBuilder.RouteInput> routes =
        List.of(
            operation(local, "LOC", List.of(A, B, C, D, E), localDwell, "local"),
            operation(rapid, "RAP", List.of(A, E2), 0, "rapid"),
            leg("CRT", RouteOperationType.CREATE, List.of(DEP, A), DEP),
            leg("RET", RouteOperationType.RETURN, List.of(E, DEP2), DEP2),
            leg("RES", RouteOperationType.RETURN, List.of(E2, DEP2), DEP2));
    return builder.build(
        new TimetableBuilder.BuildInput(
            UUID.nameUUIDFromBytes("RS".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            UUID.nameUUIDFromBytes("C".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            UUID.nameUUIDFromBytes("O".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            UUID.nameUUIDFromBytes("L".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "TT1",
            "快车错峰",
            routes,
            graph,
            TimetableTestFixtures.perEdgeSpeedModel(),
            Optional.empty()),
        new TimetableBuildOptions(
                5 * 3600,
                7 * 3600,
                Duration.ofSeconds(400),
                Duration.ofSeconds(20),
                new VehicleDutyPlanner.Limits(100, 7200, 60),
                "",
                ZONE,
                Duration.ofSeconds(TimetableBuildOptions.DEFAULT_SEPARATION_SECONDS),
                false,
                Map.of("local", 400, "rapid", 800))
            .withRapidStagger(stagger),
        BUILT_AT);
  }

  private static TimetableBuilder.RouteInput operation(
      UUID id, String code, List<String> nodes, int dwell, String group) {
    return new TimetableBuilder.RouteInput(
        id,
        code,
        RouteOperationType.OPERATION,
        1,
        TimetableTestFixtures.route(code, nodes),
        TimetableTestFixtures.stops(id, nodes.size(), dwell),
        Optional.empty(),
        Optional.empty(),
        false,
        Optional.of(group));
  }

  /** 名义时刻：被端点串行或让车推过的班次取它的名义时隙，其余就是实际发车。 */
  private static List<Integer> nominalOf(TimetableBuildResult result, UUID route) {
    Timetable timetable = result.timetable().orElseThrow();
    Map<String, Integer> nominal = new java.util.HashMap<>();
    for (TimetableBuildResult.TripShift shift : result.shifts()) {
      nominal.put(shift.tripCode(), shift.nominalSecondOfDay());
    }
    return timetable.trips().stream()
        .filter(trip -> trip.routeId().equals(route))
        .map(trip -> nominal.getOrDefault(trip.tripCode(), trip.departureSecondOfDay()))
        .sorted()
        .toList();
  }

  private TimetableConflictChecker.RouteProfile profileOf(
      UUID id, String code, List<String> waypoints, int dwell) {
    org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition route =
        TimetableTestFixtures.route(code, waypoints);
    List<org.fetarute.fetaruteTCAddon.company.model.RouteStop> stops =
        TimetableTestFixtures.stops(id, waypoints.size(), dwell);
    TimetableTimingCalculator.TimingResult timing =
        new TimetableTimingCalculator()
            .compute(graph, TimetableTestFixtures.perEdgeSpeedModel(), route, stops, Duration.ZERO);
    assertTrue(timing.ok(), () -> code + ": " + timing.failure());
    return new TimetableConflictChecker.RouteProfile(
        id,
        code,
        timing.stops(),
        timing.segments(),
        TimetableConflictChecker.platformsOf(timing.stops(), stops, route.waypoints()));
  }

  private static List<Integer> departuresOf(Timetable timetable, UUID route) {
    return timetable.trips().stream()
        .filter(trip -> trip.routeId().equals(route))
        .map(TimetableTrip::departureSecondOfDay)
        .sorted()
        .toList();
  }

  private static TimetableBuilder.RouteInput leg(
      String code, RouteOperationType type, List<String> nodes, String depot) {
    UUID id = TimetableTestFixtures.routeId(code);
    return new TimetableBuilder.RouteInput(
        id,
        code,
        type,
        0,
        TimetableTestFixtures.route(code, nodes),
        type == RouteOperationType.CREATE
            ? TimetableTestFixtures.createStops(id, nodes.size(), depot)
            : TimetableTestFixtures.returnStops(id, nodes.size(), depot),
        Optional.empty());
  }
}
