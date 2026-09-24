package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 相位两层：往返对锚定在前，组间在共用起点上交错在后；全部确定。 */
class PhasePlannerTest {

  private static final UUID RA = TimetableTestFixtures.routeId("RA");
  private static final UUID RB = TimetableTestFixtures.routeId("RB");
  private static final UUID RS = TimetableTestFixtures.routeId("RS");
  private static final UUID RT = TimetableTestFixtures.routeId("RT");

  private static ServiceGroupClassifier.Direction direction(
      String from, String to, String code, UUID id) {
    return new ServiceGroupClassifier.Direction(
        from, to, List.of(new WeightedTripAllocator.Candidate(code, 1)), List.of(id));
  }

  /** 反向相位 = 正向走行 + 折返，对间隔取模；键较小的方向是正向、相位 0。 */
  @Test
  void returnPairIsAnchoredOnRunPlusTurnaround() {
    ServiceGroupClassifier.Group group =
        new ServiceGroupClassifier.Group(
            "default",
            List.of(
                direction("OP:S:A", "OP:S:C", "RA", RA), direction("OP:S:C", "OP:S:A", "RB", RB)),
            List.of());

    PhasePlanner.Phases phases =
        PhasePlanner.plan(
            List.of(group),
            Map.of("default", 600),
            Map.of(RA, 500, RB, 500),
            TurnaroundTable.fixed(180),
            3600,
            Map.of());

    assertEquals(0, phases.phaseByDirection().get("OP:S:A→OP:S:C"));
    assertEquals((500 + 180) % 600, phases.phaseByDirection().get("OP:S:C→OP:S:A"));
    assertEquals(0, phases.offsetByGroup().get("default"));
    assertTrue(phases.notes().stream().anyMatch(note -> note.contains("锚定")));
  }

  /** 没有配对的方向相位 0；走行取方向内最短的候选。 */
  @Test
  void unpairedDirectionStaysAtZero() {
    ServiceGroupClassifier.Group group =
        new ServiceGroupClassifier.Group(
            "default", List.of(direction("OP:S:A", "OP:S:C", "RA", RA)), List.of());

    PhasePlanner.Phases phases =
        PhasePlanner.plan(
            List.of(group),
            Map.of("default", 600),
            Map.of(RA, 500),
            TurnaroundTable.fixed(180),
            3600,
            Map.of());

    assertEquals(Map.of("OP:S:A→OP:S:C", 0), phases.phaseByDirection());
    assertTrue(phases.notes().isEmpty());
  }

  /** 两组同间隔、共用起点 A：第二组偏移半个间隔，A 上的合成间隔从 600 变成 300。 */
  @Test
  void secondGroupInterleavesAtTheSharedOrigin() {
    ServiceGroupClassifier.Group full =
        new ServiceGroupClassifier.Group(
            "full", List.of(direction("OP:S:A", "OP:S:C", "RA", RA)), List.of());
    ServiceGroupClassifier.Group shortTurn =
        new ServiceGroupClassifier.Group(
            "short", List.of(direction("OP:S:A", "OP:S:B", "RS", RS)), List.of());

    PhasePlanner.Phases phases =
        PhasePlanner.plan(
            List.of(full, shortTurn),
            Map.of("full", 600, "short", 600),
            Map.of(RA, 500, RS, 200),
            TurnaroundTable.fixed(180),
            3600,
            Map.of());

    assertEquals(0, phases.phaseByDirection().get("OP:S:A→OP:S:C"));
    assertEquals(300, phases.phaseByDirection().get("OP:S:A→OP:S:B"));
    assertEquals(300, phases.offsetByGroup().get("short"));
    assertTrue(
        phases.notes().stream().anyMatch(note -> note.contains("交错") && note.contains("300s")));
  }

