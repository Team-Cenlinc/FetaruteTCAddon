package org.fetarute.fetaruteTCAddon.drive.driver.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.fetarute.fetaruteTCAddon.drive.driver.DrivingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("任务板上已被领取的车次")
class TaskBoardClaimedEntriesTest {

  private static final UUID TT = UUID.randomUUID();
  private static final LocalDate DAY = LocalDate.of(2026, 10, 4);
  private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");

  private static TaskBoardEntries.Row row(String trip, long offsetSeconds, boolean dwelling) {
    return new TaskBoardEntries.Row(
        new TaskKey(TT, trip, DAY),
        UUID.randomUUID(),
        "R1",
        2,
        "OP:S:STA:1",
        NOW.plusSeconds(offsetSeconds),
        false,
        false,
        dwelling ? "T-" + trip : null,
        dwelling);
  }

  private static List<String> trips(List<TaskBoardEntries.Entry> entries) {
    return entries.stream().map(entry -> entry.row().key().tripCode()).toList();
  }

  @Test
  @DisplayName("已被领取的不隐藏、标上领取人，顺序与可领清单相同")
  void claimedTripsStayOnTheBoard() {
    UUID other = UUID.randomUUID();
    List<TaskBoardEntries.Row> rows =
        List.of(row("A", 300, false), row("B", 120, false), row("C", -30, true));
    Map<TaskKey, TaskBoardEntries.Claimant> claimants =
        Map.of(new TaskKey(TT, "b", DAY), new TaskBoardEntries.Claimant(other, "Alex"));

    List<TaskBoardEntries.Entry> board = TaskBoardEntries.board(rows, claimants, NOW, 45);

    assertEquals(List.of("C", "B", "A"), trips(board));
    TaskBoardEntries.Entry taken = board.get(1);
    assertTrue(taken.claimed());
    assertEquals("Alex", taken.claimant().playerName());
    assertTrue(taken.claimedBy(other));
    assertFalse(taken.claimedBy(UUID.randomUUID()));
    assertFalse(board.get(0).claimed());
    assertNull(board.get(0).trip());

    assertEquals(
        List.of("C", "A"),
        TaskBoardEntries.select(rows, claimants.keySet(), NOW, 45).stream()
            .map(row -> row.key().tripCode())
            .toList(),
        "可领清单（对外接口）仍去掉已被领取的");
  }

  @Test
  @DisplayName("任务板的条数上限把已被领取的也算在内")
  void limitCountsClaimedEntries() {
    List<TaskBoardEntries.Row> rows =
        List.of(row("A", 60, false), row("B", 120, false), row("C", 180, false));
    Map<TaskKey, TaskBoardEntries.Claimant> claimants =
        Map.of(new TaskKey(TT, "A", DAY), new TaskBoardEntries.Claimant(UUID.randomUUID(), "Alex"));
    assertEquals(List.of("A", "B"), trips(TaskBoardEntries.board(rows, claimants, NOW, 2)));
  }

  @Test
  @DisplayName("补上行程概要不改领取人")
  void withTripKeepsClaimant() {
    TaskBoardEntries.Claimant claimant = new TaskBoardEntries.Claimant(UUID.randomUUID(), "Alex");
    TaskBoardEntries.Entry entry = new TaskBoardEntries.Entry(row("A", 60, false), null, claimant);
    TaskBoardEntries.Entry withTrip = entry.withTrip(new TaskBoardEntries.Trip("终点", 5, 900));
    assertEquals(claimant, withTrip.claimant());
    assertEquals(5, withTrip.trip().stopCount());
    assertEquals("", new TaskBoardEntries.Trip(null, 1, -1).destination());
  }

  @Test
  @DisplayName("任务板格子：已被领取的格子不能再领")
  void holderHidesClaimedRows() {
    TaskBoardEntries.Entry open = new TaskBoardEntries.Entry(row("A", 60, false), null, null);
    TaskBoardEntries.Entry taken =
        new TaskBoardEntries.Entry(
            row("B", 90, false), null, new TaskBoardEntries.Claimant(UUID.randomUUID(), "Alex"));
    TaskBoardHolder holder =
        new TaskBoardHolder(UUID.randomUUID(), "OP", "STA", "站", List.of(open, taken));
    assertTrue(holder.rowAt(0).isPresent());
    assertTrue(holder.entryAt(1).isPresent());
    assertTrue(holder.rowAt(1).isEmpty(), "点击已被领取的格子不起作用");
    assertTrue(holder.entryAt(2).isEmpty());
  }

  @Test
  @DisplayName("任务管理器给出领取人：结束的任务不再占着车次")
  void managerReportsClaimants() {
    DriverTaskManager tasks = new DriverTaskManager(null, null);
    Player alex = mock(Player.class);
    UUID alexId = UUID.randomUUID();
    when(alex.getUniqueId()).thenReturn(alexId);
    when(alex.getName()).thenReturn("Alex");
    tasks.claim(alex, row("A", 60, false), "OP", "STA", "站", DrivingMode.MANUAL, true, NOW);

    Map<TaskKey, TaskBoardEntries.Claimant> claimants = tasks.claimants();
    assertEquals(Set.of(new TaskKey(TT, "A", DAY)), claimants.keySet());
    assertEquals(
        new TaskBoardEntries.Claimant(alexId, "Alex"), claimants.values().iterator().next());

    tasks.abandon(alexId, "test");
    assertTrue(tasks.claimants().isEmpty());
  }
}
