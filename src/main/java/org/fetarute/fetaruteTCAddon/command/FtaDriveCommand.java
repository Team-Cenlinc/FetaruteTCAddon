package org.fetarute.fetaruteTCAddon.command;

import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartGroupStore;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.drive.DrivePermissions;
import org.fetarute.fetaruteTCAddon.drive.cab.CabFault;
import org.fetarute.fetaruteTCAddon.drive.cab.CabFaults;
import org.fetarute.fetaruteTCAddon.drive.cab.CabSystems;
import org.fetarute.fetaruteTCAddon.drive.driver.CongestionProtection;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveLeaderboardRow;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveTaskRecord;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTask;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoard;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardEntries;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardHolder;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardSource;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardStations;
import org.fetarute.fetaruteTCAddon.drive.dynamics.ReverserPosition;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSession;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSessionManager;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.permission.Permission;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * /fta drive 命令注册。
 *
 * <p>玩家坐在列车座位上执行 {@code /fta drive on} 开始手动驾驶，{@code /fta drive off} 结束。各子命令要求的权限节点见 {@link
 * DrivePermissions}：命令注册、Tab 补全与帮助都按它过滤。
 */
public final class FtaDriveCommand {

  private static final String PERMISSION_ADMIN = DrivePermissions.ADMIN;
  private static final double KMH_PER_BPS = 3.6;
  private static final int RECORD_LINES = 10;
  private static final java.time.format.DateTimeFormatter TIME =
      java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm");

  private final FetaruteTCAddon plugin;

