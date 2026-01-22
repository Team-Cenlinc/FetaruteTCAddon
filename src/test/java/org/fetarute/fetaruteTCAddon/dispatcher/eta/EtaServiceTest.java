package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.util.Vector;
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
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.HeadwayRule;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnServiceKey;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.SpawnTicket;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.spawn.TicketAssigner;
import org.junit.jupiter.api.Test;

class EtaServiceTest {

  @Test
  void trainEtaIncludesDwellTime() {
    UUID routeUuid = UUID.randomUUID();
    UUID worldId = UUID.randomUUID();
    NodeId start = NodeId.of("SURN:S:AAA:1");
    NodeId next = NodeId.of("SURN:S:BBB:1");

    RouteDefinition route =
        new RouteDefinition(RouteId.of("SURN:L1:R1"), List.of(start, next), Optional.empty());
    RailGraph graph = buildGraph(start, next, 60);

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findById(routeUuid)).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any()))
        .thenReturn(new OccupancyDecision(true, Instant.now(), SignalAspect.PROCEED, List.of()));

    TrainSnapshotStore snapshotStore = new TrainSnapshotStore();
    snapshotStore.update(
        "train-1",
        new TrainRuntimeSnapshot(
            1L,
            Instant.now(),
            worldId,
            routeUuid,
            route.id(),
            0,
            Optional.empty(),
            Optional.empty(),
            Optional.of(30),
            Optional.of(SignalAspect.PROCEED),
            Optional.empty()));

    EtaService service =
        new EtaService(
            snapshotStore,
            railGraphService,
            routeDefinitions,
            occupancyManager,
            HeadwayRule.fixed(Duration.ZERO),
            () -> 2,
            () -> 0,
            () -> 2);

    EtaResult result = service.getForTrain("train-1", EtaTarget.nextStop());

    assertEquals(10, result.travelSec());
    assertEquals(30, result.dwellSec());
    assertEquals(0, result.waitSec());
    assertEquals(1, result.etaMinutesRounded());
  }

  @Test
  void boardUsesLayoverReadyAtForTickets() {
    UUID routeUuid = UUID.randomUUID();
    UUID worldId = UUID.randomUUID();
    NodeId start = NodeId.of("SURN:S:AAA:1");
    NodeId target = NodeId.of("SURN:S:BBB:1");
    RouteDefinition route =
        new RouteDefinition(RouteId.of("SURN:L1:R1"), List.of(start, target), Optional.empty());
    RailGraph graph = buildGraph(start, target, 60);

    RailGraphService railGraphService = mock(RailGraphService.class);
    when(railGraphService.findWorldIdForPath(anyList())).thenReturn(Optional.of(worldId));
    when(railGraphService.getSnapshot(worldId))
        .thenReturn(Optional.of(new RailGraphService.RailGraphSnapshot(graph, Instant.now())));

    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    when(routeDefinitions.findById(routeUuid)).thenReturn(Optional.of(route));

    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    when(occupancyManager.canEnter(any()))
        .thenReturn(new OccupancyDecision(true, Instant.now(), SignalAspect.PROCEED, List.of()));

    TrainSnapshotStore snapshotStore = new TrainSnapshotStore();
    EtaService service =
        new EtaService(
            snapshotStore,
            railGraphService,
            routeDefinitions,
            occupancyManager,
            HeadwayRule.fixed(Duration.ZERO),
            () -> 2,
            () -> 0,
            () -> 2);

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
    Instant dueAt = Instant.now();
    SpawnTicket ticket =
        new SpawnTicket(UUID.randomUUID(), spawnService, dueAt, dueAt, 0, Optional.empty());

    SpawnManager spawnManager = mock(SpawnManager.class);
    when(spawnManager.snapshotQueue()).thenReturn(List.of(ticket));
    TicketAssigner assigner = mock(TicketAssigner.class);
    when(assigner.snapshotPendingTickets()).thenReturn(List.of());

    Instant readyAt = Instant.now().plusSeconds(120);
    LayoverRegistry layoverRegistry = new LayoverRegistry();
    layoverRegistry.register("LAYOVER-1", start.value(), start, readyAt, java.util.Map.of());

    service.attachTicketSources(spawnManager, assigner);
    service.attachLayoverRegistry(layoverRegistry);

    BoardResult board = service.getBoard("SURN", "BBB", null, Duration.ofMinutes(10));

    assertFalse(board.rows().isEmpty());
    EtaResult result = service.getForTicket(ticket.id().toString());
    assertTrue(result.eta().isAfter(readyAt.minusSeconds(1)));
  }

  private RailGraph buildGraph(NodeId start, NodeId end, int lengthBlocks) {
    SignRailNode startNode =
        new SignRailNode(
            start, NodeType.WAYPOINT, new Vector(0, 0, 0), Optional.empty(), Optional.empty());
    SignRailNode endNode =
        new SignRailNode(
            end, NodeType.WAYPOINT, new Vector(0, 0, 0), Optional.empty(), Optional.empty());
    EdgeId edgeId = EdgeId.undirected(start, end);
    RailEdge edge = new RailEdge(edgeId, start, end, lengthBlocks, 0.0, true, Optional.empty());
    return new SimpleRailGraph(
        java.util.Map.of(start, startNode, end, endNode), java.util.Map.of(edgeId, edge), Set.of());
  }
}
