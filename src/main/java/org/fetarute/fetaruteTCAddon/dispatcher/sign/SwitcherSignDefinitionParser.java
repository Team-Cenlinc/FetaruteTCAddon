package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import java.util.Optional;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.TrainCartsRailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;

/**
 * 从 TrainCarts 的 switcher/tag 牌子解析出一个 Switcher 节点定义。
 *
 * <p>注意：Switcher 牌子由 TrainCarts/TCCoasters 提供，并非本插件的自定义 SignAction。 因此这里只做“识别与纳入
 * RailGraph”的最小解析：NodeId 由“被控制的轨道方块”的世界名 + 坐标组成，保证在同一世界内唯一且稳定。
 */
public final class SwitcherSignDefinitionParser {

  private static final PlainTextComponentSerializer PLAIN_TEXT =
      PlainTextComponentSerializer.plainText();

  /**
   * 用于标记“该 Switcher 节点来自 switcher/tag 牌子”。
   *
   * <p>注意：Switcher 节点本身不用于 TrainCarts destination，因此复用 tcDestination 字段作为内部标记，便于诊断“道岔但缺少 switcher
   * 牌子”的运维提示。
   */
  public static final String SWITCHER_SIGN_MARKER = "@switcher_sign";

  private SwitcherSignDefinitionParser() {}

  /**
   * 解析 TC 的 switcher/tag 牌子。
   *
   * <p>注意：Node 的坐标会写成“被控制的轨道方块坐标”（而不是牌子方块坐标），用于与轨道分叉/junction 检测对齐。
   */
  public static Optional<SignNodeDefinition> parse(Sign sign) {
    if (sign == null) {
      return Optional.empty();
    }
    SignSide front = sign.getSide(Side.FRONT);
    String header = PLAIN_TEXT.serialize(front.line(1)).trim().toLowerCase(java.util.Locale.ROOT);
    if (!header.equals("switcher") && !header.equals("tag")) {
      return Optional.empty();
    }

    String worldName = sign.getWorld().getName();
    TrainCartsRailBlockAccess access = new TrainCartsRailBlockAccess(sign.getWorld());
    RailBlockPos center =
        new RailBlockPos(
            sign.getLocation().getBlockX(),
            sign.getLocation().getBlockY(),
            sign.getLocation().getBlockZ());
    Optional<RailBlockPos> anchor = access.findNearestRailBlocks(center, 2).stream().findFirst();
    if (anchor.isEmpty()) {
      return Optional.empty();
    }
    NodeId nodeId = nodeIdForRail(worldName, anchor.get());
    return Optional.of(
        new SignNodeDefinition(
            nodeId, NodeType.SWITCHER, Optional.of(SWITCHER_SIGN_MARKER), Optional.empty()));
  }

  public static NodeId nodeIdForRail(String worldName, RailBlockPos rail) {
    return NodeId.of("SWITCHER:" + worldName + ":" + rail.x() + ":" + rail.y() + ":" + rail.z());
  }

  /** 从 {@link #nodeIdForRail(String, RailBlockPos)} 生成的 NodeId 中回解析出轨道坐标（用于把旧记录对齐到 rail block）。 */
  public static Optional<RailBlockPos> tryParseRailPos(NodeId nodeId) {
    if (nodeId == null) {
      return Optional.empty();
    }
    String value = nodeId.value();
    if (!value.startsWith("SWITCHER:")) {
      return Optional.empty();
    }
    String[] parts = value.split(":");
    if (parts.length < 5) {
      return Optional.empty();
    }
    try {
      int x = Integer.parseInt(parts[parts.length - 3]);
      int y = Integer.parseInt(parts[parts.length - 2]);
      int z = Integer.parseInt(parts[parts.length - 1]);
      return Optional.of(new RailBlockPos(x, y, z));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }
}
