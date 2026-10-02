package org.fetarute.fetaruteTCAddon.dispatcher.route;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;

/**
 * 停靠点备注（{@link RouteStop#notes()}）里动作指令的读取规则。
 *
 * <p>运行时执行（{@code RouteStopActionResolver}）与只读展示（公开 API、HUD、停靠线路）读同一条指令时都走这里， 两边不会对同一行备注给出不同的解析结果。
 */
public final class RouteStopDirectives {

  private static final Pattern ACTION_PREFIX_PATTERN =
      Pattern.compile("^(CHANGE|DYNAMIC|ACTION|CRET|DSTY)\\b", Pattern.CASE_INSENSITIVE);

  private RouteStopDirectives() {}

  /**
   * 读取指定前缀的指令内容。
   *
   * <p>备注可有多行，取第一条前缀匹配（不区分大小写）且内容非空的指令；内容取前缀（及其后的冒号）之后、第一个空格之前的部分。
   *
   * @param stop 停靠点
   * @param prefix 指令前缀，如 CHANGE/DYNAMIC/DSTY
   * @return 去掉前缀后的内容
   */
  public static Optional<String> target(RouteStop stop, String prefix) {
    if (stop == null || prefix == null || prefix.isBlank() || stop.notes().isEmpty()) {
      return Optional.empty();
    }
    String raw = stop.notes().orElse("");
    if (raw.isBlank()) {
      return Optional.empty();
    }
    String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
    for (String line : normalized.split("\n", -1)) {
      if (line == null) {
        continue;
      }
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      if (!ACTION_PREFIX_PATTERN.matcher(trimmed).find()) {
        continue;
      }
      String normalizedAction = normalizeActionLine(trimmed);
      String actualPrefix = firstSegment(normalizedAction).trim();
      if (!actualPrefix.equalsIgnoreCase(prefix)) {
        continue;
      }
      String rest = normalizedAction.substring(actualPrefix.length()).trim();
      if (rest.startsWith(":")) {
        rest = rest.substring(1).trim();
      }
      if (rest.isBlank()) {
        continue;
      }
      int ws = rest.indexOf(' ');
      if (ws >= 0) {
        rest = rest.substring(0, ws).trim();
      }
      if (!rest.isBlank()) {
        return Optional.of(rest);
      }
    }
    return Optional.empty();
  }

  private static String firstSegment(String line) {
    if (line == null || line.isEmpty()) {
      return "";
    }
    int idx = 0;
    while (idx < line.length()) {
      char ch = line.charAt(idx);
      if (Character.isWhitespace(ch) || ch == ':') {
        break;
      }
      idx++;
    }
    return line.substring(0, idx);
  }

  private static String normalizeActionLine(String line) {
    String trimmed = line.trim();
    Matcher matcher = ACTION_PREFIX_PATTERN.matcher(trimmed);
    if (!matcher.find()) {
      return trimmed;
    }
    String prefix = matcher.group(1).toUpperCase(Locale.ROOT);
    String rest = trimmed.substring(prefix.length()).trim();
    if (rest.isEmpty()) {
      return prefix;
    }
    if (rest.startsWith(":")) {
      rest = rest.substring(1).trim();
    }
    if ("CRET".equals(prefix) || "DSTY".equals(prefix)) {
      return rest.isEmpty() ? prefix : prefix + " " + rest;
    }
    return prefix + ":" + rest;
  }
}
