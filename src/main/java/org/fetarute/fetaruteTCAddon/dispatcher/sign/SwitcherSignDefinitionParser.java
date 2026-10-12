package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import com.bergerkiller.bukkit.tc.SignActionHeader;
import com.bergerkiller.bukkit.tc.controller.components.RailPiece;
import com.bergerkiller.bukkit.tc.rails.RailLookup.TrackedRealSign;
import com.bergerkiller.bukkit.tc.rails.RailLookup.TrackedSign;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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

  /** TC 牌子按道岔用途分类。 */
  public enum Kind {
    /** 不是 switcher 牌子。 */
    NONE,
    /** 寻路道岔：第三、四行都空着，按列车 destination 寻路切换；进调度图。 */
    PATHFINDING,
    /** 写了条件或 tag 的道岔：不进调度图，探索时当作没有。 */
    CONDITIONAL
  }

  /**
   * 按牌子文字分类。
   *
   * @param header 第一行解析出的牌头；{@code [train]} 或 {@code [cart]} 之外的都不算
   * @param action 第二行
   * @param line3 第三行，非空表示条件或标签用途
   * @param line4 第四行，非空表示条件或标签用途
   */
  public static Kind kindOf(SignActionHeader header, String action, String line3, String line4) {
    if (header == null || (!header.isTrain() && !header.isCart())) {
      return Kind.NONE;
    }
    if (action == null || !action.trim().equalsIgnoreCase("switcher")) {
      return Kind.NONE;
    }
    boolean blank3 = line3 == null || line3.trim().isEmpty();
    boolean blank4 = line4 == null || line4.trim().isEmpty();
    return blank3 && blank4 ? Kind.PATHFINDING : Kind.CONDITIONAL;
  }

  /**
   * 一段轨道上的道岔牌子是否只剩写了条件或 tag 的：登记在这段轨道上的道岔节点已经作废。
   *
   * @param kinds 这段轨道上各块牌子的分类
   * @return 有寻路道岔、或一块道岔牌子都没有（判不了）时为 {@code false}
   */
  public static boolean onlyConditional(Iterable<Kind> kinds) {
    boolean conditional = false;
    for (Kind kind : kinds) {
      if (kind == Kind.PATHFINDING) {
        return false;
      }
      conditional |= kind == Kind.CONDITIONAL;
    }
    return conditional;
  }

  /** 按牌子文字分类（TrainCarts 跟踪的牌子，含 TCCoasters 的虚拟牌子）。 */
  public static Kind kindOf(TrackedSign trackedSign) {
    if (trackedSign == null) {
      return Kind.NONE;
    }
    return kindOf(
        trackedSign.getHeader(),
        safeLine(trackedSign, 1),
        safeLine(trackedSign, 2),
        safeLine(trackedSign, 3));
  }

  /**
   * 读取关联牌子当前的用途，供会删除节点的改牌监听与剪枝使用。
   *
   * <p>实体牌子直接读取自身方块及对应牌面，绕过同一 tick 内仍可能过期的 TC 文本跟踪器；虚拟牌子使用 TrackedSign 的文本接口。
   * 区块未加载或读取失败时返回空值，调用方必须保留节点，不能把无法确认当作没有寻路牌子。
   */
  public static Optional<Kind> currentKindOf(TrackedSign trackedSign) {
    if (trackedSign == null) {
      return Optional.empty();
    }
    try {
      if (!(trackedSign instanceof TrackedRealSign real)) {
        return Optional.of(kindOf(trackedSign));
      }
      Block block = real.signBlock;
      if (block == null || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
        return Optional.empty();
      }
      if (!(block.getState() instanceof Sign sign)) {
        return Optional.of(Kind.NONE);
      }
      return Optional.of(kindOf(sign, real.isFrontText() ? Side.FRONT : Side.BACK));
    } catch (RuntimeException | LinkageError ex) {
      return Optional.empty();
    }
  }

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
    // 只接受寻路道岔：TC 的 tag 牌子、写了条件或 tag 的 switcher 都不代表调度图的道岔节点。
    if (kindOf(sign, side) != Kind.PATHFINDING) {
      return Optional.empty();
    }
    return nodeIdNearSign(sign.getBlock()).map(SwitcherSignDefinitionParser::definitionOf);
  }

  /**
   * 牌子方块对应的道岔节点 ID：取牌子附近最近的轨道（等距时按坐标确定性选一段）。
   *
   * @return 附近没有轨道时为空
   */
  static Optional<NodeId> nodeIdNearSign(Block signBlock) {
    TrainCartsRailBlockAccess access = new TrainCartsRailBlockAccess(signBlock.getWorld());
    RailBlockPos center = new RailBlockPos(signBlock.getX(), signBlock.getY(), signBlock.getZ());
    Set<RailBlockPos> anchors = access.findNearestRailBlocks(center, 2);
    if (anchors.isEmpty()) {
      return Optional.empty();
    }
    // findNearestRailBlocks 可能返回多个“等距离”的轨道锚点；若直接 findFirst() 会因 Set 的迭代顺序不稳定
    // 导致 NodeId 漂移，进而引起图签名抖动/快照重复写入等问题。因此这里必须做确定性选择。
    RailBlockPos anchor = selectDeterministicAnchor(anchors);
    return Optional.of(nodeIdForRail(signBlock.getWorld().getName(), anchor));
  }

  /** 道岔节点定义。 */
  public static SignNodeDefinition definitionOf(NodeId nodeId) {
    return new SignNodeDefinition(
        nodeId, NodeType.SWITCHER, Optional.of(SWITCHER_SIGN_MARKER), Optional.empty());
  }

  /** 实体牌子某一面按道岔用途的分类。 */
  static Kind kindOf(Sign sign, Side side) {
    return kindOf(
        SignActionHeader.parse(safeLine(sign, side, 0).trim()),
        safeLine(sign, side, 1),
        safeLine(sign, side, 2),
        safeLine(sign, side, 3));
  }

  /**
   * 解析 TrainCarts 的 {@link TrackedSign}（包含 TCCoasters 的 TrackNodeSign 虚拟牌子）。
   *
   * <p>Virtual sign 没有 Bukkit {@link Sign} 实例，因此必须读取 {@link TrackedSign#getLine(int)} 与轨道方块
   * 关联信息来确定其坐标。
   */
  public static Optional<SignNodeDefinition> parse(TrackedSign trackedSign) {
    if (trackedSign == null) {
      return Optional.empty();
    }
    if (kindOf(trackedSign) != Kind.PATHFINDING) {
      return Optional.empty();
    }

    Block railBlock = resolveRailBlock(trackedSign);
    if (railBlock == null) {
      return Optional.empty();
    }

    String worldName = railBlock.getWorld().getName();
    RailBlockPos railPos = new RailBlockPos(railBlock.getX(), railBlock.getY(), railBlock.getZ());
    return Optional.of(definitionOf(nodeIdForRail(worldName, railPos)));
  }

  static RailBlockPos selectDeterministicAnchor(Set<RailBlockPos> anchors) {
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

  /**
   * 安全读取牌子文本行，兼容 TrainCarts FakeSign 的旧 API。
   *
   * <p>若 SignSide#line 不可用则回退到旧的 Sign#getLine（仅正面）。
   */
  private static String safeLine(Sign sign, Side side, int index) {
    if (sign == null) {
      return "";
    }
    try {
      SignSide view = sign.getSide(side);
      return PLAIN_TEXT.serialize(view.line(index));
    } catch (AbstractMethodError | NoSuchMethodError ex) {
      // 兼容 TrainCarts 的 FakeSign 仍使用旧 Sign API。
      if (side != Side.FRONT) {
        return "";
      }
      return safeLegacyLine(sign, index);
    } catch (IndexOutOfBoundsException ex) {
      return "";
    }
  }

  /** 旧版 Sign API 的安全读取。 */
  private static String safeLegacyLine(Sign sign, int index) {
    return readLegacyLine(sign, index);
  }

  private static String readLegacyLine(Sign sign, int index) {
    if (sign == null) {
      return "";
    }
    try {
      Method method = sign.getClass().getMethod("getLine", int.class);
      Object value = method.invoke(sign, index);
      return value == null ? "" : value.toString();
    } catch (NoSuchMethodException | IllegalAccessException ex) {
      return "";
    } catch (InvocationTargetException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof IndexOutOfBoundsException) {
        return "";
      }
      return "";
    }
  }

  /** 牌子关联的轨道方块（供传送门牌子解析复用）。 */
  static Block railBlockOf(TrackedSign trackedSign) {
    return resolveRailBlock(trackedSign);
  }

  private static Block resolveRailBlock(TrackedSign trackedSign) {
    if (trackedSign == null) {
      return null;
    }
    RailPiece piece = trackedSign.getRail();
    if (piece != null) {
      return piece.block();
    }
    try {
      Field field = TrackedSign.class.getDeclaredField("railBlock");
      field.setAccessible(true);
      Object value = field.get(trackedSign);
      if (value instanceof Block block) {
        return block;
      }
    } catch (NoSuchFieldException | IllegalAccessException ex) {
      return null;
    }
    return null;
  }
}
