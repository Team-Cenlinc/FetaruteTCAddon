package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

import java.util.Optional;

/**
 * 节点之间的区间数据，包含基础限速与长度。
 * 后续可扩展额外属性（单向、拥堵状态等）。
 */
public record RailEdge(
        EdgeId id,
        NodeId from,
        NodeId to,
        int lengthBlocks,
        double baseSpeedLimit,
        boolean bidirectional,
        Optional<RailEdgeMetadata> metadata
) {
}
