package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.drive.driver.score.StopScore;
import org.fetarute.fetaruteTCAddon.drive.driver.score.TaskScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("越站处置：同一趟越站累计到上限就交还自动运行")
class SkipStationHandbackTest {

  @Test
  @DisplayName("只数越站，停准、停过头不算")
  void countsOnlySkippedStops() {
    TaskScore score = new TaskScore();
    score.addStop(new StopScore("A", 0.5, StopAlignment.Outcome.ACCURATE, false, false));
    score.addStop(new StopScore("B", Double.NaN, StopAlignment.Outcome.SKIPPED, false, false));
    score.addStop(new StopScore("C", 4.0, StopAlignment.Outcome.OVERRUN, false, false));
    score.addStop(new StopScore("D", Double.NaN, StopAlignment.Outcome.SKIPPED, false, false));

    assertEquals(2, score.skippedStops());
  }

  @Test
  @DisplayName("到上限才交还；上限为 0 不处置")
  void handsBackAtTheLimit() {
    assertFalse(DriveSessionManager.skipHandbackDue(1, 2));
    assertTrue(DriveSessionManager.skipHandbackDue(2, 2));
    assertTrue(DriveSessionManager.skipHandbackDue(3, 2));
    assertFalse(DriveSessionManager.skipHandbackDue(6, 0), "0 表示只计成绩、不交还");
  }
}
