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
  void singleCorridorClaimKeepsDirectionWhenHoldRequestOmitsIt() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource resource = OccupancyResource.forConflict("single:comp:A~B");
    Map<String, CorridorDirection> forward = Map.of(resource.key(), CorridorDirection.A_TO_B);
    Map<String, CorridorDirection> reverse = Map.of(resource.key(), CorridorDirection.B_TO_A);

    assertTrue(
        manager
            .acquire(
                new OccupancyRequest("front", Optional.empty(), now, List.of(resource), forward))
            .allowed());
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "front", Optional.empty(), now.plusSeconds(1), List.of(resource), Map.of()))
            .allowed());

    OccupancyDecision sameDirection =
        manager.canEnter(
            new OccupancyRequest(
                "rear", Optional.empty(), now.plusSeconds(2), List.of(resource), forward));
    assertTrue(sameDirection.allowed());

    OccupancyDecision oppositeDirection =
        manager.canEnter(
            new OccupancyRequest(
                "opposite", Optional.empty(), now.plusSeconds(3), List.of(resource), reverse));
    assertFalse(oppositeDirection.allowed());
  }

  @Test
  void singleCorridorQueueDoesNotSerializeSameDirectionFollowing() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource resource = OccupancyResource.forConflict("single:comp:A~B");
    Map<String, CorridorDirection> forward = Map.of(resource.key(), CorridorDirection.A_TO_B);

    manager.touchQueues(
        new OccupancyRequest(
            "front",
            Optional.empty(),
            now,
            List.of(resource),
            forward,
            Map.of(resource.key(), 0),
            0));
    manager.touchQueues(
        new OccupancyRequest(
            "rear",
            Optional.empty(),
            now.plusSeconds(1),
            List.of(resource),
            forward,
            Map.of(resource.key(), 0),
            0));

    OccupancyDecision decision =
        manager.canEnter(
            new OccupancyRequest(
                "rear",
                Optional.empty(),
                now.plusSeconds(2),
                List.of(resource),
                forward,
                Map.of(resource.key(), 0),
                0));

    assertTrue(decision.allowed());
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
  void touchQueuesKeepsStoppedTrainAtQueueHeadWithoutClaimingResources() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource resource = OccupancyResource.forConflict("switcher:SW-1");

    OccupancyRequest frontWaiting =
        new OccupancyRequest(
            "front",
            Optional.empty(),
            now,
            List.of(resource),
            Map.of(),
            Map.of(resource.key(), 0),
            0);
    manager.touchQueues(frontWaiting);

    assertTrue(manager.getClaim(resource).isEmpty());

    OccupancyDecision rearDecision =
        manager.canEnter(
            new OccupancyRequest(
                "rear",
                Optional.empty(),
                now.plusSeconds(1),
                List.of(resource),
                Map.of(),
                Map.of(resource.key(), 0),
                0));
    assertFalse(rearDecision.allowed());

    OccupancyDecision frontDecision =
        manager.canEnter(
            new OccupancyRequest(
                "front",
                Optional.empty(),
                now.plusSeconds(2),
                List.of(resource),
                Map.of(),
                Map.of(resource.key(), 0),
                0));
    assertTrue(frontDecision.allowed());
  }

  @Test
  void previewDoesNotMutateQueueState() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource resource = OccupancyResource.forConflict("switcher:SW-1");

    OccupancyRequest frontWaiting =
        new OccupancyRequest(
            "front",
            Optional.empty(),
            now,
            List.of(resource),
            Map.of(),
            Map.of(resource.key(), 0),
            0);
    manager.touchQueues(frontWaiting);

    OccupancyDecision preview =
        manager.canEnterPreview(
            new OccupancyRequest(
                "rear",
                Optional.empty(),
                now.plusSeconds(1),
                List.of(resource),
                Map.of(),
                Map.of(resource.key(), 0),
                0));

    assertFalse(preview.allowed());
    assertEquals(SignalAspect.STOP, preview.signal());
    List<OccupancyQueueSnapshot> snapshots = manager.snapshotQueues();
    assertEquals(1, snapshots.size());
    assertEquals(1, snapshots.get(0).entries().size());
    assertEquals("front", snapshots.get(0).entries().get(0).trainName());
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
  void conflictDeadlockReleasePrefersNearerEntry() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyResource nodeA = OccupancyResource.forNode(NodeId.of("A"));
    OccupancyResource nodeB = OccupancyResource.forNode(NodeId.of("B"));
    Map<String, CorridorDirection> forward = Map.of(conflict.key(), CorridorDirection.A_TO_B);
    Map<String, CorridorDirection> reverse = Map.of(conflict.key(), CorridorDirection.B_TO_A);

    manager.acquire(
        new OccupancyRequest("tA", Optional.empty(), now, List.of(nodeA), java.util.Map.of()));
    manager.acquire(
        new OccupancyRequest("tB", Optional.empty(), now, List.of(nodeB), java.util.Map.of()));

    Map<String, Integer> farEntry = Map.of(conflict.key(), 2);
    Map<String, Integer> nearEntry = Map.of(conflict.key(), 0);

    OccupancyDecision farDecision =
        manager.canEnter(
            new OccupancyRequest(
                "tB",
                Optional.empty(),
                now,
                List.of(conflict, nodeB, nodeA),
                reverse,
                farEntry,
                0));
    assertFalse(farDecision.allowed());

    OccupancyDecision nearDecision =
        manager.canEnter(
            new OccupancyRequest(
                "tA",
                Optional.empty(),
                now,
                List.of(conflict, nodeA, nodeB),
                forward,
                nearEntry,
                0));
    assertTrue(nearDecision.allowed());
    assertTrue(nearDecision.conflictRelease());
  }

  @Test
  void conflictDeadlockReleaseScansLaterConflictWhenPrimaryDoesNotContainBlocker() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource nearConflict = OccupancyResource.forConflict("single:comp:NEAR");
    OccupancyResource farConflict = OccupancyResource.forConflict("single:comp:FAR");
    OccupancyResource nodeA = OccupancyResource.forNode(NodeId.of("A"));
    OccupancyResource nodeB = OccupancyResource.forNode(NodeId.of("B"));
    Map<String, CorridorDirection> requestDirections =
        Map.of(
            nearConflict.key(),
            CorridorDirection.A_TO_B,
            farConflict.key(),
            CorridorDirection.A_TO_B);
    Map<String, CorridorDirection> blockerDirections =
        Map.of(farConflict.key(), CorridorDirection.B_TO_A);

    manager.acquire(
        new OccupancyRequest("tReq", Optional.empty(), now, List.of(nodeA), java.util.Map.of()));
    manager.acquire(
        new OccupancyRequest(
            "tBlocker", Optional.empty(), now, List.of(nodeB), java.util.Map.of()));
    manager.canEnter(
        new OccupancyRequest(
            "tBlocker",
            Optional.empty(),
            now,
            List.of(farConflict),
            blockerDirections,
            Map.of(farConflict.key(), 5),
            0));

    OccupancyDecision decision =
        manager.canEnter(
            new OccupancyRequest(
                "tReq",
                Optional.empty(),
                now.plusSeconds(1),
                List.of(nearConflict, farConflict, nodeA, nodeB),
                requestDirections,
                Map.of(nearConflict.key(), 0, farConflict.key(), 2),
                0));

    assertTrue(decision.allowed());
    assertTrue(decision.conflictRelease());
  }

  @Test
  void conflictReleaseAcquireSkipsBlockedHardResources() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyResource nodeA = OccupancyResource.forNode(NodeId.of("A"));
    OccupancyResource nodeB = OccupancyResource.forNode(NodeId.of("B"));
    Map<String, CorridorDirection> forward = Map.of(conflict.key(), CorridorDirection.A_TO_B);
    Map<String, CorridorDirection> reverse = Map.of(conflict.key(), CorridorDirection.B_TO_A);

    manager.acquire(
        new OccupancyRequest("tReq", Optional.empty(), now, List.of(nodeA), java.util.Map.of()));
    manager.acquire(
        new OccupancyRequest(
            "tBlocker", Optional.empty(), now, List.of(nodeB), java.util.Map.of()));
    manager.canEnter(
        new OccupancyRequest(
            "tBlocker",
            Optional.empty(),
            now,
            List.of(conflict),
            reverse,
            Map.of(conflict.key(), 2),
            0));

    OccupancyDecision decision =
        manager.acquire(
            new OccupancyRequest(
                "tReq",
                Optional.empty(),
                now.plusSeconds(1),
                List.of(conflict, nodeA, nodeB),
                forward,
                Map.of(conflict.key(), 0),
                0));

    assertTrue(decision.allowed());
    assertTrue(decision.conflictRelease());
    assertTrue(
        manager.snapshotClaims().stream()
            .anyMatch(
                claim -> conflict.equals(claim.resource()) && claim.trainName().equals("tReq")));
    assertFalse(
        manager.snapshotClaims().stream()
            .anyMatch(claim -> nodeB.equals(claim.resource()) && claim.trainName().equals("tReq")));
    assertTrue(
        manager.snapshotClaims().stream()
            .anyMatch(
                claim -> nodeB.equals(claim.resource()) && claim.trainName().equals("tBlocker")));
  }

  @Test
  void conflictDeadlockReleaseDoesNotBypassSameDirectionFollowing() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyResource nodeA = OccupancyResource.forNode(NodeId.of("A"));
    OccupancyResource nodeB = OccupancyResource.forNode(NodeId.of("B"));
    Map<String, CorridorDirection> forward = Map.of(conflict.key(), CorridorDirection.A_TO_B);

    // 前车先占用目标节点，模拟同向跟驰中的前车占位。
    manager.acquire(
        new OccupancyRequest("front", Optional.empty(), now, List.of(nodeB), java.util.Map.of()));
    manager.canEnter(
        new OccupancyRequest(
            "front",
            Optional.empty(),
            now,
            List.of(conflict),
            forward,
            Map.of(conflict.key(), 0),
            1));

    // 后车虽然优先级更高，但同向跟驰不应触发“冲突区放行”绕过前车占位。
    OccupancyDecision followingDecision =
        manager.canEnter(
            new OccupancyRequest(
                "rear",
                Optional.empty(),
                now.plusSeconds(1),
                List.of(conflict, nodeA, nodeB),
                forward,
                Map.of(conflict.key(), 0),
                10));
    assertFalse(followingDecision.allowed());
    assertEquals(SignalAspect.STOP, followingDecision.signal());
  }

  @Test
  void conflictDeadlockReleaseKeepsStopWhenRequesterSideStillHasTrainAhead() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyResource nodeA = OccupancyResource.forNode(NodeId.of("A"));
    OccupancyResource nodeB = OccupancyResource.forNode(NodeId.of("B"));
    Map<String, CorridorDirection> forward = Map.of(conflict.key(), CorridorDirection.A_TO_B);
    Map<String, CorridorDirection> reverse = Map.of(conflict.key(), CorridorDirection.B_TO_A);

    // 互卡基础：对向列车占住 A 侧节点。
    manager.acquire(
        new OccupancyRequest("tOpp", Optional.empty(), now, List.of(nodeA), java.util.Map.of()));
    // 请求侧前方仍有同向列车占住 B 侧节点。
    manager.acquire(
        new OccupancyRequest("tFront", Optional.empty(), now, List.of(nodeB), java.util.Map.of()));

    // 将两侧列车都放入冲突队列，模拟会车区等待态。
    manager.canEnter(
        new OccupancyRequest(
            "tOpp",
            Optional.empty(),
            now,
            List.of(conflict),
            reverse,
            Map.of(conflict.key(), 1),
            0));
    manager.canEnter(
        new OccupancyRequest(
            "tFront",
            Optional.empty(),
            now,
            List.of(conflict),
            forward,
            Map.of(conflict.key(), 2),
            0));

    // 请求车虽然靠近冲突入口，但同向前方仍有列车占位，不应触发死锁放行。
    OccupancyDecision blocked =
        manager.canEnter(
            new OccupancyRequest(
                "tReq",
                Optional.empty(),
                now,
                List.of(conflict, nodeA, nodeB),
                forward,
                Map.of(conflict.key(), 0),
                0));
    assertFalse(blocked.allowed());
    assertEquals(SignalAspect.STOP, blocked.signal());
  }

  @Test
  void conflictDeadlockReleaseRequiresKnownOppositeDirectionForSingleCorridor() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyResource nodeA = OccupancyResource.forNode(NodeId.of("A"));
    OccupancyResource nodeB = OccupancyResource.forNode(NodeId.of("B"));

    manager.acquire(new OccupancyRequest("tReq", Optional.empty(), now, List.of(nodeA), Map.of()));
    manager.acquire(
        new OccupancyRequest("tBlocker", Optional.empty(), now, List.of(nodeB), Map.of()));

    manager.canEnter(
        new OccupancyRequest(
            "tBlocker",
            Optional.empty(),
            now,
            List.of(conflict),
            Map.of(),
            Map.of(conflict.key(), 2),
            0));

    OccupancyDecision decision =
        manager.canEnter(
            new OccupancyRequest(
                "tReq",
                Optional.empty(),
                now.plusSeconds(1),
                List.of(conflict, nodeA, nodeB),
                Map.of(),
                Map.of(conflict.key(), 0),
                0));

    assertFalse(decision.allowed());
    assertEquals(SignalAspect.STOP, decision.signal());
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

  @Test
  void queueSnapshotIncludesPriorityAndEntryOrder() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource resource = OccupancyResource.forConflict("single:comp:A~B");
    Map<String, CorridorDirection> forward = Map.of(resource.key(), CorridorDirection.A_TO_B);
    Map<String, Integer> entryOrders = Map.of(resource.key(), 2);

    OccupancyDecision decision =
        manager.canEnter(
            new OccupancyRequest(
                "t1", Optional.empty(), now, List.of(resource), forward, entryOrders, 7));
    assertTrue(decision.allowed());

    List<OccupancyQueueSnapshot> snapshots = manager.snapshotQueues();
    assertEquals(1, snapshots.size());
    OccupancyQueueEntry entry = snapshots.get(0).entries().get(0);
    assertEquals(7, entry.priority());
    assertEquals(2, entry.entryOrder());
  }

  @Test
  void queueTouchMovesTrainBetweenDirectionBucketsWithoutDuplication() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-03-15T10:00:00Z");
    OccupancyResource resource = OccupancyResource.forConflict("single:comp:A~B");
    Map<String, CorridorDirection> forward = Map.of(resource.key(), CorridorDirection.A_TO_B);

    manager.canEnter(
        new OccupancyRequest(
            "t1",
            Optional.empty(),
            now,
            List.of(resource),
            Map.of(),
            Map.of(resource.key(), 3),
            0));
    manager.canEnter(
        new OccupancyRequest(
            "t1",
            Optional.empty(),
            now.plusSeconds(1),
            List.of(resource),
            forward,
            Map.of(resource.key(), 2),
            0));

    List<OccupancyQueueSnapshot> snapshots = manager.snapshotQueues();
    assertEquals(1, snapshots.size());
    assertEquals(1, snapshots.get(0).entries().size());
    assertEquals(CorridorDirection.A_TO_B, snapshots.get(0).entries().get(0).direction());
  }

  @Test
  void releaseResourceWildcardCleansUpQueueEntries() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    Map<String, CorridorDirection> forward = Map.of(conflict.key(), CorridorDirection.A_TO_B);

    // 两列车占用同一冲突资源（同向跟驰）
    manager.acquire(
        new OccupancyRequest("t1", Optional.empty(), now, List.of(conflict), forward, 0));
    manager.acquire(
        new OccupancyRequest("t2", Optional.empty(), now, List.of(conflict), forward, 0));

    // 全量释放（不指定列车名）
    boolean released = manager.releaseResource(conflict, Optional.empty());
    assertTrue(released);

    // 队列也应被清理：第三辆车应能直接进入
    OccupancyDecision decision =
        manager.canEnter(
            new OccupancyRequest("t3", Optional.empty(), now, List.of(conflict), forward, 0));
    assertTrue(decision.allowed(), "全量释放后队列应为空，新列车可直接进入");
  }

  @Test
  void releaseByTrainIsCaseInsensitive() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("NODE-1"));
    manager.acquire(
        new OccupancyRequest("Train-Alpha", Optional.empty(), now, List.of(resource), Map.of()));

    // 释放时用不同大小写
    int removed = manager.releaseByTrain("TRAIN-ALPHA");
    assertEquals(1, removed, "大小写不同但应匹配释放");
    assertTrue(manager.snapshotClaims().isEmpty());
  }

  @Test
  void queueEntryOrderResetsAfterExpiredEntryIsPurged() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-03-15T10:00:00Z");
    OccupancyResource resource = OccupancyResource.forConflict("switcher:SW-1");

    manager.canEnter(
        new OccupancyRequest(
            "stale",
            Optional.empty(),
            now,
            List.of(resource),
            Map.of(),
            Map.of(resource.key(), 0),
            0));
    manager.canEnter(
        new OccupancyRequest(
            "keeper",
            Optional.empty(),
            now,
            List.of(resource),
            Map.of(),
            Map.of(resource.key(), 9),
            0));
    manager.canEnter(
        new OccupancyRequest(
            "keeper",
            Optional.empty(),
            now.plusSeconds(20),
            List.of(resource),
            Map.of(),
            Map.of(resource.key(), 9),
            0));
    manager.canEnter(
        new OccupancyRequest(
            "stale",
            Optional.empty(),
            now.plusSeconds(31),
            List.of(resource),
            Map.of(),
            Map.of(resource.key(), 5),
            0));

    OccupancyQueueEntry staleEntry =
        manager.snapshotQueues().stream()
            .flatMap(snapshot -> snapshot.entries().stream())
            .filter(entry -> entry.trainName().equalsIgnoreCase("stale"))
            .findFirst()
            .orElseThrow();
    assertEquals(5, staleEntry.entryOrder());
  }
}
