package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

class SpeedEnvelopeTest {

  @Test
  void emptyEnvelopeImposesNoLimit() {
    SpeedEnvelope envelope = SpeedEnvelope.empty();

    assertTrue(envelope.isEmpty());
    assertEquals(Double.POSITIVE_INFINITY, envelope.limitBps(0.0));
  }

  @Test
  void brakingConstraintFollowsPhysicsCurveAndHoldsEndSpeedPastThePoint() {
    SpeedEnvelope.Constraint constraint = SpeedEnvelope.braking(50.0, 10.0, 1.0);

    assertEquals(Math.sqrt(100.0 + 2.0 * 50.0), constraint.limitBps(0.0), 1.0e-9);
    assertEquals(Math.sqrt(100.0 + 2.0 * 20.0), constraint.limitBps(30.0), 1.0e-9);
    assertEquals(10.0, constraint.limitBps(50.0), 1.0e-9);
    assertEquals(10.0, constraint.limitBps(80.0), 1.0e-9);
  }

  @Test
  void brakingConstraintDropsAtConfiguredDecelerationWhenTrainRidesTheCurve() {
    // 沿曲线行驶时 dv/dt = −a：每 tick 走 v/20 格，一秒后应恰好少 a。
    double decel = 1.0;
    SpeedEnvelope.Constraint constraint = SpeedEnvelope.braking(200.0, 0.0, decel);
    double traveled = 0.0;
    double speed = constraint.limitBps(traveled);
    double start = speed;
    for (int tick = 0; tick < 20; tick++) {
      traveled += speed / 20.0;
      speed = constraint.limitBps(traveled);
    }

    assertEquals(start - decel, speed, 0.01);
  }

  @Test
  void invalidDecelerationFallsBackToEndSpeed() {
    assertEquals(4.0, SpeedEnvelope.braking(100.0, 4.0, 0.0).limitBps(0.0), 1.0e-9);
    assertEquals(4.0, SpeedEnvelope.braking(Double.NaN, 4.0, 1.0).limitBps(0.0), 1.0e-9);
    assertNull(SpeedEnvelope.braking(100.0, Double.NaN, 1.0));
  }

  @Test
  void envelopeTakesTheTightestConstraintAndIgnoresUnevaluableOnes() {
    SpeedEnvelope envelope =
        SpeedEnvelope.empty()
            .with(SpeedEnvelope.braking(100.0, 12.0, 1.0))
            .with(SpeedEnvelope.braking(40.0, 5.0, 1.0))
            .with(traveled -> Double.NaN)
            .with(null);

    assertEquals(3, envelope.size());
    assertEquals(Math.sqrt(25.0 + 80.0), envelope.limitBps(0.0), 1.0e-9);
    assertEquals(5.0, envelope.limitBps(60.0), 1.0e-9);
  }

  @Test
  void withAllKeepsBothSidesAndSharesEmpty() {
    SpeedEnvelope left = SpeedEnvelope.empty().with(SpeedEnvelope.braking(10.0, 3.0, 1.0));
    SpeedEnvelope right = SpeedEnvelope.empty().with(SpeedEnvelope.braking(10.0, 2.0, 1.0));

    assertSame(left, left.withAll(SpeedEnvelope.empty()));
    assertSame(right, SpeedEnvelope.empty().withAll(right));
    assertEquals(2, left.withAll(right).size());
  }

  @Test
  void holdConstraintsAlsoLimitButOnlyTheyFormTheHoldLimit() {
    SpeedEnvelope envelope =
        SpeedEnvelope.empty()
            .with(SpeedEnvelope.braking(0.0, 8.0, 1.0))
            .withHold(SpeedEnvelope.braking(40.0, 10.0, 1.0));

    assertEquals(2, envelope.size());
    assertEquals(8.0, envelope.limitBps(0.0), 1.0e-9);
    assertEquals(Math.sqrt(100.0 + 80.0), envelope.holdLimitBps(0.0), 1.0e-9);
    assertEquals(
        Double.POSITIVE_INFINITY, SpeedEnvelope.empty().with(traveled -> 5.0).holdLimitBps(0.0));
  }

  @Test
  void withAllCarriesHoldConstraintsFromBothSides() {
    SpeedEnvelope left = SpeedEnvelope.empty().with(traveled -> 30.0).withHold(traveled -> 12.0);
    SpeedEnvelope right = SpeedEnvelope.empty().withHold(traveled -> 9.0);

    SpeedEnvelope merged = left.withAll(right);

    assertEquals(3, merged.size());
    assertEquals(9.0, merged.holdLimitBps(0.0), 1.0e-9);
    assertEquals(12.0, left.holdLimitBps(0.0), 1.0e-9);
  }

  @Test
  void edgeSpeedConstraintsBecomeBrakingConstraints() {
    SpeedEnvelope envelope =
        SpeedEnvelope.edgeSpeedConstraints(
            List.of(
                new SignalLookahead.EdgeSpeedConstraint(0L, 20.0),
                new SignalLookahead.EdgeSpeedConstraint(60L, 8.0)),
            1.0);

    assertEquals(Math.sqrt(64.0 + 120.0), envelope.limitBps(0.0), 1.0e-9);
    assertEquals(8.0, envelope.limitBps(70.0), 1.0e-9);
  }

  @Test
  void lookaheadShiftMovesOnlyEdgeSpeedConstraintsAndClampsPassedStarts() {
    SignalLookahead.LookaheadResult lookahead =
        new SignalLookahead.LookaheadResult(
            OptionalLong.of(90L),
            OptionalLong.of(90L),
            OptionalLong.of(70L),
            SignalAspect.PROCEED,
            List.of(
                new SignalLookahead.EdgeSpeedConstraint(0L, 20.0),
                new SignalLookahead.EdgeSpeedConstraint(40L, 8.0)));

    SignalLookahead.LookaheadResult shifted = lookahead.withEdgeSpeedConstraintsShiftedBy(15L);

    assertEquals(0L, shifted.edgeSpeedConstraints().get(0).distanceBlocks());
    assertEquals(25L, shifted.edgeSpeedConstraints().get(1).distanceBlocks());
    assertEquals(8.0, shifted.edgeSpeedConstraints().get(1).speedLimitBps(), 1.0e-9);
    assertEquals(OptionalLong.of(90L), shifted.distanceToBlocker());
    assertEquals(OptionalLong.of(90L), shifted.distanceToCaution());
    assertEquals(OptionalLong.of(70L), shifted.distanceToApproach());
    assertSame(lookahead, lookahead.withEdgeSpeedConstraintsShiftedBy(0L));
  }
}
