package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import com.bergerkiller.bukkit.tc.SignActionHeader;
import com.bergerkiller.bukkit.tc.rails.RailLookup.TrackedSign;
import java.util.Optional;
import java.util.Set;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.TrainCartsRailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;

/**
 * 从 TrainCarts 的 {@code [portal]} 牌子（MyWorlds 传送门）解析出一个传送门节点。
 *
 * <p>传送门牌子的头部是 {@code [portal]}，不是 train/cart；第二行是这个门自己的名字，目的地在 MyWorlds 里配置。节点 ID
 * 由“牌子所在轨道方块”的世界名与坐标组成；牌子方块坐标记在 {@code tcDestination} 字段里（{@code @portal:x,y,z}）， 建立传送门连接时据此向
 * MyWorlds 查目的地。
 */
public final class PortalSignDefinitionParser {

  /** 传送门节点 ID 的前缀。 */
  public static final String NODE_PREFIX = "PORTAL:";

  /** {@code tcDestination} 字段里记牌子坐标的前缀。 */
  public static final String SIGN_MARKER_PREFIX = "@portal:";

  private static final String PORTAL_MODE = "portal";

  private static final PlainTextComponentSerializer PLAIN_TEXT =
      PlainTextComponentSerializer.plainText();

  private PortalSignDefinitionParser() {}

  /** 解析真实牌子（正反两面）。 */
  public static Optional<SignNodeDefinition> parse(Sign sign) {
    if (sign == null) {
      return Optional.empty();
    }
    if (!isPortalHeader(line(sign, Side.FRONT)) && !isPortalHeader(line(sign, Side.BACK))) {
      return Optional.empty();
    }
    TrainCartsRailBlockAccess access = new TrainCartsRailBlockAccess(sign.getWorld());
    RailBlockPos center =
        new RailBlockPos(
            sign.getLocation().getBlockX(),
            sign.getLocation().getBlockY(),
            sign.getLocation().getBlockZ());
    Set<RailBlockPos> anchors = access.findNearestRailBlocks(center, 2);
    if (anchors.isEmpty()) {
      return Optional.empty();
    }
    RailBlockPos rail = SwitcherSignDefinitionParser.selectDeterministicAnchor(anchors);
    return Optional.of(definition(sign.getWorld().getName(), rail, center));
  }

  /** 解析 TrainCarts 跟踪到的牌子。 */
  public static Optional<SignNodeDefinition> parse(TrackedSign trackedSign) {
    if (trackedSign == null) {
      return Optional.empty();
    }
    SignActionHeader header = trackedSign.getHeader();
    if (header == null || !PORTAL_MODE.equalsIgnoreCase(header.getModeText())) {
      return Optional.empty();
    }
    Block railBlock = SwitcherSignDefinitionParser.railBlockOf(trackedSign);
    if (railBlock == null) {
      return Optional.empty();
    }
    Block signBlock = trackedSign.signBlock;
    RailBlockPos rail = new RailBlockPos(railBlock.getX(), railBlock.getY(), railBlock.getZ());
    RailBlockPos signPos =
        signBlock == null
            ? rail
            : new RailBlockPos(signBlock.getX(), signBlock.getY(), signBlock.getZ());
    return Optional.of(definition(railBlock.getWorld().getName(), rail, signPos));
  }

  private static SignNodeDefinition definition(
      String worldName, RailBlockPos rail, RailBlockPos sign) {
    return new SignNodeDefinition(
        nodeIdForRail(worldName, rail),
        NodeType.PORTAL,
        Optional.of(SIGN_MARKER_PREFIX + sign.x() + "," + sign.y() + "," + sign.z()),
        Optional.empty());
  }

  /** 第一行是不是 {@code [portal]}（允许 TrainCarts 的红石前缀）。 */
  static boolean isPortalHeader(String line0) {
    if (line0 == null || line0.isBlank()) {
      return false;
    }
    SignActionHeader header = SignActionHeader.parse(line0.trim());
    return header != null && PORTAL_MODE.equalsIgnoreCase(header.getModeText());
  }

  public static NodeId nodeIdForRail(String worldName, RailBlockPos rail) {
    return NodeId.of(NODE_PREFIX + worldName + ":" + rail.x() + ":" + rail.y() + ":" + rail.z());
  }

  /** 是不是传送门节点的 ID。 */
  public static boolean isPortal(NodeId nodeId) {
    return nodeId != null && nodeId.value().startsWith(NODE_PREFIX);
  }

  /** 从传送门节点 ID 取回轨道坐标。 */
  public static Optional<RailBlockPos> tryParseRailPos(NodeId nodeId) {
    if (!isPortal(nodeId)) {
      return Optional.empty();
    }
    return lastThree(nodeId.value().split(":"));
  }

  /** 从 {@code tcDestination} 字段取回牌子方块坐标。 */
  public static Optional<RailBlockPos> signPos(Optional<String> marker) {
    if (marker == null || marker.isEmpty() || !marker.get().startsWith(SIGN_MARKER_PREFIX)) {
      return Optional.empty();
    }
    return lastThree(marker.get().substring(SIGN_MARKER_PREFIX.length()).split(","));
  }

  private static Optional<RailBlockPos> lastThree(String[] parts) {
    if (parts.length < 3) {
      return Optional.empty();
    }
    try {
      int n = parts.length;
      return Optional.of(
          new RailBlockPos(
              Integer.parseInt(parts[n - 3].trim()),
              Integer.parseInt(parts[n - 2].trim()),
              Integer.parseInt(parts[n - 1].trim())));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  private static String line(Sign sign, Side side) {
    try {
      return PLAIN_TEXT.serialize(sign.getSide(side).line(0));
    } catch (RuntimeException | LinkageError ex) {
      return side == Side.FRONT ? sign.getLine(0) : "";
    }
  }
}
