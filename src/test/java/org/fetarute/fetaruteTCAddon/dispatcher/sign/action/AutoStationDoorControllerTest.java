package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

class AutoStationDoorControllerTest {

  @Test
  void leftRightMappingForNorthSouth() {
    assertEquals(BlockFace.WEST, AutoStationDoorController.leftOf(BlockFace.NORTH));
    assertEquals(BlockFace.EAST, AutoStationDoorController.rightOf(BlockFace.NORTH));
    assertEquals(BlockFace.EAST, AutoStationDoorController.leftOf(BlockFace.SOUTH));
    assertEquals(BlockFace.WEST, AutoStationDoorController.rightOf(BlockFace.SOUTH));
  }

  @Test
  void leftRightMappingForEastWest() {
    assertEquals(BlockFace.NORTH, AutoStationDoorController.leftOf(BlockFace.EAST));
    assertEquals(BlockFace.SOUTH, AutoStationDoorController.rightOf(BlockFace.EAST));
    assertEquals(BlockFace.SOUTH, AutoStationDoorController.leftOf(BlockFace.WEST));
    assertEquals(BlockFace.NORTH, AutoStationDoorController.rightOf(BlockFace.WEST));
  }
}
