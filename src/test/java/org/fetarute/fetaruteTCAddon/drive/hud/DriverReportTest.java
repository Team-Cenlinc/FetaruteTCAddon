package org.fetarute.fetaruteTCAddon.drive.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.drive.driver.score.StopScore;
import org.fetarute.fetaruteTCAddon.drive.driver.score.TaskScore;
import org.fetarute.fetaruteTCAddon.drive.sound.DriveCue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("成绩反馈文案")
class DriverReportTest {

  @Test
  @DisplayName("对标结果：正偏移是过标，负偏移是欠标；越站不报")
  void stopResult() {
    DriverReport.Line accurate =
        DriverReport.stopResult(
                new StopScore("人民广场", 0.84, StopAlignment.Outcome.ACCURATE, false, false))
            .orElseThrow();
    assertEquals("drive.hud.stop-result.accurate", accurate.key());
    assertEquals(Map.of("station", "人民广场"), accurate.values());
    assertEquals("drive.hud.stop-result.over", accurate.parts().get("offset").key());
    assertEquals("0.8", accurate.parts().get("offset").values().get("distance"));

    DriverReport.Line accepted =
        DriverReport.stopResult(
                new StopScore("西湖", -3.6, StopAlignment.Outcome.ACCEPTED, false, false))
            .orElseThrow();
    assertEquals("drive.hud.stop-result.under", accepted.parts().get("offset").key());
    assertEquals("3.6", accepted.parts().get("offset").values().get("distance"));

    assertTrue(
        DriverReport.stopResult(
                new StopScore("龙翔桥", 20.0, StopAlignment.Outcome.SKIPPED, false, false))
            .isEmpty());
    assertTrue(DriverReport.stopResult(null).isEmpty());
  }

  @Test
  @DisplayName("对标音效按窗口区分")
  void stopCue() {
    assertEquals(DriveCue.STOP_ACCURATE, DriverReport.stopCue(StopAlignment.Outcome.ACCURATE));
    assertEquals(DriveCue.STOP_ACCEPTED, DriverReport.stopCue(StopAlignment.Outcome.ACCEPTED));
    assertEquals(DriveCue.STOP_POOR, DriverReport.stopCue(StopAlignment.Outcome.OVERRUN));
    assertEquals(DriveCue.STOP_POOR, DriverReport.stopCue(StopAlignment.Outcome.SHORT));
  }

  @Test
  @DisplayName("成绩单：逐站一行，没有的项不列")
  void sheetListsOnlyWhatHappened() {
    TaskScore score = new TaskScore();
    score.addStop(new StopScore("人民广场", 0.5, StopAlignment.Outcome.ACCURATE, false, false));
    score.addStop(new StopScore("西湖", -3.6, StopAlignment.Outcome.ACCEPTED, true, false));
    score.addStop(new StopScore("龙翔桥", 20.0, StopAlignment.Outcome.SKIPPED, false, false));
    score.setCounts(1, 0, 0, 6, 0, 1.1, 0, 0);
    score.setDelayAtStart(OptionalLong.of(12L));
    score.setDelayAtEnd(OptionalLong.of(-3L));

    List<DriverReport.Line> sheet = DriverReport.sheet(score);

    assertEquals(
        List.of(
            "drive.task.sheet.stop",
            "drive.task.sheet.stop",
            "drive.task.sheet.stop",
            "drive.task.sheet.protection",
            "drive.task.sheet.signal",
            "drive.task.sheet.delay"),
        sheet.stream().map(DriverReport.Line::key).toList());
    assertEquals("", sheet.get(0).parts().get("flags").key());
    assertEquals("drive.task.sheet.flag.wrong-door", sheet.get(1).parts().get("flags").key());
    assertEquals("drive.task.sheet.result.skipped", sheet.get(2).parts().get("result").key());
    assertEquals("1.1", sheet.get(4).values().get("reaction"));
    assertEquals("+12", sheet.get(5).parts().get("start").values().get("value"));
    assertEquals("-3", sheet.get(5).parts().get("end").values().get("value"));
  }

  @Test
  @DisplayName("什么都没有时成绩单为空")
  void emptySheet() {
    assertTrue(DriverReport.sheet(new TaskScore()).isEmpty());
  }
}
