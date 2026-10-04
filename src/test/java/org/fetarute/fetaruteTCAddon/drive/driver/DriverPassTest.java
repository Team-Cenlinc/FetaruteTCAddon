package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService.PlannedStop;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("下一通过站")
class DriverPassTest {

  private static final Instant T0 = Instant.parse("2026-10-04T04:00:00Z");

  private static PlannedStop stop(int sequence, String code, boolean stops, long offset) {
    return new PlannedStop(
        sequence,
        Optional.ofNullable(code),
        Optional.of("OP:S:" + sequence),
        stops,
        Optional.of(T0.plusSeconds(offset)),
        Optional.of(T0.plusSeconds(offset)));
  }

  private static final List<PlannedStop> STOPS =
      List.of(
          stop(0, "A", true, 0),
          stop(1, null, false, 30),
          stop(2, "B", false, 60),
          stop(3, "C", false, 90),
          stop(4, "D", true, 120));

  @Test
  @DisplayName("取本站之后、下一停车站之前第一个通过的车站，跳过不是车站的途经点")
  void firstPassBeforeNextStop() {
    DriverPass pass =
        DriverPass.next(STOPS, 0, OptionalInt.of(4), OptionalLong.of(45L), node -> "站" + node)
            .orElseThrow();

    assertEquals("站OP:S:2", pass.station());
    assertEquals(T0.plusSeconds(60), pass.planned());
    assertEquals(DriverSchedule.State.LATE, pass.state());
    assertEquals("0:45", pass.deviationText());
  }

  @Test
  @DisplayName("驶过一个通过站后换成下一个；下一停车站之前没有通过站时为空")
  void advancesAndStopsAtNextStop() {
    assertEquals(
        T0.plusSeconds(90),
        DriverPass.next(STOPS, 2, OptionalInt.of(4), OptionalLong.empty(), node -> "")
            .orElseThrow()
            .planned());
    assertTrue(
        DriverPass.next(STOPS, 3, OptionalInt.of(4), OptionalLong.empty(), node -> "").isEmpty());
  }

  @Test
  @DisplayName("取不到站名时用站码；不按表运行时为空")
  void fallsBackToStationCode() {
    assertEquals(
        "B",
        DriverPass.next(STOPS, 0, OptionalInt.of(4), OptionalLong.empty(), node -> "")
            .orElseThrow()
            .station());
    assertTrue(
        DriverPass.next(List.of(), 0, OptionalInt.empty(), OptionalLong.empty(), node -> "x")
            .isEmpty());
  }
}
