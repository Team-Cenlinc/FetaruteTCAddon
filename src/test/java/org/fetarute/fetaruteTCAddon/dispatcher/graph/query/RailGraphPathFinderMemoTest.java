package org.fetarute.fetaruteTCAddon.dispatcher.graph.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.EdgeOverrideRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.junit.jupiter.api.Test;

/** 最短距离查询按图快照记忆：命中只能发生在可达性完全相同的图上。 */
final class RailGraphPathFinderMemoTest {

  private static final NodeId A = NodeId.of("A");
  private static final NodeId B = NodeId.of("B");
  private static final NodeId C = NodeId.of("C");
  private static final NodeId D = NodeId.of("D");
  private static final List<NodeId> VIA_B = List.of(A, B, D);
  private static final List<NodeId> VIA_C = List.of(A, C, D);
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  private final RailGraphPathFinder finder = new RailGraphPathFinder();

  @Test
  void repeatedQueryOnSameSnapshotReusesResult() {
    SimpleRailGraph graph = diamond(Set.of());

    RailGraphPath first = shortest(graph);
    RailGraphPath second =
        new RailGraphPathFinder()
            .shortestPath(graph, A, D, RailGraphPathFinder.Options.shortestDistance())
            .orElseThrow();

    assertEquals(VIA_B, first.nodes());
    assertSame(first, second);
  }

  @Test
  void overrideBlockedEdgeIsNotServedFromPlainSnapshotMemo() {
    SimpleRailGraph graph = diamond(Set.of());
    assertEquals(VIA_B, shortest(graph).nodes());

    EdgeId bd = EdgeId.undirected(B, D);
    EdgeOverrideRailGraph blocked =
        new EdgeOverrideRailGraph(graph, Map.of(bd, block(bd, Optional.empty(), true)), NOW);

    assertEquals(VIA_C, shortest(blocked).nodes());
    assertEquals(VIA_B, shortest(new EdgeOverrideRailGraph(graph, Map.of(), NOW)).nodes());
  }

  @Test
  void expiredTtlBlockMatchesUnblockedResult() {
    SimpleRailGraph graph = diamond(Set.of());
    EdgeId bd = EdgeId.undirected(B, D);
    Map<EdgeId, RailEdgeOverrideRecord> overrides =
        Map.of(bd, block(bd, Optional.of(NOW.plusSeconds(60)), false));

    assertEquals(VIA_C, shortest(new EdgeOverrideRailGraph(graph, overrides, NOW)).nodes());
    assertEquals(
        VIA_B, shortest(new EdgeOverrideRailGraph(graph, overrides, NOW.plusSeconds(61))).nodes());
  }

  @Test
  void viewKeepsOverridesAsOfConstruction() {
    SimpleRailGraph graph = diamond(Set.of());
    EdgeId bd = EdgeId.undirected(B, D);
    Map<EdgeId, RailEdgeOverrideRecord> live = new HashMap<>();
    EdgeOverrideRailGraph before = new EdgeOverrideRailGraph(graph, live, NOW);

    live.put(bd, block(bd, Optional.empty(), true));

    assertFalse(before.isBlocked(bd));
    assertEquals(VIA_B, shortest(before).nodes());
    assertEquals(VIA_C, shortest(new EdgeOverrideRailGraph(graph, live, NOW)).nodes());
  }

  @Test
  void rebuiltSnapshotIsSearchedAgain() {
    assertEquals(VIA_B, shortest(diamond(Set.of())).nodes());

    SimpleRailGraph rebuilt = diamond(Set.of(EdgeId.undirected(A, B)));

    assertEquals(VIA_C, shortest(rebuilt).nodes());
  }

  @Test
  void unreachableResultIsRememberedAsEmpty() {
    SimpleRailGraph graph = new SimpleRailGraph(Map.of(A, node(A), D, node(D)), Map.of(), Set.of());

    assertTrue(
        finder.shortestPath(graph, A, D, RailGraphPathFinder.Options.shortestDistance()).isEmpty());
    assertTrue(
        finder.shortestPath(graph, A, D, RailGraphPathFinder.Options.shortestDistance()).isEmpty());
  }

  private RailGraphPath shortest(org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph graph) {
    return finder
        .shortestPath(graph, A, D, RailGraphPathFinder.Options.shortestDistance())
        .orElseThrow();
  }

  /** A–B–D 短（2+2），A–C–D 长（3+3）。 */
  private static SimpleRailGraph diamond(Set<EdgeId> blocked) {
    Map<NodeId, RailNode> nodes = new HashMap<>();
    for (NodeId id : List.of(A, B, C, D)) {
      nodes.put(id, node(id));
    }
    Map<EdgeId, RailEdge> edges = new HashMap<>();
    for (RailEdge edge : List.of(edge(A, B, 2), edge(B, D, 2), edge(A, C, 3), edge(C, D, 3))) {
      edges.put(edge.id(), edge);
    }
    return new SimpleRailGraph(nodes, edges, blocked);
  }

  private static RailNode node(NodeId id) {
    return new SignRailNode(
        id, NodeType.WAYPOINT, new Vector(0, 0, 0), Optional.empty(), Optional.empty());
  }

  private static RailEdge edge(NodeId a, NodeId b, int lengthBlocks) {
    EdgeId id = EdgeId.undirected(a, b);
    return new RailEdge(id, id.a(), id.b(), lengthBlocks, 0.0, true, Optional.empty());
  }

  private static RailEdgeOverrideRecord block(
      EdgeId id, Optional<Instant> blockedUntil, boolean manual) {
    return new RailEdgeOverrideRecord(
        UUID.randomUUID(),
        id,
        OptionalDouble.empty(),
        OptionalDouble.empty(),
        Optional.empty(),
        manual,
        blockedUntil,
        NOW);
  }
}
