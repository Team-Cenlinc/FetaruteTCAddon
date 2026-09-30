package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.CREATE_ROUTE;
import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.ZONE;
import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.event;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopObserver;
import org.junit.jupiter.api.Test;

/**
 * 交路换车：严重晚点、接不上下一班的车从交路上换下来，替补车从车库出来接它还赶得上的班次。
 *
 * <p>夹具是一个三班的交路 D001（08:00、08:10、08:20 从 AAA 发车），出库线路 DEP → AAA 走行 60 秒、就绪 120 秒， 发车容差 300 秒。
 */
class TimetableDutyReplacementTest {

  private static final Instant T0 = Instant.parse("2026-03-02T08:00:05Z");

  private final AtomicReference<Instant> clock = new AtomicReference<>(T0);
  private final List<String> logs = new ArrayList<>();
  private final TimetableService service = new TimetableService(clock::get, logs::add);

  private void start(Timetable published) {
    service.applySettings(
        new TimetableService.Settings(
            true,
            true,
            Duration.ofSeconds(120),
            Duration.ofSeconds(300),
            Duration.ofSeconds(300),
            ZONE));
    service.reload(providerWith(published));
    service.scheduledDepartureAt(event("train-A", 0, T0));
  }

  private TimetableService.DutyKey dutyOfTrainA() {
    return service.dutyBindingOf("train-A").orElseThrow();
  }

  private List<TimetableService.Replacement> at(String time) {
    clock.set(Instant.parse("2026-03-02T" + time + "Z"));
    return service.replacementsDue(clock.get());
  }

  /**
   * 第二班 08:10 过了容差（08:15）还没接上：替补车赶得上第三班（08:20 发，08:17 出库，走行 + 就绪 180 秒），于是把 train-A 换下来。 08:17
   * 发出替补出库票，只发一张；替补车出库即绑上交路，空缺填上，train-A 再也绑不回去。
   */
  @Test
  void aVehicleThatMissedItsNextTripIsReplacedForTheNextReachableOne() {
    start(timetable(TimetableStatus.PUBLISHED, 3));
    TimetableService.DutyKey duty = dutyOfTrainA();

    assertTrue(at("08:15:30").isEmpty(), "替补车 08:17 才需要出库");
    assertTrue(service.dutyBindingOf("train-A").isEmpty(), "train-A 已从交路上换下");
    assertTrue(service.retiredFromDuty("train-A"));
    assertTrue(service.allowsReturn("train-A"), "换下来的车没有交路可守，回库闸放行");
    assertEquals(1, service.vacantDutyCount());
    assertTrue(
        logs.stream()
            .anyMatch(
                line ->
                    line.startsWith("TIMETABLE_DUTY_VACATED train=train-A")
                        && line.contains("reason=next-trip-missed")),
        logs::toString);

    assertTrue(
        service.scheduledDepartureAt(event("train-A", 0, clock.get())).isEmpty(),
        "换下来的车在门控上按时间也匹配不回原交路（第三班 08:20 在容差内）");
    assertTrue(service.dutyBindingOf("train-A").isEmpty());

    List<TimetableService.Replacement> issued = at("08:17:00");
    assertEquals(1, issued.size());
    TimetableService.Replacement replacement = issued.get(0);
    assertEquals(2, replacement.tripIndex());
    assertEquals(CREATE_ROUTE, replacement.createRouteId());
    assertEquals(Instant.parse("2026-03-02T08:20:00Z"), replacement.departure());
    assertEquals(RouteOperationType.CREATE, replacement.leg(clock.get()).kind());
    assertEquals(
        Instant.parse("2026-03-02T08:22:00Z"),
        replacement.latestIssue(),
        "最晚 08:22 出库：+180 秒到站，正好卡在 08:25 的容差截止");
    assertTrue(at("08:17:05").isEmpty(), "出库票在路上，不再派第二辆");

    service.bindDuty("train-B", duty, "ticket-create");
    assertEquals(0, service.vacantDutyCount(), "替补车绑上，空缺填上");
    assertTrue(
        logs.stream().anyMatch(line -> line.startsWith("TIMETABLE_DUTY_REPLACED train=train-B")));

    service.bindDuty("train-A", duty, "trip-assigned");
    assertEquals(duty, service.dutyBindingOf("train-B").orElseThrow());
    assertTrue(service.dutyBindingOf("train-A").isEmpty(), "换下来的车不许绑回去");
    TimetableService.TicketIntent third =
        new TimetableService.TicketIntent(
            duty.timetableId(), duty.dutyId(), duty.serviceDate(), RouteOperationType.OPERATION, 2);
    assertTrue(service.acceptsVehicle(third, "train-B"));
    assertFalse(service.acceptsVehicle(third, "train-A"));
  }

