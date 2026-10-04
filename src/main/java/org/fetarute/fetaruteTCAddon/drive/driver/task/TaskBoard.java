package org.fetarute.fetaruteTCAddon.drive.driver.task;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.drive.SimulationLevel;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 任务板：一个车站即将发出的车次，一格一趟，写明终点站、停站数与按表的运行时长。左键领取人工驾驶，右键领取 ATO。 正在本站停站的车次用画着内容的地图，其余用空地图。
 *
 * <p>已被领走的车次照样列出，灰色显示领取人，点击不起作用。界面只读：所有点击都被取消，物品不会进出背包。
 *
 * <p>最后一行左侧是驾驶难度（仿真等级）的两个按钮，选中的发光；选择记在玩家数据里，从下一次开始驾驶起生效。玩家不能自选等级时不显示。
 */
public final class TaskBoard {

  /** 界面大小：六行。 */
  static final int SIZE = 54;

  /** 前五行放车次。 */
  public static final int ENTRY_SLOTS = 45;

  /** 最后一行中间放说明。 */
  static final int INFO_SLOT = 49;

  /** 最后一行左侧：驾驶难度“标准”。 */
  static final int STANDARD_SLOT = 45;

  /** 最后一行左侧：驾驶难度“仿真”。 */
  static final int SIMULATION_SLOT = 46;

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

  private TaskBoard() {}

  /**
   * 打开任务板。
   *
   * @param atoAllowed 玩家能否以 ATO 方式领取；不能时不显示右键的说明
   * @param level 玩家此刻选定的仿真等级；玩家不能自选等级时为空，界面上不显示难度按钮
   * @param driving 玩家是否正在驾驶（选择要到下一次开始驾驶才生效，按钮上注明）
   * @return 是否打开（别的插件可能取消）
   */
  public static boolean open(
      Player player,
      LocaleManager locale,
      TaskBoardHolder holder,
      boolean atoAllowed,
      Optional<SimulationLevel> level,
      boolean driving) {
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
    level.ifPresent(chosen -> showLevel(holder, locale, chosen, driving));
    return player.openInventory(inventory) != null;
  }

  /** 在已打开的任务板上换选中的难度按钮。 */
  public static void showLevel(
      TaskBoardHolder holder, LocaleManager locale, SimulationLevel chosen, boolean driving) {
    holder.setLevel(chosen);
    Inventory inventory = holder.getInventory();
    for (SimulationLevel level : SimulationLevel.values()) {
      inventory.setItem(slotOf(level), levelItem(locale, level, level == chosen, driving));
    }
  }

  /** 难度按钮所在的格子。 */
  static int slotOf(SimulationLevel level) {
    return level == SimulationLevel.SIMULATION ? SIMULATION_SLOT : STANDARD_SLOT;
  }

  /** 这一格是哪个难度按钮；不是难度按钮时为空。 */
  static Optional<SimulationLevel> levelOfSlot(int slot) {
    for (SimulationLevel level : SimulationLevel.values()) {
      if (slotOf(level) == slot) {
        return Optional.of(level);
      }
    }
    return Optional.empty();
  }

  /** 难度按钮的图标：标准用拉杆（一键启动），仿真用比较器（逐项操作开关）。 */
  static Material levelMaterial(SimulationLevel level) {
    return level == SimulationLevel.SIMULATION ? Material.COMPARATOR : Material.LEVER;
  }

  /** 难度按钮的说明行。 */
  static List<String> levelLore(SimulationLevel level, boolean selected, boolean driving) {
    String prefix = "drive.task.board.level." + level.name().toLowerCase(Locale.ROOT);
    List<String> lore = new ArrayList<>();
    lore.add(prefix + "-desc");
    if (level == SimulationLevel.SIMULATION) {
      lore.add(prefix + "-desc-2");
    }
    lore.add(selected ? "drive.task.board.level.selected" : "drive.task.board.level.select");
    lore.add(driving ? "drive.task.board.level.next-session" : "drive.task.board.level.remember");
    return lore;
  }

  private static ItemStack levelItem(
      LocaleManager locale, SimulationLevel level, boolean selected, boolean driving) {
    ItemStack stack =
        item(
            levelMaterial(level),
            locale,
            "drive.task.board.level." + level.name().toLowerCase(Locale.ROOT),
            Map.of(),
            levelLore(level, selected, driving));
    ItemMeta meta = stack.getItemMeta();
    meta.setEnchantmentGlintOverride(selected);
    if (!stack.setItemMeta(meta)) {
      throw new IllegalStateException("无法为任务板难度按钮设置物品元数据");
    }
    return stack;
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
      if (entry.claimedBy(viewer)) {
        // 自己领的：图标照常、发光，一眼找得到；点击同样不起作用。
        lore.add("drive.task.board.entry-claimed-self");
        if (row.dwelling()) {
          lore.add("drive.task.board.entry-dwelling");
        }
        ItemStack own =
            item(
                entryMaterial(row.dwelling()), locale, "drive.task.board.entry-name", values, lore);
        ItemMeta meta = own.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        if (!own.setItemMeta(meta)) {
          throw new IllegalStateException("无法为任务板条目设置物品元数据");
        }
        return own;
      }
      lore.add("drive.task.board.entry-claimed");
      ItemStack taken =
          item(claimedMaterial(), locale, "drive.task.board.entry-name-claimed", values, lore);
      if (taken.getItemMeta() instanceof MapMeta map) {
        map.setColor(CLAIMED_MARKINGS);
        if (!taken.setItemMeta(map)) {
          throw new IllegalStateException("无法为任务板条目设置地图颜色");
        }
      }
      return taken;
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

  /** 车次条目的图标：正在本站停站（马上能接班）的用画着内容的地图，其余用空地图。两者轮廓相同、一个有字一个没字，一眼分得清。自己领的照常显示并发光。 */
  static Material entryMaterial(boolean dwelling) {
    return dwelling ? Material.FILLED_MAP : Material.MAP;
  }

  /** 别人已领走的车次：画着内容的地图、标记染成红色（像藏宝图），与停站中的车次轮廓相同、颜色不同。 */
  static Material claimedMaterial() {
    return Material.FILLED_MAP;
  }

  /** 别人已领走的车次地图上的标记颜色。 */
  static final Color CLAIMED_MARKINGS = Color.fromRGB(0xB0, 0x2E, 0x26);

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
