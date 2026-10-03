package org.fetarute.fetaruteTCAddon.drive.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.fetarute.fetaruteTCAddon.drive.dynamics.Notch;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 快捷栏里显示的驾驶物品。
 *
 * <p>这些物品只存在于发往客户端的数据包里，不会放进服务器端的玩家背包。默认用染料按颜色区分档位（牵引绿、惰行灰、制动橙、紧急制动红）， 没有材质包的玩家也能看出区别；同时设置 {@code
 * item_model} 组件，装有材质包的客户端会显示 {@code fetarute:drive/<档位小写>} 对应的模型。
 */
public final class HotbarItems {

  /** 材质包模型键的命名空间。 */
  public static final String MODEL_NAMESPACE = "fetarute";

  /** 档位名称的语言键前缀，完整键为前缀加档位小写。 */
  public static final String NAME_KEY_PREFIX = "drive.notch.";

  private HotbarItems() {}

  /** 按快捷栏槽位顺序构建九格驾驶物品。 */
  public static List<ItemStack> build(LocaleManager locale) {
    List<ItemStack> items = new ArrayList<>(Notch.SLOT_COUNT);
    for (int slot = 0; slot < Notch.SLOT_COUNT; slot++) {
      Notch notch = Notch.fromSlot(slot).orElseThrow();
      items.add(build(locale, notch));
    }
    return items;
  }

  /** 构建单个档位的驾驶物品。 */
  public static ItemStack build(LocaleManager locale, Notch notch) {
    String id = notch.name().toLowerCase(Locale.ROOT);
    ItemStack stack = new ItemStack(materialOf(notch));
    ItemMeta meta = stack.getItemMeta();
    Component name =
        locale.component(NAME_KEY_PREFIX + id).decoration(TextDecoration.ITALIC, false);
    meta.displayName(name);
    meta.setItemModel(new NamespacedKey(MODEL_NAMESPACE, "drive/" + id));
    if (!stack.setItemMeta(meta)) {
      throw new IllegalStateException("无法为档位 " + notch + " 设置物品元数据");
    }
    return stack;
  }

  /** 档位对应的默认物品材质。 */
  public static Material materialOf(Notch notch) {
    return switch (notch.kind()) {
      case TRACTION -> Material.LIME_DYE;
      case COAST -> Material.GRAY_DYE;
      case BRAKE -> Material.ORANGE_DYE;
      case EMERGENCY -> Material.RED_DYE;
    };
  }
}
