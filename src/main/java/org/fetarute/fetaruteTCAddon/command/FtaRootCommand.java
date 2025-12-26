package org.fetarute.fetaruteTCAddon.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.suggestion.Suggestion;

/**
 * Bukkit 原生命令入口，负责将 /fta 转发到 Cloud 命令系统并提供基础 Tab 补全。
 *
 * <p>当 Cloud 未就绪时回退为帮助输出，保证控制台与玩家侧的最小可用性。
 */
public final class FtaRootCommand implements CommandExecutor, TabCompleter {

  private final FetaruteTCAddon plugin;
  private final CommandManager<CommandSender> commandManager;
  private final FtaInfoCommand infoCommand;

  public FtaRootCommand(
      FetaruteTCAddon plugin,
      CommandManager<CommandSender> commandManager,
      FtaInfoCommand infoCommand) {
    this.plugin = plugin;
    this.commandManager = commandManager;
    this.infoCommand = infoCommand;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (commandManager == null) {
      if (args.length == 0 && infoCommand != null) {
        infoCommand.sendHelp(sender);
        return true;
      }
      if (infoCommand != null) {
        infoCommand.sendHelp(sender);
      }
      return true;
    }
    String input = args.length == 0 ? label : (label + " " + String.join(" ", args));
    commandManager
        .commandExecutor()
        .executeCommand(sender, input)
        .exceptionally(
            ex -> {
              if (plugin != null) {
                plugin.getLogger().warning("命令执行失败: " + ex.getMessage());
              }
              return null;
            });
    return true;
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String alias, String[] args) {
    if (commandManager != null) {
      StringBuilder input = new StringBuilder(alias);
      if (args.length > 0) {
        input.append(" ").append(String.join(" ", args));
      }
      if (args.length == 0 || (args.length > 0 && args[args.length - 1].isEmpty())) {
        if (input.length() == 0 || input.charAt(input.length() - 1) != ' ') {
          input.append(' ');
        }
      }
      try {
        return commandManager
            .suggestionFactory()
            .suggest(sender, input.toString())
            .join()
            .list()
            .stream()
            .map(Suggestion::suggestion)
            .distinct()
            .toList();
      } catch (RuntimeException ignored) {
        return List.of();
      }
    }

    List<String> options = new ArrayList<>();
    options.add("info");
    options.add("help");
    options.add("company");
    options.add("operator");
    options.add("line");
    options.add("route");
    if (sender.hasPermission("fetarute.graph") || sender.hasPermission("fetarute.admin")) {
      options.add("graph");
    }
    if (sender.hasPermission("fetarute.reload")) {
      options.add("reload");
    }

    if (args.length == 0) {
      return options;
    }
    if (args.length == 1) {
      String prefix = args[0].toLowerCase(Locale.ROOT);
      options.removeIf(opt -> !opt.startsWith(prefix));
      return options;
    }
    return List.of();
  }
}
