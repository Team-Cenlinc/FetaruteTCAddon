package org.fetarute.fetaruteTCAddon.storage;

import java.io.File;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.storage.dialect.MySqlDialect;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqliteDialect;
import org.fetarute.fetaruteTCAddon.storage.provider.UnavailableStorageProvider;
import org.fetarute.fetaruteTCAddon.storage.schema.StorageSchema;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;

/**
 * 管理存储后端生命周期：读取配置、选择 SQL 方言、生成 schema 与创建 StorageProvider。
 *
 * <p>目前 provider 仍为占位实现，但暴露 readiness 标记便于命令层做友好提示。
 */
public final class StorageManager {

  private final FetaruteTCAddon plugin;
  private final LoggerManager logger;
  private ConfigManager.StorageSettings storageSettings;
  private StorageSchema storageSchema = new StorageSchema();
  private SqlDialect dialect = new SqliteDialect();
  private StorageProvider storageProvider;

  public StorageManager(FetaruteTCAddon plugin, LoggerManager logger) {
    this.plugin = plugin;
    this.logger = logger;
  }

  /**
   * 应用最新配置，后续可在此初始化连接或迁移。
   *
   * @param configView 已解析的配置视图
   */
  public void apply(ConfigManager.ConfigView configView) {
    this.storageSettings = configView.storageSettings();
    this.dialect = resolveDialect(storageSettings.backend());
    this.storageSchema = resolveSchema(storageSettings);
    bootstrapProvider();
    logCurrentBackend();
  }

  public ConfigManager.StorageBackend backend() {
    if (storageSettings == null) {
      return ConfigManager.StorageBackend.SQLITE;
    }
    return storageSettings.backend();
  }

  public ConfigManager.SqliteSettings sqliteSettings() {
    return storageSettings == null ? null : storageSettings.sqliteSettings();
  }

  public Optional<ConfigManager.MySqlSettings> mySqlSettings() {
    if (storageSettings == null) {
      return Optional.empty();
    }
    return storageSettings.mySqlSettings();
  }

  public StorageSchema schema() {
    return storageSchema;
  }

  public SqlDialect dialect() {
    return dialect;
  }

  public Optional<StorageProvider> provider() {
    return Optional.ofNullable(storageProvider);
  }

  public boolean isReady() {
    return storageProvider != null && !(storageProvider instanceof UnavailableStorageProvider);
  }

  public void shutdown() {
    closeProviderQuietly();
  }

  private void logCurrentBackend() {
    if (storageSettings.backend() == ConfigManager.StorageBackend.MYSQL) {
      storageSettings
          .mySqlSettings()
          .ifPresent(
              settings ->
                  logger.debug(
                      "存储后端: mysql @"
                          + settings.address()
                          + ":"
                          + settings.port()
                          + "/"
                          + settings.database()
                          + " prefix="
                          + settings.tablePrefix()
                          + " dialect="
                          + dialect.name()));
    } else {
      logger.debug(
          "存储后端: sqlite file="
              + storageSettings.sqliteSettings().file()
              + " dialect="
              + dialect.name());
    }
    if (!isReady()) {
      logger.warn("当前存储后端尚未初始化完毕，命令层写入操作会暂时不可用");
    }
  }

  private StorageSchema resolveSchema(ConfigManager.StorageSettings settings) {
    Optional<ConfigManager.MySqlSettings> mysql = settings.mySqlSettings();
    if (mysql.isPresent()) {
      return new StorageSchema(mysql.get().tablePrefix());
    }
    return new StorageSchema();
  }

  private SqlDialect resolveDialect(ConfigManager.StorageBackend backend) {
    return switch (backend) {
      case MYSQL -> new MySqlDialect();
      case SQLITE -> new SqliteDialect();
    };
  }

  private void bootstrapProvider() {
    closeProviderQuietly();
    File dataFolder =
        plugin == null
            ? new File(System.getProperty("java.io.tmpdir"), "FetaruteTCAddon")
            : plugin.getDataFolder();
    this.storageProvider =
        StorageProviderFactory.create(storageSettings, storageSchema, dialect, logger, dataFolder);
  }

  private void closeProviderQuietly() {
    if (storageProvider == null) {
      return;
    }
    try {
      storageProvider.close();
    } catch (Exception ex) {
      logger.warn("关闭存储后端时出错: " + ex.getMessage());
    } finally {
      storageProvider = null;
    }
  }
}
