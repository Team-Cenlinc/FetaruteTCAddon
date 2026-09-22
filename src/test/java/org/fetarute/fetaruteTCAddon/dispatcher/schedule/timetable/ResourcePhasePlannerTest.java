package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 相位第三层：为每个方向选一个 δ，使共用资源上的周期冲突最少。
 *
 * <p>前两层只看端点（往返对锚定、共用起点交错），沿线哪里交会看不见。这一组用例钉的是： 能减少"让不掉的冲突"时才付出端点等待、δ 有上限、同一输入永远同一结果、并列时零偏移胜出。
 */
class ResourcePhasePlannerTest {

  private static final String A = "OP:S:A:1";
  private static final String B = "OP:S:B:1";
  private static final String C = "OP:S:C:1";
  private static final int SEPARATION = 30;
  private static final int MAX_WAIT = 300;

  /** 单股道直链 A–B–C：每段 100 blocks、10 bps，站间 10 秒。 */
  private static TimetableConflictChecker.GraphIndex index() {
    return TimetableConflictChecker.GraphIndex.of(
        TimetableTestFixtures.chain(
            List.of(A, B, C), new int[] {100, 100}, new double[] {10.0, 10.0}));
  }

  /** 没有可动的东西时原样返回：只有一个方向就没有"相对相位"可谈。 */
  @Test
  void aSingleFlowIsLeftAlone() {
    PhasePlanner.Phases before = new PhasePlanner.Phases(Map.of("A→C", 0), Map.of(), List.of());

    PhasePlanner.Phases after =
        ResourcePhasePlanner.refine(
            before, List.of(), Map.of(), Map.of(), Map.of(), index(), SEPARATION, MAX_WAIT, 300);

    assertEquals(before, after);
  }

  /** 端点多等的 δ 不会超过闲置上限——再久运行时就把车收回库了，表上写了也不作数。 */
  @Test
  void deltaIsBoundedByMaxIdle() {
    Fixture fixture = opposingPair(600);

    PhasePlanner.Phases after =
        ResourcePhasePlanner.refine(
            fixture.phases(),
            fixture.groups(),
            fixture.intervals(),
            fixture.templates(),
            fixture.profiles(),
            index(),
            SEPARATION,
            MAX_WAIT,
            120);

    for (Integer delta : after.deltaByDirection().values()) {
      assertTrue(delta <= 600, "正向 δ 不超过间隔");
    }
    assertTrue(
        after.deltaByDirection().getOrDefault(fixture.reverseKey(), 0) <= 120,
        "反向 δ 是端点多等，必须不超过 --max-idle");
  }

  /** 同一份输入跑两次，δ 与说明逐字段相同——第三层不能引入任何不确定性。 */
  @Test
  void refineIsDeterministic() {
    Fixture fixture = opposingPair(600);

    PhasePlanner.Phases first =
        ResourcePhasePlanner.refine(
            fixture.phases(),
            fixture.groups(),
            fixture.intervals(),
            fixture.templates(),
            fixture.profiles(),
            index(),
            SEPARATION,
            MAX_WAIT,
            300);
    PhasePlanner.Phases second =
        ResourcePhasePlanner.refine(
            fixture.phases(),
            fixture.groups(),
            fixture.intervals(),
            fixture.templates(),
            fixture.profiles(),
            index(),
            SEPARATION,
            MAX_WAIT,
            300);

    assertEquals(first.deltaByDirection(), second.deltaByDirection());
    assertEquals(first.resourceNotes(), second.resourceNotes());
  }

  /** 本来就不撞的一对：δ 全取 0，并在说明里讲清楚"评估过但没有更好的"，而不是默不作声。 */
  @Test
  void zeroDeltaWinsTies() {
    // 间隔 3600 秒、全程 20 秒：两个方向怎么摆都不可能撞。
    Fixture fixture = opposingPair(3600);

    PhasePlanner.Phases after =
        ResourcePhasePlanner.refine(
            fixture.phases(),
            fixture.groups(),
            fixture.intervals(),
            fixture.templates(),
            fixture.profiles(),
            index(),
            SEPARATION,
            MAX_WAIT,
            300);

    assertTrue(
        after.deltaByDirection().values().stream().allMatch(delta -> delta == 0), "不撞就不该付出任何端点等待");
    assertEquals(1, after.resourceNotes().size());
    assertTrue(after.resourceNotes().get(0).contains("零偏移"), after.resourceNotes().get(0));
  }

