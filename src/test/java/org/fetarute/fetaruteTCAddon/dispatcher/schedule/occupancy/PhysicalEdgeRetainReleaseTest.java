package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/**
 * Phase 4：用**车体实测覆盖**释放身后区间的尾部保护。
 *
 * <p>为什么需要另起一条路径：既有回收机制开头就 {@code resource.kind() != ResourceKind.CONFLICT} 返回， 而实服
 * PROTECTIVE_RETAIN blocker 是 **NODE 1012 / EDGE 331 / CONFLICT 0**—— 判据与现实永不相交。实测第十三轮 {@code
 * selfRetainReleaseCandidate=false} **2286 / 2286，成功率 0**， 而 {@code PROTECTIVE_RETAIN_HOLD} 占全网滞留的
 * **38%**（260 车·分 / 691 车·分，70.5 分钟一轮）。
 *
 * <p>也不能复用既有的 {@code strictSelfOwnedProtectiveRetainCandidate}：它要求 {@code heldDirection !=
 * requestedDirection}，那是**反向自锁**语义；而"车已驶离身后区间"是**同向**。
 */
final class PhysicalEdgeRetainReleaseTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final OccupancyResource BEHIND =
      OccupancyResource.forEdge(EdgeId.undirected(NodeId.of("A"), NodeId.of("B")));

  /**
   * 判别核心：**同一份尾部保护，在"证明已离开"与"缺证据"两种情形下必须得到相反的结果**。
   *
   * <p>只断言"能释放"是不够的——那证明不了 fail-closed 那一半，而那一半正是红线： 车体还压着时释放 = co-occupancy。
   */
  @Test
  void edgeRetainIsReleasedOnlyWhenCoverageProvesTheTrainHasLeft() {
    // 一：覆盖完整且**不含**该区间 ⇒ 车已离开 ⇒ 放行。
    SimpleOccupancyManager departed = managerHoldingRetain();
    SimpleOccupancyManager.PhysicalEdgeRetainReleaseResult releasedResult =
        departed.releaseSelfOwnedPhysicalEdgeRetain("train-A", true, Set.of());
    assertEquals(1, releasedResult.releasedCount(), () -> "证明已离开就该放：" + releasedResult);

    // 二：覆盖完整但**仍含**该区间 ⇒ 车体还压着 ⇒ 不放（co-occupancy 红线）。
    SimpleOccupancyManager stillOn = managerHoldingRetain();
    SimpleOccupancyManager.PhysicalEdgeRetainReleaseResult stillOnResult =
        stillOn.releaseSelfOwnedPhysicalEdgeRetain("train-A", true, Set.of(BEHIND));
    assertEquals(0, stillOnResult.releasedCount(), () -> "车体还压着绝不许放：" + stillOnResult);

    // 三：覆盖**不完整** ⇒ 无从判断 ⇒ 一个都不放。
    SimpleOccupancyManager unknown = managerHoldingRetain();
    SimpleOccupancyManager.PhysicalEdgeRetainReleaseResult unknownResult =
        unknown.releaseSelfOwnedPhysicalEdgeRetain("train-A", false, Set.of());
    assertEquals(0, unknownResult.releasedCount(), () -> "缺证据不得当成已离开：" + unknownResult);
    assertEquals("coverage-incomplete", unknownResult.reason());

    // 判别点：有证据与无证据必须相反。恒真或恒假的实现会在这里失败。
    assertNotEquals(
        releasedResult.releasedCount() > 0, unknownResult.releasedCount() > 0, "有证据与缺证据必须得到相反结果");
  }

  // 说明：代码里还有一道"该区间上存在外部 claim 或外部排队时不释放"的防御性检查，
  // 但本夹具**构造不出来**——EDGE claim 是排他的，train-A 持有时 train-B 根本 acquire 不到。
  // 与其把夹具拧到能过（那样的用例不判别任何东西），不如在此说明它是防御性的、未被覆盖。

  /** 构造一辆在身后区间上持有 PROTECTIVE_RETAIN 的列车。 */
  private static SimpleOccupancyManager managerHoldingRetain() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyRequest request =
        new OccupancyRequest("train-A", Optional.empty(), NOW, List.of(BEHIND), Map.of(), 0)
            .withResourceIntents(Map.of(BEHIND, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(request).allowed(), "前置：必须先持有该区间");
    return manager;
  }
}
