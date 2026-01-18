package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphConflictSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteProgress;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.TrainRuntimeState;
import org.junit.jupiter.api.Test;

class OccupancyRequestBuilderTest {

  @Test
  void buildCreatesLookaheadResources() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
    NodeId nodeC = NodeId.of("C");
    RailNode stationA =
        new SignRailNode(
            nodeA,
            NodeType.STATION,
            new Vector(0.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode stationB =
        new SignRailNode(
            nodeB,
            NodeType.STATION,
            new Vector(10.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode stationC =
        new SignRailNode(
            nodeC,
            NodeType.STATION,
            new Vector(20.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    EdgeId edgeAB = EdgeId.undirected(nodeA, nodeB);
    EdgeId edgeBC = EdgeId.undirected(nodeB, nodeC);
    RailEdge ab = new RailEdge(edgeAB, nodeA, nodeB, 20, 8.0, true, Optional.empty());
    RailEdge bc = new RailEdge(edgeBC, nodeB, nodeC, 30, 8.0, true, Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(nodeA, stationA, nodeB, stationB, nodeC, stationC),
            Map.of(edgeAB, ab, edgeBC, bc),
            Set.of());
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 2, 0);
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("OP:LINE:ROUTE"), List.of(nodeA, nodeB, nodeC), Optional.empty());
    TrainRuntimeState state = new StubState("Train-1", new StubProgress(route.id(), 0));

    Optional<OccupancyRequest> requestOpt =
        builder.build(state, route, Instant.parse("2026-01-01T00:00:00Z"));
    assertTrue(requestOpt.isPresent());
    OccupancyRequest request = requestOpt.get();
    assertEquals(6, request.resourceList().size());
    assertTrue(request.resourceList().contains(OccupancyResource.forNode(nodeA)));
    assertTrue(request.resourceList().contains(OccupancyResource.forNode(nodeB)));
    assertTrue(request.resourceList().contains(OccupancyResource.forNode(nodeC)));
    assertTrue(request.resourceList().contains(OccupancyResource.forEdge(edgeAB)));
    assertTrue(request.resourceList().contains(OccupancyResource.forEdge(edgeBC)));
    String conflictKey =
        ((RailGraphConflictSupport) graph).conflictKeyForEdge(edgeAB).orElseThrow();
    assertTrue(request.resourceList().contains(OccupancyResource.forConflict(conflictKey)));
  }

  @Test
  void buildReturnsEmptyWhenAtRouteEnd() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
    RailNode stationA =
        new SignRailNode(
            nodeA,
            NodeType.STATION,
            new Vector(0.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode stationB =
        new SignRailNode(
            nodeB,
            NodeType.STATION,
            new Vector(10.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    EdgeId edgeAB = EdgeId.undirected(nodeA, nodeB);
    RailEdge ab = new RailEdge(edgeAB, nodeA, nodeB, 20, 8.0, true, Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(Map.of(nodeA, stationA, nodeB, stationB), Map.of(edgeAB, ab), Set.of());
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 1, 0);
    RouteDefinition route =
        new RouteDefinition(RouteId.of("OP:LINE:ROUTE"), List.of(nodeA, nodeB), Optional.empty());
    TrainRuntimeState state = new StubState("Train-1", new StubProgress(route.id(), 1));

    Optional<OccupancyRequest> requestOpt =
        builder.build(state, route, Instant.parse("2026-01-01T00:00:00Z"));
    assertTrue(requestOpt.isEmpty());
  }

  @Test
  void switcherZoneBlocksLateSwitcherConflict() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
    NodeId nodeS = NodeId.of("S");
    NodeId nodeC = NodeId.of("C");
    RailNode a =
        new SignRailNode(
            nodeA,
            NodeType.WAYPOINT,
            new Vector(0.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode b =
        new SignRailNode(
            nodeB,
            NodeType.WAYPOINT,
            new Vector(10.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode s =
        new SignRailNode(
            nodeS,
            NodeType.SWITCHER,
            new Vector(20.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode c =
        new SignRailNode(
            nodeC,
            NodeType.WAYPOINT,
            new Vector(30.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    EdgeId edgeAB = EdgeId.undirected(nodeA, nodeB);
    EdgeId edgeBS = EdgeId.undirected(nodeB, nodeS);
    EdgeId edgeSC = EdgeId.undirected(nodeS, nodeC);
    RailEdge ab = new RailEdge(edgeAB, nodeA, nodeB, 10, 8.0, true, Optional.empty());
    RailEdge bs = new RailEdge(edgeBS, nodeB, nodeS, 10, 8.0, true, Optional.empty());
    RailEdge sc = new RailEdge(edgeSC, nodeS, nodeC, 10, 8.0, true, Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(nodeA, a, nodeB, b, nodeS, s, nodeC, c),
            Map.of(edgeAB, ab, edgeBS, bs, edgeSC, sc),
            Set.of());
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 3, 1);
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("OP:LINE:ROUTE"), List.of(nodeA, nodeB, nodeS, nodeC), Optional.empty());
    TrainRuntimeState state = new StubState("Train-1", new StubProgress(route.id(), 0));

    Optional<OccupancyRequest> requestOpt =
        builder.build(state, route, Instant.parse("2026-01-01T00:00:00Z"));
    assertTrue(requestOpt.isPresent());
    OccupancyRequest request = requestOpt.get();
    String switcherKey = OccupancyResourceResolver.switcherConflictId(s);
    assertFalse(request.resourceList().contains(OccupancyResource.forConflict(switcherKey)));
  }

  @Test
  void switcherZoneKeepsNearSwitcherConflict() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeS = NodeId.of("S");
    NodeId nodeB = NodeId.of("B");
    RailNode a =
        new SignRailNode(
            nodeA,
            NodeType.WAYPOINT,
            new Vector(0.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode s =
        new SignRailNode(
            nodeS,
            NodeType.SWITCHER,
            new Vector(10.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode b =
        new SignRailNode(
            nodeB,
            NodeType.WAYPOINT,
            new Vector(20.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    EdgeId edgeAS = EdgeId.undirected(nodeA, nodeS);
    EdgeId edgeSB = EdgeId.undirected(nodeS, nodeB);
    RailEdge as = new RailEdge(edgeAS, nodeA, nodeS, 10, 8.0, true, Optional.empty());
    RailEdge sb = new RailEdge(edgeSB, nodeS, nodeB, 10, 8.0, true, Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(nodeA, a, nodeS, s, nodeB, b), Map.of(edgeAS, as, edgeSB, sb), Set.of());
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 2, 1);
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("OP:LINE:ROUTE"), List.of(nodeA, nodeS, nodeB), Optional.empty());
    TrainRuntimeState state = new StubState("Train-1", new StubProgress(route.id(), 0));

    Optional<OccupancyRequest> requestOpt =
        builder.build(state, route, Instant.parse("2026-01-01T00:00:00Z"));
    assertTrue(requestOpt.isPresent());
    OccupancyRequest request = requestOpt.get();
    String switcherKey = OccupancyResourceResolver.switcherConflictId(s);
    assertTrue(request.resourceList().contains(OccupancyResource.forConflict(switcherKey)));
  }

  private record StubProgress(RouteId routeId, int currentIndex) implements RouteProgress {

    @Override
    public Optional<NodeId> nextTarget() {
      return Optional.empty();
    }
  }

  private record StubState(String trainName, RouteProgress routeProgress)
      implements TrainRuntimeState {

    @Override
    public Optional<NodeId> occupiedNode() {
      return Optional.empty();
    }

    @Override
    public Optional<Instant> estimatedArrivalTime() {
      return Optional.empty();
    }

    @Override
    public Instant lastUpdatedAt() {
      return Instant.parse("2026-01-01T00:00:00Z");
    }
  }
}
