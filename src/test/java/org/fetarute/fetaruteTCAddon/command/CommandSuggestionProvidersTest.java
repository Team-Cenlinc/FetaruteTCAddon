package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.fetarute.fetaruteTCAddon.company.model.LineServiceType;
import org.fetarute.fetaruteTCAddon.company.model.RoutePatternType;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.suggestion.SuggestionProvider;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

final class CommandSuggestionProvidersTest {

  @Test
  void shouldSuggestEnumValuesWithPlaceholder() {
    SuggestionProvider<CommandSender> provider =
        CommandSuggestionProviders.enumValues(LineServiceType.class, "<service>");

    CommandSender sender = Mockito.mock(CommandSender.class);
    CommandManager<CommandSender> manager = new TestCommandManager();
    CommandContext<CommandSender> ctx = new CommandContext<>(sender, manager);

    List<String> suggestions = toStrings(provider, ctx, CommandInput.of(""));
    assertEquals(
        List.of("<service>", "METRO", "REGIONAL", "COMMUTER", "LRT", "EXPRESS"), suggestions);
  }

  @Test
  void shouldFilterEnumValuesByPrefix() {
    SuggestionProvider<CommandSender> provider =
        CommandSuggestionProviders.enumValues(RoutePatternType.class, "<pattern>");

    CommandSender sender = Mockito.mock(CommandSender.class);
    CommandManager<CommandSender> manager = new TestCommandManager();
    CommandContext<CommandSender> ctx = new CommandContext<>(sender, manager);

    List<String> suggestions = toStrings(provider, ctx, CommandInput.of("EX"));
    assertEquals(List.of("EXPRESS"), suggestions);
  }

  /**
   * 用于测试的最小 CommandManager 实现。
   *
   * <p>CommandContext 构造要求非空的 CommandManager；这里使用 Cloud 提供的空注册处理器与简单执行协调器，避免 Mockito 触发泛型 unchecked
   * 警告。
   */
  private static final class TestCommandManager extends CommandManager<CommandSender> {

    private TestCommandManager() {
      super(
          ExecutionCoordinator.simpleCoordinator(),
          CommandRegistrationHandler.nullCommandRegistrationHandler());
    }

    @Override
    public boolean hasPermission(CommandSender sender, String permission) {
      return true;
    }
  }

  private static List<String> toStrings(
      SuggestionProvider<CommandSender> provider,
      CommandContext<CommandSender> ctx,
      CommandInput input) {
    Iterable<? extends Suggestion> raw = provider.suggestionsFuture(ctx, input).join();
    List<String> result = new ArrayList<>();
    for (Suggestion suggestion : raw) {
      result.add(suggestion.suggestion());
    }
    return result;
  }
}
