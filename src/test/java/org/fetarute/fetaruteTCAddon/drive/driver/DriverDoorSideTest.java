package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DriverDoorSide 站台侧")
class DriverDoorSideTest {

  private static final Vector NORTH = new Vector(0, 0, -1);
  private static final Vector EAST = new Vector(1, 0, 0);

  @Test
  @DisplayName("面朝北时西侧站台在左手边，东侧在右手边")
  void leftAndRight() {
    assertEquals(DriverDoorSide.LEFT, DriverDoorSide.of(NORTH, BlockFace.WEST));
    assertEquals(DriverDoorSide.RIGHT, DriverDoorSide.of(NORTH, BlockFace.EAST));
    assertEquals(DriverDoorSide.LEFT, DriverDoorSide.of(EAST, BlockFace.NORTH));
    assertEquals(DriverDoorSide.RIGHT, DriverDoorSide.of(EAST, BlockFace.SOUTH_WEST));
    assertEquals(DriverDoorSide.ANY, DriverDoorSide.of(NORTH, BlockFace.NORTH), "站台在正前方判定不出");
    assertEquals(DriverDoorSide.ANY, DriverDoorSide.of(null, BlockFace.WEST));
  }

  @Test
  @DisplayName("按站台设置：不开门、两侧、单侧")
  void requiredFromStop() {
    assertEquals(DriverDoorSide.NONE, DriverDoorSide.required(stop(null, false, false), NORTH));
    assertEquals(DriverDoorSide.BOTH, DriverDoorSide.required(stop(null, true, true), NORTH));
    assertEquals(
        DriverDoorSide.LEFT, DriverDoorSide.required(stop(BlockFace.WEST, false, true), NORTH));
  }

  @Test
  @DisplayName("开对门与开错门")
  void satisfiedAndWrong() {
    assertTrue(DriverDoorSide.LEFT.satisfied(true, false));
    assertFalse(DriverDoorSide.LEFT.satisfied(false, true));
    assertTrue(DriverDoorSide.LEFT.wrong(false, true));
    assertFalse(DriverDoorSide.BOTH.satisfied(true, false));
    assertTrue(DriverDoorSide.ANY.satisfied(false, true));
    assertTrue(DriverDoorSide.NONE.wrong(true, false));
  }

  private static DriverStationStop stop(BlockFace face, boolean both, boolean doors) {
    return new DriverStationStop(
        NodeId.of("OP:S:STA:1"), "测试站", UUID.randomUUID(), new Vector(), face, both, doors);
  }
}
