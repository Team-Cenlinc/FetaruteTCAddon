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
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(nodes, "nodes");
    Objects.requireNonNull(edges, "edges");

    Optional<Duration> dt;
    if (travelTimeModel instanceof DynamicTravelTimeModel dynamic && initialSpeedBps.isPresent()) {
      // 使用动态模型的初速支持
      dt = dynamic.pathTravelTimeWithInitialSpeed(graph, nodes, edges, initialSpeedBps);
    } else {
      dt = travelTimeModel.pathTravelTime(graph, nodes, edges);
    }

    if (dt.isEmpty()) {
      return Optional.empty();
    }
    long sec = dt.get().getSeconds();
    if (sec < 0L || sec > Integer.MAX_VALUE) {
      return Optional.empty();
    }
    return Optional.of((int) sec);
  }
}
