package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
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

    gate.accept("SMART_RESOURCE_LIFECYCLE train=MT-1 sequence=1 tick=100 occupancyVersion=1");
    nowNanos.addAndGet(Duration.ofSeconds(5).toNanos());
    gate.accept("SMART_RESOURCE_LIFECYCLE train=MT-1 sequence=2 tick=200 occupancyVersion=2");

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
}
