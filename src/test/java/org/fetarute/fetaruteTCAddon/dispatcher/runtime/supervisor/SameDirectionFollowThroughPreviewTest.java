package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalComputationTrace;
import org.junit.jupiter.api.Test;

class SameDirectionFollowThroughPreviewTest {

  @Test
  void followThroughPreviewSameDirectionLeaderWithValidTokenDestinationAuthority() {
    assertEquals(
        SameDirectionFollowThroughDecision.WOULD_ALLOW_FOLLOW_THROUGH,
        SameDirectionFollowThroughPreview.evaluate(validSnapshot()));
  }

  @Test
  void followThroughPreviewHoldsWhenLeaderExitUnknown() {
    DispatchDecisionSnapshot snapshot =
        snapshot(true, false, false, false, false, true, true, true, false, true, true);

    assertEquals(
        SameDirectionFollowThroughDecision.WOULD_HOLD_FOR_LEADER_EXIT,
        SameDirectionFollowThroughPreview.evaluate(snapshot));
  }

  @Test
  void followThroughPreviewDenyWhenOppositeDirection() {
    DispatchDecisionSnapshot snapshot =
        snapshot(false, true, false, false, false, true, true, true, true, true, true);

    assertEquals(
        SameDirectionFollowThroughDecision.WOULD_DENY_UNSAFE,
        SameDirectionFollowThroughPreview.evaluate(snapshot));
  }

  @Test
  void followThroughPreviewDenyWhenOppositeSingleRegionOccupied() {
    DispatchDecisionSnapshot snapshot =
        snapshot(false, true, true, false, false, true, true, true, true, true, true);

    assertEquals(
        SameDirectionFollowThroughDecision.WOULD_DENY_UNSAFE,
        SameDirectionFollowThroughPreview.evaluate(snapshot));
  }

  @Test
  void sameDirectionFollowThroughPreviewDoesNotApplyToOppositeDirection() {
    DispatchDecisionSnapshot snapshot =
        snapshot(false, true, false, false, false, true, true, true, true, true, true);

    assertEquals(
        SameDirectionFollowThroughDecision.WOULD_DENY_UNSAFE,
        SameDirectionFollowThroughPreview.evaluate(snapshot));
  }

  @Test
  void followThroughPreviewUnknownWhenDirectionUnknownInSingleRegion() {
    DispatchDecisionSnapshot snapshot =
        snapshot(false, false, true, false, false, true, true, true, true, true, true);

    assertEquals(
        SameDirectionFollowThroughDecision.UNKNOWN_FAIL_SAFE,
        SameDirectionFollowThroughPreview.evaluate(snapshot));
  }

  @Test
  void followThroughPreviewDenyWhenExternalBlockerPresent() {
    DispatchDecisionSnapshot snapshot =
        snapshot(true, false, true, false, false, true, true, true, true, true, true);

    assertEquals(
        SameDirectionFollowThroughDecision.WOULD_DENY_UNSAFE,
        SameDirectionFollowThroughPreview.evaluate(snapshot));
  }

  @Test
  void followThroughPreviewUnknownWhenLeaderTokenInvalid() {
    DispatchDecisionSnapshot snapshot =
        new DispatchDecisionSnapshot(
            1L,
            1L,
            1L,
            0L,
            "follower",
            "A",
            "single:A~B",
            List.of("leader"),
            "external",
            "SAME_DIRECTION",
            SignalComputationTrace.TokenState.INVALID,
            true,
            true,
            SmartDispatcherMode.OBSERVE_ONLY,
            DispatchAction.SAME_DIRECTION_FOLLOW_THROUGH_PREVIEW,
            "leader",
            "single:A~B",
            true,
            false,
            false,
            false,
            false,
            true,
            true,
            true);

    assertEquals(
        SameDirectionFollowThroughDecision.UNKNOWN_FAIL_SAFE,
        SameDirectionFollowThroughPreview.evaluate(snapshot));
  }

  @Test
  void followThroughPreviewUnknownWhenLeaderDestinationMissing() {
    DispatchDecisionSnapshot snapshot =
        snapshot(true, false, false, false, false, true, false, true, true, true, true);

    assertEquals(
        SameDirectionFollowThroughDecision.UNKNOWN_FAIL_SAFE,
        SameDirectionFollowThroughPreview.evaluate(snapshot));
  }

  @Test
  void followThroughPreviewUnknownWhenAuthorityWindowMissing() {
    DispatchDecisionSnapshot snapshot =
        snapshot(true, false, false, false, false, true, true, false, true, true, true);

    assertEquals(
        SameDirectionFollowThroughDecision.UNKNOWN_FAIL_SAFE,
        SameDirectionFollowThroughPreview.evaluate(snapshot));
  }

  @Test
  void followThroughPreviewUnknownWhenSnapshotStale() {
    DispatchDecisionSnapshot snapshot =
        snapshot(true, false, false, false, false, true, true, true, true, true, false);

    assertEquals(
        SameDirectionFollowThroughDecision.UNKNOWN_FAIL_SAFE,
        SameDirectionFollowThroughPreview.evaluate(snapshot));
  }

  @Test
  void followThroughPreviewDenyWhenTurnback() {
    DispatchDecisionSnapshot snapshot =
        snapshot(true, false, false, false, true, true, true, true, true, true, true);

    assertEquals(
        SameDirectionFollowThroughDecision.WOULD_DENY_UNSAFE,
        SameDirectionFollowThroughPreview.evaluate(snapshot));
  }

  @Test
  void followThroughPreviewDenyWhenRepeatedEdge() {
    DispatchDecisionSnapshot snapshot =
        snapshot(true, false, false, true, false, true, true, true, true, true, true);

    assertEquals(
        SameDirectionFollowThroughDecision.WOULD_DENY_UNSAFE,
        SameDirectionFollowThroughPreview.evaluate(snapshot));
  }

  private static DispatchDecisionSnapshot validSnapshot() {
    return snapshot(true, false, false, false, false, true, true, true, true, true, true);
  }

  private static DispatchDecisionSnapshot snapshot(
      boolean sameDirection,
      boolean oppositeDirection,
      boolean externalBlocker,
      boolean repeatedEdge,
      boolean turnback,
      boolean activeToken,
      boolean destinationPresent,
      boolean authorityWindowPresent,
      boolean leaderExitVisible,
      boolean safeGapKnown,
      boolean fresh) {
    return new DispatchDecisionSnapshot(
        1L,
        1L,
        1L,
        fresh ? 0L : -1L,
        "follower",
        "A",
        "single:A~B",
        List.of("leader"),
        "external",
        sameDirection ? "SAME_DIRECTION" : oppositeDirection ? "OPPOSITE_DIRECTION" : "UNKNOWN",
        activeToken
            ? SignalComputationTrace.TokenState.ACTIVE
            : SignalComputationTrace.TokenState.INVALID,
        destinationPresent,
        authorityWindowPresent,
        SmartDispatcherMode.OBSERVE_ONLY,
        DispatchAction.SAME_DIRECTION_FOLLOW_THROUGH_PREVIEW,
        "leader",
        "single:A~B",
        sameDirection,
        oppositeDirection,
        externalBlocker,
        repeatedEdge,
        turnback,
        leaderExitVisible,
        safeGapKnown,
        safeGapKnown);
  }
}
