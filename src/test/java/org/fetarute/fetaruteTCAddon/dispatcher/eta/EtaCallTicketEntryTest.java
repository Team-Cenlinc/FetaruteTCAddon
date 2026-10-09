package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.company.model.RouteStopPassType;
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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.model.TripSource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.OnDemandTrip;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnForecastSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnServiceKey;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
import org.junit.jupiter.api.Test;

/**
 * 区间生成的叫车票：车在生成点出现，站牌排队行从那里起算到站时间，不从交路首站起算。
 *
 * <p>交路 AAA → XXX（区间点）→ BBB → CCC，每段 600 格，在 BBB 看站牌。
 */
class EtaCallTicketEntryTest {

  private static final NodeId AAA = NodeId.of("SURN:S:AAA:1");
  private static final NodeId XXX = NodeId.of("SURN:AAA:BBB:1:001");
  private static final NodeId BBB = NodeId.of("SURN:S:BBB:1");
  private static final NodeId CCC = NodeId.of("SURN:S:CCC:1");

  private final UUID routeUuid = UUID.randomUUID();
  private final UUID worldId = UUID.randomUUID();
  private final Instant now = Instant.now();

  @Test
  void aWaypointSpawnIsTimedFromItsEntry() {
    Instant fromStart = onlyRow(board(callTicket(OptionalInt.empty()))).eta();
    Instant fromEntry = onlyRow(board(callTicket(OptionalInt.of(1)))).eta();

    assertTrue(
        fromEntry.isBefore(fromStart), "生成点离本站近，应早于从首站起算: " + fromEntry + " vs " + fromStart);
  }

  @Test
  void onlyCallTicketsWithAnUpstreamEntryStartElsewhere() {
    SpawnTicket entry = callTicket(OptionalInt.of(1));

    assertEquals(1, EtaService.ticketStartIndex(entry, 2));
    assertEquals(0, EtaService.ticketStartIndex(entry, 1), "生成点不在本站上游时按首站算");
    assertEquals(0, EtaService.ticketStartIndex(callTicket(OptionalInt.empty()), 2));
  }

  private BoardResult.BoardRow onlyRow(EtaService service) {
    List<BoardResult.BoardRow> rows =
        service.getBoard("SURN", "BBB", null, Duration.ofMinutes(30)).rows();
    assertEquals(1, rows.size(), rows::toString);
    return rows.get(0);
  }

  private EtaService board(SpawnTicket ticket) {
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("SURN:L1:R1"),
            List.of(AAA, XXX, BBB, CCC),
            Optional.of(RouteMetadata.of("SURN", "L1", "R1", null)));
    RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
    when(routes.findById(routeUuid)).thenReturn(Optional.of(route));
    when(routes.listStops(any()))
        .thenReturn(
            List.of(
                stop(0, AAA, RouteStopPassType.STOP),
                stop(1, XXX, RouteStopPassType.PASS),
                stop(2, BBB, RouteStopPassType.STOP),
                stop(3, CCC, RouteStopPassType.TERMINATE)));
    RailGraphService graphs = mock(RailGraphService.class);
    when(graphs.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(linearGraph(), now)));
    when(graphs.findWorldIdForPath(anyList())).thenReturn(Optional.of(worldId));
    EtaService service = new EtaService(new TrainSnapshotStore(), graphs, routes);
    SpawnManager manager =
        mock(SpawnManager.class, withSettings().extraInterfaces(SpawnForecastSupport.class));
    when(manager.snapshotQueue()).thenReturn(List.of(ticket));
    when(((SpawnForecastSupport) manager).snapshotForecast(any(), any(), anyInt()))
        .thenReturn(List.of());
    TicketAssigner assigner = mock(TicketAssigner.class);
    when(assigner.snapshotPendingTickets()).thenReturn(List.of());
    service.attachTicketSources(manager, assigner);
    return service;
  }

  private SpawnTicket callTicket(OptionalInt entry) {
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
            Duration.ofMinutes(10),
            AAA.value());
    return new SpawnTicket(
        UUID.randomUUID(),
        spawnService,
        now,
        now,
        now,
        0,
        0L,
        Optional.empty(),
        Optional.empty(),
        Optional.of(OnDemandTrip.format(UUID.randomUUID() + "@SURN:BBB", entry)),
        TripSource.ON_DEMAND,
        0);
  }

  private RouteStop stop(int sequence, NodeId node, RouteStopPassType passType) {
    return new RouteStop(
        routeUuid,
        sequence,
        Optional.empty(),
        Optional.of(node.value()),
        Optional.empty(),
        passType,
        Optional.empty());
  }

  private static RailGraph linearGraph() {
    NodeId[] nodes = {AAA, XXX, BBB, CCC};
    Map<NodeId, RailNode> nodeMap = new HashMap<>();
    Map<EdgeId, RailEdge> edgeMap = new HashMap<>();
    for (int i = 0; i < nodes.length; i++) {
      nodeMap.put(
          nodes[i],
          new SignRailNode(
              nodes[i],
              NodeType.WAYPOINT,
              new Vector(i * 600, 0, 0),
              Optional.empty(),
              Optional.empty()));
      if (i > 0) {
        EdgeId edgeId = EdgeId.undirected(nodes[i - 1], nodes[i]);
        edgeMap.put(
            edgeId, new RailEdge(edgeId, nodes[i - 1], nodes[i], 600, 0.0, true, Optional.empty()));
      }
    }
    return new SimpleRailGraph(nodeMap, edgeMap, Set.of());
  }
}
