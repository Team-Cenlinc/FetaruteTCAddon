package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphBuilder;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.junit.jupiter.api.Test;

class DynamicDestinationResolverTest {

  @Test
  void resolverReturnsSelectedNodeWithoutAcquireOrControlSideEffects() {
    RouteDefinitionCache routeDefinitions = mock(RouteDefinitionCache.class);
    OccupancyManager occupancyManager = mock(OccupancyManager.class);
    RailGraph graph = mock(RailGraph.class);
    UUID worldId = UUID.randomUUID();
    RailGraphService railGraphService = new RailGraphService(mock(RailGraphBuilder.class));
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    railGraphService.putSnapshot(world, graph, Instant.parse("2026-01-01T00:00:00Z"));

    NodeId fromId = NodeId.of("OP:W:FROM:1:0");
    NodeId occupiedId = NodeId.of("OP:S:DEST:1");
    NodeId freeId = NodeId.of("OP:S:DEST:2");
    RailNode from = mockNode(graph, fromId, new Vector(0, 0, 0), NodeType.WAYPOINT);
    RailNode occupied = mockNode(graph, occupiedId, new Vector(10, 0, 0), NodeType.STATION);
    RailNode free = mockNode(graph, freeId, new Vector(20, 0, 0), NodeType.STATION);
    mockEdges(graph, fromId, edge(from, occupied), edge(from, free));
    mockEdges(graph, occupiedId);
    mockEdges(graph, freeId);
    when(graph.nodes()).thenReturn(List.of(from, occupied, free));
    when(occupancyManager.isNodeOccupied(occupiedId)).thenReturn(true);

    RouteId routeId = RouteId.of("DYNAMIC-RESOLVE");
    RouteDefinition route = mock(RouteDefinition.class);
    when(route.id()).thenReturn(routeId);
    when(route.waypoints()).thenReturn(Arrays.asList(fromId, NodeId.of("PLACEHOLDER")));
    RouteStop stop = mock(RouteStop.class);
    when(stop.notes()).thenReturn(Optional.of("DYNAMIC:OP:S:DEST:[1:2]"));
    when(routeDefinitions.findStop(routeId, 1)).thenReturn(Optional.of(stop));

    DynamicDestinationResolver resolver =
        new DynamicDestinationResolver(
            new DynamicPlatformAllocator(routeDefinitions, occupancyManager, message -> {}),
            railGraphService,
            message -> {});

    Optional<DynamicDestinationResolver.ResolvedDynamicDestination> result =
        resolver.resolveSignalTickDestination(
            "train-dynamic", route, 0, worldId, fromId, Optional.empty());

    assertTrue(result.isPresent());
    assertEquals(1, result.get().stopIndex());
    assertEquals(freeId, result.get().node());
    verify(occupancyManager, never()).acquire(any(OccupancyRequest.class));
  }

  private static RailNode mockNode(RailGraph graph, NodeId id, Vector pos, NodeType type) {
    RailNode node = mock(RailNode.class);
    when(node.id()).thenReturn(id);
    when(node.worldPosition()).thenReturn(pos);
    when(node.type()).thenReturn(type);
    when(graph.findNode(id)).thenReturn(Optional.of(node));
    return node;
  }

  private static RailEdge edge(RailNode from, RailNode to) {
    return new RailEdge(
        EdgeId.undirected(from.id(), to.id()), from.id(), to.id(), 10, 1.0, true, Optional.empty());
  }

  private static void mockEdges(RailGraph graph, NodeId id, RailEdge... edges) {
    when(graph.edgesFrom(id)).thenReturn(new HashSet<>(Arrays.asList(edges)));
  }
}
