package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 交路改时刻：把预订表（{@link ReservationScheduler}）定下的实际发车写回表，交路的出库、回库与收尾跟着走；
 * 延后越过计划窗口或交路上限时从那一班起截断、退到能回库的终点。
 *
 * <p>"改时刻不增减班次、交路仍要收口"的规则只有这一份，派车之后改时刻的地方都走这里。
 */
public final class DutyRetiming {

  private DutyRetiming() {}

  /**
   * 一班的时刻偏离：名义时隙与实际发车（相对秒），以及为什么。
   *
   * @param tripId 班次
   * @param nominalSeconds 名义发车
   * @param actualSeconds 实际发车
   * @param reason 原因
   */
  public record Shift(UUID tripId, int nominalSeconds, int actualSeconds, Reason reason) {
    /** 偏离的原因。 */
    public enum Reason {
      /** 本车上一班延后了，就绪晚于名义时隙。 */
      VEHICLE_READY,
      /** 让车：与已排好的车在某个资源上撞了，整趟延后到对方让开之后。 */
      YIELDED
    }
  }

  /**
   * 把改过的时刻写回表：trips 按 {@code actual}，duties 的 plannedStart / returnSecond / plannedEnd 跟着首末班走；
   * 截断的交路少了尾段并重新找回库线路。 改时刻不增减班次、交路仍要收口的规则只有这一份。
   *
   * @param chains 每条 duty 的班次链（与 duties 对齐）
   * @param kept 每条链保留的班次数
   * @param actual 临时 id → 实际发车（相对秒）；没有的照原时刻
   * @param truncated 被截掉的班次 id，不落表
   * @param startDelay 每条 duty 出库票相对原时刻的偏移（可为负：出库走行提前；可为 null）
   * @param returnDelay 每条 duty 回库票额外延后的秒数（可为 null）
   */
  static Timetable rewrite(
      Timetable table,
      List<List<TimetableTrip>> chains,
      int[] kept,
      Map<UUID, Integer> actual,
      Set<UUID> truncated,
      int[] startDelay,
      int[] returnDelay,
      int zero,
      TurnaroundTable turnarounds,
      Set<UUID> routesEndingAtDepot,
      VehicleDutyPlanner.Legs legs) {
    List<VehicleDuty> duties = table.duties();
    List<TimetableTrip> trips = new ArrayList<>(table.trips().size());
    for (TimetableTrip trip : table.trips()) {
      if (truncated.contains(trip.id())) {
        continue;
      }
      trips.add(retimed(trip, actual, zero));
    }
    List<VehicleDuty> rewritten = new ArrayList<>(duties.size());
    for (int d = 0; d < duties.size(); d++) {
      RewrittenDuty one =
          rewriteDuty(
              table,
              chains.get(d),
              d,
              duties.get(d),
              kept,
              actual,
              startDelay,
              returnDelay,
              zero,
              turnarounds,
              routesEndingAtDepot,
              legs);
      if (one != null) {
        rewritten.add(one.duty());
      }
    }
    Timetable out =
        new Timetable(
            table.id(),
            table.companyId(),
            table.operatorId(),
            table.lineId(),
            table.code(),
            table.name(),
            table.status(),
            table.zoneId(),
            table.serviceStartSecondOfDay(),
            table.serviceEndSecondOfDay(),
            table.routePlans(),
            trips,
            rewritten,
            table.notes(),
            table.createdAt(),
            table.updatedAt());

    return out;
  }

  /**
   * 一条交路改写之后的样子。
   *
   * @param duty 改写后的交路
   * @param trips 它保留下来的班次，时刻已按 {@code actual} 改过
   */
  record RewrittenDuty(VehicleDuty duty, List<TimetableTrip> trips) {}

