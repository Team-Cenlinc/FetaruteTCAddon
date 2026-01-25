package org.fetarute.fetaruteTCAddon.display.hud.scoreboard;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.display.hud.HudState;

/**
 * Scoreboard HUD 模板：支持静态页与 list page 生成器。
 *
 * <p>模板本身只描述布局，不负责占位符替换。
 */
public final class ScoreboardHudTemplate {

  public static final int MAX_LINES = 15;
  private static final int DEFAULT_LINES = 10;
  private static final int DEFAULT_PAGE_DURATION_TICKS = 60;
  private static final int DEFAULT_WINDOW_DURATION_TICKS = 120;

  private final Map<HudState, List<Page>> pagesByState;
  private final List<Page> fallbackPages;
  private final int lineCount;
  private final int pageDurationTicks;
  private final Optional<String> title;

  private ScoreboardHudTemplate(
      Map<HudState, List<Page>> pagesByState,
      List<Page> fallbackPages,
      int lineCount,
      int pageDurationTicks,
      Optional<String> title) {
    this.pagesByState = pagesByState;
    this.fallbackPages = fallbackPages;
    this.lineCount = lineCount;
    this.pageDurationTicks = pageDurationTicks;
    this.title = title == null ? Optional.empty() : title;
  }

  /** 行数上限（Scoreboard 最大 15 行）。 */
  public int lineCount() {
    return lineCount;
  }

  /** 页面轮播间隔（ticks）。 */
  public int pageDurationTicks() {
    return pageDurationTicks;
  }

  /** 模板标题（MiniMessage）。 */
  public Optional<String> title() {
    return title;
  }

  /** 解析当前 HUD 状态的页面（支持分页轮播）。 */
  public Optional<Page> resolvePage(HudState state, long tick) {
    List<Page> pages = pagesByState.get(state);
    if (pages == null || pages.isEmpty()) {
      pages = pagesByState.get(HudState.DEFAULT);
    }
    if (pages == null || pages.isEmpty()) {
      pages = fallbackPages;
    }
    if (pages == null || pages.isEmpty()) {
      return Optional.empty();
    }
    if (pages.size() == 1) {
      return Optional.of(pages.get(0));
    }
    long ticks = Math.max(1L, pageDurationTicks);
    int index = (int) ((tick / ticks) % pages.size());
    return Optional.of(pages.get(index));
  }

  /** 解析模板文本（YAML）。 */
  public static ScoreboardHudTemplate parse(String raw, Consumer<String> debugLogger) {
    if (raw == null) {
      return emptyTemplate();
    }
    YamlConfiguration config = new YamlConfiguration();
    try {
      config.loadFromString(raw);
    } catch (InvalidConfigurationException ex) {
      if (debugLogger != null) {
        debugLogger.accept("Scoreboard 模板解析失败: " + ex.getMessage());
      }
      return emptyTemplate();
    }

    int lineCount = parseLineCount(config.getInt("lines", 0));
    int pageDuration =
        parsePositive(
            config.getInt("page_duration_ticks", config.getInt("pageDurationTicks", 0)),
            DEFAULT_PAGE_DURATION_TICKS,
            "page_duration_ticks",
            debugLogger);
    Optional<String> title =
        Optional.ofNullable(config.getString("title")).map(String::trim).filter(s -> !s.isBlank());

    Map<HudState, List<Page>> pagesByState = new EnumMap<>(HudState.class);
    List<Page> allPages = new ArrayList<>();
    ConfigurationSection pagesSection = config.getConfigurationSection("pages");
    if (pagesSection != null) {
      for (String key : pagesSection.getKeys(false)) {
        if (key == null || key.isBlank()) {
          continue;
        }
        Optional<ParsedState> parsedKeyOpt = parseStateKey(key);
        if (parsedKeyOpt.isEmpty()) {
          if (debugLogger != null) {
            debugLogger.accept("Scoreboard 模板页面 key 无效: " + key);
          }
          continue;
        }
        ConfigurationSection pageSection = pagesSection.getConfigurationSection(key);
        if (pageSection == null) {
          List<String> inlineLines = readLines(pagesSection.getList(key), debugLogger);
          if (inlineLines.isEmpty()) {
            if (debugLogger != null) {
              debugLogger.accept("Scoreboard 模板页面格式无效: " + key);
            }
            continue;
          }
          Page page = new StaticPage(parsedKeyOpt.get().order(), Optional.empty(), inlineLines);
          pagesByState
              .computeIfAbsent(parsedKeyOpt.get().state(), ignored -> new ArrayList<>())
              .add(page);
          allPages.add(page);
          continue;
        }
        Page page = parsePage(parsedKeyOpt.get(), pageSection, debugLogger);
        if (page == null) {
          continue;
        }
        pagesByState
            .computeIfAbsent(parsedKeyOpt.get().state(), ignored -> new ArrayList<>())
            .add(page);
        allPages.add(page);
      }
    }

    if (pagesByState.isEmpty()) {
      return emptyTemplate();
    }

    List<Page> fallbackPages = resolveFallbackPages(pagesByState, allPages);
    Map<HudState, List<Page>> normalized = new EnumMap<>(HudState.class);
    for (Map.Entry<HudState, List<Page>> entry : pagesByState.entrySet()) {
      List<Page> sorted = sortPages(entry.getValue());
      if (!sorted.isEmpty()) {
        normalized.put(entry.getKey(), sorted);
      }
    }

    int inferredLineCount = lineCount > 0 ? lineCount : inferLineCount(allPages);
    int safeLineCount = clampLineCount(inferredLineCount);
    return new ScoreboardHudTemplate(normalized, fallbackPages, safeLineCount, pageDuration, title);
  }

