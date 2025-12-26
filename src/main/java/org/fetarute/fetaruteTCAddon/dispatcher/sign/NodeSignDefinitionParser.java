package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import java.util.EnumSet;
import java.util.Optional;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;

/** 从牌子方块解析节点定义（waypoint/autostation/depot）。 */
public final class NodeSignDefinitionParser {

  private static final PlainTextComponentSerializer PLAIN_TEXT =
      PlainTextComponentSerializer.plainText();

  private NodeSignDefinitionParser() {}

  public static Optional<SignNodeDefinition> parse(Sign sign) {
    if (sign == null) {
      return Optional.empty();
    }

    SignSide front = sign.getSide(Side.FRONT);
    String header = PLAIN_TEXT.serialize(front.line(1)).trim().toLowerCase(java.util.Locale.ROOT);
    NodeType nodeType;
    EnumSet<WaypointKind> expectedKinds;
    switch (header) {
      case "waypoint" -> {
        nodeType = NodeType.WAYPOINT;
        expectedKinds =
            EnumSet.of(
                WaypointKind.INTERVAL, WaypointKind.STATION_THROAT, WaypointKind.DEPOT_THROAT);
      }
      case "autostation" -> {
        nodeType = NodeType.STATION;
        expectedKinds = EnumSet.of(WaypointKind.STATION);
      }
      case "depot" -> {
        nodeType = NodeType.DEPOT;
        expectedKinds = EnumSet.of(WaypointKind.DEPOT);
      }
      default -> {
        return Optional.empty();
      }
    }

    String primary = PLAIN_TEXT.serialize(front.line(2));
    String fallback = PLAIN_TEXT.serialize(front.line(3));
    String rawId = !primary.isEmpty() ? primary : fallback;
    return SignTextParser.parseWaypointLike(rawId, nodeType)
        .filter(
            def ->
                def.waypointMetadata()
                    .map(metadata -> expectedKinds.contains(metadata.kind()))
                    .orElse(false));
  }
}
