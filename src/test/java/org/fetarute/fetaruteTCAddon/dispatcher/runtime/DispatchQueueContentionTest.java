package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

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

  private DispatchScenarioHarness contendingLoop() {
    DispatchScenarioHarness.Topology topology =
        DispatchScenarioHarness.loopCorridor(List.of("ALFA", "BRAVO", "CHARLIE", "DELTA", "ECHO"));
    List<NodeId> path = topology.physicalPath();
    return DispatchScenarioHarness.builder()
        .topology(topology)
        .train("q0", "shared-route", path, topology.stations(), 0)
        .train("q1", "shared-route", path, topology.stations(), 1)
        .train("q2", "shared-route", path, topology.stations(), 2)
        .train("q3", "shared-route", path, topology.stations(), 3)
        .build();
  }

  @Test
  void queuePositionNeverRegressesUnderRealContention() {
    DispatchScenarioHarness harness = contendingLoop();

    harness.runTicks(MAX_TICKS);

    // 先证明这条不变量这一轮真的有东西可判。
    assertTrue(
        harness.maxQueueDepth() >= 2,
        "整场没有任何冲突资源出现并发排队，I7 全绿是空的。列车铺得太开了：" + harness.describeState());
    assertTrue(
        harness.queuePositionComparisons() > 0,
        "I7 一次\"有基线可比\"的比较都没做过，全绿是空的：" + harness.describeState());

    harness.assertNoViolationsOf("I7");
  }

  /** 同一现场下结构性安全不得退化——排队竞争不是放宽互斥的理由。 */
  @Test
  void contentionDoesNotBreakPhysicalSafety() {
    DispatchScenarioHarness harness = contendingLoop();

    harness.runTicks(MAX_TICKS);

    harness.assertNoViolationsOf("I1", "I2", "I3", "I6");
  }
}
