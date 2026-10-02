package org.fetarute.fetaruteTCAddon.dispatcher.schedule.occupancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class PhysicalInterlockingBerthPolicyTest {

  @Test
  void combinesRoundedUpTrainLengthWithStopClearance() {
    assertEquals(
        OptionalLong.of(23L),
        PhysicalInterlockingBerthPolicy.requiredExitBerthBlocks(OptionalDouble.of(10.2), 12L));
  }

  @Test
  void missingOrInvalidTrainLengthDoesNotInventAStaticFallback() {
    assertTrue(
        PhysicalInterlockingBerthPolicy.requiredExitBerthBlocks(OptionalDouble.empty(), 12L)
            .isEmpty());
    assertTrue(
        PhysicalInterlockingBerthPolicy.requiredExitBerthBlocks(OptionalDouble.of(Double.NaN), 12L)
            .isEmpty());
    assertTrue(
        PhysicalInterlockingBerthPolicy.requiredExitBerthBlocks(OptionalDouble.of(0.0), 12L)
            .isEmpty());
  }

  @Test
  void overflowSaturatesAndNegativeClearanceIsRejected() {
    assertEquals(
        OptionalLong.of(Long.MAX_VALUE),
        PhysicalInterlockingBerthPolicy.requiredExitBerthBlocks(
            OptionalDouble.of(Double.MAX_VALUE), 12L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PhysicalInterlockingBerthPolicy.requiredExitBerthBlocks(OptionalDouble.of(10.0), -1L));
  }
}
