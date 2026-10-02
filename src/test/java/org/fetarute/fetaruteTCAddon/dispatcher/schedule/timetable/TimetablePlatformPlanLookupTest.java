package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.CREATE_ROUTE;
import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.ROUTE;
import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.providerWith;
import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.timetable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.Test;

/**
 * 计划站台的读取：运行中的车按车次、跑出库走行的车按首班始发、票据按交路意图。
 *
 * <p>夹具是一个三班的交路（AAA → BBB → CCC），出库走行 DEP → AAA。计划：首班始发 AAA:2、首班 BBB:2、第二班 BBB:1。
 */
class TimetablePlatformPlanLookupTest {

  private static final Instant T0 = Instant.parse("2026-03-02T08:00:02Z");

  private final TimetableService service = new TimetableService(() -> T0, message -> {});
  private Timetable table;
  private TimetableService.DutyKey duty;

  private void start(boolean enabled) {
    service.applySettings(
        new TimetableService.Settings(
            enabled,
            enabled,
            Duration.ofSeconds(120),
            Duration.ofSeconds(300),
            Duration.ofSeconds(300)));
    table = timetable(TimetableStatus.PUBLISHED, 3);
    List<UUID> trips = table.duties().get(0).tripIds();
    StorageProvider provider = providerWith(table);
    when(provider.timetables().listPlatformPlans(table.id()))
        .thenReturn(
            List.of(
                new PlatformPlan(trips.get(0), 0, "OP:S:AAA:2"),
                new PlatformPlan(trips.get(0), 1, "OP:S:BBB:2"),
                new PlatformPlan(trips.get(1), 1, "OP:S:BBB:1")));
    service.reload(provider);
    duty =
        new TimetableService.DutyKey(
            table.id(), table.duties().get(0).id(), LocalDate.parse("2026-03-02"));
  }

  private TimetableService.TicketIntent intent(RouteOperationType kind, int tripIndex) {
    return new TimetableService.TicketIntent(
        duty.timetableId(), duty.dutyId(), duty.serviceDate(), kind, tripIndex);
  }

  private TimetableService.DueTrip trip(int index) {
    TimetableTrip trip = table.trip(table.duties().get(0).tripIds().get(index)).orElseThrow();
    return new TimetableService.DueTrip(
        table, trip, duty.serviceDate(), table.departureOnServiceDay(trip, duty.serviceDate()));
  }

  /** 绑着首班的车：按首班的计划；没有计划的停靠为空。 */
  @Test
  void aRunningTrainReadsThePlanOfItsTrip() {
    start(true);
    service.bindDuty("train-A", duty, "ticket-operation");
    service.bindDispatchedTrip(
        "train-A", intent(RouteOperationType.OPERATION, 0), Optional.of(trip(0)));

    assertEquals(Optional.of("OP:S:BBB:2"), service.plannedPlatformOf("train-A", ROUTE, 1));
    assertEquals(Optional.empty(), service.plannedPlatformOf("train-A", ROUTE, 2));
    assertEquals(
        Optional.empty(),
        service.plannedPlatformOf("train-A", CREATE_ROUTE, 1),
        "车已经在跑首班，不再按出库走行查");
  }

  /** 出库走行没有车次：走行终点按首班始发的计划，走行的其他停靠没有计划。 */
  @Test
  void aCreateLegFollowsTheFirstTripOrigin() {
    start(true);
    service.bindDuty("train-A", duty, "ticket-create");

    assertEquals(Optional.of("OP:S:AAA:2"), service.plannedPlatformOf("train-A", CREATE_ROUTE, 1));
    assertEquals(Optional.empty(), service.plannedPlatformOf("train-A", CREATE_ROUTE, 0));
  }

  /** 票据按交路意图找车次：运营票按交路里的第几班，出库票按首班始发。 */
  @Test
  void ticketsReadThePlanThroughTheirIntent() {
    start(true);

    assertEquals(
        Optional.of("OP:S:BBB:1"),
        service.plannedPlatform(intent(RouteOperationType.OPERATION, 1), 1));
    assertEquals(
        Optional.of("OP:S:AAA:2"),
        service.plannedPlatform(intent(RouteOperationType.CREATE, 0), 1));
    assertEquals(
        Optional.empty(), service.plannedPlatform(intent(RouteOperationType.OPERATION, 2), 1));
  }

  /** 按表运行关闭时一律没有计划。 */
  @Test
  void nothingIsPlannedWhenTimetablesAreDisabled() {
    start(false);

    assertEquals(
        Optional.empty(), service.plannedPlatform(intent(RouteOperationType.OPERATION, 1), 1));
    assertEquals(Optional.empty(), service.plannedPlatformOf("train-A", ROUTE, 1));
  }
}
