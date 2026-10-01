package org.fetarute.fetaruteTCAddon.display.template;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;

/**
 * HUD 默认模板服务：从数据目录的 default_hud_template.yml 提供各通道的默认模板，并在插件升级时替换没改过的旧版默认模板。
 *
 * <p>模板文件只在缺失时从插件里复制一次，之后插件升级不会再动它，新版默认模板的改进就永远到不了已部署的服务器。 因此每次加载时逐个通道比对：
 *
 * <ul>
 *   <li>与插件内置的当前默认模板相同：照用。
 *   <li>与某个已发布过的旧版默认模板相同（指纹见 {@value #HISTORY_RESOURCE}）：管理员没改过，按新版默认模板显示。
 *   <li>留空或删掉了：照旧表示不用默认模板文件、回退到语言文件里的模板，算作改过。
 *   <li>其余（管理员改过，包括插件并不内置的通道）：照用文件里的模板，不覆盖。
 * </ul>
 *
 * <p>只有没有任何通道被改过时，才把整份文件换成新版，换之前另存为 {@code default_hud_template.yml.bak}；否则文件不动， 旧版默认模板只在内存里按新版显示。
 *
 * <p>指纹是模板文本按行去掉行尾空白、去掉首尾空行后的 SHA-256。修改内置默认模板时，必须把修改前各通道的指纹追加到历史文件， 否则已部署的旧版会被当成“改过”而停在旧版。
 */
public final class HudDefaultTemplateService {

  static final String DEFAULT_FILE_NAME = "default_hud_template.yml";
  static final String HISTORY_RESOURCE = "hud/default_hud_template.history";

  private final File dataFolder;
  private final BiConsumer<String, Boolean> resourceSaver;
  private final Function<String, InputStream> resourceReader;
  private final Consumer<String> infoLogger;
  private final Consumer<String> warnLogger;
  private Map<HudTemplateType, String> templates;

  public HudDefaultTemplateService(JavaPlugin plugin, LoggerManager logger) {
    this(
        plugin.getDataFolder(),
        plugin::saveResource,
        plugin::getResource,
        logger == null ? message -> {} : logger::info,
        logger == null ? message -> {} : logger::warn);
  }

  HudDefaultTemplateService(
      File dataFolder,
      BiConsumer<String, Boolean> resourceSaver,
      Function<String, InputStream> resourceReader,
      Consumer<String> infoLogger,
      Consumer<String> warnLogger) {
    this.dataFolder = dataFolder;
    this.resourceSaver = resourceSaver;
    this.resourceReader = resourceReader;
    this.infoLogger = infoLogger;
    this.warnLogger = warnLogger;
  }

