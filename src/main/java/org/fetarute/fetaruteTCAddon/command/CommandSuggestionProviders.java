package org.fetarute.fetaruteTCAddon.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * 命令补全工具：提供可复用的 SuggestionProvider 构造方法。
 *
 * <p>命令层的补全逻辑应尽量保持“纯函数”：给定输入前缀，返回占位符 + 候选值，避免在这里引入数据库副作用。
 */
public final class CommandSuggestionProviders {

  private CommandSuggestionProviders() {}

  /** 返回一个只输出占位符的补全提供者（用于 flag value 或自由输入参数）。 */
  public static <C> SuggestionProvider<C> placeholder(String placeholder) {
    return SuggestionProvider.suggestingStrings(placeholder);
  }

  /**
   * 枚举值补全：同时返回占位符与真实枚举候选（支持前缀过滤）。
   *
   * <p>用于 flag value 补全（如 --status / --service / --pattern），避免玩家不知道要填什么字符串。
   *
   * @param enumClass 枚举类型
   * @param placeholder 空输入时输出的占位符（如 {@code <status>}）
   */
  public static <C, E extends Enum<E>> SuggestionProvider<C> enumValues(
      Class<E> enumClass, String placeholder) {
    return SuggestionProvider.blockingStrings(
        (ctx, input) -> {
          String prefix = normalizeUpperPrefix(input);
          List<String> suggestions = new ArrayList<>();
          if (prefix.isBlank()) {
            suggestions.add(placeholder);
          }
          E[] values = enumClass.getEnumConstants();
          if (values == null) {
            return suggestions;
          }
          for (E value : values) {
            String name = value.name();
            if (prefix.isBlank() || name.startsWith(prefix)) {
              suggestions.add(name);
            }
          }
          return suggestions;
        });
  }

  /** 将 Cloud 的输入 token 规范化为大写前缀，用于枚举类前缀过滤。 */
  private static String normalizeUpperPrefix(CommandInput input) {
    if (input == null) {
      return "";
    }
    return input.lastRemainingToken().trim().toUpperCase(Locale.ROOT);
  }
}
