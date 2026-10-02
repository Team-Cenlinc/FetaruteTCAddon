package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
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
 * 增量重扫与全量重扫必须<b>逐字段相等</b>：随机扫一遍交路形态，两条路径的产物一条也不许差。
 *
 * <p>{@link OccupationIndex} 是一份缓存下来的投影：只换一辆车的占用、只重扫它碰过的资源。缓存派生视图是
 * "索引漂移"这类错误的经典温床——撤占用撤漏一条、新碰到的资源没建桶、回滚没把索引一起退回去、冲突计数 记串了，任何一处都会让修复循环拿着一张假账做取舍。这些都不会抛异常，只会让表悄悄变差。
 *
 * <p>{@code ResourceRepairChainTest.incrementalRepairEqualsFullRescan} 钉的是一个固定夹具；这里按种子随机
 * 生成交路数、班次数、出库/回库形态、让车上限、累计上限与邻表，把同一份输入分别交给两条路径跑完再逐字段比。 单车形态之外还要有两班接续的交路，否则传播与截断那一段根本走不到。
 *
 * <p>索引一旦漂移，差异会从 {@code remaining} 一路显到 {@code yields} 与 {@code timetable}：修复循环靠 {@code total}
 * 决定一段要不要提交，账错了就会提交本该回滚的段。所以这里不单比残余，六样全比。
 */
class ResourceRepairDifferentialTest {

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
  private final UUID rd = profile("RD", List.of(C1, B, A1));
  private final UUID cre = profile("CRE", List.of(DEP, A1));
  private final UUID ret = profile("RET", List.of(C1, B, A1, DEP));
  private final UUID ret2 = profile("RET2", List.of(C2, B, A2, DEP));

  @Test
  void incrementalMatchesFullRescanAcrossRandomShapes() {
    Random random = new Random(20260922L);
    int checked = 0;
    for (int seed = 0; seed < 400; seed++) {
      List<Duty> duties = new ArrayList<>();
      int n = 2 + random.nextInt(3);
      for (int i = 0; i < n; i++) {
        boolean two = random.nextBoolean();
        boolean create = random.nextBoolean();
        int dep = random.nextInt(140);
        List<Trip> trips = new ArrayList<>();
        boolean toC2 = random.nextBoolean();
        trips.add(new Trip("T" + seed + "-" + i + "-0", toC2 ? rb : ra, dep));
        if (two) {
          int second = dep + 20 + plans.get(toC2 ? rb : ra).totalRunSeconds() + random.nextInt(120);
          trips.add(new Trip("T" + seed + "-" + i + "-1", toC2 ? rc : rd, second));
        }
        duties.add(new Duty(String.format(Locale.ROOT, "D%03d", i + 1), trips, -100, create));
      }
      Timetable provisional = timetable(duties);
      int maxWait = 20 + random.nextInt(80);
      int tolerance = 20 + random.nextInt(300);
      List<NeighborTimetable> neighbors =
          random.nextBoolean() ? List.of(neighborOnEdgeAb(random.nextInt(200))) : List.of();

      ResourceRepair.Result incremental =
          ResourceRepair.repair(
              input(provisional, neighbors, maxWait, tolerance), ResourceRepair.Rescan.INCREMENTAL);
      ResourceRepair.Result full =
          ResourceRepair.repair(
              input(provisional, neighbors, maxWait, tolerance), ResourceRepair.Rescan.FULL);
      String where =
          "seed="
              + seed
              + " maxWait="
              + maxWait
              + " tol="
              + tolerance
              + " neighbors="
              + neighbors.size()
              + " duties="
              + duties;
      assertEquals(full.timetable().trips(), incremental.timetable().trips(), where);
      assertEquals(full.timetable().duties(), incremental.timetable().duties(), where);
      assertEquals(full.yields(), incremental.yields(), where);
      assertEquals(full.shifts(), incremental.shifts(), where);
      assertEquals(full.truncatedTripIds(), incremental.truncatedTripIds(), where);
      assertEquals(full.remaining(), incremental.remaining(), where);
      checked++;
    }
    assertTrue(checked > 0, "夹具要真的跑起来，否则这条等价性是空的");
  }

  // ------------------------------------------------------------------ fixture

  private ResourceRepair.Input input(
      Timetable provisional, List<NeighborTimetable> neighbors, int maxWait, int tolerance) {
    VehicleDutyPlanner.Legs legs =
        VehicleDutyPlanner.Legs.of(
            List.of(new VehicleDutyPlanner.Leg(cre, "CRE", DEP, 25)),
            List.of(
                new VehicleDutyPlanner.Leg(ret, "RET", DEP, 25),
                new VehicleDutyPlanner.Leg(ret2, "RET2", DEP, 25)),
            Map.of(cre, A1, ret, C1, ret2, C2));
    return new ResourceRepair.Input(
        provisional,
        profiles,
        index,
        0,
        3600,
        SEPARATION,
        maxWait,
        tolerance,
        legs,
        new VehicleDutyPlanner.Limits(4, 7200, TURNAROUND),
        Set.of(),
        neighbors);
  }

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
        UUID.nameUUIDFromBytes("NL-TT".getBytes(StandardCharsets.UTF_8)),
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

  private record Trip(String code, UUID routeId, int departure) {}

  private record Duty(String code, List<Trip> trips, int plannedStart, boolean create) {}

  private Timetable timetable(List<Duty> duties) {
    UUID timetableId = UUID.nameUUIDFromBytes("TT".getBytes(StandardCharsets.UTF_8));
    List<TimetableTrip> trips = new ArrayList<>();
    List<VehicleDuty> vehicleDuties = new ArrayList<>();
    int sequence = 0;
    for (Duty duty : duties) {
      UUID dutyId = TimetableTestFixtures.routeId(duty.code());
      List<UUID> ids = new ArrayList<>();
      int lastArrival = 0;
      for (Trip trip : duty.trips()) {
        UUID id = TimetableTestFixtures.routeId(trip.code());
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
      String terminal = plans.get(last.routeId()).terminalNodeId();
      UUID returnRoute = terminal.equals(C2) ? ret2 : ret;
      boolean returnable = terminal.equals(C1) || terminal.equals(C2);
      vehicleDuties.add(
          new VehicleDuty(
              dutyId,
              timetableId,
              sequence++,
              duty.code(),
              DEP,
              DEP,
              duty.create() ? Optional.of(cre) : Optional.empty(),
              returnable ? Optional.of(returnRoute) : Optional.empty(),
              ids,
              duty.plannedStart(),
              returnAt,
              returnAt + 25,
              VehicleDuty.CloseReason.HORIZON_END));
    }
    return new Timetable(
        timetableId,
        UUID.nameUUIDFromBytes("co".getBytes(StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes("op".getBytes(StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes("li".getBytes(StandardCharsets.UTF_8)),
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
            code.startsWith("RET")
                ? RouteOperationType.RETURN
                : code.equals("CRE") ? RouteOperationType.CREATE : RouteOperationType.OPERATION,
            1,
            timing.stops(),
            nodes.get(0),
            nodes.get(nodes.size() - 1),
            Optional.empty(),
            Optional.empty()));
    return id;
  }
}
