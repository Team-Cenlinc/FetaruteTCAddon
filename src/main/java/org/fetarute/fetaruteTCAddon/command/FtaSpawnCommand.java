package org.fetarute.fetaruteTCAddon.command;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnPlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.utils.LocaleManager;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.suggestion.SuggestionProvider;

/**
 * /fta spawn 命令：发车计划与队列诊断。
 *
 * <p>提供以下子命令：
 *
 * <ul>
 *   <li>{@code /fta spawn plan [limit]} - 查看发车计划（可发车的 Route 服务列表）
 *   <li>{@code /fta spawn queue [limit]} - 查看发车队列（待发票据）
 *   <li>{@code /fta spawn pending [limit]} - 查看折返待发票据（含失败重试）
 *   <li>{@code /fta spawn reset} - 清空发车队列并重置发车计划
 * </ul>
 *
 * <h3>术语说明</h3>
 *
 * <ul>
 *   <li><b>SpawnPlan</b>：从数据库构建的"可发车服务"快照，包含所有 ACTIVE 线路的发车配置
 *   <li><b>SpawnService</b>：单条可发车服务，包含 route、headway（发车间隔）、depotNodeId（出库点）
 *   <li><b>SpawnTicket</b>：一张发车票据，由 SpawnManager 生成并由 TicketAssigner 尝试放行
 *   <li><b>Headway</b>：发车间隔，即同一 route 相邻两班车的时间间隔
 *   <li><b>Pending</b>：折返待发，指等待 Layover 列车可用或占用释放的票据
 * </ul>
 *
 * <h3>权限</h3>
 *
 * <p>所有子命令需要 {@code fetarute.spawn} 权限。
 *
 * @see org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager
 * @see org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner
 */
public final class FtaSpawnCommand {

  /** 默认输出条数限制。 */
  private static final int DEFAULT_LIMIT = 20;

  private final FetaruteTCAddon plugin;

