package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher.DynamicSpec;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;

/**
 * 动态站台分配器。
 *
 * <p>在列车接近 DYNAMIC 站点时（距离 ≤5 edges），从候选轨道范围中选择一个可用站台并写入列车 destination。
 *
 * <p>触发条件：
 *
 * <ul>
 *   <li>当前 RouteStop 为 DYNAMIC 类型
 *   <li>列车距离该站点 ≤ {@link #ALLOCATION_EDGE_THRESHOLD} 个 edges
 *   <li>尚未为该站点分配过站台（避免重复分配）
 * </ul>
 *
 * <p>分配策略：
 *
 * <ul>
 *   <li>遍历 [fromTrack, toTrack] 范围内的所有候选站台
 *   <li>优先选择未被占用的站台
 *   <li>若所有站台都被占用，选择第一个候选（排队等待）
 * </ul>
 */
public final class DynamicPlatformAllocator {

  /** 触发分配的 edge 阈值：距离 DYNAMIC 站点 ≤5 edges 时触发。 */
  public static final int ALLOCATION_EDGE_THRESHOLD = 5;

  private final RouteDefinitionCache routeDefinitions;
  private final OccupancyManager occupancyManager;
  private final Consumer<String> debugLogger;

  /** 已分配记录：trainName -> (routeId:stopSequence) -> allocatedNodeId */
  private final Map<String, Map<String, NodeId>> allocations = new ConcurrentHashMap<>();

  public DynamicPlatformAllocator(
      RouteDefinitionCache routeDefinitions,
      OccupancyManager occupancyManager,
      Consumer<String> debugLogger) {
    this.routeDefinitions = Objects.requireNonNull(routeDefinitions, "routeDefinitions");
    this.occupancyManager = occupancyManager;
    this.debugLogger = debugLogger != null ? debugLogger : s -> {};
  }

  /**
   * 检查并尝试为列车分配动态站台。
   *
   * @param trainName 列车名称
   * @param route 当前 RouteDefinition
   * @param currentIndex 当前 waypoint 索引
   * @param graph 调度图（用于计算 edge 距离）
   * @param currentNode 列车当前所在节点
   * @return 分配结果（如果触发分配）
   */
  public Optional<AllocationResult> tryAllocate(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      RailGraph graph,
      NodeId currentNode) {
    if (trainName == null || route == null || graph == null) {
      return Optional.empty();
    }

    List<NodeId> waypoints = route.waypoints();
    if (waypoints.isEmpty() || currentIndex < 0 || currentIndex >= waypoints.size()) {
      return Optional.empty();
    }

    // 查找下一个 DYNAMIC stop
    for (int lookAhead = 1; lookAhead <= ALLOCATION_EDGE_THRESHOLD + 2; lookAhead++) {
      int targetIndex = currentIndex + lookAhead;
      if (targetIndex >= waypoints.size()) {
        break;
      }

      Optional<RouteStop> stopOpt = routeDefinitions.findStop(route.id(), targetIndex);
      if (stopOpt.isEmpty()) {
        continue;
      }

      RouteStop stop = stopOpt.get();
      Optional<DynamicSpec> specOpt = DynamicStopMatcher.parseDynamicSpec(stop);
      if (specOpt.isEmpty()) {
        continue;
      }

      // 检查是否已分配
      String allocationKey = route.id().value() + ":" + stop.sequence();
      Map<String, NodeId> trainAllocations =
          allocations.computeIfAbsent(
              trainName.toLowerCase(Locale.ROOT), k -> new ConcurrentHashMap<>());
      if (trainAllocations.containsKey(allocationKey)) {
        continue;
      }

      // 计算到目标的 edge 距离
      int edgeDistance = calculateEdgeDistance(graph, waypoints, currentIndex, targetIndex);
      if (edgeDistance > ALLOCATION_EDGE_THRESHOLD) {
        continue;
      }

      // 执行分配
      DynamicSpec spec = specOpt.get();
      Optional<NodeId> allocated = allocatePlatform(trainName, spec, graph);
      if (allocated.isEmpty()) {
        debugLogger.accept(
            "DYNAMIC 分配失败: 无可用站台 (train=" + trainName + ", spec=" + formatSpec(spec) + ")");
        continue;
      }

      NodeId allocatedNode = allocated.get();
      trainAllocations.put(allocationKey, allocatedNode);

      debugLogger.accept(
          "DYNAMIC 分配成功: train="
              + trainName
              + ", spec="
              + formatSpec(spec)
              + ", allocated="
              + allocatedNode.value()
              + ", edgeDistance="
              + edgeDistance);

      return Optional.of(
          new AllocationResult(trainName, route.id(), targetIndex, spec, allocatedNode));
    }

    return Optional.empty();
  }

