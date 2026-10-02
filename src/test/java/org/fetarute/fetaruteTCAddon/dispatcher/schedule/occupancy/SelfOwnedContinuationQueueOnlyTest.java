package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/**
 * 已在单线区内的车，不得被一个「还在排队、根本没进区」的存在挡住。
 *
 * <p>形态（第十九轮实服，同一座桥第四次复发）：
 *
 * <pre>
 *   SURC-WS-LC-4801  持有 single:section:bridge:SWITCHER:587~SWITCHER:705（MOVEMENT_REQUIRED）
 *   heldDirection=B_TO_A  requestedDirection=B_TO_A  directionMatches=true
 *   pathExitsZone=true              ← 证得出会离开
 *   externalBlockerAhead=false      ← 前方没有任何真实阻塞
 *   externalSinglePresenceOwner=queue-only   ← 挡它的只是个排队者
 * </pre>
 *
 * 结果它掉头堵死整条 WS 线 <b>40 分钟</b>、到站归零。同一座桥上第九轮 {@code WS-LC-2269}、 第十轮 {@code WS-LC-2008}、第十二轮 {@code
 * WS-LC-9344} 报的是同一个原因字符串。
 *
 * <p><b>为什么放行是安全的</b>：{@code physicalOccupancyText(QUEUE_POSITION)} 与 {@code
 * reservedAuthorityText(QUEUE_POSITION)} 都是 {@code "false"}——排队者既没占着物理空间也没有行车权，
 * 而且方向锁正握在本车手里，它不可能在本车离开前进来。 反过来，把它当成屏障只会让两边一起锁死：持有者出不去 → 队列永远不清 → 排队者也永远进不来。
 *
 * <p><b>入区那道门不在放宽范围内</b>——对一辆还没进区的车来说，队列里有别人是真的要让的。
 */
class SelfOwnedContinuationQueueOnlyTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final OccupancyResource BRIDGE =
      OccupancyResource.forConflict("single:section:bridge:SW587~SW705");

  /**
   * 判别核心：**排队者不算屏障，真占用仍然算**。
   *
   * <p>只断言前者会漏掉「无论如何都放行」这种改错；只断言后者则证明不了死锁被解开。
   */
  @Test
  void queuedTrainDoesNotBlockAHolderButARealClaimStillDoes() {
    SimpleOccupancyManager manager = manager();
    // 本车先拿到单线区（B_TO_A）。
    assertTrue(
        manager.acquire(request("holder", CorridorDirection.B_TO_A)).allowed(), "前置：本车应当先持有该单线区");
    // 对向车请求同一区 —— 会被拒并进入队列，而不是拿到 claim。
    assertFalse(
        manager.acquire(request("opposite", CorridorDirection.A_TO_B)).allowed(), "前置：对向车应当被拒并排队");

    // 本车继续在自己持有的区内前进：挡它的只有那个排队者 —— 必须放行。
    //
    // 必须走 canEnter：续行判定（selfOwnedSingleDirectionMismatchDecision）只在这条路径上跑。
    // 最初我用 acquire 写这条，结果无论改不改都是绿的——acquire 对已持有者直接放行，
    // 根本走不到该判定。空绿用例比没有用例更坏。
    OccupancyDecision continuation = manager.canEnter(request("holder", CorridorDirection.B_TO_A));
    assertTrue(
        continuation.allowed(),
        () -> "已在区内、同方向、只被排队者挡住 —— 必须放行续行，否则两边一起锁死：" + continuation.reason());
  }

  /**
   * 放宽只在“已在区内续行”那一支，**入区那道门不得被碰到**。
   *
   * <p>对一辆**还没进区**的车来说，队列里有别人是真的要让的——那里数队列是对的。 两条合起来才能证明这次改动是“只松开该松的那一半”，而不是把单线屏障整体调松了。
   */
  @Test
  void theEntryGateStillCountsQueuedTrains() {
    SimpleOccupancyManager manager = manager();
    assertTrue(manager.acquire(request("holder", CorridorDirection.B_TO_A)).allowed());
    assertFalse(
        manager.acquire(request("opposite", CorridorDirection.A_TO_B)).allowed(), "前置：对向车应当被拒并排队");

    // 第三辆车：**不持有**该区、且**方向未知**。这正是
    // failClosedUnknownSingleConflictEntry 的辖区，它数队列——本次改动不得碰到它。
    // （方向已知的同向车本来就该放行，那是跟行；最初我就是拿同向车写的，
    // 结果钉错了对象。）
    OccupancyDecision entry = manager.canEnter(request("newcomer", CorridorDirection.UNKNOWN));
    assertFalse(entry.allowed(), () -> "方向未知、还没进区的车仍须 fail-closed：" + entry.reason());
  }

  private static SimpleOccupancyManager manager() {
    return new SimpleOccupancyManager(
        (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
  }

  private static OccupancyRequest request(String train, CorridorDirection direction) {
    DirectedTraversalContext context =
        new DirectedTraversalContext(
            train,
            Optional.empty(),
            0,
            Optional.of(NodeId.of("SW587")),
            Optional.of(NodeId.of("SW587")),
            Optional.of(NodeId.of("SW587")),
            Optional.of(NodeId.of("SW705")),
            List.of(NodeId.of("SW587"), NodeId.of("SW705")),
            List.of(),
            Map.of(BRIDGE.key(), direction),
            Map.of(),
            AuthorizationPurpose.RUNTIME_MOVE.name(),
            0L,
            0L,
            "test-self-owned-continuation",
            Optional.empty());
    return new OccupancyRequest(
        train,
        Optional.empty(),
        NOW,
        List.of(BRIDGE),
        Map.of(BRIDGE.key(), direction),
        Map.of(BRIDGE.key(), 0),
        0,
        AuthorizationPurpose.RUNTIME_MOVE,
        Map.of(),
        Map.of(),
        Optional.of(context));
  }

  /**
   * 相位是显式参数之后，两个语境的差别必须在同一个夹具下直接对照。
   *
   * <p>之前这是两个只差一个词的方法（{@code ...Presence} 与 {@code ...ClaimPresence}），
   * 而那个词说不出“入区还是续行”。本仓库每一次单线区死锁都是同一个错： <b>拿入区该算的东西去判续行</b>。同一座桥上已经四轮复发。
   *
   * <p>钉的不是某个方法名，而是**同一份现场下两个相位必须得出相反的结论**。
   */
  @Test
  void theTwoPhasesDisagreeOnQueuedTrainsAndThatIsTheWholePoint() {
    SimpleOccupancyManager manager = manager();
    assertTrue(manager.acquire(request("holder", CorridorDirection.B_TO_A)).allowed());
    assertFalse(
        manager.acquire(request("opposite", CorridorDirection.A_TO_B)).allowed(),
        "前置：对向车被拒并排队（没有 claim，只有队列位）");

    // 续行：持有者向前——排队者不算存在，放行。
    assertTrue(
        manager.canEnter(request("holder", CorridorDirection.B_TO_A)).allowed(), "续行相位：排队者不算外部存在");

    // 入区：同一份现场、同一个资源，方向未知的新车——排队者算存在，拦下。
    assertFalse(
        manager.canEnter(request("newcomer", CorridorDirection.UNKNOWN)).allowed(),
        "入区相位：同一个排队者必须算存在");
  }

  /**
   * 相位参数真正承重的分支：持有者**方向未知**时的续行。
   *
   * <p>写上一条用例时我以为已经钉住了相位，变异验证证明没有：把续行路径改成 ENTRY 相位 它依然全绿。原因是方向**已知**时走的是 {@code
   * oppositeSingleAhead} 那一支， {@code externalSinglePresence} 根本不参与判定——它只在 {@code directionsKnown ==
   * false} 时承重。
   *
   * <p>所以这条用一个**方向未知**的持有者：它的路径能离开该区、前方无真实阻塞， 挡它的只有一个排队者——必须放行。用 ENTRY 相位就会把它锁死。
   */
  @Test
  void anUnknownDirectionHolderIsNotBlockedByAQueuedTrain() {
    SimpleOccupancyManager manager = manager();
    assertTrue(
        manager.acquire(request("holder", CorridorDirection.UNKNOWN)).allowed(), "前置：方向未知的车先持有该区");
    assertFalse(
        manager.acquire(request("queued", CorridorDirection.A_TO_B)).allowed(), "前置：第二辆车被拒并排队");

    OccupancyDecision continuation = manager.canEnter(request("holder", CorridorDirection.UNKNOWN));
    assertTrue(continuation.allowed(), () -> "方向未知的持有者只被排队者挡着 —— 必须放行续行：" + continuation.reason());
  }
}
