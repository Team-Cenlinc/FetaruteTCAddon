package org.fetarute.fetaruteTCAddon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.fetarute.fetaruteTCAddon.dispatcher.schedule.timetable.TimetableBuildResult;
import org.junit.jupiter.api.Test;

/** 编表报告：可点命令的写法与快车被卡合计。 */
class TimetableBuildReportSenderTest {

  /** 可点命令里几条线要加引号：客户端不认不带引号的逗号，整条命令标红发不出去。 */
  @Test
  void severalLinesAreQuotedInClickableCommands() {
    assertEquals("\"MT,WS\"", TimetableBuildReportSender.lineCommandArgument("MT,WS"));
    assertEquals("MT", TimetableBuildReportSender.lineCommandArgument("MT"));
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
