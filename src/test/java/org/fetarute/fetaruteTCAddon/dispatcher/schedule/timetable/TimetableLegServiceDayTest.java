package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 展示车次按交路的服务日秒数定位，不能把窗口前出库误判为次日凌晨。 */
class TimetableLegServiceDayTest {

  @ParameterizedTest
  @CsvSource({
    "CREATE, 28800, 28620, 32400",
    "CREATE, 0, -180, 3600",
    "CREATE, 0, 86580, 90000",
    "RETURN, 28800, 28620, 90000",
    "RETURN, 0, -180, 176400"
  })
  void legDateMatchesUnwrappedDutyTimeAndRoundTrips(
      RouteOperationType kind, int windowStart, int createSecond, int returnSecond) {
    UUID tableId = UUID.randomUUID();
    UUID dutyId = UUID.randomUUID();
    UUID createRoute = UUID.randomUUID(), returnRoute = UUID.randomUUID();
    int second = kind == RouteOperationType.CREATE ? createSecond : returnSecond;
    TimetableTrip display =
        new TimetableTrip(
            UUID.randomUUID(),
            tableId,
            kind == RouteOperationType.CREATE ? createRoute : returnRoute,
            0,
            "LEG-001",
            Math.floorMod(second, TimetableTrip.SECONDS_PER_DAY),
            Optional.of(dutyId));
    VehicleDuty duty =
        new VehicleDuty(
            dutyId,
            tableId,
            0,
            "D-001",
            "OP:D:DEP:1",
            "OP:D:DEP:1",
            Optional.of(createRoute),
            Optional.of(returnRoute),
            List.of(UUID.randomUUID()),
            createSecond,
            returnSecond,
            returnSecond + 180,
            VehicleDuty.CloseReason.HORIZON_END,
            Optional.empty());
    Instant midnight = Instant.parse("2026-03-02T00:00:00Z");
    Timetable table =
        new Timetable(
            tableId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "TEST",
            "Test",
            TimetableStatus.PUBLISHED,
            ZoneId.of("UTC"),
            windowStart,
            180000,
            List.of(),
            List.of(display),
            List.of(duty),
            Optional.empty(),
            midnight,
            midnight);
    LocalDate serviceDay = LocalDate.of(2026, 3, 2);
    Instant expected = midnight.plusSeconds(second);

    assertEquals(expected, table.departureOnServiceDay(display, serviceDay));
    assertEquals(
        serviceDay, table.serviceDayOf(display, LocalDate.ofInstant(expected, table.zoneId())));
  }
}
