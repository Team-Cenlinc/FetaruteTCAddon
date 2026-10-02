package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 正线折返优先减少占道等待，同时守住接续偏好、其他端点顺序与回库收口。 */
class VehicleDutyPlannerMainlineTurnbackTest {

  private static final UUID TIMETABLE = id("MAINLINE");
  private static final String MAINLINE = "SURC:OFL:MLU:2:004";
  private static final String HUB = "SURC:S:HUB:1";
  private static final String DEPOT = "SURC:D:OFL:1";
  private static final UUID EARLY_ROUTE = id("EARLY_ROUTE");
  private static final UUID LATE_ROUTE = id("LATE_ROUTE");
  private static final UUID NEXT_ROUTE = id("NEXT_ROUTE");
  private static final VehicleDutyPlanner.Limits LIMITS =
      new VehicleDutyPlanner.Limits(4, 7200, 20, 600);

  /** 两辆车相差一整个 300 秒间隔：接续偏好相同时取后车，前车的入正线班次必须取消。 */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void latestReadyVehicleTakesMainlineDeparture(boolean bothPreferred) {
    List<VehicleDutyPlanner.PlannedTrip> trips = tripsAt(MAINLINE, 100);
    Map<UUID, Set<UUID>> feeders =
        bothPreferred ? Map.of(NEXT_ROUTE, Set.of(EARLY_ROUTE, LATE_ROUTE)) : Map.of();

    VehicleDutyPlanner.Result result = plan(trips, legsAt(MAINLINE, false), LIMITS, feeders);

    assertEquals(List.of(id("LATE"), id("NEXT")), dutyHoldingNext(result).tripIds());
    VehicleDutyPlanner.PlannedTrip feeder = trips.get(1);
    assertEquals(
        20, trips.get(2).departureSeconds() - feeder.departureSeconds() - feeder.durationSeconds());
    assertEquals(1, result.duties().size());
    assertEquals(2, result.duties().stream().mapToInt(VehicleDuty::tripCount).sum());
    assertEquals(
        List.of(
            new VehicleDutyPlanner.UnassignedTrip(
                id("EARLY"), "EARLY", VehicleDutyPlanner.UnassignedReason.NO_RETURN_ACCESS)),
        result.unassigned());
    assertAllReturn(result);
  }

  /** 站台、车库与两类咽喉保持最早就绪；落选车沿已有 RETURN 线路回库，不取消它的上一班。 */
  @ParameterizedTest
  @ValueSource(
      strings = {"SURC:S:PPK:1", "SURC:D:OTHER:1", "SURC:S:PPK:1:001", "SURC:D:OTHER:1:001"})
  void otherEndpointsKeepEarliestReadyVehicle(String endpoint) {
    VehicleDutyPlanner.Result result =
        plan(tripsAt(endpoint, 100), legsAt(endpoint, true), LIMITS, Map.of());

    assertEquals(List.of(id("EARLY"), id("NEXT")), dutyHoldingNext(result).tripIds());
    assertEquals(2, result.duties().size());
    assertTrue(result.unassigned().isEmpty());
    assertEquals(3, result.duties().stream().mapToInt(VehicleDuty::tripCount).sum());
    assertAllReturn(result);
  }

  /** 正线上的偏好喂车即便先到，也必须压过后到但不匹配偏好的车。 */
  @Test
  void preferredFeederStillBeatsLatestReadyVehicle() {
    VehicleDutyPlanner.Result result =
        plan(
            tripsAt(MAINLINE, 100),
            legsAt(MAINLINE, false),
            LIMITS,
            Map.of(NEXT_ROUTE, Set.of(EARLY_ROUTE)));

    assertEquals(List.of(id("EARLY"), id("NEXT")), dutyHoldingNext(result).tripIds());
    assertEquals(
        List.of(id("LATE")),
        result.unassigned().stream().map(VehicleDutyPlanner.UnassignedTrip::tripId).toList());
    assertAllReturn(result);
  }

