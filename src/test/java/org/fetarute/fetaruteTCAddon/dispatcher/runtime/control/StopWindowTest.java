package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StopWindow 停车窗口")
class StopWindowTest {

  @Test
  @DisplayName("默认 2.5 格内停准，6 格内可开门，再远分停短与越过")
  void classifyDefaults() {
    StopWindow window = StopWindow.DEFAULTS;
    assertEquals(StopAlignment.Window.ACCURATE, window.classify(-2.5));
    assertEquals(StopAlignment.Window.ACCURATE, window.classify(1.0));
    assertEquals(StopAlignment.Window.ACCEPTED, window.classify(5.9));
    assertEquals(StopAlignment.Window.ACCEPTED, window.classify(-6.0));
    assertEquals(StopAlignment.Window.SHORT, window.classify(-6.1));
    assertEquals(StopAlignment.Window.OVERRUN, window.classify(8.0));
    assertEquals(StopAlignment.Window.ACCEPTED, window.classify(Double.NaN), "量不出时不挡住停站");
  }

  @Test
  @DisplayName("自定义窗口；不合理的窗口拒绝")
  void customAndInvalid() {
    StopWindow window = new StopWindow(1.0, 3.0);
    assertEquals(StopAlignment.Window.ACCEPTED, window.classify(2.0));
    assertEquals(StopAlignment.Window.SHORT, window.classify(-3.5));
    assertFalse(StopWindow.valid(4.0, 3.0));
    assertFalse(StopWindow.valid(0.0, 3.0));
    assertFalse(StopWindow.valid(Double.NaN, 3.0));
    assertTrue(StopWindow.valid(2.5, 6.0));
    assertThrows(IllegalArgumentException.class, () -> new StopWindow(4.0, 3.0));
  }

  @Test
  @DisplayName("停站带着窗口：没设置时为默认窗口")
  void stationStopCarriesWindow() {
    DriverStationStop stop =
        new DriverStationStop(
            org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId.of("OP:S:STA:1"),
            "站",
            java.util.UUID.randomUUID(),
            new org.bukkit.util.Vector(),
            null,
            false,
            true);
    assertEquals(StopWindow.DEFAULTS, stop.window());
    stop.setWindow(new StopWindow(1.0, 3.0));
    assertEquals(StopAlignment.Window.SHORT, stop.window().classify(-3.5));
  }
}
