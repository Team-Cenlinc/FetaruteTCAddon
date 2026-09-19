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
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.junit.jupiter.api.Test;

/**
 * 端到端构建：路网 → 时分 → 按 weight 排班 → 派车，全程确定。
 *
 * <p>这一组用例守的是三条不变量在<b>构建产物</b>上的表现：时刻表由路网算出、weight 是目标比例、 每个车辆交路都有限且回库。三者都可以只看 {@link
 * TimetableBuildResult} 断言，不需要跑 runtime。
 *
 * <p>路网是一条直链 {@code DEP - A - B - C}：DEP 是车库。CREATE 线路 DEP→A、RETURN 线路 C→DEP 或 A→DEP 由各用例按需配置。
 */
class TimetableBuilderTest {

  private static final Instant BUILT_AT = Instant.parse("2026-03-01T00:00:00Z");
  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final UUID COMPANY = UUID.randomUUID();
  private static final UUID OPERATOR = UUID.randomUUID();
  private static final UUID LINE = UUID.randomUUID();
  private static final UUID TIMETABLE = UUID.randomUUID();

  private static final String DEP = "OP:D:DEP:1";
  private static final String A = "OP:S:A:1";
  private static final String B = "OP:S:B:1";
  private static final String C = "OP:S:C:1";

  /** 同样输入构建两次，逐字段一致——包括 trip 与 duty 的主键。 */
  @Test
  void buildIsDeterministic() {
    Fixture fixture = twoRouteFixture(5, 5);

    TimetableBuildResult first = build(fixture, options(600));
    TimetableBuildResult second = build(fixture, options(600));

    Timetable a = first.timetable().orElseThrow();
    Timetable b = second.timetable().orElseThrow();
    assertEquals(a.trips(), b.trips(), "发车表必须逐项一致");
    assertEquals(a.duties(), b.duties(), "车辆交路必须逐项一致");
    assertEquals(a.routePlans(), b.routePlans(), "时分档案必须逐项一致");
  }

  /** 时分来自路网：改限速就改时分，不需要任何历史数据。 */
  @Test
  void timingComesFromTheNetwork() {
    Fixture fast = fixtureWithSpeed(20.0);
    Fixture slow = fixtureWithSpeed(5.0);

    int fastRun = operationPlan(build(fast, options(600))).totalRunSeconds();
    int slowRun = operationPlan(build(slow, options(600))).totalRunSeconds();

    assertEquals(10, fastRun, "200 blocks / 20 bps");
    assertEquals(40, slowRun, "200 blocks / 5 bps");
  }

  /** weight 决定服务比例：5:1 的两条 route，班次数也应接近 5:1。 */
  @Test
  void weightControlsServiceShare() {
    Map<String, Long> counts = tripCountsByRoute(build(twoRouteFixture(5, 1), options(300)));

    long ra = counts.getOrDefault("RA", 0L);
    long rb = counts.getOrDefault("RB", 0L);
    assertTrue(ra > rb * 4, () -> "RA=" + ra + " RB=" + rb);
  }

  /** 报告里同时给出目标份额与实际份额，按最终保留的班次计。 */
  @Test
  void reportsTargetAndAchievedShare() {
    TimetableBuildResult result = build(twoRouteFixture(3, 1), options(300));

    assertEquals(2, result.shares().size());
    WeightedTripAllocator.ShareReport ra =
        result.shares().stream().filter(s -> s.key().equals("RA")).findFirst().orElseThrow();
    assertEquals(0.75D, ra.targetShare(), 1e-9);
    assertTrue(ra.deviationPercentPoints() < TimetableBuilder.SHARE_WARN_PERCENT_POINTS);
    int reported = result.shares().stream().mapToInt(s -> s.assignedTrips()).sum();
    assertEquals(result.tripCount(), reported, "份额按最终保留的班次计，不按排定但被取消的班次计");
  }

