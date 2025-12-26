package org.fetarute.fetaruteTCAddon.dispatcher.graph.persist;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;

/** 持久化的节点记录（来自节点牌子）。 */
public record RailNodeRecord(
    UUID worldId,
    NodeId nodeId,
    NodeType nodeType,
    int x,
    int y,
    int z,
    Optional<String> trainCartsDestination,
    Optional<WaypointMetadata> waypointMetadata) {

  public RailNodeRecord {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(nodeId, "nodeId");
    Objects.requireNonNull(nodeType, "nodeType");
    trainCartsDestination =
        trainCartsDestination == null ? Optional.empty() : trainCartsDestination;
    Objects.requireNonNull(trainCartsDestination, "trainCartsDestination");
    waypointMetadata = waypointMetadata == null ? Optional.empty() : waypointMetadata;
    Objects.requireNonNull(waypointMetadata, "waypointMetadata");
  }
}
