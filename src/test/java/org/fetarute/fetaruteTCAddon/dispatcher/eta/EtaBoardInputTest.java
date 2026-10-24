package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.api.StationDirectory;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime.TrainLoad;
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
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteLineChanges;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeStopState;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationPresenceTracker;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.DutyContinuitySupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnForecastSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnServiceKey;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
import org.junit.jupiter.api.Test;

/**
 * 站牌输入快照：按拷好的输入算出的站牌与直接读钩子算出的逐项相同；按输入算时不再碰任何钩子、交路缓存与排班器，可以在别的线程上算。
 *
 * <p>场景把站牌路径上的钩子都接上：折返站 PPK 是动态站台（图上 1、2 道）。
 *
 * <ul>
 *   <li>in-1 走 IN（AAA → PPK 终到）、载客，接着开 OUT；PPK 的计划站台是 2 道。
 *   <li>thru-1 走 THRU（AAA → PPK → BBB），已停在 PPK 1 道、被扣停，有计划发车与计划到达。
 *   <li>thru-2 走 THRU，停在 AAA，PPK 尚未选台、暂定 2 道。
 *   <li>lay-1 在 PPK 2 道待命，接着开 BACK。
 *   <li>待发票据：OUT 的表定班（等 in-1、计划站台 1 道）与 THRU 的间隔班（叫车指定 1 道）；预测：THRU 间隔班与 OUT 表定班。
 *   <li>BBB 的停靠绑定了车站目录条目；线路代码按规范写法换成 {@code L1X}。
 * </ul>
 */
class EtaBoardInputTest {

  private static final NodeId AAA = NodeId.of("SURN:S:AAA:1");
  private static final NodeId PPK_1 = NodeId.of("SURN:S:PPK:1");
  private static final NodeId PPK_2 = NodeId.of("SURN:S:PPK:2");
  private static final NodeId BBB = NodeId.of("SURN:S:BBB:1");
  private static final String TRIP_OUT = "TIMETABLE-T-OUT-001-2026-10-20";
  private static final String TRIP_OUT_LATER = "TIMETABLE-T-OUT-002-2026-10-20";
  private static final String TRIP_BACK = "TIMETABLE-T-BACK-001-2026-10-20";
  private static final Duration HORIZON = Duration.ofMinutes(30);

  private final Instant now = Instant.parse("2026-10-20T08:00:00Z");
  private final UUID worldId = UUID.randomUUID();
  private final UUID inUuid = UUID.randomUUID();
  private final UUID outUuid = UUID.randomUUID();
  private final UUID backUuid = UUID.randomUUID();
  private final UUID thruUuid = UUID.randomUUID();
  private final UUID bbbStationId = UUID.randomUUID();
  private final TrainSnapshotStore snapshots = new TrainSnapshotStore();
  private final LayoverRegistry layovers = new LayoverRegistry();
  private final RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
  private final SpawnManager manager =
      mock(
          SpawnManager.class,
          withSettings().extraInterfaces(SpawnForecastSupport.class, DutyContinuitySupport.class));
  private final TicketAssigner assigner = mock(TicketAssigner.class);

  /** 置位后任何钩子被调用都算失败：按输入算站牌时不应再读钩子。 */
  private final AtomicBoolean hooksSealed = new AtomicBoolean();

  private final AtomicReference<String> touchedHook = new AtomicReference<>();

  @Test
  void boardsFromTheInputMatchBoardsFromTheLiveHooks() {
    EtaService service = service();
    for (String station : List.of("PPK", "BBB", "AAA")) {
      for (String line : java.util.Arrays.asList(null, "L1X", "L1")) {
        BoardResult live =
            service.computeBoardFromLiveSources(stationId(station), line, HORIZON, now);
        EtaBoardInput input = service.captureBoardInput(now, HORIZON);

        BoardResult frozen = service.computeBoard(input, stationId(station), line, HORIZON);

        assertEquals(live, frozen, "站 " + station + " 线路 " + line);
      }
    }
  }

