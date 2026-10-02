package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.junit.jupiter.api.Test;

/**
 * I11 诊断层零副作用。
 *
 * <h3>规格给的断言方式是错的</h3>
 *
 * <p>规格原文说"观察模式下跑完整场景，断言账本 {@code version()} 增量为 0"。但 {@code OBSERVE_ONLY} 只禁止 <b>Smart
 * Dispatcher</b> 的副作用（signal / destination / token / occupancy / destroy）， <b>基础准入层照常取
 * claim</b>——`version()` 必然增长。照抄会得到一条永远红的用例。
 *
 * <p>可验证的形式是<b>差分</b>：同一场景跑两遍，一遍完全不驱动 Smart 恢复层、一遍在 {@code OBSERVE_ONLY} 下每 tick
 * 驱动它，账本演化必须完全一致。这个差分之所以能做，前提是 {@code 8c22b26} 把时间源变成可注入的——在那之前同一场景跑两遍结果本身就不一样。
 *
 * <h3>⚠️ 本用例目前是<b>未被检验</b>的绿</h3>
 *
 * <p>实测四种组合在两个场景（五站会让走廊、三站环形死锁）上<b>全部完全相同</b>：
 *
 * <pre>
 *   会让走廊  OFF/ENFORCE v=364   ON/ENFORCE v=364   OFF/OBSERVE v=1409  ON/OBSERVE v=1409
 *   环形死锁  四种组合一律 v=2706、claims=9、三车 progress 全 0
 * </pre>
 *
 * <p>也就是说：**驱动 Smart 恢复层在现有场景里对账本没有任何影响**，连 ENFORCE 模式下也没有。 所以"OBSERVE_ONLY
 * 下相等"并不能证明观察层守住了边界——它根本没有机会越界。实服里 Smart 层是会 动手的（一轮 80 次 unlock 预约），骨架里一次都没有。
 *
 * <p><b>要让这条不变量真正吃上力，需要先造出一个"ENFORCE 下 Smart 层确实改变账本"的场景</b> （届时 ENFORCE 的 ON/OFF 必须不同，而
 * OBSERVE_ONLY 的 ON/OFF 必须相同）。在那之前本用例只是 把当前行为钉住，防止将来有人让观察层开始写账本而无人察觉。
 *
 * <p>顺带记一个反直觉的观察，待查：<b>模式本身会改变账本活跃度</b>——会让走廊场景里 {@code OBSERVE_ONLY} 的 version 增量是 {@code ENFORCE}
 * 的近四倍（1409 vs 364）， 而两者的列车推进结果完全一样。像是某个门在 OBSERVE_ONLY 下反复重试。
 */
class DispatchObserverSideEffectTest {

  private static final int TICKS = 300;

  private DispatchScenarioHarness scenario(boolean driveSmartLayer, SmartDispatcherMode mode) {
    DispatchScenarioHarness.Topology topology =
        DispatchScenarioHarness.loopCorridor(List.of("A1", "B1", "C1", "D1", "E1"));
    List<NodeId> path = topology.physicalPath();
    return DispatchScenarioHarness.builder()
        .topology(topology)
        .smartRecoveryLayer(driveSmartLayer)
        .smartDispatcherMode(mode)
        .train("q0", "shared-route", path, topology.stations(), 0)
        .train("q1", "shared-route", path, topology.stations(), 1)
        .train("q2", "shared-route", path, topology.stations(), 2)
        .train("q3", "shared-route", path, topology.stations(), 3)
        .build();
  }

  /** 账本与各车终态的规范摘要——观察层碰了任何共享状态，这里就会不一样。 */
  private String ledgerDigest(DispatchScenarioHarness harness) {
    StringBuilder sb = new StringBuilder();
    sb.append("version=").append(harness.occupancy().version()).append('\n');
    harness
        .sortedClaims()
        .forEach(
            claim ->
                sb.append("  claim ")
                    .append(claim.resource())
                    .append(" <- ")
                    .append(claim.trainName())
                    .append(" role=")
                    .append(claim.role())
                    .append('\n'));
    harness
        .occupancy()
        .snapshotQueues()
        .forEach(
            queue ->
                sb.append("  queue ")
                    .append(queue.resource())
                    .append(" -> ")
                    .append(queue.entries())
                    .append('\n'));
    for (String name : harness.trainNames()) {
      sb.append("  train ")
          .append(name)
          .append(" pos=")
          .append(harness.positionOf(name).value())
          .append(" progress=")
          .append(harness.progressOf(name))
          .append(" signal=")
          .append(harness.signalOf(name))
          .append(" stop=")
          .append(harness.stopReasonOf(name))
          .append('\n');
    }
    return sb.toString();
  }

  private String runAndDigest(boolean driveSmartLayer, SmartDispatcherMode mode) {
    DispatchScenarioHarness harness = scenario(driveSmartLayer, mode);
    harness.runTicks(TICKS);
    return ledgerDigest(harness);
  }

  /** 先证明差分本身是可信的：不驱动 Smart 层时，同一场景两次运行必须完全一致。 */
  @Test
  void theBaselineItselfIsReproducible() {
    assertEquals(
        runAndDigest(false, SmartDispatcherMode.ENFORCE),
        runAndDigest(false, SmartDispatcherMode.ENFORCE),
        "不驱动 Smart 层时同一场景两次运行就不一致——差分无从谈起");
  }

  /**
   * I11：在 OBSERVE_ONLY 下驱动 Smart 恢复层，账本演化必须与完全不驱动它时一致。
   *
   * <p>见类注释：当前这条是<b>未被检验</b>的绿——观察层在本场景里没有机会越界。
   */
  @Test
  void observeOnlySmartLayerDoesNotChangeTheLedger() {
    String withoutSmartLayer = runAndDigest(false, SmartDispatcherMode.OBSERVE_ONLY);
    String withObserverOnly = runAndDigest(true, SmartDispatcherMode.OBSERVE_ONLY);

    assertEquals(
        withoutSmartLayer, withObserverOnly, "OBSERVE_ONLY 下驱动 Smart 恢复层改变了账本演化——观察层越过了诊断边界");
  }

  /**
   * 反空绿：现场必须真的活过。
   *
   * <p>它守住的是"两边都是空账本所以相等"这一种空绿；但守不住"观察层没有机会动手所以相等" ——后者正是当前的状况，只能靠类注释说明，没法用断言表达。
   */
  @Test
  void theFieldIsAliveSoTheDifferenceWouldBeVisible() {
    DispatchScenarioHarness harness = scenario(true, SmartDispatcherMode.OBSERVE_ONLY);
    harness.runTicks(TICKS);

    assertTrue(harness.occupancy().version() > 0, "账本整场没有变化过，差分比不出任何东西");
    assertTrue(
        harness.trainNames().stream().anyMatch(name -> harness.progressOf(name) > 0),
        "没有任何列车推进过：" + harness.describeState());
    assertTrue(!new ArrayList<>(harness.sortedClaims()).isEmpty(), "整场没有任何 claim");
  }
}
