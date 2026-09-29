package org.fetarute.fetaruteTCAddon.dispatcher.graph.build;

import java.util.Comparator;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 一个走满探索上限仍未遇到任何节点的探索方向。
 *
 * <p>该方向按尽头线处理：另一侧视为没有 FTA 节点（非 FTA 轨道或施工中），不产生区间，也不降级本轮足迹证据。若它本应是一条真实区间， 图中会缺少这段轨道，运维需在 {@link
 * #stopPosition()} 附近补节点牌子或截断轨道后重建。
 *
 * @param startNode 探索起点节点
 * @param stopPosition walker 达到上限时所在的轨道方块，用于现场定位这条延伸线
 */
public record UnterminatedDirection(NodeId startNode, RailBlockPos stopPosition)
    implements Comparable<UnterminatedDirection> {

  private static final Comparator<UnterminatedDirection> ORDER =
      Comparator.comparing(UnterminatedDirection::startNode)
          .thenComparingInt(direction -> direction.stopPosition().x())
          .thenComparingInt(direction -> direction.stopPosition().y())
          .thenComparingInt(direction -> direction.stopPosition().z());

  public UnterminatedDirection {
    Objects.requireNonNull(startNode, "startNode");
    Objects.requireNonNull(stopPosition, "stopPosition");
  }

  @Override
  public int compareTo(UnterminatedDirection other) {
    return ORDER.compare(this, other);
  }
}
