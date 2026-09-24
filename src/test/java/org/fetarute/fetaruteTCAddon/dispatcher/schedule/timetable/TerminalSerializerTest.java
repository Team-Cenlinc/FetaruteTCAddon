package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
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
 * 单股道端点按资源串行。
 *
 * <p>路网是一个星形：A（两股道）与 B（两股道）各经一个路径点 XJ（道岔位置，不在 X 的命名空间下）通到单股道尽头 X。 RA A→X、RB B→X 是两路进站流，RX X→A
 * 是续班。走行都是 10 blocks/s：全程 20 s，其中路径点到 X 的岔线 10 s； 折返 60、裕量 30，于是一次折返独占 X 的岔线 10 + 60 + 10 + 30 =
 * 110 s。
 */
class TerminalSerializerTest {

  private static final String A1 = "OP:S:A:1";
  private static final String A2 = "OP:S:A:2";
  private static final String B1 = "OP:S:B:1";
  private static final String B2 = "OP:S:B:2";
  private static final String X_APPROACH = "OP:W:XJ:1";
  private static final String X = "OP:S:X:1";
  private static final String DEPOT = "OP:D:DEP:1";
  private static final String FAR_AWAY = "OP:W:FAR:1";
  private static final int TURNAROUND = 60;
  private static final int SEPARATION = 30;

  private final RailGraph graph = star();
  private final TimetableConflictChecker.GraphIndex index =
      TimetableConflictChecker.GraphIndex.of(graph);
  private final Map<UUID, TimetableConflictChecker.RouteProfile> profiles = new LinkedHashMap<>();
  private final Map<UUID, TimetableRoutePlan> plans = new LinkedHashMap<>();
  private final UUID ra = profile("RA", List.of(A1, X_APPROACH, X));
  private final UUID rb = profile("RB", List.of(B1, X_APPROACH, X));
  private final UUID rx = profile("RX", List.of(X, X_APPROACH, A2));
  private final UUID rc = profile("RC", List.of(A1, X_APPROACH, B1));
  private final UUID ret = profile("RET", List.of(X, X_APPROACH, A1));

  /** X 是唯一的单股道端点：A、B 各有两股道，路径点不是站台。 */
  @Test
  void onlyTheSingleTrackTerminalIsSerialized() {
    assertEquals(List.of("OP:S:X"), TerminalSerializer.terminalGroups(index, profiles.values()));
    assertEquals(10, TerminalSerializer.approachIn(profiles.get(ra), "OP:S:X", index.sections()));
    assertEquals(10, TerminalSerializer.approachOut(profiles.get(rx), "OP:S:X", index.sections()));
  }

  /** 两路进站按名义到达先后串行：后到的整趟延后，续班锚在车上（早于它的名义时隙），网格上的班次不动。 */
  @Test
  void arrivalsAreDelayedNeverAdvancedAndContinuationsAreAnchored() {
    Timetable provisional =
        timetable(
            duty(
                "D001", List.of(trip("RA@0", ra, 0), trip("RX@300", rx, 300)), 0, Optional.of(ret)),
            duty(
                "D002", List.of(trip("RB@5", rb, 5), trip("RX@600", rx, 600)), 5, Optional.of(ret)),
            duty("D003", List.of(trip("RC@7", rc, 7)), 7, Optional.empty()));

    TerminalSerializer.Result result = TerminalSerializer.serialize(input(provisional, List.of()));

    Map<String, Integer> dep = departures(result.timetable());
    assertEquals(0, dep.get("RA@0"), "首班不动");
    assertEquals(20 + TURNAROUND, dep.get("RX@300"), "续班 = 到达 20 + 折返 60，早于名义时隙 300");
    // D001 占 X 到 80 + 出站 10 + 裕量 30 = 120；D002 的到达要 ≥ 120 + 进站 10 = 130，即发车 110。
    assertEquals(110, dep.get("RB@5"), "后到的进站整趟延后");
    assertEquals(130 + TURNAROUND, dep.get("RX@600"), "它的续班同样锚在车上");
    assertEquals(7, dep.get("RC@7"), "终点不是单股道端点的班次留在网格上");
    for (TerminalSerializer.Shift shift : result.shifts()) {
      if (shift.reason() != TerminalSerializer.Shift.Reason.ANCHORED_TO_VEHICLE) {
        assertTrue(shift.actualSeconds() >= shift.nominalSeconds(), "非锚定的班次只能延后: " + shift);
      }
    }
    assertEquals(1, result.terminals().size());
    TerminalSerializer.TerminalReport report = result.terminals().get(0);
    assertEquals("OP:S:X", report.group());
    assertEquals(2, report.visits());
    assertEquals(2 * (10 + TURNAROUND + 10 + SEPARATION), report.occupiedSeconds());
    assertEquals(0, report.nowhereToWait());
    assertEquals(0, report.truncated());
  }

