package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link RuntimeDispatchWorkCycle} 的主线程预算行为测试。 */
@DisplayName("RuntimeDispatchWorkCycle 单元测试")
class RuntimeDispatchWorkCycleTest {

  @Test
  @DisplayName("预算耗尽时仅处理已开始的候选并保留后续候选")
  void budgetExhaustionLeavesRemainingCandidatesForNextCycle() {
    AtomicLong nowNanos = new AtomicLong();
    RuntimeDispatchWorkCycle<String> cycle =
        new RuntimeDispatchWorkCycle<>(Duration.ofMillis(5), nowNanos::get);
    List<String> processed = new ArrayList<>();

    RuntimeDispatchWorkCycle.CycleResult first =
        cycle.run(
            List.of("train-a", "train-b", "train-c"),
            trainName -> {
              processed.add(trainName);
              nowNanos.addAndGet(Duration.ofMillis(3).toNanos());
            });

    assertEquals(List.of("train-a", "train-b"), processed);
    assertEquals(2, first.processedCount());
    assertEquals(1, first.remainingCount());
    assertFalse(first.completed());
  }

  @Test
  @DisplayName("未完成周期不得被新快照插队")
  void incompleteCycleRetainsItsOriginalSnapshotUntilCompletion() {
    AtomicLong nowNanos = new AtomicLong();
    RuntimeDispatchWorkCycle<String> cycle =
        new RuntimeDispatchWorkCycle<>(Duration.ofMillis(5), nowNanos::get);
    List<String> processed = new ArrayList<>();

    cycle.run(
        List.of("train-a", "train-b", "train-c"),
        trainName -> {
          processed.add(trainName);
          nowNanos.addAndGet(Duration.ofMillis(3).toNanos());
        });
    RuntimeDispatchWorkCycle.CycleResult resumed =
        cycle.run(
            List.of("replacement-train"),
            trainName -> {
              processed.add(trainName);
              nowNanos.addAndGet(Duration.ofMillis(3).toNanos());
            });

    assertEquals(List.of("train-a", "train-b", "train-c"), processed);
    assertEquals(1, resumed.processedCount());
    assertEquals(0, resumed.remainingCount());
    assertTrue(resumed.completed());
  }

  @Test
  @DisplayName("失败的候选不得从游标中丢失")
  void failedCandidateRemainsPendingForTheNextAttempt() {
    AtomicLong nowNanos = new AtomicLong();
    RuntimeDispatchWorkCycle<String> cycle =
        new RuntimeDispatchWorkCycle<>(Duration.ofMillis(5), nowNanos::get);

    assertThrows(
        IllegalStateException.class,
        () ->
            cycle.run(
                List.of("train-a", "train-b"),
                trainName -> {
                  throw new IllegalStateException(trainName);
                }));

    List<String> retried = new ArrayList<>();
    RuntimeDispatchWorkCycle.CycleResult result =
        cycle.run(
            List.of("replacement-train"),
            trainName -> {
              retried.add(trainName);
              nowNanos.addAndGet(Duration.ofMillis(1).toNanos());
            });

    assertEquals(List.of("train-a", "train-b"), retried);
    assertEquals(2, result.processedCount());
    assertTrue(result.completed());
  }
}
