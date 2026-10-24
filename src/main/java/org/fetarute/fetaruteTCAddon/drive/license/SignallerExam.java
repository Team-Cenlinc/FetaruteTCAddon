package org.fetarute.fetaruteTCAddon.drive.license;

import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.drive.license.ExamEvaluation.Result;
import org.fetarute.fetaruteTCAddon.drive.license.ExamEvaluation.Verdict;

/**
 * 信号员考法的判定：在控制台上值守、转人工，亲手排满 {@link LicenseClass#examStops()} 列车的进路即交卷，按扣分看及不及格。本类不依赖服务器对象。
 *
 * <p>满分 100：等许可超时由系统代排、呼叫超时没回复、回复与呼叫原因不符，每次扣 {@value #PENALTY}
 * 分（比平时值班严一倍），扣到及格线以下当场不及格。走自动通过进路的车不算亲手排的。 考试中有一次模拟呼叫：排满之前它还没了结（回复或超时）时等它了结再交卷。
 * 中途把控制区转回自动、或连续几列车没人管被系统转为自动，当场不及格；下岗或到了时限还没排满，不计成绩。
 */
public final class SignallerExam {

  /** 考试中每次代排、漏接扣的分。 */
  public static final int PENALTY = 10;

  private static final int FULL_POINTS = 100;

  private SignallerExam() {}

  /**
   * 此刻的得分。
   *
   * @param autoRouted 超时代排的车数
   * @param callsMissed 超时没回复的呼叫数
   * @param callsWrong 回复与原因不符的呼叫数
   */
  public static int points(int autoRouted, int callsMissed, int callsWrong) {
    long penalty =
        ((long) Math.max(0, autoRouted) + Math.max(0, callsMissed) + Math.max(0, callsWrong))
            * PENALTY;
    return (int) Math.max(0L, FULL_POINTS - penalty);
  }

  /**
   * 按考试中的计数判定。
   *
   * @param manual 亲手排的进路上驶入控制区的车数
   * @param callsWrong 回复与原因不符的呼叫数
   * @param drillDone 模拟呼叫已了结（回复或超时）；没了结时排满也先不交卷
   * @return 还没排满（或模拟呼叫还没了结）、分数也还没掉到及格线以下时为空
   */
  public static Optional<Result> judge(
      LicenseClass license,
      int manual,
      int autoRouted,
      int callsMissed,
      int callsWrong,
      boolean drillDone) {
    int points = points(autoRouted, callsMissed, callsWrong);
    if (points < license.minPoints()) {
      return Optional.of(
          new Result(
              Verdict.FAILED,
              "points",
              Map.of(
                  "points", String.valueOf(points), "min", String.valueOf(license.minPoints()))));
    }
    if (manual < license.examStops() || !drillDone) {
      return Optional.empty();
    }
    return Optional.of(
        new Result(Verdict.PASSED, "passed", Map.of("points", String.valueOf(points))));
  }

  /** 信号员自己把控制区转回了自动：当场不及格。 */
  public static Result toAuto() {
    return new Result(Verdict.FAILED, "to-auto", Map.of());
  }

  /** 连续几列车由系统代排、其间没有操作，控制区被转为自动：当场不及格。 */
  public static Result idleAuto() {
    return new Result(Verdict.FAILED, "idle-auto", Map.of());
  }

  /** 还没排满就下岗了：不计成绩。 */
  public static Result ended() {
    return new Result(Verdict.VOID, "ended", Map.of());
  }

  /** 到了时限还没排满：不计成绩。 */
  public static Result expired() {
    return new Result(Verdict.VOID, "expired", Map.of());
  }
}
