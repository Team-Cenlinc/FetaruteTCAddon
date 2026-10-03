package org.fetarute.fetaruteTCAddon.dispatcher.sign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("停车位置标牌子的写法")
class StopMarkSignTest {

  @Test
  @DisplayName("第二行认 stopmark，不分大小写")
  void type() {
    assertTrue(StopMarkSign.isStopMark("stopmark"));
    assertTrue(StopMarkSign.isStopMark(" StopMark "));
    assertFalse(StopMarkSign.isStopMark("autostation"));
    assertFalse(StopMarkSign.isStopMark(null));
  }

  @Test
  @DisplayName("carriage: 单个、多个、范围、任意")
  void carriages() {
    StopMarkSign single = StopMarkSign.parse("carriage:4", "").orElseThrow();
    assertTrue(single.matches(4));
    assertFalse(single.matches(6));
    assertEquals(1, single.breadth());
    assertEquals("4", single.describe());

    StopMarkSign list = StopMarkSign.parse("carriage: 4, 6", null).orElseThrow();
    assertTrue(list.matches(6));
    assertFalse(list.matches(5));
    assertEquals("4,6", list.describe());

    StopMarkSign range = StopMarkSign.parse("Carriages:3-5", "").orElseThrow();
    assertTrue(range.matches(3));
    assertTrue(range.matches(5));
    assertFalse(range.matches(6));
    assertEquals(3, range.breadth());

    StopMarkSign any = StopMarkSign.parse("carriage:*", "").orElseThrow();
    assertTrue(any.matches(1));
    assertTrue(any.matches(12));
    assertEquals(Integer.MAX_VALUE, any.breadth());
    assertEquals("*", any.describe());

    assertTrue(StopMarkSign.parse("carriage：8", "").isPresent(), "全角冒号也认");
    assertTrue(StopMarkSign.parse("", "carriage:2").isPresent(), "也可以写在第四行");
  }

  @Test
  @DisplayName("写错时不认")
  void invalid() {
    assertTrue(StopMarkSign.parse("carriage:", "").isEmpty());
    assertTrue(StopMarkSign.parse("carriage:0", "").isEmpty());
    assertTrue(StopMarkSign.parse("carriage:5-3", "").isEmpty());
    assertTrue(StopMarkSign.parse("carriage:a", "").isEmpty());
    assertTrue(StopMarkSign.parse("cars:4", "").isEmpty());
    assertTrue(StopMarkSign.parse("4", "").isEmpty());
  }
}
