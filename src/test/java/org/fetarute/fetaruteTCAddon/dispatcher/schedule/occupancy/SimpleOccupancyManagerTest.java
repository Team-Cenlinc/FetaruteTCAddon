package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DispatchPriorityPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalComputationTrace;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalEvaluator;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.OccupancyAcquiredEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.OccupancyQueueChangedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalEventBus;
import org.junit.jupiter.api.Test;

class SimpleOccupancyManagerTest {

  @Test
  void physicalInterlockingQueuesBlockedCompetitorsAndPreventsBarging() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    Instant now = Instant.parse("2026-07-28T00:00:00Z");
    OccupancyResource crossing = OccupancyResource.forConflict("interlocking:jbs-crossing");
    OccupancyRequest owner =
        new OccupancyRequest("owner", Optional.empty(), now, List.of(crossing), Map.of(), 0);
    OccupancyRequest low =
        new OccupancyRequest(
            "low", Optional.empty(), now.plusSeconds(1), List.of(crossing), Map.of(), 1);
    OccupancyRequest high =
        new OccupancyRequest(
            "high", Optional.empty(), now.plusSeconds(2), List.of(crossing), Map.of(), 10);

    assertTrue(manager.acquire(owner).allowed());
    assertFalse(manager.acquire(low).allowed());
    assertFalse(manager.acquire(high).allowed());
    assertEquals(
        List.of("high", "low"),
        manager.snapshotQueues().get(0).entries().stream()
            .map(OccupancyQueueEntry::trainName)
            .toList());

    manager.releaseByTrain("owner");

