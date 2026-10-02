package org.fetarute.fetaruteTCAddon.dispatcher.runtime.supervisor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class RouteUnlockPotentialPreviewTest {

  @Test
  void routeUnlockPreviewLogsACanDrainButDoesNotMutate() {
    assertEquals(
        RouteUnlockPotentialDecision.A_CAN_DRAIN_TO_UNLOCK_B,
        RouteUnlockPotentialPreview.evaluate(snapshot(true, false, true, true, false, 0L)));
  }

  @Test
  void routeUnlockPreviewLogsBCanDrainButDoesNotMutate() {
    assertEquals(
        RouteUnlockPotentialDecision.B_CAN_DRAIN_TO_UNLOCK_A,
        RouteUnlockPotentialPreview.evaluate(snapshot(false, true, true, true, false, 0L)));
  }

  @Test
  void routeUnlockPreviewLogsNeitherCanDrain() {
    assertEquals(
        RouteUnlockPotentialDecision.NEITHER_CAN_DRAIN,
        RouteUnlockPotentialPreview.evaluate(snapshot(false, false, true, true, false, 0L)));
  }

  @Test
  void routeUnlockPreviewLogsUnsafeOppositeSingleRegion() {
    RouteUnlockPotentialSnapshot snapshot =
        new RouteUnlockPotentialSnapshot(
            "A",
            "B",
            "single:test:A~B",
            true,
            "OPPOSITE_DIRECTION",
            "CONFLICT:single:test:A~B",
            "CONFLICT:single:test:A~B",
            true,
            true,
            true,
            true,
            List.of("CONFLICT:single:test:A~B"),
            List.of("CONFLICT:single:test:A~B"),
            List.of("CONFLICT:single:test:A~B"),
            List.of("CONFLICT:single:test:A~B"),
            true,
            true,
            true,
            true,
            true,
            0L,
            1L,
            1L);

    assertEquals(
        RouteUnlockPotentialDecision.UNSAFE_OPPOSITE_SINGLE_REGION,
        RouteUnlockPotentialPreview.evaluate(snapshot));
  }

  @Test
  void routeUnlockPreviewUnknownWhenRouteMissing() {
    assertEquals(
        RouteUnlockPotentialDecision.UNKNOWN_FAIL_SAFE,
        RouteUnlockPotentialPreview.evaluate(snapshot(true, false, false, true, false, 0L)));
  }

  @Test
  void routeUnlockPreviewUnknownWhenSnapshotStale() {
    assertEquals(
        RouteUnlockPotentialDecision.UNKNOWN_FAIL_SAFE,
        RouteUnlockPotentialPreview.evaluate(snapshot(true, false, true, true, false, -1L)));
  }

  @Test
  void routeUnlockPreviewDoesNotChangeAdmissionOutcome() {
    assertEquals(
        RouteUnlockPotentialDecision.A_CAN_DRAIN_TO_UNLOCK_B,
        RouteUnlockPotentialPreview.evaluate(snapshot(true, false, true, true, false, 0L)));
  }

  @Test
  void routeUnlockPreviewDoesNotChangeSignalOutcome() {
    assertEquals(
        RouteUnlockPotentialDecision.NEITHER_CAN_DRAIN,
        RouteUnlockPotentialPreview.evaluate(snapshot(false, false, true, true, false, 0L)));
  }

  @Test
  void routeUnlockPreviewDoesNotIssueToken() {
    assertEquals(
        RouteUnlockPotentialDecision.B_CAN_DRAIN_TO_UNLOCK_A,
        RouteUnlockPotentialPreview.evaluate(snapshot(false, true, true, true, false, 0L)));
  }

  private static RouteUnlockPotentialSnapshot snapshot(
      boolean aWouldDrain,
      boolean bWouldDrain,
      boolean routePresent,
      boolean destinationPresent,
      boolean externalOpposite,
      long snapshotAgeMs) {
    return new RouteUnlockPotentialSnapshot(
        "A",
        "B",
        "single:test:A~B",
        true,
        "SAME_DIRECTION",
        "CONFLICT:single:test:A~B",
        "CONFLICT:single:test:A~B",
        destinationPresent,
        destinationPresent,
        routePresent,
        routePresent,
        List.of("CONFLICT:single:test:A~B"),
        List.of("CONFLICT:single:test:A~B"),
        List.of("CONFLICT:single:test:A~B"),
        List.of("CONFLICT:single:test:A~B"),
        aWouldDrain,
        bWouldDrain,
        aWouldDrain,
        bWouldDrain,
        externalOpposite,
        snapshotAgeMs,
        1L,
        1L);
  }
}
