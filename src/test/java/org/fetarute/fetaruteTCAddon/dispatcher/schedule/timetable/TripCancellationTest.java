package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TripCancellations.Cancellation;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TripCancellations.Reason;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TripCancellations.Scope;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.repository.TimetableRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.Test;

/**
 * 车次取消登记：整趟没派出车（票作废、追补超限），以及执行中的车离开运行时后剩下的站。
 *
 * <p>交路：AAA(0) → 区间点(1，通过) → BBB(2) → CCC(3，终点) → 回库通过点(4)。三趟车 08:00 / 08:10 / 08:20。
 */
class TripCancellationTest {

  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final UUID ROUTE = UUID.randomUUID();
  private static final UUID OTHER_ROUTE = UUID.randomUUID();
  private static final UUID TIMETABLE = UUID.randomUUID();
  private static final UUID TRIP_0800 = UUID.randomUUID();
  private static final UUID TRIP_0810 = UUID.randomUUID();
  private static final UUID TRIP_0820 = UUID.randomUUID();
  private static final LocalDate DATE = LocalDate.of(2026, 3, 2);
  private static final Instant EIGHT = Instant.parse("2026-03-02T08:00:00Z");
  private static final String TRAIN = "SURC-MT-LP-3291";

  private final List<Cancellation> heard = new ArrayList<>();
  private final List<String> logs = new ArrayList<>();
  private final TimetableService service = service();

  /** 发过车的站不算，停着还没发车的这一站算：从 BBB 起取消，开出过就是半途取消。 */
  @Test
  void releaseMidTripCancelsFromTheFirstStopNotYetDeparted() {
    assign(TRAIN, EIGHT);
    service.observeStop(event(TRAIN, ROUTE, 0), true);
    service.observeStop(event(TRAIN, ROUTE, 2), false);

    service.release(TRAIN, "stuck-cleanup");

    Cancellation cancelled = onlyHeard();
    assertEquals(TRIP_0800, cancelled.tripId());
    assertEquals(DATE, cancelled.serviceDate());
    assertEquals(EIGHT, cancelled.plannedDeparture());
    assertEquals(Scope.PARTIAL, cancelled.scope());
    assertEquals(2, cancelled.firstCancelledStopSequence());
    assertEquals(Reason.VEHICLE_REMOVED, cancelled.reason());
    assertEquals(Optional.of(TRAIN), cancelled.trainName());
    assertEquals("stuck-cleanup", cancelled.detail());
    assertEquals(Optional.of(cancelled), service.cancellationOf(TIMETABLE, TRIP_0800, DATE));
  }

  /** 发车后才算停完：离开 BBB 之后只剩终点 CCC。 */
  @Test
  void departureMovesTheCancellationPastThatStop() {
    assign(TRAIN, EIGHT);
    service.observeStop(event(TRAIN, ROUTE, 0), true);
    service.observeStop(event(TRAIN, ROUTE, 2), true);
    service.observeStop(event(TRAIN, ROUTE, 0), true);

    service.release(TRAIN, "destroyed");

    assertEquals(3, onlyHeard().firstCancelledStopSequence(), "迟到的前一站事件不往回退");
  }

  /** 到了终点站就是跑完了，之后在终点被销毁、回收都不是取消。 */
  @Test
  void arrivalAtTheTerminatingStopCompletesTheTrip() {
    assign(TRAIN, EIGHT);
    service.observeStop(event(TRAIN, ROUTE, 0), true);
    service.observeStop(event(TRAIN, ROUTE, 2), true);
    service.observeStop(event(TRAIN, ROUTE, 3), false);

    service.release(TRAIN, "DSTY");

    assertTrue(heard.isEmpty(), () -> heard.toString());
  }

  /** 起点还没开出车就没了：整趟取消。 */
  @Test
  void releasedBeforeLeavingTheOriginIsAFullCancellation() {
    assign(TRAIN, EIGHT);

    service.release(TRAIN, "train-removed");

    Cancellation cancelled = onlyHeard();
    assertEquals(Scope.FULL, cancelled.scope());
    assertEquals(0, cancelled.firstCancelledStopSequence());
    assertEquals(Reason.VEHICLE_REMOVED, cancelled.reason());
  }

