package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverLink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("越站处置：同一趟越站累计到上限就交还自动运行")
class SkipStationHandbackTest {

  private static void skip(DriverLink link, String station) {
    DriverStationStop stop =
        new DriverStationStop(
            NodeId.of("OP:S:" + station + ":1"),
            station,
            UUID.randomUUID(),
            new Vector(),
            null,
            false,
            true);
    link.beginStationStop(stop);
    stop.updateOffset(15.0);
    stop.markSkipped();
    link.stationStop();
  }

  @Test
  @DisplayName("按趟计：终点站结算或到终点站等开出下一趟时清零，没有任务、不结算的接管也一样")
  void countsSkipsPerTrip() {
    DriverLink link = new DriverLink(UUID.randomUUID(), "T-1", null, () -> 0.0, () -> 0L);
    skip(link, "AAA");
    assertEquals(1, link.tripSkippedStops());

    link.setTurnbackPending(true);
    link.setTurnbackPending(false);
    assertEquals(0, link.tripSkippedStops(), "到终点站等下一趟：上一趟的越站不带过去");

    skip(link, "BBB");
    skip(link, "CCC");
    assertEquals(2, link.tripSkippedStops());
    link.settleTrip();
    assertEquals(0, link.tripSkippedStops(), "终点站结算");
  }

  @Test
  @DisplayName("到上限才交还；上限为 0 不处置")
  void handsBackAtTheLimit() {
    assertFalse(DriveSessionManager.skipHandbackDue(1, 2));
    assertTrue(DriveSessionManager.skipHandbackDue(2, 2));
    assertTrue(DriveSessionManager.skipHandbackDue(3, 2));
    assertFalse(DriveSessionManager.skipHandbackDue(6, 0), "0 表示只计成绩、不交还");
  }
}
