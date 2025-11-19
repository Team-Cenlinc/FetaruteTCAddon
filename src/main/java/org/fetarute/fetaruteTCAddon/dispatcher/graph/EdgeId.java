package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

import java.util.Objects;

/**
 * 用于标记两个节点之间的区间。为了保证无向图的稳定性，内部会自动排序端点。
 */
public record EdgeId(NodeId a, NodeId b) {

    public EdgeId {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        if (a.equals(b)) {
            throw new IllegalArgumentException("Edge 端点不能相同: " + a);
        }
    }

    /**
     * 构建一个方向无关的 EdgeId，自动对端点排序以便在 Map 中共用键。
     */
    public static EdgeId undirected(NodeId first, NodeId second) {
        if (first.value().compareTo(second.value()) <= 0) {
            return new EdgeId(first, second);
        }
        return new EdgeId(second, first);
    }
}
