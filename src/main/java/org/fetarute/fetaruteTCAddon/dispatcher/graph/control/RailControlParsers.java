package org.fetarute.fetaruteTCAddon.dispatcher.graph.control;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 调度图运维命令的轻量参数解析器。
 *
 * <p>该类只负责把玩家输入转换为强类型值，不访问存储与图快照。`/fta graph edge ...` 与 `/fta speed section ...`
 * 共用这里的单位规则，避免底层区间限速和业务区间限速出现不同口径。
 */
public final class RailControlParsers {

  private static final Pattern SPEED_PATTERN =
      Pattern.compile(
          "^\\s*(?<value>[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))\\s*(?<unit>kmh|km/h|kph|bps|bpt)?\\s*$",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern TTL_PATTERN = Pattern.compile("(?i)(\\d+)([smhd])");

  private RailControlParsers() {}

  /**
   * 解析速度参数并统一为 blocks/s。
   *
   * <p>支持：{@code 80kmh} / {@code 8bps} / {@code 0.4bpt}；省略单位时默认视为 {@code kmh}。
   */
  public static Optional<RailSpeed> parseSpeed(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    Matcher matcher = SPEED_PATTERN.matcher(raw);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    String valueText = matcher.group("value");
    String unit = matcher.group("unit");
    if (valueText == null || valueText.isBlank()) {
      return Optional.empty();
    }
    double value;
    try {
      value = Double.parseDouble(valueText);
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
    if (!Double.isFinite(value) || value <= 0.0) {
      return Optional.empty();
    }

    String normalizedUnit = unit != null ? unit.trim().toLowerCase(Locale.ROOT) : "kmh";
    try {
      return switch (normalizedUnit) {
        case "kmh", "km/h", "kph" -> Optional.of(RailSpeed.ofKilometersPerHour(value));
        case "bps" -> Optional.of(RailSpeed.ofBlocksPerSecond(value));
        case "bpt" -> Optional.of(RailSpeed.ofBlocksPerTick(value));
        default -> Optional.empty();
      };
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  /**
   * 解析 TTL 参数。
   *
   * <p>支持 {@code 90s}、{@code 1m}、{@code 2h}、{@code 1d}，以及组合形式（如 {@code 1h30m}）。
   */
  public static Optional<Duration> parseTtl(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String trimmed = raw.trim();
    Matcher matcher = TTL_PATTERN.matcher(trimmed);
    int index = 0;
    Duration total = Duration.ZERO;
    boolean matchedAny = false;
    while (matcher.find()) {
      if (matcher.start() != index) {
        return Optional.empty();
      }
      matchedAny = true;
      long value;
      try {
        value = Long.parseLong(matcher.group(1));
      } catch (NumberFormatException ex) {
        return Optional.empty();
      }
      if (value <= 0) {
        return Optional.empty();
      }
      char unit = matcher.group(2).toLowerCase(Locale.ROOT).charAt(0);
      try {
        total =
            switch (unit) {
              case 's' -> total.plusSeconds(value);
              case 'm' -> total.plusMinutes(value);
              case 'h' -> total.plusHours(value);
              case 'd' -> total.plusDays(value);
              default -> total;
            };
      } catch (ArithmeticException ex) {
        return Optional.empty();
      }
      index = matcher.end();
    }
    if (!matchedAny || index != trimmed.length() || total.isZero() || total.isNegative()) {
      return Optional.empty();
    }
    return Optional.of(total);
  }
}
