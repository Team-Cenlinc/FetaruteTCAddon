package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  /** 空行与无法识别的值算漏写；显式 NONE 与任何可识别的方向都不算。parse 的结果不变，三者都是 NONE。 */
  @Test
  void missingDistinguishesBlankAndUnknownFromExplicitNone() {
    assertTrue(AutoStationDoorDirection.isMissing(null));
    assertTrue(AutoStationDoorDirection.isMissing(""));
    assertTrue(AutoStationDoorDirection.isMissing("   "));
    assertTrue(AutoStationDoorDirection.isMissing("left"));
    assertTrue(AutoStationDoorDirection.isMissing("SURC:S:ZKW:1"));

    assertFalse(AutoStationDoorDirection.isMissing("NONE"));
    assertFalse(AutoStationDoorDirection.isMissing(" none "));
    assertFalse(AutoStationDoorDirection.isMissing("BOTH"));
    assertFalse(AutoStationDoorDirection.isMissing("ne"));
    assertFalse(AutoStationDoorDirection.isMissing("W"));

    assertTrue(AutoStationDoorDirection.isExplicitNone("None"));
    assertFalse(AutoStationDoorDirection.isExplicitNone(""));
    assertFalse(AutoStationDoorDirection.isExplicitNone(null));
    assertEquals(AutoStationDoorDirection.NONE, AutoStationDoorDirection.parse("NONE"));
    assertEquals(AutoStationDoorDirection.NONE, AutoStationDoorDirection.parse("left"));
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
