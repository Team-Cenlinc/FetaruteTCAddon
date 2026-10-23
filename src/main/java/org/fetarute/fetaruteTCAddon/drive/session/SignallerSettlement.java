package org.fetarute.fetaruteTCAddon.drive.session;

import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.crew.SignallerRecordCodec;
import org.fetarute.fetaruteTCAddon.drive.crew.SignallerScore;
import org.fetarute.fetaruteTCAddon.drive.crew.SignallerShift;
import org.fetarute.fetaruteTCAddon.drive.driver.DriveRewardConfig;
import org.fetarute.fetaruteTCAddon.drive.driver.DriveRewards;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveTaskRecord;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;

/**
 * 信号员班次结算：算成绩、写记录、发奖励，并把成绩告诉在线的信号员。
 *
 * <p>没经手任何车的班次不结算：不写记录、不发奖励，只告诉信号员本班次未经手列车。奖励按经手的车数发（超时代排的不计），ATO 折扣与同车加成都不适用；记录与车掌记录一样在后台写库。
 * 只在主线程调用，不抛异常。
 */
final class SignallerSettlement implements SignallerShift.Settlement {

  /** 结算用到的服务器操作；用例里换成假的。 */
  interface Host {
    /** 当前的奖励配置。 */
    DriveRewardConfig rewards();

    /** 发奖励（经验给在线玩家，钱币按配置发）。 */
    DriveRewardPayer.Paid pay(UUID playerId, String playerName, DriveRewards.Reward reward);

    /** 写一条记录（后台写库）。 */
    void saveRecord(DriveTaskRecord record);

    /** 玩家在线时发一条聊天消息；不在线时什么也不做。 */
    void tell(UUID playerId, String key, Map<String, String> values);

    /** 产生记录的服务器；单服时为 {@code null}。 */
    String serverId();

    void warn(String message);
  }

  private final Host host;
  private final ZoneId zone;

  /**
   * @param zone 记录的运营日按这个时区取下岗时刻的日期（服务器时区）
   */
  SignallerSettlement(Host host, ZoneId zone) {
    this.host = Objects.requireNonNull(host, "host");
    this.zone = Objects.requireNonNull(zone, "zone");
  }

  @Override
  public void settle(SignallerShift shift) {
    if (shift == null) {
      return;
    }
    try {
      settleNow(shift);
    } catch (RuntimeException ex) {
      host.warn("信号员班次结算失败: " + shift.playerName() + " " + shift.zone() + " " + ex);
    }
  }

  private void settleNow(SignallerShift shift) {
    if (shift.trainsHandled() <= 0) {
      host.tell(shift.player(), "drive.signaller.result.no-trains", Map.of("zone", shift.zone()));
      return;
    }
    ScoreRules.Result result = SignallerScore.evaluate(shift);
    host.saveRecord(
        SignallerRecordCodec.record(UUID.randomUUID(), host.serverId(), shift, result, zone));
    DriveRewardConfig rewards = host.rewards();
    DriveRewards.Reward reward =
        DriveRewards.signaller(
            rewards,
            rewards.signallerTrainRatio(),
            SignallerScore.rewardedTrains(shift),
            result.grade());
    DriveRewardPayer.Paid paid =
        reward.empty()
            ? new DriveRewardPayer.Paid(0, Optional.empty())
            : host.pay(shift.player(), shift.playerName(), reward);
    host.tell(
        shift.player(),
        "drive.signaller.result.summary",
        Map.of(
            "zone",
            shift.zone(),
            "points",
            String.valueOf(result.points()),
            "grade",
            result.grade().name(),
            "trains",
            String.valueOf(shift.trainsHandled()),
            "auto",
            String.valueOf(shift.autoRouted())));
    DriveSessionManager.rewardMessageKey(paid, false)
        .ifPresent(
            key ->
                host.tell(
                    shift.player(),
                    key,
                    Map.of(
                        "experience",
                        String.valueOf(paid.experience()),
                        "money",
                        paid.money().orElse(""),
                        "currency",
                        rewards.currencyName())));
  }
}
