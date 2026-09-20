package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 每辆车都有有限的交路，并且一定回库——而且是沿着一条<b>具体的</b>回库线路回库。
 *
 * <p>这一组用例的对手是这样一种实现：只要下一班恰好接得上就继续复用。它在任何有限长度的测试里都"看起来没问题"，
 * 因为总能跑完；真正的问题是它<b>证明不了</b>终止性。因此这里刻意构造最有利于无限接班的场景—— 环线（终点即起点，任意两班都接得上）、班次密集、窗口很长——再断言边界仍然成立。
 *
 * <p>第二组用例守的是出库/回库途径：没有 CREATE 线路的起点开不了 duty，没有 RETURN 线路的终点收不了 duty， 排不进去的班次必须被如实上报而不是硬塞。
 */
class VehicleDutyPlannerTest {

  private static final UUID TIMETABLE = UUID.randomUUID();
  private static final String HUB = "OP:S:HUB:1";
  private static final String FAR = "OP:S:FAR:1";
  private static final String DEPOT = "OP:D:DEP:1";
  private static final UUID CREATE_ROUTE = id("CREATE");
  private static final UUID RETURN_ROUTE = id("RETURN");
  private static final int CREATE_RUN = 240;
  private static final int RETURN_RUN = 300;

  /** 环线 + 密集班次：每个 duty 仍必须被硬上限夹住，并以回库收尾。 */
  @Test
  void loopRouteCannotChainForever() {
    VehicleDutyPlanner.Limits limits = new VehicleDutyPlanner.Limits(4, 7200, 180);
    // 2000 趟环线车，每趟 600 秒，间隔 300 秒——每一趟的终点都正好是下一趟的起点。
    List<VehicleDutyPlanner.PlannedTrip> trips = loopTrips(2000, 300, 600);

    VehicleDutyPlanner.Result result = VehicleDutyPlanner.plan(TIMETABLE, trips, hubLegs(), limits);

    assertFalse(result.duties().isEmpty());
    for (VehicleDuty duty : result.duties()) {
      assertTrue(
          duty.tripCount() <= limits.maxTripsPerDuty(),
          () -> duty.dutyCode() + " 班次数 " + duty.tripCount() + " 越过上限");
      assertTrue(
          duty.plannedDurationSeconds() <= limits.maxDutyDurationSeconds(),
          () -> duty.dutyCode() + " 在线时长 " + duty.plannedDurationSeconds() + " 越过上限");
      assertFalse(duty.endDepotNodeId().isBlank(), () -> duty.dutyCode() + " 没有回库端点");
    }
    assertTrue(result.allDutiesReturnToStorage());
    assertTrue(result.unassigned().isEmpty(), "环线两端都有走行线路，不该有排不进去的班次");
    assertEquals(4, result.maxTripsInAnyDuty(), "上限就是 4，应当真的用满");
  }

  /** 车池：同池才能接班——多线联编时每条线一个车池，一辆车不跨线。位置、时间都接得上的两班，池不同就是两条交路。 */
  @Test
  void differentPoolsNeverShareADuty() {
    VehicleDutyPlanner.Limits limits = VehicleDutyPlanner.Limits.defaults();
    List<VehicleDutyPlanner.PlannedTrip> samePool =
        List.of(
            new VehicleDutyPlanner.PlannedTrip(
                id("T1"), "T1", HUB, HUB, 0, 600, false, false, "WS"),
            new VehicleDutyPlanner.PlannedTrip(
                id("T2"), "T2", HUB, HUB, 900, 600, false, false, "WS"));
    List<VehicleDutyPlanner.PlannedTrip> twoPools =
        List.of(
            new VehicleDutyPlanner.PlannedTrip(
                id("T1"), "T1", HUB, HUB, 0, 600, false, false, "WS"),
            new VehicleDutyPlanner.PlannedTrip(
                id("T2"), "T2", HUB, HUB, 900, 600, false, false, "MT"));

    assertEquals(
        1, VehicleDutyPlanner.plan(TIMETABLE, samePool, hubLegs(), limits).duties().size());
    VehicleDutyPlanner.Result split =
        VehicleDutyPlanner.plan(TIMETABLE, twoPools, hubLegs(), limits);
    assertEquals(2, split.duties().size());
    assertTrue(split.unassigned().isEmpty());
  }

