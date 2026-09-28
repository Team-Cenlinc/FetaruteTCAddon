package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

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
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

/**
 * 正线折返点上的停留：车停在正线上会挡后车，往返对要锚在这一端，让折返只花最短折返时间，余量放到另一端的站台上。
 *
 * <p>形状照实服 MT-1：两股道尽头站 P（PPK）— M — 车站 O（OFL）— 正线路径点 T（OFL:MLU:2:004），车库挂在 O 旁。 P 两股道（不是容量 1
 * 的端点），端点串行不会把 P 上的发车钉到车上——与实服 PPK 相同。S 从 P 开到 T 终到折返，N 从 T 发车回 P。两个方向的站台组都是 O 与 P（T 不是车站），按方向键的字典序
 * N（O→P）是正向，旧规则把锚点放在 P，T 上拿的是周期余数： 2026-09-27 实服 213 次正线折返，表定停留中位 187 秒。
 */
class TimetableBuilderMainlineTurnbackTest {

  private static final Instant BUILT_AT = Instant.parse("2026-03-01T00:00:00Z");
  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final String DEP = "OP:D:DEP:1";
  private static final String O = "OP:S:O:1";
  private static final String T = "OP:O:X:1:004";
  private static final String M = "OP:S:M:1";
  private static final String P = "OP:S:P:1";
  private static final String P2 = "OP:S:P:2";
  private static final int TURNAROUND = 20;

  private final RailGraph graph = graph();

  /** 每次在 T 折返，从到达到接上下一趟发车只花最短折返时间（留几秒取整余量）。 */
  @Test
  void theMainlineTurnbackDwellIsJustTheTurnaround() {
    List<Integer> dwells = dwellsAtTurnback(build(400));

    assertFalse(dwells.isEmpty(), "交路里应当有 S 接 N 的正线折返");
    assertTrue(
        dwells.stream().allMatch(dwell -> dwell <= TURNAROUND + 5),
        () -> "正线折返停留应≈折返时间 " + TURNAROUND + "s，实际 " + dwells);
  }

  /** 锚点挪到正线那端之后，余量落在尽头站 P 上：P 上的停留比折返时间长，而不是正线上。 */
  @Test
  void theSlackMovesToThePlatformTerminal() {
    Timetable timetable = build(400);
    List<Integer> atTerminal = dwellsAt(timetable, "N", "S");

    assertFalse(atTerminal.isEmpty());
    assertTrue(atTerminal.stream().anyMatch(dwell -> dwell > TURNAROUND + 5), atTerminal::toString);
  }

  private static List<Integer> dwellsAtTurnback(Timetable timetable) {
    return dwellsAt(timetable, "S", "N");
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
    UUID s = TimetableTestFixtures.routeId("S");
    UUID n = TimetableTestFixtures.routeId("N");
    UUID crt = TimetableTestFixtures.routeId("CRT");
    UUID ret = TimetableTestFixtures.routeId("RET");
    List<TimetableBuilder.RouteInput> routes =
        List.of(
            new TimetableBuilder.RouteInput(
                s,
                "S",
                1,
                TimetableTestFixtures.route("S", List.of(P, M, O, T)),
                TimetableTestFixtures.stops(s, 4, TURNAROUND),
                Optional.empty()),
            new TimetableBuilder.RouteInput(
                n,
                "N",
                1,
                TimetableTestFixtures.route("N", List.of(T, O, M, P)),
                TimetableTestFixtures.stops(n, 4, TURNAROUND),
                Optional.empty()),
            new TimetableBuilder.RouteInput(
                crt,
                "CRT",
                RouteOperationType.CREATE,
                0,
                TimetableTestFixtures.route("CRT", List.of(DEP, P)),
                TimetableTestFixtures.createStops(crt, 2, DEP),
                Optional.empty()),
            new TimetableBuilder.RouteInput(
                ret,
                "RET",
                RouteOperationType.RETURN,
                0,
                TimetableTestFixtures.route("RET", List.of(P, DEP)),
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
                        "MAINLINE-TURNBACK".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "TT1",
                    "正线折返",
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
    nodes.put(O, NodeType.STATION);
    nodes.put(T, NodeType.WAYPOINT);
    nodes.put(M, NodeType.STATION);
    nodes.put(P, NodeType.STATION);
    nodes.put(P2, NodeType.STATION);
    return TimetableTestFixtures.graph(
        nodes,
        List.of(
            new TimetableTestFixtures.Edge(DEP, O, 50, 10.0),
            new TimetableTestFixtures.Edge(O, T, 40, 10.0),
            new TimetableTestFixtures.Edge(O, M, 300, 10.0),
            new TimetableTestFixtures.Edge(M, P, 300, 10.0),
            new TimetableTestFixtures.Edge(M, P2, 300, 10.0)));
  }
}
