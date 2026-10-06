package org.fetarute.fetaruteTCAddon.command;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.drive.DrivePermissions;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardSource;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskBoardStations;
import org.fetarute.fetaruteTCAddon.drive.license.LicenseCardItem;
import org.fetarute.fetaruteTCAddon.drive.license.LicenseClass;
import org.fetarute.fetaruteTCAddon.drive.license.LicenseRecord;
import org.fetarute.fetaruteTCAddon.drive.license.LicenseService;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.permission.Permission;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * {@code /fta license}：驾驶证。玩家查看自己的驾驶证、报名考试、补发；管理员发证、吊销、查询。
 *
 * <ul>
 *   <li>{@code /fta license}：我的驾驶证与可报名的考试
 *   <li>{@code /fta license exam <等级> [车站]}：报名考试（路考可指定接班站，默认附近的车站）
 *   <li>{@code /fta license reissue}：补发驾驶证
 *   <li>{@code /fta license grant|revoke <玩家> <等级>}、{@code /fta license list <玩家>}：管理
 * </ul>
 */
public final class FtaLicenseCommand {

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

  private final FetaruteTCAddon plugin;

  public FtaLicenseCommand(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  public void register(CommandManager<CommandSender> manager) {
    SuggestionProvider<CommandSender> classSuggestions =
        SuggestionProvider.blockingStrings(
            (ctx, input) ->
                service()
                    .map(
                        licenses ->
                            licenses.config().classes().stream()
                                .filter(LicenseClass::enabled)
                                .map(LicenseClass::id)
                                .toList())
                    .orElse(List.of()));
    SuggestionProvider<CommandSender> playerSuggestions =
        SuggestionProvider.blockingStrings(
            (ctx, input) -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
    SuggestionProvider<CommandSender> stationSuggestions =
        SuggestionProvider.blockingStrings(
            (ctx, input) -> TaskBoardStations.suggestions(TaskBoardSource.stations(plugin)));
    Permission player = Permission.of(DrivePermissions.LICENSE);
    Permission admin = Permission.of(DrivePermissions.LICENSE_ADMIN);

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("license")
            .permission(Permission.anyOf(List.of(player, admin)))
            .handler(ctx -> handleInfo(ctx.sender())));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("license")
            .literal("exam")
            .permission(player)
            .required("class", StringParser.stringParser(), classSuggestions)
            .optional("station", StringParser.stringParser(), stationSuggestions)
            .handler(
                ctx ->
                    handleExam(
                        ctx.sender(),
                        ((String) ctx.get("class")).trim(),
                        ctx.optional("station").map(String.class::cast))));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("license")
            .literal("practice")
            .permission(player)
            .required("class", StringParser.stringParser(), classSuggestions)
            .optional("station", StringParser.stringParser(), stationSuggestions)
            .handler(
                ctx ->
                    handlePractice(
                        ctx.sender(),
                        ((String) ctx.get("class")).trim(),
                        ctx.optional("station").map(String.class::cast))));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("handbook")
            .literal("driver")
            .permission(player)
            .handler(ctx -> handleHandbook(ctx.sender())));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("license")
            .literal("reissue")
            .permission(player)
            .handler(ctx -> handleReissue(ctx.sender())));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("license")
            .literal("grant")
            .permission(admin)
            .required("player", StringParser.stringParser(), playerSuggestions)
            .required("class", StringParser.stringParser(), classSuggestions)
            .handler(
                ctx ->
                    handleGrant(
                        ctx.sender(),
                        ((String) ctx.get("player")).trim(),
                        ((String) ctx.get("class")).trim())));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("license")
            .literal("revoke")
            .permission(admin)
            .required("player", StringParser.stringParser(), playerSuggestions)
            .required("class", StringParser.stringParser(), classSuggestions)
            .handler(
                ctx ->
                    handleRevoke(
                        ctx.sender(),
                        ((String) ctx.get("player")).trim(),
                        ((String) ctx.get("class")).trim())));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("license")
            .literal("list")
            .permission(admin)
            .required("player", StringParser.stringParser(), playerSuggestions)
            .handler(ctx -> handleList(ctx.sender(), ((String) ctx.get("player")).trim())));
  }

  /** 我的驾驶证：按级别从低到高列出整条阶梯——已取得、考试或练习中、须先练习、可报名（带按钮）、尚未解锁。 */
  private void handleInfo(CommandSender sender) {
    Player player = requirePlayer(sender);
    LicenseService licenses = requireService(sender);
    if (player == null || licenses == null) {
      return;
    }
    LocaleManager locale = plugin.getLocaleManager();
    UUID id = player.getUniqueId();
    if (!licenses.config().enabled()) {
      sender.sendMessage(locale.component("drive.license.disabled"));
      return;
    }
    if (!licenses.ensureLoaded(player)) {
      sender.sendMessage(locale.component("drive.license.loading"));
      return;
    }
    sender.sendMessage(
        locale.component(
            "drive.license.info.header", Map.of("serial", LicenseCardItem.number(id))));
    Map<String, LicenseRecord> held = new java.util.HashMap<>();
    for (LicenseRecord record : licenses.held(id)) {
      held.put(record.classId(), record);
    }
    Optional<LicenseService.Exam> examining = licenses.exam(id);
    for (LicenseClass license : licenses.config().classes()) {
      Map<String, String> values = new java.util.HashMap<>();
      values.put("level", String.valueOf(licenses.config().levelOf(license.id())));
      values.put("name", license.name());
      values.put("class", license.id());
      values.put("description", license.description());
      values.put("stops", String.valueOf(license.examStops()));
      values.put("min", String.valueOf(license.minPoints()));
      values.put("done", String.valueOf(licenses.trainingRuns(id, license.id())));
      values.put("required", String.valueOf(license.trainingRuns()));
      LicenseRecord record = held.get(license.id());
      if (record != null) {
        values.put("date", DATE.format(record.grantedAt()));
        values.put("by", record.grantedBy());
        sender.sendMessage(
            locale.component(
                "exam".equals(record.grantedBy())
                    ? "drive.license.info.level-held-exam"
                    : "drive.license.info.level-held-admin",
                values));
        continue;
      }
      if (!license.enabled()) {
        sender.sendMessage(locale.component("drive.license.info.level-closed", values));
        continue;
      }
      if (examining.filter(exam -> license.id().equals(exam.classId())).isPresent()) {
        sender.sendMessage(
            locale.component(
                examining.get().training()
                    ? "drive.license.info.level-practice-running"
                    : "drive.license.info.level-exam-running",
                values));
        continue;
      }
      List<String> missing = new ArrayList<>();
      for (String required : license.requires()) {
        if (!held.containsKey(required)) {
          missing.add(licenses.levelName(required));
        }
      }
      if (!missing.isEmpty()) {
        values.put("required", String.join("、", missing));
        sender.sendMessage(locale.component("drive.license.info.level-locked", values));
        continue;
      }
      String key;
      if (license.exam() == LicenseClass.Exam.TUTORIAL) {
        key = "drive.license.info.level-open-tutorial";
      } else if (licenses.trainingRuns(id, license.id()) < license.trainingRuns()) {
        key = "drive.license.info.level-need-practice";
      } else {
        key = "drive.license.info.level-open-dispatch";
      }
      sender.sendMessage(locale.component(key, values));
    }
    sender.sendMessage(locale.component("drive.license.info.footer"));
  }

  /** 领一本《FTCA 驾驶员手册》。 */
  private void handleHandbook(CommandSender sender) {
    Player player = requirePlayer(sender);
    LicenseService licenses = requireService(sender);
    if (player == null || licenses == null) {
      return;
    }
    licenses.handbook().give(player, plugin.getLocaleManager(), false);
    sender.sendMessage(plugin.getLocaleManager().component("drive.handbook.driver.given"));
  }

  private void handleExam(CommandSender sender, String classId, Optional<String> station) {
    Player player = requirePlayer(sender);
    LicenseService licenses = requireService(sender);
    if (player == null || licenses == null) {
      return;
    }
    send(sender, licenses.startExam(player, classId, station));
  }

  private void handlePractice(CommandSender sender, String classId, Optional<String> station) {
    Player player = requirePlayer(sender);
    LicenseService licenses = requireService(sender);
    if (player == null || licenses == null) {
      return;
    }
    send(sender, licenses.startPractice(player, classId, station));
  }

  private void handleReissue(CommandSender sender) {
    Player player = requirePlayer(sender);
    LicenseService licenses = requireService(sender);
    if (player == null || licenses == null) {
      return;
    }
    send(sender, licenses.reissue(player));
  }

  private void handleGrant(CommandSender sender, String name, String classId) {
    LicenseService licenses = requireService(sender);
    if (licenses == null) {
      return;
    }
    LocaleManager locale = plugin.getLocaleManager();
    Optional<OfflinePlayer> target = resolve(name);
    Optional<LicenseClass> license = licenses.config().find(classId);
    if (target.isEmpty()) {
      sender.sendMessage(
          locale.component("drive.license.admin.player-not-found", Map.of("player", name)));
      return;
    }
    if (license.isEmpty()) {
      sender.sendMessage(
          locale.component("drive.license.exam.unknown-class", Map.of("class", classId)));
      return;
    }
    String playerName = Optional.ofNullable(target.get().getName()).orElse(name);
    licenses.grant(target.get().getUniqueId(), playerName, license.get(), sender.getName());
    sender.sendMessage(
        locale.component(
            target.get().isOnline()
                ? "drive.license.admin.granted"
                : "drive.license.admin.granted-offline",
            Map.of("player", playerName, "name", license.get().name())));
    Player online = target.get().getPlayer();
    if (online != null && online != sender) {
      online.sendMessage(
          locale.component(
              "drive.license.admin.granted-notice", Map.of("name", license.get().name())));
    }
  }

  private void handleRevoke(CommandSender sender, String name, String classId) {
    LicenseService licenses = requireService(sender);
    if (licenses == null) {
      return;
    }
    LocaleManager locale = plugin.getLocaleManager();
    Optional<OfflinePlayer> target = resolve(name);
    if (target.isEmpty()) {
      sender.sendMessage(
          locale.component("drive.license.admin.player-not-found", Map.of("player", name)));
      return;
    }
    String key = licenses.config().find(classId).map(LicenseClass::id).orElse(classId);
    String playerName = Optional.ofNullable(target.get().getName()).orElse(name);
    licenses.revoke(
        target.get().getUniqueId(),
        key,
        removed ->
            sender.sendMessage(
                locale.component(
                    removed.isEmpty()
                        ? "drive.license.storage-unavailable"
                        : removed.get()
                            ? "drive.license.admin.revoked"
                            : "drive.license.admin.not-held",
                    Map.of("player", playerName, "name", licenses.nameOf(key)))));
  }

  private void handleList(CommandSender sender, String name) {
    LicenseService licenses = requireService(sender);
    if (licenses == null) {
      return;
    }
    LocaleManager locale = plugin.getLocaleManager();
    Optional<OfflinePlayer> target = resolve(name);
    if (target.isEmpty()) {
      sender.sendMessage(
          locale.component("drive.license.admin.player-not-found", Map.of("player", name)));
      return;
    }
    String playerName = Optional.ofNullable(target.get().getName()).orElse(name);
    UUID id = target.get().getUniqueId();
    licenses.list(
        id,
        records -> {
          if (records.isEmpty()) {
            sender.sendMessage(locale.component("drive.license.storage-unavailable"));
            return;
          }
          sender.sendMessage(
              locale.component(
                  "drive.license.admin.list-header",
                  Map.of("player", playerName, "serial", LicenseCardItem.number(id))));
          if (records.get().isEmpty()) {
            sender.sendMessage(locale.component("drive.license.info.none"));
          }
          for (LicenseRecord record : records.get()) {
            sender.sendMessage(recordLine(locale, licenses, record));
          }
        });
  }

  private net.kyori.adventure.text.Component recordLine(
      LocaleManager locale, LicenseService licenses, LicenseRecord record) {
    return locale.component(
        "exam".equals(record.grantedBy())
            ? "drive.license.info.held-exam"
            : "drive.license.info.held-admin",
        Map.of(
            "name",
            licenses.nameOf(record.classId()),
            "date",
            DATE.format(record.grantedAt()),
            "by",
            record.grantedBy()));
  }

  /** 按名字找玩家：先找在线的，再找服务器见过的。 */
  private static Optional<OfflinePlayer> resolve(String name) {
    Player online = Bukkit.getPlayerExact(name);
    if (online != null) {
      return Optional.of(online);
    }
    return Optional.ofNullable(Bukkit.getOfflinePlayerIfCached(name));
  }

  private void send(CommandSender sender, LicenseService.Reply reply) {
    sender.sendMessage(plugin.getLocaleManager().component(reply.key(), reply.values()));
  }

  private Optional<LicenseService> service() {
    return Optional.ofNullable(plugin.getLicenseService());
  }

  private LicenseService requireService(CommandSender sender) {
    LicenseService licenses = plugin.getLicenseService();
    if (licenses == null) {
      sender.sendMessage(plugin.getLocaleManager().component("drive.command.unavailable"));
    }
    return licenses;
  }

  private Player requirePlayer(CommandSender sender) {
    if (sender instanceof Player player) {
      return player;
    }
    sender.sendMessage(plugin.getLocaleManager().component("drive.command.player-only"));
    return null;
  }
}
