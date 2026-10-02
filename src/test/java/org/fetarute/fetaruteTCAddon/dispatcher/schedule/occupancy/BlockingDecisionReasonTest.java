package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 每一次拒绝都必须说得出原因。
 *
 * <p>{@link OccupancyDecision#reason()} 的默认值是字面量 {@code "none"}——它表示「没人填过」，不是一个结论。 {@code canEnter}
 * 两条最通用的拒绝路径（blockers / queue-blocked）此前都用不带 reason 的构造器， 于是下游只能报 {@code no-decision-reason}。
 *
 * <p><b>代价是真实发生过的。</b>第十九轮 {@code SURC-WS-LC-4801} 掉头堵死整条 WS 线 40 分钟、到站归零， 而它阻塞快照里占比最高的原因就是 {@code
 * canenter-blocked:no-decision-reason}（113 次）—— 全轮卡得最久的车， 它绝大多数次被拒的原因是空的。早在第七轮就记录过 394/417 是这个值。
 *
 * <p>目标是<b>解锁疏通</b>而不是超时删车；说不出为什么被拒，就无从疏通。
 */
class BlockingDecisionReasonTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final OccupancyResource SWITCHER =
      OccupancyResource.forConflict("switcher:SW-TEST");

  /** 被真实占用挡住时，原因必须带上「是什么挡的」，而不是留空。 */
  @Test
  void aBlockedDecisionNamesTheBlockingShape() {
    SimpleOccupancyManager manager = manager();
    assertTrue(manager.acquire(request("holder")).allowed(), "前置：holder 先占住");

    OccupancyDecision denied = manager.canEnter(request("waiter"));

    assertFalse(denied.allowed(), "前置：第二辆车应当被拒");
    assertNotEquals("none", denied.reason(), "拒绝必须带原因——none 表示没人填过，会被下游报成 no-decision-reason");
    assertTrue(
        denied.reason().contains("CONFLICT") && denied.reason().contains("MOVEMENT_REQUIRED"),
        () -> "原因要说清是什么形态挡的，实际：" + denied.reason());
  }

  /** 原因字符串不得带列车名——它会进去重键，带上就随车数爆炸。 */
  @Test
  void theReasonIsBoundedAndCarriesNoTrainName() {
    SimpleOccupancyManager manager = manager();
    assertTrue(manager.acquire(request("holder-with-a-very-distinctive-name")).allowed());

    OccupancyDecision denied = manager.canEnter(request("waiter"));

    assertFalse(denied.reason().contains("holder-with-a-very-distinctive-name"), "原因不得带列车名");
    assertFalse(denied.reason().contains("waiter"), "原因不得带列车名");
  }

  private static SimpleOccupancyManager manager() {
    return new SimpleOccupancyManager(
        (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
  }

  private static OccupancyRequest request(String train) {
    return new OccupancyRequest(train, Optional.empty(), NOW, List.of(SWITCHER), Map.of(), 0)
        .withResourceIntents(Map.of(SWITCHER, ResourceIntent.MOVEMENT_REQUIRED));
  }
}
