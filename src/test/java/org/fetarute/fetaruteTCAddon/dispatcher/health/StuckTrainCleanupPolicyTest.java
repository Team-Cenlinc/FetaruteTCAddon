package org.fetarute.fetaruteTCAddon.dispatcher.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

/** 长时间停滞列车清理策略测试。 */
class StuckTrainCleanupPolicyTest {

  private static final Duration EMPTY_THRESHOLD = Duration.ofMinutes(10);
  private static final Duration PASSENGER_THRESHOLD = Duration.ofMinutes(30);

  @Test
  void emptyTrainIsSelectedBeforePassengerTrain() {
    StuckTrainCleanupPolicy.Candidate passenger =
        candidate("passenger", RouteOperationType.RETURN, true, Duration.ofMinutes(40));
    StuckTrainCleanupPolicy.Candidate empty =
        candidate("empty", RouteOperationType.OPERATION, false, Duration.ofMinutes(11));

    StuckTrainCleanupPolicy.Candidate selected =
        StuckTrainCleanupPolicy.select(
                List.of(passenger, empty), EMPTY_THRESHOLD, PASSENGER_THRESHOLD)
            .orElseThrow();

    assertEquals("empty", selected.context().trainName());
  }

  @Test
  void passengerTrainGetsLongerGraceButRemainsLastResortEligible() {
    StuckTrainCleanupPolicy.Candidate passengerBeforeGrace =
        candidate("passenger", RouteOperationType.OPERATION, true, Duration.ofMinutes(20));
    StuckTrainCleanupPolicy.Candidate passengerAfterGrace =
        candidate("passenger", RouteOperationType.OPERATION, true, Duration.ofMinutes(31));

    assertEquals(
        StuckTrainCleanupPolicy.Eligibility.PASSENGER_GRACE,
        StuckTrainCleanupPolicy.eligibility(
            passengerBeforeGrace, EMPTY_THRESHOLD, PASSENGER_THRESHOLD));
    assertTrue(
        StuckTrainCleanupPolicy.select(
                List.of(passengerAfterGrace), EMPTY_THRESHOLD, PASSENGER_THRESHOLD)
            .isPresent());
  }

  @Test
  void liveBlockerAndControlledStopAreNeverGenericCleanupCandidates() {
    StuckTrainCleanupPolicy.Candidate waiting =
        new StuckTrainCleanupPolicy.Candidate(
            context("waiting", RouteOperationType.OPERATION, false, false, false, false, false),
            Duration.ofHours(1),
            true,
            true);
    StuckTrainCleanupPolicy.Candidate dwelling =
        new StuckTrainCleanupPolicy.Candidate(
            context("dwelling", RouteOperationType.OPERATION, false, true, false, false, false),
            Duration.ofHours(1),
            true,
            false);

    assertEquals(
        StuckTrainCleanupPolicy.Eligibility.WAITING_ON_LIVE_BLOCKER,
        StuckTrainCleanupPolicy.eligibility(waiting, EMPTY_THRESHOLD, PASSENGER_THRESHOLD));
    assertEquals(
        StuckTrainCleanupPolicy.Eligibility.CONTROLLED_STOP,
        StuckTrainCleanupPolicy.eligibility(dwelling, EMPTY_THRESHOLD, PASSENGER_THRESHOLD));
  }

  @Test
  void movingCorridorOwnerKeepsAuthorityInsteadOfBecomingCleanupCandidate() {
    RuntimeDispatchService.DeadlockTrainContext movingContext =
        new RuntimeDispatchService.DeadlockTrainContext(
            "moving-owner",
            3,
            10,
            SignalAspect.PROCEED,
            0.05,
            RouteOperationType.OPERATION,
            20,
            false,
            false,
            false,
            true,
            false,
            false,
            false);
    StuckTrainCleanupPolicy.Candidate moving =
        new StuckTrainCleanupPolicy.Candidate(movingContext, Duration.ofHours(1), true, false);

    assertEquals(
        StuckTrainCleanupPolicy.Eligibility.TRAIN_MOVING,
        StuckTrainCleanupPolicy.eligibility(moving, EMPTY_THRESHOLD, PASSENGER_THRESHOLD));
  }

  @Test
  void returnAndDepotTrainsArePreferredAmongEquivalentEmptyCandidates() {
    StuckTrainCleanupPolicy.Candidate ordinary =
        candidate("ordinary", RouteOperationType.OPERATION, false, Duration.ofMinutes(11));
    StuckTrainCleanupPolicy.Candidate returningDepot =
        new StuckTrainCleanupPolicy.Candidate(
            context("returning", RouteOperationType.RETURN, false, false, false, false, true),
            Duration.ofMinutes(11),
            true,
            false);

    StuckTrainCleanupPolicy.Candidate selected =
        StuckTrainCleanupPolicy.select(
                List.of(ordinary, returningDepot), EMPTY_THRESHOLD, PASSENGER_THRESHOLD)
            .orElseThrow();

    assertEquals("returning", selected.context().trainName());
  }

  @Test
  void recoveryMustBeExhaustedBeforeCleanup() {
    StuckTrainCleanupPolicy.Candidate candidate =
        new StuckTrainCleanupPolicy.Candidate(
            context("recovering", RouteOperationType.OPERATION, false, false, false, false, false),
            Duration.ofHours(1),
            false,
            false);

    assertEquals(
        StuckTrainCleanupPolicy.Eligibility.RECOVERY_NOT_EXHAUSTED,
        StuckTrainCleanupPolicy.eligibility(candidate, EMPTY_THRESHOLD, PASSENGER_THRESHOLD));
  }

  private static StuckTrainCleanupPolicy.Candidate candidate(
      String trainName,
      RouteOperationType operationType,
      boolean passengers,
      Duration stuckDuration) {
    return new StuckTrainCleanupPolicy.Candidate(
        context(trainName, operationType, passengers, false, false, false, false),
        stuckDuration,
        true,
        false);
  }

  private static RuntimeDispatchService.DeadlockTrainContext context(
      String trainName,
      RouteOperationType operationType,
      boolean passengers,
      boolean dwelling,
      boolean departureGateHeld,
      boolean layoverReady,
      boolean depotRelated) {
    return new RuntimeDispatchService.DeadlockTrainContext(
        trainName,
        3,
        10,
        SignalAspect.STOP,
        0.0,
        operationType,
        20,
        dwelling,
        departureGateHeld,
        layoverReady,
        depotRelated,
        false,
        passengers,
        false);
  }
}
