package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.SignalLookahead.LookaheadResult;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequestContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

/** SignalLookahead 单元测试。 */
class SignalLookaheadTest {

  private static RailEdge edge(NodeId from, NodeId to, int length) {
    return new RailEdge(EdgeId.undirected(from, to), from, to, length, 0.0, true, Optional.empty());
  }

  private static OccupancyRequest emptyRequest() {
    return new OccupancyRequest("train1", Optional.empty(), Instant.now(), List.of(), Map.of(), 0);
  }

  private static OccupancyClaim claim(OccupancyResource resource, String trainName) {
    return new OccupancyClaim(
        resource, trainName, Optional.empty(), Instant.now(), Duration.ZERO, Optional.empty());
  }

  @Test
  void compute_emptyContext_returnsEmptyResult() {
    OccupancyDecision decision =
        new OccupancyDecision(true, Instant.now(), SignalAspect.PROCEED, List.of());
    OccupancyRequestContext context =
        new OccupancyRequestContext(emptyRequest(), List.of(), List.of());

    LookaheadResult result =
        SignalLookahead.compute(decision, context, SignalAspect.PROCEED, node -> false);

    assertEquals(SignalAspect.PROCEED, result.effectiveSignal());
    assertTrue(result.distanceToBlocker().isEmpty());
    assertTrue(result.distanceToCaution().isEmpty());
    assertTrue(result.distanceToApproach().isEmpty());
    assertTrue(result.minConstraintDistance().isEmpty());
  }

  @Test
  void compute_withBlocker_returnsBlockerDistance() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
    NodeId nodeC = NodeId.of("C");
    RailEdge edge1 = edge(nodeA, nodeB, 100);
    RailEdge edge2 = edge(nodeB, nodeC, 50);

    OccupancyResource blockedResource = OccupancyResource.forNode(nodeC);
    OccupancyClaim blocker = claim(blockedResource, "other-train");
    OccupancyDecision decision =
        new OccupancyDecision(false, Instant.now(), SignalAspect.STOP, List.of(blocker));
    OccupancyRequestContext context =
        new OccupancyRequestContext(
            emptyRequest(), List.of(nodeA, nodeB, nodeC), List.of(edge1, edge2));

    LookaheadResult result =
        SignalLookahead.compute(decision, context, SignalAspect.STOP, node -> false);

    assertTrue(result.distanceToBlocker().isPresent());
    assertEquals(150, result.distanceToBlocker().getAsLong());
    assertEquals(SignalAspect.STOP, result.effectiveSignal());
  }

  @Test
  void compute_withApproachNode_returnsApproachDistance() {
    NodeId nodeA = NodeId.of("A");
    NodeId nodeB = NodeId.of("B");
    NodeId nodeC = NodeId.of("OP:S:Station:1");
    RailEdge edge1 = edge(nodeA, nodeB, 80);
    RailEdge edge2 = edge(nodeB, nodeC, 60);

    OccupancyDecision decision =
        new OccupancyDecision(true, Instant.now(), SignalAspect.PROCEED, List.of());
    OccupancyRequestContext context =
        new OccupancyRequestContext(
            emptyRequest(), List.of(nodeA, nodeB, nodeC), List.of(edge1, edge2));

    // nodeC 是 station 节点
    LookaheadResult result =
        SignalLookahead.compute(
            decision, context, SignalAspect.PROCEED, node -> node.value().contains(":S:"));

    assertTrue(result.distanceToApproach().isPresent());
    assertEquals(140, result.distanceToApproach().getAsLong());
    assertEquals(SignalAspect.PROCEED, result.effectiveSignal());
  }

  @Test
  void minConstraintDistance_returnsMinimumStopConstraint() {
    // minConstraintDistance 只考虑 blocker/caution，不含 approach
    LookaheadResult result =
        new LookaheadResult(
            OptionalLong.of(200), OptionalLong.of(150), OptionalLong.of(100), SignalAspect.PROCEED);

    // 最小是 caution=150，不是 approach=100
    assertEquals(150, result.minConstraintDistance().getAsLong());
  }

  @Test
  void minConstraintDistance_partialValues() {
    LookaheadResult result =
        new LookaheadResult(
            OptionalLong.of(200), OptionalLong.empty(), OptionalLong.empty(), SignalAspect.PROCEED);

    assertEquals(200, result.minConstraintDistance().getAsLong());
  }

  @Test
  void minStopConstraintDistance_excludesApproach() {
    LookaheadResult result =
        new LookaheadResult(
            OptionalLong.of(200),
            OptionalLong.of(150),
            OptionalLong.of(50), // approach 最近但不参与
            SignalAspect.PROCEED);

    // minStopConstraintDistance 不含 approach
    assertEquals(150, result.minStopConstraintDistance().getAsLong());
    // distanceToApproach 仍可单独访问
    assertEquals(50, result.distanceToApproach().getAsLong());
  }

  @Test
  void compute_nullInputs_returnsEmpty() {
    LookaheadResult result = SignalLookahead.compute(null, null, null, null);
    assertEquals(SignalAspect.STOP, result.effectiveSignal());
    assertTrue(result.minConstraintDistance().isEmpty());
  }
}
