package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.junit.jupiter.api.Test;

/**
 * 停车明细必须说出**什么挡着**，而不是拿别的东西伪装成结论。
 *
 * <p>{@code PROTECTIVE_RETAIN_HOLD} 的明细此前只写 {@code no-self-retain-candidate}——那说的是
 * <b>恢复动作没找到可释放的自持保留</b>，不是阻塞原因。真正挡着的是别人压在物理 NODE/EDGE 上的尾部保护。
 *
 * <p><b>代价是真实发生过的</b>：第二十轮它以 562 次 / 37 辆车占据阻塞榜首，而这个名字让我整整查了一轮 ——看上去像恢复层失效，实际上 Phase 4 一直在正常释放（155
 * 次，每次 2~10 个资源）。 本仓库红线原文：明细要么是原因，要么自报「我没有原因」，<b>绝不许伪装成结论</b>。
 */
class ProtectiveRetainHoldDetailTest {

  /** 形态描述要按 {@code KIND/ROLE} 去重排序，且**不带列车名**（明细会进去重键）。 */
  @Test
  void blockerShapesAreSortedDeduplicatedAndCarryNoTrainName() {
    String shapes =
        OccupancyClaimEvidence.describeBlockerShapes(
            List.of(
                claim(
                    OccupancyResource.forNode(NodeId.of("OP:S:JBS:1")),
                    "SURC-MT-LP-6738",
                    ClaimRole.PROTECTIVE_RETAIN),
                claim(
                    OccupancyResource.forEdge(
                        EdgeId.undirected(NodeId.of("OP:S:JBS:1"), NodeId.of("OP:S:SPB:1"))),
                    "SURC-MT-LP-6738",
                    ClaimRole.PROTECTIVE_RETAIN),
                // 重复形态只应出现一次。
                claim(
                    OccupancyResource.forNode(NodeId.of("OP:S:SPB:1")),
                    "SURC-MT-LP-9999",
                    ClaimRole.PROTECTIVE_RETAIN)));

    assertEquals("EDGE/PROTECTIVE_RETAIN,NODE/PROTECTIVE_RETAIN", shapes);
    assertTrue(!shapes.contains("6738") && !shapes.contains("9999"), "明细不得带列车名");
  }

  /** 拒了却没有任何 blocker 时要显式自报，不许静默给出一个空形态。 */
  @Test
  void emptyBlockersReportThemselvesExplicitly() {
    assertEquals("no-blockers-listed", OccupancyClaimEvidence.describeBlockerShapes(List.of()));
    assertEquals("no-blockers-listed", OccupancyClaimEvidence.describeBlockerShapes(null));
  }

  private static OccupancyClaim claim(OccupancyResource resource, String train, ClaimRole role) {
    return new OccupancyClaim(
        resource,
        train,
        Optional.empty(),
        Instant.parse("2026-01-01T00:00:00Z"),
        Duration.ZERO,
        Optional.empty(),
        role);
  }
}
