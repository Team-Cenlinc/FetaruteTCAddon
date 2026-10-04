package org.fetarute.fetaruteTCAddon.drive.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.fetarute.fetaruteTCAddon.drive.inventory.HotbarItems;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 停车后菜单里的按钮物品。
 *
 * <p>默认用染料按颜色区分，没有材质包的玩家也能用；同时设置 {@code item_model}，装有材质包的客户端显示 {@code fetarute:drive/panel/…} 的贴图。
 * 选中的换向手柄按钮、正在接通的启动流程开关另加附魔光效：没有材质包时靠光效看出状态。
 */
public final class DriveMenuItems {

  private DriveMenuItems() {}

  /** 构建一个按钮。 */
  public static ItemStack build(LocaleManager locale, ButtonView view) {
    ItemStack stack = new ItemStack(materialOf(view));
    ItemMeta meta = stack.getItemMeta();
    meta.displayName(locale.component(nameKey(view)).decoration(TextDecoration.ITALIC, false));
    meta.lore(lore(locale, view));
    meta.setItemModel(
        new NamespacedKey(
            HotbarItems.MODEL_NAMESPACE,
            "drive/"
                + MenuLayout.modelKey(view.action(), view.active(), view.fault(), view.supply())));
    if ((view.action().isReverser() && view.active())
        || view.busy()
        || (view.action() == MenuAction.END_DRIVING && view.active())) {
      meta.setEnchantmentGlintOverride(true);
    }
    if (!stack.setItemMeta(meta)) {
      throw new IllegalStateException("无法为菜单按钮 " + view.action() + " 设置物品元数据");
    }
    return stack;
  }

  /** 按钮的默认物品材质。 */
  static Material materialOf(ButtonView view) {
    if (view.fault()) {
      return Material.RED_DYE;
    }
    return switch (view.action()) {
      case REVERSER_FORWARD -> Material.LIME_DYE;
      case REVERSER_NEUTRAL -> Material.GRAY_DYE;
      case REVERSER_REVERSE -> Material.LIGHT_BLUE_DYE;
      case DOOR_LEFT, DOOR_RIGHT -> view.active() ? Material.LIME_DYE : Material.GRAY_DYE;
      case KEY, POWER, BREAKER, AUX, COMPRESSOR, BRAKE_TEST -> view.busy()
          ? Material.YELLOW_DYE
          : view.active() ? Material.LIME_DYE : Material.GRAY_DYE;
      case START -> view.busy()
          ? Material.YELLOW_DYE
          : view.active() ? Material.LIME_DYE : Material.RED_DYE;
      case PARKING_BRAKE -> view.active() ? Material.LIME_DYE : Material.ORANGE_DYE;
      case DRIVING_MODE -> view.active() ? Material.CYAN_DYE : Material.LIME_DYE;
      case END_DRIVING -> Material.RED_DYE;
      case TASK_CARD -> Material.BOOK;
      case DOOR_BYPASS -> view.active() ? Material.ORANGE_DYE : Material.GRAY_DYE;
    };
  }

  /** 按钮名称的语言键。 */
  static String nameKey(ButtonView view) {
    String state = view.fault() ? "fault" : view.busy() ? "busy" : view.active() ? "on" : "off";
    return "drive.menu.item."
        + switch (view.action()) {
          case REVERSER_FORWARD -> "reverser-forward";
          case REVERSER_NEUTRAL -> "reverser-neutral";
          case REVERSER_REVERSE -> "reverser-reverse";
          case DOOR_LEFT -> view.active() ? "door-left-open" : "door-left-closed";
          case DOOR_RIGHT -> view.active() ? "door-right-open" : "door-right-closed";
          case KEY -> "key-" + state;
          case POWER -> MenuLayout.powerKey(view.supply()) + "-" + state;
          case BREAKER -> "breaker-" + state;
          case AUX -> "aux-" + state;
          case START -> "start-" + state;
          case COMPRESSOR -> "compressor-" + state;
          case PARKING_BRAKE -> view.active() ? "parking-released" : "parking-applied";
          case BRAKE_TEST -> "brake-test-" + state;
          case DRIVING_MODE -> view.active() ? "driving-mode-ato" : "driving-mode-manual";
          case END_DRIVING -> view.active() ? "end-driving-confirm" : "end-driving";
          case TASK_CARD -> "task-card-free";
          case DOOR_BYPASS -> view.active() ? "door-bypass-on" : "door-bypass-off";
        };
  }

