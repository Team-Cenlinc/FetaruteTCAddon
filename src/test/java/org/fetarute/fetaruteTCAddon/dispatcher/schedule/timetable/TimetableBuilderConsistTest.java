package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.RunTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

/**
 * 区分车型的编表：每班按自己车型的时分、开交路按班次份额选车型、表上车次与交路挂真实 route、车型记在交路上。
 *
 * <p>路网 {@code DEP - A - B - C}（50 / 100 / 100 格，全线 10 格/秒）；运营 route A→B→C，出库 DEP→A、回库 C→DEP。 6
 * 节按逐边限速跑（A→C 20 s），8 节慢一半（30 s），车长 6 节 60 格、8 节 80 格。
 */
class TimetableBuilderConsistTest {

  private static final Instant BUILT_AT = Instant.parse("2026-03-01T00:00:00Z");
  private static final UUID TIMETABLE = UUID.randomUUID();
  private static final UUID COMPANY = UUID.randomUUID();
  private static final UUID OPERATOR = UUID.randomUUID();
  private static final UUID LINE = UUID.randomUUID();
  private static final String DEP = "OP:D:DEP:1";
  private static final String A = "OP:S:A:1";
  private static final String B = "OP:S:B:1";
  private static final String C = "OP:S:C:1";
  private static final UUID RA = TimetableTestFixtures.routeId("RA");
  private static final UUID CRT = TimetableTestFixtures.routeId("CRT");
  private static final UUID RET = TimetableTestFixtures.routeId("RET");

  private static RunTimeModel scaled(double factor) {
    return RunTimeModel.perEdge(
        (graph, edge, from, to) ->
            edge == null || edge.baseSpeedLimit() <= 0.0
                ? Optional.empty()
                : Optional.of(
                    Duration.ofMillis(
                        Math.round(edge.lengthBlocks() / edge.baseSpeedLimit() * factor * 1000))));
  }

  private static ConsistFleet fleet() {
    return new ConsistFleet(
        Map.of(
            "m6", new ConsistFleet.Consist("m6", "SH_A6", scaled(1.0), 60.0),
            "m8", new ConsistFleet.Consist("m8", "SH_A8", scaled(1.5), 80.0)),
        Map.of(RA, List.of(new ConsistFleet.Share("m6", 3), new ConsistFleet.Share("m8", 1))));
  }

  private static TimetableBuildResult build(ConsistFleet fleet) {
    return build(
        fleet,
        TimetableTestFixtures.chain(
            List.of(DEP, A, B, C), new int[] {50, 100, 100}, new double[] {10.0, 10.0, 10.0}));
  }

  private static TimetableBuildResult build(ConsistFleet fleet, RailGraph graph) {
    List<TimetableBuilder.RouteInput> routes =
        List.of(
            new TimetableBuilder.RouteInput(
                RA,
                "RA",
                1,
                TimetableTestFixtures.route("RA", List.of(A, B, C)),
                TimetableTestFixtures.stops(RA, 3, 0),
                Optional.empty()),
            new TimetableBuilder.RouteInput(
                CRT,
                "CRT",
                RouteOperationType.CREATE,
                0,
                TimetableTestFixtures.route("CRT", List.of(DEP, A)),
                TimetableTestFixtures.createStops(CRT, 2, DEP),
                Optional.empty()),
            new TimetableBuilder.RouteInput(
                RET,
                "RET",
                RouteOperationType.RETURN,
                0,
                TimetableTestFixtures.route("RET", List.of(C, DEP)),
                TimetableTestFixtures.returnStops(RET, 2, DEP),
                Optional.empty()));
    TimetableBuilder.BuildInput input =
        new TimetableBuilder.BuildInput(
                TIMETABLE,
                COMPANY,
                OPERATOR,
                LINE,
                "TT1",
                "测试表",
                routes,
                graph,
                TimetableTestFixtures.perEdgeSpeedModel(),
                Optional.empty())
            .withFleet(fleet);
    TimetableBuildOptions options =
        new TimetableBuildOptions(
            5 * 3600,
            7 * 3600,
            Duration.ofSeconds(600),
            Duration.ofSeconds(20),
            VehicleDutyPlanner.Limits.defaults(),
            "",
            ZoneId.of("UTC"));
    return new TimetableBuilder().build(input, options, BUILT_AT);
  }