  /** 重新加载默认模板文件；旧版默认模板按新版显示，见类注释。 */
  public void reload() {
    ensureTemplateFile();
    YamlConfiguration onDisk =
        YamlConfiguration.loadConfiguration(new File(dataFolder, DEFAULT_FILE_NAME));
    YamlConfiguration bundled = readBundled();
    Map<HudTemplateType, Set<String>> history = readHistory();
    Map<HudTemplateType, String> resolved = new EnumMap<>(HudTemplateType.class);
    List<String> upgraded = new ArrayList<>();
    boolean customized = false;
    for (HudTemplateType type : HudTemplateType.values()) {
      String key = key(type);
      String current = onDisk.getString(key);
      String shipped = bundled.getString(key);
      boolean ships = shipped != null && !shipped.isBlank();
      if (current == null || current.isBlank()) {
        customized |= ships;
        continue;
      }
      if (ships && !normalize(current).equals(normalize(shipped))) {
        if (history.getOrDefault(type, Set.of()).contains(fingerprint(current))) {
          resolved.put(type, shipped);
          upgraded.add(type.name().toLowerCase(Locale.ROOT));
          continue;
        }
        customized = true;
      }
      customized |= !ships;
      resolved.put(type, current);
    }
    templates = resolved;
    if (upgraded.isEmpty()) {
      return;
    }
    if (customized) {
      infoLogger.accept(
          DEFAULT_FILE_NAME
              + " 中 "
              + String.join("、", upgraded)
              + " 仍是旧版默认模板，已按新版显示；文件里其余模板改过，文件未改写");
      return;
    }
    Path file = dataFolder.toPath().resolve(DEFAULT_FILE_NAME);
    try {
      Files.copy(
          file,
          file.resolveSibling(DEFAULT_FILE_NAME + ".bak"),
          StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException ex) {
      warnLogger.accept("备份 " + DEFAULT_FILE_NAME + " 失败，本次不改写文件，旧版默认模板按新版显示: " + ex.getMessage());
      return;
    }
    resourceSaver.accept(DEFAULT_FILE_NAME, true);
    infoLogger.accept(
        DEFAULT_FILE_NAME
            + " 已升级到新版默认模板："
            + String.join("、", upgraded)
            + "（原文件另存为 "
            + DEFAULT_FILE_NAME
            + ".bak）");
  }

  public Optional<String> resolveBossBarTemplate() {
    return resolveTemplate(HudTemplateType.BOSSBAR);
  }

  public Optional<String> resolveTemplate(HudTemplateType type) {
    if (type == null) {
      return Optional.empty();
    }
    if (templates == null) {
      reload();
    }
    return Optional.ofNullable(templates.get(type)).filter(value -> !value.isBlank());
  }

  /**
   * 模板指纹：按行去掉行尾空白、去掉首尾空行后的 SHA-256（小写十六进制）。
   *
   * @param template 模板文本
   */
  static String fingerprint(String template) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(normalize(template).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 不可用", ex);
    }
  }

  private static String normalize(String template) {
    List<String> lines = new ArrayList<>();
    for (String line : template.replace("\r\n", "\n").split("\n", -1)) {
      lines.add(line.stripTrailing());
    }
    int start = 0;
    int end = lines.size();
    while (start < end && lines.get(start).isEmpty()) {
      start++;
    }
    while (end > start && lines.get(end - 1).isEmpty()) {
      end--;
    }
    return String.join("\n", lines.subList(start, end));
  }

  private static String key(HudTemplateType type) {
    return type.name().toLowerCase(Locale.ROOT) + ".template";
  }

  private YamlConfiguration readBundled() {
    InputStream stream = resourceReader.apply(DEFAULT_FILE_NAME);
    if (stream == null) {
      return new YamlConfiguration();
    }
    try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      return YamlConfiguration.loadConfiguration(reader);
    } catch (IOException ex) {
      warnLogger.accept("读取内置默认 HUD 模板失败: " + ex.getMessage());
      return new YamlConfiguration();
    }
  }

  /** 历史指纹：每行 {@code <通道> <指纹>}，{@code #} 开头为注释。 */
  private Map<HudTemplateType, Set<String>> readHistory() {
    Map<HudTemplateType, Set<String>> history = new HashMap<>();
    InputStream stream = resourceReader.apply(HISTORY_RESOURCE);
    if (stream == null) {
      return history;
    }
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length != 2 || parts[0].startsWith("#")) {
          continue;
        }
        HudTemplateType.parse(parts[0])
            .ifPresent(
                type ->
                    history
                        .computeIfAbsent(type, ignored -> new HashSet<>())
                        .add(parts[1].toLowerCase(Locale.ROOT)));
      }
    } catch (IOException ex) {
      warnLogger.accept("读取默认 HUD 模板历史指纹失败: " + ex.getMessage());
    }
    return history;
  }

  private void ensureTemplateFile() {
    File file = new File(dataFolder, DEFAULT_FILE_NAME);
    if (file.exists()) {
      return;
    }
    try {
      resourceSaver.accept(DEFAULT_FILE_NAME, false);
    } catch (IllegalArgumentException ex) {
      warnLogger.accept("未找到默认 HUD 模板文件: " + DEFAULT_FILE_NAME);
    }
  }
}
