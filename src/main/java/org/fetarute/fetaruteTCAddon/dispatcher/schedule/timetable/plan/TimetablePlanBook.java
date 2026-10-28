package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.plan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 运行计划书的写法与解析。
 *
 * <p>书分两段，段头 {@code [时段]}（{@code [bands]}）与 {@code [定点]}（{@code [pins]}），不分大小写，也认全角括号 {@code 【】}；
 * 同一段可以分几次写。{@code #} 之后是注释，空行忽略。
 *
 * <pre>
 * [时段]
 * WS/WS_Full 06:00-07:00 300
 * WS_Full    07:00-09:30 180
 * [定点]
 * 05:32     WS-2C_FullR
 * 07:15:30  MT-3N_DPExp 加开
 * </pre>
 *
 * <ul>
 *   <li>时段行：{@code 组键 起-止 秒}。组键可带 {@code 线/} 前缀；起止写 {@code HH:mm} 或 {@code HH:mm:ss}，小时可到 {@value
 *       #MAX_HOUR}（跨零点写 {@code 24:30}）；起须早于止；秒为正整数；同一组键的时段不能重叠。
 *   <li>定点行：{@code 时刻 route} 后可跟 {@code 加开}（也认 {@code extra}、{@code +}）。同一时刻同一 route 不能写两次。
 * </ul>
 *
 * <p>正文按页存库，页与页之间用 {@link #PAGE_BREAK} 隔开，报错时能指到书里的页码与行号。
 */
public final class TimetablePlanBook {

  /** 存库正文里的分页符（换页符 U+000C）。 */
  public static final String PAGE_BREAK = "\f";

  /** 时刻里小时的上限：跨零点最多写到次日 23 点。 */
  public static final int MAX_HOUR = 47;

  private static final Set<String> BAND_HEADERS = Set.of("时段", "bands");
  private static final Set<String> PIN_HEADERS = Set.of("定点", "pins");
  private static final Set<String> EXTRA_MARKS = Set.of("加开", "extra", "+");
  private static final Pattern CLOCK = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");

  private TimetablePlanBook() {}

  /**
   * 一处问题。
   *
   * @param page 页码（从 1 起）；整本书的问题为 0
   * @param line 页内行号（从 1 起，含注释与空行）；整本书的问题为 0
   * @param reason 中文原因
   */
  public record Issue(int page, int line, String reason) {
    public Issue {
      Objects.requireNonNull(reason, "reason");
    }

    /** 带位置的完整描述，如 {@code 第 1 页第 3 行：时刻写法不对}。 */
    public String message() {
      return page <= 0 ? reason : "第 " + page + " 页第 " + line + " 行：" + reason;
    }
  }

  /**
   * 解析结果。有问题时 {@code pattern} 一律为 {@link ServicePattern#NONE}：半本计划书比没有计划书更容易误导编表。
   *
   * @param pattern 服务模式
   * @param issues 问题，按页码与行号
   */
  public record Parsed(ServicePattern pattern, List<Issue> issues) {
    public Parsed {
      Objects.requireNonNull(pattern, "pattern");
      issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /** 没有任何问题。 */
    public boolean ok() {
      return issues.isEmpty();
    }
  }

  private enum Section {
    NONE,
    BANDS,
    PINS,
    UNKNOWN
  }

  /** 组键的两部分：限定线路（可无）与组名。 */
  private record GroupKey(Optional<String> line, String group) {}

  /** 已收下的一条时段及其位置，查重叠时用来指出另一行。 */
  private record PlacedBand(Band band, int page, int line) {}

  /**
   * 解析存库的正文。
   *
   * @param body 正文，页间以 {@link #PAGE_BREAK} 分隔
   * @return 解析结果
   */
  public static Parsed parse(String body) {
    return parsePages(pages(body));
  }

  /**
   * 解析一本书。
   *
   * @param pages 各页的纯文本，页内用换行分行
   * @return 解析结果
   */
  public static Parsed parsePages(List<String> pages) {
    List<Band> bands = new ArrayList<>();
    List<Pin> pins = new ArrayList<>();
    List<Issue> issues = new ArrayList<>();
    Map<String, List<PlacedBand>> bandsByKey = new HashMap<>();
    Set<String> pinKeys = new HashSet<>();
    Section section = Section.NONE;
    List<String> source = pages == null ? List.of() : pages;
    for (int p = 0; p < source.size(); p++) {
      String[] lines = (source.get(p) == null ? "" : source.get(p)).split("\\R", -1);
      for (int l = 0; l < lines.length; l++) {
        int page = p + 1;
        int lineNo = l + 1;
        String text = stripComment(lines[l]);
        if (text.isEmpty()) {
          continue;
        }
        Optional<String> header = header(text);
        if (header.isPresent()) {
          section = sectionOf(header.get());
          if (section == Section.UNKNOWN) {
            issues.add(new Issue(page, lineNo, "未知段头 " + text + "：只认 [时段]（[bands]）与 [定点]（[pins]）"));
          }
          continue;
        }
        switch (section) {
          case NONE -> issues.add(new Issue(page, lineNo, "这一行不在任何段下：先写段头 [时段] 或 [定点]"));
          case BANDS -> parseBand(page, lineNo, text, issues)
              .ifPresent(band -> acceptBand(band, page, lineNo, bands, bandsByKey, issues));
          case PINS -> parsePin(page, lineNo, text, issues)
              .ifPresent(
                  pin -> {
                    if (pinKeys.add(pin.secondOfDay() + "@" + pin.routeCode())) {
                      pins.add(pin);
                    } else {
                      issues.add(
                          new Issue(
                              page,
                              lineNo,
                              "定点重复："
                                  + formatClock(pin.secondOfDay())
                                  + " "
                                  + pin.routeCode()
                                  + " 已经写过"));
                    }
                  });
          case UNKNOWN -> {
            // 未知段头已报过一次；段内各行不再逐行报错。
          }
        }
      }
    }
    if (issues.isEmpty() && bands.isEmpty() && pins.isEmpty()) {
      issues.add(new Issue(0, 0, "计划书是空的：至少写一条时段或一个定点"));
    }
    return issues.isEmpty()
        ? new Parsed(new ServicePattern(bands, pins), List.of())
        : new Parsed(ServicePattern.NONE, issues);
  }

  /**
   * 把存库正文拆成页。
   *
   * @param body 正文
   * @return 各页文本；正文为空时为一页空白
   */
  public static List<String> pages(String body) {
    return Arrays.asList((body == null ? "" : body).split(PAGE_BREAK, -1));
  }

  /**
   * 把各页拼成存库正文。
   *
   * @param pages 各页文本
   * @return 正文
   */
  public static String body(List<String> pages) {
    return String.join(PAGE_BREAK, pages == null ? List.of() : pages);
  }

  /**
   * 解析时刻 {@code HH:mm} 或 {@code HH:mm:ss}，小时 0–{@value #MAX_HOUR}。全角冒号视同半角。
   *
   * @param text 时刻文本
   * @return 相对当天零点的秒数；写法不对时为空
   */
  public static Optional<Integer> parseClock(String text) {
    if (text == null) {
      return Optional.empty();
    }
    Matcher matcher = CLOCK.matcher(text.trim().replace('：', ':'));
    if (!matcher.matches()) {
      return Optional.empty();
    }
    int hour = Integer.parseInt(matcher.group(1));
    int minute = Integer.parseInt(matcher.group(2));
    int second = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
    if (hour > MAX_HOUR || minute > 59 || second > 59) {
      return Optional.empty();
    }
    return Optional.of(hour * 3600 + minute * 60 + second);
  }

  /**
   * 时刻写回书里的样子：整分钟写 {@code HH:mm}，否则 {@code HH:mm:ss}；跨零点小时照写（{@code 24:30}）。
   *
   * @param secondOfDay 相对当天零点的秒数
   * @return 时刻文本
   */
  public static String formatClock(int secondOfDay) {
    int hour = secondOfDay / 3600;
    int minute = secondOfDay % 3600 / 60;
    int second = secondOfDay % 60;
    return second == 0
        ? String.format(Locale.ROOT, "%02d:%02d", hour, minute)
        : String.format(Locale.ROOT, "%02d:%02d:%02d", hour, minute, second);
  }

  private static String stripComment(String raw) {
    String text = raw == null ? "" : raw;
    int hash = text.indexOf('#');
    return (hash < 0 ? text : text.substring(0, hash)).trim();
  }

  private static Optional<String> header(String text) {
    boolean open = text.startsWith("[") || text.startsWith("【");
    boolean close = text.endsWith("]") || text.endsWith("】");
    return open && close && text.length() >= 2
        ? Optional.of(text.substring(1, text.length() - 1).trim().toLowerCase(Locale.ROOT))
        : Optional.empty();
  }

  private static Section sectionOf(String header) {
    if (BAND_HEADERS.contains(header)) {
      return Section.BANDS;
    }
    return PIN_HEADERS.contains(header) ? Section.PINS : Section.UNKNOWN;
  }

  private static Optional<Band> parseBand(int page, int lineNo, String text, List<Issue> issues) {
    String[] tokens = text.split("\\s+");
    if (tokens.length < 3) {
      issues.add(new Issue(page, lineNo, "时段行应写成「组键 起-止 秒」，例如 WS/WS_Full 06:00-07:00 300"));
      return Optional.empty();
    }
    Optional<GroupKey> key = parseGroupKey(tokens[0]);
    if (key.isEmpty()) {
      issues.add(new Issue(page, lineNo, "组键写法不对：" + tokens[0] + "（写成「组」或「线/组」）"));
      return Optional.empty();
    }
    String range = String.join("", Arrays.copyOfRange(tokens, 1, tokens.length - 1));
    String[] ends = range.split("[-~]", -1);
    if (ends.length != 2) {
      issues.add(new Issue(page, lineNo, "时段写法不对：" + range + "（写成 起-止，例如 06:00-07:00）"));
      return Optional.empty();
    }
    Optional<Integer> from = parseClock(ends[0]);
    Optional<Integer> to = parseClock(ends[1]);
    if (from.isEmpty() || to.isEmpty()) {
      issues.add(new Issue(page, lineNo, "时刻写法不对：" + range + clockHint()));
      return Optional.empty();
    }
    if (from.get() >= to.get()) {
      issues.add(new Issue(page, lineNo, "起点须早于终点：" + range + "（跨零点请把终点写成 24:30 这样的形式）"));
      return Optional.empty();
    }
    Optional<Integer> interval = parsePositive(tokens[tokens.length - 1]);
    if (interval.isEmpty()) {
      issues.add(new Issue(page, lineNo, "间隔应为正整数秒：" + tokens[tokens.length - 1]));
      return Optional.empty();
    }
    return Optional.of(
        new Band(key.get().line(), key.get().group(), from.get(), to.get(), interval.get()));
  }

  /** 组键：{@code 组} 或 {@code 线/组}。 */
  private static Optional<GroupKey> parseGroupKey(String token) {
    int slash = token.indexOf('/');
    if (slash < 0) {
      return Optional.of(new GroupKey(Optional.empty(), token));
    }
    String line = token.substring(0, slash);
    String group = token.substring(slash + 1);
    if (line.isEmpty() || group.isEmpty() || group.indexOf('/') >= 0) {
      return Optional.empty();
    }
    return Optional.of(new GroupKey(Optional.of(line), group));
  }

  private static void acceptBand(
      Band band,
      int page,
      int lineNo,
      List<Band> bands,
      Map<String, List<PlacedBand>> bandsByKey,
      List<Issue> issues) {
    List<PlacedBand> sameKey = bandsByKey.computeIfAbsent(band.key(), key -> new ArrayList<>());
    for (PlacedBand other : sameKey) {
      if (band.fromSecond() < other.band().toSecond()
          && other.band().fromSecond() < band.toSecond()) {
        issues.add(
            new Issue(
                page,
                lineNo,
                "组 "
                    + band.key()
                    + " 的时段 "
                    + range(band)
                    + " 与第 "
                    + other.page()
                    + " 页第 "
                    + other.line()
                    + " 行的 "
                    + range(other.band())
                    + " 重叠"));
        return;
      }
    }
    sameKey.add(new PlacedBand(band, page, lineNo));
    bands.add(band);
  }

  private static Optional<Pin> parsePin(int page, int lineNo, String text, List<Issue> issues) {
    String[] tokens = text.split("\\s+");
    if (tokens.length < 2 || tokens.length > 3) {
      issues.add(new Issue(page, lineNo, "定点行应写成「时刻 route [加开]」，例如 07:15 MT-3N_DPExp 加开"));
      return Optional.empty();
    }
    Optional<Integer> second = parseClock(tokens[0]);
    if (second.isEmpty()) {
      issues.add(new Issue(page, lineNo, "时刻写法不对：" + tokens[0] + clockHint()));
      return Optional.empty();
    }
    boolean extra = false;
    if (tokens.length == 3) {
      if (!EXTRA_MARKS.contains(tokens[2].toLowerCase(Locale.ROOT))) {
        issues.add(new Issue(page, lineNo, "route 之后只能写 加开（或 extra、+）：" + tokens[2]));
        return Optional.empty();
      }
      extra = true;
    }
    return Optional.of(new Pin(second.get(), tokens[1], extra));
  }

  private static Optional<Integer> parsePositive(String token) {
    try {
      int value = Integer.parseInt(token);
      return value > 0 ? Optional.of(value) : Optional.empty();
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  private static String range(Band band) {
    return formatClock(band.fromSecond()) + "-" + formatClock(band.toSecond());
  }

  private static String clockHint() {
    return "（写成 HH:mm 或 HH:mm:ss，小时 0–" + MAX_HOUR + "）";
  }
}
