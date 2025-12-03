package org.fetarute.fetaruteTCAddon.storage.api;

/**
 * 存储层异常，包装底层 SQL/IO 错误。
 */
public class StorageException extends RuntimeException {
    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
