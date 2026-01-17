package org.fetarute.fetaruteTCAddon.dispatcher.runtime.config;

import java.util.Locale;
import java.util.Optional;

/** 车种枚举，用于加减速与速度配置的默认模板。 */
public enum TrainType {
  EMU,
  DMU,
  DIESEL_PUSH_PULL,
  ELECTRIC_LOCO;

  public static Optional<TrainType> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    if (normalized.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(TrainType.valueOf(normalized));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }
}
