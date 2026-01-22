package org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * 运行时 ETA 采样快照：每列车一份，仅存轻量字段。
 *
 * <p>采样阶段只写快照，不做 ETA 计算，避免控车 tick 变重。
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
    Optional<String> ticketId) {

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
  }
}
