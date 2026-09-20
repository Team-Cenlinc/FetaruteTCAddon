package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.NeighborTimetable;
import org.junit.jupiter.api.Test;

/**
 * 让车写进表。
 *
 * <p>路网：DEP 连 A:1 与 A:2，A:1 – B – C:1、A:1 – B – C:2，A:2 – B，全线 10 blocks/s。RA 跑 A:1→B→C:1、RB 跑
 * A:1→B→C:2：共用 A:1–B 边与 B 站。 RA 0 s 发车占 A:1–B 边 [0, 10]，RB 5 s 发车占 [5, 15]，裕量 30 → RB 要延后到 10 + 30
 * = 40 才不撞，让车 35 s；B 站台上 25 s 的冲突随之一起消掉。 两条交路各自从自己的终点股道回库（C:1→B→A:1→DEP、C:2→B→A:2→DEP），回库走行互不相撞。
 */
class ResourceRepairTest {

  private static final String DEP = "OP:D:DEP:1";
  private static final String A1 = "OP:S:A:1";
  private static final String A2 = "OP:S:A:2";
  private static final String B = "OP:S:B:1";
  private static final String C1 = "OP:S:C:1";
  private static final String C2 = "OP:S:C:2";
  private static final int TURNAROUND = 60;
  private static final int SEPARATION = 30;
  private static final String NEIGHBOR = "CHT/SURN/NL/NL-TT";

  private final RailGraph graph =
      TimetableTestFixtures.graph(
          new LinkedHashMap<>(
              Map.of(
                  DEP, NodeType.DEPOT,
                  A1, NodeType.STATION,
                  A2, NodeType.STATION,
                  B, NodeType.STATION,
                  C1, NodeType.STATION,
                  C2, NodeType.STATION)),
          List.of(
              new TimetableTestFixtures.Edge(DEP, A1, 50, 10.0),
              new TimetableTestFixtures.Edge(DEP, A2, 50, 10.0),
              new TimetableTestFixtures.Edge(A1, B, 100, 10.0),
              new TimetableTestFixtures.Edge(A2, B, 100, 10.0),
              new TimetableTestFixtures.Edge(B, C1, 100, 10.0),
              new TimetableTestFixtures.Edge(B, C2, 100, 10.0)));
  private final TimetableConflictChecker.GraphIndex index =
      TimetableConflictChecker.GraphIndex.of(graph);
  private final Map<UUID, TimetableConflictChecker.RouteProfile> profiles = new LinkedHashMap<>();
  private final Map<UUID, TimetableRoutePlan> plans = new LinkedHashMap<>();
  private final UUID ra = profile("RA", List.of(A1, B, C1));
  private final UUID rb = profile("RB", List.of(A1, B, C2));
  private final UUID rc = profile("RC", List.of(C2, B, A2));
  private final UUID ret = profile("RET", List.of(C1, B, A1, DEP));
  private final UUID ret2 = profile("RET2", List.of(C2, B, A2, DEP));

  /** 35 s 的追踪冲突写进表：RB 延后到 40，让车清单一条（区间），修完没有冲突。 */
  @Test
  void yieldsUnderMaxWaitAreWrittenIntoTheTable() {
    Timetable provisional =
        timetable(
            duty("D001", List.of(trip("RA@0", ra, 0)), -100),
            duty("D002", List.of(trip("RB@5", rb, 5)), -100));

    ResourceRepair.Result result = ResourceRepair.repair(input(provisional, List.of(), 60, 300));

    assertTrue(result.remaining().clean(), () -> result.remaining().conflicts().toString());
    assertEquals(1, result.yields().size(), () -> result.yields().toString());
    ResourceRepair.Yield yield = result.yields().get(0);
    assertEquals(TimetableConflictChecker.Kind.TRACK, yield.kind());
    assertEquals("RA@0", yield.first());
    assertEquals("RB@5", yield.second());
    assertEquals(35, yield.waitSeconds());
    assertFalse(yield.external());
    assertEquals(40, departures(result.timetable()).get("RB@5"));
    assertEquals(0, departures(result.timetable()).get("RA@0"));
    assertEquals(1, result.shifts().size());
    assertEquals(TerminalSerializer.Shift.Reason.YIELDED, result.shifts().get(0).reason());
    // 交路的回库票跟着末班到达走。
    assertEquals(40 + 20 + TURNAROUND, dutyOf(result.timetable(), "D002").returnSecondOfDay());
  }

