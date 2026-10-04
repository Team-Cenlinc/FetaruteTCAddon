package org.fetarute.fetaruteTCAddon.drive.driver.task;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 任务板：一个车站即将发出的车次，一格一趟，写明终点站、停站数与按表的运行时长。左键领取人工驾驶，右键领取 ATO。 正在本站停站的车次用画着内容的地图，其余用空地图。
 *
 * <p>已被领走的车次照样列出，灰色显示领取人，点击不起作用。界面只读：所有点击都被取消，物品不会进出背包。
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
   * @param atoAllowed 玩家能否以 ATO 方式领取；不能时不显示右键的说明
   * @return 是否打开（别的插件可能取消）
   */
  public static boolean open(
      Player player, LocaleManager locale, TaskBoardHolder holder, boolean atoAllowed) {
    Inventory inventory =
        Bukkit.createInventory(
            holder,
            SIZE,
            locale.component("drive.task.board.title", Map.of("station", holder.stationName())));
    holder.bind(inventory);
    for (int slot = 0; slot < ENTRY_SLOTS; slot++) {
      int index = slot;
      holder
          .entryAt(slot)
          .ifPresent(
              entry ->
                  inventory.setItem(index, entry(locale, entry, holder.playerId(), atoAllowed)));
    }
    if (holder.entryAt(0).isEmpty()) {
      inventory.setItem(
          22, item(Material.BARRIER, locale, "drive.task.board.empty", Map.of(), List.of()));
    }
    List<String> info = new ArrayList<>();
    info.add("drive.task.board.info-left");
    if (atoAllowed) {
      info.add("drive.task.board.info-right");
    }
    info.add("drive.task.board.info-claimed");
    inventory.setItem(
        INFO_SLOT, item(Material.BOOK, locale, "drive.task.board.info", Map.of(), info));
    return player.openInventory(inventory) != null;
  }

  /** 是不是任务板。 */
  public static boolean isBoard(Inventory inventory) {
    return inventory != null && inventory.getHolder() instanceof TaskBoardHolder;
  }

  private static ItemStack entry(
      LocaleManager locale, TaskBoardEntries.Entry entry, UUID viewer, boolean atoAllowed) {
    TaskBoardEntries.Row row = entry.row();
    TaskBoardEntries.Trip trip = entry.trip();
    Map<String, String> values = new HashMap<>();
    values.put("route", row.routeCode());
    values.put("trip", row.key().tripCode());
    values.put("time", format(row.plannedDeparture()));
    values.put("platform", row.nodeId() == null ? "-" : RouteTerminals.platformOf(row.nodeId()));
    values.put("train", row.trainName() == null ? "-" : row.trainName());
    values.put("player", entry.claimed() ? entry.claimant().playerName() : "");
    List<String> lore = new ArrayList<>();
    lore.add("drive.task.board.entry-time");
    if (trip != null) {
      values.put("destination", trip.destination().isBlank() ? "-" : trip.destination());
      values.put("stops", String.valueOf(trip.stopCount()));
      lore.add("drive.task.board.entry-destination");
      lore.add("drive.task.board.entry-stops");
      if (trip.runSeconds() >= 0L) {
        TaskTripSummary.RunTimeText runTime = TaskTripSummary.runTime(trip.runSeconds());
        values.put("run_time", runTime.render(locale.text(runTime.key())));
        lore.add("drive.task.board.entry-run-time");
      }
    }
    lore.add("drive.task.board.entry-platform");
    if (entry.claimed()) {
      lore.add(
          entry.claimedBy(viewer)
              ? "drive.task.board.entry-claimed-self"
              : "drive.task.board.entry-claimed");
      return item(Material.GRAY_DYE, locale, "drive.task.board.entry-name-claimed", values, lore);
    }
    lore.add(
        row.trainName() == null
            ? "drive.task.board.entry-unbound"
            : "drive.task.board.entry-train");
    if (row.dwelling()) {
      lore.add("drive.task.board.entry-dwelling");
    }
    lore.add("drive.task.board.entry-left");
    if (atoAllowed) {
      lore.add("drive.task.board.entry-right");
    }
    return item(entryMaterial(row.dwelling()), locale, "drive.task.board.entry-name", values, lore);
  }

  /** 车次条目的图标：正在本站停站（马上能接班）的用画着内容的地图，其余用空地图。两者轮廓相同、一个有字一个没字，一眼分得清；已被领取的用灰色染料。 */
  static Material entryMaterial(boolean dwelling) {
    return dwelling ? Material.FILLED_MAP : Material.MAP;
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
    // 画着内容的地图没有对应的地图数据：藏起“未知地图”等附加说明，只显示条目文字。
    meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
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
