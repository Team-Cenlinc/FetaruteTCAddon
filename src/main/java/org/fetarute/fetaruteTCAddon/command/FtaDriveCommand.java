package org.fetarute.fetaruteTCAddon.command;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.drive.DrivePermissions;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveLeaderboardRow;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveTaskRecord;
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

    SuggestionProvider<CommandSender> stationSuggestions =
        SuggestionProvider.blockingStrings(
            (ctx, input) -> TaskBoardStations.suggestions(TaskBoardSource.stations(plugin)));

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
            .optional("station", StringParser.stringParser(), stationSuggestions)
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
                SuggestionProvider.suggestingStrings("start", "stop", "reset", "skip"))
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
            .optional("player", StringParser.stringParser())
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
            .required("player", StringParser.stringParser(), driverSuggestions)
            .handler(ctx -> handleRevoke(ctx.sender(), ((String) ctx.get("player")).trim())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("mode")
            .permission(permissionOf("mode"))
            .required(
                "mode",
                StringParser.stringParser(),
                SuggestionProvider.suggestingStrings("manual", "ato"))
            .handler(ctx -> handleMode(ctx.sender(), ((String) ctx.get("mode")).trim())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("breaker")
            .permission(permissionOf("breaker"))
            .optional(
                "action",
                StringParser.stringParser(),
                SuggestionProvider.suggestingStrings("status", "reset"))
            .handler(
                ctx ->
                    handleBreaker(
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
    sender.sendMessage(plugin.getLocaleManager().component(key, placeholders));
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
    if (drive.tasks().breaker().open(now)) {
      sender.sendMessage(locale.component("drive.task.claim.breaker-open"));
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
        return;
      }
      station = lookup.station();
    } else {
      station = TaskBoardSource.nearestStation(plugin, player.getLocation());
      if (station.isEmpty()) {
        sender.sendMessage(locale.component("drive.task.board.no-station"));
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
        DrivePermissions.allowsMode(DrivingMode.ATO, player::hasPermission));
  }

  /** 新手教程：开始、退出、重置，或跳过当前一步。 */
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
                        record.grade())));
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

  /** 查看或解除全网熔断。 */
  private void handleBreaker(CommandSender sender, String action) {
    DriveSessionManager drive = requireManager(sender);
    if (drive == null) {
      return;
    }
    LocaleManager locale = plugin.getLocaleManager();
    if (action.equalsIgnoreCase("reset")) {
      drive.tasks().breaker().reset();
      sender.sendMessage(locale.component("drive.command.breaker.reset"));
      return;
    }
    sender.sendMessage(
        locale.component(
            "drive.command.breaker.status",
            Map.of("status", drive.tasks().breakerStatus(Instant.now()))));
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
