package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StopAlignment 停车对位")
class StopAlignmentTest {

  @Test
  @DisplayName("沿前进方向量：越过为正，未到为负，只看水平面")
  void signedOffsetAlongTravel() {
    Vector stop = new Vector(100.5, 64.0, 200.5);
    Vector east = new Vector(1, 0, 0);
    assertEquals(3.0, StopAlignment.signedOffset(new Vector(103.5, 70.0, 200.5), stop, east), 1e-9);
    assertEquals(-2.0, StopAlignment.signedOffset(new Vector(98.5, 64.0, 201.5), stop, east), 1e-9);
    Vector diagonal = new Vector(1, 0, 1);
    assertEquals(
        Math.sqrt(2.0),
        StopAlignment.signedOffset(new Vector(101.5, 64.0, 201.5), stop, diagonal),
        1e-9);
    assertTrue(Double.isNaN(StopAlignment.signedOffset(stop, stop, new Vector(0, 1, 0))));
  }
}
