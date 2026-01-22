package org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import java.time.Instant;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainTagHelper;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * ETA 运行时采样器：从 TrainCarts 实体与运行时缓存提取轻量快照。
 *
 * <p>该类不做 ETA 计算，只负责写入 {@link TrainSnapshotStore}。
 */
public final class EtaRuntimeSampler {

  private final RouteProgressRegistry progressRegistry;
  private final TrainSnapshotStore snapshotStore;

  public EtaRuntimeSampler(
      RouteProgressRegistry progressRegistry, TrainSnapshotStore snapshotStore) {
    this.progressRegistry = progressRegistry;
    this.snapshotStore = snapshotStore;
  }

  public void sample(
      MinecartGroup group,
      long tick,
      Instant now,
      Optional<NodeId> currentNodeId,
      Optional<NodeId> lastPassedNodeId,
      Optional<Integer> dwellRemainingSec,
      Optional<SignalAspect> signalAspect) {
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
            TrainTagHelper.readTagValue(group.getProperties(), "FTA_TICKET_ID")));
  }
}
