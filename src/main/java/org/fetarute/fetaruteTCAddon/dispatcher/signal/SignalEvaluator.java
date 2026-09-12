package org.fetarute.fetaruteTCAddon.dispatcher.signal;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.OccupancyQueueChangedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.OccupancyReleasedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalEventBus;

/**
 * 占用事实到完整信号重评估的事件桥。
 *
 * <p>资源释放，或已记录的 Gate Queue 队首资格变化，才可能使列车获得新的 Movement Authority 候选。资源获取与普通队列维护会改变内部记录， 但不会让任何等待列车从
 * blocked 变为 eligible；把它们当作 wake-up 会在同一份现场事实下反复执行完整授权。本类将释放资源上已登记的队首与动态容量等待者，以及精确资格变化中的队首，交给下一
 * tick 的完整运行时重评估入口。容量通知只允许重新选台，不提前取得任何资源或队列资格。
 *
 * <p>实际合并、去重和防重入由 {@link RuntimeSignalReevaluationScheduler} 承担；Gate Queue 仍是唯一 winner 来源。
 */
public final class SignalEvaluator {

  private final SignalEventBus eventBus;
  private final WaitingTrainProvider waitingTrainProvider;
  private final Consumer<String> reevaluationRequester;
  private final BiConsumer<String, Throwable> failureHandler;
  private final Consumer<String> debugLogger;

  private SignalEventBus.Subscription queueChangedSubscription;
  private SignalEventBus.Subscription releasedSubscription;

  /**
   * 构建占用事件桥。
   *
   * @param eventBus 事件总线
   * @param waitingTrainProvider 等待列车查询器
   * @param reevaluationRequester 下一 tick 完整重评估请求入口
   */
  public SignalEvaluator(
      SignalEventBus eventBus,
      WaitingTrainProvider waitingTrainProvider,
      Consumer<String> reevaluationRequester) {
    this(
        eventBus,
        waitingTrainProvider,
        reevaluationRequester,
        (trainName, error) -> {},
        message -> {});
  }

  /**
   * 构建占用事件桥。
   *
   * @param eventBus 事件总线
   * @param waitingTrainProvider 等待列车查询器
   * @param reevaluationRequester 下一 tick 完整重评估请求入口
   * @param debugLogger 调试日志输出
   */
  public SignalEvaluator(
      SignalEventBus eventBus,
      WaitingTrainProvider waitingTrainProvider,
      Consumer<String> reevaluationRequester,
      Consumer<String> debugLogger) {
    this(
        eventBus,
        waitingTrainProvider,
        reevaluationRequester,
        (trainName, error) -> {},
        debugLogger);
  }

  /**
   * 构建带 fail-closed 故障边界的占用事件桥。
   *
   * @param eventBus 事件总线
   * @param waitingTrainProvider 等待列车查询器
   * @param reevaluationRequester 下一 tick 完整重评估请求入口
   * @param failureHandler 同步等待查询失败后的 fail-closed 回调
   * @param debugLogger 调试日志输出
   */
  public SignalEvaluator(
      SignalEventBus eventBus,
      WaitingTrainProvider waitingTrainProvider,
      Consumer<String> reevaluationRequester,
      BiConsumer<String, Throwable> failureHandler,
      Consumer<String> debugLogger) {
    this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    this.waitingTrainProvider =
        Objects.requireNonNull(waitingTrainProvider, "waitingTrainProvider");
    this.reevaluationRequester =
        Objects.requireNonNull(reevaluationRequester, "reevaluationRequester");
    this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    this.debugLogger = debugLogger == null ? message -> {} : debugLogger;
  }

  /** 启动事件桥，订阅占用事实。 */
  public void start() {
    if (releasedSubscription != null) {
      return;
    }
    queueChangedSubscription =
        eventBus.subscribe(OccupancyQueueChangedEvent.class, this::onQueueChanged);
    releasedSubscription = eventBus.subscribe(OccupancyReleasedEvent.class, this::onReleased);
    debugLogger.accept("占用事件信号重评估桥已启动");
  }

