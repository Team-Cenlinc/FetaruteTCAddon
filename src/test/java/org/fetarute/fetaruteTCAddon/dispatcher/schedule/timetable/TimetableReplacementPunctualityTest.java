package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.CREATE_ROUTE;
import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.event;
import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.providerWith;
import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.timetable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.junit.jupiter.api.Test;

/**
 * 替补只接能准点接上的下一班：替补车按计划出库时刻（计划发车 − 走行 − 就绪）出库才算赶得上，已经来不及准点的班次跳过并当即取消， 不再在发车容差内晚点接班。
 *
 * <p>夹具同 {@link TimetableDutyReplacementTest}：交路 D001 三班 08:00、08:10、08:20 从 AAA 发车，出库线路走行 60 秒、就绪
 * 120 秒（提前 180 秒出库），发车容差 300 秒。
 */
class TimetableReplacementPunctualityTest {

  private static final Instant T0 = Instant.parse("2026-03-02T08:00:05Z");

  private final AtomicReference<Instant> clock = new AtomicReference<>(T0);
  private final List<String> logs = new ArrayList<>();
  private final List<TripCancellations.Cancellation> heard = new ArrayList<>();
  private final TimetableService service = new TimetableService(clock::get, logs::add);

  private void apply(Timetable published) {
    service.applySettings(
        new TimetableService.Settings(
            true, true, Duration.ofSeconds(120), Duration.ofSeconds(300), Duration.ofSeconds(300)));
    service.setCancellationListener(heard::add);
    service.reload(providerWith(published));
  }

  private void start(Timetable published) {
    apply(published);
    service.scheduledDepartureAt(event("train-A", 0, T0));
  }

  private List<TimetableService.Replacement> at(String time) {
    clock.set(Instant.parse("2026-03-02T" + time + "Z"));
    return service.replacementsDue(clock.get());
  }

  private List<UUID> cancelledTrips() {
    return heard.stream().map(TripCancellations.Cancellation::tripId).toList();
  }

  /**
   * 08:08:30 车被清掉，交路从第二班（08:10）起空缺。替补现在出库要 08:11:30 才到，第二班已经来不及准点——以前这仍在容差（08:15）以内， 替补当场出库、晚 90
   * 秒接第二班，和同线路的下一班挤在一起开；现在第二班当即取消，替补按计划 08:17 出库接第三班，准点到站。
   */
  @Test
  void aReplacementNeverTakesATripItCanOnlyReachLate() {
    Timetable table = timetable(TimetableStatus.PUBLISHED, 3);
    List<UUID> trips = table.duties().get(0).tripIds();
    start(table);
    clock.set(Instant.parse("2026-03-02T08:08:30Z"));

    service.release("train-A", "train-removed");

    assertTrue(at("08:08:30").isEmpty(), "第二班替补只能晚到，不派");
    assertTrue(cancelledTrips().contains(trips.get(1)), "赶不上准点的第二班当即取消: " + logs);
    assertFalse(cancelledTrips().contains(trips.get(2)));

    List<TimetableService.Replacement> issued = at("08:17:00");
    assertEquals(1, issued.size());
    TimetableService.Replacement replacement = issued.get(0);
    assertEquals(2, replacement.tripIndex());
    assertFalse(
        replacement.issueAt().plusSeconds(180).isAfter(replacement.departure()), "按计划出库，准点到站");
  }

  /** 出票每隔几秒检查一次：计划出库时刻刚过几秒（两次检查之间）仍按准点派，不因检查的间隔丢掉这一班。 */
  @Test
  void aReplacementIsStillIssuedWhenThePollLandsJustAfterItsIssueTime() {
    start(timetable(TimetableStatus.PUBLISHED, 3));
    at("08:15:30");

    List<TimetableService.Replacement> issued = at("08:17:10");

    assertEquals(1, issued.size());
    assertEquals(2, issued.get(0).tripIndex());
  }

