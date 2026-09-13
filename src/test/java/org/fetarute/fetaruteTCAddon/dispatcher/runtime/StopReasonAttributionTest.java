package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

/**
 * 停因明细必须说人话的回归。
 *
 * <p>背景是一个结构性问题，不是拼写问题：{@code SMART_STOP_LIFECYCLE} 属于诊断门的**必留事务审计**， 而承载原因的那些 trace（{@code
 * SMART_POTENTIAL_PHYSICAL_CHANGE_CONTAINED}、 {@code STALE_PROTECTIVE_RETAIN_CANDIDATE}、{@code
 * HealthMonitor reapplyHardStop} …）受普通观察预算门控。 实服 2026-09-13 丢弃率 **89%**：停车必然留痕，原因必然丢失。
 *
 * <p>于是那一轮里 **122 次 {@code invalidatesAuthority=true} 的硬停车无法归因**—— 95 次 {@code
 * DEADLOCK_CONFIRMED_WAITING detail=deadlock_confirmed_waiting}、 27 次 {@code
 * SAFETY_STATE_UNAVAILABLE detail=safety_state_unavailable}， 外加 67 次 hold 写着 {@code
 * detail=none}。这些明细都不是原因，是**停因代码的复述**或**记录字段的默认值**， 长得却和真实明细（{@code
 * safety-state-unavailable:startup-occupancy-hydrating}）几乎一样。
 *
 * <p>本用例钉住的规则：**明细要么是原因，要么自报"我没有原因"，绝不许伪装成结论。**
 */
class StopReasonAttributionTest {

  private static final Instant NOW = Instant.parse("2026-09-13T15:30:00Z");

  /** 没有 decision 时，明细不得复述停因代码。 */
  @Test
  void hardStopWithoutDecisionDeclaresTheGapInsteadOfEchoingTheReasonCode() {
    RuntimeStopState state =
        RuntimeStopState.hardStop(
            "t", HardStopReason.SAFETY_STATE_UNAVAILABLE, (OccupancyDecision) null, NOW);

    assertFalse(
        state.detail().equals(state.reasonCode().toLowerCase(Locale.ROOT)),
        "明细复述停因代码等于没写；它会被误读成真实原因");
    assertTrue(state.detail().contains("no-decision-context"), state.detail());
  }

  /** 调用方给得出原因时必须原样采用——这是三个实服停因路径现在走的分支。 */
  @Test
  void hardStopUsesExplicitDetail() {
    RuntimeStopState state =
        RuntimeStopState.hardStop(
            "t",
            HardStopReason.SAFETY_STATE_UNAVAILABLE,
            "potential-physical-change-contained:group-unload",
            NOW);

    assertEquals("potential-physical-change-contained:group-unload", state.detail());
  }

  /** 明细会写进空格分隔的 trace；内部空白必须折叠，否则后续字段全部错位。 */
  @Test
  void detailIsCollapsedIntoASingleToken() {
    RuntimeStopState state =
        RuntimeStopState.hardStop("t", HardStopReason.SAFETY_STATE_UNAVAILABLE, "a b\tc", NOW);

    assertFalse(state.detail().matches(".*\\s.*"), "明细里不得留空白：" + state.detail());
    assertEquals("a_b_c", state.detail());
  }

  /** {@code OccupancyDecision.reason()} 的默认字面量 "none" 表示"没人填过"，不得当结论印出去。 */
  @Test
  void occupancyHoldDoesNotPrintTheDecisionDefaultAsIfItWereAReason() {
    RuntimeStopState state =
        RuntimeStopState.occupancyHold(
            "t", "PROTECTIVE_RETAIN_HOLD", decisionWithReason("none"), NOW);

    assertFalse("none".equals(state.detail()), "\"none\" 是记录字段的默认值，不是原因");
    assertTrue(state.detail().contains("no-decision-reason"), state.detail());
  }

  /** 调用方显式给的明细优先于 decision 自带的原因。 */
  @Test
  void explicitDetailWinsOverDecisionReason() {
    RuntimeStopState state =
        RuntimeStopState.occupancyHold(
            "t",
            "PROTECTIVE_RETAIN_HOLD",
            decisionWithReason("none"),
            "protective-retain:release-candidate",
            NOW);

    assertEquals("protective-retain:release-candidate", state.detail());
  }

  /** 收紧不得波及正常路径：decision 自带真实原因时必须原样保留。 */
  @Test
  void realDecisionReasonIsPreserved() {
    RuntimeStopState state =
        RuntimeStopState.occupancyHold(
            "t",
            "BLOCKED_BY_OCCUPANCY",
            decisionWithReason("self-owned-single-continuation-rejected"),
            NOW);

    assertEquals("self-owned-single-continuation-rejected", state.detail());
  }

  /** decision 整个缺失与"decision 在但没写原因"是两回事，明细必须分得开。 */
  @Test
  void missingDecisionAndReasonlessDecisionAreDistinguishable() {
    String missing =
        RuntimeStopState.occupancyHold("t", "BLOCKED_BY_OCCUPANCY", null, NOW).detail();
    String reasonless =
        RuntimeStopState.occupancyHold("t", "BLOCKED_BY_OCCUPANCY", decisionWithReason("none"), NOW)
            .detail();

    assertFalse(missing.equals(reasonless), "两种空缺必须可区分：" + missing);
  }

  private static OccupancyDecision decisionWithReason(String reason) {
    return new OccupancyDecision(false, NOW, SignalAspect.STOP, List.of(), false, reason);
  }
}