  private static List<Component> lore(LocaleManager locale, ButtonView view) {
    List<Component> lines = new ArrayList<>();
    if (view.action().isReverser() && view.active()) {
      lines.add(line(locale, "drive.menu.hint.current", Map.of()));
    }
    if (view.busy() && view.remainingSeconds() >= 0) {
      lines.add(
          line(
              locale,
              "drive.menu.hint.remaining",
              Map.of("seconds", String.valueOf(view.remainingSeconds()))));
    }
    if (view.detailKey() != null) {
      lines.add(line(locale, view.detailKey(), view.detailValues()));
    }
    lines.add(line(locale, hintKey(view), Map.of()));
    lines.add(line(locale, "drive.menu.hint.stopped", Map.of()));
    return lines;
  }

  static String hintKey(ButtonView view) {
    return switch (view.action()) {
      case REVERSER_FORWARD, REVERSER_NEUTRAL, REVERSER_REVERSE -> "drive.menu.hint.reverser";
      case DOOR_LEFT, DOOR_RIGHT -> "drive.menu.hint.door";
      case KEY, POWER, BREAKER, AUX -> view.clickable()
          ? "drive.menu.hint.setup-switch"
          : "drive.menu.hint.setup-lamp";
      case START -> "drive.menu.hint.start";
      case COMPRESSOR -> view.clickable()
          ? "drive.menu.hint.compressor-switch"
          : "drive.menu.hint.compressor-auto";
      case PARKING_BRAKE -> "drive.menu.hint.parking";
      case BRAKE_TEST -> "drive.menu.hint.brake-test";
      case DRIVING_MODE -> "drive.menu.hint.driving-mode";
      case END_DRIVING -> view.active()
          ? "drive.menu.hint.end-driving-confirm"
          : "drive.menu.hint.end-driving";
      case TASK_CARD -> "drive.menu.hint.task-card";
      case DOOR_BYPASS -> "drive.menu.hint.door-bypass";
    };
  }

  /** 任务卡：书本图标，写卡片上的各行；不可点击。 */
  public static ItemStack taskCard(LocaleManager locale, TaskCard.Card card) {
    ItemStack stack = new ItemStack(Material.BOOK);
    ItemMeta meta = stack.getItemMeta();
    meta.displayName(locale.component(card.titleKey()).decoration(TextDecoration.ITALIC, false));
    List<Component> lines = new ArrayList<>();
    for (TaskCard.Line line : card.lines()) {
      lines.add(line(locale, line.key(), line.values()));
    }
    meta.lore(lines);
    meta.setItemModel(
        new NamespacedKey(
            HotbarItems.MODEL_NAMESPACE,
            "drive/" + MenuLayout.modelKey(MenuAction.TASK_CARD, false, PowerSupply.PTG5)));
    if (!stack.setItemMeta(meta)) {
      throw new IllegalStateException("无法为任务卡设置物品元数据");
    }
    return stack;
  }

  /** 分组之间的灰色玻璃板：没有名称与说明，悬停不显示提示框。 */
  public static ItemStack divider() {
    ItemStack stack = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
    ItemMeta meta = stack.getItemMeta();
    meta.displayName(Component.empty());
    meta.setHideTooltip(true);
    if (!stack.setItemMeta(meta)) {
      throw new IllegalStateException("无法为菜单玻璃板设置物品元数据");
    }
    return stack;
  }

  private static Component line(LocaleManager locale, String key, Map<String, String> values) {
    return locale.component(key, values).decoration(TextDecoration.ITALIC, false);
  }
}
