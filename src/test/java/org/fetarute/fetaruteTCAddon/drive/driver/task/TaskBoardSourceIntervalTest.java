package org.fetarute.fetaruteTCAddon.drive.driver.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.FetaruteTCAddon;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 自选练习的区间任务：从列车所在的一站起接班，开过几个停车站后交班；不停的站不算。 */
class TaskBoardSourceIntervalTest {

  private static final UUID TIMETABLE = UUID.randomUUID();
  private static final LocalDate DAY = LocalDate.of(2026, 1, 1);
  private static final TaskKey KEY = new TaskKey(TIMETABLE, "WS-2C", DAY);
  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private FetaruteTCAddon plugin;
  private TimetableService timetables;

  private static TimetableService.PlannedStop stop(int sequence, String station, boolean stops) {
    return new TimetableService.PlannedStop(
        sequence,
        Optional.ofNullable(station),
        Optional.empty(),
        stops,
        Optional.of(T0.plusSeconds(sequence * 60L)),
        Optional.of(T0.plusSeconds(sequence * 60L + 20L)));
  }

  @BeforeEach
  void setUp() {
    plugin = mock(FetaruteTCAddon.class);
    when(plugin.getStationDirectory()).thenReturn(Optional.empty());
    timetables = mock(TimetableService.class);
    when(timetables.tripPlan(TIMETABLE, "WS-2C", DAY))
        .thenReturn(
            Optional.of(
                new TimetableService.TripPlan(
                    UUID.randomUUID(),
                    "WS-2C",
                    UUID.randomUUID(),
                    "WS-2",
                    List.of(
                        stop(0, "A", true),
                        stop(1, null, false),
                        stop(2, "B", true),
                        stop(3, "C", true),
                        stop(4, "D", true)))));
  }

  @Test
  void handsOverAfterTheGivenNumberOfStops() {
    DriverTaskManager.TaskSpec spec =
        TaskBoardSource.intervalSpec(
                plugin, timetables, KEY, 0, "T-1", 2, DriverTask.SOURCE_TRAINING, Map.of())
            .orElseThrow();
    assertEquals(0, spec.takeoverStopSequence());
    assertEquals("A", spec.stationCode());
    assertEquals(3, spec.handoverStopSequence());
    assertEquals("C", spec.handoverStationCode());
    assertEquals("T-1", spec.trainName());
    assertEquals(DriverTask.SOURCE_TRAINING, spec.source());
    assertEquals(T0.plusSeconds(20L), spec.plannedDeparture());
  }

  /** 列车停在不停车的点时，往后顺延到第一个停车站接班。 */
  @Test
  void takeoverSkipsPassingPoints() {
    DriverTaskManager.TaskSpec spec =
        TaskBoardSource.intervalSpec(
                plugin, timetables, KEY, 1, "T-1", 2, DriverTask.SOURCE_TRAINING, Map.of())
            .orElseThrow();
    assertEquals("B", spec.stationCode());
    assertEquals("D", spec.handoverStationCode());
  }

  @Test
  void notEnoughStopsLeft() {
    assertTrue(
        TaskBoardSource.intervalSpec(
                plugin, timetables, KEY, 2, "T-1", 3, DriverTask.SOURCE_TRAINING, Map.of())
            .isEmpty());
    assertEquals(OptionalInt.of(2), TaskBoardSource.stopsAhead(timetables, KEY, 2));
    assertEquals(OptionalInt.of(0), TaskBoardSource.stopsAhead(timetables, KEY, 4));
    assertTrue(TaskBoardSource.stopsAhead(timetables, KEY, 5).isEmpty());
  }

  /** 查不到线路名时留空（不拿内部的交路代码顶替）；终点站与发车时刻照常给出，查不到站台时写“-”。 */
  @Test
  void labelWithoutLineName() {
    DriverTaskManager.TaskSpec spec =
        TaskBoardSource.intervalSpec(
                plugin, timetables, KEY, 0, "T-1", 2, DriverTask.SOURCE_TRAINING, Map.of())
            .orElseThrow();
    TaskBoardSource.TripLabel label = TaskBoardSource.label(plugin, timetables, spec);
    assertEquals("", label.line());
    assertEquals("D", label.destination());
    assertEquals("-", label.platform());
    assertEquals(5, label.time().length());
  }
}
