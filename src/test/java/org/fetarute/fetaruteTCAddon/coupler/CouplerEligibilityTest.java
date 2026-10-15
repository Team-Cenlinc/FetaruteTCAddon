package org.fetarute.fetaruteTCAddon.coupler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.fetarute.fetaruteTCAddon.coupler.CouplerEligibility.Facts;
import org.fetarute.fetaruteTCAddon.coupler.CouplerEligibility.Verdict;
import org.junit.jupiter.api.Test;

/** 扳手只动不在运营中的受管列车。 */
class CouplerEligibilityTest {

  private static Facts standby() {
    return new Facts(false, false, false, false, false, true, false, false, false, false, false);
  }

  @Test
  void aQuietStandbyTrainOrAFinishedReturnTripMayBeWorked() {
    assertEquals(Verdict.ALLOWED, CouplerEligibility.judge(standby()));
    assertEquals(
        Verdict.ALLOWED,
        CouplerEligibility.judge(
            new Facts(false, false, false, false, false, false, false, false, false, false, true)));
  }

  @Test
  void trainsInServiceOrBusyAreRefusedWithTheReason() {
    assertEquals(
        Verdict.IN_SERVICE,
        CouplerEligibility.judge(
            new Facts(
                false, false, false, false, false, false, false, false, false, false, false)));
    assertEquals(
        Verdict.MOVING,
        CouplerEligibility.judge(
            new Facts(true, false, false, false, false, true, false, false, false, false, false)));
    assertEquals(
        Verdict.DRIVER,
        CouplerEligibility.judge(
            new Facts(false, true, false, false, false, true, false, false, false, false, false)));
    assertEquals(
        Verdict.GUARD,
        CouplerEligibility.judge(
            new Facts(false, false, true, false, false, true, false, false, false, false, false)));
    assertEquals(
        Verdict.DWELLING,
        CouplerEligibility.judge(
            new Facts(false, false, false, true, false, true, false, false, false, false, false)));
    assertEquals(
        Verdict.DEPARTURE_GATE,
        CouplerEligibility.judge(
            new Facts(false, false, false, false, true, true, false, false, false, false, false)));
    assertEquals(
        Verdict.DISPATCHING,
        CouplerEligibility.judge(
            new Facts(false, false, false, false, false, true, true, false, false, false, false)));
    assertEquals(
        Verdict.CALLED,
        CouplerEligibility.judge(
            new Facts(false, false, false, false, false, true, false, true, false, false, false)));
    assertEquals(
        Verdict.EARLY_HOLD,
        CouplerEligibility.judge(
            new Facts(false, false, false, false, true, false, false, false, true, false, false)),
        "车库里等提前出车的车也挂着门控：报更具体的原因");
    assertEquals(
        Verdict.TIMETABLE_DUTY,
        CouplerEligibility.judge(
            new Facts(false, false, false, false, false, true, false, false, false, true, false)));
  }
}
