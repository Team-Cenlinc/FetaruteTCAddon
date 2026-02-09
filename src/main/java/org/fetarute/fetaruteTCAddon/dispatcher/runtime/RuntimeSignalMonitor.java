package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.controller.status.TrainStatus;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.EtaRuntimeSampler;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainSnapshotStore;
import org.fetarute.fetaruteTCAddon.dispatcher.health.TrainHealthMonitor;
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

  /**
   * FTA tag 存在但 route 无法解析的列车，累计被观测到的 tick 次数。超过阈值视为"脱管"并清理。
   *
   * <p>避免因瞬时加载延迟误杀正在初始化的列车。
   */
  private final java.util.Map<String, Integer> staleTrainTicks =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** "脱管"列车被判定为异常前需连续被观测到的 tick 次数。 */
  private static final int STALE_THRESHOLD_TICKS = 60;

  public RuntimeSignalMonitor(
      RuntimeDispatchService dispatchService,
      EtaRuntimeSampler etaSampler,
      TrainSnapshotStore snapshotStore,
      DwellRegistry dwellRegistry,
      RouteProgressRegistry routeProgressRegistry,
      RouteDefinitionCache routeDefinitions) {
    this.dispatchService = Objects.requireNonNull(dispatchService, "dispatchService");
    this.etaSampler = etaSampler;
    this.snapshotStore = snapshotStore;
    this.dwellRegistry = dwellRegistry;
    this.routeProgressRegistry = routeProgressRegistry;
    this.routeDefinitions = routeDefinitions;
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
      if (isDerailed(group)) {
        dispatchService.handleAbnormalGroup(group, "status-derailed");
        continue;
      }
      String trainName =
          group.getProperties() != null ? group.getProperties().getTrainName() : null;
      if (trainName != null && !trainName.isBlank()) {
        activeTrainNames.add(trainName);
      }
      dispatchService.handleSignalTick(group);
      // 检测"脱管"列车：有 FTA tag 但 route 无法解析，连续多 tick 后视为异常并清理
      if (trainName != null && !trainName.isBlank()) {
        detectStaleFtaTrain(group, trainName);
      }
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
    staleTrainTicks.keySet().removeIf(name -> !activeTrainNames.contains(name));
    if (dwellRegistry != null) {
      dwellRegistry.retain(activeTrainNames);
    }
  }

  /**
   * 检测"脱管" FTA 列车：有 FTA tag 但 route 无法解析或 progressRegistry 无条目。
   *
   * <p>这类列车不会被 {@link TrainHealthMonitor} 检测（因无 progress entry），也不会被 {@code isDerailed}
   * 捕获。连续观测超过阈值后视为异常并清理，避免因瞬时加载延迟误杀正在初始化的列车。
   */
  private void detectStaleFtaTrain(MinecartGroup group, String trainName) {
    if (routeProgressRegistry == null) {
      return;
    }
    // 已有 progress 条目的列车由 TrainHealthMonitor 监控
    if (routeProgressRegistry.get(trainName).isPresent()) {
      staleTrainTicks.remove(trainName);
      return;
    }
    TrainProperties properties = group.getProperties();
    if (properties == null) {
      return;
    }
    // 检查是否有 FTA route tag（只检测曾经被 FTA 管控的列车）
    boolean hasRouteIndex =
        TrainTagHelper.readIntTag(properties, RouteProgressRegistry.TAG_ROUTE_INDEX).isPresent();
    boolean hasRouteId =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_ROUTE_ID)
            .filter(v -> !v.isBlank())
            .isPresent();
    boolean hasOperator =
        TrainTagHelper.readTagValue(properties, RouteProgressRegistry.TAG_OPERATOR_CODE)
            .filter(v -> !v.isBlank())
            .isPresent();
    if (!hasRouteIndex && !hasRouteId && !hasOperator) {
      // 非 FTA 列车，不检测
      staleTrainTicks.remove(trainName);
      return;
    }
    int ticks = staleTrainTicks.merge(trainName, 1, Integer::sum);
    if (ticks >= STALE_THRESHOLD_TICKS) {
      staleTrainTicks.remove(trainName);
      dispatchService.handleAbnormalGroup(group, "stale-no-progress");
    }
  }

  private static boolean isDerailed(MinecartGroup group) {
    if (group == null) {
      return false;
    }
    List<TrainStatus> statuses = group.getStatusInfo();
    if (statuses == null || statuses.isEmpty()) {
      return false;
    }
    for (TrainStatus status : statuses) {
      if (status instanceof TrainStatus.Derailed) {
        return true;
      }
    }
    return false;
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
