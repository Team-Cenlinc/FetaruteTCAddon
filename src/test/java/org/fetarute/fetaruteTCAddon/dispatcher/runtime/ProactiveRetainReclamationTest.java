package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.junit.jupiter.api.Test;

/**
 * 列车驶离后**主动**回收尾部保护——不必等到有人被卡住。
 *
 * <p>Phase 4 的判据一直没问题，问题是**触发时机**：它只从 {@code TrainHealthMonitor} 的 progress-stuck 恢复链进来，而那条链 (a)
 * 要求已经有车停滞，(b) 释放的是**那辆停滞车自己**的 retain。于是最常见的一种情形无人处理：列车 X 正常驶离站台、把 retain 留在身后继续跑， 列车 Y
 * 因此进不去——被卡的是 Y，对 Y 跑 Phase 4 动不了 X 的 retain。
 *
 * <p>第十六轮实服：停车快照里站台节点被持有 484 次 {@code MOVEMENT_REQUIRED}、321 次 {@code PROTECTIVE_RETAIN}；{@code
 * S:RVS:2 / S:JBS:2 / S:ZKW:1 / S:SLL:2 / S:CSB:1} 五个站台 **100% 只被尾保占着**，{@code S:PPK:1} 也有
 * 49%——那些站台里没有车。
 *
 * <p>注意本用例能证明什么、不能证明什么：它证明**机制在正常行车时会触发** （实测 periodic 8 次 vs 改动前 0 次），不证明吞吐改善——本场景只有三辆车、 尾保停车本来就是
 * 0，改动前后都一样。吞吐只能由实服回答。
 */
class ProactiveRetainReclamationTest {

  private static final int MAX_TICKS = 900;
  private static final int OBSERVE_ONLY_TICKS = 300;

  @Test
  void departedTrainsRetainsAreReclaimedWithoutAnyoneGettingStuck() {
    DispatchScenarioHarness harness = contendedLoop(SmartDispatcherMode.ENFORCE);

    harness.runTicks(MAX_TICKS);

    List<String> periodic = reclamations(harness, true);
    assertFalse(periodic.isEmpty(), () -> "周期回收一次都没触发——改动等于没生效\n" + harness.describeState());

    // 判别核心：必须带实测覆盖证据。少了它就无法区分"用覆盖证明车已离开"
    // 与"放宽了资源类型"，而后者是这条路径明令不许走的。
    assertTrue(
        periodic.stream().allMatch(line -> line.contains(" covered=")),
        () -> "回收没有带实测覆盖证据：\n  " + String.join("\n  ", periodic));
    assertTrue(
        periodic.stream().anyMatch(line -> line.contains(" nodes=") && !line.contains(" nodes=0 ")),
        () -> "NODE 半边没有参与回收：\n  " + String.join("\n  ", periodic));
  }

  /**
   * OBSERVE_ONLY 下不得产生任何回收——与恢复链走同一道效果闸。
   *
   * <p>这条是判别性的另一半：只断言"会回收"证明不了它受闸门约束，而一个绕过 mode 的 账本变更正是观察层越界。
   */
  @Test
  void observeOnlyModeReclaimsNothing() {
    DispatchScenarioHarness harness = contendedLoop(SmartDispatcherMode.OBSERVE_ONLY);

    // 只跑 OBSERVE_ONLY_TICKS。OBSERVE_ONLY 下不放行任何动作，线网按构造必然堵死，
    // 而堵死的多车场景每 tick 产生成百上千行诊断——跑满 900 tick 会把测试 JVM 撑爆
    // （实测老年代 2048MB 打满、827 次 Full GC、整个 test 任务挂死）。骨架的 BoundedLog
    // 注释早就写过这一点。这是骨架的容量上限，不是产品事实，因此缩短而不是删掉本用例：
    // 判据本身（mode 闸门是纯函数）在 300 tick 上与 900 tick 上完全等价——
    // ENFORCE 侧的首次回收远早于此。
    harness.runTicks(OBSERVE_ONLY_TICKS);

    List<String> periodic = reclamations(harness, true);
    assertTrue(
        periodic.isEmpty(), () -> "OBSERVE_ONLY 下发生了账本变更：\n  " + String.join("\n  ", periodic));
  }

  private static List<String> reclamations(DispatchScenarioHarness harness, boolean periodicOnly) {
    return harness.debugLog().stream()
        .filter(line -> line.contains("SMART_PHYSICAL_EDGE_RETAIN_RELEASED train="))
        .filter(line -> line.contains("source=periodic") == periodicOnly)
        .toList();
  }

  private static DispatchScenarioHarness contendedLoop(SmartDispatcherMode mode) {
    DispatchScenarioHarness.Topology topology =
        DispatchScenarioHarness.loopCorridor(List.of("ALFA", "BRAVO", "CHARLIE", "DELTA", "ECHO"));
    List<NodeId> path = topology.physicalPath();
    return DispatchScenarioHarness.builder()
        .topology(topology)
        .smartRecoveryLayer(true)
        .smartDispatcherMode(mode)
        .train("live-lead", "shared-route", path, topology.stations(), 5)
        .train("live-mid", "shared-route", path, topology.stations(), 2)
        .train("live-tail", "shared-route", path, topology.stations(), 0)
        .build();
  }
}
