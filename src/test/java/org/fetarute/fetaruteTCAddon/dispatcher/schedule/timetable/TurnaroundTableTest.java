package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.junit.jupiter.api.Test;

/**
 * 折返时间的唯一来源是 route 定义里终到停靠点的 dwell。
 *
 * <p>这一组用例守的是"不引入第二个事实源"：编表侧不许存在一个凭空的折返常数，也不许对同一个 dwell 另起一套解析。
 */
class TurnaroundTableTest {

  private static final UUID FAST = TimetableTestFixtures.routeId("FAST");
  private static final UUID SLOW = TimetableTestFixtures.routeId("SLOW");
  private static final UUID BARE = TimetableTestFixtures.routeId("BARE");

  /** 折返就是终到停靠点配的 dwell，逐 route 各算各的。 */
  @Test
  void turnaroundIsTheTerminalDwellOfEachRoute() {
    TurnaroundTable table =
        TurnaroundTable.ofStops(
            Map.of(
                FAST, TimetableTestFixtures.stops(FAST, 3, 20),
                SLOW, TimetableTestFixtures.stops(SLOW, 3, 30)),
            99);

    assertEquals(20, table.secondsFor(FAST));
    assertEquals(30, table.secondsFor(SLOW));
    assertEquals(20, table.minimumSeconds(), "下界取最短的那条，否则会把接得上的班次判成接不上");
    assertEquals(30, table.maximumSeconds());
    assertFalse(table.fixed());
  }

  /** 同一个站台被两条 route 以不同 dwell 终到时，快车不会被慢车拖慢——这正是按 route 而不是按节点建表的理由。 */
  @Test
  void twoRoutesEndingAtTheSamePlatformKeepTheirOwnDwell() {
    List<RouteStop> fast = TimetableTestFixtures.stops(FAST, 2, 20);
    List<RouteStop> slow = TimetableTestFixtures.stops(SLOW, 2, 30);
    TurnaroundTable table = TurnaroundTable.ofStops(Map.of(FAST, fast, SLOW, slow), 0);

    assertEquals(20, table.secondsFor(FAST));
    assertEquals(30, table.secondsFor(SLOW));
  }

  /** 终到点没配 dwell 时用 {@code --dwell} 兜底值，与行程时分对"停靠却没配"的处理同一条规则。 */
  @Test
  void missingDwellFallsBackToTheDwellOption() {
    TurnaroundTable table =
        TurnaroundTable.ofStops(Map.of(BARE, TimetableTestFixtures.stops(BARE, 2, null)), 25);

    assertEquals(25, table.secondsFor(BARE));
  }

  /** 末站是 PASS（不停站）时折返为 0：没停站就没有关门这件事。 */
  @Test
  void passTerminalMeansNoTurnaround() {
    List<RouteStop> passing =
        List.of(
            new RouteStop(
                BARE,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                RouteStopPassType.STOP,
                Optional.empty()),
            new RouteStop(
                BARE,
                1,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                RouteStopPassType.PASS,
                Optional.empty()));

    assertEquals(0, TurnaroundTable.ofStops(Map.of(BARE, passing), 40).secondsFor(BARE));
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
    assertEquals("终到站 dwell", TurnaroundTable.none().describe());
    assertEquals(
        "终到站 dwell 20s",
        TurnaroundTable.ofStops(Map.of(FAST, TimetableTestFixtures.stops(FAST, 2, 20)), 0)
            .describe());
    assertEquals(
        "终到站 dwell 20–30s",
        TurnaroundTable.ofStops(
                Map.of(
                    FAST, TimetableTestFixtures.stops(FAST, 2, 20),
                    SLOW, TimetableTestFixtures.stops(SLOW, 2, 30)),
                0)
            .describe());
  }
}