  /** 表里没有能把车送到那一班起点的出库线路：替补车赶不上，就不换——换了只会让原车连后面还接得上的班次也跑不了。 */
  @Test
  void aVehicleIsKeptWhenNoReplacementCanMakeIt() {
    start(withoutCreateRoutes(timetable(TimetableStatus.PUBLISHED, 3)));

    assertTrue(at("08:15:30").isEmpty());
    assertTrue(service.dutyBindingOf("train-A").isPresent(), "没有替补，原车照旧留在交路上");
    assertEquals(0, service.vacantDutyCount());
  }

  /**
   * 严重晚点提前换车：B 站计划 08:01:40 到、实际 08:09:00，晚 440 秒，超过容差 + 120 秒的余量——追赶也赶不上下一班。 替补车现在出库（08:09 + 180 =
   * 08:12）赶得上第二班（08:10 发、08:15 前），于是这一刻就换车，不等第二班作废。
   */
  @Test
  void aHopelesslyLateVehicleIsReplacedBeforeItsNextTripExpires() {
    start(timetable(TimetableStatus.PUBLISHED, 3));

    clock.set(Instant.parse("2026-03-02T08:09:00Z"));
    service.observeStop(event("train-A", 1, clock.get()), false);

    assertTrue(service.dutyBindingOf("train-A").isEmpty());
    assertTrue(
        logs.stream()
            .anyMatch(
                line ->
                    line.startsWith("TIMETABLE_DUTY_VACATED train=train-A")
                        && line.contains("reason=late-440s")
                        && line.contains("fromTrip=1")),
        logs::toString);
    assertEquals(1, at("08:09:00").get(0).tripIndex(), "替补车接第二班");
  }

  /** 晚点在追赶余量以内（容差 300 + 120 秒）不换：停站压缩与超速还追得回来。 */
  @Test
  void aModeratelyLateVehicleIsNotReplaced() {
    start(timetable(TimetableStatus.PUBLISHED, 3));

    clock.set(Instant.parse("2026-03-02T08:08:20Z"));
    service.observeStop(event("train-A", 1, clock.get()), false);

    assertTrue(service.dutyBindingOf("train-A").isPresent(), "晚 400 秒，还在余量以内");
  }

  /** 替补出库票作废（没派出车就过了容差）：空缺退回待派，下一轮为后面的班次再派；剩下的班次全都过了容差就撤销空缺。 */
  @Test
  void abandonedReplacementsAreRetriedUntilNoTripIsLeft() {
    start(timetable(TimetableStatus.PUBLISHED, 3));
    at("08:15:30");
    TimetableService.Replacement first = at("08:17:00").get(0);

    service.replacementAbandoned(
        new TimetableService.TicketIntent(
            first.key().timetableId(),
            first.key().dutyId(),
            first.key().serviceDate(),
            RouteOperationType.CREATE,
            first.tripIndex()));
    assertEquals(1, at("08:18:00").size(), "作废之后还赶得上第三班，再派一张");

    service.replacementAbandoned(
        new TimetableService.TicketIntent(
            first.key().timetableId(),
            first.key().dutyId(),
            first.key().serviceDate(),
            RouteOperationType.CREATE,
            first.tripIndex()));
    assertTrue(at("08:25:30").isEmpty());
    assertEquals(0, service.vacantDutyCount(), "第三班 08:25 也作废了，没有班可接");
    assertTrue(
        logs.stream()
            .anyMatch(
                line ->
                    line.startsWith("TIMETABLE_DUTY_VACANCY_CLOSED")
                        && line.contains("reason=remaining-trips-expired")),
        logs::toString);
  }

