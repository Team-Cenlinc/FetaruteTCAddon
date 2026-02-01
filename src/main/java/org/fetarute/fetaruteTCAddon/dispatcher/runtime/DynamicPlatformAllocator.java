package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
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

  /** 候选轨道遍历的安全上限，防止 spec 范围过大导致卡服。 */
  private static final int MAX_TRACK_CANDIDATES = 20;

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
    return tryAllocate(trainName, route, currentIndex, graph, currentNode, Optional.empty());
  }

  /**
   * 检查并尝试为列车分配动态站台（含方向优选）。
   *
   * @param trainName 列车名称
   * @param route 当前 RouteDefinition
   * @param currentIndex 当前 waypoint 索引
   * @param graph 调度图（用于计算 edge 距离）
   * @param currentNode 列车当前所在节点
   * @param trainDirection 列车实际运行方向（优先使用；缺失时从 waypoints 推算）
   * @return 分配结果（如果触发分配）
   */
  public Optional<AllocationResult> tryAllocate(
      String trainName,
      RouteDefinition route,
      int currentIndex,
      RailGraph graph,
      NodeId currentNode,
      Optional<BlockFace> trainDirection) {
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
      Optional<NodeId> allocated =
          allocatePlatform(
              trainName, spec, graph, waypoints, currentIndex, currentNode, trainDirection);
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

  /**
   * 直接从 DynamicSpec 分配站台（用于 DSTY DYNAMIC depot 等场景）。
   *
   * <p>不依赖 RouteDefinition waypoints，直接根据 spec 和 currentNode 进行分配。
   *
   * @param trainName 列车名称
   * @param spec DYNAMIC 规范
   * @param graph 调度图
   * @param currentNode 当前节点
   * @return 分配的 NodeId，或 empty
   */
  public Optional<NodeId> allocateDirect(
      String trainName, DynamicSpec spec, RailGraph graph, NodeId currentNode) {
    if (trainName == null || spec == null || graph == null || currentNode == null) {
      return Optional.empty();
    }
    // 直接调用分配逻辑，无 waypoints 和方向信息
    return allocatePlatform(
        trainName,
        spec,
        graph,
        java.util.Collections.emptyList(),
        -1,
        currentNode,
        Optional.empty());
  }

  /**
   * 从 DYNAMIC 范围内选择可用站台。
   *
   * <p>当存在多个可用候选时，使用“方向优选”避免在 X 字渡线/多道岔区域走出 360° 折回：
   *
   * <ol>
   *   <li>运行方向：优先使用列车实际 {@code BlockFace}；fallback 为 {@code prevNode -> currentNode} 的 2D 向量
   *   <li>候选方向：{@code currentNode -> guideNode} 的方向向量（guideNode 为路径上首个非 SWITCHER 节点）
   *   <li>用点积作为相似度，选择得分最高者
   * </ol>
   */
  private Optional<NodeId> allocatePlatform(
      String trainName,
      DynamicSpec spec,
      RailGraph graph,
      List<NodeId> routeWaypoints,
      int currentIndex,
      NodeId currentNode,
      Optional<BlockFace> trainDirection) {
    if (spec == null || graph == null || routeWaypoints == null || currentNode == null) {
      return Optional.empty();
    }

    // 计算列车运行方向：优先使用列车实际 BlockFace；fallback 为 prev -> current
    Vector travelDir = trainDirection.map(this::blockFaceToVector2d).orElse(null);
    if (travelDir == null && currentIndex > 0 && currentIndex - 1 < routeWaypoints.size()) {
      NodeId prevNode = routeWaypoints.get(currentIndex - 1);
      travelDir =
          computeDirection2d(getNodePosition(graph, prevNode), getNodePosition(graph, currentNode));
    }

    RailGraphPathFinder pathFinder = new RailGraphPathFinder();
    List<ApproachCandidate> candidates = new ArrayList<>();

    // 安全上限：防止 spec 范围过大导致长时间循环
    int maxTrack = Math.min(spec.toTrack(), spec.fromTrack() + MAX_TRACK_CANDIDATES - 1);
    for (int track = spec.fromTrack(); track <= maxTrack; track++) {
      NodeId candidate =
          NodeId.of(
              spec.operatorCode() + ":" + spec.nodeType() + ":" + spec.nodeName() + ":" + track);
      if (graph.findNode(candidate).isEmpty()) {
        continue;
      }

      Optional<RailGraphPath> pathOpt =
          pathFinder.shortestPath(
              graph, currentNode, candidate, RailGraphPathFinder.Options.shortestDistance());
      if (pathOpt.isEmpty()) {
        continue;
      }

      boolean free = occupancyManager == null || !occupancyManager.isNodeOccupied(candidate);
      candidates.add(new ApproachCandidate(candidate, free, pathOpt.get().nodes()));
    }

    if (candidates.isEmpty()) {
      return Optional.empty();
    }

    // Pass 1: free first
    List<ApproachCandidate> freeCandidates =
        candidates.stream().filter(ApproachCandidate::free).toList();
    ApproachCandidate chosen =
        freeCandidates.isEmpty()
            ? selectBestCandidateByDirection(trainName, currentNode, travelDir, candidates, graph)
            : selectBestCandidateByDirection(
                trainName, currentNode, travelDir, freeCandidates, graph);

    return Optional.ofNullable(chosen != null ? chosen.nodeId : null);
  }

  private record ApproachCandidate(NodeId nodeId, boolean free, List<NodeId> pathNodes) {
    private ApproachCandidate {
      Objects.requireNonNull(nodeId, "nodeId");
      Objects.requireNonNull(pathNodes, "pathNodes");
      pathNodes = List.copyOf(pathNodes);
    }
  }

  private ApproachCandidate selectBestCandidateByDirection(
      String trainName,
      NodeId fromNode,
      Vector travelDir,
      List<ApproachCandidate> candidates,
      RailGraph graph) {
    if (candidates == null || candidates.isEmpty()) {
      return null;
    }
    // 无法计算方向时，保持 deterministic：按 track 顺序取第一个。
    if (travelDir == null) {
      return candidates.get(0);
    }
    Vector fromPos = getNodePosition(graph, fromNode);
    if (fromPos == null) {
      return candidates.get(0);
    }

    ApproachCandidate best = null;
    double bestScore = Double.NEGATIVE_INFINITY;
    for (ApproachCandidate cand : candidates) {
      NodeId guideNode = findPathGuideNode(cand.pathNodes, graph, fromNode);
      if (guideNode == null) {
        guideNode = cand.nodeId;
      }
      Vector guideDir = computeDirection2d(fromPos, getNodePosition(graph, guideNode));
      if (guideDir == null) {
        continue;
      }
      double score = travelDir.getX() * guideDir.getX() + travelDir.getZ() * guideDir.getZ();
      if (score > bestScore) {
        bestScore = score;
        best = cand;
      }
    }

    if (best != null && candidates.size() > 1) {
      debugLogger.accept(
          "DYNAMIC(approach) 方向优选: train="
              + trainName
              + ", from="
              + fromNode.value()
              + ", chosen="
              + best.nodeId.value()
              + ", candidates="
              + candidates.size());
    }

    return best != null ? best : candidates.get(0);
  }

  private NodeId findPathGuideNode(List<NodeId> pathNodes, RailGraph graph, NodeId fromNode) {
    if (pathNodes == null || pathNodes.size() < 2 || graph == null) {
      return null;
    }

    int fromIndex = -1;
    for (int i = 0; i < pathNodes.size(); i++) {
      if (fromNode.equals(pathNodes.get(i))) {
        fromIndex = i;
        break;
      }
    }
    int start = fromIndex >= 0 ? fromIndex + 1 : 1;
    for (int i = start; i < pathNodes.size(); i++) {
      NodeId node = pathNodes.get(i);
      if (node == null) {
        continue;
      }
      Optional<RailNode> railNodeOpt = graph.findNode(node);
      if (railNodeOpt.isEmpty()) {
        continue;
      }
      if (railNodeOpt.get().type() != NodeType.SWITCHER) {
        return node;
      }
    }
    return null;
  }

  private Vector getNodePosition(RailGraph graph, NodeId nodeId) {
    return graph.findNode(nodeId).map(RailNode::worldPosition).orElse(null);
  }

  private Vector computeDirection2d(Vector from, Vector to) {
    if (from == null || to == null) {
      return null;
    }
    double dx = to.getX() - from.getX();
    double dz = to.getZ() - from.getZ();
    double mag = Math.sqrt(dx * dx + dz * dz);
    if (mag < 1.0e-6) {
      return null;
    }
    return new Vector(dx / mag, 0.0, dz / mag);
  }

  /** 将 BlockFace 转换为归一化 2D 方向向量（X/Z 平面）。 */
  private Vector blockFaceToVector2d(BlockFace face) {
    if (face == null) {
      return null;
    }
    return switch (face) {
      case NORTH -> new Vector(0.0, 0.0, -1.0);
      case SOUTH -> new Vector(0.0, 0.0, 1.0);
      case EAST -> new Vector(1.0, 0.0, 0.0);
      case WEST -> new Vector(-1.0, 0.0, 0.0);
      default -> null;
    };
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
