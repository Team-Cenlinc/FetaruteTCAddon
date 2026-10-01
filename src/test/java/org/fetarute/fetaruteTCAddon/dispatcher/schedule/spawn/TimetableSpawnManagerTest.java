package org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.Timetable;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableRoutePlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStatus;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableStop;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.VehicleDuty;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.repository.TimetableRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.Test;

/**
 * 表定出票的交路意图：一个 duty 的出库票、两张运营票、回库票各自知道自己是谁，并通过三个钩子交给票据分配器。
 *
 * <p>这里不跑真的分配器：只验证本层给出的答案——候选过滤、到期时刻、派发回调——是对的。
 */
class TimetableSpawnManagerTest {

  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final UUID COMPANY = UUID.randomUUID();
  private static final UUID OPERATOR = UUID.randomUUID();
  private static final UUID LINE = UUID.randomUUID();
  private static final UUID ROUTE = UUID.randomUUID();
  private static final UUID CREATE_ROUTE = UUID.randomUUID();
  private static final UUID RETURN_ROUTE = UUID.randomUUID();
  private static final UUID TIMETABLE = UUID.randomUUID();
  private static final UUID DUTY = UUID.randomUUID();
  private static final UUID TRIP_ONE = UUID.randomUUID();
  private static final UUID TRIP_TWO = UUID.randomUUID();
  private static final Instant DAY = Instant.parse("2026-03-02T00:00:00Z");

  /** 一天之内的四张票按时间依次出现，各带正确的交路意图：CREATE、第 0 班、第 1 班、RETURN。 */
  @Test
  void ticketsCarryTheirDutyIntent() {
    Fixture fixture = fixture();

    List<SpawnTicket> tickets = fixture.pollAll();

    assertEquals(4, tickets.size(), () -> tickets.toString());
    assertEquals(CREATE_ROUTE, tickets.get(0).service().routeId());
    assertEquals(ROUTE, tickets.get(1).service().routeId());
    assertEquals(ROUTE, tickets.get(2).service().routeId());
    assertEquals(RETURN_ROUTE, tickets.get(3).service().routeId());
    assertEquals(DAY.plusSeconds(8 * 3600 - 180), tickets.get(0).dueAt(), "出库票提前走行 + 就绪");
    assertEquals(DAY.plusSeconds(8 * 3600), tickets.get(1).dueAt());
    assertEquals(DAY.plusSeconds(8 * 3600 + 600), tickets.get(2).dueAt());
    assertEquals(DAY.plusSeconds(8 * 3600 + 950), tickets.get(3).dueAt(), "回库票在末班到达 + 折返");
  }

  /** 续班票只接本交路的车：没绑交路的车能接首班，接不了第二班；派发回调把车绑上后就能接了。 */
  @Test
  void continuationTicketWaitsForItsOwnVehicle() {
    Fixture fixture = fixture();
    List<SpawnTicket> tickets = fixture.pollAll();
    SpawnTicket first = tickets.get(1);
    SpawnTicket second = tickets.get(2);

    assertTrue(fixture.manager.acceptsCandidate(first, "train-fresh"), "首班可以接没绑交路的车");
    assertFalse(fixture.manager.acceptsCandidate(second, "train-fresh"), "续班要等本交路的车");

    fixture.manager.onDispatched(first, "train-fresh");

    assertTrue(fixture.manager.acceptsCandidate(second, "train-fresh"), "跑了首班的车就是本交路的车");
    assertFalse(fixture.manager.acceptsCandidate(second, "train-stranger"));
    assertTrue(fixture.manager.acceptsCandidate(mock(SpawnTicket.class), "anyone"), "不是本层的票一律放行");
  }

  /** 重试队列里的票同样到期作废：被 requeue 的出库票过了容差不会再被放出来。 */
  @Test
  void requeuedTicketsExpireToo() {
    Fixture fixture = fixture();
    SpawnTicket create = fixture.pollAll().get(0);
    fixture.logs.clear();

    fixture.manager.requeue(create.delayedUntil(create.dueAt().plusSeconds(10), "depot-busy"));
    List<SpawnTicket> beforeExpiry =
        fixture.manager.pollDueTickets(fixture.provider, create.dueAt().plusSeconds(60));
    fixture.manager.requeue(create.delayedUntil(create.dueAt().plusSeconds(70), "depot-busy"));
    List<SpawnTicket> afterExpiry =
        fixture.manager.pollDueTickets(fixture.provider, create.dueAt().plusSeconds(301));

    assertTrue(beforeExpiry.stream().anyMatch(t -> t.id().equals(create.id())), "容差内照常重试");
    assertTrue(afterExpiry.stream().noneMatch(t -> t.id().equals(create.id())), "过了容差不再放出");
    assertTrue(
        fixture.logs.stream()
            .anyMatch(line -> line.contains("reason=abandoned") && line.contains("CREATE")),
        () -> fixture.logs.toString());
    assertTrue(fixture.manager.expiryOf(create).isEmpty(), "作废后不再跟踪");
  }

