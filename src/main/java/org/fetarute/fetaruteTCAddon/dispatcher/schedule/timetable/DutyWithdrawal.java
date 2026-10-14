package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 从已经派好的交路里拿掉几班（严格模式取消），只动卷进去的那几条交路，其余交路的车辆、班次与出入库原样不动。
 *
 * <p>拿掉一班之后，这辆车停在被取消那一班的起点上：
 *
 * <ul>
 *   <li>下一班留着的班次就从这里发、在闲置上限之内等得到：车在这里待命到下一班，交路不拆。
 *   <li>否则交路在这里收口：从这一站回库（回不了库就按派车收尾的同一规则把尾段班次退掉，直到能回库）；
 *       后面留着的班次另开一条交路，从车库出车接上——起点没有出库线路、或出库之后超过时长上限的， 这一班也取消，再看下一班。
 * </ul>
 *
 * <p>首末两端沿用原交路的那一段（首班没取消就用原来的出库，末班没取消就用原来的回库），新开的出入库按派车的同一套候选与选择器选。
 * 不重新派车：重新派车会让一大片交路换车，端点上的冲突整片换一批，取消一班反而多出几百处冲突。
 */
final class DutyWithdrawal {

  private DutyWithdrawal() {}

  /**
   * 从派车结果里拿掉 {@code withdrawn} 里的班次。
   *
   * @param timetableId 所属时刻表
   * @param planned 不取消时的派车结果
   * @param trips 派车用的全部班次（含要拿掉的）
   * @param withdrawn 要拿掉的班次主键；记为 {@link VehicleDutyPlanner.UnassignedReason#CONFLICT_CANCELLED}
   * @param legs 出入库走行段
   * @param limits 交路硬上限
   * @param selector 新开出入库时的选择器（与派车同一个）
   * @return 新的派车结果：拿掉的与因此另外截断的班次都在 {@link VehicleDutyPlanner.Result#unassigned()} 里
   */
  static VehicleDutyPlanner.Result withdraw(
      UUID timetableId,
      VehicleDutyPlanner.Result planned,
      List<VehicleDutyPlanner.PlannedTrip> trips,
      Set<UUID> withdrawn,
      VehicleDutyPlanner.Legs legs,
      VehicleDutyPlanner.Limits limits,
      VehicleDutyPlanner.DepotLegSelector selector) {
    if (withdrawn.isEmpty()) {
      return planned;
    }
    Map<UUID, VehicleDutyPlanner.PlannedTrip> byId = new HashMap<>();
    for (VehicleDutyPlanner.PlannedTrip trip : trips) {
      byId.put(trip.tripId(), trip);
    }
    List<VehicleDutyPlanner.UnassignedTrip> unassigned = new ArrayList<>(planned.unassigned());
    List<VehicleDuty> kept = new ArrayList<>(planned.duties().size() + 1);
    for (VehicleDuty duty : planned.duties()) {
      if (duty.tripIds().stream().noneMatch(withdrawn::contains)) {
        kept.add(duty);
        continue;
      }
      new Splitter(timetableId, duty, byId, withdrawn, legs, limits, selector, unassigned)
          .split(kept);
    }
    kept.sort(
        Comparator.comparingInt(VehicleDuty::plannedStartSecondOfDay)
            .thenComparing(VehicleDuty::dutyCode));
    List<VehicleDuty> renumbered = new ArrayList<>(kept.size());
    for (int i = 0; i < kept.size(); i++) {
      VehicleDuty duty = kept.get(i);
      String dutyCode = String.format(Locale.ROOT, "D%03d", i + 1);
      renumbered.add(
          new VehicleDuty(
              VehicleDutyPlanner.deterministicDutyId(timetableId, dutyCode),
              duty.timetableId(),
              i,
              dutyCode,
              duty.startDepotNodeId(),
              duty.endDepotNodeId(),
              duty.createRouteId(),
              duty.returnRouteId(),
              duty.tripIds(),
              duty.plannedStartSecondOfDay(),
              duty.returnSecondOfDay(),
              duty.plannedEndSecondOfDay(),
              duty.closeReason(),
              duty.consist()));
    }
    unassigned.sort(Comparator.comparing(VehicleDutyPlanner.UnassignedTrip::tripCode));
    return new VehicleDutyPlanner.Result(
        List.copyOf(renumbered), planned.notes(), renumbered.size(), List.copyOf(unassigned));
  }

  /** 把一条卷进取消的交路拆成几段。 */
  private static final class Splitter {
    private final UUID timetableId;
    private final VehicleDuty duty;
    private final Map<UUID, VehicleDutyPlanner.PlannedTrip> byId;
    private final Set<UUID> withdrawn;
    private final VehicleDutyPlanner.Legs legs;
    private final VehicleDutyPlanner.Limits limits;
    private final VehicleDutyPlanner.DepotLegSelector selector;
    private final List<VehicleDutyPlanner.UnassignedTrip> unassigned;
    private final Optional<String> consist;
    private int pieces;

