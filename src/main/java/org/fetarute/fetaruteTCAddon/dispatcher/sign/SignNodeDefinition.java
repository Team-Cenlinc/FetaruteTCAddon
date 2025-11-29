package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;

import java.util.Objects;
import java.util.Optional;

/**
 * 记录从 TrainCarts 牌子解析出的节点定义，便于同步调度图与 TC 路由。
 */
public record SignNodeDefinition(
        NodeId nodeId,
        NodeType nodeType,
        Optional<String> trainCartsDestination,
        Optional<WaypointMetadata> waypointMetadata
) {

    public SignNodeDefinition {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(nodeType, "nodeType");
        Objects.requireNonNull(trainCartsDestination, "trainCartsDestination");
        Objects.requireNonNull(waypointMetadata, "waypointMetadata");
    }
}
