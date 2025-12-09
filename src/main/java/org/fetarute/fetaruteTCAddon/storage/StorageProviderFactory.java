package org.fetarute.fetaruteTCAddon.storage;

import java.io.File;
import java.util.Objects;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqlDialect;
import org.fetarute.fetaruteTCAddon.storage.jdbc.HikariDataSourceFactory;
import org.fetarute.fetaruteTCAddon.storage.jdbc.JdbcStorageProvider;
import org.fetarute.fetaruteTCAddon.storage.provider.UnavailableStorageProvider;
import org.fetarute.fetaruteTCAddon.storage.schema.StorageSchema;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;

/**
 * 按配置创建具体的 StorageProvider 实例，后续可挂上 SQLite/MySQL/Postgres 等实现。
 *
 * <p>当前返回占位实现，确保命令层拿到明确的 StorageException，而不会静默空指针。
 */
public final class StorageProviderFactory {

  private StorageProviderFactory() {}

  public static StorageProvider create(
      ConfigManager.StorageSettings settings,
      StorageSchema schema,
      SqlDialect dialect,
      LoggerManager logger,
      File dataFolder) {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(schema, "schema");
    Objects.requireNonNull(dialect, "dialect");
    String tablePrefix = schema.tablePrefix();
    return switch (settings.backend()) {
      case SQLITE -> {
        try {
          var ds = HikariDataSourceFactory.create(settings, dataFolder, logger);
          yield new JdbcStorageProvider(ds, dialect, tablePrefix, logger);
        } catch (Exception ex) {
          logger.error("初始化 SQLite 数据源失败: " + ex.getMessage());
          yield new UnavailableStorageProvider("SQLite 数据源初始化失败: " + ex.getMessage());
        }
      }
      case MYSQL -> {
        try {
          var ds = HikariDataSourceFactory.create(settings, dataFolder, logger);
          yield new JdbcStorageProvider(ds, dialect, tablePrefix, logger);
        } catch (Exception ex) {
          logger.error("初始化 MySQL 数据源失败: " + ex.getMessage());
          yield new UnavailableStorageProvider("MySQL 数据源初始化失败: " + ex.getMessage());
        }
      }
    };
  }
}
