package org.fetarute.fetaruteTCAddon.stationmaster;

import java.util.List;
import java.util.OptionalInt;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLifecycleMode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;

/** 哪一站要清客。本类不依赖服务器对象。 */
final class PassengerClearing {

  private PassengerClearing() {}

  /**
   * 列车停在交路的最后一个载客站（运营终点，见 {@link RouteTerminals#endOfOperationIndex}），且之后不再载客：
   * 交路在这一站之后还有停靠点（回库、空驶到车库或折返线），或交路在终点之后销毁。
   *
   * <p>终点就是交路最后一站、终到后留在站里待命的车不算：它多半接着开下一趟，车上的乘客等的正是那一趟。
   *
   * @param stops 交路的停靠点
   * @param index 列车此刻的进度索引
   * @param node 列车停靠的股道
   */
  static boolean lastPassengerStop(List<RouteStop> stops, int index, NodeId node) {
    OptionalInt eop = RouteTerminals.endOfOperationIndex(stops);
    if (eop.isEmpty() || index != eop.getAsInt() || node == null) {
      return false;
    }
    if (!RouteTerminals.matches(node, stops.get(eop.getAsInt()))) {
      return false;
    }
    return eop.getAsInt() < stops.size() - 1
        || RouteDefinition.resolveMode(stops) == RouteLifecycleMode.DESTROY_AFTER_TERM;
  }
}
