package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 残余分类：运行时能不能让、在哪让、让多久。
 *
 * <p>成功判据从"零冲突"改成"零不可吸收残余"之后，这个判决直接决定一张表发不发得出去，所以六个判决各钉一例， 并钉住"同一份输入两次分类结果相同"——build 与 publish
 * 重检各算一遍，两边不一致就是 bug。
 */
class ConflictAbsorptionTest {

  private static final String STUB = "OP:S:STUB";
  private static final String WIDE = "OP:S:WIDE";
  private static final int SEPARATION = 30;
  private static final int MAX_WAIT = 300;

  /** 容量：STUB 一股道、WIDE 两股道。 */
  private static final TimetableConflictChecker.GraphIndex INDEX =
      new TimetableConflictChecker.GraphIndex(null, Map.of(STUB, 1, WIDE, 2), Map.of());

  /** 一方是已发布邻表：路权先到先得，我挪不动它。 */
  @Test
  void neighbourConflictsAreNeverAbsorbable() {
    TimetableConflictChecker.Conflict conflict =
        new TimetableConflictChecker.Conflict(
            TimetableConflictChecker.Kind.TRACK,
            "edge:A~B",
            "NB-001",
            "RA-001",
            0,
            60,
            50,
            110,
            Optional.of("FTAS/SURC/DS"),
            Optional.empty());

    ConflictAbsorption.Residual residual = only(conflict);

    assertEquals(ConflictAbsorption.Verdict.EXTERNAL, residual.verdict());
    assertFalse(residual.absorbable());
  }

  /** 让车点是容量 1 的端点：在这里等就是堵死岔线。 */
  @Test
  void waitingOnACapacityOneTerminalIsNotAbsorbable() {
    ConflictAbsorption.Residual onGroup = only(conflictOn("platform-group:" + STUB, 60, 50));
    assertEquals(ConflictAbsorption.Verdict.STUB_TERMINAL, onGroup.verdict());

    ConflictAbsorption.Residual onTrack = only(conflictOn("platform:" + STUB + ":3", 60, 50));
    assertEquals(ConflictAbsorption.Verdict.STUB_TERMINAL, onTrack.verdict(), "具体股道同样算");

    ConflictAbsorption.Residual onBridge =
        only(conflictOn("single:bridge:" + STUB + ":3~SWITCHER:1:2:3", 60, 50));
    assertEquals(ConflictAbsorption.Verdict.STUB_TERMINAL, onBridge.verdict(), "端点的进站单线同样算");
  }

  /** 预计等待超过单步上限：与让车修复同一个预算。 */
  @Test
  void waitBeyondMaxWaitIsNotAbsorbable() {
    // first 到 600 秒离开、second 200 秒就要进：等 600 + 30 − 200 = 430 s。
    ConflictAbsorption.Residual residual = only(conflictOn("edge:A~B", 600, 200));

    assertEquals(ConflictAbsorption.Verdict.OVER_MAX_WAIT, residual.verdict());
    assertEquals(430, residual.waitSeconds());
  }

