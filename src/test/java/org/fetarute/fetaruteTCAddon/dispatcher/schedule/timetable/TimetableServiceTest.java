package org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.repository.TimetableRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.Test;

/**
 * 按表运行的运行时语义：车次绑定、计划时刻换算，以及车辆交路边界的执行。
 *
 * <p>最后一组是这一轮的重点：一辆车跑完自己交路里的最后一班之后，<b>即使下一班完全接得上</b>， 也必须被拒绝复用。这条否决是"每辆车最终都会回库"不被"恰好还有下一班"绕过的运行期保证。
 */
class TimetableServiceTest {

  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final UUID COMPANY = UUID.randomUUID();
  private static final UUID OPERATOR = UUID.randomUUID();
  private static final UUID LINE = UUID.randomUUID();
  private static final UUID ROUTE = UUID.randomUUID();
  private static final UUID CREATE_ROUTE = UUID.randomUUID();
  private static final UUID RETURN_ROUTE = UUID.randomUUID();
  private static final UUID TIMETABLE = UUID.randomUUID();

  /** 两班车、一个只能跑两班的交路。 */
  private static Timetable timetable(TimetableStatus status) {
    UUID dutyId = UUID.randomUUID();
    UUID tripOne = UUID.randomUUID();
    UUID tripTwo = UUID.randomUUID();
    List<TimetableStop> stops =
        List.of(
            new TimetableStop(0, Optional.of("AAA"), Optional.of("OP:S:AAA:1"), 0, 0),
            new TimetableStop(1, Optional.of("BBB"), Optional.of("OP:S:BBB:1"), 100, 130),
            new TimetableStop(2, Optional.of("CCC"), Optional.of("OP:S:CCC:1"), 230, 230));
    return new Timetable(
        TIMETABLE,
        COMPANY,
        OPERATOR,
        LINE,
        "TT1",
        "测试表",
        status,
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
                    new TimetableStop(0, Optional.empty(), Optional.of("OP:D:DEP:1"), 0, 0),
                    new TimetableStop(1, Optional.of("AAA"), Optional.of("OP:S:AAA:1"), 60, 60)),
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
                    new TimetableStop(0, Optional.of("CCC"), Optional.of("OP:S:CCC:1"), 0, 0),
                    new TimetableStop(1, Optional.empty(), Optional.of("OP:D:DEP:1"), 90, 90)),
                "OP:S:CCC:1",
                "OP:D:DEP:1",
                Optional.empty(),
                Optional.empty())),
        List.of(
            new TimetableTrip(
                tripOne, TIMETABLE, ROUTE, 0, "R1-001", 8 * 3600, Optional.of(dutyId)),
            new TimetableTrip(
                tripTwo, TIMETABLE, ROUTE, 1, "R1-002", 8 * 3600 + 600, Optional.of(dutyId))),
        List.of(
            // 出库票 07:57（走行 60 秒 + 就绪 120 秒），回库票 08:14（末班 08:10 发、230 秒到、折返 120 秒后）。
            new VehicleDuty(
                dutyId,
                TIMETABLE,
                0,
                "D001",
                "OP:D:DEP:1",
                "OP:D:DEP:1",
                Optional.of(CREATE_ROUTE),
                Optional.of(RETURN_ROUTE),
                List.of(tripOne, tripTwo),
                8 * 3600 - 180,
                8 * 3600 + 600 + 230 + 120,
                8 * 3600 + 600 + 230 + 120 + 90,
                VehicleDuty.CloseReason.MAX_TRIPS)),
        Optional.empty(),
        Instant.parse("2026-03-01T00:00:00Z"),
        Instant.parse("2026-03-01T00:00:00Z"));
  }

  private static TimetableService service(boolean enabled, Timetable... published) {
    return service(enabled, new ArrayList<>(), published);
  }

  private static TimetableService service(
      boolean enabled, List<String> logs, Timetable... published) {
    TimetableService service = new TimetableService(Instant::now, logs::add);
    service.applySettings(
        new TimetableService.Settings(
            enabled,
            enabled,
            Duration.ofSeconds(120),
            Duration.ofSeconds(300),
            Duration.ofSeconds(300),
            ZONE));
    service.reload(providerWith(published));
    return service;
  }

  private static StorageProvider providerWith(Timetable... published) {
    StorageProvider provider = mock(StorageProvider.class);
    TimetableRepository repository = mock(TimetableRepository.class);
    when(provider.timetables()).thenReturn(repository);
    when(repository.listPublished()).thenReturn(List.of(published));
    return provider;
  }

  private static StationStopEvent event(String train, int index, Instant at) {
    return new StationStopEvent(train, Optional.of(ROUTE), "R1", index, 3, "OP:S:AAA:1", at);
  }

  /** 首站按时出现：绑定到 08:00 那趟，计划发车就是 08:00。 */
  @Test
  void assignsNearestTripAndReturnsItsPlannedDeparture() {
    TimetableService service = service(true, timetable(TimetableStatus.PUBLISHED));

    Optional<Instant> scheduled =
        service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")));

    assertEquals(Optional.of(Instant.parse("2026-03-02T08:00:00Z")), scheduled);
    assertEquals(Optional.of("R1-001"), service.assignmentOf("train-A").map(a -> a.tripCode()));
  }

  /** 沿途各站用该 route 的时分档案叠加，而不是重新匹配一遍。 */
  @Test
  void laterStopsUseTheRouteProfileOfTheBoundTrip() {
    TimetableService service = service(true, timetable(TimetableStatus.PUBLISHED));
    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")));

    Optional<Instant> scheduled =
        service.scheduledDepartureAt(event("train-A", 1, Instant.parse("2026-03-02T08:01:50Z")));

    assertEquals(Optional.of(Instant.parse("2026-03-02T08:02:10Z")), scheduled, "08:00:00 + 130s");
  }

  /**
   * 交路额度用完后必须拒绝复用。
   *
   * <p>这个交路只有两班。列车跑完第二班时，第三班在时间上完全接得上——正是"总有下一班"的场景。 断言的是：此时复用被否决，列车因此落进回收窗口。
   */
  @Test
  void deniesReuseOnceTheDutyIsExhausted() {
    TimetableService service = service(true, timetable(TimetableStatus.PUBLISHED));

    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")));
    assertTrue(service.allowsLayoverReuse("train-A"), "第一班之后还有额度");

    // 跑完第一班回到起点：重新匹配会选中同一交路的第二班（也是最后一班）。
    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:10:05Z")));
    assertEquals(Optional.of("R1-002"), service.assignmentOf("train-A").map(a -> a.tripCode()));

    assertFalse(
        service.allowsLayoverReuse("train-A"),
        () -> "交路只有两班，跑完第二班就不该再接：" + service.dutyProgressOf("train-A"));
  }

  /** 未启用按表运行时，复用闸完全透明。 */
  @Test
  void reuseGateIsTransparentWhenDisabled() {
    TimetableService service = service(false, timetable(TimetableStatus.PUBLISHED));

    assertTrue(service.allowsLayoverReuse("train-A"));
    assertTrue(service.allowsLayoverReuse("anything"));
  }

  /** 不受时刻表管辖的列车照常可以被复用。 */
  @Test
  void unmanagedTrainsAreAlwaysReusable() {
    TimetableService service = service(true, timetable(TimetableStatus.PUBLISHED));

    assertTrue(service.allowsLayoverReuse("train-never-seen"));
  }

  /** 列车下线后交路进度一并释放，新车可以重新接这个交路。 */
  @Test
  void releasingATrainClearsItsDutyProgress() {
    TimetableService service = service(true, timetable(TimetableStatus.PUBLISHED));
    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")));
    assertTrue(service.dutyProgressOf("train-A").isPresent());

    service.release("train-A", "destroyed");

    assertTrue(service.dutyProgressOf("train-A").isEmpty());
    assertTrue(service.allowsLayoverReuse("train-A"));
  }

  /** retain 清掉不在网的列车绑定与交路进度。 */
  @Test
  void retainDropsGoneTrains() {
    TimetableService service = service(true, timetable(TimetableStatus.PUBLISHED));
    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")));

    service.retain(List.of("train-Z"));

    assertTrue(service.assignments().isEmpty());
    assertTrue(service.dutyProgressOf("train-A").isEmpty());
  }

  /** 未发布的时刻表对运行时不可见。 */
  @Test
  void draftTimetablesAreInvisibleToRuntime() {
    TimetableService service = service(true, timetable(TimetableStatus.DRAFT));

    assertFalse(service.managed(ROUTE));
    assertTrue(
        service
            .scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")))
            .isEmpty());
  }

  /** 下架时刻表后，残留绑定必须一起清掉，否则那趟车会永远"已被占用"。 */
  @Test
  void unpublishReleasesAssignments() {
    TimetableService service = service(true, timetable(TimetableStatus.PUBLISHED));
    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")));
    assertFalse(service.assignments().isEmpty());

    service.reload(providerWith());

    assertTrue(service.assignments().isEmpty());
    assertTrue(service.managedRoutes().isEmpty());
  }

  /** ETA 与公开 API 轮询用的计划时刻查询：只读，不能顺手建立绑定。 */
  @Test
  void plannedTimeQueriesAreReadOnly() {
    TimetableService service = service(true, timetable(TimetableStatus.PUBLISHED));

    assertTrue(service.plannedDepartureOf("train-A", 1).isEmpty());
    assertTrue(service.assignments().isEmpty(), "未绑定时查询不能建立绑定");

    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")));
    assertEquals(
        Optional.of(Instant.parse("2026-03-02T08:02:10Z")),
        service.plannedDepartureOf("TRAIN-A", 1),
        "列车名大小写不敏感");
    assertEquals(
        Optional.of(Instant.parse("2026-03-02T08:01:40Z")), service.plannedArrivalOf("train-a", 1));
    assertEquals(1, service.assignments().size());

    service.applySettings(TimetableService.Settings.disabled());
    assertTrue(service.plannedDepartureOf("train-A", 1).isEmpty());
  }

  /** 总开关关闭时立刻清空绑定。 */
  @Test
  void disablingClearsAssignments() {
    TimetableService service = service(true, timetable(TimetableStatus.PUBLISHED));
    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")));

    service.applySettings(TimetableService.Settings.disabled());

    assertTrue(service.assignments().isEmpty());
    assertTrue(
        service
            .scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")))
            .isEmpty());
  }

  /** 同一趟表定车次不能被两辆车同时占用。 */
  @Test
  void aTripIsClaimedByAtMostOneTrain() {
    TimetableService service = service(true, timetable(TimetableStatus.PUBLISHED));
    Instant now = Instant.parse("2026-03-02T08:00:05Z");

    service.scheduledDepartureAt(event("train-A", 0, now));
    service.scheduledDepartureAt(event("train-B", 0, now.plusSeconds(1)));

    assertEquals(Optional.of("R1-001"), service.assignmentOf("train-A").map(a -> a.tripCode()));
    assertTrue(service.assignmentOf("train-B").isEmpty());
  }

  /** 出票窗口是半开的：同一趟车只会在跨过它的那一次轮询里出现一次。 */
  @Test
  void dueTripsFireExactlyOncePerWindow() {
    TimetableService service = service(true, timetable(TimetableStatus.PUBLISHED));
    Instant before = Instant.parse("2026-03-02T07:59:00Z");
    Instant after = Instant.parse("2026-03-02T08:00:30Z");

    List<TimetableService.DueTrip> first = service.dueTrips(before, after);
    List<TimetableService.DueTrip> second = service.dueTrips(after, after.plusSeconds(60));

    assertEquals(1, first.size());
    assertEquals("R1-001", first.get(0).trip().tripCode());
    assertTrue(second.isEmpty(), "窗口已经越过 08:00，不能再出一次票");
  }

  /** 出库票在首班之前"走行 + 就绪"的时刻发出，回库票在末班到达 + 折返后发出，各只发一次。 */
  @Test
  void dueLegsFireCreateBeforeFirstTripAndReturnAfterLast() {
    TimetableService service = service(true, timetable(TimetableStatus.PUBLISHED));
    Instant dayStart = Instant.parse("2026-03-02T00:00:00Z");

    List<TimetableService.DueLeg> create =
        service.dueLegs(dayStart.plusSeconds(8 * 3600 - 240), dayStart.plusSeconds(8 * 3600 - 120));
    List<TimetableService.DueLeg> ret =
        service.dueLegs(
            dayStart.plusSeconds(8 * 3600 + 900), dayStart.plusSeconds(8 * 3600 + 1000));
    List<TimetableService.DueLeg> again =
        service.dueLegs(dayStart.plusSeconds(8 * 3600), dayStart.plusSeconds(8 * 3600 + 60));

    assertEquals(1, create.size());
    assertEquals(RouteOperationType.CREATE, create.get(0).kind());
    assertEquals(CREATE_ROUTE, create.get(0).routeId());
    assertEquals(dayStart.plusSeconds(8 * 3600 - 180), create.get(0).departure());
    assertEquals(1, ret.size());
    assertEquals(RouteOperationType.RETURN, ret.get(0).kind());
    assertEquals(RETURN_ROUTE, ret.get(0).routeId());
    assertEquals(dayStart.plusSeconds(8 * 3600 + 950), ret.get(0).departure());
    assertTrue(again.isEmpty(), "窗口已经越过出库时刻，不能再出一次票");
  }

  /** 出库/回库线路同样受管辖：它们的 headway 票会被拦下，改由 duty 的走行票驱动。 */
  @Test
  void legRoutesAreManagedTogetherWithTheOperationRoute() {
    TimetableService service = service(true, timetable(TimetableStatus.PUBLISHED));

    assertTrue(service.managed(ROUTE));
    assertTrue(service.managed(CREATE_ROUTE));
    assertTrue(service.managed(RETURN_ROUTE));
  }

  /**
   * 回库闸是复用闸的镜像：交路还没跑完的车不准被回库票带走，跑完了才放行。
   *
   * <p>否则按表发出的回库票会把正等着跑第二班的车送回车库，第二班就开了天窗。
   */
  @Test
  void returnGateKeepsAnUnfinishedDutyOnTheLine() {
    TimetableService service = service(true, timetable(TimetableStatus.PUBLISHED));

    assertTrue(service.allowsReturn("train-never-seen"), "不受管辖的车照常可回库");

    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")));
    assertFalse(service.allowsReturn("train-A"), "交路 1/2，还有一班要跑");

    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:10:05Z")));
    assertTrue(service.allowsReturn("train-A"), "交路 2/2，可以回库");
    assertFalse(service.allowsLayoverReuse("train-A"), "同一时刻运营复用被否决：两道闸互为镜像");
  }

  /**
   * 接班只能接本交路的车：绑在别的交路上的车不接；没绑交路的车能接首班和回库票，不能接续班。
   *
   * <p>这条规则是"接班没车就等，不新出库、不抓别人的车"在判定层的形态。
   */
  @Test
  void continuationTicketsOnlyAcceptTheirOwnDutyVehicle() {
    Timetable timetable = timetable(TimetableStatus.PUBLISHED);
    TimetableService service = service(true, timetable);
    UUID duty = timetable.duties().get(0).id();
    java.time.LocalDate day = java.time.LocalDate.of(2026, 3, 2);
    TimetableService.DutyKey mine = new TimetableService.DutyKey(TIMETABLE, duty, day);
    TimetableService.DutyKey other =
        new TimetableService.DutyKey(TIMETABLE, UUID.randomUUID(), day);
    TimetableService.TicketIntent firstTrip =
        new TimetableService.TicketIntent(TIMETABLE, duty, day, RouteOperationType.OPERATION, 0);
    TimetableService.TicketIntent secondTrip =
        new TimetableService.TicketIntent(TIMETABLE, duty, day, RouteOperationType.OPERATION, 1);
    TimetableService.TicketIntent ret =
        new TimetableService.TicketIntent(TIMETABLE, duty, day, RouteOperationType.RETURN, 0);

    service.bindDuty("train-mine", mine, "test");
    service.bindDuty("train-other", other, "test");

    assertTrue(service.acceptsVehicle(firstTrip, "train-mine"));
    assertTrue(service.acceptsVehicle(secondTrip, "train-mine"));
    assertTrue(service.acceptsVehicle(ret, "train-mine"));
    assertFalse(service.acceptsVehicle(firstTrip, "train-other"), "别的交路的车不能被拐走");
    assertFalse(service.acceptsVehicle(secondTrip, "train-other"));
    assertTrue(service.acceptsVehicle(firstTrip, "train-free"), "没绑交路的车可以接首班");
    assertFalse(service.acceptsVehicle(secondTrip, "train-free"), "续班要等本交路的车，不能凭空接");
    assertFalse(service.acceptsVehicle(ret, "train-free"), "回库票只带本交路的车：抓走陌生车会让本交路跑完的车滞留在终点");
  }

  /**
   * 在起点等点期间门控每秒问一次：绑定必须复用，不能每问一次就解绑重绑。
   *
   * <p>否则一次 150 秒的扣留就是 150 次全表扫描加 300 行日志；真正的 HOLD 行被淹没。
   */
  @Test
  void holdingAtOriginReusesTheAssignmentInsteadOfRebindingEveryPoll() {
    List<String> logs = new ArrayList<>();
    TimetableService service = service(true, logs, timetable(TimetableStatus.PUBLISHED));
    Instant early = Instant.parse("2026-03-02T07:58:30Z");

    service.scheduledDepartureAt(event("train-A", 0, early));
    service.scheduledDepartureAt(event("train-A", 0, early.plusSeconds(1)));
    service.scheduledDepartureAt(event("train-A", 0, early.plusSeconds(2)));

    assertEquals(
        1,
        logs.stream().filter(line -> line.startsWith("TIMETABLE_ASSIGN ")).count(),
        logs::toString);
    assertTrue(logs.stream().noneMatch(line -> line.contains("new-circuit")), logs::toString);
    assertEquals(Optional.of("R1-001"), service.assignmentOf("train-A").map(a -> a.tripCode()));

    // 下一圈回到起点（已经过了首班发车 + 容差）：这时才重新匹配到第二班。
    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:10:05Z")));
    assertEquals(Optional.of("R1-002"), service.assignmentOf("train-A").map(a -> a.tripCode()));
  }

  /**
   * 跨零点的班次：发车时刻取模后落到下一个日历日，但它属于前一个服务日的交路。
   *
   * <p>绑定的 DutyKey 必须用服务日，否则出库票（服务日 D）绑的车与 00:20 那班（日历日 D+1）对不上。
   */
  @Test
  void bindingAcrossMidnightUsesTheServiceDay() {
    UUID dutyId = UUID.randomUUID();
    UUID lateTrip = UUID.randomUUID();
    List<TimetableStop> stops =
        List.of(
            new TimetableStop(0, Optional.of("AAA"), Optional.of("OP:S:AAA:1"), 0, 0),
            new TimetableStop(1, Optional.of("CCC"), Optional.of("OP:S:CCC:1"), 230, 230));
    Timetable overnight =
        new Timetable(
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
            List.of(
                new TimetableRoutePlan(
                    ROUTE,
                    "R1",
                    1,
                    stops,
                    "OP:S:AAA:1",
                    "OP:S:CCC:1",
                    Optional.empty(),
                    Optional.empty())),
            List.of(
                // 00:20 发车：取模后是 1200，早于窗口起点 23:00。
                new TimetableTrip(
                    lateTrip, TIMETABLE, ROUTE, 0, "R1-001", 20 * 60, Optional.of(dutyId))),
            List.of(
                new VehicleDuty(
                    dutyId,
                    TIMETABLE,
                    0,
                    "D001",
                    "OP:D:DEP:1",
                    "OP:D:DEP:1",
                    Optional.empty(),
                    Optional.empty(),
                    List.of(lateTrip),
                    24 * 3600 + 20 * 60 - 300,
                    24 * 3600 + 20 * 60 + 230,
                    24 * 3600 + 20 * 60 + 230,
                    VehicleDuty.CloseReason.HORIZON_END)),
            Optional.empty(),
            Instant.parse("2026-03-01T00:00:00Z"),
            Instant.parse("2026-03-01T00:00:00Z"));
    TimetableService service = service(true, overnight);

    service.scheduledDepartureAt(event("train-N", 0, Instant.parse("2026-03-03T00:20:05Z")));

    assertEquals(
        Optional.of(
            new TimetableService.DutyKey(TIMETABLE, dutyId, java.time.LocalDate.of(2026, 3, 2))),
        service.dutyBindingOf("train-N"),
        "3 月 3 日 00:20 的班次属于 3 月 2 日的服务日");
  }

  /** 门控上首次绑定到带 duty 的车次时，车也随之绑到交路上；下线后解绑。 */
  @Test
  void tripAssignmentBindsTheTrainToItsDuty() {
    Timetable timetable = timetable(TimetableStatus.PUBLISHED);
    TimetableService service = service(true, timetable);
    UUID duty = timetable.duties().get(0).id();

    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")));

    assertEquals(
        Optional.of(
            new TimetableService.DutyKey(TIMETABLE, duty, java.time.LocalDate.of(2026, 3, 2))),
        service.dutyBindingOf("train-A"));
    service.release("train-A", "destroyed");
    assertTrue(service.dutyBindingOf("train-A").isEmpty());
  }

  /** 已经绑在一个交路上的车不会被另一次绑定覆盖：错派的车要暴露出来，不是藏起来。 */
  @Test
  void bindingDoesNotOverwriteAnExistingDuty() {
    TimetableService service = service(true, timetable(TimetableStatus.PUBLISHED));
    java.time.LocalDate day = java.time.LocalDate.of(2026, 3, 2);
    TimetableService.DutyKey first =
        new TimetableService.DutyKey(TIMETABLE, UUID.randomUUID(), day);
    TimetableService.DutyKey second =
        new TimetableService.DutyKey(TIMETABLE, UUID.randomUUID(), day);

    service.bindDuty("train-A", first, "create");
    service.bindDuty("train-A", second, "create-again");

    assertEquals(Optional.of(first), service.dutyBindingOf("train-A"));
  }

  /** 未启用按表运行时判定完全透明。 */
  @Test
  void vehicleAcceptanceIsTransparentWhenDisabled() {
    TimetableService service = service(false, timetable(TimetableStatus.PUBLISHED));
    TimetableService.TicketIntent intent =
        new TimetableService.TicketIntent(
            TIMETABLE,
            UUID.randomUUID(),
            java.time.LocalDate.of(2026, 3, 2),
            RouteOperationType.OPERATION,
            3);

    assertTrue(service.acceptsVehicle(intent, "anything"));
  }

  /**
   * 绑不上车次必须留痕：日志说出最近的车次与偏差，计数不节流，日志一分钟一条。
   *
   * <p>这是"跨线干扰 → 晚点 → 超过容差 → 静默退回自由运行"这条链的可归因点。
   */
  @Test
  void assignMissIsLoggedWithNearestTripAndThrottled() {
    List<String> logs = new ArrayList<>();
    TimetableService service = service(true, logs, timetable(TimetableStatus.PUBLISHED));
    // 08:00 那班晚了 20 分钟才到起点：容差 300 秒，绑不上。
    Instant late = Instant.parse("2026-03-02T08:20:00Z");

    assertTrue(service.scheduledDepartureAt(event("train-late", 0, late)).isEmpty());
    assertTrue(service.scheduledDepartureAt(event("train-late", 0, late.plusSeconds(1))).isEmpty());
    assertTrue(
        service.scheduledDepartureAt(event("train-late", 0, late.plusSeconds(61))).isEmpty());

    List<String> misses =
        logs.stream().filter(line -> line.startsWith("TIMETABLE_ASSIGN_MISS ")).toList();
    assertEquals(2, misses.size(), () -> "同一组合一分钟只记一条: " + misses);
    assertTrue(misses.get(0).contains("nearest=R1-002@600s"), misses.get(0));
    assertTrue(misses.get(0).contains("reason=out-of-tolerance"), misses.get(0));
    assertEquals(3, service.assignMisses(), "计数不节流");
    assertEquals(3, service.status().assignMisses());
  }

  /**
   * 运行到服务窗口结束之后，受管辖列车数必须能降到 0。
   *
   * <p>逐趟推进整份时刻表，每辆车跑完自己的交路后复用即被否决；最后把所有车下线，绑定与交路进度应当清空。 这条断言回答的是"服务结束后网上还会不会留着车"。
   */
  @Test
  void activeTrainsDropToZeroAfterServiceEnds() {
    Timetable timetable = timetable(TimetableStatus.PUBLISHED);
    TimetableService service = service(true, timetable);
    List<String> trains = new ArrayList<>();

    Instant cursor = Instant.parse("2026-03-02T08:00:05Z");
    for (int i = 0; i < timetable.trips().size(); i++) {
      String train = "train-" + i;
      trains.add(train);
      service.scheduledDepartureAt(event(train, 0, cursor));
      cursor = cursor.plusSeconds(600);
    }
    assertFalse(service.assignments().isEmpty());

    // 服务结束：所有车下线。
    for (String train : trains) {
      service.release(train, "end-of-service");
    }
    service.retain(List.of());

    assertTrue(service.assignments().isEmpty(), "服务结束后不应残留任何绑定");
    for (String train : trains) {
      assertTrue(service.dutyProgressOf(train).isEmpty(), () -> train + " 的交路进度没有释放");
    }
  }
}
