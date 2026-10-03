package org.fetarute.fetaruteTCAddon.drive.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PowerSupplyTest {

  @Test
  void parsesKeysCaseInsensitivelyAndCommonAliases() {
    assertEquals(Optional.of(PowerSupply.PTG5), PowerSupply.parse("PTG5"));
    assertEquals(Optional.of(PowerSupply.PTG6), PowerSupply.parse(" ptg6 "));
    assertEquals(Optional.of(PowerSupply.SHOE), PowerSupply.parse("third-rail"));
    assertEquals(Optional.of(PowerSupply.DIESEL), PowerSupply.parse("engine"));
    assertTrue(PowerSupply.parse("steam").isEmpty());
    assertTrue(PowerSupply.parse(null).isEmpty());
  }

  @Test
  void pantographsPlayTheirHeightAnimation() {
    assertEquals(Optional.of("ptg5"), PowerSupply.PTG5.animation());
    assertEquals(Optional.of("ptg6"), PowerSupply.PTG6.animation());
    assertTrue(PowerSupply.SHOE.animation().isEmpty());
  }

  @Test
  void onlyDieselIsNotElectric() {
    assertTrue(PowerSupply.SHOE.electric());
    assertFalse(PowerSupply.DIESEL.electric());
  }

  @Test
  void timingsPickTheStepForTheSupply() {
    SetupTimings timings = SetupTimings.defaults();

    assertEquals(160, timings.ticksOf(SetupSystem.POWER, PowerSupply.PTG6));
    assertEquals(40, timings.ticksOf(SetupSystem.POWER, PowerSupply.SHOE));
    assertEquals(500, timings.ticksOf(SetupSystem.POWER, PowerSupply.DIESEL));
    assertEquals(60, timings.ticksOf(SetupSystem.BREAKER, PowerSupply.PTG5));
  }

  @Test
  void stepNamesFollowTheSupply() {
    assertEquals(
        "drive.setup.step.pantograph", SetupText.stepKey(SetupSystem.POWER, PowerSupply.PTG5));
    assertEquals("drive.setup.step.shoe", SetupText.stepKey(SetupSystem.POWER, PowerSupply.SHOE));
    assertEquals(
        "drive.setup.step.engine", SetupText.stepKey(SetupSystem.POWER, PowerSupply.DIESEL));
    assertEquals("drive.setup.step.aux", SetupText.stepKey(SetupSystem.AUX, PowerSupply.DIESEL));
  }
}
