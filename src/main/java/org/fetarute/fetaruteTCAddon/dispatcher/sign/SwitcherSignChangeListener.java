package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import com.bergerkiller.bukkit.tc.SignActionHeader;
import com.bergerkiller.bukkit.tc.controller.components.RailPiece;
import com.bergerkiller.bukkit.tc.rails.RailLookup;
import com.bergerkiller.bukkit.tc.rails.RailLookup.TrackedSign;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;

/**
 * 道岔牌子改写成写条件或 tag 的道岔时，删掉库里它原来对应的道岔节点。
 *
 * <p>道岔节点只在建图时写进库，增量同步只管节点牌子；不在这里删，快速刷新与增补会一直从库里读到这个已经不进图的节点。 删除经 {@link
 * SignNodeStorageSynchronizer}，图失效的判定与提示照常。
 */
public final class SwitcherSignChangeListener implements Listener {

  private static final PlainTextComponentSerializer PLAIN_TEXT =
      PlainTextComponentSerializer.plainText();

  private final SignNodeStorageSynchronizer storageSync;
  private final Consumer<String> debugLogger;

  /**
   * @param storageSync 删除节点并重新核对图签名的增量同步器
   * @param debugLogger 可选调试输出
   */
  public SwitcherSignChangeListener(
      SignNodeStorageSynchronizer storageSync, Consumer<String> debugLogger) {
    this.storageSync = Objects.requireNonNull(storageSync, "storageSync");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
  }

  /**
   * 在改牌被允许后，比对待写入文本与方块上尚未改写的旧文本。
   *
   * <p>只处理寻路道岔改为条件道岔的转换；新建条件牌子不能代表同轨道上已有寻路牌子失效。
   */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onSignChange(SignChangeEvent event) {
    if (event.isCancelled()) {
      return;
    }
    SwitcherSignDefinitionParser.Kind kind =
        SwitcherSignDefinitionParser.kindOf(
            SignActionHeader.parse(line(event, 0).trim()),
            line(event, 1),
            line(event, 2),
            line(event, 3));
    if (kind != SwitcherSignDefinitionParser.Kind.CONDITIONAL) {
      return;
    }
    Block block = event.getBlock();
    // 新建条件牌子、或改写已有条件牌子，都不能删除同轨道上别的牌子建立的节点。
    if (!(block.getState() instanceof Sign sign)
        || SwitcherSignDefinitionParser.kindOf(sign, event.getSide())
            != SwitcherSignDefinitionParser.Kind.PATHFINDING) {
      return;
    }
    if (otherSideIsPathfinding(sign, event.getSide())) {
      return;
    }
    // 与建图口径一致：道岔节点 ID 取 TrainCarts 把这块牌子挂上的那段轨道。
    Optional<RailPiece> attached = attachedRail(block);
    if (attached.isEmpty()) {
      return;
    }
    RailPiece piece = attached.get();
    if (otherPathfindingSwitcher(piece, block)) {
      // 这段轨道上还有别的寻路道岔牌子，道岔节点照旧有效。
      return;
    }
    Block rail = piece.block();
    NodeId nodeId =
        SwitcherSignDefinitionParser.nodeIdForRail(
            rail.getWorld().getName(), new RailBlockPos(rail.getX(), rail.getY(), rail.getZ()));
    storageSync.delete(rail, SwitcherSignDefinitionParser.definitionOf(nodeId));
    debugLogger.accept(
        "道岔牌子改为条件或 tag 道岔，移除库中道岔节点: node=" + nodeId.value() + " @ " + block.getLocation());
  }

  /** 无法确定牌子关联轨道时保留节点，避免按附近轨道猜测并误删。 */
  private static Optional<RailPiece> attachedRail(Block signBlock) {
    try {
      RailPiece piece = RailLookup.discoverRailPieceFromSign(signBlock);
      return piece == null || piece.isNone() || piece.block() == null
          ? Optional.empty()
          : Optional.of(piece);
    } catch (RuntimeException | LinkageError ex) {
      return Optional.empty();
    }
  }

  /** 同一段轨道上除这块牌子之外，还有没有寻路道岔牌子。 */
  private static boolean otherPathfindingSwitcher(RailPiece piece, Block editedSign) {
    // 编辑事件发生时 TC 的缓存尚未刷新，必须重新发现牌子。
    TrackedSign[] signs = RailLookup.discoverSignsAtRailPiece(piece);
    if (signs == null) {
      return true;
    }
    for (TrackedSign sign : signs) {
      if (sign == null) {
        return true;
      }
      if (editedSign.equals(sign.signBlock)) {
        continue;
      }
      Optional<SwitcherSignDefinitionParser.Kind> kind =
          SwitcherSignDefinitionParser.currentKindOf(sign);
      if (kind.isEmpty() || kind.get() == SwitcherSignDefinitionParser.Kind.PATHFINDING) {
        return true;
      }
    }
    return false;
  }

  /** 牌子另一面仍是寻路道岔时，道岔节点照旧有效。 */
  private static boolean otherSideIsPathfinding(Sign sign, Side edited) {
    Side other = edited == Side.BACK ? Side.FRONT : Side.BACK;
    return SwitcherSignDefinitionParser.kindOf(sign, other)
        == SwitcherSignDefinitionParser.Kind.PATHFINDING;
  }

  private static String line(SignChangeEvent event, int index) {
    Component component = event.line(index);
    return component == null ? "" : PLAIN_TEXT.serialize(component);
  }
}
