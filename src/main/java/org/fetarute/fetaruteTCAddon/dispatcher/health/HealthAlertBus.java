package org.fetarute.fetaruteTCAddon.dispatcher.health;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 健康告警总线：分发 {@link HealthAlert} 给所有监听器。
 *
 * <p>支持限流避免短时间内刷屏，并记录最近 N 条告警供诊断命令查询。
 */
public final class HealthAlertBus {

  /** 最大缓存告警数量。 */
  private static final int MAX_HISTORY_SIZE = 100;

  /** 同类型告警最小间隔（毫秒）。 */
  private static final long THROTTLE_MS = 5000L;

  private final List<Consumer<HealthAlert>> listeners = new CopyOnWriteArrayList<>();
  private final java.util.Deque<HealthAlert> history =
      new java.util.concurrent.ConcurrentLinkedDeque<>();
  private final java.util.concurrent.ConcurrentMap<String, Long> lastAlertAtMs =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** 注册告警监听器。 */
  public void subscribe(Consumer<HealthAlert> listener) {
    if (listener != null) {
      listeners.add(listener);
    }
  }

  /** 移除监听器。 */
  public void unsubscribe(Consumer<HealthAlert> listener) {
    if (listener != null) {
      listeners.remove(listener);
    }
  }

  /**
   * 发布告警（带限流）。
   *
   * @param alert 告警事件
   * @return true 表示告警被分发，false 表示被限流跳过
   */
  public boolean publish(HealthAlert alert) {
    if (alert == null) {
      return false;
    }
    // 限流：同类型 + 同列车的告警在 THROTTLE_MS 内只发一次
    String key = buildThrottleKey(alert);
    long now = System.currentTimeMillis();
    Long last = lastAlertAtMs.get(key);
    if (last != null && now - last < THROTTLE_MS) {
      return false;
    }
    lastAlertAtMs.put(key, now);

    // 记录历史
    history.addLast(alert);
    while (history.size() > MAX_HISTORY_SIZE) {
      history.pollFirst();
    }

    // 分发
    for (Consumer<HealthAlert> listener : listeners) {
      try {
        listener.accept(alert);
      } catch (Exception e) {
        // 防止单个监听器异常影响其他
      }
    }
    return true;
  }

  /** 获取最近 N 条告警历史。 */
  public List<HealthAlert> recentAlerts(int limit) {
    if (limit <= 0) {
      return List.of();
    }
    List<HealthAlert> snapshot = List.copyOf(history);
    int start = Math.max(0, snapshot.size() - limit);
    return snapshot.subList(start, snapshot.size());
  }

  /** 清除历史和限流状态。 */
  public void clear() {
    history.clear();
    lastAlertAtMs.clear();
  }

  private String buildThrottleKey(HealthAlert alert) {
    String train = alert.trainName() == null ? "" : alert.trainName();
    return alert.type().name() + ":" + train;
  }
}