  /** 600 与 300 共用起点：只看最大间隔时偏移 0（同时发车）与偏移 150 打平；最小间隔最大把它定在 150。 */
  @Test
  void tieOnMaxGapIsBrokenByTheTightestGap() {
    ServiceGroupClassifier.Group full =
        new ServiceGroupClassifier.Group(
            "full", List.of(direction("OP:S:A", "OP:S:C", "RA", RA)), List.of());
    ServiceGroupClassifier.Group shortTurn =
        new ServiceGroupClassifier.Group(
            "short", List.of(direction("OP:S:A", "OP:S:B", "RS", RS)), List.of());

    PhasePlanner.Phases phases =
        PhasePlanner.plan(
            List.of(full, shortTurn),
            Map.of("full", 600, "short", 300),
            Map.of(RA, 500, RS, 200),
            TurnaroundTable.fixed(180),
            3600,
            Map.of());

    assertEquals(150, phases.offsetByGroup().get("short"));
  }

  /**
   * 起点各不相同、却在沿途重合的两组，要按<b>合流点</b>交错——这正是只按共用起点做不到的那件事。
   *
   * <p>大交路 A→D、小交路 B→D，两组都在 C 停靠、都往 D 去，于是共用合流点 {@code OP:S:C→OP:S:D}。 起点一个是 A 一个是
   * B，老的"共用起点"判据在这里一个共用点都找不到，两组永远不会被错开。
   */
  @Test
  void groupsWithDifferentOriginsAreInterleavedAtASharedMergePoint() {
    ServiceGroupClassifier.Group full =
        new ServiceGroupClassifier.Group(
            "full", List.of(direction("OP:S:A", "OP:S:D", "RA", RA)), List.of());
    ServiceGroupClassifier.Group shortTurn =
        new ServiceGroupClassifier.Group(
            "short", List.of(direction("OP:S:B", "OP:S:D", "RS", RS)), List.of());
    // 两组到 C 的走行时分都是 400s：不把小交路推开，两班就在 C 同时发车。
    Map<String, List<PhasePlanner.StopCall>> calls =
        Map.of(
            "OP:S:A→OP:S:D",
                List.of(
                    new PhasePlanner.StopCall("OP:S:A→OP:S:C", 0),
                    new PhasePlanner.StopCall("OP:S:C→OP:S:D", 400)),
            "OP:S:B→OP:S:D",
                List.of(
                    new PhasePlanner.StopCall("OP:S:B→OP:S:C", 0),
                    new PhasePlanner.StopCall("OP:S:C→OP:S:D", 400)));

    PhasePlanner.Phases phases =
        PhasePlanner.plan(
            List.of(full, shortTurn),
            Map.of("full", 600, "short", 600),
            Map.of(RA, 500, RS, 200),
            TurnaroundTable.fixed(180),
            3600,
            calls);

    // 两班都在发车后 400s 到 C，要在 C 上把 600s 均分，小交路得整体推半个间隔。
    assertEquals(300, phases.offsetByGroup().get("short"));
    assertTrue(
        phases.notes().stream().anyMatch(note -> note.contains("合流点")), phases.notes().toString());
  }

  /**
   * 窗口太短、合流点上量不出两条发车时，这个候选偏移不能当成"最大间隔 0"——0 是最好的分数， 量不出来的候选会因此盖掉真正交错得好的那些。
   *
   * <p>这里窗口 600s、间隔 600s，每条流在公共区间里至多一个点，所有候选都量不出来：此时不该有任何一个 候选凭 0 分胜出，偏移保持 0。
   */
  @Test
  void anUnmeasurableMergePointDoesNotScoreAsAPerfectOffset() {
    ServiceGroupClassifier.Group full =
        new ServiceGroupClassifier.Group(
            "full", List.of(direction("OP:S:A", "OP:S:D", "RA", RA)), List.of());
    ServiceGroupClassifier.Group shortTurn =
        new ServiceGroupClassifier.Group(
            "short", List.of(direction("OP:S:B", "OP:S:D", "RS", RS)), List.of());
    Map<String, List<PhasePlanner.StopCall>> calls =
        Map.of(
            "OP:S:A→OP:S:D", List.of(new PhasePlanner.StopCall("OP:S:C→OP:S:D", 400)),
            "OP:S:B→OP:S:D", List.of(new PhasePlanner.StopCall("OP:S:C→OP:S:D", 100)));

    PhasePlanner.Phases phases =
        PhasePlanner.plan(
            List.of(full, shortTurn),
            Map.of("full", 600, "short", 600),
            Map.of(RA, 500, RS, 200),
            TurnaroundTable.fixed(180),
            600,
            calls);

    assertEquals(0, phases.offsetByGroup().get("short"));
  }

