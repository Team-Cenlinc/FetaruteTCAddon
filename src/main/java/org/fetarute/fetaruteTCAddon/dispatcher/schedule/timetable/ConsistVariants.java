package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 车型变体在编表内外的两种形态之间转换。
 *
 * <p>编表内部，班次与交路的出入库段挂的是车型变体（{@link ConsistFleet#variantId}），各环节按变体查时分与足迹。 编完落库前折回<b>基础 route</b>：
 * 车次、交路的出入库段都写回真实的 route，车型记在交路上（{@link VehicleDuty#consist()}）；变体计划改用基础 route 的 ID、带着车型留在 {@code
 * route_plans} 里（{@link Timetable#routePlan(UUID, Optional)} 按车型取）。运行时、站牌、邻表按真实 route 比对，不认识变体 ID。
 */
public final class ConsistVariants {

  private ConsistVariants() {}

  /**
   * 编表内部形态 → 落库形态。不区分车型的表原样返回。
   *
   * @param table 编表内部的表（班次挂变体）
   * @return 车次与交路挂真实 route、变体计划改用基础 route ID 的表
   */
  public static Timetable collapse(Timetable table) {
    Map<UUID, UUID> baseOf = new HashMap<>();
    for (TimetableRoutePlan plan : table.routePlans()) {
      if (plan.consist().isPresent() && !plan.routeId().equals(plan.baseRouteId())) {
        baseOf.put(plan.routeId(), plan.baseRouteId());
      }
    }
    if (baseOf.isEmpty()) {
      return table;
    }
    List<TimetableRoutePlan> plans = new ArrayList<>(table.routePlans().size());
    for (TimetableRoutePlan plan : table.routePlans()) {
      plans.add(baseOf.containsKey(plan.routeId()) ? plan.withRouteId(plan.baseRouteId()) : plan);
    }
    List<TimetableTrip> trips = new ArrayList<>(table.trips().size());
    for (TimetableTrip trip : table.trips()) {
      UUID base = baseOf.getOrDefault(trip.routeId(), trip.routeId());
      trips.add(
          base.equals(trip.routeId())
              ? trip
              : new TimetableTrip(
                  trip.id(),
                  trip.timetableId(),
                  base,
                  trip.sequence(),
                  trip.tripCode(),
                  trip.departureSecondOfDay(),
                  trip.dutyId()));
    }
    List<VehicleDuty> duties = new ArrayList<>(table.duties().size());
    for (VehicleDuty duty : table.duties()) {
      duties.add(
          new VehicleDuty(
              duty.id(),
              duty.timetableId(),
              duty.sequence(),
              duty.dutyCode(),
              duty.startDepotNodeId(),
              duty.endDepotNodeId(),
              mapped(duty.createRouteId(), baseOf),
              mapped(duty.returnRouteId(), baseOf),
              duty.tripIds(),
              duty.plannedStartSecondOfDay(),
              duty.returnSecondOfDay(),
              duty.plannedEndSecondOfDay(),
              duty.closeReason(),
              duty.consist()));
    }
    return table.withPlansTripsAndDuties(plans, trips, duties);
  }

  private static Optional<UUID> mapped(Optional<UUID> routeId, Map<UUID, UUID> baseOf) {
    return routeId.map(id -> baseOf.getOrDefault(id, id));
  }
}