  /** 往返对余数只进报告：算得出来，但不参与任何决策。 */
  @Test
  void residuesAreReportedNotActedOn() {
    Fixture fixture = opposingPair(600);
    UUID forward = TimetableTestFixtures.routeId("FWD");

    List<PhasePlanner.Residue> residues =
        PhasePlanner.residues(
            fixture.groups(),
            fixture.intervals(),
            Map.of(forward, 20, TimetableTestFixtures.routeId("REV"), 20),
            TurnaroundTable.fixed(40));

    assertEquals(1, residues.size());
    PhasePlanner.Residue residue = residues.get(0);
    assertEquals(20, residue.runSeconds());
    assertEquals(40, residue.turnaroundSeconds());
    assertEquals(60, residue.residue(), "(20 + 40) mod 600");
  }

  // ------------------------------------------------------------------ 夹具

  private record Fixture(
      List<ServiceGroupClassifier.Group> groups,
      Map<String, Integer> intervals,
      Map<String, PeriodicTemplate> templates,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      PhasePlanner.Phases phases,
      String reverseKey) {}

  /** 单股道上对开的一对：FWD 从 A 到 C、REV 从 C 到 A，共用同一条链。 */
  private static Fixture opposingPair(int interval) {
    UUID forward = TimetableTestFixtures.routeId("FWD");
    UUID reverse = TimetableTestFixtures.routeId("REV");
    List<TimetableBuilder.RouteInput> routes =
        List.of(
            operation(forward, "FWD", List.of(A, B, C)),
            operation(reverse, "REV", List.of(C, B, A)));
    ServiceGroupClassifier.Classification classification = ServiceGroupClassifier.classify(routes);
    ServiceGroupClassifier.Group group = classification.groups().get(0);

    Map<UUID, Integer> runByRoute = Map.of(forward, 20, reverse, 20);
    Map<String, PeriodicTemplate> templates = new java.util.LinkedHashMap<>();
    for (ServiceGroupClassifier.Direction direction : group.directions()) {
      templates.put(
          direction.key(),
          PeriodicTemplate.of(
              direction,
              interval,
              runByRoute,
              VehicleDutyPlanner.Legs.none(),
              TurnaroundTable.fixed(40),
              Map.of(),
              false));
    }
    Map<UUID, TimetableConflictChecker.RouteProfile> profiles =
        Map.of(
            forward, profileOf(forward, "FWD", List.of(A, B, C)),
            reverse, profileOf(reverse, "REV", List.of(C, B, A)));

    PhasePlanner.Phases phases =
        PhasePlanner.plan(
            List.of(group),
            Map.of(group.name(), interval),
            runByRoute,
            TurnaroundTable.fixed(40),
            7200,
            Map.of());
    String reverseKey =
        phases.phaseByDirection().entrySet().stream()
            .filter(entry -> entry.getValue() != 0)
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(group.directions().get(0).key());
    return new Fixture(
        List.of(group), Map.of(group.name(), interval), templates, profiles, phases, reverseKey);
  }

  private static TimetableBuilder.RouteInput operation(UUID id, String code, List<String> nodes) {
    return new TimetableBuilder.RouteInput(
        id,
        code,
        1,
        TimetableTestFixtures.route(code, nodes),
        TimetableTestFixtures.stops(id, nodes.size(), 0),
        java.util.Optional.empty());
  }

  /** 逐段 10 秒的投影：够冲突检查把它铺到边与站台上。 */
  private static TimetableConflictChecker.RouteProfile profileOf(
      UUID id, String code, List<String> nodes) {
    TimetableTimingCalculator.TimingResult timing =
        new TimetableTimingCalculator()
            .compute(
                TimetableTestFixtures.chain(
                    List.of(A, B, C), new int[] {100, 100}, new double[] {10.0, 10.0}),
                TimetableTestFixtures.perEdgeSpeedModel(),
                TimetableTestFixtures.route(code, nodes),
                TimetableTestFixtures.stops(id, nodes.size(), 0),
                java.time.Duration.ZERO);
    return new TimetableConflictChecker.RouteProfile(
        id,
        code,
        timing.stops(),
        timing.segments(),
        TimetableConflictChecker.platformsOf(
            timing.stops(),
            TimetableTestFixtures.stops(id, nodes.size(), 0),
            nodes.stream().map(org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId::of).toList(),
            Map.of()));
  }
}
