package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.event;
import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.providerWith;
import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.timetable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.junit.jupiter.api.Test;

/**
 * 派车即绑车次、终点上的下一趟、续班票等的车。
 *
 * <p>夹具是一个三班的交路（08:00、08:10、08:20 从 AAA 发车）。折返复用的车在起点不经过门控，派车回调就是它在起点唯一的入口。
 */
class TimetableDispatchBindingTest {

  private static final Instant T0 = Instant.parse("2026-03-02T08:00:02Z");

  private final AtomicReference<Instant> clock = new AtomicReference<>(T0);
  private final TimetableService service = new TimetableService(clock::get, message -> {});
  private Timetable table;
  private TimetableService.DutyKey duty;

  private void start() {
    service.applySettings(
        new TimetableService.Settings(
            true, true, Duration.ofSeconds(120), Duration.ofSeconds(300), Duration.ofSeconds(300)));
    table = timetable(TimetableStatus.PUBLISHED, 3);
    service.reload(providerWith(table));
    duty =
        new TimetableService.DutyKey(
            table.id(), table.duties().get(0).id(), LocalDate.parse("2026-03-02"));
  }

  private TimetableService.DueTrip trip(int index) {
    TimetableTrip trip = table.trip(table.duties().get(0).tripIds().get(index)).orElseThrow();
    Instant departure = table.departureOnServiceDay(trip, duty.serviceDate());
    return new TimetableService.DueTrip(table, trip, duty.serviceDate(), departure);
  }

  private TimetableService.TicketIntent intent(int tripIndex) {
    return new TimetableService.TicketIntent(
        duty.timetableId(),
        duty.dutyId(),
        duty.serviceDate(),
        RouteOperationType.OPERATION,
        tripIndex);
  }

  /** 派出就在起点绑上那一班，第一个中途站的门控沿用它，不再按时间重新匹配。 */
  @Test
  void aDispatchedTripIsBoundAtTheOrigin() {
    start();
    service.bindDuty("train-A", duty, "ticket-operation");

    service.bindDispatchedTrip("train-A", intent(0), Optional.of(trip(0)));

    TimetableAssignment assignment = service.assignmentOf("train-A").orElseThrow();
    assertEquals("R1-001", assignment.tripCode());
    assertEquals(0, assignment.assignedAtStopIndex());
    assertEquals(2, assignment.initialDeviationSeconds());
    assertTrue(service.plannedDepartureOf("train-A", 0).isPresent(), "首段就有计划时刻");

    clock.set(T0.plusSeconds(110));
    service.scheduledDepartureAt(event("train-A", 1, clock.get()));
    assertEquals(assignment, service.assignmentOf("train-A").orElseThrow());
  }

  /** 车跑着第一班时，下一趟就是交路的第二班；跑完最后一班、交路没有带客回库班时没有下一趟。 */
  @Test
  void theNextDepartureFollowsTheDuty() {
    start();
    service.bindDuty("train-A", duty, "ticket-operation");
    service.bindDispatchedTrip("train-A", intent(0), Optional.of(trip(0)));

    TimetableService.DueTrip next = service.nextDepartureOf("train-A").orElseThrow();
    assertEquals("R1-002", next.trip().tripCode());
    assertEquals(Instant.parse("2026-03-02T08:10:00Z"), next.departure());

    clock.set(Instant.parse("2026-03-02T08:20:01Z"));
    service.bindDispatchedTrip("train-A", intent(2), Optional.of(trip(2)));
    assertEquals(Optional.empty(), service.nextDepartureOf("train-A"));
  }

  /**
   * 表从半路生效（开服、刚发布）时，车按时间绑到的那一班之后常有几班早已过了时刻：过了发车容差、票也不在了的那一班不会再出票，
   * 车接的是后面那一班，站牌上的下一趟也要跳过它；票还在等这辆车时照旧是那一班（晚点就晚发）。
   */
  @Test
  void aTripThatWillNotRunIsSkipped() {
    start();
    service.bindDuty("train-A", duty, "ticket-operation");
    service.bindDispatchedTrip("train-A", intent(0), Optional.of(trip(0)));
    clock.set(Instant.parse("2026-03-02T08:15:01Z"));

    assertEquals(
        "R1-003",
        service.nextDepartureOf("train-A").orElseThrow().trip().tripCode(),
        "08:10 那一班过了 5 分钟容差、没有票：接 08:20 那一班");

    service.setPendingTicketProbe(intent(1)::equals);
    assertEquals(
        "R1-002",
        service.nextDepartureOf("train-A").orElseThrow().trip().tripCode(),
        "08:10 那一班的票还在等这辆车：照旧是它");
  }

  /** 已取消的班次不会开：下一趟跳过它，取后面那一班。 */
  @Test
  void aCancelledTripIsSkipped() {
    start();
    service.bindDuty("train-A", duty, "ticket-operation");
    service.bindDispatchedTrip("train-A", intent(0), Optional.of(trip(0)));

    service.cancelUndispatched(trip(1), "ticket-abandoned");

    assertEquals("R1-003", service.nextDepartureOf("train-A").orElseThrow().trip().tripCode());
  }

  /** 续班票等的是本交路的车；车接下那一班以后，那一班就不再等它。 */
  @Test
  void aContinuationTicketAwaitsTheDutyVehicleUntilItTakesTheTrip() {
    start();
    service.bindDuty("train-A", duty, "ticket-operation");
    service.bindDispatchedTrip("train-A", intent(0), Optional.of(trip(0)));

    assertEquals(Optional.of("train-A"), service.awaitedVehicle(intent(1)));
    assertEquals(Optional.empty(), service.awaitedVehicle(intent(0)), "首班不是续班");

    clock.set(Instant.parse("2026-03-02T08:10:01Z"));
    service.bindDispatchedTrip("train-A", intent(1), Optional.of(trip(1)));
    assertEquals(Optional.empty(), service.awaitedVehicle(intent(1)));
    assertEquals(Optional.of("train-A"), service.awaitedVehicle(intent(2)));
  }

  /** 那一班已被别的车占着：不抢，留给门控照常匹配。 */
  @Test
  void aTripHeldByAnotherTrainIsNotTaken() {
    start();
    service.scheduledDepartureAt(event("train-B", 0, T0));

    service.bindDispatchedTrip("train-A", intent(0), Optional.of(trip(0)));

    assertEquals(Optional.empty(), service.assignmentOf("train-A"));
    assertEquals(
        List.of("train-B"),
        service.assignments().stream().map(TimetableAssignment::trainName).toList());
  }
}
