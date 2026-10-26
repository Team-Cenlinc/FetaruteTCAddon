package org.fetarute.fetaruteTCAddon.dispatcher.graph.persist;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;

/**
 * 持久化的节点记录（来自节点牌子）。
 *
 * @param doorDirection AutoStation 牌子第 4 行的开门方向（枚举名，如 {@code NORTH}、{@code BOTH}）；不是车站节点、没写或旧数据时为空
 */
public record RailNodeRecord(
    UUID worldId,
    NodeId nodeId,
    NodeType nodeType,
    int x,
    int y,
    int z,
    Optional<String> trainCartsDestination,
    Optional<WaypointMetadata> waypointMetadata,
    Optional<String> doorDirection) {

  public RailNodeRecord {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(nodeId, "nodeId");
    Objects.requireNonNull(nodeType, "nodeType");
    trainCartsDestination =
        trainCartsDestination == null ? Optional.empty() : trainCartsDestination;
    Objects.requireNonNull(trainCartsDestination, "trainCartsDestination");
    waypointMetadata = waypointMetadata == null ? Optional.empty() : waypointMetadata;
    Objects.requireNonNull(waypointMetadata, "waypointMetadata");
    doorDirection =
        doorDirection == null
            ? Optional.empty()
            : doorDirection.map(String::trim).filter(text -> !text.isEmpty());
  }

  /** 不带开门方向的节点记录。 */
  public RailNodeRecord(
      UUID worldId,
      NodeId nodeId,
      NodeType nodeType,
      int x,
      int y,
      int z,
      Optional<String> trainCartsDestination,
      Optional<WaypointMetadata> waypointMetadata) {
    this(
        worldId,
        nodeId,
        nodeType,
        x,
        y,
        z,
        trainCartsDestination,
        waypointMetadata,
        Optional.empty());
  }
}
