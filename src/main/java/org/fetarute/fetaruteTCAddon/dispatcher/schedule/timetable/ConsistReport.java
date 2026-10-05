package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;

/**
 * 区分车型时的编表报告：各车型车长、各车型时分、车型配比、按车型的交路与峰值。
 *
 * <p>只读成品表（编表内部形态：班次挂车型变体），不改任何东西。偏离目标的配比与超出出车上限的峰值写进警告。
 */
final class ConsistReport {

  /** 车型配比偏离目标超过多少个百分点时警告，与 route 份额同一阈值。 */
  static final double SHARE_WARN_PERCENT_POINTS = 5.0D;

  private ConsistReport() {}

  /**
   * 生成报告行。
   *
   * @param fleet 车型
   * @param operationPlans 运营 route 的基础计划
   * @param table 成品表（班次挂车型变体）
   * @param profiles 各 route（含变体）的冲突档案
   * @param nodeTypes 节点类型
   * @param warnings 警告追加到这里
   * @return 报告行；不区分车型时为空
   */
  static List<String> notes(
      ConsistFleet fleet,
      List<TimetableRoutePlan> operationPlans,
      Timetable table,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      Map<NodeId, NodeType> nodeTypes,
      List<String> warnings) {
    warnings.addAll(fleet.issues());
    if (!fleet.active()) {
      return List.of();
    }
    warnTerminalFouling(table, profiles, nodeTypes, warnings);
    List<String> notes = new ArrayList<>();
    notes.add(describeFleet(fleet));

    // 基础 route → 车型 → 变体计划
    Map<UUID, Map<String, TimetableRoutePlan>> variants = new HashMap<>();
    for (TimetableRoutePlan plan : table.routePlans()) {
      plan.consist()
          .ifPresent(
              variant ->
                  variants
                      .computeIfAbsent(variant.baseRouteId(), id -> new TreeMap<>())
                      .put(variant.key(), plan));
    }
    // 基础 route → 车型 → 实际班次
    Map<UUID, Map<String, Integer>> trips = new HashMap<>();
    for (TimetableTrip trip : table.trips()) {
      Optional<String> consist = table.consistOf(trip);
      Optional<TimetableRoutePlan> plan = table.routePlan(trip.routeId());
      if (consist.isEmpty() || plan.isEmpty()) {
        continue;
      }
      trips
          .computeIfAbsent(plan.get().baseRouteId(), id -> new HashMap<>())
          .merge(consist.get(), 1, Integer::sum);
    }
    List<TimetableRoutePlan> sorted = new ArrayList<>(operationPlans);
    sorted.sort(java.util.Comparator.comparing(TimetableRoutePlan::routeCode));
    for (TimetableRoutePlan base : sorted) {
      Map<String, TimetableRoutePlan> byConsist = variants.getOrDefault(base.routeId(), Map.of());
      if (byConsist.size() > 1) {
        notes.add(describeTiming(base, byConsist));
      }
      String shares =
          describeShares(
              base,
              fleet.sharesFor(base.routeId()),
              byConsist,
              trips.getOrDefault(base.routeId(), Map.of()),
              warnings);
      if (!shares.isEmpty()) {
        notes.add(shares);
      }
    }
    notes.add(describeVehicles(fleet, table.duties(), warnings));
    return List.copyOf(notes);
  }

  /**
   * 车型变体在终点停着时车尾压住的道岔：只报比最短车型多出来的那一截压到的（最短车型压不到、这个车型压得到）。
   *
   * <p>冲突检查里车尾出清只在运行中生效（{@code TailClock}）；终点待命按站台占用记，不含站台外的道岔，这几处的占用因此不进冲突检查。
   */
  private static void warnTerminalFouling(
      Timetable table,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      Map<NodeId, NodeType> nodeTypes,
      List<String> warnings) {
    for (TimetableRoutePlan plan : table.routePlans()) {
      Optional<TimetableRoutePlan.ConsistVariant> variant = plan.consist();
      if (variant.isEmpty() || variant.get().tailBlocks() <= 0.0) {
        continue;
      }
      TimetableConflictChecker.RouteProfile profile = profiles.get(plan.routeId());
      if (profile == null) {
        continue;
      }
      double length = variant.get().lengthBlocks();
      double shortest = length - variant.get().tailBlocks();
      List<String> fouled = new ArrayList<>();
      double distance = 0.0D;
      List<TimetableTimingCalculator.SegmentTiming> segments = profile.segments();
      walk:
      for (int i = segments.size() - 1; i >= 0; i--) {
        TimetableTimingCalculator.SegmentTiming segment = segments.get(i);
        for (int k = segment.nodes().size() - 1; k > 0; k--) {
          distance += segment.edges().get(k - 1).lengthBlocks();
          if (distance >= length) {
            break walk;
          }
          NodeId node = segment.nodes().get(k - 1);
          if (distance > shortest && nodeTypes.get(node) == NodeType.SWITCHER) {
            fouled.add(String.format(Locale.ROOT, "%s（距停车点 %.0f 格）", node.value(), distance));
          }
        }
      }
      if (!fouled.isEmpty()) {
        warnings.add(
            "车型 "
                + variant.get().key()
                + " 在 "
                + plan.routeCode()
                + " 终点停着时车尾压着道岔 "
                + String.join("、", fouled)
                + "（最短车型压不到）：待命期间这些道岔的占用不进冲突检查，站台需按最长车型布置");
      }
    }
  }

