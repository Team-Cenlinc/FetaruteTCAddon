package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 严格模式取消是局部的：从派好的交路里拿掉一班，只动那一条交路，其余交路的车辆、班次与出入库逐条不变。
 *
 * <p>夹具是两端之间的往返：去程 A 每 600 秒一班（0、600、…），回程 B 每 600 秒一班（300、900、…），全程 240 秒、折返 60 秒， 每条交路 4
 * 班（A、B、A、B）。两端都有出库与回库线路（另有说明的除外）。
 */
class DutyWithdrawalTest {

  private static final UUID TIMETABLE = uuid("TT");
  private static final String NEAR = "OP:S:NEAR:1";
  private static final String FAR = "OP:S:FAR:1";
  private static final String DEPOT = "OP:D:DEP:1";
  private static final VehicleDutyPlanner.Limits LIMITS =
      new VehicleDutyPlanner.Limits(4, 7200, 60);
  private static final VehicleDutyPlanner.DepotLegSelector FIRST =
      (trip, candidates, limits) -> candidates.get(0);

  /** 什么都不拿：原样交回。 */
  @Test
  void nothingWithdrawnKeepsThePlan() {
    List<VehicleDutyPlanner.PlannedTrip> trips = shuttle(8);
    VehicleDutyPlanner.Result planned = plan(trips, legs(true));

    assertSame(
        planned,
        DutyWithdrawal.withdraw(TIMETABLE, planned, trips, Set.of(), legs(true), LIMITS, FIRST));
  }

  /**
   * 拿掉第一条交路的第 2 班（B@300）：车停在 FAR，下一班 A@600 从 NEAR 发，接不上——在 FAR 回库，A@600、B@900 另出一辆车接上。 其余交路逐条不变。
   */
  @Test
  void aWithdrawnTripSplitsOnlyItsOwnDuty() {
    List<VehicleDutyPlanner.PlannedTrip> trips = shuttle(16);
    VehicleDutyPlanner.Result planned = plan(trips, legs(true));
    UUID cancelled = uuid("B@300");

    VehicleDutyPlanner.Result after =
        DutyWithdrawal.withdraw(
            TIMETABLE, planned, trips, Set.of(cancelled), legs(true), LIMITS, FIRST);

    VehicleDuty affected = holding(planned, cancelled);
    assertEquals(unaffected(planned, affected), unaffected(after, affected), "别的交路的车辆、班次、出入库都不动");
    VehicleDuty head = holding(after, uuid("A@0"));
    assertEquals(List.of(uuid("A@0")), head.tripIds());
    assertEquals(affected.startDepotNodeId(), head.startDepotNodeId());
    assertEquals(affected.createRouteId(), head.createRouteId());
    assertEquals(Optional.of(uuid("RET-FAR")), head.returnRouteId(), "在 FAR 回库");
    VehicleDuty tail = holding(after, uuid("A@600"));
    assertEquals(List.of(uuid("A@600"), uuid("B@900")), tail.tripIds());
    assertEquals(Optional.of(uuid("CRT-NEAR")), tail.createRouteId(), "从车库另出一辆车接上");
    assertEquals(affected.returnRouteId(), tail.returnRouteId(), "末班没取消，沿用原来的回库");
    assertEquals(affected.plannedEndSecondOfDay(), tail.plannedEndSecondOfDay());
    assertEquals(
        List.of(VehicleDutyPlanner.UnassignedReason.CONFLICT_CANCELLED),
        after.unassigned().stream().map(VehicleDutyPlanner.UnassignedTrip::reason).toList());
  }

  /** 连拿两班（B@300、A@600）：车在 FAR 待命，下一班 B@900 就从 FAR 发；闲置上限之内等得到就留在原交路里，不拆。 */
  @Test
  void theVehicleWaitsAtTheTerminalForItsNextTripWithinTheIdleLimit() {
    VehicleDutyPlanner.Limits patient = new VehicleDutyPlanner.Limits(4, 7200, 60, 900);
    List<VehicleDutyPlanner.PlannedTrip> trips = shuttle(8);
    VehicleDutyPlanner.Result planned =
        VehicleDutyPlanner.plan(TIMETABLE, trips, legs(true), patient, Map.of(), Map.of(), FIRST);
    VehicleDuty affected = holding(planned, uuid("B@300"));

    VehicleDutyPlanner.Result after =
        DutyWithdrawal.withdraw(
            TIMETABLE,
            planned,
            trips,
            Set.of(uuid("B@300"), uuid("A@600")),
            legs(true),
            patient,
            FIRST);

    VehicleDuty kept = holding(after, uuid("A@0"));
    assertEquals(List.of(uuid("A@0"), uuid("B@900")), kept.tripIds());
    assertEquals(affected.createRouteId(), kept.createRouteId());
    assertEquals(affected.returnRouteId(), kept.returnRouteId());
    assertEquals(affected.plannedStartSecondOfDay(), kept.plannedStartSecondOfDay());
    assertEquals(affected.plannedEndSecondOfDay(), kept.plannedEndSecondOfDay());
    assertEquals(planned.duties().size(), after.duties().size());
  }

