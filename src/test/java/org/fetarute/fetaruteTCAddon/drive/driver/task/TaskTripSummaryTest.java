package org.fetarute.fetaruteTCAddon.drive.driver.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("任务板车次的行程概要")
class TaskTripSummaryTest {

  private static final Instant T0 = Instant.parse("2026-10-04T08:00:00Z");

  /** 车站：到达、发车相对 T0 的秒数。 */
  private static TaskTripSummary.Stop station(
      int sequence, String code, boolean stops, long arriveAt, long departAt) {
    return new TaskTripSummary.Stop(
        sequence,
        Optional.of(code),
        Optional.of("OP:S:" + code + ":1"),
        stops,
        Optional.of(T0.plusSeconds(arriveAt)),
        Optional.of(T0.plusSeconds(departAt)));
  }

  /** 区间点、咽喉：没有站码。 */
  private static TaskTripSummary.Stop waypoint(int sequence, long at) {
    return new TaskTripSummary.Stop(
        sequence,
        Optional.empty(),
        Optional.of("OP:W:" + sequence),
        true,
        Optional.of(T0.plusSeconds(at)),
        Optional.of(T0.plusSeconds(at)));
  }

  /** A(0) → 区间点 → B(通过) → C → 区间点 → D(终点)。 */
  private static List<TaskTripSummary.Stop> line() {
    return List.of(
        station(0, "AAA", true, 0, 30),
        waypoint(1, 90),
        station(2, "BBB", false, 150, 150),
        station(3, "CCC", true, 300, 330),
        waypoint(4, 400),
        station(5, "DDD", true, 600, 600));
  }

  @Test
  @DisplayName("终点站是最后一个停车的车站；停站数不算通过站与区间点，含终点站")
  void terminusAndStopCount() {
    TaskTripSummary.Summary fromA = TaskTripSummary.of(line(), 0).orElseThrow();
    assertEquals("DDD", fromA.terminusCode());
    assertEquals(Optional.of("OP:S:DDD:1"), fromA.terminusNodeId());
    assertEquals(2, fromA.stopCount(), "CCC 与 DDD；BBB 通过不算");

    TaskTripSummary.Summary fromC = TaskTripSummary.of(line(), 3).orElseThrow();
    assertEquals(1, fromC.stopCount());
  }

  @Test
  @DisplayName("运行时长 = 终点站计划到达 - 本站计划发车")
  void runTimeFromTimetable() {
    assertEquals(570L, TaskTripSummary.of(line(), 0).orElseThrow().runSeconds());
    assertEquals(270L, TaskTripSummary.of(line(), 3).orElseThrow().runSeconds());
  }

  @Test
  @DisplayName("在本站终到、或停靠表里没有车站时没有概要")
  void terminatingHereHasNoSummary() {
    assertTrue(TaskTripSummary.of(line(), 5).isEmpty());
    assertTrue(TaskTripSummary.of(List.of(waypoint(0, 0), waypoint(1, 60)), 0).isEmpty());
  }

  @Test
  @DisplayName("缺少计划时刻时运行时长记为 -1，终点站停站数照算")
  void missingTimes() {
    List<TaskTripSummary.Stop> stops =
        List.of(
            new TaskTripSummary.Stop(
                0, Optional.of("AAA"), Optional.empty(), true, Optional.empty(), Optional.empty()),
            new TaskTripSummary.Stop(
                1, Optional.of("BBB"), null, true, Optional.of(T0), Optional.empty()));
    TaskTripSummary.Summary summary = TaskTripSummary.of(stops, 0).orElseThrow();
    assertEquals(-1L, summary.runSeconds());
    assertEquals(1, summary.stopCount());
    assertEquals(Optional.empty(), summary.terminusNodeId());
  }

  @Test
  @DisplayName("终点站只有发车时刻（终到不记到达）时用发车时刻")
  void terminusDepartureFallback() {
    List<TaskTripSummary.Stop> stops =
        List.of(
            station(0, "AAA", true, 0, 0),
            new TaskTripSummary.Stop(
                1,
                Optional.of("BBB"),
                Optional.empty(),
                true,
                Optional.empty(),
                Optional.of(T0.plusSeconds(125))));
    assertEquals(125L, TaskTripSummary.of(stops, 0).orElseThrow().runSeconds());
  }

  @Test
  @DisplayName("运行时长的显示：不到一分钟按秒，不到一小时按分钟四舍五入，更长按小时加分钟")
  void runTimeText() {
    assertEquals(
        new TaskTripSummary.RunTimeText(
            "drive.task.board.run-time.seconds", Map.of("seconds", "45")),
        TaskTripSummary.runTime(45));
    assertEquals(
        new TaskTripSummary.RunTimeText(
            "drive.task.board.run-time.minutes", Map.of("minutes", "10")),
        TaskTripSummary.runTime(570));
    assertEquals(
        new TaskTripSummary.RunTimeText(
            "drive.task.board.run-time.minutes", Map.of("minutes", "1")),
        TaskTripSummary.runTime(60));
    assertEquals(
        new TaskTripSummary.RunTimeText(
            "drive.task.board.run-time.hours", Map.of("hours", "1", "minutes", "5")),
        TaskTripSummary.runTime(3900));
    assertEquals("1 小时 5 分钟", TaskTripSummary.runTime(3900).render("<hours> 小时 <minutes> 分钟"));
    assertThrows(IllegalArgumentException.class, () -> TaskTripSummary.runTime(-1));
  }

  @ParameterizedTest
  @ValueSource(strings = {"zh_CN", "en_US"})
  @DisplayName("任务板新增的文案两种语言都有")
  void boardTextExists(String localeTag) throws Exception {
    YamlConfiguration lang = new YamlConfiguration();
    try (InputStream stream =
        TaskTripSummaryTest.class
            .getClassLoader()
            .getResourceAsStream("lang/" + localeTag + ".yml")) {
      lang.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
    for (String key :
        List.of(
            "run-time.seconds",
            "run-time.minutes",
            "run-time.hours",
            "entry-destination",
            "entry-stops",
            "entry-run-time",
            "entry-name-claimed",
            "entry-claimed",
            "entry-claimed-self",
            "info-claimed",
            "station-not-found",
            "station-ambiguous")) {
      assertTrue(
          lang.isString("drive.task.board." + key), localeTag + " 缺少 drive.task.board." + key);
    }
    assertTrue(lang.isString("drive.task.claim.no-mode-permission"));
    assertTrue(lang.isString("drive.command.start.no-permission"));
    assertTrue(lang.isString("drive.command.mode.no-permission"));
  }
}