  /** 改派到别的交路之后的停靠不算旧车次的：别的交路上"到终点"不能把这趟车记成跑完。 */
  @Test
  void stopsOnAnotherRouteDoNotCountForTheAssignedTrip() {
    assign(TRAIN, EIGHT);
    service.observeStop(event(TRAIN, ROUTE, 0), true);
    service.observeStop(event(TRAIN, OTHER_ROUTE, 3), false);
    service.observeStop(event(TRAIN, OTHER_ROUTE, 2), true);

    service.release(TRAIN, "destroyed");

    assertEquals(2, onlyHeard().firstCancelledStopSequence());
  }

  /** 定时 retain 清掉的车（改名、离线）不知道去向，不登记取消。 */
  @Test
  void trainGoneFromRetainIsNotACancellation() {
    assign(TRAIN, EIGHT);

    service.retain(List.of("someone-else"));

    assertTrue(heard.isEmpty(), () -> heard.toString());
    assertTrue(service.assignmentOf(TRAIN).isEmpty());
  }

  /** 取消之后又有车接上这趟车：站牌恢复正常，不另发事件。 */
  @Test
  void anotherTrainTakingTheTripRevokesTheCancellation() {
    assign(TRAIN, EIGHT);
    service.release(TRAIN, "train-removed");
    assertTrue(service.cancellationOf(TIMETABLE, TRIP_0800, DATE).isPresent());

    assign("SURC-MT-LP-0366", EIGHT.plusSeconds(30));

    assertTrue(service.cancellationOf(TIMETABLE, TRIP_0800, DATE).isEmpty());
    assertEquals(1, heard.size());
  }

  /** 到点没派出车是整趟取消；这趟车已经有车绑着时不算。同一趟只登记一次。 */
  @Test
  void undispatchedTripIsAFullCancellationUnlessAlreadyClaimed() {
    assign(TRAIN, EIGHT);
    List<TimetableService.DueTrip> due =
        service.tripsBetween(EIGHT.minusSeconds(1), EIGHT.plusSeconds(600));
    assertEquals(List.of(TRIP_0800, TRIP_0810), due.stream().map(d -> d.trip().id()).toList());

    service.cancelUndispatched(due.get(0), "ticket-abandoned");
    service.cancelUndispatched(due.get(1), "ticket-abandoned");
    service.cancelUndispatched(due.get(1), "ticket-abandoned");

    Cancellation cancelled = onlyHeard();
    assertEquals(TRIP_0810, cancelled.tripId());
    assertEquals(Scope.FULL, cancelled.scope());
    assertEquals(Reason.NOT_DISPATCHED, cancelled.reason());
    assertEquals(0, cancelled.firstCancelledStopSequence());
    assertTrue(cancelled.trainName().isEmpty());
  }

  /** 追补上限之外的车次不再出票，就此取消；上限之内的照常出票。 */
  @Test
  void tripsBeyondTheCatchUpLimitAreCancelled() {
    List<TimetableService.DueTrip> due =
        service.dueTrips(EIGHT.minusSeconds(1), EIGHT.plusSeconds(20 * 60 + 60));

    assertEquals(List.of(TRIP_0820), due.stream().map(d -> d.trip().id()).toList());
    assertEquals(List.of(TRIP_0800, TRIP_0810), heard.stream().map(Cancellation::tripId).toList());
    assertTrue(heard.stream().allMatch(c -> c.detail().equals("catch-up-limit")));
  }

  /** 下架的时刻表连同它的取消一起清掉；关掉按表运行也清空。 */
  @Test
  void unpublishingOrDisablingDropsCancellations() {
    assign(TRAIN, EIGHT);
    service.release(TRAIN, "train-removed");

    service.reload(providerWith());
    assertTrue(service.cancellationOf(TIMETABLE, TRIP_0800, DATE).isEmpty());

    service.reload(providerWith(timetable()));
    assign(TRAIN, EIGHT);
    service.release(TRAIN, "train-removed");
    assertTrue(service.cancellationOf(TIMETABLE, TRIP_0800, DATE).isPresent());
    service.applySettings(TimetableService.Settings.disabled());
    assertTrue(service.cancellationOf(TIMETABLE, TRIP_0800, DATE).isEmpty());
  }

