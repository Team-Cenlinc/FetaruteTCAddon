package org.fetarute.fetaruteTCAddon.drive.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PowerSupplyTest {

  @Test
  void parsesKeysCaseInsensitivelyAndCommonAliases() {
    assertEquals(Optional.of(PowerSupply.PTG5), PowerSupply.parse("PTG5"));
    assertEquals(Optional.of(PowerSupply.PTG6), PowerSupply.parse(" ptg6 "));
    assertEquals(Optional.of(PowerSupply.SHOE), PowerSupply.parse("third-rail"));
    assertEquals(Optional.of(PowerSupply.DIESEL), PowerSupply.parse("engine"));
    assertEquals(Optional.of(PowerSupply.SUPERCAP), PowerSupply.parse("SuperCap"));
    assertEquals(Optional.of(PowerSupply.SUPERCAP), PowerSupply.parse("super-capacitor"));
    assertTrue(PowerSupply.parse("steam").isEmpty());
    assertTrue(PowerSupply.parse(null).isEmpty());
  }

  /** 多模车的受电方式列表：逗号、加号、斜杠或空白分隔，按写的顺序去重；有一项认不出就整个认不出。 */
  @Test
  void parsesMultiModeLists() {
    assertEquals(
        Optional.of(List.of(PowerSupply.PTG6, PowerSupply.DIESEL)),
        PowerSupply.parseList("ptg6, diesel"));
    assertEquals(
        Optional.of(List.of(PowerSupply.PTG5, PowerSupply.SUPERCAP)),
        PowerSupply.parseList("ptg5+supercap"));
    assertEquals(
        Optional.of(List.of(PowerSupply.SHOE, PowerSupply.PTG5)),
        PowerSupply.parseList("third-rail/ptg5 shoe"));
    assertEquals(Optional.of(List.of(PowerSupply.PTG5)), PowerSupply.parseList("ptg5"));
    assertTrue(PowerSupply.parseList("ptg5,steam").isEmpty());
    assertTrue(PowerSupply.parseList(" ").isEmpty());
    assertEquals("ptg6,diesel", PowerSupply.format(List.of(PowerSupply.PTG6, PowerSupply.DIESEL)));
  }

  /** 内燃、超级电容不靠接触网或第三轨，开进哪种供电区段都能走。 */
  @Test
  void onlyDieselAndSupercapAreSelfPowered() {
    assertTrue(PowerSupply.DIESEL.selfPowered());
    assertTrue(PowerSupply.SUPERCAP.selfPowered());
    assertFalse(PowerSupply.PTG5.selfPowered());
    assertFalse(PowerSupply.SHOE.selfPowered());
  }

  @Test
  void pantographsPlayTheirHeightAnimation() {
    assertEquals(Optional.of("ptg5"), PowerSupply.PTG5.animation());
    assertEquals(Optional.of("ptg6"), PowerSupply.PTG6.animation());
    assertTrue(PowerSupply.SHOE.animation().isEmpty());
    assertTrue(PowerSupply.SUPERCAP.animation().isEmpty(), "超级电容投入时不升弓，充电时才试着升弓");
  }

  @Test
  void onlySupercapStoresEnergy() {
    assertTrue(PowerSupply.SUPERCAP.storesEnergy());
    assertTrue(PowerSupply.SUPERCAP.electric());
    assertFalse(PowerSupply.SHOE.storesEnergy());
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
    assertEquals(40, timings.ticksOf(SetupSystem.POWER, PowerSupply.SUPERCAP));
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
    assertEquals(
        "drive.setup.step.supercap", SetupText.stepKey(SetupSystem.POWER, PowerSupply.SUPERCAP));
  }
}
