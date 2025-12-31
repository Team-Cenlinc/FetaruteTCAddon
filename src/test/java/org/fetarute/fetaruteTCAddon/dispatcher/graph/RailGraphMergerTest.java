package org.fetarute.fetaruteTCAddon.dispatcher.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

final class RailGraphMergerTest {

  @Test
  void appendsWhenNoNodeOverlap() {
    RailGraph base = graph(Set.of(node("A"), node("B")), Set.of(edge("A", "B", 5)), Set.of());
    RailGraph update = graph(Set.of(node("X"), node("Y")), Set.of(edge("X", "Y", 7)), Set.of());

    RailGraphMerger.MergeResult merged = RailGraphMerger.appendOrReplaceComponents(base, update);

    assertEquals(RailGraphMerger.MergeAction.APPEND, merged.action());
    assertEquals(0, merged.replacedComponentCount());
    assertEquals(0, merged.removedNodes());
    assertEquals(0, merged.removedEdges());
    assertEquals(4, merged.totalNodes());
    assertEquals(2, merged.totalEdges());
    assertTrue(merged.graph().findNode(NodeId.of("A")).isPresent());
    assertTrue(merged.graph().findNode(NodeId.of("X")).isPresent());
  }

  @Test
  void replacesOverlappedComponent() {
    RailGraph base =
        graph(
            Set.of(node("A"), node("B"), node("X"), node("Y")),
            Set.of(edge("A", "B", 5), edge("X", "Y", 7)),
            Set.of());
    RailGraph update =
        graph(
            Set.of(node("X"), node("Y"), node("Z")),
            Set.of(edge("X", "Z", 2), edge("Z", "Y", 3)),
            Set.of());

    RailGraphMerger.MergeResult merged = RailGraphMerger.appendOrReplaceComponents(base, update);

    assertEquals(RailGraphMerger.MergeAction.REPLACE_COMPONENTS, merged.action());
    assertEquals(1, merged.replacedComponentCount());
    assertEquals(2, merged.removedNodes());
    assertEquals(1, merged.removedEdges());
    assertEquals(5, merged.totalNodes());
    assertEquals(3, merged.totalEdges());

    assertTrue(merged.graph().findNode(NodeId.of("A")).isPresent());
    assertTrue(merged.graph().findNode(NodeId.of("B")).isPresent());
    assertTrue(merged.graph().findNode(NodeId.of("X")).isPresent());
    assertTrue(merged.graph().findNode(NodeId.of("Y")).isPresent());
    assertTrue(merged.graph().findNode(NodeId.of("Z")).isPresent());
    assertFalse(
        merged.graph().edges().stream()
            .anyMatch(e -> e.id().equals(EdgeId.undirected(NodeId.of("X"), NodeId.of("Y")))));
  }

  @Test
  void preservesBlockedEdgesWhenAppending() {
    EdgeId blocked = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailGraph base =
        graph(Set.of(node("A"), node("B")), Set.of(edge("A", "B", 5)), Set.of(blocked));
    RailGraph update = graph(Set.of(node("X"), node("Y")), Set.of(edge("X", "Y", 7)), Set.of());

    RailGraphMerger.MergeResult merged = RailGraphMerger.appendOrReplaceComponents(base, update);

    assertTrue(merged.graph().isBlocked(blocked));
  }

  @Test
  void upsertKeepsBaseNodesAndOverwritesEdges() {
    EdgeId blocked = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailGraph base =
        graph(
            Set.of(node("A"), node("B"), node("X"), node("Y")),
            Set.of(edge("A", "B", 5), edge("X", "Y", 7)),
            Set.of(blocked));
    RailGraph update =
        graph(
            Set.of(node("X"), node("Y"), node("Z")),
            Set.of(edge("X", "Y", 9), edge("Y", "Z", 2)),
            Set.of());

    RailGraphMerger.MergeResult merged = RailGraphMerger.upsert(base, update);

    assertEquals(RailGraphMerger.MergeAction.UPSERT, merged.action());
    assertEquals(0, merged.replacedComponentCount());
    assertEquals(0, merged.removedNodes());
    assertEquals(0, merged.removedEdges());
    assertEquals(5, merged.totalNodes());
    assertEquals(3, merged.totalEdges());
    assertTrue(merged.graph().findNode(NodeId.of("A")).isPresent());
    assertTrue(merged.graph().findNode(NodeId.of("B")).isPresent());
    assertTrue(merged.graph().isBlocked(blocked));
    assertTrue(
        merged.graph().edges().stream()
            .anyMatch(
                edge ->
                    edge.id().equals(EdgeId.undirected(NodeId.of("X"), NodeId.of("Y")))
                        && edge.lengthBlocks() == 9));
  }

