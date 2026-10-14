package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.NeighborTimetable;
import org.junit.jupiter.api.Test;

/**
 * 路权先到先得：邻表的运行是不可移动的事实，build 只能挪自己。
 *
 * <p>路网直链 DEP–A–B–C，我的 RA 跑 A→C。300 秒网格撞上邻表的一趟运行时，单组完整相位能先避让。 让车场景覆盖 600
 * 秒网格的五个候选相位，让车库走行也参与检查：预算内写入让车，预算不足时才放宽本表频率。
 */
class TimetableBuilderNeighborsTest {

  /**
   * 本类的场景按 30 秒裕量设计（让车 40 s、上限 30 装不下、放宽到 310），所以显式钉住，不跟随 {@link
   * TimetableBuildOptions#DEFAULT_SEPARATION_SECONDS}——默认值改了这些用例测的东西不该跟着变。
   */
  private static final int SEPARATION = 30;

  private static final Instant BUILT_AT = Instant.parse("2026-03-01T00:00:00Z");
  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final String DEP = "OP:D:DEP:1";
  private static final String A = "OP:S:A:1";
  private static final String B = "OP:S:B:1";
  private static final String C = "OP:S:C:1";
  private static final String NEIGHBOR = "CHT/SURN/NL/NL-TT";

  private final RailGraph graph =
      TimetableTestFixtures.chain(
          List.of(DEP, A, B, C), new int[] {50, 100, 100}, new double[] {10.0, 10.0, 10.0});

  /** 单服务组也要试完整相位：避开固定邻表后保持 baseline、班次数和邻表时刻。 */
  @Test
  void aSingleServiceGroupAvoidsTheNeighborByPhaseWithoutRelaxingOrDropping() {
    NeighborTimetable neighbor = neighborAt(300);
    TimetableBuildResult result =
        build(
            new TimetableBuilder(new TimetableTimingCalculator(), Map.of(), false),
            List.of(neighbor),
            300,
            TimetableBuildOptions.Repair.none(),
            true);

    assertTrue(result.success(), () -> result.warnings().toString());
    assertFalse(result.headwayRelaxed());
    assertEquals(300, result.effectiveHeadwaySeconds());
    assertTrue(result.conflictsAtTarget().isEmpty());
    assertTrue(result.droppedTrips().isEmpty());
    List<TimetableTrip> trips = result.timetable().orElseThrow().trips();
    assertEquals(12, trips.size());
    for (int i = 1; i < trips.size(); i++) {
      assertEquals(
          300, trips.get(i).departureSecondOfDay() - trips.get(i - 1).departureSecondOfDay());
    }
    assertEquals(300, neighbor.movements().get(0).startSeconds());
  }

  /** 同一组邻表两次 build 逐字段一致——不变量 2 的新措辞在产物上的形态。 */
  @Test
  void sameNeighborsSameTable() {
    NeighborTimetable neighbor = neighborAt(300);

    Timetable first = build(List.of(neighbor), 300).timetable().orElseThrow();
    Timetable second = build(List.of(neighbor), 300).timetable().orElseThrow();

    assertEquals(first.trips(), second.trips());
    assertEquals(first.duties(), second.duties());
  }

  /** 所有候选相位都撞上邻表但等待在预算内：出入库也参与让车，保持 600 秒基准及邻表时刻。 */
  @Test
  void externalConflictUnderMaxWaitIsYieldedIntoTheTable() {
    NeighborTimetable neighbor = neighborAcrossPhaseCandidates();

    TimetableBuildResult result =
        build(List.of(neighbor), 600, TimetableBuildOptions.Repair.defaults(), false);

    assertTrue(result.success(), () -> result.warnings().toString());
    assertFalse(result.headwayRelaxed(), "让车装得下，不该放宽");
    assertEquals(600, result.effectiveHeadwaySeconds());
    assertTrue(result.conflictsAtTarget().isEmpty(), () -> result.conflictsAtTarget().toString());
    assertFalse(result.yields().isEmpty());
    assertTrue(
        result.yields().stream()
            .allMatch(
                yield ->
                    yield.external()
                        && yield.firstOwner().equals(Optional.of(NEIGHBOR))
                        && yield.waitSeconds() <= 300));
    assertTrue(
        result.shifts().stream()
            .anyMatch(shift -> shift.reason() == TerminalSerializer.Shift.Reason.YIELDED));
    // 邻表被记为影响过我的表：让车也算接触。
    assertEquals(1, result.neighbors().size());
    assertTrue(result.neighbors().get(0).conflictsAtTarget() >= 1);
    assertEquals(1, result.baselines().size());
    assertEquals(neighbor.timetableId(), result.baselines().get(0).neighborTimetableId());
  }

