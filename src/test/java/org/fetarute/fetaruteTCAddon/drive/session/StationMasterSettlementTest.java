package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.crew.StationMasterScore;
import org.fetarute.fetaruteTCAddon.drive.crew.StationMasterShift;
import org.fetarute.fetaruteTCAddon.drive.driver.DriveRewardConfig;
import org.fetarute.fetaruteTCAddon.drive.driver.DriveRewards;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveTaskRecord;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("站长班次：成绩、记录、奖励与提示")
class StationMasterSettlementTest {

  private static final UUID PLAYER = UUID.randomUUID();
  private static final Instant START = Instant.parse("2026-10-20T08:00:00Z");

  private static final class FakeHost implements SettlementHost {
    final List<DriveRewards.Reward> paid = new ArrayList<>();
    final List<DriveTaskRecord> records = new ArrayList<>();
    final List<String> told = new ArrayList<>();

    @Override
    public DriveRewardConfig rewards() {
      return DriveRewardConfig.defaults();
    }

    @Override
    public DriveRewardPayer.Paid pay(UUID playerId, String playerName, DriveRewards.Reward reward) {
      paid.add(reward);
      return new DriveRewardPayer.Paid(
          reward.experience(), Optional.of(DriveRewards.formatAmount(reward.money())));
    }

    @Override
    public void saveRecord(DriveTaskRecord record) {
      records.add(record);
    }

    @Override
    public void tell(UUID playerId, String key, Map<String, String> values) {
      told.add(key);
    }

    @Override
    public String serverId() {
      return null;
    }

    @Override
    public void warn(String message) {}
  }

  private static StationMasterShift shift(
      StationMasterShift.Counts counts, boolean examined, boolean practiced) {
    return new StationMasterShift(
        PLAYER,
        "alice",
        "OP",
        "AAA",
        START,
        START.plusSeconds(1800),
        counts,
        StationMasterShift.EndReason.OFF_DUTY,
        examined,
        practiced);
  }

  private static StationMasterShift.Counts counts(int signals, int auto, int missed, int simRight) {
    return new StationMasterShift.Counts(
        signals, auto, 1, 0, signals, missed, 0, signals, 0, 0, 0, simRight, 0, 0);
  }

  private static StationMasterSettlement settlement(FakeHost host) {
    return new StationMasterSettlement(host, ZoneId.of("UTC"));
  }

  @Test
  void scoreDeductsAutoSignalsWatchesAndDrills() {
    assertEquals(
        100, StationMasterScore.evaluate(shift(counts(5, 0, 0, 0), false, false)).points());
    assertEquals(
        88, StationMasterScore.evaluate(shift(counts(5, 2, 1, 0), false, false)).points(), "5×2+2");
    assertEquals(5.5, StationMasterScore.rewardUnits(shift(counts(5, 3, 0, 1), false, false)));
  }

  @Test
  void aShiftIsRecordedRewardedAndReported() {
    FakeHost host = new FakeHost();
    settlement(host).settle(shift(counts(4, 1, 0, 0), false, false));
    assertEquals(1, host.records.size());
    DriveTaskRecord record = host.records.get(0);
    assertEquals(DriveTaskRecord.MODE_STATION_MASTER, record.mode());
    assertEquals("AAA", record.routeCode());
    assertEquals(95, record.points());
    JsonObject detail = JsonParser.parseString(record.detailJson()).getAsJsonObject();
    assertEquals(4, detail.get("signals").getAsInt());
    assertEquals("station-master", detail.get("role").getAsString());
    assertEquals(1, host.paid.size());
    assertTrue(host.told.contains("drive.station-master.result.summary"));
    assertTrue(host.told.contains("drive.station-master.result.detail"));
  }

  @Test
  void anExamShiftIsRecordedButNotRewardedAndPracticeIsNeither() {
    FakeHost exam = new FakeHost();
    settlement(exam).settle(shift(counts(5, 0, 0, 0), true, false));
    assertEquals(1, exam.records.size());
    assertTrue(exam.paid.isEmpty());

    FakeHost practice = new FakeHost();
    settlement(practice).settle(shift(counts(5, 0, 0, 0), false, true));
    assertTrue(practice.records.isEmpty());
    assertTrue(practice.paid.isEmpty());
  }

  @Test
  void aShiftWithoutTrainsIsNotScored() {
    FakeHost host = new FakeHost();
    settlement(host).settle(shift(counts(0, 0, 0, 0), false, false));
    assertTrue(host.records.isEmpty());
    assertEquals(List.of("drive.station-master.result.no-trains"), host.told);
    assertEquals(ScoreRules.Grade.S, ScoreRules.gradeOf(100, true));
  }
}
