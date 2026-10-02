package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 主线程运行时巡检的一轮可续跑工作周期。
 *
 * <p>调用方只需在周期空闲时提供候选快照，并在每个 Bukkit tick 调用 {@link #run(Collection, Consumer)}。本类把候选保留为稳定 FIFO
 * 游标：预算耗尽时不丢弃未处理列车，下一个 tick 从同一候选继续；只有当前周期全部完成后，才会接纳新的候选快照。
 *
 * <p>预算是协作式的，不能中断已经开始的单个列车处理。因此调用方仍须保证单个工作项本身有界；本类保证不会在一个预算窗口内开始下一个工作项。 处理器抛出异常时，当前候选不会前移，调用方可以在既有
 * fail-closed 边界完成恢复后决定是否再次运行。
 *
 * @param <T> 一个可独立完成的主线程工作项类型
 */
public final class RuntimeDispatchWorkCycle<T> {

  private final long maxWorkNanos;
  private final LongSupplier nanoTime;
  private final Deque<T> pending = new ArrayDeque<>();

  /**
   * 使用单调时间源创建工作周期。
   *
   * @param maxWorkDuration 单次调用最多开始工作的时间预算
   * @param nanoTime 单调时间来源；生产环境使用 {@link System#nanoTime()}
   */
  public RuntimeDispatchWorkCycle(Duration maxWorkDuration, LongSupplier nanoTime) {
    Duration resolvedDuration = Objects.requireNonNull(maxWorkDuration, "maxWorkDuration");
    if (resolvedDuration.isNegative() || resolvedDuration.isZero()) {
      throw new IllegalArgumentException("maxWorkDuration 必须大于零");
    }
    this.maxWorkNanos = resolvedDuration.toNanos();
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
  }

  /**
   * 在本次预算内处理尽可能多的候选。
   *
   * <p>当存在未完成周期时，{@code cycleCandidates} 不会替换 pending 队列，保证 duplicate 检测、收尾清理与列车处理都基于同一候选快照。
   * 空闲周期才复制新的候选，避免调用方在处理途中改变游标语义。
   *
   * @param cycleCandidates 当前巡检得到的候选快照
   * @param processor 单个候选的完整处理；正常返回后才视为该候选完成
   * @return 本次处理数量、剩余数量及当前周期是否完成
   */
  public CycleResult run(Collection<? extends T> cycleCandidates, Consumer<? super T> processor) {
    Objects.requireNonNull(cycleCandidates, "cycleCandidates");
    Objects.requireNonNull(processor, "processor");
    if (pending.isEmpty()) {
      for (T candidate : cycleCandidates) {
        pending.addLast(Objects.requireNonNull(candidate, "cycleCandidates 不能包含 null"));
      }
    }

    int processedCount = 0;
    long startedAt = nanoTime.getAsLong();
    while (!pending.isEmpty() && hasRemainingBudget(startedAt)) {
      T candidate = pending.peekFirst();
      processor.accept(candidate);
      pending.removeFirst();
      processedCount++;
    }
    return new CycleResult(processedCount, pending.size(), pending.isEmpty());
  }

  private boolean hasRemainingBudget(long startedAt) {
    return nanoTime.getAsLong() - startedAt < maxWorkNanos;
  }

  /** 单次预算调用的可观测结果。 */
  public record CycleResult(int processedCount, int remainingCount, boolean completed) {}
}