  @Test
  void theScenarioCoversEveryKindOfBoardRow() {
    EtaService service = service();
    Set<BoardPhase> phases = EnumSet.noneOf(BoardPhase.class);
    boolean planned = false;
    boolean delayed = false;
    boolean loaded = false;
    boolean namedDestination = false;
    for (String station : List.of("PPK", "BBB", "AAA")) {
      BoardResult board =
          service.computeBoard(
              service.captureBoardInput(now, HORIZON), stationId(station), null, HORIZON);
      for (BoardResult.BoardRow row : board.rows()) {
        phases.add(row.phase());
        planned |= row.platformPlanned();
        delayed |= row.delaySeconds().isPresent();
        loaded |= row.load().isPresent();
        namedDestination |= row.destinationId().equals(Optional.of("SURN:BBB"));
        assertEquals("L1X", row.lineName(), "线路按规范写法显示");
      }
    }

    assertTrue(phases.contains(BoardPhase.AT_STATION), "已停在站内 / 终点待命");
    assertTrue(phases.contains(BoardPhase.PENDING), "待发票据");
    assertTrue(phases.contains(BoardPhase.FORECAST), "预测票据");
    assertTrue(
        phases.contains(BoardPhase.EN_ROUTE) || phases.contains(BoardPhase.ARRIVING), "运行中列车");
    assertTrue(planned, "计划或暂定站台");
    assertTrue(delayed, "按表运行的晚点秒数");
    assertTrue(loaded, "载客");
    assertTrue(namedDestination, "车站目录解析出的终点");
  }

  @Test
  void computingFromTheInputTouchesNoHookAndRunsOnAnotherThread() throws Exception {
    EtaService service = service();
    EtaBoardInput input = service.captureBoardInput(now, HORIZON);
    BoardResult expected =
        service.computeBoardFromLiveSources(stationId("PPK"), null, HORIZON, now);
    hooksSealed.set(true);
    clearInvocations(routes, manager, assigner);

    AtomicReference<BoardResult> computed = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread worker =
        new Thread(
            () -> {
              try {
                computed.set(service.computeBoard(input, stationId("PPK"), null, HORIZON));
              } catch (Throwable ex) {
                failure.set(ex);
              }
            },
            "eta-board-test");
    worker.start();
    worker.join(10_000L);

    assertEquals(null, failure.get());
    assertEquals(null, touchedHook.get(), "按输入算站牌时读了钩子");
    verifyNoInteractions(routes, manager, assigner);
    assertEquals(expected, computed.get());
    assertFalse(expected.rows().isEmpty());
  }

  private static String stationId(String code) {
    return "SURN:" + code;
  }

  private <T> T guard(String hook, T value) {
    if (hooksSealed.get()) {
      touchedHook.compareAndSet(null, hook);
    }
    return value;
  }

  private EtaService service() {
    RailGraphService graphs = mock(RailGraphService.class);
    RailGraphService.RailGraphSnapshot snapshot =
        new RailGraphService.RailGraphSnapshot(graph(), now);
    when(graphs.getSnapshot(worldId)).thenReturn(Optional.of(snapshot));
    when(graphs.snapshotAll()).thenReturn(Map.of(worldId, snapshot));
    when(graphs.findWorldIdForPath(anyList())).thenReturn(Optional.of(worldId));
    when(graphs.snapshotVersion()).thenReturn(7L);
    stubRoutes();

    trains();
    layovers.register("lay-1", "surn:s:ppk", PPK_2, now.plusSeconds(30), Map.of());

    EtaService service = new EtaService(snapshots, graphs, routes, () -> now);
    service.attachLayoverRegistry(layovers);
    StationPresenceTracker presence = new StationPresenceTracker();
    presence.onStationArrival(
        new StationStopEvent(
            "thru-1", Optional.of(thruUuid), "SURN:L1:THRU", 1, 3, PPK_1.value(), now));
    service.attachStationPresence(() -> guard("stationPresence", Optional.of(presence)));
    service.attachRuntimeStopStates(
        name ->
            guard(
                "stopStates",
                name.equals("thru-1")
                    ? Optional.of(
                        new RuntimeStopState(
                            "thru-1",
                            "HARD_BLOCKER_STOP",
                            "test",
                            RuntimeStopState.ReleaseCondition
                                .BLOCKING_RESOURCES_RELEASED_OR_TRANSFERRED,
                            RuntimeStopState.RetryTrigger.OCCUPANCY_CHANGE_OR_PERIODIC_RECHECK,
                            List.of(
                                new RuntimeStopState.Blocker(
                                    "CONFLICT:switcher:SURN:PPK", "OTHER", "PROTECTIVE_RETAIN")),
                            true,
                            now.minusSeconds(30)))
                    : Optional.empty()));
    service.attachPlannedDepartures(
        (name, index) ->
            guard(
                "plannedDepartures",
                name.equals("thru-1") && index == 1
                    ? Optional.of(now.plusSeconds(40))
                    : Optional.empty()),
        Duration.ofSeconds(120));
    service.attachPlannedArrivals(
        (name, index) ->
            guard(
                "plannedArrivals",
                name.startsWith("thru")
                    ? Optional.of(now.plusSeconds(50L * index))
                    : Optional.empty()));
    service.attachEffectiveWaypoints(
        (name, route) ->
            guard(
                "effectiveWaypoints",
                name.equals("thru-1") ? List.of(AAA, PPK_1, BBB) : route.waypoints()));
    service.attachPlacedStops(
        (name, route, index) -> guard("placedStops", name.equals("thru-1") && index == 1));
    service.attachPlannedPlatforms(
        (name, route, index) ->
            guard(
                "plannedPlatforms",
                (name.equals("thru-2") || name.equals("in-1")) && index == 1
                    ? Optional.of(PPK_2)
                    : Optional.empty()));
    service.attachTicketPlatforms(
        (ticket, index) ->
            guard(
                "ticketPlatforms",
                ticket.service().routeId().equals(thruUuid) && index == 1
                    ? Optional.of(PPK_1)
                    : Optional.empty()));
    service.attachLineCanonicalizer(
        line ->
            guard("lineCanonicalizer", new RouteLineChanges.LineRef(line.operatorCode(), "L1X")));
    Operator operator = mock(Operator.class);
    when(operator.code()).thenReturn("SURN");
    Station bbb =
        new Station(
            bbbStationId,
            "BBB",
            UUID.randomUUID(),
            Optional.empty(),
            "Bbb",
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            List.of(),
            Map.of(),
            now,
            now);
    service.attachStationLookup(
        id ->
            guard(
                "stationLookup",
                id.equals(bbbStationId)
                    ? Optional.of(new StationDirectory.StationEntry(bbb, operator))
                    : Optional.empty()));

    stubTickets();
    service.attachTicketSources(manager, assigner);
    return service;
  }

