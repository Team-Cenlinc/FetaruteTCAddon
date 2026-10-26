package org.fetarute.fetaruteTCAddon.drive.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.crew.SignallerShift;
import org.fetarute.fetaruteTCAddon.drive.driver.DriveRewardConfig;
import org.fetarute.fetaruteTCAddon.drive.driver.DriveRewards;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveTaskRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("信号员班次结算：成绩、奖励、记录与提示")
class SignallerSettlementTest {

  private static final UUID PLAYER = UUID.randomUUID();
  private static final Instant START = Instant.parse("2026-10-20T08:00:00Z");

  /** 记下结算做过的事。 */
  private static final class FakeHost implements SignallerSettlement.Host {
    DriveRewardConfig rewards = DriveRewardConfig.defaults();
    final List<DriveRewards.Reward> paid = new ArrayList<>();
    final List<DriveTaskRecord> records = new ArrayList<>();
    final List<String> told = new ArrayList<>();
    final List<Map<String, String>> values = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();
    boolean failSave;

    @Override
    public DriveRewardConfig rewards() {
      return rewards;
    }

    @Override
    public DriveRewardPayer.Paid pay(UUID playerId, String playerName, DriveRewards.Reward reward) {
      paid.add(reward);
      return new DriveRewardPayer.Paid(
          reward.experience(), Optional.of(DriveRewards.formatAmount(reward.money())));
    }

    @Override
    public void saveRecord(DriveTaskRecord record) {
      if (failSave) {
        throw new IllegalStateException("db down");
      }
      records.add(record);
    }

    @Override
    public void tell(UUID playerId, String key, Map<String, String> values) {
      told.add(key);
      this.values.add(values);
    }

    @Override
    public String serverId() {
      return null;
    }

    @Override
    public void warn(String message) {
      warnings.add(message);
    }
  }

  private static SignallerShift shift(int handled, int autoRouted, int missed) {
    return new SignallerShift(
        PLAYER,
        "Sinapole",
        "SURC",
        "WSD",
        START,
        START.plusSeconds(1800),
        SignallerShift.Counts.builder()
            .trainsHandled(handled)
            .routesSet(5)
            .autoRouted(autoRouted)
            .autoPassed(3)
            .preRouted(1)
            .callsAnswered(2)
            .callsMissed(missed)
            .build(),
        SignallerShift.EndReason.LEFT_CONSOLE);
  }

  private static SignallerSettlement settlement(FakeHost host) {
    return new SignallerSettlement(host, ZoneId.of("UTC"));
  }

  @Test
  @DisplayName("没经手任何车：不写记录、不发奖励，只告诉信号员本班次未经手列车")
  void noTrainsNoSettlement() {
    FakeHost host = new FakeHost();
    settlement(host).settle(shift(0, 0, 3));
    assertTrue(host.records.isEmpty());
    assertTrue(host.paid.isEmpty());
    assertEquals(List.of("drive.signaller.result.no-trains"), host.told);
    assertEquals("WSD", host.values.get(0).get("zone"));
  }

  @Test
  @DisplayName("经手了车：写一条信号员记录、按计奖励的车数发奖励、告诉成绩与奖励")
  void settlesShift() {
    FakeHost host = new FakeHost();
    settlement(host).settle(shift(10, 2, 1));

    assertEquals(1, host.records.size());
    DriveTaskRecord record = host.records.get(0);
    assertEquals(DriveTaskRecord.MODE_SIGNALLER, record.mode());
    assertEquals("LEFT_CONSOLE", record.state());
    assertEquals(85, record.points());
    assertEquals("A", record.grade());

    // 计奖励只算信号员亲手排的：10 − 2（代排）− 3（自动通过）= 5 列，预排命中 1 列加半列，共 5.5 列：
    // 经验 1.2 × 5.5 × 2 = 13.2 → 13，钱币 1.2 × 5.5 × 5 = 33
    assertEquals(1, host.paid.size());
    assertEquals(13, host.paid.get(0).experience());
    assertEquals(33.0, host.paid.get(0).money(), 1e-9);
    assertFalse(host.paid.get(0).crewBonus());

    assertEquals(
        List.of(
            "drive.signaller.result.summary",
            "drive.signaller.result.detail",
            "drive.task.reward.both"),
        host.told);
    Map<String, String> summary = host.values.get(0);
    assertEquals("85", summary.get("points"));
    assertEquals("A", summary.get("grade"));
    assertEquals("10", summary.get("trains"));
    assertEquals("2", summary.get("auto"));
    assertEquals("1", host.values.get(1).get("pre"));
    assertEquals("13", host.values.get(2).get("experience"));
    assertEquals("33", host.values.get(2).get("money"));
  }

