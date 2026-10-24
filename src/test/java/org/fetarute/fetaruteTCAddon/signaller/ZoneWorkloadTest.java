package org.fetarute.fetaruteTCAddon.signaller;

import static org.fetarute.fetaruteTCAddon.signaller.TestTimetables.stop;
import static org.fetarute.fetaruteTCAddon.signaller.TestTimetables.timetable;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.display.pids.yard.YardArea;
import org.junit.jupiter.api.Test;

/** 中心工位的工作量：窗口内表定停站或通过的车次数加道岔折算；整个大区窗口内一个表定车次都没有时按股道数估。 */
class ZoneWorkloadTest {

  private static final Instant NOW = Instant.parse("2026-01-01T08:00:00Z");
  private static final Duration HOUR = Duration.ofHours(1);

  private static final ZoneUnits.Unit ABC =
      unit(YardArea.Member.station("ABC"), Set.of(NodeId.of("SURC:S:ABC:1")), 2, 2);
  private static final ZoneUnits.Unit DEF =
      unit(YardArea.Member.station("DEF"), Set.of(NodeId.of("SURC:S:DEF:1")), 0, 1);
  private static final ZoneUnits.Unit DEP =
      unit(YardArea.Member.depot("DEP"), Set.of(NodeId.of("SURC:D:DEP:1")), 0, 3);

  /** 停站与通过都算，同一车次在一个单元里只算一次；窗口外的不算；道岔按 0.5 折算。 */
  @Test
  void countsTimetabledCallsInTheWindowPlusSwitches() {
    // 08:10、08:40 两班经过 ABC（停）与 DEF（通过）；09:30 那班在窗口外
    Timetable timetable =
        timetable(
            List.of(
                stop(0, "ABC", "SURC:S:ABC:1", 0, 30, RouteStopPassType.STOP),
                stop(1, "ABC", "SURC:S:ABC:1", 40, 40, RouteStopPassType.PASS),
                stop(2, "DEF", "SURC:S:DEF:1", 300, 300, RouteStopPassType.PASS)),
            8 * 3600 + 600,
            8 * 3600 + 2400,
            9 * 3600 + 1800);

    double[] loads =
        ZoneWorkload.of(List.of(ABC, DEF, DEP), "SURC", List.of(timetable), NOW, HOUR, 0.5);

    assertArrayEquals(new double[] {2 + 1.0, 2, 0}, loads, 1e-9);
  }

  /** 前一天发车、跨零点到站的车次照样算进今天的窗口。 */
  @Test
  void aTripFromTheDayBeforeStillCounts() {
    Timetable timetable =
        timetable(
            List.of(
                stop(0, "DEF", "SURC:S:DEF:1", 0, 0, RouteStopPassType.STOP),
                stop(1, "ABC", "SURC:S:ABC:1", 3600, 3600, RouteStopPassType.STOP)),
            23 * 3600 + 1800);

    double[] loads =
        ZoneWorkload.of(
            List.of(ABC, DEF),
            "SURC",
            List.of(timetable),
            Instant.parse("2026-01-02T00:00:00Z"),
            HOUR,
            0.5);

    assertArrayEquals(new double[] {1 + 1.0, 0}, loads, 1e-9);
  }

  /** 按 DYNAMIC 写法停靠的车次按写法里的车站、车库认。 */
  @Test
  void dynamicStopsCountTowardTheirMember() {
    Timetable timetable =
        timetable(
            List.of(
                stop(0, null, "DYNAMIC:SURC:D:DEP", 0, 0, RouteStopPassType.STOP),
                stop(1, "ABC", "DYNAMIC:SURC:S:ABC:[1:2]", 120, 150, RouteStopPassType.STOP)),
            8 * 3600 + 600);

    double[] loads = ZoneWorkload.of(List.of(ABC, DEP), "SURC", List.of(timetable), NOW, HOUR, 0.5);

    assertArrayEquals(new double[] {1 + 1.0, 1}, loads, 1e-9);
  }

  /** 窗口内整个大区一个表定车次都没有时，车次数换成站台股道与库线数。 */
  @Test
  void fallsBackToTrackCountsWithoutTimetabledTrains() {
    double[] loads = ZoneWorkload.of(List.of(ABC, DEF, DEP), "SURC", List.of(), NOW, HOUR, 0.5);

    assertArrayEquals(new double[] {2 + 1.0, 1, 3}, loads, 1e-9);
  }

  private static ZoneUnits.Unit unit(
      YardArea.Member member, Set<NodeId> nodes, int switches, int tracks) {
    return new ZoneUnits.Unit(List.of(member), nodes, 0.0, 0.0, switches, tracks);
  }
}
