package org.fetarute.fetaruteTCAddon.api.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.fetarute.fetaruteTCAddon.api.event.StationGroupChangedEvent;
import org.fetarute.fetaruteTCAddon.api.event.TimetableTripAssignedEvent;
import org.fetarute.fetaruteTCAddon.api.event.TimetableTripCancelledEvent;
import org.fetarute.fetaruteTCAddon.api.event.TrainArriveStationEvent;
import org.fetarute.fetaruteTCAddon.api.event.TrainDepartStationEvent;
import org.fetarute.fetaruteTCAddon.api.event.TrainHealthAlertEvent;
import org.fetarute.fetaruteTCAddon.api.event.TrainHoldEvent;
import org.fetarute.fetaruteTCAddon.api.event.TrainHoldReleasedEvent;
import org.fetarute.fetaruteTCAddon.api.event.TrainPlatformAssignedEvent;
import org.fetarute.fetaruteTCAddon.api.event.TrainReleasedEvent;
import org.fetarute.fetaruteTCAddon.api.event.TrainSignalChangeEvent;
import org.fetarute.fetaruteTCAddon.api.train.TrainApi;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.TrainHold;
import org.fetarute.fetaruteTCAddon.dispatcher.health.HealthAlert;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteTerminals;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.PlatformResolution;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopObserver;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableAssignment;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TripCancellations;

/**
 * 内部事实 → 公开 Bukkit 事件的桥。
 *
 * <p>两条来源：
 *
 * <ul>
 *   <li><b>推送</b>：车站到发、离开管辖（{@link StationStopObserver}）、健康告警（告警总线）与车站组变化（命令改库后）。
 *       回调发生在调度路径或命令里，这里只入队，不调用外部代码。
 *   <li><b>对比</b>：扣停、信号、车次绑定没有现成的变化回调，每 tick 对比一次快照（只读几张表，开销可忽略）。
 *       不往调度服务里加钩子——它贴着静态分析的方法数上限，而且事件不该有机会影响控车。
 * </ul>
 *
 * <p>{@link #tick()} 由主线程每 tick 调用：先对比，再把队列里的事件依次发出。每个事件单独隔离异常；每 tick 发出数有上限，
 * 队列满了丢弃最新事件并计数，外部监听器再慢也不能拖垮调度 tick。某类事件没有监听器时连事件对象都不创建。
 */
public final class ApiEventBridge implements StationStopObserver, Consumer<HealthAlert> {

  /** 单 tick 最多发出的事件数。 */
  static final int MAX_EVENTS_PER_TICK = 256;

  /** 队列上限；超过后丢弃新事件。 */
  static final int MAX_QUEUED_EVENTS = 4096;

  private final ConcurrentLinkedQueue<Event> queue = new ConcurrentLinkedQueue<>();
  private final AtomicInteger queued = new AtomicInteger();
  private final AtomicLong dropped = new AtomicLong();

  private final Consumer<Event> sink;
  private final Predicate<HandlerList> hasListeners;
  private final Supplier<Collection<String>> activeTrains;
  private final Function<String, Optional<TrainHold>> holds;
  private final Function<String, Optional<SignalAspect>> signals;
  private final Function<String, Optional<TimetableAssignment>> assignments;
  private final Supplier<Instant> clock;
  private final Consumer<String> debugLogger;

  private final Map<String, HoldSeen> holdsSeen = new HashMap<>();
  private final Map<String, TrainApi.Signal> lastSignals = new HashMap<>();
  private final Map<String, UUID> lastTrips = new HashMap<>();

  /**
   * @param sink 发出事件（生产环境为 {@code PluginManager#callEvent}）
   * @param hasListeners 判断某类事件是否有监听器
   * @param activeTrains 当前受管列车名
   * @param holds 列车当前扣停（{@code EtaService#currentHold}，与 ETA 顺延同一判定）
   * @param signals 列车当前信号
   * @param assignments 列车当前车次绑定（未启用按表运行时返回空）
   * @param clock 调度层时钟
   * @param debugLogger 调试日志
   */
  public ApiEventBridge(
      Consumer<Event> sink,
      Predicate<HandlerList> hasListeners,
      Supplier<Collection<String>> activeTrains,
      Function<String, Optional<TrainHold>> holds,
      Function<String, Optional<SignalAspect>> signals,
      Function<String, Optional<TimetableAssignment>> assignments,
      Supplier<Instant> clock,
      Consumer<String> debugLogger) {
    this.sink = Objects.requireNonNull(sink, "sink");
    this.hasListeners = Objects.requireNonNull(hasListeners, "hasListeners");
    this.activeTrains = activeTrains == null ? List::of : activeTrains;
    this.holds = holds == null ? name -> Optional.empty() : holds;
    this.signals = signals == null ? name -> Optional.empty() : signals;
    this.assignments = assignments == null ? name -> Optional.empty() : assignments;
    this.clock = clock == null ? Instant::now : clock;
    this.debugLogger = debugLogger == null ? message -> {} : debugLogger;
  }

