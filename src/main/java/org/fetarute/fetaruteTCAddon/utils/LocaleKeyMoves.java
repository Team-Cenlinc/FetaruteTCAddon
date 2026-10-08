package org.fetarute.fetaruteTCAddon.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 改过名的语言键：服务器语言文件里旧键上的文案搬到新键，旧键删掉。
 *
 * <p>补全缺失键只按新键补默认文案，不搬的话服务器改过的旧键文案会被丢在原处、不再生效。新键已有值时以新键为准；旧键文案与它不同时旧键原样留在文件里并报出来， 由管理员手动合并，不悄悄删掉。
 * 搬过去的文案若仍是改名前的内置旧文案，随后按 {@code lang-superseded} 换成新的内置文案（旧文案登记在新键下）。
 */
final class LocaleKeyMoves {

  /** 旧键 → 新键；以 {@code .} 结尾的是整段，段下各键按相对路径搬。 */
  static final Map<String, String> MOVED = moved();

  private LocaleKeyMoves() {}

  private static Map<String, String> moved() {
    Map<String, String> moved = new LinkedHashMap<>();
    moved.put("drive.driver.breaker.", "drive.driver.protection.");
    moved.put("drive.task.claim.breaker-open", "drive.task.claim.protection-active");
    moved.put("drive.command.start.breaker-open", "drive.command.start.protection-active");
    moved.put("drive.command.help.entry-breaker", "drive.command.help.entry-congestion");
    moved.put("drive.command.breaker.", "drive.command.congestion.");
    moved.put(
        "drive.license.exam.in-progress-dispatch", "drive.license.exam.in-progress-road-test");
    moved.put("drive.license.exam.brief.dispatch.", "drive.license.exam.brief.road-test.");
    moved.put("drive.license.info.level-open-dispatch", "drive.license.info.level-open-road-test");
    moved.put("drive.license.exam.stop.window.", "drive.license.exam.stop.outcome.");
    moved.put("drive.hud.driver.confirm-signal", "drive.hud.driver.acknowledge-signal");
    moved.put("drive.driver.signal.confirmed", "drive.driver.signal.acknowledged");
    moved.put("drive.tutorial.tips.signal-confirm.", "drive.tutorial.tips.signal-acknowledge.");
    return Collections.unmodifiableMap(moved);
  }

  /**
   * 搬键的结果。
   *
   * @param moved 从旧键搬来文案的新键
   * @param kept 新键已有不同文案、因而原样保留的旧键
   */
  record Result(List<String> moved, List<String> kept) {
    boolean changed() {
      return !moved.isEmpty();
    }
  }

  /** 把旧键搬到新键。 */
  static Result apply(ConfigurationSection config) {
    List<String> moved = new ArrayList<>();
    List<String> kept = new ArrayList<>();
    for (Map.Entry<String, String> entry : MOVED.entrySet()) {
      String from = entry.getKey();
      String to = entry.getValue();
      if (!from.endsWith(".")) {
        move(config, from, to, moved, kept);
        continue;
      }
      String section = from.substring(0, from.length() - 1);
      ConfigurationSection old = config.getConfigurationSection(section);
      if (old == null) {
        continue;
      }
      for (String key : List.copyOf(old.getKeys(true))) {
        if (!old.isConfigurationSection(key)) {
          move(config, from + key, to + key, moved, kept);
        }
      }
      pruneEmpty(config, section);
    }
    return new Result(List.copyOf(moved), List.copyOf(kept));
  }

  private static void move(
      ConfigurationSection config, String from, String to, List<String> moved, List<String> kept) {
    if (!config.contains(from) || config.isConfigurationSection(from)) {
      return;
    }
    if (!config.contains(to)) {
      config.set(to, config.get(from));
      moved.add(to);
    } else if (!Objects.equals(config.get(to), config.get(from))) {
      kept.add(from);
      return;
    }
    config.set(from, null);
    int dot = from.lastIndexOf('.');
    if (dot > 0) {
      pruneEmpty(config, from.substring(0, dot));
    }
  }

  /** 删掉搬空了的段及因此变空的上级段。 */
  private static void pruneEmpty(ConfigurationSection config, String path) {
    String current = path;
    while (!current.isEmpty()) {
      ConfigurationSection section = config.getConfigurationSection(current);
      if (section == null || !section.getKeys(false).isEmpty()) {
        return;
      }
      config.set(current, null);
      int dot = current.lastIndexOf('.');
      current = dot > 0 ? current.substring(0, dot) : "";
    }
  }
}
