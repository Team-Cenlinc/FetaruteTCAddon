package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * 运行时占用重建请求的退避：首个请求尽快执行，连续请求逐级拉长间隔。
 *
 * <p>迟加载/重组列车触发的重建需要尽快收敛，所以第一次只等 1 tick。但如果重建之后马上又有新的请求（同一批问题没被治好）， 每 tick 一轮的重建只会把主线程和 lifecycle
 * 日志刷满。连续请求之间的间隔因此按 1 → 5 → 20 tick 递增；安静超过 {@code quietPeriod} 后重新从 1 tick 开始。
 *
 * <p>仅在 Bukkit 主线程使用。
 */
public final class RecoveryRequestBackoff {

  static final long FIRST_DELAY_TICKS = 1L;
  static final long SECOND_DELAY_TICKS = 5L;
  static final long STEADY_DELAY_TICKS = 20L;

  private final long quietPeriodNanos;
  private final LongSupplier nanoTime;
  private int consecutiveRequests;
  private long lastRequestAtNanos;

  public RecoveryRequestBackoff(Duration quietPeriod, LongSupplier nanoTime) {
    if (quietPeriod.isZero() || quietPeriod.isNegative()) {
      throw new IllegalArgumentException("quietPeriod 必须大于零");
    }
    this.quietPeriodNanos = quietPeriod.toNanos();
    this.nanoTime = nanoTime;
  }

  /** 记录一次恢复请求并返回本次应等待的 tick 数。 */
  public long nextDelayTicks() {
    long now = nanoTime.getAsLong();
    if (consecutiveRequests == 0 || now - lastRequestAtNanos >= quietPeriodNanos) {
      consecutiveRequests = 0;
    }
    lastRequestAtNanos = now;
    int index = consecutiveRequests++;
    if (index == 0) {
      return FIRST_DELAY_TICKS;
    }
    return index == 1 ? SECOND_DELAY_TICKS : STEADY_DELAY_TICKS;
  }

  /** 当前这一串连续请求已经有多少次（含最近一次）；供风暴告警使用。 */
  public int consecutiveRequests() {
    return consecutiveRequests;
  }
}
