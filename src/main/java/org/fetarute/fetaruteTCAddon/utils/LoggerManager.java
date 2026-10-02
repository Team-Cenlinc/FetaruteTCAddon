package org.fetarute.fetaruteTCAddon.utils;

import java.util.logging.Level;
import java.util.logging.Logger;

/** 简易日志封装，统一处理 debug 开关。 */
public final class LoggerManager {

  private final Logger logger;
  private volatile boolean debugEnabled;
  private final DiagnosticSink debugSink = new DebugSink();

  public LoggerManager(Logger logger) {
    this.logger = logger;
  }

  /** 返回底层 JDK Logger，供特殊场景使用。 */
  public Logger underlying() {
    return logger;
  }

  public void setDebugEnabled(boolean debugEnabled) {
    this.debugEnabled = debugEnabled;
  }

  /** debug 日志此刻是否开启。 */
  public boolean debugEnabled() {
    return debugEnabled;
  }

  /** 写入 debug 日志的诊断输出端，随 debug 开关实时生效。 */
  public DiagnosticSink debugSink() {
    return debugSink;
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

  private final class DebugSink implements DiagnosticSink {
    @Override
    public void accept(String message) {
      debug(message);
    }

    @Override
    public boolean enabled() {
      return debugEnabled;
    }
  }
}
