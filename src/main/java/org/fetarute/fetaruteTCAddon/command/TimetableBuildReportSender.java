package org.fetarute.fetaruteTCAddon.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.fetarute.fetaruteTCAddon.command.FtaTimetableCommand.NeighborReport;
import org.fetarute.fetaruteTCAddon.command.FtaTimetableCommand.ResolvedLine;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.ServiceGroupClassifier;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableBuildOptions;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableBuildReportText;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableBuildResult;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableBuilder;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableConflictChecker;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableCsvExporter;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableHeadwayDefaults;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRoutePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDutyPlanner;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.WeightedTripAllocator;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.scope.TimetableNeighborhoodLoader;

/**
 * build 报告的发送器：只做 Component 拼装与动作按钮。
 *
 * <p>从 {@link FtaTimetableCommand} 抽出来，因为那个类已经两千七百行，而报告这部分既不读库也不碰调度， 只把一份 {@link
 * TimetableBuildResult} 翻译成玩家看得懂的几行字加几个可点的按钮。 它<b>不读库</b>：需要的数一律由调用方传进来（包括 {@code
 * hold-max}，报告用它区分让车发生在站台还是资源前）。
 */
final class TimetableBuildReportSender {

  private final CommandSender sender;
  private final int holdMaxSeconds;

  /**
   * @param sender 收报告的人
   * @param holdMaxSeconds 站台扣留上限（{@code timetable.hold-max-seconds}）
   */
  TimetableBuildReportSender(CommandSender sender, int holdMaxSeconds) {
    this.sender = Objects.requireNonNull(sender, "sender");
    this.holdMaxSeconds = holdMaxSeconds;
  }

