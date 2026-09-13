package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    harness.assertNoViolationsOf("I1", "I2", "I3", "I4", "I6");
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
   * S03 单线对向双车。
   *
   * <h3>为什么曾经停用，以及为什么现在恢复</h3>
   *
   * <p>原停用理由是"停在 {@code SINGLE_CORRIDOR_FAIL_CLOSED}——方向判定失败，归 Phase 1"。<b>那个归因是错的。</b>
   * 真正的成因是骨架从不走生产的到站入口（见 {@link DispatchScenarioHarness} 的 {@code advance}）：route index 永远停在起点，
   * waypoint N 与 N+1 塌成同一节点，movement plan 不可构建，停因才显示为 {@code SINGLE_CORRIDOR_FAIL_CLOSED}。 补上
   * {@code handleStationArrival} 之后该停因在骨架里完全消失，本场景的进展推进到了完全不同的阶段。
   *
   * <p>另外，"实服冲突键带 INTERVAL 语义轴锚、骨架里是车站"这条残留差异也<b>已证伪</b>：{@code
   * RailGraphConflictIndex.buildCorridorKey} 里那段锚是 {@code resolveComponentKey} 给的<b>连通分量代表节点</b>，
   * 纯命名空间，不参与方向解析。实服恰好是 INTERVAL 节点当了分量最小值而已。
   *
   * <p>因此现在把它拆成两条：交会本身（安全，绿）与交会之后（liveness，红且已定位）。
   */
  @Test
  void opposingTrainsMeetWithoutCoOccupancy() {
    DispatchScenarioHarness harness = opposingPair();

    harness.runTicks(MAX_TICKS);

    // 安全侧全部成立：没有物理共占、单线方向没有被破坏、没有越界 claim、证据链没有断。
    harness.assertNoViolationsOf("I1", "I2", "I3", "I4", "I5", "I6");
    // 双方确实都进了会让环，并且分属不同股道——对向屏障做对了事，不是靠把谁挡在环外换来的。
    assertEquals("OP:S:BRAVO", stationGroupOf(harness.positionOf("east")), harness.describeState());
    assertEquals("OP:S:BRAVO", stationGroupOf(harness.positionOf("west")), harness.describeState());
    assertNotEquals(
        harness.positionOf("east"),
        harness.positionOf("west"),
        "两列对向车停在同一条股道上\n" + harness.describeState());
  }

  /**
   * S03 后半段：<b>当前已知缺陷</b>——交会成功之后双方被对方的尾部保护锁死。
   *
   * <p>形态固定：east 停在 BRAVO 的一条股道、west 停在另一条，双方停因都是 {@code PROTECTIVE_RETAIN_HOLD}， 而各自的 blocker
   * 正是<b>对方对其出发站的 {@code PROTECTIVE_RETAIN}</b>——east 仍保留 ALFA、west 仍保留 CHARLIE，
   * 而那恰好是对方要去的地方。两条尾部保护构成 2-环，谁都不动，于是谁都不释放。
   *
   * <p>安全没有被破坏（见上一条用例）；坏掉的是 liveness。这与实服 {@code self-owned-single-continuation-rejected}（本轮日志 119
   * 次，集中在段场出库道岔）很可能是同一族问题： 尾部保护的释放条件依赖"列车继续前进"，而列车恰恰因为它而停着。归属 Phase 4（全局仲裁需要能看见并打破这种环）。
   *
   * <p><b>修好后本用例会失败。</b>那时把它与上一条合并回"双方最终都通过"，而不是删掉。
   */
  @Test
  void opposingTrainsCurrentlyDeadlockOnRearRetainAfterMeeting() {
    DispatchScenarioHarness harness = opposingPair();

    harness.runTicks(MAX_TICKS);

    assertFalse(
        harness.reachedEnd("east") && harness.reachedEnd("west"),
        "对向双车已经都能走完走廊——尾部保护 2-环可能已修复。"
            + "请把本用例与 opposingTrainsMeetWithoutCoOccupancy 合并回'双方最终都通过'，而不是删掉。\n"
            + harness.describeState());
    for (String name : List.of("east", "west")) {
      assertEquals(
          "PROTECTIVE_RETAIN_HOLD",
          harness.stopReasonOf(name),
          "停因形态变了，成因需要重新归因: " + name + "\n" + harness.describeState());
    }
  }

  private DispatchScenarioHarness opposingPair() {
    DispatchScenarioHarness.Topology topology =
        DispatchScenarioHarness.loopCorridor(List.of("ALFA", "BRAVO", "CHARLIE"));
    List<NodeId> eastbound = topology.physicalPath();
    List<NodeId> westbound = new ArrayList<>(eastbound);
    java.util.Collections.reverse(westbound);
    List<NodeId> eastStations = topology.stations();
    List<NodeId> westStations = new ArrayList<>(eastStations);
    java.util.Collections.reverse(westStations);

    return DispatchScenarioHarness.builder()
        .topology(topology)
        .train("east", "east-route", eastbound, eastStations, 0)
        .train("west", "west-route", westbound, westStations, 0)
        .build();
  }

  private static void runUntilAllArrive(DispatchScenarioHarness harness, List<String> names) {
    for (int i = 0; i < MAX_TICKS; i++) {
      harness.runTicks(1);
      if (names.stream().allMatch(harness::reachedEnd)) {
        return;
      }
    }
  }

  private static String stationGroupOf(NodeId nodeId) {
    String[] parts = nodeId.value().split(":");
    return parts.length >= 3 ? parts[0] + ":" + parts[1] + ":" + parts[2] : nodeId.value();
  }
}
