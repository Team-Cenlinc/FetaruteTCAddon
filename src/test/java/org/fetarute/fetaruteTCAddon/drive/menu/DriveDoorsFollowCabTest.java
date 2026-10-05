package org.fetarute.fetaruteTCAddon.drive.menu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("换端后车门左右跟着对调的判定")
class DriveDoorsFollowCabTest {

  @Test
  @DisplayName("面朝方向反过来（夹角超过 90°）才对调；取不到方向时不动")
  void reversedFacing() {
    Vector east = new Vector(1, 0, 0);
    assertTrue(DriveDoors.reversed(east, new Vector(-1, 0, 0)));
    assertTrue(DriveDoors.reversed(east, new Vector(-0.7, 0, 0.7)));
    assertFalse(DriveDoors.reversed(east, new Vector(1, 0, 0)));
    assertFalse(DriveDoors.reversed(east, new Vector(0.2, 0, 0.98)), "弯道上的同一端");
    assertFalse(DriveDoors.reversed(east, new Vector(0, 5, 0)), "只看水平方向");
    assertFalse(DriveDoors.reversed(null, east));
    assertFalse(DriveDoors.reversed(east, null));
  }
}
