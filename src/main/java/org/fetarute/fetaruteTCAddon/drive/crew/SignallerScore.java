package org.fetarute.fetaruteTCAddon.drive.crew;

import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;

/**
 * 信号员一个班次的成绩：满分 100 按班次扣分，再折成与驾驶员相同的 S/A/B/C/D 评级。本类不依赖服务器对象。
 *
 * <ul>
 *   <li>超时代排：车在入口等许可超时、由系统代排，每列扣 5 分；
 *   <li>呼叫超时未回复：每次扣 5 分。
 * </ul>
 *
 * <p>扣到 0 为止。走自动通过进路的车、预排命中与扣车只记次数，不扣分也不加分；班次长短不加分，避免挂机刷分。
 */
public final class SignallerScore {

  static final int AUTO_ROUTE_PENALTY = 5;
  static final int MISSED_CALL_PENALTY = 5;

  private static final int FULL_POINTS = 100;

  private SignallerScore() {}

  /** 计分。 */
  public static ScoreRules.Result evaluate(SignallerShift shift) {
    return evaluate(shift.autoRouted(), shift.callsMissed());
  }

  /**
   * 计分。
   *
   * @param autoRouted 超时代排的车数
   * @param callsMissed 超时没回复的呼叫数
   */
  public static ScoreRules.Result evaluate(int autoRouted, int callsMissed) {
    // 计数可能很大（长班次挂着不管）：用 long 算扣分，免得溢出成负数反而加分。
    long penalty =
        (long) Math.max(0, autoRouted) * AUTO_ROUTE_PENALTY
            + (long) Math.max(0, callsMissed) * MISSED_CALL_PENALTY;
    int points = (int) Math.max(0L, FULL_POINTS - penalty);
    return new ScoreRules.Result(points, ScoreRules.gradeOf(points, true));
  }

  /** 计奖励的车数：经手的车次里去掉超时代排与走自动通过进路的，只计信号员亲手排的，下限 0。自动通过不计奖励，免得设满自动通过挂机刷奖励。 */
  public static int rewardedTrains(SignallerShift shift) {
    return Math.max(0, shift.trainsHandled() - shift.autoRouted() - shift.autoPassed());
  }
}
