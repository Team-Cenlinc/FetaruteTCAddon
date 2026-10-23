package org.fetarute.fetaruteTCAddon.coupler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** 按表推联挂两边的节数：一方车底的编组是整列组合编组的首段或末段，节数取那几段之和。 */
class PlannedPatternTest {

  private static final DecoupleService.PlannedPattern WHOLE =
      new DecoupleService.PlannedPattern(List.of("Kiha_A", "Kiha_B", "Kiha_C"), List.of(2, 1, 3));

  @Test
  void aSideIsTheFirstOrLastParts() {
    assertEquals(6, WHOLE.cars());
    assertEquals(OptionalInt.of(3), WHOLE.carsOfEnd("kiha_a+kiha_b"), "首两段，不分大小写");
    assertEquals(OptionalInt.of(3), WHOLE.carsOfEnd("Kiha_C"), "末段");
    assertEquals(OptionalInt.empty(), WHOLE.carsOfEnd("Kiha_B"), "中间一段不是一方车底");
    assertEquals(OptionalInt.empty(), WHOLE.carsOfEnd("Kiha_A+Kiha_B+Kiha_C"), "整列不算一方");
    assertEquals(OptionalInt.empty(), WHOLE.carsOfEnd(""));
  }
}
