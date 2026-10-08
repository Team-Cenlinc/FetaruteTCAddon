package org.fetarute.fetaruteTCAddon.storage.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageTransaction;
import org.fetarute.fetaruteTCAddon.storage.api.StorageTransactionManager;

/**
 * 基于 JDBC 的事务管理器，每次从数据源获取一条连接并关闭。
 *
 * <p>统一关闭/提交逻辑，避免业务层直接操作 Connection。
 *
 * <p>同一线程里嵌套开启时加入外层事务：不另借连接（SQLite 连接池只有一条，另借会一直等到取连接超时），也不顶掉外层绑定
 * （否则内层提交后外层余下的写入就脱离了事务）。内层提交由外层一并提交；内层回滚则整个事务只能回滚，外层提交时报错。
 */
public final class JdbcStorageTransactionManager implements StorageTransactionManager {

  private final DataSource dataSource;

  public JdbcStorageTransactionManager(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public StorageTransaction begin() {
    Connection outer = boundOpenConnection();
    if (outer != null) {
      return new JoinedTransaction(outer);
    }
    Connection conn;
    try {
      conn = dataSource.getConnection();
    } catch (SQLException ex) {
      throw new StorageException("开启事务失败", ex);
    }
    try {
      conn.setAutoCommit(false);
    } catch (SQLException | RuntimeException ex) {
      // 连接已经借出：不还回去，连接池（SQLite 只有一条）就被占死。
      closeAfterFailedBegin(conn, ex);
      throw ex instanceof StorageException storage ? storage : new StorageException("开启事务失败", ex);
    }
    JdbcConnectionContext.bind(conn);
    return new JdbcStorageTransaction(conn);
  }

  /** 当前线程绑着的、尚未关闭的事务连接；绑着的连接已经关了（遗留绑定）则清掉并返回 null。 */
  private static Connection boundOpenConnection() {
    Connection bound = JdbcConnectionContext.current();
    if (bound == null) {
      return null;
    }
    boolean closed;
    try {
      closed = bound.isClosed();
    } catch (SQLException ex) {
      // 判不了按仍在事务中处理：加入外层，不让外层事务的写入悄悄脱离事务。
      closed = false;
    }
    if (!closed) {
      return bound;
    }
    JdbcConnectionContext.clear(bound);
    return null;
  }

  /** 加入外层事务的内层事务：提交交给外层，回滚把整个事务标为只能回滚。 */
  private static final class JoinedTransaction implements StorageTransaction {

    private final Connection connection;
    private boolean finished;

    private JoinedTransaction(Connection connection) {
      this.connection = connection;
    }

    @Override
    public void commit() {
      finished = true;
    }

    @Override
    public void rollback() {
      if (finished) {
        return;
      }
      finished = true;
      JdbcConnectionContext.markRollbackOnly(connection);
    }
  }

  private static void closeAfterFailedBegin(Connection conn, Exception cause) {
    try {
      conn.close();
    } catch (SQLException | RuntimeException closeFailure) {
      cause.addSuppressed(closeFailure);
    }
  }
}
