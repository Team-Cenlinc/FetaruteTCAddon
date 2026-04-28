package org.fetarute.fetaruteTCAddon.dispatcher.graph.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
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
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.SectionSpeedService.SectionSpeedChange;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.control.SectionSpeedService.SectionSpeedPlan;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.persist.RailEdgeOverrideRecord;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeType;
import org.junit.jupiter.api.Test;

final class SectionSpeedServiceTest {

  @Test
  void plansShortestSectionPath() {
    RailGraph graph =
        graph(
            Set.of(node("A"), node("B"), node("C")),
            Set.of(edge("A", "B", 10), edge("A", "C", 3), edge("C", "B", 4)));
    SectionSpeedService service = new SectionSpeedService();

    SectionSpeedPlan plan = service.plan(graph, NodeId.of("A"), NodeId.of("B")).orElseThrow();

    assertEquals(List.of(NodeId.of("A"), NodeId.of("C"), NodeId.of("B")), plan.nodes());
    assertEquals(List.of(edgeId("A", "C"), edgeId("C", "B")), plan.edgeIds());
    assertEquals(7L, plan.totalLengthBlocks());
  }

  @Test
  void temporarySetPreservesManualSpeedAndBlockFields() {
    UUID worldId = UUID.randomUUID();
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    SectionSpeedPlan plan = plan("A", "B");
    RailEdgeOverrideRecord current =
        new RailEdgeOverrideRecord(
            worldId,
            edgeId("A", "B"),
            OptionalDouble.of(12.0),
            OptionalDouble.empty(),
            Optional.empty(),
            true,
            Optional.of(now.plusSeconds(30)),
            now);

    SectionSpeedChange change =
        new SectionSpeedService()
            .buildSetChange(
                worldId,
                plan,
                RailSpeed.ofBlocksPerSecond(5.0),
                Optional.of(now.plusSeconds(60)),
                Map.of(edgeId("A", "B"), current),
                now);

    assertEquals(1, change.upserts().size());
    RailEdgeOverrideRecord next = change.upserts().get(0);
    assertEquals(12.0, next.speedLimitBlocksPerSecond().getAsDouble(), 1e-9);
    assertEquals(5.0, next.tempSpeedLimitBlocksPerSecond().getAsDouble(), 1e-9);
    assertEquals(Optional.of(now.plusSeconds(60)), next.tempSpeedLimitUntil());
    assertTrue(next.blockedManual());
    assertEquals(Optional.of(now.plusSeconds(30)), next.blockedUntil());
  }

  @Test
  void clearDeletesEmptySpeedRowsAndPreservesBlockedRows() {
    UUID worldId = UUID.randomUUID();
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    SectionSpeedPlan plan = plan("A", "B", "C");
    RailEdgeOverrideRecord speedOnly =
        new RailEdgeOverrideRecord(
            worldId,
            edgeId("A", "B"),
            OptionalDouble.of(8.0),
            OptionalDouble.empty(),
            Optional.empty(),
            false,
            Optional.empty(),
            now);
    RailEdgeOverrideRecord speedAndBlock =
        new RailEdgeOverrideRecord(
            worldId,
            edgeId("B", "C"),
            OptionalDouble.of(6.0),
            OptionalDouble.empty(),
            Optional.empty(),
            true,
            Optional.empty(),
            now);

    SectionSpeedChange change =
        new SectionSpeedService()
            .buildClearChange(
                worldId,
                plan,
                Map.of(edgeId("A", "B"), speedOnly, edgeId("B", "C"), speedAndBlock),
                now);

    assertEquals(List.of(edgeId("A", "B")), change.deletes());
    assertEquals(1, change.upserts().size());
    RailEdgeOverrideRecord preserved = change.upserts().get(0);
    assertEquals(edgeId("B", "C"), preserved.edgeId());
    assertTrue(preserved.speedLimitBlocksPerSecond().isEmpty());
    assertTrue(preserved.blockedManual());
    assertEquals(2, change.touchedEdges());
  }

  private static SectionSpeedPlan plan(String... nodes) {
    List<NodeId> nodeIds = java.util.Arrays.stream(nodes).map(NodeId::of).toList();
    List<RailEdge> edges = new java.util.ArrayList<>();
    List<EdgeId> edgeIds = new java.util.ArrayList<>();
    for (int i = 0; i < nodeIds.size() - 1; i++) {
      EdgeId edgeId = EdgeId.undirected(nodeIds.get(i), nodeIds.get(i + 1));
      edges.add(new RailEdge(edgeId, edgeId.a(), edgeId.b(), 1, 0.0, true, Optional.empty()));
      edgeIds.add(edgeId);
    }
    return new SectionSpeedPlan(
        nodeIds.get(0), nodeIds.get(nodeIds.size() - 1), nodeIds, edges, edgeIds, edgeIds.size());
  }

  private static RailGraph graph(
      Set<org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodes, Set<RailEdge> edges) {
    Map<NodeId, org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode> nodesById =
        new java.util.HashMap<>();
    for (org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode node : nodes) {
      nodesById.put(node.id(), node);
    }
    Map<EdgeId, RailEdge> edgesById = new java.util.HashMap<>();
    for (RailEdge edge : edges) {
      edgesById.put(edge.id(), edge);
    }
    return new SimpleRailGraph(nodesById, edgesById, Set.of());
  }

  private static org.fetarute.fetaruteTCAddon.dispatcher.node.RailNode node(String id) {
    return new SignRailNode(
        NodeId.of(id), NodeType.WAYPOINT, new Vector(0, 0, 0), Optional.empty(), Optional.empty());
  }

  private static RailEdge edge(String a, String b, int lengthBlocks) {
    EdgeId id = edgeId(a, b);
    return new RailEdge(id, id.a(), id.b(), lengthBlocks, 0.0, true, Optional.empty());
  }

  private static EdgeId edgeId(String a, String b) {
    return EdgeId.undirected(NodeId.of(a), NodeId.of(b));
  }
}
