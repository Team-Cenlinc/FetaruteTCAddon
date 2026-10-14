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

  /** 联编多线时等待相同优先本线车库：借来的出库段更短也不选，避免两条线整体对调车库。 */
  @Test
  void aQuietHomeDepotWinsOverAShorterBorrowedOne() {
    var result = linedPlan(List.of());
    assertEquals(Optional.of(LONG), result.duties().get(0).createRouteId());
    assertEquals(QUIET, result.duties().get(0).startDepotNodeId());
  }

  /** 本线出库段更挤时照常借用：本线优先只在等待不比借用多时生效。 */
  @Test
  void aBusyHomeDepotStillBorrows() {
    var result = linedPlan(List.of(new TimetableConflictChecker.Movement("BLOCK", LONG, 580)));
    assertEquals(Optional.of(SHORT), result.duties().get(0).createRouteId());
  }

  /**
   * 本线只比借用多等几秒也照常借用：不设容差。投影是名义班次，让车修复挪动运营车之后，这点等待常常落到运营车身上、在没有空股道的让车点让不掉 （真实线网回放：容差 10
   * 秒或一个最小间隔都让严格模式失败）。
   */
  @Test
  void aSlightlyBusierHomeDepotStillBorrows() {
    var result = linedPlan(List.of(new TimetableConflictChecker.Movement("BLOCK", LONG, 560)));
    assertEquals(Optional.of(SHORT), result.duties().get(0).createRouteId());
  }

  /** 同一对车在一次相遇里压到的边与两端道岔只算一次，取最长的等待；不同的车、隔开的两次相遇分别计。 */
  @Test
  void pressureCountsEachEncounterOnce() {
    var crossing =
        List.of(
            conflict("edge:X", "A", "B", 100, 120, 110, 130),
            conflict("junction:X1", "A", "B", 98, 101, 108, 111),
            conflict("junction:X2", "B", "A", 119, 122, 129, 132));
    var once = TimetableDepotAllocator.pressureOf(crossing, 15);
    assertEquals(25, once.waitSeconds());
    assertEquals(1, once.conflicts());

    var more = new java.util.ArrayList<>(crossing);
    more.add(conflict("edge:Y", "A", "C", 100, 120, 130, 150));
    more.add(conflict("edge:Z", "B", "A", 400, 420, 430, 450));
    var three = TimetableDepotAllocator.pressureOf(more, 15);
    assertEquals(25 + 5 + 5, three.waitSeconds());
    assertEquals(3, three.conflicts());
  }

  /** 邻表的交路号可能与本表同名（都有 D001-CREATE）：不同表的同名车不是同一辆车，各算一次相遇。 */
  @Test
  void sameCodesFromDifferentTimetablesAreDifferentEncounters() {
    var mine = conflict("edge:X", "D001-CREATE", "R-001", 100, 120, 110, 130);
    var neighbour =
        new TimetableConflictChecker.Conflict(
            TimetableConflictChecker.Kind.JUNCTION,
            "junction:X1",
            "D001-CREATE",
            "R-001",
            98,
            101,
            108,
            111,
            Optional.of("CO/OP/L2"),
            Optional.empty());

    var pressure = TimetableDepotAllocator.pressureOf(List.of(mine, neighbour), 15);

    assertEquals(25 + 8, pressure.waitSeconds());
    assertEquals(2, pressure.conflicts());
  }

  private static TimetableConflictChecker.Conflict conflict(
      String resource,
      String first,
      String second,
      int firstFrom,
      int firstTo,
      int secondFrom,
      int secondTo) {
    return new TimetableConflictChecker.Conflict(
        TimetableConflictChecker.Kind.TRACK,
        resource,
        first,
        second,
        firstFrom,
        firstTo,
        secondFrom,
        secondTo,
        Optional.empty(),
        Optional.empty());
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

  /** 首班属于 HOME 线；短出库段 SHORT 属于另一条线（借用），长出库段 LONG 属于 HOME 线。 */
  private static VehicleDutyPlanner.Result linedPlan(
      List<TimetableConflictChecker.Movement> traffic) {
    UUID home = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    UUID service = TimetableTestFixtures.routeId("SERVICE");
    var shortLeg = new VehicleDutyPlanner.Leg(SHORT, "SHORT", BUSY, 10);
    var longLeg = new VehicleDutyPlanner.Leg(LONG, "LONG", QUIET, 20);
    UUID ret = TimetableTestFixtures.routeId("RETURN");
    var legs =
        VehicleDutyPlanner.Legs.of(
            List.of(shortLeg, longLeg),
            List.of(new VehicleDutyPlanner.Leg(ret, "RETURN", QUIET, 10)),
            Map.of(SHORT, HUB, LONG, HUB, ret, HUB));
    var trip =
        new VehicleDutyPlanner.PlannedTrip(
            UUID.randomUUID(), service, "FIRST", HUB, HUB, 600, 100, false, false, "");
    Map<UUID, UUID> lines = Map.of(service, home, LONG, home, ret, home, SHORT, other);
    var selector =
        new TimetableDepotAllocator(
            TimetableConflictChecker.GraphIndex.of(GRAPH),
            Map.of(SHORT, profile(SHORT, "SHORT", BUSY), LONG, profile(LONG, "LONG", QUIET)),
            traffic,
            List.of(),
            15,
            route -> Optional.ofNullable(lines.get(route)));
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
