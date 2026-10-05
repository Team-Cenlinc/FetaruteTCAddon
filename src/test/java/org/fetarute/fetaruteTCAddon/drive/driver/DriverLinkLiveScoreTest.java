package org.fetarute.fetaruteTCAddon.drive.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverProtection.Decision;
import org.fetarute.fetaruteTCAddon.drive.driver.DriverProtection.Intervention;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("实时评分")
class DriverLinkLiveScoreTest {

  private final AtomicLong clock = new AtomicLong(100L);
  private final DriverLink link =
      new DriverLink(UUID.randomUUID(), "T-1", null, () -> 0.0, clock::get);

  @Test
  @DisplayName("没有扣分时满分，按开到终点给评级")
  void cleanRunIsFullMarks() {
    ScoreRules.Result result = link.liveResult(OptionalLong.empty());
    assertEquals(100, result.points());
    assertEquals(ScoreRules.Grade.S, result.grade());
  }

  @Test
  @DisplayName("介入次数与此刻的晚点实时计入，不影响结束时的成绩明细")
  void countsInterventionsAndDelaySoFar() {
    link.recordDecision(new Decision(Intervention.SERVICE, 10.0, true, false));
    link.recordDecision(new Decision(Intervention.NONE, 10.0, false, false));
    link.recordDecision(new Decision(Intervention.EMERGENCY, 10.0, true, false));
    link.score().setDelayAtStart(OptionalLong.of(0L));

    ScoreRules.Result result = link.liveResult(OptionalLong.of(130L));

    // ATP 2 + 紧急 5 + 晚点增加 130 秒（让出 30 秒后每 10 秒 1 分）10 = 17。
    assertEquals(83, result.points());
    assertEquals(ScoreRules.Grade.B, result.grade());
    assertEquals(0, link.score().serviceInterventions(), "估算不写回成绩明细");
  }
}
