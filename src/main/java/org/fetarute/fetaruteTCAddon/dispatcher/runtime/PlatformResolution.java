package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 列车在某个停靠下标的实际股道定下来或变了。
 *
 * <p>所有落定都经过同一处（选台、到站观测、折返交接）：第一次定下、或与上一次不同才发，同值不发。
 *
 * @param trainName 列车名
 * @param routeKey 交路 ID（{@code 运营商:线路:交路}）
 * @param stopIndex 交路节点下标（与停靠序号同一口径）
 * @param declaredNode 交路声明的节点（DYNAMIC 处为占位股道）
 * @param previousNode 上一次定下的股道；第一次定下时为空
 * @param node 现在的股道
 * @param plannedNode 计划股道：DYNAMIC 为时刻表计划或暂定站台，没有时为空；固定站台为声明的股道
 * @param dynamic 该停靠是 DYNAMIC
 * @param at 发生时刻
 */
public record PlatformResolution(
    String trainName,
    String routeKey,
    int stopIndex,
    NodeId declaredNode,
    Optional<NodeId> previousNode,
    NodeId node,
    Optional<NodeId> plannedNode,
    boolean dynamic,
    Instant at) {

  public PlatformResolution {
    Objects.requireNonNull(trainName, "trainName");
    Objects.requireNonNull(routeKey, "routeKey");
    Objects.requireNonNull(declaredNode, "declaredNode");
    previousNode = previousNode == null ? Optional.empty() : previousNode;
    Objects.requireNonNull(node, "node");
    plannedNode = plannedNode == null ? Optional.empty() : plannedNode;
    Objects.requireNonNull(at, "at");
  }

  /** 原因。 */
  public Reason reason() {
    if (previousNode.isPresent()) {
      return Reason.CHANGED;
    }
    return plannedNode.filter(planned -> !planned.equals(node)).isPresent()
        ? Reason.CHANGED_FROM_PLAN
        : Reason.ASSIGNED;
  }

  /** 落定的原因。 */
  public enum Reason {
    /** 第一次定下，与计划一致或没有计划。 */
    ASSIGNED,
    /** 第一次定下，但不是计划股道（计划股道不可用而改选；固定站台停到了声明以外的股道）。 */
    CHANGED_FROM_PLAN,
    /** 已定下的股道改了（被挡改选、到站实际股道与选定不同）。 */
    CHANGED
  }
}
