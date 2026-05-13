package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphConflictSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.DirectedTraversalContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.MovementPlanSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;

/**
 * 单线入口 lookahead 评估器。
 *
 * <p>评估器只读取 {@link MovementPlanSnapshot} 的 canonical expanded path。当前占用窗口看不到出口时，会继续在同一快照的 expanded
 * path 内扩展到 single exit；只有扩展后仍不可见出口，才返回 fail-closed。
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
          true, windowNodes, zoneId, entryIndex, exitBefore, false, exitBefore, true, "-");
    }
    int maxEdges = Math.max(windowEdges, Math.min(maxLookaheadEdges, edgeCount));
    int exitAfter = firstExitIndex(edges, support, zoneId, entryIndex, maxEdges);
    boolean feasible = exitAfter >= 0;
    return new Result(
        true,
        windowNodes,
        zoneId,
        entryIndex,
        -1,
        true,
        exitAfter,
        feasible,
        feasible ? "-" : "exit-not-visible-after-extension");
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
}
