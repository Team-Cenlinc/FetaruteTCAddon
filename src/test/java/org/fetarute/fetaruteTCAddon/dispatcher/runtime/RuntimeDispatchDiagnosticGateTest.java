package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyRequest;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceIntent;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspectPolicy;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SimpleOccupancyManager;
import org.fetarute.fetaruteTCAddon.dispatcher.signal.SignalComputationTrace;
import org.junit.jupiter.api.Test;

/** {@link RuntimeDispatchDiagnosticGate} 的控制台去重测试。 */
class RuntimeDispatchDiagnosticGateTest {

  @Test
  void suppressesPeriodicSignalDiagnosticWhenOnlyTickAndRequestChange() {
    List<String> messages = new ArrayList<>();
    AtomicLong nowNanos = new AtomicLong();
    RuntimeDispatchDiagnosticGate gate =
        new RuntimeDispatchDiagnosticGate(messages::add, Duration.ofSeconds(5), 32, nowNanos::get);

    gate.accept(
        "SMART_SIGNAL_FINAL train=MT-1 tick=100 requestId=request-a finalAspect=PROCEED "
            + "authorityTokenState=ACTIVE");
    nowNanos.addAndGet(Duration.ofMillis(50).toNanos());
    gate.accept(
        "SMART_SIGNAL_FINAL train=MT-1 tick=101 requestId=request-b finalAspect=PROCEED "
            + "authorityTokenState=ACTIVE");

    assertEquals(1, messages.size());
  }

  @Test
  void suppressesStableInputEdgeWhenOnlyObservedAgeChanges() {
    List<String> messages = new ArrayList<>();
    AtomicLong nowNanos = new AtomicLong();
    RuntimeDispatchDiagnosticGate gate =
        new RuntimeDispatchDiagnosticGate(messages::add, Duration.ofSeconds(5), 32, nowNanos::get);

    gate.accept(
        "SMART_DISPATCH_INPUT_EDGE blockedTrain=MT-1 blockerTrain=MT-2 resource=NODE:A "
            + "relation=HARD_OCCUPANCY ageMs=100 activeForNormalAdmission=true");
    nowNanos.addAndGet(Duration.ofMillis(50).toNanos());
    gate.accept(
        "SMART_DISPATCH_INPUT_EDGE blockedTrain=MT-1 blockerTrain=MT-2 resource=NODE:A "
            + "relation=HARD_OCCUPANCY ageMs=150 activeForNormalAdmission=true");

    assertEquals(1, messages.size());
  }

  @Test
  void suppressesAnyStableObservationDiagnosticInsteadOfMaintainingAPrefixAllowlist() {
    List<String> messages = new ArrayList<>();
    AtomicLong nowNanos = new AtomicLong();
    RuntimeDispatchDiagnosticGate gate =
        new RuntimeDispatchDiagnosticGate(messages::add, Duration.ofSeconds(5), 32, nowNanos::get);

    gate.accept(
        "SIGNAL_CAUTION_REASON train=MT-1 tick=100 requestId=request-a "
            + "reason=same-direction-follow-trace-only");
    nowNanos.addAndGet(Duration.ofMillis(50).toNanos());
    gate.accept(
        "SIGNAL_CAUTION_REASON train=MT-1 tick=101 requestId=request-b "
            + "reason=same-direction-follow-trace-only");

    assertEquals(1, messages.size());
  }

  @Test
  void emitsImmediatelyWhenTheSignalStateChanges() {
    List<String> messages = new ArrayList<>();
    AtomicLong nowNanos = new AtomicLong();
    RuntimeDispatchDiagnosticGate gate =
        new RuntimeDispatchDiagnosticGate(messages::add, Duration.ofSeconds(5), 32, nowNanos::get);

    gate.accept(
        "SMART_SIGNAL_FINAL train=MT-1 tick=100 requestId=request-a finalAspect=PROCEED "
            + "authorityTokenState=ACTIVE");
    gate.accept(
        "SMART_SIGNAL_FINAL train=MT-1 tick=101 requestId=request-b finalAspect=STOP "
            + "authorityTokenState=INVALID");

    assertEquals(2, messages.size());
  }

  @Test
  void emitsTheSameDiagnosticAgainAfterTheRepeatWindow() {
    List<String> messages = new ArrayList<>();
    AtomicLong nowNanos = new AtomicLong();
    RuntimeDispatchDiagnosticGate gate =
        new RuntimeDispatchDiagnosticGate(messages::add, Duration.ofSeconds(5), 32, nowNanos::get);

    gate.accept("SMART_RESOURCE_SNAPSHOT train=MT-1 sequence=1 tick=100 occupancyVersion=1");
    nowNanos.addAndGet(Duration.ofSeconds(5).toNanos());
    gate.accept("SMART_RESOURCE_SNAPSHOT train=MT-1 sequence=2 tick=200 occupancyVersion=2");

    assertEquals(2, messages.size());
  }

