package org.fetarute.fetaruteTCAddon.display.pids;

import io.papermc.paper.event.player.PlayerPickEntityEvent;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.GameMode;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.display.pids.map.PidsFrames;
import org.fetarute.fetaruteTCAddon.display.pids.screen.PidsScreen;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 站台屏展示框的交互：安装纸安装、配置棍操作、保护、拆除后的展示框还原。
 *
 * <ul>
 *   <li>保护：屏幕展示框上的右键（旋转、取放物品）、一切伤害（左键取物、弹射物、爆炸、火）、悬挂物破坏（含支撑方块被拆）、 创造模式中键复制地图一律取消。不用 {@code
 *       setFixed}：固定的展示框连交互事件都不触发，配置棍就没法用了。保护在最高优先级取消，其他插件的放行不会生效。
 *   <li>服务不可用时（{@code pids.yml} 关闭、启动失败）仍按框里的地图物品认出屏幕展示框并保护，只是不安装、不响应配置棍。
 *   <li>配置棍：右键打开配置菜单，左键查看信息，潜行左键请求拆除（再点确认）；都须能管理这块屏幕。
 *   <li>安装纸：右键一个空展示框，在那面墙上按布局尺寸找矩形铺满。在最高优先级判断，领地保护等插件已取消的交互不安装。
 *   <li>区块加载时还原本次运行中已拆除的屏幕的展示框（拆除时区块未加载）。
 * </ul>
 *
 * <p>服务每次现取：{@code /fta reload} 会换掉服务实例。
 */
public final class PidsFrameListener implements Listener {

  private final FetaruteTCAddon plugin;

