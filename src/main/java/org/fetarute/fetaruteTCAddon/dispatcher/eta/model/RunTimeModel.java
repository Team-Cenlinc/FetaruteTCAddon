package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailTravelTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 站到站的走行时分：列车从一个停车点起步，沿展开路径开到下一个停车点（或交路终点），逐节点给出到达时刻。
 *
 * <p>与 {@link RailTravelTimeModel} 的区别在于"起停"：后者回答"以线路速度通过这几条边要多久"，
 * 本接口回答"从静止起步、进站减速停车，这一段要多久"——车站之间的加速、制动与进站限速正是按表运行最容易少算的那部分。
 *
 * <p>另一半是停站本身：{@link #stationStopOverheadSeconds()} 给出车站停车在 dwell 之外多耗的秒数。
 */
public interface RunTimeModel {

  /**
   * 计算一段走行到达每个节点的秒数。
   *
   * @param graph 调度图
   * @param run 这一段走行
   * @return 长度为 {@code run.nodes().size()} 的秒数，首项为 0；任一边缺长度或限速时为空
   */
  Optional<double[]> nodeTimes(RailGraph graph, Run run);

  /**
   * 车站停车在 dwell 之外多耗的秒数：列车压上站牌后居中刹停，停稳后再过一小段时间才开门，dwell 从开门起算。
   *
   * @return 秒数，默认 0
   */
  default int stationStopOverheadSeconds() {
    return 0;
  }

  /**
   * 一段走行的逐点轨迹，与 {@link #nodeTimes} 同一条曲线。
   *
   * <p>编表的闭塞时间要知道列车在每一处的速度来算制动距离：只按节点到达秒数推平均速度，长边上会把进站前的减速当成线路速度。
   *
   * @param graph 调度图
   * @param run 这一段走行
   * @return 轨迹；不建模加减速（逐边相加的模型）或算不出时为空
   */
  default Optional<Trajectory> trajectory(RailGraph graph, Run run) {
    return Optional.empty();
  }

  /**
   * 逐点轨迹。
   *
   * @param distance 各采样点距起点的里程（格），递增
   * @param seconds 到达各采样点的秒数，首项为 0
   * @param speed 各采样点的速度（格/秒）
   */
  record Trajectory(double[] distance, double[] seconds, double[] speed) {

    public Trajectory {
      Objects.requireNonNull(distance, "distance");
      Objects.requireNonNull(seconds, "seconds");
      Objects.requireNonNull(speed, "speed");
      if (distance.length != seconds.length || distance.length != speed.length) {
        throw new IllegalArgumentException("distance / seconds / speed 数量不匹配");
      }
    }

    /** 采样点数。 */
    public int samples() {
      return distance.length;
    }
  }

  /**
   * 一段走行。
   *
   * @param nodes 展开路径的节点，至少两个
   * @param edges 展开路径的边，{@code edges.size() == nodes.size() - 1}
   * @param entrySpeedBps 起点速度（格/秒）：从停车点起步为 0；{@link Double#POSITIVE_INFINITY} 表示"不知道，按首边限速算"
   * @param stopsAtEnd 终点是否停车：停车时按进站规则减速，否则按线路速度开过终点
   * @param firstEdgeRemainingBlocks 列车已经走在首边上时首边剩下的长度；为空表示从首节点出发
   */
  record Run(
      List<NodeId> nodes,
      List<RailEdge> edges,
      double entrySpeedBps,
      boolean stopsAtEnd,
      OptionalDouble firstEdgeRemainingBlocks) {

    public Run {
      nodes = nodes == null ? List.of() : List.copyOf(nodes);
      edges = edges == null ? List.of() : List.copyOf(edges);
      firstEdgeRemainingBlocks =
          firstEdgeRemainingBlocks == null ? OptionalDouble.empty() : firstEdgeRemainingBlocks;
      if (nodes.size() != edges.size() + 1) {
        throw new IllegalArgumentException("nodes 与 edges 数量不匹配");
      }
      if (Double.isNaN(entrySpeedBps) || entrySpeedBps < 0.0) {
        throw new IllegalArgumentException("entrySpeedBps 必须为非负数");
      }
    }

    /** 从首节点出发的一段走行。 */
    public Run(List<NodeId> nodes, List<RailEdge> edges, double entrySpeedBps, boolean stopsAtEnd) {
      this(nodes, edges, entrySpeedBps, stopsAtEnd, OptionalDouble.empty());
    }
  }

  /**
   * 逐边累计的时分：每条边的时分直接相加，不建模起步、制动与进站，停站也不加额外开销。
   *
   * <p>只适合排班逻辑与动力学无关的场景（单元测试用它让"长度 ÷ 限速"可以手算）；生产编表用 {@link RunCurveModel}。
   *
   * @param perEdge 逐边时分模型
   */
  static RunTimeModel perEdge(RailTravelTimeModel perEdge) {
    Objects.requireNonNull(perEdge, "perEdge");
    return (graph, run) -> {
      double[] times = new double[run.nodes().size()];
      for (int k = 0; k < run.edges().size(); k++) {
        RailEdge railEdge = run.edges().get(k);
        Optional<Duration> edge =
            perEdge.edgeTravelTime(graph, railEdge, run.nodes().get(k), run.nodes().get(k + 1));
        if (edge.isEmpty() || edge.get().isNegative()) {
          return Optional.empty();
        }
        double seconds = edge.get().toMillis() / 1000.0;
        if (k == 0 && run.firstEdgeRemainingBlocks().isPresent() && railEdge.lengthBlocks() > 0) {
          double share = run.firstEdgeRemainingBlocks().getAsDouble() / railEdge.lengthBlocks();
          seconds *= Math.max(0.0, Math.min(1.0, share));
        }
        times[k + 1] = times[k] + seconds;
      }
      return Optional.of(times);
    };
  }
}
