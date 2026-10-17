package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

/**
 * 编表认加解编：主干 R（P→S→Z）在 S 切下车尾一段去跑支线 BR（S→T），回程主干 RR（Z→S→P）在 S 挂上回程支线 BB（T→S）带回来的那一段。
 *
 * <p>T 没有车库：支线上的车只能是主干解下来的。
 */
class TimetableBuilderCouplingTest {

  private static final Instant BUILT_AT = Instant.parse("2026-03-01T00:00:00Z");
  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final String DEP = "OP:D:DEP:1";
  private static final String P = "OP:S:P:1";
  private static final String S = "OP:S:S:1";
  private static final String Z = "OP:S:Z:1";
  private static final String T = "OP:S:T:1";

  private static RouteStop stop(UUID route, int seq, RouteStopPassType pass, String notes) {
    return new RouteStop(
        route,
        seq,
        Optional.empty(),
        Optional.empty(),
        Optional.of(20),
        pass,
        Optional.ofNullable(notes));
  }

  private static TimetableBuilder.RouteInput operation(
      String code, List<String> nodes, List<RouteStop> stops) {
    return new TimetableBuilder.RouteInput(
        TimetableTestFixtures.routeId(code),
        code,
        1,
        TimetableTestFixtures.route(code, nodes),
        stops,
        Optional.empty());
  }

  private static TimetableBuildResult build() {
    UUID r = TimetableTestFixtures.routeId("R");
    UUID rr = TimetableTestFixtures.routeId("RR");
    UUID br = TimetableTestFixtures.routeId("BR");
    UUID bb = TimetableTestFixtures.routeId("BB");
    UUID crt = TimetableTestFixtures.routeId("CRT");
    UUID ret = TimetableTestFixtures.routeId("RET");
    List<TimetableBuilder.RouteInput> routes =
        List.of(
            operation(
                "R",
                List.of(P, S, Z),
                List.of(
                    stop(r, 0, RouteStopPassType.STOP, null),
                    stop(r, 1, RouteStopPassType.STOP, "DECP REAR:1:GOTO:BR"),
                    stop(r, 2, RouteStopPassType.TERMINATE, null))),
            operation(
                "RR",
                List.of(Z, S, P),
                List.of(
                    stop(rr, 0, RouteStopPassType.STOP, null),
                    stop(rr, 1, RouteStopPassType.STOP, "COPL FROM:BB"),
                    stop(rr, 2, RouteStopPassType.TERMINATE, null))),
            operation(
                "BR",
                List.of(S, T),
                List.of(
                    stop(br, 0, RouteStopPassType.STOP, null),
                    stop(br, 1, RouteStopPassType.TERMINATE, null))),
            operation(
                "BB",
                List.of(T, S),
                List.of(
                    stop(bb, 0, RouteStopPassType.STOP, null),
                    stop(bb, 1, RouteStopPassType.TERMINATE, null))),
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
    CouplingInputs couplings =
        new CouplingInputs(
            Map.of(r, "A+B", rr, "A+B", br, "B", bb, "B"),
            Map.of("A+B", List.of(1, 1), "B", List.of(1)));
    TimetableBuildOptions options =
        new TimetableBuildOptions(
            5 * 3600,
            5 * 3600 + 2 * 3600,
            Duration.ofSeconds(600),
            Duration.ofSeconds(0),
            new VehicleDutyPlanner.Limits(12, 4 * 3600, TurnaroundTable.none()),
            "",
            ZONE);
    return new TimetableBuilder()
        .build(
            new TimetableBuilder.BuildInput(
                    UUID.nameUUIDFromBytes(
                        "COUPLING".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "TT1",
                    "加解编",
                    routes,
                    graph(),
                    TimetableTestFixtures.perEdgeSpeedModel(),
                    Optional.empty())
                .withCouplings(couplings),
            options,
            BUILT_AT);
  }

  private static RailGraph graph() {
    Map<String, NodeType> nodes = new LinkedHashMap<>();
    nodes.put(DEP, NodeType.DEPOT);
    nodes.put(P, NodeType.STATION);
    nodes.put(S, NodeType.STATION);
    nodes.put(Z, NodeType.STATION);
    nodes.put(T, NodeType.STATION);
    return TimetableTestFixtures.graph(
        nodes,
        List.of(
            new TimetableTestFixtures.Edge(DEP, P, 50, 10.0),
            new TimetableTestFixtures.Edge(P, S, 1200, 10.0),
            new TimetableTestFixtures.Edge(S, Z, 1200, 10.0),
            new TimetableTestFixtures.Edge(S, T, 600, 10.0)));
  }

  private static long tripsOf(Timetable timetable, String code) {
    return timetable.trips().stream()
        .filter(trip -> timetable.routePlan(trip.routeId()).orElseThrow().routeCode().equals(code))
        .count();
  }

  @Test
  void branchTripsAreDerivedFromTheTrunkAndRunByDetachedCars() {
    TimetableBuildResult result = build();

    assertTrue(result.success(), () -> result.warnings().toString());
    Timetable timetable = result.timetable().orElseThrow();
    long trunk = tripsOf(timetable, "R");
    long branch = tripsOf(timetable, "BR");
    assertTrue(trunk >= 5, () -> "主干应排满窗口，实际 " + trunk);
    assertEquals(trunk, branch, "每一班主干派生一班支线");
    for (VehicleDuty duty : timetable.duties()) {
      boolean runsBranch =
          duty.tripIds().stream()
              .map(id -> timetable.trips().stream().filter(t -> t.id().equals(id)).findFirst())
              .flatMap(Optional::stream)
              .anyMatch(
                  trip ->
                      timetable.routePlan(trip.routeId()).orElseThrow().routeCode().equals("BR"));
      if (runsBranch) {
        assertTrue(duty.coupling().startsBySplit(), () -> duty.dutyCode() + " 跑支线却不是解编生出的");
        assertTrue(duty.createRouteId().isEmpty(), "支线车不从车库出车");
      }
    }
  }

  @Test
  void returningBranchCarsRejoinTheTrunk() {
    Timetable timetable = build().timetable().orElseThrow();

    assertTrue(
        timetable.duties().stream().anyMatch(duty -> duty.coupling().endsByJoin()),
        "回程支线车应在 S 并回主干");
    assertFalse(
        timetable.duties().stream()
            .filter(duty -> duty.coupling().endsByJoin())
            .anyMatch(duty -> duty.returnRouteId().isPresent()),
        "以联挂结束的交路没有回库走行");
    assertTrue(tripsOf(timetable, "BB") > 0);
  }
}
