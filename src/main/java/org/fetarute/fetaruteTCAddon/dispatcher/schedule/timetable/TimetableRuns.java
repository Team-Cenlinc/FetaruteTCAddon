package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 已发布时刻表在一段时间附近的各趟车次：逐趟给出停靠表与这一趟的发车时刻。按工作量分工（信号员中心工位、站长分股道）共用。本类不依赖服务器对象。
 *
 * <p>从起点所在服务日的前一天（前一天发车、跨零点到站的车次）到终点所在服务日，每个服务日每个车次各给一趟；车次是否真在窗口内经过某处，由调用方按停靠时刻判。
 */
public final class TimetableRuns {

  /** 一趟车次。 */
  @FunctionalInterface
  public interface Visitor {

    /**
     * @param stops 车次的停靠表
     * @param start 这一趟的发车时刻；各停靠的到发时刻按它加偏移
     */
    void visit(List<TimetableStop> stops, Instant start);
  }

  private TimetableRuns() {}

  /**
   * 逐趟访问 {@code [from, to]} 附近的车次。
   *
   * @param timetables 已发布的时刻表
   * @param from 窗口起点
   * @param to 窗口终点
   * @param visitor 每一趟调一次
   */
  public static void forEach(
      List<Timetable> timetables, Instant from, Instant to, Visitor visitor) {
    for (Timetable timetable : timetables) {
      LocalDate first = LocalDate.ofInstant(from, timetable.zoneId()).minusDays(1);
      LocalDate last = LocalDate.ofInstant(to, timetable.zoneId());
      for (TimetableTrip trip : timetable.trips()) {
        Optional<TimetableRoutePlan> plan = timetable.tripPlan(trip);
        if (plan.isEmpty()) {
          continue;
        }
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
          visitor.visit(plan.get().stops(), trip.departureAt(date, timetable.zoneId()));
        }
      }
    }
  }
}