  /** 每个 duty 都被上限夹住、以回库收尾，且两端挂着真实的出库/回库线路。 */
  @Test
  void everyDutyIsBoundedAndReturnsToStorage() {
    TimetableBuildOptions options =
        new TimetableBuildOptions(
            5 * 3600,
            23 * 3600,
            Duration.ofSeconds(300),
            Duration.ofSeconds(20),
            new VehicleDutyPlanner.Limits(3, 5400, 120),
            "",
            ZONE);

    TimetableBuildResult result = build(twoRouteFixture(1, 1), options);
    Timetable timetable = result.timetable().orElseThrow();

    assertFalse(timetable.duties().isEmpty());
    for (VehicleDuty duty : timetable.duties()) {
      assertTrue(duty.tripCount() <= 3, () -> duty.dutyCode() + " 班次越界");
      assertTrue(duty.plannedDurationSeconds() <= 5400, () -> duty.dutyCode() + " 时长越界");
      assertEquals(DEP, duty.startDepotNodeId(), () -> duty.dutyCode() + " 出库点不是车库");
      assertEquals(DEP, duty.endDepotNodeId(), () -> duty.dutyCode() + " 回库点不是车库");
      assertEquals(Optional.of(TimetableTestFixtures.routeId("CRT")), duty.createRouteId());
      assertEquals(Optional.of(TimetableTestFixtures.routeId("RET")), duty.returnRouteId());
    }
    assertTrue(result.allDutiesReturnToStorage());
    assertTrue(result.maxTripsInAnyDuty() <= 3);
    assertTrue(result.dutyCount() > 0);
    assertTrue(result.peakConcurrentVehicles() <= result.plannedVehicles());
  }

  /** 每一趟车都挂在某个 duty 上：没有无人承担的班次。 */
  @Test
  void everyTripIsAssignedToADuty() {
    Timetable timetable = build(twoRouteFixture(1, 1), options(600)).timetable().orElseThrow();

    for (TimetableTrip trip : timetable.trips()) {
      assertTrue(trip.dutyId().isPresent(), () -> trip.tripCode() + " 没有车辆交路");
      assertTrue(timetable.duty(trip.dutyId().orElseThrow()).isPresent());
    }
  }

  /**
   * 出库/回库走行时分来自路网，并写进 duty 的时刻。
   *
   * <p>DEP→A 是 50 blocks / 10 bps = 5 秒；折返 120 秒。首班 05:00 发，出库票就得在 04:57:55 发。
   */
  @Test
  void dutyLegTimesComeFromTheNetwork() {
    TimetableBuildOptions options =
        new TimetableBuildOptions(
            5 * 3600,
            23 * 3600,
            Duration.ofSeconds(600),
            Duration.ofSeconds(0),
            new VehicleDutyPlanner.Limits(1, 5400, 120),
            "",
            ZONE);

    Timetable timetable = build(twoRouteFixture(1, 1), options).timetable().orElseThrow();
    VehicleDuty first = timetable.duties().get(0);
    TimetableRoutePlan create =
        timetable.routePlan(TimetableTestFixtures.routeId("CRT")).orElseThrow();
    TimetableRoutePlan ret =
        timetable.routePlan(TimetableTestFixtures.routeId("RET")).orElseThrow();

    assertEquals(RouteOperationType.CREATE, create.kind());
    assertEquals(5, create.totalRunSeconds(), "50 blocks / 10 bps");
    assertEquals(5 * 3600 - 5 - 120, first.plannedStartSecondOfDay());
    // 首班 A→C 20 秒到达，折返 120 秒后发回库票，回库 C→DEP 走行 250 blocks / 10 bps = 25 秒。
    assertEquals(25, ret.totalRunSeconds());
    assertEquals(5 * 3600 + 20 + 120, first.returnSecondOfDay());
    assertEquals(5 * 3600 + 20 + 120 + 25, first.plannedEndSecondOfDay());
  }

  /** 线路没有任何出库途径时直接失败，而不是产出一张发不出车的表。 */
  @Test
  void buildFailsWithoutAnyCreateAccess() {
    Fixture fixture = twoRouteFixture(1, 1);
    List<TimetableBuilder.RouteInput> routes =
        fixture.routes().stream()
            .filter(route -> route.operationType() != RouteOperationType.CREATE)
            .toList();

    TimetableBuildResult result = build(new Fixture(fixture.graph(), routes), options(600));

    assertFalse(result.success());
    assertTrue(
        result.warnings().stream().anyMatch(text -> text.contains("出库途径")),
        () -> result.warnings().toString());
  }

