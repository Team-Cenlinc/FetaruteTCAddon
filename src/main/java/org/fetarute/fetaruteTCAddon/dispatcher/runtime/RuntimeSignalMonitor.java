package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import com.bergerkiller.bukkit.tc.controller.status.TrainStatus;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 运行时巡检器。
 *
 * <p>该类只负责周期性扫描在线列车、清理异常编组、采样 ETA 以及把结果送入 {@link RuntimeDispatchService}。真正的信号控制核心仍位于 {@link
 * RuntimeDispatchService#handleSignalTick(RuntimeTrainHandle, boolean)}，这里不直接承担运行时控车决策。
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
    List<GroupTickTarget> candidates = new ArrayList<>();
    Map<String, List<GroupTickTarget>> groupsByLogicalName = new LinkedHashMap<>();
    for (MinecartGroup group : groups) {
      if (group == null || !group.isValid()) {
        continue;
      }
      if (isDerailed(group)) {
        dispatchService.handleAbnormalGroup(group, "status-derailed");
        continue;
      }
      String rawTrainName = resolveRawTrainName(group);
      String trainName = resolveLogicalTrainName(group);
      if (trainName != null && !trainName.isBlank()) {
        groupsByLogicalName
            .computeIfAbsent(trainName, unused -> new ArrayList<>())
            .add(new GroupTickTarget(group, trainName, rawTrainName));
      }
      candidates.add(new GroupTickTarget(group, trainName, rawTrainName));
    }

    Set<MinecartGroup> duplicateGroups = cleanupDuplicateLogicalTrains(groupsByLogicalName);
    Set<String> activeTrainNames = new HashSet<>();
    for (GroupTickTarget candidate : candidates) {
      MinecartGroup group = candidate.group();
      if (duplicateGroups.contains(group)) {
        continue;
      }
      String trainName = candidate.trainName();
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
   * 清理“同一逻辑列车名对应多个实体”的异常场景。
   *
   * <p>TrainCarts split 后可能短时间内留下多个携带相同 {@code FTA_TRAIN_NAME} 的 group；若继续让它们并行进入信号/占用流程，会共同读写同一份
   * progress 与 claim，导致调度状态迅速混乱。此处采用保守策略：真实重复直接清理；split 过渡态只保留一个主编组继续驱动，临时别名跳过本轮巡检。
   *
   * @param groupsByLogicalName 按逻辑列车名分组后的实体
   * @return 本轮已按重复异常处理的 group 集合
   */
  private Set<MinecartGroup> cleanupDuplicateLogicalTrains(
      Map<String, List<GroupTickTarget>> groupsByLogicalName) {
    if (groupsByLogicalName == null || groupsByLogicalName.isEmpty()) {
      return Set.of();
    }
    Map<String, Integer> groupCounts = new LinkedHashMap<>();
    for (Map.Entry<String, List<GroupTickTarget>> entry : groupsByLogicalName.entrySet()) {
      List<GroupTickTarget> sameTrainGroups = entry.getValue();
      groupCounts.put(entry.getKey(), sameTrainGroups == null ? 0 : sameTrainGroups.size());
    }
    Set<String> duplicateTrainNames = findDuplicateLogicalTrainNames(groupCounts);
    if (duplicateTrainNames.isEmpty()) {
      return Set.of();
    }
    Set<MinecartGroup> duplicates = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Map.Entry<String, List<GroupTickTarget>> entry : groupsByLogicalName.entrySet()) {
      if (!duplicateTrainNames.contains(entry.getKey())) {
        continue;
      }
      List<GroupTickTarget> sameTrainGroups = entry.getValue();
      if (sameTrainGroups == null || sameTrainGroups.isEmpty()) {
        continue;
      }
      List<String> rawTrainNames = new ArrayList<>();
      for (GroupTickTarget sameTrainGroup : sameTrainGroups) {
        if (sameTrainGroup == null) {
          continue;
        }
        if (sameTrainGroup.rawTrainName() != null && !sameTrainGroup.rawTrainName().isBlank()) {
          rawTrainNames.add(sameTrainGroup.rawTrainName());
        }
      }
      if (isLikelySplitTransitionFamily(entry.getKey(), rawTrainNames)) {
        GroupTickTarget canonicalGroup = null;
        for (GroupTickTarget sameTrainGroup : sameTrainGroups) {
          if (sameTrainGroup == null || sameTrainGroup.group() == null) {
            continue;
          }
          if (sameTrainGroup.rawTrainName() != null
              && sameTrainGroup.rawTrainName().equalsIgnoreCase(entry.getKey())) {
            canonicalGroup = sameTrainGroup;
            break;
          }
        }
        GroupTickTarget keepGroup =
            canonicalGroup != null ? canonicalGroup : sameTrainGroups.get(0);
        for (GroupTickTarget sameTrainGroup : sameTrainGroups) {
          if (sameTrainGroup == null || sameTrainGroup.group() == null) {
            continue;
          }
          if (sameTrainGroup == keepGroup) {
            continue;
          }
          duplicates.add(sameTrainGroup.group());
        }
        continue;
      }
      String detail = buildDuplicateLogicalTrainDetail(entry.getKey(), sameTrainGroups.size());
      for (GroupTickTarget sameTrainGroup : sameTrainGroups) {
        if (sameTrainGroup == null || sameTrainGroup.group() == null) {
          continue;
        }
        duplicates.add(sameTrainGroup.group());
        dispatchService.handleAbnormalGroup(
            sameTrainGroup.group(), "duplicate-logical-train", detail);
      }
    }
    return duplicates;
  }

  /**
   * 计算“同一逻辑列车名被多个实体同时占用”的名称集合。
   *
   * <p>抽成纯逻辑方法，便于单测覆盖 split/重复实体判定，而不依赖 TrainCarts 重量级运行时类型。
   *
   * @param groupCounts 每个逻辑列车名对应的实体数量
   * @return 重复的逻辑列车名集合
   */
  static Set<String> findDuplicateLogicalTrainNames(Map<String, Integer> groupCounts) {
    if (groupCounts == null || groupCounts.isEmpty()) {
      return Set.of();
    }
    Set<String> duplicates = new HashSet<>();
    for (Map.Entry<String, Integer> entry : groupCounts.entrySet()) {
      String trainName = entry.getKey();
      Integer count = entry.getValue();
      if (trainName == null || trainName.isBlank() || count == null || count <= 1) {
        continue;
      }
      duplicates.add(trainName);
    }
    return Set.copyOf(duplicates);
  }

  /**
   * 生成重复逻辑列车告警的附加诊断文本。
   *
   * <p>抽成纯逻辑方法，便于单测覆盖日志上下文而不依赖 TrainCarts 实体。
   *
   * @param trainName 逻辑列车名
   * @param groupCount 同名实体数量
   * @return 用于异常日志的 detail 文本
   */
  static String buildDuplicateLogicalTrainDetail(String trainName, int groupCount) {
    StringBuilder builder = new StringBuilder();
    RuntimeDiagnosticFormatter.appendKeyValue(builder, "logicalTrain", trainName);
    if (groupCount > 0) {
      builder.append(" groups=").append(groupCount);
    }
    return builder.length() == 0 ? null : builder.toString();
  }

  /**
   * 判定一组同名编组是否更像 TrainCarts split 的过渡态，而不是“真实重复”。
   *
   * <p>只要这组原始名称全部满足以下条件，就视为过渡态并跳过重复清理：
   *
   * <ul>
   *   <li>原始名称等于逻辑名
   *   <li>或原始名称是逻辑名的 split 临时别名，例如 {@code main~a}
   * </ul>
   */
  static boolean isLikelySplitTransitionFamily(
      String logicalTrainName, List<String> rawTrainNames) {
    if (logicalTrainName == null || logicalTrainName.isBlank()) {
      return false;
    }
    if (rawTrainNames == null || rawTrainNames.isEmpty()) {
      return false;
    }
    boolean hasSplitAlias = false;
    for (String rawTrainName : rawTrainNames) {
      if (rawTrainName == null || rawTrainName.isBlank()) {
        return false;
      }
      if (rawTrainName.equalsIgnoreCase(logicalTrainName)) {
        continue;
      }
      if (!isSplitAliasName(rawTrainName, logicalTrainName)) {
        return false;
      }
      hasSplitAlias = true;
    }
    return hasSplitAlias;
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

  private String resolveLogicalTrainName(MinecartGroup group) {
    if (group == null || group.getProperties() == null) {
      return null;
    }
    return dispatchService
        .resolveTrackedTrainName(group.getProperties())
        .orElse(group.getProperties().getTrainName());
  }

  private String resolveRawTrainName(MinecartGroup group) {
    if (group == null || group.getProperties() == null) {
      return null;
    }
    String trainName = group.getProperties().getTrainName();
    return trainName == null || trainName.isBlank() ? null : trainName.trim();
  }

  /** 判断当前列车名是否只是另一逻辑列车名的 split 别名。 */
  private static boolean isSplitAliasName(String currentTrainName, String taggedTrainName) {
    if (currentTrainName == null || taggedTrainName == null) {
      return false;
    }
    if (currentTrainName.length() <= taggedTrainName.length()
        || !currentTrainName.regionMatches(true, 0, taggedTrainName, 0, taggedTrainName.length())) {
      return false;
    }
    int index = taggedTrainName.length();
    while (index < currentTrainName.length()) {
      if (currentTrainName.charAt(index) != '~') {
        return false;
      }
      index++;
      int segmentStart = index;
      while (index < currentTrainName.length() && currentTrainName.charAt(index) != '~') {
        char c = currentTrainName.charAt(index);
        if (!Character.isLetterOrDigit(c)) {
          return false;
        }
        index++;
      }
      if (segmentStart == index) {
        return false;
      }
    }
    return true;
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

  private record GroupTickTarget(MinecartGroup group, String trainName, String rawTrainName) {}

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
