package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteProgress;

import java.util.Optional;

/**
 * 运行时追踪的列车状态，供调度算法和信息屏共享。
 */
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
     * @return 列车在当前区间的预计到达时间戳（游戏刻）。
     */
    long etaTick();
}
