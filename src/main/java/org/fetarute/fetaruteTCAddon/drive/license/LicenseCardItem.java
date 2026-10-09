package org.fetarute.fetaruteTCAddon.drive.license;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;

/**
 * 实体驾驶证：一本成书，把插件里的记录印出来——持证人、证号、准驾等级与附注（车掌）及取得日期。只是凭证，驾驶权限按记录给；丢了再印一本即可。
 *
 * <p>证号由玩家 UUID 得出，固定不变，不另外存储。书上记下持证人，换发时认得出背包里哪几本是这名玩家的旧证。
 */
public final class LicenseCardItem {

  /** 日期按服务器时区显示。 */
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

  /** 一级准驾等级或一项附注在证上的写法。 */
  public record Entry(String name, Instant grantedAt) {}

  private final NamespacedKey holderKey;

  public LicenseCardItem(Plugin plugin) {
    this.holderKey = new NamespacedKey(plugin, "license_holder");
  }

  /** 证号：{@code FTA-} 加玩家 UUID 的前 8 位（大写），同一名玩家永远相同。 */
  public static String number(UUID playerId) {
    return "FTA-" + playerId.toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
  }

  /**
   * 按持有的等级与附注印一本驾驶证。
   *
   * @param entries 准驾等级
   * @param endorsements 附注；没有时不印这一栏
   */
  public ItemStack create(
      LocaleManager locale,
      UUID playerId,
      String playerName,
      List<Entry> entries,
      List<Entry> endorsements) {
    ItemStack stack = new ItemStack(Material.WRITTEN_BOOK);
    BookMeta meta = (BookMeta) stack.getItemMeta();
    meta.title(locale.component("drive.license.card.title"));
    meta.author(locale.component("drive.license.card.author"));
    meta.setGeneration(BookMeta.Generation.ORIGINAL);
    List<Component> lines = new ArrayList<>();
    lines.add(locale.component("drive.license.card.heading"));
    lines.add(locale.component("drive.license.card.holder", Map.of("player", playerName)));
    lines.add(locale.component("drive.license.card.serial", Map.of("serial", number(playerId))));
    lines.add(Component.empty());
    // 只持有附注（例如只考了车掌）时不印空着的“准驾等级”一栏。
    if (!entries.isEmpty()) {
      lines.add(locale.component("drive.license.card.classes"));
      addEntries(locale, lines, entries);
    }
    if (!endorsements.isEmpty()) {
      lines.add(locale.component("drive.license.card.endorsements"));
      addEntries(locale, lines, endorsements);
    }
    meta.addPages(Component.join(JoinConfiguration.newlines(), lines));
    meta.addPages(locale.component("drive.license.card.notice"));
    meta.getPersistentDataContainer()
        .set(holderKey, PersistentDataType.STRING, playerId.toString());
    if (!stack.setItemMeta(meta)) {
      throw new IllegalStateException("无法为驾驶证设置物品元数据");
    }
    return stack;
  }

  private static void addEntries(LocaleManager locale, List<Component> lines, List<Entry> entries) {
    for (Entry entry : entries) {
      lines.add(
          locale.component(
              "drive.license.card.class",
              Map.of("name", entry.name(), "date", DATE.format(entry.grantedAt()))));
    }
  }

  /** 这件物品是不是驾驶证；是的话读出持证人。 */
  public Optional<UUID> holderOf(ItemStack stack) {
    if (stack == null || stack.getType() != Material.WRITTEN_BOOK) {
      return Optional.empty();
    }
    org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
    if (meta == null) {
      return Optional.empty();
    }
    String holder = meta.getPersistentDataContainer().get(holderKey, PersistentDataType.STRING);
    if (holder == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(holder));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }
}
