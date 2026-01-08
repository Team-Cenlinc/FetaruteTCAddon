package org.fetarute.fetaruteTCAddon.dispatcher.graph.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SignRailNode;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.SimpleRailGraph;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.EdgeOverrideLister.Kind;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.EdgeOverrideLister.Query;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode;
import org.junit.jupiter.api.Test;

final class EdgeOverrideListerTest {

  @Test
  void filtersSpeedOverrides() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    UUID worldId = UUID.randomUUID();
    RailEdgeOverrideRecord speed =
        new RailEdgeOverrideRecord(
            worldId,
            EdgeId.undirected(NodeId.of("A"), NodeId.of("B")),
            OptionalDouble.of(8.0),
            OptionalDouble.empty(),
            Optional.empty(),
            false,
            Optional.empty(),
            now);
    RailEdgeOverrideRecord temp =
        new RailEdgeOverrideRecord(
            worldId,
            EdgeId.undirected(NodeId.of("B"), NodeId.of("C")),
            OptionalDouble.empty(),
            OptionalDouble.of(4.0),
            Optional.of(now.plusSeconds(60)),
            false,
            Optional.empty(),
            now);

    List<RailEdgeOverrideRecord> results =
        EdgeOverrideLister.filter(
            List.of(speed, temp), now, new Query(Kind.SPEED, false, Optional.empty()));
    assertEquals(1, results.size());
    assertEquals("A", results.get(0).edgeId().a().value());
    assertEquals("B", results.get(0).edgeId().b().value());
  }

  @Test
  void filtersRestrictActiveByDefaultAndCanIncludeInactive() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    UUID worldId = UUID.randomUUID();
    RailEdgeOverrideRecord active =
        new RailEdgeOverrideRecord(
            worldId,
            EdgeId.undirected(NodeId.of("A"), NodeId.of("B")),
            OptionalDouble.empty(),
            OptionalDouble.of(4.0),
            Optional.of(now.plusSeconds(60)),
            false,
            Optional.empty(),
            now);
    RailEdgeOverrideRecord expired =
        new RailEdgeOverrideRecord(
            worldId,
            EdgeId.undirected(NodeId.of("B"), NodeId.of("C")),
            OptionalDouble.empty(),
            OptionalDouble.of(4.0),
            Optional.of(now.minusSeconds(1)),
            false,
            Optional.empty(),
            now);

    List<RailEdgeOverrideRecord> activeOnly =
        EdgeOverrideLister.filter(
            List.of(active, expired), now, new Query(Kind.RESTRICT, false, Optional.empty()));
    assertEquals(1, activeOnly.size());

    List<RailEdgeOverrideRecord> includeInactive =
        EdgeOverrideLister.filter(
            List.of(active, expired), now, new Query(Kind.RESTRICT, true, Optional.empty()));
    assertEquals(2, includeInactive.size());
  }

  @Test
  void filtersBlockActiveByDefaultAndCanIncludeInactive() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    UUID worldId = UUID.randomUUID();
    RailEdgeOverrideRecord manual =
        new RailEdgeOverrideRecord(
            worldId,
            EdgeId.undirected(NodeId.of("A"), NodeId.of("B")),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            Optional.empty(),
            true,
            Optional.empty(),
            now);
    RailEdgeOverrideRecord ttlExpired =
        new RailEdgeOverrideRecord(
            worldId,
            EdgeId.undirected(NodeId.of("B"), NodeId.of("C")),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            Optional.empty(),
            false,
            Optional.of(now.minusSeconds(1)),
            now);

    List<RailEdgeOverrideRecord> activeOnly =
        EdgeOverrideLister.filter(
            List.of(manual, ttlExpired), now, new Query(Kind.BLOCK, false, Optional.empty()));
    assertEquals(1, activeOnly.size());

    List<RailEdgeOverrideRecord> includeInactive =
        EdgeOverrideLister.filter(
            List.of(manual, ttlExpired), now, new Query(Kind.BLOCK, true, Optional.empty()));
    assertEquals(2, includeInactive.size());
  }

  @Test
  void filtersByNodeIdWhenProvided() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    UUID worldId = UUID.randomUUID();
    RailEdgeOverrideRecord ab =
        new RailEdgeOverrideRecord(
            worldId,
            EdgeId.undirected(NodeId.of("A"), NodeId.of("B")),
            OptionalDouble.of(8.0),
            OptionalDouble.empty(),
            Optional.empty(),
            false,
            Optional.empty(),
            now);
    RailEdgeOverrideRecord cd =
        new RailEdgeOverrideRecord(
            worldId,
            EdgeId.undirected(NodeId.of("C"), NodeId.of("D")),
            OptionalDouble.of(8.0),
            OptionalDouble.empty(),
            Optional.empty(),
            false,
            Optional.empty(),
            now);

    List<RailEdgeOverrideRecord> results =
        EdgeOverrideLister.filter(
            List.of(ab, cd), now, new Query(Kind.SPEED, false, Optional.of(NodeId.of("B"))));
    assertEquals(1, results.size());
    EdgeId id = EdgeId.undirected(results.get(0).edgeId().a(), results.get(0).edgeId().b());
    assertEquals("A", id.a().value());
    assertEquals("B", id.b().value());
  }

  @Test
  void detectsOrphanOverridesAgainstGraphEdges() {
    EdgeId ab = EdgeId.undirected(NodeId.of("A"), NodeId.of("B"));
    RailGraph graph = graph(ab);

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    UUID worldId = UUID.randomUUID();
    RailEdgeOverrideRecord keep =
        new RailEdgeOverrideRecord(
            worldId,
            ab,
            OptionalDouble.of(8.0),
            OptionalDouble.empty(),
            Optional.empty(),
            false,
            Optional.empty(),
            now);
    RailEdgeOverrideRecord orphan =
        new RailEdgeOverrideRecord(
            worldId,
            EdgeId.undirected(NodeId.of("A"), NodeId.of("C")),
            OptionalDouble.of(8.0),
            OptionalDouble.empty(),
            Optional.empty(),
            false,
            Optional.empty(),
            now);

    List<RailEdgeOverrideRecord> orphans =
        EdgeOverrideLister.orphanOverrides(List.of(keep, orphan), graph);
    assertEquals(1, orphans.size());
    EdgeId orphanId = EdgeId.undirected(orphans.get(0).edgeId().a(), orphans.get(0).edgeId().b());
    assertEquals("A", orphanId.a().value());
    assertEquals("C", orphanId.b().value());
  }

  private static RailGraph graph(EdgeId edgeId) {
    java.util.Map<NodeId, RailNode> nodesById =
        java.util.Map.of(
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
    return new SimpleRailGraph(nodesById, java.util.Map.of(edgeId, edge), java.util.Set.of());
  }
}
