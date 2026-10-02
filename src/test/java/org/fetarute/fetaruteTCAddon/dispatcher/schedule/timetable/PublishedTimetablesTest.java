package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 同一线路只让一张表生效：按不同时区编的两张同规律表同时发布时，每个班次会在同一秒各出一张票，接不到车的那张挂满容差作废并登记取消。 */
class PublishedTimetablesTest {

  private static final UUID LINE_MT = UUID.randomUUID();
  private static final UUID LINE_WS = UUID.randomUUID();
  private static final Instant OLD = Instant.parse("2026-09-30T12:00:00Z");
  private static final Instant NEW = Instant.parse("2026-10-01T12:00:00Z");

  @Test
  void theLatestPublishedTableOfALineWinsAndTheOtherIsShadowed() {
    Timetable composed = table("SURC_Composed", LINE_MT, "Asia/Shanghai", 0, 86_400, OLD);
    Timetable stagger = table("SURC_Stagger", LINE_MT, "America/Chicago", 0, 86_400, NEW);
    Timetable otherLine = table("WS_Table", LINE_WS, "Asia/Shanghai", 0, 86_400, OLD);

    PublishedTimetables.Selection selection =
        PublishedTimetables.select(List.of(composed, stagger, otherLine));

    assertEquals(List.of(stagger, otherLine), selection.active());
    assertEquals(1, selection.shadowed().size());
    assertEquals(composed, selection.shadowed().get(0).timetable());
    assertEquals(stagger, selection.shadowed().get(0).by());
  }

  @Test
  void disjointServiceWindowsInTheSameZoneCoexist() {
    Timetable morning = table("AM", LINE_MT, "UTC", 0, 43_200, OLD);
    Timetable evening = table("PM", LINE_MT, "UTC", 43_200, 86_400, NEW);

    assertFalse(PublishedTimetables.overlaps(morning, evening));
    assertEquals(2, PublishedTimetables.select(List.of(morning, evening)).active().size());
  }

  /** 跨零点的日间表（05:00–25:00）尾段落在夜间表（00:30–04:00）次日的开头；不跨进对方时段的照样共存。 */
  @Test
  void aWindowRunningPastMidnightOverlapsTheNextMorning() {
    Timetable day = table("DAY", LINE_MT, "UTC", 18_000, 90_000, OLD);
    Timetable night = table("NIGHT", LINE_MT, "UTC", 1_800, 14_400, NEW);
    Timetable early = table("EARLY", LINE_MT, "UTC", 3_600, 14_400, NEW);
    Timetable shortDay = table("SHORT", LINE_MT, "UTC", 18_000, 86_400, OLD);

    assertTrue(PublishedTimetables.overlaps(day, night));
    assertTrue(PublishedTimetables.overlaps(night, day));
    assertFalse(PublishedTimetables.overlaps(day, early));
    assertFalse(PublishedTimetables.overlaps(shortDay, night));
  }

  @Test
  void differentZonesCannotBeComparedAndCountAsOverlapping() {
    assertTrue(
        PublishedTimetables.overlaps(
            table("A", LINE_MT, "UTC", 0, 3_600, OLD),
            table("B", LINE_MT, "Asia/Shanghai", 7_200, 10_800, NEW)));
  }

  private static Timetable table(
      String code, UUID lineId, String zone, int start, int end, Instant updatedAt) {
    return new Timetable(
        UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        UUID.randomUUID(),
        UUID.randomUUID(),
        lineId,
        code,
        code,
        TimetableStatus.PUBLISHED,
        ZoneId.of(zone),
        start,
        end,
        List.of(),
        List.of(),
        List.of(),
        Optional.empty(),
        updatedAt,
        updatedAt);
  }
}
