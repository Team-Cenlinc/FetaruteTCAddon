package org.fetarute.fetaruteTCAddon.drive.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTask;
import org.fetarute.fetaruteTCAddon.drive.driver.task.DriverTaskManager;
import org.fetarute.fetaruteTCAddon.drive.driver.task.TaskKey;
import org.fetarute.fetaruteTCAddon.drive.seat.CabSeats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("车掌任务：一个车次一名车掌、一名玩家一个任务；到接班站上岗、开过或等不到作废；始发站扣车等车掌")
class GuardTasksTest {

  private static final Instant T0 = Instant.parse("2026-10-09T08:00:00Z");
  private static final UUID TIMETABLE = UUID.randomUUID();

  private static TaskKey key(String trip) {
    return new TaskKey(TIMETABLE, trip, LocalDate.of(2026, 10, 9));
  }

  private static DriverTaskManager.TaskSpec spec(String trip, int takeover) {
    return new DriverTaskManager.TaskSpec(
        key(trip),
        "WS-2N",
        "SURC",
        "HHU",
        "环湖",
        "SURC:S:HHU:2",
        takeover,
        T0,
        null,
        -1,
        "",
        "",
        false,
        GuardTask.SOURCE_BOARD,
        Map.of(),
        true);
  }

  @Test
  @DisplayName("领取：一名玩家一个未结束的任务，一个车次一名车掌，取消或终到的车次不能领，事件可取消")
  void claimRules() {
    GuardTasks tasks = new GuardTasks();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    assertEquals(
        GuardTasks.ClaimOutcome.CLAIMED, tasks.register(a, "A", spec("1001", 3), false, T0));
    assertEquals(
        GuardTasks.ClaimOutcome.ALREADY_HAS_TASK,
        tasks.register(a, "A", spec("1002", 3), false, T0));
    assertEquals(GuardTasks.ClaimOutcome.TAKEN, tasks.register(b, "B", spec("1001", 3), false, T0));
    assertEquals(
        GuardTasks.ClaimOutcome.UNAVAILABLE, tasks.register(b, "B", spec("1003", 3), true, T0));
    tasks.setBeforeClaim(task -> false);
    assertEquals(
        GuardTasks.ClaimOutcome.CANCELLED, tasks.register(b, "B", spec("1004", 3), false, T0));
    assertTrue(tasks.taskOf(b).isEmpty(), "被取消的不登记");
    assertEquals("A", tasks.claimants().get(key("1001")).playerName());

    tasks.dropClaim(a, GuardTask.State.ABANDONED, "command");
    assertTrue(tasks.activeTaskOf(a).isEmpty());
    assertTrue(tasks.taskOf(a).isPresent(), "结束的任务留着，直到被新任务替换");
    assertTrue(tasks.claimants().isEmpty(), "放弃后名额空出");
    tasks.setBeforeClaim(null);
    assertEquals(
        GuardTasks.ClaimOutcome.CLAIMED, tasks.register(b, "B", spec("1001", 3), false, T0));
  }

  @Test
  @DisplayName("对上列车：停在接班站上岗，开过接班站作废，还没到时等着（晚点也等）")
  void stepFollowsTheTrain() {
    GuardTask task = new GuardTask(UUID.randomUUID(), "A", spec("1001", 3), T0);
    Instant late = T0.plus(GuardTasks.EXPIRE_AFTER).plusSeconds(1);
    assertEquals(GuardTasks.Step.BOARD, GuardTasks.step(task, true, 3, true, T0));
    assertEquals(GuardTasks.Step.WAIT, GuardTasks.step(task, true, 3, false, T0), "到站还没停稳");
    assertEquals(GuardTasks.Step.EXPIRE_DEPARTED, GuardTasks.step(task, true, 4, false, T0));
    assertEquals(GuardTasks.Step.WAIT, GuardTasks.step(task, true, 1, false, late), "在路上：晚点也等");
    assertEquals(GuardTasks.Step.WAIT, GuardTasks.step(task, false, -1, false, T0));
    assertEquals(GuardTasks.Step.EXPIRE_TIMEOUT, GuardTasks.step(task, false, -1, false, late));
    task.hold("T-1", late.plusSeconds(60), CabSeats.Departure.HEAD);
    assertEquals(
        GuardTasks.Step.WAIT, GuardTasks.step(task, false, -1, false, late), "始发站扣车时由派车侧判定");
  }

