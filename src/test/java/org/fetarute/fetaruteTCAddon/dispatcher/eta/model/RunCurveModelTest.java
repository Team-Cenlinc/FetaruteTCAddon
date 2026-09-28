package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTestFixtures;
import org.junit.jupiter.api.Test;

/**
 * 走行模型把配置翻译成运行曲线的输入：边限速从哪来、进站限速从哪一个节点起、终点停不停。
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

  /** 按边数也能进窗口：距离不限、边数 1 时，列车经过 M（离 B 一条边）起按 10 行驶。A→M、M→B 各 100 格、限速 20。 */
  @Test
  void edgeCountWindowAlsoStartsAtAGraphNode() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:A:B:1:001", "OP:S:B:1"),
            List.of(NodeType.STATION, NodeType.WAYPOINT, NodeType.STATION),
            new int[] {100, 100},
            new double[] {20.0, 20.0});

    double[] times =
        model(new StopApproach.Rule(0.0, 1, 10.0, 5.0), null)
            .nodeTimes(graph, run(graph, "OP:S:A:1", "OP:S:B:1", true))
            .orElseThrow();

    double[] expected =
        RunCurve.nodeTimes(
            new double[] {100, 100},
            new double[] {20.0, 20.0},
            List.of(cap(100.0, 200.0, 10.0)),
            0.0,
            10.0,
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

  /** 边没有限速时按默认速度 8。进站规则关闭时终点不设速度上限。 */
  @Test
  void edgesWithoutLimitUseTheFallbackSpeed() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:S:B:1"), new int[] {100}, new double[] {0.0});

    double[] times =
        model(StopApproach.Rule.disabled(), null)
            .nodeTimes(graph, run(graph, "OP:S:A:1", "OP:S:B:1", true))
            .orElseThrow();

    assertEquals(curve(new double[] {100}, new double[] {8.0}, List.of(), FREE), times[1], EPS);
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

    assertEquals(curve(new double[] {100}, new double[] {5.0}, List.of(), FREE), times[1], EPS);
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
