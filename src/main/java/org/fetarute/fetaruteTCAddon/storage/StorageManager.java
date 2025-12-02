package org.fetarute.fetaruteTCAddon.storage;

import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;

import java.util.Optional;

/**
 * 管理存储后端生命周期，当前仅承载配置，后续接入 SQLite/MySQL 连接池。
 */
public final class StorageManager {

    private final FetaruteTCAddon plugin;
    private ConfigManager.StorageSettings storageSettings;

    public StorageManager(FetaruteTCAddon plugin) {
        this.plugin = plugin;
    }

    /**
     * 应用最新配置，后续可在此初始化连接或迁移。
     *
     * @param configView 已解析的配置视图
     */
    public void apply(ConfigManager.ConfigView configView) {
        this.storageSettings = configView.storageSettings();
        logCurrentBackend();
        // TODO: 初始化或切换数据库连接
    }

    public ConfigManager.StorageBackend backend() {
        return storageSettings.backend();
    }

    public ConfigManager.SqliteSettings sqliteSettings() {
        return storageSettings.sqliteSettings();
    }

    public Optional<ConfigManager.MySqlSettings> mySqlSettings() {
        return storageSettings.mySqlSettings();
    }

    public void shutdown() {
        // TODO: 关闭连接池或释放资源
    }

    private void logCurrentBackend() {
        if (storageSettings.backend() == ConfigManager.StorageBackend.MYSQL) {
            storageSettings.mySqlSettings().ifPresent(settings ->
                    plugin.debug("存储后端: mysql @" + settings.address() + ":" + settings.port()
                            + "/" + settings.database() + " prefix=" + settings.tablePrefix())
            );
        } else {
            plugin.debug("存储后端: sqlite file=" + storageSettings.sqliteSettings().file());
        }
    }
}
