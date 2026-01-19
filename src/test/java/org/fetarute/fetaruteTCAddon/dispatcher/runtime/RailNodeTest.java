package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.Optional;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;

final class RailNodeTest implements RailNode {

  private final NodeId id;

  RailNodeTest(NodeId id) {
    this.id = id;
  }

  @Override
  public NodeId id() {
    return id;
  }

  @Override
  public NodeType type() {
    return NodeType.WAYPOINT;
  }

  @Override
  public Vector worldPosition() {
    return new Vector(0, 0, 0);
  }

  @Override
  public Optional<String> trainCartsDestination() {
    return Optional.of(id.value());
  }
}
