package org.fetarute.fetaruteTCAddon.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** 简易语言管理器，负责加载 lang 目录下的 YAML 并用 MiniMessage 渲染。 */
public final class LocaleManager {

  private static final String DEFAULT_LOCALE = "zh_CN";

  private final LocaleAccess access;
  private final MiniMessage miniMessage = MiniMessage.miniMessage();
  private String currentLocale;
  private YamlConfiguration messages;
  private Component prefix = Component.empty();

  public LocaleManager(JavaPlugin plugin, String localeTag, LoggerManager loggerManager) {
    this(
        new LocaleAccess(plugin.getDataFolder(), loggerManager, plugin::saveResource),
        localeTag,
        loggerManager);
  }

  LocaleManager(LocaleAccess access, String localeTag, LoggerManager logger) {
    this.access = access;
    this.currentLocale = normalizeLocale(localeTag);
  }

  /** 重新加载当前语言。 */
  public void reload() {
    loadLocale(currentLocale);
  }

  /**
   * 重新加载并切换到指定语言。
   *
   * @param localeTag 新的语言标签
   */
  public void reload(String localeTag) {
    loadLocale(normalizeLocale(localeTag));
  }

  /**
   * 获取指定键的文本组件。
   *
   * @param key 语言键
   * @param placeholders 占位符，如 Map.of("player", "Steve")
   * @return 渲染后的 Adventure 组件
   */
  public Component component(String key, Map<String, String> placeholders) {
    if (messages == null) {
      reload();
    }
    if (messages == null) {
      return Component.empty();
    }
    String raw = messages.getString(key);
    if (raw == null) {
      logMissingKey(key);
      String fallback = messages.getString("error.missing-key", "<prefix> 缺少语言键 <red><key></red>");
      if (fallback == null) {
        fallback = "<prefix> 缺少语言键 <red><key></red>";
      }
      return miniMessage.deserialize(fallback, buildResolvers(Map.of("key", key)));
    }
    TagResolver resolver = buildResolvers(placeholders);
    return miniMessage.deserialize(raw, resolver);
  }

  /**
   * 获取指定键的文本组件，并允许传入额外的 TagResolver（用于 clickable/hover 等富交互组件占位符）。
   *
   * <p>典型场景：某些提示需要把坐标做成点击传送，但占位符不再是纯字符串。
   *
   * @param key 语言键
   * @param extraResolver 额外的 TagResolver（可为 null）
   * @return 渲染后的 Adventure 组件
   */
  public Component component(String key, TagResolver extraResolver) {
    if (messages == null) {
      reload();
    }
    if (messages == null) {
      return Component.empty();
    }
    String raw = messages.getString(key);
    if (raw == null) {
      logMissingKey(key);
      String fallback = messages.getString("error.missing-key", "<prefix> 缺少语言键 <red><key></red>");
      if (fallback == null) {
        fallback = "<prefix> 缺少语言键 <red><key></red>";
      }
      TagResolver resolver =
          TagResolver.builder()
              .resolver(Placeholder.component("prefix", prefix))
              .resolver(Placeholder.unparsed("key", key))
              .resolver(extraResolver == null ? TagResolver.empty() : extraResolver)
              .build();
      return miniMessage.deserialize(fallback, resolver);
    }
    TagResolver resolver =
        TagResolver.builder()
            .resolver(Placeholder.component("prefix", prefix))
            .resolver(extraResolver == null ? TagResolver.empty() : extraResolver)
            .build();
    return miniMessage.deserialize(raw, resolver);
  }

  public Component component(String key) {
    return component(key, Collections.emptyMap());
  }

  /**
   * 获取枚举值的本地化文本（用于命令输出展示）。
   *
   * <p>与 {@link #component(String, Map)} 不同：此方法返回的是“纯文本字符串”，适合塞到其他模板的占位符中。 语言文件中对应键建议只写纯文本（不要写
   * MiniMessage 标签），避免被当作普通字符输出。
   *
   * @param keyPrefix 枚举键前缀，如 {@code enum.route-pattern-type}
   * @param value 枚举值
   * @return 本地化文本；若缺失则回退到 {@code value.name()}
   */
  public String enumText(String keyPrefix, Enum<?> value) {
    if (value == null) {
      return "";
    }
    if (messages == null) {
      reload();
    }
    if (messages == null) {
      return value.name();
    }
    String key = keyPrefix + "." + value.name().toLowerCase(Locale.ROOT);
    String raw = messages.getString(key);
    return raw == null ? value.name() : raw;
  }

