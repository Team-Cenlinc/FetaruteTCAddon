package org.fetarute.fetaruteTCAddon.drive.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OverspeedLevelTest {

  private static final double RED = 0.1;

  @Test
  void withoutALimitThereIsNothingToMark() {
    assertEquals(OverspeedLevel.NONE, OverspeedLevel.classify(20.0, Double.NaN, RED));
    assertEquals(OverspeedLevel.NONE, OverspeedLevel.classify(20.0, 0.0, RED));
    assertEquals(OverspeedLevel.NONE, OverspeedLevel.classify(20.0, Double.POSITIVE_INFINITY, RED));
  }

  @Test
  void atOrBelowTheLimitIsFine() {
    assertEquals(OverspeedLevel.OK, OverspeedLevel.classify(0.0, 10.0, RED));
    assertEquals(OverspeedLevel.OK, OverspeedLevel.classify(10.0, 10.0, RED));
    assertEquals(OverspeedLevel.OK, OverspeedLevel.classify(10.03, 10.0, RED), "不到 0.5% 的浮点余量不算超速");
  }

  @Test
  void slightlyOverTheLimitIsYellow() {
    assertEquals(OverspeedLevel.WARN, OverspeedLevel.classify(10.5, 10.0, RED));
    assertEquals(OverspeedLevel.WARN, OverspeedLevel.classify(10.99, 10.0, RED));
  }

  @Test
  void overTheRedRatioIsRed() {
    assertEquals(OverspeedLevel.OVER, OverspeedLevel.classify(11.0, 10.0, RED));
    assertEquals(OverspeedLevel.OVER, OverspeedLevel.classify(20.0, 10.0, RED));
  }

  @Test
  void languageKeysAreLowerCaseNames() {
    assertEquals("warn", OverspeedLevel.WARN.key());
    assertEquals("over", OverspeedLevel.OVER.key());
  }
}