  /** 跨零点：00:20 那班的交路意图用服务日，与前一晚出库票绑的车对得上。 */
  @Test
  void overnightTripsShareTheServiceDayWithTheirCreateLeg() {
    Fixture fixture = fixture(overnightTimetable());
    fixture.manager.pollDueTickets(fixture.provider, DAY.plusSeconds(22 * 3600));
    List<SpawnTicket> tickets =
        new ArrayList<>(
            fixture.manager.pollDueTickets(fixture.provider, DAY.plusSeconds(26 * 3600)));
    tickets.sort(
        java.util.Comparator.comparing(SpawnTicket::dueAt)
            .thenComparingLong(SpawnTicket::sequenceNumber));
    SpawnTicket create = tickets.get(0);
    SpawnTicket lateTrip = tickets.get(1);
    assertEquals(CREATE_ROUTE, create.service().routeId());
    assertEquals(DAY.plusSeconds(24 * 3600 + 20 * 60), lateTrip.dueAt(), "次日 00:20 发车");

    fixture.manager.onDispatched(create, "train-night");

    assertTrue(
        fixture.manager.acceptsCandidate(lateTrip, "train-night"),
        () -> "前一晚出库的车必须能接次日凌晨的班次: " + fixture.logs);
  }

  /** 到期 = 计划时刻 + assign-tolerance；派发成功后不再有到期。 */
  @Test
  void expiryIsDueAtPlusAssignTolerance() {
    Fixture fixture = fixture();
    SpawnTicket first = fixture.pollAll().get(1);

    assertEquals(Optional.of(first.dueAt().plusSeconds(300)), fixture.manager.expiryOf(first));
    fixture.manager.onDispatched(first, "train-1");
    fixture.manager.complete(first);
    assertTrue(fixture.manager.expiryOf(first).isEmpty());
    assertTrue(fixture.manager.expiryOf(mock(SpawnTicket.class)).isEmpty(), "不是本层的票没有到期");
  }

  /**
   * 续班票与回库票等本交路的车：交路有车在路上就不到期，晚点就晚发；首班与出库票照常到期。
   *
   * <p>本层同时如实回答"这张票还在不在等车"：派出去之后就不在了。
   */
  @Test
  void aContinuationTicketDoesNotExpireWhileItsVehicleIsOnTheWay() {
    Fixture fixture = fixture();
    List<SpawnTicket> tickets = fixture.pollAll();
    SpawnTicket create = tickets.get(0);
    SpawnTicket first = tickets.get(1);
    SpawnTicket second = tickets.get(2);
    SpawnTicket ret = tickets.get(3);
    TimetableService.TicketIntent secondIntent =
        new TimetableService.TicketIntent(
            TIMETABLE,
            DUTY,
            java.time.LocalDate.of(2026, 3, 2),
            org.fetarute.fetaruteTCAddon.company.model.RouteOperationType.OPERATION,
            1);

    assertEquals(
        Optional.of(second.dueAt().plusSeconds(300)),
        fixture.manager.expiryOf(second),
        "交路还没有车：照常到期");

    fixture.manager.onDispatched(create, "train-1");

    assertTrue(fixture.manager.expiryOf(second).isEmpty(), "车在出库走行上：续班票等它");
    assertTrue(fixture.manager.expiryOf(ret).isEmpty(), "回库票同样等它");
    assertEquals(
        Optional.of(first.dueAt().plusSeconds(300)), fixture.manager.expiryOf(first), "首班照常到期");
    assertTrue(fixture.manager.hasPendingTicket(secondIntent));

    fixture.manager.onDispatched(second, "train-1");

    assertFalse(fixture.manager.hasPendingTicket(secondIntent), "派出去就不再等车");
    assertFalse(fixture.manager.hasPendingTicket(null));

    TimetableService.TicketIntent returnIntent =
        new TimetableService.TicketIntent(
            TIMETABLE,
            DUTY,
            java.time.LocalDate.of(2026, 3, 2),
            org.fetarute.fetaruteTCAddon.company.model.RouteOperationType.RETURN,
            0);
    assertTrue(fixture.manager.hasPendingTicket(returnIntent));
    fixture.manager.complete(ret);
    assertFalse(fixture.manager.hasPendingTicket(returnIntent), "作废（没派出就完成）也不再等车");
  }