  void sendBuildReport(
      TimetableBuildResult result,
      TimetableBuildOptions options,
      TimetableHeadwayDefaults.Choice headway,
      Map<String, String> groupSources,
      String maxTripsSource) {
    sender.sendMessage(Component.text("===== 构建报告 =====", NamedTextColor.DARK_AQUA));
    if (!result.success()) {
      for (String warning : result.warnings()) {
        sender.sendMessage(Component.text("  ! " + warning, NamedTextColor.RED));
      }
      for (TimetableBuildResult.InfeasibleRoute route : result.infeasibleRoutes()) {
        sender.sendMessage(
            Component.text("  ! " + route.routeCode() + "：" + route.reason(), NamedTextColor.RED));
      }
      return;
    }
    Timetable timetable = result.timetable().orElseThrow();
    long operations = timetable.routePlans().stream().filter(TimetableRoutePlan::operation).count();
    long creates =
        timetable.routePlans().stream()
            .filter(plan -> plan.kind() == RouteOperationType.CREATE)
            .count();
    long returns =
        timetable.routePlans().stream()
            .filter(plan -> plan.kind() == RouteOperationType.RETURN)
            .count();
    sender.sendMessage(FtaTimetableCommand.field("班次", String.valueOf(result.tripCount())));
    sender.sendMessage(
        FtaTimetableCommand.field(
            "route", "运营 " + operations + "，出库线路 " + creates + "，回库线路 " + returns));
    sender.sendMessage(
        FtaTimetableCommand.field(
            "计划窗口",
            TimetableCsvExporter.clock(options.serviceStartSecondOfDay())
                + " → "
                + TimetableCsvExporter.clock(options.serviceEndSecondOfDay())
                + "，间隔 "
                + result.effectiveHeadwaySeconds()
                + "s"
                + (result.headwayRelaxed()
                    ? "（目标 " + result.targetHeadwaySeconds() + "s 有冲突，已放宽）"
                    : "")));
    if (result.groupIntervals().isEmpty()) {
      sender.sendMessage(
          FtaTimetableCommand.field("间隔来源", headway.description() + "：" + headway.seconds() + "s"));
    }
    for (String line :
        TimetableBuildReportText.describeGroups(result.groupIntervals(), groupSources)) {
      sender.sendMessage(Component.text("  " + line, NamedTextColor.GRAY));
    }
    sender.sendMessage(
        FtaTimetableCommand.field(
            "交路上限", options.dutyLimits().maxTripsPerDuty() + " 班/交路（" + maxTripsSource + "）"));
    for (String note : result.phaseNotes()) {
      sender.sendMessage(Component.text("  相位: " + note, NamedTextColor.DARK_GRAY));
    }
    for (String line : TimetableBuildReportText.describeInterleaves(result.interleaves())) {
      sender.sendMessage(Component.text("  " + line, NamedTextColor.GRAY));
    }
    sender.sendMessage(
        Component.text(
            "  " + TimetableBuildReportText.describeDutyShapes(result.dutyShapes()),
            NamedTextColor.GRAY));
    if (result.conflictsAtTarget().isEmpty()) {
      sender.sendMessage(
          Component.text(
              "  冲突检查: 无冲突（区间/站台/单线/道岔，间隔裕量 " + options.separation().toSeconds() + "s）",
              NamedTextColor.GREEN));
    } else {
      sender.sendMessage(
          Component.text(
              "  冲突检查: 目标间隔下 " + result.conflictsAtTarget().size() + " 处冲突，明细见下方警告",
              NamedTextColor.YELLOW));
    }
    for (String line :
        TimetableBuildReportText.describeResourcePhases(result.resourcePhaseNotes())) {
      sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
    }
    for (String line : TimetableBuildReportText.describeResidues(result.residues())) {
      sender.sendMessage(Component.text(line, NamedTextColor.DARK_GRAY));
    }
    // 残余两行：运行时能让的与让不掉的分开说。判定这张表行不行的只有后者。
    for (String line :
        TimetableBuildReportText.describeResiduals(
            result.absorbable(),
            result.unabsorbable(),
            holdMaxSeconds,
            options.serviceStartSecondOfDay(),
            FtaTimetableCommand.YIELD_DETAIL_LIMIT)) {
      sender.sendMessage(
          Component.text(
              line, line.startsWith("  不可吸收") ? NamedTextColor.RED : NamedTextColor.GRAY));
    }
    sender.sendMessage(
        Component.text(
            "  "
                + TimetableBuildReportText.describeYields(
                    result.yields(), options.repair().maxWaitSeconds()),
            result.yields().isEmpty() ? NamedTextColor.GRAY : NamedTextColor.AQUA));
    for (String line :
        TimetableBuildReportText.describeYieldDetails(
            result.yields(),
            options.serviceStartSecondOfDay(),
            FtaTimetableCommand.YIELD_DETAIL_LIMIT)) {
      sender.sendMessage(Component.text("    " + line, NamedTextColor.DARK_GRAY));
    }
    for (String line :
        TimetableBuildReportText.describeTerminals(
            result.terminals(), result.shifts(), options.dutyLimits().turnaround())) {
      sender.sendMessage(
          Component.text(
              "  " + line, line.contains("超过 100%") ? NamedTextColor.YELLOW : NamedTextColor.GRAY));
    }
    for (String line : TimetableBuildReportText.describeThroats(result.throats())) {
      sender.sendMessage(
          Component.text(
              "  " + line, line.contains("超过 100%") ? NamedTextColor.YELLOW : NamedTextColor.GRAY));
    }
    sender.sendMessage(FtaTimetableCommand.field("车辆交路", String.valueOf(result.dutyCount())));
    sender.sendMessage(
        FtaTimetableCommand.field(
            "计划用车",
            "全天 " + result.plannedVehicles() + " 次出库，峰值同时在线 " + result.peakConcurrentVehicles()));
    if (!result.droppedTrips().isEmpty()) {
      sender.sendMessage(
          Component.text(
              "  取消班次: " + result.droppedTrips().size() + "（起终点缺出库/回库线路，详见下方警告）",
              NamedTextColor.YELLOW));
    }

    sender.sendMessage(Component.text("  目标服务比例 / 实际:", NamedTextColor.GRAY));
    for (WeightedTripAllocator.ShareReport share : result.shares()) {
      NamedTextColor color =
          share.deviationPercentPoints() > TimetableBuilder.SHARE_WARN_PERCENT_POINTS
              ? NamedTextColor.YELLOW
              : NamedTextColor.WHITE;
      sender.sendMessage(
          Component.text(
              String.format(
                  Locale.ROOT,
                  "    %s (w=%d)  目标 %.1f%%  实际 %.1f%%  班次 %d",
                  share.key(),
                  share.weight(),
                  share.targetShare() * 100.0D,
                  share.achievedShare() * 100.0D,
                  share.assignedTrips()),
              color));
    }

    sender.sendMessage(FtaTimetableCommand.field("最长一趟车", result.longestTripSeconds() + "s"));
    sender.sendMessage(
        FtaTimetableCommand.field("单交路最多班次", String.valueOf(result.maxTripsInAnyDuty())));
    sender.sendMessage(FtaTimetableCommand.field("单交路最长在线", result.maxDutyDurationSeconds() + "s"));
    sender.sendMessage(
        Component.text(
            "  所有交路都以回库收尾: " + (result.allDutiesReturnToStorage() ? "是" : "否"),
            result.allDutiesReturnToStorage() ? NamedTextColor.GREEN : NamedTextColor.RED));
    for (String warning : result.warnings()) {
      sender.sendMessage(Component.text("  ! " + warning, NamedTextColor.YELLOW));
    }
  }