  /** 双线区间上等一小会儿：运行时天天在做，可吸收。 */
  @Test
  void shortWaitOnAnOrdinaryResourceIsAbsorbable() {
    ConflictAbsorption.Residual residual = only(conflictOn("edge:A~B", 60, 50));

    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, residual.verdict());
    assertEquals(40, residual.waitSeconds(), "60 + 30 − 50");
    assertTrue(residual.absorbable());
  }

  /** 单线对向本身可吸收：运行时按区段互斥，后车在区段外等。 */
  @Test
  void singleLineOppositionIsAbsorbableWhenItIsNotAStub() {
    ConflictAbsorption.Residual residual =
        only(conflictOn("single:bridge:" + WIDE + ":1~" + WIDE + ":2", 60, 50));

    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, residual.verdict());
  }

  /** 让车点在那一刻站满了：没地方等。 */
  @Test
  void noRoomAtTheWaitingPointIsNotAbsorbable() {
    // WIDE 两股道，两辆车已经在 [0, 500) 待命；后车 RA-001 的起点就是 WIDE。
    TimetableConflictChecker.Platform platform =
        new TimetableConflictChecker.Platform(WIDE + ":1", WIDE, false);
    TimetableOccupancyProjector.Occupancy occupancy =
        new TimetableOccupancyProjector.Occupancy(
            List.of(),
            List.of(
                new TimetableConflictChecker.Stay("D001", platform, 0, 500, Optional.empty()),
                new TimetableConflictChecker.Stay("D002", platform, 0, 500, Optional.empty())));

    List<ConflictAbsorption.Residual> residuals =
        ConflictAbsorption.classify(
            report(conflictOn("edge:A~B", 60, 50)),
            timetableWithOriginAt(WIDE),
            occupancy,
            INDEX,
            SEPARATION,
            MAX_WAIT);

    assertEquals(ConflictAbsorption.Verdict.NO_WAITING_CAPACITY, residuals.get(0).verdict());
    assertEquals(Optional.of(WIDE), residuals.get(0).waitingPoint());
  }

  /** 让车点还有空位时同样的冲突就是可吸收的——两条用例只差站台上有几辆车。 */
  @Test
  void roomAtTheWaitingPointMakesItAbsorbable() {
    TimetableConflictChecker.Platform platform =
        new TimetableConflictChecker.Platform(WIDE + ":1", WIDE, false);
    TimetableOccupancyProjector.Occupancy occupancy =
        new TimetableOccupancyProjector.Occupancy(
            List.of(),
            List.of(new TimetableConflictChecker.Stay("D001", platform, 0, 500, Optional.empty())));

    List<ConflictAbsorption.Residual> residuals =
        ConflictAbsorption.classify(
            report(conflictOn("edge:A~B", 60, 50)),
            timetableWithOriginAt(WIDE),
            occupancy,
            INDEX,
            SEPARATION,
            MAX_WAIT);

    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, residuals.get(0).verdict());
  }

  /** 同一份输入分两次，判决与顺序都必须一致：build 与 publish 重检各算一遍，不一致就是 bug。 */
  @Test
  void classificationIsDeterministic() {
    TimetableConflictChecker.Report report =
        new TimetableConflictChecker.Report(
            List.of(
                conflictOn("edge:A~B", 60, 50),
                conflictOn("platform-group:" + STUB, 60, 50),
                conflictOn("edge:C~D", 900, 100)));

    List<ConflictAbsorption.Residual> first =
        ConflictAbsorption.classify(report, null, null, INDEX, SEPARATION, MAX_WAIT);
    List<ConflictAbsorption.Residual> second =
        ConflictAbsorption.classify(report, null, null, INDEX, SEPARATION, MAX_WAIT);

    assertEquals(first, second);
    assertEquals(
        List.of(
            ConflictAbsorption.Verdict.ABSORBABLE,
            ConflictAbsorption.Verdict.STUB_TERMINAL,
            ConflictAbsorption.Verdict.OVER_MAX_WAIT),
        first.stream().map(ConflictAbsorption.Residual::verdict).toList());
  }

  /** 计数与筛选：报告与成功判据都靠它们。 */
  @Test
  void countingAndFilteringSplitTheTwoKinds() {
    List<ConflictAbsorption.Residual> residuals =
        ConflictAbsorption.classify(
            new TimetableConflictChecker.Report(
                List.of(
                    conflictOn("edge:A~B", 60, 50),
                    conflictOn("edge:C~D", 60, 50),
                    conflictOn("platform-group:" + STUB, 60, 50))),
            null,
            null,
            INDEX,
            SEPARATION,
            MAX_WAIT);

    assertEquals(2, ConflictAbsorption.absorbable(residuals).size());
    assertEquals(1, ConflictAbsorption.unabsorbable(residuals).size());
    assertEquals(
        Map.of(
            ConflictAbsorption.Verdict.ABSORBABLE, 2,
            ConflictAbsorption.Verdict.STUB_TERMINAL, 1),
        ConflictAbsorption.countByVerdict(residuals));
  }

  // ------------------------------------------------------------------ 夹具

  /**
   * 冲突落在容量 1 端点的进站单线上，但后车还停在有容量的起点没发车：不是堵死岔线，可吸收。
   *
   * <p>让车是"整趟延后"——后车在自己的起点多站几秒，并不会开到冲突点去等。实测 WS 在 120 s 间隔下剩的 五处残余全是这个形态（撞在 CHT 进站单线上、后车停在林湾车库、等
   * 1–10 秒），按资源一刀切会把它们判成 不可吸收，白白卡住更密的间隔。
   */
  @Test
  void conflictOnAStubApproachIsAbsorbableWhenTheFollowerWaitsElsewhere() {
    TimetableConflictChecker.Conflict conflict =
        conflictOn("single:bridge:" + STUB + ":3~SWITCHER:1:2:3", 60, 50);

    // 后车 RA-001 的起点是两股道的 WIDE：它在那里等，冲突落在 STUB 的进站单线上不该判死。
    ConflictAbsorption.Residual residual =
        ConflictAbsorption.classify(
                report(conflict),
                TimetableTestFixtures.singleTripTimetable("RA-001", WIDE + ":1"),
                new TimetableOccupancyProjector.Occupancy(List.of(), List.of()),
                INDEX,
                SEPARATION,
                MAX_WAIT)
            .get(0);

    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, residual.verdict());
    assertEquals(Optional.of(WIDE), residual.waitingPoint());
    assertEquals(40, residual.waitSeconds(), "60 + 30 − 50");
  }

  /** 后车的起点<b>就是</b>那个单股道端点：这时确实只能在那里等，仍判不可吸收。 */
  @Test
  void conflictOnAStubApproachStaysUnabsorbableWhenTheFollowerWaitsThere() {
    TimetableConflictChecker.Conflict conflict =
        conflictOn("single:bridge:" + STUB + ":3~SWITCHER:1:2:3", 60, 50);

    ConflictAbsorption.Residual residual =
        ConflictAbsorption.classify(
                report(conflict),
                TimetableTestFixtures.singleTripTimetable("RA-001", STUB + ":3"),
                new TimetableOccupancyProjector.Occupancy(List.of(), List.of()),
                INDEX,
                SEPARATION,
                MAX_WAIT)
            .get(0);

    assertEquals(ConflictAbsorption.Verdict.STUB_TERMINAL, residual.verdict());
    assertEquals(Optional.of(STUB), residual.waitingPoint());
  }

  /**
   * 后车是<b>出库走行</b>：车还在库里没发出来，让车点是车库、容量不限，冲突落在岔线端点的进站单线上也不该判死。
   *
   * <p>这一类占用的 code 是 {@code Dxxx-CREATE}，{@code Context} 给它记的让车点是空串。空串必须与"判不出"
   * 分开——两者都当成判不出的话，每一处这种残余都会退回按资源保守判，正好把本判据要修的那一类原样留下。 实测 WS 在 120 s 间隔下有 26 处残余走的就是这条退路。
   */
  @Test
  void aFollowerStillInTheDepotIsAbsorbableEvenOnAStubApproach() {
    TimetableConflictChecker.Conflict conflict =
        conflictOn("single:bridge:" + STUB + ":3~SWITCHER:1:2:3", 60, 50, "D001-CREATE");

    ConflictAbsorption.Residual residual =
        ConflictAbsorption.classify(
                report(conflict),
                TimetableTestFixtures.singleTripTimetable("RA-001", WIDE + ":1", "D001"),
                new TimetableOccupancyProjector.Occupancy(List.of(), List.of()),
                INDEX,
                SEPARATION,
                MAX_WAIT)
            .get(0);

    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, residual.verdict());
    assertEquals(Optional.of(""), residual.waitingPoint(), "空串＝车库，不是判不出");
  }

  /**
   * 后车是<b>待命</b>（code 就是交路号）：车已经停在某个站台上，不在车库。
   *
   * <p>这时让车点判不出具体是哪个站台，必须退回按资源保守判——冲突落在容量 1 的端点上就仍判不可吸收。 把待命也当成"在车库等"会把这类判成可吸收，运行时那辆车真的会堵死岔线。
   */
  @Test
  void aFollowerOnAPlatformIsNotTreatedAsWaitingInTheDepot() {
    TimetableConflictChecker.Conflict conflict =
        conflictOn("single:bridge:" + STUB + ":3~SWITCHER:1:2:3", 60, 50, "D001");

    ConflictAbsorption.Residual residual =
        ConflictAbsorption.classify(
                report(conflict),
                TimetableTestFixtures.singleTripTimetable("RA-001", WIDE + ":1", "D001"),
                new TimetableOccupancyProjector.Occupancy(List.of(), List.of()),
                INDEX,
                SEPARATION,
                MAX_WAIT)
            .get(0);

    assertEquals(ConflictAbsorption.Verdict.STUB_TERMINAL, residual.verdict());
    assertEquals(Optional.empty(), residual.waitingPoint(), "判不出，不是车库");
  }

  /**
   * 相位第三层用的重载：手上没有成品表，但每条流从哪个站台组始发是知道的。
   *
   * <p>第三层的职责就是把不可吸收的那些消掉，它挑 δ 用的尺子必须与最终的成功判据同一把。此前它只能传 {@code null}，于是每一处都落到按资源保守判——比 build 严，挑出来的
   * δ 未必是 build 眼里最好的那个。
   */
  @Test
  void waitingPointsOnlyContextUsesTheSameCriterionAsTheBuild() {
    TimetableConflictChecker.Conflict conflict =
        conflictOn("single:bridge:" + STUB + ":3~SWITCHER:1:2:3", 60, 50, "full→north#0");

    assertEquals(
        ConflictAbsorption.Verdict.ABSORBABLE,
        ConflictAbsorption.classify(
                report(conflict), Map.of("full→north#0", WIDE), INDEX, SEPARATION, MAX_WAIT)
            .get(0)
            .verdict(),
        "知道后车从两股道的 WIDE 始发，与成品表那条路径同一个结论");

    assertEquals(
        ConflictAbsorption.Verdict.STUB_TERMINAL,
        ConflictAbsorption.classify(report(conflict), Map.of(), INDEX, SEPARATION, MAX_WAIT)
            .get(0)
            .verdict(),
        "判不出从哪来就退回按资源保守判");
  }

  private static ConflictAbsorption.Residual only(TimetableConflictChecker.Conflict conflict) {
    return ConflictAbsorption.classify(report(conflict), null, null, INDEX, SEPARATION, MAX_WAIT)
        .get(0);
  }

  private static TimetableConflictChecker.Report report(
      TimetableConflictChecker.Conflict conflict) {
    return new TimetableConflictChecker.Report(List.of(conflict));
  }

  /** 内部冲突：后车恒为 second，等待 = firstTo + 裕量 − secondFrom。 */
  private static TimetableConflictChecker.Conflict conflictOn(
      String resource, int firstTo, int secondFrom) {
    return conflictOn(resource, firstTo, secondFrom, "RA-001");
  }

  /** 同上，另外指定后车的 code：出入库走行是 {@code Dxxx-CREATE / -RETURN}，待命是交路号。 */
  private static TimetableConflictChecker.Conflict conflictOn(
      String resource, int firstTo, int secondFrom, String second) {
    return new TimetableConflictChecker.Conflict(
        resource.startsWith("platform")
            ? TimetableConflictChecker.Kind.PLATFORM
            : resource.startsWith("single")
                ? TimetableConflictChecker.Kind.SINGLE_LINE
                : TimetableConflictChecker.Kind.TRACK,
        resource,
        "RB-001",
        second,
        0,
        firstTo,
        secondFrom,
        secondFrom + 60,
        Optional.empty(),
        Optional.empty());
  }

  /** 一张只够回答"RA-001 的起点在哪"的表。 */
  private static Timetable timetableWithOriginAt(String group) {
    return TimetableTestFixtures.singleTripTimetable("RA-001", group + ":1");
  }
}
