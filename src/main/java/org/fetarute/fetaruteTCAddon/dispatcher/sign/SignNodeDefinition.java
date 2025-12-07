package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;

/** 记录从 TrainCarts 牌子解析出的节点定义，供调度图与站点/区间管理使用。 */
public record SignNodeDefinition(
    NodeId nodeId,
    NodeType nodeType,
    Optional<String> trainCartsDestination,
    Optional<WaypointMetadata> waypointMetadata) {

  public SignNodeDefinition {
    Objects.requireNonNull(nodeId, "nodeId");
    Objects.requireNonNull(nodeType, "nodeType");
    Objects.requireNonNull(trainCartsDestination, "trainCartsDestination");
    Objects.requireNonNull(waypointMetadata, "waypointMetadata");
  }
}
