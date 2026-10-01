package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

/**
 * 目标间隔排不开时，搜索先让所有组等比放宽，再逐组收紧：没卡住的组回到自己的目标间隔。
 *
 * <p>路网是两条互不相干的单线：tight 组走 DEP–A–B–C，free 组走 DQ–X–Y–Z。终点 C、Z 都只有一股道，每辆车跑一趟就回库、终点折返 180 秒，
 * 所以每条线的间隔至少要容得下一次折返加裕量。tight 目标 120 秒必然排不开；free 目标 600 秒本来就排得开。
 */
class TimetableBuilderTighteningTest {

  private static final Instant BUILT_AT = Instant.parse("2026-03-01T00:00:00Z");
  private static final ZoneId ZONE = ZoneId.of("UTC");

  private static final String DEP = "OP:D:DEP:1";
  private static final String A = "OP:S:A:1";
  private static final String B = "OP:S:B:1";
  private static final String C = "OP:S:C:1";
  private static final String DQ = "OP:D:DQ:1";
  private static final String X = "OP:S:X:1";
  private static final String Y = "OP:S:Y:1";
  private static final String Z = "OP:S:Z:1";

  private final RailGraph graph =
      TimetableTestFixtures.graph(
          new LinkedHashMap<>(
              Map.of(
                  DEP, NodeType.DEPOT,
                  A, NodeType.STATION,
                  B, NodeType.STATION,
                  C, NodeType.STATION,
                  DQ, NodeType.DEPOT,
                  X, NodeType.STATION,
                  Y, NodeType.STATION,
                  Z, NodeType.STATION)),
          List.of(
              new TimetableTestFixtures.Edge(DEP, A, 50, 10.0),
              new TimetableTestFixtures.Edge(A, B, 100, 10.0),
              new TimetableTestFixtures.Edge(B, C, 100, 10.0),
              new TimetableTestFixtures.Edge(DQ, X, 50, 10.0),
              new TimetableTestFixtures.Edge(X, Y, 100, 10.0),
              new TimetableTestFixtures.Edge(Y, Z, 100, 10.0)));

  /**
   * 等比放宽会把 free 一起拖到 1000 秒以上（tight 至少要放宽到 1.75 倍）；逐组收紧后 free 回到 600 秒，报告写明收紧了什么， 按收紧后的间隔重建没有冲突。
   */
  @Test
  void groupsThatDoNotConflictReturnToTheirTarget() {
    TimetableBuildResult result = build(options(Map.of("tight", 120, "free", 600)));

    assertTrue(result.success(), () -> result.warnings().toString());
    assertTrue(result.headwayRelaxed(), "tight 目标 120 秒必然排不开");
    Map<String, Integer> effective = effective(result);
    assertTrue(effective.get("tight") >= 210, () -> "tight 至少要容下一次折返: " + effective);
    assertEquals(600, effective.get("free"), () -> "free 没有卡住任何东西，应当回到目标: " + effective);
    assertTrue(
        result.warnings().stream()
            .anyMatch(text -> text.startsWith("逐组收紧：free") && text.endsWith("（回到目标）")),
        () -> result.warnings().toString());
    // 回退提示只列仍被放宽的组：最小的组间隔已不能代表"放宽到多少"。
    String fallback =
        result.warnings().stream()
            .filter(text -> text.contains("已回退到最小可行间隔"))
            .findFirst()
            .orElseThrow();
    assertTrue(fallback.contains("tight 120→" + effective.get("tight") + "s"), fallback);
    assertFalse(fallback.contains("free"), fallback);

    TimetableBuildResult rebuilt = build(options(effective));
    assertTrue(rebuilt.conflictsAtTarget().isEmpty(), () -> rebuilt.warnings().toString());
    assertFalse(rebuilt.headwayRelaxed());
  }

  /** 一组也没放宽（目标间隔下靠喂车方向多停就排开了）不说"回退"；放宽了只列放宽的组，没有交路组时比总间隔。 */
  @Test
  void onlyWidenedIntervalsAreReportedAsAFallback() {
    assertEquals(
        Optional.empty(),
        TimetableBuilder.widenedIntervals(
            Map.of("a", 360, "b", 720), 360, Map.of("a", 360, "b", 720), 360));
    assertEquals(
        Optional.of("a 360→400s"),
        TimetableBuilder.widenedIntervals(
            Map.of("a", 360, "b", 720), 360, Map.of("a", 400, "b", 720), 400));
    assertEquals(
        Optional.empty(),
        TimetableBuilder.widenedIntervals(
            Map.of("default", 360), 360, Map.of("default", 360), 360));
    assertEquals(
        Optional.of("400s"),
        TimetableBuilder.widenedIntervals(
            Map.of("default", 360), 360, Map.of("default", 400), 400));
  }

