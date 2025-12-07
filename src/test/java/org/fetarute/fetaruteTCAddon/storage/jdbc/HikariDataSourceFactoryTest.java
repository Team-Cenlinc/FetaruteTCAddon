package org.fetarute.fetaruteTCAddon.storage.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Logger;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;
import org.junit.jupiter.api.Test;

final class HikariDataSourceFactoryTest {

    @Test
    void createSqliteDataSourceWithPoolSizeOne() throws Exception {
        Path tempDir = Files.createTempDirectory("fta-sqlite");
        ConfigManager.StorageSettings settings = new ConfigManager.StorageSettings(
                ConfigManager.StorageBackend.SQLITE,
                new ConfigManager.SqliteSettings("test.sqlite"),
                Optional.empty(),
                new ConfigManager.PoolSettings(5, 30000, 600000, 1800000)
        );

        try (HikariDataSource ds = HikariDataSourceFactory.create(settings, tempDir.toFile(), new LoggerManager(Logger.getAnonymousLogger()))) {
            assertEquals(1, ds.getMaximumPoolSize(), "SQLite 应强制为单连接池");
            assertTrue(ds.getJdbcUrl().contains("test.sqlite"), "JDBC URL 应包含文件名");
        }
    }
}
