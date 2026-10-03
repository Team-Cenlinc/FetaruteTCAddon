package org.fetarute.fetaruteTCAddon.drive.dynamics;

import java.util.Locale;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 动拖比：动车（带动力）在全列车厢中所占的比例。
 *
 * <p>接受两种写法：{@code 4M2T}（4 动 2 拖，比例 4/6，不区分大小写）和 {@code 0.67}（直接给 (0, 1] 内的比例）。
 */
public final class MotorRatio {

  private static final Pattern MT = Pattern.compile("(\\d+)M(\\d+)T");

  private MotorRatio() {}

  /**
   * 解析动拖比。
   *
   * @return 动车占比，位于 (0, 1]；写法无法识别、动车为 0 或总数为 0 时为空
   */
  public static OptionalDouble parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return OptionalDouble.empty();
    }
    String text = raw.trim().toUpperCase(Locale.ROOT);
    Matcher matcher = MT.matcher(text);
    if (matcher.matches()) {
      long motors = Long.parseLong(matcher.group(1));
      long trailers = Long.parseLong(matcher.group(2));
      long total = motors + trailers;
      if (motors <= 0 || total <= 0) {
        return OptionalDouble.empty();
      }
      return OptionalDouble.of((double) motors / (double) total);
    }
    try {
      double value = Double.parseDouble(text);
      if (Double.isFinite(value) && value > 0.0 && value <= 1.0) {
        return OptionalDouble.of(value);
      }
    } catch (NumberFormatException ignored) {
      // 写法不是数字，按无法识别处理。
    }
    return OptionalDouble.empty();
  }
}
