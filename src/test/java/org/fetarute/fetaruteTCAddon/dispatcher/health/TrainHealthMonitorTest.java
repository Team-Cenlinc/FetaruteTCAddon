package org.fetarute.fetaruteTCAddon.dispatcher.health;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.fetarute.fetaruteTCAddon.company.model.RouteOperationType;
import org.fetarute.fetaruteTCAddon.config.ConfigManager;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.EdgeId;
import org.fetarute.fetaruteTCAddon.dispatcher.graph.RailGraphService;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinition;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteDefinitionCache;
import org.fetarute.fetaruteTCAddon.dispatcher.route.RouteId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.DwellRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.LayoverRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RouteProgressRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.RuntimeDispatchService;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.StationStopCoordinator;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainConfigResolver;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.DispatchEffectClass;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherController;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor.SmartDispatcherMode;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.AuthorizationPurpose;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.BlockerRelation;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ClaimRole;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.DirectedTraversalContext;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyClaim;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.SignNodeRegistry;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalComputationTrace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

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
    monitor.setTrainCleanupEnabled(true);
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
    when(dispatchService.hasRecentGateQueueEntry(anyString(), any())).thenReturn(false);
    stubDefaultDestroyPrecheck();
    when(dispatchService.reviewStuckCleanupCandidate(
            anyString(), anyInt(), anyBoolean(), any(), any(), any()))
        .thenReturn(
            SmartDispatcherController.StuckCleanupReview.allowed("verified-long-stuck-cleanup"));
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
            anyBoolean(),
            anyString(),
            anyBoolean(),
            anyBoolean(),
            anyBoolean(),
            any(),
            any()))
        .thenAnswer(
            invocation -> {
              boolean weak = invocation.getArgument(5);
              boolean allBlockersLiveHard = invocation.getArgument(6);
              boolean safeDrainCandidate = invocation.getArgument(7);
              boolean directionAuditRequired = invocation.getArgument(14);
              boolean lastResortDestroy = invocation.getArgument(17);
              Duration persisted = invocation.getArgument(19);
              Duration threshold = invocation.getArgument(20);
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
              if (directionAuditRequired && !lastResortDestroy) {
                return SmartDispatcherController.DeadlockDestroyReview.rejected(
                    "direction-audit-required");
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

  @Test
  void destructiveCleanupDefaultsDisabled() {
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 3, SignalAspect.STOP, 0.0)));
    when(dispatchService.deadlockTrainContext("train1"))
        .thenReturn(Optional.of(context("train1", 3, RouteOperationType.OPERATION, false, false)));
    TrainHealthMonitor disabledMonitor =
        new TrainHealthMonitor(dispatchService, dwellRegistry, alertBus, debugLogs::add);
    disabledMonitor.setProgressStuckThreshold(Duration.ofSeconds(5));
    disabledMonitor.setProgressStopGraceThreshold(Duration.ofSeconds(5));
    disabledMonitor.setRecoveryCooldown(Duration.ofSeconds(1));
    disabledMonitor.setStuckCleanupThreshold(Duration.ofSeconds(20));
    disabledMonitor.setStuckCleanupPassengerThreshold(Duration.ofSeconds(60));
    disabledMonitor.setStuckCleanupCooldown(Duration.ZERO);
    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

    disabledMonitor.check(Set.of("train1"), t0);
    disabledMonitor.check(Set.of("train1"), t0.plusSeconds(10));
    disabledMonitor.check(Set.of("train1"), t0.plusSeconds(20));
    disabledMonitor.check(Set.of("train1"), t0.plusSeconds(30));
    disabledMonitor.check(Set.of("train1"), t0.plusSeconds(40));

    verify(dispatchService, never())
        .reviewStuckCleanupCandidate(anyString(), anyInt(), anyBoolean(), any(), any(), any());
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
  }

  @Test
  void longStuckEmptyTrainIsCleanedAfterRecoveryIsExhausted() {
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 3, SignalAspect.STOP, 0.0)));
    when(dispatchService.deadlockTrainContext("train1"))
        .thenReturn(Optional.of(context("train1", 3, RouteOperationType.OPERATION, false, false)));
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

    monitor.check(Set.of("train1"), t0);
    monitor.check(Set.of("train1"), t0.plusSeconds(10));
    monitor.check(Set.of("train1"), t0.plusSeconds(20));
    monitor.check(Set.of("train1"), t0.plusSeconds(30));
    verify(dispatchService, never()).destroyTrainByName("train1", "health-stuck-cleanup-timeout");

    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train1"), t0.plusSeconds(40));

    verify(dispatchService)
        .reviewStuckCleanupCandidate(
            "train1",
            3,
            true,
            Duration.ofSeconds(40),
            Duration.ofSeconds(20),
            Duration.ofSeconds(60));
    verify(dispatchService).destroyTrainByName("train1", "health-stuck-cleanup-timeout");
    assertEquals(1, result.fixedCount(), "销毁/清理是当场完成的状态变化，当场计入");
  }

  @Test
  void proceedStallRecoveryCannotRunInSameCheckAsCleanup() {
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 3, SignalAspect.PROCEED, 0.0)));
    when(dispatchService.deadlockTrainContext("train1"))
        .thenReturn(Optional.of(context("train1", 3, RouteOperationType.OPERATION, false, false)));
    when(dispatchService.destroyTrainByName("train1", "health-stuck-cleanup-timeout"))
        .thenReturn(true);
    monitor.setTrainCleanupEnabled(true);
    monitor.setStallThreshold(Duration.ofSeconds(5));
    monitor.setProgressStuckThreshold(Duration.ofSeconds(5));
    monitor.setRecoveryCooldown(Duration.ofSeconds(1));
    monitor.setStuckCleanupThreshold(Duration.ofSeconds(20));
    monitor.setStuckCleanupPassengerThreshold(Duration.ofSeconds(60));
    monitor.setStuckCleanupCooldown(Duration.ZERO);
    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

    monitor.check(Set.of("train1"), t0);
    monitor.check(Set.of("train1"), t0.plusSeconds(10));
    monitor.check(Set.of("train1"), t0.plusSeconds(20));
    monitor.check(Set.of("train1"), t0.plusSeconds(30));
    verify(dispatchService, never()).destroyTrainByName("train1", "health-stuck-cleanup-timeout");
    clearInvocations(dispatchService);

    monitor.check(Set.of("train1"), t0.plusSeconds(40));

    verify(dispatchService, never()).refreshSignalByName("train1");
    verify(dispatchService, never()).forceRelaunchByName("train1");
    verify(dispatchService).destroyTrainByName("train1", "health-stuck-cleanup-timeout");
  }

  @Test
  void cleanupBatchSelectsEmptyTrainBeforePassengerTrain() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("passenger"))
        .thenReturn(Optional.of(state("passenger", 1, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("empty"))
        .thenReturn(Optional.of(state("empty", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.deadlockTrainContext("passenger"))
        .thenReturn(
            Optional.of(
                context("passenger", 1, RouteOperationType.RETURN, true, false, true, false)));
    when(dispatchService.deadlockTrainContext("empty"))
        .thenReturn(Optional.of(context("empty", 5, RouteOperationType.OPERATION, false, false)));
    when(dispatchService.destroyTrainByName("empty", "health-stuck-cleanup-timeout"))
        .thenReturn(true);
    monitor.setTrainCleanupEnabled(true);
    monitor.setProgressStuckThreshold(Duration.ofSeconds(5));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(5));
    monitor.setRecoveryCooldown(Duration.ofSeconds(1));
    monitor.setStuckCleanupThreshold(Duration.ofSeconds(20));
    monitor.setStuckCleanupPassengerThreshold(Duration.ofSeconds(20));
    monitor.setStuckCleanupCooldown(Duration.ZERO);
    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    Set<String> trains = Set.of("passenger", "empty");

    monitor.check(trains, t0);
    monitor.check(trains, t0.plusSeconds(10));
    monitor.check(trains, t0.plusSeconds(20));
    monitor.check(trains, t0.plusSeconds(30));
    monitor.check(trains, t0.plusSeconds(40));

    verify(dispatchService).destroyTrainByName("empty", "health-stuck-cleanup-timeout");
    verify(dispatchService, never())
        .destroyTrainByName("passenger", "health-stuck-cleanup-timeout");
  }

  @Test
  void liveQueueWaiterIsNeverGenericCleanupCandidate() {
    when(dwellRegistry.remainingSeconds("waiting")).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("waiting"))
        .thenReturn(Optional.of(state("waiting", 3, SignalAspect.STOP, 0.0)));
    when(dispatchService.deadlockTrainContext("waiting"))
        .thenReturn(Optional.of(context("waiting", 3, RouteOperationType.OPERATION, false, false)));
    when(dispatchService.recentBlockerTrains(eq("waiting"), any())).thenReturn(Set.of());
    when(dispatchService.hasRecentGateQueueEntry(eq("waiting"), any())).thenReturn(true);
    monitor.setTrainCleanupEnabled(true);
    monitor.setProgressStuckThreshold(Duration.ofSeconds(5));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(5));
    monitor.setRecoveryCooldown(Duration.ofSeconds(1));
    monitor.setStuckCleanupThreshold(Duration.ofSeconds(20));
    monitor.setStuckCleanupPassengerThreshold(Duration.ofSeconds(60));
    monitor.setStuckCleanupCooldown(Duration.ZERO);
    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

    monitor.check(Set.of("waiting"), t0);
    monitor.check(Set.of("waiting"), t0.plusSeconds(10));
    monitor.check(Set.of("waiting"), t0.plusSeconds(20));
    monitor.check(Set.of("waiting"), t0.plusSeconds(30));
    monitor.check(Set.of("waiting"), t0.plusSeconds(40));

    verify(dispatchService, never())
        .reviewStuckCleanupCandidate(anyString(), anyInt(), anyBoolean(), any(), any(), any());
    verify(dispatchService, never()).destroyTrainByName("waiting", "health-stuck-cleanup-timeout");
  }

  @Test
  void observeOnlyCyclesDoNotExhaustRecoveryBeforeEnforceIsEnabled() {
    AtomicBoolean enforce = new AtomicBoolean();
    when(dispatchService.smartDispatcherMode())
        .thenAnswer(
            invocation ->
                enforce.get() ? SmartDispatcherMode.ENFORCE : SmartDispatcherMode.OBSERVE_ONLY);
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 3, SignalAspect.STOP, 0.0)));
    when(dispatchService.deadlockTrainContext("train1"))
        .thenReturn(Optional.of(context("train1", 3, RouteOperationType.OPERATION, false, false)));
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

    monitor.check(Set.of("train1"), t0);
    monitor.check(Set.of("train1"), t0.plusSeconds(10));
    monitor.check(Set.of("train1"), t0.plusSeconds(20));
    monitor.check(Set.of("train1"), t0.plusSeconds(30));
    verify(dispatchService, never()).destroyTrainByName("train1", "health-stuck-cleanup-timeout");

    enforce.set(true);
    monitor.check(Set.of("train1"), t0.plusSeconds(40));
    monitor.check(Set.of("train1"), t0.plusSeconds(50));
    verify(dispatchService, never()).destroyTrainByName("train1", "health-stuck-cleanup-timeout");

    monitor.check(Set.of("train1"), t0.plusSeconds(60));
    verify(dispatchService, never()).destroyTrainByName("train1", "health-stuck-cleanup-timeout");

    monitor.check(Set.of("train1"), t0.plusSeconds(70));
    verify(dispatchService).destroyTrainByName("train1", "health-stuck-cleanup-timeout");
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

  /**
   * 「假定有效」在销毁上下文里必须继续挡住销毁。
   *
   * <p>{@code shouldHoldForSafeCandidate} 同时服务两件性质相反的事：恢复链要不要继续往下走 （{@code
   * destroyContext=false}），以及要不要**跳过销毁**（{@code destroyContext=true}）。
   * 把「假定有效」计为失败是链那一侧需要的（否则链停在第一步，割排队位一次都轮不到）， 但同一个改动若不分上下文，会把销毁门槛从「永远够不到」降成「两次尝试」——
   * 那是另一个独立的安全决定，而本项目的既定目标是解锁疏通、不是超时删车。
   *
   * <p>这一条钉住的就是那个耦合：安全候选每次都只是「假定有效」时，销毁仍必须永远不发生。
   */
  @Test
  @DisplayName("假定有效不得降低销毁门槛")
  void assumedEffectiveMustNotLowerTheDestroyBar() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.STOP, 0.0)));
    stubConfirmedDeadlock("trainA", "trainB");
    stubDefaultDestroyPrecheck();
    // 安全候选每次都 applied=true，effectiveness 走 5 参构造器 ⇒ 永远只是「假定有效」。
    when(dispatchService.applySmartSelfOwnedStaleRetainRelease(any()))
        .thenReturn(
            new RuntimeDispatchService.SmartRecoveryActionResult(
                true,
                true,
                "SMART_PHYSICAL_EDGE_RETAIN_RELEASED",
                "physical-edge-retain-released:2",
                org.fetarute
                    .fetaruteTCAddon
                    .dispatcher
                    .runtime
                    .supervisor
                    .DispatchEffectClass
                    .OCCUPANCY_MUTATION));

    monitor.setTrainCleanupEnabled(true);
    monitor.setProgressStuckThreshold(Duration.ofSeconds(5));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(5));
    monitor.setRecoveryCooldown(Duration.ofSeconds(1));
    monitor.setDeadlockThreshold(Duration.ofSeconds(10));
    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(20));
    monitor.setDeadlockDestroyCooldown(Duration.ZERO);

    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    monitor.check(Set.of("trainA", "trainB"), t0);
    // 远超销毁阈值，且安全候选反复「假定有效」——门槛若被降低，这里就会销毁。
    for (int i = 1; i <= 12; i++) {
      monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(30L * i));
    }

    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
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
    BlockerRelation relation =
        conflictKey.startsWith("switcher:")
            ? BlockerRelation.SWITCHER_CONFLICT
            : BlockerRelation.OPPOSITE_SINGLE_CONFLICT;
    return deadlockSnapshot(
        blockerTrain, conflictKey, direction, relation, ClaimRole.MOVEMENT_REQUIRED);
  }

  private RuntimeDispatchService.DeadlockBlockerSnapshot deadlockSnapshot(
      String blockerTrain,
      String conflictKey,
      CorridorDirection direction,
      BlockerRelation relation,
      ClaimRole role) {
    return deadlockSnapshot(blockerTrain, conflictKey, direction, relation.name(), role.name());
  }

  private RuntimeDispatchService.DeadlockBlockerSnapshot deadlockSnapshot(
      String blockerTrain,
      String conflictKey,
      CorridorDirection direction,
      String relation,
      String role) {
    return new RuntimeDispatchService.DeadlockBlockerSnapshot(
        Set.of(
            new RuntimeDispatchService.DeadlockBlockerInfo(
                blockerTrain,
                conflictKey,
                Optional.ofNullable(direction),
                blockerTrain,
                "CONFLICT:" + conflictKey,
                relation,
                ResourceIntent.MOVEMENT_REQUIRED.name(),
                role,
                "test",
                1L,
                1L)),
        Instant.now());
  }

  private void stubMutualSwitcherWait(String relation, String role) {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.STOP, 0.0)));
    when(dispatchService.recentDeadlockBlockers(eq("trainA"), any()))
        .thenReturn(deadlockSnapshot("trainB", "switcher:SW", null, relation, role));
    when(dispatchService.recentDeadlockBlockers(eq("trainB"), any()))
        .thenReturn(deadlockSnapshot("trainA", "switcher:SW", null, relation, role));
    when(dispatchService.deadlockTrainContext("trainA"))
        .thenReturn(Optional.of(context("trainA", 5, RouteOperationType.OPERATION, false, false)));
    when(dispatchService.deadlockTrainContext("trainB"))
        .thenReturn(Optional.of(context("trainB", 7, RouteOperationType.OPERATION, false, false)));
    when(dispatchService.destroyTrainByName(anyString(), eq("health-deadlock-timeout")))
        .thenReturn(true);
  }

  private void checkMutualWaitThroughDestroyThreshold() {
    monitor.setProgressStuckThreshold(Duration.ofSeconds(300));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(180));
    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(40));

    Instant t0 = Instant.now();
    monitor.check(Set.of("trainA", "trainB"), t0);
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(50));
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(100));
  }

  private void assertMutualWaitIsWeakAndCannotDestroy() {
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("episodeType=SWITCHER_OR_NODE_EDGE_WEAK")
                        && message.contains("destroyPolicy=diagnostic-only")),
        debugLogs::toString);
    assertFalse(
        debugLogs.stream()
            .anyMatch(message -> message.contains("SMART_DEADLOCK_LIVE_CYCLE_CONFIRMED")),
        debugLogs::toString);
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
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
  @DisplayName("按表扣车：在站里等点期间静止、进度不变都不算 stall / 进度停滞")
  void scheduledHoldIsNeitherStallNorProgressStuck() {
    List<HealthAlert> alerts = new ArrayList<>();
    alertBus.subscribe(alerts::add);
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 1, SignalAspect.PROCEED, 0.0)));
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());
    StationStopCoordinator stationStops = mock(StationStopCoordinator.class);
    when(dispatchService.stationStops()).thenReturn(stationStops);
    when(stationStops.holdingForSchedule("train1")).thenReturn(true);
    monitor.setProgressStuckThreshold(Duration.ofSeconds(60));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);
    TrainHealthMonitor.CheckResult result = monitor.check(Set.of("train1"), t0.plusSeconds(130));

    assertEquals(0, result.stallCount(), "等点不是 stall");
    assertEquals(0, result.progressStuckCount(), "等点不是进度停滞");
    assertTrue(alerts.isEmpty(), alerts::toString);
    verify(dispatchService, never()).refreshSignalByName(anyString());
  }

  @Test
  @DisplayName("Dwell 边界：停站结束后的短暂 STOP 不继承停站时长")
  void dwellExitStopDoesNotTriggerDeadlockFallbackBeforeFreshStopThreshold() {
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.STOP, 0.0)));
    when(dispatchService.recentDeadlockBlockers(eq("train1"), any()))
        .thenReturn(new RuntimeDispatchService.DeadlockBlockerSnapshot(Set.of(), Instant.EPOCH));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.of(1));
    monitor.check(Set.of("train1"), t0.plusSeconds(20));
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());
    monitor.check(Set.of("train1"), t0.plusSeconds(21));

    assertFalse(
        debugLogs.stream().anyMatch(message -> message.contains("DEADLOCK_DESTROY_SKIPPED")),
        debugLogs.toString());
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
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

    // 派发 ≠ 恢复：车尚未重新推进，因此这里断言的是派发计数。
    assertEquals(1, result.recoveryDispatchedCount());
    assertEquals(0, result.fixedCount(), "车还没动，不得计入已恢复");
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

    // 派发 ≠ 恢复：车尚未重新推进，因此这里断言的是派发计数。
    assertEquals(1, result.recoveryDispatchedCount());
    assertEquals(0, result.fixedCount(), "车还没动，不得计入已恢复");
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

    // 派发 ≠ 恢复：车尚未重新推进，因此这里断言的是派发计数。
    assertEquals(1, result.recoveryDispatchedCount());
    assertEquals(0, result.fixedCount(), "车还没动，不得计入已恢复");
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
  @DisplayName("方向证据不足时互卡快速 destroy 被拒绝并触发复审")
  void directionAuditBlocksFastDestroyAndTriggersReaudit() {
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.STOP, 0.0)));
    stubConfirmedDeadlock("trainA", "trainB");
    when(dispatchService.reapplyHardStopByName(anyString(), anyString())).thenReturn(false);
    when(dispatchService.recentDirectionAuditReason(eq("trainA"), any()))
        .thenReturn(Optional.of("INSUFFICIENT_DIRECTION_EVIDENCE"));

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
                    message.contains("SMART_DIRECTION_REAUDIT_REQUESTED")
                        && message.contains("reason=INSUFFICIENT_DIRECTION_EVIDENCE")));
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DESTROY_SKIPPED_SAFE_ALTERNATIVE")
                        && message.contains("reason=direction-audit-required")));
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
  @DisplayName("道岔 QUEUE_POSITION 互等只形成 weak episode，不能满足 destroy hard-cycle")
  void switcherQueuePositionMutualWaitRemainsWeakAndCannotDestroy() {
    stubMutualSwitcherWait(
        BlockerRelation.SWITCHER_CONFLICT.name(), ClaimRole.QUEUE_POSITION.name());
    checkMutualWaitThroughDestroyThreshold();
    assertMutualWaitIsWeakAndCannotDestroy();
  }

  @Test
  @DisplayName("道岔 UNLOCK_RESERVATION 互等只形成 weak episode，不能满足 destroy hard-cycle")
  void switcherUnlockReservationMutualWaitRemainsWeakAndCannotDestroy() {
    stubMutualSwitcherWait(
        BlockerRelation.SWITCHER_CONFLICT.name(), ClaimRole.UNLOCK_RESERVATION.name());
    checkMutualWaitThroughDestroyThreshold();
    assertMutualWaitIsWeakAndCannotDestroy();
  }

  @Test
  @DisplayName("道岔 PROTECTIVE_RETAIN 互等只形成 weak episode，不能满足 destroy hard-cycle")
  void switcherProtectiveRetainMutualWaitRemainsWeakAndCannotDestroy() {
    stubMutualSwitcherWait(
        BlockerRelation.SWITCHER_CONFLICT.name(), ClaimRole.PROTECTIVE_RETAIN.name());
    checkMutualWaitThroughDestroyThreshold();
    assertMutualWaitIsWeakAndCannotDestroy();
  }

  @Test
  @DisplayName("道岔 UNKNOWN 互等只形成 weak episode，不能满足 destroy hard-cycle")
  void switcherUnknownMutualWaitRemainsWeakAndCannotDestroy() {
    stubMutualSwitcherWait("UNKNOWN", "UNKNOWN");
    checkMutualWaitThroughDestroyThreshold();
    assertMutualWaitIsWeakAndCannotDestroy();
  }

  @Test
  @DisplayName("实体 PHYSICAL_FOOTPRINT 道岔 blocker 可确认 live hard-cycle")
  void switcherPhysicalFootprintMutualWaitConfirmsLiveHardCycle() {
    stubMutualSwitcherWait(
        BlockerRelation.HARD_OCCUPANCY.name(), ClaimRole.PHYSICAL_FOOTPRINT.name());
    checkMutualWaitThroughDestroyThreshold();

    assertTrue(
        debugLogs.stream()
            .anyMatch(message -> message.contains("SMART_DEADLOCK_LIVE_CYCLE_CONFIRMED")),
        debugLogs::toString);
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("episodeType=LIVE_MUTUAL_BLOCKER_CYCLE")
                        && message.contains("destroyPolicy=confirmed-live-hard-cycle")),
        debugLogs::toString);
  }

  @Test
  @DisplayName("destroy precheck 使用最新 typed blocker，降级为软预约后拒绝销毁")
  void destroyPrecheckRejectsLiveCycleWhenLatestTypedSnapshotIsNoLongerHard() {
    AtomicBoolean hardEvidence = new AtomicBoolean(true);
    AtomicBoolean downgradeOnForwardUnlock = new AtomicBoolean(false);
    when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
    when(dispatchService.getTrainState("trainA"))
        .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
    when(dispatchService.getTrainState("trainB"))
        .thenReturn(Optional.of(state("trainB", 7, SignalAspect.STOP, 0.0)));
    when(dispatchService.recentDeadlockBlockers(eq("trainA"), any()))
        .thenAnswer(
            ignored ->
                deadlockSnapshot(
                    "trainB",
                    "switcher:SW",
                    null,
                    BlockerRelation.SWITCHER_CONFLICT.name(),
                    hardEvidence.get()
                        ? ClaimRole.MOVEMENT_REQUIRED.name()
                        : ClaimRole.UNLOCK_RESERVATION.name()));
    when(dispatchService.recentDeadlockBlockers(eq("trainB"), any()))
        .thenAnswer(
            ignored ->
                deadlockSnapshot(
                    "trainA",
                    "switcher:SW",
                    null,
                    BlockerRelation.SWITCHER_CONFLICT.name(),
                    hardEvidence.get()
                        ? ClaimRole.MOVEMENT_REQUIRED.name()
                        : ClaimRole.UNLOCK_RESERVATION.name()));
    when(dispatchService.deadlockTrainContext("trainA"))
        .thenReturn(Optional.of(context("trainA", 5, RouteOperationType.OPERATION, false, false)));
    when(dispatchService.deadlockTrainContext("trainB"))
        .thenReturn(Optional.of(context("trainB", 7, RouteOperationType.OPERATION, false, false)));
    when(dispatchService.applySmartForwardUnlock(any()))
        .thenAnswer(
            ignored -> {
              if (downgradeOnForwardUnlock.get()) {
                hardEvidence.set(false);
              }
              return RuntimeDispatchService.SmartRecoveryActionResult.skipped("not-candidate");
            });
    when(dispatchService.destroyTrainByName(anyString(), eq("health-deadlock-timeout")))
        .thenReturn(true);

    monitor.setProgressStuckThreshold(Duration.ofSeconds(300));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(180));
    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(40));

    Instant t0 = Instant.now();
    monitor.check(Set.of("trainA", "trainB"), t0);
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(50));
    downgradeOnForwardUnlock.set(true);
    monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(100));

    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DESTROY_SKIPPED_SAFE_ALTERNATIVE")
                        && message.contains("reason=blockers-not-all-live-hard")),
        debugLogs::toString);
  }

  @Test
  @DisplayName("真实占用快照形成 live cycle，冲突释放后销毁前检查不改写幸存授权")
  void realOccupancyCycleRecoveryAndDestroyBeforeCheckPreserveSurvivorClaims() {
    String trainA = "trainA";
    String trainB = "trainB";
    Instant occupancyTime = Instant.now();
    NodeId firstEntry = NodeId.of("A:ENTRY");
    NodeId firstSwitcher = NodeId.of("SWITCHER:TEST:FIRST");
    NodeId firstExit = NodeId.of("A:EXIT");
    NodeId secondEntry = NodeId.of("B:ENTRY");
    NodeId secondSwitcher = NodeId.of("SWITCHER:TEST:SECOND");
    NodeId secondExit = NodeId.of("B:EXIT");
    OccupancyResource firstConflict =
        OccupancyResource.forConflict("switcher:" + firstSwitcher.value());
    OccupancyResource secondConflict =
        OccupancyResource.forConflict("switcher:" + secondSwitcher.value());
    OccupancyResource secondSwitcherNode = OccupancyResource.forNode(secondSwitcher);
    OccupancyResource secondExitEdge =
        OccupancyResource.forEdge(EdgeId.undirected(secondSwitcher, secondExit));
    OccupancyResource secondExitNode = OccupancyResource.forNode(secondExit);
    SimpleOccupancyManager manager =
        new SimpleOccupancyManager(
            (routeId, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());

    RouteDefinition route =
        new RouteDefinition(
            RouteId.of("health-route"),
            List.of(NodeId.of("ROUTE:A"), NodeId.of("ROUTE:B")),
            Optional.empty());
    TrainProperties propertiesA = healthTrainProperties(trainA);
    TrainProperties propertiesB = healthTrainProperties(trainB);
    RouteProgressRegistry progressRegistry = new RouteProgressRegistry();
    progressRegistry.initFromTags(trainA, propertiesA, route);
    progressRegistry.initFromTags(trainB, propertiesB, route);
    progressRegistry.updateSignal(trainA, SignalAspect.STOP, occupancyTime);
    progressRegistry.updateSignal(trainB, SignalAspect.STOP, occupancyTime);
    RouteDefinitionCache routes = mock(RouteDefinitionCache.class);
    when(routes.findByCodes("op", "line", "route")).thenReturn(Optional.of(route));
    ConfigManager configManager = mock(ConfigManager.class, RETURNS_DEEP_STUBS);
    when(configManager.current().runtimeSettings().distanceCacheRefreshSeconds()).thenReturn(3);
    when(configManager.current().runtimeSettings().pathCacheMaxSize()).thenReturn(256);
    when(configManager.current().smartDispatcherSettings().mode())
        .thenReturn(SmartDispatcherMode.ENFORCE);
    List<String> integrationLogs = new ArrayList<>();
    RuntimeDispatchService realService =
        new RuntimeDispatchService(
            manager,
            mock(RailGraphService.class),
            routes,
            progressRegistry,
            mock(SignNodeRegistry.class),
            new LayoverRegistry(),
            new DwellRegistry(),
            configManager,
            null,
            new TrainConfigResolver(),
            integrationLogs::add);
    TrainHealthMonitor realMonitor =
        new TrainHealthMonitor(
            realService, new DwellRegistry(), new HealthAlertBus(), integrationLogs::add);
    realMonitor.setProgressStuckThreshold(Duration.ofMinutes(5));
    realMonitor.setProgressStopGraceThreshold(Duration.ofMinutes(3));
    realMonitor.setDeadlockDestroyThreshold(Duration.ofSeconds(1));

    OccupancyRequest firstOwner =
        healthSwitcherRequest(
            trainA,
            occupancyTime,
            firstConflict,
            List.of(firstEntry, firstSwitcher, firstExit),
            List.of(firstConflict),
            manager.version(),
            progressRegistry.version());
    OccupancyRequest secondOwner =
        healthSwitcherRequest(
            trainB,
            occupancyTime.plusMillis(1),
            secondConflict,
            List.of(secondEntry, secondSwitcher, secondExit),
            List.of(secondConflict),
            manager.version(),
            progressRegistry.version());
    assertTrue(manager.acquire(firstOwner).allowed());
    assertTrue(manager.acquire(secondOwner).allowed());
    assertTrue(
        manager
            .acquire(
                new OccupancyRequest(
                    trainA,
                    Optional.empty(),
                    occupancyTime.plusMillis(2),
                    List.of(secondSwitcherNode, secondExitEdge),
                    Map.of(),
                    Map.of(),
                    0,
                    AuthorizationPurpose.RUNTIME_MOVE,
                    Map.of(),
                    Map.of(
                        secondSwitcherNode,
                        ResourceIntent.HOLD_ONLY,
                        secondExitEdge,
                        ResourceIntent.HOLD_ONLY)))
            .allowed());
    OccupancyRequest trainAWaiting =
        healthSwitcherRequest(
            trainA,
            occupancyTime.plusMillis(3),
            secondConflict,
            List.of(secondSwitcher, secondExit),
            List.of(secondConflict, secondSwitcherNode, secondExitEdge, secondExitNode),
            manager.version(),
            progressRegistry.version());
    OccupancyRequest trainBWaiting =
        healthSwitcherRequest(
            trainB,
            occupancyTime.plusMillis(4),
            firstConflict,
            List.of(NodeId.of("B:ALT"), firstSwitcher, NodeId.of("B:OUT")),
            List.of(firstConflict),
            manager.version(),
            progressRegistry.version());
    assertFalse(manager.canEnter(trainAWaiting).allowed());
    assertFalse(manager.canEnter(trainBWaiting).allowed());
    assertEquals(
        Set.of(trainB),
        realService.recentBlockerTrains(trainA, Duration.ofMinutes(1)),
        integrationLogs::toString);
    assertEquals(
        Set.of(trainA),
        realService.recentBlockerTrains(trainB, Duration.ofMinutes(1)),
        integrationLogs::toString);

    AtomicBoolean trainBPresent = new AtomicBoolean(true);
    try (MockedStatic<TrainPropertiesStore> store = mockStatic(TrainPropertiesStore.class)) {
      store.when(() -> TrainPropertiesStore.get(trainA)).thenReturn(propertiesA);
      store
          .when(() -> TrainPropertiesStore.get(trainB))
          .thenAnswer(ignored -> trainBPresent.get() ? propertiesB : null);
      store
          .when(TrainPropertiesStore::getAll)
          .thenAnswer(
              ignored ->
                  trainBPresent.get() ? List.of(propertiesA, propertiesB) : List.of(propertiesA));

      long versionBeforeRecovery = manager.version();
      Instant healthTime = Instant.now();
      realMonitor.check(Set.of(trainA, trainB), healthTime);
      realMonitor.check(Set.of(trainA, trainB), healthTime.plusSeconds(50));

      assertTrue(
          integrationLogs.stream()
              .anyMatch(message -> message.contains("SMART_DEADLOCK_LIVE_CYCLE_CONFIRMED")),
          integrationLogs.toString());
      assertTrue(
          integrationLogs.stream()
              .anyMatch(message -> message.contains("SMART_RECOVERY_EVALUATION_ENTER")),
          integrationLogs.toString());

      assertTrue(manager.version() > versionBeforeRecovery);
      assertEquals(trainB, manager.getClaim(secondConflict).orElseThrow().trainName());
      assertTrue(
          manager.snapshotClaims().stream()
              .anyMatch(
                  claim ->
                      claim.trainName().equals(trainA)
                          && claim.resource().equals(secondExitNode)
                          && claim.role() == ClaimRole.MOVEMENT_REQUIRED));
      assertTrue(
          integrationLogs.stream()
              .anyMatch(
                  message ->
                      message.contains("entryType=DEADLOCK_RELEASE_LOCK")
                          && message.contains("train=" + trainA)),
          integrationLogs.toString());
      assertTrue(
          integrationLogs.stream()
              .anyMatch(
                  message ->
                      message.contains("SMART_SWITCHER_DRAIN_RECOVERY_APPLIED")
                          && message.contains("train=" + trainA)
                          && message.contains("occupancyMutated=true")),
          integrationLogs.toString());

      manager.releaseByTrain(trainB);
      progressRegistry.remove(trainB);
      trainBPresent.set(false);
      List<OccupancyClaim> survivorClaimsBeforeCheck =
          manager.snapshotClaims().stream()
              .filter(claim -> claim.trainName().equals(trainA))
              .toList();

      realMonitor.check(Set.of(trainA), healthTime.plusSeconds(100));

      assertEquals(
          survivorClaimsBeforeCheck,
          manager.snapshotClaims().stream()
              .filter(claim -> claim.trainName().equals(trainA))
              .toList());
      assertFalse(
          integrationLogs.stream()
              .anyMatch(message -> message.contains("DEADLOCK_DESTROY_ATTEMPTED")),
          integrationLogs.toString());
    } finally {
      SignalComputationTrace.configureLogger(null);
    }
  }

  /**
   * 2026-09-27 实服 OFL：DS 与 MT 互卡，恢复层每 11 秒对 DS 做一次"释放车后保护占用"（假定有效），下一拍又被占回， 17 分钟 320
   * 次都停在这一步、每次报"已修复"； MT 只差 DS 的一个排队位，排队位让位一次也没轮到。
   */
  @Test
  @DisplayName("互卡链：假定有效的动作连着两次没解开，链继续走到 B 车的排队位让位")
  void mutualChainMovesPastAssumedEffectiveActionsToQueuePositionYield() {
    MutualChainFixture fixture = new MutualChainFixture();
    when(dispatchService.applySmartSelfOwnedStaleRetainRelease(any()))
        .thenReturn(assumedEffective("SMART_PHYSICAL_EDGE_RETAIN_RELEASED"));
    when(dispatchService.applySmartQueuePositionYield(fixture.inputB))
        .thenReturn(measuredEffective("SMART_QUEUE_POSITION_YIELD"));

    fixture.runUntil(125);

    verify(dispatchService, atLeastOnce()).applySmartQueuePositionYield(fixture.inputB);
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
  }

  @Test
  @DisplayName("互卡链：假定有效不报已修复，测量有效才报")
  void mutualChainReportsFixedOnlyForMeasuredEffectiveness() {
    MutualChainFixture fixture = new MutualChainFixture();
    when(dispatchService.applySmartSelfOwnedStaleRetainRelease(any()))
        .thenReturn(assumedEffective("SMART_PHYSICAL_EDGE_RETAIN_RELEASED"));

    assertEquals(0, fixture.runUntil(125), "假定有效只是派发了动作，车没动就不算修好");

    when(dispatchService.applySmartSelfOwnedStaleRetainRelease(any()))
        .thenReturn(measuredEffective("SMART_PHYSICAL_EDGE_RETAIN_RELEASED"));
    assertTrue(fixture.checkAt(140).fixedCount() > 0);
  }

  /** 链往下走不等于放宽销毁：有动作落地且（被假定）有效，本轮照旧不销毁。 */
  @Test
  @DisplayName("互卡链：假定有效的动作仍挡住销毁")
  void mutualChainStillBlocksDestroyWhileAnUnlockIsAssumedEffective() {
    MutualChainFixture fixture = new MutualChainFixture();
    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(40));
    when(dispatchService.applySmartSelfOwnedStaleRetainRelease(any()))
        .thenReturn(assumedEffective("SMART_PHYSICAL_EDGE_RETAIN_RELEASED"));

    fixture.runUntil(305);

    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("DEADLOCK_DESTROY_SKIPPED")
                        && message.contains("safe-unlock-applied")));
  }

  /** 两车互卡、各自的恢复输入；每 15 秒检查一次。 */
  private final class MutualChainFixture {
    private final RuntimeDispatchService.SmartRecoveryInput inputA =
        smartRecoveryInput("trainA", SignalComputationTrace.TokenState.ACTIVE, true, "mutual");
    private final RuntimeDispatchService.SmartRecoveryInput inputB =
        smartRecoveryInput("trainB", SignalComputationTrace.TokenState.ACTIVE, true, "mutual");
    private final Instant t0 = Instant.now();

    private MutualChainFixture() {
      when(dwellRegistry.remainingSeconds(anyString())).thenReturn(Optional.empty());
      when(dispatchService.getTrainState("trainA"))
          .thenReturn(Optional.of(state("trainA", 5, SignalAspect.STOP, 0.0)));
      when(dispatchService.getTrainState("trainB"))
          .thenReturn(Optional.of(state("trainB", 7, SignalAspect.STOP, 0.0)));
      stubConfirmedDeadlock("trainA", "trainB");
      when(dispatchService.reapplyHardStopByName(anyString(), anyString())).thenReturn(true);
      when(dispatchService.smartRecoveryInput(eq("trainA"), any(), any())).thenReturn(inputA);
      when(dispatchService.smartRecoveryInput(eq("trainB"), any(), any())).thenReturn(inputB);
      monitor.setProgressStuckThreshold(Duration.ofSeconds(300));
      monitor.setProgressStopGraceThreshold(Duration.ofSeconds(180));
      monitor.check(Set.of("trainA", "trainB"), t0);
    }

    /** 从 50 秒起每 15 秒检查一次直到 {@code lastSecond}，返回累计 fixedCount。 */
    private int runUntil(int lastSecond) {
      int fixed = 0;
      for (int second = 50; second <= lastSecond; second += 15) {
        fixed += checkAt(second).fixedCount();
      }
      return fixed;
    }

    private TrainHealthMonitor.CheckResult checkAt(int second) {
      return monitor.check(Set.of("trainA", "trainB"), t0.plusSeconds(second));
    }
  }

  private static RuntimeDispatchService.SmartRecoveryActionResult assumedEffective(
      String decision) {
    return new RuntimeDispatchService.SmartRecoveryActionResult(
        true, true, decision, "test", DispatchEffectClass.SIGNAL_CONSTRAINT);
  }

  private static RuntimeDispatchService.SmartRecoveryActionResult measuredEffective(
      String decision) {
    return new RuntimeDispatchService.SmartRecoveryActionResult(
        true,
        true,
        decision,
        "test",
        DispatchEffectClass.SIGNAL_CONSTRAINT,
        new RuntimeDispatchService.SmartRecoveryEffectiveness(
            decision,
            "-",
            true,
            SignalAspect.PROCEED,
            true,
            true,
            SignalComputationTrace.TokenState.ACTIVE,
            SignalComputationTrace.TokenState.ACTIVE,
            false,
            false,
            false,
            false,
            "measured"));
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
    monitor.setProgressStuckThreshold(Duration.ofSeconds(300));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(180));
    Instant t0 = Instant.now();
    monitor.check(Set.of("follower", "leader"), t0);
    monitor.check(Set.of("follower", "leader"), t0.plusSeconds(50));
    TrainHealthMonitor.CheckResult result =
        monitor.check(Set.of("follower", "leader"), t0.plusSeconds(65));

    assertEquals(1, result.fixedCount(), "销毁是当场可验证的状态变化，当场计入");
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
    monitor.setProgressStuckThreshold(Duration.ofSeconds(300));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(180));
    Instant t0 = Instant.now();
    monitor.check(Set.of("follower", "leader"), t0);
    monitor.check(Set.of("follower", "leader"), t0.plusSeconds(50));
    TrainHealthMonitor.CheckResult result =
        monitor.check(Set.of("follower", "leader"), t0.plusSeconds(65));

    assertEquals(1, result.fixedCount(), "销毁/清理是当场完成的状态变化，当场计入");
    verify(dispatchService).destroyTrainByName("follower", "health-deadlock-timeout");
    verify(dispatchService, never()).destroyTrainByName(eq("leader"), anyString());
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DEADLOCK_DESTROY_EXECUTED")
                        && message.contains("train=follower")
                        && message.contains("evidenceGroup=CURRENT_TRAIN_AUTHORITY_FALLBACK")
                        && message.contains("blockerTrain=leader")));
  }

  @Test
  @DisplayName("当前等待车授权失效时，恢复诊断保留原有阻塞方向")
  void currentAuthorityFallbackDoesNotReverseWaiterAndBlocker() {
    stubFollowerBlockedByLeader(
        smartRecoveryInput(
            "leader", SignalComputationTrace.TokenState.ACTIVE, true, "leader-authority-active"),
        context("leader", 7, RouteOperationType.OPERATION, false, false));
    when(dispatchService.smartRecoveryInput(eq("follower"), any(), any()))
        .thenReturn(
            smartRecoveryInput(
                "follower",
                SignalComputationTrace.TokenState.INVALID,
                true,
                "current-authority-invalid"));
    monitor.setTrainCleanupEnabled(false);
    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(40));
    Instant t0 = Instant.now();

    monitor.check(Set.of("follower", "leader"), t0);
    monitor.check(Set.of("follower", "leader"), t0.plusSeconds(50));
    monitor.check(Set.of("follower", "leader"), t0.plusSeconds(65));

    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.startsWith("SMART_DEADLOCK_DESTROY_ELIGIBILITY:")
                        && message.contains("train=follower ")
                        && message.contains("blockerTrain=leader ")
                        && message.contains("evidenceGroup=CURRENT_TRAIN_AUTHORITY_FALLBACK")
                        && message.contains("followerStuckLeaderEvidencePresent=false")),
        debugLogs::toString);
    assertFalse(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("train=follower ")
                        && message.contains("evidenceFollower=leader ")));
    assertFalse(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("train=leader ")
                        && message.contains("blockerTrain=follower ")));
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
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
    TrainHealthMonitor.CheckResult result =
        monitor.check(Set.of("follower", "leader"), t0.plusSeconds(50));

    assertEquals(1, result.fixedCount(), "销毁/清理是当场完成的状态变化，当场计入");
    verify(dispatchService).destroyTrainByName("leader", "health-deadlock-timeout");
    verify(dispatchService, never()).destroyTrainByName(eq("follower"), anyString());
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DEADLOCK_DESTROY_EXECUTED")
                        && message.contains("train=leader")
                        && message.contains("evidenceGroup=STUCK_LEADER_FALLBACK")
                        && message.contains("evidenceFollower=follower")
                        && !message.contains("blockerTrain=")));
  }

  @Test
  @DisplayName("互卡销毁兜底：follower stuck leader 证据先尝试安全恢复再考虑销毁")
  void followerStuckLeaderFallbackTriesSafeRecoveryBeforeDestroy() {
    RuntimeDispatchService.SmartRecoveryInput leaderInput =
        smartRecoveryInput(
            "leader",
            SignalComputationTrace.TokenState.ACTIVE,
            true,
            "leader-authority-active-but-terminal-mutex");
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
    when(dispatchService.smartRecoveryInput(eq("leader"), any(), any())).thenReturn(leaderInput);
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
    when(dispatchService.applySmartForwardUnlock(leaderInput))
        .thenReturn(
            new RuntimeDispatchService.SmartRecoveryActionResult(
                true,
                true,
                "SMART_FORWARD_UNLOCK",
                "authority-token-repair",
                DispatchEffectClass.SIGNAL_CONSTRAINT));

    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(40));
    monitor.setProgressStuckThreshold(Duration.ofSeconds(300));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(180));
    Instant t0 = Instant.now();
    monitor.check(Set.of("follower", "leader"), t0);
    TrainHealthMonitor.CheckResult result =
        monitor.check(Set.of("follower", "leader"), t0.plusSeconds(50));

    assertEquals(1, result.fixedCount(), "销毁/清理是当场完成的状态变化，当场计入");
    verify(dispatchService).applySmartSelfOwnedStaleRetainRelease(leaderInput);
    verify(dispatchService).applySmartDrainUnlock(leaderInput);
    verify(dispatchService).applySmartForwardUnlock(leaderInput);
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DESTROY_SKIPPED_SAFE_ALTERNATIVE")
                        && message.contains("recoveryDecision=SMART_FORWARD_UNLOCK")
                        && message.contains("evidenceGroup=STUCK_LEADER_FALLBACK")));
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_FALLBACK_RECOVERY_ACTION_ORDER")
                        && message.contains("evidenceFollower=follower")
                        && !message.contains("blockerTrain=")));
  }

  @Test
  @DisplayName("互卡销毁兜底：active authority 的 stuck leader 无安全恢复时不销毁")
  void followerStuckLeaderFallbackDoesNotDestroyActiveAuthorityLeaderWithoutSafeRecovery() {
    RuntimeDispatchService.SmartRecoveryInput leaderInput =
        smartRecoveryInput(
            "leader",
            SignalComputationTrace.TokenState.ACTIVE,
            true,
            "leader-authority-active-but-terminal-mutex");
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
    when(dispatchService.smartRecoveryInput(eq("leader"), any(), any())).thenReturn(leaderInput);
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

    monitor.setDeadlockDestroyThreshold(Duration.ofSeconds(40));
    monitor.setProgressStuckThreshold(Duration.ofSeconds(300));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(180));
    Instant t0 = Instant.now();
    monitor.check(Set.of("follower", "leader"), t0);
    TrainHealthMonitor.CheckResult result =
        monitor.check(Set.of("follower", "leader"), t0.plusSeconds(50));

    assertEquals(0, result.fixedCount());
    verify(dispatchService).applySmartSelfOwnedStaleRetainRelease(leaderInput);
    verify(dispatchService).applySmartDrainUnlock(leaderInput);
    verify(dispatchService).applySmartForwardUnlock(leaderInput);
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DEADLOCK_DESTROY_ELIGIBILITY")
                        && message.contains("evidenceGroup=STUCK_LEADER_FALLBACK")
                        && message.contains("ineligibleReason=TARGET_AUTHORITY_ACTIVE")));
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

  private static TrainProperties healthTrainProperties(String trainName) {
    TrainProperties properties = mock(TrainProperties.class);
    when(properties.getTrainName()).thenReturn(trainName);
    when(properties.hasTags()).thenReturn(true);
    when(properties.getTags())
        .thenReturn(
            List.of(
                "FTA_OPERATOR_CODE=op",
                "FTA_LINE_CODE=line",
                "FTA_ROUTE_CODE=route",
                "FTA_ROUTE_INDEX=0"));
    return properties;
  }

  private static OccupancyRequest healthSwitcherRequest(
      String trainName,
      Instant now,
      OccupancyResource conflict,
      List<NodeId> pathNodes,
      List<OccupancyResource> resources,
      long occupancyVersion,
      long progressVersion) {
    List<DirectedTraversalContext.DirectedEdge> directedEdges = new ArrayList<>();
    for (int index = 0; index + 1 < pathNodes.size(); index++) {
      NodeId from = pathNodes.get(index);
      NodeId to = pathNodes.get(index + 1);
      directedEdges.add(
          new DirectedTraversalContext.DirectedEdge(EdgeId.undirected(from, to), from, to));
    }
    OccupancyRequest request =
        new OccupancyRequest(
            trainName, Optional.empty(), now, resources, Map.of(), Map.of(conflict.key(), 0), 0);
    return request.withDirectedContext(
        Optional.of(
            new DirectedTraversalContext(
                trainName,
                Optional.empty(),
                0,
                Optional.of(pathNodes.get(0)),
                Optional.of(NodeId.of("ROUTE:A")),
                Optional.of(pathNodes.get(0)),
                pathNodes.size() < 2 ? Optional.empty() : Optional.of(pathNodes.get(1)),
                pathNodes,
                directedEdges,
                Map.of(),
                Map.of(
                    conflict.key(),
                    new DirectedTraversalContext.SwitcherPathSignature(conflict.key(), pathNodes)),
                "HEALTH_INTEGRATION",
                occupancyVersion,
                progressVersion,
                "health-integration",
                Optional.empty())));
  }

  @Test
  @DisplayName("销毁关闭时，报的必须是真正拦住它的那一道，而不是 DESTROY_DISABLED")
  void destroyDisabledMustNotMaskTheCriterionThatActuallyBlocksDestruction() {
    RuntimeDispatchService.SmartRecoveryInput leaderInput =
        smartRecoveryInput(
            "leader",
            SignalComputationTrace.TokenState.ACTIVE,
            true,
            "leader-authority-active-but-terminal-mutex");
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
    when(dispatchService.smartRecoveryInput(eq("leader"), any(), any())).thenReturn(leaderInput);
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

    // 阈值置零 = 销毁**关闭**。旧实现里 DESTROY_DISABLED 排在整条链第一道，于是这里会短路，
    // 后面八道一次都不被求值——实服第十二轮 102 次评估全部只报这一个字符串，
    // 包括两辆卡死 2073 秒和 1160 秒的车。于是"就算打开销毁它们够不够格"只能靠真的打开来回答，
    // 而那是不可逆、玩家可见的动作。挪到最后之后，关闭状态下也能看到真正的拦截点。
    monitor.setDeadlockDestroyThreshold(Duration.ZERO);
    monitor.setProgressStuckThreshold(Duration.ofSeconds(300));
    monitor.setProgressStopGraceThreshold(Duration.ofSeconds(180));
    Instant t0 = Instant.now();
    monitor.check(Set.of("follower", "leader"), t0);
    TrainHealthMonitor.CheckResult result =
        monitor.check(Set.of("follower", "leader"), t0.plusSeconds(50));

    assertEquals(0, result.fixedCount());
    verify(dispatchService).applySmartSelfOwnedStaleRetainRelease(leaderInput);
    verify(dispatchService).applySmartDrainUnlock(leaderInput);
    verify(dispatchService).applySmartForwardUnlock(leaderInput);
    verify(dispatchService, never()).destroyTrainByName(anyString(), anyString());
    // 判别核心：关闭状态下报的是**真正的**拦截理由。
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DEADLOCK_DESTROY_ELIGIBILITY")
                        && message.contains("ineligibleReason=TARGET_AUTHORITY_ACTIVE")),
        () -> "销毁关闭不得遮住真正的拦截理由：" + debugLogs);
    // 语义必须不变：关闭时永远不销毁（上面的 verify never 已钉住），
    // 且不得把 DESTROY_DISABLED 当成这一轮的结论输出。
    assertFalse(
        debugLogs.stream()
            .anyMatch(
                message ->
                    message.contains("SMART_DEADLOCK_DESTROY_ELIGIBILITY")
                        && message.contains("ineligibleReason=DESTROY_DISABLED")),
        () -> "本场景应报真正的拦截理由，而不是 DESTROY_DISABLED：" + debugLogs);
  }

  @Test
  @DisplayName("恢复动作派发后车没动，不得宣布已修复；车动了才算")
  void recoveryIsOnlyReportedFixedAfterProgressActuallyResumes() {
    // 实服第十五轮：SURC-WS-LN-3176 在同一个 idx=17 上"告警→已修复→告警→已修复"翻了 29 分钟，
    // 而 `持续=` 从 182 秒一路涨到 1735 秒——车一步没挪。全局 411 次告警对 394 次"已修复"，
    // 这个比例因此是假的，真实的恢复成功率无从得知。
    RuntimeDispatchService.SmartRecoveryInput input =
        smartRecoveryInput("train1", SignalAspect.STOP, true, "self-owned-retain");
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
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 7, SignalAspect.STOP, 0.0)));
    monitor.check(Set.of("train1"), t0);

    // 一：派发了恢复动作，但进度索引仍是 7 ⇒ 只能算"已派发"，不得算"已恢复"。
    TrainHealthMonitor.CheckResult dispatched = monitor.check(Set.of("train1"), t0.plusSeconds(65));
    assertEquals(1, dispatched.recoveryDispatchedCount(), "应记为已派发");
    assertEquals(0, dispatched.fixedCount(), "车没动就不许宣布已修复");

    // 二：再过一轮车仍未推进 ⇒ 依然不许宣布已修复（此前这里会每次都翻成"已修复"）。
    TrainHealthMonitor.CheckResult stillStuck =
        monitor.check(Set.of("train1"), t0.plusSeconds(125));
    assertEquals(0, stillStuck.fixedCount(), "持续卡住期间不得反复宣布已修复");

    // 三：进度索引真的向前了 ⇒ 这时才算恢复，且只算一次。
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 8, SignalAspect.PROCEED, 4.0)));
    TrainHealthMonitor.CheckResult recovered = monitor.check(Set.of("train1"), t0.plusSeconds(185));
    assertEquals(1, recovered.fixedCount(), "车重新推进才算恢复");

    TrainHealthMonitor.CheckResult afterwards =
        monitor.check(Set.of("train1"), t0.plusSeconds(245));
    assertEquals(0, afterwards.fixedCount(), "恢复只应计一次");

    // 判别点：卡着与恢复必须得到相反结果。恒真或恒假的实现会在这里失败。
    assertNotEquals(dispatched.fixedCount() > 0, recovered.fixedCount() > 0, "卡住与恢复必须相反");
  }

  /**
   * 一个永远候选、永远落不了地的动作，不得永久饿死排在它后面的动作。
   *
   * <p>第十九轮实服：WS 被 {@code SURC-WS-LC-4801} 掉头堵死 40 分钟、全线到站归零， 而它的 blocker <b>全部是</b> {@code
   * QUEUE_POSITION}、与 {@code SURC-WS-LH-1927} 正好成环—— 恰好是排在链末的割排队位要解的形态。
   *
   * <p>旧逻辑里 {@code !result.applied()} 是<b>无条件</b> {@code return true}，且不计数；
   * 而“落地了但无效”反而有计数放行机制。于是更弱的失败形式反而享受无限期优先权。
   */
  /**
   * [AB-fable] 第一步动作每次都「落地」、但 effectiveness 只是 legacy 假定（applied ⇒ effective）， 而现场里那份释放下一 tick
   * 就被重新拿回——第二十六轮 SMART_PHYSICAL_EDGE_RETAIN_RELEASED 335 次里 98% 是重复释放同一组资源。链不得因此永远停在第一步。
   */
  @Test
  void assumedEffectiveButRepeatingCandidateMustNotStarveLaterRecoveryActions() {
    RuntimeDispatchService.SmartRecoveryInput input =
        smartRecoveryInput("train1", SignalAspect.STOP, true, "queue-position-inversion");
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.STOP, 0.0)));
    when(dispatchService.smartRecoveryInput(eq("train1"), any(), eq(SignalAspect.STOP)))
        .thenReturn(input);
    // 第一个动作：每次都 applied=true，effectiveness 走 5 参构造器的 legacy 默认（假定有效）。
    when(dispatchService.applySmartSelfOwnedStaleRetainRelease(input))
        .thenReturn(
            new RuntimeDispatchService.SmartRecoveryActionResult(
                true,
                true,
                "SMART_PHYSICAL_EDGE_RETAIN_RELEASED",
                "physical-edge-retain-released:2",
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
    // 车始终没动（state 的 index 恒为 0），同一个动作每次都"成功"。
    for (int i = 1; i <= 6; i++) {
      monitor.check(Set.of("train1"), t0.plusSeconds(65L + i * 30L));
    }

    verify(dispatchService, atLeastOnce()).applySmartQueuePositionYield(input);
    // 留痕必须存在（它进了必留名单），且按 (train, action, conflict, kind, count) 去重：
    // 计数 1、2 各印一次，之后饱和不再印——七次 check 只能有两行。
    long assumedLines =
        debugLogs.stream()
            .filter(
                line ->
                    line.contains("SMART_RECOVERY_SAFE_CANDIDATE_FAILED_COUNT train=train1")
                        && line.contains("failureKind=assumed-effective"))
            .count();
    assertEquals(2L, assumedLines, () -> "实际日志：" + debugLogs);
  }

  @Test
  void neverAppliedCandidateMustNotStarveLaterRecoveryActions() {
    RuntimeDispatchService.SmartRecoveryInput input =
        smartRecoveryInput("train1", SignalAspect.STOP, true, "queue-position-inversion");
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.STOP, 0.0)));
    when(dispatchService.smartRecoveryInput(eq("train1"), any(), eq(SignalAspect.STOP)))
        .thenReturn(input);
    // 第一个动作：永远候选，永远不落地。
    when(dispatchService.applySmartSelfOwnedStaleRetainRelease(input))
        .thenReturn(
            new RuntimeDispatchService.SmartRecoveryActionResult(
                true,
                false,
                "SMART_RELEASE_SELF_OWNED_STALE_RETAIN",
                "candidate-but-never-applied",
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
    // 反复试：每次都是同一个永不落地的候选。
    for (int i = 1; i <= 6; i++) {
      monitor.check(Set.of("train1"), t0.plusSeconds(65L + i * 30L));
    }

    verify(dispatchService, atLeastOnce()).applySmartQueuePositionYield(input);
    assertTrue(
        debugLogs.stream()
            .anyMatch(
                line ->
                    line.contains("SMART_RECOVERY_SAFE_CANDIDATE_FAILED_COUNT")
                        && line.contains("failureKind=not-applied")),
        () -> "“候选但未落地”必须计数，实际日志：" + debugLogs);
  }

  /**
   * 服务器冻结的那段时间不得计入“卡了多久”，但冻结**前**已积累的停滞不得被抄掉。
   *
   * <p>实服第二十一轮：日志在 10:35→10:43 断了七分钟（HikariCP 同时报 thread starvation）。 恢复后的第一个 tick
   * 里，六辆车<b>在同一瞬间</b> {@code 10:43:07} 全部跨过 300 秒 （最大 622s），而它们一分钟后就自己恢复了。那不是死锁，是墙钟在说谎。
   *
   * <p>危险不在诊断：{@code deadlock-threshold-seconds}=45、{@code stuck-cleanup-threshold-seconds}=600，
   * 一次七分钟冻结会让**每一辆停着的车**同时越线——若销毁兜底开着就是大规模删车。
   *
   * <p>两个方向必须一起钉：只钉前者的话，把状态整个清空也能蒙混过关，而那是另一个方向的错。
   */
  @Test
  void freezeRebaseDropsTheFrozenSpanButKeepsRealStallBeforeIt() {
    when(dispatchService.getTrainState("train1"))
        .thenReturn(Optional.of(state("train1", 0, SignalAspect.PROCEED, 0.0)));
    when(dwellRegistry.remainingSeconds("train1")).thenReturn(Optional.empty());
    monitor.setStallThreshold(Duration.ofSeconds(60));
    monitor.setProgressStuckThreshold(Duration.ofSeconds(3000));
    monitor.setDeadlockThreshold(Duration.ofSeconds(3000));

    Instant t0 = Instant.now();
    monitor.check(Set.of("train1"), t0);
    // 冻结前已经真实静止 40 秒（未越过 60 秒阈值）。
    assertEquals(0, monitor.check(Set.of("train1"), t0.plusSeconds(40)).stallCount());

    // 服务器冻结 420 秒。调度层（HealthMonitor.tick）会调这个重基。
    monitor.rebaseAfterFreeze(Duration.ofSeconds(420));

    // 冻结的 420 秒不计数：此刻累计仍然只有 40 秒。
    assertEquals(
        0,
        monitor.check(Set.of("train1"), t0.plusSeconds(40 + 420)).stallCount(),
        "冻结的 420 秒不得被当成静止——否则每辆停着的车都会集体越线");

    // 冻结前那 40 秒必须还在：再过 25 秒（40+25=65 > 60）就该报。
    assertEquals(
        1,
        monitor.check(Set.of("train1"), t0.plusSeconds(40 + 420 + 25)).stallCount(),
        "冻结前已积累的真实静止不得被抄掉");
    assertTrue(
        debugLogs.stream().anyMatch(l -> l.contains("SMART_HEALTH_CLOCK_DISCONTINUITY")),
        "重基必须留痕迹");
  }

  /**
   * 同一 tick 内逐车处理顺序按列车名排序，与调用方集合的顺序无关。
   *
   * <p>此前用 {@code Set.copyOf(activeTrains)}，顺序每个 JVM 随机一次——两车同时满足兜底条件时谁先动手随重启变。六个非字母序的车名让退回 {@code
   * Set.copyOf} 的实现几乎必然失败。
   */
  @Test
  @DisplayName("逐车处理顺序按列车名排序（check 与 forceUnlockNow）")
  void perTrainVisitOrderIsByNameRegardlessOfCallerOrder() {
    List<String> visited = new ArrayList<>();
    when(dispatchService.getTrainState(anyString()))
        .thenAnswer(
            invocation -> {
              visited.add(invocation.getArgument(0));
              return Optional.empty();
            });
    Set<String> callerOrder =
        new java.util.LinkedHashSet<>(List.of("t-5", "t-2", "t-9", "t-1", "t-7", "t-3"));
    List<String> byName = List.of("t-1", "t-2", "t-3", "t-5", "t-7", "t-9");

    monitor.check(callerOrder, Instant.parse("2026-01-01T00:00:00Z"));
    assertEquals(byName, visited.stream().distinct().toList(), "check 的逐车顺序");

    visited.clear();
    monitor.forceUnlockNow(callerOrder, Instant.parse("2026-01-01T00:00:01Z"));
    assertEquals(byName, visited.stream().distinct().toList(), "forceUnlockNow 的逐车顺序");
  }
}
