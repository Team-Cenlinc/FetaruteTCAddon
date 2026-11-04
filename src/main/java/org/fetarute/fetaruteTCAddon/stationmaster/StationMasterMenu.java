package org.fetarute.fetaruteTCAddon.stationmaster;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.fetarute.fetaruteTCAddon.drive.inventory.CrewMenuHolder;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.fetarute.fetaruteTCAddon.utils.PackModel;

/**
 * 站长菜单（F，三排 27 格）：呼叫信号员、本班情况、下岗（点两次）。
 *
 * <p>贴图：呼叫是 {@code station/call}，下岗沿用驾驶台的“结束驾驶”（{@code panel/end · end_confirm}）。
 */
final class StationMasterMenu {

  static final int SIZE = 27;

  /** 菜单上的按钮。 */
  enum Action {
    CALL(2),
    INFO(4),
    OFF(8);

    private final int slot;

    Action(int slot) {
      this.slot = slot;
    }

    int slot() {
      return slot;
    }

    static Optional<Action> at(int slot) {
      for (Action action : values()) {
        if (action.slot == slot) {
          return Optional.of(action);
        }
      }
      return Optional.empty();
    }
  }

  private static final class Holder implements CrewMenuHolder {
    private final BiConsumer<Player, Integer> clicked;
    private final Consumer<Player> closed;
    private Inventory inventory;

    Holder(BiConsumer<Player, Integer> clicked, Consumer<Player> closed) {
      this.clicked = clicked;
      this.closed = closed;
    }

    @Override
    public void onMenuClick(Player player, int slot) {
      clicked.accept(player, slot);
    }

    @Override
    public void onMenuClosed(Player player) {
      closed.accept(player);
    }

    @Override
    public Inventory getInventory() {
      return inventory;
    }
  }

  private StationMasterMenu() {}

  /** 新建菜单窗口。 */
  static Inventory create(
      LocaleManager locale,
      StationMasterDisplay.Snapshot snapshot,
      boolean offConfirm,
      BiConsumer<Player, Integer> clicked,
      Consumer<Player> closed) {
    Holder holder = new Holder(clicked, closed);
    Inventory inventory =
        Bukkit.createInventory(holder, SIZE, locale.component("stationmaster.menu.title"));
    holder.inventory = inventory;
    render(inventory, locale, snapshot, offConfirm);
    return inventory;
  }

  /** 按此刻的样子刷新按钮。 */
  static void render(
      Inventory inventory,
      LocaleManager locale,
      StationMasterDisplay.Snapshot snapshot,
      boolean offConfirm) {
    inventory.setItem(
        Action.CALL.slot(),
        item(
            locale,
            Material.LIGHT_BLUE_DYE,
            "station/call",
            "stationmaster.menu.call",
            "stationmaster.menu.hint.call",
            Map.of(),
            false));
    inventory.setItem(
        Action.INFO.slot(),
        item(
            locale,
            Material.BOOK,
            null,
            "stationmaster.menu.info",
            "stationmaster.menu.hint.info",
            Map.of(
                "station", snapshot.station(),
                "tracks", snapshot.tracks(),
                "signals", String.valueOf(snapshot.signals()),
                "auto", String.valueOf(snapshot.autoSignals()),
                "missed", String.valueOf(snapshot.watchMissed())),
            false));
    inventory.setItem(
        Action.OFF.slot(),
        item(
            locale,
            offConfirm ? Material.RED_DYE : Material.LIGHT_GRAY_DYE,
            offConfirm ? "drive/panel/end_confirm" : "drive/panel/end",
            offConfirm ? "stationmaster.menu.off-confirm" : "stationmaster.menu.off",
            "stationmaster.menu.hint.off",
            Map.of(),
            offConfirm));
  }

  private static ItemStack item(
      LocaleManager locale,
      Material material,
      String model,
      String nameKey,
      String hintKey,
      Map<String, String> values,
      boolean glint) {
    ItemStack stack = new ItemStack(material);
    ItemMeta meta = stack.getItemMeta();
    meta.displayName(locale.component(nameKey, values).decoration(TextDecoration.ITALIC, false));
    meta.lore(List.of(locale.component(hintKey, values).decoration(TextDecoration.ITALIC, false)));
    if (model != null) {
      PackModel.mark(meta, model);
    }
    if (glint) {
      meta.setEnchantmentGlintOverride(true);
    }
    if (!stack.setItemMeta(meta)) {
      throw new IllegalStateException("无法为站长菜单按钮 " + nameKey + " 设置物品元数据");
    }
    return stack;
  }
}
