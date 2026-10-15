package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

/**
 * 快车错峰的加停、加长折返候选在编表器里怎么编（{@link TimetableBuilder#staggerCandidate}，与错峰搜索同一条路径）：加长折返整套重选、
 * 中途加停从原表相位热启动、热启动用上了却不可用时整套重选一次。
 *
 * <p>形状：慢车 A1→E1 / E1→A1，快车 A2→F1 / F1→A2，B–C–D 上下行各一股道两组共用。A 端两个站台各有出入库线路，E、F 没有（原地折返端），
 * 所以两组的往返对都以 A→E、A→F 为正向，反向在 E、F 按"正向走行 + 折返"锚定。没有 {@code --turnaround}，折返时间取各 route 终到停站。
 */
class TimetableBuilderStaggerWarmStartTest {

  private static final Instant BUILT_AT = Instant.parse("2026-03-01T00:00:00Z");
  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final String DEP = "OP:D:DEP:1";
  private static final String A1 = "OP:S:A:1";
  private static final String A2 = "OP:S:A:2";
  private static final String B1 = "OP:S:B:1";
  private static final String C1 = "OP:S:C:1";
  private static final String D1 = "OP:S:D:1";
  private static final String E1 = "OP:S:E:1";
  private static final String F1 = "OP:S:F:1";
  private static final String B2 = "OP:S:B:2";
  private static final String C2 = "OP:S:C:2";
  private static final String D2 = "OP:S:D:2";
  private static final int LOCAL_DWELL = 60;

  private final RailGraph graph = graph();
  private final UUID local = TimetableTestFixtures.routeId("LOC");
  private final UUID localBack = TimetableTestFixtures.routeId("LOCR");
  private final UUID rapid = TimetableTestFixtures.routeId("RAP");
  private final UUID rapidBack = TimetableTestFixtures.routeId("RAPR");

  /**
   * 加长折返不热启动：快车在 F 终到多停 60 秒，反向按"走行 + 折返"重新锚定，每一班反向都比原表晚开 60 秒（相对同一班正向）；
   * 与整套重选编同一份输入的完整构建次数相同——没有先按原表相位白编一遍。
   *
   * <p>按原表相位编时反向发车与原表逐班相同，加长只落在派车接续上，这类候选的作用就没了。
   */
  @Test
  void aLongerTurnbackReanchorsTheReverseDirection() {
    RapidStagger.Dwell turnback = new RapidStagger.Dwell(rapid, "RAP", 4, F1, 60, true);

    Counted candidate = candidate(turnback, () -> false);
    Counted cold = cold(route -> route.routeId().equals(rapid) ? held(route, 4, 60) : route);

    assertTrue(candidate.result().isPresent(), "这一档加长折返在目标间隔下排得开");
    TimetableBuildResult built = candidate.result().get();
    assertEquals(uniformGap(base()) + 60, uniformGap(built), "反向相对正向整体晚 60 秒");
    assertFalse(built.phaseNotes().contains(TimetableBuilder.WARM_START_NOTE), built::toString);
    assertEquals(cold.result().orElseThrow().timetable(), built.timetable(), "就是整套重选的结果");
    assertEquals(cold.builds(), candidate.builds(), "只编一次整套重选");
  }

  /** 没用上热启动的候选不可用时不再重编：加长 900 秒的候选整套重选编出来就不可用，再编一次也一样。 */
  @Test
  void anUnusableCandidateWithoutAWarmStartIsBuiltOnce() {
    Counted candidate =
        candidate(new RapidStagger.Dwell(rapid, "RAP", 4, F1, 900, true), () -> false);
    Counted cold = cold(route -> route.routeId().equals(rapid) ? held(route, 4, 900) : route);

    assertTrue(candidate.result().isEmpty(), "这一档不可用");
    assertEquals(cold.builds(), candidate.builds());
  }

