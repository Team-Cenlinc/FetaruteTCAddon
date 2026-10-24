package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import java.util.Objects;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;

/**
 * 记录从 TrainCarts 牌子解析出的节点定义，供调度图与站点/区间管理使用。
 *
 * @param doorDirection AutoStation 牌子第 4 行的开门方向，存 {@link
 *     org.fetarute.fetaruteTCAddon.dispatcher.sign.action.AutoStationDoorDirection} 的枚举名（如 {@code
 *     NORTH}、{@code BOTH}）；不是车站牌子或没写时为空
 */
public record SignNodeDefinition(
    NodeId nodeId,
    NodeType nodeType,
    Optional<String> trainCartsDestination,
    Optional<WaypointMetadata> waypointMetadata,
    Optional<String> doorDirection) {

  public SignNodeDefinition {
    Objects.requireNonNull(nodeId, "nodeId");
    Objects.requireNonNull(nodeType, "nodeType");
    Objects.requireNonNull(trainCartsDestination, "trainCartsDestination");
    Objects.requireNonNull(waypointMetadata, "waypointMetadata");
    doorDirection =
        doorDirection == null
            ? Optional.empty()
            : doorDirection.map(String::trim).filter(text -> !text.isEmpty());
  }

  /** 不带开门方向的节点定义。 */
  public SignNodeDefinition(
      NodeId nodeId,
      NodeType nodeType,
      Optional<String> trainCartsDestination,
      Optional<WaypointMetadata> waypointMetadata) {
    this(nodeId, nodeType, trainCartsDestination, waypointMetadata, Optional.empty());
  }

  /** 换上开门方向，其余不变。 */
  public SignNodeDefinition withDoorDirection(Optional<String> doorDirection) {
    return new SignNodeDefinition(
        nodeId, nodeType, trainCartsDestination, waypointMetadata, doorDirection);
  }
}