  /** 同时就绪时取较小 duty 序号，输入顺序颠倒也不改变完整结果。 */
  @Test
  void equalReadinessUsesDutySequenceDeterministically() {
    List<VehicleDutyPlanner.PlannedTrip> trips = tripsAt(MAINLINE, 400);
    VehicleDutyPlanner.Result result = plan(trips, legsAt(MAINLINE, false), LIMITS, Map.of());
    VehicleDutyPlanner.Result reversed =
        plan(
            List.of(trips.get(2), trips.get(1), trips.get(0)),
            legsAt(MAINLINE, false),
            LIMITS,
            Map.of());

    assertEquals(List.of(id("EARLY"), id("NEXT")), dutyHoldingNext(result).tripIds());
    assertEquals(result, reversed);
    assertAllReturn(result);
  }

  /** 就绪包含各 route 自己的折返时分，不能偷偷改为比较到达时间。 */
  @Test
  void readinessIncludesRouteSpecificTurnaround() {
    VehicleDutyPlanner.Limits limits =
        new VehicleDutyPlanner.Limits(
            4, 7200, TurnaroundTable.ofSeconds(Map.of(EARLY_ROUTE, 320, LATE_ROUTE, 10), 20), 600);
    VehicleDutyPlanner.Result result =
        plan(tripsAt(MAINLINE, 100), legsAt(MAINLINE, false), limits, Map.of());

    assertEquals(List.of(id("EARLY"), id("NEXT")), dutyHoldingNext(result).tripIds());
    assertAllReturn(result);
  }

  /** 两辆车从同一站出发到端点，后车 400 秒到达，唯一接班在 420 秒发车返回可回库的 HUB。 */
  private static List<VehicleDutyPlanner.PlannedTrip> tripsAt(String endpoint, int earlyDuration) {
    return List.of(
        trip("EARLY", EARLY_ROUTE, HUB, endpoint, 0, earlyDuration),
        trip("LATE", LATE_ROUTE, HUB, endpoint, 300, 100),
        trip("NEXT", NEXT_ROUTE, endpoint, HUB, 420, 100));
  }

  /** HUB 有出入库线路；只为非正线端点提供额外 RETURN。 */
  private static VehicleDutyPlanner.Legs legsAt(String endpoint, boolean canReturn) {
    VehicleDutyPlanner.Leg create = new VehicleDutyPlanner.Leg(id("CREATE"), "CREATE", DEPOT, 30);
    VehicleDutyPlanner.Leg back = new VehicleDutyPlanner.Leg(id("RETURN"), "RETURN", DEPOT, 30);
    return VehicleDutyPlanner.Legs.simple(
        Map.of(HUB, create), canReturn ? Map.of(HUB, back, endpoint, back) : Map.of(HUB, back));
  }

  private static VehicleDutyPlanner.Result plan(
      List<VehicleDutyPlanner.PlannedTrip> trips,
      VehicleDutyPlanner.Legs legs,
      VehicleDutyPlanner.Limits limits,
      Map<UUID, Set<UUID>> feeders) {
    return VehicleDutyPlanner.plan(TIMETABLE, trips, legs, limits, Map.of(), feeders);
  }

  private static VehicleDuty dutyHoldingNext(VehicleDutyPlanner.Result result) {
    return result.duties().stream()
        .filter(d -> d.tripIds().contains(id("NEXT")))
        .findFirst()
        .orElseThrow();
  }

  private static void assertAllReturn(VehicleDutyPlanner.Result result) {
    assertTrue(result.allDutiesReturnToStorage());
    for (VehicleDuty duty : result.duties()) {
      assertEquals(DEPOT, duty.endDepotNodeId());
      assertEquals(id("RETURN"), duty.returnRouteId().orElseThrow());
    }
  }

  private static VehicleDutyPlanner.PlannedTrip trip(
      String code, UUID route, String origin, String terminal, int departure, int duration) {
    return new VehicleDutyPlanner.PlannedTrip(
        id(code), route, code, origin, terminal, departure, duration, false, false, "MT");
  }

  private static UUID id(String code) {
    return UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8));
  }
}
