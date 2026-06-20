package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphConflictSupport;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.DirectedTraversalContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ExpandedPathPlan;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.MovementPlanSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.junit.jupiter.api.Test;

/** Entry lookahead 的 canonical expanded path 回归测试。 */
class EntryLookaheadEvaluatorTest {

  @Test
  void entryLookaheadUsesCanonicalExpandedPath() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    EdgeId ab = EdgeId.undirected(a, b);
    EdgeId bc = EdgeId.undirected(b, c);
    String zone = "single:test";
    MovementPlanSnapshot plan =
        plan(
            List.of(a, b, c),
            List.of(edge(ab, a, b), edge(bc, b, c)),
            Map.of(zone, CorridorDirection.A_TO_B));

    EntryLookaheadEvaluator.Result result =
        EntryLookaheadEvaluator.evaluate(
            plan, support(Map.of(ab, zone)), OccupancyResource.forConflict(zone), 1, 2);

    assertTrue(result.lookaheadUsesExpandedPath());
    assertEquals(2, result.lookaheadWindowNodeCount());
    assertEquals(-1, result.exitIndexBeforeExtension());
    assertTrue(result.extensionAttempted());
    assertEquals(2, result.exitIndexAfterExtension());
    assertTrue(result.exitFeasible());
    assertFalse(result.failClosed());
  }

  @Test
  void entryLookaheadExtendsUntilSingleExitBeforeFailClosed() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    EdgeId ab = EdgeId.undirected(a, b);
    EdgeId bc = EdgeId.undirected(b, c);
    String zone = "single:test";
    MovementPlanSnapshot plan =
        plan(
            List.of(a, b, c),
            List.of(edge(ab, a, b), edge(bc, b, c)),
            Map.of(zone, CorridorDirection.A_TO_B));

    EntryLookaheadEvaluator.Result result =
        EntryLookaheadEvaluator.evaluate(
            plan, support(Map.of(ab, zone, bc, zone)), OccupancyResource.forConflict(zone), 1, 2);

    assertTrue(result.extensionAttempted());
    assertEquals(-1, result.exitIndexAfterExtension());
    assertFalse(result.exitFeasible());
    assertTrue(result.failClosed());
    assertEquals("exit-not-visible-after-extension", result.failureReason());
  }

  @Test
  void entryLookaheadTreatsStationTargetAsSingleRegionBoundaryExit() {
    NodeId switcher = NodeId.of("SWITCHER:Towny:1:64:1");
    NodeId station = NodeId.of("OP:S:BOUNDARY:1");
    EdgeId edgeId = EdgeId.undirected(switcher, station);
    String zone = "single:test";
    MovementPlanSnapshot plan =
        plan(
            List.of(switcher, station),
            List.of(edge(edgeId, switcher, station)),
            Map.of(zone, CorridorDirection.A_TO_B));

    EntryLookaheadEvaluator.Result result =
        EntryLookaheadEvaluator.evaluate(
            plan, support(Map.of(edgeId, zone)), OccupancyResource.forConflict(zone), 1, 1);

    assertTrue(result.extensionAttempted());
    assertEquals(1, result.exitIndexAfterExtension());
    assertTrue(result.exitFeasible());
    assertFalse(result.failClosed());
    assertEquals("exit-is-target-boundary", result.failureReason());
  }

  @Test
  void entryLookaheadWithoutExitProofStillFailsClosed() {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    EdgeId edgeId = EdgeId.undirected(a, b);
    String zone = "single:test";
    MovementPlanSnapshot plan =
        plan(List.of(a, b), List.of(edge(edgeId, a, b)), Map.of(zone, CorridorDirection.A_TO_B));

    EntryLookaheadEvaluator.Result result =
        EntryLookaheadEvaluator.evaluate(
            plan, support(Map.of(edgeId, zone)), OccupancyResource.forConflict(zone), 1, 1);

    assertTrue(result.extensionAttempted());
    assertEquals(-1, result.exitIndexAfterExtension());
    assertFalse(result.exitFeasible());
    assertTrue(result.failClosed());
    assertEquals("exit-not-visible-after-extension", result.failureReason());
  }

  private static MovementPlanSnapshot plan(
      List<NodeId> nodes,
      List<DirectedTraversalContext.DirectedEdge> edges,
      Map<String, CorridorDirection> directions) {
    return new MovementPlanSnapshot(
        "train",
        Optional.of(RouteId.of("r")),
        0,
        Optional.of(nodes.get(0)),
        Optional.empty(),
        Optional.of(nodes.get(0)),
        Optional.of(nodes.get(1)),
        new ExpandedPathPlan(nodes, edges, directions, Map.of()),
        List.of(OccupancyResource.forConflict(directions.keySet().iterator().next())),
        1L,
        1L,
        "test");
  }

  private static DirectedTraversalContext.DirectedEdge edge(EdgeId edgeId, NodeId from, NodeId to) {
    return new DirectedTraversalContext.DirectedEdge(edgeId, from, to);
  }

  private static RailGraphConflictSupport support(Map<EdgeId, String> conflicts) {
    return edgeId -> Optional.ofNullable(conflicts.get(edgeId));
  }
}