  /**
   * 严重晚点提前换车按准点口径：08:09 在 B 站晚 440 秒，替补现在出库 08:12 才到，接不上 08:10 的第二班准点——不提前换（以前容差内算赶得上， 换下后替补晚 2
   * 分钟接班）。
   */
  @Test
  void aHopelesslyLateVehicleIsKeptWhenNoReplacementCanMakeTheNextTripOnTime() {
    start(timetable(TimetableStatus.PUBLISHED, 3));

    clock.set(Instant.parse("2026-03-02T08:09:00Z"));
    service.observeStop(event("train-A", 1, clock.get()), false);

    assertTrue(service.dutyBindingOf("train-A").isPresent());
  }

  /** 班次间隔拉开到 20 分钟：08:09 晚 440 秒，替补 08:17 出库准点接 08:20 的第二班，这一刻就换车。 */
  @Test
  void aHopelesslyLateVehicleIsReplacedWhenTheNextTripCanStillBeMadeOnTime() {
    start(spaced(timetable(TimetableStatus.PUBLISHED, 3), 1200));

    clock.set(Instant.parse("2026-03-02T08:09:00Z"));
    service.observeStop(event("train-A", 1, clock.get()), false);

    assertTrue(service.dutyBindingOf("train-A").isEmpty());
    assertTrue(
        logs.stream()
            .anyMatch(
                line ->
                    line.startsWith("TIMETABLE_DUTY_VACATED train=train-A")
                        && line.contains("reason=late-440s")),
        logs::toString);
    assertTrue(at("08:09:00").isEmpty(), "替补按计划 08:17 出库");
    List<TimetableService.Replacement> issued = at("08:17:00");
    assertEquals(1, issued.size());
    assertEquals(1, issued.get(0).tripIndex(), "替补接第二班");
  }

  /**
   * 开服时两个交路都没有车（D001 08:00/08:10/08:20，D002 08:03/08:13/08:23），08:11 开始补车。
   *
   * <p>以前两个交路的替补在同一轮一起出库：D001 晚 4 分钟接 08:10、D002 晚 1 分钟接 08:13。现在各按准点能接的下一班的计划出库时刻错开： D001 08:17
   * 出库接 08:20，D002 08:20 出库接 08:23。D002 08:13 那班已来不及准点，当即取消；D001 08:10 那班刚过发车、还在容差内又没有车，
   * 同样取消（否则站牌把它当晚点待发挂到容差截止）；过了容差的 08:00、08:03 不回头取消。
   */
  @Test
  void startupReplacementsAreStaggeredByTheirPlannedIssueTimes() {
    Timetable table = withSecondDuty(timetable(TimetableStatus.PUBLISHED, 3), 180);
    apply(table);
    List<UUID> first = table.duties().get(0).tripIds();
    List<UUID> second = table.duties().get(1).tripIds();
    clock.set(Instant.parse("2026-03-02T08:11:00Z"));
    service.restoreDutyBindings(clock.get());

    service.recoverUnboundDuties(Set.of(CREATE_ROUTE), clock.get());
    assertEquals(2, service.vacantDutyCount());

    assertTrue(at("08:11:00").isEmpty(), "两个交路都等计划出库时刻");
    assertEquals(
        Set.of(first.get(1), second.get(1)), Set.copyOf(cancelledTrips()), "取消还没过容差、又没有车接的班次");
    assertEquals(TripCancellations.Reason.NOT_DISPATCHED, heard.get(0).reason());
    assertTrue(heard.get(0).trainName().isEmpty());

    List<TimetableService.Replacement> d001 = at("08:17:00");
    assertEquals(1, d001.size());
    assertEquals(first, d001.get(0).duty().tripIds());
    assertEquals(2, d001.get(0).tripIndex());
    assertTrue(at("08:19:00").isEmpty());

    List<TimetableService.Replacement> d002 = at("08:20:00");
    assertEquals(1, d002.size());
    assertEquals(second, d002.get(0).duty().tripIds());
    assertEquals(2, d002.get(0).tripIndex());
    assertEquals(2, heard.size(), "替补接上的班次不取消: " + heard);
  }

