package org.fetarute.fetaruteTCAddon.dispatcher.signal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.TrainNameNormalizer;

/**
 * 将资源变化合并为下一 tick 的完整运行时信号重评估。
 *
 * <p>占用事件只说明“事实已经变化”，不能携带或创造 Movement Authority。本类因此只保存逻辑列车名，并在当前同步事件栈完全退出后调用唯一的完整授权入口。实际 winner 仍由
 * OccupancyManager/Gate Queue 决定。
 *
 * <p>同一逻辑列车在一个批次内只执行一次；执行期间产生的新请求进入下一批，避免 occupancy 事件与授权计算同步递归。单列车的运行时或 ABI 错误会被隔离，不能中断同批其他列车。
 */
public final class RuntimeSignalReevaluationScheduler implements AutoCloseable {

  private static final Duration DEFAULT_DRAIN_WORK_BUDGET = Duration.ofMillis(5);

  private final Object monitor = new Object();
  private final NextTickScheduler nextTickScheduler;
  private final Consumer<String> reevaluationAttempt;
  private final BiConsumer<String, Throwable> failureHandler;
  private final Consumer<String> debugLogger;
  private final long drainWorkBudgetNanos;
  private final LongSupplier nanoTime;
  private final Map<String, String> pendingByLogicalName = new LinkedHashMap<>();

  private boolean scheduled;
  private boolean draining;
  private boolean closed;

  /**
   * 构建重评估调度器。
   *
   * @param nextTickScheduler 下一 tick 调度适配器
   * @param reevaluationAttempt 完整运行时重评估入口
   */
  public RuntimeSignalReevaluationScheduler(
      NextTickScheduler nextTickScheduler, Consumer<String> reevaluationAttempt) {
    this(nextTickScheduler, reevaluationAttempt, (trainName, error) -> {}, message -> {});
  }

  /**
   * 构建重评估调度器。
   *
   * @param nextTickScheduler 下一 tick 调度适配器
   * @param reevaluationAttempt 完整运行时重评估入口
   * @param debugLogger 调试日志输出
   */
  public RuntimeSignalReevaluationScheduler(
      NextTickScheduler nextTickScheduler,
      Consumer<String> reevaluationAttempt,
      Consumer<String> debugLogger) {
    this(nextTickScheduler, reevaluationAttempt, (trainName, error) -> {}, debugLogger);
  }

  /**
   * 构建重评估调度器。
   *
   * @param nextTickScheduler 下一 tick 调度适配器
   * @param reevaluationAttempt 完整运行时重评估入口
   * @param failureHandler 单列车重评估失败后的 fail-closed 回调
   * @param debugLogger 调试日志输出
   */
  public RuntimeSignalReevaluationScheduler(
      NextTickScheduler nextTickScheduler,
      Consumer<String> reevaluationAttempt,
      BiConsumer<String, Throwable> failureHandler,
      Consumer<String> debugLogger) {
    this(
        nextTickScheduler,
        reevaluationAttempt,
        failureHandler,
        debugLogger,
        DEFAULT_DRAIN_WORK_BUDGET,
        System::nanoTime);
  }

  /**
   * 创建带主线程工作预算的重评估调度器。
   *
   * <p>预算仅防止一个已经有限的事实变更批次独占 Bukkit tick，绝不用于吞掉或隐藏重复 wake-up。剩余列车会被原样保留到下一 tick；持续相同事实的
   * 自我重排必须由授权链自身收敛。
   *
   * <p>生产一律用上面的构造器（{@link System#nanoTime()} + 默认预算），行为与本构造器公开前完全一致。它公开只为让回归骨架注入与场景时钟同源的
   * 时间：预算按墙钟计时时，冷 JVM 里一次完整授权就可能吃掉整份预算，把同批后面的列车推到下一 tick——同一场景两遍运行的放行顺序随之分叉。
   *
   * @param nextTickScheduler 下一 tick 调度适配器
   * @param reevaluationAttempt 完整运行时重评估入口
   * @param failureHandler 单列车重评估失败后的 fail-closed 回调
   * @param debugLogger 调试日志输出
   * @param drainWorkBudget 单次 drain 最多开始工作的时间预算
   * @param nanoTime 单调时间来源
   */
  public RuntimeSignalReevaluationScheduler(
      NextTickScheduler nextTickScheduler,
      Consumer<String> reevaluationAttempt,
      BiConsumer<String, Throwable> failureHandler,
      Consumer<String> debugLogger,
      Duration drainWorkBudget,
      LongSupplier nanoTime) {
    this.nextTickScheduler = Objects.requireNonNull(nextTickScheduler, "nextTickScheduler");
    this.reevaluationAttempt = Objects.requireNonNull(reevaluationAttempt, "reevaluationAttempt");
    this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    this.debugLogger = debugLogger == null ? message -> {} : debugLogger;
    Duration resolvedBudget = Objects.requireNonNull(drainWorkBudget, "drainWorkBudget");
    if (resolvedBudget.isZero() || resolvedBudget.isNegative()) {
      throw new IllegalArgumentException("drainWorkBudget 必须大于零");
    }
    this.drainWorkBudgetNanos = resolvedBudget.toNanos();
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
  }

