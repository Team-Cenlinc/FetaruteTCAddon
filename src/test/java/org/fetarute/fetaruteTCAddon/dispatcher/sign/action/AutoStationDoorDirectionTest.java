package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

class AutoStationDoorDirectionTest {

  @Test
  void parseDirectionTokens() {
    assertEquals(AutoStationDoorDirection.NONE, AutoStationDoorDirection.parse(null));
    assertEquals(AutoStationDoorDirection.NONE, AutoStationDoorDirection.parse(""));
    assertEquals(AutoStationDoorDirection.BOTH, AutoStationDoorDirection.parse("both"));
    assertEquals(AutoStationDoorDirection.NORTH, AutoStationDoorDirection.parse("n"));
    assertEquals(AutoStationDoorDirection.NORTH_EAST, AutoStationDoorDirection.parse("ne"));
    assertEquals(AutoStationDoorDirection.SOUTH_EAST, AutoStationDoorDirection.parse("south-east"));
    assertEquals(AutoStationDoorDirection.SOUTH_WEST, AutoStationDoorDirection.parse("south_west"));
    assertEquals(AutoStationDoorDirection.NORTH_WEST, AutoStationDoorDirection.parse("northwest"));
    assertEquals(AutoStationDoorDirection.WEST, AutoStationDoorDirection.parse("west"));
    assertEquals(AutoStationDoorDirection.NONE, AutoStationDoorDirection.parse("unknown"));
  }

  @Test
  void leftRightMappingMatchesFacing() {
    assertEquals(BlockFace.WEST, AutoStationDoorController.leftOf(BlockFace.NORTH));
    assertEquals(BlockFace.EAST, AutoStationDoorController.rightOf(BlockFace.NORTH));

    assertEquals(BlockFace.NORTH, AutoStationDoorController.leftOf(BlockFace.EAST));
    assertEquals(BlockFace.SOUTH, AutoStationDoorController.rightOf(BlockFace.EAST));

    assertEquals(BlockFace.EAST, AutoStationDoorController.leftOf(BlockFace.SOUTH));
    assertEquals(BlockFace.WEST, AutoStationDoorController.rightOf(BlockFace.SOUTH));

    assertEquals(BlockFace.SOUTH, AutoStationDoorController.leftOf(BlockFace.WEST));
    assertEquals(BlockFace.NORTH, AutoStationDoorController.rightOf(BlockFace.WEST));
  }
}
