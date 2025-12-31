package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import com.bergerkiller.bukkit.tc.SignActionHeader;
import com.bergerkiller.bukkit.tc.controller.components.RailPiece;
import com.bergerkiller.bukkit.tc.rails.RailLookup.TrackedSign;
import java.util.Optional;
import java.util.Set;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.TrainCartsRailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;

/**
 * 从 TrainCarts 的 switcher 牌子解析出一个 Switcher 节点定义。
 *
 * <p>注意：Switcher 牌子由 TrainCarts/TCCoasters 提供，并非本插件的自定义 SignAction。 因此这里只做“识别与纳入
 * RailGraph”的最小解析：NodeId 由“被控制的轨道方块”的世界名 + 坐标组成，保证在同一世界内唯一且稳定。
 *
 * <p>重要：这里只识别 {@code [train] switcher}（或 {@code [cart] switcher}），不会把 {@code [train] tag} 等其他 TC
 * 牌子误认为 switcher。否则在常见线网中会产生大量“莫名其妙的 SWITCHER 节点”（noise nodes），干扰诊断与运维。
 */
public final class SwitcherSignDefinitionParser {

  private static final PlainTextComponentSerializer PLAIN_TEXT =
      PlainTextComponentSerializer.plainText();

  /**
   * 用于标记“该 Switcher 节点来自 switcher 牌子”。
   *
   * <p>注意：Switcher 节点本身不用于 TrainCarts destination，因此复用 tcDestination 字段作为内部标记，便于诊断“道岔但缺少 switcher
   * 牌子”的运维提示。
   */
  public static final String SWITCHER_SIGN_MARKER = "@switcher_sign";

  private SwitcherSignDefinitionParser() {}

  /**
   * 解析 TC 的 switcher 牌子。
   *
   * <p>注意：Node 的坐标会写成“被控制的轨道方块坐标”（而不是牌子方块坐标），用于与轨道分叉/junction 检测对齐。
   */
  public static Optional<SignNodeDefinition> parse(Sign sign) {
    if (sign == null) {
      return Optional.empty();
    }
    return parseFromSide(sign, Side.FRONT).or(() -> parseFromSide(sign, Side.BACK));
  }

  private static Optional<SignNodeDefinition> parseFromSide(Sign sign, Side side) {
    SignSide view = sign.getSide(side);
    String trainHeader = PLAIN_TEXT.serialize(view.line(0)).trim();
    SignActionHeader parsedHeader = SignActionHeader.parse(trainHeader);
    if (parsedHeader == null || (!parsedHeader.isTrain() && !parsedHeader.isCart())) {
      return Optional.empty();
    }
    String header = PLAIN_TEXT.serialize(view.line(1)).trim().toLowerCase(java.util.Locale.ROOT);
    // 只接受明确的 switcher 行为牌子：TC 的 tag 牌子通常用于标记/过滤，不代表“道岔节点”，不应纳入 RailGraph。
    if (!header.equals("switcher")) {
      return Optional.empty();
    }

    String worldName = sign.getWorld().getName();
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
    // findNearestRailBlocks 可能返回多个“等距离”的轨道锚点；若直接 findFirst() 会因 Set 的迭代顺序不稳定
    // 导致 NodeId 漂移，进而引起图签名抖动/快照重复写入等问题。因此这里必须做确定性选择。
    RailBlockPos anchor = selectDeterministicAnchor(anchors);
    NodeId nodeId = nodeIdForRail(worldName, anchor);
    return Optional.of(
        new SignNodeDefinition(
            nodeId, NodeType.SWITCHER, Optional.of(SWITCHER_SIGN_MARKER), Optional.empty()));
  }

  /**
   * 解析 TrainCarts 的 {@link TrackedSign}（包含 TCCoasters 的 TrackNodeSign 虚拟牌子）。
   *
   * <p>Virtual sign 没有 Bukkit {@link Sign} 实例，因此必须读取 {@link TrackedSign#getLine(int)} 与 {@link
   * TrackedSign#railBlock} 来确定其关联的轨道方块。
   */
  public static Optional<SignNodeDefinition> parse(TrackedSign trackedSign) {
    if (trackedSign == null) {
      return Optional.empty();
    }
    SignActionHeader header = trackedSign.getHeader();
    if (header == null || (!header.isTrain() && !header.isCart())) {
      return Optional.empty();
    }

    String action = safeLine(trackedSign, 1).trim().toLowerCase(java.util.Locale.ROOT);
    if (!action.equals("switcher")) {
      return Optional.empty();
    }

    Block railBlock = trackedSign.railBlock;
    if (railBlock == null) {
      RailPiece piece = trackedSign.getRail();
      if (piece != null) {
        railBlock = piece.block();
      }
    }
    if (railBlock == null) {
      return Optional.empty();
    }

    String worldName = railBlock.getWorld().getName();
    RailBlockPos railPos = new RailBlockPos(railBlock.getX(), railBlock.getY(), railBlock.getZ());
    NodeId nodeId = nodeIdForRail(worldName, railPos);
    return Optional.of(
        new SignNodeDefinition(
            nodeId, NodeType.SWITCHER, Optional.of(SWITCHER_SIGN_MARKER), Optional.empty()));
  }

  private static RailBlockPos selectDeterministicAnchor(Set<RailBlockPos> anchors) {
    RailBlockPos best = null;
    for (RailBlockPos candidate : anchors) {
      if (candidate == null) {
        continue;
      }
      if (best == null) {
        best = candidate;
        continue;
      }
      if (candidate.x() < best.x()
          || (candidate.x() == best.x() && candidate.y() < best.y())
          || (candidate.x() == best.x() && candidate.y() == best.y() && candidate.z() < best.z())) {
        best = candidate;
      }
    }
    return best != null ? best : anchors.iterator().next();
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

  private static String safeLine(TrackedSign trackedSign, int index) {
    try {
      String value = trackedSign.getLine(index);
      return value != null ? value : "";
    } catch (IndexOutOfBoundsException ex) {
      return "";
    }
  }
}
