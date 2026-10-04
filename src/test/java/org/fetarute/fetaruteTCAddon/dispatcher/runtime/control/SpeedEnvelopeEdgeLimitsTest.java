package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.eta.model.SpeedCurve;
import org.junit.jupiter.api.Test;

class SpeedEnvelopeEdgeLimitsTest {

  private static final List<SignalLookahead.EdgeSpeedConstraint> EDGES =
      List.of(
          new SignalLookahead.EdgeSpeedConstraint(40L, 8.0),
          new SignalLookahead.EdgeSpeedConstraint(120L, 12.0));

  @Test
  void emptyEnvelopeHasNoEdgeLimits() {
    assertTrue(SpeedEnvelope.empty().edgeLimits().isEmpty());
  }

  @Test
  void edgeSpeedConstraintsExposeTheirRawEdges() {
    SpeedEnvelope envelope =
        SpeedEnvelope.edgeSpeedConstraints(EDGES, new SpeedCurve(1.0, 1.0), 20.0);

    assertEquals(EDGES, envelope.edgeLimits());
  }

  @Test
  void manualViewKeepsTheEdgesAfterDroppingTheApproachHold() {
    SpeedEnvelope envelope =
        SpeedEnvelope.empty()
            .withApproachHold(traveled -> 6.0, 20.0)
            .withAll(SpeedEnvelope.edgeSpeedConstraints(EDGES, new SpeedCurve(1.0, 1.0), 6.0));

    SpeedEnvelope.ManualView view = envelope.manual(6.0);

    assertEquals(EDGES, view.envelope().edgeLimits());
    // 进站限速已去掉：再取人工驾驶视图原样返回，不会重复重建。
    assertSame(view.envelope(), view.envelope().manual(view.targetBps()).envelope());
  }

  @Test
  void manualViewLimitsAreUnchangedByKeepingTheEdges() {
    SpeedCurve curve = new SpeedCurve(1.0, 1.0);
    SpeedEnvelope envelope =
        SpeedEnvelope.empty()
            .withApproachHold(traveled -> 6.0, 20.0)
            .withAll(SpeedEnvelope.edgeSpeedConstraints(EDGES, curve, 6.0));
    SpeedEnvelope expected = SpeedEnvelope.edgeSpeedConstraints(EDGES, curve, 20.0);

    SpeedEnvelope rebuilt = envelope.manual(6.0).envelope();

    for (double traveled = 0.0; traveled <= 150.0; traveled += 7.5) {
      assertEquals(expected.limitBps(traveled), rebuilt.limitBps(traveled), 1.0e-9);
    }
  }
}
