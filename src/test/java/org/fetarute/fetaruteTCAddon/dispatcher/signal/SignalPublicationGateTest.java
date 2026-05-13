package org.fetarute.fetaruteTCAddon.dispatcher.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ConflictReleaseHint;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.DirectedTraversalContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

/** 最终信号发布门回归测试。 */
class SignalPublicationGateTest {

  @Test
  void sameSnapshotProducesSameAspectIndependentOfPreviousAspect() {
    OccupancyRequest request = movementRequest("train", 0);

    SignalPublicationGate.Decision first =
        SignalPublicationGate.evaluate(
            input(request, SignalAspect.PROCEED, false, SignalComputationTrace.TokenState.ACTIVE));
    SignalPublicationGate.Decision second =
        SignalPublicationGate.evaluate(
            input(request, SignalAspect.PROCEED, false, SignalComputationTrace.TokenState.ACTIVE));

    assertEquals(SignalAspect.PROCEED, first.visibleAspect());
    assertEquals(first.visibleAspect(), second.visibleAspect());
    assertFalse(first.blocked());
  }

  @Test
  void movementInhibitedCannotPublishProceedEvenWhenCanEnterAllowed() {
    SignalPublicationGate.Decision decision =
        SignalPublicationGate.evaluate(
            input(
                movementRequest("train", 0),
                SignalAspect.PROCEED,
                true,
                SignalComputationTrace.TokenState.PENDING));

    assertTrue(decision.blocked());
    assertEquals(SignalAspect.STOP, decision.visibleAspect());
    assertEquals("movement-inhibited", decision.reason());
  }

  @Test
  void movementAuthorityDowngradeCannotBypassPublicationGate() {
    OccupancyRequest request =
        request("train", 0, ResourceIntent.PROTECTIVE_RETAIN, AuthorizationPurpose.RUNTIME_MOVE);

    SignalPublicationGate.Decision decision =
        SignalPublicationGate.evaluate(
            input(
                request,
                SignalAspect.PROCEED_WITH_CAUTION,
                false,
                SignalComputationTrace.TokenState.ACTIVE));

    assertTrue(decision.blocked());
    assertEquals(SignalAspect.STOP, decision.visibleAspect());
    assertEquals(SignalDecisionInputType.PROTECTIVE_RETAIN, decision.inputType());
  }

  @Test
  void recentFlipWithinTwoTicksIsDebugOnly() {
    OccupancyRequest request = movementRequest("train", 0);

    SignalPublicationGate.Decision proceed =
        SignalPublicationGate.evaluate(
            input(request, SignalAspect.PROCEED, false, SignalComputationTrace.TokenState.ACTIVE));
    SignalPublicationGate.Decision caution =
        SignalPublicationGate.evaluate(
            input(
                request,
                SignalAspect.PROCEED_WITH_CAUTION,
                false,
                SignalComputationTrace.TokenState.ACTIVE));

    assertEquals(SignalAspect.PROCEED, proceed.visibleAspect());
    assertEquals(SignalAspect.PROCEED_WITH_CAUTION, caution.visibleAspect());
  }

  @Test
  void conflictClearingPurposeDoesNotImplyDrainThrough() {
    OccupancyRequest request =
        request(
            "train", 1, ResourceIntent.MOVEMENT_REQUIRED, AuthorizationPurpose.CONFLICT_CLEARING);

    SignalPublicationGate.Decision decision =
        SignalPublicationGate.evaluate(
            input(request, SignalAspect.PROCEED, false, SignalComputationTrace.TokenState.ACTIVE));

    assertEquals(SignalDecisionInputType.CONFLICT_CLEARING, decision.inputType());
    assertFalse(decision.blocked());
    assertEquals("allowed", decision.reason());
  }

  @Test
  void drainGateAppliesOnlyToDrainThrough() {
    OccupancyRequest request = movementRequest("train", 1);

    SignalPublicationGate.Decision decision =
        SignalPublicationGate.evaluate(
            drainInput(
                request,
                SignalAspect.PROCEED,
                false,
                SignalComputationTrace.TokenState.ACTIVE,
                false,
                true,
                true,
                true,
                true,
                true,
                false,
                false));

    assertEquals(SignalDecisionInputType.FORWARD_MOVEMENT, decision.inputType());
    assertFalse(decision.blocked());
    assertEquals(SignalAspect.PROCEED, decision.visibleAspect());
  }

  @Test
  void drainWithoutLeaderIsLocalOnlyStop() {
    OccupancyRequest request =
        request(
                "train",
                1,
                ResourceIntent.MOVEMENT_REQUIRED,
                AuthorizationPurpose.CONFLICT_CLEARING)
            .withConflictReleaseHints(
                AuthorizationPurpose.CONFLICT_CLEARING,
                Map.of(
                    "single:A~B",
                    ConflictReleaseHint.verifiedDrainAuthority("single:A~B", "test")));

    SignalPublicationGate.Decision decision =
        SignalPublicationGate.evaluate(
            drainInput(
                request,
                SignalAspect.PROCEED,
                false,
                SignalComputationTrace.TokenState.ACTIVE,
                false,
                true,
                true,
                true,
                true,
                true,
                false,
                false));

    assertEquals(SignalDecisionInputType.DRAIN_THROUGH, decision.inputType());
    assertTrue(decision.blocked());
    assertTrue(decision.localOnlyStop());
    assertEquals(SignalAspect.STOP, decision.visibleAspect());
    assertEquals("drain-authority-without-leader", decision.reason());
  }

