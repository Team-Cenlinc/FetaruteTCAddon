package org.fetarute.fetaruteTCAddon.drive.driver.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("派任务时找接班站与下车站")
class TaskStationsTest {

  /** 车库 → AAA → 区间点 → BBB（通过） → CCC → DDD（终点）。 */
  private static final List<TaskStations.Stop> STOPS =
      List.of(
          new TaskStations.Stop(0, Optional.empty(), true),
          new TaskStations.Stop(1, Optional.of("AAA"), true),
          new TaskStations.Stop(2, Optional.empty(), false),
          new TaskStations.Stop(3, Optional.of("BBB"), false),
          new TaskStations.Stop(4, Optional.of("CCC"), true),
          new TaskStations.Stop(5, Optional.of("DDD"), true));

  @Test
  @DisplayName("不写接班站：从第一个停车的车站开到终点站")
  void defaultsToTheFirstStationAndTheTerminus() {
    TaskStations.Resolved resolved =
        TaskStations.resolve(STOPS, Optional.empty(), Optional.empty()).orElseThrow();

    assertEquals(1, resolved.board().sequence());
    assertTrue(resolved.alight().isEmpty());
  }

  @Test
  @DisplayName("区间任务：下车站在接班站之后；写的是终点站时按开到终点站")
  void intervalTasks() {
    TaskStations.Resolved resolved =
        TaskStations.resolve(STOPS, Optional.of("aaa"), Optional.of("CCC")).orElseThrow();
    assertEquals(1, resolved.board().sequence());
    assertEquals(4, resolved.alight().orElseThrow().sequence());

    assertTrue(
        TaskStations.resolve(STOPS, Optional.of("CCC"), Optional.of("DDD"))
            .orElseThrow()
            .alight()
            .isEmpty());
  }

  @Test
  @DisplayName("通过的车站、终点站接班、下车站在接班站之前都不行")
  void invalidCombinations() {
    assertTrue(TaskStations.resolve(STOPS, Optional.of("BBB"), Optional.empty()).isEmpty());
    assertTrue(TaskStations.resolve(STOPS, Optional.of("DDD"), Optional.empty()).isEmpty());
    assertTrue(TaskStations.resolve(STOPS, Optional.of("CCC"), Optional.of("AAA")).isEmpty());
    assertTrue(TaskStations.resolve(STOPS, Optional.of("AAA"), Optional.of("BBB")).isEmpty());
    assertTrue(TaskStations.resolve(STOPS, Optional.of("ZZZ"), Optional.empty()).isEmpty());
  }

  @Test
  @DisplayName("同一车次两次经过同一站：按停靠序号接班")
  void boardsAtTheGivenOccurrence() {
    List<TaskStations.Stop> loop =
        List.of(
            new TaskStations.Stop(0, Optional.of("AAA"), true),
            new TaskStations.Stop(1, Optional.of("BBB"), true),
            new TaskStations.Stop(2, Optional.of("CCC"), true),
            new TaskStations.Stop(3, Optional.of("BBB"), true),
            new TaskStations.Stop(4, Optional.of("AAA"), true));

    assertEquals(
        1,
        TaskStations.resolve(loop, Optional.of("BBB"), Optional.empty())
            .orElseThrow()
            .board()
            .sequence());
    TaskStations.Resolved second =
        TaskStations.resolve(loop, Optional.of("BBB"), 3, Optional.of("AAA")).orElseThrow();
    assertEquals(3, second.board().sequence());
    assertTrue(second.alight().isEmpty(), "AAA 在第二次 BBB 之后只剩终点站");
    assertTrue(
        TaskStations.resolve(loop, Optional.of("CCC"), 3, Optional.empty()).isEmpty(),
        "序号与站码不符时不接班");
  }
}
