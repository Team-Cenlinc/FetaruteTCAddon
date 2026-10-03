package org.fetarute.fetaruteTCAddon.display.hud.scoreboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 车内计分板只重发变了的行：原版每设一次队伍前缀就发一个包，不比较新旧值。 */
class ScoreboardLineDiffTest {

  @Test
  void onlyChangedLinesAreSent() {
    List<String> before = List.of("开往 蒲塘桥", "1 - 新笛矢", "2 分", "时间 21:40:11");
    List<String> after = List.of("开往 蒲塘桥", "1 - 新笛矢", "2 分", "时间 21:40:12");

    assertEquals(List.of(3), ScoreboardTrainHudManager.changedLines(before, after));
  }

  @Test
  void firstDrawSendsEveryLine() {
    assertEquals(
        List.of(0, 1, 2),
        ScoreboardTrainHudManager.changedLines(List.of(), List.of("a", "b", "c")));
  }
}