  /** 生产环境的监听器判断：该事件类的 HandlerList 上是否注册了任何监听器。 */
  public static boolean anyRegistered(HandlerList handlers) {
    return handlers != null && handlers.getRegisteredListeners().length > 0;
  }

  // ─── 推送来源（调度路径回调：只入队） ───────────────────────────────────────

  @Override
  public void onStationArrival(StationStopEvent event) {
    if (event == null || event.trainName().isBlank()) {
      return;
    }
    if (hasListeners.test(TrainArriveStationEvent.getHandlerList())) {
      enqueue(
          new TrainArriveStationEvent(
              event.trainName(),
              event.routeUuid(),
              event.routeKey(),
              event.stopIndex(),
              event.nodeId(),
              event.terminal(),
              event.at()));
    }
  }

  @Override
  public void onStationDeparture(StationStopEvent event) {
    if (event == null || event.trainName().isBlank()) {
      return;
    }
    if (hasListeners.test(TrainDepartStationEvent.getHandlerList())) {
      enqueue(
          new TrainDepartStationEvent(
              event.trainName(),
              event.routeUuid(),
              event.routeKey(),
              event.stopIndex(),
              event.nodeId(),
              event.terminal(),
              event.at()));
    }
  }

  @Override
  public void onTrainReleased(String trainName, String reason) {
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    if (hasListeners.test(TrainReleasedEvent.getHandlerList())) {
      enqueue(new TrainReleasedEvent(trainName, reason));
    }
  }

  @Override
  public void onPlatformResolved(PlatformResolution resolution) {
    if (resolution == null || !hasListeners.test(TrainPlatformAssignedEvent.getHandlerList())) {
      return;
    }
    enqueue(
        new TrainPlatformAssignedEvent(
            resolution.trainName(),
            resolution.routeKey(),
            resolution.stopIndex(),
            resolution.node().value(),
            RouteTerminals.platformOf(resolution.node().value()),
            resolution.previousNode().map(node -> RouteTerminals.platformOf(node.value())),
            resolution.plannedNode().map(node -> RouteTerminals.platformOf(node.value())),
            switch (resolution.reason()) {
              case ASSIGNED -> TrainPlatformAssignedEvent.Reason.ASSIGNED;
              case CHANGED_FROM_PLAN -> TrainPlatformAssignedEvent.Reason.CHANGED_FROM_PLAN;
              case CHANGED -> TrainPlatformAssignedEvent.Reason.CHANGED;
            }));
  }

  /** 时刻表车次取消（时刻表服务登记之后调用，发生在调度路径或出票轮询里：只入队）。 */
  public void onTripCancelled(TripCancellations.Cancellation cancellation) {
    if (cancellation == null || !hasListeners.test(TimetableTripCancelledEvent.getHandlerList())) {
      return;
    }
    enqueue(
        new TimetableTripCancelledEvent(
            cancellation.timetableId(),
            cancellation.tripId(),
            cancellation.tripCode(),
            cancellation.routeId(),
            cancellation.serviceDate(),
            cancellation.plannedDeparture(),
            TimetableTripCancelledEvent.Scope.valueOf(cancellation.scope().name()),
            cancellation.firstCancelledStopSequence(),
            TimetableTripCancelledEvent.Reason.valueOf(cancellation.reason().name()),
            cancellation.trainName()));
  }

  /**
   * 车站组数据变化（命令改库并刷新车站目录之后调用）。
   *
   * @param changeType 变化类型
   * @param groupId 车站组
   * @param companyId 组所属公司
   * @param groupCode 组代码
   * @param stationId 涉及的成员车站
   * @param dataRevision 刷新后的数据版本
   */
  public void onStationGroupChanged(
      StationGroupChangedEvent.ChangeType changeType,
      UUID groupId,
      UUID companyId,
      String groupCode,
      Optional<UUID> stationId,
      long dataRevision) {
    if (changeType == null || groupId == null || companyId == null || groupCode == null) {
      return;
    }
    if (hasListeners.test(StationGroupChangedEvent.getHandlerList())) {
      enqueue(
          new StationGroupChangedEvent(
              changeType, groupId, companyId, groupCode, stationId, dataRevision));
    }
  }

  /** 健康告警总线回调。 */
  @Override
  public void accept(HealthAlert alert) {
    if (alert == null || !hasListeners.test(TrainHealthAlertEvent.getHandlerList())) {
      return;
    }
    enqueue(
        new TrainHealthAlertEvent(
            alert.type().name(),
            alert.train(),
            alert.message(),
            alert.autoFixed(),
            alert.timestamp()));
  }

  // ─── 主线程 tick ───────────────────────────────────────────────────────────

  /** 主线程每 tick 调用一次：对比快照，然后发出队列中的事件。 */
  public void tick() {
    try {
      poll();
    } catch (RuntimeException | LinkageError ex) {
      debugLogger.accept("API_EVENT_POLL_FAILED error=" + ex);
    }
    drain();
  }