  public PidsFrameListener(FetaruteTCAddon plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onInteract(PlayerInteractEntityEvent event) {
    if (!(event.getRightClicked() instanceof ItemFrame frame)) {
      return;
    }
    Optional<PidsService> service = plugin.getPidsService();
    Player player = event.getPlayer();
    ItemStack hand = player.getInventory().getItemInMainHand();
    Optional<UUID> screenId = screenOf(service, frame);
    if (screenId.isPresent()) {
      event.setCancelled(true);
      if (service.isPresent()
          && event.getHand() == EquipmentSlot.HAND
          && service.get().items().isStick(hand)) {
        openMenu(player, service.get(), frame, screenId.get());
      }
      return;
    }
    if (service.isEmpty() || event.isCancelled() || event.getHand() != EquipmentSlot.HAND) {
      return;
    }
    Optional<String> layoutId = service.get().items().installLayout(hand);
    if (layoutId.isEmpty() || !PidsFrames.isEmpty(frame)) {
      return;
    }
    event.setCancelled(true);
    install(player, service.get(), frame, layoutId.get(), hand);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onDamage(EntityDamageEvent event) {
    if (!(event.getEntity() instanceof ItemFrame frame)) {
      return;
    }
    Optional<PidsService> service = plugin.getPidsService();
    Optional<UUID> screenId = screenOf(service, frame);
    if (screenId.isEmpty()) {
      return;
    }
    event.setCancelled(true);
    if (service.isPresent()
        && event instanceof EntityDamageByEntityEvent byEntity
        && byEntity.getDamager() instanceof Player player
        && service.get().items().isStick(player.getInventory().getItemInMainHand())) {
      leftClick(player, service.get(), screenId.get());
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onHangingBreak(HangingBreakEvent event) {
    if (event.getEntity() instanceof ItemFrame frame
        && screenOf(plugin.getPidsService(), frame).isPresent()) {
      event.setCancelled(true);
    }
  }

  /** 创造模式中键会复制框里的地图；复制件挂到屏幕旁会被 BKC 拼进同一块显示。 */
  @EventHandler(priority = EventPriority.HIGHEST)
  public void onPick(PlayerPickEntityEvent event) {
    if (event.getEntity() instanceof ItemFrame frame
        && screenOf(plugin.getPidsService(), frame).isPresent()) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onEntitiesLoad(EntitiesLoadEvent event) {
    plugin.getPidsService().ifPresent(service -> service.cleanOrphans(event.getEntities()));
  }

  /** 展示框属于哪块屏幕；服务不可用时只看框里的地图物品。 */
  private static Optional<UUID> screenOf(Optional<PidsService> service, ItemFrame frame) {
    return service.isPresent()
        ? service.get().screenOf(frame)
        : PidsFrames.screenIdOf(frame.getItem());
  }

  private void openMenu(Player player, PidsService service, ItemFrame frame, UUID screenId) {
    LocaleManager locale = plugin.getLocaleManager();
    Optional<PidsScreen> screen = service.find(screenId);
    if (screen.isEmpty()) {
      if (player.hasPermission(PidsAccess.MANAGE_PERMISSION)) {
        int restored = service.restoreOrphan(frame);
        if (restored > 0) {
          player.sendMessage(
              locale.component(
                  "pids.menu.orphan-restored", Map.of("count", Integer.toString(restored))));
          return;
        }
      }
      player.sendMessage(locale.component("pids.menu.orphan"));
      return;
    }
    if (!service.canManage(player, screen.get().station())) {
      player.sendMessage(locale.component("pids.menu.no-permission"));
      return;
    }
    new PidsStickMenu(locale).open(player, service, screen.get());
  }

  private void leftClick(Player player, PidsService service, UUID screenId) {
    LocaleManager locale = plugin.getLocaleManager();
    Optional<PidsScreen> screen = service.find(screenId);
    if (screen.isEmpty()) {
      player.sendMessage(locale.component("pids.menu.orphan"));
      return;
    }
    if (!service.canManage(player, screen.get().station())) {
      player.sendMessage(locale.component("pids.menu.no-permission"));
      return;
    }
    PidsStickMenu menu = new PidsStickMenu(locale);
    if (player.isSneaking()) {
      menu.promptRemove(player, screen.get());
    } else {
      menu.info(player, service, screen.get());
    }
  }

  private void install(
      Player player, PidsService service, ItemFrame frame, String layoutId, ItemStack paper) {
    LocaleManager locale = plugin.getLocaleManager();
    Optional<PidsLayout> layout = service.layouts().find(layoutId);
    if (layout.isEmpty()) {
      player.sendMessage(
          locale.component("pids.install.unknown-layout", Map.of("layout", layoutId)));
      return;
    }
    Map<String, String> size =
        Map.of("size", layout.get().tileRows() + "×" + layout.get().tileCols());
    PidsService.InstallResult result = service.install(player, frame, layout.get());
    switch (result.outcome()) {
      case INSTALLED -> {
        if (player.getGameMode() != GameMode.CREATIVE) {
          paper.setAmount(paper.getAmount() - 1);
        }
        announceInstalled(player, service, result.screen().orElseThrow(), layout.get());
      }
      case UNSUPPORTED_FACING -> player.sendMessage(
          locale.component("pids.install.unsupported-facing"));
      case NO_ROOM -> player.sendMessage(locale.component("pids.install.no-room", size));
      case NO_PERMISSION -> player.sendMessage(locale.component("pids.install.no-permission"));
      case LIMIT_REACHED -> player.sendMessage(
          locale.component(
              "pids.install.limit-reached",
              Map.of("limit", Integer.toString(service.settings().limits().maxScreens()))));
      case STORAGE_UNAVAILABLE -> player.sendMessage(
          locale.component("pids.install.storage-unavailable"));
      case MAP_UNAVAILABLE -> player.sendMessage(locale.component("pids.install.map-unavailable"));
    }
  }

  private void announceInstalled(
      Player player, PidsService service, PidsScreen screen, PidsLayout layout) {
    LocaleManager locale = plugin.getLocaleManager();
    player.sendMessage(
        locale.component(
            "pids.install.success",
            Map.of(
                "layout",
                layout.name(),
                "size",
                screen.tileRows() + "×" + screen.tileCols(),
                "id",
                PidsComposer.shortId(screen.id()))));
    screen
        .station()
        .ifPresentOrElse(
            station ->
                player.sendMessage(
                    locale.component(
                        "pids.install.detected",
                        Map.of(
                            "station",
                            PidsStickMenu.stationName(service, station),
                            "platforms",
                            PidsStickMenu.platformsText(locale, screen)))),
            () -> player.sendMessage(locale.component("pids.install.not-detected")));
    Component next = locale.component("pids.install.next-step");
    if (!service.items().isStick(player.getInventory().getItemInMainHand())) {
      next =
          next.append(Component.space())
              .append(
                  locale
                      .component("pids.install.get-stick")
                      .clickEvent(ClickEvent.runCommand("/fta pids stick"))
                      .hoverEvent(locale.component("pids.install.get-stick-hover")));
    }
    player.sendMessage(next);
  }
}
