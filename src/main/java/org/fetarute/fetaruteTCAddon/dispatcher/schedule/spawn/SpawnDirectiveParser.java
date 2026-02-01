package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;

/** 从 RouteStop 的 notes 中解析 CRET/DSTY 等指令的目标。 */
public final class SpawnDirectiveParser {

  private SpawnDirectiveParser() {}

  public static Optional<String> findDirectiveTarget(List<RouteStop> stops, String directive) {
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

  /**
   * 从 RouteStop 的 notes 中查找指定 directive（如 CRET/DSTY）的目标。
   *
   * <p>返回值可能是：
   *
   * <ul>
   *   <li>普通 nodeId：如 "SURC:D:OFL:1"
   *   <li>DYNAMIC 规范：如 "DYNAMIC:SURC:D:OFL" 或 "DYNAMIC:SURC:D:OFL:[1:3]"
   * </ul>
   *
   * <p>调用方需检查返回值是否以 "DYNAMIC:" 开头来判断是否需要动态选择。
   */
  public static Optional<String> findDirectiveTarget(RouteStop stop, String directive) {
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
      // 检查是否是 DYNAMIC 格式
      if (rest.toUpperCase(Locale.ROOT).startsWith("DYNAMIC:")) {
        // 返回完整的 DYNAMIC 规范（包含 "DYNAMIC:" 前缀）
        return Optional.of(rest);
      }
      // 普通格式：CRET <NodeId> 或 DSTY <NodeId>（NodeId 不包含空格）
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

  /**
   * 检查目标字符串是否是 DYNAMIC 规范。
   *
   * @param target findDirectiveTarget 返回的目标字符串
   * @return 如果是 DYNAMIC 规范返回 true
   */
  public static boolean isDynamicTarget(String target) {
    return target != null && target.toUpperCase(Locale.ROOT).startsWith("DYNAMIC:");
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
