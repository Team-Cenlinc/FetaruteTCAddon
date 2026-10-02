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
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

/**
 * 原地折返端：车到了只能等本端的下一班。除正线折返点外，<b>没有出入库线路的站台</b>同样如此（MT-3 速达 NTA↔PPK：NTA 是 WS 的终点，MT 在那里没有车库）。
 *
 * <p>形状：无库端点 A（单股道）— M — 有库端点 B（两股道，车库挂在 M 旁）。N 从 A 开到 B，O 从 B 开回 A。两个方向的站台组是 A 与 B，按方向键的字典序
 * N（A→B）是正向，旧规则把锚点放在 B、周期余量全堆在 A：A 上没有车库，车只能等下一班 N，余量超过闲置上限就接不上，N 一班都排不出来。
 */
class TimetableBuilderInPlaceTurnbackTest {

  private static final Instant BUILT_AT = Instant.parse("2026-03-01T00:00:00Z");
  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final String DEP = "OP:D:DEP:1";
  private static final String A = "OP:S:A:1";
  private static final String M = "OP:S:M:1";
  private static final String B = "OP:S:B:1";
  private static final String B2 = "OP:S:B:2";
  private static final String T = "OP:O:X:1:004";
  private static final int TURNAROUND = 20;

  private final RailGraph graph = graph();

  /** 无库端点上每次折返只花最短折返时间；余量落在有库的那一端。 */
  @Test
  void theDepotlessEndTurnsBackWithoutSlack() {
    Timetable timetable = build(600);

    List<Integer> atDepotless = dwellsAt(timetable, "O", "N");

    assertFalse(atDepotless.isEmpty(), "无库端点 A 上应当有 O 接 N 的折返");
    assertTrue(
        atDepotless.stream().allMatch(dwell -> dwell <= TURNAROUND + 5),
        () -> "无库端点上的停留应≈折返时间 " + TURNAROUND + "s，实际 " + atDepotless);
  }

  /** 旧规则下这条 N 一班都排不出来（余量堆在无库端点、超过闲置上限接不上），现在每班都在。 */
  @Test
  void everyTripOfTheDepotlessEndIsScheduled() {
    Timetable timetable = build(600);

    long fromDepotless =
        timetable.trips().stream()
            .filter(
                trip -> timetable.routePlan(trip.routeId()).orElseThrow().routeCode().equals("N"))
            .count();
    long fromDepot =
        timetable.trips().stream()
            .filter(
                trip -> timetable.routePlan(trip.routeId()).orElseThrow().routeCode().equals("O"))
            .count();

    assertTrue(fromDepotless >= 5, () -> "N 应排满窗口，实际 " + fromDepotless);
    assertEquals(fromDepot, fromDepotless, "往返对两个方向的班次数应一致");
  }

  /** 终点没有 RETURN 线路的运营 route 是原地折返；有 RETURN 线路的不是。 */
  @Test
  void aTerminalWithoutAReturnLegTurnsBackInPlace() {
    TimetableRoutePlan depotless = plan("N", A, B, RouteStopPassType.TERMINATE);
    TimetableRoutePlan withDepot = plan("O", B, A, RouteStopPassType.TERMINATE);
    VehicleDutyPlanner.Legs legs = legsReturningAt(A);

    assertEquals(
        Set.of(depotless.routeId()),
        TimetableBuilder.inPlaceTurnbackRoutes(List.of(depotless, withDepot), legs));
  }

  /** 正线折返点即使有 RETURN 线路也算原地折返（车停在正线上会挡后车）。 */
  @Test
  void aMainlineTurnbackIsInPlaceEvenWithAReturnLeg() {
    TimetableRoutePlan mainline = plan("S", A, T, RouteStopPassType.TERMINATE);

    assertEquals(
        Set.of(mainline.routeId()),
        TimetableBuilder.inPlaceTurnbackRoutes(List.of(mainline), legsReturningAt(T)));
  }

  /** 无库站台是原地折返端，但不是正线折返点：只有正线那种才禁止在远端多等。 */
  @Test
  void aDepotlessPlatformIsNotAMainlineTurnback() {
    TimetableRoutePlan depotless = plan("N", A, B, RouteStopPassType.TERMINATE);
    TimetableRoutePlan mainline = plan("S", A, T, RouteStopPassType.TERMINATE);

    assertEquals(
        Set.of(mainline.routeId()),
        TimetableBuilder.mainlineTurnbackRoutes(List.of(depotless, mainline)));
  }

  /** 末站不是 TERMINATE（以销毁收尾的回库形态）不算原地折返：车不用等下一班。 */
  @Test
  void aRouteThatEndsByDestroyingIsNotATurnback() {
    TimetableRoutePlan destroying = plan("D", A, B, RouteStopPassType.STOP);

    assertTrue(
        TimetableBuilder.inPlaceTurnbackRoutes(List.of(destroying), VehicleDutyPlanner.Legs.none())
            .isEmpty());
  }

