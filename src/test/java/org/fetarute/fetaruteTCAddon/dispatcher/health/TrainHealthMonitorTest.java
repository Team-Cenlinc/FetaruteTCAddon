package org.fetarute.fetaruteTCAddon.dispatcher.health;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DwellRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherController;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalComputationTrace;
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
    when(dispatchService.smartDispatcherMode()).thenReturn(SmartDispatcherMode.ENFORCE);
    when(dispatchService.smartRecoveryInput(anyString(), any(), any()))
        .thenAnswer(
            invocation ->
                RuntimeDispatchService.SmartRecoveryInput.fallback(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2)));
    when(dispatchService.applySmartForwardUnlock(any()))
        .thenReturn(RuntimeDispatchService.SmartRecoveryActionResult.skipped("not-candidate"));
    when(dispatchService.applySmartSelfOwnedStaleRetainRelease(any()))
        .thenReturn(RuntimeDispatchService.SmartRecoveryActionResult.skipped("not-candidate"));
    when(dispatchService.applySmartDrainUnlock(any()))
        .thenReturn(RuntimeDispatchService.SmartRecoveryActionResult.skipped("not-candidate"));
    stubDefaultDestroyPrecheck();
  }

  private void stubDefaultDestroyPrecheck() {
    when(dispatchService.reviewDeadlockDestroyCandidate(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyBoolean(),
            anyBoolean(),
            anyBoolean(),
            anyBoolean(),
            anyBoolean(),
            anyBoolean(),
            any(),
            anyBoolean(),
            anyBoolean(),
            any(),
            any()))
        .thenAnswer(
            invocation -> {
              boolean weak = invocation.getArgument(5);
              boolean allBlockersLiveHard = invocation.getArgument(6);
              boolean safeDrainCandidate = invocation.getArgument(7);
              Duration persisted = invocation.getArgument(14);
              Duration threshold = invocation.getArgument(15);
              if (weak) {
                return SmartDispatcherController.DeadlockDestroyReview.rejected(
                    "weak-blocker-diagnostic-only");
              }
              if (!allBlockersLiveHard) {
                return SmartDispatcherController.DeadlockDestroyReview.rejected(
                    "blockers-not-all-live-hard");
              }
              if (safeDrainCandidate) {
                return SmartDispatcherController.DeadlockDestroyReview.rejected(
                    "safe-drain-candidate-exists");
              }
              if (persisted != null && threshold != null && persisted.compareTo(threshold) < 0) {
                return SmartDispatcherController.DeadlockDestroyReview.rejected(
                    "threshold-not-reached");
              }
              return SmartDispatcherController.DeadlockDestroyReview.allowed(
                  "confirmed-live-hard-cycle",
                  List.of("safe-drain", "stale-release", "forward-unlock", "priority-scheduling"));
            });
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

  private void stubConfirmedDeadlock(String firstTrain, String secondTrain) {
    stubConfirmedDeadlock(
        firstTrain,
        secondTrain,
        "single:test:A:B",
        CorridorDirection.A_TO_B,
        CorridorDirection.B_TO_A,
        context(firstTrain, 5, RouteOperationType.OPERATION, false, false),
        context(secondTrain, 7, RouteOperationType.OPERATION, false, false));
  }

  private void stubConfirmedDeadlock(
      String firstTrain,
      String secondTrain,
      String conflictKey,
      CorridorDirection firstDirection,
      CorridorDirection secondDirection,
      RuntimeDispatchService.DeadlockTrainContext firstContext,
      RuntimeDispatchService.DeadlockTrainContext secondContext) {
    when(dispatchService.recentBlockerTrains(eq(firstTrain), any()))
        .thenReturn(Set.of(secondTrain));
    when(dispatchService.recentBlockerTrains(eq(secondTrain), any()))
        .thenReturn(Set.of(firstTrain));
    when(dispatchService.recentDeadlockBlockers(eq(firstTrain), any()))
        .thenReturn(deadlockSnapshot(secondTrain, conflictKey, secondDirection));
    when(dispatchService.recentDeadlockBlockers(eq(secondTrain), any()))
        .thenReturn(deadlockSnapshot(firstTrain, conflictKey, firstDirection));
    when(dispatchService.deadlockTrainContext(firstTrain)).thenReturn(Optional.of(firstContext));
    when(dispatchService.deadlockTrainContext(secondTrain)).thenReturn(Optional.of(secondContext));
  }

  private RuntimeDispatchService.DeadlockBlockerSnapshot deadlockSnapshot(
      String blockerTrain, String conflictKey, CorridorDirection direction) {
    return new RuntimeDispatchService.DeadlockBlockerSnapshot(
        Set.of(
            new RuntimeDispatchService.DeadlockBlockerInfo(
                blockerTrain, conflictKey, Optional.ofNullable(direction))),
        Instant.now());
  }

  private void stubFollowerBlockedByLeader(
      RuntimeDispatchService.SmartRecoveryInput leaderInput,
      RuntimeDispatchService.DeadlockTrainContext leaderContext) {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("follower"))
        .thenReturn(Optional.of(state("follower", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("leader"))
        .thenReturn(Optional.of(state("leader", 7, SignalAspect.STOP, 0.0)));
    RuntimeDispatchService.DeadlockBlockerInfo leaderBlocker =
        new RuntimeDispatchService.DeadlockBlockerInfo(
            "leader",
            "",
            Optional.empty(),
            "leader",
            "NODE:X",
            "WAIT_FOR",
            "TEST",
            "NORMAL",
            "test",
            1L,
            1L);
    when(dispatchService.recentDeadlockBlockers(eq("follower"), any()))
        .thenReturn(
            new RuntimeDispatchService.DeadlockBlockerSnapshot(
                Set.of(leaderBlocker), Instant.now()));
    when(dispatchService.recentDeadlockBlockers(eq("leader"), any()))
        .thenReturn(new RuntimeDispatchService.DeadlockBlockerSnapshot(Set.of(), Instant.now()));
    when(dispatchService.deadlockTrainContext("follower"))
        .thenReturn(
            Optional.of(context("follower", 5, RouteOperationType.OPERATION, false, false)));
    when(dispatchService.deadlockTrainContext("leader")).thenReturn(Optional.of(leaderContext));
    when(dispatchService.smartRecoveryInput(eq("leader"), any(), any())).thenReturn(leaderInput);
    when(dispatchService.smartRecoveryInput(eq("follower"), any(), any()))
        .thenReturn(
            smartRecoveryInput(
                "follower", SignalComputationTrace.TokenState.PENDING, true, "waiting-for-leader"));
  }

  private RuntimeDispatchService.DeadlockTrainContext context(
      String trainName,
      int progressIndex,
      RouteOperationType operationType,
      boolean depotRelated,
      boolean nearRouteEnd) {
    return context(
        trainName, progressIndex, operationType, depotRelated, nearRouteEnd, false, false);
  }

  private RuntimeDispatchService.DeadlockTrainContext context(
      String trainName,
      int progressIndex,
      RouteOperationType operationType,
      boolean depotRelated,
      boolean nearRouteEnd,
      boolean hasPassengers,
      boolean manualHold) {
    return new RuntimeDispatchService.DeadlockTrainContext(
        trainName,
        progressIndex,
        10,
        SignalAspect.STOP,
        0.0,
        operationType,
        0,
        false,
        false,
        false,
        depotRelated,
        nearRouteEnd,
        hasPassengers,
        manualHold);
  }

  private RuntimeDispatchService.SmartRecoveryInput smartRecoveryInput(
      String trainName, SignalAspect signal, boolean movementInhibited, String primaryReason) {
    return new RuntimeDispatchService.SmartRecoveryInput(
        trainName,
        65,
        signal,
        movementInhibited,
        movementInhibited
            ? SignalComputationTrace.TokenState.PENDING
            : SignalComputationTrace.TokenState.NONE,
        false,
        0,
        Set.of(),
        NodeId.of("A"),
        NodeId.of("B"),
        "route:test",
        0,
        "A",
        false,
        false,
        false,
        false,
        primaryReason);
  }

  private RuntimeDispatchService.SmartRecoveryInput smartRecoveryInput(
      String trainName,
      SignalComputationTrace.TokenState tokenState,
      boolean destinationPresent,
      String primaryReason) {
    return new RuntimeDispatchService.SmartRecoveryInput(
        trainName,
        90,
        SignalAspect.STOP,
        tokenState == SignalComputationTrace.TokenState.INVALID,
        tokenState,
        destinationPresent,
        0,
        Set.of(),
        NodeId.of("A"),
        NodeId.of("B"),
        "route:test",
        0,
        "A",
        false,
        false,
        false,
        false,
        primaryReason);
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
    doReturn(RuntimeDispatchService.SignalRefreshResult.unresolved("train1", "test"))
        .when(dispatchService)
        .refreshSignalByName("train1");
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
    doReturn(RuntimeDispatchService.SignalRefreshResult.unresolved("train1", "test"))
        .when(dispatchService)
        .refreshSignalByName("train1");

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
        .thenReturn(Optional.of(state("train1", 4, SignalAspect.PROCEED, 0.2, "SURC:S:CSB:1")))
        .thenReturn(
            Optional.of(state("train1", 4, SignalAspect.PROCEED, 0.2, "SURC:JBS:CSB:1:004")))
        .thenReturn(
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
    verify(dispatchService, atLeastOnce()).reissueDestinationByName("train1");
    verify(dispatchService).forceRelaunchByName("train1");
  }

  @Test
  @DisplayName("OBSERVE_ONLY 下 progress stuck 只输出 trace，不执行恢复副作用")
  void smartDispatcherObserveOnlySuppressesProgressStuckRecoveryMutations() {
    when(dispatchService.smartDispatcherMode()).thenReturn(SmartDispatcherMode.OBSERVE_ONLY);
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.PROCEED, 0.5)));
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());

    monitor.setProgressStuckThreshold(Duration.ofSeconds(60));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);
    monitor.check(Set.of("train1"), t0.plusSeconds(65));
    monitor.check(Set.of("train1"), t0.plusSeconds(80));
    monitor.check(Set.of("train1"), t0.plusSeconds(95));

    verify(dispatchService, never()).refreshSignalByName("train1");
    verify(dispatchService, never()).reissueDestinationByName("train1");
    verify(dispatchService, never()).forceRelaunchByName("train1");
    verify(dispatchService, never()).reapplyHardStopByName(eq("train1"), anyString());
    assertTrue(debugLogs.stream().anyMatch(line -> line.contains("SMART_DISPATCH_MODE")));
    assertTrue(debugLogs.stream().anyMatch(line -> line.contains("SMART_DISPATCH_EFFECT_CLASS")));
    assertTrue(
        debugLogs.stream()
            .anyMatch(line -> line.contains("SMART_DISPATCH_ACTION_SUPPRESSED_BY_MODE")));
  }

  @Test
  @DisplayName("PROGRESS_STUCK 会桥接到 Smart recovery input trace")
  void progressStuckBridgesIntoSmartRecoveryInputTrace() {
    RuntimeDispatchService.SmartRecoveryInput input =
        smartRecoveryInput("train1", SignalAspect.STOP, true, "signal-authority-window-exceeded");
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.STOP, 0.0)));
    when(dispatchService.smartRecoveryInput(eq("train1"), any(), eq(SignalAspect.STOP)))
        .thenReturn(input);
    when(dispatchService.applySmartForwardUnlock(input))
        .thenReturn(RuntimeDispatchService.SmartRecoveryActionResult.skipped("not-candidate"));
    when(dispatchService.recentBlockerTrains(eq("train1"), any())).thenReturn(Set.of());
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());

    monitor.setProgressStuckThreshold(Duration.ofSeconds(10));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(20));
    monitor.setDeadlockThreshold(Duration.ofSeconds(300));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);
    monitor.check(Set.of("train1"), t0.plusSeconds(65));

    assertTrue(debugLogs.stream().anyMatch(line -> line.contains("SMART_STUCK_TRAIN_DETECTED")));
    assertTrue(debugLogs.stream().anyMatch(line -> line.contains("SMART_RECOVERY_INPUT")));
    assertTrue(
        debugLogs.stream()
            .anyMatch(line -> line.contains("primaryReason=signal-authority-window-exceeded")));
  }

  @Test
  @DisplayName("authority-window-exceeded 且无 blocker 时优先进入 Smart forward unlock")
  void smartForwardUnlockCandidateWhenAuthorityWindowExceededButNoBlockers() {
    RuntimeDispatchService.SmartRecoveryInput input =
        smartRecoveryInput("train1", SignalAspect.STOP, true, "signal-authority-window-exceeded");
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.STOP, 0.0)));
    when(dispatchService.smartRecoveryInput(eq("train1"), any(), eq(SignalAspect.STOP)))
        .thenReturn(input);
    when(dispatchService.applySmartForwardUnlock(input))
        .thenReturn(
            new RuntimeDispatchService.SmartRecoveryActionResult(
                true,
                true,
                "SMART_FORWARD_UNLOCK_APPLIED",
                "authority-token-repair",
                org.fetarute
                    .fetaruteTCAddon
                    .dispatcher
                    .runtime
                    .supervisor
                    .DispatchEffectClass
                    .SIGNAL_CONSTRAINT));
    when(dispatchService.recentBlockerTrains(eq("train1"), any())).thenReturn(Set.of());
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());

    monitor.setProgressStuckThreshold(Duration.ofSeconds(10));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(20));
    monitor.setDeadlockThreshold(Duration.ofSeconds(300));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);
    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train1"), t0.plusSeconds(65));

    assertEquals(1, result.fixedCount());
    verify(dispatchService).applySmartForwardUnlock(input);
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertTrue(
        debugLogs.stream()
            .anyMatch(line -> line.contains("recoveryDecision=SMART_FORWARD_UNLOCK_APPLIED")));
  }

  @Test
  @DisplayName("Smart forward unlock 在 OBSERVE_ONLY 下只作为候选，不触发旧恢复副作用")
  void smartForwardUnlockSuppressedInObserveOnly() {
    when(dispatchService.smartDispatcherMode()).thenReturn(SmartDispatcherMode.OBSERVE_ONLY);
    RuntimeDispatchService.SmartRecoveryInput input =
        smartRecoveryInput("train1", SignalAspect.STOP, true, "signal-authority-window-exceeded");
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.STOP, 0.0)));
    when(dispatchService.smartRecoveryInput(eq("train1"), any(), eq(SignalAspect.STOP)))
        .thenReturn(input);
    when(dispatchService.applySmartForwardUnlock(input))
        .thenReturn(
            new RuntimeDispatchService.SmartRecoveryActionResult(
                true,
                false,
                "SMART_FORWARD_UNLOCK_CANDIDATE",
                "suppressed-by-mode",
                org.fetarute
                    .fetaruteTCAddon
                    .dispatcher
                    .runtime
                    .supervisor
                    .DispatchEffectClass
                    .SIGNAL_CONSTRAINT));
    when(dispatchService.recentBlockerTrains(eq("train1"), any())).thenReturn(Set.of());
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());

    monitor.setProgressStuckThreshold(Duration.ofSeconds(10));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(20));
    monitor.setDeadlockThreshold(Duration.ofSeconds(300));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);
    monitor.check(Set.of("train1"), t0.plusSeconds(65));

    verify(dispatchService, never()).refreshSignalByName("train1");
    verify(dispatchService, never()).reissueDestinationByName("train1");
    verify(dispatchService, never()).forceRelaunchByName("train1");
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertTrue(
        debugLogs.stream()
            .anyMatch(line -> line.contains("recoveryDecision=SMART_FORWARD_UNLOCK_CANDIDATE")));
  }

  @Test
  @DisplayName("progress stuck 恢复优先释放自持 retain，不直接进入 destroy")
  void recoveryPrefersSelfRetainReleaseBeforeDestroy() {
    RuntimeDispatchService.SmartRecoveryInput input =
        smartRecoveryInput("train1", SignalAspect.STOP, true, "self-owned-retain");
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.STOP, 0.0)));
    when(dispatchService.smartRecoveryInput(eq("train1"), any(), eq(SignalAspect.STOP)))
        .thenReturn(input);
    when(dispatchService.applySmartSelfOwnedStaleRetainRelease(input))
        .thenReturn(
            new RuntimeDispatchService.SmartRecoveryActionResult(
                true,
                true,
                "SMART_RELEASE_SELF_OWNED_STALE_RETAIN",
                "released-self-owned-stale-retain",
                org.fetarute
                    .fetaruteTCAddon
                    .dispatcher
                    .runtime
                    .supervisor
                    .DispatchEffectClass
                    .OCCUPANCY_MUTATION));
    when(dispatchService.recentBlockerTrains(eq("train1"), any())).thenReturn(Set.of());
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());

    monitor.setProgressStuckThreshold(Duration.ofSeconds(10));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(20));
    monitor.setDeadlockThreshold(Duration.ofSeconds(300));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);
    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train1"), t0.plusSeconds(65));

    assertEquals(1, result.fixedCount());
    verify(dispatchService).applySmartSelfOwnedStaleRetainRelease(input);
    verify(dispatchService, never()).applySmartDrainUnlock(input);
    verify(dispatchService, never()).applySmartForwardUnlock(input);
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertTrue(debugLogs.stream().anyMatch(line -> line.contains("SMART_RECOVERY_ACTION_ORDER")));
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                line -> line.contains("recoveryDecision=SMART_RELEASE_SELF_OWNED_STALE_RETAIN")));
  }

  @Test
  @DisplayName("progress stuck 恢复在 forward unlock 前尝试 drain unlock")
  void recoveryPrefersDrainUnlockBeforeForwardUnlock() {
    RuntimeDispatchService.SmartRecoveryInput input =
        new RuntimeDispatchService.SmartRecoveryInput(
            "train1",
            65,
            SignalAspect.STOP,
            true,
            SignalComputationTrace.TokenState.PENDING,
            false,
            0,
            Set.of(),
            NodeId.of("A"),
            NodeId.of("B"),
            "route:test",
            0,
            "A",
            true,
            false,
            false,
            false,
            "self-owned-single-continuation-rejected");
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.STOP, 0.0)));
    when(dispatchService.smartRecoveryInput(eq("train1"), any(), eq(SignalAspect.STOP)))
        .thenReturn(input);
    when(dispatchService.applySmartDrainUnlock(input))
        .thenReturn(
            new RuntimeDispatchService.SmartRecoveryActionResult(
                true,
                true,
                "SMART_DRAIN_UNLOCK",
                "drain-refresh",
                org.fetarute
                    .fetaruteTCAddon
                    .dispatcher
                    .runtime
                    .supervisor
                    .DispatchEffectClass
                    .SIGNAL_CONSTRAINT));
    when(dispatchService.recentBlockerTrains(eq("train1"), any())).thenReturn(Set.of());
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());

    monitor.setProgressStuckThreshold(Duration.ofSeconds(10));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(20));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);
    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train1"), t0.plusSeconds(65));

    assertEquals(1, result.fixedCount());
    verify(dispatchService).applySmartSelfOwnedStaleRetainRelease(input);
    verify(dispatchService).applySmartDrainUnlock(input);
    verify(dispatchService, never()).applySmartForwardUnlock(input);
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
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
  @DisplayName("STOP 信号下 progress stuck 超宽限后只做非动车恢复")
  void stopSignalProgressStuckDoesNotRelaunchAfterGrace() {
    List<HealthAlert> alerts = new ArrayList<>();
    alertBus.subscribe(alerts::add);
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.STOP, 0.0)));
    when(dispatchService.recentBlockerTrains(eq("train1"), any())).thenReturn(Set.of());
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());
    when(dispatchService.reapplyHardStopByName(eq("train1"), anyString())).thenReturn(true);

    monitor.setProgressStuckThreshold(Duration.ofSeconds(10));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(20));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0); // 首次采样
    monitor.check(Set.of("train1"), t0.plusSeconds(25)); // stage1: refresh-stop
    TrainHealthMonitor.CheckResult hardStop =
        monitor.check(Set.of("train1"), t0.plusSeconds(40)); // stage2: cleanup/hard-stop
    monitor.check(Set.of("train1"), t0.plusSeconds(55)); // stage2 retry: cleanup/hard-stop

    assertEquals(0, hardStop.fixedCount(), "复下发 STOP 不代表已修复");
    assertFalse(alerts.stream().anyMatch(HealthAlert::autoFixed), "STOP 恢复链不应输出“已修复”");
    verify(dispatchService, atLeastOnce()).refreshSignalByName("train1");
    verify(dispatchService, atLeastOnce()).clearSelfOwnedSingleDirectionMismatchByName("train1");
    verify(dispatchService, atLeastOnce()).reapplyHardStopByName(eq("train1"), anyString());
    verify(dispatchService, never()).reissueDestinationByName("train1");
    verify(dispatchService, never()).forceRelaunchByName("train1");
  }

  @Test
  @DisplayName("STOP progress stuck 第二阶段优先清理自持 single 反向残留")
  void stopSignalProgressStuckClearsSelfOwnedSingleMismatchBeforeHardStop() {
    List<HealthAlert> alerts = new ArrayList<>();
    alertBus.subscribe(alerts::add);
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.STOP, 0.0)));
    when(dispatchService.recentBlockerTrains(eq("train1"), any())).thenReturn(Set.of());
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());
    when(dispatchService.clearSelfOwnedSingleDirectionMismatchByName("train1")).thenReturn(true);

    monitor.setProgressStuckThreshold(Duration.ofSeconds(10));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(20));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);
    monitor.check(Set.of("train1"), t0.plusSeconds(25));
    TrainHealthMonitor.CheckResult cleanup = monitor.check(Set.of("train1"), t0.plusSeconds(40));

    assertEquals(0, cleanup.fixedCount(), "清理残留只触发重新判定，不直接宣布修复");
    assertFalse(alerts.stream().anyMatch(HealthAlert::autoFixed), "STOP 恢复链不应输出“已修复”");
    verify(dispatchService).refreshSignalByName("train1");
    verify(dispatchService).clearSelfOwnedSingleDirectionMismatchByName("train1");
    verify(dispatchService, never()).reapplyHardStopByName(eq("train1"), anyString());
    verify(dispatchService, never()).reissueDestinationByName("train1");
    verify(dispatchService, never()).forceRelaunchByName("train1");
  }

  @Test
  @DisplayName("STOP 信号下若 blocker 快照仍新鲜且仍在宽限内则不触发 progress stuck 恢复")
  void stopSignalWithFreshBlockerWithinGraceDoesNotTriggerProgressRecovery() {
    List<HealthAlert> alerts = new ArrayList<>();
    alertBus.subscribe(alerts::add);
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.STOP, 0.0)));
    when(dispatchService.recentBlockerTrains(eq("train1"), any())).thenReturn(Set.of("front"));
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());

    monitor.setProgressStuckThreshold(Duration.ofSeconds(10));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(20));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);
    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train1"), t0.plusSeconds(15));

    assertEquals(0, result.progressStuckCount(), "新鲜 blocker 存在时应视为合法等待");
    assertTrue(alerts.isEmpty(), "合法 STOP 等待不应产生 stuck 告警");
    verify(dispatchService, never()).refreshSignalByName("train1");
    verify(dispatchService, never()).reissueDestinationByName(anyString());
    verify(dispatchService, never()).forceRelaunchByName(anyString());
  }

  @Test
  @DisplayName("STOP 信号下 blocker 持续刷新但超过宽限后仍应进入恢复链")
  void stopSignalWithFreshBlockerAfterGraceEscalatesProgressRecovery() {
    List<HealthAlert> alerts = new ArrayList<>();
    alertBus.subscribe(alerts::add);
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.STOP, 0.0)));
    when(dispatchService.recentBlockerTrains(eq("train1"), any())).thenReturn(Set.of("front"));
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());

    monitor.setProgressStuckThreshold(Duration.ofSeconds(10));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(20));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);
    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train1"), t0.plusSeconds(25));

    assertEquals(1, result.progressStuckCount(), "超过 STOP 宽限后不能被新鲜 blocker 无限压制");
    assertEquals(1, alerts.size(), "进入恢复链后应产生 stuck 告警");
    verify(dispatchService).refreshSignalByName("train1");
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
  }

  @Test
  @DisplayName("STOP 信号下 blocker 宽限内等待，超宽限后进入 progress stuck 恢复链")
  void stopSignalProgressRecoveryResumesAfterBlockerSnapshotExpires() {
    List<HealthAlert> alerts = new ArrayList<>();
    alertBus.subscribe(alerts::add);
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.STOP, 0.0)));
    when(dispatchService.recentBlockerTrains(eq("train1"), any()))
        .thenReturn(Set.of("front"))
        .thenReturn(Set.of());
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());

    monitor.setProgressStuckThreshold(Duration.ofSeconds(10));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(30));
    monitor.setBlockerSnapshotMaxAge(Duration.ofSeconds(5));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);
    TrainHealthMonitor.CheckResult blocked = monitor.check(Set.of("train1"), t0.plusSeconds(25));
    TrainHealthMonitor.CheckResult recovered = monitor.check(Set.of("train1"), t0.plusSeconds(40));

    assertEquals(0, blocked.progressStuckCount(), "STOP 宽限内有 blocker 时不应进入 stuck 恢复");
    assertEquals(1, recovered.progressStuckCount(), "超过 STOP 宽限后应重新进入 stuck 恢复");
    assertEquals(1, alerts.size(), "恢复链重新生效后应产生一条告警");
    verify(dispatchService).refreshSignalByName("train1");
    verify(dispatchService, never()).reissueDestinationByName(anyString());
    verify(dispatchService, never()).forceRelaunchByName(anyString());
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
    stubConfirmedDeadlock("trainA", "trainB");

    monitor.setProgressStuckThreshold(Duration.ofSeconds(300));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(180));

    Instant t0 = Instant.now();
    monitor.check(Set.of("trainA", "trainB"), t0); // 首次采样
    TrainHealthMonitor.CheckResult result =
        monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(50));

    assertEquals(1, result.progressStuckCount(), "仅由一侧执行互卡修复");
    assertEquals(0, result.fixedCount(), "refresh 只是恢复动作，不能宣称已修复");
    verify(dispatchService).refreshSignalByName("trainA");
    verify(dispatchService).refreshSignalByName("trainB");
    verify(dispatchService, never()).reissueDestinationByName(anyString());
    verify(dispatchService, never()).forceRelaunchByName(anyString());
    assertFalse(alerts.isEmpty());
    assertFalse(alerts.get(0).autoFixed(), "未观察到恢复前不应输出“已修复”告警");
  }

  @Test
  @DisplayName("OBSERVE_ONLY 下互卡恢复只输出 trace，不执行 refresh/hard-stop/destroy")
  void smartDispatcherObserveOnlySuppressesDeadlockRecoveryActions() {
    when(dispatchService.smartDispatcherMode()).thenReturn(SmartDispatcherMode.OBSERVE_ONLY);
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.STOP, 0.0)));
    stubConfirmedDeadlock("trainA", "trainB");

    monitor.setProgressStuckThreshold(Duration.ofSeconds(300));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(180));
    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(40));

    Instant t0 = Instant.now();
    monitor.check(Set.of("trainA", "trainB"), t0);
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(50));
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(100));

    verify(dispatchService, never()).refreshSignalByName(anyString());
    verify(dispatchService, never()).reapplyHardStopByName(anyString(), anyString());
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertTrue(debugLogs.stream().anyMatch(line -> line.contains("SMART_DISPATCH_MODE")));
    assertTrue(
        debugLogs.stream()
            .anyMatch(line -> line.contains("SMART_DISPATCH_ACTION_SUPPRESSED_BY_MODE")));
  }

  @Test
  @DisplayName("互相阻塞分级恢复：自动模式只 refresh/hard-stop")
  void mutualDeadlockEscalatesRecoveryStages() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.STOP, 0.0)));
    stubConfirmedDeadlock("trainA", "trainB");
    when(dispatchService.reapplyHardStopByName(anyString(), anyString())).thenReturn(true);

    monitor.setProgressStuckThreshold(Duration.ofSeconds(300));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(180));

    Instant t0 = Instant.now();
    monitor.check(Set.of("trainA", "trainB"), t0); // 首次采样
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(50)); // stage1 refresh
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(65)); // stage2 reissue
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(80)); // stage2 retry

    verify(dispatchService, atLeastOnce()).refreshSignalByName("trainA");
    verify(dispatchService, atLeastOnce()).refreshSignalByName("trainB");
    verify(dispatchService).reapplyHardStopByName(eq("trainA"), anyString());
    verify(dispatchService).reapplyHardStopByName(eq("trainB"), anyString());
    verify(dispatchService, never()).reissueDestinationByName(anyString());
    verify(dispatchService, never()).forceRelaunchByName(anyString());
  }

  @Test
  @DisplayName("互相阻塞自动恢复在销毁阈值前不会 reissue、relaunch 或销毁列车")
  void mutualDeadlockDoesNotRelaunchOrDestroyBeforeDestroyThreshold() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.STOP, 0.0)));
    stubConfirmedDeadlock("trainA", "trainB");
    when(dispatchService.reapplyHardStopByName(anyString(), anyString())).thenReturn(true);

    monitor.setProgressStuckThreshold(Duration.ofSeconds(300));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(180));

    Instant t0 = Instant.now();
    monitor.check(Set.of("trainA", "trainB"), t0); // 首次采样
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(50)); // stage1 refresh
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(65)); // stage2 hard-stop
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(80)); // stage2 retry
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(95)); // stage2 retry

    verify(dispatchService, never()).reissueDestinationByName(anyString());
    verify(dispatchService, never()).forceRelaunchByName(anyString());
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
  }

  @Test
  @DisplayName("互相阻塞超过销毁阈值后销毁稳定 leader")
  void mutualDeadlockDestroysPairLeaderAfterDestroyThreshold() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.STOP, 0.0)));
    stubConfirmedDeadlock("trainA", "trainB");
    when(dispatchService.reapplyHardStopByName(anyString(), anyString())).thenReturn(false);
    when(dispatchService.destroyTrainByName(eq("trainA"), eq("health-deadlock-timeout")))
        .thenReturn(true);
    doNothing()
        .when(dispatchService)
        .scheduleSurvivorRefreshAfterTrainRemoved(eq("trainA"), eq("trainB"));

    monitor.setProgressStuckThreshold(Duration.ofSeconds(300));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(180));
    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(40));

    Instant t0 = Instant.now();
    monitor.check(Set.of("trainA", "trainB"), t0); // 首次采样
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(50)); // stage1 refresh
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(65)); // stage2 hard-stop
    TrainHealthMonitor.CheckResult result =
        monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(100)); // destroy leader

    assertEquals(1, result.fixedCount(), "超过阈值后应销毁互卡 leader 作为最终兜底");
    verify(dispatchService).scheduleSurvivorRefreshAfterTrainRemoved("trainA", "trainB");
    verify(dispatchService).destroyTrainByName("trainA", "health-deadlock-timeout");
    verify(dispatchService, never()).destroyTrainByName(eq("trainB"), anyString());
    verify(dispatchService, never()).forceRelaunchByName(anyString());
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("DEADLOCK_DESTROY_ATTEMPTED")
                        && message.contains("episodeType=CONFIRMED_SINGLE")),
        "confirmed mutual single 仍走原有 eligibility 后才进入 destroy trace");
  }

  @Test
  @DisplayName("Smart destroy 前若 forward unlock 可用则跳过销毁")
  void smartDestroySkippedWhenForwardUnlockAvailable() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.STOP, 0.0)));
    stubConfirmedDeadlock("trainA", "trainB");
    when(dispatchService.reapplyHardStopByName(anyString(), anyString())).thenReturn(false);
    when(dispatchService.applySmartForwardUnlock(any()))
        .thenReturn(
            new RuntimeDispatchService.SmartRecoveryActionResult(
                true,
                false,
                "SMART_FORWARD_UNLOCK_CANDIDATE",
                "authority-token-repair",
                org.fetarute
                    .fetaruteTCAddon
                    .dispatcher
                    .runtime
                    .supervisor
                    .DispatchEffectClass
                    .SIGNAL_CONSTRAINT));

    monitor.setProgressStuckThreshold(Duration.ofSeconds(300));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(180));
    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(40));

    Instant t0 = Instant.now();
    monitor.check(Set.of("trainA", "trainB"), t0);
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(50));
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(65));
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(100));

    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DESTROY_SKIPPED_SAFE_ALTERNATIVE")
                        && message.contains("reason=forward-unlock-candidate")));
  }

  @Test
  @DisplayName("道岔互相阻塞可用 live blocker cycle 进入恢复评估")
  void switcherMutualBlockerConfirmsLiveCycleAndEntersRecovery() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.STOP, 0.0)));
    when(dispatchService.recentDeadlockBlockers(eq("trainA"), any()))
        .thenReturn(deadlockSnapshot("trainB", "switcher:SW", null));
    when(dispatchService.recentDeadlockBlockers(eq("trainB"), any()))
        .thenReturn(deadlockSnapshot("trainA", "switcher:SW", null));
    when(dispatchService.deadlockTrainContext("trainA"))
        .thenReturn(Optional.of(context("trainA", 5, RouteOperationType.OPERATION, false, false)));
    when(dispatchService.deadlockTrainContext("trainB"))
        .thenReturn(Optional.of(context("trainB", 7, RouteOperationType.OPERATION, false, false)));

    monitor.setProgressStuckThreshold(Duration.ofSeconds(300));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(180));
    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(40));

    Instant t0 = Instant.now();
    monitor.check(Set.of("trainA", "trainB"), t0);
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(50));
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(90));
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(140));

    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("DEADLOCK_EPISODE_CREATED")
                        && message.contains("episodeType=LIVE_MUTUAL_BLOCKER_CYCLE")),
        "switcher blocker 应形成 live mutual blocker cycle");
    assertTrue(
        debugLogs.stream()
            .anyMatch(message -> message.contains("SMART_DEADLOCK_LIVE_CYCLE_CONFIRMED")),
        "live blocker cycle 应输出确认诊断");
    assertTrue(
        debugLogs.stream().anyMatch(message -> message.contains("SMART_RECOVERY_EVALUATION_ENTER")),
        "live blocker cycle 应进入恢复评估");
    assertFalse(
        debugLogs.stream().anyMatch(message -> message.contains("episodeType=CONFIRMED_SINGLE")),
        "switcher blocker 不应被标成 confirmed single");
  }

  @Test
  @DisplayName("互相阻塞自动恢复失败后仍不强制动车")
  void mutualDeadlockKeepsRetryingNonMovingRecoveryWhenActionsFail() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.STOP, 0.0)));
    stubConfirmedDeadlock("trainA", "trainB");
    when(dispatchService.reapplyHardStopByName(anyString(), anyString())).thenReturn(false);

    monitor.setProgressStuckThreshold(Duration.ofSeconds(300));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(180));

    Instant t0 = Instant.now();
    monitor.check(Set.of("trainA", "trainB"), t0); // 首次采样
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(50)); // stage1 refresh
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(65)); // stage2 hard-stop 失败
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(80)); // stage2 hard-stop retry
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(95)); // stage2 hard-stop retry

    verify(dispatchService).reapplyHardStopByName(eq("trainA"), anyString());
    verify(dispatchService).reapplyHardStopByName(eq("trainB"), anyString());
    verify(dispatchService, never()).reissueDestinationByName(anyString());
    verify(dispatchService, never()).forceRelaunchByName(anyString());
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
  }

  @Test
  @DisplayName("互卡 episode：blocker 快照短暂消失不会重置 firstSeenAt")
  void mutualDeadlockEpisodeGracePreservesFirstSeenAcrossSnapshotJitter() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.STOP, 0.0)));
    when(dispatchService.recentBlockerTrains(eq("trainA"), any())).thenReturn(Set.of("trainB"));
    when(dispatchService.recentBlockerTrains(eq("trainB"), any())).thenReturn(Set.of("trainA"));
    RuntimeDispatchService.DeadlockBlockerSnapshot aSnapshot =
        deadlockSnapshot("trainB", "single:test:A:B", CorridorDirection.B_TO_A);
    RuntimeDispatchService.DeadlockBlockerSnapshot bSnapshot =
        deadlockSnapshot("trainA", "single:test:A:B", CorridorDirection.A_TO_B);
    RuntimeDispatchService.DeadlockBlockerSnapshot emptySnapshot =
        new RuntimeDispatchService.DeadlockBlockerSnapshot(Set.of(), Instant.now());
    AtomicBoolean snapshotsVisible = new AtomicBoolean(true);
    when(dispatchService.recentDeadlockBlockers(eq("trainA"), any()))
        .thenAnswer(ignored -> snapshotsVisible.get() ? aSnapshot : emptySnapshot);
    when(dispatchService.recentDeadlockBlockers(eq("trainB"), any()))
        .thenAnswer(ignored -> snapshotsVisible.get() ? bSnapshot : emptySnapshot);
    when(dispatchService.deadlockTrainContext("trainA"))
        .thenReturn(Optional.of(context("trainA", 5, RouteOperationType.OPERATION, false, false)));
    when(dispatchService.deadlockTrainContext("trainB"))
        .thenReturn(Optional.of(context("trainB", 7, RouteOperationType.OPERATION, false, false)));
    when(dispatchService.destroyTrainByName(eq("trainA"), eq("health-deadlock-timeout")))
        .thenReturn(true);

    monitor.setProgressStuckThreshold(Duration.ofSeconds(300));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(180));
    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(40));
    monitor.setDeadlockEpisodeGrace(Duration.ofSeconds(20));

    Instant t0 = Instant.now();
    monitor.check(Set.of("trainA", "trainB"), t0);
    snapshotsVisible.set(true);
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(50)); // firstSeen + refresh
    snapshotsVisible.set(false);
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(65)); // jitter, keep episode
    snapshotsVisible.set(true);
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(75)); // reissue
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(95)); // destroy by preserved firstSeen

    verify(dispatchService).destroyTrainByName("trainA", "health-deadlock-timeout");
  }

  @Test
  @DisplayName("互卡兜底：UNKNOWN direction 不进入 destroy")
  void unknownDirectionDeadlockDoesNotDestroy() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.STOP, 0.0)));
    stubConfirmedDeadlock(
        "trainA",
        "trainB",
        "single:test:A:B",
        CorridorDirection.UNKNOWN,
        CorridorDirection.B_TO_A,
        context("trainA", 5, RouteOperationType.OPERATION, false, false),
        context("trainB", 7, RouteOperationType.OPERATION, false, false));

    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(1));
    Instant t0 = Instant.now();
    monitor.check(Set.of("trainA", "trainB"), t0);
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(80));

    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertFalse(
        debugLogs.stream().anyMatch(message -> message.contains("episodeType=CONFIRMED_SINGLE")),
        "UNKNOWN direction single 不应成为 confirmed mutual single");
  }

  @Test
  @DisplayName("道岔 occupant 阻塞多车只输出诊断，不直接销毁")
  void switcherOccupantBlockingManyEmitsDiagnosticButNoPolicyDestroy() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("blockedA"))
        .thenReturn(Optional.of(state("blockedA", 3, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("blockedB"))
        .thenReturn(Optional.of(state("blockedB", 4, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("occupant"))
        .thenReturn(Optional.of(state("occupant", 5, SignalAspect.STOP, 0.0, "SW")));
    when(dispatchService.recentDeadlockBlockers(eq("blockedA"), any()))
        .thenReturn(deadlockSnapshot("occupant", "switcher:SW", null));
    when(dispatchService.recentDeadlockBlockers(eq("blockedB"), any()))
        .thenReturn(deadlockSnapshot("occupant", "switcher:SW", null));
    when(dispatchService.recentDeadlockBlockers(eq("occupant"), any()))
        .thenReturn(new RuntimeDispatchService.DeadlockBlockerSnapshot(Set.of(), Instant.now()));
    when(dispatchService.currentConflictClaimKeys(anyString())).thenReturn(Set.of());
    when(dispatchService.currentConflictClaimKeys("occupant")).thenReturn(Set.of("switcher:SW"));

    Instant t0 = Instant.now();
    monitor.check(Set.of("blockedA", "blockedB", "occupant"), t0);

    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("SWITCHER_OCCUPANT_BLOCKING_MANY")
                        && message.contains("blockerTrain=occupant")
                        && message.contains("blockedCount=2")
                        && message.contains("containsSwitcherConflict=true")
                        && message.contains("protectedSwitcherClaimPresent=true")),
        "occupant-to-many 模式应可观测");
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
  }

  @Test
  @DisplayName("互卡兜底：departure gate / station hold 不进入 destroy")
  void departureGateHoldDoesNotDestroy() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.STOP, 0.0)));
    RuntimeDispatchService.DeadlockTrainContext gated =
        new RuntimeDispatchService.DeadlockTrainContext(
            "trainA",
            5,
            10,
            SignalAspect.STOP,
            0.0,
            RouteOperationType.OPERATION,
            0,
            false,
            true,
            false,
            false,
            false,
            false,
            false);
    stubConfirmedDeadlock(
        "trainA",
        "trainB",
        "single:test:A:B",
        CorridorDirection.A_TO_B,
        CorridorDirection.B_TO_A,
        gated,
        context("trainB", 7, RouteOperationType.OPERATION, false, false));

    Instant t0 = Instant.now();
    monitor.check(Set.of("trainA", "trainB"), t0);
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(80));

    verify(dispatchService, never()).reissueDestinationByName(anyString());
    verify(dispatchService, never()).forceRelaunchByName(anyString());
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
  }

  @Test
  @DisplayName("互卡 leader：RETURN 优先于 OPERATION 被销毁")
  void stableLeaderPrefersReturnOverOperation() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.STOP, 0.0)));
    stubConfirmedDeadlock(
        "trainA",
        "trainB",
        "single:test:A:B",
        CorridorDirection.A_TO_B,
        CorridorDirection.B_TO_A,
        context("trainA", 5, RouteOperationType.OPERATION, false, false),
        context("trainB", 7, RouteOperationType.RETURN, false, false));
    when(dispatchService.destroyTrainByName(eq("trainB"), eq("health-deadlock-timeout")))
        .thenReturn(true);

    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(40));
    Instant t0 = Instant.now();
    monitor.check(Set.of("trainA", "trainB"), t0);
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(50));
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(65));
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(100));

    verify(dispatchService).destroyTrainByName("trainB", "health-deadlock-timeout");
    verify(dispatchService, never()).destroyTrainByName(eq("trainA"), anyString());
  }

  @Test
  @DisplayName("互卡 leader：depot exit 优先于 in-service 被销毁")
  void stableLeaderPrefersDepotExitOverInService() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.STOP, 0.0)));
    stubConfirmedDeadlock(
        "trainA",
        "trainB",
        "single:test:A:B",
        CorridorDirection.A_TO_B,
        CorridorDirection.B_TO_A,
        context("trainA", 5, RouteOperationType.OPERATION, false, false),
        context("trainB", 7, RouteOperationType.OPERATION, true, false));
    when(dispatchService.destroyTrainByName(eq("trainB"), eq("health-deadlock-timeout")))
        .thenReturn(true);

    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(40));
    Instant t0 = Instant.now();
    monitor.check(Set.of("trainA", "trainB"), t0);
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(50));
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(65));
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(100));

    verify(dispatchService).destroyTrainByName("trainB", "health-deadlock-timeout");
    verify(dispatchService, never()).destroyTrainByName(eq("trainA"), anyString());
  }

  @Test
  @DisplayName("互卡 episode：destroyAttempted 后不会连续销毁第二辆")
  void deadlockDestroyAttemptedPreventsSecondDestroy() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.STOP, 0.0)));
    stubConfirmedDeadlock("trainA", "trainB");
    when(dispatchService.destroyTrainByName(eq("trainA"), eq("health-deadlock-timeout")))
        .thenReturn(true);

    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(40));
    Instant t0 = Instant.now();
    monitor.check(Set.of("trainA", "trainB"), t0);
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(50));
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(65));
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(100));
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(130));

    verify(dispatchService, times(1))
        .destroyTrainByName(anyString(), eq("health-deadlock-timeout"));
  }

  @Test
  @DisplayName("互卡销毁兜底：缺 blocker snapshot 但未过阈值时只记录 early skip")
  void destroyFallbackDoesNotFireBeforeThresholdWhenBlockerSnapshotMissing() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.recentDeadlockBlockers(eq("train1"), any()))
        .thenReturn(new RuntimeDispatchService.DeadlockBlockerSnapshot(Set.of(), Instant.now()));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);
    monitor.check(Set.of("train1"), t0.plusSeconds(30));

    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("DEADLOCK_DESTROY_SKIPPED")
                        && message.contains("reason=BLOCKER_SNAPSHOT_MISSING")
                        && message.contains("skipPhase=EARLY")));
  }

  @Test
  @DisplayName("互卡销毁兜底：缺 fallback evidence 过阈值时不误报已检查 eligibility")
  void destroyFallbackMissingEvidenceAfterThresholdDoesNotClaimEligibilityChecked() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.recentDeadlockBlockers(eq("train1"), any()))
        .thenReturn(new RuntimeDispatchService.DeadlockBlockerSnapshot(Set.of(), Instant.now()));

    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(40));
    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);
    monitor.check(Set.of("train1"), t0.plusSeconds(50));

    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("DEADLOCK_DESTROY_SKIPPED")
                        && message.contains("reason=BLOCKER_SNAPSHOT_MISSING")
                        && message.contains("skipPhase=THRESHOLD_PASSED")
                        && message.contains("fallbackEvidenceChecked=false")
                        && message.contains("fallbackIneligibleReason=NO_FALLBACK_EVIDENCE")));
    assertFalse(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("DEADLOCK_DESTROY_SKIPPED")
                        && message.contains("see-smart-deadlock-destroy-eligibility")));
  }

  @Test
  @DisplayName("互卡销毁兜底：planner wait-for edge 持续存在时可销毁超阈值 blocker")
  void destroyFallbackWithPersistentPlannerEdgeDestroysBlockerAfterThreshold() {
    stubFollowerBlockedByLeader(
        smartRecoveryInput(
            "leader", SignalComputationTrace.TokenState.PENDING, true, "planner-wait-for-edge"),
        context("leader", 7, RouteOperationType.OPERATION, false, false));
    when(dispatchService.destroyTrainByName(eq("leader"), eq("health-deadlock-timeout")))
        .thenReturn(true);

    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(40));
    Instant t0 = Instant.now();
    monitor.check(Set.of("follower", "leader"), t0);
    monitor.check(Set.of("follower", "leader"), t0.plusSeconds(50));
    TrainHealthMonitor.CheckResult result =
        monitor.check(Set.of("follower", "leader"), t0.plusSeconds(65));

    assertEquals(1, result.fixedCount());
    verify(dispatchService).destroyTrainByName("leader", "health-deadlock-timeout");
    verify(dispatchService, never()).destroyTrainByName(eq("follower"), anyString());
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DEADLOCK_DESTROY_EXECUTED")
                        && message.contains("evidenceGroup=PLANNER_WAIT_FOR_EDGE_FALLBACK")));
  }

  @Test
  @DisplayName("互卡销毁兜底：当前停滞车无有效 authority 时先销毁当前车")
  void destroyFallbackTargetsCurrentTrainWhenCurrentAuthorityInvalid() {
    stubFollowerBlockedByLeader(
        smartRecoveryInput(
            "leader", SignalComputationTrace.TokenState.ACTIVE, true, "leader-authority-active"),
        context("leader", 7, RouteOperationType.OPERATION, false, false));
    when(dispatchService.smartRecoveryInput(eq("follower"), any(), any()))
        .thenReturn(
            smartRecoveryInput(
                "follower",
                SignalComputationTrace.TokenState.INVALID,
                false,
                "current-authority-invalid"));
    when(dispatchService.destroyTrainByName(eq("follower"), eq("health-deadlock-timeout")))
        .thenReturn(true);

    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(40));
    Instant t0 = Instant.now();
    monitor.check(Set.of("follower", "leader"), t0);
    monitor.check(Set.of("follower", "leader"), t0.plusSeconds(50));
    TrainHealthMonitor.CheckResult result =
        monitor.check(Set.of("follower", "leader"), t0.plusSeconds(65));

    assertEquals(1, result.fixedCount());
    verify(dispatchService).destroyTrainByName("follower", "health-deadlock-timeout");
    verify(dispatchService, never()).destroyTrainByName(eq("leader"), anyString());
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DEADLOCK_DESTROY_EXECUTED")
                        && message.contains("train=follower")
                        && message.contains("evidenceGroup=STUCK_LEADER_FALLBACK")));
  }

  @Test
  @DisplayName("互卡销毁兜底：stuck leader 证据优先销毁 leader 而不是 follower")
  void destroyFallbackWithStuckLeaderEvidenceTargetsLeader() {
    stubFollowerBlockedByLeader(
        smartRecoveryInput(
            "leader",
            SignalComputationTrace.TokenState.INVALID,
            false,
            "stuck-leader-invalid-token"),
        context("leader", 7, RouteOperationType.OPERATION, false, false));
    when(dispatchService.destroyTrainByName(eq("leader"), eq("health-deadlock-timeout")))
        .thenReturn(true);

    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(40));
    Instant t0 = Instant.now();
    monitor.check(Set.of("follower", "leader"), t0);
    monitor.check(Set.of("follower", "leader"), t0.plusSeconds(50));
    monitor.check(Set.of("follower", "leader"), t0.plusSeconds(65));

    verify(dispatchService).destroyTrainByName("leader", "health-deadlock-timeout");
    verify(dispatchService, never()).destroyTrainByName(eq("follower"), anyString());
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DEADLOCK_DESTROY_EXECUTED")
                        && message.contains("evidenceGroup=STUCK_LEADER_FALLBACK")));
  }

  @Test
  @DisplayName("互卡销毁兜底：缺 blocker snapshot 时使用 follower stuck leader 证据销毁 leader")
  void destroyFallbackWithFollowerStuckLeaderEvidenceTargetsLeaderWhenSnapshotMissing() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("follower"))
        .thenReturn(Optional.of(state("follower", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("leader"))
        .thenReturn(Optional.of(state("leader", 7, SignalAspect.STOP, 0.0)));
    when(dispatchService.recentDeadlockBlockers(anyString(), any()))
        .thenReturn(new RuntimeDispatchService.DeadlockBlockerSnapshot(Set.of(), Instant.now()));
    when(dispatchService.deadlockTrainContext("follower"))
        .thenReturn(
            Optional.of(context("follower", 5, RouteOperationType.OPERATION, false, false)));
    when(dispatchService.deadlockTrainContext("leader"))
        .thenReturn(Optional.of(context("leader", 7, RouteOperationType.OPERATION, false, false)));
    when(dispatchService.smartRecoveryInput(eq("leader"), any(), any()))
        .thenReturn(
            smartRecoveryInput(
                "leader",
                SignalComputationTrace.TokenState.INVALID,
                false,
                "stuck-leader-invalid-token"));
    when(dispatchService.recentFollowerStuckLeaderEvidence(eq("follower"), any()))
        .thenReturn(
            Optional.of(
                new RuntimeDispatchService.FollowerStuckLeaderEvidence(
                    "follower",
                    "leader",
                    "CONFLICT:single:test:A~B",
                    Instant.now().minusSeconds(20),
                    Instant.now(),
                    2)));
    when(dispatchService.destroyTrainByName(eq("leader"), eq("health-deadlock-timeout")))
        .thenReturn(true);

    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(40));
    Instant t0 = Instant.now();
    monitor.check(Set.of("follower", "leader"), t0);
    monitor.check(Set.of("follower", "leader"), t0.plusSeconds(50));
    TrainHealthMonitor.CheckResult result =
        monitor.check(Set.of("follower", "leader"), t0.plusSeconds(65));

    assertEquals(1, result.fixedCount());
    verify(dispatchService).destroyTrainByName("leader", "health-deadlock-timeout");
    verify(dispatchService, never()).destroyTrainByName(eq("follower"), anyString());
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DEADLOCK_DESTROY_EXECUTED")
                        && message.contains("train=leader")
                        && message.contains("evidenceGroup=STUCK_LEADER_FALLBACK")));
  }

  @Test
  @DisplayName("互卡销毁兜底：active unlock reservation 观察中不销毁")
  void destroyFallbackBlockedByActiveUnlockReservation() {
    stubFollowerBlockedByLeader(
        smartRecoveryInput(
            "leader",
            SignalComputationTrace.TokenState.INVALID,
            false,
            "stuck-leader-invalid-token"),
        context("leader", 7, RouteOperationType.OPERATION, false, false));
    when(dispatchService.hasActiveSmartUnlockReservation("leader")).thenReturn(true);

    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(40));
    Instant t0 = Instant.now();
    monitor.check(Set.of("follower", "leader"), t0);
    monitor.check(Set.of("follower", "leader"), t0.plusSeconds(50));
    monitor.check(Set.of("follower", "leader"), t0.plusSeconds(65));

    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DEADLOCK_DESTROY_ELIGIBILITY")
                        && message.contains("ineligibleReason=ACTIVE_UNLOCK_RESERVATION")));
  }

  @Test
  @DisplayName("互卡销毁兜底：有乘客时不销毁")
  void destroyFallbackBlockedByPassenger() {
    stubFollowerBlockedByLeader(
        smartRecoveryInput(
            "leader",
            SignalComputationTrace.TokenState.INVALID,
            false,
            "stuck-leader-invalid-token"),
        context("leader", 7, RouteOperationType.OPERATION, false, false, true, false));

    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(40));
    Instant t0 = Instant.now();
    monitor.check(Set.of("follower", "leader"), t0);
    monitor.check(Set.of("follower", "leader"), t0.plusSeconds(50));
    monitor.check(Set.of("follower", "leader"), t0.plusSeconds(65));

    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DEADLOCK_DESTROY_ELIGIBILITY")
                        && message.contains("ineligibleReason=PLAYER_PASSENGER_PRESENT")));
  }

  @Test
  @DisplayName("互卡销毁兜底：手动控制/维护保持时不销毁")
  void destroyFallbackBlockedByManualControl() {
    stubFollowerBlockedByLeader(
        smartRecoveryInput(
            "leader",
            SignalComputationTrace.TokenState.INVALID,
            false,
            "stuck-leader-invalid-token"),
        context("leader", 7, RouteOperationType.OPERATION, false, false, false, true));

    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(40));
    Instant t0 = Instant.now();
    monitor.check(Set.of("follower", "leader"), t0);
    monitor.check(Set.of("follower", "leader"), t0.plusSeconds(50));
    monitor.check(Set.of("follower", "leader"), t0.plusSeconds(65));

    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DEADLOCK_DESTROY_ELIGIBILITY")
                        && message.contains("ineligibleReason=MANUAL_CONTROL")));
  }

  @Test
  @DisplayName("手动强制解锁：不等待阈值，直接复下发 hard-stop 但不误报已修复")
  void forceUnlockNowEscalatesImmediately() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.STOP, 0.0)));
    when(dispatchService.recentBlockerTrains(eq("trainA"), any())).thenReturn(Set.of("trainB"));
    when(dispatchService.recentBlockerTrains(eq("trainB"), any())).thenReturn(Set.of("trainA"));
    when(dispatchService.reapplyHardStopByName(anyString(), anyString())).thenReturn(true);

    int fixed = monitor.forceUnlockNow(Set.of("trainA", "trainB"), Instant.now());

    assertEquals(0, fixed, "refresh/hard-stop 只是恢复动作，不能宣称互卡已解锁");
    verify(dispatchService).refreshSignalByName("trainA");
    verify(dispatchService).refreshSignalByName("trainB");
    verify(dispatchService).reapplyHardStopByName(eq("trainA"), anyString());
    verify(dispatchService).reapplyHardStopByName(eq("trainB"), anyString());
    verify(dispatchService, never()).reissueDestinationByName(anyString());
    verify(dispatchService, never()).forceRelaunchByName(anyString());
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
  @DisplayName("手动强制解锁：STOP 单车阻塞时复下发 hard-stop 但不误报已修复")
  void forceUnlockNowReappliesHardStopForSingleBlockedStopTrain() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.PROCEED, 0.5)));
    when(dispatchService.recentBlockerTrains(eq("trainA"), any())).thenReturn(Set.of("trainB"));
    when(dispatchService.recentBlockerTrains(eq("trainB"), any())).thenReturn(Set.of("trainC"));
    when(dispatchService.reapplyHardStopByName(eq("trainA"), anyString())).thenReturn(true);

    int fixed = monitor.forceUnlockNow(Set.of("trainA", "trainB"), Instant.now());

    assertEquals(0, fixed, "单车 STOP 阻塞只复下发 hard-stop，不计入已修复");
    verify(dispatchService).refreshSignalByName("trainA");
    verify(dispatchService).reapplyHardStopByName(eq("trainA"), anyString());
    verify(dispatchService, never()).reissueDestinationByName(anyString());
    verify(dispatchService, never()).forceRelaunchByName(anyString());
  }

  @Test
  @DisplayName("手动强制解锁：STOP 单车阻塞 hard-stop 失败时不升级动车")
  void forceUnlockNowDoesNotRelaunchSingleBlockedStopTrainWhenHardStopFails() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.PROCEED, 0.5)));
    when(dispatchService.recentBlockerTrains(eq("trainA"), any())).thenReturn(Set.of("trainB"));
    when(dispatchService.recentBlockerTrains(eq("trainB"), any())).thenReturn(Set.of("trainC"));
    when(dispatchService.reapplyHardStopByName(eq("trainA"), anyString())).thenReturn(false);

    int fixed = monitor.forceUnlockNow(Set.of("trainA", "trainB"), Instant.now());

    assertEquals(0, fixed, "单车 STOP 阻塞时 hard-stop 失败也不应升级到 relaunch");
    verify(dispatchService).refreshSignalByName("trainA");
    verify(dispatchService).reapplyHardStopByName(eq("trainA"), anyString());
    verify(dispatchService, never()).reissueDestinationByName(anyString());
    verify(dispatchService, never()).forceRelaunchByName(anyString());
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
