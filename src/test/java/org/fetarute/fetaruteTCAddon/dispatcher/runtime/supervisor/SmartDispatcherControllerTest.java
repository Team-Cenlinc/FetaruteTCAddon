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
  @DisplayName("protective-only blocker 不直接变成 STOP")
  void protectiveRetainBlockerClassifiedAsProtectiveOnly() {
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

    assertEquals(DispatchAction.RELEASE_STALE_RETAIN, decision.action());
    assertEquals(DispatchEffectClass.OCCUPANCY_MUTATION, decision.effectClass());
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
                "test"));

    assertEquals(DispatchAction.NO_ACTION, decision.action());
    assertEquals(SignalAspect.PROCEED, decision.targetAspect());
  }

  @Test
  @DisplayName("优先级评分按确定性规则选择 winner")
  void priorityWinnerIsDeterministic() {
    SmartDispatcherController controller = new SmartDispatcherController(message -> {});
    SmartDispatcherController.PrioritySelection selection =
        controller.selectPriorityWinner(
            List.of(
                new SmartDispatcherController.PriorityInput(
                    "train-B",
                    "single:X",
                    false,
                    OptionalLong.of(20),
                    false,
                    false,
                    2.0,
                    Duration.ofSeconds(30),
                    0,
                    false,
                    0,
                    true),
                new SmartDispatcherController.PriorityInput(
                    "train-A",
                    "single:X",
                    true,
                    OptionalLong.of(10),
                    true,
                    true,
                    1.0,
                    Duration.ofSeconds(5),
                    0,
                    false,
                    0,
                    true)));

    assertNotNull(selection.winner());
    assertEquals("train-A", selection.winner().trainId());
    assertEquals(1, selection.losers().size());
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
                Duration.ofMinutes(10),
                Duration.ofSeconds(60)));

    assertFalse(review.allowed());
    assertEquals("weak-blocker-diagnostic-only", review.reason());
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
        "test");
  }
}
