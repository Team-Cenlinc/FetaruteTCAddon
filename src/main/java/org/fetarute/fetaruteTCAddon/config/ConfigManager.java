package org.fetarute.fetaruteTCAddon.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;

import java.util.Optional;

/**
 * 负责读取并缓存 config.yml，统一暴露调试开关与存储后端配置。
 */
public final class ConfigManager {

    private final FetaruteTCAddon plugin;
    private static final int EXPECTED_CONFIG_VERSION = 1;
    private ConfigView current;

    public ConfigManager(FetaruteTCAddon plugin) {
        this.plugin = plugin;
    }

    /**
     * 重新读取磁盘配置，更新缓存。
     */
    public void reload() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        current = parse(config);
    }

    public ConfigView current() {
        return current;
    }

    private ConfigView parse(FileConfiguration config) {
        int version = config.getInt("config-version", 0);
        if (version != EXPECTED_CONFIG_VERSION) {
            plugin.getLogger().warning("config-version 不匹配，当前: " + version + "，期望: " + EXPECTED_CONFIG_VERSION + "。请备份后更新配置模板。");
        }
        boolean debugEnabled = config.getBoolean("debug.enabled", false);
        ConfigurationSection storageSection = config.getConfigurationSection("storage");
        StorageSettings storageSettings = parseStorage(storageSection);
        return new ConfigView(version, debugEnabled, storageSettings);
    }

    private StorageSettings parseStorage(ConfigurationSection storageSection) {
        if (storageSection == null) {
            plugin.getLogger().warning("缺少 storage 配置段，已回退为 SQLite");
            return new StorageSettings(StorageBackend.SQLITE, defaultSqlite(), Optional.empty());
        }
        String rawBackend = storageSection.getString("backend", "sqlite");
        StorageBackend backend = StorageBackend.from(rawBackend);
        if (!backend.name().equalsIgnoreCase(rawBackend)) {
            plugin.getLogger().warning("存储后端配置无效: " + rawBackend + "，已回退为 " + backend.name().toLowerCase());
        }

        ConfigurationSection sqliteSection = storageSection.getConfigurationSection("sqlite");
        SqliteSettings sqliteSettings = parseSqlite(sqliteSection);

        ConfigurationSection mysqlSection = storageSection.getConfigurationSection("mysql");
        Optional<MySqlSettings> mySqlSettings = parseMySql(mysqlSection);

        return new StorageSettings(backend, sqliteSettings, mySqlSettings);
    }

    private SqliteSettings parseSqlite(ConfigurationSection sqliteSection) {
        if (sqliteSection == null) {
            return defaultSqlite();
        }
        String file = sqliteSection.getString("file", "data/fetarute.sqlite");
        return new SqliteSettings(file);
    }

    private Optional<MySqlSettings> parseMySql(ConfigurationSection mysqlSection) {
        if (mysqlSection == null) {
            return Optional.empty();
        }
        String address = mysqlSection.getString("db_address", "127.0.0.1");
        int port = mysqlSection.getInt("db_port", 3306);
        String database = mysqlSection.getString("db_table", "fetarute_tc");
        String username = mysqlSection.getString("db_username", "fta");
        String password = mysqlSection.getString("db_password", "change-me");
        String tablePrefix = mysqlSection.getString("table_prefix", "fta_");
        MySqlSettings settings = new MySqlSettings(address, port, database, username, password, tablePrefix);
        return Optional.of(settings);
    }

    private SqliteSettings defaultSqlite() {
        return new SqliteSettings("data/fetarute.sqlite");
    }

    /**
     * 调试开关与存储设置的不可变视图。
     */
    public record ConfigView(int configVersion, boolean debugEnabled, StorageSettings storageSettings) {
    }

    /**
     * 存储后端定义。
     */
    public enum StorageBackend {
        SQLITE,
        MYSQL;

        public static StorageBackend from(String raw) {
            if (raw == null) {
                return SQLITE;
            }
            try {
                return StorageBackend.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return SQLITE;
            }
        }
    }

    /**
     * 存储配置的聚合，便于 StorageManager 统一接收。
     */
    public record StorageSettings(StorageBackend backend, SqliteSettings sqliteSettings,
                                  Optional<MySqlSettings> mySqlSettings) {
    }

    /**
     * SQLite 配置。
     */
    public record SqliteSettings(String file) {
    }

    /**
     * MySQL 配置。
     */
    public record MySqlSettings(String address, int port, String database, String username, String password,
                                String tablePrefix) {
    }
}
