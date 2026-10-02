package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;
import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

/**
 * 没有 blocker 的"死锁"不得安上一个永远满足不了的解除条件。
 *
 * <p>{@code hardReleaseCondition} 在 {@code hasBlockers} 时已经提前返回，所以它的 switch **只在没有任何 blocker
 * 时执行**——也就是说 {@code DEADLOCK_WINNER_OR_RESOURCE_RELEASE_CONFIRMED}
 * 只会在"根本没有死锁证据"时被安上。而它要求"决出死锁赢家或某个资源被释放"：没有 blocker 就没有赢家、 也没有资源可释放，**条件永远不可能满足**。
 *
 * <p>实服 2026-09-13 第六轮 207 条快照落在这一族。典型现场（用户在 LWN 出库口看到的堵点）： 车刚出库、5 个资源全部到手、{@code
 * blockedBy=[]}，却被判成死锁并撤销授权 （{@code movementToken=INVALID}）；停车本身让进度停滞，HealthMonitor 再次判定
 * progress-stuck 又重新施加——自我维持。
 *
 * <p>这条停因真正的来源也不是死锁检测，而是 {@code TrainHealthMonitor} 的兜底 {@code reapplyHardStopByName(...,
 * "health-stop-progress-stuck")}。
 */
class DeadlockReleaseConditionTest {

  private static final Instant NOW = Instant.parse("2026-09-13T21:00:00Z");

  /** 没有 blocker 时，解除条件必须是"重新签发授权"这种可达成的条件。 */
  @Test
  void deadlockWithoutBlockersGetsASatisfiableReleaseCondition() {
    RuntimeStopState state =
        RuntimeStopState.hardStop(
            "t", HardStopReason.DEADLOCK_CONFIRMED_WAITING, decisionWithoutBlockers(), NOW);

    assertNotEquals(
        RuntimeStopState.ReleaseCondition.DEADLOCK_WINNER_OR_RESOURCE_RELEASE_CONFIRMED,
        state.releaseCondition(),
        "没有 blocker 就没有死锁赢家、也没有资源可释放，这个条件永远满足不了");
    assertEquals(
        RuntimeStopState.ReleaseCondition.ACTIVE_AUTHORITY_REISSUED, state.releaseCondition());
  }

  /** 收紧不得波及真实死锁：有 blocker 时仍然要求"blocker 释放 + 重新签发授权"。 */
  @Test
  void realDeadlockWithBlockersStillRequiresTheBlockerToClear() {
    RuntimeStopState state =
        RuntimeStopState.hardStop(
            "t", HardStopReason.DEADLOCK_CONFIRMED_WAITING, decisionWithBlockers(), NOW);

    assertEquals(
        RuntimeStopState.ReleaseCondition.BLOCKING_RESOURCES_RELEASED_AND_AUTHORITY_REISSUED,
        state.releaseCondition(),
        "有 blocker 时必须仍然等它释放");
  }

  private static OccupancyDecision decisionWithoutBlockers() {
    return new OccupancyDecision(
        false, NOW, SignalAspect.STOP, List.of(), false, "health-monitor-reapply:progress-stuck");
  }

  private static org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim
      blockingClaim() {
    return new org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim(
        org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource.forNode(
            org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId.of("OP:S:ALFA:1")),
        "other-train",
        java.util.Optional.empty(),
        NOW,
        java.time.Duration.ZERO,
        java.util.Optional.empty(),
        org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole.MOVEMENT_REQUIRED);
  }

  private static OccupancyDecision decisionWithBlockers() {
    return new OccupancyDecision(
        false, NOW, SignalAspect.STOP, List.of(blockingClaim()), false, "real-deadlock");
  }
}