  private static Page parsePage(
      ParsedState parsedKey, ConfigurationSection pageSection, Consumer<String> debugLogger) {
    String kindRaw = pageSection.getString("kind", "static");
    String kind = kindRaw == null ? "static" : kindRaw.trim().toLowerCase(Locale.ROOT);
    Optional<String> title =
        Optional.ofNullable(pageSection.getString("title"))
            .map(String::trim)
            .filter(s -> !s.isBlank());
    int order = parsedKey.order();

    if ("list_page".equals(kind) || "list".equals(kind)) {
      return parseListPage(order, title, pageSection, debugLogger);
    }
    List<String> lines = readLines(pageSection.getList("lines"), debugLogger);
    if (lines.isEmpty()) {
      if (debugLogger != null) {
        debugLogger.accept("Scoreboard 模板静态页缺少 lines: " + parsedKey.state().name());
      }
      return null;
    }
    return new StaticPage(order, title, lines);
  }

  private static ListPage parseListPage(
      int order,
      Optional<String> title,
      ConfigurationSection pageSection,
      Consumer<String> debugLogger) {
    String sourceRaw = pageSection.getString("source", "");
    Optional<ListSource> sourceOpt = ListSource.parse(sourceRaw);
    if (sourceOpt.isEmpty()) {
      if (debugLogger != null) {
        debugLogger.accept("Scoreboard list_page source 无效: " + sourceRaw);
      }
      return null;
    }
    List<String> rowLines = readLines(pageSection.getList("row"), debugLogger);
    if (rowLines.isEmpty()) {
      String row = pageSection.getString("row", "");
      if (row != null && !row.isBlank()) {
        rowLines = List.of(row);
      }
    }
    if (rowLines.isEmpty()) {
      if (debugLogger != null) {
        debugLogger.accept("Scoreboard list_page 缺少 row");
      }
      return null;
    }
    int limit = parsePositive(pageSection.getInt("limit", 0), 3, "list_page.limit", debugLogger);
    List<String> header = readLines(pageSection.getList("header"), debugLogger);
    List<String> footer = readLines(pageSection.getList("footer"), debugLogger);
    Optional<String> empty =
        Optional.ofNullable(pageSection.getString("empty"))
            .map(String::trim)
            .filter(s -> !s.isBlank());
    WindowConfig window =
        parseWindowConfig(pageSection.getConfigurationSection("window"), limit, debugLogger);
    return new ListPage(
        order, title, sourceOpt.get(), limit, header, rowLines, footer, empty, window);
  }

  private static int parseLineCount(int configured) {
    return configured > 0 ? configured : 0;
  }

  private static int parsePositive(
      int configured, int fallback, String key, Consumer<String> debugLogger) {
    if (configured > 0) {
      return configured;
    }
    if (configured != 0 && debugLogger != null) {
      debugLogger.accept("Scoreboard 模板参数无效: " + key + "=" + configured);
    }
    return fallback;
  }

