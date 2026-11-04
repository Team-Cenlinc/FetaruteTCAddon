package org.fetarute.fetaruteTCAddon.drive.crew;

import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;

/**
 * 站长一个班次的成绩：满分 100 按项扣分，再折成与驾驶员相同的评级。本类不依赖服务器对象。
 *
 * <ul>
 *   <li>发车信号超时、由系统代发：每次 5 分；
 *   <li>接车不在岗，接车监视、出站监视不合格，扣车到上限自动放行：每次 2 分；
 *   <li>模拟事件处置错误或没按时处置：每次 2 分；
 *   <li>站台紧急停车只记次数，不扣分（宁停勿过）。
 * </ul>
 *
 * <p>值守时长不加分，免得挂机刷分；奖励按亲手发的发车信号与处置正确的模拟事件算。
 */
public final class StationMasterScore {

  static final int AUTO_SIGNAL_PENALTY = 5;
  static final int WATCH_PENALTY = 2;
  static final int HOLD_EXPIRED_PENALTY = 2;
  static final int SIM_PENALTY = 2;

  private StationMasterScore() {}

  /** 成绩与评级。 */
  public static ScoreRules.Result evaluate(StationMasterShift shift) {
    return evaluate(shift.counts());
  }

  /** 按此刻的计数算成绩：值守中的实时评分也用它。 */
  public static ScoreRules.Result evaluate(StationMasterShift.Counts counts) {
    long penalty =
        (long) counts.autoSignals() * AUTO_SIGNAL_PENALTY
            + (long) (counts.arrivalAbsent() + counts.arrivalMissed() + counts.departureMissed())
                * WATCH_PENALTY
            + (long) counts.holdsExpired() * HOLD_EXPIRED_PENALTY
            + (long) (counts.simWrong() + counts.simMissed()) * SIM_PENALTY;
    int points = (int) Math.max(0L, 100L - penalty);
    return new ScoreRules.Result(points, ScoreRules.gradeOf(points, true));
  }

  /** 计奖励的车数：亲手发的发车信号，每次模拟事件处置正确折半列。 */
  public static double rewardUnits(StationMasterShift shift) {
    return shift.counts().signals() + 0.5 * shift.counts().simRight();
  }
}
