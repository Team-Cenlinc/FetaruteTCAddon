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
import org.fetarute.fetaruteTCAddon.drive.dynamics.ReverserPosition;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSession;
import org.fetarute.fetaruteTCAddon.drive.session.DriveSessionManager;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * /fta drive 命令注册。
 *
 * <p>玩家坐在列车座位上执行 {@code /fta drive on} 开始手动驾驶，{@code /fta drive off} 结束。
 */
public final class FtaDriveCommand {

  private static final String PERMISSION = "fetarute.drive";
  private static final String PERMISSION_ADMIN = "fetarute.drive.admin";
  private static final double KMH_PER_BPS = 3.6;

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

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .permission(PERMISSION)
            .handler(ctx -> sendHelp(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("on")
            .permission(PERMISSION)
            .handler(ctx -> handleOn(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("off")
            .permission(PERMISSION)
            .handler(ctx -> handleOff(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("status")
            .permission(PERMISSION)
            .handler(ctx -> handleStatus(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("reverser")
            .permission(PERMISSION)
            .required("direction", StringParser.stringParser(), directionSuggestions)
            .handler(ctx -> handleReverser(ctx.sender(), ((String) ctx.get("direction")).trim())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("list")
            .permission(PERMISSION_ADMIN)
            .handler(ctx -> handleList(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("stop")
            .permission(PERMISSION_ADMIN)
            .required("player", StringParser.stringParser(), driverSuggestions)
            .handler(ctx -> handleStop(ctx.sender(), ((String) ctx.get("player")).trim())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("drive")
            .literal("probe")
            .permission(PERMISSION_ADMIN)
            .optional("state", StringParser.stringParser(), toggleSuggestions)
            .handler(
                ctx -> handleProbe(ctx.sender(), ctx.optional("state").map(String.class::cast))));
  }

  private void sendHelp(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    sender.sendMessage(locale.component("drive.command.help.header"));
    for (String entry : List.of("on", "off", "status", "reverser", "list", "stop", "probe")) {
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
