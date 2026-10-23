package org.fetarute.fetaruteTCAddon.coupler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/** 车厢两端的几何与推车找车的碰到判定。 */
class CarEndsTest {

  @Test
  void theEndFacingAnotherCarFollowsTheCarsOwnFacing() {
    Vector east = new Vector(1, 0, 0);
    Vector self = new Vector(0, 64, 0);

    assertEquals(CouplerState.End.FRONT, CarEnds.facing(east, self, new Vector(5, 64, 1)));
    assertEquals(CouplerState.End.REAR, CarEnds.facing(east, self, new Vector(-5, 64, 1)));
    assertEquals(
        CouplerState.End.FRONT,
        CarEnds.facing(east.clone().multiply(-1), self, new Vector(-5, 64, 0)),
        "车厢朝西时西边是前端");
  }

  @Test
  void outwardPointsAwayFromTheCarAtThatEnd() {
    Vector forward = new Vector(0, 0, 2);

    assertEquals(1.0, CarEnds.outward(forward, CouplerState.End.FRONT).getZ(), 1e-9);
    assertEquals(-1.0, CarEnds.outward(forward, CouplerState.End.REAR).getZ(), 1e-9);
  }

  @Test
  void aWalkPointTouchesACarNearItsCentre() {
    Vector center = new Vector(10.5, 64, 3.5);

    assertTrue(PushTargetFinder.touches(new Vector(10.9, 64, 3.5), center));
    assertTrue(PushTargetFinder.touches(new Vector(10.5, 65.2, 3.5), center), "坡道上高差在范围内");
    assertFalse(PushTargetFinder.touches(new Vector(11.3, 64, 3.5), center));
    assertFalse(PushTargetFinder.touches(new Vector(10.5, 66, 3.5), center), "上下两层轨道不算");
  }
}
