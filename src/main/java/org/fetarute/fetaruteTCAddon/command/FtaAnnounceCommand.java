package org.fetarute.fetaruteTCAddon.command;

import java.util.Optional;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.display.pids.PidsService;
import org.incendo.cloud.CommandManager;

/**
 * 乘客命令：{@code /fta announce [on|off]} 查看或开关自己的站台广播。
 *
 * <p>所有玩家默认可用（{@code fetarute.announce}）；开关记在玩家数据里，重进服务器仍有效。
 */
public final class FtaAnnounceCommand {

  private final FetaruteTCAddon plugin;

  public FtaAnnounceCommand(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  public void register(CommandManager<CommandSender> manager) {
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("announce")
            .permission("fetarute.announce")
            .senderType(Player.class)
            .handler(
                ctx ->
                    withService(
                        (Player) ctx.sender(),
                        (player, service) -> {
                          if (!service.settings().broadcast().enabled()) {
                            player.sendMessage(locale("pids.announce.server-off"));
                            return;
                          }
                          player.sendMessage(
                              locale(
                                  service.announcer().muted(player)
                                      ? "pids.announce.status-off"
                                      : "pids.announce.status-on"));
                        })));
    register(manager, "on", false, "pids.announce.unmuted");
    register(manager, "off", true, "pids.announce.muted");
  }

  private void register(
      CommandManager<CommandSender> manager, String literal, boolean muted, String messageKey) {
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("announce")
            .literal(literal)
            .permission("fetarute.announce")
            .senderType(Player.class)
            .handler(
                ctx ->
                    withService(
                        (Player) ctx.sender(),
                        (player, service) -> {
                          service.announcer().setMuted(player, muted);
                          player.sendMessage(locale(messageKey));
                          if (!service.settings().broadcast().enabled()) {
                            player.sendMessage(locale("pids.announce.server-off"));
                          }
                        })));
  }

  private void withService(Player player, BiConsumer<Player, PidsService> action) {
    Optional<PidsService> service = plugin.getPidsService();
    if (service.isEmpty()) {
      player.sendMessage(locale("pids.announce.unavailable"));
      return;
    }
    action.accept(player, service.get());
  }

  private Component locale(String key) {
    return plugin.getLocaleManager().component(key);
  }
}
