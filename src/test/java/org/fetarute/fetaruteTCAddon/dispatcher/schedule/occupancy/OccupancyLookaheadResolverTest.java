package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailEdge;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.junit.jupiter.api.Test;

class OccupancyLookaheadResolverTest {

  @Test
  void returnsEmptyWhenOnlyConflictBlocked() {
    OccupancyDecision decision =
        new OccupancyDecision(
            false,
            Instant.EPOCH,
            SignalAspect.CAUTION,
            List.of(
                new OccupancyClaim(
                    OccupancyResource.forConflict("switcher:dummy"),
                    "t1",
                    Optional.of(new RouteId("R1")),
                    Instant.EPOCH,
                    Duration.ZERO)));
    OccupancyRequestContext context = contextForTwoEdges(10, 20);

    OptionalLong distance = OccupancyLookaheadResolver.resolveBlockerDistance(decision, context);

    assertTrue(distance.isEmpty());
  }

  @Test
  void returnsDistanceForEdgeBlocker() {
    OccupancyRequestContext context = contextForTwoEdges(10, 20);
    RailEdge edge = context.edges().get(1);
    OccupancyDecision decision =
        new OccupancyDecision(
            false,
            Instant.EPOCH,
            SignalAspect.CAUTION,
            List.of(
                new OccupancyClaim(
                    OccupancyResource.forEdge(edge.id()),
                    "t1",
                    Optional.empty(),
                    Instant.EPOCH,
                    Duration.ZERO)));

    OptionalLong distance = OccupancyLookaheadResolver.resolveBlockerDistance(decision, context);

    assertEquals(10L, distance.orElseThrow());
  }

  @Test
  void returnsDistanceForNodeBlocker() {
    OccupancyRequestContext context = contextForTwoEdges(10, 20);
    NodeId node = context.pathNodes().get(2);
    OccupancyDecision decision =
        new OccupancyDecision(
            false,
            Instant.EPOCH,
            SignalAspect.CAUTION,
            List.of(
                new OccupancyClaim(
                    OccupancyResource.forNode(node),
                    "t1",
                    Optional.empty(),
                    Instant.EPOCH,
                    Duration.ZERO)));

    OptionalLong distance = OccupancyLookaheadResolver.resolveBlockerDistance(decision, context);

    assertEquals(30L, distance.orElseThrow());
  }

  @Test
  void picksNearestBlocker() {
    OccupancyRequestContext context = contextForTwoEdges(10, 20);
    NodeId node = context.pathNodes().get(1);
    RailEdge edge = context.edges().get(1);
    OccupancyDecision decision =
        new OccupancyDecision(
            false,
            Instant.EPOCH,
            SignalAspect.CAUTION,
            List.of(
                new OccupancyClaim(
                    OccupancyResource.forEdge(edge.id()),
                    "t1",
                    Optional.empty(),
                    Instant.EPOCH,
                    Duration.ZERO),
                new OccupancyClaim(
                    OccupancyResource.forNode(node),
                    "t2",
                    Optional.empty(),
                    Instant.EPOCH,
                    Duration.ZERO)));

    OptionalLong distance = OccupancyLookaheadResolver.resolveBlockerDistance(decision, context);

    assertEquals(10L, distance.orElseThrow());
  }

  private OccupancyRequestContext contextForTwoEdges(int firstLength, int secondLength) {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    NodeId c = NodeId.of("C");
    RailEdge edge1 =
        new RailEdge(EdgeId.undirected(a, b), a, b, firstLength, -1.0, true, Optional.empty());
    RailEdge edge2 =
        new RailEdge(EdgeId.undirected(b, c), b, c, secondLength, -1.0, true, Optional.empty());
    OccupancyRequest request =
        new OccupancyRequest("t1", Optional.empty(), Instant.EPOCH, List.of());
    return new OccupancyRequestContext(request, List.of(a, b, c), List.of(edge1, edge2));
  }
}
