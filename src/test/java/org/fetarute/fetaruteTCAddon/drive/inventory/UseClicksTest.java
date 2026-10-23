package org.fetarute.fetaruteTCAddon.drive.inventory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 右键数据包：同一拍里连发的几个包算一次；按住时约 200 毫秒一个的重发不算新按下。 */
class UseClicksTest {

  @Test
  void packetsOfOneClickArriveTogether() {
    assertFalse(UseClicks.sameClick(null, 1_000));
    assertTrue(UseClicks.sameClick(1_000L, 1_000), "对着实体与使用两个包同时到");
    assertTrue(UseClicks.sameClick(1_000L, 1_030));
    assertFalse(UseClicks.sameClick(1_000L, 1_200), "按住时的重发");
  }

  @Test
  void holdingIsNotAFreshPress() {
    assertTrue(UseClicks.fresh(null, 1_000, 300));
    assertFalse(UseClicks.fresh(1_000L, 1_200, 300), "按住");
    assertTrue(UseClicks.fresh(1_000L, 1_400, 300), "松开再按");
  }
}
