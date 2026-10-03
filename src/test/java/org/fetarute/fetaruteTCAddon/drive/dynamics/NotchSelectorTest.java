package org.fetarute.fetaruteTCAddon.drive.dynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NotchSelectorTest {

  @Test
  void startsAtCoastAndFollowsTheSelectedSlot() {
    NotchSelector selector = new NotchSelector();
    assertEquals(Notch.N, selector.current());

    NotchSelector.Selection selection = selector.select(0, true);

    assertEquals(Notch.P3, selection.notch());
    assertEquals(0, selection.correctedSlot());
    assertTrue(selection.changed());
    assertEquals(Notch.P3, selector.current());
  }

  @Test
  void selectingTheCurrentSlotChangesNothing() {
    NotchSelector selector = new NotchSelector();

    NotchSelector.Selection selection = selector.select(Notch.N.slot(), true);

    assertFalse(selection.changed());
    assertEquals(Notch.N, selection.notch());
  }

  @Test
  void slotsOutsideTheHotbarKeepTheCurrentNotch() {
    NotchSelector selector = new NotchSelector();
    selector.select(Notch.B2.slot(), true);

    NotchSelector.Selection selection = selector.select(9, true);

    assertFalse(selection.changed());
    assertEquals(Notch.B2, selection.notch());
    assertEquals(Notch.B2.slot(), selection.correctedSlot());
  }

  @Test
  void emergencyBrakeNeverJumpsStraightToTraction() {
    NotchSelector selector = new NotchSelector();
    selector.force(Notch.EB);

    NotchSelector.Selection selection = selector.select(Notch.P3.slot(), true);

    assertEquals(Notch.N, selection.notch());
    assertEquals(Notch.N.slot(), selection.correctedSlot());
    assertTrue(selection.changed());
  }

  @Test
  void emergencyBrakeStaysAppliedWhileMovingUnderStandardLevel() {
    NotchSelector selector = new NotchSelector();
    selector.force(Notch.EB);

    for (Notch requested : new Notch[] {Notch.P3, Notch.N, Notch.B1}) {
      NotchSelector.Selection selection = selector.select(requested.slot(), false);
      assertEquals(Notch.EB, selection.notch(), "选 " + requested + " 时仍应保持紧急制动");
      assertEquals(Notch.EB.slot(), selection.correctedSlot());
      assertFalse(selection.changed());
    }
  }

  @Test
  void emergencyBrakeReleasesToBrakeOrCoastOnceStopped() {
    NotchSelector selector = new NotchSelector();
    selector.force(Notch.EB);

    assertEquals(Notch.B2, selector.select(Notch.B2.slot(), true).notch());

    selector.force(Notch.EB);
    assertEquals(Notch.N, selector.select(Notch.N.slot(), true).notch());
  }

  @Test
  void nonEmergencyNotchesSwitchFreely() {
    NotchSelector selector = new NotchSelector();
    selector.select(Notch.P3.slot(), false);

    assertEquals(Notch.B4, selector.select(Notch.B4.slot(), false).notch());
    assertEquals(Notch.EB, selector.select(Notch.EB.slot(), false).notch());
  }
}