  /** 出库票派发成功后，实体车立刻绑到交路上——它就是这个 duty 的车，后面的运营票只认它。 */
  @Test
  void createDispatchBindsTheTrainToTheDuty() {
    Fixture fixture = fixture();
    SpawnTicket create = fixture.pollAll().get(0);

    fixture.manager.onDispatched(create, "train-out-of-depot");

    assertEquals(
        Optional.of(
            new TimetableService.DutyKey(TIMETABLE, DUTY, java.time.LocalDate.of(2026, 3, 2))),
        fixture.service.dutyBindingOf("train-out-of-depot"));
  }

  /** 没派出去就被放弃的票会留下 abandoned 记录；派出去的不会。 */
  @Test
  void abandonedTicketsAreLogged() {
    Fixture fixture = fixture();
    List<SpawnTicket> tickets = fixture.pollAll();
    fixture.logs.clear();

    fixture.manager.onDispatched(tickets.get(1), "train-1");
    fixture.manager.complete(tickets.get(1));
    fixture.manager.complete(tickets.get(2));

    assertEquals(
        1,
        fixture.logs.stream().filter(line -> line.contains("reason=abandoned")).count(),
        () -> fixture.logs.toString());
    assertTrue(
        fixture.logs.stream()
            .anyMatch(line -> line.contains("reason=abandoned") && line.contains("tripIndex=1")),
        () -> fixture.logs.toString());
  }

  /** 放弃的运营票登记为整趟取消；派出去的运营票、放弃的走行票都不算。 */
  @Test
  void abandonedOperationTicketCancelsItsTrip() {
    Fixture fixture = fixture();
    List<SpawnTicket> tickets = fixture.pollAll();
    List<org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TripCancellations.Cancellation>
        cancelled = new ArrayList<>();
    fixture.service.setCancellationListener(cancelled::add);
    java.time.LocalDate date = java.time.LocalDate.of(2026, 3, 2);

    fixture.manager.complete(tickets.get(0));
    fixture.manager.onDispatched(tickets.get(1), "train-1");
    fixture.manager.complete(tickets.get(1));
    fixture.manager.complete(tickets.get(2));

    assertEquals(1, cancelled.size(), () -> cancelled.toString());
    assertEquals(TRIP_TWO, cancelled.get(0).tripId());
    assertEquals(
        org.fetarute
            .fetaruteTCAddon
            .dispatcher
            .schedule
            .timetable
            .TripCancellations
            .Reason
            .NOT_DISPATCHED,
        cancelled.get(0).reason());
    assertEquals("ticket-abandoned", cancelled.get(0).detail());
    assertTrue(fixture.service.cancellationOf(TIMETABLE, TRIP_ONE, date).isEmpty());
    assertTrue(fixture.service.cancellationOf(TIMETABLE, TRIP_TWO, date).isPresent());
  }