  /**
   * 端点先到先进：全程长的车早发、晚到，不能挡住晚发、先到的车。
   *
   * <p>RL 从 200 秒外开来，0 发、220 到；RB 100 发、120 到。曾经事件按发车时刻排，RL 先被处理，把 X 的空闲时刻推到 320， RB 只能排到它后面（发车推到
   * 310）——而 X 在 120 时明明是空的。实服 WS 就是这样：2N 从南渡开来先发，小交路 1L 晚发先到，全天每班推后 276 秒。
   */
  @Test
  void theTerminalServesTrainsInArrivalOrder() {
    UUID rl = profile("RL", List.of(FAR_AWAY, B2, X_APPROACH, X));
    Timetable provisional =
        timetable(
            duty("D001", List.of(trip("RL@0", rl, 0)), 0, Optional.of(ret)),
            duty("D002", List.of(trip("RB@100", rb, 100)), 100, Optional.of(ret)));

    TerminalSerializer.Result result = TerminalSerializer.serialize(input(provisional, List.of()));

    Map<String, Integer> dep = departures(result.timetable());
    assertEquals(100, dep.get("RB@100"), "先到的不等");
    // RB 占 X 到 120 + 折返 60 + 出站 10 + 裕量 30 = 220；RL 的到达要 ≥ 220 + 进站 10 = 230，发车 10。
    assertEquals(10, dep.get("RL@0"), "后到的排在它后面，只晚 10 秒");
  }

  /** 交路时刻跟着改：延后的首班让出库提前量不变（plannedStart 同步后移），回库票 = 末班实际到达 + 折返。 */
  @Test
  void dutyTimesFollowTheShiftedTrips() {
    Timetable provisional =
        timetable(
            duty(
                "D001",
                List.of(trip("RA@0", ra, 0), trip("RX@300", rx, 300)),
                -100,
                Optional.of(ret)),
            duty("D002", List.of(trip("RB@5", rb, 5)), -95, Optional.of(ret)));

    TerminalSerializer.Result result = TerminalSerializer.serialize(input(provisional, List.of()));

    VehicleDuty d001 = dutyOf(result.timetable(), "D001");
    VehicleDuty d002 = dutyOf(result.timetable(), "D002");
    assertEquals(-100, d001.plannedStartSecondOfDay(), "首班没动，出库不动");
    assertEquals(80 + 20 + TURNAROUND, d001.returnSecondOfDay(), "回库票 = RX 到达 A 100 + 折返");
    assertEquals(-95 + 105, d002.plannedStartSecondOfDay(), "首班延后 105，出库同步后移");
    assertEquals(130 + TURNAROUND, d002.returnSecondOfDay(), "回库票 = 实际到达 130 + 折返");
  }

  /** 邻表在端点的待命是预订：我的进站只能落在它之后；邻表本身一动不动。 */
  @Test
  void neighborBookingsAreRespected() {
    TimetableConflictChecker.Platform platformX =
        new TimetableConflictChecker.Platform(X, "OP:S:X", false);
    NeighborTimetable neighbor =
        new NeighborTimetable(
            UUID.randomUUID(),
            "C/O/NB/TT",
            Instant.EPOCH,
            ZoneId.of("UTC"),
            1,
            false,
            false,
            Map.of(),
            List.of(),
            List.of(
                new TimetableConflictChecker.Stay(
                    "NB-D001", platformX, 0, 200, Optional.of("C/O/NB/TT"))),
            List.of());
    Timetable provisional =
        timetable(duty("D001", List.of(trip("RA@0", ra, 0)), 0, Optional.of(ret)));

    TerminalSerializer.Result result =
        TerminalSerializer.serialize(input(provisional, List.of(neighbor)));

    // 预订 [0 − 30, 200 + 30]；我的窗口从到达 − 进站 10 起，所以到达 ≥ 230 + 10 = 240，发车 220。
    assertEquals(220, departures(result.timetable()).get("RA@0"));
  }

