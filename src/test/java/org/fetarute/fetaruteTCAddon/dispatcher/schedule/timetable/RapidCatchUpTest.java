package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 成品表上的快车被卡：报告一行怎么写、怎么写进构建结果。 */
class RapidCatchUpTest {

  /** 报告一行：按快车交路分开，点名拖住它最多的慢车。 */
  @Test
  void theReportNamesTheWorstBlocker() {
    UUID rapid = TimetableTestFixtures.routeId("RAPID");
    UUID local = TimetableTestFixtures.routeId("LOCAL");
    UUID other = TimetableTestFixtures.routeId("OTHER");
    RapidCatchUp.Measure measure =
        measure(
            130L,
            2,
            List.of(
                new CorridorCatchUp.Caught("R1", rapid, 0, 0, 100, local),
                new CorridorCatchUp.Caught("R2", rapid, 720, 0, 30, other)),
            0L);

    String line =
        RapidCatchUp.describe(measure, Map.of(rapid, "RAPID", local, "LOCAL", other, "OTHER"));

    assertEquals("快车被卡（成品表实测）：RAPID 2 班、共 130s（主要被 LOCAL 拖住）", line);
    assertEquals("快车被卡（成品表实测）：无", RapidCatchUp.describe(measure(0L, 0, List.of(), 0L), Map.of()));
  }

  /** 实测写进构建结果：结构化合计与一行说明。 */
  @Test
  void theMeasureIsWrittenIntoTheResult() {
    UUID rapid = TimetableTestFixtures.routeId("RAPID");
    UUID local = TimetableTestFixtures.routeId("LOCAL");
    RapidCatchUp.Measure measure =
        measure(100L, 1, List.of(new CorridorCatchUp.Caught("R1", rapid, 0, 0, 100, local)), 0L);

    TimetableBuildResult result =
        RapidCatchUp.annotate(
            TimetableBuildResult.failure("x", List.of()),
            measure,
            Map.of(rapid, "RAPID", local, "LOCAL"));

    assertEquals(new TimetableBuildResult.CatchUp(100L, 1, 0L), result.rapidCatchUp());
    assertEquals(List.of("快车被卡（成品表实测）：RAPID 1 班、共 100s（主要被 LOCAL 拖住）"), result.phaseNotes());
  }

  /** 表里写给快车的让车等待一并写出。 */
  @Test
  void theReportAddsTheRapidsWaitingInTheTable() {
    assertEquals(
        "快车被卡（成品表实测）：无；表里另有快车让车等待共 40s",
        RapidCatchUp.describe(measure(0L, 0, List.of(), 40L), Map.of()));
  }

  private static RapidCatchUp.Measure measure(
      long seconds, int trips, List<CorridorCatchUp.Caught> caught, long held) {
    return new RapidCatchUp.Measure(seconds, trips, caught, held);
  }
}