  private void trains() {
    snapshots.update(
        "in-1",
        snapshot(
            inUuid,
            "SURN:L1:IN",
            0,
            AAA,
            Optional.of(new TrainLoad(List.of(new TrainLoad.Car(8, 8), new TrainLoad.Car(4, 2))))));
    snapshots.update("thru-1", snapshot(thruUuid, "SURN:L1:THRU", 1, PPK_1, Optional.empty()));
    snapshots.update("thru-2", snapshot(thruUuid, "SURN:L1:THRU", 0, AAA, Optional.empty()));
  }

  private TrainRuntimeSnapshot snapshot(
      UUID routeUuid, String routeId, int index, NodeId at, Optional<TrainLoad> load) {
    return new TrainRuntimeSnapshot(
        1L,
        now,
        worldId,
        routeUuid,
        RouteId.of(routeId),
        index,
        Optional.of(at),
        Optional.of(at),
        Optional.empty(),
        Optional.of(SignalAspect.PROCEED),
        Optional.empty(),
        OptionalDouble.empty(),
        OptionalInt.empty(),
        OptionalInt.empty(),
        OptionalDouble.empty(),
        null,
        Optional.empty(),
        load);
  }

  private void stubTickets() {
    SpawnTicket outTicket = ticket(outUuid, "OUT", now.plusSeconds(120), Optional.of(TRIP_OUT));
    SpawnTicket thruTicket = ticket(thruUuid, "THRU", now.plusSeconds(300), Optional.empty());
    SpawnTicket thruForecast = ticket(thruUuid, "THRU", now.plusSeconds(600), Optional.empty());
    SpawnTicket outForecast =
        ticket(outUuid, "OUT", now.plusSeconds(900), Optional.of(TRIP_OUT_LATER));
    when(manager.snapshotQueue()).thenReturn(List.of(outTicket, thruTicket));
    when(((SpawnForecastSupport) manager).snapshotForecast(any(), any(), anyInt()))
        .thenReturn(List.of(thruForecast, outForecast));
    when(assigner.snapshotPendingTickets()).thenReturn(List.of());
    DutyContinuitySupport continuity = (DutyContinuitySupport) manager;
    when(continuity.nextDepartureOf(any()))
        .thenAnswer(
            invocation ->
                switch ((String) invocation.getArgument(0)) {
                  case "in-1" -> Optional.of(
                      new DutyContinuitySupport.NextDeparture(
                          outUuid, now.plusSeconds(120), TRIP_OUT));
                  case "lay-1" -> Optional.of(
                      new DutyContinuitySupport.NextDeparture(
                          backUuid, now.plusSeconds(200), TRIP_BACK));
                  default -> Optional.empty();
                });
    when(continuity.awaitedVehicleOf(any()))
        .thenAnswer(
            invocation ->
                ((SpawnTicket) invocation.getArgument(0))
                        .serviceTripId()
                        .filter(TRIP_OUT::equals)
                        .isPresent()
                    ? Optional.of("in-1")
                    : Optional.empty());
    when(continuity.plannedPlatformOf(any(), anyInt()))
        .thenAnswer(
            invocation ->
                ((SpawnTicket) invocation.getArgument(0)).serviceTripId().isPresent()
                        && (int) invocation.getArgument(1) == 0
                    ? Optional.of(PPK_1.value())
                    : Optional.empty());
  }

