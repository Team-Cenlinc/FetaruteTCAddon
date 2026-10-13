package org.fetarute.fetaruteTCAddon.dispatcher.route;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DYNAMIC 的不可变股道允许集合。
 *
 * <p>显式集合用排序、合并后的闭区间保存，不展开数值空间；不限股道是独立状态，空的显式集合无效。 相等性只比较资格语义，因此重复项、输入顺序和区间分隔符不会使已有选台失效。
 */
public final class TrackSelection {

  private static final Pattern ITEM = Pattern.compile("([0-9]+)(?:[:-]([0-9]+))?");

  /** 分隔符两侧的空白：旧版本容忍 {@code [1 : 3]}、{@code [1, 3]} 这类写法。 */
  private static final Pattern DELIMITER_SPACING = Pattern.compile("\\s*([\\[\\],:\\-])\\s*");

  private static final TrackSelection ALL = new TrackSelection(true, List.of());
  private final boolean unbounded;
  private final List<Interval> intervals;

  private TrackSelection(boolean unbounded, List<Interval> intervals) {
    this.unbounded = unbounded;
    this.intervals = List.copyOf(intervals);
  }

  /** 不限本站/车库现有的正整数股道。 */
  public static TrackSelection all() {
    return ALL;
  }

  /** 创建包含两端的闭区间；逆序端点按已有范围语义归一化。 */
  public static TrackSelection between(int from, int to) {
    if (from < 1 || to < 1) {
      throw new IllegalArgumentException("股道号必须为正整数");
    }
    return new TrackSelection(false, List.of(new Interval(Math.min(from, to), Math.max(from, to))));
  }

