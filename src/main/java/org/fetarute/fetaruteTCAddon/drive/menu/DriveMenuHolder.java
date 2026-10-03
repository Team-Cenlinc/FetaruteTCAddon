package org.fetarute.fetaruteTCAddon.drive.menu;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 停车后菜单的界面持有者，用来在事件里认出“这是驾驶台菜单”。
 *
 * <p>菜单是纯虚拟界面：里面的物品不是真实物品，关闭即丢弃，永远不会交给玩家。
 */
public final class DriveMenuHolder implements InventoryHolder {

  private final UUID playerId;
  private Inventory inventory;

  public DriveMenuHolder(UUID playerId) {
    this.playerId = Objects.requireNonNull(playerId, "playerId");
  }

  /** 菜单属于哪名玩家。 */
  public UUID playerId() {
    return playerId;
  }

  void bind(Inventory inventory) {
    this.inventory = inventory;
  }

  @Override
  public Inventory getInventory() {
    return inventory;
  }
}
