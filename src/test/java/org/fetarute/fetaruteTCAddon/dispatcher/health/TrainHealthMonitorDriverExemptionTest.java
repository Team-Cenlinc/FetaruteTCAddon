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
    for (int i = 0; i <= 6; i++) {
      monitor.check(Set.of("train1"), t0.plusSeconds(10L * i));
    }

    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertTrue(authority.handbacks().contains("drv:deadlock"), authority.handbacks()::toString);
  }
}
