package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 停车位置标牌子：{@code [train]} / {@code stopmark} / {@code carriage:4}。
 *
 * <p>列车车头停在这块牌子的轨道上；第三或第四行的 {@code carriage:} 写适用的节数（TrainCarts 车厢数）：单个 {@code 4}、 多个 {@code
 * 4,6}、范围 {@code 3-5}，或 {@code *} 表示任意节数。本类只做解析，不依赖服务器对象。
 *
 * @param ranges 适用的节数范围；为空表示任意节数
 */
public record StopMarkSign(List<Range> ranges) {

  /** 第二行的类型名。 */
  public static final String TYPE = "stopmark";

  private static final String KEY = "carriage";

  /** 节数范围（含两端）。 */
  public record Range(int min, int max) {
    public Range {
      if (min < 1 || max < min) {
        throw new IllegalArgumentException("节数范围无效: " + min + "-" + max);
      }
    }
  }

  public StopMarkSign {
    ranges = List.copyOf(ranges);
  }

  /** 第二行是不是停车位置标。 */
  public static boolean isStopMark(String line) {
    return line != null && TYPE.equalsIgnoreCase(line.trim());
  }

  /** 从第三、第四行找 {@code carriage:} 并解析；找不到或写错时为空。 */
  public static Optional<StopMarkSign> parse(String line3, String line4) {
    Optional<StopMarkSign> fromThird = parseLine(line3);
    return fromThird.isPresent() ? fromThird : parseLine(line4);
  }

  private static Optional<StopMarkSign> parseLine(String line) {
    if (line == null) {
      return Optional.empty();
    }
    String text = line.trim().replace('：', ':');
    int colon = text.indexOf(':');
    if (colon < 0) {
      return Optional.empty();
    }
    String key = text.substring(0, colon).trim().toLowerCase(Locale.ROOT);
    if (!KEY.equals(key) && !(KEY + "s").equals(key)) {
      return Optional.empty();
    }
    return parseSpec(text.substring(colon + 1));
  }

  private static Optional<StopMarkSign> parseSpec(String spec) {
    String value = spec.replace(" ", "");
    if (value.isEmpty()) {
      return Optional.empty();
    }
    if ("*".equals(value)) {
      return Optional.of(new StopMarkSign(List.of()));
    }
    List<Range> ranges = new ArrayList<>();
    for (String part : value.split(",", -1)) {
      Optional<Range> range = parseRange(part);
      if (range.isEmpty()) {
        return Optional.empty();
      }
      ranges.add(range.get());
    }
    return Optional.of(new StopMarkSign(ranges));
  }

  private static Optional<Range> parseRange(String part) {
    try {
      int dash = part.indexOf('-');
      int min = Integer.parseInt(dash < 0 ? part : part.substring(0, dash));
      int max = dash < 0 ? min : Integer.parseInt(part.substring(dash + 1));
      return min >= 1 && max >= min ? Optional.of(new Range(min, max)) : Optional.empty();
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  /** 是否适用于这个节数。 */
  public boolean matches(int carriages) {
    if (ranges.isEmpty()) {
      return carriages >= 1;
    }
    for (Range range : ranges) {
      if (carriages >= range.min() && carriages <= range.max()) {
        return true;
      }
    }
    return false;
  }

  /** 覆盖多少种节数（越少越专门）；任意节数为 {@link Integer#MAX_VALUE}。 */
  public int breadth() {
    if (ranges.isEmpty()) {
      return Integer.MAX_VALUE;
    }
    long total = 0L;
    for (Range range : ranges) {
      total += (long) range.max() - range.min() + 1L;
    }
    // 封顶在“任意节数”之下：写了超大范围的标志不会因溢出反而排到最前。
    return (int) Math.min(total, Integer.MAX_VALUE - 1L);
  }

  /** 给玩家看的节数写法，例如 {@code 4,6}、{@code 3-5}、{@code *}。 */
  public String describe() {
    if (ranges.isEmpty()) {
      return "*";
    }
    List<String> parts = new ArrayList<>();
    for (Range range : ranges) {
      parts.add(
          range.min() == range.max()
              ? String.valueOf(range.min())
              : range.min() + "-" + range.max());
    }
    return String.join(",", parts);
  }
}
