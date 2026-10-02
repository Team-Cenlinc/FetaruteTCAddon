package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
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
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteMetadata;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
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
 * 终点折返：停在终点的车已经知道接下来开哪一趟，站牌列出那一趟（实际站台、晚点），并把来车的晚点传过终点。
 *
 * <p>折返站 PPK 是动态站台（图上 1、2 道），车停在 2 道；下一趟 OUT 从 PPK 开往 BBB。来车走 IN（AAA → PPK 终到）。
 */
class EtaBoardContinuityTest {

  private static final NodeId AAA = NodeId.of("SURN:S:AAA:1");
  private static final NodeId PPK_1 = NodeId.of("SURN:S:PPK:1");
  private static final NodeId PPK_2 = NodeId.of("SURN:S:PPK:2");
  private static final NodeId BBB = NodeId.of("SURN:S:BBB:1");
  private static final String TRIP = "TIMETABLE-T-OUT-001-2026-10-01";

  private final UUID outUuid = UUID.randomUUID();
  private final UUID backUuid = UUID.randomUUID();
  private final UUID inUuid = UUID.randomUUID();
  private final TrainSnapshotStore snapshots = new TrainSnapshotStore();
  private final UUID worldId = UUID.randomUUID();
  private final Instant now = Instant.now();
  private final LayoverRegistry layovers = new LayoverRegistry();

  @Test
  void aTrainStandingAtTheTerminalListsItsNextDepartureOnItsTrack() {
    layovers.register("train-1", "surn:s:ppk", PPK_2, now.plusSeconds(30), Map.of());
    EtaService service = board(Optional.empty(), List.of(ticket(now.plusSeconds(120))));

    List<BoardResult.BoardRow> rows = rows(service, "PPK");

    assertEquals(1, rows.size(), "同一班的票据不再另列一行");
    BoardResult.BoardRow row = rows.get(0);
    assertEquals(BoardPhase.AT_STATION, row.phase());
    assertEquals("2", row.platform(), "站台就是车停的股道，不是待定");
    assertFalse(row.platformPending());
    assertEquals(Optional.of("train-1"), row.trainName());
    assertEquals(0L, row.delaySeconds().getAsLong(), "就绪早于计划发车：准点");
  }

  @Test
  void aLateReadyTimeIsTheDepartureDelay() {
    layovers.register("train-1", "surn:s:ppk", PPK_2, now.plusSeconds(200), Map.of());
    EtaService service = board(Optional.empty(), List.of());

    BoardResult.BoardRow row = rows(service, "PPK").get(0);

    long delay = row.delaySeconds().getAsLong();
    assertTrue(delay >= 79 && delay <= 81, "就绪比计划晚 80 秒，实际 " + delay);
  }

  @Test
  void anInboundVehiclesReadinessDelaysTheContinuationDownstream() {
    layovers.register("train-1", "surn:s:ppk", PPK_2, now.plusSeconds(300), Map.of());
    SpawnTicket waiting = ticket(now.plusSeconds(60));

    BoardResult.BoardRow withVehicle =
        rows(board(Optional.of("train-1"), List.of(waiting)), "BBB").get(0);
    BoardResult.BoardRow unknownVehicle =
        rows(board(Optional.empty(), List.of(waiting)), "BBB").get(0);

    long gap = Duration.between(unknownVehicle.eta(), withVehicle.eta()).toSeconds();
    assertTrue(gap >= 239 && gap <= 241, "等的车 300 秒后才就绪，比票面晚 240 秒，实际 " + gap);
  }

