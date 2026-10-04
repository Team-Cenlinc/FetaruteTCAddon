package org.fetarute.fetaruteTCAddon.drive.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.fetarute.fetaruteTCAddon.drive.inventory.HotbarItems;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 停车后菜单里的表计与故障指示物品。
 *
 * <p>压力表用耐久条当指针（{@code max_damage}/{@code damage} 数据组件），名称与说明写读数；物品堆叠上限设为 1，耐久与堆叠不冲突。
 * 没有材质包时按区段用绿、黄、红色玻璃板兜底，装有材质包时显示 {@code fetarute:drive/panel/gauge_…} 的贴图。菜单是虚拟界面，物品不会进入玩家背包。
 */
public final class DriveGaugeItems {

  private DriveGaugeItems() {}

  /** 构建一块压力表。 */
  public static ItemStack gauge(LocaleManager locale, GaugeView view) {
    ItemStack stack = new ItemStack(materialOf(view.band()));
    ItemMeta meta = stack.getItemMeta();
    meta.displayName(
        line(locale, view.nameKey(), Map.of("value", String.valueOf(view.roundedKpa()))));
    List<Component> lore = new ArrayList<>();
    lore.add(
        line(
            locale,
            "drive.menu.gauge.range",
            Map.of("range", String.valueOf(Math.round(view.rangeKpa())))));
    lore.add(line(locale, view.detailKey(), view.detailValues()));
    lore.add(line(locale, "drive.menu.hint.gauge", Map.of()));
    meta.lore(lore);
    meta.setItemModel(new NamespacedKey(HotbarItems.MODEL_NAMESPACE, "drive/" + view.modelKey()));
    meta.setMaxStackSize(1);
    if (meta instanceof Damageable damageable) {
      damageable.setMaxDamage(GaugeView.MAX_DAMAGE);
      damageable.setDamage(view.damage());
    }
    if (!stack.setItemMeta(meta)) {
      throw new IllegalStateException("无法为表计 " + view.indicator() + " 设置物品元数据");
    }
    return stack;
  }

  /** 构建故障指示。 */
  public static ItemStack faults(LocaleManager locale, FaultPanelView view) {
    ItemStack stack =
        new ItemStack(
            view.lit() ? Material.RED_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE);
    ItemMeta meta = stack.getItemMeta();
    meta.displayName(
        line(locale, view.nameKey(), Map.of("count", String.valueOf(view.faults().size()))));
    List<Component> lore = new ArrayList<>();
    for (String key : view.lineKeys()) {
      lore.add(line(locale, key, Map.of()));
    }
    lore.add(line(locale, "drive.menu.hint.faults", Map.of()));
    meta.lore(lore);
    meta.setItemModel(new NamespacedKey(HotbarItems.MODEL_NAMESPACE, "drive/" + view.modelKey()));
    if (!view.faults().isEmpty()) {
      meta.setEnchantmentGlintOverride(true);
    }
    if (!stack.setItemMeta(meta)) {
      throw new IllegalStateException("无法为故障指示设置物品元数据");
    }
    return stack;
  }

  /** 压力表的兜底材质：按区段着色。 */
  static Material materialOf(GaugeView.Band band) {
    return switch (band) {
      case NORMAL -> Material.LIME_STAINED_GLASS_PANE;
      case WARNING -> Material.YELLOW_STAINED_GLASS_PANE;
      case ALARM -> Material.RED_STAINED_GLASS_PANE;
    };
  }

  private static Component line(LocaleManager locale, String key, Map<String, String> values) {
    return locale.component(key, values).decoration(TextDecoration.ITALIC, false);
  }
}
