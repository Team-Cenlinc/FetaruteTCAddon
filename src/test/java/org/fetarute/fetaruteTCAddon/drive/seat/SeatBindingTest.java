package org.fetarute.fetaruteTCAddon.drive.seat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SeatBindingTest {

  @Test
  void cabsInTheFrontHalfFaceForwardAndTheRearHalfFaceBackward() {
    assertEquals(1, new SeatBinding("t", 0, 0).cabSign(6));
    assertEquals(1, new SeatBinding("t", 2, 0).cabSign(6));
    assertEquals(-1, new SeatBinding("t", 3, 0).cabSign(6));
    assertEquals(-1, new SeatBinding("t", 5, 0).cabSign(6));
  }

  @Test
  void theMiddleOfAnOddTrainCountsAsFront() {
    assertEquals(1, new SeatBinding("t", 2, 0).cabSign(5));
    assertEquals(-1, new SeatBinding("t", 3, 0).cabSign(5));
  }

  @Test
  void aSingleCarHasOnlyAForwardCab() {
    assertEquals(1, new SeatBinding("t", 0, 0).cabSign(1));
    assertEquals(1, new SeatBinding("t", 0, 0).cabSign(0));
  }

  @Test
  void rejectsNegativeIndexes() {
    assertThrows(IllegalArgumentException.class, () -> new SeatBinding("t", -1, 0));
    assertThrows(IllegalArgumentException.class, () -> new SeatBinding("t", 0, -1));
  }
}
