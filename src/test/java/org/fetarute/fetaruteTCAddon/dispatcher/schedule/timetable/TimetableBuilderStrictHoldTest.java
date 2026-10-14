package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

/**
 * 严格模式也试喂车方向中途多停：多停不改间隔，与非严格模式第一步同一个顺序。
 *
 * <p>仿 WS 的形状：大交路 A↔Z（A、Z 都是单股道端点，上下行分线经过 H、M），小交路 SM 从车库 DEP 出库、过咽喉路径点 J、 在 M 第一次停车，再经 H 终到 A，到 A
 * 折返后接大交路 A→Z。回库 A→J→DEP 与 SM 出库共用咽喉 DEP–J。
 */
class TimetableBuilderStrictHoldTest {

  private static final Instant BUILT_AT = Instant.parse("2026-03-01T00:00:00Z");
  private static final String DEP = "OP:D:DEP:1";
  private static final String DZ = "OP:D:DZ:1";
  private static final String J = "OP:W:J:1";
  private static final String A = "OP:S:A:1";
  private static final String H1 = "OP:S:H:1";
  private static final String H2 = "OP:S:H:2";
  private static final String M1 = "OP:S:M:1";
  private static final String M2 = "OP:S:M:2";
  private static final String Z = "OP:S:Z:1";

  /**
   * 目标间隔 105 秒：不多停排不开，结构预筛给出几种多停，严格模式逐一完整构建过才失败，失败文案写明试过几种；非严格模式在同一处先试同样的多停。 旧行为是严格模式一种多停也不试、直接失败。
   */
  @Test
  void strictModeTriesFeederHoldsBeforeFailing() {
    TimetableBuildResult strict = build(105, true, 50, 50);

    assertFalse(strict.success());
    String reason = String.join("\n", strict.warnings());
    assertTrue(reason.contains("严格模式下不放宽间隔，喂车方向中途多停 "), reason);
    assertTrue(reason.contains(" 种也排不开"), reason);
    assertFalse(strict.unabsorbable().isEmpty(), reason);
    assertTrue(reason.contains("让不掉的冲突按判决与让车点"), reason);
    assertFalse(strict.resourcePhaseNotes().isEmpty(), "目标间隔那次第三层选了什么也带出来");
  }

  /**
   * 咽喉到 A 短、到 M 长时，目标间隔 103 秒不多停排不开，喂车方向在 M 多停之后排开：严格模式照样成表、间隔不变，报告写明多停与"严格模式不放宽间隔"。 多停写进 SM 在 M
   * 的停站，与非严格模式同一种。
   */
  @Test
  void strictModeAcceptsAFeederHoldAtTheTarget() {
    TimetableBuildResult strict = build(103, true, 30, 80);
    TimetableBuildResult lenient = build(103, false, 30, 80);

    assertTrue(strict.success(), () -> strict.warnings().toString());
    assertFalse(strict.headwayRelaxed());
    assertTrue(strict.unabsorbable().isEmpty());
    assertTrue(
        strict.warnings().stream().anyMatch(text -> text.startsWith("按车接续：喂车方向 SM 在 OP:S:M:2 多停")),
        () -> strict.warnings().toString());
    assertTrue(
        strict.warnings().stream().anyMatch(text -> text.contains("严格模式不放宽间隔，靠喂车方向中途多停排开")),
        () -> strict.warnings().toString());
    assertTrue(lenient.success(), () -> lenient.warnings().toString());
    assertEquals(lenient.timetable(), strict.timetable(), "非严格模式第一步也是这一种多停");
  }

  /** 运行时不扣车（扣车上限 0）时表上写进去的多停兑现不了：不试多停，上面那张靠多停才排开的表在严格模式下排不开。 */
  @Test
  void noFeederHoldWhenTheRuntimeDoesNotHold() {
    TimetableBuildResult strict = build(103, true, 30, 80, 0);

    assertFalse(strict.success(), () -> strict.warnings().toString());
    assertTrue(
        strict.warnings().stream()
            .noneMatch(text -> text.contains("多停 ") && text.contains("种也排不开")),
        () -> strict.warnings().toString());
  }

  /** 间隔排得开时严格与非严格逐字段相同：严格模式多出来的只是失败前的那一步。 */
  @Test
  void aCleanTargetIsUnaffected() {
    TimetableBuildResult strict = build(106, true, 50, 50);
    TimetableBuildResult lenient = build(106, false, 50, 50);

    assertTrue(strict.success(), () -> strict.warnings().toString());
    assertEquals(lenient.timetable(), strict.timetable());
  }

  /**
   * @param throatToA 咽喉路径点 J 到 A 的长度（blocks）
   * @param throatToM J 到 M 两股道的长度（blocks）
   */
  private TimetableBuildResult build(int interval, boolean strict, int throatToA, int throatToM) {
    return build(
        interval, strict, throatToA, throatToM, TimetableBuildOptions.DEFAULT_HOLD_MAX_SECONDS);
  }

  /**
   * @param holdMax 运行时扣车上限：喂车多停至多它的一半
   */
  private TimetableBuildResult build(
      int interval, boolean strict, int throatToA, int throatToM, int holdMax) {
    TimetableBuildOptions options =
        new TimetableBuildOptions(
                5 * 3600,
                8 * 3600,
                Duration.ofSeconds(interval),
                Duration.ofSeconds(20),
                new VehicleDutyPlanner.Limits(6, 7200, 20),
                "",
                ZoneId.of("UTC"),
                Duration.ofSeconds(15),
                strict)
            .withIntervals(
                Duration.ofSeconds(interval), Map.of("full", interval, "short", interval))
            .withHoldMax(holdMax);
    return new TimetableBuilder().build(input(throatToA, throatToM), options, BUILT_AT);
  }