  /** 中途加停热启动：反向快车在 C 多停，相位沿用原表（每一班发车与原表相同），只编一次；说明里写明沿用原表相位。 加停 10 秒时整套重选选出的也是原表相位，两者逐班相同。 */
  @Test
  void aMidRouteHoldStartsFromTheOriginalPhases() {
    TimetableBuildResult base = base();
    for (int seconds : new int[] {10, 30}) {
      Counted candidate =
          candidate(new RapidStagger.Dwell(rapidBack, "RAPR", 2, C2, seconds), () -> false);

      assertTrue(candidate.result().isPresent(), "加停 " + seconds);
      TimetableBuildResult built = candidate.result().get();
      assertTrue(built.phaseNotes().contains(TimetableBuilder.WARM_START_NOTE), built::toString);
      assertTrue(
          built.phaseNotes().stream().noneMatch(note -> note.contains("锚定")),
          () -> "原表的相位说明不沿用：" + built.phaseNotes());
      assertEquals(1, candidate.builds(), "热启动只试排一次");
      for (UUID route : List.of(local, localBack, rapid, rapidBack)) {
        assertEquals(departures(base, route), departures(built, route), "加停 " + seconds);
      }
    }
    Counted cold = cold(route -> route.routeId().equals(rapidBack) ? held(route, 2, 10) : route);
    assertEquals(
        cold.result().orElseThrow().timetable(),
        candidate(new RapidStagger.Dwell(rapidBack, "RAPR", 2, C2, 10), () -> false)
            .result()
            .orElseThrow()
            .timetable());
  }

  /**
   * 热启动用上了却不可用时整套重选一次：正向快车在 C 多停 60 秒，原表相位上反向在 F 等不到这班车（F 没有车库）而不可用， 整套重选把反向重新锚定后可用。完整构建次数 = 热启动一次
   * + 整套重选一次；到了错峰时间上限就不重编，候选为空。
   */
  @Test
  void anUnusableWarmStartFallsBackOnceUnlessTheTimeIsUp() {
    RapidStagger.Dwell hold = new RapidStagger.Dwell(rapid, "RAP", 2, C1, 60);

    Counted candidate = candidate(hold, () -> false);
    Counted cold = cold(route -> route.routeId().equals(rapid) ? held(route, 2, 60) : route);
    Counted expired = candidate(hold, () -> true);

    assertTrue(candidate.result().isPresent());
    TimetableBuildResult built = candidate.result().get();
    assertFalse(built.phaseNotes().contains(TimetableBuilder.WARM_START_NOTE), built::toString);
    assertEquals(cold.result().orElseThrow().timetable(), built.timetable());
    assertEquals(1 + cold.builds(), candidate.builds());
    assertTrue(expired.result().isEmpty(), "到点不重编，热启动那份不可用就淘汰");
    assertEquals(1, expired.builds());
  }

  /**
   * 热启动被拒（原表相位上让不掉的冲突比原表多）时，同一次构建里就退回整套重选；整套重选编出来仍不可用，候选为空，不再重编—— 重编只看热启动有没有真的用上，不看候选开没开热启动。完整构建次数
   * = 被拒的热启动一次 + 整套重选一次。
   *
   * <p>到了错峰时间上限次数也一样：退回整套重选是在热启动被拒的那次构建里，不是事后的重编。
   */
  @Test
  void aRejectedWarmStartIsNotRebuiltWhenTheReselectionIsUnusable() {
    int seconds = 300;
    RapidStagger.Dwell hold = new RapidStagger.Dwell(rapidBack, "RAPR", 2, C2, seconds);

    Counted candidate = candidate(hold, () -> false);
    Counted expired = candidate(hold, () -> true);
    TimetableBuildProgress progress = new TimetableBuildProgress();
    TimetableBuildResult reselected =
        new TimetableBuilder(new TimetableTimingCalculator(), Map.of(), false, progress)
            .build(
                input(route -> route.routeId().equals(rapidBack) ? held(route, 2, seconds) : route),
                options(false),
                BUILT_AT);
    int reselection = progress.snapshot().fullBuilds();

    assertFalse(
        RapidStagger.acceptable(
            RapidStagger.Outcome.of(base()), RapidStagger.Outcome.of(reselected)),
        () -> "整套重选也不可用：" + reselected);
    assertTrue(candidate.result().isEmpty(), "这一档不可用");
    assertEquals(1 + reselection, candidate.builds(), "被拒的热启动一次 + 整套重选一次，不再重编");
    assertTrue(expired.result().isEmpty());
    assertEquals(1 + reselection, expired.builds(), "热启动被拒，退回整套重选与时间上限无关");
  }

