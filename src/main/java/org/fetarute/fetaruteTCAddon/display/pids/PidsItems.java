package org.fetarute.fetaruteTCAddon.display.pids;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.fetarute.fetaruteTCAddon.display.pids.layout.PidsLayout;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 站台屏的两件工具：安装纸与配置棍。
 *
 * <p>都靠物品上的 PDC 标记识别，不看名称与材质之外的东西：改名、附魔都不影响，普通纸、普通木棍不会被误认。
 */
public final class PidsItems {

  private final NamespacedKey installKey;
  private final NamespacedKey stickKey;
  private final LocaleManager locale;

  public PidsItems(Plugin plugin, LocaleManager locale) {
    Objects.requireNonNull(plugin, "plugin");
    this.installKey = new NamespacedKey(plugin, "pids_install_layout");
    this.stickKey = new NamespacedKey(plugin, "pids_stick");
    this.locale = Objects.requireNonNull(locale, "locale");
  }

  /** 安装纸：记着布局 ID，放进空展示框墙即按布局尺寸铺满。 */
  public ItemStack installPaper(PidsLayout layout) {
    ItemStack paper = new ItemStack(Material.PAPER);
    ItemMeta meta = paper.getItemMeta();
    Map<String, String> placeholders =
        Map.of("layout", layout.name(), "size", layout.tileRows() + "×" + layout.tileCols());
    meta.displayName(locale.component("pids.item.install.name", placeholders));
    meta.lore(
        List.of(
            locale.component("pids.item.install.lore-1", placeholders),
            locale.component("pids.item.install.lore-2", placeholders)));
    meta.getPersistentDataContainer().set(installKey, PersistentDataType.STRING, layout.id());
    paper.setItemMeta(meta);
    return paper;
  }

  /** 配置棍。 */
  public ItemStack stick() {
    ItemStack stick = new ItemStack(Material.STICK);
    ItemMeta meta = stick.getItemMeta();
    meta.displayName(locale.component("pids.item.stick.name"));
    meta.lore(
        List.of(
            locale.component("pids.item.stick.lore-1"),
            locale.component("pids.item.stick.lore-2"),
            locale.component("pids.item.stick.lore-3")));
    meta.getPersistentDataContainer().set(stickKey, PersistentDataType.BYTE, (byte) 1);
    stick.setItemMeta(meta);
    return stick;
  }

  /** 安装纸上记的布局 ID；不是安装纸时为空。 */
  public Optional<String> installLayout(ItemStack item) {
    if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        item.getItemMeta().getPersistentDataContainer().get(installKey, PersistentDataType.STRING));
  }

  /** 是否为配置棍。 */
  public boolean isStick(ItemStack item) {
    return item != null
        && item.getType() == Material.STICK
        && item.hasItemMeta()
        && item.getItemMeta().getPersistentDataContainer().has(stickKey, PersistentDataType.BYTE);
  }
}
