package org.fetarute.fetaruteTCAddon.drive.driver.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi;
import org.fetarute.fetaruteTCAddon.api.timetable.TimetableApi;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;
import org.fetarute.fetaruteTCAddon.drive.driver.score.StopScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("驾驶任务转成公开 API 快照")
class TaskViewsTest {

  private static final UUID TT = UUID.randomUUID();
  private static final LocalDate DAY = LocalDate.of(2026, 10, 3);

  private static DriverTask task() {
    DriverTask task =
        new DriverTask(
            UUID.randomUUID(),
            "a",
            new TaskKey(TT, "R1-001", DAY),
            "R1",
            "OP",
            "AAA",
            "A 站",
            "OP:S:AAA:1",
            1,
            Instant.EPOCH,
            DrivingMode.ATO,
            Instant.EPOCH);
    task.setHandover(4, "CCC", "C 站");
    task.setSource("typewriter", Map.of("quest", "q1"));
    task.setTrainName("T-1");
    return task;
  }

  @Test
  @DisplayName("任务快照带接班站、交班站、来源与附加数据")
  void taskView() {
    DriverTask task = task();
    DriveApi.TaskView view = TaskViews.of(task);

    assertEquals(task.taskId(), view.taskId());
    assertEquals(new DriveApi.StationRef("AAA", "A 站", 1), view.takeoverStation());
    assertEquals(Optional.of(new DriveApi.StationRef("CCC", "C 站", 4)), view.handoverStation());
    assertEquals(DriveApi.Mode.ATO, view.mode());
    assertEquals(DriveApi.TaskState.CLAIMED, view.state());
    assertEquals(Optional.of("T-1"), view.trainName());
    assertEquals("typewriter", view.source());
    assertEquals(Map.of("quest", "q1"), view.metadata());
    assertTrue(view.points().isEmpty());
    assertTrue(view.grade().isEmpty());

    task.finish(DriverTask.State.COMPLETED, "alight");
    task.setResult(88, "A");
    DriveApi.TaskView finished = TaskViews.of(task);
    assertEquals(DriveApi.TaskState.COMPLETED, finished.state());
    assertEquals(88, finished.points().getAsInt());
    assertEquals(Optional.of("A"), finished.grade());
  }

  @Test
  @DisplayName("停站成绩与驾驶方式一一对应")
  void stopAndMode() {
    DriveApi.StopResult stop =
        TaskViews.stop(new StopScore("B 站", 3.5, StopAlignment.Outcome.OVERRUN, true, false));

    assertEquals(DriveApi.StopOutcome.OVERRUN, stop.outcome());
    assertEquals(3.5, stop.offsetBlocks(), 1.0e-9);
    assertTrue(stop.wrongDoor());
    assertFalse(stop.doorsTakenOver());
    assertEquals(DrivingMode.ATO, TaskViews.mode(DriveApi.Mode.ATO));
    assertEquals(DriveApi.Mode.MANUAL, TaskViews.mode(DrivingMode.MANUAL));
  }

  @Test
  @DisplayName("区间任务：停过或越过交班站才算到站，且只认这一班")
  void reachedHandover() {
    TaskKey key = new TaskKey(TT, "R1-001", DAY);

    assertFalse(DriverTaskManager.reachedHandover(4, key, assignment("R1-001", 3)));
    assertTrue(DriverTaskManager.reachedHandover(4, key, assignment("R1-001", 4)));
    assertTrue(DriverTaskManager.reachedHandover(4, key, assignment("R1-001", 5)));
    assertFalse(DriverTaskManager.reachedHandover(4, key, assignment("R1-002", 5)));
  }

  private static TimetableApi.TrainAssignment assignment(String trip, int lastStop) {
    return new TimetableApi.TrainAssignment(
        "T-1",
        TT,
        trip,
        UUID.randomUUID(),
        Optional.empty(),
        DAY,
        Instant.EPOCH,
        0L,
        Optional.of(lastStop),
        Optional.empty(),
        Optional.empty(),
        OptionalLong.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        OptionalLong.empty());
  }
}
