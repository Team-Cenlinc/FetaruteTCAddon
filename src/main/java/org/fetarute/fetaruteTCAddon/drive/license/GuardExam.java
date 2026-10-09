package org.fetarute.fetaruteTCAddon.drive.license;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.drive.guard.GuardScore;
import org.fetarute.fetaruteTCAddon.drive.guard.GuardSession;
import org.fetarute.fetaruteTCAddon.drive.license.ExamEvaluation.Result;
import org.fetarute.fetaruteTCAddon.drive.license.ExamEvaluation.Verdict;

/**
 * 车掌考法的判定：在调度列车上值乘，做满 {@link LicenseClass#examStops()} 站作业后按车掌成绩看及不及格。本类不依赖服务器对象。
 *
 * <p>哪一站有超时（站台代开、代关或代发发车信号）当场不及格；开错车门按 {@link LicenseClass#allowWrongDoor()}；做满站数后得分不低于及格线即及格。
 * 值乘中途结束：连续超时、漏乘、换端没坐进车尾为不及格，其余（自己结束、离线、列车不在了）不计成绩。
 */
public final class GuardExam {

  /** 一站讲评里作业合格。 */
  public static final String OK = "✔";

  /** 一站讲评里作业不合格。 */
  public static final String MISSED = "✘";

  /** 一站讲评里这项没有判（没采到样）。 */
  public static final String NOT_JUDGED = "—";

  private GuardExam() {}

  /**
   * 按考试中做过的站判定。
   *
   * @return 还没做满站数、也没有不及格项时为空
   */
  public static Optional<Result> judge(LicenseClass license, List<GuardScore.Stop> stops) {
    for (GuardScore.Stop stop : stops) {
      if (stop.forcedOpen() || stop.forcedClose() || stop.forcedSignal()) {
        return Optional.of(
            new Result(Verdict.FAILED, "timeout", Map.of("station", stop.station())));
      }
      if (stop.wrongDoor() && !license.allowWrongDoor()) {
        return Optional.of(
            new Result(Verdict.FAILED, "wrong-door", Map.of("station", stop.station())));
      }
    }
    if (stops.size() < license.examStops()) {
      return Optional.empty();
    }
    GuardScore score = new GuardScore();
    stops.forEach(score::add);
    int points = score.evaluate(true).points();
    if (points < license.minPoints()) {
      return Optional.of(
          new Result(
              Verdict.FAILED,
              "points",
              Map.of(
                  "points", String.valueOf(points), "min", String.valueOf(license.minPoints()))));
    }
    return Optional.of(
        new Result(Verdict.PASSED, "passed", Map.of("points", String.valueOf(points))));
  }

  /** 值乘在做满站数前结束。 */
  public static Result ended(GuardSession.EndReason reason) {
    return switch (reason) {
      case TIMEOUTS -> new Result(Verdict.FAILED, "timeouts", Map.of());
      case LEFT_BEHIND -> new Result(Verdict.FAILED, "left-behind", Map.of());
      case CAB_CHANGE -> new Result(Verdict.FAILED, "cab-change", Map.of());
      default -> new Result(Verdict.VOID, "ended", Map.of());
    };
  }

  /**
   * 一站的讲评：开门（按时、开对一侧）、关门（停站时间到后按时关）、关门监视、发车信号（按时发出）、出站监视，各项 {@link #OK}、{@link #MISSED} 或 {@link
   * #NOT_JUDGED}。
   */
  public static Map<String, String> review(GuardScore.Stop stop) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("station", stop.station());
    values.put("open", mark(!stop.forcedOpen() && !stop.wrongDoor()));
    values.put("close", mark(!stop.forcedClose() && !stop.closedEarly()));
    values.put("closing", watch(stop.closingWatch()));
    values.put("signal", mark(!stop.forcedSignal()));
    values.put("departure", watch(stop.departureWatch()));
    return values;
  }

  private static String mark(boolean ok) {
    return ok ? OK : MISSED;
  }

  private static String watch(Optional<Boolean> passed) {
    return passed.map(GuardExam::mark).orElse(NOT_JUDGED);
  }
}
