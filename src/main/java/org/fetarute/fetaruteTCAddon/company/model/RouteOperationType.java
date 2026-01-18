package org.fetarute.fetaruteTCAddon.company.model;

import java.util.Locale;
import java.util.Optional;

/** 路线运营属性：运营班次或回库/回送。 */
public enum RouteOperationType {

  /** 常规运营班次（客运/正线运行）。 */
  OPERATION,

  /** 回库/回送班次（非运营）。 */
  RETURN;

  private static final java.util.Map<String, RouteOperationType> TOKEN_MAP =
      java.util.Map.ofEntries(
          java.util.Map.entry("OPERATION", OPERATION),
          java.util.Map.entry("RETURN", RETURN),
          // 常用缩写
          java.util.Map.entry("OP", OPERATION),
          java.util.Map.entry("RET", RETURN),
          java.util.Map.entry("BACK", RETURN));

  /** 从命令/存储的字符串解析为枚举值（兼容常用缩写）。 */
  public static Optional<RouteOperationType> fromToken(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    if (normalized.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(TOKEN_MAP.get(normalized));
  }
}
