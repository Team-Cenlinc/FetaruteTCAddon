package org.fetarute.fetaruteTCAddon.drive.inventory;

import com.bergerkiller.bukkit.tc.events.seat.MemberBeforeSeatEnterEvent;
import com.bergerkiller.bukkit.tc.events.seat.MemberBeforeSeatExitEvent;
import com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent;
import io.papermc.paper.event.player.PlayerPickItemEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.fetarute.fetaruteTCAddon.drive.guard.GuardMenu;
import org.fetarute.fetaruteTCAddon.drive.menu.DriveMenu;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSession;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSessionManager;

/**
 * 驾驶期间的 Bukkit 事件处理。
 *
 * <p>分两类：
 *
 * <ul>
 *   <li>会话事件：选中槽位换算成档位、潜行离座（含防误离座）、下线、死亡、游戏模式与世界变化；
 *   <li>物品保护：驾驶员的快捷栏里是他的真实物品，客户端却以为是驾驶物品，所以一切会动用、转移、损耗物品的事件都要取消。
 *       数据包层已经截获了大部分按键，这里是第二道防线，并负责对方块的右键、挖掘与放置。
 * </ul>
 *
 * <p>物品保护类处理函数的第一件事就是取消事件，再做其它判断，避免后续代码抛异常时漏取消。
 */
public final class DriveListener implements Listener {

  private final Plugin plugin;
  private final DriveSessionManager manager;

  public DriveListener(Plugin plugin, DriveSessionManager manager) {
    this.plugin = plugin;
    this.manager = manager;
  }

  // ---- 会话事件 ----

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onHeldSlot(PlayerItemHeldEvent event) {
    Player player = event.getPlayer();
    int corrected = manager.onHeldSlot(player, event.getNewSlot());
    if (corrected < 0 || corrected == event.getNewSlot()) {
      return;
    }
    event.setCancelled(true);
    if (corrected != event.getPreviousSlot()) {
      Bukkit.getScheduler().runTask(plugin, () -> player.getInventory().setHeldItemSlot(corrected));
    }
  }

  @EventHandler
  public void onInput(PlayerInputEvent event) {
    manager.noteSneakInput(event.getPlayer().getUniqueId(), event.getInput().isSneak());
    manager.onHornInput(event.getPlayer(), event.getInput().isJump());
  }

  /** 驾驶员自己按 Shift 离座：行驶中拦下，停稳且有任务时须再按一次（中文输入法常用 Shift 切换，容易误按）。 */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onSeatExit(MemberBeforeSeatExitEvent event) {
    if (event.isPlayerInitiated()
        && !event.isSeatChange()
        && event.getEntity() instanceof Player player
        && !manager.allowSeatExit(player)) {
      event.setCancelled(true);
    }
  }

  /** 车掌预留的座位：别人不能坐进去（车掌换端时先让出来）。 */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onSeatEnter(MemberBeforeSeatEnterEvent event) {
    if (!manager.allowSeatEnter(event.getEntity(), event.getMember(), event.getSeat())) {
      event.setCancelled(true);
    }
  }

