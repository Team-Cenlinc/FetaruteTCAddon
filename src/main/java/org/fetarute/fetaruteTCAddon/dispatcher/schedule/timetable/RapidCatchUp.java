package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 成品表上的快车被卡：快车跟在慢车后面进同一段无越行共线，被一路拖住多少（{@link CorridorCatchUp}），以及表里写给快车的让车等待。
 *
 * <p>只量不改：快车被卡是周期层求解（{@link PeriodicSolver}）的目标项，它在相位与途中多停里直接权衡快慢车（允许慢车待避越行）；
 * 这里在成品表上按运行时的跟车规则复核一遍，写进报告。
 */
final class RapidCatchUp {

  private RapidCatchUp() {}

  /**
   * 成品表上的快车被卡情况。
   *
   * @param seconds 被拖住、超过一个裕量的秒数合计
   * @param trips 被拖住的班次数
   * @param caught 逐条明细
   * @param held 表里写给快车的让车等待合计：被卡改成在表里等，快车照样慢了这么多
   */
  record Measure(long seconds, int trips, List<CorridorCatchUp.Caught> caught, long held) {
    Measure {
      caught = List.copyOf(caught);
    }

    /** 快车损失：被卡加表里的等待。 */
    long lost() {
      return seconds + held;
    }
  }

  /**
   * 量一张成品表：只算快车交路的班次被拖住。
   *
   * <p>投影里还有出库、回库走行，它们不停站，在共线段跟着慢车也会被"拖住"，但那不是快车——算进来会让报告点名走行线路、
   * 把出入库当成要优化的快车。挡在前面的车则什么都算：走行挡住快车也是挡。
   *
   * @param table 成品表
   * @param profiles 交路投影
   * @param index 图索引
   * @param separation 裕量
   * @param following 跟车规则：启用时被拖住按闭塞时间算（与运行时放行同一口径），否则按占用区间
   * @param trajectories 闭塞时间用的逐点轨迹
   * @param zeroSecondOfDay 计划窗口起点（日内秒）
   * @param fastRoutes 快车交路（{@link #fastRoutes}）
   * @param yields 表里写的让车（量快车自己的等待）
   */
  static Measure measure(
      Timetable table,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      TimetableConflictChecker.GraphIndex index,
      int separation,
      TimetableBuildOptions.Following following,
      BlockingTimes.Trajectories trajectories,
      int zeroSecondOfDay,
      Set<UUID> fastRoutes,
      List<TripYield> yields) {
    CorridorCatchUp corridors =
        new CorridorCatchUp(profiles, index, separation, following, trajectories);
    List<CorridorCatchUp.Caught> caught =
        corridors
            .caught(
                TimetableOccupancyProjector.project(table, profiles, zeroSecondOfDay).movements())
            .stream()
            .filter(one -> fastRoutes.contains(one.routeId()))
            .toList();
    long seconds = 0L;
    Set<String> trips = new TreeSet<>();
    for (CorridorCatchUp.Caught one : caught) {
      seconds += one.seconds();
      trips.add(one.code());
    }
    Set<String> fastTrips = new HashSet<>();
    for (TimetableTrip trip : table.trips()) {
      if (fastRoutes.contains(trip.routeId())) {
        fastTrips.add(trip.tripCode());
      }
    }
    long held =
        yields.stream()
            .filter(yield -> fastTrips.contains(yield.second()))
            .mapToLong(TripYield::waitSeconds)
            .sum();
    return new Measure(seconds, trips.size(), caught, held);
  }

  /** 实测的合计，交给构建结果。 */
  static TimetableBuildResult.CatchUp total(Measure measure) {
    return new TimetableBuildResult.CatchUp(measure.seconds(), measure.trips(), measure.held());
  }

  /** 快车交路：排进发车表的运营交路里，某段共线上比另一条明显更快（快出一个裕量以上）的。 */
  static Set<UUID> fastRoutes(
      List<ServiceGroupClassifier.Group> groups,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      TimetableConflictChecker.GraphIndex index,
      int separation) {
    Set<UUID> routes = new TreeSet<>();
    for (ServiceGroupClassifier.Group group : groups) {
      for (ServiceGroupClassifier.Direction direction : group.directions()) {
        routes.addAll(direction.routeIds());
      }
    }
    return new CorridorCatchUp(profiles, index, separation).fasterRoutes(routes);
  }

  /** 把实测写进构建结果：报告一行说明，加上结构化的合计。 */
  static TimetableBuildResult annotate(
      TimetableBuildResult result, Measure measure, Map<UUID, String> routeCodes) {
    return result.withRapidCatchUp(total(measure)).withPhaseNote(describe(measure, routeCodes));
  }

  /** 报告一行：成品表上快车被卡的情况，按快车交路分开，点名拖住它最多的慢车；表里另有快车等待时一并写出。 */
  static String describe(Measure measure, Map<UUID, String> routeCodes) {
    String held = measure.held() > 0L ? "；表里另有快车让车等待共 " + measure.held() + "s" : "";
    if (measure.seconds() == 0L) {
      return "快车被卡（成品表实测）：无" + held;
    }
    Map<String, Long> byRoute = new TreeMap<>();
    Map<String, Set<String>> tripsByRoute = new HashMap<>();
    Map<String, Map<String, Long>> blockers = new HashMap<>();
    for (CorridorCatchUp.Caught one : measure.caught()) {
      String code = routeCodes.getOrDefault(one.routeId(), one.routeId().toString());
      byRoute.merge(code, (long) one.seconds(), Long::sum);
      tripsByRoute.computeIfAbsent(code, key -> new TreeSet<>()).add(one.code());
      blockers
          .computeIfAbsent(code, key -> new TreeMap<>())
          .merge(
              routeCodes.getOrDefault(one.blockingRouteId(), one.blockingRouteId().toString()),
              (long) one.seconds(),
              Long::sum);
    }
    List<String> parts = new ArrayList<>();
    byRoute.forEach(
        (code, seconds) -> {
          String blocker =
              blockers.get(code).entrySet().stream()
                  .max(Map.Entry.<String, Long>comparingByValue())
                  .map(Map.Entry::getKey)
                  .orElse("-");
          parts.add(
              String.format(
                  Locale.ROOT,
                  "%s %d 班、共 %ds（主要被 %s 拖住）",
                  code,
                  tripsByRoute.get(code).size(),
                  seconds,
                  blocker));
        });
    return "快车被卡（成品表实测）：" + String.join("；", parts) + held;
  }
}
