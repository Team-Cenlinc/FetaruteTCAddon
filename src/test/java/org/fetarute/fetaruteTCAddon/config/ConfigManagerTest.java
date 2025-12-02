package org.fetarute.fetaruteTCAddon.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfigManagerTest {

    @Test
    // 应解析 debug 开关与 MySQL 配置
    void parseDebugAndMySqlSettings() {
        FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("config-test"));
        doNothing().when(plugin).reloadConfig();

        YamlConfiguration config = new YamlConfiguration();
        config.set("config-version", 1);
        config.set("debug.enabled", true);
        config.set("storage.backend", "mysql");
        config.set("storage.mysql.db_address", "db.example.com");
        config.set("storage.mysql.db_port", 3307);
        config.set("storage.mysql.db_table", "fta_data");
        config.set("storage.mysql.db_username", "user");
        config.set("storage.mysql.db_password", "secret");
        config.set("storage.mysql.table_prefix", "t_");
        when(plugin.getConfig()).thenReturn(config);

        ConfigManager manager = new ConfigManager(plugin);
        manager.reload();
        ConfigManager.ConfigView view = manager.current();

        assertEquals(1, view.configVersion());
        assertTrue(view.debugEnabled());
        assertEquals(ConfigManager.StorageBackend.MYSQL, view.storageSettings().backend());
        ConfigManager.MySqlSettings mysql = view.storageSettings().mySqlSettings().orElseThrow();
        assertEquals("db.example.com", mysql.address());
        assertEquals(3307, mysql.port());
        assertEquals("fta_data", mysql.database());
        assertEquals("user", mysql.username());
        assertEquals("secret", mysql.password());
        assertEquals("t_", mysql.tablePrefix());
    }

    @Test
    // 无效 backend 应回退到 SQLite，并填充默认文件名
    void fallbackToSqliteOnInvalidBackend() {
        FetaruteTCAddon plugin = mock(FetaruteTCAddon.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("config-test"));
        doNothing().when(plugin).reloadConfig();

        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.backend", "unknown");
        when(plugin.getConfig()).thenReturn(config);

        ConfigManager manager = new ConfigManager(plugin);
        manager.reload();
        ConfigManager.ConfigView view = manager.current();

        assertEquals(0, view.configVersion());
        assertFalse(view.debugEnabled());
        assertEquals(ConfigManager.StorageBackend.SQLITE, view.storageSettings().backend());
        assertEquals("data/fetarute.sqlite", view.storageSettings().sqliteSettings().file());
    }
}
