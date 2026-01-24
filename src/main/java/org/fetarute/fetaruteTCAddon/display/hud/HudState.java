package org.fetarute.fetaruteTCAddon.display.hud;

import java.util.Locale;
import java.util.Optional;

/**
 * HUD 状态枚举：用于 BossBar/ActionBar 模板按状态分支。
 *
 * <p>解析时兼容旧前缀（STOP/LAYOVER/TERMINAL_ARRIVING）。
 */
public enum HudState {
  IDLE,
  AT_STATION,
  ON_LAYOVER,
  DEPARTING,
  ARRIVING,
  TERM_ARRIVING,
  IN_TRIP;

  /** 解析模板中的状态 key（大小写不敏感）。 */
  public static Optional<HudState> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    if ("STOP".equals(normalized)) {
      return Optional.of(HudState.AT_STATION);
    }
    if ("LAYOVER".equals(normalized)) {
      return Optional.of(HudState.ON_LAYOVER);
    }
    if ("TERMINAL_ARRIVING".equals(normalized)) {
      return Optional.of(HudState.TERM_ARRIVING);
    }
    try {
      return Optional.of(HudState.valueOf(normalized));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }
}