  @Test
  void removesComponentBySeed() {
    RailGraph base =
        graph(
            Set.of(node("A"), node("B"), node("X"), node("Y"), node("Z")),
            Set.of(edge("A", "B", 5), edge("X", "Y", 7), edge("Y", "Z", 2)),
            Set.of());

    RailGraphMerger.RemoveResult removed =
        RailGraphMerger.removeComponents(base, Set.of(NodeId.of("X")));

    assertEquals(1, removed.removedComponentCount());
    assertEquals(3, removed.removedNodes());
    assertEquals(2, removed.removedEdges());
    assertEquals(2, removed.totalNodes());
    assertEquals(1, removed.totalEdges());
    assertTrue(removed.graph().findNode(NodeId.of("A")).isPresent());
    assertTrue(removed.graph().findNode(NodeId.of("B")).isPresent());
    assertFalse(removed.graph().findNode(NodeId.of("X")).isPresent());
  }

  @Test
  void removeComponentIgnoresUnknownSeed() {
    RailGraph base = graph(Set.of(node("A"), node("B")), Set.of(edge("A", "B", 5)), Set.of());

    RailGraphMerger.RemoveResult removed =
        RailGraphMerger.removeComponents(base, Set.of(NodeId.of("unknown")));

    assertEquals(0, removed.removedComponentCount());
    assertEquals(0, removed.removedNodes());
    assertEquals(0, removed.removedEdges());
    assertEquals(2, removed.totalNodes());
    assertEquals(1, removed.totalEdges());
  }

  @Test
  void removesMultipleComponentsWhenSeedsInDifferentComponents() {
    RailGraph base =
        graph(
            Set.of(node("A"), node("B"), node("X"), node("Y"), node("Z"), node("P"), node("Q")),
            Set.of(edge("A", "B", 5), edge("X", "Y", 7), edge("Y", "Z", 2), edge("P", "Q", 1)),
            Set.of());

    RailGraphMerger.RemoveResult removed =
        RailGraphMerger.removeComponents(base, Set.of(NodeId.of("X"), NodeId.of("P")));

    assertEquals(2, removed.removedComponentCount());
    assertEquals(5, removed.removedNodes());
    assertEquals(3, removed.removedEdges());
    assertEquals(2, removed.totalNodes());
    assertEquals(1, removed.totalEdges());
    assertTrue(removed.graph().findNode(NodeId.of("A")).isPresent());
    assertTrue(removed.graph().findNode(NodeId.of("B")).isPresent());
    assertTrue(removed.graph().findNode(NodeId.of("X")).isEmpty());
    assertTrue(removed.graph().findNode(NodeId.of("P")).isEmpty());
  }

  @Test
  void removesComponentOnceWhenSeedsOverlapSameComponent() {
    RailGraph base =
        graph(
            Set.of(node("A"), node("B"), node("X"), node("Y"), node("Z")),
            Set.of(edge("A", "B", 5), edge("X", "Y", 7), edge("Y", "Z", 2)),
            Set.of());

    RailGraphMerger.RemoveResult removed =
        RailGraphMerger.removeComponents(base, Set.of(NodeId.of("X"), NodeId.of("Z")));

    assertEquals(1, removed.removedComponentCount());
    assertEquals(3, removed.removedNodes());
    assertEquals(2, removed.removedEdges());
    assertEquals(2, removed.totalNodes());
    assertEquals(1, removed.totalEdges());
    assertTrue(removed.graph().findNode(NodeId.of("A")).isPresent());
    assertTrue(removed.graph().findNode(NodeId.of("B")).isPresent());
    assertTrue(removed.graph().findNode(NodeId.of("X")).isEmpty());
    assertTrue(removed.graph().findNode(NodeId.of("Z")).isEmpty());
  }

  @Test
  void preservesBlockedEdgesWhenRemovingOtherComponent() {
    EdgeId blocked = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailGraph base =
        graph(
            Set.of(node("A"), node("B"), node("X"), node("Y")),
            Set.of(edge("A", "B", 5), edge("X", "Y", 7)),
            Set.of(blocked));

    RailGraphMerger.RemoveResult removed =
        RailGraphMerger.removeComponents(base, Set.of(NodeId.of("X")));

    assertEquals(1, removed.removedComponentCount());
    assertEquals(2, removed.removedNodes());
    assertEquals(1, removed.removedEdges());
    assertEquals(2, removed.totalNodes());
    assertEquals(1, removed.totalEdges());
    assertTrue(removed.graph().isBlocked(blocked));
  }

  private static SimpleRailGraph graph(
      Set<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodes,
      Set<RailEdge> edges,
      Set<EdgeId> blockedEdges) {
    Map<NodeId, org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodesById = new HashMap<>();
    for (org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode node : nodes) {
      nodesById.put(node.id(), node);
    }
    Map<EdgeId, RailEdge> edgesById = new HashMap<>();
    for (RailEdge edge : edges) {
      edgesById.put(edge.id(), edge);
    }
    return new SimpleRailGraph(nodesById, edgesById, blockedEdges);
  }

  private static org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode node(String id) {
    return new SignRailNode(
        NodeId.of(id), NodeType.WAYPOINT, new Vector(0, 0, 0), Optional.empty(), Optional.empty());
  }

  private static RailEdge edge(String a, String b, int length) {
    EdgeId id = EdgeId.undirected(NodeId.of(a), NodeId.of(b));
    return new RailEdge(id, id.a(), id.b(), length, 0.0, true, Optional.empty());
  }
}
