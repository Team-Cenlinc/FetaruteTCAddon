package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.drive.driver.score.StopScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("停站中途由驾驶员接管：站台开着的车门交给驾驶员")
class DriverStationStopHandOverTest {

  private static DriverStationStop stop() {
    return new DriverStationStop(
        NodeId.of("OP:S:STA:1"),
        "测试站",
        UUID.randomUUID(),
        new Vector(),
        StopAlignment.Reference.CENTER,
        BlockFace.EAST,
        false,
        true);
  }

  @Test
  @DisplayName("交接标记只取走一次；没交接时取不到")
  void handOverIsTakenOnce() {
    DriverStationStop stop = stop();
    assertFalse(stop.takeHandedOverDoors());
    stop.handOverOpenDoors();
    assertTrue(stop.takeHandedOverDoors());
    assertFalse(stop.takeHandedOverDoors());
  }

  @Test
  @DisplayName("交接来的停站从停站计时开始、不经停妥：不计对标成绩")
  void handedOverStopIsNotScored() {
    DriverStationStop stop = stop();
    stop.setPhase(DriverStationStop.Phase.DWELL);
    stop.handOverOpenDoors();
    stop.end();
    assertEquals(DriverStationStop.Phase.ENDED, stop.phase());
    assertNull(StopScore.of(stop));
  }
}