  private static TimetableBuilder.BuildInput input(int throatToA, int throatToM) {
    Map<String, NodeType> nodes = new LinkedHashMap<>();
    nodes.put(DEP, NodeType.DEPOT);
    nodes.put(DZ, NodeType.DEPOT);
    nodes.put(J, NodeType.WAYPOINT);
    nodes.put(A, NodeType.STATION);
    nodes.put(H1, NodeType.STATION);
    nodes.put(H2, NodeType.STATION);
    nodes.put(M1, NodeType.STATION);
    nodes.put(M2, NodeType.STATION);
    nodes.put(Z, NodeType.STATION);
    RailGraph graph =
        TimetableTestFixtures.graph(
            nodes,
            List.of(
                new TimetableTestFixtures.Edge(A, H1, 100, 10.0),
                new TimetableTestFixtures.Edge(A, H2, 100, 10.0),
                new TimetableTestFixtures.Edge(H1, M1, 200, 10.0),
                new TimetableTestFixtures.Edge(H2, M2, 200, 10.0),
                new TimetableTestFixtures.Edge(M1, Z, 600, 10.0),
                new TimetableTestFixtures.Edge(M2, Z, 600, 10.0),
                new TimetableTestFixtures.Edge(DEP, J, 50, 10.0),
                new TimetableTestFixtures.Edge(J, M1, throatToM, 10.0),
                new TimetableTestFixtures.Edge(J, M2, throatToM, 10.0),
                new TimetableTestFixtures.Edge(J, A, throatToA, 10.0),
                new TimetableTestFixtures.Edge(DZ, Z, 50, 10.0)));
    return new TimetableBuilder.BuildInput(
        TimetableTestFixtures.routeId("timetable"),
        TimetableTestFixtures.routeId("company"),
        TimetableTestFixtures.routeId("operator"),
        TimetableTestFixtures.routeId("line"),
        "TT",
        "严格多停",
        List.of(
            operation("FA", List.of(A, H1, M1, Z), "full"),
            operation("FB", List.of(Z, M2, H2, A), "full"),
            feeder(),
            leg("C2", RouteOperationType.CREATE, List.of(DZ, Z), DZ),
            leg("C3", RouteOperationType.CREATE, List.of(DEP, J, A), DEP),
            leg("R1", RouteOperationType.RETURN, List.of(A, J, DEP), DEP),
            leg("R2", RouteOperationType.RETURN, List.of(Z, DZ), DZ)),
        graph,
        TimetableTestFixtures.perEdgeSpeedModel(),
        Optional.empty());
  }

  /** 小交路：车库出库（CRET）、过咽喉路径点（PASS）、M 第一次停车、H、终到 A。 */
  private static TimetableBuilder.RouteInput feeder() {
    UUID routeId = TimetableTestFixtures.routeId("SM");
    List<String> path = List.of(DEP, J, M2, H2, A);
    List<RouteStop> stops = new ArrayList<>();
    for (int i = 0; i < path.size(); i++) {
      stops.add(
          new RouteStop(
              routeId,
              i,
              Optional.empty(),
              Optional.empty(),
              Optional.of(i <= 1 ? 0 : 20),
              i == 1
                  ? RouteStopPassType.PASS
                  : i == path.size() - 1 ? RouteStopPassType.TERMINATE : RouteStopPassType.STOP,
              i == 0 ? Optional.of("CRET " + DEP) : Optional.empty()));
    }
    return new TimetableBuilder.RouteInput(
        routeId,
        "SM",
        RouteOperationType.OPERATION,
        1,
        TimetableTestFixtures.route("SM", path),
        stops,
        Optional.empty(),
        Optional.empty(),
        false,
        Optional.of("short"));
  }

  private static TimetableBuilder.RouteInput operation(
      String code, List<String> path, String group) {
    UUID routeId = TimetableTestFixtures.routeId(code);
    return new TimetableBuilder.RouteInput(
        routeId,
        code,
        RouteOperationType.OPERATION,
        1,
        TimetableTestFixtures.route(code, path),
        TimetableTestFixtures.stops(routeId, path.size(), 20),
        Optional.empty(),
        Optional.empty(),
        false,
        Optional.of(group));
  }

  /** 出入库走行：中间的路径点只经过。 */
  private static TimetableBuilder.RouteInput leg(
      String code, RouteOperationType type, List<String> path, String depot) {
    UUID routeId = TimetableTestFixtures.routeId(code);
    List<RouteStop> base =
        type == RouteOperationType.CREATE
            ? TimetableTestFixtures.createStops(routeId, path.size(), depot)
            : TimetableTestFixtures.returnStops(routeId, path.size(), depot);
    List<RouteStop> stops = new ArrayList<>();
    for (int i = 0; i < base.size(); i++) {
      RouteStop stop = base.get(i);
      stops.add(
          i == 0 || i == base.size() - 1
              ? stop
              : new RouteStop(
                  stop.routeId(),
                  stop.sequence(),
                  stop.stationId(),
                  stop.waypointNodeId(),
                  stop.dwellSeconds(),
                  RouteStopPassType.PASS,
                  stop.notes()));
    }
    return new TimetableBuilder.RouteInput(
        routeId,
        code,
        type,
        0,
        TimetableTestFixtures.route(code, path),
        stops,
        Optional.empty(),
        Optional.empty(),
        false,
        Optional.of("full"));
  }
}
