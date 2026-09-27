package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 行程时间模型（ETA 核心计算组件之一）：把"列车到目标的剩余路径"换算成 travelSec。
 *
 * <p>走行本身交给 {@link RunTimeModel}（生产上是 {@link RunCurveModel}，与编表同一条运行曲线），本类只负责按途中停车点把剩余路径切段：
 * 第一段从列车当前位置、当前速度出发，之后每段从停车点静止起步；停站本身的时长由调用方另计。
 */
public final class TravelTimeModel {

  private final RunTimeModel runTimeModel;

  public TravelTimeModel(RunTimeModel runTimeModel) {
    this.runTimeModel = Objects.requireNonNull(runTimeModel, "runTimeModel");
  }

  /** 车站停车在 dwell 之外多耗的秒数，调用方累加途中停站时用（与编表同一口径）。 */
  public int stationStopOverheadSeconds() {
    return runTimeModel.stationStopOverheadSeconds();
  }

  /**
   * 估算剩余路段的行程时间，途中停车点处按"减速进站—停稳—起步"拆段。
   *
   * <p>整条路径一口气算会把途中车站当成不减速通过：每站少算的是进站制动与出站加速，线路速度 17–22 格/秒时一站十几秒。
   *
   * @param graph 调度图
   * @param nodes 节点序列（edges.size()+1），首节点是列车最近经过的图节点
   * @param edges 边序列
   * @param initialSpeedBps 当前速度（格/秒）；为空表示不知道，首段按首边限速出发
   * @param firstEdgeRemainingBlocks 列车已经走在首边上时首边剩下的长度；为空按整条边计
   * @param stopPositions 途中停车点在 {@code nodes} 中的位置，严格递增且不含首尾
   * @param stopsAtTarget 列车在目标处是否停车（决定最后一段按不按进站规则减速）
   * @return travelSec，任一段无法估算则 empty
   */
  public Optional<Integer> estimateTravelSec(
      RailGraph graph,
      List<NodeId> nodes,
      List<RailEdge> edges,
      OptionalDouble initialSpeedBps,
      OptionalDouble firstEdgeRemainingBlocks,
      List<Integer> stopPositions,
      boolean stopsAtTarget) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(nodes, "nodes");
    Objects.requireNonNull(edges, "edges");
    if (nodes.size() != edges.size() + 1) {
      return Optional.empty();
    }
    List<Integer> breaks = new ArrayList<>();
    for (Integer position : stopPositions == null ? List.<Integer>of() : stopPositions) {
      if (position != null && position > 0 && position < nodes.size() - 1) {
        breaks.add(position);
      }
    }
    breaks.add(nodes.size() - 1);

    double total = 0.0;
    int legStart = 0;
    double entrySpeed =
        initialSpeedBps.isPresent() && initialSpeedBps.getAsDouble() >= 0.0
            ? initialSpeedBps.getAsDouble()
            : Double.POSITIVE_INFINITY;
    OptionalDouble firstEdgeRemaining =
        firstEdgeRemainingBlocks == null ? OptionalDouble.empty() : firstEdgeRemainingBlocks;
    for (int legEnd : breaks) {
      if (legEnd <= legStart) {
        continue;
      }
      boolean last = legEnd == nodes.size() - 1;
      Optional<double[]> times =
          runTimeModel.nodeTimes(
              graph,
              new RunTimeModel.Run(
                  nodes.subList(legStart, legEnd + 1),
                  edges.subList(legStart, legEnd),
                  entrySpeed,
                  !last || stopsAtTarget,
                  firstEdgeRemaining));
      if (times.isEmpty()) {
        return Optional.empty();
      }
      total += times.get()[times.get().length - 1];
      legStart = legEnd;
      entrySpeed = 0.0;
      firstEdgeRemaining = OptionalDouble.empty();
    }
    long sec = Math.round(total);
    if (sec < 0L || sec > Integer.MAX_VALUE) {
      return Optional.empty();
    }
    return Optional.of((int) sec);
  }
}
