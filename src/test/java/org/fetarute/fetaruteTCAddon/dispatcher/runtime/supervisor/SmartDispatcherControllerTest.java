package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link SmartDispatcherController} 的确定性规则测试。 */
@DisplayName("SmartDispatcherController 单元测试")
class SmartDispatcherControllerTest {

  @Test
  @DisplayName("ARTIFICIAL_WINDOW_LIMIT 只输出诊断，不降级可见信号")
  void artificialWindowLimitDoesNotProduceCaution() {
    List<String> traces = new ArrayList<>();
    SmartDispatcherController controller = new SmartDispatcherController(traces::add);
    ForwardSignalRiskSnapshot risk =
        new ForwardSignalRiskSnapshot(
            "train-A",
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.of(128),
            RiskSource.ARTIFICIAL_WINDOW_LIMIT,
            RiskFreshness.UNKNOWN,
            "-",
            "window",
            false,
            false,
            true,
            false,
            false);

    DispatchDecision decision = controller.decideForwardSignal(input("train-A", risk));

    assertEquals(DispatchAction.NO_ACTION, decision.action());
    assertEquals(SignalAspect.PROCEED, decision.targetAspect());
    assertTrue(
        traces.stream()
            .anyMatch(line -> line.contains("SIGNAL_ARTIFICIAL_WINDOW_IGNORED_FOR_ASPECT")));
    assertTrue(traces.stream().anyMatch(line -> line.contains("SIGNAL_CAUTION_SKIPPED")));
  }

  @Test
  @DisplayName("SAME_DIRECTION_FOLLOW 只输出诊断，不降级可见信号")
  void sameDirectionFollowRiskDoesNotProduceCaution() {
    List<String> traces = new ArrayList<>();
    SmartDispatcherController controller = new SmartDispatcherController(traces::add);
    ForwardSignalRiskSnapshot risk =
        new ForwardSignalRiskSnapshot(
            "train-A",
            OptionalLong.empty(),
            OptionalLong.of(32),
            OptionalLong.of(32),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.of(32),
            OptionalLong.empty(),
            RiskSource.SAME_DIRECTION_FOLLOW,
            RiskFreshness.LIVE,
            "train-B",
            "switcher:SWITCHER:Towny:-566:77:1179",
            false,
            false,
            true,
            false,
            false);

    DispatchDecision decision = controller.decideForwardSignal(input("train-A", risk));

    assertEquals(DispatchAction.NO_ACTION, decision.action());
    assertEquals(SignalAspect.PROCEED, decision.targetAspect());
    assertEquals(RiskSource.SAME_DIRECTION_FOLLOW, decision.riskSource());
    assertTrue(
        traces.stream().anyMatch(line -> line.contains("reason=same-direction-follow-trace-only")));
  }

  @Test
  @DisplayName("规划窗口内的真实风险提前输出 PROCEED_WITH_CAUTION")
  void advisoryBlockerProducesCautionNotStop() {
    SmartDispatcherController controller = new SmartDispatcherController(message -> {});
    ForwardSignalRiskSnapshot risk =
        new ForwardSignalRiskSnapshot(
            "train-A",
            OptionalLong.empty(),
            OptionalLong.of(80),
            OptionalLong.of(80),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.of(80),
            RiskSource.MOVEMENT_AUTHORITY_PHYSICAL_END,
            RiskFreshness.LIVE,
            "-",
            "edge:A-B",
            false,
            false,
            true,
            false,
            false);

    DispatchDecision decision = controller.decideForwardSignal(input("train-A", risk));

    assertEquals(DispatchAction.PROCEED_WITH_CAUTION, decision.action());
    assertEquals(DispatchEffectClass.SIGNAL_ADVISORY, decision.effectClass());
    assertEquals(SignalAspect.PROCEED_WITH_CAUTION, decision.targetAspect());
    assertTrue(decision.targetSpeedBps() > 0.0);
    assertEquals(RiskSource.MOVEMENT_AUTHORITY_PHYSICAL_END, decision.riskSource());
  }

