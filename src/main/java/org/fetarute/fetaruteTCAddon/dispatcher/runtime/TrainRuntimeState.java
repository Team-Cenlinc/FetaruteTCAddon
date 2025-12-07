package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Instant;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteProgress;

/** 运行时追踪的列车状态，供调度算法和信息屏共享。 时间全部以真实世界时间为准，方便对接运营时刻表。 */
public interface TrainRuntimeState {

  /**
   * @return Bukkit 内部的列车标识，通常对应 TrainCarts 的名称。
   */
  String trainName();

  RouteProgress routeProgress();

  /**
   * @return 当前占用的节点（如果列车处于区间中则返回空）。
   */
  Optional<NodeId> occupiedNode();

  /**
   * @return 列车抵达下一节点或站台的预计现实时间；若无法计算则返回空。
   */
  Optional<Instant> estimatedArrivalTime();

  /**
   * @return 此状态最后同步的现实时间，便于检测陈旧数据。
   */
  Instant lastUpdatedAt();
}