  @Test
  @DisplayName("奖励关闭或经手的车全是超时代排：照常写记录与告诉成绩，不发奖励")
  void noRewardWhenDisabledOrAllAutoRouted() {
    FakeHost off = new FakeHost();
    off.rewards = new DriveRewardConfig(false, 10, 2, 20, 5, 0.5, Map.of(), "", "FRD", 3, 0.5, 1.0);
    settlement(off).settle(shift(10, 0, 0));
    assertEquals(1, off.records.size());
    assertTrue(off.paid.isEmpty());
    assertEquals(
        List.of("drive.signaller.result.summary", "drive.signaller.result.detail"), off.told);

    FakeHost allAuto = new FakeHost();
    settlement(allAuto).settle(shift(3, 3, 0));
    assertEquals(1, allAuto.records.size());
    assertEquals(85, allAuto.records.get(0).points());
    assertTrue(allAuto.paid.isEmpty());
  }

  @Test
  @DisplayName("信号员考试的这一班写记录、不发奖励、不另报成绩；练习的这一班什么都不做")
  void examAndPracticeShifts() {
    SignallerShift plain = shift(10, 2, 1);
    SignallerShift exam = withExam(plain, true, false);
    FakeHost examHost = new FakeHost();
    settlement(examHost).settle(exam);
    assertEquals(1, examHost.records.size());
    assertTrue(examHost.paid.isEmpty());
    assertTrue(examHost.told.isEmpty(), "考官按考试标准讲评，这里不再报一遍");

    FakeHost practiceHost = new FakeHost();
    settlement(practiceHost).settle(withExam(plain, false, true));
    assertTrue(practiceHost.records.isEmpty());
    assertTrue(practiceHost.paid.isEmpty());
    assertTrue(practiceHost.told.isEmpty());

    FakeHost emptyExam = new FakeHost();
    settlement(emptyExam).settle(withExam(shift(0, 0, 0), true, false));
    assertTrue(emptyExam.told.isEmpty(), "考试中没经手车也不报“未经手列车”");
  }

  private static SignallerShift withExam(SignallerShift s, boolean examined, boolean practiced) {
    SignallerShift marked =
        new SignallerShift(
            s.player(),
            s.playerName(),
            s.operator(),
            s.zone(),
            s.start(),
            s.end(),
            s.counts(),
            s.endReason(),
            examined,
            practiced);
    assertTrue(marked.examined(), "练习也算考官在场");
    return marked;
  }

  @Test
  @DisplayName("结算出错不抛给信号员模块，只记日志")
  void neverThrows() {
    FakeHost host = new FakeHost();
    host.failSave = true;
    assertDoesNotThrow(() -> settlement(host).settle(shift(4, 0, 0)));
    assertEquals(1, host.warnings.size());
    assertDoesNotThrow(() -> settlement(host).settle(null));
  }

  @Test
  @DisplayName("出过模拟事件的班次多报一行：答对、答错、超时各几次")
  void simulatedEventsGetTheirOwnLine() {
    FakeHost host = new FakeHost();
    SignallerShift shift =
        new SignallerShift(
            PLAYER,
            "Sinapole",
            "SURC",
            "WSD",
            START,
            START.plusSeconds(1800),
            SignallerShift.Counts.builder()
                .trainsHandled(4)
                .simRight(3)
                .simWrong(1)
                .simMissed(2)
                .build(),
            SignallerShift.EndReason.LEFT_CONSOLE);

    settlement(host).settle(shift);

    int at = host.told.indexOf("drive.signaller.result.simulated");
    assertEquals(host.told.indexOf("drive.signaller.result.detail") + 1, at, "紧跟明细一行");
    assertEquals(Map.of("right", "3", "wrong", "1", "missed", "2"), host.values.get(at));
  }
}