  @Test
  @DisplayName("贴近硬 blocker 时输出 STOP")
  void immediateHardBlockerProducesStop() {
    SmartDispatcherController controller = new SmartDispatcherController(message -> {});
    ForwardSignalRiskSnapshot risk =
        new ForwardSignalRiskSnapshot(
            "train-A",
            OptionalLong.of(2),
            OptionalLong.of(2),
            OptionalLong.of(2),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.of(2),
            RiskSource.HARD_BLOCKER,
            RiskFreshness.LIVE,
            "train-B",
            "NODE:B",
            false,
            false,
            true,
            false,
            false);

    DispatchDecision decision = controller.decideForwardSignal(input("train-A", risk));

    assertEquals(DispatchAction.HOLD_AT_SIGNAL, decision.action());
    assertEquals(DispatchEffectClass.SIGNAL_CONSTRAINT, decision.effectClass());
    assertEquals(SignalAspect.STOP, decision.targetAspect());
  }

  @Test
  @DisplayName("计划 RouteStop 进入制动距离时只给出减速建议")
  void plannedRouteStopProducesCautionInsteadOfImmediateStop() {
    SmartDispatcherController controller = new SmartDispatcherController(message -> {});
    ForwardSignalRiskSnapshot risk =
        new ForwardSignalRiskSnapshot(
            "train-A",
            OptionalLong.empty(),
            OptionalLong.of(18),
            OptionalLong.of(18),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.of(18),
            RiskSource.ROUTE_STOP_OR_TERMINAL,
            RiskFreshness.LIVE,
            "-",
            "NODE:OP:S:CENTRAL:1",
            false,
            false,
            true,
            false,
            false);

    DispatchDecision decision = controller.decideForwardSignal(routeStopInput(risk, true));

    assertEquals(DispatchAction.PROCEED_WITH_CAUTION, decision.action());
    assertEquals(DispatchEffectClass.SIGNAL_ADVISORY, decision.effectClass());
    assertEquals(SignalAspect.PROCEED_WITH_CAUTION, decision.targetAspect());
    assertEquals(6.0, decision.targetSpeedBps());
  }

  @Test
  @DisplayName("没有 RouteStop 证明的 route 末端必须保持停车")
  void routeStopWithoutPlanProofFailsClosed() {
    SmartDispatcherController controller = new SmartDispatcherController(message -> {});
    ForwardSignalRiskSnapshot risk =
        new ForwardSignalRiskSnapshot(
            "train-A",
            OptionalLong.empty(),
            OptionalLong.of(18),
            OptionalLong.of(18),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.of(18),
            RiskSource.ROUTE_STOP_OR_TERMINAL,
            RiskFreshness.LIVE,
            "-",
            "NODE:OP:S:CENTRAL:1",
            false,
            false,
            true,
            false,
            false);

    DispatchDecision decision = controller.decideForwardSignal(routeStopInput(risk, false));

    assertEquals(DispatchAction.HOLD_AT_SIGNAL, decision.action());
    assertEquals(SignalAspect.STOP, decision.targetAspect());
  }

  @Test
  @DisplayName("protective-only blocker 不发布无执行器的释放动作")
  void protectiveRetainBlockerDefersReleaseToCanonicalOccupancyChain() {
    SmartDispatcherController controller = new SmartDispatcherController(message -> {});
    ForwardSignalRiskSnapshot risk =
        new ForwardSignalRiskSnapshot(
            "train-A",
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            RiskSource.PROTECTIVE_ONLY_CLAIM,
            RiskFreshness.PROTECTIVE_ONLY,
            "train-B",
            "switcher:SW",
            false,
            true,
            true,
            true,
            false);

    DispatchDecision decision = controller.decideForwardSignal(input("train-A", risk));

    assertEquals(DispatchAction.NO_ACTION, decision.action());
    assertEquals(DispatchEffectClass.DIAGNOSTIC_ONLY, decision.effectClass());
    assertEquals("canonical-occupancy-recovery", decision.expectedUnblockEffect());
    assertNotEquals(SignalAspect.STOP, decision.targetAspect());
  }

  @Test
  @DisplayName("静止列车遇到远端 advisory 风险不产生黄灯")
  void noCautionWhenTrainStationaryAndRiskFar() {
    SmartDispatcherController controller = new SmartDispatcherController(message -> {});
    ForwardSignalRiskSnapshot risk =
        new ForwardSignalRiskSnapshot(
            "train-A",
            OptionalLong.empty(),
            OptionalLong.of(180),
            OptionalLong.of(180),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),
            OptionalLong.of(180),
            RiskSource.HARD_BLOCKER,
            RiskFreshness.LIVE,
            "train-B",
            "NODE:B",
            false,
            false,
            true,
            false,
            false);

