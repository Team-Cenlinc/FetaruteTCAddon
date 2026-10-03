package org.fetarute.fetaruteTCAddon.drive.dynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class NotchTest {

  @Test
  void nineNotchesMapOneToOneOntoHotbarSlots() {
    Notch[] expected = {
      Notch.P3, Notch.P2, Notch.P1, Notch.N, Notch.B1, Notch.B2, Notch.B3, Notch.B4, Notch.EB
    };
    assertEquals(Notch.SLOT_COUNT, expected.length);
    for (int slot = 0; slot < expected.length; slot++) {
      assertEquals(Optional.of(expected[slot]), Notch.fromSlot(slot));
      assertEquals(slot, expected[slot].slot());
    }
  }

  @Test
  void slotsOutsideTheHotbarHaveNoNotch() {
    assertTrue(Notch.fromSlot(-1).isEmpty());
    assertTrue(Notch.fromSlot(9).isEmpty());
  }

  @Test
  void kindsAndStepsFollowTheHandleLayout() {
    assertTrue(Notch.P1.isTraction());
    assertEquals(3, Notch.P3.step());
    assertEquals(1, Notch.P1.step());
    assertEquals(Notch.Kind.COAST, Notch.N.kind());
    assertEquals(4, Notch.B4.step());
    assertTrue(Notch.B1.isBrake());
    assertTrue(Notch.EB.isBrake());
    assertEquals(Notch.Kind.EMERGENCY, Notch.EB.kind());
  }
}
