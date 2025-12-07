package org.fetarute.fetaruteTCAddon.storage.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;
import java.util.logging.Logger;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageTransaction;
import org.fetarute.fetaruteTCAddon.utils.LoggerManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class JdbcStorageTransactionManagerTest {

  private HikariDataSource dataSource;

  @BeforeEach
  void setUp() throws Exception {
    Path tempDir = Files.createTempDirectory("fta-tx");
    ConfigManager.StorageSettings settings =
        new ConfigManager.StorageSettings(
            ConfigManager.StorageBackend.SQLITE,
            new ConfigManager.SqliteSettings("tx.sqlite"),
            Optional.empty(),
            new ConfigManager.PoolSettings(1, 30000, 600000, 1800000));
    dataSource =
        HikariDataSourceFactory.create(
            settings, tempDir.toFile(), new LoggerManager(Logger.getAnonymousLogger()));
  }

  @AfterEach
  void tearDown() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @Test
  void beginCommitAndRollback() throws Exception {
    JdbcStorageTransactionManager txManager = new JdbcStorageTransactionManager(dataSource);

    // commit path
    try (StorageTransaction tx = txManager.begin()) {
      var conn = ((JdbcStorageTransaction) tx).connection();
      try (Statement st = conn.createStatement()) {
        st.executeUpdate("CREATE TABLE IF NOT EXISTS demo(id INTEGER PRIMARY KEY, name TEXT)");
        st.executeUpdate("INSERT INTO demo(id, name) VALUES (1, 'ok')");
      }
      tx.commit();
    }
    try (var conn = dataSource.getConnection();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM demo")) {
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
    }

    // rollback path
    try (StorageTransaction tx = txManager.begin()) {
      var conn = ((JdbcStorageTransaction) tx).connection();
      try (Statement st = conn.createStatement()) {
        st.executeUpdate("INSERT INTO demo(id, name) VALUES (2, 'rollback')");
      }
      tx.rollback();
    }
    try (var conn = dataSource.getConnection();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM demo WHERE id=2")) {
      assertTrue(rs.next());
      assertEquals(0, rs.getInt(1));
    }
  }
}
