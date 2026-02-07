package org.fetarute.fetaruteTCAddon.dispatcher.health;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
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

  private RuntimeDispatchService.TrainRuntimeState state(
      String name, int idx, SignalAspect signal, double speedBpt, String lastPassedGraphNode) {
    return new RuntimeDispatchService.TrainRuntimeState(
        name,
        idx,
        signal,
        speedBpt,
        lastPassedGraphNode == null
            ? Optional.empty()
            : Optional.of(NodeId.of(lastPassedGraphNode)));
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
    verify(dispatchService, never()).forceRelaunchByName("train1");
  }

  @Test
  @DisplayName("Stall 分级恢复：第二次触发升级到 relaunch")
  void stallEscalatesToRelaunchOnSecondAttempt() {
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.PROCEED, 0.0)));
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());
    when(dispatchService.forceRelaunchByName("train1")).thenReturn(true);

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0); // 首次采样
    monitor.check(Set.of("train1"), t0.plusSeconds(35)); // stage1: refresh
    monitor.check(Set.of("train1"), t0.plusSeconds(50)); // stage2: relaunch

    verify(dispatchService, atLeastOnce()).refreshSignalByName("train1");
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
  @DisplayName("中间 waypoint 推进应重置 progress stuck 计时")
  void nonRouteWaypointProgressResetsProgressTimer() {
    List<HealthAlert> alerts = new ArrayList<>();
    alertBus.subscribe(alerts::add);
    when(dispatchService.getTrainState("train1"))
        .thenReturn(
            Optional.of(state("train1", 4, SignalAspect.PROCEED, 0.2, "SURC:S:CSB:1")),
            Optional.of(state("train1", 4, SignalAspect.PROCEED, 0.2, "SURC:JBS:CSB:1:004")),
            Optional.of(state("train1", 4, SignalAspect.PROCEED, 0.2, "SURC:JBS:CSB:1:003")));
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());

    monitor.setProgressStuckThreshold(Duration.ofSeconds(60));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);
    TrainHealthMonitor.CheckResult r1 = monitor.check(Set.of("train1"), t0.plusSeconds(65));
    TrainHealthMonitor.CheckResult r2 = monitor.check(Set.of("train1"), t0.plusSeconds(130));

    assertEquals(0, r1.progressStuckCount(), "经过中间 waypoint 后不应触发 stuck");
    assertEquals(0, r2.progressStuckCount(), "持续经过中间 waypoint 时不应触发 stuck");
    assertTrue(alerts.isEmpty(), "不应产生 progress stuck 告警");
    verify(dispatchService, never()).refreshSignalByName("train1");
  }

  @Test
  @DisplayName("进度停滞分级恢复：refresh -> reissue -> relaunch")
  void progressStuckEscalatesRecoveryStages() {
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.PROCEED, 0.5)));
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());
    when(dispatchService.reissueDestinationByName("train1")).thenReturn(true);
    when(dispatchService.forceRelaunchByName("train1")).thenReturn(true);

    monitor.setProgressStuckThreshold(Duration.ofSeconds(60));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0); // 首次采样
    monitor.check(Set.of("train1"), t0.plusSeconds(65)); // stage1: refresh
    monitor.check(Set.of("train1"), t0.plusSeconds(80)); // stage2: reissue
    monitor.check(Set.of("train1"), t0.plusSeconds(95)); // stage3: relaunch

    verify(dispatchService, atLeastOnce()).refreshSignalByName("train1");
    verify(dispatchService).reissueDestinationByName("train1");
    verify(dispatchService).forceRelaunchByName("train1");
  }

  @Test
  @DisplayName("STOP 信号下 progress stuck 在宽限内不告警")
  void stopSignalProgressStuckWithinGraceIsIgnored() {
    List<HealthAlert> alerts = new ArrayList<>();
    alertBus.subscribe(alerts::add);
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.STOP, 0.0)));
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());

    monitor.setProgressStuckThreshold(Duration.ofSeconds(60));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(180));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);
    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train1"), t0.plusSeconds(120));

    assertEquals(0, result.progressStuckCount());
    assertTrue(alerts.isEmpty());
    verify(dispatchService, never()).refreshSignalByName("train1");
  }

  @Test
  @DisplayName("STOP 信号下 progress stuck 超宽限后分级升级到 relaunch")
  void stopSignalProgressStuckEscalatesToRelaunchAfterGrace() {
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.STOP, 0.0)));
    when(dispatchService.recentBlockerTrains(eq("train1"), any())).thenReturn(Set.of());
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());
    when(dispatchService.reissueDestinationByName("train1")).thenReturn(false);
    when(dispatchService.forceRelaunchByName("train1")).thenReturn(true);

    monitor.setProgressStuckThreshold(Duration.ofSeconds(10));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(20));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0); // 首次采样
    monitor.check(Set.of("train1"), t0.plusSeconds(25)); // stage1: refresh-stop
    monitor.check(Set.of("train1"), t0.plusSeconds(40)); // stage2: reissue-stop
    monitor.check(Set.of("train1"), t0.plusSeconds(55)); // stage3: relaunch-stop

    verify(dispatchService, atLeastOnce()).refreshSignalByName("train1");
    verify(dispatchService).reissueDestinationByName("train1");
    verify(dispatchService).forceRelaunchByName("train1");
  }

  @Test
  @DisplayName("互相阻塞：在 STOP 宽限内触发成对解锁（refresh 双车）")
  void mutualDeadlockTriggersPairRefreshBeforeStopGrace() {
    List<HealthAlert> alerts = new ArrayList<>();
    alertBus.subscribe(alerts::add);
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.STOP, 0.0)));
    when(dispatchService.recentBlockerTrains(eq("trainA"), any())).thenReturn(Set.of("trainB"));
    when(dispatchService.recentBlockerTrains(eq("trainB"), any())).thenReturn(Set.of("trainA"));

    monitor.setProgressStuckThreshold(Duration.ofSeconds(300));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(180));

    Instant t0 = Instant.now();
    monitor.check(Set.of("trainA", "trainB"), t0); // 首次采样
    TrainHealthMonitor.CheckResult result =
        monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(50));

    assertEquals(1, result.progressStuckCount(), "仅由一侧执行互卡修复");
    assertEquals(1, result.fixedCount(), "应执行一轮成对 refresh");
    verify(dispatchService).refreshSignalByName("trainA");
    verify(dispatchService).refreshSignalByName("trainB");
    verify(dispatchService, never()).reissueDestinationByName(anyString());
    verify(dispatchService, never()).forceRelaunchByName(anyString());
    assertFalse(alerts.isEmpty());
  }

  @Test
  @DisplayName("互相阻塞分级恢复：refresh -> reissue -> relaunch")
  void mutualDeadlockEscalatesRecoveryStages() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.STOP, 0.0)));
    when(dispatchService.recentBlockerTrains(eq("trainA"), any())).thenReturn(Set.of("trainB"));
    when(dispatchService.recentBlockerTrains(eq("trainB"), any())).thenReturn(Set.of("trainA"));
    when(dispatchService.reissueDestinationByName("trainA")).thenReturn(true);
    when(dispatchService.forceRelaunchByName("trainA")).thenReturn(true);

    monitor.setProgressStuckThreshold(Duration.ofSeconds(300));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(180));

    Instant t0 = Instant.now();
    monitor.check(Set.of("trainA", "trainB"), t0); // 首次采样
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(50)); // stage1 refresh
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(65)); // stage2 reissue
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(80)); // stage3 relaunch

    verify(dispatchService, atLeastOnce()).refreshSignalByName("trainA");
    verify(dispatchService, atLeastOnce()).refreshSignalByName("trainB");
    verify(dispatchService).reissueDestinationByName("trainA");
    verify(dispatchService).forceRelaunchByName("trainA");
  }

  @Test
  @DisplayName("手动强制解锁：不等待阈值，直接升级到 reissue/relaunch")
  void forceUnlockNowEscalatesImmediately() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.STOP, 0.0)));
    when(dispatchService.recentBlockerTrains(eq("trainA"), any())).thenReturn(Set.of("trainB"));
    when(dispatchService.recentBlockerTrains(eq("trainB"), any())).thenReturn(Set.of("trainA"));
    when(dispatchService.reissueDestinationByName("trainA")).thenReturn(false);
    when(dispatchService.reissueDestinationByName("trainB")).thenReturn(false);
    when(dispatchService.forceRelaunchByName("trainA")).thenReturn(true);

    int fixed = monitor.forceUnlockNow(Set.of("trainA", "trainB"), Instant.now());

    assertEquals(1, fixed, "应在单次手动解锁中完成一对互卡修复");
    verify(dispatchService).refreshSignalByName("trainA");
    verify(dispatchService).refreshSignalByName("trainB");
    verify(dispatchService).reissueDestinationByName("trainA");
    verify(dispatchService).reissueDestinationByName("trainB");
    verify(dispatchService).forceRelaunchByName("trainA");
  }

  @Test
  @DisplayName("手动强制解锁：非互卡场景不触发")
  void forceUnlockNowSkipsNonMutualBlockers() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.CAUTION, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.CAUTION, 0.0)));
    when(dispatchService.recentBlockerTrains(eq("trainA"), any())).thenReturn(Set.of("trainB"));
    when(dispatchService.recentBlockerTrains(eq("trainB"), any())).thenReturn(Set.of("trainC"));

    int fixed = monitor.forceUnlockNow(Set.of("trainA", "trainB"), Instant.now());

    assertEquals(0, fixed, "非互卡应跳过，避免误触发");
    verify(dispatchService, never()).reissueDestinationByName(anyString());
    verify(dispatchService, never()).forceRelaunchByName(anyString());
  }

  @Test
  @DisplayName("手动强制解锁：STOP 单车阻塞时尝试 reissue")
  void forceUnlockNowReissuesSingleBlockedStopTrain() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.PROCEED, 0.5)));
    when(dispatchService.recentBlockerTrains(eq("trainA"), any())).thenReturn(Set.of("trainB"));
    when(dispatchService.recentBlockerTrains(eq("trainB"), any())).thenReturn(Set.of("trainC"));
    when(dispatchService.reissueDestinationByName("trainA")).thenReturn(true);

    int fixed = monitor.forceUnlockNow(Set.of("trainA", "trainB"), Instant.now());

    assertEquals(1, fixed, "单车 STOP 阻塞时应尝试 reissue");
    verify(dispatchService).refreshSignalByName("trainA");
    verify(dispatchService).reissueDestinationByName("trainA");
  }

  @Test
  @DisplayName("手动强制解锁：STOP 单车阻塞时 reissue 失败后升级 relaunch")
  void forceUnlockNowRelaunchesSingleBlockedStopTrainWhenReissueFails() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.PROCEED, 0.5)));
    when(dispatchService.recentBlockerTrains(eq("trainA"), any())).thenReturn(Set.of("trainB"));
    when(dispatchService.recentBlockerTrains(eq("trainB"), any())).thenReturn(Set.of("trainC"));
    when(dispatchService.reissueDestinationByName("trainA")).thenReturn(false);
    when(dispatchService.forceRelaunchByName("trainA")).thenReturn(true);

    int fixed = monitor.forceUnlockNow(Set.of("trainA", "trainB"), Instant.now());

    assertEquals(1, fixed, "单车 STOP 阻塞时应升级到 relaunch");
    verify(dispatchService).refreshSignalByName("trainA");
    verify(dispatchService).reissueDestinationByName("trainA");
    verify(dispatchService).forceRelaunchByName("trainA");
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
