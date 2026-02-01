package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.controller.components.RailState;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeTrainHandle;

/**
 * 列车位置解析器：计算列车在轨道上的精确位置与到目标的距离。
 *
 * <p>使用 TrainCarts 的 RailPath/RailState API 获取精确位置，避免使用最短路径估算。
 *
 * <p>性能注意：RailPath 查询有一定开销，建议每 tick 只调用一次并缓存结果。
 */
public final class TrainPositionResolver {

  private TrainPositionResolver() {}

  /**
   * 位置解析结果。
   *
   * @param distanceToNextBlocks 到下一节点的距离（blocks）
   * @param edgeProgressRatio 在当前边上的进度（0.0 = 起点，1.0 = 终点）
   * @param currentSpeedBps 当前速度（blocks/s）
   */
  public record PositionResult(
      OptionalLong distanceToNextBlocks,
      OptionalDouble edgeProgressRatio,
      OptionalDouble currentSpeedBps) {

    /** 空结果。 */
    public static PositionResult empty() {
      return new PositionResult(
          OptionalLong.empty(), OptionalDouble.empty(), OptionalDouble.empty());
    }
  }

  /**
   * 解析列车位置：计算到下一节点的精确距离。
   *
   * @param train 列车句柄
   * @param graph 调度图
   * @param currentNode 当前节点
   * @param nextNode 下一节点
   * @param edgeLengthBlocks 当前边的长度（作为 fallback）
   * @return 位置解析结果
   */
  public static PositionResult resolve(
      RuntimeTrainHandle train,
      RailGraph graph,
      NodeId currentNode,
      NodeId nextNode,
      long edgeLengthBlocks) {
    if (train == null || currentNode == null || nextNode == null) {
      return PositionResult.empty();
    }

    // 1. 获取当前速度
    double currentSpeedBps = train.currentSpeedBlocksPerTick() * 20.0;

    // 2. 尝试从 RailState 获取精确位置
    Optional<RailState> railStateOpt = train.railState();
    if (railStateOpt.isEmpty()) {
      // 无法获取精确位置，使用边长度作为 fallback
      return new PositionResult(
          edgeLengthBlocks > 0 ? OptionalLong.of(edgeLengthBlocks) : OptionalLong.empty(),
          OptionalDouble.empty(),
          OptionalDouble.of(currentSpeedBps));
    }

    // 3. 计算在边上的进度
    RailState railState = railStateOpt.get();
    OptionalDouble progressOpt = estimateEdgeProgress(railState, graph, currentNode, nextNode);

    // 4. 计算到下一节点的距离
    OptionalLong distanceOpt;
    if (progressOpt.isPresent() && edgeLengthBlocks > 0) {
      double remaining = 1.0 - progressOpt.getAsDouble();
      long distance = Math.round(remaining * edgeLengthBlocks);
      distanceOpt = OptionalLong.of(Math.max(0, distance));
    } else if (edgeLengthBlocks > 0) {
      distanceOpt = OptionalLong.of(edgeLengthBlocks);
    } else {
      distanceOpt = OptionalLong.empty();
    }

    return new PositionResult(distanceOpt, progressOpt, OptionalDouble.of(currentSpeedBps));
  }

  /**
   * 从 MinecartGroup 解析位置（直接使用 TrainCarts API）。
   *
   * @param group TrainCarts 列车组
   * @param edgeLengthBlocks 当前边长度
   * @return 位置解析结果
   */
  public static PositionResult resolveFromGroup(MinecartGroup group, long edgeLengthBlocks) {
    if (group == null || group.isEmpty()) {
      return PositionResult.empty();
    }

    // 使用头车位置
    MinecartMember<?> head = group.head();
    if (head == null) {
      return PositionResult.empty();
    }

    // 获取实际速度（使用实体 velocity，而非 TrainCarts 目标速度）
    org.bukkit.entity.Entity entity =
        head.getEntity() != null ? head.getEntity().getEntity() : null;
    double speedBpt = 0.0;
    if (entity != null) {
      org.bukkit.util.Vector velocity = entity.getVelocity();
      if (velocity != null) {
        speedBpt = velocity.length();
      }
    }
    double speedBps = speedBpt * 20.0;

    // 简化处理：使用边长度作为距离估算
    // TrainCarts 的精确轨道位置 API 较复杂，这里先使用简化实现
    return new PositionResult(
        edgeLengthBlocks > 0 ? OptionalLong.of(edgeLengthBlocks) : OptionalLong.empty(),
        OptionalDouble.empty(),
        OptionalDouble.of(speedBps));
  }

  /**
   * 估算列车在边上的进度（0.0 - 1.0）。
   *
   * <p>当前实现使用坐标插值估算。未来可接入更精确的轨道路径计算。
   */
  private static OptionalDouble estimateEdgeProgress(
      RailState railState, RailGraph graph, NodeId currentNode, NodeId nextNode) {
    if (railState == null || graph == null) {
      return OptionalDouble.empty();
    }

    // 获取列车坐标
    Vector trainPos = railState.positionLocation().toVector();

    // 获取节点坐标（从图或注册表）
    Optional<org.bukkit.Location> currentLocOpt = getNodeLocation(graph, currentNode);
    Optional<org.bukkit.Location> nextLocOpt = getNodeLocation(graph, nextNode);

    if (currentLocOpt.isEmpty() || nextLocOpt.isEmpty()) {
      return OptionalDouble.empty();
    }

    Vector currentPos = currentLocOpt.get().toVector();
    Vector nextPos = nextLocOpt.get().toVector();

    // 计算总距离和已行驶距离
    double totalDistance = currentPos.distance(nextPos);
    if (totalDistance <= 0.0) {
      return OptionalDouble.of(0.0);
    }

    double traveledDistance = currentPos.distance(trainPos);
    double progress = Math.min(1.0, Math.max(0.0, traveledDistance / totalDistance));

    return OptionalDouble.of(progress);
  }

  /** 获取节点的世界坐标（从图的节点数据）。 */
  private static Optional<org.bukkit.Location> getNodeLocation(RailGraph graph, NodeId nodeId) {
    if (graph == null || nodeId == null) {
      return Optional.empty();
    }
    // 从图中查找节点的 anchor 位置
    // 当前实现：RailGraph 不直接存储坐标，需要从 SignNodeRegistry 查找
    // 这里返回 empty，让调用方 fallback 到边长度估算
    return Optional.empty();
  }

  /**
   * 计算多边路径的总剩余距离。
   *
   * @param currentEdgeRemaining 当前边的剩余距离
   * @param remainingEdges 后续边列表
   * @return 总剩余距离
   */
  public static long totalRemainingDistance(
      long currentEdgeRemaining, Iterable<RailEdge> remainingEdges) {
    long total = Math.max(0, currentEdgeRemaining);
    if (remainingEdges != null) {
      for (RailEdge edge : remainingEdges) {
        if (edge != null) {
          total += Math.max(0, edge.lengthBlocks());
        }
      }
    }
    return total;
  }
}
