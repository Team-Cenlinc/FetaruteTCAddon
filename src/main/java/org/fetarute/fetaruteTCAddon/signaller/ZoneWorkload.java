package org.fetarute.fetaruteTCAddon.signaller;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.DynamicStopMatcher;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRoutePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTrip;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardArea;

/**
 * 中心工位切小区时各单元的工作量（见 {@link ZoneSplitter}）。只求切得大致均匀，不追求精确。本类不依赖服务器对象。
 *
 * <ul>
 *   <li>按已发布的时刻表，窗口内在单元里停站或通过的车次数（一个车次在一个单元里只算一次，取消的也算），再加道岔数 × 折算系数；
 *   <li>停靠点按调度图节点认；DYNAMIC 写法按写法里的车站、车库认；
 *   <li>窗口内整个大区一个表定车次都没有（没有时刻表的线路、凌晨空窗）时，车次数换成单元里的站台股道与库线数。
 * </ul>
 */
final class ZoneWorkload {

  private ZoneWorkload() {}

  /**
   * 算各单元的工作量。
   *
   * @param units 单元
   * @param operator 运营商代码（认 DYNAMIC 写法用）
   * @param timetables 已发布的时刻表
   * @param from 窗口起点
   * @param window 窗口长度
   * @param switchWeight 每个道岔折算的车次
   * @return 与 {@code units} 一一对应
   */
  static double[] of(
      List<ZoneUnits.Unit> units,
      String operator,
      List<Timetable> timetables,
      Instant from,
      Duration window,
      double switchWeight) {
    int[] calls = new int[units.size()];
    Instant to = from.plus(window);
    int total = 0;
    for (Timetable timetable : timetables) {
      LocalDate first = LocalDate.ofInstant(from, timetable.zoneId()).minusDays(1);
      LocalDate last = LocalDate.ofInstant(to, timetable.zoneId());
      for (TimetableTrip trip : timetable.trips()) {
        Optional<TimetableRoutePlan> plan = timetable.tripPlan(trip);
        if (plan.isEmpty()) {
          continue;
        }
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
          Instant start = trip.departureAt(date, timetable.zoneId());
          total += count(units, operator, plan.get().stops(), start, from, to, calls);
        }
      }
    }
    double[] out = new double[units.size()];
    for (int i = 0; i < units.size(); i++) {
      ZoneUnits.Unit unit = units.get(i);
      int base = total > 0 ? calls[i] : unit.tracks();
      out[i] = base + switchWeight * unit.switches();
    }
    return out;
  }

  /** 一个车次的一次发车：在窗口内经过的单元各记一次。返回记了几次。 */
  private static int count(
      List<ZoneUnits.Unit> units,
      String operator,
      List<TimetableStop> stops,
      Instant start,
      Instant from,
      Instant to,
      int[] calls) {
    boolean[] hit = new boolean[units.size()];
    int counted = 0;
    for (TimetableStop stop : stops) {
      Instant arrival = start.plusSeconds(stop.arrivalOffsetSeconds());
      Instant departure = start.plusSeconds(stop.departureOffsetSeconds());
      // 到站到发车这段与窗口有重叠
      if (!arrival.isBefore(to) || departure.isBefore(from)) {
        continue;
      }
      for (int i = 0; i < units.size(); i++) {
        if (!hit[i] && matches(units.get(i), operator, stop)) {
          hit[i] = true;
          calls[i]++;
          counted++;
        }
      }
    }
    return counted;
  }

  /** 停靠点在不在单元里：按节点认；DYNAMIC 写法按写法里的车站、车库认。 */
  private static boolean matches(ZoneUnits.Unit unit, String operator, TimetableStop stop) {
    String node = stop.nodeId().orElse(null);
    if (node == null) {
      return false;
    }
    Optional<DynamicStopMatcher.DynamicSpec> spec =
        node.regionMatches(true, 0, "DYNAMIC:", 0, "DYNAMIC:".length())
            ? DynamicStopMatcher.parseDynamicSpec(node)
            : Optional.empty();
    if (spec.isEmpty()) {
      return unit.nodes().contains(NodeId.of(node));
    }
    if (!spec.get().operatorCode().equalsIgnoreCase(operator)) {
      return false;
    }
    YardArea.Member member =
        "D".equals(spec.get().nodeType())
            ? YardArea.Member.depot(spec.get().nodeName())
            : YardArea.Member.station(spec.get().nodeName());
    return unit.members().contains(member);
  }
}
