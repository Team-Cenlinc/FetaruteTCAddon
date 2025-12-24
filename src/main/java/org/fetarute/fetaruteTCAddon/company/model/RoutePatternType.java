package org.fetarute.fetaruteTCAddon.company.model;

import java.util.Locale;
import java.util.Optional;

/** 运行模式，与 TrainCarts route 类型对应。 */
public enum RoutePatternType {

  /** 各站停车。 */
  LOCAL,

  /** 快速：跳站但停靠站较多（常见于“快车/快线”）。 */
  RAPID,

  /** 新快速：比 RAPID 更少停靠（例如“新快速/新快车”）。 */
  NEO_RAPID,

  /** 特快：长距离跳站，停靠站更少。 */
  EXPRESS,

  /** 限定特快：停靠最少，通常需要额外定义或车次区分。 */
  LIMITED_EXPRESS;

  private static final java.util.Map<String, RoutePatternType> TOKEN_MAP =
      java.util.Map.ofEntries(
          java.util.Map.entry("LOCAL", LOCAL),
          java.util.Map.entry("RAPID", RAPID),
          java.util.Map.entry("NEO_RAPID", NEO_RAPID),
          java.util.Map.entry("EXPRESS", EXPRESS),
          java.util.Map.entry("LIMITED_EXPRESS", LIMITED_EXPRESS),
          // 常用缩写/历史值兼容
          java.util.Map.entry("LTD_EXPRESS", LIMITED_EXPRESS));

  /**
   * 从命令/存储的字符串解析为枚举值（兼容历史值与常用别名）。
   *
   * <p>兼容规则：{@code LTD_EXPRESS}（常用缩写）→ {@link #LIMITED_EXPRESS}。
   */
  public static Optional<RoutePatternType> fromToken(String raw) {
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