  /** 算不出沿途合流点的方向会退回只按起点交错，与算得出的方向对不上——这件事必须说出来。 */
  @Test
  void aDirectionWithoutMergePointsIsCalledOutInTheNotes() {
    ServiceGroupClassifier.Group full =
        new ServiceGroupClassifier.Group(
            "full", List.of(direction("OP:S:A", "OP:S:D", "RA", RA)), List.of());
    ServiceGroupClassifier.Group shortTurn =
        new ServiceGroupClassifier.Group(
            "short", List.of(direction("OP:S:B", "OP:S:D", "RS", RS)), List.of());
    // 只给大交路算出了合流点，小交路没有。
    Map<String, List<PhasePlanner.StopCall>> calls =
        Map.of("OP:S:A→OP:S:D", List.of(new PhasePlanner.StopCall("OP:S:C→OP:S:D", 400)));

    PhasePlanner.Phases phases =
        PhasePlanner.plan(
            List.of(full, shortTurn),
            Map.of("full", 600, "short", 600),
            Map.of(RA, 500, RS, 200),
            TurnaroundTable.fixed(180),
            3600,
            calls);

    assertTrue(
        phases.notes().stream()
            .anyMatch(note -> note.contains("算不出沿途合流点") && note.contains("OP:S:B→OP:S:D")),
        phases.notes().toString());
  }

  /** 交错报告：按起点站台组叠加各子网格的发车，给出最小/中位/最大相邻间隔；只有一条发车的起点不列。 */
  @Test
  void interleaveReportSummarisesGapsPerOrigin() {
    ServiceGroupClassifier.Direction ac = direction("OP:S:A", "OP:S:C", "RA", RA);
    ServiceGroupClassifier.Direction ab = direction("OP:S:A", "OP:S:B", "RS", RS);
    ServiceGroupClassifier.Direction ca = direction("OP:S:C", "OP:S:A", "RT", RT);
    List<GroupGrid.DirectionGrid> grids =
        List.of(
            GroupGrid.of(ac, 600, 0, 1800, (s, i, a) -> true),
            GroupGrid.of(ab, 600, 200, 1800, (s, i, a) -> true),
            GroupGrid.of(ca, 5000, 0, 1800, (s, i, a) -> true));

    List<PhasePlanner.Interleave> report = PhasePlanner.interleaves(grids);

    assertEquals(1, report.size());
    PhasePlanner.Interleave a = report.get(0);
    assertEquals("OP:S:A", a.originGroup());
    assertEquals(7, a.departures());
    assertEquals(200, a.minGap());
    assertEquals(400, a.maxGap());
  }

  // ------------------------------------------------------------------ 跨组按车接续

