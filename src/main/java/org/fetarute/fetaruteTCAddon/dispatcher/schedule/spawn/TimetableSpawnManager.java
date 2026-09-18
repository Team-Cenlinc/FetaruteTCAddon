package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 让已发布时刻表接管发车出票的装饰器。
 *
 * <p>它包住现有的 {@link StorageSpawnManager}，对受时刻表管辖的 route 做两件事：把 headway 出的票拦下来，按时刻表的发车时刻另出一张。 其余
 * route 完全原样透传。没有任何一份时刻表处于 PUBLISHED 时，本类对发车链路是不可见的。
 *
 * <p>为什么是装饰器而不是改 {@link StorageSpawnManager}：发车是这套系统里最难回滚的一段：
 * 一旦多发或漏发，纠正手段只有人工销毁列车。装饰器让“关掉按表运行”退化成不装配这一层， 而不是依赖一个布尔分支在几百行状态机里到处判断。
 *
 * <p><b>前提</b>：时刻表只提供“几点发车”，不提供“从哪发、算谁的”。出库点、线路/运营商 code 仍然取自 {@link StorageSpawnManager}
 * 的计划快照。因此一条 route 必须本来就是可发车服务（配了 depot 与 spawn 开关）， 时刻表才能驱动它——否则本层会跳过并留下审计记录，而不是猜一个 depot。
 */
