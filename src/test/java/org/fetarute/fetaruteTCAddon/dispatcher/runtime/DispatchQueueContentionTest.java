package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/**
 * S11/S12 前置：真实排队竞争下的队列位次稳定性（I7）。
 *
 * <p>I7 说的是"列车取得队列位次后，经历 STOP、HOLD 或授权重取，其 {@code enqueueSequence} 不得增大"。增大意味着它被
 * 移出队列又重新入队，<b>静默失去了已经赢下的资格</b>，后来者会插到它前面。生产有 {@code releaseResourceRetainingQueuePosition} 专门为此而写。
 *
 * <h3>为什么必须专门造一个场景</h3>
 *
 * <p>S00/S01/S03/S16 里每一个 {@code enqueueSequence} 都是 0——列车沿走廊铺得太开，任意时刻同一冲突资源上至多
 * 一列车排队，计数器永远只分配第一个值。那种现场上 I7 恒绿，而且恒绿不代表任何事。本场景把四列车挤在同一条
 * 走廊上，才产生真正的并发排队。用例因此<b>先断言现场是活的</b>（有竞争、有判定对象、有推进），再断言不变量。
 *
 * <h3>一个被时钟骗过、又被时钟纠正的结论</h3>
 *
 * <p>本用例一度断言 I7 <b>被违反</b>，并给出过很具体的轨迹：某车 {@code enqueueSequence=1} 时挨了一次硬停， 队列条目被整个摘掉，260 个 tick
 * 之后以 {@code enqueueSequence=2} 从队尾重排，全程没持有过该资源的 claim。 据此曾断定"硬停路径没走 {@code
 * releaseResourceRetainingQueuePosition}"。
 *
 * <p>那个结论是错的，成因是<b>骨架当时用墙钟跑</b>：骨架一个 tick 只花约 1ms，而生产一个 tick 是 50ms。 队列仲裁的 {@code
 * arbitrationDeadlineMillis} 由 {@code firstSeen} 与优先级折算的毫秒数算出——当所有时间戳被压 到 1ms
 * 粒度内，先后关系退化成任意打破，于是同一份代码连跑 6 次出现 3 次违反、3 次不违反。
 *
 * <p>给 {@link RuntimeDispatchService} 注入按 50ms/tick 推进的场景时钟之后，这套现场<b>确定</b>且 I7
 * <b>成立</b>。也就是说那是骨架的时间压缩造出来的假象，不是生产缺陷。
 *
 * <p>留下的真实教训有两条，都比原"缺陷"重要：<b>调度的放行顺序对 tick 的真实时长敏感</b>；以及 <b>任何带时间语义的判定，都不能在压缩时间的骨架上下结论</b>。
 */
class DispatchQueueContentionTest {

  private static final int MAX_TICKS = 1000;

  /**
   * 五站会让走廊，四列车逐站起步。
   *
   * <p>配置是量出来的：要让 I7 真正被检验，现场必须同时有并发排队、有非 STOP 信号、且有列车推进。 在 50ms/tick 的场景时钟下实测（跑 1000 tick）：
   *
   * <pre>
   *   5 站 0/1/2/3 → 并发 2、比较 2918 次、proceed 1496 次、四车全部推进  ← 采用
   *   7 站 0/2/4/6 → 并发 1（没有竞争）
   *   9 站 0/2/4/6/8 → 并发 1（没有竞争）
   * </pre>
   *
   * <p>注意这些数字与墙钟骨架时代的完全不同：那时 5 站 0/1/2/3 是发车线上就锁死的现场（四车 progress 全 0），
   * 现在它是四车全部推进的正常现场。换时间源会换掉整个现场的动力学，旧的调参结论不可沿用。
   */
  private DispatchScenarioHarness contendingLoop() {
    DispatchScenarioHarness.Topology topology =
        DispatchScenarioHarness.loopCorridor(List.of("A1", "B1", "C1", "D1", "E1"));
    List<NodeId> path = topology.physicalPath();
    return DispatchScenarioHarness.builder()
        .topology(topology)
        .train("q0", "shared-route", path, topology.stations(), 0)
        .train("q1", "shared-route", path, topology.stations(), 1)
        .train("q2", "shared-route", path, topology.stations(), 2)
        .train("q3", "shared-route", path, topology.stations(), 3)
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
        "没有任何列车推进过——这是锁死的现场，任何不变量在这里绿都没有意义：" + harness.describeState());
  }

  /** 并发排队下队列位次不倒退。 */
  @Test
  void queuePositionHoldsUnderRealContention() {
    DispatchScenarioHarness harness = contendingLoop();

    harness.runTicks(MAX_TICKS);

    assertFieldIsAlive(harness);
    harness.assertNoViolationsOf("I7");
  }

  /** 同一现场下结构性安全与可解释性不得退化——排队竞争不是放宽互斥的理由。 */
  @Test
  void contentionDoesNotBreakPhysicalSafety() {
    DispatchScenarioHarness harness = contendingLoop();

    harness.runTicks(MAX_TICKS);

    assertFieldIsAlive(harness);
    harness.assertNoViolationsOf("I1", "I2", "I3", "I4", "I6", "I7");
  }
}