  /**
   * 改写一条交路。逐条改写，{@link #rewrite} 用它拼整张表。
   *
   * @return {@code null} 表示这条交路整条不落表：全被截掉，或者退到头也找不到回库线路
   */
  static RewrittenDuty rewriteDuty(
      Timetable table,
      List<TimetableTrip> chain,
      int d,
      VehicleDuty duty,
      int[] kept,
      Map<UUID, Integer> actual,
      int[] startDelay,
      int[] returnDelay,
      int zero,
      TurnaroundTable turnarounds,
      Set<UUID> routesEndingAtDepot,
      VehicleDutyPlanner.Legs legs) {
    if (chain.isEmpty()) {
      return new RewrittenDuty(duty, List.of());
    }
    if (kept[d] <= 0) {
      return null; // 整条交路都被截掉：它没有一班能跑，不落表
    }
    List<TimetableTrip> keptChain = chain.subList(0, kept[d]);
    TimetableTrip first = keptChain.get(0);
    TimetableTrip last = keptChain.get(keptChain.size() - 1);
    int firstDelta =
        actual.getOrDefault(first.id(), first.departureSecondOfDay() - zero)
            - (first.departureSecondOfDay() - zero);
    List<UUID> ids = new ArrayList<>(keptChain.size());
    List<TimetableTrip> rows = new ArrayList<>(keptChain.size());
    for (TimetableTrip trip : keptChain) {
      ids.add(trip.id());
      rows.add(retimed(trip, actual, zero));
    }
    TimetableRoutePlan lastPlan = table.routePlan(last.routeId()).orElse(null);
    int lastArrival =
        actual.getOrDefault(last.id(), last.departureSecondOfDay() - zero)
            + (lastPlan == null ? 0 : lastPlan.totalRunSeconds());
    boolean truncatedDuty = kept[d] < chain.size();
    String endDepot = duty.endDepotNodeId();
    Optional<UUID> returnRouteId = duty.returnRouteId();
    int returnAt;
    int end;
    if (!truncatedDuty) {
      int oldLastArrival =
          (last.departureSecondOfDay() - zero)
              + (lastPlan == null ? 0 : lastPlan.totalRunSeconds());
      int delta = lastArrival - oldLastArrival + returnDelayOf(returnDelay, d);
      returnAt = duty.returnSecondOfDay() - zero + delta;
      end = duty.plannedEndSecondOfDay() - zero + delta;
    } else if (lastPlan != null && routesEndingAtDepot.contains(last.routeId())) {
      endDepot = lastPlan.terminalNodeId();
      returnRouteId = Optional.empty();
      returnAt = lastArrival + returnDelayOf(returnDelay, d);
      end = returnAt;
    } else {
      // 改了时刻不改归属：回库仍按派车器那条"回自己出库的库"的规则选，两边必须一致。
      VehicleDutyPlanner.Leg leg =
          lastPlan == null
              ? null
              : legs.returnLegAt(lastPlan.terminalNodeId(), duty.startDepotNodeId(), duty.consist())
                  .orElse(null);
      if (leg == null) {
        return null; // truncateFrom 已保证可回库；到这里是防御
      }
      endDepot = leg.depotNodeId();
      returnRouteId = Optional.of(leg.routeId());
      returnAt =
          lastArrival + turnarounds.secondsFor(last.routeId()) + returnDelayOf(returnDelay, d);
      end = returnAt + leg.runSeconds();
    }
    return new RewrittenDuty(
        new VehicleDuty(
            duty.id(),
            duty.timetableId(),
            duty.sequence(),
            duty.dutyCode(),
            duty.startDepotNodeId(),
            endDepot,
            duty.createRouteId(),
            returnRouteId,
            ids,
            duty.plannedStartSecondOfDay() + firstDelta + startDelayOf(startDelay, d),
            zero + returnAt,
            zero + end,
            truncatedDuty ? VehicleDuty.CloseReason.NO_COMPATIBLE_NEXT : duty.closeReason(),
            duty.consist(),
            // 截断了的交路改为回库收尾，不再并入原来那一班主干
            truncatedDuty
                ? new DutyCoupling(duty.coupling().splitFromTripId(), java.util.Optional.empty())
                : duty.coupling()),
        rows);
  }