  @Test
  void boundsAllObservationDiagnosticsAndSummarizesSuppressedEntriesAfterBudgetRecovers() {
    List<String> messages = new ArrayList<>();
    AtomicLong nowNanos = new AtomicLong();
    RuntimeDispatchDiagnosticGate gate =
        new RuntimeDispatchDiagnosticGate(
            messages::add, Duration.ofSeconds(5), 32, Duration.ofSeconds(60), 2, nowNanos::get);

    gate.accept("SIGNAL_CAUTION_REASON train=MT-1 reason=first");
    gate.accept("SIGNAL_CAUTION_REASON train=MT-2 reason=second");
    gate.accept("SIGNAL_CAUTION_REASON train=MT-3 reason=suppressed");

    assertEquals(2, messages.size());

    nowNanos.addAndGet(Duration.ofSeconds(60).toNanos());
    gate.accept("SIGNAL_CAUTION_REASON train=MT-4 reason=after-window");

    assertEquals(4, messages.size());
    assertEquals(
        "SMART_DISPATCH_DIAGNOSTICS_SUPPRESSED count=1 repeat=0 budgetDrop=1 "
            + "repeatKinds=[] budgetKinds=[SIGNAL_CAUTION_REASON(reason=suppressed):1] "
            + "budget=2 windowSeconds=60",
        messages.get(2));
    assertEquals("SIGNAL_CAUTION_REASON train=MT-4 reason=after-window", messages.get(3));
  }

  @Test
  void separatesRepeatedAndBudgetSuppressionByDiagnosticCause() {
    List<String> messages = new ArrayList<>();
    AtomicLong nowNanos = new AtomicLong();
    RuntimeDispatchDiagnosticGate gate =
        new RuntimeDispatchDiagnosticGate(
            messages::add, Duration.ofSeconds(5), 32, Duration.ofSeconds(60), 1, nowNanos::get);

    gate.accept("SIGNAL_CAUTION_REASON train=MT-1 reason=same-blocker");
    nowNanos.addAndGet(Duration.ofMillis(1).toNanos());
    gate.accept("SIGNAL_CAUTION_REASON train=MT-1 reason=same-blocker");
    nowNanos.addAndGet(Duration.ofMillis(1).toNanos());
    gate.accept("SMART_LIVE_BLOCKER_SNAPSHOT_REJECTED train=MT-2 reason=STALE_PROGRESS_CONTEXT");

    nowNanos.addAndGet(Duration.ofSeconds(60).toNanos());
    gate.accept("SIGNAL_CAUTION_REASON train=MT-3 reason=after-window");

    assertEquals(
        "SMART_DISPATCH_DIAGNOSTICS_SUPPRESSED count=2 repeat=1 budgetDrop=1 "
            + "repeatKinds=[SIGNAL_CAUTION_REASON(reason=same-blocker):1] "
            + "budgetKinds=[SMART_LIVE_BLOCKER_SNAPSHOT_REJECTED(reason=STALE_PROGRESS_CONTEXT):1] "
            + "budget=1 windowSeconds=60",
        messages.get(1));
  }

  @Test
  void preservesArrivalAndStopLifecycleBoundariesAfterObservationBudgetIsExhausted() {
    List<String> messages = new ArrayList<>();
    RuntimeDispatchDiagnosticGate gate =
        new RuntimeDispatchDiagnosticGate(
            messages::add, Duration.ofSeconds(5), 32, Duration.ofSeconds(60), 1, () -> 0L);
    String observation = "SIGNAL_CAUTION_REASON train=MT-1 reason=budget-filler";
    String arrival = "SMART_ROUTE_ARRIVAL train=MT-1 index=13 arrivedNode=OP:PPK:RVS:1:001";
    String entered = "SMART_STOP_LIFECYCLE event=enter train=MT-1 reasonCode=AUTHORIZATION_FAILURE";
    String transitioned =
        "SMART_STOP_LIFECYCLE event=transition train=MT-1 reasonCode=HARD_BLOCKER_STOP";
    String cleared = "SMART_STOP_LIFECYCLE event=clear train=MT-1 clearReason=authority-active";

    gate.accept(observation);
    gate.accept(arrival);
    gate.accept(entered);
    gate.accept(transitioned);
    gate.accept(cleared);
    gate.accept(entered);
    gate.accept("SIGNAL_CAUTION_REASON train=MT-2 reason=still-budgeted");

    assertEquals(List.of(observation, arrival, entered, transitioned, cleared, entered), messages);
  }

