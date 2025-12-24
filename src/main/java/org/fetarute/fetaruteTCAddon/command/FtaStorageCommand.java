package org.fetarute.fetaruteTCAddon.command;

import org.bukkit.command.CommandSender;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.incendo.cloud.CommandManager;

/**
 * 存储与配置相关命令入口：/fta reload。
 *
 * <p>用于触发配置与语言文件的重载。
 */
public final class FtaStorageCommand {

  private final FetaruteTCAddon plugin;

  public FtaStorageCommand(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  public void register(CommandManager<CommandSender> manager) {
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("reload")
            .permission("fetarute.reload")
            .handler(
                ctx -> {
                  // 主动触发配置与语言重载，供运维快速刷新生效。
                  plugin.reloadFromCommand(ctx.sender());
                }));
  }
}
