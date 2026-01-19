package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;

/** 从 RouteStop 的 notes 中解析 CRET/DSTY 等指令的目标。 */
final class SpawnDirectiveParser {

  private SpawnDirectiveParser() {}

  static Optional<String> findDirectiveTarget(List<RouteStop> stops, String directive) {
    if (stops == null || stops.isEmpty() || directive == null || directive.isBlank()) {
      return Optional.empty();
    }
    for (RouteStop stop : stops) {
      Optional<String> target = findDirectiveTarget(stop, directive);
      if (target.isPresent()) {
        return target;
      }
    }
    return Optional.empty();
  }

  static Optional<String> findDirectiveTarget(RouteStop stop, String directive) {
    if (stop == null || stop.notes().isEmpty() || directive == null || directive.isBlank()) {
      return Optional.empty();
    }
    String raw = stop.notes().orElse("");
    if (raw == null || raw.isBlank()) {
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
      String prefix = firstToken(trimmed).toUpperCase(Locale.ROOT);
      if (!prefix.equalsIgnoreCase(directive)) {
        continue;
      }
      String rest = trimmed.substring(prefix.length()).trim();
      if (rest.startsWith(":")) {
        rest = rest.substring(1).trim();
      }
      if (rest.isBlank()) {
        continue;
      }
      // 指令格式：CRET <NodeId> 或 DSTY <NodeId>（NodeId 不包含空格）
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

  private static String firstToken(String line) {
    if (line == null) {
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
}