  /** 班次按 {@code actual} 改时刻；没改过的原样返回。 */
  private static TimetableTrip retimed(TimetableTrip trip, Map<UUID, Integer> actual, int zero) {
    Integer dep = actual.get(trip.id());
    return dep == null
        ? trip
        : new TimetableTrip(
            trip.id(),
            trip.timetableId(),
            trip.routeId(),
            trip.sequence(),
            trip.tripCode(),
            zero + dep,
            trip.dutyId());
  }

  /** 出库走行可以提前（在首站多等），所以出库的偏移可为负。 */
  private static int startDelayOf(int[] delays, int d) {
    return delays == null || d >= delays.length ? 0 : delays[d];
  }

  private static int returnDelayOf(int[] delays, int d) {
    return delays == null || d >= delays.length ? 0 : Math.max(0, delays[d]);
  }

  /** 每条 duty 的班次链，与 {@code table.duties()} 对齐；引用不到的 trip id 跳过。 */
  static List<List<TimetableTrip>> chainsOf(Timetable table) {
    Map<UUID, TimetableTrip> tripsById = new HashMap<>();
    for (TimetableTrip trip : table.trips()) {
      tripsById.put(trip.id(), trip);
    }
    List<List<TimetableTrip>> chains = new ArrayList<>(table.duties().size());
    for (VehicleDuty duty : table.duties()) {
      List<TimetableTrip> chain = new ArrayList<>();
      for (UUID id : duty.tripIds()) {
        TimetableTrip trip = tripsById.get(id);
        if (trip != null) {
          chain.add(trip);
        }
      }
      chains.add(chain);
    }
    return chains;
  }

  /** 延后之后这一班还装不装得下：越过计划窗口，或连同收尾超过交路时长上限。 */
  static boolean exceedsLimits(
      int dep,
      TimetableRoutePlan plan,
      VehicleDuty duty,
      int horizonSeconds,
      int turnaroundSeconds,
      Set<UUID> routesEndingAtDepot,
      VehicleDutyPlanner.Legs legs,
      VehicleDutyPlanner.Limits limits,
      int zero) {
    if (dep >= horizonSeconds) {
      return true;
    }
    int tail;
    if (routesEndingAtDepot.contains(plan.routeId())) {
      tail = 0;
    } else {
      tail =
          legs.returnLegAt(plan.terminalNodeId(), null, duty.consist())
              .map(leg -> turnaroundSeconds + leg.runSeconds())
              .orElse(turnaroundSeconds);
    }
    int dutyStart = duty.plannedStartSecondOfDay() - zero;
    return dep + plan.totalRunSeconds() + tail - dutyStart > limits.maxDutyDurationSeconds();
  }

  /** 从第 i 班起截断交路，再往前退到一个能回库的终点为止（与派车器窗口末尾的处理同一条规则）。 */
  static void truncateFrom(
      int i,
      List<TimetableTrip> chain,
      int d,
      int[] kept,
      Map<UUID, Integer> actual,
      Set<UUID> truncated,
      Set<UUID> routesEndingAtDepot,
      VehicleDutyPlanner.Legs legs,
      Timetable table,
      Optional<String> consist) {
    int keep = i;
    while (keep > 0) {
      TimetableTrip last = chain.get(keep - 1);
      TimetableRoutePlan plan = table.routePlan(last.routeId()).orElse(null);
      boolean closable =
          plan != null
              && (routesEndingAtDepot.contains(last.routeId())
                  || legs.returnLegAt(plan.terminalNodeId(), null, consist).isPresent());
      if (closable) {
        break;
      }
      keep--;
    }
    for (int k = keep; k < chain.size(); k++) {
      truncated.add(chain.get(k).id());
      actual.remove(chain.get(k).id());
    }
    kept[d] = keep;
  }
}
