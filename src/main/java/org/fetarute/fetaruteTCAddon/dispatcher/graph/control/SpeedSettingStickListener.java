package org.fetarute.fetaruteTCAddon.dispatcher.graph.control;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.command.CommandStorageProviders;
import org.fetarute.fetaruteTCAddon.command.CommandUx;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.SectionSpeedService.SectionSpeedChange;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.SectionSpeedService.SectionSpeedPlan;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interaction.GraphNodeClickResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 限速设置棍交互：两次左键选节点并按当前最短路径批量写入 section speed。
 *
 * <p>该道具是写入类工具，和 debug 棍分开标记。它只改变 edge speed override，不调整调度、占用、发车队列、ETA 或图构建流程。
 */
public final class SpeedSettingStickListener implements Listener {

  private final FetaruteTCAddon plugin;
  private final RailGraphService railGraphService;
  private final LocaleManager locale;
  private final GraphNodeClickResolver nodeClickResolver;
  private final SectionSpeedService sectionSpeedService;
  private final SectionSpeedOverrideWriter overrideWriter;
  private final Consumer<String> debugLogger;
  private final NamespacedKey stickKey;
  private final NamespacedKey speedKey;
  private final NamespacedKey ttlSecondsKey;
  private final NamespacedKey selectedNodeKey;
  private final NamespacedKey selectedWorldKey;

  public SpeedSettingStickListener(
      FetaruteTCAddon plugin,
      SignNodeRegistry registry,
      RailGraphService railGraphService,
      LocaleManager locale,
      Consumer<String> debugLogger) {
    this(
        plugin,
        registry,
        railGraphService,
        locale,
        new SectionSpeedService(),
        new SectionSpeedOverrideWriter(),
        debugLogger);
  }

