package org.fetarute.fetaruteTCAddon.dispatcher.consist;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 同一车型在各方案里的覆盖项必须一致；不同车型、写法大小写不同但覆盖一致都不算冲突。 */
class ConsistOverrideConflictsTest {

  private static List<ConsistPlanBook.Entry> entries(String... lines) {
    return ConsistPlanBook.parse(List.of(lines)).entries();
  }

  @Test
  void flagsSamePatternWithDifferentOverrides() {
    List<ConsistPlanBook.Entry> mine = entries("3 SH_A6", "1 SH_A8 | type=emu");
    Map<String, List<ConsistPlanBook.Entry>> others =
        Map.of(
            "SURC/Peak", entries("1 sh_a8 | type=metro"),
            "SURN/Base", entries("1 SH_A6", "1 SH_B4 | type=dmu"),
            "SURC/Night", entries("2 SH_A8 | type=emu"));

    assertEquals(
        List.of(new ConsistOverrideConflicts.Conflict("SH_A8", "SURC/Peak")),
        ConsistOverrideConflicts.find(mine, others));
  }

  @Test
  void writingOverridesWhereTheOtherPlanHasNoneIsAConflict() {
    assertEquals(
        List.of(new ConsistOverrideConflicts.Conflict("SH_A6", "SURC/Base")),
        ConsistOverrideConflicts.find(
            entries("1 SH_A6 | name=6 节"), Map.of("SURC/Base", entries("1 SH_A6"))));
  }
}