  /**
   * 提前换车只在替补车赶得上<b>下一班</b>时做：08:14 晚 740 秒，替补车现在出库 08:17 才到，赶不上第二班（08:15 截止）；
   * 它赶得上第三班，但原车晚点跑第二班也许还在容差内（终点有折返余量时）——不提前换，等第二班真的作废再说。
   */
  @Test
  void earlyReplacementRequiresTheNextTripToBeCovered() {
    start(timetable(TimetableStatus.PUBLISHED, 3));

    clock.set(Instant.parse("2026-03-02T08:14:00Z"));
    service.observeStop(event("train-A", 1, clock.get()), false);

    assertTrue(service.dutyBindingOf("train-A").isPresent());
    assertTrue(at("08:15:30").isEmpty(), "第二班作废之后照常换车，替补车 08:17 出库");
    assertTrue(service.dutyBindingOf("train-A").isEmpty());
  }

  /** 替补出库票丢了（票据追踪重置、发车复位）：它要接的那一班过了容差，在途标记作废；没有班可接就撤销空缺。 */
  @Test
  void anInFlightReplacementIsClearedOnceItsTripExpires() {
    start(timetable(TimetableStatus.PUBLISHED, 3));
    at("08:15:30");
    assertEquals(1, at("08:17:00").size());

    assertTrue(at("08:25:30").isEmpty());
    assertEquals(0, service.vacantDutyCount(), "第三班 08:25 截止，在途替补作废，空缺撤销");
  }

  /** 换下来的车绑上别的交路就回到正常运营：回收不能再把它当成换下来的车。 */
  @Test
  void aRetiredVehicleThatTakesAnotherDutyIsNoLongerRetired() {
    start(timetable(TimetableStatus.PUBLISHED, 3));
    TimetableService.DutyKey duty = dutyOfTrainA();
    at("08:15:30");
    assertTrue(service.retiredFromDuty("train-A"));

    service.bindDuty(
        "train-A",
        new TimetableService.DutyKey(
            duty.timetableId(), duty.dutyId(), duty.serviceDate().plusDays(1)),
        "trip-assigned");

    assertFalse(service.retiredFromDuty("train-A"));
  }

  /**
   * 车被清掉（清车、手动删车）时交路还有两班：交路转成空缺，替补赶不上的第二班当即取消，第三班留给替补。
   *
   * <p>08:12:30 删车：替补现在出库、走行 + 就绪 180 秒，08:15:30 才到，赶不上第二班（08:15 截止）；第三班 08:17 出库赶得上。
   * 当前这一班（起点还没开出）照旧整趟取消。
   */
  @Test
  void aRemovedVehicleHandsItsDutyToAReplacementAndCancelsWhatItCannotReach() {
    List<TripCancellations.Cancellation> heard = new ArrayList<>();
    service.setCancellationListener(heard::add);
    Timetable table = timetable(TimetableStatus.PUBLISHED, 3);
    start(table);
    List<UUID> trips = table.duties().get(0).tripIds();
    clock.set(Instant.parse("2026-03-02T08:12:30Z"));

    service.release("train-A", "health-stuck-cleanup-timeout");

    assertEquals(
        List.of(trips.get(0), trips.get(1)),
        heard.stream().map(TripCancellations.Cancellation::tripId).toList(),
        logs::toString);
    TripCancellations.Cancellation second = heard.get(1);
    assertEquals(TripCancellations.Scope.FULL, second.scope());
    assertEquals(TripCancellations.Reason.VEHICLE_REMOVED, second.reason());
    assertEquals("train-A", second.trainName().orElseThrow());
    assertTrue(
        second.detail().startsWith("duty-vacated:health-stuck-cleanup-timeout replacement-from="),
        second.detail());
    assertEquals(1, service.vacantDutyCount());
    assertTrue(
        logs.stream()
            .anyMatch(
                line ->
                    line.startsWith("TIMETABLE_DUTY_VACATED train=train-A")
                        && line.contains("reason=removed:health-stuck-cleanup-timeout")
                        && line.contains("fromTrip=1")),
        logs::toString);
    assertFalse(service.retiredFromDuty("train-A"), "车已离开运行时，不再记退役");

    List<TimetableService.Replacement> issued = at("08:17:00");
    assertEquals(1, issued.size());
    assertEquals(2, issued.get(0).tripIndex(), "替补接第三班");
    assertEquals(2, heard.size(), "第三班有替补，不取消");
  }

