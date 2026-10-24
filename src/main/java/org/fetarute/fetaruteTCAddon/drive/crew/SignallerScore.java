package org.fetarute.fetaruteTCAddon.drive.crew;

import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;

/**
 * 信号员一个班次的成绩：满分 100 按班次扣分，再折成与驾驶员相同的 S/A/B/C/D 评级。本类不依赖服务器对象。
 *
 * <ul>
 *   <li>超时代排：车在入口等许可超时、由系统代排，每列扣 5 分；
 *   <li>呼叫超时未回复：每次扣 5 分；
 *   <li>回复与呼叫原因不符：每次扣 5 分；
 *   <li>在区内丢了时间：拿着许可驶入的车出区时晚点比进区时多出 {@value #LOST_SECONDS} 秒以上，每列扣 2 分；
 *   <li>交汇排序不当：从不同入口抢同一片道岔的两列车，两车在区内合计多出的晚点超过 {@value #PAIR_BAD_SECONDS} 秒，每次扣 3 分；
 *   <li>值守中的模拟事件答错或超时：每次扣 2 分（系统随机出题，比真呼叫轻）。
 * </ul>
 *
 * <p>奖励按亲手排进来的车计，另有加成：预排命中（车到入口前进路已排好、没停车）、正点放行（亲手排进来的车出区时晚点没多出 {@value #KEPT_SECONDS}
 * 秒以上）各加半列；交汇排序得当（两车合计多出的晚点在 {@value #PAIR_GOOD_SECONDS} 秒以内）加一列；模拟事件答对每次加半列。
 *
 * <p>扣到 0 为止。走自动通过进路的车与扣车只记次数，不扣分；班次长短不加分，避免挂机刷分。
 */
public final class SignallerScore {

  static final int AUTO_ROUTE_PENALTY = 5;
  static final int MISSED_CALL_PENALTY = 5;
  static final int WRONG_REPLY_PENALTY = 5;
  static final int LOST_TIME_PENALTY = 2;
  static final int BAD_CONFLICT_PENALTY = 3;
  static final int SIM_PENALTY = 2;

  /** 出区时晚点比进区时多出不超过这么多秒，算正点放行。 */
  public static final long KEPT_SECONDS = 30L;

  /** 出区时晚点比进区时多出超过这么多秒，算在区内丢了时间。 */
  public static final long LOST_SECONDS = 60L;

  /** 交汇的两车合计多出的晚点不超过这么多秒，算排序得当。 */
  public static final long PAIR_GOOD_SECONDS = 60L;

  /** 交汇的两车合计多出的晚点超过这么多秒，算排序不当。 */
  public static final long PAIR_BAD_SECONDS = 180L;

  /** 预排命中、正点放行各加的奖励（按列计）。 */
  static final double HALF_TRAIN = 0.5;

  /** 交汇排序得当加的奖励（按列计）。 */
  static final double GOOD_CONFLICT_BONUS = 1.0;

  private static final int FULL_POINTS = 100;

  private SignallerScore() {}

  /** 计分。 */
  public static ScoreRules.Result evaluate(SignallerShift shift) {
    return evaluate(
        shift.autoRouted(),
        shift.callsMissed(),
        shift.callsWrong(),
        shift.lostTime(),
        shift.conflictsBad(),
        shift.simWrong() + (long) shift.simMissed());
  }

  /**
   * 计分。
   *
   * @param autoRouted 超时代排的车数
   * @param callsMissed 超时没回复的呼叫数
   * @param callsWrong 回复与原因不符的呼叫数
   * @param lostTime 在区内丢了时间的车数
   * @param conflictsBad 交汇排序不当的次数
   */
  public static ScoreRules.Result evaluate(
      int autoRouted, int callsMissed, int callsWrong, int lostTime, int conflictsBad) {
    return evaluate(autoRouted, callsMissed, callsWrong, lostTime, conflictsBad, 0L);
  }

  /**
   * 计分。
   *
   * @param simFailed 模拟事件答错与超时的次数
   */
  private static ScoreRules.Result evaluate(
      int autoRouted,
      int callsMissed,
      int callsWrong,
      int lostTime,
      int conflictsBad,
      long simFailed) {
    // 计数可能很大（长班次挂着不管）：用 long 算扣分，免得溢出成负数反而加分。
    long penalty =
        (long) Math.max(0, autoRouted) * AUTO_ROUTE_PENALTY
            + (long) Math.max(0, callsMissed) * MISSED_CALL_PENALTY
            + (long) Math.max(0, callsWrong) * WRONG_REPLY_PENALTY
            + (long) Math.max(0, lostTime) * LOST_TIME_PENALTY
            + (long) Math.max(0, conflictsBad) * BAD_CONFLICT_PENALTY
            + Math.max(0L, simFailed) * SIM_PENALTY;
    int points = (int) Math.max(0L, FULL_POINTS - penalty);
    return new ScoreRules.Result(points, ScoreRules.gradeOf(points, true));
  }

  /** 计奖励的车数：经手的车次里去掉超时代排与走自动通过进路的，只计信号员亲手排的，下限 0。自动通过不计奖励，免得设满自动通过挂机刷奖励。 */
  public static int rewardedTrains(SignallerShift shift) {
    return Math.max(0, shift.trainsHandled() - shift.autoRouted() - shift.autoPassed());
  }

  /** 计奖励的列数：亲手排的车，加上预排命中与正点放行各半列、交汇排序得当各一列、模拟事件答对各半列。 */
  public static double rewardUnits(SignallerShift shift) {
    int routed = rewardedTrains(shift);
    // 加成只给亲手排的车：预排命中、正点放行都不超过亲手排的车数
    return routed
        + HALF_TRAIN * (Math.min(shift.preRouted(), routed) + Math.min(shift.keptTime(), routed))
        + GOOD_CONFLICT_BONUS * shift.conflictsGood()
        + HALF_TRAIN * shift.simRight();
  }

  /** 出区时晚点比进区时多出这么多秒：算正点放行。 */
  public static boolean timeKept(long growthSeconds) {
    return growthSeconds <= KEPT_SECONDS;
  }

  /** 出区时晚点比进区时多出这么多秒：算在区内丢了时间。 */
  public static boolean timeLost(long growthSeconds) {
    return growthSeconds > LOST_SECONDS;
  }
}
