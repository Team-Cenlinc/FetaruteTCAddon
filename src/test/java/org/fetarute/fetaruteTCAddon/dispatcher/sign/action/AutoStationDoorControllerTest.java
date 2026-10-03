package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bergerkiller.bukkit.tc.attachments.animation.AnimationOptions;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;
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

  @Test
  void relativeSideHandlesDiagonalTravel() {
    assertEquals(
        "left",
        AutoStationDoorController.chooseSideNameByTravelFace(
            BlockFace.NORTH_EAST, BlockFace.NORTH));
    assertEquals(
        "right",
        AutoStationDoorController.chooseSideNameByTravelFace(BlockFace.NORTH_EAST, BlockFace.EAST));
  }

  @Test
  void relativeSideCoversAllDiagonalNorthSouthSides() {
    assertEquals(
        "right",
        AutoStationDoorController.chooseSideNameByTravelFace(
            BlockFace.SOUTH_EAST, BlockFace.SOUTH));
    assertEquals(
        "left",
        AutoStationDoorController.chooseSideNameByTravelFace(
            BlockFace.SOUTH_EAST, BlockFace.NORTH));
    assertEquals(
        "left",
        AutoStationDoorController.chooseSideNameByTravelFace(
            BlockFace.SOUTH_WEST, BlockFace.SOUTH));
    assertEquals(
        "right",
        AutoStationDoorController.chooseSideNameByTravelFace(
            BlockFace.SOUTH_WEST, BlockFace.NORTH));
  }

  @Test
  void relativeSideAcceptsExplicitDiagonalPlatformSides() {
    assertEquals(
        "left",
        AutoStationDoorController.chooseSideNameByTravelFace(
            BlockFace.NORTH_EAST, BlockFace.NORTH_WEST));
    assertEquals(
        "right",
        AutoStationDoorController.chooseSideNameByTravelFace(
            BlockFace.NORTH_EAST, BlockFace.SOUTH_EAST));
    assertEquals(
        "left",
        AutoStationDoorController.chooseSideNameByTravelFace(
            BlockFace.SOUTH_EAST, BlockFace.NORTH_EAST));
    assertEquals(
        "right",
        AutoStationDoorController.chooseSideNameByTravelFace(
            BlockFace.SOUTH_WEST, BlockFace.NORTH_WEST));
  }

  @Test
  void worldProjectionSelectsSouthEastDoorSide() {
    Location doorLeftNorthWest = new Location(null, -2.0, 0.0, -2.0);
    Location doorRightSouthEast = new Location(null, 2.0, 0.0, 2.0);

    assertEquals(
        "right",
        AutoStationDoorController.chooseSideNameByAttachmentPositions(
            BlockFace.SOUTH_EAST, doorLeftNorthWest, doorRightSouthEast));
  }

  @Test
  void worldProjectionSelectsNorthWestMirrorOppositeDoorSide() {
    Location doorLeftSouthEast = new Location(null, 2.0, 0.0, 2.0);
    Location doorRightNorthWest = new Location(null, -2.0, 0.0, -2.0);

    assertEquals(
        "left",
        AutoStationDoorController.chooseSideNameByAttachmentPositions(
            BlockFace.SOUTH_EAST, doorLeftSouthEast, doorRightNorthWest));
    assertEquals(
        "right",
        AutoStationDoorController.chooseSideNameByAttachmentPositions(
            BlockFace.NORTH_WEST, doorLeftSouthEast, doorRightNorthWest));
  }

  @Test
  void worldProjectionCoversNorthEastAndSouthWestDoorSides() {
    Location doorLeftNorthEast = new Location(null, 2.0, 0.0, -2.0);
    Location doorRightSouthWest = new Location(null, -2.0, 0.0, 2.0);

    assertEquals(
        "left",
        AutoStationDoorController.chooseSideNameByAttachmentPositions(
            BlockFace.NORTH_EAST, doorLeftNorthEast, doorRightSouthWest));
    assertEquals(
        "right",
        AutoStationDoorController.chooseSideNameByAttachmentPositions(
            BlockFace.SOUTH_WEST, doorLeftNorthEast, doorRightSouthWest));
  }

  @Test
  void worldProjectionDoesNotUseTrainFacingVector() {
    Vector northEastTrainFacing = new Vector(1.0, 0.0, -1.0);
    Location doorLeftSouthEast = new Location(null, 2.0, 0.0, 2.0);
    Location doorRightNorthWest = new Location(null, -2.0, 0.0, -2.0);

    assertEquals(
        "left",
        AutoStationDoorController.chooseSideNameByAttachmentPositions(
            BlockFace.SOUTH_EAST, doorLeftSouthEast, doorRightNorthWest));
    assertEquals(
        "right",
        AutoStationDoorController.chooseSideNameByTravelVector(
            northEastTrainFacing, BlockFace.SOUTH_EAST));
  }

  @Test
  void relativeSidePreservesNonFortyFiveDegreeAngles() {
    Vector thirtyDegreesEastOfNorth = new Vector(0.5, 0.0, -0.8660254038);

    assertEquals(
        "left",
        AutoStationDoorController.chooseSideNameByTravelVector(
            thirtyDegreesEastOfNorth, BlockFace.NORTH));
    assertEquals(
        "right",
        AutoStationDoorController.chooseSideNameByTravelVector(
            thirtyDegreesEastOfNorth, BlockFace.EAST));
    assertEquals(
        "right",
        AutoStationDoorController.chooseSideNameByTravelVector(
            thirtyDegreesEastOfNorth, BlockFace.SOUTH));
    assertEquals(
        "left",
        AutoStationDoorController.chooseSideNameByTravelVector(
            thirtyDegreesEastOfNorth, BlockFace.WEST));
  }

  /**
   * 同时置 reset 与 queue；TrainCarts 先判 reset，queue 实际不生效，见 {@code TrainCartsAnimationSemanticsTest}。
   */
  @Test
  void doorAnimationOptionsRestartTheAnimationAndCarryTheQueueFlag() {
    AnimationOptions options = AutoStationDoorController.doorAnimationOptions("doorL", 1.0);

    assertTrue(options.getQueue());
    assertTrue(options.getReset());
    assertEquals(1.0, options.getSpeed());
  }

  @Test
  void driverLateralFaceFollowsTheFacingDirection() {
    // 面朝东（+X）时，左手边是北，右手边是南。
    assertEquals(
        BlockFace.NORTH, AutoStationDoorController.lateralCompassFace(new Vector(1, 0, 0), true));
    assertEquals(
        BlockFace.SOUTH, AutoStationDoorController.lateralCompassFace(new Vector(1, 0, 0), false));
    // 面朝北（-Z）时，左手边是西。
    assertEquals(
        BlockFace.WEST, AutoStationDoorController.lateralCompassFace(new Vector(0, 0, -1), true));
    assertEquals(
        BlockFace.EAST, AutoStationDoorController.lateralCompassFace(new Vector(0, 0, -1), false));
    // 面朝南、西同理。
    assertEquals(
        BlockFace.EAST, AutoStationDoorController.lateralCompassFace(new Vector(0, 0, 1), true));
    assertEquals(
        BlockFace.SOUTH, AutoStationDoorController.lateralCompassFace(new Vector(-1, 0, 0), true));
  }

  @Test
  void driverLateralFaceSnapsDiagonalAndCurvedHeadingsToTheNearestCompassPoint() {
    // 面朝东北，左手边指向西北。
    assertEquals(
        BlockFace.NORTH_WEST,
        AutoStationDoorController.lateralCompassFace(new Vector(1, 0, -1), true));
    // 稍偏离正东（弯道上）仍取最近的方位。
    assertEquals(
        BlockFace.NORTH,
        AutoStationDoorController.lateralCompassFace(new Vector(1, 0.3, 0.2), true));
  }

  @Test
  void driverLateralFaceIgnoresVerticalAndRejectsDegenerateFacing() {
    assertEquals(
        BlockFace.NORTH, AutoStationDoorController.lateralCompassFace(new Vector(1, 5, 0), true));
    assertNull(AutoStationDoorController.lateralCompassFace(new Vector(0, 1, 0), true));
    assertNull(AutoStationDoorController.lateralCompassFace(null, true));
    assertNull(AutoStationDoorController.lateralCompassFace(new Vector(Double.NaN, 0, 1), true));
  }
}