public final class TimetableSpawnManager
    implements SpawnManager, SpawnForecastSupport, SpawnResetSupport {

  /** 自有票据追踪上限，防止 assigner 长期不回收时无界增长。 */
  private static final int MAX_TRACKED_TICKETS = 512;

  private final SpawnManager delegate;
  private final TimetableService timetableService;
  private final Consumer<String> debugLogger;

  private final ConcurrentLinkedQueue<SpawnTicket> retryQueue = new ConcurrentLinkedQueue<>();
  private final Set<UUID> ownedTickets = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final AtomicLong sequence = new AtomicLong();
  private final Set<UUID> missingServiceWarned = java.util.concurrent.ConcurrentHashMap.newKeySet();

  private volatile Instant lastPoll;

  public TimetableSpawnManager(
      SpawnManager delegate, TimetableService timetableService, Consumer<String> debugLogger) {
    this.delegate = delegate;
    this.timetableService = timetableService;
    this.debugLogger = debugLogger == null ? message -> {} : debugLogger;
  }

  @Override
  public List<SpawnTicket> pollDueTickets(StorageProvider provider, Instant now) {
    if (delegate == null) {
      return List.of();
    }
    // 先让 delegate 跑：它顺带刷新了计划快照，而本层要靠那份快照拿到 depot 与各级 code。
    List<SpawnTicket> delegated = delegate.pollDueTickets(provider, now);
    if (timetableService == null || now == null) {
      return delegated;
    }
    Set<UUID> managed = managedRoutes();
    if (managed.isEmpty()) {
      lastPoll = now;
      return delegated;
    }

    List<SpawnTicket> out = new ArrayList<>(delegated.size());
    for (SpawnTicket ticket : delegated) {
      if (ticket != null
          && ticket.service() != null
          && managed.contains(ticket.service().routeId())) {
        // 拦下 headway 票，同时向 delegate 报完成：否则它的 backlog 会一直涨到上限然后停止生成，
        // 等到时刻表被下架时，这条 route 会静悄悄地一辆车都发不出来。
        delegate.complete(ticket);
        continue;
      }
      out.add(ticket);
    }

    Instant from = lastPoll;
    lastPoll = now;
    out.addAll(drainRetries(now));
    if (from != null) {
      out.addAll(buildTimetableTickets(from, now));
    }
    return List.copyOf(out);
  }

  @Override
  public void requeue(SpawnTicket ticket) {
    if (ticket == null) {
      return;
    }
    if (ownedTickets.contains(ticket.id())) {
      retryQueue.add(ticket);
      return;
    }
    delegate.requeue(ticket);
  }

  @Override
  public void complete(SpawnTicket ticket) {
    if (ticket == null) {
      return;
    }
    if (ownedTickets.remove(ticket.id())) {
      return;
    }
    delegate.complete(ticket);
  }

  @Override
  public SpawnPlan snapshotPlan() {
    return delegate.snapshotPlan();
  }

  @Override
  public List<SpawnTicket> snapshotQueue() {
    List<SpawnTicket> out = new ArrayList<>(delegate.snapshotQueue());
    out.addAll(retryQueue);
    return List.copyOf(out);
  }

  @Override
  public ReplacementSnapshot snapshotForReplacement() {
    return delegate.snapshotForReplacement();
  }

  @Override
  public void restoreForReplacement(
      ReplacementSnapshot snapshot, List<SpawnTicket> additionalTickets, Instant restoredAt) {
    delegate.restoreForReplacement(snapshot, additionalTickets, restoredAt);
  }

  @Override
  public SpawnResetResult reset(Instant now) {
    int cleared = retryQueue.size();
    retryQueue.clear();
    ownedTickets.clear();
    missingServiceWarned.clear();
    lastPoll = now;
    if (delegate instanceof SpawnResetSupport resetSupport) {
      SpawnResetResult inner = resetSupport.reset(now);
      return new SpawnResetResult(
          inner.clearedQueue() + cleared, inner.clearedStates(), inner.planReset());
    }
    return new SpawnResetResult(cleared, 0, false);
  }

  @Override
  public List<SpawnTicket> snapshotForecast(Instant now, Duration horizon, int maxPerService) {
    List<SpawnTicket> delegated =
        delegate instanceof SpawnForecastSupport forecast
            ? forecast.snapshotForecast(now, horizon, maxPerService)
            : List.of();
    if (timetableService == null || now == null || horizon == null || horizon.isNegative()) {
      return delegated;
    }
    Set<UUID> managed = managedRoutes();
    if (managed.isEmpty()) {
      return delegated;
    }
    // 受管辖 route 的 headway 预测是错的——那条线已经不按 headway 发车了。换成表定时刻。
    List<SpawnTicket> out = new ArrayList<>();
    for (SpawnTicket ticket : delegated) {
      if (ticket == null
          || ticket.service() == null
          || !managed.contains(ticket.service().routeId())) {
        out.add(ticket);
      }
    }
    for (TimetableService.DueTrip due : timetableService.tripsBetween(now, now.plus(horizon))) {
      buildTicket(due).ifPresent(out::add);
    }
    for (TimetableService.DueLeg due : timetableService.legsBetween(now, now.plus(horizon))) {
      buildLegTicket(due).ifPresent(out::add);
    }
    return List.copyOf(out);
  }

  private Set<UUID> managedRoutes() {
    TimetableService.Settings settings = timetableService.settings();
    if (!settings.enabled() || !settings.spawnEnabled()) {
      return Set.of();
    }
    return new HashSet<>(timetableService.managedRoutes());
  }

  private List<SpawnTicket> drainRetries(Instant now) {
    if (retryQueue.isEmpty()) {
      return List.of();
    }
    List<SpawnTicket> ready = new ArrayList<>();
    List<SpawnTicket> deferred = new ArrayList<>();
    SpawnTicket ticket;
    while ((ticket = retryQueue.poll()) != null) {
      if (ticket.notBefore().isAfter(now)) {
        deferred.add(ticket);
      } else {
        ready.add(ticket);
      }
    }
    retryQueue.addAll(deferred);
    return ready;
  }

  private List<SpawnTicket> buildTimetableTickets(Instant from, Instant to) {
    List<TimetableService.DueTrip> due = timetableService.dueTrips(from, to);
    List<TimetableService.DueLeg> legs = timetableService.dueLegs(from, to);
    if (due.isEmpty() && legs.isEmpty()) {
      return List.of();
    }
    List<SpawnTicket> out = new ArrayList<>(due.size() + legs.size());
    // 走行票先入队：同一秒里出库票排在运营票前面，首班才接得上刚到站的车。
    for (TimetableService.DueLeg leg : legs) {
      buildLegTicket(leg)
          .ifPresent(
              built -> {
                trackTicket(built.id());
                out.add(built);
                debugLogger.accept(
                    "TIMETABLE_SPAWN_TICKET kind="
                        + leg.kind().name()
                        + " duty="
                        + leg.duty().dutyCode()
                        + " route="
                        + leg.routeId()
                        + " plannedDeparture="
                        + leg.departure()
                        + " serviceDate="
                        + leg.serviceDate());
              });
    }
    for (TimetableService.DueTrip trip : due) {
      buildTicket(trip)
          .ifPresent(
              built -> {
                trackTicket(built.id());
                out.add(built);
                debugLogger.accept(
                    "TIMETABLE_SPAWN_TICKET kind=OPERATION trip="
                        + trip.trip().tripCode()
                        + " duty="
                        + trip.trip()
                            .dutyId()
                            .flatMap(trip.timetable()::duty)
                            .map(d -> d.dutyCode())
                            .orElse("-")
                        + " route="
                        + trip.trip().routeId()
                        + " plannedDeparture="
                        + trip.departure()
                        + " serviceDate="
                        + trip.serviceDate());
              });
    }
    return List.copyOf(out);
  }

  /**
   * 把一个 duty 的出库/回库走行变成可入队的票据。
   *
   * <p>出库票走 CREATE 线路从车库实体化一辆车送到首站；回库票走 RETURN 线路把跑完交路的车送回车库。 两者都必须本来就是可发车服务，否则跳过并只警告一次——理由同运营票。
   */
  private Optional<SpawnTicket> buildLegTicket(TimetableService.DueLeg due) {
    Optional<SpawnService> service = findService(due.routeId());
    if (service.isEmpty()) {
      if (missingServiceWarned.add(due.routeId())) {
        debugLogger.accept(
            "TIMETABLE_SPAWN_SKIP reason=no-spawn-service kind="
                + due.kind().name()
                + " route="
                + due.routeId()
                + " duty="
                + due.duty().dutyCode()
                + " timetable="
                + due.timetable().code());
      }
      return Optional.empty();
    }
    String tripId =
        "TIMETABLE-" + due.timetable().code() + "-" + due.code() + "-" + due.serviceDate();
    return Optional.of(
        new SpawnTicket(
            UUID.randomUUID(),
            service.get(),
            due.departure(),
            due.departure(),
            0,
            sequence.incrementAndGet(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(tripId),
            TripSource.SCHEDULED,
            0));
  }

  /**
   * 把一趟表定车次变成可入队的票据。
   *
   * <p>找不到对应的 {@link SpawnService} 时返回空并只警告一次：这条 route 没被配置成可发车服务， 重复刷屏没有意义，而静默跳过又会让人以为时刻表在工作。
   */
  private Optional<SpawnTicket> buildTicket(TimetableService.DueTrip due) {
    UUID routeId = due.trip().routeId();
    Optional<SpawnService> service = findService(routeId);
    if (service.isEmpty()) {
      if (missingServiceWarned.add(routeId)) {
        debugLogger.accept(
            "TIMETABLE_SPAWN_SKIP reason=no-spawn-service route="
                + routeId
                + " timetable="
                + due.timetable().code());
      }
      return Optional.empty();
    }
    Optional<String> depotOverride =
        due.timetable().routePlan(routeId).flatMap(plan -> plan.depotNodeId());
    String tripId =
        "TIMETABLE-"
            + due.timetable().code()
            + "-"
            + due.trip().tripCode()
            + "-"
            + due.serviceDate();
    return Optional.of(
        new SpawnTicket(
            UUID.randomUUID(),
            depotOverride.map(node -> withDepot(service.get(), node)).orElse(service.get()),
            due.departure(),
            due.departure(),
            0,
            sequence.incrementAndGet(),
            depotOverride,
            Optional.empty(),
            Optional.of(tripId),
            TripSource.SCHEDULED,
            0));
  }

  /** 车次指定了出库点时覆盖服务默认 depot；其余字段保持不变。 */
  private static SpawnService withDepot(SpawnService service, String depotNodeId) {
    if (depotNodeId == null || depotNodeId.isBlank()) {
      return service;
    }
    return new SpawnService(
        service.key(),
        service.companyId(),
        service.companyCode(),
        service.operatorId(),
        service.operatorCode(),
        service.lineId(),
        service.lineCode(),
        service.routeId(),
        service.routeCode(),
        service.baseHeadway(),
        depotNodeId);
  }

  private Optional<SpawnService> findService(UUID routeId) {
    SpawnPlan plan = delegate.snapshotPlan();
    if (plan == null || routeId == null) {
      return Optional.empty();
    }
    for (SpawnService service : plan.services()) {
      if (service != null && routeId.equals(service.routeId())) {
        return Optional.of(service);
      }
    }
    return Optional.empty();
  }

  private void trackTicket(UUID ticketId) {
    if (ownedTickets.size() >= MAX_TRACKED_TICKETS) {
      // 上限被打到说明 assigner 长期既不 complete 也不 requeue；此时清空只会丢掉“这张是我的”这条信息，
      // 代价是后续 requeue 会误派给 delegate。相比无界增长，这是更可控的退化。
      debugLogger.accept("TIMETABLE_SPAWN_TRACK_RESET size=" + ownedTickets.size());
      ownedTickets.clear();
    }
    ownedTickets.add(ticketId);
  }
}
