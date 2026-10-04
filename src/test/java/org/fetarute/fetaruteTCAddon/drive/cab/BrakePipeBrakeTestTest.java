package org.fetarute.fetaruteTCAddon.drive.cab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BrakePipeBrakeTestTest {

  private static final CabConfig CONFIG = CabConfig.defaults();
  private static final double DT = 0.05;

  /** 保压 n 秒：制动管 380、制动缸从 peak 起每秒下降 leakPerSecond。 */
  private static void hold(BrakeTest test, double seconds, double peak, double leakPerSecond) {
    double bc = peak;
    for (int i = 0; i < Math.round(seconds / DT); i++) {
      test.tickBrakePipe(380, bc, 120, true, DT, CONFIG);
      bc -= leakPerSecond * DT;
    }
  }

  @Test
  void chargeReduceHoldAndReleasePasses() {
    BrakeTest test = BrakeTest.forBrakePipe();
    assertTrue(test.start());
    assertEquals(BrakeTest.Stage.CHARGE, test.stage());

    test.tickBrakePipe(300, 0, 0, true, DT, CONFIG);
    assertEquals(BrakeTest.Stage.CHARGE, test.stage(), "制动管没充到定压");
    test.tickBrakePipe(495, 0, 0, true, DT, CONFIG);
    assertEquals(BrakeTest.Stage.APPLY, test.stage());
    assertEquals("reduce", test.stageKey());

    test.tickBrakePipe(450, 117, 120, true, DT, CONFIG);
    assertEquals(BrakeTest.Stage.APPLY, test.stage(), "减压 50 不够 100");
    test.tickBrakePipe(380, 280, 120, true, DT, CONFIG);
    assertEquals(BrakeTest.Stage.HOLD, test.stage());

    hold(test, 8.5, 280, 0.0);
    assertEquals(BrakeTest.Stage.HOLD, test.stage(), "保压不满 10 秒");
    assertEquals(2, test.holdRemainingSeconds(CONFIG.brakePipe()));
    hold(test, 1.6, 280, 0.0);
    assertEquals(BrakeTest.Stage.RELEASE, test.stage());

    test.tickBrakePipe(450, 117, 0, true, DT, CONFIG);
    assertEquals(BrakeTest.Stage.RELEASE, test.stage(), "制动缸还没排空");
    test.tickBrakePipe(495, 10, 0, true, DT, CONFIG);
    assertTrue(test.passed());
  }

  @Test
  void aLeakingBrakeCylinderFailsTheHoldTestAndTheTestCanBeRestarted() {
    BrakeTest test = BrakeTest.forBrakePipe();
    test.start();
    test.tickBrakePipe(500, 0, 0, true, DT, CONFIG);
    test.tickBrakePipe(380, 280, 120, true, DT, CONFIG);

    hold(test, 5.0, 280, 6.0);

    assertEquals(BrakeTest.Stage.FAILED, test.stage(), "每秒漏 6 kPa，两秒内超过 10 kPa");
    assertEquals("failed", test.stageKey());
    assertFalse(test.passed());
    assertTrue(test.start(), "不通过后可以重新开始");
    assertEquals(BrakeTest.Stage.CHARGE, test.stage());
  }

  @Test
  void releasingDuringTheHoldGoesBackToTheReductionStep() {
    BrakeTest test = BrakeTest.forBrakePipe();
    test.start();
    test.tickBrakePipe(500, 0, 0, true, DT, CONFIG);
    test.tickBrakePipe(380, 280, 120, true, DT, CONFIG);
    hold(test, 3.0, 280, 0.0);

    test.tickBrakePipe(382, 275, 0, true, DT, CONFIG);

    assertEquals(BrakeTest.Stage.APPLY, test.stage(), "中途缓解不算漏泄，回到减压一步");
  }

  @Test
  void aRisingPipeDuringTheHoldIsTreatedAsARelease() {
    BrakeTest test = BrakeTest.forBrakePipe();
    test.start();
    test.tickBrakePipe(500, 0, 0, true, DT, CONFIG);
    test.tickBrakePipe(380, 280, 120, true, DT, CONFIG);

    test.tickBrakePipe(390, 260, 120, true, DT, CONFIG);

    assertEquals(BrakeTest.Stage.APPLY, test.stage());
  }

  @Test
  void movingVoidsTheTest() {
    BrakeTest test = BrakeTest.forBrakePipe();
    test.start();
    test.tickBrakePipe(500, 0, 0, true, DT, CONFIG);

    test.tickBrakePipe(500, 0, 0, false, DT, CONFIG);

    assertEquals(BrakeTest.Stage.NOT_DONE, test.stage());
  }
}
