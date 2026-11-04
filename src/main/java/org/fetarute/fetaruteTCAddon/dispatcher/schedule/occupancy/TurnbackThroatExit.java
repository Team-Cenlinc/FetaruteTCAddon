package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import java.util.List;
import java.util.OptionalInt;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;

/**
 * 交路在站咽喉/车库咽喉折返时的联锁出口规则，供请求构建（原子窗口、冲突出口窗口）与运行时咽喉原子准入共用。
 *
 * <p>完整路径进入联锁后一路只有咽喉与道岔、终点就是折返咽喉时，找不到车站/车库/区间清出点。列车本来就停在这里原路折回，车身压着的道岔由自己的占用挡住，
 * 所以以终点的折返咽喉为出口。只认完整路径的终点、只认站咽喉/车库咽喉（道岔不算）；路径被截在中途时终点只是途经点，调用方不得套用本规则。
 */
public final class TurnbackThroatExit {

  private TurnbackThroatExit() {}

  /**
   * 完整路径终点是折返咽喉时返回终点索引。
   *
   * @param graph 调度图
   * @param path 完整路径（交路终点或折返切分点为止）
   * @return 终点为站咽喉/车库咽喉时的终点索引；否则为空
   */
  public static OptionalInt pathEndExit(RailGraph graph, List<NodeId> path) {
    if (graph == null || path == null || path.size() < 2) {
      return OptionalInt.empty();
    }
    int endIndex = path.size() - 1;
    return isThroat(graph, path.get(endIndex)) ? OptionalInt.of(endIndex) : OptionalInt.empty();
  }

  /** 站咽喉或车库咽喉（五段牌子）；道岔不算。 */
  public static boolean isThroat(RailGraph graph, NodeId nodeId) {
    return graph != null
        && nodeId != null
        && graph
            .findNode(nodeId)
            .flatMap(RailNode::waypointMetadata)
            .map(WaypointMetadata::kind)
            .filter(WaypointKind::isThroat)
            .isPresent();
  }
}
