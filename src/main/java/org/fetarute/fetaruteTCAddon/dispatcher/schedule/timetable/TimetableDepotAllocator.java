package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 按同一份图与运行投影比较出入库途径；显式指定、原库偏好、车型与时长约束由派车器先过滤。 */
public final class TimetableDepotAllocator implements VehicleDutyPlanner.DepotLegSelector {
  private static final String CREATE_PREFIX = "depot-create:";
  private static final String RETURN_PREFIX = "depot-return:";
  private final Map<String, TimetableConflictChecker.Resource> resources = new LinkedHashMap<>();
  private final Map<UUID, TimetableConflictChecker.RouteProfile> profiles;
  private final TimetableConflictChecker.Footprints footprints;
  private final int separation;

  /** 基础占用包含名义运营班次与邻表；每次选定的出入库立即加入，后续分配会看到它的咽喉压力。 */
  public TimetableDepotAllocator(
      TimetableConflictChecker.GraphIndex index,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      List<TimetableConflictChecker.Movement> movements,
      List<TimetableConflictChecker.Stay> stays,
      int separationSeconds) {
    this.profiles = Map.copyOf(profiles);
    this.footprints = new TimetableConflictChecker.Footprints(index);
    this.separation = Math.max(0, separationSeconds);
    TimetableConflictChecker.projectInto(
        resources, footprints, profiles, movements, stays, TimetableDepotAllocator::vehicleOf);
  }

  @Override
  public VehicleDutyPlanner.Leg selectCreate(
      VehicleDutyPlanner.PlannedTrip trip,
      List<VehicleDutyPlanner.Leg> candidates,
      VehicleDutyPlanner.Limits limits) {
    return select(
        candidates,
        leg ->
            new TimetableConflictChecker.Movement(
                CREATE_PREFIX + trip.tripCode(),
                leg.routeId(),
                VehicleDutyPlanner.openingStart(trip, leg, limits)));
  }

  @Override
  public VehicleDutyPlanner.Leg selectReturn(
      VehicleDutyPlanner.PlannedTrip trip, int returnAt, List<VehicleDutyPlanner.Leg> candidates) {
    return select(
        candidates,
        leg ->
            new TimetableConflictChecker.Movement(
                RETURN_PREFIX + trip.tripCode(), leg.routeId(), returnAt));
  }

  /** 比较候选真实运行的受影响窗口，并只把选中的路径加入后续分配账本。 */
  private VehicleDutyPlanner.Leg select(
      List<VehicleDutyPlanner.Leg> candidates,
      java.util.function.Function<VehicleDutyPlanner.Leg, TimetableConflictChecker.Movement>
          movementOf) {
    VehicleDutyPlanner.Leg best = candidates.get(0);
    Pressure bestPressure = null;
    for (VehicleDutyPlanner.Leg leg : candidates) {
      var movement = movementOf.apply(leg);
      Map<String, TimetableConflictChecker.Resource> trial = new LinkedHashMap<>();
      var windows =
          TimetableConflictChecker.projectInto(
              trial,
              footprints,
              profiles,
              List.of(movement),
              List.of(),
              TimetableDepotAllocator::vehicleOf);
      TimetableConflictChecker.mergeOverlapping(trial, resources, separation);
      var report = TimetableConflictChecker.scanWindows(trial, windows, separation);
      long wait =
          report.conflicts().stream()
              .mapToLong(
                  conflict ->
                      Math.max(0L, (long) conflict.firstTo() + separation - conflict.secondFrom()))
              .sum();
      Pressure pressure = new Pressure(wait, report.conflicts().size());
      if (bestPressure == null || pressure.compareTo(bestPressure) < 0) {
        best = leg;
        bestPressure = pressure;
      }
    }
    TimetableConflictChecker.projectInto(
        resources,
        footprints,
        profiles,
        List.of(movementOf.apply(best)),
        List.of(),
        TimetableDepotAllocator::vehicleOf);
    return best;
  }

  /** 出库与首班、回库与末班分别是同车，接续重叠不能计成两辆车的压力；邻表身份由投影器保留。 */
  private static String vehicleOf(String code) {
    if (code.startsWith(CREATE_PREFIX)) {
      return code.substring(CREATE_PREFIX.length());
    }
    return code.startsWith(RETURN_PREFIX) ? code.substring(RETURN_PREFIX.length()) : code;
  }

  /** 等待总量优先，冲突条数打破并列；完全并列保留已排好的显式指定、走行时分、code 序。 */
  private record Pressure(long waitSeconds, int conflicts) implements Comparable<Pressure> {
    @Override
    public int compareTo(Pressure other) {
      int wait = Long.compare(waitSeconds, other.waitSeconds);
      return wait == 0 ? Integer.compare(conflicts, other.conflicts) : wait;
    }
  }
}
