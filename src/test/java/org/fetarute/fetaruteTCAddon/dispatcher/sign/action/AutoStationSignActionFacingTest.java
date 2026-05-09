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

  @Test
  void exitOffsetSideSupportsDiagonalFacing() {
    assertEquals(
        3.0, AutoStationSignAction.computeRelativeSide(BlockFace.NORTH_EAST, BlockFace.NORTH, 3.0));
    assertEquals(
        -3.0, AutoStationSignAction.computeRelativeSide(BlockFace.NORTH_EAST, BlockFace.EAST, 3.0));
    assertEquals(
        -3.0,
        AutoStationSignAction.computeRelativeSide(BlockFace.NORTH_WEST, BlockFace.NORTH, 3.0));
    assertEquals(
        3.0, AutoStationSignAction.computeRelativeSide(BlockFace.NORTH_WEST, BlockFace.WEST, 3.0));
  }

  @Test
  void exitOffsetSideSupportsExplicitDiagonalDoorFace() {
    assertEquals(
        3.0,
        AutoStationSignAction.computeRelativeSide(BlockFace.NORTH_EAST, BlockFace.NORTH_WEST, 3.0));
    assertEquals(
        -3.0,
        AutoStationSignAction.computeRelativeSide(BlockFace.NORTH_EAST, BlockFace.SOUTH_EAST, 3.0));
  }

  @Test
  void exitOffsetSideSupportsContinuousFacingVector() {
    Vector thirtyDegreesEastOfNorth = new Vector(0.5, 0.0, -0.8660254038);

    assertEquals(
        3.0,
        AutoStationSignAction.computeRelativeSide(
            BlockFace.NORTH_EAST, thirtyDegreesEastOfNorth, BlockFace.NORTH, 3.0));
    assertEquals(
        -3.0,
        AutoStationSignAction.computeRelativeSide(
            BlockFace.NORTH_EAST, thirtyDegreesEastOfNorth, BlockFace.EAST, 3.0));
  }

  @Test
  void exitOffsetSideKeepsCardinalFacingSign() {
    assertEquals(
        -3.0, AutoStationSignAction.computeRelativeSide(BlockFace.NORTH, BlockFace.EAST, 3.0));
    assertEquals(
        3.0, AutoStationSignAction.computeRelativeSide(BlockFace.NORTH, BlockFace.WEST, 3.0));
  }
}