  /** 让车沿交路链传播：RB 延后后到达 C 是 60，折返 60 → 就绪 120，原本 110 发的续班 RC 后移到 120（VEHICLE_READY）。 */
  @Test
  void yieldsPropagateAlongTheDuty() {
    Timetable provisional =
        timetable(
            duty("D001", List.of(trip("RA@0", ra, 0)), -100),
            duty("D002", List.of(trip("RB@5", rb, 5), trip("RC@110", rc, 110)), -100));

    ResourceRepair.Result result = ResourceRepair.repair(input(provisional, List.of(), 60, 300));

    assertTrue(result.remaining().clean(), () -> result.remaining().conflicts().toString());
    assertEquals(40, departures(result.timetable()).get("RB@5"));
    assertEquals(120, departures(result.timetable()).get("RC@110"));
    Map<UUID, TerminalSerializer.Shift.Reason> reasons = new LinkedHashMap<>();
    result.shifts().forEach(shift -> reasons.put(shift.tripId(), shift.reason()));
    assertEquals(TerminalSerializer.Shift.Reason.YIELDED, reasons.get(tripId("RB@5")));
    assertEquals(TerminalSerializer.Shift.Reason.VEHICLE_READY, reasons.get(tripId("RC@110")));
  }

  /** 超过 --max-wait 的不动（区间 35 s、站台 25 s 都超过 20）：表原样、让车为空、冲突留在 remaining 里。 */
  @Test
  void conflictsOverMaxWaitRemainAsRealConflicts() {
    Timetable provisional =
        timetable(
            duty("D001", List.of(trip("RA@0", ra, 0)), -100),
            duty("D002", List.of(trip("RB@5", rb, 5)), -100));

    ResourceRepair.Result result = ResourceRepair.repair(input(provisional, List.of(), 20, 300));

    assertTrue(result.yields().isEmpty());
    assertTrue(result.shifts().isEmpty());
    assertEquals(provisional.trips(), result.timetable().trips());
    assertFalse(result.remaining().clean());
    assertTrue(
        result.remaining().conflicts().stream()
            .anyMatch(c -> c.kind() == TimetableConflictChecker.Kind.TRACK));
  }

  /** 单处上限是"每一处"的：区间 35 s 超过 30 不能一步修，但站台 25 s 可以；修完站台之后区间只剩 10 s，再修——累计 35 s 仍然写进表。 */
  @Test
  void smallYieldsCanAddUpBeyondASingleMaxWait() {
    Timetable provisional =
        timetable(
            duty("D001", List.of(trip("RA@0", ra, 0)), -100),
            duty("D002", List.of(trip("RB@5", rb, 5)), -100));

    ResourceRepair.Result result = ResourceRepair.repair(input(provisional, List.of(), 30, 300));

    assertTrue(result.remaining().clean(), () -> result.remaining().conflicts().toString());
    assertEquals(40, departures(result.timetable()).get("RB@5"));
    assertEquals(2, result.yields().size(), () -> result.yields().toString());
    assertTrue(result.yields().stream().allMatch(y -> y.waitSeconds() <= 30));
  }

  /** --max-wait 0 关闭修复：只扫一遍。 */
  @Test
  void zeroMaxWaitDisablesRepair() {
    Timetable provisional =
        timetable(
            duty("D001", List.of(trip("RA@0", ra, 0)), -100),
            duty("D002", List.of(trip("RB@5", rb, 5)), -100));

    ResourceRepair.Result result = ResourceRepair.repair(input(provisional, List.of(), 0, 300));

    assertTrue(result.yields().isEmpty());
    assertTrue(result.shifts().isEmpty());
    assertFalse(result.remaining().clean());
    assertEquals(provisional.trips(), result.timetable().trips());
  }

  /** 同一班累计让车超过容差就截断：容差 20 装不下 35 s 的让车，RB 那条交路整条截掉，剩下的表没有冲突。 */
  @Test
  void cumulativeWaitOverToleranceTruncates() {
    Timetable provisional =
        timetable(
            duty("D001", List.of(trip("RA@0", ra, 0)), -100),
            duty("D002", List.of(trip("RB@5", rb, 5)), -100));

    ResourceRepair.Result result = ResourceRepair.repair(input(provisional, List.of(), 60, 20));

    assertEquals(List.of(tripId("RB@5")), result.truncatedTripIds());
    assertTrue(result.remaining().clean(), () -> result.remaining().conflicts().toString());
    assertTrue(result.timetable().trips().stream().noneMatch(t -> t.tripCode().equals("RB@5")));
    assertTrue(result.timetable().duties().stream().noneMatch(d -> d.dutyCode().equals("D002")));
  }

