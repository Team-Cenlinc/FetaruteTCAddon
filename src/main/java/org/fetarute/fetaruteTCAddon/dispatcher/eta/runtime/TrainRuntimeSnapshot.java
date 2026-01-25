package org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * 运行时 ETA 采样快照：每列车一份，仅存轻量字段。
 *
 * <p>采样阶段只写快照，不做 ETA 计算，避免控车 tick 变重。
 *
 * <h2>速度与位置字段（用于精确 ETA）</h2>
 *
 * <ul>
 *   <li>{@code currentSpeedBps}：当前速度（blocks per second）
 *   <li>{@code distanceToNextBlocks}：到下一节点的剩余距离（blocks）
 *   <li>{@code edgeLengthBlocks}：当前边总长度（blocks），用于计算进度
 * </ul>
 *
 * <p>这些字段为 Optional，若采样时无法获取则返回 empty，ETA 计算会使用默认估算。
 */
public record TrainRuntimeSnapshot(
    long updatedTick,
    Instant updatedAt,
    UUID worldId,
    UUID routeUuid,
    RouteId routeId,
    int routeIndex,
    Optional<NodeId> currentNodeId,
    Optional<NodeId> lastPassedNodeId,
    Optional<Integer> dwellRemainingSec,
    Optional<SignalAspect> signalAspect,
    Optional<String> ticketId,
    OptionalDouble currentSpeedBps,
    OptionalInt distanceToNextBlocks,
    OptionalInt edgeLengthBlocks) {

  public TrainRuntimeSnapshot {
    Objects.requireNonNull(updatedAt, "updatedAt");
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(routeUuid, "routeUuid");
    Objects.requireNonNull(routeId, "routeId");
    currentNodeId = currentNodeId == null ? Optional.empty() : currentNodeId;
    lastPassedNodeId = lastPassedNodeId == null ? Optional.empty() : lastPassedNodeId;
    dwellRemainingSec = dwellRemainingSec == null ? Optional.empty() : dwellRemainingSec;
    signalAspect = signalAspect == null ? Optional.empty() : signalAspect;
    ticketId = ticketId == null ? Optional.empty() : ticketId;
    currentSpeedBps = currentSpeedBps == null ? OptionalDouble.empty() : currentSpeedBps;
    distanceToNextBlocks =
        distanceToNextBlocks == null ? OptionalInt.empty() : distanceToNextBlocks;
    edgeLengthBlocks = edgeLengthBlocks == null ? OptionalInt.empty() : edgeLengthBlocks;
  }

  /** 兼容旧构造：不含速度/距离字段。 */
  public TrainRuntimeSnapshot(
      long updatedTick,
      Instant updatedAt,
      UUID worldId,
      UUID routeUuid,
      RouteId routeId,
      int routeIndex,
      Optional<NodeId> currentNodeId,
      Optional<NodeId> lastPassedNodeId,
      Optional<Integer> dwellRemainingSec,
      Optional<SignalAspect> signalAspect,
      Optional<String> ticketId) {
    this(
        updatedTick,
        updatedAt,
        worldId,
        routeUuid,
        routeId,
        routeIndex,
        currentNodeId,
        lastPassedNodeId,
        dwellRemainingSec,
        signalAspect,
        ticketId,
        OptionalDouble.empty(),
        OptionalInt.empty(),
        OptionalInt.empty());
  }

  /**
   * 计算当前边的进度百分比（0.0 ~ 1.0）。
   *
   * @return 进度百分比，若无法计算则返回 0.5（中间值）
   */
  public double edgeProgressRatio() {
    if (edgeLengthBlocks.isEmpty() || edgeLengthBlocks.getAsInt() <= 0) {
      return 0.5;
    }
    if (distanceToNextBlocks.isEmpty()) {
      return 0.5;
    }
    int total = edgeLengthBlocks.getAsInt();
    int remaining = distanceToNextBlocks.getAsInt();
    double traveled = total - remaining;
    return Math.max(0.0, Math.min(1.0, traveled / total));
  }
}
