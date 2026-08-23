package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphConflictSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.DirectedTraversalContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.MovementPlanSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignTextParser;

/**
 * 单线入口 lookahead 评估器。
 *
 * <p>评估器只读取 {@link MovementPlanSnapshot} 的 canonical expanded path。当前硬授权窗口看不到出口时，会继续在同一快照的 expanded
 * path 内定位所需清出点，但拓扑可见性本身不构成通行许可：只有当前 hard authority 已覆盖清出点才返回可行，否则返回所需授权边数并 fail-closed。规范计划明确终止在
 * single 边界 Station/Depot 时，仅当该行为节点已经位于当前 hard authority 内，才能作为安全停车例外。
 */
public final class EntryLookaheadEvaluator {

  private EntryLookaheadEvaluator() {}

  /** 入口 lookahead 结果。 */
  public record Result(
      boolean lookaheadUsesExpandedPath,
      int lookaheadWindowNodeCount,
      String entryZoneId,
      int entryZoneStartIndex,
      int exitIndexBeforeExtension,
      boolean extensionAttempted,
      int exitIndexAfterExtension,
      boolean exitFeasible,
      String failureReason) {

    public Result {
      entryZoneId = entryZoneId == null || entryZoneId.isBlank() ? "-" : entryZoneId.trim();
      failureReason = failureReason == null || failureReason.isBlank() ? "-" : failureReason.trim();
    }

    /** 是否应安全侧阻塞。 */
    public boolean failClosed() {
      return !exitFeasible;
    }

    /**
     * 返回本次入口授权至少需要覆盖的有向边数。
     *
     * <p>出口仅在 advisory expanded path 中可见时，该值会大于当前 hard window；调用方必须据此重新构建完整硬授权，不能把拓扑可见性直接当成通行许可。
     */
    public int requiredHardAuthorityEdgeCount() {
      return Math.max(exitIndexBeforeExtension, exitIndexAfterExtension);
    }

    /** 是否已经由当前 hard authority 覆盖到冲突区清出点。 */
    public boolean hardAuthorityCoversExit() {
      return entryZoneStartIndex < 0 || (exitFeasible && exitIndexBeforeExtension >= 0);
    }

    /** 是否已经证明授权末端位于冲突区外的安全停车侧。 */
    public boolean safeHoldOutsideConflict() {
      return hardAuthorityCoversExit();
    }

    /**
     * 校验冲突区外的授权末端是否同时满足当前制动距离。
     *
     * @param distanceToAuthorityEndBlocks 当前物理位置到 hard authority 末端的距离
     * @param requiredStoppingDistanceBlocks 当前速度、减速度与停车余量推导出的停车距离
     */
    public boolean brakingSafeHoldOutsideConflict(
        double distanceToAuthorityEndBlocks, double requiredStoppingDistanceBlocks) {
      return safeHoldOutsideConflict()
          && Double.isFinite(distanceToAuthorityEndBlocks)
          && Double.isFinite(requiredStoppingDistanceBlocks)
          && distanceToAuthorityEndBlocks >= 0.0
          && requiredStoppingDistanceBlocks >= 0.0
          && distanceToAuthorityEndBlocks + 1.0e-6 >= requiredStoppingDistanceBlocks;
    }
  }

