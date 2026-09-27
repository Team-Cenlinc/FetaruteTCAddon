package org.fetarute.fetaruteTCAddon.dispatcher.eta.model;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailTravelTimeModel;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 行程时间模型（ETA 核心计算组件之一）。
 *
 * <p>本项目已存在 {@link RailTravelTimeModel}（按边估算），本类作为 ETA 模块的适配层， 将"剩余边列表"转换为 travelSec。
 *
 * <p>支持传入当前速度以提高首边估算精度。
 */
public final class TravelTimeModel {

  private final RailTravelTimeModel travelTimeModel;

  public TravelTimeModel(RailTravelTimeModel travelTimeModel) {
    this.travelTimeModel = Objects.requireNonNull(travelTimeModel, "travelTimeModel");
  }

  /**
   * 估算剩余路段的行程时间（不含初速）。
   *
   * @param graph 调度图
   * @param nodes 节点序列（edges.size()+1）
   * @param edges 边序列
   * @return travelSec，无法估算则 empty
   */
  public Optional<Integer> estimateTravelSec(
      RailGraph graph, List<NodeId> nodes, List<RailEdge> edges) {
    return estimateTravelSec(graph, nodes, edges, OptionalDouble.empty());
  }

  /**
   * 估算剩余路段的行程时间（含初速）。
   *
   * @param graph 调度图
   * @param nodes 节点序列（edges.size()+1）
   * @param edges 边序列
   * @param initialSpeedBps 当前速度（blocks/s），用于首边估算；empty 时使用首边限速
   * @return travelSec，无法估算则 empty
   */
  public Optional<Integer> estimateTravelSec(
      RailGraph graph, List<NodeId> nodes, List<RailEdge> edges, OptionalDouble initialSpeedBps) {
    return estimateTravelSec(graph, nodes, edges, initialSpeedBps, OptionalDouble.empty());
  }

  /**
   * 估算剩余路段的行程时间（含初速，首边可只算剩余部分）。
   *
   * @param firstEdgeRemainingBlocks 首边剩余长度；为空按整条边计。仅动态模型支持，其它模型忽略
   * @return travelSec，无法估算则 empty
   */
  public Optional<Integer> estimateTravelSec(
      RailGraph graph,
      List<NodeId> nodes,
      List<RailEdge> edges,
      OptionalDouble initialSpeedBps,
      OptionalDouble firstEdgeRemainingBlocks) {
    return estimateTravelSec(
        graph, nodes, edges, initialSpeedBps, firstEdgeRemainingBlocks, List.of());
  }

  /**
   * 估算剩余路段的行程时间，途中停车点处按“减速进站—停稳—起步”拆段。
   *
   * <p>整条路径一口气算会把途中车站当成不减速通过：每站少算的是进站制动与出站加速，按默认参数约 5 秒一站。
   * 拆段后每段各自以进站限速收尾（目标是车站时），下一段从静止起步；停站本身的时长由调用方另计。
   *
   * @param stopPositions 途中停车点在 {@code nodes} 中的位置，严格递增且不含首尾
   * @return travelSec，任一段无法估算则 empty
   */
  public Optional<Integer> estimateTravelSec(
      RailGraph graph,
      List<NodeId> nodes,
      List<RailEdge> edges,
      OptionalDouble initialSpeedBps,
      OptionalDouble firstEdgeRemainingBlocks,
      List<Integer> stopPositions) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(nodes, "nodes");
    Objects.requireNonNull(edges, "edges");
    if (nodes.size() != edges.size() + 1) {
      return Optional.empty();
    }

    Duration total = Duration.ZERO;
    int legStart = 0;
    OptionalDouble legInitialSpeed = initialSpeedBps;
    OptionalDouble legFirstEdgeRemaining = firstEdgeRemainingBlocks;
    List<Integer> breaks = new ArrayList<>();
    for (Integer position : stopPositions == null ? List.<Integer>of() : stopPositions) {
      if (position != null && position > 0 && position < nodes.size() - 1) {
        breaks.add(position);
      }
    }
    breaks.add(nodes.size() - 1);
    for (int legEnd : breaks) {
      if (legEnd <= legStart) {
        continue;
      }
      Optional<Duration> leg =
          legTravelTime(
              graph,
              nodes.subList(legStart, legEnd + 1),
              edges.subList(legStart, legEnd),
              legInitialSpeed,
              legFirstEdgeRemaining);
      if (leg.isEmpty()) {
        return Optional.empty();
      }
      total = total.plus(leg.get());
      legStart = legEnd;
      legInitialSpeed = OptionalDouble.of(0.0);
      legFirstEdgeRemaining = OptionalDouble.empty();
    }

    long sec = total.getSeconds();
    if (sec < 0L || sec > Integer.MAX_VALUE) {
      return Optional.empty();
    }
    return Optional.of((int) sec);
  }

  private Optional<Duration> legTravelTime(
      RailGraph graph,
      List<NodeId> nodes,
      List<RailEdge> edges,
      OptionalDouble initialSpeedBps,
      OptionalDouble firstEdgeRemainingBlocks) {
    if (travelTimeModel instanceof DynamicTravelTimeModel dynamic
        && (initialSpeedBps.isPresent() || firstEdgeRemainingBlocks.isPresent())) {
      // 使用动态模型的初速与首边剩余长度支持
      return dynamic.pathTravelTimeWithInitialSpeed(
          graph, nodes, edges, initialSpeedBps, firstEdgeRemainingBlocks);
    }
    return travelTimeModel.pathTravelTime(graph, nodes, edges);
  }
}
