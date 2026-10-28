package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.NeighborTimetable;
import org.junit.jupiter.api.Test;

/**
 * 严格模式取消是局部的：取消一班只动它所在的交路，别的交路的车辆、班次与出入库与不取消时逐条相同，取消之后冲突也不增加。
 *
 * <p>路网直链 DEPA–A–B–C–DEPC，两端各有车库；RA（A→C）与 RB（C→A）每 600 秒一班往返，一条交路最多 4 班。 已发布邻表在第 3 个 600 秒周期里每 60
 * 秒跑一趟 A→B（全线单线，邻表那段时间把区间占满），那段时间里与它相撞的班次让不掉，只能取消。对照组是没有邻表的同一张表（干净，不取消）。
 */
class TimetableBuilderLocalCancelTest {

  private static final Instant BUILT_AT = Instant.parse("2026-03-01T00:00:00Z");
  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final String DEPA = "OP:D:DEPA:1";
  private static final String A = "OP:S:A:1";
  private static final String B = "OP:S:B:1";
  private static final String C = "OP:S:C:1";
  private static final String DEPC = "OP:D:DEPC:1";
  private static final String NEIGHBOR = "CO/OP/L2/L2-TT";
  private static final int START = 5 * 3600;
  private static final int INTERVAL = 600;
  private static final int NEIGHBOR_RUNS = 10;
  private static final int NEIGHBOR_HEADWAY = 60;
  private static final int NEIGHBOR_FROM = 2 * INTERVAL;

  /** 邻表末班离开 A–B 区间的时刻（末班发车 + 区间运行 100 秒）。 */
  private static final int NEIGHBOR_UNTIL =
      NEIGHBOR_FROM + (NEIGHBOR_RUNS - 1) * NEIGHBOR_HEADWAY + 100;

  private final RailGraph graph =
      TimetableTestFixtures.chain(
          List.of(DEPA, A, B, C, DEPC),
          new int[] {50, 1000, 1000, 50},
          new double[] {10.0, 10.0, 10.0, 10.0});

  /** 取消的班次之外，不卷进取消的交路与对照组逐条相同；取消只落在邻表占线的那段时间里、只卷进一条交路，取消之后的冲突不比目标间隔多。 */
  @Test
  void cancellingATripLeavesTheOtherDutiesAsTheyWere() {
    TimetableBuildResult reference = build(new TimetableBuilder(), false, 10);
    TimetableBuildResult cancelled = build(new TimetableBuilder(), true, 10);

    assertTrue(reference.success(), () -> reference.warnings().toString());
    assertTrue(reference.conflictsAtTarget().isEmpty(), "对照组本身是干净的");
    assertTrue(cancelled.success(), () -> cancelled.warnings().toString());
    assertTrue(cancelled.unabsorbable().isEmpty());
    long count =
        cancelled.droppedTrips().stream()
            .filter(trip -> trip.reason() == VehicleDutyPlanner.UnassignedReason.CONFLICT_CANCELLED)
            .count();
    assertTrue(count >= 1, "邻表占满区间的那段时间里必须取消");

    Timetable before = reference.timetable().orElseThrow();
    Timetable after = cancelled.timetable().orElseThrow();
    Set<String> kept = new HashSet<>();
    for (TimetableTrip trip : after.trips()) {
      kept.add(slot(trip));
    }
    Set<String> gone = new HashSet<>();
    for (TimetableTrip trip : before.trips()) {
      if (!kept.contains(slot(trip))) {
        gone.add(slot(trip));
      }
    }
    assertEquals(count, gone.size(), () -> "只有取消的班次从表上消失，别的班次时刻不动：" + gone);
    for (TimetableTrip trip : before.trips()) {
      int at = trip.departureSecondOfDay() - START;
      assertTrue(
          !gone.contains(slot(trip)) || (at > NEIGHBOR_FROM - INTERVAL && at < NEIGHBOR_UNTIL),
          () -> "取消落在邻表占线的时段之外：" + slot(trip));
    }
    Set<String> afterDuties = new HashSet<>(signatures(after));
    int untouched = 0;
    for (Map.Entry<String, List<String>> duty : dutySlots(before).entrySet()) {
      if (duty.getValue().stream().noneMatch(gone::contains)) {
        untouched++;
        assertTrue(
            afterDuties.contains(duty.getKey()),
            () -> "不卷进取消的交路变了：" + duty.getKey() + "\n取消之后：" + afterDuties);
      }
    }
    assertEquals(before.duties().size() - 1, untouched, "只有一条交路卷进取消");
    assertTrue(
        cancelled.absorbable().size() + cancelled.unabsorbable().size()
            <= cancelled.conflictsAtTarget().size(),
        "取消之后剩下的冲突不比目标间隔多");
  }

  /** 同一份输入编两次，表、取消与报告逐字段相同。 */
  @Test
  void localCancellationIsDeterministic() {
    TimetableBuildResult first = build(new TimetableBuilder(), true, 10);
    TimetableBuildResult second = build(new TimetableBuilder(), true, 10);

    assertEquals(first.timetable(), second.timetable());
    assertEquals(first.droppedTrips(), second.droppedTrips());
    assertEquals(first.warnings(), second.warnings());
  }

