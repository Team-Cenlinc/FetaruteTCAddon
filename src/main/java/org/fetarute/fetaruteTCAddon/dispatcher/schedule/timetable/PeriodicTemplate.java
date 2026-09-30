package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 一个周期流在各资源上的一周期占用模板。
 *
 * <h2>什么是周期流</h2>
 *
 * <p>一个方向每隔 {@code interval} 秒发一班，这一班在沿线各资源上的占用是固定形状，只是整体平移。 把它当成"模板 +
 * 相位"，两条周期流会不会撞就只取决于<b>相对相位</b>——这正是第三层要选的那个量。
 *
 * <p>单班交路（出库班跑完一趟就回库）的周期流不止班次本身：车到终点折返之后 紧接着走回库线路，那段走行同样每周期出现一次，而且往往和别的流共用车库咽喉， "出库流 ×
 * 回库流"正是在那里相撞。所以回库段必须进模板，否则第三层看不见它。
 *
 * <h2>为什么模板里一个方向会轮换 route</h2>
 *
 * <p>同一方向可以有多条 route 按 weight 切份额（SWRR），它们的路径不同、占用的资源也不同。模板按周期轮换 候选 route（第 c 个周期用第 {@code c % n}
 * 条），比只取一条代表更接近真实铺法，且完全确定。
 *
 * @param key 方向键
 * @param intervalSeconds 该方向的发车间隔
 * @param routeIds 本方向的候选 route，按周期轮换
 * @param tailByRoute 每条 route 跑完之后紧接的回库走行（折返秒数 + 回库线路）；没有就不在表里
 */
public record PeriodicTemplate(
    String key, int intervalSeconds, List<UUID> routeIds, Map<UUID, Tail> tailByRoute) {

  /**
   * 班次之后紧接的回库走行。
   *
   * @param routeId 回库线路
   * @param afterSeconds 班次发车后多久发出（= 全程时分 + 折返）
   */
  public record Tail(UUID routeId, int afterSeconds) {
    public Tail {
      Objects.requireNonNull(routeId, "routeId");
      afterSeconds = Math.max(0, afterSeconds);
    }
  }

  public PeriodicTemplate {
    key = key == null ? "" : key;
    intervalSeconds = Math.max(1, intervalSeconds);
    routeIds = routeIds == null ? List.of() : List.copyOf(routeIds);
    tailByRoute = tailByRoute == null ? Map.of() : Map.copyOf(tailByRoute);
  }

  /**
   * 从方向与投影建模板。
   *
   * @param direction 方向
   * @param intervalSeconds 该方向的间隔
   * @param runSecondsByRoute 各 route 的全程时分
   * @param legs 出库/回库走行段
   * @param turnarounds 折返表
   * @param terminalNodeByRoute 各 route 的终到节点：用来找它的回库线路
   * @param singleTripDuty 这个方向的交路是不是跑一班就回库；false 时模板只含班次
   */
  public static PeriodicTemplate of(
      ServiceGroupClassifier.Direction direction,
      int intervalSeconds,
      Map<UUID, Integer> runSecondsByRoute,
      VehicleDutyPlanner.Legs legs,
      TurnaroundTable turnarounds,
      Map<UUID, String> terminalNodeByRoute,
      boolean singleTripDuty) {
    List<UUID> routes = List.copyOf(direction.routeIds());
    Map<UUID, Tail> tails = new java.util.LinkedHashMap<>();
    if (singleTripDuty && legs != null) {
      for (UUID routeId : routes) {
        String terminal = terminalNodeByRoute == null ? null : terminalNodeByRoute.get(routeId);
        if (terminal == null) {
          continue;
        }
        legs.returnLegAt(terminal)
            .ifPresent(
                leg -> {
                  int run = runSecondsByRoute.getOrDefault(routeId, 0);
                  int turnaround = turnarounds == null ? 0 : turnarounds.secondsFor(routeId);
                  tails.put(routeId, new Tail(leg.routeId(), run + turnaround));
                });
      }
    }
    return new PeriodicTemplate(direction.key(), intervalSeconds, routes, tails);
  }

  /**
   * 铺 {@code cycles} 个周期、整体加 {@code phase} 之后的占用。
   *
   * <p>code 带方向键与周期序号，保证两条流的 code 永不相同——冲突检查靠 code 判"是不是同一辆车"。
   *
   * @param phase 相位
   * @param cycles 铺几个周期
   */
  public List<TimetableConflictChecker.Movement> unroll(int phase, int cycles) {
    if (routeIds.isEmpty()) {
      return List.of();
    }
    List<TimetableConflictChecker.Movement> out = new ArrayList<>(cycles * 2);
    for (int cycle = 0; cycle < Math.max(1, cycles); cycle++) {
      UUID routeId = routeIds.get(cycle % routeIds.size());
      int start = phase + cycle * intervalSeconds;
      String code = String.format(Locale.ROOT, "%s#%d", key, cycle);
      out.add(new TimetableConflictChecker.Movement(code, routeId, start));
      Tail tail = tailByRoute.get(routeId);
      if (tail != null) {
        // 回库走行和它的班次是同一辆车：共用 code，冲突检查才不会把它们判成两辆车互撞。
        out.add(
            new TimetableConflictChecker.Movement(
                code, tail.routeId(), start + tail.afterSeconds()));
      }
    }
    return List.copyOf(out);
  }
}
