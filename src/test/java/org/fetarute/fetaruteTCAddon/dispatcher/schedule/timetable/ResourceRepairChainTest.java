package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
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
 * 连锁段：回滚判据按一段而不是按一步。
 *
 * <p>夹具刻意只剩一种资源——A:1 与 B:1 之间那一条边。绕行的 A:1 – X – B:1 让这条边不是桥（否则它还会再登记一层单线区段）， X 是路径点不登记站台；route
 * 只有首末两站，中间站台与道岔都没有；交路既不出库也不回库，所以一辆车只贡献一条运行。 于是每一处冲突都是"同一条边上前后两趟车"，连锁怎么走一目了然。
 *
 * <p>每趟跑 A:1 → B:1 要 10 s，裕量 30 s：前车 {@code [t, t+10]} 走完之后，后车至少要到 {@code t + 40} 才能进。
 */
class ResourceRepairChainTest {

  private static final String A = "OP:S:A:1";
  private static final String B = "OP:S:B:1";
  private static final String X = "OP:S:X:1:001";
  private static final String DEP = "OP:D:DEP:1";
  private static final int SEPARATION = 30;
  private static final int TURNAROUND = 60;
  private static final String NEIGHBOR = "C/O/N/N-TT";

  private final RailGraph graph =
      TimetableTestFixtures.graph(
          nodes(),
          List.of(
              new TimetableTestFixtures.Edge(A, B, 100, 10.0),
              new TimetableTestFixtures.Edge(A, X, 100, 10.0),
              new TimetableTestFixtures.Edge(X, B, 100, 10.0)));
  private final TimetableConflictChecker.GraphIndex index =
      TimetableConflictChecker.GraphIndex.of(graph);
  private final Map<UUID, TimetableConflictChecker.RouteProfile> profiles = new LinkedHashMap<>();
  private final Map<UUID, TimetableRoutePlan> plans = new LinkedHashMap<>();
  private final UUID r = profile("R");

  /**
   * 一串小让车合起来才让表变好，所以判据必须看整段。
   *
   * <p>T1@0、T2@5、T3@10 三趟挤在同一条边上，冲突两处：(T1,T2) 要让 35 s、(T1,T3) 要让 30 s。
   *
   * <p>第一段从 (T1,T2) 开：T2 挪到 40 之后它自己撞上了 T3（T3 占到 20，40 太早），冲突数 2 → 2 <b>一处没少</b>——
   * 单步判据到这里就回滚了，于是这张表一处也修不动。连锁段接着修那处新冒出来的 10 s，T2 到 50，冲突剩一处，整段接受。 第二段同理把 (T1,T3) 连它带出来的 30 s
   * 一起修掉。四处让车之后表干净。
   */
  @Test
  void chainOfSmallYieldsIsAcceptedAsOneSegment() {
    Timetable provisional = timetable(trip("T1", 0), trip("T2", 5), trip("T3", 10));

    ResourceRepair.Result result = ResourceRepair.repair(input(provisional, List.of(), 60, 300));

    assertTrue(result.remaining().clean(), () -> result.remaining().conflicts().toString());
    assertEquals(4, result.yields().size(), () -> result.yields().toString());
    Map<String, Integer> departures = departures(result.timetable());
    assertEquals(0, departures.get("T1"));
    assertEquals(40, departures.get("T3"));
    assertEquals(80, departures.get("T2"));
  }

  /**
   * 段末变糟，段里已经施加的每一步都要撤掉——包括那一步单独看完全说得过去的。
   *
   * <p>同样三趟，外加一趟邻表 NB 在 50 s 占住这条边（它比谁都晚，起初谁也不撞）。第一段从 (T1,T2) 开： T2 到 40 与前一个用例一样是"冲突数不变"，接着修那 10 s
   * 把 T2 推到 50——正好撞上 NB。邻表挪不动，我得等到 90， 超过 36 s 的单步上限，于是段末的真冲突从 0 变成 1：整段回滚，T2 回到 5，连第一步那 35 s 也不留。
   *
   * <p>(T1,T3) 开的第二段撞上同一堵墙。两处都判成真冲突之后没有可修的了，表原样交出去。
   */
  @Test
  void worseningChainIsRolledBackAsAWhole() {
    Timetable provisional = timetable(trip("T1", 0), trip("T2", 5), trip("T3", 10));

    ResourceRepair.Result result =
        ResourceRepair.repair(input(provisional, List.of(neighborAt(50)), 36, 300));

    assertTrue(result.yields().isEmpty(), () -> result.yields().toString());
    assertTrue(result.shifts().isEmpty(), () -> result.shifts().toString());
    assertEquals(provisional.trips(), result.timetable().trips(), "整段回滚：表一个字都不改");
    assertEquals(5, departures(result.timetable()).get("T2"), "第一步那 35 s 也不留");
    assertFalse(result.remaining().clean());
  }

