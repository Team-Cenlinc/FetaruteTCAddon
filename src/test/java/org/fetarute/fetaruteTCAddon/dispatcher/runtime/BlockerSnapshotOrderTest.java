package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartWaitForPlanner;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.junit.jupiter.api.Test;

/**
 * blocker 快照的遍历顺序必须等于占用判定给出的顺序。
 *
 * <p>健康监控按"第一个合格 blocker"定互卡配对与兜底 destroy 的对象，wait-for 规划器按输入边顺序建候选。此前快照用 {@code Set.copyOf}
 * 冻结，顺序由每个 JVM 随机一次的 SALT 决定——同一进程内稳定，所以任何"跑两遍比一比"的用例都抓不到。
 *
 * <p>这里刻意用六个 blocker、非字母序的车名，并且都带走廊方向（{@code DeadlockBlockerInfo} 的 hash 因此含枚举、随进程变）：
 * 若实现退回任何哈希序，断言几乎必然失败，而不必等到换一个 JVM 才碰上。
 */
class BlockerSnapshotOrderTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final String BLOCKED = "waiting-train";
  private static final List<String> BLOCKERS_IN_PATH_ORDER =
      List.of("t-5", "t-2", "t-9", "t-1", "t-7", "t-3");

  @Test
  void publicSnapshotKeepsInsertionOrder() {
    Set<RuntimeDispatchService.DeadlockBlockerInfo> inserted = new LinkedHashSet<>();
    for (int i = 0; i < BLOCKERS_IN_PATH_ORDER.size(); i++) {
      inserted.add(
          new RuntimeDispatchService.DeadlockBlockerInfo(
              BLOCKERS_IN_PATH_ORDER.get(i),
              "single:section:" + i,
              Optional.of(i % 2 == 0 ? CorridorDirection.A_TO_B : CorridorDirection.B_TO_A)));
    }

    RuntimeDispatchService.DeadlockBlockerSnapshot snapshot =
        new RuntimeDispatchService.DeadlockBlockerSnapshot(inserted, NOW);

    assertEquals(List.copyOf(inserted), List.copyOf(snapshot.blockers()));
    assertEquals(BLOCKERS_IN_PATH_ORDER, List.copyOf(snapshot.trainNames()));
  }

  /** 走生产的 claim→blocker 转换，三个出口（健康监控、手动解锁、规划器输入边）都必须保持占用判定的顺序。 */
  @Test
  void serviceSnapshotKeepsOccupancyDecisionOrderAtEveryExit() {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service =
        TestServices.minimal(new ArrayList<>(), manager, SmartDispatcherMode.ENFORCE, () -> NOW);
    List<OccupancyClaim> claimsInPathOrder = new ArrayList<>();
    for (int i = 0; i < BLOCKERS_IN_PATH_ORDER.size(); i++) {
      claimsInPathOrder.add(
          new OccupancyClaim(
              OccupancyResource.forConflict("single:section:" + i),
              BLOCKERS_IN_PATH_ORDER.get(i),
              Optional.empty(),
              NOW,
              Duration.ZERO,
              Optional.of(CorridorDirection.A_TO_B),
              ClaimRole.MOVEMENT_REQUIRED));
    }

    service.updateBlockerSnapshot(BLOCKED, claimsInPathOrder, null, NOW, "test");

    assertEquals(
        BLOCKERS_IN_PATH_ORDER,
        service.recentDeadlockBlockers(BLOCKED, Duration.ofMinutes(1)).blockers().stream()
            .map(RuntimeDispatchService.DeadlockBlockerInfo::trainName)
            .toList(),
        "健康监控读到的 blocker 顺序");
    assertEquals(
        BLOCKERS_IN_PATH_ORDER,
        List.copyOf(service.recentBlockerTrains(BLOCKED, Duration.ofMinutes(1))),
        "手动解锁读到的 blocker 列车顺序");
    assertEquals(
        BLOCKERS_IN_PATH_ORDER,
        inputEdges(service).stream().map(SmartWaitForPlanner.InputEdge::blockerTrain).toList(),
        "wait-for 规划器输入边顺序");
  }

  @SuppressWarnings("unchecked")
  private static List<SmartWaitForPlanner.InputEdge> inputEdges(RuntimeDispatchService service) {
    try {
      java.lang.reflect.Method method =
          RuntimeDispatchService.class.getDeclaredMethod(
              "smartPlannerInputEdges",
              Map.class,
              Set.class,
              Instant.class,
              long.class,
              List.class);
      method.setAccessible(true);
      return (List<SmartWaitForPlanner.InputEdge>)
          method.invoke(service, Map.of(), Set.of(BLOCKED), NOW, 10_000L, List.of());
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
