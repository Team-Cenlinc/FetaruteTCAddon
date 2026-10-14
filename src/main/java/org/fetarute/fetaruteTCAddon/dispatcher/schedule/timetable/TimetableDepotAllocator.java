package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 按同一份图与运行投影比较出入库途径；显式指定、原库偏好、车型与时长约束由派车器先过滤。
 *
 * <p>联编多线时等待相同优先本线的出入库段：只看等待会让共用终点的两条线整体对调车库（各自借对方的库），本线车库反而闲着。
 * 本线候选的等待不比最好的候选多就用本线；本线更挤时照常借用。不放宽成"多等几秒也用本线"：投影是名义班次，让车修复挪动运营车之后，
 * 本线出库多出的那点等待常常落到运营车身上、在没有空股道的让车点让不掉。不知道线路归属（单线编表、外线走行）时只按等待选。
 */
public final class TimetableDepotAllocator implements VehicleDutyPlanner.DepotLegSelector {
  private static final String CREATE_PREFIX = "depot-create:";
  private static final String RETURN_PREFIX = "depot-return:";
  private final Map<String, TimetableConflictChecker.Resource> resources = new LinkedHashMap<>();
  private final Map<UUID, TimetableConflictChecker.RouteProfile> profiles;
  private final TimetableConflictChecker.Footprints footprints;
  private final int separation;
  private final Function<UUID, Optional<UUID>> lineOf;

  /** 基础占用包含名义运营班次与邻表；每次选定的出入库立即加入，后续分配会看到它的咽喉压力。 */
  public TimetableDepotAllocator(
      TimetableConflictChecker.GraphIndex index,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      List<TimetableConflictChecker.Movement> movements,
      List<TimetableConflictChecker.Stay> stays,
      int separationSeconds) {
    this(index, profiles, movements, stays, separationSeconds, route -> Optional.empty());
  }

  /**
   * @param lineOf route（含车型变体）属于哪条线；不知道时为空
   */
  public TimetableDepotAllocator(
      TimetableConflictChecker.GraphIndex index,
      Map<UUID, TimetableConflictChecker.RouteProfile> profiles,
      List<TimetableConflictChecker.Movement> movements,
      List<TimetableConflictChecker.Stay> stays,
      int separationSeconds,
      Function<UUID, Optional<UUID>> lineOf) {
    this.lineOf = lineOf;
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
        trip,
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
        trip,
        candidates,
        leg ->
            new TimetableConflictChecker.Movement(
                RETURN_PREFIX + trip.tripCode(), leg.routeId(), returnAt));
  }

  /** 比较候选真实运行的受影响窗口，等待相同时取本线，并只把选中的路径加入后续分配账本。 */
  private VehicleDutyPlanner.Leg select(
      VehicleDutyPlanner.PlannedTrip trip,
      List<VehicleDutyPlanner.Leg> candidates,
      java.util.function.Function<VehicleDutyPlanner.Leg, TimetableConflictChecker.Movement>
          movementOf) {
    Optional<UUID> homeLine =
        trip.routeId() == null ? Optional.empty() : lineOf.apply(trip.routeId());
    VehicleDutyPlanner.Leg best = candidates.get(0);
    Pressure bestPressure = null;
    VehicleDutyPlanner.Leg bestHome = null;
    Pressure bestHomePressure = null;
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
      Pressure pressure = pressureOf(report.conflicts(), separation);
      if (bestPressure == null || pressure.compareTo(bestPressure) < 0) {
        best = leg;
        bestPressure = pressure;
      }
      if (homeLine.isPresent()
          && homeLine.equals(lineOf.apply(leg.routeId()))
          && (bestHomePressure == null || pressure.compareTo(bestHomePressure) < 0)) {
        bestHome = leg;
        bestHomePressure = pressure;
      }
    }
    if (bestHome != null && bestHomePressure.waitSeconds() <= bestPressure.waitSeconds()) {
      best = bestHome;
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

  /**
   * 一段走行的压力：按相遇计，不按冲突条数计。
   *
   * <p>两辆车在一处交叉相遇时，边与两端道岔各报一条冲突，逐条求和会把同一次相遇算成三次，交叉多的进路被成倍放大。
   * 同一对车、时窗相接（间隔不超过最小间隔）的冲突并成一次相遇，只计其中最长的那段等待——让过最长的那一处，其余几处一起让开了。
   *
   * @param conflicts 这段走行与已有占用的冲突
   * @param separation 最小间隔（秒）
   */
  static Pressure pressureOf(List<TimetableConflictChecker.Conflict> conflicts, int separation) {
    List<TimetableConflictChecker.Conflict> sorted = new ArrayList<>(conflicts);
    sorted.sort(
        Comparator.comparing(TimetableDepotAllocator::pairOf)
            .thenComparingInt(TimetableDepotAllocator::spanFrom)
            .thenComparingInt(TimetableDepotAllocator::spanTo)
            .thenComparing(TimetableConflictChecker.Conflict::resource));
    long total = 0L;
    int encounters = 0;
    String pair = null;
    int until = Integer.MIN_VALUE;
    long longest = 0L;
    for (TimetableConflictChecker.Conflict conflict : sorted) {
      long wait = Math.max(0L, (long) conflict.firstTo() + separation - conflict.secondFrom());
      String key = pairOf(conflict);
      if (key.equals(pair) && spanFrom(conflict) <= (long) until + separation) {
        longest = Math.max(longest, wait);
        until = Math.max(until, spanTo(conflict));
        continue;
      }
      total += longest;
      encounters++;
      pair = key;
      until = spanTo(conflict);
      longest = wait;
    }
    return new Pressure(total + longest, encounters);
  }

  /** 冲突双方，不分先后。 */
  private static String pairOf(TimetableConflictChecker.Conflict conflict) {
    String first = conflict.first();
    String second = conflict.second();
    return first.compareTo(second) <= 0 ? first + "\n" + second : second + "\n" + first;
  }

  private static int spanFrom(TimetableConflictChecker.Conflict conflict) {
    return Math.min(conflict.firstFrom(), conflict.secondFrom());
  }

  private static int spanTo(TimetableConflictChecker.Conflict conflict) {
    return Math.max(conflict.firstTo(), conflict.secondTo());
  }

  /** 等待总量优先，相遇次数打破并列；完全并列保留已排好的显式指定、走行时分、code 序。 */
  record Pressure(long waitSeconds, int conflicts) implements Comparable<Pressure> {
    @Override
    public int compareTo(Pressure other) {
      int wait = Long.compare(waitSeconds, other.waitSeconds);
      return wait == 0 ? Integer.compare(conflicts, other.conflicts) : wait;
    }
  }
}
