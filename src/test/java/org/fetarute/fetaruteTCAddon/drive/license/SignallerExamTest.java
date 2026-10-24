package org.fetarute.fetaruteTCAddon.drive.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fetarute.fetaruteTCAddon.drive.license.ExamEvaluation.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("信号员考试判定")
class SignallerExamTest {

  private static final LicenseClass SIGNALLER =
      LicenseConfig.defaults().find("signaller").orElseThrow();

  @Test
  @DisplayName("代排、漏接各扣 10 分，扣到 0 为止")
  void pointsDropTenPerMiss() {
    assertEquals(100, SignallerExam.points(0, 0, 0));
    assertEquals(70, SignallerExam.points(2, 1, 0));
    assertEquals(0, SignallerExam.points(20, 0, 0));
    assertEquals(
        0, SignallerExam.points(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE), "不溢出成负数");
  }

  @Test
  @DisplayName("亲手排满 5 列交卷：及格线 70 分；还没排满、分数也没掉下去时不判")
  void judgesOnceFiveTrainsAreRouted() {
    assertTrue(SignallerExam.judge(SIGNALLER, 4, 0, 0, 0, true).isEmpty());
    ExamEvaluation.Result passed = SignallerExam.judge(SIGNALLER, 5, 3, 0, 0, true).orElseThrow();
    assertEquals(Verdict.PASSED, passed.verdict());
    assertEquals(Map.of("points", "70"), passed.values());
  }

  @Test
  @DisplayName("答错与漏接一样扣 10 分；模拟呼叫没了结时排满也先不交卷")
  void wrongRepliesCostAndTheDrillCallMustBeSettled() {
    assertEquals(70, SignallerExam.points(1, 1, 1));
    assertTrue(SignallerExam.judge(SIGNALLER, 5, 0, 0, 0, false).isEmpty());
    assertEquals(
        Verdict.PASSED, SignallerExam.judge(SIGNALLER, 5, 0, 0, 1, true).orElseThrow().verdict());
    assertEquals(
        Verdict.FAILED,
        SignallerExam.judge(SIGNALLER, 2, 2, 0, 2, false).orElseThrow().verdict(),
        "分数掉下去不等模拟呼叫");
  }

  @Test
  @DisplayName("分数掉到及格线以下当场不及格，不必等排满")
  void failsAsSoonAsThePassMarkIsOutOfReach() {
    assertTrue(SignallerExam.judge(SIGNALLER, 1, 3, 0, 0, true).isEmpty(), "70 分还够");
    ExamEvaluation.Result failed = SignallerExam.judge(SIGNALLER, 1, 3, 1, 0, true).orElseThrow();
    assertEquals(Verdict.FAILED, failed.verdict());
    assertEquals("points", failed.reason());
    assertEquals("60", failed.values().get("points"));
    assertEquals("70", failed.values().get("min"));
  }

  @Test
  @DisplayName("转回自动、被系统转自动不及格；下岗与到时不计成绩")
  void endingsWithoutFiveTrains() {
    assertEquals(Verdict.FAILED, SignallerExam.toAuto().verdict());
    assertEquals(Verdict.FAILED, SignallerExam.idleAuto().verdict());
    assertEquals(Verdict.VOID, SignallerExam.ended().verdict());
    assertEquals(Verdict.VOID, SignallerExam.expired().verdict());
  }

  /** 信号员考试与练习的说明、进度、讲评、原因与要领、带练各步与提示、手册各页、报名与放弃都有文案。 */
  @ParameterizedTest
  @ValueSource(strings = {"zh_CN", "en_US"})
  void everyMessageExists(String localeTag) throws Exception {
    YamlConfiguration lang = new YamlConfiguration();
    try (InputStream stream =
        getClass().getClassLoader().getResourceAsStream("lang/" + localeTag + ".yml")) {
      lang.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
    List<String> keys = new ArrayList<>();
    for (String line : List.of("header", "how", "pass", "fail", "handbook")) {
      keys.add("drive.license.exam.brief.signaller." + line);
    }
    for (String line : List.of("header", "how", "pass", "handbook")) {
      keys.add("drive.license.practice.signaller.brief." + line);
    }
    for (String line :
        List.of(
            "started",
            "wait-manual",
            "progress",
            "deduct-auto-routed",
            "deduct-call-missed",
            "passed",
            "failed",
            "void")) {
      keys.add("drive.license.exam.signaller." + line);
    }
    List<ExamEvaluation.Result> results =
        List.of(
            SignallerExam.judge(SIGNALLER, 5, 0, 0, 0, true).orElseThrow(),
            SignallerExam.judge(SIGNALLER, 0, 5, 0, 0, true).orElseThrow(),
            SignallerExam.toAuto(),
            SignallerExam.idleAuto(),
            SignallerExam.ended(),
            SignallerExam.expired());
    for (ExamEvaluation.Result result : results) {
      keys.add("drive.license.exam.signaller.reason." + result.reason());
      if (result.verdict() == Verdict.FAILED) {
        keys.add("drive.license.exam.signaller.tip." + result.reason());
      }
    }
    for (SignallerTutorial.Step step : SignallerTutorial.Step.values()) {
      for (String part : List.of("name", "chat", "subtitle")) {
        keys.add("drive.license.practice.signaller.steps." + step.key() + "." + part);
      }
    }
    for (String tip : List.of("call", "auto-routed", "call-missed", "to-auto", "idle-auto")) {
      keys.add("drive.license.practice.signaller.tips." + tip);
    }
    for (String line : List.of("header", "counts", "not-counted", "separator", "ready", "more")) {
      keys.add("drive.license.practice.signaller.review." + line);
    }
    for (String line :
        List.of("in-progress", "expired", "step", "step-first", "skipped", "all-done")) {
      keys.add("drive.license.practice.signaller." + line);
    }
    for (String line : List.of("none", "exam", "practice", "signaller-on-duty")) {
      keys.add("drive.license.abandon." + line);
    }
    for (String line : List.of("need-practice", "open")) {
      keys.add("drive.license.info.signaller." + line);
    }
    keys.add("drive.license.exam.signaller-unavailable");
    keys.add("drive.license.exam.need-training-signaller");
    keys.add("drive.license.exam.in-progress-signaller");
    keys.add("signaller.duty.ended.exam");
    for (String part : List.of("title", "author", "given")) {
      keys.add("drive.handbook.signaller." + part);
    }
    for (int page = 1; page <= DriverHandbook.SIGNALLER_PAGES; page++) {
      keys.add("drive.handbook.signaller.page-" + page);
    }
    for (String key : keys) {
      assertTrue(lang.isString(key), localeTag + " 缺少 " + key);
    }
  }
}
