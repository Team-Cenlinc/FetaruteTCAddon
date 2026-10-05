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
    assertTrue(StopMarkSign.parse("note", "carriage:2").isPresent(), "别的文字不管");
    assertEquals(
        "4", StopMarkSign.parse("carriage:a", "carriage:4").orElseThrow().describe(), "写错的一行不管");
    assertEquals(
        "4", StopMarkSign.parse("carriage:4", "cars:6").orElseThrow().describe(), "两行都写时取第一行");
    assertEquals("3-5", StopMarkSign.parse("car:3－5", "").orElseThrow().describe(), "全角减号也认");
  }

  @Test
  @DisplayName("car: 是 carriage: 的简写")
  void carAlias() {
    StopMarkSign car = StopMarkSign.parse("car:4,6", "").orElseThrow();
    assertEquals("4,6", car.describe());
    assertTrue(StopMarkSign.parse("Cars: 3-5", "").orElseThrow().matches(4));
    assertTrue(StopMarkSign.parse("CAR:*", "").orElseThrow().matches(12));
  }

  @Test
  @DisplayName("door: 选哪几节车厢开门，写法与 car: 相同；不写或写 * 时全车开门")
  void doors() {
    StopMarkSign none = StopMarkSign.parse("car:4", "").orElseThrow();
    assertTrue(none.allDoors());
    assertTrue(none.opensDoorsAt(1));
    assertTrue(none.opensDoorsAt(4));
    assertEquals("*", none.describeDoors());
    assertTrue(StopMarkSign.parse("car:4", "   ").orElseThrow().allDoors(), "第四行空着");
    assertTrue(StopMarkSign.parse("car:4", "door:*").orElseThrow().allDoors());

    StopMarkSign first = StopMarkSign.parse("car:4", "door:1").orElseThrow();
    assertFalse(first.allDoors());
    assertTrue(first.opensDoorsAt(1));
    assertFalse(first.opensDoorsAt(2));
    assertEquals("1", first.describeDoors());
    assertTrue(first.matches(4), "door: 不影响适用节数");
    assertEquals(1, first.breadth());

    StopMarkSign several = StopMarkSign.parse("car:*", "doors: 1, 3-4").orElseThrow();
    assertTrue(several.opensDoorsAt(1));
    assertFalse(several.opensDoorsAt(2));
    assertTrue(several.opensDoorsAt(3));
    assertTrue(several.opensDoorsAt(4));
    assertFalse(several.opensDoorsAt(5));
    assertEquals("1,3-4", several.describeDoors());

    StopMarkSign swapped = StopMarkSign.parse("door：2", "carriage:4").orElseThrow();
    assertEquals("4", swapped.describe());
    assertEquals("2", swapped.describeDoors());

    assertEquals(
        "1,3", StopMarkSign.parse("car:4", "door:1，3").orElseThrow().describeDoors(), "全角逗号也认");
  }

  @Test
  @DisplayName("door: 写错，或适用的最短编组没有其中任何一节时整块不认")
  void invalidDoors() {
    assertTrue(StopMarkSign.parse("car:4", "door:").isEmpty());
    assertTrue(StopMarkSign.parse("car:4", "door:0").isEmpty());
    assertTrue(StopMarkSign.parse("car:4", "door:a").isEmpty());
    assertTrue(StopMarkSign.parse("car:4", "door:3-1").isEmpty());
    assertTrue(StopMarkSign.parse("door:1", "").isEmpty(), "car: 必填");
    assertTrue(StopMarkSign.parse("door:1", "door:2").isEmpty());
    assertTrue(StopMarkSign.parse("car:4", "door:5").isEmpty(), "四节车没有第五节");
    assertTrue(StopMarkSign.parse("car:4,6", "door:5-6").isEmpty(), "四节车没有第五、六节");
    assertTrue(StopMarkSign.parse("car:*", "door:2").isEmpty(), "单节车没有第二节");
    assertTrue(StopMarkSign.parse("car:*", "door:1,3").isPresent());
    assertTrue(StopMarkSign.parse("car:4-8", "door:3-6").isPresent(), "四节车有第三、四节");
  }

  @Test
  @DisplayName("写错时不认")
  void invalid() {
    assertTrue(StopMarkSign.parse("carriage:", "").isEmpty());
    assertTrue(StopMarkSign.parse("carriage:0", "").isEmpty());
    assertTrue(StopMarkSign.parse("carriage:5-3", "").isEmpty());
    assertTrue(StopMarkSign.parse("carriage:a", "").isEmpty());
    assertTrue(StopMarkSign.parse("cart:4", "").isEmpty());
    assertTrue(StopMarkSign.parse("4", "").isEmpty());
  }

  @Test
  @DisplayName("超大范围不会因溢出排到最前")
  void breadthDoesNotOverflow() {
    StopMarkSign huge = StopMarkSign.parse("carriage:1-2147483647,3", "").orElseThrow();
    StopMarkSign exact = StopMarkSign.parse("carriage:3", "").orElseThrow();
    StopMarkSign any = StopMarkSign.parse("carriage:*", "").orElseThrow();
    assertTrue(huge.breadth() > exact.breadth());
    assertTrue(huge.breadth() < any.breadth());
  }
}