  /** 同样连拿两班，闲置上限 300 秒等不到 600 秒后的 B@900：在 FAR 回库，B@900 另出一辆车。 */
  @Test
  void beyondTheIdleLimitTheVehicleReturnsAndTheNextTripGetsANewOne() {
    List<VehicleDutyPlanner.PlannedTrip> trips = shuttle(8);
    VehicleDutyPlanner.Result planned = plan(trips, legs(true));

    VehicleDutyPlanner.Result after =
        DutyWithdrawal.withdraw(
            TIMETABLE,
            planned,
            trips,
            Set.of(uuid("B@300"), uuid("A@600")),
            legs(true),
            LIMITS,
            FIRST);

    assertEquals(List.of(uuid("A@0")), holding(after, uuid("A@0")).tripIds());
    VehicleDuty tail = holding(after, uuid("B@900"));
    assertEquals(List.of(uuid("B@900")), tail.tripIds());
    assertEquals(Optional.of(uuid("CRT-FAR")), tail.createRouteId());
  }

  /**
   * FAR 没有回库线路：拿掉 B@300 之后车停在 FAR 回不了库，按派车收尾的同一规则把 A@0 也退掉（记为回不了库），A@600、B@900 另出一辆车； 两班都计入取消清单。
   */
  @Test
  void aStrandedHeadIsTrimmedLikeTheDutyPlannerDoes() {
    List<VehicleDutyPlanner.PlannedTrip> trips = shuttle(8);
    VehicleDutyPlanner.Result planned = plan(trips, legs(false));

    VehicleDutyPlanner.Result after =
        DutyWithdrawal.withdraw(
            TIMETABLE, planned, trips, Set.of(uuid("B@300")), legs(false), LIMITS, FIRST);

    Map<UUID, VehicleDutyPlanner.UnassignedReason> reasons =
        after.unassigned().stream()
            .collect(
                Collectors.toMap(
                    VehicleDutyPlanner.UnassignedTrip::tripId,
                    VehicleDutyPlanner.UnassignedTrip::reason));
    assertEquals(
        VehicleDutyPlanner.UnassignedReason.CONFLICT_CANCELLED, reasons.get(uuid("B@300")));
    assertEquals(VehicleDutyPlanner.UnassignedReason.NO_RETURN_ACCESS, reasons.get(uuid("A@0")));
    assertEquals(List.of(uuid("A@600"), uuid("B@900")), holding(after, uuid("A@600")).tripIds());
    assertTrue(after.allDutiesReturnToStorage());
    for (VehicleDuty duty : after.duties()) {
      assertFalse(duty.tripIds().contains(uuid("A@0")));
    }
  }

  // ------------------------------------------------------------------ 夹具

  private static VehicleDutyPlanner.Result plan(
      List<VehicleDutyPlanner.PlannedTrip> trips, VehicleDutyPlanner.Legs legs) {
    return VehicleDutyPlanner.plan(TIMETABLE, trips, legs, LIMITS, Map.of(), Map.of(), FIRST);
  }

  /** {@code count} 班往返，A 在偶数位、B 在奇数位。 */
  private static List<VehicleDutyPlanner.PlannedTrip> shuttle(int count) {
    List<VehicleDutyPlanner.PlannedTrip> out = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      int departure = 300 * i;
      boolean outbound = i % 2 == 0;
      String code = (outbound ? "A@" : "B@") + departure;
      out.add(
          new VehicleDutyPlanner.PlannedTrip(
              uuid(code),
              uuid(outbound ? "A" : "B"),
              code,
              outbound ? NEAR : FAR,
              outbound ? FAR : NEAR,
              departure,
              240,
              false,
              false,
              ""));
    }
    return out;
  }

  /** 两端都能出库；{@code farReturns} 为假时 FAR 不能回库。 */
  private static VehicleDutyPlanner.Legs legs(boolean farReturns) {
    Map<String, VehicleDutyPlanner.Leg> creates = new HashMap<>();
    creates.put(NEAR, new VehicleDutyPlanner.Leg(uuid("CRT-NEAR"), "CRT-NEAR", DEPOT, 120));
    creates.put(FAR, new VehicleDutyPlanner.Leg(uuid("CRT-FAR"), "CRT-FAR", DEPOT, 120));
    Map<String, VehicleDutyPlanner.Leg> returns = new HashMap<>();
    returns.put(NEAR, new VehicleDutyPlanner.Leg(uuid("RET-NEAR"), "RET-NEAR", DEPOT, 120));
    if (farReturns) {
      returns.put(FAR, new VehicleDutyPlanner.Leg(uuid("RET-FAR"), "RET-FAR", DEPOT, 120));
    }
    return VehicleDutyPlanner.Legs.simple(creates, returns);
  }

  private static VehicleDuty holding(VehicleDutyPlanner.Result result, UUID trip) {
    return result.duties().stream()
        .filter(duty -> duty.tripIds().contains(trip))
        .findFirst()
        .orElseThrow();
  }

  /** 除了 {@code affected} 那一条之外的交路：车辆的出入库、班次与时刻（交路编号会因拆分重排，不比）。 */
  private static List<String> unaffected(VehicleDutyPlanner.Result result, VehicleDuty affected) {
    return result.duties().stream()
        .filter(duty -> duty.tripIds().stream().noneMatch(affected.tripIds()::contains))
        .map(
            duty ->
                duty.startDepotNodeId()
                    + duty.createRouteId()
                    + duty.tripIds()
                    + duty.returnRouteId()
                    + duty.endDepotNodeId()
                    + "@"
                    + duty.plannedStartSecondOfDay()
                    + "-"
                    + duty.plannedEndSecondOfDay()
                    + duty.closeReason())
        .toList();
  }

  private static UUID uuid(String name) {
    return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
  }
}
