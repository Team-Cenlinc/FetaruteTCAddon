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

  /** 驾驶员接车按票找车次：出库票是交路首班，运营票是自己那一班，回库票与别层的票没有。 */
  @Test
  void pickupTripFollowsTheTicketIntent() {
    Fixture fixture = fixture();
    List<SpawnTicket> tickets = fixture.pollAll();

    TimetableService.DueTrip viaCreate = fixture.manager.pickupTripOf(tickets.get(0)).orElseThrow();
    TimetableService.DueTrip first = fixture.manager.pickupTripOf(tickets.get(1)).orElseThrow();
    assertEquals("R1-001", viaCreate.trip().tripCode());
    assertEquals("R1-001", first.trip().tripCode());
    assertEquals(first.serviceDate(), viaCreate.serviceDate(), "出库票找到的车次与运营票同一天");
    assertEquals(
        "R1-002", fixture.manager.pickupTripOf(tickets.get(2)).orElseThrow().trip().tripCode());
    assertTrue(fixture.manager.pickupTripOf(tickets.get(3)).isEmpty());
    assertTrue(fixture.manager.pickupTripOf(mock(SpawnTicket.class)).isEmpty());
  }

  /** 交路首班有出库走行：列车从交路的车库出车；续班由终点站待命车接，不从车库出车。 */
  @Test
  void depotOriginIsTheDutysStartDepotForItsFirstTrip() {
    Fixture fixture = fixture();

    assertEquals(Optional.of("OP:D:DEP:1"), fixture.service().depotOriginOf(TIMETABLE, "R1-001"));
    assertTrue(fixture.service().depotOriginOf(TIMETABLE, "R1-002").isEmpty());
    assertTrue(fixture.service().depotOriginOf(UUID.randomUUID(), "R1-001").isEmpty());
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

  /** 已取消的车次不会开出，站牌预测里不再出现；站牌与站台屏从时刻表的取消标记显示它。 */
  @Test
  void cancelledTripsAreLeftOutOfTheForecast() {
    Fixture fixture = fixture();
    List<SpawnTicket> tickets = fixture.pollAll();
    fixture.manager.complete(tickets.get(2));

    List<SpawnTicket> forecast =
        fixture.manager.snapshotForecast(DAY.plusSeconds(8 * 3600 - 60), Duration.ofMinutes(20), 5);

    List<UUID> routes = forecast.stream().map(ticket -> ticket.service().routeId()).toList();
    assertEquals(1, routes.stream().filter(ROUTE::equals).count(), () -> "只剩第 0 班: " + forecast);
    assertEquals(
        DAY.plusSeconds(8 * 3600),
        forecast.stream()
            .filter(ticket -> ROUTE.equals(ticket.service().routeId()))
            .findFirst()
            .orElseThrow()
            .dueAt());
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

  /** 最早提前量：测试统一 15 分钟。 */
  private static final Duration MAX_LEAD = Duration.ofMinutes(15);

  /** 挑股道：车库空着，用命令里指定的那条。 */
  private static final EarlySpawnPlan.TrackChooser REQUESTED_TRACK =
      plan -> EarlySpawnYard.Decision.use(plan.requestedNode());

  /** 07:57 出库走行的计划时刻。 */
  private static final Instant CREATE_DEPARTURE = DAY.plusSeconds(8 * 3600 - 180);

  /** 手动提前出车：下一班出库走行现在就出票（计划时刻不变、来源记为手动、出库点为挑出的股道），正点时不再出第二张；再提前一次说下一班已出车，不往后找。 */
  @Test
  void anEarlySpawnIssuesTheNextDepotDepartureOnce() {
    Fixture fixture = fixture();
    Instant now = DAY.plusSeconds(7 * 3600 + 50 * 60);
    fixture.manager.pollDueTickets(fixture.provider, now);

    TimetableSpawnManager.EarlySpawn early =
        fixture.manager.issueEarly(
            CREATE_ROUTE, "OP:D:DEP:1", now, Duration.ofMinutes(60), MAX_LEAD, REQUESTED_TRACK);

    assertEquals(TimetableSpawnManager.EarlySpawnOutcome.ISSUED, early.outcome());
    assertEquals("R1-001", early.tripCode(), "出库走行报它要去接的那一班");
    assertEquals(Optional.of(CREATE_DEPARTURE), early.plannedDeparture());
    assertEquals(Optional.of("OP:D:DEP:1"), early.depotNode());
    List<SpawnTicket> released =
        fixture.manager.pollDueTickets(fixture.provider, now.plusSeconds(1));
    assertEquals(1, released.size(), () -> released.toString());
    SpawnTicket ticket = released.get(0);
    assertEquals(CREATE_ROUTE, ticket.service().routeId());
    assertEquals(CREATE_DEPARTURE, ticket.dueAt(), "计划时刻不变：出车后扣到这个时刻");
    assertFalse(ticket.notBefore().isAfter(now.plusSeconds(1)), "现在就放出去出车");
    assertEquals(
        org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource.MANUAL, ticket.source());
    assertEquals(Optional.of("OP:D:DEP:1"), ticket.selectedDepotNodeId());
    assertTrue(fixture.manager.pickupTripOf(ticket).isPresent(), "带着交路意图：派出即绑交路");

    List<SpawnTicket> atPlanned =
        fixture.manager.pollDueTickets(fixture.provider, CREATE_DEPARTURE.plusSeconds(10));
    assertTrue(
        atPlanned.stream().noneMatch(t -> CREATE_ROUTE.equals(t.service().routeId())),
        () -> "同一班不出第二张：" + atPlanned);

    TimetableSpawnManager.EarlySpawn again =
        fixture.manager.issueEarly(
            CREATE_ROUTE, "OP:D:DEP:1", now, Duration.ofMinutes(60), MAX_LEAD, REQUESTED_TRACK);
    assertEquals(TimetableSpawnManager.EarlySpawnOutcome.ALREADY_OUT, again.outcome(), "只提前下一班");
    assertEquals("R1-001", again.tripCode());
    assertEquals(Optional.of(CREATE_DEPARTURE), again.plannedDeparture());
  }

  /** 交路已经有车在跑（例如提前出的车已经派出），下一班同样算已出车。 */
  @Test
  void anEarlySpawnRefusesWhenTheNextDutyAlreadyHasATrain() {
    Fixture fixture = fixture();
    Instant now = DAY.plusSeconds(7 * 3600 + 50 * 60);
    fixture.service.bindDuty(
        "train-A",
        new TimetableService.DutyKey(TIMETABLE, DUTY, java.time.LocalDate.of(2026, 3, 2)),
        "test");

    TimetableSpawnManager.EarlySpawn early =
        fixture.manager.issueEarly(
            CREATE_ROUTE, "OP:D:DEP:1", now, Duration.ofMinutes(60), MAX_LEAD, REQUESTED_TRACK);

    assertEquals(TimetableSpawnManager.EarlySpawnOutcome.ALREADY_OUT, early.outcome());
    assertTrue(fixture.manager.pollDueTickets(fixture.provider, now.plusSeconds(1)).isEmpty());
  }

  /** 离计划发车超过最早提前量：不出票，告诉最早什么时候可以提前出。 */
  @Test
  void anEarlySpawnRefusesBeforeTheLeadWindow() {
    Fixture fixture = fixture();
    Instant now = DAY.plusSeconds(7 * 3600 + 30 * 60);
    fixture.manager.pollDueTickets(fixture.provider, now);
    List<EarlySpawnPlan> asked = new ArrayList<>();

    TimetableSpawnManager.EarlySpawn early =
        fixture.manager.issueEarly(
            CREATE_ROUTE,
            "OP:D:DEP:1",
            now,
            Duration.ofMinutes(60),
            MAX_LEAD,
            plan -> {
              asked.add(plan);
              return EarlySpawnYard.Decision.use(plan.requestedNode());
            });

    assertEquals(TimetableSpawnManager.EarlySpawnOutcome.TOO_EARLY, early.outcome());
    assertEquals("R1-001", early.tripCode());
    assertEquals(Optional.of(CREATE_DEPARTURE), early.plannedDeparture());
    assertTrue(asked.isEmpty(), "还太早时不去挑股道");
    assertTrue(fixture.manager.pollDueTickets(fixture.provider, now.plusSeconds(1)).isEmpty());
  }

  /** 选不出股道就不出票：原因原样交回；之后照常可以再提前（这一班没有被占住）。 */
  @Test
  void aBlockedEarlySpawnIssuesNothing() {
    Fixture fixture = fixture();
    Instant now = DAY.plusSeconds(7 * 3600 + 50 * 60);
    fixture.manager.pollDueTickets(fixture.provider, now);
    EarlySpawnYard.Blocker blocker =
        new EarlySpawnYard.Blocker(
            EarlySpawnYard.Reason.TRACK_OCCUPIED, "OP:D:DEP:1", Optional.empty());

    TimetableSpawnManager.EarlySpawn early =
        fixture.manager.issueEarly(
            CREATE_ROUTE,
            "OP:D:DEP:1",
            now,
            Duration.ofMinutes(60),
            MAX_LEAD,
            plan -> EarlySpawnYard.Decision.blocked(blocker));

    assertEquals(TimetableSpawnManager.EarlySpawnOutcome.BLOCKED, early.outcome());
    assertEquals(Optional.of(blocker), early.blocker());
    assertEquals(Optional.of(CREATE_DEPARTURE), early.plannedDeparture());
    assertTrue(fixture.manager.pollDueTickets(fixture.provider, now.plusSeconds(1)).isEmpty());
    assertEquals(
        TimetableSpawnManager.EarlySpawnOutcome.ISSUED,
        fixture
            .manager
            .issueEarly(
                CREATE_ROUTE, "OP:D:DEP:1", now, Duration.ofMinutes(60), MAX_LEAD, REQUESTED_TRACK)
            .outcome());
  }

  /** 挑股道拿到的是还没定出库点的票（出库点规范是交路的 CRET 写法）与本车自己之外的计划使用；出库点按挑出的股道写进票。 */
  @Test
  void theTrackChooserSeesAnUnpinnedTicketAndPinsItsChoice() {
    Fixture fixture = fixture();
    Instant now = DAY.plusSeconds(7 * 3600 + 50 * 60);
    fixture.manager.pollDueTickets(fixture.provider, now);
    List<EarlySpawnPlan> asked = new ArrayList<>();

    TimetableSpawnManager.EarlySpawn early =
        fixture.manager.issueEarly(
            CREATE_ROUTE,
            "OP:D:DEP:1",
            now,
            Duration.ofMinutes(60),
            MAX_LEAD,
            plan -> {
              asked.add(plan);
              return EarlySpawnYard.Decision.use("OP:D:DEP:3");
            });

    assertEquals(1, asked.size());
    EarlySpawnPlan plan = asked.get(0);
    assertEquals("OP:D:DEP:1", plan.requestedNode());
    assertEquals(CREATE_DEPARTURE, plan.plannedDeparture());
    assertEquals("R1-001", plan.tripCode());
    assertEquals(Optional.empty(), plan.ticket().selectedDepotNodeId(), "出库点还没定");
    assertEquals("OP:D:DEP:1", plan.ticket().service().depotNodeId());
    assertTrue(
        plan.departures().stream().noneMatch(t -> CREATE_ROUTE.equals(t.service().routeId())),
        () -> "不把自己算成别的车：" + plan.departures());
    assertTrue(plan.arrivals().isEmpty(), "自己的交路回库不算");
    assertEquals(Optional.of("OP:D:DEP:3"), early.depotNode());
    SpawnTicket ticket =
        fixture.manager.pollDueTickets(fixture.provider, now.plusSeconds(1)).get(0);
    assertEquals(Optional.of("OP:D:DEP:3"), ticket.selectedDepotNodeId());
    assertEquals("OP:D:DEP:3", ticket.service().depotNodeId());
  }

  /** 别的车库、不归时刻表发车的线路都不出票：前者说没有车次，后者交回原来的手动出车。 */
  @Test
  void anEarlySpawnOnlyTakesThisDepotsTimetabledDepartures() {
    Fixture fixture = fixture();
    Instant now = DAY.plusSeconds(7 * 3600 + 50 * 60);

    assertEquals(
        TimetableSpawnManager.EarlySpawnOutcome.NONE_UPCOMING,
        fixture
            .manager
            .issueEarly(
                CREATE_ROUTE,
                "OP:D:OTHER:1",
                now,
                Duration.ofMinutes(60),
                MAX_LEAD,
                REQUESTED_TRACK)
            .outcome());
    assertEquals(
        TimetableSpawnManager.EarlySpawnOutcome.NONE_UPCOMING,
        fixture
            .manager
            .issueEarly(
                CREATE_ROUTE,
                "OP:D:DEP:1",
                DAY.plusSeconds(7 * 3600 + 30 * 60),
                Duration.ofMinutes(10),
                MAX_LEAD,
                REQUESTED_TRACK)
            .outcome(),
        "07:57 的出库走行不在 10 分钟内");
    assertEquals(
        TimetableSpawnManager.EarlySpawnOutcome.NOT_TIMETABLED,
        fixture
            .manager
            .issueEarly(
                UUID.randomUUID(),
                "OP:D:DEP:1",
                now,
                Duration.ofMinutes(60),
                MAX_LEAD,
                REQUESTED_TRACK)
            .outcome());

    TimetableSpawnManager.EarlySpawn byPassengerRoute =
        fixture.manager.issueEarly(
            ROUTE, "OP:D:DEP:1", now, Duration.ofMinutes(60), MAX_LEAD, REQUESTED_TRACK);
    assertEquals(
        TimetableSpawnManager.EarlySpawnOutcome.ISSUED,
        byPassengerRoute.outcome(),
        "写首班的载客线路也认成它的出库走行");
    assertEquals(Optional.of(CREATE_DEPARTURE), byPassengerRoute.plannedDeparture());
    assertEquals(
        CREATE_ROUTE,
        fixture
            .manager
            .pollDueTickets(fixture.provider, now.plusSeconds(1))
            .get(0)
            .service()
            .routeId());
  }

  /** 首班线路本身从车库始发（首站就是车库、计划写了出库点）：提前出这一班；同一车库换条股道也认，出库点按指定的股道。 */
  @Test
  void anEarlySpawnTakesADepotStartTripOnAnyTrackOfThatDepot() {
    Timetable base = depotStartTimetable();
    List<TimetableRoutePlan> plans = new ArrayList<>();
    for (TimetableRoutePlan plan : base.routePlans()) {
      plans.add(
          plan.routeId().equals(ROUTE)
              ? new TimetableRoutePlan(
                  ROUTE,
                  "R1",
                  1,
                  plan.stops(),
                  "OP:D:DEP:1",
                  "OP:S:CCC:1",
                  Optional.empty(),
                  Optional.empty())
              : plan);
    }
    Fixture fixture = fixture(base.withPlansTripsAndDuties(plans, base.trips(), base.duties()));
    Instant now = DAY.plusSeconds(7 * 3600 + 50 * 60);

    TimetableSpawnManager.EarlySpawn early =
        fixture.manager.issueEarly(
            ROUTE, "OP:D:DEP:2", now, Duration.ofMinutes(60), MAX_LEAD, REQUESTED_TRACK);

    assertEquals(TimetableSpawnManager.EarlySpawnOutcome.ISSUED, early.outcome());
    assertEquals("R1-001", early.tripCode());
    assertEquals(Optional.of(DAY.plusSeconds(8 * 3600)), early.plannedDeparture());
    SpawnTicket ticket =
        fixture.manager.pollDueTickets(fixture.provider, now.plusSeconds(1)).get(0);
    assertEquals(Optional.of("OP:D:DEP:2"), ticket.selectedDepotNodeId());
    assertEquals("OP:D:DEP:2", ticket.service().depotNodeId());
  }

  /** 按表回库：交路的计划到达车库时刻落在窗口里才算，回库线路取回库走行。 */
  @Test
  void depotArrivalsComeFromTheDutyEnd() {
    Fixture fixture = fixture();
    Instant end = DAY.plusSeconds(8 * 3600 + 600 + 230 + 120 + 90);

    List<TimetableService.DueArrival> arrivals =
        fixture.service.depotArrivalsBetween(DAY.plusSeconds(8 * 3600), end);

    assertEquals(1, arrivals.size(), arrivals::toString);
    assertEquals("D001", arrivals.get(0).duty().dutyCode());
    assertEquals(end, arrivals.get(0).at());
    assertEquals(Optional.of(RETURN_ROUTE), arrivals.get(0).routeId());
    assertTrue(fixture.service.depotArrivalsBetween(end, end.plusSeconds(600)).isEmpty(), "窗口起点不含");
  }

  /** 重启找回：提前出车的票带着交路意图，写法读回来相同；按它绑回交路后，这一班到点不再出车。 */
  @Test
  void anEarlyHoldRebindsItsDutyAfterARestart() {
    Fixture fixture = fixture();
    Instant now = DAY.plusSeconds(7 * 3600 + 50 * 60);
    fixture.manager.pollDueTickets(fixture.provider, now);
    fixture.manager.issueEarly(
        CREATE_ROUTE, "OP:D:DEP:1", now, Duration.ofMinutes(60), MAX_LEAD, REQUESTED_TRACK);
    SpawnTicket ticket =
        fixture.manager.pollDueTickets(fixture.provider, now.plusSeconds(1)).get(0);
    String token = fixture.manager.earlyHoldToken(ticket).orElseThrow();
    assertEquals(
        Optional.of(token),
        TimetableSpawnManager.parseIntent(token).map(TimetableSpawnManager::formatIntent));

    Fixture restarted = fixture();
    restarted.manager.pollDueTickets(restarted.provider, now.plusSeconds(30));
    assertTrue(restarted.manager.restoreEarlyHold("train-A", token));
    assertEquals(
        Optional.of(
            new TimetableService.DutyKey(TIMETABLE, DUTY, java.time.LocalDate.of(2026, 3, 2))),
        restarted.service.dutyBindingOf("train-A"));
    List<SpawnTicket> atPlanned =
        restarted.manager.pollDueTickets(restarted.provider, CREATE_DEPARTURE.plusSeconds(10));
    assertTrue(
        atPlanned.stream().noneMatch(t -> CREATE_ROUTE.equals(t.service().routeId())),
        () -> "绑回交路后不再出第二辆：" + atPlanned);
    assertFalse(restarted.manager.restoreEarlyHold("train-B", token), "交路已有车时不绑");
    assertFalse(restarted.manager.restoreEarlyHold("train-C", "bad"), "写法不对时不绑");
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

  /** 区分车型的表：出库票与运营票带交路的车型，回库票不出车、不带；没绑交路的车接首班要车型相同。 */
  @Test
  void ticketsCarryTheDutyConsist() {
    Fixture fixture = fixture(withDutyConsist(timetable(), "m8"));
    fixture
        .service()
        .setConsistOfTrain(name -> Optional.of(name.startsWith("eight") ? "m8" : "m6"));

    List<SpawnTicket> tickets = fixture.pollAll();

    assertEquals(Optional.of("m8"), tickets.get(0).consist(), "出库票出交路的车型");
    assertEquals(Optional.of("m8"), tickets.get(1).consist());
    assertEquals(Optional.of("m8"), tickets.get(2).consist());
    assertEquals(Optional.empty(), tickets.get(3).consist(), "回库票不出车");
    assertTrue(fixture.manager.acceptsCandidate(tickets.get(1), "eight-1"));
    assertFalse(fixture.manager.acceptsCandidate(tickets.get(1), "six-1"), "别的车型接不了首班");
    assertEquals(Optional.of("m8"), tickets.get(0).withRetry(DAY, "retry").consist(), "重试票保留车型");
  }

  private static Timetable withDutyConsist(Timetable source, String consist) {
    List<VehicleDuty> duties = new ArrayList<>();
    for (VehicleDuty duty : source.duties()) {
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
              Optional.of(consist)));
    }
    return source.withTripsAndDuties(source.trips(), duties);
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
