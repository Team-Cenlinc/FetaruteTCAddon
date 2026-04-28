package org.fetarute.fetaruteTCAddon.dispatcher.graph.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SpeedSettingStickConfigTest {

  @Test
  void parsesSpeedAndTtl() {
    Optional<SpeedSettingStickConfig> config =
        SpeedSettingStickConfig.parse("40kmh", Optional.of("5m"));

    assertTrue(config.isPresent());
    assertEquals(Duration.ofMinutes(5), config.get().ttl().orElseThrow());
    assertEquals(
        Instant.parse("2026-01-01T00:05:00Z"),
        config.get().tempUntil(Instant.parse("2026-01-01T00:00:00Z")).orElseThrow());
  }

  @Test
  void rejectsInvalidTtl() {
    assertTrue(SpeedSettingStickConfig.parse("40kmh", Optional.of("later")).isEmpty());
  }
}
