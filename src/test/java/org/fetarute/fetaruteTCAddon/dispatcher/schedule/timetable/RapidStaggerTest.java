package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 快车错峰：扫哪些平移、候选怎么判可用、怎么排先后、加停加在哪。 */
class RapidStaggerTest {

  /** 平移 gcd(I, I_k) 的整数倍对第 k 组是同一个相对位置：各 gcd 的最小公倍数就是要扫的那一段。 */
  @Test
  void thePeriodIsWhereEveryOtherGroupLooksTheSameAgain() {
    assertEquals(360, RapidStagger.period(720, List.of(360, 360)), "720 对 360：挪 360 秒对它们都一样");
    assertEquals(200, RapidStagger.period(600, List.of(400)));
    assertEquals(600, RapidStagger.period(600, List.of(400, 300)), "gcd 200 与 300 的最小公倍数");
    assertEquals(300, RapidStagger.period(300, List.of()), "没有别的组就扫满一个间隔");
    assertEquals(7, RapidStagger.period(7, List.of(11)), "互质时只能扫满一个间隔");
  }

  /** 平移档从一个步长起、不含 0（0 就是原表）、不到周期。 */
  @Test
  void shiftsCoverThePeriodWithoutZero() {
    assertEquals(List.of(10, 20, 30), RapidStagger.shifts(40));
    assertEquals(List.of(), RapidStagger.shifts(10));
  }

  /** 候选可用：排得开、不放宽、班次不少、高峰车数不多。 */
  @Test
  void aCandidateMustKeepTripsVehiclesAndTheTargetInterval() {
    RapidStagger.Outcome base = outcome(true, false, 100, 10);

    assertTrue(RapidStagger.acceptable(base, outcome(true, false, 100, 10)));
    assertTrue(RapidStagger.acceptable(base, outcome(true, false, 101, 9)));
    assertFalse(RapidStagger.acceptable(base, outcome(true, false, 99, 10)), "少了班次");
    assertFalse(RapidStagger.acceptable(base, outcome(true, false, 100, 11)), "多了车");
    assertFalse(RapidStagger.acceptable(base, outcome(true, true, 100, 10)), "放宽了间隔");
    assertFalse(RapidStagger.acceptable(base, outcome(false, false, 100, 10)), "没排出表");
  }

  /** 被卡秒数一样时，留给运行时去让的残余少的优先。 */
  @Test
  void fewerRuntimeResidualsWinATie() {
    RapidStagger.Candidate calm =
        new RapidStagger.Candidate(
            Map.of("G", 70),
            Optional.empty(),
            TimetableBuildResult.failure("只比排序", List.of()),
            new RapidStagger.Outcome(true, false, 100, 10, 0, 9),
            new RapidStagger.Measure(0L, 0, List.of()));
    RapidStagger.Candidate busy =
        new RapidStagger.Candidate(
            Map.of("G", 60),
            Optional.empty(),
            TimetableBuildResult.failure("只比排序", List.of()),
            new RapidStagger.Outcome(true, false, 100, 10, 351, 3),
            new RapidStagger.Measure(0L, 0, List.of()));

    assertTrue(RapidStagger.ORDER.compare(calm, busy) < 0);
  }

  /** 其余都一样时，加停少的优先：能少改就少改。 */
  @Test
  void aShorterDwellWinsATie() {
    UUID route = TimetableTestFixtures.routeId("RAPID");
    RapidStagger.Candidate shortDwell =
        new RapidStagger.Candidate(
            Map.of("G", 20),
            Optional.of(new RapidStagger.Dwell(route, "RAPID", 2, "OP:S:H:1", 15)),
            TimetableBuildResult.failure("只比排序", List.of()),
            new RapidStagger.Outcome(true, false, 100, 10, 0, 0),
            new RapidStagger.Measure(0L, 0, List.of()));
    RapidStagger.Candidate longDwell =
        new RapidStagger.Candidate(
            Map.of("G", 20),
            Optional.of(new RapidStagger.Dwell(route, "RAPID", 2, "OP:S:H:1", 60)),
            TimetableBuildResult.failure("只比排序", List.of()),
            new RapidStagger.Outcome(true, false, 100, 10, 0, 0),
            new RapidStagger.Measure(0L, 0, List.of()));

    assertTrue(RapidStagger.ORDER.compare(shortDwell, longDwell) < 0);
  }

  /** 只留排名前几的候选：每个都带着一整张表。 */
  @Test
  void onlyTheTopCandidatesAreKept() {
    List<RapidStagger.Candidate> top = new java.util.ArrayList<>();
    for (long caught = 50; caught > 0; caught -= 10) {
      RapidStagger.keepTop(top, candidate(Map.of("G", (int) caught), caught, 0));
    }

    assertEquals(RapidStagger.DWELL_SHORTLIST, top.size());
    assertEquals(10L, top.get(0).measure().seconds());
    assertEquals(30L, top.get(top.size() - 1).measure().seconds());
  }

