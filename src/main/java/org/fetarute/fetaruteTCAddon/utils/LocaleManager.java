package org.fetarute.fetaruteTCAddon.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
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

  /** 内置文案改写前的旧值所在的资源目录（{@code <目录>/<语言>.yml}，键同语言文件、值为旧值列表）。放在 {@code lang/} 之外，不会被当成一种语言。 */
  private static final String SUPERSEDED_DIR = "lang-superseded/";

  private final LocaleAccess access;
  private final MiniMessage miniMessage = MiniMessage.miniMessage();
  private String currentLocale;
  private YamlConfiguration messages;
  private final Set<String> warnedMissingKeys = ConcurrentHashMap.newKeySet();
  private Component prefix = Component.empty();
  private List<String> availableLocales = List.of();

  /** 每次重新加载语言文件加一：缓存了渲染结果的调用方据此作废。 */
  private final java.util.concurrent.atomic.AtomicLong generation =
      new java.util.concurrent.atomic.AtomicLong();

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

  /** 语言文件加载过几次；缓存渲染结果的调用方比对它来判断是否要重新渲染。 */
  public long generation() {
    return generation.get();
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
    return miniMessage.deserialize(expandClickArguments(raw, placeholders), resolver);
  }

  /** 点击事件的参数：MiniMessage 不在引号参数里解析占位符，这里先把它们换成实际值。 */
  private static final java.util.regex.Pattern CLICK_ARGUMENT =
      java.util.regex.Pattern.compile("(<click:[a-z_]+:)(['\"])(.*?)\\2>");

  /**
   * 把点击事件参数里的 {@code <占位符>} 换成实际值（例如 {@code <click:run_command:'/fta license exam <class>'>}）；
   * 值里的引号与反斜杠按 MiniMessage 的规则转义。其余位置的占位符照常交给解析器。
   */
  static String expandClickArguments(String raw, Map<String, String> placeholders) {
    if (raw == null || placeholders == null || placeholders.isEmpty() || !raw.contains("<click:")) {
      return raw;
    }
    java.util.regex.Matcher matcher = CLICK_ARGUMENT.matcher(raw);
    StringBuilder out = new StringBuilder(raw.length());
    while (matcher.find()) {
      String quote = matcher.group(2);
      String argument = matcher.group(3);
      for (Map.Entry<String, String> entry : placeholders.entrySet()) {
        String token = "<" + entry.getKey() + ">";
        if (argument.contains(token)) {
          String value = entry.getValue() == null ? "" : entry.getValue();
          argument =
              argument.replace(token, value.replace("\\", "\\\\").replace(quote, "\\" + quote));
        }
      }
      matcher.appendReplacement(
          out,
          java.util.regex.Matcher.quoteReplacement(
              matcher.group(1) + quote + argument + quote + ">"));
    }
    matcher.appendTail(out);
    return out.toString();
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
   * 获取语言文件中的纯文本字符串（不做 MiniMessage 解析）。
   *
   * <p>适用于需要把短文本作为占位符塞进其他模板的场景（例如 list/status/mode 等）。
   *
   * <p>注意：语言文件中的对应键建议只写纯文本（不要写 MiniMessage 标签），避免被当作普通字符输出。
   *
   * @param key 语言键
   * @return 纯文本；缺失时回退为 key 本身
   */
  public String text(String key) {
    Objects.requireNonNull(key, "key");
    if (messages == null) {
      reload();
    }
    if (messages == null) {
      return key;
    }
    String raw = messages.getString(key);
    if (raw == null) {
      logMissingKey(key);
      return key;
    }
    return raw;
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

  /** 获取当前已发现的语言列表（来自 lang 目录）。 */
  public List<String> availableLocales() {
    if (availableLocales == null || availableLocales.isEmpty()) {
      if (currentLocale != null && !currentLocale.isBlank()) {
        return List.of(currentLocale);
      }
      return List.of(DEFAULT_LOCALE);
    }
    return List.copyOf(availableLocales);
  }

  private void loadLocale(String localeTag) {
    ensureBundledLocales();
    LocaleFile localeFile = prepareLocaleFile(localeTag);
    messages = YamlConfiguration.loadConfiguration(localeFile.file());
    currentLocale = localeFile.locale();
    generation.incrementAndGet();
    warnedMissingKeys.clear();
    prefix = parsePrefix(messages);
    refreshAvailableLocales();
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
      List<String> replaced = upgradeSuperseded(localeTag, defaults, existing);
      if (!added.isEmpty() || !replaced.isEmpty()) {
        existing.save(localeFile);
      }
      if (!added.isEmpty()) {
        // 补全键属于诊断信息：默认不刷屏，仅在 debug.enabled=true 时输出。
        access.logger().debug("已补全语言键: " + String.join(", ", added));
      }
      if (!replaced.isEmpty()) {
        access.logger().info("已换成新的内置文案: " + String.join(", ", replaced));
      }
    } catch (IOException ex) {
      access.logger().warn("更新语言文件失败: " + ex.getMessage());
    }
  }

  /**
   * 把仍是改写前旧内置文案的键换成新的内置文案：语言文件只补缺失的键，改写已有键的文案到不了已部署的服务器。
   *
   * <p>只换值与旧值清单逐字相同的键（服务器没有改过）；改过的、清单里没有的都不动。
   *
   * @return 换掉的键
   */
  private List<String> upgradeSuperseded(
      String localeTag, YamlConfiguration defaults, YamlConfiguration existing) throws IOException {
    try (InputStream stream =
        LocaleManager.class
            .getClassLoader()
            .getResourceAsStream(SUPERSEDED_DIR + localeTag + ".yml")) {
      if (stream == null) {
        return List.of();
      }
      YamlConfiguration superseded =
          YamlConfiguration.loadConfiguration(
              new InputStreamReader(stream, StandardCharsets.UTF_8));
      List<String> replaced = new ArrayList<>();
      for (String key : superseded.getKeys(true)) {
        if (superseded.isConfigurationSection(key) || !defaults.isString(key)) {
          continue;
        }
        String current = existing.getString(key);
        if (current != null
            && !current.equals(defaults.getString(key))
            && superseded.getStringList(key).contains(current)) {
          existing.set(key, defaults.getString(key));
          replaced.add(key);
        }
      }
      return replaced;
    }
  }

  /** 确保内置语言文件已落地到 lang 目录（仅补缺失键）。 */
  private void ensureBundledLocales() {
    File langDir = new File(access.dataFolder(), "lang");
    if (!langDir.exists() && !langDir.mkdirs()) {
      access.logger().warn("无法创建语言目录 " + langDir.getAbsolutePath());
      return;
    }
    for (String localeTag : listBundledLocales()) {
      if (localeTag == null || localeTag.isBlank()) {
        continue;
      }
      File localeFile = new File(langDir, localeTag + ".yml");
      mergeLocaleDefaults(localeTag, localeFile);
    }
  }

  /** 刷新当前可用语言列表（来自 lang 目录 + 当前 locale）。 */
  private void refreshAvailableLocales() {
    Set<String> locales = new HashSet<>(listLocalesInDir(new File(access.dataFolder(), "lang")));
    if (currentLocale != null && !currentLocale.isBlank()) {
      locales.add(currentLocale);
    }
    if (locales.isEmpty()) {
      locales.add(DEFAULT_LOCALE);
    }
    List<String> sorted = new ArrayList<>(locales);
    Collections.sort(sorted);
    availableLocales = List.copyOf(sorted);
  }

  /** 扫描目录下的 <locale>.yml 文件名。 */
  private List<String> listLocalesInDir(File langDir) {
    if (langDir == null || !langDir.exists() || !langDir.isDirectory()) {
      return List.of();
    }
    File[] files = langDir.listFiles((dir, name) -> name.endsWith(".yml"));
    if (files == null || files.length == 0) {
      return List.of();
    }
    List<String> locales = new ArrayList<>();
    for (File file : files) {
      String name = file.getName();
      if (!name.endsWith(".yml")) {
        continue;
      }
      String locale = name.substring(0, name.length() - ".yml".length());
      if (!locale.isBlank()) {
        locales.add(locale);
      }
    }
    Collections.sort(locales);
    return locales;
  }

  /** 从内置资源/插件包中枚举可用语言文件。 */
  private List<String> listBundledLocales() {
    Set<String> locales = new HashSet<>();
    try {
      URL resource = LocaleManager.class.getClassLoader().getResource("lang");
      if (resource != null && "file".equalsIgnoreCase(resource.getProtocol())) {
        File dir = new File(resource.toURI());
        locales.addAll(listLocalesInDir(dir));
      }
    } catch (URISyntaxException ex) {
      access.logger().debug("解析 lang 资源目录失败: " + ex.getMessage());
    }
    try {
      URL location = LocaleManager.class.getProtectionDomain().getCodeSource().getLocation();
      if (location != null) {
        File source = new File(location.toURI());
        if (source.isFile()) {
          locales.addAll(listLocalesInJar(source));
        } else if (source.isDirectory()) {
          locales.addAll(listLocalesInDir(new File(source, "lang")));
        }
      }
    } catch (URISyntaxException | IOException ex) {
      access.logger().debug("扫描内置语言失败: " + ex.getMessage());
    }
    List<String> sorted = new ArrayList<>(locales);
    Collections.sort(sorted);
    return sorted;
  }

  /** 从插件 jar 内枚举 lang/*.yml。 */
  private List<String> listLocalesInJar(File jarFile) throws IOException {
    if (jarFile == null || !jarFile.isFile()) {
      return List.of();
    }
    Set<String> locales = new HashSet<>();
    try (JarFile jar = new JarFile(jarFile)) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        if (entry.isDirectory()) {
          continue;
        }
        String name = entry.getName();
        if (name == null || !name.startsWith("lang/") || !name.endsWith(".yml")) {
          continue;
        }
        String locale = name.substring("lang/".length(), name.length() - ".yml".length());
        if (!locale.isBlank()) {
          locales.add(locale);
        }
      }
    }
    List<String> sorted = new ArrayList<>(locales);
    Collections.sort(sorted);
    return sorted;
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

  /** 同一个缺失的键每次加载语言后只警告一次：HUD 每 tick 都会查同一批键，不节流会刷屏并拖慢主线程。 */
  private void logMissingKey(String key) {
    if (warnedMissingKeys.add(key)) {
      access.logger().warn("缺少语言键: " + key);
    }
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
