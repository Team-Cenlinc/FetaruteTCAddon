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
    return record(player, name, state, points, "A", finishedAt);
  }

  private static DriveTaskRecord record(
      UUID player, String name, String state, int points, String grade, Instant finishedAt) {
    return record(player, name, "MANUAL", state, points, grade, finishedAt);
  }

  private static DriveTaskRecord record(
      UUID player,
      String name,
      String mode,
      String state,
      int points,
      String grade,
      Instant finishedAt) {
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
        mode,
        state,
        points,
        grade,
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

  @Test
  void totalsAreSummedInTheDatabase() {
    UUID carol = UUID.randomUUID();
    assertEquals(
        new DriveTaskRecordRepository.PlayerTotals(0, 0, 0L, ""), records.totalsByPlayer(carol));

    records.save(record(carol, "carol", "COMPLETED", 90, "A", NOW));
    records.save(record(carol, "carol", "COMPLETED", 70, "B", NOW));
    records.save(record(carol, "carol", "ABANDONED", 40, "S", NOW));
    records.save(record(carol, "carol", "FAILED", 30, "", NOW));
    records.save(record(UUID.randomUUID(), "dave", "COMPLETED", 99, "S", NOW));

    assertEquals(
        new DriveTaskRecordRepository.PlayerTotals(4, 2, 160L, "S"),
        records.totalsByPlayer(carol),
        "总分只算开完的任务；最好评级不看终态，空评级不算");
  }

  /** 车掌值乘的记录照样能按玩家列出，但不进驾驶排行与驾驶员的汇总。 */
  @Test
  void guardRecordsStayOutOfTheDriverTotals() {
    UUID erin = UUID.randomUUID();
    records.save(record(erin, "erin", "COMPLETED", 80, "B", NOW));
    records.save(record(erin, "erin", DriveTaskRecord.MODE_GUARD, "COMPLETED", 100, "S", NOW));
    assertEquals(2, records.listByPlayer(erin, 10).size());
    assertEquals(
        new DriveTaskRecordRepository.PlayerTotals(1, 1, 80L, "B"), records.totalsByPlayer(erin));
    assertEquals(80L, records.leaderboard(null, 10).get(0).totalPoints());
  }

  /** 车掌的记录与汇总单独查：只算 GUARD，口径同驾驶员。 */
  @Test
  void guardRecordsAreListedAndSummedByMode() {
    UUID fay = UUID.randomUUID();
    records.save(record(fay, "fay", "COMPLETED", 80, "B", NOW));
    DriveTaskRecord older =
        record(fay, "fay", DriveTaskRecord.MODE_GUARD, "COMPLETED", 90, "A", NOW.minusSeconds(60));
    records.save(older);
    records.save(record(fay, "fay", DriveTaskRecord.MODE_GUARD, "FAILED", 50, "D", NOW));
    List<DriveTaskRecord> guard = records.listByPlayerAndMode(fay, DriveTaskRecord.MODE_GUARD, 10);
    assertEquals(2, guard.size());
    assertEquals(older, guard.get(1), "新的在前");
    assertEquals(1, records.listByPlayerAndMode(fay, DriveTaskRecord.MODE_GUARD, 1).size());
    assertEquals(
        new DriveTaskRecordRepository.PlayerTotals(2, 1, 90L, "A"),
        records.totalsByPlayerAndMode(fay, DriveTaskRecord.MODE_GUARD));
    assertEquals(
        new DriveTaskRecordRepository.PlayerTotals(1, 1, 80L, "B"), records.totalsByPlayer(fay));
  }
}
