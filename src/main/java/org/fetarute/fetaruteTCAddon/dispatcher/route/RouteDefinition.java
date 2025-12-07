package org.fetarute.fetaruteTCAddon.dispatcher.route;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/** 描述某条线路所遵循的节点序列，列车仅需依次把 destination 设置为下一节点。 */
public record RouteDefinition(
    RouteId id, List<NodeId> waypoints, Optional<RouteMetadata> metadata) {

  public RouteDefinition {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(waypoints, "waypoints");
    Objects.requireNonNull(metadata, "metadata");
    if (waypoints.isEmpty()) {
      throw new IllegalArgumentException("线路至少要包含一个节点");
    }
  }

  public static RouteDefinition of(RouteId id, List<NodeId> nodes, RouteMetadata metadata) {
    return new RouteDefinition(id, nodes, Optional.ofNullable(metadata));
  }
}