  /** 终点是第一个 TERMINATE 站，没有时为最后一个停车点；其后的通过点、区间点都不算停车站。 */
  @Test
  void planKnowsItsTerminatingStopAndTheNextStop() {
    TimetableRoutePlan plan = timetable().routePlan(ROUTE).orElseThrow();

    assertEquals(OptionalInt.of(3), plan.terminatingSequence());
    assertEquals(OptionalInt.of(0), plan.firstStopAfter(-1));
    assertEquals(OptionalInt.of(2), plan.firstStopAfter(0));
    assertEquals(OptionalInt.empty(), plan.firstStopAfter(3));

    TimetableRoutePlan terminated =
        new TimetableRoutePlan(
            ROUTE,
            "R1",
            1,
            List.of(
                stop(0, "AAA", RouteStopPassType.STOP),
                stop(1, "BBB", RouteStopPassType.TERMINATE),
                stop(2, "CCC", RouteStopPassType.STOP)),
            "OP:S:AAA:1",
            "OP:S:CCC:1",
            Optional.empty(),
            Optional.empty());
    assertEquals(OptionalInt.of(1), terminated.terminatingSequence());
    assertEquals(OptionalInt.empty(), terminated.firstStopAfter(1));
    assertFalse(
        new TimetableRoutePlan(
                ROUTE,
                "R1",
                1,
                List.of(stop(0, "AAA", RouteStopPassType.PASS)),
                "OP:S:AAA:1",
                "OP:S:AAA:1",
                Optional.empty(),
                Optional.empty())
            .terminatingSequence()
            .isPresent());
  }

  private Cancellation onlyHeard() {
    assertEquals(1, heard.size(), () -> heard + " logs=" + logs);
    return heard.get(0);
  }

  /** 列车在起点门控上问发车时刻，同时绑定到最近的那趟车。 */
  private void assign(String train, Instant at) {
    assertTrue(
        service
            .scheduledDepartureAt(
                new StationStopEvent(train, Optional.of(ROUTE), "R1", 0, 5, "OP:S:AAA:1", at))
            .isPresent(),
        () -> "未绑定: " + logs);
  }

  private static StationStopEvent event(String train, UUID route, int index) {
    return new StationStopEvent(
        train, Optional.of(route), "R", index, 5, "node-" + index, EIGHT.plusSeconds(60));
  }

  private TimetableService service() {
    TimetableService created = new TimetableService(() -> EIGHT, logs::add);
    created.applySettings(
        new TimetableService.Settings(
            true, true, Duration.ofSeconds(120), Duration.ofSeconds(300), Duration.ofSeconds(300)));
    created.reload(providerWith(timetable()));
    created.setCancellationListener(heard::add);
    return created;
  }

  private static StorageProvider providerWith(Timetable... published) {
    StorageProvider provider = mock(StorageProvider.class);
    TimetableRepository repository = mock(TimetableRepository.class);
    when(provider.timetables()).thenReturn(repository);
    when(repository.listPublished()).thenReturn(List.of(published));
    return provider;
  }

  private static TimetableStop stop(int sequence, String station, RouteStopPassType passType) {
    return new TimetableStop(
        sequence,
        Optional.ofNullable(station),
        Optional.of(station == null ? "OP:MID:" + sequence : "OP:S:" + station + ":1"),
        sequence * 100,
        sequence * 100,
        passType);
  }

  private static Timetable timetable() {
    return new Timetable(
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
                List.of(
                    stop(0, "AAA", RouteStopPassType.STOP),
                    stop(1, null, RouteStopPassType.PASS),
                    stop(2, "BBB", RouteStopPassType.STOP),
                    stop(3, "CCC", RouteStopPassType.STOP),
                    stop(4, null, RouteStopPassType.PASS)),
                "OP:S:AAA:1",
                "OP:MID:4",
                Optional.empty(),
                Optional.empty())),
        List.of(
            new TimetableTrip(TRIP_0800, TIMETABLE, ROUTE, 0, "R1-001", 8 * 3600, Optional.empty()),
            new TimetableTrip(
                TRIP_0810, TIMETABLE, ROUTE, 1, "R1-002", 8 * 3600 + 600, Optional.empty()),
            new TimetableTrip(
                TRIP_0820, TIMETABLE, ROUTE, 2, "R1-003", 8 * 3600 + 1200, Optional.empty())),
        List.of(),
        Optional.empty(),
        EIGHT,
        EIGHT);
  }
}