  @Test
  void dutiesCarryTheirConsistAndTheShareFollowsThePlan() {
    TimetableBuildResult result = build(fleet());
    Timetable table = result.timetable().orElseThrow();

    Map<String, Integer> trips = new HashMap<>();
    for (TimetableTrip trip : table.trips()) {
      assertEquals(RA, trip.routeId(), "落库的车次挂真实 route，不挂车型变体");
      trips.merge(table.consistOf(trip).orElseThrow(), 1, Integer::sum);
    }
    int m6 = trips.getOrDefault("m6", 0);
    int m8 = trips.getOrDefault("m8", 0);
    assertTrue(m6 + m8 > 4);
    assertTrue(Math.abs(m6 - 3 * m8) <= 3, () -> "3:1 份额，实际 m6=" + m6 + " m8=" + m8);
    for (VehicleDuty duty : table.duties()) {
      assertTrue(duty.consist().isPresent());
      assertEquals(Optional.of(CRT), duty.createRouteId(), "出入库段同样折回真实 route");
      assertEquals(Optional.of(RET), duty.returnRouteId());
    }
  }

  @Test
  void eachConsistKeepsItsOwnTimingAndTheBaseIsTheSlowest() {
    Timetable table = build(fleet()).timetable().orElseThrow();

    assertEquals(30, table.routePlan(RA).orElseThrow().totalRunSeconds(), "基础计划取最慢的 8 节");
    assertEquals(20, table.routePlan(RA, Optional.of("m6")).orElseThrow().totalRunSeconds());
    assertEquals(30, table.routePlan(RA, Optional.of("m8")).orElseThrow().totalRunSeconds());
    TimetableRoutePlan.ConsistVariant eight =
        table.routePlan(RA, Optional.of("m8")).orElseThrow().consist().orElseThrow();
    assertEquals(RA, eight.baseRouteId());
    assertEquals(20.0, eight.tailBlocks(), 1e-9, "车尾只多算比最短车型长出来的 20 格");
    assertEquals(List.of(CRT, RA, RET), table.routeIds(), "同一条 route 的几份计划只算一条");
  }

  @Test
  void dutyEndsAfterItsOwnConsistsRun() {
    Timetable table = build(fleet()).timetable().orElseThrow();
    for (VehicleDuty duty : table.duties()) {
      TimetableTrip only = table.trip(duty.tripIds().get(duty.tripIds().size() - 1)).orElseThrow();
      int run = table.routePlan(RA, duty.consist()).orElseThrow().totalRunSeconds();
      int arrival = only.departureSecondOfDay() + run;
      assertTrue(duty.returnSecondOfDay() >= arrival, () -> duty.dutyCode() + " 回库不能早于它自己的车型到达终点");
      assertTrue(
          duty.returnSecondOfDay() - arrival < 30, () -> duty.dutyCode() + " 回库按自己的车型算，不按最慢的");
    }
  }

  @Test
  void reportsConsistsAndIsDeterministic() {
    TimetableBuildResult first = build(fleet());
    TimetableBuildResult second = build(fleet());

    assertEquals(first.timetable(), second.timetable());
    String notes = String.join("\n", first.consistNotes());
    assertTrue(notes.contains("车型：SH_A6 车长 60.0 格；SH_A8 车长 80.0 格（车尾多算 20.0 格）"), notes);
    assertTrue(notes.contains("各车型时分：RA m6 20 s、m8 30 s（往返锚定按 m8）"), notes);
    assertTrue(notes.contains("车型配比：RA m6 目标 75%"), notes);
    assertTrue(notes.contains("按车型：m6 交路"), notes);
  }

  @Test
  void withoutConsistsNothingChanges() {
    TimetableBuildResult plain = build(ConsistFleet.none());
    Timetable table = plain.timetable().orElseThrow();
    assertTrue(plain.consistNotes().isEmpty());
    assertTrue(table.duties().stream().allMatch(duty -> duty.consist().isEmpty()));
    assertTrue(table.routePlans().stream().allMatch(plan -> plan.consist().isEmpty()));
  }

