package org.fetarute.fetaruteTCAddon.api.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaResult;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaTarget;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationPresenceTracker;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableAssignment;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRoutePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDuty;

/**
 * TimetableApi 内部实现：只读已发布时刻表的内存快照。
 *
 * <p>只调用 {@link TimetableService} 的纯查询方法；{@code scheduledDepartureAt} 等会建立绑定、写日志的方法一律不碰，
 * 外部插件怎么轮询都不会改变调度状态。
 */
public final class TimetableApiImpl implements TimetableApi {

  private static final Duration MAX_WINDOW = Duration.ofHours(24);
  private static final int DEFAULT_LIMIT = 50;

  /**
   * 促成绑定的那次到站最早可以早于绑定多久。
   *
   * <p>车次在发车门控时才绑定，而这一站的到站发生在停站与按表等点（上限 150 秒）之前。 更早的记录属于上一趟车，不能拿来算本车次的偏差。
   */
  static final Duration BIND_ARRIVAL_LOOKBACK = Duration.ofMinutes(10);

  private final Supplier<Optional<TimetableService>> service;
  private final Supplier<Optional<StationPresenceTracker>> stops;
  private final Supplier<Optional<EtaService>> eta;

  /**
   * @param service 时刻表服务（可能尚未初始化）
   * @param stops 实际停靠记录（用于计算当前偏差；可能为空）
   */
  public TimetableApiImpl(
      Supplier<Optional<TimetableService>> service,
      Supplier<Optional<StationPresenceTracker>> stops) {
    this(service, stops, Optional::empty);
  }

  /**
   * @param service 时刻表服务（可能尚未初始化）
   * @param stops 实际停靠记录（用于计算当前偏差；可能为空）
   * @param eta ETA 服务（用于预计到下一站的偏差；可能为空）
   */
  public TimetableApiImpl(
      Supplier<Optional<TimetableService>> service,
      Supplier<Optional<StationPresenceTracker>> stops,
      Supplier<Optional<EtaService>> eta) {
    this.service = Objects.requireNonNull(service, "service");
    this.stops = stops == null ? Optional::empty : stops;
    this.eta = eta == null ? Optional::empty : eta;
  }

  @Override
  public boolean enabled() {
    return service.get().map(s -> s.settings().enabled()).orElse(false);
  }

  @Override
  public Collection<TimetableInfo> listPublished() {
    return published().stream().map(TimetableApiImpl::info).toList();
  }

  @Override
  public Collection<TimetableInfo> listByLine(UUID lineId) {
    if (lineId == null) {
      return List.of();
    }
    return published().stream()
        .filter(t -> lineId.equals(t.lineId()))
        .map(TimetableApiImpl::info)
        .toList();
  }

  @Override
  public Optional<TimetableDetail> getTimetable(UUID timetableId) {
    if (timetableId == null) {
      return Optional.empty();
    }
    return published().stream()
        .filter(t -> timetableId.equals(t.id()))
        .findFirst()
        .map(TimetableApiImpl::detail);
  }

  @Override
  public List<Departure> departuresAt(
      UUID operatorId, String stationCode, Instant from, Duration window, int limit) {
    if (stationCode == null || stationCode.isBlank() || from == null) {
      return List.of();
    }
    Duration span =
        window == null || window.isNegative() || window.isZero()
            ? Duration.ofMinutes(10)
            : (window.compareTo(MAX_WINDOW) > 0 ? MAX_WINDOW : window);
    Instant to = from.plus(span);
    int max = limit <= 0 ? DEFAULT_LIMIT : limit;
    List<Departure> out = new ArrayList<>();
    for (Timetable timetable : published()) {
      if (operatorId != null && !operatorId.equals(timetable.operatorId())) {
        continue;
      }
      collectDepartures(timetable, stationCode.trim(), from, to, out);
    }
    out.sort(Comparator.comparing(Departure::plannedDeparture).thenComparing(Departure::tripCode));
    return out.size() > max ? List.copyOf(out.subList(0, max)) : List.copyOf(out);
  }

  @Override
  public Optional<TrainAssignment> getAssignment(String trainName) {
    if (trainName == null || trainName.isBlank()) {
      return Optional.empty();
    }
    Optional<TimetableService> svc = service.get();
    if (svc.isEmpty() || !svc.get().settings().enabled()) {
      return Optional.empty();
    }
    return svc.get().assignmentOf(trainName).map(a -> assignment(svc.get(), a));
  }