  /** 先比被卡秒数，再比让车数，最后比改动大小。 */
  @Test
  void candidatesAreRankedByCaughtSecondsThenYieldsThenChange() {
    RapidStagger.Candidate fewerCaught = candidate(Map.of("G", 200), 10L, 5);
    RapidStagger.Candidate moreCaught = candidate(Map.of("G", 10), 20L, 0);
    RapidStagger.Candidate fewerYields = candidate(Map.of("G", 200), 10L, 3);
    RapidStagger.Candidate smallerChange = candidate(Map.of("G", 100), 10L, 3);

    assertTrue(RapidStagger.ORDER.compare(fewerCaught, moreCaught) < 0);
    assertTrue(RapidStagger.ORDER.compare(fewerYields, fewerCaught) < 0);
    assertTrue(RapidStagger.ORDER.compare(smallerChange, fewerYields) < 0);
  }

  /** 加停加在被拖住最多的那段共线之前的最后一个中途停车站，不加在起点。 */
  @Test
  void theDwellGoesToTheLastStopBeforeTheWorstCorridor() {
    UUID rapid = TimetableTestFixtures.routeId("RAPID");
    List<TimetableStop> stops =
        List.of(stop(0, 0, 0), stop(1, 60, 80), stop(2, 200, 220), stop(3, 400, 400));
    TimetableConflictChecker.RouteProfile profile =
        new TimetableConflictChecker.RouteProfile(rapid, "RAPID", stops, List.of(), List.of());
    RapidStagger.Measure measure =
        new RapidStagger.Measure(
            150L,
            2,
            List.of(
                new CorridorCatchUp.Caught("R1", rapid, 0, 0, 50, UUID.randomUUID()),
                new CorridorCatchUp.Caught("R1", rapid, 0, 230, 100, UUID.randomUUID())));

    List<RapidStagger.Dwell> points =
        RapidStagger.dwellPoints(measure, Map.of(rapid, profile), Map.of(rapid, "RAPID"));

    assertEquals(1, points.size());
    assertEquals(2, points.get(0).stopIndex(), "230 起那段被拖得最多，之前最后一个停车站是第 2 站（220 发）");

    RapidStagger.Measure onlyAtOrigin =
        new RapidStagger.Measure(
            50L, 1, List.of(new CorridorCatchUp.Caught("R1", rapid, 0, 0, 50, UUID.randomUUID())));
    assertTrue(
        RapidStagger.dwellPoints(onlyAtOrigin, Map.of(rapid, profile), Map.of(rapid, "RAPID"))
            .isEmpty(),
        "从起点就被拖住的，加停帮不上（那是平移的事）");
  }

  /** 报告一行：按快车交路分开，点名拖住它最多的慢车。 */
  @Test
  void theReportNamesTheWorstBlocker() {
    UUID rapid = TimetableTestFixtures.routeId("RAPID");
    UUID local = TimetableTestFixtures.routeId("LOCAL");
    UUID other = TimetableTestFixtures.routeId("OTHER");
    RapidStagger.Measure measure =
        new RapidStagger.Measure(
            130L,
            2,
            List.of(
                new CorridorCatchUp.Caught("R1", rapid, 0, 0, 100, local),
                new CorridorCatchUp.Caught("R2", rapid, 720, 0, 30, other)));

    String line =
        RapidStagger.describe(measure, Map.of(rapid, "RAPID", local, "LOCAL", other, "OTHER"));

    assertEquals("快车被卡（成品表实测）：RAPID 2 班、共 130s（主要被 LOCAL 拖住）", line);
    assertEquals(
        "快车被卡（成品表实测）：无",
        RapidStagger.describe(new RapidStagger.Measure(0L, 0, List.of()), Map.of()));
  }

  /**
   * 平移扫到第一段完全错开的窗口、把这段扫完就停，再在最好的位置两侧细试；错开了就不试加停。
   *
   * <p>周期 100：30、40 两档被卡 0，其余 100。扫 10、20、30、40，到 50 不再是 0 就停；最好的是 30（改动小），细试 25、35。
   */
  @Test
  void theScanStopsAfterTheFirstClearWindowAndRefinesAroundIt() {
    FakeEvaluator evaluator =
        new FakeEvaluator(shift -> shift.get("G") == 30 || shift.get("G") == 40 ? 0L : 100L);

    RapidStagger.Search search =
        RapidStagger.search(
            Map.of("G", 100), base(100L), measure -> List.of(dwellPoint()), evaluator);

    assertEquals(List.of(10, 20, 30, 40, 50, 25, 35), evaluator.shifts);
    assertEquals(List.of(), evaluator.dwells, "已经完全错开，不试加停");
    assertEquals(Map.of("G", 30), search.improved().orElseThrow().shift());
    assertEquals(7, search.tried());
  }