  @Test
  void routeBoundToAnUnusablePlanIsInfeasible() {
    ConsistFleet fleet =
        new ConsistFleet(
            Map.of("m6", new ConsistFleet.Consist("m6", "SH_A6", scaled(1.0), 60.0)),
            Map.of(RA, List.of(new ConsistFleet.Share("x9", 1))));
    assertEquals(Optional.of("编组方案里没有可用的车型"), fleet.blockedReason(RA));
    assertTrue(fleet.sharesFor(RA).isEmpty());

    TimetableBuildResult result = build(fleet);
    assertTrue(result.timetable().isEmpty(), "不能退回不限车型");
    assertTrue(
        result.infeasibleRoutes().stream()
            .anyMatch(route -> route.routeCode().equals("RA") && route.reason().contains("编组方案")),
        () -> result.infeasibleRoutes().toString());
  }

  @Test
  void jointBuildKeepsConsistsWithinTheirLine() {
    UUID rb = TimetableTestFixtures.routeId("RB");
    UUID rc = TimetableTestFixtures.routeId("RC");
    UUID crtA = TimetableTestFixtures.routeId("CRTA");
    ConsistFleet.Consist m6 = new ConsistFleet.Consist("m6", "SH_A6", scaled(1.0), 60.0);
    ConsistFleet.Consist m8 = new ConsistFleet.Consist("m8", "SH_A8", scaled(1.5), 80.0);
    TimetableSetBuilder.Member a =
        new TimetableSetBuilder.Member(
            memberInput(UUID.randomUUID())
                .withFleet(
                    new ConsistFleet(
                        Map.of("m6", m6), Map.of(RA, List.of(new ConsistFleet.Share("m6", 1))))),
            "A",
            "A",
            java.util.Set.of(RA, crtA));
    TimetableSetBuilder.Member b =
        new TimetableSetBuilder.Member(
            memberInput(UUID.randomUUID()), "B", "B", java.util.Set.of(rb));
    TimetableSetBuilder.Member c =
        new TimetableSetBuilder.Member(
            memberInput(UUID.randomUUID())
                .withFleet(
                    new ConsistFleet(
                        Map.of("m8", m8), Map.of(rc, List.of(new ConsistFleet.Share("m8", 1))))),
            "C",
            "C",
            java.util.Set.of(rc));

    ConsistFleet merged = TimetableSetBuilder.mergedFleet(List.of(a, b, c));
    assertTrue(merged.sharesFor(rb).isEmpty(), "没有编组方案的线不区分车型");
    assertEquals(
        List.of(new ConsistFleet.Share("m6", 1)), merged.sharesFor(crtA), "没绑方案的 route 只许本线的车型");
    assertEquals(List.of(new ConsistFleet.Share("m8", 1)), merged.sharesFor(rc));
  }

  @Test
  void warnsWhenOnlyTheLongerConsistFoulsASwitchAtTheTerminal() {
    // C 前 70 格是道岔：6 节（60 格）停在 C 压不到，8 节（80 格）压得到
    String sw = "OP:W:SW:1";
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of(DEP, A, B, sw, C),
            List.of(
                NodeType.STATION,
                NodeType.STATION,
                NodeType.STATION,
                NodeType.SWITCHER,
                NodeType.STATION),
            new int[] {50, 100, 30, 70},
            new double[] {10.0, 10.0, 10.0, 10.0});

    List<String> warnings = build(fleet(), graph).warnings();
    assertTrue(
        warnings.stream().anyMatch(w -> w.contains("车型 m8 在 RA 终点") && w.contains(sw)),
        warnings::toString);
    assertTrue(warnings.stream().noneMatch(w -> w.contains("车型 m6 在")), warnings::toString);
  }

  private static TimetableBuilder.BuildInput memberInput(UUID lineId) {
    return new TimetableBuilder.BuildInput(
        UUID.randomUUID(),
        COMPANY,
        OPERATOR,
        lineId,
        "TT",
        "测试表",
        List.of(),
        null,
        TimetableTestFixtures.perEdgeSpeedModel(),
        Optional.empty());
  }
}
