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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
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
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationPresenceTracker;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnForecastSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnServiceKey;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
import org.junit.jupiter.api.Test;

/**
 * 动态站台尚未选台时，站牌行不能把占位股道当站台号：站台写“-”，标出待定并给出候选。
 *
 * <p>交路 AAA（停）→ PPK（DYNAMIC 不写范围，终到），图上 PPK 有 1、2 两条股道；占位股道是 1 道。
 */
class EtaBoardPlatformPendingTest {

  private static final NodeId AAA = NodeId.of("SURN:S:AAA:1");
  private static final NodeId PPK_1 = NodeId.of("SURN:S:PPK:1");
  private static final NodeId PPK_2 = NodeId.of("SURN:S:PPK:2");
  private static final String TRAIN = "train-1";

  @Test
  void unselectedDynamicStopIsPendingWithTheCandidatesOnTheGraph() {
    Fixture fixture = new Fixture(List.of(PPK_1, PPK_2)).running(0, AAA);

    BoardResult.BoardRow row = onlyRow(fixture.service);

    assertTrue(row.platformPending());
    assertEquals("-", row.platform(), "占位股道 1 道不是列车会去的站台");
    assertEquals(List.of("1", "2"), row.platformCandidates());
  }

  @Test
  void selectingThePlaceholderTrackIsNotPending() {
    Fixture fixture = new Fixture(List.of(PPK_1, PPK_2)).running(0, AAA);
    fixture.service.attachEffectiveWaypoints((train, route) -> List.of(AAA, PPK_1));
    fixture.service.attachPlacedStops((train, route, index) -> index == 1);

    BoardResult.BoardRow row = onlyRow(fixture.service);

    assertFalse(row.platformPending(), "选中的恰好是占位股道：实际节点与声明相同，靠选台记录区分");
    assertEquals("1", row.platform());
    assertTrue(row.platformCandidates().isEmpty());
  }

  @Test
  void selectedTrackIsShown() {
    Fixture fixture = new Fixture(List.of(PPK_1, PPK_2)).running(0, AAA);
    fixture.service.attachEffectiveWaypoints((train, route) -> List.of(AAA, PPK_2));
    fixture.service.attachPlacedStops((train, route, index) -> index == 1);

    BoardResult.BoardRow row = onlyRow(fixture.service);

    assertFalse(row.platformPending());
    assertEquals("2", row.platform());
  }

  @Test
  void aSingleCandidateIsTheConfirmedPlatform() {
    Fixture fixture = new Fixture(List.of(PPK_2)).running(0, AAA);

    BoardResult.BoardRow row = onlyRow(fixture.service);

    assertFalse(row.platformPending());
    assertEquals("2", row.platform());
  }

  @Test
  void trainStandingWithoutASelectionRecordShowsTheTrackItStopsAt() {
    Fixture fixture = new Fixture(List.of(PPK_1, PPK_2)).running(1, PPK_2);
    StationPresenceTracker presence = new StationPresenceTracker();
    presence.onStationArrival(
        new StationStopEvent(
            TRAIN,
            Optional.of(fixture.routeUuid),
            "SURN:L1:R1",
            1,
            3,
            PPK_2.value(),
            Instant.now()));
    fixture.service.attachStationPresence(() -> Optional.of(presence));

    BoardResult.BoardRow row = onlyRow(fixture.service);

    assertEquals(BoardPhase.AT_STATION, row.phase());
    assertFalse(row.platformPending());
    assertEquals("2", row.platform());
  }

