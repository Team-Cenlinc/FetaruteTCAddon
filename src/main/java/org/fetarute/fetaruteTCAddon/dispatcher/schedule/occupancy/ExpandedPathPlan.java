package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.List;
import java.util.Map;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 规范化展开路径计划。
 *
 * <p>该记录只描述本次行车快照采用的 from-to 展开路径，不直接决定占用裁决。占用窗口可以只覆盖其中前 N 条边， 但 entry
 * lookahead、移动授权、信号诊断和最终发布门必须读同一份展开路径，避免事件链路与周期链路各自重新展开出不同视角。
 *
 * @param expandedPathNodes 从当前有效节点开始的完整展开节点序列
 * @param directedEdges 与节点序列对应的有向边序列
 * @param singleConflictDirections 单线冲突区方向
 * @param switcherPathSignatures 道岔区路径签名
 */
public record ExpandedPathPlan(
    List<NodeId> expandedPathNodes,
    List<DirectedTraversalContext.DirectedEdge> directedEdges,
    Map<String, CorridorDirection> singleConflictDirections,
    Map<String, DirectedTraversalContext.SwitcherPathSignature> switcherPathSignatures) {

  public ExpandedPathPlan {
    expandedPathNodes = expandedPathNodes == null ? List.of() : List.copyOf(expandedPathNodes);
    directedEdges = directedEdges == null ? List.of() : List.copyOf(directedEdges);
    singleConflictDirections =
        singleConflictDirections == null ? Map.of() : Map.copyOf(singleConflictDirections);
    switcherPathSignatures =
        switcherPathSignatures == null ? Map.of() : Map.copyOf(switcherPathSignatures);
  }

  /** 空路径计划。 */
  public static ExpandedPathPlan empty() {
    return new ExpandedPathPlan(List.of(), List.of(), Map.of(), Map.of());
  }
}
