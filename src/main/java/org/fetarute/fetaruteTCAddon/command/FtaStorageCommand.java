package org.fetarute.fetaruteTCAddon.command;

import org.bukkit.command.CommandSender;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.incendo.cloud.CommandManager;

/**
 * 存储与配置相关命令，如 /fta reload。
 */
public final class FtaStorageCommand {

    private final FetaruteTCAddon plugin;

    public FtaStorageCommand(FetaruteTCAddon plugin) {
        this.plugin = plugin;
    }

    public void register(CommandManager<CommandSender> manager) {
        manager.command(manager.commandBuilder("fta")
                .literal("reload")
                .permission("fetarute.reload")
                .handler(ctx -> plugin.reloadFromCommand(ctx.sender()))
        );
    }
}
