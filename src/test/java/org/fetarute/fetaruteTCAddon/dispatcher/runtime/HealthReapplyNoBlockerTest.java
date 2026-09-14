package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.Test;

/**
 * 没有任何 blocker 的车，不得再被"进度停滞"兜底加一层死锁硬停车。
 *
 * <p>{@code TrainHealthMonitor} 的进度停滞升级最后会调 {@code reapplyHardStopByName(...,
 * "health-stop-progress-stuck")}，而它落地成 {@code DEADLOCK_CONFIRMED_WAITING} 硬停车——语义只是
 * "重新施加停车"，却顶着死锁的名字。对一辆**没有任何东西挡着**的车施加它，唯一效果是再装一个 movement
 * inhibitor、撤销它本来就有的授权；而停车本身让进度继续停滞，下一轮健康检查再次判定 progress-stuck——自我维持。
 *
 * <p>实服 2026-09-13 第七轮：804 条状态快照落在这一族，**全部** {@code blockedBy=[]}、 {@code
 * movementToken=INVALID}，滞留中位 543 秒、最长 1751 秒；同期 movement inhibitor 占车队比例 从 0% 单调涨到 **64%**。
 *
 * <p>调用方在返回 false 时会退回 {@code refreshSignalByName}——对"没人挡、只是没动"的车， 重新算一次信号正是该做的事。
 */
class HealthReapplyNoBlockerTest {

  private static final Instant NOW = Instant.parse("2026-09-13T22:30:00Z");

  @Test
  void reapplyIsRefusedWhenNothingIsBlockingTheTrain() throws Exception {
    java.util.List<String> debug = new java.util.ArrayList<>();
    RuntimeDispatchService service = TestServices.minimal(debug);
    installStopState(
        service,
        RuntimeStopState.hardStop(
            "train-1",
            HardStopReason.DEADLOCK_CONFIRMED_WAITING,
            "health-monitor-reapply:health-stop-progress-stuck",
            NOW.minus(Duration.ofMinutes(9))));

    assertFalse(
        service.reapplyHardStopByName("train-1", "health-stop-progress-stuck"),
        "没有 blocker 就不是死锁，不得再加一层硬停车");
    assertTrue(
        debug.stream().anyMatch(l -> l.startsWith("SMART_HEALTH_REAPPLY_SKIPPED_NO_BLOCKER")),
        "必须留下拒绝的证据：" + debug);
  }

  @SuppressWarnings("unchecked")
  private static void installStopState(RuntimeDispatchService service, RuntimeStopState state)
      throws Exception {
    var field = RuntimeDispatchService.class.getDeclaredField("activeStopStates");
    field.setAccessible(true);
    ((ConcurrentMap<String, RuntimeStopState>) field.get(service)).put("train-1", state);
  }
}
