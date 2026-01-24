package org.fetarute.fetaruteTCAddon.display.template;

import java.util.Locale;
import java.util.Optional;

/** HUD 模板类型：用于区分不同展示通道的模板。 */
public enum HudTemplateType {
  BOSSBAR,
  ACTIONBAR,
  ANNOUNCEMENT,
  PLAYER_DISPLAY,
  STATION_DISPLAY;

  /** 从文本解析模板类型（不区分大小写）。 */
  public static Optional<HudTemplateType> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(HudTemplateType.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }
}
