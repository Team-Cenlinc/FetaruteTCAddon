package org.fetarute.fetaruteTCAddon.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.display.DisplayService;
import org.incendo.cloud.CommandManager;

/**
 * 乘客命令：{@code /fta trip} 打开后续站点对话框（与乘车时按副手交换键相同）。
 *
 * <p>所有玩家默认可用（{@code fetarute.trip}）。
 */
public final class FtaTripCommand {

  private final FetaruteTCAddon plugin;

  public FtaTripCommand(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  public void register(CommandManager<CommandSender> manager) {
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("trip")
            .permission("fetarute.trip")
            .senderType(Player.class)
            .handler(ctx -> open((Player) ctx.sender())));
  }

  private void open(Player player) {
    plugin
        .getDisplayService()
        .flatMap(DisplayService::tripDialog)
        .ifPresentOrElse(
            dialog -> dialog.open(player, false),
            () ->
                player.sendMessage(
                    plugin.getLocaleManager().component("display.trip.unavailable")));
  }
}
