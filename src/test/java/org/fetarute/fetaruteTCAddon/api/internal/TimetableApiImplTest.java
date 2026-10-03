package org.fetarute.fetaruteTCAddon.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.api.event.TimetableTripCancelledEvent;
import org.fetarute.fetaruteTCAddon.api.route.RouteApi;
import org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaConfidence;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaReason;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaResult;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaService;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.EtaTarget;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainRuntimeSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainSnapshotStore;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationPresenceTracker;
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

class TimetableApiImplTest {

  private static final ZoneId ZONE = ZoneId.of("UTC");
  private static final UUID OPERATOR = UUID.randomUUID();
  private static final UUID LINE = UUID.randomUUID();
  private static final UUID ROUTE = UUID.randomUUID();
  private static final UUID TIMETABLE = UUID.randomUUID();
  private static final UUID DUTY = UUID.randomUUID();
  private static final UUID TRIP_ONE = UUID.randomUUID();
  private static final UUID TRIP_TWO = UUID.randomUUID();
  private static final Instant BOUND_AT = Instant.parse("2026-03-02T08:00:05Z");

  /**
   * AAA → PPP（通过）→ BBB（停 30 秒）→ QQQ（停站 0 秒，但是 STOP）→ CCC（终点），08:00 与 08:10 各一班。
   *
   * <p>PPP 与 QQQ 的到发时刻都相同：只有停车方式能区分“通过”和“停 0 秒”。
   */
  private static List<TimetableStop> planStops() {
    return List.of(
        new TimetableStop(
            0, Optional.of("AAA"), Optional.of("OP:S:AAA:1"), 0, 0, RouteStopPassType.STOP),
        new TimetableStop(
            1, Optional.of("PPP"), Optional.of("OP:S:PPP:1"), 50, 50, RouteStopPassType.PASS),
        new TimetableStop(
            2, Optional.of("BBB"), Optional.of("OP:S:BBB:1"), 100, 130, RouteStopPassType.STOP),
        new TimetableStop(
            3, Optional.of("QQQ"), Optional.of("OP:S:QQQ:1"), 180, 180, RouteStopPassType.STOP),
        new TimetableStop(
            4,
            Optional.of("CCC"),
            Optional.of("OP:S:CCC:1"),
            230,
            230,
            RouteStopPassType.TERMINATE));
  }

