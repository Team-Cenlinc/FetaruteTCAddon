package org.fetarute.fetaruteTCAddon.dispatcher.sign.action;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 车掌、驾驶员提前关门的窗口：开门满停站下限，且计划发车之前只留这列车的关门动画与发车作业（5 秒），不晚于停站结束。 */
class AutoStationCrewCloseWindowTest {

  /** 晚点的车卡在停站下限上，与自动运行的停站压缩一致。 */
  @Test
  void aLateTrainMayCloseOnceTheFloorIsReached() {
    assertEquals(200L, AutoStationSignAction.earliestCrewCloseTick(200L, -100L, 200L, 100L));
  }

  /** 准点的车在计划发车前 10 秒（关门动画 5 秒 + 发车作业 5 秒）可以开始关门。 */
  @Test
  void anOnTimeTrainMayCloseTheLeadBeforeItsDeparture() {
    assertEquals(400L, AutoStationSignAction.earliestCrewCloseTick(200L, 600L, 600L, 100L));
  }

  /** 窗口不晚于停站结束：早到的车停站时间到了照常关门。 */
  @Test
  void theWindowNeverOpensAfterTheDwellEnds() {
    assertEquals(600L, AutoStationSignAction.earliestCrewCloseTick(200L, 2000L, 600L, 100L));
  }

  /** 停站本来就短于下限时（计划停站更短），到停站结束即可关。 */
  @Test
  void aShortPlannedDwellIsNotExtended() {
    assertEquals(100L, AutoStationSignAction.earliestCrewCloseTick(200L, 100L, 100L, 100L));
  }

  /** 关门动画长的车（旧式门动画 8 秒）窗口跟着提前，发车仍落在计划发车上。 */
  @Test
  void aLongerCloseAnimationOpensTheWindowEarlier() {
    assertEquals(340L, AutoStationSignAction.earliestCrewCloseTick(200L, 600L, 600L, 160L));
  }
}
