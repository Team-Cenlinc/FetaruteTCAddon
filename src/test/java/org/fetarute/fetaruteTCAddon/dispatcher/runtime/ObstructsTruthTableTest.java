package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.junit.jupiter.api.Test;

/**
 * 「什么算阻塞」的真值表——本仓库反复栽跟头的那一处，钉死它。
 *
 * <p>这个判据必须<b>同时</b>看角色与资源种类。本会话已经在同一个缺陷上栽过两次：
 *
 * <ol>
 *   <li>{@code 67ef652}：{@code UNLOCK_RESERVATION} 被当成「持有」，而 {@code ResourceIntent} 自己写着 「不得阻塞正常行车
 *       admission」——证出了不存在的等待环；
 *   <li>本次：{@code PROTECTIVE_RETAIN} / {@code HOLD_ONLY} 在**抽象** CONFLICT 键上被当成阻塞，
 *       而仓库不变量是它们只在**物理** NODE/EDGE 上才硬。同一个缺陷的第二个实例。
 * </ol>
 *
 * <p>真值表用例的价值就在于此：它不测某一条路径，而是把<b>全部 7 个角色 × 两类资源</b>都列出来。 漏掉一维时，逐条写的用例很容易恰好都在同一维上。
 */
class ObstructsTruthTableTest {

  private static final OccupancyResource ABSTRACT_SINGLE =
      OccupancyResource.forConflict("single:section:bridge:A~B");
  private static final OccupancyResource ABSTRACT_SWITCHER =
      OccupancyResource.forConflict("switcher:SWITCHER:Towny:1:64:1");
  private static final OccupancyResource PHYSICAL_NODE =
      OccupancyResource.forNode(NodeId.of("OP:S:ALFA:1"));
  private static final OccupancyResource PHYSICAL_EDGE =
      OccupancyResource.forEdge(
          EdgeId.undirected(NodeId.of("OP:S:ALFA:1"), NodeId.of("OP:S:BRAVO:1")));

  /** 物理空间上的真值表。 */
  @Test
  void physicalResources() {
    for (OccupancyResource r : new OccupancyResource[] {PHYSICAL_NODE, PHYSICAL_EDGE}) {
      assertEquals(true, OccupancyClaimEvidence.obstructs(ClaimRole.MOVEMENT_REQUIRED, r));
      assertEquals(true, OccupancyClaimEvidence.obstructs(ClaimRole.PHYSICAL_FOOTPRINT, r));
      assertEquals(
          true, OccupancyClaimEvidence.obstructs(ClaimRole.PROTECTIVE_RETAIN, r), "物理空间上尾部保护是硬的");
      assertEquals(true, OccupancyClaimEvidence.obstructs(ClaimRole.HOLD_ONLY, r));
      assertEquals(false, OccupancyClaimEvidence.obstructs(ClaimRole.QUEUE_POSITION, r));
      assertEquals(false, OccupancyClaimEvidence.obstructs(ClaimRole.LOOKAHEAD_PREVIEW, r));
      assertEquals(false, OccupancyClaimEvidence.obstructs(ClaimRole.UNLOCK_RESERVATION, r));
    }
  }

  /** 抽象冲突键上的真值表——差别只在 PROTECTIVE_RETAIN / HOLD_ONLY 两行。 */
  @Test
  void abstractConflictResources() {
    for (OccupancyResource r : new OccupancyResource[] {ABSTRACT_SINGLE, ABSTRACT_SWITCHER}) {
      assertEquals(true, OccupancyClaimEvidence.obstructs(ClaimRole.MOVEMENT_REQUIRED, r));
      assertEquals(true, OccupancyClaimEvidence.obstructs(ClaimRole.PHYSICAL_FOOTPRINT, r));
      assertEquals(
          false,
          OccupancyClaimEvidence.obstructs(ClaimRole.PROTECTIVE_RETAIN, r),
          "抽象 single:/switcher: 上尾部保护不挡人——这是仓库不变量");
      assertEquals(false, OccupancyClaimEvidence.obstructs(ClaimRole.HOLD_ONLY, r));
      assertEquals(false, OccupancyClaimEvidence.obstructs(ClaimRole.QUEUE_POSITION, r));
      assertEquals(false, OccupancyClaimEvidence.obstructs(ClaimRole.LOOKAHEAD_PREVIEW, r));
      assertEquals(false, OccupancyClaimEvidence.obstructs(ClaimRole.UNLOCK_RESERVATION, r));
    }
  }

  /** 缺证据时闭向：角色或资源为空一律不算阻塞（它会被用来**证明**环，宁可证不出也不要错证）。 */
  @Test
  void missingInputsNeverObstruct() {
    assertFalse(OccupancyClaimEvidence.obstructs(null, PHYSICAL_NODE));
    assertFalse(
        OccupancyClaimEvidence.obstructs(ClaimRole.PROTECTIVE_RETAIN, null), "资源未知时不得当成物理空间");
  }
}
