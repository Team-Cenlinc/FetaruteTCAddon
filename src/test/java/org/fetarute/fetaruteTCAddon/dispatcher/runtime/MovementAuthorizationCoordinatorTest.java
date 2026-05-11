package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

class MovementAuthorizationCoordinatorTest {

  @Test
  void canEnterFailureReturnsBlockedWithoutAcquire() {
    OccupancyRequest request = request();
    OccupancyDecision blocked =
        new OccupancyDecision(false, request.now(), SignalAspect.STOP, List.of());
    StubOccupancyManager occupancy = new StubOccupancyManager(blocked, blocked);
    MovementAuthorizationCoordinator coordinator =
        new MovementAuthorizationCoordinator(occupancy, message -> {});

    MovementAuthorizationCoordinator.AuthorizationResult result =
        coordinator.authorize(
            authRequest(
                request, Optional.empty(), MovementAuthorizationCoordinatorTest::byDecision));

    assertFalse(result.proceedAllowed());
    assertSame(blocked, result.decision());
    assertEquals(1, occupancy.canEnterCalls);
    assertEquals(0, occupancy.acquireCalls);
  }

  @Test
  void hardBlockerEvaluatorRejectsBeforeAcquire() {
    OccupancyRequest request = request();
    OccupancyDecision preview =
        new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
    StubOccupancyManager occupancy = new StubOccupancyManager(preview, preview);
    MovementAuthorizationCoordinator coordinator =
        new MovementAuthorizationCoordinator(occupancy, message -> {});

    MovementAuthorizationCoordinator.AuthorizationResult result =
        coordinator.authorize(
            authRequest(
                request,
                Optional.of(preview),
                (trainName, decision, now, scope) ->
                    new MovementAuthorizationCoordinator.ProceedEvaluation(false, true, true)));

    assertFalse(result.proceedAllowed());
    assertTrue(result.hardBlockerBypass());
    assertEquals(0, occupancy.canEnterCalls);
    assertEquals(0, occupancy.acquireCalls);
  }

  @Test
  void acquireFailureBlocksFinalAuthorization() {
    OccupancyRequest request = request();
    OccupancyDecision allowed =
        new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
    OccupancyDecision acquireBlocked =
        new OccupancyDecision(false, request.now(), SignalAspect.STOP, List.of());
    StubOccupancyManager occupancy = new StubOccupancyManager(allowed, acquireBlocked);
    MovementAuthorizationCoordinator coordinator =
        new MovementAuthorizationCoordinator(occupancy, message -> {});

    MovementAuthorizationCoordinator.AuthorizationResult result =
        coordinator.authorize(
            authRequest(
                request, Optional.empty(), MovementAuthorizationCoordinatorTest::byDecision));

    assertFalse(result.proceedAllowed());
    assertTrue(result.acquireAttempted());
    assertFalse(result.acquired());
    assertSame(acquireBlocked, result.decision());
    assertEquals(1, occupancy.canEnterCalls);
    assertEquals(1, occupancy.acquireCalls);
  }

  @Test
  void allowedPreviewAndAcquirePermitDestinationCommitByCaller() {
    OccupancyRequest request = request();
    OccupancyDecision allowed =
        new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
    StubOccupancyManager occupancy = new StubOccupancyManager(allowed, allowed);
    MovementAuthorizationCoordinator coordinator =
        new MovementAuthorizationCoordinator(occupancy, message -> {});

    MovementAuthorizationCoordinator.AuthorizationResult result =
        coordinator.authorize(
            authRequest(
                request, Optional.of(allowed), MovementAuthorizationCoordinatorTest::byDecision));

    assertTrue(result.proceedAllowed());
    assertTrue(result.acquired());
    assertEquals(0, occupancy.canEnterCalls);
    assertEquals(1, occupancy.acquireCalls);
  }

  private static MovementAuthorizationCoordinator.AuthorizationRequest authRequest(
      OccupancyRequest request,
      Optional<OccupancyDecision> previewDecision,
      MovementAuthorizationCoordinator.ProceedEvaluator evaluator) {
    return new MovementAuthorizationCoordinator.AuthorizationRequest(
        request.trainName(),
        request,
        previewDecision,
        request.now(),
        "test",
        "test-acquire",
        evaluator);
  }

  private static MovementAuthorizationCoordinator.ProceedEvaluation byDecision(
      String trainName, OccupancyDecision decision, Instant now, String scope) {
    return new MovementAuthorizationCoordinator.ProceedEvaluation(
        decision.allowed(), decision.allowed(), false);
  }

  private static OccupancyRequest request() {
    return new OccupancyRequest(
        "train",
        Optional.empty(),
        Instant.parse("2026-01-01T00:00:00Z"),
        List.of(OccupancyResource.forNode(NodeId.of("OP:S:A:1"))),
        Map.of());
  }

  private static final class StubOccupancyManager implements OccupancyManager {
    private final OccupancyDecision canEnterDecision;
    private final OccupancyDecision acquireDecision;
    private int canEnterCalls;
    private int acquireCalls;

    private StubOccupancyManager(
        OccupancyDecision canEnterDecision, OccupancyDecision acquireDecision) {
      this.canEnterDecision = canEnterDecision;
      this.acquireDecision = acquireDecision;
    }

    @Override
    public OccupancyDecision canEnter(OccupancyRequest request) {
      canEnterCalls++;
      return canEnterDecision;
    }

    @Override
    public OccupancyDecision acquire(OccupancyRequest request) {
      acquireCalls++;
      return acquireDecision;
    }

    @Override
    public Optional<OccupancyClaim> getClaim(OccupancyResource resource) {
      return Optional.empty();
    }

    @Override
    public List<OccupancyClaim> snapshotClaims() {
      return List.of();
    }

    @Override
    public int releaseByTrain(String trainName) {
      return 0;
    }

    @Override
    public boolean releaseResource(OccupancyResource resource, Optional<String> trainName) {
      return false;
    }
  }
}