  /** 线路没有任何回库途径时同样失败。 */
  @Test
  void buildFailsWithoutAnyReturnAccess() {
    Fixture fixture = twoRouteFixture(1, 1);
    List<TimetableBuilder.RouteInput> routes =
        fixture.routes().stream()
            .filter(route -> route.operationType() != RouteOperationType.RETURN)
            .toList();

    TimetableBuildResult result = build(new Fixture(fixture.graph(), routes), options(600));

    assertFalse(result.success());
    assertTrue(
        result.warnings().stream().anyMatch(text -> text.contains("回库途径")),
        () -> result.warnings().toString());
  }

  /**
   * 终点没有回库线路的班次不会被硬排：取消并逐条报告，份额按保留的班次重算。
   *
   * <p>RA: A→C、RB: C→A，回库线路只有 A→DEP。RA 的班次只有接上 RB 回到 A 才能收口。 窗口刚好装下 5 班（RA RB RA RB RA），最后那班 RA 停在
   * C 且后面没有 RB，必须被取消。
   */
  @Test
  void tripsWithoutReturnAccessAreDroppedAndReported() {
    RailGraph graph = chain();
    UUID ra = TimetableTestFixtures.routeId("RA");
    UUID rb = TimetableTestFixtures.routeId("RB");
    UUID crt = TimetableTestFixtures.routeId("CRT");
    UUID ret = TimetableTestFixtures.routeId("RETA");
    List<TimetableBuilder.RouteInput> routes =
        List.of(
            operation(ra, "RA", 1, List.of(A, B, C)),
            operation(rb, "RB", 1, List.of(C, B, A)),
            new TimetableBuilder.RouteInput(
                crt,
                "CRT",
                RouteOperationType.CREATE,
                0,
                TimetableTestFixtures.route("CRT", List.of(DEP, A)),
                TimetableTestFixtures.createStops(crt, 2, DEP),
                Optional.empty()),
            new TimetableBuilder.RouteInput(
                ret,
                "RETA",
                RouteOperationType.RETURN,
                0,
                TimetableTestFixtures.route("RETA", List.of(A, DEP)),
                TimetableTestFixtures.returnStops(ret, 2, DEP),
                Optional.empty()));
    TimetableBuildOptions options =
        new TimetableBuildOptions(
            5 * 3600,
            5 * 3600 + 2420,
            Duration.ofSeconds(600),
            Duration.ofSeconds(0),
            new VehicleDutyPlanner.Limits(4, 7200, 60),
            "",
            ZONE);

    TimetableBuildResult result = build(new Fixture(graph, routes), options);
    Timetable timetable = result.timetable().orElseThrow();

    assertEquals(1, result.droppedTrips().size(), "停在 C 的最后一班 RA 必须被取消");
    assertEquals("05:40:00", result.droppedTrips().get(0).departureText());
    assertEquals(
        VehicleDutyPlanner.UnassignedReason.NO_RETURN_ACCESS,
        result.droppedTrips().get(0).reason());
    assertEquals(4, timetable.trips().size());
    assertTrue(
        result.droppedTrips().stream()
            .allMatch(
                dropped ->
                    dropped.routeCode().equals("RA")
                        || dropped.reason()
                            == VehicleDutyPlanner.UnassignedReason.NO_CREATE_ACCESS),
        () -> result.droppedTrips().toString());
    assertTrue(
        result.warnings().stream().anyMatch(text -> text.contains("取消")),
        () -> result.warnings().toString());
    for (VehicleDuty duty : timetable.duties()) {
      assertEquals(DEP, duty.endDepotNodeId());
      assertEquals(Optional.of(ret), duty.returnRouteId());
    }
    // 取消之后车次号仍然连续：RA-001、RA-002…，没有空洞。
    List<String> raCodes =
        timetable.trips().stream()
            .filter(trip -> trip.routeId().equals(ra))
            .map(TimetableTrip::tripCode)
            .toList();
    for (int i = 0; i < raCodes.size(); i++) {
      assertEquals(String.format("RA-%03d", i + 1), raCodes.get(i));
    }
    int reported = result.shares().stream().mapToInt(s -> s.assignedTrips()).sum();
    assertEquals(timetable.trips().size(), reported);
  }

