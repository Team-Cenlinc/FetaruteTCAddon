package org.fetarute.fetaruteTCAddon.drive.cab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class CabSystemsTest {

  @Test
  void theStandardLevelBlocksNothing() {
    CabSystems cab = CabSystems.disabled();

    assertTrue(cab.tractionBlock().isEmpty());
    assertEquals(1.0, cab.brakeScale());
    assertEquals(Vigilance.Event.NONE, cab.tick(10_000, 0.05, false, 0, false, true));
  }

  @Test
  void simulationNeedsParkingReleaseAirAndABrakeTestInThatOrder() {
    CabSystems cab = CabSystems.simulation(CabConfig.defaults(), false, 600, false, 0);

    assertEquals(Optional.of(CabSystems.TractionBlock.PARKING_BRAKE), cab.tractionBlock());
    cab.air().toggleParking();
    assertEquals(Optional.of(CabSystems.TractionBlock.BRAKE_TEST), cab.tractionBlock());

    cab.brakeTest().start();
    cab.tick(1, 0.05, true, 1.0, true, true);
    cab.tick(2, 0.05, true, 0.0, true, true);

    assertTrue(cab.tractionBlock().isEmpty());
  }

  @Test
  void lowAirBlocksTractionEvenWithTheParkingBrakeReleased() {
    CabSystems cab = CabSystems.simulation(CabConfig.defaults(), true, 500, false, 0);
    cab.air().toggleParking();

    assertEquals(Optional.of(CabSystems.TractionBlock.LOW_AIR), cab.tractionBlock());
  }

  @Test
  void anUnattendedCabDoesNotCountTowardVigilance() {
    CabSystems cab = CabSystems.simulation(CabConfig.defaults(), false, 900, false, 0);

    for (long t = 1; t < 5000; t++) {
      assertEquals(Vigilance.Event.NONE, cab.tick(t, 0.05, true, 0, false, false));
    }
  }

  @Test
  void brakeTestProgressIsReportedOncePerChange() {
    CabSystems cab = CabSystems.simulation(CabConfig.defaults(), false, 900, false, 0);
    cab.brakeTest().start();

    cab.tick(1, 0.05, true, 1.0, true, true);
    assertEquals(Optional.of(BrakeTest.Stage.RELEASE), cab.takeBrakeTestChange());
    assertTrue(cab.takeBrakeTestChange().isEmpty());

    cab.tick(2, 0.05, true, 0.0, true, true);
    assertEquals(Optional.of(BrakeTest.Stage.PASSED), cab.takeBrakeTestChange());
  }
}
