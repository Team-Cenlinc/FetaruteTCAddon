package org.fetarute.fetaruteTCAddon.drive.session;

import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.crew.StationMasterRecordCodec;
import org.fetarute.fetaruteTCAddon.drive.crew.StationMasterScore;
import org.fetarute.fetaruteTCAddon.drive.crew.StationMasterShift;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;

/**
 * 站长班次结算：算成绩、写记录、发奖励，并把成绩告诉在线的站长。与信号员班次结算同一套做法。
 *
 * <p>一列车也没经手的班次不结算：不写记录、不发奖励，只告诉站长本班次未经手列车。奖励按亲手发的发车信号算（超时代发的不计），模拟事件处置正确一次折半列。
 * 考试的这一班写记录、不发奖励，也不另报成绩（考官按考试标准讲评）；练习的这一班不写记录也不发奖励。只在主线程调用，不抛异常。
 */
final class StationMasterSettlement implements StationMasterShift.Settlement {

  private final SettlementHost host;
  private final ZoneId zone;

  /**
   * @param zone 记录的运营日按这个时区取下岗时刻的日期（服务器时区）
   */
  StationMasterSettlement(SettlementHost host, ZoneId zone) {
    this.host = Objects.requireNonNull(host, "host");
    this.zone = Objects.requireNonNull(zone, "zone");
  }

  @Override
  public void settle(StationMasterShift shift) {
    if (shift == null) {
      return;
    }
    try {
      settleNow(shift);
    } catch (RuntimeException ex) {
      host.warn("站长班次结算失败: " + shift.playerName() + " " + shift.station() + " " + ex);
    }
  }

  private void settleNow(StationMasterShift shift) {
    if (shift.practiced()) {
      return;
    }
    if (shift.trainsHandled() <= 0) {
      if (!shift.examined()) {
        host.tell(
            shift.player(),
            "drive.station-master.result.no-trains",
            Map.of("station", shift.station()));
      }
      return;
    }
    ScoreRules.Result result = StationMasterScore.evaluate(shift);
    host.saveRecord(
        StationMasterRecordCodec.record(UUID.randomUUID(), host.serverId(), shift, result, zone));
    if (shift.examined()) {
      return;
    }
    DriveRewardPayer.Paid paid =
        host.payPerTrain(
            shift.player(),
            shift.playerName(),
            host.rewards().stationMasterTrainRatio(),
            StationMasterScore.rewardUnits(shift),
            result.grade());
    StationMasterShift.Counts counts = shift.counts();
    host.tell(
        shift.player(),
        "drive.station-master.result.summary",
        Map.of(
            "station",
            shift.station(),
            "points",
            String.valueOf(result.points()),
            "grade",
            result.grade().name(),
            "signals",
            String.valueOf(counts.signals()),
            "auto",
            String.valueOf(counts.autoSignals())));
    host.tell(
        shift.player(),
        "drive.station-master.result.detail",
        Map.of(
            "watched",
            String.valueOf(counts.arrivalOk() + counts.departureOk()),
            "missed",
            String.valueOf(counts.arrivalMissed() + counts.departureMissed()),
            "absent",
            String.valueOf(counts.arrivalAbsent()),
            "holds",
            String.valueOf(counts.holds()),
            "expired",
            String.valueOf(counts.holdsExpired()),
            "emergencies",
            String.valueOf(counts.emergencies()),
            "cleared",
            String.valueOf(counts.clearedRiders())));
    if (counts.simRight() + counts.simWrong() + counts.simMissed() > 0) {
      host.tell(
          shift.player(),
          "drive.station-master.result.simulated",
          Map.of(
              "right",
              String.valueOf(counts.simRight()),
              "wrong",
              String.valueOf(counts.simWrong()),
              "missed",
              String.valueOf(counts.simMissed())));
    }
    host.tellPaid(shift.player(), paid);
  }
}
