package org.fetarute.fetaruteTCAddon.drive.license;

import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.drive.license.ExamEvaluation.Result;
import org.fetarute.fetaruteTCAddon.drive.license.ExamEvaluation.Verdict;

/**
 * 站长考试的判定：亲手给满几列车发车信号、处置一次模拟事件，按扣分判定。本类不依赖服务器对象。
 *
 * <p>满分 100，超时代发、监视不合格、模拟事件处置错误或没按时处置，每次扣 {@link #PENALTY} 分；低于及格线当场不及格。
 */
final class StationMasterExam {

  /** 每一项扣的分。 */
  static final int PENALTY = 10;

  private StationMasterExam() {}

  /** 考试得分。 */
  static int points(int autoSignals, int watchMissed, int simWrong, int simMissed) {
    long lapses = (long) autoSignals + watchMissed + simWrong + simMissed;
    return (int) Math.max(0L, 100L - lapses * PENALTY);
  }

  /**
   * 判定；还没考完时为空。
   *
   * @param signals 亲手发的发车信号
   * @param drillDone 考官安排的那一次模拟事件已了结
   */
  static Optional<Result> judge(
      LicenseClass license,
      int signals,
      int autoSignals,
      int watchMissed,
      int simWrong,
      int simMissed,
      boolean drillDone) {
    int points = points(autoSignals, watchMissed, simWrong, simMissed);
    if (points < license.minPoints()) {
      return Optional.of(
          new Result(
              Verdict.FAILED,
              "points",
              Map.of(
                  "points", String.valueOf(points), "min", String.valueOf(license.minPoints()))));
    }
    if (signals < license.examStops() || !drillDone) {
      return Optional.empty();
    }
    return Optional.of(
        new Result(Verdict.PASSED, "passed", Map.of("points", String.valueOf(points))));
  }

  /** 考试中下岗（自己下岗、离开车站、下线等）：不计成绩。 */
  static Result ended() {
    return new Result(Verdict.VOID, "ended", Map.of());
  }

  /** 过了报名时限还没考完：不计成绩。 */
  static Result expired() {
    return new Result(Verdict.VOID, "expired", Map.of());
  }
}
