package org.fetarute.fetaruteTCAddon.dispatcher.health;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DwellRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link TrainHealthMonitor} 单元测试。 */
@DisplayName("TrainHealthMonitor 单元测试")
class TrainHealthMonitorTest {

  private RuntimeDispatchService dispatchService;
  private DwellRegistry dwellRegistry;
  private HealthAlertBus alertBus;
  private List<String> debugLogs;
  private TrainHealthMonitor monitor;

  @BeforeEach
  void setUp() {
    dispatchService = mock(RuntimeDispatchService.class);
    dwellRegistry = mock(DwellRegistry.class);
    alertBus = new HealthAlertBus();
    debugLogs = new ArrayList<>();
    monitor = new TrainHealthMonitor(dispatchService, dwellRegistry, alertBus, debugLogs::add);
  }

  private RuntimeDispatchService.TrainRuntimeState state(
      String name, int idx, SignalAspect signal, double speedBpt) {
    return new RuntimeDispatchService.TrainRuntimeState(name, idx, signal, speedBpt);
  }

  @Test
  @DisplayName("首次采样：不触发告警")
  void firstSampleNoAlert() {
    List<HealthAlert> alerts = new ArrayList<>();
    alertBus.subscribe(alerts::add);
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.PROCEED, 0.0)));
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());

    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train1"), Instant.now());

    assertEquals(0, result.stallCount(), "首次采样不应检测到 stall");
    assertEquals(0, result.progressStuckCount());
    assertTrue(alerts.isEmpty());
  }

  @Test
  @DisplayName("正常运行：有速度时不触发 stall")
  void normalRunningNoStall() {
    List<HealthAlert> alerts = new ArrayList<>();
    alertBus.subscribe(alerts::add);
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.PROCEED, 0.5)));
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0); // 首次采样

    // 35 秒后仍有速度
    Instant t1 = t0.plusSeconds(35);
    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train1"), t1);

    assertEquals(0, result.stallCount(), "有速度时不应检测到 stall");
    assertTrue(alerts.isEmpty());
  }

  @Test
  @DisplayName("Stall 检测：PROCEED 信号但静止超过阈值")
  void stallDetection() {
    List<HealthAlert> alerts = new ArrayList<>();
    alertBus.subscribe(alerts::add);
    // 低速（< 0.01 bpt）+ PROCEED
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.PROCEED, 0.0)));
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());
    doNothing().when(dispatchService).refreshSignalByName("train1");
    when(dispatchService.forceRelaunchByName("train1")).thenReturn(true);

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0); // 首次采样

    // 35 秒后仍然静止（超过 30 秒阈值）
    Instant t1 = t0.plusSeconds(35);
    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train1"), t1);

    assertEquals(1, result.stallCount(), "应检测到 1 个 stall");
    assertEquals(1, result.fixedCount(), "应尝试修复");
    assertEquals(1, alerts.size());
    assertEquals(HealthAlert.AlertType.STALL, alerts.get(0).type());
    assertTrue(alerts.get(0).autoFixed());

    // 验证调用了修复方法
    verify(dispatchService).refreshSignalByName("train1");
    verify(dispatchService).forceRelaunchByName("train1");
  }

  @Test
  @DisplayName("Stall 排除：STOP 信号时静止不算 stall")
  void stopSignalNoStall() {
    List<HealthAlert> alerts = new ArrayList<>();
    alertBus.subscribe(alerts::add);
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.STOP, 0.0)));
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);
    Instant t1 = t0.plusSeconds(35);
    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train1"), t1);

    assertEquals(0, result.stallCount(), "STOP 信号时静止不应视为 stall");
    assertTrue(alerts.isEmpty());
  }

  @Test
  @DisplayName("Stall 排除：正在停站（dwell）时静止不算 stall")
  void dwellingNoStall() {
    List<HealthAlert> alerts = new ArrayList<>();
    alertBus.subscribe(alerts::add);
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.PROCEED, 0.0)));
    // 正在停站
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.of(15));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);
    Instant t1 = t0.plusSeconds(35);
    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train1"), t1);

    assertEquals(0, result.stallCount(), "停站期间静止不应视为 stall");
    assertTrue(alerts.isEmpty());
  }

  @Test
  @DisplayName("进度停滞检测：进度长时间不推进")
  void progressStuckDetection() {
    List<HealthAlert> alerts = new ArrayList<>();
    alertBus.subscribe(alerts::add);
    // 进度保持在 0
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.PROCEED, 0.5)));
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());
    doNothing().when(dispatchService).refreshSignalByName("train1");

    monitor.setProgressStuckThreshold(Duration.ofSeconds(60));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);

    // 65 秒后进度仍为 0
    Instant t1 = t0.plusSeconds(65);
    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train1"), t1);

    assertEquals(1, result.progressStuckCount(), "应检测到进度停滞");
    assertEquals(1, alerts.size());
    assertEquals(HealthAlert.AlertType.PROGRESS_STUCK, alerts.get(0).type());
  }

  @Test
  @DisplayName("进度推进：进度变化时重置计时器")
  void progressAdvancesResetTimer() {
    List<HealthAlert> alerts = new ArrayList<>();
    alertBus.subscribe(alerts::add);
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());

    monitor.setProgressStuckThreshold(Duration.ofSeconds(60));

    Instant t0 = Instant.now();
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.PROCEED, 0.5)));
    monitor.check(Set.of("train1"), t0);

    // 40 秒后进度推进
    Instant t1 = t0.plusSeconds(40);
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 1, SignalAspect.PROCEED, 0.5)));
    monitor.check(Set.of("train1"), t1);

    // 再过 40 秒（累计进度只有 40 秒不变，未超过 60 秒阈值）
    Instant t2 = t1.plusSeconds(40);
    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train1"), t2);

    assertEquals(0, result.progressStuckCount(), "进度推进后计时器应重置");
    assertTrue(alerts.isEmpty());
  }

  @Test
  @DisplayName("禁用自动修复：不执行修复操作")
  void autoFixDisabled() {
    List<HealthAlert> alerts = new ArrayList<>();
    alertBus.subscribe(alerts::add);
    monitor.setAutoFixEnabled(false);

    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.PROCEED, 0.0)));
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);
    Instant t1 = t0.plusSeconds(35);
    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train1"), t1);

    assertEquals(1, result.stallCount());
    assertEquals(0, result.fixedCount(), "禁用自动修复时不应计入 fixedCount");
    verify(dispatchService, never()).refreshSignalByName(any());
    verify(dispatchService, never()).forceRelaunchByName(any());
  }

  @Test
  @DisplayName("自定义阈值：10 秒触发 stall")
  void customStallThreshold() {
    List<HealthAlert> alerts = new ArrayList<>();
    alertBus.subscribe(alerts::add);
    monitor.setStallThreshold(Duration.ofSeconds(10));

    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.PROCEED, 0.0)));
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());
    when(dispatchService.forceRelaunchByName("train1")).thenReturn(true);

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);
    Instant t1 = t0.plusSeconds(15);
    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train1"), t1);

    assertEquals(1, result.stallCount(), "应按自定义阈值触发");
  }

  @Test
  @DisplayName("列车消失：清理快照")
  void trainRemovedCleanupSnapshot() {
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.PROCEED, 0.5)));
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);

    // 列车消失
    Instant t1 = t0.plusSeconds(5);
    monitor.check(Set.of(), t1);

    // 列车重新出现应视为首次采样
    Instant t2 = t1.plusSeconds(5);
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.PROCEED, 0.0)));
    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train1"), t2);

    // 不应立即触发 stall（因为是"首次"采样）
    assertEquals(0, result.stallCount());
  }

  @Test
  @DisplayName("clear：清除所有快照")
  void clearSnapshots() {
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.PROCEED, 0.5)));
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);

    monitor.clear();

    // clear 后视为首次采样
    Instant t1 = t0.plusSeconds(5);
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.PROCEED, 0.0)));
    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train1"), t1);

    assertEquals(0, result.stallCount(), "clear 后应视为首次采样");
  }

  @Test
  @DisplayName("多列车检测：各自独立计时")
  void multipleTrains() {
    List<HealthAlert> alerts = new ArrayList<>();
    alertBus.subscribe(alerts::add);
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.forceRelaunchByName(anyString())).thenReturn(true);

    // train1 静止，train2 正常运行
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.PROCEED, 0.0)));
    when(dispatchService.getTrainState("train2"))
        .thenReturn(Optional.of(state("train2", 0, SignalAspect.PROCEED, 0.5)));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1", "train2"), t0);

    Instant t1 = t0.plusSeconds(35);
    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train1", "train2"), t1);

    assertEquals(1, result.stallCount(), "只有 train1 应触发 stall");
    assertEquals(1, alerts.size());
    assertEquals("train1", alerts.get(0).trainName());
  }

  @Test
  @DisplayName("getTrainState 返回空：跳过该列车")
  void trainStateNotFound() {
    when(dispatchService.getTrainState("train1")).thenReturn(Optional.empty());

    Instant t0 = Instant.now();
    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train1"), t0);

    assertEquals(0, result.stallCount());
    assertEquals(0, result.progressStuckCount());
  }

  @Test
  @DisplayName("DwellRegistry 为 null：不排除任何列车")
  void nullDwellRegistry() {
    List<HealthAlert> alerts = new ArrayList<>();
    alertBus.subscribe(alerts::add);
    // 使用 null dwellRegistry
    TrainHealthMonitor monitorNoDwell =
        new TrainHealthMonitor(dispatchService, null, alertBus, debugLogs::add);

    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.PROCEED, 0.0)));
    when(dispatchService.forceRelaunchByName("train1")).thenReturn(true);

    Instant t0 = Instant.now();
    monitorNoDwell.check(Set.of("train1"), t0);
    Instant t1 = t0.plusSeconds(35);
    TrainHealthMonitor.CheckResult result = monitorNoDwell.check(Set.of("train1"), t1);

    assertEquals(1, result.stallCount(), "无 dwellRegistry 时也应检测 stall");
  }
}
