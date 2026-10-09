package org.fetarute.fetaruteTCAddon.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.drive.DrivePermissions;
import org.fetarute.fetaruteTCAddon.drive.guard.GuardSession;
import org.fetarute.fetaruteTCAddon.drive.guard.GuardSessionManager;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSessionManager;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.permission.Permission;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * /fta guard 命令注册：坐在调度列车车尾驾驶室的玩家 {@code /fta guard on} 上岗当车掌，{@code off} 离岗，{@code status} 查看；管理员
 * {@code stop <玩家>} 撤下车掌。
 */
public final class FtaGuardCommand {

  private final FetaruteTCAddon plugin;

  public FtaGuardCommand(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  /** 注册 {@code /fta guard} 子命令与补全。 */
  public void register(CommandManager<CommandSender> manager) {
    SuggestionProvider<CommandSender> guardSuggestions =
        SuggestionProvider.blockingStrings(
            (ctx, input) -> {
              List<String> names = new ArrayList<>();
              guards()
                  .ifPresent(
                      guards -> {
                        for (GuardSession session : guards.sessions()) {
                          names.add(session.playerName());
                        }
                      });
              return names;
            });
    Permission player = Permission.of(DrivePermissions.GUARD);
    Permission admin = Permission.of(DrivePermissions.ADMIN);
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("guard")
            .permission(Permission.anyOf(List.of(player, admin)))
            .handler(ctx -> sendHelp(ctx.sender())));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("guard")
            .literal("on")
            .permission(player)
            .handler(ctx -> handleOn(ctx.sender())));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("guard")
            .literal("off")
            .permission(player)
            .handler(ctx -> handleOff(ctx.sender())));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("guard")
            .literal("status")
            .permission(player)
            .handler(ctx -> handleStatus(ctx.sender())));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("guard")
            .literal("stop")
            .required("player", StringParser.stringParser(), guardSuggestions)
            .permission(admin)
            .handler(ctx -> handleStop(ctx.sender(), ctx.get("player"))));
  }

  private Optional<GuardSessionManager> guards() {
    DriveSessionManager drive = plugin.getDriveSessionManager();
    return drive == null ? Optional.empty() : drive.guards();
  }

  private LocaleManager locale() {
    return plugin.getLocaleManager();
  }

  private void sendHelp(CommandSender sender) {
    sender.sendMessage(locale().component("drive.guard.command.help.header"));
    if (sender.hasPermission(DrivePermissions.GUARD)) {
      for (String sub : List.of("on", "off", "status")) {
        sender.sendMessage(locale().component("drive.guard.command.help." + sub));
      }
    }
    if (sender.hasPermission(DrivePermissions.ADMIN)) {
      sender.sendMessage(locale().component("drive.guard.command.help.stop"));
    }
  }

  private void handleOn(CommandSender sender) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(locale().component("drive.guard.command.player-only"));
      return;
    }
    Optional<GuardSessionManager> guards = guards();
    if (guards.isEmpty()) {
      sender.sendMessage(locale().component("drive.guard.command.start.unavailable"));
      return;
    }
    GuardSessionManager.StartOutcome outcome = guards.get().start(player);
    player.sendMessage(
        locale()
            .component(
                "drive.guard.command.start."
                    + outcome.name().toLowerCase(Locale.ROOT).replace('_', '-')));
  }

  private void handleOff(CommandSender sender) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(locale().component("drive.guard.command.player-only"));
      return;
    }
    Optional<GuardSessionManager> guards = guards();
    if (guards.isEmpty() || !guards.get().isOnDuty(player.getUniqueId())) {
      player.sendMessage(locale().component("drive.guard.command.not-on-duty"));
      return;
    }
    guards.get().stop(player.getUniqueId(), GuardSession.EndReason.COMMAND);
  }

  private void handleStatus(CommandSender sender) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(locale().component("drive.guard.command.player-only"));
      return;
    }
    Optional<GuardSession> session =
        guards().flatMap(guards -> guards.sessionOf(player.getUniqueId()));
    if (session.isEmpty()) {
      player.sendMessage(locale().component("drive.guard.command.not-on-duty"));
      return;
    }
    player.sendMessage(
        locale()
            .component(
                "drive.guard.command.status",
                Map.of(
                    "train",
                    session.get().link().properties() == null
                        ? session.get().link().trainName()
                        : session.get().link().properties().getTrainName(),
                    "stops",
                    String.valueOf(session.get().link().completedStops()),
                    "timeouts",
                    String.valueOf(session.get().link().timeoutStops()))));
  }

  private void handleStop(CommandSender sender, String name) {
    Player target = Bukkit.getPlayerExact(name);
    Optional<GuardSessionManager> guards = guards();
    if (target == null || guards.isEmpty() || !guards.get().isOnDuty(target.getUniqueId())) {
      sender.sendMessage(
          locale().component("drive.guard.command.stop.not-found", Map.of("player", name)));
      return;
    }
    guards.get().stop(target.getUniqueId(), GuardSession.EndReason.ADMIN);
    sender.sendMessage(
        locale().component("drive.guard.command.stop.done", Map.of("player", target.getName())));
  }
}
