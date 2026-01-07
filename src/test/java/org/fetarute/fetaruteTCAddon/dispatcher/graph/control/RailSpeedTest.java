package org.fetarute.fetaruteTCAddon.dispatcher.graph.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RailSpeedTest {

  @Test
  void convertsBetweenUnits() {
    RailSpeed speed = RailSpeed.ofBlocksPerSecond(8.0);
    assertEquals(0.4, speed.blocksPerTick(), 1e-9);
    assertEquals(28.8, speed.kilometersPerHour(), 1e-9);

    RailSpeed fromBpt = RailSpeed.ofBlocksPerTick(0.4);
    assertEquals(8.0, fromBpt.blocksPerSecond(), 1e-9);

    RailSpeed fromKmh = RailSpeed.ofKilometersPerHour(72.0);
    assertEquals(20.0, fromKmh.blocksPerSecond(), 1e-9);
    assertEquals(1.0, fromKmh.blocksPerTick(), 1e-9);
  }

  @Test
  void rejectsNonPositiveValues() {
    assertThrows(IllegalArgumentException.class, () -> RailSpeed.ofBlocksPerSecond(0.0));
    assertThrows(IllegalArgumentException.class, () -> RailSpeed.ofBlocksPerTick(0.0));
    assertThrows(IllegalArgumentException.class, () -> RailSpeed.ofKilometersPerHour(0.0));
  }

  @Test
  void formatsAllUnits() {
    String text = RailSpeed.ofBlocksPerSecond(8.0).formatWithAllUnits();
    assertTrue(text.contains("km/h"));
    assertTrue(text.contains("bps"));
    assertTrue(text.contains("bpt"));
  }
}
