package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("信号确认")
class SignalAcknowledgeTest {

  private static void hold(
      SignalAcknowledge c, SignalAspect aspect, long from, long to, boolean simulation) {
    for (long t = from; t <= to; t++) {
      c.observe(aspect, t, true, simulation);
    }
  }

  @Test
  @DisplayName("变严稳定一小段后才要求确认；standard 级“注意”不用确认")
  void debounceAndStandardLevel() {
    SignalAcknowledge c = new SignalAcknowledge();
    hold(c, SignalAspect.PROCEED_WITH_CAUTION, 0, 20, false);
    assertFalse(c.pending(), "standard 级只对减速与停车要求确认");
    hold(c, SignalAspect.STOP, 21, 25, false);
    assertFalse(c.pending(), "还没稳定");
    hold(c, SignalAspect.STOP, 26, 40, false);
    assertTrue(c.pending());
    assertEquals(SignalAspect.STOP, c.pendingAspect());
  }

  @Test
  @DisplayName("simulation 级每次变严都要确认；停着时不要求")
  void simulationAndStopped() {
    SignalAcknowledge c = new SignalAcknowledge();
    hold(c, SignalAspect.PROCEED_WITH_CAUTION, 0, 20, true);
    assertTrue(c.pending());
    SignalAcknowledge stopped = new SignalAcknowledge();
    for (long t = 0; t <= 20; t++) {
      stopped.observe(SignalAspect.STOP, t, false, true);
    }
    assertFalse(stopped.pending());
  }

  @Test
  @DisplayName("确认记反应时间；不确认先常用制动再紧急制动，漏确认只记一次")
  void acknowledgeAndEscalate() {
    SignalAcknowledge c = new SignalAcknowledge();
    hold(c, SignalAspect.STOP, 0, 10, false);
    assertTrue(c.pending());
    assertEquals(20L, c.acknowledge(30).orElseThrow());
    assertEquals(1, c.acknowledgements());
    assertEquals(1.0, c.averageReactionSeconds(), 1e-9);
    assertFalse(c.pending());

    hold(c, SignalAspect.PROCEED, 31, 31, false);
    hold(c, SignalAspect.CAUTION, 32, 42, false);
    assertTrue(c.pending());
    assertEquals(SignalAcknowledge.Intervention.NONE, c.intervention(42 + 79, true));
    assertEquals(SignalAcknowledge.Intervention.SERVICE, c.intervention(42 + 80, true));
    assertEquals(SignalAcknowledge.Intervention.SERVICE, c.intervention(42 + 100, true));
    assertEquals(SignalAcknowledge.Intervention.EMERGENCY, c.intervention(42 + 160, true));
    assertEquals(SignalAcknowledge.Intervention.NONE, c.intervention(42 + 160, false), "停稳后不再升级");
    assertEquals(1, c.misses());
  }

  @Test
  @DisplayName("信号放宽时等着的确认作废")
  void relaxCancels() {
    SignalAcknowledge c = new SignalAcknowledge();
    hold(c, SignalAspect.STOP, 0, 10, false);
    assertTrue(c.pending());
    c.observe(SignalAspect.PROCEED, 11, true, false);
    assertFalse(c.pending());
  }
}
