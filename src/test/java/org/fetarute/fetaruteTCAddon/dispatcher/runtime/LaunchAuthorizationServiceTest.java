package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
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

class LaunchAuthorizationServiceTest {

  @Test
  void authorizeRunsActionsWhenAllowedAndAcquired() {
    OccupancyRequest request = request("train-1");
    StubOccupancyManager manager =
        new StubOccupancyManager(allowed(request), allowed(request), false);
    LaunchAuthorizationService service = new LaunchAuthorizationService(manager, null, null);
    CountingActions actions = new CountingActions();

    LaunchAuthorizationService.AuthorizationResult result =
        service.authorize(
            new LaunchAuthorizationService.AuthorizationPlan(
                request, "test", false, true, false, false, actions));

    assertTrue(result.allowed());
    assertTrue(result.acquired());
    assertEquals(1, manager.canEnterCalls);
    assertEquals(1, manager.acquireCalls);
    assertEquals(1, actions.writeDestinationCalls);
    assertEquals(1, actions.launchCalls);
    assertEquals(1, actions.refreshCalls);
    assertEquals(0, actions.holdCalls);
  }

  @Test
  void authorizeHoldsStopWhenBlocked() {
    OccupancyRequest request = request("train-1");
    StubOccupancyManager manager =
        new StubOccupancyManager(blocked(request, List.of()), allowed(request), false);
    LaunchAuthorizationService service = new LaunchAuthorizationService(manager, null, null);
    CountingActions actions = new CountingActions();

    LaunchAuthorizationService.AuthorizationResult result =
        service.authorize(
            new LaunchAuthorizationService.AuthorizationPlan(
                request, "test", false, true, false, false, actions));

    assertFalse(result.allowed());
    assertEquals(SignalAspect.STOP, result.signal());
    assertEquals(1, manager.canEnterCalls);
    assertEquals(0, manager.acquireCalls);
    assertEquals(1, actions.holdCalls);
    assertEquals(0, actions.launchCalls);
  }

  @Test
  void authorizeSuppressesAllowedDecisionWithHardBlocker() {
    OccupancyRequest request = request("train-1");
    OccupancyClaim blocker =
        new OccupancyClaim(
            OccupancyResource.forNode(NodeId.of("B")),
            "train-2",
            Optional.empty(),
            request.now(),
            Duration.ZERO,
            Optional.empty());
    StubOccupancyManager manager =
        new StubOccupancyManager(
            new OccupancyDecision(
                true, request.now(), SignalAspect.PROCEED, List.of(blocker), false),
            allowed(request),
            false);
    LaunchAuthorizationService service = new LaunchAuthorizationService(manager, null, null);
    CountingActions actions = new CountingActions();

    LaunchAuthorizationService.AuthorizationResult result =
        service.authorize(
            new LaunchAuthorizationService.AuthorizationPlan(
                request, "test", false, true, false, false, actions));

    assertFalse(result.allowed());
    assertTrue(result.rawAllowed());
    assertTrue(result.hardBlockerBypass());
    assertEquals(0, manager.acquireCalls);
    assertEquals(1, actions.holdCalls);
  }

  @Test
  void authorizeDestroysLaunchPathWhenAcquireFails() {
    OccupancyRequest request = request("train-1");
    OccupancyClaim blocker =
        new OccupancyClaim(
            OccupancyResource.forEdge(
                org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId.undirected(
                    NodeId.of("A"), NodeId.of("B"))),
            "train-2",
            Optional.empty(),
            request.now(),
            Duration.ZERO,
            Optional.empty());
    StubOccupancyManager manager =
        new StubOccupancyManager(allowed(request), blocked(request, List.of(blocker)), false);
    LaunchAuthorizationService service = new LaunchAuthorizationService(manager, null, null);
    CountingActions actions = new CountingActions();

    LaunchAuthorizationService.AuthorizationResult result =
        service.authorize(
            new LaunchAuthorizationService.AuthorizationPlan(
                request, "test", false, true, false, false, actions));

    assertFalse(result.allowed());
    assertTrue(result.acquireAttempted());
    assertFalse(result.acquired());
    assertEquals(1, manager.acquireCalls);
    assertEquals(1, actions.holdCalls);
    assertEquals(0, actions.launchCalls);
  }

  private static OccupancyRequest request(String trainName) {
    return new OccupancyRequest(
        trainName,
        Optional.empty(),
        Instant.parse("2026-01-01T00:00:00Z"),
        List.of(OccupancyResource.forNode(NodeId.of("A"))),
        Map.of(),
        Map.of(),
        0);
  }

  private static OccupancyDecision allowed(OccupancyRequest request) {
    return new OccupancyDecision(true, request.now(), SignalAspect.PROCEED, List.of());
  }

  private static OccupancyDecision blocked(
      OccupancyRequest request, List<OccupancyClaim> blockers) {
    return new OccupancyDecision(false, request.now(), SignalAspect.STOP, blockers);
  }

  private static final class CountingActions implements LaunchAuthorizationService.LaunchActions {
    private int holdCalls;
    private int writeDestinationCalls;
    private int launchCalls;
    private int refreshCalls;

    @Override
    public void holdStop(LaunchAuthorizationService.AuthorizationResult result) {
      holdCalls++;
    }

    @Override
    public void writeDestination(LaunchAuthorizationService.AuthorizationResult result) {
      writeDestinationCalls++;
    }

    @Override
    public void launchOrProceed(LaunchAuthorizationService.AuthorizationResult result) {
      launchCalls++;
    }

    @Override
    public void refreshRelated(LaunchAuthorizationService.AuthorizationResult result) {
      refreshCalls++;
    }
  }

  private static final class StubOccupancyManager implements OccupancyManager {
    private final OccupancyDecision canEnterDecision;
    private final OccupancyDecision acquireDecision;
    private final boolean shouldYield;
    private int canEnterCalls;
    private int acquireCalls;

    private StubOccupancyManager(
        OccupancyDecision canEnterDecision,
        OccupancyDecision acquireDecision,
        boolean shouldYield) {
      this.canEnterDecision = canEnterDecision;
      this.acquireDecision = acquireDecision;
      this.shouldYield = shouldYield;
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

    @Override
    public boolean shouldYield(OccupancyRequest request) {
      return shouldYield;
    }
  }
}
