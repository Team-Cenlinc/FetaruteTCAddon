package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

/** 通过实际派车接口验证补车途径按资源压力分配，保持确定性及硬约束。 */
class TimetableDepotAllocatorTest {
  private static final String BUSY = "OP:D:BUSY:1";
  private static final String QUIET = "OP:D:QUIET:1";
  private static final String HUB = "OP:S:HUB:1";
  private static final UUID SHORT = TimetableTestFixtures.routeId("SHORT");
  private static final UUID LONG = TimetableTestFixtures.routeId("LONG");
  private static final RailGraph GRAPH =
      TimetableTestFixtures.graph(
          Map.of(
              BUSY,
              NodeType.DEPOT,
              QUIET,
              NodeType.DEPOT,
              HUB,
              NodeType.STATION,
              "OP:S:OTHER:1",
              NodeType.STATION),
          List.of(
              new TimetableTestFixtures.Edge(BUSY, HUB, 100, 10),
              new TimetableTestFixtures.Edge(QUIET, HUB, 200, 10),
              new TimetableTestFixtures.Edge(HUB, "OP:S:OTHER:1", 100, 10)));

  /** 短出库段的咽喉已被占用时，自动借用仍保留，但应选择较长而空闲的出库段。 */
  @Test
  void aBusyShorterDepotDoesNotWinEveryOpening() {
    var result = plan(List.of(new TimetableConflictChecker.Movement("BLOCK", SHORT, 590)));
    assertEquals(Optional.of(LONG), result.duties().get(0).createRouteId());
    assertEquals(QUIET, result.duties().get(0).startDepotNodeId());
  }

  /** 空闲时仍优先短出库，资源压力相同不能凭空改为轮流借车。 */
  @Test
  void aQuietShorterDepotKeepsItsDeterministicPreference() {
    assertEquals(Optional.of(SHORT), plan(List.of()).duties().get(0).createRouteId());
  }

  /** 回同一车库的两条进路中，较短进路被占用时应使用较长而空闲的进路。 */
  @Test
  void aBusyShorterReturnDoesNotWinEveryClosing() {
    var result =
        returnPlan(
            List.of(new TimetableConflictChecker.Movement("BLOCK", SHORT, 700)),
            new VehicleDutyPlanner.Limits(1, 300, 0));

    assertEquals(Optional.of(LONG), result.duties().get(0).returnRouteId());
    assertEquals("OP:D:BUSY:2", result.duties().get(0).endDepotNodeId());
    assertEquals(700, result.duties().get(0).returnSecondOfDay());
    assertEquals(130, result.duties().get(0).plannedDurationSeconds());
  }

  /** 压力更小也不能超出硬时长：长进路超限时只能使用仍合格的短进路。 */
  @Test
  void aQuieterReturnCannotExceedTheDutyDurationLimit() {
    var result =
        returnPlan(
            List.of(new TimetableConflictChecker.Movement("BLOCK", SHORT, 700)),
            new VehicleDutyPlanner.Limits(1, 120, 0));
    assertEquals(Optional.of(SHORT), result.duties().get(0).returnRouteId());
    assertEquals(120, result.duties().get(0).plannedDurationSeconds());
  }

  /** 第一辆车已选定的回库立刻形成压力，第二辆车不能继续把该进路当作空闲。 */
  @Test
  void laterReturnsSeeTheEarlierClosingAllocation() {
    var result = returnPlan(List.of(), new VehicleDutyPlanner.Limits(1, 300, 0), 2);
    assertEquals(
        List.of(Optional.of(SHORT), Optional.of(LONG)),
        result.duties().stream().map(VehicleDuty::returnRouteId).toList());
  }

  private static VehicleDutyPlanner.Result returnPlan(
      List<TimetableConflictChecker.Movement> traffic, VehicleDutyPlanner.Limits limits) {
    return returnPlan(traffic, limits, 1);
  }

