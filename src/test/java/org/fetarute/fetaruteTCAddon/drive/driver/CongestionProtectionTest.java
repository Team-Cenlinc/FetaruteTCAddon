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

@DisplayName("拥堵保护")
class CongestionProtectionTest {

  private static final DriverRecovery RECOVERY = DriverRecovery.defaults();
  private static final Instant NOW = Instant.parse("2026-10-03T08:00:00Z");

  private static Map<String, CongestionProtection.Hold> chain(int count, String head) {
    Map<String, CongestionProtection.Hold> holds = new HashMap<>();
    String blocker = head;
    for (int i = 0; i < count; i++) {
      String train = "t" + i;
      holds.put(train, new CongestionProtection.Hold(Duration.ofSeconds(90), Set.of(blocker)));
      blocker = train;
    }
    return holds;
  }

  @Test
  @DisplayName("被扣的车够多且阻挡链追到驾驶员车：触发拥堵保护并进入冷却")
  void tripsWhenChainReachesDriver() {
    CongestionProtection protection = new CongestionProtection();
    assertTrue(protection.evaluate(chain(5, "drv"), Set.of("drv"), RECOVERY, NOW));
    assertTrue(protection.open(NOW.plusSeconds(60)));
    assertFalse(protection.evaluate(chain(5, "drv"), Set.of("drv"), RECOVERY, NOW), "冷却期内不重复触发");
    assertFalse(
        protection.open(NOW.plus(Duration.ofMinutes(RECOVERY.protectionCooldownMinutes()))));
    protection.reset();
    assertFalse(protection.open(NOW));
  }

  @Test
  @DisplayName("车不够多、链上没有驾驶员车、扣得不够久都不触发")
  void staysClosedOtherwise() {
    CongestionProtection protection = new CongestionProtection();
    assertFalse(protection.evaluate(chain(4, "drv"), Set.of("drv"), RECOVERY, NOW));
    assertFalse(protection.evaluate(chain(6, "auto"), Set.of("drv"), RECOVERY, NOW));
    Map<String, CongestionProtection.Hold> brief = chain(6, "drv");
    brief.replaceAll((k, v) -> new CongestionProtection.Hold(Duration.ofSeconds(10), v.blockers()));
    assertFalse(protection.evaluate(brief, Set.of("drv"), RECOVERY, NOW));
  }

  @Test
  @DisplayName("阻挡链有环时不会死循环")
  void cycleTerminates() {
    Map<String, CongestionProtection.Hold> holds = new HashMap<>();
    holds.put("a", new CongestionProtection.Hold(Duration.ofSeconds(90), Set.of("b")));
    holds.put("b", new CongestionProtection.Hold(Duration.ofSeconds(90), Set.of("a")));
    assertFalse(CongestionProtection.chainReachesDriver("a", holds, Set.of("drv")));
  }
}