  /** 每一趟车都恰好属于且只属于一个 duty：没有孤儿班次，也没有被两辆车同时承担的班次。 */
  @Test
  void everyTripBelongsToExactlyOneDuty() {
    List<VehicleDutyPlanner.PlannedTrip> trips = loopTrips(500, 300, 600);

    VehicleDutyPlanner.Result result =
        VehicleDutyPlanner.plan(TIMETABLE, trips, hubLegs(), VehicleDutyPlanner.Limits.defaults());

    Set<UUID> seen = new HashSet<>();
    int total = 0;
    for (VehicleDuty duty : result.duties()) {
      for (UUID tripId : duty.tripIds()) {
        assertTrue(seen.add(tripId), () -> "班次 " + tripId + " 被两个 duty 同时承担");
        total++;
      }
    }
    assertEquals(trips.size(), total, "有班次没有被任何 duty 承担");
  }

  /** duty 的两端是车库，而不是首末站；两端各挂一条具体的走行线路。 */
  @Test
  void dutyStartsAndEndsAtStorageViaConcreteRoutes() {
    List<VehicleDutyPlanner.PlannedTrip> trips = loopTrips(20, 300, 600);

    VehicleDutyPlanner.Result result =
        VehicleDutyPlanner.plan(TIMETABLE, trips, hubLegs(), VehicleDutyPlanner.Limits.defaults());

    for (VehicleDuty duty : result.duties()) {
      assertEquals(DEPOT, duty.startDepotNodeId());
      assertEquals(DEPOT, duty.endDepotNodeId());
      assertEquals(Optional.of(CREATE_ROUTE), duty.createRouteId());
      assertEquals(Optional.of(RETURN_ROUTE), duty.returnRouteId());
    }
  }

  /**
   * 出库要提前、回库要滞后，两段时分都来自走行线路而不是常数。
   *
   * <p>出库票 = 首班发车 − 车库到首站走行 − 就绪时间；回库票 = 末班到达 + 折返；到库 = 回库票 + 回库走行。
   */
  @Test
  void legTimesComeFromTheRoutesNotFromConstants() {
    VehicleDutyPlanner.Limits limits = new VehicleDutyPlanner.Limits(1, 7200, 180);
    List<VehicleDutyPlanner.PlannedTrip> trips = loopTrips(1, 300, 600);

    VehicleDuty duty = VehicleDutyPlanner.plan(TIMETABLE, trips, hubLegs(), limits).duties().get(0);

    assertEquals(0 - CREATE_RUN - 180, duty.plannedStartSecondOfDay(), "出库票提前走行 + 就绪");
    assertEquals(600 + 180, duty.returnSecondOfDay(), "回库票在末班到达 + 折返发出");
    assertEquals(600 + 180 + RETURN_RUN, duty.plannedEndSecondOfDay(), "到库 = 回库票 + 回库走行");
    assertEquals(VehicleDuty.CloseReason.MAX_TRIPS, duty.closeReason());
  }

  /** 班次少于上限时，duty 由计划窗口结束封口——同样必须回库。 */
  @Test
  void horizonEndClosesRemainingDuties() {
    List<VehicleDutyPlanner.PlannedTrip> trips = loopTrips(2, 300, 600);

    VehicleDutyPlanner.Result result =
        VehicleDutyPlanner.plan(TIMETABLE, trips, hubLegs(), VehicleDutyPlanner.Limits.defaults());

    assertTrue(
        result.duties().stream()
            .anyMatch(duty -> duty.closeReason() == VehicleDuty.CloseReason.HORIZON_END));
    assertTrue(result.allDutiesReturnToStorage());
  }

