package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 周期层求解：合成夹具上的相位错开、超容量如实报、靠途中多停排开、避开邻表、确定性。 */
class PeriodicSolverTest {

  private static final TimetableConflictChecker.Footprints FOOTPRINTS =
      new TimetableConflictChecker.Footprints(TimetableConflictChecker.GraphIndex.of(null));

  @Test
  void twoFlowsOnOneTrackAreStaggered() {
    UUID a = route("a");
    UUID b = route("b");
    PeriodicSolver.Solution solution =
        PeriodicSolver.solve(
            input(
                List.of(flow("A", 100, a), flow("B", 100, b)),
                Map.of(a, shape(a, cell("e1", 0, 40)), b, shape(b, cell("e1", 0, 40))),
                0));
    assertEquals(0, solution.conflicts());
    int gap = Math.floorMod(solution.phases().get("B") - solution.phases().get("A"), 100);
    assertTrue(gap >= 40 && gap <= 60, "两条流在同一股道上须错开至少一格: " + gap);
  }

  @Test
  void overCapacityIsReportedNotHidden() {
    UUID a = route("a");
    UUID b = route("b");
    UUID c = route("c");
    PeriodicSolver.Solution solution =
        PeriodicSolver.solve(
            input(
                List.of(flow("A", 100, a), flow("B", 100, b), flow("C", 100, c)),
                Map.of(
                    a, shape(a, cell("e1", 0, 40)),
                    b, shape(b, cell("e1", 0, 40)),
                    c, shape(c, cell("e1", 0, 40))),
                0));
    assertTrue(solution.conflicts() > 0, "三条 40 秒的流挤不进 100 秒的环");
  }

  @Test
  void intermediateHoldResolvesWhatPhaseAloneCannot() {
    // B 先放（键序在前）：e1 占 [0,40)，e2 占 [10,50)。A 过 e1 到中途站（停 5 秒，40 到 45 发），再过 e2。
    // 只挪相位时 A 的 e1 只能放在 [40,60]，此时 e2 必撞；在中途站多停 45–70 秒才错得开。
    UUID a = route("a");
    UUID b = route("b");
    PeriodicSolver.Shape held =
        new PeriodicSolver.Shape(
            a,
            "",
            List.of(cell("e1", 0, 40), cell("e2", 45, 85)),
            new int[] {45},
            new int[] {1},
            85,
            Optional.empty(),
            Optional.empty(),
            0,
            true,
            true,
            false,
            List.of());
    PeriodicSolver.Solution solution =
        PeriodicSolver.solve(
            input(
                List.of(flow("A-follow", 100, a), flow("A-base", 100, b)),
                Map.of(a, held, b, shape(b, cell("e1", 0, 40), cell("e2", 10, 50))),
                70));
    assertEquals(0, solution.conflicts());
    assertFalse(solution.holds().isEmpty(), "须靠中途多停排开");
    int hold = solution.holds().get(a).get(1);
    assertTrue(hold >= 45 && hold <= 70, "多停应在 45–70 秒: " + hold);
  }

  @Test
  void aNeighborRunIsAvoidedByPhase() {
    UUID a = route("a");
    PeriodicSolver.Solution solution =
        PeriodicSolver.solve(
            withNeighbor(
                List.of(flow("A", 100, a)),
                Map.of(a, shape(a, cell("e1", 0, 40))),
                cell("e1", 0, 40)));
    int phase = solution.phases().get("A");
    assertTrue(phase >= 40 && phase <= 60, "邻表占 [0,40)，本表 40 秒的运行只能放在 [40,60]: " + phase);
  }

