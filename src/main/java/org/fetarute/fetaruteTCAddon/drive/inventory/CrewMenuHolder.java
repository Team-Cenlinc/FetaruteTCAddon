package org.fetarute.fetaruteTCAddon.drive.inventory;

import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;

/** 岗位自己的 F 菜单：窗口持有者实现它，物品保护放行这个窗口，点击与关闭转回岗位处理。所有点击都已被取消，物品不会进出背包。 */
public interface CrewMenuHolder extends InventoryHolder {

  /** 点了菜单上半部分的一格（左键或右键）。 */
  void onMenuClick(Player player, int slot);

  /** 菜单关了。 */
  void onMenuClosed(Player player);
}