  private static int parseNonNegative(
      int configured, int fallback, String key, Consumer<String> debugLogger) {
    if (configured >= 0) {
      return configured;
    }
    if (debugLogger != null) {
      debugLogger.accept("Scoreboard 模板参数无效: " + key + "=" + configured);
    }
    return fallback;
  }

  private static WindowConfig parseWindowConfig(
      ConfigurationSection section, int limit, Consumer<String> debugLogger) {
    int size = limit;
    int step = limit;
    int fixed = 0;
    WindowMode mode = WindowMode.CHUNK;
    int periodTicks = DEFAULT_WINDOW_DURATION_TICKS;
    boolean resetOnStop = true;

    if (section != null) {
      fixed = parseNonNegative(section.getInt("fixed", fixed), fixed, "window.fixed", debugLogger);
      size = parsePositive(section.getInt("size", size), size, "window.size", debugLogger);
      String rawMode = section.getString("mode", "chunk");
      mode = WindowMode.parse(rawMode).orElse(WindowMode.CHUNK);
      step = section.getInt("step", 0);
      if (step <= 0) {
        step = mode == WindowMode.SLIDE ? 1 : size;
      }
      periodTicks =
          parsePositive(
              section.getInt("periodTicks", periodTicks),
              periodTicks,
              "window.periodTicks",
              debugLogger);
      resetOnStop = section.getBoolean("resetOnStop", true);
    }
    if (fixed > limit) {
      if (debugLogger != null) {
        debugLogger.accept("Scoreboard window.fixed 超过 limit，将截断至 limit: " + fixed);
      }
      fixed = limit;
    }
    return new WindowConfig(size, step, fixed, mode, periodTicks, resetOnStop);
  }

