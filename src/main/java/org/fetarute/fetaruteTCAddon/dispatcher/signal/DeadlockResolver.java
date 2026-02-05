package org.fetarute.fetaruteTCAddon.dispatcher.signal;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.DeadlockDetectedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.DeadlockResolvedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.OccupancyAcquiredEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalChangedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalEventBus;

/**
 * 死锁解决器：检测并解决信号系统中的死锁。
 *
 * <p>职责：
 *
 * <ul>
 *   <li>订阅 {@link OccupancyAcquiredEvent}，检测潜在死锁
 *   <li>维护死锁放行锁，避免信号乒乓
 *   <li>解决死锁后发布 {@link DeadlockResolvedEvent}
 * </ul>
 *
 * <p>死锁检测策略：当两个或多个列车互相阻塞形成循环依赖时，选择优先级最高的列车放行。
 */
public class DeadlockResolver {

  /** 死锁放行锁定时长。 */
  private static final Duration LOCK_TTL = Duration.ofSeconds(8);

  private final SignalEventBus eventBus;
  private final Consumer<String> debugLogger;

  /** 死锁放行锁：key=冲突资源 key，value=被放行列车与锁定过期时间。 */
  private final Map<String, DeadlockLock> locks = new LinkedHashMap<>();

  private SignalEventBus.Subscription acquiredSubscription;

  /**
   * 构建死锁解决器。
   *
   * @param eventBus 事件总线
   */
  public DeadlockResolver(SignalEventBus eventBus) {
    this(eventBus, msg -> {});
  }

  /**
   * 构建死锁解决器。
   *
   * @param eventBus 事件总线
   * @param debugLogger 调试日志输出
   */
  public DeadlockResolver(SignalEventBus eventBus, Consumer<String> debugLogger) {
    this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    this.debugLogger = debugLogger != null ? debugLogger : msg -> {};
  }

  /** 启动解决器，订阅事件。 */
  public void start() {
    if (acquiredSubscription != null) {
      return;
    }
    acquiredSubscription = eventBus.subscribe(OccupancyAcquiredEvent.class, this::onAcquired);
    debugLogger.accept("DeadlockResolver 已启动");
  }

  /** 停止解决器，取消订阅。 */
  public void stop() {
    if (acquiredSubscription != null) {
      acquiredSubscription.unsubscribe();
      acquiredSubscription = null;
    }
    locks.clear();
    debugLogger.accept("DeadlockResolver 已停止");
  }

  /**
   * 检查指定列车是否持有死锁放行锁。
   *
   * @param trainName 列车名
   * @param conflictKey 冲突资源 key
   * @return 是否持有有效锁
   */
  public boolean holdsLock(String trainName, String conflictKey) {
    if (trainName == null || conflictKey == null) {
      return false;
    }
    DeadlockLock lock = locks.get(conflictKey);
    if (lock == null || lock.isExpired(Instant.now())) {
      return false;
    }
    return lock.trainName().equalsIgnoreCase(trainName);
  }

  /**
   * 授予死锁放行锁。
   *
   * @param trainName 被放行的列车
   * @param conflictKey 冲突资源 key
   * @param now 当前时间
   * @return 是否成功授予（若已被其他列车持有则失败）
   */
  public boolean grantLock(String trainName, String conflictKey, Instant now) {
    if (trainName == null || conflictKey == null || now == null) {
      return false;
    }
    DeadlockLock existing = locks.get(conflictKey);
    if (existing != null && !existing.isExpired(now)) {
      if (!existing.trainName().equalsIgnoreCase(trainName)) {
        return false; // 其他列车持有锁
      }
    }
    locks.put(conflictKey, new DeadlockLock(trainName, now.plus(LOCK_TTL)));
    debugLogger.accept("死锁放行锁授予: train=" + trainName + " conflict=" + conflictKey);
    return true;
  }

  /** 释放指定列车的所有死锁放行锁。 */
  public void releaseLocks(String trainName) {
    if (trainName == null) {
      return;
    }
    locks.entrySet().removeIf(e -> e.getValue().trainName().equalsIgnoreCase(trainName));
  }

  /** 清理过期锁。 */
  public void purgeExpiredLocks(Instant now) {
    if (now == null) {
      return;
    }
    locks.entrySet().removeIf(e -> e.getValue().isExpired(now));
  }

  /** 获取当前锁数量（用于诊断）。 */
  public int lockCount() {
    return locks.size();
  }

  /** 处理占用获取事件：检测潜在死锁。 */
  private void onAcquired(OccupancyAcquiredEvent event) {
    if (event == null) {
      return;
    }
    // 当前实现仅清理过期锁；死锁检测逻辑保留在 SimpleOccupancyManager 中
    // 后续可将 tryResolveConflictDeadlock 逻辑迁移到此处
    purgeExpiredLocks(event.timestamp());
  }

  /**
   * 发布死锁检测事件。
   *
   * @param conflictResource 冲突资源
   * @param involvedTrains 涉及的列车
   * @param description 描述
   * @param now 当前时间
   */
  public void publishDetected(
      org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource conflictResource,
      List<String> involvedTrains,
      String description,
      Instant now) {
    DeadlockDetectedEvent event =
        new DeadlockDetectedEvent(now, involvedTrains, conflictResource, description);
    eventBus.publish(event);
  }

  /**
   * 发布死锁解决事件。
   *
   * @param conflictResource 冲突资源
   * @param releasedTrain 被放行的列车
   * @param lockDuration 锁定时长
   * @param now 当前时间
   */
  public void publishResolved(
      org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource conflictResource,
      String releasedTrain,
      Duration lockDuration,
      Instant now) {
    DeadlockResolvedEvent event =
        new DeadlockResolvedEvent(now, releasedTrain, conflictResource, lockDuration);
    eventBus.publish(event);
    // 触发被放行列车的信号变化
    SignalChangedEvent signalEvent =
        new SignalChangedEvent(now, releasedTrain, SignalAspect.STOP, SignalAspect.PROCEED);
    eventBus.publish(signalEvent);
  }

  /** 死锁放行锁记录。 */
  private record DeadlockLock(String trainName, Instant expiresAt) {
    boolean isExpired(Instant now) {
      return now.isAfter(expiresAt);
    }
  }
}