  /** 快车错峰的候选编表器不放宽：上面那种让车装不下、要放宽才排得开的，候选直接失败（被淘汰），而不是去搜放宽。 */
  @Test
  void aStaggerCandidateFailsInsteadOfRelaxing() {
    TimetableBuildResult result =
        build(
            new TimetableBuilder(new TimetableTimingCalculator(), Map.of(), false),
            List.of(neighborAcrossPhaseCandidates()),
            600,
            new TimetableBuildOptions.Repair(Duration.ofSeconds(5), Duration.ofSeconds(300)),
            false);

    assertFalse(result.success(), () -> result.warnings().toString());
  }

  /** 严格模式只对真冲突失败：只有让车的表照常成表。 */
  @Test
  void strictModeAcceptsATableThatOnlyYields() {
    TimetableBuildResult result =
        build(
            List.of(neighborAcrossPhaseCandidates()),
            600,
            TimetableBuildOptions.Repair.defaults(),
            true);

    assertTrue(result.success(), () -> result.warnings().toString());
    assertFalse(result.yields().isEmpty());
    assertTrue(result.conflictsAtTarget().isEmpty());
  }

  /**
   * 让车上限装不下且所有相位都撞车时回到放宽：上限 5 秒，零偏移要让 40 秒，相位候选里离邻表最远的细档（起点 +30 秒）也要让 10 秒，目标 600 下全是真冲突；
   * 放宽之后差的那几秒又能让了—— 搜索找的是"让车装得下"的最小间隔。邻表的运行在冲突记录里一动不动；基线记下了对方。
   */
  @Test
  void externalConflictOverMaxWaitRelaxesMyHeadwayAndNeverMovesTheNeighbor() {
    NeighborTimetable neighbor = neighborAcrossPhaseCandidates();

    TimetableBuildResult result =
        build(
            List.of(neighbor),
            600,
            new TimetableBuildOptions.Repair(Duration.ofSeconds(5), Duration.ofSeconds(300)),
            false);

    assertTrue(result.success(), () -> result.warnings().toString());
    assertTrue(result.headwayRelaxed(), "目标 600s 的全部相位与邻表撞上，必须放宽");
    assertTrue(
        result.yields().stream().allMatch(yield -> yield.waitSeconds() <= 5),
        () -> result.yields().toString());
    assertTrue(
        result.effectiveHeadwaySeconds() >= 610,
        () -> String.valueOf(result.effectiveHeadwaySeconds()));
    assertFalse(result.externalConflictsAtTarget().isEmpty());
    assertTrue(
        result.externalConflictsAtTarget().stream()
            .allMatch(c -> c.otherOwner().map(NEIGHBOR::equals).orElse(false)),
        () -> result.externalConflictsAtTarget().toString());
    // 邻表那一侧的占用区间就是它输入时的时刻：没有被挪动。
    TimetableConflictChecker.Conflict external = result.externalConflictsAtTarget().get(0);
    int neighborFrom =
        external.firstOwner().isPresent() ? external.firstFrom() : external.secondFrom();
    assertEquals(600, neighborFrom);
    assertEquals(1, result.neighbors().size());
    assertEquals(NEIGHBOR, result.neighbors().get(0).displayCode());
    assertTrue(result.neighbors().get(0).conflictsAtTarget() >= 1);
    assertEquals(1, result.baselines().size());
    assertEquals(neighbor.timetableId(), result.baselines().get(0).neighborTimetableId());
    assertEquals(neighbor.updatedAt(), result.baselines().get(0).neighborUpdatedAt());
    assertTrue(
        result.warnings().stream().anyMatch(text -> text.contains("已发布邻表的冲突")),
        () -> result.warnings().toString());
  }

  /** 没有邻表时结果与旧行为一致：无外部冲突、无基线。 */
  @Test
  void noNeighborsMeansNoExternalConflictsAndNoBaselines() {
    TimetableBuildResult result = build(List.of(), 600);

    assertTrue(result.success());
    assertTrue(result.externalConflictsAtTarget().isEmpty());
    assertTrue(result.baselines().isEmpty());
    assertTrue(result.neighbors().isEmpty());
  }

  /** 运营商优先级不是 build 的输入：BuildInput 的组件里没有任何叫 priority 的东西，将来也别偷偷加。 */
  @Test
  void operatorPriorityIsNotAnInput() {
    for (var component : TimetableBuilder.BuildInput.class.getRecordComponents()) {
      assertFalse(
          component.getName().toLowerCase(java.util.Locale.ROOT).contains("priority"),
          "BuildInput 不得含 priority：路权由发布顺序仲裁，不由运行时排队点数仲裁");
    }
  }

