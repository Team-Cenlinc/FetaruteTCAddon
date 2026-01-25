package org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * ETA 运行时采样器：从 TrainCarts 实体与运行时缓存提取轻量快照。
 *
 * <p>该类不做 ETA 计算，只负责写入 {@link TrainSnapshotStore}。
 *
 * <h2>速度与距离采样</h2>
 *
 * <ul>
 *   <li>速度：从 MinecartGroup head 的 velocity 计算（blocks per second）
 *   <li>距离：需要图信息才能计算（当前边长度 - 已行驶距离），若无图信息则为 empty
 * </ul>
 */
public final class EtaRuntimeSampler {

  /** 每 tick 20 次，velocity magnitude 转 blocks/second 的系数。 */
  private static final double VELOCITY_TO_BPS = 20.0;

  private final RouteProgressRegistry progressRegistry;
  private final TrainSnapshotStore snapshotStore;

  public EtaRuntimeSampler(
      RouteProgressRegistry progressRegistry, TrainSnapshotStore snapshotStore) {
    this.progressRegistry = progressRegistry;
    this.snapshotStore = snapshotStore;
  }

  /**
   * 采样列车状态（不含图信息）。
   *
   * <p>此方法向后兼容，距离字段将为 empty。
   */
  public void sample(
      MinecartGroup group,
      long tick,
      Instant now,
      Optional<NodeId> currentNodeId,
      Optional<NodeId> lastPassedNodeId,
      Optional<Integer> dwellRemainingSec,
      Optional<SignalAspect> signalAspect) {
    sample(
        group, tick, now, currentNodeId, lastPassedNodeId, dwellRemainingSec, signalAspect, null);
  }

  /**
   * 采样列车状态（含图信息以计算边距离）。
   *
   * @param graph 调度图（用于查询边长度），可为 null
   */
  public void sample(
      MinecartGroup group,
      long tick,
      Instant now,
      Optional<NodeId> currentNodeId,
      Optional<NodeId> lastPassedNodeId,
      Optional<Integer> dwellRemainingSec,
      Optional<SignalAspect> signalAspect,
      RailGraph graph) {
    if (group == null || group.getProperties() == null) {
      return;
    }
    String trainName = group.getProperties().getTrainName();
    if (trainName == null || trainName.isBlank()) {
      return;
    }

    var entryOpt = progressRegistry.get(trainName);
    if (entryOpt.isEmpty()) {
      return;
    }
    RouteProgressRegistry.RouteProgressEntry entry = entryOpt.get();
    Optional<SignalAspect> resolvedSignal =
        signalAspect != null && signalAspect.isPresent()
            ? signalAspect
            : Optional.ofNullable(entry.lastSignal());

    // 采样速度
    OptionalDouble speedBps = sampleSpeed(group);

    // 采样边距离（需要 graph + lastPassedNodeId + currentNodeId/下一节点）
    OptionalInt distanceToNext = OptionalInt.empty();
    OptionalInt edgeLength = OptionalInt.empty();

    if (graph != null && lastPassedNodeId.isPresent()) {
      NodeId from = lastPassedNodeId.get();
      // 尝试确定下一节点
      Optional<NodeId> nextNode = determineNextNode(entry, currentNodeId);
      if (nextNode.isPresent()) {
        EdgeId edgeId = EdgeId.undirected(from, nextNode.get());
        Optional<RailEdge> edgeOpt = findEdge(graph, edgeId);
        if (edgeOpt.isPresent()) {
          RailEdge edge = edgeOpt.get();
          edgeLength = OptionalInt.of(edge.lengthBlocks());
          // 距离估算：暂时使用边长度的一半（后续可通过轨道位置精确计算）
          // TODO: 使用 RailPath position 计算精确距离
          distanceToNext = OptionalInt.of(edge.lengthBlocks() / 2);
        }
      }
    }

    snapshotStore.update(
        trainName,
        new TrainRuntimeSnapshot(
            tick,
            now != null ? now : Instant.now(),
            group.getWorld() != null ? group.getWorld().getUID() : new java.util.UUID(0L, 0L),
            entry.routeUuid() != null ? entry.routeUuid() : new java.util.UUID(0L, 0L),
            entry.routeId(),
            entry.currentIndex(),
            currentNodeId,
            lastPassedNodeId,
            dwellRemainingSec,
            resolvedSignal,
            TrainTagHelper.readTagValue(group.getProperties(), "FTA_TICKET_ID"),
            speedBps,
            distanceToNext,
            edgeLength));
  }

  /** 从 MinecartGroup 采样速度（blocks per second）。 */
  private OptionalDouble sampleSpeed(MinecartGroup group) {
    if (group == null || group.isEmpty()) {
      return OptionalDouble.empty();
    }
    MinecartMember<?> head = group.head();
    if (head == null || head.getEntity() == null) {
      return OptionalDouble.empty();
    }
    org.bukkit.util.Vector velocity = head.getEntity().getVelocity();
    if (velocity == null) {
      return OptionalDouble.empty();
    }
    // velocity 是每 tick 的移动量，乘以 20 得到 blocks/second
    double speedBps = velocity.length() * VELOCITY_TO_BPS;
    return OptionalDouble.of(speedBps);
  }

  /** 确定下一节点：优先使用 currentNodeId，否则从 route 推断。 */
  private Optional<NodeId> determineNextNode(
      RouteProgressRegistry.RouteProgressEntry entry, Optional<NodeId> currentNodeId) {
    // 如果 currentNodeId 是"当前停靠/目标节点"，直接使用
    if (currentNodeId.isPresent()) {
      return currentNodeId;
    }
    // 否则，从 route definition 中按 index 查找（需要 RouteDefinition）
    // 这里简化处理，返回 empty
    return Optional.empty();
  }

  /** 从图中查找边（通过遍历 edgesFrom）。 */
  private Optional<RailEdge> findEdge(RailGraph graph, EdgeId edgeId) {
    if (graph == null || edgeId == null) {
      return Optional.empty();
    }
    // 从 edgeId.a() 出发查找
    Set<RailEdge> edges = graph.edgesFrom(edgeId.a());
    if (edges != null) {
      for (RailEdge edge : edges) {
        if (edge.id().equals(edgeId)) {
          return Optional.of(edge);
        }
      }
    }
    // 从 edgeId.b() 出发查找（无向图）
    edges = graph.edgesFrom(edgeId.b());
    if (edges != null) {
      for (RailEdge edge : edges) {
        if (edge.id().equals(edgeId)) {
          return Optional.of(edge);
        }
      }
    }
    return Optional.empty();
  }
}
