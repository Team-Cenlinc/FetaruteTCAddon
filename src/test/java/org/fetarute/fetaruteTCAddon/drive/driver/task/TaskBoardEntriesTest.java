package org.fetarute.fetaruteTCAddon.drive.driver.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("任务板列哪些车次")
class TaskBoardEntriesTest {

  private static final UUID TT = UUID.randomUUID();
  private static final LocalDate DAY = LocalDate.of(2026, 10, 3);
  private static final Instant NOW = Instant.parse("2026-10-03T08:00:00Z");

  private static TaskBoardEntries.Row row(
      String trip, long offsetSeconds, boolean terminating, boolean cancelled, boolean dwelling) {
    return new TaskBoardEntries.Row(
        new TaskKey(TT, trip, DAY),
        UUID.randomUUID(),
        "R1",
        2,
        "OP:S:STA:1",
        NOW.plusSeconds(offsetSeconds),
        terminating,
        cancelled,
        dwelling ? "T-" + trip : null,
        dwelling);
  }

  @Test
  @DisplayName("去掉取消、终到、已被领走、早已开走的；停站中的排最前")
  void filtersAndSorts() {
    List<TaskBoardEntries.Row> rows =
        List.of(
            row("A", 300, false, false, false),
            row("B", 120, false, false, false),
            row("C", -30, false, false, true),
            row("D", 60, true, false, false),
            row("E", 90, false, true, false),
            row("F", -120, false, false, false),
            row("G", 30, false, false, false));

    List<TaskBoardEntries.Row> selected =
        TaskBoardEntries.select(rows, Set.of(new TaskKey(TT, "g", DAY)), NOW, 45);

    assertEquals(List.of("C", "B", "A"), selected.stream().map(r -> r.key().tripCode()).toList());
  }

  @Test
  @DisplayName("同一车次在本站出现两次时取较早的一次，并受条数上限约束")
  void dedupesAndLimits() {
    List<TaskBoardEntries.Row> rows =
        List.of(
            row("A", 600, false, false, false),
            row("A", 60, false, false, false),
            row("B", 30, false, false, false));
    List<TaskBoardEntries.Row> selected = TaskBoardEntries.select(rows, Set.of(), NOW, 1);
    assertEquals(1, selected.size());
    assertEquals("B", selected.get(0).key().tripCode());
    assertEquals(
        NOW.plusSeconds(60),
        TaskBoardEntries.select(rows, Set.of(), NOW, 45).get(1).plannedDeparture());
  }
}
