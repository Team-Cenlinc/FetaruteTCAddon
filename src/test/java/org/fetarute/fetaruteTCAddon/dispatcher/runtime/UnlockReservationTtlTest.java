package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.junit.jupiter.api.Test;

/**
 * unlock 预约的 TTL 必须长于它所等待的物理过程。
 *
 * <p>预约等的是"被提升优先级的列车真的走出去、把资源让出来"。TTL 的单位是 50ms 的信号 tick （{@code currentSignalTraceTick() =
 * System.currentTimeMillis() / 50}），所以旧默认值 60 其实是 **3 秒**—— 而 trace 里印出来是 {@code ttl=60}，读起来像 60 秒。
 *
 * <p>实服 2026-09-13 第四轮实测「列车被放行 → 到达下一个节点」：中位数 **21 秒**、p75 31 秒、p90 66 秒， 只有 **3%** 能在 3 秒内完成。后果是
 * 68 个预约全部在 2–4 秒内夭折，66 次 no-release-timeout 里 **65 次 {@code
 * currentNodeChanged=true}**——列车明明已经动起来了，却被 3 秒的秒表判为失败。 恢复层因此在"终于能执行"之后仍然 97% 必然失败。
 *
 * <p>本用例钉住的是量纲关系，不是某个具体数字：**TTL 必须覆盖实测的移动耗时**。
 */
class UnlockReservationTtlTest {

  /** 实服实测的"放行 → 到达下一节点"耗时（秒）。 */
  private static final int OBSERVED_MEDIAN_CLEAR_SECONDS = 21;

  private static final int OBSERVED_P75_CLEAR_SECONDS = 31;

  /** 一个信号 tick 的毫秒数，与 {@code currentSignalTraceTick()} 保持一致。 */
  private static final long SIGNAL_TICK_MILLIS = 50L;

  @Test
  void defaultReservationTtlOutlivesTheMoveItWaitsFor() {
    long ttlSeconds =
        ConfigManager.SmartDispatcherPlannerSettings.defaults().reservationTtlTicks()
            * SIGNAL_TICK_MILLIS
            / 1000L;

    assertTrue(
        ttlSeconds > OBSERVED_P75_CLEAR_SECONDS,
        "预约 TTL "
            + ttlSeconds
            + "s 必须长于实测 p75 的 "
            + OBSERVED_P75_CLEAR_SECONDS
            + "s，否则恢复层在列车走完之前就判自己失败");
  }

  /** 显式钉住"3 秒是不够的"——这正是回归时最容易被改回去的值。 */
  @Test
  void threeSecondTtlWouldBeShorterThanMostMoves() {
    long oldTtlSeconds = 60 * SIGNAL_TICK_MILLIS / 1000L;

    assertTrue(oldTtlSeconds < OBSERVED_MEDIAN_CLEAR_SECONDS, "旧默认值 60 tick 就是 3 秒，短于中位数移动耗时");
  }
}
