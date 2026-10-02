package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 让车预算的边界。
 *
 * <p>这里曾经把两种等待混为一谈：单处让车上限被 {@code timetable.hold-max-seconds} 封成 60–120 秒，理由是 "那是车真能被扣留的上限"。但
 * hold-max 约束的是<b>早到的车在站台被扣多久</b>（超了运行时直接放行）， 而车在资源前排队由占用队列做、运行时无界。用前者限制后者会把大量现实可行的表判成不可行。
 */
class TimetableBuildOptionsTest {

  /** 缺省单处让车上限与累计上限同为 300 秒（= {@code assign-tolerance-seconds} 的默认值）。 */
  @Test
  void maxWaitDefaultsToTolerance() {
    TimetableBuildOptions.Repair defaults = TimetableBuildOptions.Repair.defaults();

    assertEquals(300, defaults.maxWaitSeconds());
    assertEquals(300, defaults.toleranceSeconds());
    assertEquals(
        TimetableBuildOptions.Repair.DEFAULT_TOLERANCE_SECONDS,
        TimetableBuildOptions.Repair.DEFAULT_MAX_WAIT_SECONDS,
        "两个缺省值必须同源，否则第一处让车就会把交路截断");
  }

  /** 累计上限不会小于单步上限：小于的话第一处让车就超累计，交路当场被截断。 */
  @Test
  void toleranceNeverBelowMaxWait() {
    TimetableBuildOptions.Repair repair =
        new TimetableBuildOptions.Repair(Duration.ofSeconds(600), Duration.ofSeconds(120));

    assertEquals(600, repair.maxWaitSeconds());
    assertEquals(600, repair.toleranceSeconds(), "累计上限被抬到单步上限");
  }

  /** 单处让车有自己的上限 1800 秒，不再跟 hold-max 走。 */
  @Test
  void maxWaitIsCappedAtItsOwnCeiling() {
    TimetableBuildOptions.Repair repair =
        new TimetableBuildOptions.Repair(Duration.ofSeconds(9999), Duration.ofSeconds(300));

    assertEquals(
        TimetableBuildOptions.Repair.MAX_WAIT_CEILING_SECONDS, repair.maxWaitSeconds(), "超过上限按上限计");
    assertEquals(1800, TimetableBuildOptions.Repair.MAX_WAIT_CEILING_SECONDS);
  }

  /** 零仍然表示关闭修复，不被"累计不小于单步"那条改写。 */
  @Test
  void zeroStillDisablesRepair() {
    TimetableBuildOptions.Repair none = TimetableBuildOptions.Repair.none();

    assertEquals(0, none.maxWaitSeconds());
    assertEquals(300, none.toleranceSeconds());
  }
}
