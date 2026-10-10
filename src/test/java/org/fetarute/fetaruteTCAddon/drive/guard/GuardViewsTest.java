package org.fetarute.fetaruteTCAddon.drive.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.bukkit.event.HandlerList;
import org.fetarute.fetaruteTCAddon.api.drive.GuardApi;
import org.fetarute.fetaruteTCAddon.api.event.GuardDepartureSignalEvent;
import org.fetarute.fetaruteTCAddon.api.event.GuardDutyEndedEvent;
import org.fetarute.fetaruteTCAddon.api.event.GuardDutyStartEvent;
import org.fetarute.fetaruteTCAddon.api.event.GuardEmergencyStopEvent;
import org.fetarute.fetaruteTCAddon.api.event.GuardIncidentReportEvent;
import org.fetarute.fetaruteTCAddon.api.event.GuardStopWorkedEvent;
import org.fetarute.fetaruteTCAddon.api.event.GuardTaskClaimEvent;
import org.fetarute.fetaruteTCAddon.api.event.GuardTaskFinishedEvent;
import org.fetarute.fetaruteTCAddon.api.event.GuardTripScoredEvent;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTaskManager;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("车掌的公开快照：任务、作业、成绩与事件")
class GuardViewsTest {

  private static final UUID TT = UUID.randomUUID();
  private static final Instant T0 = Instant.parse("2026-10-09T08:00:00Z");

  private static GuardTask task(int handover) {
    return new GuardTask(
        UUID.randomUUID(),
        "A",
        new DriverTaskManager.TaskSpec(
            new TaskKey(TT, "1001", LocalDate.of(2026, 10, 9)),
            "WS-2N",
            "SURC",
            "HHU",
            "环湖",
            "SURC:S:HHU:2",
            3,
            T0,
            null,
            handover,
            handover >= 0 ? "KPO" : "",
            handover >= 0 ? "康平" : "",
            false,
            "quest",
            Map.of("id", "7"),
            false),
        T0);
  }

  @Test
  @DisplayName("任务快照：接班站、交班站、来源与附加数据、终态与成绩")
  void taskView() {
    GuardTask task = task(6);
    GuardApi.TaskView view = GuardViews.of(task);
    assertEquals("1001", view.tripCode());
    assertEquals("HHU", view.takeoverStation().stationCode());
    assertEquals(3, view.takeoverStation().stopSequence());
    assertEquals("康平", view.handoverStation().orElseThrow().name());
    assertEquals(GuardApi.TaskState.CLAIMED, view.state());
    assertEquals("quest", view.source());
    assertEquals(Map.of("id", "7"), view.metadata());
    assertTrue(view.points().isEmpty());

    task.start("T-1");
    task.setResult(88, "A");
    task.finish(GuardTask.State.COMPLETED, "COMPLETED");
    GuardApi.TaskView done = GuardViews.of(task);
    assertEquals(GuardApi.TaskState.COMPLETED, done.state());
    assertEquals(Optional.of("T-1"), done.trainName());
    assertEquals(OptionalInt.of(88), done.points());
    assertEquals(Optional.of("A"), done.grade());
    assertTrue(GuardViews.of(task(-1)).handoverStation().isEmpty());
  }

  @Test
  @DisplayName("内部状态与原因都有对应的公开写法")
  void enumsLineUp() {
    for (GuardTask.State state : GuardTask.State.values()) {
      assertEquals(state.finished(), GuardApi.TaskState.valueOf(state.name()).finished());
    }
    for (GuardSessionManager.IncidentReason reason : GuardSessionManager.IncidentReason.values()) {
      assertEquals(reason.name(), GuardViews.incident(reason).name());
    }
  }

  @Test
  @DisplayName("作业与成绩：各项照搬，里程换算成公里")
  void workAndScore() {
    GuardScore.Stop stop =
        new GuardScore.Stop(
            "环湖", false, true, false, true, false, Optional.of(true), Optional.empty(), 2);
    GuardApi.StopWork work = GuardViews.work(stop);
    assertTrue(work.forcedClose());
    assertTrue(work.wrongDoor());
    assertTrue(work.timedOut());
    assertEquals(Optional.of(true), work.closingWatch());
    assertEquals(2, work.incidents());
    GuardScore score = new GuardScore();
    score.add(stop);
    ScoreRules.Result result = score.evaluate(true);
    GuardApi.TripScore trip = GuardViews.score(score, result, 2500.0);
    assertEquals(result.points(), trip.points());
    assertEquals(result.grade().name(), trip.grade());
    assertEquals(List.of(work), trip.stops());
    assertEquals(2.5, trip.kilometres());
  }

  @Test
  @DisplayName("每个车掌事件都有 Bukkit 要求的静态 getHandlerList")
  void everyEventHasAHandlerList() throws Exception {
    for (Class<?> type :
        List.of(
            GuardTaskClaimEvent.class,
            GuardTaskFinishedEvent.class,
            GuardDutyStartEvent.class,
            GuardDutyEndedEvent.class,
            GuardStopWorkedEvent.class,
            GuardDepartureSignalEvent.class,
            GuardEmergencyStopEvent.class,
            GuardIncidentReportEvent.class,
            GuardTripScoredEvent.class)) {
      Method method = type.getMethod("getHandlerList");
      assertTrue(Modifier.isStatic(method.getModifiers()), type.getSimpleName());
      assertNotNull((HandlerList) method.invoke(null), type.getSimpleName());
    }
  }
}