  /** 起点也是单股道端点的班次无处等待：不延后、照常登记、计数。 */
  @Test
  void arrivalFromAnotherTerminalIsNotDelayedButCounted() {
    UUID ry = profile("RY", List.of(X, X_APPROACH, "OP:S:Y:1"));
    Timetable provisional =
        timetable(
            duty(
                "D001", List.of(trip("RA@0", ra, 0), trip("RY@300", ry, 300)), 0, Optional.of(ret)),
            duty("D003", List.of(trip("RY@100", ry, 100)), 100, Optional.of(ret)));

    TerminalSerializer.Result result = TerminalSerializer.serialize(input(provisional, List.of()));

    Map<String, Integer> dep = departures(result.timetable());
    // D001 的 RY 锚在车上：80 发车、100 到 Y，占 Y 到 100 + 折返 60 + 出站 10 + 裕量 30 = 200。
    // D003 从 X 发车 100、120 到 Y，本该等到 210；但 X 也是单股道端点，无处等待：不延后，照常登记。
    assertEquals(80, dep.get("RY@300"));
    assertEquals(100, dep.get("RY@100"), "起点是端点：不延后");
    TerminalSerializer.TerminalReport y =
        result.terminals().stream()
            .filter(t -> t.group().equals("OP:S:Y"))
            .findFirst()
            .orElseThrow();
    assertEquals(1, y.nowhereToWait());
  }

  /** 延后让交路超过时长上限：从那一班起截断，退到能回库的终点；截掉的班次如实上报。 */
  @Test
  void dutyIsTruncatedWhenDelayBreaksLimits() {
    Timetable provisional =
        timetable(
            duty(
                "D001", List.of(trip("RA@0", ra, 0), trip("RX@300", rx, 300)), 0, Optional.of(ret)),
            duty(
                "D002",
                List.of(trip("RB@5", rb, 5), trip("RX@600", rx, 600)),
                5,
                Optional.of(ret)));
    // D002 延后到 110 发车、130 到达；收尾 = 折返 60 + 回库 20 → 210 − 5 = 205 > 180；D001 的续班 80 + 20 + 60 = 160
    // 装得下。
    VehicleDutyPlanner.Limits tight = new VehicleDutyPlanner.Limits(4, 180, TURNAROUND);

    TerminalSerializer.Result result =
        TerminalSerializer.serialize(input(provisional, List.of(), tight));

    assertEquals(2, result.truncatedTripIds().size(), "RB 与它的续班都被截掉");
    assertTrue(
        result.timetable().duties().stream().noneMatch(d -> d.dutyCode().equals("D002")),
        "整条交路没有一班能跑，不落表");
    assertEquals(2, result.timetable().trips().size());
    assertEquals(2, result.terminals().get(0).truncated());
  }

  /** 确定性：同一输入两次串行逐字段相同。 */
  @Test
  void serializationIsDeterministic() {
    Timetable provisional =
        timetable(
            duty(
                "D001", List.of(trip("RA@0", ra, 0), trip("RX@300", rx, 300)), 0, Optional.of(ret)),
            duty(
                "D002", List.of(trip("RB@5", rb, 5), trip("RX@600", rx, 600)), 5, Optional.of(ret)),
            duty("D003", List.of(trip("RC@7", rc, 7)), 7, Optional.empty()));

    TerminalSerializer.Result first = TerminalSerializer.serialize(input(provisional, List.of()));
    TerminalSerializer.Result second = TerminalSerializer.serialize(input(provisional, List.of()));

    assertEquals(first.timetable().trips(), second.timetable().trips());
    assertEquals(first.timetable().duties(), second.timetable().duties());
    assertEquals(first.shifts(), second.shifts());
    assertEquals(first.terminals(), second.terminals());
  }

  /**
   * 结构下界：每次占用 × 按权重的经过次数 / 周期班次数。RA、RB 终到（各 w1）、RX 始发（w2）→ (1+1+2)/2 = 2 次；周期 RA+RB+RX+RC = 5 班 →
   * ceil(2 × 110 / 5) = 44。
   */
  @Test
  void floorUsesWeightedVisitsPerCycle() {
    Timetable provisional =
        timetable(duty("D001", List.of(trip("RA@0", ra, 0)), 0, Optional.of(ret)));

    TerminalSerializer.Result result = TerminalSerializer.serialize(input(provisional, List.of()));

    TerminalSerializer.TerminalReport report = result.terminals().get(0);
    assertEquals(110, report.visitCostSeconds());
    assertEquals(5, report.cycleTrips());
    assertEquals(2.0D, report.visitsPerCycle(), 1e-9);
    assertEquals(44, report.headwayFloorSeconds());
  }

  /** 没有单股道端点：原样返回。 */
  @Test
  void withoutSingleTrackTerminalsNothingChanges() {
    Timetable provisional =
        timetable(duty("D003", List.of(trip("RC@7", rc, 7)), 7, Optional.empty()));
    TerminalSerializer.Input base = input(provisional, List.of());
    TerminalSerializer.Input noTerminals =
        new TerminalSerializer.Input(
            base.provisional(),
            base.profiles(),
            new TimetableConflictChecker.GraphIndex(
                index.sections(), Map.of("OP:S:X", 2), index.nodeTypes()),
            base.zeroSecondOfDay(),
            base.horizonSeconds(),
            base.separationSeconds(),
            base.legs(),
            base.limits(),
            base.routesEndingAtDepot(),
            base.candidates(),
            base.operationPlans(),
            base.neighbors());

    TerminalSerializer.Result result = TerminalSerializer.serialize(noTerminals);

    assertTrue(result.shifts().isEmpty());
    assertTrue(result.terminals().isEmpty());
    assertEquals(provisional.trips(), result.timetable().trips());
  }

