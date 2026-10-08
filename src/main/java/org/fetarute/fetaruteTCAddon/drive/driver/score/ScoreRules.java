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
 * <p>卡住被收回、超过任务时限的最高 D；驾驶员自己中途结束、因调度等非本人原因被收回的按已开的部分评级（状态记为未完成）。
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

  /** 不评级的记录里的评级（库里可能有这样的旧记录）。 */
  public static final String UNGRADED = "-";

  static final long DELAY_ALLOWANCE_SECONDS = 30L;

  /** 越站：乘客没能上下车，按一次强制停车计。 */
  static final int SKIPPED_STOP_PENALTY = 15;

  private ScoreRules() {}

  /**
   * 计分。
   *
   * @param completed 照常评级：开到了终点站，或中途结束、被收回（按已开的部分）；卡住被收回、超过任务时限时为 {@code false}，最高 D
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
