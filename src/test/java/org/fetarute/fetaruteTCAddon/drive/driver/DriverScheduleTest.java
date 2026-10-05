package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class DriverScheduleTest {

  private static final Instant NOW = Instant.parse("2026-10-03T08:00:00Z");

  private static final DriverSchedule.Lookup ARRIVALS =
      (train, index) -> Optional.of(NOW.plusSeconds(100L * index));

  private static final DriverSchedule.Lookup DEPARTURES =
      (train, index) -> Optional.of(NOW.plusSeconds(100L * index + 30L));

  @Test
  void betweenStationsItShowsTheScheduledArrivalAtTheNextStop() {
    DriverSchedule schedule =
        DriverSchedule.of(
                "T1", false, 2, OptionalInt.of(3), OptionalLong.of(80L), ARRIVALS, DEPARTURES, NOW)
            .orElseThrow();

    assertFalse(schedule.departure());
    assertEquals(NOW.plusSeconds(300L), schedule.planned());
    assertEquals(DriverSchedule.State.LATE, schedule.state());
    assertEquals("1:20", schedule.deviationText());
  }

  @Test
  void atAStationItShowsTheScheduledDepartureFromIt() {
    DriverSchedule waiting =
        DriverSchedule.of(
                "T1", true, 2, OptionalInt.of(3), OptionalLong.empty(), ARRIVALS, DEPARTURES, NOW)
            .orElseThrow();

    assertTrue(waiting.departure());
    assertEquals(NOW.plusSeconds(230L), waiting.planned());
    // 还没到表定发车时刻：不提示（停站倒计时在显示）。
    assertEquals(DriverSchedule.State.PLAIN, waiting.state());

    DriverSchedule late =
        DriverSchedule.of(
                "T1",
                true,
                2,
                OptionalInt.empty(),
                OptionalLong.empty(),
                ARRIVALS,
                DEPARTURES,
                NOW.plusSeconds(275L))
            .orElseThrow();
    assertEquals(DriverSchedule.State.LATE, late.state());
    assertEquals("0:45", late.deviationText());
  }

  @Test
  void smallDeviationsAreOnTimeAndLargeNegativeOnesEarly() {
    DriverSchedule onTime =
        new DriverSchedule(false, NOW, OptionalLong.of(-DriverSchedule.ON_TIME_SECONDS));
    DriverSchedule early =
        new DriverSchedule(false, NOW, OptionalLong.of(-DriverSchedule.ON_TIME_SECONDS - 1L));

    assertEquals(DriverSchedule.State.ON_TIME, onTime.state());
    assertEquals(DriverSchedule.State.EARLY, early.state());
    assertEquals("0:31", early.deviationText());
    assertEquals(
        DriverSchedule.State.PLAIN, new DriverSchedule(false, NOW, OptionalLong.empty()).state());
  }

  @Test
  void nothingIsShownWithoutATimetableOrANextStop() {
    DriverSchedule.Lookup none = (train, index) -> Optional.empty();

    assertTrue(
        DriverSchedule.of("T1", false, 2, OptionalInt.of(3), OptionalLong.empty(), none, none, NOW)
            .isEmpty());
    assertTrue(
        DriverSchedule.of(
                "T1",
                false,
                2,
                OptionalInt.empty(),
                OptionalLong.empty(),
                ARRIVALS,
                DEPARTURES,
                NOW)
            .isEmpty());
  }
}
