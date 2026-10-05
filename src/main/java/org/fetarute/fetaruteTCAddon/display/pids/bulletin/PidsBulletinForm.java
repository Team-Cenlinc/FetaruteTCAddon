package org.fetarute.fetaruteTCAddon.display.pids.bulletin;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 公告对话框里填的内容：原样保存（出错时原样填回对话框），校验通过后转成公告。
 *
 * <ul>
 *   <li>中文标题必填，其余可留空；各项有长度上限（对话框输入框也按同样的上限截住）。
 *   <li>车站、线路填代码，以逗号（全角半角均可）或空格分隔，须是本运营商已有的；留空表示不限。
 *   <li>时刻按服务器时区，可写 {@code 2026-10-05 22:00}、{@code 10-05 22:00}（今年；早于今天半年以上的算明年）或 {@code
 *       22:00}（今天）； 开始留空为立即，结束留空为长期；结束须晚于开始，且尚未过去。
 * </ul>
 *
 * <p>是否在各种屏幕上排得下由调用方另行检查（需要字体）。
 *
 * @param level 等级的取值（{@code normal} 或 {@code important}）
 * @param titlePrimary 中文标题
 * @param titleSecondary 英文标题
 * @param bodyPrimary 中文正文
 * @param bodySecondary 英文正文
 * @param stations 车站代码清单
 * @param lines 线路代码清单
 * @param startsAt 开始时刻
 * @param endsAt 结束时刻
 */
