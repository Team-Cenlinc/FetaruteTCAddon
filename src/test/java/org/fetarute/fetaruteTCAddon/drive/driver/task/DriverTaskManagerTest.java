package org.fetarute.fetaruteTCAddon.drive.driver.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;
import org.fetarute.fetaruteTCAddon.drive.seat.SeatBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("驾驶任务的领取与终态")
class DriverTaskManagerTest {

  private static final Instant NOW = Instant.parse("2026-10-03T08:00:00Z");
  private static final UUID TT = UUID.randomUUID();

  private final DriverTaskManager tasks = new DriverTaskManager(null, null);

  private static Player player(String name) {
    Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    when(player.getName()).thenReturn(name);
    return player;
  }

  private static TaskBoardEntries.Row row(String trip) {
    return new TaskBoardEntries.Row(
        new TaskKey(TT, trip, LocalDate.of(2026, 10, 3)),
        UUID.randomUUID(),
        "R1",
        3,
        "OP:S:STA:1",
        NOW.plusSeconds(120),
        false,
        false,
        "T-1",
        false);
  }

  @Test
  @DisplayName("一人一个任务、一个车次一名驾驶员；总开关与熔断挡住领取")
  void claimRules() {
    Player a = player("a");
    Player b = player("b");
    assertEquals(
        DriverTaskManager.ClaimOutcome.DISABLED,
        tasks.claim(a, row("X1"), "OP", "STA", "站", DrivingMode.MANUAL, false, NOW));
    assertEquals(
        DriverTaskManager.ClaimOutcome.CLAIMED,
        tasks.claim(a, row("X1"), "OP", "STA", "站", DrivingMode.ATO, true, NOW));
    assertEquals(
        DriverTaskManager.ClaimOutcome.ALREADY_HAS_TASK,
        tasks.claim(a, row("X2"), "OP", "STA", "站", DrivingMode.MANUAL, true, NOW));
    assertEquals(
        DriverTaskManager.ClaimOutcome.TAKEN,
        tasks.claim(b, row("x1"), "OP", "STA", "站", DrivingMode.MANUAL, true, NOW));
    assertTrue(tasks.claimFor(a.getUniqueId(), "t-1").isPresent());
    assertEquals(DrivingMode.ATO, tasks.claimFor(a.getUniqueId(), "T-1").orElseThrow().mode());

    assertTrue(tasks.abandon(a.getUniqueId(), "test"));
    assertEquals(
        DriverTaskManager.ClaimOutcome.CLAIMED,
        tasks.claim(b, row("X1"), "OP", "STA", "站", DrivingMode.MANUAL, true, NOW),
        "放弃后车次可被别人领取");
  }

  @Test
  @DisplayName("驾驶结束按原因定终态，已结束的不再改")
  void sessionEndStates() {
    Player a = player("a");
    tasks.claim(a, row("Y1"), "OP", "STA", "站", DrivingMode.MANUAL, true, NOW);
    tasks.onSessionStarted(a.getUniqueId(), "T-1", 100L);
    assertEquals(DriverTask.State.DRIVING, tasks.taskOf(a.getUniqueId()).orElseThrow().state());
    tasks.fail(a.getUniqueId(), "stuck");
    tasks.onSessionEnded(a.getUniqueId(), DriverTask.State.INTERRUPTED, "WATCHDOG");
    DriverTask task = tasks.taskOf(a.getUniqueId()).orElseThrow();
    assertEquals(DriverTask.State.FAILED, task.state());
    assertEquals("stuck", task.endReason());
  }

  @Test
  @DisplayName("坐进任务列车车头一端才提示确认接班；后半列车提示换座")
  void seatCheck() {
    assertEquals(
        DriverTaskManager.SeatCheck.NOT_ON_TRAIN, DriverTaskManager.checkSeat(null, "T1", 6));
    assertEquals(
        DriverTaskManager.SeatCheck.NOT_ON_TRAIN,
        DriverTaskManager.checkSeat(new SeatBinding("T2", 0, 0), "T1", 6));
    assertEquals(
        DriverTaskManager.SeatCheck.CONFIRM,
        DriverTaskManager.checkSeat(new SeatBinding("t1", 0, 0), "T1", 6));
    assertEquals(
        DriverTaskManager.SeatCheck.WRONG_SEAT,
        DriverTaskManager.checkSeat(new SeatBinding("T1", 5, 0), "T1", 6));
  }
}
