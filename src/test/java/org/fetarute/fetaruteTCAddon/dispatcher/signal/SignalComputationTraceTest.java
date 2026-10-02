package org.fetarute.fetaruteTCAddon.dispatcher.signal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

/** 信号诊断 trace 输出门控回归测试。 */
class SignalComputationTraceTest {

  @Test
  void rawTraceSuppressesStableDuplicateIgnoringLifecycleSequenceTickAndVersionFields() {
    List<String> traces = new ArrayList<>();
    SignalComputationTrace.configureLogger(traces::add);

    SignalComputationTrace.emitRaw(
        "SMART_RESOURCE_LIFECYCLE sequence=1 train=MT-1 tick=10 occupancyVersion=1 "
            + "resource=EDGE:A-B relation=HARD_OCCUPANCY reason=held",
        traces::add);
    SignalComputationTrace.emitRaw(
        "SMART_RESOURCE_LIFECYCLE sequence=2 train=MT-1 tick=11 occupancyVersion=2 "
            + "resource=EDGE:A-B relation=HARD_OCCUPANCY reason=held",
        traces::add);

    assertEquals(1, traces.size());
  }

  @Test
  void rawTraceDedupKeepsOnlyRecentKeysSoEvictedTraceEmitsAgain() {
    List<String> traces = new ArrayList<>();
    SignalComputationTrace.configureLogger(traces::add);
    String first = "SMART_PRIORITY_RESOLVED train=MT-0 priority=0";

    SignalComputationTrace.emitRaw(first, traces::add);
    SignalComputationTrace.emitRaw(first, traces::add);
    for (int i = 1; i <= SignalComputationTrace.EMITTED_STABLE_TRACE_LIMIT; i++) {
      SignalComputationTrace.emitRaw("SMART_PRIORITY_RESOLVED train=MT-" + i + " priority=0");
    }
    SignalComputationTrace.emitRaw(first, traces::add);

    assertEquals(SignalComputationTrace.EMITTED_STABLE_TRACE_LIMIT + 2, traces.size());
    assertEquals(first, traces.get(traces.size() - 1));
  }

  @Test
  void rawTraceEmitsWhenStableContentChanges() {
    List<String> traces = new ArrayList<>();
    SignalComputationTrace.configureLogger(traces::add);

    SignalComputationTrace.emitRaw(
        "SMART_PRIORITY_RESOLVED train=MT-1 tick=10 priority=0 fallbackReason=default",
        traces::add);
    SignalComputationTrace.emitRaw(
        "SMART_PRIORITY_RESOLVED train=MT-1 tick=11 priority=10 fallbackReason=manual",
        traces::add);

    assertEquals(2, traces.size());
  }

  @Test
  void repeatedStableSignalTraceEmitsOnce() {
    List<String> traces = new ArrayList<>();
    SignalComputationTrace.configureLogger(traces::add);

    SignalComputationTrace.emit(stableStopTrace(), traces::add);
    SignalComputationTrace.emit(stableStopTrace(), traces::add);

    assertEquals(1, traces.size());
  }

  @Test
  void suppressedPhysicalNoOpSignalTraceIsDropped() {
    List<String> traces = new ArrayList<>();
    SignalComputationTrace.configureLogger(traces::add);

    SignalComputationTrace.emit(
        SignalComputationTrace.builder(
                "MT-1", "MT-1", SignalComputationTrace.Source.PERIODIC_TICK, SignalAspect.STOP)
            .previousAspect(SignalAspect.STOP)
            .primaryReason("already-current-physical-aspect")
            .field("publishSuppressed", true),
        traces::add);

    assertTrue(traces.isEmpty());
  }

  @Test
  void throwingLoggerCannotEscapeStructuredSignalTrace() {
    SignalComputationTrace.configureLogger(null);
    try {
      assertDoesNotThrow(
          () ->
              SignalComputationTrace.emit(
                  stableStopTrace(),
                  message -> {
                    throw new IllegalStateException("diagnostic-sink-failed");
                  }));
    } finally {
      SignalComputationTrace.configureLogger(null);
    }
  }

  private static SignalComputationTrace.Builder stableStopTrace() {
    return SignalComputationTrace.builder(
            "MT-1", "MT-1", SignalComputationTrace.Source.PERIODIC_TICK, SignalAspect.STOP)
        .previousAspect(SignalAspect.STOP)
        .primaryReason("same-blocker")
        .field("publishSuppressed", false)
        .field("blocker", "EDGE:A-B");
  }
}
