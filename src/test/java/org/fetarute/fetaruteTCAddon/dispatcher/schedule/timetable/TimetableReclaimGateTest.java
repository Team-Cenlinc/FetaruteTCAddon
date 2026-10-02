package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.providerWith;
import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.timetable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.junit.jupiter.api.Test;

/**
 * 跑完交路的车按表还有一班带客回库：回收不抢在它前面把车带走，回收时先走它那条回库线路；站牌上的下一趟与之一致。
 *
 * <p>夹具是一个一班的交路（08:00 发车），带客回库班 08:05:50 从终点开回车库，发车容差 5 分钟。
 */
class TimetableReclaimGateTest {

  private static final Instant T0 = Instant.parse("2026-03-02T08:00:02Z");

  private final AtomicReference<Instant> clock = new AtomicReference<>(T0);
  private final TimetableService service = new TimetableService(clock::get, message -> {});
  private UUID returnRoute;
  private TimetableService.DutyKey duty;

  private void start() {
    Timetable base = timetable(TimetableStatus.PUBLISHED, 1);
    VehicleDuty vehicleDuty = base.duties().get(0);
    returnRoute = vehicleDuty.returnRouteId().orElseThrow();
    List<TimetableTrip> trips = new ArrayList<>(base.trips());
    trips.add(
        new TimetableTrip(
            UUID.randomUUID(),
            base.id(),
            returnRoute,
            1,
            "R1-RET",
            8 * 3600 + 350,
            Optional.of(vehicleDuty.id())));
    Timetable table =
        new Timetable(
            base.id(),
            base.companyId(),
            base.operatorId(),
            base.lineId(),
            base.code(),
            base.name(),
            base.status(),
            base.zoneId(),
            base.serviceStartSecondOfDay(),
            base.serviceEndSecondOfDay(),
            base.routePlans(),
            trips,
            base.duties(),
            base.notes(),
            base.createdAt(),
            base.updatedAt());
    service.applySettings(
        new TimetableService.Settings(
            true, true, Duration.ofSeconds(120), Duration.ofSeconds(300), Duration.ofSeconds(300)));
    service.reload(providerWith(table));
    duty =
        new TimetableService.DutyKey(table.id(), vehicleDuty.id(), LocalDate.parse("2026-03-02"));
    TimetableTrip first = table.trip(vehicleDuty.tripIds().get(0)).orElseThrow();
    service.bindDuty("train-A", duty, "ticket-operation");
    service.bindDispatchedTrip(
        "train-A",
        new TimetableService.TicketIntent(
            duty.timetableId(), duty.dutyId(), duty.serviceDate(), RouteOperationType.OPERATION, 0),
        Optional.of(
            new TimetableService.DueTrip(
                table,
                first,
                duty.serviceDate(),
                table.departureOnServiceDay(first, duty.serviceDate()))));
  }

  /** 停在回库班起点站（CCC）的车等回库班；回库班过了容差、或车停在别的站（票接不到它）时不等。 */
  @Test
  void reclaimWaitsForTheOwnReturnLegOnlyAtItsOrigin() {
    start();

    assertTrue(service.allowsReturn("train-A"), "交路跑完了：回库票可以带走它");
    assertTrue(service.awaitsOwnReturnAt("train-A", "OP:S:CCC:2"), "停在回库班起点站：回收不抢在它前面");
    assertFalse(service.awaitsOwnReturnAt("train-A", "OP:S:AAA:1"), "停在别的站：回库票接不到它，不等");
    assertEquals(
        "R1-RET",
        service.nextDepartureOf("train-A").orElseThrow().trip().tripCode(),
        "站牌上的下一趟就是回库班");
    assertEquals(Optional.of(returnRoute), service.returnRouteOf("train-A"));

    clock.set(Instant.parse("2026-03-02T08:10:51Z"));

    assertFalse(service.awaitsOwnReturnAt("train-A", "OP:S:CCC:2"), "回库班过了容差还没开成：照常回收");
    assertEquals(Optional.empty(), service.nextDepartureOf("train-A"), "回库班不会再出票：站牌不再写它");

    service.setPendingTicketProbe(intent -> intent.kind() == RouteOperationType.RETURN);
    assertEquals(
        "R1-RET",
        service.nextDepartureOf("train-A").orElseThrow().trip().tripCode(),
        "回库票还在等这辆车：站牌照旧是它");
    assertFalse(service.awaitsOwnReturnAt("train-A", "OP:S:CCC:2"), "回收的等待只认时刻，不认票");
  }

  /** 回收沿交路自己的回库线路派走：算跑了回库班，回库票不再等它，站牌不再列它。 */
  @Test
  void reclaimAlongTheOwnReturnRouteRunsTheReturnLeg() {
    start();
    TimetableService.TicketIntent returnLeg =
        new TimetableService.TicketIntent(
            duty.timetableId(), duty.dutyId(), duty.serviceDate(), RouteOperationType.RETURN, 0);
    assertEquals(Optional.of("train-A"), service.awaitedVehicle(returnLeg));

    service.reclaimed("train-A", returnRoute);

    assertEquals(Optional.empty(), service.awaitedVehicle(returnLeg), "回库票不再空等");
    assertEquals(Optional.empty(), service.nextDepartureOf("train-A"));
    assertEquals("R1-RET", service.assignmentOf("train-A").orElseThrow().tripCode());
  }

  /** 回收走了别的回库线路：车离开交路，回库票不再等它。 */
  @Test
  void reclaimAlongAnotherRouteReleasesTheDuty() {
    start();
    TimetableService.TicketIntent returnLeg =
        new TimetableService.TicketIntent(
            duty.timetableId(), duty.dutyId(), duty.serviceDate(), RouteOperationType.RETURN, 0);

    service.reclaimed("train-A", UUID.randomUUID());

    assertEquals(Optional.empty(), service.dutyBindingOf("train-A"));
    assertEquals(Optional.empty(), service.awaitedVehicle(returnLeg));
    assertEquals(
        "R1-001", service.assignmentOf("train-A").orElseThrow().tripCode(), "跑完的那一趟照旧算跑完，不按半路离开取消");
  }
}
