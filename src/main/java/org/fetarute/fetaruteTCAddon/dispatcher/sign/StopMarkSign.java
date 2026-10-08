package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 停车位置标牌子：{@code [train]} / {@code stopmark} / {@code car:4} / {@code door:1}。
 *
 * <p>列车车头最前端（第一节车厢的车体前端）停在这块牌子的轨道中心。第三、第四行各写一项：
 *
 * <ul>
 *   <li>{@code car:}（也可写 {@code carriage:}，必填）：适用的节数（TrainCarts 车厢数）。单个 {@code 4}、多个 {@code 4,6}、范围
 *       {@code 3-5}，或 {@code *} 表示任意节数；
 *   <li>{@code door:}（选填）：哪几节车厢开关门，从行进方向上的第一节数起，写法同上，例如 {@code door:1} 只开第一节。不写或写 {@code *} 时全车开门。
 *       适用的最短编组也必须至少有一节开门车厢。
 * </ul>
 *
 * 逗号、减号、冒号写成全角也认。
 *
 * <p>本类只做解析，不依赖服务器对象。
 *
 * @param ranges 适用的节数范围；为空表示任意节数
 * @param doors 开关门的车厢位置（行进方向上的第一节为 1）；为空表示全车开门
 */
public record StopMarkSign(List<Range> ranges, List<Range> doors) {

  /** 第二行的类型名。 */
  public static final String TYPE = "stopmark";

  /** 第三、第四行的属性名（不分大小写）。 */
  private enum Key {
    CAR("car", "cars", "carriage", "carriages"),
    DOOR("door", "doors");

    private final Set<String> names;

    Key(String... names) {
      this.names = Set.of(names);
    }

    static Optional<Key> of(String name) {
      for (Key key : values()) {
        if (key.names.contains(name)) {
          return Optional.of(key);
        }
      }
      return Optional.empty();
    }
  }

  /** 节数或车厢位置的范围（含两端）。 */
  public record Range(int min, int max) {
    public Range {
      if (min < 1 || max < min) {
        throw new IllegalArgumentException("节数范围无效: " + min + "-" + max);
      }
    }
  }

  public StopMarkSign {
    ranges = List.copyOf(ranges);
    doors = List.copyOf(doors);
  }

  /** 全车开门。 */
  public StopMarkSign(List<Range> ranges) {
    this(ranges, List.of());
  }

  /** 第二行是不是停车位置标。 */
  public static boolean isStopMark(String line) {
    return line != null && TYPE.equalsIgnoreCase(line.trim());
  }

  /**
   * 解析第三、第四行：两行各写一项、顺序不限，空行或别的文字不管。
   *
   * <p>{@code car:} 与旧版一致：两行都写时取第一行写对的，写错的一行不管。
   *
   * @return 没有写对的 {@code car:}、{@code door:} 写错，或适用的最短编组没有 {@code door:} 写的车厢时为空
   */
  public static Optional<StopMarkSign> parse(String line3, String line4) {
    List<Range> cars = null;
    List<Range> doors = List.of();
    for (String line : Arrays.asList(line3, line4)) {
      Optional<Map.Entry<Key, String>> attribute = attributeOf(line);
      if (attribute.isEmpty()) {
        continue;
      }
      Optional<List<Range>> spec = parseSpec(attribute.get().getValue());
      if (attribute.get().getKey() == Key.CAR) {
        if (cars == null) {
          cars = spec.orElse(null);
        }
      } else if (spec.isEmpty()) {
        return Optional.empty();
      } else {
        doors = spec.get();
      }
    }
    if (cars == null) {
      return Optional.empty();
    }
    StopMarkSign sign = new StopMarkSign(cars, doors);
    return sign.doorsOnShortestTrain() ? Optional.of(sign) : Optional.empty();
  }

  private static Optional<Map.Entry<Key, String>> attributeOf(String line) {
    if (line == null) {
      return Optional.empty();
    }
    String text = line.trim().replace('：', ':');
    int colon = text.indexOf(':');
    if (colon < 0) {
      return Optional.empty();
    }
    return Key.of(text.substring(0, colon).trim().toLowerCase(Locale.ROOT))
        .map(key -> Map.entry(key, text.substring(colon + 1)));
  }

  /** 解析 {@code 4}、{@code 4,6}、{@code 3-5}、{@code *}；{@code *} 为空列表。 */
  private static Optional<List<Range>> parseSpec(String spec) {
    String value = spec.replace(" ", "").replace('，', ',').replace('－', '-');
    if (value.isEmpty()) {
      return Optional.empty();
    }
    if ("*".equals(value)) {
      return Optional.of(List.of());
    }
    List<Range> ranges = new ArrayList<>();
    for (String part : value.split(",", -1)) {
      Optional<Range> range = parseRange(part);
      if (range.isEmpty()) {
        return Optional.empty();
      }
      ranges.add(range.get());
    }
    return Optional.of(ranges);
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

  /** 适用的最短编组（任意节数时为 1 节）也有 {@code door:} 写的车厢；否则那样的列车停在这里一扇门也开不了。 */
  private boolean doorsOnShortestTrain() {
    if (doors.isEmpty()) {
      return true;
    }
    int firstDoor = doors.stream().mapToInt(Range::min).min().orElse(1);
    int shortest = ranges.stream().mapToInt(Range::min).min().orElse(1);
    return firstDoor <= shortest;
  }

  /** 是否适用于这个节数。 */
  public boolean matches(int carriages) {
    return contains(ranges, carriages);
  }

  /** 是否全车开门（没写 {@code door:} 或写了 {@code *}）。 */
  public boolean allDoors() {
    return doors.isEmpty();
  }

  /**
   * 这一节车厢开不开门。
   *
   * @param position 车厢位置，行进方向上的第一节为 1
   */
  public boolean opensDoorsAt(int position) {
    return contains(doors, position);
  }

  private static boolean contains(List<Range> ranges, int value) {
    if (ranges.isEmpty()) {
      return value >= 1;
    }
    for (Range range : ranges) {
      if (value >= range.min() && value <= range.max()) {
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
    return describe(ranges);
  }

  /** 给玩家看的开门车厢写法，全车开门时为 {@code *}。 */
  public String describeDoors() {
    return describe(doors);
  }

  private static String describe(List<Range> ranges) {
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
