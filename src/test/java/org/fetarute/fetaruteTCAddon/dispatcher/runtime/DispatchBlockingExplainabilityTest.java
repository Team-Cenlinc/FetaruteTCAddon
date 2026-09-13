package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/**
 * S16 阻塞可解释性与陈旧 blocker 清理。
 *
 * <p>对应 Phase 0 规格里的 I5（阻塞可解释性）与 I6（请求上下文与进度表一致）。二者是一条因果链：请求携带的进度锚点与进度表不一致时， 这次判定产生的 blocker 证据会被
 * {@code liveBlockerSnapshotProgressFresh} 丢弃（I6 违反），于是列车停下来而系统说不出在等谁（I5 违反）， 死锁检测与列车清理同时失明。实服形态是
 * {@code BLOCKER_SNAPSHOT_MISSING} 占 destroy 判定的 71%，伴随 127–178s 无解释停车。
 *
 * <p>本用例是 Phase 0 的"钉住现状"用例，<b>同时包含一条绿的保证和一条红的缺陷</b>：
 *
 * <ul>
 *   <li><b>I6 必须绿。</b> 进度锚点已在 {@code markDirectedRequest} 绑定（提交 22fcde1）。回滚那次修复会让本用例变红—— 这正是它存在的意义。
 *   <li><b>I5 目前必定红</b>，且形态固定：股道占满导致的硬停，停因里 blocker 为空、队列里也没有该车。这里<b>正面断言这个缺陷存在</b>，
 *       而不是把它藏进白名单。修好之后本用例会失败，那时应当把断言翻转成"不得再出现"，而不是删掉。
 * </ul>
 */
class DispatchBlockingExplainabilityTest {

  private static final int MAX_TICKS = 400;

  /**
   * 三车同交路跟驰，前两车会把某个会让站的两条股道同时占住。
   *
   * <p>现场与 S01 相同——那是刻意的：S01 从"仲裁有没有对称拒绝"看这块现场，本用例从"停车说不说得出在等谁"看同一块现场。
   * 会让站必须足够多，前车才有机会在中途把两条股道同时占满；三站的走廊里前车会直接停到终点站，后车全都能指名等谁。
   */
  private DispatchScenarioHarness saturatedLoop() {
    DispatchScenarioHarness.Topology topology =
        DispatchScenarioHarness.loopCorridor(List.of("ALFA", "BRAVO", "CHARLIE", "DELTA", "ECHO"));
    List<NodeId> path = topology.physicalPath();
    return DispatchScenarioHarness.builder()
        .topology(topology)
        .train("lead", "shared-route", path, topology.stations(), 5)
        .train("mid", "shared-route", path, topology.stations(), 2)
        .train("tail", "shared-route", path, topology.stations(), 0)
        .build();
  }

  /**
   * I6：整场不得出现任何"请求上下文已陈旧"的判定。
   *
   * <p>骨架的 tick 循环是同步的——信号 tick 阶段不更新进度表，到达提交在其后单独一段——所以这里出现的任何 {@code
   * SMART_LIVE_BLOCKER_SNAPSHOT_REJECTED} 都不可能是真的异步滞后，只能是请求上下文自己没和进度表对齐。
   *
   * <p><b>关于本用例的效力，必须说清楚：</b>在当前拓扑上它<b>抓不到</b> 22fcde1 修掉的那个缺陷——实测回滚该提交后这里依然全绿， 因为本拓扑里 {@code
   * resolveEffectiveCurrentNodeForSignal} 总能成功采用 lastPassed（走廊简单，锚点必然落在最短路上），
   * 旧实现用窗口起点顶替时两者恰好相等。真正会分叉的形态是咽喉与段场（规格里的 T3/T4/T5），骨架还没有这些拓扑。
   *
   * <p>因此该缺陷的<b>回归保护在单元层</b>：{@link LiveBlockerSnapshotProgressAnchorTest}（回滚 22fcde1 会让其中 2
   * 条变红，已实测）。本用例是多车层面的看门狗，等 T3/T4/T5 进来之后才会真正吃上力。
   *
   * <p>为避免"没触发也算绿"，这里额外断言快照机制确实跑过。断言读的是<b>状态</b>（{@code recentDeadlockBlockers} 非空）而不是数
   * trace：{@code SMART_LIVE_BLOCKER_SNAPSHOT_UPDATED} 要过诊断预算门， 现场一嘈杂就会被压掉——接上 Smart 恢复层之后这条 trace
   * 立刻被挤没了，而快照本身一直在写。 用 trace 有无判断状态，会把预算问题误读成证据缺失。
   */
  @Test
  void requestContextNeverDivergesFromProgressRegistry() {
    DispatchScenarioHarness harness = saturatedLoop();

    harness.runTicks(MAX_TICKS);

    assertTrue(
        harness.blockerSnapshotObservations() > 0, "整场没有写入过任何 blocker 快照——I6 全绿是空的，证据链上游已经断了");
    harness.assertNoViolationsOf("I6");
  }

  /**
   * I5：<b>当前已知缺陷</b>——股道占满导致的硬停说不出在等谁。
   *
   * <p>停因是 {@code AUTHORIZATION_FAILURE /
   * dynamic-target-blocked:no-available-platform}：系统知道"没有可用股道"， 却不记录<b>是谁占着</b>。于是 {@code
   * RuntimeStopState.blockers} 为空、队列里也没有这辆车，恢复层拿不到任何可执行的依赖。
   *
   * <p>这与实服里"明明前面有空位它不进去"是同一类现象的诊断面：不是判错，是判不出。归属 Phase 4（账本直接产出结构化 wait-for 边）。
   *
   * <p><b>修好后本用例会失败。</b>那时把断言翻转为"不得再出现"，并把 S01 的保证范围扩到 I5。
   */
  @Test
  void platformSaturationStopIsCurrentlyUnexplainable() {
    DispatchScenarioHarness harness = saturatedLoop();

    harness.runTicks(MAX_TICKS);

    List<String> unexplained = harness.violationsOf("I5");
    assertFalse(
        unexplained.isEmpty(),
        "I5 已经不再被违反——缺陷可能已修复。请把本用例翻转为'不得再出现'，"
            + "并把 S01 的保证范围扩到 I5，而不是删掉本用例。\n"
            + harness.describeState());
    assertTrue(
        unexplained.stream().anyMatch(line -> line.contains("no-available-platform")),
        "I5 违反形态变了，说明成因已经不是股道占满，需要重新归因:\n  " + String.join("\n  ", unexplained));
    assertTrue(
        unexplained.stream().allMatch(line -> line.contains("blockers=[] inAnyQueue=false")),
        "出现了带依赖信息却仍被判为不可解释的停车，说明 I5 的判据本身要收紧:\n  " + String.join("\n  ", unexplained));
  }

  /** 结构性安全在同一现场下不得退化——不可解释不等于可以共占。 */
  @Test
  void saturationDoesNotBreakPhysicalSafety() {
    DispatchScenarioHarness harness = saturatedLoop();

    harness.runTicks(MAX_TICKS);

    harness.assertNoViolationsOf("I1", "I2", "I3", "I4");
  }
}
