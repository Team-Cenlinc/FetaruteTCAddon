package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
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
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
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

  @Test
  void dynamicStopPrefersFreeReachablePlatform() {
    NodeId fromId = NodeId.of("OP:W:FROM:1:0");
    NodeId occupiedId = NodeId.of("OP:S:DEST:1");
    NodeId freeId = NodeId.of("OP:S:DEST:2");
    RailNode from = mockNode(fromId, new Vector(0, 0, 0), NodeType.WAYPOINT);
    RailNode occupied = mockNode(occupiedId, new Vector(10, 0, 0), NodeType.STATION);
    RailNode free = mockNode(freeId, new Vector(20, 0, 0), NodeType.STATION);
    mockEdges(fromId, edge(from, occupied), edge(from, free));
    mockEdges(occupiedId);
    mockEdges(freeId);

    RouteId routeId = RouteId.of("FREE-FIRST");
    RouteDefinition route = routeWithDynamicStop(routeId, fromId);
    RouteStop stop = dynamicStop();
    when(routeDefinitions.findStop(routeId, 1)).thenReturn(Optional.of(stop));
    OccupancyResource occupiedResource = OccupancyResource.forNode(occupiedId);
    when(occupancyManager.isNodeOccupied(occupiedId)).thenReturn(true);
    when(occupancyManager.getClaim(occupiedResource))
        .thenReturn(
            Optional.of(
                new OccupancyClaim(
                    occupiedResource,
                    "other-train",
                    Optional.empty(),
                    java.time.Instant.EPOCH,
                    Duration.ZERO,
                    Optional.empty())));

    Optional<DynamicPlatformAllocator.AllocationResult> result =
        allocator.tryAllocate("train-free-first", route, 0, graph, fromId);

    assertTrue(result.isPresent());
    assertEquals(freeId, result.get().allocatedNode());
  }

  @Test
  void dynamicStopDoesNotAllocateOccupiedPlatformWhenAllCandidatesOccupied() {
    NodeId fromId = NodeId.of("OP:W:FROM:1:0");
    NodeId firstId = NodeId.of("OP:S:DEST:1");
    NodeId secondId = NodeId.of("OP:S:DEST:2");
    RailNode from = mockNode(fromId, new Vector(0, 0, 0), NodeType.WAYPOINT);
    RailNode first = mockNode(firstId, new Vector(10, 0, 0), NodeType.STATION);
    RailNode second = mockNode(secondId, new Vector(20, 0, 0), NodeType.STATION);
    mockEdges(fromId, edge(from, first), edge(from, second));
    mockEdges(firstId);
    mockEdges(secondId);

    RouteId routeId = RouteId.of("FALLBACK");
    RouteDefinition route = routeWithDynamicStop(routeId, fromId);
    RouteStop stop = dynamicStop();
    when(routeDefinitions.findStop(routeId, 1)).thenReturn(Optional.of(stop));
    when(occupancyManager.isNodeOccupied(firstId)).thenReturn(true);
    when(occupancyManager.isNodeOccupied(secondId)).thenReturn(true);
    mockOccupied(firstId, "first-train");
    mockOccupied(secondId, "second-train");

    Optional<DynamicPlatformAllocator.AllocationResult> result =
        allocator.tryAllocate("train-fallback", route, 0, graph, fromId);

    assertFalse(result.isPresent());
  }

  @Test
  void dynamicStopSkipsPlatformsReservedByOtherTrains() {
    NodeId fromId = NodeId.of("OP:W:FROM:1:0");
    NodeId firstId = NodeId.of("OP:S:DEST:1");
    NodeId secondId = NodeId.of("OP:S:DEST:2");
    RailNode from = mockNode(fromId, new Vector(0, 0, 0), NodeType.WAYPOINT);
    RailNode first = mockNode(firstId, new Vector(10, 0, 0), NodeType.STATION);
    RailNode second = mockNode(secondId, new Vector(20, 0, 0), NodeType.STATION);
    mockEdges(fromId, edge(from, first), edge(from, second));
    mockEdges(firstId);
    mockEdges(secondId);

    RouteId routeId = RouteId.of("RESERVATION");
    RouteDefinition route = routeWithDynamicStop(routeId, fromId);
    RouteStop stop = dynamicStop();
    when(routeDefinitions.findStop(routeId, 1)).thenReturn(Optional.of(stop));

    Optional<DynamicPlatformAllocator.AllocationResult> firstResult =
        allocator.tryAllocate("train-first", route, 0, graph, fromId);
    Optional<DynamicPlatformAllocator.AllocationResult> secondResult =
        allocator.tryAllocate("train-second", route, 0, graph, fromId);
    Optional<DynamicPlatformAllocator.AllocationResult> thirdResult =
        allocator.tryAllocate("train-third", route, 0, graph, fromId);

    assertTrue(firstResult.isPresent());
    assertEquals(firstId, firstResult.get().allocatedNode());
    assertTrue(secondResult.isPresent());
    assertEquals(secondId, secondResult.get().allocatedNode());
    assertFalse(thirdResult.isPresent(), "两个站台都已被他车预订时必须 fail-closed");
  }

  @Test
  void clearAllocationsReleasesPlatformReservation() {
    NodeId fromId = NodeId.of("OP:W:FROM:1:0");
    NodeId firstId = NodeId.of("OP:S:DEST:1");
    NodeId secondId = NodeId.of("OP:S:DEST:2");
    RailNode from = mockNode(fromId, new Vector(0, 0, 0), NodeType.WAYPOINT);
    RailNode first = mockNode(firstId, new Vector(10, 0, 0), NodeType.STATION);
    RailNode second = mockNode(secondId, new Vector(20, 0, 0), NodeType.STATION);
    mockEdges(fromId, edge(from, first), edge(from, second));
    mockEdges(firstId);
    mockEdges(secondId);

    RouteId routeId = RouteId.of("RESERVATION-CLEAR");
    RouteDefinition route = routeWithDynamicStop(routeId, fromId);
    RouteStop stop = dynamicStop();
    when(routeDefinitions.findStop(routeId, 1)).thenReturn(Optional.of(stop));

    Optional<DynamicPlatformAllocator.AllocationResult> firstResult =
        allocator.tryAllocate("train-first", route, 0, graph, fromId);
    Optional<DynamicPlatformAllocator.AllocationResult> secondResult =
        allocator.tryAllocate("train-second", route, 0, graph, fromId);
    allocator.clearAllocations("train-first");
    Optional<DynamicPlatformAllocator.AllocationResult> thirdResult =
        allocator.tryAllocate("train-third", route, 0, graph, fromId);

    assertTrue(firstResult.isPresent());
    assertEquals(firstId, firstResult.get().allocatedNode());
    assertTrue(secondResult.isPresent());
    assertEquals(secondId, secondResult.get().allocatedNode());
    assertTrue(thirdResult.isPresent());
    assertEquals(firstId, thirdResult.get().allocatedNode());
  }

  @Test
  void completedDynamicStopReleasesReservationForFollowingTrain() {
    NodeId fromId = NodeId.of("OP:W:FROM:1:0");
    NodeId platformId = NodeId.of("OP:S:DEST:1");
    RailNode from = mockNode(fromId, new Vector(0, 0, 0), NodeType.WAYPOINT);
    RailNode platform = mockNode(platformId, new Vector(10, 0, 0), NodeType.STATION);
    mockEdges(fromId, edge(from, platform));
    mockEdges(platformId);

    RouteId routeId = RouteId.of("RESERVATION-COMPLETED");
    RouteDefinition route = routeWithDynamicStop(routeId, fromId);
    RouteStop stop = mock(RouteStop.class);
    when(stop.notes()).thenReturn(Optional.of("DYNAMIC:OP:S:DEST:[1:1]"));
    when(routeDefinitions.findStop(routeId, 1)).thenReturn(Optional.of(stop));

    assertTrue(allocator.tryAllocate("train-first", route, 0, graph, fromId).isPresent());
    assertFalse(allocator.tryAllocate("train-second", route, 0, graph, fromId).isPresent());

    allocator.releaseCompletedAllocations("train-first", routeId, 2);

    Optional<DynamicPlatformAllocator.AllocationResult> following =
        allocator.tryAllocate("train-second", route, 0, graph, fromId);
    assertEquals(platformId, following.orElseThrow().allocatedNode());
  }

  @Test
  void renamedOwnerCanReleaseReservationUnderNewName() {
    NodeId fromId = NodeId.of("OP:W:FROM:1:0");
    NodeId platformId = NodeId.of("OP:S:DEST:1");
    RailNode from = mockNode(fromId, new Vector(0, 0, 0), NodeType.WAYPOINT);
    RailNode platform = mockNode(platformId, new Vector(10, 0, 0), NodeType.STATION);
    mockEdges(fromId, edge(from, platform));
    mockEdges(platformId);

    RouteId routeId = RouteId.of("RESERVATION-RENAME");
    RouteDefinition route = routeWithDynamicStop(routeId, fromId);
    RouteStop stop = mock(RouteStop.class);
    when(stop.notes()).thenReturn(Optional.of("DYNAMIC:OP:S:DEST:[1:1]"));
    when(routeDefinitions.findStop(routeId, 1)).thenReturn(Optional.of(stop));

    assertTrue(allocator.tryAllocate("train-old", route, 0, graph, fromId).isPresent());
    assertTrue(allocator.migrateAllocations("train-old", "train-new"));
    assertEquals(Optional.empty(), allocator.getAllocation("train-old", routeId, 0));
    assertEquals(Optional.of(platformId), allocator.getAllocation("train-new", routeId, 0));

    allocator.releaseCompletedAllocations("train-new", routeId, 2);

    assertEquals(
        platformId,
        allocator
            .tryAllocate("train-following", route, 0, graph, fromId)
            .orElseThrow()
            .allocatedNode());
  }

  @Test
  void cachedDynamicAllocationReselectsWhenExternalClaimAppears() {
    NodeId fromId = NodeId.of("OP:W:FROM:1:0");
    NodeId firstId = NodeId.of("OP:S:DEST:1");
    NodeId secondId = NodeId.of("OP:S:DEST:2");
    RailNode from = mockNode(fromId, new Vector(0, 0, 0), NodeType.WAYPOINT);
    RailNode first = mockNode(firstId, new Vector(10, 0, 0), NodeType.STATION);
    RailNode second = mockNode(secondId, new Vector(20, 0, 0), NodeType.STATION);
    mockEdges(fromId, edge(from, first), edge(from, second));
    mockEdges(firstId);
    mockEdges(secondId);

    RouteId routeId = RouteId.of("CACHE-REVALIDATE");
    RouteDefinition route = routeWithDynamicStop(routeId, fromId);
    RouteStop stop = dynamicStop();
    when(routeDefinitions.findStop(routeId, 1)).thenReturn(Optional.of(stop));

    DynamicPlatformAllocator.AllocationResult initial =
        allocator.tryAllocate("train-cache", route, 0, graph, fromId).orElseThrow();
    assertEquals(firstId, initial.allocatedNode());

    when(occupancyManager.isNodeOccupied(firstId)).thenReturn(true);
    mockOccupied(firstId, "external-train");

    DynamicResolution<DynamicPlatformAllocator.AllocationResult> revalidated =
        allocator.resolveAllocation("train-cache", route, 0, graph, fromId, Optional.empty());

    assertTrue(revalidated.isSelected());
    assertEquals(secondId, revalidated.selected().orElseThrow().allocatedNode());
    assertEquals(Optional.of(secondId), allocator.getAllocation("train-cache", routeId, 0));
  }

  @Test
  void cachedDynamicAllocationBlocksWhenExternalClaimLeavesNoCandidate() {
    NodeId fromId = NodeId.of("OP:W:FROM:1:0");
    NodeId platformId = NodeId.of("OP:S:DEST:1");
    RailNode from = mockNode(fromId, new Vector(0, 0, 0), NodeType.WAYPOINT);
    RailNode platform = mockNode(platformId, new Vector(10, 0, 0), NodeType.STATION);
    mockEdges(fromId, edge(from, platform));
    mockEdges(platformId);

    RouteId routeId = RouteId.of("CACHE-BLOCKED");
    RouteDefinition route = routeWithDynamicStop(routeId, fromId);
    RouteStop stop = dynamicStop();
    when(routeDefinitions.findStop(routeId, 1)).thenReturn(Optional.of(stop));

    assertTrue(allocator.tryAllocate("train-cache", route, 0, graph, fromId).isPresent());

    when(occupancyManager.isNodeOccupied(platformId)).thenReturn(true);
    mockOccupied(platformId, "external-train");

    DynamicResolution<DynamicPlatformAllocator.AllocationResult> revalidated =
        allocator.resolveAllocation("train-cache", route, 0, graph, fromId, Optional.empty());

    assertTrue(revalidated.isBlocked());
    assertEquals("no-available-platform", revalidated.reason());
    assertEquals(Optional.empty(), allocator.getAllocation("train-cache", routeId, 0));
  }

  @Test
  void cachedDynamicAllocationRevalidatesCurrentSpec() {
    NodeId fromId = NodeId.of("OP:W:FROM:1:0");
    NodeId firstId = NodeId.of("OP:S:DEST:1");
    NodeId secondId = NodeId.of("OP:S:DEST:2");
    RailNode from = mockNode(fromId, new Vector(0, 0, 0), NodeType.WAYPOINT);
    RailNode first = mockNode(firstId, new Vector(10, 0, 0), NodeType.STATION);
    RailNode second = mockNode(secondId, new Vector(20, 0, 0), NodeType.STATION);
    mockEdges(fromId, edge(from, first), edge(from, second));
    mockEdges(firstId);
    mockEdges(secondId);

    RouteId routeId = RouteId.of("CACHE-SPEC");
    RouteDefinition route = routeWithDynamicStop(routeId, fromId);
    RouteStop initialStop = mock(RouteStop.class);
    when(initialStop.notes()).thenReturn(Optional.of("DYNAMIC:OP:S:DEST:[1:1]"));
    RouteStop refreshedStop = mock(RouteStop.class);
    when(refreshedStop.notes()).thenReturn(Optional.of("DYNAMIC:OP:S:DEST:[2:2]"));
    java.util.concurrent.atomic.AtomicReference<Optional<RouteStop>> currentStop =
        new java.util.concurrent.atomic.AtomicReference<>(Optional.of(initialStop));
    when(routeDefinitions.findStop(routeId, 1)).thenAnswer(invocation -> currentStop.get());

    assertEquals(
        firstId,
        allocator
            .tryAllocate("train-cache", route, 0, graph, fromId)
            .orElseThrow()
            .allocatedNode());
    currentStop.set(Optional.of(refreshedStop));

    assertEquals(
        secondId,
        allocator
            .resolveAllocation("train-cache", route, 0, graph, fromId, Optional.empty())
            .selected()
            .orElseThrow()
            .allocatedNode());
  }

  @Test
  void removedDynamicDefinitionReleasesCachedReservation() {
    NodeId fromId = NodeId.of("OP:W:FROM:1:0");
    NodeId platformId = NodeId.of("OP:S:DEST:1");
    RailNode from = mockNode(fromId, new Vector(0, 0, 0), NodeType.WAYPOINT);
    RailNode platform = mockNode(platformId, new Vector(10, 0, 0), NodeType.STATION);
    mockEdges(fromId, edge(from, platform));
    mockEdges(platformId);

    RouteId routeId = RouteId.of("CACHE-REMOVED");
    RouteDefinition route = routeWithDynamicStop(routeId, fromId);
    RouteStop stop = mock(RouteStop.class);
    when(stop.notes()).thenReturn(Optional.of("DYNAMIC:OP:S:DEST:[1:1]"));
    java.util.concurrent.atomic.AtomicReference<Optional<RouteStop>> currentStop =
        new java.util.concurrent.atomic.AtomicReference<>(Optional.of(stop));
    when(routeDefinitions.findStop(routeId, 1)).thenAnswer(invocation -> currentStop.get());

    assertTrue(allocator.tryAllocate("train-stale", route, 0, graph, fromId).isPresent());
    currentStop.set(Optional.empty());
    assertFalse(
        allocator
            .resolveAllocation("train-stale", route, 0, graph, fromId, Optional.empty())
            .isSelected());
    assertEquals(Optional.empty(), allocator.getAllocation("train-stale", routeId, 0));

    currentStop.set(Optional.of(stop));
    assertEquals(
        platformId,
        allocator
            .tryAllocate("train-following", route, 0, graph, fromId)
            .orElseThrow()
            .allocatedNode());
  }

  @Test
  void shortenedRouteReleasesCachedReservationBeforeIndexValidation() {
    NodeId fromId = NodeId.of("OP:W:FROM:1:0");
    NodeId platformId = NodeId.of("OP:S:DEST:1");
    RailNode from = mockNode(fromId, new Vector(0, 0, 0), NodeType.WAYPOINT);
    RailNode platform = mockNode(platformId, new Vector(10, 0, 0), NodeType.STATION);
    mockEdges(fromId, edge(from, platform));
    mockEdges(platformId);

    RouteId routeId = RouteId.of("CACHE-SHORTENED");
    RouteDefinition originalRoute = routeWithDynamicStop(routeId, fromId);
    RouteStop stop = mock(RouteStop.class);
    when(stop.notes()).thenReturn(Optional.of("DYNAMIC:OP:S:DEST:[1:1]"));
    when(routeDefinitions.findStop(routeId, 1)).thenReturn(Optional.of(stop));
    assertTrue(allocator.tryAllocate("train-stale", originalRoute, 0, graph, fromId).isPresent());

    RouteDefinition shortenedRoute = mock(RouteDefinition.class);
    when(shortenedRoute.id()).thenReturn(routeId);
    when(shortenedRoute.waypoints()).thenReturn(java.util.List.of(fromId));
    DynamicResolution<DynamicPlatformAllocator.AllocationResult> result =
        allocator.resolveAllocation(
            "train-stale", shortenedRoute, 1, graph, fromId, Optional.empty());

    assertFalse(result.isSelected());
    assertEquals(Optional.empty(), allocator.getAllocation("train-stale", routeId, 0));
  }

  @Test
  void changedStopSequenceReplacesCachedReservationKey() {
    NodeId fromId = NodeId.of("OP:W:FROM:1:0");
    NodeId platformId = NodeId.of("OP:S:DEST:1");
    RailNode from = mockNode(fromId, new Vector(0, 0, 0), NodeType.WAYPOINT);
    RailNode platform = mockNode(platformId, new Vector(10, 0, 0), NodeType.STATION);
    mockEdges(fromId, edge(from, platform));
    mockEdges(platformId);

    RouteId routeId = RouteId.of("CACHE-SEQUENCE");
    RouteDefinition route = routeWithDynamicStop(routeId, fromId);
    RouteStop initialStop = mock(RouteStop.class);
    when(initialStop.sequence()).thenReturn(1);
    when(initialStop.notes()).thenReturn(Optional.of("DYNAMIC:OP:S:DEST:[1:1]"));
    RouteStop refreshedStop = mock(RouteStop.class);
    when(refreshedStop.sequence()).thenReturn(2);
    when(refreshedStop.notes()).thenReturn(Optional.of("DYNAMIC:OP:S:DEST:[1:1]"));
    java.util.concurrent.atomic.AtomicReference<Optional<RouteStop>> currentStop =
        new java.util.concurrent.atomic.AtomicReference<>(Optional.of(initialStop));
    when(routeDefinitions.findStop(routeId, 1)).thenAnswer(invocation -> currentStop.get());

    assertTrue(allocator.tryAllocate("train-cache", route, 0, graph, fromId).isPresent());
    assertEquals(Optional.of(platformId), allocator.getAllocation("train-cache", routeId, 1));
    currentStop.set(Optional.of(refreshedStop));

    assertTrue(
        allocator
            .resolveAllocation("train-cache", route, 0, graph, fromId, Optional.empty())
            .isSelected());
    assertEquals(Optional.empty(), allocator.getAllocation("train-cache", routeId, 1));
    assertEquals(Optional.of(platformId), allocator.getAllocation("train-cache", routeId, 2));
  }

  @Test
  void cachedDynamicAllocationDetectsExternalClaimBehindSelfClaim() {
    NodeId fromId = NodeId.of("OP:W:FROM:1:0");
    NodeId firstId = NodeId.of("OP:S:DEST:1");
    NodeId secondId = NodeId.of("OP:S:DEST:2");
    RailNode from = mockNode(fromId, new Vector(0, 0, 0), NodeType.WAYPOINT);
    RailNode first = mockNode(firstId, new Vector(10, 0, 0), NodeType.STATION);
    RailNode second = mockNode(secondId, new Vector(20, 0, 0), NodeType.STATION);
    mockEdges(fromId, edge(from, first), edge(from, second));
    mockEdges(firstId);
    mockEdges(secondId);

    RouteId routeId = RouteId.of("CACHE-ALL-CLAIMS");
    RouteDefinition route = routeWithDynamicStop(routeId, fromId);
    RouteStop stop = dynamicStop();
    when(routeDefinitions.findStop(routeId, 1)).thenReturn(Optional.of(stop));

    assertEquals(
        firstId,
        allocator
            .tryAllocate("train-cache", route, 0, graph, fromId)
            .orElseThrow()
            .allocatedNode());
    OccupancyResource firstResource = OccupancyResource.forNode(firstId);
    when(occupancyManager.isNodeOccupied(firstId)).thenReturn(true);
    when(occupancyManager.snapshotClaims())
        .thenReturn(
            java.util.List.of(
                claim(firstResource, "train-cache"), claim(firstResource, "external-train")));

    assertEquals(
        secondId,
        allocator
            .resolveAllocation("train-cache", route, 0, graph, fromId, Optional.empty())
            .selected()
            .orElseThrow()
            .allocatedNode());
  }

  @Test
  void dynamicAllocationIgnoresClaimOwnedBySameLogicalTrain() {
    NodeId fromId = NodeId.of("OP:W:FROM:1:0");
    NodeId platformId = NodeId.of("OP:S:DEST:1");
    RailNode from = mockNode(fromId, new Vector(0, 0, 0), NodeType.WAYPOINT);
    RailNode platform = mockNode(platformId, new Vector(10, 0, 0), NodeType.STATION);
    mockEdges(fromId, edge(from, platform));
    mockEdges(platformId);

    RouteId routeId = RouteId.of("SELF-CLAIM");
    RouteDefinition route = routeWithDynamicStop(routeId, fromId);
    RouteStop stop = dynamicStop();
    when(routeDefinitions.findStop(routeId, 1)).thenReturn(Optional.of(stop));
    when(occupancyManager.isNodeOccupied(platformId)).thenReturn(true);
    mockOccupied(platformId, "Train-Self~A");

    Optional<DynamicPlatformAllocator.AllocationResult> result =
        allocator.tryAllocate("train-self", route, 0, graph, fromId);

    assertTrue(result.isPresent());
    assertEquals(platformId, result.orElseThrow().allocatedNode());
  }

  @Test
  void dynamicAllocationSharesReservationAcrossSplitAliases() {
    NodeId fromId = NodeId.of("OP:W:FROM:1:0");
    NodeId platformId = NodeId.of("OP:S:DEST:1");
    RailNode from = mockNode(fromId, new Vector(0, 0, 0), NodeType.WAYPOINT);
    RailNode platform = mockNode(platformId, new Vector(10, 0, 0), NodeType.STATION);
    mockEdges(fromId, edge(from, platform));
    mockEdges(platformId);

    RouteId routeId = RouteId.of("SPLIT-RESERVATION");
    RouteDefinition route = routeWithDynamicStop(routeId, fromId);
    RouteStop stop = dynamicStop();
    when(routeDefinitions.findStop(routeId, 1)).thenReturn(Optional.of(stop));

    Optional<DynamicPlatformAllocator.AllocationResult> splitResult =
        allocator.tryAllocate("Train-Self~A", route, 0, graph, fromId);
    Optional<DynamicPlatformAllocator.AllocationResult> rootResult =
        allocator.tryAllocate("train-self", route, 0, graph, fromId);

    assertEquals(platformId, splitResult.orElseThrow().allocatedNode());
    assertEquals(platformId, rootResult.orElseThrow().allocatedNode());
    allocator.clearAllocations("TRAIN-SELF~B");
    assertEquals(Optional.empty(), allocator.getAllocation("train-self", routeId, 0));
  }

  @Test
  void invalidDeclaredDynamicSpecBlocksInsteadOfFallingBackToPlaceholder() {
    NodeId fromId = NodeId.of("OP:W:FROM:1:0");
    RouteId routeId = RouteId.of("INVALID-DYNAMIC");
    RouteDefinition route = routeWithDynamicStop(routeId, fromId);
    RouteStop stop = mock(RouteStop.class);
    when(stop.notes()).thenReturn(Optional.of("DYNAMIC:OP:S:DEST:[invalid]"));
    when(routeDefinitions.findStop(routeId, 1)).thenReturn(Optional.of(stop));

    DynamicResolution<DynamicPlatformAllocator.AllocationResult> result =
        allocator.resolveAllocation("train-invalid", route, 0, graph, fromId, Optional.empty());

    assertTrue(result.isBlocked());
    assertEquals("invalid-dynamic-spec", result.reason());
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

  private void mockOccupied(NodeId nodeId, String trainName) {
    OccupancyResource resource = OccupancyResource.forNode(nodeId);
    when(occupancyManager.getClaim(resource)).thenReturn(Optional.of(claim(resource, trainName)));
  }

  private static OccupancyClaim claim(OccupancyResource resource, String trainName) {
    return new OccupancyClaim(
        resource,
        trainName,
        Optional.empty(),
        java.time.Instant.EPOCH,
        Duration.ZERO,
        Optional.empty());
  }

  private static RouteStop dynamicStop() {
    RouteStop stop = mock(RouteStop.class);
    when(stop.notes()).thenReturn(Optional.of("DYNAMIC:OP:S:DEST:[1:2]"));
    return stop;
  }

  private static RouteDefinition routeWithDynamicStop(RouteId routeId, NodeId fromId) {
    RouteDefinition route = mock(RouteDefinition.class);
    when(route.id()).thenReturn(routeId);
    when(route.waypoints()).thenReturn(Arrays.asList(fromId, NodeId.of("PLACEHOLDER")));
    return route;
  }
}
