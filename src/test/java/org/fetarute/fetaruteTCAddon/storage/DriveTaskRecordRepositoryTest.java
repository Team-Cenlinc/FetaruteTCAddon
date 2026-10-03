package org.fetarute.fetaruteTCAddon.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveLeaderboardRow;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveTaskRecord;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveTaskRecordRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 驾驶任务记录表：往返、按玩家取最近记录、排行只算完成的任务并可按时间筛选。 */
class DriveTaskRecordRepositoryTest {

  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

  @TempDir Path dir;
  private TransitTestStorage storage;
  private DriveTaskRecordRepository records;

  @BeforeEach
  void setUp() throws Exception {
    storage = TransitTestStorage.open(dir);
    records = storage.provider().driveTaskRecords();
  }

  @AfterEach
  void tearDown() {
    storage.close();
  }

  private static DriveTaskRecord record(
      UUID player, String name, String state, int points, Instant finishedAt) {
    return new DriveTaskRecord(
        UUID.randomUUID(),
        null,
        player,
        name,
        UUID.randomUUID(),
        "1023",
        LocalDate.of(2026, 10, 3),
        "L1-A",
        "OP-L1-A01-0042",
        "MANUAL",
        state,
        points,
        "A",
        finishedAt.minusSeconds(600),
        finishedAt,
        "{\"formatVersion\":1}");
  }

  @Test
  void roundTripAndLeaderboard() {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    DriveTaskRecord first = record(alice, "alice", "COMPLETED", 90, NOW.minusSeconds(3600));
    records.save(first);
    records.save(record(alice, "alice", "COMPLETED", 80, NOW));
    records.save(record(alice, "alice", "FAILED", 40, NOW));
    records.save(record(bob, "bob", "COMPLETED", 95, NOW.minus(10, ChronoUnit.DAYS)));

    List<DriveTaskRecord> mine = records.listByPlayer(alice, 10);
    assertEquals(3, mine.size());
    assertEquals(first, mine.get(2), "最旧的在最后，且各字段原样读回");

    List<DriveLeaderboardRow> all = records.leaderboard(null, 10);
    assertEquals(List.of(alice, bob), all.stream().map(DriveLeaderboardRow::playerId).toList());
    assertEquals(170L, all.get(0).totalPoints(), "只算完成的任务");
    assertEquals(2, all.get(0).tasks());

    List<DriveLeaderboardRow> week = records.leaderboard(NOW.minus(7, ChronoUnit.DAYS), 10);
    assertEquals(List.of(alice), week.stream().map(DriveLeaderboardRow::playerId).toList());
  }
}
