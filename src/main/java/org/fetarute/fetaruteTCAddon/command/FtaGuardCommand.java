package org.fetarute.fetaruteTCAddon.command;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.drive.DrivePermissions;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoard;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardEntries;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardHolder;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardSource;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardStations;
import org.fetarute.fetaruteTCAddon.drive.guard.GuardSession;
import org.fetarute.fetaruteTCAddon.drive.guard.GuardSessionManager;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSessionManager;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.permission.Permission;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * /fta guard 命令注册：坐在调度列车车尾驾驶室的玩家 {@code /fta guard on} 上岗当车掌，{@code off} 离岗，{@code seat}
 * 传送入座（终点站换端时送进要换到的那一端）， {@code status} 查看；{@code tasks [车站]} 打开车掌任务板领取一班车掌，{@code task
 * status|abandon|goto} 查看、放弃任务或前往接班；管理员 {@code stop <玩家>} 撤下车掌。
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
    SuggestionProvider<CommandSender> stationSuggestions =
        SuggestionProvider.blockingStrings(
            (ctx, input) ->
                CommandUx.suggestions(
                    TaskBoardStations.suggestions(TaskBoardSource.stations(plugin)),
                    input.lastRemainingToken()));
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
            .literal("seat")
            .permission(player)
            .handler(ctx -> handleSeat(ctx.sender())));
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
            .literal("tasks")
            .permission(player)
            .optional("station", StringParser.quotedStringParser(), stationSuggestions)
            .handler(
                ctx -> handleTasks(ctx.sender(), ctx.optional("station").map(String.class::cast))));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("guard")
            .literal("task")
            .permission(player)
            .optional(
                "action",
                StringParser.stringParser(),
                SuggestionProvider.suggestingStrings("status", "abandon", "goto"))
            .handler(
                ctx ->
                    handleTask(
                        ctx.sender(),
                        ctx.optional("action").map(String.class::cast).orElse("status"))));
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
      for (String sub : List.of("on", "off", "seat", "status", "tasks", "task")) {
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

  private void handleSeat(CommandSender sender) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(locale().component("drive.guard.command.player-only"));
      return;
    }
    Optional<GuardSessionManager> guards = guards();
    if (guards.isEmpty()) {
      player.sendMessage(locale().component("drive.guard.command.not-on-duty"));
      return;
    }
    guards.get().seat(player);
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

  /** 打开车掌任务板：给了站码打开那一站，否则打开玩家附近的车站。 */
  private void handleTasks(CommandSender sender, Optional<String> stationArg) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(locale().component("drive.guard.command.player-only"));
      return;
    }
    Optional<GuardSessionManager> guards = guards();
    DriveSessionManager drive = plugin.getDriveSessionManager();
    if (guards.isEmpty() || drive == null || !guards.get().available()) {
      player.sendMessage(locale().component("drive.guard.task.claim.disabled"));
      return;
    }
    if (guards.get().isOnDuty(player.getUniqueId())) {
      player.sendMessage(locale().component("drive.guard.board.on-duty"));
      return;
    }
    Optional<TaskBoardSource.Station> station;
    if (stationArg.filter(arg -> !arg.isBlank()).isPresent()) {
      String code = stationArg.get().trim();
      TaskBoardStations.Lookup lookup =
          TaskBoardStations.find(TaskBoardSource.stations(plugin), code);
      if (lookup.outcome() == TaskBoardStations.Outcome.AMBIGUOUS) {
        player.sendMessage(
            locale()
                .component(
                    "drive.task.board.station-ambiguous",
                    Map.of("station", code, "candidates", String.join(", ", lookup.candidates()))));
        return;
      }
      if (lookup.outcome() == TaskBoardStations.Outcome.NOT_FOUND) {
        player.sendMessage(
            locale().component("drive.task.board.station-not-found", Map.of("station", code)));
        sendStationChoices(player);
        return;
      }
      station = lookup.station();
    } else {
      station = TaskBoardSource.nearestStation(plugin, player.getLocation());
      if (station.isEmpty()) {
        player.sendMessage(locale().component("drive.guard.board.no-station"));
        sendStationChoices(player);
        return;
      }
    }
    Instant now = Instant.now();
    List<TaskBoardEntries.Entry> entries =
        TaskBoardSource.withTrips(
            plugin,
            TaskBoardEntries.board(
                TaskBoardSource.departures(
                    plugin,
                    station.get(),
                    now,
                    drive.config().driver().recovery().taskWindowMinutes()),
                guards.get().tasks().claimants(),
                now,
                TaskBoard.ENTRY_SLOTS));
    TaskBoard.open(
        player,
        locale(),
        new TaskBoardHolder(
            player.getUniqueId(),
            station.get().operatorCode(),
            station.get().stationCode(),
            station.get().name(),
            entries,
            TaskBoard.Kind.GUARD),
        false,
        Optional.empty());
  }

  /** 列出全部车站，点站码即打开那一站的车掌任务板。 */
  private void sendStationChoices(Player player) {
    List<TaskBoardStations.Choice> choices =
        TaskBoardStations.choices(TaskBoardSource.stations(plugin));
    if (choices.isEmpty()) {
      return;
    }
    Component line = locale().component("drive.task.board.station-list");
    for (TaskBoardStations.Choice choice : choices) {
      Map<String, String> values = Map.of("code", choice.argument(), "station", choice.name());
      line =
          line.append(Component.space())
              .append(
                  locale()
                      .component("drive.task.board.station-choice", values)
                      .clickEvent(
                          ClickEvent.runCommand(
                              "/fta guard tasks " + CommandUx.suggestion(choice.argument(), false)))
                      .hoverEvent(
                          HoverEvent.showText(
                              locale()
                                  .component("drive.guard.board.station-choice-hover", values))));
    }
    player.sendMessage(line);
  }

  /** 车掌任务：查看、放弃、前往接班。 */
  private void handleTask(CommandSender sender, String action) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(locale().component("drive.guard.command.player-only"));
      return;
    }
    Optional<GuardSessionManager> guards = guards();
    if (guards.isEmpty()) {
      player.sendMessage(locale().component("drive.guard.task.none"));
      return;
    }
    switch (action.toLowerCase(Locale.ROOT)) {
      case "abandon" -> player.sendMessage(locale().component(guards.get().abandonTask(player)));
      case "goto" -> player.sendMessage(locale().component(guards.get().gotoTask(player)));
      case "status" -> guards.get().sendTaskStatus(player);
      default -> player.sendMessage(locale().component("drive.guard.task.invalid-action"));
    }
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
