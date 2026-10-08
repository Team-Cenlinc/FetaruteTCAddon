package org.fetarute.fetaruteTCAddon.dispatcher.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DwellRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.RecordingControlAuthority;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherController;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 有驾驶员在岗的列车不做恢复动作；挡住别人的驾驶员车先被请求交还，不连累别的车。 */
@DisplayName("TrainHealthMonitor 驾驶员列车豁免")
class TrainHealthMonitorDriverExemptionTest {

  private RuntimeDispatchService dispatchService;
  private DwellRegistry dwellRegistry;
  private RecordingControlAuthority authority;
  private TrainHealthMonitor monitor;

  @BeforeEach
  void setUp() {
    dispatchService = mock(RuntimeDispatchService.class);
    dwellRegistry = mock(DwellRegistry.class);
    monitor = new TrainHealthMonitor(dispatchService, dwellRegistry, new HealthAlertBus(), s -> {});
    authority = new RecordingControlAuthority().controlName("drv");
    monitor.setControlAuthority(authority);
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
    when(dispatchService.reviewStuckCleanupCandidate(
            anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(
            SmartDispatcherController.StuckCleanupReview.allowed("verified-long-stuck-cleanup"));
  }

  @Test
  @DisplayName("驾驶员车 PROCEED 下静止：不算停滞，不重发车")
  void driverTrainIsNotRecovered() {
    when(dispatchService.getTrainState("drv"))
        .thenReturn(
            Optional.of(
                new RuntimeDispatchService.TrainRuntimeState("drv", 0, SignalAspect.PROCEED, 0.0)));
    when(dwellRegistry.remainingSeconds("drv")).thenReturn(Optional.empty());

    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    monitor.check(Set.of("drv"), t0);
    monitor.check(Set.of("drv"), t0.plusSeconds(35));
    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("drv"), t0.plusSeconds(50));

    assertEquals(0, result.stallCount());
    verify(dispatchService, never()).forceRelaunchByName("drv");
    verify(dispatchService, never()).refreshSignalByName("drv");
  }

  @Test
  @DisplayName("在驾驶员车后面短暂排队不请它交还；到了销毁兜底的时限才请，且绝不销毁别的车")
  void blockedByDriverTrainRequestsHandbackOnlyPastDestroyThreshold() {
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("train1"))
        .thenReturn(
            Optional.of(
                new RuntimeDispatchService.TrainRuntimeState("train1", 3, SignalAspect.STOP, 0.0)));
    when(dispatchService.recentBlockerTrains(eq("train1"), any())).thenReturn(Set.of("drv"));
    monitor.setAutoFixEnabled(true);
    monitor.setDeadlockMinStopDuration(Duration.ofSeconds(20));
    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(60));

    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    for (int i = 0; i <= 4; i++) {
      monitor.check(Set.of("train1"), t0.plusSeconds(10L * i));
    }
    assertTrue(authority.handbacks().isEmpty(), "排队 40 秒（比如前车在站里停站）不请交还");
    for (int i = 5; i <= 8; i++) {
      monitor.check(Set.of("train1"), t0.plusSeconds(10L * i));
    }
    assertTrue(authority.handbacks().contains("drv:deadlock"), authority.handbacks()::toString);
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
  }

  @Test
  @DisplayName("被驾驶员车挡住的长时间停滞车不清，先请驾驶员车交还")
  void blockedByDriverTrainRequestsHandbackInsteadOfCleanup() {
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("train1"))
        .thenReturn(
            Optional.of(
                new RuntimeDispatchService.TrainRuntimeState("train1", 3, SignalAspect.STOP, 0.0)));
    when(dispatchService.deadlockTrainContext("train1"))
        .thenReturn(
            Optional.of(
                new RuntimeDispatchService.DeadlockTrainContext(
                    "train1",
                    3,
                    10,
                    SignalAspect.STOP,
                    0.0,
                    RouteOperationType.OPERATION,
                    0,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false)));
    when(dispatchService.recentBlockerTrains(eq("train1"), any())).thenReturn(Set.of("drv"));
    when(dispatchService.destroyTrainByName("train1", "health-stuck-cleanup-timeout"))
        .thenReturn(true);
    monitor.setTrainCleanupEnabled(true);
    monitor.setProgressStuckThreshold(Duration.ofSeconds(5));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(5));
    monitor.setRecoveryCooldown(Duration.ofSeconds(1));
    monitor.setStuckCleanupThreshold(Duration.ofSeconds(20));
    monitor.setStuckCleanupPassengerThreshold(Duration.ofSeconds(60));
    monitor.setStuckCleanupCooldown(Duration.ZERO);

    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    for (int i = 0; i <= 8; i++) {
      monitor.check(Set.of("train1"), t0.plusSeconds(10L * i));
    }

    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertTrue(authority.handbacks().contains("drv:deadlock"), authority.handbacks()::toString);
  }

  @Test
  @DisplayName("静止很久后才被驾驶员车挡住（例如终点待命车开出时）：从被挡起算满时限才请交还，不按本车自己的静止时长")
  void driverBlockIsTimedFromWhenItStarted() {
    AtomicReference<Set<String>> blockers = new AtomicReference<>(Set.of());
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("train1"))
        .thenReturn(
            Optional.of(
                new RuntimeDispatchService.TrainRuntimeState("train1", 3, SignalAspect.STOP, 0.0)));
    when(dispatchService.recentBlockerTrains(eq("train1"), any()))
        .thenAnswer(invocation -> blockers.get());
    monitor.setAutoFixEnabled(true);
    monitor.setDeadlockMinStopDuration(Duration.ofSeconds(20));
    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(60));

    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    for (int i = 0; i <= 16; i++) {
      monitor.check(Set.of("train1"), t0.plusSeconds(10L * i));
    }
    blockers.set(Set.of("drv"));
    for (int i = 17; i <= 22; i++) {
      monitor.check(Set.of("train1"), t0.plusSeconds(10L * i));
    }
    assertTrue(authority.handbacks().isEmpty(), "已静止 220 秒，但被驾驶员车挡住才 50 秒");
    monitor.check(Set.of("train1"), t0.plusSeconds(230));
    assertTrue(authority.handbacks().contains("drv:deadlock"), authority.handbacks()::toString);
  }

  @Test
  @DisplayName("驾驶员车在站里停站、等发车门控时挡住后车不计时：停站结束、仍挡着才开始算")
  void driverInPlannedStopIsNotTimed() {
    AtomicReference<String> driverStop = new AtomicReference<>("dwell");
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());
    when(dwellRegistry.remainingSeconds("drv"))
        .thenAnswer(
            invocation -> "dwell".equals(driverStop.get()) ? Optional.of(15) : Optional.empty());
    when(dispatchService.hasDepartureGate("drv"))
        .thenAnswer(invocation -> "gate".equals(driverStop.get()));
    when(dispatchService.getTrainState("train1"))
        .thenReturn(
            Optional.of(
                new RuntimeDispatchService.TrainRuntimeState("train1", 3, SignalAspect.STOP, 0.0)));
    when(dispatchService.recentBlockerTrains(eq("train1"), any())).thenReturn(Set.of("drv"));
    monitor.setAutoFixEnabled(true);
    monitor.setDeadlockMinStopDuration(Duration.ofSeconds(20));
    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(60));

    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    for (int i = 0; i <= 6; i++) {
      monitor.check(Set.of("train1"), t0.plusSeconds(10L * i));
    }
    driverStop.set("gate");
    for (int i = 7; i <= 12; i++) {
      monitor.check(Set.of("train1"), t0.plusSeconds(10L * i));
    }
    assertTrue(authority.handbacks().isEmpty(), "驾驶员车停站、等发车门控共 120 秒：都是计划停车");
    driverStop.set("none");
    for (int i = 13; i <= 18; i++) {
      monitor.check(Set.of("train1"), t0.plusSeconds(10L * i));
    }
    assertTrue(authority.handbacks().isEmpty(), "停站结束后才挡了 50 秒");
    monitor.check(Set.of("train1"), t0.plusSeconds(190));
    assertTrue(authority.handbacks().contains("drv:deadlock"), authority.handbacks()::toString);
  }

  private void stoppedBehind(AtomicReference<Set<String>> blockers) {
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("train1"))
        .thenReturn(
            Optional.of(
                new RuntimeDispatchService.TrainRuntimeState("train1", 3, SignalAspect.STOP, 0.0)));
    when(dispatchService.recentBlockerTrains(eq("train1"), any()))
        .thenAnswer(invocation -> blockers.get());
    monitor.setAutoFixEnabled(true);
    monitor.setDeadlockMinStopDuration(Duration.ofSeconds(20));
    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(60));
  }

  private static RuntimeDispatchService.DeadlockTrainContext layoverContext(String trainName) {
    return new RuntimeDispatchService.DeadlockTrainContext(
        trainName,
        10,
        11,
        SignalAspect.STOP,
        0.0,
        RouteOperationType.OPERATION,
        0,
        false,
        false,
        true,
        false,
        true,
        false,
        false);
  }

  @Test
  @DisplayName("驾驶员车在终点站待命或结算后等开出下一趟时挡住后车：不计时，不请交还")
  void driverWaitingAtTheTerminalIsNotTimed() {
    stoppedBehind(new AtomicReference<>(Set.of("drv", "drv2")));
    authority.controlName("drv2").awaitingTurnbackName("drv2");
    when(dispatchService.deadlockTrainContext("drv"))
        .thenReturn(Optional.of(layoverContext("drv")));

    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    for (int i = 0; i <= 30; i++) {
      monitor.check(Set.of("train1"), t0.plusSeconds(10L * i));
    }
    assertTrue(authority.handbacks().isEmpty(), authority.handbacks()::toString);
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
  }

  @Test
  @DisplayName("先后被两列驾驶员车挡住：按各自挡车的时长算，后来那列不接前一列的时间")
  void eachDriverIsTimedOnItsOwn() {
    AtomicReference<Set<String>> blockers = new AtomicReference<>(Set.of("drvA"));
    stoppedBehind(blockers);
    authority.controlName("drvA").controlName("drvB");

    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    for (int i = 0; i <= 7; i++) {
      monitor.check(Set.of("train1"), t0.plusSeconds(10L * i));
    }
    blockers.set(Set.of("drvB"));
    for (int i = 8; i <= 13; i++) {
      monitor.check(Set.of("train1"), t0.plusSeconds(10L * i));
    }
    assertTrue(authority.handbacks().isEmpty(), "drvA 挡了 50 秒就走了，drvB 才挡了 50 秒");
    monitor.check(Set.of("train1"), t0.plusSeconds(140));
    assertEquals(java.util.List.of("drvB:deadlock"), authority.handbacks());
  }

  @Test
  @DisplayName("一次采样没看到阻挡者（快照刚过期、信号重算）不清零；连续两次没看到才重新起算")
  void oneMissedSampleKeepsTheTimer() {
    AtomicReference<Set<String>> blockers = new AtomicReference<>(Set.of("drv"));
    stoppedBehind(blockers);

    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    for (int i = 0; i <= 8; i++) {
      blockers.set(i == 5 ? Set.of() : Set.of("drv"));
      monitor.check(Set.of("train1"), t0.plusSeconds(10L * i));
    }
    assertTrue(authority.handbacks().contains("drv:deadlock"), "中间漏一次照样满 60 秒交还");

    RecordingControlAuthority fresh = new RecordingControlAuthority().controlName("drv");
    TrainHealthMonitor second =
        new TrainHealthMonitor(dispatchService, dwellRegistry, new HealthAlertBus(), s -> {});
    second.setControlAuthority(fresh);
    second.setAutoFixEnabled(true);
    second.setDeadlockMinStopDuration(Duration.ofSeconds(20));
    second.setDeadlockDestroyThreshold(Duration.ofSeconds(60));
    for (int i = 0; i <= 8; i++) {
      blockers.set(i == 5 || i == 6 ? Set.of() : Set.of("drv"));
      second.check(Set.of("train1"), t0.plusSeconds(10L * i));
    }
    assertTrue(fresh.handbacks().isEmpty(), "连续两次没看到：从 70 秒起重新计时");
  }
}