    DispatchDecision decision =
        controller.decideForwardSignal(
            new SmartDispatcherController.ForwardDecisionInput(
                "train-A",
                risk,
                SignalAspect.PROCEED,
                0.0,
                12.0,
                5.0,
                1.0,
                256,
                8.0,
                24.0,
                false,
                "none",
                "test",
                false));

    assertEquals(DispatchAction.NO_ACTION, decision.action());
    assertEquals(SignalAspect.PROCEED, decision.targetAspect());
  }

  @Test
  @DisplayName("weak deadlock destroy 前置审查被拒绝")
  void weakDeadlockDestroyReviewIsRejected() {
    SmartDispatcherController controller = new SmartDispatcherController(message -> {});

    SmartDispatcherController.DeadlockDestroyReview review =
        controller.reviewDestroyCandidate(
            new SmartDispatcherController.DeadlockDestroyInput(
                "episode",
                "train-A",
                "train-B",
                "train-A",
                "weaker:train-a|train-b",
                true,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                true,
                false,
                "-",
                false,
                false,
                false,
                Duration.ofMinutes(10),
                Duration.ofSeconds(60)));

    assertFalse(review.allowed());
    assertEquals("weak-blocker-diagnostic-only", review.reason());
  }

  @Test
  @DisplayName("方向证据不足时拒绝快速 destroy")
  void directionAuditBlocksFastDestroyReview() {
    SmartDispatcherController controller = new SmartDispatcherController(message -> {});

    SmartDispatcherController.DeadlockDestroyReview review =
        controller.reviewDestroyCandidate(
            new SmartDispatcherController.DeadlockDestroyInput(
                "episode",
                "train-A",
                "train-B",
                "train-A",
                "single:test:A~B",
                false,
                true,
                false,
                false,
                false,
                false,
                true,
                false,
                true,
                true,
                "INSUFFICIENT_DIRECTION_EVIDENCE",
                true,
                false,
                true,
                Duration.ofMinutes(10),
                Duration.ofSeconds(60)));

    assertFalse(review.allowed());
    assertEquals("direction-audit-required", review.reason());
  }

  @Test
  @DisplayName("方向复审后 last-resort destroy 仍要求活跃交通阻塞证明")
  void directionAuditLastResortDestroyRequiresActiveTrafficProof() {
    SmartDispatcherController controller = new SmartDispatcherController(message -> {});

    SmartDispatcherController.DeadlockDestroyReview review =
        controller.reviewDestroyCandidate(
            new SmartDispatcherController.DeadlockDestroyInput(
                "episode",
                "train-A",
                "train-B",
                "train-A",
                "single:test:A~B",
                false,
                true,
                false,
                false,
                false,
                false,
                true,
                false,
                true,
                true,
                "NEED_DIRECTION_AUDIT",
                true,
                true,
                false,
                Duration.ofMinutes(10),
                Duration.ofSeconds(60)));

    assertFalse(review.allowed());
    assertEquals("direction-audit-active-traffic-not-proven", review.reason());
  }

  @Test
  @DisplayName("方向复审后 last-resort destroy 可通过最终审查")
  void directionAuditLastResortDestroyCanPassWithActiveTrafficProof() {
    SmartDispatcherController controller = new SmartDispatcherController(message -> {});

    SmartDispatcherController.DeadlockDestroyReview review =
        controller.reviewDestroyCandidate(
            new SmartDispatcherController.DeadlockDestroyInput(
                "episode",
                "train-A",
                "train-B",
                "train-A",
                "single:test:A~B",
                false,
                true,
                false,
                false,
                false,
                false,
                true,
                false,
                true,
                true,
                "NEED_DIRECTION_AUDIT",
                true,
                true,
                true,
                Duration.ofMinutes(10),
                Duration.ofSeconds(60)));

    assertTrue(review.allowed());
    assertEquals("confirmed-live-hard-cycle-last-resort-direction-audit", review.reason());
  }

  @Test
  @DisplayName("恢复耗尽且长期无 blocker 的空车可通过 stuck cleanup 审查")
  void stuckCleanupAllowsVerifiedEmptyTrain() {
    SmartDispatcherController controller = new SmartDispatcherController(message -> {});

    SmartDispatcherController.StuckCleanupReview review =
        controller.reviewStuckCleanupCandidate(
            new SmartDispatcherController.StuckCleanupInput(
                "train-A",
                true,
                true,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                Duration.ofMinutes(11),
                Duration.ofMinutes(10),
                Duration.ofMinutes(30)));

    assertTrue(review.allowed());
    assertEquals("verified-long-stuck-cleanup", review.reason());
    assertTrue(review.requiresPostVerification());
  }

  @Test
  @DisplayName("载客列车必须等到更长保护阈值")
  void stuckCleanupRejectsPassengerBeforeProtectedThreshold() {
    SmartDispatcherController controller = new SmartDispatcherController(message -> {});

    SmartDispatcherController.StuckCleanupReview review =
        controller.reviewStuckCleanupCandidate(
            new SmartDispatcherController.StuckCleanupInput(
                "train-A",
                true,
                true,
                false,
                true,
                false,
                false,
                false,
                false,
                true,
                Duration.ofMinutes(20),
                Duration.ofMinutes(10),
                Duration.ofMinutes(30)));

    assertFalse(review.allowed());
    assertEquals("passenger-grace", review.reason());
  }

  @Test
  @DisplayName("正在等新鲜 blocker 的列车不能由通用 cleanup 删除")
  void stuckCleanupRejectsLiveQueueWaiter() {
    SmartDispatcherController controller = new SmartDispatcherController(message -> {});

    SmartDispatcherController.StuckCleanupReview review =
        controller.reviewStuckCleanupCandidate(
            new SmartDispatcherController.StuckCleanupInput(
                "train-A",
                true,
                true,
                false,
                true,
                false,
                false,
                true,
                false,
                false,
                Duration.ofHours(1),
                Duration.ofMinutes(10),
                Duration.ofMinutes(30)));

    assertFalse(review.allowed());
    assertEquals("waiting-on-live-blocker", review.reason());
  }

  @Test
  @DisplayName("无效 cleanup 阈值不能把全真安全标志升级为销毁授权")
  void stuckCleanupRejectsInvalidThresholds() {
    SmartDispatcherController controller = new SmartDispatcherController(message -> {});

    SmartDispatcherController.StuckCleanupReview missingThreshold =
        controller.reviewStuckCleanupCandidate(
            new SmartDispatcherController.StuckCleanupInput(
                "train-A",
                true,
                true,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                Duration.ofHours(1),
                null,
                Duration.ofMinutes(30)));
    SmartDispatcherController.StuckCleanupReview negativePassengerThreshold =
        controller.reviewStuckCleanupCandidate(
            new SmartDispatcherController.StuckCleanupInput(
                "train-A",
                true,
                true,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                Duration.ofHours(1),
                Duration.ofMinutes(10),
                Duration.ofSeconds(-1)));

    assertFalse(missingThreshold.allowed());
    assertEquals("cleanup-threshold-invalid", missingThreshold.reason());
    assertFalse(negativePassengerThreshold.allowed());
    assertEquals("cleanup-threshold-invalid", negativePassengerThreshold.reason());
  }

  private static SmartDispatcherController.ForwardDecisionInput input(
      String trainId, ForwardSignalRiskSnapshot risk) {
    return new SmartDispatcherController.ForwardDecisionInput(
        trainId,
        risk,
        SignalAspect.PROCEED,
        8.0,
        12.0,
        5.0,
        1.0,
        256,
        8.0,
        24.0,
        false,
        "none",
        "test",
        false);
  }

  private static SmartDispatcherController.ForwardDecisionInput routeStopInput(
      ForwardSignalRiskSnapshot risk, boolean plannedRouteStopProven) {
    return new SmartDispatcherController.ForwardDecisionInput(
        "train-A",
        risk,
        SignalAspect.PROCEED,
        10.0,
        10.0,
        6.0,
        1.0,
        256,
        8.0,
        24.0,
        false,
        "none",
        "ROUTE_STOP_OR_TERMINAL",
        plannedRouteStopProven);
  }
}
