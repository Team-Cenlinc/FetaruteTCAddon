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

  @Test
  @DisplayName("停车窗口：默认 2.5 格内停准，6 格内可开门，再远分停短与越过")
  void classify() {
    StopAlignment.configure(
        StopAlignment.DEFAULT_ACCURATE_BLOCKS, StopAlignment.DEFAULT_ACCEPT_BLOCKS);
    assertEquals(StopAlignment.Window.ACCURATE, StopAlignment.classify(-2.5));
    assertEquals(StopAlignment.Window.ACCURATE, StopAlignment.classify(1.0));
    assertEquals(StopAlignment.Window.ACCEPTED, StopAlignment.classify(5.9));
    assertEquals(StopAlignment.Window.ACCEPTED, StopAlignment.classify(-6.0));
    assertEquals(StopAlignment.Window.SHORT, StopAlignment.classify(-6.1));
    assertEquals(StopAlignment.Window.OVERRUN, StopAlignment.classify(8.0));
    assertEquals(StopAlignment.Window.ACCEPTED, StopAlignment.classify(Double.NaN), "量不出时不挡住停站");
  }

  @Test
  @DisplayName("窗口可配置；不合理的配置退回默认值")
  void configure() {
    try {
      StopAlignment.configure(1.0, 3.0);
      assertEquals(StopAlignment.Window.ACCEPTED, StopAlignment.classify(2.0));
      assertEquals(StopAlignment.Window.SHORT, StopAlignment.classify(-3.5));
      StopAlignment.configure(4.0, 3.0);
      assertEquals(StopAlignment.DEFAULT_ACCEPT_BLOCKS, StopAlignment.acceptBlocks(), 1e-9);
    } finally {
      StopAlignment.configure(
          StopAlignment.DEFAULT_ACCURATE_BLOCKS, StopAlignment.DEFAULT_ACCEPT_BLOCKS);
    }
  }
}