  /** 收紧只在搜索之后发生：目标间隔本来就排得开时不放宽、也不收紧。 */
  @Test
  void feasibleTargetsAreKeptAsIs() {
    TimetableBuildResult result = build(options(Map.of("tight", 600, "free", 600)));

    assertTrue(result.success(), () -> result.warnings().toString());
    assertFalse(result.headwayRelaxed());
    assertEquals(Map.of("tight", 600, "free", 600), effective(result));
    assertTrue(result.warnings().stream().noneMatch(text -> text.startsWith("逐组收紧")));
  }

  /**
   * 剪枝的覆盖关系：单元内间隔相同、单元外每一组都不比候选紧的失败，才算已经证伪了这个候选。
   *
   * <p>实服三线联编：等比搜索在 WS/MT 156、DS 208 失败过；收紧 WS 时的候选是 WS 156、MT 151/150、DS 200——单元外更紧，跳过。
   */
  @Test
  void aFailureCoversCandidatesThatAreTighterOutsideTheUnit() {
    Map<String, Integer> searched = Map.of("WS", 156, "MT", 156, "DS", 208);
    Set<String> unit = Set.of("WS");

    assertTrue(
        TimetableBuilder.dominates(searched, Map.of("WS", 156, "MT", 150, "DS", 200), unit),
        "单元外更紧：注定更难，跳过");
    assertFalse(
        TimetableBuilder.dominates(searched, Map.of("WS", 161, "MT", 150, "DS", 200), unit),
        "单元内间隔不同：没试过");
    assertFalse(
        TimetableBuilder.dominates(searched, Map.of("WS", 156, "MT", 170, "DS", 200), unit),
        "单元外有一组更松：那次失败说明不了这里");
    assertFalse(
        TimetableBuilder.dominates(Map.of("WS", 156), Map.of("WS", 156, "MT", 150), unit),
        "那次失败里没有这个组：不能推断");
  }

  private static Map<String, Integer> effective(TimetableBuildResult result) {
    return result.groupIntervals().stream()
        .collect(
            Collectors.toMap(
                TimetableBuildResult.GroupInterval::group,
                TimetableBuildResult.GroupInterval::effectiveSeconds));
  }

  private TimetableBuildResult build(TimetableBuildOptions options) {
    return new TimetableBuilder()
        .build(
            new TimetableBuilder.BuildInput(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "TT",
                "收紧",
                List.of(
                    operation("RA", List.of(A, B, C), "tight"),
                    leg("CRT", RouteOperationType.CREATE, List.of(DEP, A), DEP, "tight"),
                    leg("RET", RouteOperationType.RETURN, List.of(C, DEP), DEP, "tight"),
                    operation("RX", List.of(X, Y, Z), "free"),
                    leg("CRQ", RouteOperationType.CREATE, List.of(DQ, X), DQ, "free"),
                    leg("REQ", RouteOperationType.RETURN, List.of(Z, DQ), DQ, "free")),
                graph,
                TimetableTestFixtures.perEdgeSpeedModel(),
                Optional.empty()),
            options,
            BUILT_AT);
  }

  private static TimetableBuildOptions options(Map<String, Integer> intervals) {
    int fallback = intervals.values().stream().mapToInt(Integer::intValue).min().orElse(600);
    return new TimetableBuildOptions(
            5 * 3600,
            7 * 3600,
            Duration.ofSeconds(fallback),
            Duration.ofSeconds(0),
            new VehicleDutyPlanner.Limits(1, 5400, 180),
            "",
            ZONE)
        .withIntervals(Duration.ofSeconds(fallback), intervals);
  }

  private static TimetableBuilder.RouteInput operation(
      String code, List<String> nodes, String group) {
    UUID routeId = TimetableTestFixtures.routeId(code);
    return new TimetableBuilder.RouteInput(
        routeId,
        code,
        RouteOperationType.OPERATION,
        1,
        TimetableTestFixtures.route(code, nodes),
        TimetableTestFixtures.stops(routeId, nodes.size(), 0),
        Optional.empty(),
        Optional.empty(),
        false,
        Optional.of(group));
  }

  private static TimetableBuilder.RouteInput leg(
      String code, RouteOperationType type, List<String> nodes, String depot, String group) {
    UUID routeId = TimetableTestFixtures.routeId(code);
    return new TimetableBuilder.RouteInput(
        routeId,
        code,
        type,
        0,
        TimetableTestFixtures.route(code, nodes),
        type == RouteOperationType.CREATE
            ? TimetableTestFixtures.createStops(routeId, nodes.size(), depot)
            : TimetableTestFixtures.returnStops(routeId, nodes.size(), depot),
        Optional.empty(),
        Optional.empty(),
        false,
        Optional.of(group));
  }
}
