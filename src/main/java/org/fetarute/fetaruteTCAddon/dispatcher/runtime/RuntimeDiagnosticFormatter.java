package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.Locale;
import org.bukkit.Location;

/**
 * 运行时诊断日志格式化工具。
 *
 * <p>统一输出单行 key/value，避免 split/异常清理日志在多个入口各自拼接而格式漂移。
 */
final class RuntimeDiagnosticFormatter {

  private RuntimeDiagnosticFormatter() {}

  /**
   * 规范化单行日志值。
   *
   * <p>会压平换行与制表符，保留可读空格，便于 warning/debug 日志做 grep。
   *
   * @param value 原始值
   * @return 清洗后的单行文本；为空时返回 {@code null}
   */
  static String sanitizeInline(String value) {
    if (value == null) {
      return null;
    }
    String sanitized =
        value.trim().replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').replace('"', '\'');
    sanitized = sanitized.replaceAll("\\s+", " ");
    return sanitized.isBlank() ? null : sanitized;
  }

  /**
   * 追加单个 key/value 片段。
   *
   * <p>若 value 含空白字符，会自动加双引号，方便在日志中保留整体语义。
   *
   * @param builder 输出缓冲
   * @param key 字段名
   * @param value 字段值
   */
  static void appendKeyValue(StringBuilder builder, String key, String value) {
    if (builder == null) {
      return;
    }
    String sanitizedKey = sanitizeInline(key);
    String sanitizedValue = sanitizeInline(value);
    if (sanitizedKey == null || sanitizedValue == null) {
      return;
    }
    if (builder.length() > 0) {
      builder.append(' ');
    }
    builder.append(sanitizedKey).append('=');
    if (containsWhitespace(sanitizedValue)) {
      builder.append('"').append(sanitizedValue).append('"');
      return;
    }
    builder.append(sanitizedValue);
  }

  /**
   * 格式化 Bukkit 位置。
   *
   * @param location 位置
   * @return {@code world(x,y,z)} 形式；无法解析时返回 {@code null}
   */
  static String formatLocation(Location location) {
    if (location == null || location.getWorld() == null) {
      return null;
    }
    return String.format(
        Locale.ROOT,
        "%s(%.2f,%.2f,%.2f)",
        location.getWorld().getName(),
        location.getX(),
        location.getY(),
        location.getZ());
  }

  private static boolean containsWhitespace(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (Character.isWhitespace(value.charAt(i))) {
        return true;
      }
    }
    return false;
  }
}
