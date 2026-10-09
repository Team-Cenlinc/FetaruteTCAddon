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
 *
 * <p>《FTCA 车掌手册》（{@link #guard}）是同样的成书，语言键在 {@code drive.handbook.guard} 下。
 */
public final class DriverHandbook {

  /** 手册页数（语言文件里的页键个数）。 */
  public static final int PAGES = 10;

  /** 车掌手册页数。 */
  public static final int GUARD_PAGES = 6;

  private final NamespacedKey markKey;
  private final String prefix;
  private final int pages;

  public DriverHandbook(Plugin plugin) {
    this(plugin, "driver_handbook", "drive.handbook.driver", PAGES);
  }

  private DriverHandbook(Plugin plugin, String mark, String prefix, int pages) {
    this.markKey = new NamespacedKey(plugin, mark);
    this.prefix = prefix;
    this.pages = pages;
  }

  /** 《FTCA 车掌手册》：上岗、站停作业、发车铃与紧急停车、终点换端、成绩与考试。 */
  public static DriverHandbook guard(Plugin plugin) {
    return new DriverHandbook(plugin, "guard_handbook", "drive.handbook.guard", GUARD_PAGES);
  }

  /** 语言键前缀（{@code drive.handbook.driver} 或 {@code drive.handbook.guard}）。 */
  public String prefix() {
    return prefix;
  }

  /** 印一本手册。 */
  public ItemStack create(LocaleManager locale) {
    ItemStack stack = new ItemStack(Material.WRITTEN_BOOK);
    BookMeta meta = (BookMeta) stack.getItemMeta();
    meta.title(locale.component(prefix + ".title"));
    meta.author(locale.component(prefix + ".author"));
    meta.setGeneration(BookMeta.Generation.ORIGINAL);
    for (int page = 1; page <= pages; page++) {
      meta.addPages(locale.component(prefix + ".page-" + page));
    }
    meta.getPersistentDataContainer().set(markKey, PersistentDataType.BYTE, (byte) 1);
    if (!stack.setItemMeta(meta)) {
      throw new IllegalStateException("无法为手册设置物品元数据: " + prefix);
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
