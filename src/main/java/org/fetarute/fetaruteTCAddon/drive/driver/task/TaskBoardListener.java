package org.fetarute.fetaruteTCAddon.drive.driver.task;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;

/** 任务板的点击：一律取消（物品不进出背包），左键领人工驾驶、右键领 ATO。 */
public final class TaskBoardListener implements Listener {

  /** 领取的回调。 */
  public interface ClaimHandler {
    void claim(Player player, TaskBoardHolder holder, TaskBoardEntries.Row row, DrivingMode mode);
  }

  private final ClaimHandler handler;

  public TaskBoardListener(ClaimHandler handler) {
    this.handler = handler;
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
  public void onClick(InventoryClickEvent event) {
    Inventory top = event.getView().getTopInventory();
    if (!(top.getHolder() instanceof TaskBoardHolder holder)) {
      return;
    }
    event.setCancelled(true);
    if (!(event.getWhoClicked() instanceof Player player)
        || event.getClickedInventory() != top
        || !player.getUniqueId().equals(holder.playerId())) {
      return;
    }
    ClickType click = event.getClick();
    DrivingMode mode =
        click == ClickType.LEFT
            ? DrivingMode.MANUAL
            : click == ClickType.RIGHT ? DrivingMode.ATO : null;
    if (mode == null) {
      return;
    }
    holder
        .rowAt(event.getSlot())
        .ifPresent(
            row -> {
              player.closeInventory();
              handler.claim(player, holder, row, mode);
            });
  }

  /** 别的插件在中间优先级撤销取消也不行：最后再取消一次。 */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void enforceClickCancelled(InventoryClickEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof TaskBoardHolder) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void enforceDragCancelled(InventoryDragEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof TaskBoardHolder) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
  public void onDrag(InventoryDragEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof TaskBoardHolder) {
      event.setCancelled(true);
    }
  }
}