  /** 在 canonical expanded path 中验证 single zone 出口是否可见。 */
  public static Result evaluate(
      MovementPlanSnapshot plan,
      RailGraphConflictSupport support,
      OccupancyResource conflict,
      int currentWindowEdgeCount,
      int maxLookaheadEdges) {
    String zoneId = conflict == null ? "-" : conflict.key();
    if (plan == null || support == null || conflict == null) {
      return new Result(true, 0, zoneId, -1, -1, false, -1, false, "missing-plan");
    }
    List<DirectedTraversalContext.DirectedEdge> edges = plan.directedEdges();
    int edgeCount = edges.size();
    int windowEdges = Math.max(0, Math.min(currentWindowEdgeCount, edgeCount));
    int windowNodes = Math.min(plan.expandedPathNodes().size(), windowEdges + 1);
    int entryIndex = firstZoneEdgeIndex(edges, support, zoneId, 0, windowEdges);
    if (entryIndex < 0) {
      return new Result(true, windowNodes, zoneId, -1, -1, false, -1, true, "-");
    }
    int exitBefore = firstExitIndex(edges, support, zoneId, entryIndex, windowEdges);
    if (exitBefore >= 0) {
      return new Result(
          true,
          windowNodes,
          zoneId,
          entryIndex,
          exitBefore,
          false,
          exitBefore,
          true,
          "exit-visible-before-extension");
    }
    if (targetIsSingleRegionBoundary(plan, support, zoneId, entryIndex, windowEdges)) {
      int boundaryIndex = Math.max(0, plan.expandedPathNodes().size() - 1);
      return new Result(
          true,
          windowNodes,
          zoneId,
          entryIndex,
          boundaryIndex,
          false,
          boundaryIndex,
          true,
          "exit-is-target-boundary");
    }
    int maxEdges = Math.max(windowEdges, Math.min(maxLookaheadEdges, edgeCount));
    int exitAfter = firstExitIndex(edges, support, zoneId, entryIndex, maxEdges);
    if (exitAfter >= 0) {
      return new Result(
          true,
          windowNodes,
          zoneId,
          entryIndex,
          -1,
          true,
          exitAfter,
          false,
          "exit-visible-outside-hard-authority");
    }
    if (targetIsSingleRegionBoundary(plan, support, zoneId, entryIndex, maxEdges)) {
      int boundaryIndex = Math.max(0, plan.expandedPathNodes().size() - 1);
      return new Result(
          true,
          windowNodes,
          zoneId,
          entryIndex,
          -1,
          true,
          boundaryIndex,
          false,
          "target-boundary-outside-hard-authority");
    }
    return new Result(
        true,
        windowNodes,
        zoneId,
        entryIndex,
        -1,
        true,
        -1,
        false,
        "exit-not-visible-after-extension");
  }

  private static int firstZoneEdgeIndex(
      List<DirectedTraversalContext.DirectedEdge> edges,
      RailGraphConflictSupport support,
      String zoneId,
      int startInclusive,
      int endExclusive) {
    for (int i = Math.max(0, startInclusive); i < Math.min(edges.size(), endExclusive); i++) {
      Optional<String> edgeConflict = support.conflictKeyForEdge(edges.get(i).edgeId());
      if (edgeConflict.isPresent() && edgeConflict.get().equals(zoneId)) {
        return i;
      }
    }
    return -1;
  }

  private static int firstExitIndex(
      List<DirectedTraversalContext.DirectedEdge> edges,
      RailGraphConflictSupport support,
      String zoneId,
      int startInclusive,
      int endExclusive) {
    boolean sawZone = false;
    for (int i = Math.max(0, startInclusive); i < Math.min(edges.size(), endExclusive); i++) {
      Optional<String> edgeConflict = support.conflictKeyForEdge(edges.get(i).edgeId());
      if (edgeConflict.isPresent() && edgeConflict.get().equals(zoneId)) {
        sawZone = true;
        continue;
      }
      if (sawZone) {
        return i + 1;
      }
    }
    return -1;
  }

  /**
   * 判断本次 route window 是否直接停在 single-region 的边界节点。
   *
   * <p>实服路线常把单线出口后的第一个 Station/Depot 作为目标节点，而 expanded path 中不会再出现下一条非冲突边。此时若同一逻辑列车已在 single-region
   * 内，抵达该行为节点就是 drain-out，不应因为“缺少出口后的下一条边”误判为不可证明。
   */
  private static boolean targetIsSingleRegionBoundary(
      MovementPlanSnapshot plan,
      RailGraphConflictSupport support,
      String zoneId,
      int entryIndex,
      int checkedEdges) {
    if (plan == null || support == null || zoneId == null || entryIndex < 0) {
      return false;
    }
    List<DirectedTraversalContext.DirectedEdge> edges = plan.directedEdges();
    List<NodeId> nodes = plan.expandedPathNodes();
    if (edges.isEmpty() || nodes.size() < edges.size() + 1 || checkedEdges < edges.size()) {
      return false;
    }
    for (int i = entryIndex; i < edges.size(); i++) {
      Optional<String> edgeConflict = support.conflictKeyForEdge(edges.get(i).edgeId());
      if (edgeConflict.isEmpty() || !edgeConflict.get().equals(zoneId)) {
        return false;
      }
    }
    return isStationOrDepotBoundary(nodes.get(nodes.size() - 1));
  }

  private static boolean isStationOrDepotBoundary(NodeId nodeId) {
    if (nodeId == null || nodeId.value() == null) {
      return false;
    }
    return SignTextParser.parseWaypointLike(nodeId.value(), NodeType.WAYPOINT)
        .flatMap(SignNodeDefinition::waypointMetadata)
        .map(
            metadata ->
                metadata.kind() == WaypointKind.STATION || metadata.kind() == WaypointKind.DEPOT)
        .orElse(false);
  }
}
