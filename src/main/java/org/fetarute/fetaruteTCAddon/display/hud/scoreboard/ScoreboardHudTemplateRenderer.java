package org.fetarute.fetaruteTCAddon.display.hud.scoreboard;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/** Scoreboard 模板渲染器：先替换 {@code {placeholder}}，再用 MiniMessage 解析为组件。 */
public final class ScoreboardHudTemplateRenderer {

  private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

  private ScoreboardHudTemplateRenderer() {}

  public static Component render(
      String template, Map<String, String> placeholders, Consumer<String> debugLogger) {
    Objects.requireNonNull(placeholders, "placeholders");
    if (template == null || template.isBlank()) {
      return Component.empty();
    }
    String resolved = applyPlaceholders(template, placeholders);
    try {
      return MINI_MESSAGE.deserialize(resolved);
    } catch (Exception ex) {
      if (debugLogger != null) {
        debugLogger.accept("Scoreboard 模板解析失败: " + ex.getMessage());
      }
      return Component.text(resolved);
    }
  }

  /** 渲染已完成占位符替换的文本。 */
  public static Component renderResolved(String resolved, Consumer<String> debugLogger) {
    if (resolved == null || resolved.isBlank()) {
      return Component.empty();
    }
    try {
      return MINI_MESSAGE.deserialize(resolved);
    } catch (Exception ex) {
      if (debugLogger != null) {
        debugLogger.accept("Scoreboard 模板解析失败: " + ex.getMessage());
      }
      return Component.text(resolved);
    }
  }

  /** 替换模板中的 {@code {key}} 占位符；未知 key 保持原样。 */
  public static String applyPlaceholders(String template, Map<String, String> placeholders) {
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(placeholders, "placeholders");
    int length = template.length();
    if (length == 0) {
      return template;
    }
    StringBuilder out = new StringBuilder(length + 32);
    int index = 0;
    while (index < length) {
      int open = template.indexOf('{', index);
      if (open < 0) {
        out.append(template, index, length);
        break;
      }
      int close = template.indexOf('}', open + 1);
      if (close < 0) {
        out.append(template, index, length);
        break;
      }
      out.append(template, index, open);
      String key = template.substring(open + 1, close);
      String value = placeholders.get(key);
      if (value == null) {
        out.append('{').append(key).append('}');
      } else {
        out.append(value);
      }
      index = close + 1;
    }
    return out.toString();
  }
}
