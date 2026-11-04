package org.fetarute.fetaruteTCAddon.drive.session;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.drive.driver.DriveRewardConfig;
import org.fetarute.fetaruteTCAddon.drive.driver.DriveRewards;
import org.fetarute.fetaruteTCAddon.drive.driver.record.DriveTaskRecord;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;

/** 不跟车的岗位（信号员、站长）下岗结算用到的服务器操作；用例里换成假的。 */
interface SettlementHost {

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

  /** 结算失败等告警。 */
  void warn(String message);

  /**
   * 按车数发这一班的奖励（见 {@link DriveRewards#perTrain}）；奖励为零时不发。
   *
   * @param trainRatio 这个岗位每列车的单价倍数
   * @param trains 计奖励的车数
   */
  default DriveRewardPayer.Paid payPerTrain(
      UUID playerId, String playerName, double trainRatio, double trains, ScoreRules.Grade grade) {
    DriveRewards.Reward reward = DriveRewards.perTrain(rewards(), trainRatio, trains, grade);
    return reward.empty()
        ? new DriveRewardPayer.Paid(0, Optional.empty())
        : pay(playerId, playerName, reward);
  }

  /** 告诉玩家发了多少奖励；什么也没发时不说。 */
  default void tellPaid(UUID playerId, DriveRewardPayer.Paid paid) {
    DriveSessionManager.rewardMessageKey(paid, false)
        .ifPresent(
            key ->
                tell(
                    playerId,
                    key,
                    Map.of(
                        "experience",
                        String.valueOf(paid.experience()),
                        "money",
                        paid.money().orElse(""),
                        "currency",
                        rewards().currencyName())));
  }
}
