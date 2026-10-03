package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StopControlMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverDirective;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverProtection.Decision;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverProtection.Intervention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DriverLink 控制链路")
class DriverLinkTest {

  private final AtomicLong clock = new AtomicLong(100L);
  private final double[] odometer = {0.0};
  private final DriverLink link =
      new DriverLink(UUID.randomUUID(), "T-1", null, () -> odometer[0], clock::get);

  private static DriverDirective directive(SignalAspect aspect) {
    return new DriverDirective(
        aspect,
        StopControlMode.BRAKING_TO_PLANNED_STOP,
        10.0,
        10.0,
        true,
        OptionalLong.empty(),
        null);
  }

  @Test
  @DisplayName("指令的时间与里程从收到时起算")
  void directiveAgeAndDistance() {
    assertEquals(Long.MAX_VALUE, link.ticksSinceDirective());
    odometer[0] = 5.0;
    link.acceptDirective(directive(SignalAspect.PROCEED));
    clock.addAndGet(7L);
    odometer[0] = 8.5;
    assertEquals(7L, link.ticksSinceDirective());
    assertEquals(3.5, link.travelledSinceDirective(), 1.0e-9);
  }

  @Test
  @DisplayName("调度要求停车：下一条非停车指令解除；已请求交还时不解除")
  void serviceStopReleaseRules() {
    link.requestServiceStop();
    link.acceptDirective(directive(SignalAspect.STOP));
    assertTrue(link.serviceStopRequested());
    link.acceptDirective(directive(SignalAspect.PROCEED));
    assertFalse(link.serviceStopRequested());

    link.requestHandback("deadlock");
    link.acceptDirective(directive(SignalAspect.PROCEED));
    assertTrue(link.serviceStopRequested(), "请求交还后必须停车，新的行车许可不能解除");
    assertEquals("deadlock", link.handbackReason());
    link.requestHandback("admin");
    assertEquals("deadlock", link.handbackReason(), "保留第一次交还的原因");
  }

  @Test
  @DisplayName("介入计数只在介入加重时增加")
  void interventionCounting() {
    link.recordDecision(new Decision(Intervention.SERVICE, 5.0, true, false));
    link.recordDecision(new Decision(Intervention.SERVICE, 5.0, true, false));
    link.recordDecision(new Decision(Intervention.EMERGENCY, 5.0, true, false));
    link.recordDecision(new Decision(Intervention.NONE, 5.0, false, false));
    link.recordDecision(new Decision(Intervention.CLAMP, 0.0, true, false));
    assertEquals(1, link.serviceInterventions());
    assertEquals(1, link.emergencyInterventions());
    assertEquals(1, link.forcedStops());
  }

  @Test
  @DisplayName("ATO 下不由驾驶员物理控车")
  void atoDoesNotControlPhysically() {
    assertTrue(link.controlsPhysically());
    link.setMode(DrivingMode.ATO);
    assertFalse(link.controlsPhysically());
  }
}
