package org.fetarute.fetaruteTCAddon.drive.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("站长考试：亲手发满列数并处置一次模拟事件，按扣分判定")
class StationMasterExamTest {

  private static final LicenseClass STATION_MASTER =
      LicenseConfig.defaults().find("station-master").orElseThrow();

  @Test
  void theDefaultClassIsAStationMasterPost() {
    assertEquals(LicenseClass.Exam.STATION_MASTER, STATION_MASTER.exam());
    assertEquals(LicenseClass.Post.STATION_MASTER, STATION_MASTER.post());
    assertEquals(5, STATION_MASTER.examStops());
    assertTrue(STATION_MASTER.grants().contains("fetarute.stationmaster"));
    assertEquals(LicenseClass.Exam.STATION_MASTER, LicenseClass.Exam.parse("station-master"));
  }

  @Test
  void notFinishedUntilEnoughSignalsAndTheDrill() {
    assertEquals(Optional.empty(), StationMasterExam.judge(STATION_MASTER, 4, 0, 0, 0, 0, true));
    assertEquals(Optional.empty(), StationMasterExam.judge(STATION_MASTER, 5, 0, 0, 0, 0, false));
    assertEquals(
        ExamEvaluation.Verdict.PASSED,
        StationMasterExam.judge(STATION_MASTER, 5, 1, 0, 0, 0, true).orElseThrow().verdict());
  }

  @Test
  void lapsesBelowThePassMarkFailAtOnce() {
    ExamEvaluation.Result result =
        StationMasterExam.judge(STATION_MASTER, 1, 2, 1, 1, 0, false).orElseThrow();
    assertEquals(ExamEvaluation.Verdict.FAILED, result.verdict());
    assertEquals("60", result.values().get("points"));
  }
}
