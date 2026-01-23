package org.fetarute.fetaruteTCAddon.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.logging.Logger;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.SpeedCurveType;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.storage.dialect.MySqlDialect;
import org.fetarute.fetaruteTCAddon.storage.dialect.SqliteDialect;
import org.fetarute.fetaruteTCAddon.storage.provider.UnavailableStorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;
import org.junit.jupiter.api.Test;

final class StorageManagerTest {

  @Test
  void selectSqliteDialectWhenConfigured() {
    ConfigManager.StorageSettings settings =
        new ConfigManager.StorageSettings(
            ConfigManager.StorageBackend.SQLITE,
            new ConfigManager.SqliteSettings("data/test.sqlite"),
            Optional.empty(),
            new ConfigManager.PoolSettings(5, 30000, 600000, 1800000));
    ConfigManager.ConfigView view =
        new ConfigManager.ConfigView(
            9,
            true,
            "zh_CN",
            settings,
            new ConfigManager.GraphSettings(8.0),
            new ConfigManager.AutoStationSettings("BLOCK_NOTE_BLOCK_BELL", 1.0f, 1.2f),
            new ConfigManager.RuntimeSettings(
                10,
                2,
                1,
                3,
                4.0,
                6.0,
                3.5,
                true,
                SpeedCurveType.PHYSICS,
                1.0,
                0.0,
                0.2,
                60,
                true,
                true,
                10,
                Optional.empty()),
            new ConfigManager.SpawnSettings(false, 20, 200, 1, 5, 5, 40),
            new ConfigManager.TrainConfigSettings(
                "emu",
                new ConfigManager.TrainTypeSettings(0.8, 1.0),
                new ConfigManager.TrainTypeSettings(0.7, 0.9),
                new ConfigManager.TrainTypeSettings(0.6, 0.8),
                new ConfigManager.TrainTypeSettings(0.9, 1.1)),
            new ConfigManager.ReclaimSettings(false, 3600L, 100, 60L));
    StorageManager manager =
        new StorageManager(null, new LoggerManager(Logger.getAnonymousLogger()));

    manager.apply(view);

    assertTrue(manager.dialect() instanceof SqliteDialect);
    assertTrue(manager.isReady());
    StorageProvider provider = manager.provider().orElse(null);
    assertNotNull(provider);
  }

  @Test
  void selectMysqlDialectWhenConfigured() {
    ConfigManager.StorageSettings settings =
        new ConfigManager.StorageSettings(
            ConfigManager.StorageBackend.MYSQL,
            new ConfigManager.SqliteSettings("data/test.sqlite"),
            Optional.of(
                new ConfigManager.MySqlSettings("127.0.0.1", 3306, "db", "user", "pwd", "fta_")),
            new ConfigManager.PoolSettings(5, 30000, 600000, 1800000));
    ConfigManager.ConfigView view =
        new ConfigManager.ConfigView(
            9,
            false,
            "zh_CN",
            settings,
            new ConfigManager.GraphSettings(8.0),
            new ConfigManager.AutoStationSettings("BLOCK_NOTE_BLOCK_BELL", 1.0f, 1.2f),
            new ConfigManager.RuntimeSettings(
                10,
                2,
                1,
                3,
                4.0,
                6.0,
                3.5,
                true,
                SpeedCurveType.PHYSICS,
                1.0,
                0.0,
                0.2,
                60,
                true,
                true,
                10,
                Optional.empty()),
            new ConfigManager.SpawnSettings(false, 20, 200, 1, 5, 5, 40),
            new ConfigManager.TrainConfigSettings(
                "emu",
                new ConfigManager.TrainTypeSettings(0.8, 1.0),
                new ConfigManager.TrainTypeSettings(0.7, 0.9),
                new ConfigManager.TrainTypeSettings(0.6, 0.8),
                new ConfigManager.TrainTypeSettings(0.9, 1.1)),
            new ConfigManager.ReclaimSettings(false, 3600L, 100, 60L));
    StorageManager manager =
        new StorageManager(null, new LoggerManager(Logger.getAnonymousLogger()));

    manager.apply(view);

    assertTrue(manager.dialect() instanceof MySqlDialect);
    // MySQL 驱动在测试环境可能不存在，若初始化失败应回退为占位 Provider
    StorageProvider provider = manager.provider().orElseThrow();
    assertTrue(provider instanceof UnavailableStorageProvider || !manager.isReady());
  }
}