  /**
   * 终点续班与运行时同一口径：第二班 08:10 的票一直在等 train-A（续班票等本交路的车），08:20 时晚了 600 秒、过了容差但没到晚点上限 900 秒，
   * 车到终点仍接着开第二班，站牌上的下一趟就是它；08:25 到了上限、车被换下，下一趟由替补接，这辆车没有下一趟。
   */
  @Test
  void aLateVehicleStillContinuesWithItsNextTripUntilItIsReplaced() {
    Timetable table = timetable(TimetableStatus.PUBLISHED, 4);
    service.applySettings(
        new TimetableService.Settings(
            true,
            true,
            Duration.ofSeconds(120),
            Duration.ofSeconds(300),
            Duration.ofSeconds(300),
            Duration.ZERO,
            Duration.ofSeconds(900)));
    service.setCancellationListener(heard::add);
    service.reload(providerWith(table));
    service.scheduledDepartureAt(event("train-A", 0, T0));
    service.setPendingTicketProbe(
        intent -> intent.tripIndex() == 1 && service.dutyBindingOf("train-A").isPresent());

    assertTrue(at("08:20:00").isEmpty());
    assertTrue(service.dutyBindingOf("train-A").isPresent(), "票还在等它，晚点就晚发");
    assertEquals(
        "R1-002",
        service.nextDepartureOf("train-A").orElseThrow().trip().tripCode(),
        "过了容差、没到晚点上限：终点续班仍是第二班");

    assertTrue(at("08:25:00").isEmpty());
    assertTrue(service.retiredFromDuty("train-A"));
    assertTrue(service.nextDepartureOf("train-A").isEmpty(), "换下来的车没有下一趟，站牌照实列本站终到");
  }

  /**
   * 第二班的票不在了（开服前出的票随重启丢失）：车不会再开这一班。替补能准点接第四班（08:30，08:27 出库）就当场换下这辆车， 站牌上它没有下一趟；第三班 08:20
   * 替补来不及准点，当即取消。
   */
  @Test
  void aVehicleWhoseNextTripWillNotRunIsReplacedAndShowsNoNextTrip() {
    Timetable table = timetable(TimetableStatus.PUBLISHED, 4);
    List<UUID> trips = table.duties().get(0).tripIds();
    start(table);

    assertTrue(at("08:19:00").isEmpty());

    assertTrue(service.retiredFromDuty("train-A"));
    assertTrue(service.nextDepartureOf("train-A").isEmpty());
    assertEquals(List.of(trips.get(2)), cancelledTrips(), logs::toString);
    assertEquals(3, at("08:27:00").get(0).tripIndex());
  }

  /** 两次出票检查相隔比宽限更久（出票间隔调大、服务器卡顿）：计划出库时刻落在两次检查之间的那一班仍按准点派，不白白取消。 */
  @Test
  void aLongerPollGapStillCatchesTheTripWhoseIssueTimeFellBetweenChecks() {
    Timetable table = timetable(TimetableStatus.PUBLISHED, 3);
    List<UUID> trips = table.duties().get(0).tripIds();
    start(table);
    clock.set(Instant.parse("2026-03-02T08:08:30Z"));
    service.release("train-A", "train-removed");
    at("08:16:40");

    List<TimetableService.Replacement> issued = at("08:17:25");

    assertEquals(1, issued.size(), "08:17 计划出库，检查相隔 45 秒，08:17:25 仍算准点");
    assertEquals(2, issued.get(0).tripIndex());
    assertFalse(cancelledTrips().contains(trips.get(2)));
  }

