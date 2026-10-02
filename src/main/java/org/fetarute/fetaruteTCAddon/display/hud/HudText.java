package org.fetarute.fetaruteTCAddon.display.hud;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;

/**
 * HUD 模板文本：占位符替换与 MiniMessage 渲染，BossBar、ActionBar 与车内显示屏共用。
 *
 * <p>先替换占位符、再按 MiniMessage 解析，所以占位符的值里可以带颜色标签（如换乘线路色块）。站名、线路名等来自主数据，
 * 公司成员就能改，会原样进入模板，因此解析只开放展示类标签（颜色、样式、渐变、按键名、精灵图等），不开放点击、悬停、插入、换行、
 * 选择器与计分板读取：后续站点对话框里的文字可以点，名称里夹带的点击事件不能变成钓鱼链接或命令。
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

  private static final MiniMessage MINI_MESSAGE =
      MiniMessage.builder()
          .tags(
              TagResolver.resolver(
                  StandardTags.color(),
                  StandardTags.decorations(),
                  StandardTags.gradient(),
                  StandardTags.rainbow(),
                  StandardTags.transition(),
                  StandardTags.pride(),
                  StandardTags.shadowColor(),
                  StandardTags.reset(),
                  StandardTags.keybind(),
                  StandardTags.translatable(),
                  StandardTags.translatableFallback(),
                  StandardTags.sprite()))
          .build();

  private static final Pattern HEX_COLOR = Pattern.compile("[0-9A-Fa-f]{6}");

  /** 解析结果缓存：同一列车上的乘客、同一乘客相邻两次刷新，多数行的文字完全相同；组件不可变，可以共享。按最近使用淘汰。 */
  private static final int PARSE_CACHE_SIZE = 1024;

  private static final Map<String, Component> PARSED =
      Collections.synchronizedMap(
          new LinkedHashMap<>(PARSE_CACHE_SIZE * 4 / 3, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Component> eldest) {
              return size() > PARSE_CACHE_SIZE;
            }
          });

  /** 原版颜色名的英式拼写，MiniMessage 也认。 */
  private static final Map<String, String> COLOR_ALIASES =
      Map.of("grey", "gray", "dark_grey", "dark_gray");

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
    Component cached = PARSED.get(resolved);
    if (cached != null) {
      return cached;
    }
    Component parsed;
    try {
      parsed = MINI_MESSAGE.deserialize(resolved);
    } catch (RuntimeException ex) {
      if (debugLogger != null) {
        debugLogger.accept("HUD 模板解析失败: " + ex.getMessage());
      }
      parsed = Component.text(resolved);
    }
    PARSED.put(resolved, parsed);
    return parsed;
  }

  /**
   * 转义文字里的 MiniMessage 标签，使其原样显示。插件自己拼进占位符的名称（如换乘线路名）用它。
   *
   * @param text 原文
   */
  public static String escape(String text) {
    return text == null ? "" : MINI_MESSAGE.escapeTags(text);
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
    if (HEX_COLOR.matcher(hex).matches()) {
      return "#" + hex.toUpperCase(Locale.ROOT);
    }
    String lower = value.toLowerCase(Locale.ROOT);
    String named = COLOR_ALIASES.getOrDefault(lower, lower);
    return NamedTextColor.NAMES.value(named) != null ? named : DEFAULT_COLOR_TAG;
  }
}
