package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 按表运行认联挂：回程支线交路 D002 以并入主干交路 D001 的第一班结束。
 *
 * <p>夹具沿用 {@link TimetableServiceTest#timetable}（三班，08:00 起每 10 分钟一班）：前两班留给 D001，第三班改给 D002。
 */
class TimetableJoinRuntimeTest {

  private static final Instant T0 = Instant.parse("2026-03-02T08:00:05Z");
  private static final LocalDate DAY = LocalDate.parse("2026-03-02");

  private final AtomicReference<Instant> clock = new AtomicReference<>(T0);
  private final TimetableService service = new TimetableService(clock::get, line -> {});

  private Timetable start() {
    Timetable base = timetable(TimetableStatus.PUBLISHED, 3);
    VehicleDuty trunk = base.duties().get(0);
    TimetableTrip first = base.trips().get(0);
    TimetableTrip third = base.trips().get(2);
    UUID feederId =
        UUID.nameUUIDFromBytes("feeder".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    List<TimetableTrip> trips = new ArrayList<>(base.trips().subList(0, 2));
    trips.add(
        new TimetableTrip(
            third.id(),
            third.timetableId(),
            third.routeId(),
            third.sequence(),
            third.tripCode(),
            third.departureSecondOfDay(),
            Optional.of(feederId)));
    VehicleDuty shortened =
        new VehicleDuty(
            trunk.id(),
            trunk.timetableId(),
            trunk.sequence(),
            trunk.dutyCode(),
            trunk.startDepotNodeId(),
            trunk.endDepotNodeId(),
            trunk.createRouteId(),
            trunk.returnRouteId(),
            List.of(first.id(), base.trips().get(1).id()),
            trunk.plannedStartSecondOfDay(),
            trunk.returnSecondOfDay(),
            trunk.plannedEndSecondOfDay(),
            trunk.closeReason(),
            trunk.consist());
    VehicleDuty feeder =
        new VehicleDuty(
            feederId,
            trunk.timetableId(),
            1,
            "D002",
            "OP:D:DEP:1",
            "OP:S:BBB:1",
            trunk.createRouteId(),
            Optional.empty(),
            List.of(third.id()),
            third.departureSecondOfDay() - 180,
            third.departureSecondOfDay() + 230,
            third.departureSecondOfDay() + 230,
            VehicleDuty.CloseReason.COUPLED,
            Optional.empty(),
            new DutyCoupling(Optional.empty(), Optional.of(first.id())));
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
            List.of(shortened, feeder),
            base.notes(),
            base.createdAt(),
            base.updatedAt());
    service.applySettings(
        new TimetableService.Settings(
            true, true, Duration.ofSeconds(120), Duration.ofSeconds(300), Duration.ofSeconds(300)));
    service.reload(providerWith(table));
    return table;
  }

  private TimetableService.DutyKey feederKey(Timetable table) {
    return new TimetableService.DutyKey(table.id(), table.duties().get(1).id(), DAY);
  }

  @Test
  void theTrunkFindsTheFeederJoiningItsRunningTrip() {
    Timetable table = start();
    assertEquals(TimetableService.JoinPlan.UNBOUND, service.joinPlanOf("trunk"));

    service.scheduledDepartureAt(event("trunk", 0, T0));
    TimetableService.JoinPlan before = service.joinPlanOf("trunk");
    assertEquals(Optional.of(feederKey(table)), before.feeder());
    assertTrue(before.feederTrain().isEmpty(), "回程支线交路还没有车");

    service.bindDuty("feeder", feederKey(table), "test");
    assertEquals(Optional.of("feeder"), service.joinPlanOf("trunk").feederTrain());
  }

  @Test
  void theFeederWaitsOnlyWhileTheTrunkIsStillComing() {
    Timetable table = start();
    service.bindDuty("feeder", feederKey(table), "test");
    assertEquals(Optional.of(""), service.joinTrunkOf("feeder"), "主干交路还没有车：不会来");

    service.scheduledDepartureAt(event("trunk", 0, T0));
    assertEquals(Optional.of("trunk"), service.joinTrunkOf("feeder"));

    clock.set(Instant.parse("2026-03-02T08:10:05Z"));
    service.scheduledDepartureAt(event("trunk", 0, clock.get()));
    assertEquals(Optional.of(""), service.joinTrunkOf("feeder"), "主干已经跑上下一班：那一班过去了");
  }

  @Test
  void aDutyThatDoesNotEndByJoiningHasNoTrunk() {
    start();
    service.scheduledDepartureAt(event("trunk", 0, T0));

    assertTrue(service.joinTrunkOf("trunk").isEmpty());
    assertFalse(service.joinTrunkOf("nobody").isPresent());
  }
}
