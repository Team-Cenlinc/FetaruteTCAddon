package org.fetarute.fetaruteTCAddon.display.hud.bossbar;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.display.hud.HudState;

/**
 * HUD 模板解析器：支持状态分支、轮播与进度表达式。
 *
 * <p>BossBar/ActionBar 共用该解析结果，区别仅在渲染通道与进度条使用方式。
 */
public final class BossBarHudTemplate {

  private static final int DEFAULT_ROTATE_TICKS = 40;

  private final Map<HudState, List<TemplateLine>> linesByState;
  private final List<TemplateLine> fallbackLines;
  private final Optional<String> progressExpression;
  private final int rotateTicks;

  private BossBarHudTemplate(
      Map<HudState, List<TemplateLine>> linesByState,
      List<TemplateLine> fallbackLines,
      Optional<String> progressExpression,
      int rotateTicks) {
    this.linesByState = linesByState;
    this.fallbackLines = fallbackLines;
    this.progressExpression = progressExpression;
    this.rotateTicks = rotateTicks;
  }

  /** 按状态与 tick 解析当前展示行（支持轮播）。 */
  public Optional<String> resolveLine(HudState state, long tick) {
    List<TemplateLine> candidates = linesByState.get(state);
    if (candidates == null || candidates.isEmpty()) {
      candidates = fallbackLines;
    }
    if (candidates == null || candidates.isEmpty()) {
      return Optional.empty();
    }
    int size = candidates.size();
    if (size == 1) {
      return Optional.ofNullable(candidates.get(0).content());
    }
    long ticks = Math.max(1L, rotateTicks);
    int index = (int) ((tick / ticks) % size);
    return Optional.ofNullable(candidates.get(index).content());
  }

  /** 进度表达式（BossBar 专用）。 */
  public Optional<String> progressExpression() {
    return progressExpression;
  }

  /**
   * 解析模板文本。
   *
   * <p>支持：
   *
   * <ul>
   *   <li>{@code STATE[_n]}：状态分支与轮播顺序
   *   <li>{@code rotate_ticks}: 轮播间隔
   *   <li>{@code progress}: 进度表达式
   * </ul>
   */
  public static BossBarHudTemplate parse(String raw, Consumer<String> debugLogger) {
    if (raw == null) {
      return new BossBarHudTemplate(Map.of(), List.of(), Optional.empty(), DEFAULT_ROTATE_TICKS);
    }
    String[] lines = raw.split("\n", -1);
    Map<HudState, List<TemplateLine>> stateLines = new EnumMap<>(HudState.class);
    List<TemplateLine> fallback = new ArrayList<>();
    Optional<String> progress = Optional.empty();
    int rotateTicks = DEFAULT_ROTATE_TICKS;
    boolean hasDirective = false;

    for (String line : lines) {
      if (line == null) {
        continue;
      }
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      int separatorIndex = findSeparator(line);
      if (separatorIndex >= 0) {
        String key = line.substring(0, separatorIndex).trim();
        String value = line.substring(separatorIndex + 1).trim();
        if (!key.isEmpty() && tryParseDirective(stateLines, key, value)) {
          hasDirective = true;
          continue;
        }
        if ("progress".equalsIgnoreCase(key)) {
          if (!value.isBlank()) {
            progress = Optional.of(value);
            hasDirective = true;
          }
          continue;
        }
        if ("rotate_ticks".equalsIgnoreCase(key)) {
          if (!value.isBlank()) {
            rotateTicks = parseRotateTicks(value, rotateTicks, debugLogger);
            hasDirective = true;
          }
          continue;
        }
      }
      fallback.add(new TemplateLine(1, line));
    }

    if (!hasDirective) {
      String content = raw.isBlank() ? "" : raw;
      List<TemplateLine> single =
          content.isEmpty() ? List.of() : List.of(new TemplateLine(1, content));
      return new BossBarHudTemplate(Map.of(), single, Optional.empty(), DEFAULT_ROTATE_TICKS);
    }

    Map<HudState, List<TemplateLine>> normalized = new EnumMap<>(HudState.class);
    for (Map.Entry<HudState, List<TemplateLine>> entry : stateLines.entrySet()) {
      List<TemplateLine> sorted = sortLines(entry.getValue());
      if (!sorted.isEmpty()) {
        normalized.put(entry.getKey(), sorted);
      }
    }
    List<TemplateLine> fallbackLines = sortLines(fallback);
    return new BossBarHudTemplate(normalized, fallbackLines, progress, rotateTicks);
  }

  private static boolean tryParseDirective(
      Map<HudState, List<TemplateLine>> stateLines, String key, String value) {
    if (key == null || key.isBlank()) {
      return false;
    }
    ParsedState parsed = parseStateKey(key);
    if (parsed == null) {
      return false;
    }
    if (value == null || value.isBlank()) {
      return true;
    }
    stateLines
        .computeIfAbsent(parsed.state(), ignored -> new ArrayList<>())
        .add(new TemplateLine(parsed.order(), value));
    return true;
  }

  private static ParsedState parseStateKey(String key) {
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
    return stateOpt.map(state -> new ParsedState(state, finalOrder)).orElse(null);
  }

  private static int parseRotateTicks(String raw, int fallback, Consumer<String> debugLogger) {
    try {
      int value = Integer.parseInt(raw.trim());
      if (value <= 0) {
        return fallback;
      }
      return value;
    } catch (NumberFormatException ex) {
      if (debugLogger != null) {
        debugLogger.accept("BossBar rotate_ticks invalid: " + raw);
      }
      return fallback;
    }
  }

  private static List<TemplateLine> sortLines(List<TemplateLine> lines) {
    if (lines == null || lines.isEmpty()) {
      return List.of();
    }
    List<TemplateLine> sorted = new ArrayList<>(lines);
    sorted.sort((a, b) -> Integer.compare(a.order(), b.order()));
    return List.copyOf(sorted);
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

  private static int findSeparator(String line) {
    int colon = line.indexOf(':');
    int equals = line.indexOf('=');
    if (colon < 0) {
      return equals;
    }
    if (equals < 0) {
      return colon;
    }
    return Math.min(colon, equals);
  }

  private record TemplateLine(int order, String content) {
    private TemplateLine {
      Objects.requireNonNull(content, "content");
    }
  }

  private record ParsedState(HudState state, int order) {
    private ParsedState {
      Objects.requireNonNull(state, "state");
    }
  }
}
