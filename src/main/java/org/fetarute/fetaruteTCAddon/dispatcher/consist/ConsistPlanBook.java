package org.fetarute.fetaruteTCAddon.dispatcher.consist;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;

/**
 * 编组方案书的写法与解析。
 *
 * <p>每行一个车型：行首是权重（正整数），然后是编组写法，写到第一个 {@code |} 为止，中间可以有空格；{@code |} 之后是可选的覆盖项 {@code 键=值}。空行与
 * {@code #}、{@code //} 开头的行忽略。
 *
 * <pre>
 * # 6 节为主，8 节补充
 * 3 SH_A6
 * 1 SH_A8 | name=8 节编组 | type=emu
 * 1 loco_df4 6*coach_25g | max-bps=22
 * </pre>
 *
 * <p>只做文本层面的检查；编组能不能在 TrainCarts 里解析出来，由车型档案在主线程另行检查。
 */
public final class ConsistPlanBook {

  /** 权重上限，与 route 的 {@code spawn_weight} 相同。 */
  public static final int MAX_WEIGHT = 1000;

  /** 覆盖项：显示名。 */
  public static final String KEY_NAME = "name";

  /** 覆盖项：车种。 */
  public static final String KEY_TYPE = "type";

  /** 覆盖项：加速度。 */
  public static final String KEY_ACCEL = "accel";

  /** 覆盖项：减速度。 */
  public static final String KEY_DECEL = "decel";

  /** 覆盖项：最高速度。 */
  public static final String KEY_MAX_SPEED = "max-bps";

  private ConsistPlanBook() {}

  /** 问题种类。 */
  public enum ProblemKind {
    /** 一个车型都没写。 */
    EMPTY,
    /** 行首不是 1..{@value #MAX_WEIGHT} 的整数。 */
    BAD_WEIGHT,
    /** 有权重、没有编组写法。 */
    MISSING_PATTERN,
    /** 写法里有 {@code %}：TrainCarts 会按概率随机抽车，出车结果不确定。 */
    RANDOM_PATTERN,
    /** 不认识的覆盖项，或缺少 {@code =}。 */
    UNKNOWN_KEY,
    /** 覆盖项的值不合法，或同一行写了两次。 */
    BAD_VALUE,
    /** 同一份方案里同一个编组写了两行。 */
    DUPLICATE_PATTERN
  }

  /**
   * 一行车型。
   *
   * @param lineNo 行号（从 1 起，按书里的行数，含注释与空行）
   * @param weight 权重
   * @param pattern 编组写法（规整后的书写形式）
   * @param overrides 覆盖项
   */
  public record Entry(int lineNo, int weight, String pattern, ConsistOverrides overrides) {
    public Entry {
      Objects.requireNonNull(pattern, "pattern");
      Objects.requireNonNull(overrides, "overrides");
    }

    /** 比较用的车型键。 */
    public String key() {
      return ConsistKey.of(pattern).orElseThrow();
    }
  }

  /**
   * 一处问题。
   *
   * @param lineNo 行号；{@link ProblemKind#EMPTY} 时为 0
   * @param kind 种类
   * @param detail 出问题的原文片段
   */
  public record Problem(int lineNo, ProblemKind kind, String detail) {
    public Problem {
      Objects.requireNonNull(kind, "kind");
      detail = detail == null ? "" : detail;
    }
  }

  /**
   * 解析结果。
   *
   * @param entries 解析成功的车型，按书里的顺序
   * @param problems 问题，按行号
   */
  public record Parsed(List<Entry> entries, List<Problem> problems) {
    public Parsed {
      entries = List.copyOf(entries);
      problems = List.copyOf(problems);
    }

    /** 没有任何问题。 */
    public boolean ok() {
      return problems.isEmpty();
    }
  }

