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
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.junit.jupiter.api.Test;

/**
 * 按表运行认解编：主干交路 D001 跑第一班时在中途解下一段，子交路 D002 的首班由解下的那辆车跑。
 *
 * <p>夹具沿用 {@link TimetableServiceTest#timetable}（三班，08:00 起每 10 分钟一班）：前两班留给 D001，第三班改给由第一班拆分开始的
 * D002。
 */
class TimetableSplitRuntimeTest {

  private static final Instant T0 = Instant.parse("2026-03-02T08:00:05Z");
  private static final LocalDate DAY = LocalDate.parse("2026-03-02");

  private final AtomicReference<Instant> clock = new AtomicReference<>(T0);
  private final List<String> logs = new ArrayList<>();
  private final TimetableService service = new TimetableService(clock::get, logs::add);

  /**
   * @param splitTrip 主干交路里第几班拆出子交路
   */
  private static Timetable withSplitChild(int splitTrip) {
    return withSplitChild(splitTrip, false);
  }

  /**
   * @param joinsTrunk 子交路最后并回主干交路的首班（回程支线）
   */
  private static Timetable withSplitChild(int splitTrip, boolean joinsTrunk) {
    Timetable base = timetable(TimetableStatus.PUBLISHED, 3);
    VehicleDuty trunk = base.duties().get(0);
    TimetableTrip first = base.trips().get(0);
    TimetableTrip third = base.trips().get(2);
    UUID childId =
        UUID.nameUUIDFromBytes("child".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    List<TimetableTrip> trips = new ArrayList<>(base.trips().subList(0, 2));
    trips.add(
        new TimetableTrip(
            third.id(),
            third.timetableId(),
            third.routeId(),
            third.sequence(),
            third.tripCode(),
            third.departureSecondOfDay(),
            Optional.of(childId)));
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
    VehicleDuty child =
        new VehicleDuty(
            childId,
            trunk.timetableId(),
            1,
            "D002",
            "OP:S:BBB:1",
            "OP:S:CCC:1",
            Optional.empty(),
            Optional.empty(),
            List.of(third.id()),
            8 * 3600 + 130,
            third.departureSecondOfDay() + 230,
            third.departureSecondOfDay() + 230,
            VehicleDuty.CloseReason.IDLE_LIMIT,
            Optional.empty(),
            new DutyCoupling(
                Optional.of(base.trips().get(splitTrip).id()),
                joinsTrunk ? Optional.of(first.id()) : Optional.empty()));
    return new Timetable(
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
        List.of(shortened, child),
        base.notes(),
        base.createdAt(),
        base.updatedAt());
  }

  private Timetable start() {
    return start(0);
  }

  private Timetable start(int splitTrip) {
    Timetable table = withSplitChild(splitTrip);
    service.applySettings(
        new TimetableService.Settings(
            true, true, Duration.ofSeconds(120), Duration.ofSeconds(300), Duration.ofSeconds(300)));
    service.reload(providerWith(table));
    return table;
  }

  private static TimetableService.TicketIntent childFirstTrip(Timetable table) {
    return new TimetableService.TicketIntent(
        table.id(), table.duties().get(1).id(), DAY, RouteOperationType.OPERATION, 0);
  }

  @Test
  void theTrunkVehicleFindsTheChildDutyOfItsRunningTrip() {
    Timetable table = start();
    assertEquals(TimetableService.SplitPlan.UNBOUND, service.splitPlanOf("trunk"), "没绑交路按无表处理");

    assertTrue(service.scheduledDepartureAt(event("trunk", 0, T0)).isPresent());
    TimetableService.SplitPlan plan = service.splitPlanOf("trunk");

    assertTrue(plan.bound());
    assertEquals(
        Optional.of(new TimetableService.DutyKey(table.id(), table.duties().get(1).id(), DAY)),
        plan.child());
    assertFalse(plan.tripless());
  }

  @Test
  void theChildFirstTripWaitsForTheTrunkAndThenOnlyTakesTheDetachedCar() {
    Timetable table = start();
    service.scheduledDepartureAt(event("trunk", 0, T0));
    TimetableService.TicketIntent first = childFirstTrip(table);

    assertTrue(service.awaitsOwnVehicle(first), "主干还没跑过拆分那一班：首班票等它");
    assertFalse(service.acceptsVehicle(first, "other"), "车还没解下来：首班留给它，站里别的待命车不能接走");

    TimetableService.DutyKey child = service.splitPlanOf("trunk").child().orElseThrow();
    service.pinSplit(child, "detached", false);

    assertTrue(service.acceptsVehicle(first, "detached"));
    assertFalse(service.acceptsVehicle(first, "other"), "首班只接解下的那辆车");
    TimetableService.TicketIntent trunkSecond =
        new TimetableService.TicketIntent(
            table.id(), table.duties().get(0).id(), DAY, RouteOperationType.OPERATION, 0);
    assertFalse(service.acceptsVehicle(trunkSecond, "detached"), "解下的车只接子交路的票");
    assertTrue(service.awaitsOwnVehicle(first));
    assertTrue(service.dutyBindingOf("detached").isEmpty(), "钉车不等于绑交路：首班票派出时才绑");
    UUID childFirst = table.duties().get(1).tripIds().get(0);
    assertEquals(
        Optional.of(childFirst),
        service.nextDepartureOf("detached").map(due -> due.trip().id()),
        "还没绑上也知道下一趟是子交路首班：站牌按它列出这辆车");
    assertTrue(service.nextDepartureOf("other").isEmpty());

    service.bindDuty("detached", child, "dispatch");
    assertTrue(service.acceptsVehicle(first, "detached"));
  }

  /** 车跑完前一班、还没开出拆分那一班：下一趟拆不拆已经知道，站牌提前写分行。 */
  @Test
  void theNextTripsSplitIsKnownBeforeItDeparts() {
    Timetable table = start(1);
    service.scheduledDepartureAt(event("trunk", 0, T0));

    assertEquals(TimetableService.SplitPlan.NONE, service.splitPlanOf("trunk"), "正在跑的这一班不拆");
    TimetableService.SplitPlan plan = service.splitPlanOfNext("trunk");

    assertTrue(plan.bound(), plan::toString);
    assertEquals(
        Optional.of(new TimetableService.DutyKey(table.id(), table.duties().get(1).id(), DAY)),
        plan.child());
    assertEquals(TimetableService.SplitPlan.UNBOUND, service.splitPlanOfNext("other"));
  }

  /** 还没出车的一班（站牌上的票据与预测行）：按票据意图查表上那一班拆不拆，不看车。 */
  @Test
  void aPlannedTripKnowsItsSplitWithoutAVehicle() {
    Timetable table = start(1);
    UUID trunk = table.duties().get(0).id();

    TimetableService.PlannedTrip second =
        service
            .plannedTripOf(
                new TimetableService.TicketIntent(
                    table.id(), trunk, DAY, RouteOperationType.OPERATION, 1))
            .orElseThrow();
    assertEquals(table.trips().get(1).routeId(), second.routeId());
    assertEquals(
        Optional.of(new TimetableService.DutyKey(table.id(), table.duties().get(1).id(), DAY)),
        second.split().child());

    TimetableService.PlannedTrip first =
        service
            .plannedTripOf(
                new TimetableService.TicketIntent(
                    table.id(), trunk, DAY, RouteOperationType.OPERATION, 0))
            .orElseThrow();
    assertEquals(TimetableService.SplitPlan.NONE, first.split(), "这一班不拆");
    assertTrue(
        service
            .plannedTripOf(
                new TimetableService.TicketIntent(
                    table.id(), trunk, DAY, RouteOperationType.OPERATION, 5))
            .isEmpty(),
        "班次越界");
    assertTrue(
        service
            .plannedTripOf(
                new TimetableService.TicketIntent(
                    table.id(), trunk, DAY, RouteOperationType.RETURN, 1))
            .isEmpty(),
        "只看载客班次");
  }

  /** 回程支线由拆分产生：主干车还没跑过拆分那一班时算有着落；拆不开以后、主干没车时算缺车（主干出车出整列）。 */
  @Test
  void aFeederComingFromASplitIsMissingOnlyWhenTheSplitCannotHappen() {
    Timetable table = withSplitChild(0, true);
    service.applySettings(
        new TimetableService.Settings(
            true, true, Duration.ofSeconds(120), Duration.ofSeconds(300), Duration.ofSeconds(300)));
    service.reload(providerWith(table));
    TimetableService.DutyKey trunk =
        new TimetableService.DutyKey(table.id(), table.duties().get(0).id(), DAY);
    TimetableService.DutyKey child =
        new TimetableService.DutyKey(table.id(), table.duties().get(1).id(), DAY);

    assertTrue(service.feederMissing(trunk), "主干没车：拆不出回程支线的车");
    assertFalse(service.feederMissing(child), "子交路首班不联挂");

    service.scheduledDepartureAt(event("trunk", 0, T0));
    assertFalse(service.feederMissing(trunk), "主干车还没跑过拆分那一班：回程支线的车会有");

    service.splitFailed(child);
    assertTrue(service.feederMissing(trunk), "拆不开：回程支线没车");
  }

  @Test
  void aFailedSplitLetsTheChildFirstTripLapse() {
    Timetable table = start();
    service.scheduledDepartureAt(event("trunk", 0, T0));
    TimetableService.TicketIntent first = childFirstTrip(table);
    TimetableService.DutyKey child = service.splitPlanOf("trunk").child().orElseThrow();

    service.splitFailed(child);

    assertFalse(service.awaitsOwnVehicle(first), "拆不开：票不再等，到期照常登记取消");
    assertFalse(service.acceptsVehicle(first, "other"));
    assertTrue(
        logs.stream().anyMatch(line -> line.contains("reason=split-failed")), logs::toString);
  }

  @Test
  void aTriplessChildIsBoundSoItsReturnLegTakesTheCar() {
    Timetable table = start();
    TimetableService.DutyKey child =
        new TimetableService.DutyKey(table.id(), table.duties().get(1).id(), DAY);

    service.pinSplit(child, "detached", true);

    assertEquals(Optional.of(child), service.dutyBindingOf("detached"));
  }
}
