package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TimetableTripLookupTest {

  private static final UUID TIMETABLE_ID = UUID.randomUUID();
  private static final UUID ROUTE_ID = UUID.randomUUID();
  private static final UUID ID_COMPANY = UUID.randomUUID();
  private static final UUID ID_OPERATOR = UUID.randomUUID();
  private static final UUID ID_LINE = UUID.randomUUID();

  @Test
  void tripByCodeIgnoresCaseAndSurroundingBlanks() {
    TimetableTrip early = trip("ws101", 600);
    TimetableTrip late = trip("WS102", 1200);
    Timetable timetable = timetable(List.of(late, early));

    assertEquals(Optional.of(early), timetable.tripByCode(" WS101 "));
    assertEquals(Optional.of(late), timetable.tripByCode("ws102"));
    assertTrue(timetable.tripByCode("WS103").isEmpty());
    assertTrue(timetable.tripByCode(" ").isEmpty());
  }

  @Test
  void duplicateCodeResolvesToEarliestDeparture() {
    TimetableTrip early = trip("WS101", 600);
    TimetableTrip late = trip("ws101", 1200);

    Timetable timetable = timetable(List.of(late, early));

    assertEquals(Optional.of(early), timetable.tripByCode("WS101"));
  }

  @Test
  void tripByIdAndListContract() {
    TimetableTrip early = trip("WS101", 600);
    TimetableTrip late = trip("WS102", 1200);
    Timetable timetable = timetable(List.of(late, early));

    assertEquals(Optional.of(late), timetable.trip(late.id()));
    assertTrue(timetable.trip(UUID.randomUUID()).isEmpty());
    assertEquals(List.of(early, late), timetable.trips());
    assertEquals(timetable, timetable(List.of(early, late)));
    assertThrows(UnsupportedOperationException.class, () -> timetable.trips().add(early));
  }

  private static TimetableTrip trip(String code, int departure) {
    return new TimetableTrip(
        UUID.randomUUID(), TIMETABLE_ID, ROUTE_ID, 0, code, departure, Optional.empty());
  }

  private static Timetable timetable(List<TimetableTrip> trips) {
    return new Timetable(
        TIMETABLE_ID,
        ID_COMPANY,
        ID_OPERATOR,
        ID_LINE,
        "T",
        "T",
        TimetableStatus.DRAFT,
        ZoneId.of("UTC"),
        0,
        86_400,
        List.of(),
        trips,
        List.of(),
        Optional.empty(),
        Instant.EPOCH,
        Instant.EPOCH);
  }
}