  /** 首站带 CRET 的运营 route 自己就是出库途径：没有 CREATE 线路也能成表。 */
  @Test
  void operationRouteStartingAtDepotNeedsNoCreateRoute() {
    RailGraph graph = chain();
    UUID ra = TimetableTestFixtures.routeId("RA");
    UUID ret = TimetableTestFixtures.routeId("RET");
    List<TimetableBuilder.RouteInput> routes =
        List.of(
            new TimetableBuilder.RouteInput(
                ra,
                "RA",
                RouteOperationType.OPERATION,
                1,
                TimetableTestFixtures.route("RA", List.of(DEP, A, B, C)),
                TimetableTestFixtures.createStops(ra, 4, DEP),
                Optional.empty()),
            new TimetableBuilder.RouteInput(
                ret,
                "RET",
                RouteOperationType.RETURN,
                0,
                TimetableTestFixtures.route("RET", List.of(C, DEP)),
                TimetableTestFixtures.returnStops(ret, 2, DEP),
                Optional.empty()));

    TimetableBuildResult result = build(new Fixture(graph, routes), options(600));
    Timetable timetable = result.timetable().orElseThrow();

    assertTrue(result.success());
    for (VehicleDuty duty : timetable.duties()) {
      assertTrue(duty.createRouteId().isEmpty(), "首班自车库始发，没有单独的出库票");
      assertEquals(1, duty.tripCount(), "从车库始发的班次不能接在别的 duty 后面");
      assertEquals(DEP, duty.startDepotNodeId());
    }
  }

  /**
   * 目标 headway 排出来有冲突时，回退到最小可行间隔并明确报告，回退后的表本身没有冲突。
   *
   * <p>单线 DEP–A–B–C、终点 C 只有一股道、折返 180 秒：120 秒一班时，前一辆车还在 C 折返、后一辆已经到了； 回库车还要从待命在 A
   * 的下一辆车身上碾过去。这些都是物理冲突，不是模型的偏好。
   */
  @Test
  void conflictingHeadwayFallsBackToTheSmallestFeasibleOne() {
    TimetableBuildOptions options =
        new TimetableBuildOptions(
            5 * 3600,
            7 * 3600,
            Duration.ofSeconds(120),
            Duration.ofSeconds(0),
            new VehicleDutyPlanner.Limits(1, 5400, 180),
            "",
            ZONE);

    TimetableBuildResult result = build(twoRouteFixture(1, 1), options);

    assertTrue(result.success(), () -> result.warnings().toString());
    assertTrue(result.headwayRelaxed(), "目标 120s 必然冲突，应当回退");
    assertEquals(120, result.targetHeadwaySeconds());
    assertTrue(
        result.effectiveHeadwaySeconds() >= 210,
        () -> "至少要等前车折返完: " + result.effectiveHeadwaySeconds());
    assertFalse(result.conflictsAtTarget().isEmpty());
    assertTrue(result.warnings().stream().anyMatch(text -> text.contains("已回退到最小可行间隔")));
    Timetable timetable = result.timetable().orElseThrow();
    assertEquals(
        result.effectiveHeadwaySeconds(),
        timetable.trips().get(1).departureSecondOfDay()
            - timetable.trips().get(0).departureSecondOfDay(),
        "发车表按回退后的间隔铺开");

    // 用回退后的间隔再建一次：不该再有冲突，这就是"最小可行"的定义。
    TimetableBuildResult rebuilt =
        build(
            twoRouteFixture(1, 1),
            options.withHeadway(Duration.ofSeconds(result.effectiveHeadwaySeconds())));
    assertTrue(rebuilt.conflictsAtTarget().isEmpty(), () -> rebuilt.warnings().toString());
    assertFalse(rebuilt.headwayRelaxed());
  }

