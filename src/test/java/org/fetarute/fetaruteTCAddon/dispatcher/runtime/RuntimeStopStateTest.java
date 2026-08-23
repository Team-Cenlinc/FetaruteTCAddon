package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

/** 结构化 STOP 生命周期的解除条件与重评契约测试。 */
class RuntimeStopStateTest {

  @Test
  void publicationGateLocalStopRequiresDrainAuthorityAndLeaderRevalidation() {
    Instant now = Instant.parse("2026-07-28T00:00:00Z");
    OccupancyDecision decision =
        new OccupancyDecision(
            true, now, SignalAspect.PROCEED, List.of(), false, "occupancy-allowed");

    RuntimeStopState state =
        RuntimeStopState.publicationGateHold(
            "train-1", "drain-authority-without-leader", decision, now);

    assertEquals("PUBLICATION_GATE_LOCAL_STOP", state.reasonCode());
    assertEquals("drain-authority-without-leader", state.detail());
    assertEquals(
        RuntimeStopState.ReleaseCondition.DRAIN_AUTHORITY_AND_LEADER_REVALIDATED,
        state.releaseCondition());
    assertEquals(
        RuntimeStopState.RetryTrigger.OCCUPANCY_OR_PROGRESS_CHANGE_OR_PERIODIC_RECHECK,
        state.retryTrigger());
    assertFalse(state.invalidatesAuthority());
  }

  @Test
  void missingStopContextIsAnExplicitFailClosedLifecycle() {
    Instant now = Instant.parse("2026-07-28T00:00:00Z");

    RuntimeStopState state = RuntimeStopState.stopContextMissing("train-1", now);

    assertEquals("STOP_CONTEXT_MISSING", state.reasonCode());
    assertEquals("stop-published-without-specific-context", state.detail());
    assertEquals(
        RuntimeStopState.ReleaseCondition.SAFETY_STATE_RESTORED_AND_AUTHORITY_REISSUED,
        state.releaseCondition());
    assertEquals(
        RuntimeStopState.RetryTrigger.GRAPH_ROUTE_OR_PROGRESS_REFRESH, state.retryTrigger());
    assertTrue(state.invalidatesAuthority());
  }

  @Test
  void abnormalPhysicalQuarantineWaitsForFieldReconstructionCommit() {
    Instant now = Instant.parse("2026-07-28T00:00:00Z");

    RuntimeStopState state =
        RuntimeStopState.abnormalPhysicalQuarantine("train-1", "unexpected-split-source", now);

    assertEquals("ABNORMAL_PHYSICAL_QUARANTINE", state.reasonCode());
    assertEquals("unexpected-split-source", state.detail());
    assertEquals(
        RuntimeStopState.ReleaseCondition.FIELD_RECONSTRUCTION_COMMITTED, state.releaseCondition());
    assertEquals(RuntimeStopState.RetryTrigger.STARTUP_RECONSTRUCTION_RETRY, state.retryTrigger());
    assertTrue(state.invalidatesAuthority());
  }
}
