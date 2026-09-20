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
      case STUB_SATURATED -> "端点排队或让车累计超限，等到能发车时交路已超上限或越过计划窗口";
    };
  }

  /** 让车一行："让车: N 处已写进表（涉及 M 班，最长 +Xs，--max-wait Ys；对邻表 K 处）"。 */
  public static String describeYields(List<ResourceRepair.Yield> yields, int maxWaitSeconds) {
    if (maxWaitSeconds <= 0) {
      return "让车: 关闭（--max-wait 0），冲突原样上报";
    }
    if (yields == null || yields.isEmpty()) {
      return "让车: 无（--max-wait " + maxWaitSeconds + "s）";
    }
    long external = yields.stream().filter(ResourceRepair.Yield::external).count();
    Map<TimetableConflictChecker.Kind, Integer> byKind = new LinkedHashMap<>();
    for (ResourceRepair.Yield yield : yields) {
      byKind.merge(yield.kind(), 1, Integer::sum);
    }
    StringBuilder kinds = new StringBuilder();
    byKind.forEach(
        (kind, count) -> {
          if (kinds.length() > 0) {
            kinds.append("、");
          }
          kinds.append(describe(kind)).append(' ').append(count);
        });
    return String.format(
        Locale.ROOT,
        "让车: %d 处已写进表（涉及 %d 班，最长 +%ds，--max-wait %ds）%s；%s",
        yields.size(),
        ResourceRepair.movedCount(yields),
        ResourceRepair.maxWait(yields),
        maxWaitSeconds,
        external > 0 ? "，其中对邻表让车 " + external + " 处" : "",
        kinds);
  }

  /** 让车明细，最多前几条："· 站台 platform:OP:S:A:1: RA-002 让 D001 +40s（05:05:00）"。 */
  public static List<String> describeYieldDetails(
      List<ResourceRepair.Yield> yields, int serviceStartSecondOfDay, int limit) {
    List<String> out = new ArrayList<>();
    if (yields == null || yields.isEmpty()) {
      return out;
    }
    int shown = Math.min(yields.size(), Math.max(0, limit));
    for (int i = 0; i < shown; i++) {
      ResourceRepair.Yield yield = yields.get(i);
      out.add(
          String.format(
              Locale.ROOT,
              "· %s %s: %s 让 %s +%ds（%s）",
              describe(yield.kind()),
              yield.resource(),
              yield.second(),
              yield.firstOwner().map(owner -> owner + " " + yield.first()).orElse(yield.first()),
              yield.waitSeconds(),
              TimetableCsvExporter.clock(serviceStartSecondOfDay + yield.atSeconds())));
    }
    if (yields.size() > shown) {
      out.add("· … 另有 " + (yields.size() - shown) + " 处");
    }
    return out;
  }

  /** 每个交路组一行：目标间隔（来源）→ 实际间隔。 */
  public static List<String> describeGroups(
      List<TimetableBuildResult.GroupInterval> intervals, Map<String, String> sources) {
    List<String> out = new ArrayList<>();
    for (TimetableBuildResult.GroupInterval interval : intervals) {
      String source = sources == null ? null : sources.get(interval.group());
      out.add(
          "交路组 "
              + interval.group()
              + ": 每方向 "
              + interval.targetSeconds()
              + "s"
              + (source == null ? "" : "（" + source + "）")
              + (interval.effectiveSeconds() != interval.targetSeconds()
                  ? " → 放宽到 " + interval.effectiveSeconds() + "s"
                  : ""));
    }
    return out;
  }

  /** 共用起点上的合成间隔：大小交路交错得好不好，看这一行。 */
  public static List<String> describeInterleaves(List<PhasePlanner.Interleave> interleaves) {
    List<String> out = new ArrayList<>();
    for (PhasePlanner.Interleave interleave : interleaves) {
      out.add(
          String.format(
              Locale.ROOT,
              "合成间隔 %s: %d 班，相邻 min %ds / med %ds / max %ds",
              interleave.originGroup(),
              interleave.departures(),
              interleave.minGap(),
              interleave.medianGap(),
              interleave.maxGap()));
    }
    return out;
  }

  /** 交路形状："跑 N 班的交路 M 条"，出入库班配得多不多看这一行。 */
  public static String describeDutyShapes(List<TimetableBuildResult.DutyShape> shapes) {
    if (shapes == null || shapes.isEmpty()) {
      return "交路形状: 无";
    }
    StringBuilder text = new StringBuilder("交路形状: ");
    for (int i = 0; i < shapes.size(); i++) {
      if (i > 0) {
        text.append("，");
      }
      text.append(shapes.get(i).trips())
          .append(" 班 × ")
          .append(shapes.get(i).duties())
          .append(" 条");
    }
    return text.toString();
  }

  /**
   * 「结构下界」与「端点串行」两行；没有容量 1 的端点时为空。
   *
   * <p>折返秒数<b>从该端点的占用成本里反推</b>而不是另外传一个数：打印出来的必须就是算下界时用的那个， 否则报告会和它自己解释的算式对不上。来源（终到站 dwell 还是 {@code
   * --turnaround} 覆盖）由 {@code turnarounds} 说明。
   */
  public static List<String> describeTerminals(
      List<TerminalSerializer.TerminalReport> terminals,
      List<TimetableBuildResult.TripShift> shifts,
      TurnaroundTable turnarounds) {
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
              "结构下界: %s（单股道）每次折返占用 ≥ %ds（进站 %d + 折返 %d + 出站 %d + 裕量 %d，折返取 %s），按权重每 %d 班经过 %.1f 次 → 全线间隔下界 %ds",
              terminal.group(),
              terminal.visitCostSeconds(),
              terminal.approachInSeconds(),
              turnaroundOf(terminal),
              terminal.approachOutSeconds(),
              terminal.separationSeconds(),
              turnarounds.describe(),
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

  /** 从端点占用成本里反推本端点用的折返秒数：成本 = 进站 + 折返 + 出站 + 裕量。 */
  private static int turnaroundOf(TerminalSerializer.TerminalReport terminal) {
    return Math.max(
        0,
        terminal.visitCostSeconds()
            - terminal.approachInSeconds()
            - terminal.approachOutSeconds()
            - terminal.separationSeconds());
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
      TurnaroundTable turnarounds) {
    for (TerminalSerializer.TerminalReport terminal : terminals) {
      if (terminal.utilization() > 1.0D) {
        return String.format(
            Locale.ROOT,
            "目标间隔 %ds 下端点 %s（单股道）利用率 %.0f%%（每次折返占用 %ds，折返取 %s）：目标本身在结构上不可能；放宽到 %ds 仍找不到无冲突的间隔。可做的事：核对终到站的 dwell 是否偏大或 --turnaround 是否设得太长、降低经过该端点的 route 权重、或增加股道",
            targetHeadway,
            terminal.group(),
            terminal.utilization() * 100.0D,
            terminal.visitCostSeconds(),
            turnarounds.describe(),
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
