package org.fetarute.fetaruteTCAddon.drive.cab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BrakeTestTest {

  private static final CabConfig CONFIG = CabConfig.defaults();

  @Test
  void passesAfterApplyingAndThenReleasing() {
    BrakeTest test = new BrakeTest();
    assertTrue(test.start());

    test.tick(100, true, CONFIG);
    assertEquals(BrakeTest.Stage.APPLY, test.stage(), "压力不够不算施加");
    test.tick(320, true, CONFIG);
    assertEquals(BrakeTest.Stage.RELEASE, test.stage());
    test.tick(150, true, CONFIG);
    assertEquals(BrakeTest.Stage.RELEASE, test.stage());
    test.tick(10, true, CONFIG);

    assertTrue(test.passed());
    assertFalse(test.start(), "通过后不再重开");
  }

  @Test
  void movingDuringTheTestVoidsIt() {
    BrakeTest test = new BrakeTest();
    test.start();
    test.tick(320, true, CONFIG);

    test.tick(0, false, CONFIG);

    assertEquals(BrakeTest.Stage.NOT_DONE, test.stage());
    assertTrue(test.start());
  }

  @Test
  void nothingHappensBeforeTheTestIsStarted() {
    BrakeTest test = new BrakeTest();

    assertFalse(test.tick(400, true, CONFIG));
    assertEquals(BrakeTest.Stage.NOT_DONE, test.stage());
  }
}