  /** 开往折返站、已知接着开哪一趟的车：列那一趟（开往 BBB、计划站台、按发车时刻），不列“本站终到”，那一班的票据也不另列。 */
  @Test
  void anInboundTrainIsListedAsItsNextDepartureAtTheTurnback() {
    snapshots.update(
        "train-1",
        new TrainRuntimeSnapshot(
            1L,
            now,
            worldId,
            inUuid,
            RouteId.of("SURN:L1:IN"),
            0,
            Optional.of(AAA),
            Optional.of(AAA),
            Optional.empty(),
            Optional.of(SignalAspect.PROCEED),
            Optional.empty()));
    EtaService service = board(Optional.empty(), List.of(ticket(now.plusSeconds(120))));
    service.attachPlannedPlatforms(
        (train, route, index) -> index == 1 ? Optional.of(PPK_2) : Optional.empty());

    List<BoardResult.BoardRow> rows = rows(service, "PPK");

    assertEquals(1, rows.size(), "不另列“本站终到”，那一班的票据也不另列");
    BoardResult.BoardRow row = rows.get(0);
    assertFalse(row.terminating());
    assertEquals("SURN:L1:OUT", row.routeId());
    assertEquals(Optional.of("train-1"), row.trainName());
    assertEquals("2", row.platform(), "站台是它到站要停的计划站台");
    assertTrue(row.platformPlanned());
    assertTrue(!row.eta().isBefore(now.plusSeconds(119)), "时刻是发车，不是到站");
  }

  /** 来车到终点全员下车：折返那一趟按全车空座列出；继续同向开出时车厢顺序不变。 */
  @Test
  void anInboundTrainsLoadIsEmptiedForItsNextDeparture() {
    inboundWithLoad();
    EtaService service = board(Optional.empty(), List.of(), outUuid);

    BoardResult.BoardRow row = rows(service, "PPK").get(0);

    assertEquals(
        Optional.of(new TrainLoad(List.of(new TrainLoad.Car(8, 0), new TrainLoad.Car(4, 0)))),
        row.load(),
        "到终点全员下车，座位数照旧、车头在前");
  }

  /** 下一趟原地折返、反向开出：车头换到另一端，车厢顺序倒过来。 */
  @Test
  void aTurnbackReversesTheCarOrder() {
    inboundWithLoad();
    EtaService service = board(Optional.empty(), List.of(), backUuid);

    BoardResult.BoardRow row = rows(service, "PPK").get(0);

    assertEquals("SURN:L1:BACK", row.routeId());
    assertEquals(
        Optional.of(new TrainLoad(List.of(new TrainLoad.Car(4, 0), new TrainLoad.Car(8, 0)))),
        row.load());
  }

  /** 已停在终点的车：发车在站牌时间窗之外时不列，等进了时间窗再列。 */
  @Test
  void aStandingTrainBeyondTheHorizonIsNotListed() {
    layovers.register("train-1", "surn:s:ppk", PPK_2, now.plusSeconds(30), Map.of());
    EtaService service = board(Optional.empty(), List.of());

    assertTrue(
        service.getBoard("SURN", "PPK", null, Duration.ofSeconds(60)).rows().isEmpty(),
        "下一趟 120 秒后才开，时间窗只有 60 秒");
  }

  /** 来车走 IN 开往 PPK，载着 8 + 2 名乘客（两节车，座位 8 与 4）。 */
  private void inboundWithLoad() {
    snapshots.update(
        "train-1",
        new TrainRuntimeSnapshot(
            1L,
            now,
            worldId,
            inUuid,
            RouteId.of("SURN:L1:IN"),
            0,
            Optional.of(AAA),
            Optional.of(AAA),
            Optional.empty(),
            Optional.of(SignalAspect.PROCEED),
            Optional.empty(),
            java.util.OptionalDouble.empty(),
            java.util.OptionalInt.empty(),
            java.util.OptionalInt.empty(),
            java.util.OptionalDouble.empty(),
            null,
            Optional.empty(),
            Optional.of(new TrainLoad(List.of(new TrainLoad.Car(8, 8), new TrainLoad.Car(4, 2))))));
  }

  private List<BoardResult.BoardRow> rows(EtaService service, String station) {
    return service.getBoard("SURN", station, null, Duration.ofMinutes(30)).rows();
  }

  private EtaService board(Optional<String> awaitedVehicle, List<SpawnTicket> pending) {
    return board(awaitedVehicle, pending, outUuid);
  }

