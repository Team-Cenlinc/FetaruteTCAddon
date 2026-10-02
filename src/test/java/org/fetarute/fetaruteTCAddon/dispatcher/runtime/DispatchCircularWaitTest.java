package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * S10 三车循环等待。
 *
 * <p>规格要求"构造使 α 等 β、β 等 γ、γ 等 α 的资源持有格局"，并且<b>必须跨交路</b>——同交路时 {@code RouteProgressRegistry}
 * 的索引比较能正确定序，会把问题掩盖掉。三列车各自一条交路，在环形走廊上顺时针追赶。
 *
 * <h3>为什么是环形单股道走廊</h3>
 *
 * <p>先扫过 10 种线形多车配置（3/4/5/7 站 × 同向/对向 × 不同起点），<b>全部只产生 2-环</b>。3-环撞不出来， 必须按拓扑构造。{@link
 * DispatchScenarioHarness#ringCorridor} 的两处选择都是必需的：每站单股道（有第二条股道就等于给了旁路，
 * 环永远闭不上），每站两端仍放道岔（否则整个环没有边界，会被归并成一个 {@code cycle:} 闭环冲突并严格互斥， 变成全环只准一辆车的串行化，那不是循环等待）。
 *
 * <h3>两个现场，分别回答不同的问题</h3>
 *
 * <ul>
 *   <li><b>3 站 3 车</b>：物理上无解——没有任何空位，谁都无法先动。用它验证"系统在真正无解时的表现"： 环成立、不自行消解（destroy 是关的）、但安全与可解释性全部保持。
 *   <li><b>4 站 3 车</b>：留一个空位，环<b>可解</b>。用它验证仲裁确实能挑出推进者。审计怀疑过 "成对反对称关系只能保证无 2-环，不保证无
 *       3-环"——这个现场是该怀疑的直接检验。
 * </ul>
 */
class DispatchCircularWaitTest {

  private static final int MAX_TICKS = 400;

  private DispatchScenarioHarness ring(List<String> stations, int trainCount) {
    return ring(stations, trainCount, false);
  }

  private DispatchScenarioHarness ring(
      List<String> stations, int trainCount, boolean smartRecoveryLayer) {
    DispatchScenarioHarness.Topology topology = DispatchScenarioHarness.ringCorridor(stations);
    DispatchScenarioHarness.Builder builder =
        DispatchScenarioHarness.builder().topology(topology).smartRecoveryLayer(smartRecoveryLayer);
    int legs = stations.size() - 1;
    for (int i = 0; i < trainCount; i++) {
      builder.train(
          "r" + i,
          "ring-route-" + i,
          DispatchScenarioHarness.ringRoute(stations, i, legs),
          DispatchScenarioHarness.ringStations(stations, i, legs),
          0);
    }
    return builder.build();
  }

  /**
   * 无解的环：成立、不自行消解，但安全与可解释性全部保持。
   *
   * <p>三列车各占一站、各自需要下一站，没有空位，任何调度都无法在不让某辆车反向或被移除的前提下解开——
   * 这不是缺陷。<b>值得断言的是系统在这种局面下的表现</b>：不共占、不越权、说得出在等谁，并且不会靠 destroy 硬闯。
   */
  @Test
  void unavoidableRingHoldsSafelyAndStaysExplainable() {
    DispatchScenarioHarness harness = ring(List.of("RA", "RB", "RC"), 3);

    harness.runTicks(MAX_TICKS);

    assertEquals(
        List.of("r0->r1->r2->r0"),
        harness.observedWaitCycles(),
        "3-环的形态变了:\n" + harness.describeState());
    assertTrue(
        harness.hasLiveWaitCycle(),
        "无解的环自行消解了——只可能是有车被强行处置了，destroy 应当是关的。\n" + harness.describeState());
    // 安全与可解释性一条都不能因为死锁而退化。I5 尤其重要：恢复层要动手，先得知道在等谁。
    harness.assertNoViolationsOf("I1", "I2", "I3", "I4", "I5", "I6", "I7");
  }

  /**
   * 生产自己的 wait-for 图必须完整看见这个环。
   *
   * <p>这是 {@code 22fcde1}（绑定进度锚点）在多车层面的收益：证据链完整时，planner 拿到的是 {@code nodes=3 edges=3
   * rejectedEdges=0 staleEdges=0}——一个完好的三环。修复前实服里这条链是断的 （{@code BLOCKER_SNAPSHOT_MISSING} 占 destroy
   * 判定的 71%），planner 看到的是残缺图。
   */
  @Test
  void productionWaitForGraphSeesTheWholeRing() {
    // 这是唯一需要恢复层的用例，断言的也只是"它看见了什么"这种与时序无关的事实。
    DispatchScenarioHarness harness = ring(List.of("RA", "RB", "RC"), 3, true);

    harness.runTicks(MAX_TICKS);

    String graph = harness.lastDiagnostic("SMART_WAIT_FOR_GRAPH");
    assertTrue(
        graph.contains("nodes=3") && graph.contains("edges=3"),
        "planner 没有看见完整的三环: " + graph + "\n" + harness.describeState());
    assertTrue(
        graph.contains("rejectedEdges=0") && graph.contains("staleEdges=0"),
        "planner 丢了边——证据链上游又断了: " + graph);
  }

  /**
   * 可解的环：仲裁必须挑得出推进者，且不依赖 destroy。
   *
   * <p>四站三车留一个空位。审计怀疑"成对反对称关系只能保证无 2-环"——如果该怀疑在这个现场成立， 三车会一起卡死；实测它解开了。本用例把这个能力钉住：将来任何让它重新卡死的改动都会变红。
   */
  @Test
  void resolvableRingUnwindsWithoutDestroy() {
    DispatchScenarioHarness harness = ring(List.of("RA", "RB", "RC", "RD"), 3);

    harness.runTicks(MAX_TICKS);

    assertFalse(harness.hasLiveWaitCycle(), "有空位的环没有解开——仲裁没能挑出推进者:\n" + harness.describeState());
    assertTrue(
        harness.trainNames().stream().anyMatch(name -> harness.progressOf(name) > 0),
        "没有任何列车推进过:\n" + harness.describeState());
    harness.assertNoViolationsOf("I1", "I2", "I3", "I4", "I5", "I6", "I7");
  }
}
