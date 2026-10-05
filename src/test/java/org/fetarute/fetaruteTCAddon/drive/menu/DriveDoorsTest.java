package org.fetarute.fetaruteTCAddon.drive.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

class DriveDoorsTest {

  @Test
  void aCabAtTheHeadFacesTowardTheHead() {
    Vector headward = new Vector(1, 0, 0);

    assertEquals(new Vector(1, 0, 0), DriveDoors.facingFrom(headward, true));
  }

  @Test
  void aCabAtTheTailFacesAwayFromTheHead() {
    Vector headward = new Vector(1, 0, 0);

    assertEquals(new Vector(-1, 0, 0), DriveDoors.facingFrom(headward, false));
    assertEquals(new Vector(1, 0, 0), headward, "不能改动传入的向量");
  }

  @Test
  void reversingTheTrainKeepsTheDriversFacing() {
    // 调头后车厢序号翻转：指向车头的方向反过来，驾驶室也从车尾端变成车头端，驾驶员实际朝向不变。
    Vector before = DriveDoors.facingFrom(new Vector(0, 0, -1), false);
    Vector after = DriveDoors.facingFrom(new Vector(0, 0, 1), true);

    assertEquals(before, after);
  }

  @Test
  void noDirectionGivesNoFacing() {
    assertNull(DriveDoors.facingFrom(null, true));
  }
}
