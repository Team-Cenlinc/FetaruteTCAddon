package org.fetarute.fetaruteTCAddon.display.hud.bossbar;

import java.util.Locale;
import java.util.Optional;

public enum BossBarHudState {
  IDLE,
  AT_STATION,
  ON_LAYOVER,
  DEPARTING,
  ARRIVING,
  IN_TRIP;

  public static Optional<BossBarHudState> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    if ("STOP".equals(normalized)) {
      return Optional.of(BossBarHudState.AT_STATION);
    }
    if ("LAYOVER".equals(normalized)) {
      return Optional.of(BossBarHudState.ON_LAYOVER);
    }
    try {
      return Optional.of(BossBarHudState.valueOf(normalized));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }
}
