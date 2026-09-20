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

  /** 外方（另一个 operator）的车站与车库：同一物理站台只有一个 id，直通 route 直接引用裸节点。 */
  private static final String FOREIGN_X = "CHT:S:X:1";

  private static final String FOREIGN_DEP = "CHT:D:DEP2:1";

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

  /**
   * 不传 {@code --turnaround} 时折返来自各 route 终到停靠点的 dwell，没配 dwell 的那条走 {@code --dwell} 兜底。
   *
   * <p>这条守的是"编表侧不另立事实源"：运行时的待命就绪时刻是 {@code 到达 + 终到站 dwell}，编表必须算出同一个数。 从前这里是一个凭空的 180
   * 秒常数，它把端点占用系统性地放大了。
   */
  @Test
  void turnaroundComesFromEachRouteTerminalDwell() {
    UUID ra = TimetableTestFixtures.routeId("RA");
    UUID crt = TimetableTestFixtures.routeId("CRT");
    UUID ret = TimetableTestFixtures.routeId("RET");
    List<TimetableBuilder.RouteInput> routes =
        List.of(
            new TimetableBuilder.RouteInput(
                ra,
                "RA",
                1,
                TimetableTestFixtures.route("RA", List.of(A, B, C)),
                // 中途不停站，终到 C 停 30 秒——折返应当正好是这 30 秒。
                List.of(stop(ra, 0, 0, false), stop(ra, 1, 0, false), stop(ra, 2, 30, true)),
                Optional.empty()),
            new TimetableBuilder.RouteInput(
                crt,
                "CRT",
                RouteOperationType.CREATE,
                0,
                TimetableTestFixtures.route("CRT", List.of(DEP, A)),
                // 出库线路的终到点没配 dwell：走 --dwell 兜底的 7 秒。
                List.of(cret(crt, 0, DEP), stop(crt, 1, null, true)),
                Optional.empty()),
            new TimetableBuilder.RouteInput(
                ret,
                "RET",
                RouteOperationType.RETURN,
                0,
                TimetableTestFixtures.route("RET", List.of(C, DEP)),
                TimetableTestFixtures.returnStops(ret, 2, DEP),
                Optional.empty()));
    TimetableBuildOptions options =
        new TimetableBuildOptions(
            5 * 3600,
            23 * 3600,
            Duration.ofSeconds(600),
            Duration.ofSeconds(7),
            // 折返没有显式覆盖：builder 应当按 route 定义建表。
            new VehicleDutyPlanner.Limits(1, 5400, TurnaroundTable.none()),
            "",
            ZONE);

    Timetable timetable = build(new Fixture(chain(), routes), options).timetable().orElseThrow();
    VehicleDuty first = timetable.duties().get(0);

    assertEquals(
        5 * 3600 - 5 - 7, first.plannedStartSecondOfDay(), "出库提前 = 走行 5 + CREATE 终到 dwell 兜底 7");
    assertEquals(5 * 3600 + 20 + 30, first.returnSecondOfDay(), "回库票 = 到达 + 运营 route 终到 dwell 30");
    assertEquals(5 * 3600 + 20 + 30 + 25, first.plannedEndSecondOfDay());
  }

  /**
   * 直通运转：运营线路跑到外方车站 X 终到，本 operator 没有从 X 回库的线路；运营 route 的 metadata 显式指定外方的 RETURN 线路 X→DEP2
   * 后，它作为回库走行进入交路，车在外方车库销毁。
   */
  @Test
  void declaredCrossOperatorReturnRouteBecomesALeg() {
    UUID ra = TimetableTestFixtures.routeId("RA");
    UUID crt = TimetableTestFixtures.routeId("CRT");
    UUID fret = TimetableTestFixtures.routeId("NL-RET");
    List<TimetableBuilder.RouteInput> routes =
        List.of(
            operation(ra, "RA", 1, List.of(A, B, C, FOREIGN_X)),
            new TimetableBuilder.RouteInput(
                crt,
                "CRT",
                RouteOperationType.CREATE,
                0,
                TimetableTestFixtures.route("CRT", List.of(DEP, A)),
                TimetableTestFixtures.createStops(crt, 2, DEP),
                Optional.empty()),
            new TimetableBuilder.RouteInput(
                fret,
                "NL-RET",
                RouteOperationType.RETURN,
                0,
                TimetableTestFixtures.route("NL-RET", List.of(FOREIGN_X, FOREIGN_DEP)),
                TimetableTestFixtures.returnStops(fret, 2, FOREIGN_DEP),
                Optional.empty(),
                Optional.of(RouteOperationType.RETURN),
                true));
    TimetableBuildOptions options =
        new TimetableBuildOptions(
            5 * 3600,
            5 * 3600 + 1800,
            Duration.ofSeconds(600),
            Duration.ofSeconds(0),
            new VehicleDutyPlanner.Limits(1, 5400, 60),
            "",
            ZONE);

    TimetableBuildResult result = build(new Fixture(throughRunningChain(), routes), options);

    assertTrue(result.success(), () -> result.warnings().toString());
    Timetable timetable = result.timetable().orElseThrow();
    assertFalse(timetable.duties().isEmpty());
    assertTrue(result.droppedTrips().isEmpty(), () -> result.droppedTrips().toString());
    for (VehicleDuty duty : timetable.duties()) {
      assertEquals(Optional.of(fret), duty.returnRouteId(), "回库走外方的 RETURN 线路");
      assertEquals(FOREIGN_DEP, duty.endDepotNodeId(), "在外方车库销毁");
    }
    assertTrue(timetable.routePlan(fret).orElseThrow().external(), "外方线路在计划里标 external");
    assertTrue(timetable.routeIds().contains(fret), "它进足迹与交路");
    assertFalse(timetable.managedRouteIds().contains(fret), "但不受本表管辖：外方线路自己的 headway 票照常发");
    assertTrue(timetable.managedRouteIds().contains(crt));
  }

  /** 指定的走行线路类型不符（把一条 CREATE 线路指定为回库线路）：判为不可行并说明原因，不拿它当回库线路用。 */
  @Test
  void declaredRouteOfWrongTypeIsInfeasible() {
    UUID ra = TimetableTestFixtures.routeId("RA");
    UUID crt = TimetableTestFixtures.routeId("CRT");
    UUID wrong = TimetableTestFixtures.routeId("NL-CRT");
    List<TimetableBuilder.RouteInput> routes =
        List.of(
            operation(ra, "RA", 1, List.of(A, B, C, FOREIGN_X)),
            new TimetableBuilder.RouteInput(
                crt,
                "CRT",
                RouteOperationType.CREATE,
                0,
                TimetableTestFixtures.route("CRT", List.of(DEP, A)),
                TimetableTestFixtures.createStops(crt, 2, DEP),
                Optional.empty()),
            new TimetableBuilder.RouteInput(
                wrong,
                "NL-CRT",
                RouteOperationType.CREATE,
                0,
                TimetableTestFixtures.route("NL-CRT", List.of(FOREIGN_DEP, FOREIGN_X)),
                TimetableTestFixtures.createStops(wrong, 2, FOREIGN_DEP),
                Optional.empty(),
                Optional.of(RouteOperationType.RETURN)));

    TimetableBuildResult result = build(new Fixture(throughRunningChain(), routes), options(600));

    assertFalse(result.success());
    assertTrue(
        result.infeasibleRoutes().stream()
            .anyMatch(r -> r.routeCode().equals("NL-CRT") && r.reason().contains("类型不符")),
        () -> result.infeasibleRoutes().toString());
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
   * <p>RA: A→C、RB: C→A，回库线路只有 A→DEP。RA 的班次只有接上 RB 回到 A 才能收口。 每方向 600 s 一班，RB 锚在 RA 到达 + 折返上：窗口装下
   * RA×5、RB×4， 最后那班 RA（05:40）停在 C 且后面没有 RB，必须被取消。
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
    assertEquals(8, timetable.trips().size());
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

  /**
   * 别的线的带客出库班只当走行段用，并标 external——不排成我的班次，也不进受管辖集合。
   *
   * <p>编表时 CREATE/RETURN 的搜索范围是本 operator 全部线路（为了找出库/回库途径），而"中途有 STOP 的 CREATE/RETURN
   * 是班次"这条规则一叠加，别的线的带客出库班就被排进了我的表。实测里给 WS 编表会把 DS 与 MT 的 带客 CREATE 一起排进去，发布后连人家自己的 headway 票都拦掉。
   */
  @Test
  void foreignPassengerCreateIsOnlyALegAndMarkedExternal() {
    UUID ra = TimetableTestFixtures.routeId("RA");
    UUID foreign = TimetableTestFixtures.routeId("FOREIGN-DS");
    UUID ret = TimetableTestFixtures.routeId("RET");
    UUID crt = TimetableTestFixtures.routeId("CRT");
    // 外线的带客出库班：DEP→A→B，中途有 STOP，自己看是班次。
    TimetableBuilder.RouteInput foreignCreate =
        new TimetableBuilder.RouteInput(
            foreign,
            "FOREIGN-DS",
            RouteOperationType.CREATE,
            1,
            TimetableTestFixtures.route("FOREIGN-DS", List.of(DEP, A, B)),
            TimetableTestFixtures.createStops(foreign, 3, DEP),
            Optional.empty());
    List<TimetableBuilder.RouteInput> routes = new ArrayList<>();
    routes.add(operation(ra, "RA", 1, List.of(A, B, C)));
    routes.add(foreignCreate);
    // 本线自己的出库/回库走行：没有它们 RA 开不了交路，验不到"外线带客班只当走行"这件事。
    routes.addAll(legs());

    Timetable owned = buildOwning(routes, Map.of(ra, LINE, ret, LINE));

    TimetableRoutePlan foreignPlan = owned.routePlan(foreign).orElseThrow();
    assertTrue(foreignPlan.external(), "别的线的走行线路必须标 external");
    assertTrue(
        owned.trips().stream().noneMatch(trip -> trip.routeId().equals(foreign)), "它不该被排成本表的班次");
    assertTrue(owned.trips().stream().anyMatch(trip -> trip.routeId().equals(ra)), "本线的班次照排");
    assertTrue(owned.routePlan(crt).orElseThrow().external(), "借来的纯走行同样标 external");
  }

  /** 受管辖集合排除借来的走行线路：发布之后不能去拦别人线路自己的 headway 票。 */
  @Test
  void managedRouteIdsExcludeForeignLegs() {
    UUID ra = TimetableTestFixtures.routeId("RA");
    UUID crt = TimetableTestFixtures.routeId("CRT");
    UUID ret = TimetableTestFixtures.routeId("RET");
    List<TimetableBuilder.RouteInput> routes = new ArrayList<>();
    routes.add(operation(ra, "RA", 1, List.of(A, B, C)));
    routes.addAll(legs());

    Timetable owned = buildOwning(routes, Map.of(ra, LINE));

    assertTrue(owned.managedRouteIds().contains(ra), "本线的运营线路受管辖");
    assertFalse(owned.managedRouteIds().contains(crt), "借来的出库线路不受管辖");
    assertFalse(owned.managedRouteIds().contains(ret), "借来的回库线路不受管辖");
    assertTrue(owned.routeIds().contains(crt), "但它仍然在本表的足迹与交路里");
  }

  /** 按给定归属构建：不在 {@code lineByRoute} 里的 route 是借来的走行线路。 */
  private static Timetable buildOwning(
      List<TimetableBuilder.RouteInput> routes, Map<UUID, UUID> lineByRoute) {
    TimetableBuildResult result =
        new TimetableBuilder()
            .build(
                new TimetableBuilder.BuildInput(
                    TIMETABLE,
                    COMPANY,
                    OPERATOR,
                    LINE,
                    "TT-OWN",
                    "归属测试",
                    routes,
                    chain(),
                    TimetableTestFixtures.perEdgeSpeedModel(),
                    Optional.empty(),
                    List.of(),
                    lineByRoute),
                options(600),
                BUILT_AT);
    return result.timetable().orElseThrow();
  }

  /**
   * 残余分两类，且两类之和就是报告里的冲突数——成功判据只看不可吸收那一类。
   *
   * <p>DEP-A-B-C 是单股道夹具，120 s 目标下的冲突都压在容量 1 的端点上，运行时在那里等就是堵死岔线， 所以它们必须被判成不可吸收，并因此触发放宽。这条同时钉住"分类接进了
   * build"与"判据没被放松成永远可吸收"。
   */
  @Test
  void unabsorbableResidualsStillRelax() {
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

    assertTrue(result.success(), "非严格模式下应当回退到可行间隔");
    assertTrue(result.effectiveHeadwaySeconds() > result.targetHeadwaySeconds(), "有让不掉的残余就该放宽");
    assertTrue(
        result.absorbable().stream().allMatch(ConflictAbsorption.Residual::absorbable),
        "可吸收那一列里不能混进别的判决");
    assertTrue(
        result.unabsorbable().stream().noneMatch(ConflictAbsorption.Residual::absorbable),
        "不可吸收那一列里不能混进可吸收的");
  }

  /** 排得干净的表两类残余都为空：分类不会凭空造出残余。 */
  @Test
  void aCleanTableHasNoResidualsAtAll() {
    TimetableBuildResult result = build(twoRouteFixture(1, 1), options(600));

    assertTrue(result.success());
    assertEquals(result.targetHeadwaySeconds(), result.effectiveHeadwaySeconds(), "不该放宽");
    assertTrue(result.absorbable().isEmpty());
    assertTrue(result.unabsorbable().isEmpty());
  }

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

  /** 直通链 DEP - A - B - C - X - DEP2：C 之后是外方的车站 X 与车库 DEP2。 */
  private static RailGraph throughRunningChain() {
    return TimetableTestFixtures.chain(
        List.of(DEP, A, B, C, FOREIGN_X, FOREIGN_DEP),
        new int[] {50, 100, 100, 100, 50},
        new double[] {10.0, 10.0, 10.0, 10.0, 10.0});
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

  /** 单个停靠点：{@code dwellSeconds} 为 null 表示没配，交给 {@code --dwell} 兜底。 */
  private static org.fetarute.fetaruteTCAddon.company.model.RouteStop stop(
      UUID routeId, int sequence, Integer dwellSeconds, boolean terminate) {
    return new org.fetarute.fetaruteTCAddon.company.model.RouteStop(
        routeId,
        sequence,
        Optional.empty(),
        Optional.empty(),
        Optional.ofNullable(dwellSeconds),
        terminate
            ? org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType.TERMINATE
            : org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType.STOP,
        Optional.empty());
  }

  /** 出库线路的首站：带 {@code CRET <depot>} 指令。 */
  private static org.fetarute.fetaruteTCAddon.company.model.RouteStop cret(
      UUID routeId, int sequence, String depotNodeId) {
    return new org.fetarute.fetaruteTCAddon.company.model.RouteStop(
        routeId,
        sequence,
        Optional.empty(),
        Optional.empty(),
        Optional.of(0),
        org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType.STOP,
        Optional.of("CRET " + depotNodeId));
  }

  private record Fixture(RailGraph graph, List<TimetableBuilder.RouteInput> routes) {}
}