  /** 没有出库线路能把替补送到起点：剩下的班次当即全部取消，不再等每张票各自过容差。 */
  @Test
  void aRemovedVehicleWithoutAnyReplacementCancelsTheRestOfItsDuty() {
    List<TripCancellations.Cancellation> heard = new ArrayList<>();
    service.setCancellationListener(heard::add);
    Timetable table = withoutCreateRoutes(timetable(TimetableStatus.PUBLISHED, 3));
    start(table);
    clock.set(Instant.parse("2026-03-02T08:05:00Z"));

    service.release("train-A", "train-removed");

    assertEquals(
        table.duties().get(0).tripIds(),
        heard.stream().map(TripCancellations.Cancellation::tripId).toList(),
        logs::toString);
    assertTrue(heard.get(2).detail().endsWith("no-replacement"), heard.get(2).detail());
  }

  /** 只扣车不出票：没有替补可派，后面的班次也可能被别的车按时间接上，不提前取消，也不登记空缺。 */
  @Test
  void withoutTicketingTheRestOfTheDutyIsLeftAlone() {
    List<TripCancellations.Cancellation> heard = new ArrayList<>();
    service.setCancellationListener(heard::add);
    Timetable table = timetable(TimetableStatus.PUBLISHED, 3);
    service.applySettings(
        new TimetableService.Settings(
            true,
            false,
            Duration.ofSeconds(120),
            Duration.ofSeconds(300),
            Duration.ofSeconds(300),
            ZONE));
    service.reload(providerWith(table));
    service.scheduledDepartureAt(event("train-A", 0, T0));
    assertTrue(service.dutyBindingOf("train-A").isPresent());

    service.release("train-A", "train-removed");

    assertEquals(
        List.of(table.duties().get(0).tripIds().get(0)),
        heard.stream().map(TripCancellations.Cancellation::tripId).toList(),
        "只有当前这一班");
    assertEquals(0, service.vacantDutyCount());
  }

  /** 跑完交路的车回库销毁是正常收车：没有班次可交，不记空缺、不印"换下"。 */
  @Test
  void aVehicleThatFinishedItsDutyLeavesNoVacancy() {
    start(timetable(TimetableStatus.PUBLISHED, 1));

    service.release("train-A", "depot-stored");

    assertEquals(0, service.vacantDutyCount());
    assertTrue(
        logs.stream().noneMatch(line -> line.startsWith("TIMETABLE_DUTY_VACATED")), logs::toString);
  }

  /** TrainCarts 卸载不算车没了：车在离线存储里，醒来还是它。交路不交给替补，也不提前取消后面的班次。 */
  @Test
  void anUnloadedVehicleKeepsItsDutyForWhenItWakesUp() {
    List<TripCancellations.Cancellation> heard = new ArrayList<>();
    service.setCancellationListener(heard::add);
    Timetable table = timetable(TimetableStatus.PUBLISHED, 3);
    start(table);
    clock.set(Instant.parse("2026-03-02T08:12:30Z"));

    service.release("train-A", StationStopObserver.RELEASE_UNLOADED);

    assertEquals(0, service.vacantDutyCount());
    assertEquals(
        List.of(table.duties().get(0).tripIds().get(0)),
        heard.stream().map(TripCancellations.Cancellation::tripId).toList(),
        "只有当前这一班（与改动前相同）");
  }

