package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTestFixtures;
import org.junit.jupiter.api.Test;

/**
 * 走行模型把配置翻译成运行曲线的输入：边限速从哪来、进站限速从哪一个节点起、终点停不停、控车在停车点留多少速度。
 *
 * <p>期望值用同一组原始输入直接调 {@link RunCurve}：这里只验证翻译，曲线本身由 {@code RunCurveTest} 与 {@code SpeedCurveTest}
 * 验证。
 */
class RunCurveModelTest {

  private static final double EPS = 1e-6;
  private static final double FREE = Double.POSITIVE_INFINITY;
  private static final SpeedCurve CURVE = new SpeedCurve(1.0, 1.0);

  /** 车库停车用进库限速：窗口罩住全程时，整段按 5 行驶、到站速度 5。 */
  @Test
  void depotStopsUseTheDepotApproachSpeed() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:D:DEP:1"),
            List.of(NodeType.STATION, NodeType.DEPOT),
            new int[] {200},
            new double[] {20.0});

    double[] times =
        model(new StopApproach.Rule(1000.0, 0, 10.0, 5.0), null)
            .nodeTimes(graph, run(graph, "OP:S:A:1", "OP:D:DEP:1", true))
            .orElseThrow();

    assertEquals(
        curve(new double[] {200}, new double[] {20.0}, List.of(cap(0.0, 200.0, 5.0)), 5.0),
        times[1],
        EPS);
  }

  /** 按边数也能进窗口：距离不限、边数 1 时，列车经过 M（离车库一条边）起按进库限速 5 行驶。A→M、M→车库各 100 格、限速 20。 */
  @Test
  void edgeCountWindowAlsoStartsAtAGraphNode() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:A:B:1:001", "OP:D:DEP:1"),
            List.of(NodeType.STATION, NodeType.WAYPOINT, NodeType.DEPOT),
            new int[] {100, 100},
            new double[] {20.0, 20.0});

    double[] times =
        model(new StopApproach.Rule(0.0, 1, 10.0, 5.0), null)
            .nodeTimes(graph, run(graph, "OP:S:A:1", "OP:D:DEP:1", true))
            .orElseThrow();

    double[] expected =
        RunCurve.nodeTimes(
            new double[] {100, 100},
            new double[] {20.0, 20.0},
            List.of(cap(100.0, 200.0, 5.0)),
            0.0,
            5.0,
            CURVE);
    assertEquals(expected[1], times[1], EPS);
    assertEquals(expected[2], times[2], EPS);
  }

  /** 终点不停车（交路末端以 PASS 开进车库销毁）时不做进站限速：按线路速度开过去。 */
  @Test
  void runThroughEndIgnoresApproach() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:S:B:1"), new int[] {100}, new double[] {10.0});

    double[] times =
        model(new StopApproach.Rule(1000.0, 0, 2.0, 2.0), null)
            .nodeTimes(graph, run(graph, "OP:S:A:1", "OP:S:B:1", false))
            .orElseThrow();

    assertEquals(curve(new double[] {100}, new double[] {10.0}, List.of(), FREE), times[1], EPS);
  }

  /** 边没有限速时按默认速度 8；车站停车刹到 0。 */
  @Test
  void edgesWithoutLimitUseTheFallbackSpeed() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:S:B:1"), new int[] {100}, new double[] {0.0});

    double[] times =
        model(StopApproach.Rule.disabled(), null)
            .nodeTimes(graph, run(graph, "OP:S:A:1", "OP:S:B:1", true))
            .orElseThrow();

    assertEquals(curve(new double[] {100}, new double[] {8.0}, List.of(), 0.0), times[1], EPS);
  }

  /** 车站不按进站限速：没有限速区，沿减速曲线直接刹到 0（区间停车点的进站限速 10 只给区间停车点）。 */
  @Test
  void stationStopsBrakeStraightToRestWithoutAnApproachCap() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:S:B:1"), new int[] {300}, new double[] {20.0});

    double[] times =
        model(new StopApproach.Rule(1000.0, 0, 10.0, 5.0), null)
            .nodeTimes(graph, run(graph, "OP:S:A:1", "OP:S:B:1", true))
            .orElseThrow();

    assertEquals(curve(new double[] {300}, new double[] {20.0}, List.of(), 0.0), times[1], EPS);
  }

  /** 区间停车点照旧：窗口内按进站限速 10，编表按刹到 0 计。 */
  @Test
  void waypointStopsKeepTheirApproachCap() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:A:B:1:001"),
            List.of(NodeType.STATION, NodeType.WAYPOINT),
            new int[] {300},
            new double[] {20.0});

    double[] times =
        model(new StopApproach.Rule(1000.0, 0, 10.0, 5.0), null)
            .nodeTimes(graph, run(graph, "OP:S:A:1", "OP:A:B:1:001", true))
            .orElseThrow();

    assertEquals(
        curve(new double[] {300}, new double[] {20.0}, List.of(cap(0.0, 300.0, 10.0)), 0.0),
        times[1],
        EPS);
  }

  /**
   * 控车的车站终点：停车点在站牌之后 tail 格，天花板在站牌处取这段剩余距离按同一条曲线算出的速度，不低于保底速度（车要压得上站牌）；
   * 关闭速度曲线时没有曲线可接，照旧按进站限速进站；区间停车点按进站限速到点。
   */
  @Test
  void controlCeilingLeavesTheTailSpeedAtTheStationSign() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:S:B:1"), new int[] {300}, new double[] {20.0});
    RunCurveModel model = model(new StopApproach.Rule(1000.0, 0, 10.0, 5.0), null);
    RunTimeModel.Run run = run(graph, "OP:S:A:1", "OP:S:B:1", true);

    SpeedCeiling far =
        model.ceiling(graph, run, CURVE, new RunCurveModel.ControlEnd(true, 20.0)).orElseThrow();
    // 一段刹车：把路径延长到站牌之后 20 格的停车点、刹到 0。站牌处不是刹车的终点，不在那里收尾再重新起一段。
    SpeedCeiling extended =
        SpeedCeiling.of(new double[] {300, 20}, new double[] {20.0, 20.0}, List.of(), 0.0, CURVE);
    assertEquals(extended.limitBps(300.0), far.limitBps(300.0), EPS);
    assertEquals(extended.limitBps(150.0), far.limitBps(150.0), EPS, "站牌前沿同一条曲线刹车，没有进站限速平台");
    double atSignSpeed = far.limitBps(300.0);
    double beforeSign = far.limitBps(299.0);
    double decelAtSign = (beforeSign * beforeSign - atSignSpeed * atSignSpeed) / 2.0;
    assertEquals(CURVE.decelBps2(), decelAtSign, 0.02, "压上站牌时仍是满减速度，不在收尾段");

    // 停车点就在站牌处（单节车）：压上站牌时第一节车厢中心离停车点只剩约半格，保底速度不超过这半格里刹得住的速度，最后一拍不骤停。
    SpeedCeiling atSign =
        model.ceiling(graph, run, CURVE, new RunCurveModel.ControlEnd(true, 0.0)).orElseThrow();
    double atSignExit = atSign.limitBps(300.0);
    assertEquals(SpeedCeiling.brakingLimitBps(CURVE, 20.0, 0.0, 0.5), atSignExit, EPS);
    assertTrue(atSignExit > 0.0, "车要压得上站牌");
    assertTrue(
        atSignExit < RunCurveModel.STATION_TRIGGER_FLOOR_BPS
            && atSignExit <= Math.sqrt(2.0 * CURVE.decelBps2() * 0.5),
        "半格内刹得住: " + atSignExit);
    // 停车点在站牌后 1 格：曲线在站牌处的速度比保底速度低，取保底速度，但保底速度不超过余下 1.5 格里刹得住的速度。
    SpeedCeiling shortTail =
        model.ceiling(graph, run, CURVE, new RunCurveModel.ControlEnd(true, 1.0)).orElseThrow();
    assertTrue(
        SpeedCeiling.brakingLimitBps(CURVE, 20.0, 0.0, 1.0)
            < RunCurveModel.STATION_TRIGGER_FLOOR_BPS);
    assertEquals(
        Math.min(
            RunCurveModel.STATION_TRIGGER_FLOOR_BPS,
            SpeedCeiling.brakingLimitBps(CURVE, 20.0, 0.0, 1.5)),
        shortTail.limitBps(300.0),
        EPS);

    SpeedCeiling legacy =
        model.ceiling(graph, run, null, new RunCurveModel.ControlEnd(true, 20.0)).orElseThrow();
    assertEquals(10.0, legacy.limitBps(300.0), EPS);
    assertEquals(10.0, legacy.limitBps(100.0), EPS, "关闭速度曲线：窗口内按进站限速");

    RailGraph waypointGraph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:A:B:1:001"),
            List.of(NodeType.STATION, NodeType.WAYPOINT),
            new int[] {300},
            new double[] {20.0});
    SpeedCeiling waypoint =
        model
            .ceiling(
                waypointGraph,
                run(waypointGraph, "OP:S:A:1", "OP:A:B:1:001", true),
                CURVE,
                new RunCurveModel.ControlEnd(true, 20.0))
            .orElseThrow();
    assertEquals(10.0, waypoint.limitBps(300.0), EPS);
  }

  /** 换车种时减速度乘舒适比例，与控车、编表同一口径；直接给定的曲线不再乘。 */
  @Test
  void withTrainAppliesTheAtoBrakeFraction() {
    RunCurveModel.Settings settings =
        new RunCurveModel.Settings(CURVE, 8.0, StopApproach.Rule.disabled(), 0, 0.75);

    assertEquals(0.9, settings.withTrain(1.1, 1.2).motion().decelBps2(), EPS);
    assertEquals(1.1, settings.withTrain(1.1, 1.2).motion().accelBps2(), EPS);
    assertEquals(1.2, settings.withMotion(new SpeedCurve(1.1, 1.2)).motion().decelBps2(), EPS);
  }

  /** 限速解析器优先于边的基础限速（编表时接的是永久限速覆盖）：按 5 行驶。 */
  @Test
  void edgeSpeedResolverWins() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:S:B:1"), new int[] {100}, new double[] {10.0});

    double[] times =
        model(StopApproach.Rule.disabled(), (g, edge, fallback) -> 5.0)
            .nodeTimes(graph, run(graph, "OP:S:A:1", "OP:S:B:1", true))
            .orElseThrow();

    assertEquals(curve(new double[] {100}, new double[] {5.0}, List.of(), 0.0), times[1], EPS);
  }

  /** 停站开销原样交给调用方。 */
  @Test
  void exposesStationStopOverhead() {
    RunCurveModel model =
        new RunCurveModel(
            new RunCurveModel.Settings(
                new SpeedCurve(0.8, 1.0), 8.0, StopApproach.Rule.disabled(), 4),
            null);

    assertEquals(4, model.stationStopOverheadSeconds());
    assertEquals(0.8, model.settings().motion().accelBps2(), EPS);
  }

  private static RunCurveModel model(
      StopApproach.Rule approach, RunCurveModel.EdgeSpeedResolver speeds) {
    return new RunCurveModel(new RunCurveModel.Settings(CURVE, 8.0, approach, 0), speeds);
  }

  /** 同一组原始输入从静止起步时到终点的秒数。 */
  private static double curve(
      double[] lengths, double[] speeds, List<SpeedCeiling.Cap> caps, double exitSpeed) {
    return RunCurve.nodeTimes(lengths, speeds, caps, 0.0, exitSpeed, CURVE)[lengths.length];
  }

  private static SpeedCeiling.Cap cap(double from, double to, double speed) {
    return new SpeedCeiling.Cap(from, to, speed);
  }

  private static RunTimeModel.Run run(RailGraph graph, String from, String to, boolean stops) {
    RailGraphPath path =
        new RailGraphPathFinder()
            .shortestPath(
                graph,
                NodeId.of(from),
                NodeId.of(to),
                RailGraphPathFinder.Options.shortestDistance())
            .orElseThrow();
    return new RunTimeModel.Run(path.nodes(), path.edges(), 0.0, stops);
  }
}