  SpeedSettingStickListener(
      FetaruteTCAddon plugin,
      SignNodeRegistry registry,
      RailGraphService railGraphService,
      LocaleManager locale,
      SectionSpeedService sectionSpeedService,
      SectionSpeedOverrideWriter overrideWriter,
      Consumer<String> debugLogger) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.railGraphService = Objects.requireNonNull(railGraphService, "railGraphService");
    this.locale = Objects.requireNonNull(locale, "locale");
    this.nodeClickResolver =
        new GraphNodeClickResolver(plugin, Objects.requireNonNull(registry, "registry"));
    this.sectionSpeedService = Objects.requireNonNull(sectionSpeedService, "sectionSpeedService");
    this.overrideWriter = Objects.requireNonNull(overrideWriter, "overrideWriter");
    this.debugLogger = debugLogger != null ? debugLogger : message -> {};
    this.stickKey = new NamespacedKey(plugin, "speed_setting_stick");
    this.speedKey = new NamespacedKey(plugin, "speed_setting_stick_speed_bps");
    this.ttlSecondsKey = new NamespacedKey(plugin, "speed_setting_stick_ttl_seconds");
    this.selectedNodeKey = new NamespacedKey(plugin, "speed_setting_stick_node");
    this.selectedWorldKey = new NamespacedKey(plugin, "speed_setting_stick_world");
  }

  /** 生成携带速度与可选 TTL 的限速设置棍。 */
  public ItemStack createStickItem(SpeedSettingStickConfig config) {
    Objects.requireNonNull(config, "config");
    ItemStack stick = new ItemStack(Material.STICK, 1);
    ItemMeta meta = stick.getItemMeta();
    if (meta == null) {
      return stick;
    }
    Map<String, String> values = Map.of("speed", config.speedText(), "ttl", config.ttlText());
    meta.displayName(locale.component("command.speed.stick.name", values));
    meta.lore(
        List.of(
            locale.component("command.speed.stick.lore-1", values),
            locale.component("command.speed.stick.lore-2", values),
            locale.component("command.speed.stick.lore-3", values)));
    PersistentDataContainer container = meta.getPersistentDataContainer();
    container.set(stickKey, PersistentDataType.BYTE, (byte) 1);
    container.set(speedKey, PersistentDataType.DOUBLE, config.speed().blocksPerSecond());
    config
        .ttl()
        .ifPresent(ttl -> container.set(ttlSecondsKey, PersistentDataType.LONG, ttl.toSeconds()));
    stick.setItemMeta(meta);
    return stick;
  }

  @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
  public void onLeftClick(PlayerInteractEvent event) {
    if (event.getAction() != Action.LEFT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
      return;
    }
    Player player = event.getPlayer();
    ItemStack item = player.getInventory().getItemInMainHand();
    if (!isSpeedSettingStick(item)) {
      return;
    }
    event.setCancelled(true);
    if (!player.hasPermission("fetarute.speed.stick") && !player.hasPermission("fetarute.speed")) {
      player.sendMessage(locale.component("error.no-permission"));
      return;
    }

    Optional<SpeedSettingStickConfig> configOpt = readConfig(item);
    if (configOpt.isEmpty()) {
      player.sendMessage(locale.component("command.speed.stick.invalid"));
      return;
    }

    Block clicked = event.getClickedBlock();
    if (clicked == null) {
      return;
    }
    Optional<SignNodeDefinition> defOpt = nodeClickResolver.resolveNodeFromBlock(clicked);
    if (defOpt.isEmpty()) {
      player.sendMessage(
          locale.component(
              "command.speed.stick.no-sign",
              Map.of(
                  "x",
                  String.valueOf(clicked.getX()),
                  "y",
                  String.valueOf(clicked.getY()),
                  "z",
                  String.valueOf(clicked.getZ()))));
      return;
    }

    NodeId nodeId = defOpt.get().nodeId();
    PersistentDataContainer playerData = player.getPersistentDataContainer();
    String worldValue = clicked.getWorld().getUID().toString();
    String selectedWorld = playerData.get(selectedWorldKey, PersistentDataType.STRING);
    String selectedNode = playerData.get(selectedNodeKey, PersistentDataType.STRING);
    if (selectedWorld == null || selectedNode == null || !selectedWorld.equals(worldValue)) {
      playerData.set(selectedWorldKey, PersistentDataType.STRING, worldValue);
      playerData.set(selectedNodeKey, PersistentDataType.STRING, nodeId.value());
      player.sendMessage(
          locale.component(
              "command.speed.stick.select.first",
              Map.of(
                  "node",
                  nodeId.value(),
                  "speed",
                  configOpt.get().speedText(),
                  "ttl",
                  configOpt.get().ttlText())));
      debugLogger.accept("speed stick 选点: player=" + player.getName() + " node=" + nodeId.value());
      return;
    }

    playerData.remove(selectedWorldKey);
    playerData.remove(selectedNodeKey);
    applySection(player, clicked.getWorld(), NodeId.of(selectedNode), nodeId, configOpt.get());
  }

  @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
  public void onRightClick(PlayerInteractEvent event) {
    if ((event.getAction() != Action.RIGHT_CLICK_AIR
            && event.getAction() != Action.RIGHT_CLICK_BLOCK)
        || event.getHand() != EquipmentSlot.HAND) {
      return;
    }
    Player player = event.getPlayer();
    ItemStack item = player.getInventory().getItemInMainHand();
    if (!isSpeedSettingStick(item)) {
      return;
    }
    PersistentDataContainer playerData = player.getPersistentDataContainer();
    boolean hadSelection =
        playerData.has(selectedWorldKey, PersistentDataType.STRING)
            || playerData.has(selectedNodeKey, PersistentDataType.STRING);
    playerData.remove(selectedWorldKey);
    playerData.remove(selectedNodeKey);
    if (hadSelection) {
      player.sendMessage(locale.component("command.speed.stick.select.cancelled"));
      event.setCancelled(true);
    }
  }

  private void applySection(
      Player player, World world, NodeId from, NodeId to, SpeedSettingStickConfig config) {
    UUID worldId = world.getUID();
    Optional<RailGraphService.RailGraphSnapshot> snapshotOpt = railGraphService.getSnapshot(world);
    if (snapshotOpt.isEmpty()) {
      player.sendMessage(locale.component("command.graph.info.missing"));
      return;
    }
    RailGraph graph = snapshotOpt.get().graph();
    Optional<SectionSpeedPlan> planOpt = sectionSpeedService.plan(graph, from, to);
    if (planOpt.isEmpty() || planOpt.get().edgeIds().isEmpty()) {
      player.sendMessage(
          locale.component(
              "command.speed.section.unreachable", Map.of("from", from.value(), "to", to.value())));
      return;
    }
    Optional<StorageProvider> providerOpt = CommandStorageProviders.readyProvider(player, plugin);
    if (providerOpt.isEmpty()) {
      return;
    }

    Instant now = Instant.now();
    SectionSpeedPlan plan = planOpt.get();
    StorageProvider provider = providerOpt.get();
    SectionSpeedChange change =
        sectionSpeedService.buildSetChange(
            worldId,
            plan,
            config.speed(),
            config.tempUntil(now),
            overrideWriter.loadExisting(provider, railGraphService, worldId, plan.edgeIds()),
            now);
    try {
      overrideWriter.apply(provider, railGraphService, worldId, change);
    } catch (Exception ex) {
      player.sendMessage(
          locale.component(
              "command.speed.section.storage-failed",
              Map.of("error", ex.getMessage() != null ? ex.getMessage() : "")));
      return;
    }
    player.sendMessage(
        locale.component(
            config.ttl().isPresent()
                ? "command.speed.stick.apply.temp-success"
                : "command.speed.stick.apply.success",
            Map.of(
                "from",
                plan.from().value(),
                "to",
                plan.to().value(),
                "nodes",
                Integer.toString(plan.nodes().size()),
                "edges",
                Integer.toString(change.touchedEdges()),
                "distance_blocks",
                Long.toString(plan.totalLengthBlocks()),
                "speed",
                config.speedText(),
                "ttl",
                config.ttlText())));
    player.sendMessage(
        CommandUx.actions(
            CommandUx.runAction(
                "[详情]",
                "/fta graph edge get path "
                    + CommandUx.quoteCommandArgument(plan.from().value())
                    + " "
                    + CommandUx.quoteCommandArgument(plan.to().value()),
                "查看这段最短路上的 edge 详情"),
            CommandUx.suggestAction(
                "[清除]",
                "/fta speed section clear "
                    + CommandUx.quoteCommandArgument(plan.from().value())
                    + " "
                    + CommandUx.quoteCommandArgument(plan.to().value()),
                "填充 clear 命令；回车后才会执行")));
  }

  private boolean isSpeedSettingStick(ItemStack item) {
    if (item == null || item.getType() != Material.STICK) {
      return false;
    }
    ItemMeta meta = item.getItemMeta();
    if (meta == null) {
      return false;
    }
    return meta.getPersistentDataContainer().has(stickKey, PersistentDataType.BYTE);
  }

  private Optional<SpeedSettingStickConfig> readConfig(ItemStack item) {
    if (item == null) {
      return Optional.empty();
    }
    ItemMeta meta = item.getItemMeta();
    if (meta == null) {
      return Optional.empty();
    }
    PersistentDataContainer container = meta.getPersistentDataContainer();
    Double speed = container.get(speedKey, PersistentDataType.DOUBLE);
    if (speed == null || !Double.isFinite(speed) || speed <= 0.0) {
      return Optional.empty();
    }
    Long ttlSeconds = container.get(ttlSecondsKey, PersistentDataType.LONG);
    Optional<java.time.Duration> ttl =
        ttlSeconds == null || ttlSeconds <= 0L
            ? Optional.empty()
            : Optional.of(java.time.Duration.ofSeconds(ttlSeconds));
    return Optional.of(new SpeedSettingStickConfig(RailSpeed.ofBlocksPerSecond(speed), ttl));
  }
}
