package org.fetarute.fetaruteTCAddon.command;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.health.HealthAlert;
import org.fetarute.fetaruteTCAddon.dispatcher.health.HealthMonitor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;

/**
 * /fta health 命令注册。
 *
 * <p>用于查看健康监控状态、最近告警、手动触发检查与修复。
 */
public final class FtaHealthCommand {

  private static final DateTimeFormatter TIME_FMT =
      DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

  private final FetaruteTCAddon plugin;

  public FtaHealthCommand(FetaruteTCAddon plugin) {
    this.plugin = plugin;
  }

  /** 注册 /fta health 相关命令与补全。 */
  public void register(CommandManager<CommandSender> manager) {
    // /fta health - 帮助
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("health")
            .permission("fetarute.health")
            .handler(ctx -> sendHelp(ctx.sender())));

    // /fta health status - 当前状态
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("health")
            .literal("status")
            .permission("fetarute.health")
            .handler(ctx -> handleStatus(ctx.sender())));

    // /fta health alerts [limit] - 最近告警
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("health")
            .literal("alerts")
            .permission("fetarute.health")
            .optional(
                "limit",
                IntegerParser.integerParser(1, 50),
                CommandSuggestionProviders.placeholder("<limit>"))
            .handler(
                ctx -> {
                  int limit = ctx.<Integer>optional("limit").orElse(10);
                  handleAlerts(ctx.sender(), limit);
                }));

    // /fta health check - 立即执行一次检查
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("health")
            .literal("check")
            .permission("fetarute.health.check")
            .handler(ctx -> handleCheck(ctx.sender())));

    // /fta health heal [train] - 手动触发修复
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("health")
            .literal("heal")
            .permission("fetarute.health.heal")
            .optional(
                "train",
                StringParser.greedyStringParser(),
                CommandSuggestionProviders.placeholder("<train>"))
            .handler(
                ctx -> {
                  String train = ctx.<String>optional("train").orElse(null);
                  handleHeal(ctx.sender(), train);
                }));

    // /fta health toggle - 开关健康监控
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("health")
            .literal("toggle")
            .permission("fetarute.health.toggle")
            .handler(ctx -> handleToggle(ctx.sender())));

    // /fta health clear - 清除历史告警
    manager.command(
        manager
            .commandBuilder("fta")
            .literal("health")
            .literal("clear")
            .permission("fetarute.health.clear")
            .handler(ctx -> handleClear(ctx.sender())));
  }

  private void sendHelp(CommandSender sender) {
    sender.sendMessage(Component.text("===== /fta health =====", NamedTextColor.DARK_AQUA));
    sendHelpLine(sender, "/fta health status", "查看健康监控状态");
    sendHelpLine(sender, "/fta health alerts [limit]", "查看最近告警");
    sendHelpLine(sender, "/fta health check", "立即执行一次检查");
    sendHelpLine(sender, "/fta health heal [train]", "手动触发修复");
    sendHelpLine(sender, "/fta health toggle", "开关健康监控");
    sendHelpLine(sender, "/fta health clear", "清除历史告警");
  }

  private void sendHelpLine(CommandSender sender, String cmd, String desc) {
    Component line =
        Component.text("  " + cmd, NamedTextColor.AQUA)
            .clickEvent(ClickEvent.suggestCommand(cmd + " "))
            .append(Component.text(" - " + desc, NamedTextColor.GRAY));
    sender.sendMessage(line);
  }

  private void handleStatus(CommandSender sender) {
    Optional<HealthMonitor> monitorOpt = plugin.getHealthMonitor();
    if (monitorOpt.isEmpty()) {
      sender.sendMessage(Component.text("健康监控器未初始化", NamedTextColor.RED));
      return;
    }
    HealthMonitor.DiagnosticsSnapshot diag = monitorOpt.get().diagnostics();

    sender.sendMessage(Component.text("===== 健康监控状态 =====", NamedTextColor.DARK_AQUA));

    String enabledText = diag.enabled() ? "启用" : "禁用";
    NamedTextColor enabledColor = diag.enabled() ? NamedTextColor.GREEN : NamedTextColor.RED;
    sender.sendMessage(
        Component.text("状态: ", NamedTextColor.GRAY)
            .append(Component.text(enabledText, enabledColor)));

    sender.sendMessage(
        Component.text("总检查次数: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(diag.totalChecks()), NamedTextColor.WHITE)));

    sender.sendMessage(
        Component.text("总修复次数: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(diag.totalFixes()), NamedTextColor.WHITE)));

    String lastCheck =
        diag.lastCheckTime().equals(Instant.EPOCH)
            ? "从未"
            : TIME_FMT.format(diag.lastCheckTime())
                + " ("
                + formatDuration(Duration.between(diag.lastCheckTime(), Instant.now()))
                + "前)";
    sender.sendMessage(
        Component.text("上次检查: ", NamedTextColor.GRAY)
            .append(Component.text(lastCheck, NamedTextColor.WHITE)));

    sender.sendMessage(
        Component.text("最近告警数: ", NamedTextColor.GRAY)
            .append(
                Component.text(String.valueOf(diag.recentAlerts().size()), NamedTextColor.YELLOW)));
  }

  private void handleAlerts(CommandSender sender, int limit) {
    Optional<HealthMonitor> monitorOpt = plugin.getHealthMonitor();
    if (monitorOpt.isEmpty()) {
      sender.sendMessage(Component.text("健康监控器未初始化", NamedTextColor.RED));
      return;
    }
    List<HealthAlert> alerts = monitorOpt.get().diagnostics().recentAlerts();
    if (alerts.isEmpty()) {
      sender.sendMessage(Component.text("暂无告警记录", NamedTextColor.GRAY));
      return;
    }

    int show = Math.min(limit, alerts.size());
    sender.sendMessage(Component.text("===== 最近 " + show + " 条告警 =====", NamedTextColor.DARK_AQUA));

    // 从最新到最旧
    List<HealthAlert> toShow = alerts.subList(Math.max(0, alerts.size() - show), alerts.size());
    for (int i = toShow.size() - 1; i >= 0; i--) {
      HealthAlert alert = toShow.get(i);
      String time = TIME_FMT.format(alert.timestamp());
      String fixed = alert.autoFixed() ? "[已修复]" : "";
      String train = alert.trainName() != null ? alert.trainName() : "-";

      NamedTextColor typeColor =
          switch (alert.type()) {
            case STALL, PROGRESS_STUCK -> NamedTextColor.YELLOW;
            case ORPHAN_OCCUPANCY, OCCUPANCY_TIMEOUT -> NamedTextColor.AQUA;
            default -> NamedTextColor.WHITE;
          };

      Component line =
          Component.text(time + " ", NamedTextColor.GRAY)
              .append(Component.text(alert.type().name(), typeColor))
              .append(Component.text(" " + train + " ", NamedTextColor.WHITE))
              .append(Component.text(fixed, NamedTextColor.GREEN));
      sender.sendMessage(line);

      if (!alert.message().isBlank()) {
        sender.sendMessage(Component.text("  " + alert.message(), NamedTextColor.DARK_GRAY));
      }
    }
  }

  private void handleCheck(CommandSender sender) {
    Optional<HealthMonitor> monitorOpt = plugin.getHealthMonitor();
    if (monitorOpt.isEmpty()) {
      sender.sendMessage(Component.text("健康监控器未初始化", NamedTextColor.RED));
      return;
    }
    sender.sendMessage(Component.text("正在执行健康检查...", NamedTextColor.GRAY));

    HealthMonitor.CheckResult result = monitorOpt.get().checkNow();

    if (!result.hasIssues()) {
      sender.sendMessage(Component.text("检查完成，未发现异常", NamedTextColor.GREEN));
    } else {
      sender.sendMessage(
          Component.text("检查完成，发现问题:", NamedTextColor.YELLOW)
              .append(Component.text(" stall=" + result.stallCount(), NamedTextColor.WHITE))
              .append(Component.text(" stuck=" + result.progressStuckCount(), NamedTextColor.WHITE))
              .append(Component.text(" orphan=" + result.orphanCleaned(), NamedTextColor.WHITE))
              .append(Component.text(" timeout=" + result.timeoutCleaned(), NamedTextColor.WHITE))
              .append(Component.text(" 已修复=" + result.totalFixed(), NamedTextColor.GREEN)));
    }
  }

  private void handleHeal(CommandSender sender, String train) {
    Optional<HealthMonitor> monitorOpt = plugin.getHealthMonitor();
    if (monitorOpt.isEmpty()) {
      sender.sendMessage(Component.text("健康监控器未初始化", NamedTextColor.RED));
      return;
    }

    if (train == null || train.isBlank()) {
      // 执行全局检查+修复
      HealthMonitor.CheckResult result = monitorOpt.get().checkNow();
      sender.sendMessage(
          Component.text("全局修复完成: 修复了 " + result.totalFixed() + " 个问题", NamedTextColor.GREEN));
    } else {
      // 针对单列车修复
      plugin
          .getRuntimeDispatchService()
          .ifPresentOrElse(
              dispatch -> {
                dispatch.refreshSignalByName(train);
                boolean relaunched = dispatch.forceRelaunchByName(train);
                if (relaunched) {
                  sender.sendMessage(Component.text("已刷新信号并重发列车: " + train, NamedTextColor.GREEN));
                } else {
                  sender.sendMessage(
                      Component.text("已刷新信号，但重发失败（列车可能无 route）: " + train, NamedTextColor.YELLOW));
                }
              },
              () -> sender.sendMessage(Component.text("运行时调度服务未初始化", NamedTextColor.RED)));
    }
  }

  private void handleToggle(CommandSender sender) {
    Optional<HealthMonitor> monitorOpt = plugin.getHealthMonitor();
    if (monitorOpt.isEmpty()) {
      sender.sendMessage(Component.text("健康监控器未初始化", NamedTextColor.RED));
      return;
    }
    HealthMonitor monitor = monitorOpt.get();
    boolean current = monitor.diagnostics().enabled();
    monitor.setEnabled(!current);

    String newState = !current ? "启用" : "禁用";
    NamedTextColor color = !current ? NamedTextColor.GREEN : NamedTextColor.RED;
    sender.sendMessage(Component.text("健康监控已" + newState, color));
  }

  private void handleClear(CommandSender sender) {
    Optional<HealthMonitor> monitorOpt = plugin.getHealthMonitor();
    if (monitorOpt.isEmpty()) {
      sender.sendMessage(Component.text("健康监控器未初始化", NamedTextColor.RED));
      return;
    }
    monitorOpt.get().alertBus().clear();
    sender.sendMessage(Component.text("已清除告警历史", NamedTextColor.GREEN));
  }

  private String formatDuration(Duration d) {
    if (d.toHours() > 0) {
      return d.toHours() + "小时";
    }
    if (d.toMinutes() > 0) {
      return d.toMinutes() + "分钟";
    }
    return d.toSeconds() + "秒";
  }
}