  /** 邻表永远是前车：它后到也不挪它，挪先到的我——RA 延后到邻表离开 + 裕量；邻表的运行原样。 */
  @Test
  void neighborIsAlwaysTheLeadingTrain() {
    Timetable provisional = timetable(duty("D001", List.of(trip("RA@0", ra, 0)), -100));
    NeighborTimetable neighbor = neighborOnEdgeAb(5);

    ResourceRepair.Result result =
        ResourceRepair.repair(input(provisional, List.of(neighbor), 60, 300));

    assertTrue(result.remaining().clean(), () -> result.remaining().conflicts().toString());
    assertEquals(1, result.yields().size(), () -> result.yields().toString());
    ResourceRepair.Yield yield = result.yields().get(0);
    assertTrue(yield.external());
    assertEquals(Optional.of(NEIGHBOR), yield.firstOwner());
    assertEquals("NB-001", yield.first());
    assertEquals("RA@0", yield.second());
    assertEquals(15 + SEPARATION, yield.waitSeconds());
    assertEquals(45, departures(result.timetable()).get("RA@0"));
  }

  /** 两次修复逐字段相等。 */
  @Test
  void repairIsDeterministic() {
    Timetable provisional =
        timetable(
            duty("D001", List.of(trip("RA@0", ra, 0)), -100),
            duty("D002", List.of(trip("RB@5", rb, 5), trip("RC@110", rc, 110)), -100),
            duty("D003", List.of(trip("RA@12", ra, 12)), -100));

    ResourceRepair.Result first = ResourceRepair.repair(input(provisional, List.of(), 60, 300));
    ResourceRepair.Result second = ResourceRepair.repair(input(provisional, List.of(), 60, 300));

    assertEquals(first.timetable().trips(), second.timetable().trips());
    assertEquals(first.timetable().duties(), second.timetable().duties());
    assertEquals(first.yields(), second.yields());
    assertEquals(first.shifts(), second.shifts());
    assertEquals(first.remaining(), second.remaining());
  }

  // ------------------------------------------------------------------ 夹具

  private ResourceRepair.Input input(
      Timetable provisional, List<NeighborTimetable> neighbors, int maxWait, int tolerance) {
    VehicleDutyPlanner.Legs legs =
        VehicleDutyPlanner.Legs.of(
            List.of(),
            List.of(
                new VehicleDutyPlanner.Leg(ret, "RET", DEP, 25),
                new VehicleDutyPlanner.Leg(ret2, "RET2", DEP, 25)),
            Map.of(ret, C1, ret2, C2));
    return new ResourceRepair.Input(
        provisional,
        profiles,
        index,
        0,
        3600,
        TURNAROUND,
        SEPARATION,
        maxWait,
        tolerance,
        legs,
        new VehicleDutyPlanner.Limits(4, 7200, TURNAROUND),
        Set.of(),
        neighbors);
  }

