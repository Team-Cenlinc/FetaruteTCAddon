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
   * L1 被他车尾部保护挡住的车必须最终推进。
   *
   * <p><b>本用例已从"钉住缺陷"翻正。</b>此前它断言的是缺陷仍在：实服第八轮 439/439 条 {@code PROTECTIVE_RETAIN_HOLD} 快照都 {@code
   * movementToken=ACTIVE}（自身授权完好、只是被别人的 尾部保护挡着），1035 个 blocker 里 840 个是 PROTECTIVE_RETAIN，挡人的 30 辆车
   * 30 辆自己也被挡， 整条链没有出口。
   *
   * <p>它一直绿着并不是因为缺陷还在，而是因为**骨架从未真正运转过实测覆盖层**： {@code liveRailFootprintCells} 返回一个固定的 y=200
   * 方块，命不中任何边足迹；而 {@code smartRecoveryLayer(true)} 只调了一次全局快照，并不驱动任何恢复动作。两处补上之后 Phase 4 当场生效（{@code
   * releasedCount=2 edges=1 nodes=1}），环自解。
   */
  @Test
  void trainsBlockedByOthersProtectiveRetainEventuallyProceed() {
    DispatchScenarioHarness harness = contendedLoop();

    harness.runTicks(MAX_TICKS);

    List<String> trains = List.of("live-lead", "live-mid", "live-tail");

    // 一：现场必须真的用到过尾部保护回收，否则本用例什么都没判别。
    List<String> releases =
        harness.debugLog().stream()
            .filter(line -> line.contains("SMART_PHYSICAL_EDGE_RETAIN_RELEASED train="))
            .toList();
    assertFalse(releases.isEmpty(), () -> "整场没有一次尾部保护回收——用例不判别任何东西\n" + harness.describeState());

    // 二：不得有车最终停在别人的尾部保护上。
    List<String> stuck =
        trains.stream()
            .filter(t -> "PROTECTIVE_RETAIN_HOLD".equals(harness.stopReasonOf(t)))
            .toList();
    assertTrue(stuck.isEmpty(), () -> "仍有车停在尾部保护上：" + stuck + "\n" + harness.describeState());

    // 三：也不得"中途卡很久又自己好了"——那同样是缺陷的形态，只断言终态会漏掉它。
    // N 取 240 tick（12 秒模拟时间）：实测本场景最长连续 STOP 远低于此，
    // 而缺陷未修时列车会卡满整场 900 tick。
    for (String train : trains) {
      assertTrue(
          harness.stopStreakTicksOf(train) <= 240,
          () ->
              train
                  + " 连续停车 "
                  + harness.stopStreakTicksOf(train)
                  + " tick，超过上限 240\n"
                  + harness.describeState());
    }
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
   * L3 自持尾部保护的回收必须真的能释放，且放行条件必须是**实测物理覆盖**。
   *
   * <p><b>本用例已从"钉住缺陷"翻正。</b>原缺陷：{@code rememberSelfOwnedStaleRetainCandidate} 与 {@code
   * stillHasReleasableSelfOwnedRetain} 都要求 {@code resource.kind() == CONFLICT}， 而实服的尾部保护 blocker 是
   * **NODE 840 / EDGE 若干 / CONFLICT 0**，判据与现实永不相交。
   *
   * <p>修法不是去掉那道资源类型限制——CONFLICT 是抽象互斥键，NODE/EDGE 对应物理空间， 车体还压着时释放就是
   * co-occupancy。因此这里**同时断言两件事**：确实释放了， 且释放来自实测覆盖（trace 带 {@code covered=}）。少了后半句，用"放宽资源类型"
   * 蒙混过关的实现也能让本用例变绿。
   */
  @Test
  void selfOwnedRetainReleaseUsesMeasuredPhysicalCoverage() {
    DispatchScenarioHarness harness = contendedLoop();

    harness.runTicks(MAX_TICKS);

    List<String> releases =
        harness.debugLog().stream()
            .filter(line -> line.contains("SMART_PHYSICAL_EDGE_RETAIN_RELEASED train="))
            .toList();
    assertFalse(releases.isEmpty(), () -> "回收机制一次都没产出释放\n" + harness.describeState());
    assertTrue(
        releases.stream().allMatch(line -> line.contains(" covered=")),
        () -> "释放没有带实测覆盖证据——放行条件可能被换成了放宽资源类型：\n  " + String.join("\n  ", releases));

    // NODE 半边必须也在出力：NODE 占实服 blocker 的 75%，只放 EDGE 等于放过大头。
    assertTrue(
        releases.stream().anyMatch(line -> line.contains(" nodes=") && !line.contains(" nodes=0 ")),
        () -> "只释放了 EDGE，NODE 半边没有生效：\n  " + String.join("\n  ", releases));
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
