package org.fetarute.fetaruteTCAddon.api.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.fetarute.fetaruteTCAddon.api.event.TimetableTripCancelledEvent;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaResult;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaTarget;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationPresenceTracker;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableAssignment;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRoutePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TripCancellations;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDuty;

/**
 * TimetableApi 内部实现：只读已发布时刻表的内存快照。
 *
 * <p>只调用 {@link TimetableService} 的纯查询方法；{@code scheduledDepartureAt} 等会建立绑定、写日志的方法一律不碰，
 * 外部插件怎么轮询都不会改变调度状态。
 *
 * <p>车次绑定按 tick 缓存：预计偏差要走一遍 ETA 路径计算，外部插件按“列车数 × 每秒数次”轮询时， 同一 tick 内的重复查询只读第一次的结果。
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
  private final LongSupplier tick;

  /** 当前 tick 的车次绑定结果；换 tick 整份丢弃，所以只占当前在网列车数的内存。 */
  private volatile TickMemo memo = new TickMemo(Long.MIN_VALUE, new ConcurrentHashMap<>());

  /**
   * @param service 时刻表服务（可能尚未初始化）
   * @param stops 实际停靠记录（用于计算当前偏差；可能为空）
   * @param eta ETA 服务（用于预计到下一站的偏差；可能为空）
   * @param tick 服务器当前 tick；同一 tick 内的车次绑定查询共用一份结果
   */
  public TimetableApiImpl(
      Supplier<Optional<TimetableService>> service,
      Supplier<Optional<StationPresenceTracker>> stops,
      Supplier<Optional<EtaService>> eta,
      LongSupplier tick) {
    this.service = Objects.requireNonNull(service, "service");
    this.stops = stops == null ? Optional::empty : stops;
    this.eta = eta == null ? Optional::empty : eta;
    this.tick = Objects.requireNonNull(tick, "tick");
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
    Optional<TimetableService> svc = service.get();
    for (Timetable timetable : published()) {
      if (operatorId != null && !operatorId.equals(timetable.operatorId())) {
        continue;
      }
      collectDepartures(svc, timetable, stationCode.trim(), from, to, out);
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
    return memoized(svc.get(), trainName);
  }

  @Override
  public Collection<TrainAssignment> listAssignments() {
    Optional<TimetableService> svc = service.get();
    if (svc.isEmpty() || !svc.get().settings().enabled()) {
      return List.of();
    }
    return svc.get().assignments().stream()
        .map(a -> memoized(svc.get(), a.trainName()))
        .flatMap(Optional::stream)
        .toList();
  }

  @Override
  public List<CancelledTrip> cancellations(Instant from, Instant to) {
    if (from == null || to == null) {
      return List.of();
    }
    return service.get().map(svc -> svc.cancellationsBetween(from, to)).orElse(List.of()).stream()
        .map(TimetableApiImpl::cancelledTrip)
        .toList();
  }

  private static CancelledTrip cancelledTrip(TripCancellations.Cancellation cancellation) {
    return new CancelledTrip(
        cancellation.timetableId(),
        cancellation.tripId(),
        cancellation.tripCode(),
        cancellation.routeId(),
        cancellation.serviceDate(),
        cancellation.plannedDeparture(),
        TimetableTripCancelledEvent.Scope.valueOf(cancellation.scope().name()),
        cancellation.firstCancelledStopSequence(),
        TimetableTripCancelledEvent.Reason.valueOf(cancellation.reason().name()),
        cancellation.trainName());
  }

  /** 本 tick 内第一次查询时计算，其后直接返回同一份结果。 */
  private Optional<TrainAssignment> memoized(TimetableService svc, String trainName) {
    long now = tick.getAsLong();
    TickMemo current = memo;
    if (current.tick() != now) {
      current = new TickMemo(now, new ConcurrentHashMap<>());
      memo = current;
    }
    return current
        .byTrain()
        .computeIfAbsent(
            trainName.trim().toLowerCase(Locale.ROOT),
            key -> svc.assignmentOf(trainName).map(a -> assignment(svc, a)));
  }

  /**
   * 一个 tick 的查询结果。
   *
   * @param tick 服务器 tick
   * @param byTrain 列车名（小写）→ 车次绑定；未绑定记为空
   */
  private record TickMemo(
      long tick, ConcurrentHashMap<String, Optional<TrainAssignment>> byTrain) {}

  private List<Timetable> published() {
    return service.get().map(TimetableService::publishedTimetables).orElse(List.of());
  }

  private void collectDepartures(
      Optional<TimetableService> svc,
      Timetable timetable,
      String stationCode,
      Instant from,
      Instant to,
      List<Departure> out) {
    LocalDate firstDate = from.atZone(timetable.zoneId()).toLocalDate().minusDays(1);
    LocalDate lastDate = to.atZone(timetable.zoneId()).toLocalDate();
    for (TimetableTrip trip : timetable.trips()) {
      // 区分车型的表：各站时刻按这一班那辆车的车型。
      Optional<TimetableRoutePlan> planOpt = timetable.tripPlan(trip);
      if (planOpt.isEmpty()) {
        continue;
      }
      TimetableRoutePlan plan = planOpt.get();
      Optional<String> consist = timetable.consistOf(trip);
      List<TimetableStop> planStops = plan.stops();
      int lastStopping = lastStoppingIndex(planStops);
      for (int i = 0; i < planStops.size(); i++) {
        TimetableStop stop = planStops.get(i);
        if (!stop.stops() || stop.stationCode().filter(stationCode::equalsIgnoreCase).isEmpty()) {
          continue;
        }
        boolean terminating = i == lastStopping || stop.passType() == RouteStopPassType.TERMINATE;
        for (LocalDate date = firstDate; !date.isAfter(lastDate); date = date.plusDays(1)) {
          Instant base = trip.departureAt(date, timetable.zoneId());
          Instant departure = base.plusSeconds(stop.departureOffsetSeconds());
          if (departure.isBefore(from) || !departure.isBefore(to)) {
            continue;
          }
          LocalDate serviceDate = date;
          // 整趟（或从某站起）取消，或驾驶员在这一站越站，都显示为取消。
          boolean cancelled =
              svc.flatMap(s -> s.cancellationOf(timetable.id(), trip.id(), serviceDate))
                      .map(cancellation -> cancellation.covers(stop.stopSequence()))
                      .orElse(false)
                  || svc.map(
                          s ->
                              s.stopSkipped(
                                  timetable.id(), trip.id(), serviceDate, stop.stopSequence()))
                      .orElse(false);
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
                  date,
                  cancelled,
                  svc.flatMap(s -> s.plannedPlatform(trip.id(), stop.stopSequence())),
                  consist));
        }
      }
    }
  }

  /** 最后一个停车点：其后只剩回库、折返等通过点。它与 TERMINATE 站都是车次终点。 */
  private static int lastStoppingIndex(List<TimetableStop> planStops) {
    for (int i = planStops.size() - 1; i >= 0; i--) {
      if (planStops.get(i).stops()) {
        return i;
      }
    }
    return -1;
  }

  private TrainAssignment assignment(TimetableService svc, TimetableAssignment a) {
    Optional<Timetable> timetable =
        svc.publishedTimetables().stream().filter(t -> t.id().equals(a.timetableId())).findFirst();
    Optional<VehicleDuty> duty = a.dutyId().flatMap(id -> timetable.flatMap(t -> t.duty(id)));
    Optional<String> dutyCode = duty.map(VehicleDuty::dutyCode);
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
    Optional<NextStop> next = timetable.flatMap(t -> nextStop(t, a));
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
        last.map(StationPresenceTracker.StopRecord::nodeId).filter(id -> !id.isBlank()),
        last.flatMap(record -> RouteTerminals.stationCodeOf(record.nodeId())),
        delay,
        next.map(NextStop::stopSequence),
        next.flatMap(NextStop::nodeId),
        next.flatMap(NextStop::stationCode),
        next.map(NextStop::projectedDelaySeconds).orElse(OptionalLong.empty()),
        duty.flatMap(VehicleDuty::consist));
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

  /**
   * 下一个计划停车点，及按 ETA 预计到达它相对计划的偏差。
   *
   * <p>停车点按交路停车方式定（停站 0 秒的 STOP 也算），与 RouteApi 停靠表、HUD 的“下一站”是同一站。 ETA 按序号估算（{@link
   * EtaTarget.StopIndex}）：DYNAMIC 已选台时是实际股道，未选台时是到该站任一候选股道——拿占位股道去算， 列车被分到别的股道时会算错站台，占位股道不可达时干脆算不出。
   */
  private Optional<NextStop> nextStop(Timetable timetable, TimetableAssignment a) {
    Optional<EtaService> etaService = eta.get();
    Optional<TimetableRoutePlan> plan = timetable.routePlan(a.routeId());
    if (etaService.isEmpty() || plan.isEmpty()) {
      return Optional.empty();
    }
    Optional<TrainRuntimeSnapshot> snap = etaService.get().getRuntimeSnapshot(a.trainName());
    if (snap.isEmpty() || !a.routeId().equals(snap.get().routeUuid())) {
      return Optional.empty();
    }
    int index = snap.get().routeIndex();
    Optional<TimetableStop> next =
        plan.get().stops().stream()
            .filter(stop -> stop.stopSequence() > index && stop.stops())
            .findFirst();
    if (next.isEmpty()) {
      return Optional.empty();
    }
    TimetableStop stop = next.get();
    Optional<String> nodeId =
        etaService
            .get()
            .effectiveStopNode(a.trainName(), stop.stopSequence())
            .map(NodeId::value)
            .or(stop::nodeId);
    Optional<String> stationCode =
        nodeId.flatMap(RouteTerminals::stationCodeOf).or(stop::stationCode);
    return Optional.of(
        new NextStop(
            stop.stopSequence(),
            nodeId,
            stationCode,
            projectedDelay(etaService.get(), timetable, a, stop.stopSequence())));
  }

  private static OptionalLong projectedDelay(
      EtaService etaService, Timetable timetable, TimetableAssignment a, int stopSequence) {
    Optional<Instant> planned =
        timetable
            .tripByCode(a.tripCode())
            .flatMap(trip -> timetable.scheduledArrival(trip, stopSequence, a.serviceDate()));
    if (planned.isEmpty()) {
      return OptionalLong.empty();
    }
    EtaResult result = etaService.getForTrain(a.trainName(), new EtaTarget.StopIndex(stopSequence));
    if (result.etaEpochMillis() <= 0L) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(
        Math.floorDiv(result.etaEpochMillis() - planned.get().toEpochMilli(), 1000L));
  }

  /**
   * 下一个计划停车点。
   *
   * @param stopSequence 停靠序号
   * @param nodeId 实际节点（DYNAMIC 未选台为占位股道）
   * @param stationCode 站码
   * @param projectedDelaySeconds 预计到达偏差；ETA 不可用时为空
   */
  private record NextStop(
      int stopSequence,
      Optional<String> nodeId,
      Optional<String> stationCode,
      OptionalLong projectedDelaySeconds) {}

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
    // 区分车型的表里同一条 route 还有各车型的变体计划：对外每条 route 一份，各车型的时分挂在它的 consistStops 里。
    Map<UUID, Map<String, List<StopTime>>> consistStops = new LinkedHashMap<>();
    for (TimetableRoutePlan plan : t.routePlans()) {
      plan.consist()
          .ifPresent(
              variant ->
                  consistStops
                      .computeIfAbsent(plan.routeId(), id -> new LinkedHashMap<>())
                      .put(variant.key(), stopTimes(plan)));
    }
    List<RoutePlan> plans =
        t.routePlans().stream()
            .filter(plan -> plan.consist().isEmpty())
            .map(
                plan ->
                    new RoutePlan(
                        plan.routeId(),
                        plan.routeCode(),
                        plan.kind().name(),
                        stopTimes(plan),
                        consistStops.getOrDefault(plan.routeId(), Map.of())))
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
                        Optional.ofNullable(dutyByTrip.get(trip.id())),
                        t.consistOf(trip)))
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
                        d.plannedEndSecondOfDay(),
                        d.consist()))
            .toList();
    return new TimetableDetail(info(t), plans, trips, duties);
  }

  private static List<StopTime> stopTimes(TimetableRoutePlan plan) {
    return plan.stops().stream()
        .map(
            s ->
                new StopTime(
                    s.stopSequence(),
                    s.stationCode(),
                    s.nodeId(),
                    s.arrivalOffsetSeconds(),
                    s.departureOffsetSeconds(),
                    RouteApi.PassType.valueOf(s.passType().name())))
        .toList();
  }
}
