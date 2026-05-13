package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;

/**
 * 单个列车在单个信号周期内的规范化行车计划快照。
 *
 * <p>快照同时保存完整展开路径和当前占用窗口内真正需要前进授权的资源。信号相关模块应传递并复用该快照，而不是分别用 route waypoint、currentIndex 或 lookahead
 * depth 重新展开路径。
 *
 * @param trainKey 规范化列车键
 * @param routeId 线路 route id
 * @param routeIndex 当前 route index
 * @param currentNode 当前有效图节点
 * @param lastPassedGraphNode 最近经过的图节点
 * @param effectiveFromNode 本次计划起点
 * @param effectiveToNode 本次计划首个目标节点
 * @param expandedPathPlan 完整展开路径计划
 * @param movementRequiredResources 当前授权窗口内的前进必须资源
 * @param occupancyVersion 占用快照版本
 * @param progressVersion 运行进度快照版本
 * @param requestId 请求快照 id
 */
public record MovementPlanSnapshot(
    String trainKey,
    Optional<RouteId> routeId,
    int routeIndex,
    Optional<NodeId> currentNode,
    Optional<NodeId> lastPassedGraphNode,
    Optional<NodeId> effectiveFromNode,
    Optional<NodeId> effectiveToNode,
    ExpandedPathPlan expandedPathPlan,
    List<OccupancyResource> movementRequiredResources,
    long occupancyVersion,
    long progressVersion,
    String requestId) {

  public MovementPlanSnapshot {
    trainKey = trainKey == null ? "" : TrainNameNormalizer.normalizeKey(trainKey);
    routeId = routeId == null ? Optional.empty() : routeId;
    currentNode = currentNode == null ? Optional.empty() : currentNode;
    lastPassedGraphNode = lastPassedGraphNode == null ? Optional.empty() : lastPassedGraphNode;
    effectiveFromNode = effectiveFromNode == null ? Optional.empty() : effectiveFromNode;
    effectiveToNode = effectiveToNode == null ? Optional.empty() : effectiveToNode;
    expandedPathPlan = expandedPathPlan == null ? ExpandedPathPlan.empty() : expandedPathPlan;
    movementRequiredResources =
        movementRequiredResources == null ? List.of() : List.copyOf(movementRequiredResources);
    requestId = requestId == null || requestId.isBlank() ? "-" : requestId.trim();
  }

  /** 从请求的有向上下文生成快照。 */
  public static Optional<MovementPlanSnapshot> fromRequest(OccupancyRequest request) {
    if (request == null || request.directedContext().isEmpty()) {
      return Optional.empty();
    }
    DirectedTraversalContext context = request.directedContext().get();
    List<OccupancyResource> movement = new ArrayList<>();
    for (OccupancyResource resource : request.resourceList()) {
      if (resource != null && request.intentFor(resource) == ResourceIntent.MOVEMENT_REQUIRED) {
        movement.add(resource);
      }
    }
    return Optional.of(
        new MovementPlanSnapshot(
            context.trainKey(),
            context.routeId(),
            context.currentIndex(),
            context.currentNode(),
            context.lastPassedGraphNode(),
            context.effectiveFromNode(),
            context.effectiveToNode(),
            new ExpandedPathPlan(
                context.expandedPathNodes(),
                context.directedEdges(),
                context.singleConflictDirections(),
                context.switcherPathSignatures()),
            movement,
            context.occupancyVersion(),
            context.progressVersion(),
            context.requestId()));
  }

  /** 便捷访问完整展开节点。 */
  public List<NodeId> expandedPathNodes() {
    return expandedPathPlan.expandedPathNodes();
  }

  /** 便捷访问完整有向边。 */
  public List<DirectedTraversalContext.DirectedEdge> directedEdges() {
    return expandedPathPlan.directedEdges();
  }

  /** 便捷访问单线方向。 */
  public Map<String, CorridorDirection> singleConflictDirections() {
    return expandedPathPlan.singleConflictDirections();
  }

  /** 便捷访问道岔路径签名。 */
  public Map<String, DirectedTraversalContext.SwitcherPathSignature> switcherPathSignatures() {
    return expandedPathPlan.switcherPathSignatures();
  }
}
