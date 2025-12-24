package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointKind;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * Route Editor 交互增强：手持“运行图编辑书（书与笔）”右键节点牌子时，把 nodeId 追加到书的末尾。
 *
 * <p>这让玩家可以用“世界中的牌子”作为可视化选择器，避免手动输入长 nodeId 或复制粘贴。
 */
public final class RouteEditorAppendListener implements Listener {

  private static final PlainTextComponentSerializer PLAIN_TEXT =
      PlainTextComponentSerializer.plainText();
  private static final int MAX_PAGE_CHARS = 240;

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
    Objects.requireNonNull(plugin, "plugin");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.locale = Objects.requireNonNull(locale, "locale");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
    this.bookEditorMarkerKey = new NamespacedKey(plugin, "route_editor_marker");
    this.bookRouteIdKey = new NamespacedKey(plugin, "route_editor_route_id");
  }

  @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
  public void onRightClickSign(PlayerInteractEvent event) {
    if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
      return;
    }
    if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
      return;
    }
    Block clicked = event.getClickedBlock();
    if (clicked == null) {
      return;
    }

    Player player = event.getPlayer();
    ItemStack item = player.getInventory().getItemInMainHand();
    if (!isWritableRouteEditorBook(item)) {
      return;
    }

    Optional<SignNodeDefinition> defOpt = registry.get(clicked).or(() -> parseNodeSign(clicked));
    if (defOpt.isEmpty()) {
      return;
    }
    SignNodeDefinition definition = defOpt.get();
    String node = definition.trainCartsDestination().orElse(definition.nodeId().value());
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
    WaypointKind expectedKind;
    switch (header) {
      case "waypoint" -> {
        nodeType = NodeType.WAYPOINT;
        expectedKind = WaypointKind.INTERVAL;
      }
      case "autostation" -> {
        nodeType = NodeType.STATION;
        expectedKind = WaypointKind.STATION_THROAT;
      }
      case "depot" -> {
        nodeType = NodeType.DEPOT;
        expectedKind = WaypointKind.DEPOT;
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
                    .map(metadata -> metadata.kind() == expectedKind)
                    .orElse(false));
  }

  private String localizedType(SignNodeDefinition definition) {
    if (definition == null) {
      return "";
    }
    WaypointKind kind = definition.waypointMetadata().map(metadata -> metadata.kind()).orElse(null);
    String key =
        kind == WaypointKind.STATION_THROAT
            ? "sign.type.station_throat"
            : kind == WaypointKind.DEPOT
                ? "sign.type.depot_throat"
                : "sign.type." + definition.nodeType().name().toLowerCase(java.util.Locale.ROOT);
    return PLAIN_TEXT.serialize(locale.component(key));
  }
}
