package org.fetarute.fetaruteTCAddon.drive.driver.score;

/**
 * 成绩规则：满分 100 按项扣分，再折成 S/A/B/C/D 评级。本类不依赖服务器对象。
 *
 * <ul>
 *   <li>停站：停准不扣；可开门窗口内 2 分；越过窗口 5 分；停短未对标（超时才停妥）5 分；开错门 5 分；迟迟不开门由站台代开 5 分；
 *   <li>防护：ATP 制动 2 分、紧急制动 5 分、强制停车 15 分；
 *   <li>信号：漏确认 5 分；警惕装置紧急制动 10 分；ATO 迟确认发车 2 分；
 *   <li>晚点：驾驶期间晚点增加超过 30 秒的部分每 10 秒 1 分，最多 30 分。
 * </ul>
 *
 * <p>未完成的任务（卡住被收回、超时）最高 D；驾驶员自己中途结束、因调度等非本人原因被收回的不评级（见 {@link #graded}）。
 */
public final class ScoreRules {

  /** 评级。 */
  public enum Grade {
    S,
    A,
    B,
    C,
    D
  }

  /**
   * 结果。
   *
   * @param points 0–100
   * @param grade 评级
   */
  public record Result(int points, Grade grade) {}

  /** 不评级时记下的评级。 */
  public static final String UNGRADED = "-";

  static final long DELAY_ALLOWANCE_SECONDS = 30L;

  /** 越站：乘客没能上下车，按一次强制停车计。 */
  static final int SKIPPED_STOP_PENALTY = 15;

  private ScoreRules() {}

  /**
   * 这一趟要不要评级：开到终点站的照常评级，卡住被收回、超过任务时限的评 D；驾驶员自己中途结束（离座、命令）、 因调度、管理员、拥堵保护等非本人原因被收回的不评级。
   *
   * @param completed 开到了终点站
   * @param failed 卡住被收回或超过任务时限
   */
  public static boolean graded(boolean completed, boolean failed) {
    return completed || failed;
  }

  /**
   * 计分。
   *
   * @param completed 任务是否完成（开到终点站）
   */
  public static Result evaluate(TaskScore score, boolean completed) {
    int penalty = 0;
    for (StopScore stop : score.stops()) {
      penalty +=
          switch (stop.outcome()) {
            case ACCURATE -> 0;
            case ACCEPTED -> 2;
            case OVERRUN, SHORT -> 5;
            case SKIPPED -> SKIPPED_STOP_PENALTY;
          };
      if (stop.wrongDoor()) {
        penalty += 5;
      }
      if (stop.doorsTakenOver()) {
        penalty += 5;
      }
    }
    penalty += 2 * score.serviceInterventions();
    penalty += 5 * score.emergencyInterventions();
    penalty += 15 * score.forcedStops();
    penalty += 5 * score.signalMisses();
    penalty += 10 * score.vigilanceTrips();
    penalty += 2 * score.lateDepartureConfirmations();
    long gained = score.delayGainedSeconds().orElse(0L) - DELAY_ALLOWANCE_SECONDS;
    if (gained > 0L) {
      penalty += (int) Math.min(30L, gained / 10L);
    }
    int points = Math.max(0, 100 - penalty);
    Grade grade =
        !completed
            ? Grade.D
            : points >= 95
                ? Grade.S
                : points >= 85
                    ? Grade.A
                    : points >= 70 ? Grade.B : points >= 50 ? Grade.C : Grade.D;
    return new Result(points, grade);
  }
}