  /**
   * 跨零点：交路属于 3 月 2 日的服务日，00:00 那班却在 3 月 3 日发车。当即取消要按发车的日历日登记——站牌、出票、到期作废都按日历日查，
   * 按服务日登记站牌看不到，票到期还会再发一次事件。
   */
  @Test
  void cancellationsAcrossMidnightUseTheDepartureDate() {
    List<TripCancellations.Cancellation> heard = new ArrayList<>();
    service.setCancellationListener(heard::add);
    Timetable table = overnight(timetable(TimetableStatus.PUBLISHED, 3));
    service.applySettings(
        new TimetableService.Settings(
            true,
            true,
            Duration.ofSeconds(120),
            Duration.ofSeconds(300),
            Duration.ofSeconds(300),
            ZONE));
    service.reload(providerWith(table));
    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T23:50:05Z")));
    UUID midnightTrip = table.duties().get(0).tripIds().get(1);
    clock.set(Instant.parse("2026-03-03T00:02:30Z"));

    service.release("train-A", "train-removed");

    TripCancellations.Cancellation midnight =
        heard.stream().filter(c -> c.tripId().equals(midnightTrip)).findFirst().orElseThrow();
    assertEquals(LocalDate.of(2026, 3, 3), midnight.serviceDate());
    assertEquals(Instant.parse("2026-03-03T00:00:00Z"), midnight.plannedDeparture());
    assertTrue(
        service.cancellationOf(table.id(), midnightTrip, LocalDate.of(2026, 3, 3)).isPresent());
    assertEquals(2, heard.size(), "00:10 那班替补赶得上，不取消: " + heard);
  }

  /** 同一份表挪到深夜：三班 23:50、00:00、00:10，计划窗口 23:00 起，后两班跨零点。 */
  private static Timetable overnight(Timetable source) {
    VehicleDuty duty = source.duties().get(0);
    int[] departures = {23 * 3600 + 50 * 60, 0, 10 * 60};
    List<TimetableTrip> trips = new ArrayList<>();
    for (int i = 0; i < source.trips().size(); i++) {
      TimetableTrip trip = source.trips().get(i);
      trips.add(
          new TimetableTrip(
              trip.id(),
              trip.timetableId(),
              trip.routeId(),
              trip.sequence(),
              trip.tripCode(),
              departures[i],
              trip.dutyId()));
    }
    VehicleDuty shifted =
        new VehicleDuty(
            duty.id(),
            duty.timetableId(),
            duty.sequence(),
            duty.dutyCode(),
            duty.startDepotNodeId(),
            duty.endDepotNodeId(),
            duty.createRouteId(),
            duty.returnRouteId(),
            duty.tripIds(),
            departures[0] - 180,
            24 * 3600 + departures[2] + 230 + 120,
            24 * 3600 + departures[2] + 230 + 120 + 90,
            duty.closeReason());
    return new Timetable(
        source.id(),
        source.companyId(),
        source.operatorId(),
        source.lineId(),
        source.code(),
        source.name(),
        source.status(),
        source.zoneId(),
        23 * 3600,
        25 * 3600,
        source.routePlans(),
        trips,
        List.of(shifted),
        source.notes(),
        source.createdAt(),
        source.updatedAt());
  }

  /** 同一份表去掉 CREATE 线路：替补车无路可走。 */
  private static Timetable withoutCreateRoutes(Timetable source) {
    return new Timetable(
        source.id(),
        source.companyId(),
        source.operatorId(),
        source.lineId(),
        source.code(),
        source.name(),
        source.status(),
        source.zoneId(),
        source.serviceStartSecondOfDay(),
        source.serviceEndSecondOfDay(),
        source.routePlans().stream()
            .filter(plan -> plan.kind() != RouteOperationType.CREATE)
            .toList(),
        source.trips(),
        source.duties(),
        source.notes(),
        source.createdAt(),
        source.updatedAt());
  }
}