  /**
   * 开服时交路记录没恢复上的幸存车随后按时刻接回空缺的交路：替补阶段给它后面班次登记的取消一并撤销，站牌不再把它要跑的班次标成取消。
   *
   * <p>班次每 2 分钟一班（08:00…08:06），08:01:30 开服：08:02、08:04 已来不及准点、当即取消，替补按计划 08:03 出库接 08:06。 08:02:05
   * 幸存车开出 08:02 那班，接回交路，08:04 那班的取消随之撤销。
   */
  @Test
  void aSurvivorTakingBackAVacantDutyRevokesItsSkippedCancellations() {
    Timetable table = spaced(timetable(TimetableStatus.PUBLISHED, 4), 120);
    List<UUID> trips = table.duties().get(0).tripIds();
    apply(table);
    clock.set(Instant.parse("2026-03-02T08:01:30Z"));
    service.restoreDutyBindings(clock.get());
    service.recoverUnboundDuties(Set.of(CREATE_ROUTE), clock.get());
    at("08:01:30");
    java.time.LocalDate day = java.time.LocalDate.parse("2026-03-02");
    assertTrue(service.cancellationOf(table.id(), trips.get(2), day).isPresent(), logs::toString);

    clock.set(Instant.parse("2026-03-02T08:02:05Z"));
    service.scheduledDepartureAt(event("train-S", 0, clock.get()));

    assertTrue(service.dutyBindingOf("train-S").isPresent(), logs::toString);
    assertTrue(service.cancellationOf(table.id(), trips.get(1), day).isEmpty());
    assertTrue(service.cancellationOf(table.id(), trips.get(2), day).isEmpty(), "后面的班次由它接着跑");
  }

  /**
   * 替补从交路原定的车库出：表里另有一条走行更短的出库线路（OTHER 库 → AAA，30 秒）也到 AAA，替补仍走交路自己的 DEP 出库线路， 与编表投影一致（原定回库还是
   * DEP），按它的走行 + 就绪 08:17 出库接第三班。
   */
  @Test
  void aReplacementLeavesFromTheDutysOwnDepot() {
    UUID shorter = UUID.randomUUID();
    Timetable table =
        withPlan(
            timetable(TimetableStatus.PUBLISHED, 3),
            createPlan(shorter, "OTHER", "OP:D:OTHER:1", "OP:S:AAA:1", 30));
    start(table);
    clock.set(Instant.parse("2026-03-02T08:08:30Z"));
    service.release("train-A", "train-removed");

    assertTrue(at("08:08:30").isEmpty());
    List<TimetableService.Replacement> issued = at("08:17:00");

    assertEquals(1, issued.size(), logs::toString);
    assertEquals(CREATE_ROUTE, issued.get(0).createRouteId());
    assertEquals(Instant.parse("2026-03-02T08:17:00Z"), issued.get(0).issueAt());
  }

  /** 交路原定的出库线路到不了那一班的起点时，照旧在到得了的出库线路里挑走行最短的。 */
  @Test
  void aReplacementFallsBackWhenTheDutysDepotCannotReachTheTrip() {
    UUID elsewhere = UUID.randomUUID();
    UUID shorter = UUID.randomUUID();
    Timetable base =
        withPlan(
            withPlan(
                timetable(TimetableStatus.PUBLISHED, 3),
                createPlan(elsewhere, "FAR", "OP:D:DEP:1", "OP:S:ZZZ:1", 20)),
            createPlan(shorter, "OTHER", "OP:D:OTHER:1", "OP:S:AAA:1", 30));
    VehicleDuty duty = base.duties().get(0);
    Timetable table =
        copy(
            base,
            base.trips(),
            List.of(
                new VehicleDuty(
                    duty.id(),
                    duty.timetableId(),
                    duty.sequence(),
                    duty.dutyCode(),
                    duty.startDepotNodeId(),
                    duty.endDepotNodeId(),
                    Optional.of(elsewhere),
                    duty.returnRouteId(),
                    duty.tripIds(),
                    duty.plannedStartSecondOfDay(),
                    duty.returnSecondOfDay(),
                    duty.plannedEndSecondOfDay(),
                    duty.closeReason())));
    start(table);
    clock.set(Instant.parse("2026-03-02T08:08:30Z"));
    service.release("train-A", "train-removed");

    List<TimetableService.Replacement> issued = at("08:19:30");

    assertEquals(1, issued.size(), logs::toString);
    assertEquals(shorter, issued.get(0).createRouteId());
  }

