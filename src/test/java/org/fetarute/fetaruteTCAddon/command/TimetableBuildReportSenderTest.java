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
            .withRapidCatchUp(new TimetableBuildResult.CatchUp(14140L, 117));

    String hint = TimetableBuildReportSender.rapidStaggerHint(OPTIONS, result).orElseThrow();

    assertTrue(hint.startsWith("快车 117 班在共线段被慢车拖住，共 14140 秒。"), hint);
    assertTrue(hint.contains("--rapid-stagger"), hint);
  }

  @Test
  void noHintWhenNothingIsCaughtOrTheSearchAlreadyRan() {
    TimetableBuildResult clear = TimetableBuildResult.failure("没被卡", List.of());
    TimetableBuildResult caught = clear.withRapidCatchUp(new TimetableBuildResult.CatchUp(30L, 1));

    assertTrue(TimetableBuildReportSender.rapidStaggerHint(OPTIONS, clear).isEmpty());
    assertTrue(
        TimetableBuildReportSender.rapidStaggerHint(OPTIONS.withRapidStagger(true), caught)
            .isEmpty(),
        "已经开过错峰：再给按钮只会重复同一次搜索");
  }

  @Test
  void theCatchUpTotalSurvivesAddingNotes() {
    TimetableBuildResult result =
        TimetableBuildResult.failure("x", List.of())
            .withRapidCatchUp(new TimetableBuildResult.CatchUp(60L, 2))
            .withPhaseNote("一行说明");

    assertEquals(new TimetableBuildResult.CatchUp(60L, 2), result.rapidCatchUp());
    assertEquals(List.of("一行说明"), result.phaseNotes());
  }
}
