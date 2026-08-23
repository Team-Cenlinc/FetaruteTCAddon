package org.fetarute.fetaruteTCAddon.dispatcher.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PhysicalRailFootprintPolicyTest {

  @Test
  void longCartWithCenteredWheelsKeepsItsHalfBodyAndBoundaryPadding() {
    assertEquals(
        6.0,
        PhysicalRailFootprintPolicy.requiredEndPaddingBlocks(10.0, 0.0, 0.0).orElseThrow(),
        1.0e-12);
  }

  @Test
  void wheelsAtBodyEndsStillKeepOneBlockBoundaryPadding() {
    assertEquals(
        1.0,
        PhysicalRailFootprintPolicy.requiredEndPaddingBlocks(10.0, 5.0, 5.0).orElseThrow(),
        1.0e-12);
  }

  @Test
  void longCartCenterWalkCoversHalfBodyAndBoundaryPadding() {
    assertEquals(
        6.0,
        PhysicalRailFootprintPolicy.requiredCenterWalkDistanceBlocks(10.0).orElseThrow(),
        1.0e-12);
  }

  @Test
  void inconsistentOrNonFinitePhysicalModelFailsClosed() {
    assertTrue(PhysicalRailFootprintPolicy.requiredEndPaddingBlocks(2.0, 1.1, 0.0).isEmpty());
    assertTrue(
        PhysicalRailFootprintPolicy.requiredEndPaddingBlocks(Double.NaN, 0.0, 0.0).isEmpty());
    assertTrue(
        PhysicalRailFootprintPolicy.requiredCenterWalkDistanceBlocks(Double.POSITIVE_INFINITY)
            .isEmpty());
  }
}