  @Test
  void aSingleLineNeighborOnlyRepelsOpposingRuns() {
    UUID a = route("a");
    PeriodicSolver.Solution following =
        PeriodicSolver.solve(
            withNeighbor(
                List.of(flow("A", 100, a)),
                Map.of(a, shape(a, single(0, 40, 1))),
                single(0, 40, 1)));
    assertEquals(0, following.phases().get("A"), "单线同向跟着邻表走不算相撞，不必挪");

    PeriodicSolver.Solution opposing =
        PeriodicSolver.solve(
            withNeighbor(
                List.of(flow("A", 100, a)),
                Map.of(a, shape(a, single(0, 40, -1))),
                single(0, 40, 1)));
    int phase = opposing.phases().get("A");
    assertTrue(phase >= 40 && phase <= 60, "单线对向须避开邻表: " + phase);
  }

  @Test
  void sameInputSameSolution() {
    UUID a = route("a");
    UUID b = route("b");
    PeriodicSolver.Input input =
        input(
            List.of(flow("A", 120, a), flow("B", 180, b)),
            Map.of(
                a, shape(a, cell("e1", 0, 30), cell("e2", 30, 50)),
                b, shape(b, cell("e2", 0, 25), cell("e1", 25, 45))),
            0);
    assertEquals(PeriodicSolver.solve(input), PeriodicSolver.solve(input));
    assertEquals(360, PeriodicSolver.solve(input).hyperperiod());
  }

  /** 间隔组合的最小公倍数超过上限时不解、给出原因，而不是铺一张几十小时的环。 */
  @Test
  void hyperperiodBeyondTheCapIsRefused() {
    UUID a = route("a");
    UUID b = route("b");
    PeriodicSolver.HyperperiodTooLong refused =
        org.junit.jupiter.api.Assertions.assertThrows(
            PeriodicSolver.HyperperiodTooLong.class,
            () ->
                PeriodicSolver.solve(
                    input(
                        List.of(flow("A", 317, a), flow("B", 476, b)),
                        Map.of(a, shape(a, cell("e1", 0, 40)), b, shape(b, cell("e2", 0, 40))),
                        0)));
    assertTrue(refused.getMessage().contains("最小公倍数"), refused.getMessage());
  }

  private static PeriodicSolver.Input input(
      List<PeriodicSolver.Flow> flows, Map<UUID, PeriodicSolver.Shape> shapes, int holdLimit) {
    return new PeriodicSolver.Input(flows, shapes, FOOTPRINTS, 0, 600, holdLimit, Map.of());
  }

  /** 邻表在 0 秒跑一趟 {@code neighbor}；计划窗口为零，不做整体旋转。 */
  private static PeriodicSolver.Input withNeighbor(
      List<PeriodicSolver.Flow> flows,
      Map<UUID, PeriodicSolver.Shape> shapes,
      TimetableConflictChecker.Slot neighbor) {
    return new PeriodicSolver.Input(
        flows,
        shapes,
        FOOTPRINTS,
        0,
        600,
        0,
        Map.of(),
        1,
        List.of(new PeriodicSolver.Fixed(neighbor, 0)),
        0,
        java.util.Set.of());
  }

  private static PeriodicSolver.Flow flow(String key, int interval, UUID route) {
    return new PeriodicSolver.Flow(key, key, interval, List.of(route), false);
  }

  private static PeriodicSolver.Shape shape(UUID route, TimetableConflictChecker.Slot... cells) {
    int arrival = 0;
    for (TimetableConflictChecker.Slot cell : cells) {
      arrival = Math.max(arrival, cell.to());
    }
    return new PeriodicSolver.Shape(
        route,
        "",
        List.of(cells),
        new int[0],
        new int[0],
        arrival,
        Optional.empty(),
        Optional.empty(),
        0,
        true,
        true,
        false,
        List.of());
  }

  private static TimetableConflictChecker.Slot cell(String key, int from, int to) {
    return new TimetableConflictChecker.Slot(
        key, TimetableConflictChecker.Kind.TRACK, 1, from, to, 0);
  }

  private static TimetableConflictChecker.Slot single(int from, int to, int direction) {
    return new TimetableConflictChecker.Slot(
        "s1", TimetableConflictChecker.Kind.SINGLE_LINE, 1, from, to, direction);
  }

  private static UUID route(String name) {
    return UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