  /**
   * 在线时长上限单独也能封口：把班次上限放得很大，靠时长把 duty 夹住。
   *
   * <p>这里用"全程时分 = 发车间隔"的班次序列，使同一辆车能连续接班，从而让时长上限成为唯一的约束。 上限覆盖整个 duty，包含出库与回库走行。
   */
  @Test
  void durationLimitAloneBoundsTheDuty() {
    VehicleDutyPlanner.Limits limits = new VehicleDutyPlanner.Limits(1000, 2400, 60);
    List<VehicleDutyPlanner.PlannedTrip> trips = loopTrips(200, 300, 300);

    VehicleDutyPlanner.Result result = VehicleDutyPlanner.plan(TIMETABLE, trips, hubLegs(), limits);

    assertTrue(
        result.duties().stream()
            .anyMatch(duty -> duty.closeReason() == VehicleDuty.CloseReason.MAX_DURATION),
        "应当存在被时长上限封口的 duty");
    for (VehicleDuty duty : result.duties()) {
      assertTrue(duty.plannedDurationSeconds() <= limits.maxDutyDurationSeconds());
    }
    assertTrue(result.unassigned().isEmpty());
  }

  /**
   * "不限制"这个取值不存在。
   *
   * <p>把上限写成 0 或负数时会被夹回默认值，而不是被理解成无限——允许无限就等于允许一辆车永远不回库。
   */
  @Test
  void nonPositiveLimitsFallBackToDefaultsInsteadOfMeaningUnlimited() {
    VehicleDutyPlanner.Limits limits = new VehicleDutyPlanner.Limits(0, -1, -5);

    assertEquals(VehicleDutyPlanner.Limits.DEFAULT_MAX_TRIPS, limits.maxTripsPerDuty());
    assertEquals(
        VehicleDutyPlanner.Limits.DEFAULT_MAX_DURATION_SECONDS, limits.maxDutyDurationSeconds());
    assertEquals(0, limits.turnaroundSeconds());
  }

  /** 折返时间不够时不会把班次塞进同一个 duty，而是另开一辆车。 */
  @Test
  void turnaroundIsRespectedWhenChaining() {
    // 每趟 600 秒、间隔 601 秒：上一班刚结束下一班就要发车，折返 180 秒装不下；隔一班才接得上。
    List<VehicleDutyPlanner.PlannedTrip> trips = loopTrips(6, 601, 600);

    VehicleDutyPlanner.Result result =
        VehicleDutyPlanner.plan(
            TIMETABLE, trips, hubLegs(), new VehicleDutyPlanner.Limits(4, 7200, 180));

    assertEquals(2, result.duties().size(), "两辆车隔班轮换，而不是一辆车瞬间掉头");
    Map<UUID, VehicleDutyPlanner.PlannedTrip> byId = new java.util.HashMap<>();
    trips.forEach(trip -> byId.put(trip.tripId(), trip));
    for (VehicleDuty duty : result.duties()) {
      for (int i = 1; i < duty.tripIds().size(); i++) {
        VehicleDutyPlanner.PlannedTrip previous = byId.get(duty.tripIds().get(i - 1));
        VehicleDutyPlanner.PlannedTrip next = byId.get(duty.tripIds().get(i));
        assertTrue(
            next.departureSeconds()
                >= previous.departureSeconds() + previous.durationSeconds() + 180,
            () -> duty.dutyCode() + " 两班之间折返不足");
      }
    }
    assertTrue(result.allDutiesReturnToStorage());
  }

  /** 同样输入两次规划结果一致，包括 duty 主键。 */
  @Test
  void planningIsDeterministic() {
    List<VehicleDutyPlanner.PlannedTrip> trips = loopTrips(50, 300, 600);

    VehicleDutyPlanner.Result first =
        VehicleDutyPlanner.plan(TIMETABLE, trips, hubLegs(), VehicleDutyPlanner.Limits.defaults());
    VehicleDutyPlanner.Result second =
        VehicleDutyPlanner.plan(TIMETABLE, trips, hubLegs(), VehicleDutyPlanner.Limits.defaults());

    assertEquals(first.duties(), second.duties());
  }

  /** 空输入不产生 duty，也不抛异常。 */
  @Test
  void emptyInputProducesNoDuties() {
    VehicleDutyPlanner.Result result =
        VehicleDutyPlanner.plan(
            TIMETABLE, List.of(), hubLegs(), VehicleDutyPlanner.Limits.defaults());

    assertTrue(result.duties().isEmpty());
    assertEquals(0, result.spawnedVehicles());
    assertTrue(result.allDutiesReturnToStorage(), "空集合上这条性质平凡成立");
  }

