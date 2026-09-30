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
import java.util.OptionalLong;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
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

  static final ZoneId ZONE = ZoneId.of("UTC");
  private static final UUID COMPANY = UUID.randomUUID();
  private static final UUID OPERATOR = UUID.randomUUID();
  private static final UUID LINE = UUID.randomUUID();
  static final UUID ROUTE = UUID.randomUUID();
  static final UUID CREATE_ROUTE = UUID.randomUUID();
  private static final UUID RETURN_ROUTE = UUID.randomUUID();
  static final UUID TIMETABLE = UUID.randomUUID();

  /** 两班车、一个只能跑两班的交路。 */
  private static Timetable timetable(TimetableStatus status) {
    return timetable(status, 2);
  }

  /** {@code tripCount} 班车（08:00 起每 10 分钟一班）、一个跑完全部班次的交路。 */
  static Timetable timetable(TimetableStatus status, int tripCount) {
    UUID dutyId = UUID.randomUUID();
    List<TimetableTrip> trips = new ArrayList<>();
    for (int index = 0; index < tripCount; index++) {
      trips.add(
          new TimetableTrip(
              UUID.randomUUID(),
              TIMETABLE,
              ROUTE,
              index,
              String.format("R1-%03d", index + 1),
              8 * 3600 + 600 * index,
              Optional.of(dutyId)));
    }
    int lastDeparture = 8 * 3600 + 600 * (tripCount - 1);
    List<TimetableStop> stops =
        List.of(
            new TimetableStop(
                0, Optional.of("AAA"), Optional.of("OP:S:AAA:1"), 0, 0, RouteStopPassType.STOP),
            new TimetableStop(
                1, Optional.of("BBB"), Optional.of("OP:S:BBB:1"), 100, 130, RouteStopPassType.STOP),
            new TimetableStop(
                2,
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
        trips,
        List.of(
            // 出库票 07:57（走行 60 秒 + 就绪 120 秒），回库票在末班发车 + 230 秒到 + 折返 120 秒后（两班时为 08:14）。
            new VehicleDuty(
                dutyId,
                TIMETABLE,
                0,
                "D001",
                "OP:D:DEP:1",
                "OP:D:DEP:1",
                Optional.of(CREATE_ROUTE),
                Optional.of(RETURN_ROUTE),
                trips.stream().map(TimetableTrip::id).toList(),
                8 * 3600 - 180,
                lastDeparture + 230 + 120,
                lastDeparture + 230 + 120 + 90,
                VehicleDuty.CloseReason.MAX_TRIPS)),
        Optional.empty(),
        Instant.parse("2026-03-01T00:00:00Z"),
        Instant.parse("2026-03-01T00:00:00Z"));
  }

  private static TimetableService service(boolean enabled, Timetable... published) {
    return service(enabled, new ArrayList<>(), published);
  }

  /** 服务时钟与用例里的事件同一天：交路是否还有班次要跑取决于"现在"，墙钟会让 3 月的班次全部显得早已作废。 */
  private static final Instant FIXTURE_NOW = Instant.parse("2026-03-02T08:00:00Z");

  private static TimetableService service(
      boolean enabled, List<String> logs, Timetable... published) {
    TimetableService service = new TimetableService(() -> FIXTURE_NOW, logs::add);
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

  static StorageProvider providerWith(Timetable... published) {
    StorageProvider provider = mock(StorageProvider.class);
    TimetableRepository repository = mock(TimetableRepository.class);
    when(provider.timetables()).thenReturn(repository);
    when(repository.listPublished()).thenReturn(List.of(published));
    return provider;
  }

  static StationStopEvent event(String train, int index, Instant at) {
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
   * 交路断了就放它回库：剩下的班次都过了发车容差，票都已作废，再没人会派这辆车。
   *
   * <p>否则它会被自己交路的回库票以"还有班次"永远拒绝，回收也绕开它，只能在终点等兜底销毁。容差之内（下一班的票还可能派它）照旧不放行。
   */
  @Test
  void returnIsAllowedOnceTheRemainingTripsHaveAllExpired() {
    java.util.concurrent.atomic.AtomicReference<Instant> clock =
        new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-03-02T08:00:05Z"));
    List<String> logs = new ArrayList<>();
    TimetableService service = new TimetableService(clock::get, logs::add);
    service.applySettings(
        new TimetableService.Settings(
            true,
            true,
            Duration.ofSeconds(120),
            Duration.ofSeconds(300),
            Duration.ofSeconds(300),
            ZONE));
    service.reload(providerWith(timetable(TimetableStatus.PUBLISHED)));
    service.scheduledDepartureAt(event("train-A", 0, clock.get()));

    // 第二班 08:10 发，容差 300 秒：08:15:00 之前它的票还可能派这辆车。
    clock.set(Instant.parse("2026-03-02T08:14:59Z"));
    assertFalse(service.allowsReturn("train-A"), "第二班的票还没作废");
    clock.set(Instant.parse("2026-03-02T08:15:01Z"));
    assertTrue(service.allowsReturn("train-A"), "剩下的班次都作废了");
    assertTrue(
        logs.stream().anyMatch(line -> line.startsWith("TIMETABLE_DUTY_CONTINUATION_LOST")),
        logs::toString);
  }

  /**
   * 停在正线折返点的车，下一班作废就放它回送，不等交路末班也作废。
   *
   * <p>三班的交路跑完第一班：第二班 08:10 发、容差 300 秒。08:15 之后第二班的票已作废，第三班从别的端点发车，这辆车停在正线上再也接不上； 在站台上的车照旧按 {@link
   * TimetableService#allowsReturn} 等到末班也作废。
   */
  @Test
  void mainlineTurnbackReturnsOnceTheNextTripIsMissed() {
    java.util.concurrent.atomic.AtomicReference<Instant> clock =
        new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-03-02T08:00:05Z"));
    List<String> logs = new ArrayList<>();
    TimetableService service = new TimetableService(clock::get, logs::add);
    service.applySettings(
        new TimetableService.Settings(
            true,
            true,
            Duration.ofSeconds(120),
            Duration.ofSeconds(300),
            Duration.ofSeconds(300),
            ZONE));
    service.reload(providerWith(timetable(TimetableStatus.PUBLISHED, 3)));
    service.scheduledDepartureAt(event("train-A", 0, clock.get()));

    clock.set(Instant.parse("2026-03-02T08:14:59Z"));
    assertFalse(
        service.allowsReturnFromMainlineTurnback("train-A", Optional.of(ROUTE)), "第二班的票还没作废");

    clock.set(Instant.parse("2026-03-02T08:15:01Z"));
    logs.clear();
    assertTrue(
        service.allowsReturnFromMainlineTurnback("train-A", Optional.of(ROUTE)), "第二班已作废，接不上了");
    assertEquals(1, logs.size(), logs::toString);
    assertTrue(logs.get(0).startsWith("TIMETABLE_DUTY_NEXT_TRIP_MISSED"), "放行时不能先记一条'回库被否决'");
    assertFalse(service.allowsReturn("train-A"), "第三班还没作废：站台上的车照旧留着");
  }

  /**
   * 正线立即回收只管由时刻表出票的交路：自由运行的线路、只扣车不出票的模式下，接哪一班由间隔出票决定，没有交路上的对应关系可判， 照旧交给闲置回收。
   *
   * <p>由时刻表出票的交路上没绑交路的车（例如重启后账本丢了）就是没有对应关系，放行。
   */
  @Test
  void mainlineTurnbackOnlyAppliesToTimetableIssuedRoutes() {
    TimetableService service = service(true, timetable(TimetableStatus.PUBLISHED));

    assertTrue(
        service.allowsReturnFromMainlineTurnback("train-unbound", Optional.of(ROUTE)),
        "按表交路上没绑交路的车");
    assertFalse(
        service.allowsReturnFromMainlineTurnback("train-unbound", Optional.of(UUID.randomUUID())),
        "自由运行的交路");
    assertFalse(
        service.allowsReturnFromMainlineTurnback("train-unbound", Optional.empty()), "不知道跑的是哪条交路");
    assertFalse(
        service(false, timetable(TimetableStatus.PUBLISHED))
            .allowsReturnFromMainlineTurnback("train-unbound", Optional.of(ROUTE)),
        "未启用按表运行");

    TimetableService holdOnly = new TimetableService(() -> FIXTURE_NOW, line -> {});
    holdOnly.applySettings(
        new TimetableService.Settings(
            true,
            false,
            Duration.ofSeconds(120),
            Duration.ofSeconds(300),
            Duration.ofSeconds(300),
            ZONE));
    holdOnly.reload(providerWith(timetable(TimetableStatus.PUBLISHED)));
    assertFalse(
        holdOnly.allowsReturnFromMainlineTurnback("train-unbound", Optional.of(ROUTE)),
        "只扣车不出票：车由间隔出票复用");
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
   * 晚点超过容差、还堵在起点的车：每次问门控都重新匹配，结果仍是手上这一班，就原样保留，不解绑重绑。
   *
   * <p>绑在交路上的车匹配不看容差，所以"过了容差就不是在等点"的判定会让它每秒重新匹配一次；结果一样时若照样解绑再绑， 堵在起点的每辆车每秒刷两行日志。
   */
  @Test
  void lateTrainStuckAtOriginKeepsItsTripWithoutRebinding() {
    List<String> logs = new ArrayList<>();
    TimetableService service = new TimetableService(Instant::now, logs::add);
    service.applySettings(
        new TimetableService.Settings(
            true,
            true,
            Duration.ofSeconds(120),
            Duration.ofSeconds(120),
            Duration.ofSeconds(300),
            ZONE));
    service.reload(providerWith(timetable(TimetableStatus.PUBLISHED)));
    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")));

    // 08:00 那班晚了 180 秒（超过 120 秒容差），下一班 08:10 还差 420 秒：仍是 08:00 那班。
    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:03:00Z")));
    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:03:01Z")));

    assertEquals(Optional.of("R1-001"), service.assignmentOf("train-A").map(a -> a.tripCode()));
    assertEquals(
        1,
        logs.stream().filter(line -> line.startsWith("TIMETABLE_ASSIGN ")).count(),
        logs::toString);
    assertTrue(
        logs.stream().noneMatch(line -> line.startsWith("TIMETABLE_RELEASE")), logs::toString);
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
            new TimetableStop(
                0, Optional.of("AAA"), Optional.of("OP:S:AAA:1"), 0, 0, RouteStopPassType.STOP),
            new TimetableStop(
                1,
                Optional.of("CCC"),
                Optional.of("OP:S:CCC:1"),
                230,
                230,
                RouteStopPassType.STOP));
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

  /**
   * 交路进度按班次在交路里的位置算，折返改名后的车不会从零数起。
   *
   * <p>折返复用常常给车改名，新名字没有进度。按指派次数数的话，这辆跑到第二班（也是最后一班）的车会以为自己才跑了 1/2， 回库票被 {@code allowsReturn}
   * 拒绝，车就滞留在终点。
   */
  @Test
  void dutyProgressFollowsTripPositionAcrossRenames() {
    Timetable timetable = timetable(TimetableStatus.PUBLISHED);
    TimetableService service = service(true, timetable);
    service.bindDuty(
        "train-renamed",
        new TimetableService.DutyKey(
            TIMETABLE, timetable.duties().get(0).id(), java.time.LocalDate.of(2026, 3, 2)),
        "ticket-operation");

    service.scheduledDepartureAt(event("train-renamed", 0, Instant.parse("2026-03-02T08:10:05Z")));

    assertEquals(
        Optional.of("R1-002"), service.assignmentOf("train-renamed").map(a -> a.tripCode()));
    assertEquals(
        Optional.of(2), service.dutyProgressOf("train-renamed").map(p -> p.assignedTrips()));
    assertTrue(service.allowsReturn("train-renamed"), "跑到了交路的最后一班，回库票要能带走它");
    assertFalse(service.allowsLayoverReuse("train-renamed"));
  }

  /**
   * 只有"会新出一辆车"的票才会因为交路已经有车而作废。
   *
   * <p>这个交路有出库走行：出库票会新出一辆车；首班票接的是出库上来的那辆车，续班与回库票同理——它们都必须照常放出， 否则交路自己的车等不到票。
   */
  @Test
  void onlyVehicleCreatingTicketsAreSupersededByARunningDuty() {
    Timetable timetable = timetable(TimetableStatus.PUBLISHED);
    TimetableService service = service(true, timetable);
    UUID duty = timetable.duties().get(0).id();
    java.time.LocalDate day = java.time.LocalDate.of(2026, 3, 2);
    service.bindDuty(
        "train-A", new TimetableService.DutyKey(TIMETABLE, duty, day), "ticket-create");

    assertEquals(
        Optional.of("train-a"),
        service.runningVehicleFor(
            new TimetableService.TicketIntent(TIMETABLE, duty, day, RouteOperationType.CREATE, 0)));
    for (TimetableService.TicketIntent intent :
        List.of(
            new TimetableService.TicketIntent(
                TIMETABLE, duty, day, RouteOperationType.OPERATION, 0),
            new TimetableService.TicketIntent(
                TIMETABLE, duty, day, RouteOperationType.OPERATION, 1),
            new TimetableService.TicketIntent(
                TIMETABLE, duty, day, RouteOperationType.RETURN, 0))) {
      assertTrue(service.runningVehicleFor(intent).isEmpty(), intent::toString);
    }
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

  // ------------------------------------------------------------------ 晚点账

  /**
   * 逐站记下到发偏差，到达终点站结账成一行：带入 +5（起点晚发）、途中新增 10、追回 15，终到准点。
   *
   * <p>R1-001：08:00:00 发，B 站 08:01:40 到、08:02:10 发，C 站 08:03:50 到。
   */
  @Test
  void delayLedgerRecordsEachStopAndClosesAtTheTerminal() {
    List<String> logs = new ArrayList<>();
    TimetableService service = service(true, logs, timetable(TimetableStatus.PUBLISHED));
    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")));

    service.observeStop(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")), true);
    service.observeStop(event("train-A", 1, Instant.parse("2026-03-02T08:01:55Z")), false);
    assertEquals(OptionalLong.of(15), service.currentDelaySeconds("TRAIN-A"), "最近一次：B 站晚到 15 秒");
    service.observeStop(event("train-A", 1, Instant.parse("2026-03-02T08:02:15Z")), true);
    assertEquals(OptionalLong.of(5), service.currentDelaySeconds("train-A"), "压缩停站追回 10 秒");
    service.observeStop(event("train-A", 2, Instant.parse("2026-03-02T08:03:50Z")), false);

    List<String> ledger =
        logs.stream().filter(line -> line.startsWith("TIMETABLE_TRIP_DELAY ")).toList();
    assertEquals(1, ledger.size(), logs::toString);
    String line = ledger.get(0);
    assertTrue(line.contains("trip=R1-001"), line);
    assertTrue(line.contains("reason=terminated"), line);
    assertTrue(line.contains("marks=4"), line);
    assertTrue(line.contains("carriedIn=+5"), line);
    assertTrue(line.contains("final=+0"), line);
    assertTrue(line.contains("max=+15"), line);
    assertTrue(line.contains("gained=10"), line);
    assertTrue(line.contains("recovered=15"), line);
    assertTrue(line.contains("detail=0d+5,1a+15,1d+5,2a+0"), line);
    assertTrue(service.currentDelaySeconds("train-A").isEmpty(), "结账后不再有当前偏差");
  }

  /** 车次没跑完列车就离开：照样结账，原因带上离开的理由。 */
  @Test
  void releasingATrainClosesItsDelayLedger() {
    List<String> logs = new ArrayList<>();
    TimetableService service = service(true, logs, timetable(TimetableStatus.PUBLISHED));
    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")));
    service.observeStop(event("train-A", 1, Instant.parse("2026-03-02T08:02:40Z")), false);

    service.release("train-A", "destroyed");

    assertTrue(
        logs.stream()
            .anyMatch(
                line ->
                    line.startsWith("TIMETABLE_TRIP_DELAY ")
                        && line.contains("reason=released:destroyed")
                        && line.contains("final=+60")),
        logs::toString);
    assertTrue(service.currentDelaySeconds("train-A").isEmpty());
  }

  /** 停站压缩到站就要查计划发车：只读、不建立绑定，交路对不上时答不上来。 */
  @Test
  void boundDepartureIsReadOnlyAndScopedToTheBoundRoute() {
    TimetableService service = service(true, timetable(TimetableStatus.PUBLISHED));
    Instant arrival = Instant.parse("2026-03-02T08:01:50Z");

    assertTrue(service.boundDepartureAt(event("train-A", 1, arrival)).isEmpty());
    assertTrue(service.assignments().isEmpty(), "查询不能建立绑定");

    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")));
    assertEquals(
        Optional.of(Instant.parse("2026-03-02T08:02:10Z")),
        service.boundDepartureAt(event("train-A", 1, arrival)));
    assertTrue(
        service
            .boundDepartureAt(
                new StationStopEvent(
                    "train-A", Optional.of(UUID.randomUUID()), "R9", 1, 3, "OP:S:BBB:1", arrival))
            .isEmpty(),
        "改派到别的交路之后不按旧车次压缩");
  }

  /** 改派到别的交路之后，旧车次的晚点不再算数：否则已经不受时刻表约束的车会一直带着放宽的限速跑。旧账就地结账。 */
  @Test
  void currentDelayForgetsATripTheTrainIsNoLongerBoundTo() {
    List<String> logs = new ArrayList<>();
    TimetableService service = service(true, logs, timetable(TimetableStatus.PUBLISHED));
    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")));
    service.observeStop(event("train-A", 1, Instant.parse("2026-03-02T08:02:10Z")), false);
    assertEquals(OptionalLong.of(30), service.currentDelaySeconds("train-A"));

    service.scheduledDepartureAt(
        new StationStopEvent(
            "train-A",
            Optional.of(UUID.randomUUID()),
            "R9",
            0,
            3,
            "OP:S:ZZZ:1",
            Instant.parse("2026-03-02T08:05:00Z")));

    assertTrue(service.currentDelaySeconds("train-A").isEmpty(), "改派后不再有当前偏差");
    assertTrue(
        logs.stream()
            .anyMatch(
                line ->
                    line.startsWith("TIMETABLE_TRIP_DELAY ")
                        && line.contains("reason=route-changed")),
        logs::toString);
  }

  /** 回到起点重新匹配到下一班：新车次还没有到发记录之前，上一班的晚点不能顶替成当前偏差。 */
  @Test
  void currentDelayIsScopedToTheNewlyBoundTripAfterRebinding() {
    TimetableService service = service(true, timetable(TimetableStatus.PUBLISHED));
    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:05Z")));
    service.observeStop(event("train-A", 1, Instant.parse("2026-03-02T08:02:10Z")), false);
    assertEquals(OptionalLong.of(30), service.currentDelaySeconds("train-A"));

    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:10:05Z")));

    assertEquals(Optional.of("R1-002"), service.assignmentOf("train-A").map(a -> a.tripCode()));
    assertTrue(service.currentDelaySeconds("train-A").isEmpty(), "R1-001 的 +30 不属于 R1-002");
  }

  /**
   * 两处口径：到站事件在停稳之后，要按"压牌 + 停稳耗时"比；早到后按表扣留回到 0 不算新增晚点。
   *
   * <p>停稳耗时 3 秒。B 站早到 20 秒（08:01:23 停稳）、扣到 08:02:10 准点开，C 站 08:03:53 停稳即准点：整趟没有晚点。
   */
  @Test
  void earlyHoldsAndSettlingAreNotCountedAsLateness() {
    List<String> logs = new ArrayList<>();
    TimetableService service = new TimetableService(() -> FIXTURE_NOW, logs::add);
    service.applySettings(
        new TimetableService.Settings(
            true,
            true,
            Duration.ofSeconds(120),
            Duration.ofSeconds(300),
            Duration.ofSeconds(300),
            ZONE,
            Duration.ofSeconds(3)));
    service.reload(providerWith(timetable(TimetableStatus.PUBLISHED)));
    service.scheduledDepartureAt(event("train-A", 0, Instant.parse("2026-03-02T08:00:00Z")));

    service.observeStop(event("train-A", 0, Instant.parse("2026-03-02T08:00:00Z")), true);
    service.observeStop(event("train-A", 1, Instant.parse("2026-03-02T08:01:23Z")), false);
    service.observeStop(event("train-A", 1, Instant.parse("2026-03-02T08:02:10Z")), true);
    service.observeStop(event("train-A", 2, Instant.parse("2026-03-02T08:03:53Z")), false);

    String line =
        logs.stream()
            .filter(entry -> entry.startsWith("TIMETABLE_TRIP_DELAY "))
            .findFirst()
            .orElseThrow(() -> new AssertionError(logs.toString()));
    assertTrue(line.contains("detail=0d+0,1a-20,1d+0,2a+0"), line);
    assertTrue(line.contains("gained=0"), line);
    assertTrue(line.contains("recovered=0"), line);
  }

  /** 总开关关着：没有晚点可言，控车不会因此放宽限速。 */
  @Test
  void currentDelayIsEmptyWhenDisabled() {
    TimetableService service = service(false, timetable(TimetableStatus.PUBLISHED));

    assertTrue(service.currentDelaySeconds("train-A").isEmpty());
  }
}
