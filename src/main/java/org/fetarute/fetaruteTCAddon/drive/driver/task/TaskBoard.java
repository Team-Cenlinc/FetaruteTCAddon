package org.fetarute.fetaruteTCAddon.drive.driver.task;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 任务板：最近车站即将发出的车次，一格一趟。左键领取人工驾驶，右键领取 ATO。
 *
 * <p>界面只读：所有点击都被取消，物品不会进出背包。
 */
public final class TaskBoard {

  /** 界面大小：六行。 */
  static final int SIZE = 54;

  /** 前五行放车次。 */
  public static final int ENTRY_SLOTS = 45;

  /** 最后一行中间放说明。 */
  static final int INFO_SLOT = 49;

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

  private TaskBoard() {}

  /**
   * 打开任务板。
   *
   * @return 是否打开（别的插件可能取消）
   */
  public static boolean open(Player player, LocaleManager locale, TaskBoardHolder holder) {
    Inventory inventory =
        Bukkit.createInventory(
            holder,
            SIZE,
            locale.component("drive.task.board.title", Map.of("station", holder.stationName())));
    holder.bind(inventory);
    for (int slot = 0; slot < ENTRY_SLOTS; slot++) {
      int index = slot;
      holder.rowAt(slot).ifPresent(row -> inventory.setItem(index, entry(locale, row)));
    }
    if (holder.rowAt(0).isEmpty()) {
      inventory.setItem(
          22, item(Material.BARRIER, locale, "drive.task.board.empty", Map.of(), List.of()));
    }
    inventory.setItem(
        INFO_SLOT,
        item(
            Material.BOOK,
            locale,
            "drive.task.board.info",
            Map.of(),
            List.of("drive.task.board.info-left", "drive.task.board.info-right")));
    return player.openInventory(inventory) != null;
  }

  /** 是不是任务板。 */
  public static boolean isBoard(Inventory inventory) {
    return inventory != null && inventory.getHolder() instanceof TaskBoardHolder;
  }

  private static ItemStack entry(LocaleManager locale, TaskBoardEntries.Row row) {
    Map<String, String> values =
        Map.of(
            "route",
            row.routeCode(),
            "trip",
            row.key().tripCode(),
            "time",
            format(row.plannedDeparture()),
            "platform",
            row.nodeId() == null ? "-" : RouteTerminals.platformOf(row.nodeId()),
            "train",
            row.trainName() == null ? "-" : row.trainName());
    List<String> lore = new ArrayList<>();
    lore.add("drive.task.board.entry-time");
    lore.add("drive.task.board.entry-platform");
    lore.add(
        row.trainName() == null
            ? "drive.task.board.entry-unbound"
            : "drive.task.board.entry-train");
    if (row.dwelling()) {
      lore.add("drive.task.board.entry-dwelling");
    }
    lore.add("drive.task.board.entry-left");
    lore.add("drive.task.board.entry-right");
    return item(
        row.dwelling() ? Material.MAP : Material.PAPER,
        locale,
        "drive.task.board.entry-name",
        values,
        lore);
  }

  private static ItemStack item(
      Material material,
      LocaleManager locale,
      String nameKey,
      Map<String, String> values,
      List<String> loreKeys) {
    ItemStack stack = new ItemStack(material);
    ItemMeta meta = stack.getItemMeta();
    meta.displayName(locale.component(nameKey, values).decoration(TextDecoration.ITALIC, false));
    List<Component> lore = new ArrayList<>();
    for (String key : loreKeys) {
      lore.add(locale.component(key, values).decoration(TextDecoration.ITALIC, false));
    }
    meta.lore(lore);
    if (!stack.setItemMeta(meta)) {
      throw new IllegalStateException("无法为任务板物品 " + material + " 设置物品元数据");
    }
    return stack;
  }

  /** 按服务器时区显示时刻。 */
  public static String format(Instant instant) {
    return TIME.format(instant.atZone(ZoneId.systemDefault()));
  }
}
