package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableBuildOptions;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableBuildResult;
import org.junit.jupiter.api.Test;

/** 编表报告里的「用快车错峰重建」：没开错峰、成品表上又有快车被拖住时才给。 */
class TimetableBuildReportSenderTest {

  private static final TimetableBuildOptions OPTIONS =
      TimetableBuildOptions.defaults(ZoneId.of("UTC"));

  @Test
  void caughtRapidsWithoutTheFlagGetARebuildHint() {
    TimetableBuildResult result =
        TimetableBuildResult.failure("只看快车被卡", List.of())
            .withRapidCatchUp(new TimetableBuildResult.CatchUp(14140L, 117, 0L));

    String hint = TimetableBuildReportSender.rapidStaggerHint(OPTIONS, result).orElseThrow();

    assertTrue(hint.startsWith("快车 117 班在共线段被慢车拖住，共 14140 秒。"), hint);
    assertTrue(hint.contains("--rapid-stagger"), hint);
  }

  @Test
  void noHintWhenNothingIsCaughtOrTheSearchAlreadyRan() {
    TimetableBuildResult clear = TimetableBuildResult.failure("没被卡", List.of());
    TimetableBuildResult caught =
        clear.withRapidCatchUp(new TimetableBuildResult.CatchUp(30L, 1, 0L));

    assertTrue(TimetableBuildReportSender.rapidStaggerHint(OPTIONS, clear).isEmpty());
    assertTrue(
        TimetableBuildReportSender.rapidStaggerHint(OPTIONS.withRapidStagger(true), caught)
            .isEmpty(),
        "已经开过错峰：再给按钮只会重复同一次搜索");
  }

  /** 开了错峰但原表放宽了：错峰没搜，按放宽后的间隔带上错峰重编，要给按钮。 */
  @Test
  void aRelaxedTableGetsTheHintEvenWithTheFlag() {
    TimetableBuildResult.CatchUp caught = new TimetableBuildResult.CatchUp(500L, 9, 0L);

    String hint = TimetableBuildReportSender.rapidStaggerHint(true, true, caught).orElseThrow();

    assertTrue(hint.contains("已放宽，按放宽后的间隔带 --rapid-stagger"), hint);
    assertTrue(TimetableBuildReportSender.rapidStaggerHint(true, false, caught).isEmpty());
    assertTrue(
        TimetableBuildReportSender.rapidStaggerHint(false, false, caught)
            .orElseThrow()
            .startsWith("快车 9 班在共线段被慢车拖住，共 500 秒。带 --rapid-stagger"));
  }

  /** 可点命令里几条线要加引号：客户端不认不带引号的逗号，整条命令标红发不出去。 */
  @Test
  void severalLinesAreQuotedInClickableCommands() {
    assertEquals("\"MT,WS\"", TimetableBuildReportSender.lineCommandArgument("MT,WS"));
    assertEquals("MT", TimetableBuildReportSender.lineCommandArgument("MT"));
  }

  /** 共线不被卡、却在表里让车等待的快车同样给按钮：那也是被慢车拖慢。 */
  @Test
  void rapidsWaitingInTheTableAlsoGetTheHint() {
    String hint =
        TimetableBuildReportSender.rapidStaggerHint(
                false, false, new TimetableBuildResult.CatchUp(0L, 0, 2379L))
            .orElseThrow();

    assertTrue(hint.startsWith("快车在表里让车等待共 2379 秒。带 --rapid-stagger"), hint);
    assertTrue(
        TimetableBuildReportSender.rapidStaggerHint(
                false, false, new TimetableBuildResult.CatchUp(500L, 9, 40L))
            .orElseThrow()
            .startsWith("快车 9 班在共线段被慢车拖住，共 500 秒，快车在表里让车等待共 40 秒。"));
  }

  @Test
  void theCatchUpTotalSurvivesAddingNotes() {
    TimetableBuildResult result =
        TimetableBuildResult.failure("x", List.of())
            .withRapidCatchUp(new TimetableBuildResult.CatchUp(60L, 2, 0L))
            .withPhaseNote("一行说明");

    assertEquals(new TimetableBuildResult.CatchUp(60L, 2, 0L), result.rapidCatchUp());
    assertEquals(List.of("一行说明"), result.phaseNotes());
  }
}
