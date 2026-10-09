package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.fetarute.fetaruteTCAddon.dispatcher.sign.action.AutoStationSignAction.CrewDeparture;
import org.junit.jupiter.api.Test;

/** 驾驶员或车掌开关门的停站，等发车那一步依次过出站门控、车掌的发车信号、ATO 驾驶员的确认发车。 */
class AutoStationCrewDepartureTest {

  /** 出站不放行时也问车掌（他据此暂停计时），但照样扣着。 */
  @Test
  void theGuardIsToldWhenTheExitIsClosed() {
    List<Boolean> asked = new ArrayList<>();
    CrewDeparture step =
        AutoStationSignAction.crewDeparture(
            false,
            true,
            exitOpen -> {
              asked.add(exitOpen);
              return false;
            },
            false,
            () -> false);
    assertEquals(CrewDeparture.HOLD, step);
    assertEquals(List.of(false), asked);
  }

  @Test
  void theGuardHoldsUntilTheSignal() {
    assertEquals(
        CrewDeparture.HOLD,
        AutoStationSignAction.crewDeparture(true, true, exitOpen -> true, false, () -> false));
    assertEquals(
        CrewDeparture.RELEASE_AUTOMATIC,
        AutoStationSignAction.crewDeparture(true, true, exitOpen -> false, false, () -> false));
  }

  /** ATO 驾驶员只在车掌放行之后才问，提示与计时从那时开始。 */
  @Test
  void theAtoDriverIsAskedOnlyAfterTheGuard() {
    AtomicBoolean atoAsked = new AtomicBoolean();
    assertEquals(
        CrewDeparture.HOLD,
        AutoStationSignAction.crewDeparture(
            true,
            true,
            exitOpen -> true,
            false,
            () -> {
              atoAsked.set(true);
              return true;
            }));
    assertFalse(atoAsked.get());
    assertEquals(
        CrewDeparture.HOLD,
        AutoStationSignAction.crewDeparture(true, true, exitOpen -> false, false, () -> true));
  }

  /** 人工驾驶：车掌放行后放出出站许可，等驾驶员起步；没有车掌时照旧。 */
  @Test
  void aManualDriverStartsAfterTheGuard() {
    assertEquals(
        CrewDeparture.RELEASE_TO_DRIVER,
        AutoStationSignAction.crewDeparture(true, true, exitOpen -> false, true, () -> true));
    assertEquals(
        CrewDeparture.RELEASE_TO_DRIVER,
        AutoStationSignAction.crewDeparture(true, false, exitOpen -> true, true, () -> true));
    assertEquals(
        CrewDeparture.HOLD,
        AutoStationSignAction.crewDeparture(false, false, exitOpen -> false, true, () -> false));
    assertTrue(true);
  }
}
