package org.fetarute.fetaruteTCAddon.drive.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.fetarute.fetaruteTCAddon.drive.setup.PowerSupply;
import org.junit.jupiter.api.Test;

class MenuLayoutTest {

  private static final PowerSupply PTG5 = PowerSupply.PTG5;

  @Test
  void theReverserTakesTheFirstThreeSlotsAndTheDoorsTheFifthAndSixth() {
    assertEquals(Optional.of(MenuAction.REVERSER_FORWARD), MenuLayout.actionAt(0));
    assertEquals(Optional.of(MenuAction.REVERSER_NEUTRAL), MenuLayout.actionAt(1));
    assertEquals(Optional.of(MenuAction.REVERSER_REVERSE), MenuLayout.actionAt(2));
    assertEquals(Optional.of(MenuAction.DOOR_LEFT), MenuLayout.actionAt(4));
    assertEquals(Optional.of(MenuAction.DOOR_RIGHT), MenuLayout.actionAt(5));
  }

  @Test
  void emptySlotsAndSlotsOutsideTheMenuHaveNoAction() {
    assertTrue(MenuLayout.actionAt(3).isEmpty());
    assertTrue(MenuLayout.actionAt(6).isEmpty());
    assertTrue(MenuLayout.actionAt(-1).isEmpty());
    assertTrue(MenuLayout.actionAt(MenuLayout.SIZE).isEmpty());
  }

  @Test
  void everyActionHasItsOwnSlotInsideTheMenu() {
    Set<Integer> slots = new HashSet<>();
    for (MenuAction action : MenuAction.values()) {
      int slot = MenuLayout.slotOf(action);
      assertTrue(slot >= 0 && slot < MenuLayout.SIZE, action + " 的槽位 " + slot);
      assertTrue(slots.add(slot), action + " 的槽位与别的按钮重复");
      assertEquals(Optional.of(action), MenuLayout.actionAt(slot));
    }
  }

  @Test
  void reverserButtonsUseTheSelectedTextureWhileTheyAreSelected() {
    assertEquals("panel/reverser_f", MenuLayout.modelKey(MenuAction.REVERSER_FORWARD, false, PTG5));
    assertEquals(
        "panel/reverser_f_sel", MenuLayout.modelKey(MenuAction.REVERSER_FORWARD, true, PTG5));
    assertEquals(
        "panel/reverser_n_sel", MenuLayout.modelKey(MenuAction.REVERSER_NEUTRAL, true, PTG5));
    assertEquals("panel/reverser_r", MenuLayout.modelKey(MenuAction.REVERSER_REVERSE, false, PTG5));
  }

  @Test
  void doorButtonsSwitchBetweenOnAndOff() {
    assertEquals("panel/door_l_on", MenuLayout.modelKey(MenuAction.DOOR_LEFT, true, PTG5));
    assertEquals("panel/door_l_off", MenuLayout.modelKey(MenuAction.DOOR_LEFT, false, PTG5));
    assertEquals("panel/door_r_on", MenuLayout.modelKey(MenuAction.DOOR_RIGHT, true, PTG5));
    assertEquals("panel/door_r_off", MenuLayout.modelKey(MenuAction.DOOR_RIGHT, false, PTG5));
  }

  @Test
  void aSlotLookupForNoActionFailsInsteadOfGuessing() {
    assertThrows(IllegalArgumentException.class, () -> MenuLayout.slotOf(null));
  }

  @Test
  void theSecondRowHoldsTheStartUpSwitchesAndTheStartButton() {
    assertEquals(Optional.of(MenuAction.KEY), MenuLayout.actionAt(9));
    assertEquals(Optional.of(MenuAction.POWER), MenuLayout.actionAt(10));
    assertEquals(Optional.of(MenuAction.BREAKER), MenuLayout.actionAt(11));
    assertEquals(Optional.of(MenuAction.AUX), MenuLayout.actionAt(12));
    assertEquals(Optional.of(MenuAction.START), MenuLayout.actionAt(17));
  }

  @Test
  void thePowerSwitchTextureFollowsTheSupply() {
    assertEquals("panel/ptg5_on", MenuLayout.modelKey(MenuAction.POWER, true, PowerSupply.PTG5));
    assertEquals("panel/ptg6_off", MenuLayout.modelKey(MenuAction.POWER, false, PowerSupply.PTG6));
    assertEquals("panel/shoe_on", MenuLayout.modelKey(MenuAction.POWER, true, PowerSupply.SHOE));
    assertEquals(
        "panel/engine_off", MenuLayout.modelKey(MenuAction.POWER, false, PowerSupply.DIESEL));
    assertEquals("panel/start_on", MenuLayout.modelKey(MenuAction.START, true, PTG5));
    assertEquals("panel/aux_off", MenuLayout.modelKey(MenuAction.AUX, false, PTG5));
  }

  @Test
  void simulationButtonsFollowTheStartUpSwitches() {
    assertEquals(Optional.of(MenuAction.COMPRESSOR), MenuLayout.actionAt(13));
    assertEquals(Optional.of(MenuAction.PARKING_BRAKE), MenuLayout.actionAt(14));
    assertEquals(Optional.of(MenuAction.BRAKE_TEST), MenuLayout.actionAt(15));
    assertEquals("panel/release_on", MenuLayout.modelKey(MenuAction.PARKING_BRAKE, true, PTG5));
    assertEquals("panel/test_off", MenuLayout.modelKey(MenuAction.BRAKE_TEST, false, PTG5));
    assertEquals("panel/compressor_on", MenuLayout.modelKey(MenuAction.COMPRESSOR, true, PTG5));
  }

  @Test
  void theThirdRowStartsWithTheTaskCardAndTheFirstRowEndsWithModeAndEnd() {
    assertEquals(27, MenuLayout.SIZE);
    assertEquals(Optional.of(MenuAction.DRIVING_MODE), MenuLayout.actionAt(7));
    assertEquals(Optional.of(MenuAction.END_DRIVING), MenuLayout.actionAt(8));
    assertEquals(Optional.of(MenuAction.TASK_CARD), MenuLayout.actionAt(18));
  }

  @Test
  void dividersNeverCoverAButton() {
    for (int slot : MenuLayout.dividers()) {
      assertTrue(slot >= 0 && slot < MenuLayout.SIZE, "玻璃板槽位 " + slot);
      assertTrue(MenuLayout.actionAt(slot).isEmpty(), "玻璃板盖住了按钮: " + slot);
      assertTrue(MenuLayout.isDivider(slot));
    }
  }

  @Test
  void newButtonsHaveTheirOwnTextures() {
    assertEquals("panel/mode_manual", MenuLayout.modelKey(MenuAction.DRIVING_MODE, false, PTG5));
    assertEquals("panel/mode_ato", MenuLayout.modelKey(MenuAction.DRIVING_MODE, true, PTG5));
    assertEquals("panel/end", MenuLayout.modelKey(MenuAction.END_DRIVING, false, PTG5));
    assertEquals("panel/end_confirm", MenuLayout.modelKey(MenuAction.END_DRIVING, true, PTG5));
    assertEquals("panel/task_card", MenuLayout.modelKey(MenuAction.TASK_CARD, false, PTG5));
  }
}
