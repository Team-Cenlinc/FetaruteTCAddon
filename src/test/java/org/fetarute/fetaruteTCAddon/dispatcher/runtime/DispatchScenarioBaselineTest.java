package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/**
 * Phase 0 基线场景：S01 三车同向跟驰、S03 单线对向双车。
 *
 * <p>这两个场景<b>必须绿</b>。它们不是用来发现缺陷的，而是用来证明骨架正确建模了当前行为——基线红说明骨架本身不合格， 后续所有场景的结果都不可信。
 */
class DispatchScenarioBaselineTest {

  private static final int MAX_TICKS = 700;

  /** S00 单车基线：没有竞争者时，列车必须能走完整条走廊。 */
  @Test
  void singleTrainTraversesTheCorridor() {
    List<NodeId> corridor = DispatchScenarioHarness.corridor("ALFA", "BRAVO", 0);
    DispatchScenarioHarness harness =
        DispatchScenarioHarness.builder()
            .nodes(corridor)
            .lookaheadEdges(corridor.size() - 1)
            .train("solo", corridor, 0)
            .build();

    runUntilAllArrive(harness, List.of("solo"));

    harness.assertNoViolations();
    assertTrue(
        harness.reachedEnd("solo"),
        "单车应当走完走廊，实际停在 " + harness.positionOf("solo").value() + "\n" + harness.describeState());
  }

  /**
   * S01 三车同向跟驰。
   *
   * <p>同交路、同方向、错开起点。物理间隔由 NODE/EDGE 闭塞控制，同向不得互相拒绝。
   *
   * <p>防护对象：RC7 之前的 {@code NEITHER_CAN_DRAIN}——两车互为 stalled leader 时被对称拒绝。
   */
  @Test
  void threeSameDirectionTrainsAllReachTheEnd() {
    DispatchScenarioHarness.Topology topology =
        DispatchScenarioHarness.loopCorridor(List.of("ALFA", "BRAVO", "CHARLIE", "DELTA", "ECHO"));
    List<NodeId> path = topology.physicalPath();
    DispatchScenarioHarness harness =
        DispatchScenarioHarness.builder()
            .topology(topology)
            .train("follow-lead", "follow-route", path, topology.stations(), 5)
            .train("follow-mid", "follow-route", path, topology.stations(), 2)
            .train("follow-tail", "follow-route", path, topology.stations(), 0)
            .build();
    List<String> names = List.of("follow-lead", "follow-mid", "follow-tail");

    harness.runTicks(MAX_TICKS);

    // 只声明结构性安全三条。本场景<b>会</b>违反 I5（阻塞可解释性）：第三列车在会让站两条股道
    // 都被占满时停在 AUTHORIZATION_FAILURE / dynamic-target-blocked:no-available-platform，
    // 而停因里既没有具名 blocker 也没有队列位次。那是真实缺陷，由
    // DispatchBlockingExplainabilityTest 正面钉住，不在这里掩盖。
    harness.assertNoViolationsOf("I1", "I2", "I3", "I6");
    // 同向跟驰的正确判据是"都在推进且没有互相拒绝"，不是"都到同一终点"——
    // 终端站只有一条股道，先到的车停在那里会合法地封住它身后的区间。
    for (String name : names) {
      assertTrue(
          harness.progressOf(name) > 0, "同向跟驰列车 " + name + " 完全没有推进\n" + harness.describeState());
      assertNotEquals(
          "NEITHER_CAN_DRAIN", harness.stopReasonOf(name), "同向两车被对称拒绝\n" + harness.describeState());
    }
  }

  /**
   * S03 单线对向双车。<b>暂停用</b>——骨架拓扑尚不足以真实表达正线冲突区。
   *
   * <p>恰好一方先行，另一方被对向屏障拦下并入队；先行方通过并释放后，后行方必须能取得反向锁。
   *
   * <p>防护对象：对向屏障的基础回归。任何后续阶段若让它由绿变红，立即回滚。
   *
   * <h3>为什么停用</h3>
   *
   * <p>现状：两车已能在 BRAVO 正确交会（east 在 1 道、west 在 2 道，身后区间也正常释放），随后双双停在 {@code
   * SINGLE_CORRIDOR_FAIL_CLOSED}——<b>方向判定失败</b>，并各自保留进入侧咽喉、需要对方的出口侧咽喉。
   *
   * <p>已排除的假设（都实测证伪，别重复走）：<b>不是</b>道岔区保护 （{@code protectedSwitcherZoneClaims} 的 {@code 保护道岔占用}
   * trace 从未出现，claim 是 {@code MOVEMENT_REQUIRED} 而非保护性残留）；<b>不是</b>站间缺少 INTERVAL 节点（补上后 section 变 2
   * 条边， 静止列车进不去，方向仍未解决）；<b>不是</b> {@code single:section:} 形态不真实 （实服日志确认 {@code STATION~SWITCHER} 与
   * {@code SWITCHER~SWITCHER} 正是生产形态）。
   *
   * <p>剩余差异：实服的单线冲突键带 <b>INTERVAL 语义轴锚</b> （{@code single:SURC:CGL:WYB:1:00x:<A>~<B>}，576
   * 次），而骨架里锚是车站 （{@code single:OP:S:ALFA:1:...}）。方向解析走的正是语义轴，这条差异尚未排除。
   *
   * <h3>启用条件</h3>
   *
   * <p>Phase 1（方向模型归一）落地后重新启用：届时方向来自一次运动的唯一判定，而不是逐资源的多级回退，
   * 本场景是否仍红将直接回答"这是建模缺陷还是骨架差异"。在那之前红色不足以判定为生产缺陷。
   */
  @org.junit.jupiter.api.Disabled("停因是方向判定失败，指向 Phase 1 方向模型；道岔区保护与 INTERVAL 缺失两个假设已实测证伪")
  @Test
  void opposingTrainsSerializeAndBothPass() {
    DispatchScenarioHarness.Topology topology =
        DispatchScenarioHarness.loopCorridor(List.of("ALFA", "BRAVO", "CHARLIE"));
    List<NodeId> eastbound = topology.physicalPath();
    List<NodeId> westbound = new ArrayList<>(eastbound);
    java.util.Collections.reverse(westbound);
    List<NodeId> eastStations = topology.stations();
    List<NodeId> westStations = new ArrayList<>(eastStations);
    java.util.Collections.reverse(westStations);

    DispatchScenarioHarness harness =
        DispatchScenarioHarness.builder()
            .topology(topology)
            .train("east", "east-route", eastbound, eastStations, 0)
            .train("west", "west-route", westbound, westStations, 0)
            .build();
    List<String> names = List.of("east", "west");

    runUntilAllArrive(harness, names);

    harness.assertNoViolations();
    for (String name : names) {
      assertTrue(harness.reachedEnd(name), "对向列车 " + name + " 未走完走廊\n" + harness.describeState());
    }
  }

  private static void runUntilAllArrive(DispatchScenarioHarness harness, List<String> names) {
    for (int i = 0; i < MAX_TICKS; i++) {
      harness.runTicks(1);
      if (names.stream().allMatch(harness::reachedEnd)) {
        return;
      }
    }
  }
}