  /** 加一条计划。 */
  private static Timetable withPlan(Timetable source, TimetableRoutePlan plan) {
    List<TimetableRoutePlan> plans = new ArrayList<>(source.routePlans());
    plans.add(plan);
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
        plans,
        source.trips(),
        source.duties(),
        source.notes(),
        source.createdAt(),
        source.updatedAt());
  }

  /** 车库 {@code depot} 到 {@code terminal} 的出库线路，走行 {@code run} 秒。 */
  private static TimetableRoutePlan createPlan(
      UUID id, String code, String depot, String terminal, int run) {
    return new TimetableRoutePlan(
        id,
        code,
        RouteOperationType.CREATE,
        0,
        List.of(
            new TimetableStop(
                0, Optional.empty(), Optional.of(depot), 0, 0, RouteStopPassType.STOP),
            new TimetableStop(
                1, Optional.empty(), Optional.of(terminal), run, run, RouteStopPassType.STOP)),
        depot,
        terminal,
        Optional.empty(),
        Optional.empty());
  }

  /** 同一份表把班次间隔改成 {@code spacing} 秒（首班仍是 08:00）。 */
  private static Timetable spaced(Timetable source, int spacing) {
    List<TimetableTrip> trips = new ArrayList<>();
    for (TimetableTrip trip : source.trips()) {
      trips.add(
          new TimetableTrip(
              trip.id(),
              trip.timetableId(),
              trip.routeId(),
              trip.sequence(),
              trip.tripCode(),
              8 * 3600 + spacing * trip.sequence(),
              trip.dutyId()));
    }
    VehicleDuty duty = source.duties().get(0);
    int last = 8 * 3600 + spacing * (trips.size() - 1);
    VehicleDuty stretched =
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
            duty.plannedStartSecondOfDay(),
            last + 230 + 120,
            last + 230 + 120 + 90,
            duty.closeReason());
    return copy(source, trips, List.of(stretched));
  }

  /** 加一个与 D001 相同、整体晚 {@code offset} 秒的交路 D002。 */
  private static Timetable withSecondDuty(Timetable source, int offset) {
    VehicleDuty duty = source.duties().get(0);
    UUID dutyId = UUID.randomUUID();
    List<TimetableTrip> trips = new ArrayList<>(source.trips());
    List<UUID> ids = new ArrayList<>();
    for (TimetableTrip trip : source.trips()) {
      TimetableTrip shifted =
          new TimetableTrip(
              UUID.randomUUID(),
              trip.timetableId(),
              trip.routeId(),
              trip.sequence() + source.trips().size(),
              "R2-" + trip.tripCode().substring(3),
              trip.departureSecondOfDay() + offset,
              Optional.of(dutyId));
      trips.add(shifted);
      ids.add(shifted.id());
    }
    VehicleDuty second =
        new VehicleDuty(
            dutyId,
            duty.timetableId(),
            duty.sequence() + 1,
            "D002",
            duty.startDepotNodeId(),
            duty.endDepotNodeId(),
            duty.createRouteId(),
            duty.returnRouteId(),
            ids,
            duty.plannedStartSecondOfDay() + offset,
            duty.returnSecondOfDay() + offset,
            duty.plannedEndSecondOfDay() + offset,
            duty.closeReason());
    return copy(source, trips, List.of(duty, second));
  }

  private static Timetable copy(
      Timetable source, List<TimetableTrip> trips, List<VehicleDuty> duties) {
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
        source.routePlans(),
        trips,
        duties,
        source.notes(),
        source.createdAt(),
        source.updatedAt());
  }
}
