package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 观察预算必须可调。
 *
 * <p>预算原本硬编码在 {@code RuntimeDispatchDiagnosticGate} 里（120 条/分钟）。实服 2026-09-13 一轮 42 分钟 丢弃 305,203
 * 行、写出 36,229 行——**89% 被丢**，连只在异常时出现的 trace 都一条没留下。 于是「日志里没有
 * X」既可能是"没发生"也可能是"被丢了"，**两者无法区分**，排查直接停摆。
 *
 * <p>我曾建议用户"把诊断预算调高"——**当时根本没有这个开关**，那条建议无法执行。本用例钉住它现在有了。
 */
class DiagnosticBudgetConfigurableTest {

  /** 观察类 trace 超出预算即被丢弃——这是默认预算下的既有行为，先确认它确实在起作用。 */
  @Test
  void observationTracesAreDroppedOnceTheBudgetIsSpent() {
    List<String> out = new ArrayList<>();
    RuntimeDispatchDiagnosticGate gate = new RuntimeDispatchDiagnosticGate(out::add, 3);

    for (int i = 0; i < 40; i++) {
      gate.accept("SMART_DISPATCH_DECISION train=t" + i + " action=NO_ACTION");
    }

    long decisions =
        out.stream().filter(line -> line.startsWith("SMART_DISPATCH_DECISION")).count();
    assertEquals(3, decisions, "超出预算的观察 trace 必须被丢弃：" + out);
  }

  /** 调高预算必须真的放行更多——否则这个开关是假的。 */
  @Test
  void raisingTheBudgetLetsMoreObservationTracesThrough() {
    List<String> low = new ArrayList<>();
    List<String> high = new ArrayList<>();
    RuntimeDispatchDiagnosticGate lowGate = new RuntimeDispatchDiagnosticGate(low::add, 3);
    RuntimeDispatchDiagnosticGate highGate = new RuntimeDispatchDiagnosticGate(high::add, 30);

    for (int i = 0; i < 40; i++) {
      String line = "SMART_DISPATCH_DECISION train=t" + i + " action=NO_ACTION";
      lowGate.accept(line);
      highGate.accept(line);
    }

    long lowCount = low.stream().filter(l -> l.startsWith("SMART_DISPATCH_DECISION")).count();
    long highCount = high.stream().filter(l -> l.startsWith("SMART_DISPATCH_DECISION")).count();
    assertEquals(3, lowCount);
    assertEquals(30, highCount, "调高预算必须放行更多观察 trace");
  }

  /** 非正值必须回落到默认，不能把日志关死或除零。 */
  @Test
  void nonPositiveBudgetFallsBackToDefault() {
    List<String> out = new ArrayList<>();
    RuntimeDispatchDiagnosticGate gate = new RuntimeDispatchDiagnosticGate(out::add, 0);

    for (int i = 0; i < 200; i++) {
      gate.accept("SMART_DISPATCH_DECISION train=t" + i + " action=NO_ACTION");
    }

    long decisions = out.stream().filter(l -> l.startsWith("SMART_DISPATCH_DECISION")).count();
    assertEquals(120, decisions, "非正值必须回落到默认 120，而不是 0");
  }

  /**
   * 恢复层唯一的执行证据必须不受预算约束。
   *
   * <p>实服 2026-09-13 需要回答的问题是"恢复层到底动没动"，唯一能回答它的就是这条 trace。 它此前不在必留名单里，而其余每一条 unlock
   * 事务边界都在——名单漏了最关键的那条。
   */
  @Test
  void recoveryLayerEffectEvidenceIsNeverBudgetDropped() {
    List<String> out = new ArrayList<>();
    RuntimeDispatchDiagnosticGate gate = new RuntimeDispatchDiagnosticGate(out::add, 1);

    for (int i = 0; i < 30; i++) {
      gate.accept(
          "SMART_UNLOCK_PRIORITY_INTENT_APPLIED train=t"
              + i
              + " reservationId=r"
              + i
              + " effectivePriority=240");
    }

    long applied =
        out.stream().filter(l -> l.startsWith("SMART_UNLOCK_PRIORITY_INTENT_APPLIED")).count();
    assertEquals(30, applied, "恢复层执行证据不得被预算吞掉：" + out);
  }

  /**
   * 终点站 layover 事件必须不受预算约束。
   *
   * <p>复用会给列车改名（{@code Layover 复用: trainName 更新为 X}），旧名从此不再出现在任何日志里。
   * 缺了这几行就无法把改名链接起来，"某个列车名不再出现"会被读成"这辆车冻住了"——2026-09-13 就是这样误判了一轮，据此得出的"5 辆车冻死在终点站"是错的。
   */
  @Test
  void layoverLifecycleEventsAreNeverBudgetDropped() {
    List<String> out = new ArrayList<>();
    RuntimeDispatchDiagnosticGate gate = new RuntimeDispatchDiagnosticGate(out::add, 1);

    for (int i = 0; i < 10; i++) {
      gate.accept("Layover 注册: train=t" + i + " terminalKey=surc:s:ppk:1 station=PPK");
      gate.accept("Layover 复用: trainName 更新为 SURC-MT-LH-" + i);
      gate.accept("Layover 复用等待: route=MT-2O_ShortD start=SURC:S:PPK:1");
    }

    long layover = out.stream().filter(l -> l.startsWith("Layover ")).count();
    assertEquals(30, layover, "layover 生命周期事件不得被预算吞掉：" + out.size());
  }

  /** 事务审计不受预算约束——调预算不得动摇这条边界。 */
  @Test
  void transactionAuditsIgnoreTheBudgetEntirely() {
    List<String> out = new ArrayList<>();
    RuntimeDispatchDiagnosticGate gate = new RuntimeDispatchDiagnosticGate(out::add, 1);

    for (int i = 0; i < 50; i++) {
      gate.accept(
          "SMART_STOP_LIFECYCLE event=enter train=t" + i + " reasonCode=BLOCKED_BY_OCCUPANCY");
    }

    long stops = out.stream().filter(l -> l.startsWith("SMART_STOP_LIFECYCLE")).count();
    assertTrue(stops == 50, "停因生命周期是必留事务审计，不得被预算吞掉，实际 " + stops);
  }
}
