package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.junit.jupiter.api.Test;

/**
 * 计划扣留：按表运行里唯一一条会让列车不动的分支。
 *
 * <p>这条判定是刻意不对称的——只有“有计划源、有计划时刻、确实早到、早到幅度在上限内”四个条件同时成立才扣车，
 * 其余每一条路径都放行。原因是两种错误的代价完全不对等：误放行只是这趟车没按表跑，误扣留会让这辆车停在站里 不动，并顺带堵住它身后的所有车。用例按“每一条放行路径都要有人钉住”来组织。
 */
class ScheduledDepartureHoldTest {

  private static final Instant T0 = Instant.parse("2026-03-02T08:00:00Z");
  private static final NodeId NODE = NodeId.of("OP:S:AAA:1");
  private static final UUID ROUTE = UUID.randomUUID();

  private static RouteDefinition route() {
    return new RouteDefinition(
        RouteId.of("OP:L1:R1"),
        List.of(NODE, NodeId.of("OP:S:BBB:1"), NodeId.of("OP:S:CCC:1")),
        Optional.empty());
  }

  private static StationStopCoordinator serviceWithPlan(
      List<String> logs, ScheduledDeparturePlan plan, Duration maxHold) {
    StationStopCoordinator coordinator = TestServices.minimal(logs, () -> T0).stationStops();
    coordinator.setPlan(plan);
    coordinator.setMaxHold(maxHold);
    return coordinator;
  }

  private static boolean holds(StationStopCoordinator coordinator, Instant now) {
    return coordinator.holdsDeparture("train-A", route(), Optional.of(ROUTE), 0, NODE, now);
  }

  /** 早到且在上限内：这是唯一应当扣车的情形，并且必须留痕。 */
  @Test
  void holdsEarlyTrainWithinCap() {
    List<String> logs = new ArrayList<>();
    StationStopCoordinator service =
        serviceWithPlan(logs, event -> Optional.of(T0.plusSeconds(60)), Duration.ofSeconds(120));

    assertTrue(holds(service, T0));
    assertTrue(
        logs.stream().anyMatch(line -> line.startsWith("SCHEDULED_DEPARTURE_HOLD ")),
        () -> "扣车必须可归因，否则现场看到的就是一辆无故不动的车：" + logs);
  }

  /** 已到点：立刻放行。时刻表的作用是让车不早开，不是让车晚开。 */
  @Test
  void releasesExactlyAtPlannedTime() {
    StationStopCoordinator service =
        serviceWithPlan(new ArrayList<>(), event -> Optional.of(T0), Duration.ofSeconds(120));

    assertFalse(holds(service, T0));
  }

  /** 已晚点：立刻放行，绝不能让时刻表把晚点的车扣得更晚。 */
  @Test
  void releasesLateTrain() {
    StationStopCoordinator service =
        serviceWithPlan(
            new ArrayList<>(), event -> Optional.of(T0.minusSeconds(300)), Duration.ofSeconds(120));

    assertFalse(holds(service, T0));
  }

  /** 早到幅度超过上限：判为匹配错误而不是真的早到，放行并留痕。 */
  @Test
  void releasesWhenEarlinessExceedsCap() {
    List<String> logs = new ArrayList<>();
    StationStopCoordinator service =
        serviceWithPlan(logs, event -> Optional.of(T0.plusSeconds(3600)), Duration.ofSeconds(120));

    assertFalse(holds(service, T0));
    assertTrue(
        logs.stream().anyMatch(line -> line.startsWith("SCHEDULED_DEPARTURE_HOLD_SKIPPED ")),
        () -> logs.toString());
  }

  /** 上限被配成 0 等于关掉扣留：即使计划源说要等一小时也照常放行。 */
  @Test
  void zeroCapDisablesHoldEntirely() {
    StationStopCoordinator service =
        serviceWithPlan(new ArrayList<>(), event -> Optional.of(T0.plusSeconds(60)), Duration.ZERO);

    assertFalse(holds(service, T0));
  }

  /**
   * 上限被封顶在调度层的硬上限内。
   *
   * <p>发车门锁 180 秒就会被回收，扣留必须先于门锁结束；否则会出现“自己不动、也不再排队”的状态。 这里给一个远超上限的配置值，验证它不会被原样采纳。
   */
  @Test
  void capIsClampedBelowDepartureGateLifetime() {
    StationStopCoordinator service =
        serviceWithPlan(
            new ArrayList<>(), event -> Optional.of(T0.plusSeconds(170)), Duration.ofHours(1));

    assertFalse(holds(service, T0), "170 秒早到已经超过硬上限，必须放行而不是按配置扣一小时");
  }

