package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalComputationTrace;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.OccupancyQueueChangedEvent;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.event.SignalEventBus;
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
        OccupancyClaimEvidence.findProvenQueueCycle(
            "train-A",
            Set.of(blocker("train-B", "CONFLICT:switcher:S643", "QUEUE_POSITION")),
            owner -> Set.of(blocker("train-A", "CONFLICT:switcher:S637", "MOVEMENT_REQUIRED")),
            Set.of("CONFLICT:switcher:S637"));
    assertTrue(proven.isPresent(), "A 只被排队挡、而排队者被 A 持有的资源挡 —— 必须割");
    assertEquals("train-B", proven.get().queueOwner());
    assertEquals("CONFLICT:switcher:S643", proven.get().resourceKey());

    // 不成环：排队者自己没被挡 —— 它排队是正当的，等它。
    assertFalse(
        OccupancyClaimEvidence.findProvenQueueCycle(
                "train-A",
                Set.of(blocker("train-B", "CONFLICT:switcher:S643", "QUEUE_POSITION")),
                owner -> Set.of(),
                Set.of("CONFLICT:switcher:S637"))
            .isPresent(),
        "排队者没被挡时不得割它的位次");

    // 不成环：排队者被挡，但挡它的与 A 无关。
    assertFalse(
        OccupancyClaimEvidence.findProvenQueueCycle(
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
        OccupancyClaimEvidence.findProvenQueueCycle(
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
        OccupancyClaimEvidence.findProvenQueueCycle(
                "train-A", Set.of(), owner -> Set.of(), Set.of())
            .isPresent());
    assertFalse(
        OccupancyClaimEvidence.findProvenQueueCycle(null, null, owner -> Set.of(), Set.of())
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

  /**
   * 多条候选边时，同一份输入必须永远割同一条。
   *
   * <p>{@code blockedBy} 是 {@code Set}，迭代顺序不保证稳定。不定的选择会让事后对着日志复盘 得到对不上的结论——而日志复盘是本项目诊断死锁唯一的手段。
   */
  @Test
  void picksTheSameEdgeEveryTimeWhenSeveralCloseTheCycle() {
    Set<RuntimeDispatchService.DeadlockBlockerInfo> candidates =
        Set.of(
            blocker("train-B", "CONFLICT:switcher:S900", "QUEUE_POSITION"),
            blocker("train-C", "CONFLICT:switcher:S100", "QUEUE_POSITION"),
            blocker("train-D", "CONFLICT:switcher:S500", "QUEUE_POSITION"));
    String first = null;
    for (int attempt = 0; attempt < 50; attempt++) {
      Optional<RuntimeDispatchService.QueueYieldTarget> target =
          OccupancyClaimEvidence.findProvenQueueCycle(
              "train-A",
              candidates,
              owner -> Set.of(blocker("train-A", "CONFLICT:switcher:S637", "MOVEMENT_REQUIRED")),
              Set.of("CONFLICT:switcher:S637"));
      assertTrue(target.isPresent());
      String chosen = target.get().resourceKey() + "/" + target.get().queueOwner();
      if (first == null) {
        first = chosen;
      }
      assertEquals(first, chosen, "同一输入必须永远割同一条边");
    }
    assertEquals("CONFLICT:switcher:S100/train-C", first, "顺序必须是可预测的（资源 key 升序）");
  }

  // ---------- 账本写入的配套动作 ----------

  /**
   * 割排队位必须**同时**发出生命周期痕迹与新队首资格。
   *
   * <p>只把队列项拿掉、不通知任何人，被解锁的车要等下一个周期 tick 才会被重新仲裁； 而被割的车每 tick 都在重新入队，很可能抢先把环恢复，这次割就白割了。
   */
  @Test
  void yieldPublishesTheNewQueueHeadAsEligible() {
    OccupancyResource shared = OccupancyResource.forConflict("switcher:TEST-PUBLISH");
    List<OccupancyQueueChangedEvent> queueEvents = new ArrayList<>();
    SignalEventBus bus = new SignalEventBus();
    bus.subscribe(OccupancyQueueChangedEvent.class, queueEvents::add);
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy(), bus);

    assertTrue(manager.acquire(request("holder", shared)).allowed(), "前置：holder 先占住");
    assertFalse(manager.acquire(request("cut-me", shared)).allowed(), "前置：cut-me 进队列队首");
    assertFalse(manager.acquire(request("behind", shared)).allowed(), "前置：behind 排在它后面");

    queueEvents.clear();
    assertTrue(manager.yieldQueuePosition(shared, "cut-me").removed());

    assertEquals(1, queueEvents.size(), () -> "割排队位必须发一条队列变更事件：" + queueEvents);
    OccupancyQueueChangedEvent event = queueEvents.get(0);
    assertTrue(event.affectedResources().contains(shared), "变更资源必须包含被割的那个");
    assertEquals(List.of("behind"), event.eligibleTrainNames(), "新队首必须被公布为新晋可仲裁，否则没人会去唤醒它");
  }

  // ---------- 接线（从 SmartRecoveryInput 到账本） ----------

  /**
   * 基准：ENFORCE 下确实会割。
   *
   * <p>没有这条，下面的 OBSERVE_ONLY 用例就是空的——"没改账本"可能只是因为场景根本没机会动手。 这正是 {@code
   * DispatchObserverSideEffectTest} 自己记下的那个坑。
   */
  @Test
  void enforceModeCutsTheQueueEdgeOnAProvenCycle() {
    Fixture fixture = new Fixture(SmartDispatcherMode.ENFORCE);

    RuntimeDispatchService.SmartRecoveryActionResult result =
        fixture.service.applySmartQueuePositionYield(fixture.blockedTrainInput());

    assertTrue(result.applied(), () -> "ENFORCE 下应当真的割：" + result.reason());
    assertFalse(
        fixture.manager.snapshotQueues().stream()
            .flatMap(queue -> queue.entries().stream())
            .anyMatch(entry -> QUEUE_OWNER.equals(entry.trainName())),
        "排队位次必须真的从账本里消失");
  }

  /**
   * OBSERVE_ONLY 下必须一步账本都不改。
   *
   * <p>排队位次虽然不是物理占用、也不是已授予的行车权，但它**就写在占用账本里**， 删它就是 {@code
   * OCCUPANCY_MUTATION}。观察模式的定义是零副作用，没有"反正不危险所以可以改"这种例外。
   */
  @Test
  void observeOnlyModeMutatesNothing() {
    Fixture fixture = new Fixture(SmartDispatcherMode.OBSERVE_ONLY);
    long versionBefore = fixture.manager.version();

    RuntimeDispatchService.SmartRecoveryActionResult result =
        fixture.service.applySmartQueuePositionYield(fixture.blockedTrainInput());

    assertFalse(result.applied(), () -> "OBSERVE_ONLY 不得落地：" + result.reason());
    assertEquals("suppressed-by-mode", result.reason());
    assertEquals(versionBefore, fixture.manager.version(), "OBSERVE_ONLY 下账本版本不得变化");
    assertTrue(
        fixture.manager.snapshotQueues().stream()
            .flatMap(queue -> queue.entries().stream())
            .anyMatch(entry -> QUEUE_OWNER.equals(entry.trainName())),
        "OBSERVE_ONLY 下排队位次必须原封不动");
  }

  /**
   * 快照过期就不再是证据。
   *
   * <p>等待环是从两份 blocker 快照拼出来的。超过 {@code BLOCKER_SNAPSHOT_TTL} 后对方可能早已走掉，
   * 此时再去割，割的就是一辆正当排队的车。这是本项目反复栅跟头的"把缺证据当成证据"。
   */
  @Test
  void staleBlockerSnapshotsAreNotProof() {
    Fixture fixture = new Fixture(SmartDispatcherMode.ENFORCE);
    // 15 秒这个数是**特意挑的**：它大于配置里的 blocker-snapshot-ttl-ms（测试默认 10s），
    // 却小于写死的 BLOCKER_SNAPSHOT_TTL（20s）。所以这条用例同时钉住两件事——
    // 过期要拒，以及**有效期必须取配置值**。改回读常量，它会变红。
    fixture.now = NOW.plusSeconds(15);

    RuntimeDispatchService.SmartRecoveryActionResult result =
        fixture.service.applySmartQueuePositionYield(fixture.blockedTrainInput());

    assertFalse(result.applied(), () -> "快照过期时不得割：" + result.reason());
    assertEquals("stale-blocker-snapshot", result.reason());
  }

  /**
   * 割完必须有冷却。
   *
   * <p>被割的车下一 tick 就会重新入队，环可能立刻复原。没有冷却就会退化成每轮健康检查 都割一次的抖动——本会话已经因这个形态撤回过一次改动（主动回收尾部保护，93 分钟 10603
   * 次）。
   */
  @Test
  void repeatedYieldOnTheSameEdgeIsRateLimited() {
    Fixture fixture = new Fixture(SmartDispatcherMode.ENFORCE);
    assertTrue(fixture.service.applySmartQueuePositionYield(fixture.blockedTrainInput()).applied());

    // 环复原：被割的车重新入队，快照也重新采样（新鲜）。
    fixture.now = NOW.plusSeconds(1);
    assertFalse(fixture.manager.acquire(request(QUEUE_OWNER, QUEUED_RESOURCE)).allowed());
    fixture.seedSnapshots();

    RuntimeDispatchService.SmartRecoveryActionResult second =
        fixture.service.applySmartQueuePositionYield(fixture.blockedTrainInput());
    assertFalse(second.applied(), () -> "冷却期内不得再割：" + second.reason());
    assertEquals("queue-yield-cooldown", second.reason());

    // 冷却过期后可以再割——冷却只是限速，不是永久禁用。
    fixture.now = NOW.plusSeconds(62);
    fixture.seedSnapshots();
    RuntimeDispatchService.SmartRecoveryActionResult third =
        fixture.service.applySmartQueuePositionYield(fixture.blockedTrainInput());
    assertTrue(third.applied(), () -> "冷却过期后应当可以再割：" + third.reason());
  }

  /**
   * 不挡人的 claim 不算"持有"，不能拿来证环。
   *
   * <p>这里用 {@code UNLOCK_RESERVATION}，因为它是真的会写进 claims 的那个（{@code LOOKAHEAD_PREVIEW} 目前压根不会）。而
   * {@link
   * org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent#UNLOCK_RESERVATION}
   * 自己的文档写着「不得阻塞正常行车 admission」——它挡不住排队者，就不能用它来闭合等待环。 把它当成持有就会把一个**并不存在**的环判成已证明，继而去割一辆正当排队的车。
   */
  @Test
  void nonBlockingClaimsDoNotCountAsHolding() {
    Fixture fixture = new Fixture(SmartDispatcherMode.ENFORCE, ResourceIntent.UNLOCK_RESERVATION);

    RuntimeDispatchService.SmartRecoveryActionResult result =
        fixture.service.applySmartQueuePositionYield(fixture.blockedTrainInput());

    assertFalse(result.applied(), () -> "不挡人的 claim 不得被当成持有：" + result.reason());
    assertEquals("blocked-train-holds-nothing", result.reason());
  }

  /**
   * 每一个跳过出口都必须留下原因。
   *
   * <p>第十八轮实服这个动作一次都没落地，而日志里**分不出**它是根本没走到、还是走到了默默拒了—— 八个 {@code skipped(...)} 出口一条日志都没有，而 {@code
   * candidate=false} 让调用方的 {@code SMART_RECOVERY_DECISION} 也不会打。“计数为 0”于是成了一个无法落地的结论。
   *
   * <p>这条用例钉的不是某一句文案，而是**跳过必须可观测**：拿两个走不同分支的场景， 各自的原因都要能从日志里读出来。
   */
  @Test
  void everySkipLeavesItsReasonInTheLog() {
    Fixture noCycle = new Fixture(SmartDispatcherMode.ENFORCE, ResourceIntent.UNLOCK_RESERVATION);
    noCycle.service.applySmartQueuePositionYield(noCycle.blockedTrainInput());
    assertTrue(
        noCycle.debug.stream()
            .anyMatch(
                m ->
                    m.startsWith("SMART_QUEUE_POSITION_YIELD_SKIPPED")
                        && m.contains("reason=blocked-train-holds-nothing")),
        () -> "跳过必须写明原因，实际日志：" + noCycle.debug);

    Fixture stale = new Fixture(SmartDispatcherMode.ENFORCE);
    stale.now = NOW.plusSeconds(15);
    stale.service.applySmartQueuePositionYield(stale.blockedTrainInput());
    assertTrue(
        stale.debug.stream()
            .anyMatch(
                m ->
                    m.startsWith("SMART_QUEUE_POSITION_YIELD_SKIPPED")
                        && m.contains("reason=stale-blocker-snapshot")),
        () -> "不同分支要给出不同原因，实际日志：" + stale.debug);
  }

  // ---------- 夹具 ----------

  private static final String BLOCKED_TRAIN = "MT-LH-3340";
  private static final String QUEUE_OWNER = "MT-LP-0838";
  private static final OccupancyResource HELD_RESOURCE =
      OccupancyResource.forConflict("switcher:S637");
  private static final OccupancyResource QUEUED_RESOURCE =
      OccupancyResource.forConflict("switcher:S643");

  /**
   * 第十七轮 MT 死锁的最小重现：账本里有真的 claim 与真的队列，blocker 快照用真的 {@link
   * org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim} 播种（走生产转换路径）。
   */
  private final class Fixture {
    private final SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    private final List<String> debug = new ArrayList<>();
    private final RuntimeDispatchService service;
    private Instant now = NOW;

    Fixture(SmartDispatcherMode mode) {
      this(mode, ResourceIntent.MOVEMENT_REQUIRED);
    }

    Fixture(SmartDispatcherMode mode, ResourceIntent heldIntent) {
      service = TestServices.minimal(debug, manager, mode, () -> now);
      // A 真的持有 S637。
      assertTrue(
          manager.acquire(request(BLOCKED_TRAIN, HELD_RESOURCE, heldIntent)).allowed(),
          "前置：被卡列车先持有 S637");
      // B 真的在 S643 排队（需要先有人占着才能形成队列）。
      assertTrue(manager.acquire(request("other-holder", QUEUED_RESOURCE)).allowed());
      assertFalse(manager.acquire(request(QUEUE_OWNER, QUEUED_RESOURCE)).allowed());
      seedSnapshots();
    }

    /** 把 A↔B 的等待关系重新采样到当前时刻。 */
    void seedSnapshots() {
      service.updateBlockerSnapshot(
          BLOCKED_TRAIN,
          List.of(claim(QUEUE_OWNER, QUEUED_RESOURCE, ClaimRole.QUEUE_POSITION)),
          null,
          now,
          "test");
      service.updateBlockerSnapshot(
          QUEUE_OWNER,
          List.of(claim(BLOCKED_TRAIN, HELD_RESOURCE, ClaimRole.MOVEMENT_REQUIRED)),
          null,
          now,
          "test");
    }

    RuntimeDispatchService.SmartRecoveryInput blockedTrainInput() {
      return new RuntimeDispatchService.SmartRecoveryInput(
          BLOCKED_TRAIN,
          2839,
          SignalAspect.STOP,
          true,
          SignalComputationTrace.TokenState.PENDING,
          true,
          1,
          Set.of(),
          NodeId.of("OP:S:MT:1"),
          NodeId.of("OP:S:MT:2"),
          "route:MT",
          0,
          "OP:S:MT:1",
          false,
          true,
          false,
          true,
          "queue-position-inversion");
    }
  }

  private static OccupancyClaim claim(String train, OccupancyResource resource, ClaimRole role) {
    return new OccupancyClaim(
        resource, train, Optional.empty(), NOW, Duration.ZERO, Optional.empty(), role);
  }

  private static RuntimeDispatchService.DeadlockBlockerInfo blocker(
      String owner, String resourceKey, String role) {
    return new RuntimeDispatchService.DeadlockBlockerInfo(
        owner, resourceKey, Optional.empty(), owner, resourceKey, "-", "-", role, "test", 0L, 0L);
  }

  private static OccupancyRequest request(String train, OccupancyResource resource) {
    return request(train, resource, ResourceIntent.MOVEMENT_REQUIRED);
  }

  private static OccupancyRequest request(
      String train, OccupancyResource resource, ResourceIntent intent) {
    return new OccupancyRequest(train, Optional.empty(), NOW, List.of(resource), Map.of(), 0)
        .withResourceIntents(Map.of(resource, intent));
  }
}