  /** 「共用资源」一节：有表的邻表参与了检查（列冲突数），无表的只能报告——它们按 headway 发车，干扰单向。 */
  void sendNeighborReport(
      NeighborReport neighbors, List<TimetableBuildResult.NeighborSummary> summaries) {
    if (neighbors == null) {
      return;
    }
    if (neighbors.scheduled().isEmpty() && neighbors.unscheduled().isEmpty()) {
      sender.sendMessage(Component.text("  共用资源: 没有别的线路与本表共用区间、站台、单线或道岔", NamedTextColor.GREEN));
      return;
    }
    Map<String, TimetableBuildResult.NeighborSummary> byCode = new java.util.HashMap<>();
    for (TimetableBuildResult.NeighborSummary summary : summaries) {
      byCode.put(summary.displayCode(), summary);
    }
    sender.sendMessage(Component.text("  共用资源:", NamedTextColor.GRAY));
    for (TimetableNeighborhoodLoader.FootprintNeighbor neighbor : neighbors.scheduled()) {
      TimetableBuildResult.NeighborSummary summary = byCode.get(neighbor.displayCode());
      String verdict =
          summary == null
              ? "已发布，本次未参与检查"
              : summary.conflictsAtTarget() == 0
                  ? "已发布，已避让，目标间隔下无冲突"
                  : "已发布，目标间隔下与它冲突 " + summary.conflictsAtTarget() + " 处（只能挪自己）";
      String flags =
          (summary != null && summary.stale() ? "，对方表基于旧图" : "")
              + (summary != null && summary.zoneApproximated() ? "，时区不同按当日偏移换算" : "");
      sender.sendMessage(
          Component.text(
              "    "
                  + neighbor.displayCode()
                  + " 共用 "
                  + neighbor.sharedResources()
                  + " 个资源，"
                  + verdict
                  + flags,
              summary != null && summary.conflictsAtTarget() == 0
                  ? NamedTextColor.WHITE
                  : NamedTextColor.YELLOW));
      for (String warning : neighbor.warnings()) {
        sender.sendMessage(Component.text("      · " + warning, NamedTextColor.DARK_GRAY));
      }
    }
    for (TimetableNeighborhoodLoader.UnscheduledNeighbor neighbor : neighbors.unscheduled()) {
      sender.sendMessage(
          Component.text(
              "    "
                  + neighbor.displayCode()
                  + " 共用 "
                  + neighbor.sharedResources()
                  + " 个资源，无已发布时刻表——它按 headway 发车，干扰单向，无法联合排布",
              NamedTextColor.YELLOW));
    }
  }

