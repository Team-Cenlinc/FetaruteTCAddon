package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 端点串行与让车修复挪动班次之后，把加解编两边重新对齐（只往后推，不往前提）：
 *
 * <ul>
 *   <li>解编：解下的车跑的第一班不早于主干离开解编站；
 *   <li>联挂：主干进联挂站不早于回程支线到站 + 联挂提前量（支线车晚了，主干在联挂站等它）；
 *   <li>同一辆车：下一班不早于上一班到达 + 折返。
 * </ul>
 *
 * 推到不再变化为止（有上限）。被推后的冲突照常交给后面的冲突检查与残余分类。纯计算。
 */
final class CouplingAlignment {

  /** 推到不再变化的轮数上限。 */
  private static final int MAX_ROUNDS = 64;

  private CouplingAlignment() {}

  /**
   * 对齐。
   *
   * @param table 修复后的表（时刻为零点 + 相对秒，不取模）
   * @param analysis 加解编结构（按基础 route 查）
   * @param planOf route（含车型变体）→ 时分
   * @param baseOf 车型变体 → 基础 route
   * @param turnaround 折返时间
   * @param coupleSeconds 联挂提前量
   * @return 对齐后的表；没有加解编或不用挪时原样返回
   */
  static Timetable align(
      Timetable table,
      CouplingPlanner.Analysis analysis,
      Function<UUID, TimetableRoutePlan> planOf,
      Function<UUID, UUID> baseOf,
      TurnaroundTable turnaround,
      int coupleSeconds) {
    if (analysis.isEmpty()) {
      return table;
    }
    Map<UUID, TimetableTrip> tripsById = new HashMap<>();
    Map<UUID, Integer> departure = new HashMap<>();
    for (TimetableTrip trip : table.trips()) {
      tripsById.put(trip.id(), trip);
      departure.put(trip.id(), trip.departureSecondOfDay());
    }
    boolean changed = true;
    for (int round = 0; changed && round < MAX_ROUNDS; round++) {
      changed = false;
      for (VehicleDuty duty : table.duties()) {
        List<UUID> chain = present(duty.tripIds(), tripsById);
        if (chain.isEmpty()) {
          continue;
        }
        // 解编：第一班不早于主干离开解编站
        if (duty.coupling().splitFromTripId().isPresent()) {
          TimetableTrip trunk = tripsById.get(duty.coupling().splitFromTripId().get());
          CouplingPlanner.TrunkSplit split =
              trunk == null ? null : analysis.splits().get(baseOf.apply(trunk.routeId()));
          if (split != null) {
            int earliest = departure.get(trunk.id()) + split.departureOffset();
            changed |= pushTo(departure, chain.get(0), earliest);
          }
        }
        // 联挂：主干进联挂站不早于回程支线到站 + 提前量
        if (duty.coupling().joinsTripId().isPresent()) {
          TimetableTrip trunk = tripsById.get(duty.coupling().joinsTripId().get());
          CouplingPlanner.TrunkJoin join =
              trunk == null ? null : analysis.joins().get(baseOf.apply(trunk.routeId()));
          UUID last = chain.get(chain.size() - 1);
          if (join != null) {
            int arrival = departure.get(last) + runOf(planOf, tripsById.get(last));
            int earliest = arrival + coupleSeconds - join.arrivalOffset();
            changed |= pushTo(departure, trunk.id(), earliest);
          }
        }
        // 同一辆车：下一班不早于上一班到达 + 折返
        for (int i = 0; i + 1 < chain.size(); i++) {
          TimetableTrip previous = tripsById.get(chain.get(i));
          int ready =
              departure.get(previous.id())
                  + runOf(planOf, previous)
                  + turnaround.secondsFor(baseOf.apply(previous.routeId()));
          changed |= pushTo(departure, chain.get(i + 1), ready);
        }
      }
    }
    boolean any = false;
    List<TimetableTrip> trips = new ArrayList<>(table.trips().size());
    for (TimetableTrip trip : table.trips()) {
      int next = departure.get(trip.id());
      if (next == trip.departureSecondOfDay()) {
        trips.add(trip);
        continue;
      }
      any = true;
      trips.add(
          new TimetableTrip(
              trip.id(),
              trip.timetableId(),
              trip.routeId(),
              trip.sequence(),
              trip.tripCode(),
              next,
              trip.dutyId()));
    }
    if (!any && table.duties().stream().noneMatch(duty -> duty.coupling().endsByJoin())) {
      return table;
    }
    List<VehicleDuty> duties = new ArrayList<>(table.duties().size());
    for (VehicleDuty duty : table.duties()) {
      List<UUID> chain = present(duty.tripIds(), tripsById);
      if (chain.isEmpty()) {
        duties.add(duty);
        continue;
      }
      UUID last = chain.get(chain.size() - 1);
      int delay = departure.get(last) - tripsById.get(last).departureSecondOfDay();
      if (duty.coupling().joinsTripId().isPresent()) {
        // 以联挂结束：车在联挂站一直等到主干离开（站台待命的终点），之后并进主干
        duties.add(
            joinedUntilTrunkLeaves(duty, last, departure, tripsById, analysis, planOf, baseOf));
        continue;
      }
      if (delay <= 0) {
        duties.add(duty);
        continue;
      }
      // 末班推后多少，回库（或联挂收尾）就跟着推后多少
      duties.add(
          new VehicleDuty(
              duty.id(),
              duty.timetableId(),
              duty.sequence(),
              duty.dutyCode(),
              duty.startDepotNodeId(),
              duty.endDepotNodeId(),
              duty.createRouteId(),
              duty.returnRouteId(),
              duty.tripIds(),
              duty.plannedStartSecondOfDay(),
              duty.returnSecondOfDay() + delay,
              duty.plannedEndSecondOfDay() + delay,
              duty.closeReason(),
              duty.consist(),
              duty.coupling()));
    }
    return table.withTripsAndDuties(trips, duties);
  }

