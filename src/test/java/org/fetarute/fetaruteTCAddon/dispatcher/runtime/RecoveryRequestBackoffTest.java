package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 恢复请求退避与单车升级限流的纯逻辑测试。 */
class RecoveryRequestBackoffTest {

  private long nowNanos;

  @Test
  void consecutiveRequestsBackOffAndQuietPeriodResets() {
    RecoveryRequestBackoff backoff =
        new RecoveryRequestBackoff(Duration.ofSeconds(10), () -> nowNanos);

    assertEquals(1L, backoff.nextDelayTicks());
    nowNanos += Duration.ofMillis(50).toNanos();
    assertEquals(5L, backoff.nextDelayTicks());
    nowNanos += Duration.ofMillis(250).toNanos();
    assertEquals(20L, backoff.nextDelayTicks());
    nowNanos += Duration.ofSeconds(1).toNanos();
    assertEquals(20L, backoff.nextDelayTicks());
    assertEquals(4, backoff.consecutiveRequests());

    nowNanos += Duration.ofSeconds(10).toNanos();
    assertEquals(1L, backoff.nextDelayTicks(), "安静超过窗口后重新从最快的一档开始");
    assertEquals(1, backoff.consecutiveRequests());
  }

  @Test
  void limiterAllowsUpToLimitPerIdentityThenRecoversAfterWindow() {
    StartupEscalationLimiter limiter = new StartupEscalationLimiter(3, Duration.ofSeconds(30));
    Object trainA = new Object();
    Object trainB = new Object();
    Instant t0 = Instant.parse("2026-09-30T01:00:00Z");

    assertTrue(limiter.tryAcquire(trainA, t0));
    assertTrue(limiter.tryAcquire(trainA, t0.plusSeconds(1)));
    assertTrue(limiter.tryAcquire(trainA, t0.plusSeconds(2)));
    assertFalse(limiter.tryAcquire(trainA, t0.plusSeconds(3)));
    assertTrue(limiter.tryAcquire(trainB, t0.plusSeconds(3)), "另一辆车不受牵连");

    assertTrue(limiter.tryAcquire(trainA, t0.plusSeconds(33)), "窗口滑出后重新给一次机会");
  }

  @Test
  void limiterForgetsRemovedIdentityAndIgnoresUnknownOwner() {
    StartupEscalationLimiter limiter = new StartupEscalationLimiter(1, Duration.ofSeconds(30));
    Object train = new Object();
    Instant now = Instant.parse("2026-09-30T01:00:00Z");

    assertTrue(limiter.tryAcquire(train, now));
    assertFalse(limiter.tryAcquire(train, now));
    limiter.forget(train);
    assertEquals(0, limiter.trackedIdentityCount());
    assertTrue(limiter.tryAcquire(train, now));
    assertTrue(limiter.tryAcquire(null, now), "无从区分是谁时不限流");
  }
}
