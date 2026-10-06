package org.fetarute.fetaruteTCAddon.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * 命令输出交互组件工具。
 *
 * <p>命令层统一在这里生成短动作按钮与命令参数引用，避免 help、列表、诊断输出各自使用不同的点击/悬浮口径。
 */
public final class CommandUx {

  private CommandUx() {}

  /** 构造一个短动作按钮，例如 {@code [详情]} 或 {@code [清除]}。 */
  public static Component action(String label, ClickEvent clickEvent, Component hoverText) {
    return Component.text(label, NamedTextColor.DARK_AQUA)
        .clickEvent(clickEvent)
        .hoverEvent(HoverEvent.showText(hoverText));
  }

  /** 构造一个低风险执行按钮。 */
  public static Component runAction(String label, String command, String hoverText) {
    return action(label, ClickEvent.runCommand(command), Component.text(hoverText));
  }

  /** 构造一个只填充输入框的按钮。 */
  public static Component suggestAction(String label, String command, String hoverText) {
    return action(label, ClickEvent.suggestCommand(command), Component.text(hoverText));
  }

  /** 以空格分隔多个动作按钮。 */
  public static Component actions(Component... actions) {
    Component out = Component.empty();
    if (actions == null) {
      return out;
    }
    boolean first = true;
    for (Component action : actions) {
      if (action == null) {
        continue;
      }
      if (!first) {
        out = out.append(Component.space());
      }
      out = out.append(action);
      first = false;
    }
    return out;
  }

  /**
   * 引用一个命令参数。
   *
   * <p>始终返回双引号包裹的参数，并转义反斜杠和双引号，便于 nodeId、组名等包含冒号或空格的值安全填入建议命令。
   */
  public static String quoteCommandArgument(String raw) {
    String text = raw == null ? "" : raw.trim();
    String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"");
    return "\"" + escaped + "\"";
  }

  /**
   * 生成可直接放入命令建议的参数。
   *
   * <p>普通 code、train name 等安全 token 不加引号，避免传给 {@code StringParser} 时把引号当成内容；包含空格或特殊字符时再使用双引号。
   */
  public static String commandArgument(String raw) {
    return quoteCommandArgument(raw);
  }

  /**
   * 客户端按 Brigadier 规则解析参数时，这个值不加引号也合法：只含字母、数字与 {@code _ - . +}。冒号、逗号、{@code * ?}、中文与空格不加引号时，
   * 客户端把命令标红、其后的参数也不再补全；服务端仍能照常解析执行，所以只影响补全候选要不要加引号，不要据此拒绝不加引号的输入。
   */
  public static boolean unquotedSafe(String raw) {
    if (raw == null || raw.isEmpty()) {
      return false;
    }
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      boolean ok =
          (c >= '0' && c <= '9')
              || (c >= 'A' && c <= 'Z')
              || (c >= 'a' && c <= 'z')
              || c == '_'
              || c == '-'
              || c == '.'
              || c == '+';
      if (!ok) {
        return false;
      }
    }
    return true;
  }

  /**
   * 补全候选：不加引号也合法的值原样给出；否则，或玩家已经起了引号时，加双引号。
   *
   * @param quoted 当前参数的输入以双引号开头
   */
  public static String suggestion(String raw, boolean quoted) {
    String text = raw == null ? "" : raw.trim();
    return !quoted && unquotedSafe(text) ? text : quoteCommandArgument(text);
  }

  /** 补全时当前参数已输入的文字是否以双引号开头。 */
  public static boolean startsQuoted(String token) {
    return token != null && token.trim().startsWith("\"");
  }

  /** 补全时当前参数已输入的文字去掉开头（可能还没闭合）的引号与首尾空白，转小写，用来和候选值比较。 */
  public static String suggestionPrefix(String token) {
    String text = token == null ? "" : token.trim();
    if (text.startsWith("\"") || text.startsWith("'")) {
      text = text.substring(1);
    }
    if (text.endsWith("\"") || text.endsWith("'")) {
      text = text.substring(0, text.length() - 1);
    }
    return text.trim().toLowerCase(java.util.Locale.ROOT);
  }

  /**
   * 以输入开头（不分大小写、不计开头的引号）的候选值，按 {@link #suggestion(String, boolean)} 需要时加引号。
   *
   * @param token 当前参数已输入的文字
   */
  public static java.util.List<String> suggestions(java.util.List<String> values, String token) {
    boolean quoted = startsQuoted(token);
    String prefix = suggestionPrefix(token);
    return values.stream()
        .filter(
            value -> value != null && value.toLowerCase(java.util.Locale.ROOT).startsWith(prefix))
        .map(value -> suggestion(value, quoted))
        .toList();
  }

  /** 去掉由 {@link #quoteCommandArgument(String)} 生成的外层引号。 */
  public static String unquoteCommandArgument(String raw) {
    if (raw == null) {
      return "";
    }
    String text = raw.trim();
    if (text.length() < 2 || text.charAt(0) != '"' || text.charAt(text.length() - 1) != '"') {
      return text;
    }
    String body = text.substring(1, text.length() - 1);
    StringBuilder out = new StringBuilder(body.length());
    boolean escaping = false;
    for (int i = 0; i < body.length(); i++) {
      char ch = body.charAt(i);
      if (escaping) {
        out.append(ch);
        escaping = false;
      } else if (ch == '\\') {
        escaping = true;
      } else {
        out.append(ch);
      }
    }
    if (escaping) {
      out.append('\\');
    }
    return out.toString().trim();
  }
}
