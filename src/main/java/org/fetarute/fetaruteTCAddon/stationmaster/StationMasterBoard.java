package org.fetarute.fetaruteTCAddon.stationmaster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 站长任务板（{@code /fta stationmaster tasks}）：各车站一格，写明股道数、已有几名站长；点击在这一站上岗，人不在站上时只告诉去哪。界面只读，物品不会进出背包。
 */
final class StationMasterBoard implements Listener {

  /** 界面大小：六行。 */
  private static final int SIZE = 54;

  /**
   * 任务板上的一个车站。
   *
   * @param station 车站
   * @param tracks 站台股道数
   * @param masters 已在岗的站长数
   */
  record Entry(StationMasterWorld.Station station, int tracks, int masters) {}

  private static final class Holder implements InventoryHolder {
    private final List<Entry> entries;
    private Inventory inventory;

    Holder(List<Entry> entries) {
      this.entries = List.copyOf(entries);
    }

    @Override
    public Inventory getInventory() {
      return inventory;
    }
  }

  private final Consumer<Clicked> clicked;

  /** 点了任务板上的一个车站。 */
  record Clicked(Player player, Entry entry) {}

  StationMasterBoard(Consumer<Clicked> clicked) {
    this.clicked = clicked;
  }

  /** 打开任务板；放不下的车站不显示。 */
  static void open(Player player, LocaleManager locale, List<Entry> all) {
    List<Entry> entries = new ArrayList<>(all.subList(0, Math.min(SIZE, all.size())));
    Holder holder = new Holder(entries);
    Inventory inventory =
        Bukkit.createInventory(holder, SIZE, locale.component("stationmaster.board.title"));
    holder.inventory = inventory;
    for (int slot = 0; slot < entries.size(); slot++) {
      inventory.setItem(slot, item(locale, entries.get(slot)));
    }
    player.openInventory(inventory);
  }

  private static ItemStack item(LocaleManager locale, Entry entry) {
    Map<String, String> values =
        Map.of(
            "station", entry.station().name(),
            "code", entry.station().code(),
            "tracks", String.valueOf(entry.tracks()),
            "masters", String.valueOf(entry.masters()));
    ItemStack stack = new ItemStack(entry.masters() > 0 ? Material.LIME_DYE : Material.GRAY_DYE);
    ItemMeta meta = stack.getItemMeta();
    meta.displayName(
        locale
            .component("stationmaster.board.station", values)
            .decoration(TextDecoration.ITALIC, false));
    meta.lore(
        List.of(
            locale
                .component("stationmaster.board.detail", values)
                .decoration(TextDecoration.ITALIC, false),
            locale
                .component("stationmaster.board.hint", values)
                .decoration(TextDecoration.ITALIC, false)));
    if (!stack.setItemMeta(meta)) {
      throw new IllegalStateException("无法为站长任务板设置物品元数据");
    }
    return stack;
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void onClick(InventoryClickEvent event) {
    if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) {
      return;
    }
    event.setCancelled(true);
    if (event.getWhoClicked() instanceof Player player
        && event.getClickedInventory() == event.getView().getTopInventory()
        && event.getClick() == ClickType.LEFT
        && event.getSlot() < holder.entries.size()) {
      player.closeInventory();
      clicked.accept(new Clicked(player, holder.entries.get(event.getSlot())));
    }
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void onDrag(InventoryDragEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof Holder) {
      event.setCancelled(true);
    }
  }
}