  /**
   * 获取已分配的站台（用于 RouteDefinition 节点覆盖）。
   *
   * @param trainName 列车名称
   * @param routeId 路线 ID
   * @param stopSequence 停靠点序号
   * @return 已分配的 NodeId
   */
  public Optional<NodeId> getAllocation(String trainName, RouteId routeId, int stopSequence) {
    if (trainName == null || routeId == null) {
      return Optional.empty();
    }
    Map<String, NodeId> trainAllocations = allocations.get(trainName.toLowerCase(Locale.ROOT));
    if (trainAllocations == null) {
      return Optional.empty();
    }
    String key = routeId.value() + ":" + stopSequence;
    return Optional.ofNullable(trainAllocations.get(key));
  }

  /**
   * 清除列车的所有分配记录（列车销毁/完成运行时调用）。
   *
   * @param trainName 列车名称
   */
  public void clearAllocations(String trainName) {
    if (trainName != null) {
      allocations.remove(trainName.toLowerCase(Locale.ROOT));
    }
  }

  /** 从 DYNAMIC 范围内选择可用站台。 */
  private Optional<NodeId> allocatePlatform(String trainName, DynamicSpec spec, RailGraph graph) {
    // 构建图中的节点 ID 集合用于快速查找
    Set<NodeId> graphNodes = graph.nodes().stream().map(n -> n.id()).collect(Collectors.toSet());

    NodeId firstCandidate = null;
    for (int track = spec.fromTrack(); track <= spec.toTrack(); track++) {
      String nodeIdValue =
          spec.operatorCode() + ":" + spec.nodeType() + ":" + spec.nodeName() + ":" + track;
      NodeId candidate = NodeId.of(nodeIdValue);

      // 检查节点是否存在于图中
      if (!graphNodes.contains(candidate)) {
        continue;
      }

      if (firstCandidate == null) {
        firstCandidate = candidate;
      }

      // 检查是否被占用
      if (occupancyManager != null && occupancyManager.isNodeOccupied(candidate)) {
        continue;
      }

      return Optional.of(candidate);
    }

    // 所有站台都被占用，返回第一个候选（排队等待）
    return Optional.ofNullable(firstCandidate);
  }

  /** 计算从 currentIndex 到 targetIndex 的 edge 数量（沿 waypoints 路径）。 */
  private int calculateEdgeDistance(
      RailGraph graph, List<NodeId> waypoints, int currentIndex, int targetIndex) {
    if (currentIndex >= targetIndex) {
      return 0;
    }

    int edgeCount = 0;
    RailGraphPathFinder pathFinder = new RailGraphPathFinder();

    for (int i = currentIndex; i < targetIndex && i + 1 < waypoints.size(); i++) {
      NodeId from = waypoints.get(i);
      NodeId to = waypoints.get(i + 1);

      // 直接相邻的 waypoint 计为 1 edge
      // 若中间有路径则计算实际 edge 数
      var pathOpt =
          pathFinder.shortestPath(graph, from, to, RailGraphPathFinder.Options.shortestDistance());
      if (pathOpt.isPresent()) {
        edgeCount += Math.max(1, pathOpt.get().nodes().size() - 1);
      } else {
        edgeCount += 1; // fallback
      }
    }

    return edgeCount;
  }

  private static String formatSpec(DynamicSpec spec) {
    if (spec == null) {
      return "-";
    }
    String range =
        spec.fromTrack() == spec.toTrack()
            ? String.valueOf(spec.fromTrack())
            : "[" + spec.fromTrack() + ":" + spec.toTrack() + "]";
    return spec.operatorCode() + ":" + spec.nodeType() + ":" + spec.nodeName() + ":" + range;
  }

  /**
   * 分配结果。
   *
   * @param trainName 列车名称
   * @param routeId 路线 ID
   * @param stopIndex 停靠点索引
   * @param spec DYNAMIC 规范
   * @param allocatedNode 分配的具体站台 NodeId
   */
  public record AllocationResult(
      String trainName, RouteId routeId, int stopIndex, DynamicSpec spec, NodeId allocatedNode) {
    public AllocationResult {
      Objects.requireNonNull(trainName, "trainName");
      Objects.requireNonNull(routeId, "routeId");
      Objects.requireNonNull(spec, "spec");
      Objects.requireNonNull(allocatedNode, "allocatedNode");
    }
  }
}
