package org.fetarute.fetaruteTCAddon.storage.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
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
            // 取连接超时取短：嵌套开启若另借连接会干等到超时，取短了失败得快。
            new ConfigManager.PoolSettings(1, 2000, 600000, 1800000));
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

  /** 关自动提交失败时连接要还回去：SQLite 连接池只有一条，漏还之后所有读写都拿不到连接。 */
  @Test
  void beginClosesConnectionWhenDisablingAutoCommitFails() throws Exception {
    DataSource failing = mock(DataSource.class);
    Connection broken = mock(Connection.class);
    when(failing.getConnection()).thenReturn(broken);
    doThrow(new SQLException("拒绝关闭自动提交")).when(broken).setAutoCommit(false);

    assertThrows(StorageException.class, () -> new JdbcStorageTransactionManager(failing).begin());

    verify(broken).close();
    assertNull(JdbcConnectionContext.current());
  }

  /** 嵌套开启加入外层事务：共用外层连接，内外层的写入由外层一并提交。 */
  @Test
  void nestedBeginJoinsOuterTransaction() throws Exception {
    JdbcStorageTransactionManager txManager = new JdbcStorageTransactionManager(dataSource);
    try (StorageTransaction outer = txManager.begin()) {
      Connection outerConnection = ((JdbcStorageTransaction) outer).connection();
      insert(outerConnection, 3);

      try (StorageTransaction inner = txManager.begin()) {
        assertSame(outerConnection, JdbcConnectionContext.current());
        insert(JdbcConnectionContext.current(), 4);
        inner.commit();
      }
      assertSame(outerConnection, JdbcConnectionContext.current());

      outer.commit();
    }
    assertNull(JdbcConnectionContext.current());
    assertEquals(2, countDemoRows(3, 4));
  }

  /** 内层回滚后外层不能只提交一半：外层提交报错，内外层的写入一起回滚。 */
  @Test
  void nestedRollbackRollsBackOuterTransaction() throws Exception {
    // 表在事务外建好：SQLite 的建表也随事务回滚。
    try (Connection conn = dataSource.getConnection()) {
      insert(conn, 0);
    }
    JdbcStorageTransactionManager txManager = new JdbcStorageTransactionManager(dataSource);
    try (StorageTransaction outer = txManager.begin()) {
      Connection outerConnection = ((JdbcStorageTransaction) outer).connection();
      insert(outerConnection, 5);
      try (StorageTransaction inner = txManager.begin()) {
        insert(JdbcConnectionContext.current(), 6);
        inner.rollback();
      }

      StorageException failure = assertThrows(StorageException.class, outer::commit);
      assertTrue(failure.getMessage().contains("回滚"));
    }
    assertNull(JdbcConnectionContext.current());
    assertEquals(0, countDemoRows(5, 6));
  }

  private static void insert(Connection connection, int id) throws SQLException {
    try (Statement st = connection.createStatement()) {
      st.executeUpdate("CREATE TABLE IF NOT EXISTS demo(id INTEGER PRIMARY KEY, name TEXT)");
    }
    try (PreparedStatement ps =
        connection.prepareStatement("INSERT INTO demo(id, name) VALUES (?, 'row')")) {
      ps.setInt(1, id);
      ps.executeUpdate();
    }
  }

  private int countDemoRows(int firstId, int secondId) throws SQLException {
    try (var conn = dataSource.getConnection();
        PreparedStatement ps =
            conn.prepareStatement("SELECT COUNT(*) FROM demo WHERE id = ? OR id = ?")) {
      ps.setInt(1, firstId);
      ps.setInt(2, secondId);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        return rs.getInt(1);
      }
    }
  }

  /** 绑着的连接已经关了（遗留绑定）：清掉后照常开启。 */
  @Test
  void staleClosedBindingDoesNotBlockNewTransaction() throws Exception {
    Connection closed = mock(Connection.class);
    when(closed.isClosed()).thenReturn(true);
    JdbcConnectionContext.bind(closed);
    try {
      JdbcStorageTransactionManager txManager = new JdbcStorageTransactionManager(dataSource);
      try (StorageTransaction tx = txManager.begin()) {
        assertSame(((JdbcStorageTransaction) tx).connection(), JdbcConnectionContext.current());
        tx.commit();
      }
      assertNull(JdbcConnectionContext.current());
    } finally {
      JdbcConnectionContext.clear(closed);
    }
  }
}