  @Test
  void departureHoldingSingleClaimIsForwardMovement() {
    OccupancyRequest request =
        movementRequest("train", 0)
            .withConflictClearingEvidence(
                Map.of("single:A~B", ConflictReleaseHint.topologyExit("single:A~B", "test")));

    SignalPublicationGate.Decision decision =
        SignalPublicationGate.evaluate(
            input(request, SignalAspect.PROCEED, false, SignalComputationTrace.TokenState.ACTIVE));

    assertEquals(SignalDecisionInputType.FORWARD_MOVEMENT, decision.inputType());
    assertFalse(decision.blocked());
    assertEquals(SignalAspect.PROCEED, decision.visibleAspect());
  }

  @Test
  void drainAuthorityFailureDoesNotClearDepartureDestination() {
    OccupancyRequest request =
        movementRequest("train", 0)
            .withConflictClearingEvidence(
                Map.of("single:A~B", ConflictReleaseHint.topologyExit("single:A~B", "stale")));

    SignalPublicationGate.Decision decision =
        SignalPublicationGate.evaluate(
            drainInput(
                request,
                SignalAspect.PROCEED,
                false,
                SignalComputationTrace.TokenState.ACTIVE,
                false,
                true,
                false,
                false,
                false,
                false,
                true,
                true));

    assertEquals(SignalDecisionInputType.FORWARD_MOVEMENT, decision.inputType());
    assertFalse(decision.blocked());
    assertFalse(decision.localOnlyStop());
    assertEquals("allowed", decision.reason());
  }

  @Test
  void noStopProceedStopLoopOnDeparture() {
    OccupancyRequest request =
        movementRequest("train", 0)
            .withConflictClearingEvidence(
                Map.of("single:A~B", ConflictReleaseHint.topologyExit("single:A~B", "test")));

    SignalPublicationGate.Decision first =
        SignalPublicationGate.evaluate(
            input(request, SignalAspect.PROCEED, false, SignalComputationTrace.TokenState.ACTIVE));
    SignalPublicationGate.Decision second =
        SignalPublicationGate.evaluate(
            input(request, SignalAspect.PROCEED, false, SignalComputationTrace.TokenState.ACTIVE));

    assertEquals(SignalAspect.PROCEED, first.visibleAspect());
    assertEquals(SignalAspect.PROCEED, second.visibleAspect());
    assertFalse(first.blocked());
    assertFalse(second.blocked());
  }

  private static SignalPublicationGate.Input input(
      OccupancyRequest request,
      SignalAspect candidate,
      boolean movementInhibited,
      SignalComputationTrace.TokenState tokenState) {
    return drainInput(
        request,
        candidate,
        movementInhibited,
        tokenState,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        true);
  }

  private static SignalPublicationGate.Input drainInput(
      OccupancyRequest request,
      SignalAspect candidate,
      boolean movementInhibited,
      SignalComputationTrace.TokenState tokenState,
      boolean drainLeader,
      boolean drainAuthorityActive,
      boolean drainAuthorityFresh,
      boolean drainAuthorityZoneMatches,
      boolean trainInsideMatchingSingleConflict,
      boolean pathDrainingTowardExit,
      boolean ordinaryDeparture,
      boolean topologyExitHintOnly) {
    return new SignalPublicationGate.Input(
        request,
        candidate,
        movementInhibited,
        tokenState,
        drainLeader,
        drainAuthorityActive,
        drainAuthorityFresh,
        drainAuthorityZoneMatches,
        trainInsideMatchingSingleConflict,
        pathDrainingTowardExit,
        ordinaryDeparture,
        topologyExitHintOnly,
        true,
        "-");
  }

  private static OccupancyRequest movementRequest(String trainName, int currentIndex) {
    return request(
        trainName,
        currentIndex,
        ResourceIntent.MOVEMENT_REQUIRED,
        AuthorizationPurpose.RUNTIME_MOVE);
  }

  private static OccupancyRequest request(
      String trainName, int currentIndex, ResourceIntent intent, AuthorizationPurpose purpose) {
    NodeId a = NodeId.of("A");
    NodeId b = NodeId.of("B");
    OccupancyResource resource = OccupancyResource.forNode(b);
    DirectedTraversalContext context =
        new DirectedTraversalContext(
            trainName,
            Optional.of(RouteId.of("r")),
            currentIndex,
            Optional.of(a),
            Optional.empty(),
            Optional.of(a),
            Optional.of(b),
            List.of(a, b),
            List.of(),
            Map.of(),
            Map.of(),
            "TEST",
            1L,
            1L,
            "test",
            Optional.empty());
    return new OccupancyRequest(
        trainName,
        Optional.of(RouteId.of("r")),
        Instant.parse("2026-01-01T00:00:00Z"),
        List.of(resource),
        Map.of(),
        Map.of(),
        0,
        purpose,
        Map.of(),
        Map.of(resource, intent),
        Optional.of(context));
  }
}
