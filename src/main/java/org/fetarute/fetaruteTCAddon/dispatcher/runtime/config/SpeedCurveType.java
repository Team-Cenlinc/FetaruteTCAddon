package org.fetarute.fetaruteTCAddon.dispatcher.runtime.config;

import java.util.Locale;
import java.util.Optional;

/**
 * 速度曲线类型：控制“提前减速”的曲线形态。
 *
 * <p>PHYSICS 使用物理刹车上限（v = sqrt(2ad)），其余类型基于比例缩放目标速度。
 */
public enum SpeedCurveType {
  PHYSICS,
  LINEAR,
  QUADRATIC,
  CUBIC;

  /** 解析配置值；大小写不敏感，空值返回 empty。 */
  public static Optional<SpeedCurveType> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    for (SpeedCurveType type : values()) {
      if (type.name().equals(normalized)) {
        return Optional.of(type);
      }
    }
    return Optional.empty();
  }
}