  // ------------------------------------------------------------------ 夹具

  /** 覆盖 600 秒间隔的五个相位候选；相邻邻表运行之间仍可容纳 40 秒让车。 */
  private NeighborTimetable neighborAcrossPhaseCandidates() {
    NeighborTimetable base = neighborAt(600);
    var movement = base.movements().get(0);
    List<TimetableConflictChecker.Movement> movements = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      movements.add(
          new TimetableConflictChecker.Movement(
              "NB-00" + (i + 1), movement.routeId(), 600 + 120 * i, movement.owner()));
    }
    return new NeighborTimetable(
        base.timetableId(),
        base.displayCode(),
        base.updatedAt(),
        base.zoneId(),
        base.sharedResources(),
        base.staleAgainstGraph(),
        base.zoneApproximated(),
        base.profiles(),
        movements,
        base.stays(),
        base.warnings());
  }

  /** 邻表：一趟 NB（A→B）在相对 {@code start} 秒发车，占 A–B 边 10 秒。 */
  private NeighborTimetable neighborAt(int start) {
    UUID nb = TimetableTestFixtures.routeId("NB");
    RouteDefinition definition = TimetableTestFixtures.route("NB", List.of(A, B));
    List<RouteStop> stops = TimetableTestFixtures.stops(nb, 2, 0);
    TimetableTimingCalculator.TimingResult timing =
        new TimetableTimingCalculator()
            .compute(
                graph, TimetableTestFixtures.perEdgeSpeedModel(), definition, stops, Duration.ZERO);
    assertTrue(timing.ok());
    TimetableConflictChecker.RouteProfile profile =
        new TimetableConflictChecker.RouteProfile(
            nb,
            "NB",
            timing.stops(),
            timing.segments(),
            TimetableConflictChecker.platformsOf(timing.stops(), stops, definition.waypoints()));
    return new NeighborTimetable(
        UUID.nameUUIDFromBytes("NL-TT".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        NEIGHBOR,
        Instant.parse("2026-02-28T12:00:00Z"),
        ZONE,
        3,
        false,
        false,
        Map.of(nb, profile),
        List.of(new TimetableConflictChecker.Movement("NB-001", nb, start, Optional.of(NEIGHBOR))),
        List.of(),
        List.of());
  }

  private TimetableBuildResult build(List<NeighborTimetable> neighbors, int headway) {
    return build(neighbors, headway, TimetableBuildOptions.Repair.none(), false);
  }

  private TimetableBuildResult build(
      List<NeighborTimetable> neighbors,
      int headway,
      TimetableBuildOptions.Repair repair,
      boolean strict) {
    return build(new TimetableBuilder(), neighbors, headway, repair, strict);
  }

  private TimetableBuildResult build(
      TimetableBuilder builder,
      List<NeighborTimetable> neighbors,
      int headway,
      TimetableBuildOptions.Repair repair,
      boolean strict) {
    UUID ra = TimetableTestFixtures.routeId("RA");
    UUID crt = TimetableTestFixtures.routeId("CRT");
    UUID ret = TimetableTestFixtures.routeId("RET");
    List<TimetableBuilder.RouteInput> routes = new ArrayList<>();
    routes.add(
        new TimetableBuilder.RouteInput(
            ra,
            "RA",
            1,
            TimetableTestFixtures.route("RA", List.of(A, B, C)),
            TimetableTestFixtures.stops(ra, 3, 0),
            Optional.empty()));
    routes.add(
        new TimetableBuilder.RouteInput(
            crt,
            "CRT",
            RouteOperationType.CREATE,
            0,
            TimetableTestFixtures.route("CRT", List.of(DEP, A)),
            TimetableTestFixtures.createStops(crt, 2, DEP),
            Optional.empty()));
    routes.add(
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
            5 * 3600 + 3600,
            Duration.ofSeconds(headway),
            Duration.ofSeconds(0),
            new VehicleDutyPlanner.Limits(1, 5400, 60),
            "",
            ZONE,
            Duration.ofSeconds(SEPARATION),
            strict,
            Map.of(),
            repair);
    return builder.build(
        new TimetableBuilder.BuildInput(
            UUID.nameUUIDFromBytes("TT".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "TT1",
            "测试表",
            routes,
            graph,
            TimetableTestFixtures.perEdgeSpeedModel(),
            Optional.empty(),
            neighbors),
        options,
        BUILT_AT);
  }
}
