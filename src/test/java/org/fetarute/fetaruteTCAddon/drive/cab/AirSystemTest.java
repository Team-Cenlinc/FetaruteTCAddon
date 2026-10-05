package org.fetarute.fetaruteTCAddon.drive.cab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AirSystemTest {

  private static final CabConfig CONFIG = CabConfig.defaults();

  private static void run(AirSystem air, double seconds, boolean aux, double demand) {
    for (double t = 0; t < seconds; t += 0.05) {
      air.tick(0.05, aux, demand);
    }
  }

  @Test
  void aColdAutomaticCompressorFillsTheReservoirOnceAuxPowerIsOn() {
    AirSystem air = new AirSystem(CONFIG, false, 0, false);

    run(air, 10, false, 0);
    assertEquals(0.0, air.mainReservoirKpa(), "没有辅助电源压缩机不转");

    run(air, 45, true, 0);
    assertEquals(450.0, air.mainReservoirKpa(), 2.0, "90 秒充满 900，45 秒约一半");
    assertTrue(air.compressorRunning());

    run(air, 60, true, 0);
    assertEquals(900.0, air.mainReservoirKpa(), 1e-9);
    assertFalse(air.compressorRunning(), "到满压停机");
  }

  @Test
  void theCompressorRestartsOnlyBelowTheCutInPressure() {
    AirSystem air = new AirSystem(CONFIG, false, 800, false);

    run(air, 1, true, 0);
    assertFalse(air.compressorRunning(), "800 高于启动压力 750，不启动");

    AirSystem low = new AirSystem(CONFIG, false, 740, false);
    run(low, 0.1, true, 0);
    assertTrue(low.compressorRunning());
  }

  @Test
  void aManualCompressorNeedsItsSwitch() {
    AirSystem air = new AirSystem(CONFIG, true, 0, false);

    run(air, 5, true, 0);
    assertEquals(0.0, air.mainReservoirKpa());

    assertTrue(air.toggleCompressor());
    run(air, 5, true, 0);
    assertTrue(air.mainReservoirKpa() > 0);
  }

  @Test
  void applyingTheBrakeConsumesReservoirAir() {
    AirSystem air = new AirSystem(CONFIG, false, 900, false);

    air.tick(0.05, false, 1.0);

    assertEquals(350.0, air.brakeCylinderKpa(), 1e-9);
    assertEquals(900 - 350 * 0.1, air.mainReservoirKpa(), 1e-9);

    air.tick(0.05, false, 1.0);
    assertEquals(900 - 350 * 0.1, air.mainReservoirKpa(), 1e-9, "保压不再耗气");

    air.tick(0.05, false, 0.0);
    assertEquals(0.0, air.brakeCylinderKpa());
  }

  @Test
  void lowReservoirWeakensTheBrakeAndLocksTraction() {
    AirSystem full = new AirSystem(CONFIG, false, 900, false);
    AirSystem low = new AirSystem(CONFIG, false, 225, false);

    assertEquals(1.0, full.brakeScale());
    assertFalse(full.tractionLocked());
    assertEquals(0.5, low.brakeScale(), 1e-9);
    assertTrue(low.tractionLocked());
  }

  @Test
  void theParkingBrakeStartsAppliedAndNeedsAirToRelease() {
    AirSystem low = new AirSystem(CONFIG, false, 300, false);
    assertTrue(low.parkingApplied());
    assertEquals(AirSystem.ParkingResult.NOT_ENOUGH_AIR, low.toggleParking());
    assertTrue(low.parkingApplied());

    AirSystem charged = new AirSystem(CONFIG, false, 600, false);
    assertEquals(AirSystem.ParkingResult.RELEASED, charged.toggleParking());
    assertFalse(charged.parkingApplied());
    assertEquals(AirSystem.ParkingResult.APPLIED, charged.toggleParking());
  }

  @Test
  void losingAirAppliesTheParkingBrakeByItself() {
    AirSystem air = new AirSystem(CONFIG, true, 600, false);
    air.toggleParking();
    assertFalse(air.parkingApplied());

    // 压缩机开关关着，反复制动把主风缸用到自动施加压力（300）以下。
    for (int i = 0; i < 20 && !air.parkingApplied(); i++) {
      air.tick(0.05, true, 1.0);
      air.tick(0.05, true, 0.0);
    }

    assertTrue(air.parkingApplied());
    assertTrue(air.takeParkingAutoApplied());
    assertFalse(air.takeParkingAutoApplied(), "记号取出后清除");
  }

  @Test
  void aParkingBrakeThatWasAlreadyAppliedIsNotReportedAsAutoApplied() {
    AirSystem air = new AirSystem(CONFIG, false, 0, false);

    air.tick(0.05, false, 0);

    assertTrue(air.parkingApplied());
    assertFalse(air.takeParkingAutoApplied());
  }
}
