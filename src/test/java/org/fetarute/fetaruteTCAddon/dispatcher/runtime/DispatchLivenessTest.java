package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/**
 * Liveness：动态调度"做完了没有"的判据。
 *
 * <p>I1–I11 全是**安全性**不变量——唯一性、方向一致、claim 不越界、队列不倒退……它们保证"不会出坏事"， 但**没有任何一条能说出"好事最终会发生"**。后果是
 * 2026-09-13 连跑八轮实服，每轮靠人读日志才发现新缺陷， 而测试套件始终全绿：吞吐从 126/5min 塌到 12/5min 时，**0 条红**。
 *
 * <p>这里补上最小的一条 liveness：**wait-for 环必须在有界时间内被打开**。环由各车 {@code RuntimeStopState.blockers} 的 owner
 * 关系构成，是调度自己说出来的依赖，不是骨架另算的。
 *
 * <p>实服对应现象（第八轮，`30b6ded`，数据已清除幽灵停因与 inhibitor 级联）： 挡住别人的 30 辆车里 **30 辆自己也被挡**，连续三轮都是 100%；blocker
 * 里 **840/1035 是 `PROTECTIVE_RETAIN`**；而能回收自持尾部保护的机制 {@code selfRetainReleaseCandidate} 在
 * **439/439** 条快照里恒为 false——它只认 CONFLICT，而实际全是 NODE/EDGE。
 *
 * <p><b>本文件里的红用例修好后会失败。</b>那时把断言翻转为"环必须在 N tick 内消失"，而不是删掉。
 */
class DispatchLivenessTest {

  private static final int MAX_TICKS = 900;

  /**
   * L1 被他车尾部保护挡住的车必须最终推进（当前红，钉住缺陷）。
   *
   * <p>环本身已由 S10（{@code DispatchCircularWaitTest}）覆盖，这里钉的是另一件事：
   * **尾部保护按定义是过渡态**——持有者正在离开，所以它应当很快消失。实服第八轮却是 439/439 条 `PROTECTIVE_RETAIN_HOLD` 快照都 {@code
   * movementToken=ACTIVE} （自身授权完好、只是被别人的尾部保护挡着），而 1035 个 blocker 里 840 个是 PROTECTIVE_RETAIN， 挡人的 30
   * 辆车 30 辆自己也被挡。整条链没有出口。
   *
   * <p><b>修好后本用例会失败。</b>那时翻转为"停留不得超过 N tick"并定出 N。
   */
  @Test
  void trainsBlockedOnlyByOthersProtectiveRetainCurrentlyNeverProceed() {
    DispatchScenarioHarness harness = contendedLoop();

    harness.runTicks(MAX_TICKS);

    List<String> stuck =
        List.of("live-lead", "live-mid", "live-tail").stream()
            .filter(t -> "PROTECTIVE_RETAIN_HOLD".equals(harness.stopReasonOf(t)))
            .toList();
    assertTrue(
        !stuck.isEmpty(),
        "跑满 " + MAX_TICKS + " tick 后没有任何车停在尾部保护上——用例不判别任何东西\n" + harness.describeState());
    assertFalse(
        stuck.isEmpty(),
        "尾部保护已经不再困住列车——缺陷可能已修复。"
            + "请把本用例翻转为'停留不得超过 N tick'并定出 N，而不是删掉。\n"
            + harness.describeState());
  }

  /**
   * L2 安全性在同一现场下不得退化。
   *
   * <p>环不自解是 liveness 问题，不是安全问题——不能因为解不开就允许共占。这条必须一直绿。
   */
  @Test
  void unresolvedCycleStillHoldsEverySafetyInvariant() {
    DispatchScenarioHarness harness = contendedLoop();

    harness.runTicks(MAX_TICKS);

    // I5（停车可解释性）是另一条已经单独钉住的红线（DispatchBlockingExplainabilityTest），
    // 它是诊断面的缺陷，不是安全性退化。这里只断言真正的安全不变量。
    harness.assertNoViolationsOf("I1", "I2", "I3", "I4", "I7", "I8", "I9", "I10", "I11");
  }

  /**
   * L3 自持尾部保护的回收机制当前从不产出候选（当前红，钉住缺陷）。
   *
   * <p>实服 439/439 条 `PROTECTIVE_RETAIN_HOLD` 快照里 {@code selfRetainReleaseCandidate=false}。 成因是
   * {@code rememberSelfOwnedStaleRetainCandidate} 与 {@code stillHasReleasableSelfOwnedRetain} 都要求
   * {@code resource.kind() == CONFLICT}， 而实服的尾部保护 blocker 是 **NODE 840 / EDGE 若干 / CONFLICT 0**。
   *
   * <p>**不要靠去掉那道限制来"修"它**：CONFLICT 是抽象互斥键，NODE/EDGE 对应物理空间， 车体还压在上面时释放就是
   * co-occupancy。正确做法是用实测物理覆盖做放行条件（Phase 4）。
   *
   * <p><b>修好后本用例会失败。</b>那时翻转为"必须能产出候选"。
   */
  @Test
  void selfOwnedRetainReleaseCurrentlyNeverHasACandidate() {
    DispatchScenarioHarness harness = contendedLoop();

    harness.runTicks(MAX_TICKS);

    List<String> retainHolds =
        harness.debugLog().stream().filter(line -> line.contains("protective-retain:")).toList();
    assertTrue(!retainHolds.isEmpty(), "整场没有出现尾部保护停车——用例不判别任何东西\n" + harness.describeState());
    assertFalse(
        retainHolds.stream().anyMatch(line -> line.contains("protective-retain:release-candidate")),
        "出现了可释放候选——回收机制可能已经覆盖到 NODE/EDGE。"
            + "请把本用例翻转为'必须能产出候选'，并确认放行条件用的是实测物理覆盖而不是放宽了资源类型。\n  "
            + String.join("\n  ", retainHolds.subList(0, Math.min(5, retainHolds.size()))));
  }

  private static DispatchScenarioHarness contendedLoop() {
    DispatchScenarioHarness.Topology topology =
        DispatchScenarioHarness.loopCorridor(List.of("ALFA", "BRAVO", "CHARLIE", "DELTA", "ECHO"));
    List<NodeId> path = topology.physicalPath();
    return DispatchScenarioHarness.builder()
        .topology(topology)
        .smartRecoveryLayer(true)
        .train("live-lead", "shared-route", path, topology.stations(), 5)
        .train("live-mid", "shared-route", path, topology.stations(), 2)
        .train("live-tail", "shared-route", path, topology.stations(), 0)
        .build();
  }
}
