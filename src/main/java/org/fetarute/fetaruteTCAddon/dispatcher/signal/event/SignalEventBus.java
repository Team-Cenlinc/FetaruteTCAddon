package org.fetarute.fetaruteTCAddon.dispatcher.signal.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 信号事件总线。
 *
 * <p>提供简单的同步发布-订阅机制，用于信号系统内部组件的解耦通信。
 *
 * <p>特性：
 *
 * <ul>
 *   <li>类型安全的事件订阅与发布
 *   <li>同步执行（避免并发问题）
 *   <li>支持按事件类型订阅
 *   <li>支持全局订阅（接收所有事件）
 * </ul>
 *
 * <p>使用示例：
 *
 * <pre>{@code
 * SignalEventBus bus = new SignalEventBus();
 * bus.subscribe(SignalChangedEvent.class, event -> {
 *     // 处理信号变化
 * });
 * bus.publish(new SignalChangedEvent(...));
 * }</pre>
 */
public class SignalEventBus {

  private final Map<Class<? extends SignalEvent>, List<Consumer<? super SignalEvent>>> subscribers;
  private final List<Consumer<SignalEvent>> globalSubscribers;
  private final Consumer<String> debugLogger;

  public SignalEventBus() {
    this(msg -> {});
  }

  public SignalEventBus(Consumer<String> debugLogger) {
    this.subscribers = new ConcurrentHashMap<>();
    this.globalSubscribers = new CopyOnWriteArrayList<>();
    this.debugLogger = debugLogger != null ? debugLogger : msg -> {};
  }

  /**
   * 订阅指定类型的事件。
   *
   * @param eventType 事件类型
   * @param handler 事件处理器
   * @param <T> 事件类型参数
   * @return 用于取消订阅的 Subscription 对象
   */
  @SuppressWarnings("unchecked")
  public <T extends SignalEvent> Subscription subscribe(Class<T> eventType, Consumer<T> handler) {
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(handler, "handler");
    Consumer<? super SignalEvent> wrappedHandler = event -> handler.accept((T) event);
    subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(wrappedHandler);
    return () -> subscribers.getOrDefault(eventType, List.of()).remove(wrappedHandler);
  }

  /**
   * 订阅所有事件。
   *
   * @param handler 事件处理器
   * @return 用于取消订阅的 Subscription 对象
   */
  public Subscription subscribeAll(Consumer<SignalEvent> handler) {
    Objects.requireNonNull(handler, "handler");
    globalSubscribers.add(handler);
    return () -> globalSubscribers.remove(handler);
  }

  /**
   * 发布事件。
   *
   * <p>同步调用所有匹配的订阅者，按订阅顺序执行。
   *
   * @param event 事件实例
   */
  public void publish(SignalEvent event) {
    if (event == null) {
      return;
    }

    // 调用全局订阅者
    for (Consumer<SignalEvent> subscriber : globalSubscribers) {
      try {
        subscriber.accept(event);
      } catch (RuntimeException | LinkageError failure) {
        safeDebug("global subscriber error", failure);
      }
    }

    // 调用类型订阅者
    List<Consumer<? super SignalEvent>> handlers = subscribers.get(event.getClass());
    if (handlers != null) {
      for (Consumer<? super SignalEvent> handler : handlers) {
        try {
          handler.accept(event);
        } catch (RuntimeException | LinkageError failure) {
          safeDebug("subscriber error for " + event.eventType(), failure);
        }
      }
    }
  }

  /**
   * 尽力记录订阅者故障，不允许诊断输出再次打断同步占用事务。
   *
   * <p>TrainCarts/BKCommonLib 版本不匹配通常以 {@link LinkageError} 暴露；事件发布发生在 claim
   * 状态提交之后，因此该错误必须与普通运行时异常一样被订阅边界隔离。
   */
  private void safeDebug(String source, Throwable failure) {
    try {
      debugLogger.accept(
          "SignalEventBus: "
              + source
              + ": "
              + failure.getClass().getSimpleName()
              + ":"
              + String.valueOf(failure.getMessage()));
    } catch (RuntimeException | LinkageError ignored) {
      // 诊断通道不可用不能改变已提交的占用事实，也不能阻断同批后续订阅者。
    }
  }

  /** 获取指定事件类型的订阅者数量（用于测试和诊断）。 */
  public int subscriberCount(Class<? extends SignalEvent> eventType) {
    return subscribers.getOrDefault(eventType, List.of()).size();
  }

  /** 获取全局订阅者数量。 */
  public int globalSubscriberCount() {
    return globalSubscribers.size();
  }

  /** 获取所有订阅者的总数（用于诊断）。 */
  public int totalSubscriberCount() {
    int total = globalSubscriberCount();
    for (List<?> list : subscribers.values()) {
      total += list.size();
    }
    return total;
  }

  /** 清除所有订阅（用于测试和关闭）。 */
  public void clear() {
    subscribers.clear();
    globalSubscribers.clear();
  }

  /**
   * 获取所有已发生的事件类型的列表快照（用于诊断）。
   *
   * @return 已订阅的事件类型列表
   */
  public List<Class<? extends SignalEvent>> subscribedEventTypes() {
    return new ArrayList<>(subscribers.keySet());
  }

  /** 订阅句柄，用于取消订阅。 */
  @FunctionalInterface
  public interface Subscription {
    void unsubscribe();
  }
}
