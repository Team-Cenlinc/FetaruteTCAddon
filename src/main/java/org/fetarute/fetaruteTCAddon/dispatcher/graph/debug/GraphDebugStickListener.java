package org.fetarute.fetaruteTCAddon.dispatcher.graph.debug;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.EdgeOverrideRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.RailBlockPos;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.explore.TrainCartsRailBlockAccess;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPath;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailGraphPathFinder;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.NodeSignDefinitionParser;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SwitcherSignDefinitionParser;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 调度图诊断工具：debug 棍。
 *
 * <p>交互：
 *
 * <ul>
 *   <li>左键牌子/牌子对应轨道：两次选点，输出 edge 或最短路 path 摘要
 *   <li>潜行左键：输出该节点的基本信息（不进入两点选择流程）
 * </ul>
 *
 * <p>若点击轨道但附近无法解析到节点牌子，会输出“无牌子”提示。
 */
public final class GraphDebugStickListener implements Listener {

  private static final int RAIL_SIGN_SCAN_RADIUS = 2;

  private final FetaruteTCAddon plugin;
  private final SignNodeRegistry registry;
  private final RailGraphService railGraphService;
  private final LocaleManager locale;
  private final Consumer<String> debugLogger;
  private final NamespacedKey debugStickKey;
  private final NamespacedKey selectedNodeKey;
  private final NamespacedKey selectedWorldKey;

