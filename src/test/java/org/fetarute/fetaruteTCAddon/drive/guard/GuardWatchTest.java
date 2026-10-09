package org.fetarute.fetaruteTCAddon.drive.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/** 车掌的关门监视与出站监视：看哪儿算在监视，几成合格；紧急停车扣到时限自动解除。 */
class GuardWatchTest {

  private static final Vector EAST = new Vector(1, 0, 0);
  private static final Vector WEST = new Vector(-1, 0, 0);
  private static final Vector NORTH = new Vector(0, 0, -1);

  /** 关门监视：在站台上、离车门够近、视线沿车身（朝车头或车尾都行）。 */
  @Test
  void closingWatchNeedsPlatformDistanceAndGaze() {
    assertTrue(GuardWatch.closingWatch(false, 5.0, 8.0, WEST, EAST, 45.0), "朝车尾看也算");
    assertTrue(
        GuardWatch.closingWatch(false, 5.0, 8.0, new Vector(1, -0.5, 0.3), EAST, 45.0), "只看水平方向");
    assertFalse(GuardWatch.closingWatch(true, 1.0, 8.0, EAST, EAST, 45.0), "坐在车上不算");
    assertFalse(GuardWatch.closingWatch(false, 9.0, 8.0, EAST, EAST, 45.0), "离车门太远");
    assertFalse(GuardWatch.closingWatch(false, 5.0, 8.0, NORTH, EAST, 45.0), "背对车身");
  }

  /** 出站监视：坐在车掌座位上，朝站台一侧或朝后看。 */
  @Test
  void departureWatchLooksAtThePlatformOrBehind() {
    assertTrue(GuardWatch.departureWatch(true, WEST, NORTH, WEST), "朝后看");
    assertTrue(GuardWatch.departureWatch(true, NORTH, NORTH, WEST), "朝站台看");
    assertFalse(GuardWatch.departureWatch(true, EAST, NORTH, WEST), "朝前看不算");
    assertFalse(GuardWatch.departureWatch(false, WEST, NORTH, WEST), "不在座位上不算");
    assertFalse(GuardWatch.departureWatch(true, NORTH, null, WEST), "两侧都是站台时只认朝后");
  }

  /** 合格：监视的采样至少占七成；没有采样时不判。 */
  @Test
  void aWatchPassesAtSeventyPercent() {
    GuardStopWork work = new GuardStopWork(GuardConfig.defaults());
    assertEquals(Optional.empty(), work.closingWatchPassed());
    for (int i = 0; i < 7; i++) {
      work.sampleClosing(true);
    }
    for (int i = 0; i < 3; i++) {
      work.sampleClosing(false);
    }
    assertEquals(Optional.of(true), work.closingWatchPassed());
    work.sampleClosing(false);
    assertEquals(Optional.of(false), work.closingWatchPassed(), "7/11 不到七成");
    work.sampleDeparture(false);
    assertEquals(Optional.of(false), work.departureWatchPassed());
  }

  /** 紧急停车扣着，到时限自动解除。 */
  @Test
  void anEmergencyHoldExpires() {
    long[] clock = {100L};
    GuardLink link =
        new GuardLink(UUID.randomUUID(), "T", null, GuardConfig.defaults(), () -> clock[0]);
    assertFalse(link.emergencyHold());
    link.latchEmergency(200L);
    assertTrue(link.emergencyHold());
    assertFalse(link.emergencyExpired());
    clock[0] = 200L;
    assertTrue(link.emergencyExpired());
    link.releaseEmergency();
    assertFalse(link.emergencyHold());
    assertFalse(link.emergencyExpired());
  }
}
