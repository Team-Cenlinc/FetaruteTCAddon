package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import java.util.Objects;
import java.util.Optional;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;

/** 由牌子注册信息生成的只读节点实现。 */
public record SignRailNode(
    NodeId id,
    NodeType type,
    Vector worldPosition,
    Optional<String> trainCartsDestination,
    Optional<WaypointMetadata> waypointMetadata)
    implements RailNode {

  public SignRailNode {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(worldPosition, "worldPosition");
    Objects.requireNonNull(trainCartsDestination, "trainCartsDestination");
    Objects.requireNonNull(waypointMetadata, "waypointMetadata");
  }

  @Override
  public Optional<WaypointMetadata> waypointMetadata() {
    return waypointMetadata;
  }
}