  /** 增量重扫与全量重扫的产物逐字段相等——语义一漂移这里就红。 */
  @Test
  void incrementalRepairEqualsFullRescan() {
    Timetable provisional =
        timetable(trip("T1", 0), trip("T2", 5), trip("T3", 10), trip("T4", 20), trip("T5", 33));

    ResourceRepair.Result incremental =
        ResourceRepair.repair(
            input(provisional, List.of(neighborAt(120)), 60, 300),
            ResourceRepair.Rescan.INCREMENTAL);
    ResourceRepair.Result full =
        ResourceRepair.repair(
            input(provisional, List.of(neighborAt(120)), 60, 300), ResourceRepair.Rescan.FULL);

    assertFalse(incremental.yields().isEmpty(), "夹具要真的修出东西来，否则这条等价性是空的");
    assertEquals(full.timetable().trips(), incremental.timetable().trips());
    assertEquals(full.timetable().duties(), incremental.timetable().duties());
    assertEquals(full.yields(), incremental.yields());
    assertEquals(full.shifts(), incremental.shifts());
    assertEquals(full.truncatedTripIds(), incremental.truncatedTripIds());
    assertEquals(full.remaining(), incremental.remaining());
  }

  // ------------------------------------------------------------------ 夹具

  private static Map<String, NodeType> nodes() {
    Map<String, NodeType> out = new LinkedHashMap<>();
    out.put(A, NodeType.STATION);
    out.put(B, NodeType.STATION);
    out.put(X, NodeType.WAYPOINT);
    return out;
  }

  private ResourceRepair.Input input(
      Timetable provisional, List<NeighborTimetable> neighbors, int maxWait, int tolerance) {
    return new ResourceRepair.Input(
        provisional,
        profiles,
        index,
        0,
        3600,
        SEPARATION,
        maxWait,
        tolerance,
        VehicleDutyPlanner.Legs.none(),
        new VehicleDutyPlanner.Limits(4, 7200, TURNAROUND),
        Set.of(r),
        neighbors);
  }

  /** 邻表：一趟 NB 在 {@code start} 秒占这条边 10 秒。它是不可移动的前车。 */
  private NeighborTimetable neighborAt(int start) {
    return new NeighborTimetable(
        UUID.nameUUIDFromBytes("N-TT".getBytes(StandardCharsets.UTF_8)),
        NEIGHBOR,
        Instant.parse("2026-02-28T12:00:00Z"),
        ZoneId.of("UTC"),
        1,
        false,
        false,
        Map.copyOf(profiles),
        List.of(new TimetableConflictChecker.Movement("NB-001", r, start, Optional.of(NEIGHBOR))),
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

  private static UUID idOf(String code) {
    return UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8));
  }

  private record Trip(String code, int departure) {}

  private static Trip trip(String code, int departure) {
    return new Trip(code, departure);
  }

  /** 一趟一条交路，既不出库也不回库：一辆车只贡献一条运行，投影里没有走行也没有待命。 */
  private Timetable timetable(Trip... trips) {
    UUID timetableId = idOf("TT");
    List<TimetableTrip> rows = new ArrayList<>();
    List<VehicleDuty> duties = new ArrayList<>();
    int run = plans.get(r).totalRunSeconds();
    int sequence = 0;
    for (Trip trip : trips) {
      UUID dutyId = idOf("D-" + trip.code());
      UUID tripId = idOf(trip.code());
      rows.add(
          new TimetableTrip(
              tripId,
              timetableId,
              r,
              rows.size(),
              trip.code(),
              trip.departure(),
              Optional.of(dutyId)));
      int arrival = trip.departure() + run;
      duties.add(
          new VehicleDuty(
              dutyId,
              timetableId,
              sequence++,
              String.format(java.util.Locale.ROOT, "D%03d", sequence),
              DEP,
              DEP,
              Optional.empty(),
              Optional.empty(),
              List.of(tripId),
              -100,
              arrival,
              arrival,
              VehicleDuty.CloseReason.HORIZON_END));
    }
    return new Timetable(
        timetableId,
        idOf("company"),
        idOf("operator"),
        idOf("line"),
        "TT",
        "t",
        TimetableStatus.DRAFT,
        ZoneId.of("UTC"),
        0,
        3600,
        new ArrayList<>(plans.values()),
        rows,
        duties,
        Optional.empty(),
        Instant.EPOCH,
        Instant.EPOCH);
  }

  private UUID profile(String code) {
    UUID id = TimetableTestFixtures.routeId(code);
    RouteDefinition route = TimetableTestFixtures.route(code, List.of(A, B));
    List<RouteStop> stops = TimetableTestFixtures.stops(id, 2, 0);
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
            A,
            B,
            Optional.empty(),
            Optional.empty()));
    return id;
  }
}
