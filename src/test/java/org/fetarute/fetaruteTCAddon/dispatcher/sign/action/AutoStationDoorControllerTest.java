package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

  /** 只排队、不 reset：reset 会清空附件上 TC 牌子排进去的动画，见 {@code TrainCartsAnimationSemanticsTest}。 */
  @Test
  void doorAnimationOptionsQueueWithoutReset() {
    AnimationOptions options = AutoStationDoorController.doorAnimationOptions("doorL", 1.0);

    assertTrue(options.getQueue());
    assertFalse(options.getReset());
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

  /** 判不出的车厢按最近的已判定车厢推：朝向相同开同一组，反着挂的开另一组。 */
  @Test
  void undecidedCarsFollowTheNearestDecidedCarByOrientation() {
    Vector east = new Vector(1, 0, 0);
    Vector west = new Vector(-1, 0, 0);
    java.util.List<Boolean> decided = java.util.Arrays.asList(true, null, null, null);
    java.util.List<Vector> forwards = java.util.List.of(east, east, west, west);

    assertEquals(
        java.util.List.of(true, true, false, false),
        AutoStationDoorController.inferCarSides(decided, forwards));
  }

  /** 已判定的车厢不改；前后一样近时取前面那节。 */
  @Test
  void inferenceKeepsDecidedCarsAndPrefersTheFrontNeighbourOnATie() {
    Vector east = new Vector(1, 0, 0);
    Vector west = new Vector(-1, 0, 0);
    java.util.List<Boolean> decided = java.util.Arrays.asList(false, null, true);
    java.util.List<Vector> forwards = java.util.List.of(east, west, east);

    assertEquals(
        java.util.List.of(false, true, true),
        AutoStationDoorController.inferCarSides(decided, forwards),
        "中间那节与前一节反向：开前一节的另一组");
  }

  /** 弯道上相邻车厢夹角不到 90° 仍算同向；读不到朝向时按同向处理。 */
  @Test
  void inferenceTreatsCurvesAndUnknownOrientationAsTheSameDirection() {
    java.util.List<Boolean> decided = java.util.Arrays.asList(true, null, null);
    java.util.List<Vector> forwards =
        java.util.Arrays.asList(new Vector(1, 0, 0), new Vector(1, 0, 0.9), null);

    assertEquals(
        java.util.List.of(true, true, true),
        AutoStationDoorController.inferCarSides(decided, forwards));
  }

  /** 一节都没判出时原样返回，交给上层按判不出处理。 */
  @Test
  void inferenceLeavesEverythingUndecidedWhenNoCarIsDecided() {
    java.util.List<Boolean> decided = java.util.Arrays.asList(null, null);

    assertEquals(
        decided,
        AutoStationDoorController.inferCarSides(
            decided, java.util.List.of(new Vector(1, 0, 0), new Vector(-1, 0, 0))));
  }

  /** 手动开关门的对侧：另一组动画、相反的世界方位；不知道方位时只换动画组。 */
  @Test
  void manualDoorOppositeFlipsTheModelSideAndTheWorldFace() {
    AutoStationDoorController.ManualDoorSide east =
        new AutoStationDoorController.ManualDoorSide(
            true, "test", java.util.Optional.of(BlockFace.EAST));

    AutoStationDoorController.ManualDoorSide opposite = east.opposite();

    assertFalse(opposite.modelLeft());
    assertEquals(java.util.Optional.of(BlockFace.WEST), opposite.worldFace());
    assertEquals(
        java.util.Optional.empty(),
        new AutoStationDoorController.ManualDoorSide(false, "legacy").opposite().worldFace());
    assertTrue(
        new AutoStationDoorController.ManualDoorSide(false, "legacy").opposite().modelLeft());
  }
}
