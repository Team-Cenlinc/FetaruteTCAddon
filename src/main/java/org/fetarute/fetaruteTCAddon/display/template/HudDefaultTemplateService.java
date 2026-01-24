package org.fetarute.fetaruteTCAddon.display.template;

import java.io.File;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;

/** HUD 默认模板服务：从 default_hud_template.yml 提供模板文本。 */
public final class HudDefaultTemplateService {

  private static final String DEFAULT_FILE_NAME = "default_hud_template.yml";

  private final File dataFolder;
  private final BiConsumer<String, Boolean> resourceSaver;
  private final Consumer<String> warnLogger;
  private YamlConfiguration config;

  public HudDefaultTemplateService(JavaPlugin plugin, LoggerManager logger) {
    this(
        plugin.getDataFolder(),
        (path, replace) -> plugin.saveResource(path, replace),
        logger == null ? null : logger::warn);
  }

  HudDefaultTemplateService(
      File dataFolder, BiConsumer<String, Boolean> resourceSaver, Consumer<String> warnLogger) {
    this.dataFolder = dataFolder;
    this.resourceSaver = resourceSaver;
    this.warnLogger = warnLogger;
  }

  /** 重新加载默认模板文件。 */
  public void reload() {
    ensureTemplateFile();
    File file = new File(dataFolder, DEFAULT_FILE_NAME);
    config = YamlConfiguration.loadConfiguration(file);
  }

  public Optional<String> resolveBossBarTemplate() {
    return resolveTemplate(HudTemplateType.BOSSBAR);
  }

  public Optional<String> resolveTemplate(HudTemplateType type) {
    if (type == null) {
      return Optional.empty();
    }
    if (config == null) {
      reload();
    }
    if (config == null) {
      return Optional.empty();
    }
    String key = type.name().toLowerCase(Locale.ROOT) + ".template";
    String value = config.getString(key);
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(value);
  }

  private void ensureTemplateFile() {
    File file = new File(dataFolder, DEFAULT_FILE_NAME);
    if (file.exists()) {
      return;
    }
    try {
      if (resourceSaver != null) {
        resourceSaver.accept(DEFAULT_FILE_NAME, false);
      }
    } catch (IllegalArgumentException ex) {
      if (warnLogger != null) {
        warnLogger.accept("未找到默认 HUD 模板文件: " + DEFAULT_FILE_NAME);
      }
    }
  }
}