  public FtaDriveCommand(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  /** 注册 {@code /fta drive} 子命令与补全。 */
  public void register(CommandManager<CommandSender> manager) {
    SuggestionProvider<CommandSender> directionSuggestions =
        SuggestionProvider.suggestingStrings("forward", "neutral", "reverse");
    SuggestionProvider<CommandSender> toggleSuggestions =
        SuggestionProvider.suggestingStrings("on", "off");
    SuggestionProvider<CommandSender> driverSuggestions =
        SuggestionProvider.blockingStrings(
            (ctx, input) -> {
              List<String> names = new ArrayList<>();
              DriveSessionManager drive = plugin.getDriveSessionManager();
              if (drive != null) {
                for (DriveSession session : drive.sessions()) {
                  names.add(session.playerName());
                }
              }
              return names;
            });
    SuggestionProvider<CommandSender> handbackSuggestions =
        SuggestionProvider.blockingStrings(
            (ctx, input) -> {
              List<String> names = new ArrayList<>();
              names.add("all");
              DriveSessionManager drive = plugin.getDriveSessionManager();
              if (drive != null) {
                for (DriveSession session : drive.sessions()) {
                  if (session.isDispatchDriving()) {
                    names.add(session.playerName());
                  }
                }
              }
              return names;
            });

    // 列车名可能带中文终点字、? 或空格：参数用 quotedString，需要时候选带引号。
    SuggestionProvider<CommandSender> trainSuggestions =
        SuggestionProvider.blockingStrings(
            (ctx, input) -> {
              List<String> names = new ArrayList<>();
              for (MinecartGroup group : MinecartGroupStore.getGroups()) {
                if (group != null && group.isValid()) {
                  names.add(group.getProperties().getTrainName());
                }
              }
              return CommandUx.suggestions(names, input.lastRemainingToken());
            });
    // 收回任务：领了任务的玩家，包括领完还没上车、已经下线的。
    SuggestionProvider<CommandSender> taskHolderSuggestions =
        SuggestionProvider.blockingStrings(
            (ctx, input) -> {
              java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
              DriveSessionManager drive = plugin.getDriveSessionManager();
              if (drive != null) {
                for (DriverTask task : drive.tasks().activeTasks()) {
                  names.add(task.playerName());
                }
              }
              return List.copyOf(names);
            });
    // 查别人的记录要管理权限：有权限时补全在线玩家。
    SuggestionProvider<CommandSender> recordsSuggestions =
        SuggestionProvider.blockingStrings(
            (ctx, input) -> {
              if (!ctx.sender().hasPermission(PERMISSION_ADMIN)) {
                return List.of();
              }
              List<String> names = new ArrayList<>();
              names.add("<player>");
              for (Player online : Bukkit.getOnlinePlayers()) {
                names.add(online.getName());
              }
              return names;
            });
    // 没有 ATO 权限的不补出 ato。
    SuggestionProvider<CommandSender> modeSuggestions =
        SuggestionProvider.blockingStrings(
            (ctx, input) ->
                DrivePermissions.allowsMode(DrivingMode.ATO, ctx.sender()::hasPermission)
                    ? List.of("manual", "ato")
                    : List.of("manual"));
    // 车站可写“运营商:站码”区分重名站，冒号不加引号不合法：参数用 quotedString，需要时候选带引号。
    SuggestionProvider<CommandSender> stationSuggestions =
        SuggestionProvider.blockingStrings(
            (ctx, input) ->
                CommandUx.suggestions(
                    TaskBoardStations.suggestions(TaskBoardSource.stations(plugin)),
                    input.lastRemainingToken()));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .permission(anyOf(DrivePermissions.anyCommand()))
            .handler(ctx -> sendHelp(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("on")
            .permission(permissionOf("on"))
            .handler(ctx -> handleOn(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("on")
            .literal("cab")
            .permission(permissionOf("on"))
            .handler(ctx -> handleOnCab(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("off")
            .permission(permissionOf("off"))
            .handler(ctx -> handleOff(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("status")
            .permission(permissionOf("status"))
            .handler(ctx -> handleStatus(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("cab")
            .permission(permissionOf("cab"))
            .handler(ctx -> handleCab(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("reverser")
            .permission(permissionOf("reverser"))
            .required("direction", StringParser.stringParser(), directionSuggestions)
            .handler(ctx -> handleReverser(ctx.sender(), ((String) ctx.get("direction")).trim())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("list")
            .permission(permissionOf("list"))
            .handler(ctx -> handleList(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("stop")
            .permission(permissionOf("stop"))
            .required("player", StringParser.stringParser(), driverSuggestions)
            .handler(ctx -> handleStop(ctx.sender(), ((String) ctx.get("player")).trim())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("tasks")
            .permission(permissionOf("tasks"))
            .optional("station", StringParser.quotedStringParser(), stationSuggestions)
            .handler(
                ctx -> handleTasks(ctx.sender(), ctx.optional("station").map(String.class::cast))));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("tutorial")
            .permission(permissionOf("tutorial"))
            .optional(
                "action",
                StringParser.stringParser(),
                SuggestionProvider.suggestingStrings("start", "restart", "stop", "reset", "skip"))
            .handler(
                ctx ->
                    handleTutorial(
                        ctx.sender(),
                        ctx.optional("action").map(String.class::cast).orElse("start"))));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("task")
            .permission(permissionOf("task"))
            .optional(
                "action",
                StringParser.stringParser(),
                SuggestionProvider.suggestingStrings("status", "abandon", "goto", "pickup"))
            .handler(
                ctx ->
                    handleTask(
                        ctx.sender(),
                        ctx.optional("action").map(String.class::cast).orElse("status"))));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("records")
            .permission(permissionOf("records"))
            .optional("player", StringParser.stringParser(), recordsSuggestions)
            .handler(
                ctx ->
                    handleRecords(
                        ctx.sender(),
                        ctx.optional("player").map(String.class::cast).orElse(null))));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("top")
            .permission(permissionOf("top"))
            .optional(
                "period",
                StringParser.stringParser(),
                SuggestionProvider.suggestingStrings("week", "all"))
            .handler(
                ctx ->
                    handleTop(
                        ctx.sender(),
                        ctx.optional("period").map(String.class::cast).orElse("week"))));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("revoke")
            .permission(permissionOf("revoke"))
            .required("player", StringParser.stringParser(), taskHolderSuggestions)
            .handler(ctx -> handleRevoke(ctx.sender(), ((String) ctx.get("player")).trim())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("mode")
            .permission(permissionOf("mode"))
            .required("mode", StringParser.stringParser(), modeSuggestions)
            .handler(ctx -> handleMode(ctx.sender(), ((String) ctx.get("mode")).trim())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("congestion", "breaker")
            .permission(permissionOf("congestion"))
            .optional(
                "action",
                StringParser.stringParser(),
                SuggestionProvider.suggestingStrings("status", "reset"))
            .handler(
                ctx ->
                    handleCongestion(
                        ctx.sender(),
                        ctx.optional("action").map(String.class::cast).orElse("status"))));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("handback")
            .permission(permissionOf("handback"))
            .required("target", StringParser.stringParser(), handbackSuggestions)
            .handler(ctx -> handleHandback(ctx.sender(), ((String) ctx.get("target")).trim())));

    List<String> faultTypes = new ArrayList<>();
    for (CabFault fault : CabFault.values()) {
      faultTypes.add(fault.key());
    }
    faultTypes.add("clear");
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("fault")
            .permission(PERMISSION_ADMIN)
            .required("player", StringParser.stringParser(), driverSuggestions)
            .required(
                "type",
                StringParser.stringParser(),
                SuggestionProvider.suggestingStrings(faultTypes))
            .handler(
                ctx ->
                    handleFault(
                        ctx.sender(),
                        ((String) ctx.get("player")).trim(),
                        ((String) ctx.get("type")).trim())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("rescue")
            .permission(PERMISSION_ADMIN)
            .required("train", StringParser.quotedStringParser(), trainSuggestions)
            .optional(
                "action",
                StringParser.stringParser(),
                SuggestionProvider.suggestingStrings("destroy"))
            .handler(
                ctx ->
                    handleRescue(
                        ctx.sender(),
                        ((String) ctx.get("train")).trim(),
                        ctx.optional("action")
                            .map(String.class::cast)
                            .map(action -> action.equalsIgnoreCase("destroy"))
                            .orElse(false))));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("probe")
            .permission(permissionOf("probe"))
            .optional("state", StringParser.stringParser(), toggleSuggestions)
            .handler(
                ctx -> handleProbe(ctx.sender(), ctx.optional("state").map(String.class::cast))));
  }

  /** 子命令要求的节点（满足任一即可）。 */
  private static Permission permissionOf(String subcommand) {
    return anyOf(DrivePermissions.of(subcommand));
  }

  private static Permission anyOf(List<String> nodes) {
    if (nodes.size() == 1) {
      return Permission.of(nodes.get(0));
    }
    List<Permission> permissions = new ArrayList<>(nodes.size());
    for (String node : nodes) {
      permissions.add(Permission.of(node));
    }
    return Permission.anyOf(permissions);
  }

  /** 帮助只列出有权限使用的子命令。 */
  private void sendHelp(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    sender.sendMessage(locale.component("drive.command.help.header"));
    for (String entry : DrivePermissions.visibleSubcommands(sender::hasPermission)) {
      sender.sendMessage(locale.component("drive.command.help.entry-" + entry));
    }
  }

  private void handleOn(CommandSender sender) {
    Player player = requirePlayer(sender);
    if (player == null) {
      return;
    }
    DriveSessionManager drive = requireManager(sender);
    if (drive == null) {
      return;
    }
    DriveSessionManager.StartOutcome outcome = drive.startSession(player);
    sendStartOutcome(player, drive, outcome);
    // 没坐在要驾驶的那一端驾驶室：提议直接送过去再开始。
    if ((outcome == DriveSessionManager.StartOutcome.NOT_HEAD_CAB
            || outcome == DriveSessionManager.StartOutcome.NOT_SEATED)
        && drive.canEnterCab(player)) {
      sender.sendMessage(plugin.getLocaleManager().component("drive.command.start.cab-offer"));
    }
  }

  /** 送进下一趟要驾驶的那一端驾驶室，再开始驾驶。 */
  private void handleOnCab(CommandSender sender) {
    Player player = requirePlayer(sender);
    if (player == null) {
      return;
    }
    DriveSessionManager drive = requireManager(sender);
    if (drive == null) {
      return;
    }
    drive.startSessionInCab(player, outcome -> sendStartOutcome(player, drive, outcome));
  }

  private void sendStartOutcome(
      Player player, DriveSessionManager drive, DriveSessionManager.StartOutcome outcome) {
    if (!player.isOnline()) {
      return;
    }
    String key = "drive.command.start." + outcome.name().toLowerCase(Locale.ROOT).replace('_', '-');
    Map<String, String> placeholders =
        drive
            .sessionOf(player.getUniqueId())
            .map(session -> Map.of("train", session.trainName()))
            .orElse(Map.of());
    if (outcome == DriveSessionManager.StartOutcome.TRAIN_MOVING) {
      placeholders =
          Map.of(
              "speed_kmh",
              drive
                  .trainSpeedKmh(player)
                  .map(speed -> String.format(Locale.ROOT, "%.1f", speed))
                  .orElse("-"));
    }
    if (outcome == DriveSessionManager.StartOutcome.RESERVED_BY_OTHER) {
      placeholders = Map.of("player", drive.reservedByForSeat(player).orElse("-"));
      if (DrivePermissions.of("revoke").stream().anyMatch(player::hasPermission)) {
        key = key + "-revoke";
      }
    }
    player.sendMessage(plugin.getLocaleManager().component(key, placeholders));
  }

  private void handleOff(CommandSender sender) {
    Player player = requirePlayer(sender);
    if (player == null) {
      return;
    }
    DriveSessionManager drive = requireManager(sender);
    if (drive == null) {
      return;
    }
    boolean stopped = drive.stopSession(player.getUniqueId(), DriveSession.EndReason.COMMAND);
    sender.sendMessage(
        plugin
            .getLocaleManager()
            .component(stopped ? "drive.command.stop.stopped" : "drive.command.stop.not-driving"));
  }

  private void handleStatus(CommandSender sender) {
    Player player = requirePlayer(sender);
    if (player == null) {
      return;
    }
    DriveSessionManager drive = requireManager(sender);
    if (drive == null) {
      return;
    }
    LocaleManager locale = plugin.getLocaleManager();
    Optional<DriveSession> session = drive.sessionOf(player.getUniqueId());
    if (session.isEmpty()) {
      sender.sendMessage(locale.component("drive.command.stop.not-driving"));
      return;
    }
    sender.sendMessage(locale.component("drive.command.status", describe(locale, session.get())));
  }

  /** 直接坐到发车端的驾驶室：驾驶中是折返换端（换端提示里的按钮），还没驾驶时同 {@code /fta drive on cab}。 */
  private void handleCab(CommandSender sender) {
    Player player = requirePlayer(sender);
    if (player == null) {
      return;
    }
    DriveSessionManager drive = requireManager(sender);
    if (drive == null) {
      return;
    }
    if (drive.sessionOf(player.getUniqueId()).isEmpty()) {
      drive.startSessionInCab(player, outcome -> sendStartOutcome(player, drive, outcome));
      return;
    }
    sender.sendMessage(plugin.getLocaleManager().component(drive.switchCab(player)));
  }

  private void handleReverser(CommandSender sender, String direction) {
    Player player = requirePlayer(sender);
    if (player == null) {
      return;
    }
    DriveSessionManager drive = requireManager(sender);
    if (drive == null) {
      return;
    }
    LocaleManager locale = plugin.getLocaleManager();
    Optional<DriveSession> session = drive.sessionOf(player.getUniqueId());
    if (session.isEmpty()) {
      sender.sendMessage(locale.component("drive.command.stop.not-driving"));
      return;
    }
    Optional<ReverserPosition> position = ReverserPosition.parse(direction);
    if (position.isEmpty()) {
      sender.sendMessage(locale.component("drive.command.reverser.invalid"));
      return;
    }
    if (!session.get().isStopped()) {
      sender.sendMessage(locale.component("drive.command.reverser.need-stop"));
      return;
    }
    if (session.get().anyDoorOpen()) {
      sender.sendMessage(locale.component("drive.menu.deny.doors-open"));
      return;
    }
    if (session.get().isDispatchDriving()) {
      sender.sendMessage(locale.component("drive.menu.deny.reverser-locked"));
      return;
    }
    session.get().setReverser(position.get());
    sender.sendMessage(
        locale.component(
            "drive.command.reverser.set",
            Map.of(
                "direction",
                locale.text(
                    "drive.hud.direction." + position.get().name().toLowerCase(Locale.ROOT)))));
  }

  private void handleList(CommandSender sender) {
    DriveSessionManager drive = requireManager(sender);
    if (drive == null) {
      return;
    }
    LocaleManager locale = plugin.getLocaleManager();
    List<DriveSession> sessions = drive.sessions();
    sender.sendMessage(
        locale.component(
            "drive.command.list.header", Map.of("count", String.valueOf(sessions.size()))));
    for (DriveSession session : sessions) {
      sender.sendMessage(locale.component("drive.command.list.entry", describe(locale, session)));
    }
  }

  private void handleStop(CommandSender sender, String playerName) {
    DriveSessionManager drive = requireManager(sender);
    if (drive == null) {
      return;
    }
    LocaleManager locale = plugin.getLocaleManager();
    Player target = Bukkit.getPlayerExact(playerName);
    boolean stopped =
        target != null && drive.stopSession(target.getUniqueId(), DriveSession.EndReason.ADMIN);
    sender.sendMessage(
        locale.component(
            stopped ? "drive.command.admin-stop.stopped" : "drive.command.admin-stop.not-driving",
            Map.of("player", playerName)));
  }

  /** 打开任务板：给了车站代码就是那一站，否则是玩家附近最近的车站。 */
  private void handleTasks(CommandSender sender, Optional<String> stationArg) {
    Player player = requirePlayer(sender);
    if (player == null) {
      return;
    }
    DriveSessionManager drive = requireManager(sender);
    if (drive == null) {
      return;
    }
    LocaleManager locale = plugin.getLocaleManager();
    if (drive.sessionOf(player.getUniqueId()).isPresent()) {
      sender.sendMessage(locale.component("drive.task.board.driving"));
      return;
    }
    if (!drive.config().enabled() || !drive.config().driver().enabled()) {
      sender.sendMessage(locale.component("drive.task.claim.disabled"));
      return;
    }
    Instant now = Instant.now();
    if (drive.tasks().congestionProtection().open(now)) {
      sender.sendMessage(locale.component("drive.task.claim.protection-active"));
      return;
    }
    Optional<TaskBoardSource.Station> station;
    if (stationArg.filter(arg -> !arg.isBlank()).isPresent()) {
      String code = stationArg.get().trim();
      TaskBoardStations.Lookup lookup =
          TaskBoardStations.find(TaskBoardSource.stations(plugin), code);
      if (lookup.outcome() == TaskBoardStations.Outcome.AMBIGUOUS) {
        sender.sendMessage(
            locale.component(
                "drive.task.board.station-ambiguous",
                Map.of("station", code, "candidates", String.join(", ", lookup.candidates()))));
        return;
      }
      if (lookup.outcome() == TaskBoardStations.Outcome.NOT_FOUND) {
        sender.sendMessage(
            locale.component("drive.task.board.station-not-found", Map.of("station", code)));
        sendStationChoices(sender, locale);
        return;
      }
      station = lookup.station();
    } else {
      station = TaskBoardSource.nearestStation(plugin, player.getLocation());
      if (station.isEmpty()) {
        sender.sendMessage(locale.component("drive.task.board.no-station"));
        sendStationChoices(sender, locale);
        return;
      }
    }
    List<TaskBoardEntries.Entry> entries =
        TaskBoardSource.withTrips(
            plugin,
            TaskBoardEntries.board(
                TaskBoardSource.departures(
                    plugin,
                    station.get(),
                    now,
                    drive.config().driver().recovery().taskWindowMinutes()),
                drive.tasks().claimants(),
                now,
                TaskBoard.ENTRY_SLOTS));
    TaskBoard.open(
        player,
        locale,
        new TaskBoardHolder(
            player.getUniqueId(),
            station.get().operatorCode(),
            station.get().stationCode(),
            station.get().name(),
            entries),
        DrivePermissions.allowsMode(DrivingMode.ATO, player::hasPermission),
        player.hasPermission(DrivePermissions.LEVEL)
            ? Optional.of(drive.levels().effective(player, drive.config().level()))
            : Optional.empty());
  }

  /** 列出全部车站，点站码即打开那一站的任务板（悬停显示站名）。 */
  private void sendStationChoices(CommandSender sender, LocaleManager locale) {
    List<TaskBoardStations.Choice> choices =
        TaskBoardStations.choices(TaskBoardSource.stations(plugin));
    if (choices.isEmpty()) {
      return;
    }
    Component line = locale.component("drive.task.board.station-list");
    for (TaskBoardStations.Choice choice : choices) {
      line =
          line.append(Component.space())
              .append(
                  locale
                      .component(
                          "drive.task.board.station-choice",
                          Map.of("code", choice.argument(), "station", choice.name()))
                      .clickEvent(
                          ClickEvent.runCommand(
                              "/fta drive tasks " + CommandUx.suggestion(choice.argument(), false)))
                      .hoverEvent(
                          HoverEvent.showText(
                              locale.component(
                                  "drive.task.board.station-choice-hover",
                                  Map.of("code", choice.argument(), "station", choice.name())))));
    }
    sender.sendMessage(line);
  }

  /** 新手教程：开始、从头开始、退出、重置，或跳过当前一步。 */
  private void handleTutorial(CommandSender sender, String action) {
    Player player = requirePlayer(sender);
    if (player == null) {
      return;
    }
    DriveSessionManager drive = requireManager(sender);
    if (drive == null) {
      return;
    }
    String key =
        switch (action.toLowerCase(Locale.ROOT)) {
          case "start" -> drive.startTutorial(player);
          case "restart" -> drive.restartTutorial(player);
          case "stop" -> drive.tutorials().stop(player);
          case "reset" -> drive.tutorials().reset(player);
          case "skip" -> drive.skipTutorialStep(player);
          default -> "drive.tutorial.command.invalid";
        };
    if (key != null) {
      sender.sendMessage(plugin.getLocaleManager().component(key));
    }
  }

  /** 查看或放弃自己的任务。 */
  private void handleTask(CommandSender sender, String action) {
    Player player = requirePlayer(sender);
    if (player == null) {
      return;
    }
    DriveSessionManager drive = requireManager(sender);
    if (drive == null) {
      return;
    }
    LocaleManager locale = plugin.getLocaleManager();
    if (action.equalsIgnoreCase("abandon")) {
      sender.sendMessage(
          locale.component(drive.abandonTask(player) ? "drive.task.abandoned" : "drive.task.none"));
      return;
    }
    if (action.equalsIgnoreCase("goto")) {
      sender.sendMessage(locale.component(drive.gotoPickup(player)));
      return;
    }
    if (action.equalsIgnoreCase("pickup")) {
      if (!drive.togglePickup(player)) {
        sender.sendMessage(locale.component("drive.task.none"));
      }
      return;
    }
    Optional<DriverTask> task = drive.tasks().taskOf(player.getUniqueId());
    if (task.isEmpty()) {
      sender.sendMessage(locale.component("drive.task.none"));
      return;
    }
    DriverTask current = task.get();
    sender.sendMessage(
        locale.component(
            "drive.task.status",
            Map.of(
                "route",
                current.routeCode(),
                "trip",
                current.key().tripCode(),
                "station",
                current.stationName(),
                "time",
                TaskBoard.format(current.plannedDeparture()),
                "train",
                current.trainName() == null ? "-" : current.trainName(),
                "mode",
                locale.text("drive.driver.mode." + current.mode().name().toLowerCase(Locale.ROOT)),
                "state",
                locale.text(
                    "drive.task.state." + current.state().name().toLowerCase(Locale.ROOT)))));
  }

  /** 切换人工驾驶与 ATO。 */
  private void handleMode(CommandSender sender, String raw) {
    Player player = requirePlayer(sender);
    if (player == null) {
      return;
    }
    DriveSessionManager drive = requireManager(sender);
    if (drive == null) {
      return;
    }
    DrivingMode mode;
    if (raw.equalsIgnoreCase("ato")) {
      mode = DrivingMode.ATO;
    } else if (raw.equalsIgnoreCase("manual")) {
      mode = DrivingMode.MANUAL;
    } else {
      sender.sendMessage(plugin.getLocaleManager().component("drive.command.mode.invalid"));
      return;
    }
    if (!DrivePermissions.allowsMode(mode, player::hasPermission)) {
      sender.sendMessage(plugin.getLocaleManager().component("drive.command.mode.no-permission"));
      return;
    }
    sender.sendMessage(plugin.getLocaleManager().component(drive.setDrivingMode(player, mode)));
  }

  /** 驾驶记录：自己的（命令本身已要求记录或管理节点之一），或（管理员）别人的。 */
  private void handleRecords(CommandSender sender, String playerName) {
    LocaleManager locale = plugin.getLocaleManager();
    UUID target;
    String targetName;
    if (playerName == null || playerName.isBlank()) {
      Player self = requirePlayer(sender);
      if (self == null) {
        return;
      }
      target = self.getUniqueId();
      targetName = self.getName();
    } else {
      if (!sender.hasPermission(PERMISSION_ADMIN)) {
        sender.sendMessage(locale.component("drive.command.records.no-permission"));
        return;
      }
      // 记录按 UUID 存：离线玩家按服务器缓存过的名字查。
      org.bukkit.OfflinePlayer other = Bukkit.getOfflinePlayerIfCached(playerName);
      if (other == null) {
        sender.sendMessage(
            locale.component("drive.command.records.offline", Map.of("player", playerName)));
        return;
      }
      target = other.getUniqueId();
      targetName = other.getName() == null ? playerName : other.getName();
    }
    queryAsync(
        sender,
        provider -> provider.driveTaskRecords().listByPlayer(target, RECORD_LINES),
        records -> {
          if (records.isEmpty()) {
            sender.sendMessage(
                locale.component("drive.command.records.empty", Map.of("player", targetName)));
            return;
          }
          sender.sendMessage(
              locale.component("drive.command.records.header", Map.of("player", targetName)));
          for (DriveTaskRecord record : records) {
            sender.sendMessage(
                locale.component(
                    "drive.command.records.entry",
                    Map.of(
                        "time",
                        TIME.format(record.finishedAt().atZone(ZoneId.systemDefault())),
                        "route",
                        record.routeCode(),
                        "trip",
                        record.tripCode(),
                        "mode",
                        record.mode(),
                        "state",
                        locale.text("drive.task.state." + record.state().toLowerCase(Locale.ROOT)),
                        "points",
                        String.valueOf(record.points()),
                        "grade",
                        ScoreRules.UNGRADED.equals(record.grade())
                            ? locale.text("drive.task.ungraded")
                            : record.grade())));
          }
        });
  }

  /** 排行：本周（近 7 天）或全部完成的任务按总分。 */
  private void handleTop(CommandSender sender, String period) {
    LocaleManager locale = plugin.getLocaleManager();
    boolean all = period.equalsIgnoreCase("all");
    Instant since = all ? null : Instant.now().minus(Duration.ofDays(7));
    queryAsync(
        sender,
        provider -> provider.driveTaskRecords().leaderboard(since, RECORD_LINES),
        rows -> {
          sender.sendMessage(
              locale.component(
                  all ? "drive.command.top.header-all" : "drive.command.top.header-week"));
          if (rows.isEmpty()) {
            sender.sendMessage(locale.component("drive.command.top.empty"));
            return;
          }
          int rank = 1;
          for (DriveLeaderboardRow row : rows) {
            sender.sendMessage(
                locale.component(
                    "drive.command.top.entry",
                    Map.of(
                        "rank",
                        String.valueOf(rank++),
                        "player",
                        row.playerName(),
                        "tasks",
                        String.valueOf(row.tasks()),
                        "points",
                        String.valueOf(row.totalPoints()))));
          }
        });
  }

  /** 管理员收回某名玩家的任务。 */
  private void handleRevoke(CommandSender sender, String playerName) {
    DriveSessionManager drive = requireManager(sender);
    if (drive == null) {
      return;
    }
    // 领了任务就下线的玩家也要能收回：按任务里记的名字找。
    Player online = Bukkit.getPlayerExact(playerName);
    Optional<UUID> target =
        online != null
            ? Optional.of(online.getUniqueId())
            : drive.tasks().activeTasks().stream()
                .filter(task -> task.playerName().equalsIgnoreCase(playerName))
                .map(DriverTask::playerId)
                .findFirst();
    boolean revoked = target.isPresent() && drive.revokeTask(target.get());
    sender.sendMessage(
        plugin
            .getLocaleManager()
            .component(
                revoked ? "drive.command.revoke.done" : "drive.command.revoke.none",
                Map.of("player", playerName)));
  }

  /** 在异步线程查库，回主线程回复。存储未就绪时直接提示。 */
  private <T> void queryAsync(
      CommandSender sender,
      java.util.function.Function<StorageProvider, T> query,
      java.util.function.Consumer<T> reply) {
    Optional<StorageProvider> provider =
        plugin.getStorageManager() == null || !plugin.getStorageManager().isReady()
            ? Optional.empty()
            : plugin.getStorageManager().provider();
    if (provider.isEmpty()) {
      sender.sendMessage(plugin.getLocaleManager().component("drive.command.records.unavailable"));
      return;
    }
    Bukkit.getScheduler()
        .runTaskAsynchronously(
            plugin,
            () -> {
              T result;
              try {
                result = query.apply(provider.get());
              } catch (RuntimeException ex) {
                plugin.getLogger().warning("读取驾驶记录失败: " + ex);
                if (plugin.isEnabled()) {
                  Bukkit.getScheduler()
                      .runTask(
                          plugin,
                          () ->
                              sender.sendMessage(
                                  plugin
                                      .getLocaleManager()
                                      .component("drive.command.records.unavailable")));
                }
                return;
              }
              if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> reply.accept(result));
              }
            });
  }

  /** 查看或解除拥堵保护。 */
  private void handleCongestion(CommandSender sender, String action) {
    DriveSessionManager drive = requireManager(sender);
    if (drive == null) {
      return;
    }
    LocaleManager locale = plugin.getLocaleManager();
    CongestionProtection protection = drive.tasks().congestionProtection();
    if (action.equalsIgnoreCase("reset")) {
      protection.reset();
      sender.sendMessage(locale.component("drive.command.congestion.reset"));
      return;
    }
    Instant now = Instant.now();
    Component status =
        protection.open(now)
            ? locale.component(
                "drive.command.congestion.state.active",
                Map.of(
                    "seconds",
                    String.valueOf(Duration.between(now, protection.openUntil()).toSeconds()),
                    "reason",
                    protection.lastReason()))
            : locale.component("drive.command.congestion.state.inactive");
    sender.sendMessage(
        locale.component(
            "drive.command.congestion.status",
            TagResolver.resolver(Placeholder.component("status", status))));
  }

  /** 管理员把某名玩家（或全部）驾驶的调度列车交还自动运行：停稳后交还，行驶中先常用制动停车。 */
  private void handleHandback(CommandSender sender, String target) {
    DriveSessionManager drive = requireManager(sender);
    if (drive == null) {
      return;
    }
    LocaleManager locale = plugin.getLocaleManager();
    if (target.equalsIgnoreCase("all")) {
      int count = drive.handbackAll("admin");
      sender.sendMessage(
          locale.component("drive.command.handback.all", Map.of("count", String.valueOf(count))));
      return;
    }
    Player player = Bukkit.getPlayerExact(target);
    boolean requested = player != null && drive.handback(player.getUniqueId(), "admin");
    sender.sendMessage(
        locale.component(
            requested ? "drive.command.handback.requested" : "drive.command.handback.not-driving",
            Map.of("player", target)));
  }

  private void handleRescue(CommandSender sender, String train, boolean destroy) {
    DriveSessionManager drive = requireManager(sender);
    if (drive == null) {
      return;
    }
    DriveSessionManager.RescueResult result = drive.rescueTrain(train, destroy);
    LocaleManager locale = plugin.getLocaleManager();
    if (!result.found()) {
      sender.sendMessage(
          locale.component("drive.command.rescue.not-found", Map.of("train", train)));
      return;
    }
    sender.sendMessage(
        locale.component(
            result.relocated() || result.moved() == 0
                ? "drive.command.rescue.done"
                : "drive.command.rescue.no-target",
            Map.of("train", train, "players", String.valueOf(result.moved()))));
    if (result.destroyed()) {
      sender.sendMessage(
          locale.component("drive.command.rescue.destroyed", Map.of("train", train)));
    }
  }

  /** 管理员给某名驾驶员的列车注入或清除车上故障（仅 simulation 级）。 */
  private void handleFault(CommandSender sender, String playerName, String type) {
    DriveSessionManager drive = requireManager(sender);
    if (drive == null) {
      return;
    }
    LocaleManager locale = plugin.getLocaleManager();
    Map<String, String> values = Map.of("player", playerName, "type", type);
    Player target = Bukkit.getPlayerExact(playerName);
    Optional<DriveSession> session =
        target == null ? Optional.empty() : drive.sessionOf(target.getUniqueId());
    if (session.isEmpty() || session.get().phase() != DriveSession.Phase.ACTIVE) {
      sender.sendMessage(locale.component("drive.command.fault.not-driving", values));
      return;
    }
    CabSystems cab = session.get().cab();
    if (!cab.enabled()) {
      sender.sendMessage(locale.component("drive.command.fault.not-simulation", values));
      return;
    }
    if (type.equalsIgnoreCase("clear")) {
      int cleared = cab.faults().clearAll();
      sender.sendMessage(
          locale.component(
              cleared > 0 ? "drive.command.fault.cleared" : "drive.command.fault.none",
              Map.of("player", playerName, "count", String.valueOf(cleared))));
      return;
    }
    Optional<CabFault> fault = CabFault.parse(type);
    if (fault.isEmpty()) {
      sender.sendMessage(locale.component("drive.command.fault.invalid", values));
      return;
    }
    CabFaults.Outcome outcome = cab.faults().inject(fault.get(), Bukkit.getCurrentTick());
    String key =
        switch (outcome) {
          case INJECTED -> "drive.command.fault.injected";
          case ALREADY_ACTIVE -> "drive.command.fault.already-active";
          case NOT_APPLICABLE -> "drive.command.fault.not-applicable";
        };
    sender.sendMessage(
        locale.component(
            key,
            Map.of("player", playerName, "type", locale.text("drive.fault." + fault.get().key()))));
  }

  private void handleProbe(CommandSender sender, Optional<String> state) {
    DriveSessionManager drive = requireManager(sender);
    if (drive == null) {
      return;
    }
    boolean enabled = state.map(value -> value.equalsIgnoreCase("on")).orElse(!drive.trace());
    drive.setTrace(enabled);
    sender.sendMessage(
        plugin
            .getLocaleManager()
            .component(enabled ? "drive.command.probe.on" : "drive.command.probe.off"));
  }

  private Map<String, String> describe(LocaleManager locale, DriveSession session) {
    return Map.ofEntries(
        Map.entry("player", session.playerName()),
        Map.entry("train", session.trainName()),
        Map.entry("notch", session.notch().name()),
        Map.entry(
            "speed_kmh", String.format(Locale.ROOT, "%.1f", session.speedBps() * KMH_PER_BPS)),
        Map.entry("limit_kmh", limitText(session)),
        Map.entry("mode", enumText(locale, "drive.mode.", session.params().mode())),
        Map.entry("accel", String.format(Locale.ROOT, "%.2f", session.params().accelBps2())),
        Map.entry("decel", String.format(Locale.ROOT, "%.2f", session.params().decelBps2())),
        Map.entry(
            "direction",
            locale.text(
                "drive.hud.direction." + session.reverser().name().toLowerCase(Locale.ROOT))),
        Map.entry("phase", enumText(locale, "drive.phase.", session.phase())));
  }

  /** 枚举值的本地化文本；语言文件缺键时回退为枚举名。 */
  private static String enumText(LocaleManager locale, String prefix, Enum<?> value) {
    return locale.text(prefix + value.name().toLowerCase(Locale.ROOT));
  }

  private Player requirePlayer(CommandSender sender) {
    if (sender instanceof Player player) {
      return player;
    }
    sender.sendMessage(plugin.getLocaleManager().component("drive.command.player-only"));
    return null;
  }

  private DriveSessionManager requireManager(CommandSender sender) {
    DriveSessionManager drive = plugin.getDriveSessionManager();
    if (drive == null) {
      sender.sendMessage(plugin.getLocaleManager().component("drive.command.unavailable"));
    }
    return drive;
  }

  /** 状态里的限速文字；没有限速信息时为 {@code -}。 */
  private static String limitText(DriveSession session) {
    double limit = session.displayLimitBps();
    return Double.isFinite(limit) ? String.format(Locale.ROOT, "%.1f", limit * KMH_PER_BPS) : "-";
  }
}