  /**
   * @param nextRoute 来车接着开的那一趟：OUT 继续同向开往 BBB，BACK 原地折返开回 AAA
   */
  private EtaService board(
      Optional<String> awaitedVehicle, List<SpawnTicket> pending, UUID nextRoute) {
    RouteDefinition back =
        new RouteDefinition(
            RouteId.of("SURN:L1:BACK"),
            List.of(PPK_1, AAA),
            Optional.of(RouteMetadata.of("SURN", "L1", "BACK", null)));
    RouteDefinition out =
        new RouteDefinition(
            RouteId.of("SURN:L1:OUT"),
            List.of(PPK_1, BBB),
            Optional.of(RouteMetadata.of("SURN", "L1", "OUT", null)));
    RouteDefinition in =
        new RouteDefinition(
            RouteId.of("SURN:L1:IN"),
            List.of(AAA, PPK_1),
            Optional.of(RouteMetadata.of("SURN", "L1", "IN", null)));
    RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
    when(routes.findById(outUuid)).thenReturn(Optional.of(out));
    when(routes.findById(inUuid)).thenReturn(Optional.of(in));
    when(routes.findById(backUuid)).thenReturn(Optional.of(back));
    when(routes.listStops(back.id()))
        .thenReturn(
            List.of(
                new RouteStop(
                    backUuid,
                    0,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    RouteStopPassType.STOP,
                    Optional.of("DYNAMIC:SURN:S:PPK")),
                new RouteStop(
                    backUuid,
                    1,
                    Optional.empty(),
                    Optional.of(AAA.value()),
                    Optional.empty(),
                    RouteStopPassType.TERMINATE,
                    Optional.empty())));
    when(routes.listStops(in.id()))
        .thenReturn(
            List.of(
                new RouteStop(
                    inUuid,
                    0,
                    Optional.empty(),
                    Optional.of(AAA.value()),
                    Optional.empty(),
                    RouteStopPassType.STOP,
                    Optional.empty()),
                new RouteStop(
                    inUuid,
                    1,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    RouteStopPassType.TERMINATE,
                    Optional.of("DYNAMIC:SURN:S:PPK"))));
    when(routes.listStops(out.id()))
        .thenReturn(
            List.of(
                new RouteStop(
                    outUuid,
                    0,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    RouteStopPassType.STOP,
                    Optional.of("DYNAMIC:SURN:S:PPK")),
                new RouteStop(
                    outUuid,
                    1,
                    Optional.empty(),
                    Optional.of(BBB.value()),
                    Optional.empty(),
                    RouteStopPassType.TERMINATE,
                    Optional.empty())));
    RailGraphService graphs = mock(RailGraphService.class);
    RailGraphService.RailGraphSnapshot snapshot =
        new RailGraphService.RailGraphSnapshot(graph(), now);
    when(graphs.getSnapshot(worldId)).thenReturn(Optional.of(snapshot));
    when(graphs.snapshotAll()).thenReturn(Map.of(worldId, snapshot));
    when(graphs.findWorldIdForPath(anyList())).thenReturn(Optional.of(worldId));
    EtaService service = new EtaService(snapshots, graphs, routes);
    service.attachLayoverRegistry(layovers);

    SpawnManager manager =
        mock(
            SpawnManager.class,
            withSettings()
                .extraInterfaces(SpawnForecastSupport.class, DutyContinuitySupport.class));
    when(manager.snapshotQueue()).thenReturn(pending);
    when(((SpawnForecastSupport) manager).snapshotForecast(any(), any(), anyInt()))
        .thenReturn(List.of());
    DutyContinuitySupport continuity = (DutyContinuitySupport) manager;
    when(continuity.nextDepartureOf("train-1"))
        .thenReturn(
            Optional.of(
                new DutyContinuitySupport.NextDeparture(nextRoute, now.plusSeconds(120), TRIP)));
    when(continuity.awaitedVehicleOf(any())).thenReturn(awaitedVehicle);
    TicketAssigner assigner = mock(TicketAssigner.class);
    when(assigner.snapshotPendingTickets()).thenReturn(List.of());
    service.attachTicketSources(manager, assigner);
    return service;
  }

  private SpawnTicket ticket(Instant due) {
    SpawnService spawnService =
        new SpawnService(
            new SpawnServiceKey(outUuid),
            UUID.randomUUID(),
            "COMP",
            UUID.randomUUID(),
            "SURN",
            UUID.randomUUID(),
            "L1",
            outUuid,
            "OUT",
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
        Optional.of(TRIP),
        TripSource.SCHEDULED,
        0);
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
