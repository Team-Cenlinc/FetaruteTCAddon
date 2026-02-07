package org.fetarute.fetaruteTCAddon.dispatcher.runtime.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy.SignalAspect;
import org.junit.jupiter.api.Test;

class MovementAuthorityServiceTest {

  private final MovementAuthorityService service = new MovementAuthorityService();

  @Test
  void evaluateReturnsRequestedSignalWhenConstraintMissing() {
    MovementAuthorityService.MovementAuthorityDecision decision =
        service.evaluate(
            new MovementAuthorityService.MovementAuthorityInput(
                SignalAspect.PROCEED, 8.0, 1.0, OptionalLong.empty(), 2.0, 8.0));

    assertEquals(SignalAspect.PROCEED, decision.effectiveAspect());
    assertFalse(decision.restricted());
  }

  @Test
  void evaluateDowngradesToStopWhenAuthorityIsInsufficient() {
    MovementAuthorityService.MovementAuthorityDecision decision =
        service.evaluate(
            new MovementAuthorityService.MovementAuthorityInput(
                SignalAspect.PROCEED, 10.0, 1.0, OptionalLong.of(20L), 2.0, 8.0));

    assertEquals(SignalAspect.STOP, decision.effectiveAspect());
    assertTrue(decision.restricted());
    assertTrue(decision.recommendedMaxSpeedBps().isPresent());
  }

  @Test
  void evaluateDowngradesOneStepWhenWithinCautionMargin() {
    MovementAuthorityService.MovementAuthorityDecision decision =
        service.evaluate(
            new MovementAuthorityService.MovementAuthorityInput(
                SignalAspect.PROCEED, 6.0, 1.0, OptionalLong.of(24L), 2.0, 8.0));

    assertEquals(SignalAspect.PROCEED_WITH_CAUTION, decision.effectiveAspect());
    assertTrue(decision.restricted());
  }
}
