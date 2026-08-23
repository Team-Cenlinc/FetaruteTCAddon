package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphInterlockingSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailEdgeFootprint;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailFootprintCell;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.interlocking.RailInterlockingState;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.junit.jupiter.api.Test;

/** 实时轨道方块到物理占用资源的映射测试。 */
class LiveRailFootprintResolverTest {

  @Test
  void crossingCellMapsOnlySharedSparseInterlockingResource() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    NodeId d = NodeId.of("D");
    RailFootprintCell crossing = new RailFootprintCell(10, 64, 10);
    TestEdge first = edge(a, b, Set.of(new RailFootprintCell(9, 64, 10), crossing));
    TestEdge second = edge(c, d, Set.of(crossing, new RailFootprintCell(10, 64, 11)));
    SimpleRailGraph graph = graph(first, second);

    LiveRailFootprintResolver.Resolution resolution =
        LiveRailFootprintResolver.resolve(graph, Set.of(crossing));

    assertTrue(resolution.complete());
    assertEquals(1, resolution.occupiedZoneKeys().size());
    assertEquals(1, resolution.resources().size());
    assertTrue(
        resolution.resources().stream()
            .allMatch(resource -> resource.key().startsWith("interlocking:")));
  }

  @Test
  void ordinaryTrackCellsAreCompleteClearWithoutPersistentEdgeMapping() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    RailFootprintCell matched = new RailFootprintCell(1, 64, 1);
    RailFootprintCell unmatched = new RailFootprintCell(99, 64, 99);
    SimpleRailGraph graph = graph(edge(a, b, Set.of(matched)));

    LiveRailFootprintResolver.Resolution resolution =
        LiveRailFootprintResolver.resolve(graph, Set.of(matched, unmatched));

    assertTrue(resolution.complete());
    assertTrue(resolution.resources().isEmpty());
    assertTrue(resolution.occupiedZoneKeys().isEmpty());
  }

  @Test
  void incompleteGraphFootprintCannotBeUsedAsLiveEvidence() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    RailFootprintCell cell = new RailFootprintCell(1, 64, 1);
    TestEdge edge =
        new TestEdge(
            new RailEdge(EdgeId.undirected(a, b), a, b, 10, -1.0, true, Optional.empty()),
            new RailEdgeFootprint(RailEdgeFootprint.CURRENT_FORMAT_VERSION, false, Set.of(cell)));
    SimpleRailGraph graph = graph(edge);

    assertFalse(LiveRailFootprintResolver.resolve(graph, Set.of(cell)).complete());
  }

  @Test
  void liveResolutionUsesSparseZoneIndexWithoutScanningAllEdges() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    NodeId d = NodeId.of("D");
    RailFootprintCell cell = new RailFootprintCell(1, 64, 1);
    TestEdge edge = edge(a, b, Set.of(cell));
    TestEdge crossingEdge = edge(c, d, Set.of(cell));
    RailInterlockingState state =
        RailInterlockingState.from(
            UUID.randomUUID(),
            Set.of(edge.edge().id(), crossingEdge.edge().id()),
            Map.of(
                edge.edge().id(),
                edge.footprint(),
                crossingEdge.edge().id(),
                crossingEdge.footprint()));
    RailGraph graph =
        new IndexedGraph(
            Map.of(edge.edge().id(), edge.edge(), crossingEdge.edge().id(), crossingEdge.edge()),
            state);

    LiveRailFootprintResolver.Resolution resolution =
        LiveRailFootprintResolver.resolve(graph, Set.of(cell));

    assertTrue(resolution.complete());
    assertEquals(1, resolution.occupiedZoneKeys().size());
  }

  private static TestEdge edge(NodeId from, NodeId to, Set<RailFootprintCell> cells) {
    return new TestEdge(
        new RailEdge(EdgeId.undirected(from, to), from, to, 10, -1.0, true, Optional.empty()),
        new RailEdgeFootprint(RailEdgeFootprint.CURRENT_FORMAT_VERSION, true, cells));
  }

  private static SimpleRailGraph graph(TestEdge... edges) {
    Map<NodeId, RailNode> nodes =
        java.util.Arrays.stream(edges)
            .map(TestEdge::edge)
            .flatMap(edge -> java.util.stream.Stream.of(edge.from(), edge.to()))
            .distinct()
            .collect(java.util.stream.Collectors.toMap(node -> node, TestRailNode::new));
    Map<EdgeId, RailEdge> byId =
        java.util.Arrays.stream(edges)
            .map(TestEdge::edge)
            .collect(java.util.stream.Collectors.toMap(RailEdge::id, edge -> edge));
    Map<EdgeId, RailEdgeFootprint> footprints =
        java.util.Arrays.stream(edges)
            .collect(
                java.util.stream.Collectors.toMap(edge -> edge.edge().id(), TestEdge::footprint));
    return new SimpleRailGraph(
        nodes,
        byId,
        Set.of(),
        RailInterlockingState.from(UUID.randomUUID(), byId.keySet(), footprints));
  }

  private record TestEdge(RailEdge edge, RailEdgeFootprint footprint) {}

  private record TestRailNode(NodeId id) implements RailNode {

    @Override
    public NodeType type() {
      return NodeType.WAYPOINT;
    }

    @Override
    public Vector worldPosition() {
      return new Vector();
    }

    @Override
    public Optional<String> trainCartsDestination() {
      return Optional.of(id.value());
    }
  }

  private record IndexedGraph(Map<EdgeId, RailEdge> edgesById, RailInterlockingState state)
      implements RailGraph, RailGraphInterlockingSupport {

    @Override
    public Collection<RailNode> nodes() {
      return Set.of();
    }

    @Override
    public Collection<RailEdge> edges() {
      throw new AssertionError("现场定位不得扫描全部区间");
    }

    @Override
    public Optional<RailEdge> findEdge(EdgeId id) {
      return Optional.ofNullable(edgesById.get(EdgeId.undirected(id.a(), id.b())));
    }

    @Override
    public Optional<RailNode> findNode(NodeId id) {
      return Optional.empty();
    }

    @Override
    public Set<RailEdge> edgesFrom(NodeId id) {
      return Set.of();
    }

    @Override
    public boolean isBlocked(EdgeId id) {
      return false;
    }

    @Override
    public RailInterlockingState interlockingState() {
      return state;
    }
  }
}