  /** 对比一次扣停、信号与车次绑定（包内可见供测试直接调用）。 */
  void poll() {
    Instant now = clock.get();
    Set<String> seen = new HashSet<>();
    for (String name : activeTrains.get()) {
      if (name == null || name.isBlank()) {
        continue;
      }
      seen.add(name);
      pollHold(name, now);
      pollSignal(name);
      pollAssignment(name);
    }
    holdsSeen.keySet().retainAll(seen);
    lastSignals.keySet().retainAll(seen);
    lastTrips.keySet().retainAll(seen);
  }

  private void pollHold(String name, Instant now) {
    Optional<TrainHold> hold = holds.apply(name);
    HoldSeen previous = holdsSeen.get(name);
    if (hold.isEmpty()) {
      if (previous != null) {
        holdsSeen.remove(name);
        emitReleased(name, previous, now);
      }
      return;
    }
    TrainHold current = hold.get();
    if (previous != null && previous.since().equals(current.since())) {
      // 同一次扣停（起点跨停车状态替换保持不变）换了原因：再发一次扣停，不发解除。
      if (!previous.reasonCode().equals(current.reasonCode())) {
        holdsSeen.put(name, new HoldSeen(current.reasonCode(), current.since()));
        emitHold(name, current);
      }
      return;
    }
    if (previous != null) {
      emitReleased(name, previous, now);
    }
    holdsSeen.put(name, new HoldSeen(current.reasonCode(), current.since()));
    emitHold(name, current);
  }

  private void emitHold(String name, TrainHold hold) {
    if (!hasListeners.test(TrainHoldEvent.getHandlerList())) {
      return;
    }
    List<TrainHoldEvent.Blocker> blockers =
        hold.blockers().stream()
            .map(b -> new TrainHoldEvent.Blocker(b.resource(), b.owner(), b.role()))
            .toList();
    enqueue(new TrainHoldEvent(name, hold.reasonCode(), hold.detail(), blockers, hold.since()));
  }

  private void emitReleased(String name, HoldSeen previous, Instant now) {
    if (hasListeners.test(TrainHoldReleasedEvent.getHandlerList())) {
      enqueue(
          new TrainHoldReleasedEvent(
              name, previous.reasonCode(), Duration.between(previous.since(), now)));
    }
  }

  private void pollSignal(String name) {
    TrainApi.Signal current = toApiSignal(signals.apply(name).orElse(null));
    TrainApi.Signal previous = lastSignals.put(name, current);
    if (previous != null
        && previous != current
        && hasListeners.test(TrainSignalChangeEvent.getHandlerList())) {
      enqueue(new TrainSignalChangeEvent(name, previous, current));
    }
  }

  private void pollAssignment(String name) {
    Optional<TimetableAssignment> assignment = assignments.apply(name);
    if (assignment.isEmpty()) {
      lastTrips.remove(name);
      return;
    }
    TimetableAssignment a = assignment.get();
    UUID previous = lastTrips.put(name, a.tripId());
    if (!a.tripId().equals(previous)
        && hasListeners.test(TimetableTripAssignedEvent.getHandlerList())) {
      enqueue(
          new TimetableTripAssignedEvent(
              name, a.timetableId(), a.tripCode(), a.routeId(), a.initialDeviationSeconds()));
    }
  }

  private void enqueue(Event event) {
    if (queued.incrementAndGet() > MAX_QUEUED_EVENTS) {
      queued.decrementAndGet();
      long total = dropped.incrementAndGet();
      if (total == 1L || total % 1000L == 0L) {
        debugLogger.accept("API_EVENT_QUEUE_FULL dropped=" + total);
      }
      return;
    }
    queue.add(event);
  }

  private void drain() {
    for (int i = 0; i < MAX_EVENTS_PER_TICK; i++) {
      Event event = queue.poll();
      if (event == null) {
        return;
      }
      queued.decrementAndGet();
      try {
        sink.accept(event);
      } catch (RuntimeException | LinkageError ex) {
        debugLogger.accept(
            "API_EVENT_LISTENER_FAILED event=" + event.getEventName() + " error=" + ex);
      }
    }
  }

  /** 已丢弃的事件数（队列满）。 */
  public long droppedEvents() {
    return dropped.get();
  }

  static TrainApi.Signal toApiSignal(SignalAspect aspect) {
    if (aspect == null) {
      return TrainApi.Signal.UNKNOWN;
    }
    return switch (aspect) {
      case PROCEED -> TrainApi.Signal.PROCEED;
      case CAUTION -> TrainApi.Signal.CAUTION;
      case PROCEED_WITH_CAUTION -> TrainApi.Signal.PROCEED_WITH_CAUTION;
      case STOP -> TrainApi.Signal.STOP;
    };
  }

  private record HoldSeen(String reasonCode, Instant since) {}
}
