package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.EtaRuntimeSampler;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainSnapshotStore;
import org.fetarute.fetaruteTCAddon.dispatcher.health.HealthMonitor;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;

/**
 * 周期性检查信号等级变化，更新列车速度控制。
 *
 * <p>执行频率由配置 {@code runtime.dispatch-tick-interval-ticks} 控制。
 */
public final class RuntimeSignalMonitor implements Runnable {

  private final RuntimeDispatchService dispatchService;
  private final EtaRuntimeSampler etaSampler;
  private final TrainSnapshotStore snapshotStore;
  private final DwellRegistry dwellRegistry;
  private final RouteProgressRegistry routeProgressRegistry;
  private final RouteDefinitionCache routeDefinitions;
  private final HealthMonitor healthMonitor;

  public RuntimeSignalMonitor(
      RuntimeDispatchService dispatchService,
      EtaRuntimeSampler etaSampler,
      TrainSnapshotStore snapshotStore,
      DwellRegistry dwellRegistry,
      RouteProgressRegistry routeProgressRegistry,
      RouteDefinitionCache routeDefinitions,
      HealthMonitor healthMonitor) {
    this.dispatchService = Objects.requireNonNull(dispatchService, "dispatchService");
    this.etaSampler = etaSampler;
    this.snapshotStore = snapshotStore;
    this.dwellRegistry = dwellRegistry;
    this.routeProgressRegistry = routeProgressRegistry;
    this.routeDefinitions = routeDefinitions;
    this.healthMonitor = healthMonitor;
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
      String trainName =
          group.getProperties() != null ? group.getProperties().getTrainName() : null;
      if (trainName != null && !trainName.isBlank()) {
        activeTrainNames.add(trainName);
      }
      dispatchService.handleSignalTick(group);
      if (etaSampler != null && trainName != null && !trainName.isBlank()) {
        Optional<Integer> dwellRemainingSec =
            dwellRegistry != null ? dwellRegistry.remainingSeconds(trainName) : Optional.empty();
        NodeSampleInfo nodeInfo = resolveNodeInfo(trainName);
        etaSampler.sample(
            group,
            tick,
            now,
            nodeInfo.currentNodeId,
            nodeInfo.lastPassedNodeId,
            dwellRemainingSec,
            nodeInfo.signalAspect);
      }
    }
    dispatchService.cleanupOrphanOccupancyClaims(activeTrainNames);
    cleanupSnapshotStore(activeTrainNames);
    if (dwellRegistry != null) {
      dwellRegistry.retain(activeTrainNames);
    }
    // 健康监控 tick（内部有 checkInterval 控制，不会每次都检查）
    if (healthMonitor != null) {
      healthMonitor.tick();
    }
  }

  private NodeSampleInfo resolveNodeInfo(String trainName) {
    if (routeProgressRegistry == null || routeDefinitions == null) {
      return NodeSampleInfo.EMPTY;
    }
    Optional<RouteProgressRegistry.RouteProgressEntry> entryOpt =
        routeProgressRegistry.get(trainName);
    if (entryOpt.isEmpty()) {
      return NodeSampleInfo.EMPTY;
    }
    RouteProgressRegistry.RouteProgressEntry entry = entryOpt.get();
    if (entry.routeUuid() == null) {
      return new NodeSampleInfo(
          Optional.empty(), Optional.empty(), Optional.ofNullable(entry.lastSignal()));
    }
    Optional<RouteDefinition> routeOpt = routeDefinitions.findById(entry.routeUuid());
    if (routeOpt.isEmpty()) {
      return new NodeSampleInfo(
          Optional.empty(), Optional.empty(), Optional.ofNullable(entry.lastSignal()));
    }
    RouteDefinition route = routeOpt.get();
    List<NodeId> waypoints = route.waypoints();
    int currentIndex = entry.currentIndex();
    Optional<NodeId> currentNodeId =
        (currentIndex >= 0 && currentIndex < waypoints.size())
            ? Optional.of(waypoints.get(currentIndex))
            : Optional.empty();
    // 优先使用 RouteProgressEntry 中的 lastPassedGraphNode（中间 waypoint 触发会更新）
    // 若为空则回退到 route waypoints 上一个节点
    Optional<NodeId> lastPassedNodeId = entry.lastPassedGraphNode();
    if (lastPassedNodeId.isEmpty() && currentIndex > 0 && currentIndex - 1 < waypoints.size()) {
      lastPassedNodeId = Optional.of(waypoints.get(currentIndex - 1));
    }
    return new NodeSampleInfo(
        currentNodeId, lastPassedNodeId, Optional.ofNullable(entry.lastSignal()));
  }

  private record NodeSampleInfo(
      Optional<NodeId> currentNodeId,
      Optional<NodeId> lastPassedNodeId,
      Optional<SignalAspect> signalAspect) {
    static final NodeSampleInfo EMPTY =
        new NodeSampleInfo(Optional.empty(), Optional.empty(), Optional.empty());
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
