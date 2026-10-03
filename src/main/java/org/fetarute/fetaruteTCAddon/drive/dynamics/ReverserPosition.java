package org.fetarute.fetaruteTCAddon.drive.dynamics;

import java.util.Locale;
import java.util.Optional;

/**
 * 换向手柄的位置：前进、空挡、后退。
 *
 * <p>空挡时不能牵引（只能惰行或制动）；前进与后退决定列车朝驾驶员面朝的方向走，还是朝相反方向走。
 */
public enum ReverserPosition {
  FORWARD(1),
  NEUTRAL(0),
  REVERSE(-1);

  private final int sign;

  ReverserPosition(int sign) {
    this.sign = sign;
  }

  /** 前进为 +1，空挡为 0，后退为 -1。 */
  public int sign() {
    return sign;
  }

  /** 解析命令参数里的位置名（不区分大小写）。 */
  public static Optional<ReverserPosition> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    for (ReverserPosition position : values()) {
      if (position.name().equals(normalized)) {
        return Optional.of(position);
      }
    }
    return Optional.empty();
  }
}
