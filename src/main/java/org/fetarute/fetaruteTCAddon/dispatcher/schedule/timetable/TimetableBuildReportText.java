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
    };
  }
}
