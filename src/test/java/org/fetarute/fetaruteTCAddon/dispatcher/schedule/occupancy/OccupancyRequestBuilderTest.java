package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.query.RailTravelTimeModel;
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
    RailTravelTimeModel timeModel = (g, edge, from, to) -> Optional.of(Duration.ofSeconds(5));
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, timeModel, 2);
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("OP:LINE:ROUTE"), List.of(nodeA, nodeB, nodeC), Optional.empty());
    TrainRuntimeState state = new StubState("Train-1", new StubProgress(route.id(), 0));

    Optional<OccupancyRequest> requestOpt =
        builder.build(state, route, Instant.parse("2026-01-01T00:00:00Z"));
    assertTrue(requestOpt.isPresent());
    OccupancyRequest request = requestOpt.get();
    assertEquals(Duration.ofSeconds(10), request.travelTime());
    assertEquals(2, request.resourceList().size());
    assertTrue(request.resourceList().contains(OccupancyResource.forEdge(edgeAB)));
    assertTrue(request.resourceList().contains(OccupancyResource.forEdge(edgeBC)));
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
    RailTravelTimeModel timeModel = (g, edge, from, to) -> Optional.of(Duration.ofSeconds(5));
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, timeModel, 1);
    RouteDefinition route =
        new RouteDefinition(RouteId.of("OP:LINE:ROUTE"), List.of(nodeA, nodeB), Optional.empty());
    TrainRuntimeState state = new StubState("Train-1", new StubProgress(route.id(), 1));

    Optional<OccupancyRequest> requestOpt =
        builder.build(state, route, Instant.parse("2026-01-01T00:00:00Z"));
    assertTrue(requestOpt.isEmpty());
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
