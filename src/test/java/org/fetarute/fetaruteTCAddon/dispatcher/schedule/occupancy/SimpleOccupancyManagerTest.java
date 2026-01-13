package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

class SimpleOccupancyManagerTest {

  @Test
  void acquireBlocksOtherTrainsUntilReleasePlusHeadway() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ofSeconds(10);
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource resource =
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("A"), NodeId.of("B")));
    OccupancyRequest request =
        new OccupancyRequest(
            "train-A", Optional.empty(), now, Duration.ofSeconds(5), List.of(resource));
    OccupancyDecision decision = manager.acquire(request);
    assertTrue(decision.allowed());

    OccupancyRequest otherRequest =
        new OccupancyRequest(
            "train-B", Optional.empty(), now, Duration.ofSeconds(5), List.of(resource));
    OccupancyDecision otherDecision = manager.canEnter(otherRequest);
    assertFalse(otherDecision.allowed());
    assertEquals(now.plusSeconds(15), otherDecision.earliestTime());
    assertEquals(SignalAspect.CAUTION, otherDecision.signal());
  }

  @Test
  void cleanupExpiredRemovesClaimsAfterReleaseAndHeadway() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ofSeconds(3);
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("NODE-1"));
    OccupancyRequest request =
        new OccupancyRequest(
            "train-A", Optional.empty(), now, Duration.ofSeconds(4), List.of(resource));
    manager.acquire(request);

    assertEquals(0, manager.cleanupExpired(now.plusSeconds(6)));
    assertEquals(1, manager.cleanupExpired(now.plusSeconds(8)));
  }
}