  public FtaSpawnCommand(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  /** 注册 /fta spawn 相关命令与补全。 */
  public void register(CommandManager<CommandSender> manager) {
    SuggestionProvider<CommandSender> limitSuggestions =
        CommandSuggestionProviders.placeholder("<limit>");

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("spawn")
            .permission("fetarute.spawn")
            .handler(ctx -> sendHelp(ctx.sender())));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("spawn")
            .literal("plan")
            .permission("fetarute.spawn")
            .optional("limit", IntegerParser.integerParser(1, 100), limitSuggestions)
            .handler(
                ctx -> {
                  int limit = ctx.<Integer>optional("limit").orElse(DEFAULT_LIMIT);
                  listPlan(ctx.sender(), limit);
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("spawn")
            .literal("queue")
            .permission("fetarute.spawn")
            .optional("limit", IntegerParser.integerParser(1, 100), limitSuggestions)
            .handler(
                ctx -> {
                  int limit = ctx.<Integer>optional("limit").orElse(DEFAULT_LIMIT);
                  listQueue(ctx.sender(), limit);
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("spawn")
            .literal("pending")
            .permission("fetarute.spawn")
            .optional("limit", IntegerParser.integerParser(1, 100), limitSuggestions)
            .handler(
                ctx -> {
                  int limit = ctx.<Integer>optional("limit").orElse(DEFAULT_LIMIT);
                  listPending(ctx.sender(), limit);
                }));

    manager.command(
        manager
            .commandBuilder("fta")
            .literal("spawn")
            .literal("reset")
            .permission("fetarute.spawn")
            .handler(ctx -> resetQueue(ctx.sender())));
  }

  private void sendHelp(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    sender.sendMessage(locale.component("command.spawn.help.header"));
    sendHelpEntry(
        sender,
        locale.component("command.spawn.help.entry-plan"),
        locale.component("command.spawn.help.hover-plan"),
        "/fta spawn plan ");
    sendHelpEntry(
        sender,
        locale.component("command.spawn.help.entry-queue"),
        locale.component("command.spawn.help.hover-queue"),
        "/fta spawn queue ");
    sendHelpEntry(
        sender,
        locale.component("command.spawn.help.entry-pending"),
        locale.component("command.spawn.help.hover-pending"),
        "/fta spawn pending ");
    sendHelpEntry(
        sender,
        locale.component("command.spawn.help.entry-reset"),
        locale.component("command.spawn.help.hover-reset"),
        "/fta spawn reset");
  }

  private void sendHelpEntry(
      CommandSender sender, Component entry, Component hover, String suggest) {
    Component msg =
        entry.hoverEvent(HoverEvent.showText(hover)).clickEvent(ClickEvent.suggestCommand(suggest));
    sender.sendMessage(msg);
  }

  /**
   * 列出发车计划（SpawnPlan）。
   *
   * <p>显示所有可发车服务，包括：
   *
   * <ul>
   *   <li>operator/line/route：服务标识
   *   <li>headway：发车间隔（如 2m30s 表示每 2 分 30 秒发一班）
   *   <li>depot：出库点 NodeId（对于 DYNAMIC 首站会显示 placeholder）
   * </ul>
   *
   * @param sender 命令发送者
   * @param limit 最大显示条数
   */
  private void listPlan(CommandSender sender, int limit) {
    LocaleManager locale = plugin.getLocaleManager();

    var mgrOpt = plugin.getSpawnManager();
    if (mgrOpt.isEmpty()) {
      sender.sendMessage(locale.component("command.spawn.not-ready"));
      return;
    }

    SpawnPlan plan = mgrOpt.get().snapshotPlan();
    List<SpawnService> services = plan.services();

    sender.sendMessage(
        locale.component(
            "command.spawn.plan.header",
            Map.of(
                "count", String.valueOf(services.size()),
                "built_at", formatInstant(plan.builtAt()))));

    if (services.isEmpty()) {
      sender.sendMessage(locale.component("command.spawn.plan.empty"));
      return;
    }

    // 按 operatorCode/lineCode/routeCode 排序
    List<SpawnService> sorted =
        services.stream()
            .sorted(
                Comparator.comparing(SpawnService::operatorCode)
                    .thenComparing(SpawnService::lineCode)
                    .thenComparing(SpawnService::routeCode))
            .limit(limit)
            .toList();

    for (SpawnService svc : sorted) {
      sender.sendMessage(
          locale.component(
              "command.spawn.plan.entry",
              Map.of(
                  "operator", svc.operatorCode(),
                  "line", svc.lineCode(),
                  "route", svc.routeCode(),
                  "headway", formatDuration(svc.baseHeadway()),
                  "depot", svc.depotNodeId())));
    }

    if (services.size() > limit) {
      sender.sendMessage(locale.component("command.spawn.plan.truncated"));
    }
  }

  /**
   * 列出发车队列（待发票据）。
   *
   * <p>显示由 SpawnManager 生成、等待 TicketAssigner 放行的票据：
   *
   * <ul>
   *   <li>operator/line/route：服务标识
   *   <li>due：计划发车时间（相对当前时间）
   *   <li>notBefore：最早可发车时间（用于重试延迟）
   *   <li>attempts：尝试次数（0 表示首次尝试）
   * </ul>
   *
   * @param sender 命令发送者
   * @param limit 最大显示条数
   */
  private void listQueue(CommandSender sender, int limit) {
    LocaleManager locale = plugin.getLocaleManager();

    var mgrOpt = plugin.getSpawnManager();
    if (mgrOpt.isEmpty()) {
      sender.sendMessage(locale.component("command.spawn.not-ready"));
      return;
    }

    List<SpawnTicket> queue = mgrOpt.get().snapshotQueue();

    sender.sendMessage(
        locale.component(
            "command.spawn.queue.header", Map.of("count", String.valueOf(queue.size()))));

    if (queue.isEmpty()) {
      sender.sendMessage(locale.component("command.spawn.queue.empty"));
      return;
    }

    // 按 dueAt 排序
    List<SpawnTicket> sorted =
        queue.stream().sorted(Comparator.comparing(SpawnTicket::dueAt)).limit(limit).toList();

    Instant now = Instant.now();
    for (SpawnTicket ticket : sorted) {
      SpawnService svc = ticket.service();
      String dueSec = formatDurationSec(Duration.between(now, ticket.dueAt()));
      String notBeforeSec = formatDurationSec(Duration.between(now, ticket.notBefore()));
      sender.sendMessage(
          locale.component(
              "command.spawn.queue.entry",
              Map.of(
                  "operator", svc.operatorCode(),
                  "line", svc.lineCode(),
                  "route", svc.routeCode(),
                  "due", dueSec,
                  "not_before", notBeforeSec,
                  "attempts", String.valueOf(ticket.attempts()))));
    }

    if (queue.size() > limit) {
      sender.sendMessage(locale.component("command.spawn.queue.truncated"));
    }
  }

  /**
   * 列出折返待发票据（Pending Layover）。
   *
   * <p>显示等待 Layover 列车可用或占用释放的票据，这些票据已从主队列移出，等待条件满足后立即派发：
   *
   * <ul>
   *   <li>operator/line/route：服务标识
   *   <li>due：原计划发车时间（相对当前时间）
   *   <li>attempts：尝试次数
   *   <li>error：上次失败原因（如占用冲突、列车不可用等）
   * </ul>
   *
   * @param sender 命令发送者
   * @param limit 最大显示条数
   */
  private void listPending(CommandSender sender, int limit) {
    LocaleManager locale = plugin.getLocaleManager();

    var assignerOpt = plugin.getSpawnTicketAssigner();
    if (assignerOpt.isEmpty()) {
      sender.sendMessage(locale.component("command.spawn.not-ready"));
      return;
    }

    List<SpawnTicket> pending = assignerOpt.get().snapshotPendingTickets();

    sender.sendMessage(
        locale.component(
            "command.spawn.pending.header", Map.of("count", String.valueOf(pending.size()))));

    if (pending.isEmpty()) {
      sender.sendMessage(locale.component("command.spawn.pending.empty"));
      return;
    }

    // 按 dueAt 排序
    List<SpawnTicket> sorted =
        pending.stream().sorted(Comparator.comparing(SpawnTicket::dueAt)).limit(limit).toList();

    Instant now = Instant.now();
    for (SpawnTicket ticket : sorted) {
      SpawnService svc = ticket.service();
      String dueSec = formatDurationSec(Duration.between(now, ticket.dueAt()));
      String error = ticket.lastError().orElse("-");
      sender.sendMessage(
          locale.component(
              "command.spawn.pending.entry",
              Map.of(
                  "operator", svc.operatorCode(),
                  "line", svc.lineCode(),
                  "route", svc.routeCode(),
                  "due", dueSec,
                  "attempts", String.valueOf(ticket.attempts()),
                  "error", error)));
    }

    if (pending.size() > limit) {
      sender.sendMessage(locale.component("command.spawn.pending.truncated"));
    }
  }

  private void resetQueue(CommandSender sender) {
    LocaleManager locale = plugin.getLocaleManager();
    var mgrOpt = plugin.getSpawnManager();
    var assignerOpt = plugin.getSpawnTicketAssigner();
    if (mgrOpt.isEmpty() || assignerOpt.isEmpty()) {
      sender.sendMessage(locale.component("command.spawn.not-ready"));
      return;
    }

    int clearedQueue = 0;
    int clearedStates = 0;
    boolean planReset = false;
    if (mgrOpt.get()
        instanceof
        org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnResetSupport
        support) {
      var result = support.reset(Instant.now());
      clearedQueue = result.clearedQueue();
      clearedStates = result.clearedStates();
      planReset = result.planReset();
    } else {
      sender.sendMessage(locale.component("command.spawn.reset.not-supported"));
    }

    int clearedPending = assignerOpt.get().clearPendingTickets();
    assignerOpt.get().resetDiagnostics();

    sender.sendMessage(
        locale.component(
            "command.spawn.reset.done",
            Map.of(
                "queue",
                String.valueOf(clearedQueue),
                "pending",
                String.valueOf(clearedPending),
                "states",
                String.valueOf(clearedStates),
                "plan",
                planReset ? "✓" : "✗")));
  }

  /**
   * 格式化 Instant 为 ISO 字符串。
   *
   * @param instant 时间点
   * @return ISO 格式字符串，若为 null 或 EPOCH 则返回 "-"
   */
  private static String formatInstant(Instant instant) {
    if (instant == null || instant.equals(Instant.EPOCH)) {
      return "-";
    }
    return instant.toString();
  }

  /**
   * 格式化 Duration 为人类可读字符串（如 "2m30s"）。
   *
   * @param duration 时长
   * @return 格式化字符串，若为 null 则返回 "-"
   */
  private static String formatDuration(Duration duration) {
    if (duration == null) {
      return "-";
    }
    long secs = duration.toSeconds();
    if (secs < 60) {
      return secs + "s";
    }
    return (secs / 60) + "m" + (secs % 60) + "s";
  }

  /**
   * 格式化相对时间（可能为负，表示已过期）。
   *
   * @param duration 时长（正数表示未来，负数表示已过期）
   * @return 格式化字符串，负数前缀"已过期"
   */
  private static String formatDurationSec(Duration duration) {
    if (duration == null) {
      return "-";
    }
    long secs = duration.toSeconds();
    if (secs < 0) {
      return "已过期 " + (-secs) + "s";
    }
    if (secs < 60) {
      return secs + "s";
    }
    return (secs / 60) + "m" + (secs % 60) + "s";
  }
}
