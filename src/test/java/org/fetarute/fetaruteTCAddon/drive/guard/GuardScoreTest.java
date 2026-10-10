package org.fetarute.fetaruteTCAddon.drive.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop.Phase;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;
import org.junit.jupiter.api.Test;

/** 车掌成绩：按站扣分，异常报告不扣分，连续超时被撤下最高 D。 */
class GuardScoreTest {

  private static GuardScore.Stop clean(String station) {
    return new GuardScore.Stop(
        station, false, false, false, false, false, Optional.of(true), Optional.of(true), 0);
  }

  @Test
  void aCleanDutyIsAnS() {
    GuardScore score = new GuardScore();
    score.add(clean("A"));
    score.add(clean("B"));
    assertEquals(new ScoreRules.Result(100, ScoreRules.Grade.S), score.evaluate(true));
    assertEquals(ScoreRules.Grade.D, score.evaluate(false).grade(), "连续超时被撤下最高 D");
  }

  @Test
  void eachLapseCostsPoints() {
    assertEquals(
        15,
        new GuardScore.Stop(
                "A", true, true, true, false, false, Optional.empty(), Optional.empty(), 0)
            .penalty(),
        "三步超时各 5 分");
    assertEquals(
        7,
        new GuardScore.Stop(
                "A", false, false, false, true, true, Optional.empty(), Optional.empty(), 0)
            .penalty(),
        "开错门 5 分、提前关门 2 分");
    assertEquals(
        4,
        new GuardScore.Stop(
                "A", false, false, false, false, false, Optional.of(false), Optional.of(false), 0)
            .penalty(),
        "两项监视各 2 分");
    assertEquals(
        0,
        new GuardScore.Stop(
                "A", false, false, false, false, false, Optional.empty(), Optional.empty(), 2)
            .penalty(),
        "没采样不判，报告不扣分");
  }

  @Test
  void theScoreAddsUpAcrossStops() {
    GuardScore score = new GuardScore();
    score.add(
        new GuardScore.Stop(
            "A", true, false, false, false, false, Optional.empty(), Optional.empty(), 1));
    score.add(
        new GuardScore.Stop(
            "B", false, false, true, true, false, Optional.empty(), Optional.empty(), 2));
    score.add(clean("C"));
    assertEquals(new ScoreRules.Result(85, ScoreRules.Grade.A), score.evaluate(true));
    assertEquals(3, score.incidents());
    assertEquals(2, score.timeoutStops());
    assertEquals(3, score.stopCount());
  }

  /** 由一站的作业记录得出：开错门、停站中关门都记下。 */
  @Test
  void aStopIsReadFromItsWork() {
    GuardStopWork work = new GuardStopWork(GuardConfig.defaults());
    work.markWrongDoor();
    work.noteClosed(Phase.CLOSE_DOORS);
    GuardScore.Stop stop = GuardScore.Stop.of("A", work);
    assertTrue(stop.wrongDoor());
    assertFalse(stop.closedEarly(), "停站时间到了再关不算提前");
    work.noteClosed(Phase.DWELL);
    assertTrue(GuardScore.Stop.of("A", work).closedEarly());
  }
}