  /**
   * 出库票在车库口重试期间，重启后留下的车在首站接下了这个交路：出库票作废，不再出第二辆车。
   *
   * <p>2026-09-27 实服就是这个顺序——出库票被车库咽喉挡了一分钟，这期间留下的车绑走了那一班；出库票随后照发，
   * 同一交路两辆车，后出的那辆从此抢下一班。续班票不受影响：它只接本交路的车，不会多出车。
   */
  @Test
  void createTicketIsDroppedOnceItsDutyHasATrain() {
    Fixture fixture = fixture();
    List<SpawnTicket> tickets = fixture.pollAll();
    SpawnTicket create = tickets.get(0);
    SpawnTicket continuation = tickets.get(2);
    Instant now = DAY.plusSeconds(8 * 3600 + 5);
    fixture.service.scheduledDepartureAt(
        new StationStopEvent("train-restored", Optional.of(ROUTE), "R1", 0, 2, "OP:S:AAA:1", now));
    fixture.logs.clear();

    fixture.manager.requeue(create.delayedUntil(now, "depot-busy"));
    fixture.manager.requeue(continuation.delayedUntil(now, "waiting-vehicle"));
    List<SpawnTicket> released =
        fixture.manager.pollDueTickets(fixture.provider, now.plusSeconds(5));

    assertTrue(released.stream().noneMatch(t -> t.id().equals(create.id())), "交路已有车，不再出库");
    assertTrue(released.stream().anyMatch(t -> t.id().equals(continuation.id())), "续班票照常放出");
    assertTrue(
        fixture.logs.stream()
            .anyMatch(
                line ->
                    line.contains("reason=duty-already-running")
                        && line.contains("kind=CREATE")
                        && line.contains("train=train-restored")),
        () -> fixture.logs.toString());
    assertTrue(
        fixture.logs.stream().noneMatch(line -> line.contains("reason=abandoned")),
        () -> "作废原因必须是交路已有车，不能混进超时：" + fixture.logs);
    assertTrue(fixture.manager.expiryOf(create).isEmpty(), "作废后不再跟踪");
  }

  /** 首班本身从车库始发（没有出库走行）时，首班票就是出库票：交路已有车同样作废。 */
  @Test
  void depotFirstTripTicketIsDroppedOnceItsDutyHasATrain() {
    Fixture fixture = fixture(depotStartTimetable());
    List<SpawnTicket> tickets = fixture.pollAll();
    SpawnTicket firstTrip = tickets.get(0);
    assertEquals(ROUTE, firstTrip.service().routeId(), () -> tickets.toString());
    Instant now = DAY.plusSeconds(8 * 3600 + 5);
    fixture.service.scheduledDepartureAt(
        new StationStopEvent("train-restored", Optional.of(ROUTE), "R1", 0, 2, "OP:S:AAA:1", now));

    fixture.manager.requeue(firstTrip.delayedUntil(now, "depot-busy"));
    List<SpawnTicket> released =
        fixture.manager.pollDueTickets(fixture.provider, now.plusSeconds(5));

    assertTrue(
        released.stream().noneMatch(t -> t.id().equals(firstTrip.id())),
        () -> fixture.logs.toString());
  }

  // ------------------------------------------------------------------ 夹具

  /** 同一份表，但交路没有出库走行：首班 route 本身从车库始发，首班票就是出库票。 */
  private static Timetable depotStartTimetable() {
    Timetable base = timetable();
    VehicleDuty duty = base.duties().get(0);
    return base.withTripsAndDuties(
        base.trips(),
        List.of(
            new VehicleDuty(
                duty.id(),
                duty.timetableId(),
                duty.sequence(),
                duty.dutyCode(),
                duty.startDepotNodeId(),
                duty.endDepotNodeId(),
                Optional.empty(),
                duty.returnRouteId(),
                duty.tripIds(),
                8 * 3600,
                duty.returnSecondOfDay(),
                duty.plannedEndSecondOfDay(),
                duty.closeReason())));
  }

