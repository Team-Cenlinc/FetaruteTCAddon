package org.fetarute.fetaruteTCAddon.drive.guard;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.fetarute.fetaruteTCAddon.drive.inventory.HotbarItems;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 车掌菜单（F，三排 27 格）：传送入座、呼叫驾驶员、异常报告、结束值乘。
 *
 * <p>贴图沿用驾驶台面板：结束值乘用驾驶台的“结束驾驶”（{@code panel/end · end_confirm}），呼叫用发车铃，传送入座是 {@code guard/seat}。
 */
public final class GuardMenu {

  /** 菜单格数。 */
  public static final int SIZE = 27;

  /** 菜单上的按钮。 */
  public enum Action {
    SEAT(0),
    CALL(2),
    REPORT(4),
    END(8);

    private final int slot;

    Action(int slot) {
      this.slot = slot;
    }

    public int slot() {
      return slot;
    }
  }

  /**
   * 按钮此刻的样子。
   *
   * @param endConfirm 结束值乘已点过一次，等再次点击确认
   * @param reportUsed 本站报告次数已用完
   */
  public record View(boolean endConfirm, boolean reportUsed) {}

  /** 识别车掌菜单的窗口持有者。 */
  static final class Holder implements InventoryHolder {
    private final UUID playerId;
    private Inventory inventory;

    Holder(UUID playerId) {
      this.playerId = playerId;
    }

    UUID playerId() {
      return playerId;
    }

    @Override
    public Inventory getInventory() {
      return inventory;
    }
  }

  private GuardMenu() {}

  /** 这个窗口是不是车掌菜单。 */
  public static boolean isMenu(Inventory inventory) {
    return inventory != null && inventory.getHolder() instanceof Holder;
  }

  /** 这一格是哪个按钮；空格为空。 */
  public static Optional<Action> actionAt(int slot) {
    for (Action action : Action.values()) {
      if (action.slot == slot) {
        return Optional.of(action);
      }
    }
    return Optional.empty();
  }

  /** 新建菜单窗口。 */
  public static Inventory create(UUID playerId, LocaleManager locale, View view) {
    Holder holder = new Holder(Objects.requireNonNull(playerId, "playerId"));
    Inventory inventory =
        Bukkit.createInventory(holder, SIZE, locale.component("drive.guard.menu.title"));
    holder.inventory = inventory;
    render(inventory, locale, view);
    return inventory;
  }

  /** 按此刻的样子刷新按钮。 */
  public static void render(Inventory inventory, LocaleManager locale, View view) {
    for (Action action : Action.values()) {
      inventory.setItem(action.slot(), item(locale, action, view));
    }
  }

  static ItemStack item(LocaleManager locale, Action action, View view) {
    ItemStack stack = new ItemStack(materialOf(action, view));
    ItemMeta meta = stack.getItemMeta();
    meta.displayName(
        locale.component(nameKey(action, view)).decoration(TextDecoration.ITALIC, false));
    meta.lore(
        List.of(
            locale
                .component("drive.guard.menu.hint." + hintId(action))
                .decoration(TextDecoration.ITALIC, false)));
    meta.setItemModel(new NamespacedKey(HotbarItems.MODEL_NAMESPACE, modelKey(action, view)));
    if (action == Action.END && view.endConfirm()) {
      meta.setEnchantmentGlintOverride(true);
    }
    if (!stack.setItemMeta(meta)) {
      throw new IllegalStateException("无法为车掌菜单按钮 " + action + " 设置物品元数据");
    }
    return stack;
  }

  static String modelKey(Action action, View view) {
    return switch (action) {
      case SEAT -> "drive/guard/seat";
      case CALL -> "drive/guard/buzzer";
      case REPORT -> "drive/guard/report" + (view.reportUsed() ? "_used" : "");
      case END -> view.endConfirm() ? "drive/panel/end_confirm" : "drive/panel/end";
    };
  }

  static Material materialOf(Action action, View view) {
    return switch (action) {
      case SEAT -> Material.LIGHT_BLUE_DYE;
      case CALL -> Material.YELLOW_DYE;
      case REPORT -> view.reportUsed() ? Material.GRAY_DYE : Material.PURPLE_DYE;
      case END -> Material.RED_DYE;
    };
  }

  private static String nameKey(Action action, View view) {
    String base = "drive.guard.menu.item.";
    return switch (action) {
      case SEAT -> base + "seat";
      case CALL -> base + "call";
      case REPORT -> base + (view.reportUsed() ? "report-used" : "report");
      case END -> base + (view.endConfirm() ? "end-confirm" : "end");
    };
  }

  private static String hintId(Action action) {
    return switch (action) {
      case SEAT -> "seat";
      case CALL -> "call";
      case REPORT -> "report";
      case END -> "end";
    };
  }
}
