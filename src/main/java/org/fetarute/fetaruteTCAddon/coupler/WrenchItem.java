package org.fetarute.fetaruteTCAddon.coupler;

import java.util.List;
import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.fetarute.fetaruteTCAddon.drive.inventory.HotbarItems;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 车钩扳手：靠物品上的 PDC 标记识别，改名、附魔不影响，普通绊线钩不会被误认。物品模型写成 {@code fetarute:coupler/wrench}
 * 给材质包用，没有材质包时显示为绊线钩。
 */
public final class WrenchItem {

  /** 物品模型（材质包里的路径）。 */
  static final String MODEL_KEY = "coupler/wrench";

  private final NamespacedKey markKey;
  private final LocaleManager locale;

  public WrenchItem(Plugin plugin, LocaleManager locale) {
    Objects.requireNonNull(plugin, "plugin");
    this.markKey = new NamespacedKey(plugin, "coupler_wrench");
    this.locale = Objects.requireNonNull(locale, "locale");
  }

  /** 新的一把扳手。 */
  public ItemStack create() {
    ItemStack item = new ItemStack(Material.TRIPWIRE_HOOK);
    ItemMeta meta = item.getItemMeta();
    meta.displayName(locale.component("coupler.item.name"));
    meta.lore(
        List.of(
            locale.component("coupler.item.lore-1"),
            locale.component("coupler.item.lore-2"),
            locale.component("coupler.item.lore-3"),
            locale.component("coupler.item.lore-4")));
    meta.setItemModel(new NamespacedKey(HotbarItems.MODEL_NAMESPACE, MODEL_KEY));
    meta.getPersistentDataContainer().set(markKey, PersistentDataType.BYTE, (byte) 1);
    item.setItemMeta(meta);
    return item;
  }

  /** 是不是扳手。 */
  public boolean isWrench(ItemStack item) {
    return item != null
        && item.getType() == Material.TRIPWIRE_HOOK
        && item.hasItemMeta()
        && item.getItemMeta().getPersistentDataContainer().has(markKey, PersistentDataType.BYTE);
  }
}
