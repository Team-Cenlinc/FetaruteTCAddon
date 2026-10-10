package org.fetarute.fetaruteTCAddon.drive.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatBinding;
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
  void aGuardAtTheTailCountsDoorsByTheDirectionOfTravel() {
    // 车尾驾驶室面朝车后（-x），列车行进方向 +x：车掌的车门左右按行进方向。
    Vector seat = DriveDoors.facingFrom(new Vector(1, 0, 0), false);
    assertEquals(new Vector(1, 0, 0), DriveDoors.doorFacing(seat, false, true));
    assertEquals(new Vector(-1, 0, 0), seat, "不能改动传入的向量");
    assertEquals(seat, DriveDoors.doorFacing(seat, false, false), "按所坐驾驶室算时不变");
    assertEquals(seat, DriveDoors.doorFacing(seat, true, true), "坐车头时行进方向就是所坐的朝向");
    assertNull(DriveDoors.doorFacing(null, false, true));
  }

  @Test
  void doorsFollowTheSeatByDefault() {
    DriveDoors.Cab tail =
        new DriveDoors.Cab() {
          @Override
          public SeatBinding binding() {
            return new SeatBinding("T", 1, 0);
          }

          @Override
          public boolean cabAtHead(int memberCount) {
            return false;
          }

          @Override
          public boolean isLeftDoorOpen() {
            return false;
          }

          @Override
          public boolean isRightDoorOpen() {
            return false;
          }

          @Override
          public void setDoorOpen(boolean left, boolean open) {}

          @Override
          public void markDoorsClosing(long untilTick) {}
        };
    assertEquals(false, tail.doorsFromHead(2), "驾驶员默认按所坐的驾驶室");
  }

  @Test
  void noDirectionGivesNoFacing() {
    assertNull(DriveDoors.facingFrom(null, true));
  }
}
