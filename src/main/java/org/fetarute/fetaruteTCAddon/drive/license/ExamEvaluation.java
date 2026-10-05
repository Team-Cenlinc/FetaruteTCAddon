package org.fetarute.fetaruteTCAddon.drive.license;

import java.util.Map;
import java.util.Optional;
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi;

/** 驾驶证路考的判定：按区间任务的终态与成绩，看及不及格。本类不依赖服务器对象。 */
public final class ExamEvaluation {

  /** 判定结论。 */
  public enum Verdict {
    /** 及格：发证。 */
    PASSED,
    /** 不及格：要等冷却才能再考。 */
    FAILED,
    /** 不算成绩（没开车就结束、被调度收回、中途转 ATO）：可以马上重考。 */
    VOID
  }

  /**
   * 判定结果。
   *
   * @param verdict 结论
   * @param reason 原因（语言键 {@code drive.license.exam.reason.<原因>} 的后缀）
   * @param values 原因里的占位符
   */
  public record Result(Verdict verdict, String reason, Map<String, String> values) {
    public Result {
      values = values == null ? Map.of() : Map.copyOf(values);
    }
  }

  private ExamEvaluation() {}

  /**
   * 判定一次路考。
   *
   * @param license 考的那一级
   * @param task 考试任务结束时的快照
   * @param score 成绩；没开过车就结束时为空
   */
  public static Result dispatch(
      LicenseClass license, DriveApi.TaskView task, Optional<DriveApi.TaskScore> score) {
    if (score.isEmpty()) {
      return new Result(Verdict.VOID, "not-started", Map.of());
    }
    if (task.state() == DriveApi.TaskState.INTERRUPTED) {
      return new Result(Verdict.VOID, "interrupted", Map.of());
    }
    if (task.mode() != DriveApi.Mode.MANUAL) {
      return new Result(Verdict.VOID, "ato", Map.of());
    }
    if (task.state() != DriveApi.TaskState.COMPLETED) {
      return new Result(Verdict.FAILED, "not-completed", Map.of());
    }
    DriveApi.TaskScore result = score.get();
    if (!license.allowEmergency() && result.emergencyInterventions() > 0) {
      return new Result(
          Verdict.FAILED,
          "emergency",
          Map.of("count", String.valueOf(result.emergencyInterventions())));
    }
    for (DriveApi.StopResult stop : result.stops()) {
      if (!license.allowOverrun()
          && (stop.window() == DriveApi.StopWindow.OVERRUN
              || stop.window() == DriveApi.StopWindow.SKIPPED)) {
        return new Result(Verdict.FAILED, "overrun", Map.of("station", stop.station()));
      }
      if (!license.allowWrongDoor() && stop.wrongDoor()) {
        return new Result(Verdict.FAILED, "wrong-door", Map.of("station", stop.station()));
      }
    }
    if (result.points() < license.minPoints()) {
      return new Result(
          Verdict.FAILED,
          "points",
          Map.of(
              "points",
              String.valueOf(result.points()),
              "min",
              String.valueOf(license.minPoints())));
    }
    return new Result(Verdict.PASSED, "passed", Map.of("points", String.valueOf(result.points())));
  }
}