  @Test
  void preservesActualClaimChangesAfterBudgetExhaustionWithoutRepeatingStableRefreshes() {
    List<String> messages = new ArrayList<>();
    RuntimeDispatchDiagnosticGate gate =
        new RuntimeDispatchDiagnosticGate(
            messages::add, Duration.ofSeconds(5), 32, Duration.ofSeconds(60), 1, () -> 0L);
    gate.accept("SIGNAL_CAUTION_REASON train=MT-1 reason=budget-filler");
    SignalComputationTrace.configureLogger(gate);
    try {
      SimpleOccupancyManager occupancy =
          new SimpleOccupancyManager(
              (route, resource) -> Duration.ZERO, SignalAspectPolicy.defaultPolicy());
      OccupancyResource node = OccupancyResource.forNode(NodeId.of("OP:S:PPK:1"));
      Instant now = Instant.parse("2026-09-05T00:00:00Z");
      OccupancyRequest retain =
          new OccupancyRequest("MT-1", Optional.empty(), now, List.of(node), Map.of())
              .withResourceIntents(Map.of(node, ResourceIntent.PROTECTIVE_RETAIN));
      OccupancyRequest movement =
          new OccupancyRequest(
              "MT-1", Optional.empty(), now.plusSeconds(1), List.of(node), Map.of());

      assertTrue(occupancy.acquire(retain).allowed());
      assertTrue(occupancy.acquire(movement).allowed());
      assertTrue(occupancy.acquire(movement).allowed());
      assertEquals(1, occupancy.releaseByTrain("MT-1"));
      gate.accept("SMART_RESOURCE_SNAPSHOT train=MT-1 reason=still-budgeted");

      List<String> lifecycle =
          messages.stream()
              .filter(message -> message.startsWith("SMART_RESOURCE_LIFECYCLE "))
              .toList();
      assertEquals(3, lifecycle.size(), messages::toString);
      assertTrue(lifecycle.get(0).contains("event=acquire"));
      assertTrue(lifecycle.get(1).contains("event=merge"));
      assertTrue(lifecycle.get(1).contains("oldRole=PROTECTIVE_RETAIN newRole=MOVEMENT_REQUIRED"));
      assertTrue(lifecycle.get(2).contains("event=release"));
      assertTrue(
          messages.stream().noneMatch(message -> message.startsWith("SMART_RESOURCE_SNAPSHOT ")));
    } finally {
      SignalComputationTrace.configureLogger(null);
    }
  }

  @Test
  void preservesRepeatedExecutorAudits() {
    List<String> messages = new ArrayList<>();
    RuntimeDispatchDiagnosticGate gate =
        new RuntimeDispatchDiagnosticGate(
            messages::add, Duration.ofSeconds(5), 32, Duration.ofSeconds(60), 1, () -> 0L);

    gate.accept(
        "SMART_DISPATCH_EXECUTOR_SKIPPED train=MT-1 planId=plan-a reason=NO_RELEASE_COOLDOWN");
    gate.accept(
        "SMART_DISPATCH_EXECUTOR_SKIPPED train=MT-1 planId=plan-a reason=NO_RELEASE_COOLDOWN");

    assertEquals(2, messages.size());
  }

  @Test
  void preservesUnlockTransactionAuditsAfterObservationBudgetIsExhausted() {
    List<String> messages = new ArrayList<>();
    RuntimeDispatchDiagnosticGate gate =
        new RuntimeDispatchDiagnosticGate(
            messages::add, Duration.ofSeconds(5), 32, Duration.ofSeconds(60), 1, () -> 0L);

    String ordinaryObservation = "SIGNAL_CAUTION_REASON train=MT-1 reason=budget-filler";
    String reservationCreated =
        "SMART_UNLOCK_RESERVATION_CREATED reservationId=unlock-1 train=MT-1";
    String reevaluationRequested =
        "SMART_UNLOCK_AUTHORITY_REEVALUATION_REQUESTED reservationId=unlock-1 train=MT-1";
    String planApply =
        "SMART_UNLOCK_PLAN_APPLY reservationId=unlock-1 applyPhase=reevaluation-requested";
    String rollbackStarted = "SMART_UNLOCK_ROLLBACK_STARTED reservationId=unlock-1 train=MT-1";
    String rollbackDone = "SMART_UNLOCK_ROLLBACK_DONE reservationId=unlock-1 train=MT-1";

    gate.accept(ordinaryObservation);
    gate.accept(reservationCreated);
    gate.accept(reevaluationRequested);
    gate.accept(planApply);
    gate.accept(rollbackStarted);
    gate.accept(rollbackDone);

    assertEquals(
        List.of(
            ordinaryObservation,
            reservationCreated,
            reevaluationRequested,
            rollbackStarted,
            rollbackDone),
        messages);
  }
}
