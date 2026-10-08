package org.fetarute.fetaruteTCAddon.drive.driver.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("终点站结算后接续下一趟：留班与当场记成任务")
class DriverTaskContinuationTest {

  private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");
  private static final UUID TT = UUID.randomUUID();
  private static final LocalDate DAY = LocalDate.of(2026, 10, 4);

  private final DriverTaskManager tasks = new DriverTaskManager(null, null);

  private static Player player(String name) {
    Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    when(player.getName()).thenReturn(name);
    return player;
  }

  private static TaskKey key(String trip) {
    return new TaskKey(TT, trip, DAY);
  }

  private static DriverTaskManager.TaskSpec spec(String trip, String source) {
    return new DriverTaskManager.TaskSpec(
        key(trip),
        "R1",
        "OP",
        "STA",
        "站",
        "OP:S:STA:1",
        0,
        NOW,
        "T-1",
        -1,
        null,
        null,
        false,
        source,
        Map.of(),
        true);
  }

  private static TaskBoardEntries.Row row(String trip) {
    return new TaskBoardEntries.Row(
        key(trip), UUID.randomUUID(), "R1", 0, "OP:S:STA:1", NOW, false, false, "T-1", false);
  }

  @Test
  @DisplayName("留下的班次在任务板上显示为留给的人，别人领不走、留不走")
  void reservationBlocksOthers() {
    Player a = player("a");
    Player b = player("b");
    assertTrue(tasks.reserve(a.getUniqueId(), "a", key("N1")));
    assertTrue(tasks.reserve(a.getUniqueId(), "a", key("N1")), "同一人重复留无妨");
    assertTrue(tasks.takenKeys().contains(key("N1")));
    assertEquals(a.getUniqueId(), tasks.claimants().get(key("N1")).playerId());
    assertFalse(tasks.reserve(b.getUniqueId(), "b", key("N1")));
    assertEquals(
        DriverTaskManager.ClaimOutcome.TAKEN,
        tasks.claim(b, row("N1"), "OP", "STA", "站", DrivingMode.MANUAL, true, NOW));

    assertEquals(java.util.Optional.of(key("N1")), tasks.reservationOf(a.getUniqueId()));
    assertTrue(tasks.reservationOf(b.getUniqueId()).isEmpty());

    tasks.releaseReservation(a.getUniqueId());
    assertTrue(tasks.reservationOf(a.getUniqueId()).isEmpty());
    assertFalse(tasks.takenKeys().contains(key("N1")));
    assertEquals(
        DriverTaskManager.ClaimOutcome.CLAIMED,
        tasks.claim(b, row("N1"), "OP", "STA", "站", DrivingMode.MANUAL, true, NOW));
  }

  @Test
  @DisplayName("已被别人领走的班次留不下")
  void cannotReserveClaimed() {
    Player a = player("a");
    Player b = player("b");
    tasks.claim(b, row("N2"), "OP", "STA", "站", DrivingMode.MANUAL, true, NOW);
    assertFalse(tasks.reserve(a.getUniqueId(), "a", key("N2")));
  }

  @Test
  @DisplayName("开出下一趟时当场记成驾驶中的任务，并放掉留着的班次")
  void adoptStartsDriving() {
    Player a = player("a");
    tasks.reserve(a.getUniqueId(), "a", key("N3"));
    assertEquals(
        DriverTaskManager.ClaimOutcome.CLAIMED,
        tasks.adopt(a, spec("N3", DriverTask.SOURCE_CONTINUATION), DrivingMode.MANUAL, NOW, 200L));
    DriverTask task = tasks.activeTaskOf(a.getUniqueId()).orElseThrow();
    assertEquals(DriverTask.State.DRIVING, task.state());
    assertEquals(DriverTask.SOURCE_CONTINUATION, task.source());
    assertEquals("T-1", task.trainName());
    assertEquals(200L, task.startedTick());
    assertEquals(a.getUniqueId(), tasks.claimants().get(key("N3")).playerId());

    assertEquals(
        DriverTaskManager.ClaimOutcome.ALREADY_HAS_TASK,
        tasks.adopt(a, spec("N4", DriverTask.SOURCE_CONTINUATION), DrivingMode.MANUAL, NOW, 300L),
        "驾驶中的任务没结束不能再记一趟");
  }

  @Test
  @DisplayName("上一趟完成后接着记下一趟；别人领走的班次记不成")
  void adoptAfterCompletion() {
    Player a = player("a");
    Player b = player("b");
    tasks.adopt(a, spec("M1", DriverTask.SOURCE_TAKEOVER), DrivingMode.ATO, NOW, 1L);
    tasks.complete(a.getUniqueId());
    assertEquals(DriverTask.State.COMPLETED, tasks.taskOf(a.getUniqueId()).orElseThrow().state());

    tasks.claim(b, row("M2"), "OP", "STA", "站", DrivingMode.MANUAL, true, NOW);
    assertEquals(
        DriverTaskManager.ClaimOutcome.TAKEN,
        tasks.adopt(a, spec("M2", DriverTask.SOURCE_CONTINUATION), DrivingMode.MANUAL, NOW, 2L));
    assertEquals(
        DriverTaskManager.ClaimOutcome.CLAIMED,
        tasks.adopt(a, spec("M3", DriverTask.SOURCE_CONTINUATION), DrivingMode.MANUAL, NOW, 3L));
  }
}