  // ------------------------------------------------------------ 出库/回库途径

  /**
   * 起点没有 CREATE 线路、又没有接得上的待命车时，班次不能凭空开出来。
   *
   * <p>HUB↔FAR 往返，只有 HUB 有出库线路：一开始从 FAR 发的班次没有车可用，必须被上报； 等第一辆车跑到 FAR 之后，FAR 发的班次才有车接。
   */
  @Test
  void tripsWithoutCreateAccessAreReportedNotInvented() {
    List<VehicleDutyPlanner.PlannedTrip> trips = new ArrayList<>();
    // 每 300 秒一班：偶数班 HUB→FAR，奇数班 FAR→HUB；全程 600 秒。
    for (int i = 0; i < 12; i++) {
      boolean outbound = i % 2 == 0;
      trips.add(trip("T" + i, outbound ? HUB : FAR, outbound ? FAR : HUB, i * 300, 600));
    }

    VehicleDutyPlanner.Result result =
        VehicleDutyPlanner.plan(
            TIMETABLE, trips, hubLegs(), new VehicleDutyPlanner.Limits(10, 7200, 60));

    List<VehicleDutyPlanner.UnassignedTrip> noCreate =
        result.unassigned().stream()
            .filter(u -> u.reason() == VehicleDutyPlanner.UnassignedReason.NO_CREATE_ACCESS)
            .toList();
    assertFalse(noCreate.isEmpty(), "最早从 FAR 发的班次没有车，必须被上报");
    assertTrue(
        noCreate.stream().allMatch(u -> u.tripCode().matches("T(1|3)")),
        () -> "只有第一辆车到达 FAR 之前的班次才该被取消: " + noCreate);
    Set<UUID> assigned = new HashSet<>();
    result.duties().forEach(duty -> assigned.addAll(duty.tripIds()));
    assertTrue(assigned.contains(id("T5")), "第一辆车 T0 600s 到 FAR、660s 就绪，T5（1500s）接得上");
  }

  /**
   * 终点没有 RETURN 线路的地方不能封口。
   *
   * <p>只有 HUB 能回库：窗口结束时停在 FAR 的车，尾段班次退掉直到它停在 HUB，退掉的班次上报为 NO_RETURN_ACCESS。
   */
  @Test
  void dutiesOnlyCloseWhereAReturnRouteExists() {
    List<VehicleDutyPlanner.PlannedTrip> trips = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      boolean outbound = i % 2 == 0;
      trips.add(trip("T" + i, outbound ? HUB : FAR, outbound ? FAR : HUB, i * 900, 600));
    }

    VehicleDutyPlanner.Result result =
        VehicleDutyPlanner.plan(
            TIMETABLE, trips, hubLegs(), new VehicleDutyPlanner.Limits(10, 36000, 60));

