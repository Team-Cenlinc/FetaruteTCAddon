package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("越站登记")
class SkippedStopsTest {

  private final UUID timetable = UUID.randomUUID();
  private final UUID trip = UUID.randomUUID();
  private final LocalDate today = LocalDate.of(2026, 10, 3);

  @Test
  @DisplayName("只标记越站的那一站，同一站重复登记只算一次")
  void onlyThatStop() {
    SkippedStops skipped = new SkippedStops();
    assertTrue(skipped.record(timetable, trip, today, 3));
    assertFalse(skipped.record(timetable, trip, today, 3));
    assertTrue(skipped.contains(timetable, trip, today, 3));
    assertFalse(skipped.contains(timetable, trip, today, 4));
    assertFalse(skipped.contains(timetable, trip, today.minusDays(1), 3));
  }

  @Test
  @DisplayName("过期的运营日与下架的时刻表会被清掉")
  void prunes() {
    SkippedStops skipped = new SkippedStops();
    skipped.record(timetable, trip, today.minusDays(5), 1);
    skipped.record(timetable, trip, today, 2);
    assertFalse(skipped.contains(timetable, trip, today.minusDays(5), 1), "两天前的运营日不再保留");
    skipped.retainTimetables(Set.of(UUID.randomUUID()));
    assertFalse(skipped.contains(timetable, trip, today, 2));
  }
}
