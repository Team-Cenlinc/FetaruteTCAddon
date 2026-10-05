package org.fetarute.fetaruteTCAddon.drive.cab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BrakePipeTest {

  private static final BrakePipeConfig CONFIG = BrakePipeConfig.defaults();
  private static final double DT = 0.05;

  private static double run(
      BrakePipe pipe, double seconds, double ratio, boolean venting, double mr) {
    double consumed = 0.0;
    for (int i = 0; i < Math.round(seconds / DT); i++) {
      consumed += pipe.tick(DT, ratio, venting, mr);
    }
    return consumed;
  }

  private static double secondsToCharge(int cars) {
    BrakePipe pipe = new BrakePipe(CONFIG, cars, 0);
    int ticks = 0;
    while (pipe.pressureKpa() < CONFIG.nominalKpa() - 10 && ticks < 100_000) {
      pipe.tick(DT, 0.0, false, 900);
      ticks++;
    }
    return ticks * DT;
  }

  @Test
  void chargingTakesLongerTheMoreCarsThereAre() {
    double six = secondsToCharge(6);
    double twelve = secondsToCharge(12);

    assertEquals(34.0 * 0.98, six, 0.2, "10 秒 + 6 节 × 4 秒，充到定压减 10 kPa");
    assertEquals(58.0 * 0.98, twelve, 0.2);
    assertTrue(twelve > six);
  }

  @Test
  void aServiceApplicationReducesThePipeAtTheServiceRate() {
    BrakePipe pipe = new BrakePipe(CONFIG, 6, 500);

    run(pipe, 1.0, 1.0, false, 900);
    assertEquals(460.0, pipe.pressureKpa(), 1e-6, "每秒减压 40 kPa");

    run(pipe, 5.0, 1.0, false, 900);
    assertEquals(350.0, pipe.pressureKpa(), 1e-6, "常用全制动减压 150 kPa 后保压");
    assertEquals(150.0, pipe.reductionKpa(), 1e-6);
  }

  @Test
  void theCylinderPressureIsProportionalToTheReduction() {
    BrakePipe half = new BrakePipe(CONFIG, 6, 500);
    run(half, 5.0, 0.5, false, 900);

    assertEquals(425.0, half.pressureKpa(), 1e-6);
    assertEquals(175.0, half.cylinderTargetKpa(350), 1e-6, "减压一半，制动缸半压");

    BrakePipe full = new BrakePipe(CONFIG, 6, 500);
    run(full, 5.0, 1.0, false, 900);
    assertEquals(350.0, full.cylinderTargetKpa(350), 1e-6);
  }

  @Test
  void releasingRechargesThePipeAndCostsMainReservoirAir() {
    BrakePipe pipe = new BrakePipe(CONFIG, 6, 350);

    double consumed = run(pipe, 20.0, 0.0, false, 900);

    assertEquals(500.0, pipe.pressureKpa(), 1e-6);
    assertEquals(150.0 * 0.04 * 6, consumed, 1e-6, "充 150 kPa，每节每 kPa 耗 0.04");
  }

  @Test
  void thePipeNeverChargesAboveTheMainReservoir() {
    BrakePipe pipe = new BrakePipe(CONFIG, 6, 0);

    run(pipe, 60.0, 0.0, false, 300);

    assertEquals(300.0, pipe.pressureKpa(), 1e-6);
    assertTrue(pipe.belowFullService(), "300 低于常用全制动后的 350");
  }

  @Test
  void anEmergencyVentEmptiesThePipeWithoutCountingAsALoss() {
    BrakePipe pipe = new BrakePipe(CONFIG, 6, 500);

    run(pipe, 2.0, 0.0, true, 900);

    assertEquals(0.0, pipe.pressureKpa(), 1e-6);
    assertFalse(pipe.takeLoss(), "驾驶员拉紧急制动不算失压");
  }

  @Test
  void losingMainReservoirAirDragsThePipeDownAndTripsTheEmergencyBrakeOnce() {
    BrakePipe pipe = new BrakePipe(CONFIG, 6, 500);

    run(pipe, 10.0, 0.0, false, 200);

    assertEquals(200.0, pipe.pressureKpa(), 1e-6);
    assertTrue(pipe.takeLoss(), "跌破 250 kPa 自动紧急制动");
    assertFalse(pipe.takeLoss(), "取走后清除");

    run(pipe, 10.0, 0.0, false, 200);
    assertFalse(pipe.takeLoss(), "一直在阈值以下不重复触发");
  }

  @Test
  void rechargingFromEmptyDoesNotTripTheEmergencyBrake() {
    BrakePipe pipe = new BrakePipe(CONFIG, 6, 0);

    run(pipe, 60.0, 0.0, false, 900);

    assertEquals(500.0, pipe.pressureKpa(), 1e-6);
    assertFalse(pipe.takeLoss());
  }

  @Test
  void inconsistentConfigurationsAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new BrakePipeConfig(500, 150, 10, 4, 40, 360, 0.04, 100, 10, 10));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BrakePipeConfig(500, 150, 10, 4, 40, 250, 0.04, 200, 10, 10));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BrakePipeConfig(500, 600, 10, 4, 40, 250, 0.04, 100, 10, 10));
  }
}
