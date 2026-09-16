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
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartWaitForPlanner;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.junit.jupiter.api.Test;

/**
 * 等待图的边要不要丢，先拿账本复核一次。
 *
 * <p>判据本身在 {@code SmartWaitForPlannerTest} 里已经钉住了（复核过的边不走年龄判据）。 <b>本用例钉的是接线</b>——那才是这个改动真正的风险面：
 * {@code RuntimeDispatchService} 用 「规范化列车名 + {@code |} + 资源」 建索引，而 blocker 那边给的是 {@code
 * DeadlockBlockerInfo.resourceKey()}。 两个字符串只要有一处口径不同（大小写、{@code kind:} 前缀、规范化规则）， 判据再正确也永远匹配不上，而
 * 1820 个用例仍然全绿。
 *
 * <p>这正是本项目反复栽的那类缺陷：<b>一个量只能和同一个量比</b>。
 */
class WaitGraphLiveVerificationTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final String BLOCKED = "SURC-MT-LP-5720";
  private static final String BLOCKER = "SURC-DS-LW-3277";
  private static final OccupancyResource CONTESTED =
      OccupancyResource.forConflict("switcher:SWITCHER:Towny:502:74:996");

  /**
   * 两个方向必须一起钉：账本确认得了就标 live，确认不了就不标。
   *
   * <p>只断言前者会漏掉「无论如何都返回 true」这种接错；只断言后者则证明不了修复生效。
   */
  @Test
  void liveVerifiedIsSetOnlyWhenTheLedgerStillConfirmsTheHold() {
    assertTrue(
        edgeLiveVerified(claim(BLOCKER, CONTESTED, ClaimRole.MOVEMENT_REQUIRED)),
        "账本里 blocker 仍以阻塞角色持有该资源 —— 必须标成已复核");

    assertFalse(edgeLiveVerified(), "账本里已经没有这条 claim —— 不得标成已复核，应当照旧按 TTL 年龄处理");

    assertFalse(
        edgeLiveVerified(claim("SURC-WS-LC-0001", CONTESTED, ClaimRole.MOVEMENT_REQUIRED)),
        "持有者是**别的车** —— 不能闭合这条等待边");

    assertFalse(
        edgeLiveVerified(
            claim(
                BLOCKER,
                OccupancyResource.forConflict("switcher:OTHER"),
                ClaimRole.MOVEMENT_REQUIRED)),
        "同一辆车持的是**别的资源** —— 同样不能闭合");
  }

  /**
   * 排队位次不是持有，复核不能因为它成立。
   *
   * <p>口径必须与 {@code OccupancyClaimEvidence.blockingClaimRole} 一致：QUEUE_POSITION / LOOKAHEAD_PREVIEW
   * / UNLOCK_RESERVATION 都挡不住别人。
   */
  @Test
  void nonBlockingRolesDoNotCountAsAConfirmedHold() {
    for (ClaimRole role :
        List.of(
            ClaimRole.QUEUE_POSITION, ClaimRole.LOOKAHEAD_PREVIEW, ClaimRole.UNLOCK_RESERVATION)) {
      assertFalse(
          edgeLiveVerified(claim(BLOCKER, CONTESTED, role)), () -> role + " 挡不住别人，不得据此认定等待边仍然成立");
    }
    assertTrue(
        edgeLiveVerified(claim(BLOCKER, CONTESTED, ClaimRole.PROTECTIVE_RETAIN)),
        "PROTECTIVE_RETAIN 会挡人，应当算已复核");
  }

  /** 跑一遍真实的建边路径，返回那条边的 liveVerified。 */
  private static boolean edgeLiveVerified(OccupancyClaim... liveClaims) {
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
    RuntimeDispatchService service =
        TestServices.minimal(new ArrayList<>(), manager, SmartDispatcherMode.ENFORCE, () -> NOW);
    // 用真实的 claim 播种等待关系，走生产的 claim→blocker 转换。
    service.updateBlockerSnapshot(
        BLOCKED,
        List.of(claim(BLOCKER, CONTESTED, ClaimRole.MOVEMENT_REQUIRED)),
        null,
        NOW,
        "test");

    List<SmartWaitForPlanner.InputEdge> edges = inputEdges(service, List.of(liveClaims));
    assertEquals(1, edges.size(), () -> "前置：应当恰好建出一条边，实际 " + edges);
    return edges.get(0).liveVerified();
  }

  @SuppressWarnings("unchecked")
  private static List<SmartWaitForPlanner.InputEdge> inputEdges(
      RuntimeDispatchService service, List<OccupancyClaim> liveClaims) {
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
          method.invoke(service, Map.of(), Set.of(BLOCKED, BLOCKER), NOW, 10_000L, liveClaims);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static OccupancyClaim claim(String train, OccupancyResource resource, ClaimRole role) {
    return new OccupancyClaim(
        resource, train, Optional.empty(), NOW, Duration.ZERO, Optional.empty(), role);
  }
}
