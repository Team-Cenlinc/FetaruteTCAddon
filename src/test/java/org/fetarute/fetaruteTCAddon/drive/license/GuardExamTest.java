package org.fetarute.fetaruteTCAddon.drive.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.drive.guard.GuardScore;
import org.fetarute.fetaruteTCAddon.drive.guard.GuardSession;
import org.fetarute.fetaruteTCAddon.drive.license.ExamEvaluation.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("车掌考试：做满站数按成绩判定，超时当场不及格，逐站讲评")
class GuardExamTest {

  private static final LicenseClass GUARD = LicenseConfig.defaults().find("guard").orElseThrow();

  private static GuardScore.Stop clean(String station) {
    return new GuardScore.Stop(
        station, false, false, false, false, false, Optional.of(true), Optional.of(true), 0);
  }

  private static GuardScore.Stop sloppy(String station) {
    // 提前关门、两项监视不合格：一站扣 6 分。
    return new GuardScore.Stop(
        station, false, false, false, false, true, Optional.of(false), Optional.of(false), 0);
  }

  @Test
  @DisplayName("没做满站数时不判定；做满后得分够就及格")
  void passesAfterEnoughStops() {
    List<GuardScore.Stop> stops = new ArrayList<>(List.of(clean("A"), clean("B")));
    assertEquals(Optional.empty(), GuardExam.judge(GUARD, stops));
    stops.add(sloppy("C"));
    ExamEvaluation.Result result = GuardExam.judge(GUARD, stops).orElseThrow();
    assertEquals(Verdict.PASSED, result.verdict());
    assertEquals(Map.of("points", "94"), result.values());
  }

