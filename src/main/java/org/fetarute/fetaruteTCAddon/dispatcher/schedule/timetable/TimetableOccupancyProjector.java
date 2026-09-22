package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;

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
    return project(timetable, profiles, zeroSecondOfDay, Optional.empty());
  }

  /**
   * 展开占用并标上归属。
   *
   * @param owner 邻表的显示码；空 = 自己。带归属的运行在冲突检查里是不可移动的路权事实
   */
  public static Occupancy project(
      Timetable timetable,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      int zeroSecondOfDay,
      Optional<String> owner) {
    Objects.requireNonNull(timetable, "timetable");
    Objects.requireNonNull(profiles, "profiles");
    Optional<String> tag = owner == null ? Optional.empty() : owner;
    List<TimetableConflictChecker.Movement> movements = new ArrayList<>();
    List<TimetableConflictChecker.Stay> stays = new ArrayList<>();
    Map<UUID, TimetableTrip> tripsById = new HashMap<>();
    Set<UUID> returnRouteIds = returnRouteIdsOf(timetable);
    int serviceStart = timetable.serviceStartSecondOfDay();
    for (TimetableTrip trip : timetable.trips()) {
      tripsById.put(trip.id(), trip);
      addTripMovement(
          trip, profiles, returnRouteIds, serviceStart, zeroSecondOfDay, tag, movements);
    }
    for (VehicleDuty duty : timetable.duties()) {
      appendDutyLegs(
          duty, tripsById, profiles, serviceStart, zeroSecondOfDay, tag, movements, stays);
    }
    return new Occupancy(List.copyOf(movements), List.copyOf(stays));
  }

  /**
   * 只展开一个 duty 的占用，班次直接给进来。
   *
   * <p>让车修复的增量重扫走这一条：它手上已经有改写好的交路与班次，为了查几行而重建一整张 {@link Timetable} 是纯浪费——光把九百多个班次重排一遍就够贵了。
   *
   * @param duty 交路（时刻已改写）
   * @param trips 它的班次，时刻已改写；顺序无关，按 {@link VehicleDuty#tripIds()} 取用
   * @param profiles 各 route 的投影（含 CREATE/RETURN）
   * @param returnRouteIds 以 RETURN 收尾的 route：这些班次的运行由回库走行投影，不再单独投一遍
   * @param serviceStartSecondOfDay 计划窗口起点，判跨零点用
   * @param zeroSecondOfDay 零点
   */
  public static Occupancy projectDuty(
      VehicleDuty duty,
      List<TimetableTrip> trips,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      Set<UUID> returnRouteIds,
      int serviceStartSecondOfDay,
      int zeroSecondOfDay) {
    Objects.requireNonNull(duty, "duty");
    Objects.requireNonNull(profiles, "profiles");
    Map<UUID, TimetableTrip> tripsById = new HashMap<>();
    for (TimetableTrip trip : trips == null ? List.<TimetableTrip>of() : trips) {
      tripsById.put(trip.id(), trip);
    }
    Set<UUID> returns = returnRouteIds == null ? Set.of() : returnRouteIds;
    List<TimetableConflictChecker.Movement> movements = new ArrayList<>();
    List<TimetableConflictChecker.Stay> stays = new ArrayList<>();
    for (UUID tripId : duty.tripIds()) {
      TimetableTrip trip = tripsById.get(tripId);
      if (trip != null) {
        addTripMovement(
            trip,
            profiles,
            returns,
            serviceStartSecondOfDay,
            zeroSecondOfDay,
            Optional.empty(),
            movements);
      }
    }
    appendDutyLegs(
        duty,
        tripsById,
        profiles,
        serviceStartSecondOfDay,
        zeroSecondOfDay,
        Optional.empty(),
        movements,
        stays);
    return new Occupancy(movements, stays);
  }

  /** 表里以 RETURN 收尾的 route：它们的班次行不单独投影运行。 */
  public static Set<UUID> returnRouteIdsOf(Timetable timetable) {
    Set<UUID> out = new HashSet<>();
    for (TimetableRoutePlan plan : timetable.routePlans()) {
      if (plan.kind() == RouteOperationType.RETURN) {
        out.add(plan.routeId());
      }
    }
    return out;
  }

  /** 一班车的运行。带客的回库班有 trip 行，但它的运行由 duty 的回库走行投影（同一辆车、同一时刻），这里不再投一遍。 */
  private static void addTripMovement(
      TimetableTrip trip,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      Set<UUID> returnRouteIds,
      int serviceStartSecondOfDay,
      int zeroSecondOfDay,
      Optional<String> owner,
      List<TimetableConflictChecker.Movement> movements) {
    if (profiles.containsKey(trip.routeId()) && !returnRouteIds.contains(trip.routeId())) {
      movements.add(
          new TimetableConflictChecker.Movement(
              trip.tripCode(),
              trip.routeId(),
              relativeDeparture(trip, serviceStartSecondOfDay, zeroSecondOfDay),
              owner));
    }
  }

  /** 班次相对零点的发车秒数：早于窗口起点的当日秒数属于下一个日历日。 */
  static int relativeDeparture(Timetable timetable, TimetableTrip trip, int zeroSecondOfDay) {
    return relativeDeparture(trip, timetable.serviceStartSecondOfDay(), zeroSecondOfDay);
  }

  private static int relativeDeparture(
      TimetableTrip trip, int serviceStartSecondOfDay, int zeroSecondOfDay) {
    int departure = trip.departureSecondOfDay();
    if (departure < serviceStartSecondOfDay) {
      departure += TimetableTrip.SECONDS_PER_DAY;
    }
    return departure - zeroSecondOfDay;
  }

  /** 一个 duty 的贡献：两段走行是运行，到站等首班、两班之间折返、末班到发回库票是站台待命。 */
  private static void appendDutyLegs(
      VehicleDuty duty,
      Map<UUID, TimetableTrip> tripsById,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      int serviceStart,
      int zero,
      Optional<String> owner,
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
    int firstDeparture = relativeDeparture(chain.get(0), serviceStart, zero);
    int dutyStart = duty.plannedStartSecondOfDay() - zero;
    int returnAt = duty.returnSecondOfDay() - zero;
    duty.createRouteId()
        .ifPresent(
            routeId -> {
              movements.add(
                  new TimetableConflictChecker.Movement(
                      duty.dutyCode() + "-CREATE", routeId, dutyStart, owner));
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
                                  firstDeparture,
                                  owner)));
            });
    for (int i = 0; i + 1 < chain.size(); i++) {
      int arrival =
          relativeDeparture(chain.get(i), serviceStart, zero)
              + lastArrival(chainProfiles.get(i).stops());
      int nextDeparture = relativeDeparture(chain.get(i + 1), serviceStart, zero);
      chainProfiles
          .get(i)
          .terminal()
          .ifPresent(
              platform ->
                  stays.add(
                      new TimetableConflictChecker.Stay(
                          duty.dutyCode(),
                          platform,
                          arrival,
                          Math.max(arrival, nextDeparture),
                          owner)));
    }
    int lastIndex = chain.size() - 1;
    duty.returnRouteId()
        .ifPresent(
            routeId -> {
              movements.add(
                  new TimetableConflictChecker.Movement(
                      duty.dutyCode() + "-RETURN", routeId, returnAt, owner));
              int arrival =
                  relativeDeparture(chain.get(lastIndex), serviceStart, zero)
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
                                  Math.max(arrival, returnAt),
                                  owner)));
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
