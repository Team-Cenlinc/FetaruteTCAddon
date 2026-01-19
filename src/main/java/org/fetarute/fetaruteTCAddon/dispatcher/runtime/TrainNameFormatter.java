package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;

/** 列车名生成器：统一自动/手动出车的 trainName 格式。 */
public final class TrainNameFormatter {

  private TrainNameFormatter() {}

  /**
   * 生成列车名：{@code <OP>-<LINE>-<PATTERN><DEST>-<SEQ>}.
   *
   * <p>其中：
   *
   * <ul>
   *   <li>{@code OP}/{@code LINE}：运营商/线路 code（空值会回退为占位符）
   *   <li>{@code PATTERN}：运行图模式首字母（L/R/N/E/X）
   *   <li>{@code DEST}：目的地显示名的首字符（用于人眼快速识别）
   *   <li>{@code SEQ}：由 runId 派生的 4 位序号（仅用于区分，不保证连续）
   * </ul>
   */
  public static String buildTrainName(
      String operator, String line, RoutePatternType pattern, String destName, UUID runId) {
    Objects.requireNonNull(runId, "runId");
    String op = safeNameToken(operator, "OP");
    String ln = safeNameToken(line, "LINE");
    String patternInit = patternInitial(pattern);
    String destInit = firstGlyph(destName);
    int raw = runId.hashCode();
    int normalized = raw == Integer.MIN_VALUE ? 0 : Math.abs(raw);
    String seq = String.format(Locale.ROOT, "%04d", normalized % 10000);
    return op + "-" + ln + "-" + patternInit + destInit + "-" + seq;
  }

  private static String patternInitial(RoutePatternType pattern) {
    if (pattern == null) {
      return "X";
    }
    return switch (pattern) {
      case LOCAL -> "L";
      case RAPID -> "R";
      case NEO_RAPID -> "N";
      case EXPRESS -> "E";
      case LIMITED_EXPRESS -> "X";
    };
  }

  private static String safeNameToken(String value, String fallback) {
    if (value == null) {
      return fallback;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? fallback : trimmed;
  }

  private static String firstGlyph(String value) {
    if (value == null) {
      return "?";
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return "?";
    }
    int cp = trimmed.codePointAt(0);
    return new String(Character.toChars(cp));
  }
}
