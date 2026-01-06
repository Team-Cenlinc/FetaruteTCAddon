package org.fetarute.fetaruteTCAddon.dispatcher.graph.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

final class RailGraphPathFinderTest {

  @Test
  void returnsEmptyWhenNodeMissing() {
    RailGraph graph = SimpleRailGraph.empty();
    RailGraphPathFinder finder = new RailGraphPathFinder();

    assertTrue(
        finder
            .shortestPath(
                graph,
                NodeId.of("A"),
                NodeId.of("B"),
                RailGraphPathFinder.Options.shortestDistance())
            .isEmpty());
  }

  @Test
  void returnsTrivialPathWhenFromEqualsTo() {
    RailGraph graph = graph(Set.of(node("A")), Set.of(), Set.of());
    RailGraphPathFinder finder = new RailGraphPathFinder();

    RailGraphPath path =
        finder
            .shortestPath(
                graph,
                NodeId.of("A"),
                NodeId.of("A"),
                RailGraphPathFinder.Options.shortestDistance())
            .orElseThrow();

    assertEquals(1, path.nodes().size());
    assertEquals(0, path.edges().size());
    assertEquals(0L, path.totalLengthBlocks());
  }

  @Test
  void choosesShortestPathByLength() {
    RailGraph graph =
        graph(
            Set.of(node("A"), node("B"), node("C")),
            Set.of(edge("A", "B", 10), edge("A", "C", 3), edge("C", "B", 4)),
            Set.of());
    RailGraphPathFinder finder = new RailGraphPathFinder();

    RailGraphPath path =
        finder
            .shortestPath(
                graph,
                NodeId.of("A"),
                NodeId.of("B"),
                RailGraphPathFinder.Options.shortestDistance())
            .orElseThrow();

    assertEquals(List.of(NodeId.of("A"), NodeId.of("C"), NodeId.of("B")), path.nodes());
    assertEquals(2, path.edges().size());
    assertEquals(7L, path.totalLengthBlocks());
  }

  @Test
  void skipsBlockedEdgesByDefault() {
    EdgeId blocked = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailGraph graph =
        graph(
            Set.of(node("A"), node("B"), node("C")),
            Set.of(edge("A", "B", 5), edge("A", "C", 2), edge("C", "B", 2)),
            Set.of(blocked));
    RailGraphPathFinder finder = new RailGraphPathFinder();

    RailGraphPath path =
        finder
            .shortestPath(
                graph,
                NodeId.of("A"),
                NodeId.of("B"),
                RailGraphPathFinder.Options.shortestDistance())
            .orElseThrow();

    assertEquals(4L, path.totalLengthBlocks());
    assertEquals(List.of(NodeId.of("A"), NodeId.of("C"), NodeId.of("B")), path.nodes());
  }

  private static RailGraph graph(
      Set<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodes,
      Set<RailEdge> edges,
      Set<EdgeId> blockedEdges) {
    Map<NodeId, org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodesById =
        new java.util.HashMap<>();
    for (org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode node : nodes) {
      nodesById.put(node.id(), node);
    }
    Map<EdgeId, RailEdge> edgesById = new java.util.HashMap<>();
    for (RailEdge edge : edges) {
      edgesById.put(edge.id(), edge);
    }
    return new SimpleRailGraph(nodesById, edgesById, blockedEdges);
  }

  private static org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode node(String id) {
    return new SignRailNode(
        NodeId.of(id), NodeType.WAYPOINT, new Vector(0, 0, 0), Optional.empty(), Optional.empty());
  }

  private static RailEdge edge(String a, String b, int lengthBlocks) {
    EdgeId id = EdgeId.undirected(NodeId.of(a), NodeId.of(b));
    return new RailEdge(id, id.a(), id.b(), lengthBlocks, 0.0, true, Optional.empty());
  }
}