  private static String describeFleet(ConsistFleet fleet) {
    List<String> parts = new ArrayList<>();
    for (String key : fleet.keys()) {
      ConsistFleet.Consist consist = fleet.consists().get(key);
      double tail = fleet.tailBlocks(key);
      parts.add(
          String.format(Locale.ROOT, "%s 车长 %.1f 格", consist.pattern(), consist.lengthBlocks())
              + (tail > 0.0 ? String.format(Locale.ROOT, "（车尾多算 %.1f 格）", tail) : ""));
    }
    return "车型：" + String.join("；", parts);
  }

  private static String describeTiming(
      TimetableRoutePlan base, Map<String, TimetableRoutePlan> byConsist) {
    List<String> parts = new ArrayList<>();
    String slowest = null;
    int slowestRun = -1;
    for (Map.Entry<String, TimetableRoutePlan> entry : byConsist.entrySet()) {
      int run = entry.getValue().totalRunSeconds();
      parts.add(entry.getKey() + " " + run + " s");
      if (run > slowestRun) {
        slowestRun = run;
        slowest = entry.getKey();
      }
    }
    return "各车型时分：" + base.routeCode() + " " + String.join("、", parts) + "（往返锚定按 " + slowest + "）";
  }

  private static String describeShares(
      TimetableRoutePlan base,
      List<ConsistFleet.Share> shares,
      Map<String, TimetableRoutePlan> byConsist,
      Map<String, Integer> trips,
      List<String> warnings) {
    List<ConsistFleet.Share> usable = new ArrayList<>();
    int totalWeight = 0;
    for (ConsistFleet.Share share : shares) {
      if (byConsist.containsKey(share.key())) {
        usable.add(share);
        totalWeight += share.weight();
      }
    }
    int totalTrips = trips.values().stream().mapToInt(Integer::intValue).sum();
    if (usable.isEmpty() || totalTrips == 0) {
      return "";
    }
    List<String> parts = new ArrayList<>();
    double worst = 0.0D;
    for (ConsistFleet.Share share : usable) {
      int count = trips.getOrDefault(share.key(), 0);
      double target = 100.0D * share.weight() / totalWeight;
      double actual = 100.0D * count / totalTrips;
      worst = Math.max(worst, Math.abs(actual - target));
      parts.add(
          String.format(
              Locale.ROOT, "%s 目标 %.0f%% 实际 %.0f%%（%d 班）", share.key(), target, actual, count));
    }
    String line = "车型配比：" + base.routeCode() + " " + String.join("、", parts);
    if (worst > SHARE_WARN_PERCENT_POINTS) {
      warnings.add(
          String.format(
              Locale.ROOT,
              "%s 的车型配比偏离目标 %.0f 个百分点：车型配比排在车辆周转之后，接续结构或出入库线路限制了车型（%s）",
              base.routeCode(),
              worst,
              String.join("、", parts)));
    }
    return line;
  }

  private static String describeVehicles(
      ConsistFleet fleet, List<VehicleDuty> duties, List<String> warnings) {
    Map<String, List<VehicleDuty>> byConsist = new LinkedHashMap<>();
    for (String key : fleet.keys()) {
      byConsist.put(key, new ArrayList<>());
    }
    for (VehicleDuty duty : duties) {
      duty.consist()
          .ifPresent(key -> byConsist.computeIfAbsent(key, k -> new ArrayList<>()).add(duty));
    }
    List<String> parts = new ArrayList<>();
    byConsist.forEach(
        (key, list) -> {
          int peak =
              new VehicleDutyPlanner.Result(list, List.of(), list.size(), List.of())
                  .peakConcurrentVehicles();
          parts.add(key + " 交路 " + list.size() + "、峰值同时在线 " + peak);
          ConsistFleet.Consist consist = fleet.consists().get(key);
          if (consist != null
              && consist.spawnLimit().isPresent()
              && peak > consist.spawnLimit().getAsInt()) {
            warnings.add(
                "车型 "
                    + key
                    + " 峰值同时在线 "
                    + peak
                    + " 列，超过存车的出车上限 "
                    + consist.spawnLimit().getAsInt()
                    + " 列：运行时到上限后这个车型出不了车");
          }
        });
    return "按车型：" + String.join("；", parts);
  }
}
