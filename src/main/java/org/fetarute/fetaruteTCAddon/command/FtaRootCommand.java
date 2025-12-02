package org.fetarute.fetaruteTCAddon.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;

/**
 * Bukkit 原生命令入口，兼容控制台输出与简单 Tab 补全。
 */
public final class FtaRootCommand implements CommandExecutor, TabCompleter {

    private final FetaruteTCAddon plugin;
    private final FtaInfoCommand infoCommand;

    public FtaRootCommand(FetaruteTCAddon plugin, FtaInfoCommand infoCommand) {
        this.plugin = plugin;
        this.infoCommand = infoCommand;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            infoCommand.sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> infoCommand.sendInfo(sender);
            case "help" -> infoCommand.sendHelp(sender);
            case "reload" -> {
                if (!sender.hasPermission("fetarute.reload")) {
                    sender.sendMessage(plugin.getLocaleManager().component("error.no-permission"));
                    return true;
                }
                plugin.reloadFromCommand(sender);
            }
            default -> infoCommand.sendHelp(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.add("info");
            options.add("help");
            if (sender.hasPermission("fetarute.reload")) {
                options.add("reload");
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            options.removeIf(opt -> !opt.startsWith(prefix));
            return options;
        }
        return List.of();
    }
}