  @Test
  @DisplayName("始发站：车掌在线才扣车，坐好之前扣着（其他候选车也不派），到时限放行作废；上岗后只派车掌坐的那一列；不是始发站不扣")
  void layoverHoldsForTheGuard() {
    GuardTask task = new GuardTask(UUID.randomUUID(), "A", spec("1001", 0), T0);
    Instant deadline = T0.plus(Duration.ofSeconds(90));
    assertEquals(GuardTasks.HoldVerdict.DISPATCH, GuardTasks.layover(null, "T-1", true, T0));
    assertEquals(GuardTasks.HoldVerdict.DISPATCH, GuardTasks.layover(task, "T-1", false, T0));
    assertEquals(GuardTasks.HoldVerdict.START, GuardTasks.layover(task, "T-1", true, T0));
    task.hold("T-1", deadline, CabSeats.Departure.TAIL);
    assertEquals(GuardTasks.HoldVerdict.HOLD, GuardTasks.layover(task, "t-1", true, T0));
    assertEquals(
        GuardTasks.HoldVerdict.HOLD, GuardTasks.layover(task, "T-2", true, T0), "其他候选车也不派");
    assertEquals(GuardTasks.HoldVerdict.GIVE_UP, GuardTasks.layover(task, "T-1", true, deadline));
    assertEquals(GuardTasks.HoldVerdict.GIVE_UP, GuardTasks.layover(task, "T-1", false, T0), "下线");

    task.start("T-1");
    assertEquals(GuardTasks.HoldVerdict.DISPATCH, GuardTasks.layover(task, "T-1", true, T0));
    assertEquals(
        GuardTasks.HoldVerdict.HOLD, GuardTasks.layover(task, "T-2", true, T0), "车掌坐的是 T-1");
    assertEquals(GuardTasks.HoldVerdict.DISPATCH, GuardTasks.layover(task, "T-2", true, deadline));
    GuardTask midway = new GuardTask(UUID.randomUUID(), "B", spec("1002", 2), T0);
    assertEquals(GuardTasks.HoldVerdict.DISPATCH, GuardTasks.layover(midway, "T-3", true, T0));
  }

  @Test
  @DisplayName("扣着等车掌的列车不派去跑别的车次；到时限或已上岗后不再拦")
  void aHeldTrainIsNotGivenToAnotherTrip() {
    GuardTasks tasks = new GuardTasks();
    UUID a = UUID.randomUUID();
    tasks.register(a, "A", spec("1001", 0), false, T0);
    GuardTask task = tasks.activeTaskOf(a).orElseThrow();
    Instant deadline = T0.plus(Duration.ofSeconds(90));
    task.hold("T-1", deadline, CabSeats.Departure.HEAD);
    assertTrue(tasks.heldForOther("t-1", key("2002"), T0));
    assertFalse(tasks.heldForOther("T-1", key("1001"), T0), "自己这一班");
    assertFalse(tasks.heldForOther("T-9", key("2002"), T0));
    assertFalse(tasks.heldForOther("T-1", key("2002"), deadline));
    task.start("T-1");
    assertFalse(tasks.heldForOther("T-1", key("2002"), T0));
  }

  @Test
  @DisplayName("始发站车掌坐下一趟发车端的另一头，分不出或单节车两头都行；任务终态跟着一趟的终态")
  void cabAndStates() {
    assertTrue(GuardSessionManager.layoverCab(CabSeats.End.HEAD, CabSeats.Departure.TAIL, 6));
    assertFalse(GuardSessionManager.layoverCab(CabSeats.End.TAIL, CabSeats.Departure.TAIL, 6));
    assertTrue(GuardSessionManager.layoverCab(CabSeats.End.TAIL, CabSeats.Departure.HEAD, 6));
    assertTrue(GuardSessionManager.layoverCab(CabSeats.End.HEAD, CabSeats.Departure.EITHER, 6));
    assertTrue(GuardSessionManager.layoverCab(CabSeats.End.HEAD, CabSeats.Departure.HEAD, 1));
    assertFalse(GuardSessionManager.layoverCab(CabSeats.End.NONE, CabSeats.Departure.EITHER, 6));
    assertEquals(CabSeats.End.TAIL, GuardSessionManager.guardEndFor(null));
    assertEquals(CabSeats.End.HEAD, GuardSessionManager.guardEndFor(CabSeats.Departure.TAIL));
    assertEquals(CabSeats.End.NONE, GuardSessionManager.guardEndFor(CabSeats.Departure.EITHER));
    assertEquals(
        GuardTask.State.COMPLETED, GuardSessionManager.taskState(DriverTask.State.COMPLETED));
    assertEquals(GuardTask.State.FAILED, GuardSessionManager.taskState(DriverTask.State.FAILED));
    assertEquals(
        GuardTask.State.INTERRUPTED, GuardSessionManager.taskState(DriverTask.State.DRIVING));
    assertEquals(
        DriverTask.State.COMPLETED, GuardTrip.stateFor(GuardSession.EndReason.HANDOVER), "交班算开完");
  }
}
