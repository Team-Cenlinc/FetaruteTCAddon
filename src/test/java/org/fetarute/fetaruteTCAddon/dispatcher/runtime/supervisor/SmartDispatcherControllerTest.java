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

  @Test
  @DisplayName("远处的站台/终点不得降速：规划视野不是减速判据")
  void distantRouteStopDoesNotTriggerCaution() {
    SmartDispatcherController controller = new SmartDispatcherController(message -> {});
    // 实服 2026-09-13：WS 车在距站台 385 blocks 处就被压成黄灯。
    //
    // 这里取 200：它必须<b>同时</b>落在规划视野内（本用例 256）与减速所需距离之外，才能把
    // "只因为看得见就降速"和"看不见所以不降速"区分开。取 385 会超出视野，新旧实现都不降速，
    // 用例就成了空的——第一版正是这么写的。以本用例参数（10 bps 接近、6 bps caution 速度、
    // 减速度 1.0），从 10 减到 6 只需 (100-36)/2 = 32 blocks，加 24 裕量共 56。
    // 实测：旧实现在 40/100/200/250 全部降速，新实现只在 40 降速。
    // 以本用例的参数（10 bps 接近、6 bps caution 速度、减速度 1.0），从 10 减到 6 只需
    // (100-36)/2 = 32 blocks，加上 24 的裕量也只要 56。385 是它的近七倍。
    DispatchDecision decision =
        controller.decideForwardSignal(routeStopInput(routeStopRisk(200), true));

    assertEquals(DispatchAction.NO_ACTION, decision.action());
    assertEquals(SignalAspect.PROCEED, decision.targetAspect());
  }

  @Test
  @DisplayName("进入减速距离后必须降速")
  void routeStopInsideBrakingDistanceTriggersCaution() {
    SmartDispatcherController controller = new SmartDispatcherController(message -> {});

    DispatchDecision decision =
        controller.decideForwardSignal(routeStopInput(routeStopRisk(40), true));

    assertEquals(DispatchAction.PROCEED_WITH_CAUTION, decision.action());
    assertEquals(SignalAspect.PROCEED_WITH_CAUTION, decision.targetAspect());
  }

  @Test
  @DisplayName("降速生效之后不得自行释放——否则黄灯会反复闪")
  void cautionDoesNotReleaseOnceTheTrainHasSlowedDown() {
    SmartDispatcherController controller = new SmartDispatcherController(message -> {});
    // 列车已经按 caution 降到 6 bps，但线路允许速度仍是 10：一旦释放它就会重新加速。
    // 判定若用**瞬时**速度算制动距离，此时 (36-36)/2 = 0，阈值塌成 24，35 > 24 便会释放，
    // 于是"降速->释放->加速->再降速"在二十几 blocks 的带里反复，表现为黄灯反复闪。
    // 判定改用"不减速会达到的速度"（max(当前, 允许)）之后，阈值稳定在 56，不会释放。
    SmartDispatcherController.ForwardDecisionInput slowedDown =
        new SmartDispatcherController.ForwardDecisionInput(
            "train-A",
            routeStopRisk(35),
            SignalAspect.PROCEED_WITH_CAUTION,
            6.0,
            10.0,
            6.0,
            1.0,
            256,
            8.0,
            24.0,
            false,
            "none",
            "ROUTE_STOP_OR_TERMINAL",
            true);

    DispatchDecision decision = controller.decideForwardSignal(slowedDown);

    // 断言 action 而不是 targetAspect：NO_ACTION 会把 currentAspect 原样带出，
    // 而本用例的 currentAspect 恰好就是 PROCEED_WITH_CAUTION——断 aspect 会永远成立。
    assertEquals(
        DispatchAction.PROCEED_WITH_CAUTION, decision.action(), "列车已降到 caution 速度后判定被释放，会造成黄灯反复切换");
  }

  private static ForwardSignalRiskSnapshot routeStopRisk(long distance) {
    return new ForwardSignalRiskSnapshot(
        "train-A",
        OptionalLong.empty(),
        OptionalLong.of(distance),
        OptionalLong.of(distance),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.of(distance),
        RiskSource.ROUTE_STOP_OR_TERMINAL,
        RiskFreshness.LIVE,
        "-",
        "edge:A-B",
        false,
        false,
        true,
        false,
        false);
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