  /** 「外部冲突」一节：与已发布邻表撞上的，建议只有四种——我不能挪别人。 */
  void sendExternalConflicts(
      List<TimetableConflictChecker.Conflict> external,
      int serviceStartSecondOfDay,
      boolean relaxed) {
    if (external.isEmpty()) {
      return;
    }
    sender.sendMessage(
        Component.text("  外部冲突（目标间隔下，与已发布邻表）: " + external.size() + " 处", NamedTextColor.YELLOW));
    int shown = Math.min(external.size(), TimetableBuildResult.CONFLICT_DETAIL_LIMIT);
    for (int i = 0; i < shown; i++) {
      sender.sendMessage(
          Component.text(
              "    · "
                  + external
                      .get(i)
                      .describe(
                          seconds -> TimetableCsvExporter.clock(serviceStartSecondOfDay + seconds)),
              NamedTextColor.GRAY));
    }
    if (external.size() > shown) {
      sender.sendMessage(
          Component.text("    · … 另有 " + (external.size() - shown) + " 处", NamedTextColor.GRAY));
    }
    sender.sendMessage(
        Component.text(
            "    可做的事: "
                + (relaxed ? "目标间隔已自动放宽；" : "")
                + "加大 --separation / 换股道（改 DYNAMIC 范围或站台）/ 与对方运营方协商由其 unpublish 重编",
            NamedTextColor.DARK_GRAY));
  }

  /**
   * 落库之后那一段：保存提示与可点的后续动作。
   *
   * @param lines 本次编表涉及的线路，第一条用来拼命令前缀
   * @param saved 已落库的表，与 {@code lines} 同序
   * @param lineArg 命令里线路参数的写法（逗号分隔）
   * @param options 本次构建参数，重建命令要用
   * @param result 构建结果，决定给不给「重建」与「写回 baseline」两个按钮
   */
  void sendSavedActions(
      List<ResolvedLine> lines,
      List<Timetable> saved,
      String lineArg,
      TimetableBuildOptions options,
      TimetableBuildResult result) {
    ResolvedLine first = lines.get(0);
    String code = saved.get(0).code();
    String target =
        first.company().code() + " " + first.operator().code() + " " + lineArg + " " + code;
    sender.sendMessage(
        Component.text(
                (lines.size() > 1 ? "已保存草稿（" + lines.size() + " 张，互为基线）：" : "已保存草稿：")
                    + lineArg
                    + "/"
                    + code
                    + " ",
                NamedTextColor.DARK_AQUA)
            .append(
                CommandUx.suggestAction(
                    lines.size() > 1 ? "[整组投入运行]" : "[投入运行]",
                    "/fta timetable publish " + target,
                    lines.size() > 1
                        ? "填入 publish 命令：几张表一起重检、一起发布，任一张拒绝则整组不发"
                        : "填入 publish 命令，回车后按表运行")));
    for (ResolvedLine line : lines) {
      String single =
          first.company().code()
              + " "
              + first.operator().code()
              + " "
              + line.line().code()
              + " "
              + code;
      sender.sendMessage(
          Component.text("  " + line.line().code() + " ", NamedTextColor.GRAY)
              .append(
                  CommandUx.actions(
                      CommandUx.runAction("[详情]", "/fta timetable info " + single, "查看班次"),
                      CommandUx.runAction("[交路]", "/fta timetable duties " + single, "查看每条车辆交路"),
                      CommandUx.runAction("[导出]", "/fta timetable export " + single, "导出 CSV"))));
    }
    if (result.headwayRelaxed()) {
      sender.sendMessage(
          Component.text("  目标间隔被放宽 ", NamedTextColor.GRAY)
              .append(
                  CommandUx.suggestAction(
                      "[按放宽后的间隔重建]",
                      rebuildCommand(first, lineArg, saved.get(0), options, result),
                      "把放宽后的各组间隔填成 --group-headway 再建一次；无冲突就是可以写回配置的值")));
      for (TimetableBuildResult.GroupInterval group : result.groupIntervals()) {
        if (group.effectiveSeconds() == group.targetSeconds()) {
          continue;
        }
        int slash = group.group().indexOf('/');
        String lineCode = slash < 0 ? first.line().code() : group.group().substring(0, slash);
        String groupName = slash < 0 ? group.group() : group.group().substring(slash + 1);
        if (ServiceGroupClassifier.DEFAULT_GROUP.equals(groupName)) {
          continue;
        }
        sender.sendMessage(
            Component.text("    ", NamedTextColor.GRAY)
                .append(
                    CommandUx.suggestAction(
                        "[写回 " + group.group() + " baseline " + group.effectiveSeconds() + "s]",
                        "/fta route group set "
                            + first.company().code()
                            + " "
                            + first.operator().code()
                            + " "
                            + lineCode
                            + " "
                            + CommandUx.quoteCommandArgument(groupName)
                            + " --baseline "
                            + group.effectiveSeconds(),
                        "把这个交路组的 baselineSec 改成放宽后的间隔，下次不传 --headway 也是它")));
      }
    }
  }

