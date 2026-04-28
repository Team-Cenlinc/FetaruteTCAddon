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