  /**
   * 交路换车：车晚 460 秒到终点 CCC（超过容差 + 追赶余量），替补车现在出库还赶得上第二班（08:10 发、08:15 前），交路空出来；
   * 下一次轮询发出替补出库票，派发时新车绑上交路。替补票作废则下一轮再发。
   */
  @Test
  void aVacantDutyGetsAReplacementCreateTicket() {
    java.util.concurrent.atomic.AtomicReference<Instant> clock =
        new java.util.concurrent.atomic.AtomicReference<>(DAY.plusSeconds(8 * 3600 + 5));
    List<String> logs = new ArrayList<>();
    TimetableService service = new TimetableService(clock::get, logs::add);
    service.applySettings(
        new TimetableService.Settings(
            true, true, Duration.ofSeconds(120), Duration.ofSeconds(300), Duration.ofHours(24)));
    StorageProvider provider = mock(StorageProvider.class);
    TimetableRepository repository = mock(TimetableRepository.class);
    when(provider.timetables()).thenReturn(repository);
    when(repository.listPublished()).thenReturn(List.of(timetable()));
    service.reload(provider);
    SpawnManager delegate = mock(SpawnManager.class);
    when(delegate.pollDueTickets(any(), any())).thenReturn(List.of());
    when(delegate.snapshotPlan()).thenReturn(plan());
    TimetableSpawnManager manager = new TimetableSpawnManager(delegate, service, logs::add);

    service.scheduledDepartureAt(stop("train-A", 0, clock.get()));
    clock.set(DAY.plusSeconds(8 * 3600 + 11 * 60 + 30));
    service.observeStop(stop("train-A", 1, clock.get()), false);
    assertTrue(service.dutyBindingOf("train-A").isEmpty(), "晚 460 秒，交路换车");

    List<SpawnTicket> first = manager.pollDueTickets(provider, clock.get());
    assertEquals(1, first.size(), first::toString);
    SpawnTicket replacement = first.get(0);
    assertEquals(CREATE_ROUTE, replacement.service().routeId());
    assertTrue(
        logs.stream()
            .anyMatch(line -> line.contains("TIMETABLE_SPAWN_TICKET kind=CREATE replacement=true")),
        logs::toString);

    manager.complete(replacement);
    List<SpawnTicket> retried = manager.pollDueTickets(provider, clock.get().plusSeconds(5));
    assertEquals(1, retried.size(), "作废之后还赶得上，再发一张");

    manager.onDispatched(retried.get(0), "train-B");
    assertEquals(
        Optional.of(
            new TimetableService.DutyKey(TIMETABLE, DUTY, java.time.LocalDate.of(2026, 3, 2))),
        service.dutyBindingOf("train-B"));
    assertEquals(0, service.vacantDutyCount());
    assertTrue(manager.pollDueTickets(provider, clock.get().plusSeconds(10)).isEmpty(), "空缺已填上");
  }

  private static org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopEvent stop(
      String train, int index, Instant at) {
    return new org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopEvent(
        train, Optional.of(ROUTE), "R1", index, 2, index == 0 ? "OP:S:AAA:1" : "OP:S:CCC:1", at);
  }

  private static Fixture fixture() {
    return fixture(timetable());
  }

  private static Fixture fixture(Timetable published) {
    List<String> logs = new ArrayList<>();
    TimetableService service = new TimetableService(Instant::now, logs::add);
    service.applySettings(
        new TimetableService.Settings(
            true, true, Duration.ofSeconds(120), Duration.ofSeconds(300), Duration.ofHours(24)));
    StorageProvider provider = mock(StorageProvider.class);
    TimetableRepository repository = mock(TimetableRepository.class);
    when(provider.timetables()).thenReturn(repository);
    when(repository.listPublished()).thenReturn(List.of(published));
    service.reload(provider);

    SpawnManager delegate = mock(SpawnManager.class);
    when(delegate.pollDueTickets(any(), any())).thenReturn(List.of());
    when(delegate.snapshotPlan()).thenReturn(plan());
    TimetableSpawnManager manager = new TimetableSpawnManager(delegate, service, logs::add);
    return new Fixture(manager, service, provider, logs);
  }

  private record Fixture(
      TimetableSpawnManager manager,
      TimetableService service,
      StorageProvider provider,
      List<String> logs) {

    /** 先在服务日前一刻轮询建立窗口起点，再一次性取出当天全部票据，按计划时刻排序（分配器也是按时刻处理的）。 */
    List<SpawnTicket> pollAll() {
      manager.pollDueTickets(provider, DAY.plusSeconds(7 * 3600));
      List<SpawnTicket> tickets =
          new ArrayList<>(manager.pollDueTickets(provider, DAY.plusSeconds(10 * 3600)));
      tickets.sort(
          java.util.Comparator.comparing(SpawnTicket::dueAt)
              .thenComparingLong(SpawnTicket::sequenceNumber));
      return tickets;
    }
  }

  private static SpawnPlan plan() {
    return new SpawnPlan(
        Instant.EPOCH,
        List.of(
            service(CREATE_ROUTE, "CRT", "OP:D:DEP:1"),
            service(ROUTE, "R1", "OP:D:DEP:1"),
            service(RETURN_ROUTE, "RET", "OP:D:DEP:1")));
  }

  private static SpawnService service(UUID routeId, String code, String depot) {
    return new SpawnService(
        new SpawnServiceKey(routeId),
        COMPANY,
        "COMP",
        OPERATOR,
        "OP",
        LINE,
        "L1",
        routeId,
        code,
        Duration.ofSeconds(600),
        depot);
  }

