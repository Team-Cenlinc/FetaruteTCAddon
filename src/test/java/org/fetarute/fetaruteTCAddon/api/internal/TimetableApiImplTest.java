package org.fetarute.fetaruteTCAddon.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaConfidence;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaReason;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaResult;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaTarget;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationPresenceTracker;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRoutePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStatus;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDuty;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.repository.TimetableRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.Test;

class TimetableApiImplTest {

  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final UUID OPERATOR = UUID.randomUUID();
  private static final UUID LINE = UUID.randomUUID();
  private static final UUID ROUTE = UUID.randomUUID();
  private static final UUID TIMETABLE = UUID.randomUUID();
  private static final UUID DUTY = UUID.randomUUID();
  private static final UUID TRIP_ONE = UUID.randomUUID();
  private static final UUID TRIP_TWO = UUID.randomUUID();

  /** AAA → PPP（通过）→ BBB（停 30 秒）→ CCC（终点），08:00 与 08:10 各一班。 */
  private static Timetable timetable() {
    List<TimetableStop> stops =
        List.of(
            new TimetableStop(0, Optional.of("AAA"), Optional.of("OP:S:AAA:1"), 0, 0),
            new TimetableStop(1, Optional.of("PPP"), Optional.of("OP:S:PPP:1"), 50, 50),
            new TimetableStop(2, Optional.of("BBB"), Optional.of("OP:S:BBB:1"), 100, 130),
            new TimetableStop(3, Optional.of("CCC"), Optional.of("OP:S:CCC:1"), 230, 230));
    return new Timetable(
        TIMETABLE,
        UUID.randomUUID(),
        OPERATOR,
        LINE,
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
                "OP:S:CCC:1",
                Optional.empty(),
                Optional.empty())),
        List.of(
            new TimetableTrip(
                TRIP_TWO, TIMETABLE, ROUTE, 1, "R1-002", 8 * 3600 + 600, Optional.of(DUTY)),
            new TimetableTrip(
                TRIP_ONE, TIMETABLE, ROUTE, 0, "R1-001", 8 * 3600, Optional.of(DUTY))),
        List.of(
            new VehicleDuty(
                DUTY,
                TIMETABLE,
                0,
                "D001",
                "OP:D:DEP:1",
                "OP:D:DEP:1",
                Optional.empty(),
                Optional.empty(),
                List.of(TRIP_ONE, TRIP_TWO),
                8 * 3600 - 180,
                8 * 3600 + 900,
                8 * 3600 + 990,
                VehicleDuty.CloseReason.MAX_TRIPS)),
        Optional.empty(),
        Instant.parse("2026-03-01T00:00:00Z"),
        Instant.parse("2026-03-01T00:00:00Z"));
  }

  private static TimetableService service(boolean enabled) {
    TimetableService service = new TimetableService(Instant::now, message -> {});
    service.applySettings(
        new TimetableService.Settings(
            enabled,
            enabled,
            Duration.ofSeconds(120),
            Duration.ofSeconds(300),
            Duration.ofSeconds(300),
            ZONE));
    StorageProvider provider = mock(StorageProvider.class);
    TimetableRepository repository = mock(TimetableRepository.class);
    when(provider.timetables()).thenReturn(repository);
    when(repository.listPublished()).thenReturn(List.of(timetable()));
    service.reload(provider);
    return service;
  }

  @Test
  void departuresListOnlyStoppingTrainsInWindowSortedByTime() {
    TimetableApiImpl api = new TimetableApiImpl(() -> Optional.of(service(true)), Optional::empty);

    List<TimetableApi.Departure> atB =
        api.departuresAt(
            OPERATOR, "bbb", Instant.parse("2026-03-02T07:59:00Z"), Duration.ofMinutes(20), 10);
    assertEquals(
        List.of("R1-001", "R1-002"), atB.stream().map(TimetableApi.Departure::tripCode).toList());
    assertEquals(Instant.parse("2026-03-02T08:01:40Z"), atB.get(0).plannedArrival());
    assertEquals(Instant.parse("2026-03-02T08:02:10Z"), atB.get(0).plannedDeparture());
    assertFalse(atB.get(0).terminating());

    assertTrue(
        api.departuresAt(
                OPERATOR, "PPP", Instant.parse("2026-03-02T07:59:00Z"), Duration.ofMinutes(20), 10)
            .isEmpty(),
        "通过站不列");

    List<TimetableApi.Departure> atC =
        api.departuresAt(
            null, "CCC", Instant.parse("2026-03-02T08:05:00Z"), Duration.ofMinutes(10), 10);
    assertEquals(1, atC.size());
    assertTrue(atC.get(0).terminating());

    assertTrue(
        api.departuresAt(
                UUID.randomUUID(),
                "BBB",
                Instant.parse("2026-03-02T07:59:00Z"),
                Duration.ofMinutes(20),
                10)
            .isEmpty(),
        "按运营商过滤");
  }

  @Test
  void detailExposesTripsInDepartureOrderWithDutyCodes() {
    TimetableApiImpl api = new TimetableApiImpl(() -> Optional.of(service(true)), Optional::empty);
    assertEquals(1, api.listPublished().size());
    assertEquals(1, api.listByLine(LINE).size());
    TimetableApi.TimetableDetail detail = api.getTimetable(TIMETABLE).orElseThrow();
    assertEquals(
        List.of("R1-001", "R1-002"),
        detail.trips().stream().map(TimetableApi.Trip::tripCode).toList());
    assertEquals(Optional.of("D001"), detail.trips().get(0).dutyCode());
    assertEquals(List.of("R1-001", "R1-002"), detail.duties().get(0).tripCodes());
    assertEquals(RouteOperationType.OPERATION.name(), detail.routePlans().get(0).kind());
  }

  @Test
  void assignmentReportsDelayOfLatestActualStop() {
    TimetableService service = service(true);
    StationPresenceTracker tracker = new StationPresenceTracker();
    TimetableApiImpl api =
        new TimetableApiImpl(() -> Optional.of(service), () -> Optional.of(tracker));
    service.scheduledDepartureAt(
        new StationStopEvent(
            "train-A",
            Optional.of(ROUTE),
            "R1",
            0,
            4,
            "OP:S:AAA:1",
            Instant.parse("2026-03-02T08:00:05Z")));

    TimetableApi.TrainAssignment bound = api.getAssignment("TRAIN-A").orElseThrow();
    assertEquals("R1-001", bound.tripCode());
    assertEquals(Optional.of("D001"), bound.dutyCode());
    assertTrue(bound.currentDelaySeconds().isEmpty(), "尚无实际停靠记录");

    // 计划 08:01:40 到 BBB，实际 08:02:00 到：晚 20 秒。
    tracker.onStationArrival(
        new StationStopEvent(
            "train-A",
            Optional.of(ROUTE),
            "R1",
            2,
            4,
            "OP:S:BBB:1",
            Instant.parse("2026-03-02T08:02:00Z")));
    TimetableApi.TrainAssignment late = api.getAssignment("train-a").orElseThrow();
    assertEquals(Optional.of(2), late.lastStopSequence());
    assertEquals(20L, late.currentDelaySeconds().getAsLong());

    // 计划 08:02:10 发，实际 08:02:05 发：早 5 秒。
    tracker.onStationDeparture(
        new StationStopEvent(
            "train-A",
            Optional.of(ROUTE),
            "R1",
            2,
            4,
            "OP:S:BBB:1",
            Instant.parse("2026-03-02T08:02:05Z")));
    assertEquals(-5L, api.getAssignment("train-a").orElseThrow().currentDelaySeconds().getAsLong());
  }

  @Test
  void previousCircuitRecordsDoNotCountForNewTrip() {
    TimetableService service = service(true);
    StationPresenceTracker tracker = new StationPresenceTracker();
    TimetableApiImpl api =
        new TimetableApiImpl(() -> Optional.of(service), () -> Optional.of(tracker));
    // 上一趟车在 07:55 终到 CCC；本车次 08:00:05 在 AAA 绑定。
    tracker.onStationArrival(
        new StationStopEvent(
            "train-A",
            Optional.of(ROUTE),
            "R1",
            3,
            4,
            "OP:S:CCC:1",
            Instant.parse("2026-03-02T07:55:00Z")));
    service.scheduledDepartureAt(
        new StationStopEvent(
            "train-A",
            Optional.of(ROUTE),
            "R1",
            0,
            4,
            "OP:S:AAA:1",
            Instant.parse("2026-03-02T08:00:05Z")));
    // 修复前只按交路过滤：上一趟的终到被拿去和本车次 CCC 的计划到达（08:03:50）相减。
    assertTrue(api.getAssignment("train-A").orElseThrow().currentDelaySeconds().isEmpty());

    // 促成绑定的那次到站（绑定前 15 秒到 AAA）算本车次。
    tracker.onStationArrival(
        new StationStopEvent(
            "train-A",
            Optional.of(ROUTE),
            "R1",
            0,
            4,
            "OP:S:AAA:1",
            Instant.parse("2026-03-02T07:59:50Z")));
    assertEquals(Optional.of(0), api.getAssignment("train-A").orElseThrow().lastStopSequence());
  }

  @Test
  void projectedDelayTracksEtaToNextStoppingPoint() {
    TimetableService service = service(true);
    EtaService eta = mock(EtaService.class);
    TimetableApiImpl api =
        new TimetableApiImpl(() -> Optional.of(service), Optional::empty, () -> Optional.of(eta));
    service.scheduledDepartureAt(
        new StationStopEvent(
            "train-A",
            Optional.of(ROUTE),
            "R1",
            0,
            4,
            "OP:S:AAA:1",
            Instant.parse("2026-03-02T08:00:05Z")));
    when(eta.getRuntimeSnapshot("train-A"))
        .thenReturn(
            Optional.of(
                new TrainRuntimeSnapshot(
                    1L,
                    Instant.parse("2026-03-02T08:01:00Z"),
                    UUID.randomUUID(),
                    ROUTE,
                    RouteId.of("OP:L1:R1"),
                    0,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty())));
    // 在区间被扣停：ETA 预计 08:02:25 到 BBB（计划 08:01:40），跳过只通过的 PPP。
    when(eta.getForTrain(eq("train-A"), eq(new EtaTarget.PlatformNode(NodeId.of("OP:S:BBB:1")))))
        .thenReturn(
            new EtaResult(
                false,
                "Delayed 1m",
                Instant.parse("2026-03-02T08:02:25Z").toEpochMilli(),
                1,
                30,
                0,
                60,
                List.of(EtaReason.HOLD),
                EtaConfidence.LOW));
    TimetableApi.TrainAssignment a = api.getAssignment("train-A").orElseThrow();
    assertEquals(Optional.of(2), a.nextStopSequence());
    assertEquals(45L, a.projectedDelaySeconds().getAsLong());
  }

  @Test
  void disabledTimetableHidesAssignmentsButKeepsPublishedQueries() {
    TimetableApiImpl api = new TimetableApiImpl(() -> Optional.of(service(false)), Optional::empty);
    assertFalse(api.enabled());
    assertTrue(api.getAssignment("train-A").isEmpty());
    assertEquals(1, api.listPublished().size());
  }

  @Test
  void missingServiceIsEmptyNotNull() {
    TimetableApiImpl api = new TimetableApiImpl(Optional::empty, Optional::empty);
    assertFalse(api.enabled());
    assertTrue(api.listPublished().isEmpty());
    assertTrue(api.departuresAt(null, "AAA", Instant.now(), Duration.ofMinutes(5), 5).isEmpty());
  }
}
