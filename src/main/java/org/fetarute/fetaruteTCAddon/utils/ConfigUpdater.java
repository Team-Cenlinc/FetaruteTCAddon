package org.fetarute.fetaruteTCAddon.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.bukkit.configuration.file.YamlConfiguration;

/** 将默认配置合并到用户配置，填补新键并保留用户已改值。 */
public final class ConfigUpdater {

  private final File configFile;
  private final Supplier<InputStream> defaultSupplier;
  private final LoggerManager logger;

  public ConfigUpdater(
      File configFile, Supplier<InputStream> defaultSupplier, LoggerManager logger) {
    this.configFile = configFile;
    this.defaultSupplier = defaultSupplier;
    this.logger = logger;
  }

  public static ConfigUpdater forPlugin(
      java.io.File dataFolder,
      java.util.function.Supplier<InputStream> defaultSupplier,
      LoggerManager logger) {
    return new ConfigUpdater(new File(dataFolder, "config.yml"), defaultSupplier, logger);
  }

  /** 执行合并。若未检测到差异则不写盘。 */
  public UpdateResult update() {
    if (defaultSupplier == null) {
      return UpdateResult.empty();
    }
    try (InputStream defaultStream = defaultSupplier.get()) {
      if (defaultStream == null) {
        logger.warn("未找到内置配置模板，跳过配置合并");
        return UpdateResult.empty();
      }
      if (!configFile.exists()) {
        ensureParent();
        Files.copy(defaultStream, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        logger.info("已创建默认配置文件");
        return new UpdateResult(true, 0, readVersion(configFile), List.of(), List.of());
      }

      YamlConfiguration defaultConfig =
          YamlConfiguration.loadConfiguration(
              new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
      YamlConfiguration existing = YamlConfiguration.loadConfiguration(configFile);

      UpdateState state = merge(defaultConfig, existing);
      if (!state.changed) {
        return new UpdateResult(false, state.oldVersion, state.newVersion, List.of(), List.of());
      }

      backupConfig();
      state.merged.save(configFile);
      logResult(state);
      return new UpdateResult(
          true, state.oldVersion, state.newVersion, state.addedKeys, state.extraKeys);
    } catch (IOException ex) {
      logger.error("合并配置失败: " + ex.getMessage());
      return UpdateResult.empty();
    }
  }

  private UpdateState merge(YamlConfiguration defaults, YamlConfiguration existing) {
    YamlConfiguration merged = new YamlConfiguration();
    int defaultVersion = defaults.getInt("config-version", 0);
    int oldVersion = existing.getInt("config-version", 0);
    List<String> added = new ArrayList<>();
    List<String> extras = new ArrayList<>();

    // 先填充模板中的键
    for (String key : defaults.getKeys(true)) {
      if (defaults.isConfigurationSection(key)) {
        continue;
      }
      Object value;
      if (existing.contains(key)) {
        value = existing.get(key);
      } else {
        value = defaults.get(key);
        added.add(key);
      }
      merged.set(key, value);
    }

    // 保留用户的额外键并记录
    Set<String> defaultKeys = new HashSet<>(defaults.getKeys(true));
    for (String key : existing.getKeys(true)) {
      if (existing.isConfigurationSection(key)) {
        continue;
      }
      if (!defaultKeys.contains(key)) {
        extras.add(key);
        merged.set(key, existing.get(key));
      }
    }

    merged.set("config-version", defaultVersion);

    boolean changed = oldVersion != defaultVersion || !added.isEmpty() || !extras.isEmpty();
    return new UpdateState(merged, oldVersion, defaultVersion, added, extras, changed);
  }

  private void ensureParent() throws IOException {
    File parent = configFile.getParentFile();
    if (parent != null && !parent.exists()) {
      Files.createDirectories(parent.toPath());
    }
  }

  private void backupConfig() {
    Path source = configFile.toPath();
    Path target = source.resolveSibling("config.yml.bak");
    try {
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
      logger.debug("已备份配置到 " + target.getFileName());
    } catch (IOException ex) {
      logger.warn("备份配置失败: " + ex.getMessage());
    }
  }

  private void logResult(UpdateState state) {
    if (state.oldVersion != state.newVersion) {
      logger.info("config-version " + state.oldVersion + " -> " + state.newVersion);
    }
    if (!state.addedKeys.isEmpty()) {
      logger.info("新增配置键: " + String.join(", ", state.addedKeys));
    }
    if (!state.extraKeys.isEmpty()) {
      logger.warn("配置中存在未识别的键: " + String.join(", ", state.extraKeys));
    }
  }

  private int readVersion(File file) {
    try (InputStream in = new FileInputStream(file)) {
      YamlConfiguration yaml =
          YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
      return yaml.getInt("config-version", 0);
    } catch (IOException ex) {
      return 0;
    }
  }

  public record UpdateResult(
      boolean changed,
      int oldVersion,
      int newVersion,
      List<String> addedKeys,
      List<String> extraKeys) {
    static UpdateResult empty() {
      return new UpdateResult(false, 0, 0, List.of(), List.of());
    }
  }

  private record UpdateState(
      YamlConfiguration merged,
      int oldVersion,
      int newVersion,
      List<String> addedKeys,
      List<String> extraKeys,
      boolean changed) {}
}
