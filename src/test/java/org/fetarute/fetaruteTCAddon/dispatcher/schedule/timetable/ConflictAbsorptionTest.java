package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/**
 * 残余分类：运行时能不能让、在哪让、让多久。
 *
 * <p>成功判据从"零冲突"改成"零不可吸收残余"之后，这个判决直接决定一张表发不发得出去，所以六个判决各钉一例， 并钉住"同一份输入两次分类结果相同"——相位候选、
 * 完整窗口复核与严格模式取消都要分类，同一输入结论不一致就是 bug。
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
            Optional.of("CO/OP/L2"),
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
        new TimetableConflictChecker.Platform(WIDE + ":2", WIDE, false);
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

  /** 整趟晚发在始发站等待：远处冲突发生时始发站已满，不影响更早的晚发窗口。 */
  @Test
  void aFullOriginAtTheRemoteConflictTimeDoesNotRejectAnEarlierDepartureHold() {
    TimetableConflictChecker.Platform platform =
        new TimetableConflictChecker.Platform(WIDE + ":1", WIDE, false);
    TimetableOccupancyProjector.Occupancy occupancy =
        new TimetableOccupancyProjector.Occupancy(
            List.of(),
            List.of(
                new TimetableConflictChecker.Stay("D001", platform, 900, 1200),
                new TimetableConflictChecker.Stay("D002", platform, 950, 1200)));

    ConflictAbsorption.Residual residual =
        ConflictAbsorption.classify(
                report(conflictOn("edge:REMOTE~NEXT", 980, 1000)),
                timetableWithOriginAt(WIDE),
                occupancy,
                INDEX,
                SEPARATION,
                MAX_WAIT)
            .get(0);

    assertEquals(10, residual.waitSeconds());
    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, residual.verdict());
  }

  /** 晚发窗口开始时有位，但其他车辆在窗口内到站占满，不能继续停在这里等待。 */
  @Test
  void capacityMustRemainAvailableThroughoutTheDepartureHold() {
    TimetableConflictChecker.Platform platform =
        new TimetableConflictChecker.Platform(WIDE + ":1", WIDE, false);
    TimetableOccupancyProjector.Occupancy occupancy =
        new TimetableOccupancyProjector.Occupancy(
            List.of(),
            List.of(
                new TimetableConflictChecker.Stay("D001", platform, 10, 100),
                new TimetableConflictChecker.Stay("D002", platform, 20, 100)));

    ConflictAbsorption.Residual residual =
        ConflictAbsorption.classify(
                report(conflictOn("edge:REMOTE~NEXT", 60, 50)),
                timetableWithOriginAt(WIDE),
                occupancy,
                INDEX,
                SEPARATION,
                MAX_WAIT)
            .get(0);

    assertEquals(40, residual.waitSeconds());
    assertEquals(ConflictAbsorption.Verdict.NO_WAITING_CAPACITY, residual.verdict());
  }

  /** 旧冲突的安全裕量恰在晚发窗口开始时结束，不能替后车判定无处等待。 */
  @Test
  void anEndedConflictDoesNotRejectALaterDepartureHold() {
    Timetable base = TimetableTestFixtures.singleTripTimetable("RA-001", WIDE + ":1");
    TimetableTrip original = base.trips().get(0);
    TimetableTrip moved =
        new TimetableTrip(
            original.id(),
            original.timetableId(),
            original.routeId(),
            original.sequence(),
            original.tripCode(),
            100,
            original.dutyId());
    Timetable table = base.withTripsAndDuties(List.of(moved), List.of());
    TimetableConflictChecker.Platform platform =
        new TimetableConflictChecker.Platform(WIDE + ":1", WIDE, false);
    var occupancy =
        new TimetableOccupancyProjector.Occupancy(
            List.of(),
            List.of(
                new TimetableConflictChecker.Stay("A", platform, 0, 85),
                new TimetableConflictChecker.Stay("B", platform, 30, 85)));

    ConflictAbsorption.Residual residual =
        ConflictAbsorption.classify(
                report(conflictOn("edge:REMOTE~NEXT", 1010, 1000)),
                table,
                occupancy,
                INDEX,
                15,
                MAX_WAIT)
            .get(0);

    assertEquals(25, residual.waitSeconds());
    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, residual.verdict());
  }

  /** 同一辆车的连续到发记录可能重叠，但只能占用一个等待位置。 */
  @Test
  void overlappingStaysOfOneVehicleOnlyConsumeOnePlace() {
    TimetableConflictChecker.Platform platform =
        new TimetableConflictChecker.Platform(WIDE + ":2", WIDE, false);
    TimetableOccupancyProjector.Occupancy occupancy =
        new TimetableOccupancyProjector.Occupancy(
            List.of(),
            List.of(
                new TimetableConflictChecker.Stay("D001", platform, 0, 100),
                new TimetableConflictChecker.Stay("D001", platform, 0, 200)));

    ConflictAbsorption.Residual residual =
        ConflictAbsorption.classify(
                report(conflictOn("edge:REMOTE~NEXT", 60, 50)),
                timetableWithOriginAt(WIDE),
                occupancy,
                INDEX,
                SEPARATION,
                MAX_WAIT)
            .get(0);

    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, residual.verdict());
  }

  /** 发车班次与它所属 duty 的待命是同一辆车，延长这段待命不能再占一个位置。 */
  @Test
  void aVehicleDoesNotNeedAnExtraPlaceToExtendItsOwnStay() {
    Timetable base = TimetableTestFixtures.singleTripTimetable("RA-001", WIDE + ":1", "D001");
    Timetable table =
        base.withTripsAndDuties(
            List.of(base.trips().get(0).withDuty(base.duties().get(0).id())), base.duties());
    TimetableConflictChecker.Platform platform =
        new TimetableConflictChecker.Platform(WIDE + ":1", WIDE, false);
    TimetableOccupancyProjector.Occupancy occupancy =
        new TimetableOccupancyProjector.Occupancy(
            List.of(),
            List.of(
                new TimetableConflictChecker.Stay("D001", platform, 0, 100),
                new TimetableConflictChecker.Stay(
                    "D002",
                    new TimetableConflictChecker.Platform(WIDE + ":2", WIDE, false),
                    0,
                    100)));

    ConflictAbsorption.Residual residual =
        ConflictAbsorption.classify(
                report(conflictOn("edge:REMOTE~NEXT", 60, 50)),
                table,
                occupancy,
                INDEX,
                SEPARATION,
                MAX_WAIT)
            .get(0);

    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, residual.verdict());
  }

  /** 总容量有空位不代表指定股道有空位：固定始发股道被另一辆车占着时不能在那里晚发。 */
  @Test
  void spareCapacityOnAnotherTrackDoesNotFreeTheFixedOriginTrack() {
    TimetableConflictChecker.Platform platform =
        new TimetableConflictChecker.Platform(WIDE + ":1", WIDE, false);
    TimetableOccupancyProjector.Occupancy occupancy =
        new TimetableOccupancyProjector.Occupancy(
            List.of(), List.of(new TimetableConflictChecker.Stay("D001", platform, 0, 100)));

    ConflictAbsorption.Residual residual =
        ConflictAbsorption.classify(
                report(conflictOn("edge:REMOTE~NEXT", 60, 50)),
                timetableWithOriginAt(WIDE),
                occupancy,
                INDEX,
                SEPARATION,
                MAX_WAIT)
            .get(0);

    assertEquals(ConflictAbsorption.Verdict.NO_WAITING_CAPACITY, residual.verdict());
  }

  /** 同一份输入分两次，判决与顺序都必须一致：编表里同一张表会被分类多次，不一致就是 bug。 */
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

  /**
   * 冲突在途中停车点之后：运行时后车在那一站发车前被挡（或在区间信号前减速），不会回到起点去等。
   *
   * <p>起点站已满、途中停车点有空位：让车点是途中停车点，可吸收。按起点判会把双线区间上的跟车判成"始发站无容量"。
   */
  @Test
  void aConflictAfterAnIntermediateStopWaitsThereNotAtTheOrigin() {
    EnRoute fixture = new EnRoute();
    var occupancy = fixture.occupancy(fixture.originFull());

    ConflictAbsorption.Residual residual =
        fixture.classify(conflictOn("edge:MID~END", 210, 200), occupancy);

    assertEquals(Optional.of(MID), residual.waitingPoint());
    assertEquals(40, residual.waitSeconds());
    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, residual.verdict());
  }

  /** 途中停车点的那股道在等待窗口内被别的车占着：没地方等，与起点同一口径判无容量。 */
  @Test
  void anOccupiedIntermediateStopHasNoWaitingCapacity() {
    EnRoute fixture = new EnRoute();
    List<TimetableConflictChecker.Stay> stays = new java.util.ArrayList<>(fixture.originFull());
    stays.add(
        new TimetableConflictChecker.Stay(
            "OTHER", new TimetableConflictChecker.Platform(MID + ":1", MID, false), 155, 400));

    ConflictAbsorption.Residual residual =
        fixture.classify(conflictOn("edge:MID~END", 210, 200), fixture.occupancy(stays));

    assertEquals(Optional.of(MID), residual.waitingPoint());
    assertEquals(ConflictAbsorption.Verdict.NO_WAITING_CAPACITY, residual.verdict());
  }

  /**
   * 前车刚离开这一站、离开时刻加裕量盖住了后车的发车：那是到站时就有的冲突（另有一条记录），不是多等造成的，不能算让车点无容量。
   *
   * <p>运行时后车在这一站多停，只会挡住等待期间要进这股道的车；已经走了的前车不受影响。
   */
  @Test
  void aLeaderThatAlreadyLeftTheStopDoesNotBlockTheWait() {
    EnRoute fixture = new EnRoute();
    List<TimetableConflictChecker.Stay> stays = new java.util.ArrayList<>(fixture.originFull());
    stays.add(
        new TimetableConflictChecker.Stay(
            "LEAD", new TimetableConflictChecker.Platform(MID + ":1", MID, false), 40, 100));

    ConflictAbsorption.Residual residual =
        fixture.classify(conflictOn("edge:MID~END", 210, 200), fixture.occupancy(stays));

    assertEquals(Optional.of(MID), residual.waitingPoint());
    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, residual.verdict());
  }

  /**
   * 途中停车点没有空位、起点有：仍判无容量，让车点记途中这一站。
   *
   * <p>残余不写进表，运行时也不读让车点：车按原时刻离开起点，到这一站发车前被挡，在这里等。起点有空位兑现不了。
   */
  @Test
  void aFullIntermediateStopIsNotRescuedByARoomyOrigin() {
    EnRoute fixture = new EnRoute();
    List<TimetableConflictChecker.Stay> stays =
        List.of(
            new TimetableConflictChecker.Stay(
                "OTHER", new TimetableConflictChecker.Platform(MID + ":1", MID, false), 155, 400));

    ConflictAbsorption.Residual residual =
        fixture.classify(conflictOn("edge:MID~END", 210, 200), fixture.occupancy(stays));

    assertEquals(Optional.of(MID), residual.waitingPoint());
    assertEquals(ConflictAbsorption.Verdict.NO_WAITING_CAPACITY, residual.verdict());
  }

  /** 途中这一站只有一股道：在这里等就是堵死岔线，起点有几股道都一样。 */
  @Test
  void aCapacityOneIntermediateStopIsAStubEvenFromAWideOrigin() {
    EnRoute fixture = new EnRoute(RouteStopPassType.STOP, 1);

    ConflictAbsorption.Residual residual =
        fixture.classify(conflictOn("edge:MID~END", 210, 200), fixture.occupancy(List.of()));

    assertEquals(Optional.of(MID), residual.waitingPoint());
    assertEquals(ConflictAbsorption.Verdict.STUB_TERMINAL, residual.verdict());
  }

  /** 别的车恰在"表定发车 + 裕量"进入这股道：按原时刻它与后车不冲突（后车的裕量刚好结束），后车多等就撞上了，算等待挡住的车。 */
  @Test
  void aTrainEnteringRightAtDeparturePlusSeparationIsBlockedByTheWait() {
    EnRoute fixture = new EnRoute();
    List<TimetableConflictChecker.Stay> stays =
        List.of(
            new TimetableConflictChecker.Stay(
                "OTHER",
                new TimetableConflictChecker.Platform(MID + ":1", MID, false),
                120 + SEPARATION,
                400));

    ConflictAbsorption.Residual residual =
        fixture.classify(conflictOn("edge:MID~END", 210, 200), fixture.occupancy(stays));

    assertEquals(Optional.of(MID), residual.waitingPoint());
    assertEquals(ConflictAbsorption.Verdict.NO_WAITING_CAPACITY, residual.verdict());
  }

  /** 别的车之间原本就有的冲突：站台组在后车多等之前就已超员（X、Z 在场时 Y 又进来），那一处另有记录，与后车多等无关，不能算这一站没空位。 */
  @Test
  void anOldConflictBetweenOtherTrainsDoesNotFillTheStop() {
    EnRoute fixture = new EnRoute();
    List<TimetableConflictChecker.Stay> stays = new java.util.ArrayList<>(fixture.originFull());
    stays.add(
        new TimetableConflictChecker.Stay(
            "X", new TimetableConflictChecker.Platform(MID + ":2", MID, false), 130, 400));
    stays.add(
        new TimetableConflictChecker.Stay(
            "Z", new TimetableConflictChecker.Platform(MID + ":3", MID, false), 140, 400));
    stays.add(
        new TimetableConflictChecker.Stay(
            "Y", new TimetableConflictChecker.Platform(MID + ":3", MID, false), 170, 400));

    ConflictAbsorption.Residual residual =
        fixture.classify(conflictOn("edge:MID~END", 210, 200), fixture.occupancy(stays));

    assertEquals(Optional.of(MID), residual.waitingPoint());
    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, residual.verdict());
  }

  /**
   * 相位第三层与成品表的成功判据对同一处冲突得出同一结论：第三层没有成品表，但手上有铺开的运行与档案，途中让车点照样认得出。
   *
   * <p>途中这一站只有一股道、起点两股道：两层都判在途中这一站等、堵死岔线。第三层若只看起点，会把它判成可吸收，挑 δ 用的尺子比成功判据松。
   */
  @Test
  void thePhaseLayerSeesTheSameIntermediateStopAsTheBuild() {
    EnRoute fixture = new EnRoute(RouteStopPassType.STOP, 1);
    TimetableConflictChecker.Conflict conflict = conflictOn("edge:MID~END", 210, 200);

    ConflictAbsorption.Residual build = fixture.classify(conflict, fixture.occupancy(List.of()));
    ConflictAbsorption.Residual phase =
        ConflictAbsorption.classify(
                report(conflict),
                Map.of("RA-001", WIDE),
                List.of(new TimetableConflictChecker.Movement("RA-001", fixture.route, 0)),
                Map.of(fixture.route, fixture.profile),
                fixture.index,
                SEPARATION,
                MAX_WAIT)
            .get(0);

    assertEquals(ConflictAbsorption.Verdict.STUB_TERMINAL, build.verdict());
    assertEquals(build.verdict(), phase.verdict());
    assertEquals(build.waitingPoint(), phase.waitingPoint());
  }

  /** 冲突在第一个停车点之前（含它的站台本身）：车还没到那一站，让车点仍是起点。 */
  @Test
  void aConflictBeforeTheFirstStopStillWaitsAtTheOrigin() {
    EnRoute fixture = new EnRoute();
    var occupancy = fixture.occupancy(fixture.originFull());

    ConflictAbsorption.Residual onLine =
        fixture.classify(conflictOn("edge:WIDE~MID", 70, 50), occupancy);
    ConflictAbsorption.Residual atPlatform =
        fixture.classify(conflictOn("platform:" + MID + ":1", 110, 100), occupancy);

    assertEquals(Optional.of(WIDE), onLine.waitingPoint());
    assertEquals(ConflictAbsorption.Verdict.NO_WAITING_CAPACITY, onLine.verdict());
    assertEquals(Optional.of(WIDE), atPlatform.waitingPoint(), "到站冲突时车还在上一站");
  }

  /** 不停车通过的车站不是让车点：运行时只在停车点的发车门控扣车。 */
  @Test
  void aPassedStationIsNotAWaitingPoint() {
    EnRoute fixture = new EnRoute(RouteStopPassType.PASS);
    var occupancy = fixture.occupancy(fixture.originFull());

    ConflictAbsorption.Residual residual =
        fixture.classify(conflictOn("edge:MID~END", 210, 200), occupancy);

    assertEquals(Optional.of(WIDE), residual.waitingPoint());
    assertEquals(ConflictAbsorption.Verdict.NO_WAITING_CAPACITY, residual.verdict());
  }

  private static final String MID = "OP:S:MID";

  /** 起点 WIDE（两股道）→ 途中 MID（默认两股道，100 s 到、120 s 发）→ 终点 END（300 s 到）。 */
  private static final class EnRoute {
    private final Timetable table = timetableWithOriginAt(WIDE);
    private final java.util.UUID route = table.trips().get(0).routeId();
    private final TimetableConflictChecker.RouteProfile profile;
    private final TimetableConflictChecker.GraphIndex index;

    EnRoute() {
      this(RouteStopPassType.STOP);
    }

    EnRoute(RouteStopPassType midPass) {
      this(midPass, 2);
    }

    /** {@code midTracks}：MID 有几股道。 */
    EnRoute(RouteStopPassType midPass, int midTracks) {
      index =
          new TimetableConflictChecker.GraphIndex(
              null, Map.of(STUB, 1, WIDE, 2, MID, midTracks, "OP:S:END", 2), Map.of());
      int midDeparture = midPass == RouteStopPassType.PASS ? 100 : 120;
      profile =
          new TimetableConflictChecker.RouteProfile(
              route,
              "RA",
              List.of(
                  new TimetableStop(
                      0, Optional.empty(), Optional.of(WIDE + ":1"), 0, 0, RouteStopPassType.STOP),
                  new TimetableStop(
                      1, Optional.empty(), Optional.of(MID + ":1"), 100, midDeparture, midPass),
                  new TimetableStop(
                      2,
                      Optional.empty(),
                      Optional.of("OP:S:END:1"),
                      300,
                      300,
                      RouteStopPassType.TERMINATE)),
              List.of(),
              List.of(
                  new TimetableConflictChecker.Platform(WIDE + ":1", WIDE, false),
                  new TimetableConflictChecker.Platform(MID + ":1", MID, false),
                  new TimetableConflictChecker.Platform("OP:S:END:1", "OP:S:END", false)));
    }

    /** 起点两股道在 [0, 500) 都被别的车占着。 */
    List<TimetableConflictChecker.Stay> originFull() {
      return List.of(
          new TimetableConflictChecker.Stay(
              "D001", new TimetableConflictChecker.Platform(WIDE + ":1", WIDE, false), 0, 500),
          new TimetableConflictChecker.Stay(
              "D002", new TimetableConflictChecker.Platform(WIDE + ":2", WIDE, false), 0, 500));
    }

    TimetableOccupancyProjector.Occupancy occupancy(List<TimetableConflictChecker.Stay> stays) {
      return new TimetableOccupancyProjector.Occupancy(
          List.of(new TimetableConflictChecker.Movement("RA-001", route, 0)), stays);
    }

    ConflictAbsorption.Residual classify(
        TimetableConflictChecker.Conflict conflict,
        TimetableOccupancyProjector.Occupancy occupancy) {
      return ConflictAbsorption.classify(
              report(conflict),
              table,
              occupancy,
              index,
              SEPARATION,
              MAX_WAIT,
              TurnaroundTable.none(),
              Map.of(route, profile))
          .get(0);
    }
  }

  // ------------------------------------------------------------------ 途中站多等挡住的是谁

  /** 同方向同路径的后车被途中站多等挡住：它在后面排队，不算放不下；它自己交路里赶得上下一班就可吸收。 */
  @Test
  void aFollowerQueuingBehindAtAThroughStationIsAbsorbable() {
    Corridor corridor = new Corridor();
    UUID main = corridor.through("RA", WIDE + ":1", "OP:X:XA:1", "OP:X:XB:1", END + ":1");
    corridor.run("RA-001", main, 0, "D001");
    corridor.run("RA-002", main, 60, "D002");

    ConflictAbsorption.Residual residual = corridor.classifyMover();

    assertEquals(Optional.of(MID), residual.waitingPoint());
    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, residual.verdict());
    assertEquals(0, residual.chainDepth());
  }

  /**
   * 同一个后车，它交路里的下一班 370 s 就要发：被挡 30 s（MID 上本车 160 s 才走，加裕量 30 s）之后 60 + 300 + 30 = 390 s 才到，赶不上——
   * 后车的连锁照样算，顺推超限。
   */
  @Test
  void aFollowerWhoseNextTripCannotAbsorbTheDelayIsTooDeep() {
    Corridor corridor = new Corridor();
    UUID main = corridor.through("RA", WIDE + ":1", "OP:X:XA:1", "OP:X:XB:1", END + ":1");
    corridor.run("RA-001", main, 0, "D001");
    corridor.run("RA-002", main, 60, "D002");
    corridor.run("RA-003", main, 370, "D002");

    ConflictAbsorption.Residual residual = corridor.classifyMover();

    assertEquals(ConflictAbsorption.Verdict.CHAIN_TOO_DEEP, residual.verdict());
    assertEquals(1, residual.chainDepth());
  }

  // ------------------------------------------------------------------ 顺推按可追回量吸收

  /**
   * 后车被挡 30 s 后 390 s 才到，下一班 380 s 发：晚 10 s。下一班途中 MID 表定停 20 s，压到最短 10 s 能追回 10 s，
   * 在那一班里就追回来了，链不算深，可吸收；不按追回量判时照旧顺推超限。交路 D002 后面还有一班，后两班可追回共 20 s，累计上限 10 s 够用。
   */
  @Test
  void aDelayTheNextTripCanRecoverByShorterStopsIsAbsorbable() {
    Corridor corridor = new Corridor();
    UUID main = corridor.through("RA", WIDE + ":1", "OP:X:XA:1", "OP:X:XB:1", END + ":1");
    corridor.run("RA-001", main, 0, "D001");
    corridor.run("RA-002", main, 60, "D002");
    corridor.run("RA-003", main, 380, "D002");
    corridor.run("RA-004", main, 1000, "D002");

    ConflictAbsorption.Residual recovered =
        corridor.classifyMover(new ConflictAbsorption.Recovery(10, 0));
    ConflictAbsorption.Residual strict = corridor.classifyMover(ConflictAbsorption.Recovery.NONE);

    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, recovered.verdict());
    assertEquals(0, recovered.chainDepth());
    assertEquals(ConflictAbsorption.Verdict.CHAIN_TOO_DEEP, strict.verdict(), "不按追回量判时是旧行为");
    assertEquals(1, strict.chainDepth());
  }

  /** 晚 20 s，下一班只能追回 10 s：追不回来，仍是顺推超限。 */
  @Test
  void aDelayLongerThanTheRecoverableStopsIsStillTooDeep() {
    Corridor corridor = new Corridor();
    UUID main = corridor.through("RA", WIDE + ":1", "OP:X:XA:1", "OP:X:XB:1", END + ":1");
    corridor.run("RA-001", main, 0, "D001");
    corridor.run("RA-002", main, 60, "D002");
    corridor.run("RA-003", main, 370, "D002");

    ConflictAbsorption.Residual residual =
        corridor.classifyMover(new ConflictAbsorption.Recovery(10, 0));

    assertEquals(ConflictAbsorption.Verdict.CHAIN_TOO_DEEP, residual.verdict());
    assertEquals(1, residual.chainDepth());
  }

  /** 停站开销不算可追回：表定停 20 s 里有 10 s 是居中刹停与开门延迟，压到最短 10 s 一秒也追不回。 */
  @Test
  void theStopOverheadIsNotRecoverable() {
    Corridor corridor = new Corridor();
    UUID main = corridor.through("RA", WIDE + ":1", "OP:X:XA:1", "OP:X:XB:1", END + ":1");
    corridor.run("RA-001", main, 0, "D001");
    corridor.run("RA-002", main, 60, "D002");
    corridor.run("RA-003", main, 380, "D002");

    ConflictAbsorption.Residual residual =
        corridor.classifyMover(new ConflictAbsorption.Recovery(10, 10));

    assertEquals(ConflictAbsorption.Verdict.CHAIN_TOO_DEEP, residual.verdict());
  }

  /** 交路只有两班：第二班可追回 10 s，累计上限只有一半（5 s），晚 10 s 靠追回吸收不了，仍是顺推超限。 */
  @Test
  void aShortDutyCannotSpendAllOfItsRecoverableSeconds() {
    Corridor corridor = new Corridor();
    UUID main = corridor.through("RA", WIDE + ":1", "OP:X:XA:1", "OP:X:XB:1", END + ":1");
    corridor.run("RA-001", main, 0, "D001");
    corridor.run("RA-002", main, 60, "D002");
    corridor.run("RA-003", main, 380, "D002");

    ConflictAbsorption.Residual residual =
        corridor.classifyMover(new ConflictAbsorption.Recovery(10, 0));

    assertEquals(ConflictAbsorption.Verdict.CHAIN_TOO_DEEP, residual.verdict());
    assertEquals(1, residual.chainDepth());
  }

  /**
   * 同一辆车靠追回吸收的顺推按交路累计，不超过这一交路可追回总量的 {@value ConflictAbsorption#RECOVERABLE_BUDGET_PERCENT}%：
   * 三圈里每圈的后车都晚 15 s、在交路下一班压缩停站追回 15 s；交路 D002 六班、后五班各能追回 15 s，上限 37 s——前两圈共 30 s 可吸收，
   * 第三圈超出累计上限判顺推超限。
   */
  @Test
  void recoveredDelaysAreBudgetedPerDuty() {
    Corridor corridor = new Corridor();
    UUID main = corridor.through("RA", WIDE + ":1", "OP:X:XA:1", "OP:X:XB:1", END + ":1");
    List<TimetableConflictChecker.Conflict> conflicts = new ArrayList<>();
    for (int lap = 0; lap < 3; lap++) {
      int base = 1000 * lap;
      corridor.run("RA-0" + lap + "1", main, base, "D0" + lap + "1");
      corridor.run("RA-0" + lap + "2", main, base + 60, "D002");
      corridor.run("RA-0" + lap + "3", main, base + 375, "D002");
      conflicts.add(conflictOn("edge:MID~END", base + 210, base + 200, "RA-0" + lap + "1"));
    }

    List<ConflictAbsorption.Residual> residuals =
        corridor.classifyAll(conflicts, new ConflictAbsorption.Recovery(5, 0, 0, Map.of()));

    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, residuals.get(0).verdict());
    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, residuals.get(1).verdict());
    assertEquals(ConflictAbsorption.Verdict.CHAIN_TOO_DEEP, residuals.get(2).verdict());
    assertEquals(1, residuals.get(2).chainDepth());
  }

  /** 同一班在两处各等 30 s（两处的前车不同）：单看每一处，到终点晚 30 s 都赶得上 340 s 的下一班；两处加起来晚 60 s 就赶不上了—— 第二处判顺推超限，不能各算各的。 */
  @Test
  void waitsOfOneTripAddUpBeforeTheChainIsJudged() {
    Corridor corridor = new Corridor();
    UUID main = corridor.through("RA", WIDE + ":1", "OP:X:XA:1", "OP:X:XB:1", END + ":1");
    corridor.run("RA-001", main, 0, "D001");
    corridor.run("RA-005", main, 340, "D001");

    List<ConflictAbsorption.Residual> residuals =
        corridor.classifyAll(
            List.of(
                conflictOn("edge:MID~XB", 200, 200, "RA-001", "RB-001"),
                conflictOn("edge:XB~END", 250, 250, "RA-001", "RB-002")),
            ConflictAbsorption.Recovery.NONE);

    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, residuals.get(0).verdict());
    assertEquals(ConflictAbsorption.Verdict.CHAIN_TOO_DEEP, residuals.get(1).verdict());
    assertEquals(1, residuals.get(1).chainDepth());
  }

  /** 同一对车在相邻几个资源上各报一处冲突：后车在第一处之前等够了，后面几处也就让开了，等待不重复累加。 */
  @Test
  void repeatedConflictsWithTheSameLeaderDoNotAddUp() {
    Corridor corridor = new Corridor();
    UUID main = corridor.through("RA", WIDE + ":1", "OP:X:XA:1", "OP:X:XB:1", END + ":1");
    corridor.run("RA-001", main, 0, "D001");
    corridor.run("RA-005", main, 340, "D001");

    List<ConflictAbsorption.Residual> residuals =
        corridor.classifyAll(
            List.of(
                conflictOn("edge:MID~XB", 200, 200, "RA-001", "RB-001"),
                conflictOn("edge:XB~END", 250, 250, "RA-001", "RB-001")),
            ConflictAbsorption.Recovery.NONE);

    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, residuals.get(0).verdict());
    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, residuals.get(1).verdict());
  }

  /** 可追回量只算途中车站：起点与终到不压缩，PASS 不停站，区间停车点不是车站（运行时只在车站压缩）。 */
  @Test
  void recoverableSecondsCountOnlyIntermediateStations() {
    TimetableRoutePlan plan = recoveryPlan("RR", "OP:S:A:1", Optional.empty());

    // B 停 30 s：扣开销 4 s、留最短 10 s，追回 16 s；X 是区间停车点不算；D 停 14 s 扣完不剩；起点 A 与终到 E 不算。
    assertEquals(16, new ConflictAbsorption.Recovery(10, 4, 0, Map.of()).recoverableSeconds(plan));
    assertEquals(0, ConflictAbsorption.Recovery.NONE.recoverableSeconds(plan));
  }

  /** 运行时压缩的下限是最短停站与车门开关过程取大：最短停站 5 s、车门要 8 s 时，各站只能压到 8 s。 */
  @Test
  void theDoorFloorBoundsTheCompression() {
    TimetableRoutePlan plan = recoveryPlan("RR", "OP:S:A:1", Optional.empty());

    // B 30 − 4 − 8，D 14 − 4 − 8。
    assertEquals(
        18 + 2, new ConflictAbsorption.Recovery(5, 4, 8, Map.of()).recoverableSeconds(plan));
    int floor = ConflictAbsorption.Recovery.RUNTIME_DOOR_FLOOR_SECONDS;
    assertEquals(
        (30 - 4 - floor) + Math.max(0, 14 - 4 - floor),
        new ConflictAbsorption.Recovery(1, 4).recoverableSeconds(plan),
        "不给车门下限时按运行时的缺省");
  }

  /** 从车库出车的班次：出库后第一个车站运行时不压缩（开门延迟更长），只剩后面的车站。 */
  @Test
  void theFirstStationAfterLeavingTheDepotIsNotCompressed() {
    TimetableRoutePlan fromDepot = recoveryPlan("RD", "OP:D:DEP:1", Optional.of("OP:D:DEP:1"));
    TimetableRoutePlan fromStation = recoveryPlan("RR", "OP:S:A:1", Optional.empty());
    ConflictAbsorption.Recovery recovery = new ConflictAbsorption.Recovery(5, 4, 0, Map.of());

    // 车站始发：B 30 − 4 − 5 = 21，D 14 − 4 − 5 = 5；车库始发少了 B。
    assertEquals(21 + 5, recovery.recoverableSeconds(fromStation));
    assertEquals(5, recovery.recoverableSeconds(fromDepot));
  }

  /** 表定停站里的换模加时运行时不压缩：B 停 30 s 里有 20 s 是换模，只剩 10 s 可压（扣开销 4 s、最短 5 s 后追回 1 s）。 */
  @Test
  void powerSwitchDwellIsNotRecoverable() {
    TimetableRoutePlan plan = recoveryPlan("RR", "OP:S:A:1", Optional.empty());
    ConflictAbsorption.Recovery recovery =
        new ConflictAbsorption.Recovery(
            5, 4, 0, Map.of(plan.routeId(), List.of(0, 20, 0, 0, 0, 0)));

    assertEquals(1 + 5, recovery.recoverableSeconds(plan));
  }

  /**
   * A（起点）— B 车站（停 30 s）— C 通过 — X 区间停车点（停 40 s）— D 车站（停 14 s）— E 终到。
   *
   * @param origin 起点节点
   * @param depot 出库点：有它表示从车库出车
   */
  private static TimetableRoutePlan recoveryPlan(
      String code, String origin, Optional<String> depot) {
    return new TimetableRoutePlan(
        TimetableTestFixtures.routeId(code),
        code,
        RouteOperationType.OPERATION,
        1,
        List.of(
            new TimetableStop(
                0, Optional.empty(), Optional.of(origin), 0, 30, RouteStopPassType.STOP),
            new TimetableStop(
                1, Optional.of("B"), Optional.of("OP:S:B:1"), 100, 130, RouteStopPassType.STOP),
            new TimetableStop(
                2, Optional.of("C"), Optional.of("OP:S:C:1"), 200, 200, RouteStopPassType.PASS),
            new TimetableStop(
                3, Optional.empty(), Optional.of("OP:X:X:1"), 250, 290, RouteStopPassType.STOP),
            new TimetableStop(
                4, Optional.of("D"), Optional.of("OP:S:D:1"), 300, 314, RouteStopPassType.STOP),
            new TimetableStop(
                5,
                Optional.of("E"),
                Optional.of("OP:S:E:1"),
                400,
                460,
                RouteStopPassType.TERMINATE)),
        origin,
        "OP:S:E:1",
        depot,
        Optional.empty());
  }

  /** 对向来车要进同一股道：它从本车要去的方向来，与本车互等，放不下。 */
  @Test
  void anOpposingTrainBlockedByTheWaitMeansNoRoom() {
    Corridor corridor = new Corridor();
    UUID main = corridor.through("RA", WIDE + ":1", "OP:X:XA:1", "OP:X:XB:1", END + ":1");
    UUID back = corridor.through("RO", END + ":1", "OP:X:XB:1", "OP:X:XA:1", WIDE + ":1");
    corridor.run("RA-001", main, 0, "D001");
    corridor.run("RO-001", back, 60, "D002");

    ConflictAbsorption.Residual residual = corridor.classifyMover();

    assertEquals(Optional.of(MID), residual.waitingPoint());
    assertEquals(ConflictAbsorption.Verdict.NO_WAITING_CAPACITY, residual.verdict());
  }

  /** 从另一条支线开来、要用同一股道的车：进站前与本车没有共同节点，但出站后与本车走同一侧（XB、END），是同一行进方向在站上汇合的车， 在本车身后排队，可吸收。 */
  @Test
  void aSameDirectionTrainFromAnotherBranchQueuesBehind() {
    Corridor corridor = new Corridor();
    UUID main = corridor.through("RA", WIDE + ":1", "OP:X:XA:1", "OP:X:XB:1", END + ":1");
    UUID branch = corridor.through("RC", SIDE + ":1", "OP:X:XC:1", "OP:X:XB:1", END + ":1");
    corridor.run("RA-001", main, 0, "D001");
    corridor.run("RC-001", branch, 60, "D002");

    ConflictAbsorption.Residual residual = corridor.classifyMover();

    assertEquals(Optional.of(MID), residual.waitingPoint());
    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, residual.verdict());
  }

  /** 反方向从另一条支线进同一股道的车：进站前与本车没有共同节点，出站后开往本车来的那一侧（XA），与本车互等，放不下。 */
  @Test
  void anOpposingTrainFromAnotherBranchMeansNoRoom() {
    Corridor corridor = new Corridor();
    UUID main = corridor.through("RA", WIDE + ":1", "OP:X:XA:1", "OP:X:XB:1", END + ":1");
    UUID back = corridor.through("RO", SIDE + ":1", "OP:X:XC:1", "OP:X:XA:1", WIDE + ":1");
    corridor.run("RA-001", main, 0, "D001");
    corridor.run("RO-001", back, 60, "D002");

    ConflictAbsorption.Residual residual = corridor.classifyMover();

    assertEquals(Optional.of(MID), residual.waitingPoint());
    assertEquals(ConflictAbsorption.Verdict.NO_WAITING_CAPACITY, residual.verdict());
  }

  /**
   * 进路与本车去路交叉的车：它从 SIDE 开来，进站前穿过本车出站要走的道岔 XB，再经 XD 进同一股道。它被挡时停在本车前方要用的资源上， 与本车互卡——哪怕它出站后也开往
   * END，放不下。
   */
  @Test
  void aTrainWhoseApproachCrossesOurWayOutMeansNoRoom() {
    Corridor corridor = new Corridor();
    UUID main = corridor.through("RA", WIDE + ":1", "OP:X:XA:1", "OP:X:XB:1", END + ":1");
    UUID crossing =
        corridor.along(
            "RX", List.of(SIDE + ":1", "OP:X:XB:1", "OP:X:XD:1"), List.of("OP:X:XE:1", END + ":1"));
    corridor.run("RA-001", main, 0, "D001");
    corridor.run("RX-001", crossing, 60, "D002");

    ConflictAbsorption.Residual residual = corridor.classifyMover();

    assertEquals(Optional.of(MID), residual.waitingPoint());
    assertEquals(ConflictAbsorption.Verdict.NO_WAITING_CAPACITY, residual.verdict());
  }

  /** 两车在这一站前后没有任何共同节点：方向判不清，保守算放不下。 */
  @Test
  void aTrainSharingNoNodeAroundTheStationMeansNoRoom() {
    Corridor corridor = new Corridor();
    UUID main = corridor.through("RA", WIDE + ":1", "OP:X:XA:1", "OP:X:XB:1", END + ":1");
    UUID other = corridor.through("RO", SIDE + ":1", "OP:X:XC:1", "OP:X:XD:1", TURN + ":1");
    corridor.run("RA-001", main, 0, "D001");
    corridor.run("RO-001", other, 60, "D002");

    ConflictAbsorption.Residual residual = corridor.classifyMover();

    assertEquals(ConflictAbsorption.Verdict.NO_WAITING_CAPACITY, residual.verdict());
  }

  /**
   * 本车占着自己的固定股道 MID:1 多等，对向车进的是另一股道 MID:2：它在自己股道上没有新增冲突，只是站台组按车辆数 + 裕量多算了一辆而超员 （MID:2 上前一辆 RX 140 s
   * 才走，加裕量盖到 170 s），并没有被本车挡住，可吸收。
   */
  @Test
  void anOpposingTrainOnAnotherFixedTrackIsNotBlockedByTheWait() {
    Corridor corridor = new Corridor();
    UUID main = corridor.through("RA", WIDE + ":1", "OP:X:XA:1", "OP:X:XB:1", END + ":1");
    UUID back =
        corridor.through(
            "RO", END + ":1", "OP:X:XB:2", "OP:X:XA:2", WIDE + ":1", MID + ":2", false);
    corridor.run("RA-001", main, 0, "D001");
    corridor.run("RX-001", back, 20, "D002");
    corridor.run("RO-001", back, 60, "D003");

    ConflictAbsorption.Residual residual = corridor.classifyMover();

    assertEquals(Optional.of(MID), residual.waitingPoint());
    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, residual.verdict());
  }

  /** 本车停的是 DYNAMIC 停靠（没有自己的固定股道）：站台组层的超员就是真的少一股道，照旧放不下。 */
  @Test
  void aDynamicStopStillCountsTheStationGroup() {
    Corridor corridor = new Corridor();
    UUID main =
        corridor.through("RA", WIDE + ":1", "OP:X:XA:1", "OP:X:XB:1", END + ":1", MID + ":1", true);
    UUID back =
        corridor.through(
            "RO", END + ":1", "OP:X:XB:2", "OP:X:XA:2", WIDE + ":1", MID + ":2", false);
    corridor.run("RA-001", main, 0, "D001");
    corridor.run("RX-001", back, 20, "D002");
    corridor.run("RO-001", back, 60, "D003");

    ConflictAbsorption.Residual residual = corridor.classifyMover();

    assertEquals(ConflictAbsorption.Verdict.NO_WAITING_CAPACITY, residual.verdict());
  }

  /** 这一站是别的 route 的终点（折返站）：在这里多等挡住的哪怕是同方向后车，也算放不下。 */
  @Test
  void aFollowerBlockedAtATurnbackStationMeansNoRoom() {
    Corridor corridor = new Corridor();
    UUID main = corridor.through("RA", WIDE + ":1", "OP:X:XA:1", "OP:X:XB:1", END + ":1");
    UUID shortTurn = corridor.ending("RT", SIDE + ":1", "OP:X:XC:1", MID + ":2");
    corridor.run("RA-001", main, 0, "D001");
    corridor.run("RA-002", main, 60, "D002");
    corridor.run("RT-001", shortTurn, 5000, "D003");

    ConflictAbsorption.Residual residual = corridor.classifyMover();

    assertEquals(ConflictAbsorption.Verdict.NO_WAITING_CAPACITY, residual.verdict());
  }

  /** 支线在站前道岔 J 汇入：本车从上一个停车点起走的是 WIDE — J，它走的是 SIDE — J，进路不同，但都经 J 进 MID、同一行进方向， 它在本车身后排队，可吸收。 */
  @Test
  void aBranchMergingRightBeforeTheStationQueuesBehind() {
    Corridor corridor = new Corridor();
    UUID main = corridor.through("RA", WIDE + ":1", "OP:X:J:1", "OP:X:XB:1", END + ":1");
    UUID branch = corridor.through("RC", SIDE + ":1", "OP:X:J:1", "OP:X:XB:1", END + ":1");
    corridor.run("RA-001", main, 0, "D001");
    corridor.run("RC-001", branch, 60, "D002");

    ConflictAbsorption.Residual residual = corridor.classifyMover();

    assertEquals(Optional.of(MID), residual.waitingPoint());
    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, residual.verdict());
  }

  /**
   * 途中折返：本车 WIDE → MID → TURN 折返 → 再进 MID（第二次停 MID 是让车点，从 XB 一侧进站）。从 WIDE 开来的车经 XA 进 MID——XA
   * 是本车第一次进 MID 之前走过的节点，但这一次本车从另一侧来，它是对向车，算放不下。
   */
  @Test
  void aTrainFromTheOtherSideAfterAMidRouteTurnbackIsNotAFollower() {
    Corridor corridor = new Corridor();
    UUID turning = corridor.turnback("RA");
    UUID main = corridor.through("RO", WIDE + ":1", "OP:X:XA:1", "OP:X:XB:1", END + ":1");
    corridor.run("RA-001", turning, 0, "D001");
    corridor.run("RO-001", main, 300, "D002");

    ConflictAbsorption.Residual residual =
        corridor.classify(conflictOn("edge:MID~SIDE", 410, 400), ConflictAbsorption.Recovery.NONE);

    assertEquals(Optional.of(MID), residual.waitingPoint());
    assertEquals(ConflictAbsorption.Verdict.NO_WAITING_CAPACITY, residual.verdict());
  }

  /** 同一站经过两次：同一条折返 route 的后车第二次进 MID，与本车同一侧、同一段进站，是后车，可吸收。 */
  @Test
  void aFollowerOnTheSecondVisitOfTheSameStationIsAbsorbable() {
    Corridor corridor = new Corridor();
    UUID turning = corridor.turnback("RA");
    corridor.run("RA-001", turning, 0, "D001");
    corridor.run("RA-002", turning, 60, "D002");

    ConflictAbsorption.Residual residual =
        corridor.classify(conflictOn("edge:MID~SIDE", 410, 400), ConflictAbsorption.Recovery.NONE);

    assertEquals(Optional.of(MID), residual.waitingPoint());
    assertEquals(ConflictAbsorption.Verdict.ABSORBABLE, residual.verdict());
  }

  /**
   * 环线：本车 WIDE → XB → END → XA → MID，反向绕行的车经 XB 进 MID。XB 在本车进 MID 之前的路上，但本车是从 XA 一侧进站的，
   * 那辆车是对向车，算放不下。
   */
  @Test
  void aCounterRotatingTrainOnALoopIsNotAFollower() {
    Corridor corridor = new Corridor();
    UUID loop = corridor.loop("RA");
    UUID counter = corridor.through("RO", SIDE + ":1", "OP:X:XB:1", "OP:X:XD:1", WIDE + ":1");
    corridor.run("RA-001", loop, 0, "D001");
    corridor.run("RO-001", counter, 160, "D002");

    ConflictAbsorption.Residual residual =
        corridor.classify(conflictOn("edge:MID~SIDE", 250, 240), ConflictAbsorption.Recovery.NONE);

    assertEquals(Optional.of(MID), residual.waitingPoint());
    assertEquals(ConflictAbsorption.Verdict.NO_WAITING_CAPACITY, residual.verdict());
  }

  /**
   * 途中原地折返的车站（进站与出站走同一个相邻节点，车在站台上换向）与端点同样对待：本车在 MID 换向后从后车来的那一侧出站， 后车排在进站区间里就是与本车顶牛，哪怕它是同一条 route
   * 的后车，挡住了也算放不下。
   */
  @Test
  void aMidRouteReversalStationCountsAsAnEndpoint() {
    Corridor corridor = new Corridor();
    UUID reversing = corridor.reversing("RA");
    corridor.run("RA-001", reversing, 0, "D001");
    corridor.run("RA-002", reversing, 60, "D002");

    ConflictAbsorption.Residual residual = corridor.classifyMover();

    assertEquals(Optional.of(MID), residual.waitingPoint());
    assertEquals(ConflictAbsorption.Verdict.NO_WAITING_CAPACITY, residual.verdict());
  }

  private static final String SIDE = "OP:S:SIDE";
  private static final String END = "OP:S:END";
  private static final String TURN = "OP:S:TURN";

  /**
   * 带逐边路径的走廊：贯通 route 是 {@code 起点 — x — MID:1 — y — 终点}，0 s 发、100 s 到 MID、120 s 发、300 s 到终点。
   *
   * <p>后车 RA-001 在 MID 之后的区间与前车冲突（{@code firstTo = 210, secondFrom = 200}），在 MID 多等 40 s；同一股道上 60 s
   * 发车的车 160 s 进 MID，按原时刻不冲突（120 + 30 ≤ 160），多等就被挡住。
   */
  private static final class Corridor {
    private final Map<UUID, TimetableConflictChecker.RouteProfile> profiles = new LinkedHashMap<>();
    private final Map<UUID, TimetableRoutePlan> plans = new LinkedHashMap<>();
    private final List<String[]> runs = new ArrayList<>();
    private final Map<String, UUID> routeOfRun = new HashMap<>();
    private final java.util.Set<String> dynamicStops = new java.util.HashSet<>();
    private final TimetableConflictChecker.GraphIndex index =
        new TimetableConflictChecker.GraphIndex(
            null, Map.of(WIDE, 2, MID, 2, END, 2, SIDE, 2, TURN, 2), Map.of());

    UUID through(String code, String from, String x, String y, String to) {
      return through(code, from, x, y, to, MID + ":1", false);
    }

    /** 同上，指定停 MID 的哪一股道；{@code dynamic} 时 MID 是 DYNAMIC 停靠（只登记站台组一层）。 */
    UUID through(
        String code, String from, String x, String y, String to, String midTrack, boolean dynamic) {
      List<String> nodes = List.of(from, x, midTrack, y, to);
      if (dynamic) {
        dynamicStops.add(code);
      }
      return add(
          code,
          List.of(
              stop(0, from, 0, 0, RouteStopPassType.STOP),
              stop(1, midTrack, 100, 120, RouteStopPassType.STOP),
              stop(2, to, 300, 300, RouteStopPassType.TERMINATE)),
          List.of(
              segment(0, 1, nodes.subList(0, 3), List.of(0, 50, 100)),
              segment(1, 2, nodes.subList(2, 5), List.of(120, 200, 300))));
    }

    /**
     * 任意进出站路径：{@code approach} 首节点 0 s 发，经其余节点 100 s 到 MID:1、120 s 发，经 {@code departure} 300 s
     * 终到其末节点。
     */
    UUID along(String code, List<String> approach, List<String> departure) {
      List<String> before = new ArrayList<>(approach);
      before.add(MID + ":1");
      List<String> after = new ArrayList<>();
      after.add(MID + ":1");
      after.addAll(departure);
      return add(
          code,
          List.of(
              stop(0, approach.get(0), 0, 0, RouteStopPassType.STOP),
              stop(1, MID + ":1", 100, 120, RouteStopPassType.STOP),
              stop(2, departure.get(departure.size() - 1), 300, 300, RouteStopPassType.TERMINATE)),
          List.of(
              segment(0, 1, before, evenly(0, 100, before.size())),
              segment(1, 2, after, evenly(120, 300, after.size()))));
    }

    private static List<Integer> evenly(int from, int to, int count) {
      List<Integer> out = new ArrayList<>(count);
      for (int k = 0; k < count; k++) {
        out.add(from + (to - from) * k / (count - 1));
      }
      return out;
    }

    UUID ending(String code, String from, String x, String to) {
      return add(
          code,
          List.of(
              stop(0, from, 0, 0, RouteStopPassType.STOP),
              stop(1, to, 100, 100, RouteStopPassType.TERMINATE)),
          List.of(segment(0, 1, List.of(from, x, to), List.of(0, 50, 100))));
    }

    /**
     * 途中折返：WIDE:1（0 s 发）— XA — MID:1（100 s 到、120 s 发）— XB — TURN:1（200 s 到、260 s 发）— XB — MID:1（340
     * s 到、360 s 发）— XC — SIDE:1（460 s 终到）。
     */
    UUID turnback(String code) {
      return add(
          code,
          List.of(
              stop(0, WIDE + ":1", 0, 0, RouteStopPassType.STOP),
              stop(1, MID + ":1", 100, 120, RouteStopPassType.STOP),
              stop(2, TURN + ":1", 200, 260, RouteStopPassType.STOP),
              stop(3, MID + ":1", 340, 360, RouteStopPassType.STOP),
              stop(4, SIDE + ":1", 460, 460, RouteStopPassType.TERMINATE)),
          List.of(
              segment(0, 1, List.of(WIDE + ":1", "OP:X:XA:1", MID + ":1"), List.of(0, 50, 100)),
              segment(1, 2, List.of(MID + ":1", "OP:X:XB:1", TURN + ":1"), List.of(120, 160, 200)),
              segment(2, 3, List.of(TURN + ":1", "OP:X:XB:1", MID + ":1"), List.of(260, 300, 340)),
              segment(
                  3, 4, List.of(MID + ":1", "OP:X:XC:1", SIDE + ":1"), List.of(360, 410, 460))));
    }

    /** 在 MID 原地换向：WIDE:1（0 s 发）— XA — MID:1（100 s 到、120 s 发）— XA — END:1（300 s 终到）。 */
    UUID reversing(String code) {
      return add(
          code,
          List.of(
              stop(0, WIDE + ":1", 0, 0, RouteStopPassType.STOP),
              stop(1, MID + ":1", 100, 120, RouteStopPassType.STOP),
              stop(2, END + ":1", 300, 300, RouteStopPassType.TERMINATE)),
          List.of(
              segment(0, 1, List.of(WIDE + ":1", "OP:X:XA:1", MID + ":1"), List.of(0, 50, 100)),
              segment(1, 2, List.of(MID + ":1", "OP:X:XA:1", END + ":1"), List.of(120, 200, 300))));
    }

    /**
     * 环线的一段：WIDE:1（0 s 发）— XB — END:1（100 s 到、120 s 发）— XA — MID:1（200 s 到、220 s 发）— XC —
     * SIDE:1（300 s 终到）。
     */
    UUID loop(String code) {
      return add(
          code,
          List.of(
              stop(0, WIDE + ":1", 0, 0, RouteStopPassType.STOP),
              stop(1, END + ":1", 100, 120, RouteStopPassType.STOP),
              stop(2, MID + ":1", 200, 220, RouteStopPassType.STOP),
              stop(3, SIDE + ":1", 300, 300, RouteStopPassType.TERMINATE)),
          List.of(
              segment(0, 1, List.of(WIDE + ":1", "OP:X:XB:1", END + ":1"), List.of(0, 50, 100)),
              segment(1, 2, List.of(END + ":1", "OP:X:XA:1", MID + ":1"), List.of(120, 160, 200)),
              segment(
                  2, 3, List.of(MID + ":1", "OP:X:XC:1", SIDE + ":1"), List.of(220, 260, 300))));
    }

    void run(String code, UUID route, int start, String duty) {
      runs.add(new String[] {code, String.valueOf(start), duty});
      routeOfRun.put(code, route);
    }

    ConflictAbsorption.Residual classifyMover() {
      return classifyMover(ConflictAbsorption.Recovery.NONE);
    }

    ConflictAbsorption.Residual classifyMover(ConflictAbsorption.Recovery recovery) {
      return classifyRepeated(1, recovery).get(0);
    }

    /** 同一处冲突在报告里出现 {@code times} 次：累计上限按报告顺序逐处扣。 */
    List<ConflictAbsorption.Residual> classifyRepeated(
        int times, ConflictAbsorption.Recovery recovery) {
      List<TimetableConflictChecker.Conflict> conflicts = new ArrayList<>();
      for (int i = 0; i < times; i++) {
        conflicts.add(conflictOn("edge:MID~END", 210, 200));
      }
      return classifyAll(conflicts, recovery);
    }

    /** 只分类给定的这一处冲突。 */
    ConflictAbsorption.Residual classify(
        TimetableConflictChecker.Conflict conflict, ConflictAbsorption.Recovery recovery) {
      return classifyAll(List.of(conflict), recovery).get(0);
    }

    /** 按报告顺序分类这几处冲突。 */
    List<ConflictAbsorption.Residual> classifyAll(
        List<TimetableConflictChecker.Conflict> conflicts, ConflictAbsorption.Recovery recovery) {
      UUID tableId = uuid("corridor");
      List<TimetableTrip> trips = new ArrayList<>();
      Map<String, List<UUID>> dutyTrips = new LinkedHashMap<>();
      Map<String, Integer> dutyStart = new HashMap<>();
      List<TimetableConflictChecker.Movement> movements = new ArrayList<>();
      for (String[] run : runs) {
        UUID tripId = uuid(run[0]);
        int start = Integer.parseInt(run[1]);
        UUID route = routeOfRun.get(run[0]);
        trips.add(
            new TimetableTrip(
                tripId, tableId, route, trips.size(), run[0], start, Optional.of(uuid(run[2]))));
        dutyTrips.computeIfAbsent(run[2], ignored -> new ArrayList<>()).add(tripId);
        dutyStart.putIfAbsent(run[2], start);
        movements.add(new TimetableConflictChecker.Movement(run[0], route, start));
      }
      List<VehicleDuty> duties = new ArrayList<>();
      for (Map.Entry<String, List<UUID>> duty : dutyTrips.entrySet()) {
        int start = dutyStart.get(duty.getKey());
        duties.add(
            new VehicleDuty(
                uuid(duty.getKey()),
                tableId,
                duties.size(),
                duty.getKey(),
                "OP:D:DEP:1",
                "OP:D:DEP:1",
                Optional.empty(),
                Optional.empty(),
                duty.getValue(),
                start,
                start + 7000,
                start + 7100,
                VehicleDuty.CloseReason.HORIZON_END));
      }
      Timetable table =
          new Timetable(
              tableId,
              uuid("company"),
              uuid("operator"),
              uuid("line"),
              "CORRIDOR",
              "走廊",
              TimetableStatus.DRAFT,
              ZoneId.of("UTC"),
              0,
              86_400,
              new ArrayList<>(plans.values()),
              trips,
              duties,
              Optional.empty(),
              Instant.EPOCH,
              Instant.EPOCH);
      return ConflictAbsorption.classify(
          new TimetableConflictChecker.Report(conflicts),
          table,
          new TimetableOccupancyProjector.Occupancy(movements, List.of()),
          index,
          SEPARATION,
          MAX_WAIT,
          TurnaroundTable.none(),
          profiles,
          recovery);
    }

    private UUID add(
        String code,
        List<TimetableStop> stops,
        List<TimetableTimingCalculator.SegmentTiming> segments) {
      UUID id = TimetableTestFixtures.routeId(code);
      List<TimetableConflictChecker.Platform> platforms = new ArrayList<>();
      for (TimetableStop stop : stops) {
        String node = stop.nodeId().orElseThrow();
        boolean dynamic =
            dynamicStops.contains(code) && stop.stopSequence() > 0 && stop.stopSequence() < 2;
        platforms.add(
            new TimetableConflictChecker.Platform(
                node, TimetableConflictChecker.groupOf(node), dynamic));
      }
      profiles.put(
          id, new TimetableConflictChecker.RouteProfile(id, code, stops, segments, platforms));
      plans.put(
          id,
          new TimetableRoutePlan(
              id,
              code,
              RouteOperationType.OPERATION,
              1,
              stops,
              stops.get(0).nodeId().orElseThrow(),
              stops.get(stops.size() - 1).nodeId().orElseThrow(),
              Optional.empty(),
              Optional.empty()));
      return id;
    }

    private static UUID uuid(String name) {
      return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    /** 车站本体节点带站码（运行时只在车站压缩停站），区间点、车库不带。 */
    private static TimetableStop stop(
        int index, String node, int arrival, int departure, RouteStopPassType pass) {
      return new TimetableStop(
          index,
          org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals.stationCodeOf(node),
          Optional.of(node),
          arrival,
          departure,
          pass);
    }

    private static TimetableTimingCalculator.SegmentTiming segment(
        int fromStop, int toStop, List<String> nodes, List<Integer> offsets) {
      List<NodeId> ids = nodes.stream().map(NodeId::of).toList();
      List<RailEdge> edges = new ArrayList<>();
      for (int k = 0; k + 1 < ids.size(); k++) {
        edges.add(
            new RailEdge(
                EdgeId.undirected(ids.get(k), ids.get(k + 1)),
                ids.get(k),
                ids.get(k + 1),
                10,
                10.0,
                true,
                Optional.empty()));
      }
      return new TimetableTimingCalculator.SegmentTiming(fromStop, toStop, ids, edges, offsets);
    }
  }

  private static ConflictAbsorption.Residual only(TimetableConflictChecker.Conflict conflict) {
    return ConflictAbsorption.classify(report(conflict), null, null, INDEX, SEPARATION, MAX_WAIT)
        .get(0);
  }

  /** DYNAMIC 占位首股道已占用时，声明允许的第二股道仍可用于始发等待。 */
  @Test
  void dynamicOriginCanWaitOnAnotherEligibleTrack() {
    Timetable table = timetableWithOriginAt(WIDE);
    java.util.UUID route = table.trips().get(0).routeId();
    TimetableConflictChecker.RouteProfile profile =
        new TimetableConflictChecker.RouteProfile(
            route,
            "RA",
            List.of(),
            List.of(),
            List.of(new TimetableConflictChecker.Platform(WIDE + ":1", WIDE, true)));
    TimetableOccupancyProjector.Occupancy occupancy =
        new TimetableOccupancyProjector.Occupancy(
            List.of(new TimetableConflictChecker.Movement("RA-001", route, 0)),
            List.of(
                new TimetableConflictChecker.Stay(
                    "OTHER",
                    new TimetableConflictChecker.Platform(WIDE + ":1", WIDE, false),
                    0,
                    100)));

    assertEquals(
        ConflictAbsorption.Verdict.ABSORBABLE,
        ConflictAbsorption.classify(
                report(conflictOn("edge:REMOTE~NEXT", 60, 50)),
                table,
                occupancy,
                INDEX,
                SEPARATION,
                MAX_WAIT,
                TurnaroundTable.none(),
                Map.of(route, profile))
            .get(0)
            .verdict());
  }

  /** 站台组三股道不代表 DYNAMIC 有三股道可用；声明只允许的两条已满时必须拒绝等待。 */
  @Test
  void dynamicWaitingRetainsItsDeclaredTrackSubset() {
    Timetable table = timetableWithOriginAt(WIDE);
    java.util.UUID route = table.trips().get(0).routeId();
    var graph =
        TimetableTestFixtures.graph(
            Map.of(
                WIDE + ":1",
                org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType.STATION,
                WIDE + ":2",
                org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType.STATION,
                WIDE + ":3",
                org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType.STATION),
            List.of());
    var spec =
        org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher.parseDynamicSpec(
            "DYNAMIC:OP:S:WIDE:[1,2]");
    var platform = new TimetableConflictChecker.Platform(WIDE + ":1", WIDE, true, spec);
    var profile =
        new TimetableConflictChecker.RouteProfile(
            route, "RA", List.of(), List.of(), List.of(platform));
    var occupancy =
        new TimetableOccupancyProjector.Occupancy(
            List.of(new TimetableConflictChecker.Movement("RA-001", route, 0)),
            List.of(
                new TimetableConflictChecker.Stay(
                    "OTHER1",
                    new TimetableConflictChecker.Platform(WIDE + ":1", WIDE, false),
                    0,
                    100),
                new TimetableConflictChecker.Stay(
                    "OTHER2",
                    new TimetableConflictChecker.Platform(WIDE + ":2", WIDE, false),
                    0,
                    100)));

    assertEquals(
        ConflictAbsorption.Verdict.NO_WAITING_CAPACITY,
        ConflictAbsorption.classify(
                report(conflictOn("edge:REMOTE~NEXT", 60, 50)),
                table,
                occupancy,
                TimetableConflictChecker.GraphIndex.of(graph),
                SEPARATION,
                MAX_WAIT,
                TurnaroundTable.none(),
                Map.of(route, profile))
            .get(0)
            .verdict());
  }

  /** 邻表 code 与本表相同也不是同车；邻表占位不能被本车豁免。 */
  @Test
  void neighbourWithTheSameTripCodeStillOccupiesTheOrigin() {
    var occupancy =
        new TimetableOccupancyProjector.Occupancy(
            List.of(),
            List.of(
                new TimetableConflictChecker.Stay(
                    "RA-001",
                    new TimetableConflictChecker.Platform(WIDE + ":1", WIDE, false),
                    0,
                    100,
                    Optional.of("OTHER-TIMETABLE"))));
    assertEquals(
        ConflictAbsorption.Verdict.NO_WAITING_CAPACITY,
        ConflictAbsorption.classify(
                report(conflictOn("edge:REMOTE~NEXT", 60, 50)),
                timetableWithOriginAt(WIDE),
                occupancy,
                INDEX,
                SEPARATION,
                MAX_WAIT)
            .get(0)
            .verdict());
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
    return conflictOn(resource, firstTo, secondFrom, second, "RB-001");
  }

  /** 同上，另外指定前车的 code。 */
  private static TimetableConflictChecker.Conflict conflictOn(
      String resource, int firstTo, int secondFrom, String second, String first) {
    return new TimetableConflictChecker.Conflict(
        resource.startsWith("platform")
            ? TimetableConflictChecker.Kind.PLATFORM
            : resource.startsWith("single")
                ? TimetableConflictChecker.Kind.SINGLE_LINE
                : TimetableConflictChecker.Kind.TRACK,
        resource,
        first,
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
