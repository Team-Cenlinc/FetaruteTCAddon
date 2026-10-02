package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.junit.jupiter.api.Test;

class RailGraphConflictIndexTest {

  @Test
  void corridorSharesSameConflictKey() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
    NodeId nodeC = NodeId.of("C");
    EdgeId edgeAB = EdgeId.undirected(nodeA, nodeB);
    EdgeId edgeBC = EdgeId.undirected(nodeB, nodeC);
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(
                nodeA,
                node(nodeA, NodeType.WAYPOINT, 0.0),
                nodeB,
                node(nodeB, NodeType.WAYPOINT, 1.0),
                nodeC,
                node(nodeC, NodeType.WAYPOINT, 2.0)),
            Map.of(edgeAB, edge(edgeAB, nodeA, nodeB), edgeBC, edge(edgeBC, nodeB, nodeC)),
            Set.of());

    RailGraphConflictIndex index = RailGraphConflictIndex.fromGraph(graph);

    assertEquals(
        index.conflictKeyForEdge(edgeAB).orElseThrow(),
        index.conflictKeyForEdge(edgeBC).orElseThrow());
    assertEquals("single:A:A~C", index.conflictKeyForEdge(edgeAB).orElseThrow());
  }

  @Test
  void switcherSplitsConflictCorridor() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeS = NodeId.of("S");
    NodeId nodeB = NodeId.of("B");
    EdgeId edgeAS = EdgeId.undirected(nodeA, nodeS);
    EdgeId edgeSB = EdgeId.undirected(nodeS, nodeB);
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(
                nodeA,
                node(nodeA, NodeType.WAYPOINT, 0.0),
                nodeS,
                node(nodeS, NodeType.SWITCHER, 1.0),
                nodeB,
                node(nodeB, NodeType.WAYPOINT, 2.0)),
            Map.of(edgeAS, edge(edgeAS, nodeA, nodeS), edgeSB, edge(edgeSB, nodeS, nodeB)),
            Set.of());

    RailGraphConflictIndex index = RailGraphConflictIndex.fromGraph(graph);

    assertNotEquals(
        index.conflictKeyForEdge(edgeAS).orElseThrow(),
        index.conflictKeyForEdge(edgeSB).orElseThrow());
  }

  @Test
  void sectionIndexMergesLinearBridgeChainAcrossDegreeTwoSwitcher() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeS = NodeId.of("S");
    NodeId nodeB = NodeId.of("B");
    EdgeId edgeAS = EdgeId.undirected(nodeA, nodeS);
    EdgeId edgeSB = EdgeId.undirected(nodeS, nodeB);
    SimpleRailGraph graph =
        new SimpleRailGraph(
            Map.of(
                nodeA,
                node(nodeA, NodeType.WAYPOINT, 0.0),
                nodeS,
                node(nodeS, NodeType.SWITCHER, 1.0),
                nodeB,
                node(nodeB, NodeType.WAYPOINT, 2.0)),
            Map.of(edgeAS, edge(edgeAS, nodeA, nodeS), edgeSB, edge(edgeSB, nodeS, nodeB)),
            Set.of());

    RailGraphConflictIndex conflictIndex = RailGraphConflictIndex.fromGraph(graph);
    SingleLineSectionIndex sectionIndex = SingleLineSectionIndex.fromGraph(graph);
    SingleLineSectionInfo sectionAS = sectionIndex.sectionInfoForEdge(edgeAS).orElseThrow();
    SingleLineSectionInfo sectionSB = sectionIndex.sectionInfoForEdge(edgeSB).orElseThrow();

    assertNotEquals(
        conflictIndex.conflictKeyForEdge(edgeAS).orElseThrow(),
        conflictIndex.conflictKeyForEdge(edgeSB).orElseThrow());
    assertEquals(sectionAS, sectionSB);
    assertEquals(List.of(nodeA, nodeS, nodeB), sectionAS.nodes());
    assertTrue(sectionAS.directional());
  }

  @Test
  void sectionIndexCutsAtDegreeThreeBranchSwitcher() {
    assertBranchSwitcherCutsEveryBridgeChain(3);
  }

  @Test
  void sectionIndexCutsAtDegreeFourBranchSwitcher() {
    assertBranchSwitcherCutsEveryBridgeChain(4);
  }

  @Test
  void sectionIndexLeavesPpkMeshUnsectionedAndKeepsExternalTracksIndependent() {
    PpkFixture fixture = ppkFixture(false);
    SingleLineSectionIndex index = SingleLineSectionIndex.fromGraph(fixture.graph());

    fixture.blockEdges().forEach(edge -> assertTrue(index.sectionInfoForEdge(edge).isEmpty()));
    assertNotEquals(
        index.sectionInfoForEdge(fixture.ppk1Stub()).orElseThrow().key(),
        index.sectionInfoForEdge(fixture.ppk2Stub()).orElseThrow().key());
    assertNotEquals(
        assertSingleDirectionalSection(index, fixture.track1Edges()),
        assertSingleDirectionalSection(index, fixture.track2Edges()));
    assertTrue(index.snapshot().values().stream().allMatch(SingleLineSectionInfo::directional));
    assertTrue(
        index.snapshot().values().stream().noneMatch(info -> info.key().contains(":cycle:")));
  }

  @Test
  void sectionIndexLeavesPassingLoopEdgesUnsectioned() {
    NodeId west = NodeId.of("WEST");
    NodeId switcherA = NodeId.of("SW-A");
    NodeId upper = NodeId.of("UPPER");
    NodeId lower = NodeId.of("LOWER");
    NodeId switcherB = NodeId.of("SW-B");
    NodeId east = NodeId.of("EAST");
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    List.of(west, upper, lower, east)
        .forEach(id -> nodes.put(id, node(id, NodeType.WAYPOINT, nodes.size())));
    nodes.put(switcherA, node(switcherA, NodeType.SWITCHER, nodes.size()));
    nodes.put(switcherB, node(switcherB, NodeType.SWITCHER, nodes.size()));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    EdgeId westStub = putEdge(edges, west, switcherA, 20);
    List<EdgeId> loopEdges =
        List.of(
            putEdge(edges, switcherA, upper, 10),
            putEdge(edges, upper, switcherB, 10),
            putEdge(edges, switcherA, lower, 10),
            putEdge(edges, lower, switcherB, 10));
    EdgeId eastStub = putEdge(edges, switcherB, east, 20);

    SingleLineSectionIndex index =
        SingleLineSectionIndex.fromGraph(new SimpleRailGraph(nodes, edges, Set.of()));

    loopEdges.forEach(edge -> assertTrue(index.sectionInfoForEdge(edge).isEmpty()));
    assertTrue(index.sectionInfoForEdge(westStub).orElseThrow().directional());
    assertTrue(index.sectionInfoForEdge(eastStub).orElseThrow().directional());
    assertNotEquals(
        index.sectionInfoForEdge(westStub).orElseThrow().key(),
        index.sectionInfoForEdge(eastStub).orElseThrow().key());
  }

  @Test
  void sectionIndexIsStableAcrossGraphInsertionOrder() {
    PpkFixture forward = ppkFixture(false);
    PpkFixture reversed = ppkFixture(true);

    assertEquals(
        SingleLineSectionIndex.fromGraph(forward.graph()).snapshot(),
        SingleLineSectionIndex.fromGraph(reversed.graph()).snapshot());
  }

  @Test
  void sectionKeyStaysStableWhenComponentGetsSmallerNodeOutsideBridgeChain() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeS = NodeId.of("S");
    NodeId nodeB = NodeId.of("B");
    EdgeId edgeAS = EdgeId.undirected(nodeA, nodeS);
    EdgeId edgeSB = EdgeId.undirected(nodeS, nodeB);
    Map<NodeId, RailNode> originalNodes =
        Map.of(
            nodeA, node(nodeA, NodeType.WAYPOINT, 0.0),
            nodeS, node(nodeS, NodeType.SWITCHER, 1.0),
            nodeB, node(nodeB, NodeType.WAYPOINT, 2.0));
    Map<EdgeId, RailEdge> originalEdges =
        Map.of(edgeAS, edge(edgeAS, nodeA, nodeS), edgeSB, edge(edgeSB, nodeS, nodeB));
    SimpleRailGraph original = new SimpleRailGraph(originalNodes, originalEdges, Set.of());

    NodeId smaller = NodeId.of("0");
    NodeId cyclePeer = NodeId.of("X");
    Map<NodeId, RailNode> expandedNodes = new LinkedHashMap<>(originalNodes);
    expandedNodes.put(smaller, node(smaller, NodeType.WAYPOINT, -2.0));
    expandedNodes.put(cyclePeer, node(cyclePeer, NodeType.WAYPOINT, -1.0));
    Map<EdgeId, RailEdge> expandedEdges = new LinkedHashMap<>(originalEdges);
    putEdge(expandedEdges, nodeA, smaller, 10);
    putEdge(expandedEdges, smaller, cyclePeer, 10);
    putEdge(expandedEdges, cyclePeer, nodeA, 10);
    SimpleRailGraph expanded = new SimpleRailGraph(expandedNodes, expandedEdges, Set.of());

    String originalKey =
        SingleLineSectionIndex.fromGraph(original).sectionInfoForEdge(edgeAS).orElseThrow().key();
    SingleLineSectionInfo expandedSection =
        SingleLineSectionIndex.fromGraph(expanded).sectionInfoForEdge(edgeAS).orElseThrow();

    assertEquals("single:section:bridge:A~B", originalKey);
    assertEquals(originalKey, expandedSection.key());
    assertEquals(
        expandedSection,
        SingleLineSectionIndex.fromGraph(expanded).sectionInfoForEdge(edgeSB).orElseThrow());
  }

  private static void assertBranchSwitcherCutsEveryBridgeChain(int branchCount) {
    NodeId switcher = NodeId.of("SWITCHER");
    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    nodes.put(switcher, node(switcher, NodeType.SWITCHER, 0.0));
    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    for (int i = 0; i < branchCount; i++) {
      NodeId leaf = NodeId.of("LEAF-" + i);
      nodes.put(leaf, node(leaf, NodeType.WAYPOINT, i + 1.0));
      putEdge(edges, switcher, leaf, 10);
    }

    SingleLineSectionIndex index =
        SingleLineSectionIndex.fromGraph(new SimpleRailGraph(nodes, edges, Set.of()));
    Set<String> sectionKeys =
        edges.keySet().stream()
            .map(edge -> index.sectionInfoForEdge(edge).orElseThrow().key())
            .collect(java.util.stream.Collectors.toSet());

    assertEquals(branchCount, sectionKeys.size());
    index
        .snapshot()
        .values()
        .forEach(
            section -> {
              assertEquals(2, section.nodes().size());
              assertTrue(section.nodes().contains(switcher));
              assertTrue(section.directional());
            });
  }

  private static String assertSingleDirectionalSection(
      SingleLineSectionIndex index, List<EdgeId> edges) {
    Set<SingleLineSectionInfo> sections =
        edges.stream()
            .map(edge -> index.sectionInfoForEdge(edge).orElseThrow())
            .collect(java.util.stream.Collectors.toSet());
    assertEquals(1, sections.size());
    SingleLineSectionInfo section = sections.iterator().next();
    assertTrue(section.directional());
    return section.key();
  }

  private static PpkFixture ppkFixture(boolean reverseInsertionOrder) {
    NodeId ppk1 = NodeId.of("SURC:S:PPK:1");
    NodeId ppk2 = NodeId.of("SURC:S:PPK:2");
    NodeId s1 = NodeId.of("SWITCHER:Towny:-579:65:630");
    NodeId s2 = NodeId.of("SWITCHER:Towny:-583:65:630");
    NodeId c1 = NodeId.of("SWITCHER:Towny:-581:65:637");
    NodeId c2 = NodeId.of("SWITCHER:Towny:-581:65:643");
    NodeId n1 = NodeId.of("SWITCHER:Towny:-579:65:650");
    NodeId n2 = NodeId.of("SWITCHER:Towny:-583:65:650");
    NodeId way1a = NodeId.of("SURC:PPK:RVS:1:001");
    NodeId way1b = NodeId.of("SURC:PPK:RVS:1:002");
    NodeId way2a = NodeId.of("SURC:PPK:RVS:2:001");
    NodeId way2b = NodeId.of("SURC:PPK:RVS:2:002");
    NodeId rvs1 = NodeId.of("SURC:S:RVS:1");
    NodeId rvs2 = NodeId.of("SURC:S:RVS:2");

    Map<NodeId, RailNode> nodes = new LinkedHashMap<>();
    List.of(ppk1, ppk2, rvs1, rvs2)
        .forEach(id -> nodes.put(id, node(id, NodeType.STATION, nodes.size())));
    List.of(s1, s2, c1, c2, n1, n2)
        .forEach(id -> nodes.put(id, node(id, NodeType.SWITCHER, nodes.size())));
    List.of(way1a, way1b, way2a, way2b)
        .forEach(id -> nodes.put(id, node(id, NodeType.WAYPOINT, nodes.size())));

    Map<EdgeId, RailEdge> edges = new LinkedHashMap<>();
    EdgeId ppk1Stub = putEdge(edges, ppk1, s1, 25);
    EdgeId ppk2Stub = putEdge(edges, ppk2, s2, 25);
    List<EdgeId> blockEdges =
        List.of(
            putEdge(edges, s1, n1, 18),
            putEdge(edges, s2, n2, 18),
            putEdge(edges, s1, c1, 7),
            putEdge(edges, s2, c1, 7),
            putEdge(edges, c1, c2, 4),
            putEdge(edges, c2, n1, 7),
            putEdge(edges, c2, n2, 7));
    List<EdgeId> track1Edges =
        List.of(
            putEdge(edges, n1, way1a, 21),
            putEdge(edges, way1a, way1b, 41),
            putEdge(edges, way1b, rvs1, 38));
    List<EdgeId> track2Edges =
        List.of(
            putEdge(edges, n2, way2a, 21),
            putEdge(edges, way2a, way2b, 41),
            putEdge(edges, way2b, rvs2, 38));
    Map<NodeId, RailNode> graphNodes =
        reverseInsertionOrder ? reversedCopy(nodes) : Map.copyOf(nodes);
    Map<EdgeId, RailEdge> graphEdges =
        reverseInsertionOrder ? reversedCopy(edges) : Map.copyOf(edges);
    return new PpkFixture(
        new OrderedRailGraph(graphNodes, graphEdges),
        ppk1Stub,
        ppk2Stub,
        blockEdges,
        track1Edges,
        track2Edges);
  }

  private static SignRailNode node(NodeId id, NodeType type, double x) {
    return new SignRailNode(id, type, new Vector(x, 64.0, 0.0), Optional.empty(), Optional.empty());
  }

  private static RailEdge edge(EdgeId id, NodeId from, NodeId to) {
    return edge(id, from, to, 10);
  }

  private static RailEdge edge(EdgeId id, NodeId from, NodeId to, int lengthBlocks) {
    return new RailEdge(id, from, to, lengthBlocks, 8.0, true, Optional.empty());
  }

  private static EdgeId putEdge(
      Map<EdgeId, RailEdge> edges, NodeId from, NodeId to, int lengthBlocks) {
    EdgeId edgeId = EdgeId.undirected(from, to);
    edges.put(edgeId, edge(edgeId, from, to, lengthBlocks));
    return edgeId;
  }

  private static <K, V> Map<K, V> reversedCopy(Map<K, V> source) {
    List<Map.Entry<K, V>> entries = new ArrayList<>(source.entrySet());
    java.util.Collections.reverse(entries);
    Map<K, V> reversed = new LinkedHashMap<>();
    entries.forEach(entry -> reversed.put(entry.getKey(), entry.getValue()));
    return reversed;
  }

  private record PpkFixture(
      RailGraph graph,
      EdgeId ppk1Stub,
      EdgeId ppk2Stub,
      List<EdgeId> blockEdges,
      List<EdgeId> track1Edges,
      List<EdgeId> track2Edges) {}

  /** 保留输入迭代顺序的测试图，用于证明派生 section 与 Map 插入顺序无关。 */
  private static final class OrderedRailGraph implements RailGraph {
    private final Map<NodeId, RailNode> nodes;
    private final List<RailEdge> edges;
    private final Map<NodeId, Set<RailEdge>> adjacency;

    private OrderedRailGraph(Map<NodeId, RailNode> nodes, Map<EdgeId, RailEdge> edges) {
      this.nodes = new LinkedHashMap<>(nodes);
      this.edges = List.copyOf(edges.values());
      Map<NodeId, Set<RailEdge>> adjacency = new LinkedHashMap<>();
      this.nodes.keySet().forEach(node -> adjacency.put(node, new LinkedHashSet<>()));
      this.edges.forEach(
          edge -> {
            adjacency.computeIfAbsent(edge.from(), ignored -> new LinkedHashSet<>()).add(edge);
            adjacency.computeIfAbsent(edge.to(), ignored -> new LinkedHashSet<>()).add(edge);
          });
      Map<NodeId, Set<RailEdge>> frozen = new LinkedHashMap<>();
      adjacency.forEach(
          (node, incident) -> frozen.put(node, java.util.Collections.unmodifiableSet(incident)));
      this.adjacency = java.util.Collections.unmodifiableMap(frozen);
    }

    @Override
    public Collection<RailNode> nodes() {
      return nodes.values();
    }

    @Override
    public Collection<RailEdge> edges() {
      return edges;
    }

    @Override
    public Optional<RailNode> findNode(NodeId id) {
      return Optional.ofNullable(nodes.get(id));
    }

    @Override
    public Set<RailEdge> edgesFrom(NodeId id) {
      return adjacency.getOrDefault(id, Set.of());
    }

    @Override
    public boolean isBlocked(EdgeId id) {
      return false;
    }
  }
}