  /** 邻表：一趟 NB（A:1→B）在相对 {@code start} 秒发车，占 A:1–B 边 10 秒。 */
  private NeighborTimetable neighborOnEdgeAb(int start) {
    UUID nb = TimetableTestFixtures.routeId("NB");
    RouteDefinition definition = TimetableTestFixtures.route("NB", List.of(A1, B));
    List<RouteStop> stops = TimetableTestFixtures.stops(nb, 2, 0);
    TimetableTimingCalculator.TimingResult timing =
        new TimetableTimingCalculator()
            .compute(
                graph, TimetableTestFixtures.perEdgeSpeedModel(), definition, stops, Duration.ZERO);
    assertTrue(timing.ok());
    TimetableConflictChecker.RouteProfile profile =
        new TimetableConflictChecker.RouteProfile(
            nb,
            "NB",
            timing.stops(),
            timing.segments(),
            TimetableConflictChecker.platformsOf(
                timing.stops(), stops, definition.waypoints(), index.nodeTypes()));
    return new NeighborTimetable(
        UUID.nameUUIDFromBytes("NL-TT".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        NEIGHBOR,
        Instant.parse("2026-02-28T12:00:00Z"),
        ZoneId.of("UTC"),
        1,
        false,
        false,
        Map.of(nb, profile),
        List.of(new TimetableConflictChecker.Movement("NB-001", nb, start, Optional.of(NEIGHBOR))),
        List.of(),
        List.of());
  }

  private static Map<String, Integer> departures(Timetable timetable) {
    Map<String, Integer> out = new LinkedHashMap<>();
    for (TimetableTrip trip : timetable.trips()) {
      out.put(trip.tripCode(), trip.departureSecondOfDay());
    }
    return out;
  }

  private static VehicleDuty dutyOf(Timetable timetable, String code) {
    return timetable.duties().stream()
        .filter(d -> d.dutyCode().equals(code))
        .findFirst()
        .orElseThrow();
  }

  private static UUID tripId(String code) {
    return UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private record Trip(String code, UUID routeId, int departure) {}

  private static Trip trip(String code, UUID routeId, int departure) {
    return new Trip(code, routeId, departure);
  }

  private record Duty(String code, List<Trip> trips, int plannedStart) {}

  private static Duty duty(String code, List<Trip> trips, int plannedStart) {
    return new Duty(code, trips, plannedStart);
  }

  /** 每条交路从末班的终点股道回库（C:1 走 RET、C:2 走 RET2），没有出库走行：待命从首班发车算。 */
  private Timetable timetable(Duty... duties) {
    UUID timetableId =
        UUID.nameUUIDFromBytes("TT".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    List<TimetableTrip> trips = new ArrayList<>();
    List<VehicleDuty> vehicleDuties = new ArrayList<>();
    int sequence = 0;
    for (Duty duty : duties) {
      UUID dutyId =
          UUID.nameUUIDFromBytes(duty.code().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      List<UUID> ids = new ArrayList<>();
      int lastArrival = 0;
      for (Trip trip : duty.trips()) {
        UUID id = tripId(trip.code());
        ids.add(id);
        trips.add(
            new TimetableTrip(
                id,
                timetableId,
                trip.routeId(),
                trips.size(),
                trip.code(),
                trip.departure(),
                Optional.of(dutyId)));
        lastArrival = trip.departure() + plans.get(trip.routeId()).totalRunSeconds();
      }
      int returnAt = lastArrival + TURNAROUND;
      Trip last = duty.trips().get(duty.trips().size() - 1);
      UUID returnRoute = plans.get(last.routeId()).terminalNodeId().equals(C2) ? ret2 : ret;
      vehicleDuties.add(
          new VehicleDuty(
              dutyId,
              timetableId,
              sequence++,
              duty.code(),
              DEP,
              DEP,
              Optional.empty(),
              Optional.of(returnRoute),
              ids,
              duty.plannedStart(),
              returnAt,
              returnAt + 25,
              VehicleDuty.CloseReason.HORIZON_END));
    }
    return new Timetable(
        timetableId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "TT",
        "t",
        TimetableStatus.DRAFT,
        ZoneId.of("UTC"),
        0,
        3600,
        new ArrayList<>(plans.values()),
        trips,
        vehicleDuties,
        Optional.empty(),
        Instant.EPOCH,
        Instant.EPOCH);
  }

  private UUID profile(String code, List<String> nodes) {
    UUID id = TimetableTestFixtures.routeId(code);
    RouteDefinition route = TimetableTestFixtures.route(code, nodes);
    List<RouteStop> stops = TimetableTestFixtures.stops(id, nodes.size(), 0);
    TimetableTimingCalculator.TimingResult timing =
        new TimetableTimingCalculator()
            .compute(graph, TimetableTestFixtures.perEdgeSpeedModel(), route, stops, Duration.ZERO);
    assertTrue(timing.ok(), () -> code + ": " + timing.failure());
    profiles.put(
        id,
        new TimetableConflictChecker.RouteProfile(
            id,
            code,
            timing.stops(),
            timing.segments(),
            TimetableConflictChecker.platformsOf(
                timing.stops(), stops, route.waypoints(), index.nodeTypes())));
    plans.put(
        id,
        new TimetableRoutePlan(
            id,
            code,
            code.startsWith("RET") ? RouteOperationType.RETURN : RouteOperationType.OPERATION,
            1,
            timing.stops(),
            nodes.get(0),
            nodes.get(nodes.size() - 1),
            Optional.empty(),
            Optional.empty()));
    return id;
  }
}
