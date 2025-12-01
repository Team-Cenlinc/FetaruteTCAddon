package org.fetarute.fetaruteTCAddon.command;

import org.incendo.cloud.CommandManager;
import org.bukkit.command.CommandSender;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;

import java.util.Optional;

/**
 * 注册 /fta info 与 /fta help 子命令，输出版本与帮助信息。
 */
public final class FtaInfoCommand {

    private final FetaruteTCAddon plugin;

    public FtaInfoCommand(FetaruteTCAddon plugin) {
        this.plugin = plugin;
    }

    public void register(CommandManager<CommandSender> manager) {
        manager.command(manager.commandBuilder("fta")
                .handler(ctx -> sendHelp(ctx.sender()))
        );
        manager.command(manager.commandBuilder("fta")
                .literal("info")
                .handler(ctx -> sendInfo(ctx.sender()))
        );
        manager.command(manager.commandBuilder("fta")
                .literal("help")
                .handler(ctx -> sendHelp(ctx.sender()))
        );
    }

    public void sendInfo(CommandSender sender) {
        String version = Optional.of(plugin.getPluginMeta().getVersion()).orElse("dev");
        String build = Optional.ofNullable(plugin.getClass().getPackage().getImplementationVersion()).orElse(version);
        sender.sendMessage("§bFetaruteTCAddon 版本: §f" + version);
        sender.sendMessage("§b构建: §f" + build);
    }

    public void sendHelp(CommandSender sender) {
        sender.sendMessage("§b[FetaruteTCAddon] 可用子命令：");
        sender.sendMessage("§7/fta info §f- 显示插件版本与构建信息");
        sender.sendMessage("§7/fta help §f- 显示此帮助");
    }
}
