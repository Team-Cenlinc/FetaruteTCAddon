package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 折返时间的唯一来源是 route 定义里的终到停站（dwell，车站再加停站开销）。
 *
 * <p>这一组用例守的是"不引入第二个事实源"：编表侧不许存在一个凭空的折返常数。终到停站怎么从停靠配置算出来 由 {@code TimetableTimingCalculatorTest}
 * 覆盖——那是与途中停站共用的同一个方法。
 */
class TurnaroundTableTest {

  private static final UUID FAST = TimetableTestFixtures.routeId("FAST");
  private static final UUID SLOW = TimetableTestFixtures.routeId("SLOW");
  private static final UUID BARE = TimetableTestFixtures.routeId("BARE");

  /** 按 route 各算各的：同一个站台被两条 route 以不同停站终到时，快车不会被慢车拖慢。 */
  @Test
  void turnaroundIsKeptPerRoute() {
    TurnaroundTable table = TurnaroundTable.ofSeconds(Map.of(FAST, 20, SLOW, 30), 99);

    assertEquals(20, table.secondsFor(FAST));
    assertEquals(30, table.secondsFor(SLOW));
    assertEquals(20, table.minimumSeconds(), "下界取最短的那条，否则会把接得上的班次判成接不上");
    assertEquals(30, table.maximumSeconds());
    assertFalse(table.fixed());
  }

  /** 表里没有的 route 用兜底值（{@code --dwell}），负数按 0。 */
  @Test
  void missingRouteUsesFallbackAndNegativeClampsToZero() {
    TurnaroundTable table = TurnaroundTable.ofSeconds(Map.of(FAST, -5), 25);

    assertEquals(25, table.secondsFor(BARE));
    assertEquals(0, table.secondsFor(FAST));
  }

  /** {@code --turnaround} 是显式覆盖：所有 route 用同一个数，且报告要说得出它来自覆盖。 */
  @Test
  void explicitOverrideAppliesToEveryRoute() {
    TurnaroundTable table = TurnaroundTable.fixed(90);

    assertTrue(table.fixed());
    assertEquals(90, table.secondsFor(FAST));
    assertEquals(90, table.secondsFor(SLOW));
    assertEquals(90, table.minimumSeconds());
    assertTrue(table.describe().contains("--turnaround 90"));
  }

  /** 没有覆盖也没有表时折返是 0，不是某个默认常数——编表侧不存在凭空的折返值。 */
  @Test
  void thereIsNoFabricatedDefault() {
    assertEquals(0, TurnaroundTable.none().secondsFor(FAST));
    assertEquals(0, VehicleDutyPlanner.Limits.defaults().turnaround().secondsFor(FAST));
    assertFalse(VehicleDutyPlanner.Limits.defaults().turnaround().fixed());
  }

  /** 表还没建起来时只说来源不报数字，避免报告里出现两个对不上的秒数。 */
  @Test
  void describeOmitsNumbersUntilTheTableIsBuilt() {
    assertEquals("终到站停站", TurnaroundTable.none().describe());
    assertEquals("终到站停站 24s", TurnaroundTable.ofSeconds(Map.of(FAST, 24), 0).describe());
    assertEquals(
        "终到站停站 20–30s", TurnaroundTable.ofSeconds(Map.of(FAST, 20, SLOW, 30), 0).describe());
  }
}
