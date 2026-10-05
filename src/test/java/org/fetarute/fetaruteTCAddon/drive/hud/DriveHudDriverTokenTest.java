package org.fetarute.fetaruteTCAddon.drive.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.Optional;
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

  private static Optional<DriverStationHint.Hint> hint(DriverStationHint.Kind kind) {
    return Optional.of(new DriverStationHint.Hint(kind, "", Map.of()));
  }

  @Test
  @DisplayName("侧边栏看得到时动作栏只放要动手的车站提示；看不到时全放")
  void stationSlot() {
    assertEquals(DriveHud.StationSlot.NONE, DriveHud.stationSlot(Optional.empty(), true));
    assertEquals(
        DriveHud.StationSlot.SHOW,
        DriveHud.stationSlot(hint(DriverStationHint.Kind.MOVE_UP), true));
    assertEquals(
        DriveHud.StationSlot.SHOW,
        DriveHud.stationSlot(hint(DriverStationHint.Kind.OPEN_DOORS), true));
    assertEquals(
        DriveHud.StationSlot.SHOW, DriveHud.stationSlot(hint(DriverStationHint.Kind.DEPART), true));
    assertEquals(
        DriveHud.StationSlot.NONE,
        DriveHud.stationSlot(hint(DriverStationHint.Kind.APPROACH), true),
        "进站距离只在侧边栏，动作栏接着看车门、停车信号");
    assertEquals(
        DriveHud.StationSlot.SUPPRESS,
        DriveHud.stationSlot(hint(DriverStationHint.Kind.DWELL), true),
        "停站计时时车门开着是正常的");
    assertEquals(
        DriveHud.StationSlot.SUPPRESS,
        DriveHud.stationSlot(hint(DriverStationHint.Kind.WAIT_DEPARTURE), true));
    assertEquals(
        DriveHud.StationSlot.SHOW,
        DriveHud.stationSlot(hint(DriverStationHint.Kind.APPROACH), false));
    assertEquals(
        DriveHud.StationSlot.SHOW, DriveHud.stationSlot(hint(DriverStationHint.Kind.DWELL), false));
  }
}