  /** 以联挂结束的交路：收尾时刻改为主干离开联挂站（不早于本车到站）。 */
  private static VehicleDuty joinedUntilTrunkLeaves(
      VehicleDuty duty,
      UUID last,
      Map<UUID, Integer> departure,
      Map<UUID, TimetableTrip> tripsById,
      CouplingPlanner.Analysis analysis,
      Function<UUID, TimetableRoutePlan> planOf,
      Function<UUID, UUID> baseOf) {
    int arrival = departure.get(last) + runOf(planOf, tripsById.get(last));
    int end = arrival;
    TimetableTrip trunk = tripsById.get(duty.coupling().joinsTripId().get());
    if (trunk != null) {
      CouplingPlanner.TrunkJoin join = analysis.joins().get(baseOf.apply(trunk.routeId()));
      if (join != null) {
        end = Math.max(arrival, departure.get(trunk.id()) + join.departureOffset());
      }
    }
    return new VehicleDuty(
        duty.id(),
        duty.timetableId(),
        duty.sequence(),
        duty.dutyCode(),
        duty.startDepotNodeId(),
        duty.endDepotNodeId(),
        duty.createRouteId(),
        duty.returnRouteId(),
        duty.tripIds(),
        duty.plannedStartSecondOfDay(),
        Math.max(duty.plannedStartSecondOfDay(), end),
        Math.max(duty.plannedStartSecondOfDay(), end),
        duty.closeReason(),
        duty.consist(),
        duty.coupling());
  }

  private static List<UUID> present(List<UUID> ids, Map<UUID, TimetableTrip> tripsById) {
    List<UUID> out = new ArrayList<>(ids.size());
    for (UUID id : ids) {
      if (tripsById.containsKey(id)) {
        out.add(id);
      }
    }
    return out;
  }

  private static int runOf(Function<UUID, TimetableRoutePlan> planOf, TimetableTrip trip) {
    TimetableRoutePlan plan = planOf.apply(trip.routeId());
    return plan == null ? 0 : plan.totalRunSeconds();
  }

  private static boolean pushTo(Map<UUID, Integer> departure, UUID tripId, int earliest) {
    Integer current = departure.get(tripId);
    if (current == null || current >= earliest) {
      return false;
    }
    departure.put(tripId, earliest);
    return true;
  }
}
