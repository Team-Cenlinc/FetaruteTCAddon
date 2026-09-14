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

  /**
   * 阻塞状态快照必须不受预算约束。
   *
   * <p>它存在的唯一理由就是"事件流推不出当前状态"——2026-09-13 我两次据此判断错误。 被普通观察预算吞掉，它就退化成又一条可丢的事件 trace，等于没加。
   */
  @Test
  void blockingStateSnapshotIsNeverBudgetDropped() {
    List<String> out = new ArrayList<>();
    RuntimeDispatchDiagnosticGate gate = new RuntimeDispatchDiagnosticGate(out::add, 1);

    for (int i = 0; i < 25; i++) {
      gate.accept(
          "SMART_BLOCKING_SNAPSHOT train=t"
              + i
              + " heldSeconds=42 reasonCode=PROTECTIVE_RETAIN_HOLD");
    }

    long kept = out.stream().filter(l -> l.startsWith("SMART_BLOCKING_SNAPSHOT")).count();
    assertEquals(25, kept, "状态快照不得被预算吞掉：" + out.size());
  }

  /**
   * 自持单线续行被拒的内层原因必须不受预算约束。
   *
   * <p>外层标签把四个互不相同的成因压成同一个字符串；内层原因是唯一能分辨"WS 出库占着平面交叉不走" 到底属于哪一类的证据。它此前根本没进过日志——承载它的 trace 走
   * SignalComputationTrace.Builder， 那里以**信号灯色**为判据（PROCEED 一律不输出），实服第九轮全场 177 行 SignalTrace 里
   * 自持续行事件一条都没有。
   */
  @Test
  void selfOwnedContinuationRejectReasonIsNeverBudgetDropped() {
    List<String> out = new ArrayList<>();
    RuntimeDispatchDiagnosticGate gate = new RuntimeDispatchDiagnosticGate(out::add, 1);

    for (int i = 0; i < 12; i++) {
      gate.accept(
          "SMART_SELF_OWNED_CONTINUATION_REJECTED train=t"
              + i
              + " resource=single:comp:A~B reason=path-does-not-exit-or-continue");
    }

    long kept =
        out.stream().filter(l -> l.startsWith("SMART_SELF_OWNED_CONTINUATION_REJECTED")).count();
    assertEquals(12, kept, "内层原因不得被预算吞掉：" + out.size());
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

  /**
   * 环被检测到这件事必须不受预算约束。
   *
   * <p>它是"排队位边进图"那个修复（{@code 1f398c7}）唯一的终局判据。实服第十轮丢弃率 **91%** （输出 49790 行、丢弃 509047
   * 行）——不列入必留，修好之后第一次检测到环那一行有九成概率被吞掉， 于是"到底修好没有"根本答不出来。
   */
  @Test
  void cycleDetectionAndItsOutcomeAreNeverBudgetDropped() {
    List<String> out = new ArrayList<>();
    RuntimeDispatchDiagnosticGate gate = new RuntimeDispatchDiagnosticGate(out::add, 1);

    for (int i = 0; i < 20; i++) {
      gate.accept("SMART_DISPATCH_CYCLE_DETECTED type=MUTUAL cycleId=c" + i + " trains=[a, b]");
      // 光知道"检测到环"不够：环检测之后什么都没发生，才是这十轮的常态
      // （SMART_UNLOCK_SUCCESS 连续十轮为 0）。结局那一条同样不能丢，否则下一轮又只能得出
      // "检测到了环，然后不知道"。
      gate.accept("SMART_DISPATCH_PLAN_SELECTED train=t" + i + " cycleId=c" + i);
      gate.accept("SMART_NO_SAME_DIRECTION_UNLOCK_PLAN recommendation=none cycleId=c" + i);
    }

    assertEquals(
        20,
        out.stream().filter(l -> l.startsWith("SMART_DISPATCH_CYCLE_DETECTED")).count(),
        "环检测不得被预算吞掉：" + out.size());
    assertEquals(
        20,
        out.stream().filter(l -> l.startsWith("SMART_DISPATCH_PLAN_SELECTED")).count(),
        "解锁计划被选中不得被预算吞掉：" + out.size());
    assertEquals(
        20,
        out.stream().filter(l -> l.startsWith("SMART_NO_SAME_DIRECTION_UNLOCK_PLAN")).count(),
        "没有可用解锁计划的原因不得被预算吞掉：" + out.size());
  }

  /**
   * 反向边界：{@code SMART_DEADLOCK_DESTROY_ELIGIBILITY} **故意不在**必留名单里。
   *
   * <p>它残留只有 128 行，看着便宜，真实体量却是 **3076 行 / 74 分钟 ≈ 41 行/分钟** （丢弃 2948 + 残留 128）——加进来要给日志增重 6%。而实服
   * {@code destroyEnabled=false}， 它回答的"为什么没资格销毁"当前没有任何可操作性。
   *
   * <p>这条用例把这个取舍钉住：谁要加它进名单，先在这里说明为什么值这 6%。 也顺带钉住判体量的口径——**残留不是体量，体量是「丢弃 + 残留」**。
   */
  @Test
  void destroyEligibilityStaysBudgetedBecauseItIsFortyOneLinesPerMinute() {
    List<String> out = new ArrayList<>();
    RuntimeDispatchDiagnosticGate gate = new RuntimeDispatchDiagnosticGate(out::add, 1);

    for (int i = 0; i < 40; i++) {
      gate.accept("SMART_DEADLOCK_DESTROY_ELIGIBILITY: train=t" + i + " eligible=false");
    }

    long kept =
        out.stream().filter(l -> l.startsWith("SMART_DEADLOCK_DESTROY_ELIGIBILITY")).count();
    assertTrue(kept < 40, "销毁资格判据应当受预算约束，实际全留了 " + kept + " 条");
  }

  /**
   * 健康监视器的事件带冒号，必留判定必须照样认得出来。
   *
   * <p>两种生产端行格式不同：{@code RuntimeDispatchService} 输出 {@code 事件名 空格 ...}， 而 {@link
   * org.fetarute.fetaruteTCAddon.dispatcher.health.TrainHealthMonitor} 的 {@code traceHealthEvent}
   * 输出 {@code 事件名 + ": " + message}。必留判定按第一个空格前的 token 取 kind，不去掉尾部冒号就会得到 {@code
   * "SMART_DEADLOCK_DESTROY_EXECUTED:"}，与名单里的字面量**永不相等**—— 加进名单完全不起作用，而代码路径俱在、看起来像在工作。
   *
   * <p>销毁列车是不可逆动作。在放宽了排队位边进图条件之后，万一放宽造出假环并据此销毁了车， 这一行是唯一的证据，绝不允许被预算丢掉。
   */
  @Test
  void healthMonitorEventsWithTrailingColonAreStillRecognizedAsMustKeep() {
    List<String> out = new ArrayList<>();
    RuntimeDispatchDiagnosticGate gate = new RuntimeDispatchDiagnosticGate(out::add, 1);

    for (int i = 0; i < 20; i++) {
      // 逐字照搬生产端格式：事件名后面紧跟冒号。
      gate.accept("SMART_DEADLOCK_DESTROY_EXECUTED: train=t" + i + " reason=hard-cycle");
      gate.accept("SMART_DEADLOCK_LIVE_CYCLE_CONFIRMED: trainA=a" + i + " trainB=b" + i);
    }

    assertEquals(
        20,
        out.stream().filter(l -> l.startsWith("SMART_DEADLOCK_DESTROY_EXECUTED")).count(),
        "销毁执行是不可逆动作，不得被预算吞掉：" + out.size());
    assertEquals(
        20,
        out.stream().filter(l -> l.startsWith("SMART_DEADLOCK_LIVE_CYCLE_CONFIRMED")).count(),
        "死锁确认不得被预算吞掉：" + out.size());
  }
}