    for (VehicleDuty duty : result.duties()) {
      assertEquals(DEPOT, duty.endDepotNodeId(), duty.dutyCode() + " 必须从 HUB 回库");
      assertEquals(Optional.of(RETURN_ROUTE), duty.returnRouteId());
    }
    assertTrue(
        result.unassigned().stream()
            .anyMatch(u -> u.reason() == VehicleDutyPlanner.UnassignedReason.NO_RETURN_ACCESS),
        () -> "最后一班 HUB→FAR 停在 FAR，回不了库，必须被退掉: " + result.unassigned());
    assertTrue(result.allDutiesReturnToStorage());
  }

  /** 班次上限恰好在回不了库的终点用完时，不接这一班，而是留给别的车或另开。 */
  @Test
  void tripLimitNeverLandsOnATerminalWithoutReturn() {
    List<VehicleDutyPlanner.PlannedTrip> trips = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      boolean outbound = i % 2 == 0;
      trips.add(trip("T" + i, outbound ? HUB : FAR, outbound ? FAR : HUB, i * 900, 600));
    }

    // 上限 3 班：HUB→FAR→HUB→FAR 会停在 FAR。规划器必须在第 2 班就封口。
    VehicleDutyPlanner.Result result =
        VehicleDutyPlanner.plan(
            TIMETABLE, trips, hubLegs(), new VehicleDutyPlanner.Limits(3, 36000, 60));

    for (VehicleDuty duty : result.duties()) {
      assertEquals(0, duty.tripCount() % 2, duty.dutyCode() + " 从 HUB 出发就必须回到 HUB 才能封口");
      assertEquals(DEPOT, duty.endDepotNodeId());
    }
  }

  /** 首站从车库始发（CRET）的 route 自己就是出库票：不需要 CREATE 线路，也不能接在别的 duty 后面。 */
  @Test
  void selfCreatingRouteOpensItsOwnDuty() {
    List<VehicleDutyPlanner.PlannedTrip> trips =
        List.of(
            new VehicleDutyPlanner.PlannedTrip(id("A"), "A", DEPOT, HUB, 0, 300, true, false),
            new VehicleDutyPlanner.PlannedTrip(id("B"), "B", DEPOT, HUB, 900, 300, true, false));
    VehicleDutyPlanner.Legs legs =
        new VehicleDutyPlanner.Legs(
            Map.of(),
            Map.of(HUB, new VehicleDutyPlanner.Leg(RETURN_ROUTE, "RET", DEPOT, RETURN_RUN)));

    VehicleDutyPlanner.Result result =
        VehicleDutyPlanner.plan(TIMETABLE, trips, legs, VehicleDutyPlanner.Limits.defaults());

    assertEquals(2, result.duties().size(), "两班都从车库始发，各自一辆车");
    for (VehicleDuty duty : result.duties()) {
      assertTrue(duty.createRouteId().isEmpty());
      assertEquals(DEPOT, duty.startDepotNodeId());
      assertEquals(Optional.of(RETURN_ROUTE), duty.returnRouteId());
    }
    assertTrue(result.unassigned().isEmpty());
  }

  /** 以销毁收尾（DSTY）的 route 跑完即回库：duty 必然在此结束，不需要 RETURN 线路。 */
  @Test
  void selfReturningRouteClosesTheDuty() {
    List<VehicleDutyPlanner.PlannedTrip> trips =
        List.of(
            trip("T0", HUB, HUB, 0, 300),
            new VehicleDutyPlanner.PlannedTrip(id("T1"), "T1", HUB, DEPOT, 600, 300, false, true),
            trip("T2", HUB, HUB, 1200, 300));
    VehicleDutyPlanner.Legs legs =
        new VehicleDutyPlanner.Legs(
            Map.of(HUB, new VehicleDutyPlanner.Leg(CREATE_ROUTE, "CRT", DEPOT, CREATE_RUN)),
            Map.of());

    VehicleDutyPlanner.Result result =
        VehicleDutyPlanner.plan(
            TIMETABLE, trips, legs, new VehicleDutyPlanner.Limits(10, 36000, 60));

    VehicleDuty first = result.duties().get(0);
    assertEquals(List.of(id("T0"), id("T1")), first.tripIds(), "T1 销毁后不能再接 T2");
    assertEquals(VehicleDuty.CloseReason.ROUTE_ENDS_AT_DEPOT, first.closeReason());
    assertTrue(first.returnRouteId().isEmpty());
    assertEquals(DEPOT, first.endDepotNodeId());
    assertEquals(900, first.plannedEndSecondOfDay(), "到库时刻就是末班到达，没有另外的回库走行");
    // T2 停在 HUB，HUB 没有 RETURN 线路：退掉并上报。
    assertTrue(
        result.unassigned().stream()
            .anyMatch(
                u ->
                    u.tripCode().equals("T2")
                        && u.reason() == VehicleDutyPlanner.UnassignedReason.NO_RETURN_ACCESS));
  }

  /** 单独一班连同出库、回库走行就超过时长上限时，这一班排不进任何 duty，而不是产出一个越界的 duty。 */
  @Test
  void aTripThatCannotFitAnyDutyIsReported() {
    List<VehicleDutyPlanner.PlannedTrip> trips = loopTrips(1, 300, 3000);

    VehicleDutyPlanner.Result result =
        VehicleDutyPlanner.plan(
            TIMETABLE, trips, hubLegs(), new VehicleDutyPlanner.Limits(4, 1800, 60));

    assertTrue(result.duties().isEmpty());
    assertEquals(1, result.unassigned().size());
    assertEquals(
        VehicleDutyPlanner.UnassignedReason.EXCEEDS_DUTY_LIMITS,
        result.unassigned().get(0).reason());
  }

  /** 峰值并发用车数的是同一时刻在线的车，不是 duty 总数。 */
  @Test
  void peakConcurrentVehiclesCountsOverlapNotTotal() {
    // 每班 600 秒、间隔 300 秒、每辆车只跑一班：任一时刻最多两辆车重叠（不含出库/回库走行的话）。
    List<VehicleDutyPlanner.PlannedTrip> trips = loopTrips(10, 300, 600);

    VehicleDutyPlanner.Result result =
        VehicleDutyPlanner.plan(
            TIMETABLE, trips, hubLegs(), new VehicleDutyPlanner.Limits(1, 7200, 0));

    assertEquals(10, result.spawnedVehicles());
    int dutySpan = CREATE_RUN + 600 + RETURN_RUN;
    int expectedPeak = (int) Math.ceil(dutySpan / 300.0D);
    assertTrue(
        result.peakConcurrentVehicles() <= expectedPeak && result.peakConcurrentVehicles() >= 2,
        () -> "峰值应当是重叠数而不是总数: " + result.peakConcurrentVehicles());
  }

  // ------------------------------------------------------------------ 夹具

  /** HUB 既能出库也能回库。 */
  private static VehicleDutyPlanner.Legs hubLegs() {
    return new VehicleDutyPlanner.Legs(
        Map.of(HUB, new VehicleDutyPlanner.Leg(CREATE_ROUTE, "CRT", DEPOT, CREATE_RUN)),
        Map.of(HUB, new VehicleDutyPlanner.Leg(RETURN_ROUTE, "RET", DEPOT, RETURN_RUN)));
  }

  private static UUID id(String code) {
    return UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static VehicleDutyPlanner.PlannedTrip trip(
      String code, String origin, String terminal, int departure, int duration) {
    return new VehicleDutyPlanner.PlannedTrip(
        id(code), code, origin, terminal, departure, duration);
  }

  /**
   * 造一批环线班次：终点即起点，因此任意一班都能接在任意一班后面。
   *
   * @param count 班次数
   * @param headwaySeconds 发车间隔
   * @param durationSeconds 全程时分
   */
  private static List<VehicleDutyPlanner.PlannedTrip> loopTrips(
      int count, int headwaySeconds, int durationSeconds) {
    List<VehicleDutyPlanner.PlannedTrip> out = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      String code = String.format(Locale.ROOT, "T%05d", i);
      out.add(trip(code, HUB, HUB, i * headwaySeconds, durationSeconds));
    }
    return out;
  }

  /** 同一站有多条走行线路时，运营 route 显式指定的那条优先于走行更短的；没有指定时仍取最短。 */
  @Test
  void declaredLegWinsOverShorterUndeclaredOne() {
    UUID shortRoute =
        UUID.nameUUIDFromBytes("short".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    UUID declaredRoute =
        UUID.nameUUIDFromBytes("declared".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    VehicleDutyPlanner.Leg shorter = new VehicleDutyPlanner.Leg(shortRoute, "RET-A", DEPOT, 10);
    VehicleDutyPlanner.Leg declared =
        new VehicleDutyPlanner.Leg(declaredRoute, "RET-B", "CHT:D:DEP2:1", 30, true);
    java.util.Map<UUID, String> station = java.util.Map.of(shortRoute, HUB, declaredRoute, HUB);

    VehicleDutyPlanner.Legs withDeclared =
        VehicleDutyPlanner.Legs.of(List.of(), List.of(shorter, declared), station);
    VehicleDutyPlanner.Legs undeclared =
        VehicleDutyPlanner.Legs.of(
            List.of(),
            List.of(
                shorter, new VehicleDutyPlanner.Leg(declaredRoute, "RET-B", "CHT:D:DEP2:1", 30)),
            station);

    assertEquals(Optional.of(declared), withDeclared.returnLegAt(HUB));
    assertEquals(Optional.of(shorter), undeclared.returnLegAt(HUB));
  }
}