  /**
   * 仿 WS：大交路 A↔Z 在 A 是单股道端点，A→Z 是正向（它的起点 A 没有本对的车喂）；小交路 M→A 到 A 折返后接 A→Z。
   *
   * <p>小交路的相位由车决定：到站 226 + 折返 20 正好是 A→Z 的发车（0），即 (0 − 20 − 226) mod 150 = 54，不按合流点扫。 然后 Z→A
   * 在远端多等，让它回到 A 与小交路到站错开半个周期：原本相距 3 秒，多等 78 秒后相距 75 秒。
   */
  @Test
  void feederIsAnchoredToTheFedDirectionAtAStubTerminal() {
    PhasePlanner.Phases phases = planWsLike(55);

    assertEquals(0, phases.phaseByDirection().get("OP:S:A→OP:S:Z"));
    assertEquals(54, phases.phaseByDirection().get("OP:S:M→OP:S:A"), "到站 + 折返 = 被接那一班发车");
    assertEquals(54, phases.offsetByGroup().get("short"));
    // 锚定给的 (575 + 20) mod 150 = 145，再多等 78。
    assertEquals((145 + 78) % 150, phases.phaseByDirection().get("OP:S:Z→OP:S:A"));
    assertEquals(1, phases.connections().size());
    PhasePlanner.Connection connection = phases.connections().get(0);
    assertEquals("OP:S:A", connection.terminal());
    assertEquals("OP:S:M→OP:S:A", connection.feederKey());
    assertEquals("OP:S:A→OP:S:Z", connection.fedKey());
    assertEquals("OP:S:Z→OP:S:A", connection.backKey());
    assertEquals(List.of(RS), connection.feederRoutes());
    assertEquals(List.of(RA), connection.fedRoutes());
    assertEquals(78, connection.farEndWaitSeconds());
    int feederArrival = Math.floorMod(54 + 226, 150);
    int backArrival = Math.floorMod(phases.phaseByDirection().get("OP:S:Z→OP:S:A") + 582, 150);
    assertEquals(75, Math.floorMod(backArrival - feederArrival, 150), "两次折返错开半个周期");
    assertTrue(
        phases.notes().stream().anyMatch(note -> note.contains("按车接续")), phases.notes().toString());
  }

  /** 远端多等不能超过"间隔 − 远端一次折返的占用"：再久下一班到远端时上一辆车还占着那股道。 */
  @Test
  void farEndWaitIsCappedByTheFarEndOccupancy() {
    // 远端 Z 一次折返占 120 秒，端点 A 仍是 55。
    PhasePlanner.Phases phases =
        PhasePlanner.plan(
            wsLikeGroups(),
            Map.of("full", 150, "short", 150),
            Map.of(RA, 575, RB, 582, RS, 226),
            TurnaroundTable.fixed(20),
            3600,
            Map.of(),
            new PhasePlanner.Topology(
                Set.of("OP:S:A"), (g, a, d) -> g.equals("OP:S:Z") ? 120 : 55));

    PhasePlanner.Connection connection = phases.connections().get(0);
    assertEquals(30, connection.farEndWaitSeconds(), "上限 150 − 120 = 30，取上限内错开最远的");
    assertEquals((145 + 30) % 150, phases.phaseByDirection().get("OP:S:Z→OP:S:A"));
  }

  /** 被接方向的起点若是它自己往返对的锚定端（本对的车已经喂它），就不做跨组接续，小交路照旧按合流点交错。 */
  @Test
  void noConnectionWhereTheFedOriginIsAlreadyFedByItsOwnPair() {
    // 端点叫 Z：Z→A 的键比 A→Z 大，于是它是反向，起点 Z 已经由 A→Z 的车喂。
    ServiceGroupClassifier.Group full =
        new ServiceGroupClassifier.Group(
            "full",
            List.of(
                direction("OP:S:A", "OP:S:Z", "RA", RA), direction("OP:S:Z", "OP:S:A", "RB", RB)),
            List.of());
    ServiceGroupClassifier.Group shortTurn =
        new ServiceGroupClassifier.Group(
            "short", List.of(direction("OP:S:M", "OP:S:Z", "RS", RS)), List.of());

    PhasePlanner.Phases phases =
        PhasePlanner.plan(
            List.of(full, shortTurn),
            Map.of("full", 150, "short", 150),
            Map.of(RA, 575, RB, 582, RS, 226),
            TurnaroundTable.fixed(20),
            3600,
            Map.of(),
            new PhasePlanner.Topology(Set.of("OP:S:Z"), (g, a, d) -> 55));

    assertTrue(phases.connections().isEmpty(), phases.notes().toString());
  }

  /** 不给路网形状时与只有两层的旧行为逐字段相同：没有接续，没有远端多等。 */
  @Test
  void withoutTopologyThereAreNoConnections() {
    PhasePlanner.Phases withShape = planWsLike(55);
    PhasePlanner.Phases without =
        PhasePlanner.plan(
            wsLikeGroups(),
            Map.of("full", 150, "short", 150),
            Map.of(RA, 575, RB, 582, RS, 226),
            TurnaroundTable.fixed(20),
            3600,
            Map.of());

    assertTrue(without.connections().isEmpty());
    assertEquals((575 + 20) % 150, without.phaseByDirection().get("OP:S:Z→OP:S:A"), "没有接续就没有远端多等");
    assertEquals(1, withShape.connections().size());
  }

