package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

  private static final OccupancyResource BEHIND_NODE = OccupancyResource.forNode(NodeId.of("A"));

  /**
   * NODE 半边：同一条判据必须对节点同样成立，**两个方向都要成立**。
   *
   * <p>第十五轮实测：SURC-MT-LH-1650 在同一秒被释放了它够得着的那条 EDGE，却因为 {@code HHU:4:003}/{@code 004} 两个 NODE 留着继续卡了
   * 184 秒，并把 SURC-DS-LH-2216 一起堵了 62 秒。只接 EDGE 等于放过 blocker 的 75%。
   */
  @Test
  void nodeRetainFollowsTheSameCoverageRuleAsEdges() {
    SimpleOccupancyManager departed = managerHoldingNodeRetain();
    SimpleOccupancyManager.PhysicalEdgeRetainReleaseResult released =
        departed.releaseSelfOwnedPhysicalEdgeRetain("train-A", true, Set.of());
    assertEquals(1, released.releasedCount(), () -> "证明已离开的节点就该放：" + released);
    assertEquals(1, released.releasedNodes(), () -> "应记为 NODE 释放：" + released);
    assertEquals(0, released.releasedEdges(), () -> "不应误记成 EDGE：" + released);

    // 反方向：覆盖集合含该节点 ⇒ 车体可能压着 ⇒ 绝不放。
    SimpleOccupancyManager stillOn = managerHoldingNodeRetain();
    SimpleOccupancyManager.PhysicalEdgeRetainReleaseResult stillOnResult =
        stillOn.releaseSelfOwnedPhysicalEdgeRetain("train-A", true, Set.of(BEHIND_NODE));
    assertEquals(0, stillOnResult.releasedCount(), () -> "节点仍在覆盖集合里绝不许放：" + stillOnResult);
    assertEquals(1, stillOnResult.skippedStillCovered(), () -> "跳过原因要可归因：" + stillOnResult);

    assertNotEquals(
        released.releasedCount() > 0, stillOnResult.releasedCount() > 0, "节点的有证据/无证据也必须得到相反结果");
  }

  /** 缺证据时节点同样一个都不放——fail-closed 不因资源种类而不同。 */
  @Test
  void nodeRetainIsNotReleasedWhenCoverageIsIncomplete() {
    SimpleOccupancyManager unknown = managerHoldingNodeRetain();
    SimpleOccupancyManager.PhysicalEdgeRetainReleaseResult result =
        unknown.releaseSelfOwnedPhysicalEdgeRetain("train-A", false, Set.of());
    assertEquals(0, result.releasedCount(), () -> "缺证据不得当成已离开：" + result);
    assertEquals("coverage-incomplete", result.reason());
  }

  /** CONFLICT 仍然不走这条路径：它是抽象互斥键，没有"车体压没压着"这回事。 */
  @Test
  void conflictRetainStaysOutOfThePhysicalPath() {
    OccupancyResource conflict = OccupancyResource.forConflict("single:section:demo");
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyRequest request =
        new OccupancyRequest("train-A", Optional.empty(), NOW, List.of(conflict), Map.of(), 0)
            .withResourceIntents(Map.of(conflict, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(request).allowed(), "前置：必须先持有该 CONFLICT");

    SimpleOccupancyManager.PhysicalEdgeRetainReleaseResult result =
        manager.releaseSelfOwnedPhysicalEdgeRetain("train-A", true, Set.of());
    assertEquals(0, result.releasedCount(), () -> "CONFLICT 不该被物理路径释放：" + result);
  }

  /** 构造一辆在身后**节点**上持有 PROTECTIVE_RETAIN 的列车。 */
  private static SimpleOccupancyManager managerHoldingNodeRetain() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    OccupancyRequest request =
        new OccupancyRequest("train-A", Optional.empty(), NOW, List.of(BEHIND_NODE), Map.of(), 0)
            .withResourceIntents(Map.of(BEHIND_NODE, ResourceIntent.PROTECTIVE_RETAIN));
    assertTrue(manager.acquire(request).allowed(), "前置：必须先持有该节点");
    return manager;
  }

  /**
   * 外部排队对本路径而言**不存在**——这是一条把假设钉死的用例。
   *
   * <p>代码里有一道"该资源上有别人排队就不放"的防御，读起来像是会生效的。实际上不会：队列只为 {@code CONFLICT} 建立（{@code isQueueableConflict}
   * 开头就 {@code kind != CONFLICT} 返回 false）， 而这条路径只看 EDGE/NODE，于是 {@code queues.get(resource)} 恒为
   * null，那道防御是死代码。
   *
   * <p>钉住它有两个用处：避免再有人（包括我）把"卡住"归因到这条排队规则上；以及一旦将来 NODE/EDGE 也进队列，{@code releasedDespiteQueue} 会立刻从 0
   * 变成非 0，行为变化不会无声发生。
   */
  @Test
  void externalQueueDoesNotExistForNodeOrEdgeResources() {
    SimpleOccupancyManager manager = managerHoldingNodeRetain();
    OccupancyRequest waiting =
        new OccupancyRequest("train-B", Optional.empty(), NOW, List.of(BEHIND_NODE), Map.of(), 0)
            .withResourceIntents(Map.of(BEHIND_NODE, ResourceIntent.MOVEMENT_REQUIRED));
    assertFalse(manager.acquire(waiting).allowed(), "前置：train-B 应当拿不到该节点");

    SimpleOccupancyManager.PhysicalEdgeRetainReleaseResult result =
        manager.releaseSelfOwnedPhysicalEdgeRetain("train-A", true, Set.of());

    assertEquals(1, result.releasedCount(), () -> "证明已离开就该放：" + result);
    assertEquals(
        0,
        result.releasedDespiteQueue(),
        () -> "NODE/EDGE 上根本不会有队列；此处非 0 说明队列语义变了，需重新审查这条路径：" + result);
  }
}