  // ------------------------------------------------------------------ 夹具

  private static String slot(TimetableTrip trip) {
    return trip.routeId() + "@" + trip.departureSecondOfDay();
  }

  /** 交路签名（出入库、车库、时刻与各班的 route@发车）→ 它的班次。 */
  private static Map<String, List<String>> dutySlots(Timetable table) {
    Map<String, List<String>> out = new HashMap<>();
    for (VehicleDuty duty : table.duties()) {
      List<String> trips = tripsOf(table, duty);
      out.put(signature(duty, trips), trips);
    }
    return out;
  }

  private static List<String> signatures(Timetable table) {
    List<String> out = new ArrayList<>();
    for (VehicleDuty duty : table.duties()) {
      out.add(signature(duty, tripsOf(table, duty)));
    }
    return out;
  }

  private static List<String> tripsOf(Timetable table, VehicleDuty duty) {
    Map<UUID, TimetableTrip> byId = new HashMap<>();
    for (TimetableTrip trip : table.trips()) {
      byId.put(trip.id(), trip);
    }
    return duty.tripIds().stream()
        .map(byId::get)
        .map(TimetableBuilderLocalCancelTest::slot)
        .toList();
  }

  private static String signature(VehicleDuty duty, List<String> trips) {
    return duty.startDepotNodeId()
        + duty.createRouteId()
        + trips
        + duty.returnRouteId()
        + duty.endDepotNodeId()
        + "@"
        + duty.plannedStartSecondOfDay()
        + "-"
        + duty.plannedEndSecondOfDay();
  }

  /** 邻表 NB（A→B）在第 3 个 600 秒周期里每 60 秒一趟：任何相位都避不开，那个周期里与它相撞的班次只能取消。 */
  private NeighborTimetable neighbor() {
    UUID nb = TimetableTestFixtures.routeId("NB");
    RouteDefinition definition = TimetableTestFixtures.route("NB", List.of(A, B));
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
            TimetableConflictChecker.platformsOf(timing.stops(), stops, definition.waypoints()));
    List<TimetableConflictChecker.Movement> movements = new ArrayList<>();
    for (int k = 0; k < NEIGHBOR_RUNS; k++) {
      movements.add(
          new TimetableConflictChecker.Movement(
              String.format("NB-%03d", k + 1),
              nb,
              NEIGHBOR_FROM + NEIGHBOR_HEADWAY * k,
              Optional.of(NEIGHBOR)));
    }
    return new NeighborTimetable(
        UUID.nameUUIDFromBytes("L2-TT".getBytes(StandardCharsets.UTF_8)),
        NEIGHBOR,
        Instant.parse("2026-02-28T12:00:00Z"),
        ZONE,
        3,
        false,
        false,
        Map.of(nb, profile),
        movements,
        List.of(),
        List.of());
  }

  private TimetableBuildResult build(
      TimetableBuilder builder, boolean withNeighbor, int maxCancel) {
    List<TimetableBuilder.RouteInput> routes = new ArrayList<>();
    routes.add(operation("RA", List.of(A, B, C)));
    routes.add(operation("RB", List.of(C, B, A)));
    routes.add(leg("CRA", RouteOperationType.CREATE, List.of(DEPA, A), DEPA));
    routes.add(leg("RTA", RouteOperationType.RETURN, List.of(A, DEPA), DEPA));
    routes.add(leg("CRC", RouteOperationType.CREATE, List.of(DEPC, C), DEPC));
    routes.add(leg("RTC", RouteOperationType.RETURN, List.of(C, DEPC), DEPC));
    TimetableBuildOptions options =
        new TimetableBuildOptions(
                START,
                START + 3 * 3600,
                Duration.ofSeconds(INTERVAL),
                Duration.ofSeconds(0),
                new VehicleDutyPlanner.Limits(4, 7200, 60),
                "",
                ZONE,
                Duration.ofSeconds(30),
                true,
                Map.of(),
                TimetableBuildOptions.Repair.none())
            .withMaxCancelTrips(maxCancel);
    return builder.build(
        new TimetableBuilder.BuildInput(
            UUID.nameUUIDFromBytes("TT".getBytes(StandardCharsets.UTF_8)),
            UUID.nameUUIDFromBytes("CO".getBytes(StandardCharsets.UTF_8)),
            UUID.nameUUIDFromBytes("OP".getBytes(StandardCharsets.UTF_8)),
            UUID.nameUUIDFromBytes("LN".getBytes(StandardCharsets.UTF_8)),
            "TT1",
            "测试表",
            routes,
            graph,
            TimetableTestFixtures.perEdgeSpeedModel(),
            Optional.empty(),
            withNeighbor ? List.of(neighbor()) : List.of()),
        options,
        BUILT_AT);
  }

  private static TimetableBuilder.RouteInput operation(String code, List<String> nodes) {
    UUID id = TimetableTestFixtures.routeId(code);
    return new TimetableBuilder.RouteInput(
        id,
        code,
        1,
        TimetableTestFixtures.route(code, nodes),
        TimetableTestFixtures.stops(id, nodes.size(), 0),
        Optional.empty());
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
