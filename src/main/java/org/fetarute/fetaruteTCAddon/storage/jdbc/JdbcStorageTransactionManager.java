package org.fetarute.fetaruteTCAddon.storage.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.fetarute.fetaruteTCAddon.storage.api.StorageException;
import org.fetarute.fetaruteTCAddon.storage.api.StorageTransaction;
import org.fetarute.fetaruteTCAddon.storage.api.StorageTransactionManager;

/**
 * 基于 JDBC 的事务管理器，每次从数据源获取一条连接并关闭。
 * <p>统一关闭/提交逻辑，避免业务层直接操作 Connection。</p>
 */
public final class JdbcStorageTransactionManager implements StorageTransactionManager {

    private final DataSource dataSource;

    public JdbcStorageTransactionManager(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public StorageTransaction begin() {
        try {
            Connection conn = dataSource.getConnection();
            conn.setAutoCommit(false);
            return new JdbcStorageTransaction(conn);
        } catch (SQLException ex) {
            throw new StorageException("开启事务失败", ex);
        }
    }
}
