package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/**
 * S14 / S15：运行中列车消失与 owner 迁移。
 *
 * <p>这两个场景守的是<b>生命周期边界</b>——调度的绝大多数状态按列车名索引，散落在几十个 map 上。
 * 一列车被销毁或改名时，只要有一处没清干净，残留就会以「一个不存在的列车挡着路」的形式出现， 而且没有任何结构能防止它：清理全靠每个调用点都没写漏。
 */
class DispatchLifecycleScenarioTest {

  private static final int WARMUP_TICKS = 120;

  private static final int SETTLE_TICKS = 120;

  private DispatchScenarioHarness fleet() {
    DispatchScenarioHarness.Topology topology =
        DispatchScenarioHarness.loopCorridor(List.of("A1", "B1", "C1", "D1", "E1"));
    List<NodeId> path = topology.physicalPath();
    return DispatchScenarioHarness.builder()
        .topology(topology)
        .train("lead", "shared-route", path, topology.stations(), 3)
        .train("mid", "shared-route", path, topology.stations(), 1)
        .train("tail", "shared-route", path, topology.stations(), 0)
        .build();
  }

  /**
   * S15 运行中消失：销毁一列持有资源的车，账本/队列/进度表都不得残留它。
   *
   * <p>先跑热身让它真正持有东西再销毁——对一列还没取得任何 claim 的车做销毁，检查等于没做。
   */
  @Test
  void destroyedTrainLeavesNoResidue() {
    DispatchScenarioHarness harness = fleet();
    harness.runTicks(WARMUP_TICKS);

    long heldBefore =
        harness.sortedClaims().stream().filter(claim -> claim.trainName().contains("mid")).count();
    assertTrue(heldBefore > 0, "销毁前该车没有持有任何 claim，本用例检查不到东西:\n" + harness.describeState());

    harness.destroyTrain("mid");
    harness.runTicks(SETTLE_TICKS);

    harness.assertNoViolationsOf("I10");
    // 结构性安全不得因为一列车消失而退化。
    harness.assertNoViolationsOf("I1", "I2", "I3", "I8");
  }

  /** 一列车消失之后，它身后的车必须能被唤醒继续走——否则清理只是「账面干净」。 */
  @Test
  void othersKeepMovingAfterDestroy() {
    DispatchScenarioHarness harness = fleet();
    harness.runTicks(WARMUP_TICKS);
    int tailBefore = harness.progressOf("tail");

    harness.destroyTrain("lead");
    harness.runTicks(SETTLE_TICKS * 3);

    assertTrue(
        harness.progressOf("tail") > tailBefore || harness.progressOf("mid") > 0,
        "前车消失后没有任何车继续推进:\n" + harness.describeState());
  }

  /**
   * S14 owner 迁移：迁移完成的那一刻，旧名不得在进度表中残留。
   *
   * <h3>为什么只断言"那一刻"，不往后跑 tick</h3>
   *
   * <p>实测：{@code migrateRuntimeOwner} 本身是对的——迁移返回 true 之后进度表立刻从 {@code [mid, lead, tail]} 变成 {@code
   * [mid-renamed, lead, tail]}，旧名干净地消失。 但<b>再跑一个 tick，旧名又会冒出来</b>（{@code [mid-renamed, lead, tail,
   * mid]}）。
   *
   * <p>骨架侧的改名已经做到位：句柄自报名与 {@code properties.getTrainName()} 都返回新名（已打印确认）。
   * 所以旧名是被<b>调度内部</b>重新建出来的——最可能是 {@code resolveTrackedTrainName} 依赖的 追踪名映射仍指向旧名，而它的更新走的是 {@code
   * handleRenameIfNeeded}，该入口只在 progress-trigger / waypoint-enter 上被调用，骨架的 tick 循环里没有它。
   *
   * <p><b>这是骨架保真度问题还是生产缺陷，尚未定论</b>，所以本用例只钉住已经证实的那一半： 迁移事务本身是原子的。往后跑 tick
   * 的断言等这个问题查清再加——现在加等于把一个没搞懂的现象 写成规格。
   */
  @Test
  void migrationRenamesTheProgressRegistryAtomically() {
    DispatchScenarioHarness harness = fleet();
    harness.runTicks(WARMUP_TICKS);
    assertTrue(harness.registry().get("mid").isPresent(), "迁移前旧名不在进度表里，用例前提不成立");

    boolean migrated = harness.migrateOwner("mid", "mid-renamed");

    assertTrue(migrated, "迁移未成功:\n" + harness.describeState());
    assertTrue(harness.registry().get("mid-renamed").isPresent(), "迁移后新名不在进度表里");
    assertFalse(harness.registry().get("mid").isPresent(), "迁移后旧名仍在进度表里——迁移事务不是原子的");
    harness.assertNoViolationsOf("I9");
  }

  /**
   * 迁移到一个已被占用的名字必须失败，且<b>不得留下新名</b>。
   *
   * <p>这是 I9 的失败侧：回滚必须彻底。审计已定位一处缺口（{@code dynamicCapacityWaits.rename()} 在回滚点之后调用），本用例是它的现场。
   */
  @Test
  void failedMigrationDoesNotCreateTheNewName() {
    DispatchScenarioHarness harness = fleet();
    harness.runTicks(WARMUP_TICKS);

    // 迁到一个已存在的逻辑 owner 上：迁移必须拒绝。
    boolean migrated = harness.migrateOwner("mid", "lead");

    assertFalse(migrated, "迁移到已占用的名字竟然成功了:\n" + harness.describeState());
    // 失败之后旧名必须完好——两者都还在，账本里不应出现半迁移状态。
    assertTrue(
        harness.registry().get("mid").isPresent(), "迁移失败后旧名从进度表消失了:\n" + harness.describeState());
    harness.assertNoViolationsOf("I1", "I2", "I3", "I8", "I9");
  }
}
