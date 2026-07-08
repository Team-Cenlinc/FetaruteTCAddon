package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphConflictSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphCorridorInfo;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphCorridorSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphSectionSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SingleLineSectionInfo;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.node.WaypointMetadata;
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
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 6, 0, 0, 0);
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("OP:LINE:ROUTE"), List.of(nodeA, nodeB, nodeC), Optional.empty());
    TrainRuntimeState state = new StubState("Train-1", new StubProgress(route.id(), 0));

    Optional<OccupancyRequest> requestOpt =
        builder.build(state, route, Instant.parse("2026-01-01T00:00:00Z"));
    assertTrue(requestOpt.isPresent());
    OccupancyRequest request = requestOpt.get();
    assertEquals(7, request.resourceList().size());
    assertTrue(request.resourceList().contains(OccupancyResource.forNode(nodeA)));
    assertTrue(request.resourceList().contains(OccupancyResource.forNode(nodeB)));
    assertTrue(request.resourceList().contains(OccupancyResource.forNode(nodeC)));
    assertTrue(request.resourceList().contains(OccupancyResource.forEdge(edgeAB)));
    assertTrue(request.resourceList().contains(OccupancyResource.forEdge(edgeBC)));
    String conflictKey =
        ((RailGraphConflictSupport) graph).conflictKeyForEdge(edgeAB).orElseThrow();
    SingleLineSectionInfo section =
        ((RailGraphSectionSupport) graph).sectionInfoForEdge(edgeAB).orElseThrow();
    assertTrue(request.resourceList().contains(OccupancyResource.forConflict(conflictKey)));
    assertTrue(request.resourceList().contains(OccupancyResource.forConflict(section.key())));
    assertEquals(CorridorDirection.A_TO_B, request.corridorDirections().get(conflictKey));
    assertEquals(CorridorDirection.A_TO_B, request.corridorDirections().get(section.key()));
    assertEquals(0, request.conflictEntryOrders().get(conflictKey));
    assertEquals(0, request.conflictEntryOrders().get(section.key()));
  }

  @Test
  void forwardRiskUsesAdvisoryWindowNotMovementRequiredResources() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
    NodeId nodeC = NodeId.of("C");
    NodeId nodeD = NodeId.of("D");
    RailNode a = waypoint(nodeA, 0.0);
    RailNode b = waypoint(nodeB, 10.0);
    RailNode c = waypoint(nodeC, 20.0);
    RailNode d = waypoint(nodeD, 30.0);
    EdgeId edgeAB = EdgeId.undirected(nodeA, nodeB);
    EdgeId edgeBC = EdgeId.undirected(nodeB, nodeC);
    EdgeId edgeCD = EdgeId.undirected(nodeC, nodeD);
    RailEdge ab = new RailEdge(edgeAB, nodeA, nodeB, 10, 8.0, true, Optional.empty());
    RailEdge bc = new RailEdge(edgeBC, nodeB, nodeC, 10, 8.0, true, Optional.empty());
    RailEdge cd = new RailEdge(edgeCD, nodeC, nodeD, 10, 8.0, true, Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(nodeA, a, nodeB, b, nodeC, c, nodeD, d),
            Map.of(edgeAB, ab, edgeBC, bc, edgeCD, cd),
            Set.of());
    List<NodeId> nodes = List.of(nodeA, nodeB, nodeC, nodeD);

    OccupancyRequestContext hard =
        new OccupancyRequestBuilder(graph, 1, 0, 0, 0)
            .buildContextFromNodes(
                "Train-1",
                Optional.of(RouteId.of("OP:LINE:ROUTE")),
                nodes,
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0,
                AuthorizationPurpose.RUNTIME_MOVE)
            .orElseThrow();
    OccupancyRequestContext advisory =
        new OccupancyRequestBuilder(graph, 3, 0, 0, 0)
            .buildContextFromNodes(
                "Train-1",
                Optional.of(RouteId.of("OP:LINE:ROUTE")),
                nodes,
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0,
                AuthorizationPurpose.RUNTIME_MOVE)
            .orElseThrow();

    assertFalse(hard.request().resourceList().contains(OccupancyResource.forNode(nodeC)));
    assertFalse(hard.request().resourceList().contains(OccupancyResource.forEdge(edgeBC)));
    assertTrue(advisory.pathNodes().contains(nodeC));
    assertTrue(advisory.request().resourceList().contains(OccupancyResource.forNode(nodeC)));
    assertEquals(
        ResourceIntent.MOVEMENT_REQUIRED,
        hard.request().intentFor(OccupancyResource.forEdge(edgeAB)));
  }

  @Test
  void distanceLookaheadExtendsPastEdgeFloorInDenseShortEdges() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
    NodeId nodeC = NodeId.of("C");
    NodeId nodeD = NodeId.of("D");
    EdgeId edgeAB = EdgeId.undirected(nodeA, nodeB);
    EdgeId edgeBC = EdgeId.undirected(nodeB, nodeC);
    EdgeId edgeCD = EdgeId.undirected(nodeC, nodeD);
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(
                nodeA, waypoint(nodeA, 0.0),
                nodeB, waypoint(nodeB, 1.0),
                nodeC, waypoint(nodeC, 2.0),
                nodeD, waypoint(nodeD, 3.0)),
            Map.of(
                edgeAB, new RailEdge(edgeAB, nodeA, nodeB, 2, 8.0, true, Optional.empty()),
                edgeBC, new RailEdge(edgeBC, nodeB, nodeC, 2, 8.0, true, Optional.empty()),
                edgeCD, new RailEdge(edgeCD, nodeC, nodeD, 2, 8.0, true, Optional.empty())),
            Set.of());
    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(graph, 2, 0, 0, 0, 5L, 4, msg -> {});

    OccupancyRequestContext context =
        builder
            .buildContextFromNodes(
                "Train-1",
                Optional.of(RouteId.of("OP:LINE:ROUTE")),
                List.of(nodeA, nodeB, nodeC, nodeD),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow();

    assertEquals(List.of(nodeA, nodeB, nodeC, nodeD), context.pathNodes());
    assertEquals(3, context.edges().size());
  }

  @Test
  void distanceLookaheadStopsAtMaxEdgesEvenWhenDistanceFloorIsUnmet() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
    NodeId nodeC = NodeId.of("C");
    NodeId nodeD = NodeId.of("D");
    NodeId nodeE = NodeId.of("E");
    EdgeId edgeAB = EdgeId.undirected(nodeA, nodeB);
    EdgeId edgeBC = EdgeId.undirected(nodeB, nodeC);
    EdgeId edgeCD = EdgeId.undirected(nodeC, nodeD);
    EdgeId edgeDE = EdgeId.undirected(nodeD, nodeE);
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(
                nodeA, waypoint(nodeA, 0.0),
                nodeB, waypoint(nodeB, 1.0),
                nodeC, waypoint(nodeC, 2.0),
                nodeD, waypoint(nodeD, 3.0),
                nodeE, waypoint(nodeE, 4.0)),
            Map.of(
                edgeAB, new RailEdge(edgeAB, nodeA, nodeB, 1, 8.0, true, Optional.empty()),
                edgeBC, new RailEdge(edgeBC, nodeB, nodeC, 1, 8.0, true, Optional.empty()),
                edgeCD, new RailEdge(edgeCD, nodeC, nodeD, 1, 8.0, true, Optional.empty()),
                edgeDE, new RailEdge(edgeDE, nodeD, nodeE, 1, 8.0, true, Optional.empty())),
            Set.of());
    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(graph, 1, 0, 0, 0, 100L, 3, msg -> {});

    OccupancyRequestContext context =
        builder
            .buildContextFromNodes(
                "Train-1",
                Optional.of(RouteId.of("OP:LINE:ROUTE")),
                List.of(nodeA, nodeB, nodeC, nodeD, nodeE),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow();

    assertEquals(List.of(nodeA, nodeB, nodeC, nodeD), context.pathNodes());
    assertEquals(3, context.edges().size());
  }

  @Test
  void buildExpandsRouteSegmentBeforeResolvingDirectionAndEntryOrder() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeM = NodeId.of("M");
    NodeId nodeC = NodeId.of("C");
    RailNode a =
        new SignRailNode(
            nodeA,
            NodeType.WAYPOINT,
            new Vector(0.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode m =
        new SignRailNode(
            nodeM,
            NodeType.WAYPOINT,
            new Vector(10.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode c =
        new SignRailNode(
            nodeC,
            NodeType.WAYPOINT,
            new Vector(20.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    EdgeId edgeAM = EdgeId.undirected(nodeA, nodeM);
    EdgeId edgeMC = EdgeId.undirected(nodeM, nodeC);
    RailEdge am = new RailEdge(edgeAM, nodeA, nodeM, 10, 8.0, true, Optional.empty());
    RailEdge mc = new RailEdge(edgeMC, nodeM, nodeC, 10, 8.0, true, Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(nodeA, a, nodeM, m, nodeC, c), Map.of(edgeAM, am, edgeMC, mc), Set.of());
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 6, 0, 0, 0);
    RouteDefinition route =
        new RouteDefinition(RouteId.of("OP:LINE:ROUTE"), List.of(nodeA, nodeC), Optional.empty());

    Optional<OccupancyRequestContext> contextOpt =
        builder.buildContextFromNodes(
            "Train-1",
            Optional.of(route.id()),
            route.waypoints(),
            0,
            Instant.parse("2026-01-01T00:00:00Z"),
            0);

    assertTrue(contextOpt.isPresent());
    OccupancyRequestContext context = contextOpt.get();
    OccupancyRequest request = context.request();
    assertEquals(List.of(nodeA, nodeM, nodeC), context.pathNodes());
    assertTrue(request.resourceList().contains(OccupancyResource.forEdge(edgeAM)));
    assertTrue(request.resourceList().contains(OccupancyResource.forEdge(edgeMC)));
    String firstConflict =
        ((RailGraphConflictSupport) graph).conflictKeyForEdge(edgeAM).orElseThrow();
    String secondConflict =
        ((RailGraphConflictSupport) graph).conflictKeyForEdge(edgeMC).orElseThrow();
    assertEquals(CorridorDirection.A_TO_B, request.corridorDirections().get(firstConflict));
    assertEquals(CorridorDirection.A_TO_B, request.corridorDirections().get(secondConflict));
    assertEquals(0, request.conflictEntryOrders().get(firstConflict));
    int expectedSecondEntry = firstConflict.equals(secondConflict) ? 0 : 1;
    assertEquals(expectedSecondEntry, request.conflictEntryOrders().get(secondConflict));
    DirectedTraversalContext directed = request.directedContext().orElseThrow();
    assertEquals(nodeA, directed.effectiveFromNode().orElseThrow());
    assertEquals(nodeM, directed.effectiveToNode().orElseThrow());
    assertEquals(List.of(nodeA, nodeM, nodeC), directed.expandedPathNodes());
    assertEquals(2, directed.directedEdges().size());
    assertEquals(CorridorDirection.A_TO_B, directed.singleConflictDirections().get(firstConflict));

    OccupancyRequest eventRequest = request.withDirectedSource("EVENT");
    OccupancyRequest periodicRequest = request.withDirectedSource("PERIODIC_TICK");
    OccupancyRequest progressRequest = request.withDirectedSource("PROGRESS_TRIGGER");
    assertEquals(
        eventRequest.directedContext().orElseThrow().expandedPathNodes(),
        periodicRequest.directedContext().orElseThrow().expandedPathNodes());
    assertEquals(
        eventRequest.directedContext().orElseThrow().singleConflictDirections(),
        progressRequest.directedContext().orElseThrow().singleConflictDirections());
  }

  @Test
  void buildAddsSectionTokenAcrossSwitcherSplitMicroCorridors() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeS = NodeId.of("S");
    NodeId nodeB = NodeId.of("B");
    RailNode a = waypoint(nodeA, 0.0);
    RailNode s =
        new SignRailNode(
            nodeS,
            NodeType.SWITCHER,
            new Vector(10.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode b = waypoint(nodeB, 20.0);
    EdgeId edgeAS = EdgeId.undirected(nodeA, nodeS);
    EdgeId edgeSB = EdgeId.undirected(nodeS, nodeB);
    RailEdge as = new RailEdge(edgeAS, nodeA, nodeS, 10, 8.0, true, Optional.empty());
    RailEdge sb = new RailEdge(edgeSB, nodeS, nodeB, 10, 8.0, true, Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(nodeA, a, nodeS, s, nodeB, b), Map.of(edgeAS, as, edgeSB, sb), Set.of());
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 2, 0, 0, 0);

    OccupancyRequest request =
        builder
            .buildContextFromNodes(
                "train",
                Optional.of(RouteId.of("r")),
                List.of(nodeA, nodeS, nodeB),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow()
            .request();

    String corridorAS = ((RailGraphConflictSupport) graph).conflictKeyForEdge(edgeAS).orElseThrow();
    String corridorSB = ((RailGraphConflictSupport) graph).conflictKeyForEdge(edgeSB).orElseThrow();
    SingleLineSectionInfo section =
        ((RailGraphSectionSupport) graph).sectionInfoForEdge(edgeAS).orElseThrow();
    assertNotEquals(corridorAS, corridorSB);
    assertEquals(
        section.key(),
        ((RailGraphSectionSupport) graph).sectionInfoForEdge(edgeSB).orElseThrow().key());
    assertTrue(request.resourceList().contains(OccupancyResource.forConflict(corridorAS)));
    assertTrue(request.resourceList().contains(OccupancyResource.forConflict(corridorSB)));
    assertTrue(request.resourceList().contains(OccupancyResource.forConflict(section.key())));
    assertEquals(CorridorDirection.A_TO_B, request.corridorDirections().get(section.key()));
    assertEquals(0, request.conflictEntryOrders().get(section.key()));
    assertEquals(
        CorridorDirection.A_TO_B,
        request.directedContext().orElseThrow().singleConflictDirections().get(section.key()));
  }

  @Test
  void directedTraversalContextStableAcrossEventPeriodicProgress() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
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
    EdgeId edgeAB = EdgeId.undirected(nodeA, nodeB);
    RailEdge ab = new RailEdge(edgeAB, nodeA, nodeB, 10, 8.0, true, Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(Map.of(nodeA, a, nodeB, b), Map.of(edgeAB, ab), Set.of());
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 1, 0, 0, 0);

    OccupancyRequest request =
        builder
            .buildContextFromNodes(
                "train",
                Optional.of(RouteId.of("r")),
                List.of(nodeA, nodeB),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow()
            .request();

    OccupancyRequest event = request.withDirectedSource("EVENT");
    OccupancyRequest periodic = request.withDirectedSource("PERIODIC_TICK");
    OccupancyRequest progress = request.withDirectedSource("PROGRESS_TRIGGER");
    assertEquals(
        event.directedContext().orElseThrow().expandedPathNodes(),
        periodic.directedContext().orElseThrow().expandedPathNodes());
    assertEquals(
        event.directedContext().orElseThrow().directedEdges(),
        progress.directedContext().orElseThrow().directedEdges());
    assertEquals(
        event.directedContext().orElseThrow().singleConflictDirections(),
        periodic.directedContext().orElseThrow().singleConflictDirections());
  }

  @Test
  void protectiveRetainPreservesSingleCorridorDirection() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
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
    RailNode c =
        new SignRailNode(
            nodeC,
            NodeType.WAYPOINT,
            new Vector(20.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    EdgeId edgeAB = EdgeId.undirected(nodeA, nodeB);
    EdgeId edgeBC = EdgeId.undirected(nodeB, nodeC);
    RailEdge ab = new RailEdge(edgeAB, nodeA, nodeB, 10, 8.0, true, Optional.empty());
    RailEdge bc = new RailEdge(edgeBC, nodeB, nodeC, 10, 8.0, true, Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(nodeA, a, nodeB, b, nodeC, c), Map.of(edgeAB, ab, edgeBC, bc), Set.of());
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 1, 0, 0, 0);
    String conflictKey =
        ((RailGraphConflictSupport) graph).conflictKeyForEdge(edgeBC).orElseThrow();

    OccupancyRequest forward =
        builder.buildCurrentPositionRequest(
            "train", Optional.empty(), nodeB, Optional.of(nodeC), Instant.now(), 0);
    assertTrue(forward.resourceList().contains(OccupancyResource.forNode(nodeB)));
    assertTrue(forward.resourceList().contains(OccupancyResource.forEdge(edgeBC)));
    assertTrue(forward.resourceList().contains(OccupancyResource.forConflict(conflictKey)));
    assertEquals(CorridorDirection.A_TO_B, forward.corridorDirections().get(conflictKey));
    assertEquals(0, forward.conflictEntryOrders().get(conflictKey));
    assertEquals(
        CorridorDirection.A_TO_B,
        forward.directedContext().orElseThrow().singleConflictDirections().get(conflictKey));

    OccupancyRequest reverse =
        builder.buildCurrentPositionRequest(
            "train", Optional.empty(), nodeB, Optional.of(nodeA), Instant.now(), 0);
    assertEquals(CorridorDirection.B_TO_A, reverse.corridorDirections().get(conflictKey));
  }

  @Test
  void holdRetainPreservesSingleCorridorDirection() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
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
    RailNode c =
        new SignRailNode(
            nodeC,
            NodeType.WAYPOINT,
            new Vector(20.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    EdgeId edgeAB = EdgeId.undirected(nodeA, nodeB);
    EdgeId edgeBC = EdgeId.undirected(nodeB, nodeC);
    RailEdge ab = new RailEdge(edgeAB, nodeA, nodeB, 10, 8.0, true, Optional.empty());
    RailEdge bc = new RailEdge(edgeBC, nodeB, nodeC, 10, 8.0, true, Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(nodeA, a, nodeB, b, nodeC, c), Map.of(edgeAB, ab, edgeBC, bc), Set.of());
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 1, 0, 1, 0);
    String conflictKey =
        ((RailGraphConflictSupport) graph).conflictKeyForEdge(edgeBC).orElseThrow();

    OccupancyRequest request =
        builder.buildHoldPositionRequest(
            "train",
            Optional.of(RouteId.of("r")),
            nodeB,
            Optional.of(nodeC),
            List.of(nodeA, nodeB, nodeC),
            1,
            Instant.parse("2026-01-01T00:00:00Z"),
            0,
            AuthorizationPurpose.RUNTIME_MOVE);

    assertTrue(request.resourceList().contains(OccupancyResource.forNode(nodeB)));
    assertTrue(request.resourceList().contains(OccupancyResource.forEdge(edgeBC)));
    assertTrue(request.resourceList().contains(OccupancyResource.forConflict(conflictKey)));
    assertEquals(CorridorDirection.A_TO_B, request.corridorDirections().get(conflictKey));
    assertEquals(0, request.conflictEntryOrders().get(conflictKey));
    assertEquals(
        CorridorDirection.A_TO_B,
        request.directedContext().orElseThrow().singleConflictDirections().get(conflictKey));
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
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 1, 0, 0, 0);
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
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 3, 0, 0, 1);
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
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 2, 0, 0, 1);
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

  @Test
  void rearGuardKeepsTailEdgesAndNodes() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
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
    RailNode c =
        new SignRailNode(
            nodeC,
            NodeType.WAYPOINT,
            new Vector(20.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    EdgeId edgeAB = EdgeId.undirected(nodeA, nodeB);
    EdgeId edgeBC = EdgeId.undirected(nodeB, nodeC);
    RailEdge ab = new RailEdge(edgeAB, nodeA, nodeB, 12, 8.0, true, Optional.empty());
    RailEdge bc = new RailEdge(edgeBC, nodeB, nodeC, 18, 8.0, true, Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(nodeA, a, nodeB, b, nodeC, c), Map.of(edgeAB, ab, edgeBC, bc), Set.of());
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 1, 0, 1, 0);
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("OP:LINE:ROUTE"), List.of(nodeA, nodeB, nodeC), Optional.empty());
    TrainRuntimeState state = new StubState("Train-1", new StubProgress(route.id(), 1));

    Optional<OccupancyRequest> requestOpt =
        builder.build(state, route, Instant.parse("2026-01-01T00:00:00Z"));
    assertTrue(requestOpt.isPresent());
    OccupancyRequest request = requestOpt.get();
    assertTrue(request.resourceList().contains(OccupancyResource.forNode(nodeA)));
    assertTrue(request.resourceList().contains(OccupancyResource.forEdge(edgeAB)));
    assertTrue(request.resourceList().contains(OccupancyResource.forEdge(edgeBC)));
    assertEquals(
        ResourceIntent.PROTECTIVE_RETAIN, request.intentFor(OccupancyResource.forEdge(edgeAB)));
    assertEquals(
        ResourceIntent.MOVEMENT_REQUIRED, request.intentFor(OccupancyResource.forEdge(edgeBC)));
  }

  @Test
  void rearGuardRequestKeepsOnlyTailSegment() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
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
    RailNode c =
        new SignRailNode(
            nodeC,
            NodeType.WAYPOINT,
            new Vector(20.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    EdgeId edgeAB = EdgeId.undirected(nodeA, nodeB);
    EdgeId edgeBC = EdgeId.undirected(nodeB, nodeC);
    RailEdge ab = new RailEdge(edgeAB, nodeA, nodeB, 12, 8.0, true, Optional.empty());
    RailEdge bc = new RailEdge(edgeBC, nodeB, nodeC, 18, 8.0, true, Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(nodeA, a, nodeB, b, nodeC, c), Map.of(edgeAB, ab, edgeBC, bc), Set.of());
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 2, 0, 1, 0);
    List<NodeId> nodes = List.of(nodeA, nodeB, nodeC);

    OccupancyRequest request =
        builder.buildRearGuardRequestFromNodes(
            "Train-1", Optional.of(RouteId.of("OP:LINE:ROUTE")), nodes, 2, Instant.now(), 0);

    assertTrue(request.resourceList().contains(OccupancyResource.forNode(nodeB)));
    assertTrue(request.resourceList().contains(OccupancyResource.forNode(nodeC)));
    assertTrue(request.resourceList().contains(OccupancyResource.forEdge(edgeBC)));
    assertFalse(request.resourceList().contains(OccupancyResource.forEdge(edgeAB)));
    assertEquals(ResourceIntent.HOLD_ONLY, request.intentFor(OccupancyResource.forNode(nodeC)));
    assertEquals(
        ResourceIntent.PROTECTIVE_RETAIN, request.intentFor(OccupancyResource.forEdge(edgeBC)));
  }

  @Test
  void depotLookoverAddsDirectionalConflictForBranch() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
    NodeId nodeC = NodeId.of("C");
    NodeId nodeS = NodeId.of("S");
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
    RailNode c =
        new SignRailNode(
            nodeC,
            NodeType.WAYPOINT,
            new Vector(10.0, 64.0, 10.0),
            Optional.empty(),
            Optional.empty());
    EdgeId edgeAS = EdgeId.undirected(nodeA, nodeS);
    EdgeId edgeSB = EdgeId.undirected(nodeS, nodeB);
    EdgeId edgeSC = EdgeId.undirected(nodeS, nodeC);
    RailEdge as = new RailEdge(edgeAS, nodeA, nodeS, 10, 8.0, true, Optional.empty());
    RailEdge sb = new RailEdge(edgeSB, nodeS, nodeB, 10, 8.0, true, Optional.empty());
    RailEdge sc = new RailEdge(edgeSC, nodeS, nodeC, 10, 8.0, true, Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(nodeA, a, nodeS, s, nodeB, b, nodeC, c),
            Map.of(edgeAS, as, edgeSB, sb, edgeSC, sc),
            Set.of());
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 2, 0, 0, 1);
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("OP:LINE:ROUTE"), List.of(nodeA, nodeS, nodeB), Optional.empty());
    TrainRuntimeState state = new StubState("Train-1", new StubProgress(route.id(), 0));

    Optional<OccupancyRequestContext> ctxOpt =
        builder.buildContextFromNodes(
            state.trainName(), Optional.of(route.id()), route.waypoints(), 0, Instant.now(), 0);
    assertTrue(ctxOpt.isPresent());
    OccupancyRequest request = builder.applyDepotLookover(ctxOpt.get());

    OccupancyResource branchEdge = OccupancyResource.forEdge(edgeSC);
    assertFalse(request.resourceList().contains(branchEdge));
    String branchConflict =
        ((RailGraphConflictSupport) graph).conflictKeyForEdge(edgeSC).orElseThrow();
    assertTrue(request.resourceList().contains(OccupancyResource.forConflict(branchConflict)));
    assertTrue(request.corridorDirections().containsKey(branchConflict));
    assertEquals(0, request.conflictEntryOrders().get(branchConflict));
  }

  @Test
  void depotLookoverUsesExplicitDepotAnchorWhenRouteStartIsNotDepot() {
    NodeId nodeDepot = NodeId.of("DEPOT");
    NodeId nodeThroat = NodeId.of("THROAT");
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
    NodeId nodeC = NodeId.of("C");
    RailNode depot =
        new SignRailNode(
            nodeDepot,
            NodeType.DEPOT,
            new Vector(-10.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode throat =
        new SignRailNode(
            nodeThroat,
            NodeType.SWITCHER,
            new Vector(0.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailNode a =
        new SignRailNode(
            nodeA,
            NodeType.WAYPOINT,
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
    RailNode c =
        new SignRailNode(
            nodeC,
            NodeType.WAYPOINT,
            new Vector(30.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    EdgeId edgeDepotThroat = EdgeId.undirected(nodeDepot, nodeThroat);
    EdgeId edgeThroatA = EdgeId.undirected(nodeThroat, nodeA);
    EdgeId edgeAB = EdgeId.undirected(nodeA, nodeB);
    EdgeId edgeBC = EdgeId.undirected(nodeB, nodeC);
    RailEdge depotThroat =
        new RailEdge(edgeDepotThroat, nodeDepot, nodeThroat, 10, 8.0, true, Optional.empty());
    RailEdge throatA =
        new RailEdge(edgeThroatA, nodeThroat, nodeA, 10, 8.0, true, Optional.empty());
    RailEdge ab = new RailEdge(edgeAB, nodeA, nodeB, 10, 8.0, true, Optional.empty());
    RailEdge bc = new RailEdge(edgeBC, nodeB, nodeC, 10, 8.0, true, Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(nodeDepot, depot, nodeThroat, throat, nodeA, a, nodeB, b, nodeC, c),
            Map.of(edgeDepotThroat, depotThroat, edgeThroatA, throatA, edgeAB, ab, edgeBC, bc),
            Set.of());
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 2, 0, 0, 1);
    RouteDefinition route =
        new RouteDefinition(RouteId.of("OP:LINE:ROUTE"), List.of(nodeA, nodeB), Optional.empty());
    TrainRuntimeState state = new StubState("Train-1", new StubProgress(route.id(), 0));

    Optional<OccupancyRequestContext> ctxOpt =
        builder.buildContextFromNodes(
            state.trainName(), Optional.of(route.id()), route.waypoints(), 0, Instant.now(), 0);
    assertTrue(ctxOpt.isPresent());
    OccupancyRequest fallbackRequest = builder.applyDepotLookover(ctxOpt.get());
    OccupancyRequest anchoredRequest =
        builder.applyDepotLookover(ctxOpt.get(), Optional.of(nodeDepot));

    OccupancyResource depotEdgeResource = OccupancyResource.forEdge(edgeDepotThroat);
    OccupancyResource deepEdgeResource = OccupancyResource.forEdge(edgeBC);
    String depotConflict =
        ((RailGraphConflictSupport) graph).conflictKeyForEdge(edgeDepotThroat).orElseThrow();
    assertFalse(fallbackRequest.resourceList().contains(depotEdgeResource));
    assertTrue(anchoredRequest.resourceList().contains(depotEdgeResource));
    assertTrue(
        anchoredRequest.resourceList().contains(OccupancyResource.forConflict(depotConflict)));
    assertEquals(CorridorDirection.A_TO_B, anchoredRequest.corridorDirections().get(depotConflict));
    assertEquals(0, anchoredRequest.conflictEntryOrders().get(depotConflict));
    assertFalse(fallbackRequest.resourceList().contains(deepEdgeResource));
    assertTrue(anchoredRequest.resourceList().contains(deepEdgeResource));
    assertEquals(
        ctxOpt.get().request().directedContext().orElseThrow().expandedPathNodes(),
        anchoredRequest.directedContext().orElseThrow().expandedPathNodes());
    assertEquals(
        ctxOpt.get().request().directedContext().orElseThrow().directedEdges(),
        anchoredRequest.directedContext().orElseThrow().directedEdges());
  }

  @Test
  void depotLookoverFallsBackToAllDirectionConflictWhenDirectionUnavailable() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
    NodeId nodeC = NodeId.of("C");
    NodeId nodeS = NodeId.of("S");
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
    RailNode c =
        new SignRailNode(
            nodeC,
            NodeType.WAYPOINT,
            new Vector(10.0, 64.0, 10.0),
            Optional.empty(),
            Optional.empty());
    EdgeId edgeAS = EdgeId.undirected(nodeA, nodeS);
    EdgeId edgeSB = EdgeId.undirected(nodeS, nodeB);
    EdgeId edgeSC = EdgeId.undirected(nodeS, nodeC);
    RailEdge as = new RailEdge(edgeAS, nodeA, nodeS, 10, 8.0, true, Optional.empty());
    RailEdge sb = new RailEdge(edgeSB, nodeS, nodeB, 10, 8.0, true, Optional.empty());
    RailEdge sc = new RailEdge(edgeSC, nodeS, nodeC, 10, 8.0, true, Optional.empty());
    SimpleRailGraph delegate =
        new SimpleRailGraph(
            Map.of(nodeA, a, nodeS, s, nodeB, b, nodeC, c),
            Map.of(edgeAS, as, edgeSB, sb, edgeSC, sc),
            Set.of());
    String branchConflict =
        ((RailGraphConflictSupport) delegate).conflictKeyForEdge(edgeSC).orElseThrow();
    RailGraph graph =
        new CorridorInfoOverrideGraph(
            delegate,
            Map.of(
                edgeSC,
                new RailGraphCorridorInfo(branchConflict, nodeA, nodeB, List.of(nodeS), false)));
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 2, 0, 0, 1);
    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("OP:LINE:ROUTE"), List.of(nodeA, nodeS, nodeB), Optional.empty());
    TrainRuntimeState state = new StubState("Train-1", new StubProgress(route.id(), 0));

    Optional<OccupancyRequestContext> ctxOpt =
        builder.buildContextFromNodes(
            state.trainName(), Optional.of(route.id()), route.waypoints(), 0, Instant.now(), 0);
    assertTrue(ctxOpt.isPresent());
    OccupancyRequest request = builder.applyDepotLookover(ctxOpt.get());

    OccupancyResource branchEdge = OccupancyResource.forEdge(edgeSC);
    assertFalse(request.resourceList().contains(branchEdge));
    assertTrue(request.resourceList().contains(OccupancyResource.forConflict(branchConflict)));
    assertFalse(request.corridorDirections().containsKey(branchConflict));
    assertEquals(0, request.conflictEntryOrders().get(branchConflict));
  }

  @Test
  void semanticDirectionIgnoresLexicalCorridorEndpointOrder() {
    NodeId ppk = NodeId.of("SURC:S:PPK:1");
    NodeId nearSwitcher = NodeId.of("SWITCHER:Towny:-9:65:630");
    NodeId farSwitcher = NodeId.of("SWITCHER:Towny:-10:65:650");
    NodeId interval = NodeId.of("SURC:PPK:RVS:1:001");
    NodeId rvs = NodeId.of("SURC:S:RVS:1");
    EdgeId edgePpkNear = EdgeId.undirected(ppk, nearSwitcher);
    EdgeId edgeNearFar = EdgeId.undirected(nearSwitcher, farSwitcher);
    EdgeId edgeFarInterval = EdgeId.undirected(farSwitcher, interval);
    EdgeId edgeIntervalRvs = EdgeId.undirected(interval, rvs);
    SimpleRailGraph delegate =
        new SimpleRailGraph(
            Map.of(
                ppk, station(ppk, "PPK", 1, 0.0),
                nearSwitcher, switcher(nearSwitcher, 10.0),
                farSwitcher, switcher(farSwitcher, 20.0),
                interval, interval(interval, "PPK", "RVS", 1, "001", 30.0),
                rvs, station(rvs, "RVS", 1, 40.0)),
            Map.of(
                edgePpkNear, edge(ppk, nearSwitcher, edgePpkNear),
                edgeNearFar, edge(nearSwitcher, farSwitcher, edgeNearFar),
                edgeFarInterval, edge(farSwitcher, interval, edgeFarInterval),
                edgeIntervalRvs, edge(interval, rvs, edgeIntervalRvs)),
            Set.of());
    String conflict = delegate.conflictKeyForEdge(edgeNearFar).orElseThrow();
    RailGraph graph =
        new CorridorInfoOverrideGraph(
            delegate,
            Map.of(
                edgeNearFar,
                new RailGraphCorridorInfo(
                    conflict,
                    farSwitcher,
                    nearSwitcher,
                    List.of(farSwitcher, nearSwitcher),
                    false)));
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 4, 0, 0, 0);

    OccupancyRequest forward =
        builder
            .buildContextFromNodes(
                "forward",
                Optional.of(RouteId.of("SURC:MT:PPK_RVS")),
                List.of(ppk, rvs),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow()
            .request();
    OccupancyRequest reverse =
        builder
            .buildContextFromNodes(
                "reverse",
                Optional.of(RouteId.of("SURC:MT:RVS_PPK")),
                List.of(rvs, ppk),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow()
            .request();

    assertEquals(CorridorDirection.A_TO_B, forward.corridorDirections().get(conflict));
    assertEquals(CorridorDirection.B_TO_A, reverse.corridorDirections().get(conflict));
  }

  @Test
  void semanticDirectionAllowsSameStationPairAcrossTrackChange() {
    NodeId trackTwo = NodeId.of("SURC:ZKW:HHU:2:002");
    NodeId switcher = NodeId.of("SWITCHER:Towny:221:74:1018");
    NodeId trackFour = NodeId.of("SURC:ZKW:HHU:4:003");
    EdgeId edgeTwoSwitcher = EdgeId.undirected(trackTwo, switcher);
    EdgeId edgeSwitcherFour = EdgeId.undirected(switcher, trackFour);
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(
                trackTwo, interval(trackTwo, "ZKW", "HHU", 2, "002", 0.0),
                switcher, switcher(switcher, 10.0),
                trackFour, interval(trackFour, "ZKW", "HHU", 4, "003", 20.0)),
            Map.of(
                edgeTwoSwitcher, edge(trackTwo, switcher, edgeTwoSwitcher),
                edgeSwitcherFour, edge(switcher, trackFour, edgeSwitcherFour)),
            Set.of());
    String conflict = graph.conflictKeyForEdge(edgeSwitcherFour).orElseThrow();
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 2, 0, 0, 0);

    OccupancyRequest request =
        builder
            .buildContextFromNodes(
                "train",
                Optional.of(RouteId.of("SURC:MT:ZKW_HHU")),
                List.of(trackTwo, trackFour),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow()
            .request();

    assertEquals(CorridorDirection.A_TO_B, request.corridorDirections().get(conflict));
  }

  @Test
  void semanticDirectionAllowsLinearStationChainAcrossSectionChange() {
    NodeId spbJbs = NodeId.of("SURC:SPB:JBS:1:005");
    NodeId switcher = NodeId.of("SWITCHER:Towny:-516:77:1387");
    NodeId jbsCsb = NodeId.of("SURC:JBS:CSB:1:001");
    EdgeId edgeSpbJbsSwitcher = EdgeId.undirected(spbJbs, switcher);
    EdgeId edgeSwitcherJbsCsb = EdgeId.undirected(switcher, jbsCsb);
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(
                spbJbs, interval(spbJbs, "SPB", "JBS", 1, "005", 0.0),
                switcher, switcher(switcher, 10.0),
                jbsCsb, interval(jbsCsb, "JBS", "CSB", 1, "001", 20.0)),
            Map.of(
                edgeSpbJbsSwitcher, edge(spbJbs, switcher, edgeSpbJbsSwitcher),
                edgeSwitcherJbsCsb, edge(switcher, jbsCsb, edgeSwitcherJbsCsb)),
            Set.of());
    String conflict = graph.conflictKeyForEdge(edgeSwitcherJbsCsb).orElseThrow();
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 2, 0, 0, 0);

    OccupancyRequest forward =
        builder
            .buildContextFromNodes(
                "forward",
                Optional.of(RouteId.of("SURC:MT:SPB_CSB")),
                List.of(spbJbs, jbsCsb),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow()
            .request();
    OccupancyRequest reverse =
        builder
            .buildContextFromNodes(
                "reverse",
                Optional.of(RouteId.of("SURC:MT:CSB_SPB")),
                List.of(jbsCsb, spbJbs),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow()
            .request();

    assertEquals(CorridorDirection.A_TO_B, forward.corridorDirections().get(conflict));
    assertEquals(CorridorDirection.B_TO_A, reverse.corridorDirections().get(conflict));
  }

  @Test
  void semanticDirectionScopesPathAxisBeforeRemoteHubBranch() {
    NodeId sw128 = NodeId.of("SWITCHER:Towny:128:74:1014");
    NodeId zkwHhu2 = NodeId.of("SURC:ZKW:HHU:1:002");
    NodeId zkwHhu1 = NodeId.of("SURC:ZKW:HHU:1:001");
    NodeId zkw = NodeId.of("SURC:S:ZKW:1");
    NodeId sccZkw3 = NodeId.of("SURC:SCC:ZKW:1:003");
    NodeId sccZkw2 = NodeId.of("SURC:SCC:ZKW:1:002");
    NodeId sccZkw1 = NodeId.of("SURC:SCC:ZKW:1:001");
    NodeId scc = NodeId.of("SURC:S:SCC:1");
    NodeId wsdScc2 = NodeId.of("SURC:WSD:SCC:1:002");
    NodeId wsdScc1 = NodeId.of("SURC:WSD:SCC:1:001");
    NodeId sw262 = NodeId.of("SWITCHER:Towny:-262:74:1117");
    NodeId wsd = NodeId.of("SURC:S:WSD:2");
    NodeId spbWsd2 = NodeId.of("SURC:SPB:WSD:2:002");
    NodeId spbWsd1 = NodeId.of("SURC:SPB:WSD:2:001");
    NodeId spb = NodeId.of("SURC:S:SPB:2");
    NodeId hub = NodeId.of("SWITCHER:Towny:-566:77:1179");
    NodeId spbJbs1 = NodeId.of("SURC:SPB:JBS:1:001");
    NodeId jbs = NodeId.of("SURC:S:JBS:1");
    EdgeId edgeStart = EdgeId.undirected(sw128, zkwHhu2);
    EdgeId edgeZkwHhu = EdgeId.undirected(zkwHhu2, zkwHhu1);
    EdgeId edgeZkwStation = EdgeId.undirected(zkwHhu1, zkw);
    EdgeId edgeSccZkw3 = EdgeId.undirected(zkw, sccZkw3);
    EdgeId edgeSccZkw2 = EdgeId.undirected(sccZkw3, sccZkw2);
    EdgeId edgeSccZkw1 = EdgeId.undirected(sccZkw2, sccZkw1);
    EdgeId edgeSccStation = EdgeId.undirected(sccZkw1, scc);
    EdgeId edgeWsdScc2 = EdgeId.undirected(scc, wsdScc2);
    EdgeId edgeWsdScc1 = EdgeId.undirected(wsdScc2, wsdScc1);
    EdgeId edgeSw262 = EdgeId.undirected(wsdScc1, sw262);
    EdgeId edgeWsd = EdgeId.undirected(sw262, wsd);
    EdgeId edgeSpbWsd2 = EdgeId.undirected(wsd, spbWsd2);
    EdgeId edgeSpbWsd1 = EdgeId.undirected(spbWsd2, spbWsd1);
    EdgeId edgeSpb = EdgeId.undirected(spbWsd1, spb);
    EdgeId edgeHub = EdgeId.undirected(spb, hub);
    EdgeId edgeSpbJbs = EdgeId.undirected(hub, spbJbs1);
    EdgeId edgeJbs = EdgeId.undirected(spbJbs1, jbs);
    SimpleRailGraph delegate =
        new SimpleRailGraph(
            Map.ofEntries(
                Map.entry(sw128, switcher(sw128, 0.0)),
                Map.entry(zkwHhu2, interval(zkwHhu2, "ZKW", "HHU", 1, "002", 10.0)),
                Map.entry(zkwHhu1, interval(zkwHhu1, "ZKW", "HHU", 1, "001", 20.0)),
                Map.entry(zkw, station(zkw, "ZKW", 1, 30.0)),
                Map.entry(sccZkw3, interval(sccZkw3, "SCC", "ZKW", 1, "003", 40.0)),
                Map.entry(sccZkw2, interval(sccZkw2, "SCC", "ZKW", 1, "002", 50.0)),
                Map.entry(sccZkw1, interval(sccZkw1, "SCC", "ZKW", 1, "001", 60.0)),
                Map.entry(scc, station(scc, "SCC", 1, 70.0)),
                Map.entry(wsdScc2, interval(wsdScc2, "WSD", "SCC", 1, "002", 80.0)),
                Map.entry(wsdScc1, interval(wsdScc1, "WSD", "SCC", 1, "001", 90.0)),
                Map.entry(sw262, switcher(sw262, 100.0)),
                Map.entry(wsd, station(wsd, "WSD", 2, 110.0)),
                Map.entry(spbWsd2, interval(spbWsd2, "SPB", "WSD", 2, "002", 120.0)),
                Map.entry(spbWsd1, interval(spbWsd1, "SPB", "WSD", 2, "001", 130.0)),
                Map.entry(spb, station(spb, "SPB", 2, 140.0)),
                Map.entry(hub, switcher(hub, 150.0)),
                Map.entry(spbJbs1, interval(spbJbs1, "SPB", "JBS", 1, "001", 160.0)),
                Map.entry(jbs, station(jbs, "JBS", 1, 170.0))),
            Map.ofEntries(
                Map.entry(edgeStart, edge(sw128, zkwHhu2, edgeStart)),
                Map.entry(edgeZkwHhu, edge(zkwHhu2, zkwHhu1, edgeZkwHhu)),
                Map.entry(edgeZkwStation, edge(zkwHhu1, zkw, edgeZkwStation)),
                Map.entry(edgeSccZkw3, edge(zkw, sccZkw3, edgeSccZkw3)),
                Map.entry(edgeSccZkw2, edge(sccZkw3, sccZkw2, edgeSccZkw2)),
                Map.entry(edgeSccZkw1, edge(sccZkw2, sccZkw1, edgeSccZkw1)),
                Map.entry(edgeSccStation, edge(sccZkw1, scc, edgeSccStation)),
                Map.entry(edgeWsdScc2, edge(scc, wsdScc2, edgeWsdScc2)),
                Map.entry(edgeWsdScc1, edge(wsdScc2, wsdScc1, edgeWsdScc1)),
                Map.entry(edgeSw262, edge(wsdScc1, sw262, edgeSw262)),
                Map.entry(edgeWsd, edge(sw262, wsd, edgeWsd)),
                Map.entry(edgeSpbWsd2, edge(wsd, spbWsd2, edgeSpbWsd2)),
                Map.entry(edgeSpbWsd1, edge(spbWsd2, spbWsd1, edgeSpbWsd1)),
                Map.entry(edgeSpb, edge(spbWsd1, spb, edgeSpb)),
                Map.entry(edgeHub, edge(spb, hub, edgeHub)),
                Map.entry(edgeSpbJbs, edge(hub, spbJbs1, edgeSpbJbs)),
                Map.entry(edgeJbs, edge(spbJbs1, jbs, edgeJbs))),
            Set.of());
    String conflict = delegate.conflictKeyForEdge(edgeStart).orElseThrow();
    RailGraph graph =
        new CorridorInfoOverrideGraph(
            delegate,
            Map.of(
                edgeStart,
                new RailGraphCorridorInfo(
                    conflict,
                    sw262,
                    sw128,
                    List.of(
                        sw262, wsdScc1, wsdScc2, scc, sccZkw1, sccZkw2, sccZkw3, zkw, zkwHhu1,
                        zkwHhu2, sw128),
                    false)));
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 20, 0, 0, 0);

    OccupancyRequest request =
        builder
            .buildContextFromNodes(
                "SURC-MT-LP-9160",
                Optional.of(RouteId.of("SURC:MT:MT-2F_FULL")),
                List.of(sw128, jbs),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow()
            .request();

    assertTrue(request.resourceList().contains(OccupancyResource.forConflict(conflict)));
    assertEquals(CorridorDirection.B_TO_A, request.corridorDirections().get(conflict));
  }

  @Test
  void semanticDirectionKeepsHubBranchGapUnknown() {
    NodeId wsd = NodeId.of("SURC:S:WSD:2");
    NodeId spbWsd = NodeId.of("SURC:SPB:WSD:2:001");
    NodeId spb = NodeId.of("SURC:S:SPB:2");
    NodeId hub = NodeId.of("SWITCHER:Towny:-566:77:1179");
    NodeId spbJbs = NodeId.of("SURC:SPB:JBS:1:001");
    NodeId jbs = NodeId.of("SURC:S:JBS:1");
    EdgeId edgeWsdInterval = EdgeId.undirected(wsd, spbWsd);
    EdgeId edgeSpbWsd = EdgeId.undirected(spbWsd, spb);
    EdgeId edgeHub = EdgeId.undirected(spb, hub);
    EdgeId edgeSpbJbs = EdgeId.undirected(hub, spbJbs);
    EdgeId edgeJbs = EdgeId.undirected(spbJbs, jbs);
    SimpleRailGraph delegate =
        new SimpleRailGraph(
            Map.of(
                wsd, station(wsd, "WSD", 2, 0.0),
                spbWsd, interval(spbWsd, "SPB", "WSD", 2, "001", 10.0),
                spb, station(spb, "SPB", 2, 20.0),
                hub, switcher(hub, 30.0),
                spbJbs, interval(spbJbs, "SPB", "JBS", 1, "001", 40.0),
                jbs, station(jbs, "JBS", 1, 50.0)),
            Map.of(
                edgeWsdInterval, edge(wsd, spbWsd, edgeWsdInterval),
                edgeSpbWsd, edge(spbWsd, spb, edgeSpbWsd),
                edgeHub, edge(spb, hub, edgeHub),
                edgeSpbJbs, edge(hub, spbJbs, edgeSpbJbs),
                edgeJbs, edge(spbJbs, jbs, edgeJbs)),
            Set.of());
    String conflict = "single:hub:SPB~SWITCHER";
    RailGraph graph =
        new CorridorInfoOverrideGraph(
            delegate,
            Map.of(
                edgeHub, new RailGraphCorridorInfo(conflict, spb, hub, List.of(spb, hub), false)));
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 6, 0, 0, 0);

    OccupancyRequest request =
        builder
            .buildContextFromNodes(
                "SURC-MT-LP-9160",
                Optional.of(RouteId.of("SURC:MT:WSD_JBS")),
                List.of(wsd, jbs),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow()
            .request();

    assertTrue(request.resourceList().contains(OccupancyResource.forConflict(conflict)));
    assertFalse(request.corridorDirections().containsKey(conflict));
  }

  @Test
  void semanticDirectionKeepsSharedHubRegionUnknownAfterEarlierLinearEdge() {
    NodeId wsd = NodeId.of("SURC:S:WSD:2");
    NodeId spbWsd = NodeId.of("SURC:SPB:WSD:2:001");
    NodeId spb = NodeId.of("SURC:S:SPB:2");
    NodeId hub = NodeId.of("SWITCHER:Towny:-566:77:1179");
    NodeId spbJbs = NodeId.of("SURC:SPB:JBS:1:001");
    NodeId jbs = NodeId.of("SURC:S:JBS:1");
    EdgeId edgeWsdInterval = EdgeId.undirected(wsd, spbWsd);
    EdgeId edgeSpbWsd = EdgeId.undirected(spbWsd, spb);
    EdgeId edgeHub = EdgeId.undirected(spb, hub);
    EdgeId edgeSpbJbs = EdgeId.undirected(hub, spbJbs);
    EdgeId edgeJbs = EdgeId.undirected(spbJbs, jbs);
    SimpleRailGraph delegate =
        new SimpleRailGraph(
            Map.of(
                wsd, station(wsd, "WSD", 2, 0.0),
                spbWsd, interval(spbWsd, "SPB", "WSD", 2, "001", 10.0),
                spb, station(spb, "SPB", 2, 20.0),
                hub, switcher(hub, 30.0),
                spbJbs, interval(spbJbs, "SPB", "JBS", 1, "001", 40.0),
                jbs, station(jbs, "JBS", 1, 50.0)),
            Map.of(
                edgeWsdInterval, edge(wsd, spbWsd, edgeWsdInterval),
                edgeSpbWsd, edge(spbWsd, spb, edgeSpbWsd),
                edgeHub, edge(spb, hub, edgeHub),
                edgeSpbJbs, edge(hub, spbJbs, edgeSpbJbs),
                edgeJbs, edge(spbJbs, jbs, edgeJbs)),
            Set.of());
    String conflict = "single:hub:WSD~JBS";
    RailGraph graph =
        new CorridorInfoOverrideGraph(
            delegate,
            Map.of(
                edgeSpbWsd,
                new RailGraphCorridorInfo(
                    conflict, wsd, jbs, List.of(wsd, spbWsd, spb, hub, spbJbs, jbs), false),
                edgeHub,
                new RailGraphCorridorInfo(
                    conflict, wsd, jbs, List.of(wsd, spbWsd, spb, hub, spbJbs, jbs), false),
                edgeSpbJbs,
                new RailGraphCorridorInfo(
                    conflict, wsd, jbs, List.of(wsd, spbWsd, spb, hub, spbJbs, jbs), false)));
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 2, 0, 0, 0);

    OccupancyRequest request =
        builder
            .buildContextFromNodes(
                "SURC-MT-LP-9160",
                Optional.of(RouteId.of("SURC:MT:WSD_JBS")),
                List.of(wsd, jbs),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow()
            .request();

    assertTrue(request.resourceList().contains(OccupancyResource.forConflict(conflict)));
    assertFalse(request.corridorDirections().containsKey(conflict));
  }

  @Test
  void semanticDirectionScopesPathAxisAfterRemoteHubBranch() {
    NodeId wsd = NodeId.of("SURC:S:WSD:2");
    NodeId spbWsd = NodeId.of("SURC:SPB:WSD:2:001");
    NodeId spb = NodeId.of("SURC:S:SPB:2");
    NodeId hub = NodeId.of("SWITCHER:Towny:-566:77:1179");
    NodeId spbJbs = NodeId.of("SURC:SPB:JBS:1:001");
    NodeId jbs = NodeId.of("SURC:S:JBS:1");
    NodeId jbsCsb = NodeId.of("SURC:JBS:CSB:1:001");
    NodeId csb = NodeId.of("SURC:S:CSB:1");
    EdgeId edgeWsdInterval = EdgeId.undirected(wsd, spbWsd);
    EdgeId edgeSpbWsd = EdgeId.undirected(spbWsd, spb);
    EdgeId edgeHub = EdgeId.undirected(spb, hub);
    EdgeId edgeSpbJbs = EdgeId.undirected(hub, spbJbs);
    EdgeId edgeJbs = EdgeId.undirected(spbJbs, jbs);
    EdgeId edgeJbsCsb = EdgeId.undirected(jbs, jbsCsb);
    EdgeId edgeCsb = EdgeId.undirected(jbsCsb, csb);
    SimpleRailGraph delegate =
        new SimpleRailGraph(
            Map.of(
                wsd, station(wsd, "WSD", 2, 0.0),
                spbWsd, interval(spbWsd, "SPB", "WSD", 2, "001", 10.0),
                spb, station(spb, "SPB", 2, 20.0),
                hub, switcher(hub, 30.0),
                spbJbs, interval(spbJbs, "SPB", "JBS", 1, "001", 40.0),
                jbs, station(jbs, "JBS", 1, 50.0),
                jbsCsb, interval(jbsCsb, "JBS", "CSB", 1, "001", 60.0),
                csb, station(csb, "CSB", 1, 70.0)),
            Map.of(
                edgeWsdInterval, edge(wsd, spbWsd, edgeWsdInterval),
                edgeSpbWsd, edge(spbWsd, spb, edgeSpbWsd),
                edgeHub, edge(spb, hub, edgeHub),
                edgeSpbJbs, edge(hub, spbJbs, edgeSpbJbs),
                edgeJbs, edge(spbJbs, jbs, edgeJbs),
                edgeJbsCsb, edge(jbs, jbsCsb, edgeJbsCsb),
                edgeCsb, edge(jbsCsb, csb, edgeCsb)),
            Set.of());
    String conflict = "single:downstream:JBS~CSB";
    RailGraph graph =
        new CorridorInfoOverrideGraph(
            delegate,
            Map.of(
                edgeJbsCsb,
                new RailGraphCorridorInfo(conflict, jbs, csb, List.of(jbs, jbsCsb, csb), false)));
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 8, 0, 0, 0);

    OccupancyRequest request =
        builder
            .buildContextFromNodes(
                "SURC-MT-LP-9160",
                Optional.of(RouteId.of("SURC:MT:WSD_CSB")),
                List.of(wsd, csb),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow()
            .request();

    assertTrue(request.resourceList().contains(OccupancyResource.forConflict(conflict)));
    assertEquals(CorridorDirection.A_TO_B, request.corridorDirections().get(conflict));
  }

  @Test
  void semanticDirectionUsesPathLocalAxisWhenResourceNeighborhoodBranches() {
    NodeId spbJbsBefore = NodeId.of("SURC:SPB:JBS:1:002");
    NodeId switcher = NodeId.of("SWITCHER:Towny:-557:77:1193");
    NodeId spbJbsAfter = NodeId.of("SURC:SPB:JBS:1:003");
    NodeId spbWsd = NodeId.of("SURC:SPB:WSD:2:001");
    EdgeId edgeBeforeSwitcher = EdgeId.undirected(spbJbsBefore, switcher);
    EdgeId edgeSwitcherAfter = EdgeId.undirected(switcher, spbJbsAfter);
    EdgeId edgeSwitcherWsd = EdgeId.undirected(switcher, spbWsd);
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(
                spbJbsBefore, interval(spbJbsBefore, "SPB", "JBS", 1, "002", 0.0),
                switcher, switcher(switcher, 10.0),
                spbJbsAfter, interval(spbJbsAfter, "SPB", "JBS", 1, "003", 20.0),
                spbWsd, interval(spbWsd, "SPB", "WSD", 2, "001", 10.0)),
            Map.of(
                edgeBeforeSwitcher, edge(spbJbsBefore, switcher, edgeBeforeSwitcher),
                edgeSwitcherAfter, edge(switcher, spbJbsAfter, edgeSwitcherAfter),
                edgeSwitcherWsd, edge(switcher, spbWsd, edgeSwitcherWsd)),
            Set.of());
    String conflict = graph.conflictKeyForEdge(edgeSwitcherAfter).orElseThrow();
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 2, 0, 0, 0);

    OccupancyRequest request =
        builder
            .buildContextFromNodes(
                "train",
                Optional.of(RouteId.of("SURC:MT:SPB_JBS")),
                List.of(spbJbsBefore, spbJbsAfter),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow()
            .request();

    assertTrue(request.resourceList().contains(OccupancyResource.forConflict(conflict)));
    assertEquals(CorridorDirection.A_TO_B, request.corridorDirections().get(conflict));
  }

  @Test
  void semanticDirectionInfersDepotEntryFromSinglePathStationAnchor() {
    NodeId hhuLwn = NodeId.of("SURC:HHU:LWN:2:001");
    NodeId switcher = NodeId.of("SWITCHER:Towny:558:74:1014");
    NodeId throat = NodeId.of("SURC:D:LWN:1:005");
    NodeId depot = NodeId.of("SURC:D:LWN:1");
    EdgeId edgeIntervalSwitcher = EdgeId.undirected(hhuLwn, switcher);
    EdgeId edgeSwitcherThroat = EdgeId.undirected(switcher, throat);
    EdgeId edgeThroatDepot = EdgeId.undirected(throat, depot);
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(
                hhuLwn, interval(hhuLwn, "HHU", "LWN", 2, "001", 0.0),
                switcher, switcher(switcher, 10.0),
                throat, depotThroat(throat, "LWN", 1, "005", 20.0),
                depot, depot(depot, "LWN", 1, 30.0)),
            Map.of(
                edgeIntervalSwitcher, edge(hhuLwn, switcher, edgeIntervalSwitcher),
                edgeSwitcherThroat, edge(switcher, throat, edgeSwitcherThroat),
                edgeThroatDepot, edge(throat, depot, edgeThroatDepot)),
            Set.of());
    String conflict = graph.conflictKeyForEdge(edgeSwitcherThroat).orElseThrow();
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 2, 0, 0, 0);

    OccupancyRequest request =
        builder
            .buildContextFromNodes(
                "train",
                Optional.of(RouteId.of("SURC:WS:LWN_DEPOT")),
                List.of(switcher, depot),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow()
            .request();

    assertEquals(CorridorDirection.A_TO_B, request.corridorDirections().get(conflict));
  }

  @Test
  void semanticDirectionResolvesHhuDepotExitFromForwardPathAnchor() {
    NodeId depot = NodeId.of("SURC:D:HHU:3");
    NodeId switcher = NodeId.of("SWITCHER:Towny:502:74:996");
    NodeId station = NodeId.of("SURC:S:HHU:1");
    NodeId zkwHhu = NodeId.of("SURC:ZKW:HHU:1:006");
    EdgeId edgeDepotSwitcher = EdgeId.undirected(depot, switcher);
    EdgeId edgeSwitcherStation = EdgeId.undirected(switcher, station);
    EdgeId edgeStationInterval = EdgeId.undirected(station, zkwHhu);
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(
                depot, depot(depot, "HHU", 3, 0.0),
                switcher, switcher(switcher, 10.0),
                station, station(station, "HHU", 1, 20.0),
                zkwHhu, interval(zkwHhu, "ZKW", "HHU", 1, "006", 30.0)),
            Map.of(
                edgeDepotSwitcher, edge(depot, switcher, edgeDepotSwitcher),
                edgeSwitcherStation, edge(switcher, station, edgeSwitcherStation),
                edgeStationInterval, edge(station, zkwHhu, edgeStationInterval)),
            Set.of());
    String conflict = graph.conflictKeyForEdge(edgeDepotSwitcher).orElseThrow();
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 3, 0, 0, 0);

    OccupancyRequest request =
        builder
            .buildContextFromNodes(
                "train",
                Optional.of(RouteId.of("SURC:DS:DS-1F_FULL")),
                List.of(depot, zkwHhu),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow()
            .request();

    assertEquals(CorridorDirection.B_TO_A, request.corridorDirections().get(conflict));
  }

  @Test
  void semanticDirectionResolvesOflDepotExitThroughDepotThroat() {
    NodeId depot = NodeId.of("SURC:D:OFL:1");
    NodeId throat = NodeId.of("SURC:D:OFL:1:001");
    NodeId switcher = NodeId.of("SWITCHER:Towny:-515:77:2272");
    NodeId station = NodeId.of("SURC:S:OFL:1");
    NodeId oflMlu = NodeId.of("SURC:OFL:MLU:1:001");
    EdgeId edgeDepotThroat = EdgeId.undirected(depot, throat);
    EdgeId edgeThroatSwitcher = EdgeId.undirected(throat, switcher);
    EdgeId edgeSwitcherStation = EdgeId.undirected(switcher, station);
    EdgeId edgeStationInterval = EdgeId.undirected(station, oflMlu);
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(
                depot, depot(depot, "OFL", 1, 0.0),
                throat, depotThroat(throat, "OFL", 1, "001", 10.0),
                switcher, switcher(switcher, 20.0),
                station, station(station, "OFL", 1, 30.0),
                oflMlu, interval(oflMlu, "OFL", "MLU", 1, "001", 40.0)),
            Map.of(
                edgeDepotThroat, edge(depot, throat, edgeDepotThroat),
                edgeThroatSwitcher, edge(throat, switcher, edgeThroatSwitcher),
                edgeSwitcherStation, edge(switcher, station, edgeSwitcherStation),
                edgeStationInterval, edge(station, oflMlu, edgeStationInterval)),
            Set.of());
    String conflict = graph.conflictKeyForEdge(edgeDepotThroat).orElseThrow();
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 4, 0, 0, 0);

    OccupancyRequest request =
        builder
            .buildContextFromNodes(
                "train",
                Optional.of(RouteId.of("SURC:MT:MT-1N_SHORT")),
                List.of(depot, oflMlu),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow()
            .request();

    assertEquals(CorridorDirection.A_TO_B, request.corridorDirections().get(conflict));
  }

  @Test
  void requestDirectionsUseFullPlanWhenAdmissionWindowIsTooShortForSemanticAnchors() {
    NodeId jbs = NodeId.of("SURC:S:JBS:1");
    NodeId throat = NodeId.of("SURC:S:JBS:1:001");
    NodeId switcherA = NodeId.of("SWITCHER:Towny:-520:77:1390");
    NodeId switcherB = NodeId.of("SWITCHER:Towny:-520:77:1410");
    NodeId switcherC = NodeId.of("SWITCHER:Towny:-520:77:1430");
    NodeId jbsCsb = NodeId.of("SURC:JBS:CSB:2:001");
    NodeId csb = NodeId.of("SURC:S:CSB:2");
    EdgeId edgeStationThroat = EdgeId.undirected(jbs, throat);
    EdgeId edgeThroatA = EdgeId.undirected(throat, switcherA);
    EdgeId edgeAB = EdgeId.undirected(switcherA, switcherB);
    EdgeId edgeBC = EdgeId.undirected(switcherB, switcherC);
    EdgeId edgeCInterval = EdgeId.undirected(switcherC, jbsCsb);
    EdgeId edgeIntervalStation = EdgeId.undirected(jbsCsb, csb);
    SimpleRailGraph delegate =
        new SimpleRailGraph(
            Map.of(
                jbs, station(jbs, "JBS", 1, 0.0),
                throat, stationThroat(throat, "JBS", 1, "001", 10.0),
                switcherA, switcher(switcherA, 20.0),
                switcherB, switcher(switcherB, 30.0),
                switcherC, switcher(switcherC, 40.0),
                jbsCsb, interval(jbsCsb, "JBS", "CSB", 2, "001", 50.0),
                csb, station(csb, "CSB", 2, 60.0)),
            Map.of(
                edgeStationThroat, edge(jbs, throat, edgeStationThroat),
                edgeThroatA, edge(throat, switcherA, edgeThroatA),
                edgeAB, edge(switcherA, switcherB, edgeAB),
                edgeBC, edge(switcherB, switcherC, edgeBC),
                edgeCInterval, edge(switcherC, jbsCsb, edgeCInterval),
                edgeIntervalStation, edge(jbsCsb, csb, edgeIntervalStation)),
            Set.of());
    String conflict = "single:SURC:JBS:CSB:throat";
    RailGraph graph =
        new CorridorInfoOverrideGraph(
            delegate,
            Map.of(
                edgeStationThroat,
                new RailGraphCorridorInfo(
                    conflict, switcherA, switcherB, List.of(switcherA, switcherB), false)));
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 1, 0, 0, 0);

    OccupancyRequestContext context =
        builder
            .buildContextFromNodes(
                "SURC-DS-LW-0996",
                Optional.of(RouteId.of("SURC:DS:DS-1F_Full")),
                List.of(jbs, csb),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow();
    OccupancyRequest request = context.request();

    assertEquals(List.of(jbs, throat), context.pathNodes());
    assertTrue(request.resourceList().contains(OccupancyResource.forConflict(conflict)));
    assertEquals(
        CorridorDirection.A_TO_B,
        request.directedContext().orElseThrow().singleConflictDirections().get(conflict));
    assertEquals(CorridorDirection.A_TO_B, request.corridorDirections().get(conflict));
  }

  @Test
  void repeatedOppositeEdgeTraversalSplitsContinuousMovementRequest() {
    NodeId nodeA = NodeId.of("SURC:D:HHU:3");
    NodeId nodeWsd = NodeId.of("SURC:WSD:1");
    NodeId nodeJbs = NodeId.of("SURC:S:JBS:1");
    NodeId nodeC = NodeId.of("SURC:AFTER:JBS");
    EdgeId edgeAWsd = EdgeId.undirected(nodeA, nodeWsd);
    EdgeId edgeWsdJbs = EdgeId.undirected(nodeWsd, nodeJbs);
    EdgeId edgeWsdC = EdgeId.undirected(nodeWsd, nodeC);
    RailEdge aWsd = new RailEdge(edgeAWsd, nodeA, nodeWsd, 10, 8.0, true, Optional.empty());
    RailEdge wsdJbs = new RailEdge(edgeWsdJbs, nodeWsd, nodeJbs, 10, 8.0, true, Optional.empty());
    RailEdge wsdC = new RailEdge(edgeWsdC, nodeWsd, nodeC, 10, 8.0, true, Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(
                nodeA, waypoint(nodeA, 0.0),
                nodeWsd, waypoint(nodeWsd, 10.0),
                nodeJbs, waypoint(nodeJbs, 20.0),
                nodeC, waypoint(nodeC, 30.0)),
            Map.of(edgeAWsd, aWsd, edgeWsdJbs, wsdJbs, edgeWsdC, wsdC),
            Set.of());
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 8, 0, 0, 0);
    List<NodeId> routeNodes = List.of(nodeA, nodeWsd, nodeJbs, nodeWsd, nodeC);

    OccupancyRequestContext context =
        builder
            .buildContextFromNodes(
                "SURC-DS-LW-4660",
                Optional.of(RouteId.of("SURC:DS:DS-1F_Full")),
                routeNodes,
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0,
                AuthorizationPurpose.RUNTIME_MOVE)
            .orElseThrow();

    assertEquals(List.of(nodeA, nodeWsd, nodeJbs), context.pathNodes());
    assertEquals(2, context.edges().size());
    assertTrue(context.request().resourceList().contains(OccupancyResource.forEdge(edgeWsdJbs)));
    assertEquals(1, context.pathNodes().stream().filter(nodeWsd::equals).count());
  }

  private static RailNode waypoint(NodeId nodeId, double x) {
    return new SignRailNode(
        nodeId, NodeType.WAYPOINT, new Vector(x, 64.0, 0.0), Optional.empty(), Optional.empty());
  }

  private static RailNode station(NodeId nodeId, String station, int track, double x) {
    return new SignRailNode(
        nodeId,
        NodeType.STATION,
        new Vector(x, 64.0, 0.0),
        Optional.empty(),
        Optional.of(WaypointMetadata.station("SURC", station, track)));
  }

  private static RailNode stationThroat(
      NodeId nodeId, String station, int track, String sequence, double x) {
    return new SignRailNode(
        nodeId,
        NodeType.WAYPOINT,
        new Vector(x, 64.0, 0.0),
        Optional.empty(),
        Optional.of(WaypointMetadata.stationThroat("SURC", station, track, sequence)));
  }

  private static RailNode interval(
      NodeId nodeId, String origin, String destination, int track, String sequence, double x) {
    return new SignRailNode(
        nodeId,
        NodeType.WAYPOINT,
        new Vector(x, 64.0, 0.0),
        Optional.empty(),
        Optional.of(WaypointMetadata.interval("SURC", origin, destination, track, sequence)));
  }

  private static RailNode depot(NodeId nodeId, String depot, int track, double x) {
    return new SignRailNode(
        nodeId,
        NodeType.DEPOT,
        new Vector(x, 64.0, 0.0),
        Optional.empty(),
        Optional.of(WaypointMetadata.depot("SURC", depot, track)));
  }

  private static RailNode depotThroat(
      NodeId nodeId, String depot, int track, String sequence, double x) {
    return new SignRailNode(
        nodeId,
        NodeType.WAYPOINT,
        new Vector(x, 64.0, 0.0),
        Optional.empty(),
        Optional.of(WaypointMetadata.depotThroat("SURC", depot, track, sequence)));
  }

  private static RailNode switcher(NodeId nodeId, double x) {
    return new SignRailNode(
        nodeId, NodeType.SWITCHER, new Vector(x, 64.0, 0.0), Optional.empty(), Optional.empty());
  }

  private static RailEdge edge(NodeId from, NodeId to, EdgeId id) {
    return new RailEdge(id, from, to, 10, 8.0, true, Optional.empty());
  }

  private static final class CorridorInfoOverrideGraph
      implements RailGraph, RailGraphCorridorSupport {

    private final SimpleRailGraph delegate;
    private final Map<EdgeId, RailGraphCorridorInfo> overrides;

    private CorridorInfoOverrideGraph(
        SimpleRailGraph delegate, Map<EdgeId, RailGraphCorridorInfo> overrides) {
      this.delegate = delegate;
      this.overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
    }

    @Override
    public Collection<RailNode> nodes() {
      return delegate.nodes();
    }

    @Override
    public Collection<RailEdge> edges() {
      return delegate.edges();
    }

    @Override
    public Optional<RailNode> findNode(NodeId id) {
      return delegate.findNode(id);
    }

    @Override
    public Set<RailEdge> edgesFrom(NodeId id) {
      return delegate.edgesFrom(id);
    }

    @Override
    public boolean isBlocked(EdgeId id) {
      return delegate.isBlocked(id);
    }

    @Override
    public Optional<String> conflictKeyForEdge(EdgeId edgeId) {
      Optional<RailGraphCorridorInfo> override = overrideFor(edgeId);
      if (override.isPresent()) {
        return override.map(RailGraphCorridorInfo::key);
      }
      return delegate.conflictKeyForEdge(edgeId);
    }

    @Override
    public Optional<RailGraphCorridorInfo> corridorInfoForEdge(EdgeId edgeId) {
      if (edgeId == null) {
        return Optional.empty();
      }
      return overrideFor(edgeId).or(() -> delegate.corridorInfoForEdge(edgeId));
    }

    private Optional<RailGraphCorridorInfo> overrideFor(EdgeId edgeId) {
      if (edgeId == null) {
        return Optional.empty();
      }
      RailGraphCorridorInfo info = overrides.get(edgeId);
      if (info == null) {
        info = overrides.get(EdgeId.undirected(edgeId.a(), edgeId.b()));
      }
      return Optional.ofNullable(info);
    }
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
