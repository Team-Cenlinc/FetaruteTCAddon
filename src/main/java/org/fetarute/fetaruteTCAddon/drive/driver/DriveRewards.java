package org.fetarute.fetaruteTCAddon.drive.driver;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.fetarute.fetaruteTCAddon.dispatcher.runtime.control.StopAlignment;
import org.fetarute.fetaruteTCAddon.drive.driver.score.ScoreRules;
import org.fetarute.fetaruteTCAddon.drive.driver.score.StopScore;
import org.fetarute.fetaruteTCAddon.drive.driver.score.TaskScore;

/** 一趟驾驶任务的奖励：人工驾驶与 ATO 各自的里程、停站数分别计，ATO 那段打折，再乘评级系数。本类不依赖服务器对象。 */
public final class DriveRewards {

  private static final double BLOCKS_PER_KM = 1000.0;

  /**
   * 奖励。
   *
   * @param experience 经验（取整，不足 1 不发）
   * @param money 钱币（保留两位小数）
   */
  public record Reward(int experience, double money) {
    public static final Reward NONE = new Reward(0, 0.0);

    /** 什么也不发。 */
    public boolean empty() {
      return experience <= 0 && !(money > 0.0);
    }
  }

  private DriveRewards() {}

  /** 按这趟的里程、停站与评级算奖励；奖励关闭时为 {@link Reward#NONE}。 */
  public static Reward of(DriveRewardConfig config, TaskScore score, ScoreRules.Grade grade) {
    if (config == null || !config.enabled() || score == null) {
      return Reward.NONE;
    }
    int manualStops = 0;
    for (StopScore stop : score.stops()) {
      if (stop.outcome() != StopAlignment.Outcome.SKIPPED) {
        manualStops++;
      }
    }
    Basis basis =
        new Basis(
            score.manualBlocks() / BLOCKS_PER_KM,
            manualStops,
            score.atoBlocks() / BLOCKS_PER_KM,
            score.atoStops(),
            config.atoMultiplier(),
            config.gradeMultiplier(grade));
    double experience = basis.amount(config.experiencePerKm(), config.experiencePerStop());
    double money = basis.amount(config.moneyPerKm(), config.moneyPerStop());
    // 浮点乘出来的整数常差一点点（35.99999999999999）：先补一个极小量再取整，钱币四舍五入到分。
    return new Reward(
        (int) Math.floor(Math.max(0.0, experience) + ROUNDING_EPSILON),
        BigDecimal.valueOf(Math.max(0.0, money)).setScale(2, RoundingMode.HALF_UP).doubleValue());
  }

  private static final double ROUNDING_EPSILON = 1.0e-9;

  /** 一趟的里程、停站与系数：经验与钱币用同一个式子，只是每公里、每站的数不同。 */
  private record Basis(
      double manualKm,
      int manualStops,
      double atoKm,
      int atoStops,
      double atoMultiplier,
      double gradeMultiplier) {

    double amount(double perKm, double perStop) {
      return gradeMultiplier
          * (manualKm * perKm
              + manualStops * perStop
              + atoMultiplier * (atoKm * perKm + atoStops * perStop));
    }
  }

  /** 钱币数额写进命令与提示：两位小数，去掉末尾的 0。 */
  public static String formatAmount(double amount) {
    return BigDecimal.valueOf(amount)
        .setScale(2, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString();
  }
}
