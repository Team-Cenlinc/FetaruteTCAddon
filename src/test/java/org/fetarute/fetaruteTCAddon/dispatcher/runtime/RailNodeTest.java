package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.Optional;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;

final class RailNodeTest implements RailNode {

  private final NodeId id;
  private final NodeType type;
  private final Optional<WaypointMetadata> waypointMetadata;

  RailNodeTest(NodeId id) {
    this(id, NodeType.WAYPOINT, Optional.empty());
  }

  RailNodeTest(NodeId id, NodeType type, Optional<WaypointMetadata> waypointMetadata) {
    this.id = id;
    this.type = type == null ? NodeType.WAYPOINT : type;
    this.waypointMetadata = waypointMetadata == null ? Optional.empty() : waypointMetadata;
  }

  @Override
  public NodeId id() {
    return id;
  }

  @Override
  public NodeType type() {
    return type;
  }

  @Override
  public Vector worldPosition() {
    return new Vector(0, 0, 0);
  }

  @Override
  public Optional<String> trainCartsDestination() {
    return Optional.of(id.value());
  }

  @Override
  public Optional<WaypointMetadata> waypointMetadata() {
    return waypointMetadata;
  }
}
