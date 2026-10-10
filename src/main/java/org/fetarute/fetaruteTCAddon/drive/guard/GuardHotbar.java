package org.fetarute.fetaruteTCAddon.drive.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.fetarute.fetaruteTCAddon.drive.inventory.HotbarItems;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 车掌的快捷栏：九格按钮，画法沿用驾驶台面板（钢灰图形配点缀色，接通时亮、断开时暗，金框表示已确认或已拉下）。
 *
 * <p>左右车门直接用驾驶台的车门贴图（{@code fetarute:drive/panel/door_*}），其余是 {@code fetarute:drive/guard/<键>}。
 * 没装材质包时按染料颜色区分，“亮起”加附魔光效。5、8 两格空着。物品只存在于发往客户端的数据包里。
 */
public final class GuardHotbar {

  /** 快捷栏上的按钮。 */
  public enum Button {
    DOOR_LEFT(0),
    DOOR_RIGHT(1),
    DOOR_CLOSE(2),
    REPORT(3),
    CONFIRM(5),
    BUZZER(6),
    EMERGENCY(8);

    private final int slot;

    Button(int slot) {
      this.slot = slot;
    }

    public int slot() {
      return slot;
    }

    /** 这一格是哪个按钮；空格为空。 */
    public static Optional<Button> fromSlot(int slot) {
      for (Button button : values()) {
        if (button.slot == slot) {
          return Optional.of(button);
        }
      }
      return Optional.empty();
    }
  }

  /** 出发确认按钮的三种样子。 */
  public enum ConfirmLamp {
    /** 出站未开放：信号机亮红灯。 */
    STOP,
    /** 出站开放：信号机亮绿灯。 */
    GO,
    /** 已确认：绿灯加金框。 */
    DONE
  }

  /**
   * 按钮此刻的样子。
   *
   * @param leftOpen 行进方向左侧的门开着
   * @param rightOpen 行进方向右侧的门开着
   * @param closing 关门动画在放
   * @param reportUsed 本站报告次数已用完
   * @param confirm 出发确认按钮
   * @param ringing 发车铃正在响
   * @param emergency 紧急停车已拉下
   */
  public record View(
      boolean leftOpen,
      boolean rightOpen,
      boolean closing,
      boolean reportUsed,
      ConfirmLamp confirm,
      boolean ringing,
      boolean emergency) {

    public static final View IDLE =
        new View(false, false, false, false, ConfirmLamp.STOP, false, false);
  }

  private GuardHotbar() {}

  /** 按快捷栏槽位顺序构建九格。 */
  public static List<ItemStack> build(LocaleManager locale, View view) {
    List<ItemStack> items = new ArrayList<>(9);
    for (int slot = 0; slot < 9; slot++) {
      items.add(
          Button.fromSlot(slot)
              .map(button -> build(locale, button, view))
              .orElseGet(() -> new ItemStack(Material.AIR)));
    }
    return items;
  }

  static ItemStack build(LocaleManager locale, Button button, View view) {
    ItemStack stack = new ItemStack(materialOf(button, view));
    ItemMeta meta = stack.getItemMeta();
    meta.displayName(
        locale.component(nameKey(button, view)).decoration(TextDecoration.ITALIC, false));
    meta.lore(
        List.of(
            locale
                .component("drive.guard.button.hint." + hintId(button))
                .decoration(TextDecoration.ITALIC, false)));
    meta.setItemModel(new NamespacedKey(HotbarItems.MODEL_NAMESPACE, modelKey(button, view)));
    if (lit(button, view)) {
      meta.setEnchantmentGlintOverride(true);
    }
    if (!stack.setItemMeta(meta)) {
      throw new IllegalStateException("无法为车掌按钮 " + button + " 设置物品元数据");
    }
    return stack;
  }

  /** 贴图键（{@code fetarute:} 之后的部分）。 */
  static String modelKey(Button button, View view) {
    return switch (button) {
      case DOOR_LEFT -> "drive/panel/door_l" + (view.leftOpen() ? "_on" : "_off");
      case DOOR_RIGHT -> "drive/panel/door_r" + (view.rightOpen() ? "_on" : "_off");
      case DOOR_CLOSE -> "drive/guard/door_close" + (view.closing() ? "_on" : "");
      case REPORT -> "drive/guard/report" + (view.reportUsed() ? "_used" : "");
      case CONFIRM -> "drive/guard/confirm_"
          + switch (view.confirm()) {
            case STOP -> "stop";
            case GO -> "go";
            case DONE -> "done";
          };
      case BUZZER -> "drive/guard/buzzer" + (view.ringing() ? "_on" : "");
      case EMERGENCY -> "drive/guard/emergency" + (view.emergency() ? "_on" : "");
    };
  }

  /** 没装材质包时的染料。 */
  static Material materialOf(Button button, View view) {
    return switch (button) {
      case DOOR_LEFT -> view.leftOpen() ? Material.LIME_DYE : Material.GRAY_DYE;
      case DOOR_RIGHT -> view.rightOpen() ? Material.LIME_DYE : Material.GRAY_DYE;
      case DOOR_CLOSE -> view.closing() ? Material.ORANGE_DYE : Material.GRAY_DYE;
      case REPORT -> view.reportUsed() ? Material.GRAY_DYE : Material.PURPLE_DYE;
      case CONFIRM -> view.confirm() == ConfirmLamp.STOP ? Material.RED_DYE : Material.LIME_DYE;
      case BUZZER -> Material.YELLOW_DYE;
      case EMERGENCY -> Material.RED_DYE;
    };
  }

  /** 没装材质包时用附魔光效表示“亮起”的状态。 */
  static boolean lit(Button button, View view) {
    return switch (button) {
      case CONFIRM -> view.confirm() == ConfirmLamp.DONE;
      case BUZZER -> view.ringing();
      case EMERGENCY -> view.emergency();
      default -> false;
    };
  }

  private static String nameKey(Button button, View view) {
    String base = "drive.guard.button.";
    return switch (button) {
      case DOOR_LEFT -> base + (view.leftOpen() ? "door-left-open" : "door-left");
      case DOOR_RIGHT -> base + (view.rightOpen() ? "door-right-open" : "door-right");
      case DOOR_CLOSE -> base + (view.closing() ? "door-close-closing" : "door-close");
      case REPORT -> base + (view.reportUsed() ? "report-used" : "report");
      case CONFIRM -> base
          + switch (view.confirm()) {
            case STOP -> "confirm-stop";
            case GO -> "confirm-go";
            case DONE -> "confirm-done";
          };
      case BUZZER -> base + (view.ringing() ? "buzzer-ringing" : "buzzer");
      case EMERGENCY -> base + (view.emergency() ? "emergency-on" : "emergency");
    };
  }

  private static String hintId(Button button) {
    return switch (button) {
      case DOOR_LEFT, DOOR_RIGHT -> "door";
      case DOOR_CLOSE -> "door-close";
      case REPORT -> "report";
      case CONFIRM -> "confirm";
      case BUZZER -> "buzzer";
      case EMERGENCY -> "emergency";
    };
  }
}
