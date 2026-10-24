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
import java.util.function.Function;
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
 * 实体操作许可证：一本成书，把插件里的记录印出来——持证人、证号，驾驶、车掌、信号员三个岗位各自取得了什么、哪天取得。只是凭证，权限按记录给；丢了再印一本即可。
 *
 * <p>证号由玩家 UUID 得出，固定不变，不另外存储。书上记下持证人与版式版本：换发时认得出背包里哪几本是这名玩家的证，旧版式（改名为操作许可证之前印的驾驶证）上线时原地换成新版。
 */
public final class LicenseCardItem {

  /** 现在的版式版本：旧版驾驶证没有这一项，算 1。 */
  public static final int CURRENT_VERSION = 2;

  /** 成书一页最多 14 行。 */
  static final int PAGE_LINES = 14;

  /** 日期按服务器时区显示。 */
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

  /** 一项资格在证上的写法。 */
  public record Entry(String name, Instant grantedAt) {}

  /**
   * 证上一个岗位的一栏。
   *
   * @param post 岗位
   * @param entries 取得的资格（只印最高的一项）；没有时印“未取得”
   */
  public record Section(LicenseClass.Post post, List<Entry> entries) {
    public Section {
      entries = List.copyOf(entries);
    }
  }

  /**
   * 证面上的一行：语言键与占位符。
   *
   * @param key 语言键
   * @param values 占位符
   */
  record Line(String key, Map<String, String> values) {}

  private final NamespacedKey holderKey;
  private final NamespacedKey versionKey;

  public LicenseCardItem(Plugin plugin) {
    this.holderKey = new NamespacedKey(plugin, "license_holder");
    this.versionKey = new NamespacedKey(plugin, "license_card_version");
  }

  /** 证号：{@code FTA-} 加玩家 UUID 的前 8 位（大写），同一名玩家永远相同。 */
  public static String number(UUID playerId) {
    return "FTA-" + playerId.toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
  }

  /**
   * 证上的各栏：按岗位先后（驾驶、车掌、信号员）。每个岗位只印持有的最高一项（按配置先后，驾驶即最高一级）。
   * 一个岗位什么都没取得、也没开放考试时整栏不印；配置里已没有的资格不印（也不再给权限）。
   *
   * @param config 资格配置
   * @param held 持有的资格
   * @param displayName 资格在证上的写法（驾驶写“第 N 级 · 名称”）
   */
  public static List<Section> sections(
      LicenseConfig config, List<LicenseRecord> held, Function<String, String> displayName) {
    List<Section> sections = new ArrayList<>();
    for (LicenseClass.Post post : LicenseClass.Post.values()) {
      List<LicenseClass> classes =
          config.classes().stream().filter(license -> license.post() == post).toList();
      List<Entry> entries = new ArrayList<>();
      for (LicenseClass license : classes) {
        held.stream()
            .filter(record -> record.classId().equals(license.id()))
            .findFirst()
            .ifPresent(
                record ->
                    entries.add(new Entry(displayName.apply(license.id()), record.grantedAt())));
      }
      // 每个岗位只印持有的最高一项（按配置先后），第一页才放得下
      if (entries.size() > 1) {
        Entry highest = entries.get(entries.size() - 1);
        entries.clear();
        entries.add(highest);
      }
      boolean offered = classes.stream().anyMatch(LicenseClass::enabled);
      if (!entries.isEmpty() || offered) {
        sections.add(new Section(post, entries));
      }
    }
    return sections;
  }

  /** 证面第一页的各行：标题、持证人、证号、分隔线，然后各岗位一栏（栏名一行，取得的资格或“未取得”一行）。 */
  static List<Line> firstPage(String playerName, String serial, List<Section> sections) {
    List<Line> lines = new ArrayList<>();
    lines.add(new Line("drive.license.card.heading", Map.of()));
    lines.add(new Line("drive.license.card.holder", Map.of("player", playerName)));
    lines.add(new Line("drive.license.card.serial", Map.of("serial", serial)));
    lines.add(new Line("drive.license.card.rule", Map.of()));
    for (Section section : sections) {
      lines.add(
          new Line(
              "drive.license.card.post." + section.post().name().toLowerCase(Locale.ROOT),
              Map.of()));
      if (section.entries().isEmpty()) {
        lines.add(new Line("drive.license.card.none", Map.of()));
      }
      for (Entry entry : section.entries()) {
        lines.add(
            new Line(
                "drive.license.card.entry",
                Map.of("name", entry.name(), "date", DATE.format(entry.grantedAt()))));
      }
    }
    return lines;
  }

  /** 按持有的资格印一本操作许可证（现行版式）。 */
  public ItemStack create(
      LocaleManager locale, UUID playerId, String playerName, List<Section> sections) {
    ItemStack stack = new ItemStack(Material.WRITTEN_BOOK);
    BookMeta meta = (BookMeta) stack.getItemMeta();
    meta.title(locale.component("drive.license.card.title"));
    meta.author(locale.component("drive.license.card.author"));
    meta.setGeneration(BookMeta.Generation.ORIGINAL);
    List<Component> lines = new ArrayList<>();
    for (Line line : firstPage(playerName, number(playerId), sections)) {
      lines.add(locale.component(line.key(), line.values()));
    }
    meta.addPages(Component.join(JoinConfiguration.newlines(), lines));
    meta.addPages(locale.component("drive.license.card.notice"));
    meta.getPersistentDataContainer()
        .set(holderKey, PersistentDataType.STRING, playerId.toString());
    meta.getPersistentDataContainer().set(versionKey, PersistentDataType.INTEGER, CURRENT_VERSION);
    if (!stack.setItemMeta(meta)) {
      throw new IllegalStateException("无法为操作许可证设置物品元数据");
    }
    return stack;
  }

  /** 这件物品是不是操作许可证（含旧版驾驶证）；是的话读出持证人。 */
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

  /** 这本证是旧版式（版式版本低于 {@link #CURRENT_VERSION}，含改名前印的驾驶证）。不是证时为假。 */
  public boolean outdated(ItemStack stack) {
    if (holderOf(stack).isEmpty()) {
      return false;
    }
    Integer version =
        stack
            .getItemMeta()
            .getPersistentDataContainer()
            .get(versionKey, PersistentDataType.INTEGER);
    return version == null || version < CURRENT_VERSION;
  }
}
