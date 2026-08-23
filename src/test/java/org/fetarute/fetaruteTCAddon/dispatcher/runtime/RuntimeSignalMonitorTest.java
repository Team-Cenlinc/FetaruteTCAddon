package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link RuntimeSignalMonitor} 单元测试。 */
@DisplayName("RuntimeSignalMonitor 单元测试")
class RuntimeSignalMonitorTest {

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
  @DisplayName("稳定静止列车不应被周期心跳重复执行完整授权")
  void fullSignalTickRequiresFirstObservationOrPhysicalMovementChange() {
    assertTrue(RuntimeSignalMonitor.shouldRunFullSignalTick(null, false));
    assertFalse(RuntimeSignalMonitor.shouldRunFullSignalTick(false, false));
    assertTrue(RuntimeSignalMonitor.shouldRunFullSignalTick(false, true));
    assertTrue(RuntimeSignalMonitor.shouldRunFullSignalTick(true, true));
    assertTrue(RuntimeSignalMonitor.shouldRunFullSignalTick(true, false));
  }
}
