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
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService.TicketIntent;
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
 * <p>本层出的每张票都带着交路意图（哪个 duty、第几班），并通过三个钩子交给票据分配器：候选过滤（续班只能接本交路的车）、 到期作废（计划时刻 + assign-tolerance
 * 还没车就放弃）、派发回调（把实体车绑到交路上）。出库类票每次放出前还要确认交路没有车在跑：同一交路同一时刻只能有一辆车。
 *
 * <p>交路换车：严重晚点的车被换下后，交路空缺，本层按 {@link TimetableService#replacementsDue} 发替补出库票，派发即绑上交路。
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

  /** 本层出的票：归属、交路意图与到期时刻放在一起，派发成功或作废时整条移除。 */
  private final java.util.concurrent.ConcurrentMap<UUID, OwnedTicket> ownedTickets =
      new java.util.concurrent.ConcurrentHashMap<>();

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
    out.addAll(withoutRunningDuties(drainRetries(now)));
    if (from != null) {
      out.addAll(withoutRunningDuties(buildTimetableTickets(from, now)));
    }
    out.addAll(withoutRunningDuties(buildReplacementTickets(now)));
    return List.copyOf(out);
  }

  /**
   * 交路换车：为空缺的交路发替补出库票。
   *
   * <p>替补票就是一张出库票：走 CREATE 线路从车库实体化一辆车送到它要接的那一班的起点，派发时绑到交路上（{@link #onDispatched}），
   * 之后那一班及后续班次的续班票只接它。发不出（这条出库线路不是可发车服务）就把空缺退回待派；作废时同样退回（{@link #complete}）。
   */
  private List<SpawnTicket> buildReplacementTickets(Instant now) {
    List<TimetableService.Replacement> due = timetableService.replacementsDue(now);
    if (due.isEmpty()) {
      return List.of();
    }
    List<SpawnTicket> out = new ArrayList<>(due.size());
    for (TimetableService.Replacement replacement : due) {
      TicketIntent intent =
          new TicketIntent(
              replacement.timetable().id(),
              replacement.duty().id(),
              replacement.key().serviceDate(),
              RouteOperationType.CREATE,
              replacement.tripIndex());
      Optional<SpawnTicket> built = buildLegTicket(replacement.leg(now));
      if (built.isEmpty()) {
        timetableService.replacementAbandoned(intent);
        continue;
      }
      // 到期取"最晚还来得及的出库时刻"，不按票面 + 容差：替补常常晚了才派，票面 + 容差会比那一班的截止还晚或早得离谱。
      ownedTickets.put(
          built.get().id(),
          new OwnedTicket(
              Optional.of(intent), Optional.of(replacement.latestIssue()), Optional.empty()));
      out.add(built.get());
      debugLogger.accept(
          "TIMETABLE_SPAWN_TICKET kind=CREATE replacement=true duty="
              + replacement.duty().dutyCode()
              + " trip="
              + replacement.trip().tripCode()
              + " route="
              + replacement.createRouteId()
              + " plannedDeparture="
              + replacement.departure()
              + " serviceDate="
              + replacement.key().serviceDate());
    }
    return out;
  }

  /**
   * 交路已经有车在跑时，它的出库票作废：同一交路只能有一辆车。
   *
   * <p>每次放票前都要问，不只在出票那一刻：出库票常常要在车库口重试一两分钟，重启后留在线上的车可能正是在这段时间里 在门控上接下了这个交路。判定本身在 {@link
   * TimetableService#runningVehicleFor}。
   */
  private List<SpawnTicket> withoutRunningDuties(List<SpawnTicket> tickets) {
    if (tickets.isEmpty()) {
      return tickets;
    }
    List<SpawnTicket> kept = new ArrayList<>(tickets.size());
    for (SpawnTicket ticket : tickets) {
      Optional<TicketIntent> intent =
          Optional.ofNullable(ownedTickets.get(ticket.id())).flatMap(OwnedTicket::intent);
      Optional<String> running = intent.flatMap(timetableService::runningVehicleFor);
      if (running.isEmpty()) {
        kept.add(ticket);
        continue;
      }
      ownedTickets.remove(ticket.id());
      debugLogger.accept(
          "TIMETABLE_SPAWN_SKIP reason=duty-already-running kind="
              + intent.get().kind().name()
              + " duty="
              + intent.get().key().describe()
              + " tripIndex="
              + intent.get().tripIndex()
              + " train="
              + running.get()
              + " ticket="
              + ticket.id());
    }
    return kept;
  }

  @Override
  public void requeue(SpawnTicket ticket) {
    if (ticket == null) {
      return;
    }
    if (ownedTickets.containsKey(ticket.id())) {
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
    OwnedTicket owned = ownedTickets.remove(ticket.id());
    if (owned != null) {
      // 派发成功会先经过 onDispatched 把意图摘掉；走到这里还有意图，说明票是被放弃的：运营票这一趟就此取消。
      owned
          .intent()
          .ifPresent(
              intent -> {
                logAbandoned(ticket, intent);
                owned
                    .trip()
                    .ifPresent(due -> timetableService.cancelUndispatched(due, "ticket-abandoned"));
                // 替补出库票作废：空缺退回待派（普通出库票没有空缺，这一步是空操作）。
                timetableService.replacementAbandoned(intent);
              });
      return;
    }
    delegate.complete(ticket);
  }

  private void logAbandoned(SpawnTicket ticket, TicketIntent intent) {
    debugLogger.accept(
        "TIMETABLE_SPAWN_SKIP reason=abandoned kind="
            + intent.kind().name()
            + " duty="
            + intent.key().describe()
            + " tripIndex="
            + intent.tripIndex()
            + " ticket="
            + ticket.id());
  }

  /**
   * 发车侧问：这辆待命车能不能接这张票。不是本层的票一律放行。
   *
   * <p>这是"接班只能接本交路的车、没车就等"在发车侧的落点；判定本身在 {@link TimetableService#acceptsVehicle}。
   */
  public boolean acceptsCandidate(SpawnTicket ticket, String trainName) {
    if (ticket == null || ticket.id() == null || timetableService == null) {
      return true;
    }
    OwnedTicket owned = ownedTickets.get(ticket.id());
    return owned == null
        || owned
            .intent()
            .map(intent -> timetableService.acceptsVehicle(intent, trainName))
            .orElse(true);
  }

  /**
   * 发车侧问：这张票等到什么时候就该放弃。不是本层的票没有到期时刻。
   *
   * <p>到期 = 计划时刻 + assign-tolerance：超过容差还没车，这一班就开天窗，再等下去只会让后面的班次跟着乱。
   */
  public Optional<Instant> expiryOf(SpawnTicket ticket) {
    if (ticket == null || ticket.id() == null) {
      return Optional.empty();
    }
    OwnedTicket owned = ownedTickets.get(ticket.id());
    return owned == null ? Optional.empty() : owned.expiry();
  }

  /** 发车侧回调：本层的票派给了某辆车。把车绑到交路上，意图随之摘掉。 */
  public void onDispatched(SpawnTicket ticket, String trainName) {
    if (ticket == null || ticket.id() == null) {
      return;
    }
    OwnedTicket owned = ownedTickets.get(ticket.id());
    TicketIntent intent = owned == null ? null : owned.intent().orElse(null);
    if (intent == null || timetableService == null) {
      return;
    }
    // 意图摘掉、到期作废：票已经派出去了，之后 complete 不再记 abandoned，也不会再被到期清理。
    ownedTickets.put(ticket.id(), OwnedTicket.dispatched());
    timetableService.bindDuty(
        trainName,
        intent.key(),
        "ticket-" + intent.kind().name().toLowerCase(java.util.Locale.ROOT));
    debugLogger.accept(
        "TIMETABLE_SPAWN_DISPATCHED kind="
            + intent.kind().name()
            + " duty="
            + intent.key().describe()
            + " tripIndex="
            + intent.tripIndex()
            + " train="
            + trainName);
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
      Optional<Instant> expiry = expiryOf(ticket);
      if (expiry.isPresent() && !now.isBefore(expiry.get())) {
        // 重试队列里的票同样受到期约束：过了容差还没派出去的出库/运营票，再发就是一辆没有班次可跑的车。
        complete(ticket);
        continue;
      }
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
                track(
                    built,
                    Optional.of(
                        new TicketIntent(
                            leg.timetable().id(),
                            leg.duty().id(),
                            leg.serviceDate(),
                            leg.kind(),
                            0)),
                    Optional.empty());
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
                track(built, intentOf(trip), Optional.of(trip));
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
        SpawnTicket.TIMETABLE_TRIP_PREFIX
            + due.timetable().code()
            + "-"
            + due.code()
            + "-"
            + due.serviceDate();
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
        SpawnTicket.TIMETABLE_TRIP_PREFIX
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

  /** 运营票的交路意图：它属于哪个 duty、是第几班。没有 duty 的票（不应出现）没有意图，按普通票处理。 */
  private static Optional<TicketIntent> intentOf(TimetableService.DueTrip due) {
    return due.trip()
        .dutyId()
        .flatMap(
            dutyId ->
                due.timetable()
                    .duty(dutyId)
                    .map(
                        duty ->
                            new TicketIntent(
                                due.timetable().id(),
                                dutyId,
                                // 跨零点的班次落在下一个日历日，但它属于前一个服务日的交路：与出库/回库票同一口径。
                                due.timetable().serviceDayOf(due.trip(), due.serviceDate()),
                                RouteOperationType.OPERATION,
                                Math.max(0, duty.tripIds().indexOf(due.trip().id())))));
  }

  /** 登记一张本层的票：有交路意图的票同时带上到期时刻（计划时刻 + assign-tolerance）；运营票带上车次，作废时登记取消。 */
  private void track(
      SpawnTicket ticket, Optional<TicketIntent> intent, Optional<TimetableService.DueTrip> trip) {
    if (ownedTickets.size() >= MAX_TRACKED_TICKETS) {
      // 上限被打到说明 assigner 长期既不 complete 也不 requeue；此时清空只会丢掉“这张是我的”这条信息，
      // 代价是后续 requeue 会误派给 delegate。相比无界增长，这是更可控的退化。
      debugLogger.accept("TIMETABLE_SPAWN_TRACK_RESET size=" + ownedTickets.size());
      ownedTickets.clear();
    }
    Optional<Instant> expiry =
        intent.map(ignored -> ticket.dueAt().plus(timetableService.settings().assignTolerance()));
    ownedTickets.put(ticket.id(), new OwnedTicket(intent, expiry, trip));
  }

  /**
   * 本层一张票的状态。
   *
   * @param intent 交路意图；派发后或没有 duty 的票为空
   * @param expiry 到期时刻；派发后为空
   * @param trip 运营票对应的车次；走行票与派发后为空
   */
  private record OwnedTicket(
      Optional<TicketIntent> intent,
      Optional<Instant> expiry,
      Optional<TimetableService.DueTrip> trip) {
    private OwnedTicket {
      intent = intent == null ? Optional.empty() : intent;
      expiry = expiry == null ? Optional.empty() : expiry;
      trip = trip == null ? Optional.empty() : trip;
    }

    private static OwnedTicket dispatched() {
      return new OwnedTicket(Optional.empty(), Optional.empty(), Optional.empty());
    }
  }
}