    private Splitter(
        UUID timetableId,
        VehicleDuty duty,
        Map<UUID, VehicleDutyPlanner.PlannedTrip> byId,
        Set<UUID> withdrawn,
        VehicleDutyPlanner.Legs legs,
        VehicleDutyPlanner.Limits limits,
        VehicleDutyPlanner.DepotLegSelector selector,
        List<VehicleDutyPlanner.UnassignedTrip> unassigned) {
      this.timetableId = timetableId;
      this.duty = duty;
      this.byId = byId;
      this.withdrawn = withdrawn;
      this.legs = legs;
      this.limits = limits;
      this.selector = selector;
      this.unassigned = unassigned;
      this.consist = duty.consist();
    }

    private void split(List<VehicleDuty> out) {
      List<UUID> chain = duty.tripIds();
      UUID lastId = chain.get(chain.size() - 1);
      Piece current = null;
      boolean gap = false;
      for (int i = 0; i < chain.size(); i++) {
        VehicleDutyPlanner.PlannedTrip trip = byId.get(chain.get(i));
        if (withdrawn.contains(trip.tripId())) {
          drop(trip, VehicleDutyPlanner.UnassignedReason.CONFLICT_CANCELLED);
          gap = true;
          continue;
        }
        if (current != null && gap && !waitsFor(current, trip)) {
          close(current, lastId, out);
          current = null;
        }
        if (current == null) {
          current =
              i == 0
                  ? new Piece(
                      duty.startDepotNodeId(),
                      duty.createRouteId(),
                      duty.plannedStartSecondOfDay(),
                      false)
                  : open(trip);
          if (current == null) {
            drop(trip, VehicleDutyPlanner.UnassignedReason.NO_CREATE_ACCESS);
            continue;
          }
        }
        current.trips.add(trip);
        gap = false;
      }
      if (current != null) {
        close(current, lastId, out);
      }
    }

    /** 车停在上一班终点、下一班就从这里发，并且在闲置上限之内等得到：留在原交路里待命。 */
    private boolean waitsFor(Piece piece, VehicleDutyPlanner.PlannedTrip next) {
      VehicleDutyPlanner.PlannedTrip last = piece.last();
      if (last.endsAtDepot()
          || next.startsAtDepot()
          || !last.terminalNodeId().equals(next.originNodeId())) {
        return false;
      }
      long wait = (long) next.departureSeconds() - readyAt(last);
      return wait >= 0 && wait <= limits.maxIdleSeconds();
    }

    private int readyAt(VehicleDutyPlanner.PlannedTrip trip) {
      return arrival(trip) + limits.turnaround().secondsFor(trip.routeId());
    }

    private int arrival(VehicleDutyPlanner.PlannedTrip trip) {
      return trip.departureSeconds() + trip.durationFor(consist);
    }

    /** 在这一班上从车库出一辆车：与派车开新交路同一套候选（车型一致、显式指定的优先）与选择器。没有出库途径时为空。 */
    private Piece open(VehicleDutyPlanner.PlannedTrip trip) {
      if (trip.startsAtDepot()) {
        return new Piece(trip.originNodeId(), Optional.empty(), trip.departureSeconds(), true);
      }
      List<VehicleDutyPlanner.Leg> candidates =
          legs.createCandidates().getOrDefault(trip.originNodeId(), List.of()).stream()
              .filter(leg -> leg.consist().equals(consist))
              .toList();
      if (candidates.isEmpty()) {
        return null;
      }
      List<VehicleDutyPlanner.Leg> declared =
          candidates.stream().filter(VehicleDutyPlanner.Leg::declared).toList();
      List<VehicleDutyPlanner.Leg> pool = declared.isEmpty() ? candidates : declared;
      VehicleDutyPlanner.Leg chosen = selector.selectCreate(trip, pool, limits);
      if (!pool.contains(chosen)) {
        throw new IllegalArgumentException("出库选择器返回了不合格候选");
      }
      return new Piece(
          chosen.depotNodeId(),
          Optional.of(chosen.routeId()),
          VehicleDutyPlanner.openingStart(trip, chosen, limits),
          true);
    }

