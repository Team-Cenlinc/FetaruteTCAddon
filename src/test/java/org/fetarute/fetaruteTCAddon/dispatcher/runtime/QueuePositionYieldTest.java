package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.junit.jupiter.api.Test;

/**
 * 割等待环上的排队边。
 *
 * <p>形态（第十七轮实服，MT 线整条被掐死 47 分钟）：
 *
 * <pre>
 *   MT-LH-3340  持有 switcher:637(MOVEMENT_REQUIRED)，想要 643
 *               ← 被 MT-LP-0838 在 643 上的 QUEUE_POSITION 挡住
 *   MT-LP-0838  在 643 排队（并不持有它），想要 637
 *               ← 被 MT-LH-3340 的 MOVEMENT_REQUIRED 挡住
 * </pre>
 *
 * 0838 永远排不到 643，因为它要的 637 在 3340 手里；3340 又被这个排队位挡着。 两车各卡 2839 / 2700 秒，等待图检测到该环 <b>1455
 * 次</b>，而当时三个已实现的恢复动作 没有一个能割它——环上两条边一条是 MOVEMENT_REQUIRED、一条是 QUEUE_POSITION， 而那三个动作都只处理**自持**资源。
 *
 * <p>为什么割排队边安全：{@code SimpleOccupancyManager.createQueueBlocker} 自己的注释写着 「该 blocker
 * 不代表物理占用或已授予的行车权」，{@code physicalOccupancyText} 与 {@code reservedAuthorityText} 对 {@code
 * QUEUE_POSITION} 都返回 {@code "false"}。 撤销它不可能造成共占，代价只是队列公平性。
 */
class QueuePositionYieldTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  // ---------- 判定（纯函数，与拓扑无关） ----------

  /**
   * 判别核心：**成环与不成环必须得到相反的结果**。
   *
   * <p>只断言"能割"是不够的——那证明不了它不会去割正当排队的车，而那才是这条判据的风险面。
   */
  @Test
  void yieldsOnlyWhenTheCycleIsProven() {
    // 成环：A 只被 B 的排队位挡着，而 B 被 A 持有的资源挡着。
    Optional<RuntimeDispatchService.QueueYieldTarget> proven =
        RuntimeDispatchService.findProvenQueueCycle(
            "train-A",
            Set.of(blocker("train-B", "CONFLICT:switcher:S643", "QUEUE_POSITION")),
            owner -> Set.of(blocker("train-A", "CONFLICT:switcher:S637", "MOVEMENT_REQUIRED")),
            Set.of("CONFLICT:switcher:S637"));
    assertTrue(proven.isPresent(), "A 只被排队挡、而排队者被 A 持有的资源挡 —— 必须割");
    assertEquals("train-B", proven.get().queueOwner());
    assertEquals("CONFLICT:switcher:S643", proven.get().resourceKey());

    // 不成环：排队者自己没被挡 —— 它排队是正当的，等它。
    assertFalse(
        RuntimeDispatchService.findProvenQueueCycle(
                "train-A",
                Set.of(blocker("train-B", "CONFLICT:switcher:S643", "QUEUE_POSITION")),
                owner -> Set.of(),
                Set.of("CONFLICT:switcher:S637"))
            .isPresent(),
        "排队者没被挡时不得割它的位次");

    // 不成环：排队者被挡，但挡它的与 A 无关。
    assertFalse(
        RuntimeDispatchService.findProvenQueueCycle(
                "train-A",
                Set.of(blocker("train-B", "CONFLICT:switcher:S643", "QUEUE_POSITION")),
                owner -> Set.of(blocker("train-C", "CONFLICT:switcher:S999", "MOVEMENT_REQUIRED")),
                Set.of("CONFLICT:switcher:S637"))
            .isPresent(),
        "环没闭合时不得割");
  }

  /**
   * A 只要还被**任何真实占用**挡着，就不许割队列。
   *
   * <p>那时割队列既解不开问题（真正挡路的还在），又白白牺牲别人的排队公平性。
   */
  @Test
  void refusesWhenAnyRealOccupancyAlsoBlocks() {
    assertFalse(
        RuntimeDispatchService.findProvenQueueCycle(
                "train-A",
                Set.of(
                    blocker("train-B", "CONFLICT:switcher:S643", "QUEUE_POSITION"),
                    blocker("train-C", "NODE:X", "MOVEMENT_REQUIRED")),
                owner -> Set.of(blocker("train-A", "CONFLICT:switcher:S637", "MOVEMENT_REQUIRED")),
                Set.of("CONFLICT:switcher:S637"))
            .isPresent(),
        "还有真实占用挡着时不得割队列");
  }

  @Test
  void emptyInputsYieldNothing() {
    assertFalse(
        RuntimeDispatchService.findProvenQueueCycle(
                "train-A", Set.of(), owner -> Set.of(), Set.of())
            .isPresent());
    assertFalse(
        RuntimeDispatchService.findProvenQueueCycle(null, null, owner -> Set.of(), Set.of())
            .isPresent());
  }

  // ---------- 账本原语 ----------

  /** 撤销排队位次必须真的把它从队列里拿掉，且不动任何 claim。 */
  @Test
  void yieldRemovesTheQueueEntryAndNothingElse() {
    // 必须用 CONFLICT：队列只为 switcher: / single: / interlocking 这类冲突键建立
    // （见 isQueueableConflict），普通 EDGE/NODE 上根本不存在队列。
    OccupancyResource shared = OccupancyResource.forConflict("switcher:TEST-SWITCHER");
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    assertTrue(manager.acquire(request("holder", shared)).allowed(), "前置：holder 先占住");
    assertFalse(manager.acquire(request("waiter", shared)).allowed(), "前置：waiter 应进队列");

    SimpleOccupancyManager.QueuePositionYieldResult yielded =
        manager.yieldQueuePosition(shared, "waiter");
    assertTrue(yielded.removed(), () -> "应当撤销排队位次：" + yielded);

    // 再撤一次应当是 no-op，而不是报成功。
    SimpleOccupancyManager.QueuePositionYieldResult again =
        manager.yieldQueuePosition(shared, "waiter");
    assertFalse(again.removed(), () -> "重复撤销不得报成功：" + again);

    // holder 的 claim 一个都不能少 —— 撤队列不碰占用。
    assertTrue(
        manager.snapshotClaims().stream()
            .anyMatch(c -> shared.equals(c.resource()) && c.trainName().equals("holder")),
        "撤销排队位次不得动到任何 claim");
  }

  private static RuntimeDispatchService.DeadlockBlockerInfo blocker(
      String owner, String resourceKey, String role) {
    return new RuntimeDispatchService.DeadlockBlockerInfo(
        owner, resourceKey, Optional.empty(), owner, resourceKey, "-", "-", role, "test", 0L, 0L);
  }

  private static OccupancyRequest request(String train, OccupancyResource resource) {
    return new OccupancyRequest(train, Optional.empty(), NOW, List.of(resource), Map.of(), 0)
        .withResourceIntents(Map.of(resource, ResourceIntent.MOVEMENT_REQUIRED));
  }
}