  /**
   * 请求在下一 tick 对列车执行完整重评估。
   *
   * <p>大小写变体与 TrainCarts split 临时后缀会合并，但实际调用保留该批次最先收到的名称，供运行时的 canonical/alias 解析继续使用。
   *
   * @param trainName 列车名
   */
  public void request(String trainName) {
    String logicalName = TrainNameNormalizer.normalizeKey(trainName);
    if (logicalName.isEmpty()) {
      return;
    }
    boolean scheduleDrain = false;
    synchronized (monitor) {
      if (closed) {
        return;
      }
      pendingByLogicalName.putIfAbsent(logicalName, trainName.trim());
      if (!scheduled && !draining) {
        scheduled = true;
        scheduleDrain = true;
      }
    }
    if (scheduleDrain) {
      scheduleDrain();
    }
  }

  /**
   * 关闭调度器并丢弃尚未执行的 wake-up。
   *
   * <p>已由 Bukkit 排入下一 tick 的 Runnable 无需取消；它执行时会再次检查关闭状态，因而不会触发运行时控车。
   */
  @Override
  public void close() {
    synchronized (monitor) {
      closed = true;
      scheduled = false;
      pendingByLogicalName.clear();
    }
  }

  int pendingCount() {
    synchronized (monitor) {
      return pendingByLogicalName.size();
    }
  }

  private void scheduleDrain() {
    try {
      nextTickScheduler.schedule(this::drain);
    } catch (RuntimeException | LinkageError error) {
      List<String> failedBatch;
      synchronized (monitor) {
        scheduled = false;
        failedBatch = new ArrayList<>(pendingByLogicalName.values());
      }
      for (String trainName : failedBatch) {
        notifyFailure(trainName, error);
      }
      safeDebug(
          "信号完整重评估排队失败: error="
              + error.getClass().getSimpleName()
              + ":"
              + String.valueOf(error.getMessage()));
    }
  }

  private void drain() {
    List<String> batch;
    synchronized (monitor) {
      scheduled = false;
      if (closed) {
        pendingByLogicalName.clear();
        return;
      }
      draining = true;
      batch = new ArrayList<>(pendingByLogicalName.values());
      pendingByLogicalName.clear();
    }

    long startedAt = nanoTime.getAsLong();
    int processed = 0;
    try {
      for (; processed < batch.size() && hasRemainingWorkBudget(startedAt); processed++) {
        String trainName = batch.get(processed);
        try {
          reevaluationAttempt.accept(trainName);
        } catch (RuntimeException | LinkageError error) {
          notifyFailure(trainName, error);
          safeDebug(
              "信号完整重评估失败: train="
                  + trainName
                  + " error="
                  + error.getClass().getSimpleName()
                  + ":"
                  + String.valueOf(error.getMessage()));
        }
      }
    } finally {
      boolean scheduleNextBatch = false;
      synchronized (monitor) {
        draining = false;
        retainUnprocessedBatch(batch, processed);
        if (!closed && !pendingByLogicalName.isEmpty() && !scheduled) {
          scheduled = true;
          scheduleNextBatch = true;
        }
      }
      if (scheduleNextBatch) {
        scheduleDrain();
      }
    }
  }

  /** 将预算之外的原批次放回队首，保证它们先于 drain 中新收到的请求继续执行。 */
  private void retainUnprocessedBatch(List<String> batch, int processed) {
    if (closed || batch == null || processed >= batch.size()) {
      return;
    }
    Map<String, String> merged = new LinkedHashMap<>();
    for (int index = Math.max(0, processed); index < batch.size(); index++) {
      String trainName = batch.get(index);
      String logicalName = TrainNameNormalizer.normalizeKey(trainName);
      if (!logicalName.isEmpty()) {
        merged.putIfAbsent(logicalName, trainName);
      }
    }
    pendingByLogicalName.forEach(merged::putIfAbsent);
    pendingByLogicalName.clear();
    pendingByLogicalName.putAll(merged);
  }

  private boolean hasRemainingWorkBudget(long startedAt) {
    return nanoTime.getAsLong() - startedAt < drainWorkBudgetNanos;
  }

  private void notifyFailure(String trainName, Throwable error) {
    try {
      failureHandler.accept(trainName, error);
    } catch (RuntimeException | LinkageError handlerError) {
      safeDebug(
          "信号完整重评估故障处理失败: train="
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
      // 日志系统不能成为授权重评估批次的单点故障。
    }
  }

  /** 生产环境必须把任务安排到调用发生后的下一 Bukkit tick。 */
  @FunctionalInterface
  public interface NextTickScheduler {

    /**
     * 安排下一 tick 执行任务。
     *
     * @param task 待执行任务
     */
    void schedule(Runnable task);
  }
}
