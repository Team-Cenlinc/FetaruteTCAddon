package org.fetarute.fetaruteTCAddon.storage.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageTransaction;

/**
 * JDBC 事务包装，负责提交/回滚并在关闭时清理连接。
 * <p>一次事务对应一条连接，commit/rollback 后自动关闭，重复关闭安全。</p>
 */
public final class JdbcStorageTransaction implements StorageTransaction {

    private final Connection connection;
    private boolean closed;

    public JdbcStorageTransaction(Connection connection) {
        this.connection = connection;
    }

    public Connection connection() {
        return connection;
    }

    @Override
    public void commit() {
        if (closed) {
            return;
        }
        try {
            connection.commit();
        } catch (SQLException ex) {
            throw new StorageException("提交事务失败", ex);
        } finally {
            closeSilently();
        }
    }

    @Override
    public void rollback() {
        if (closed) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ex) {
            throw new StorageException("回滚事务失败", ex);
        } finally {
            closeSilently();
        }
    }

    private void closeSilently() {
        if (closed) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignore) {
            // 忽略关闭异常
        } finally {
            closed = true;
        }
    }
}
