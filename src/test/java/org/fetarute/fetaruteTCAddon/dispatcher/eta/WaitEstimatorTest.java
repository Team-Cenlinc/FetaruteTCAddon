package org.fetarute.fetaruteTCAddon.dispatcher.eta;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.WaitEstimator;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.CorridorDirection;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.HeadwayRule;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyDecision;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueEntry;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyQueueSnapshot;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.OccupancyResource;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.ResourceKind;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

public class WaitEstimatorTest {

  @Test
  void estimate_allowed_returnsZero() {
    WaitEstimator estimator = new WaitEstimator(HeadwayRule.fixed(Duration.ofSeconds(10)), 30);
    OccupancyDecision decision =
        new OccupancyDecision(true, Instant.now(), SignalAspect.PROCEED, List.of());

    WaitEstimator.WaitEstimate est =
        estimator.estimate("T1", decision, Optional.empty(), Instant.now());

    assertEquals(0, est.waitSec());
    assertTrue(est.reasons().isEmpty());
  }

  @Test
  void estimate_blocked_usesQueuePosAndHeadwayPlusPenalty() {
    HeadwayRule headwayRule = HeadwayRule.fixed(Duration.ofSeconds(15));
    WaitEstimator estimator = new WaitEstimator(headwayRule, 30);

    OccupancyResource res = new OccupancyResource(ResourceKind.CONFLICT, "single:test");
    OccupancyQueueSnapshot snapshot =
        new OccupancyQueueSnapshot(
            res,
            Optional.empty(),
            0,
            List.of(
                new OccupancyQueueEntry(
                    "A",
                    CorridorDirection.A_TO_B,
                    Instant.EPOCH,
                    Instant.EPOCH,
                    0,
                    Integer.MAX_VALUE),
                new OccupancyQueueEntry(
                    "T1",
                    CorridorDirection.A_TO_B,
                    Instant.EPOCH,
                    Instant.EPOCH,
                    0,
                    Integer.MAX_VALUE),
                new OccupancyQueueEntry(
                    "C",
                    CorridorDirection.A_TO_B,
                    Instant.EPOCH,
                    Instant.EPOCH,
                    0,
                    Integer.MAX_VALUE)));

    OccupancyDecision decision =
        new OccupancyDecision(false, Instant.now(), SignalAspect.STOP, List.of());

    WaitEstimator.WaitEstimate est =
        estimator.estimate("T1", decision, Optional.of(snapshot), Instant.now());

    // queuePos=1 => 1*15 + penalty(30)
    assertEquals(45, est.waitSec());
    assertTrue(est.reasons().contains(EtaReason.WAIT));
  }

  @Test
  void estimate_blocked_earliestTime_isLowerBoundOnly() {
    HeadwayRule headwayRule = HeadwayRule.fixed(Duration.ofSeconds(5));
    WaitEstimator estimator = new WaitEstimator(headwayRule, 0);

    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    Instant earliest = now.plusSeconds(120);

    OccupancyDecision decision =
        new OccupancyDecision(false, earliest, SignalAspect.STOP, List.of());

    WaitEstimator.WaitEstimate est = estimator.estimate("T1", decision, Optional.empty(), now);

    // queuePos 默认 0 => 0，但 lower bound 120s 生效
    assertEquals(120, est.waitSec());
  }
}
