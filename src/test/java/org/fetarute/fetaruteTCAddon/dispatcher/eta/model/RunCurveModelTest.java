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

/** 走行模型把配置翻译成运行曲线的输入：边限速从哪来、进站限速从哪一个节点起、终点停不停。每个用例都能手算。 */
class RunCurveModelTest {

  private static final double EPS = 1e-6;

  /** 车库停车用进库限速：窗口罩住全程时，从静止加速到 5（5 秒、12.5 格），其余 187.5 格按 5 走 37.5 秒。 */
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

    assertEquals(42.5, times[1], EPS);
  }

  /**
   * 按边数也能进窗口：距离不限、边数 1 时，列车经过 M（离 B 一条边）起按 10 行驶。 A→M、M→B 各 100 格、限速 20：加速曲线 v² = 2s 与 M 前的制动曲线 v²
   * = 100 + 2(100 − s) 交于 s = 75，M 约 14.49 秒，B 再加 10 秒。
   */
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

    double peak = Math.sqrt(150.0);
    assertEquals(peak + (peak - 10.0), times[1], EPS);
    assertEquals(peak + (peak - 10.0) + 10.0, times[2], EPS);
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

    assertEquals(15.0, times[1], EPS, "10 秒加到 10 格/秒，再匀速 5 秒");
  }

  /** 边没有限速时按默认速度：100 格按 8，从静止起步 8 秒加到 8（32 格），剩下 68 格匀速 8.5 秒。 */
  @Test
  void edgesWithoutLimitUseTheFallbackSpeed() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:S:B:1"), new int[] {100}, new double[] {0.0});

    double[] times =
        model(StopApproach.Rule.disabled(), null)
            .nodeTimes(graph, run(graph, "OP:S:A:1", "OP:S:B:1", true))
            .orElseThrow();

    assertEquals(16.5, times[1], EPS);
  }

  /** 限速解析器优先于边的基础限速（编表时接的是永久限速覆盖）：按 5，从静止 5 秒加到 5（12.5 格），剩下 87.5 格 17.5 秒。 */
  @Test
  void edgeSpeedResolverWins() {
    RailGraph graph =
        TimetableTestFixtures.chain(
            List.of("OP:S:A:1", "OP:S:B:1"), new int[] {100}, new double[] {10.0});

    double[] times =
        model(StopApproach.Rule.disabled(), (g, edge, fallback) -> 5.0)
            .nodeTimes(graph, run(graph, "OP:S:A:1", "OP:S:B:1", true))
            .orElseThrow();

    assertEquals(22.5, times[1], EPS);
  }

  /** 停站开销原样交给调用方。 */
  @Test
  void exposesStationStopOverhead() {
    RunCurveModel model =
        new RunCurveModel(
            new RunCurveModel.Settings(
                new RunCurveModel.MotionParams(0.8, 1.0), 8.0, StopApproach.Rule.disabled(), 4),
            null);

    assertEquals(4, model.stationStopOverheadSeconds());
    assertEquals(0.8, model.settings().motion().accelBps2(), EPS);
  }

  private static RunCurveModel model(
      StopApproach.Rule approach, RunCurveModel.EdgeSpeedResolver speeds) {
    return new RunCurveModel(
        new RunCurveModel.Settings(new RunCurveModel.MotionParams(1.0, 1.0), 8.0, approach, 0),
        speeds);
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
