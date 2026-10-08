package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.junit.jupiter.api.Test;

/**
 * 净变化版本：只有账本的语义状态（占用、排队、道岔签名、放行锁）真正变了才前进。
 *
 * <p>停着的车以它为重评估条件。窗口内“放掉再取回同一份占用”与心跳刷新不算变化；窗口外的任何改动照常计数。
 */
class OccupancyNetChangeVersionTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final OccupancyResource NODE_X =
      OccupancyResource.forNode(NodeId.of("OP:A:B:1:001"));
  private static final OccupancyResource NODE_Y =
      OccupancyResource.forNode(NodeId.of("OP:A:B:1:002"));
  private static final OccupancyResource SWITCHER =
      OccupancyResource.forConflict("switcher:SWITCHER:Test:1:64:1");

  private final SimpleOccupancyManager manager =
      new SimpleOccupancyManager(
          (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());

  /** 窗口外取得占用：净变化版本前进。 */
  @Test
  void changeOutsideAWindowAdvances() {
    long before = manager.netChangeVersion();

    acquire("A", NODE_X, ResourceIntent.PROTECTIVE_RETAIN);

    assertNotEquals(before, manager.netChangeVersion());
  }

  /** 窗口内放掉再以同一角色取回：原始版本照常推进，净变化版本不动。 */
  @Test
  void releaseAndReacquireOfTheSameClaimInsideAWindowIsNotAChange() {
    acquire("A", NODE_X, ResourceIntent.PROTECTIVE_RETAIN);
    long net = manager.netChangeVersion();
    long raw = manager.version();

    manager.beginNetChangeWindow();
    manager.releaseResource(NODE_X, Optional.of("A"));
    acquire("A", NODE_X, ResourceIntent.PROTECTIVE_RETAIN);
    manager.endNetChangeWindow();

    assertNotEquals(raw, manager.version(), "一放一取应照常推进原始版本，否则本用例是空的");
    assertEquals(net, manager.netChangeVersion());
  }

  /** 窗口内真的放掉了、或拿了新的：结束时前进，且只前进一次。 */
  @Test
  void netChangeInsideAWindowAdvancesOnce() {
    acquire("A", NODE_X, ResourceIntent.PROTECTIVE_RETAIN);
    long net = manager.netChangeVersion();

    manager.beginNetChangeWindow();
    manager.releaseResource(NODE_X, Optional.of("A"));
    acquire("A", NODE_Y, ResourceIntent.PROTECTIVE_RETAIN);
    manager.endNetChangeWindow();

    assertEquals(net + 1, manager.netChangeVersion());
  }

  /** 同一资源换了角色或换了主人，都是变化。 */
  @Test
  void roleOrOwnerChangeInsideAWindowIsAChange() {
    acquire("A", NODE_X, ResourceIntent.MOVEMENT_REQUIRED);
    long net = manager.netChangeVersion();

    manager.beginNetChangeWindow();
    manager.releaseResource(NODE_X, Optional.of("A"));
    acquire("A", NODE_X, ResourceIntent.PROTECTIVE_RETAIN);
    manager.endNetChangeWindow();
    long afterRole = manager.netChangeVersion();

    manager.beginNetChangeWindow();
    manager.releaseResource(NODE_X, Optional.of("A"));
    acquire("B", NODE_X, ResourceIntent.PROTECTIVE_RETAIN);
    manager.endNetChangeWindow();

    assertNotEquals(net, afterRole, "角色变化");
    assertNotEquals(afterRole, manager.netChangeVersion(), "主人变化");
  }

  /** 窗口内有车排进了冲突队列：是变化（队首可能因此改变）。 */
  @Test
  void queueChangeInsideAWindowIsAChange() {
    acquire("A", SWITCHER, ResourceIntent.MOVEMENT_REQUIRED);
    long net = manager.netChangeVersion();

    manager.beginNetChangeWindow();
    OccupancyDecision decision =
        manager.canEnter(request("B", SWITCHER, ResourceIntent.MOVEMENT_REQUIRED));
    manager.endNetChangeWindow();

    assertFalse(decision.allowed(), "B 应被 A 挡住并排队，否则本用例是空的");
    assertTrue(
        manager.snapshotQueues().stream()
            .anyMatch(
                snapshot -> snapshot.entries().stream().anyMatch(e -> e.trainName().equals("B"))),
        "B 应已入队");
    assertNotEquals(net, manager.netChangeVersion());
  }

  /** 嵌套窗口只在最外层结束时判定：内层结束时放掉了、外层结束前又取回，不算变化。 */
  @Test
  void nestedWindowsAreDecidedAtTheOutermostEnd() {
    acquire("A", NODE_X, ResourceIntent.PROTECTIVE_RETAIN);
    long net = manager.netChangeVersion();

    manager.beginNetChangeWindow();
    manager.beginNetChangeWindow();
    manager.releaseResource(NODE_X, Optional.of("A"));
    manager.endNetChangeWindow();
    assertEquals(net, manager.netChangeVersion(), "内层结束时不判定");
    acquire("A", NODE_X, ResourceIntent.PROTECTIVE_RETAIN);
    manager.endNetChangeWindow();

    assertEquals(net, manager.netChangeVersion());
  }

  /** 窗口开始前、还没人读过的改动，不会被随后什么也没变的窗口吞掉。 */
  @Test
  void changeBeforeAWindowIsNotSwallowedByIt() {
    long net = manager.netChangeVersion();
    acquire("A", NODE_X, ResourceIntent.PROTECTIVE_RETAIN);

    manager.beginNetChangeWindow();
    manager.endNetChangeWindow();

    assertNotEquals(net, manager.netChangeVersion());
  }

  private void acquire(String train, OccupancyResource resource, ResourceIntent intent) {
    assertTrue(manager.acquire(request(train, resource, intent)).allowed(), train + " " + resource);
  }

  private static OccupancyRequest request(
      String train, OccupancyResource resource, ResourceIntent intent) {
    Map<OccupancyResource, ResourceIntent> intents = new LinkedHashMap<>();
    intents.put(resource, intent);
    return new OccupancyRequest(
        train,
        Optional.empty(),
        NOW,
        List.of(resource),
        Map.of(),
        Map.of(),
        0,
        AuthorizationPurpose.RUNTIME_MOVE,
        Map.of(),
        intents);
  }
}