  private static List<String> readLines(List<?> raw, Consumer<String> debugLogger) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    List<String> lines = new ArrayList<>();
    for (Object item : raw) {
      if (item == null) {
        lines.add("");
        continue;
      }
      if (item instanceof String text) {
        lines.add(text);
        continue;
      }
      if (debugLogger != null) {
        debugLogger.accept("Scoreboard 模板行无法解析: " + item);
      }
      lines.add(String.valueOf(item));
    }
    return List.copyOf(lines);
  }

  private static List<Page> resolveFallbackPages(
      Map<HudState, List<Page>> pagesByState, List<Page> allPages) {
    List<Page> defaults = pagesByState.get(HudState.DEFAULT);
    if (defaults != null && !defaults.isEmpty()) {
      return sortPages(defaults);
    }
    List<Page> inTrip = pagesByState.get(HudState.IN_TRIP);
    if (inTrip != null && !inTrip.isEmpty()) {
      return sortPages(inTrip);
    }
    return sortPages(allPages);
  }

  private static List<Page> sortPages(List<Page> pages) {
    if (pages == null || pages.isEmpty()) {
      return List.of();
    }
    List<Page> sorted = new ArrayList<>(pages);
    sorted.sort((a, b) -> Integer.compare(a.order(), b.order()));
    return List.copyOf(sorted);
  }

  private static int inferLineCount(List<Page> pages) {
    if (pages == null || pages.isEmpty()) {
      return DEFAULT_LINES;
    }
    int maxLines = 0;
    for (Page page : pages) {
      if (page == null) {
        continue;
      }
      int count = 0;
      if (page instanceof StaticPage staticPage) {
        count = staticPage.lines().size();
      } else if (page instanceof ListPage listPage) {
        int rowLines = listPage.rowLines().isEmpty() ? 1 : listPage.rowLines().size();
        count =
            listPage.header().size()
                + listPage.window().fixed() * rowLines
                + listPage.window().size() * rowLines
                + listPage.footer().size();
      }
      if (count > maxLines) {
        maxLines = count;
      }
    }
    return maxLines > 0 ? maxLines : DEFAULT_LINES;
  }

  private static int clampLineCount(int lineCount) {
    int safe = lineCount <= 0 ? DEFAULT_LINES : lineCount;
    return Math.min(safe, MAX_LINES);
  }

  private static Optional<ParsedState> parseStateKey(String key) {
    String upper = key.toUpperCase(Locale.ROOT);
    int lastUnderscore = upper.lastIndexOf('_');
    String base = upper;
    int order = 1;
    if (lastUnderscore > 0 && lastUnderscore < upper.length() - 1) {
      String suffix = upper.substring(lastUnderscore + 1);
      if (isDigits(suffix)) {
        base = upper.substring(0, lastUnderscore);
        try {
          order = Integer.parseInt(suffix);
        } catch (NumberFormatException ex) {
          order = 1;
        }
      }
    }
    Optional<HudState> stateOpt = HudState.parse(base);
    int finalOrder = Math.max(1, order);
    return stateOpt.map(state -> new ParsedState(state, finalOrder));
  }

  private static boolean isDigits(String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      if (!Character.isDigit(value.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static ScoreboardHudTemplate emptyTemplate() {
    return new ScoreboardHudTemplate(
        Map.of(), List.of(), DEFAULT_LINES, DEFAULT_PAGE_DURATION_TICKS, Optional.empty());
  }

  /** 页面类型。 */
  public sealed interface Page permits StaticPage, ListPage {
    int order();

    Optional<String> title();
  }

  /** 静态页面。 */
  public record StaticPage(int order, Optional<String> title, List<String> lines) implements Page {
    public StaticPage {
      lines = lines == null ? List.of() : List.copyOf(lines);
      title = title == null ? Optional.empty() : title;
    }
  }

  /** 列表页（生成器）。 */
  public record ListPage(
      int order,
      Optional<String> title,
      ListSource source,
      int limit,
      List<String> header,
      List<String> rowLines,
      List<String> footer,
      Optional<String> empty,
      WindowConfig window)
      implements Page {
    public ListPage {
      Objects.requireNonNull(source, "source");
      header = header == null ? List.of() : List.copyOf(header);
      rowLines = rowLines == null ? List.of() : List.copyOf(rowLines);
      footer = footer == null ? List.of() : List.copyOf(footer);
      empty = empty == null ? Optional.empty() : empty;
      title = title == null ? Optional.empty() : title;
      if (limit <= 0) {
        throw new IllegalArgumentException("limit 必须为正数");
      }
      if (rowLines.isEmpty()) {
        throw new IllegalArgumentException("rowLines 不能为空");
      }
      if (window == null) {
        throw new IllegalArgumentException("window 不能为空");
      }
    }
  }

  /** 列表数据源。 */
  public enum ListSource {
    NEXT_STOPS;

    public static Optional<ListSource> parse(String raw) {
      if (raw == null || raw.isBlank()) {
        return Optional.empty();
      }
      String normalized = raw.trim().toUpperCase(Locale.ROOT);
      if ("NEXT_STOPS".equals(normalized) || "UPCOMING_STOPS".equals(normalized)) {
        return Optional.of(NEXT_STOPS);
      }
      try {
        return Optional.of(ListSource.valueOf(normalized));
      } catch (IllegalArgumentException ex) {
        return Optional.empty();
      }
    }
  }

  /** 窗口滚动配置。 */
  public record WindowConfig(
      int size, int step, int fixed, WindowMode mode, int periodTicks, boolean resetOnStop) {
    public WindowConfig {
      if (size <= 0) {
        throw new IllegalArgumentException("size 必须为正数");
      }
      if (step <= 0) {
        throw new IllegalArgumentException("step 必须为正数");
      }
      if (fixed < 0) {
        throw new IllegalArgumentException("fixed 不能为负数");
      }
      if (periodTicks <= 0) {
        throw new IllegalArgumentException("periodTicks 必须为正数");
      }
      Objects.requireNonNull(mode, "mode");
    }
  }

  /** 窗口滚动模式。 */
  public enum WindowMode {
    CHUNK,
    SLIDE;

    public static Optional<WindowMode> parse(String raw) {
      if (raw == null || raw.isBlank()) {
        return Optional.empty();
      }
      String normalized = raw.trim().toUpperCase(Locale.ROOT);
      try {
        return Optional.of(WindowMode.valueOf(normalized));
      } catch (IllegalArgumentException ex) {
        return Optional.empty();
      }
    }
  }

  private record ParsedState(HudState state, int order) {
    private ParsedState {
      Objects.requireNonNull(state, "state");
    }
  }
}
