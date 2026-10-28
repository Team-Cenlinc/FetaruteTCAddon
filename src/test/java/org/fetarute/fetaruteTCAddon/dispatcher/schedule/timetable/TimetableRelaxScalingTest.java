package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 非严格模式等比放宽：各组间隔有较大公约数时按公约数的整数倍放宽，比例严格不变；否则逐组向上取整。 */
class TimetableRelaxScalingTest {

  @Test
  void commonDivisorKeepsTheRatioExact() {
    var scaled = TimetableBuilder.proportional(Map.of("MT", 180, "WS", 270), 15);
    assertEquals(Map.of("MT", 180, "WS", 270), scaled.apply(180));
    // 181 落在 2 × 91 上：两组都按 91 的倍数放宽（2:3），不出现 181/272 这种超周期几十小时的组合。
    assertEquals(Map.of("MT", 182, "WS", 273), scaled.apply(181));
    assertEquals(Map.of("MT", 182, "WS", 273), scaled.apply(182));
  }

  @Test
  void coprimeTargetsFallBackToRounding() {
    var scaled = TimetableBuilder.proportional(Map.of("A", 181, "B", 360), 15);
    assertEquals(Map.of("A", 190, "B", 378), scaled.apply(190));
  }
}