  /** 跨零点：窗口 23:00→25:00，唯一一班 00:20 发（取模后 1200 秒），出库票 23:57，服务日是前一天。 */
  private static Timetable overnightTimetable() {
    UUID lateTrip = UUID.randomUUID();
    Timetable base = timetable();
    int departure = 24 * 3600 + 20 * 60;
    return new Timetable(
        TIMETABLE,
        COMPANY,
        OPERATOR,
        LINE,
        "NIGHT",
        "跨零点",
        TimetableStatus.PUBLISHED,
        ZONE,
        23 * 3600,
        25 * 3600,
        base.routePlans(),
        List.of(
            new TimetableTrip(lateTrip, TIMETABLE, ROUTE, 0, "R1-001", 20 * 60, Optional.of(DUTY))),
        List.of(
            new VehicleDuty(
                DUTY,
                TIMETABLE,
                0,
                "D001",
                "OP:D:DEP:1",
                "OP:D:DEP:1",
                Optional.of(CREATE_ROUTE),
                Optional.of(RETURN_ROUTE),
                List.of(lateTrip),
                departure - 180,
                departure + 230 + 120,
                departure + 230 + 120 + 90,
                VehicleDuty.CloseReason.HORIZON_END)),
        Optional.empty(),
        DAY,
        DAY);
  }

  /** 两班车、一个交路：出库 07:57，08:00 与 08:10 发，08:15:50 发回库票。 */
  private static Timetable timetable() {
    List<TimetableStop> stops =
        List.of(
            new TimetableStop(
                0, Optional.of("AAA"), Optional.of("OP:S:AAA:1"), 0, 0, RouteStopPassType.STOP),
            new TimetableStop(
                1,
                Optional.of("CCC"),
                Optional.of("OP:S:CCC:1"),
                230,
                230,
                RouteStopPassType.STOP));
    return new Timetable(
        TIMETABLE,
        COMPANY,
        OPERATOR,
        LINE,
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
                stops,
                "OP:S:AAA:1",
                "OP:S:CCC:1",
                Optional.empty(),
                Optional.empty()),
            new TimetableRoutePlan(
                CREATE_ROUTE,
                "CRT",
                RouteOperationType.CREATE,
                0,
                List.of(
                    new TimetableStop(
                        0,
                        Optional.empty(),
                        Optional.of("OP:D:DEP:1"),
                        0,
                        0,
                        RouteStopPassType.STOP),
                    new TimetableStop(
                        1,
                        Optional.of("AAA"),
                        Optional.of("OP:S:AAA:1"),
                        60,
                        60,
                        RouteStopPassType.STOP)),
                "OP:D:DEP:1",
                "OP:S:AAA:1",
                Optional.empty(),
                Optional.empty()),
            new TimetableRoutePlan(
                RETURN_ROUTE,
                "RET",
                RouteOperationType.RETURN,
                0,
                List.of(
                    new TimetableStop(
                        0,
                        Optional.of("CCC"),
                        Optional.of("OP:S:CCC:1"),
                        0,
                        0,
                        RouteStopPassType.STOP),
                    new TimetableStop(
                        1,
                        Optional.empty(),
                        Optional.of("OP:D:DEP:1"),
                        90,
                        90,
                        RouteStopPassType.STOP)),
                "OP:S:CCC:1",
                "OP:D:DEP:1",
                Optional.empty(),
                Optional.empty())),
        List.of(
            new TimetableTrip(TRIP_ONE, TIMETABLE, ROUTE, 0, "R1-001", 8 * 3600, Optional.of(DUTY)),
            new TimetableTrip(
                TRIP_TWO, TIMETABLE, ROUTE, 1, "R1-002", 8 * 3600 + 600, Optional.of(DUTY))),
        List.of(
            new VehicleDuty(
                DUTY,
                TIMETABLE,
                0,
                "D001",
                "OP:D:DEP:1",
                "OP:D:DEP:1",
                Optional.of(CREATE_ROUTE),
                Optional.of(RETURN_ROUTE),
                List.of(TRIP_ONE, TRIP_TWO),
                8 * 3600 - 180,
                8 * 3600 + 600 + 230 + 120,
                8 * 3600 + 600 + 230 + 120 + 90,
                VehicleDuty.CloseReason.MAX_TRIPS)),
        Optional.empty(),
        DAY,
        DAY);
  }
}
