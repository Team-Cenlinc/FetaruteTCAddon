package org.fetarute.fetaruteTCAddon.dispatcher.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Set;
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

  /** 三列停滞车首尾相接地互等：谁也等不到，按清理顺序清环上排第一的那列（同等条件下按名字）。 */
  @Test
  void aCycleOfStuckTrainsLosesOneTrain() {
    StuckTrainCleanupPolicy.WaitCycle cycle =
        StuckTrainCleanupPolicy.waitCycleTargets(
                List.of(waiting("b", "c"), waiting("c", "a"), waiting("a", "b")),
                EMPTY_THRESHOLD,
                PASSENGER_THRESHOLD)
            .get(0);

    assertEquals("a", cycle.target().context().trainName());
    assertEquals(List.of("a", "b", "c"), cycle.members());
  }

  /** 复审拒绝了第一列时健康监控接着试下一列：环上每一列可清车都按清理顺序给出。 */
  @Test
  void everyCleanableTrainOnTheCycleIsOfferedInOrder() {
    assertEquals(
        List.of("a", "b", "c"),
        StuckTrainCleanupPolicy.waitCycleTargets(
                List.of(waiting("c", "a"), waiting("a", "b"), waiting("b", "c")),
                EMPTY_THRESHOLD,
                PASSENGER_THRESHOLD)
            .stream()
            .map(cycle -> cycle.target().context().trainName())
            .toList());
  }

  /** 拆分别名（{@code ~a}）与运行时复审同一口径：等的是 {@code b~a} 也算在等 b。 */
  @Test
  void splitAliasBlockersCountAsTheTrainItself() {
    assertEquals(
        List.of("a", "b"),
        StuckTrainCleanupPolicy.waitCycleTargets(
                List.of(waiting("a", "B~a"), waiting("b", "a")),
                EMPTY_THRESHOLD,
                PASSENGER_THRESHOLD)
            .get(0)
            .members());
  }

  /** 两列互等同样成环：互卡处理只认同一单线冲突上的对向配对，别的两车互等由这里兜底。 */
  @Test
  void twoTrainsWaitingOnEachOtherFormACycle() {
    assertEquals(
        List.of("x", "y"),
        StuckTrainCleanupPolicy.waitCycleTargets(
                List.of(waiting("x", "y"), waiting("y", "x")), EMPTY_THRESHOLD, PASSENGER_THRESHOLD)
            .get(0)
            .members());
  }

  /** 链尾在等候选集之外的车（残留占用、待命车、仍在运行的车）：源头不在这里，整条链都不动。 */
  @Test
  void aChainEndingOutsideTheStuckTrainsIsLeftAlone() {
    assertTrue(
        StuckTrainCleanupPolicy.waitCycleTargets(
                List.of(waiting("a", "b"), waiting("b", "parked")),
                EMPTY_THRESHOLD,
                PASSENGER_THRESHOLD)
            .isEmpty());
  }

  /** 环的尾巴（等着环上的车、自己不在环上）不清：清掉它解不开环，只清环上的车。 */
  @Test
  void theTailOfACycleIsNeverTheTarget() {
    StuckTrainCleanupPolicy.WaitCycle cycle =
        StuckTrainCleanupPolicy.waitCycleTargets(
                List.of(waiting("a", "m"), waiting("m", "n"), waiting("n", "m")),
                EMPTY_THRESHOLD,
                PASSENGER_THRESHOLD)
            .get(0);

    assertEquals("m", cycle.target().context().trainName());
    assertEquals(List.of("m", "n"), cycle.members());
  }

  /** 环上有一列载客车还在宽限期内：它不清，但仍是环上的一环——清掉环上的空车，载客车就能走。 */
  @Test
  void anEmptyTrainInACycleWithAPassengerTrainIsCleanedFirst() {
    StuckTrainCleanupPolicy.Candidate passenger =
        new StuckTrainCleanupPolicy.Candidate(
            context("p", RouteOperationType.OPERATION, true, false, false, false, false),
            Duration.ofMinutes(20),
            true,
            true,
            Set.of("q"));

    StuckTrainCleanupPolicy.WaitCycle cycle =
        StuckTrainCleanupPolicy.waitCycleTargets(
                List.of(passenger, waiting("q", "p")), EMPTY_THRESHOLD, PASSENGER_THRESHOLD)
            .get(0);

    assertEquals("q", cycle.target().context().trainName());
    assertEquals(List.of("p", "q"), cycle.members());
  }

  /** 环上的 x 同时还在等环外的待命车：x 的等待不全由环解释，清它运行时复审也不放行；但环仍是死结，清只等环上车的 y 就能解开—— 虽然按名字 x 排在 y 前面。 */
  @Test
  void aCycleMemberThatAlsoWaitsElsewhereIsSkippedButTheCycleIsStillBroken() {
    StuckTrainCleanupPolicy.Candidate x =
        new StuckTrainCleanupPolicy.Candidate(
            context("x", RouteOperationType.OPERATION, false, false, false, false, false),
            Duration.ofMinutes(11),
            true,
            true,
            Set.of("y", "parked"));

    StuckTrainCleanupPolicy.WaitCycle cycle =
        StuckTrainCleanupPolicy.waitCycleTargets(
                List.of(x, waiting("y", "x")), EMPTY_THRESHOLD, PASSENGER_THRESHOLD)
            .get(0);

    assertEquals("y", cycle.target().context().trainName());
    assertEquals(List.of("x", "y"), cycle.members());
  }

  /** 环上恢复还没耗尽的车仍是环上一环，但轮不到它被清：按名字它排在前面，清的仍是恢复已耗尽的那列。 */
  @Test
  void aTrainStillInRecoveryIsNotTheTarget() {
    StuckTrainCleanupPolicy.Candidate recovering =
        new StuckTrainCleanupPolicy.Candidate(
            context("a", RouteOperationType.OPERATION, false, false, false, false, false),
            Duration.ofMinutes(11),
            false,
            true,
            Set.of("b"));

    StuckTrainCleanupPolicy.WaitCycle cycle =
        StuckTrainCleanupPolicy.waitCycleTargets(
                List.of(recovering, waiting("b", "a")), EMPTY_THRESHOLD, PASSENGER_THRESHOLD)
            .get(0);

    assertEquals("b", cycle.target().context().trainName());
  }

  /** 环上全是载客宽限期内的车：成环也不清，载客车只在宽限期满后才是最后手段。 */
  @Test
  void aCycleOfPassengerTrainsInGraceIsLeftAlone() {
    StuckTrainCleanupPolicy.Candidate first =
        new StuckTrainCleanupPolicy.Candidate(
            context("p1", RouteOperationType.OPERATION, true, false, false, false, false),
            Duration.ofMinutes(20),
            true,
            true,
            Set.of("p2"));
    StuckTrainCleanupPolicy.Candidate second =
        new StuckTrainCleanupPolicy.Candidate(
            context("p2", RouteOperationType.OPERATION, true, false, false, false, false),
            Duration.ofMinutes(20),
            true,
            true,
            Set.of("p1"));

    assertTrue(
        StuckTrainCleanupPolicy.waitCycleTargets(
                List.of(first, second), EMPTY_THRESHOLD, PASSENGER_THRESHOLD)
            .isEmpty());
  }

  /** 环上有一列还在动：那不是死结（它可能正在让出资源），不当成环。 */
  @Test
  void aMovingTrainBreaksTheCycle() {
    RuntimeDispatchService.DeadlockTrainContext movingContext =
        new RuntimeDispatchService.DeadlockTrainContext(
            "x",
            3,
            10,
            SignalAspect.STOP,
            0.05,
            RouteOperationType.OPERATION,
            20,
            false,
            false,
            false,
            false,
            false,
            false,
            false);
    StuckTrainCleanupPolicy.Candidate moving =
        new StuckTrainCleanupPolicy.Candidate(
            movingContext, Duration.ofMinutes(11), true, true, Set.of("y"));

    assertTrue(
        StuckTrainCleanupPolicy.waitCycleTargets(
                List.of(moving, waiting("y", "x")), EMPTY_THRESHOLD, PASSENGER_THRESHOLD)
            .isEmpty());
  }

  /** 只在排队、没记下在等谁的车说不清源头，不进等待环。 */
  @Test
  void aQueuedTrainWithoutKnownBlockersIsNotPartOfACycle() {
    StuckTrainCleanupPolicy.Candidate queued =
        new StuckTrainCleanupPolicy.Candidate(
            context("q", RouteOperationType.OPERATION, false, false, false, false, false),
            Duration.ofHours(1),
            true,
            true,
            Set.of());

    assertTrue(
        StuckTrainCleanupPolicy.waitCycleTargets(
                List.of(queued, waiting("r", "q")), EMPTY_THRESHOLD, PASSENGER_THRESHOLD)
            .isEmpty());
  }

  /** 停满阈值、恢复已耗尽、排队等 {@code blocker} 的空车。 */
  private static StuckTrainCleanupPolicy.Candidate waiting(String trainName, String blocker) {
    return new StuckTrainCleanupPolicy.Candidate(
        context(trainName, RouteOperationType.OPERATION, false, false, false, false, false),
        Duration.ofMinutes(11),
        true,
        true,
        Set.of(blocker));
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
