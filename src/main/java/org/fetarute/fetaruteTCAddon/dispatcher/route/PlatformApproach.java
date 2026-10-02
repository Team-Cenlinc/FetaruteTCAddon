package org.fetarute.fetaruteTCAddon.dispatcher.route;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;

/**
 * 进站方向规则：几条空闲站台里挑哪一条，取进站路径最顺着来车方向的那条，避免在 X 字渡线、多道岔咽喉里走出 360° 折回。
 *
 * <p>运行时选台（{@code DynamicPlatformAllocator}）与编表排计划站台（{@code TimetablePlatformPlanner}）共用这一条规则：
 * 计划站台因此就是车本来会选的那条，不会为了计划让车多穿一次渡线。
 */
public final class PlatformApproach {

  private PlatformApproach() {}

  /**
   * 两个节点之间在 X/Z 平面上的方向。
   *
   * @return 单位向量；任一节点不在图上或两者重合时为空
   */
  public static Optional<Vector> direction(RailGraph graph, NodeId from, NodeId to) {
    if (graph == null || from == null || to == null) {
      return Optional.empty();
    }
    return direction(position(graph, from), position(graph, to));
  }

  /**
   * 候选站台顺着来车方向的程度：从 {@code fromNode} 看，进站路径上第一个非道岔节点（路径上没有就是候选本身）的方向与来车方向的点积，越大越顺。
   *
   * @param graph 调度图
   * @param fromNode 车所在（或进站前最后经过）的节点
   * @param travelDir 来车方向（X/Z 平面单位向量）；为空时算不出
   * @param pathNodes 从 {@code fromNode} 到候选的路径
   * @param candidate 候选站台
   * @return 点积；方向或位置算不出时为空
   */
  public static OptionalDouble score(
      RailGraph graph,
      NodeId fromNode,
      Vector travelDir,
      List<NodeId> pathNodes,
      NodeId candidate) {
    if (graph == null || fromNode == null || travelDir == null) {
      return OptionalDouble.empty();
    }
    Optional<Vector> fromPos = position(graph, fromNode);
    if (fromPos.isEmpty()) {
      return OptionalDouble.empty();
    }
    NodeId guide = guideNode(pathNodes, graph, fromNode).orElse(candidate);
    return direction(fromPos, position(graph, guide))
        .map(
            guideDir ->
                OptionalDouble.of(
                    travelDir.getX() * guideDir.getX() + travelDir.getZ() * guideDir.getZ()))
        .orElse(OptionalDouble.empty());
  }

  /** 路径上 {@code fromNode} 之后第一个非道岔节点：道岔就在车头前，方向看不出车会进哪条股道。 */
  private static Optional<NodeId> guideNode(
      List<NodeId> pathNodes, RailGraph graph, NodeId fromNode) {
    if (pathNodes == null || pathNodes.size() < 2) {
      return Optional.empty();
    }
    int start = Math.max(1, pathNodes.indexOf(fromNode) + 1);
    for (int i = start; i < pathNodes.size(); i++) {
      NodeId node = pathNodes.get(i);
      if (node != null
          && graph
              .findNode(node)
              .map(RailNode::type)
              .filter(t -> t != NodeType.SWITCHER)
              .isPresent()) {
        return Optional.of(node);
      }
    }
    return Optional.empty();
  }

  private static Optional<Vector> position(RailGraph graph, NodeId node) {
    return node == null ? Optional.empty() : graph.findNode(node).map(RailNode::worldPosition);
  }

  private static Optional<Vector> direction(Optional<Vector> from, Optional<Vector> to) {
    if (from.isEmpty() || to.isEmpty()) {
      return Optional.empty();
    }
    double dx = to.get().getX() - from.get().getX();
    double dz = to.get().getZ() - from.get().getZ();
    double mag = Math.sqrt(dx * dx + dz * dz);
    return mag < 1.0e-6 ? Optional.empty() : Optional.of(new Vector(dx / mag, 0.0, dz / mag));
  }
}
