package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.EtaRuntimeSampler;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainSnapshotStore;

/**
 * 周期性检查信号等级变化，更新列车速度控制。
 *
 * <p>执行频率由配置 {@code runtime.dispatch-tick-interval-ticks} 控制。
 */
public final class RuntimeSignalMonitor implements Runnable {

  private final RuntimeDispatchService dispatchService;
  private final EtaRuntimeSampler etaSampler;
  private final TrainSnapshotStore snapshotStore;

  public RuntimeSignalMonitor(
      RuntimeDispatchService dispatchService,
      EtaRuntimeSampler etaSampler,
      TrainSnapshotStore snapshotStore) {
    this.dispatchService = Objects.requireNonNull(dispatchService, "dispatchService");
    this.etaSampler = etaSampler;
    this.snapshotStore = snapshotStore;
  }

  @Override
  public void run() {
    Collection<MinecartGroup> groups = MinecartGroupStore.getGroups();
    if (groups == null) {
      return;
    }
    Instant now = Instant.now();
    long tick = now.toEpochMilli() / 50L;
    Set<String> activeTrainNames = new HashSet<>();
    for (MinecartGroup group : groups) {
      if (group == null || !group.isValid()) {
        continue;
      }
      if (group.getProperties() != null && group.getProperties().getTrainName() != null) {
        activeTrainNames.add(group.getProperties().getTrainName());
      }
      dispatchService.handleSignalTick(group);
      if (etaSampler != null) {
        etaSampler.sample(
            group,
            tick,
            now,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
      }
    }
    dispatchService.cleanupOrphanOccupancyClaims(activeTrainNames);
    cleanupSnapshotStore(activeTrainNames);
  }

  private void cleanupSnapshotStore(Set<String> activeTrainNames) {
    if (snapshotStore == null || activeTrainNames == null) {
      return;
    }
    for (String trainName : snapshotStore.snapshot().keySet()) {
      if (!activeTrainNames.contains(trainName)) {
        snapshotStore.remove(trainName);
      }
    }
  }
}
