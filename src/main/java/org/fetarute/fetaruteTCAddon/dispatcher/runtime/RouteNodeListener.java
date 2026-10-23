package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;

/**
 * 列车到达或经过交路节点（车站、路径点）时的回调，紧接在 CHANGE 移交之后调用；用于多模车按交路备注换模与解编。
 *
 * <p>回调不读占用、不改授权；抛出的异常由调用方吞掉，不影响调度推进。
 */
@FunctionalInterface
public interface RouteNodeListener {

  /** 什么也不做。 */
  RouteNodeListener NONE = (trainName, properties, route, index, dwelling) -> 0;

  /**
   * @param route 交路
   * @param index 节点在交路里的下标
   * @param dwelling 列车在这里停车（车站、停车的路径点）
   * @return 这一站要多停的秒数（路径点停车时加到停车时长上）
   */
  int onRouteNode(
      String trainName, TrainProperties properties, RouteId route, int index, boolean dwelling);

  /** 两个监听都调用、多停的秒数相加；前一个抛异常时后一个照样调用，异常再抛给调用方记日志。 */
  default RouteNodeListener andThen(RouteNodeListener next) {
    return (trainName, properties, route, index, dwelling) -> {
      int first;
      try {
        first = onRouteNode(trainName, properties, route, index, dwelling);
      } catch (RuntimeException ex) {
        next.onRouteNode(trainName, properties, route, index, dwelling);
        throw ex;
      }
      return first + next.onRouteNode(trainName, properties, route, index, dwelling);
    };
  }
}