  private SpawnTicket ticket(UUID routeUuid, String code, Instant due, Optional<String> trip) {
    SpawnService spawnService =
        new SpawnService(
            new SpawnServiceKey(routeUuid),
            UUID.randomUUID(),
            "COMP",
            UUID.randomUUID(),
            "SURN",
            UUID.randomUUID(),
            "L1",
            routeUuid,
            code,
            Duration.ofMinutes(5),
            "SURN:D:DEPOT:1");
    return new SpawnTicket(
        UUID.randomUUID(),
        spawnService,
        due,
        due,
        due,
        0,
        0L,
        Optional.empty(),
        Optional.empty(),
        trip,
        TripSource.SCHEDULED,
        0);
  }

  private void stubRoutes() {
    route(
        inUuid,
        "IN",
        List.of(AAA, PPK_1),
        List.of(
            fixed(inUuid, 0, AAA, RouteStopPassType.STOP),
            dynamic(inUuid, 1, RouteStopPassType.TERMINATE)));
    route(
        outUuid,
        "OUT",
        List.of(PPK_1, BBB),
        List.of(
            dynamic(outUuid, 0, RouteStopPassType.STOP),
            new RouteStop(
                outUuid,
                1,
                Optional.of(bbbStationId),
                Optional.of(BBB.value()),
                Optional.empty(),
                RouteStopPassType.TERMINATE,
                Optional.empty())));
    route(
        backUuid,
        "BACK",
        List.of(PPK_1, AAA),
        List.of(
            dynamic(backUuid, 0, RouteStopPassType.STOP),
            fixed(backUuid, 1, AAA, RouteStopPassType.TERMINATE)));
    route(
        thruUuid,
        "THRU",
        List.of(AAA, PPK_1, BBB),
        List.of(
            fixed(thruUuid, 0, AAA, RouteStopPassType.STOP),
            dynamic(thruUuid, 1, RouteStopPassType.STOP),
            fixed(thruUuid, 2, BBB, RouteStopPassType.TERMINATE)));
  }

  private void route(UUID uuid, String code, List<NodeId> nodes, List<RouteStop> stops) {
    RouteDefinition definition =
        new RouteDefinition(
            RouteId.of("SURN:L1:" + code),
            nodes,
            Optional.of(RouteMetadata.of("SURN", "L1", code, null)));
    when(routes.findById(uuid)).thenReturn(Optional.of(definition));
    when(routes.listStops(definition.id())).thenReturn(stops);
  }

  private static RouteStop fixed(UUID route, int sequence, NodeId node, RouteStopPassType type) {
    return new RouteStop(
        route,
        sequence,
        Optional.empty(),
        Optional.of(node.value()),
        Optional.empty(),
        type,
        Optional.empty());
  }

  private static RouteStop dynamic(UUID route, int sequence, RouteStopPassType type) {
    return new RouteStop(
        route,
        sequence,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        type,
        Optional.of("DYNAMIC:SURN:S:PPK"));
  }

  /** AAA 连到 PPK 的 1、2 道，两道都连到 BBB，每段 60 格。 */
  private static RailGraph graph() {
    Map<NodeId, RailNode> nodes = new HashMap<>();
    List<NodeId> all = List.of(AAA, PPK_1, PPK_2, BBB);
    for (int i = 0; i < all.size(); i++) {
      nodes.put(
          all.get(i),
          new SignRailNode(
              all.get(i),
              NodeType.WAYPOINT,
              new Vector(i * 10, 0, 0),
              Optional.empty(),
              Optional.empty()));
    }
    Map<EdgeId, RailEdge> edges = new HashMap<>();
    for (NodeId track : List.of(PPK_1, PPK_2)) {
      for (NodeId other : List.of(AAA, BBB)) {
        EdgeId id = EdgeId.undirected(track, other);
        edges.put(id, new RailEdge(id, track, other, 60, 0.0, true, Optional.empty()));
      }
    }
    return new SimpleRailGraph(nodes, edges, Set.of());
  }
}