    assertFalse(
        manager
            .acquire(
                new OccupancyRequest(
                    "low", Optional.empty(), now.plusSeconds(3), List.of(crossing), Map.of(), 1))
            .allowed());
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "high", Optional.empty(), now.plusSeconds(3), List.of(crossing), Map.of(), 10))
            .allowed());
  }

  @Test
  void physicalInterlockingPreviewPreservesSameTickArrivalOrderOverTrainName() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    Instant now = Instant.parse("2026-07-28T00:00:00Z");
    OccupancyResource crossing = OccupancyResource.forConflict("interlocking:same-tick");
    OccupancyRequest owner =
        new OccupancyRequest("owner", Optional.empty(), now, List.of(crossing), Map.of(), 0);
    OccupancyRequest first =
        new OccupancyRequest("zulu", Optional.empty(), now, List.of(crossing), Map.of(), 0);
    OccupancyRequest second =
        new OccupancyRequest("alpha", Optional.empty(), now, List.of(crossing), Map.of(), 0);

    assertTrue(manager.acquire(owner).allowed());
    assertFalse(manager.canEnter(first).allowed());
    assertFalse(manager.canEnter(second).allowed());

    manager.releaseByTrain("owner");

    assertTrue(manager.canEnterPreview(first).allowed());
    assertFalse(manager.canEnterPreview(second).allowed());
    assertFalse(manager.canEnter(second).allowed());
    assertTrue(manager.canEnter(first).allowed());
  }

  @Test
  void physicalInterlockingAgingEventuallyPreventsPriorityBarging() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    Instant now = Instant.parse("2026-07-28T00:00:00Z");
    OccupancyResource crossing = OccupancyResource.forConflict("interlocking:jbs-crossing");
    OccupancyRequest owner =
        new OccupancyRequest("owner", Optional.empty(), now, List.of(crossing), Map.of(), 0);
    OccupancyRequest oldLow =
        new OccupancyRequest(
            "old-low", Optional.empty(), now.plusSeconds(1), List.of(crossing), Map.of(), -10);

    assertTrue(manager.acquire(owner).allowed());
    assertFalse(manager.acquire(oldLow).allowed());
    assertFalse(
        manager
            .acquire(
                new OccupancyRequest(
                    "old-low",
                    Optional.empty(),
                    now.plusSeconds(25),
                    List.of(crossing),
                    Map.of(),
                    -10))
            .allowed());
    assertFalse(
        manager
            .acquire(
                new OccupancyRequest(
                    "old-low",
                    Optional.empty(),
                    now.plusSeconds(49),
                    List.of(crossing),
                    Map.of(),
                    -10))
            .allowed());
    assertFalse(
        manager
            .acquire(
                new OccupancyRequest(
                    "new-high",
                    Optional.empty(),
                    now.plusSeconds(60),
                    List.of(crossing),
                    Map.of(),
                    20))
            .allowed());

    manager.releaseByTrain("owner");

    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "old-low",
                    Optional.empty(),
                    now.plusSeconds(61),
                    List.of(crossing),
                    Map.of(),
                    -10))
            .allowed());
    assertFalse(
        manager
            .acquire(
                new OccupancyRequest(
                    "new-high",
                    Optional.empty(),
                    now.plusSeconds(61),
                    List.of(crossing),
                    Map.of(),
                    20))
            .allowed());
  }

  @Test
  void startupPhysicalSnapshotKeepsEveryOverlappingFieldFactRegardlessOfInputOrder() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    Instant now = Instant.parse("2026-07-28T00:00:00Z");
    OccupancyResource crossing = OccupancyResource.forConflict("interlocking:jbs-crossing");
    OccupancyRequest mt =
        new OccupancyRequest(
            "MT",
            Optional.empty(),
            now,
            List.of(crossing),
            Map.of(),
            Map.of(),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            Map.of(),
            Map.of(crossing, ResourceIntent.HOLD_ONLY));
    OccupancyRequest ds = mt.withTrainName("DS");

    StartupOccupancyReconstructionSupport.ReconstructionResult first =
        manager.reconstructPhysicalSnapshot(
            List.of(
                FieldOccupancySnapshot.allPhysical(mt), FieldOccupancySnapshot.allPhysical(ds)));
    List<String> firstOwners =
        manager.snapshotClaims().stream().map(OccupancyClaim::trainName).toList();

    StartupOccupancyReconstructionSupport.ReconstructionResult second =
        manager.reconstructPhysicalSnapshot(
            List.of(
                FieldOccupancySnapshot.allPhysical(ds), FieldOccupancySnapshot.allPhysical(mt)));
    List<OccupancyClaim> secondClaims = manager.snapshotClaims();

    assertTrue(first.committed(), first::reason);
    assertTrue(second.committed(), second::reason);
    assertEquals(List.of("DS", "MT"), firstOwners);
    assertEquals(firstOwners, secondClaims.stream().map(OccupancyClaim::trainName).toList());
    assertTrue(
        secondClaims.stream().allMatch(claim -> claim.role() == ClaimRole.PHYSICAL_FOOTPRINT));
    assertEquals(2, secondClaims.size());
  }

  @Test
  void startupFieldSnapshotKeepsLogicalHoldsSeparateFromObservedSparseZones() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyResource logicalEdge =
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("A"), NodeId.of("B")));
    OccupancyResource observedZone = OccupancyResource.forConflict("interlocking:jbs-crossing");
    OccupancyRequest request =
        new OccupancyRequest(
                "MT",
                Optional.empty(),
                Instant.parse("2026-07-30T00:00:00Z"),
                List.of(logicalEdge, observedZone),
                Map.of())
            .withResourceIntents(
                Map.of(
                    logicalEdge, ResourceIntent.HOLD_ONLY, observedZone, ResourceIntent.HOLD_ONLY));

    StartupOccupancyReconstructionSupport.ReconstructionResult result =
        manager.reconstructPhysicalSnapshot(
            List.of(new FieldOccupancySnapshot(request, Set.of(observedZone))));

    assertTrue(result.committed(), result::reason);
    Map<OccupancyResource, ClaimRole> roles =
        manager.snapshotClaims().stream()
            .collect(
                java.util.stream.Collectors.toMap(OccupancyClaim::resource, OccupancyClaim::role));
    assertEquals(ClaimRole.HOLD_ONLY, roles.get(logicalEdge));
    assertEquals(ClaimRole.PHYSICAL_FOOTPRINT, roles.get(observedZone));
  }

  @Test
  void lateTrainPhysicalHydrationPreservesOtherClaimsAndQueues() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    Instant now = Instant.parse("2026-07-28T00:00:00Z");
    OccupancyResource crossing = OccupancyResource.forConflict("interlocking:jbs-crossing");
    OccupancyResource queueResource = OccupancyResource.forConflict("switcher:other-route");
    OccupancyRequest existing =
        new OccupancyRequest("MT", Optional.empty(), now, List.of(crossing), Map.of());
    OccupancyRequest queued =
        new OccupancyRequest(
            "OTHER",
            Optional.empty(),
            now,
            List.of(queueResource),
            Map.of(),
            Map.of(queueResource.key(), 0),
            0);
    assertTrue(manager.acquire(existing).allowed());
    manager.touchQueues(queued);
    OccupancyRequest late =
        new OccupancyRequest(
            "DS",
            Optional.empty(),
            now.plusSeconds(1),
            List.of(crossing),
            Map.of(),
            Map.of(),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            Map.of(),
            Map.of(crossing, ResourceIntent.HOLD_ONLY));

    StartupOccupancyReconstructionSupport.ReconstructionResult result =
        manager.replaceTrainPhysicalFootprint(FieldOccupancySnapshot.allPhysical(late));

    assertTrue(result.committed(), result::reason);
    assertEquals(
        Set.of("DS", "MT"),
        manager.snapshotClaims().stream()
            .filter(claim -> claim.resource().equals(crossing))
            .map(OccupancyClaim::trainName)
            .collect(java.util.stream.Collectors.toSet()));
    assertTrue(
        manager.snapshotClaims().stream()
            .filter(claim -> claim.trainName().equals("DS"))
            .allMatch(claim -> claim.role() == ClaimRole.PHYSICAL_FOOTPRINT));
    assertEquals("OTHER", manager.snapshotQueues().get(0).entries().get(0).trainName());
  }

  @Test
  void invalidLateTrainHydrationLeavesLiveStateUntouched() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyResource existingResource = OccupancyResource.forNode(NodeId.of("A"));
    OccupancyRequest existing =
        new OccupancyRequest(
            "existing", Optional.empty(), Instant.EPOCH, List.of(existingResource), Map.of());
    assertTrue(manager.acquire(existing).allowed());
    List<OccupancyClaim> claimsBefore = manager.snapshotClaims();
    long versionBefore = manager.version();
    OccupancyResource invalidResource =
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("A"), NodeId.of("B")));
    OccupancyRequest invalid =
        new OccupancyRequest(
            "late", Optional.empty(), Instant.EPOCH, List.of(invalidResource), Map.of());

    StartupOccupancyReconstructionSupport.ReconstructionResult result =
        manager.replaceTrainPhysicalFootprint(FieldOccupancySnapshot.allPhysical(invalid));

    assertFalse(result.committed());
    assertEquals(claimsBefore, manager.snapshotClaims());
    assertEquals(versionBefore, manager.version());
  }

  @Test
  void identicalClaimRefreshDoesNotAdvanceVersionOrPublishAcquireEvent() {
    SignalEventBus eventBus = new SignalEventBus();
    AtomicInteger acquiredEvents = new AtomicInteger();
    eventBus.subscribe(OccupancyAcquiredEvent.class, event -> acquiredEvents.incrementAndGet());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy(), eventBus);
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("A"));
    OccupancyRequest request =
        new OccupancyRequest(
            "train-A",
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:00Z"),
            List.of(resource),
            Map.of());

    assertTrue(manager.acquire(request).allowed());
    long versionAfterAcquire = manager.version();
    assertEquals(1, acquiredEvents.get());

    assertTrue(manager.acquire(request).allowed());

    assertEquals(versionAfterAcquire, manager.version());
    assertEquals(1, acquiredEvents.get());
  }

  @Test
  void throwingDiagnosticLoggerCannotInterruptClaimVersionOrAcquireEventCommit() {
    SignalComputationTrace.configureLogger(
        message -> {
          throw new IllegalStateException("diagnostic-sink-failed");
        });
    try {
      SignalEventBus eventBus = new SignalEventBus();
      AtomicInteger acquiredEvents = new AtomicInteger();
      eventBus.subscribe(OccupancyAcquiredEvent.class, event -> acquiredEvents.incrementAndGet());
      SimpleOccupancyManager manager =
          new SimpleOccupancyManager(
              (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy(), eventBus);
      OccupancyResource resource = OccupancyResource.forNode(NodeId.of("A"));
      OccupancyRequest request =
          new OccupancyRequest(
              "train-A",
              Optional.empty(),
              Instant.parse("2026-01-01T00:00:00Z"),
              List.of(resource),
              Map.of());

      OccupancyDecision decision = assertDoesNotThrow(() -> manager.acquire(request));

      assertTrue(decision.allowed());
      assertEquals(1, manager.version());
      assertEquals(
          List.of("train-A"),
          manager.snapshotClaims().stream().map(OccupancyClaim::trainName).toList());
      assertEquals(1, acquiredEvents.get());
    } finally {
      SignalComputationTrace.configureLogger(null);
    }
  }

  @Test
  void linkageErrorSubscriberCannotTurnCommittedAcquireIntoFailure() {
    SignalEventBus eventBus = new SignalEventBus();
    AtomicInteger completedNotifications = new AtomicInteger();
    eventBus.subscribe(
        OccupancyAcquiredEvent.class,
        event -> {
          throw new LinkageError("subscriber-abi-mismatch");
        });
    eventBus.subscribe(
        OccupancyAcquiredEvent.class, event -> completedNotifications.incrementAndGet());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy(), eventBus);
    OccupancyResource resource = OccupancyResource.forNode(NodeId.of("A"));
    OccupancyRequest request =
        new OccupancyRequest(
            "train-A",
            Optional.empty(),
            Instant.parse("2026-01-01T00:00:00Z"),
            List.of(resource),
            Map.of());

    OccupancyDecision decision = assertDoesNotThrow(() -> manager.acquire(request));

    assertTrue(decision.allowed());
    assertEquals(1, manager.version());
    assertEquals(
        List.of("train-A"),
        manager.snapshotClaims().stream().map(OccupancyClaim::trainName).toList());
    assertEquals(1, completedNotifications.get());
  }

  @Test
  void queueHeartbeatUpdatesLastSeenWithoutAdvancingVersion() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("switcher:SW-1");
    OccupancyRequest request =
        new OccupancyRequest(
            "train-A",
            Optional.empty(),
            now,
            List.of(conflict),
            Map.of(),
            Map.of(conflict.key(), 0),
            0);

    manager.touchQueues(request);
    long versionAfterInsert = manager.version();
    OccupancyQueueEntry first = manager.snapshotQueues().get(0).entries().get(0);

    manager.touchQueues(request.withSchedulingMetadata(now.plusSeconds(1), 0));

    OccupancyQueueEntry refreshed = manager.snapshotQueues().get(0).entries().get(0);
    assertEquals(versionAfterInsert, manager.version());
    assertEquals(first.firstSeen(), refreshed.firstSeen());
    assertEquals(now.plusSeconds(1), refreshed.lastSeen());

    manager.touchQueues(request.withSchedulingMetadata(now.plusSeconds(2), 1));
    assertEquals(versionAfterInsert + 1, manager.version());
  }

  @Test
  void removingCapacityBlockedArrivalQueueLetsTerminalDepartureProceedWithoutReleasingClaims() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource throat = OccupancyResource.forConflict("switcher:PPK-THROAT");
    OccupancyResource arrivalFootprint =
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("PPK-A"), NodeId.of("PPK-B")));
    OccupancyRequest physicalArrival =
        new OccupancyRequest(
            "incoming-train", Optional.empty(), now, List.of(arrivalFootprint), Map.of());
    OccupancyRequest incomingQueue =
        new OccupancyRequest(
            "incoming-train",
            Optional.empty(),
            now,
            List.of(throat),
            Map.of(throat.key(), CorridorDirection.B_TO_A),
            Map.of(throat.key(), 0),
            0);
    OccupancyRequest terminalDeparture =
        new OccupancyRequest(
            "turning-train",
            Optional.empty(),
            now,
            List.of(throat),
            Map.of(throat.key(), CorridorDirection.A_TO_B),
            Map.of(throat.key(), 0),
            0);

    assertTrue(manager.acquire(physicalArrival).allowed());
    manager.touchQueues(incomingQueue);
    manager.touchQueues(terminalDeparture);
    assertFalse(manager.canEnterPreview(terminalDeparture).allowed());

    assertEquals(1, manager.removeQueueEntries("incoming-train", List.of(throat)));

    assertTrue(manager.canEnterPreview(terminalDeparture).allowed());
    assertEquals("incoming-train", manager.getClaim(arrivalFootprint).orElseThrow().trainName());
  }

  @Test
  void removingQueueHeadPublishesArbitrationChangeForNextWinner() {
    SignalEventBus eventBus = new SignalEventBus();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy(), eventBus);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("switcher:queue-wakeup");
    OccupancyRequest first =
        new OccupancyRequest("yield-train", Optional.empty(), now, List.of(conflict), Map.of(), 0);
    OccupancyRequest second =
        new OccupancyRequest("winner-train", Optional.empty(), now, List.of(conflict), Map.of(), 0);
    manager.touchQueues(first);
    manager.touchQueues(second);
    List<OccupancyQueueChangedEvent> events = new java.util.ArrayList<>();
    eventBus.subscribe(OccupancyQueueChangedEvent.class, events::add);

    assertEquals(1, manager.removeQueueEntries("yield-train", List.of(conflict)));

    assertEquals("winner-train", manager.snapshotQueues().get(0).entries().get(0).trainName());
    assertEquals(1, events.size());
    assertEquals("yield-train", events.get(0).sourceTrainName());
    assertEquals(List.of(conflict), events.get(0).affectedResources());
  }

  @Test
  void loweringExistingQueueHeadPriorityWakesPreciselyTheNewWinner() {
    SignalEventBus eventBus = new SignalEventBus();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy(), eventBus);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("switcher:priority-wakeup");
    OccupancyRequest previousWinner =
        new OccupancyRequest(
            "previous-winner", Optional.empty(), now, List.of(conflict), Map.of(), 10);
    OccupancyRequest nextWinner =
        new OccupancyRequest(
            "next-winner", Optional.empty(), now.plusMillis(1), List.of(conflict), Map.of(), 0);
    manager.touchQueues(previousWinner);
    manager.touchQueues(nextWinner);
    assertEquals("previous-winner", manager.snapshotQueues().get(0).entries().get(0).trainName());

    List<String> reevaluationRequests = new java.util.ArrayList<>();
    SignalEvaluator evaluator =
        new SignalEvaluator(
            eventBus,
            resources ->
                manager.snapshotQueues().stream()
                    .filter(snapshot -> resources.contains(snapshot.resource()))
                    .flatMap(snapshot -> snapshot.entries().stream())
                    .map(OccupancyQueueEntry::trainName)
                    .toList(),
            reevaluationRequests::add);
    evaluator.start();

    manager.touchQueues(previousWinner.withSchedulingMetadata(now.plusSeconds(1), -10));

    assertEquals("next-winner", manager.snapshotQueues().get(0).entries().get(0).trainName());
    assertEquals(List.of("next-winner"), reevaluationRequests);
  }

  @Test
  void splitAliasRefreshesCanonicalQueueEntryInsteadOfCreatingGhostCompetitor() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("switcher:split-alias");
    manager.touchQueues(
        new OccupancyRequest("train-main", Optional.empty(), now, List.of(conflict), Map.of(), 0));
    manager.touchQueues(
        new OccupancyRequest(
            "train-main~a", Optional.empty(), now.plusSeconds(1), List.of(conflict), Map.of(), 0));

    assertEquals(1, manager.snapshotQueues().get(0).entries().size());
    assertEquals(1, manager.removeQueueEntries("TRAIN-MAIN~b", List.of(conflict)));
    assertTrue(manager.snapshotQueues().isEmpty());
  }

  @Test
  void releaseByTrainPublishesQueueChangeWhenOnlyQueueStateWasRemoved() {
    SignalEventBus eventBus = new SignalEventBus();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy(), eventBus);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("switcher:release-queue-only");
    manager.touchQueues(
        new OccupancyRequest(
            "leaving-train", Optional.empty(), now, List.of(conflict), Map.of(), 0));
    manager.touchQueues(
        new OccupancyRequest(
            "winner-train", Optional.empty(), now, List.of(conflict), Map.of(), 0));
    List<OccupancyQueueChangedEvent> events = new java.util.ArrayList<>();
    eventBus.subscribe(OccupancyQueueChangedEvent.class, events::add);

    assertEquals(0, manager.releaseByTrain("leaving-train"));

    assertEquals("winner-train", manager.snapshotQueues().get(0).entries().get(0).trainName());
    assertEquals(1, events.size());
    assertEquals(List.of(conflict), events.get(0).affectedResources());
  }

  @Test
  void releaseByTrainPublishesArbitrationChangeWhenOnlyReleaseLockWasRemoved() {
    SignalEventBus eventBus = new SignalEventBus();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy(), eventBus);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("switcher:release-lock-only");
    manager.rememberDeadlockReleaseLock(conflict.key(), "leaving-train", now.plusSeconds(8));
    manager.touchQueues(
        new OccupancyRequest(
            "winner-train", Optional.empty(), now, List.of(conflict), Map.of(), 0));
    List<OccupancyQueueChangedEvent> events = new java.util.ArrayList<>();
    eventBus.subscribe(OccupancyQueueChangedEvent.class, events::add);

    assertEquals(0, manager.releaseByTrain("leaving-train"));

    assertTrue(
        events.stream()
            .anyMatch(
                event ->
                    event.sourceTrainName().equals("leaving-train")
                        && event.affectedResources().equals(List.of(conflict))));
  }

  @Test
  void releaseResourceWithoutMatchingClaimKeepsQueueAndVersionUntouched() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("switcher:claim-owner");
    OccupancyRequest owner =
        new OccupancyRequest("owner-train", Optional.empty(), now, List.of(conflict), Map.of(), 0);
    OccupancyRequest waiter =
        new OccupancyRequest(
            "waiting-train", Optional.empty(), now.plusSeconds(1), List.of(conflict), Map.of(), 0);
    assertTrue(manager.acquire(owner).allowed());
    assertFalse(manager.canEnter(waiter).allowed());
    long versionBefore = manager.version();

    assertFalse(manager.releaseResource(conflict, Optional.of("waiting-train")));

    assertEquals(versionBefore, manager.version());
    assertTrue(
        manager.snapshotQueues().stream()
            .flatMap(snapshot -> snapshot.entries().stream())
            .anyMatch(entry -> entry.trainName().equals("waiting-train")));
  }

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
  void blockedDecisionUpdatesLiveBlockerSnapshotListener() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ofSeconds(10);
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());
    AtomicReference<String> observed = new AtomicReference<>("");
    manager.setLiveBlockerSnapshotListener(
        (trainName, decision, request, sampledAt, source) ->
            observed.set(
                trainName
                    + "|"
                    + decision.blockers().get(0).trainName()
                    + "|"
                    + decision.blockers().get(0).resource()
                    + "|"
                    + source));

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource resource =
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("A"), NodeId.of("B")));
    manager.acquire(
        new OccupancyRequest(
            "train-A", Optional.empty(), now, List.of(resource), java.util.Map.of()));

    OccupancyDecision blocked =
        manager.canEnter(
            new OccupancyRequest(
                "train-B", Optional.empty(), now.plusSeconds(1), List.of(resource), Map.of()));

    assertFalse(blocked.allowed());
    assertTrue(observed.get().startsWith("train-B|train-A|EDGE:"));
    assertTrue(observed.get().contains("canEnter:blockers"));
  }

  @Test
  void switcherClaimLifecycleTraceCoversAcquireAndReleaseButNotBlockerRead() {
    List<String> traces = new java.util.ArrayList<>();
    SignalComputationTrace.configureLogger(traces::add);
    try {
      SimpleOccupancyManager manager =
          new SimpleOccupancyManager(
              (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
      Instant now = Instant.parse("2026-01-01T00:00:00Z");
      OccupancyResource switcher =
          OccupancyResource.forConflict("switcher:SWITCHER:Towny:545:74:1014");

      assertTrue(
          manager
              .acquire(
                  new OccupancyRequest(
                      "train-A", Optional.empty(), now, List.of(switcher), Map.of()))
              .allowed());
      OccupancyDecision blocked =
          manager.canEnter(
              new OccupancyRequest(
                  "train-B", Optional.empty(), now.plusSeconds(1), List.of(switcher), Map.of()));
      assertFalse(blocked.allowed());

      manager.releaseByTrain("train-A");

      assertTrue(
          traces.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_SWITCHER_CLAIM_LIFECYCLE")
                          && message.contains("event=acquire")
                          && message.contains("owner=train-A")));
      assertFalse(
          traces.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_SWITCHER_CLAIM_LIFECYCLE")
                          && message.contains("event=blocker-read")
                          && message.contains("train=train-B")),
          () -> "只读 blocker 查询不应伪装为 claim 生命周期: " + traces);
      assertTrue(
          traces.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_SWITCHER_CLAIM_LIFECYCLE")
                          && message.contains("event=release")
                          && message.contains("owner=train-A")));
    } finally {
      SignalComputationTrace.configureLogger(null);
    }
  }

  @Test
  void interlockingClaimLifecycleTraceCoversAcquireBlockerReadAndRelease() {
    List<String> traces = new java.util.ArrayList<>();
    SignalComputationTrace.configureLogger(traces::add);
    try {
      SimpleOccupancyManager manager =
          new SimpleOccupancyManager(
              (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
      Instant now = Instant.parse("2026-01-01T00:00:00Z");
      OccupancyResource interlocking =
          OccupancyResource.forConflict("interlocking:0123456789abcdef");

      assertTrue(
          manager
              .acquire(
                  new OccupancyRequest(
                      "train-A", Optional.empty(), now, List.of(interlocking), Map.of()))
              .allowed());
      assertFalse(
          manager
              .canEnter(
                  new OccupancyRequest(
                      "train-B",
                      Optional.empty(),
                      now.plusSeconds(1),
                      List.of(interlocking),
                      Map.of()))
              .allowed());
      manager.releaseByTrain("train-A");

      assertTrue(
          traces.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_INTERLOCKING_CLAIM_LIFECYCLE")
                          && message.contains("event=acquire")
                          && message.contains("owner=train-A")));
      assertFalse(
          traces.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_INTERLOCKING_CLAIM_LIFECYCLE")
                          && message.contains("event=blocker-read")
                          && message.contains("train=train-B")),
          () -> "只读 blocker 查询不应伪装为 claim 生命周期: " + traces);
      assertTrue(
          traces.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_INTERLOCKING_CLAIM_LIFECYCLE")
                          && message.contains("event=release")
                          && message.contains("owner=train-A")));
    } finally {
      SignalComputationTrace.configureLogger(null);
    }
  }

  @Test
  void sameOwnerClaimRefreshDoesNotEmitLifecycleDiagnosticsOrRestrictedSignalTrace() {
    List<String> traces = new java.util.ArrayList<>();
    SignalComputationTrace.configureLogger(traces::add);
    try {
      SimpleOccupancyManager manager =
          new SimpleOccupancyManager(
              (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
      Instant now = Instant.parse("2026-01-01T00:00:00Z");
      OccupancyResource node = OccupancyResource.forNode(NodeId.of("NODE-A"));
      OccupancyRequest request =
          new OccupancyRequest("train-A", Optional.empty(), now, List.of(node), Map.of());

      assertTrue(manager.acquire(request).allowed());
      traces.clear();

      assertTrue(
          manager
              .acquire(
                  new OccupancyRequest(
                      "train-A", Optional.empty(), now.plusSeconds(1), List.of(node), Map.of()))
              .allowed());

      assertTrue(
          traces.stream()
              .noneMatch(
                  message ->
                      message.contains("SMART_OCCUPANCY_CLAIM_MERGE")
                          || message.contains("SMART_RESOURCE_LIFECYCLE")
                          || message.startsWith("SignalTrace")),
          traces::toString);
    } finally {
      SignalComputationTrace.configureLogger(null);
    }
  }

  @Test
  void unifiedResourceLifecycleAuditsEveryResourceFamily() {
    List<String> traces = new java.util.ArrayList<>();
    SignalComputationTrace.configureLogger(traces::add);
    try {
      SimpleOccupancyManager manager =
          new SimpleOccupancyManager(
              (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
      Instant now = Instant.parse("2026-01-01T00:00:00Z");
      OccupancyResource node = OccupancyResource.forNode(NodeId.of("NODE-A"));
      OccupancyResource edge =
          OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("NODE-A"), NodeId.of("NODE-B")));
      OccupancyResource single = OccupancyResource.forConflict("single:section:a~b");
      OccupancyResource switcher = OccupancyResource.forConflict("switcher:JBS");
      OccupancyResource interlocking =
          OccupancyResource.forConflict("interlocking:0123456789abcdef");
      List<OccupancyResource> resources = List.of(node, edge, single, switcher, interlocking);
      OccupancyRequest request =
          new OccupancyRequest(
              "train-A",
              Optional.empty(),
              now,
              resources,
              Map.of(single.key(), CorridorDirection.A_TO_B));

      assertTrue(manager.acquire(request).allowed());
      assertEquals(resources.size(), manager.releaseByTrain("train-A"));

      for (OccupancyResource resource : resources) {
        assertTrue(
            traces.stream()
                .anyMatch(
                    message ->
                        message.contains("SMART_RESOURCE_LIFECYCLE")
                            && message.contains("resource=" + resource)
                            && message.contains("event=acquire")
                            && message.contains("owner=train-A")),
            () -> "missing acquire lifecycle for " + resource + ": " + traces);
        assertTrue(
            traces.stream()
                .anyMatch(
                    message ->
                        message.contains("SMART_RESOURCE_LIFECYCLE")
                            && message.contains("resource=" + resource)
                            && message.contains("event=release")
                            && message.contains("owner=train-A")),
            () -> "missing release lifecycle for " + resource + ": " + traces);
      }
    } finally {
      SignalComputationTrace.configureLogger(null);
    }
  }

  @Test
  void unifiedResourceLifecycleAuditsClaimRoleMerge() {
    List<String> traces = new java.util.ArrayList<>();
    SignalComputationTrace.configureLogger(traces::add);
    try {
      SimpleOccupancyManager manager =
          new SimpleOccupancyManager(
              (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
      Instant now = Instant.parse("2026-01-01T00:00:00Z");
      OccupancyResource node = OccupancyResource.forNode(NodeId.of("NODE-A"));
      OccupancyRequest retain =
          new OccupancyRequest("train-A", Optional.empty(), now, List.of(node), Map.of())
              .withResourceIntents(Map.of(node, ResourceIntent.PROTECTIVE_RETAIN));
      OccupancyRequest movement =
          new OccupancyRequest(
              "train-A", Optional.empty(), now.plusSeconds(1), List.of(node), Map.of());

      assertTrue(manager.acquire(retain).allowed());
      assertTrue(manager.acquire(movement).allowed());

      assertTrue(
          traces.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_RESOURCE_LIFECYCLE")
                          && message.contains("resource=" + node)
                          && message.contains("event=merge")
                          && message.contains("oldRole=PROTECTIVE_RETAIN")
                          && message.contains("newRole=MOVEMENT_REQUIRED")),
          traces::toString);
    } finally {
      SignalComputationTrace.configureLogger(null);
    }
  }

  @Test
  void queueLifecycleAuditsEnqueueAndExplicitRemoval() {
    List<String> traces = new java.util.ArrayList<>();
    SignalComputationTrace.configureLogger(traces::add);
    try {
      SimpleOccupancyManager manager =
          new SimpleOccupancyManager(
              (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
      Instant now = Instant.parse("2026-01-01T00:00:00Z");
      OccupancyResource single = OccupancyResource.forConflict("single:section:a~b");
      OccupancyRequest request =
          new OccupancyRequest(
              "train-A",
              Optional.empty(),
              now,
              List.of(single),
              Map.of(single.key(), CorridorDirection.A_TO_B));

      manager.touchQueues(request);
      assertEquals(1, manager.removeQueueEntries("train-A", List.of(single)));

      assertTrue(
          traces.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_QUEUE_LIFECYCLE")
                          && message.contains("resource=" + single)
                          && message.contains("entryType=WAITING_TRAIN")
                          && message.contains("event=enqueue")
                          && message.contains("train=train-A")),
          traces::toString);
      assertTrue(
          traces.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_QUEUE_LIFECYCLE")
                          && message.contains("resource=" + single)
                          && message.contains("entryType=WAITING_TRAIN")
                          && message.contains("event=remove")
                          && message.contains("train=train-A")
                          && message.contains("reason=explicit-remove")),
          traces::toString);
    } finally {
      SignalComputationTrace.configureLogger(null);
    }
  }

  @Test
  void queueLifecycleAuditsTtlExpiration() {
    List<String> traces = new java.util.ArrayList<>();
    SignalComputationTrace.configureLogger(traces::add);
    try {
      SimpleOccupancyManager manager =
          new SimpleOccupancyManager(
              (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
      Instant now = Instant.parse("2026-01-01T00:00:00Z");
      OccupancyResource single = OccupancyResource.forConflict("single:section:a~b");
      manager.touchQueues(
          new OccupancyRequest(
              "stale-train",
              Optional.empty(),
              now,
              List.of(single),
              Map.of(single.key(), CorridorDirection.A_TO_B)));

      manager.touchQueues(
          new OccupancyRequest(
              "fresh-train",
              Optional.empty(),
              now.plusSeconds(31),
              List.of(single),
              Map.of(single.key(), CorridorDirection.B_TO_A)));

      assertTrue(
          traces.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_QUEUE_LIFECYCLE")
                          && message.contains("resource=" + single)
                          && message.contains("entryType=WAITING_TRAIN")
                          && message.contains("event=expire")
                          && message.contains("train=stale-train")
                          && message.contains("reason=ttl-expired")),
          traces::toString);
    } finally {
      SignalComputationTrace.configureLogger(null);
    }
  }

  @Test
  void ttlExpirationPublishesQueueChangeForRemainingWinner() {
    SignalEventBus eventBus = new SignalEventBus();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy(), eventBus);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("switcher:ttl-wakeup");
    manager.touchQueues(
        new OccupancyRequest("stale-train", Optional.empty(), now, List.of(conflict), Map.of(), 0));
    manager.touchQueues(
        new OccupancyRequest(
            "winner-train", Optional.empty(), now.plusSeconds(20), List.of(conflict), Map.of(), 0));
    List<OccupancyQueueChangedEvent> events = new java.util.ArrayList<>();
    eventBus.subscribe(OccupancyQueueChangedEvent.class, events::add);

    manager.canEnter(
        new OccupancyRequest(
            "probe", Optional.empty(), now.plusSeconds(31), List.of(), Map.of(), 0));

    assertEquals("winner-train", manager.snapshotQueues().get(0).entries().get(0).trainName());
    assertTrue(
        events.stream()
            .anyMatch(
                event ->
                    event.sourceTrainName().equals("*")
                        && event.affectedResources().equals(List.of(conflict))));
  }

  @Test
  void startupReconstructionAuditsAtomicClaimQueueAndReleaseLockDiff() {
    List<String> traces = new java.util.ArrayList<>();
    SignalComputationTrace.configureLogger(traces::add);
    try {
      SimpleOccupancyManager manager =
          new SimpleOccupancyManager(
              (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
      Instant now = Instant.parse("2026-01-01T00:00:00Z");
      OccupancyResource retainedNode = OccupancyResource.forNode(NodeId.of("RETAINED"));
      OccupancyResource removedEdge =
          OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("OLD-A"), NodeId.of("OLD-B")));
      OccupancyResource addedNode = OccupancyResource.forNode(NodeId.of("ADDED"));
      OccupancyResource queueResource =
          OccupancyResource.forConflict("single:section:queue-a~queue-b");
      OccupancyResource releaseLockResource =
          OccupancyResource.forConflict("switcher:startup-lock");
      assertTrue(
          manager
              .acquire(
                  new OccupancyRequest(
                      "same-train",
                      Optional.empty(),
                      now,
                      List.of(retainedNode, removedEdge),
                      Map.of()))
              .allowed());
      manager.touchQueues(
          new OccupancyRequest(
              "queued-train",
              Optional.empty(),
              now,
              List.of(queueResource),
              Map.of(queueResource.key(), CorridorDirection.A_TO_B)));
      manager.rememberDeadlockReleaseLock(
          releaseLockResource.key(), "lock-train", now.plusSeconds(8));
      traces.clear();
      SignalComputationTrace.configureLogger(traces::add);

      OccupancyRequest retainedField =
          new OccupancyRequest(
                  "same-train",
                  Optional.empty(),
                  now.plusSeconds(1),
                  List.of(retainedNode),
                  Map.of())
              .withResourceIntents(Map.of(retainedNode, ResourceIntent.PROTECTIVE_RETAIN));
      OccupancyRequest addedField =
          new OccupancyRequest(
                  "new-train", Optional.empty(), now.plusSeconds(1), List.of(addedNode), Map.of())
              .withResourceIntents(Map.of(addedNode, ResourceIntent.PROTECTIVE_RETAIN));

      StartupOccupancyReconstructionSupport.ReconstructionResult result =
          manager.reconstructPhysicalSnapshot(
              List.of(
                  FieldOccupancySnapshot.allPhysical(retainedField),
                  FieldOccupancySnapshot.allPhysical(addedField)));

      assertTrue(result.committed(), result::reason);
      assertTrue(
          traces.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_RESOURCE_LIFECYCLE")
                          && message.contains("resource=" + retainedNode)
                          && message.contains("event=snapshot-update")
                          && message.contains("oldRole=MOVEMENT_REQUIRED")
                          && message.contains("newRole=PHYSICAL_FOOTPRINT")),
          traces::toString);
      assertTrue(
          traces.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_RESOURCE_LIFECYCLE")
                          && message.contains("resource=" + removedEdge)
                          && message.contains("event=snapshot-remove")
                          && message.contains("owner=same-train")),
          traces::toString);
      assertTrue(
          traces.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_RESOURCE_LIFECYCLE")
                          && message.contains("resource=" + addedNode)
                          && message.contains("event=snapshot-acquire")
                          && message.contains("owner=new-train")),
          traces::toString);
      assertTrue(
          traces.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_QUEUE_LIFECYCLE")
                          && message.contains("resource=" + queueResource)
                          && message.contains("entryType=WAITING_TRAIN")
                          && message.contains("event=snapshot-clear")
                          && message.contains("train=queued-train")),
          traces::toString);
      assertTrue(
          traces.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_QUEUE_LIFECYCLE")
                          && message.contains("resource=" + releaseLockResource)
                          && message.contains("entryType=DEADLOCK_RELEASE_LOCK")
                          && message.contains("event=snapshot-clear")
                          && message.contains("train=lock-train")),
          traces::toString);
    } finally {
      SignalComputationTrace.configureLogger(null);
    }
  }

  @Test
  void authorityOwnerMigrationAuditsClaimQueueAndReleaseLock() {
    List<String> traces = new java.util.ArrayList<>();
    SignalComputationTrace.configureLogger(traces::add);
    try {
      SimpleOccupancyManager manager =
          new SimpleOccupancyManager(
              (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
      Instant now = Instant.parse("2099-01-01T00:00:00Z");
      OccupancyResource node = OccupancyResource.forNode(NodeId.of("NODE-A"));
      OccupancyResource queueResource =
          OccupancyResource.forConflict("single:section:queue-a~queue-b");
      OccupancyResource releaseLock = OccupancyResource.forConflict("switcher:owner-migration");
      assertTrue(
          manager
              .acquire(
                  new OccupancyRequest("train-old", Optional.empty(), now, List.of(node), Map.of()))
              .allowed());
      manager.touchQueues(
          new OccupancyRequest(
              "train-old",
              Optional.empty(),
              now,
              List.of(queueResource),
              Map.of(queueResource.key(), CorridorDirection.A_TO_B)));
      manager.rememberDeadlockReleaseLock(releaseLock.key(), "train-old", now.plusSeconds(8));
      traces.clear();
      SignalComputationTrace.configureLogger(traces::add);

      assertTrue(manager.migrateAuthorityOwner("train-old", "train-new"));

      assertTrue(
          traces.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_RESOURCE_LIFECYCLE")
                          && message.contains("resource=" + node)
                          && message.contains("event=owner-migrate")
                          && message.contains("oldOwner=train-old")
                          && message.contains("newOwner=train-new")),
          traces::toString);
      assertTrue(
          traces.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_QUEUE_LIFECYCLE")
                          && message.contains("resource=" + queueResource)
                          && message.contains("entryType=WAITING_TRAIN")
                          && message.contains("event=owner-migrate")
                          && message.contains("train=train-new")),
          traces::toString);
      assertTrue(
          traces.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_QUEUE_LIFECYCLE")
                          && message.contains("resource=" + releaseLock)
                          && message.contains("entryType=DEADLOCK_RELEASE_LOCK")
                          && message.contains("event=owner-migrate")
                          && message.contains("train=train-new")),
          traces::toString);
    } finally {
      SignalComputationTrace.configureLogger(null);
    }
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
  void releaseByTrainAdvancesVersionWhenOnlyQueueEntryChanges() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("switcher:terminal-throat");
    OccupancyRequest waiting =
        new OccupancyRequest(
            "waiting-train",
            Optional.empty(),
            now,
            List.of(conflict),
            Map.of(),
            Map.of(conflict.key(), 0),
            0);
    manager.touchQueues(waiting);
    long queuedVersion = manager.version();

    assertEquals(0, manager.releaseByTrain("waiting-train"));

    assertTrue(manager.snapshotQueues().isEmpty());
    assertEquals(queuedVersion + 1, manager.version());
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
  void sectionTokenBlocksOppositeDirectionBeforeMicroSegmentOverlap() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource section = OccupancyResource.forConflict("single:section:bridge:A~B");
    OccupancyResource localSegmentA = OccupancyResource.forConflict("single:comp:A~S");
    OccupancyResource localSegmentB = OccupancyResource.forConflict("single:comp:S~B");
    Map<String, CorridorDirection> forward =
        Map.of(
            section.key(), CorridorDirection.A_TO_B, localSegmentA.key(), CorridorDirection.A_TO_B);
    Map<String, CorridorDirection> reverse =
        Map.of(
            section.key(), CorridorDirection.B_TO_A, localSegmentB.key(), CorridorDirection.B_TO_A);

    OccupancyDecision first =
        manager.acquire(
            new OccupancyRequest(
                "front", Optional.empty(), now, List.of(section, localSegmentA), forward));
    assertTrue(first.allowed());

    OccupancyDecision opposite =
        manager.canEnter(
            new OccupancyRequest(
                "opposite",
                Optional.empty(),
                now.plusSeconds(1),
                List.of(section, localSegmentB),
                reverse));
    assertFalse(opposite.allowed());
    assertEquals(SignalAspect.STOP, opposite.signal());

    OccupancyDecision follower =
        manager.canEnter(
            new OccupancyRequest(
                "follower",
                Optional.empty(),
                now.plusSeconds(2),
                List.of(section, localSegmentB),
                Map.of(
                    section.key(),
                    CorridorDirection.A_TO_B,
                    localSegmentB.key(),
                    CorridorDirection.A_TO_B)));
    assertTrue(follower.allowed());
  }

  @Test
  void unknownSectionDirectionWithoutOtherPresenceDoesNotCreatePhantomStop() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource section =
        OccupancyResource.forConflict("single:section:comp:SURC:S:WSD:2~SURC:S:WSD:3");
    OccupancyRequest request =
        new OccupancyRequest(
            "follower",
            Optional.empty(),
            now,
            List.of(section),
            Map.of(),
            Map.of(section.key(), 0),
            0);

    OccupancyDecision decision = manager.canEnterPreview(request);

    assertTrue(decision.allowed(), decision.toString());
  }

  @Test
  void unknownSectionDirectionWithOtherPresenceStillFailsClosed() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource section =
        OccupancyResource.forConflict("single:section:comp:SURC:S:WSD:2~SURC:S:WSD:3");
    OccupancyRequest leader =
        new OccupancyRequest(
            "leader",
            Optional.empty(),
            now,
            List.of(section),
            Map.of(),
            Map.of(section.key(), 0),
            0);
    OccupancyRequest request =
        new OccupancyRequest(
            "follower",
            Optional.empty(),
            now.plusSeconds(1),
            List.of(section),
            Map.of(),
            Map.of(section.key(), 0),
            0);

    assertTrue(manager.acquire(leader).allowed());
    OccupancyDecision decision = manager.canEnterPreview(request);

    assertFalse(decision.allowed());
    assertEquals("single-conflict-direction-unknown", decision.reason());
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
  void directionUnknownDoesNotOverwriteKnownQueueEntryWithoutTrace() {
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
            "front",
            Optional.empty(),
            now.plusSeconds(1),
            List.of(resource),
            Map.of(),
            Map.of(resource.key(), 0),
            0));

    List<OccupancyQueueSnapshot> snapshots = manager.snapshotQueues();
    assertEquals(1, snapshots.size());
    assertEquals(1, snapshots.get(0).entries().size());
    assertEquals(CorridorDirection.A_TO_B, snapshots.get(0).entries().get(0).direction());
  }

  @Test
  void sameDirectionFollowerNotBlockedByUnknownDirectionFromHoldClaim() {
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
            "front",
            Optional.empty(),
            now.plusSeconds(1),
            List.of(resource),
            Map.of(),
            Map.of(resource.key(), 0),
            0));

    OccupancyDecision rear =
        manager.canEnter(
            new OccupancyRequest(
                "rear",
                Optional.empty(),
                now.plusSeconds(2),
                List.of(resource),
                forward,
                Map.of(resource.key(), 0),
                0));

    assertTrue(rear.allowed());
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
    assertEquals(ClaimRole.QUEUE_POSITION, second.blockers().get(0).role());
  }

  @Test
  void losingSwitcherQueueEntryDoesNotBecomeAdvisoryRiskForIncumbentAuthority() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId switcher = NodeId.of("SWITCHER:TEST:MERGE");
    NodeId sharedExit = NodeId.of("TEST:MERGE:EXIT");
    OccupancyResource conflict = OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyRequest incumbent =
        switcherRequest(
                "incumbent",
                now,
                conflict,
                List.of(NodeId.of("TEST:MERGE:ENTRY:A"), switcher, sharedExit))
            .withSchedulingMetadata(now, 10);
    OccupancyRequest waiting =
        switcherRequest(
                "waiting",
                now.plusMillis(1),
                conflict,
                List.of(NodeId.of("TEST:MERGE:ENTRY:B"), switcher, sharedExit))
            .withSchedulingMetadata(now.plusMillis(1), -10);

    assertTrue(manager.acquire(incumbent).allowed());
    assertFalse(manager.acquire(waiting).allowed());

    List<AdvisoryRisk> risks =
        manager.scanAdvisoryRisks(
            incumbent.withSchedulingMetadata(now.plusSeconds(1), 10).asLookaheadPreview());

    assertTrue(risks.isEmpty(), "失败方的排队位次不能反向阻停已持有可执行进路的列车");
    assertFalse(
        manager.canEnter(waiting.withSchedulingMetadata(now.plusSeconds(1), -10)).allowed(),
        "失败方仍须服从既有占用与队列顺序");
  }

  @Test
  void multipleSwitcherQueueLosersCannotBackBlockIncumbentAndRemainSerialized() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId switcher = NodeId.of("SWITCHER:TEST:MULTI-MERGE");
    NodeId sharedExit = NodeId.of("TEST:MULTI-MERGE:EXIT");
    OccupancyResource conflict = OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyRequest incumbent =
        switcherRequest(
                "incumbent",
                now,
                conflict,
                List.of(NodeId.of("TEST:MULTI-MERGE:ENTRY:A"), switcher, sharedExit))
            .withSchedulingMetadata(now, 10);
    OccupancyRequest nextWinner =
        switcherRequest(
                "next-winner",
                now.plusMillis(1),
                conflict,
                List.of(NodeId.of("TEST:MULTI-MERGE:ENTRY:B"), switcher, sharedExit))
            .withSchedulingMetadata(now.plusMillis(1), 0);
    OccupancyRequest follower =
        switcherRequest(
                "follower",
                now.plusMillis(2),
                conflict,
                List.of(NodeId.of("TEST:MULTI-MERGE:ENTRY:C"), switcher, sharedExit))
            .withSchedulingMetadata(now.plusMillis(2), -10);

    assertTrue(manager.acquire(incumbent).allowed());
    assertFalse(manager.acquire(nextWinner).allowed());
    assertFalse(manager.acquire(follower).allowed());
    assertTrue(
        manager.scanAdvisoryRisks(incumbent.asLookaheadPreview()).isEmpty(),
        "多个失败方也不能通过 queue-only 风险反向阻停当前进路");

    manager.releaseByTrain("incumbent");

    assertTrue(
        manager.acquire(nextWinner.withSchedulingMetadata(now.plusSeconds(1), 0)).allowed(),
        "既有胜者清空后应按队列优先级放行下一列车");
    assertFalse(
        manager.canEnter(follower.withSchedulingMetadata(now.plusSeconds(1), -10)).allowed(),
        "后续列车仍须等待新的冲突区占用者");
  }

  @Test
  void incumbentQueueSuppressionDoesNotHideExternalLiveResourceRisk() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource switcher = OccupancyResource.forConflict("switcher:SWITCHER:TEST:LIVE-RISK");
    OccupancyResource downstreamNode =
        OccupancyResource.forNode(NodeId.of("TEST:LIVE-RISK:DOWNSTREAM"));
    OccupancyRequest incumbent =
        new OccupancyRequest(
            "incumbent", Optional.empty(), now, List.of(switcher), Map.of(), Map.of(), 10);
    OccupancyRequest waiting =
        new OccupancyRequest(
            "waiting",
            Optional.empty(),
            now.plusMillis(1),
            List.of(switcher),
            Map.of(),
            Map.of(),
            -10);
    OccupancyRequest external =
        new OccupancyRequest(
            "external", Optional.empty(), now.plusMillis(2), List.of(downstreamNode), Map.of());

    assertTrue(manager.acquire(incumbent).allowed());
    assertFalse(manager.acquire(waiting).allowed());
    assertTrue(manager.acquire(external).allowed());

    OccupancyRequest advisory =
        new OccupancyRequest(
                "incumbent",
                Optional.empty(),
                now.plusSeconds(1),
                List.of(switcher, downstreamNode),
                Map.of())
            .asLookaheadPreview();
    List<AdvisoryRisk> risks = manager.scanAdvisoryRisks(advisory);

    assertEquals(1, risks.size());
    assertEquals(downstreamNode, risks.get(0).resource());
    assertEquals("external", risks.get(0).claim().trainName());
    assertEquals(ClaimRole.MOVEMENT_REQUIRED, risks.get(0).claim().role());
  }

  @Test
  void jbsStyleSwitcherConflictAdmitsExactlyOneTrain() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource resource = OccupancyResource.forConflict("switcher:JBS");
    OccupancyRequest firstRequest =
        new OccupancyRequest("JBS-DS", Optional.empty(), now, List.of(resource), Map.of(), 0);
    OccupancyRequest secondRequest =
        new OccupancyRequest(
            "JBS-MT", Optional.empty(), now.plusMillis(1), List.of(resource), Map.of(), 0);

    OccupancyDecision first = manager.acquire(firstRequest);
    OccupancyDecision second = manager.acquire(secondRequest);

    assertTrue(first.allowed());
    assertFalse(second.allowed());
    assertEquals(1, manager.snapshotClaims().size());
    assertEquals("JBS-DS", manager.snapshotClaims().get(0).trainName());
  }

  @Test
  void crossingSwitcherSignaturesWithoutConcreteFootprintsRemainBlocked() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId switcher = NodeId.of("SWITCHER:Towny:-569:77:1181");
    OccupancyResource resource = OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyRequest jbsRoute =
        switcherRequest(
            "SURC-MT-LO-8935",
            now,
            resource,
            List.of(NodeId.of("SURC:SPB:JBS:2:002"), switcher, NodeId.of("SURC:SPB:JBS:2:003")));
    OccupancyRequest wsdRoute =
        switcherRequest(
            "SURC-MT-LH-0296",
            now.plusMillis(1),
            resource,
            List.of(
                NodeId.of("SURC:SPB:WSD:1:002"),
                switcher,
                NodeId.of("SWITCHER:Towny:-557:77:1193")));

    assertTrue(manager.acquire(jbsRoute).allowed());
    OccupancyDecision decision = manager.canEnter(wsdRoute);

    assertFalse(decision.allowed(), decision.toString());
  }

  @Test
  void crossingSwitcherMovementsRemainMutuallyExclusiveWithConcreteEdgeFootprints() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId switcher = NodeId.of("SWITCHER:Towny:-569:77:1181");
    OccupancyResource resource = OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyRequest jbsRoute =
        concreteSwitcherRequest(
            "SURC-MT-LO-8935",
            now,
            resource,
            List.of(NodeId.of("SURC:SPB:JBS:2:002"), switcher, NodeId.of("SURC:SPB:JBS:2:003")));
    OccupancyRequest wsdRoute =
        concreteSwitcherRequest(
            "SURC-MT-LH-0296",
            now.plusMillis(1),
            resource,
            List.of(
                NodeId.of("SURC:SPB:WSD:1:002"),
                switcher,
                NodeId.of("SWITCHER:Towny:-557:77:1193")));

    assertTrue(manager.acquire(jbsRoute).allowed());
    OccupancyDecision decision = manager.canEnter(wsdRoute);

    assertFalse(decision.allowed(), decision.toString());
    assertFalse(manager.acquire(wsdRoute).allowed());
    assertEquals(
        1,
        manager.snapshotClaims().stream()
            .filter(claim -> resource.equals(claim.resource()))
            .count());
  }

  @Test
  void partialSwitcherDrainSignatureDoesNotEnableTopologySharing() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId switcher = NodeId.of("SWITCHER:Towny:-569:77:1181");
    NodeId incumbentExit = NodeId.of("X");
    OccupancyResource resource = OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyResource incumbentExitEdge =
        OccupancyResource.forEdge(EdgeId.undirected(switcher, incumbentExit));
    OccupancyRequest partialDrain =
        switcherRequest(
            "inside",
            now,
            resource,
            List.of(switcher, incumbentExit),
            List.of(resource, incumbentExitEdge),
            1L,
            1L);
    OccupancyRequest crossing =
        concreteSwitcherRequest(
            "crossing",
            now.plusMillis(1),
            resource,
            List.of(NodeId.of("B"), switcher, NodeId.of("Y")));

    assertTrue(manager.acquire(partialDrain).allowed());

    OccupancyDecision decision = manager.canEnter(crossing);

    assertFalse(decision.allowed(), decision.toString());
  }

  @Test
  void unsignedClaimRefreshDeletesRememberedSwitcherEvidence() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId entry = NodeId.of("A");
    NodeId switcher = NodeId.of("SWITCHER:Towny:-569:77:1181");
    NodeId exit = NodeId.of("X");
    OccupancyResource section = OccupancyResource.forConflict("single:section:test:A~X");
    OccupancyResource resource = OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyRequest incumbent =
        switcherSectionRequest(
            "incumbent",
            now,
            section,
            resource,
            CorridorDirection.A_TO_B,
            List.of(entry, switcher, exit));
    OccupancyRequest follower =
        switcherSectionRequest(
            "follower",
            now.plusMillis(1),
            section,
            resource,
            CorridorDirection.A_TO_B,
            List.of(entry, switcher, exit));

    assertTrue(manager.acquire(incumbent).allowed());
    assertTrue(manager.canEnterPreview(follower).allowed());
    long signedVersion = manager.version();

    DirectedTraversalContext signedContext = incumbent.directedContext().orElseThrow();
    OccupancyRequest unsignedRefresh =
        new OccupancyRequest(
                incumbent.trainName(),
                Optional.empty(),
                now.plusSeconds(1),
                List.of(section, resource),
                Map.of(section.key(), CorridorDirection.A_TO_B))
            .withDirectedContext(
                Optional.of(
                    new DirectedTraversalContext(
                        signedContext.trainKey(),
                        signedContext.routeId(),
                        signedContext.currentIndex(),
                        signedContext.currentNode(),
                        signedContext.lastPassedGraphNode(),
                        signedContext.effectiveFromNode(),
                        signedContext.effectiveToNode(),
                        signedContext.expandedPathNodes(),
                        signedContext.directedEdges(),
                        signedContext.singleConflictDirections(),
                        Map.of(),
                        signedContext.source(),
                        signedContext.occupancyVersion(),
                        signedContext.progressVersion(),
                        signedContext.requestId(),
                        signedContext.authorityTokenId())));
    OccupancyDecision refreshDecision = manager.acquire(unsignedRefresh);
    assertTrue(refreshDecision.allowed(), refreshDecision.toString());

    assertEquals(signedVersion + 1, manager.version());
    assertFalse(manager.canEnterPreview(follower).allowed());
  }

  @Test
  void unsignedQueueRefreshDeletesRememberedSwitcherEvidenceOnce() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId switcher = NodeId.of("SWITCHER:Towny:-569:77:1181");
    OccupancyResource resource = OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyRequest owner =
        switcherRequest("owner", now, resource, List.of(NodeId.of("A"), switcher, NodeId.of("X")));
    OccupancyRequest signedWaiter =
        switcherRequest(
            "waiter",
            now.plusMillis(1),
            resource,
            List.of(NodeId.of("B"), switcher, NodeId.of("Y")));
    OccupancyRequest unsignedWaiter =
        new OccupancyRequest(
            signedWaiter.trainName(),
            Optional.empty(),
            now.plusSeconds(1),
            List.of(resource),
            Map.of());

    assertTrue(manager.acquire(owner).allowed());
    assertFalse(manager.acquire(signedWaiter).allowed());
    long signedQueueVersion = manager.version();

    assertFalse(manager.acquire(unsignedWaiter).allowed());
    assertEquals(signedQueueVersion + 1, manager.version());
    long unsignedQueueVersion = manager.version();

    assertFalse(
        manager
            .acquire(
                new OccupancyRequest(
                    signedWaiter.trainName(),
                    Optional.empty(),
                    now.plusSeconds(2),
                    List.of(resource),
                    Map.of()))
            .allowed());
    assertEquals(unsignedQueueVersion, manager.version());
  }

  @Test
  void sharedSwitcherPathSignatureStillBlocksOtherTrain() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId switcher = NodeId.of("SWITCHER:Towny:-569:77:1181");
    NodeId sharedExit = NodeId.of("SURC:SPB:JBS:2:003");
    OccupancyResource resource = OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyRequest first =
        switcherRequest(
            "first", now, resource, List.of(NodeId.of("SURC:SPB:JBS:2:002"), switcher, sharedExit));
    OccupancyRequest second =
        switcherRequest(
            "second",
            now.plusMillis(1),
            resource,
            List.of(NodeId.of("SURC:SPB:ALT:2:001"), switcher, sharedExit));

    assertTrue(manager.acquire(first).allowed());
    OccupancyDecision blocked = manager.acquire(second);

    assertFalse(blocked.allowed());
    assertEquals("first", blocked.blockers().get(0).trainName());
  }

  @Test
  void switcherConflictReleaseRejectsUnsignedIncumbentClaim() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId switcher = NodeId.of("SWITCHER:Towny:-566:77:1179");
    NodeId exit = NodeId.of("SURC:SPB:JBS:1:002");
    OccupancyResource conflict = OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyRequest unsignedOwner =
        new OccupancyRequest(
            "entrant",
            Optional.empty(),
            now,
            List.of(conflict),
            Map.of(),
            Map.of(conflict.key(), 0),
            0);
    OccupancyRequest clearing =
        switcherRequest("inside", now.plusSeconds(1), conflict, List.of(switcher, exit))
            .withConflictReleaseHints(
                AuthorizationPurpose.CONFLICT_CLEARING,
                Map.of(conflict.key(), ConflictReleaseHint.verified(conflict.key())));

    assertTrue(manager.acquire(unsignedOwner).allowed());
    OccupancyDecision decision = manager.canEnter(clearing);

    assertFalse(decision.allowed());
    assertFalse(decision.conflictRelease());
    assertEquals("entrant", decision.blockers().get(0).trainName());
  }

  @Test
  void switcherReleaseLockCannotBypassLaterSwitcherConflict() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId firstSwitcher = NodeId.of("SWITCHER:Towny:-566:77:1179");
    NodeId secondSwitcher = NodeId.of("SWITCHER:Towny:-557:77:1193");
    NodeId exit = NodeId.of("SURC:SPB:JBS:1:002");
    OccupancyResource firstConflict =
        OccupancyResource.forConflict("switcher:" + firstSwitcher.value());
    OccupancyResource secondConflict =
        OccupancyResource.forConflict("switcher:" + secondSwitcher.value());
    OccupancyResource firstNode = OccupancyResource.forNode(firstSwitcher);
    OccupancyRequest firstOwner =
        switcherRequest("first-owner", now, firstConflict, List.of(firstSwitcher, exit));
    OccupancyRequest secondOwner =
        switcherRequest(
            "second-owner", now.plusMillis(1), secondConflict, List.of(secondSwitcher, exit));

    assertTrue(manager.acquire(firstOwner).allowed());
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest("inside", Optional.empty(), now, List.of(firstNode), Map.of()))
            .allowed());
    OccupancyRequest firstClearing =
        VerifiedSwitcherDrainClaims.prepare(
                verifiedSwitcherRuntimeRequest(
                    "inside", now.plusSeconds(1), firstConflict, List.of(firstSwitcher, exit)),
                new VerifiedSwitcherDrainClaims.VerificationSnapshot(
                    manager.snapshotClaims(), 1L, 1L))
            .request();
    OccupancyDecision firstRelease = manager.canEnter(firstClearing);
    assertTrue(firstRelease.allowed(), firstRelease.toString());
    assertTrue(firstRelease.conflictRelease());
    assertTrue(manager.acquire(secondOwner).allowed());

    OccupancyRequest chainedRequest =
        new OccupancyRequest(
            "inside",
            Optional.empty(),
            now.plusSeconds(2),
            List.of(firstConflict, secondConflict),
            Map.of(),
            Map.of(firstConflict.key(), 0, secondConflict.key(), 1),
            0,
            AuthorizationPurpose.CONFLICT_CLEARING,
            firstClearing.conflictReleaseHints(),
            Map.of(),
            firstClearing.directedContext());
    OccupancyDecision preview = manager.canEnterPreview(chainedRequest);
    OccupancyDecision decision = manager.acquire(chainedRequest);

    assertFalse(preview.allowed(), preview.toString());
    assertFalse(preview.conflictRelease());
    assertFalse(decision.allowed(), decision.toString());
    assertFalse(decision.conflictRelease());
    assertTrue(
        decision.blockers().stream()
            .anyMatch(
                blocker ->
                    secondConflict.equals(blocker.resource())
                        && blocker.trainName().equals("second-owner")));
    assertFalse(
        manager.snapshotClaims().stream()
            .anyMatch(
                claim ->
                    secondConflict.equals(claim.resource()) && claim.trainName().equals("inside")));
  }

  @Test
  void sameDirectionSectionLetsFollowerShareSwitcherConflict() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId entry = NodeId.of("A");
    NodeId switcher = NodeId.of("SWITCHER:PPK");
    NodeId exit = NodeId.of("B");
    OccupancyResource section = OccupancyResource.forConflict("single:section:comp:A~B");
    OccupancyResource switcherConflict =
        OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyRequest leader =
        switcherSectionRequest(
            "leader",
            now,
            section,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(entry, switcher, exit));
    OccupancyRequest follower =
        switcherSectionRequest(
            "follower",
            now.plusSeconds(1),
            section,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(entry, switcher, exit));

    assertTrue(manager.acquire(leader).allowed());
    OccupancyDecision decision = manager.canEnter(follower);

    assertTrue(decision.allowed(), decision.toString());
  }

  @Test
  void sameDirectionSectionDoesNotTreatSwitcherMergeAsFollowingMovement() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId firstEntry = NodeId.of("SURC:S:JBS:1:001");
    NodeId secondEntry = NodeId.of("SURC:S:JBS:3:001");
    NodeId switcher = NodeId.of("SWITCHER:Towny:-520:77:1390");
    NodeId sharedExit = NodeId.of("SURC:JBS:CSB:2:001");
    OccupancyResource section = OccupancyResource.forConflict("single:section:SURC:JBS");
    OccupancyResource switcherConflict =
        OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyRequest first =
        switcherSectionRequest(
            "SURC-DS-LW-5912",
            now,
            section,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(firstEntry, switcher, sharedExit));
    OccupancyRequest second =
        switcherSectionRequest(
            "SURC-MT-LO-3495",
            now.plusMillis(1),
            section,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(secondEntry, switcher, sharedExit));

    assertTrue(manager.acquire(first).allowed());
    OccupancyDecision decision = manager.canEnter(second);

    assertFalse(decision.allowed());
    assertTrue(
        decision.blockers().stream()
            .anyMatch(blocker -> blocker.resource().equals(switcherConflict)));
  }

  @Test
  void sameDirectionSectionDoesNotShareTerminalStationThroatSwitcher() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId entry = NodeId.of("SURC:PPK:RVS:2:001");
    NodeId switcher = NodeId.of("SWITCHER:Towny:-583:65:630");
    NodeId platform = NodeId.of("SURC:S:PPK:2");
    OccupancyResource section = OccupancyResource.forConflict("single:section:comp:PPK~RVS");
    OccupancyResource switcherConflict =
        OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyRequest leader =
        switcherSectionRequest(
            "leader",
            now,
            section,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(entry, switcher, platform));
    OccupancyRequest follower =
        switcherSectionRequest(
            "follower",
            now.plusSeconds(1),
            section,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(entry, switcher, platform));

    assertTrue(manager.acquire(leader).allowed());
    OccupancyDecision decision = manager.canEnter(follower);

    assertFalse(decision.allowed());
    assertEquals(SignalAspect.STOP, decision.signal());
    assertTrue(
        decision.blockers().stream()
            .anyMatch(blocker -> blocker.resource().equals(switcherConflict)));
  }

  @Test
  void oppositeSectionStillBlocksSwitcherFollowThrough() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId nodeA = NodeId.of("A");
    NodeId switcher = NodeId.of("SWITCHER:PPK");
    NodeId nodeB = NodeId.of("B");
    OccupancyResource section = OccupancyResource.forConflict("single:section:comp:A~B");
    OccupancyResource switcherConflict =
        OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyRequest leader =
        switcherSectionRequest(
            "leader",
            now,
            section,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(nodeA, switcher, nodeB));
    OccupancyRequest opposite =
        switcherSectionRequest(
            "opposite",
            now.plusSeconds(1),
            section,
            switcherConflict,
            CorridorDirection.B_TO_A,
            List.of(nodeB, switcher, nodeA));

    assertTrue(manager.acquire(leader).allowed());
    OccupancyDecision decision = manager.canEnter(opposite);

    assertFalse(decision.allowed());
    assertEquals(SignalAspect.STOP, decision.signal());
  }

  @Test
  void sameDirectionSectionDoesNotBypassNodeHardBlocker() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId entry = NodeId.of("A");
    NodeId switcher = NodeId.of("SWITCHER:PPK");
    NodeId platform = NodeId.of("SURC:S:PPK:1");
    OccupancyResource section = OccupancyResource.forConflict("single:section:comp:A~B");
    OccupancyResource switcherConflict =
        OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyResource node = OccupancyResource.forNode(platform);
    OccupancyRequest leader =
        switcherSectionRequest(
            "leader",
            now,
            section,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(entry, switcher, platform),
            List.of(node));
    OccupancyRequest follower =
        switcherSectionRequest(
            "follower",
            now.plusSeconds(1),
            section,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(entry, switcher, platform),
            List.of(node));

    assertTrue(manager.acquire(leader).allowed());
    OccupancyDecision decision = manager.canEnter(follower);

    assertFalse(decision.allowed());
    assertTrue(decision.blockers().stream().anyMatch(blocker -> blocker.resource().equals(node)));
  }

  @Test
  void sameDirectionSectionDoesNotBypassPhysicalProtectiveClaims() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId entry = NodeId.of("A");
    NodeId exit = NodeId.of("B");
    OccupancyResource section = OccupancyResource.forConflict("single:section:comp:A~B");
    OccupancyResource edge = OccupancyResource.forEdge(EdgeId.undirected(entry, exit));
    List<ResourceIntent> protectiveIntents =
        List.of(ResourceIntent.PROTECTIVE_RETAIN, ResourceIntent.HOLD_ONLY);
    for (ResourceIntent protectiveIntent : protectiveIntents) {
      SimpleOccupancyManager manager =
          new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());
      OccupancyRequest leader =
          sectionPhysicalRequest(
              "leader", now, section, CorridorDirection.A_TO_B, edge, protectiveIntent);
      OccupancyRequest follower =
          sectionPhysicalRequest(
              "follower",
              now.plusSeconds(1),
              section,
              CorridorDirection.A_TO_B,
              edge,
              ResourceIntent.MOVEMENT_REQUIRED);

      assertTrue(manager.acquire(leader).allowed());
      OccupancyDecision decision = manager.canEnter(follower);

      assertFalse(decision.allowed(), protectiveIntent + ": " + decision);
      assertTrue(decision.blockers().stream().anyMatch(blocker -> blocker.resource().equals(edge)));
    }
  }

  @Test
  void sameDirectionSectionDoesNotBypassPhysicalMovementRequired() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId entry = NodeId.of("A");
    NodeId exit = NodeId.of("B");
    OccupancyResource section = OccupancyResource.forConflict("single:section:comp:A~B");
    OccupancyResource edge = OccupancyResource.forEdge(EdgeId.undirected(entry, exit));
    OccupancyRequest leader =
        sectionPhysicalRequest(
            "leader",
            now,
            section,
            CorridorDirection.A_TO_B,
            edge,
            ResourceIntent.MOVEMENT_REQUIRED);
    OccupancyRequest follower =
        sectionPhysicalRequest(
            "follower",
            now.plusSeconds(1),
            section,
            CorridorDirection.A_TO_B,
            edge,
            ResourceIntent.MOVEMENT_REQUIRED);

    assertTrue(manager.acquire(leader).allowed());
    OccupancyDecision decision = manager.canEnter(follower);

    assertFalse(decision.allowed());
    assertTrue(decision.blockers().stream().anyMatch(blocker -> blocker.resource().equals(edge)));
  }

  @Test
  void sameDirectionSectionDoesNotBypassStationBoundaryProtectiveRetain() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId station = NodeId.of("SURC:S:SPB:1");
    NodeId throat = NodeId.of("SURC:SPB:JBS:1:001");
    OccupancyResource section = OccupancyResource.forConflict("single:section:comp:JBS~SPB");
    OccupancyResource edge = OccupancyResource.forEdge(EdgeId.undirected(station, throat));
    OccupancyRequest leader =
        sectionPhysicalRequest(
            "leader",
            now,
            section,
            CorridorDirection.A_TO_B,
            edge,
            ResourceIntent.PROTECTIVE_RETAIN);
    OccupancyRequest follower =
        sectionPhysicalRequest(
            "follower",
            now.plusSeconds(1),
            section,
            CorridorDirection.A_TO_B,
            edge,
            ResourceIntent.MOVEMENT_REQUIRED);

    assertTrue(manager.acquire(leader).allowed());
    OccupancyDecision decision = manager.canEnter(follower);

    assertFalse(decision.allowed());
    assertTrue(decision.blockers().stream().anyMatch(blocker -> blocker.resource().equals(edge)));
  }

  @Test
  void sameDirectionFrontHardBlockerDoesNotRejectSelfOwnedSectionContinuation() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId entry = NodeId.of("A");
    NodeId switcher = NodeId.of("SWITCHER:PPK");
    NodeId frontNode = NodeId.of("B");
    OccupancyResource section = OccupancyResource.forConflict("single:section:comp:A~B");
    OccupancyResource switcherConflict =
        OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyResource node = OccupancyResource.forNode(frontNode);
    OccupancyResource edge = OccupancyResource.forEdge(EdgeId.undirected(switcher, frontNode));
    OccupancyRequest leader =
        switcherSectionRequest(
            "leader",
            now,
            section,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(entry, switcher, frontNode),
            List.of(node, edge));
    OccupancyRequest followerInitial =
        switcherSectionRequest(
            "follower",
            now.plusSeconds(1),
            section,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(entry, switcher, frontNode));
    OccupancyRequest followerWithFrontBlocker =
        switcherSectionRequest(
            "follower",
            now.plusSeconds(2),
            section,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(entry, switcher, frontNode),
            List.of(node, edge));

    assertTrue(manager.acquire(leader).allowed());
    assertTrue(manager.acquire(followerInitial).allowed());
    OccupancyDecision decision = manager.canEnter(followerWithFrontBlocker);

    assertFalse(decision.allowed());
    assertEquals("none", decision.reason());
    assertTrue(
        decision.blockers().stream()
            .anyMatch(
                blocker -> blocker.resource().equals(node) || blocker.resource().equals(edge)));
  }

  @Test
  void sameDirectionSectionQueueDoesNotSerializeSwitcherQueue() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId entry = NodeId.of("A");
    NodeId switcher = NodeId.of("SWITCHER:PPK");
    NodeId exit = NodeId.of("B");
    OccupancyResource section = OccupancyResource.forConflict("single:section:comp:A~B");
    OccupancyResource switcherConflict =
        OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyRequest waitingLeader =
        switcherSectionRequest(
            "leader",
            now,
            section,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(entry, switcher, exit));
    OccupancyRequest follower =
        switcherSectionRequest(
            "follower",
            now.plusSeconds(1),
            section,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(entry, switcher, exit));

    manager.touchQueues(waitingLeader);
    OccupancyDecision decision = manager.canEnter(follower);

    assertTrue(decision.allowed(), decision.toString());
  }

  @Test
  void sameDirectionSectionSwitcherClaimIsNotAdvisoryStopPoint() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId entry = NodeId.of("A");
    NodeId switcher = NodeId.of("SWITCHER:PPK");
    NodeId exit = NodeId.of("B");
    OccupancyResource section = OccupancyResource.forConflict("single:section:comp:A~B");
    OccupancyResource switcherConflict =
        OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyRequest leader =
        switcherSectionRequest(
            "leader",
            now,
            section,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(entry, switcher, exit));
    OccupancyRequest advisory =
        switcherSectionRequest(
                "follower",
                now.plusSeconds(1),
                section,
                switcherConflict,
                CorridorDirection.A_TO_B,
                List.of(entry, switcher, exit))
            .asLookaheadPreview();

    assertTrue(manager.acquire(leader).allowed());
    List<AdvisoryRisk> risks = manager.scanAdvisoryRisks(advisory);

    assertTrue(risks.isEmpty(), "同向 section 内的 switcher claim 不能继续作为前车停车点");
  }

  @Test
  void sameDirectionSwitcherCorridorLetsFollowerShareSwitcherConflict() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId entry = NodeId.of("SURC:SPB:JBS:1:003");
    NodeId switcherA = NodeId.of("SWITCHER:Towny:-555:77:1196");
    NodeId switcherB = NodeId.of("SWITCHER:Towny:-557:77:1193");
    NodeId sharedSwitcher = NodeId.of("SWITCHER:Towny:-566:77:1179");
    NodeId exit = NodeId.of("SURC:SPB:JBS:1:002");
    OccupancyResource corridor =
        OccupancyResource.forConflict(
            "single:SURC:CGL:WYB:1:001:"
                + "SWITCHER:Towny:-566:77:1179~SWITCHER:Towny:-579:65:650");
    OccupancyResource switcherConflict =
        OccupancyResource.forConflict("switcher:" + sharedSwitcher.value());
    OccupancyRequest leader =
        switcherSectionRequest(
            "leader",
            now,
            corridor,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(entry, switcherA, switcherB, sharedSwitcher, exit));
    OccupancyRequest follower =
        switcherSectionRequest(
            "follower",
            now.plusSeconds(1),
            corridor,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(entry, switcherA, switcherB, sharedSwitcher, exit));

    assertTrue(manager.acquire(leader).allowed());
    OccupancyDecision decision = manager.canEnter(follower);

    assertTrue(decision.allowed(), decision.toString());
  }

  @Test
  void sameDirectionFollowerProofAcceptsSwitcherCorridorConflict() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId entry = NodeId.of("SURC:SPB:JBS:1:003");
    NodeId switcherA = NodeId.of("SWITCHER:Towny:-555:77:1196");
    NodeId switcherB = NodeId.of("SWITCHER:Towny:-557:77:1193");
    NodeId sharedSwitcher = NodeId.of("SWITCHER:Towny:-566:77:1179");
    NodeId exit = NodeId.of("SURC:SPB:JBS:1:002");
    OccupancyResource corridor =
        OccupancyResource.forConflict(
            "single:SURC:CGL:WYB:1:001:"
                + "SWITCHER:Towny:-566:77:1179~SWITCHER:Towny:-579:65:650");
    OccupancyResource switcherConflict =
        OccupancyResource.forConflict("switcher:" + sharedSwitcher.value());
    OccupancyRequest leader =
        switcherSectionRequest(
            "leader",
            now,
            corridor,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(entry, switcherA, switcherB, sharedSwitcher, exit));
    OccupancyRequest follower =
        switcherSectionRequest(
            "follower",
            now.plusSeconds(1),
            corridor,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(entry, switcherA, switcherB, sharedSwitcher, exit));

    assertTrue(manager.acquire(leader).allowed());

    assertTrue(
        manager.isProvenSameDirectionFollower(follower, switcherConflict, "leader"),
        "运行时风险层应能复用同向 switcher-to-switcher corridor 证明");
  }

  @Test
  void sameDirectionSwitcherCorridorQueueIsNotAdvisoryStopPoint() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId entry = NodeId.of("SURC:SPB:JBS:1:003");
    NodeId switcherA = NodeId.of("SWITCHER:Towny:-555:77:1196");
    NodeId switcherB = NodeId.of("SWITCHER:Towny:-557:77:1193");
    NodeId sharedSwitcher = NodeId.of("SWITCHER:Towny:-566:77:1179");
    NodeId exit = NodeId.of("SURC:SPB:JBS:1:002");
    OccupancyResource corridor =
        OccupancyResource.forConflict(
            "single:SURC:CGL:WYB:1:001:"
                + "SWITCHER:Towny:-566:77:1179~SWITCHER:Towny:-579:65:650");
    OccupancyResource switcherConflict =
        OccupancyResource.forConflict("switcher:" + sharedSwitcher.value());
    OccupancyRequest leader =
        switcherSectionRequest(
            "leader",
            now,
            corridor,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(entry, switcherA, switcherB, sharedSwitcher, exit));
    OccupancyRequest follower =
        switcherSectionRequest(
            "follower",
            now.plusSeconds(1),
            corridor,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(entry, switcherA, switcherB, sharedSwitcher, exit));

    manager.touchQueues(follower);
    List<AdvisoryRisk> risks = manager.scanAdvisoryRisks(leader.asLookaheadPreview());

    assertTrue(risks.isEmpty(), "同向 switcher-to-switcher single 队列不能变成前车停车点");
  }

  @Test
  void sameDirectionFollowerProofRejectsPhysicalNode() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource node = OccupancyResource.forNode(NodeId.of("SURC:S:PPK:1"));
    assertTrue(
        manager
            .acquire(new OccupancyRequest("leader", Optional.empty(), now, List.of(node), Map.of()))
            .allowed());
    OccupancyRequest follower =
        new OccupancyRequest(
            "follower", Optional.empty(), now.plusSeconds(1), List.of(node), Map.of());

    assertFalse(manager.isProvenSameDirectionFollower(follower, node, "leader"));
    assertFalse(manager.isProvenSameDirectionFollower(follower, node, "leader", true));
  }

  @Test
  void sameDirectionFollowerProofAcceptsKnownRouteLeaderWithoutSharedSectionClaim() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource section = OccupancyResource.forConflict("single:test:A~B");
    OccupancyRequest follower =
        new OccupancyRequest("follower", Optional.empty(), now, List.of(section), Map.of());

    assertFalse(manager.isProvenSameDirectionFollower(follower, section, "leader"));
    assertTrue(
        manager.isProvenSameDirectionFollower(follower, section, "leader", true),
        "RouteProgressRegistry 已证明同 route 前车时，不再要求双方 claim 落在同一 section 实例");
  }

  @Test
  void sameDirectionFollowerProofRejectsKnownRouteLeaderSwitcherWithoutMovementSignature() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource switcher =
        OccupancyResource.forConflict("switcher:SWITCHER:Towny:-555:77:1196");
    OccupancyRequest follower =
        switcherRequest(
            "follower",
            now,
            switcher,
            List.of(
                NodeId.of("SURC:SPB:JBS:1:003"),
                NodeId.of("SWITCHER:Towny:-555:77:1196"),
                NodeId.of("SWITCHER:Towny:-557:77:1193")));

    assertFalse(manager.isProvenSameDirectionFollower(follower, switcher, "leader"));
    assertFalse(
        manager.isProvenSameDirectionFollower(follower, switcher, "leader", true),
        "runtime 同 route 前车证明不能替代 exact switcher movement signature");
  }

  @Test
  void sameDirectionSwitcherCorridorDoesNotShareTerminalStationThroatSwitcher() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId entry = NodeId.of("SURC:PPK:RVS:2:001");
    NodeId switcher = NodeId.of("SWITCHER:Towny:-583:65:630");
    NodeId platform = NodeId.of("SURC:S:PPK:2");
    OccupancyResource corridor =
        OccupancyResource.forConflict(
            "single:SURC:CGL:WYB:1:001:" + "SWITCHER:Towny:-583:65:630~SWITCHER:Towny:-583:65:650");
    OccupancyResource switcherConflict =
        OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyRequest leader =
        switcherSectionRequest(
            "leader",
            now,
            corridor,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(entry, switcher, platform));
    OccupancyRequest follower =
        switcherSectionRequest(
            "follower",
            now.plusSeconds(1),
            corridor,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(entry, switcher, platform));

    assertTrue(manager.acquire(leader).allowed());
    OccupancyDecision decision = manager.canEnter(follower);

    assertFalse(decision.allowed());
    assertEquals(SignalAspect.STOP, decision.signal());
    assertTrue(
        decision.blockers().stream()
            .anyMatch(blocker -> blocker.resource().equals(switcherConflict)));
  }

  @Test
  void sameDirectionFollowerProofRejectsTerminalStationThroatSwitcher() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId entry = NodeId.of("SURC:PPK:RVS:2:001");
    NodeId switcher = NodeId.of("SWITCHER:Towny:-583:65:630");
    NodeId platform = NodeId.of("SURC:S:PPK:2");
    OccupancyResource corridor =
        OccupancyResource.forConflict(
            "single:SURC:CGL:WYB:1:001:" + "SWITCHER:Towny:-583:65:630~SWITCHER:Towny:-583:65:650");
    OccupancyResource switcherConflict =
        OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyRequest leader =
        switcherSectionRequest(
            "leader",
            now,
            corridor,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(entry, switcher, platform));
    OccupancyRequest follower =
        switcherSectionRequest(
            "follower",
            now.plusSeconds(1),
            corridor,
            switcherConflict,
            CorridorDirection.A_TO_B,
            List.of(entry, switcher, platform));

    assertTrue(manager.acquire(leader).allowed());

    assertFalse(
        manager.isProvenSameDirectionFollower(follower, switcherConflict, "leader"),
        "终端站台相邻 switcher 必须继续由咽喉互斥保护");
    assertFalse(
        manager.isProvenSameDirectionFollower(follower, switcherConflict, "leader", true),
        "即使 runtime 已证明同 route 前车，终端站台相邻 switcher 仍必须互斥");
  }

  @Test
  void crossingSignatureAloneDoesNotBypassPendingSwitcherWinner() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId switcher = NodeId.of("SWITCHER:Towny:-569:77:1181");
    OccupancyResource resource = OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyRequest first =
        switcherRequest(
            "SURC-MT-LH-0296",
            now,
            resource,
            List.of(switcher, NodeId.of("SWITCHER:Towny:-557:77:1193")));
    OccupancyRequest second =
        switcherRequest(
            "SURC-MT-LO-8935",
            now.plusMillis(1),
            resource,
            List.of(NodeId.of("SURC:SPB:JBS:2:002"), switcher, NodeId.of("SURC:SPB:JBS:2:003")));

    assertTrue(manager.acquire(first).allowed());
    assertTrue(
        manager.releaseResourceRetainingQueuePosition(
            resource, Optional.of(first.trainName()), first));

    OccupancyDecision decision = manager.canEnterPreview(second);

    assertFalse(decision.allowed(), decision.toString());
  }

  @Test
  void disjointSwitcherSignatureDoesNotBypassTerminalStationThroatMutex() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    NodeId switcher = NodeId.of("SWITCHER:Towny:-583:65:630");
    NodeId exit = NodeId.of("SWITCHER:Towny:-583:65:650");
    NodeId oppositeEntry = NodeId.of("SWITCHER:Towny:-581:65:637");
    NodeId platform = NodeId.of("SURC:S:PPK:2");
    OccupancyResource resource = OccupancyResource.forConflict("switcher:" + switcher.value());
    OccupancyRequest outbound = switcherRequest("outbound", now, resource, List.of(switcher, exit));
    OccupancyRequest terminalApproach =
        switcherRequest(
            "terminal-approach",
            now.plusMillis(1),
            resource,
            List.of(oppositeEntry, switcher, platform));

    assertTrue(manager.acquire(outbound).allowed());
    OccupancyDecision decision = manager.canEnterPreview(terminalApproach);

    assertFalse(decision.allowed());
    assertEquals(SignalAspect.STOP, decision.signal());
  }

  @Test
  void recoverableReleaseRetainsQueueWinnerAgainstLowerPriorityProtectiveUpgrade() {
    List<String> traces = new java.util.ArrayList<>();
    SignalComputationTrace.configureLogger(traces::add);
    try {
      HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
      SimpleOccupancyManager manager =
          new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

      Instant now = Instant.parse("2026-01-01T00:00:00Z");
      OccupancyResource resource = OccupancyResource.forConflict("switcher:JBS");
      OccupancyRequest dsRequest =
          new OccupancyRequest(
              "JBS-DS",
              Optional.empty(),
              now,
              List.of(resource),
              Map.of(),
              Map.of(resource.key(), 0),
              10);
      OccupancyRequest mtRetain =
          new OccupancyRequest(
                  "JBS-MT",
                  Optional.empty(),
                  now.plusMillis(1),
                  List.of(resource),
                  Map.of(),
                  Map.of(resource.key(), 0),
                  -10)
              .withResourceIntents(Map.of(resource, ResourceIntent.PROTECTIVE_RETAIN));
      OccupancyRequest mtMove =
          new OccupancyRequest(
              "JBS-MT",
              Optional.empty(),
              now.plusMillis(2),
              List.of(resource),
              Map.of(),
              Map.of(resource.key(), 0),
              -10);

      assertTrue(manager.acquire(dsRequest).allowed());
      assertTrue(
          manager.releaseResourceRetainingQueuePosition(
              resource, Optional.of("JBS-DS"), dsRequest));
      assertEquals("JBS-DS", manager.snapshotQueues().get(0).entries().get(0).trainName());
      assertTrue(manager.acquire(mtRetain).allowed());

      OccupancyDecision mtBlocked = manager.canEnter(mtMove);

      assertFalse(mtBlocked.allowed());
      assertEquals("JBS-DS", mtBlocked.blockers().get(0).trainName());
      assertEquals(ClaimRole.QUEUE_POSITION, mtBlocked.blockers().get(0).role());
      assertTrue(
          traces.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_PENDING_WINNER_ARBITRATION")
                          && message.contains("requesterTrain=JBS-MT")
                          && message.contains("pendingWinnerTrain=JBS-DS")
                          && message.contains("pendingWinnerWaitSeconds=")
                          && message.contains("priorityAdvantageCapSeconds=120")
                          && message.contains("decision=hold-lower-priority")));

      assertTrue(manager.acquire(dsRequest).allowed());
      assertTrue(
          manager.snapshotClaims().stream()
              .anyMatch(
                  claim ->
                      claim.resource().equals(resource)
                          && claim.trainName().equals("JBS-DS")
                          && claim.role() == ClaimRole.MOVEMENT_REQUIRED));
      assertFalse(
          manager.snapshotClaims().stream()
              .anyMatch(
                  claim ->
                      claim.resource().equals(resource)
                          && claim.trainName().equals("JBS-MT")
                          && claim.role() == ClaimRole.MOVEMENT_REQUIRED));
    } finally {
      SignalComputationTrace.configureLogger(null);
    }
  }

  @Test
  void plainReleaseDoesNotRetainQueueWinner() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource resource = OccupancyResource.forConflict("switcher:JBS");
    OccupancyRequest dsRequest =
        new OccupancyRequest(
            "JBS-DS",
            Optional.empty(),
            now,
            List.of(resource),
            Map.of(),
            Map.of(resource.key(), 0),
            10);
    OccupancyRequest mtRequest =
        new OccupancyRequest(
            "JBS-MT",
            Optional.empty(),
            now.plusMillis(1),
            List.of(resource),
            Map.of(),
            Map.of(resource.key(), 0),
            -10);

    assertTrue(manager.acquire(dsRequest).allowed());
    assertTrue(manager.releaseResource(resource, Optional.of("JBS-DS")));

    OccupancyDecision mtDecision = manager.acquire(mtRequest);

    assertTrue(mtDecision.allowed());
    assertTrue(
        manager.snapshotClaims().stream()
            .anyMatch(
                claim ->
                    claim.resource().equals(resource)
                        && claim.trainName().equals("JBS-MT")
                        && claim.role() == ClaimRole.MOVEMENT_REQUIRED));
    assertFalse(
        manager.snapshotQueues().stream()
            .flatMap(snapshot -> snapshot.entries().stream())
            .anyMatch(entry -> entry.trainName().equals("JBS-DS")));
  }

  @Test
  void hhuStyleCreatePriorityStaysAheadOfReturnWhenBothWaitForMainlineCutIn() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource resource = OccupancyResource.forConflict("switcher:HHU-mainline-cut");
    int createPriority = DispatchPriorityPolicy.runtimePriority(0, RouteOperationType.CREATE, true);
    int returnPriority =
        DispatchPriorityPolicy.runtimePriority(0, RouteOperationType.RETURN, false);
    OccupancyRequest returnRequest =
        new OccupancyRequest(
            "HHU-return",
            Optional.empty(),
            now,
            List.of(resource),
            Map.of(),
            Map.of(resource.key(), 0),
            returnPriority);
    OccupancyRequest createRequest =
        new OccupancyRequest(
            "HHU-create",
            Optional.empty(),
            now.plusMillis(1),
            List.of(resource),
            Map.of(),
            Map.of(resource.key(), 0),
            createPriority);
    manager.touchQueues(returnRequest);
    manager.touchQueues(createRequest);

    assertTrue(manager.canEnter(createRequest).allowed());
    assertFalse(manager.canEnter(returnRequest).allowed());
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
  void runtimeMoveDoesNotReleaseNodeDeadlockByEntryOrder() {
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
    assertFalse(nearDecision.allowed());
    assertFalse(nearDecision.conflictRelease());
  }

  @Test
  void runtimeMoveDoesNotReleaseLaterConflictWhenBlockerIsHardNode() {
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

    assertFalse(decision.allowed());
    assertFalse(decision.conflictRelease());
  }

  @Test
  void conflictReleaseAcquireBlocksHardNodeResource() {
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
            withClearingHint(
                new OccupancyRequest(
                    "tReq",
                    Optional.empty(),
                    now.plusSeconds(1),
                    List.of(conflict, nodeA, nodeB),
                    forward,
                    Map.of(conflict.key(), 0),
                    0,
                    AuthorizationPurpose.CONFLICT_CLEARING),
                conflict));

    assertFalse(decision.allowed());
    assertFalse(decision.conflictRelease());
    assertEquals("conflict-release-hard-blocker:" + nodeB, decision.reason());
    assertFalse(
        manager.snapshotClaims().stream()
            .anyMatch(claim -> nodeB.equals(claim.resource()) && claim.trainName().equals("tReq")));
    assertTrue(
        manager.snapshotClaims().stream()
            .anyMatch(
                claim -> nodeB.equals(claim.resource()) && claim.trainName().equals("tBlocker")));
  }

  @Test
  void conflictReleaseAcquireBlocksHardEdgeResource() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyResource edge =
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("B"), NodeId.of("C")));
    Map<String, CorridorDirection> forward = Map.of(conflict.key(), CorridorDirection.A_TO_B);
    Map<String, CorridorDirection> reverse = Map.of(conflict.key(), CorridorDirection.B_TO_A);

    manager.acquire(
        new OccupancyRequest("tBlocker", Optional.empty(), now, List.of(edge), Map.of()));
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
        manager.canEnter(
            withClearingHint(
                new OccupancyRequest(
                    "tReq",
                    Optional.empty(),
                    now.plusSeconds(1),
                    List.of(conflict, edge),
                    forward,
                    Map.of(conflict.key(), 0),
                    0,
                    AuthorizationPurpose.CONFLICT_CLEARING),
                conflict));

    assertFalse(decision.allowed());
    assertEquals("conflict-release-hard-blocker:" + edge, decision.reason());
  }

  @Test
  void conflictReleaseTreatsPhysicalConflictFootprintAsHardBlocker() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource crossing = OccupancyResource.forConflict("switcher:terminal-crossing");
    OccupancyRequest clearing =
        new OccupancyRequest(
            "requester",
            Optional.empty(),
            now.plusSeconds(1),
            List.of(crossing),
            Map.of(),
            Map.of(),
            0,
            AuthorizationPurpose.CONFLICT_CLEARING);
    OccupancyClaim footprint =
        new OccupancyClaim(
            crossing,
            "turning-train",
            Optional.empty(),
            now,
            Duration.ZERO,
            Optional.empty(),
            ClaimRole.PHYSICAL_FOOTPRINT);
    Optional<String> reason =
        manager.conflictReleaseHardBlockerReason(clearing, List.of(footprint));

    assertEquals(Optional.of("conflict-release-hard-blocker:" + crossing), reason);
  }

  @Test
  void strictCycleProtectiveClaimBlocksMovementAndConflictRelease() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource cycle = OccupancyResource.forConflict("single:component:cycle:LOOP");
    OccupancyRequest retainedCycle =
        new OccupancyRequest(
            "incumbent",
            Optional.empty(),
            now,
            List.of(cycle),
            Map.of(),
            Map.of(),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            Map.of(),
            Map.of(cycle, ResourceIntent.HOLD_ONLY));
    OccupancyRequest movement =
        new OccupancyRequest(
            "requester", Optional.empty(), now.plusSeconds(1), List.of(cycle), Map.of());

    assertTrue(manager.acquire(retainedCycle).allowed());
    OccupancyClaim incumbent = manager.getClaim(cycle).orElseThrow();
    assertEquals(ClaimRole.HOLD_ONLY, incumbent.role());
    assertFalse(manager.canEnter(movement).allowed());

    OccupancyRequest clearing =
        new OccupancyRequest(
            "requester",
            Optional.empty(),
            now.plusSeconds(2),
            List.of(cycle),
            Map.of(),
            Map.of(),
            0,
            AuthorizationPurpose.CONFLICT_CLEARING);

    assertEquals(
        Optional.of("conflict-release-hard-blocker:" + cycle),
        manager.conflictReleaseHardBlockerReason(clearing, List.of(incumbent)));
  }

  @Test
  void conflictReleaseAcquireSkipsOnlyConflictBlocker() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    Map<String, CorridorDirection> forward = Map.of(conflict.key(), CorridorDirection.A_TO_B);
    Map<String, CorridorDirection> reverse = Map.of(conflict.key(), CorridorDirection.B_TO_A);

    manager.acquire(
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
            withClearingHint(
                new OccupancyRequest(
                    "tReq",
                    Optional.empty(),
                    now.plusSeconds(1),
                    List.of(conflict),
                    forward,
                    Map.of(conflict.key(), 0),
                    0,
                    AuthorizationPurpose.CONFLICT_CLEARING),
                conflict));

    assertFalse(decision.allowed());
    assertFalse(decision.conflictRelease());
    assertEquals(
        SimpleOccupancyManager.OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER, decision.reason());
    assertFalse(
        manager.snapshotClaims().stream()
            .anyMatch(
                claim -> conflict.equals(claim.resource()) && claim.trainName().equals("tReq")));
    assertTrue(
        manager.snapshotClaims().stream()
            .anyMatch(
                claim ->
                    conflict.equals(claim.resource()) && claim.trainName().equals("tBlocker")));
  }

  @Test
  void depotSpawnRequestDoesNotConflictReleaseEvenWithHint() {
    assertPurposeDoesNotRelease(AuthorizationPurpose.DEPOT_SPAWN);
  }

  @Test
  void stationDepartureRequestDoesNotConflictReleaseEvenWithHint() {
    assertPurposeDoesNotRelease(AuthorizationPurpose.STATION_DEPARTURE);
  }

  @Test
  void conflictClearingWithoutInsideExitHintStaysBlocked() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    Map<String, CorridorDirection> forward = Map.of(conflict.key(), CorridorDirection.A_TO_B);
    Map<String, CorridorDirection> reverse = Map.of(conflict.key(), CorridorDirection.B_TO_A);

    manager.acquire(
        new OccupancyRequest(
            "tBlocker",
            Optional.empty(),
            now,
            List.of(conflict),
            reverse,
            Map.of(conflict.key(), 2),
            0));

    OccupancyDecision decision =
        manager.canEnter(
            new OccupancyRequest(
                "tReq",
                Optional.empty(),
                now.plusSeconds(1),
                List.of(conflict),
                forward,
                Map.of(conflict.key(), 0),
                0,
                AuthorizationPurpose.CONFLICT_CLEARING));

    assertFalse(decision.allowed());
    assertFalse(decision.conflictRelease());
  }

  @Test
  void deadlockReleaseLockCreationAndExpiryAdvanceVersionAndEmitLifecycle() {
    List<String> traces = new java.util.ArrayList<>();
    SignalComputationTrace.configureLogger(traces::add);
    try {
      SimpleOccupancyManager manager =
          new SimpleOccupancyManager(
              (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
      Instant now = Instant.parse("2026-01-01T00:00:00Z");
      long initialVersion = manager.version();

      manager.rememberDeadlockReleaseLock(
          "switcher:versioned-release", "winner", now.plusSeconds(8));

      assertEquals(initialVersion + 1, manager.version());
      long lockedVersion = manager.version();

      OccupancyRequest purgeProbe =
          new OccupancyRequest("probe", Optional.empty(), now.plusSeconds(10), List.of(), Map.of());
      assertTrue(manager.canEnter(purgeProbe).allowed());
      assertEquals(lockedVersion + 1, manager.version());
      assertTrue(
          traces.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_QUEUE_LIFECYCLE")
                          && message.contains("resource=CONFLICT:switcher:versioned-release")
                          && message.contains("entryType=DEADLOCK_RELEASE_LOCK")
                          && message.contains("event=acquire")
                          && message.contains("train=winner")),
          traces::toString);
      assertTrue(
          traces.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_QUEUE_LIFECYCLE")
                          && message.contains("resource=CONFLICT:switcher:versioned-release")
                          && message.contains("entryType=DEADLOCK_RELEASE_LOCK")
                          && message.contains("event=expire")
                          && message.contains("train=winner")
                          && message.contains("reason=ttl-expired")),
          traces::toString);
    } finally {
      SignalComputationTrace.configureLogger(null);
    }
  }

  @Test
  void deadlockReleaseLockExpiryPublishesArbitrationChange() {
    SignalEventBus eventBus = new SignalEventBus();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy(), eventBus);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("switcher:expired-release-lock");
    manager.rememberDeadlockReleaseLock(conflict.key(), "old-winner", now.plusSeconds(8));
    manager.touchQueues(
        new OccupancyRequest(
            "waiting-train", Optional.empty(), now, List.of(conflict), Map.of(), 0));
    List<OccupancyQueueChangedEvent> events = new java.util.ArrayList<>();
    eventBus.subscribe(OccupancyQueueChangedEvent.class, events::add);

    manager.canEnter(
        new OccupancyRequest(
            "probe", Optional.empty(), now.plusSeconds(10), List.of(), Map.of(), 0));

    assertTrue(
        events.stream()
            .anyMatch(
                event ->
                    event.sourceTrainName().equals("*")
                        && event.affectedResources().equals(List.of(conflict))));
  }

  @Test
  void deadlockReleaseLockLifecycleAuditsOwnerRelease() {
    List<String> traces = new java.util.ArrayList<>();
    SignalComputationTrace.configureLogger(traces::add);
    try {
      SimpleOccupancyManager manager =
          new SimpleOccupancyManager(
              (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
      Instant now = Instant.parse("2026-01-01T00:00:00Z");
      OccupancyResource releaseLock = OccupancyResource.forConflict("switcher:released-with-owner");
      manager.rememberDeadlockReleaseLock(releaseLock.key(), "winner", now.plusSeconds(8));

      manager.releaseByTrain("winner");

      assertTrue(
          traces.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_QUEUE_LIFECYCLE")
                          && message.contains("resource=" + releaseLock)
                          && message.contains("entryType=DEADLOCK_RELEASE_LOCK")
                          && message.contains("event=release")
                          && message.contains("train=winner")
                          && message.contains("reason=release-by-train")),
          traces::toString);
    } finally {
      SignalComputationTrace.configureLogger(null);
    }
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
  void conflictDeadlockReleaseFailsClosedWhenDirectionUnknown() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyResource nodeB = OccupancyResource.forNode(NodeId.of("B"));
    Map<String, CorridorDirection> reverse = Map.of(conflict.key(), CorridorDirection.B_TO_A);

    manager.acquire(
        new OccupancyRequest("blocker", Optional.empty(), now, List.of(nodeB), java.util.Map.of()));
    manager.canEnter(
        new OccupancyRequest(
            "blocker",
            Optional.empty(),
            now,
            List.of(conflict),
            reverse,
            Map.of(conflict.key(), 1),
            0));

    OccupancyDecision decision =
        manager.canEnter(
            new OccupancyRequest(
                "requester",
                Optional.empty(),
                now.plusSeconds(1),
                List.of(conflict, nodeB),
                Map.of(conflict.key(), CorridorDirection.UNKNOWN),
                Map.of(conflict.key(), 0),
                10));

    assertFalse(decision.allowed());
    assertFalse(decision.conflictRelease());
    assertEquals(SignalAspect.STOP, decision.signal());
  }

  @Test
  void unknownDirectionSingleCorridorEntryWithoutPresenceDoesNotFailClosed() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");

    OccupancyDecision decision =
        manager.canEnter(
            new OccupancyRequest(
                "requester",
                Optional.empty(),
                now,
                List.of(conflict),
                Map.of(),
                Map.of(conflict.key(), 0),
                0));

    assertTrue(decision.allowed(), decision.toString());
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

    manager.touchQueues(
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

  @Test
  void canEnterPreviewDoesNotPurgeQueue() {
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

    OccupancyRequest request =
        new OccupancyRequest(
            "requester",
            Optional.empty(),
            now.plusSeconds(31),
            List.of(resource),
            Map.of(),
            Map.of(resource.key(), 0),
            0);

    long versionBefore = manager.version();
    List<OccupancyQueueSnapshot> queuesBefore = manager.snapshotQueues();

    manager.canEnterPreview(request);

    assertEquals(versionBefore, manager.version());
    assertEquals(queuesBefore, manager.snapshotQueues());
    assertEquals(0, manager.staleQueueCleanupCount());

    OccupancyDecision actual = manager.canEnter(request);

    assertTrue(actual.allowed());
    assertTrue(manager.staleQueueCleanupCount() > 0);
  }

  @Test
  void canEnterPreviewDoesNotChangeOccupancyVersion() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());
    Instant now = Instant.parse("2026-03-15T10:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:preview:A~B");
    Map<String, CorridorDirection> direction = Map.of(conflict.key(), CorridorDirection.A_TO_B);
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "leader",
                    Optional.empty(),
                    now,
                    List.of(conflict),
                    direction,
                    Map.of(conflict.key(), 0),
                    0))
            .allowed());
    long versionBefore = manager.version();

    manager.canEnterPreview(
        new OccupancyRequest(
            "follower",
            Optional.empty(),
            now.plusSeconds(1),
            List.of(conflict),
            direction,
            Map.of(conflict.key(), 0),
            0));

    assertEquals(versionBefore, manager.version());
  }

  @Test
  void canEnterPreviewKeepsUnexpiredUnknownDirectionEntryFailClosed() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-03-15T10:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    manager.touchQueues(
        new OccupancyRequest(
            "unknown",
            Optional.empty(),
            now,
            List.of(conflict),
            Map.of(),
            Map.of(conflict.key(), 0),
            0));

    OccupancyDecision preview =
        manager.canEnterPreview(
            new OccupancyRequest(
                "requester",
                Optional.empty(),
                now.plusSeconds(1),
                List.of(conflict),
                Map.of(conflict.key(), CorridorDirection.A_TO_B),
                Map.of(conflict.key(), 0),
                0));

    assertFalse(preview.allowed());
    assertEquals(SignalAspect.STOP, preview.signal());
  }

  @Test
  void advisoryBlockerProducesCautionNotStop() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-03-15T10:00:00Z");
    OccupancyResource node = OccupancyResource.forNode(NodeId.of("B"));
    assertTrue(
        manager
            .acquire(new OccupancyRequest("leader", Optional.empty(), now, List.of(node), Map.of()))
            .allowed());
    OccupancyRequest advisory =
        new OccupancyRequest("follower", Optional.empty(), now, List.of(node), Map.of())
            .asLookaheadPreview();

    OccupancyDecision preview = manager.canEnterPreview(advisory);
    List<AdvisoryRisk> risks = manager.scanAdvisoryRisks(advisory);

    assertTrue(preview.allowed());
    assertEquals(SignalAspect.PROCEED, preview.signal());
    assertEquals(1, risks.size());
    assertEquals(AdvisoryRiskSource.OCCUPIED_NODE, risks.get(0).source());
  }

  @Test
  void physicalHoldOnlyClaimRemainsAdvisoryVisible() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-03-15T10:00:00Z");
    OccupancyResource node = OccupancyResource.forNode(NodeId.of("SURC:S:HHU:1"));
    OccupancyRequest hold =
        new OccupancyRequest(
            "front",
            Optional.empty(),
            now,
            List.of(node),
            Map.of(),
            Map.of(),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            Map.of(),
            Map.of(node, ResourceIntent.HOLD_ONLY));
    assertTrue(manager.acquire(hold).allowed());
    OccupancyRequest advisory =
        new OccupancyRequest("rear", Optional.empty(), now.plusSeconds(1), List.of(node), Map.of())
            .asLookaheadPreview();

    List<AdvisoryRisk> risks = manager.scanAdvisoryRisks(advisory);

    assertEquals(1, risks.size());
    assertEquals(AdvisoryRiskSource.OCCUPIED_NODE, risks.get(0).source());
    assertEquals(ClaimRole.HOLD_ONLY, risks.get(0).claim().role());
  }

  @Test
  void conflictHoldOnlyClaimDoesNotBecomeAdvisoryRisk() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-03-15T10:00:00Z");
    OccupancyResource switcher = OccupancyResource.forConflict("switcher:SWITCHER:HHU:502");
    OccupancyRequest hold =
        new OccupancyRequest(
            "front",
            Optional.empty(),
            now,
            List.of(switcher),
            Map.of(),
            Map.of(),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            Map.of(),
            Map.of(switcher, ResourceIntent.HOLD_ONLY));
    assertTrue(manager.acquire(hold).allowed());
    OccupancyRequest advisory =
        new OccupancyRequest(
                "rear", Optional.empty(), now.plusSeconds(1), List.of(switcher), Map.of())
            .asLookaheadPreview();

    List<AdvisoryRisk> risks = manager.scanAdvisoryRisks(advisory);

    assertTrue(risks.isEmpty(), "抽象 conflict 上的 hold claim 仍只表达区段保护，不应变成前方物理停车点");
  }

  @Test
  void sameDirectionSingleClaimIsNotAdvisoryStopPoint() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-03-15T10:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    Map<String, CorridorDirection> forward = Map.of(conflict.key(), CorridorDirection.A_TO_B);
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "leader",
                    Optional.empty(),
                    now,
                    List.of(conflict),
                    forward,
                    Map.of(conflict.key(), 0),
                    0))
            .allowed());

    OccupancyRequest advisory =
        new OccupancyRequest(
                "follower",
                Optional.empty(),
                now.plusSeconds(1),
                List.of(conflict),
                forward,
                Map.of(conflict.key(), 0),
                0)
            .asLookaheadPreview();

    List<AdvisoryRisk> risks = manager.scanAdvisoryRisks(advisory);

    assertTrue(risks.isEmpty(), "同向 single claim 不能把入口距离 0 误报成前车停车点");
  }

  @Test
  void oppositeSingleClaimRemainsAdvisoryRisk() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-03-15T10:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "opposite",
                    Optional.empty(),
                    now,
                    List.of(conflict),
                    Map.of(conflict.key(), CorridorDirection.B_TO_A),
                    Map.of(conflict.key(), 0),
                    0))
            .allowed());

    OccupancyRequest advisory =
        new OccupancyRequest(
                "follower",
                Optional.empty(),
                now.plusSeconds(1),
                List.of(conflict),
                Map.of(conflict.key(), CorridorDirection.A_TO_B),
                Map.of(conflict.key(), 0),
                0)
            .asLookaheadPreview();

    List<AdvisoryRisk> risks = manager.scanAdvisoryRisks(advisory);

    assertEquals(1, risks.size());
    assertEquals(AdvisoryRiskSource.ACTIVE_SINGLE_CONFLICT, risks.get(0).source());
  }

  @Test
  void advisoryResourcesDoNotEnterMovementRequiredResources() {
    OccupancyResource edge =
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("A"), NodeId.of("B")));
    OccupancyRequest advisory =
        new OccupancyRequest("train", Optional.empty(), Instant.now(), List.of(edge), Map.of())
            .asLookaheadPreview();

    assertFalse(advisory.hasMovementRequiredResources());
    assertEquals(ResourceIntent.LOOKAHEAD_PREVIEW, advisory.intentFor(edge));
  }

  @Test
  void advisoryPreviewDoesNotAcquireOrQueue() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-03-15T10:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyRequest advisory =
        new OccupancyRequest(
                "train",
                Optional.empty(),
                now,
                List.of(conflict),
                Map.of(conflict.key(), CorridorDirection.A_TO_B),
                Map.of(conflict.key(), 0),
                0)
            .asLookaheadPreview();

    OccupancyDecision preview = manager.canEnterPreview(advisory);
    List<AdvisoryRisk> risks = manager.scanAdvisoryRisks(advisory);

    assertTrue(preview.allowed());
    assertTrue(risks.isEmpty());
    assertTrue(manager.snapshotClaims().isEmpty());
    assertTrue(manager.snapshotQueues().isEmpty());
  }

  @Test
  void acquireCannotPersistLookaheadPreviewClaims() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyResource previewNode = OccupancyResource.forNode(NodeId.of("PREVIEW"));
    OccupancyResource authorityNode = OccupancyResource.forNode(NodeId.of("AUTHORITY"));
    OccupancyRequest mixedRequest =
        new OccupancyRequest(
                "train",
                Optional.empty(),
                Instant.parse("2026-03-15T10:00:00Z"),
                List.of(previewNode, authorityNode),
                Map.of())
            .withResourceIntents(
                Map.of(
                    previewNode,
                    ResourceIntent.LOOKAHEAD_PREVIEW,
                    authorityNode,
                    ResourceIntent.MOVEMENT_REQUIRED));

    assertTrue(manager.acquire(mixedRequest).allowed());

    assertTrue(manager.getClaim(previewNode).isEmpty());
    assertEquals(ClaimRole.MOVEMENT_REQUIRED, manager.getClaim(authorityNode).orElseThrow().role());
    assertTrue(manager.snapshotQueues().isEmpty());
    assertEquals(1L, manager.version());

    OccupancyRequest previewOnly =
        new OccupancyRequest(
                "preview-only",
                Optional.empty(),
                Instant.parse("2026-03-15T10:00:01Z"),
                List.of(previewNode),
                Map.of())
            .withResourceIntents(Map.of(previewNode, ResourceIntent.LOOKAHEAD_PREVIEW));

    assertTrue(manager.acquire(previewOnly).allowed());
    assertTrue(manager.getClaim(previewNode).isEmpty());
    assertTrue(manager.snapshotQueues().isEmpty());
    assertEquals(1L, manager.version());
  }

  @Test
  void sameDirectionFrontTrainNotBlockedByRearGuard_onProgressTrigger() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource rearGuardEdge =
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("A"), NodeId.of("M1")));
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "TrainRear", Optional.empty(), now, List.of(rearGuardEdge), Map.of(), 0))
            .allowed());

    OccupancyResource forwardEdge =
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("M1"), NodeId.of("M2")));
    OccupancyRequest frontProgressRequest =
        new OccupancyRequest(
            "TrainFront",
            Optional.empty(),
            now.plusSeconds(1),
            List.of(rearGuardEdge, forwardEdge),
            Map.of(),
            Map.of(),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            Map.of(),
            Map.of(
                rearGuardEdge,
                ResourceIntent.PROTECTIVE_RETAIN,
                forwardEdge,
                ResourceIntent.MOVEMENT_REQUIRED));

    OccupancyDecision decision = manager.canEnter(frontProgressRequest);

    assertTrue(decision.allowed());
    assertTrue(decision.blockers().isEmpty());
  }

  @Test
  void protectiveRetainResourceDoesNotBlockForwardMovement() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource tailNode = OccupancyResource.forNode(NodeId.of("TAIL"));
    OccupancyRequest front =
        new OccupancyRequest(
            "front",
            Optional.empty(),
            now,
            List.of(tailNode),
            Map.of(),
            Map.of(),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            Map.of(),
            Map.of(tailNode, ResourceIntent.PROTECTIVE_RETAIN));

    assertTrue(manager.canEnter(front).allowed());
    assertTrue(manager.acquire(front).allowed());
    assertEquals("front", manager.getClaim(tailNode).orElseThrow().trainName());
  }

  @Test
  void selfOwnedSingleConflictDoesNotBlockDeparture() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyRequest initial =
        singleConflictRequest("train", now, conflict, CorridorDirection.A_TO_B);

    assertTrue(manager.acquire(initial).allowed());
    assertTrue(
        manager
            .canEnter(
                singleConflictRequest(
                    "train", now.plusSeconds(1), conflict, CorridorDirection.A_TO_B))
            .allowed());
  }

  @Test
  void smartSelfOwnedContinuationDoesNotFailClosedOnUnknownDirection() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");

    assertTrue(
        manager
            .acquire(singleConflictRequest("train", now, conflict, CorridorDirection.A_TO_B))
            .allowed());

    OccupancyDecision continuation =
        manager.canEnter(
            singleConflictRequest(
                "train", now.plusSeconds(1), conflict, CorridorDirection.UNKNOWN));

    assertTrue(continuation.allowed(), "同车已在 single 内继续前进时 UNKNOWN 不应套用入口 fail-closed");
  }

  @Test
  void acquirePersistsSectionDirectionFromCommittedCorridorToken() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    String axis = "SURC:S:SCC:1~SURC:S:WSD:1";
    OccupancyResource section = OccupancyResource.forConflict("single:section:bridge:" + axis);
    OccupancyRequest request =
        sectionRequestWithPlanDirections(
            "train",
            now,
            section,
            Map.of(section.key(), CorridorDirection.UNKNOWN),
            Map.of("single:SURC:CGL:WYB:1:001:" + axis, CorridorDirection.A_TO_B));

    assertTrue(manager.acquire(request).allowed());
    assertEquals(
        Optional.of(CorridorDirection.A_TO_B),
        manager.getClaim(section).orElseThrow().corridorDirection());
  }

  @Test
  void bridgeSectionDirectionStaysUnknownWhenCommittedMicroTokensConflict() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    String axis = "SURC:S:SCC:1~SURC:S:WSD:1";
    OccupancyResource section = OccupancyResource.forConflict("single:section:bridge:" + axis);
    OccupancyRequest request =
        sectionRequestWithPlanDirections(
            "train",
            now,
            section,
            Map.of(section.key(), CorridorDirection.UNKNOWN),
            Map.of(
                "single:SURC:CGL:WYB:1:001:" + axis,
                CorridorDirection.A_TO_B,
                "single:SURC:OTHER:COMPONENT:001:" + axis,
                CorridorDirection.B_TO_A));

    assertTrue(manager.acquire(request).allowed());
    assertTrue(manager.getClaim(section).orElseThrow().corridorDirection().isEmpty());
  }

  @Test
  void acquireKeepsUnknownWhenCommittedSectionTokenComponentDiffers() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource section = OccupancyResource.forConflict("single:section:comp:A~B");
    OccupancyRequest request =
        sectionRequestWithPlanDirections(
            "train",
            now,
            section,
            Map.of(section.key(), CorridorDirection.UNKNOWN),
            Map.of("single:other:A~B", CorridorDirection.A_TO_B));

    assertTrue(manager.acquire(request).allowed());
    assertTrue(manager.getClaim(section).orElseThrow().corridorDirection().isEmpty());
  }

  @Test
  void smartAlreadyInsideSingleBlockedOnlyByExternalHardBlocker() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyResource nodeB = OccupancyResource.forNode(NodeId.of("B"));

    assertTrue(
        manager
            .acquire(singleConflictRequest("train", now, conflict, CorridorDirection.A_TO_B))
            .allowed());
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "blocker", Optional.empty(), now.plusSeconds(1), List.of(nodeB), Map.of(), 0))
            .allowed());

    OccupancyRequest blockedRequest =
        singleConflictRequest("train", now.plusSeconds(2), conflict, CorridorDirection.UNKNOWN);
    blockedRequest =
        new OccupancyRequest(
                blockedRequest.trainName(),
                blockedRequest.routeId(),
                blockedRequest.now(),
                List.of(conflict, nodeB),
                blockedRequest.corridorDirections(),
                blockedRequest.conflictEntryOrders(),
                blockedRequest.priority(),
                blockedRequest.purpose(),
                blockedRequest.conflictReleaseHints(),
                blockedRequest.resourceIntents())
            .withDirectedContext(blockedRequest.directedContext());
    OccupancyDecision blocked = manager.canEnter(blockedRequest);

    assertFalse(blocked.allowed());
    assertEquals("self-owned-single-continuation-rejected", blocked.reason());
  }

  @Test
  void selfOwnedSingleConflictOppositeDirectionStillBlocks() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    assertTrue(
        manager
            .acquire(singleConflictRequest("train", now, conflict, CorridorDirection.A_TO_B))
            .allowed());

    OccupancyDecision decision =
        manager.canEnter(
            singleConflictRequest("train", now.plusSeconds(1), conflict, CorridorDirection.B_TO_A));

    assertFalse(decision.allowed());
    assertEquals(SignalAspect.STOP, decision.signal());
    assertEquals("self-owned-single-opposite-direction", decision.reason());
  }

  @Test
  void protectiveRefreshCannotReverseOrDowngradeMovementAuthorityClaim() {
    SignalEventBus eventBus = new SignalEventBus();
    AtomicInteger acquiredEvents = new AtomicInteger();
    eventBus.subscribe(OccupancyAcquiredEvent.class, event -> acquiredEvents.incrementAndGet());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy(), eventBus);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyRequest authority =
        singleConflictRequest("train", now, conflict, CorridorDirection.A_TO_B);
    OccupancyRequest protective =
        singleConflictRequest("train", now.plusSeconds(1), conflict, CorridorDirection.B_TO_A)
            .withResourceIntents(Map.of(conflict, ResourceIntent.PROTECTIVE_RETAIN));

    assertTrue(manager.acquire(authority).allowed());
    long authorityVersion = manager.version();
    int authorityEvents = acquiredEvents.get();

    assertTrue(manager.acquire(protective).allowed());

    OccupancyClaim claim = manager.getClaim(conflict).orElseThrow();
    assertEquals(ClaimRole.MOVEMENT_REQUIRED, claim.role());
    assertEquals(Optional.of(CorridorDirection.A_TO_B), claim.corridorDirection());
    assertEquals(authorityVersion, manager.version());
    assertEquals(authorityEvents, acquiredEvents.get());
  }

  @Test
  void selfOwnedContinuationUsesMovementSnapshotDirectionWhenRequestMapEmpty() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyRequest retain =
        singleConflictRequest("train", now, conflict, CorridorDirection.B_TO_A)
            .withResourceIntents(Map.of(conflict, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(retain).allowed());

    OccupancyRequest continuation =
        singleConflictRequestWithSnapshotDirection(
            "train",
            now.plusSeconds(1),
            conflict,
            Map.of(),
            Map.of(conflict.key(), CorridorDirection.B_TO_A),
            Map.of());
    OccupancyDecision decision = manager.canEnter(continuation);

    assertTrue(decision.allowed());
    assertTrue(manager.selfOwnedStaleRetainReleaseCandidate("train").isEmpty());
  }

  @Test
  void selfOwnedContinuationUsesRequestDirectionWhenSnapshotMissesExactKey() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    assertTrue(
        manager
            .acquire(singleConflictRequest("train", now, conflict, CorridorDirection.A_TO_B))
            .allowed());

    OccupancyRequest continuation =
        singleConflictRequestWithSnapshotDirection(
            "train",
            now.plusSeconds(1),
            conflict,
            Map.of(conflict.key(), CorridorDirection.A_TO_B),
            Map.of(),
            Map.of());
    OccupancyDecision decision = manager.canEnter(continuation);

    assertTrue(
        decision.allowed(), () -> "请求方向已知且同向时，不应因 movement snapshot 缺 exact key 自锁: " + decision);
    assertTrue(manager.selfOwnedStaleRetainReleaseCandidate("train").isEmpty());
  }

  @Test
  void selfOwnedTailProtectionZoneNotOnPlanDoesNotReject() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyRequest retain =
        singleConflictRequest("train", now, conflict, CorridorDirection.B_TO_A)
            .withResourceIntents(Map.of(conflict, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(retain).allowed());

    // 折返后的 movement plan 不再穿越该 zone：请求仍携带该资源做车尾保护（非 hard、方向未知）。
    OccupancyRequest continuation =
        singleConflictRequestWithSnapshotDirection(
                "train", now.plusSeconds(1), conflict, Map.of(), Map.of(), Map.of())
            .withResourceIntents(Map.of(conflict, ResourceIntent.PROTECTIVE_RETAIN));
    OccupancyDecision decision = manager.canEnter(continuation);

    assertTrue(decision.allowed(), "不在计划路径上的自持保护 claim 不应否决本车移动");
    assertTrue(manager.selfOwnedStaleRetainReleaseCandidate("train").isEmpty());
  }

  @Test
  void selfOwnedUnknownDirectionHardAuthorityWithoutExternalPresenceDoesNotReject() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyRequest retain =
        singleConflictRequest("train", now, conflict, CorridorDirection.B_TO_A)
            .withResourceIntents(Map.of(conflict, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(retain).allowed());

    // 分叉/车库口可能无法给出语义方向；没有外部 single 或硬阻塞时不应把自持 claim
    // 当成真实对向车，否则列车会在自己的出库保留上永久自锁。
    OccupancyRequest continuation =
        singleConflictRequestWithSnapshotDirection(
            "train", now.plusSeconds(1), conflict, Map.of(), Map.of(), Map.of());
    OccupancyDecision decision = manager.canEnter(continuation);

    assertTrue(decision.allowed(), () -> "零外部存在的自有 UNKNOWN 续行不应套用跨车 fail-closed: " + decision);
    assertTrue(manager.selfOwnedStaleRetainReleaseCandidate("train").isEmpty());
  }

  @Test
  void selfOwnedContinuationAllowsSameDirectionExternalSinglePresence() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyRequest retain =
        singleConflictRequest("train", now, conflict, CorridorDirection.A_TO_B)
            .withResourceIntents(Map.of(conflict, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(retain).allowed());
    assertTrue(
        manager
            .acquire(
                singleConflictRequest(
                    "leader", now.plusMillis(1), conflict, CorridorDirection.A_TO_B))
            .allowed());

    OccupancyDecision decision =
        manager.canEnter(
            singleConflictRequest("train", now.plusSeconds(1), conflict, CorridorDirection.A_TO_B));

    assertTrue(decision.allowed());
    assertTrue(manager.selfOwnedStaleRetainReleaseCandidate("train").isEmpty());
  }

  @Test
  void protectiveRetainInheritsCommittedDirectionForSameDirectionProof() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource section = OccupancyResource.forConflict("single:section:terminal:A~B");

    assertTrue(
        manager
            .acquire(singleConflictRequest("turning", now, section, CorridorDirection.A_TO_B))
            .allowed());
    assertTrue(
        manager
            .acquire(
                singleConflictRequest(
                    "leader", now.plusMillis(1), section, CorridorDirection.A_TO_B))
            .allowed());
    OccupancyRequest rearGuard =
        singleConflictRequestWithSnapshotDirection(
                "turning", now.plusSeconds(1), section, Map.of(), Map.of(), Map.of())
            .withResourceIntents(Map.of(section, ResourceIntent.PROTECTIVE_RETAIN));

    assertTrue(
        manager.isProvenSameDirectionFollower(rearGuard, section, "leader", false),
        "尾部保护请求应继承本车已提交的单线方向，不能退化为 UNKNOWN");
  }

  @Test
  void terminalTurnbackAtomicallyReplacesDirectionAndMigratesOwner() {
    SignalEventBus eventBus = new SignalEventBus();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy(), eventBus);
    AuthorityHandoffSupport handoff = assertInstanceOf(AuthorityHandoffSupport.class, manager);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource terminal = OccupancyResource.forNode(NodeId.of("TERMINAL"));
    OccupancyResource oldApproach =
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("APPROACH"), NodeId.of("TERMINAL")));
    OccupancyResource section = OccupancyResource.forConflict("single:section:turnback:A~B");
    OccupancyRequest inbound =
        new OccupancyRequest(
            "turning-inbound",
            Optional.empty(),
            now,
            List.of(terminal, oldApproach, section),
            Map.of(section.key(), CorridorDirection.A_TO_B));
    OccupancyRequest outbound =
        new OccupancyRequest(
                "turning-inbound",
                Optional.empty(),
                now.plusSeconds(1),
                List.of(terminal, oldApproach, section),
                Map.of(section.key(), CorridorDirection.B_TO_A))
            .withResourceIntents(Map.of(oldApproach, ResourceIntent.LOOKAHEAD_PREVIEW));

    assertTrue(manager.acquire(inbound).allowed());
    assertFalse(manager.canEnter(outbound).allowed(), "普通 acquire 仍必须拒绝直接翻转方向");
    AtomicReference<Boolean> releaseObservedContinuousGuard = new AtomicReference<>(false);
    AtomicInteger releasedEvents = new AtomicInteger();
    eventBus.subscribe(
        org.fetarute.fetaruteTCAddon.dispatcher.signal.event.OccupancyReleasedEvent.class,
        event -> {
          releasedEvents.incrementAndGet();
          releaseObservedContinuousGuard.set(
              manager.getClaim(terminal).isPresent()
                  && manager.getClaim(section).isPresent()
                  && manager
                          .getClaim(section)
                          .flatMap(OccupancyClaim::corridorDirection)
                          .orElse(CorridorDirection.UNKNOWN)
                      == CorridorDirection.B_TO_A);
        });

    OccupancyDecision decision = handoff.handoffAuthority(outbound);

    assertTrue(decision.allowed(), decision.toString());
    assertFalse(releaseObservedContinuousGuard.get(), "连续占用的资源不应伪装成已释放事件");
    assertEquals(0, releasedEvents.get());
    OccupancyClaim retainedApproach = manager.getClaim(oldApproach).orElseThrow();
    assertEquals(ClaimRole.PHYSICAL_FOOTPRINT, retainedApproach.role());
    assertFalse(
        manager
            .canEnter(
                new OccupancyRequest(
                    "crossing-train",
                    Optional.empty(),
                    now.plusSeconds(2),
                    List.of(oldApproach),
                    Map.of()))
            .allowed(),
        "没有 rear-clear 证据前，静止长编组仍必须保护进站咽喉/道口 footprint");
    assertEquals(
        CorridorDirection.B_TO_A,
        manager
            .getClaim(section)
            .flatMap(OccupancyClaim::corridorDirection)
            .orElse(CorridorDirection.UNKNOWN));
    assertTrue(handoff.migrateAuthorityOwner("turning-inbound", "turning-outbound"));
    OccupancyRequest renamed = outbound.withTrainName("turning-outbound");
    assertTrue(handoff.holdsHardAuthority(renamed));
    assertTrue(
        manager.snapshotClaims().stream()
            .allMatch(claim -> claim.trainName().equals("turning-outbound")));
  }

  @Test
  void periodicProtectiveRefreshCannotDowngradeTurnbackPhysicalFootprint() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    AuthorityHandoffSupport handoff = assertInstanceOf(AuthorityHandoffSupport.class, manager);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource terminal = OccupancyResource.forNode(NodeId.of("TERMINAL"));
    OccupancyResource oldApproach =
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("APPROACH"), NodeId.of("TERMINAL")));
    OccupancyRequest inbound =
        new OccupancyRequest(
            "turning", Optional.empty(), now, List.of(terminal, oldApproach), Map.of());
    OccupancyRequest outbound =
        new OccupancyRequest(
                "turning",
                Optional.empty(),
                now.plusSeconds(1),
                List.of(terminal, oldApproach),
                Map.of())
            .withResourceIntents(Map.of(oldApproach, ResourceIntent.LOOKAHEAD_PREVIEW));

    assertTrue(manager.acquire(inbound).allowed());
    assertTrue(handoff.handoffAuthority(outbound).allowed());
    assertEquals(ClaimRole.PHYSICAL_FOOTPRINT, manager.getClaim(oldApproach).orElseThrow().role());

    OccupancyRequest periodicRearGuard =
        new OccupancyRequest(
                "turning", Optional.empty(), now.plusSeconds(2), List.of(oldApproach), Map.of())
            .withResourceIntents(Map.of(oldApproach, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(periodicRearGuard).allowed());

    assertEquals(
        ClaimRole.PHYSICAL_FOOTPRINT,
        manager.getClaim(oldApproach).orElseThrow().role(),
        "周期 rear-guard refresh 不能在真实列尾清空前把硬 footprint 降为软保护");
    assertFalse(
        manager
            .canEnter(
                new OccupancyRequest(
                    "crossing-train",
                    Optional.empty(),
                    now.plusSeconds(3),
                    List.of(oldApproach),
                    Map.of()))
            .allowed());
  }

  @Test
  void blockedTerminalTurnbackRestoresInboundAuthorityWithoutPublishingRelease() {
    SignalEventBus eventBus = new SignalEventBus();
    AtomicInteger releasedEvents = new AtomicInteger();
    eventBus.subscribe(
        org.fetarute.fetaruteTCAddon.dispatcher.signal.event.OccupancyReleasedEvent.class,
        event -> releasedEvents.incrementAndGet());
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy(), eventBus);
    AuthorityHandoffSupport handoff = assertInstanceOf(AuthorityHandoffSupport.class, manager);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource terminal = OccupancyResource.forNode(NodeId.of("TERMINAL"));
    OccupancyResource section = OccupancyResource.forConflict("single:section:turnback:A~B");
    OccupancyResource outboundEdge =
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("TERMINAL"), NodeId.of("CROSSING")));
    OccupancyRequest inbound =
        new OccupancyRequest(
            "turning",
            Optional.empty(),
            now,
            List.of(terminal, section),
            Map.of(section.key(), CorridorDirection.A_TO_B));
    OccupancyRequest outbound =
        new OccupancyRequest(
            "turning",
            Optional.empty(),
            now.plusSeconds(2),
            List.of(terminal, section, outboundEdge),
            Map.of(section.key(), CorridorDirection.B_TO_A));

    assertTrue(manager.acquire(inbound).allowed());
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "crossing-train",
                    Optional.empty(),
                    now.plusSeconds(1),
                    List.of(outboundEdge),
                    Map.of()))
            .allowed());
    List<OccupancyClaim> before = manager.snapshotClaims();

    OccupancyDecision decision = handoff.handoffAuthority(outbound);

    assertFalse(decision.allowed());
    assertEquals(Set.copyOf(before), Set.copyOf(manager.snapshotClaims()));
    assertEquals(0, releasedEvents.get());
    assertEquals(
        CorridorDirection.A_TO_B,
        manager
            .getClaim(section)
            .flatMap(OccupancyClaim::corridorDirection)
            .orElse(CorridorDirection.UNKNOWN));
  }

  @Test
  void terminalTurnbackKeepsIncumbentAheadOfWaiterQueuedByItsOldClaim() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    AuthorityHandoffSupport handoff = assertInstanceOf(AuthorityHandoffSupport.class, manager);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource cycle =
        OccupancyResource.forConflict("single:component:cycle:TERMINAL-TURNBACK");
    OccupancyRequest inbound =
        new OccupancyRequest("turning", Optional.empty(), now, List.of(cycle), Map.of());
    OccupancyRequest waiting =
        new OccupancyRequest(
            "waiting-crossing", Optional.empty(), now.plusSeconds(1), List.of(cycle), Map.of());
    OccupancyRequest outbound =
        new OccupancyRequest(
            "turning", Optional.empty(), now.plusSeconds(2), List.of(cycle), Map.of());

    assertTrue(manager.acquire(inbound).allowed());
    assertFalse(manager.canEnter(waiting).allowed());
    assertTrue(
        manager.snapshotQueues().stream()
            .flatMap(queue -> queue.entries().stream())
            .anyMatch(entry -> entry.trainName().equals("waiting-crossing")));

    OccupancyDecision decision = handoff.handoffAuthority(outbound);

    assertTrue(decision.allowed(), decision.toString());
    OccupancyClaim claim = manager.getClaim(cycle).orElseThrow();
    assertEquals("turning", claim.trainName());
    assertEquals(ClaimRole.MOVEMENT_REQUIRED, claim.role());
    assertTrue(
        manager.snapshotQueues().stream()
            .flatMap(queue -> queue.entries().stream())
            .anyMatch(entry -> entry.trainName().equals("waiting-crossing")),
        "handoff 只能保留 incumbent 的清界权，不能吞掉外车原有队列位次");
  }

  @Test
  void terminalTurnbackRetainsOnlyPhysicalFootprintRoles() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    AuthorityHandoffSupport handoff = assertInstanceOf(AuthorityHandoffSupport.class, manager);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource terminal = OccupancyResource.forNode(NodeId.of("TERMINAL"));
    OccupancyResource outboundEdge =
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("TERMINAL"), NodeId.of("OUT")));
    OccupancyResource retainedConflict =
        OccupancyResource.forConflict("switcher:terminal-footprint");
    OccupancyResource lookahead = OccupancyResource.forNode(NodeId.of("LOOKAHEAD"));
    OccupancyResource queuePosition = OccupancyResource.forConflict("switcher:queue-position");
    OccupancyResource unlockReservation =
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("UNLOCK-A"), NodeId.of("UNLOCK-B")));
    OccupancyRequest inbound =
        new OccupancyRequest(
                "turning",
                Optional.empty(),
                now,
                List.of(terminal, retainedConflict, lookahead, queuePosition, unlockReservation),
                Map.of())
            .withResourceIntents(
                Map.of(
                    retainedConflict,
                    ResourceIntent.PROTECTIVE_RETAIN,
                    lookahead,
                    ResourceIntent.LOOKAHEAD_PREVIEW,
                    queuePosition,
                    ResourceIntent.QUEUE_POSITION,
                    unlockReservation,
                    ResourceIntent.UNLOCK_RESERVATION));
    OccupancyRequest outbound =
        new OccupancyRequest(
            "turning",
            Optional.empty(),
            now.plusSeconds(1),
            List.of(terminal, outboundEdge),
            Map.of());

    assertTrue(manager.acquire(inbound).allowed());
    assertEquals(4, manager.snapshotClaims().size());
    assertTrue(manager.getClaim(lookahead).isEmpty(), "lookahead preview 不能进入可写 claim 状态");

    OccupancyDecision decision = handoff.handoffAuthority(outbound);

    assertTrue(decision.allowed(), decision.toString());
    assertEquals(
        ClaimRole.PHYSICAL_FOOTPRINT,
        manager.getClaim(retainedConflict).orElseThrow().role(),
        "旧 hard/protective CONFLICT 仍代表车体 footprint，必须防止区段被抢入");
    OccupancyDecision crossingEntry =
        manager.canEnter(
            new OccupancyRequest(
                "crossing-train",
                Optional.empty(),
                now.plusSeconds(2),
                List.of(retainedConflict),
                Map.of()));
    assertFalse(crossingEntry.allowed(), "折返列尾未清空前，抽象 crossing conflict 也必须保持硬闭锁");
    Set<OccupancyResource> retainedResources =
        manager.snapshotClaims().stream()
            .map(OccupancyClaim::resource)
            .collect(java.util.stream.Collectors.toSet());
    assertFalse(retainedResources.contains(lookahead));
    assertFalse(retainedResources.contains(queuePosition));
    assertFalse(retainedResources.contains(unlockReservation));
  }

  @Test
  void selfOwnedContinuationRejectIncludesExternalSingleOwner() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyRequest retain =
        singleConflictRequest("train", now, conflict, CorridorDirection.A_TO_B)
            .withResourceIntents(Map.of(conflict, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(retain).allowed());
    assertTrue(
        manager
            .acquire(
                singleConflictRequest(
                    "leader", now.plusMillis(1), conflict, CorridorDirection.A_TO_B))
            .allowed());

    OccupancyDecision decision =
        manager.canEnter(
            singleConflictRequestWithSnapshotDirection(
                "train", now.plusSeconds(1), conflict, Map.of(), Map.of(), Map.of()));

    assertFalse(decision.allowed());
    assertEquals("self-owned-single-continuation-rejected", decision.reason());
    assertTrue(
        decision.blockers().stream().anyMatch(claim -> claim.trainName().equals("train")),
        () -> "blockers=" + decision.blockers());
    assertTrue(
        decision.blockers().stream().anyMatch(claim -> claim.trainName().equals("leader")),
        () -> "blockers=" + decision.blockers());
  }

  @Test
  void unrelatedMovementSnapshotDirectionDoesNotCreateOppositeSelfBlock() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyResource unrelated = OccupancyResource.forConflict("single:comp:C~D");
    OccupancyRequest retain =
        singleConflictRequest("train", now, conflict, CorridorDirection.B_TO_A)
            .withResourceIntents(Map.of(conflict, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(retain).allowed());

    OccupancyRequest request =
        singleConflictRequestWithSnapshotDirection(
            "train",
            now.plusSeconds(1),
            conflict,
            Map.of(),
            Map.of(unrelated.key(), CorridorDirection.B_TO_A),
            Map.of());
    OccupancyDecision decision = manager.canEnter(request);

    assertTrue(decision.allowed());
    assertTrue(manager.selfOwnedStaleRetainReleaseCandidate("train").isEmpty());
  }

  @Test
  void selfOwnedStaleRetainCandidateSkippedInsideSwitcherZone() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyRequest retain =
        singleConflictRequest("train", now, conflict, CorridorDirection.A_TO_B)
            .withResourceIntents(Map.of(conflict, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(retain).allowed());

    OccupancyRequest reversedInsideSwitcher =
        singleConflictRequestWithSnapshotDirection(
            "train",
            now.plusSeconds(1),
            conflict,
            Map.of(conflict.key(), CorridorDirection.B_TO_A),
            Map.of(conflict.key(), CorridorDirection.B_TO_A),
            Map.of(
                "switcher:SW",
                new DirectedTraversalContext.SwitcherPathSignature(
                    "switcher:SW", List.of(NodeId.of("A"), NodeId.of("SW"), NodeId.of("B")))));

    assertFalse(manager.canEnter(reversedInsideSwitcher).allowed());
    assertTrue(manager.selfOwnedStaleRetainReleaseCandidate("train").isEmpty());
  }

  @Test
  void clearSelfOwnedSingleDirectionMismatchRemovesOnlyStaleSelfClaim() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    assertTrue(
        manager
            .acquire(singleConflictRequest("train", now, conflict, CorridorDirection.A_TO_B))
            .allowed());
    OccupancyRequest reversed =
        singleConflictRequest("train", now.plusSeconds(1), conflict, CorridorDirection.B_TO_A);

    OccupancyDecision blocked = manager.canEnterPreview(reversed);
    assertFalse(blocked.allowed());
    assertEquals("self-owned-single-opposite-direction", blocked.reason());

    assertEquals(1, manager.clearSelfOwnedSingleDirectionMismatches(reversed));
    assertTrue(manager.canEnterPreview(reversed).allowed());
    assertEquals(0, manager.clearSelfOwnedSingleDirectionMismatches(reversed));
  }

  @Test
  void clearSelfOwnedSingleDirectionMismatchPublishesQueueOnlyArbitrationChange() {
    SignalEventBus eventBus = new SignalEventBus();
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy(), eventBus);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:queue-only:A~B");
    manager.touchQueues(singleConflictRequest("train", now, conflict, CorridorDirection.A_TO_B));
    List<OccupancyQueueChangedEvent> events = new java.util.ArrayList<>();
    eventBus.subscribe(OccupancyQueueChangedEvent.class, events::add);
    OccupancyRequest reversed =
        singleConflictRequest("train", now.plusSeconds(1), conflict, CorridorDirection.B_TO_A);

    assertEquals(1, manager.clearSelfOwnedSingleDirectionMismatches(reversed));

    assertEquals(1, events.size());
    assertEquals(List.of(conflict), events.get(0).affectedResources());
  }

  @Test
  void clearSelfOwnedSingleDirectionMismatchCannotReleasePhysicalFootprint() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    AuthorityHandoffSupport handoff = assertInstanceOf(AuthorityHandoffSupport.class, manager);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource terminal = OccupancyResource.forNode(NodeId.of("TERMINAL"));
    OccupancyResource section = OccupancyResource.forConflict("single:terminal:A~B");
    OccupancyRequest inbound =
        new OccupancyRequest(
            "turning",
            Optional.empty(),
            now,
            List.of(terminal, section),
            Map.of(section.key(), CorridorDirection.A_TO_B));
    OccupancyRequest outbound =
        new OccupancyRequest(
            "turning", Optional.empty(), now.plusSeconds(1), List.of(terminal), Map.of());
    assertTrue(manager.acquire(inbound).allowed());
    assertTrue(handoff.handoffAuthority(outbound).allowed());
    assertEquals(ClaimRole.PHYSICAL_FOOTPRINT, manager.getClaim(section).orElseThrow().role());

    OccupancyRequest reversed =
        singleConflictRequest("turning", now.plusSeconds(2), section, CorridorDirection.B_TO_A);

    assertEquals(0, manager.clearSelfOwnedSingleDirectionMismatches(reversed));
    assertEquals(ClaimRole.PHYSICAL_FOOTPRINT, manager.getClaim(section).orElseThrow().role());
  }

  @Test
  void selfOwnedProtectiveRetainReleaseCandidateDetected() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyRequest retain =
        singleConflictRequest("train", now, conflict, CorridorDirection.A_TO_B)
            .withResourceIntents(Map.of(conflict, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(retain).allowed());

    OccupancyDecision blocked =
        manager.canEnter(
            singleConflictRequest("train", now.plusSeconds(1), conflict, CorridorDirection.B_TO_A));

    assertFalse(blocked.allowed());
    Optional<SimpleOccupancyManager.SelfOwnedStaleRetainCandidate> candidate =
        manager.selfOwnedStaleRetainReleaseCandidate("train");
    assertTrue(candidate.isPresent());
    assertEquals(conflict, candidate.get().resource());
    assertEquals(ClaimRole.PROTECTIVE_RETAIN, candidate.get().claimRole());
  }

  @Test
  void selfOwnedProtectiveRetainPreviewDoesNotMutateState() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyRequest retain =
        singleConflictRequest("train", now, conflict, CorridorDirection.A_TO_B)
            .withResourceIntents(Map.of(conflict, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(retain).allowed());
    long versionBefore = manager.version();
    List<OccupancyClaim> claimsBefore = manager.snapshotClaims();
    List<OccupancyQueueSnapshot> queuesBefore = manager.snapshotQueues();

    Optional<SimpleOccupancyManager.SelfOwnedStaleRetainCandidate> candidate =
        manager.previewSelfOwnedStaleRetainReleaseCandidate(
            singleConflictRequest("train", now.plusSeconds(1), conflict, CorridorDirection.B_TO_A));

    assertTrue(candidate.isPresent());
    assertEquals(versionBefore, manager.version());
    assertEquals(claimsBefore, manager.snapshotClaims());
    assertEquals(queuesBefore, manager.snapshotQueues());
    assertTrue(manager.selfOwnedStaleRetainReleaseCandidate("train").isEmpty());
  }

  @Test
  void externalInterlockingClaimPreventsStaleRetainReleaseCandidate() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource single = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyResource interlocking = OccupancyResource.forConflict("interlocking:0123456789abcdef");
    OccupancyRequest retain =
        singleConflictRequest("train", now, single, CorridorDirection.A_TO_B)
            .withResourceIntents(Map.of(single, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(retain).allowed());
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "blocker",
                    Optional.empty(),
                    now.plusSeconds(1),
                    List.of(interlocking),
                    Map.of()))
            .allowed());

    OccupancyRequest reversed =
        singleConflictRequest("train", now.plusSeconds(2), single, CorridorDirection.B_TO_A);
    OccupancyRequest withInterlockingAhead =
        new OccupancyRequest(
                reversed.trainName(),
                reversed.routeId(),
                reversed.now(),
                List.of(single, interlocking),
                reversed.corridorDirections(),
                reversed.conflictEntryOrders(),
                reversed.priority(),
                reversed.purpose(),
                reversed.conflictReleaseHints(),
                reversed.resourceIntents())
            .withDirectedContext(reversed.directedContext());

    assertTrue(
        manager.previewSelfOwnedStaleRetainReleaseCandidate(withInterlockingAhead).isEmpty());
    OccupancyDecision blocked = manager.canEnter(withInterlockingAhead);
    assertFalse(blocked.allowed());
    assertTrue(manager.selfOwnedStaleRetainReleaseCandidate("train").isEmpty());
    assertTrue(
        blocked.blockers().stream()
            .anyMatch(
                claim ->
                    claim.resource().equals(interlocking) && claim.trainName().equals("blocker")));
  }

  @Test
  void cachedStaleRetainCandidateRechecksInterlockingScopeBeforeRelease() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource single = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyResource interlocking = OccupancyResource.forConflict("interlocking:fedcba9876543210");
    OccupancyRequest retain =
        singleConflictRequest("train", now, single, CorridorDirection.A_TO_B)
            .withResourceIntents(Map.of(single, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(retain).allowed());

    OccupancyRequest reversed =
        singleConflictRequest("train", now.plusSeconds(1), single, CorridorDirection.B_TO_A);
    OccupancyRequest withClearInterlockingAhead =
        new OccupancyRequest(
                reversed.trainName(),
                reversed.routeId(),
                reversed.now(),
                List.of(single, interlocking),
                reversed.corridorDirections(),
                reversed.conflictEntryOrders(),
                reversed.priority(),
                reversed.purpose(),
                reversed.conflictReleaseHints(),
                reversed.resourceIntents())
            .withDirectedContext(reversed.directedContext());
    assertFalse(manager.canEnter(withClearInterlockingAhead).allowed());
    assertTrue(manager.selfOwnedStaleRetainReleaseCandidate("train").isPresent());

    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "blocker",
                    Optional.empty(),
                    now.plusSeconds(2),
                    List.of(interlocking),
                    Map.of()))
            .allowed());
    SimpleOccupancyManager.SelfOwnedStaleRetainReleaseResult release =
        manager.releaseSelfOwnedStaleRetain("train");

    assertFalse(release.released());
    assertEquals("external-hard-blocker-present", release.reason());
    assertTrue(
        manager.snapshotClaims().stream()
            .anyMatch(
                claim ->
                    claim.resource().equals(single)
                        && claim.trainName().equals("train")
                        && claim.role() == ClaimRole.PROTECTIVE_RETAIN));
  }

  @Test
  void selfOwnedProtectiveRetainReleased() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyRequest retain =
        singleConflictRequest("train", now, conflict, CorridorDirection.A_TO_B)
            .withResourceIntents(Map.of(conflict, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(retain).allowed());
    assertFalse(
        manager
            .canEnter(
                singleConflictRequest(
                    "train", now.plusSeconds(1), conflict, CorridorDirection.B_TO_A))
            .allowed());

    SimpleOccupancyManager.SelfOwnedStaleRetainReleaseResult result =
        manager.releaseSelfOwnedStaleRetain("train");

    assertTrue(result.candidate());
    assertTrue(result.released());
    assertTrue(manager.snapshotClaims().isEmpty());
  }

  @Test
  void interlockingRetainCannotBecomeStaleReleaseCandidate() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource interlocking = OccupancyResource.forConflict("interlocking:0123456789abcdef");
    OccupancyRequest retain =
        singleConflictRequest("train", now, interlocking, CorridorDirection.A_TO_B)
            .withResourceIntents(Map.of(interlocking, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(retain).allowed());
    OccupancyRequest reversed =
        singleConflictRequest("train", now.plusSeconds(1), interlocking, CorridorDirection.B_TO_A);

    assertTrue(manager.canEnter(reversed).allowed());
    assertTrue(manager.selfOwnedStaleRetainReleaseCandidate("train").isEmpty());
    assertTrue(manager.previewSelfOwnedStaleRetainReleaseCandidate(reversed).isEmpty());
  }

  @Test
  void interlockingClaimCannotBeBypassedByVerifiedHintAndReleaseLock() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource interlocking = OccupancyResource.forConflict("interlocking:fedcba9876543210");
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "owner", Optional.empty(), now, List.of(interlocking), Map.of()))
            .allowed());
    manager.rememberDeadlockReleaseLock(interlocking.key(), "requester", now.plusSeconds(10));
    OccupancyRequest clearing =
        withClearingHint(
            new OccupancyRequest(
                "requester",
                Optional.empty(),
                now.plusSeconds(1),
                List.of(interlocking),
                Map.of(),
                Map.of(interlocking.key(), 0),
                0,
                AuthorizationPurpose.CONFLICT_CLEARING),
            interlocking);

    OccupancyDecision decision = manager.acquire(clearing);

    assertFalse(decision.allowed());
    assertFalse(decision.conflictRelease());
    assertEquals("owner", decision.blockers().get(0).trainName());
  }

  @Test
  void unlockReservationIsNotHardAuthorityBlocker() {
    assertFalse(ResourceIntent.UNLOCK_RESERVATION.hardAuthority());
  }

  @Test
  void normalAdmissionIgnoresSpeculativeUnlockReservation() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyRequest reservation =
        singleConflictRequest("planner", now, conflict, CorridorDirection.A_TO_B)
            .withResourceIntents(Map.of(conflict, ResourceIntent.UNLOCK_RESERVATION));
    assertTrue(manager.acquire(reservation).allowed());

    OccupancyDecision normalMovement =
        manager.canEnter(
            singleConflictRequest(
                "normal", now.plusSeconds(1), conflict, CorridorDirection.B_TO_A));

    assertTrue(normalMovement.allowed());
    assertTrue(normalMovement.blockers().isEmpty());
  }

  @Test
  void outsideTrainCannotEnterOccupiedOppositeSingleRegion() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    assertTrue(
        manager
            .acquire(singleConflictRequest("inside", now, conflict, CorridorDirection.A_TO_B))
            .allowed());

    OccupancyDecision decision =
        manager.canEnter(
            singleConflictRequest(
                "outside", now.plusSeconds(1), conflict, CorridorDirection.B_TO_A));

    assertFalse(decision.allowed());
    assertEquals(
        SimpleOccupancyManager.OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER, decision.reason());
  }

  @Test
  void outsideTrainCannotEnterOccupiedUnknownDirectionSingleRegion() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    assertTrue(
        manager
            .acquire(singleConflictRequest("inside", now, conflict, CorridorDirection.A_TO_B))
            .allowed());

    OccupancyDecision decision =
        manager.canEnter(
            singleConflictRequest(
                "outside", now.plusSeconds(1), conflict, CorridorDirection.UNKNOWN));

    assertFalse(decision.allowed());
    assertEquals("single-conflict-direction-unknown", decision.reason());
  }

  @Test
  void speculativeUnlockDoesNotReserveExternalOppositeSingleRegion() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    assertTrue(
        manager
            .acquire(singleConflictRequest("inside", now, conflict, CorridorDirection.A_TO_B))
            .allowed());
    OccupancyRequest reservation =
        singleConflictRequest("planner", now.plusSeconds(1), conflict, CorridorDirection.B_TO_A)
            .withResourceIntents(Map.of(conflict, ResourceIntent.UNLOCK_RESERVATION));

    OccupancyDecision decision = manager.acquire(reservation);

    assertFalse(decision.allowed());
    assertEquals(
        SimpleOccupancyManager.OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER, decision.reason());
    assertTrue(
        manager.snapshotClaims().stream()
            .noneMatch(claim -> claim.role() == ClaimRole.UNLOCK_RESERVATION));
  }

  @Test
  void unlockReservationNonBlockingDoesNotDemoteRealExternalOppositeOccupancy() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    assertTrue(
        manager
            .acquire(singleConflictRequest("inside", now, conflict, CorridorDirection.A_TO_B))
            .allowed());
    OccupancyRequest normal =
        singleConflictRequest("normal", now.plusSeconds(1), conflict, CorridorDirection.B_TO_A);

    OccupancyDecision decision = manager.canEnterPreview(normal);

    assertFalse(decision.allowed());
    assertEquals(
        SimpleOccupancyManager.OPPOSITE_OR_UNKNOWN_SINGLE_REGION_HARD_BARRIER, decision.reason());
    assertEquals("inside", decision.blockers().get(0).trainName());
  }

  @Test
  void blockerClassifierDemotesUnlockReservation() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyRequest request =
        singleConflictRequest("normal", now.plusSeconds(1), conflict, CorridorDirection.B_TO_A);
    OccupancyClaim reservation =
        new OccupancyClaim(
            conflict,
            "planner",
            Optional.empty(),
            now,
            Duration.ZERO,
            Optional.of(CorridorDirection.A_TO_B),
            ClaimRole.UNLOCK_RESERVATION);

    BlockerRelation relation = BlockerClassifier.classify(request, conflict, reservation);

    assertEquals(BlockerRelation.STALE_PROTECTIVE_CLAIM, relation);
    assertFalse(BlockerClassifier.isHardMovementBlocker(relation));
  }

  @Test
  void selfOwnedCurrentBodyClaimIsNotReleased() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource bodyNode = OccupancyResource.forNode(NodeId.of("A"));
    OccupancyRequest bodyRetain =
        new OccupancyRequest(
            "train",
            Optional.empty(),
            now,
            List.of(bodyNode),
            Map.of(),
            Map.of(),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            Map.of(),
            Map.of(bodyNode, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(bodyRetain).allowed());

    SimpleOccupancyManager.SelfOwnedStaleRetainReleaseResult result =
        manager.releaseSelfOwnedStaleRetain("train");

    assertFalse(result.candidate());
    assertEquals(1, manager.snapshotClaims().size());
  }

  @Test
  void selfOwnedMovementRequiredForwardClaimIsNotReleased() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    assertTrue(
        manager
            .acquire(singleConflictRequest("train", now, conflict, CorridorDirection.A_TO_B))
            .allowed());
    assertFalse(
        manager
            .canEnter(
                singleConflictRequest(
                    "train", now.plusSeconds(1), conflict, CorridorDirection.B_TO_A))
            .allowed());

    assertTrue(manager.selfOwnedStaleRetainReleaseCandidate("train").isEmpty());
    SimpleOccupancyManager.SelfOwnedStaleRetainReleaseResult result =
        manager.releaseSelfOwnedStaleRetain("train");
    assertFalse(result.candidate());
    assertEquals(1, manager.snapshotClaims().size());
  }

  @Test
  void physicalProtectiveRetainClaimBlocksMovementRequiredEntry() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource node = OccupancyResource.forNode(NodeId.of("A"));
    OccupancyRequest retain =
        new OccupancyRequest(
            "front",
            Optional.empty(),
            now,
            List.of(node),
            Map.of(),
            Map.of(),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            Map.of(),
            Map.of(node, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(retain).allowed());

    OccupancyDecision decision =
        manager.canEnter(
            new OccupancyRequest(
                "rear", Optional.empty(), now.plusSeconds(1), List.of(node), Map.of()));

    assertFalse(decision.allowed());
    assertEquals(SignalAspect.STOP, decision.signal());
    assertEquals("front", decision.blockers().get(0).trainName());
  }

  @Test
  void physicalHoldOnlyClaimBlocksMovementRequiredEntry() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource node = OccupancyResource.forNode(NodeId.of("SURC:S:HHU:1"));
    OccupancyRequest hold =
        new OccupancyRequest(
            "front",
            Optional.empty(),
            now,
            List.of(node),
            Map.of(),
            Map.of(),
            0,
            AuthorizationPurpose.RUNTIME_MOVE,
            Map.of(),
            Map.of(node, ResourceIntent.HOLD_ONLY));
    assertTrue(manager.acquire(hold).allowed());

    OccupancyDecision decision =
        manager.canEnter(
            new OccupancyRequest(
                "rear", Optional.empty(), now.plusSeconds(1), List.of(node), Map.of()));

    assertFalse(decision.allowed());
    assertEquals(SignalAspect.STOP, decision.signal());
    assertEquals("front", decision.blockers().get(0).trainName());
  }

  @Test
  void conflictProtectiveRetainClaimDoesNotBecomeHardBlocker() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    OccupancyRequest retain =
        singleConflictRequest("front", now, conflict, CorridorDirection.A_TO_B)
            .withResourceIntents(Map.of(conflict, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(retain).allowed());

    OccupancyDecision decision =
        manager.canEnter(
            singleConflictRequest("rear", now.plusSeconds(1), conflict, CorridorDirection.A_TO_B));

    assertTrue(decision.allowed(), decision.toString());
    assertTrue(decision.blockers().isEmpty());
  }

  @Test
  void sameDirectionRearTrainStopsBehindFront() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource forwardEdge =
        OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("M1"), NodeId.of("M2")));
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "front", Optional.empty(), now, List.of(forwardEdge), Map.of()))
            .allowed());

    OccupancyDecision rear =
        manager.canEnter(
            new OccupancyRequest(
                "rear", Optional.empty(), now.plusSeconds(1), List.of(forwardEdge), Map.of()));

    assertFalse(rear.allowed());
    assertEquals(SignalAspect.STOP, rear.signal());
    assertEquals("front", rear.blockers().get(0).trainName());
  }

  private static OccupancyRequest singleConflictRequest(
      String trainName, Instant now, OccupancyResource conflict, CorridorDirection direction) {
    NodeId from = direction == CorridorDirection.B_TO_A ? NodeId.of("B") : NodeId.of("A");
    NodeId to = direction == CorridorDirection.B_TO_A ? NodeId.of("A") : NodeId.of("B");
    OccupancyRequest request =
        new OccupancyRequest(
            trainName,
            Optional.empty(),
            now,
            List.of(conflict),
            Map.of(conflict.key(), direction),
            Map.of(conflict.key(), 0),
            0);
    return request.withDirectedContext(
        Optional.of(
            new DirectedTraversalContext(
                trainName,
                Optional.empty(),
                0,
                Optional.of(from),
                Optional.empty(),
                Optional.of(from),
                Optional.of(to),
                List.of(from, to),
                List.of(
                    new DirectedTraversalContext.DirectedEdge(
                        EdgeId.undirected(from, to), from, to)),
                Map.of(conflict.key(), direction),
                Map.of(),
                "TEST",
                -1L,
                -1L,
                "test",
                Optional.empty())));
  }

  private static OccupancyRequest sectionRequestWithPlanDirections(
      String trainName,
      Instant now,
      OccupancyResource section,
      Map<String, CorridorDirection> requestDirections,
      Map<String, CorridorDirection> planDirections) {
    NodeId from = NodeId.of("A");
    NodeId to = NodeId.of("B");
    OccupancyRequest request =
        new OccupancyRequest(
            trainName,
            Optional.empty(),
            now,
            List.of(section),
            requestDirections,
            Map.of(section.key(), 0),
            0);
    return request.withDirectedContext(
        Optional.of(
            new DirectedTraversalContext(
                trainName,
                Optional.empty(),
                0,
                Optional.of(from),
                Optional.empty(),
                Optional.of(from),
                Optional.of(to),
                List.of(from, to),
                List.of(
                    new DirectedTraversalContext.DirectedEdge(
                        EdgeId.undirected(from, to), from, to)),
                planDirections,
                Map.of(),
                "TEST",
                -1L,
                -1L,
                "test-section-token",
                Optional.empty())));
  }

  private static OccupancyRequest singleConflictRequestWithSnapshotDirection(
      String trainName,
      Instant now,
      OccupancyResource conflict,
      Map<String, CorridorDirection> requestDirections,
      Map<String, CorridorDirection> snapshotDirections,
      Map<String, DirectedTraversalContext.SwitcherPathSignature> switcherSignatures) {
    CorridorDirection direction =
        snapshotDirections.getOrDefault(
            conflict.key(),
            requestDirections.getOrDefault(conflict.key(), CorridorDirection.A_TO_B));
    NodeId from = direction == CorridorDirection.B_TO_A ? NodeId.of("B") : NodeId.of("A");
    NodeId to = direction == CorridorDirection.B_TO_A ? NodeId.of("A") : NodeId.of("B");
    OccupancyRequest request =
        new OccupancyRequest(
            trainName,
            Optional.empty(),
            now,
            List.of(conflict),
            requestDirections,
            Map.of(conflict.key(), 0),
            0);
    return request.withDirectedContext(
        Optional.of(
            new DirectedTraversalContext(
                trainName,
                Optional.empty(),
                0,
                Optional.of(from),
                Optional.empty(),
                Optional.of(from),
                Optional.of(to),
                List.of(from, to),
                List.of(
                    new DirectedTraversalContext.DirectedEdge(
                        EdgeId.undirected(from, to), from, to)),
                snapshotDirections,
                switcherSignatures,
                "TEST",
                -1L,
                -1L,
                "test",
                Optional.empty())));
  }

  private static OccupancyRequest switcherRequest(
      String trainName, Instant now, OccupancyResource resource, List<NodeId> pathNodes) {
    return switcherRequest(trainName, now, resource, pathNodes, List.of(resource), -1L, -1L);
  }

  private static OccupancyRequest concreteSwitcherRequest(
      String trainName, Instant now, OccupancyResource resource, List<NodeId> pathNodes) {
    List<OccupancyResource> resources = new java.util.ArrayList<>();
    resources.add(resource);
    for (int index = 0; index + 1 < pathNodes.size(); index++) {
      resources.add(
          OccupancyResource.forEdge(
              EdgeId.undirected(pathNodes.get(index), pathNodes.get(index + 1))));
    }
    return switcherRequest(trainName, now, resource, pathNodes, resources, 1L, 1L);
  }

  private static OccupancyRequest verifiedSwitcherRuntimeRequest(
      String trainName, Instant now, OccupancyResource resource, List<NodeId> pathNodes) {
    OccupancyResource switcherNode = OccupancyResource.forNode(pathNodes.get(0));
    OccupancyResource exitEdge =
        OccupancyResource.forEdge(EdgeId.undirected(pathNodes.get(0), pathNodes.get(1)));
    return switcherRequest(
        trainName, now, resource, pathNodes, List.of(resource, switcherNode, exitEdge), 1L, 1L);
  }

  private static OccupancyRequest switcherRequest(
      String trainName,
      Instant now,
      OccupancyResource resource,
      List<NodeId> pathNodes,
      List<OccupancyResource> resources,
      long occupancyVersion,
      long progressVersion) {
    List<DirectedTraversalContext.DirectedEdge> edges = new java.util.ArrayList<>();
    for (int i = 0; i < pathNodes.size() - 1; i++) {
      NodeId from = pathNodes.get(i);
      NodeId to = pathNodes.get(i + 1);
      edges.add(new DirectedTraversalContext.DirectedEdge(EdgeId.undirected(from, to), from, to));
    }
    OccupancyRequest request =
        new OccupancyRequest(
            trainName, Optional.empty(), now, resources, Map.of(), Map.of(resource.key(), 0), 0);
    return request.withDirectedContext(
        Optional.of(
            new DirectedTraversalContext(
                trainName,
                Optional.empty(),
                0,
                Optional.of(pathNodes.get(0)),
                Optional.empty(),
                Optional.of(pathNodes.get(0)),
                pathNodes.size() < 2 ? Optional.empty() : Optional.of(pathNodes.get(1)),
                pathNodes,
                edges,
                Map.of(),
                Map.of(
                    resource.key(),
                    new DirectedTraversalContext.SwitcherPathSignature(resource.key(), pathNodes)),
                "TEST",
                occupancyVersion,
                progressVersion,
                "test",
                Optional.empty())));
  }

  private static OccupancyRequest sectionPhysicalRequest(
      String trainName,
      Instant now,
      OccupancyResource section,
      CorridorDirection direction,
      OccupancyResource physicalResource,
      ResourceIntent physicalIntent) {
    return new OccupancyRequest(
        trainName,
        Optional.empty(),
        now,
        List.of(section, physicalResource),
        Map.of(section.key(), direction),
        Map.of(section.key(), 0),
        0,
        AuthorizationPurpose.RUNTIME_MOVE,
        Map.of(),
        Map.of(section, ResourceIntent.MOVEMENT_REQUIRED, physicalResource, physicalIntent));
  }

  private static OccupancyRequest switcherSectionRequest(
      String trainName,
      Instant now,
      OccupancyResource section,
      OccupancyResource switcher,
      CorridorDirection direction,
      List<NodeId> pathNodes) {
    return switcherSectionRequest(
        trainName, now, section, switcher, direction, pathNodes, List.of());
  }

  private static OccupancyRequest switcherSectionRequest(
      String trainName,
      Instant now,
      OccupancyResource section,
      OccupancyResource switcher,
      CorridorDirection direction,
      List<NodeId> pathNodes,
      List<OccupancyResource> extraResources) {
    List<DirectedTraversalContext.DirectedEdge> edges = new java.util.ArrayList<>();
    for (int i = 0; i < pathNodes.size() - 1; i++) {
      NodeId from = pathNodes.get(i);
      NodeId to = pathNodes.get(i + 1);
      edges.add(new DirectedTraversalContext.DirectedEdge(EdgeId.undirected(from, to), from, to));
    }
    List<OccupancyResource> resources = new java.util.ArrayList<>();
    resources.add(section);
    resources.add(switcher);
    resources.addAll(extraResources);
    Map<String, CorridorDirection> directions = Map.of(section.key(), direction);
    Map<String, Integer> entryOrders = new java.util.LinkedHashMap<>();
    entryOrders.put(section.key(), 0);
    entryOrders.put(switcher.key(), 1);
    OccupancyRequest request =
        new OccupancyRequest(
            trainName, Optional.empty(), now, resources, directions, entryOrders, 0);
    return request.withDirectedContext(
        Optional.of(
            new DirectedTraversalContext(
                trainName,
                Optional.empty(),
                0,
                Optional.of(pathNodes.get(0)),
                Optional.empty(),
                Optional.of(pathNodes.get(0)),
                pathNodes.size() < 2 ? Optional.empty() : Optional.of(pathNodes.get(1)),
                pathNodes,
                edges,
                directions,
                Map.of(
                    switcher.key(),
                    new DirectedTraversalContext.SwitcherPathSignature(switcher.key(), pathNodes)),
                "TEST",
                -1L,
                -1L,
                "test",
                Optional.empty())));
  }

  @Test
  void rawNameAndFtaNameSelfClaimsAreIgnored() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource node = OccupancyResource.forNode(NodeId.of("A"));
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    "train-main~a", Optional.empty(), now, List.of(node), Map.of()))
            .allowed());

    OccupancyDecision decision =
        manager.canEnter(
            new OccupancyRequest(
                "train-main", Optional.empty(), now.plusSeconds(1), List.of(node), Map.of()));

    assertTrue(decision.allowed());
    assertTrue(decision.blockers().isEmpty());
  }

  @Test
  void staleUnknownQueueEntryDoesNotBlockKnownSameDirectionAfterCleanup() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    manager.touchQueues(
        new OccupancyRequest(
            "stale-unknown",
            Optional.empty(),
            now,
            List.of(conflict),
            Map.of(),
            Map.of(conflict.key(), 0),
            0));

    Map<String, CorridorDirection> forward = Map.of(conflict.key(), CorridorDirection.A_TO_B);
    OccupancyDecision decision =
        manager.canEnter(
            new OccupancyRequest(
                "known-same",
                Optional.empty(),
                now.plusSeconds(31),
                List.of(conflict),
                forward,
                Map.of(conflict.key(), 0),
                0));

    assertTrue(decision.allowed());
  }

  @Test
  void sameDirectionSingleConflictDoesNotQueueSerialize() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    Map<String, CorridorDirection> direction = Map.of(conflict.key(), CorridorDirection.A_TO_B);

    OccupancyRequest front =
        new OccupancyRequest(
            "front",
            Optional.empty(),
            now,
            List.of(conflict),
            direction,
            Map.of(conflict.key(), 0),
            0);
    assertTrue(manager.acquire(front).allowed());

    OccupancyDecision rear =
        manager.canEnter(
            new OccupancyRequest(
                "rear",
                Optional.empty(),
                now.plusSeconds(1),
                List.of(conflict),
                direction,
                Map.of(conflict.key(), 0),
                0));

    assertTrue(rear.allowed());
    assertTrue(rear.blockers().isEmpty());
  }

  @Test
  void sameDirectionSingleConflictDoesNotQueueSerializeWithStaleRearEntry() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    Map<String, CorridorDirection> direction = Map.of(conflict.key(), CorridorDirection.A_TO_B);
    manager.touchQueues(
        new OccupancyRequest(
            "stale-rear",
            Optional.empty(),
            now,
            List.of(conflict),
            direction,
            Map.of(conflict.key(), 9),
            0));

    OccupancyDecision front =
        manager.canEnter(
            new OccupancyRequest(
                "front",
                Optional.empty(),
                now.plusSeconds(31),
                List.of(conflict),
                direction,
                Map.of(conflict.key(), 0),
                0));

    assertTrue(front.allowed());
  }

  @Test
  void unknownDirectionSingleEntryStillFailsClosed() {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    manager.touchQueues(
        new OccupancyRequest(
            "unknown",
            Optional.empty(),
            now,
            List.of(conflict),
            Map.of(),
            Map.of(conflict.key(), 0),
            0));

    OccupancyDecision decision =
        manager.canEnter(
            new OccupancyRequest(
                "known",
                Optional.empty(),
                now.plusSeconds(1),
                List.of(conflict),
                Map.of(conflict.key(), CorridorDirection.A_TO_B),
                Map.of(conflict.key(), 1),
                0));

    assertFalse(decision.allowed());
    assertEquals(SignalAspect.STOP, decision.signal());
  }

  @Test
  void conflictClearingHintWithoutReleaseLeaderDoesNotEmitDrainIncident() {
    java.util.List<String> traces = new java.util.ArrayList<>();
    SignalComputationTrace.configureLogger(traces::add);
    try {
      HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
      SimpleOccupancyManager manager =
          new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

      Instant now = Instant.parse("2026-01-01T00:00:00Z");
      OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
      Map<String, CorridorDirection> direction = Map.of(conflict.key(), CorridorDirection.A_TO_B);
      OccupancyRequest request =
          withClearingHint(
              new OccupancyRequest(
                  "drain",
                  Optional.empty(),
                  now,
                  List.of(conflict),
                  direction,
                  Map.of(conflict.key(), 0),
                  0,
                  AuthorizationPurpose.CONFLICT_CLEARING),
              conflict);

      OccupancyDecision decision = manager.canEnter(request);

      assertTrue(decision.allowed());
      assertFalse(decision.conflictRelease());
      assertFalse(
          traces.stream().anyMatch(message -> message.contains("DRAIN_AUTHORITY_INCONSISTENT")));
    } finally {
      SignalComputationTrace.configureLogger(message -> {});
    }
  }

  private static OccupancyRequest withClearingHint(
      OccupancyRequest request, OccupancyResource conflict) {
    return request.withConflictReleaseHints(
        request.purpose(), Map.of(conflict.key(), ConflictReleaseHint.verified(conflict.key())));
  }

  private static void assertPurposeDoesNotRelease(AuthorizationPurpose purpose) {
    HeadwayRule headwayRule = (routeId, resource) -> Duration.ZERO;
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(headwayRule, SignalAspectPolicy.defaultPolicy());

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    OccupancyResource conflict = OccupancyResource.forConflict("single:comp:A~B");
    Map<String, CorridorDirection> forward = Map.of(conflict.key(), CorridorDirection.A_TO_B);
    Map<String, CorridorDirection> reverse = Map.of(conflict.key(), CorridorDirection.B_TO_A);

    manager.acquire(
        new OccupancyRequest(
            "tBlocker",
            Optional.empty(),
            now,
            List.of(conflict),
            reverse,
            Map.of(conflict.key(), 2),
            0));

    OccupancyDecision decision =
        manager.canEnter(
            withClearingHint(
                new OccupancyRequest(
                    "tReq",
                    Optional.empty(),
                    now.plusSeconds(1),
                    List.of(conflict),
                    forward,
                    Map.of(conflict.key(), 0),
                    0,
                    purpose),
                conflict));

    assertFalse(decision.allowed());
    assertFalse(decision.conflictRelease());
  }
}
