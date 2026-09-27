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

  /**
   * 实服 WS LWN:2→SWN:2 的开头：站台 26 格（22.2）→ 道岔 48 格（默认 8）→ 主线（22.2）。
   *
   * <p>列车在站台边上，基准是"刹得住那条 8 格/秒道岔边"的速度，而不是整段最小的 8。
   */
  private static final List<RailEdge> LWN_TO_SWN =
      List.of(
          edge(NodeId.of("S:LWN:2"), NodeId.of("SW887"), 26),
          edge(NodeId.of("SW887"), NodeId.of("LWN:SWN:2:001"), 48),
          edge(NodeId.of("LWN:SWN:2:001"), NodeId.of("LWN:SWN:2:002"), 53),
          edge(NodeId.of("LWN:SWN:2:002"), NodeId.of("S:SWN:2"), 600));

  private static double lwnLimit(RailEdge edge) {
    return edge.to().value().equals("LWN:SWN:2:001") ? 8.0 : 22.2;
  }

  @Test
  void pathSpeedEnvelopeBrakesForTheSlowEdgeAheadInsteadOfCappingTheWholeSegment() {
    double envelope =
        SignalLookahead.pathSpeedEnvelope(LWN_TO_SWN, SignalLookaheadTest::lwnLimit, 1.0, 0L)
            .orElseThrow();

    assertEquals(Math.sqrt(8.0 * 8.0 + 2.0 * 1.0 * 26), envelope, 1.0e-9);
  }

  @Test
  void pathSpeedEnvelopeMeasuresTheSlowEdgeFromTheHead() {
    double atNode =
        SignalLookahead.pathSpeedEnvelope(LWN_TO_SWN, SignalLookaheadTest::lwnLimit, 1.0, 0L)
            .orElseThrow();
    double headPastNode =
        SignalLookahead.pathSpeedEnvelope(LWN_TO_SWN, SignalLookaheadTest::lwnLimit, 1.0, 20L)
            .orElseThrow();
    double headAtSlowEdge =
        SignalLookahead.pathSpeedEnvelope(LWN_TO_SWN, SignalLookaheadTest::lwnLimit, 1.0, 40L)
            .orElseThrow();

    assertEquals(Math.sqrt(8.0 * 8.0 + 2.0 * 1.0 * 6), headPastNode, 1.0e-9);
    assertTrue(headPastNode < atNode);
    assertEquals(8.0, headAtSlowEdge, 1.0e-9, "车头已到慢速边，距离按 0 计，不会变成负数");
  }

  @Test
  void pathSpeedEnvelopeReturnsTheCurrentEdgeLimitOnceTheSlowEdgeIsBehind() {
    List<RailEdge> pastTheSwitch = LWN_TO_SWN.subList(2, LWN_TO_SWN.size());

    assertEquals(
        22.2,
        SignalLookahead.pathSpeedEnvelope(pastTheSwitch, SignalLookaheadTest::lwnLimit, 1.0, 0L)
            .orElseThrow(),
        1.0e-9);
  }

  @Test
  void pathSpeedEnvelopeWithoutBrakingFallsBackToTheSegmentMinimum() {
    assertEquals(
        8.0,
        SignalLookahead.pathSpeedEnvelope(LWN_TO_SWN, SignalLookaheadTest::lwnLimit, 0.0, 0L)
            .orElseThrow(),
        1.0e-9);
    assertEquals(
        8.0,
        SignalLookahead.pathSpeedEnvelope(LWN_TO_SWN, SignalLookaheadTest::lwnLimit, Double.NaN, 0L)
            .orElseThrow(),
        1.0e-9);
  }

  @Test
  void pathSpeedEnvelopeKeepsTheCurrentEdgeLimitWhateverTheHeadProgress() {
    List<RailEdge> slowFirst = LWN_TO_SWN.subList(1, LWN_TO_SWN.size());

    assertEquals(
        8.0,
        SignalLookahead.pathSpeedEnvelope(slowFirst, SignalLookaheadTest::lwnLimit, 1.0, 30L)
            .orElseThrow(),
        1.0e-9);
  }

  @Test
  void pathSpeedEnvelopeIgnoresEdgesWithoutAValidLimit() {
    assertTrue(SignalLookahead.pathSpeedEnvelope(List.of(), edge -> 10.0, 1.0, 0L).isEmpty());
    assertTrue(SignalLookahead.pathSpeedEnvelope(LWN_TO_SWN, edge -> 0.0, 1.0, 0L).isEmpty());
    assertEquals(
        22.2,
        SignalLookahead.pathSpeedEnvelope(
                LWN_TO_SWN,
                edge -> edge.to().value().equals("LWN:SWN:2:001") ? Double.NaN : 22.2,
                1.0,
                0L)
            .orElseThrow(),
        1.0e-9);
  }
}
