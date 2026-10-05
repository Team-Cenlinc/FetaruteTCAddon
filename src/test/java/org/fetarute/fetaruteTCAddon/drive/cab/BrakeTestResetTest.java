package org.fetarute.fetaruteTCAddon.drive.cab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("换端后作废制动试验")
class BrakeTestResetTest {

  @Test
  @DisplayName("作废后须重做，牵引被封锁")
  void resetRequiresANewTest() {
    CabSystems cab = CabSystems.hotHandover(CabConfig.defaults(), false, 0L);
    assertTrue(cab.brakeTest().passed());
    assertTrue(cab.tractionBlock().isEmpty());

    cab.brakeTest().reset();

    assertFalse(cab.brakeTest().passed());
    assertEquals(BrakeTest.Stage.NOT_DONE, cab.brakeTest().stage());
    assertEquals(CabSystems.TractionBlock.BRAKE_TEST, cab.tractionBlock().orElseThrow());
    assertTrue(cab.brakeTest().start(), "作废后可以重新开始试验");
  }
}