  public GraphDebugStickListener(
      FetaruteTCAddon plugin,
      SignNodeRegistry registry,
      RailGraphService railGraphService,
      LocaleManager locale,
      Consumer<String> debugLogger) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.railGraphService = Objects.requireNonNull(railGraphService, "railGraphService");
    this.locale = Objects.requireNonNull(locale, "locale");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
    this.debugStickKey = new NamespacedKey(plugin, "graph_debug_stick");
    this.selectedNodeKey = new NamespacedKey(plugin, "graph_debug_stick_node");
    this.selectedWorldKey = new NamespacedKey(plugin, "graph_debug_stick_world");
  }

  /** 生成带 PDC 标记的 debug 棍物品。 */
  public ItemStack createDebugStickItem() {
    ItemStack stick = new ItemStack(org.bukkit.Material.STICK, 1);
    ItemMeta meta = stick.getItemMeta();
    if (meta == null) {
      return stick;
    }
    meta.displayName(locale.component("command.graph.debugstick.name"));
    meta.lore(
        List.of(
            locale.component("command.graph.debugstick.lore-1"),
            locale.component("command.graph.debugstick.lore-2"),
            locale.component("command.graph.debugstick.lore-3")));
    meta.getPersistentDataContainer().set(debugStickKey, PersistentDataType.BYTE, (byte) 1);
    stick.setItemMeta(meta);
    return stick;
  }

  @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
  public void onLeftClick(PlayerInteractEvent event) {
    if (event.getAction() != Action.LEFT_CLICK_BLOCK) {
      return;
    }
    if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
      return;
    }
    Player player = event.getPlayer();
    ItemStack item = player.getInventory().getItemInMainHand();
    if (!isDebugStick(item)) {
      return;
    }

    Block clicked = event.getClickedBlock();
    if (clicked == null) {
      return;
    }

    Optional<SignNodeDefinition> defOpt = resolveNodeFromBlock(clicked);
    if (defOpt.isEmpty()) {
      player.sendMessage(
          locale.component(
              "command.graph.debugstick.no-sign",
              Map.of(
                  "x",
                  String.valueOf(clicked.getX()),
                  "y",
                  String.valueOf(clicked.getY()),
                  "z",
                  String.valueOf(clicked.getZ()))));
      event.setCancelled(true);
      return;
    }

    NodeId nodeId = defOpt.get().nodeId();
    if (player.isSneaking()) {
      sendNodeInfo(player, nodeId);
      event.setCancelled(true);
      return;
    }

    PersistentDataContainer container = player.getPersistentDataContainer();
    String worldValue = clicked.getWorld().getUID().toString();
    String selectedWorld = container.get(selectedWorldKey, PersistentDataType.STRING);
    String selectedNode = container.get(selectedNodeKey, PersistentDataType.STRING);
    if (selectedWorld == null || selectedNode == null || !selectedWorld.equals(worldValue)) {
      container.set(selectedWorldKey, PersistentDataType.STRING, worldValue);
      container.set(selectedNodeKey, PersistentDataType.STRING, nodeId.value());
      player.sendMessage(
          locale.component(
              "command.graph.debugstick.select.first", Map.of("node", nodeId.value())));
      debugLogger.accept("debugstick 选点: player=" + player.getName() + " node=" + nodeId.value());
      event.setCancelled(true);
      return;
    }

    container.remove(selectedWorldKey);
    container.remove(selectedNodeKey);
    NodeId first = NodeId.of(selectedNode);
    sendEdgeOrPath(player, first, nodeId);
    event.setCancelled(true);
  }

  private boolean isDebugStick(ItemStack item) {
    if (item == null || item.getType() != org.bukkit.Material.STICK) {
      return false;
    }
    ItemMeta meta = item.getItemMeta();
    if (meta == null) {
      return false;
    }
    return meta.getPersistentDataContainer().has(debugStickKey, PersistentDataType.BYTE);
  }

  /** 解析“牌子或轨道”对应的节点定义。 */
  private Optional<SignNodeDefinition> resolveNodeFromBlock(Block clicked) {
    if (clicked == null) {
      return Optional.empty();
    }

    Optional<SignNodeDefinition> signOpt = resolveNodeFromSignBlock(clicked);
    if (signOpt.isPresent()) {
      return signOpt;
    }
    return resolveNodeFromRailBlock(clicked);
  }

  /** 优先通过 registry/牌子解析节点定义。 */
  private Optional<SignNodeDefinition> resolveNodeFromSignBlock(Block block) {
    if (block == null) {
      return Optional.empty();
    }
    return registry.get(block).or(() -> parseSignBlock(block));
  }

  /** 解析可见牌子（含 TrainCarts switcher 牌子）。 */
  private Optional<SignNodeDefinition> parseSignBlock(Block block) {
    if (block == null) {
      return Optional.empty();
    }
    BlockState state = block.getState();
    if (!(state instanceof Sign sign)) {
      return Optional.empty();
    }
    return NodeSignDefinitionParser.parse(sign).or(() -> SwitcherSignDefinitionParser.parse(sign));
  }

  /**
   * 从轨道方块反推其对应的节点牌子。
   *
   * <p>用于支持“点击轨道也能获取节点信息”的交互。
   */
  private Optional<SignNodeDefinition> resolveNodeFromRailBlock(Block railBlock) {
    if (railBlock == null) {
      return Optional.empty();
    }
    com.bergerkiller.bukkit.tc.controller.components.RailPiece piece =
        com.bergerkiller.bukkit.tc.controller.components.RailPiece.create(railBlock);
    if (piece == null || piece.isNone()) {
      return Optional.empty();
    }

    TrainCartsRailBlockAccess access = new TrainCartsRailBlockAccess(railBlock.getWorld());
    Block canonical = piece.block();
    RailBlockPos railPos = new RailBlockPos(canonical.getX(), canonical.getY(), canonical.getZ());
    if (!access.isRail(railPos)) {
      return Optional.empty();
    }

    List<Block> candidates = new ArrayList<>();
    for (int dx = -RAIL_SIGN_SCAN_RADIUS; dx <= RAIL_SIGN_SCAN_RADIUS; dx++) {
      for (int dy = -RAIL_SIGN_SCAN_RADIUS; dy <= RAIL_SIGN_SCAN_RADIUS; dy++) {
        for (int dz = -RAIL_SIGN_SCAN_RADIUS; dz <= RAIL_SIGN_SCAN_RADIUS; dz++) {
          Block candidate = railBlock.getRelative(dx, dy, dz);
          candidates.add(candidate);
        }
      }
    }

    SignNodeDefinition best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (Block candidate : candidates) {
      Optional<SignNodeDefinition> defOpt = resolveNodeFromSignBlock(candidate);
      if (defOpt.isEmpty()) {
        continue;
      }
      Set<RailBlockPos> anchors =
          access.findNearestRailBlocks(
              new RailBlockPos(candidate.getX(), candidate.getY(), candidate.getZ()), 2);
      if (!anchors.contains(railPos)) {
        continue;
      }
      int distance =
          Math.abs(candidate.getX() - railBlock.getX())
              + Math.abs(candidate.getY() - railBlock.getY())
              + Math.abs(candidate.getZ() - railBlock.getZ());
      if (best == null || distance < bestDistance) {
        best = defOpt.get();
        bestDistance = distance;
      }
    }
    return Optional.ofNullable(best);
  }

  private void sendNodeInfo(Player player, NodeId nodeId) {
    if (player == null || nodeId == null) {
      return;
    }
    World world = player.getWorld();
    Optional<RailGraphService.RailGraphSnapshot> snapshotOpt = railGraphService.getSnapshot(world);
    if (snapshotOpt.isEmpty()) {
      player.sendMessage(locale.component("command.graph.info.missing"));
      return;
    }
    RailGraph graph = snapshotOpt.get().graph();
    Optional<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodeOpt =
        graph.findNode(nodeId);
    if (nodeOpt.isEmpty()) {
      player.sendMessage(
          locale.component("command.graph.query.node-not-found", Map.of("node", nodeId.value())));
      return;
    }
    var node = nodeOpt.get();
    int edges = graph.edgesFrom(nodeId).size();
    String componentKey = railGraphService.componentKey(world.getUID(), nodeId).orElse("-");
    org.bukkit.util.Vector pos = node.worldPosition();
    player.sendMessage(
        locale.component(
            "command.graph.debugstick.node.header",
            Map.of(
                "node",
                nodeId.value(),
                "type",
                node.type().name(),
                "x",
                String.valueOf(pos.getBlockX()),
                "y",
                String.valueOf(pos.getBlockY()),
                "z",
                String.valueOf(pos.getBlockZ()),
                "edges",
                String.valueOf(edges),
                "component",
                componentKey)));
  }

  private void sendEdgeOrPath(Player player, NodeId a, NodeId b) {
    if (player == null || a == null || b == null) {
      return;
    }
    World world = player.getWorld();
    UUID worldId = world.getUID();
    Optional<RailGraphService.RailGraphSnapshot> snapshotOpt = railGraphService.getSnapshot(world);
    if (snapshotOpt.isEmpty()) {
      player.sendMessage(locale.component("command.graph.info.missing"));
      return;
    }
    Instant now = Instant.now();
    RailGraph graph =
        new EdgeOverrideRailGraph(
            snapshotOpt.get().graph(), railGraphService.edgeOverrides(worldId), now);

    if (graph.findNode(a).isEmpty()) {
      player.sendMessage(
          locale.component("command.graph.query.node-not-found", Map.of("node", a.value())));
      return;
    }
    if (graph.findNode(b).isEmpty()) {
      player.sendMessage(
          locale.component("command.graph.query.node-not-found", Map.of("node", b.value())));
      return;
    }

    RailEdge edge = findEdgeOrNull(graph, a, b);
    if (edge != null) {
      sendEdgeInfo(player, worldId, graph, edge, a, b, now);
      return;
    }

    RailGraphPathFinder finder = new RailGraphPathFinder();
    Optional<RailGraphPath> pathOpt =
        finder.shortestPath(graph, a, b, RailGraphPathFinder.Options.shortestDistance());
    if (pathOpt.isEmpty()) {
      player.sendMessage(
          locale.component(
              "command.graph.query.unreachable", Map.of("from", a.value(), "to", b.value())));
      return;
    }
    RailGraphPath path = pathOpt.get();
    double defaultSpeed = defaultSpeedBlocksPerSecond();
    Duration eta = estimateEta(path.totalLengthBlocks(), defaultSpeed);
    player.sendMessage(
        locale.component(
            "command.graph.path.header",
            Map.of(
                "from",
                a.value(),
                "to",
                b.value(),
                "hops",
                String.valueOf(path.nodes().size() - 1),
                "distance_blocks",
                String.valueOf(path.totalLengthBlocks()),
                "eta",
                formatEta(eta))));
  }

  private void sendEdgeInfo(
      Player player,
      UUID worldId,
      RailGraph graph,
      RailEdge edge,
      NodeId a,
      NodeId b,
      Instant now) {
    double defaultSpeed = defaultSpeedBlocksPerSecond();
    double effectiveSpeed =
        railGraphService.effectiveSpeedLimitBlocksPerSecond(worldId, edge, now, defaultSpeed);
    String effectiveText =
        org.fetarute
            .fetaruteTCAddon
            .dispatcher
            .graph
            .control
            .RailSpeed
            .ofBlocksPerSecond(effectiveSpeed)
            .formatWithAllUnits();
    boolean blocked = graph.isBlocked(edge.id());

    player.sendMessage(
        locale.component(
            "command.graph.edge.get.edge.header",
            Map.of(
                "a",
                a.value(),
                "b",
                b.value(),
                "len_blocks",
                String.valueOf(edge.lengthBlocks()),
                "blocked",
                blocked ? locale.text("command.common.yes") : locale.text("command.common.no"),
                "effective_speed",
                effectiveText)));
  }

  private RailEdge findEdgeOrNull(RailGraph graph, NodeId a, NodeId b) {
    if (graph == null || a == null || b == null) {
      return null;
    }
    for (RailEdge edge : graph.edgesFrom(a)) {
      if (edge == null) {
        continue;
      }
      if (Objects.equals(edge.from(), a) && Objects.equals(edge.to(), b)) {
        return edge;
      }
      if (Objects.equals(edge.from(), b) && Objects.equals(edge.to(), a)) {
        return edge;
      }
    }
    return null;
  }

  private double defaultSpeedBlocksPerSecond() {
    ConfigManager manager = plugin.getConfigManager();
    if (manager == null) {
      return ConfigManager.GraphSettings.defaults().defaultSpeedBlocksPerSecond();
    }
    ConfigManager.ConfigView current = manager.current();
    if (current == null || current.graphSettings() == null) {
      return ConfigManager.GraphSettings.defaults().defaultSpeedBlocksPerSecond();
    }
    return current.graphSettings().defaultSpeedBlocksPerSecond();
  }

  private Duration estimateEta(long distanceBlocks, double speedBlocksPerSecond) {
    if (distanceBlocks <= 0) {
      return Duration.ZERO;
    }
    if (!Double.isFinite(speedBlocksPerSecond) || speedBlocksPerSecond <= 0.0) {
      return Duration.ZERO;
    }
    double seconds = distanceBlocks / speedBlocksPerSecond;
    long millis = (long) Math.round(seconds * 1000.0);
    if (millis < 0) {
      return Duration.ZERO;
    }
    return Duration.ofMillis(millis);
  }

  private String formatEta(Duration duration) {
    if (duration == null) {
      return "-";
    }
    long seconds = Math.max(0L, duration.getSeconds());
    if (seconds < 60) {
      return seconds + "s";
    }
    long minutes = seconds / 60;
    long remain = seconds % 60;
    if (minutes < 60) {
      return remain == 0 ? (minutes + "m") : (minutes + "m " + remain + "s");
    }
    long hours = minutes / 60;
    long minRemain = minutes % 60;
    return minRemain == 0 ? (hours + "h") : (hours + "h " + minRemain + "m");
  }
}