  /** 没有计划源：完全透明。 */
  @Test
  void noPlanMeansNoHold() {
    StationStopCoordinator service =
        TestServices.minimal(new ArrayList<>(), () -> T0).stationStops();
    service.setMaxHold(Duration.ofSeconds(120));

    assertFalse(holds(service, T0));
  }

  /** 计划源说“不受约束”：放行。 */
  @Test
  void emptyPlanResultMeansNoHold() {
    StationStopCoordinator service =
        serviceWithPlan(new ArrayList<>(), event -> Optional.empty(), Duration.ofSeconds(120));

    assertFalse(holds(service, T0));
  }

  /** 计划源自己抛异常：吞掉、留痕、放行。一个坏掉的计划源不能把列车扣在站里。 */
  @Test
  void planFailureReleasesAndIsAudited() {
    List<String> logs = new ArrayList<>();
    StationStopCoordinator service =
        serviceWithPlan(
            logs,
            event -> {
              throw new IllegalStateException("boom");
            },
            Duration.ofSeconds(120));

    assertFalse(holds(service, T0));
    assertTrue(
        logs.stream().anyMatch(line -> line.startsWith("SCHEDULED_DEPARTURE_PLAN_FAILED ")),
        () -> logs.toString());
  }

  /**
   * 健康检查要能看见"它在等点"：扣车期间为真，门控放行或到点即失效，列车下线立刻清掉。
   *
   * <p>看不见的话，停滞检测会在扣留期间派发恢复动作，一路升级到强制重发，把等点的车提前放走（2026-09-27 实服每辆 MT-2 都被报了停滞）。
   */
  @Test
  void activeHoldIsVisibleUntilReleased() {
    java.util.concurrent.atomic.AtomicReference<Instant> clock =
        new java.util.concurrent.atomic.AtomicReference<>(T0);
    StationStopCoordinator service =
        TestServices.minimal(new ArrayList<>(), clock::get).stationStops();
    service.setPlan(event -> Optional.of(T0.plusSeconds(60)));
    service.setMaxHold(Duration.ofSeconds(120));

    assertFalse(service.holdingForSchedule("train-A"), "还没问过门控");
    assertTrue(holds(service, T0));
    assertTrue(service.holdingForSchedule("TRAIN-A"), "大小写不敏感");

    clock.set(T0.plusSeconds(30));
    assertTrue(service.holdingForSchedule("train-A"));
    assertFalse(holds(service, T0.plusSeconds(60)), "到点放行");
    assertFalse(service.holdingForSchedule("train-A"), "门控放行后立即失效");

    clock.set(T0);
    assertTrue(holds(service, T0));
    clock.set(T0.plusSeconds(61));
    assertFalse(service.holdingForSchedule("train-A"), "没人再问门控也会在计划时刻失效");

    clock.set(T0);
    assertTrue(holds(service, T0));
    service.notifyReleased("train-A", "destroyed");
    assertFalse(service.holdingForSchedule("train-A"), "列车下线立刻清掉");
  }

  /** 计划源可以被随时摘掉，摘掉后立刻不再扣车——这是“关掉按表运行”的唯一动作。 */
  @Test
  void detachingPlanStopsHoldingImmediately() {
    StationStopCoordinator service =
        serviceWithPlan(
            new ArrayList<>(), event -> Optional.of(T0.plusSeconds(60)), Duration.ofSeconds(120));
    assertTrue(holds(service, T0));

    service.setPlan(null);

    assertFalse(holds(service, T0));
  }

  /** 手动提前出车在车库等计划时刻：健康检查当成按表扣车，到点自动失效。 */
  @Test
  void depotHoldIsVisibleUntilThePlannedDeparture() {
    java.util.concurrent.atomic.AtomicReference<Instant> clock =
        new java.util.concurrent.atomic.AtomicReference<>(T0);
    StationStopCoordinator service =
        TestServices.minimal(new ArrayList<>(), clock::get).stationStops();

    service.holdAtDepotUntil("train-A", T0.plusSeconds(600));
    assertTrue(service.holdingForSchedule("TRAIN-A"));
    clock.set(T0.plusSeconds(599));
    assertTrue(service.holdingForSchedule("train-A"), "扣得比门控上限久也照样可见");
    clock.set(T0.plusSeconds(600));
    assertFalse(service.holdingForSchedule("train-A"), "到点失效");
  }
}