  /**
   * 解析一份方案的正文。
   *
   * @param lines 书里的各行
   * @return 解析结果；有问题时 {@code entries} 只含没出问题的行
   */
  public static Parsed parse(List<String> lines) {
    List<Entry> entries = new ArrayList<>();
    List<Problem> problems = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    List<String> source = lines == null ? List.of() : lines;
    for (int i = 0; i < source.size(); i++) {
      int lineNo = i + 1;
      String raw = source.get(i) == null ? "" : source.get(i).trim();
      if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("//")) {
        continue;
      }
      parseLine(lineNo, raw, problems)
          .ifPresent(
              entry -> {
                if (seen.add(entry.key())) {
                  entries.add(entry);
                } else {
                  problems.add(new Problem(lineNo, ProblemKind.DUPLICATE_PATTERN, entry.pattern()));
                }
              });
    }
    if (entries.isEmpty() && problems.isEmpty()) {
      problems.add(new Problem(0, ProblemKind.EMPTY, ""));
    }
    return new Parsed(entries, problems);
  }

  /**
   * 把正文按行拆开再解析；存库的正文用换行分行。
   *
   * @param body 方案正文
   * @return 解析结果
   */
  public static Parsed parse(String body) {
    return parse(body == null ? List.of() : List.of(body.split("\\R", -1)));
  }

  private static Optional<Entry> parseLine(int lineNo, String raw, List<Problem> problems) {
    String[] segments = raw.split("\\|", -1);
    String head = segments[0].trim();
    int space = indexOfWhitespace(head);
    String weightToken = space < 0 ? head : head.substring(0, space);
    Optional<Integer> weight = parseWeight(weightToken);
    if (weight.isEmpty()) {
      problems.add(new Problem(lineNo, ProblemKind.BAD_WEIGHT, weightToken));
      return Optional.empty();
    }
    Optional<String> pattern =
        space < 0 ? Optional.empty() : ConsistKey.tidy(head.substring(space + 1));
    if (pattern.isEmpty()) {
      problems.add(new Problem(lineNo, ProblemKind.MISSING_PATTERN, head));
      return Optional.empty();
    }
    if (pattern.get().indexOf('%') >= 0) {
      problems.add(new Problem(lineNo, ProblemKind.RANDOM_PATTERN, pattern.get()));
      return Optional.empty();
    }
    Optional<ConsistOverrides> overrides = parseOverrides(lineNo, segments, problems);
    return overrides.map(value -> new Entry(lineNo, weight.get(), pattern.get(), value));
  }

  private static Optional<ConsistOverrides> parseOverrides(
      int lineNo, String[] segments, List<Problem> problems) {
    Optional<String> name = Optional.empty();
    Optional<TrainType> type = Optional.empty();
    OptionalDouble accel = OptionalDouble.empty();
    OptionalDouble decel = OptionalDouble.empty();
    OptionalDouble maxSpeed = OptionalDouble.empty();
    Set<String> keys = new HashSet<>();
    boolean failed = false;
    for (int s = 1; s < segments.length; s++) {
      String segment = segments[s].trim();
      if (segment.isEmpty()) {
        continue;
      }
      int eq = segment.indexOf('=');
      if (eq <= 0) {
        problems.add(new Problem(lineNo, ProblemKind.UNKNOWN_KEY, segment));
        failed = true;
        continue;
      }
      String key = segment.substring(0, eq).trim().toLowerCase(Locale.ROOT);
      String value = segment.substring(eq + 1).trim();
      if (!keys.add(key)) {
        problems.add(new Problem(lineNo, ProblemKind.BAD_VALUE, segment));
        failed = true;
        continue;
      }
      switch (key) {
        case KEY_NAME -> {
          if (value.isEmpty()) {
            problems.add(new Problem(lineNo, ProblemKind.BAD_VALUE, segment));
            failed = true;
          } else {
            name = Optional.of(value);
          }
        }
        case KEY_TYPE -> {
          type = TrainType.parse(value);
          if (type.isEmpty()) {
            problems.add(new Problem(lineNo, ProblemKind.BAD_VALUE, segment));
            failed = true;
          }
        }
        case KEY_ACCEL, KEY_DECEL, KEY_MAX_SPEED -> {
          OptionalDouble parsed = parsePositive(value);
          if (parsed.isEmpty()) {
            problems.add(new Problem(lineNo, ProblemKind.BAD_VALUE, segment));
            failed = true;
          } else if (key.equals(KEY_ACCEL)) {
            accel = parsed;
          } else if (key.equals(KEY_DECEL)) {
            decel = parsed;
          } else {
            maxSpeed = parsed;
          }
        }
        default -> {
          problems.add(new Problem(lineNo, ProblemKind.UNKNOWN_KEY, segment));
          failed = true;
        }
      }
    }
    return failed
        ? Optional.empty()
        : Optional.of(new ConsistOverrides(name, type, accel, decel, maxSpeed));
  }

  private static int indexOfWhitespace(String text) {
    for (int i = 0; i < text.length(); i++) {
      if (Character.isWhitespace(text.charAt(i))) {
        return i;
      }
    }
    return -1;
  }

  private static Optional<Integer> parseWeight(String token) {
    try {
      int value = Integer.parseInt(token);
      return value >= 1 && value <= MAX_WEIGHT ? Optional.of(value) : Optional.empty();
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  private static OptionalDouble parsePositive(String token) {
    try {
      double value = Double.parseDouble(token);
      return Double.isFinite(value) && value > 0.0
          ? OptionalDouble.of(value)
          : OptionalDouble.empty();
    } catch (NumberFormatException ex) {
      return OptionalDouble.empty();
    }
  }
}