  /**
   * 解析显式选择器：单值、旧冒号区间，以及方括号内的单值/闭区间并集。
   *
   * <p>省略选择器由调用方使用 {@link #all()} 表达；空串、空括号、空项、正负号均失败，不放宽为不限股道。
   * 括号与分隔符两侧的空白、全角数字按旧版本兼容；两个数字之间夹着空白仍失败。
   *
   * @param raw 如 {@code [1,3,5]}、{@code [1,2,3-5]}、{@code [1:5]} 或 {@code 3}
   * @return 合并后的非空集合；任何一项无效时整条选择器失败
   */
  public static Optional<TrackSelection> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String body = DELIMITER_SPACING.matcher(halfWidthDigits(raw.trim())).replaceAll("$1");
    if (body.chars().anyMatch(Character::isWhitespace)) {
      return Optional.empty();
    }
    boolean bracketed = body.startsWith("[") && body.endsWith("]");
    if (bracketed) {
      body = body.substring(1, body.length() - 1);
    } else if (body.indexOf(',') >= 0 || body.indexOf('-') >= 0) {
      return Optional.empty();
    }
    List<Interval> parsed = new ArrayList<>();
    for (String item : body.split(",", -1)) {
      Matcher matcher = ITEM.matcher(item);
      if (!matcher.matches()) {
        return Optional.empty();
      }
      try {
        int from = Integer.parseInt(matcher.group(1));
        int to = matcher.group(2) == null ? from : Integer.parseInt(matcher.group(2));
        if (from < 1 || to < 1) {
          return Optional.empty();
        }
        parsed.add(new Interval(Math.min(from, to), Math.max(from, to)));
      } catch (NumberFormatException ex) {
        return Optional.empty();
      }
    }
    parsed.sort(Comparator.comparingInt(Interval::from).thenComparingInt(Interval::to));
    List<Interval> merged = new ArrayList<>();
    for (Interval interval : parsed) {
      if (merged.isEmpty()) {
        merged.add(interval);
        continue;
      }
      Interval previous = merged.get(merged.size() - 1);
      if ((long) interval.from() <= (long) previous.to() + 1L) {
        merged.set(
            merged.size() - 1,
            new Interval(previous.from(), Math.max(previous.to(), interval.to())));
      } else {
        merged.add(interval);
      }
    }
    return Optional.of(new TrackSelection(false, merged));
  }

  /** 全角数字换成半角；其它字符原样保留，由后续格式检查判定。 */
  private static String halfWidthDigits(String raw) {
    StringBuilder out = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char ch = raw.charAt(i);
      out.append(ch >= '\uFF10' && ch <= '\uFF19' ? (char) ('0' + (ch - '\uFF10')) : ch);
    }
    return out.toString();
  }

  /** 是否省略了选择器；与“显式范围很大”不同。 */
  public boolean isUnbounded() {
    return unbounded;
  }

  /** 精确资格判断；数值范围再大也只查询区间，不枚举股道号。 */
  public boolean contains(int track) {
    if (track < 1) {
      return false;
    }
    if (unbounded) {
      return true;
    }
    int low = 0;
    int high = intervals.size();
    while (low < high) {
      int mid = (low + high) >>> 1;
      Interval interval = intervals.get(mid);
      if (track < interval.from()) {
        high = mid;
      } else if (track > interval.to()) {
        low = mid + 1;
      } else {
        return true;
      }
    }
    return false;
  }

  /** 显式集合的最小允许股道；不限范围没有声明代表值。 */
  public OptionalInt firstDeclaredTrack() {
    return unbounded ? OptionalInt.empty() : OptionalInt.of(intervals.get(0).from());
  }

  /** 显式集合的最大允许股道；仅用于描述，不得作为成员判断。 */
  public OptionalInt lastDeclaredTrack() {
    return unbounded
        ? OptionalInt.empty()
        : OptionalInt.of(intervals.get(intervals.size() - 1).to());
  }

  /** 显式集合的元素总数；不限范围没有可计算的声明数量。 */
  public OptionalLong cardinality() {
    return unbounded
        ? OptionalLong.empty()
        : OptionalLong.of(intervals.stream().mapToLong(i -> (long) i.to() - i.from() + 1L).sum());
  }

  /** 可重新解析的选择器；不限范围为空，完整 DYNAMIC payload 应省略尾段。 */
  public Optional<String> formatSelector() {
    if (unbounded) {
      return Optional.empty();
    }
    if (intervals.size() == 1 && intervals.get(0).from() == intervals.get(0).to()) {
      return Optional.of(String.valueOf(intervals.get(0).from()));
    }
    return Optional.of(
        "[" + String.join(",", intervals.stream().map(Interval::format).toList()) + "]");
  }

  /**
   * 仅供有限长度的展示枚举；调用方必须明确给出数量上限，运行时候选应从真实图节点筛选。
   *
   * @param limit 最多返回的股道数
   * @return 按数字升序排列的显式股道；不限范围为空
   */
  public List<Integer> firstTracks(int limit) {
    return missingTracks(List.of(), limit);
  }

  /**
   * 取得显式集合里没有出现在已知节点中的股道样本。
   *
   * <p>通过已知股道切分空缺区间，只展开至多 {@code limit} 个样本；例如 1..MAX_VALUE 的范围不会制造巨量错误文案。
   *
   * @param present 已存在的股道号
   * @param limit 缺失样本上限
   * @return 缺失股道样本；不限范围或非正上限为空
   */
  public List<Integer> missingTracks(Collection<Integer> present, int limit) {
    if (unbounded || limit <= 0) {
      return List.of();
    }
    TreeSet<Integer> known = new TreeSet<>(present);
    List<Integer> missing = new ArrayList<>();
    for (Interval interval : intervals) {
      long cursor = interval.from();
      for (int track : known.subSet(interval.from(), true, interval.to(), true)) {
        appendGap(missing, cursor, (long) track - 1L, limit);
        if (missing.size() >= limit) {
          return List.copyOf(missing);
        }
        cursor = (long) track + 1L;
      }
      appendGap(missing, cursor, interval.to(), limit);
      if (missing.size() >= limit) {
        break;
      }
    }
    return List.copyOf(missing);
  }

  private static void appendGap(List<Integer> out, long from, long to, int limit) {
    for (long track = from; track <= to && out.size() < limit; track++) {
      out.add((int) track);
    }
  }

  @Override
  public boolean equals(Object value) {
    return value instanceof TrackSelection other
        && unbounded == other.unbounded
        && intervals.equals(other.intervals);
  }

  @Override
  public int hashCode() {
    return Objects.hash(unbounded, intervals);
  }

  @Override
  public String toString() {
    return formatSelector().orElse("全部");
  }

  private record Interval(int from, int to) {
    String format() {
      return from == to ? String.valueOf(from) : from + "-" + to;
    }
  }
}