  @Test
  @DisplayName("得分低于及格线不及格")
  void failsOnPoints() {
    List<GuardScore.Stop> stops = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      stops.add(
          new GuardScore.Stop(
              "S" + i,
              false,
              false,
              false,
              false,
              true,
              Optional.of(false),
              Optional.of(false),
              0));
    }
    LicenseClass strict =
        new LicenseClass(
            "guard",
            "车掌证",
            "",
            true,
            List.of(),
            LicenseClass.Exam.GUARD,
            3,
            90,
            false,
            false,
            false,
            0,
            List.of());
    ExamEvaluation.Result result = GuardExam.judge(strict, stops).orElseThrow();
    assertEquals(Verdict.FAILED, result.verdict());
    assertEquals("points", result.reason());
    assertEquals(Map.of("points", "82", "min", "90"), result.values());
    LicenseClass exact =
        new LicenseClass(
            "guard",
            "车掌证",
            "",
            true,
            List.of(),
            LicenseClass.Exam.GUARD,
            3,
            82,
            false,
            false,
            false,
            0,
            List.of());
    assertEquals(Verdict.PASSED, GuardExam.judge(exact, stops).orElseThrow().verdict(), "恰好及格线算及格");
  }

  @Test
  @DisplayName("哪一站超时当场不及格；开错车门看配置")
  void timeoutsAndWrongDoorsFailAtOnce() {
    GuardScore.Stop timedOut =
        new GuardScore.Stop(
            "B", false, true, false, false, false, Optional.empty(), Optional.empty(), 0);
    ExamEvaluation.Result result =
        GuardExam.judge(GUARD, List.of(clean("A"), timedOut)).orElseThrow();
    assertEquals(Verdict.FAILED, result.verdict());
    assertEquals("timeout", result.reason());
    assertEquals(Map.of("station", "B"), result.values());

    GuardScore.Stop wrong =
        new GuardScore.Stop(
            "C", false, false, false, true, false, Optional.empty(), Optional.empty(), 0);
    assertEquals("wrong-door", GuardExam.judge(GUARD, List.of(wrong)).orElseThrow().reason());
    LicenseClass lenient =
        new LicenseClass(
            "guard",
            "车掌证",
            "",
            true,
            List.of(),
            LicenseClass.Exam.GUARD,
            3,
            70,
            false,
            false,
            true,
            0,
            List.of());
    assertEquals(Optional.empty(), GuardExam.judge(lenient, List.of(wrong)), "允许开错门时照常往下考");
  }

  @Test
  @DisplayName("值乘中途结束：连续超时、漏乘、换端失败为不及格，其余不计成绩")
  void endingEarly() {
    assertEquals(Verdict.FAILED, GuardExam.ended(GuardSession.EndReason.TIMEOUTS).verdict());
    assertEquals(Verdict.FAILED, GuardExam.ended(GuardSession.EndReason.LEFT_BEHIND).verdict());
    assertEquals(Verdict.FAILED, GuardExam.ended(GuardSession.EndReason.CAB_CHANGE).verdict());
    assertEquals(Verdict.VOID, GuardExam.ended(GuardSession.EndReason.COMMAND).verdict());
    assertEquals(Verdict.VOID, GuardExam.ended(GuardSession.EndReason.TRAIN_GONE).verdict());
  }

  @Test
  @DisplayName("逐站讲评：各项合格、不合格或未判")
  void reviewMarksEachDuty() {
    Map<String, String> review =
        GuardExam.review(
            new GuardScore.Stop(
                "A", false, false, true, true, false, Optional.of(true), Optional.empty(), 1));
    assertEquals("A", review.get("station"));
    assertEquals(GuardExam.MISSED, review.get("open"), "开错门");
    assertEquals(GuardExam.OK, review.get("close"));
    assertEquals(GuardExam.OK, review.get("closing"));
    assertEquals(GuardExam.MISSED, review.get("signal"));
    assertEquals(GuardExam.NOT_JUDGED, review.get("departure"));
    assertEquals(
        GuardExam.MISSED,
        GuardExam.review(
                new GuardScore.Stop(
                    "B", false, false, false, false, true, Optional.empty(), Optional.empty(), 0))
            .get("close"),
        "停站时间未到就关门");
  }

  @Test
  @DisplayName("夹人夹物演练排在第二站到最后一站之间；只考一站时就在这一站；时限为 0 不演练")
  void drillStopIsNeverTheFirst() {
    assertEquals(15, GUARD.drillSeconds(), "车掌考试默认 15 秒");
    assertEquals(2, GuardExam.drillStop(GUARD, bound -> 0));
    assertEquals(3, GuardExam.drillStop(GUARD, bound -> bound - 1));
    LicenseClass oneStop =
        new LicenseClass(
            "guard",
            "车掌证",
            "",
            true,
            List.of(),
            LicenseClass.Exam.GUARD,
            1,
            70,
            false,
            false,
            false,
            0,
            List.of());
    assertEquals(1, GuardExam.drillStop(oneStop, bound -> 0));
    LicenseClass noDrill =
        new LicenseClass(
            "guard",
            "车掌证",
            "",
            true,
            List.of(),
            LicenseClass.Exam.GUARD,
            3,
            70,
            false,
            false,
            false,
            0,
            List.of(),
            0);
    assertEquals(0, GuardExam.drillStop(noDrill, bound -> 0));
    assertEquals(
        0, LicenseConfig.defaults().find("driver").orElseThrow().drillSeconds(), "路考没有车掌演练");
  }

  @Test
  @DisplayName("到了排定的那一站开始演练；晚点顺延过来的下一站照样开始；不演练时从不开始")
  void drillDueAtOrAfterThePlannedStop() {
    assertEquals(false, GuardExam.drillDue(2, 0), "第一站");
    assertTrue(GuardExam.drillDue(2, 1), "第二站");
    assertTrue(GuardExam.drillDue(2, 2), "顺延到第三站");
    assertEquals(false, GuardExam.drillDue(0, 2));
    ExamEvaluation.Result missed = GuardExam.drillMissed("B");
    assertEquals(Verdict.FAILED, missed.verdict());
    assertEquals(Map.of("station", "B"), missed.values());
  }

  /** 车掌考试的提示、讲评、原因与要领，以及车掌手册各页都有文案。 */
  @ParameterizedTest
  @ValueSource(strings = {"zh_CN", "en_US"})
  void everyMessageExists(String localeTag) throws Exception {
    YamlConfiguration lang = new YamlConfiguration();
    try (InputStream stream =
        getClass().getClassLoader().getResourceAsStream("lang/" + localeTag + ".yml")) {
      lang.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
    List<String> keys = new ArrayList<>();
    for (String line :
        List.of("header", "duties", "watch", "pass", "how", "no-wrong-door", "drill")) {
      keys.add("drive.license.exam.brief.guard." + line);
    }
    keys.add("drive.license.exam.brief.guard.handbook");
    keys.add("drive.license.exam.guard.stop");
    for (Verdict verdict : Verdict.values()) {
      keys.add("drive.license.exam.guard." + verdict.name().toLowerCase(java.util.Locale.ROOT));
    }
    List<ExamEvaluation.Result> results = new ArrayList<>();
    for (GuardSession.EndReason reason : GuardSession.EndReason.values()) {
      results.add(GuardExam.ended(reason));
    }
    results.add(
        GuardExam.judge(
                GUARD,
                List.of(
                    new GuardScore.Stop(
                        "A",
                        true,
                        false,
                        false,
                        false,
                        false,
                        Optional.empty(),
                        Optional.empty(),
                        0)))
            .orElseThrow());
    results.add(
        GuardExam.judge(
                GUARD,
                List.of(
                    new GuardScore.Stop(
                        "A",
                        false,
                        false,
                        false,
                        true,
                        false,
                        Optional.empty(),
                        Optional.empty(),
                        0)))
            .orElseThrow());
    results.add(GuardExam.judge(GUARD, List.of(clean("A"), clean("B"), clean("C"))).orElseThrow());
    results.add(GuardExam.drillMissed("A"));
    keys.add("drive.license.exam.guard.drill-ok");
    for (ExamEvaluation.Result result : results) {
      keys.add("drive.license.exam.guard.reason." + result.reason());
      if (result.verdict() == Verdict.FAILED) {
        keys.add("drive.license.exam.guard.tip." + result.reason());
      }
    }
    keys.add("drive.license.exam.guard.reason.points");
    keys.add("drive.license.exam.guard.tip.points");
    keys.add("drive.license.practice.not-guard");
    keys.add("drive.license.exam.guard-unavailable");
    keys.add("drive.license.info.level-open-guard");
    for (String part : List.of("title", "author", "given")) {
      keys.add("drive.handbook.guard." + part);
    }
    for (int page = 1; page <= DriverHandbook.GUARD_PAGES; page++) {
      keys.add("drive.handbook.guard.page-" + page);
    }
    for (String key : keys) {
      assertTrue(lang.isString(key), localeTag + " 缺少 " + key);
    }
  }
}
