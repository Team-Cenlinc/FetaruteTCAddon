package org.fetarute.fetaruteTCAddon.display.hud.bossbar;

import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignTextParser;

/** HUD 用的节点展示名解析（仅基于 NodeId 编码，不依赖数据库）。 */
public final class HudWaypointLabel {

  private HudWaypointLabel() {}

  /**
   * 尝试把 NodeId 转为“站名”。
   *
   * <p>区间点：优先显示终到站；站/咽喉/Depot：显示 origin。
   */
  public static String stationLabel(NodeId nodeId) {
    if (nodeId == null || nodeId.value() == null || nodeId.value().isBlank()) {
      return "-";
    }
    Optional<WaypointMetadata> metaOpt = parseWaypointMetadata(nodeId);
    if (metaOpt.isEmpty()) {
      return nodeId.value();
    }
    WaypointMetadata meta = metaOpt.get();
    if (meta.kind() == WaypointKind.INTERVAL) {
      return meta.destinationStation().orElse(meta.originStation());
    }
    return meta.originStation();
  }

  private static Optional<WaypointMetadata> parseWaypointMetadata(NodeId nodeId) {
    return SignTextParser.parseWaypointLike(nodeId.value(), NodeType.WAYPOINT)
        .flatMap(SignNodeDefinition::waypointMetadata);
  }
}
