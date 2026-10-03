package org.fetarute.fetaruteTCAddon.drive.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.OptionalLong;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StopControlMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverDirective;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverProtection.Decision;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverProtection.Intervention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("驾驶调度列车时的动作栏提示")
class DriveHudDriverTokenTest {

  private final DriverLink link = new DriverLink(UUID.randomUUID(), "T", null, () -> 0.0, () -> 0L);

  @Test
  @DisplayName("按严重程度取第一条")
  void picksTheMostSevere() {
    assertEquals("drive.hud.driver.no-signal", DriveHud.interventionKey(link));
    link.acceptDirective(
        new DriverDirective(
            SignalAspect.PROCEED,
            StopControlMode.BRAKING_TO_PLANNED_STOP,
            10.0,
            10.0,
            true,
            OptionalLong.empty(),
            null));
    assertNull(DriveHud.interventionKey(link));

    link.requestHandback("admin");
    assertEquals("drive.hud.driver.handback", DriveHud.interventionKey(link));
    link.recordDecision(new Decision(Intervention.SERVICE, 5.0, true, false));
    assertEquals("drive.hud.driver.service", DriveHud.interventionKey(link));
    link.latchEmergency();
    assertEquals("drive.hud.driver.emergency", DriveHud.interventionKey(link));
    link.recordDecision(new Decision(Intervention.CLAMP, 0.0, true, false));
    assertEquals("drive.hud.driver.forced-stop", DriveHud.interventionKey(link));
  }
}