  private static VehicleDutyPlanner.Result returnPlan(
      List<TimetableConflictChecker.Movement> traffic,
      VehicleDutyPlanner.Limits limits,
      int count) {
    String otherTrack = "OP:D:BUSY:2";
    var graph =
        TimetableTestFixtures.graph(
            Map.of(
                BUSY,
                NodeType.DEPOT,
                otherTrack,
                NodeType.DEPOT,
                HUB,
                NodeType.STATION,
                "OP:S:OTHER:1",
                NodeType.STATION),
            List.of(
                new TimetableTestFixtures.Edge(HUB, BUSY, 100, 10),
                new TimetableTestFixtures.Edge(HUB, otherTrack, 200, 10),
                new TimetableTestFixtures.Edge(HUB, "OP:S:OTHER:1", 100, 10)));
    UUID create = TimetableTestFixtures.routeId("CREATE");
    var legs =
        VehicleDutyPlanner.Legs.of(
            List.of(new VehicleDutyPlanner.Leg(create, "CREATE", BUSY, 10)),
            List.of(
                new VehicleDutyPlanner.Leg(SHORT, "SHORT", BUSY, 10),
                new VehicleDutyPlanner.Leg(LONG, "LONG", otherTrack, 20)),
            Map.of(create, HUB, SHORT, HUB, LONG, HUB));
    List<VehicleDutyPlanner.PlannedTrip> trips = new java.util.ArrayList<>();
    for (int i = 0; i < count; i++) {
      trips.add(
          new VehicleDutyPlanner.PlannedTrip(
              UUID.randomUUID(), null, "FIRST" + i, HUB, HUB, 600 + 5 * i, 100, false, false, ""));
    }
    var profiles = new java.util.HashMap<UUID, TimetableConflictChecker.RouteProfile>();
    for (var entry : Map.of(SHORT, BUSY, LONG, otherTrack).entrySet()) {
      var route =
          TimetableTestFixtures.route(entry.getKey().toString(), List.of(HUB, entry.getValue()));
      var stops = TimetableTestFixtures.returnStops(entry.getKey(), 2, entry.getValue());
      var timing =
          new TimetableTimingCalculator()
              .compute(
                  graph, TimetableTestFixtures.perEdgeSpeedModel(), route, stops, Duration.ZERO);
      profiles.put(
          entry.getKey(),
          new TimetableConflictChecker.RouteProfile(
              entry.getKey(),
              entry.getKey().toString(),
              timing.stops(),
              timing.segments(),
              TimetableConflictChecker.platformsOf(timing.stops(), stops, route.waypoints())));
    }
    return VehicleDutyPlanner.plan(
        UUID.randomUUID(),
        trips,
        legs,
        limits,
        Map.of(),
        Map.of(),
        new TimetableDepotAllocator(
            TimetableConflictChecker.GraphIndex.of(graph), profiles, traffic, List.of(), 15));
  }

  private static VehicleDutyPlanner.Result plan(List<TimetableConflictChecker.Movement> traffic) {
    var shortLeg = new VehicleDutyPlanner.Leg(SHORT, "SHORT", BUSY, 10);
    var longLeg = new VehicleDutyPlanner.Leg(LONG, "LONG", QUIET, 20);
    UUID ret = TimetableTestFixtures.routeId("RETURN");
    var legs =
        VehicleDutyPlanner.Legs.of(
            List.of(shortLeg, longLeg),
            List.of(new VehicleDutyPlanner.Leg(ret, "RETURN", BUSY, 10)),
            Map.of(SHORT, HUB, LONG, HUB, ret, HUB));
    var trip =
        new VehicleDutyPlanner.PlannedTrip(
            UUID.randomUUID(), null, "FIRST", HUB, HUB, 600, 100, false, false, "");
    var selector =
        new TimetableDepotAllocator(
            TimetableConflictChecker.GraphIndex.of(GRAPH),
            Map.of(SHORT, profile(SHORT, "SHORT", BUSY), LONG, profile(LONG, "LONG", QUIET)),
            traffic,
            List.of(),
            15);
    return VehicleDutyPlanner.plan(
        UUID.randomUUID(),
        List.of(trip),
        legs,
        VehicleDutyPlanner.Limits.defaults(),
        Map.of(),
        Map.of(),
        selector);
  }

  private static TimetableConflictChecker.RouteProfile profile(UUID id, String code, String depot) {
    var route = TimetableTestFixtures.route(code, List.of(depot, HUB));
    var stops = TimetableTestFixtures.createStops(id, 2, depot);
    var timing =
        new TimetableTimingCalculator()
            .compute(GRAPH, TimetableTestFixtures.perEdgeSpeedModel(), route, stops, Duration.ZERO);
    return new TimetableConflictChecker.RouteProfile(
        id,
        code,
        timing.stops(),
        timing.segments(),
        TimetableConflictChecker.platformsOf(timing.stops(), stops, route.waypoints()));
  }
}
