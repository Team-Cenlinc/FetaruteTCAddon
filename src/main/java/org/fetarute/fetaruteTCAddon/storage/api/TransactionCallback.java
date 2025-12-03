package org.fetarute.fetaruteTCAddon.storage.api;

/**
 * 事务回调。
 */
@FunctionalInterface
public interface TransactionCallback<T> {
    T doInTransaction() throws StorageException;
}