  /** 原版下车事件上的第二道：TrainCarts 没把潜行下车交给它的离座事件时照样拦得住，同一 tick 里两道的判定一致。 */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onDismount(EntityDismountEvent event) {
    if (event.isCancellable()
        && event.getEntity() instanceof Player player
        && !manager.allowDismount(player)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    manager.onPlayerGone(event.getPlayer().getUniqueId(), DriveSession.EndReason.OFFLINE);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onDeath(PlayerDeathEvent event) {
    manager.onPlayerGone(event.getEntity().getUniqueId(), DriveSession.EndReason.DEATH);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onGameMode(PlayerGameModeChangeEvent event) {
    if (!manager.isGameModeAllowed(event.getNewGameMode())) {
      manager.onPlayerGone(event.getPlayer().getUniqueId(), DriveSession.EndReason.GAME_MODE);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onWorldChange(PlayerChangedWorldEvent event) {
    if (manager.isDriving(event.getPlayer().getUniqueId())) {
      manager.refreshLater(event.getPlayer());
    }
  }

  // ---- 物品保护 ----

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onDrop(PlayerDropItemEvent event) {
    guard(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onInteract(PlayerInteractEvent event) {
    if (guard(event.getPlayer(), event)) {
      event.setUseItemInHand(Event.Result.DENY);
      event.setUseInteractedBlock(Event.Result.DENY);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onInteractEntity(PlayerInteractEntityEvent event) {
    if (guard(event.getPlayer(), event) && event.getHand() == EquipmentSlot.HAND) {
      // 拦下的右键若是点在自己驾驶的列车上，由会话代为入座（换端时走到另一端、被挤下座位后回座）。
      manager.onGuardedEntityClick(event.getPlayer(), event.getRightClicked());
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
    guard(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
    guard(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onPickItem(PlayerPickItemEvent event) {
    guard(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onRecipeBookClick(PlayerRecipeBookClickEvent event) {
    guard(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onProjectileLaunch(ProjectileLaunchEvent event) {
    if (event.getEntity().getShooter() instanceof Player player) {
      guard(player, event);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onConsume(PlayerItemConsumeEvent event) {
    guard(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onSwapHands(PlayerSwapHandItemsEvent event) {
    guard(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onBlockPlace(BlockPlaceEvent event) {
    guard(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onBlockBreak(BlockBreakEvent event) {
    guard(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onBucketEmpty(PlayerBucketEmptyEvent event) {
    guard(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onBucketFill(PlayerBucketFillEvent event) {
    guard(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onItemDamage(PlayerItemDamageEvent event) {
    guard(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onFish(PlayerFishEvent event) {
    guard(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onShootBow(EntityShootBowEvent event) {
    if (event.getEntity() instanceof Player player) {
      guard(player, event);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onPickup(EntityPickupItemEvent event) {
    if (event.getEntity() instanceof Player player) {
      guard(player, event);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onInventoryOpen(InventoryOpenEvent event) {
    if (DriveMenu.isMenu(event.getInventory()) || GuardMenu.isMenu(event.getInventory())) {
      // 停车后菜单与车掌菜单是我们自己打开的虚拟界面，放行；其它界面一律不许打开。
      return;
    }
    if (event.getPlayer() instanceof Player player) {
      guard(player, event);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onInventoryClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player) || !guard(player, event)) {
      return;
    }
    // 点击一律取消；菜单上半部分的左键、右键点击再交给会话管理器处理。
    var top = event.getView().getTopInventory();
    boolean buttonClick =
        event.getClickedInventory() == top
            && (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT);
    if (DriveMenu.isMenu(top) && buttonClick) {
      manager.onMenuClick(player, event.getSlot());
    } else if (GuardMenu.isMenu(top) && buttonClick) {
      manager.guards().ifPresent(guards -> guards.onMenuClick(player, top, event.getSlot()));
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onInventoryClose(InventoryCloseEvent event) {
    if (DriveMenu.isMenu(event.getInventory()) && event.getPlayer() instanceof Player player) {
      manager.onMenuClosed(player);
    }
    if (GuardMenu.isMenu(event.getInventory()) && event.getPlayer() instanceof Player player) {
      manager.guards().ifPresent(guards -> guards.onMenuClosed(player));
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onInventoryCreative(InventoryCreativeEvent event) {
    if (event.getWhoClicked() instanceof Player player) {
      guard(player, event);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onInventoryDrag(InventoryDragEvent event) {
    if (event.getWhoClicked() instanceof Player player) {
      guard(player, event);
    }
  }

  /**
   * 玩家正在驾驶或当车掌就取消事件。
   *
   * @return 玩家是否正在驾驶或当车掌（即事件是否被取消）
   */
  private boolean guard(Player player, Cancellable event) {
    if (!manager.isProtected(player.getUniqueId())) {
      return false;
    }
    event.setCancelled(true);
    return true;
  }
}
