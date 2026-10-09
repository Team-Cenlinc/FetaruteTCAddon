package org.fetarute.fetaruteTCAddon.drive.driver.task;

import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.fetarute.fetaruteTCAddon.drive.SimulationLevel;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;

/** 任务板的点击：一律取消（物品不进出背包），车次左键领人工驾驶、右键领 ATO；点难度按钮换仿真等级。车掌任务板左键领车掌。 */
public final class TaskBoardListener implements Listener {

  /** 领取的回调。 */
  public interface ClaimHandler {
    void claim(Player player, TaskBoardHolder holder, TaskBoardEntries.Row row, DrivingMode mode);
  }

  /** 车掌任务板领取的回调。 */
  public interface GuardClaimHandler {
    void claim(Player player, TaskBoardHolder holder, TaskBoardEntries.Row row);
  }

  /** 选择驾驶难度的回调。 */
  public interface LevelHandler {
    void choose(Player player, TaskBoardHolder holder, SimulationLevel level);
  }

  private final ClaimHandler handler;
  private final LevelHandler levels;
  private final GuardClaimHandler guards;

  public TaskBoardListener(ClaimHandler handler, LevelHandler levels, GuardClaimHandler guards) {
    this.handler = handler;
    this.levels = levels;
    this.guards = guards;
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
    if (click != ClickType.LEFT && click != ClickType.RIGHT) {
      return;
    }
    if (holder.kind() == TaskBoard.Kind.GUARD) {
      if (click == ClickType.LEFT) {
        holder
            .rowAt(event.getSlot())
            .ifPresent(
                row -> {
                  player.closeInventory();
                  guards.claim(player, holder, row);
                });
      }
      return;
    }
    Optional<SimulationLevel> level = holder.levelAt(event.getSlot());
    if (level.isPresent()) {
      if (holder.level().orElse(null) != level.get()) {
        levels.choose(player, holder, level.get());
      }
      return;
    }
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
