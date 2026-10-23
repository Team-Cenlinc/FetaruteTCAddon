package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DoorCars;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("停站中途接管时站台把开着的车门交给驾驶员的条件")
class AutoStationDoorHandOverTest {

  @Test
  @DisplayName("人工驾驶接管、站台已开门且没开始关门、还没放行、本站要开门：交接")
  void handsOverOpenDoorsToAManualDriver() {
    assertTrue(AutoStationSignAction.shouldHandOverDoors(false, false, true, false, true, true));
  }

  @Test
  @DisplayName("其余情形不交接：已由驾驶员开关门、已放行、还没开门、已开始关门、本站不开门、ATO")
  void otherwiseTheStationKeepsTheDoors() {
    assertFalse(AutoStationSignAction.shouldHandOverDoors(true, false, true, false, true, true));
    assertFalse(AutoStationSignAction.shouldHandOverDoors(false, true, true, false, true, true));
    assertFalse(AutoStationSignAction.shouldHandOverDoors(false, false, false, false, true, true));
    assertFalse(AutoStationSignAction.shouldHandOverDoors(false, false, true, true, true, true));
    assertFalse(AutoStationSignAction.shouldHandOverDoors(false, false, true, false, false, true));
    assertFalse(AutoStationSignAction.shouldHandOverDoors(false, false, true, false, true, false));
  }

  @Test
  @DisplayName("交给驾驶员或车掌的门记成开着，门会话也记成开过门：之后关门照常播关门动画，不会门开着却记成已关")
  void adoptedDoorsCloseLikeOpenedOnes() {
    AutoStationDoorController.ManualDoor door =
        AutoStationDoorController.manualDoor(
            null, new AutoStationDoorController.ManualDoorSide(true, "test"), null, DoorCars.ALL);
    assertFalse(door.session().openSucceeded());

    door.markOpenedByStation();

    assertTrue(door.isOpen());
    assertTrue(door.session().openSucceeded(), "关门只对开过门的会话播动画");
    door.close();
    assertFalse(door.isOpen());
  }
}
