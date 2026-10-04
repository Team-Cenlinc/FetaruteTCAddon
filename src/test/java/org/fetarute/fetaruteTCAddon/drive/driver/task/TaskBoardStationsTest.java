package org.fetarute.fetaruteTCAddon.drive.driver.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("按车站代码找任务板的车站")
class TaskBoardStationsTest {

  private static final List<TaskBoardSource.Station> STATIONS =
      List.of(
          new TaskBoardSource.Station("SURC", "HHU", "红湖"),
          new TaskBoardSource.Station("SURC", "LWN", "柳湾"),
          new TaskBoardSource.Station("METRO", "LWN", "柳湾（地铁）"),
          new TaskBoardSource.Station("METRO", "PPK", "平坡"));

  @Test
  @DisplayName("站码不区分大小写；唯一时直接找到")
  void findsUniqueCode() {
    TaskBoardStations.Lookup lookup = TaskBoardStations.find(STATIONS, " hhu ");
    assertEquals(TaskBoardStations.Outcome.FOUND, lookup.outcome());
    assertEquals("红湖", lookup.station().orElseThrow().name());
  }

  @Test
  @DisplayName("重名时要求写明运营商，并列出候选写法")
  void ambiguousCodeNeedsOperator() {
    TaskBoardStations.Lookup lookup = TaskBoardStations.find(STATIONS, "LWN");
    assertEquals(TaskBoardStations.Outcome.AMBIGUOUS, lookup.outcome());
    assertEquals(List.of("METRO:LWN", "SURC:LWN"), lookup.candidates());

    TaskBoardStations.Lookup qualified = TaskBoardStations.find(STATIONS, "metro:lwn");
    assertEquals(TaskBoardStations.Outcome.FOUND, qualified.outcome());
    assertEquals("METRO", qualified.station().orElseThrow().operatorCode());
  }

  @Test
  @DisplayName("找不到的站码、运营商写错或空参数都报不存在")
  void notFound() {
    assertEquals(
        TaskBoardStations.Outcome.NOT_FOUND, TaskBoardStations.find(STATIONS, "XYZ").outcome());
    assertEquals(
        TaskBoardStations.Outcome.NOT_FOUND,
        TaskBoardStations.find(STATIONS, "SURC:PPK").outcome());
    assertEquals(
        TaskBoardStations.Outcome.NOT_FOUND, TaskBoardStations.find(STATIONS, " ").outcome());
    assertEquals(
        TaskBoardStations.Outcome.NOT_FOUND, TaskBoardStations.find(List.of(), "HHU").outcome());
  }

  @Test
  @DisplayName("补全：唯一的站码只写站码，重名的写运营商:站码")
  void suggestions() {
    assertEquals(
        List.of("HHU", "METRO:LWN", "PPK", "SURC:LWN"), TaskBoardStations.suggestions(STATIONS));
  }
}
