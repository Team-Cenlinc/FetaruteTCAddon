package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.company.model.RouteStop;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DynamicPlatformAllocatorTest {

  private RouteDefinitionCache routeDefinitions;
  private OccupancyManager occupancyManager;
  private RailGraph graph;
  private DynamicPlatformAllocator allocator;

  @BeforeEach
  void setUp() {
    routeDefinitions = mock(RouteDefinitionCache.class);
    occupancyManager = mock(OccupancyManager.class);
    graph = mock(RailGraph.class);
    allocator =
        new DynamicPlatformAllocator(routeDefinitions, occupancyManager, System.out::println);
  }

  @Test
  void testSelectBestCandidateByDirection_StraightVsReverse() {
    // Setup Nodes
    NodeId prevId = NodeId.of("OP:W:PREV:1:0");
    NodeId fromId = NodeId.of("OP:W:FROM:1:0");
    NodeId swId = NodeId.of("OP:S:SW:1");
    // 这里刻意让“正确直行”不是 track=1，以验证方向优选能覆盖 track 顺序。
    NodeId targetAId = NodeId.of("OP:S:DEST:2"); // Straight
    NodeId targetBId = NodeId.of("OP:S:DEST:1"); // Backwards

    // Direction: (0,0,-10) -> (0,0,0) => (0,0,1)
    RailNode prevNode = mockNode(prevId, new Vector(0, 0, -10), NodeType.WAYPOINT);
    RailNode fromNode = mockNode(fromId, new Vector(0, 0, 0), NodeType.WAYPOINT);

    // Switcher at (0,0,10)
    RailNode swNode = mockNode(swId, new Vector(0, 0, 10), NodeType.SWITCHER);

    // Target A at (0,0,20) - Straight ahead
    RailNode targetA = mockNode(targetAId, new Vector(0, 0, 20), NodeType.STATION);

    // Target B at (0,0,-20) - Behind (requires loop or reverse)
    // We simulate connectivity via SW.
    // Path: FROM -> SW -> TARGET_B
    // SW -> TARGET_B vector: (0,0,-20) - (0,0,10) = (0,0,-30) -> (0,0,-1)
    // Direction dot product: (0,0,1) . (0,0,-1) = -1
    RailNode targetB = mockNode(targetBId, new Vector(0, 0, -20), NodeType.STATION);

    // Mock graph connectivity
    mockEdges(prevId, edge(prevNode, fromNode));
    mockEdges(fromId, edge(fromNode, swNode));
    mockEdges(swId, edge(swNode, targetA), edge(swNode, targetB));

    // Targets have no outgoing edges for this test
    mockEdges(targetAId);
    mockEdges(targetBId);

    // Mock graph.nodes()
    when(graph.nodes()).thenReturn(Arrays.asList(prevNode, fromNode, swNode, targetA, targetB));

    // Setup Route
    RouteId routeId = RouteId.of("TEST");
    RouteStop stop = mock(RouteStop.class);
    when(stop.notes()).thenReturn(Optional.of("DYNAMIC:OP:S:DEST:[1:2]"));

    RouteDefinition route = mock(RouteDefinition.class);
    when(route.id()).thenReturn(routeId);
    when(route.waypoints()).thenReturn(Arrays.asList(prevId, fromId, NodeId.of("PLACEHOLDER")));

    // Next stop is index 2 (targetIndex inside tryAllocate loop: currentIndex + 1 = 2)
    when(routeDefinitions.findStop(routeId, 2)).thenReturn(Optional.of(stop));

    // Act
    // currentIndex = 1 (at FROM node)
    Optional<DynamicPlatformAllocator.AllocationResult> result =
        allocator.tryAllocate("train1", route, 1, graph, fromId);

    // Assert
    assertTrue(result.isPresent(), "Should allocate a platform");
    assertEquals(
        targetAId,
        result.get().allocatedNode(),
        "Should select Target A (Straight) even if not track=1");
  }

  @Test
  void testSelectBestCandidateByDirection_XCrossing() {
    // Simulate the scenario from user:
    // X crossing
    // 630 direction (Straight) vs 650 direction (Turn/Loop)

    NodeId prevId = NodeId.of("OP:W:PREV:1:0");
    NodeId fromId = NodeId.of("OP:W:FROM:1:0");

    // Candidate 1: 630 direction (Straight)
    NodeId cand1Id = NodeId.of("OP:S:DEST:1");
    // Candidate 2: 650 direction (Sharp turn)
    NodeId cand2Id = NodeId.of("OP:S:DEST:2");

    // Use consistent coordinates relative to origin
    // Train moving Z+
    RailNode prevNode = mockNode(prevId, new Vector(0, 0, -10), NodeType.WAYPOINT);
    RailNode fromNode = mockNode(fromId, new Vector(0, 0, 0), NodeType.WAYPOINT);
    // Dir: (0, 0, 10) -> (0, 0, 1)

    // Switcher at Z=10
    RailNode swNode = mockNode(NodeId.of("SW"), new Vector(0, 0, 10), NodeType.SWITCHER);

    // Cand1 (Straight ahead) at Z=30
    RailNode cand1 = mockNode(cand1Id, new Vector(0, 0, 30), NodeType.STATION);
    // Guide vector: (0,0,0) -> (0,0,30) = (0,0,1). Dot = 1.0.

    // Cand2 (Side/Turn) at X=20, Z=10
    RailNode cand2 = mockNode(cand2Id, new Vector(20, 0, 10), NodeType.STATION);
    // Guide vector: (0,0,0) -> (20,0,10) = (2,0,1). Normalized approx (0.89, 0, 0.44).
    // Dot = 0.44.

    mockEdges(prevId, edge(prevNode, fromNode));
    mockEdges(fromId, edge(fromNode, swNode));
    mockEdges(swNode.id(), edge(swNode, cand1), edge(swNode, cand2));
    mockEdges(cand1Id);
    mockEdges(cand2Id);

    when(graph.nodes()).thenReturn(Arrays.asList(prevNode, fromNode, swNode, cand1, cand2));

    // Route setup
    RouteId routeId = RouteId.of("TEST2");
    RouteStop stop = mock(RouteStop.class);
    when(stop.notes()).thenReturn(Optional.of("DYNAMIC:OP:S:DEST:[1:2]"));

    RouteDefinition route = mock(RouteDefinition.class);
    when(route.id()).thenReturn(routeId);
    when(route.waypoints()).thenReturn(Arrays.asList(prevId, fromId, NodeId.of("P")));
    when(routeDefinitions.findStop(routeId, 2)).thenReturn(Optional.of(stop));

    Optional<DynamicPlatformAllocator.AllocationResult> result =
        allocator.tryAllocate("train2", route, 1, graph, fromId);

    assertTrue(result.isPresent());
    // Expect cand1 (Z+) because train dir is Z+ (dot=1.0). Cand2 is X+ (dot=0.0).
    assertEquals(cand1Id, result.get().allocatedNode());
  }

  private RailNode mockNode(NodeId id, Vector pos, NodeType type) {
    RailNode node = mock(RailNode.class);
    when(node.id()).thenReturn(id);
    when(node.worldPosition()).thenReturn(pos);
    when(node.type()).thenReturn(type);
    when(graph.findNode(id)).thenReturn(Optional.of(node));
    return node;
  }

  private RailEdge edge(RailNode from, RailNode to) {
    return new RailEdge(
        EdgeId.undirected(from.id(), to.id()), from.id(), to.id(), 10, 1.0, true, Optional.empty());
  }

  private void mockEdges(NodeId id, RailEdge... edges) {
    when(graph.edgesFrom(id)).thenReturn(new HashSet<>(Arrays.asList(edges)));
  }
}
