package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 恢复请求退避的纯逻辑测试。 */
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
}