  /** 严格模式：目标 headway 有冲突就失败，把冲突列出来，不回退。 */
  @Test
  void strictModeFailsInsteadOfRelaxing() {
    TimetableBuildOptions options =
        new TimetableBuildOptions(
            5 * 3600,
            7 * 3600,
            Duration.ofSeconds(120),
            Duration.ofSeconds(0),
            new VehicleDutyPlanner.Limits(1, 5400, 180),
            "",
            ZONE,
            Duration.ofSeconds(30),
            true);

    TimetableBuildResult result = build(twoRouteFixture(1, 1), options);

    assertFalse(result.success());
    assertTrue(
        result.warnings().stream().anyMatch(text -> text.contains("冲突") && text.contains("严格")),
        () -> result.warnings().toString());
  }

  /** 宽松的间隔下没有冲突，表按目标间隔成表。 */
  @Test
  void feasibleHeadwayIsKeptAsIs() {
    TimetableBuildResult result = build(twoRouteFixture(1, 1), options(600));

    assertTrue(result.conflictsAtTarget().isEmpty(), () -> result.warnings().toString());
    assertEquals(600, result.effectiveHeadwaySeconds());
    assertFalse(result.headwayRelaxed());
  }

  /** 一条 route 算不出时分时被排除并给出原因，其余 route 照常成表。 */
  @Test
  void infeasibleRouteIsExcludedWithReason() {
    Fixture fixture = twoRouteFixture(1, 1);
    // RB 指向一个不在图里的节点。
    List<TimetableBuilder.RouteInput> routes = new ArrayList<>(fixture.routes());
    routes.set(
        1,
        new TimetableBuilder.RouteInput(
            routes.get(1).routeId(),
            "RB",
            1,
            TimetableTestFixtures.route("RB", List.of(A, "OP:S:NOWHERE:1")),
            TimetableTestFixtures.stops(routes.get(1).routeId(), 2, 0),
            Optional.empty()));

    TimetableBuildResult result = build(new Fixture(fixture.graph(), routes), options(600));

    assertTrue(result.success(), "RA 仍应成表");
    assertEquals(1, result.infeasibleRoutes().size());
    assertEquals("RB", result.infeasibleRoutes().get(0).routeCode());
    assertTrue(
        result.warnings().stream().anyMatch(text -> text.contains("RB")),
        () -> result.warnings().toString());
  }

  /** 所有 route 都算不出时分时构建失败，而不是产出一张空表。 */
  @Test
  void buildFailsWhenNothingIsSchedulable() {
    Fixture fixture = twoRouteFixture(1, 1);
    TimetableBuildResult result =
        new TimetableBuilder()
            .build(
                new TimetableBuilder.BuildInput(
                    TIMETABLE,
                    COMPANY,
                    OPERATOR,
                    LINE,
                    "TT1",
                    "测试表",
                    fixture.routes(),
                    null,
                    TimetableTestFixtures.perEdgeSpeedModel(),
                    Optional.empty()),
                options(600),
                BUILT_AT);

    assertFalse(result.success());
  }

  /** 全程时分超过计划窗口时不硬排班次。 */
  @Test
  void tripsThatCannotFinishInsideHorizonAreNotScheduled() {
    Fixture fixture = twoRouteFixture(1, 1);
    // 窗口只有 5 秒，任何一趟 20 秒的车都装不下。
    TimetableBuildOptions options =
        new TimetableBuildOptions(
            5 * 3600,
            5 * 3600 + 5,
            Duration.ofSeconds(1),
            Duration.ZERO,
            VehicleDutyPlanner.Limits.defaults(),
            "",
            ZONE);

    TimetableBuildResult result = build(fixture, options);

    assertFalse(result.success());
  }

  /** 发车时刻按 headway 等间隔铺开，并落在计划窗口之内。 */
  @Test
  void departuresFollowTheConfiguredHeadway() {
    Timetable timetable = build(twoRouteFixture(1, 1), options(600)).timetable().orElseThrow();

    List<TimetableTrip> trips = timetable.trips();
    assertTrue(trips.size() >= 2);
    assertEquals(5 * 3600, trips.get(0).departureSecondOfDay());
    assertEquals(5 * 3600 + 600, trips.get(1).departureSecondOfDay());
  }

  // ------------------------------------------------------------------ 夹具

