package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("拥堵熔断")
class DriverCircuitBreakerTest {

  private static final DriverRecovery RECOVERY = DriverRecovery.defaults();
  private static final Instant NOW = Instant.parse("2026-10-03T08:00:00Z");

  private static Map<String, DriverCircuitBreaker.Hold> chain(int count, String head) {
    Map<String, DriverCircuitBreaker.Hold> holds = new HashMap<>();
    String blocker = head;
    for (int i = 0; i < count; i++) {
      String train = "t" + i;
      holds.put(train, new DriverCircuitBreaker.Hold(Duration.ofSeconds(90), Set.of(blocker)));
      blocker = train;
    }
    return holds;
  }

  @Test
  @DisplayName("被扣的车够多且阻挡链追到驾驶员车：熔断并进入冷却")
  void tripsWhenChainReachesDriver() {
    DriverCircuitBreaker breaker = new DriverCircuitBreaker();
    assertTrue(breaker.evaluate(chain(5, "drv"), Set.of("drv"), RECOVERY, NOW));
    assertTrue(breaker.open(NOW.plusSeconds(60)));
    assertFalse(breaker.evaluate(chain(5, "drv"), Set.of("drv"), RECOVERY, NOW), "冷却期内不重复触发");
    assertFalse(breaker.open(NOW.plus(Duration.ofMinutes(RECOVERY.breakerCooldownMinutes()))));
    breaker.reset();
    assertFalse(breaker.open(NOW));
  }

  @Test
  @DisplayName("车不够多、链上没有驾驶员车、扣得不够久都不熔断")
  void staysClosedOtherwise() {
    DriverCircuitBreaker breaker = new DriverCircuitBreaker();
    assertFalse(breaker.evaluate(chain(4, "drv"), Set.of("drv"), RECOVERY, NOW));
    assertFalse(breaker.evaluate(chain(6, "auto"), Set.of("drv"), RECOVERY, NOW));
    Map<String, DriverCircuitBreaker.Hold> brief = chain(6, "drv");
    brief.replaceAll((k, v) -> new DriverCircuitBreaker.Hold(Duration.ofSeconds(10), v.blockers()));
    assertFalse(breaker.evaluate(brief, Set.of("drv"), RECOVERY, NOW));
  }

  @Test
  @DisplayName("阻挡链有环时不会死循环")
  void cycleTerminates() {
    Map<String, DriverCircuitBreaker.Hold> holds = new HashMap<>();
    holds.put("a", new DriverCircuitBreaker.Hold(Duration.ofSeconds(90), Set.of("b")));
    holds.put("b", new DriverCircuitBreaker.Hold(Duration.ofSeconds(90), Set.of("a")));
    assertFalse(DriverCircuitBreaker.chainReachesDriver("a", holds, Set.of("drv")));
  }
}