  @Override
  public Collection<TrainAssignment> listAssignments() {
    Optional<TimetableService> svc = service.get();
    if (svc.isEmpty() || !svc.get().settings().enabled()) {
      return List.of();
    }
    return svc.get().assignments().stream().map(a -> assignment(svc.get(), a)).toList();
  }

  private List<Timetable> published() {
    return service.get().map(TimetableService::publishedTimetables).orElse(List.of());
  }

  private void collectDepartures(
      Timetable timetable, String stationCode, Instant from, Instant to, List<Departure> out) {
    LocalDate firstDate = from.atZone(timetable.zoneId()).toLocalDate().minusDays(1);
    LocalDate lastDate = to.atZone(timetable.zoneId()).toLocalDate();
    for (TimetableTrip trip : timetable.trips()) {
      Optional<TimetableRoutePlan> planOpt = timetable.routePlan(trip.routeId());
      if (planOpt.isEmpty()) {
        continue;
      }
      TimetableRoutePlan plan = planOpt.get();
      List<TimetableStop> planStops = plan.stops();
      for (int i = 0; i < planStops.size(); i++) {
        TimetableStop stop = planStops.get(i);
        if (stop.stationCode().filter(stationCode::equalsIgnoreCase).isEmpty()) {
          continue;
        }
        boolean terminating = i == planStops.size() - 1;
        if (!stopsHere(stop, i, planStops.size())) {
          continue;
        }
        for (LocalDate date = firstDate; !date.isAfter(lastDate); date = date.plusDays(1)) {
          Instant base = trip.departureAt(date, timetable.zoneId());
          Instant departure = base.plusSeconds(stop.departureOffsetSeconds());
          if (departure.isBefore(from) || !departure.isBefore(to)) {
            continue;
          }
          out.add(
              new Departure(
                  timetable.id(),
                  timetable.lineId(),
                  trip.routeId(),
                  plan.routeCode(),
                  trip.tripCode(),
                  stop.stopSequence(),
                  stop.nodeId(),
                  base.plusSeconds(stop.arrivalOffsetSeconds()),
                  departure,
                  terminating,
                  date));
        }
      }
    }
  }

  /** 车次在该点停车：通过站到发时刻相同；起点与终点即使停站时分为 0 也算。 */
  private static boolean stopsHere(TimetableStop stop, int position, int count) {
    return position == 0
        || position == count - 1
        || stop.departureOffsetSeconds() > stop.arrivalOffsetSeconds();
  }

  private TrainAssignment assignment(TimetableService svc, TimetableAssignment a) {
    Optional<Timetable> timetable =
        svc.publishedTimetables().stream().filter(t -> t.id().equals(a.timetableId())).findFirst();
    Optional<String> dutyCode =
        a.dutyId().flatMap(id -> timetable.flatMap(t -> t.duty(id))).map(VehicleDuty::dutyCode);
    Optional<StationPresenceTracker.StopRecord> last =
        stops
            .get()
            .flatMap(tracker -> tracker.lastStop(a.trainName()))
            .filter(record -> belongsToTrip(record, a));
    OptionalLong delay = OptionalLong.empty();
    if (last.isPresent() && timetable.isPresent()) {
      StationPresenceTracker.StopRecord record = last.get();
      Optional<TimetableTrip> trip = timetable.get().tripByCode(a.tripCode());
      Optional<Instant> planned =
          trip.flatMap(
              t ->
                  record.arrival()
                      ? timetable.get().scheduledArrival(t, record.stopIndex(), a.serviceDate())
                      : timetable.get().scheduledDeparture(t, record.stopIndex(), a.serviceDate()));
      if (planned.isPresent()) {
        delay = OptionalLong.of(Duration.between(planned.get(), record.at()).getSeconds());
      }
    }
    Optional<Projection> projection = timetable.flatMap(t -> project(t, a));
    return new TrainAssignment(
        a.trainName(),
        a.timetableId(),
        a.tripCode(),
        a.routeId(),
        dutyCode,
        a.serviceDate(),
        a.assignedAt(),
        a.initialDeviationSeconds(),
        last.map(StationPresenceTracker.StopRecord::stopIndex),
        delay,
        projection.map(Projection::stopSequence),
        projection.map(p -> OptionalLong.of(p.delaySeconds())).orElse(OptionalLong.empty()));
  }

  /** 到发记录是否属于这个车次：同一交路，且发生在绑定之后，或正是促成绑定的那次到站。 */
  static boolean belongsToTrip(StationPresenceTracker.StopRecord record, TimetableAssignment a) {
    if (record.routeUuid().filter(a.routeId()::equals).isEmpty()) {
      return false;
    }
    if (!record.at().isBefore(a.assignedAt())) {
      return true;
    }
    return record.arrival()
        && record.stopIndex() == a.assignedAtStopIndex()
        && !record.at().isBefore(a.assignedAt().minus(BIND_ARRIVAL_LOOKBACK));
  }

