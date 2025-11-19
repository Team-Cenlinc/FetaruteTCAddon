package org.fetarute.fetaruteTCAddon.dispatcher.route;

import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

import java.util.Optional;

/**
 * 表示列车在某条线路上的当前位置（当前节点索引、下一目标节点等）。
 */
public interface RouteProgress {

    RouteId routeId();

    /**
     * @return 当前已经抵达的节点索引。
     */
    int currentIndex();

    /**
     * @return 下一目标节点，若线路已经结束则返回空。
     */
    Optional<NodeId> nextTarget();
}
