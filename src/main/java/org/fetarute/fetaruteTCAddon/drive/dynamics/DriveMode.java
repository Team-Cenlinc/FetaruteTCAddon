package org.fetarute.fetaruteTCAddon.drive.dynamics;

import java.util.Locale;
import java.util.Optional;

/**
 * 列车的动力配置方式。
 *
 * <ul>
 *   <li>{@link #MU}：动车组，动力分散在若干动车上，加速度随动车占比变化；
 *   <li>{@link #LOCO}：机车牵引，动力集中在机车上，牵引车厢越多加速越慢。
 * </ul>
 */
public enum DriveMode {
  MU,
  LOCO;

  /** 解析标签或命令参数里的模式名（不区分大小写）。 */
  public static Optional<DriveMode> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    for (DriveMode mode : values()) {
      if (mode.name().equals(normalized)) {
        return Optional.of(mode);
      }
    }
    return Optional.empty();
  }
}
