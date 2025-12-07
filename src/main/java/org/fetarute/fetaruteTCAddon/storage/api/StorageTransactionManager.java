package org.fetarute.fetaruteTCAddon.storage.api;

/**
 * 事务执行器，屏蔽底层实现差异。
 *
 * <p>用于封装 SQLite 单连接事务或连接池事务，业务层不直接触碰 JDBC。
 */
public interface StorageTransactionManager {

  /** 开启一个显式事务，交由调用方控制 commit/rollback。 */
  StorageTransaction begin() throws StorageException;

  /** 便捷执行单次事务。 */
  default <T> T execute(TransactionCallback<T> callback) throws StorageException {
    try (StorageTransaction tx = begin()) {
      T result = callback.doInTransaction();
      tx.commit();
      return result;
    } catch (StorageException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new StorageException("事务执行失败", ex);
    }
  }
}