    /** 收口：末班还是原交路的末班时沿用原来的回库；否则从末班终点回库，回不了库（或超过时长上限）就把尾段班次退掉。 新开的一段沿用原来的回库却超过时长上限时，从头退掉班次重新出车。 */
    private void close(Piece piece, UUID originalLast, List<VehicleDuty> out) {
      Piece current = piece;
      while (current != null && !current.trips.isEmpty()) {
        VehicleDutyPlanner.PlannedTrip last = current.last();
        if (last.tripId().equals(originalLast)) {
          if (duty.plannedEndSecondOfDay() - current.startSeconds <= limits.maxDutyDurationSeconds()
              || !current.opened) {
            out.add(
                build(
                    current,
                    duty.endDepotNodeId(),
                    duty.returnRouteId(),
                    duty.returnSecondOfDay(),
                    duty.plannedEndSecondOfDay(),
                    duty.closeReason()));
            return;
          }
          current = reopenWithoutFirst(current);
          continue;
        }
        if (last.endsAtDepot()) {
          int arrival = arrival(last);
          out.add(
              build(
                  current,
                  last.terminalNodeId(),
                  Optional.empty(),
                  arrival,
                  arrival,
                  VehicleDuty.CloseReason.ROUTE_ENDS_AT_DEPOT));
          return;
        }
        int returnAt = readyAt(last);
        int start = current.startSeconds;
        List<VehicleDutyPlanner.Leg> candidates =
            legs
                .returnCandidatesAt(last.terminalNodeId(), Optional.of(current.startDepot), consist)
                .stream()
                .filter(
                    leg ->
                        (long) returnAt + leg.runSeconds() - start
                            <= limits.maxDutyDurationSeconds())
                .toList();
        if (candidates.isEmpty()) {
          current.trips.remove(current.trips.size() - 1);
          drop(last, VehicleDutyPlanner.UnassignedReason.NO_RETURN_ACCESS);
          continue;
        }
        VehicleDutyPlanner.Leg chosen = selector.selectReturn(last, returnAt, candidates);
        if (!candidates.contains(chosen)) {
          throw new IllegalArgumentException("回库选择器返回了不合格候选");
        }
        out.add(
            build(
                current,
                chosen.depotNodeId(),
                Optional.of(chosen.routeId()),
                returnAt,
                returnAt + chosen.runSeconds(),
                VehicleDuty.CloseReason.NO_COMPATIBLE_NEXT));
        return;
      }
    }

    /** 新开的一段超过时长上限：退掉首班，从下一班重新出车；出不了车的班次一并退掉。 */
    private Piece reopenWithoutFirst(Piece piece) {
      drop(piece.trips.get(0), VehicleDutyPlanner.UnassignedReason.EXCEEDS_DUTY_LIMITS);
      List<VehicleDutyPlanner.PlannedTrip> rest = piece.trips.subList(1, piece.trips.size());
      for (int i = 0; i < rest.size(); i++) {
        Piece reopened = open(rest.get(i));
        if (reopened != null) {
          reopened.trips.addAll(rest.subList(i, rest.size()));
          return reopened;
        }
        drop(rest.get(i), VehicleDutyPlanner.UnassignedReason.NO_CREATE_ACCESS);
      }
      return null;
    }

    private VehicleDuty build(
        Piece piece,
        String endDepot,
        Optional<UUID> returnRoute,
        int returnAt,
        int end,
        VehicleDuty.CloseReason reason) {
      pieces++;
      return new VehicleDuty(
          VehicleDutyPlanner.deterministicDutyId(timetableId, duty.dutyCode() + "." + pieces),
          duty.timetableId(),
          duty.sequence(),
          duty.dutyCode() + "." + pieces,
          piece.startDepot,
          endDepot,
          piece.createRoute,
          returnRoute,
          piece.trips.stream().map(VehicleDutyPlanner.PlannedTrip::tripId).toList(),
          piece.startSeconds,
          returnAt,
          end,
          reason,
          consist);
    }

    private void drop(
        VehicleDutyPlanner.PlannedTrip trip, VehicleDutyPlanner.UnassignedReason reason) {
      unassigned.add(new VehicleDutyPlanner.UnassignedTrip(trip.tripId(), trip.tripCode(), reason));
    }
  }

  /**
   * 拆出来的一段交路。
   *
   * @param opened 为接这一段新出的车（不是原交路的出库）
   */
  private static final class Piece {
    private final String startDepot;
    private final Optional<UUID> createRoute;
    private final int startSeconds;
    private final boolean opened;
    private final List<VehicleDutyPlanner.PlannedTrip> trips = new ArrayList<>();

    private Piece(String startDepot, Optional<UUID> createRoute, int startSeconds, boolean opened) {
      this.startDepot = startDepot;
      this.createRoute = createRoute;
      this.startSeconds = startSeconds;
      this.opened = opened;
    }

    private VehicleDutyPlanner.PlannedTrip last() {
      return trips.get(trips.size() - 1);
    }
  }
}
