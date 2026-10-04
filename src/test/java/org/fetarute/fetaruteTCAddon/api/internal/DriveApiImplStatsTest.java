package org.fetarute.fetaruteTCAddon.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.api.drive.DriveApi;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveTaskRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("累计成绩")
class DriveApiImplStatsTest {

  private static DriveTaskRecord record(String state, int points, String grade) {
    return new DriveTaskRecord(
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        "p",
        UUID.randomUUID(),
        "R1-001",
        LocalDate.of(2026, 10, 3),
        "R1",
        "T-1",
        "MANUAL",
        state,
        points,
        grade,
        Instant.EPOCH,
        Instant.EPOCH,
        "{}");
  }

  @Test
  @DisplayName("任务数、开完数、总分、最好评级")
  void aggregates() {
    DriveApi.TaskStats stats =
        DriveApiImpl.statsOf(
            List.of(
                record("COMPLETED", 80, "B"),
                record("ABANDONED", 20, "D"),
                record("COMPLETED", 95, "A")));

    assertEquals(3, stats.tasks());
    assertEquals(2, stats.completed());
    assertEquals(195L, stats.totalPoints());
    assertEquals(Optional.of("A"), stats.bestGrade());
    assertEquals(Optional.empty(), DriveApiImpl.statsOf(List.of()).bestGrade());
  }
}
