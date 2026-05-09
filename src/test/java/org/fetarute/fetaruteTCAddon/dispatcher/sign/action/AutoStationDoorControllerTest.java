package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bergerkiller.bukkit.tc.attachments.animation.AnimationOptions;
import java.util.List;
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
  void vectorFallbackHandlesDiagonalTravel() {
    assertEquals(
        "left",
        AutoStationDoorController.chooseSideNameByTravelFace(
            BlockFace.NORTH_EAST, BlockFace.NORTH));
    assertEquals(
        "right",
        AutoStationDoorController.chooseSideNameByTravelFace(BlockFace.NORTH_EAST, BlockFace.EAST));
  }

  @Test
  void vectorFallbackCoversAllDiagonalNorthSouthSides() {
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
  void vectorFallbackAcceptsExplicitDiagonalPlatformSides() {
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
  void attachmentPositionProjectionAcceptsExplicitDiagonalPlatformSides() {
    Location doorLeftNorthWest = new Location(null, -2.0, 0.0, -2.0);
    Location doorRightSouthEast = new Location(null, 2.0, 0.0, 2.0);

    assertEquals(
        "left",
        AutoStationDoorController.chooseSideNameByAttachmentPositions(
            BlockFace.NORTH_WEST, doorLeftNorthWest, doorRightSouthEast));
    assertEquals(
        "right",
        AutoStationDoorController.chooseSideNameByAttachmentPositions(
            BlockFace.SOUTH_EAST, doorLeftNorthWest, doorRightSouthEast));
  }

  @Test
  void lateralProjectionDoesNotGuessDoorSideWithoutAttachmentPositions() {
    Vector northEast = new Vector(1.0, 0.0, -1.0);

    assertEquals(
        "none",
        AutoStationDoorController.chooseSideNameByLateralProjections(
            northEast, BlockFace.SOUTH_EAST, null, null));
    assertEquals(
        "right",
        AutoStationDoorController.chooseSideNameByTravelVector(northEast, BlockFace.SOUTH_EAST));
  }

  @Test
  void lateralProjectionSeparatesSouthEastAndNorthWestDoors() {
    Vector northEast = new Vector(1.0, 0.0, -1.0);
    double doorLeftNorthWest = 2.0;
    double doorRightSouthEast = -2.0;

    assertEquals(
        "left",
        AutoStationDoorController.chooseSideNameByLateralProjections(
            northEast, BlockFace.NORTH_WEST, doorLeftNorthWest, doorRightSouthEast));
    assertEquals(
        "right",
        AutoStationDoorController.chooseSideNameByLateralProjections(
            northEast, BlockFace.SOUTH_EAST, doorLeftNorthWest, doorRightSouthEast));
  }

  @Test
  void pairedLateralProjectionKeepsSouthEastOppositeOfNorthWest() {
    Vector northEast = new Vector(1.0, 0.0, -1.0);
    Location center = new Location(null, 0.0, 0.0, 0.0);
    List<Location> leftDoors = List.of(pointOnTrainAxis(northEast, 0.0, 2.0));
    List<Location> rightDoors = List.of(pointOnTrainAxis(northEast, 0.0, -2.0));

    assertEquals(
        "left",
        AutoStationDoorController.chooseSideNameByLateralDoorLocations(
            northEast, BlockFace.NORTH_WEST, center, leftDoors, rightDoors));
    assertEquals(
        "right",
        AutoStationDoorController.chooseSideNameByLateralDoorLocations(
            northEast, BlockFace.SOUTH_EAST, center, leftDoors, rightDoors));
  }

  @Test
  void pairedLateralProjectionIgnoresUnpairedLongitudinalOutlier() {
    Vector northEast = new Vector(1.0, 0.0, -1.0);
    Location center = new Location(null, 0.0, 0.0, 0.0);
    List<Location> leftDoors =
        List.of(pointOnTrainAxis(northEast, 0.0, 2.0), pointOnTrainAxis(northEast, 100.0, -8.0));
    List<Location> rightDoors = List.of(pointOnTrainAxis(northEast, 0.0, -2.0));

    assertEquals(
        "left",
        AutoStationDoorController.chooseSideNameByLateralDoorLocations(
            northEast, BlockFace.NORTH_WEST, center, leftDoors, rightDoors));
    assertEquals(
        "right",
        AutoStationDoorController.chooseSideNameByLateralDoorLocations(
            northEast, BlockFace.SOUTH_EAST, center, leftDoors, rightDoors));
  }

  @Test
  void pairedLateralProjectionKeepsNorthEastOppositeOfSouthWest() {
    Vector southEast = new Vector(1.0, 0.0, 1.0);
    Location center = new Location(null, 0.0, 0.0, 0.0);
    List<Location> leftDoors = List.of(pointOnTrainAxis(southEast, 0.0, 2.0));
    List<Location> rightDoors = List.of(pointOnTrainAxis(southEast, 0.0, -2.0));

    assertEquals(
        "left",
        AutoStationDoorController.chooseSideNameByLateralDoorLocations(
            southEast, BlockFace.NORTH_EAST, center, leftDoors, rightDoors));
    assertEquals(
        "right",
        AutoStationDoorController.chooseSideNameByLateralDoorLocations(
            southEast, BlockFace.SOUTH_WEST, center, leftDoors, rightDoors));
  }

  @Test
  void pairedLateralProjectionIgnoresNorthEastSouthWestOutlier() {
    Vector southEast = new Vector(1.0, 0.0, 1.0);
    Location center = new Location(null, 0.0, 0.0, 0.0);
    List<Location> leftDoors =
        List.of(pointOnTrainAxis(southEast, 0.0, 2.0), pointOnTrainAxis(southEast, 100.0, -8.0));
    List<Location> rightDoors = List.of(pointOnTrainAxis(southEast, 0.0, -2.0));

    assertEquals(
        "left",
        AutoStationDoorController.chooseSideNameByLateralDoorLocations(
            southEast, BlockFace.NORTH_EAST, center, leftDoors, rightDoors));
    assertEquals(
        "right",
        AutoStationDoorController.chooseSideNameByLateralDoorLocations(
            southEast, BlockFace.SOUTH_WEST, center, leftDoors, rightDoors));
  }

  @Test
  void diagonalFallbackUsesUnifiedVectorSideForAllCardinalDoors() {
    assertEquals(
        "left",
        AutoStationDoorController.chooseDiagonalSideNameByTravelFace(
            BlockFace.NORTH_EAST, BlockFace.NORTH));
    assertEquals(
        "right",
        AutoStationDoorController.chooseDiagonalSideNameByTravelFace(
            BlockFace.NORTH_EAST, BlockFace.EAST));
    assertEquals(
        "right",
        AutoStationDoorController.chooseDiagonalSideNameByTravelFace(
            BlockFace.SOUTH_EAST, BlockFace.SOUTH));
    assertEquals(
        "left",
        AutoStationDoorController.chooseDiagonalSideNameByTravelFace(
            BlockFace.SOUTH_WEST, BlockFace.SOUTH));
    assertEquals(
        "right",
        AutoStationDoorController.chooseDiagonalSideNameByTravelFace(
            BlockFace.SOUTH_WEST, BlockFace.NORTH));
  }

  @Test
  void vectorFallbackPreservesNonFortyFiveDegreeAngles() {
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

  @Test
  void doorAnimationOptionsEnterTrainCartsQueue() {
    AnimationOptions options = AutoStationDoorController.doorAnimationOptions("doorL", 1.0);

    assertTrue(options.getQueue());
    assertTrue(options.getReset());
    assertEquals(1.0, options.getSpeed());
  }

  private static Location pointOnTrainAxis(
      Vector travelVector, double longitudinal, double lateral) {
    Vector forward = AutoStationDoorController.normalizeHorizontalVector(travelVector);
    Vector leftAxis = new Vector(forward.getZ(), 0.0, -forward.getX()).normalize();
    double x = forward.getX() * longitudinal + leftAxis.getX() * lateral;
    double z = forward.getZ() * longitudinal + leftAxis.getZ() * lateral;
    return new Location(null, x, 0.0, z);
  }
}
