package org.fetarute.fetaruteTCAddon.drive.dynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MotorRatioTest {

  @Test
  void parsesMotorTrailerCounts() {
    assertEquals(4.0 / 6.0, MotorRatio.parse("4M2T").getAsDouble(), 1e-12);
    assertEquals(0.5, MotorRatio.parse(" 3m3t ").getAsDouble(), 1e-12);
    assertEquals(1.0, MotorRatio.parse("6M0T").getAsDouble(), 1e-12);
  }

  @Test
  void parsesADirectFraction() {
    assertEquals(0.67, MotorRatio.parse("0.67").getAsDouble(), 1e-12);
    assertEquals(1.0, MotorRatio.parse("1").getAsDouble(), 1e-12);
  }

  @Test
  void rejectsUnusableValues() {
    assertTrue(MotorRatio.parse(null).isEmpty());
    assertTrue(MotorRatio.parse("").isEmpty());
    assertTrue(MotorRatio.parse("0M6T").isEmpty());
    assertTrue(MotorRatio.parse("0M0T").isEmpty());
    assertTrue(MotorRatio.parse("1.5").isEmpty());
    assertTrue(MotorRatio.parse("0").isEmpty());
    assertTrue(MotorRatio.parse("-0.2").isEmpty());
    assertTrue(MotorRatio.parse("abc").isEmpty());
    assertTrue(MotorRatio.parse("NaN").isEmpty());
  }
}
