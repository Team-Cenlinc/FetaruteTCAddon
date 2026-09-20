package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 构建报告里的文案：冲突摘要与明细、取消班次的归组说明。
 *
 * <p>从 {@link TimetableBuilder} 抽出来只有一个理由：builder 要回答"表是什么"，不该同时负责"怎么把它说给人听"。 时刻一律走 {@link
 * TimetableCsvExporter#clock}，跨零点带 +1、早于零点带 -1。
 */
public final class TimetableBuildReportText {

  /** 取消班次的警告里最多列出多少个时刻。 */
  static final int DROPPED_TIMES_IN_WARNING = 6;

  private TimetableBuildReportText() {}

  /** "目标间隔 300s 有 N 处冲突（区间 x、站台 y）"。 */
  public static String summarizeConflicts(
      TimetableConflictChecker.Report report, int targetHeadway) {
    StringBuilder kinds = new StringBuilder();
    report
        .countByKind()
        .forEach(
            (kind, count) -> {
              if (kinds.length() > 0) {
                kinds.append("、");
              }
              kinds.append(describe(kind)).append(' ').append(count);
            });
    return "目标间隔 " + targetHeadway + "s 有 " + report.conflicts().size() + " 处冲突（" + kinds + "）";
  }

  /** 冲突明细，最多列前几条；相对秒数换算成当日时钟。 */
  public static List<String> describeConflicts(
      TimetableConflictChecker.Report report, int serviceStartSecondOfDay) {
    List<String> out = new ArrayList<>();
    int shown = Math.min(report.conflicts().size(), TimetableBuildResult.CONFLICT_DETAIL_LIMIT);
    for (int i = 0; i < shown; i++) {
      out.add(
          "  · "
              + report
                  .conflicts()
                  .get(i)
                  .describe(
                      seconds -> TimetableCsvExporter.clock(serviceStartSecondOfDay + seconds)));
    }
    if (report.conflicts().size() > shown) {
      out.add("  · … 另有 " + (report.conflicts().size() - shown) + " 处");
    }
    return out;
  }

  /** 取消的班次按 route + 原因归组，每组列出前几个时刻。 */
  public static List<String> describeDropped(List<TimetableBuildResult.DroppedTrip> dropped) {
    Map<String, List<TimetableBuildResult.DroppedTrip>> groups = new LinkedHashMap<>();
    for (TimetableBuildResult.DroppedTrip trip : dropped) {
      groups
          .computeIfAbsent(trip.routeCode() + "|" + trip.reason(), key -> new ArrayList<>())
          .add(trip);
    }
    List<String> out = new ArrayList<>(groups.size());
    for (List<TimetableBuildResult.DroppedTrip> group : groups.values()) {
      TimetableBuildResult.DroppedTrip first = group.get(0);
      StringBuilder times = new StringBuilder();
      for (int i = 0; i < Math.min(group.size(), DROPPED_TIMES_IN_WARNING); i++) {
        if (i > 0) {
          times.append(", ");
        }
        times.append(group.get(i).departureText());
      }
      if (group.size() > DROPPED_TIMES_IN_WARNING) {
        times.append(" …");
      }
      out.add(
          String.format(
              Locale.ROOT,
              "route %s 取消 %d 班（%s）: %s",
              first.routeCode(),
              group.size(),
              describe(first.reason()),
              times));
    }
    return out;
  }

  public static String describe(TimetableConflictChecker.Kind kind) {
    return switch (kind) {
      case TRACK -> "区间";
      case PLATFORM -> "站台";
      case SINGLE_LINE -> "单线对向";
      case JUNCTION -> "道岔";
    };
  }

  public static String describe(VehicleDutyPlanner.UnassignedReason reason) {
    return switch (reason) {
      case NO_CREATE_ACCESS -> "起点没有 CREATE 线路，也没有接得上的待命车";
      case NO_RETURN_ACCESS -> "终点没有 RETURN 线路，后面也接不上能回库的班次";
      case EXCEEDS_DUTY_LIMITS -> "单独一班连同出库、回库走行就超过交路时长上限";
      case STUB_SATURATED -> "单股道端点排队过长，等到端点空出来交路已超上限或越过计划窗口";
    };
  }

  /** 「结构下界」与「端点串行」两行；没有容量 1 的端点时为空。 */
  public static List<String> describeTerminals(
      List<TerminalSerializer.TerminalReport> terminals,
      List<TimetableBuildResult.TripShift> shifts,
      int turnaroundSeconds) {
    List<String> out = new ArrayList<>();
    if (terminals == null || terminals.isEmpty()) {
      return out;
    }
    long delayed =
        shifts.stream().filter(s -> s.actualSecondOfDay() > s.nominalSecondOfDay()).count();
    long advanced = shifts.size() - delayed;
    int maxDelay =
        shifts.stream()
            .mapToInt(s -> s.actualSecondOfDay() - s.nominalSecondOfDay())
            .max()
            .orElse(0);
    for (TerminalSerializer.TerminalReport terminal : terminals) {
      out.add(
          String.format(
              Locale.ROOT,
              "结构下界: %s（单股道）每次折返占用 ≥ %ds（进站 %d + 折返 %d + 出站 %d + 裕量 %d，按 --turnaround %d 计），按权重每 %d 班经过 %.1f 次 → 全线间隔下界 %ds",
              terminal.group(),
              terminal.visitCostSeconds(),
              terminal.approachInSeconds(),
              turnaroundSeconds,
              terminal.approachOutSeconds(),
              terminal.separationSeconds(),
              turnaroundSeconds,
              terminal.cycleTrips(),
              terminal.visitsPerCycle(),
              terminal.headwayFloorSeconds()));
      out.add(
          String.format(
              Locale.ROOT,
              "端点串行: %s 经过 %d 次、占用 %.0f%%%s；%d 班偏离网格（延后 %d / 提前 %d，最大 +%ds）；无处等待 %d 班；截断 %d 班",
              terminal.group(),
              terminal.visits(),
              terminal.utilization() * 100.0D,
              terminal.utilization() > 1.0D ? "（超过 100%，目标间隔本身在结构上不可能）" : "",
              shifts.size(),
              delayed,
              advanced,
              Math.max(0, maxDelay),
              terminal.nowhereToWait(),
              terminal.truncated()));
    }
    return out;
  }

  /**
   * 搜索失败的文案：端点利用率超过 100% 时说"结构上不可能"，否则说"范围内没找到"并点名剩余冲突最多的资源。
   * 两种情况下运营者要做的事不同：前者改折返/权重/股道，后者改裕量或等下一轮把咽喉也串行。
   */
  public static String describeSearchFailure(
      int targetHeadway,
      int limitHeadway,
      TimetableConflictChecker.Report conflictsAtTarget,
      List<TerminalSerializer.TerminalReport> terminals,
      int turnaroundSeconds) {
    for (TerminalSerializer.TerminalReport terminal : terminals) {
      if (terminal.utilization() > 1.0D) {
        return String.format(
            Locale.ROOT,
            "目标间隔 %ds 下端点 %s（单股道）利用率 %.0f%%（每次折返占用 %ds，按 --turnaround %d 计）：目标本身在结构上不可能；放宽到 %ds 仍找不到无冲突的间隔。可做的事：核对 --turnaround 是否远大于终到站的 dwell、降低经过该端点的 route 权重、或增加股道",
            targetHeadway,
            terminal.group(),
            terminal.utilization() * 100.0D,
            terminal.visitCostSeconds(),
            turnaroundSeconds,
            limitHeadway);
      }
    }
    return "目标间隔 "
        + targetHeadway
        + "s 在放宽到 "
        + limitHeadway
        + "s 的范围内没有找到无冲突的间隔；剩余冲突集中在 "
        + String.join("、", conflictsAtTarget.topResources(3))
        + "。检查单线区段、站台数量与折返时间";
  }
}
