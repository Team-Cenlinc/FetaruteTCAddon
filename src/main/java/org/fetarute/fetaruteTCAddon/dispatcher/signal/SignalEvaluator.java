package org.fetarute.fetaruteTCAddon.dispatcher.signal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.OccupancyAcquiredEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.OccupancyReleasedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalChangedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalEventBus;

/**
 * 信号评估器。
 *
 * <p>订阅占用变化事件，在资源状态变化时重新评估受影响列车的信号。
 *
 * <p>职责：
 *
 * <ul>
 *   <li>订阅 {@link OccupancyAcquiredEvent} 和 {@link OccupancyReleasedEvent}
 *   <li>根据事件中的 affectedTrains 列表，重新评估这些列车的信号状态
 *   <li>若信号变化，发布 {@link SignalChangedEvent}
 * </ul>
 *
 * <p>此组件将"主动查询"改为"被动推送"，减少周期性 tick 的频率需求。
 */
public class SignalEvaluator {

  private final SignalEventBus eventBus;
  private final OccupancyManager occupancyManager;
  private final TrainRequestProvider requestProvider;
  private final Consumer<String> debugLogger;

  /** 缓存每个列车的上次信号状态，用于检测变化。 */
  private final Map<String, SignalAspect> lastSignalCache = new ConcurrentHashMap<>();

  private SignalEventBus.Subscription acquiredSubscription;
  private SignalEventBus.Subscription releasedSubscription;

  /**
   * 构建信号评估器。
   *
   * @param eventBus 事件总线
   * @param occupancyManager 占用管理器
   * @param requestProvider 列车请求构建器（用于为受影响列车构建 OccupancyRequest）
   */
  public SignalEvaluator(
      SignalEventBus eventBus,
      OccupancyManager occupancyManager,
      TrainRequestProvider requestProvider) {
    this(eventBus, occupancyManager, requestProvider, msg -> {});
  }

  /**
   * 构建信号评估器。
   *
   * @param eventBus 事件总线
   * @param occupancyManager 占用管理器
   * @param requestProvider 列车请求构建器
   * @param debugLogger 调试日志输出
   */
  public SignalEvaluator(
      SignalEventBus eventBus,
      OccupancyManager occupancyManager,
      TrainRequestProvider requestProvider,
      Consumer<String> debugLogger) {
    this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    this.occupancyManager = Objects.requireNonNull(occupancyManager, "occupancyManager");
    this.requestProvider = Objects.requireNonNull(requestProvider, "requestProvider");
    this.debugLogger = debugLogger != null ? debugLogger : msg -> {};
  }

  /** 启动评估器，订阅事件。 */
  public void start() {
    if (acquiredSubscription != null) {
      return; // 已启动
    }
    acquiredSubscription = eventBus.subscribe(OccupancyAcquiredEvent.class, this::onAcquired);
    releasedSubscription = eventBus.subscribe(OccupancyReleasedEvent.class, this::onReleased);
    debugLogger.accept("SignalEvaluator 已启动");
  }

  /** 停止评估器，取消订阅。 */
  public void stop() {
    if (acquiredSubscription != null) {
      acquiredSubscription.unsubscribe();
      acquiredSubscription = null;
    }
    if (releasedSubscription != null) {
      releasedSubscription.unsubscribe();
      releasedSubscription = null;
    }
    lastSignalCache.clear();
    debugLogger.accept("SignalEvaluator 已停止");
  }

  /** 处理占用获取事件：重新评估受影响列车。 */
  private void onAcquired(OccupancyAcquiredEvent event) {
    if (event == null) {
      return;
    }
    List<String> affected = event.affectedTrains();
    if (affected == null || affected.isEmpty()) {
      return;
    }
    Instant now = event.timestamp();
    for (String trainName : affected) {
      reevaluate(trainName, now);
    }
  }

  /** 处理占用释放事件：资源释放后，所有等待该资源的列车需重新评估。 */
  private void onReleased(OccupancyReleasedEvent event) {
    if (event == null) {
      return;
    }
    // 获取等待释放资源的列车列表
    List<String> waitingTrains = requestProvider.trainsWaitingFor(event.releasedResources());
    if (waitingTrains == null || waitingTrains.isEmpty()) {
      return;
    }
    Instant now = event.timestamp();
    for (String trainName : waitingTrains) {
      reevaluate(trainName, now);
    }
  }

  /**
   * 重新评估指定列车的信号状态。
   *
   * <p>若信号变化，发布 {@link SignalChangedEvent}。
   */
  private void reevaluate(String trainName, Instant now) {
    if (trainName == null || trainName.isBlank()) {
      return;
    }
    Optional<OccupancyRequest> requestOpt = requestProvider.buildRequest(trainName, now);
    if (requestOpt.isEmpty()) {
      // 列车不存在或无法构建请求
      lastSignalCache.remove(trainName);
      return;
    }
    OccupancyRequest request = requestOpt.get();
    OccupancyDecision decision = occupancyManager.canEnter(request);
    SignalAspect newSignal = decision.signal();
    SignalAspect previous = lastSignalCache.get(trainName);

    if (previous == null || previous != newSignal) {
      lastSignalCache.put(trainName, newSignal);
      SignalChangedEvent changeEvent = new SignalChangedEvent(now, trainName, previous, newSignal);
      eventBus.publish(changeEvent);
      debugLogger.accept(
          "信号变化(事件): train="
              + trainName
              + " "
              + (previous != null ? previous : "null")
              + " -> "
              + newSignal);
    }
  }

  /** 更新列车信号缓存（供外部同步调用，如周期性 tick 兜底）。 */
  public void updateCache(String trainName, SignalAspect signal) {
    if (trainName != null && signal != null) {
      lastSignalCache.put(trainName, signal);
    }
  }

  /** 获取列车上次信号状态（用于诊断）。 */
  public Optional<SignalAspect> lastSignal(String trainName) {
    return Optional.ofNullable(lastSignalCache.get(trainName));
  }

  /** 清除列车信号缓存（列车销毁时调用）。 */
  public void clearCache(String trainName) {
    if (trainName != null) {
      lastSignalCache.remove(trainName);
    }
  }

  /**
   * 列车请求提供者接口。
   *
   * <p>用于为受影响列车构建 {@link OccupancyRequest}，以及查询等待特定资源的列车列表。
   */
  public interface TrainRequestProvider {

    /**
     * 为指定列车构建占用请求。
     *
     * @param trainName 列车名
     * @param now 当前时间
     * @return 请求（列车不存在时返回空）
     */
    Optional<OccupancyRequest> buildRequest(String trainName, Instant now);

    /**
     * 获取等待指定资源的列车列表。
     *
     * @param resources 已释放的资源列表
     * @return 等待这些资源的列车名列表
     */
    List<String> trainsWaitingFor(
        List<org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource>
            resources);
  }
}
