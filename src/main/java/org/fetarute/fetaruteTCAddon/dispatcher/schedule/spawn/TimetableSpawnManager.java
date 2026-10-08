package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    implements SpawnManager, SpawnForecastSupport, SpawnResetSupport, DutyContinuitySupport {

  /** 自有票据追踪上限，防止 assigner 长期不回收时无界增长。 */
  private static final int MAX_TRACKED_TICKETS = 512;

  /** 提前出车查车库使用时，每条按间隔发车的线路最多预测几班。 */
  private static final int MAX_FORECAST_PER_SERVICE = 20;

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

  /**
   * 最近一次预测出的票据各属于哪个交路的哪一班：预测票不入队，站牌要靠它找到来车。每次预测整张替换。
   *
   * <p>按车次标识（{@link SpawnTicket#serviceTripId()}）记，不按票据 ID：预测票每次重新生成、ID 随之变，
   * 两次预测交错（例如外部插件在别的线程查站牌）时按 ID 记的会互相顶掉，同一车次的标识则不变。
   */
  private volatile Map<String, TicketIntent> forecastIntents = Map.of();

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
   * 这张票要开的运营车次：运营票是它自己那一班，出库走行票是它要去接的那一班；回库票、别层的票为空。
   *
   * <p>驾驶员接车据此判断这张票是不是自己领的那一班：终点站待命车派车前、车库出车时。
   */
  public Optional<TimetableService.DueTrip> pickupTripOf(SpawnTicket ticket) {
    if (ticket == null || ticket.id() == null || timetableService == null) {
      return Optional.empty();
    }
    OwnedTicket owned = ownedTickets.get(ticket.id());
    if (owned == null) {
      return Optional.empty();
    }
    if (owned.trip().isPresent()) {
      return owned.trip();
    }
    return owned
        .intent()
        .filter(intent -> intent.kind() == RouteOperationType.CREATE)
        .flatMap(timetableService::tripOfIntent);
  }

  /**
   * 发车侧问：这张票等到什么时候就该放弃。不是本层的票没有到期时刻。
   *
   * <p>到期 = 计划时刻 + assign-tolerance：超过容差还没车，这一班就开天窗，再等下去只会让后面的班次跟着乱。
   *
   * <p>例外是续班票与回库票还在等本交路的车（{@link TimetableService#awaitsOwnVehicle}）：车在路上就不到期，晚点就晚发。
   * 那一班别的车本来就接不了，作废它只会让车到了终点无班可接、占着站台。
   */
  public Optional<Instant> expiryOf(SpawnTicket ticket) {
    if (ticket == null || ticket.id() == null) {
      return Optional.empty();
    }
    OwnedTicket owned = ownedTickets.get(ticket.id());
    if (owned == null || owned.intent().filter(timetableService::awaitsOwnVehicle).isPresent()) {
      return Optional.empty();
    }
    return owned.expiry();
  }

  /**
   * 本层还有没有这个交路意图的票在等车：出了票、还没派出，也没作废。
   *
   * <p>装进 {@link TimetableService#setPendingTicketProbe}：续班票不按时刻到期以后，"过了容差"不再等于"接不上"，回库闸要据此判断。
   *
   * @param intent 交路意图
   * @return 有这样一张票时为 true
   */
  public boolean hasPendingTicket(TicketIntent intent) {
    if (intent == null) {
      return false;
    }
    for (OwnedTicket owned : ownedTickets.values()) {
      if (owned.intent().filter(intent::equals).isPresent()) {
        return true;
      }
    }
    return false;
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
    timetableService.bindDispatchedTrip(trainName, intent, owned.trip());
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

  /** 手动提前出车的结果类别。 */
  public enum EarlySpawnOutcome {
    /** 已出票：车照常经发车侧出库，在车库等到计划时刻再走。 */
    ISSUED,
    /** 这条线路不归时刻表发车。 */
    NOT_TIMETABLED,
    /** 这条线路没有设成可发车服务，出不了票。 */
    NO_SERVICE,
    /** 时限内没有从这个车库按这条线路开出的班次。 */
    NONE_UPCOMING,
    /** 下一班已经出票或已有车在跑：只提前出下一班，不往后找。 */
    ALREADY_OUT,
    /** 离下一班的计划发车还早于最早提前量。 */
    TOO_EARLY,
    /** 车库没有可用股道，或占用后会挡住别的车出库、回库。 */
    BLOCKED
  }

  /**
   * 手动提前出车的结果。
   *
   * @param outcome 结果类别
   * @param tripCode 下一班的车次（出库走行为它要去接的那一班，查不到时为交路号）；没找到下一班时为空串
   * @param plannedDeparture 下一班计划从车库发车的时刻；没找到下一班时为空
   * @param depotNode 出库的股道（仅 {@link EarlySpawnOutcome#ISSUED}）
   * @param blocker 选不出股道的原因（仅 {@link EarlySpawnOutcome#BLOCKED}）
   */
  public record EarlySpawn(
      EarlySpawnOutcome outcome,
      String tripCode,
      Optional<Instant> plannedDeparture,
      Optional<String> depotNode,
      Optional<EarlySpawnYard.Blocker> blocker) {
    private static EarlySpawn of(EarlySpawnOutcome outcome) {
      return new EarlySpawn(outcome, "", Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static EarlySpawn about(EarlySpawnOutcome outcome, EarlyTarget target) {
      return new EarlySpawn(
          outcome,
          target.tripCode(),
          Optional.of(target.departure()),
          Optional.empty(),
          Optional.empty());
    }
  }

  /**
   * 手动提前出车：这条线路从这个车库开出的下一班（交路的出库走行，或首站就是车库的车次）现在就出票，计划时刻不变。
   *
   * <p>只提前下一班：下一班已经出票或已有车在跑时不往后找；离计划发车超过 {@code maxLead} 时不出。车在车库股道上一直等到计划时刻， 出库点由 {@code chooser}
   * 挑选：出库点写成 DYNAMIC 时在车库里挑一条，固定写法时用指定的股道；占掉后会挡住别的车出库、回库就不出。
   *
   * <p>票照常经发车侧实体化（闭塞、车队上限、启动恢复照查），出车后在车库扣到计划时刻再走（见 {@link SimpleTicketAssigner}）。 这一班到点时不再出第二张票。
   *
   * @param routeId 线路：出库走行线路、首班的载客线路，或首站就是车库的线路
   * @param depotNodeId 命令里指定的车库节点
   * @param now 当前时刻
   * @param horizon 往后找下一班找多远
   * @param maxLead 最多提前多久
   * @param chooser 挑股道
   */
  public EarlySpawn issueEarly(
      UUID routeId,
      String depotNodeId,
      Instant now,
      Duration horizon,
      Duration maxLead,
      EarlySpawnPlan.TrackChooser chooser) {
    if (timetableService == null
        || routeId == null
        || depotNodeId == null
        || now == null
        || horizon == null
        || maxLead == null
        || chooser == null
        || !managedRoutes().contains(routeId)) {
      return EarlySpawn.of(EarlySpawnOutcome.NOT_TIMETABLED);
    }
    Optional<EarlyTarget> next = nextDepotDeparture(routeId, depotNodeId, now, now.plus(horizon));
    if (next.isEmpty()) {
      return EarlySpawn.of(EarlySpawnOutcome.NONE_UPCOMING);
    }
    EarlyTarget target = next.get();
    if (alreadyIssued(target.intent())) {
      return EarlySpawn.about(EarlySpawnOutcome.ALREADY_OUT, target);
    }
    if (target.departure().isAfter(now.plus(maxLead))) {
      return EarlySpawn.about(EarlySpawnOutcome.TOO_EARLY, target);
    }
    Optional<SpawnTicket> built =
        target.leg().isPresent()
            ? buildLegTicket(target.leg().get())
            : target.trip().flatMap(this::buildTicket);
    if (built.isEmpty()) {
      return EarlySpawn.of(EarlySpawnOutcome.NO_SERVICE);
    }
    SpawnTicket planned = built.get();
    SpawnTicket draft =
        new SpawnTicket(
                planned.id(),
                planned.service(),
                planned.dueAt(),
                now,
                0,
                planned.sequenceNumber(),
                planned.selectedDepotNodeId(),
                Optional.empty(),
                planned.serviceTripId(),
                TripSource.MANUAL,
                planned.priority())
            .withConsist(planned.consist());
    EarlySpawnPlan plan =
        new EarlySpawnPlan(
            draft,
            depotNodeId,
            target.tripCode(),
            target.departure(),
            departuresBefore(now, target),
            arrivalsBefore(now, target));
    EarlySpawnYard.Decision decision = chooser.choose(plan);
    if (decision.blocker().isPresent()) {
      debugLogger.accept(
          "TIMETABLE_SPAWN_EARLY_BLOCKED trip="
              + target.tripCode()
              + " route="
              + planned.service().routeId()
              + " depot="
              + depotNodeId
              + " reason="
              + decision.blocker().get());
      return new EarlySpawn(
          EarlySpawnOutcome.BLOCKED,
          target.tripCode(),
          Optional.of(target.departure()),
          Optional.empty(),
          decision.blocker());
    }
    String track = decision.track().orElseThrow();
    SpawnTicket early =
        new SpawnTicket(
                draft.id(),
                withDepot(draft.service(), track),
                draft.dueAt(),
                now,
                0,
                draft.sequenceNumber(),
                Optional.of(track),
                Optional.empty(),
                draft.serviceTripId(),
                TripSource.MANUAL,
                draft.priority())
            .withConsist(draft.consist());
    track(early, Optional.of(target.intent()), target.trip());
    retryQueue.add(early);
    debugLogger.accept(
        "TIMETABLE_SPAWN_EARLY trip="
            + target.tripCode()
            + " route="
            + planned.service().routeId()
            + " depot="
            + track
            + " plannedDeparture="
            + planned.dueAt()
            + " ticket="
            + early.id());
    return new EarlySpawn(
        EarlySpawnOutcome.ISSUED,
        target.tripCode(),
        Optional.of(target.departure()),
        Optional.of(track),
        Optional.empty());
  }

  /**
   * 提前出车要开的那一班。
   *
   * @param intent 交路意图
   * @param leg 出库走行；首站就是车库的车次为空
   * @param trip 首站就是车库的车次；出库走行为空
   * @param departure 计划从车库发车的时刻
   * @param tripCode 车次（出库走行为它要去接的那一班，查不到时为交路号）
   */
  private record EarlyTarget(
      TicketIntent intent,
      Optional<TimetableService.DueLeg> leg,
      Optional<TimetableService.DueTrip> trip,
      Instant departure,
      String tripCode) {}

  /** 这条线路从这个车库开出的下一班：已出票、已有车在跑的也算（交给调用方判断），整趟取消的不算。 */
  private Optional<EarlyTarget> nextDepotDeparture(
      UUID routeId, String depotNodeId, Instant now, Instant to) {
    TimetableService.DueLeg nextLeg = null;
    for (TimetableService.DueLeg leg : timetableService.legsBetween(now, to)) {
      String plannedDepot = leg.duty().startDepotNodeId();
      if (leg.kind() == RouteOperationType.CREATE
          && (routeId.equals(leg.routeId()) || routeId.equals(firstTripRouteOf(leg)))
          && (plannedDepot.isEmpty() || sameDepot(plannedDepot, depotNodeId))
          && (nextLeg == null || leg.departure().isBefore(nextLeg.departure()))) {
        nextLeg = leg;
      }
    }
    TimetableService.DueTrip nextTrip = null;
    for (TimetableService.DueTrip trip : timetableService.tripsBetween(now, to)) {
      boolean fromThisDepot =
          depotStartOf(trip).filter(depot -> sameDepot(depot, depotNodeId)).isPresent();
      if (fromThisDepot
          && routeId.equals(trip.trip().routeId())
          && !cancelledFromOrigin(trip)
          && intentOf(trip).isPresent()
          && (nextTrip == null || trip.departure().isBefore(nextTrip.departure()))) {
        nextTrip = trip;
      }
    }
    if (nextLeg != null
        && (nextTrip == null || !nextTrip.departure().isBefore(nextLeg.departure()))) {
      TicketIntent intent = legIntent(nextLeg);
      String tripCode =
          timetableService
              .tripOfIntent(intent)
              .map(due -> due.trip().tripCode())
              .orElse(nextLeg.duty().dutyCode());
      return Optional.of(
          new EarlyTarget(
              intent, Optional.of(nextLeg), Optional.empty(), nextLeg.departure(), tripCode));
    }
    if (nextTrip != null) {
      return Optional.of(
          new EarlyTarget(
              intentOf(nextTrip).orElseThrow(),
              Optional.empty(),
              Optional.of(nextTrip),
              nextTrip.departure(),
              nextTrip.trip().tripCode()));
    }
    return Optional.empty();
  }

  /**
   * 提前出的车等在车库期间（到它的计划时刻为止）要出库的别的票：已在队列里等出库的、时刻表的出库走行与车次、按间隔发车线路的预测。
   *
   * <p>不从车库出车的票（折返复用）也列进来，由挑股道一侧按线路首站判断。已出票、已有车在跑的班次不再列：前者已在队列里，后者已经占着股道或已经开走。
   */
  private List<SpawnTicket> departuresBefore(Instant now, EarlyTarget target) {
    Set<UUID> managed = managedRoutes();
    List<SpawnTicket> out = new ArrayList<>();
    for (SpawnTicket queued : snapshotQueue()) {
      if (queued == null || queued.service() == null) {
        continue;
      }
      OwnedTicket owned = ownedTickets.get(queued.id());
      if (owned == null && managed.contains(queued.service().routeId())) {
        // 受管辖线路的间隔票会在出票时被拦下，不会出车。
        continue;
      }
      if (owned != null && owned.intent().filter(target.intent()::equals).isPresent()) {
        continue;
      }
      out.add(queued);
    }
    Instant until = target.departure();
    for (TimetableService.DueLeg leg : timetableService.legsBetween(now, until)) {
      TicketIntent intent = legIntent(leg);
      if (leg.kind() == RouteOperationType.CREATE
          && !intent.equals(target.intent())
          && !alreadyIssued(intent)) {
        buildLegTicket(leg).ifPresent(out::add);
      }
    }
    for (TimetableService.DueTrip trip : timetableService.tripsBetween(now, until)) {
      Optional<TicketIntent> intent = intentOf(trip);
      if (!cancelledFromOrigin(trip)
          && intent.filter(target.intent()::equals).isEmpty()
          && intent.filter(this::alreadyIssued).isEmpty()) {
        buildTicket(trip).ifPresent(out::add);
      }
    }
    if (delegate instanceof SpawnForecastSupport forecast && until.isAfter(now)) {
      for (SpawnTicket predicted :
          forecast.snapshotForecast(now, Duration.between(now, until), MAX_FORECAST_PER_SERVICE)) {
        if (predicted != null
            && predicted.service() != null
            && !managed.contains(predicted.service().routeId())) {
          out.add(predicted);
        }
      }
    }
    return out;
  }

  /** 提前出的车等在车库期间按时刻表回库的交路（不含它自己的交路）。 */
  private List<EarlySpawnPlan.DepotArrival> arrivalsBefore(Instant now, EarlyTarget target) {
    List<EarlySpawnPlan.DepotArrival> out = new ArrayList<>();
    for (TimetableService.DueArrival arrival :
        timetableService.depotArrivalsBetween(now, target.departure())) {
      boolean own =
          arrival.timetable().id().equals(target.intent().timetableId())
              && arrival.duty().id().equals(target.intent().dutyId());
      if (!own) {
        out.add(
            new EarlySpawnPlan.DepotArrival(
                arrival.duty().dutyCode(),
                arrival.routeId(),
                arrival.duty().endDepotNodeId(),
                arrival.at()));
      }
    }
    return out;
  }

  /** 出库走行要去接的那一班的线路：命令里写的是首班的载客线路时，同样认成这条出库走行。 */
  private UUID firstTripRouteOf(TimetableService.DueLeg leg) {
    return timetableService
        .tripOfIntent(legIntent(leg))
        .map(due -> due.trip().routeId())
        .orElse(null);
  }

  /** 车次从车库始发（线路首站就是车库）时的车库节点；由终点站待命车接班、或经出库走行接车的车次为空。 */
  private static Optional<String> depotStartOf(TimetableService.DueTrip trip) {
    return trip.timetable()
        .tripPlan(trip.trip())
        .flatMap(
            plan ->
                plan.depotNodeId()
                    .or(
                        () ->
                            isDepotNode(plan.originNodeId())
                                ? Optional.of(plan.originNodeId())
                                : Optional.empty()));
  }

  /** 节点写法“运营商:D:车库:股道”。 */
  private static boolean isDepotNode(String nodeId) {
    return nodeId != null && nodeId.toUpperCase(java.util.Locale.ROOT).contains(":D:");
  }

  /** 同一个车库：节点相同，或只是股道不同。 */
  private static boolean sameDepot(String a, String b) {
    if (a.equalsIgnoreCase(b)) {
      return true;
    }
    int ai = a.lastIndexOf(':');
    int bi = b.lastIndexOf(':');
    return ai > 0 && bi > 0 && a.substring(0, ai).equalsIgnoreCase(b.substring(0, bi));
  }

  /** 已出过票（还在等车），或交路已经有车在跑。 */
  private boolean alreadyIssued(TicketIntent intent) {
    return hasPendingTicket(intent) || timetableService.runningVehicleFor(intent).isPresent();
  }

  /**
   * 提前出的车在车库等候时写进列车标签的交路意图，重启后据此把车重新绑回交路（见 {@link #restoreEarlyHold}）。
   *
   * @param ticket 提前出车的票
   * @return 本层的票、且还带着交路意图时为它的写法
   */
  public Optional<String> earlyHoldToken(SpawnTicket ticket) {
    if (ticket == null || ticket.id() == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(ownedTickets.get(ticket.id()))
        .flatMap(OwnedTicket::intent)
        .map(TimetableSpawnManager::formatIntent);
  }

  /**
   * 重启后把在车库等候的提前出车重新绑回交路：交路记录只在内存里，不绑回去的话，这一班到点时会再出一辆车。
   *
   * <p>交路已经有别的车在跑时不绑（同一交路只能有一辆车）。
   *
   * @param trainName 列车名
   * @param token {@link #earlyHoldToken} 写下的交路意图
   * @return 绑上时为 true
   */
  public boolean restoreEarlyHold(String trainName, String token) {
    if (timetableService == null || trainName == null) {
      return false;
    }
    Optional<TicketIntent> parsed = parseIntent(token);
    if (parsed.isEmpty()) {
      debugLogger.accept("TIMETABLE_SPAWN_EARLY_RESTORE_SKIP reason=bad-token train=" + trainName);
      return false;
    }
    TicketIntent intent = parsed.get();
    Optional<String> running = timetableService.runningVehicleFor(intent);
    if (running.isPresent()) {
      debugLogger.accept(
          "TIMETABLE_SPAWN_EARLY_RESTORE_SKIP reason=duty-running train="
              + trainName
              + " running="
              + running.get()
              + " duty="
              + intent.key().describe());
      return false;
    }
    timetableService.bindDuty(trainName, intent.key(), "early-spawn-restore");
    Optional<TimetableService.DueTrip> trip =
        intent.kind() == RouteOperationType.OPERATION
            ? timetableService.tripOfIntent(intent)
            : Optional.empty();
    timetableService.bindDispatchedTrip(trainName, intent, trip);
    debugLogger.accept(
        "TIMETABLE_SPAWN_EARLY_RESTORED train="
            + trainName
            + " duty="
            + intent.key().describe()
            + " kind="
            + intent.kind().name());
    return true;
  }

  static String formatIntent(TicketIntent intent) {
    return intent.timetableId()
        + ","
        + intent.dutyId()
        + ","
        + intent.serviceDate()
        + ","
        + intent.kind().name()
        + ","
        + intent.tripIndex();
  }

  static Optional<TicketIntent> parseIntent(String token) {
    if (token == null) {
      return Optional.empty();
    }
    String[] parts = token.trim().split(",");
    if (parts.length != 5) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new TicketIntent(
              UUID.fromString(parts[0]),
              UUID.fromString(parts[1]),
              LocalDate.parse(parts[2]),
              RouteOperationType.valueOf(parts[3]),
              Integer.parseInt(parts[4])));
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
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
    Map<String, TicketIntent> intents = new HashMap<>();
    for (TimetableService.DueTrip due : timetableService.tripsBetween(now, now.plus(horizon))) {
      if (!cancelledFromOrigin(due)) {
        buildTicket(due)
            .ifPresent(
                built -> {
                  out.add(built);
                  intentOf(due)
                      .ifPresent(
                          intent ->
                              built.serviceTripId().ifPresent(trip -> intents.put(trip, intent)));
                });
      }
    }
    for (TimetableService.DueLeg due : timetableService.legsBetween(now, now.plus(horizon))) {
      buildLegTicket(due)
          .ifPresent(
              built -> {
                out.add(built);
                built.serviceTripId().ifPresent(trip -> intents.put(trip, legIntent(due)));
              });
    }
    forecastIntents = Map.copyOf(intents);
    return List.copyOf(out);
  }

  @Override
  public Optional<NextDeparture> nextDepartureOf(String trainName) {
    if (timetableService == null) {
      return Optional.empty();
    }
    return timetableService
        .nextDepartureOf(trainName)
        .map(due -> new NextDeparture(due.trip().routeId(), due.departure(), serviceTripIdOf(due)));
  }

  @Override
  public Optional<String> awaitedVehicleOf(SpawnTicket ticket) {
    return ticketIntentOf(ticket).flatMap(timetableService::awaitedVehicle);
  }

  @Override
  public Optional<String> plannedPlatformOf(SpawnTicket ticket, int stopIndex) {
    return ticketIntentOf(ticket)
        .flatMap(intent -> timetableService.plannedPlatform(intent, stopIndex));
  }

  /** 已出的票按登记的意图，预测票按最近一次预测记下的意图；派出后意图已摘掉，查不到。 */
  private Optional<TicketIntent> ticketIntentOf(SpawnTicket ticket) {
    if (ticket == null || ticket.id() == null || timetableService == null) {
      return Optional.empty();
    }
    OwnedTicket owned = ownedTickets.get(ticket.id());
    if (owned != null) {
      return owned.intent();
    }
    Map<String, TicketIntent> forecast = forecastIntents;
    return ticket.serviceTripId().map(forecast::get);
  }

  /** 一趟车对应票据的车次标识：带客回库班由交路的回库走行票开出，标识跟走行票走（交路号 + 服务日）； 其余按车次号 + 起点发车日历日。 */
  private static String serviceTripIdOf(TimetableService.DueTrip due) {
    boolean returnTrip =
        due.timetable()
            .routePlan(due.trip().routeId())
            .map(plan -> plan.kind() == RouteOperationType.RETURN)
            .orElse(false);
    if (returnTrip) {
      Optional<String> legId =
          due.trip()
              .dutyId()
              .flatMap(due.timetable()::duty)
              .map(
                  duty ->
                      legTicketId(
                          due.timetable().code(),
                          duty.dutyCode() + "-" + RouteOperationType.RETURN.name(),
                          due.timetable().serviceDayOf(due.trip(), due.serviceDate())));
      if (legId.isPresent()) {
        return legId.get();
      }
    }
    return tripTicketId(due.timetable().code(), due.trip().tripCode(), due.serviceDate());
  }

  private static String tripTicketId(String timetableCode, String tripCode, LocalDate date) {
    return SpawnTicket.TIMETABLE_TRIP_PREFIX + timetableCode + "-" + tripCode + "-" + date;
  }

  private static String legTicketId(String timetableCode, String legCode, LocalDate serviceDay) {
    return SpawnTicket.TIMETABLE_TRIP_PREFIX + timetableCode + "-" + legCode + "-" + serviceDay;
  }

  private static TicketIntent legIntent(TimetableService.DueLeg leg) {
    return new TicketIntent(
        leg.timetable().id(), leg.duty().id(), leg.serviceDate(), leg.kind(), 0);
  }

  /** 整趟取消的车次不会开出，不再预测；站牌与站台屏从时刻表的取消标记显示它。 */
  private boolean cancelledFromOrigin(TimetableService.DueTrip due) {
    return timetableService
        .cancellationOf(due.timetable().id(), due.trip().id(), due.serviceDate())
        .filter(cancellation -> cancellation.covers(0))
        .isPresent();
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
      if (hasPendingTicket(legIntent(leg))) {
        // 已经手动提前出过票（见 issueEarly）：同一班不出第二张。
        continue;
      }
      buildLegTicket(leg)
          .ifPresent(
              built -> {
                track(built, Optional.of(legIntent(leg)), Optional.empty());
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
      if (intentOf(trip).filter(this::hasPendingTicket).isPresent()) {
        continue;
      }
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
    String tripId = legTicketId(due.timetable().code(), due.code(), due.serviceDate());
    // 出库票出的是交路的车型（替补车同样）；回库票不出车。
    Optional<String> consist =
        due.kind() == RouteOperationType.CREATE ? due.duty().consist() : Optional.empty();
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
                0)
            .withConsist(consist));
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
        due.timetable().tripPlan(due.trip()).flatMap(plan -> plan.depotNodeId());
    String tripId = tripTicketId(due.timetable().code(), due.trip().tripCode(), due.serviceDate());
    // 车次的车型即交路的车型：从车库始发的车次（首站 CRET）按它出车。
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
                0)
            .withConsist(due.timetable().consistOf(due.trip())));
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