  /** 按放宽后的各组间隔重建的命令：显式给出各组 --group-headway，其余只带与默认值不同的参数。 */
  private static String rebuildCommand(
      ResolvedLine first,
      String lineArg,
      Timetable timetable,
      TimetableBuildOptions options,
      TimetableBuildResult result) {
    StringBuilder command =
        new StringBuilder("/fta timetable build ")
            .append(first.company().code())
            .append(' ')
            .append(first.operator().code())
            .append(' ')
            .append(lineArg)
            .append(' ')
            .append(timetable.code());
    for (TimetableBuildResult.GroupInterval group : result.groupIntervals()) {
      command
          .append(" --group-headway ")
          .append(CommandUx.quoteCommandArgument(group.group() + "=" + group.effectiveSeconds()));
    }
    if (result.groupIntervals().isEmpty()) {
      command.append(" --headway ").append(result.effectiveHeadwaySeconds());
    }
    if (options.serviceStartSecondOfDay() != TimetableBuildOptions.DEFAULT_SERVICE_START) {
      command
          .append(" --start ")
          .append(TimetableCsvExporter.clock(options.serviceStartSecondOfDay()));
    }
    if (options.serviceEndSecondOfDay() != TimetableBuildOptions.DEFAULT_SERVICE_END) {
      command.append(" --end ").append(TimetableCsvExporter.clock(options.serviceEndSecondOfDay()));
    }
    if (options.defaultDwell().toSeconds() != TimetableBuildOptions.DEFAULT_DWELL_SECONDS) {
      command.append(" --dwell ").append(options.defaultDwell().toSeconds());
    }
    VehicleDutyPlanner.Limits limits = options.dutyLimits();
    if (limits.maxTripsPerDuty() != VehicleDutyPlanner.Limits.DEFAULT_MAX_TRIPS) {
      command.append(" --max-trips ").append(limits.maxTripsPerDuty());
    }
    if (limits.maxDutyDurationSeconds() != VehicleDutyPlanner.Limits.DEFAULT_MAX_DURATION_SECONDS) {
      command.append(" --max-duty-minutes ").append(limits.maxDutyDurationSeconds() / 60);
    }
    if (limits.turnaround().fixed()) {
      command.append(" --turnaround ").append(limits.turnaround().fallbackSeconds());
    }
    if (limits.maxIdleSeconds() != VehicleDutyPlanner.Limits.DEFAULT_MAX_IDLE_SECONDS) {
      // 重建命令带上它：缺省值来自 reclaim.max-idle-seconds，与默认常数不同的一律显式写出，免得重建换了个数。
      command.append(" --max-idle ").append(limits.maxIdleSeconds());
    }
    if (options.separation().toSeconds() != TimetableBuildOptions.DEFAULT_SEPARATION_SECONDS) {
      command.append(" --separation ").append(options.separation().toSeconds());
    }
    if (options.repair().maxWaitSeconds()
        != TimetableBuildOptions.Repair.DEFAULT_MAX_WAIT_SECONDS) {
      command.append(" --max-wait ").append(options.repair().maxWaitSeconds());
    }
    if (options.strictConflicts()) {
      command.append(" --strict");
    }
    if (!options.tripCodePrefix().isBlank()) {
      command.append(" --prefix ").append(options.tripCodePrefix());
    }
    if (!timetable.name().equals(timetable.code())) {
      command.append(" --name ").append(CommandUx.quoteCommandArgument(timetable.name()));
    }
    return command.toString();
  }
}
