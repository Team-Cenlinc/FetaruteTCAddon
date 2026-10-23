package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

class AutoStationSignActionFacingTest {

  @Test
  void horizontalFaceKeepsFortyFiveDegreeOrientation() {
    assertEquals(
        BlockFace.NORTH_EAST, AutoStationSignAction.toHorizontalFace(new Vector(1.0, 0.0, -1.0)));
    assertEquals(
        BlockFace.NORTH_WEST, AutoStationSignAction.toHorizontalFace(new Vector(-1.0, 0.0, -1.0)));
    assertEquals(
        BlockFace.SOUTH_EAST, AutoStationSignAction.toHorizontalFace(new Vector(1.0, 0.0, 1.0)));
    assertEquals(
        BlockFace.SOUTH_WEST, AutoStationSignAction.toHorizontalFace(new Vector(-1.0, 0.0, 1.0)));
  }

  @Test
  void horizontalFaceKeepsStraightOrientationCardinal() {
    assertEquals(
        BlockFace.NORTH, AutoStationSignAction.toHorizontalFace(new Vector(0.0, 0.0, -1.0)));
    assertEquals(BlockFace.EAST, AutoStationSignAction.toHorizontalFace(new Vector(1.0, 0.0, 0.0)));
    assertEquals(
        BlockFace.SOUTH, AutoStationSignAction.toHorizontalFace(new Vector(0.0, 0.0, 1.0)));
    assertEquals(
        BlockFace.WEST, AutoStationSignAction.toHorizontalFace(new Vector(-1.0, 0.0, 0.0)));
  }
}