  /** 平移错不开时，在最好的几个平移上试快车加停；加停把被卡降到 0 的就选它。 */
  @Test
  void whenShiftingIsNotEnoughTheRapidDwells() {
    FakeEvaluator evaluator =
        new FakeEvaluator(shift -> shift.get("G") == 20 ? 30L : 60L)
            .dwelling(
                (shift, dwell) ->
                    shift.getOrDefault("G", 0) == 20 && dwell.seconds() == 30 ? 0L : 40L);

    RapidStagger.Search search =
        RapidStagger.search(
            Map.of("G", 40), base(100L), measure -> List.of(dwellPoint()), evaluator);

    RapidStagger.Candidate best = search.improved().orElseThrow();
    assertEquals(Map.of("G", 20), best.shift());
    assertEquals(30, best.dwell().orElseThrow().seconds());
    assertEquals(0L, best.measure().seconds());
    assertEquals(
        (RapidStagger.DWELL_SHORTLIST + 1) * RapidStagger.DWELL_STEPS.size(),
        evaluator.dwells.size(),
        "前几个最好的平移与原表各试一遍加停档位");
  }

  /** 所有平移都比原表差、而原表上加停就能解决：原表也是加停的起点。 */
  @Test
  void theOriginalLayoutIsAlsoTriedWithADwell() {
    FakeEvaluator evaluator =
        new FakeEvaluator(shift -> 500L)
            .dwelling((shift, dwell) -> shift.isEmpty() && dwell.seconds() == 15 ? 0L : 400L);

    RapidStagger.Search search =
        RapidStagger.search(
            Map.of("G", 40), base(100L), measure -> List.of(dwellPoint()), evaluator);

    RapidStagger.Candidate best = search.improved().orElseThrow();
    assertEquals(Map.of(), best.shift());
    assertEquals(15, best.dwell().orElseThrow().seconds());
  }

  /** 前一组已经完全错开：后面的快车组不再逐个编。 */
  @Test
  void laterGroupsAreSkippedOnceNothingIsCaught() {
    FakeEvaluator evaluator =
        new FakeEvaluator(shift -> shift.getOrDefault("A", 0) == 20 ? 0L : 50L);

    RapidStagger.Search search =
        RapidStagger.search(Map.of("A", 30, "B", 30), base(100L), measure -> List.of(), evaluator);

    assertEquals(Map.of("A", 20), search.improved().orElseThrow().shift());
    assertTrue(
        evaluator.history.stream().noneMatch(shift -> shift.containsKey("B")),
        evaluator.history::toString);
  }

  /** 开了错峰、有快车被拖住、原表没放宽才搜；放宽了不搜但要说一声。 */
  @Test
  void searchOnlyWhenEnabledCaughtAndNotRelaxed() {
    RapidStagger.Measure caught = new RapidStagger.Measure(10L, 1, List.of());
    RapidStagger.Measure clear = new RapidStagger.Measure(0L, 0, List.of());

    assertEquals(RapidStagger.Plan.SEARCH, RapidStagger.plan(true, caught, false));
    assertEquals(RapidStagger.Plan.SKIP_RELAXED, RapidStagger.plan(true, caught, true));
    assertEquals(RapidStagger.Plan.MEASURE_ONLY, RapidStagger.plan(false, caught, false));
    assertEquals(RapidStagger.Plan.MEASURE_ONLY, RapidStagger.plan(true, clear, false));
  }

  /** 实测写进构建结果：结构化合计（报告据此给按钮）与一行说明。 */
  @Test
  void theMeasureIsWrittenIntoTheResult() {
    UUID rapid = TimetableTestFixtures.routeId("RAPID");
    UUID local = TimetableTestFixtures.routeId("LOCAL");
    RapidStagger.Measure measure =
        new RapidStagger.Measure(
            100L, 1, List.of(new CorridorCatchUp.Caught("R1", rapid, 0, 0, 100, local)));

    TimetableBuildResult result =
        RapidStagger.annotate(
            TimetableBuildResult.failure("x", List.of()),
            measure,
            Map.of(rapid, "RAPID", local, "LOCAL"));

    assertEquals(new TimetableBuildResult.CatchUp(100L, 1), result.rapidCatchUp());
    assertEquals(List.of("快车被卡（成品表实测）：RAPID 1 班、共 100s（主要被 LOCAL 拖住）"), result.phaseNotes());
  }

  /** 没有一个候选比原表被卡更少：保持原表；编不出来（不可用）的候选跳过。 */
  @Test
  void noImprovementKeepsTheOriginal() {
    FakeEvaluator evaluator = new FakeEvaluator(shift -> shift.get("G") == 20 ? -1L : 100L);

    RapidStagger.Search search =
        RapidStagger.search(Map.of("G", 40), base(100L), measure -> List.of(), evaluator);

    assertTrue(search.improved().isEmpty());
    assertEquals(List.of(10, 20, 30, 5, 15), evaluator.shifts, "最好的是 10（改动小），细试 5、15");
  }

