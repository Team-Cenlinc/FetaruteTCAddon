package org.fetarute.fetaruteTCAddon.drive.license;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("驾驶证路考判定")
class ExamEvaluationTest {

  private static final LicenseClass STRICT = LicenseConfig.defaults().find("driver").orElseThrow();

  private static DriveApi.TaskView task(DriveApi.TaskState state, DriveApi.Mode mode) {
    return new DriveApi.TaskView(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "T-1",
        LocalDate.of(2026, 10, 4),
        "R1",
        new DriveApi.StationRef("AAA", "A 站", 0),
        Optional.of(new DriveApi.StationRef("DDD", "D 站", 3)),
        Instant.EPOCH,
        mode,
        state,
        Optional.of("T"),
        false,
        "exam",
        Map.of(),
        "",
        OptionalInt.empty(),
        Optional.empty());
  }

  private static DriveApi.StopResult stop(DriveApi.StopOutcome window, boolean wrongDoor) {
    return new DriveApi.StopResult("B 站", 0.5, window, wrongDoor, false);
  }

  private static Optional<DriveApi.TaskScore> score(
      int points, int emergency, DriveApi.StopResult... stops) {
    return Optional.of(
        new DriveApi.TaskScore(
            points, "A", List.of(stops), 0, emergency, 0, 0, OptionalLong.empty()));
  }

  private static ExamEvaluation.Verdict verdict(
      DriveApi.TaskState state, DriveApi.Mode mode, Optional<DriveApi.TaskScore> score) {
    return ExamEvaluation.roadTest(STRICT, task(state, mode), score).verdict();
  }

  @Test
  @DisplayName("开到交班站、分数够、无不及格项即及格")
  void pass() {
    ExamEvaluation.Result result =
        ExamEvaluation.roadTest(
            STRICT,
            task(DriveApi.TaskState.COMPLETED, DriveApi.Mode.MANUAL),
            score(85, 0, stop(DriveApi.StopOutcome.ACCURATE, false)));
    assertEquals(ExamEvaluation.Verdict.PASSED, result.verdict());
    assertEquals("85", result.values().get("points"));
  }

  @Test
  @DisplayName("不及格：分数不够、防护紧急制动、停过头或越站、开错门、没开到交班站")
  void fail() {
    DriveApi.TaskState done = DriveApi.TaskState.COMPLETED;
    DriveApi.Mode manual = DriveApi.Mode.MANUAL;
    assertEquals(ExamEvaluation.Verdict.FAILED, verdict(done, manual, score(60, 0)));
    assertEquals(ExamEvaluation.Verdict.FAILED, verdict(done, manual, score(95, 1)));
    assertEquals(
        ExamEvaluation.Verdict.FAILED,
        verdict(done, manual, score(95, 0, stop(DriveApi.StopOutcome.OVERRUN, false))));
    assertEquals(
        ExamEvaluation.Verdict.FAILED,
        verdict(done, manual, score(95, 0, stop(DriveApi.StopOutcome.SKIPPED, false))));
    assertEquals(
        ExamEvaluation.Verdict.FAILED,
        verdict(done, manual, score(95, 0, stop(DriveApi.StopOutcome.ACCEPTED, true))));
    assertEquals(
        ExamEvaluation.Verdict.FAILED, verdict(DriveApi.TaskState.ABANDONED, manual, score(95, 0)));
    assertEquals(
        "points", ExamEvaluation.roadTest(STRICT, task(done, manual), score(60, 0)).reason());
  }

  @Test
  @DisplayName("不计成绩：没开车就结束、被调度收回、转 ATO")
  void voided() {
    assertEquals(
        ExamEvaluation.Verdict.VOID,
        verdict(DriveApi.TaskState.EXPIRED, DriveApi.Mode.MANUAL, Optional.empty()));
    assertEquals(
        ExamEvaluation.Verdict.VOID,
        verdict(DriveApi.TaskState.INTERRUPTED, DriveApi.Mode.MANUAL, score(95, 0)));
    assertEquals(
        ExamEvaluation.Verdict.VOID,
        verdict(DriveApi.TaskState.COMPLETED, DriveApi.Mode.ATO, score(95, 0)));
  }

  @Test
  @DisplayName("配置放宽的项不影响及格")
  void allowed() {
    LicenseClass lenient =
        new LicenseClass(
            "x",
            "x",
            "",
            true,
            List.of(),
            LicenseClass.Exam.ROAD_TEST,
            3,
            70,
            true,
            true,
            true,
            0,
            List.of());
    assertEquals(
        ExamEvaluation.Verdict.PASSED,
        ExamEvaluation.roadTest(
                lenient,
                task(DriveApi.TaskState.COMPLETED, DriveApi.Mode.MANUAL),
                score(80, 2, stop(DriveApi.StopOutcome.OVERRUN, true)))
            .verdict());
  }
}
