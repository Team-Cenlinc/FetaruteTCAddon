package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.ROUTE;
import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.event;
import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.providerWith;
import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.timetable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.junit.jupiter.api.Test;

/**
 * 续班票等本交路的车：车还在路上就不按时刻作废，晚点就晚发；回库闸据此区分"续班接不上"与"续班的票还在等这辆车"。
 *
 * <p>夹具是一个三班的交路 D001（08:00、08:10、08:20 从 AAA 发车），发车容差 300 秒；train-A 在 08:00:05 接下首班。 出票侧的"票还在等车"由
 * {@link #pending} 模拟。
 */
class TimetableContinuationTicketTest {

  private static final Instant T0 = Instant.parse("2026-03-02T08:00:05Z");

  private final AtomicReference<Instant> clock = new AtomicReference<>(T0);
  private final List<String> logs = new ArrayList<>();
  private final TimetableService service = new TimetableService(clock::get, logs::add);
  private final Set<TimetableService.TicketIntent> pending = new HashSet<>();

  private void start(boolean spawnEnabled) {
    service.applySettings(
        new TimetableService.Settings(
            true,
            spawnEnabled,
            Duration.ofSeconds(120),
            Duration.ofSeconds(300),
            Duration.ofSeconds(300)));
    service.reload(providerWith(timetable(TimetableStatus.PUBLISHED, 3)));
    service.setPendingTicketProbe(pending::contains);
    service.scheduledDepartureAt(event("train-A", 0, T0));
  }

  private TimetableService.TicketIntent intent(RouteOperationType kind, int tripIndex) {
    TimetableService.DutyKey duty = service.dutyBindingOf("train-A").orElseThrow();
    return new TimetableService.TicketIntent(
        duty.timetableId(), duty.dutyId(), duty.serviceDate(), kind, tripIndex);
  }

  private void at(String time) {
    clock.set(Instant.parse("2026-03-02T" + time + "Z"));
  }

  /** 第二班 08:10 过了容差（08:15）车还没到：它的票、后面的票与回库票都在等这辆车；首班与出库票不是续班。 */
  @Test
  void aLateVehiclesRemainingTicketsKeepWaitingForIt() {
    start(true);
    at("08:16:00");

    assertTrue(service.awaitsOwnVehicle(intent(RouteOperationType.OPERATION, 1)));
    assertTrue(service.awaitsOwnVehicle(intent(RouteOperationType.OPERATION, 2)));
    assertTrue(service.awaitsOwnVehicle(intent(RouteOperationType.RETURN, 0)));
    assertFalse(service.awaitsOwnVehicle(intent(RouteOperationType.OPERATION, 0)), "首班不是续班");
    assertFalse(service.awaitsOwnVehicle(intent(RouteOperationType.CREATE, 0)), "出库票新出一辆车");
    assertFalse(service.awaitsOwnVehicle(null));
  }

  /** 车已经接下的班次不再算等它：回到起点接下第二班之后，等它的是第三班。 */
  @Test
  void aTripTheVehicleAlreadyTookIsNoLongerAwaited() {
    start(true);
    at("08:10:05");
    service.scheduledDepartureAt(event("train-A", 0, clock.get()));

    assertFalse(service.awaitsOwnVehicle(intent(RouteOperationType.OPERATION, 1)));
    assertTrue(service.awaitsOwnVehicle(intent(RouteOperationType.OPERATION, 2)));
  }

  /**
   * 第二班过了容差、票还在等这辆车：不换车，它晚点把这一班跑掉。票不在了（例如等满了上限被放弃）才换，换下之后交路不再归它。
   *
   * <p>替补车赶得上第三班（出库线路走行 + 就绪 180 秒）；只看时刻的话，换车会让第二班的票立即作废，乘客少一班车。
   */
  @Test
  void aWaitingTicketKeepsTheLateVehicleFromBeingReplaced() {
    start(true);
    TimetableService.TicketIntent second = intent(RouteOperationType.OPERATION, 1);
    pending.add(second);
    at("08:15:30");

    service.replacementsDue(clock.get());

    assertFalse(service.retiredFromDuty("train-A"));
    assertTrue(service.awaitsOwnVehicle(second));

    pending.clear();
    service.replacementsDue(clock.get());

    assertTrue(service.retiredFromDuty("train-A"));
    assertFalse(service.awaitsOwnVehicle(second));
  }

  /** 车在终点等下一班时回库闸会被反复问到：同一进度只记一次拒绝。 */
  @Test
  void aRepeatedReturnDenialIsLoggedOnce() {
    start(true);

    assertFalse(service.allowsReturn("train-A"));
    assertFalse(service.allowsReturn("train-A"));

    assertEquals(
        1,
        logs.stream()
            .filter(line -> line.startsWith("TIMETABLE_RETURN_DENIED train=train-A"))
            .count(),
        logs::toString);
  }

  /** 只扣车不出票时没有续班票：按时刻作废的老规则不变。 */
  @Test
  void withoutTimetableSpawningNothingIsAwaited() {
    start(false);
    at("08:16:00");

    assertFalse(service.awaitsOwnVehicle(intent(RouteOperationType.OPERATION, 1)));
  }

  /**
   * 剩下的班次都过了容差，但第二班的票还在等这辆车：交路没有断，回库闸不放行；票不在了才放行。
   *
   * <p>以前只看时刻：车晚点到终点、自己的票还在等它，回库闸却先放行，原地折返的车站上它就会被当作无班可接处理掉。
   */
  @Test
  void aWaitingTicketKeepsTheVehicleOnItsDuty() {
    start(true);
    TimetableService.TicketIntent second = intent(RouteOperationType.OPERATION, 1);
    pending.add(second);
    at("08:26:00");

    assertFalse(service.allowsReturn("train-A"));

    pending.clear();

    assertTrue(service.allowsReturn("train-A"));
    assertTrue(
        logs.stream()
            .anyMatch(line -> line.startsWith("TIMETABLE_DUTY_CONTINUATION_LOST train=train-A")),
        logs::toString);
  }

  /** 折返点的立即回收闸同理：下一班过了容差、票还在等这辆车时不算接不上。 */
  @Test
  void aWaitingNextTicketKeepsTheTurnbackGateClosed() {
    start(true);
    TimetableService.TicketIntent second = intent(RouteOperationType.OPERATION, 1);
    pending.add(second);
    at("08:15:30");

    assertFalse(service.allowsReturnFromMainlineTurnback("train-A", Optional.of(ROUTE)));

    pending.clear();

    assertTrue(service.allowsReturnFromMainlineTurnback("train-A", Optional.of(ROUTE)));
    assertTrue(
        logs.stream()
            .anyMatch(line -> line.startsWith("TIMETABLE_DUTY_NEXT_TRIP_MISSED train=train-A")),
        logs::toString);
  }
}
