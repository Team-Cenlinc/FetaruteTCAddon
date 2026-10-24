package org.fetarute.fetaruteTCAddon.drive.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.fetarute.fetaruteTCAddon.drive.crew.SignallerShift;
import org.fetarute.fetaruteTCAddon.drive.license.SignallerTutorial.Change;
import org.fetarute.fetaruteTCAddon.drive.license.SignallerTutorial.Step;
import org.fetarute.fetaruteTCAddon.signaller.SignallerExaminer.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("信号员练习的带练")
class SignallerTutorialTest {

  private static final Instant T0 = Instant.parse("2026-10-23T08:00:00Z");

  @Test
  @DisplayName("一步步做下去：认识控制台按时间继续，人工模式已是人工就当场完成，最后用下岗命令下岗才算完成")
  void walksThroughEveryStep() {
    SignallerTutorial tutorial = new SignallerTutorial();
    assertEquals(List.of(new Change.Started(Step.ON_DUTY, 1, 0)), tutorial.begin(T0), "上岗前不知道总共几步");

    List<Change> changes = tutorial.dutyStarted(true, true, T0);
    assertEquals(
        List.of(new Change.Completed(Step.ON_DUTY), new Change.Started(Step.SCREEN, 2, 11)),
        changes);
    assertEquals(List.of(), tutorial.tick(T0.plusSeconds(11)));
    assertEquals(
        List.of(
            new Change.Completed(Step.SCREEN),
            new Change.Started(Step.MANUAL, 3, 11),
            new Change.Completed(Step.MANUAL),
            new Change.Started(Step.SELECT, 4, 11)),
        tutorial.tick(T0.plus(SignallerTutorial.SCREEN_READ)));

    Instant at = T0.plusSeconds(20);
    tutorial.event(Event.SELECTED, at);
    tutorial.event(Event.ROUTE_SET, at);
    tutorial.event(Event.HELD, at);
    tutorial.event(Event.RELEASED, at);
    tutorial.event(Event.AUTO_PASS_ON, at);
    tutorial.event(Event.AUTO_PASS_OFF, at);
    assertEquals(Step.ANSWER_CALL, tutorial.current());
    tutorial.event(Event.DRILL_WRONG, at);
    assertEquals(Step.ANSWER_CALL, tutorial.current(), "回错不算");
    tutorial.event(Event.DRILL_ANSWERED, at);
    assertEquals(Step.OFF_DUTY, tutorial.current());
    assertFalse(tutorial.finished());

    assertEquals(
        List.of(new Change.Completed(Step.OFF_DUTY), new Change.Finished()),
        tutorial.dutyEnded(SignallerShift.EndReason.OFF_DUTY, at));
    assertTrue(tutorial.finished());
    assertEquals(List.of(), tutorial.remaining());
  }

  @Test
  @DisplayName("先做的事也算：讲到那一步时当场完成")
  void earlierActionsCount() {
    SignallerTutorial tutorial = new SignallerTutorial();
    tutorial.begin(T0);
    tutorial.dutyStarted(true, true, T0);
    tutorial.event(Event.HELD, T0);
    tutorial.event(Event.RELEASED, T0);
    tutorial.tick(T0.plus(SignallerTutorial.SCREEN_READ));
    tutorial.event(Event.SELECTED, T0.plusSeconds(13));

    List<Change> changes = tutorial.event(Event.ROUTE_SET, T0.plusSeconds(14));

    assertTrue(changes.contains(new Change.Completed(Step.HOLD)));
    assertTrue(changes.contains(new Change.Completed(Step.RELEASE)));
    assertEquals(Step.AUTO_PASS, tutorial.current());
  }

  @Test
  @DisplayName("控制区没有车站：扣车、放行两步跳过；转为自动后人工一步要等转回人工")
  void noStationsSkipsHoldingAndManualWaitsForManual() {
    SignallerTutorial tutorial = new SignallerTutorial();
    tutorial.begin(T0);
    tutorial.dutyStarted(false, false, T0);
    List<Change> changes = new ArrayList<>(tutorial.tick(T0.plus(SignallerTutorial.SCREEN_READ)));
    assertEquals(Step.MANUAL, tutorial.current(), "上岗时图没就绪、暂为自动");
    changes.addAll(tutorial.event(Event.MANUAL, T0.plusSeconds(13)));
    changes.addAll(tutorial.event(Event.SELECTED, T0.plusSeconds(13)));
    changes.addAll(tutorial.event(Event.ROUTE_SET, T0.plusSeconds(13)));

    assertTrue(changes.contains(new Change.Skipped(Step.HOLD)));
    assertTrue(changes.contains(new Change.Skipped(Step.RELEASE)));
    assertTrue(changes.contains(new Change.Started(Step.AUTO_PASS, 6, 9)));
  }

  @Test
  @DisplayName("中途以别的方式下岗不算完成，列出还差的步骤；久未完成的一步每分钟再提示一次")
  void leavingEarlyIsNotFinishedAndStepsAreRemindedEveryMinute() {
    SignallerTutorial tutorial = new SignallerTutorial();
    tutorial.begin(T0);
    assertEquals(
        List.of(new Change.Reminder(Step.ON_DUTY)),
        tutorial.tick(T0.plus(SignallerTutorial.REMINDER)));
    tutorial.dutyStarted(true, true, T0.plusSeconds(60));
    tutorial.tick(T0.plusSeconds(60).plus(SignallerTutorial.SCREEN_READ));
    tutorial.event(Event.SELECTED, T0.plusSeconds(80));

    tutorial.dutyEnded(SignallerShift.EndReason.LEFT_CONSOLE, T0.plusSeconds(90));

    assertFalse(tutorial.finished());
    assertEquals(
        List.of(
            Step.SET_ROUTE,
            Step.HOLD,
            Step.RELEASE,
            Step.AUTO_PASS,
            Step.AUTO_PASS_OFF,
            Step.ANSWER_CALL,
            Step.OFF_DUTY),
        tutorial.remaining());
  }
}
