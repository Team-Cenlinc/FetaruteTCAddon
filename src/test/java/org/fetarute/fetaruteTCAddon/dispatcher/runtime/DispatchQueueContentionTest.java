package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/**
 * S11/S12 前置：真实排队竞争下的队列位次稳定性（I7）。
 *
 * <p>I7 说的是"列车取得队列位次后，经历 STOP、HOLD 或授权重取，其 {@code enqueueSequence} 不得增大"。增大意味着它被移出队列又重新入队，
 * <b>静默失去了已经赢下的资格</b>，后来者会插到它前面。生产里有 {@code releaseResourceRetainingQueuePosition} 专门为此而写，
 * 但此前<b>没有任何测试验证各条 STOP 路径都调用了它</b>。
 *
 * <h3>为什么必须专门造一个场景</h3>
 *
 * <p>S00/S01/S03/S16 里 <b>每一个 {@code enqueueSequence} 都是 0</b>——那些场景的列车沿走廊铺得太开，任意时刻同一个冲突资源上
 * 至多只有一列车排队，计数器永远只分配第一个值。在那种现场上 I7 恒绿，而且<b>恒绿不代表任何事</b>。
 *
 * <p>本场景把四列车挤在同一条会让走廊上，才第一次产生真正的并发排队（单队列并发 ≥2，序号一路涨到数百）。
 * 用例因此<b>同时断言"竞争真的发生了"</b>，否则一个铺得太开的改动会让这条不变量悄悄退化成空绿。
 *
 * <h3>已知的结构性弱点（记录，暂不修）</h3>
 *
 * <p>{@code enqueueSequence} 的计数器挂在 {@code ConflictQueue} 上，而队列一旦排空就会被 {@code
 * queues.remove(resource)} 整个丢弃，下次 {@code computeIfAbsent} 建的新队列从 0 重新开始。所以它只是"队列非空期间的稳定序号"，
 * 不是跨越排空间隙的全局到达序。本不变量因此只比较<b>同一段连续排队期内</b>的序号，且基线在列车真正取得 claim 时清除。
 */
class DispatchQueueContentionTest {

  private static final int MAX_TICKS = 500;

  /**
   * 七站会让走廊，四列车隔站起步。
   *
   * <p>这个配置是量出来的，不是拍的。要让 I4/I7 真正被检验，现场必须同时满足三件事：单队列并发 ≥2（有竞争）、 有列车拿到过非 STOP 信号（I4
   * 有判定对象）、并且确实有列车在推进（不是发车线上就全锁死）。实测：
   *
   * <pre>
   *   5 站 0/1/2/3 → 并发 2、比较 1996 次，但 proceedChecks=0、四车 progress 全 0（发车线上即死锁）
   *   5 站 0/2/4   → 并发 2、proceedChecks=260，只有一车推进
   *   7 站 0/2/4/6 → 并发 2、比较 1713 次、proceedChecks=958、四车中三车推进  ← 采用
   * </pre>
   *
   * <p>四列车挤在 5 站上是发车线上就首尾相接的初始条件，现实里不会这么排；那种现场里所有不变量都"绿"， 因为根本没有任何事情发生过。用例因此把这三件事都写成断言。
   */
  private DispatchScenarioHarness contendingLoop() {
    DispatchScenarioHarness.Topology topology =
        DispatchScenarioHarness.loopCorridor(List.of("A1", "B1", "C1", "D1", "E1", "F1", "G1"));
    List<NodeId> path = topology.physicalPath();
    return DispatchScenarioHarness.builder()
        .topology(topology)
        .train("q0", "shared-route", path, topology.stations(), 0)
        .train("q1", "shared-route", path, topology.stations(), 2)
        .train("q2", "shared-route", path, topology.stations(), 4)
        .train("q3", "shared-route", path, topology.stations(), 6)
        .build();
  }

  /** 现场必须真的"活"过：有竞争、有判定对象、有推进。三者缺一，全绿都不代表任何事。 */
  private void assertFieldIsAlive(DispatchScenarioHarness harness) {
    assertTrue(harness.maxQueueDepth() >= 2, "整场没有任何冲突资源出现并发排队：" + harness.describeState());
    assertTrue(
        harness.queuePositionComparisons() > 0, "I7 一次\"有基线可比\"的比较都没做过：" + harness.describeState());
    assertTrue(
        harness.proceedAuthorityChecks() > 0,
        "整场没有任何列车拿到过非 STOP 信号，I4 没有判定对象：" + harness.describeState());
    assertTrue(
        harness.trainNames().stream().anyMatch(name -> harness.progressOf(name) > 0),
        "没有任何列车推进过——这是发车线上就锁死的现场，任何不变量在这里绿都没有意义：" + harness.describeState());
  }

  /**
   * I7：<b>当前已知缺陷</b>——硬停会让列车丢掉已经赢下的队列位次。
   *
   * <p>实测轨迹（`CONFLICT:switcher:SWITCHER:C1:W` 上的 q2）：
   *
   * <pre>
   *   tick 1   enqueueSequence=1   停因 BLOCKED_BY_OCCUPANCY
   *   tick 2   队列条目消失         停因转为 AUTHORIZATION_FAILURE
   *   tick 262 重新入队，enqueueSequence=2
   *   全程 q2 从未在该资源上持有过 claim
   * </pre>
   *
   * <p>也就是说：一次硬停把它的排队条目整个摘掉，260 个 tick 之后它从队尾重新排。这正是 {@code releaseResourceRetainingQueuePosition}
   * 想要防止的事——该方法存在，但硬停路径显然没有走它 （它走的是 {@code releaseMovementAuthorityResources}）。规格里对 S12
   * 的怀疑"硬停路径最可疑"由此得到证实， 而且不需要先把 S12 造出来。
   *
   * <p>影响：后来者会插到它前面，先到先得被打破；在高密度区段表现为"某辆车一直排不上"。
   *
   * <p><b>修好后本用例会失败。</b>那时把断言翻转成 {@code assertNoViolationsOf("I7")}，并把 I7 加进 {@link
   * #contentionDoesNotBreakPhysicalSafety()} 的保证范围。
   */
  @Test
  void queuePositionCurrentlyRegressesAfterHardStop() {
    DispatchScenarioHarness harness = contendingLoop();

    harness.runTicks(MAX_TICKS);

    assertFieldIsAlive(harness);
    List<String> regressions = harness.violationsOf("I7");
    assertFalse(
        regressions.isEmpty(),
        "I7 已经不再被违反——队列位次可能已经修好了。请把本用例翻转为 assertNoViolationsOf(\"I7\")"
            + "，并把 I7 加进 contentionDoesNotBreakPhysicalSafety 的保证范围，而不是删掉本用例。\n"
            + harness.describeState());
    assertTrue(
        regressions.stream().allMatch(line -> line.contains("队列位次倒退")),
        "I7 违反形态变了，需要重新归因:\n  " + String.join("\n  ", regressions));
  }

  /** 同一现场下结构性安全不得退化——排队竞争不是放宽互斥的理由。 */
  @Test
  void contentionDoesNotBreakPhysicalSafety() {
    DispatchScenarioHarness harness = contendingLoop();

    harness.runTicks(MAX_TICKS);

    assertFieldIsAlive(harness);
    harness.assertNoViolationsOf("I1", "I2", "I3", "I4", "I6");
  }
}
