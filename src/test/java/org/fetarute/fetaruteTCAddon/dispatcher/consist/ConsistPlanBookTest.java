package org.fetarute.fetaruteTCAddon.dispatcher.consist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.fetarute.fetaruteTCAddon.dispatcher.consist.ConsistPlanBook.ProblemKind;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.config.TrainType;
import org.junit.jupiter.api.Test;

/** 方案书写法：权重、组合写法、覆盖项、注释，以及每种错误落在哪一行。 */
class ConsistPlanBookTest {

  @Test
  void parsesWeightsPatternsAndOverrides() {
    ConsistPlanBook.Parsed parsed =
        ConsistPlanBook.parse(
            List.of(
                "# 6 节为主",
                "",
                "3 SH_A6",
                "// 推拉车",
                "1   loco_df4   6*coach_25g | name=推拉 7 节 | max-bps=22",
                "1 SH_A8 | TYPE=emu | accel=0.8 | decel=1.1"));

    assertTrue(parsed.ok(), () -> parsed.problems().toString());
    assertEquals(3, parsed.entries().size());
    ConsistPlanBook.Entry a6 = parsed.entries().get(0);
    assertEquals(3, a6.lineNo());
    assertEquals(3, a6.weight());
    assertEquals("SH_A6", a6.pattern());
    assertEquals(ConsistOverrides.NONE, a6.overrides());

    ConsistPlanBook.Entry loco = parsed.entries().get(1);
    assertEquals("loco_df4 6*coach_25g", loco.pattern(), "组合写法里的空白压成一个空格");
    assertEquals(Optional.of("推拉 7 节"), loco.overrides().displayName());
    assertEquals(OptionalDouble.of(22.0), loco.overrides().maxSpeedBps());

    ConsistPlanBook.Entry a8 = parsed.entries().get(2);
    assertEquals(Optional.of(TrainType.EMU), a8.overrides().type(), "键不区分大小写");
    assertEquals(OptionalDouble.of(0.8), a8.overrides().accelBps2());
    assertEquals(OptionalDouble.of(1.1), a8.overrides().decelBps2());
  }

  @Test
  void reportsEachProblemOnItsLine() {
    ConsistPlanBook.Parsed parsed =
        ConsistPlanBook.parse(
            List.of(
                "three SH_A6",
                "0 SH_A6",
                "2",
                "1 50%a 50%b",
                "1 SH_A6 | colour=red",
                "1 SH_A7 | type=maglev",
                "1 SH_A9 | accel=-1",
                "1 SH_B1 | name=x | name=y",
                "1 SH_B2 | name",
                "2 SH_C1",
                "1 sh_c1"));

    assertEquals(
        List.of(
            new ConsistPlanBook.Problem(1, ProblemKind.BAD_WEIGHT, "three"),
            new ConsistPlanBook.Problem(2, ProblemKind.BAD_WEIGHT, "0"),
            new ConsistPlanBook.Problem(3, ProblemKind.MISSING_PATTERN, "2"),
            new ConsistPlanBook.Problem(4, ProblemKind.RANDOM_PATTERN, "50%a 50%b"),
            new ConsistPlanBook.Problem(5, ProblemKind.UNKNOWN_KEY, "colour=red"),
            new ConsistPlanBook.Problem(6, ProblemKind.BAD_VALUE, "type=maglev"),
            new ConsistPlanBook.Problem(7, ProblemKind.BAD_VALUE, "accel=-1"),
            new ConsistPlanBook.Problem(8, ProblemKind.BAD_VALUE, "name=y"),
            new ConsistPlanBook.Problem(9, ProblemKind.UNKNOWN_KEY, "name"),
            new ConsistPlanBook.Problem(11, ProblemKind.DUPLICATE_PATTERN, "sh_c1")),
        parsed.problems());
    assertEquals(
        List.of("SH_C1"),
        parsed.entries().stream().map(ConsistPlanBook.Entry::pattern).toList(),
        "出问题的行不进结果；同一编组按大小写不敏感判重");
  }

  @Test
  void emptyBookIsAProblem() {
    assertEquals(
        List.of(new ConsistPlanBook.Problem(0, ProblemKind.EMPTY, "")),
        ConsistPlanBook.parse(List.of("# 只有注释", "  ")).problems());
  }

  @Test
  void parsesStoredBodyByLines() {
    ConsistPlanBook.Parsed parsed = ConsistPlanBook.parse("3 SH_A6\r\n1 SH_A8\n");
    assertEquals(
        List.of("SH_A6", "SH_A8"),
        parsed.entries().stream().map(ConsistPlanBook.Entry::pattern).toList());
  }
}
