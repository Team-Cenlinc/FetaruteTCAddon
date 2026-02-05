package org.fetarute.fetaruteTCAddon.dispatcher.signal;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalChangedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalEventBus;

/**
 * 列车控制器：订阅信号变化事件，即时下发速度/控制指令。
 *
 * <p>职责：
 *
 * <ul>
 *   <li>订阅 {@link SignalChangedEvent}
 *   <li>根据信号变化调用控车回调（速度限制、发车、停车等）
 *   <li>将信号下发与业务逻辑解耦
 * </ul>
 *
 * <p>控车回调由调用方注入，支持不同的控车策略（TrainCarts、Mock 等）。
 */
public class TrainController {

  private final SignalEventBus eventBus;
  private final BiConsumer<String, SignalAspect> signalHandler;
  private final Consumer<String> debugLogger;

  private SignalEventBus.Subscription subscription;

  /**
   * 构建列车控制器。
   *
   * @param eventBus 事件总线
   * @param signalHandler 信号处理回调：(trainName, newSignal) -&gt; 下发控制指令
   */
  public TrainController(SignalEventBus eventBus, BiConsumer<String, SignalAspect> signalHandler) {
    this(eventBus, signalHandler, msg -> {});
  }

  /**
   * 构建列车控制器。
   *
   * @param eventBus 事件总线
   * @param signalHandler 信号处理回调
   * @param debugLogger 调试日志输出
   */
  public TrainController(
      SignalEventBus eventBus,
      BiConsumer<String, SignalAspect> signalHandler,
      Consumer<String> debugLogger) {
    this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    this.signalHandler = Objects.requireNonNull(signalHandler, "signalHandler");
    this.debugLogger = debugLogger != null ? debugLogger : msg -> {};
  }

  /** 启动控制器，订阅信号变化事件。 */
  public void start() {
    if (subscription != null) {
      return;
    }
    subscription = eventBus.subscribe(SignalChangedEvent.class, this::onSignalChanged);
    debugLogger.accept("TrainController 已启动");
  }

  /** 停止控制器，取消订阅。 */
  public void stop() {
    if (subscription != null) {
      subscription.unsubscribe();
      subscription = null;
    }
    debugLogger.accept("TrainController 已停止");
  }

  /** 处理信号变化事件。 */
  private void onSignalChanged(SignalChangedEvent event) {
    if (event == null || event.trainName() == null) {
      return;
    }
    String trainName = event.trainName();
    SignalAspect newSignal = event.newSignal();

    debugLogger.accept(
        "控车下发: train=" + trainName + " signal=" + newSignal + " unblocked=" + event.isUnblocked());

    try {
      signalHandler.accept(trainName, newSignal);
    } catch (Exception e) {
      debugLogger.accept("控车下发失败: train=" + trainName + " error=" + e.getMessage());
    }
  }
}
