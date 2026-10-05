package org.fetarute.fetaruteTCAddon.drive.license;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 游戏内的《FTCA 驾驶员手册》：一本成书，讲 FTCA 的大致流程与开车流程（驾驶证、领任务与接班、驾驶台、信号与控速、进站与车门、发车与准点、终点与换端、出了状况）。
 * 每页一个语言键（{@code drive.handbook.driver.page-1} 起），完整版在 Fetarute Wiki。
 */
public final class DriverHandbook {

  /** 手册页数（语言文件里的页键个数）。 */
  public static final int PAGES = 10;

  private final NamespacedKey markKey;

  public DriverHandbook(Plugin plugin) {
    this.markKey = new NamespacedKey(plugin, "driver_handbook");
  }

  /** 印一本手册。 */
  public ItemStack create(LocaleManager locale) {
    ItemStack stack = new ItemStack(Material.WRITTEN_BOOK);
    BookMeta meta = (BookMeta) stack.getItemMeta();
    meta.title(locale.component("drive.handbook.driver.title"));
    meta.author(locale.component("drive.handbook.driver.author"));
    meta.setGeneration(BookMeta.Generation.ORIGINAL);
    for (int page = 1; page <= PAGES; page++) {
      meta.addPages(locale.component("drive.handbook.driver.page-" + page));
    }
    meta.getPersistentDataContainer().set(markKey, PersistentDataType.BYTE, (byte) 1);
    if (!stack.setItemMeta(meta)) {
      throw new IllegalStateException("无法为驾驶员手册设置物品元数据");
    }
    return stack;
  }

  /** 玩家背包里有没有这本手册。 */
  public boolean carriedBy(Player player) {
    PlayerInventory inventory = player.getInventory();
    for (int slot = 0; slot < inventory.getSize(); slot++) {
      ItemStack stack = inventory.getItem(slot);
      if (stack == null || stack.getType() != Material.WRITTEN_BOOK) {
        continue;
      }
      ItemMeta meta = stack.getItemMeta();
      if (meta != null && meta.getPersistentDataContainer().has(markKey)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 给玩家一本手册（放不下掉在脚边）。
   *
   * @param onlyIfMissing 背包里已有一本时不再给
   * @return 是否给了
   */
  public boolean give(Player player, LocaleManager locale, boolean onlyIfMissing) {
    if (onlyIfMissing && carriedBy(player)) {
      return false;
    }
    for (ItemStack left : player.getInventory().addItem(create(locale)).values()) {
      player.getWorld().dropItemNaturally(player.getLocation(), left);
    }
    return true;
  }
}
