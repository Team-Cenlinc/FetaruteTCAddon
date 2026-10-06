package org.fetarute.fetaruteTCAddon.drive;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code drive.yml} 里改过名的键：旧键还在、新键没写时把旧键改成新键，值、注释与缩进原样保留。
 *
 * <p>必须在补全新键之前做，否则补全会按模板加上新键，与旧键并存、旧键上改过的值被忽略。按行处理、不经 YAML 往返，免得重写整个文件；只认顶层段下一层的键。
 */
public final class DriveConfigKeyRenames {

  /** 顶层段 → 旧键 → 新键。 */
  static final Map<String, Map<String, String>> RENAMED =
      Map.of(
          "driver",
          Map.of(
              "breaker-held-trains", "protection-held-trains",
              "breaker-held-seconds", "protection-held-seconds",
              "breaker-cooldown-minutes", "protection-cooldown-minutes"),
          "sounds",
          Map.of("signal-confirmed", "signal-acknowledged"));

  /** 键行：缩进、可选的引号、键名、同一引号，冒号后可带值或注释。 */
  private static final Pattern KEY =
      Pattern.compile("^(\\s*)([\"']?)([A-Za-z0-9_-]+)\\2:(\\s.*)?$");

  private DriveConfigKeyRenames() {}

  /**
   * 迁移文件内容。
   *
   * @param lines 文件的各行
   * @return 迁移后的各行；没有可迁移的内容时与输入相等
   */
  public static List<String> migrate(List<String> lines) {
    List<String> out = new ArrayList<>(lines);
    for (Map.Entry<String, Map<String, String>> section : RENAMED.entrySet()) {
      renameIn(out, section.getKey(), section.getValue());
    }
    return out;
  }

  private static void renameIn(List<String> lines, String section, Map<String, String> renamed) {
    int start = -1;
    for (int i = 0; i < lines.size(); i++) {
      Matcher key = KEY.matcher(lines.get(i));
      if (key.matches() && key.group(1).isEmpty() && key.group(3).equals(section)) {
        start = i;
        break;
      }
    }
    if (start < 0) {
      return;
    }
    int end = start + 1;
    while (end < lines.size() && !endsBlock(lines.get(end))) {
      end++;
    }
    // 段内第一层键的缩进：第一个键行的缩进。
    int childIndent = -1;
    Set<String> existing = new HashSet<>();
    for (int i = start + 1; i < end; i++) {
      Matcher key = KEY.matcher(lines.get(i));
      if (!key.matches()) {
        continue;
      }
      if (childIndent < 0) {
        childIndent = key.group(1).length();
      }
      if (key.group(1).length() == childIndent) {
        existing.add(key.group(3));
      }
    }
    if (childIndent < 0) {
      return;
    }
    for (int i = start + 1; i < end; i++) {
      Matcher key = KEY.matcher(lines.get(i));
      if (!key.matches() || key.group(1).length() != childIndent) {
        continue;
      }
      String target = renamed.get(key.group(3));
      if (target == null || existing.contains(target)) {
        continue;
      }
      lines.set(
          i,
          key.group(1)
              + key.group(2)
              + target
              + key.group(2)
              + ":"
              + (key.group(4) == null ? "" : key.group(4)));
      existing.add(target);
    }
  }

  /** 这一行是否已离开顶层段：非空、非注释且没有缩进。 */
  private static boolean endsBlock(String line) {
    String trimmed = line.trim();
    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
      return false;
    }
    return !Character.isWhitespace(line.charAt(0));
  }
}
