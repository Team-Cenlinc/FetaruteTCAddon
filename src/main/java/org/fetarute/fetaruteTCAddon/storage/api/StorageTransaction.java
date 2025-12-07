package org.fetarute.fetaruteTCAddon.storage.api;

/**
 * 事务上下文，便于手动控制提交/回滚。
 *
 * <p>实现应保证幂等 close/rollback，防止调用方遗忘提交导致脏写。
 */
public interface StorageTransaction extends AutoCloseable {

  /** 提交事务。 */
  void commit() throws StorageException;

  /** 回滚事务。 */
  void rollback() throws StorageException;

  /** 默认 close 等同回滚，防止遗留未提交的写入。 */
  @Override
  default void close() throws StorageException {
    rollback();
  }
}
