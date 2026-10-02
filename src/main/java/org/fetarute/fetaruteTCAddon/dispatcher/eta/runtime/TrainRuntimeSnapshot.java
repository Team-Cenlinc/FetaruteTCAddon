package org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLineChanges;
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
 *
 * <p>{@code lineTag} 是采样时列车的线路标签（{@code FTA_OPERATOR_CODE}/{@code FTA_LINE_CODE}，出车与直通运转 CHANGE
 * 写入）。标签只能在主线程读，采样时顺手记下，公开 API 在任意线程读快照即可； 两个标签不全时为空，读取方按交路本身的线路处理（见 {@link
 * RouteLineChanges#current}）。
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
    OptionalInt edgeLengthBlocks,
    OptionalDouble traveledSinceLastPassedBlocks,
    HoldTimeline holdTimeline,
    Optional<RouteLineChanges.LineRef> lineTag) {

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
    traveledSinceLastPassedBlocks =
        traveledSinceLastPassedBlocks == null
            ? OptionalDouble.empty()
            : traveledSinceLastPassedBlocks;
    holdTimeline = holdTimeline == null ? HoldTimeline.EMPTY : holdTimeline;
    lineTag = lineTag == null ? Optional.empty() : lineTag;
  }

  /** 兼容调用：不带线路标签。 */
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
      Optional<String> ticketId,
      OptionalDouble currentSpeedBps,
      OptionalInt distanceToNextBlocks,
      OptionalInt edgeLengthBlocks,
      OptionalDouble traveledSinceLastPassedBlocks,
      HoldTimeline holdTimeline) {
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
        currentSpeedBps,
        distanceToNextBlocks,
        edgeLengthBlocks,
        traveledSinceLastPassedBlocks,
        holdTimeline,
        Optional.empty());
  }

  /** 兼容调用：不带扣停时间线。 */
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
      Optional<String> ticketId,
      OptionalDouble currentSpeedBps,
      OptionalInt distanceToNextBlocks,
      OptionalInt edgeLengthBlocks,
      OptionalDouble traveledSinceLastPassedBlocks) {
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
        currentSpeedBps,
        distanceToNextBlocks,
        edgeLengthBlocks,
        traveledSinceLastPassedBlocks,
        HoldTimeline.EMPTY);
  }

  /** 兼容旧调用：不带“自上一节点起已行驶距离”。 */
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
      Optional<String> ticketId,
      OptionalDouble currentSpeedBps,
      OptionalInt distanceToNextBlocks,
      OptionalInt edgeLengthBlocks) {
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
        currentSpeedBps,
        distanceToNextBlocks,
        edgeLengthBlocks,
        OptionalDouble.empty());
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
        OptionalInt.empty(),
        OptionalDouble.empty());
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

  /**
   * 扣停相关的时间事实，由采样器逐次推进。
   *
   * <p>运行时停车状态在原因、明细或阻挡者变化时会整体替换、{@code enteredAt} 随之重置（停站期间明细每秒都在变），
   * 不能拿来量“已经扣了多久”。这里记的是跨状态替换连续成立的两个时刻：
   *
   * @param holdSince 本次连续非例行停车（信号、占用、授权、尾保等）的开始时刻；未被扣停时为空
   * @param dwellEndedAt 本站停站计时结束的时刻；仍在停站计时、或已离开该站时为空
   */
  public record HoldTimeline(Optional<Instant> holdSince, Optional<Instant> dwellEndedAt) {

    public static final HoldTimeline EMPTY = new HoldTimeline(Optional.empty(), Optional.empty());

    public HoldTimeline {
      holdSince = holdSince == null ? Optional.empty() : holdSince;
      dwellEndedAt = dwellEndedAt == null ? Optional.empty() : dwellEndedAt;
    }
  }
}
