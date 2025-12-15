package org.fetarute.fetaruteTCAddon.storage.jdbc;

import java.sql.Connection;

/**
 * JDBC 事务连接上下文。
 *
 * <p>用于在 {@link org.fetarute.fetaruteTCAddon.storage.api.StorageTransactionManager} 开启事务时，将同一条
 * Connection 绑定到当前线程，使仓库方法在事务内复用同一连接。
 */
public final class JdbcConnectionContext {

  private static final ThreadLocal<Connection> CURRENT = new ThreadLocal<>();

  private JdbcConnectionContext() {}

  public static Connection current() {
    return CURRENT.get();
  }

  public static boolean isCurrent(Connection connection) {
    return connection != null && connection == CURRENT.get();
  }

  static void bind(Connection connection) {
    CURRENT.set(connection);
  }

  static void clear(Connection connection) {
    if (isCurrent(connection)) {
      CURRENT.remove();
    }
  }
}
