package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

/**
 * 端到端：多条 route 共用一个单股道端点——CHT 的抽象形态。
 *
 * <p>直链 {@code DEP – A:1 – A:2 – M – XJ – X}：A 两股道，M 一股道中间站，XJ 是 X 的进站路径点（WAYPOINT），X 单股道尽头。 RA
 * A:1→X、RB X→A:1 往返，出库 DEP→A:1，回库 A:1→DEP。间隔 60 s 时网格模型在 X 的岔线上必然撞（上一班还没出来、下一班已经进来）； 串行之后 X
 * 上不该再有任何冲突。
 */
class TimetableBuilderStubTerminalTest {

  private static final Instant BUILT_AT = Instant.parse("2026-03-01T00:00:00Z");
  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final String DEP = "OP:D:DEP:1";
  private static final String A1 = "OP:S:A:1";
  private static final String A2 = "OP:S:A:2";
  private static final String M = "OP:S:M:1";
  private static final String XJ = "OP:W:XJ:1";
  private static final String X = "OP:S:X:1";

  private final RailGraph graph =
      TimetableTestFixtures.chain(
          List.of(DEP, A1, A2, M, XJ, X),
          List.of(
              NodeType.DEPOT,
              NodeType.STATION,
              NodeType.STATION,
              NodeType.STATION,
              NodeType.WAYPOINT,
              NodeType.STATION),
          new int[] {50, 10, 100, 100, 50},
          new double[] {10.0, 10.0, 10.0, 10.0, 10.0});

  /** 串行后 X 上没有任何冲突；报告里有它的端点行；有班次偏离了网格。 */
  @Test
  void stubTerminalIsCleanAfterSerialization() {
    TimetableBuildResult result = build(60);

    assertTrue(result.success(), () -> result.warnings().toString());
    assertTrue(
        result.conflictsAtTarget().stream().noneMatch(c -> c.resource().contains("OP:S:X")),
        () -> result.conflictsAtTarget().toString());
    assertEquals(1, result.terminals().size());
    assertEquals("OP:S:X", result.terminals().get(0).group());
    assertTrue(result.terminals().get(0).visits() > 0);
    assertFalse(result.shifts().isEmpty(), "续班锚在车上，必有班次偏离网格");
    assertTrue(
        result.shifts().stream()
            .anyMatch(s -> s.reason() == TerminalSerializer.Shift.Reason.ANCHORED_TO_VEHICLE));
  }

  /** 两次 build 逐字段相等：实际时刻、车次号、主键、偏离清单、端点报告。 */
  @Test
  void serializedBuildIsDeterministic() {
    TimetableBuildResult first = build(60);
    TimetableBuildResult second = build(60);

    assertEquals(first.timetable().orElseThrow().trips(), second.timetable().orElseThrow().trips());
    assertEquals(
        first.timetable().orElseThrow().duties(), second.timetable().orElseThrow().duties());
    assertEquals(first.shifts(), second.shifts());
    assertEquals(first.terminals(), second.terminals());
  }

  /** 车次号按实际发车顺序：同 route 的 001 永远早于 002，主键由车次号派生。 */
  @Test
  void tripCodesFollowActualDepartureOrder() {
    Timetable timetable = build(60).timetable().orElseThrow();

    for (TimetableRoutePlan plan : timetable.routePlans()) {
      List<TimetableTrip> ofRoute =
          timetable.trips().stream().filter(t -> t.routeId().equals(plan.routeId())).toList();
      for (int i = 1; i < ofRoute.size(); i++) {
        assertTrue(
            ofRoute.get(i - 1).departureSecondOfDay() <= ofRoute.get(i).departureSecondOfDay(),
            "同 route 序号与实际发车同序");
        assertEquals(String.format("%s-%03d", plan.routeCode(), i + 1), ofRoute.get(i).tripCode());
      }
    }
    for (TimetableTrip trip : timetable.trips()) {
      assertEquals(
          TimetableTripNumbering.deterministicTripId(timetable.id(), trip.tripCode()), trip.id());
    }
  }

  /** 不变量 4 不受串行影响：每条交路都以回库收尾，回库票不早于末班实际到达 + 折返。 */
  @Test
  void everyDutyStillReturnsToStorageAfterSerialization() {
    TimetableBuildResult result = build(60);
    Timetable timetable = result.timetable().orElseThrow();

    assertTrue(result.allDutiesReturnToStorage());
    for (VehicleDuty duty : timetable.duties()) {
      TimetableTrip last =
          timetable.trips().stream()
              .filter(t -> t.id().equals(duty.tripIds().get(duty.tripIds().size() - 1)))
              .findFirst()
              .orElseThrow();
      int arrival =
          last.departureSecondOfDay()
              + timetable.routePlan(last.routeId()).orElseThrow().totalRunSeconds();
      assertTrue(duty.returnSecondOfDay() >= arrival + 60, duty.dutyCode());
    }
  }

  /** 份额由 SWRR 与派车决定，串行不增减班次：实际份额与班次数对得上。 */
  @Test
  void sharesMatchEmittedTrips() {
    TimetableBuildResult result = build(60);

    int reported = result.shares().stream().mapToInt(s -> s.assignedTrips()).sum();
    assertEquals(result.timetable().orElseThrow().trips().size(), reported);
  }

  private TimetableBuildResult build(int headwaySeconds) {
    UUID ra = TimetableTestFixtures.routeId("RA");
    UUID rb = TimetableTestFixtures.routeId("RB");
    UUID crt = TimetableTestFixtures.routeId("CRT");
    UUID ret = TimetableTestFixtures.routeId("RET");
    List<TimetableBuilder.RouteInput> routes =
        List.of(
            new TimetableBuilder.RouteInput(
                ra,
                "RA",
                1,
                TimetableTestFixtures.route("RA", List.of(A1, M, X)),
                TimetableTestFixtures.stops(ra, 3, 0),
                Optional.empty()),
            new TimetableBuilder.RouteInput(
                rb,
                "RB",
                1,
                TimetableTestFixtures.route("RB", List.of(X, M, A1)),
                TimetableTestFixtures.stops(rb, 3, 0),
                Optional.empty()),
            new TimetableBuilder.RouteInput(
                crt,
                "CRT",
                RouteOperationType.CREATE,
                0,
                TimetableTestFixtures.route("CRT", List.of(DEP, A1)),
                TimetableTestFixtures.createStops(crt, 2, DEP),
                Optional.empty()),
            new TimetableBuilder.RouteInput(
                ret,
                "RET",
                RouteOperationType.RETURN,
                0,
                TimetableTestFixtures.route("RET", List.of(A1, DEP)),
                TimetableTestFixtures.returnStops(ret, 2, DEP),
                Optional.empty()));
    TimetableBuildOptions options =
        new TimetableBuildOptions(
            5 * 3600,
            5 * 3600 + 1800,
            Duration.ofSeconds(headwaySeconds),
            Duration.ofSeconds(0),
            new VehicleDutyPlanner.Limits(4, 7200, 60),
            "",
            ZONE);
    return new TimetableBuilder()
        .build(
            new TimetableBuilder.BuildInput(
                UUID.nameUUIDFromBytes("STUB".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "TT1",
                "端点表",
                routes,
                graph,
                TimetableTestFixtures.perEdgeSpeedModel(),
                Optional.empty()),
            options,
            BUILT_AT);
  }
}
