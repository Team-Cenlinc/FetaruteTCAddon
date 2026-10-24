package org.fetarute.fetaruteTCAddon.drive.crew;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        SignallerShift.Counts.builder()
            .trainsHandled(handled)
            .routesSet(7)
            .autoRouted(autoRouted)
            .autoPassed(autoPassed)
            .preRouted(2)
            .holds(1)
            .callsAnswered(3)
            .callsMissed(missed)
            .build(),
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

    assertEquals(70, SignallerScore.evaluate(6, 0, 0, 0, 0).points());
    assertEquals(ScoreRules.Grade.B, SignallerScore.evaluate(6, 0, 0, 0, 0).grade());
  }

  @Test
  @DisplayName("扣到 0 为止，计数再大也不溢出成加分")
  void floorsAtZero() {
    assertEquals(0, SignallerScore.evaluate(40, 0, 0, 0, 0).points());
    assertEquals(ScoreRules.Grade.D, SignallerScore.evaluate(40, 0, 0, 0, 0).grade());
    assertEquals(
        0, SignallerScore.evaluate(Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0, 0).points());
  }

  @Test
  @DisplayName("答错扣 5 分、在区内丢了时间每列扣 2 分、交汇排序不当扣 3 分；多出 30 秒以内算正点，60 秒以上算丢了时间")
  void wrongRepliesLostTimeAndPoorJunctionOrderCost() {
    assertEquals(100 - 5 - 2 * 2 - 3, SignallerScore.evaluate(0, 0, 1, 2, 1).points());
    assertTrue(SignallerScore.timeKept(30));
    assertFalse(SignallerScore.timeKept(31));
    assertFalse(SignallerScore.timeLost(60));
    assertTrue(SignallerScore.timeLost(61));
  }

  @Test
  @DisplayName("奖励列数：亲手排的车，预排命中与正点放行各加半列（不超过亲手排的车数），交汇排序得当加一列")
  void rewardUnitsAddPreRoutingKeptTimeAndGoodJunctions() {
    SignallerShift shift =
        new SignallerShift(
            UUID.randomUUID(),
            "Sig",
            "SURC",
            "A",
            Instant.EPOCH,
            Instant.EPOCH.plusSeconds(600),
            SignallerShift.Counts.builder()
                .trainsHandled(10)
                .routesSet(6)
                .autoRouted(2)
                .autoPassed(2)
                .preRouted(3)
                .keptTime(4)
                .conflictsGood(1)
                .build(),
            SignallerShift.EndReason.OFF_DUTY);
    // 亲手排 10 − 2 − 2 = 6 列，预排 3、正点 4 各加半列，交汇得当 1 加一列
    assertEquals(6 + 1.5 + 2.0 + 1.0, SignallerScore.rewardUnits(shift), 1e-9);
    assertEquals(0.0, SignallerScore.rewardUnits(shift(3, 3, 3, 0)), 1e-9, "全是代排与自动通过时加成也没有");
  }

  @Test
  @DisplayName("计奖励的车数：经手的去掉超时代排与自动通过的，不少于 0")
  void rewardedTrains() {
    assertEquals(5, SignallerScore.rewardedTrains(shift(12, 3, 4, 0)));
    assertEquals(0, SignallerScore.rewardedTrains(shift(2, 5, 0, 0)));
    assertEquals(0, SignallerScore.rewardedTrains(shift(9, 0, 9, 0)));
  }
}
