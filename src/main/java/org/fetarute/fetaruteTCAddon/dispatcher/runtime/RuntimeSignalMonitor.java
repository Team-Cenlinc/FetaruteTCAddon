package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;

/**
 * 周期性检查信号等级变化，更新列车速度控制。
 *
 * <p>执行频率由配置 {@code runtime.dispatch-tick-interval-ticks} 控制。
 */
public final class RuntimeSignalMonitor implements Runnable {

  private final RuntimeDispatchService dispatchService;

  public RuntimeSignalMonitor(RuntimeDispatchService dispatchService) {
    this.dispatchService = Objects.requireNonNull(dispatchService, "dispatchService");
  }

  @Override
  public void run() {
    Collection<MinecartGroup> groups = MinecartGroupStore.getGroups();
    if (groups == null) {
      return;
    }
    Instant now = Instant.now();
    java.util.Set<String> activeTrainNames = new java.util.HashSet<>();
    for (MinecartGroup group : groups) {
      if (group == null || !group.isValid()) {
        continue;
      }
      if (group.getProperties() != null && group.getProperties().getTrainName() != null) {
        activeTrainNames.add(group.getProperties().getTrainName());
      }
      dispatchService.handleSignalTick(group);
    }
    dispatchService.cleanupOrphanOccupancyClaims(activeTrainNames, now);
  }
}
