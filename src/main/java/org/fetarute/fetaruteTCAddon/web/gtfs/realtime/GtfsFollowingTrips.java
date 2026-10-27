package org.fetarute.fetaruteTCAddon.web.gtfs.realtime;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteStopExtraDwell;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRoutePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDuty;

/**
 * 从已发布时刻表里查一趟车之后、同一车辆交路依次要跑的车次。纯计算，只读不可变的时刻表与交路停靠表。
 *
 * <p>交路的车次顺序是：带客的出库展示车次（如有）→ {@link VehicleDuty#tripIds()} → 带客的回库展示车次（如有）。后续车次的日期按交路服务日换算：
 * 跨零点的交路里，零点后的那一趟起点发车落在下一个日历日，不能沿用前一趟的日期。
 */
final class GtfsFollowingTrips {

  private GtfsFollowingTrips() {}

  /**
   * @param timetables 生效中的已发布时刻表
   * @param routeStops 交路 UUID → 停靠表（与交路节点下标对齐）；交路不在缓存里时为空列表
   * @param timetableId 当前车次的时刻表
   * @param tripCode 当前车次号
   * @param serviceDate 当前车次起点发车所在日期
   * @param limit 最多查几趟
   * @return 接下来的车次；找不到车次、不挂交路或已是最后一趟时为空
   */
  static List<GtfsRealtimeSnapshot.FollowingTrip> of(
      List<Timetable> timetables,
      Function<UUID, List<RouteStop>> routeStops,
      UUID timetableId,
      String tripCode,
      LocalDate serviceDate,
      int limit) {
    Optional<Timetable> found =
        timetables.stream().filter(t -> t.id().equals(timetableId)).findFirst();
    if (found.isEmpty() || limit <= 0) {
      return List.of();
    }
    Timetable timetable = found.get();
    Optional<TimetableTrip> current = timetable.tripByCode(tripCode);
    Optional<VehicleDuty> duty = current.flatMap(TimetableTrip::dutyId).flatMap(timetable::duty);
    if (current.isEmpty() || duty.isEmpty()) {
      return List.of();
    }
    List<TimetableTrip> chain = chain(timetable, duty.get());
    int index = -1;
    for (int i = 0; i < chain.size(); i++) {
      if (chain.get(i).id().equals(current.get().id())) {
        index = i;
        break;
      }
    }
    if (index < 0) {
      return List.of();
    }
    LocalDate dutyDay = timetable.serviceDayOf(current.get(), serviceDate);
    List<GtfsRealtimeSnapshot.FollowingTrip> out = new ArrayList<>();
    TimetableTrip previous = current.get();
    for (int i = index + 1; i < chain.size() && out.size() < limit; i++) {
      TimetableTrip next = chain.get(i);
      Instant departure = timetable.departureOnServiceDay(next, dutyDay);
      out.add(
          new GtfsRealtimeSnapshot.FollowingTrip(
              next.tripCode(),
              LocalDate.ofInstant(departure, timetable.zoneId()),
              afterLastStation(timetable, previous)
                  + terminalDwellSeconds(routeStops.apply(previous.routeId()))));
      previous = next;
    }
    return out;
  }

  /** 前一趟从最后一个载客站到交路最后一个节点的表定时分：交路在终点站之后还要开进折返线（正线折返点）时，这段走行不能算成折返余量。 交路就在终点站结束时为 0。 */
  static int afterLastStation(Timetable timetable, TimetableTrip trip) {
    List<TimetableStop> stops =
        timetable.tripPlan(trip).map(TimetableRoutePlan::stops).orElse(List.of());
    if (stops.isEmpty()) {
      return 0;
    }
    int lastStation = -1;
    for (int i = 0; i < stops.size(); i++) {
      if (stops.get(i).stops() && stops.get(i).stationCode().isPresent()) {
        lastStation = i;
      }
    }
    if (lastStation < 0) {
      return 0;
    }
    return Math.max(
        0,
        stops.get(stops.size() - 1).arrivalOffsetSeconds()
            - stops.get(lastStation).arrivalOffsetSeconds());
  }

  /** 交路依次跑的车次：带客出库展示车次、交路车次、带客回库展示车次。 */
  private static List<TimetableTrip> chain(Timetable timetable, VehicleDuty duty) {
    List<TimetableTrip> out = new ArrayList<>();
    timetable.legTrip(duty, RouteOperationType.CREATE).ifPresent(out::add);
    for (UUID tripId : duty.tripIds()) {
      timetable.trip(tripId).ifPresent(out::add);
    }
    timetable.legTrip(duty, RouteOperationType.RETURN).ifPresent(out::add);
    return out;
  }

  /**
   * 车到终点后最早多久能开下一趟：交路最后一个节点的计划停站，加多模车换模的时间。与站牌推算“终点车接着开的那一趟”同一口径（{@code
   * RouteStopPlan#plannedDwellSec}）。
   */
  static int terminalDwellSeconds(List<RouteStop> stops) {
    Objects.requireNonNull(stops, "stops");
    if (stops.isEmpty()) {
      return 0;
    }
    int last = stops.size() - 1;
    int[] power = RouteStopExtraDwell.plan(stops);
    return stops.get(last).plannedDwellSeconds() + (last < power.length ? power[last] : 0);
  }
}
