package org.fetarute.fetaruteTCAddon.coupler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/** 扳手点中车厢哪儿：视线与车厢轴线的最近点落在哪一段。 */
class WrenchAimTest {

  /** 车厢中心在原点、朝 +X、长 6 格。 */
  private static final Vector CENTER = new Vector(0, 0, 0);

  private static final Vector EAST = new Vector(1, 0, 0);

  private static final double HALF = 3.0;

  private static WrenchAim.Spot lookAt(Vector eye, Vector target) {
    return WrenchAim.spot(eye, target.clone().subtract(eye), CENTER, EAST, HALF);
  }

  @Test
  void standingBesideTheCarTheSpotFollowsWhereThePlayerLooks() {
    Vector eye = new Vector(0, 1.6, 3);

    assertEquals(WrenchAim.Spot.FRONT, lookAt(eye, new Vector(2.5, 0, 0)));
    assertEquals(WrenchAim.Spot.REAR, lookAt(eye, new Vector(-2.5, 0, 0)));
    assertEquals(WrenchAim.Spot.MIDDLE, lookAt(eye, new Vector(0.3, 0, 0)));
  }

  @Test
  void theEndZoneIsAtMostOneAndAHalfBlocks() {
    Vector eye = new Vector(0, 1.6, 3);
    Vector east = new Vector(1, 0, 0);
    Vector longCarFront = new Vector(4.6, 0, 0);
    Vector longCarMiddle = new Vector(4.4, 0, 0);

    assertEquals(
        WrenchAim.Spot.FRONT,
        WrenchAim.spot(eye, longCarFront.subtract(eye), CENTER, east, 6.0),
        "12 格长的车厢离端部 1.5 格以内");
    assertEquals(
        WrenchAim.Spot.MIDDLE, WrenchAim.spot(eye, longCarMiddle.subtract(eye), CENTER, east, 6.0));
    assertEquals(WrenchAim.Spot.FRONT, lookAt(eye, new Vector(1.7, 0, 0)), "6 格长的车厢按半车长的 45% 收窄");
    assertEquals(WrenchAim.Spot.MIDDLE, lookAt(eye, new Vector(1.6, 0, 0)));
  }

  @Test
  void aShortCarStillHasAMiddle() {
    Vector eye = new Vector(0, 1.6, 2);
    Vector look = new Vector(0, -1.6, -2);

    assertEquals(WrenchAim.Spot.MIDDLE, WrenchAim.spot(eye, look, CENTER, EAST, 0.5));
    assertEquals(
        WrenchAim.Spot.FRONT,
        WrenchAim.spot(eye, new Vector(0.45, 0, 0).subtract(eye), CENTER, EAST, 0.5));
  }

  @Test
  void lookingAlongTheCarPicksTheEndNearestTheEye() {
    assertEquals(WrenchAim.Spot.FRONT, lookAt(new Vector(6, 0, 0), new Vector(0, 0, 0)));
    assertEquals(WrenchAim.Spot.REAR, lookAt(new Vector(-6, 0, 0), new Vector(0, 0, 0)));
  }

  @Test
  void lookingAwayUsesWhereTheEyeIsAlongTheCar() {
    Vector eye = new Vector(2.8, 1.6, 2);
    Vector away = new Vector(0, 0.5, 1);

    assertEquals(WrenchAim.Spot.FRONT, WrenchAim.spot(eye, away, CENTER, EAST, HALF));
  }

  @Test
  void theCarFacingDecidesWhichEndIsFront() {
    Vector eye = new Vector(0, 1.6, 3);
    Vector look = new Vector(2.5, 0, 0).subtract(eye);

    assertEquals(
        WrenchAim.Spot.REAR, WrenchAim.spot(eye, look, CENTER, new Vector(-2, 0, 0), HALF));
  }

  @Test
  void aMissingFacingCountsAsTheMiddle() {
    Vector eye = new Vector(0, 1.6, 3);

    assertEquals(
        WrenchAim.Spot.MIDDLE,
        WrenchAim.spot(eye, new Vector(0, -1, -1), CENTER, new Vector(), HALF));
  }
}