  // ------------------------------------------------------------------ 夹具

  private TerminalSerializer.Input input(Timetable provisional, List<NeighborTimetable> neighbors) {
    return input(provisional, neighbors, new VehicleDutyPlanner.Limits(4, 7200, TURNAROUND));
  }

  private TerminalSerializer.Input input(
      Timetable provisional, List<NeighborTimetable> neighbors, VehicleDutyPlanner.Limits limits) {
    // 除回库线路外全部是运营候选：RX 权重 2，其余 1；顺序与 plans 的插入顺序一致（确定）。
    List<WeightedTripAllocator.Candidate> candidates = new ArrayList<>();
    List<TimetableRoutePlan> operationPlans = new ArrayList<>();
    for (TimetableRoutePlan plan : plans.values()) {
      if (plan.routeId().equals(ret)) {
        continue;
      }
      candidates.add(
          new WeightedTripAllocator.Candidate(
              plan.routeCode(), plan.routeCode().equals("RX") ? 2 : 1));
      operationPlans.add(plan);
    }
    VehicleDutyPlanner.Legs legs =
        VehicleDutyPlanner.Legs.of(
            List.of(), List.of(new VehicleDutyPlanner.Leg(ret, "RET", DEPOT, 20)), Map.of(ret, X));
    return new TerminalSerializer.Input(
        provisional,
        profiles,
        index,
        0,
        3600,
        SEPARATION,
        legs,
        limits,
        Set.of(),
        candidates,
        operationPlans,
        neighbors);
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

  private record Trip(String code, UUID routeId, int departure) {}

  private static Trip trip(String code, UUID routeId, int departure) {
    return new Trip(code, routeId, departure);
  }

  private record Duty(
      String code, List<Trip> trips, int plannedStart, Optional<UUID> returnRoute) {}

  private static Duty duty(
      String code, List<Trip> trips, int plannedStart, Optional<UUID> returnRoute) {
    return new Duty(code, trips, plannedStart, returnRoute);
  }

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
        UUID id =
            UUID.nameUUIDFromBytes(trip.code().getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
      int returnAt = duty.returnRoute().isPresent() ? lastArrival + TURNAROUND : lastArrival;
      int end = duty.returnRoute().isPresent() ? returnAt + 20 : returnAt;
      vehicleDuties.add(
          new VehicleDuty(
              dutyId,
              timetableId,
              sequence++,
              duty.code(),
              DEPOT,
              DEPOT,
              Optional.empty(),
              duty.returnRoute(),
              ids,
              duty.plannedStart(),
              returnAt,
              end,
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
            RouteOperationType.OPERATION,
            1,
            timing.stops(),
            nodes.get(0),
            nodes.get(nodes.size() - 1),
            Optional.empty(),
            Optional.empty()));
    return id;
  }

  private static RailGraph star() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    for (String id : List.of(A1, A2, B1, B2, X, "OP:S:Y:1")) {
      nodes.put(NodeId.of(id), node(id, NodeType.STATION));
    }
    nodes.put(NodeId.of(X_APPROACH), node(X_APPROACH, NodeType.WAYPOINT));
    nodes.put(NodeId.of(DEPOT), node(DEPOT, NodeType.DEPOT));
    nodes.put(NodeId.of(FAR_AWAY), node(FAR_AWAY, NodeType.WAYPOINT));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    edge(edges, A1, X_APPROACH, 100);
    edge(edges, A2, X_APPROACH, 100);
    edge(edges, B1, X_APPROACH, 100);
    edge(edges, B2, X_APPROACH, 100);
    edge(edges, X_APPROACH, X, 100);
    edge(edges, X_APPROACH, "OP:S:Y:1", 100);
    edge(edges, DEPOT, A1, 100);
    edge(edges, FAR_AWAY, B2, 2000);
    return new SimpleRailGraph(nodes, edges, Set.of());
  }

  private static RailNode node(String id, NodeType type) {
    return new SignRailNode(
        NodeId.of(id), type, new Vector(0, 64, 0), Optional.empty(), Optional.empty());
  }

  private static void edge(Map<EdgeId, RailEdge> edges, String from, String to, int length) {
    EdgeId id = EdgeId.undirected(NodeId.of(from), NodeId.of(to));
    edges.put(
        id, new RailEdge(id, NodeId.of(from), NodeId.of(to), length, 10.0, true, Optional.empty()));
  }
}