  @Test
  void ticketsArePendingUntilATrainIsDispatched() {
    Fixture fixture = new Fixture(List.of(PPK_1, PPK_2));
    SpawnManager manager =
        mock(SpawnManager.class, withSettings().extraInterfaces(SpawnForecastSupport.class));
    when(manager.snapshotQueue())
        .thenReturn(List.of(fixture.ticket(Instant.now().plusSeconds(60))));
    when(((SpawnForecastSupport) manager).snapshotForecast(any(), any(), anyInt()))
        .thenReturn(List.of());
    TicketAssigner assigner = mock(TicketAssigner.class);
    when(assigner.snapshotPendingTickets()).thenReturn(List.of());
    fixture.service.attachTicketSources(manager, assigner);

    BoardResult.BoardRow row = onlyRow(fixture.service);

    assertEquals(BoardPhase.PENDING, row.phase());
    assertTrue(row.platformPending());
    assertEquals(List.of("1", "2"), row.platformCandidates());
  }

  private static BoardResult.BoardRow onlyRow(EtaService service) {
    List<BoardResult.BoardRow> rows =
        service.getBoard("SURN", "PPK", null, Duration.ofMinutes(10)).rows();
    assertEquals(1, rows.size());
    return rows.get(0);
  }

  private static final class Fixture {
    private final UUID routeUuid = UUID.randomUUID();
    private final UUID worldId = UUID.randomUUID();
    private final RouteDefinition route =
        new RouteDefinition(
            RouteId.of("SURN:L1:R1"),
            List.of(AAA, PPK_1),
            Optional.of(RouteMetadata.of("SURN", "L1", "R1", null)));
    private final TrainSnapshotStore snapshots = new TrainSnapshotStore();
    private final EtaService service;

    /**
     * @param tracks 图上存在的 PPK 股道
     */
    private Fixture(List<NodeId> tracks) {
      List<RouteStop> stops =
          List.of(
              new RouteStop(
                  routeUuid,
                  0,
                  Optional.empty(),
                  Optional.of(AAA.value()),
                  Optional.empty(),
                  RouteStopPassType.STOP,
                  Optional.empty()),
              new RouteStop(
                  routeUuid,
                  1,
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  RouteStopPassType.TERMINATE,
                  Optional.of("DYNAMIC:SURN:S:PPK")));
      RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
      when(routes.findById(routeUuid)).thenReturn(Optional.of(route));
      when(routes.listStops(any())).thenReturn(stops);
      RailGraphService graphs = mock(RailGraphService.class);
      RailGraphService.RailGraphSnapshot snapshot =
          new RailGraphService.RailGraphSnapshot(graph(tracks), Instant.now());
      when(graphs.getSnapshot(worldId)).thenReturn(Optional.of(snapshot));
      when(graphs.snapshotAll()).thenReturn(Map.of(worldId, snapshot));
      when(graphs.findWorldIdForPath(anyList())).thenReturn(Optional.of(worldId));
      service = new EtaService(snapshots, graphs, routes);
    }

    Fixture running(int index, NodeId lastPassed) {
      snapshots.update(
          TRAIN,
          new TrainRuntimeSnapshot(
              1L,
              Instant.now(),
              worldId,
              routeUuid,
              route.id(),
              index,
              Optional.of(lastPassed),
              Optional.of(lastPassed),
              Optional.empty(),
              Optional.of(SignalAspect.PROCEED),
              Optional.empty()));
      return this;
    }

    SpawnTicket ticket(Instant due) {
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
              "R1",
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
          Optional.empty(),
          TripSource.SCHEDULED,
          0);
    }
  }

  /** AAA 分别连到 PPK 的各条股道，每段 60 格。 */
  private static RailGraph graph(List<NodeId> tracks) {
    Map<NodeId, RailNode> nodes = new HashMap<>();
    Map<EdgeId, RailEdge> edges = new HashMap<>();
    List<NodeId> all = new ArrayList<>(tracks);
    all.add(0, AAA);
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
    for (NodeId track : tracks) {
      EdgeId id = EdgeId.undirected(AAA, track);
      edges.put(id, new RailEdge(id, AAA, track, 60, 0.0, true, Optional.empty()));
    }
    return new SimpleRailGraph(nodes, edges, Set.of());
  }
}