  /** 两个快车组：前一组定下的平移带进后一组的每个候选。 */
  @Test
  void groupsAreSettledOneAfterAnother() {
    FakeEvaluator evaluator =
        new FakeEvaluator(
            shift -> {
              long a = shift.getOrDefault("A", 0) == 20 ? 0L : 50L;
              long b = shift.getOrDefault("B", 0) == 10 ? 0L : 50L;
              return a + b;
            });

    RapidStagger.Search search =
        RapidStagger.search(Map.of("A", 30, "B", 30), base(100L), measure -> List.of(), evaluator);

    assertEquals(Map.of("A", 20, "B", 10), search.improved().orElseThrow().shift());
    assertTrue(
        evaluator.history.stream()
            .filter(shift -> shift.containsKey("B"))
            .allMatch(shift -> shift.get("A") == 20),
        evaluator.history::toString);
  }

  private static RapidStagger.Candidate base(long caught) {
    return new RapidStagger.Candidate(
        Map.of(),
        Optional.empty(),
        TimetableBuildResult.failure("原表", List.of()),
        new RapidStagger.Outcome(true, false, 100, 10, 0, 0),
        new RapidStagger.Measure(caught, caught > 0 ? 1 : 0, List.of()));
  }

  private static RapidStagger.Dwell dwellPoint() {
    return new RapidStagger.Dwell(
        TimetableTestFixtures.routeId("RAPID"), "RAPID", 2, "OP:S:H:1", 0);
  }

  /** 假的编表：按平移（与加停）给出被卡秒数；负数表示这个候选不可用。 */
  private static final class FakeEvaluator implements RapidStagger.Evaluator {
    private final java.util.function.ToLongFunction<Map<String, Integer>> caughtByShift;
    private java.util.function.ToLongBiFunction<Map<String, Integer>, RapidStagger.Dwell>
        caughtByDwell = (shift, dwell) -> 1000L;
    private final List<Integer> shifts = new java.util.ArrayList<>();
    private final List<Map<String, Integer>> history = new java.util.ArrayList<>();
    private final List<RapidStagger.Dwell> dwells = new java.util.ArrayList<>();

    FakeEvaluator(java.util.function.ToLongFunction<Map<String, Integer>> caughtByShift) {
      this.caughtByShift = caughtByShift;
    }

    FakeEvaluator dwelling(
        java.util.function.ToLongBiFunction<Map<String, Integer>, RapidStagger.Dwell> caught) {
      this.caughtByDwell = caught;
      return this;
    }

    @Override
    public Optional<RapidStagger.Candidate> shift(Map<String, Integer> shift) {
      history.add(Map.copyOf(shift));
      shifts.add(shift.values().stream().reduce((first, second) -> second).orElse(0));
      return candidate(shift, Optional.empty(), caughtByShift.applyAsLong(shift));
    }

    @Override
    public Optional<RapidStagger.Candidate> dwell(
        Map<String, Integer> shift, RapidStagger.Dwell dwell) {
      dwells.add(dwell);
      return candidate(shift, Optional.of(dwell), caughtByDwell.applyAsLong(shift, dwell));
    }

    private static Optional<RapidStagger.Candidate> candidate(
        Map<String, Integer> shift, Optional<RapidStagger.Dwell> dwell, long caught) {
      if (caught < 0) {
        return Optional.empty();
      }
      return Optional.of(
          new RapidStagger.Candidate(
              shift,
              dwell,
              TimetableBuildResult.failure("候选", List.of()),
              new RapidStagger.Outcome(true, false, 100, 10, 0, 0),
              new RapidStagger.Measure(caught, caught > 0 ? 1 : 0, List.of())));
    }
  }

  private static TimetableStop stop(int sequence, int arrival, int departure) {
    return new TimetableStop(
        sequence,
        Optional.empty(),
        Optional.of("OP:S:N" + sequence + ":1"),
        arrival,
        departure,
        org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType.STOP);
  }

  private static RapidStagger.Candidate candidate(
      Map<String, Integer> shift, long caught, int yields) {
    return new RapidStagger.Candidate(
        shift,
        Optional.empty(),
        TimetableBuildResult.failure("只比排序", List.of()),
        new RapidStagger.Outcome(true, false, 100, 10, 0, yields),
        new RapidStagger.Measure(caught, caught > 0 ? 1 : 0, List.of()));
  }

  private static RapidStagger.Outcome outcome(
      boolean success, boolean relaxed, int trips, int peak) {
    return new RapidStagger.Outcome(success, relaxed, trips, peak, 0, 0);
  }
}
