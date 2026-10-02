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

  /** 候选可用：排得开、不放宽、班次不少、高峰最多多用一列车。 */
  @Test
  void aCandidateMustKeepTripsVehiclesAndTheTargetInterval() {
    RapidStagger.Outcome base = outcome(true, false, 100, 10);

    assertTrue(RapidStagger.acceptable(base, outcome(true, false, 100, 10)));
    assertTrue(RapidStagger.acceptable(base, outcome(true, false, 101, 9)));
    assertFalse(RapidStagger.acceptable(base, outcome(true, false, 99, 10)), "少了班次");
    assertTrue(RapidStagger.acceptable(base, outcome(true, false, 100, 11)), "多一列车可以");
    assertFalse(RapidStagger.acceptable(base, outcome(true, false, 100, 12)), "多两列不行");
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
            outcome(true, false, 100, 10, 0, 9),
            measure(0L, 0, List.of()));
    RapidStagger.Candidate busy =
        new RapidStagger.Candidate(
            Map.of("G", 60),
            Optional.empty(),
            TimetableBuildResult.failure("只比排序", List.of()),
            outcome(true, false, 100, 10, 351, 3),
            measure(0L, 0, List.of()));

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
            outcome(true, false, 100, 10, 0, 0),
            measure(0L, 0, List.of()));
    RapidStagger.Candidate longDwell =
        new RapidStagger.Candidate(
            Map.of("G", 20),
            Optional.of(new RapidStagger.Dwell(route, "RAPID", 2, "OP:S:H:1", 60)),
            TimetableBuildResult.failure("只比排序", List.of()),
            outcome(true, false, 100, 10, 0, 0),
            measure(0L, 0, List.of()));

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
        measure(
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
        measure(
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
        measure(
            130L,
            2,
            List.of(
                new CorridorCatchUp.Caught("R1", rapid, 0, 0, 100, local),
                new CorridorCatchUp.Caught("R2", rapid, 720, 0, 30, other)));

    String line =
        RapidStagger.describe(measure, Map.of(rapid, "RAPID", local, "LOCAL", other, "OTHER"));

    assertEquals("快车被卡（成品表实测）：RAPID 2 班、共 130s（主要被 LOCAL 拖住）", line);
    assertEquals("快车被卡（成品表实测）：无", RapidStagger.describe(measure(0L, 0, List.of()), Map.of()));
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
            Map.of("G", 100),
            base(100L),
            measure -> List.of(),
            measure -> List.of(dwellPoint()),
            false,
            evaluator);

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
            Map.of("G", 40),
            base(100L),
            measure -> List.of(),
            measure -> List.of(dwellPoint()),
            false,
            evaluator);

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
            Map.of("G", 40),
            base(100L),
            measure -> List.of(),
            measure -> List.of(dwellPoint()),
            false,
            evaluator);

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
        RapidStagger.search(
            Map.of("A", 30, "B", 30),
            base(100L),
            measure -> List.of(),
            measure -> List.of(),
            false,
            evaluator);

    assertEquals(Map.of("A", 20), search.improved().orElseThrow().shift());
    assertTrue(
        evaluator.history.stream().noneMatch(shift -> shift.containsKey("B")),
        evaluator.history::toString);
  }

  /** 开了错峰、有快车被拖住才搜；原表放宽了只试加长折返。 */
  @Test
  void searchOnlyWhenEnabledCaughtAndNotRelaxed() {
    RapidStagger.Measure caught = measure(10L, 1, List.of());
    RapidStagger.Measure clear = measure(0L, 0, List.of());

    assertEquals(RapidStagger.Plan.SEARCH, RapidStagger.plan(true, caught, false));
    assertEquals(RapidStagger.Plan.TURNBACK_ONLY, RapidStagger.plan(true, caught, true));
    assertEquals(RapidStagger.Plan.MEASURE_ONLY, RapidStagger.plan(false, caught, false));
    assertEquals(RapidStagger.Plan.MEASURE_ONLY, RapidStagger.plan(true, clear, false));
    assertEquals(
        RapidStagger.Plan.SEARCH,
        RapidStagger.plan(true, measure(0L, 0, List.of(), 40L), false),
        "共线不被卡、却在表里让车等待，也是快车损失");
  }

  /** 实测写进构建结果：结构化合计（报告据此给按钮）与一行说明。 */
  @Test
  void theMeasureIsWrittenIntoTheResult() {
    UUID rapid = TimetableTestFixtures.routeId("RAPID");
    UUID local = TimetableTestFixtures.routeId("LOCAL");
    RapidStagger.Measure measure =
        measure(100L, 1, List.of(new CorridorCatchUp.Caught("R1", rapid, 0, 0, 100, local)));

    TimetableBuildResult result =
        RapidStagger.annotate(
            TimetableBuildResult.failure("x", List.of()),
            measure,
            Map.of(rapid, "RAPID", local, "LOCAL"));

    assertEquals(new TimetableBuildResult.CatchUp(100L, 1, 0L), result.rapidCatchUp());
    assertEquals(List.of("快车被卡（成品表实测）：RAPID 1 班、共 100s（主要被 LOCAL 拖住）"), result.phaseNotes());
  }

  /** 没有一个候选比原表被卡更少：保持原表；编不出来（不可用）的候选跳过。 */
  @Test
  void noImprovementKeepsTheOriginal() {
    FakeEvaluator evaluator = new FakeEvaluator(shift -> shift.get("G") == 20 ? -1L : 100L);

    RapidStagger.Search search =
        RapidStagger.search(
            Map.of("G", 40),
            base(100L),
            measure -> List.of(),
            measure -> List.of(),
            false,
            evaluator);

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
        RapidStagger.search(
            Map.of("A", 30, "B", 30),
            base(100L),
            measure -> List.of(),
            measure -> List.of(),
            false,
            evaluator);

    assertEquals(Map.of("A", 20, "B", 10), search.improved().orElseThrow().shift());
    assertTrue(
        evaluator.history.stream()
            .filter(shift -> shift.containsKey("B"))
            .allMatch(shift -> shift.get("A") == 20),
        evaluator.history::toString);
  }

  /**
   * 原地折返端加长折返排在最前：按实测给的秒数从小到大加余量，错开了就停，后面的平移与中途加停都不再试。
   *
   * <p>加长点给 120 秒：120 仍被卡、150 错开，于是只编两次。
   */
  @Test
  void aLongerTurnbackIsTriedFirstAndStopsOnceClear() {
    FakeEvaluator evaluator =
        new FakeEvaluator(shift -> 500L)
            .dwelling((shift, dwell) -> dwell.turnback() && dwell.seconds() >= 150 ? 0L : 80L);

    RapidStagger.Search search =
        RapidStagger.search(
            Map.of("G", 40),
            base(100L),
            measure -> List.of(turnbackPoint(120)),
            measure -> List.of(dwellPoint()),
            false,
            evaluator);

    RapidStagger.Candidate best = search.improved().orElseThrow();
    assertEquals(150, best.dwell().orElseThrow().seconds());
    assertTrue(best.dwell().orElseThrow().turnback());
    assertEquals(Map.of(), best.shift(), "折返加长不平移");
    assertEquals(
        List.of(120, 150), evaluator.dwells.stream().map(RapidStagger.Dwell::seconds).toList());
    assertTrue(evaluator.history.isEmpty(), "错开了就不再平移");
    assertEquals(2, search.tried());
  }

  /** 加长折返错不开时照常往下：平移、中途加停接着试，几样里取最好的。 */
  @Test
  void aTurnbackThatIsNotEnoughFallsThroughToShifting() {
    FakeEvaluator evaluator =
        new FakeEvaluator(shift -> shift.get("G") == 20 ? 0L : 90L).dwelling((shift, dwell) -> 60L);

    RapidStagger.Search search =
        RapidStagger.search(
            Map.of("G", 40),
            base(100L),
            measure -> List.of(turnbackPoint(120)),
            measure -> List.of(dwellPoint()),
            false,
            evaluator);

    assertEquals(Map.of("G", 20), search.improved().orElseThrow().shift());
    assertEquals(
        RapidStagger.TURNBACK_MARGINS.size(), evaluator.dwells.size(), "每档余量各试一次；平移错开后不再中途加停");
  }

  /** 原表已放宽：只试加长折返，不平移、不中途加停。 */
  @Test
  void aRelaxedOriginalOnlyTriesTheTurnback() {
    FakeEvaluator evaluator =
        new FakeEvaluator(shift -> 0L).dwelling((shift, dwell) -> dwell.turnback() ? 50L : 0L);

    RapidStagger.Search search =
        RapidStagger.search(
            Map.of("G", 40),
            base(100L),
            measure -> List.of(turnbackPoint(120)),
            measure -> List.of(dwellPoint()),
            true,
            evaluator);

    assertEquals(50L, search.improved().orElseThrow().measure().seconds());
    assertTrue(evaluator.history.isEmpty());
    assertTrue(evaluator.dwells.stream().allMatch(RapidStagger.Dwell::turnback));
  }

  /** 其余都一样时用车少的优先：能不加车就不加车。 */
  @Test
  void fewerVehiclesWinATie() {
    assertTrue(
        RapidStagger.ORDER.compare(ranked(0L, 0L, 0L, 0, 18, 50), ranked(0L, 0L, 0L, 0, 19, 50))
            < 0);
  }

  /** 多用的一列车在额度内，就是为了换一张运行时残余更少的表：残余排在用车之前。 */
  @Test
  void aCleanerTableIsWorthOneMoreVehicle() {
    assertTrue(
        RapidStagger.ORDER.compare(ranked(0L, 0L, 0L, 0, 19, 0), ranked(0L, 0L, 0L, 50, 18, 0))
            < 0);
  }

  /** 全网损失排第一：快车不被卡，却靠表里让车等出来的，不比被卡少一点、让车也少的好。 */
  @Test
  void waitingInTheTableCountsAsLost() {
    RapidStagger.Candidate converted = ranked(0L, 200L, 500L, 0, 18, 0);
    RapidStagger.Candidate clean = ranked(30L, 0L, 0L, 0, 19, 0);

    assertEquals(200L, converted.rapidLost());
    assertEquals(500L, converted.networkLost());
    assertTrue(RapidStagger.ORDER.compare(clean, converted) < 0);
  }

  /** 快车损失少了、全网损失多了：是拿普通车往后推换来的，不算比原表好。 */
  @Test
  void pushingLocalsBackIsNotAnImprovement() {
    RapidStagger.Candidate original = ranked(100L, 0L, 0L, 0, 18, 0);

    assertFalse(ranked(0L, 0L, 300L, 0, 18, 0).betterThan(original), "普通车多等 300s 换快车少卡 100s");
    assertTrue(ranked(0L, 0L, 80L, 0, 18, 0).betterThan(original));
    assertFalse(ranked(100L, 0L, 0L, 0, 18, 0).betterThan(original), "快车没有更好");
    assertFalse(ranked(0L, 0L, 0L, 30, 18, 0).betterThan(original), "冲突没写进表、留到运行时让，同样是把普通车往后推");
  }

  /**
   * 刚好错开的一档往往只是把被卡改成了表里的等待：错开之后照样往长试，挑全网损失最少的，到 0 才停。
   *
   * <p>120 被卡 80；150 不被卡，但快车在表里等 200、全网等 500；180 干净。
   */
  @Test
  void aMarginalTurnbackLosesToALongerCleanOne() {
    FakeEvaluator evaluator =
        new FakeEvaluator(shift -> 500L)
            .detailing(
                (shift, dwell) ->
                    switch (dwell.seconds()) {
                      case 120 -> new long[] {80L, 0L, 0L};
                      case 150 -> new long[] {0L, 200L, 500L};
                      default -> new long[] {0L, 0L, 0L};
                    });

    RapidStagger.Search search =
        RapidStagger.search(
            Map.of("G", 40),
            base(100L),
            measure -> List.of(turnbackPoint(120)),
            measure -> List.of(dwellPoint()),
            false,
            evaluator);

    assertEquals(180, search.improved().orElseThrow().dwell().orElseThrow().seconds());
    assertEquals(
        List.of(120, 150, 180),
        evaluator.dwells.stream().map(RapidStagger.Dwell::seconds).toList());
    assertTrue(evaluator.history.isEmpty(), "快车已不被卡，不再平移");
  }

  /**
   * 排在最前的候选不一定比原表好：最终答案只从比原表好的候选里取。
   *
   * <p>原表被卡 80、全网等 100。平移 20 全网只损失 90，可快车在表里等了 90，比原表的 80 还多；平移 10 快车损失 50、全网 100，才是改进。
   */
  @Test
  void theAnswerComesFromCandidatesBetterThanTheOriginal() {
    FakeEvaluator evaluator =
        new FakeEvaluator(shift -> 0L)
            .shiftDetailing(
                shift ->
                    shift.get("G") == 10 ? new long[] {50L, 0L, 50L} : new long[] {0L, 90L, 90L});
    RapidStagger.Candidate original =
        new RapidStagger.Candidate(
            Map.of(),
            Optional.empty(),
            TimetableBuildResult.failure("原表", List.of()),
            outcome(true, false, 100, 10, 0, 0, 100L),
            measure(80L, 1, List.of()));

    RapidStagger.Search search =
        RapidStagger.search(
            Map.of("G", 30),
            original,
            measure -> List.of(),
            measure -> List.of(),
            false,
            evaluator);

    assertEquals(Map.of("G", 10), search.improved().orElseThrow().shift());
  }

  /**
   * 加长点：被拖住的快车班次接的是在它起点终到的反方向快车时，就在那条快车上加长折返；秒数取这条快车各班被拖住的最大值，取整到 10 秒。
   *
   * <p>RAPID_N 从 NTA 出发，N1、N2 接 RAPID_O 终到 NTA（另一股道、停靠没带站点 code，按站台组认同一站）的车，于是加在 RAPID_O 的终到停靠；
   * 秒数只看接 RAPID_O 的 N1、N2，取 114 → 120。
   *
   * <p>不给的：N3 是车库出车（没有上一班），被拖住 200 秒也不参与定秒数——加长折返推不动它；N4–N6 接的是同在 NTA 终到的普通车
   * LOCAL_O，次数更多也不算——加长普通车的折返就是把普通车往后推； RAPID_X 上一班是 RAPID_N，但 RAPID_N 不在 RAPID_X 的起点终到。
   */
  @Test
  void theTurnbackGoesToTheReverseRapidEndingWhereTheCaughtOneStarts() {
    UUID north = TimetableTestFixtures.routeId("RAPID_N");
    UUID south = TimetableTestFixtures.routeId("RAPID_O");
    UUID lonely = TimetableTestFixtures.routeId("RAPID_X");
    UUID local = TimetableTestFixtures.routeId("LOCAL_O");
    TimetableConflictChecker.RouteProfile northProfile =
        profile(
            north,
            "RAPID_N",
            List.of(
                stationStop(0, "NTA", "OP:S:NTA:1", 0, 0),
                stationStop(1, "HHU", "OP:S:HHU:1", 300, 320)));
    TimetableConflictChecker.RouteProfile southProfile =
        profile(
            south,
            "RAPID_O",
            List.of(
                stationStop(0, "HHU", "OP:S:HHU:2", 0, 0),
                new TimetableStop(
                    1,
                    Optional.empty(),
                    Optional.of("OP:S:NTA:2"),
                    300,
                    300,
                    org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType.STOP)));
    TimetableConflictChecker.RouteProfile lonelyProfile =
        profile(
            lonely,
            "RAPID_X",
            List.of(
                stationStop(0, "ABC", "OP:S:ABC:1", 0, 0),
                stationStop(1, "HHU", "OP:S:HHU:3", 300, 300)));
    RapidStagger.Measure measure =
        measure(
            245L,
            3,
            List.of(
                new CorridorCatchUp.Caught("N1", north, 0, 0, 101, UUID.randomUUID()),
                new CorridorCatchUp.Caught("N2", north, 720, 0, 114, UUID.randomUUID()),
                new CorridorCatchUp.Caught("N3", north, 1440, 0, 200, UUID.randomUUID()),
                new CorridorCatchUp.Caught("N4", north, 2160, 0, 10, UUID.randomUUID()),
                new CorridorCatchUp.Caught("N5", north, 2880, 0, 10, UUID.randomUUID()),
                new CorridorCatchUp.Caught("N6", north, 3600, 0, 10, UUID.randomUUID()),
                new CorridorCatchUp.Caught("X1", lonely, 0, 0, 30, UUID.randomUUID())));

    List<RapidStagger.Dwell> points =
        RapidStagger.turnbackPoints(
            measure,
            Map.of("N1", south, "N2", south, "X1", north, "N4", local, "N5", local, "N6", local),
            Map.of(
                north,
                northProfile,
                south,
                southProfile,
                lonely,
                lonelyProfile,
                local,
                profile(
                    local,
                    "LOCAL_O",
                    List.of(
                        stationStop(0, "HHU", "OP:S:HHU:4", 0, 0),
                        stationStop(1, "NTA", "OP:S:NTA:3", 400, 400)))),
            Map.of(north, "RAPID_N", south, "RAPID_O", lonely, "RAPID_X"),
            java.util.Set.of(north, south, lonely));

    assertEquals(1, points.size(), points::toString);
    RapidStagger.Dwell point = points.get(0);
    assertEquals(south, point.routeId());
    assertEquals(1, point.stopIndex(), "终到停靠");
    assertEquals(120, point.seconds(), "秒数只看接这条快车的班次");
    assertTrue(point.turnback());
  }

  /**
   * 加长折返的候选排在所有平移之前时，平移阶段照样围着平移里最好的那档细试。
   *
   * <p>折返被卡 30（不到 0，接着平移）；平移 20 被卡 40、其余 50：细试 15、25。
   */
  @Test
  void aTurnbackCandidateDoesNotStopTheShiftRefinement() {
    FakeEvaluator evaluator =
        new FakeEvaluator(shift -> shift.get("G") == 20 ? 40L : 50L)
            .dwelling((shift, dwell) -> dwell.turnback() ? 30L : 1000L);

    RapidStagger.search(
        Map.of("G", 40),
        base(100L),
        measure -> List.of(turnbackPoint(120)),
        measure -> List.of(),
        false,
        evaluator);

    assertEquals(List.of(10, 20, 30, 15, 25), evaluator.shifts);
  }

  /** 折返由 --turnaround 固定时不给加长点：终到停站改多少，折返都不变。 */
  @Test
  void aFixedTurnaroundHasNoTurnbackPoints() {
    UUID north = TimetableTestFixtures.routeId("RAPID_N");
    UUID south = TimetableTestFixtures.routeId("RAPID_O");
    Timetable table = dutyTable(List.of(trip("O1", south, 0), trip("N1", north, 400)));
    Map<UUID, TimetableConflictChecker.RouteProfile> profiles =
        Map.of(
            north,
            profile(
                north,
                "RAPID_N",
                List.of(
                    stationStop(0, "NTA", "OP:S:NTA:1", 0, 0),
                    stationStop(1, "HHU", "OP:S:HHU:1", 300, 320))),
            south,
            profile(
                south,
                "RAPID_O",
                List.of(
                    stationStop(0, "HHU", "OP:S:HHU:2", 0, 0),
                    stationStop(1, "NTA", "OP:S:NTA:2", 300, 300))));
    Map<UUID, String> codes = Map.of(north, "RAPID_N", south, "RAPID_O");
    RapidStagger.Measure caught =
        measure(
            90L,
            1,
            List.of(new CorridorCatchUp.Caught("N1", north, 400, 0, 90, UUID.randomUUID())));

    assertEquals(
        1,
        RapidStagger.turnbackSource(
                TurnaroundTable.none(), table, profiles, codes, java.util.Set.of(north, south))
            .apply(caught)
            .size());
    assertTrue(
        RapidStagger.turnbackSource(
                TurnaroundTable.fixed(60), table, profiles, codes, java.util.Set.of(north, south))
            .apply(caught)
            .isEmpty());
  }

  /** 每一班的上一班：同一辆车在交路里排在它前面的那一班。 */
  @Test
  void previousRoutesFollowTheDutyChain() {
    UUID north = TimetableTestFixtures.routeId("RAPID_N");
    UUID south = TimetableTestFixtures.routeId("RAPID_O");
    Timetable table = dutyTable(List.of(trip("O1", south, 0), trip("N1", north, 400)));

    assertEquals(Map.of("N1", south), RapidStagger.previousRoutes(table));
  }

  /** 表里另有快车等待时，报告一并写出。 */
  @Test
  void theReportAddsTheRapidsWaitingInTheTable() {
    assertEquals(
        "快车被卡（成品表实测）：无；表里另有快车让车等待共 40s",
        RapidStagger.describe(measure(0L, 0, List.of(), 40L), Map.of()));
  }

  /** 原表已放宽：没找到时说明平移与中途加停为什么没试。 */
  @Test
  void aRelaxedSearchExplainsWhatWasNotTried() {
    assertEquals(
        "快车错峰：目标间隔本身排不开（已放宽），平移与中途加停要先把间隔排开才比得了；也没有可加长的原地折返端（或折返时间由 --turnaround 固定），这次没有搜，"
            + "保持原表（被卡 100s），用时 2s",
        RapidStagger.describeSearch(base(100L), Optional.empty(), 0, 2_000L, true));
    assertEquals(
        "快车错峰：目标间隔本身排不开（已放宽），平移与中途加停要先把间隔排开才比得了；只试了原地折返端加长折返 11 档，没有在目标间隔下排得开、比原表好的，"
            + "保持原表（被卡 100s），用时 2s",
        RapidStagger.describeSearch(base(100L), Optional.empty(), 11, 2_000L, true));
  }

  /** 报告写明折返加长与多用的车。 */
  @Test
  void theReportNamesTheTurnbackAndTheExtraVehicle() {
    RapidStagger.Candidate chosen =
        new RapidStagger.Candidate(
            Map.of(),
            Optional.of(turnbackPoint(180)),
            TimetableBuildResult.failure("只看报告", List.of()),
            outcome(true, false, 100, 19, 0, 0, 900L),
            measure(0L, 0, List.of(), 2L));
    RapidStagger.Candidate original =
        new RapidStagger.Candidate(
            Map.of(),
            Optional.empty(),
            TimetableBuildResult.failure("原表", List.of()),
            outcome(true, false, 100, 18, 0, 0, 1200L),
            measure(12805L, 116, List.of(), 0L));

    String line = RapidStagger.describeSearch(original, Optional.of(chosen), 3, 61_000L, false);

    assertEquals(
        "快车错峰：RAPID_O 在 OP:S:NTA:2 终到折返多停 180s，快车被卡 12805s → 0s（116 → 0 班），快车让车等待 0s → 2s，"
            + "全网让车等待 1200s → 900s，高峰 18 → 19 列车，试了 3 个位置，用时 61s",
        line);
  }

  /** 只用来比排序的候选：被卡、快车等待、全网等待、残余、高峰、让车处数。 */
  private static RapidStagger.Candidate ranked(
      long caught, long held, long waited, int absorbable, int peak, int yields) {
    return new RapidStagger.Candidate(
        Map.of(),
        Optional.empty(),
        TimetableBuildResult.failure("只比排序", List.of()),
        outcome(true, false, 100, peak, absorbable, yields, waited),
        measure(caught, caught > 0 ? 1 : 0, List.of(), held));
  }

  private static TimetableTrip trip(String code, UUID route, int departure) {
    return new TimetableTrip(
        TimetableTestFixtures.routeId("trip-" + code),
        TimetableTestFixtures.routeId("table"),
        route,
        0,
        code,
        departure,
        Optional.empty());
  }

  /** 一辆车依次跑这几班的表。 */
  private static Timetable dutyTable(List<TimetableTrip> trips) {
    UUID tableId = TimetableTestFixtures.routeId("table");
    return new Timetable(
        tableId,
        TimetableTestFixtures.routeId("company"),
        TimetableTestFixtures.routeId("operator"),
        TimetableTestFixtures.routeId("line"),
        "FIXTURE",
        "夹具",
        TimetableStatus.DRAFT,
        java.time.ZoneId.of("UTC"),
        0,
        86_400,
        List.of(),
        trips,
        List.of(
            new VehicleDuty(
                TimetableTestFixtures.routeId("duty"),
                tableId,
                0,
                "D001",
                "OP:D:DEPOT:1",
                "OP:D:DEPOT:1",
                Optional.empty(),
                Optional.empty(),
                trips.stream().map(TimetableTrip::id).toList(),
                0,
                600,
                600,
                VehicleDuty.CloseReason.HORIZON_END)),
        Optional.empty(),
        java.time.Instant.EPOCH,
        java.time.Instant.EPOCH);
  }

  private static RapidStagger.Dwell turnbackPoint(int seconds) {
    return new RapidStagger.Dwell(
        TimetableTestFixtures.routeId("RAPID_O"), "RAPID_O", 1, "OP:S:NTA:2", seconds, true);
  }

  private static TimetableConflictChecker.RouteProfile profile(
      UUID id, String code, List<TimetableStop> stops) {
    return new TimetableConflictChecker.RouteProfile(id, code, stops, List.of(), List.of());
  }

  private static TimetableStop stationStop(
      int sequence, String station, String node, int arrival, int departure) {
    return new TimetableStop(
        sequence,
        Optional.of(station),
        Optional.of(node),
        arrival,
        departure,
        org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType.STOP);
  }

  private static RapidStagger.Candidate base(long caught) {
    return new RapidStagger.Candidate(
        Map.of(),
        Optional.empty(),
        TimetableBuildResult.failure("原表", List.of()),
        outcome(true, false, 100, 10, 0, 0),
        measure(caught, caught > 0 ? 1 : 0, List.of()));
  }

  private static RapidStagger.Dwell dwellPoint() {
    return new RapidStagger.Dwell(
        TimetableTestFixtures.routeId("RAPID"), "RAPID", 2, "OP:S:H:1", 0);
  }

  /**
   * 假的编表：按平移（与加停）给出被卡秒数；负数表示这个候选不可用。
   *
   * <p>要细到快车等待与全网等待时用 {@code detailing}/{@code shiftDetailing}，给出 {被卡, 快车等待, 全网等待}。
   */
  private static final class FakeEvaluator implements RapidStagger.Evaluator {
    private final java.util.function.ToLongFunction<Map<String, Integer>> caughtByShift;
    private java.util.function.ToLongBiFunction<Map<String, Integer>, RapidStagger.Dwell>
        caughtByDwell = (shift, dwell) -> 1000L;
    private java.util.function.BiFunction<Map<String, Integer>, RapidStagger.Dwell, long[]>
        dwellDetail;
    private java.util.function.Function<Map<String, Integer>, long[]> shiftDetail;
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

    FakeEvaluator detailing(
        java.util.function.BiFunction<Map<String, Integer>, RapidStagger.Dwell, long[]> detail) {
      this.dwellDetail = detail;
      return this;
    }

    FakeEvaluator shiftDetailing(java.util.function.Function<Map<String, Integer>, long[]> detail) {
      this.shiftDetail = detail;
      return this;
    }

    @Override
    public Optional<RapidStagger.Candidate> shift(Map<String, Integer> shift) {
      history.add(Map.copyOf(shift));
      shifts.add(shift.values().stream().reduce((first, second) -> second).orElse(0));
      if (shiftDetail != null) {
        return candidate(shift, Optional.empty(), shiftDetail.apply(shift));
      }
      return candidate(shift, Optional.empty(), caughtByShift.applyAsLong(shift));
    }

    @Override
    public Optional<RapidStagger.Candidate> dwell(
        Map<String, Integer> shift, RapidStagger.Dwell dwell) {
      dwells.add(dwell);
      if (dwellDetail != null) {
        return candidate(shift, Optional.of(dwell), dwellDetail.apply(shift, dwell));
      }
      return candidate(shift, Optional.of(dwell), caughtByDwell.applyAsLong(shift, dwell));
    }

    private static Optional<RapidStagger.Candidate> candidate(
        Map<String, Integer> shift, Optional<RapidStagger.Dwell> dwell, long caught) {
      return candidate(shift, dwell, new long[] {caught, 0L, 0L});
    }

    private static Optional<RapidStagger.Candidate> candidate(
        Map<String, Integer> shift, Optional<RapidStagger.Dwell> dwell, long[] detail) {
      long caught = detail[0];
      if (caught < 0) {
        return Optional.empty();
      }
      return Optional.of(
          new RapidStagger.Candidate(
              shift,
              dwell,
              TimetableBuildResult.failure("候选", List.of()),
              outcome(true, false, 100, 10, 0, 0, detail[2]),
              measure(caught, caught > 0 ? 1 : 0, List.of(), detail[1])));
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
        outcome(true, false, 100, 10, 0, yields),
        measure(caught, caught > 0 ? 1 : 0, List.of()));
  }

  private static RapidStagger.Outcome outcome(
      boolean success, boolean relaxed, int trips, int peak) {
    return outcome(success, relaxed, trips, peak, 0, 0);
  }

  private static RapidStagger.Outcome outcome(
      boolean success, boolean relaxed, int trips, int peak, int absorbable, int yields) {
    return outcome(success, relaxed, trips, peak, absorbable, yields, 0L);
  }

  private static RapidStagger.Outcome outcome(
      boolean success,
      boolean relaxed,
      int trips,
      int peak,
      int absorbable,
      int yields,
      long waited) {
    return new RapidStagger.Outcome(success, relaxed, trips, peak, absorbable, yields, waited);
  }

  private static RapidStagger.Measure measure(
      long seconds, int trips, List<CorridorCatchUp.Caught> caught) {
    return measure(seconds, trips, caught, 0L);
  }

  private static RapidStagger.Measure measure(
      long seconds, int trips, List<CorridorCatchUp.Caught> caught, long held) {
    return new RapidStagger.Measure(seconds, trips, caught, held);
  }
}
