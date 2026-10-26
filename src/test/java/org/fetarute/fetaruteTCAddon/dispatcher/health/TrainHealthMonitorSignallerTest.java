package org.fetarute.fetaruteTCAddon.dispatcher.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DwellRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeStopState;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.SignallerGateCoordinator;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherController;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 停在控制区入口等信号员的车是计划内的静止：不重发、不清车；等待有超时自动排列兜底。 */
@DisplayName("TrainHealthMonitor 等信号员豁免")
class TrainHealthMonitorSignallerTest {

  private static final String TRAIN = "train1";

  private RuntimeDispatchService dispatchService;
  private SignallerGateCoordinator gates;
  private TrainHealthMonitor monitor;

  @BeforeEach
  void setUp() {
    dispatchService = mock(RuntimeDispatchService.class);
    DwellRegistry dwellRegistry = mock(DwellRegistry.class);
    when(dwellRegistry.remainingSeconds(TRAIN)).thenReturn(Optional.empty());
    gates = mock(SignallerGateCoordinator.class);
    when(dispatchService.signallerGates()).thenReturn(gates);
    monitor = new TrainHealthMonitor(dispatchService, dwellRegistry, new HealthAlertBus(), s -> {});
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
    when(dispatchService.applySmartQueuePositionYield(any()))
        .thenReturn(RuntimeDispatchService.SmartRecoveryActionResult.skipped("not-candidate"));
    when(dispatchService.reviewStuckCleanupCandidate(
            anyString(), anyInt(), anyBoolean(), any(), any(), any(), any()))
        .thenReturn(
            SmartDispatcherController.StuckCleanupReview.allowed("verified-long-stuck-cleanup"));
    when(dispatchService.getTrainState(TRAIN))
        .thenReturn(
            Optional.of(
                new RuntimeDispatchService.TrainRuntimeState(TRAIN, 3, SignalAspect.STOP, 0.0)));
    when(dispatchService.deadlockTrainContext(TRAIN))
        .thenReturn(
            Optional.of(
                new RuntimeDispatchService.DeadlockTrainContext(
                    TRAIN,
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
    when(dispatchService.destroyTrainByName(anyString(), anyString())).thenReturn(true);
    monitor.setAutoFixEnabled(true);
    monitor.setTrainCleanupEnabled(true);
    monitor.setProgressStuckThreshold(Duration.ofSeconds(5));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(5));
    monitor.setRecoveryCooldown(Duration.ofSeconds(1));
    monitor.setStuckCleanupThreshold(Duration.ofSeconds(20));
    monitor.setStuckCleanupPassengerThreshold(Duration.ofSeconds(60));
    monitor.setStuckCleanupCooldown(Duration.ZERO);
  }

  private int runFor(int samples) {
    Instant t0 = Instant.parse("2026-10-20T08:00:00Z");
    int stuck = 0;
    for (int i = 0; i <= samples; i++) {
      stuck += monitor.check(Set.of(TRAIN), t0.plusSeconds(10L * i)).progressStuckCount();
    }
    return stuck;
  }

  @Test
  @DisplayName("对照：没有信号员闸时，同样静止的车会被恢复、最终清掉")
  void withoutTheGateTheSameTrainIsRecovered() {
    when(gates.active()).thenReturn(false);

    int stuck = runFor(12);

    assertTrue(stuck > 0, "对照组必须真的触发停滞，否则下面的豁免用例是空绿");
    verify(dispatchService, atLeastOnce()).destroyTrainByName(anyString(), anyString());
  }

  @Test
  @DisplayName("闸记着它在等：不算停滞，不重评、不重发、不清车")
  void waitingForSignallerIsAPlannedStop() {
    when(gates.active()).thenReturn(true);
    when(gates.waiting(TRAIN)).thenReturn(true);

    assertEquals(0, runFor(12));
    verify(dispatchService, never()).refreshSignalByName(anyString());
    verify(dispatchService, never()).reissueDestinationByName(anyString());
    verify(dispatchService, never()).forceRelaunchByName(anyString());
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
  }

  @Test
  @DisplayName("只剩旧停因、闸已不认它在等：不豁免，照常恢复")
  void aLeftoverSignallerStopReasonIsNotExempt() {
    when(gates.active()).thenReturn(true);
    when(gates.waiting(TRAIN)).thenReturn(false);
    when(dispatchService.getActiveStopState(TRAIN))
        .thenReturn(
            Optional.of(
                new RuntimeStopState(
                    TRAIN,
                    SignallerGateCoordinator.STOP_REASON,
                    "box=SURC:PPK since=3",
                    RuntimeStopState.ReleaseCondition.BLOCKING_RESOURCES_RELEASED_OR_TRANSFERRED,
                    RuntimeStopState.RetryTrigger.values()[0],
                    List.of(),
                    false,
                    Instant.parse("2026-10-20T08:00:00Z"))));

    assertTrue(runFor(12) > 0, "许可到了、控制区回到自动或超时自动排列之后，旧停因不能继续豁免");
    verify(dispatchService, atLeastOnce()).destroyTrainByName(anyString(), anyString());
  }
}