  /**
   * 热启动认原表只看参数（{@link TimetableBuilder.TargetMemo#originalFor}）：准备不同照认（中途加停的候选），折返表不同不认——
   * 终到停站改了，反向相位的锚定跟着变，原表的相位不再是这组参数下的锚定。与"加长折返不开热启动"是有意重复的两道闸，这里单独钉住这一道。
   */
  @Test
  void theOriginalPhasesAreNotReusedUnderAnotherTurnaroundTable() {
    TimetableBuildOptions original =
        options(false)
            .withTurnaround(TurnaroundTable.ofSeconds(Map.of(rapid, 20, rapidBack, 20), 20));
    TimetableBuildOptions longer =
        original.withTurnaround(TurnaroundTable.ofSeconds(Map.of(rapid, 80, rapidBack, 20), 20));
    TimetableBuilder.TargetMemo<Object, String> memo = new TimetableBuilder.TargetMemo<>();

    assertTrue(memo.originalFor(original).isEmpty(), "还没记下原表");
    Object prepared = new Object();
    memo.offer(prepared, original, "原表");
    memo.offer(new Object(), original, "后来的候选");

    assertEquals(Optional.of("原表"), memo.originalFor(original), "只记第一份");
    assertEquals(
        Optional.of("原表"),
        memo.originalFor(
            options(false)
                .withTurnaround(
                    TurnaroundTable.ofSeconds(
                        new LinkedHashMap<>(Map.of(rapid, 20, rapidBack, 20)), 20))),
        "另编的一份参数、折返表逐项相同就认");
    assertTrue(memo.originalFor(longer).isEmpty(), "折返表不同不认原表");
    assertEquals(Optional.of("原表"), memo.find(prepared, original));
    assertTrue(memo.find(new Object(), original).isEmpty(), "复用整份试排还要同一份准备");
  }

  /** 各班反向发车减同一序号的正向发车：每一对都相同时返回它。 */
  private int uniformGap(TimetableBuildResult result) {
    List<Integer> forward = departures(result, rapid);
    List<Integer> reverse = departures(result, rapidBack);
    assertEquals(forward.size(), reverse.size(), result::toString);
    assertFalse(forward.isEmpty(), result::toString);
    int gap = reverse.get(0) - forward.get(0);
    for (int i = 0; i < forward.size(); i++) {
      assertEquals(gap, reverse.get(i) - forward.get(i), "第 " + i + " 对");
    }
    return gap;
  }

  /** 一次构建的结果与它用掉的完整构建次数。 */
  private record Counted(Optional<TimetableBuildResult> result, int builds) {}

  /** 原表（不开错峰）。 */
  private TimetableBuildResult base() {
    TimetableBuildResult result =
        new TimetableBuilder().build(input(UnaryOperator.identity()), options(false), BUILT_AT);
    assertTrue(result.success(), result::toString);
    return result;
  }

  /** 编原表再编这个候选；次数只数候选的（减去单编原表的次数）。 */
  private Counted candidate(RapidStagger.Dwell dwell, BooleanSupplier expired) {
    TimetableBuildProgress alone = new TimetableBuildProgress();
    new TimetableBuilder(alone, 1).build(input(UnaryOperator.identity()), options(false), BUILT_AT);
    TimetableBuildProgress progress = new TimetableBuildProgress();
    Optional<RapidStagger.Candidate> candidate =
        new TimetableBuilder(progress, 1)
            .staggerCandidate(
                input(UnaryOperator.identity()),
                options(true),
                BUILT_AT,
                Map.of(),
                Optional.of(dwell),
                expired);
    return new Counted(
        candidate.map(RapidStagger.Candidate::result),
        progress.snapshot().fullBuilds() - alone.snapshot().fullBuilds());
  }

  /** 候选编表器（不许放宽、不热启动）直接编改过停站的输入：整套重选的参照。 */
  private Counted cold(UnaryOperator<TimetableBuilder.RouteInput> change) {
    TimetableBuildProgress progress = new TimetableBuildProgress();
    TimetableBuildResult result =
        new TimetableBuilder(new TimetableTimingCalculator(), Map.of(), false, progress)
            .build(input(change), options(false), BUILT_AT);
    assertTrue(result.success(), result::toString);
    return new Counted(Optional.of(result), progress.snapshot().fullBuilds());
  }

  private static TimetableBuilder.RouteInput held(
      TimetableBuilder.RouteInput route, int stop, int seconds) {
    return TimetableBuilder.heldAt(route, stop, seconds, Duration.ofSeconds(20));
  }

  private TimetableBuildOptions options(boolean stagger) {
    return new TimetableBuildOptions(
            5 * 3600,
            8 * 3600,
            Duration.ofSeconds(400),
            Duration.ofSeconds(20),
            new VehicleDutyPlanner.Limits(100, 7200, TurnaroundTable.none()),
            "",
            ZONE,
            Duration.ofSeconds(TimetableBuildOptions.DEFAULT_SEPARATION_SECONDS),
            false,
            Map.of("local", 600, "rapid", 1200))
        .withRapidStagger(stagger);
  }

