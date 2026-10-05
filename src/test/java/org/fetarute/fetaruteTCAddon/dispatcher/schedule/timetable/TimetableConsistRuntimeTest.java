package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.CREATE_ROUTE;
import static org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableServiceTest.ROUTE;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.junit.jupiter.api.Test;

/**
 * 按表运行认交路的车型：各站时刻按车次那辆车的车型、没绑交路的车只接同车型交路的首班、门控就近绑定跳过别的车型、 替补车只走表里允许这个车型的出库线路。
 *
 * <p>夹具沿用 {@link TimetableServiceTest#timetable}：三班的交路 D001（08:00、08:10、08:20 从 AAA 发车）。 基础计划是最慢的 8
 * 节（B 站 100 到 130 发），6 节的变体快一些（B 站 60 到 80 发）。
 */
class TimetableConsistRuntimeTest {

  private static final Instant T0 = Instant.parse("2026-03-02T08:00:05Z");
  private static final LocalDate DAY = LocalDate.parse("2026-03-02");

  private final AtomicReference<Instant> clock = new AtomicReference<>(T0);
  private final List<String> logs = new ArrayList<>();
  private final TimetableService service = new TimetableService(clock::get, logs::add);

  private static final Map<String, String> CONSIST_OF_TRAIN = Map.of("six", "m6", "eight", "m8");

  /** 交路改成指定车型，并给运营与出库线路加上各车型的变体计划。 */
  static Timetable mixed(String dutyConsist, List<String> createConsists) {
    Timetable base = timetable(TimetableStatus.PUBLISHED, 3);
    List<TimetableRoutePlan> plans = new ArrayList<>(base.routePlans());
    TimetableRoutePlan operation = base.routePlan(ROUTE).orElseThrow();
    plans.add(variant(operation, "m8", operation.stops()));
    plans.add(
        variant(
            operation,
            "m6",
            List.of(
                new TimetableStop(
                    0, Optional.of("AAA"), Optional.of("OP:S:AAA:1"), 0, 0, RouteStopPassType.STOP),
                new TimetableStop(
                    1,
                    Optional.of("BBB"),
                    Optional.of("OP:S:BBB:1"),
                    60,
                    80,
                    RouteStopPassType.STOP),
                new TimetableStop(
                    2,
                    Optional.of("CCC"),
                    Optional.of("OP:S:CCC:1"),
                    150,
                    150,
                    RouteStopPassType.STOP))));
    TimetableRoutePlan create = base.routePlan(CREATE_ROUTE).orElseThrow();
    for (String consist : createConsists) {
      plans.add(variant(create, consist, create.stops()));
    }
    List<VehicleDuty> duties = new ArrayList<>();
    for (VehicleDuty duty : base.duties()) {
      duties.add(
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
              duty.returnSecondOfDay(),
              duty.plannedEndSecondOfDay(),
              duty.closeReason(),
              Optional.of(dutyConsist)));
    }
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
        plans,
        base.trips(),
        duties,
        base.notes(),
        base.createdAt(),
        base.updatedAt());
  }

  private static TimetableRoutePlan variant(
      TimetableRoutePlan plan, String consist, List<TimetableStop> stops) {
    return plan.asVariant(
        plan.routeId(),
        new TimetableRoutePlan.ConsistVariant(consist, plan.routeId(), 60.0, 0.0),
        stops);
  }

  private void start(Timetable published) {
    service.applySettings(
        new TimetableService.Settings(
            true, true, Duration.ofSeconds(120), Duration.ofSeconds(300), Duration.ofSeconds(300)));
    service.reload(providerWith(published));
    service.setConsistOfTrain(name -> Optional.ofNullable(CONSIST_OF_TRAIN.get(name)));
  }

  private static TimetableService.TicketIntent firstTrip(Timetable table) {
    VehicleDuty duty = table.duties().get(0);
    return new TimetableService.TicketIntent(
        table.id(), duty.id(), DAY, RouteOperationType.OPERATION, 0);
  }

  @Test
  void stopTimesFollowTheDutyConsist() {
    Timetable table = mixed("m6", List.of("m6", "m8"));
    TimetableTrip first = table.trips().get(0);

    assertEquals(
        Optional.of("m6"),
        table
            .tripPlan(first)
            .flatMap(TimetableRoutePlan::consist)
            .map(TimetableRoutePlan.ConsistVariant::key));
    assertEquals(
        Optional.of(Instant.parse("2026-03-02T08:01:20Z")),
        table.scheduledDeparture(first, 1, DAY),
        "6 节在 B 站 80 秒发车，不按基础计划（最慢车型）的 130 秒");
    assertEquals(
        Optional.of(Instant.parse("2026-03-02T08:01:40Z")),
        mixed("m8", List.of("m6", "m8")).scheduledArrival(first, 1, DAY),
        "8 节按自己的 100 秒到达");
  }

  @Test
  void unboundVehicleOnlyTakesTheFirstTripOfItsOwnConsist() {
    Timetable table = mixed("m6", List.of("m6", "m8"));
    start(table);
    TimetableService.TicketIntent first = firstTrip(table);

    assertTrue(service.acceptsVehicle(first, "six"));
    assertFalse(service.acceptsVehicle(first, "eight"), "8 节跑不出 6 节的时刻");
    assertFalse(service.acceptsVehicle(first, "unknown"), "读不出车型的车不接带车型的交路");
    assertTrue(
        logs.stream().anyMatch(line -> line.contains("reason=consist-mismatch")), logs::toString);
  }

  @Test
  void gateMatchingSkipsDutiesOfAnotherConsist() {
    start(mixed("m6", List.of("m6", "m8")));

    assertTrue(service.scheduledDepartureAt(event("eight", 0, T0)).isEmpty());
    assertTrue(service.dutyBindingOf("eight").isEmpty(), "8 节不就近绑到 6 节的交路");
    assertTrue(service.scheduledDepartureAt(event("six", 0, T0)).isPresent());
    assertTrue(service.dutyBindingOf("six").isPresent());
  }

  @Test
  void replacementOnlyUsesCreateRoutesServingTheDutyConsist() {
    start(mixed("m8", List.of("m6")));
    assertTrue(service.scheduledDepartureAt(event("eight", 0, T0)).isPresent());

    clock.set(Instant.parse("2026-03-02T08:15:30Z"));
    assertTrue(service.replacementsDue(clock.get()).isEmpty());
    assertTrue(service.dutyBindingOf("eight").isPresent(), "表里没有能出 8 节的出库线路：派不出替补，原车照旧留在交路上");

    TimetableService other = new TimetableService(clock::get, logs::add);
    clock.set(T0);
    other.applySettings(
        new TimetableService.Settings(
            true, true, Duration.ofSeconds(120), Duration.ofSeconds(300), Duration.ofSeconds(300)));
    other.reload(providerWith(mixed("m8", List.of("m6", "m8"))));
    other.setConsistOfTrain(name -> Optional.ofNullable(CONSIST_OF_TRAIN.get(name)));
    assertTrue(other.scheduledDepartureAt(event("eight", 0, T0)).isPresent());
    clock.set(Instant.parse("2026-03-02T08:15:30Z"));
    other.replacementsDue(clock.get());
    clock.set(Instant.parse("2026-03-02T08:17:00Z"));
    List<TimetableService.Replacement> issued = other.replacementsDue(clock.get());
    assertEquals(1, issued.size(), "有 8 节的出库变体：替补车照常派");
    assertEquals(Optional.of("m8"), issued.get(0).leg(clock.get()).duty().consist(), "替补出库票带交路的车型");
  }
}
