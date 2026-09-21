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
 * <p>路网直链 DEP–A–B–C，我的 RA 跑 A→C。邻表（另一条线、另一个运营商）有一趟车在相对 300 秒时占 A–B 边 10 秒： 目标 300 秒一班时我的第二班正好撞上。
 * 让车写进表之后，默认 --max-wait 60 装得下这 40 秒（10 + 裕量 30），第二班延后到 340 发；把让车关掉或上限压到 30 以下，才回到"放宽到 340 才躲开"。
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

  /** 同一组邻表两次 build 逐字段一致——不变量 2 的新措辞在产物上的形态。 */
  @Test
  void sameNeighborsSameTable() {
    NeighborTimetable neighbor = neighborAt(300);

    Timetable first = build(List.of(neighbor), 300).timetable().orElseThrow();
    Timetable second = build(List.of(neighbor), 300).timetable().orElseThrow();

    assertEquals(first.trips(), second.trips());
    assertEquals(first.duties(), second.duties());
  }

  /** 撞上邻表、等待在 --max-wait 之内：让车写进表——我的第二班延后 40 s，目标间隔不放宽，邻表原样，基线记下了对方。 */
  @Test
  void externalConflictUnderMaxWaitIsYieldedIntoTheTable() {
    NeighborTimetable neighbor = neighborAt(300);

    TimetableBuildResult result =
        build(List.of(neighbor), 300, TimetableBuildOptions.Repair.defaults(), false);

    assertTrue(result.success(), () -> result.warnings().toString());
    assertFalse(result.headwayRelaxed(), "40 s 的让车装得下，不该放宽");
    assertTrue(result.conflictsAtTarget().isEmpty(), () -> result.conflictsAtTarget().toString());
    assertEquals(1, result.yields().size());
    ResourceRepair.Yield yield = result.yields().get(0);
    assertTrue(yield.external());
    assertEquals(Optional.of(NEIGHBOR), yield.firstOwner());
    assertEquals("NB-001", yield.first());
    assertEquals("RA-002", yield.second());
    assertEquals(40, yield.waitSeconds());
    Timetable timetable = result.timetable().orElseThrow();
    assertEquals(5 * 3600 + 340, timetable.trips().get(1).departureSecondOfDay());
    assertEquals(1, result.shifts().size());
    assertEquals(TerminalSerializer.Shift.Reason.YIELDED, result.shifts().get(0).reason());
    // 邻表被记为影响过我的表：让车也算接触。
    assertEquals(1, result.neighbors().size());
    assertEquals(1, result.neighbors().get(0).conflictsAtTarget());
    assertEquals(1, result.baselines().size());
    assertEquals(neighbor.timetableId(), result.baselines().get(0).neighborTimetableId());
  }

  /** 严格模式只对真冲突失败：只有让车的表照常成表。 */
  @Test
  void strictModeAcceptsATableThatOnlyYields() {
    TimetableBuildResult result =
        build(List.of(neighborAt(300)), 300, TimetableBuildOptions.Repair.defaults(), true);

    assertTrue(result.success(), () -> result.warnings().toString());
    assertEquals(1, result.yields().size());
  }

  /**
   * 让车上限装不下（30 < 40）时回到放宽：目标 300 下那 40 s 是真冲突，只放宽我的 headway；放宽到 310 之后差的 30 s 又能让了——
   * 搜索找的是"让车装得下"的最小间隔。邻表的运行在冲突记录里一动不动；基线记下了对方。
   */
  @Test
  void externalConflictOverMaxWaitRelaxesMyHeadwayAndNeverMovesTheNeighbor() {
    NeighborTimetable neighbor = neighborAt(300);

    TimetableBuildResult result =
        build(
            List.of(neighbor),
            300,
            new TimetableBuildOptions.Repair(Duration.ofSeconds(30), Duration.ofSeconds(300)),
            false);

    assertTrue(result.success(), () -> result.warnings().toString());
    assertTrue(result.headwayRelaxed(), "目标 300s 与邻表撞上，必须放宽");
    assertTrue(
        result.yields().stream().allMatch(yield -> yield.waitSeconds() <= 30),
        () -> result.yields().toString());
    assertTrue(
        result.effectiveHeadwaySeconds() >= 310,
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
    assertEquals(300, neighborFrom);
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
    return new TimetableBuilder()
        .build(
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
