package org.fetarute.fetaruteTCAddon.drive.driver.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRoutePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStatus;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.repository.TimetableRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("驾驶任务：终到站按车次自己的计划核对")
class DriverTaskTerminalTest {

  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final UUID TIMETABLE = UUID.randomUUID();
  private static final UUID ROUTE = UUID.randomUUID();
  private static final LocalDate DATE = LocalDate.of(2026, 3, 2);
  private static final TaskKey KEY = new TaskKey(TIMETABLE, "R1-001", DATE);

  /** AAA → BBB → CCC（最后一个停车点）→ DEP（回库通过点），08:00 发车。 */
  private static TimetableService service() {
    List<TimetableStop> stops =
        List.of(
            new TimetableStop(
                0, Optional.of("AAA"), Optional.of("OP:S:AAA:1"), 0, 0, RouteStopPassType.STOP),
            new TimetableStop(
                1, Optional.of("BBB"), Optional.of("OP:S:BBB:1"), 100, 130, RouteStopPassType.STOP),
            new TimetableStop(
                2, Optional.of("CCC"), Optional.of("OP:S:CCC:1"), 230, 260, RouteStopPassType.STOP),
            new TimetableStop(
                3, Optional.empty(), Optional.of("OP:D:DEP:1"), 300, 300, RouteStopPassType.PASS));
    Timetable timetable =
        new Timetable(
            TIMETABLE,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "TT1",
            "测试表",
            TimetableStatus.PUBLISHED,
            ZONE,
            8 * 3600,
            9 * 3600,
            List.of(
                new TimetableRoutePlan(
                    ROUTE,
                    "R1",
                    1,
                    stops,
                    "OP:S:AAA:1",
                    "OP:D:DEP:1",
                    Optional.empty(),
                    Optional.empty())),
            List.of(
                new TimetableTrip(
                    UUID.randomUUID(), TIMETABLE, ROUTE, 0, "R1-001", 8 * 3600, Optional.empty())),
            List.of(),
            Optional.empty(),
            Instant.parse("2026-03-01T00:00:00Z"),
            Instant.parse("2026-03-01T00:00:00Z"));
    TimetableService service = new TimetableService(Instant::now, message -> {});
    service.applySettings(
        new TimetableService.Settings(
            true, true, Duration.ofSeconds(120), Duration.ofSeconds(300), Duration.ofSeconds(300)));
    StorageProvider provider = mock(StorageProvider.class);
    TimetableRepository repository = mock(TimetableRepository.class);
    when(provider.timetables()).thenReturn(repository);
    when(repository.listPublished()).thenReturn(List.of(timetable));
    service.reload(provider);
    return service;
  }

  @Test
  @DisplayName("最后一个停车点是终到站，中途站、错站与过时的车次都不是")
  void terminatesOnlyAtTheLastStoppingStop() {
    TimetableService service = service();
    Instant arrived = Instant.parse("2026-03-02T08:04:00Z");

    assertTrue(DriverTaskManager.terminatesAt(service, KEY, 2, "ccc", arrived));
    assertFalse(DriverTaskManager.terminatesAt(service, KEY, 1, "BBB", arrived), "中途站");
    assertFalse(DriverTaskManager.terminatesAt(service, KEY, 2, "BBB", arrived), "站码对不上");
    assertFalse(
        DriverTaskManager.terminatesAt(
            service, new TaskKey(TIMETABLE, "R1-999", DATE), 2, "CCC", arrived),
        "没有这个车次");
    assertFalse(
        DriverTaskManager.terminatesAt(
            service, KEY, 2, "CCC", Instant.parse("2026-03-02T12:00:00Z")),
        "表定时刻不在核对窗口里");
  }
}
