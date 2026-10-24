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
 * {@code /fta license}：操作许可证（驾驶、车掌、信号员）。玩家查看自己的许可证、报名考试与练习、补发；管理员发证、吊销、查询。
 *
 * <ul>
 *   <li>{@code /fta license}：我的操作许可证与可报名的考试
 *   <li>{@code /fta license exam <资格> [车站]}：报名考试（路考与车掌考试可指定接班站，默认附近的车站）
 *   <li>{@code /fta license practice <资格>}：报名练习
 *   <li>{@code /fta license abandon}：放弃还没上岗的信号员考试或练习
 *   <li>{@code /fta license reissue}：补发操作许可证
 *   <li>{@code /fta handbook driver|guard|signaller}：领一本手册
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
    // 报名：开放的等级；练习：开放的路考与车掌等级（教程级没有练习）；发证、吊销：配置里的全部等级（停用的也能处理）。
    SuggestionProvider<CommandSender> classSuggestions = classSuggestions(LicenseClass::enabled);
    SuggestionProvider<CommandSender> practiceClassSuggestions =
        classSuggestions(
            license -> license.enabled() && license.exam() != LicenseClass.Exam.TUTORIAL);
    SuggestionProvider<CommandSender> adminClassSuggestions = classSuggestions(license -> true);
    // 发证、吊销、查询接受服务器见过的离线玩家：补全在线玩家，输入的名字正好是见过的离线玩家时也列出它。
    SuggestionProvider<CommandSender> playerSuggestions =
        SuggestionProvider.blockingStrings(
            (ctx, input) -> knownPlayerNames(input.lastRemainingToken().trim()));
    // 车站可写“运营商:站码”区分重名站，冒号不加引号不合法：参数用 quotedString，需要时候选带引号。
    SuggestionProvider<CommandSender> stationSuggestions =
        SuggestionProvider.blockingStrings(
            (ctx, input) ->
                CommandUx.suggestions(
                    TaskBoardStations.suggestions(TaskBoardSource.stations(plugin)),
                    input.lastRemainingToken()));
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
            .optional("station", StringParser.quotedStringParser(), stationSuggestions)
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
            .required("class", StringParser.stringParser(), practiceClassSuggestions)
            .handler(ctx -> handlePractice(ctx.sender(), ((String) ctx.get("class")).trim())));
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
            .literal("handbook")
            .literal("guard")
            .permission(player)
            .handler(ctx -> handleGuardHandbook(ctx.sender())));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("handbook")
            .literal("signaller")
            .permission(player)
            .handler(ctx -> handleSignallerHandbook(ctx.sender())));
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("license")
            .literal("abandon")
            .permission(player)
            .handler(ctx -> handleAbandon(ctx.sender())));
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
            .required("class", StringParser.stringParser(), adminClassSuggestions)
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
            .required("class", StringParser.stringParser(), adminClassSuggestions)
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

  private SuggestionProvider<CommandSender> classSuggestions(
      java.util.function.Predicate<LicenseClass> filter) {
    return SuggestionProvider.blockingStrings(
        (ctx, input) ->
            service()
                .map(
                    licenses ->
                        licenses.config().classes().stream()
                            .filter(filter)
                            .map(LicenseClass::id)
                            .toList())
                .orElse(List.of()));
  }

  /**
   * 以输入开头的在线玩家名，最多 30 个；输入的名字正好是服务器见过的离线玩家时也列出它。
   *
   * <p>不遍历全部离线玩家：{@code getOfflinePlayers()} 每次都要为每个玩家文件建对象，逐键补全时太重。
   */
  private static List<String> knownPlayerNames(String typed) {
    String prefix = typed.toLowerCase(java.util.Locale.ROOT);
    java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
    for (Player online : Bukkit.getOnlinePlayers()) {
      if (online.getName().toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) {
        names.add(online.getName());
      }
    }
    if (!typed.isEmpty()) {
      OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(typed);
      if (cached != null && cached.getName() != null) {
        names.add(cached.getName());
      }
    }
    return names.stream().limit(30).toList();
  }

  /** 我的操作许可证：先列驾驶各级（从低到高），再列车掌、信号员——已取得、考试或练习中、须先练习、可报名（带按钮）、尚未解锁。 */
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
    // 三个岗位并列：先列驾驶的阶梯，再列车掌、信号员（各按配置先后）。
    for (boolean graded : new boolean[] {true, false}) {
      for (LicenseClass license : licenses.config().classes()) {
        if (license.graded() != graded) {
          continue;
        }
        sender.sendMessage(
            locale.component(
                infoKey(licenses, license, id, held, examining),
                infoValues(licenses, license, id, held)));
      }
    }
    sender.sendMessage(locale.component("drive.license.info.footer"));
  }

  /** /fta license 里一项资格的那一行：已取得、关闭、考试或练习中、未解锁、须先练习、可报名。信号员的练习与报名说法另写。 */
  private static String infoKey(
      LicenseService licenses,
      LicenseClass license,
      UUID id,
      Map<String, LicenseRecord> held,
      Optional<LicenseService.Exam> examining) {
    String prefix =
        license.graded() ? "drive.license.info.level-" : "drive.license.info.endorsement.";
    boolean signaller = license.exam() == LicenseClass.Exam.SIGNALLER;
    LicenseRecord record = held.get(license.id());
    if (record != null) {
      return prefix + ("exam".equals(record.grantedBy()) ? "held-exam" : "held-admin");
    }
    if (!license.enabled()) {
      return prefix + "closed";
    }
    if (examining.filter(exam -> license.id().equals(exam.classId())).isPresent()) {
      return prefix + (examining.get().training() ? "practice-running" : "exam-running");
    }
    for (String required : license.requires()) {
      if (!held.containsKey(required)) {
        return prefix + "locked";
      }
    }
    if (license.exam() == LicenseClass.Exam.TUTORIAL) {
      return prefix + "open-tutorial";
    }
    if (licenses.trainingRuns(id, license.id()) < license.trainingRuns()) {
      return signaller ? "drive.license.info.signaller.need-practice" : prefix + "need-practice";
    }
    if (signaller) {
      return "drive.license.info.signaller.open";
    }
    return prefix + (license.graded() ? "open-road-test" : "open");
  }

  /** 那一行的占位符：驾驶写级别与名称，车掌、信号员只写名称。 */
  private static Map<String, String> infoValues(
      LicenseService licenses, LicenseClass license, UUID id, Map<String, LicenseRecord> held) {
    Map<String, String> values = new java.util.HashMap<>();
    values.put("level", String.valueOf(licenses.config().levelOf(license.id())));
    values.put("name", license.name());
    values.put("class", license.id());
    values.put("description", license.description());
    values.put("stops", String.valueOf(license.examStops()));
    values.put("trains", String.valueOf(license.examStops()));
    values.put("min", String.valueOf(license.minPoints()));
    values.put("done", String.valueOf(licenses.trainingRuns(id, license.id())));
    values.put("required", String.valueOf(license.trainingRuns()));
    LicenseRecord record = held.get(license.id());
    if (record != null) {
      values.put("date", DATE.format(record.grantedAt()));
      values.put("by", record.grantedBy());
    }
    List<String> missing = new ArrayList<>();
    for (String required : license.requires()) {
      if (!held.containsKey(required)) {
        missing.add(licenses.levelName(required));
      }
    }
    if (!missing.isEmpty()) {
      values.put("required", String.join("、", missing));
    }
    return values;
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

  /** 领一本《FTCA 车掌手册》。 */
  private void handleGuardHandbook(CommandSender sender) {
    Player player = requirePlayer(sender);
    LicenseService licenses = requireService(sender);
    if (player == null || licenses == null) {
      return;
    }
    licenses.guardHandbook().give(player, plugin.getLocaleManager(), false);
    sender.sendMessage(plugin.getLocaleManager().component("drive.handbook.guard.given"));
  }

  /** 领一本《FTCA 信号员手册》。 */
  private void handleSignallerHandbook(CommandSender sender) {
    Player player = requirePlayer(sender);
    LicenseService licenses = requireService(sender);
    if (player == null || licenses == null) {
      return;
    }
    licenses.signallerHandbook().give(player, plugin.getLocaleManager(), false);
    sender.sendMessage(plugin.getLocaleManager().component("drive.handbook.signaller.given"));
  }

  private void handleAbandon(CommandSender sender) {
    Player player = requirePlayer(sender);
    LicenseService licenses = requireService(sender);
    if (player == null || licenses == null) {
      return;
    }
    send(sender, licenses.abandon(player));
  }

  private void handleExam(CommandSender sender, String classId, Optional<String> station) {
    Player player = requirePlayer(sender);
    LicenseService licenses = requireService(sender);
    if (player == null || licenses == null) {
      return;
    }
    send(sender, licenses.startExam(player, classId, station));
  }

  /** 报名练习：玩家先坐上要练的那列调度列车，再报名。 */
  private void handlePractice(CommandSender sender, String classId) {
    Player player = requirePlayer(sender);
    LicenseService licenses = requireService(sender);
    if (player == null || licenses == null) {
      return;
    }
    send(sender, licenses.startPractice(player, classId));
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
            Map.of("player", playerName, "name", licenses.displayName(license.get()))));
    Player online = target.get().getPlayer();
    if (online != null && online != sender) {
      online.sendMessage(
          locale.component(
              "drive.license.admin.granted-notice",
              Map.of("name", licenses.displayName(license.get()))));
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
