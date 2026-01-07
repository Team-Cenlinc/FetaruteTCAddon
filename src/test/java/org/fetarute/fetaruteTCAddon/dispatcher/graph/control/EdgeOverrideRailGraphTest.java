package org.fetarute.fetaruteTCAddon.dispatcher.graph.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.junit.jupiter.api.Test;

final class EdgeOverrideRailGraphTest {

  @Test
  void delegatesToUnderlyingGraphWhenNoOverrides() {
    EdgeId edgeId = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailGraph base = graph(edgeId, false);
    RailGraph wrapped = new EdgeOverrideRailGraph(base, Map.of(), Instant.EPOCH);
    assertFalse(wrapped.isBlocked(edgeId));
  }

  @Test
  void treatsManualBlockAsBlocked() {
    EdgeId edgeId = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailGraph base = graph(edgeId, false);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    RailEdgeOverrideRecord override =
        new RailEdgeOverrideRecord(
            UUID.randomUUID(),
            edgeId,
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            Optional.empty(),
            true,
            Optional.empty(),
            now);
    RailGraph wrapped = new EdgeOverrideRailGraph(base, Map.of(edgeId, override), now);
    assertTrue(wrapped.isBlocked(edgeId));
  }

  @Test
  void normalizesEdgeIdWhenCheckingBlock() {
    EdgeId edgeId = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailGraph base = graph(edgeId, false);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    RailEdgeOverrideRecord override =
        new RailEdgeOverrideRecord(
            UUID.randomUUID(),
            edgeId,
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            Optional.empty(),
            true,
            Optional.empty(),
            now);
    RailGraph wrapped = new EdgeOverrideRailGraph(base, Map.of(edgeId, override), now);
    assertTrue(wrapped.isBlocked(new EdgeId(NodeId.of("B"), NodeId.of("A"))));
  }

  @Test
  void treatsTtlBlockAsBlockedOnlyWhenActive() {
    EdgeId edgeId = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailGraph base = graph(edgeId, false);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    RailEdgeOverrideRecord override =
        new RailEdgeOverrideRecord(
            UUID.randomUUID(),
            edgeId,
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            Optional.empty(),
            false,
            Optional.of(now.plusSeconds(60)),
            now);
    RailGraph active = new EdgeOverrideRailGraph(base, Map.of(edgeId, override), now);
    RailGraph expired =
        new EdgeOverrideRailGraph(base, Map.of(edgeId, override), now.plusSeconds(120));

    assertTrue(active.isBlocked(edgeId));
    assertFalse(expired.isBlocked(edgeId));
  }

  @Test
  void alwaysRespectsUnderlyingBlockedEdges() {
    EdgeId edgeId = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailGraph base = graph(edgeId, true);
    RailGraph wrapped = new EdgeOverrideRailGraph(base, Map.of(), Instant.EPOCH);
    assertTrue(wrapped.isBlocked(edgeId));
  }

  private static RailGraph graph(EdgeId edgeId, boolean blocked) {
    Map<NodeId, RailNode> nodesById =
        Map.of(
            edgeId.a(),
            new SignRailNode(
                edgeId.a(),
                NodeType.WAYPOINT,
                new Vector(0, 0, 0),
                Optional.empty(),
                Optional.empty()),
            edgeId.b(),
            new SignRailNode(
                edgeId.b(),
                NodeType.WAYPOINT,
                new Vector(0, 0, 0),
                Optional.empty(),
                Optional.empty()));
    RailEdge edge = new RailEdge(edgeId, edgeId.a(), edgeId.b(), 10, 0.0, true, Optional.empty());
    Set<EdgeId> blockedEdges = blocked ? Set.of(edgeId) : Set.of();
    return new SimpleRailGraph(nodesById, Map.of(edgeId, edge), blockedEdges);
  }
}
