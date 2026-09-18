package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link RuntimeSignalMonitor} 单元测试。 */
@DisplayName("RuntimeSignalMonitor 单元测试")
class RuntimeSignalMonitorTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  @DisplayName("重复逻辑列车名检测应只返回数量大于 1 的条目")
  void findDuplicateLogicalTrainNamesReturnsOnlyRepeatedEntries() {
    Set<String> duplicates =
        RuntimeSignalMonitor.findDuplicateLogicalTrainNames(
            Map.of(
                "train-1", 2,
                "train-2", 1,
                "train-3", 3));

    assertEquals(Set.of("train-1", "train-3"), duplicates);
  }

  @Test
  @DisplayName("重复逻辑列车 detail 应包含逻辑列车名与实体数量")
  void buildDuplicateLogicalTrainDetailIncludesTrainNameAndCount() {
    String detail = RuntimeSignalMonitor.buildDuplicateLogicalTrainDetail("train-1", 3);

    assertTrue(detail.contains("logicalTrain=train-1"));
    assertTrue(detail.contains("groups=3"));
  }

  @Test
  @DisplayName("split 过渡态不应被当成真实重复")
  void splitTransitionFamilyShouldBeIgnored() {
    assertTrue(
        RuntimeSignalMonitor.isLikelySplitTransitionFamily(
            "train-1", List.of("train-1", "train-1~a", "train-1~b")));
    assertFalse(
        RuntimeSignalMonitor.isLikelySplitTransitionFamily(
            "train-1", List.of("train-1", "train-1")));
    assertFalse(
        RuntimeSignalMonitor.isLikelySplitTransitionFamily(
            "train-1", List.of("train-1", "train-x")));
  }

  @Test
  @DisplayName("已有 progress 的列车不应进入 stale-no-progress 清理计数")
  void staleFtaTrainCounterSkipsTrainsWithProgressEntry() {
    assertFalse(RuntimeSignalMonitor.shouldTrackStaleFtaTrain(true, true, false, false));
    assertTrue(RuntimeSignalMonitor.shouldTrackStaleFtaTrain(false, true, false, false));
    assertTrue(RuntimeSignalMonitor.shouldTrackStaleFtaTrain(false, false, true, false));
    assertTrue(RuntimeSignalMonitor.shouldTrackStaleFtaTrain(false, false, false, true));
    assertFalse(RuntimeSignalMonitor.shouldTrackStaleFtaTrain(false, false, false, false));
  }

  @Test
  @DisplayName("普通列车仅在明确脱轨时进入巡检兜底")
  void unmanagedTrainInspectionOnlyAllowsDerailedSafetyCleanup() {
    assertFalse(RuntimeSignalMonitor.shouldInspectRuntimeGroup(false, false));
    assertTrue(RuntimeSignalMonitor.shouldInspectRuntimeGroup(false, true));
    assertTrue(RuntimeSignalMonitor.shouldInspectRuntimeGroup(true, false));
  }

  @Test
  @DisplayName("改名迁移失败时旧 owner 与当前实体名都必须视为存活")
  void activeRuntimeOwnerNamesPreserveTaggedOwnerDuringRenameFailure() {
    assertEquals(
        Set.of("new-train", "old-train"),
        RuntimeSignalMonitor.activeRuntimeOwnerNames("new-train", "old-train"));
    assertEquals(
        Set.of("same-train"),
        RuntimeSignalMonitor.activeRuntimeOwnerNames("same-train", "same-train"));
  }

  @Test
  @DisplayName("空 groupCounts 应返回空集合")
  void findDuplicateLogicalTrainNamesEmptyInput() {
    assertEquals(Set.of(), RuntimeSignalMonitor.findDuplicateLogicalTrainNames(Map.of()));
    assertEquals(Set.of(), RuntimeSignalMonitor.findDuplicateLogicalTrainNames(null));
  }

  @Test
  @DisplayName("所有列车数量为 1 时应无重复")
  void findDuplicateLogicalTrainNamesNoDuplicates() {
    Set<String> duplicates =
        RuntimeSignalMonitor.findDuplicateLogicalTrainNames(
            Map.of("train-1", 1, "train-2", 1, "train-3", 1));

    assertTrue(duplicates.isEmpty());
  }

  @Test
  @DisplayName("周期巡检遇到 ABI 错误必须触发 fail-closed 且不让错误逃出任务")
  void periodicInspectionLinkageFailureTriggersFailClosedBoundary() {
    AtomicReference<String> failedTrain = new AtomicReference<>();
    AtomicReference<Throwable> capturedFailure = new AtomicReference<>();
    LinkageError failure = new LinkageError("runtime ABI mismatch");

    boolean completed =
        RuntimeSignalMonitor.runWithFailClosedBoundary(
            "train-1",
            () -> {
              throw failure;
            },
            (trainName, throwable) -> {
              failedTrain.set(trainName);
              capturedFailure.set(throwable);
            });

    assertFalse(completed);
    assertEquals("train-1", failedTrain.get());
    assertEquals(failure, capturedFailure.get());
  }

  @Test
  @DisplayName("完整周期后才等待配置间隔，未完成周期由下一 tick 续跑")
  void nextCycleEligibilityWaitsOnlyAfterCompletedCycle() {
    assertEquals(140L, RuntimeSignalMonitor.nextCycleEligibleTick(120L, 20));
    assertEquals(
        Long.MAX_VALUE, RuntimeSignalMonitor.nextCycleEligibleTick(Long.MAX_VALUE - 3L, 20));
  }

  @Test
  @DisplayName("稳定静止且无停因的列车不应被周期心跳重复执行完整授权")
  void fullSignalTickRequiresFirstObservationOrPhysicalMovementChange() {
    assertTrue(RuntimeSignalMonitor.shouldRunFullSignalTick(null, false, false));
    assertFalse(RuntimeSignalMonitor.shouldRunFullSignalTick(false, false, false));
    assertTrue(RuntimeSignalMonitor.shouldRunFullSignalTick(false, true, false));
    assertTrue(RuntimeSignalMonitor.shouldRunFullSignalTick(true, true, false));
    assertTrue(RuntimeSignalMonitor.shouldRunFullSignalTick(true, false, false));
  }

  /**
   * 停车列车必须能被重评估唤醒——这是第二十四轮那个缺陷的核心。
   *
   * <p>原判据对「刚才停、现在还停」的车整个跳过完整 tick，而它依赖的「资源释放会再次触发」 从未被实现（{@code retryTrigger}
   * 全仓只被读两处，都是展示用）。于是阻塞者清空后车仍空等 中位 141 秒，最终只能靠健康监控 182 秒兜底。**这一条是那个洞的回归钉。**
   */
  @Test
  @DisplayName("停车列车到达重评估条件时必须执行完整授权")
  void heldTrainRecheckForcesFullSignalTick() {
    // 唯一与旧行为不同的那一格：静止未变化，但该车有停因且已到重评估条件。
    assertTrue(RuntimeSignalMonitor.shouldRunFullSignalTick(false, false, true));
  }

  /** 从未评估过的停车列车必须先跑一次，否则它要等到占用版本变化才有第一次机会。 */
  @Test
  @DisplayName("首次见到的停车列车必须重评估一次")
  void firstSightOfHeldTrainIsAlwaysDue() {
    assertTrue(RuntimeSignalMonitor.heldRecheckDue(null, null, 7L, T0, Duration.ofSeconds(5)));
    assertTrue(RuntimeSignalMonitor.heldRecheckDue(7L, null, 7L, T0, Duration.ofSeconds(5)));
    assertTrue(RuntimeSignalMonitor.heldRecheckDue(null, T0, 7L, T0, Duration.ofSeconds(5)));
  }

  /**
   * 主因：占用版本推进说明有 claim 变动过——阻塞者释放正是在这一刻发生的。
   *
   * <p>用版本而不是事件是有意的：本项目的事件流已被证明不完备 （{@code SMART_RESOURCE_LIFECYCLE} 在某些移除路径上不发 release）。
   */
  @Test
  @DisplayName("占用版本变化应立即触发重评估，不必等节拍")
  void occupancyVersionChangeTriggersImmediately() {
    assertTrue(
        RuntimeSignalMonitor.heldRecheckDue(7L, T0, 8L, T0.plusMillis(1), Duration.ofSeconds(5)));
  }

  /** 版本没动且节拍未到：不评估。这条决定了开销上界，松掉就等于每 tick 全量重算。 */
  @Test
  @DisplayName("版本未变且节拍未到时不得重评估")
  void steadyStateWithinIntervalIsNotDue() {
    assertFalse(
        RuntimeSignalMonitor.heldRecheckDue(7L, T0, 7L, T0.plusSeconds(4), Duration.ofSeconds(5)));
  }

  /** 兜底节拍：停因不由占用变化解除时（发车门控到期、折返停站结束），仍必须有人再看一眼。 */
  @Test
  @DisplayName("版本未变但节拍到期仍须重评估")
  void cadenceFiresWhenOccupancyIsQuiet() {
    assertTrue(
        RuntimeSignalMonitor.heldRecheckDue(7L, T0, 7L, T0.plusSeconds(5), Duration.ofSeconds(5)));
    assertTrue(
        RuntimeSignalMonitor.heldRecheckDue(7L, T0, 7L, T0.plusSeconds(90), Duration.ofSeconds(5)));
  }

  /** 重评估条件只影响「静止未变化」那一格，其余各格本来就该跑，不能因此变得可跳过。 */
  @Test
  @DisplayName("重评估条件不得让本应执行的格子变成跳过")
  void heldRecheckNeverSuppressesAnExistingTick() {
    for (boolean due : new boolean[] {false, true}) {
      assertTrue(RuntimeSignalMonitor.shouldRunFullSignalTick(null, false, due));
      assertTrue(RuntimeSignalMonitor.shouldRunFullSignalTick(false, true, due));
      assertTrue(RuntimeSignalMonitor.shouldRunFullSignalTick(true, true, due));
      assertTrue(RuntimeSignalMonitor.shouldRunFullSignalTick(true, false, due));
    }
  }
}