  private TimetableBuilder.BuildInput input(UnaryOperator<TimetableBuilder.RouteInput> change) {
    List<TimetableBuilder.RouteInput> routes = new ArrayList<>();
    routes.add(operation(local, "LOC", List.of(A1, B1, C1, D1, E1), LOCAL_DWELL, "local"));
    routes.add(operation(localBack, "LOCR", List.of(E1, D2, C2, B2, A1), LOCAL_DWELL, "local"));
    routes.add(operation(rapid, "RAP", List.of(A2, B1, C1, D1, F1), 0, "rapid"));
    routes.add(operation(rapidBack, "RAPR", List.of(F1, D2, C2, B2, A2), 0, "rapid"));
    routes.replaceAll(change);
    routes.add(leg("CRL", RouteOperationType.CREATE, List.of(DEP, A1)));
    routes.add(leg("REL", RouteOperationType.RETURN, List.of(A1, DEP)));
    routes.add(leg("CRR", RouteOperationType.CREATE, List.of(DEP, A2)));
    routes.add(leg("RER", RouteOperationType.RETURN, List.of(A2, DEP)));
    return new TimetableBuilder.BuildInput(
        UUID.nameUUIDFromBytes("RS".getBytes(StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes("C".getBytes(StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes("O".getBytes(StandardCharsets.UTF_8)),
        UUID.nameUUIDFromBytes("L".getBytes(StandardCharsets.UTF_8)),
        "TT1",
        "错峰热启动",
        List.copyOf(routes),
        graph,
        TimetableTestFixtures.perEdgeSpeedModel(),
        Optional.empty());
  }

  private static TimetableBuilder.RouteInput operation(
      UUID id, String code, List<String> nodes, int dwell, String group) {
    return new TimetableBuilder.RouteInput(
        id,
        code,
        RouteOperationType.OPERATION,
        1,
        TimetableTestFixtures.route(code, nodes),
        TimetableTestFixtures.stops(id, nodes.size(), dwell),
        Optional.empty(),
        Optional.empty(),
        false,
        Optional.of(group));
  }

  private static TimetableBuilder.RouteInput leg(
      String code, RouteOperationType type, List<String> nodes) {
    UUID id = TimetableTestFixtures.routeId(code);
    return new TimetableBuilder.RouteInput(
        id,
        code,
        type,
        0,
        TimetableTestFixtures.route(code, nodes),
        type == RouteOperationType.CREATE
            ? TimetableTestFixtures.createStops(id, nodes.size(), DEP)
            : TimetableTestFixtures.returnStops(id, nodes.size(), DEP),
        Optional.empty());
  }

  private static List<Integer> departures(TimetableBuildResult result, UUID route) {
    return result.timetable().orElseThrow().trips().stream()
        .filter(trip -> trip.routeId().equals(route))
        .map(TimetableTrip::departureSecondOfDay)
        .sorted()
        .toList();
  }

  private static RailGraph graph() {
    Map<String, NodeType> nodes = new LinkedHashMap<>();
    nodes.put(DEP, NodeType.DEPOT);
    for (String station : List.of(A1, A2, B1, C1, D1, E1, F1, B2, C2, D2)) {
      nodes.put(station, NodeType.STATION);
    }
    return TimetableTestFixtures.graph(
        nodes,
        List.of(
            new TimetableTestFixtures.Edge(DEP, A1, 50, 10.0),
            new TimetableTestFixtures.Edge(DEP, A2, 50, 10.0),
            new TimetableTestFixtures.Edge(A1, B1, 100, 10.0),
            new TimetableTestFixtures.Edge(A2, B1, 100, 10.0),
            new TimetableTestFixtures.Edge(B1, C1, 100, 10.0),
            new TimetableTestFixtures.Edge(C1, D1, 100, 10.0),
            new TimetableTestFixtures.Edge(D1, E1, 100, 10.0),
            new TimetableTestFixtures.Edge(D1, F1, 100, 10.0),
            new TimetableTestFixtures.Edge(E1, D2, 100, 10.0),
            new TimetableTestFixtures.Edge(F1, D2, 100, 10.0),
            new TimetableTestFixtures.Edge(D2, C2, 100, 10.0),
            new TimetableTestFixtures.Edge(C2, B2, 100, 10.0),
            new TimetableTestFixtures.Edge(B2, A1, 100, 10.0),
            new TimetableTestFixtures.Edge(B2, A2, 100, 10.0)));
  }
}