  private static Timetable timetable(List<TimetableStop> stops) {
    return new Timetable(
        TIMETABLE,
        UUID.randomUUID(),
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
                stops.get(0).nodeId().orElseThrow(),
                stops.get(stops.size() - 1).nodeId().orElseThrow(),
                Optional.empty(),
                Optional.empty())),
        List.of(
            new TimetableTrip(
                TRIP_TWO, TIMETABLE, ROUTE, 1, "R1-002", 8 * 3600 + 600, Optional.of(DUTY)),
            new TimetableTrip(
                TRIP_ONE, TIMETABLE, ROUTE, 0, "R1-001", 8 * 3600, Optional.of(DUTY))),
        List.of(
            new VehicleDuty(
                DUTY,
                TIMETABLE,
                0,
                "D001",
                "OP:D:DEP:1",
                "OP:D:DEP:1",
                Optional.empty(),
                Optional.empty(),
                List.of(TRIP_ONE, TRIP_TWO),
                8 * 3600 - 180,
                8 * 3600 + 900,
                8 * 3600 + 990,
                VehicleDuty.CloseReason.MAX_TRIPS)),
        Optional.empty(),
        Instant.parse("2026-03-01T00:00:00Z"),
        Instant.parse("2026-03-01T00:00:00Z"));
  }

  private static TimetableService service(boolean enabled) {
    return service(enabled, planStops());
  }

  private static TimetableService service(boolean enabled, List<TimetableStop> stops) {
    TimetableService service = new TimetableService(Instant::now, message -> {});
    service.applySettings(
        new TimetableService.Settings(
            enabled,
            enabled,
            Duration.ofSeconds(120),
            Duration.ofSeconds(300),
            Duration.ofSeconds(300)));
    StorageProvider provider = mock(StorageProvider.class);
    TimetableRepository repository = mock(TimetableRepository.class);
    when(provider.timetables()).thenReturn(repository);
    when(repository.listPublished()).thenReturn(List.of(timetable(stops)));
    service.reload(provider);
    return service;
  }

  /** 每次查询都是新 tick：这些用例在两次查询之间改状态，不应读到上一次的缓存。 */
  private static LongSupplier everyCallNewTick() {
    return new AtomicLong()::incrementAndGet;
  }

  private static TimetableApiImpl api(
      TimetableService service, StationPresenceTracker tracker, EtaService eta) {
    return new TimetableApiImpl(
        () -> Optional.of(service),
        () -> Optional.ofNullable(tracker),
        () -> Optional.ofNullable(eta),
        everyCallNewTick());
  }

  private static void bindTripOne(TimetableService service, int stopCount) {
    service.scheduledDepartureAt(
        new StationStopEvent(
            "train-A", Optional.of(ROUTE), "R1", 0, stopCount, "OP:S:AAA:1", BOUND_AT));
  }

  private static TrainRuntimeSnapshot snapshotAt(int routeIndex) {
    return new TrainRuntimeSnapshot(
        1L,
        Instant.parse("2026-03-02T08:01:00Z"),
        UUID.randomUUID(),
        ROUTE,
        RouteId.of("OP:L1:R1"),
        routeIndex,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  private static EtaResult etaAt(Instant eta) {
    return new EtaResult(
        false,
        "Delayed 1m",
        eta.toEpochMilli(),
        1,
        30,
        0,
        60,
        List.of(EtaReason.HOLD),
        EtaConfidence.LOW);
  }

  @Test
  void departuresListOnlyStoppingTrainsInWindowSortedByTime() {
    TimetableApiImpl api = api(service(true), null, null);

    List<TimetableApi.Departure> atB =
        api.departuresAt(
            OPERATOR, "bbb", Instant.parse("2026-03-02T07:59:00Z"), Duration.ofMinutes(20), 10);
    assertEquals(
        List.of("R1-001", "R1-002"), atB.stream().map(TimetableApi.Departure::tripCode).toList());
    assertEquals(Instant.parse("2026-03-02T08:01:40Z"), atB.get(0).plannedArrival());
    assertEquals(Instant.parse("2026-03-02T08:02:10Z"), atB.get(0).plannedDeparture());
    assertFalse(atB.get(0).terminating());

    assertTrue(
        api.departuresAt(
                OPERATOR, "PPP", Instant.parse("2026-03-02T07:59:00Z"), Duration.ofMinutes(20), 10)
            .isEmpty(),
        "通过站不列");

    List<TimetableApi.Departure> atC =
        api.departuresAt(
            null, "CCC", Instant.parse("2026-03-02T08:05:00Z"), Duration.ofMinutes(10), 10);
    assertEquals(1, atC.size());
    assertTrue(atC.get(0).terminating());

    assertTrue(
        api.departuresAt(
                UUID.randomUUID(),
                "BBB",
                Instant.parse("2026-03-02T07:59:00Z"),
                Duration.ofMinutes(20),
                10)
            .isEmpty(),
        "按运营商过滤");
  }

  /**
   * 车在 BBB 发车后被销毁：这一趟 BBB 及之前照常，QQQ 起（含终点）标为取消。同一交路的下一趟没有出库线路可派替补，当即整趟标为取消（2026-09-30
   * 起，此前要等它的票过了容差才作废）。
   */
  @Test
  void departuresMarkTheStopsACancelledTripNoLongerServes() {
    TimetableService service = service(true);
    TimetableApiImpl api = api(service, null, null);
    bindTripOne(service, 5);
    service.observeStop(
        new StationStopEvent("train-A", Optional.of(ROUTE), "R1", 0, 5, "OP:S:AAA:1", BOUND_AT),
        true);
    service.observeStop(
        new StationStopEvent("train-A", Optional.of(ROUTE), "R1", 2, 5, "OP:S:BBB:1", BOUND_AT),
        true);
    service.release("train-A", "destroyed");

    Instant from = Instant.parse("2026-03-02T07:59:00Z");
    assertEquals(
        List.of(false, true),
        cancelledFlags(api.departuresAt(OPERATOR, "BBB", from, Duration.ofMinutes(20), 10)));
    assertEquals(
        List.of(true, true),
        cancelledFlags(api.departuresAt(OPERATOR, "QQQ", from, Duration.ofMinutes(20), 10)));
    assertEquals(
        List.of(true, true),
        cancelledFlags(api.departuresAt(OPERATOR, "CCC", from, Duration.ofMinutes(20), 10)));

    List<TimetableApi.CancelledTrip> cancelled =
        api.cancellations(from, from.plus(Duration.ofMinutes(20)));
    assertEquals(
        List.of(TRIP_ONE, TRIP_TWO),
        cancelled.stream().map(TimetableApi.CancelledTrip::tripId).toList(),
        "按计划始发排序");
    assertEquals(TimetableTripCancelledEvent.Scope.PARTIAL, cancelled.get(0).scope());
    assertEquals(3, cancelled.get(0).firstCancelledStopSequence(), "从 QQQ 起，与事件同一口径");
    assertEquals(TimetableTripCancelledEvent.Scope.FULL, cancelled.get(1).scope());
    assertEquals(ROUTE, cancelled.get(0).routeId());
    assertTrue(api.cancellations(from, from.plusSeconds(30)).isEmpty(), "计划始发不在窗口里的不列");
  }

  private static List<Boolean> cancelledFlags(List<TimetableApi.Departure> departures) {
    return departures.stream().map(TimetableApi.Departure::cancelled).toList();
  }

  @Test
  void zeroDwellStopStillCountsAsStop() {
    TimetableService service = service(true);
    EtaService eta = mock(EtaService.class);
    TimetableApiImpl api = api(service, null, eta);

    // 修复前按“停站 > 0 秒”猜：QQQ 到发同刻，被当成通过站，站牌上看不到它。
    List<TimetableApi.Departure> atQ =
        api.departuresAt(
            OPERATOR, "QQQ", Instant.parse("2026-03-02T07:59:00Z"), Duration.ofMinutes(10), 10);
    assertEquals(List.of("R1-001"), atQ.stream().map(TimetableApi.Departure::tripCode).toList());
    assertEquals(
        Optional.of(3), atQ.stream().map(TimetableApi.Departure::stopSequence).findFirst());

    TimetableApi.StopTime q =
        api.getTimetable(TIMETABLE).orElseThrow().routePlans().get(0).stops().get(3);
    assertEquals(RouteApi.PassType.STOP, q.passType());
    assertTrue(q.stops());
    TimetableApi.StopTime p =
        api.getTimetable(TIMETABLE).orElseThrow().routePlans().get(0).stops().get(1);
    assertFalse(p.stops());

    // 已过 BBB：下一站是 QQQ（修复前会跳到 CCC，晚点算到后面的站上）。
    bindTripOne(service, 5);
    when(eta.getRuntimeSnapshot("train-A")).thenReturn(Optional.of(snapshotAt(2)));
    when(eta.effectiveStopNode("train-A", 3)).thenReturn(Optional.of(NodeId.of("OP:S:QQQ:1")));
    when(eta.getForTrain(eq("train-A"), eq(new EtaTarget.StopIndex(3))))
        .thenReturn(etaAt(Instant.parse("2026-03-02T08:03:20Z")));
    TimetableApi.TrainAssignment a = api.getAssignment("train-A").orElseThrow();
    assertEquals(Optional.of(3), a.nextStopSequence());
    assertEquals(Optional.of("QQQ"), a.nextStationCode());
    // 计划 08:03:00 到 QQQ，预计 08:03:20。
    assertEquals(20L, a.projectedDelaySeconds().getAsLong());
  }

  @Test
  void nextStopSequenceIndexesTheSameStopAsRouteApi() {
    TimetableService service = service(true);
    EtaService eta = mock(EtaService.class);
    TimetableApiImpl api = api(service, null, eta);
    bindTripOne(service, 5);
    when(eta.getRuntimeSnapshot("train-A")).thenReturn(Optional.of(snapshotAt(0)));
    when(eta.effectiveStopNode(eq("train-A"), anyInt())).thenReturn(Optional.empty());
    when(eta.getForTrain(eq("train-A"), any()))
        .thenReturn(etaAt(Instant.parse("2026-03-02T08:02:00Z")));

    // RouteApi 停靠表：与 waypoints 下标对齐，PPP 是 PASS。
    List<String> nodes =
        List.of("OP:S:AAA:1", "OP:S:PPP:1", "OP:S:BBB:1", "OP:S:QQQ:1", "OP:S:CCC:1");
    List<RouteStopPassType> passTypes =
        List.of(
            RouteStopPassType.STOP,
            RouteStopPassType.PASS,
            RouteStopPassType.STOP,
            RouteStopPassType.STOP,
            RouteStopPassType.TERMINATE);
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("OP:L1:R1"), nodes.stream().map(NodeId::of).toList(), Optional.empty());
    List<RouteStop> routeStops =
        java.util.stream.IntStream.range(0, nodes.size())
            .mapToObj(
                i ->
                    new RouteStop(
                        ROUTE,
                        (i + 1) * 10,
                        Optional.empty(),
                        Optional.of(nodes.get(i)),
                        Optional.empty(),
                        passTypes.get(i),
                        Optional.empty()))
            .toList();
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findById(ROUTE)).thenReturn(Optional.of(route));
    when(routeDefinitions.listStops(route.id())).thenReturn(routeStops);
    RouteApi.RouteDetail detail =
        new RouteApiImpl(routeDefinitions, null).getRoute(ROUTE).orElseThrow();

    TimetableApi.TrainAssignment a = api.getAssignment("train-A").orElseThrow();
    int next = a.nextStopSequence().orElseThrow();
    assertEquals(2, next, "跳过只通过的 PPP");
    RouteApi.StopInfo stop = detail.stops().get(next);
    assertEquals(next, stop.sequence(), "停靠序号就是停靠表下标（0 起）");
    assertEquals(stop.nodeId(), a.nextStopNodeId().orElseThrow());
    assertEquals(detail.waypoints().get(next), a.nextStopNodeId().orElseThrow());
    assertEquals(Optional.of("BBB"), a.nextStationCode());

    // 上一站：实际到发记录带节点与站码，序号同一口径。
    StationPresenceTracker tracker = new StationPresenceTracker();
    TimetableApiImpl withStops = api(service, tracker, eta);
    tracker.onStationArrival(
        new StationStopEvent(
            "train-A",
            Optional.of(ROUTE),
            "R1",
            2,
            5,
            "OP:S:BBB:2",
            Instant.parse("2026-03-02T08:02:00Z")));
    TimetableApi.TrainAssignment atB = withStops.getAssignment("train-A").orElseThrow();
    assertEquals(Optional.of(2), atB.lastStopSequence());
    assertEquals(Optional.of("OP:S:BBB:2"), atB.lastStopNodeId());
    assertEquals(Optional.of("BBB"), atB.lastStationCode());
    assertEquals(detail.stops().get(2).sequence(), atB.lastStopSequence().orElseThrow());
  }

  /**
   * DYNAMIC 停靠被分到非 fromTrack 的股道：预计偏差按实际股道算。
   *
   * <p>真实 ETA 服务 + 调度图。AAA→BBB:1（占位股道）40 格，AAA→BBB:2 200 格；列车被分到 2 道。 默认速度 6 格/秒、无初速变化：1 道约 6 秒，2
   * 道约 33 秒。修复前拿占位股道去算，差出 27 秒。
   */
  @Test
  void dynamicStopProjectsDelayToAllocatedPlatform() {
    NodeId a = NodeId.of("OP:S:AAA:1");
    NodeId b1 = NodeId.of("OP:S:BBB:1");
    NodeId b2 = NodeId.of("OP:S:BBB:2");
    NodeId c = NodeId.of("OP:S:CCC:1");
    UUID worldId = UUID.randomUUID();
    RouteDefinition route =
        new RouteDefinition(RouteId.of("OP:L1:R1"), List.of(a, b1, c), Optional.empty());
    List<RouteStop> routeStops =
        List.of(
            new RouteStop(
                ROUTE,
                0,
                Optional.empty(),
                Optional.of(a.value()),
                Optional.empty(),
                RouteStopPassType.STOP,
                Optional.empty()),
            new RouteStop(
                ROUTE,
                1,
                Optional.empty(),
                Optional.empty(),
                Optional.of(20),
                RouteStopPassType.STOP,
                Optional.of("DYNAMIC:OP:S:BBB:[1:2]")),
            new RouteStop(
                ROUTE,
                2,
                Optional.empty(),
                Optional.of(c.value()),
                Optional.empty(),
                RouteStopPassType.TERMINATE,
                Optional.empty()));
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findById(ROUTE)).thenReturn(Optional.of(route));
    when(routeDefinitions.listStops(route.id())).thenReturn(routeStops);
    RailGraph graph =
        graph(
            List.of(a, b1, b2, c),
            List.of(edge(a, b1, 40), edge(a, b2, 200), edge(b1, c, 40), edge(b2, c, 40)));
    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));
    TrainSnapshotStore snapshots = new TrainSnapshotStore();
    snapshots.update(
        "train-A",
        new TrainRuntimeSnapshot(
            1L,
            Instant.now(),
            worldId,
            ROUTE,
            route.id(),
            0,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()));
    EtaService eta = new EtaService(snapshots, railGraphService, routeDefinitions);
    eta.attachEffectiveWaypoints((name, r) -> List.of(a, b2, c));

    List<TimetableStop> stops =
        List.of(
            new TimetableStop(
                0, Optional.of("AAA"), Optional.of(a.value()), 0, 0, RouteStopPassType.STOP),
            new TimetableStop(
                1, Optional.of("BBB"), Optional.of(b1.value()), 60, 80, RouteStopPassType.STOP),
            new TimetableStop(
                2,
                Optional.of("CCC"),
                Optional.of(c.value()),
                120,
                120,
                RouteStopPassType.TERMINATE));
    TimetableService service = service(true, stops);
    bindTripOne(service, 3);
    TimetableApiImpl api = api(service, null, eta);

    Instant before = Instant.now();
    TimetableApi.TrainAssignment assignment = api.getAssignment("train-A").orElseThrow();
    Instant after = Instant.now();

    assertEquals(Optional.of(1), assignment.nextStopSequence());
    assertEquals(Optional.of(b2.value()), assignment.nextStopNodeId(), "已选台：报实际股道");
    assertEquals(Optional.of("BBB"), assignment.nextStationCode());
    // 计划 08:01:00 到 BBB；预计 = 查询时刻 + 33 秒（2 道），不是 + 6 秒（占位的 1 道）。
    long planned = Instant.parse("2026-03-02T08:01:00Z").toEpochMilli();
    long low = Math.floorDiv(before.toEpochMilli() + 33_000L - planned, 1000L);
    long high = Math.floorDiv(after.toEpochMilli() + 33_000L - planned, 1000L);
    long projected = assignment.projectedDelaySeconds().getAsLong();
    assertTrue(
        projected >= low && projected <= high,
        "预计偏差应按 2 道（33 秒）算: projected=" + projected + " expected∈[" + low + "," + high + "]");
  }

  @Test
  void repeatedQueriesWithinOneTickComputeEtaOnce() {
    TimetableService service = service(true);
    EtaService eta = mock(EtaService.class);
    AtomicLong tick = new AtomicLong(100L);
    TimetableApiImpl api =
        new TimetableApiImpl(
            () -> Optional.of(service), Optional::empty, () -> Optional.of(eta), tick::get);
    bindTripOne(service, 5);
    when(eta.getRuntimeSnapshot("train-A")).thenReturn(Optional.of(snapshotAt(0)));
    when(eta.effectiveStopNode(eq("train-A"), anyInt())).thenReturn(Optional.empty());
    when(eta.getForTrain(eq("train-A"), eq(new EtaTarget.StopIndex(2))))
        .thenReturn(etaAt(Instant.parse("2026-03-02T08:02:25Z")));

    TimetableApi.TrainAssignment first = api.getAssignment("train-A").orElseThrow();
    TimetableApi.TrainAssignment second = api.getAssignment("TRAIN-A").orElseThrow();
    assertEquals(1, api.listAssignments().size());
    assertEquals(first, second);
    verify(eta, times(1)).getForTrain(eq("train-A"), any());

    tick.incrementAndGet();
    api.getAssignment("train-A").orElseThrow();
    verify(eta, times(2)).getForTrain(eq("train-A"), any());
  }

  @Test
  void assignmentReportsDelayOfLatestActualStop() {
    TimetableService service = service(true);
    StationPresenceTracker tracker = new StationPresenceTracker();
    TimetableApiImpl api = api(service, tracker, null);
    bindTripOne(service, 5);

    TimetableApi.TrainAssignment bound = api.getAssignment("TRAIN-A").orElseThrow();
    assertEquals("R1-001", bound.tripCode());
    assertEquals(Optional.of("D001"), bound.dutyCode());
    assertTrue(bound.currentDelaySeconds().isEmpty(), "尚无实际停靠记录");

    // 计划 08:01:40 到 BBB，实际 08:02:00 到：晚 20 秒。
    tracker.onStationArrival(
        new StationStopEvent(
            "train-A",
            Optional.of(ROUTE),
            "R1",
            2,
            5,
            "OP:S:BBB:1",
            Instant.parse("2026-03-02T08:02:00Z")));
    TimetableApi.TrainAssignment late = api.getAssignment("train-a").orElseThrow();
    assertEquals(Optional.of(2), late.lastStopSequence());
    assertEquals(20L, late.currentDelaySeconds().getAsLong());

    // 计划 08:02:10 发，实际 08:02:05 发：早 5 秒。
    tracker.onStationDeparture(
        new StationStopEvent(
            "train-A",
            Optional.of(ROUTE),
            "R1",
            2,
            5,
            "OP:S:BBB:1",
            Instant.parse("2026-03-02T08:02:05Z")));
    assertEquals(-5L, api.getAssignment("train-a").orElseThrow().currentDelaySeconds().getAsLong());
  }

  @Test
  void previousCircuitRecordsDoNotCountForNewTrip() {
    TimetableService service = service(true);
    StationPresenceTracker tracker = new StationPresenceTracker();
    TimetableApiImpl api = api(service, tracker, null);
    // 上一趟车在 07:55 终到 CCC；本车次 08:00:05 在 AAA 绑定。
    tracker.onStationArrival(
        new StationStopEvent(
            "train-A",
            Optional.of(ROUTE),
            "R1",
            4,
            5,
            "OP:S:CCC:1",
            Instant.parse("2026-03-02T07:55:00Z")));
    bindTripOne(service, 5);
    // 修复前只按交路过滤：上一趟的终到被拿去和本车次 CCC 的计划到达（08:03:50）相减。
    assertTrue(api.getAssignment("train-A").orElseThrow().currentDelaySeconds().isEmpty());

    // 促成绑定的那次到站（绑定前 15 秒到 AAA）算本车次。
    tracker.onStationArrival(
        new StationStopEvent(
            "train-A",
            Optional.of(ROUTE),
            "R1",
            0,
            5,
            "OP:S:AAA:1",
            Instant.parse("2026-03-02T07:59:50Z")));
    assertEquals(Optional.of(0), api.getAssignment("train-A").orElseThrow().lastStopSequence());
  }

  @Test
  void projectedDelayTracksEtaToNextStoppingPoint() {
    TimetableService service = service(true);
    EtaService eta = mock(EtaService.class);
    TimetableApiImpl api = api(service, null, eta);
    bindTripOne(service, 5);
    when(eta.getRuntimeSnapshot("train-A")).thenReturn(Optional.of(snapshotAt(0)));
    when(eta.effectiveStopNode("train-A", 2)).thenReturn(Optional.of(NodeId.of("OP:S:BBB:1")));
    // 在区间被扣停：ETA 预计 08:02:25 到 BBB（计划 08:01:40），跳过只通过的 PPP。
    when(eta.getForTrain(eq("train-A"), eq(new EtaTarget.StopIndex(2))))
        .thenReturn(etaAt(Instant.parse("2026-03-02T08:02:25Z")));
    TimetableApi.TrainAssignment a = api.getAssignment("train-A").orElseThrow();
    assertEquals(Optional.of(2), a.nextStopSequence());
    assertEquals(Optional.of("OP:S:BBB:1"), a.nextStopNodeId());
    assertEquals(45L, a.projectedDelaySeconds().getAsLong());
    verify(eta, never()).getForTrain(eq("train-A"), eq(new EtaTarget.StopIndex(1)));
  }

  @Test
  void detailExposesTripsInDepartureOrderWithDutyCodes() {
    TimetableApiImpl api = api(service(true), null, null);
    assertEquals(1, api.listPublished().size());
    assertEquals(1, api.listByLine(LINE).size());
    TimetableApi.TimetableDetail detail = api.getTimetable(TIMETABLE).orElseThrow();
    assertEquals(
        List.of("R1-001", "R1-002"),
        detail.trips().stream().map(TimetableApi.Trip::tripCode).toList());
    assertEquals(Optional.of("D001"), detail.trips().get(0).dutyCode());
    assertEquals(List.of("R1-001", "R1-002"), detail.duties().get(0).tripCodes());
    assertEquals(RouteOperationType.OPERATION.name(), detail.routePlans().get(0).kind());
  }

  @Test
  void disabledTimetableHidesAssignmentsButKeepsPublishedQueries() {
    TimetableApiImpl api = api(service(false), null, null);
    assertFalse(api.enabled());
    assertTrue(api.getAssignment("train-A").isEmpty());
    assertEquals(1, api.listPublished().size());
  }

  @Test
  void missingServiceIsEmptyNotNull() {
    TimetableApiImpl api =
        new TimetableApiImpl(Optional::empty, Optional::empty, Optional::empty, () -> 0L);
    assertFalse(api.enabled());
    assertTrue(api.listPublished().isEmpty());
    assertTrue(api.departuresAt(null, "AAA", Instant.now(), Duration.ofMinutes(5), 5).isEmpty());
  }

  private static RailEdge edge(NodeId from, NodeId to, int lengthBlocks) {
    EdgeId id = EdgeId.undirected(from, to);
    return new RailEdge(id, from, to, lengthBlocks, 0.0, true, Optional.empty());
  }

  private static RailGraph graph(List<NodeId> nodes, List<RailEdge> edges) {
    Map<NodeId, RailNode> nodeMap = new java.util.HashMap<>();
    for (int i = 0; i < nodes.size(); i++) {
      nodeMap.put(
          nodes.get(i),
          new SignRailNode(
              nodes.get(i),
              NodeType.WAYPOINT,
              new Vector(i * 10, 0, 0),
              Optional.empty(),
              Optional.empty()));
    }
    Map<EdgeId, RailEdge> edgeMap = new java.util.HashMap<>();
    for (RailEdge edge : edges) {
      edgeMap.put(edge.id(), edge);
    }
    return new SimpleRailGraph(nodeMap, edgeMap, Set.of());
  }
}
