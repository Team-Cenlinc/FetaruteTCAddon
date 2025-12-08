package org.fetarute.fetaruteTCAddon.config;

import java.util.Locale;
import java.util.Optional;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;

/** 负责读取并缓存 config.yml，统一暴露调试开关与存储后端配置。 */
public final class ConfigManager {

  private static final int EXPECTED_CONFIG_VERSION = 1;
  private static final String DEFAULT_LOCALE = "zh_CN";
  private final FetaruteTCAddon plugin;
  private final java.util.logging.Logger logger;
  private ConfigView current;

  public ConfigManager(FetaruteTCAddon plugin) {
    this.plugin = plugin;
    this.logger = plugin.getLogger();
  }

  /** 重新读取磁盘配置，更新缓存。 */
  public void reload() {
    plugin.reloadConfig();
    FileConfiguration config = plugin.getConfig();
    current = parse(config, logger);
  }

  public ConfigView current() {
    return current;
  }

  /** 解析配置，供生产与测试共用。 */
  public static ConfigView parse(FileConfiguration config, java.util.logging.Logger logger) {
    int version = config.getInt("config-version", 0);
    if (version != EXPECTED_CONFIG_VERSION) {
      logger.warning(
          "config-version 不匹配，当前: " + version + "，期望: " + EXPECTED_CONFIG_VERSION + "。请备份后更新配置模板。");
    }
    boolean debugEnabled = config.getBoolean("debug.enabled", false);
    String localeTag = config.getString("locale", DEFAULT_LOCALE);
    ConfigurationSection storageSection = config.getConfigurationSection("storage");
    StorageSettings storageSettings = parseStorage(storageSection, logger);
    return new ConfigView(version, debugEnabled, localeTag, storageSettings);
  }

  private static StorageSettings parseStorage(
      ConfigurationSection storageSection, java.util.logging.Logger logger) {
    if (storageSection == null) {
      logger.warning("缺少 storage 配置段，已回退为 SQLite");
      return new StorageSettings(
          StorageBackend.SQLITE, defaultSqlite(), Optional.empty(), defaultPool());
    }
    String rawBackend = storageSection.getString("backend", "sqlite");
    StorageBackend backend = StorageBackend.from(rawBackend);
    if (!backend.name().equalsIgnoreCase(rawBackend)) {
      logger.warning(
          "存储后端配置无效: " + rawBackend + "，已回退为 " + backend.name().toLowerCase(Locale.ROOT));
    }

    ConfigurationSection sqliteSection = storageSection.getConfigurationSection("sqlite");
    SqliteSettings sqliteSettings = parseSqlite(sqliteSection);

    ConfigurationSection mysqlSection = storageSection.getConfigurationSection("mysql");
    Optional<MySqlSettings> mySqlSettings = parseMySql(mysqlSection);

    PoolSettings poolSettings = parsePool(storageSection.getConfigurationSection("pool"));

    return new StorageSettings(backend, sqliteSettings, mySqlSettings, poolSettings);
  }

  private static SqliteSettings parseSqlite(ConfigurationSection sqliteSection) {
    if (sqliteSection == null) {
      return defaultSqlite();
    }
    String file = sqliteSection.getString("file", "data/fetarute.sqlite");
    return new SqliteSettings(file);
  }

  private static Optional<MySqlSettings> parseMySql(ConfigurationSection mysqlSection) {
    if (mysqlSection == null) {
      return Optional.empty();
    }
    String address = mysqlSection.getString("db_address", "127.0.0.1");
    int port = mysqlSection.getInt("db_port", 3306);
    String database = mysqlSection.getString("db_table", "fetarute_tc");
    String username = mysqlSection.getString("db_username", "fta");
    String password = mysqlSection.getString("db_password", "change-me");
    String tablePrefix = mysqlSection.getString("table_prefix", "fta_");
    MySqlSettings settings =
        new MySqlSettings(address, port, database, username, password, tablePrefix);
    return Optional.of(settings);
  }

  private static PoolSettings parsePool(ConfigurationSection poolSection) {
    if (poolSection == null) {
      return defaultPool();
    }
    int maxPoolSize = poolSection.getInt("maximum-pool-size", 5);
    long connectionTimeoutMs = poolSection.getLong("connection-timeout-ms", 30000);
    long idleTimeoutMs = poolSection.getLong("idle-timeout-ms", 600000);
    long maxLifetimeMs = poolSection.getLong("max-lifetime-ms", 1800000);
    return new PoolSettings(maxPoolSize, connectionTimeoutMs, idleTimeoutMs, maxLifetimeMs);
  }

  private static SqliteSettings defaultSqlite() {
    return new SqliteSettings("data/fetarute.sqlite");
  }

  private static PoolSettings defaultPool() {
    return new PoolSettings(5, 30000, 600000, 1800000);
  }

  /** 调试开关与存储设置的不可变视图。 */
  public record ConfigView(
      int configVersion, boolean debugEnabled, String locale, StorageSettings storageSettings) {}

  /** 存储后端定义。 */
  public enum StorageBackend {
    SQLITE,
    MYSQL;

    public static StorageBackend from(String raw) {
      if (raw == null) {
        return SQLITE;
      }
      try {
        return StorageBackend.valueOf(raw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        return SQLITE;
      }
    }
  }

  /** 存储配置的聚合，便于 StorageManager 统一接收。 */
  public record StorageSettings(
      StorageBackend backend,
      SqliteSettings sqliteSettings,
      Optional<MySqlSettings> mySqlSettings,
      PoolSettings poolSettings) {}

  /** SQLite 配置。 */
  public record SqliteSettings(String file) {}

  /** MySQL 配置。 */
  public record MySqlSettings(
      String address,
      int port,
      String database,
      String username,
      String password,
      String tablePrefix) {}

  /** 连接池配置。 */
  public record PoolSettings(
      int maximumPoolSize,
      long connectionTimeoutMillis,
      long idleTimeoutMillis,
      long maxLifetimeMillis) {}
}