  /**
   * 获取语言文件中的字符串列表（用于书页模板、帮助文本等非 MiniMessage 场景）。
   *
   * <p>注意：此方法不做 MiniMessage 解析，也不做占位符替换；调用方可按需处理（例如把每行当作书本 page 的行）。 Bukkit 的 {@code getStringList}
   * 对缺失键会返回空列表，因此这里不返回 null。
   *
   * @param key 语言键
   * @return 字符串列表；缺失或非列表时返回空列表
   */
  public List<String> stringList(String key) {
    Objects.requireNonNull(key, "key");
    if (messages == null) {
      reload();
    }
    if (messages == null) {
      return List.of();
    }
    List<String> list = messages.getStringList(key);
    if (list.isEmpty()) {
      return List.of();
    }
    // Bukkit 可能返回可变 List；这里返回不可变副本，避免外部改写影响缓存。
    return List.copyOf(list);
  }

  public String getCurrentLocale() {
    return currentLocale;
  }

  private void loadLocale(String localeTag) {
    LocaleFile localeFile = prepareLocaleFile(localeTag);
    messages = YamlConfiguration.loadConfiguration(localeFile.file());
    currentLocale = localeFile.locale();
    prefix = parsePrefix(messages);
  }

  private LocaleFile prepareLocaleFile(String localeTag) {
    File langDir = new File(access.dataFolder(), "lang");
    if (!langDir.exists() && !langDir.mkdirs()) {
      access.logger().warn("无法创建语言目录 " + langDir.getAbsolutePath());
    }
    String effectiveLocale = localeTag;
    File localeFile = new File(langDir, effectiveLocale + ".yml");
    if (!localeFile.exists() && !copyBundledLocale(localeTag)) {
      Logger rawLogger = access.logger().underlying();
      rawLogger.warning("未找到语言文件 " + localeTag + "，回退到 " + DEFAULT_LOCALE);
      effectiveLocale = DEFAULT_LOCALE;
      copyBundledLocale(effectiveLocale);
      localeFile = new File(langDir, effectiveLocale + ".yml");
    }
    mergeLocaleDefaults(effectiveLocale, localeFile);
    return new LocaleFile(localeFile, effectiveLocale);
  }

  private boolean copyBundledLocale(String localeTag) {
    try {
      access.saveResource().save("lang/" + localeTag + ".yml", false);
      return true;
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  private Component parsePrefix(YamlConfiguration config) {
    if (config == null) {
      return Component.empty();
    }
    String rawPrefix = config.getString("prefix");
    if (rawPrefix == null || rawPrefix.isEmpty()) {
      return Component.empty();
    }
    return miniMessage.deserialize(rawPrefix);
  }

  private void mergeLocaleDefaults(String localeTag, File localeFile) {
    try (InputStream defaultStream =
        LocaleManager.class.getClassLoader().getResourceAsStream("lang/" + localeTag + ".yml")) {
      if (defaultStream == null) {
        access.logger().warn("未找到内置语言模板 lang/" + localeTag + ".yml");
        return;
      }
      YamlConfiguration defaults =
          YamlConfiguration.loadConfiguration(
              new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
      YamlConfiguration existing = YamlConfiguration.loadConfiguration(localeFile);
      List<String> added = new ArrayList<>();
      for (String key : defaults.getKeys(true)) {
        if (defaults.isConfigurationSection(key)) {
          continue;
        }
        if (!existing.contains(key)) {
          existing.set(key, defaults.get(key));
          added.add(key);
        }
      }
      if (!added.isEmpty()) {
        existing.save(localeFile);
        // 补全键属于诊断信息：默认不刷屏，仅在 debug.enabled=true 时输出。
        access.logger().debug("已补全语言键: " + String.join(", ", added));
      }
    } catch (IOException ex) {
      access.logger().warn("更新语言文件失败: " + ex.getMessage());
    }
  }

  private TagResolver buildResolvers(Map<String, String> placeholders) {
    TagResolver.Builder builder = TagResolver.builder();
    builder.resolver(Placeholder.component("prefix", prefix));
    if (placeholders != null) {
      for (Map.Entry<String, String> entry : placeholders.entrySet()) {
        String value = entry.getValue() == null ? "" : entry.getValue();
        builder.resolver(Placeholder.unparsed(entry.getKey(), value));
      }
    }
    return builder.build();
  }

  private void logMissingKey(String key) {
    access.logger().warn("缺少语言键: " + key);
  }

  private String normalizeLocale(String localeTag) {
    if (localeTag == null || localeTag.isBlank()) {
      return DEFAULT_LOCALE;
    }
    return localeTag;
  }

  private record LocaleFile(File file, String locale) {}

  record LocaleAccess(File dataFolder, LoggerManager logger, SaveResource saveResource) {}

  @FunctionalInterface
  interface SaveResource {
    void save(String path, boolean replace);
  }
}