  /** 停止事件桥并取消订阅。 */
  public void stop() {
    if (releasedSubscription != null) {
      releasedSubscription.unsubscribe();
      releasedSubscription = null;
    }
    if (queueChangedSubscription != null) {
      queueChangedSubscription.unsubscribe();
      queueChangedSubscription = null;
    }
    debugLogger.accept("占用事件信号重评估桥已停止");
  }

  private void onReleased(OccupancyReleasedEvent event) {
    if (event == null || event.releasedResources().isEmpty()) {
      return;
    }
    requestWaitingTrains(event.releasedResources(), event.trainName(), true, "occupancy-released");
  }

  private void onQueueChanged(OccupancyQueueChangedEvent event) {
    if (event == null || event.eligibleTrainNames().isEmpty()) {
      return;
    }
    requestAffectedTrains(
        event.eligibleTrainNames(), event.sourceTrainName(), true, "occupancy-queue-eligible");
  }

  private void requestWaitingTrains(
      List<OccupancyResource> resources,
      String sourceTrainName,
      boolean excludeSourceTrain,
      String source) {
    List<String> waitingTrains;
    try {
      waitingTrains = waitingTrainProvider.trainsWaitingFor(resources);
    } catch (RuntimeException | LinkageError error) {
      notifyFailure(sourceTrainName, error);
      safeDebug(
          "占用仲裁等待列车查询失败: train="
              + sourceTrainName
              + " source="
              + source
              + " error="
              + error.getClass().getSimpleName()
              + ":"
              + String.valueOf(error.getMessage()));
      return;
    }
    if (waitingTrains == null || waitingTrains.isEmpty()) {
      return;
    }
    requestAffectedTrains(waitingTrains, sourceTrainName, excludeSourceTrain, source);
  }

  private void requestAffectedTrains(
      List<String> trainNames, String sourceTrainName, boolean excludeSourceTrain, String source) {
    for (String trainName : trainNames) {
      if (trainName == null
          || trainName.isBlank()
          || (excludeSourceTrain
              && TrainNameNormalizer.sameLogicalTrain(trainName, sourceTrainName))) {
        continue;
      }
      try {
        reevaluationRequester.accept(trainName);
      } catch (RuntimeException | LinkageError error) {
        notifyFailure(trainName, error);
        safeDebug(
            "信号完整重评估请求失败: train="
                + trainName
                + " source="
                + source
                + " error="
                + error.getClass().getSimpleName()
                + ":"
                + String.valueOf(error.getMessage()));
      }
    }
  }

  private void notifyFailure(String trainName, Throwable error) {
    try {
      failureHandler.accept(trainName, error);
    } catch (RuntimeException | LinkageError handlerError) {
      safeDebug(
          "信号事件桥故障处理失败: train="
              + trainName
              + " error="
              + handlerError.getClass().getSimpleName()
              + ":"
              + String.valueOf(handlerError.getMessage()));
    }
  }

  private void safeDebug(String message) {
    try {
      debugLogger.accept(message);
    } catch (RuntimeException | LinkageError ignored) {
      // 日志不可用不能打断同步 occupancy 事件栈，也不能阻止同批其他列车进入 fail-closed。
    }
  }

  /**
   * 等待列车查询器接口。
   *
   * <p>事件桥只读取资源释放后应重新评估的列车。它不构建 preview 请求，也不参与 Movement Authority、资源取得或信号发布。
   */
  public interface WaitingTrainProvider {
    /**
     * 返回已释放资源上的直接 Gate Queue 队首与显式登记的容量等待者。
     *
     * @param resources 已释放资源
     * @return 获得重新授权机会的逻辑列车名
     */
    List<String> trainsWaitingFor(List<OccupancyResource> resources);
  }
}
