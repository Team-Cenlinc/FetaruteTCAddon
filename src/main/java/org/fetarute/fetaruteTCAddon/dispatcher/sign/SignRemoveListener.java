package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.HangingSign;
import org.bukkit.block.data.type.WallHangingSign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/** 监听牌子拆除，发送提示并清理注册表。 */
public final class SignRemoveListener implements Listener {

  private static final PlainTextComponentSerializer PLAIN_TEXT =
      PlainTextComponentSerializer.plainText();
  private static final BlockFace[] HORIZONTAL_FACES =
      new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

  private final SignNodeRegistry registry;
  private final LocaleManager locale;
  private final Consumer<String> debugLogger;
  private final SignNodeStorageSynchronizer storageSync;

  public SignRemoveListener(
      SignNodeRegistry registry, LocaleManager locale, Consumer<String> debugLogger) {
    this(registry, locale, debugLogger, SignNodeStorageSynchronizer.noop());
  }

  public SignRemoveListener(
      SignNodeRegistry registry,
      LocaleManager locale,
      Consumer<String> debugLogger,
      SignNodeStorageSynchronizer storageSync) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.locale = Objects.requireNonNull(locale, "locale");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
    this.storageSync = storageSync != null ? storageSync : SignNodeStorageSynchronizer.noop();
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBlockBreak(BlockBreakEvent event) {
    if (event.isCancelled()) {
      return;
    }

    // 优先清理“玩家直接拆除的方块”：这是最可靠的“是否是我们的节点牌子”的判断。
    if (cleanupNodeSign(event.getBlock(), event.getPlayer(), "玩家拆除节点牌子")) {
      return;
    }

    // 玩家可能拆除的是“牌子所依附的方块”，导致牌子因物理更新掉落，此时不会触发 SignAction#destroy / BlockBreakEvent(sign)。
    for (Block dependentSign : findDependentSigns(event.getBlock())) {
      cleanupNodeSign(dependentSign, event.getPlayer(), "玩家拆除节点牌子(依附方块)");
    }
  }

  private boolean cleanupNodeSign(Block block, org.bukkit.entity.Player player, String logPrefix) {
    var removedOpt = registry.remove(block);
    if (removedOpt.isPresent()) {
      SignNodeDefinition definition = removedOpt.get();
      storageSync.delete(block, definition);
      if (player != null) {
        player.sendMessage(
            locale.component(
                "sign.removed",
                Map.of(
                    "node", definition.nodeId().value(), "type", localizedTypeName(definition))));
      }
      debugLogger.accept(
          logPrefix
              + ": player="
              + (player != null ? player.getName() : "unknown")
              + " node="
              + definition.nodeId().value()
              + " type="
              + definition.nodeType()
              + " @ "
              + block.getLocation());
      return true;
    }

    // 兜底：若 destroy() 已提前移除 registry，这里仍尝试从牌子文本解析并清理存储与提示玩家。
    var definitionOpt = parseFromSignState(block);
    if (definitionOpt.isEmpty()) {
      return false;
    }
    SignNodeDefinition definition = definitionOpt.get();
    storageSync.delete(block, definition);
    if (player != null) {
      player.sendMessage(
          locale.component(
              "sign.removed",
              Map.of("node", definition.nodeId().value(), "type", localizedTypeName(definition))));
    }
    debugLogger.accept(
        logPrefix
            + "(解析回退): player="
            + (player != null ? player.getName() : "unknown")
            + " node="
            + definition.nodeId().value()
            + " type="
            + definition.nodeType()
            + " @ "
            + block.getLocation());
    return true;
  }

  private java.util.Set<Block> findDependentSigns(Block brokenBlock) {
    if (brokenBlock == null) {
      return java.util.Set.of();
    }

    java.util.Set<Block> dependents = new java.util.LinkedHashSet<>();

    // 立式牌子（standing sign）依附于下方方块。
    Block above = brokenBlock.getRelative(BlockFace.UP);
    BlockData aboveData = above.getBlockData();
    if (aboveData instanceof org.bukkit.block.data.type.Sign
        && brokenBlock.equals(above.getRelative(BlockFace.DOWN))) {
      dependents.add(above);
    }

    // 挂牌（hanging sign）依附于上方方块。
    Block below = brokenBlock.getRelative(BlockFace.DOWN);
    BlockData belowData = below.getBlockData();
    if (belowData instanceof HangingSign && brokenBlock.equals(below.getRelative(BlockFace.UP))) {
      dependents.add(below);
    }

    // 墙牌/墙挂牌依附于其 facing 的反方向方块。
    for (BlockFace face : HORIZONTAL_FACES) {
      Block candidate = brokenBlock.getRelative(face);
      BlockData data = candidate.getBlockData();
      if (data instanceof WallSign wallSign) {
        BlockFace attachedFace = wallSign.getFacing().getOppositeFace();
        if (brokenBlock.equals(candidate.getRelative(attachedFace))) {
          dependents.add(candidate);
        }
        continue;
      }
      if (data instanceof WallHangingSign wallHangingSign) {
        BlockFace attachedFace = wallHangingSign.getFacing().getOppositeFace();
        if (brokenBlock.equals(candidate.getRelative(attachedFace))) {
          dependents.add(candidate);
        }
      }
    }

    return java.util.Set.copyOf(dependents);
  }

  private java.util.Optional<SignNodeDefinition> parseFromSignState(Block block) {
    BlockState state = block.getState();
    if (!(state instanceof Sign sign)) {
      return java.util.Optional.empty();
    }
    SignSide front = sign.getSide(Side.FRONT);
    String header = PLAIN_TEXT.serialize(front.line(1)).trim().toLowerCase(java.util.Locale.ROOT);
    NodeType nodeType;
    java.util.EnumSet<org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind> expectedKinds;
    switch (header) {
      case "waypoint" -> {
        nodeType = NodeType.WAYPOINT;
        expectedKinds =
            java.util.EnumSet.of(
                org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind.INTERVAL,
                org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind.STATION_THROAT,
                org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind.DEPOT_THROAT);
      }
      case "autostation" -> {
        nodeType = NodeType.STATION;
        expectedKinds =
            java.util.EnumSet.of(org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind.STATION);
      }
      case "depot" -> {
        nodeType = NodeType.DEPOT;
        expectedKinds =
            java.util.EnumSet.of(org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind.DEPOT);
      }
      default -> {
        return java.util.Optional.empty();
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

  private String localizedTypeName(SignNodeDefinition definition) {
    if (definition == null) {
      return "";
    }
    WaypointKind kind = definition.waypointMetadata().map(metadata -> metadata.kind()).orElse(null);
    String key =
        kind == WaypointKind.STATION_THROAT
            ? "sign.type.station_throat"
            : kind == WaypointKind.DEPOT_THROAT
                ? "sign.type.depot_throat"
                : kind == WaypointKind.STATION
                    ? "sign.type.station"
                    : kind == WaypointKind.DEPOT
                        ? "sign.type.depot"
                        : "sign.type."
                            + definition.nodeType().name().toLowerCase(java.util.Locale.ROOT);
    return PLAIN_TEXT.serialize(locale.component(key));
  }
}
