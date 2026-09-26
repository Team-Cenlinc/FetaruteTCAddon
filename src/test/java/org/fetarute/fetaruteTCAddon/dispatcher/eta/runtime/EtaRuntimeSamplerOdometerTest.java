package org.fetarute.fetaruteTCAddon.dispatcher.eta.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeStopState;
import org.junit.jupiter.api.Test;

class EtaRuntimeSamplerOdometerTest {

  private static final NodeId A = NodeId.of("SURN:S:AAA:1");
  private static final NodeId B = NodeId.of("SURN:S:BBB:1");
  private static final Instant T0 = Instant.parse("2026-09-24T10:00:00Z");

  private static TrainRuntimeSnapshot previous(NodeId lastPassed, double speed, double traveled) {
    return new TrainRuntimeSnapshot(
        1L,
        T0,
        UUID.randomUUID(),
        UUID.randomUUID(),
        RouteId.of("SURN:L1:R1"),
        0,
        Optional.empty(),
        Optional.of(lastPassed),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        OptionalDouble.of(speed),
        OptionalInt.empty(),
        OptionalInt.empty(),
        OptionalDouble.of(traveled));
  }

  @Test
  void integratesAverageSpeedSinceLastSample() {
    OptionalDouble next =
        EtaRuntimeSampler.accumulateTraveled(
            previous(A, 8.0, 10.0), Optional.of(A), OptionalDouble.of(10.0), T0.plusMillis(500));
    assertEquals(10.0 + 9.0 * 0.5, next.getAsDouble(), 1e-9);
  }

  @Test
  void resetsWhenPassingANewNode() {
    OptionalDouble next =
        EtaRuntimeSampler.accumulateTraveled(
            previous(A, 8.0, 90.0), Optional.of(B), OptionalDouble.of(8.0), T0.plusMillis(500));
    assertEquals(0.0, next.getAsDouble(), 1e-9);
  }

  @Test
  void capsIntegrationGapAfterSamplingPause() {
    // 区块卸载或服务器卡顿后的第一次采样，不能一次积出几十秒的距离。
    OptionalDouble next =
        EtaRuntimeSampler.accumulateTraveled(
            previous(A, 10.0, 0.0), Optional.of(A), OptionalDouble.of(10.0), T0.plusSeconds(30));
    assertEquals(
        10.0 * EtaRuntimeSampler.MAX_INTEGRATION_GAP_MILLIS / 1000.0, next.getAsDouble(), 1e-9);
  }

  private static final RouteId ROUTE = RouteId.of("SURN:L1:R1");

  private static TrainRuntimeSnapshot sampled(
      int index, Optional<Integer> dwell, TrainRuntimeSnapshot.HoldTimeline timeline) {
    return new TrainRuntimeSnapshot(
        1L,
        T0,
        UUID.randomUUID(),
        UUID.randomUUID(),
        ROUTE,
        index,
        Optional.empty(),
        Optional.of(A),
        dwell,
        Optional.empty(),
        Optional.empty(),
        OptionalDouble.of(0.0),
        OptionalInt.empty(),
        OptionalInt.empty(),
        OptionalDouble.empty(),
        timeline);
  }

  private static RuntimeStopState state(RuntimeStopState.ReleaseCondition release, Instant at) {
    return new RuntimeStopState(
        "T1",
        "HARD_BLOCKER_STOP",
        "d",
        release,
        RuntimeStopState.RetryTrigger.PERIODIC_RECHECK,
        List.of(),
        true,
        at);
  }

  @Test
  void holdStartSurvivesStopStateReplacement() {
    TrainRuntimeSnapshot.HoldTimeline first =
        EtaRuntimeSampler.advanceTimeline(
            sampled(3, Optional.empty(), null),
            ROUTE,
            3,
            Optional.empty(),
            Optional.of(state(RuntimeStopState.ReleaseCondition.ACTIVE_AUTHORITY_REISSUED, T0)),
            T0);
    assertEquals(Optional.of(T0), first.holdSince());
    // 30 秒后停车状态整体替换（enteredAt 重置），扣停起点仍是 T0。
    TrainRuntimeSnapshot.HoldTimeline later =
        EtaRuntimeSampler.advanceTimeline(
            sampled(3, Optional.empty(), first),
            ROUTE,
            3,
            Optional.empty(),
            Optional.of(
                state(
                    RuntimeStopState.ReleaseCondition.BLOCKING_RESOURCES_RELEASED_OR_TRANSFERRED,
                    T0.plusSeconds(30))),
            T0.plusSeconds(30));
    assertEquals(Optional.of(T0), later.holdSince());
    // 回到例行停车：清零。
    assertTrue(
        EtaRuntimeSampler.advanceTimeline(
                sampled(3, Optional.empty(), later),
                ROUTE,
                3,
                Optional.empty(),
                Optional.of(state(RuntimeStopState.ReleaseCondition.PLANNED_STOP_COMPLETED, T0)),
                T0.plusSeconds(31))
            .holdSince()
            .isEmpty());
  }

  @Test
  void dwellEndIsRecordedOnceAndClearedWhenLeaving() {
    Instant end = T0.plusSeconds(20);
    TrainRuntimeSnapshot.HoldTimeline ended =
        EtaRuntimeSampler.advanceTimeline(
            sampled(5, Optional.of(1), null), ROUTE, 5, Optional.empty(), Optional.empty(), end);
    assertEquals(Optional.of(end), ended.dwellEndedAt());
    assertEquals(
        Optional.of(end),
        EtaRuntimeSampler.advanceTimeline(
                sampled(5, Optional.empty(), ended),
                ROUTE,
                5,
                Optional.empty(),
                Optional.empty(),
                end.plusSeconds(9))
            .dwellEndedAt(),
        "同一站上保持最初的结束时刻");
    assertTrue(
        EtaRuntimeSampler.advanceTimeline(
                sampled(5, Optional.empty(), ended),
                ROUTE,
                6,
                Optional.empty(),
                Optional.empty(),
                end.plusSeconds(30))
            .dwellEndedAt()
            .isEmpty(),
        "离开该站即清空");
  }

  @Test
  void unknownPositionYieldsEmpty() {
    assertTrue(
        EtaRuntimeSampler.accumulateTraveled(
                previous(A, 8.0, 5.0), Optional.empty(), OptionalDouble.of(8.0), T0.plusSeconds(1))
            .isEmpty());
    assertEquals(
        0.0,
        EtaRuntimeSampler.accumulateTraveled(
                null, Optional.of(A), OptionalDouble.of(8.0), T0.plusSeconds(1))
            .getAsDouble(),
        1e-9);
  }
}
