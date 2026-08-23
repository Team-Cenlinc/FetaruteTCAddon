package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingEdgeSignature;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailInterlockingSnapshotRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailNodeRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.junit.jupiter.api.Test;

class RailGraphServiceTest {

  @Test
  void findWorldIdForPathUsesSingleGraph() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    RailGraph graph = graphWithEdges(edge(a, b), edge(b, c));

    RailGraphService service = new RailGraphService(world -> graph);
    World world = mock(World.class);
    UUID worldId = UUID.randomUUID();
    when(world.getUID()).thenReturn(worldId);
    when(world.getName()).thenReturn("world");
    service.putSnapshot(world, graph, Instant.now());

    Optional<UUID> found = service.findWorldIdForPath(List.of(a, b, c));

    assertEquals(Optional.of(worldId), found);
  }

  @Test
  void findWorldIdForPathReturnsEmptyWhenEdgesSplitAcrossWorlds() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    RailGraph graphA = graphWithEdges(edge(a, b));
    RailGraph graphB = graphWithEdges(edge(b, c));

    RailGraphService service = new RailGraphService(world -> graphA);
    World world1 = mock(World.class);
    UUID worldId1 = UUID.randomUUID();
    when(world1.getUID()).thenReturn(worldId1);
    when(world1.getName()).thenReturn("world1");
    service.putSnapshot(world1, graphA, Instant.now());

    World world2 = mock(World.class);
    UUID worldId2 = UUID.randomUUID();
    when(world2.getUID()).thenReturn(worldId2);
    when(world2.getName()).thenReturn("world2");
    service.putSnapshot(world2, graphB, Instant.now());

    Optional<UUID> found = service.findWorldIdForPath(List.of(a, b, c));

    assertTrue(found.isEmpty());
  }

  @Test
  void buildGraphFromRecordsRestoresIndependentSparseSnapshot() {
    UUID worldId = UUID.randomUUID();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    EdgeId edgeId = EdgeId.undirected(a, b);
    List<RailNodeRecord> nodes = List.of(nodeRecord(worldId, a, 0), nodeRecord(worldId, b, 10));
    List<RailEdgeRecord> edges = List.of(new RailEdgeRecord(worldId, edgeId, 10, 0.0, true));
    RailInterlockingSnapshotRecord snapshot =
        new RailInterlockingSnapshotRecord(
            worldId,
            RailInterlockingSnapshotRecord.CURRENT_FORMAT_VERSION,
            RailInterlockingEdgeSignature.of(Set.of(edgeId)),
            new org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingCoverage(
                1, 1, true),
            Map.of());

    RailGraph graph = RailGraphService.buildGraphFromRecords(nodes, edges, Optional.of(snapshot));

    RailGraphInterlockingSupport support = (RailGraphInterlockingSupport) graph;
    assertTrue(support.interlockingState().coverage().complete());
    assertEquals(1, support.interlockingState().coverage().inputEdgeCount());
  }

  @Test
  void buildGraphFromLegacyRecordsCreatesIncompleteWorldSentinel() {
    UUID worldId = UUID.randomUUID();
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    EdgeId edgeId = EdgeId.undirected(a, b);
    List<RailNodeRecord> nodes = List.of(nodeRecord(worldId, a, 0), nodeRecord(worldId, b, 10));
    List<RailEdgeRecord> edges = List.of(new RailEdgeRecord(worldId, edgeId, 10, 0.0, true));

    RailGraph graph = RailGraphService.buildGraphFromRecords(nodes, edges);

    RailGraphInterlockingSupport support = (RailGraphInterlockingSupport) graph;
    assertTrue(support.interlockingState().available());
    assertFalse(support.interlockingState().coverage().complete());
    assertEquals(1, support.interlockingState().zoneKeysForEdge(edgeId).size());
  }

  @Test
  void rejectsChangedInterlockingProjectionWhileClaimsAreActiveAndKeepsOldSnapshot() {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    RailGraph oldGraph =
        interlockingGraph(
            worldId, new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(2, 64, 8))));
    RailGraph changedGraph = interlockingGraph(worldId, new RailEdgeFootprint(0, false, Set.of()));
    RailGraphService service = new RailGraphService(ignored -> oldGraph);
    service.putSnapshot(world, oldGraph, Instant.parse("2026-01-01T00:00:00Z"));
    service.setSnapshotActivationGuard(() -> false);

    assertThrows(
        IllegalStateException.class,
        () -> service.putSnapshot(world, changedGraph, Instant.parse("2026-01-02T00:00:00Z")));

    assertSame(oldGraph, service.getSnapshot(world).orElseThrow().graph());
  }

  @Test
  void staleSnapshotStillGuardsItsLastActivatedInterlockingProjection() {
    UUID worldId = UUID.randomUUID();
    World world = mock(World.class);
    when(world.getUID()).thenReturn(worldId);
    RailGraph oldGraph =
        interlockingGraph(
            worldId, new RailEdgeFootprint(1, true, Set.of(new RailFootprintCell(2, 64, 8))));
    RailGraph changedGraph = interlockingGraph(worldId, new RailEdgeFootprint(0, false, Set.of()));
    RailGraphService service = new RailGraphService(ignored -> oldGraph);
    service.putSnapshot(world, oldGraph, Instant.parse("2026-01-01T00:00:00Z"));
    service.setSnapshotActivationGuard(() -> false);
    service.markStale(
        world,
        new RailGraphService.RailGraphStaleState(
            Instant.parse("2026-01-01T00:00:00Z"), "old", "new", 2, 1, 3));

    assertThrows(
        IllegalStateException.class,
        () -> service.putSnapshot(world, changedGraph, Instant.parse("2026-01-02T00:00:00Z")));

    assertTrue(service.getSnapshot(world).isEmpty());
    assertTrue(service.getStaleState(world).isPresent());
  }

  private static RailEdge edge(NodeId a, NodeId b) {
    return new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty());
  }

  private static RailGraph graphWithEdges(RailEdge... edges) {
    List<RailEdge> edgeList = List.of(edges);
    Map<NodeId, RailNode> nodes =
        edgeList.stream()
            .flatMap(edge -> java.util.stream.Stream.of(edge.from(), edge.to()))
            .distinct()
            .collect(java.util.stream.Collectors.toMap(id -> id, RailGraphServiceTest::node));
    return new RailGraph() {
      @Override
      public java.util.Collection<RailNode> nodes() {
        return nodes.values();
      }

      @Override
      public java.util.Collection<RailEdge> edges() {
        return edgeList;
      }

      @Override
      public Optional<RailNode> findNode(NodeId id) {
        return Optional.ofNullable(nodes.get(id));
      }

      @Override
      public Set<RailEdge> edgesFrom(NodeId id) {
        return edgeList.stream()
            .filter(edge -> edge.from().equals(id) || edge.to().equals(id))
            .collect(java.util.stream.Collectors.toSet());
      }

      @Override
      public boolean isBlocked(EdgeId id) {
        return false;
      }
    };
  }

  private static RailNode node(NodeId id) {
    return new RailNode() {
      @Override
      public NodeId id() {
        return id;
      }

      @Override
      public NodeType type() {
        return NodeType.WAYPOINT;
      }

      @Override
      public Vector worldPosition() {
        return new Vector(0, 0, 0);
      }

      @Override
      public Optional<String> trainCartsDestination() {
        return Optional.empty();
      }
    };
  }

  private static RailNodeRecord nodeRecord(UUID worldId, NodeId id, int x) {
    return new RailNodeRecord(
        worldId, id, NodeType.WAYPOINT, x, 64, 0, Optional.empty(), Optional.empty());
  }

  private static RailGraph interlockingGraph(UUID worldId, RailEdgeFootprint footprint) {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    RailEdge edge = new RailEdge(EdgeId.undirected(a, b), a, b, 10, 0.0, true, Optional.empty());
    Map<NodeId, RailNode> nodes = Map.of(a, node(a), b, node(b));
    Map<EdgeId, RailEdge> edges = Map.of(edge.id(), edge);
    RailInterlockingState state =
        RailInterlockingState.from(worldId, edges.keySet(), Map.of(edge.id(), footprint));
    return new SimpleRailGraph(nodes, edges, Set.of(), state);
  }
}
