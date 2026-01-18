package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.TrainCartsRailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * Route Editor 交互增强：手持“运行图编辑书（书与笔）”右键节点牌子时，把 nodeId 追加到书的末尾。
 *
 * <p>这让玩家可以用“世界中的牌子”作为可视化选择器，避免手动输入长 nodeId 或复制粘贴。
 *
 * <p>其中 Waypoint 牌子支持追加区间点与咽喉（站咽喉/车库咽喉），AutoStation/Depot 仅追加站点/车库本体。
 */
public final class RouteEditorAppendListener implements Listener {

  private static final PlainTextComponentSerializer PLAIN_TEXT =
      PlainTextComponentSerializer.plainText();
  private static final int MAX_PAGE_CHARS = 240;

  private final FetaruteTCAddon plugin;
  private final SignNodeRegistry registry;
  private final LocaleManager locale;
  private final Consumer<String> debugLogger;
  private final NamespacedKey bookEditorMarkerKey;
  private final NamespacedKey bookRouteIdKey;

  public RouteEditorAppendListener(
      FetaruteTCAddon plugin,
      SignNodeRegistry registry,
      LocaleManager locale,
      Consumer<String> debugLogger) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.locale = Objects.requireNonNull(locale, "locale");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
    this.bookEditorMarkerKey = new NamespacedKey(plugin, "route_editor_marker");
    this.bookRouteIdKey = new NamespacedKey(plugin, "route_editor_route_id");
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onRightClickSignOrRail(PlayerInteractEvent event) {
    if (event.getAction() != Action.RIGHT_CLICK_BLOCK
        && event.getAction() != Action.RIGHT_CLICK_AIR) {
      return;
    }
    if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
      return;
    }

    Player player = event.getPlayer();
    ItemStack item = player.getInventory().getItemInMainHand();
    if (!isWritableRouteEditorBook(item)) {
      return;
    }

    Block clicked = event.getClickedBlock();
    if (clicked == null) {
      RayTraceResult ray = player.rayTraceBlocks(6.0);
      if (ray != null) {
        clicked = ray.getHitBlock();
      }
    }
    if (clicked == null) {
      return;
    }

    List<Block> targets = List.of(clicked, clicked.getRelative(org.bukkit.block.BlockFace.UP));
    boolean shouldHandle = targets.stream().anyMatch(this::isSignOrRailLike);
    if (shouldHandle) {
      denyUse(event);
      closeBookLater(player);
    }

    Optional<SignNodeDefinition> defOpt = Optional.empty();
    for (Block target : targets) {
      if (target == null) {
        continue;
      }
      defOpt =
          registry
              .get(target)
              .or(() -> parseNodeSign(target))
              .or(() -> parseSwitcherSign(target))
              .or(() -> resolveNodeFromRail(target));
      if (defOpt.isPresent()) {
        break;
      }
    }
    if (defOpt.isEmpty()) {
      if (shouldHandle) {
        player.sendMessage(
            locale.component(
                "command.route.editor.append.no-sign",
                java.util.Map.of(
                    "x",
                    String.valueOf(clicked.getX()),
                    "y",
                    String.valueOf(clicked.getY()),
                    "z",
                    String.valueOf(clicked.getZ()))));
        event.setCancelled(true);
      }
      return;
    }
    SignNodeDefinition definition = defOpt.get();
    String node = nodeValueForEditor(definition);
    if (node.isBlank()) {
      return;
    }

    if (!(item.getItemMeta() instanceof BookMeta meta)) {
      return;
    }
    appendLine(meta, node.trim());
    item.setItemMeta(meta);

