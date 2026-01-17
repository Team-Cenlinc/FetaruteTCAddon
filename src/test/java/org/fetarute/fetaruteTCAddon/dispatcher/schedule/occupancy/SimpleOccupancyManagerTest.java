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
  void acquireBlocksOtherTrainsUntilRelease() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ofSeconds(10);
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource resource =
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("A"), NodeId.of("B")));
    OccupancyRequest request =
        new OccupancyRequest("train-A", Optional.empty(), now, List.of(resource));
    OccupancyDecision decision = manager.acquire(request);
    assertTrue(decision.allowed());

    OccupancyRequest otherRequest =
        new OccupancyRequest("train-B", Optional.empty(), now, List.of(resource));
    OccupancyDecision otherDecision = manager.canEnter(otherRequest);
    assertFalse(otherDecision.allowed());
    assertEquals(now, otherDecision.earliestTime());
    assertEquals(SignalAspect.STOP, otherDecision.signal());
  }

  @Test
  void releaseByTrainClearsClaims() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("NODE-1"));
    OccupancyRequest request =
        new OccupancyRequest("train-A", Optional.empty(), now, List.of(resource));
    manager.acquire(request);

    manager.releaseByTrain("train-A");
    OccupancyDecision decision =
        manager.canEnter(new OccupancyRequest("train-B", Optional.empty(), now, List.of(resource)));
    assertTrue(decision.allowed());
  }
}