  /**
   * 远端多等同时看车库咽喉：只看端点时选 78 秒（端点两次折返间隙 20），可那样喂车方向出库与反向车回库在咽喉上重叠 12 秒——
   * 咽喉一串行，出库车在库里一等就晚到端点。两边都看时取两个间隙里较小的最大：94 秒，端点与咽喉各留 4 秒。
   *
   * <p>几何照 WS@150：出库在发车后 6–62 秒占咽喉（已含裕量），回库在反向车到端点后 199–252 秒占咽喉；端点每次折返占 55 秒。
   */
  @Test
  void farEndWaitBalancesTheTerminalAndTheDepotThroat() {
    PhasePlanner.Phases phases =
        PhasePlanner.plan(
            wsLikeGroups(),
            Map.of("full", 150, "short", 150),
            Map.of(RA, 575, RB, 582, RS, 226),
            TurnaroundTable.fixed(20),
            3600,
            Map.of(),
            new PhasePlanner.Topology(
                Set.of("OP:S:A"),
                (g, a, d) -> 55,
                (feeder, back) ->
                    java.util.Optional.of(new PhasePlanner.ThroatWindows(6, 62, 199, 252))));

    PhasePlanner.Connection connection = phases.connections().get(0);
    assertEquals(94, connection.farEndWaitSeconds());
    int feederDeparture = 54;
    int feederArrival = 130;
    int backArrival = 127 + 94;
    assertEquals(
        4,
        PhasePlanner.arcGap(feederArrival, feederArrival + 55, backArrival, backArrival + 55, 150));
    assertEquals(
        4,
        PhasePlanner.arcGap(
            feederDeparture + 6, feederDeparture + 62, backArrival + 199, backArrival + 252, 150));
    // 只看端点的那个 78 秒：咽喉重叠 12 秒。
    assertEquals(
        -12,
        PhasePlanner.arcGap(
            feederDeparture + 6, feederDeparture + 62, 127 + 78 + 199, 127 + 78 + 252, 150));
  }

  /** 圆周上两段弧的间隙：不相交时取两侧较小的空档，相交时是负的重叠秒数，跨零点照算。 */
  @Test
  void arcGapHandlesWrapAround() {
    assertEquals(10, PhasePlanner.arcGap(0, 50, 60, 100, 150));
    assertEquals(-20, PhasePlanner.arcGap(0, 50, 30, 80, 150));
    assertEquals(
        20,
        PhasePlanner.arcGap(130, 160, 40, 110, 150),
        "130–160 跨零点到 10，与 40 隔 30、110 到 130 隔 20");
    assertEquals(-5, PhasePlanner.arcGap(100, 150, 145, 170, 150));
  }

  private static List<ServiceGroupClassifier.Group> wsLikeGroups() {
    ServiceGroupClassifier.Group full =
        new ServiceGroupClassifier.Group(
            "full",
            List.of(
                direction("OP:S:A", "OP:S:Z", "RA", RA), direction("OP:S:Z", "OP:S:A", "RB", RB)),
            List.of());
    ServiceGroupClassifier.Group shortTurn =
        new ServiceGroupClassifier.Group(
            "short", List.of(direction("OP:S:M", "OP:S:A", "RS", RS)), List.of());
    return List.of(full, shortTurn);
  }

  private static PhasePlanner.Phases planWsLike(int farEndOccupancy) {
    return PhasePlanner.plan(
        wsLikeGroups(),
        Map.of("full", 150, "short", 150),
        Map.of(RA, 575, RB, 582, RS, 226),
        TurnaroundTable.fixed(20),
        3600,
        Map.of(),
        new PhasePlanner.Topology(Set.of("OP:S:A"), (g, a, d) -> farEndOccupancy));
  }
}
