package org.fetarute.fetaruteTCAddon.display.hud;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * HUD 模板文本：占位符替换与 MiniMessage 渲染，BossBar、ActionBar 与车内显示屏共用。
 *
 * <p>先替换占位符、再按 MiniMessage 解析，所以占位符的值里可以带颜色标签（如换乘线路色块）。
 *
 * <ul>
 *   <li>{@code {key}}：普通占位符；模板里写了但没有提供的 key 原样保留，便于排查模板。
 *   <li>{@code {?key}}：条件占位符；有值时与 {@code {key}} 一样替换，值缺失（未提供、空白或 {@code -}）时整行不显示。
 *       用于只在部分站、部分时刻才有内容的行，例如换乘、晚点、直通换线。
 * </ul>
 */
public final class HudText {

  /** 缺失值的统一写法：上下文解析器把所有缺失数据标准化为它。 */
  public static final String MISSING = "-";

  private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
  private static final String DEFAULT_COLOR_TAG = "white";

  private HudText() {}

  /**
   * 替换模板中的占位符。条件占位符按普通占位符替换；是否整行隐藏由 {@link #shown} 判断。
   *
   * @param template 模板文本
   * @param placeholders 占位符键值
   * @return 替换后的文本；未提供的普通占位符原样保留
   */
  public static String apply(String template, Map<String, String> placeholders) {
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(placeholders, "placeholders");
    int length = template.length();
    StringBuilder out = new StringBuilder(length + 32);
    int index = 0;
    while (index < length) {
      int open = template.indexOf('{', index);
      int close = open < 0 ? -1 : template.indexOf('}', open + 1);
      if (close < 0) {
        out.append(template, index, length);
        break;
      }
      out.append(template, index, open);
      String token = template.substring(open + 1, close);
      boolean conditional = token.startsWith("?");
      String key = conditional ? token.substring(1) : token;
      String value = placeholders.get(key);
      if (value != null) {
        out.append(value);
      } else if (!conditional) {
        out.append('{').append(token).append('}');
      }
      index = close + 1;
    }
    return out.toString();
  }

  /**
   * 模板行是否显示：行内每个条件占位符都有值时才显示；没有条件占位符的行总是显示。
   *
   * @param template 模板文本
   * @param placeholders 占位符键值
   */
  public static boolean shown(String template, Map<String, String> placeholders) {
    if (template == null) {
      return false;
    }
    int index = template.indexOf("{?");
    while (index >= 0) {
      int close = template.indexOf('}', index + 2);
      if (close < 0) {
        return true;
      }
      if (!present(placeholders.get(template.substring(index + 2, close)))) {
        return false;
      }
      index = template.indexOf("{?", close + 1);
    }
    return true;
  }

  /** 值是否算“有内容”：非空、非空白且不是 {@link #MISSING}。 */
  public static boolean present(String value) {
    return value != null && !value.isBlank() && !MISSING.equals(value.trim());
  }

  /**
   * 替换占位符并解析为组件。
   *
   * @param template 模板文本
   * @param placeholders 占位符键值
   * @param debugLogger 解析失败时的调试输出（可为 null）
   */
  public static Component render(
      String template, Map<String, String> placeholders, Consumer<String> debugLogger) {
    Objects.requireNonNull(placeholders, "placeholders");
    if (template == null || template.isBlank()) {
      return Component.empty();
    }
    return parse(apply(template, placeholders), debugLogger);
  }

  /**
   * 解析已替换完占位符的文本；MiniMessage 解析失败时退回纯文本。
   *
   * @param resolved 已替换占位符的文本
   * @param debugLogger 解析失败时的调试输出（可为 null）
   */
  public static Component parse(String resolved, Consumer<String> debugLogger) {
    if (resolved == null || resolved.isBlank()) {
      return Component.empty();
    }
    try {
      return MINI_MESSAGE.deserialize(resolved);
    } catch (RuntimeException ex) {
      if (debugLogger != null) {
        debugLogger.accept("HUD 模板解析失败: " + ex.getMessage());
      }
      return Component.text(resolved);
    }
  }

  /**
   * 把线路色转成可直接写进 MiniMessage 的颜色标签名：六位十六进制（带不带 {@code #} 均可）转为 {@code #RRGGBB}， 原版颜色名原样小写；其余（含缺失）为
   * {@code white}。
   *
   * <p>颜色是管理员手填的，写成 {@code F6A000} 这类不带 {@code #} 的值时，直接拼成 {@code <F6A000>} 会被 MiniMessage
   * 当成普通文字显示出来。
   *
   * @param raw 线路或运营商的颜色字段
   */
  public static String colorTag(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_COLOR_TAG;
    }
    String value = raw.trim();
    String hex = value.startsWith("#") ? value.substring(1) : value;
    if (hex.matches("[0-9A-Fa-f]{6}")) {
      return "#" + hex.toUpperCase(Locale.ROOT);
    }
    String named = value.toLowerCase(Locale.ROOT);
    return NamedTextColor.NAMES.value(named) != null ? named : DEFAULT_COLOR_TAG;
  }
}