  private static TimetableBuildResult build(Fixture fixture, TimetableBuildOptions options) {
    return new TimetableBuilder()
        .build(
            new TimetableBuilder.BuildInput(
                TIMETABLE,
                COMPANY,
                OPERATOR,
                LINE,
                "TT1",
                "测试表",
                fixture.routes(),
                fixture.graph(),
                TimetableTestFixtures.perEdgeSpeedModel(),
                Optional.empty()),
            options,
            BUILT_AT);
  }

  private static TimetableBuildOptions options(int headwaySeconds) {
    return new TimetableBuildOptions(
        5 * 3600,
        23 * 3600,
        Duration.ofSeconds(headwaySeconds),
        Duration.ofSeconds(20),
        VehicleDutyPlanner.Limits.defaults(),
        "",
        ZONE);
  }

  private static TimetableRoutePlan operationPlan(TimetableBuildResult result) {
    return result.timetable().orElseThrow().routePlans().stream()
        .filter(TimetableRoutePlan::operation)
        .findFirst()
        .orElseThrow();
  }

  private static Map<String, Long> tripCountsByRoute(TimetableBuildResult result) {
    Timetable timetable = result.timetable().orElseThrow();
    Map<String, Long> counts = new LinkedHashMap<>();
    for (TimetableTrip trip : timetable.trips()) {
      String code =
          timetable.routePlan(trip.routeId()).map(TimetableRoutePlan::routeCode).orElse("?");
      counts.merge(code, 1L, Long::sum);
    }
    return counts;
  }

  /** 直链 DEP - A - B - C：车库段 50 blocks，站间各 100 blocks，全线 10 bps。 */
  private static RailGraph chain() {
    return chain(10.0);
  }

  private static RailGraph chain(double speedBps) {
    return TimetableTestFixtures.chain(
        List.of(DEP, A, B, C),
        new int[] {50, 100, 100},
        new double[] {speedBps, speedBps, speedBps});
  }

  private static TimetableBuilder.RouteInput operation(
      UUID routeId, String code, int weight, List<String> nodes) {
    return new TimetableBuilder.RouteInput(
        routeId,
        code,
        weight,
        TimetableTestFixtures.route(code, nodes),
        TimetableTestFixtures.stops(routeId, nodes.size(), 0),
        Optional.empty());
  }

  /** 出库 DEP→A、回库 C→DEP。 */
  private static List<TimetableBuilder.RouteInput> legs() {
    UUID crt = TimetableTestFixtures.routeId("CRT");
    UUID ret = TimetableTestFixtures.routeId("RET");
    return List.of(
        new TimetableBuilder.RouteInput(
            crt,
            "CRT",
            RouteOperationType.CREATE,
            0,
            TimetableTestFixtures.route("CRT", List.of(DEP, A)),
            TimetableTestFixtures.createStops(crt, 2, DEP),
            Optional.empty()),
        new TimetableBuilder.RouteInput(
            ret,
            "RET",
            RouteOperationType.RETURN,
            0,
            TimetableTestFixtures.route("RET", List.of(C, DEP)),
            TimetableTestFixtures.returnStops(ret, 2, DEP),
            Optional.empty()));
  }

  /** 两条同构 route（A→B→C），只有权重不同；出库 DEP→A、回库 C→DEP。 */
  private static Fixture twoRouteFixture(int weightA, int weightB) {
    UUID routeA = TimetableTestFixtures.routeId("RA");
    UUID routeB = TimetableTestFixtures.routeId("RB");
    List<TimetableBuilder.RouteInput> routes = new ArrayList<>();
    routes.add(operation(routeA, "RA", weightA, List.of(A, B, C)));
    routes.add(operation(routeB, "RB", weightB, List.of(A, B, C)));
    routes.addAll(legs());
    return new Fixture(chain(), routes);
  }

  /** 单条 route，路网限速可调——时分应当随之改变。 */
  private static Fixture fixtureWithSpeed(double speedBps) {
    UUID routeId = TimetableTestFixtures.routeId("RA");
    List<TimetableBuilder.RouteInput> routes = new ArrayList<>();
    routes.add(operation(routeId, "RA", 1, List.of(A, B, C)));
    routes.addAll(legs());
    return new Fixture(chain(speedBps), routes);
  }

  private record Fixture(RailGraph graph, List<TimetableBuilder.RouteInput> routes) {}
}
