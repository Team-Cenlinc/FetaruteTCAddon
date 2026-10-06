package org.fetarute.fetaruteTCAddon.drive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import org.bukkit.configuration.ConfigurationSection;
import org.fetarute.fetaruteTCAddon.utils.YamlLines;

/**
 * {@code drive.yml} 里改过名的键：旧键还在、新键没写时把旧键改成新键，值、注释与缩进原样保留。
 *
 * <p>必须在补全新键之前做，否则补全会按模板加上新键，与旧键并存、旧键上改过的值被忽略。按行处理（见 {@link YamlLines}），只认顶层段下一层的键； 按行改不成的（如流式写法），由
 * {@link #unmigrated} 与 {@link #carryOver} 在读配置时沿用旧键的值。
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

  private DriveConfigKeyRenames() {}

  /**
   * 迁移的结果。
   *
   * @param lines 迁移后的各行；没有可迁移的内容时与输入相等
   * @param renamed 改了名的键（{@code 段.旧键 → 新键}）
   */
  public record Result(List<String> lines, List<String> renamed) {
    public Result {
      lines = List.copyOf(lines);
      renamed = List.copyOf(renamed);
    }
  }

  /** 迁移文件内容，只返回迁移后的各行。 */
  public static List<String> migrate(List<String> lines) {
    return rename(lines).lines();
  }

  /** 迁移文件内容。 */
  public static Result rename(List<String> lines) {
    List<String> out = new ArrayList<>(lines);
    List<String> renamed = new ArrayList<>();
    for (Map.Entry<String, Map<String, String>> section : RENAMED.entrySet()) {
      renameIn(out, section.getKey(), section.getValue(), renamed);
    }
    return new Result(out, renamed);
  }

  /**
   * 按行没能改名的旧键：旧键在、新键不在。要在补全新键之前查，补全后新键总在。
   *
   * @return 旧键完整路径 → 新键完整路径
   */
  public static Map<String, String> unmigrated(ConfigurationSection root) {
    Map<String, String> pending = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, String>> section : RENAMED.entrySet()) {
      for (Map.Entry<String, String> key : section.getValue().entrySet()) {
        String from = section.getKey() + "." + key.getKey();
        String to = section.getKey() + "." + key.getValue();
        if (root.contains(from) && !root.contains(to)) {
          pending.put(from, to);
        }
      }
    }
    return pending;
  }

  /** 把 {@code before} 里旧键的值写到 {@code target} 的新键上（只改内存，不写文件），盖过补全时按模板加上的默认值。 */
  public static void carryOver(
      ConfigurationSection before, Map<String, String> pending, ConfigurationSection target) {
    for (Map.Entry<String, String> entry : pending.entrySet()) {
      ConfigurationSection section = before.getConfigurationSection(entry.getKey());
      if (section == null) {
        target.set(entry.getValue(), before.get(entry.getKey()));
        continue;
      }
      target.set(entry.getValue(), null);
      for (String key : section.getKeys(true)) {
        if (!section.isConfigurationSection(key)) {
          target.set(entry.getValue() + "." + key, section.get(key));
        }
      }
    }
  }

  private static void renameIn(
      List<String> lines, String section, Map<String, String> names, List<String> renamed) {
    int start = -1;
    for (int i = 0; i < lines.size(); i++) {
      Matcher key = YamlLines.KEY.matcher(lines.get(i));
      if (key.matches() && key.group(1).isEmpty() && key.group(3).equals(section)) {
        start = i;
        break;
      }
    }
    if (start < 0) {
      return;
    }
    int end = YamlLines.blockEnd(lines, start, 0);
    YamlLines.Children children = YamlLines.children(lines, start + 1, end);
    if (children.indent() < 0) {
      return;
    }
    List<String> existing = new ArrayList<>(children.keys());
    for (int i = start + 1; i < end; i++) {
      Matcher key = YamlLines.KEY.matcher(lines.get(i));
      if (!key.matches() || key.group(1).length() != children.indent()) {
        continue;
      }
      String target = names.get(key.group(3));
      if (target == null || existing.contains(target)) {
        continue;
      }
      lines.set(i, YamlLines.renamed(key, target));
      existing.add(target);
      renamed.add(section + "." + key.group(3) + " → " + target);
    }
  }
}
