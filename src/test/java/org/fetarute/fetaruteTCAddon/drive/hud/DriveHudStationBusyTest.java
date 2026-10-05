package org.fetarute.fetaruteTCAddon.drive.hud;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("停站要驾驶员操作时停站提示优先")
class DriveHudStationBusyTest {

  @Test
  @DisplayName("等开门、停站、等关门时优先；进站、等待发车（终点站待命）时不优先")
  void busyPhases() {
    DriverLink link = new DriverLink(UUID.randomUUID(), "T", null, () -> 0.0, () -> 0L);
    assertFalse(DriveHud.stationBusy(link));
    assertFalse(DriveHud.stationBusy(null));
    DriverStationStop stop =
        new DriverStationStop(
            NodeId.of("OP:S:END:1"), "站", UUID.randomUUID(), new Vector(), null, false, true);
    link.beginStationStop(stop);
    assertFalse(DriveHud.stationBusy(link), "进站");
    stop.setPhase(DriverStationStop.Phase.OPEN_DOORS);
    assertTrue(DriveHud.stationBusy(link));
    stop.setPhase(DriverStationStop.Phase.DWELL);
    assertTrue(DriveHud.stationBusy(link));
    stop.setPhase(DriverStationStop.Phase.CLOSE_DOORS);
    assertTrue(DriveHud.stationBusy(link));
    stop.setPhase(DriverStationStop.Phase.WAIT_DEPARTURE);
    assertFalse(DriveHud.stationBusy(link), "等待发车时换端提示在前");
    assertFalse(DriverStationStop.Phase.DEPART.needsDriver());
    assertFalse(DriverStationStop.Phase.ENDED.needsDriver());
  }
}
