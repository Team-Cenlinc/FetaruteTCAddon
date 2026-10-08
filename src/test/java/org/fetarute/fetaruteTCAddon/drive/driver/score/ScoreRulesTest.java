package org.fetarute.fetaruteTCAddon.drive.driver.score;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import java.util.UUID;
import org.bukkit.util.Vector;
import org.fetarute.fetaruteTCAddon.dispatcher.node.NodeId;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.DriverStationStop;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveTaskRecordCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("成绩规则")
class ScoreRulesTest {

  private static StopScore stop(
      StopAlignment.Outcome window, boolean wrongDoor, boolean takenOver) {
    return new StopScore("站", 0.0, window, wrongDoor, takenOver);
  }

  @Test
  @DisplayName("全部停准、无介入：满分 S")
  void perfect() {
    TaskScore score = new TaskScore();
    score.addStop(stop(StopAlignment.Outcome.ACCURATE, false, false));
    score.setDelayAtStart(OptionalLong.of(10));
    score.setDelayAtEnd(OptionalLong.of(35));
    assertEquals(new ScoreRules.Result(100, ScoreRules.Grade.S), ScoreRules.evaluate(score, true));
  }

  @Test
  @DisplayName("逐项扣分与评级")
  void penalties() {
    TaskScore score = new TaskScore();
    score.addStop(stop(StopAlignment.Outcome.ACCEPTED, true, false));
    score.addStop(stop(StopAlignment.Outcome.OVERRUN, false, true));
    score.setCounts(2, 1, 0, 3, 1, 1.2, 0, 1);
    score.setDelayAtStart(OptionalLong.of(0));
    score.setDelayAtEnd(OptionalLong.of(95));
    // 停站 2+5+5+5，ATP 4，紧急 5，漏确认 5，迟确认 2，晚点 (95-30)/10=6
    ScoreRules.Result result = ScoreRules.evaluate(score, true);
    assertEquals(100 - 17 - 4 - 5 - 5 - 2 - 6, result.points());
    assertEquals(ScoreRules.Grade.C, result.grade());
  }

  @Test
  @DisplayName("没完成的任务最高 D；扣分不低于 0")
  void incompleteAndFloor() {
    TaskScore score = new TaskScore();
    assertEquals(ScoreRules.Grade.D, ScoreRules.evaluate(score, false).grade());
    score.setCounts(0, 0, 10, 0, 0, 0.0, 0, 0);
    assertEquals(0, ScoreRules.evaluate(score, true).points());
  }

  @Test
  @DisplayName("没停妥的停站不计；明细 JSON 带格式版本")
  void stopScoreAndCodec() {
    DriverStationStop stationStop =
        new DriverStationStop(
            NodeId.of("OP:S:STA:1"), "测试站", UUID.randomUUID(), new Vector(), null, false, true);
    stationStop.end();
    assertNull(StopScore.of(stationStop));
    DriverStationStop stopped =
        new DriverStationStop(
            NodeId.of("OP:S:STA:2"), "二站", UUID.randomUUID(), new Vector(), null, false, true);
    stopped.updateOffset(4.0);
    stopped.markStopped();
    stopped.end();
    StopScore score = StopScore.of(stopped);
    assertEquals(StopAlignment.Outcome.ACCEPTED, score.outcome());

    TaskScore task = new TaskScore();
    task.addStop(score);
    String json = DriveTaskRecordCodec.encode(task);
    assertTrue(json.contains("\"formatVersion\":1"), json);
    assertTrue(json.contains("二站"), json);
  }
}
