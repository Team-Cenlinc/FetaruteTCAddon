package org.fetarute.fetaruteTCAddon.storage.api;

/**
 * 事务执行器，屏蔽底层实现差异。
 */
public interface StorageTransactionManager {

    <T> T execute(TransactionCallback<T> callback) throws StorageException;
}