  /** 按 ETA 预计到达下一个计划停靠点，与计划到达相减。 */
  private Optional<Projection> project(Timetable timetable, TimetableAssignment a) {
    Optional<EtaService> etaService = eta.get();
    Optional<TimetableTrip> trip = timetable.tripByCode(a.tripCode());
    Optional<TimetableRoutePlan> plan = timetable.routePlan(a.routeId());
    if (etaService.isEmpty() || trip.isEmpty() || plan.isEmpty()) {
      return Optional.empty();
    }
    Optional<TrainRuntimeSnapshot> snap = etaService.get().getRuntimeSnapshot(a.trainName());
    if (snap.isEmpty() || !a.routeId().equals(snap.get().routeUuid())) {
      return Optional.empty();
    }
    int index = snap.get().routeIndex();
    List<TimetableStop> planStops = plan.get().stops();
    Optional<TimetableStop> next = Optional.empty();
    for (int i = 0; i < planStops.size(); i++) {
      TimetableStop stop = planStops.get(i);
      if (stop.stopSequence() > index
          && stop.nodeId().isPresent()
          && stopsHere(stop, i, planStops.size())) {
        next = Optional.of(stop);
        break;
      }
    }
    if (next.isEmpty()) {
      return Optional.empty();
    }
    Optional<Instant> planned =
        timetable.scheduledArrival(trip.get(), next.get().stopSequence(), a.serviceDate());
    EtaResult result =
        etaService
            .get()
            .getForTrain(
                a.trainName(), new EtaTarget.PlatformNode(NodeId.of(next.get().nodeId().get())));
    if (planned.isEmpty() || result.etaEpochMillis() <= 0L) {
      return Optional.empty();
    }
    long delay = Math.floorDiv(result.etaEpochMillis() - planned.get().toEpochMilli(), 1000L);
    return Optional.of(new Projection(next.get().stopSequence(), delay));
  }

  private record Projection(int stopSequence, long delaySeconds) {}

  private static TimetableInfo info(Timetable t) {
    return new TimetableInfo(
        t.id(),
        t.operatorId(),
        t.lineId(),
        t.code(),
        t.name(),
        t.zoneId().getId(),
        t.serviceStartSecondOfDay(),
        t.serviceEndSecondOfDay(),
        List.copyOf(t.routeIds()),
        t.trips().size(),
        t.duties().size(),
        t.updatedAt());
  }

  private static TimetableDetail detail(Timetable t) {
    Map<UUID, String> dutyByTrip =
        t.duties().stream()
            .flatMap(d -> d.tripIds().stream().map(id -> Map.entry(id, d.dutyCode())))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (x, y) -> x));
    Map<UUID, String> tripCodeById =
        t.trips().stream()
            .collect(Collectors.toMap(TimetableTrip::id, TimetableTrip::tripCode, (x, y) -> x));
    List<RoutePlan> plans =
        t.routePlans().stream()
            .map(
                plan ->
                    new RoutePlan(
                        plan.routeId(),
                        plan.routeCode(),
                        plan.kind().name(),
                        plan.stops().stream()
                            .map(
                                s ->
                                    new StopTime(
                                        s.stopSequence(),
                                        s.stationCode(),
                                        s.nodeId(),
                                        s.arrivalOffsetSeconds(),
                                        s.departureOffsetSeconds()))
                            .toList()))
            .toList();
    List<Trip> trips =
        t.trips().stream()
            .sorted(
                Comparator.comparingInt(TimetableTrip::departureSecondOfDay)
                    .thenComparing(TimetableTrip::tripCode))
            .map(
                trip ->
                    new Trip(
                        trip.id(),
                        trip.routeId(),
                        trip.tripCode(),
                        trip.departureSecondOfDay(),
                        Optional.ofNullable(dutyByTrip.get(trip.id()))))
            .toList();
    List<Duty> duties =
        t.duties().stream()
            .map(
                d ->
                    new Duty(
                        d.id(),
                        d.dutyCode(),
                        d.startDepotNodeId(),
                        d.endDepotNodeId(),
                        d.tripIds().stream()
                            .map(tripCodeById::get)
                            .filter(Objects::nonNull)
                            .toList(),
                        d.plannedStartSecondOfDay(),
                        d.plannedEndSecondOfDay()))
            .toList();
    return new TimetableDetail(info(t), plans, trips, duties);
  }
}
