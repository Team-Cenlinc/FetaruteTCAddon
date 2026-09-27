package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
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
    assertEquals(5L, context.minimumSafeAuthorityDistanceBlocks());
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
    assertEquals(100L, context.minimumSafeAuthorityDistanceBlocks());
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
  void currentPositionWithoutCanonicalPlanKeepsSingleDirectionUnknown() {
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
    assertFalse(forward.corridorDirections().containsKey(conflictKey));
    assertEquals(0, forward.conflictEntryOrders().get(conflictKey));
    assertFalse(
        forward
            .directedContext()
            .orElseThrow()
            .singleConflictDirections()
            .containsKey(conflictKey));

    OccupancyRequest reverse =
        builder.buildCurrentPositionRequest(
            "train", Optional.empty(), nodeB, Optional.of(nodeA), Instant.now(), 0);
    assertFalse(reverse.corridorDirections().containsKey(conflictKey));
  }

  @Test
  void holdWithoutCanonicalPlanKeepsSingleDirectionUnknown() {
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
    assertFalse(request.corridorDirections().containsKey(conflictKey));
    assertEquals(0, request.conflictEntryOrders().get(conflictKey));
    assertFalse(
        request
            .directedContext()
            .orElseThrow()
            .singleConflictDirections()
            .containsKey(conflictKey));
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
  void switcherZoneBlocksLateSwitcherConflictBeyondSafeStop() {
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
            NodeType.STATION,
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
  void canonicalRearRetainPathPreservesPreviousLegAcrossRouteIndexAdvance() {
    NodeId depot = NodeId.of("SURC:D:HHU:3");
    NodeId switcher = NodeId.of("SWITCHER:Towny:502:74:996");
    NodeId current = NodeId.of("SURC:S:HHU:1");
    NodeId next = NodeId.of("SURC:ZKW:HHU:1:006");
    List<NodeId> physicalPath = List.of(depot, switcher, current, next);
    SimpleRailGraph graph = linearGraph(physicalPath);
    RouteId routeId = RouteId.of("SURC:MT:MT-2F_Short");

    OccupancyRequest request =
        new OccupancyRequestBuilder(graph, 3, 0, 0, 0)
            .buildContextFromNodesWithDirectionContext(
                "SURC-MT-LP-5108",
                Optional.of(routeId),
                List.of(depot, current, next),
                List.of(depot, current, next),
                1,
                Instant.parse("2026-01-01T00:00:00Z"),
                0,
                AuthorizationPurpose.RUNTIME_MOVE)
            .orElseThrow()
            .request()
            .withDirectedSource("PERIODIC_TICK");

    DirectedTraversalContext context = request.directedContext().orElseThrow();
    assertEquals(List.of(current, next), context.expandedPathNodes());
    assertFalse(request.resourceList().contains(OccupancyResource.forNode(switcher)));
    assertFalse(
        request
            .resourceList()
            .contains(OccupancyResource.forEdge(EdgeId.undirected(depot, switcher))));
    assertEquals(
        List.of(depot, switcher, current),
        context.canonicalRearRetainPathPlan().orElseThrow().expandedPathNodes());
    assertEquals(
        List.of(depot, switcher, current),
        request
            .movementPlanSnapshot()
            .orElseThrow()
            .canonicalRearRetainPathPlan()
            .orElseThrow()
            .expandedPathNodes());
  }

  @Test
  void canonicalRearRetainPathPreservesCurrentLegPrefixAtIntermediateLastPassed() {
    NodeId station = NodeId.of("SURC:S:RVS:1");
    NodeId throatTwo = NodeId.of("SURC:PPK:RVS:1:002");
    NodeId current = NodeId.of("SURC:PPK:RVS:1:001");
    NodeId next = NodeId.of("SWITCHER:Towny:593:68:1140");
    SimpleRailGraph graph = linearGraph(List.of(station, throatTwo, current, next));
    RouteId routeId = RouteId.of("SURC:MT:MT-2F_Short");

    OccupancyRequest request =
        new OccupancyRequestBuilder(graph, 3, 0, 0, 0)
            .buildContextFromNodesWithDirectionContext(
                "SURC-MT-LP-7327",
                Optional.of(routeId),
                List.of(current, next),
                List.of(station, next),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0,
                AuthorizationPurpose.RUNTIME_MOVE)
            .orElseThrow()
            .request();

    DirectedTraversalContext context = request.directedContext().orElseThrow();
    assertEquals(List.of(current, next), context.expandedPathNodes());
    assertFalse(request.resourceList().contains(OccupancyResource.forNode(station)));
    assertFalse(request.resourceList().contains(OccupancyResource.forNode(throatTwo)));
    assertEquals(
        List.of(station, throatTwo, current),
        context.canonicalRearRetainPathPlan().orElseThrow().expandedPathNodes());
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

  /**
   * 尾部保护 = 车身 + 车尾之后 {@code rear-guard-edges} 条边，不拿车头身后那条边的长度当余量。
   *
   * <p>从车头节点往回依次是 25、7、4、7、21 格的边（照实服 PPK:2 的进站路径）。车长 30：车身覆盖 25 + 7， 车尾之后再留 1 条边（4 格），止于
   * S2。旧公式要覆盖"车头身后那条边 25 + 车长 30 = 55"再取整到整边，一路退到 64 格外的 R0。
   */
  @Test
  void rearGuardCoversTheTrainBodyPlusConfiguredEdgesBehindTheTail() {
    NodeId r0 = NodeId.of("R0");
    NodeId s1 = NodeId.of("S1");
    NodeId s2 = NodeId.of("S2");
    NodeId s3 = NodeId.of("S3");
    NodeId s4 = NodeId.of("S4");
    NodeId platform = NodeId.of("P");
    SimpleRailGraph graph = linearGraph(List.of(r0, s1, s2, s3, s4, platform), 21, 7, 4, 7, 25);
    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(graph, 1, 0, 1, 0, 0L, 1, 30L, message -> {});

    OccupancyRequest request =
        builder.buildRearGuardRequestFromNodes(
            "Train-1",
            Optional.empty(),
            List.of(r0, s1, s2, s3, s4, platform),
            5,
            Instant.now(),
            0);

    assertTrue(
        request
            .resourceList()
            .contains(OccupancyResource.forEdge(EdgeId.undirected(s4, platform))));
    assertTrue(
        request.resourceList().contains(OccupancyResource.forEdge(EdgeId.undirected(s3, s4))));
    assertTrue(
        request.resourceList().contains(OccupancyResource.forEdge(EdgeId.undirected(s2, s3))),
        "车尾之后再留 1 条边");
    assertFalse(
        request.resourceList().contains(OccupancyResource.forEdge(EdgeId.undirected(s1, s2))),
        () -> request.resourceList().toString());
    assertFalse(request.resourceList().contains(OccupancyResource.forNode(s1)));
    assertFalse(request.resourceList().contains(OccupancyResource.forNode(r0)));
  }

  /**
   * 车身从车头节点往回量：车长 34（实服 MT 三节模型车的保守估算）时，车身盖到 36 格处的 S2，再留 1 条边到 S1。
   *
   * <p>停站时车头越过站台节点约半个车长，实际车尾只在节点后方十几格；从节点起量的保护因此偏长——这是已知局限， 按停稳后的实测足迹收窄尚未实现。
   */
  @Test
  void rearGuardIsMeasuredBackFromTheHeadNode() {
    NodeId r0 = NodeId.of("R0");
    NodeId s1 = NodeId.of("S1");
    NodeId s2 = NodeId.of("S2");
    NodeId s3 = NodeId.of("S3");
    NodeId s4 = NodeId.of("S4");
    NodeId platform = NodeId.of("P");
    SimpleRailGraph graph = linearGraph(List.of(r0, s1, s2, s3, s4, platform), 21, 7, 4, 7, 25);
    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(graph, 1, 0, 1, 0, 0L, 1, 34L, message -> {});

    OccupancyRequest request =
        builder.buildRearGuardRequestFromNodes(
            "Train-1",
            Optional.empty(),
            List.of(r0, s1, s2, s3, s4, platform),
            5,
            Instant.now(),
            0);

    assertTrue(
        request.resourceList().contains(OccupancyResource.forEdge(EdgeId.undirected(s1, s2))));
    assertTrue(request.resourceList().contains(OccupancyResource.forNode(s1)));
    assertFalse(
        request.resourceList().contains(OccupancyResource.forEdge(EdgeId.undirected(r0, s1))),
        () -> request.resourceList().toString());
  }

  /** 车长未知时覆盖全部可证明的后向路径：不能用边数代替车长提前放掉身后的资源。 */
  @Test
  void rearGuardKeepsTheWholeProvenPathWhenTrainLengthIsUnknown() {
    NodeId r0 = NodeId.of("R0");
    NodeId s1 = NodeId.of("S1");
    NodeId s2 = NodeId.of("S2");
    NodeId platform = NodeId.of("P");
    SimpleRailGraph graph = linearGraph(List.of(r0, s1, s2, platform), 21, 7, 25);
    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(graph, 1, 0, 1, 0, 0L, 1, Long.MAX_VALUE, message -> {});

    OccupancyRequest request =
        builder.buildRearGuardRequestFromNodes(
            "Train-1", Optional.empty(), List.of(r0, s1, s2, platform), 3, Instant.now(), 0);

    assertTrue(
        request.resourceList().contains(OccupancyResource.forEdge(EdgeId.undirected(r0, s1))));
    assertTrue(request.resourceList().contains(OccupancyResource.forNode(r0)));
  }

  /** 直线图：相邻节点依次相连，边长按给定顺序。 */
  private static SimpleRailGraph linearGraph(List<NodeId> nodes, int... lengths) {
    Map<NodeId, RailNode> railNodes = new LinkedHashMap<>();
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    double x = 0.0;
    for (int i = 0; i < nodes.size(); i++) {
      railNodes.put(nodes.get(i), waypoint(nodes.get(i), x));
      if (i < lengths.length) {
        EdgeId edgeId = EdgeId.undirected(nodes.get(i), nodes.get(i + 1));
        edges.put(
            edgeId,
            new RailEdge(
                edgeId, nodes.get(i), nodes.get(i + 1), lengths[i], 8.0, true, Optional.empty()));
        x += lengths[i];
      }
    }
    return new SimpleRailGraph(railNodes, edges, Set.of());
  }

  @Test
  void rearGuardDistanceKeepsEnoughPhysicalEdgesForTrainLengthAndConfiguredMargin() {
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
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(nodeA, a, nodeB, b, nodeC, c, nodeD, d),
            Map.of(
                edgeAB,
                new RailEdge(edgeAB, nodeA, nodeB, 10, 8.0, true, Optional.empty()),
                edgeBC,
                new RailEdge(edgeBC, nodeB, nodeC, 10, 8.0, true, Optional.empty()),
                edgeCD,
                new RailEdge(edgeCD, nodeC, nodeD, 10, 8.0, true, Optional.empty())),
            Set.of());
    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(graph, 1, 0, 1, 0, 0L, 1, 5L, message -> {});

    OccupancyRequest request =
        builder.buildRearGuardRequestFromNodes(
            "Train-1",
            Optional.of(RouteId.of("OP:LINE:ROUTE")),
            List.of(nodeA, nodeB, nodeC, nodeD),
            3,
            Instant.now(),
            0);

    assertFalse(request.resourceList().contains(OccupancyResource.forEdge(edgeAB)));
    assertTrue(request.resourceList().contains(OccupancyResource.forEdge(edgeBC)));
    assertTrue(request.resourceList().contains(OccupancyResource.forEdge(edgeCD)));
  }

  @Test
  void rearGuardRequestAcceptsMissingCurrentNodeInBothCompatibilityPaths() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
    EdgeId edgeAB = EdgeId.undirected(nodeA, nodeB);
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
            new Vector(12.0, 64.0, 0.0),
            Optional.empty(),
            Optional.empty());
    RailEdge edge = new RailEdge(edgeAB, nodeA, nodeB, 12, 8.0, true, Optional.empty());
    SimpleRailGraph graph =
        new SimpleRailGraph(Map.of(nodeA, a, nodeB, b), Map.of(edgeAB, edge), Set.of());
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 1, 0, 1, 0);
    RouteId routeId = RouteId.of("OP:LINE:ROUTE");
    List<NodeId> nodes = java.util.Arrays.asList(null, nodeB);
    MovementPlanSnapshot plan =
        movementPlanWithDirections("Train-1", routeId, nodeA, nodeB, edgeAB, Map.of());

    OccupancyRequest compatibility =
        builder.buildRearGuardRequestFromNodes(
            "Train-1", Optional.of(routeId), nodes, 0, Instant.now(), 0);
    OccupancyRequest canonical =
        builder.buildRearGuardRequestFromPlan(
            "Train-1",
            Optional.of(routeId),
            nodes,
            0,
            Instant.now(),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            plan);

    assertTrue(compatibility.directedContext().orElseThrow().currentNode().isEmpty());
    assertTrue(canonical.directedContext().orElseThrow().currentNode().isEmpty());
  }

  @Test
  void protectiveRequestsUseCanonicalPlanDirectionWithoutLocalFallback() {
    NodeId depot = NodeId.of("SURC:D:HHU:3");
    NodeId switcher = NodeId.of("SWITCHER:Towny:502:74:996");
    EdgeId edgeId = EdgeId.undirected(depot, switcher);
    SimpleRailGraph delegate =
        new SimpleRailGraph(
            Map.of(depot, depot(depot, "HHU", 3, 0.0), switcher, switcher(switcher, 10.0)),
            Map.of(edgeId, edge(depot, switcher, edgeId)),
            Set.of());
    String conflict = "single:SURC:CGL:WYB:1:001:SURC:D:HHU:3~SWITCHER:Towny:502:74:996";
    RailGraph graph =
        new CorridorInfoOverrideGraph(
            delegate,
            Map.of(
                edgeId,
                new RailGraphCorridorInfo(
                    conflict, depot, switcher, List.of(depot, switcher), false)));
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 2, 0, 1, 0);
    RouteId routeId = RouteId.of("SURC:DS:DS-1F_Full");
    MovementPlanSnapshot canonicalPlan =
        movementPlanWithDirections(
            "SURC-DS-LW-1581",
            routeId,
            switcher,
            depot,
            edgeId,
            Map.of(conflict, CorridorDirection.B_TO_A));

    OccupancyRequest rearGuard =
        builder.buildRearGuardRequestFromPlan(
            "SURC-DS-LW-1581",
            Optional.of(routeId),
            List.of(depot, switcher),
            1,
            Instant.parse("2026-01-01T00:00:00Z"),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            canonicalPlan);
    OccupancyRequest hold =
        builder.buildHoldPositionRequestFromPlan(
            "SURC-DS-LW-1581",
            Optional.of(routeId),
            depot,
            Optional.of(switcher),
            List.of(depot, switcher),
            0,
            Instant.parse("2026-01-01T00:00:00Z"),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            canonicalPlan);
    MovementPlanSnapshot unknownPlan =
        movementPlanWithDirections(
            "SURC-DS-LW-1581",
            routeId,
            switcher,
            depot,
            edgeId,
            Map.of(conflict, CorridorDirection.UNKNOWN));
    OccupancyRequest unresolvedCurrent =
        builder.buildCurrentPositionRequestFromPlan(
            "SURC-DS-LW-1581",
            Optional.of(routeId),
            depot,
            Optional.of(switcher),
            Instant.parse("2026-01-01T00:00:00Z"),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            unknownPlan);

    assertEquals(CorridorDirection.B_TO_A, rearGuard.corridorDirections().get(conflict));
    assertEquals(CorridorDirection.B_TO_A, hold.corridorDirections().get(conflict));
    assertEquals(
        canonicalPlan.expandedPathNodes(),
        rearGuard.movementPlanSnapshot().orElseThrow().expandedPathNodes());
    assertEquals(
        canonicalPlan.expandedPathNodes(),
        hold.movementPlanSnapshot().orElseThrow().expandedPathNodes());
    assertFalse(unresolvedCurrent.corridorDirections().containsKey(conflict));
  }

  @Test
  void depotSpawnAuthorityFollowsSelectedPathThroughClearanceOnly() {
    NodeId depot = NodeId.of("SURC:D:HHU:3");
    NodeId throat = NodeId.of("SWITCHER:Towny:502:74:996");
    NodeId clearance = NodeId.of("SURC:ZKW:HHU:1:006");
    NodeId remote = NodeId.of("SURC:ZKW:HHU:1:005");
    NodeId branch = NodeId.of("SURC:HHU:LWN:1:001");
    EdgeId depotThroat = EdgeId.undirected(depot, throat);
    EdgeId throatClearance = EdgeId.undirected(throat, clearance);
    EdgeId clearanceRemote = EdgeId.undirected(clearance, remote);
    EdgeId throatBranch = EdgeId.undirected(throat, branch);
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(
                depot, depot(depot, "HHU", 3, 0.0),
                throat, switcher(throat, 10.0),
                clearance, interval(clearance, "ZKW", "HHU", 1, "006", 20.0),
                remote, interval(remote, "ZKW", "HHU", 1, "005", 30.0),
                branch, interval(branch, "HHU", "LWN", 1, "001", 20.0)),
            Map.of(
                depotThroat, edge(depot, throat, depotThroat),
                throatClearance, edge(throat, clearance, throatClearance),
                clearanceRemote, edge(clearance, remote, clearanceRemote),
                throatBranch, edge(throat, branch, throatBranch)),
            Set.of());
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 1, 0, 0, 0);
    OccupancyRequestContext context =
        builder
            .buildContextFromNodes(
                "SURC-DS-LW-1181",
                Optional.of(RouteId.of("SURC:DS:DS-1F_Full")),
                List.of(depot, remote),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                10,
                AuthorizationPurpose.DEPOT_SPAWN)
            .orElseThrow();

    assertEquals(List.of(depot, throat, clearance), context.pathNodes());
    assertTrue(context.request().resourceList().contains(OccupancyResource.forEdge(depotThroat)));
    assertTrue(
        context.request().resourceList().contains(OccupancyResource.forEdge(throatClearance)));
    assertFalse(
        context.request().resourceList().contains(OccupancyResource.forEdge(clearanceRemote)));
    assertFalse(context.request().resourceList().contains(OccupancyResource.forEdge(throatBranch)));
    OccupancyResource sharedSwitcher = OccupancyResource.forConflict("switcher:" + throat.value());
    assertTrue(context.request().resourceList().contains(sharedSwitcher));

    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> java.time.Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyRequest returningBranch =
        new OccupancyRequest(
            "SURC-DS-LW-1582",
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:00Z"),
            List.of(sharedSwitcher),
            Map.of());
    assertTrue(manager.acquire(returningBranch).allowed());

    OccupancyDecision spawnDecision = manager.canEnterPreview(context.request());
    assertFalse(spawnDecision.allowed());
    assertTrue(
        spawnDecision.blockers().stream()
            .anyMatch(blocker -> sharedSwitcher.equals(blocker.resource())));
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
  void semanticDirectionBuildsLongPathAxisOncePerDirectionContext() {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    List<NodeId> routeNodes = new ArrayList<>();
    NodeId previous = null;
    for (int index = 0; index < 12; index++) {
      NodeId node = NodeId.of("SURC:S" + index + ":S" + (index + 1) + ":1:001");
      routeNodes.add(node);
      nodes.put(node, interval(node, "S" + index, "S" + (index + 1), 1, "001", index * 10.0));
      if (previous != null) {
        EdgeId edgeId = EdgeId.undirected(previous, node);
        edges.put(edgeId, edge(previous, node, edgeId));
      }
      previous = node;
    }
    CountingCorridorGraph graph =
        new CountingCorridorGraph(new SimpleRailGraph(nodes, edges, Set.of()));

    OccupancyRequest request =
        new OccupancyRequestBuilder(graph, 11, 0, 0, 0)
            .buildContextFromNodesWithDirectionContext(
                "train",
                Optional.of(RouteId.of("SURC:MT:LONG_AXIS")),
                routeNodes,
                routeNodes,
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0,
                AuthorizationPurpose.RUNTIME_MOVE)
            .orElseThrow()
            .request();

    assertFalse(request.corridorDirections().isEmpty());
    assertTrue(
        request.corridorDirections().values().stream()
            .allMatch(direction -> direction == CorridorDirection.A_TO_B));
    assertTrue(
        graph.findNodeCalls() <= 300, () -> "单条路径的语义方向轴被重复扫描: findNode=" + graph.findNodeCalls());
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
    List<String> diagnostics = new ArrayList<>();
    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(graph, 6, 0, 0, 0, diagnostics::add);

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
    assertTrue(
        diagnostics.stream()
            .anyMatch(
                message ->
                    message.contains("方向判定失败: stage=FINAL")
                        && message.contains("key=" + conflict)
                        && message.contains("finalSource=NONE")),
        () -> "真正 UNKNOWN 的实际 single 资源必须保留可审计诊断: " + diagnostics);
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
  void semanticDirectionUsesOrderedAxisAtCanonicalHubToIntervalBoundary() {
    NodeId wsd = NodeId.of("SURC:S:WSD:2");
    NodeId spbWsd = NodeId.of("SURC:SPB:WSD:2:001");
    NodeId spb = NodeId.of("SURC:S:SPB:2");
    NodeId entryHub = NodeId.of("SWITCHER:Towny:-566:77:1179");
    NodeId spbJbs1 = NodeId.of("SURC:SPB:JBS:1:001");
    NodeId spbJbs2 = NodeId.of("SURC:SPB:JBS:1:002");
    NodeId exitHub = NodeId.of("SWITCHER:Towny:-579:77:1214");
    EdgeId edgeWsdInterval = EdgeId.undirected(wsd, spbWsd);
    EdgeId edgeSpbWsd = EdgeId.undirected(spbWsd, spb);
    EdgeId edgeEntryHub = EdgeId.undirected(spb, entryHub);
    EdgeId edgeConflictEntry = EdgeId.undirected(entryHub, spbJbs1);
    EdgeId edgeConflictMiddle = EdgeId.undirected(spbJbs1, spbJbs2);
    EdgeId edgeConflictExit = EdgeId.undirected(spbJbs2, exitHub);
    SimpleRailGraph delegate =
        new SimpleRailGraph(
            Map.of(
                wsd, station(wsd, "WSD", 2, 0.0),
                spbWsd, interval(spbWsd, "SPB", "WSD", 2, "001", 10.0),
                spb, station(spb, "SPB", 2, 20.0),
                entryHub, switcher(entryHub, 30.0),
                spbJbs1, interval(spbJbs1, "SPB", "JBS", 1, "001", 40.0),
                spbJbs2, interval(spbJbs2, "SPB", "JBS", 1, "002", 50.0),
                exitHub, switcher(exitHub, 60.0)),
            Map.of(
                edgeWsdInterval, edge(wsd, spbWsd, edgeWsdInterval),
                edgeSpbWsd, edge(spbWsd, spb, edgeSpbWsd),
                edgeEntryHub, edge(spb, entryHub, edgeEntryHub),
                edgeConflictEntry, edge(entryHub, spbJbs1, edgeConflictEntry),
                edgeConflictMiddle, edge(spbJbs1, spbJbs2, edgeConflictMiddle),
                edgeConflictExit, edge(spbJbs2, exitHub, edgeConflictExit)),
            Set.of());
    String conflict = "single:mt:" + entryHub.value() + "~" + exitHub.value();
    RailGraph graph =
        new CorridorInfoOverrideGraph(
            delegate,
            Map.of(
                edgeConflictEntry,
                new RailGraphCorridorInfo(
                    conflict,
                    entryHub,
                    exitHub,
                    List.of(entryHub, spbJbs1, spbJbs2, exitHub),
                    false)));
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 8, 0, 0, 0);

    OccupancyRequest request =
        builder
            .buildContextFromNodes(
                "SURC-MT-LP-9914",
                Optional.of(RouteId.of("SURC:MT:WSD_JBS")),
                List.of(wsd, exitHub),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow()
            .request();

    assertTrue(request.resourceList().contains(OccupancyResource.forConflict(conflict)));
    assertEquals(CorridorDirection.A_TO_B, request.corridorDirections().get(conflict));
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
    List<String> diagnostics = new ArrayList<>();
    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(graph, 3, 0, 0, 0, diagnostics::add);

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
    assertTrue(
        diagnostics.stream().noneMatch(message -> message.contains("方向判定失败")),
        () -> "已由完整计划确定方向时不应留下失败诊断: " + diagnostics);
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

    assertEquals(
        List.of(jbs, throat, switcherA, switcherB, switcherC, jbsCsb), context.pathNodes());
    assertTrue(request.resourceList().contains(OccupancyResource.forConflict(conflict)));
    assertEquals(
        CorridorDirection.A_TO_B,
        request.directedContext().orElseThrow().singleConflictDirections().get(conflict));
    assertEquals(CorridorDirection.A_TO_B, request.corridorDirections().get(conflict));
  }

  @Test
  void canonicalDirectionContextDoesNotReportIntermediateWindowFailureWhenFinalDirectionIsKnown() {
    NodeId station = NodeId.of("SURC:S:JBS:1");
    NodeId switcherA = NodeId.of("SWITCHER:Towny:-520:77:1390");
    NodeId switcherB = NodeId.of("SWITCHER:Towny:-520:77:1410");
    NodeId switcherC = NodeId.of("SWITCHER:Towny:-520:77:1430");
    NodeId interval = NodeId.of("SURC:JBS:CSB:2:001");
    NodeId nextStation = NodeId.of("SURC:S:CSB:2");
    EdgeId edgeStationA = EdgeId.undirected(station, switcherA);
    EdgeId edgeAB = EdgeId.undirected(switcherA, switcherB);
    EdgeId edgeBC = EdgeId.undirected(switcherB, switcherC);
    EdgeId edgeCInterval = EdgeId.undirected(switcherC, interval);
    EdgeId edgeIntervalStation = EdgeId.undirected(interval, nextStation);
    SimpleRailGraph delegate =
        new SimpleRailGraph(
            Map.of(
                station, station(station, "JBS", 1, 0.0),
                switcherA, switcher(switcherA, 10.0),
                switcherB, switcher(switcherB, 20.0),
                switcherC, switcher(switcherC, 30.0),
                interval, waypoint(interval, 40.0),
                nextStation, station(nextStation, "CSB", 2, 50.0)),
            Map.of(
                edgeStationA, edge(station, switcherA, edgeStationA),
                edgeAB, edge(switcherA, switcherB, edgeAB),
                edgeBC, edge(switcherB, switcherC, edgeBC),
                edgeCInterval, edge(switcherC, interval, edgeCInterval),
                edgeIntervalStation, edge(interval, nextStation, edgeIntervalStation)),
            Set.of());
    String conflict = "single:SURC:JBS:CSB:direction-context";
    RailGraph graph =
        new CorridorInfoOverrideGraph(
            delegate,
            Map.of(
                edgeCInterval,
                new RailGraphCorridorInfo(
                    conflict, switcherA, switcherB, List.of(switcherA, switcherB), false)));
    List<String> diagnostics = new ArrayList<>();
    OccupancyRequestBuilder builder =
        new OccupancyRequestBuilder(graph, 2, 0, 0, 0, diagnostics::add);

    OccupancyRequest request =
        builder
            .buildContextFromNodesWithDirectionContext(
                "SURC-DS-LW-0996",
                Optional.of(RouteId.of("SURC:DS:DS-1F_Full")),
                List.of(switcherC, nextStation),
                List.of(station, nextStation),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0,
                AuthorizationPurpose.RUNTIME_MOVE)
            .orElseThrow()
            .request();

    assertEquals(CorridorDirection.A_TO_B, request.corridorDirections().get(conflict));
    assertTrue(
        diagnostics.stream().noneMatch(message -> message.contains("方向判定失败")),
        () -> "完整方向上下文已恢复方向时不应报告最终失败: " + diagnostics);
  }

  @Test
  void stationThroatEdgeFallsBackToOrderedCorridorWhenSemanticAxisSwitchesAtStation() {
    NodeId jbsWsd = NodeId.of("SURC:JBS:WSD:1:001");
    NodeId jbs = NodeId.of("SURC:S:JBS:1");
    NodeId throat = NodeId.of("SURC:S:JBS:1:001");
    NodeId switcher = NodeId.of("SWITCHER:Towny:-520:77:1390");
    NodeId jbsCsb = NodeId.of("SURC:JBS:CSB:2:001");
    NodeId csb = NodeId.of("SURC:S:CSB:2");
    EdgeId edgeWsdStation = EdgeId.undirected(jbsWsd, jbs);
    EdgeId edgeStationThroat = EdgeId.undirected(jbs, throat);
    EdgeId edgeThroatSwitcher = EdgeId.undirected(throat, switcher);
    EdgeId edgeSwitcherCsb = EdgeId.undirected(switcher, jbsCsb);
    EdgeId edgeCsbStation = EdgeId.undirected(jbsCsb, csb);
    SimpleRailGraph delegate =
        new SimpleRailGraph(
            Map.of(
                jbsWsd, interval(jbsWsd, "JBS", "WSD", 1, "001", 0.0),
                jbs, station(jbs, "JBS", 1, 10.0),
                throat, stationThroat(throat, "JBS", 1, "001", 20.0),
                switcher, switcher(switcher, 30.0),
                jbsCsb, interval(jbsCsb, "JBS", "CSB", 2, "001", 40.0),
                csb, station(csb, "CSB", 2, 50.0)),
            Map.of(
                edgeWsdStation, edge(jbsWsd, jbs, edgeWsdStation),
                edgeStationThroat, edge(jbs, throat, edgeStationThroat),
                edgeThroatSwitcher, edge(throat, switcher, edgeThroatSwitcher),
                edgeSwitcherCsb, edge(switcher, jbsCsb, edgeSwitcherCsb),
                edgeCsbStation, edge(jbsCsb, csb, edgeCsbStation)),
            Set.of());
    String conflict =
        "single:SURC:CGL:WYB:1:001:SWITCHER:Towny:-262:74:1117~SWITCHER:Towny:-520:77:1390";
    RailGraph graph =
        new CorridorInfoOverrideGraph(
            delegate,
            Map.of(
                edgeStationThroat,
                new RailGraphCorridorInfo(
                    conflict, jbsWsd, switcher, List.of(jbsWsd, jbs, throat, switcher), false)));
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 4, 0, 0, 0);

    OccupancyRequest request =
        builder
            .buildContextFromNodes(
                "SURC-DS-LW-4590",
                Optional.of(RouteId.of("SURC:DS:DS-1F_Full")),
                List.of(jbsWsd, csb),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow()
            .request();

    assertTrue(request.resourceList().contains(OccupancyResource.forConflict(conflict)));
    assertEquals(CorridorDirection.A_TO_B, request.corridorDirections().get(conflict));
  }

  @Test
  void depotMetadataDoesNotFlipDirectionAfterTrainLeavesDepotAnchor() {
    NodeId depot = NodeId.of("SURC:D:OFL:1");
    NodeId throat = NodeId.of("SURC:D:OFL:1:001");
    NodeId entry = NodeId.of("SWITCHER:Towny:-515:77:2272");
    NodeId current = NodeId.of("SWITCHER:Towny:-516:77:2268");
    NodeId oflMlu3 = NodeId.of("SURC:OFL:MLU:1:003");
    NodeId inner = NodeId.of("SWITCHER:Towny:-516:77:2236");
    NodeId exit = NodeId.of("SWITCHER:Towny:-516:77:2212");
    NodeId oflMlu2 = NodeId.of("SURC:OFL:MLU:1:002");
    NodeId oflMlu1 = NodeId.of("SURC:OFL:MLU:1:001");
    NodeId station = NodeId.of("SURC:S:OFL:1");
    EdgeId edgeDepotThroat = EdgeId.undirected(depot, throat);
    EdgeId edgeThroatEntry = EdgeId.undirected(throat, entry);
    EdgeId edgeEntryCurrent = EdgeId.undirected(entry, current);
    EdgeId edgeCurrentInterval = EdgeId.undirected(current, oflMlu3);
    EdgeId edgeIntervalInner = EdgeId.undirected(oflMlu3, inner);
    EdgeId edgeInnerExit = EdgeId.undirected(inner, exit);
    EdgeId edgeExitInterval = EdgeId.undirected(exit, oflMlu2);
    EdgeId edgeInterval2Interval1 = EdgeId.undirected(oflMlu2, oflMlu1);
    EdgeId edgeIntervalStation = EdgeId.undirected(oflMlu1, station);
    SimpleRailGraph delegate =
        new SimpleRailGraph(
            Map.of(
                depot, depot(depot, "OFL", 1, 0.0),
                throat, depotThroat(throat, "OFL", 1, "001", 10.0),
                entry, switcher(entry, 20.0),
                current, switcher(current, 30.0),
                oflMlu3, interval(oflMlu3, "OFL", "MLU", 1, "003", 40.0),
                inner, switcher(inner, 50.0),
                exit, switcher(exit, 60.0),
                oflMlu2, interval(oflMlu2, "OFL", "MLU", 1, "002", 70.0),
                oflMlu1, interval(oflMlu1, "OFL", "MLU", 1, "001", 80.0),
                station, station(station, "OFL", 1, 90.0)),
            Map.of(
                edgeDepotThroat, edge(depot, throat, edgeDepotThroat),
                edgeThroatEntry, edge(throat, entry, edgeThroatEntry),
                edgeEntryCurrent, edge(entry, current, edgeEntryCurrent),
                edgeCurrentInterval, edge(current, oflMlu3, edgeCurrentInterval),
                edgeIntervalInner, edge(oflMlu3, inner, edgeIntervalInner),
                edgeInnerExit, edge(inner, exit, edgeInnerExit),
                edgeExitInterval, edge(exit, oflMlu2, edgeExitInterval),
                edgeInterval2Interval1, edge(oflMlu2, oflMlu1, edgeInterval2Interval1),
                edgeIntervalStation, edge(oflMlu1, station, edgeIntervalStation)),
            Set.of());
    String conflict =
        "single:SURC:CGL:WYB:1:001:SWITCHER:Towny:-516:77:2236~SWITCHER:Towny:-516:77:2268";
    RailGraph graph =
        new CorridorInfoOverrideGraph(
            delegate,
            Map.of(
                edgeCurrentInterval,
                new RailGraphCorridorInfo(
                    conflict, inner, current, List.of(inner, oflMlu3, current), false)));
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 12, 0, 0, 0);

    OccupancyRequest depotRequest =
        builder
            .buildContextFromNodes(
                "SURC-MT-LP-9119",
                Optional.of(RouteId.of("SURC:MT:MT-1N_Short")),
                List.of(depot, station),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow()
            .request();
    OccupancyRequest currentRequest =
        builder
            .buildContextFromNodes(
                "SURC-MT-LP-9119",
                Optional.of(RouteId.of("SURC:MT:MT-1N_Short")),
                List.of(current, station),
                0,
                Instant.parse("2026-01-01T00:00:01Z"),
                0)
            .orElseThrow()
            .request();

    assertTrue(depotRequest.resourceList().contains(OccupancyResource.forConflict(conflict)));
    assertTrue(currentRequest.resourceList().contains(OccupancyResource.forConflict(conflict)));
    assertEquals(
        depotRequest.corridorDirections().get(conflict),
        currentRequest.corridorDirections().get(conflict));
  }

  @Test
  void terminalSectionDirectionStaysStableAfterApproachAnchorFallsBehind() {
    NodeId cgl = NodeId.of("SURC:S:CGL:2");
    NodeId interval = NodeId.of("SURC:CGL:WYB:2:007");
    NodeId current = NodeId.of("SWITCHER:Towny:-520:77:4131");
    NodeId innerOne = NodeId.of("SWITCHER:Towny:-518:77:4138");
    NodeId innerTwo = NodeId.of("SWITCHER:Towny:-518:77:4145");
    NodeId terminalSwitcher = NodeId.of("SWITCHER:Towny:-516:77:4152");
    NodeId wyb = NodeId.of("SURC:S:WYB:1");
    List<NodeId> physicalPath =
        List.of(cgl, interval, current, innerOne, innerTwo, terminalSwitcher, wyb);
    Map<NodeId, RailNode> nodes =
        Map.of(
            cgl, station(cgl, "CGL", 2, 0.0),
            interval, interval(interval, "CGL", "WYB", 2, "007", 10.0),
            current, switcher(current, 20.0),
            innerOne, switcher(innerOne, 30.0),
            innerTwo, switcher(innerTwo, 40.0),
            terminalSwitcher, switcher(terminalSwitcher, 50.0),
            wyb, station(wyb, "WYB", 1, 60.0));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    for (int index = 0; index + 1 < physicalPath.size(); index++) {
      NodeId from = physicalPath.get(index);
      NodeId to = physicalPath.get(index + 1);
      EdgeId edgeId = EdgeId.undirected(from, to);
      edges.put(edgeId, edge(from, to, edgeId));
    }
    String conflict = "single:SURC:CGL:WYB:2:007:SWITCHER:Towny:-516:77:4152~SURC:S:WYB:1";
    EdgeId terminalEdge = EdgeId.undirected(terminalSwitcher, wyb);
    RailGraph graph =
        new CorridorInfoOverrideGraph(
            new SimpleRailGraph(nodes, edges, Set.of()),
            Map.of(
                terminalEdge,
                new RailGraphCorridorInfo(
                    conflict, wyb, terminalSwitcher, List.of(wyb, terminalSwitcher), false)));
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 12, 0, 0, 0);

    OccupancyRequest canonical =
        builder
            .buildContextFromNodes(
                "SURC-DS-LW-8443",
                Optional.of(RouteId.of("SURC:DS:DS-1F_Full")),
                List.of(cgl, wyb),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0)
            .orElseThrow()
            .request();
    OccupancyRequest liveSuffix =
        builder
            .buildContextFromNodesWithDirectionContext(
                "SURC-DS-LW-8443",
                Optional.of(RouteId.of("SURC:DS:DS-1F_Full")),
                List.of(current, wyb),
                List.of(cgl, wyb),
                0,
                Instant.parse("2026-01-01T00:00:01Z"),
                0,
                AuthorizationPurpose.RUNTIME_MOVE)
            .orElseThrow()
            .request();

    assertEquals(CorridorDirection.A_TO_B, canonical.corridorDirections().get(conflict));
    assertEquals(
        canonical.corridorDirections().get(conflict),
        liveSuffix.corridorDirections().get(conflict));
  }

  @Test
  void currentPositionRetainCannotReverseExistingSpawnAuthorityClaim() {
    NodeId depot = NodeId.of("SURC:D:HHU:3");
    NodeId switcher = NodeId.of("SWITCHER:Towny:502:74:996");
    NodeId hhu = NodeId.of("SURC:S:HHU:1");
    NodeId zkwHhu = NodeId.of("SURC:ZKW:HHU:1:006");
    NodeId zkw = NodeId.of("SURC:S:ZKW:1");
    EdgeId edgeDepotSwitcher = EdgeId.undirected(depot, switcher);
    EdgeId edgeSwitcherHhu = EdgeId.undirected(switcher, hhu);
    EdgeId edgeHhuInterval = EdgeId.undirected(hhu, zkwHhu);
    EdgeId edgeIntervalZkw = EdgeId.undirected(zkwHhu, zkw);
    SimpleRailGraph delegate =
        new SimpleRailGraph(
            Map.of(
                depot, depot(depot, "HHU", 3, 0.0),
                switcher, switcher(switcher, 10.0),
                hhu, station(hhu, "HHU", 1, 20.0),
                zkwHhu, interval(zkwHhu, "ZKW", "HHU", 1, "006", 30.0),
                zkw, station(zkw, "ZKW", 1, 40.0)),
            Map.of(
                edgeDepotSwitcher, edge(depot, switcher, edgeDepotSwitcher),
                edgeSwitcherHhu, edge(switcher, hhu, edgeSwitcherHhu),
                edgeHhuInterval, edge(hhu, zkwHhu, edgeHhuInterval),
                edgeIntervalZkw, edge(zkwHhu, zkw, edgeIntervalZkw)),
            Set.of());
    String conflict = "single:SURC:CGL:WYB:1:001:SURC:D:HHU:3~SWITCHER:Towny:502:74:996";
    RailGraph graph =
        new CorridorInfoOverrideGraph(
            delegate,
            Map.of(
                edgeDepotSwitcher,
                new RailGraphCorridorInfo(
                    conflict, depot, switcher, List.of(depot, switcher), false)));
    OccupancyRequestBuilder builder = new OccupancyRequestBuilder(graph, 8, 0, 0, 0);
    List<NodeId> routeNodes = List.of(depot, hhu, zkw);

    OccupancyRequest spawnRequest =
        builder
            .buildContextFromNodes(
                "SURC-DS-LW-1581",
                Optional.of(RouteId.of("SURC:DS:DS-1F_Full")),
                routeNodes,
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                10,
                AuthorizationPurpose.DEPOT_SPAWN)
            .orElseThrow()
            .request();
    OccupancyRequest currentPositionRequest =
        builder.buildCurrentPositionRequestFromPlan(
            "SURC-DS-LW-1581",
            Optional.of(RouteId.of("SURC:DS:DS-1F_Full")),
            depot,
            Optional.of(hhu),
            Instant.parse("2026-01-01T00:00:01Z"),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            spawnRequest.movementPlanSnapshot().orElseThrow());
    OccupancyRequest movementRequest =
        builder
            .buildContextFromNodes(
                "SURC-DS-LW-1581",
                Optional.of(RouteId.of("SURC:DS:DS-1F_Full")),
                routeNodes,
                0,
                Instant.parse("2026-01-01T00:00:02Z"),
                10,
                AuthorizationPurpose.RUNTIME_MOVE)
            .orElseThrow()
            .request();
    OccupancyResource conflictResource = OccupancyResource.forConflict(conflict);
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> java.time.Duration.ZERO, SignalAspectPolicy.defaultPolicy());

    assertTrue(manager.acquire(spawnRequest).allowed());
    assertEquals(
        spawnRequest.directedContext().orElseThrow().switcherPathSignatures(),
        currentPositionRequest.directedContext().orElseThrow().switcherPathSignatures());
    assertTrue(manager.acquire(currentPositionRequest).allowed());
    long versionAfterCurrentPosition = manager.version();
    assertTrue(manager.acquire(currentPositionRequest).allowed());

    OccupancyClaim retainedClaim = manager.getClaim(conflictResource).orElseThrow();
    OccupancyDecision departure = manager.canEnter(movementRequest);
    assertTrue(departure.allowed(), () -> "当前位置保护不应反向覆盖出库授权: " + departure);
    assertEquals(versionAfterCurrentPosition, manager.version(), "同值保护刷新不应推进 occupancy version");
    assertEquals(
        spawnRequest.corridorDirections().get(conflict),
        currentPositionRequest.corridorDirections().get(conflict));
    assertEquals(
        spawnRequest.movementPlanSnapshot().orElseThrow().expandedPathNodes(),
        currentPositionRequest.movementPlanSnapshot().orElseThrow().expandedPathNodes());
    assertEquals(ClaimRole.MOVEMENT_REQUIRED, retainedClaim.role());
    assertEquals(
        Optional.of(spawnRequest.corridorDirections().get(conflict)),
        retainedClaim.corridorDirection());
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

  @Test
  void shortLookaheadPromotesSelectedThroatPathThroughClearanceExit() {
    NodeId station = NodeId.of("SURC:S:TERM:1");
    NodeId throat = NodeId.of("SURC:S:TERM:1:001");
    NodeId switcher = NodeId.of("SWITCHER:Towny:1:64:1");
    NodeId clearance = NodeId.of("SURC:TERM:NEXT:1:001");
    NodeId nextStation = NodeId.of("SURC:S:NEXT:1");
    Map<NodeId, RailNode> nodes =
        Map.of(
            station, station(station, "TERM", 1, 0.0),
            throat, stationThroat(throat, "TERM", 1, "001", 1.0),
            switcher, switcher(switcher, 2.0),
            clearance, interval(clearance, "TERM", "NEXT", 1, "001", 3.0),
            nextStation, station(nextStation, "NEXT", 1, 4.0));
    EdgeId stationThroat = EdgeId.undirected(station, throat);
    EdgeId throatSwitcher = EdgeId.undirected(throat, switcher);
    EdgeId switcherClearance = EdgeId.undirected(switcher, clearance);
    EdgeId clearanceStation = EdgeId.undirected(clearance, nextStation);
    SimpleRailGraph graph =
        new SimpleRailGraph(
            nodes,
            Map.of(
                stationThroat, edge(station, throat, stationThroat),
                throatSwitcher, edge(throat, switcher, throatSwitcher),
                switcherClearance, edge(switcher, clearance, switcherClearance),
                clearanceStation, edge(clearance, nextStation, clearanceStation)),
            Set.of());

    OccupancyRequestContext context =
        new OccupancyRequestBuilder(graph, 1, 0, 0, 0)
            .buildContextFromNodes(
                "Train-1",
                Optional.of(RouteId.of("SURC:LINE:OUTBOUND")),
                List.of(station, nextStation),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0,
                AuthorizationPurpose.STATION_DEPARTURE)
            .orElseThrow();

    assertEquals(List.of(station, throat, switcher, clearance), context.pathNodes());
    assertTrue(
        context.request().resourceList().contains(OccupancyResource.forEdge(throatSwitcher)));
    assertTrue(
        context.request().resourceList().contains(OccupancyResource.forEdge(switcherClearance)));
    assertEquals(
        ResourceIntent.MOVEMENT_REQUIRED,
        context.request().intentFor(OccupancyResource.forNode(clearance)));
    assertFalse(context.request().resourceList().contains(OccupancyResource.forNode(nextStation)));
  }

  @Test
  void remoteInterlockingDoesNotExpandBeyondConfiguredHardLookahead() {
    NodeId station = NodeId.of("SURC:S:SCC:1");
    NodeId approachTwo = NodeId.of("SURC:WSD:SCC:1:002");
    NodeId approachOne = NodeId.of("SURC:WSD:SCC:1:001");
    NodeId crossing = NodeId.of("SWITCHER:Towny:-262:74:1117");
    NodeId clearance = NodeId.of("SURC:WSD:SCC:1:000");
    NodeId nextStation = NodeId.of("SURC:S:WSD:2");
    Map<NodeId, RailNode> nodes =
        Map.of(
            station, station(station, "SCC", 1, 0.0),
            approachTwo, interval(approachTwo, "WSD", "SCC", 1, "002", 1.0),
            approachOne, interval(approachOne, "WSD", "SCC", 1, "001", 2.0),
            crossing, switcher(crossing, 3.0),
            clearance, interval(clearance, "WSD", "SCC", 1, "000", 4.0),
            nextStation, station(nextStation, "WSD", 2, 5.0));
    List<NodeId> path =
        List.of(station, approachTwo, approachOne, crossing, clearance, nextStation);
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    for (int index = 0; index + 1 < path.size(); index++) {
      NodeId from = path.get(index);
      NodeId to = path.get(index + 1);
      EdgeId edgeId = EdgeId.undirected(from, to);
      edges.put(edgeId, edge(from, to, edgeId));
    }
    SimpleRailGraph graph = new SimpleRailGraph(nodes, edges, Set.of());

    OccupancyRequestContext context =
        new OccupancyRequestBuilder(graph, 1, 0, 0, 0)
            .buildContextFromNodes(
                "Train-1",
                Optional.of(RouteId.of("SURC:MT:TURNBACK")),
                List.of(station, nextStation),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0,
                AuthorizationPurpose.LAYOVER_REUSE)
            .orElseThrow();

    assertEquals(List.of(station, approachTwo), context.pathNodes());
    assertFalse(context.request().resourceList().contains(OccupancyResource.forNode(crossing)));
    assertFalse(context.request().resourceList().contains(OccupancyResource.forNode(clearance)));
  }

  @Test
  void approachingTrainReservesSwitcherThroughNextGraphBoundary() {
    NodeId approach = NodeId.of("SURC:WSD:SCC:1:001");
    NodeId crossing = NodeId.of("SWITCHER:Towny:-262:74:1117");
    NodeId clearance = NodeId.of("SURC:WSD:SCC:1:000");
    NodeId station = NodeId.of("SURC:S:WSD:2");
    List<NodeId> path = List.of(approach, crossing, clearance, station);
    Map<NodeId, RailNode> nodes =
        Map.of(
            approach, interval(approach, "WSD", "SCC", 1, "001", 0.0),
            crossing, switcher(crossing, 1.0),
            clearance, interval(clearance, "WSD", "SCC", 1, "000", 2.0),
            station, station(station, "WSD", 2, 3.0));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    for (int index = 0; index + 1 < path.size(); index++) {
      NodeId from = path.get(index);
      NodeId to = path.get(index + 1);
      EdgeId edgeId = EdgeId.undirected(from, to);
      edges.put(edgeId, edge(from, to, edgeId));
    }

    OccupancyRequestContext context =
        new OccupancyRequestBuilder(new SimpleRailGraph(nodes, edges, Set.of()), 1, 0, 0, 0)
            .buildContextFromNodes(
                "Train-2",
                Optional.of(RouteId.of("SURC:DS:APPROACH")),
                List.of(approach, station),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0,
                AuthorizationPurpose.RUNTIME_MOVE)
            .orElseThrow();

    assertEquals(List.of(approach, crossing, clearance), context.pathNodes());
    assertEquals(
        ResourceIntent.MOVEMENT_REQUIRED,
        context.request().intentFor(OccupancyResource.forNode(clearance)));
  }

  @Test
  void throatWithoutVisibleClearanceExitFailsClosed() {
    NodeId station = NodeId.of("SURC:S:TERM:1");
    NodeId throat = NodeId.of("SURC:S:TERM:1:001");
    NodeId switcher = NodeId.of("SWITCHER:Towny:1:64:1");
    EdgeId stationThroat = EdgeId.undirected(station, throat);
    EdgeId throatSwitcher = EdgeId.undirected(throat, switcher);
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(
                station, station(station, "TERM", 1, 0.0),
                throat, stationThroat(throat, "TERM", 1, "001", 1.0),
                switcher, switcher(switcher, 2.0)),
            Map.of(
                stationThroat, edge(station, throat, stationThroat),
                throatSwitcher, edge(throat, switcher, throatSwitcher)),
            Set.of());

    Optional<OccupancyRequestContext> context =
        new OccupancyRequestBuilder(graph, 1, 0, 0, 0)
            .buildContextFromNodes(
                "Train-1",
                Optional.of(RouteId.of("SURC:LINE:BROKEN")),
                List.of(station, switcher),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0,
                AuthorizationPurpose.STATION_DEPARTURE);

    assertTrue(context.isEmpty());
  }

  @Test
  void shortLookaheadPromotesConflictExitIntoTheSameHardAuthority() {
    NodeId entry = NodeId.of("ENTRY");
    NodeId inside = NodeId.of("INSIDE");
    NodeId exit = NodeId.of("EXIT");
    NodeId clearBerth = NodeId.of("CLEAR");
    EdgeId entryEdge = EdgeId.undirected(entry, inside);
    EdgeId insideEdge = EdgeId.undirected(inside, exit);
    EdgeId clearanceEdge = EdgeId.undirected(exit, clearBerth);
    SimpleRailGraph delegate =
        new SimpleRailGraph(
            Map.of(
                entry, waypoint(entry, 0.0),
                inside, waypoint(inside, 10.0),
                exit, waypoint(exit, 20.0),
                clearBerth, waypoint(clearBerth, 30.0)),
            Map.of(
                entryEdge, edge(entry, inside, entryEdge),
                insideEdge, edge(inside, exit, insideEdge),
                clearanceEdge, edge(exit, clearBerth, clearanceEdge)),
            Set.of());
    String conflictKey = "interlocking:test:entry-exit";
    RailGraph graph =
        new ConflictOnlyGraph(delegate, Map.of(entryEdge, conflictKey, insideEdge, conflictKey));

    OccupancyRequestContext context =
        new OccupancyRequestBuilder(graph, 1, 0, 0, 0)
            .buildContextFromNodes(
                "Train-1",
                Optional.of(RouteId.of("OP:LINE:ROUTE")),
                List.of(entry, clearBerth),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0,
                AuthorizationPurpose.RUNTIME_MOVE)
            .orElseThrow();

    assertEquals(List.of(entry, inside, exit, clearBerth), context.pathNodes());
    assertEquals(3, context.edges().size());
    assertEquals(
        ResourceIntent.MOVEMENT_REQUIRED,
        context.request().intentFor(OccupancyResource.forEdge(clearanceEdge)));
    assertEquals(
        ResourceIntent.MOVEMENT_REQUIRED,
        context.request().intentFor(OccupancyResource.forNode(clearBerth)));
    assertEquals(
        ResourceIntent.MOVEMENT_REQUIRED,
        context.request().intentFor(OccupancyResource.forConflict(conflictKey)));
  }

  @Test
  void physicalInterlockingExitBerthCoversTrainLengthBeyondTheConflict() {
    NodeId entry = NodeId.of("ENTRY");
    NodeId inside = NodeId.of("INSIDE");
    NodeId exit = NodeId.of("EXIT");
    NodeId shortBerth = NodeId.of("SHORT_BERTH");
    NodeId fullBerth = NodeId.of("FULL_BERTH");
    EdgeId entryEdge = EdgeId.undirected(entry, inside);
    EdgeId insideEdge = EdgeId.undirected(inside, exit);
    EdgeId firstClearanceEdge = EdgeId.undirected(exit, shortBerth);
    EdgeId secondClearanceEdge = EdgeId.undirected(shortBerth, fullBerth);
    SimpleRailGraph delegate =
        new SimpleRailGraph(
            Map.of(
                entry, waypoint(entry, 0.0),
                inside, waypoint(inside, 10.0),
                exit, waypoint(exit, 20.0),
                shortBerth, waypoint(shortBerth, 30.0),
                fullBerth, waypoint(fullBerth, 40.0)),
            Map.of(
                entryEdge, edge(entry, inside, entryEdge),
                insideEdge, edge(inside, exit, insideEdge),
                firstClearanceEdge, edge(exit, shortBerth, firstClearanceEdge),
                secondClearanceEdge, edge(shortBerth, fullBerth, secondClearanceEdge)),
            Set.of());
    String conflictKey = "interlocking:test:long-train-exit";
    RailGraph graph =
        new ConflictOnlyGraph(delegate, Map.of(entryEdge, conflictKey, insideEdge, conflictKey));

    OccupancyRequestContext context =
        new OccupancyRequestBuilder(graph, 1, 0, 0, 0)
            .withMinimumConflictExitDistanceBlocks(15L)
            .buildContextFromNodes(
                "Long-Train",
                Optional.of(RouteId.of("OP:LINE:LONG")),
                List.of(entry, fullBerth),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0,
                AuthorizationPurpose.RUNTIME_MOVE)
            .orElseThrow();

    assertEquals(List.of(entry, inside, exit, shortBerth, fullBerth), context.pathNodes());
    assertTrue(
        context.request().resourceList().contains(OccupancyResource.forEdge(secondClearanceEdge)));
    assertEquals(
        ResourceIntent.MOVEMENT_REQUIRED,
        context.request().intentFor(OccupancyResource.forNode(fullBerth)));
  }

  @Test
  void physicalInterlockingExitBerthFailsClosedWhenRouteCannotProveEnoughDistance() {
    NodeId entry = NodeId.of("ENTRY");
    NodeId inside = NodeId.of("INSIDE");
    NodeId exit = NodeId.of("EXIT");
    NodeId shortBerth = NodeId.of("SHORT_BERTH");
    EdgeId entryEdge = EdgeId.undirected(entry, inside);
    EdgeId insideEdge = EdgeId.undirected(inside, exit);
    EdgeId clearanceEdge = EdgeId.undirected(exit, shortBerth);
    SimpleRailGraph delegate =
        new SimpleRailGraph(
            Map.of(
                entry, waypoint(entry, 0.0),
                inside, waypoint(inside, 10.0),
                exit, waypoint(exit, 20.0),
                shortBerth, waypoint(shortBerth, 30.0)),
            Map.of(
                entryEdge, edge(entry, inside, entryEdge),
                insideEdge, edge(inside, exit, insideEdge),
                clearanceEdge, edge(exit, shortBerth, clearanceEdge)),
            Set.of());
    String conflictKey = "interlocking:test:short-exit";
    RailGraph graph =
        new ConflictOnlyGraph(delegate, Map.of(entryEdge, conflictKey, insideEdge, conflictKey));

    Optional<OccupancyRequestContext> context =
        new OccupancyRequestBuilder(graph, 1, 0, 0, 0)
            .withMinimumConflictExitDistanceBlocks(15L)
            .buildContextFromNodes(
                "Long-Train",
                Optional.of(RouteId.of("OP:LINE:SHORT")),
                List.of(entry, shortBerth),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0,
                AuthorizationPurpose.RUNTIME_MOVE);

    assertTrue(context.isEmpty());
  }

  @Test
  void shortLookaheadDoesNotPromoteDirectionalSingleSectionToWholeSection() {
    NodeId entry = NodeId.of("ENTRY");
    NodeId inside = NodeId.of("INSIDE");
    NodeId exit = NodeId.of("EXIT");
    NodeId clearBerth = NodeId.of("CLEAR");
    EdgeId entryEdge = EdgeId.undirected(entry, inside);
    EdgeId insideEdge = EdgeId.undirected(inside, exit);
    EdgeId clearanceEdge = EdgeId.undirected(exit, clearBerth);
    SimpleRailGraph delegate =
        new SimpleRailGraph(
            Map.of(
                entry, waypoint(entry, 0.0),
                inside, waypoint(inside, 10.0),
                exit, waypoint(exit, 20.0),
                clearBerth, waypoint(clearBerth, 30.0)),
            Map.of(
                entryEdge, edge(entry, inside, entryEdge),
                insideEdge, edge(inside, exit, insideEdge),
                clearanceEdge, edge(exit, clearBerth, clearanceEdge)),
            Set.of());
    String conflictKey = "single:test:entry-exit";
    RailGraph graph =
        new ConflictOnlyGraph(delegate, Map.of(entryEdge, conflictKey, insideEdge, conflictKey));

    OccupancyRequestContext context =
        new OccupancyRequestBuilder(graph, 1, 0, 0, 0)
            .buildContextFromNodes(
                "Train-1",
                Optional.of(RouteId.of("OP:LINE:ROUTE")),
                List.of(entry, clearBerth),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0,
                AuthorizationPurpose.RUNTIME_MOVE)
            .orElseThrow();

    assertEquals(List.of(entry, inside), context.pathNodes());
    assertEquals(1, context.edges().size());
    assertTrue(
        context.request().resourceList().contains(OccupancyResource.forConflict(conflictKey)));
    assertFalse(context.request().resourceList().contains(OccupancyResource.forEdge(insideEdge)));
    assertFalse(
        context.request().resourceList().contains(OccupancyResource.forEdge(clearanceEdge)));
  }

  @Test
  void physicalInterlockingWithoutVisibleClearanceEdgeFailsClosed() {
    NodeId entry = NodeId.of("ENTRY");
    NodeId inside = NodeId.of("INSIDE");
    NodeId exit = NodeId.of("EXIT");
    EdgeId entryEdge = EdgeId.undirected(entry, inside);
    EdgeId insideEdge = EdgeId.undirected(inside, exit);
    SimpleRailGraph delegate =
        new SimpleRailGraph(
            Map.of(
                entry, waypoint(entry, 0.0),
                inside, waypoint(inside, 10.0),
                exit, waypoint(exit, 20.0)),
            Map.of(
                entryEdge, edge(entry, inside, entryEdge),
                insideEdge, edge(inside, exit, insideEdge)),
            Set.of());
    String conflictKey = "interlocking:test:no-clearance";
    RailGraph graph =
        new ConflictOnlyGraph(delegate, Map.of(entryEdge, conflictKey, insideEdge, conflictKey));

    Optional<OccupancyRequestContext> context =
        new OccupancyRequestBuilder(graph, 1, 0, 0, 0)
            .buildContextFromNodes(
                "Train-1",
                Optional.of(RouteId.of("OP:LINE:BROKEN")),
                List.of(entry, exit),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0,
                AuthorizationPurpose.RUNTIME_MOVE);

    assertTrue(context.isEmpty());
  }

  @Test
  void occupiedConflictExitBlocksFreshAdmission() {
    NodeId entry = NodeId.of("ENTRY");
    NodeId inside = NodeId.of("INSIDE");
    NodeId exit = NodeId.of("EXIT");
    NodeId clearBerth = NodeId.of("CLEAR");
    EdgeId entryEdge = EdgeId.undirected(entry, inside);
    EdgeId insideEdge = EdgeId.undirected(inside, exit);
    EdgeId clearanceEdge = EdgeId.undirected(exit, clearBerth);
    SimpleRailGraph delegate =
        new SimpleRailGraph(
            Map.of(
                entry, waypoint(entry, 0.0),
                inside, waypoint(inside, 10.0),
                exit, waypoint(exit, 20.0),
                clearBerth, waypoint(clearBerth, 30.0)),
            Map.of(
                entryEdge, edge(entry, inside, entryEdge),
                insideEdge, edge(inside, exit, insideEdge),
                clearanceEdge, edge(exit, clearBerth, clearanceEdge)),
            Set.of());
    RailGraph graph =
        new ConflictOnlyGraph(
            delegate,
            Map.of(
                entryEdge,
                "interlocking:test:entry-exit",
                insideEdge,
                "interlocking:test:entry-exit"));
    OccupancyRequestContext context =
        new OccupancyRequestBuilder(graph, 1, 0, 0, 0)
            .buildContextFromNodes(
                "candidate",
                Optional.of(RouteId.of("OP:LINE:ROUTE")),
                List.of(entry, clearBerth),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                0,
                AuthorizationPurpose.RUNTIME_MOVE)
            .orElseThrow();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> java.time.Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyRequest exitOccupant =
        new OccupancyRequest(
            "exit-owner",
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:00Z"),
            List.of(
                OccupancyResource.forEdge(clearanceEdge), OccupancyResource.forNode(clearBerth)),
            Map.of(),
            0);

    assertTrue(manager.acquire(exitOccupant).allowed());
    OccupancyDecision decision = manager.canEnter(context.request());

    assertFalse(decision.allowed());
    assertTrue(
        decision.blockers().stream().anyMatch(claim -> claim.trainName().equals("exit-owner")));
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

  private static SimpleRailGraph linearGraph(List<NodeId> pathNodes) {
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    for (int index = 0; index < pathNodes.size(); index++) {
      NodeId node = pathNodes.get(index);
      nodes.put(node, waypoint(node, index * 10.0));
      if (index == 0) {
        continue;
      }
      NodeId previous = pathNodes.get(index - 1);
      EdgeId edgeId = EdgeId.undirected(previous, node);
      edges.put(edgeId, new RailEdge(edgeId, previous, node, 10, 8.0, true, Optional.empty()));
    }
    return new SimpleRailGraph(nodes, edges, Set.of());
  }

  private static MovementPlanSnapshot movementPlanWithDirections(
      String trainName,
      RouteId routeId,
      NodeId from,
      NodeId to,
      EdgeId edgeId,
      Map<String, CorridorDirection> directions) {
    DirectedTraversalContext.DirectedEdge directedEdge =
        new DirectedTraversalContext.DirectedEdge(edgeId, from, to);
    return new MovementPlanSnapshot(
        trainName,
        Optional.of(routeId),
        1,
        Optional.of(from),
        Optional.empty(),
        Optional.of(from),
        Optional.of(to),
        new ExpandedPathPlan(List.of(from, to), List.of(directedEdge), directions, Map.of()),
        directions.keySet().stream().map(OccupancyResource::forConflict).toList(),
        1L,
        1L,
        "canonical-plan");
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

  private static final class CountingCorridorGraph implements RailGraph, RailGraphCorridorSupport {

    private final SimpleRailGraph delegate;
    private int findNodeCalls;

    private CountingCorridorGraph(SimpleRailGraph delegate) {
      this.delegate = delegate;
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
      findNodeCalls++;
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
      return delegate.conflictKeyForEdge(edgeId);
    }

    @Override
    public Optional<RailGraphCorridorInfo> corridorInfoForEdge(EdgeId edgeId) {
      return delegate.corridorInfoForEdge(edgeId);
    }

    private int findNodeCalls() {
      return findNodeCalls;
    }
  }

  private static final class ConflictOnlyGraph implements RailGraph, RailGraphConflictSupport {

    private final SimpleRailGraph delegate;
    private final Map<EdgeId, String> conflicts;

    private ConflictOnlyGraph(SimpleRailGraph delegate, Map<EdgeId, String> conflicts) {
      this.delegate = delegate;
      this.conflicts = conflicts == null ? Map.of() : Map.copyOf(conflicts);
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
      if (edgeId == null) {
        return Optional.empty();
      }
      String conflict = conflicts.get(edgeId);
      if (conflict == null) {
        conflict = conflicts.get(EdgeId.undirected(edgeId.a(), edgeId.b()));
      }
      return Optional.ofNullable(conflict);
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