    player.sendMessage(
        locale.component(
            "command.route.editor.append.success",
            java.util.Map.of("node", node.trim(), "type", localizedType(definition))));
    debugLogger.accept("追加节点到运行图编辑书: player=" + player.getName() + " node=" + node.trim());
    event.setCancelled(true);
  }

  private boolean isWritableRouteEditorBook(ItemStack item) {
    if (item == null || item.getType() != Material.WRITABLE_BOOK) {
      return false;
    }
    if (!(item.getItemMeta() instanceof BookMeta meta)) {
      return false;
    }
    PersistentDataContainer container = meta.getPersistentDataContainer();
    return container.has(bookEditorMarkerKey, PersistentDataType.BYTE)
        || container.has(bookRouteIdKey, PersistentDataType.STRING);
  }

  private void appendLine(BookMeta meta, String line) {
    List<Component> pages = new ArrayList<>(meta.pages());
    if (pages.isEmpty()) {
      pages.add(Component.text(line));
      meta.pages(pages);
      return;
    }

    int lastIndex = pages.size() - 1;
    String lastText = PLAIN_TEXT.serialize(pages.get(lastIndex));
    String appended = lastText.isBlank() ? line : lastText + "\n" + line;
    if (appended.length() <= MAX_PAGE_CHARS) {
      pages.set(lastIndex, Component.text(appended));
    } else {
      pages.add(Component.text(line));
    }
    meta.pages(pages);
  }

  private Optional<SignNodeDefinition> parseNodeSign(Block block) {
    BlockState state = block.getState();
    if (!(state instanceof Sign sign)) {
      return Optional.empty();
    }
    SignSide front = sign.getSide(Side.FRONT);
    String header = PLAIN_TEXT.serialize(front.line(1)).trim().toLowerCase(java.util.Locale.ROOT);
    NodeType nodeType;
    java.util.EnumSet<WaypointKind> expectedKinds;
    switch (header) {
      case "waypoint" -> {
        nodeType = NodeType.WAYPOINT;
        expectedKinds =
            java.util.EnumSet.of(
                WaypointKind.INTERVAL, WaypointKind.STATION_THROAT, WaypointKind.DEPOT_THROAT);
      }
      case "autostation" -> {
        nodeType = NodeType.STATION;
        expectedKinds = java.util.EnumSet.of(WaypointKind.STATION);
      }
      case "depot" -> {
        nodeType = NodeType.DEPOT;
        expectedKinds = java.util.EnumSet.of(WaypointKind.DEPOT);
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

  private Optional<SignNodeDefinition> parseSwitcherSign(Block block) {
    if (block == null) {
      return Optional.empty();
    }
    BlockState state = block.getState();
    if (!(state instanceof Sign sign)) {
      return Optional.empty();
    }
    return SwitcherSignDefinitionParser.parse(sign);
  }

  /** 判定方块是否为 TrainCarts 可识别的轨道方块（含 TCC 轨道）。 */
  private boolean isRailLike(Block clicked) {
    if (clicked == null) {
      return false;
    }
    com.bergerkiller.bukkit.tc.controller.components.RailPiece piece =
        com.bergerkiller.bukkit.tc.controller.components.RailPiece.create(clicked);
    return piece != null && !piece.isNone();
  }

  /** 用于快速判断“是否需要拦截右键行为”。 */
  private boolean isSignOrRailLike(Block block) {
    if (block == null) {
      return false;
    }
    return block.getState() instanceof Sign || isRailLike(block);
  }

  /** 关闭与物品/方块交互相关的默认行为（避免书本打开编辑界面）。 */
  private void denyUse(PlayerInteractEvent event) {
    event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
    event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
    event.setCancelled(true);
  }

  /** 在下一 tick 强制关闭书本界面，用于兜底客户端已弹窗的情况。 */
  private void closeBookLater(Player player) {
    if (player == null) {
      return;
    }
    plugin.getServer().getScheduler().runTask(plugin, (Runnable) player::closeInventory);
  }

  /** 为 route editor 选择“可写入的 nodeId 文本”。 */
  private String nodeValueForEditor(SignNodeDefinition definition) {
    if (definition == null) {
      return "";
    }
    if (definition.nodeType() == NodeType.SWITCHER
        || definition
            .trainCartsDestination()
            .filter(SwitcherSignDefinitionParser.SWITCHER_SIGN_MARKER::equals)
            .isPresent()) {
      return definition.nodeId().value();
    }
    return definition.trainCartsDestination().orElse(definition.nodeId().value());
  }

  /**
   * 从轨道方块反推其对应的节点牌子。
   *
   * <p>策略：以轨道为中心在小范围内扫描牌子，并校验该牌子的“轨道锚点”是否包含该轨道。
   */
  private Optional<SignNodeDefinition> resolveNodeFromRail(Block clicked) {
    if (clicked == null) {
      return Optional.empty();
    }

    com.bergerkiller.bukkit.tc.controller.components.RailPiece piece =
        com.bergerkiller.bukkit.tc.controller.components.RailPiece.create(clicked);
    if (piece == null || piece.isNone()) {
      return Optional.empty();
    }
    TrainCartsRailBlockAccess access = new TrainCartsRailBlockAccess(clicked.getWorld());
    org.bukkit.block.Block railBlock = piece.block();
    RailBlockPos railPos = new RailBlockPos(railBlock.getX(), railBlock.getY(), railBlock.getZ());
    if (!access.isRail(railPos)) {
      return Optional.empty();
    }

    int radius = 2;
    SignNodeDefinition best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dy = -radius; dy <= radius; dy++) {
        for (int dz = -radius; dz <= radius; dz++) {
          Block candidate = clicked.getRelative(dx, dy, dz);
          Optional<SignNodeDefinition> defOpt =
              registry
                  .get(candidate)
                  .or(() -> parseNodeSign(candidate))
                  .or(() -> parseSwitcherSign(candidate));
          if (defOpt.isEmpty()) {
            continue;
          }
          SignNodeDefinition def = defOpt.get();
          Set<RailBlockPos> anchors =
              access.findNearestRailBlocks(
                  new RailBlockPos(candidate.getX(), candidate.getY(), candidate.getZ()), 2);
          if (!anchors.contains(railPos)) {
            continue;
          }
          int distance = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
          if (best == null || distance < bestDistance) {
            best = def;
            bestDistance = distance;
          }
        }
      }
    }
    return Optional.ofNullable(best);
  }

  private String localizedType(SignNodeDefinition definition) {
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
