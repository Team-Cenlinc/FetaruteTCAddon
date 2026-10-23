package org.fetarute.fetaruteTCAddon.drive.crew;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("信号员班次成绩：超时代排与漏回呼叫扣分")
class SignallerScoreTest {

  private static final Instant START = Instant.parse("2026-10-20T08:00:00Z");

  static SignallerShift shift(int handled, int autoRouted, int autoPassed, int missed) {
    return new SignallerShift(
        UUID.fromString("00000000-0000-0000-0000-00000000abcd"),
        "Sinapole",
        "SURC",
        "WSD",
        START,
        START.plusSeconds(3600),
        handled,
        7,
        autoRouted,
        autoPassed,
        2,
        1,
        3,
        missed,
        SignallerShift.EndReason.OFF_DUTY);
  }

  @Test
  @DisplayName("没有扣分项时满分 S；自动通过、预排、扣车与班次长短都不影响分数")
  void fullMarks() {
    ScoreRules.Result result = SignallerScore.evaluate(shift(12, 0, 9, 0));
    assertEquals(100, result.points());
    assertEquals(ScoreRules.Grade.S, result.grade());
  }

  @Test
  @DisplayName("超时代排每列扣 5、漏回呼叫每次扣 5，按驾驶员同一口径折评级")
  void penalties() {
    ScoreRules.Result result = SignallerScore.evaluate(shift(12, 2, 0, 1));
    assertEquals(85, result.points());
    assertEquals(ScoreRules.Grade.A, result.grade());

    assertEquals(70, SignallerScore.evaluate(6, 0).points());
    assertEquals(ScoreRules.Grade.B, SignallerScore.evaluate(6, 0).grade());
  }

  @Test
  @DisplayName("扣到 0 为止，计数再大也不溢出成加分")
  void floorsAtZero() {
    assertEquals(0, SignallerScore.evaluate(40, 0).points());
    assertEquals(ScoreRules.Grade.D, SignallerScore.evaluate(40, 0).grade());
    assertEquals(0, SignallerScore.evaluate(Integer.MAX_VALUE, Integer.MAX_VALUE).points());
  }

  @Test
  @DisplayName("计奖励的车数：经手的去掉超时代排与自动通过的，不少于 0")
  void rewardedTrains() {
    assertEquals(5, SignallerScore.rewardedTrains(shift(12, 3, 4, 0)));
    assertEquals(0, SignallerScore.rewardedTrains(shift(2, 5, 0, 0)));
    assertEquals(0, SignallerScore.rewardedTrains(shift(9, 0, 9, 0)));
  }
}
