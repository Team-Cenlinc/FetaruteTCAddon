package org.fetarute.fetaruteTCAddon.utils;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 简易日志封装，统一处理 debug 开关。
 */
public final class LoggerManager {

    private final Logger logger;
    private volatile boolean debugEnabled;

    public LoggerManager(Logger logger) {
        this.logger = logger;
    }

    /**
     * 返回底层 JDK Logger，供特殊场景使用。
     */
    public Logger underlying() {
        return logger;
    }

    public void setDebugEnabled(boolean debugEnabled) {
        this.debugEnabled = debugEnabled;
    }

    public void info(String message) {
        logger.info(message);
    }

    public void warn(String message) {
        logger.warning(message);
    }

    public void error(String message) {
        logger.severe(message);
    }

    public void debug(String message) {
        if (debugEnabled) {
            logger.log(Level.INFO, "[DEBUG] {0}", message);
        }
    }
}