public record PidsBulletinForm(
    String level,
    String titlePrimary,
    String titleSecondary,
    String bodyPrimary,
    String bodySecondary,
    String stations,
    String lines,
    String startsAt,
    String endsAt) {

  /** 中文标题最多几个字。 */
  public static final int TITLE_MAX = 24;

  /** 英文标题最多几个字符。 */
  public static final int TITLE_SECONDARY_MAX = 64;

  /** 中文正文最多几个字。 */
  public static final int BODY_MAX = 200;

  /** 英文正文最多几个字符。 */
  public static final int BODY_SECONDARY_MAX = 400;

  /** 车站、线路清单最多几个字符。 */
  public static final int CODES_MAX = 200;

  /** 时刻最多几个字符。 */
  public static final int TIME_MAX = 16;

  /** 填回对话框、列表里显示时刻的格式。 */
  public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

  /** 不写年份的日期早于今天这么多个月时算明年（年底写“01-02”指的是下个月）。 */
  private static final int SHORT_DATE_LOOKBACK_MONTHS = 6;

  /** 等级的取值。 */
  public static final String NORMAL = "normal";

  public static final String IMPORTANT = "important";

  public PidsBulletinForm {
    level = clean(level);
    titlePrimary = clean(titlePrimary);
    titleSecondary = clean(titleSecondary);
    bodyPrimary = clean(bodyPrimary);
    bodySecondary = clean(bodySecondary);
    stations = clean(stations);
    lines = clean(lines);
    startsAt = clean(startsAt);
    endsAt = clean(endsAt);
  }

  /** 空表单（新建公告）。 */
  public static PidsBulletinForm empty() {
    return new PidsBulletinForm(NORMAL, "", "", "", "", "", "", "", "");
  }

  /** 已有公告的内容（修改公告时填进对话框）。 */
  public static PidsBulletinForm of(PidsBulletin bulletin, ZoneId zone) {
    return new PidsBulletinForm(
        bulletin.important() ? IMPORTANT : NORMAL,
        bulletin.title().primary(),
        bulletin.title().secondary(),
        bulletin.body().primary(),
        bulletin.body().secondary(),
        String.join(", ", bulletin.stations()),
        String.join(", ", bulletin.lines()),
        bulletin.startsAt().map(at -> TIME.format(at.atZone(zone))).orElse(""),
        bulletin.endsAt().map(at -> TIME.format(at.atZone(zone))).orElse(""));
  }

  /**
   * 一个问题：语言文件里的键与占位符。
   *
   * @param key 键（{@code pids.bulletin.error.} 之后的部分）
   * @param values 占位符
   */
  public record Problem(String key, Map<String, String> values) {

    public Problem {
      Objects.requireNonNull(key, "key");
      values = Map.copyOf(values);
    }

    static Problem of(String key) {
      return new Problem(key, Map.of());
    }
  }

  /**
   * 校验通过后的内容。
   *
   * @param level 等级
   * @param title 标题
   * @param body 正文
   * @param stations 车站代码（大写）
   * @param lines 线路代码（大写）
   * @param startsAt 开始时刻
   * @param endsAt 结束时刻
   */
  public record Parsed(
      PidsBulletin.Level level,
      PidsBulletin.Text title,
      PidsBulletin.Text body,
      Set<String> stations,
      Set<String> lines,
      Optional<Instant> startsAt,
      Optional<Instant> endsAt) {

    public Parsed {
      stations = Set.copyOf(stations);
      lines = Set.copyOf(lines);
    }
  }

  /**
   * 校验。
   *
   * @param stationExists 本运营商有没有这个车站（代码已转大写）
   * @param lineExists 本运营商有没有这条线路（代码已转大写）
   * @param now 当前时刻
   * @param zone 服务器时区
   * @param problems 发现的问题追加到这里
   * @return 没有问题时为校验通过的内容
   */
  public Optional<Parsed> parse(
      Predicate<String> stationExists,
      Predicate<String> lineExists,
      Instant now,
      ZoneId zone,
      List<Problem> problems) {
    int before = problems.size();
    if (titlePrimary.isEmpty()) {
      problems.add(Problem.of("title-required"));
    }
    tooLong("title", titlePrimary, TITLE_MAX, problems);
    tooLong("title-secondary", titleSecondary, TITLE_SECONDARY_MAX, problems);
    tooLong("body", bodyPrimary, BODY_MAX, problems);
    tooLong("body-secondary", bodySecondary, BODY_SECONDARY_MAX, problems);
    Set<String> stationCodes = codes(stations);
    for (String code : stationCodes) {
      if (!stationExists.test(code)) {
        problems.add(new Problem("unknown-station", Map.of("code", code)));
      }
    }
    Set<String> lineCodes = codes(lines);
    for (String code : lineCodes) {
      if (!lineExists.test(code)) {
        problems.add(new Problem("unknown-line", Map.of("code", code)));
      }
    }
    Optional<Instant> starts = time(startsAt, now, zone, problems);
    Optional<Instant> ends = time(endsAt, now, zone, problems);
    if (starts.isPresent() && ends.isPresent() && !ends.get().isAfter(starts.get())) {
      problems.add(Problem.of("ends-before-starts"));
    } else if (ends.isPresent() && !ends.get().isAfter(now)) {
      problems.add(Problem.of("ended-already"));
    }
    if (problems.size() > before) {
      return Optional.empty();
    }
    return Optional.of(
        new Parsed(
            IMPORTANT.equals(level) ? PidsBulletin.Level.IMPORTANT : PidsBulletin.Level.NORMAL,
            new PidsBulletin.Text(titlePrimary, titleSecondary),
            new PidsBulletin.Text(bodyPrimary, bodySecondary),
            stationCodes,
            lineCodes,
            starts,
            ends));
  }

  private static void tooLong(String field, String value, int max, List<Problem> problems) {
    if (value.codePointCount(0, value.length()) > max) {
      problems.add(new Problem("too-long", Map.of("field", field, "max", String.valueOf(max))));
    }
  }

  /** 代码清单：逗号（全角半角）、顿号或空白分隔，转大写、去重，保持填写顺序。 */
  static Set<String> codes(String raw) {
    Set<String> codes = new LinkedHashSet<>();
    for (String part : raw.split("[,，、\\s]+")) {
      String code = part.trim().toUpperCase(Locale.ROOT);
      if (!code.isEmpty()) {
        codes.add(code);
      }
    }
    return codes;
  }

  /** 解析时刻；留空为空，写错时记一个问题。 */
  static Optional<Instant> time(String raw, Instant now, ZoneId zone, List<Problem> problems) {
    if (raw.isEmpty()) {
      return Optional.empty();
    }
    String value = raw.replace('/', '-').replace('：', ':').replaceAll("\\s+", " ");
    LocalDate today = LocalDate.ofInstant(now, zone);
    try {
      return Optional.of(LocalDateTime.parse(value, TIME).atZone(zone).toInstant());
    } catch (DateTimeParseException ignored) {
      // 再试短写法
    }
    try {
      LocalDateTime parsed = LocalDateTime.parse(today.getYear() + "-" + value, TIME);
      if (parsed.toLocalDate().isBefore(today.minusMonths(SHORT_DATE_LOOKBACK_MONTHS))) {
        parsed = parsed.plusYears(1);
      }
      return Optional.of(parsed.atZone(zone).toInstant());
    } catch (DateTimeParseException ignored) {
      // 再试只写时刻
    }
    try {
      return Optional.of(LocalTime.parse(value, CLOCK).atDate(today).atZone(zone).toInstant());
    } catch (DateTimeParseException ignored) {
      problems.add(new Problem("bad-time", Map.of("value", raw)));
      return Optional.empty();
    }
  }

  /** 去掉首尾空白，{@code null} 视为空串；正文里的换行保留。 */
  private static String clean(String raw) {
    return raw == null ? "" : raw.strip();
  }
}
