package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 把一份时刻表（班次 + 车辆交路）连同各 route 的投影，展开成冲突模型里的运行与站台待命。
 *
 * <p>builder 检查自己的 attempt 和将来投影邻表用的是同一个实现：占用的口径只能有一个，否则"自己查自己没冲突、 别人查我却撞上"这种事会出现在同一份表上。
 *
 * <p>待命必须按 duty 建模而不是按班次：同一辆车"到站 → 折返 → 再发车"是一段连续占用，拆成两个班次各自的到发区间 会在中间留出一个并不存在的空档。
 *
 * <p>时刻全部换算成相对 {@code zeroSecondOfDay} 的秒数。班次的发车时刻是取模后的当日秒数，早于计划窗口起点的视为跨零点 （与 {@link
 * Timetable#serviceDayOf} 同一条规则）；交路的时刻本来就不取模，直接减零点。
 */
public final class TimetableOccupancyProjector {

  private TimetableOccupancyProjector() {}

  /**
   * 展开占用。
   *
   * @param timetable 时刻表
   * @param profiles 各 route 的投影（含 CREATE/RETURN）
   * @param zeroSecondOfDay 零点（相对服务日的秒数）
   * @return 运行与待命
   */
  public static Occupancy project(
      Timetable timetable,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      int zeroSecondOfDay) {
    Objects.requireNonNull(timetable, "timetable");
    Objects.requireNonNull(profiles, "profiles");
    List<TimetableConflictChecker.Movement> movements = new ArrayList<>();
    List<TimetableConflictChecker.Stay> stays = new ArrayList<>();
    Map<UUID, TimetableTrip> tripsById = new HashMap<>();
    for (TimetableTrip trip : timetable.trips()) {
      tripsById.put(trip.id(), trip);
      if (profiles.containsKey(trip.routeId())) {
        movements.add(
            new TimetableConflictChecker.Movement(
                trip.tripCode(),
                trip.routeId(),
                relativeDeparture(timetable, trip, zeroSecondOfDay)));
      }
    }
    for (VehicleDuty duty : timetable.duties()) {
      projectDuty(timetable, duty, tripsById, profiles, zeroSecondOfDay, movements, stays);
    }
    return new Occupancy(List.copyOf(movements), List.copyOf(stays));
  }

  /** 班次相对零点的发车秒数：早于窗口起点的当日秒数属于下一个日历日。 */
  static int relativeDeparture(Timetable timetable, TimetableTrip trip, int zeroSecondOfDay) {
    int departure = trip.departureSecondOfDay();
    if (departure < timetable.serviceStartSecondOfDay()) {
      departure += TimetableTrip.SECONDS_PER_DAY;
    }
    return departure - zeroSecondOfDay;
  }

  /** 一个 duty 的贡献：两段走行是运行，到站等首班、两班之间折返、末班到发回库票是站台待命。 */
  private static void projectDuty(
      Timetable timetable,
      VehicleDuty duty,
      Map<UUID, TimetableTrip> tripsById,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      int zero,
      List<TimetableConflictChecker.Movement> movements,
      List<TimetableConflictChecker.Stay> stays) {
    List<TimetableTrip> chain = new ArrayList<>(duty.tripIds().size());
    List<TimetableConflictChecker.RouteProfile> chainProfiles = new ArrayList<>();
    for (UUID tripId : duty.tripIds()) {
      TimetableTrip trip = tripsById.get(tripId);
      TimetableConflictChecker.RouteProfile profile =
          trip == null ? null : profiles.get(trip.routeId());
      if (trip == null || profile == null) {
        continue;
      }
      chain.add(trip);
      chainProfiles.add(profile);
    }
    if (chain.isEmpty()) {
      return;
    }
    int firstDeparture = relativeDeparture(timetable, chain.get(0), zero);
    int dutyStart = duty.plannedStartSecondOfDay() - zero;
    int returnAt = duty.returnSecondOfDay() - zero;
    duty.createRouteId()
        .ifPresent(
            routeId -> {
              movements.add(
                  new TimetableConflictChecker.Movement(
                      duty.dutyCode() + "-CREATE", routeId, dutyStart));
              TimetableConflictChecker.RouteProfile create = profiles.get(routeId);
              int arrival = dutyStart + (create == null ? 0 : lastArrival(create.stops()));
              chainProfiles
                  .get(0)
                  .origin()
                  .ifPresent(
                      platform ->
                          stays.add(
                              new TimetableConflictChecker.Stay(
                                  duty.dutyCode(),
                                  platform,
                                  Math.min(arrival, firstDeparture),
                                  firstDeparture)));
            });
    for (int i = 0; i + 1 < chain.size(); i++) {
      int arrival =
          relativeDeparture(timetable, chain.get(i), zero)
              + lastArrival(chainProfiles.get(i).stops());
      int nextDeparture = relativeDeparture(timetable, chain.get(i + 1), zero);
      chainProfiles
          .get(i)
          .terminal()
          .ifPresent(
              platform ->
                  stays.add(
                      new TimetableConflictChecker.Stay(
                          duty.dutyCode(), platform, arrival, Math.max(arrival, nextDeparture))));
    }
    int lastIndex = chain.size() - 1;
    duty.returnRouteId()
        .ifPresent(
            routeId -> {
              movements.add(
                  new TimetableConflictChecker.Movement(
                      duty.dutyCode() + "-RETURN", routeId, returnAt));
              int arrival =
                  relativeDeparture(timetable, chain.get(lastIndex), zero)
                      + lastArrival(chainProfiles.get(lastIndex).stops());
              chainProfiles
                  .get(lastIndex)
                  .terminal()
                  .ifPresent(
                      platform ->
                          stays.add(
                              new TimetableConflictChecker.Stay(
                                  duty.dutyCode(),
                                  platform,
                                  arrival,
                                  Math.max(arrival, returnAt))));
            });
  }

  private static int lastArrival(List<TimetableStop> stops) {
    return stops.isEmpty() ? 0 : stops.get(stops.size() - 1).arrivalOffsetSeconds();
  }

  /**
   * 展开结果。
   *
   * @param movements 运行（班次 + 出库/回库走行）
   * @param stays 站台待命
   */
  public record Occupancy(
      List<TimetableConflictChecker.Movement> movements,
      List<TimetableConflictChecker.Stay> stays) {
    public Occupancy {
      movements = movements == null ? List.of() : List.copyOf(movements);
      stays = stays == null ? List.of() : List.copyOf(stays);
    }
  }
}
