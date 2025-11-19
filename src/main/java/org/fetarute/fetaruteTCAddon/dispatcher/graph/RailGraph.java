package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * 提供对调度图的只读视图，用于路线规划、ETA 计算以及运行时诊断。
 */
public interface RailGraph {

    /**
     * @return 图中全部节点的快照。
     */
    Collection<RailNode> nodes();

    /**
     * @return 图中全部区间的快照。
     */
    Collection<RailEdge> edges();

    /**
     * @return 根据节点 ID 查询节点。
     */
    Optional<RailNode> findNode(NodeId id);

    /**
     * @return 某节点可达的区间集合；若节点不存在返回空集合。
     */
    Set<RailEdge> edgesFrom(NodeId id);

    /**
     * @return 指定区间是否被运行时封锁（施工、故障等）。
     */
    boolean isBlocked(EdgeId id);
}
