package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
        new OccupancyRequest(
            "train-A", Optional.empty(), now, List.of(resource), java.util.Map.of());
    OccupancyDecision decision = manager.acquire(request);
    assertTrue(decision.allowed());

    OccupancyRequest otherRequest =
        new OccupancyRequest(
            "train-B", Optional.empty(), now, List.of(resource), java.util.Map.of());
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
        new OccupancyRequest(
            "train-A", Optional.empty(), now, List.of(resource), java.util.Map.of());
    manager.acquire(request);

    manager.releaseByTrain("train-A");
    OccupancyDecision decision =
        manager.canEnter(
            new OccupancyRequest(
                "train-B", Optional.empty(), now, List.of(resource), java.util.Map.of()));
    assertTrue(decision.allowed());
  }

  @Test
  void singleCorridorAllowsSameDirection() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource resource = OccupancyResource.forConflict("single:comp:A~B");
    Map<String, CorridorDirection> forward = Map.of(resource.key(), CorridorDirection.A_TO_B);

    OccupancyDecision first =
        manager.acquire(
            new OccupancyRequest("t1", Optional.empty(), now, List.of(resource), forward));
    assertTrue(first.allowed());

    OccupancyDecision second =
        manager.canEnter(
            new OccupancyRequest("t2", Optional.empty(), now, List.of(resource), forward));
    assertTrue(second.allowed());
  }

  @Test
  void singleCorridorBlocksOppositeDirection() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource resource = OccupancyResource.forConflict("single:comp:A~B");
    Map<String, CorridorDirection> forward = Map.of(resource.key(), CorridorDirection.A_TO_B);
    Map<String, CorridorDirection> reverse = Map.of(resource.key(), CorridorDirection.B_TO_A);

    OccupancyDecision first =
        manager.acquire(
            new OccupancyRequest("t1", Optional.empty(), now, List.of(resource), forward));
    assertTrue(first.allowed());

    OccupancyDecision second =
        manager.canEnter(
            new OccupancyRequest("t2", Optional.empty(), now, List.of(resource), reverse));
    assertFalse(second.allowed());
    assertEquals(SignalAspect.STOP, second.signal());
  }

  @Test
  void queueBlocksSwitcherByOrder() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource resource = OccupancyResource.forConflict("switcher:SW-1");

    OccupancyDecision first =
        manager.canEnter(
            new OccupancyRequest("t1", Optional.empty(), now, List.of(resource), Map.of()));
    assertTrue(first.allowed());

    OccupancyDecision second =
        manager.canEnter(
            new OccupancyRequest("t2", Optional.empty(), now, List.of(resource), Map.of()));
    assertFalse(second.allowed());
  }

  @Test
  void queuePrefersEarliestSingleCorridorEntry() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource resource = OccupancyResource.forConflict("single:comp:A~B");
    Map<String, CorridorDirection> forward = Map.of(resource.key(), CorridorDirection.A_TO_B);
    Map<String, CorridorDirection> reverse = Map.of(resource.key(), CorridorDirection.B_TO_A);

    OccupancyDecision first =
        manager.canEnter(
            new OccupancyRequest("t1", Optional.empty(), now, List.of(resource), forward));
    assertTrue(first.allowed());

    OccupancyDecision second =
        manager.canEnter(
            new OccupancyRequest("t2", Optional.empty(), now, List.of(resource), reverse));
    assertFalse(second.allowed());
  }

  @Test
  void shouldYieldForHigherPriorityOppositeCorridor() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource resource = OccupancyResource.forConflict("single:comp:A~B");
    Map<String, CorridorDirection> forward = Map.of(resource.key(), CorridorDirection.A_TO_B);
    Map<String, CorridorDirection> reverse = Map.of(resource.key(), CorridorDirection.B_TO_A);

    OccupancyRequest high =
        new OccupancyRequest("fast", Optional.empty(), now, List.of(resource), reverse, 10);
    manager.canEnter(high);

    OccupancyRequest low =
        new OccupancyRequest("slow", Optional.empty(), now, List.of(resource), forward, 1);
    assertTrue(manager.shouldYield(low));
  }

  @Test
  void shouldNotYieldForHigherPrioritySameDirection() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource resource = OccupancyResource.forConflict("single:comp:A~B");
    Map<String, CorridorDirection> forward = Map.of(resource.key(), CorridorDirection.A_TO_B);

    OccupancyRequest high =
        new OccupancyRequest("fast", Optional.empty(), now, List.of(resource), forward, 10);
    manager.canEnter(high);

    OccupancyRequest low =
        new OccupancyRequest("slow", Optional.empty(), now, List.of(resource), forward, 1);
    assertFalse(manager.shouldYield(low));
  }

  @Test
  void shouldYieldForHigherPrioritySwitcher() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource resource = OccupancyResource.forConflict("switcher:SW-1");

    OccupancyRequest high =
        new OccupancyRequest("fast", Optional.empty(), now, List.of(resource), Map.of(), 5);
    manager.canEnter(high);

    OccupancyRequest low =
        new OccupancyRequest("slow", Optional.empty(), now, List.of(resource), Map.of(), 1);
    assertTrue(manager.shouldYield(low));
  }
}