  private static TimetableRoutePlan plan(
      String code, String origin, String terminal, RouteStopPassType last) {
    List<TimetableStop> stops =
        List.of(
            new TimetableStop(
                0, Optional.empty(), Optional.of(origin), 0, 20, RouteStopPassType.STOP),
            new TimetableStop(1, Optional.empty(), Optional.of(terminal), 60, 60, last));
    return new TimetableRoutePlan(
        TimetableTestFixtures.routeId(code),
        code,
        RouteOperationType.OPERATION,
        1,
        stops,
        origin,
        terminal,
        Optional.empty(),
        Optional.empty());
  }

  private static VehicleDutyPlanner.Legs legsReturningAt(String terminal) {
    return VehicleDutyPlanner.Legs.simple(
        Map.of(),
        Map.of(terminal, new VehicleDutyPlanner.Leg(UUID.randomUUID(), "RET", "OP:D:DEP:1", 30)));
  }

  /** 交路里 {@code arriving} 紧接 {@code departing} 时，前者到达到后者发车之间的停留。 */
  private static List<Integer> dwellsAt(Timetable timetable, String arriving, String departing) {
    Map<UUID, TimetableTrip> byId = new LinkedHashMap<>();
    timetable.trips().forEach(trip -> byId.put(trip.id(), trip));
    List<Integer> dwells = new ArrayList<>();
    for (VehicleDuty duty : timetable.duties()) {
      List<UUID> ids = duty.tripIds();
      for (int i = 0; i + 1 < ids.size(); i++) {
        TimetableTrip first = byId.get(ids.get(i));
        TimetableTrip next = byId.get(ids.get(i + 1));
        TimetableRoutePlan firstPlan = timetable.routePlan(first.routeId()).orElseThrow();
        TimetableRoutePlan nextPlan = timetable.routePlan(next.routeId()).orElseThrow();
        if (firstPlan.routeCode().equals(arriving) && nextPlan.routeCode().equals(departing)) {
          dwells.add(
              next.departureSecondOfDay()
                  - (first.departureSecondOfDay() + firstPlan.totalRunSeconds()));
        }
      }
    }
    return dwells;
  }

  private Timetable build(int headwaySeconds) {
    UUID n = TimetableTestFixtures.routeId("N");
    UUID o = TimetableTestFixtures.routeId("O");
    UUID crt = TimetableTestFixtures.routeId("CRT");
    UUID ret = TimetableTestFixtures.routeId("RET");
    List<TimetableBuilder.RouteInput> routes =
        List.of(
            new TimetableBuilder.RouteInput(
                n,
                "N",
                1,
                TimetableTestFixtures.route("N", List.of(A, M, B)),
                TimetableTestFixtures.stops(n, 3, TURNAROUND),
                Optional.empty()),
            new TimetableBuilder.RouteInput(
                o,
                "O",
                1,
                TimetableTestFixtures.route("O", List.of(B, M, A)),
                TimetableTestFixtures.stops(o, 3, TURNAROUND),
                Optional.empty()),
            new TimetableBuilder.RouteInput(
                crt,
                "CRT",
                RouteOperationType.CREATE,
                0,
                TimetableTestFixtures.route("CRT", List.of(DEP, B)),
                TimetableTestFixtures.createStops(crt, 2, DEP),
                Optional.empty()),
            new TimetableBuilder.RouteInput(
                ret,
                "RET",
                RouteOperationType.RETURN,
                0,
                TimetableTestFixtures.route("RET", List.of(B, DEP)),
                TimetableTestFixtures.returnStops(ret, 2, DEP),
                Optional.empty()));
    TimetableBuildOptions options =
        new TimetableBuildOptions(
            5 * 3600,
            5 * 3600 + 3600,
            Duration.ofSeconds(headwaySeconds),
            Duration.ofSeconds(0),
            new VehicleDutyPlanner.Limits(8, 7200, TurnaroundTable.none()),
            "",
            ZONE);
    TimetableBuildResult result =
        new TimetableBuilder()
            .build(
                new TimetableBuilder.BuildInput(
                    UUID.nameUUIDFromBytes(
                        "IN-PLACE-TURNBACK".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "TT1",
                    "原地折返",
                    routes,
                    graph,
                    TimetableTestFixtures.perEdgeSpeedModel(),
                    Optional.empty()),
                options,
                BUILT_AT);
    assertTrue(result.success(), () -> result.warnings().toString());
    return result.timetable().orElseThrow();
  }

  private static RailGraph graph() {
    Map<String, NodeType> nodes = new LinkedHashMap<>();
    nodes.put(DEP, NodeType.DEPOT);
    nodes.put(A, NodeType.STATION);
    nodes.put(M, NodeType.STATION);
    nodes.put(B, NodeType.STATION);
    nodes.put(B2, NodeType.STATION);
    return TimetableTestFixtures.graph(
        nodes,
        List.of(
            new TimetableTestFixtures.Edge(DEP, M, 50, 10.0),
            new TimetableTestFixtures.Edge(A, M, 300, 10.0),
            new TimetableTestFixtures.Edge(M, B, 300, 10.0),
            new TimetableTestFixtures.Edge(M, B2, 300, 10.0)));
  }
}
